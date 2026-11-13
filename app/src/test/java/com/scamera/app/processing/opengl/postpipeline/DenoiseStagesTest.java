package com.scamera.app.processing.opengl.postpipeline;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P600 (owner, 2026-11-10: «при его отключении абсолютно весь шумодав отключается ПОЛНОСТЬЮ»): the switch «Все шумодавы» reaches
 * every denoise stage of the photo route (DenoiseStages), and a new stage cannot bypass it - the sources are scanned:
 * <ul>
 * <li>every shader of the denoise folders is a stage's or listed in DenoiseStages.NOT_DENOISE with its reason;</li>
 * <li>every class of the app that runs a stage's shader asks DenoiseStages.on();</li>
 * <li>the worker's denoise stages (scam-hybrid.h kHybridDenoiseStages, hybridDenoiseSwitch) are the registry's;</li>
 * <li>off: the worker gets "denoise 0", on: no line (its tuning as before).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DenoiseStagesTest {
    private static final String MAIN = "src/main/";
    private static final String SHADERS = MAIN + "assets/shaders/";
    /** The folders whose shaders are denoise-like by name; every file there is a stage's or NOT_DENOISE. */
    private static final String[] DENOISE_DIRS = {"scamdn", "chromadn", "resolvedn"};
    /** Single denoise shaders outside them. */
    private static final String[] DENOISE_FILES = {"why/bracket_dn", "why/msdn", "editor/nr"};

    private SettingsManager manager;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
    }

    @After public void tearDown() {
        PreferenceKeys.endShotProfile();
        if (camera != null) camera.close();
    }

    private static File file(String path) {
        final File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(file(path).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static Set<String> stageShaders() {
        final Set<String> out = new TreeSet<>();
        for (DenoiseStages.Stage s : DenoiseStages.Stage.values()) out.addAll(Arrays.asList(s.shaders));
        return out;
    }

    /** On (the default): the worker gets no line, its tuning stays the one before P600; off: "denoise 0" and every stage off. */
    @Test public void theSwitchReachesTheWorker() {
        assertTrue(PreferenceKeys.isHybridDenoiseEnabled());
        assertTrue(DenoiseStages.on());
        assertEquals("", DenoiseStages.workerTuning());
        assertEquals("on", DenoiseStages.summary());
        final String on = PreferenceKeys.hybridTuningText();
        for (String line : on.split("\n")) assertFalse(on, line.startsWith("denoise "));
        manager.set("default_scope", "pref_scam_hybrid_denoise", false);
        assertFalse(DenoiseStages.on());
        assertEquals("denoise 0\n", DenoiseStages.workerTuning());
        final String off = PreferenceKeys.hybridTuningText();
        assertTrue(off, off.endsWith("\ndenoise 0\n"));
        assertEquals("only the switch's line differs", on + "denoise 0\n", off);
        final String summary = DenoiseStages.summary();
        for (DenoiseStages.Stage s : DenoiseStages.Stage.values())
            assertTrue(summary, summary.contains(s.name().toLowerCase(java.util.Locale.ROOT)));
        manager.set("default_scope", "pref_scam_hybrid_denoise", true);
        assertEquals(on, PreferenceKeys.hybridTuningText());
    }

    /** Every shader of the denoise folders is a stage's (and exists) or NOT_DENOISE's, never both. */
    @Test public void everyDenoiseShaderIsRegistered() throws IOException {
        final Set<String> stages = stageShaders();
        final List<String> found = new ArrayList<>();
        for (String dir : DENOISE_DIRS) {
            final File[] files = file(SHADERS + dir).listFiles((d, n) -> n.endsWith(".glsl"));
            assertTrue(dir, files != null && files.length > 0);
            for (File f : files) found.add(dir + "/" + f.getName().replace(".glsl", ""));
        }
        for (String f : DENOISE_FILES) {
            assertTrue(f, file(SHADERS + f + ".glsl").isFile());
            found.add(f);
        }
        for (String name : found)
            assertTrue("a denoise shader neither a stage's nor NOT_DENOISE's: " + name,
                    stages.contains(name) ^ DenoiseStages.NOT_DENOISE.containsKey(name));
        for (String name : stages) assertTrue("no such shader: " + name, file(SHADERS + name + ".glsl").isFile());
        for (String name : DenoiseStages.NOT_DENOISE.keySet()) assertTrue("no such shader: " + name, found.contains(name));
    }

    private static final Pattern ASSET = Pattern.compile("\"((?:scamdn|chromadn|resolvedn|why|editor)/[a-z0-9_]*)\"");

    /** A class of the app that names a stage's shader (or the folder prefix of one) asks DenoiseStages.on(). */
    @Test public void everyClassRunningAStageAsksTheSwitch() throws IOException {
        final Set<String> stages = stageShaders();
        final Set<String> users = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(file(MAIN + "java").toPath())) {
            for (Path p : (Iterable<Path>) paths.filter(x -> x.toString().endsWith(".java"))::iterator) {
                if (p.getFileName().toString().equals("DenoiseStages.java")) continue; // the registry names them
                final String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                final Matcher m = ASSET.matcher(src);
                boolean uses = false;
                while (m.find()) {
                    final String name = m.group(1);
                    for (String s : stages) if (name.endsWith("/") ? s.startsWith(name) : s.equals(name)) uses = true;
                }
                if (!uses) continue;
                users.add(p.getFileName().toString());
                assertTrue(p + " runs a denoise stage without asking DenoiseStages.on()", src.contains("DenoiseStages.on()"));
            }
        }
        // the stages' own classes are among them (the scan sees the shaders where they run)
        assertTrue(users.toString(), users.containsAll(Arrays.asList("WhyResolveDenoise.java", "WhyMsDenoise.java", "WhyFusion.java")));
        for (DenoiseStages.Stage s : DenoiseStages.Stage.values()) {
            if (s.where != DenoiseStages.Where.POST || s.shaders.length == 0) continue;
            assertTrue(s + ": its node asks the switch", read(MAIN + "java/com/scamera/app/processing/opengl/postpipeline/"
                    + s.owner + ".java").contains("DenoiseStages.on()"));
        }
    }

    /** The pipeline consults the registry: the pyramid node only when on, the Resolve node passes the merge when off. */
    @Test public void thePipelineAsksTheRegistry() throws IOException {
        final String dir = MAIN + "java/com/scamera/app/processing/opengl/postpipeline/";
        final String pipeline = read(dir + "PostPipeline.java");
        assertTrue(pipeline.contains("if (DenoiseStages.on()) add(new WhyMsDenoise());"));
        assertTrue(pipeline.contains("+\" denoise=\"+DenoiseStages.summary()"));
        final String resolve = read(dir + "ScamHdrDenoise.java");
        final int gate = resolve.indexOf("if (!DenoiseStages.on()) {"), run = resolve.indexOf("WhyResolveDenoise.process(");
        assertTrue("the Resolve runs only behind the switch", gate > 0 && run > gate);
        assertTrue(read(dir + "WhyFusion.java").contains("float strength = whyDenoise(hostPipeline.shotParams) ? WhyTone.value(\"bracket_denoise\", 0f) : 0f;"));
        // LIFTED_CHROMA: the combine's chroma denoise of lifted shadows is 0 wherever its uniform is set
        final Pattern dn = Pattern.compile("\"chromaDnU\",\\s*([^)]*)\\)");
        int set = 0;
        for (String f : new String[]{"WhyCombine.java", "LiveWhyTone.java"}) {
            final Matcher m = dn.matcher(read(dir + f));
            while (m.find()) {
                assertEquals(f, "0f", m.group(1).trim());
                set++;
            }
        }
        assertEquals("chromaDnU set in WhyCombine and LiveWhyTone", 2, set);
    }

    /** The worker's stages (scam-hybrid.h) are the registry's WORKER stages, each turned off by hybridDenoiseSwitch. */
    @Test public void theWorkersStagesAreTheRegistrys() throws IOException {
        final String h = read(MAIN + "cpp/scam-hybrid.h");
        final Matcher list = Pattern.compile("kHybridDenoiseStages\\[\\]=\\{([^}]*)\\}").matcher(h);
        assertTrue(list.find());
        final Set<String> worker = new HashSet<>();
        for (String s : list.group(1).split(",")) worker.add(s.trim().replace("\"", ""));
        final Set<String> registry = new HashSet<>();
        for (DenoiseStages.Stage s : DenoiseStages.Stage.values()) if (s.where == DenoiseStages.Where.WORKER) registry.add(s.owner);
        assertEquals(registry, worker);
        final int at = h.indexOf("inline bool hybridDenoiseSwitch(");
        assertTrue(at > 0);
        final String body = h.substring(at, h.indexOf("\n}\n", at));
        assertTrue(body, body.contains("if(t.denoise!=0)return false;") && body.contains("t.denoise=-1;"));
        for (String k : worker) assertTrue(k + " is not turned off", Pattern.compile("t\\." + k + "=0(\\.f)?;").matcher(body).find());
        // the tuning file's line is read and applied after every line; a tuning built in code is applied at the merge's entry
        assertTrue(h.contains("||set(\"denoise\",nullptr,&t.denoise)"));
        assertTrue(h.contains("hybridDenoiseSwitch(t,report); // P600: after every line"));
        assertTrue(h.contains("if(tune.denoise==0){\n        HybridTuning t=tune;hybridDenoiseSwitch(t,report);"));
    }
}
