#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <random>

using namespace scam;

struct alignas(16) CullInput {
    float p[4], center[4], params[4];
};

static GLuint shaderProgram(const std::string& source) {
    const GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    const char* text = source.c_str();
    glShaderSource(shader, 1, &text, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096]; glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        throw std::runtime_error(log);
    }
    const GLuint program = glCreateProgram();
    glAttachShader(program, shader); glLinkProgram(program); glDeleteShader(shader);
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) throw std::runtime_error("culling check link failed");
    return program;
}

int main() {
    // Compile the production branch itself, and evaluate its original four tap weights independently.
    const std::string fast = kHybMergeMosaicFast;
    const auto first = fast.find("        if(skipSmall&&kD.z>=0.0&&kD.z<0.0001){");
    const auto last = fast.find("\n#endif\n#ifdef NAT_TEX\n        uvec4 u=inner?", first); // P555: the block read follows the culling
    if (first == std::string::npos || last == std::string::npos) return 2;
    const std::string branch = fast.substr(first, last - first);
    const auto kwFirst = fast.find("            float kw=exp2(kk*(dx*dx*P.x");
    const auto kwLast = fast.find('\n', kwFirst);
    if (kwFirst == std::string::npos || kwLast == std::string::npos) return 2;
    const std::string weight = fast.substr(kwFirst, kwLast - kwFirst);
    const std::string source = std::string(R"GLSL(#version 310 es
precision highp float;
precision highp int;
#define NAT_CULL_TIGHT 1
#define NAT_CULL_EXP 10.0
layout(local_size_x=64) in;
struct Input {vec4 p;vec4 center;vec4 params;};
layout(std430,binding=0) readonly buffer Inputs {Input cases[];};
layout(std430,binding=1) writeonly buffer Results {uvec4 result[];};
uniform int count;
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=uint(count))return;
    Input c0=cases[i];vec3 P=c0.p.xyz;vec2 O=c0.center.xy;
    int bx=int(c0.center.z),by=int(c0.center.w);
    float kk=c0.params.x;vec3 kD=vec3(0.0,0.0,c0.params.y);
    bool skipSmall=c0.params.z>0.0,culled=true;
    for(int once=0;once<1;once++){
)GLSL") + branch + R"GLSL(
        culled=false;
    }
    uint contributors=0u;float maxWeight=0.0;
    for(int t=0;t<4;t++){
        int sx=2*bx+(t&1),sy=2*by+(t>>1);
        float dx=float(sx)-O.x,dy=float(sy)-O.y;
)GLSL" + weight + R"GLSL(
        if(!(kw<0.002))contributors++;
        maxWeight=max(maxWeight,kw);
    }
    result[i]=uvec4(uint(culled),contributors,floatBitsToUint(maxWeight),0u);
}
)GLSL";
    LaGpu context("");
    const GLuint program = shaderProgram(source);
    std::mt19937 random(41728);
    std::uniform_real_distribution<float> unit(0.f, 1.f);
    constexpr int count = 1 << 20;
    std::vector<CullInput> inputs(count);
    for (int i = 0; i < count; ++i) {
        auto& c = inputs[i];
        const float angle = unit(random) * 6.283185307f;
        const float cs = std::cos(angle), sn = std::sin(angle);
        const float a = std::exp2(-12.f + 24.f * unit(random));
        const float b = std::exp2(-12.f + 24.f * unit(random));
        c.p[0] = a*cs*cs + b*sn*sn;
        c.p[1] = a*sn*sn + b*cs*cs;
        c.p[2] = (a-b)*sn*cs;
        if (i % 31 == 0) c.p[2] *= 2.f; // Indefinite inputs must not manufacture a positive lower bound.
        c.center[0] = unit(random) * 128.f - 64.f;
        c.center[1] = unit(random) * 128.f - 64.f;
        c.center[2] = float(int(random() % 64) - 32);
        c.center[3] = float(int(random() % 64) - 32);
        c.params[0] = -std::exp2(-8.f + 16.f*unit(random));
        c.params[1] = i % 7 == 0 ? .0001f : unit(random) * .00009999f;
        c.params[2] = i % 11 == 0 ? 0.f : 1.f;
        if (i % 43 == 0) c.params[0] = -c.params[0];
        if (i % 53 == 0) c.params[1] = -.001f;
    }
    GLuint buffers[2]; glGenBuffers(2, buffers);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[0]);
    glBufferData(GL_SHADER_STORAGE_BUFFER, inputs.size()*sizeof(CullInput), inputs.data(), GL_STATIC_DRAW);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffers[0]);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[1]);
    glBufferData(GL_SHADER_STORAGE_BUFFER, size_t(count)*16, nullptr, GL_STREAM_READ);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, buffers[1]);
    glUseProgram(program); glUniform1i(glGetUniformLocation(program,"count"),count);
    glDispatchCompute((count+63)/64,1,1);
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    const auto* results = static_cast<const uint32_t*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,size_t(count)*16,GL_MAP_READ_BIT));
    if (!results) throw std::runtime_error("culling check readback failed");
    int culled=0, failed=0;
    for (int i=0;i<count;i++) if (results[4*i]) {
        ++culled;
        if (results[4*i+1] || inputs[i].params[2]==0.f || inputs[i].params[1]<0.f || inputs[i].params[1]>=.0001f) {
            if (failed++ < 8) std::printf("FAIL case %d contributors=%u\n",i,results[4*i+1]);
        }
    }
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    glDeleteBuffers(2,buffers); glDeleteProgram(program);
    if (glGetError()!=GL_NO_ERROR || culled<1000) ++failed;
    std::printf("check_native_cull_gpu: %d cases, %d culled, %d failures\n",count,culled,failed);
    return failed ? 1 : 0;
}
