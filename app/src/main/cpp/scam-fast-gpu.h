#pragma once
// The GLES 3.1 side of the fast merge route (scam-fast-merge.h): the context, the reference programs (scam-fast-shaders.h, generated)
// with the program binary cache, the prewarm and the teardown. Included early by scam-hybrid.h (hybridPrewarmGpu builds it).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <cstdio>
#include "scam-progress.h" // P509b: the GPU waits as fences for the app's watchdog
#include "scam-crash.h" // P500: the program being compiled is the crash site a driver crash names
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
#include <map>
#include <mutex>
#include <sys/stat.h>
#include <utime.h>
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
// P540 (owner, 2026-11-07: «засветы ... без артефактов»): > 0 = 0.94 x the lowest gain of the valid short frames. Where they
// carried the light (the final mask, or any value above the base frames' 1) the merge holds measured light up to their clip:
// flagged there at >= 1 (as before) the app recovered real data channel by channel (lime speckle in a sunlit gilded arch, white
// blocks in a tail light's glint, Ark burst 140421). There a channel is flagged only at the short frames' own clip.
uniform float u_clip_short;
bool clipAt(float x, bool shortData) {
    if (u_clip_short <= 0.0) return x >= u_clip;
    if (x >= u_clip_short) return true;
    return !shortData && x >= u_clip && x <= 1.02;
}
uniform highp sampler2D u_hl_base;  // P497: the base's clipped mask (HALF)
uniform highp sampler2D u_hl_final; // P497: the final mask (HALF, the short frames' validity applied)
uniform highp sampler2D u_base_bayer; // P497: the base frame's Bayer quads (HALF, sensor units)
uniform vec4 u_black;     // P497: its black level per quad channel
uniform float u_white;    // P497: its white level
uniform float u_moire;   // P497: Quad colour moire suppression (0 = off)
uniform float u_step;    // P501: OUT px per sensor px (the sabre_scale; the Quad filters' sensor offsets scaled, 1 = as before)
ivec2 sc(int dx, int dy) { return ivec2(round(vec2(float(dx), float(dy)) * u_step)); }
uniform int u_fill;       // P497: > 0 = the reach (OUT px) of the hole fill below (short frames merged), 0 = off
// P607 (owner, 2026-11-10, a street lamp at night: «артефакты на источниках света», dark blotches and a ragged star in its core):
// > 0 = the hole fill only between two sides. A light's short frames are refused on the rim of the base's clipped disc (its
// steep falloff 1 - 2 px off in their registration reads as 8x darker than the clipped base), and the fill stamped the light's
// peak into that rim from the inside alone: a hard step at the base's clip edge (13 -> 1, a dark outline once toned), and on a
// small lamp, whose rays all stopped, the fallback's rays through the dark reached its centre only along eight lines (bright
// spokes beside the rim's own 2 - 5: holes). The wire it was made for (P497) is a band inside a plateau, recovered light on both
// sides; now the fill brings its level only when two opposite rays found light (or one found it and the other the frame's edge) and
// it is not darker than the merged light; elsewhere it brings the colour alone, at the pixel's own level (its brightest channel): the
// rim keeps the short frames' falloff, and a refused pixel still loses the base's partial clip colour (a cyan G + B clip beside a
// lit window's measured R stayed a cyan patch with the fill off there) (0 = as before)
uniform float u_fill_light;
uniform float u_quad_lift; // P506: > 0 = a Quad stream with short frames merged: the lattice round recovered lights notched
// P596 (owner, 2026-11-10: «улучшить детализацию/резкость ... шарпинг трогать запрещаю»): deGrid's «flat ground» is a local change
// under 4 .. 10 % of the luma, a single 50 MP frame's noise. After 30 merged frames the noise is a few tenths of that, and fine
// low-contrast texture (asphalt, foliage, fabric, a face) under 4 % was blurred by the period-4 notch in both directions (its
// response 0.6 at 1/8 cycles / px, 0 at 1/4 - 1/2). x, y = the base frame's noise model (variance S v + O, normalised units),
// z > 0: «flat» from z .. 2.5 z times the merged luma noise's own value of that measure (sqrt((S Y + O) / frames) / Y, the
// frames from the merge's weights), never above the old 0.04 .. 0.10 - where few frames merged (moving subjects) as before.
// w > 0: the notch along a line (the beads of one frame's colour phase) fades out from w / 2 to w merged frames: with many
// frames of scattered sub-pixel shifts the phase is averaged out, and the notch blurred everything oriented (bark, twigs, hair,
// a slanted edge across 4 px) to its [1 2 2 2 1] / 8; the site response's static lattice is the lattice pass's (P566 / P571).
// 0 = as before.
uniform vec4 u_flat_noise;
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
vec4 fill(ivec2 p,bool cross,out bool paired){ // cross: through structures (the fallback when nothing is in reach otherwise)
    ivec2 lo=ivec2(0,u_acc_row0),hi=ivec2(u_size.x-1,u_acc_row0+textureSize(u_accum_color_r,0).y-1);
    ivec2 dirs[8]=ivec2[8](ivec2(1,0),ivec2(-1,0),ivec2(0,1),ivec2(0,-1),ivec2(1,1),ivec2(-1,-1),ivec2(1,-1),ivec2(-1,1));
    vec3 sum=vec3(0.0);float wsum=0.0;
    uint hit=0u,edge=0u; // P607: the rays that found light, the rays that reached the frame's edge (bit d)
    for(int d=0;d<8;++d){
        ivec2 dd=dirs[d];float f=d<4?1.0:1.4142;
        for(int s=1;s<=u_fill;++s){
            ivec2 t=p+dd*s;
            if(any(lessThan(t,lo))||any(greaterThan(t,hi))){edge|=1u<<uint(d);break;}
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
            hit|=1u<<uint(d);
            break;
        }
    }
    // opposite rays are the pairs (0, 1), (2, 3), (4, 5), (6, 7)
    uint ok=hit|edge,two=ok&(ok>>1u)&0x55u,one=(hit|(hit>>1u))&0x55u;
    paired=(two&one)!=0u;
    return vec4(wsum>0.0?sum/wsum:vec3(0.0),wsum);
}
// P497 (owner, 2026-11-04, a 50 MP Quad shot: «муар на шторах», purple outlines, lavender speaker grilles): a 2 x 2 Quad samples
// colour every 4 px, so a fine neutral mesh (lace, a grille) gets per-pixel false colour (chroma sd 0.046 there vs 0.025 on the
// flat wall); the post's chroma denoise gathers it into purple / green blotches. In a textured (luma HF), near-neutral (local mean
// colour) spot the pixel takes the local mean colour at its own luma; a coloured edge (red print) keeps its colour
// P497 (owner, 2026-11-04: «сетка» on 2x ISZ from the wide and main cameras, the cage in a 50 MP Quad shot): a 2 x 2 Quad leaves a
// period-4 luma pattern - its pixel response inside a block (QPC, +-15 % at the frame's edge raw, +-0.8 % left on a flat wall
// after the merge) and its colour phase along a thin line (a horizontal bar: beads every 4 px, 3 - 5 %). Along a line the period
// 4 is notched in the line's direction only ([1 2 2 2 1] / 8 there, the cross direction keeps its sharpness); on flat ground in
// both; texture without one direction (print, diagonals) is left alone. The pixel's colour ratio is kept
float lumaAt(ivec2 p, ivec2 lo, ivec2 hi) { return dot(at(clamp(p, lo, hi)), vec3(0.25, 0.5, 0.25)); }
vec3 deGrid(ivec2 p, vec3 v) {
    ivec2 lo = ivec2(0, u_acc_row0), hi = ivec2(u_size.x - 1, u_acc_row0 + textureSize(u_accum_color_r, 0).y - 1);
    float y0 = dot(v, vec3(0.25, 0.5, 0.25));
    if (y0 <= 1.0e-5) return v;
    float xm2 = lumaAt(p + sc(-2, 0), lo, hi), xm1 = lumaAt(p + sc(-1, 0), lo, hi), xp1 = lumaAt(p + sc(1, 0), lo, hi), xp2 = lumaAt(p + sc(2, 0), lo, hi);
    float ym2 = lumaAt(p + sc(0, -2), lo, hi), ym1 = lumaAt(p + sc(0, -1), lo, hi), yp1 = lumaAt(p + sc(0, 1), lo, hi), yp2 = lumaAt(p + sc(0, 2), lo, hi);
    float nx = (xm2 + 2.0 * (xm1 + y0 + xp1) + xp2) / 8.0; // the period-4 notch along x
    float ny = (ym2 + 2.0 * (ym1 + y0 + yp1) + yp2) / 8.0; // along y
    // structure: the change across (a horizontal line changes along y), over 4 px so the period 4 itself does not count
    float gx = abs(xp2 - xm2) + 0.5 * abs(lumaAt(p + sc(2, 1), lo, hi) - lumaAt(p + sc(-2, 1), lo, hi)) + 0.5 * abs(lumaAt(p + sc(2, -1), lo, hi) - lumaAt(p + sc(-2, -1), lo, hi));
    float gy = abs(yp2 - ym2) + 0.5 * abs(lumaAt(p + sc(1, 2), lo, hi) - lumaAt(p + sc(1, -2), lo, hi)) + 0.5 * abs(lumaAt(p + sc(-1, 2), lo, hi) - lumaAt(p + sc(-1, -2), lo, hi));
    float rel = (gx + gy) / (4.0 * y0);
    float flatLo = 0.04, flatHi = 0.10; // a single 50 MP frame's noise alone reaches ~0.04
    float frames = u_flat_noise.z > 0.0 || u_flat_noise.w > 0.0 ? texelFetch(u_frame_weights, p * u_half / u_size, 0).r + 1.0 : 1.0;
    float relF = rel, dx4 = 0.0, dy4 = 0.0, m = 1.0;
    if (u_flat_noise.z > 0.0 || u_flat_noise.w > 0.0) { // P596: +-4 px, blind to the Quad lattice's periods 4 and 2
        dx4 = abs(lumaAt(p + sc(4, 0), lo, hi) - lumaAt(p + sc(-4, 0), lo, hi));
        dy4 = abs(lumaAt(p + sc(0, 4), lo, hi) - lumaAt(p + sc(0, -4), lo, hi));
        m = max(sqrt(max(u_flat_noise.x * y0 + u_flat_noise.y, 0.0) / max(frames, 1.0)) / y0, 1.0e-6); // the merged noise's level
    }
    if (u_flat_noise.z > 0.0) {
        flatLo = min(flatLo, u_flat_noise.z * m);
        flatHi = max(min(flatHi, 2.5 * u_flat_noise.z * m), flatLo + 1.0e-6);
        // the +-2 px differences span a whole period 4 (blind to the Quad lattice, as meant) and so nearly a whole 4.5 px stripe
        // too (0.68 of it: a facade's grooves read as flat, 140309); +-4 px is as blind to the periods 4 and 2 and sees them
        // (1.3), less its noise
        relF = max(rel, max((dx4 + dy4) / (2.0 * y0) - 1.5 * m, 0.0));
    }
    float flatK = 1.0 - smoothstep(flatLo, flatHi, relF);
    float hline = smoothstep(1.5, 3.0, gy / max(gx, 1.0e-5 * y0)) * (1.0 - flatK); // a horizontal line: notch along x
    float vline = smoothstep(1.5, 3.0, gx / max(gy, 1.0e-5 * y0)) * (1.0 - flatK);
    if (u_flat_noise.w > 0.0) { // P596: the line notch where few frames merged
        float few = 1.0 - smoothstep(0.5 * u_flat_noise.w, u_flat_noise.w, frames);
        // and not on a line with structure along it at other periods than the beads' 4 (vertical grooves crossing a horizontal
        // seam or letter: the notch along x took them where the beads' period 4 gives no +-4 px change)
        // (above the noise: 2 .. 4 times its level, so noise and the beads' field are notched as before)
        hline *= few * (1.0 - smoothstep(0.15, 0.4, dx4 / max(0.5 * gy, 1.0e-5 * y0)) * smoothstep(2.0 * m, 4.0 * m, dx4 / y0));
        vline *= few * (1.0 - smoothstep(0.15, 0.4, dy4 / max(0.5 * gx, 1.0e-5 * y0)) * smoothstep(2.0 * m, 4.0 * m, dy4 / y0));
    }
    float yt = y0;
    yt = mix(yt, nx, hline);
    yt = mix(yt, ny, vline);
    yt = mix(yt, 0.5 * (nx + ny), flatK);
    return v * (yt / y0);
}
vec3 antiMoire(ivec2 p, vec3 v) {
    ivec2 lo = ivec2(0, u_acc_row0), hi = ivec2(u_size.x - 1, u_acc_row0 + textureSize(u_accum_color_r, 0).y - 1);
    vec3 wbg = 1.0 / u_inv_wb;
    const vec3 L = vec3(0.25, 0.5, 0.25);
    vec3 s = vec3(0.0);
    float ys[9];
    float ysum = 0.0;
    int i = 0;
    for (int dy = -2; dy <= 2; dy += 2) for (int dx = -2; dx <= 2; dx += 2) {
        vec3 u = at(clamp(p + sc(dx, dy), lo, hi)) * wbg;
        s += u;
        float y = dot(u, L);
        ys[i++] = y;
        ysum += y;
    }
    vec3 m = s / 9.0;
    float ym = ysum / 9.0;
    float hf = 0.0;
    for (int j = 0; j < 9; ++j) hf += abs(ys[j] - ym);
    hf /= 9.0 * max(ym, 1.0e-4);
    float mx = max(m.r, max(m.g, m.b)), mn = min(m.r, min(m.g, m.b));
    float msat = (mx - mn) / max(mx, 1.0e-4);
    float a = u_moire * smoothstep(0.02, 0.05, hf) * (1.0 - smoothstep(0.15, 0.3, msat)); // lace hf ~0.05, the wall 0.02; lace colour ~0.08-0.15
    if (a <= 0.0) return v;
    vec3 vw = v * wbg;
    vec3 target = m * (dot(vw, L) / max(dot(m, L), 1.0e-6));
    return mix(vw, target, a) / wbg;
}
// P506 (owner, 2026-11-05, 2x ISZ sunset: «сетка вокруг блика»): in a Quad stream round a recovered light (glitter, the sun's
// path) the 2 x 2 blocks alternate between a neutral recovered level (the green blocks the base clipped: 1.45 x white) and the
// measured orange of the red / blue blocks between them (0.9): a dot lattice of period 4. Its sign: the pixels 2 px away on both
// sides are of one phase (alike) and differ from this one, where an edge changes across those 4 px. Inside the base's clipped
// mask such a pixel takes the period-4 notch of RGB ([1 2 2 2 1] / 8 along x and y); edges and the texture round them are kept
vec3 quadDeLattice(ivec2 p, vec3 v) {
    float mk = max(textureLod(u_hl_base, halfUv(p), 0.0).r, textureLod(u_hl_final, halfUv(p), 0.0).r);
    float a = smoothstep(0.02, 0.15, mk) * u_quad_lift;
    if (a <= 0.0) return v;
    ivec2 lo = ivec2(0, u_acc_row0), hi = ivec2(u_size.x - 1, u_acc_row0 + textureSize(u_accum_color_r, 0).y - 1);
    float y0 = dot(v, vec3(0.25, 0.5, 0.25));
    float xm = lumaAt(p + sc(-2, 0), lo, hi), xp = lumaAt(p + sc(2, 0), lo, hi);
    float ym = lumaAt(p + sc(0, -2), lo, hi), yp = lumaAt(p + sc(0, 2), lo, hi);
    float ref = 0.25 * (xm + xp + ym + yp);
    float lat = abs(y0 - ref) / max(max(y0, ref), 1.0e-4);           // this pixel against the opposite phase
    float edge = (abs(xp - xm) + abs(yp - ym)) / max(2.0 * ref, 1.0e-4); // the change across 4 px (0 for a pure lattice)
    a *= smoothstep(0.08, 0.2, lat) * (1.0 - smoothstep(1.0, 2.0, edge / max(lat, 1.0e-4)));
    if (a <= 0.0) return v;
    vec3 s = vec3(0.0);
    for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx)
        s += (abs(dx) == 2 ? 1.0 : 2.0) * (abs(dy) == 2 ? 1.0 : 2.0) * at(clamp(p + sc(dx, dy), lo, hi)); // no arrays (P500)
    return mix(v, s / 64.0, a);
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
            if(a>0.0){
                bool two;vec4 fv=fill(p,false,two);if(fv.a<=0.0)fv=fill(p,true,two);
                if(fv.a>0.0&&u_fill_light>0.0){ // P607: the level only between two sides and never darker; else the colour alone
                    float vm=max(v.r,max(v.g,v.b)),fm=max(fv.r,max(fv.g,fv.b));
                    float full=(two?1.0:0.0)*smoothstep(0.95,1.05,fm/max(vm,1.0e-6));
                    fv.rgb=mix(fv.rgb,mix(fv.rgb*(vm/max(fm,1.0e-6)),fv.rgb,full),clamp(u_fill_light,0.0,1.0));
                }
                if(fv.a>0.0)v=mix(v,fv.rgb,a);
            }
        }
        if(u_quad_lift>0.0)v=quadDeLattice(p,v);
        if(u_moire>0.0)v=deGrid(p,antiMoire(p,v));
        c[k]=v;
        float cf=b.z/max(b.w,1.0e-6);
        uint f=0u;
        bool sd=u_clip_short>0.0&&textureLod(u_hl_final,halfUv(p),0.0).r>0.5;
        if(clipAt(v.r,sd))f|=1u;
        if(clipAt(v.g,sd))f|=2u;
        if(clipAt(v.b,sd))f|=4u;
        if(cf>0.02&&cf<0.98&&!(sd&&max(v.r,max(v.g,v.b))>1.02))f|=8u; // P540: not over the short frames' measured light
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
    // P501: each HALF cell once, by the first OUT pixel over it (p = ceil(h OUT / HALF)); at OUT = 2 HALF the even pixels, as before
    for(int k=0;k<4;++k){
        ivec2 p=ivec2(q*4+k,y);
        ivec2 h=p*u_half/u_size;
        if(p==(h*u_size+u_half-ivec2(1))/u_half&&h.x<u_half.x&&h.y<u_half.y)eff[h.y*u_half.x+h.x]=texelFetch(u_frame_weights,h,0).r+1.0;
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
// P500 (Pixel 10 Pro XL, PowerVR D-Series: «FAST MERGE GPU shader: Compile failed.» on sh000, whose median9 takes an array
// argument and sorts it in place): the same per-channel median of the 3 x 3 neighbourhood by Devillard's opt_med9 min / max
// network on vec3 - it selects the same value, so the result is bit for bit the reference shader's. Used when sh000 does not
// compile (and with SCAM_FM_MEDIAN_NET in a replay, the check of that).
static const char* kFastMedianAlignNet=R"FM(#version 310 es
precision highp float;
precision highp int;
uniform sampler2D uAlignment;
uniform ivec2 uGridSize;
out vec4 oAlignment;
vec3 at(ivec2 p, int ox, int oy) {
    return texelFetch(uAlignment, clamp(p + ivec2(ox, oy), ivec2(0), uGridSize - ivec2(1)), 0).xyz;
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 p0 = at(p, -1, -1), p1 = at(p, 0, -1), p2 = at(p, 1, -1);
    vec3 p3 = at(p, -1, 0), p4 = at(p, 0, 0), p5 = at(p, 1, 0);
    vec3 p6 = at(p, -1, 1), p7 = at(p, 0, 1), p8 = at(p, 1, 1);
    vec3 t;
#define S2(a, b) t = a; a = min(t, b); b = max(t, b);
    S2(p1, p2) S2(p4, p5) S2(p7, p8) S2(p0, p1) S2(p3, p4) S2(p6, p7)
    S2(p1, p2) S2(p4, p5) S2(p7, p8) S2(p0, p3) S2(p5, p8) S2(p4, p7)
    S2(p3, p6) S2(p1, p4) S2(p2, p5) S2(p4, p7) S2(p4, p2) S2(p6, p4)
    S2(p4, p2)
    oAlignment = vec4(p4, 0.0);
}
)FM";
// P501 (owner, 2026-11-04: «x200 pro не у меня в руках, давай без смартфона чинить»): the vivo X200 Pro's Mali-G925 driver
// (MT6991, Android 17 PD2405_A_17.2.11.3) dies with SIGSEGV inside its compiler (libGLES_mali.so+0x1eda70c, under
// cmpbe_v2_compile_multiple_shaders) on kHybCells, whose array argument the function changed (kHybCellsPlain without it compiles
// there), and the PowerVR D-Series refuses sh000's median9, which sorts its array argument in place. The fast route's programs
// with an array parameter - sh000 (median9), sh059 / sh035 (GetThreeBayerQuadFromExtractedBayer's out arrays) and sh025
// (median9_valid) - are given here without one, the same arithmetic in the same order (the medians as the min / max network: the
// same element selected), so the result is bit for bit the reference shader's. Taken on every Mali GPU (the crash kills the worker
// before any fallback could run) and with SCAM_FM_ARRAY_FREE in a replay (the check of the equality); else the reference's text.
inline std::string fastFunctionBody(const std::string& src,const std::string& signature,size_t& at,size_t& end){
    at=src.find(signature);
    if(at==std::string::npos)throw std::runtime_error("FAST MERGE GPU: array-free variant: no "+signature);
    end=src.find("\n}\n",at);
    if(end==std::string::npos)throw std::runtime_error("FAST MERGE GPU: array-free variant: no end of "+signature);
    end+=3;
    return src.substr(at,end-at);
}
inline void fastReplaceOnce(std::string& s,const std::string& a,const std::string& b){
    const size_t at=s.find(a);
    if(at==std::string::npos||s.find(a,at+1)!=std::string::npos)throw std::runtime_error("FAST MERGE GPU: array-free variant: not one "+a);
    s.replace(at,a.size(),b);
}
// the guides' GetThreeBayerQuadFromExtractedBayer with each out array as its three elements (raw: the full guide's raw values too)
inline std::string fastGuideArrayFree(const char* reference,bool raw){
    std::string s(reference);
    const std::string sig=raw?"void GetThreeBayerQuadFromExtractedBayer(in vec2 pos, out vec4 values[3], out vec4 raw_values[3]) {"
                             :"void GetThreeBayerQuadFromExtractedBayer(in vec2 pos, out vec4 values[3]) {";
    size_t at=0,end=0;
    std::string body=fastFunctionBody(s,sig,at,end);
    for(int i=0;i<3;++i){
        const std::string k=std::to_string(i);
        if(raw)fastReplaceOnce(body,"raw_values["+k+"]","raw_values"+k);
        fastReplaceOnce(body,"  values["+k+"]","  values"+k);
    }
    fastReplaceOnce(body,sig,raw?"void GetThreeBayerQuadFromExtractedBayer(in vec2 pos, out vec4 values0, out vec4 values1, out vec4 values2, "
                                  "out vec4 raw_values0, out vec4 raw_values1, out vec4 raw_values2) {"
                                :"void GetThreeBayerQuadFromExtractedBayer(in vec2 pos, out vec4 values0, out vec4 values1, out vec4 values2) {");
    s.replace(at,end-at,body);
    const std::string call="GetThreeBayerQuadFromExtractedBayer(tc_mirror + vec2(0.0, tex_size.w * float(y)), rggb_values";
    fastReplaceOnce(s,call+(raw?", raw_rggb_values);":");"),call.substr(0,call.size()-std::string("rggb_values").size())
        +"rggb_values[0], rggb_values[1], rggb_values[2]"+(raw?", raw_rggb_values[0], raw_rggb_values[1], raw_rggb_values[2]);":");"));
    return s;
}
// sh025's median of the 3 x 3 validity: the selection sort's a[4] is the fifth smallest, the network's p4 the same element
inline std::string fastCleanValidArrayFree(const char* reference){
    std::string s(reference);
    size_t at=0,end=0;
    fastFunctionBody(s,"float median9_valid(float a[9]) {",at,end);
    s.replace(at,end-at,
        "float median9_valid(float p0, float p1, float p2, float p3, float p4, float p5, float p6, float p7, float p8) {\n"
        "    float t;\n"
        "#define S2(a, b) t = a; a = min(t, b); b = max(t, b);\n"
        "    S2(p1, p2) S2(p4, p5) S2(p7, p8) S2(p0, p1) S2(p3, p4) S2(p6, p7)\n"
        "    S2(p1, p2) S2(p4, p5) S2(p7, p8) S2(p0, p3) S2(p5, p8) S2(p4, p7)\n"
        "    S2(p3, p6) S2(p1, p4) S2(p2, p5) S2(p4, p7) S2(p4, p2) S2(p6, p4)\n"
        "    S2(p4, p2)\n"
        "#undef S2\n"
        "    return p4;\n"
        "}\n");
    fastReplaceOnce(s,"median9_valid(v);","median9_valid(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);");
    return s;
}
inline const std::string& fastGuideQuadArrayFree(){static const std::string t=fastGuideArrayFree(kFastGuideQuad,false);return t;}
inline const char* fastGuideQuadArrayFreeText(){return fastGuideQuadArrayFree().c_str();}
// P569 (owner, 2026-11-09: «Ускорь и оптимизируй всё максимально ... без ухудшения качества»; the nubia's fast route: the merge
// and the guide of a 50 MP Quad shot, whole frame and again per row strip, ~5 s of its 7.4 s on the GPU): the Quad programs' local
// arrays written at a run-time index - sh051 / sh103's accumulator per colour (accumulatedIntensities[dest_ch], sum_ch[qc],
// wt_ch[qc]) and the CFA tables (k_cfa_map, k_ch_to_rgb, k_guide_cfa_map) - are what a mobile compiler puts in scratch memory.
// On every GPU they are given as the same arithmetic without a run-time index: the tables are XOR / shift (k_cfa_map[p * 4 + ph]
// = ph ^ p for its four rows, k_ch_to_rgb[c] = (c + 1) >> 1), an accumulator a[k] += v as a += v x (k == i) per component (the
// other components add +0.0: every added value is >= 0). The sums are the reference's (a compiler may fuse a multiply-add
// differently: 1 ulp). SCAM_FM_INDEXED: the reference's text (replays).
inline std::string fastQuadIndexFree(const char* reference){
    std::string s(reference);
    fastReplaceOnce(s,"    return k_cfa_map[p * 4 + ph];","    return ph ^ p;");
    fastReplaceOnce(s,"            int dest_ch = k_ch_to_rgb[ch];\n            accumulatedIntensities[dest_ch] += intensity * w;\n"
                      "            accumulatedWeights[dest_ch]     += w;",
                      "            vec3 dest_sel = vec3(equal(ivec3(0, 1, 2), ivec3((ch + 1) >> 1)));\n"
                      "            accumulatedIntensities += dest_sel * (intensity * w);\n"
                      "            accumulatedWeights     += dest_sel * w;");
    fastReplaceOnce(s,"            float sum_ch[4] = float[4](0.0, 0.0, 0.0, 0.0);\n            float wt_ch[4]  = float[4](0.0, 0.0, 0.0, 0.0);",
                      "            vec4 sum_ch = vec4(0.0);\n            vec4 wt_ch  = vec4(0.0);");
    fastReplaceOnce(s,"                    sum_ch[qc] += qm * sw;\n                    wt_ch[qc]  += sw;",
                      "                    vec4 qc_sel = vec4(equal(ivec4(0, 1, 2, 3), ivec4(qc)));\n"
                      "                    sum_ch += qc_sel * (qm * sw);\n                    wt_ch  += qc_sel * sw;");
    return s;
}
inline std::string fastGuideQuadIndexFree(const std::string& reference){
    std::string s(reference);
    fastReplaceOnce(s,"  return k_guide_cfa_map[clamp(u_cfa_pattern, 0, 3) * 4 + phase];","  return phase ^ clamp(u_cfa_pattern, 0, 3);");
    return s;
}
// the variant of src without run-time indices (P569), or src: once per program text, the reference's text when a pattern is missing
inline const char* fastIndexFree(const char* src){
    if(std::getenv("SCAM_FM_INDEXED"))return src;
    static std::mutex m;static std::map<const char*,std::string> made;
    std::lock_guard<std::mutex> l(m);
    auto it=made.find(src);
    if(it==made.end()){
        std::string v;
        try{
            if(src==kFastMergeQuad||src==kFastMergeQuadQuad)v=fastQuadIndexFree(src);
            else if(src==kFastGuideQuad)v=fastGuideQuadIndexFree(src);
            else if(src==fastGuideQuadArrayFreeText())v=fastGuideQuadIndexFree(src);
        }catch(const std::exception&){v.clear();}
        it=made.emplace(src,v).first;
    }
    return it->second.empty()?src:it->second.c_str();
}
// the reference's source, or its array-free variant (built once; the pointer names the program in the shader fallbacks)
inline const char* fastArrayFree(const char* src){
    static const std::string guide=fastGuideArrayFree(kFastGuide,true),clean=fastCleanValidArrayFree(kFastHlCleanValid);
    const std::string& guideQuad=fastGuideQuadArrayFree();
    if(src==kFastMedianAlign)return kFastMedianAlignNet;
    if(src==kFastGuide)return guide.c_str();
    if(src==kFastGuideQuad)return guideQuad.c_str();
    if(src==kFastHlCleanValid)return clean.c_str();
    return src;
}
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
                pCoarseShift,pHomoPick, // P497: the KLT seed of a frame after the press (coarse translation search), the better homography
                pHlMaskOut, // P480: built on first use (hlMaskOutProgram), not at init: a burst without the highlight path never builds it
                pFlowPick,pFlowBg,pCyclopsRescue, // P601: built with the rest when they can be (flowPickProgram / flowPickReady)
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
            // P501: the programs without array parameters on Mali (fastArrayFree); P505: on PowerVR too (its compiler refused
            // sh000's median9(float v[9]); the variants are bit-exact, so this costs nothing on a driver that would compile them)
            arrayFree=renderer.find("Mali")!=std::string::npos||renderer.find("PowerVR")!=std::string::npos||std::getenv("SCAM_FM_ARRAY_FREE");
            if(arrayFree)notes.push_back("FAST MERGE GPU: programs without array parameters ("+renderer+")");
            prog[pUnpack]=cs(kFastUnpackFused);prog[pUnpackBuf]=cs(kFastUnpackFusedBuf);prog[pGuide]=fs(kFastGuide);prog[pRef]=fs(kFastBaseRefColor);prog[pUnblock]=cs(kFastUnblocker);
            prog[pDown2]=fs(kFastGrayDown2);prog[pDown4]=fs(kFastGrayDown4);prog[pProducts]=cs(kFastGradProducts);prog[pGuide8]=fs(kFastPyrToGuide8);
            prog[pCorners]=cs(kFastHomoCorners);prog[pKlt]=cs(kFastHomoKlt);prog[pRansac]=cs(kFastHomoRansac);prog[pFill]=cs(kFastHomoFillFlow);prog[pCoarseShift]=cs(kFastCoarseShift);prog[pHomoPick]=cs(kFastHomoPick);
            prog[pUpsample]=cs(kFastUpsampleAlign);prog[pLk]=cs(kFastBlockLk);prog[pMedian]=fs(std::getenv("SCAM_FM_MEDIAN_NET")?kFastMedianAlignNet:kFastMedianAlign);prog[pSparse]=fs(kFastAlignSparse);
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
            // P601: the flow pick's programs with the rest (the default fastFlowPick 2 uses them on every shot); one that does not
            // build leaves the pick off for the merge (flowPickReady), not the route
            try{flowPickProgram(true);}catch(const std::exception& e){notes.push_back(std::string("FAST MERGE GPU: flow pick programs unavailable (")+e.what()+")");}
            if(compiled>0)trimCache();
            check("init");
        }catch(...){cleanup();throw;}
        initMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
    }
    /** P500: lines for the worker's report (a shader that needed its plain variant), taken by the merge. */
    std::vector<std::string> notes;
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
    void flowPickProgram(bool rescue){ // P601 (throws when one does not build)
        if(!prog[pFlowPick])prog[pFlowPick]=fs(fastmerge::kFastFlowPick);
        if(!prog[pFlowBg])prog[pFlowBg]=fs(fastmerge::kFastFlowBg);
        if(rescue&&!prog[pCyclopsRescue])prog[pCyclopsRescue]=fs(fastmerge::kFastCyclopsRescue);
    }
    bool flowPickReady(bool rescue){ // P601: the programs, built now if missing; false (and a note) when they do not build
        try{flowPickProgram(rescue);return true;}
        catch(const std::exception& e){notes.push_back(std::string("FAST MERGE GPU: flow pick off (")+e.what()+")");return false;}
    }
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
        scam_progress::FenceWait fw; // P509b: a hung GPU shows here as a fence
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
        utime(file.c_str(),nullptr); // P501: a hit is fresh for trimCache (least recently used goes first)
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
    GLuint tryShader(GLenum type,const char* src,std::string& log){
        GLuint s=glCreateShader(type);
        glShaderSource(s,1,&src,nullptr);
        // P500 (vivo X200 Pro, Mali-G925, Android 17: the driver's compiler died on the fast route's programs, the crash said
        // «EGL warm-up» and the app took the GPU for unusable - every shot one frame): the crash report names the fast route, so
        // the app leaves only the fast route out (fastMerge 0) and the shot merges on the classic route
        static thread_local char stage[128];
        std::snprintf(stage,sizeof(stage),"GPU compile fast: %.100s",shaderIdentity(src).c_str());
        const char* const previous=worker_crash::threadStage();
        worker_crash::mark(stage);
        glCompileShader(s);
        worker_crash::setThreadStage(previous);
        GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
        if(ok)return s;
        char msg[2048]{};glGetShaderInfoLog(s,sizeof(msg),nullptr,msg);glDeleteShader(s);
        log=msg;
        return 0;
    }
    /** P500: the first declaration lines of a shader that would not compile (the driver's log may say only «Compile failed.»). */
    static std::string shaderIdentity(const char* src){
        std::string out;
        const std::string s(src);
        size_t at=0;
        for(int lines=0;at<s.size()&&lines<4;){
            size_t end=s.find('\n',at);if(end==std::string::npos)end=s.size();
            const std::string line=s.substr(at,end-at);at=end+1;
            if(line.empty()||line[0]=='#'||line.rfind("precision",0)==0||line.rfind("//",0)==0)continue;
            out+=(out.empty()?"":" | ")+line.substr(0,90);++lines;
        }
        return out;
    }
    GLuint shader(GLenum type,const char* src){
        std::string log;
        if(GLuint s=tryShader(type,src,log))return s;
        // P500 (Pixel 10 Pro XL, PowerVR D-Series: «FAST MERGE GPU shader: Compile failed.», the fast merge never ran there): the
        // optional GL_EXT_gpu_shader5 line (textureGather with a constant component is core ES 3.1) is left out and it is tried
        // again; the line becomes an empty one so the driver's line numbers still match
        std::string plain(src);
        const std::string ext="#extension GL_EXT_gpu_shader5 : enable";
        bool changed=false;
        for(size_t at=plain.find(ext);at!=std::string::npos;at=plain.find(ext,at)){plain.replace(at,ext.size(),"");changed=true;}
        std::string second;
        if(changed)if(GLuint s=tryShader(type,plain.c_str(),second)){
            notes.push_back("FAST MERGE GPU shader: compiled without GL_EXT_gpu_shader5 ("+shaderIdentity(src)+")");
            return s;
        }
        // P500: sh000 (the alignment's median) as a min / max network (same result) when the driver refuses its array argument
        if(src==fastmerge::kFastMedianAlign){
            std::string third;
            if(GLuint s=tryShader(type,fastmerge::kFastMedianAlignNet,third)){
                notes.push_back("FAST MERGE GPU shader: the alignment median as a min / max network ("+log+")");
                return s;
            }
            log+=" / the median network: "+third;
        }
        throw std::runtime_error("FAST MERGE GPU shader: "+log+(changed?" / without GL_EXT_gpu_shader5: "+second:"")+" ["+shaderIdentity(src)+"]");
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
    bool arrayFree=false; // P501: fastArrayFree's variants (Mali)
    GLuint cs(const char* src){return link({{GL_COMPUTE_SHADER,fastmerge::fastIndexFree(arrayFree?fastmerge::fastArrayFree(src):src)}});}
    GLuint fs(const char* src){return link({{GL_VERTEX_SHADER,fastmerge::kFastVs},{GL_FRAGMENT_SHADER,fastmerge::fastIndexFree(arrayFree?fastmerge::fastArrayFree(src):src)}});}
};

