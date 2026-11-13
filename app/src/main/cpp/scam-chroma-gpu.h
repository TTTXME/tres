#pragma once
// P80 (owner, 2026-10-10: move the CPU steps of the shot to the GPU): mosaicChromaMedian (scam-hybrid.h) on the GPU. The same
// steps in the same order: R/G and B/G ratios, the separable 5-point medians (selections only: the same values), the luma
// high-pass, the soft sign-change count, the box filters and the false-colour blend. The boxes sum their taps in float here (the CPU
// keeps a running double sum) and the soft count runs in float (double there): the result differs by float rounding only.
// One context per call (the merge's context is gone by then); any failure throws and the caller runs the CPU version.
#include "scam-glsl-guard.h" // P662 SCAM_GLSL_DUMP
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <future>
#include <memory>
#include <thread>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam {

static const char* kChromaGlslCommon = R"(
precision highp float; precision highp int;
uniform ivec2 size;
uniform int rowBase;
float ssf(float x){x=clamp(x,0.0,1.0);return x*x*(3.0-2.0*x);}
)";
// rgb -> R/G, B/G ratios (uv) and the luma (L)
static const char* kChromaGlslRatio = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Rgb{float rgb[];};
layout(std430,binding=1) writeonly buffer Uv{vec2 uv[];};
layout(std430,binding=2) writeonly buffer Ls{float L[];};
uniform float eps;
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int i=y*size.x+x;
    float r=rgb[3*i],g=rgb[3*i+1],b=rgb[3*i+2],gg=max(g,0.0)+eps;
    uv[i]=vec2((r+eps)/gg,(b+eps)/gg);
    L[i]=0.25*r+0.5*g+0.25*b;
}
)";
// median of five by selection (mosaicChromaMedian med5), on both ratios; dir 0 horizontal, 1 vertical; taps d apart, clamped
static const char* kChromaGlslMedian = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=1) readonly buffer In{vec2 a[];};
layout(std430,binding=3) writeonly buffer Out{vec2 o[];};
uniform int d;
uniform int dir;
vec2 med5(vec2 a,vec2 b,vec2 c,vec2 d,vec2 e){
    vec2 t1=min(a,b),t2=max(a,b),t3=min(c,d),t4=max(c,d);
    vec2 lo=max(t1,t3),hi=min(t2,t4);
    vec2 x=min(lo,hi),y=max(lo,hi);
    return max(x,min(y,e));
}
vec2 at(int x,int y,int k){
    if(dir==0)return a[y*size.x+clamp(x+k*d,0,size.x-1)];
    return a[clamp(y+k*d,0,size.y-1)*size.x+x];
}
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    o[y*size.x+x]=med5(at(x,y,-2),at(x,y,-1),at(x,y,0),at(x,y,1),at(x,y,2));
}
)";
// P306: the medians smoothed by [1 2 1] x [1 2 1] / 16 for taps d >= 2 (mosaicSmooth121Row of scam-hybrid.h: the two column / row
// parities of the 5-point taps meet; additions only, b + b then left to right), borders clamped
static const char* kChromaGlslSmooth = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=1) readonly buffer In{vec2 a[];};
layout(std430,binding=3) writeonly buffer Out{vec2 o[];};
vec2 tap(int x,int y){
    int r=clamp(y,0,size.y-1)*size.x;
    vec2 b=a[r+x],t=b+b;
    vec2 s=a[r+max(x-1,0)]+t;
    s=s+a[r+min(x+1,size.x-1)];
    return s*0.25;
}
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    vec2 m=tap(x,y),t=m+m;
    vec2 s=tap(x,y-1)+t;
    s=s+tap(x,y+1);
    o[y*size.x+x]=s*0.25;
}
)";
// box mean of radius r (clamped taps); T float or vec2. P80: a group of 64 x 4 outputs (horizontal: 64 columns of 4 rows;
// vertical: 64 rows of 4 columns) loads its 64 + 2r inputs of each line into shared memory once; each output sums its 2r + 1 taps
// in the same order as before (k = -r..r). r <= 32. HIGH_PASS (float): hp 1 = out is L - mean.
static const char* kChromaGlslBox = R"(
layout(local_size_x=64,local_size_y=4) in;
layout(std430,binding=4) readonly buffer In{T a[];};
layout(std430,binding=5) writeonly buffer Out{T o[];};
layout(std430,binding=6) readonly buffer Lf{float lum[];};
uniform int r;
uniform int dir;
uniform int hp;
shared T tile[4][128];
void main(){
    int lx=int(gl_LocalInvocationID.x),ly=int(gl_LocalInvocationID.y);
    // along = the filter direction (x horizontal, y vertical), across = the line; rowBase offsets the dispatched rows
    bool hor=dir==0;
    int along0=hor?int(gl_WorkGroupID.x)*64:rowBase+int(gl_WorkGroupID.x)*64;
    int across=hor?rowBase+int(gl_WorkGroupID.y)*4+ly:int(gl_WorkGroupID.y)*4+ly;
    int n=hor?size.x:size.y,lines=hor?size.y:size.x;
    int line=min(across,lines-1);
    for(int k=lx;k<64+2*r;k+=64){
        int p=clamp(along0-r+k,0,n-1);
        tile[ly][k]=hor?a[line*size.x+p]:a[p*size.x+line];
    }
    barrier();
    int along=along0+lx;
    if(across>=lines||along>=n)return;
    T s=T(0.0);
    for(int k=0;k<=2*r;k++)s+=tile[ly][lx+k];
    T m=s/float(2*r+1);
    int i=hor?across*size.x+along:along*size.x+across;
#ifdef HIGH_PASS
    if(hp!=0){o[i]=lum[i]-m;return;}
#endif
    o[i]=m;
}
)";
// soft sign-change count of the luma high-pass (mosaicSoftSignChange, kMosaicLumaTau)
static const char* kChromaGlslSign = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=4) readonly buffer Hp{float hp[];};
layout(std430,binding=5) writeonly buffer Sc{float sc[];};
layout(std430,binding=6) readonly buffer Lf{float lum[];};
float soft(float p0,float p1,float gate){
    if(!(gate>0.0))return 0.0;
    float opp=max(0.0,min(p0,-p1))+max(0.0,min(-p0,p1));
    if(opp<=0.0)return 0.0;
    return ssf(opp/(0.25*gate))*ssf(((abs(p0)+abs(p1))/gate-0.75)/0.5);
}
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int i=y*size.x+x;
    float a=hp[i],gate=0.04*max(lum[i],1.0e-4),c=0.0;
    if(x+1<size.x)c+=0.5*soft(a,hp[i+1],gate);
    if(y+1<size.y)c+=0.5*soft(a,hp[i+size.x],gate);
    sc[i]=c;
}
)";
// the false-colour blend and the R / B reconstruction
static const char* kChromaGlslBlend = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) buffer Rgb{float rgb[];};
layout(std430,binding=1) readonly buffer Uv{vec2 uv[];};
layout(std430,binding=3) readonly buffer Wide{vec2 wide[];};
layout(std430,binding=4) readonly buffer Z{float z[];};
layout(std430,binding=7) readonly buffer Osc{float osc[];};
uniform float eps;
uniform vec2 zr;        // z0, z1
uniform ivec4 oscU;     // x: 1 = raw oscillation map, y = block, z / w = blocks per row / column
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int i=y*size.x+x;
    float t=clamp((z[i]-zr.x)/(zr.y-zr.x),0.0,1.0),k=t*t*(3.0-2.0*t);
    if(oscU.x!=0){
        float o=osc[min(y/oscU.y,oscU.w-1)*oscU.z+min(x/oscU.y,oscU.z-1)];
        float t2=clamp((o-0.2375)/0.2375,0.0,1.0);k=max(k,t2*t2*(3.0-2.0*t2));
    }
    vec2 c=uv[i];c+=k*(wide[i]-c);
    float g=max(rgb[3*i+1],0.0)+eps;
    rgb[3*i]=c.x*g-eps;rgb[3*i+2]=c.y*g-eps;
}
)";

