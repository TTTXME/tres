package com.scamera.app.processing.opengl;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.render.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static com.scamera.app.processing.opengl.GLProg.glVersion;

/**
 * What a pipeline pass works with (its program, the processing context, the shot's parameters) and the shader source
 * preprocessor: {@code #import name} pulls in assets/shaders/utils/import_name.glsl, {@code defines} override #define values,
 * a source without its own #version gets the ES 3.10 header and highp ints; getLayouts reads a compute shader's bindings.
 */
public class GLInterface {
    /** P317: the int precision every fragment program gets (Mali's mediump int is 16 bit). */
    static final String HIGHP_INT = "precision highp int;\n";

    /** The program every pass of this context draws with. */
    public final GLProg prog;
    /** The shot's parameters (a pass reads them through its pipeline). */
    public Parameters shotParams;
    /** The block processing context (null for a plain context) or the plain one, and their helpers. */
    public GLCoreBlockProcessing blockCtx;
    public GLContext plainCtx;
    public GLUtils kitUtils;

    public GLInterface(GLCoreBlockProcessing processing) {
        blockCtx = processing;
        prog = processing.program;
        kitUtils = new GLUtils(processing);
    }

    public GLInterface(GLContext context) {
        plainCtx = context;
        prog = context.program;
    }

    public static String loadShader(String code) {
        return loadShader(code, null);
    }

    public static String loadShader(int rawResource) {
        return loadShader(rawResource, null);
    }

    public static String loadShader(int rawResource, ArrayList<String[]> defines) {
        return readProgram(new BufferedReader(new InputStreamReader(ScameraApp.getResourcesStatic().openRawResource(rawResource))), defines);
    }

    public static String loadShader(String code, ArrayList<String[]> defines) {
        return readProgram(new BufferedReader(new StringReader(code)), defines);
    }

    /**
     * The blocks of a compute shader by name: {@code layout(local_size_x = .., ...) in;} under "in", every other layout line
     * (image, buffer) under the word before its '{' or its ';' with its binding. P317: a one-line block ("buffer aeHistY {
     * uint histY[]; };") is named by the word before its '{' - it was keyed "}" and bound nothing.
     */
    public static Map<String, GLComputeLayout> getLayouts(String program) {
        final Map<String, GLComputeLayout> layouts = new HashMap<>();
        final Iterator<String> lines = new BufferedReader(new StringReader(program)).lines().iterator();
        while (lines.hasNext()) {
            String code = lines.next();
            final int comment = code.indexOf("//");
            if (comment >= 0) code = code.substring(0, comment);
            if (!code.contains("layout")) continue;
            final int brace = code.indexOf('{');
            if (brace >= 0) code = code.substring(0, brace);
            final String[] words = code.trim().split("\\s+");
            final String name = words[words.length - 1].replace(";", "").replace("\n", "");
            final int open = code.indexOf('(');
            final int close = code.lastIndexOf(')');
            final String[] qualifiers = code.substring(open >= 0 ? open + 1 : 1, close >= 0 ? close : code.length() - 1).split(",");
            layouts.put(name, name.equals("in")
                    ? new GLComputeLayout(qualifier(qualifiers, "local_size_x"), qualifier(qualifiers, "local_size_y"),
                            qualifier(qualifiers, "local_size_z"))
                    : new GLComputeLayout(qualifier(qualifiers, "binding")));
        }
        return layouts;
    }

    /** The integer of {@code key = n} among the layout qualifiers, 0 when it is not there. */
    private static int qualifier(String[] qualifiers, String key) {
        for (String q : qualifiers) {
            final String[] keyValue = q.replace(" ", "").split("=");
            if (keyValue[0].equals(key)) return Integer.parseInt(keyValue[1]);
        }
        return 0;
    }

    /** The asset of an {@code #import} line: "#import interpolation" is shaders/utils/import_interpolation.glsl. */
    private static String importAsset(String line) {
        return "shaders/utils/" + line.replace("#", "").replace(" ", "_").replace("\n", "") + ".glsl";
    }

    /**
     * The source of {@code reader} preprocessed: imports inlined between #line markers (an import line with a comment is
     * dropped), a {@code #define NAME } line of a name in {@code defines} replaced by {@code #define NAME value}, and - for a
     * source without #version - the ES 3.10 header and highp int after its leading #extension directives.
     */
    public static String readProgram(BufferedReader reader, ArrayList<String[]> defines) {
        final StringBuilder source = new StringBuilder();
        boolean versioned = false;
        // P317: where the leading #extension directives end (the precision statement must follow them)
        int extensionEnd = -1, extensionLine = 0;
        int lineNumber = 0;
        final Iterator<String> lines = reader.lines().iterator();
        while (lines.hasNext()) {
            final String line = lines.next();
            lineNumber++;
            versioned |= line.contains("#version");
            if (line.contains("#import")) {
                final String imported = line.contains("//") ? "" : ScameraApp.getAssetLoader().getString(importAsset(line));
                if (!imported.isEmpty()) {
                    source.append("#line 1\n").append(imported).append('\n').append("#line ").append(lineNumber + 1).append('\n');
                }
                continue;
            }
            source.append(defined(line, defines)).append('\n');
            if (line.trim().startsWith("#extension")) {
                extensionEnd = source.length();
                extensionLine = lineNumber;
            }
        }
        if (versioned) return source.toString();
        // P317 (owner, 2026-10-21: «Исправь работу на всех мтк»): a fragment shader's ints default to mediump (GLSL ES 3.10
        // 4.7.4) - 16 bit on Mali (coordinates in 2 / 4 px steps above 2048 / 4096 px; the film grain hash went to +inf:
        // white photos on the X300 Pro)
        if (extensionEnd < 0) return glVersion + "\n" + HIGHP_INT + "#line 1\n" + source;
        source.insert(extensionEnd, HIGHP_INT + "#line " + (extensionLine + 1) + "\n");
        return glVersion + "\n" + "#line 1\n" + source;
    }

    /** {@code line}, or the override of the first define it names ({@code " NAME "} in a #define line). */
    private static String defined(String line, ArrayList<String[]> defines) {
        if (defines == null || !line.contains("#define")) return line;
        for (String[] d : defines) if (line.contains(" " + d[0] + " ")) return "#define " + d[0] + " " + d[1];
        return line;
    }
}
