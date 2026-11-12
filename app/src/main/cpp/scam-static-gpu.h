#pragma once
// P624 «Статика»: the two GPU backends of the static merge (scam-static-merge.h drives them). Both run the same programs
// (scam-static-shaders.h) on the same storage buffers with the same per-dispatch parameters:
//  - Vulkan 1.1 compute (libvulkan loaded with dlopen: the worker starts on a device without it), SPIR-V compiled offline
//    (scam-static-spirv.h), a pipeline cache on disk, explicit compute-to-compute barriers, one submit and one fence per strip;
//  - GLES 3.1 compute (the fallback): its own EGL context, the program binaries cached on disk like the other GPU stages.
// Every buffer is host-visible on Vulkan (one memory type that is device-local and host-coherent where the device has it - the
// phones' unified memory - else host-coherent): the strip's uploads are memcpy before the submit, the readback memcpy after the
// fence. On GLES the uploads are glBufferSubData and the readback a mapped range after a fence.
#include "scam-static-shaders.h"
#include "scam-static-spirv.h"
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <dlfcn.h>
#include <array>
#include <chrono>
#include <fstream>
#include <functional>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#define VK_NO_PROTOTYPES 1
#include <vulkan/vulkan.h>

namespace scam {
namespace staticmerge {

// per-dispatch parameters (kStCommon's StParams: 16 ints, 14 floats, then P627's colour block; std140 and push constants lay
// scalars out the same)
struct StParams {
    int32_t K=0,W=0,H=0,s=2,r0=0,RH=0,WH=0,rows=0,cfa=0,mode=0,tile=64,ntx=1,nty=1,spread=2,frame0=0,outRows=0;
    float mu=0,stepc=1.8f,lc=0,ll=0,delta=1,robK=3,robTau=0.15f,clipLvl=0.98f,sig2=1,lam=0.1f,lamFloor=1e-4f,maxShift=3,wbR=1,wbB=1;
    int32_t bs=0; // P627: log2 of the colour block
    int32_t row0=0; // P639: first row of this dispatch (the field pass: first tile row)
};
static_assert(sizeof(StParams)==128,"StParams: 32 scalars (the push constants' 128 B limit)");

// buffer slots of a merge
enum StBuf { bFrames, bRaw, bZ, bZ2, bX, bRW, bR, bWb, bT, bW, bFld, bFld2, bOut, bCnt, bCd, kStBufs }; // bCd: P627's colour prior
// one dispatch: program, the buffer at each binding 1..7 (-1 = none), groups
struct StDispatch { int prog=0; std::array<int,8> bind{{-1,-1,-1,-1,-1,-1,-1,-1}}; uint32_t gx=1,gy=1,gz=1; StParams p; };

class StBackend {
public:
    virtual ~StBackend(){}
    virtual const char* name() const=0;
    virtual std::string describe() const=0;              // device / driver / subgroup / fp16 / cache, for the report
    virtual void alloc(int buf,size_t bytes)=0;            // (re)allocated when larger; contents undefined
    virtual void upload(int buf,size_t off,const void* data,size_t bytes)=0; // before the strip's submit
    virtual void zero(int buf,size_t bytes)=0;             // before the strip's submit
    virtual void begin()=0;                                // a strip's recording
    virtual void dispatch(const StDispatch& d)=0;
    virtual void copy(int src,int dst,size_t bytes)=0;
    virtual void submitWait()=0;                           // the recorded work, one fence
    // P639: the work recorded since the last slice as its own submission, waited for; the ms it took. A strip's passes in one
    // submission ran ~2 s on an Adreno 830 (12 MP, 162 rows): the viewfinder's draws queued behind it (2 - 3 fps) and the third
    // strip was dropped by the driver (the kernel's 2 s limit on one command batch: P29's note in scam-hybrid.h dispatchRows),
    // caught by the strip guard. Short slices: no batch near that limit, the viewfinder's work runs between two of them.
    virtual double slice()=0;
    // how many slices the backend keeps queued (1: slice() returns when its own work is done; 2 - GLES: when the one before is
    // done, so the GPU always holds the next and does not idle while this thread wakes up) and the wait for all of them
    virtual int inFlight() const{return 1;}
    virtual void drain(){}
    virtual void flush(){}                                 // P638: what was dispatched so far goes to the GPU now (not waited for)
    virtual void read(int buf,size_t off,size_t bytes,void* dst)=0; // after submitWait
    virtual void stamp(int buf,const std::vector<size_t>& words,uint32_t value)=0; // the strip guard's sentinels (before submit)
    virtual size_t maxBuffer() const=0;                    // the largest storage buffer the device takes
    bool subgroup=false;                                   // the field pass's subgroupAdd variant is used
    std::vector<std::pair<std::string,double>> passMs;     // GPU-side time per pass name (Vulkan timestamps), the report
};

// ------------------------------------------------------------------------------------------------------------------- GLES
class StGles : public StBackend {
public:
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;
    std::string renderer,version,cacheDir;int cacheHits=0,compiled=0;
    GLuint prog[spCount]{};GLuint ubo=0;
    std::array<GLuint,kStBufs> buf{};std::array<size_t,kStBufs> size{};
    GLint maxBlock=0;
    explicit StGles(const std::string& cache):cacheDir(cache){
        try{
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr)||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("EGL unavailable");
            const EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
            EGLConfig config{};EGLint count=0;
            if(!eglChooseConfig(display,attrs,&config,1,&count)||count!=1)throw std::runtime_error("no EGL configuration");
            context=scamProcessingContext(display,config);
            if(context==EGL_NO_CONTEXT)throw std::runtime_error("no GLES 3.1 context");
            const EGLint sz[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
            surface=eglCreatePbufferSurface(display,config,sz);
            if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("GLES context not current");
            const auto* r=glGetString(GL_RENDERER);renderer=r?reinterpret_cast<const char*>(r):"unknown";
            const auto* v=glGetString(GL_VERSION);version=v?reinterpret_cast<const char*>(v):"";
            GLint blocks=0;glGetIntegerv(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS,&blocks);
            if(blocks<7)throw std::runtime_error("GLES: "+std::to_string(blocks)+" compute storage blocks (< 7)");
            glGetIntegerv(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&maxBlock);
            for(int i=0;i<spCount;++i)if(i!=spFieldSg)prog[i]=build(i);
            glGenBuffers(1,&ubo);glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferData(GL_UNIFORM_BUFFER,sizeof(StParams),nullptr,GL_DYNAMIC_DRAW);
            glGenBuffers(kStBufs,buf.data());
            if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("GLES init error");
        }catch(...){cleanup();throw;}
    }
    ~StGles() override{cleanup();}
    void cleanup(){
        if(display!=EGL_NO_DISPLAY&&context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            for(GLsync f:pending)glDeleteSync(f);
            pending.clear();
            for(GLuint p:prog)if(p)glDeleteProgram(p);
            if(ubo)glDeleteBuffers(1,&ubo);
            glDeleteBuffers(kStBufs,buf.data());
        }
        if(display!=EGL_NO_DISPLAY){
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        }
        surface=EGL_NO_SURFACE;context=EGL_NO_CONTEXT;
    }
    GLuint build(int i){
        const std::string src=stSource(i,true);
        char key[32];std::snprintf(key,sizeof(key),"%016llx",(unsigned long long)stHash(src+renderer+version));
        const std::string file=cacheDir.empty()?"":cacheDir+"/static-"+key+".bin";
        GLuint p=glCreateProgram();
        if(!file.empty()){
            std::ifstream f(file,std::ios::binary);
            std::vector<char> d((std::istreambuf_iterator<char>(f)),std::istreambuf_iterator<char>());
            if(d.size()>sizeof(GLenum)){
                GLenum fmt;std::memcpy(&fmt,d.data(),sizeof(fmt));
                glProgramBinary(p,fmt,d.data()+sizeof(fmt),GLsizei(d.size()-sizeof(fmt)));
                GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);
                if(ok){++cacheHits;return p;}
                glGetError();
            }
        }
        GLuint sh=glCreateShader(GL_COMPUTE_SHADER);
        const char* t=src.c_str();glShaderSource(sh,1,&t,nullptr);glCompileShader(sh);
        GLint ok=0;glGetShaderiv(sh,GL_COMPILE_STATUS,&ok);
        if(!ok){char log[1024]={0};glGetShaderInfoLog(sh,sizeof(log)-1,nullptr,log);glDeleteShader(sh);glDeleteProgram(p);
            throw std::runtime_error(std::string("GLES static shader ")+stPrograms()[i].name+": "+log);}
        if(!file.empty())glProgramParameteri(p,GL_PROGRAM_BINARY_RETRIEVABLE_HINT,GL_TRUE);
        glAttachShader(p,sh);glLinkProgram(p);glDeleteShader(sh);
        glGetProgramiv(p,GL_LINK_STATUS,&ok);
        if(!ok){char log[1024]={0};glGetProgramInfoLog(p,sizeof(log)-1,nullptr,log);glDeleteProgram(p);
            throw std::runtime_error(std::string("GLES static link ")+stPrograms()[i].name+": "+log);}
        GLuint ix=glGetUniformBlockIndex(p,"StParams");if(ix!=GL_INVALID_INDEX)glUniformBlockBinding(p,ix,0);
        ++compiled;
        if(!file.empty()){
            GLint len=0;glGetProgramiv(p,GL_PROGRAM_BINARY_LENGTH,&len);
            if(len>0){std::vector<char> d(sizeof(GLenum)+size_t(len));GLenum fmt=0;GLsizei n=0;
                glGetProgramBinary(p,len,&n,&fmt,d.data()+sizeof(GLenum));
                if(n>0){std::memcpy(d.data(),&fmt,sizeof(fmt));std::ofstream(file,std::ios::binary).write(d.data(),std::streamsize(sizeof(GLenum)+size_t(n)));}}
            glGetError();
        }
        return p;
    }
    // the context current on this thread (another GPU stage of the worker may have made its own current in between)
    void cur(){if(eglGetCurrentContext()!=context&&!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("GLES static: context not current");}
    const char* name() const override{return "GLES";}
    std::string describe() const override{
        return renderer+", "+version+", programs "+std::to_string(cacheHits)+" from the cache / "+std::to_string(compiled)+" compiled";
    }
    void alloc(int b,size_t bytes) override{
        cur();bytes=std::max<size_t>(bytes,16);
        if(size[size_t(b)]>=bytes)return;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[size_t(b)]);
        glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(bytes),nullptr,GL_DYNAMIC_COPY);
        if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("GLES static: buffer of "+std::to_string(bytes>>20)+" MB refused");
        size[size_t(b)]=bytes;
    }
    void upload(int b,size_t off,const void* data,size_t bytes) override{
        cur();glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[size_t(b)]);glBufferSubData(GL_SHADER_STORAGE_BUFFER,GLintptr(off),GLsizeiptr(bytes),data);
    }
    void zero(int b,size_t bytes) override{std::vector<uint8_t> z(bytes,0);upload(b,0,z.data(),bytes);}
    void begin() override{cur();}
    void dispatch(const StDispatch& d) override{
        glUseProgram(prog[d.prog]);
        glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferSubData(GL_UNIFORM_BUFFER,0,sizeof(StParams),&d.p);
        glBindBufferBase(GL_UNIFORM_BUFFER,0,ubo);
        for(int i=1;i<8;++i)if(d.bind[size_t(i)]>=0)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,GLuint(i),buf[size_t(d.bind[size_t(i)])]);
        glDispatchCompute(d.gx,d.gy,d.gz);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT|GL_UNIFORM_BARRIER_BIT);
    }
    // P638: each pass a submission of its own (the same commands in the same order). A strip's ~80 passes went to the GPU as
    // one batch of ~2 s: nothing else of the phone's GPU ran meanwhile (the viewfinder's draws of 1.9 - 2.0 s in the vivo
    // X200 Ultra's log of 31258) and a batch that long is what a driver's hang detection gives up on (its rows not written).
    // P639: the solve calls it after a pass it does not slice (staticSliceMs 0); a sliced pass's submissions are slice()'s,
    // shorter and waited for
    void flush() override{glFlush();}
    void copy(int src,int dst,size_t bytes) override{
        glBindBuffer(GL_COPY_READ_BUFFER,buf[size_t(src)]);glBindBuffer(GL_COPY_WRITE_BUFFER,buf[size_t(dst)]);
        glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,0,0,GLsizeiptr(bytes));
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
    }
    std::vector<GLsync> pending; // P639: the submissions not yet waited for (at most two)
    void waitOldest(){
        GLsync f=pending.front();pending.erase(pending.begin());
        scam_progress::FenceWait fw;
        for(;;){const GLenum r=glClientWaitSync(f,GL_SYNC_FLUSH_COMMANDS_BIT,50000000);
            if(r==GL_ALREADY_SIGNALED||r==GL_CONDITION_SATISFIED)break;
            if(r==GL_WAIT_FAILED){glDeleteSync(f);throw std::runtime_error("GLES static: fence wait failed");}
            scam_progress::tick();}
        glDeleteSync(f);
    }
    int inFlight() const override{return 2;}
    double slice() override{
        const auto t0=std::chrono::steady_clock::now();
        GLsync f=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);glFlush();
        if(!f){glFinish();glGetError();}
        else {pending.push_back(f);while(pending.size()>1)waitOldest();}
        return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
    }
    void drain() override{while(!pending.empty())waitOldest();}
    void submitWait() override{
        if(std::getenv("SCAM_STATIC_GLES_FAIL"))throw std::runtime_error("SCAM_STATIC_GLES_FAIL (host check of the backend ladder)");
        // P638: one failed strip (the n-th submission since the variable was set), the ones after it fine
        if(const char* at=std::getenv("SCAM_STATIC_GLES_FAIL_AT")){static int n=0;
            if(++n==std::atoi(at))throw std::runtime_error("SCAM_STATIC_GLES_FAIL_AT (host check: one strip fails)");}
        slice();drain();
        const GLenum e=glGetError();
        if(e!=GL_NO_ERROR)throw std::runtime_error("GLES static: GL error "+std::to_string(e));
    }
    void read(int b,size_t off,size_t bytes,void* dst) override{
        cur();glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[size_t(b)]);
        const void* m=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,GLintptr(off),GLsizeiptr(bytes),GL_MAP_READ_BIT);
        if(!m)throw std::runtime_error("GLES static: readback map failed");
        std::memcpy(dst,m,bytes);glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    }
    void stamp(int b,const std::vector<size_t>& words,uint32_t value) override{
        cur();if(words.empty())return;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[size_t(b)]);
        const size_t span=(words.back()+1)*4;
        auto* m=static_cast<uint32_t*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(span),GL_MAP_WRITE_BIT));
        if(!m)throw std::runtime_error("GLES static: strip guard map failed");
        for(size_t w:words)m[w]=value;
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    }
    size_t maxBuffer() const override{return maxBlock>0?size_t(maxBlock):size_t(128)<<20;}
};