// P531 (owner, 2026-11-07: «Делаем на ГПУ»): the native merge's post passes on the GPU, in this context (scam-hybrid.h's
// hybridAlongEdge and hybridLatticeNotch are their CPU twins: the same steps and sums in the same order, borders clamped to the image;
// the CPU's bands read the input as it was, hybridPureBands). Buffers: rgb (in / out alternately), eff (unshifted: pixel (x, y)
// reads (x - off.x, y - off.y)), T0 / T1 (vec2 each: the tensor products or the notch's four sums), cnt (changed pixels).
static const char* kPostGlslCommon = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Src{float src[];};
layout(std430,binding=2) readonly buffer Eff{float eff[];};
layout(std430,binding=5) buffer Cnt{uint cnt[];};
uniform ivec2 off;
uniform ivec4 stripU; // x: the image row of the strip's row 0, y: the first eff row uploaded, z / w: the strip's rows it owns [z, w)
shared uint groupCount;
float lumaAt(int x,int y){int i=3*(y*size.x+x);return 0.25*src[i]+0.5*src[i+1]+0.25*src[i+2];}
float effAt(int x,int y){return eff[(max(0,stripU.x+y-off.y)-stripU.y)*size.x+max(0,x-off.x)];}
bool owned(int y){return y>=stripU.z&&y<stripU.w;}
void countStart(){if(gl_LocalInvocationIndex==0u)groupCount=0u;barrier();}
void countEnd(bool changed,int slot){
    if(changed)atomicAdd(groupCount,1u);
    barrier();
    if(gl_LocalInvocationIndex==0u&&groupCount>0u)atomicAdd(cnt[slot],groupCount);
}
)";
// along-edge 1: the Sobel products (gx^2, gy^2, gx gy) of the luma, the rows' gaussian [1 4 6 4 1] / 16 -> T0 (j11, j22), T1.x (j12)
static const char* kPostGlslAlongRows = R"(
layout(std430,binding=3) writeonly buffer T0{vec2 t0[];};
layout(std430,binding=4) writeonly buffer T1{vec2 t1[];};
vec3 sobel(int x,int y){
    int xl=max(x-1,0),xr=min(x+1,size.x-1),yu=max(y-1,0),yd=min(y+1,size.y-1);
    float gx=(lumaAt(xr,yu)+2.0*lumaAt(xr,y)+lumaAt(xr,yd))-(lumaAt(xl,yu)+2.0*lumaAt(xl,y)+lumaAt(xl,yd));
    float gy=(lumaAt(xl,yd)+2.0*lumaAt(x,yd)+lumaAt(xr,yd))-(lumaAt(xl,yu)+2.0*lumaAt(x,yu)+lumaAt(xr,yu));
    return vec3(gx*gx,gy*gy,gx*gy);
}
uniform int alongS; // the taps' step (1 Quad, 2 Tetra)
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int k=alongS;
    vec3 a=sobel(max(x-2*k,0),y),b=sobel(max(x-k,0),y),c=sobel(x,y),d=sobel(min(x+k,size.x-1),y),e=sobel(min(x+2*k,size.x-1),y);
    vec3 o=(a+e+4.0*(b+d)+6.0*c)*(1.0/16.0);
    int i=y*size.x+x;
    t0[i]=o.xy;t1[i]=vec2(o.z,0.0);
}
)";
// along-edge 2: the columns' gaussian, the coherence and the tangent; 1 + 2 x 2 bilinear taps along the edge (weights 1, 0.6)
static const char* kPostGlslAlongApply = R"(
layout(std430,binding=1) writeonly buffer Dst{float dst[];};
layout(std430,binding=3) readonly buffer T0{vec2 t0[];};
layout(std430,binding=4) readonly buffer T1{vec2 t1[];};
uniform vec2 alongU; // x: K (the share 1 - smoothstep(1, 1 + K, eff)), y: the strength
uniform int alongS;  // the taps' step (1 Quad, 2 Tetra)
vec3 tensorAt(int x,int y){int i=clamp(y,0,size.y-1)*size.x+x;return vec3(t0[i],t1[i].x);}
vec3 rgbAt(int x,int y){int i=3*(y*size.x+x);return vec3(src[i],src[i+1],src[i+2]);}
uniform int imageH;
// fx, fy in image px (the CPU's rounding), read in the strip
vec3 bilinear(float fx,float fy){
    fx=clamp(fx,0.0,float(size.x-1));fy=clamp(fy,0.0,float(imageH-1));
    int x0=min(int(fx),size.x-2),y0=min(int(fy),imageH-2);float ax=fx-float(x0),ay=fy-float(y0);
    int r=y0-stripU.x;
    return (rgbAt(x0,r)*(1.0-ax)+rgbAt(x0+1,r)*ax)*(1.0-ay)+(rgbAt(x0,r+1)*(1.0-ax)+rgbAt(x0+1,r+1)*ax)*ay;
}
void main(){
    countStart();
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    bool inside=x<size.x&&y<size.y,changed=false;
    if(inside){
        vec3 c0=rgbAt(x,y),o=c0;
        float u=clamp((effAt(x,y)-1.0)/alongU.x,0.0,1.0),sh=alongU.y*(1.0-u*u*(3.0-2.0*u));
        if(sh>1.0e-3){
            int k=alongS;
            vec3 m=(tensorAt(x,y-2*k)+tensorAt(x,y+2*k)+4.0*(tensorAt(x,y-k)+tensorAt(x,y+k))+6.0*tensorAt(x,y))*(1.0/16.0);
            float tr=m.x+m.y;
            if(tr>1.0e-12){
                float dxx=m.x-m.y,dxy=2.0*m.z,mag2=dxx*dxx+dxy*dxy;
                float w=sh*clamp((mag2/(tr*tr)-0.3)/0.7,0.0,1.0);
                if(w>1.0e-3){
                    float inv=1.0/sqrt(mag2),c2=dxx*inv,s2=dxy*inv;
                    float ct=sqrt(max(0.0,0.5*(1.0+c2))),st=sqrt(max(0.0,0.5*(1.0-c2)))*(s2<0.0?-1.0:1.0);
                    vec2 t=vec2(-st,ct);
                    vec3 acc=c0;float ws=1.0;
                    for(int q=1;q<=2;q++)for(int sg=-1;sg<=1;sg+=2){
                        float wq=q==1?1.0:0.6;
                        acc+=wq*bilinear(float(x)+float(sg*q*k)*t.x,float(stripU.x+y)+float(sg*q*k)*t.y);
                        ws+=wq;
                    }
                    o=c0+w*(acc/ws-c0);
                    changed=owned(y);
                }
            }
        }
        int i=3*(y*size.x+x);
        dst[i]=o.x;dst[i+1]=o.y;dst[i+2]=o.z;
    }
    countEnd(changed,0);
}
)";
// notch 1: the rows' gaussian of the luma times exp(-i 2 pi f (x +- y)) (re, im of both diagonals) -> T0, T1
static const char* kPostGlslNotchRows = R"(
layout(std430,binding=3) writeonly buffer T0{vec2 t0[];};
layout(std430,binding=4) writeonly buffer T1{vec2 t1[];};
uniform float cosT[10];
uniform float sinT[10];
uniform float gN[49];
uniform ivec2 notchPR; // P543: the lattice's period (10 Quad, 8 Tetra) and the gaussian's radius (6, 24)
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int gy=stripU.x+y,P=notchPR.x,R=notchPR.y;
    float c2=cosT[gy%P],s2=sinT[gy%P];
    vec4 a=vec4(0.0);
    for(int q=-R;q<=R;q++){
        int xx=clamp(x+q,0,size.x-1);
        float L=lumaAt(xx,y),c1=cosT[xx%P],s1=sinT[xx%P];
        float cp=c1*c2-s1*s2,sp=s1*c2+c1*s2,cm=c1*c2+s1*s2,sm=s1*c2-c1*s2;
        a+=gN[q+R]*vec4(L*cp,-L*sp,L*cm,-L*sm);
    }
    int i=y*size.x+x;
    t0[i]=a.xy;t1[i]=a.zw;
}
)";
// notch 2: the columns' gaussian, 2 Re(A exp(+i phase)) of both diagonals off R, G and B alike by the coverage's share
static const char* kPostGlslNotchApply = R"(
layout(std430,binding=1) writeonly buffer Dst{float dst[];};
layout(std430,binding=3) readonly buffer T0{vec2 t0[];};
layout(std430,binding=4) readonly buffer T1{vec2 t1[];};
uniform float cosT[10];
uniform float sinT[10];
uniform float gN[49];
uniform ivec2 notchPR; // P543: period, radius
uniform vec2 notchG;   // P543: the share's lower gate in frames (Quad 1.2 .. 2.5; Tetra -1 .. -0.5: from a single frame)
uniform vec3 notchU; // lo, hi frames, the strength
float sstep(float a,float b,float v){float u=clamp((v-a)/(b-a),0.0,1.0);return u*u*(3.0-2.0*u);}
void main(){
    countStart();
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    bool inside=x<size.x&&y<size.y,changed=false;
    if(inside){
        int i=3*(y*size.x+x);
        vec3 o=vec3(src[i],src[i+1],src[i+2]);
        float F=effAt(x,y),sh=notchU.z*(1.0-sstep(notchU.x,notchU.y,F))*sstep(notchG.x,notchG.y,F);
        if(sh>1.0e-3){
            int P=notchPR.x,R=notchPR.y;
            vec4 a=vec4(0.0);
            for(int q=-R;q<=R;q++){int k=clamp(y+q,0,size.y-1)*size.x+x;a+=gN[q+R]*vec4(t0[k],t1[k]);}
            int gy=stripU.x+y;
            float c1=cosT[x%P],s1=sinT[x%P],c2=cosT[gy%P],s2=sinT[gy%P];
            float cp=c1*c2-s1*s2,sp=s1*c2+c1*s2,cm=c1*c2+s1*s2,sm=s1*c2-c1*s2;
            float comp=2.0*(a.x*cp-a.y*sp)+2.0*(a.z*cm-a.w*sm);
            o-=vec3(sh*comp);
            changed=owned(y);
        }
        dst[i]=o.x;dst[i+1]=o.y;dst[i+2]=o.z;
    }
    countEnd(changed,1);
}
)";
// P571 (owner, 2026-11-09: «Сетку сделай такой же как на качестве и даже лучше»): the fast route's Quad lattice - its 2 / 4 px
// periodic part of the luma where the merge has few frames (the base's own Quad interpolation; nubia burst, dark wall: peaks 4.9 /
// 5.9 x the background at 0.5 cycles / px against the quality route's 2.4 / 3.2). Per pixel: r = Y - box4(Y) (a 4 x 4 box passes no
// 4-periodic part, so r holds it), clipped at 2.5 sigma of its own (sigma = 1.2533 x the 32 x 32 mean of |r|: edges do not leak into
// the estimate), averaged per phase (x mod 4, y mod 4) over 8 x 8 samples of its phase (a 32 x 32 window; P591: the median of 8 along x,
// then of 8 along y); that pattern less its own 4 x 4 mean is taken off the luma as a gain on R, G and B (P591). Window ends as scipy's uniform_filter with mode 'nearest' (each phase plane clamped
// to its own samples). One pass per MODE; the strip's rows: image rows strip.x + row, the image strip.y rows high.
// P632 (owner, 2026-11-11, burst 142159 on «Быстрый»: a grid on the branches against the sky): relU 1 takes the lattice in
// relative units - r = Y / box4(Y) - 1 (0 where the box is below 1e-4), the same clip and medians, off as the gain 1 - l. In
// absolute units (relU 0, P591) a 32 x 32 window of bright sky set the pattern and the gain (Y - l) / Y put it 5 - 10x larger on
// the dark branches and the dark side of an edge (with P571's mean, 2.5x quality's lattice on the branches; with P591's
// median, dotted rows under an edge). A Quad lattice is a site response / interpolation residue: relative to its pixel's level.
static const char* kQuadLatticeGlsl = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Src{float src[];};
layout(std430,binding=1) writeonly buffer Dst{float dst[];};
layout(std430,binding=2) buffer Aux{float aux[];};
uniform ivec2 strip;  // x: the image row of the strip's row 0, y: the image's height
uniform float clipK;
uniform int relU;     // P632: 1 the lattice relative to the 4 x 4 box of the luma, 0 in luma units (P591)
int rowOf(int gy){gy=clamp(gy,0,strip.y-1);return clamp(gy-strip.x,0,size.y-1);}
int phaseX(int x,int d){int p=x&3,n=(size.x-p+3)>>2;return p+4*clamp((x>>2)+d,0,n-1);}
int phaseY(int gy,int d){int p=gy&3,n=(strip.y-p+3)>>2;return rowOf(p+4*clamp((gy>>2)+d,0,n-1));}
// P591: the median of 8 phase samples (the mean before let a moving edge in one or two of them leak into the pattern: rows of
// 1 px lines 4 rows apart across legs, a curb, a car's sill on the burst 20261026_142159); the mean of the middle two
float med8(float v[8]){for(int a=0;a<7;a++)for(int b=0;b<7-a;b++){float lo=min(v[b],v[b+1]),hi=max(v[b],v[b+1]);v[b]=lo;v[b+1]=hi;}return 0.5*(v[3]+v[4]);}
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int gy=strip.x+y,i=y*size.x+x;
#if MODE==1
    dst[i]=0.25*src[3*i]+0.5*src[3*i+1]+0.25*src[3*i+2];
