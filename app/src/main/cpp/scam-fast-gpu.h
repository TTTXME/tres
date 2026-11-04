#pragma once
// The GLES 3.1 side of the fast merge route (scam-fast-merge.h): the context, the reference programs (scam-fast-shaders.h, generated)
// with the program binary cache, the prewarm and the teardown. Included early by scam-hybrid.h (hybridPrewarmGpu builds it).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#else
// The host checks (desktop g++): the types the readback buffers name; libandroid.so is not there, so ahbReady() stays false
#include <cstdint>
struct AHardwareBuffer;
struct ARect { int32_t left, top, right, bottom; };
struct AHardwareBuffer_Desc { uint32_t width, height, layers, format; uint64_t usage; uint32_t stride, rfu0; uint64_t rfu1; };
enum : uint32_t { AHARDWAREBUFFER_FORMAT_BLOB = 0x21 };
enum : uint64_t { AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN = 3, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN = 3UL << 4,
                  AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER = 1ULL << 24 };
#endif
#include <dlfcn.h>
#include "scam-superres-gpu.h" // scamProcessingContext
#include "scam-fast-shaders.h"
#include <condition_variable>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <mutex>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>

namespace scam {

namespace fastmerge {
// The normalisation (sh076's rgb / max(w, 1e-6)) as a compute pass that also packs what the worker reads back: the RGB as half floats
// (12 per 4 pixels = 6 words, the worker's RGB layout), the clip flags (one byte per pixel) and the effective frames at HALF.
// Runs over one band of rows (u_row0, its own buffers: every readback is one map from offset 0).
static const char* kFastOutput=R"FM(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=64,local_size_y=1) in;
uniform highp sampler2D u_accum_color_r;
uniform highp sampler2D u_accum_weights_gb;
uniform highp sampler2D u_frame_weights;
uniform ivec2 u_size;     // OUT
uniform ivec2 u_half;     // HALF
uniform int u_row0;       // first row of this band
uniform int u_rows;       // rows of this band
uniform int u_acc_row0;   // the accumulators' first row (row strips; 0 = the whole frame)
uniform vec3 u_inv_wb;    // 1 / the white balance gains of the merge
uniform float u_clip;
uniform highp sampler2D u_hl_base;  // P497: the base's clipped mask (HALF)
uniform highp sampler2D u_hl_final; // P497: the final mask (HALF, the short frames' validity applied)
uniform highp sampler2D u_base_bayer; // P497: the base frame's Bayer quads (HALF, sensor units)
uniform vec4 u_black;     // P497: its black level per quad channel
uniform float u_white;    // P497: its white level
uniform int u_fill;       // P497: > 0 = the reach (OUT px) of the hole fill below (short frames merged), 0 = off
layout(std430,binding=0) writeonly buffer Rgb{uint rgb[];};
layout(std430,binding=1) writeonly buffer Flags{uint flags[];};
layout(std430,binding=2) writeonly buffer Eff{float eff[];};
vec3 at(ivec2 p){
    vec4 a=texelFetch(u_accum_color_r,p-ivec2(0,u_acc_row0),0);
    vec4 b=texelFetch(u_accum_weights_gb,p-ivec2(0,u_acc_row0),0);
    return max(a.rgb/max(vec3(a.a,b.r,b.g),vec3(1.0e-6))*u_inv_wb,vec3(0.0));
}
// P497: inside the base frame's clipped mask, the short frames refused (their validity cut the final mask) - a near wire's copy
// their flow missed, SCA_20261104_073400: a pink band beside the wire (G at the base's clip, R and B measured) in a recovered window
vec2 halfUv(ivec2 p){return (vec2(p)+0.5)/vec2(u_size);}
float baseMask(ivec2 p){return textureLod(u_hl_base,halfUv(p),0.0).r;}
// how far the short frames were refused here (bilinear: no half-res steps along a slanted wire)
float refusal(ivec2 p){
    vec2 uv=halfUv(p);float b=textureLod(u_hl_base,uv,0.0).r;
    return b>0.3?smoothstep(0.05,0.3,1.0-textureLod(u_hl_final,uv,0.0).r/b):0.0;
}
// how clipped the base frame's own quad is: its second-highest channel (both greens for a G-clipped sky) at 90-97 % of the range;
// a quad the thin wire crosses has one green below - not filled, so the wire keeps its edge (one channel gave a staircase)
float baseClip(ivec2 p){
    vec4 q=(texelFetch(u_base_bayer,p*u_half/u_size,0)-u_black)/max(u_white-u_black,vec4(1.0));
    float second=max(max(min(q.r,q.g),min(q.b,q.a)),min(max(q.r,q.g),max(q.b,q.a))); // second-highest of four
    return smoothstep(0.90,0.97,second);
}
// such a pixel: the recovered highlight around it, rays in eight directions to their first pixel the short frames filled (past the
// mask's soft rim while it still rises), weighted 1 / distance; a ray stops at a structure (the wire, outside the mask). One average
// of all rays, not one chosen line: the line's switches drew boxes and roofs
vec4 fill(ivec2 p,bool cross){ // cross: through structures (the fallback when nothing is in reach otherwise)
    ivec2 lo=ivec2(0,u_acc_row0),hi=ivec2(u_size.x-1,u_acc_row0+textureSize(u_accum_color_r,0).y-1);
    ivec2 dirs[8]=ivec2[8](ivec2(1,0),ivec2(-1,0),ivec2(0,1),ivec2(0,-1),ivec2(1,1),ivec2(-1,-1),ivec2(1,-1),ivec2(-1,1));
    vec3 sum=vec3(0.0);float wsum=0.0;
    for(int d=0;d<8;++d){
        ivec2 dd=dirs[d];float f=d<4?1.0:1.4142;
        for(int s=1;s<=u_fill;++s){
            ivec2 t=p+dd*s;
            if(any(lessThan(t,lo))||any(greaterThan(t,hi)))break;
            vec3 u=at(t);float m=max(u.r,max(u.g,u.b));
            if(m<0.85||baseClip(t)<0.5||baseMask(t)<=0.5){if(cross)continue;break;} // the wire itself, or outside the mask
            if(refusal(t)>0.5)continue;
            int dist=s;
            for(int k=1;k<=8;++k){
                ivec2 e=t+dd*k;
                if(any(lessThan(e,lo))||any(greaterThan(e,hi))||baseClip(e)<0.5||baseMask(e)<=0.5||refusal(e)>0.5)break;
                vec3 w=at(e);float mw=max(w.r,max(w.g,w.b));
                if(mw<m*1.01)break;
                u=w;dist=s+k;m=mw;
            }
            float wt=1.0/(float(dist)*f);
            sum+=u*wt;wsum+=wt;
            break;
        }
    }
    return vec4(wsum>0.0?sum/wsum:vec3(0.0),wsum);
}
void main(){
    int q=int(gl_GlobalInvocationID.x);
    int yb=int(gl_GlobalInvocationID.y);
    if(q*4>=u_size.x||yb>=u_rows)return;
    int y=u_row0+yb;
    vec3 c[4];
    uint fl=0u;
    for(int k=0;k<4;++k){
        ivec2 p=ivec2(q*4+k,y);
        vec4 a=texelFetch(u_accum_color_r,p-ivec2(0,u_acc_row0),0);
        vec4 b=texelFetch(u_accum_weights_gb,p-ivec2(0,u_acc_row0),0);
        vec3 wt=max(vec3(a.a,b.r,b.g),vec3(1.0e-6));
        vec3 v=max(a.rgb/wt*u_inv_wb,vec3(0.0));
        if(u_fill>0){
            float a=refusal(p)*baseClip(p)*smoothstep(0.8,0.9,max(v.r,max(v.g,v.b)));
            // across the wire only when nothing else is in reach: a strip the flow left without data between the frame's edge and
            // a wire (SCA_20261104_073312, its top left corner) stayed the base's pink clip
            if(a>0.0){vec4 fv=fill(p,false);if(fv.a<=0.0)fv=fill(p,true);if(fv.a>0.0)v=mix(v,fv.rgb,a);}
        }
        c[k]=v;
        float cf=b.z/max(b.w,1.0e-6);
        uint f=0u;
        if(v.r>=u_clip)f|=1u;
        if(v.g>=u_clip)f|=2u;
        if(v.b>=u_clip)f|=4u;
        if(cf>0.02&&cf<0.98)f|=8u;
        fl|=f<<(8u*uint(k));
    }
    int o=(yb*u_size.x+q*4)*3/2;
    rgb[o+0]=packHalf2x16(c[0].rg);
    rgb[o+1]=packHalf2x16(vec2(c[0].b,c[1].r));
    rgb[o+2]=packHalf2x16(c[1].gb);
    rgb[o+3]=packHalf2x16(c[2].rg);
    rgb[o+4]=packHalf2x16(vec2(c[2].b,c[3].r));
    rgb[o+5]=packHalf2x16(c[3].gb);
    flags[(yb*u_size.x+q*4)/4]=fl;
    if((y&1)==0){
        for(int k=0;k<2;++k){
            ivec2 h=ivec2(q*2+k,y/2);
            if(h.x<u_half.x&&h.y<u_half.y)eff[h.y*u_half.x+h.x]=texelFetch(u_frame_weights,h,0).r+1.0;
        }
    }
}
)FM";
// P480: the base highlight mask (R16F at HALF) packed for the app's dezipper pass (its trailer): two halves per word, row by row
// (HALF width even: the route takes widths of 4 px multiples). The texels' own halves: the same values the merge sampled.
static const char* kFastHlMaskOut=R"FM(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=64,local_size_y=1) in;
uniform highp sampler2D u_mask;
uniform ivec2 u_half;
layout(std430,binding=0) writeonly buffer Mask{uint mask[];};
void main(){
    int q=int(gl_GlobalInvocationID.x),y=int(gl_GlobalInvocationID.y);
    if(2*q>=u_half.x||y>=u_half.y)return;
    vec2 v=vec2(texelFetch(u_mask,ivec2(2*q,y),0).r,texelFetch(u_mask,ivec2(2*q+1,y),0).r);
    mask[(y*u_half.x)/2+q]=packHalf2x16(v);
}
)FM";
} // namespace fastmerge