// ----------------------------------------------------------------------------------------------------------------- Vulkan
struct StVkFns {
    void* lib=nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr=nullptr;
#define ST_VK_FNS(X) X(vkCreateInstance) X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties) \
    X(vkGetPhysicalDeviceProperties2) X(vkGetPhysicalDeviceFeatures2) X(vkGetPhysicalDeviceQueueFamilyProperties) \
    X(vkGetPhysicalDeviceMemoryProperties) X(vkCreateDevice) X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkCreateBuffer) \
    X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkAllocateMemory) X(vkFreeMemory) X(vkBindBufferMemory) X(vkMapMemory) \
    X(vkUnmapMemory) X(vkCreateShaderModule) X(vkDestroyShaderModule) X(vkCreateDescriptorSetLayout) X(vkDestroyDescriptorSetLayout) \
    X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) X(vkCreatePipelineCache) X(vkDestroyPipelineCache) X(vkGetPipelineCacheData) \
    X(vkCreateComputePipelines) X(vkDestroyPipeline) X(vkCreateDescriptorPool) X(vkDestroyDescriptorPool) X(vkAllocateDescriptorSets) \
    X(vkUpdateDescriptorSets) X(vkCreateCommandPool) X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) \
    X(vkEndCommandBuffer) X(vkResetCommandBuffer) X(vkCmdBindPipeline) X(vkCmdBindDescriptorSets) X(vkCmdPushConstants) X(vkCmdDispatch) \
    X(vkCmdPipelineBarrier) X(vkCmdCopyBuffer) X(vkCmdFillBuffer) X(vkCmdWriteTimestamp) X(vkCmdResetQueryPool) X(vkCreateQueryPool) \
    X(vkDestroyQueryPool) X(vkGetQueryPoolResults) X(vkQueueSubmit) X(vkCreateFence) X(vkDestroyFence) X(vkWaitForFences) X(vkResetFences) \
    X(vkDeviceWaitIdle)
