#include <jni.h>
#include <algorithm>
#include <chrono>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include "film-reference-params.h"
#include "third_party/spektrafilm/SpektraVulkanRenderer.h"

namespace {
struct FilmRuntime {
    std::mutex mutex;
    std::unique_ptr<spektrafilm::VulkanRenderer> renderer;
    std::string resourceDirectory;
};

FilmRuntime& runtime(){
    // The upstream backend has translation-unit statics; release before unloading,
    // never from a cross-translation-unit exit destructor.
    static auto* state=new FilmRuntime;
    return *state;
}

void fail(JNIEnv* env,const std::string& message){
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message.c_str());
}

void selectRenderer(FilmRuntime& state,const std::string& directory){
    if(directory.empty())throw std::invalid_argument("Film resource directory is empty");
    if(state.renderer&&state.resourceDirectory==directory)return;
    state.renderer.reset();
    if(setenv("SPEKTRAFILM_RESOURCE_DIR",directory.c_str(),1)!=0)
        throw std::runtime_error("Film resource directory could not be selected");
    state.resourceDirectory=directory;
    state.renderer=std::make_unique<spektrafilm::VulkanRenderer>();
}
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*,void*){
    auto& state=runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    state.renderer.reset();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_color_FilmReferenceEngine_availableNative(
        JNIEnv* env,jclass,jstring directory){
    if(!directory)return JNI_FALSE;
    auto& state=runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    const char* path=env->GetStringUTFChars(directory,nullptr);
    if(!path)return JNI_FALSE;
    std::string requested;
    try{requested=path;}
    catch(...){env->ReleaseStringUTFChars(directory,path);return JNI_FALSE;}
    env->ReleaseStringUTFChars(directory,path);
    try{
        selectRenderer(state,requested);
        return state.renderer->isAvailable()?JNI_TRUE:JNI_FALSE;
    }catch(...){state.renderer.reset();return JNI_FALSE;}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_processing_color_FilmReferenceEngine_renderNative(
        JNIEnv* env,jclass,jstring directory,jobject input,jobject output,jobject packet,
        jint width,jint height,jlong budget,jdouble time){
    auto& state=runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    auto& renderer=state.renderer;
    try{
        if(!directory||width<=0||height<=0||budget<32ll*1024*1024
                ||budget>4ll*1024*1024*1024||!std::isfinite(time))
            throw std::invalid_argument("Invalid film render geometry or budget");
        const uint64_t bytes=uint64_t(width)*uint64_t(height)*8;
        if(bytes>uint64_t(std::numeric_limits<int32_t>::max())||width>32768||height>32768)
            throw std::invalid_argument("Film image exceeds direct-buffer limits");
        void* in=env->GetDirectBufferAddress(input);
        void* out=env->GetDirectBufferAddress(output);
        auto* data=static_cast<uint8_t*>(env->GetDirectBufferAddress(packet));
        if(!in||!out||!data||env->GetDirectBufferCapacity(input)<jlong(bytes)
                ||env->GetDirectBufferCapacity(output)<jlong(bytes)
                ||(env->GetDirectBufferCapacity(packet)!=648&&env->GetDirectBufferCapacity(packet)!=652))
            throw std::invalid_argument("Film requires separate complete direct RGBA16F buffers");
        const uintptr_t first=reinterpret_cast<uintptr_t>(in),second=reinterpret_cast<uintptr_t>(out);
        if((first<second?second-first:first-second)<bytes)
            throw std::invalid_argument("Film image buffers overlap");
        const char* path=env->GetStringUTFChars(directory,nullptr);
        if(!path)return nullptr;
        std::string requested(path);
        env->ReleaseStringUTFChars(directory,path);
        auto params=scamera_film::decode(data);
        if(env->GetDirectBufferCapacity(packet)==652){
            params.filmFormatLongEdgeMmOverride=scamera_film::packetFloat(data,648);
            if(params.filmFormatLongEdgeMmOverride<0.0f||params.filmFormatLongEdgeMmOverride>200.0f)
                throw std::invalid_argument("Unsupported film frame size");
        }
        params.gpuRenderTiling=spektrafilm::GpuRenderTilingMode::Tiled;
        selectRenderer(state,requested);
        if(!renderer->isAvailable())throw std::runtime_error(renderer->lastError());
        renderer->setScratchBudget(static_cast<uint64_t>(budget));
        const auto started=std::chrono::steady_clock::now();
        const spektrafilm::ImageView source{in,0,0,width,height,width*8,4,2};
        const spektrafilm::MutableImageView target{out,0,0,width,height,width*8,4,2};
        if(!renderer->render(source,target,{0,0,width,height},params,time))
            throw std::runtime_error(renderer->lastError());
        const auto& d=renderer->lastDiagnostics();
        const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
        std::string report="ms="+std::to_string(ms)+" passes="+std::to_string(d.passCount)
                +" tiles="+std::to_string(d.tileCount)+" scratch="+std::to_string(d.transientCachedBytes)
                +" budget="+std::to_string(budget)+" route="+(d.stagedRendering?"staged":"whole")
                +" storageLimit="+std::to_string(d.storageBufferLimitBytes)+" device="+d.deviceName
                +" setupMs="+std::to_string(d.cpuSetupMs)+" submitMs="+std::to_string(d.commandBufferMs)
                +" copyMs="+std::to_string(d.sourceCopyMs+d.outputCopyMs);
        renderer->releaseTransientResources();
        return env->NewStringUTF(report.c_str());
    }catch(const std::exception& error){
        if(renderer)renderer->releaseTransientResources();
        fail(env,error.what());return nullptr;
    }catch(...){
        renderer.reset();fail(env,"Film renderer failed");return nullptr;
    }
}