// The GLES 3.1 context of the route and its programs (compiled, or loaded from the merge's program binary cache: files "fm-*.bin").
class FastMergeGpu {
public:
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    std::string renderer,driver,cacheDir,gpuPrio; // gpuPrio: " gpuPrio=low/medium/high" (EGL 0x3100), else empty
    int cacheHits=0,compiled=0;
    double initMs=0;
    bool floatLinear=false,externalBuffer=false,nativeClientBuffer=false;
    enum Prog { pUnpack,pUnpackBuf,pGuide,pRef,pUnblock,pDown2,pDown4,pProducts,pGuide8,pCorners,pKlt,pRansac,pFill,pUpsample,pLk,pMedian,pSparse,
                pReject,pRejFilter,pDilate,pCyclops,pBlendEq,pAccum,pMergeB,pMergeA,pOutput,
                pPass,pHlClipDilate,pHlGaussH,pHlGaussV,pHlValidate,pQuarDown,pQuarReduce,pQuarDilateH,pQuarDilateV,pHlCleanValid,pHlApplyValidity,pShortRecover,
                pGuideQuad,pMergeQuad,pQpcEstimate,pQpcSmooth,pRawToGray,pHotPixel,pMergeQuadQuad,
                pHlMaskOut, // P480: built on first use (hlMaskOutProgram), not at init: a burst without the highlight path never builds it
                kProgs };
    GLuint prog[kProgs]{};
    std::vector<GLuint> textures,fbos,buffers;
    explicit FastMergeGpu(const std::string& cache){
        const auto t0=std::chrono::steady_clock::now();
        cacheDir=cache;
        try{
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr)||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("EGL unavailable");
            const EGLint configAttrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
            EGLConfig config{};EGLint count=0;
            if(!eglChooseConfig(display,configAttrs,&config,1,&count)||count!=1)throw std::runtime_error("no EGL configuration");
            context=scamProcessingContext(display,config);
            if(context==EGL_NO_CONTEXT)throw std::runtime_error("cannot create a GLES 3.1 context");
            {EGLint prio=0; // P57: the priority the driver gave the context (0x3101 high, 0x3102 medium, 0x3103 low)
             if(eglQueryContext(display,context,0x3100,&prio)&&prio)gpuPrio=prio==0x3103?" gpuPrio=low":prio==0x3102?" gpuPrio=medium":" gpuPrio=high";
             else eglGetError();}
            const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
            surface=eglCreatePbufferSurface(display,config,size);
            if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("cannot activate the GLES context");
            const auto* name=glGetString(GL_RENDERER);renderer=name?reinterpret_cast<const char*>(name):"unknown";
            const auto* version=glGetString(GL_VERSION);driver=renderer+" "+(version?reinterpret_cast<const char*>(version):"");
            const auto* ext=glGetString(GL_EXTENSIONS);const std::string exts=ext?reinterpret_cast<const char*>(ext):"";
            // RGBA32F / R16F / RGBA16F render targets (the median, the flow, the guides) and the filtered RGBA32F noise LUT / flow
            if(exts.find("GL_EXT_color_buffer_float")==std::string::npos)throw std::runtime_error("no GL_EXT_color_buffer_float");
            floatLinear=exts.find("GL_OES_texture_float_linear")!=std::string::npos;
            externalBuffer=exts.find("GL_EXT_external_buffer")!=std::string::npos;
            const char* eglExt=eglQueryString(display,EGL_EXTENSIONS);
            nativeClientBuffer=eglExt&&std::strstr(eglExt,"EGL_ANDROID_get_native_client_buffer")!=nullptr;
            using namespace fastmerge;
            prog[pUnpack]=cs(kFastUnpackFused);prog[pUnpackBuf]=cs(kFastUnpackFusedBuf);prog[pGuide]=fs(kFastGuide);prog[pRef]=fs(kFastBaseRefColor);prog[pUnblock]=cs(kFastUnblocker);
            prog[pDown2]=fs(kFastGrayDown2);prog[pDown4]=fs(kFastGrayDown4);prog[pProducts]=cs(kFastGradProducts);prog[pGuide8]=fs(kFastPyrToGuide8);
            prog[pCorners]=cs(kFastHomoCorners);prog[pKlt]=cs(kFastHomoKlt);prog[pRansac]=cs(kFastHomoRansac);prog[pFill]=cs(kFastHomoFillFlow);
            prog[pUpsample]=cs(kFastUpsampleAlign);prog[pLk]=cs(kFastBlockLk);prog[pMedian]=fs(kFastMedianAlign);prog[pSparse]=fs(kFastAlignSparse);
            prog[pReject]=fs(kFastRejection);prog[pRejFilter]=rejFilterProgram(true);prog[pDilate]=fs(kFastDilateMask);prog[pCyclops]=fs(kFastCyclops);
            prog[pBlendEq]=fs(kFastBlendEq);prog[pAccum]=fs(kFastAccumWeight);prog[pMergeB]=fs(kFastMergeB);prog[pMergeA]=fs(kFastMergeA);
            prog[pOutput]=cs(kFastOutput);
            prog[pPass]=fs(kFastPass);prog[pHlClipDilate]=fs(kFastHlClipDilate);prog[pHlGaussH]=fs(kFastHlGaussH);prog[pHlGaussV]=fs(kFastHlGaussV);
            prog[pHlValidate]=fs(kFastHlValidate);prog[pQuarDown]=fs(kFastQuarDown);prog[pQuarReduce]=cs(kFastQuarReduce);
            prog[pQuarDilateH]=fs(kFastQuarDilateH);prog[pQuarDilateV]=fs(kFastQuarDilateV);prog[pHlCleanValid]=fs(kFastHlCleanValid);
            prog[pHlApplyValidity]=fs(kFastHlApplyValidity);prog[pShortRecover]=cs(kFastShortRecover);
            prog[pGuideQuad]=fs(kFastGuideQuad);prog[pMergeQuad]=fs(kFastMergeQuad);prog[pQpcEstimate]=cs(kFastQpcEstimate);
            prog[pQpcSmooth]=cs(kFastQpcSmooth);prog[pRawToGray]=fs(kFastRawToGray);prog[pHotPixel]=cs(kFastHotPixel);
            prog[pMergeQuadQuad]=fs(kFastMergeQuadQuad);
            if(compiled>0)trimCache();
            check("init");
        }catch(...){cleanup();throw;}
        initMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
    }
    FastMergeGpu(const FastMergeGpu&)=delete;
    FastMergeGpu& operator=(const FastMergeGpu&)=delete;
    ~FastMergeGpu(){cleanup();}
    void release(){eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);}
    // P484 fmFilterGather: the rejection filter reading its 7x7 window by textureGather (default, built at init) or by texelFetch
    // (the previous program, built on first use); the output is the same
    GLuint rejFilterVariant[2]{};bool rejFilterGather=true;
    GLuint rejFilterProgram(bool gather){
        GLuint& q=rejFilterVariant[gather?1:0];
        if(!q)q=fs(gather?fastmerge::kFastRejectionFilterGather:fastmerge::kFastRejectionFilter);
        return q;
    }
    void selectRejFilter(bool gather){
        if(gather==rejFilterGather)return;
        prog[pRejFilter]=rejFilterProgram(gather);rejFilterGather=gather;locs.clear(); // (uniform locations are per program)
    }
    void hlMaskOutProgram(){if(!prog[pHlMaskOut])prog[pHlMaskOut]=cs(fastmerge::kFastHlMaskOut);} // P480
    void acquire(){if(!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("FAST MERGE GPU: context not current");}
    // GPU objects of one merge (the programs stay)
    void freeObjects(){
        if(!fbos.empty())glDeleteFramebuffers(GLsizei(fbos.size()),fbos.data());
        if(!textures.empty())glDeleteTextures(GLsizei(textures.size()),textures.data());
        if(!buffers.empty())glDeleteBuffers(GLsizei(buffers.size()),buffers.data());
        fbos.clear();textures.clear();buffers.clear();
        for(auto& e:ahbOf)ahb.release(e.second);
        ahbOf.clear();
    }
    static void check(const char* where){
        const GLenum e=glGetError();
        if(e!=GL_NO_ERROR)throw std::runtime_error(std::string("FAST MERGE GPU ")+where+" GL error="+std::to_string(e));
    }
    // ---- uniforms: the locations of (program, name literal) looked up once
    std::unordered_map<uint64_t,GLint> locs;
    GLint L(int p,const char* n){
        const uint64_t key=(uint64_t(p)<<56)^uint64_t(reinterpret_cast<uintptr_t>(n));
        auto it=locs.find(key);if(it!=locs.end())return it->second;
        const GLint l=glGetUniformLocation(prog[p],n);locs.emplace(key,l);return l;
    }
    void u1i(int p,const char* n,int v){glProgramUniform1i(prog[p],L(p,n),v);}
    void u1f(int p,const char* n,float v){glProgramUniform1f(prog[p],L(p,n),v);}
    void u2i(int p,const char* n,int a,int b){glProgramUniform2i(prog[p],L(p,n),a,b);}
    void u2f(int p,const char* n,float a,float b){glProgramUniform2f(prog[p],L(p,n),a,b);}
    void u3f(int p,const char* n,float a,float b,float c){glProgramUniform3f(prog[p],L(p,n),a,b,c);}
    void u4f(int p,const char* n,float a,float b,float c,float d){glProgramUniform4f(prog[p],L(p,n),a,b,c,d);}
    void u4f(int p,const char* n,const std::array<float,4>& v){glProgramUniform4f(prog[p],L(p,n),v[0],v[1],v[2],v[3]);}
    void uMat4(int p,const char* n,const float* m){glProgramUniformMatrix4fv(prog[p],L(p,n),1,GL_FALSE,m);}
    // a sampler on a texture unit (fragment programs have no layout bindings; the compute programs' bindings are set the same way)
    void tex(int p,const char* n,int unit,GLuint t){
        glActiveTexture(GLenum(GL_TEXTURE0+unit));glBindTexture(GL_TEXTURE_2D,t);
        const GLint l=L(p,n);if(l>=0)glProgramUniform1i(prog[p],l,unit);
    }
    // ---- objects
    uint64_t objBytes=0; // the bytes of the textures and buffers made (the merge reports it)
    GLuint texture(GLenum format,int w,int h,bool linear){
        GLuint t=0;glGenTextures(1,&t);textures.push_back(t);
        {const int bpp=format==GL_RGBA32F?16:format==GL_RGBA16F?8:(format==GL_RG16F||format==GL_R32F||format==GL_RGB10_A2||format==GL_RGBA8)?4
                       :(format==GL_R16F||format==GL_RG8)?2:1;
         objBytes+=uint64_t(w)*uint64_t(h)*uint64_t(bpp);}
        glBindTexture(GL_TEXTURE_2D,t);
        glTexStorage2D(GL_TEXTURE_2D,1,format,w,h);
        const GLint f=linear?GL_LINEAR:GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,f);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,f);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        return t;
    }
    GLuint fbo(std::initializer_list<GLuint> attach){
        GLuint f=0;glGenFramebuffers(1,&f);fbos.push_back(f);
        glBindFramebuffer(GL_FRAMEBUFFER,f);
        GLenum bufs[4];int i=0;
        for(GLuint t:attach){glFramebufferTexture2D(GL_FRAMEBUFFER,GLenum(GL_COLOR_ATTACHMENT0+i),GL_TEXTURE_2D,t,0);bufs[i]=GLenum(GL_COLOR_ATTACHMENT0+i);++i;}
        glDrawBuffers(i,bufs);
        const GLenum s=glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if(s!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("FAST MERGE GPU framebuffer incomplete "+std::to_string(s));
        return f;
    }
    GLuint buffer(size_t bytes,GLenum usage=GL_DYNAMIC_COPY){
        GLuint b=0;glGenBuffers(1,&b);buffers.push_back(b);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(bytes),nullptr,usage);
        objBytes+=bytes;
        return b;
    }
    // Readback buffers in CPU-cached memory: an AHardwareBuffer (BLOB, CPU_READ_OFTEN | GPU_DATA_BUFFER) bound as the storage of
    // the buffer object (EXT_external_buffer); the CPU reads it through AHardwareBuffer_lock (the driver's cache maintenance).
    // The default storage is write-combined: its 72 MB of RGB halves read at ~0.6-1 GB/s. Any step that fails: the default storage.
    struct Ahb { void* lib=nullptr;
        int (*allocate)(const AHardwareBuffer_Desc*,AHardwareBuffer**)=nullptr;void (*release)(AHardwareBuffer*)=nullptr;
        int (*lock)(AHardwareBuffer*,uint64_t,int32_t,const ARect*,void**)=nullptr;int (*unlock)(AHardwareBuffer*,int32_t*)=nullptr;
        EGLClientBuffer (*clientBuffer)(const AHardwareBuffer*)=nullptr;
        void (*storageExternal)(GLenum,GLintptr,GLsizeiptr,EGLClientBuffer,GLbitfield)=nullptr;
        bool ok=false; };
    Ahb ahb;bool ahbTried=false,ahbFailed=false;
    std::unordered_map<GLuint,AHardwareBuffer*> ahbOf;
    bool ahbReady(){
        if(ahbTried)return ahb.ok;
        ahbTried=true;
        if(!externalBuffer||!nativeClientBuffer||std::getenv("SCAM_FM_NO_AHB"))return false;
        ahb.lib=dlopen("libandroid.so",RTLD_NOW|RTLD_LOCAL);
        if(!ahb.lib)return false;
        ahb.allocate=reinterpret_cast<decltype(ahb.allocate)>(dlsym(ahb.lib,"AHardwareBuffer_allocate"));
        ahb.release=reinterpret_cast<decltype(ahb.release)>(dlsym(ahb.lib,"AHardwareBuffer_release"));
        ahb.lock=reinterpret_cast<decltype(ahb.lock)>(dlsym(ahb.lib,"AHardwareBuffer_lock"));
        ahb.unlock=reinterpret_cast<decltype(ahb.unlock)>(dlsym(ahb.lib,"AHardwareBuffer_unlock"));
        ahb.clientBuffer=reinterpret_cast<decltype(ahb.clientBuffer)>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        ahb.storageExternal=reinterpret_cast<decltype(ahb.storageExternal)>(eglGetProcAddress("glBufferStorageExternalEXT"));
        ahb.ok=ahb.allocate&&ahb.release&&ahb.lock&&ahb.unlock&&ahb.clientBuffer&&ahb.storageExternal;
        return ahb.ok;
    }
    GLuint readBuffer(size_t bytes){
        if(ahbReady()){
            AHardwareBuffer_Desc d{};d.width=uint32_t(bytes);d.height=1;d.layers=1;d.format=AHARDWAREBUFFER_FORMAT_BLOB;
            d.usage=AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN|AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
            AHardwareBuffer* h=nullptr;
            if(ahb.allocate(&d,&h)==0&&h){
                const EGLClientBuffer cb=ahb.clientBuffer(h);
                GLuint b=0;glGenBuffers(1,&b);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);
                glGetError();
                if(cb)ahb.storageExternal(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),cb,0);
                if(cb&&glGetError()==GL_NO_ERROR){buffers.push_back(b);ahbOf[b]=h;return b;}
                glDeleteBuffers(1,&b);ahb.release(h);
            }
            ahb.ok=false; // once failed: the default storage from here on
            ahbFailed=true;
        }
        return buffer(bytes,GL_DYNAMIC_READ);
    }
    // P495 (owner, 2026-11-03: «Тоже делаем»): the RAW upload ring the same way, written by the CPU (CPU_WRITE_OFTEN): the
    // AHardwareBuffer's lock / unlock in place of the driver's map / unmap, which took ~1.2 s of a 2.8 s merge of 22 frames on
    // the Xiaomi 12X's Adreno 650 (uploads 1612 ms, the copies themselves 260). SCAM_FM_NO_AHB_UPLOAD: the GL buffers (A/B).
    bool uploadAhbFailed=false;
    GLuint writeBuffer(size_t bytes){
        if(!uploadAhbFailed&&!std::getenv("SCAM_FM_NO_AHB_UPLOAD")&&ahbReady()){
            AHardwareBuffer_Desc d{};d.width=uint32_t(bytes);d.height=1;d.layers=1;d.format=AHARDWAREBUFFER_FORMAT_BLOB;
            d.usage=AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN|AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
            AHardwareBuffer* h=nullptr;
            if(ahb.allocate(&d,&h)==0&&h){
                const EGLClientBuffer cb=ahb.clientBuffer(h);
                GLuint b=0;glGenBuffers(1,&b);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);
                glGetError();
                if(cb)ahb.storageExternal(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),cb,0);
                if(cb&&glGetError()==GL_NO_ERROR){buffers.push_back(b);ahbOf[b]=h;return b;}
                glDeleteBuffers(1,&b);ahb.release(h);
            }
            uploadAhbFailed=true; // the readback buffers keep theirs
        }
        return buffer(bytes,GL_STREAM_DRAW);
    }
    /** An upload buffer for the CPU's writes (the caller waited for the GPU's last read of it): the AHB's view, else the map. */
    void* mapWrite(GLuint b,size_t offset,size_t bytes){
        const auto ah=ahbOf.find(b);
        if(ah!=ahbOf.end()){
            void* p=nullptr;
            if(ahb.lock(ah->second,AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,-1,nullptr,&p)!=0||!p)return nullptr;
            return static_cast<char*>(p)+offset;
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);
        return glMapBufferRange(GL_SHADER_STORAGE_BUFFER,GLintptr(offset),GLsizeiptr(bytes),GL_MAP_WRITE_BIT|GL_MAP_UNSYNCHRONIZED_BIT|GL_MAP_INVALIDATE_RANGE_BIT);
    }
    bool unmapWrite(GLuint b){
        const auto ah=ahbOf.find(b);
        if(ah!=ahbOf.end())return ahb.unlock(ah->second,nullptr)==0;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);
        return glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)==GL_TRUE;
    }
    bool ahbUpload(GLuint b) const { return ahbOf.count(b)!=0; }
    // invalidate: the pass writes every pixel of its targets (no blending): their old contents need not be loaded (a tiler's
    // GMEM load; the same pixels result). Off for the additive passes. SCAM_FM_NO_INVALIDATE: off everywhere (A/B).
    bool invalidateOn=!std::getenv("SCAM_FM_NO_INVALIDATE");
    void draw(int p,GLuint target,int w,int h,int invalidate=0){
        glBindFramebuffer(GL_FRAMEBUFFER,target);glViewport(0,0,w,h);
        if(invalidate>0&&invalidateOn){
            static const GLenum att[3]={GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1,GL_COLOR_ATTACHMENT2};
            glInvalidateFramebuffer(GL_FRAMEBUFFER,invalidate,att);
        }
        glUseProgram(prog[p]);glDrawArrays(GL_TRIANGLES,0,3);
    }
    // a fullscreen draw into a row strip of the frame (the fast merge's strips above 16 MP): the viewport is the whole frame moved
    // up by the strip's first row, so every fragment gets the tex_coord it has in the whole-frame draw; a scissor may restrict rows
    void drawAt(int p,GLuint target,int x,int y,int w,int h){
        glBindFramebuffer(GL_FRAMEBUFFER,target);glViewport(x,y,w,h);
        glUseProgram(prog[p]);glDrawArrays(GL_TRIANGLES,0,3);
    }
    void dispatch(int p,int gx,int gy){glUseProgram(prog[p]);glDispatchCompute(GLuint(std::max(gx,1)),GLuint(std::max(gy,1)),1);}
    // A buffer read back whole from offset 0 (Adreno 750 returns NULL from some maps without a GL error, see HybridGpu::getBuffer):
    // the map, after a finish, or a staging copy into a fresh GL_DYNAMIC_READ buffer
    double mapMs=0,useMs=0,unmapMs=0; // readBuffer's time in the map, the caller's use of it, the unmap
    template<class F> void readBuffer(GLuint b,size_t bytes,const F& use){
        const auto t0=std::chrono::steady_clock::now();
        const auto ah=ahbOf.find(b);
        if(ah!=ahbOf.end()){ // GPU work done first (the lock takes no fence of ours), then the cached view
            glFinish();
            void* p=nullptr;
            if(ahb.lock(ah->second,AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,-1,nullptr,&p)!=0||!p)throw std::runtime_error("FAST MERGE GPU: readback buffer not locked");
            const auto t1=std::chrono::steady_clock::now();
            use(static_cast<const void*>(p));
            const auto t2=std::chrono::steady_clock::now();
            ahb.unlock(ah->second,nullptr);
            mapMs+=std::chrono::duration<double,std::milli>(t1-t0).count();useMs+=std::chrono::duration<double,std::milli>(t2-t1).count();
            unmapMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t2).count();
            return;
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);
        const void* m=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),GL_MAP_READ_BIT);
        if(!m){glGetError();glFinish();m=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),GL_MAP_READ_BIT);}
        if(m){
            const auto t1=std::chrono::steady_clock::now();
            use(m);
            const auto t2=std::chrono::steady_clock::now();
            if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("FAST MERGE GPU storage invalidated");
            mapMs+=std::chrono::duration<double,std::milli>(t1-t0).count();useMs+=std::chrono::duration<double,std::milli>(t2-t1).count();
            unmapMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t2).count();
            return;
        }
        glGetError();
        GLuint tmp=0;glGenBuffers(1,&tmp);
        glBindBuffer(GL_COPY_READ_BUFFER,b);glBindBuffer(GL_COPY_WRITE_BUFFER,tmp);
        glBufferData(GL_COPY_WRITE_BUFFER,GLsizeiptr(bytes),nullptr,GL_DYNAMIC_READ);
        glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,0,0,GLsizeiptr(bytes));
        glFinish();
        m=glMapBufferRange(GL_COPY_WRITE_BUFFER,0,GLsizeiptr(bytes),GL_MAP_READ_BIT);
        if(!m){glDeleteBuffers(1,&tmp);throw std::runtime_error("FAST MERGE GPU readback failed ("+std::to_string(bytes)+" bytes)");}
        use(m);
        const bool ok=glUnmapBuffer(GL_COPY_WRITE_BUFFER)!=GL_FALSE;
        glDeleteBuffers(1,&tmp);
        if(!ok)throw std::runtime_error("FAST MERGE GPU storage invalidated");
    }
    std::string cacheNote() const {return std::to_string(cacheHits)+"/"+std::to_string(kProgs-1)+" from the cache";} // P480: pHlMaskOut not at init
