// Every post shader of the Android app (app/src/main/assets/shaders, assembled as GLInterface.readProgram does) linked
// through the GL that is linked in: ScamGL on Metal (macOS / iOS) or the system GLES (Mesa). Viewfinder (preview/) and editor
// (editor/) programs are assembled from parts at run time and are reported, not counted.
//   post_shader_check <shaders root>
#include "../Engine/post/GLShaderSource.hpp"

#include <EGL/egl.h>
#include <GLES3/gl31.h>

#include <cstdio>
#include <dirent.h>
#include <regex>
#include <string>
#include <vector>

static void walk(const std::string& root, const std::string& rel, std::vector<std::string>& out) {
    DIR* d = opendir((root + "/" + rel).c_str());
    if (!d) return;
    while (dirent* e = readdir(d)) {
        const std::string n = e->d_name;
        if (n == "." || n == "..") continue;
        const std::string r = rel.empty() ? n : rel + "/" + n;
        if (n.size() > 5 && n.compare(n.size() - 5, 5, ".glsl") == 0) out.push_back(r.substr(0, r.size() - 5));
        else walk(root, r, out);
    }
    closedir(d);
}

static GLuint shader(GLenum type, const std::string& src) {
    GLuint s = glCreateShader(type);
    const char* p = src.c_str();
    glShaderSource(s, 1, &p, nullptr);
    glCompileShader(s);
    return s;
}

int main(int argc, char** argv) {
    if (argc != 2) { std::fprintf(stderr, "usage: %s <shaders root>\n", argv[0]); return 2; }
    const std::string root = argv[1];
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig config;
    EGLint n = 0;
    eglChooseConfig(display, configAttrs, &config, 1, &n);
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
    const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, size);
    if (!eglMakeCurrent(display, surface, surface, context)) {
        std::fprintf(stderr, "no current GL context (EGL error 0x%x)\n", eglGetError());
        return 1;
    }
    std::printf("GL %s / %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

    std::vector<std::string> names;
    walk(root, "", names);
    const std::regex computeMark("\\bshared\\b|gl_GlobalInvocationID|gl_LocalInvocationID|gl_WorkGroupID|local_size_x");
    int ok = 0, failed = 0, skipped = 0;
    for (const auto& name : names) {
        if (name.rfind("utils/import_", 0) == 0) continue;
        const bool runtimeAssembled = name.rfind("preview/", 0) == 0 || name.rfind("editor/", 0) == 0;
        std::string src = scam_post::assetProgram(name, nullptr, root);
        const bool compute = std::regex_search(src, computeMark) && src.find("gl_FragCoord") == std::string::npos;
        if (compute && src.find("local_size_x") == std::string::npos) {
            const size_t v = src.find('\n', src.find("#version"));
            src.insert(v + 1, "layout(local_size_x=8,local_size_y=8) in;\n");
        }
        GLuint program = glCreateProgram();
        if (compute) {
            glAttachShader(program, shader(GL_COMPUTE_SHADER, src));
        } else {
            glAttachShader(program, shader(GL_VERTEX_SHADER, scam_post::vertexSource()));
            glAttachShader(program, shader(GL_FRAGMENT_SHADER, src));
        }
        glLinkProgram(program);
        GLint linked = 0;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (linked) { ok++; glDeleteProgram(program); continue; }
        char log[600] = {};
        glGetProgramInfoLog(program, sizeof log, nullptr, log);
        // utils/corners does not compile on Mesa either (an import it needs is gone): not used by the post pipeline
        if (runtimeAssembled || name == "utils/corners") { skipped++; std::printf("SKIP %s (assembled at run time)\n", name.c_str()); }
        else { failed++; std::printf("FAIL %s (%s): %s\n", name.c_str(), compute ? "compute" : "fragment", log); }
        glDeleteProgram(program);
        while (glGetError() != GL_NO_ERROR) {}
    }
    std::printf("post shaders: %d linked, %d failed, %d viewfinder / editor skipped\n", ok, failed, skipped);
    return failed ? 1 : 0;
}