#elif MODE==2
    float m=0.0;
    for(int dy=-2;dy<=1;dy++){int yy=rowOf(gy+dy);for(int dx=-2;dx<=1;dx++)m+=src[yy*size.x+clamp(x+dx,0,size.x-1)];}
    float b=m*0.0625,r=relU!=0?(b>1.0e-4?src[i]/b-1.0:0.0):src[i]-b;
    dst[i]=r;aux[i]=abs(r);
#elif MODE==3
    float m=0.0;
    for(int dx=-16;dx<=15;dx++)m+=src[y*size.x+clamp(x+dx,0,size.x-1)];
    dst[i]=m;
#elif MODE==4
    float m=0.0;
    for(int dy=-16;dy<=15;dy++)m+=src[rowOf(gy+dy)*size.x+x];
    float sg=clipK*1.2533*m*(1.0/1024.0);
    dst[i]=clamp(aux[i],-sg,sg);
#elif MODE==5
    float v[8];
    for(int d=-4;d<=3;d++)v[d+4]=src[y*size.x+phaseX(x,d)];
    dst[i]=med8(v);
#elif MODE==6
    float v[8];
    for(int d=-4;d<=3;d++)v[d+4]=src[phaseY(gy,d)*size.x+x];
    dst[i]=med8(v);
#elif MODE==7
    float m=0.0;
    for(int dy=-2;dy<=1;dy++){int yy=rowOf(gy+dy);for(int dx=-2;dx<=1;dx++)m+=src[yy*size.x+clamp(x+dx,0,size.x-1)];}
    float l=src[i]-m*0.0625;
    vec3 c=vec3(aux[3*i],aux[3*i+1],aux[3*i+2]);
    float Y=0.25*c.x+0.5*c.y+0.25*c.z;
    // P591: the luma's lattice off as a gain (the colour kept: an equal subtraction from R, G and B before the white balance
    // tinted the corrected rows magenta / green), a dark pixel by subtraction
    if(relU!=0)c*=clamp(1.0-l,0.25,1.75); // P632: the relative lattice off as a gain
    else if(Y>1.0e-4)c*=clamp((Y-l)/Y,0.25,1.75);else c-=vec3(l);
    dst[3*i]=c.x;dst[3*i+1]=c.y;dst[3*i+2]=c.z;
