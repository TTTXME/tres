// The spectral film engine (libscameraFilm: app/src/main/cpp/film-reference-jni.cpp with spektrafilm's Vulkan renderer) for the
// host references, built by run.sh with SCAM_HOST_FILM=1 on a host with a Vulkan driver (Mesa lavapipe). Besides the app's JNI
// entry points (the Android reference's FilmReferenceEngine) the same library exports scam_film_host_render: the JNI's
// renderWithQueue without the JNI, which the C++ port's host tools (post_node_check) set as FilmReferenceEngine::renderer - the
// same renderer code and driver on both sides. Not part of the app.
#include "film-reference-params.h"
#include "third_party/spektrafilm/SpektraVulkanRenderer.h"

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>

namespace {
struct HostRuntime {
    std::mutex mutex;
    std::unique_ptr<spektrafilm::VulkanRenderer> renderer;
    std::string directory;
    spektrafilm::QueuePolicy policy = spektrafilm::QueuePolicy::Legacy;
};
HostRuntime& host() { static auto* r = new HostRuntime; return *r; }
std::string& lastReport() { static std::string s; return s; }
}

/// 0: rendered (the report in scam_film_host_report), else the error there. input / output: RGBA16F rows; packet: 652 bytes.
extern "C" __attribute__((visibility("default"))) int scam_film_host_render(const char* directory, const void* input, void* output,
                                                                            const uint8_t* packet, int packetBytes, int width, int height,
                                                                            long long budget, int policy) {
    HostRuntime& state = host();
    std::lock_guard<std::mutex> lock(state.mutex);
    try {
        if (!directory || width <= 0 || height <= 0 || budget < 32ll * 1024 * 1024 || budget > 4ll * 1024 * 1024 * 1024)
            throw std::invalid_argument("Invalid film render geometry or budget");
        if (policy != 0 && policy != 1) throw std::invalid_argument("Unsupported film queue policy");
        auto params = scamera_film::decode(packet);
        if (packetBytes == 652) params.filmFormatLongEdgeMmOverride = scamera_film::packetFloat(packet, 648);
        params.gpuRenderTiling = spektrafilm::GpuRenderTilingMode::Tiled;
        const auto queue = static_cast<spektrafilm::QueuePolicy>(policy);
        if (!state.renderer || state.directory != directory || state.policy != queue) {
            setenv("SPEKTRAFILM_RESOURCE_DIR", directory, 1);
            state.renderer = std::make_unique<spektrafilm::VulkanRenderer>(queue);
            state.directory = directory;
            state.policy = queue;
        }
        if (!state.renderer->isAvailable()) throw std::runtime_error(state.renderer->lastError());
        state.renderer->setScratchBudget(static_cast<uint64_t>(budget));
        const spektrafilm::ImageView source{input, 0, 0, width, height, width * 8, 4, 2};
        const spektrafilm::MutableImageView target{output, 0, 0, width, height, width * 8, 4, 2};
        const auto started = std::chrono::steady_clock::now();
        if (!state.renderer->render(source, target, {0, 0, width, height}, params, 0.0)) throw std::runtime_error(state.renderer->lastError());
        const auto& d = state.renderer->lastDiagnostics();
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
        lastReport() = "ms=" + std::to_string(ms) + " passes=" + std::to_string(d.passCount) + " tiles=" + std::to_string(d.tileCount)
                       + " device=" + d.deviceName;
        state.renderer->releaseTransientResources();
        return 0;
    } catch (const std::exception& e) {
        if (state.renderer) state.renderer->releaseTransientResources();
        lastReport() = e.what();
        return 1;
    }
}

extern "C" __attribute__((visibility("default"))) const char* scam_film_host_report() { return lastReport().c_str(); }
