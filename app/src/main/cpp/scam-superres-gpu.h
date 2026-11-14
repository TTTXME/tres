#pragma once
// What the hybrid merge keeps of the SCAM super-resolution merge (its CPU reference scam-superres.h and this GPU version, with
// their common GLSL kCommonShader, went with SCAM HDR's worker paths in P704): the Sabre kernel tuning and its SNR curves and
// the worker's GL context at medium priority.
#include "scam-homography.h"
#include "scam-glsl-guard.h" // P662 SCAM_GLSL_DUMP
#include <EGL/egl.h>
#include <fstream>
#include <functional>
#include <GLES3/gl31.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <array>
#include <cstring>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam {

// Merge kernel tuning (the hybrid merge sets it from its own tuning and the SNR curves below).
struct SuperResTuning {
    int legacy=0;          // 1: the former isotropic kernel + 3x3-minimum robustness
    int grid=1;            // sub-pixel evaluations per axis for detailed pixels (1 or 2): 2 = merge on a 2x grid
                           // and average back to the sensor grid (anti-aliased, supersampled)
    float subDetail=0.3f;  // detail measure above which the 2x grid is used
    // Kernel (Gaussian standard deviations in sensor pixels) from the structure tensor of the model
    // luma, after Wronski et al. / Sabre: narrow across a coherent edge, long along it, wide
    // and isotropic in flat areas.
    // Negative = follows the SNR of the reference frame (Sabre: every kernel parameter is a curve of the
    // SNR of mid grey, see snrKernel()); a value >= 0 overrides that parameter.
    float base=-1.f,shrunk=-1.f,stretched=-1.f,flat=-1.f;
    float strengthScale=50.f;   // edge amount: coherence limited by strength * scale
    float flat0=-1.f,flat1=-1.f;  // gradient (u per pixel) below which the kernel is blurred
    float snrScale=0.25f;       // noise variance scale of the model input (applied to the SNR key only)
    int snrFixed=0;             // >0: use this SNR instead of the one derived from the noise model
    float rawTensor=0.f;        // weight of the structure tensor of the reference frame's raw greens (SCAM guide), added to the model's
    float rawNoise=1.f;         // bias (noise) removed from the raw tensor, in units of the expected gradient noise^2
    int subset=0;               // debug: 1 = odd donor frames only, 2 = even donor frames only (split-half noise estimate)
    float texStd=0.25f;         // weight of the local raw std in the "detail" measure
    float tensorNoise=0.0015f;  // gradient noise floor (u per pixel)
    // Robustness: Wiener-shrunk colour differences against max(noise, share of the local texture).
    float robustK=3.f,robustSigmas=3.f,robustTex=0.25f,dilate=4.f;
    // Base frame (added last): wider kernel where little donor weight accumulated.
    float widenBelow=2.5f,widenMul=1.6f;
    float subShrink=0.8f;      // kernel scale on the 2x sub-grid
    float legacySigma=0.55f;
};
// Piecewise-linear curve (values outside the keys are clamped), as the Sabre parameter curves.
template<size_t N> inline float sabreCurve(float x,const float (&k)[N],const float (&v)[N]){
    if(x<=k[0])return v[0];
    for(size_t i=1;i<N;++i)if(x<=k[i])return v[i-1]+(v[i]-v[i-1])*(x-k[i-1])/(k[i]-k[i-1]);
    return v[N-1];
}
// SNR of mid grey (18 %) of the reference frame: the key of the Sabre curves.
inline float sabreSnr(float slope,float offset,float scale){
    return 0.18f/std::sqrt(std::max(scale*(offset+slope*0.18f),1e-10f));
}
// Kernel parameters for an SNR. Sabre 6.1 (true gaussian sigmas, sensor pixels): across an edge 0.18 -> 0.14, base 0.40 -> 0.30,
// along 1.6 -> 1.2, blurred 1.9 -> 0.9 from SNR 8 to 30; structure threshold f2 0.01 -> 0.001, transition f3 0.02 -> 0.006.
// Luminance is merged with sigma/sqrt2 here (and colour with sigma), hence the larger values than in Sabre.
inline void snrKernel(SuperResTuning& t,float snr){
    static const float k[]={4.f,8.f,14.f,30.f};
    static const float shrunk[]={0.40f,0.32f,0.28f,0.22f};
    static const float base[]={0.56f,0.46f,0.40f,0.32f};
    static const float stretched[]={1.90f,1.65f,1.42f,1.18f};
    static const float flat[]={2.50f,2.05f,1.65f,1.27f};
    static const float f0[]={0.0020f,0.0013f,0.0009f,0.0005f};
    static const float f1[]={0.0120f,0.0085f,0.0060f,0.0040f};
    if(t.shrunk<0)t.shrunk=sabreCurve(snr,k,shrunk);
    if(t.base<0)t.base=sabreCurve(snr,k,base);
    if(t.stretched<0)t.stretched=sabreCurve(snr,k,stretched);
    if(t.flat<0)t.flat=sabreCurve(snr,k,flat);
    if(t.flat0<0)t.flat0=sabreCurve(snr,k,f0);
    if(t.flat1<0)t.flat1=sabreCurve(snr,k,f1);
}

// P57: the worker's GL contexts at low GPU priority (EGL_IMG_context_priority, Adreno / Mali / PowerVR), so the GPU preempts
// the merge for the viewfinder's and the compositor's draws (owner: the viewfinder stuttered heavily while a photo was processed;
// the X7 Ultra merge submits strips of ~1.4 s). The attribute is a hint: a driver without the extension, or one that refuses
// it, gets the context as before (and SCAM_GPU_NORMAL_PRIORITY=1 keeps it as before for comparisons). Results are unchanged.
// P364 (owner, 2026-10-27: processing as fast as the reference app): medium, not low. In a shot on the OPPO Find X7 Ultra the
// low context's merge took ~0.45 s longer than a replay without the viewfinder; medium: the merge 3.67-3.99 -> 3.46-3.54 s, the
// viewfinder during it ~20 -> ~12 fps (largest gap ~0.1 -> ~0.25 s, not the 1.4 s stalls of P57). SCAM_GPU_LOW_PRIORITY=1: low.
inline EGLContext scamProcessingContext(EGLDisplay display,EGLConfig config){
    const char* ext=eglQueryString(display,EGL_EXTENSIONS);
    if(ext&&std::strstr(ext,"EGL_IMG_context_priority")&&!std::getenv("SCAM_GPU_NORMAL_PRIORITY")){
        const EGLint level=std::getenv("SCAM_GPU_LOW_PRIORITY")?0x3103 /*EGL_CONTEXT_PRIORITY_LOW_IMG*/:0x3102 /*EGL_CONTEXT_PRIORITY_MEDIUM_IMG*/;
        const EGLint low[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,0x3100 /*EGL_CONTEXT_PRIORITY_LEVEL_IMG*/,
                            level,EGL_NONE};
        EGLContext c=eglCreateContext(display,config,EGL_NO_CONTEXT,low);
        if(c!=EGL_NO_CONTEXT)return c;
        eglGetError();
    }
    const EGLint attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,EGL_NONE};
    return eglCreateContext(display,config,EGL_NO_CONTEXT,attrs);
}

} // namespace scam
