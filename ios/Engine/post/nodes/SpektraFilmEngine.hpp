// The spectral film engine in process: spektrafilm's Vulkan renderer (app/src/main/cpp/third_party/spektrafilm, the code of the
// Android libscameraFilm, unchanged) behind FilmReferenceEngine's hooks. On the iPhone it runs through MoltenVK (Vulkan on Metal,
// tools/build_moltenvk.sh); the macOS check (ios-post-check.yml) and a Linux host run the same file on their Vulkan driver.
// The calls are film-reference-jni.cpp's: availableWithQueue (the renderer made and cached by resource directory and queue policy,
// its own initialisation the answer) and renderWithQueue (the packet decoded with its frame size, tiled rendering, the scratch
// budget, the same report). The resources are read from `directory` (SPEKTRAFILM_RESOURCE_DIR): shaders/<name>.comp.spv compiled
// from the shaders at build time and the two float tables of assets/film-reference - the bytes the Android build embeds.
// Needs the app's cpp folder on the include path, <vulkan/vulkan.h>, SpektraVulkanRenderer.cpp and SpektraGeneratedProfileCurves.cpp.
#pragma once

#include "ReferenceFilm.hpp"
#include "film-reference-params.h"
#include "third_party/spektrafilm/SpektraVulkanRenderer.h"

#include <chrono>
#include <cstdlib>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#if defined(__APPLE__)
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#include <os/proc.h>
#endif
#endif

namespace scam_post {

class SpektraFilmEngine {
public:
    /// Sets FilmReferenceEngine's renderer and probe (and, on the iPhone, the memory headroom) to this engine with its resources
    static void install(const std::string& directory) {
        FilmReferenceEngine::probe() = [directory] { return available(directory); };
        FilmReferenceEngine::renderer() = [directory](const uint16_t* in, uint16_t* out, const FilmReferenceParameters& params, int width,
                                                      int height, int64_t budget) {
            return render(directory, in, out, params, width, height, budget);
        };
#if defined(__APPLE__) && TARGET_OS_IPHONE
        // ActivityManager.MemoryInfo.availMem's counterpart: what the process may still allocate before the system ends it
        FilmReferenceEngine::availableMemory() = [] { return int64_t(os_proc_available_memory()); };
#endif
    }

    /// FilmReferenceEngine.available (availableWithQueueNative): the renderer for the current queue policy initialised
    static bool available(const std::string& directory) {
        Runtime& state = runtime();
        std::lock_guard<std::mutex> lock(state.mutex);
        const spektrafilm::QueuePolicy policy = queuePolicy();
        const bool fresh = !(state.renderer && state.resourceDirectory == directory && state.queuePolicy == policy);
        const auto started = std::chrono::steady_clock::now();
        try {
            select(state, directory, policy);
            const bool supported = state.renderer->isAvailable();
            if (fresh)
                logI("SCAM_PIPELINE", "spectral film initialization available=" + std::string(supported ? "true" : "false") + " queuePolicy="
                     + std::to_string(int(policy)) + " ms="
                     + std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count())
                     + (supported ? "" : " error=" + state.renderer->lastError()));
            return supported;
        } catch (...) {
            state.renderer.reset();
            return false;
        }
    }