// The context with its programs made on the worker's EGL warm-up thread while it waits for the burst (as laGpuPrewarm)
struct FastGpuPrewarm { std::mutex m;std::condition_variable cv;bool started=false,done=false,abandoned=false;std::unique_ptr<FastMergeGpu> gpu;std::string error; };
inline FastGpuPrewarm& fastGpuPrewarm(){static FastGpuPrewarm p;return p;}
// Marked on the starting thread before the warm-up thread runs (the merge may ask for the context before that thread is scheduled);
// fastGpuPrewarmCancel when the thread could not be started
inline void fastGpuPrewarmBegin(){FastGpuPrewarm& p=fastGpuPrewarm();std::lock_guard<std::mutex> l(p.m);p.started=true;}
inline void fastGpuPrewarmCancel(const char* why="no thread"){FastGpuPrewarm& p=fastGpuPrewarm();{std::lock_guard<std::mutex> l(p.m);p.error=why;p.done=true;}p.cv.notify_all();}
inline void fastGpuPrewarmMake(const std::string& cache){
    FastGpuPrewarm& p=fastGpuPrewarm();
    {std::lock_guard<std::mutex> l(p.m);p.started=true;}
    std::unique_ptr<FastMergeGpu> g;std::string error;
    try{g=std::make_unique<FastMergeGpu>(cache);g->release();}catch(const std::exception& e){g.reset();error=e.what();}
    {std::lock_guard<std::mutex> l(p.m);if(p.abandoned){(void)g.release();}else{p.gpu=std::move(g);p.error=error;}p.done=true;} // (P501: given up: leaked to the exit)
    p.cv.notify_all();
}
// why: "" when it came; else not started / its error. P501 (Pixel 10 Pro log of build 31224: the merge waited for the warm-up
// thread, which never finished in the PowerVR driver - no progress until the app's 90 s watchdog): at most kFastPrewarmWaitMs, then
// the merge makes its own context (the warm-up's, if it ever comes, is left to the process exit)
constexpr int kFastPrewarmWaitMs=8000;
inline std::unique_ptr<FastMergeGpu> fastGpuPrewarmTake(std::string& why){
    FastGpuPrewarm& p=fastGpuPrewarm();
    std::unique_lock<std::mutex> l(p.m);
    if(!p.started){why="no prewarm";return nullptr;}
    if(!p.cv.wait_for(l,std::chrono::milliseconds(kFastPrewarmWaitMs),[&]{return p.done;})){
        why="the warm-up still running after "+std::to_string(kFastPrewarmWaitMs/1000)+" s: a context of the merge's own";
        p.abandoned=true;
        return nullptr;
    }
    why=p.gpu?"":"prewarm failed: "+p.error;
    return std::move(p.gpu);
}
// The context destroyed on a thread of its own after the merge (its ~400 MB of textures and the EGL teardown are not the shot's),
// joined at the process exit
struct FastMergeTeardown { std::thread t; ~FastMergeTeardown(){if(t.joinable())t.join();} };
inline FastMergeTeardown& fastMergeTeardown(){static FastMergeTeardown t;return t;}

} // namespace scam
