#include "ScamGLCompiler.h"

#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <glslang/SPIRV/GlslangToSpv.h>
#include <spirv_msl.hpp>

#include <map>
#include <mutex>
#include <set>

namespace scamgl {

namespace {

constexpr unsigned kDefaultBlockBinding = 99;
constexpr int kBufferSizeIndex = 30;

std::once_flag initOnce;

EShLanguage language(Stage stage) {
    switch (stage) {
    case Stage::Compute: return EShLangCompute;
    case Stage::Vertex: return EShLangVertex;
    case Stage::Fragment: return EShLangFragment;
    }
    return EShLangCompute;
}

spv::ExecutionModel model(Stage stage) {
    switch (stage) {
    case Stage::Compute: return spv::ExecutionModelGLCompute;
    case Stage::Vertex: return spv::ExecutionModelVertex;
    case Stage::Fragment: return spv::ExecutionModelFragment;
    }
    return spv::ExecutionModelGLCompute;
}

bool toSpirv(const std::string& glsl, Stage stage, std::vector<uint32_t>& spirv, std::string& log) {
    std::call_once(initOnce, [] { glslang::InitializeProcess(); });
    const EShLanguage lang = language(stage);
    glslang::TShader shader(lang);
    const char* text = glsl.c_str();
    shader.setStrings(&text, 1);
    shader.setEnvInput(glslang::EShSourceGlsl, lang, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_1);
    shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_3);
    shader.setEnvInputVulkanRulesRelaxed();
    shader.setGlobalUniformBlockName("gl_DefaultUniformBlock");
    shader.setGlobalUniformSet(0);
    shader.setGlobalUniformBinding(kDefaultBlockBinding);
    shader.setAutoMapBindings(true);
    shader.setAutoMapLocations(true);
    shader.setShiftBinding(glslang::EResSsbo, kShiftSsbo);
    shader.setShiftBinding(glslang::EResImage, kShiftImage);
    shader.setShiftBinding(glslang::EResTexture, kShiftTexture);
    shader.setShiftBinding(glslang::EResSampler, kShiftTexture);
    shader.setShiftBinding(glslang::EResUbo, kShiftUbo);
    const auto messages = static_cast<EShMessages>(EShMsgSpvRules | EShMsgVulkanRules);
    if (!shader.parse(GetDefaultResources(), 310, false, messages)) {
        log = std::string("compile: ") + shader.getInfoLog() + shader.getInfoDebugLog();
        return false;
    }
    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(messages) || !program.mapIO()) {
        log = std::string("link: ") + program.getInfoLog() + program.getInfoDebugLog();
        return false;
    }
    glslang::SpvOptions options;
    options.generateDebugInfo = false;
    options.disableOptimizer = true;
    glslang::GlslangToSpv(*program.getIntermediate(lang), spirv, &options);
    return !spirv.empty();
}

int baseTypeOf(const spirv_cross::SPIRType& t) {
    switch (t.basetype) {
    case spirv_cross::SPIRType::Int: return 1;
    case spirv_cross::SPIRType::UInt: return 2;
    case spirv_cross::SPIRType::Boolean: return 3;
    default: return 0;
    }
}

} // namespace