    /// renderWithQueue: RGBA16F rows in -> RGBA16F rows out; the report as the JNI's, throws with the renderer's error
    static std::string render(const std::string& directory, const uint16_t* in, uint16_t* out, const FilmReferenceParameters& packet,
                              int width, int height, int64_t budget) {
        Runtime& state = runtime();
        std::lock_guard<std::mutex> lock(state.mutex);
        auto& renderer = state.renderer;
        try {
            if (directory.empty() || width <= 0 || height <= 0 || budget < 32ll * 1024 * 1024 || budget > 4ll * 1024 * 1024 * 1024)
                throw std::invalid_argument("Invalid film render geometry or budget");
            const uint64_t bytes = uint64_t(width) * uint64_t(height) * 8;
            if (bytes > uint64_t(std::numeric_limits<int32_t>::max()) || width > 32768 || height > 32768)
                throw std::invalid_argument("Film image exceeds direct-buffer limits");
            if (!in || !out) throw std::invalid_argument("Film requires separate complete direct RGBA16F buffers");
            const uintptr_t first = reinterpret_cast<uintptr_t>(in), second = reinterpret_cast<uintptr_t>(out);
            if ((first < second ? second - first : first - second) < bytes) throw std::invalid_argument("Film image buffers overlap");
            const uint8_t* data = packet.data.data();
            auto params = scamera_film::decode(data);
            params.filmFormatLongEdgeMmOverride = scamera_film::packetFloat(data, 648);
            if (params.filmFormatLongEdgeMmOverride < 0.0f || params.filmFormatLongEdgeMmOverride > 200.0f)
                throw std::invalid_argument("Unsupported film frame size");
            params.gpuRenderTiling = spektrafilm::GpuRenderTilingMode::Tiled;
            select(state, directory, queuePolicy());
            if (!renderer->isAvailable()) throw std::runtime_error(renderer->lastError());
            renderer->setScratchBudget(static_cast<uint64_t>(budget));
            const auto started = std::chrono::steady_clock::now();
            const spektrafilm::ImageView source{in, 0, 0, width, height, width * 8, 4, 2};
            const spektrafilm::MutableImageView target{out, 0, 0, width, height, width * 8, 4, 2};
            if (!renderer->render(source, target, {0, 0, width, height}, params, 0.0)) throw std::runtime_error(renderer->lastError());
            const auto& d = renderer->lastDiagnostics();
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
            std::string report = "ms=" + std::to_string(ms) + " passes=" + std::to_string(d.passCount) + " tiles=" + std::to_string(d.tileCount)
                                 + " scratch=" + std::to_string(d.transientCachedBytes) + " budget=" + std::to_string(budget)
                                 + " route=" + (d.stagedRendering ? "staged" : "whole") + " storageLimit=" + std::to_string(d.storageBufferLimitBytes)
                                 + " device=" + d.deviceName + " setupMs=" + std::to_string(d.cpuSetupMs)
                                 + " submitMs=" + std::to_string(d.commandBufferMs) + " copyMs=" + std::to_string(d.sourceCopyMs + d.outputCopyMs)
                                 + " " + renderer->queueDescription();
            renderer->releaseTransientResources();
            return report;
        } catch (const std::exception&) {
            if (renderer) renderer->releaseTransientResources();
            throw;
        } catch (...) {
            renderer.reset();
            throw std::runtime_error("Film renderer failed");
        }
    }

private:
    struct Runtime {
        std::mutex mutex;
        std::unique_ptr<spektrafilm::VulkanRenderer> renderer;
        std::string resourceDirectory;
        spektrafilm::QueuePolicy queuePolicy = spektrafilm::QueuePolicy::Legacy;
    };
    // as the JNI's: never released by a static destructor (the renderer's own translation-unit statics may be gone by then)
    static Runtime& runtime() { static auto* r = new Runtime; return *r; }

    /// FilmReferenceEngine.queuePolicy: scam_dev film_vk_queue_policy (0 legacy, else 1, the default)
    static spektrafilm::QueuePolicy queuePolicy() {
        return Settings::current().scamDevNumber("film_vk_queue_policy", 1.f) == 0.f ? spektrafilm::QueuePolicy::Legacy
                                                                                       : spektrafilm::QueuePolicy::Interactive;
    }

    static void select(Runtime& state, const std::string& directory, spektrafilm::QueuePolicy policy) {
        if (directory.empty()) throw std::invalid_argument("Film resource directory is empty");
        spektrafilm::selectCachedFilmRenderer(state, directory, policy, [&] {
            if (setenv("SPEKTRAFILM_RESOURCE_DIR", directory.c_str(), 1) != 0)
                throw std::runtime_error("Film resource directory could not be selected");
            return std::make_unique<spektrafilm::VulkanRenderer>(policy);
        });
    }
};

} // namespace scam_post