#endif
}
)";
// P603 round 2: the Tetra motion smoothing (scam-hybrid.h hybridTetraMotionSmooth is its CPU twin: the same box and taps in the same
// order): the coverage's 8 x 8 box sets the share; a 5 x 5 gaussian (sigma 1.5) gated by the luma's difference against k x the
// noise at the pixel. Rows and the coverage's rows clamped to the image and to the strip (a halo row's result is not kept).
static const char* kPostGlslSmooth = R"(
layout(std430,binding=1) writeonly buffer Dst{float dst[];};
uniform vec4 smoothU; // lo, hi of the coverage box, the strength, -0.5 / k^2
uniform vec2 noiseU;  // the noise model: variance S Y + O
uniform int imageH;
uniform int effRowsU; // the coverage rows uploaded for the strip
vec3 rgbAt(int x,int y){int i=3*(y*size.x+x);return vec3(src[i],src[i+1],src[i+2]);}
float effImg(int x,int Y){return eff[clamp(max(0,Y-off.y)-stripU.y,0,effRowsU-1)*size.x+max(0,x-off.x)];}
void main(){
    countStart();
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    bool inside=x<size.x&&y<size.y,changed=false;
    if(inside){
        vec3 c=rgbAt(x,y),o=c;
        int Y=stripU.x+y;
        float e=0.0;
        for(int j=-4;j<4;j++){
            int yy=clamp(Y+j,0,imageH-1);
            float a=0.0;
            for(int i=-4;i<4;i++)a+=effImg(clamp(x+i,0,size.x-1),yy);
            e+=a;
        }
        e*=1.0/64.0;
        float u=clamp((e-smoothU.x)/(smoothU.y-smoothU.x),0.0,1.0),sh=smoothU.z*(1.0-u*u*(3.0-2.0*u));
        if(sh>1.0e-3){
            float Yc=0.25*c.x+0.5*c.y+0.25*c.z;
            float inv=smoothU.w/max(noiseU.x*max(Yc,0.0)+noiseU.y,1.0e-12);
            vec3 a=vec3(0.0);float ws=0.0;
            for(int j=-2;j<=2;j++){
                int r=clamp(clamp(Y+j,0,imageH-1)-stripU.x,0,size.y-1);
                for(int i=-2;i<=2;i++){
                    vec3 p=rgbAt(clamp(x+i,0,size.x-1),r);
                    float d=0.25*p.x+0.5*p.y+0.25*p.z-Yc;
                    float w=exp(-float(i*i+j*j)*(1.0/4.5)+d*d*inv);
                    a+=w*p;ws+=w;
                }
            }
            o=c+sh*(a*(1.0/ws)-c);
            changed=owned(y);
        }
        int i=3*(y*size.x+x);
        dst[i]=o.x;dst[i+1]=o.y;dst[i+2]=o.z;
    }
    countEnd(changed,2);
}
)";
class ChromaMedianGpu {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint pRatio = 0, pMedian = 0, pBoxF = 0, pBoxV2 = 0, pSign = 0, pBlend = 0, pSmooth = 0;
    GLuint pAlongRows = 0, pAlongApply = 0, pNotchRows = 0, pNotchApply = 0; // P531 (mosaicPost; built by ensurePost)
    GLuint pSmoothT = 0; // P603 round 2: the Tetra motion smoothing (mosaicPost's third pass)
    GLuint pQl[8]{}; // P571: quadLattice's passes 1..7 (built by ensureQuadLattice)
    enum { kRgb, kUv, kT, kX, kL, kA, kS, kOsc, kPostA, kPostB, kPostEff, kPostT0, kPostT1, kPostCnt, kQA, kQB, kQ1, kQ2, kQ3, kQT, kSlots };
    GLuint buf[kSlots]{};
    size_t bufBytes[kSlots]{}; // P610: the bytes of each slot's storage (alloc)
    int w = 0, h = 0;
    int stripRow0 = 0; // P571: the image row of the current quadLattice strip's row 0
    // P662 (speed): kRgb holds the whole frame's chroma median result after process() (every row of rgb was read back from it):
    // the frame (data, size) it stands for, null once anything else used kRgb. The Quad lattice that follows takes its strips from
    // there (GPU to GPU) instead of uploading the frame's floats again - the same bytes in its buffer.
    const float* medianOf = nullptr;
    int medianW = 0, medianH = 0;
    void cleanup() noexcept {
        if (display == EGL_NO_DISPLAY) return;
        if (context != EGL_NO_CONTEXT && eglMakeCurrent(display, surface, surface, context)) {
            for (GLuint p : {pRatio, pMedian, pBoxF, pBoxV2, pSign, pBlend, pSmooth, pAlongRows, pAlongApply, pNotchRows, pNotchApply, pSmoothT}) if (p) glDeleteProgram(p);
            for (GLuint p : pQl) if (p) glDeleteProgram(p);
            glDeleteBuffers(kSlots, buf);
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
    }
    static void check(const char* where) {
        const GLenum e = glGetError();
        if (e != GL_NO_ERROR) throw std::runtime_error(std::string("CHROMA GPU ") + where + " GL error=" + std::to_string(e));
    }
    static GLuint compile(const char* defs, const char* body, const char* common2 = "") {
        const char* sources[] = {"#version 310 es\n", defs, kChromaGlslCommon, common2, body};
        scamGlslDump("chromaGpu", std::string(sources[0]) + sources[1] + sources[2] + sources[3] + sources[4]); // P662
        GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, 5, sources, nullptr);
        glCompileShader(shader);
        GLint ok = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (!ok) { char msg[2048]{}; glGetShaderInfoLog(shader, sizeof(msg), nullptr, msg); glDeleteShader(shader); throw std::runtime_error(std::string("CHROMA GPU shader: ") + msg); }
        GLuint program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        glGetProgramiv(program, GL_LINK_STATUS, &ok);
        if (!ok) { char msg[2048]{}; glGetProgramInfoLog(program, sizeof(msg), nullptr, msg); glDeleteProgram(program); throw std::runtime_error(std::string("CHROMA GPU link: ") + msg); }
        return program;
    }
    void alloc(int slot, size_t bytes) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[slot]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, GLsizeiptr(std::max<size_t>(bytes, 16)), nullptr, GL_DYNAMIC_DRAW);
        bufBytes[slot] = std::max<size_t>(bytes, 16);
    }
    // P610 (speed, 50 MP): the slot's storage kept when it holds bytes already (bound as alloc binds it): the chroma median's bands
    // of one frame (10 at 50 MP, ~275 MB each) took new storage every band. Every pass writes every value it reads later and reads
    // only inside size (clamped taps), so what an earlier call left in the storage is never read.
    void ensure(int slot, size_t bytes) {
        if (bufBytes[slot] >= std::max<size_t>(bytes, 16)) glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[slot]);
        else alloc(slot, bytes);
    }
    void bind(int binding, int slot) { glBindBufferBase(GL_SHADER_STORAGE_BUFFER, GLuint(binding), buf[slot]); }
    // rows in dispatches of 256 (a single long dispatch can trip the GPU hang detection), flushed together
    void run(GLuint p) {
        glUniform2i(glGetUniformLocation(p, "size"), w, h);
        const GLint l = glGetUniformLocation(p, "rowBase");
        for (int r = 0; r < h; r += 256) {
            glUniform1i(l, r);
            glDispatchCompute(GLuint((w + 7) / 8), GLuint((std::min(256, h - r) + 7) / 8), 1);
        }
        glFlush();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    void box(GLuint p, int in, int out, int r, int dir, int hpMode, int lum) {
        glUseProgram(p);
        bind(4, in); bind(5, out); bind(6, lum);
        glUniform1i(glGetUniformLocation(p, "r"), r);
        glUniform1i(glGetUniformLocation(p, "dir"), dir);
        glUniform1i(glGetUniformLocation(p, "hp"), hpMode);
        glUniform2i(glGetUniformLocation(p, "size"), w, h);
        const GLint l = glGetUniformLocation(p, "rowBase");
        if (dir == 0) { // groups: 64 columns x 4 rows; rows in dispatches of 256
            for (int y = 0; y < h; y += 256) {
                glUniform1i(l, y);
                glDispatchCompute(GLuint((w + 63) / 64), GLuint((std::min(256, h - y) + 3) / 4), 1);
            }
        } else {        // groups: 64 rows x 4 columns; rows in dispatches of 256
            for (int y = 0; y < h; y += 256) {
                glUniform1i(l, y);
                glDispatchCompute(GLuint((std::min(256, h - y) + 63) / 64), GLuint((w + 3) / 4), 1);
            }
        }
        glFlush();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    using Clock = std::chrono::steady_clock;
    static double ms(Clock::time_point a) { return std::chrono::duration<double, std::milli>(Clock::now() - a).count(); }
public:
    std::string renderer, times;
    ChromaMedianGpu() {
        const auto t0 = Clock::now();
        try {
            display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr) || !eglBindAPI(EGL_OPENGL_ES_API)) throw std::runtime_error("EGL unavailable");
            const EGLint configAttrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
            EGLConfig config{};
            EGLint count = 0;
            if (!eglChooseConfig(display, configAttrs, &config, 1, &count) || count != 1) throw std::runtime_error("No EGL compute configuration");
            const EGLint attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 1, EGL_NONE};
            context = eglCreateContext(display, config, EGL_NO_CONTEXT, attrs);
            if (context == EGL_NO_CONTEXT) throw std::runtime_error("Cannot create GLES 3.1 context");
            const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            surface = eglCreatePbufferSurface(display, config, size);
            if (surface == EGL_NO_SURFACE || !eglMakeCurrent(display, surface, surface, context)) throw std::runtime_error("Cannot activate GLES context");
            const auto* name = glGetString(GL_RENDERER);
            renderer = name ? reinterpret_cast<const char*>(name) : "unknown";
            pRatio = compile("", kChromaGlslRatio);
            pMedian = compile("", kChromaGlslMedian);
            pBoxF = compile("#define T float\n#define HIGH_PASS 1\n", kChromaGlslBox);
            pBoxV2 = compile("#define T vec2\n", kChromaGlslBox);
            pSign = compile("", kChromaGlslSign);
            pBlend = compile("", kChromaGlslBlend);
            pSmooth = compile("", kChromaGlslSmooth);
            glGenBuffers(kSlots, buf);
            check("init");
            times = "init " + std::to_string(int(ms(t0)));
        } catch (...) { cleanup(); throw; }
    }
    // P80: the context may be built on one thread (chromaPrewarm, during the merge) and used on another: release() on the first,
    // acquire() on the second (a context is current on one thread at a time); the destructor makes it current where it runs.
    void release() { eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); }
    void acquire() {
        if (!eglMakeCurrent(display, surface, surface, context))
            throw std::runtime_error("CHROMA GPU: context not current (EGL error " + std::to_string(eglGetError()) + ")");
    }
    ChromaMedianGpu(const ChromaMedianGpu&) = delete;
    ChromaMedianGpu& operator=(const ChromaMedianGpu&) = delete;
    ~ChromaMedianGpu() { cleanup(); }
    // P571: the fast route's Quad lattice off the luma (kQuadLatticeGlsl) on rgb (W x H x 3, in place), in strips of up to 1024 rows
    // with 40 rows of halo each side (the passes reach 2 + 16 + 16 + 2 rows): every owned row reads the image as it was (the
    // originals of the rows above a strip are kept before its rows are written back). Returns the passes' times.
    static constexpr int kQlHalo = 40;
    int qlStrip = 1024; // (the checks run more strips)
    // P632: relative = the lattice relative to the luma's 4 x 4 box (kQuadLatticeGlsl relU 1); false = in luma units (P591)
    // P662: fromMedian - rgb is the chroma median's result as process() left it (nothing changed it since): the strips are copied
    // from the median's GPU buffer when it still holds that frame (medianOf), else uploaded as before
    std::string quadLattice(std::vector<float>& rgb, int W, int H, float clipK = 2.5f, bool relative = true, bool fromMedian = false) {
        if (W < 16 || H < 16 || rgb.size() < size_t(W) * H * 3) throw std::runtime_error("QUAD LATTICE GPU: sizes");
        const bool onGpu = fromMedian && medianOf == rgb.data() && medianW == W && medianH == H && bufBytes[kRgb] >= size_t(W) * H * 12;
        struct DropMedian { ChromaMedianGpu& g; ~DropMedian() { if (g.medianOf) { g.medianOf = nullptr; g.alloc(kRgb, 16); } } } dropMedian{*this};
        auto t0 = Clock::now();
        ensureQuadLattice();
        GLint64 maxBlock = 0;
        glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &maxBlock);
        int strip = std::min(H, std::max(qlStrip, 64));
        if (maxBlock > 0) strip = std::min<int64_t>(strip, maxBlock / (int64_t(W) * 12) - 2 * kQlHalo);
        if (strip < 64 && strip < H) throw std::runtime_error("QUAD LATTICE GPU: a strip larger than a storage block");
        latticeBuffers(W, std::min(H, strip + 2 * kQlHalo), false);
        std::vector<float> saved(size_t(kQlHalo) * W * 3);
        const double prep = ms(t0);
        double upMs = 0, passMs = 0, readMs = 0;
        int strips = 0;
        for (int y0 = 0; y0 < H; y0 += strip, ++strips) {
            const int y1 = std::min(H, y0 + strip), ga = std::max(0, y0 - kQlHalo), gb = std::min(H, y1 + kQlHalo), rows = gb - ga, top = y0 - ga;
            auto u = Clock::now();
            // P610: the strip's rows uploaded from where they are (the kept originals above it, then the frame's rows): the same
            // bytes in the buffer as through a copy of the strip
            if (onGpu) { // P662: rows ga..gb of the median's result, GPU to GPU (the bytes the upload below would give)
                glBindBuffer(GL_COPY_READ_BUFFER, buf[kRgb]);
                glBindBuffer(GL_COPY_WRITE_BUFFER, buf[kQA]);
                glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, GLintptr(size_t(ga) * W * 12), 0, GLsizeiptr(size_t(rows) * W * 12));
            } else {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[kQA]);
                if (top > 0) glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(size_t(top) * W * 12), saved.data() + size_t(kQlHalo - top) * W * 3);
                glBufferSubData(GL_SHADER_STORAGE_BUFFER, GLintptr(size_t(top) * W * 12), GLsizeiptr(size_t(gb - y0) * W * 12), rgb.data() + size_t(y0) * W * 3);
            }
            check("quad lattice upload");
            upMs += ms(u); u = Clock::now();
            const int keep = std::min(kQlHalo, y1);
            if (!onGpu) std::memcpy(saved.data() + size_t(kQlHalo - keep) * W * 3, rgb.data() + size_t(y1 - keep) * W * 3, size_t(keep) * W * 12);
            latticeStrip(W, H, ga, rows, y0, y1, clipK, relative, rgb.data() + size_t(y0) * W * 3, passMs, readMs);
        }
        latticeRelease();
        return "programs " + std::to_string(int(prep)) + " upload " + std::to_string(int(upMs)) + (onGpu ? " (from the median's GPU buffer, P662)" : "")
            + " passes " + std::to_string(int(passMs)) + " readback " + std::to_string(int(readMs)) + " (" + std::to_string(strips) + " strips) ms";
    }
    // P662: one strip of the Quad lattice whose image rows [ga, ga + rows) of an H-row image sit in kQA from its row 0: the 7
    // passes, then its rows [y0, y1) read back into dst (image row y0 at dst). quadLattice's strip; the fused median bands' too.
    void latticeStrip(int W, int H, int ga, int rows, int y0, int y1, float clipK, bool relative, float* dst, double& passMs, double& readMs) {
        auto u = Clock::now();
        auto pass = [&](int m, int s, int d, int a) {
            glUseProgram(pQl[m]);
            bind(0, s); bind(1, d); bind(2, a);
            glUniform2i(glGetUniformLocation(pQl[m], "strip"), stripRow0, H);
            glUniform1f(glGetUniformLocation(pQl[m], "clipK"), clipK);
            glUniform1i(glGetUniformLocation(pQl[m], "relU"), relative ? 1 : 0);
            run(pQl[m]);
        };
        w = W; h = rows; stripRow0 = ga;
        pass(1, kQA, kQ1, kQ3);   // Y -> Q1
        pass(2, kQ1, kQ2, kQ3);   // r -> Q2, |r| -> Q3
        pass(3, kQ3, kQ1, kQ3);   // the rows' sums of |r| -> Q1
        pass(4, kQ1, kQ3, kQ2);   // r clipped -> Q3 (aux = r)
        pass(5, kQ3, kQ1, kQ2);   // the phase rows' means -> Q1
        pass(6, kQ1, kQ2, kQ3);   // the phase means L -> Q2
        pass(7, kQ2, kQB, kQA);   // rgb - (L - box4 L) -> QB
        check("quad lattice passes");
        glFinish(); passMs += ms(u); u = Clock::now();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[kQB]);
        const size_t off = size_t(y0 - ga) * W * 12, bytes = size_t(y1 - y0) * W * 12;
        constexpr size_t kChunk = size_t(32) << 20;
        for (size_t o = 0; o < bytes; o += kChunk) {
            const size_t n = std::min(kChunk, bytes - o);
            const void* m = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, GLintptr(off + o), GLsizeiptr(n), GL_MAP_READ_BIT);
            if (!m) throw std::runtime_error("QUAD LATTICE GPU readback failed");
            std::memcpy(reinterpret_cast<char*>(dst) + o, m, n);
            if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) throw std::runtime_error("QUAD LATTICE GPU storage invalidated");
        }
        readMs += ms(u);
    }
    // P662 (mosaicChromaLatticeBands): the lattice strip's rows [ga, gb) into kQA from the median band of image rows from a in kRgb
    // (rows y0..gb, final there) and kQT (rows ga..y0: the band above's last ones); then this band's last kQlHalo rows into kQT
    void latticeFromBand(int W, int H, int a, int y0, int y1, int ga, int gb) {
        const size_t row = size_t(W) * 12;
        copySlot(kQT, 0, kQA, 0, size_t(y0 - ga) * row);
        copySlot(kRgb, size_t(y0 - a) * row, kQA, size_t(y0 - ga) * row, size_t(gb - y0) * row);
        if (y1 < H) copySlot(kRgb, size_t(y1 - kQlHalo - a) * row, kQT, 0, size_t(kQlHalo) * row);
        check("fused lattice copies");
    }
    // P662: bytes of one slot's storage into another's, GPU to GPU
    void copySlot(int from, size_t fromOff, int to, size_t toOff, size_t bytes) {
        if (!bytes) return;
        glBindBuffer(GL_COPY_READ_BUFFER, buf[from]);
        glBindBuffer(GL_COPY_WRITE_BUFFER, buf[to]);
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, GLintptr(fromOff), GLintptr(toOff), GLsizeiptr(bytes));
    }
    // P662: the lattice's storage for strips of up to `rows` rows W wide (kQT: the kQlHalo median rows above a fused band's strip)
    void latticeBuffers(int W, int rows, bool fused) {
        const size_t cap = size_t(W) * size_t(rows);
        alloc(kQA, cap * 12); alloc(kQB, cap * 12); alloc(kQ1, cap * 4); alloc(kQ2, cap * 4); alloc(kQ3, cap * 4);
        if (fused) alloc(kQT, size_t(W) * kQlHalo * 12);
        check("quad lattice buffers");
    }
    void latticeRelease() { for (int sl : {kQA, kQB, kQ1, kQ2, kQ3, kQT}) if (bufBytes[sl] > 16) alloc(sl, 16); }
    // P610: quadLattice's programs (on the prewarm's thread when chromaPrewarm(.., true) built the context, else on first use)
    void ensureQuadLattice() {
        if (pQl[7]) return;
        for (int m = 1; m <= 7; ++m) pQl[m] = compile(("#define MODE " + std::to_string(m) + "\n").c_str(), kQuadLatticeGlsl);
        check("quad lattice programs");
    }
    // P531: the post passes' programs (on the prewarm's thread when chromaPrewarm(true) built the context, else on first use)
    void ensurePost() {
        if (pNotchApply && pSmoothT) return;
        if (!pNotchApply) {
            pAlongRows = compile("", kPostGlslAlongRows, kPostGlslCommon);
            pAlongApply = compile("", kPostGlslAlongApply, kPostGlslCommon);
            pNotchRows = compile("", kPostGlslNotchRows, kPostGlslCommon);
            pNotchApply = compile("", kPostGlslNotchApply, kPostGlslCommon);
        }
        if (!pSmoothT) pSmoothT = compile("", kPostGlslSmooth, kPostGlslCommon); // P603
        check("post programs");
    }
    // P531: rows of a strip of mosaicPost (its own rows; kPostHalo more each side: the notch after the along-edge pass reads up to 24 + 3 rows)
    static constexpr int kPostStrip = 1024, kPostHalo = 32; // P543: the Tetra notch reads 24 + 3 rows
    int postStrip = kPostStrip; // (the checks run more strips)
    int postCommitted = 0;      // rows mosaicPost wrote back into rgb (a failure after some: the caller must not run the CPU twins)
    // GPU memory of mosaicPost (B): per pixel of a strip with its halo rgb twice 24, eff 4, T0 / T1 16
    static size_t postBytesFor(int w, int h) { return size_t(w) * size_t(std::min(h, kPostStrip) + 2 * kPostHalo) * 44; }
    // P531: hybridAlongEdge (alongK > 0: K, alongStrength, the taps' step alongS) and then hybridLatticeNotch (notch = lo, hi, strength; strength > 0) on rgb
    // (W x H x 3, in place), eff W x H unshifted by (dx, dy); changed[0], [1]: the pixels each changed. In strips of up to kPostStrip
    // rows (a storage block holds a strip: Mali's and Mesa's are 128 MB) with kPostHalo rows each side: the rows a strip owns read
    // only rows of its own (the image's borders are the strip's there). The post buffers are freed after (the context may stay for
    // the chroma median: chromaKeep).
    void mosaicPost(std::vector<float>& rgb, int width, int height, const std::vector<float>& eff, int dx, int dy, float alongK,
                    float alongStrength, int alongS, const float* notch, const float* cosT, const float* sinT, const float* gN, size_t* changed,
                    int notchPeriod = 10, int notchR = 6, float notchGateLo = 1.2f, float notchGateHi = 2.5f,
                    const float* smooth = nullptr, size_t* smoothChanged = nullptr) { // P603: smooth = lo, hi, strength, k, S, O
        if (notchPeriod < 2 || notchPeriod > 10 || notchR < 1 || notchR > 24) throw std::runtime_error("POST GPU: notch spec");
        const int W = width, H = height;
        auto t = Clock::now();
        if (W < 8 || H < 8 || eff.size() < size_t(W) * H || rgb.size() < size_t(W) * H * 3) throw std::runtime_error("POST GPU: sizes");
        GLint64 maxBlock = 0;
        glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &maxBlock);
        int strip = std::min(H, std::max(postStrip, 64));
        if (maxBlock > 0) strip = std::min<int64_t>(strip, maxBlock / (int64_t(W) * 12) - 2 * kPostHalo);
        if (strip < 64 && strip < H) throw std::runtime_error("POST GPU: a strip larger than a storage block");
        const bool along = alongK > 0.f && alongStrength > 0.f, notchOn = notch && notch[2] > 0.f;
        const bool smoothOn = smooth && smooth[2] > 0.f && smooth[1] > smooth[0];
        if (changed) changed[0] = changed[1] = 0;
        if (smoothChanged) *smoothChanged = 0;
        postCommitted = 0;
        if (!along && !notchOn && !smoothOn) return;
        ensurePost();
        times += " post programs " + std::to_string(int(ms(t))); t = Clock::now();
        const size_t cap = size_t(W) * size_t(std::min(H, strip + 2 * kPostHalo));
        alloc(kPostA, cap * 12); alloc(kPostB, cap * 12); alloc(kPostEff, (cap + size_t(W) * kPostHalo) * 4); alloc(kPostT0, cap * 8); alloc(kPostT1, cap * 8);
        alloc(kPostCnt, 16);
        const GLuint zero[4] = {0, 0, 0, 0};
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, 16, zero);
        check("post buffers");
        auto read = [&](int slot, size_t off, void* outp, size_t bytes) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[slot]);
            constexpr size_t kChunk = size_t(32) << 20;
            for (size_t o = 0; o < bytes; o += kChunk) {
                const size_t n = std::min(kChunk, bytes - o);
                const void* m = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, GLintptr(off + o), GLsizeiptr(n), GL_MAP_READ_BIT);
                if (!m) throw std::runtime_error("POST GPU readback failed");
                std::memcpy(static_cast<char*>(outp) + o, m, n);
                if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) throw std::runtime_error("POST GPU storage invalidated");
            }
        };
        // a strip's last kPostHalo rows go back after the next strip's upload (it reads them as they were)
        std::vector<float> pending;
        int pendingY = -1;
        double upMs = 0, passMs = 0, readMs = 0;
        for (int y0 = 0; y0 < H; y0 += strip) {
            const int y1 = std::min(H, y0 + strip), ga = std::max(0, y0 - kPostHalo), gb = std::min(H, y1 + kPostHalo), rows = gb - ga;
            const int e0 = std::max(0, ga - dy), e1 = std::max(e0 + 1, std::min(H, gb - std::min(dy, 0)));
            auto u = Clock::now();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[kPostA]);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(size_t(rows) * W * 12), rgb.data() + size_t(ga) * W * 3);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[kPostEff]);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(size_t(e1 - e0) * W * 4), eff.data() + size_t(e0) * W);
            check("post upload");
            if (pendingY >= 0) { std::copy(pending.begin(), pending.end(), rgb.begin() + std::ptrdiff_t(size_t(pendingY) * W * 3)); postCommitted = y0; }
            glFinish(); upMs += ms(u); u = Clock::now();
            w = W; h = rows;
            int cur = kPostA, other = kPostB;
            auto common = [&](GLuint p) {
                glUseProgram(p);
                bind(0, cur); bind(1, other); bind(2, kPostEff); bind(3, kPostT0); bind(4, kPostT1); bind(5, kPostCnt);
                glUniform2i(glGetUniformLocation(p, "off"), dx, dy);
                glUniform4i(glGetUniformLocation(p, "stripU"), ga, e0, y0 - ga, y1 - ga);
            };
            if (along) {
                const int k = std::clamp(alongS, 1, 4);
                common(pAlongRows);
                glUniform1i(glGetUniformLocation(pAlongRows, "alongS"), k);
                run(pAlongRows);
                common(pAlongApply);
                glUniform1i(glGetUniformLocation(pAlongApply, "alongS"), k);
                glUniform2f(glGetUniformLocation(pAlongApply, "alongU"), std::max(alongK, 0.01f), std::min(alongStrength, 1.f));
                glUniform1i(glGetUniformLocation(pAlongApply, "imageH"), H);
                run(pAlongApply);
                std::swap(cur, other);
            }
            if (notchOn) {
                for (GLuint p : {pNotchRows, pNotchApply}) {
                    common(p);
                    glUniform1fv(glGetUniformLocation(p, "cosT"), notchPeriod, cosT);
                    glUniform1fv(glGetUniformLocation(p, "sinT"), notchPeriod, sinT);
                    glUniform1fv(glGetUniformLocation(p, "gN"), 2 * notchR + 1, gN);
                    glUniform2i(glGetUniformLocation(p, "notchPR"), notchPeriod, notchR);
                    if (p == pNotchApply) glUniform2f(glGetUniformLocation(p, "notchG"), notchGateLo, notchGateHi);
                    if (p == pNotchApply) glUniform3f(glGetUniformLocation(p, "notchU"), notch[0], notch[1], std::min(notch[2], 1.f));
                    run(p);
                }
                std::swap(cur, other);
            }
            if (smoothOn) { // P603 round 2
                const float k = std::max(smooth[3], 0.1f);
                common(pSmoothT);
                glUniform4f(glGetUniformLocation(pSmoothT, "smoothU"), smooth[0], smooth[1], std::min(smooth[2], 1.f), -0.5f / (k * k));
                glUniform2f(glGetUniformLocation(pSmoothT, "noiseU"), smooth[4], smooth[5]);
                glUniform1i(glGetUniformLocation(pSmoothT, "imageH"), H);
                glUniform1i(glGetUniformLocation(pSmoothT, "effRowsU"), e1 - e0);
                run(pSmoothT);
                std::swap(cur, other);
            }
            check("post passes");
            glFinish(); passMs += ms(u); u = Clock::now();
            const int keep = y1 < H ? std::min(kPostHalo, y1 - y0) : 0, direct = y1 - y0 - keep;
            if (direct) postCommitted = std::max(postCommitted, y0 + 1); // (a failed map may have left part of these rows written)
            read(cur, size_t(y0 - ga) * W * 12, rgb.data() + size_t(y0) * W * 3, size_t(direct) * W * 12);
            pending.resize(size_t(keep) * W * 3);
            if (keep) read(cur, size_t(y0 + direct - ga) * W * 12, pending.data(), size_t(keep) * W * 12);
            pendingY = keep ? y0 + direct : -1;
            readMs += ms(u);
        }
        GLuint counts[4]{};
        read(kPostCnt, 0, counts, 16);
        if (changed) { changed[0] = counts[0]; changed[1] = counts[1]; }
        if (smoothChanged) *smoothChanged = counts[2];
        for (int slot : {kPostA, kPostB, kPostEff, kPostT0, kPostT1}) alloc(slot, 16);
        check("post readback");
        times += " post upload " + std::to_string(int(upMs)) + " passes " + std::to_string(int(passMs)) + " readback "
                 + std::to_string(int(readMs)) + " (" + std::to_string((H + strip - 1) / strip) + " strips)";
    }
    // GPU memory of one call (B): rgb 12, ratios / medians / wide ratios 3 x 8, scalars 3 x 4 per pixel
    static size_t bytesFor(int w, int h) { return size_t(w) * h * 48; }
    void process(std::vector<float>& rgb, int width, int height, int block, const std::vector<float>* osc) {
        if (rgb.size() < size_t(width) * size_t(height) * 3) throw std::runtime_error("CHROMA GPU: sizes");
        processRows(nullptr, 0, rgb.data(), width, height, block, osc, rgb.data(), 0, height);
        medianOf = rgb.data(); medianW = width; medianH = height; // P662: kRgb = every row just read back
    }
    // P610 (speed, 50 MP: mosaicChromaMedianBands): one call on width x height rows given in two parts - the first topRows from top,
    // the rest from rest (the band's kept originals above it and the frame's own rows: no copy of the band) - and the result's rows
    // [readFirst, readEnd) read back into dst. The same bytes in the buffers as process() of the band copied together.
    void processRows(const float* top, int topRows, const float* rest, int width, int height, int block, const std::vector<float>* osc,
                     float* dst, int readFirst, int readEnd) {
        medianOf = nullptr; // P662: kRgb is about to hold this call's rows
        w = width; h = height;
        const size_t P = size_t(w) * h;
        if (topRows < 0 || topRows > h || (topRows > 0 && !top) || !rest || !dst || readFirst < 0 || readEnd < readFirst || readEnd > h)
            throw std::runtime_error("CHROMA GPU: rows");
        auto t = Clock::now();
        GLint64 maxBlock = 0;
        glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &maxBlock);
        if (maxBlock > 0 && P * 12 > size_t(maxBlock)) throw std::runtime_error("CHROMA GPU: frame larger than a storage block");
        const int d = std::max(1, block / 2), r1 = block, r2 = 2 * block, r3 = 3 * block, bw = w / block, bh = h / block;
        if (r3 > 32) throw std::runtime_error("CHROMA GPU: block " + std::to_string(block) + " beyond the box tiles");
        const bool rawOsc = osc && int(osc->size()) == bw * bh;
        const float eps = 0.002f;
        const size_t topBytes = size_t(topRows) * w * 12;
        ensure(kRgb, P * 12);
        if (topBytes) glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(topBytes), top);
        if (P * 12 > topBytes) glBufferSubData(GL_SHADER_STORAGE_BUFFER, GLintptr(topBytes), GLsizeiptr(P * 12 - topBytes), rest);
        ensure(kUv, P * 8); ensure(kT, P * 8); ensure(kX, P * 8);
        ensure(kL, P * 4); ensure(kA, P * 4); ensure(kS, P * 4);
        ensure(kOsc, rawOsc ? osc->size() * 4 : 16);
        if (rawOsc) glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(osc->size() * 4), osc->data());
        check("upload");
        glFinish(); times += " upload " + std::to_string(int(ms(t))); t = Clock::now();
        // ratios and luma
        glUseProgram(pRatio);
        bind(0, kRgb); bind(1, kUv); bind(2, kL);
        glUniform1f(glGetUniformLocation(pRatio, "eps"), eps);
        run(pRatio);
        // medians: horizontal uv -> t, vertical t -> uv
        glUseProgram(pMedian);
        glUniform1i(glGetUniformLocation(pMedian, "d"), d);
        bind(1, kUv); bind(3, kT);
        glUniform1i(glGetUniformLocation(pMedian, "dir"), 0);
        run(pMedian);
        bind(1, kT); bind(3, kUv);
        glUniform1i(glGetUniformLocation(pMedian, "dir"), 1);
        run(pMedian);
        // luma high-pass hp = L - box(L, r1) into S; sign count into A; z = box(sc, r2) into S
        box(pBoxF, kL, kA, r1, 0, 0, kL);
        box(pBoxF, kA, kS, r1, 1, 1, kL);   // reads the luma for the high-pass
        glUseProgram(pSign);
        bind(4, kS); bind(5, kA); bind(6, kL);
        run(pSign);
        // (the luma binding is the input again where unused: no buffer bound for reading and writing in one dispatch)
        box(pBoxF, kA, kL, r2, 0, 0, kA);
        box(pBoxF, kL, kS, r2, 1, 0, kL);
        // wide ratios: box(box(uv, r3), r3) into X
        box(pBoxV2, kUv, kT, r3, 0, 0, kA);
        box(pBoxV2, kT, kX, r3, 1, 0, kA);
        box(pBoxV2, kX, kT, r3, 0, 0, kA);
        box(pBoxV2, kT, kX, r3, 1, 0, kA);
        // P306: taps d >= 2: the blend takes the medians smoothed by [1 2 1] x [1 2 1] (into T, free after the wide boxes)
        int medians = kUv;
        if (d >= 2) {
            glUseProgram(pSmooth);
            bind(1, kUv); bind(3, kT);
            run(pSmooth);
            medians = kT;
        }
        // blend and R / B
        glUseProgram(pBlend);
        bind(0, kRgb); bind(1, medians); bind(3, kX); bind(4, kS); bind(7, kOsc);
        glUniform1f(glGetUniformLocation(pBlend, "eps"), eps);
        // P306: taps d >= 2 (Tetra): the ramp at half the counts (MosaicChromaStream's z0, z1 in scam-hybrid.h)
        const float zs = d >= 2 ? 0.5f : 1.f;
        glUniform2f(glGetUniformLocation(pBlend, "zr"), 0.51f / (2 * block) * zs, 1.02f / (2 * block) * zs);
        glUniform4i(glGetUniformLocation(pBlend, "oscU"), rawOsc ? 1 : 0, block, std::max(bw, 1), std::max(bh, 1));
        run(pBlend);
        check("passes");
        glFinish(); times += " passes " + std::to_string(int(ms(t))); t = Clock::now();
        // readback (direct map; Adreno may return a NULL map for a large range: then in chunks)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[kRgb]);
        constexpr size_t kChunk = size_t(32) << 20;
        const size_t first = size_t(readFirst) * w * 12, bytes = size_t(readEnd - readFirst) * w * 12;
        for (size_t off = 0; off < bytes; off += kChunk) {
            const size_t n = std::min(kChunk, bytes - off);
            const void* m = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, GLintptr(first + off), GLsizeiptr(n), GL_MAP_READ_BIT);
            if (!m) throw std::runtime_error("CHROMA GPU readback failed");
            std::memcpy(reinterpret_cast<char*>(dst) + off, m, n);
            if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) throw std::runtime_error("CHROMA GPU storage invalidated");
        }
        check("readback");
        times += " readback " + std::to_string(int(ms(t)));
    }
    // P610: the chroma median's storage given back (kept by ensure() between the bands of a frame) once a frame is done
    // P662: keepMedian - a whole frame's result in kRgb (medianOf) stays for the Quad lattice that takes this context next
    void releaseBuffers(bool keepMedian = false) {
        if (!keepMedian) medianOf = nullptr;
        for (int slot : {kRgb, kUv, kT, kX, kL, kA, kS, kOsc}) if (bufBytes[slot] > 16 && !(slot == kRgb && medianOf)) alloc(slot, 16);
    }
};