private:
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            freeObjects();
            for(GLuint p:prog)if(p)glDeleteProgram(p);
            for(GLuint v:rejFilterVariant)if(v&&v!=prog[pRejFilter])glDeleteProgram(v); // P484
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        display=EGL_NO_DISPLAY;
    }
    // the program binary cache as LaGpu's (key: FNV-1a 64 of the driver and the sources), the newest 128 "fm-" files kept (P484)
    std::string cacheFile(const std::string& source){
        if(cacheDir.empty())return "";
        GLint formats=0;glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS,&formats);
        if(formats<=0)return "";
        uint64_t hash=1469598103934665603ULL;
        auto mix=[&](const std::string& text){for(unsigned char c:text){hash^=c;hash*=1099511628211ULL;}hash^=0xff;hash*=1099511628211ULL;};
        mix(driver);mix(source);
        char name[40];std::snprintf(name,sizeof(name),"fm-%016llx.bin",static_cast<unsigned long long>(hash));
        return cacheDir+"/"+name;
    }
    GLuint loadBinary(const std::string& file){
        std::ifstream in(file,std::ios::binary);
        if(!in)return 0;
        std::vector<char> data((std::istreambuf_iterator<char>(in)),std::istreambuf_iterator<char>());
        if(data.size()<=sizeof(GLenum))return 0;
        GLenum format=0;std::memcpy(&format,data.data(),sizeof(format));
        GLuint program=glCreateProgram();
        glProgramBinary(program,format,data.data()+sizeof(format),GLsizei(data.size()-sizeof(format)));
        GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok||glGetError()!=GL_NO_ERROR){glDeleteProgram(program);std::remove(file.c_str());return 0;}
        ++cacheHits;
        return program;
    }
    void storeBinary(GLuint program,const std::string& file){
        GLint length=0;glGetProgramiv(program,GL_PROGRAM_BINARY_LENGTH,&length);
        if(length<=0){glGetError();return;}
        std::vector<char> data(sizeof(GLenum)+size_t(length));
        GLenum format=0;GLsizei written=0;
        glGetProgramBinary(program,length,&written,&format,data.data()+sizeof(GLenum));
        if(glGetError()!=GL_NO_ERROR||written<=0)return;
        std::memcpy(data.data(),&format,sizeof(format));
        const std::string tmp=file+".tmp"+std::to_string(getpid());
        {std::ofstream out(tmp,std::ios::binary|std::ios::trunc);if(!out)return;out.write(data.data(),std::streamsize(sizeof(GLenum)+size_t(written)));if(!out){std::remove(tmp.c_str());return;}}
        if(std::rename(tmp.c_str(),file.c_str())!=0)std::remove(tmp.c_str());
    }
    void trimCache(){
        DIR* d=opendir(cacheDir.c_str());if(!d)return;
        std::vector<std::pair<time_t,std::string>> files;
        while(dirent* e=readdir(d)){
            std::string name=e->d_name;if(name.rfind("fm-",0)!=0)continue;
            struct stat st{};if(stat((cacheDir+"/"+name).c_str(),&st)==0)files.emplace_back(st.st_mtime,cacheDir+"/"+name);
        }
        closedir(d);
        // P484: 128 files (was 40: fewer than the 45+ programs of the route, so every run evicted and compiled 5 of them again)
        const size_t keep=128;
        if(files.size()<=keep)return;
        std::sort(files.begin(),files.end());
        for(size_t i=0;i+keep<files.size();++i)std::remove(files[i].second.c_str());
    }
    GLuint shader(GLenum type,const char* src){
        GLuint s=glCreateShader(type);
        glShaderSource(s,1,&src,nullptr);glCompileShader(s);
        GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetShaderInfoLog(s,sizeof(msg),nullptr,msg);glDeleteShader(s);throw std::runtime_error(std::string("FAST MERGE GPU shader: ")+msg);}
        return s;
    }
    GLuint link(std::initializer_list<std::pair<GLenum,const char*>> stages){
        std::string all;for(const auto& st:stages){all+=st.second;all+='\x01';}
        const std::string file=cacheFile(all);
        if(!file.empty())if(GLuint cached=loadBinary(file))return cached;
        GLuint program=glCreateProgram();
        std::vector<GLuint> made;
        for(const auto& st:stages){const GLuint s=shader(st.first,st.second);glAttachShader(program,s);made.push_back(s);}
        if(!file.empty())glProgramParameteri(program,GL_PROGRAM_BINARY_RETRIEVABLE_HINT,GL_TRUE);
        glLinkProgram(program);
        for(GLuint s:made)glDeleteShader(s);
        GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetProgramInfoLog(program,sizeof(msg),nullptr,msg);glDeleteProgram(program);throw std::runtime_error(std::string("FAST MERGE GPU link: ")+msg);}
        ++compiled;
        if(!file.empty())storeBinary(program,file);
        return program;
    }
    GLuint cs(const char* src){return link({{GL_COMPUTE_SHADER,src}});}
    GLuint fs(const char* src){return link({{GL_VERTEX_SHADER,fastmerge::kFastVs},{GL_FRAGMENT_SHADER,src}});}
};