#define ST_VK_DECL(f) PFN_##f f=nullptr;
    ST_VK_FNS(ST_VK_DECL)
#undef ST_VK_DECL
};

// The Vulkan backend: throws (the caller falls back to GLES) on any missing piece, each with its reason.
class StVulkan : public StBackend {
public:
    StVkFns vk;
    VkInstance inst=VK_NULL_HANDLE;VkPhysicalDevice phys=VK_NULL_HANDLE;VkDevice dev=VK_NULL_HANDLE;VkQueue queue=VK_NULL_HANDLE;
    uint32_t family=0;VkPhysicalDeviceProperties props{};VkPhysicalDeviceMemoryProperties mem{};
    VkDescriptorSetLayout dsl=VK_NULL_HANDLE;VkPipelineLayout layout=VK_NULL_HANDLE;VkPipelineCache pcache=VK_NULL_HANDLE;
    VkPipeline pipe[spCount]{};VkDescriptorPool pool=VK_NULL_HANDLE;VkCommandPool cpool=VK_NULL_HANDLE;VkCommandBuffer cmd=VK_NULL_HANDLE;
    VkFence fence=VK_NULL_HANDLE;VkQueryPool qpool=VK_NULL_HANDLE;
    struct Buf { VkBuffer b=VK_NULL_HANDLE;VkDeviceMemory m=VK_NULL_HANDLE;void* map=nullptr;size_t size=0; };
    std::array<Buf,kStBufs> bufs{};
    std::map<std::array<int,8>,VkDescriptorSet> sets;
    uint32_t memType=0;bool memDeviceLocal=false;
    uint32_t subgroupSize=0;bool fp16=false;std::string cacheNote,cacheFile;
    bool profile=false;float tsPeriod=1;uint32_t tsCount=0;std::vector<std::string> tsNames;
    static constexpr uint32_t kTs=1024;
    explicit StVulkan(const std::string& cacheDir,bool wantProfile){
        try{init(cacheDir,wantProfile);}catch(...){cleanup();throw;}
    }
    ~StVulkan() override{cleanup();}
    void need(VkResult r,const char* what){if(r!=VK_SUCCESS)throw std::runtime_error(std::string("Vulkan ")+what+" failed ("+std::to_string(int(r))+")");}
    void init(const std::string& cacheDir,bool wantProfile){
        vk.lib=dlopen("libvulkan.so",RTLD_NOW|RTLD_LOCAL);
        if(!vk.lib)vk.lib=dlopen("libvulkan.so.1",RTLD_NOW|RTLD_LOCAL);
        if(!vk.lib)throw std::runtime_error("no libvulkan");
        vk.getInstanceProcAddr=reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vk.lib,"vkGetInstanceProcAddr"));
        if(!vk.getInstanceProcAddr)throw std::runtime_error("libvulkan without vkGetInstanceProcAddr");
        auto vers=reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vk.getInstanceProcAddr(VK_NULL_HANDLE,"vkEnumerateInstanceVersion"));
        uint32_t iv=VK_API_VERSION_1_0;if(vers)vers(&iv);
        if(iv<VK_API_VERSION_1_1)throw std::runtime_error("Vulkan instance 1.0 only");
        vk.vkCreateInstance=reinterpret_cast<PFN_vkCreateInstance>(vk.getInstanceProcAddr(VK_NULL_HANDLE,"vkCreateInstance"));
        if(!vk.vkCreateInstance)throw std::runtime_error("no vkCreateInstance");
        VkApplicationInfo app{};app.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;app.pApplicationName="SCAMERA static merge";app.apiVersion=VK_API_VERSION_1_1;
        VkInstanceCreateInfo ici{};ici.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;ici.pApplicationInfo=&app;
        need(vk.vkCreateInstance(&ici,nullptr,&inst),"instance");