// P80: IEEE 754 binary16 of a float, rounded to nearest even (as the ARM FCVT / __fp16 conversion): overflow to infinity, NaN kept
// (quiet), subnormal halves below 2^-14. Portable (the host checks build the worker with g++ on x86).
inline uint16_t floatToHalf(float f) {
    uint32_t x; std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    const uint32_t absx = x & 0x7FFFFFFFu;
    if (absx >= 0x7F800000u) return uint16_t(sign | 0x7C00u | (absx > 0x7F800000u ? 0x200u | ((absx >> 13) & 0x3FFu) : 0u));
    if (absx >= 0x477FF000u) return uint16_t(sign | 0x7C00u);        // rounds to >= 65520: infinity
    if (absx < 0x38800000u) {                                          // below 2^-14: subnormal half (or zero)
        if (absx < 0x33000000u) return uint16_t(sign);                 // below 2^-25: rounds to zero
        const uint32_t mant = (absx & 0x7FFFFFu) | 0x800000u;
        const int shift = 126 - int(absx >> 23);                       // 14..24
        const uint32_t half = mant >> shift, rem = mant & ((1u << shift) - 1u), mid = 1u << (shift - 1);
        return uint16_t(sign | (half + ((rem > mid || (rem == mid && (half & 1u))) ? 1u : 0u)));
    }
    const uint32_t e = (absx >> 23) - 112u, m = absx & 0x7FFFFFu;      // rebias 127 -> 15
    uint32_t h = (e << 10) | (m >> 13);
    const uint32_t rem = m & 0x1FFFu;
    if (rem > 0x1000u || (rem == 0x1000u && (h & 1u))) ++h;            // may carry into the exponent: correct
    return uint16_t(sign | h);
}

