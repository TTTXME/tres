// GLInterface.readProgram (processing/opengl/GLInterface.java) one to one: the post shader sources as GLProg hands them to
// the driver (#import of shaders/utils/import_*.glsl, #define overrides, #version 310 es + highp int when unversioned).
#pragma once

#include <fstream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace scam_post {

using Defines = std::vector<std::pair<std::string, std::string>>;

inline std::string readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    std::stringstream s;
    s << f.rdbuf();
    return s.str();
}

inline const std::string& glVersion() { static const std::string v = "#version 310 es\n"; return v; }
inline const std::string& highpInt() { static const std::string v = "precision highp int;\n"; return v; }

/// shadersRoot: the folder that holds why/, utils/, ... (app/src/main/assets/shaders).
inline std::string readProgram(const std::string& code, const Defines* defines, const std::string& shadersRoot) {
    std::string source;
    int linecnt = 0;
    bool versioned = false;
    long extensionEnd = -1;
    int extensionLine = 0;
    std::istringstream in(code);
    std::string line;
    while (std::getline(in, line)) {
        linecnt++;
        if (line.find("#version") != std::string::npos) versioned = true;
        if (line.find("#import") != std::string::npos) {
            std::string imported;
            if (line.find("//") == std::string::npos) {
                std::string name;
                for (char ch : line) {
                    if (ch == '#' || ch == '\n') continue;
                    name += ch == ' ' ? '_' : ch;
                }
                imported = readFile(shadersRoot + "/utils/" + name + ".glsl");
            }
            if (!imported.empty()) {
                source += "#line 1\n";
                source += imported;
                source += "\n";
                source += "#line " + std::to_string(linecnt + 1) + "\n";
            }
            continue;
        }
        std::string out = line;
        if (line.find("#define") != std::string::npos && defines) {
            for (const auto& d : *defines) {
                if (line.find(" " + d.first + " ") != std::string::npos) {
                    out = "#define " + d.first + " " + d.second;
                    break;
                }
            }
        }
        source += out + "\n";
        const size_t first = line.find_first_not_of(" \t");
        if (first != std::string::npos && line.compare(first, 10, "#extension") == 0) {
            extensionEnd = long(source.size());
            extensionLine = linecnt;
        }
    }
    if (versioned) return source;
    if (extensionEnd >= 0) {
        source.insert(size_t(extensionEnd), highpInt() + "#line " + std::to_string(extensionLine + 1) + "\n");
        return glVersion() + "\n" + "#line 1\n" + source;
    }
    return glVersion() + "\n" + highpInt() + "#line 1\n" + source;
}

inline std::string assetProgram(const std::string& name, const Defines* defines, const std::string& shadersRoot) {
    return readProgram(readFile(shadersRoot + "/" + name + ".glsl"), defines, shadersRoot);
}

/// The post passes' vertex shader: GLProg's full-viewport square (GLSquareModel, a 4-vertex triangle strip at z 0) made
/// from gl_VertexID instead of a vertex buffer - the same coverage, so every fragment and its gl_FragCoord are the same.
inline const std::string& vertexSource() {
    static const std::string v = glVersion() + "precision mediump float;\n"
        "void main() {\n"
        "    vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;\n"
        "    gl_Position = vec4(p, 0.0, 1.0);\n"
        "}\n";
    return v;
}

} // namespace scam_post