// The context with its programs made on the worker's EGL warm-up thread while it waits for the burst (as laGpuPrewarm)
struct FastGpuPrewarm { std::mutex m;std::condition_variable cv;bool started=false,done=false;std::unique_ptr<FastMergeGpu> gpu;std::string error; };
inline FastGpuPrewarm& fastGpuPrewarm(){static FastGpuPrewarm p;return p;}
// Marked on the starting thread before the warm-up thread runs (the merge may ask for the context before that thread is scheduled);
// fastGpuPrewarmCancel when the thread could not be started
inline void fastGpuPrewarmBegin(){FastGpuPrewarm& p=fastGpuPrewarm();std::lock_guard<std::mutex> l(p.m);p.started=true;}
inline void fastGpuPrewarmCancel(){FastGpuPrewarm& p=fastGpuPrewarm();{std::lock_guard<std::mutex> l(p.m);p.error="no thread";p.done=true;}p.cv.notify_all();}
inline void fastGpuPrewarmMake(const std::string& cache){
    FastGpuPrewarm& p=fastGpuPrewarm();
    {std::lock_guard<std::mutex> l(p.m);p.started=true;}
    std::unique_ptr<FastMergeGpu> g;std::string error;
    try{g=std::make_unique<FastMergeGpu>(cache);g->release();}catch(const std::exception& e){g.reset();error=e.what();}
    {std::lock_guard<std::mutex> l(p.m);p.gpu=std::move(g);p.error=error;p.done=true;}
    p.cv.notify_all();
}
// why: "" when it came; else not started / its error
inline std::unique_ptr<FastMergeGpu> fastGpuPrewarmTake(std::string& why){
    FastGpuPrewarm& p=fastGpuPrewarm();
    std::unique_lock<std::mutex> l(p.m);
    if(!p.started){why="no prewarm";return nullptr;}
    p.cv.wait(l,[&]{return p.done;});
    why=p.gpu?"":"prewarm failed: "+p.error;
    return std::move(p.gpu);
}
// The context destroyed on a thread of its own after the merge (its ~400 MB of textures and the EGL teardown are not the shot's),
// joined at the process exit
struct FastMergeTeardown { std::thread t; ~FastMergeTeardown(){if(t.joinable())t.join();} };
inline FastMergeTeardown& fastMergeTeardown(){static FastMergeTeardown t;return t;}

} // namespace scam