// P80: one context built ahead (during the merge) and one teardown behind (after the median): both off the shot's path. The
// teardown thread is joined before the next one starts and at the process exit (a static destructor), never left running.
// P219: a context built ahead and never taken (a 50 MP merge: its median runs on the CPU, the size is above the GPU path's) is left
// to the process exit, not torn down from this static destructor: on the OPPO Find X7 Ultra eglMakeCurrent crashed there (SIGSEGV
// in libGLESv2_adreno after «SCAM CAPTURE OK», which the app takes for a failed merge) whenever the vendor CRE library had loaded
// after the context was built (its exit handlers ran first): 2 of 4 50 MP replays once the merge no longer waited for the
// worker's mosaic detector. The process exit frees it with the rest.
struct ChromaGpuSlot {
    std::future<std::unique_ptr<ChromaMedianGpu>> ahead;
    std::thread teardown;
    // P610 (speed): the next chromaGiveBack keeps the context for a later chromaTake (the fast route's Quad lattice after the chroma
    // median: it made a context of its own and compiled every program again, ~0.1 - 0.3 s of a 50 MP shot)
    bool keepNext = false;
    void join() { if (teardown.joinable()) teardown.join(); }
    ~ChromaGpuSlot() {
        join();
        if (ahead.valid()) try { ChromaMedianGpu* left = ahead.get().release(); (void)left; } catch (...) {}
    }
};
inline ChromaGpuSlot& chromaGpuSlot() { static ChromaGpuSlot slot; return slot; }
// P610: lattice = also the fast route's Quad lattice programs (quadLattice after the chroma median)
inline void chromaPrewarm(bool post = false, bool lattice = false) {
    ChromaGpuSlot& s = chromaGpuSlot();
    if (s.ahead.valid()) return;
    try {
        s.ahead = std::async(std::launch::async, [post, lattice] {
            auto g = std::make_unique<ChromaMedianGpu>();
            if (post) g->ensurePost(); // P531: the native merge's post passes
            if (lattice) g->ensureQuadLattice();
            g->release();
            return g;
        });
    } catch (const std::exception&) {}
}
// The prewarmed context (or a new one), current on this thread.
inline std::unique_ptr<ChromaMedianGpu> chromaTake() {
    ChromaGpuSlot& s = chromaGpuSlot();
    std::unique_ptr<ChromaMedianGpu> g;
    if (s.ahead.valid()) {
        g = s.ahead.get();                                     // a failed build rethrows here
        // P531: a context the display lost meanwhile (Mesa's eglTerminate of the merge's context ends every context of the
        // default display, Android's counts its eglInitialize calls) is built again here
        try { g->acquire(); } catch (const std::exception&) { g.reset(); g = std::make_unique<ChromaMedianGpu>(); }
    } else g = std::make_unique<ChromaMedianGpu>();
    return g;
}
// P531: a context put back for a later take (the chroma median after the post passes), not torn down
inline void chromaKeep(std::unique_ptr<ChromaMedianGpu> g) {
    if (!g) return;
    g->times.clear();
    g->release();
    std::promise<std::unique_ptr<ChromaMedianGpu>> p;
    chromaGpuSlot().ahead = p.get_future();
    p.set_value(std::move(g));
}
inline void chromaGiveBack(std::unique_ptr<ChromaMedianGpu> g) {
    if (!g) return;
    ChromaGpuSlot& s = chromaGpuSlot();
    if (s.keepNext) { // P610: a later pass takes this context (its storage given back first)
        s.keepNext = false;
        try { g->acquire(); g->releaseBuffers(true); } catch (const std::exception&) {}
        chromaKeep(std::move(g));
        return;
    }
    g->release();
    s.join();
    ChromaMedianGpu* p = g.release();
    try { s.teardown = std::thread([p] { delete p; }); }
    catch (const std::exception&) { delete p; } // no thread: torn down here
}
// P610: the next chromaGiveBack keeps its context for the next chromaTake (ChromaGpuSlot::keepNext)
inline void chromaKeepNext(bool keep = true) { chromaGpuSlot().keepNext = keep; }

} // namespace scam