#define ST_VK_LOAD(f) vk.f=reinterpret_cast<PFN_##f>(vk.getInstanceProcAddr(inst,#f)); if(!vk.f)throw std::runtime_error("Vulkan: no " #f);
        ST_VK_FNS(ST_VK_LOAD)
#undef ST_VK_LOAD
        uint32_t n=0;vk.vkEnumeratePhysicalDevices(inst,&n,nullptr);
        if(n==0)throw std::runtime_error("no Vulkan device");
        std::vector<VkPhysicalDevice> devs(n);vk.vkEnumeratePhysicalDevices(inst,&n,devs.data());
        for(VkPhysicalDevice d:devs){ // the first 1.1 device with a compute queue
            VkPhysicalDeviceProperties p{};vk.vkGetPhysicalDeviceProperties(d,&p);
            if(p.apiVersion<VK_API_VERSION_1_1)continue;
            uint32_t qn=0;vk.vkGetPhysicalDeviceQueueFamilyProperties(d,&qn,nullptr);
            std::vector<VkQueueFamilyProperties> q(qn);vk.vkGetPhysicalDeviceQueueFamilyProperties(d,&qn,q.data());
            for(uint32_t i=0;i<qn;++i)if(q[i].queueFlags&VK_QUEUE_COMPUTE_BIT){phys=d;family=i;props=p;tsPeriod=p.limits.timestampPeriod;
                profile=wantProfile&&q[i].timestampValidBits>0&&p.limits.timestampComputeAndGraphics;break;}
            if(phys)break;
        }
        if(!phys)throw std::runtime_error("no Vulkan 1.1 device with a compute queue");
        const VkPhysicalDeviceLimits& L=props.limits;
        if(L.maxPushConstantsSize<sizeof(StParams))throw std::runtime_error("push constants "+std::to_string(L.maxPushConstantsSize)+" B");
        if(L.maxComputeWorkGroupInvocations<256||L.maxComputeSharedMemorySize<5*256*4)throw std::runtime_error("compute limits");
        if(L.maxPerStageDescriptorStorageBuffers<7)throw std::runtime_error("storage buffers per stage");
        // subgroup arithmetic in compute (the field pass) and fp16 (reported: the solver keeps fp32)
        VkPhysicalDeviceSubgroupProperties sg{};sg.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
        VkPhysicalDeviceProperties2 p2{};p2.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;p2.pNext=&sg;
        vk.vkGetPhysicalDeviceProperties2(phys,&p2);
        subgroupSize=sg.subgroupSize;
        subgroup=(sg.supportedStages&VK_SHADER_STAGE_COMPUTE_BIT)&&(sg.supportedOperations&VK_SUBGROUP_FEATURE_ARITHMETIC_BIT)
                 &&(sg.supportedOperations&VK_SUBGROUP_FEATURE_BASIC_BIT)&&sg.subgroupSize>=4&&!std::getenv("SCAM_STATIC_NO_SUBGROUP");
        VkPhysicalDeviceShaderFloat16Int8FeaturesKHR f16{};f16.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR;
        VkPhysicalDeviceFeatures2 f2{};f2.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;f2.pNext=&f16;
        vk.vkGetPhysicalDeviceFeatures2(phys,&f2);
        fp16=f16.shaderFloat16!=0;
        const float prio=1.f;
        VkDeviceQueueCreateInfo qci{};qci.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;qci.queueFamilyIndex=family;qci.queueCount=1;qci.pQueuePriorities=&prio;
        VkDeviceCreateInfo dci{};dci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qci;
        need(vk.vkCreateDevice(phys,&dci,nullptr,&dev),"device");
        vk.vkGetDeviceQueue(dev,family,0,&queue);
        vk.vkGetPhysicalDeviceMemoryProperties(phys,&mem);
        // one memory type for every buffer: host-visible + coherent, device-local when the device has such a type
        int best=-1;
        for(uint32_t i=0;i<mem.memoryTypeCount;++i){
            const VkMemoryPropertyFlags f=mem.memoryTypes[i].propertyFlags;
            if((f&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))!=(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))continue;
            const bool dl=f&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            if(best<0||(dl&&!memDeviceLocal)){best=int(i);memDeviceLocal=dl;}
        }
        if(best<0)throw std::runtime_error("no host-coherent memory");
        memType=uint32_t(best);
        // layout: storage buffers 1..7, push constants
        std::array<VkDescriptorSetLayoutBinding,7> b{};
        for(int i=0;i<7;++i){b[size_t(i)].binding=uint32_t(i+1);b[size_t(i)].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            b[size_t(i)].descriptorCount=1;b[size_t(i)].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;}
        VkDescriptorSetLayoutCreateInfo dl{};dl.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;dl.bindingCount=7;dl.pBindings=b.data();
        need(vk.vkCreateDescriptorSetLayout(dev,&dl,nullptr,&dsl),"set layout");
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(StParams)};
        VkPipelineLayoutCreateInfo pl{};pl.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;pl.setLayoutCount=1;pl.pSetLayouts=&dsl;pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&pr;
        need(vk.vkCreatePipelineLayout(dev,&pl,nullptr,&layout),"pipeline layout");
        // the pipeline cache: the file when its header names this device (VkPipelineCacheHeaderVersionOne: 16 B, then the UUID)
        std::vector<char> data;
        if(!cacheDir.empty()){
            cacheFile=cacheDir+"/static-vk.cache";
            std::ifstream f(cacheFile,std::ios::binary);data.assign(std::istreambuf_iterator<char>(f),std::istreambuf_iterator<char>());
            if(data.size()>=32&&std::memcmp(data.data()+16,props.pipelineCacheUUID,VK_UUID_SIZE)==0)cacheNote="pipeline cache hit ("+std::to_string(data.size()>>10)+" KB)";
            else {cacheNote=data.empty()?"pipeline cache empty":"pipeline cache of another driver: rebuilt";data.clear();}
        } else cacheNote="no pipeline cache directory";
        VkPipelineCacheCreateInfo pci{};pci.sType=VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;pci.initialDataSize=data.size();pci.pInitialData=data.empty()?nullptr:data.data();
        if(vk.vkCreatePipelineCache(dev,&pci,nullptr,&pcache)!=VK_SUCCESS){pci.initialDataSize=0;pci.pInitialData=nullptr;need(vk.vkCreatePipelineCache(dev,&pci,nullptr,&pcache),"pipeline cache");}
        for(int i=0;i<spCount;++i){
            if(i==spFieldSg&&!subgroup)continue;
            const StSpv& s=stSpv()[i];
            if(s.hash!=stHash(stSource(i,false)))throw std::runtime_error(std::string("SPIR-V of ")+stPrograms()[i].name+" is stale (tools/static/gen_static_spirv)");
            VkShaderModuleCreateInfo smi{};smi.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;smi.codeSize=size_t(s.words)*4;smi.pCode=s.code;
            VkShaderModule sm=VK_NULL_HANDLE;need(vk.vkCreateShaderModule(dev,&smi,nullptr,&sm),"shader module");
            VkComputePipelineCreateInfo cpi{};cpi.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            cpi.stage=VkPipelineShaderStageCreateInfo{};cpi.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;cpi.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;cpi.stage.module=sm;cpi.stage.pName="main";
            cpi.layout=layout;
            const VkResult r=vk.vkCreateComputePipelines(dev,pcache,1,&cpi,nullptr,&pipe[i]);
            vk.vkDestroyShaderModule(dev,sm,nullptr);
            need(r,stPrograms()[i].name);
        }
        if(!cacheFile.empty()){
            size_t sz=0;
            if(vk.vkGetPipelineCacheData(dev,pcache,&sz,nullptr)==VK_SUCCESS&&sz>0&&sz!=data.size()){
                std::vector<char> out(sz);
                if(vk.vkGetPipelineCacheData(dev,pcache,&sz,out.data())==VK_SUCCESS)std::ofstream(cacheFile,std::ios::binary).write(out.data(),std::streamsize(sz));
            }
        }
        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,7*128};
        VkDescriptorPoolCreateInfo dpi{};dpi.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;dpi.maxSets=128;dpi.poolSizeCount=1;dpi.pPoolSizes=&ps;
        need(vk.vkCreateDescriptorPool(dev,&dpi,nullptr,&pool),"descriptor pool");
        VkCommandPoolCreateInfo cpci{};cpci.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;cpci.queueFamilyIndex=family;cpci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        need(vk.vkCreateCommandPool(dev,&cpci,nullptr,&cpool),"command pool");
        VkCommandBufferAllocateInfo cai{};cai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;cai.commandPool=cpool;cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;cai.commandBufferCount=1;
        need(vk.vkAllocateCommandBuffers(dev,&cai,&cmd),"command buffer");
        VkFenceCreateInfo fci{};fci.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;need(vk.vkCreateFence(dev,&fci,nullptr,&fence),"fence");
        if(profile){VkQueryPoolCreateInfo q{};q.sType=VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;q.queryType=VK_QUERY_TYPE_TIMESTAMP;q.queryCount=kTs;
            if(vk.vkCreateQueryPool(dev,&q,nullptr,&qpool)!=VK_SUCCESS)profile=false;}
    }
    void cleanup(){
        if(dev){
            vk.vkDeviceWaitIdle(dev); // teardown only (never between strips)
            for(Buf& b:bufs){if(b.map)vk.vkUnmapMemory(dev,b.m);if(b.b)vk.vkDestroyBuffer(dev,b.b,nullptr);if(b.m)vk.vkFreeMemory(dev,b.m,nullptr);b=Buf{};}
            if(qpool)vk.vkDestroyQueryPool(dev,qpool,nullptr);
            if(fence)vk.vkDestroyFence(dev,fence,nullptr);
            if(cpool)vk.vkDestroyCommandPool(dev,cpool,nullptr);
            if(pool)vk.vkDestroyDescriptorPool(dev,pool,nullptr);
            for(VkPipeline p:pipe)if(p)vk.vkDestroyPipeline(dev,p,nullptr);
            if(pcache)vk.vkDestroyPipelineCache(dev,pcache,nullptr);
            if(layout)vk.vkDestroyPipelineLayout(dev,layout,nullptr);
            if(dsl)vk.vkDestroyDescriptorSetLayout(dev,dsl,nullptr);
            vk.vkDestroyDevice(dev,nullptr);dev=VK_NULL_HANDLE;
        }
        if(inst&&vk.vkDestroyInstance){vk.vkDestroyInstance(inst,nullptr);inst=VK_NULL_HANDLE;}
        if(vk.lib){dlclose(vk.lib);vk.lib=nullptr;}
    }
    const char* name() const override{return "Vulkan";}
    std::string describe() const override{
        char t[800];
        std::snprintf(t,sizeof(t),"%s (vendor 0x%04x device 0x%04x, driver 0x%08x, API %u.%u.%u), subgroup %u%s, fp16 %s (not used: fp32 solve), memory %s, %s",
            props.deviceName,props.vendorID,props.deviceID,props.driverVersion,VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),
            VK_VERSION_PATCH(props.apiVersion),subgroupSize,subgroup?" (field pass subgroupAdd)":" (shared-memory sums)",fp16?"available":"absent",
            memDeviceLocal?"device-local host-coherent":"host-coherent",cacheNote.c_str());
        return t;
    }
    void alloc(int b,size_t bytes) override{
        bytes=std::max<size_t>(bytes,16);
        Buf& B=bufs[size_t(b)];
        if(B.size>=bytes)return;
        if(B.b){vk.vkDeviceWaitIdle(dev);if(B.map)vk.vkUnmapMemory(dev,B.m);vk.vkDestroyBuffer(dev,B.b,nullptr);vk.vkFreeMemory(dev,B.m,nullptr);B=Buf{};
            sets.clear();vk.vkDestroyDescriptorPool(dev,pool,nullptr);pool=VK_NULL_HANDLE;
            VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,7*128};
            VkDescriptorPoolCreateInfo dpi{};dpi.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;dpi.maxSets=128;dpi.poolSizeCount=1;dpi.pPoolSizes=&ps;
            need(vk.vkCreateDescriptorPool(dev,&dpi,nullptr,&pool),"descriptor pool");}
        if(bytes>props.limits.maxStorageBufferRange)throw std::runtime_error("Vulkan: buffer "+std::to_string(bytes>>20)+" MB above maxStorageBufferRange");
        VkBufferCreateInfo bi{};bi.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;bi.size=bytes;
        bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        need(vk.vkCreateBuffer(dev,&bi,nullptr,&B.b),"buffer");
        VkMemoryRequirements r{};vk.vkGetBufferMemoryRequirements(dev,B.b,&r);
        if(!(r.memoryTypeBits&(1u<<memType)))throw std::runtime_error("Vulkan: buffer memory type");
        VkMemoryAllocateInfo ai{};ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;ai.allocationSize=r.size;ai.memoryTypeIndex=memType;
        need(vk.vkAllocateMemory(dev,&ai,nullptr,&B.m),"memory");
        need(vk.vkBindBufferMemory(dev,B.b,B.m,0),"bind memory");
        need(vk.vkMapMemory(dev,B.m,0,VK_WHOLE_SIZE,0,&B.map),"map");
        B.size=bytes;
    }
    void upload(int b,size_t off,const void* data,size_t bytes) override{std::memcpy(static_cast<char*>(bufs[size_t(b)].map)+off,data,bytes);}
    void zero(int b,size_t bytes) override{std::memset(bufs[size_t(b)].map,0,bytes);}
    std::string curName;
    void begin() override{
        need(vk.vkResetCommandBuffer(cmd,0),"reset");
        VkCommandBufferBeginInfo bi{};bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        need(vk.vkBeginCommandBuffer(cmd,&bi),"begin");
        tsCount=0;tsNames.clear();
        if(profile){vk.vkCmdResetQueryPool(cmd,qpool,0,kTs);stamp("");}
    }
    void stamp(const std::string& n){if(!profile||tsCount>=kTs)return;vk.vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,qpool,tsCount++);tsNames.push_back(n);}
    VkDescriptorSet set(const std::array<int,8>& bind){
        auto it=sets.find(bind);if(it!=sets.end())return it->second;
        VkDescriptorSetAllocateInfo ai{};ai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;ai.descriptorPool=pool;ai.descriptorSetCount=1;ai.pSetLayouts=&dsl;
        VkDescriptorSet s=VK_NULL_HANDLE;need(vk.vkAllocateDescriptorSets(dev,&ai,&s),"descriptor set");
        std::array<VkDescriptorBufferInfo,7> info{};std::array<VkWriteDescriptorSet,7> w{};
        for(int i=1;i<8;++i){
            int b=bind[size_t(i)];if(b<0)b=bFrames; // an unused binding still needs a buffer
            info[size_t(i-1)].buffer=bufs[size_t(b)].b;info[size_t(i-1)].offset=0;info[size_t(i-1)].range=VK_WHOLE_SIZE;
            w[size_t(i-1)]=VkWriteDescriptorSet{};w[size_t(i-1)].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;w[size_t(i-1)].dstSet=s;w[size_t(i-1)].dstBinding=uint32_t(i);
            w[size_t(i-1)].descriptorCount=1;w[size_t(i-1)].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;w[size_t(i-1)].pBufferInfo=&info[size_t(i-1)];
        }
        vk.vkUpdateDescriptorSets(dev,7,w.data(),0,nullptr);
        sets.emplace(bind,s);return s;
    }
    void barrier(){
        VkMemoryBarrier mb{};mb.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        mb.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;mb.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;
        vk.vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&mb,0,nullptr,0,nullptr);
    }
    void dispatch(const StDispatch& d) override{
        const int p=d.prog==spField&&subgroup?spFieldSg:d.prog;
        vk.vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipe[p]);
        VkDescriptorSet s=set(d.bind);
        vk.vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&s,0,nullptr);
        vk.vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(StParams),&d.p);
        vk.vkCmdDispatch(cmd,d.gx,d.gy,d.gz);
        barrier();
        stamp(stPrograms()[d.prog].name);
    }
    void copy(int src,int dst,size_t bytes) override{
        VkBufferCopy c{0,0,bytes};vk.vkCmdCopyBuffer(cmd,bufs[size_t(src)].b,bufs[size_t(dst)].b,1,&c);
        barrier();
        stamp("copy");
    }
    void submitWait() override{
        need(vk.vkEndCommandBuffer(cmd),"end");
        VkSubmitInfo si{};si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;si.commandBufferCount=1;si.pCommandBuffers=&cmd;
        need(vk.vkResetFences(dev,1,&fence),"fence reset");
        need(vk.vkQueueSubmit(queue,1,&si,fence),"submit");
        scam_progress::FenceWait fw;
        for(;;){const VkResult r=vk.vkWaitForFences(dev,1,&fence,VK_TRUE,50000000ull);
            if(r==VK_SUCCESS)break;
            if(r!=VK_TIMEOUT)need(r,"fence wait");
            scam_progress::tick();}
        if(profile&&tsCount>1){
            std::vector<uint64_t> t(tsCount);
            if(vk.vkGetQueryPoolResults(dev,qpool,0,tsCount,t.size()*8,t.data(),8,VK_QUERY_RESULT_64_BIT)==VK_SUCCESS)
                for(uint32_t i=1;i<tsCount;++i){
                    const double ms=double(t[i]-t[i-1])*tsPeriod*1e-6;
                    bool found=false;for(auto& e:passMs)if(e.first==tsNames[i]){e.second+=ms;found=true;break;}
                    if(!found)passMs.emplace_back(tsNames[i],ms);
                }
        }
    }
    double slice() override{
        const auto t0=std::chrono::steady_clock::now();
        submitWait();begin();
        return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
    }
    void read(int b,size_t off,size_t bytes,void* dst) override{std::memcpy(dst,static_cast<const char*>(bufs[size_t(b)].map)+off,bytes);}
    void stamp(int b,const std::vector<size_t>& words,uint32_t value) override{
        auto* m=static_cast<uint32_t*>(bufs[size_t(b)].map);for(size_t w:words)m[w]=value;
    }
    size_t maxBuffer() const override{return std::min<size_t>(props.limits.maxStorageBufferRange,size_t(1)<<30);}
};

} // namespace staticmerge
} // namespace scam
