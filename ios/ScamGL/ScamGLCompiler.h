// GLSL ES 3.1 → SPIR-V (glslang, Vulkan relaxed rules: loose uniforms in gl_DefaultUniformBlock) → MSL (SPIRV-Cross),
// with the reflection ScamGL needs to bind GL state to the Metal kernel.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace scamgl {

enum class Stage { Compute, Vertex, Fragment };

struct Resource {
    enum Type { DefaultBlock, UniformBlock, StorageBlock, Image, Sampled } type;
    int glBinding = 0;    // the GL binding point / image unit (the sampler's GL unit comes from glUniform1i)
    int mslIndex = 0;     // [[buffer(n)]] or [[texture(n)]]
    int mslSampler = -1;  // [[sampler(n)]] for Sampled
    bool arrayed = false; // 2D array texture / image
    bool volume = false;  // a 3D texture (sampler3D: the film's LUTs)
    std::string name;
};

struct Member {
    std::string name;
    uint32_t offset = 0, size = 0, arrayStride = 0, count = 1, vecsize = 1, columns = 1, matrixStride = 0;
    int baseType = 0; // 0 float, 1 int, 2 uint, 3 bool
};

struct Compiled {
    bool ok = false;
    std::string log, msl, entry;
    uint32_t local[3] = {1, 1, 1};
    std::vector<Resource> resources;
    std::vector<Member> members;  // gl_DefaultUniformBlock
    uint32_t blockSize = 0;
    int bufferSizeIndex = -1;     // [[buffer(n)]] holding the bound buffers' sizes (runtime arrays' length()), or -1
};

// Shifts of the GL binding namespaces in the SPIR-V binding space (they are separate in GL, one space in Vulkan).
constexpr int kShiftSsbo = 100, kShiftImage = 200, kShiftTexture = 300, kShiftUbo = 400;

Compiled compile(const std::string& glsl, Stage stage, bool iosPlatform);

} // namespace scamgl