Compiled compile(const std::string& glsl, Stage stage, bool iosPlatform) {
    Compiled out;
    std::vector<uint32_t> spirv;
    if (!toSpirv(glsl, stage, spirv, out.log)) return out;
    try {
        spirv_cross::CompilerMSL msl(std::move(spirv));
        spirv_cross::CompilerMSL::Options options;
        options.platform = iosPlatform ? spirv_cross::CompilerMSL::Options::iOS : spirv_cross::CompilerMSL::Options::macOS;
        options.set_msl_version(2, 4);
        options.buffer_size_buffer_index = kBufferSizeIndex;
        options.enable_decoration_binding = false;
        msl.set_msl_options(options);
        const spv::ExecutionModel exec = model(stage);
        const auto resources = msl.get_shader_resources();
        int nextBuffer = 0, nextTexture = 0, nextSampler = 0;
        std::map<uint32_t, int> bufferOfBinding; // SPIR-V binding → [[buffer]] (aliased blocks share it)
        auto bindBuffer = [&](uint32_t binding) {
            auto it = bufferOfBinding.find(binding);
            if (it != bufferOfBinding.end()) return it->second;
            const int index = nextBuffer++;
            if (index == kBufferSizeIndex) throw std::runtime_error("too many buffers");
            bufferOfBinding[binding] = index;
            spirv_cross::MSLResourceBinding b;
            b.stage = exec;
            b.desc_set = 0;
            b.binding = binding;
            b.msl_buffer = uint32_t(index);
            msl.add_msl_resource_binding(b);
            return index;
        };
        for (const auto& r : resources.uniform_buffers) {
            const uint32_t binding = msl.get_decoration(r.id, spv::DecorationBinding);
            Resource res;
            res.name = msl.get_name(r.base_type_id);
            // The global block's binding is shifted with the UBOs; its name identifies it.
            const bool isDefault = res.name == "gl_DefaultUniformBlock" || binding == kDefaultBlockBinding + unsigned(kShiftUbo);
            res.type = isDefault ? Resource::DefaultBlock : Resource::UniformBlock;
            res.glBinding = isDefault ? -1 : int(binding) - kShiftUbo;
            res.mslIndex = bindBuffer(binding);
            out.resources.push_back(res);
            if (isDefault) {
                const auto& type = msl.get_type(r.base_type_id);
                out.blockSize = uint32_t(msl.get_declared_struct_size(type));
                for (uint32_t i = 0; i < type.member_types.size(); ++i) {
                    const auto& mt = msl.get_type(type.member_types[i]);
                    Member m;
                    m.name = msl.get_member_name(r.base_type_id, i);
                    m.offset = msl.type_struct_member_offset(type, i);
                    m.size = uint32_t(msl.get_declared_struct_member_size(type, i));
                    m.vecsize = mt.vecsize;
                    m.columns = mt.columns;
                    m.baseType = baseTypeOf(mt);
                    if (!mt.array.empty()) {
                        m.count = mt.array[0];
                        m.arrayStride = msl.type_struct_member_array_stride(type, i);
                    }
                    if (mt.columns > 1) m.matrixStride = msl.type_struct_member_matrix_stride(type, i);
                    out.members.push_back(m);
                }
            }
        }
        for (const auto& r : resources.storage_buffers) {
            const uint32_t binding = msl.get_decoration(r.id, spv::DecorationBinding);
            Resource res;
            res.type = Resource::StorageBlock;
            res.name = msl.get_name(r.base_type_id);
            res.glBinding = int(binding) - kShiftSsbo;
            res.mslIndex = bindBuffer(binding);
            out.resources.push_back(res);
        }
        for (const auto& r : resources.storage_images) {
            const uint32_t binding = msl.get_decoration(r.id, spv::DecorationBinding);
            Resource res;
            res.type = Resource::Image;
            res.name = msl.get_name(r.id);
            res.glBinding = int(binding) - kShiftImage;
            res.mslIndex = nextTexture++;
            res.arrayed = msl.get_type(r.type_id).image.arrayed;
            spirv_cross::MSLResourceBinding b;
            b.stage = exec;
            b.desc_set = 0;
            b.binding = binding;
            b.msl_texture = uint32_t(res.mslIndex);
            msl.add_msl_resource_binding(b);
            out.resources.push_back(res);
        }
        for (const auto& r : resources.sampled_images) {
            const uint32_t binding = msl.get_decoration(r.id, spv::DecorationBinding);
            Resource res;
            res.type = Resource::Sampled;
            res.name = msl.get_name(r.id);
            res.glBinding = int(binding) - kShiftTexture;
            res.mslIndex = nextTexture++;
            res.mslSampler = nextSampler++;
            res.arrayed = msl.get_type(r.type_id).image.arrayed;
            spirv_cross::MSLResourceBinding b;
            b.stage = exec;
            b.desc_set = 0;
            b.binding = binding;
            b.msl_texture = uint32_t(res.mslIndex);
            b.msl_sampler = uint32_t(res.mslSampler);
            msl.add_msl_resource_binding(b);
            out.resources.push_back(res);
        }
        out.msl = msl.compile();
        out.entry = msl.get_cleansed_entry_point_name("main", exec);
        if (stage == Stage::Compute) {
            for (int i = 0; i < 3; ++i) out.local[i] = std::max(1u, msl.get_execution_mode_argument(spv::ExecutionModeLocalSize, uint32_t(i)));
        }
        if (msl.needs_buffer_size_buffer()) out.bufferSizeIndex = kBufferSizeIndex;
        out.ok = true;
    } catch (const std::exception& error) {
        out.log = std::string("SPIRV-Cross: ") + error.what();
    }
    return out;
}

} // namespace scamgl
