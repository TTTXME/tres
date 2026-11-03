#pragma once
#include <stdint.h>

namespace scam {
inline bool hybridBayerQzEligible(bool native,int grid,int width,int height,int frames,
                                  bool normals,bool dcg,bool donorSums){
    return !native&&grid==1&&width>0&&height>0&&int64_t(width)*height<=13000000
        &&frames>=5&&frames<=128&&normals&&!dcg&&!donorSums;
}
inline bool hybridBayerQuadsEligible(int enabled,bool qz,bool standardCells,bool failed){
    return enabled>0&&qz&&standardCells&&!failed;
}
inline uint64_t hybridBayerQuadBudget(uint64_t available){
    const uint64_t cap=uint64_t(128)<<20;
    return available/8<cap?available/8:cap;
}
inline bool hybridBayerQuadStorage(int width,int height,int layers,uint64_t& bytes){
    bytes=0;
    if(width<=0||height<=0||layers<2||layers>128)return false;
    uint64_t n=uint64_t(width);
    const uint64_t limit=~uint64_t(0);
    if(n>limit/uint64_t(height))return false;n*=uint64_t(height);
    if(n>limit/uint64_t(layers))return false;n*=uint64_t(layers);
    if(n>limit/8)return false;n*=8;
    if(n>(uint64_t(128)<<20))return false;
    bytes=n;return true;
}
inline bool hybridBayerQuadMemory(uint64_t requested,uint64_t held,bool reuse,uint64_t available){
    const uint64_t budget=hybridBayerQuadBudget(available);
    if(!requested||requested>budget||held>budget)return false;
    // Reallocation can overlap the previous texture until queued GPU work retires.
    return reuse?requested<=held:held<=available/8-requested;
}

static const char* kHybQzMain=R"QZ(
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+chunkU+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=cy1)return;
    vec4 cv=cov[(cy-ry0)*w2+cx];
    vec3 P=cv.xyz;
    float m=kD.w>0.5?bmask[(cy-cy0)*w2+cx]:0.0;
    // Sabre 6.1 2x grid: the output pixel centres fall on sensor positions x/2 - 0.25, i.e. the sub-positions
    // +-0.25 px of every sensor pixel; one dispatch per sub-position, the kernel stays in sensor pixel units.
    int g=kG.x,ow=kG.y,sx=kG.z,sy=kG.w;
    vec2 sub=g>1?(vec2(float(sx),float(sy))+0.5)/float(g)-0.5:vec2(0.0); // grid 2: +-0.25 exactly; grid 4 (Tetra sub-frames): +-0.125, +-0.375
    int oy0=2*cy0*g;
    vec2 cell=vec2(float(2*cx),float(2*cy));
    int mode=mergeModeU.x;
    float wb=1.0-m;
    if(kE.x>=0.0||mergeModeU.y!=0)wb=0.0;
    { int q=int(gl_WorkGroupID.z);
        int x=2*cx+(q&1),y=2*cy+(q>>1);
        vec2 pos=vec2(float(x),float(y))+sub;
        Acc a;initAcc(a);
        float frames=0.0; // accepted donor frames (6.1 accumulated_frame_weights)
        for(int f=1;f<frameCount;f++){
            float r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
            if(r<0.004)continue;
            if(kE.x>=0.0&&int(kE.x)!=f)continue;
            vec2 oc=originM(f,2*cx,2*cy);
            vec2 O=oc+(pos-cell);
            // ultrashort frame: isotropic kernel wide enough for a single Bayer frame (the base guide is an edge
            // along every highlight border, its across-edge sigma leaves R/B holes = green/magenta zipper)
            bool us=int(fParam[f].w)==5;
            vec3 Pf;
            if(us)Pf=vec3(kE.y,kE.y,0.0);
            else if((mode&1)!=0){ vec2 dc=floor((O+0.5)*0.5); Pf=dcovAt(f,int(dc.x),int(dc.y))*fParam[f].z; } // 6.1: NEAREST at the sample
            else Pf=P*fParam[f].z;
            float rw=r/max(fParam[f].y,1.0e-6);
            if(!us)frames+=rw;
            if(markU!=0)frameSamples(a,f,O,r,rw,Pf,(mode&2)!=0&&!us); else frameSamplesPlain(a,f,O,r,rw,Pf,(mode&2)!=0&&!us);
        }
        // Base frame last: inside the Bento mask it yields to the ultrashort frame; where the donors left
        // little coverage its kernel widens (6.1: covariance x0.3 below 4 accepted frames).
        if(wb>0.0){
            vec3 Pb;
            if((mode&4)!=0)Pb=frames<kD.x?P/(kD.y*kD.y):P;
            else { float widen=mix(kD.y,1.0,smoothstep(0.5*kD.x,kD.x,a.cover)); Pb=P/(widen*widen); }
            if(markU!=0)frameSamples(a,0,pos,wb,wb,Pb,(mode&2)!=0&&m<=0.0); else frameSamplesPlain(a,0,pos,wb,wb,Pb,(mode&2)!=0&&m<=0.0);
        }
        vec3 col;uint cfl=0u;
        for(int c=0;c<3;c++){
            if(a.den[c]>1.0e-7)col[c]=a.num[c]/a.den[c];
            else if(a.clipDen[c]>0.0){col[c]=a.clipNum[c]/a.clipDen[c];cfl|=1u<<uint(c);} // everything clipped: keep the clipped level
            // No sample at all: an outlier site under the 6.1 window (+-1.5 px; the next R/B site is 2 px away). Where the frames do
            // not move against each other (tripod) every frame skips the same site and the colour came out 0 (static burst replay:
            // ~750 R/B holes): the base frame's lattice around the pixel fills it.
            else col[c]=(markU!=0&&wb>0.0)?baseFill(c,pos):0.0;
            if(a.clipDen[c]>0.0)cfl|=8u;
        }
        int ox=x*g+sx,oy=y*g+sy;
        int o=((oy-oy0)*ow+ox)*3;
        outRgb[o]=col.x;outRgb[o+1]=col.y;outRgb[o+2]=col.z;
        eff[(oy-oy0)*ow+ox]=a.cover+wb;
        if(mergeModeU.z!=0||rimU.x>0.0){
            if(m>0.0)cfl|=16u;
            if(a.usClip>0.0&&(cfl&7u)!=0u)cfl|=32u;
            // candidates of kHybRim (bits 8-13; the trailer keeps the low byte): the largest share of a colour's kernel weight that
            // went to clipped samples, 0 = left alone. Only pixels with a real green: where green fell back to the clipped mean too
            // (inside the highlight), the colour of the real sites around may be that of another surface (a lamp rim measured bluish
            // next to its white inside): those are left to the highlight recovery and its defringe (scamhdr/scamrgb).
            if(rimU.x>0.0&&(cfl&8u)!=0u&&a.den[1]>1.0e-7){
                float e=0.0;
                for(int c=0;c<3;c++)e=max(e,a.clipDen[c]/max(a.den[c]+a.clipDen[c],1.0e-20));
                cfl|=uint(clamp(e,0.0,1.0)*63.0+0.5)<<8;
            }
            // bit 6: the clip-border colour of this pixel is the worker's (kHybRim ran on the merge): consumers skip their own
            // blanket border defringe, which inside a Bento highlight (bit 3 almost everywhere) also took the colour of real lights
            if(rimU.x>0.0&&(cfl&8u)!=0u)cfl|=64u;
            cflags[(oy-oy0)*ow+ox]=cfl;
        }
    }
}
)QZ";
static const char* kHybQzQuadPlain=R"QZ(
layout(std430,binding=8) readonly buffer Robust{float robust[];};
#ifdef RIM_STATS
layout(std430,binding=3) buffer Out{float outRgb[];};
layout(std430,binding=6) buffer CFlags{uint cflags[];};
#elif (defined(NAT_BASEFILL) && NAT_BASEFILL) || (defined(NAT_DEMOSAIC) && NAT_DEMOSAIC)
layout(std430,binding=3) buffer Out{float outRgb[];};
layout(std430,binding=4) writeonly buffer Eff{float eff[];};
layout(std430,binding=6) writeonly buffer CFlags{uint cflags[];};
#else
layout(std430,binding=3) writeonly buffer Out{float outRgb[];};
layout(std430,binding=4) writeonly buffer Eff{float eff[];};
layout(std430,binding=6) writeonly buffer CFlags{uint cflags[];};
#endif
layout(std430,binding=11) readonly buffer Cov{vec4 cov[];};
layout(std430,binding=13) readonly buffer Mask{float bmask[];};
uniform int cy0;
uniform int cy1;
uniform int ry0;
uniform vec4 kD; // widen below, widen multiplier, kernel floor, bento active
uniform vec4 kE; // debug frame (-1 = all), ultrashort kernel precision 1/sigma^2, 0, 0
uniform ivec4 kG; // output grid (1|2), output row width, sub-position x (0|1), sub-position y (0|1)
uniform ivec4 mergeModeU; // x: 1 per-frame 6.1 covariance, 2 6.1 window, 4 6.1 base widening (frames); y: no base; z: clip flags
uniform vec4 rimU;        // x: 1 = clip-border colour pass (kHybRim) on: the merge marks its candidates in the clip flags
#ifdef RIM_STATS
// kHybRim: trueDen = the part of clipDen from sites at or above the clip (the colour itself saturated); the rest of clipDen is real
// values of the other sites of clipped cells (cellClip). Not in the merge itself: three more accumulators in its frame loop cost
// ~12 % of the merge time on Adreno 750.
struct Acc{vec3 num;vec3 den;float cover;vec3 clipNum;vec3 clipDen;float usClip;vec3 trueDen;};
void initAcc(out Acc a){a.num=vec3(0.0);a.den=vec3(0.0);a.cover=0.0;a.clipNum=vec3(0.0);a.clipDen=vec3(0.0);a.usClip=0.0;a.trueDen=vec3(0.0);}
#else
struct Acc{vec3 num;vec3 den;float cover;vec3 clipNum;vec3 clipDen;float usClip;};
void initAcc(out Acc a){a.num=vec3(0.0);a.den=vec3(0.0);a.cover=0.0;a.clipNum=vec3(0.0);a.clipDen=vec3(0.0);a.usClip=0.0;}
#endif
#ifdef JSR_LCA
uniform vec4 jsrCaA; // P74: k1 / k2 of R, k1 / k2 of B (P19 model: displacement 2 (k1 + k2 r^2) q RAW px, q = (cell - centre) / half)
uniform vec4 jsrCaB; // centre x / y (cells), 1 / half
vec2 jsrSite(int c,vec2 s){
    if(c==1)return s;
    vec2 q=(0.5*s-jsrCaB.xy)*jsrCaB.z;float r2=dot(q,q);
    float k=c==0?jsrCaA.x+jsrCaA.y*r2:jsrCaA.z+jsrCaA.w*r2;
    return s-2.0*k*q;
}
#else
#define jsrSite(c,s) (s)
#endif
float kernelW(vec2 d,vec3 P){
    return exp2(-0.72135*(d.x*d.x*P.x+d.y*d.y*P.y+2.0*d.x*d.y*P.z))+kD.z; // exp(-0.5 d'Pd) + floor
}
#ifdef LOCAL_ALIGN
layout(std430,binding=16) readonly buffer LaCell{uint laCell[];};
// origin() plus the F6 offset kHybDilate stored for donor f (>= 1) and the strip cell (x/2, y/2). Indices clamped: a load the
// compiler hoists out of a branch (f = 0 in the rim loop) stays inside the buffer.
vec2 originM(int f,int x,int y){
    int w2=size.x/2,f1=clamp(f,1,max(frameCount-1,1))-1,cx=clamp(x>>1,0,w2-1),cy=clamp((y>>1)-cy0,0,max(cy1-cy0-1,0));
    return origin(f,x,y)+unpackHalf2x16(laCell[uint(f1*(cy1-cy0)*w2+cy*w2+cx)]);
}
#else
#define originM(f,x,y) origin(f,x,y)
#endif
// Sites of frame f around position O (frame coordinates): the two lattice sites per axis of every colour phase. Site flags come
// with the RAW word: an outlier site gives no sample; a site of a clipped cell sends its value to the clipped mean (used only
// where nothing valid is left), so a clip border does not average one colour from one side only. win = the 6.1 window
// max(|dx|,|dy|) <= 1.5.
layout(binding=9) uniform highp usampler2DArray qzQuadTex;
uniform ivec2 qzQuadRows[128];
void qzQuadDecode(uvec3 words,ivec3 phase,out vec3 values,out vec3 good,out vec3 clipped,out vec3 trueClip){
    uvec3 flags=markU!=0?words>>14:uvec3(0u);
    words&=uvec3(markU!=0?0x3FFFu:0xFFFFu);
    values=clamp((vec3(words)-vec3(black[phase.x],black[phase.y],black[phase.z]))*vec3(inv[phase.x],inv[phase.y],inv[phase.z]),vec3(-0.25),vec3(1.0));
    good=vec3(equal(flags&uvec3(1u),uvec3(0u)));
    trueClip=vec3(greaterThanEqual(values,vec3(clipLevel)));
    clipped=max(trueClip,vec3(notEqual(flags&uvec3(2u),uvec3(0u))));
}
bool qzQuadRead9(int f,ivec2 N,out vec3 v0,out vec3 v1,out vec3 v2,out vec3 ok0,out vec3 ok1,out vec3 ok2,out vec3 cl0,out vec3 cl1,out vec3 cl2,out vec3 tc0,out vec3 tc1,out vec3 tc2){
    if(f<=0)return false;
    ivec2 first=N-ivec2(1),c=first/2;
    if(any(lessThan(first,ivec2(0)))||c.x+1>=size.x/2||c.y<qzQuadRows[f].x||c.y+1>=qzQuadRows[f].y)return false;
    int row=c.y-fCells[f].x;
    if(row<0||row+1>=fCells[f].y)return false;
    uvec4 a=texelFetch(qzQuadTex,ivec3(c.x,row,f),0);
    uvec4 b=texelFetch(qzQuadTex,ivec3(c.x+1,row,f),0);
    uvec4 d=texelFetch(qzQuadTex,ivec3(c.x,row+1,f),0);
    uvec4 e=texelFetch(qzQuadTex,ivec3(c.x+1,row+1,f),0);
    uvec3 w0,w1,w2;
    if((first.x&1)==0){
        if((first.y&1)==0){w0=uvec3(a.xy,b.x);w1=uvec3(a.zw,b.z);w2=uvec3(d.xy,e.x);}
        else{w0=uvec3(a.zw,b.z);w1=uvec3(d.xy,e.x);w2=uvec3(d.zw,e.z);}
    }else{
        if((first.y&1)==0){w0=uvec3(a.y,b.xy);w1=uvec3(a.w,b.zw);w2=uvec3(d.y,e.xy);}
        else{w0=uvec3(a.w,b.zw);w1=uvec3(d.y,e.xy);w2=uvec3(d.w,e.zw);}
    }
    int px=(first.x+cfaShift.x)&1,py=(first.y+cfaShift.y)&1;
    ivec3 p0=ivec3(2*py+px,2*py+(px^1),2*py+px),p1=p0^ivec3(2);
    qzQuadDecode(w0,p0,v0,ok0,cl0,tc0);
    qzQuadDecode(w1,p1,v1,ok1,cl1,tc1);
    qzQuadDecode(w2,p0,v2,ok2,cl2,tc2);
    return true;
}
float qzNineRead(int f,ivec2 site,out float good,out float clipped,out float trueClip){
    uint flags;float value=rawSite(f,site.x,site.y,flags);
    good=(flags&1u)==0u?1.0:0.0;
    clipped=((flags&2u)!=0u||value>=clipLevel)?1.0:0.0;
    trueClip=value>=clipLevel?1.0:0.0;
    return value;
}
void qzNineAcc(inout Acc a,int c,float r,float cover,float g,vec4 values,vec4 weights,vec4 clips,vec4 trueClips){
    vec4 clippedW=weights*clips,normalW=weights-clippedW;
    a.clipNum[c]+=r*dot(clippedW,values)*g;a.clipDen[c]+=r*dot(clippedW,vec4(1.0));
#ifdef RIM_STATS
    a.trueDen[c]+=r*dot(weights*trueClips,vec4(1.0));
#endif
    a.num[c]+=r*dot(normalW,values)*g;a.den[c]+=r*dot(normalW,vec4(1.0));
    if(c==1)a.cover+=cover*dot(normalW,vec4(1.0));
}
bool qzNineFrame(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win,int role){
#ifdef JSR_LCA
    return false;
#else
    if(!win||role!=1||any(isnan(O))||any(isinf(O))||any(greaterThan(abs(O),vec2(1000000.0))))return false;
    ivec2 N=ivec2(floor(O+0.5));
    // At a half-site boundary the inclusive original window contains four sites.
    if(any(greaterThanEqual(abs(vec2(N)-O),vec2(0.49999))))return false;
    vec3 dx=vec3(float(N.x)-O.x)+vec3(-1.0,0.0,1.0);
    vec3 dy=vec3(float(N.y)-O.y)+vec3(-1.0,0.0,1.0);
    vec3 xx=dx*dx*P.x;
    vec3 xz=2.0*dx*P.z;
    vec3 w0=exp2(-0.72135*(xx+vec3(dy.x*dy.x*P.y)+xz*dy.x))+vec3(kD.z);
    vec3 w1=exp2(-0.72135*(xx+vec3(dy.y*dy.y*P.y)+xz*dy.y))+vec3(kD.z);
    vec3 w2=exp2(-0.72135*(xx+vec3(dy.z*dy.z*P.y)+xz*dy.z))+vec3(kD.z);
    if(f!=0){w0*=step(vec3(0.002),w0);w1*=step(vec3(0.002),w1);w2*=step(vec3(0.002),w2);}
    vec3 v0,v1,v2,ok0,ok1,ok2,cl0,cl1,cl2,tc0,tc1,tc2;
    if(!qzQuadRead9(f,N,v0,v1,v2,ok0,ok1,ok2,cl0,cl1,cl2,tc0,tc1,tc2))return false;
    w0*=ok0;w1*=ok1;w2*=ok2;
    int corner=((N.y-1)&1)*2+((N.x-1)&1);
    float g=fParam[f].x;
    qzNineAcc(a,phaseColor[corner],r,cover,g,vec4(v0.x,v0.z,v2.x,v2.z),vec4(w0.x,w0.z,w2.x,w2.z),vec4(cl0.x,cl0.z,cl2.x,cl2.z),vec4(tc0.x,tc0.z,tc2.x,tc2.z));
    qzNineAcc(a,phaseColor[corner^1],r,cover,g,vec4(v0.y,v2.y,0.0,0.0),vec4(w0.y,w2.y,0.0,0.0),vec4(cl0.y,cl2.y,0.0,0.0),vec4(tc0.y,tc2.y,0.0,0.0));
    qzNineAcc(a,phaseColor[corner^2],r,cover,g,vec4(v1.x,v1.z,0.0,0.0),vec4(w1.x,w1.z,0.0,0.0),vec4(cl1.x,cl1.z,0.0,0.0),vec4(tc1.x,tc1.z,0.0,0.0));
    qzNineAcc(a,phaseColor[corner^3],r,cover,g,vec4(v1.y,0.0,0.0,0.0),vec4(w1.y,0.0,0.0,0.0),vec4(cl1.y,0.0,0.0,0.0),vec4(tc1.y,0.0,0.0,0.0));
    return true;
#endif
}
void frameSamples(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){
    float g=fParam[f].x;
    int role=int(fParam[f].w);
    if(qzNineFrame(a,f,O,r,cover,P,win,role))return;
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=jsrSite(c,vec2(float(sx),float(sy)))-O;
            if(win&&max(abs(d.x),abs(d.y))>1.5)continue;
            float kw=kernelW(d,P);
            if(kw<0.002&&f!=0&&role!=5)continue;
            uint fl;
            float v=rawSite(f,sx,sy,fl);
            if((fl&1u)!=0u)continue;                           // outlier site: no sample at all
            if((fl&2u)!=0u||v>=clipLevel){
                if(role==3)continue;                           // a clipped longer frame is only a lower bound below the base's
                a.clipNum[c]+=r*kw*v*g;a.clipDen[c]+=r*kw;
#ifdef RIM_STATS
                if(v>=clipLevel)a.trueDen[c]+=r*kw;
#endif
                if(role==5)a.usClip+=r*kw;
                continue;
            }
            a.num[c]+=r*kw*v*g;a.den[c]+=r*kw;
            if(c==1)a.cover+=cover*kw;
        }
    }
}
#ifndef RIM_STATS
// Mean of the base frame's sites of colour c on the lattice around O (two sites per axis and phase, no window, no kernel), outlier
// sites skipped: the fill of a colour that lost every sample to outlier sites (see main).
float baseFill(int c,vec2 O){
    float s=0.0,n=0.0;
    for(int p=0;p<4;p++){
        if(phaseColor[p]!=c)continue;
        int px=p&1,py=p>>1;
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            uint fl;
            float v=rawSite(0,bx+di,by+dj,fl);
            if((fl&1u)!=0u)continue;
            s+=v;n+=1.0;
        }
    }
    return n>0.0?s*fParam[0].x/n:0.0;
}
// The same without site flags (strips with nothing marked: no outlier test, no clipped sample), as fast as before the flags.
void frameSamplesPlain(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){
    float g=fParam[f].x;
    int role=int(fParam[f].w);
    if(qzNineFrame(a,f,O,r,cover,P,win,role))return;
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=jsrSite(c,vec2(float(sx),float(sy)))-O;
            if(win&&max(abs(d.x),abs(d.y))>1.5)continue;
            float kw=kernelW(d,P);
            if(kw<0.002&&f!=0&&role!=5)continue;
            float v=sampleRaw(f,sx,sy);
            if(v>=clipLevel){
                if(role==3)continue;                           // a clipped longer frame is only a lower bound below the base's
                a.clipNum[c]+=r*kw*v*g;a.clipDen[c]+=r*kw;
                if(role==5)a.usClip+=r*kw;
                continue;
            }
            a.num[c]+=r*kw*v*g;a.den[c]+=r*kw;
            if(c==1)a.cover+=cover*kw;
        }
    }
}
#endif
#ifdef CHROMA_PASS
// Colour-difference interpolation of the base frame (pass kHybChroma, HybridTuning::chromaDiff). The base's R (B) samples alone
// cannot resolve an edge: with the 6.1 window and an across-edge sigma of 0.25 px every output pixel takes the nearest R sites
// across the edge (a period-2 R/B fringe where the donors were rejected). The difference R - G is smooth across a neutral edge,
// so for every unclipped, unflagged R (B) site v of the base inside the window, G at the site is interpolated from its four
// orthogonal green neighbours with the same kernel relative to the site (one weight per axis: d'Pd is even in d, so along the
// edge they dominate) and v - G(site) is accumulated with the site's kernel weight. A neighbour that is flagged or clipped is
// left out; a site that lost most of the neighbour weight (both along-edge greens) or is flagged/clipped itself gives no
// difference, and the pass keeps the plain path for a colour without any. cdLo/cdHi hold the range of the sites that gave a
// difference (the clamp: no new extremum).
void baseChromaDiff(inout vec2 cdNum,inout vec2 cdDen,inout vec2 cdLo,inout vec2 cdHi,vec2 O,vec3 P,bool win){
    float g=fParam[0].x;
    float wx=kernelW(vec2(1.0,0.0),P),wy=kernelW(vec2(0.0,1.0),P),wMin=0.6*(wx+wy);
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        if(c==1)continue;
        int k=c>>1;
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=vec2(float(sx),float(sy))-O;
            if(win&&max(abs(d.x),abs(d.y))>1.5)continue;
            float kw=kernelW(d,P);
            uint fl;
            float v=rawSite(0,sx,sy,fl);
            if((fl&3u)!=0u||v>=clipLevel)continue;
            float gs=0.0,gw=0.0;uint f2;
            float gl=rawSite(0,sx-1,sy,f2);if((f2&3u)==0u&&gl<clipLevel){gs+=wx*gl;gw+=wx;}
            float gr=rawSite(0,sx+1,sy,f2);if((f2&3u)==0u&&gr<clipLevel){gs+=wx*gr;gw+=wx;}
            float gu=rawSite(0,sx,sy-1,f2);if((f2&3u)==0u&&gu<clipLevel){gs+=wy*gu;gw+=wy;}
            float gd=rawSite(0,sx,sy+1,f2);if((f2&3u)==0u&&gd<clipLevel){gs+=wy*gd;gw+=wy;}
            if(gw<wMin)continue;
            cdNum[k]+=kw*(v-gs/gw)*g;cdDen[k]+=kw;
            cdLo[k]=min(cdLo[k],v*g);cdHi[k]=max(cdHi[k],v*g);
        }
    }
}
// P282 (Bayer): the base frame's own demosaic at O where the donors were rejected. A 9x9 site neighbourhood around the nearest
// site; the local direction from the 3x3 sites' gradients (green differences at distance 1 plus same-colour Laplacians); green
// at every red / blue site of the 5x5 core interpolated along that direction with the Laplacian correction (Hamilton-Adams);
// green at O bilinear from the four sites around it; R - G and B - G from the 5x5 core's red / blue sites, weighted by distance
// (narrowed across the edge) and by how close their green is to the green at O (a red / green edge flips R - G: the side O
// belongs to). Returns false (the merge's value stays) at a clipped or flagged site of the neighbourhood.
uniform float rampKU; // P309: the ramp mask's K (0 = the previous mask): the base demosaic fades out over share 0..0.5
uniform vec4 bdU; // x = share at no donor (HybridTuning::baseDemosaic), y = anisotropy, z / w = structure / noise ratio from
                  // which to which the demosaic takes over (flat areas keep the merge's wider, less noisy kernel)
uniform float bdAliasU; // P335: 0..1 share of the false-colour damping (0 = off, the demosaic as before)
bool bayerDemosaic(vec2 O,out vec3 rgb,out float detail){
    ivec2 N=ivec2(floor(O+0.5));
    float A[81];
    for(int j=0;j<9;j++)for(int i=0;i<9;i++){
        uint fl;
        float v=rawSite(0,N.x+i-4,N.y+j-4,fl);
        if((fl&3u)!=0u||v>=clipLevel)return false;
        A[j*9+i]=v;
    }
    float dh=0.0,dv=0.0;
    for(int j=3;j<=5;j++)for(int i=3;i<=5;i++){
        int k=j*9+i;
        dh+=abs(A[k+1]-A[k-1])+0.5*abs(2.0*A[k]-A[k-2]-A[k+2]);
        dv+=abs(A[k+9]-A[k-9])+0.5*abs(2.0*A[k]-A[k-18]-A[k+18]);
    }
    float own=A[40];
    float sig=sqrt(max(baseNoise.x*max(own,0.0)+baseNoise.y,1.0e-10));
    detail=(dh+dv)/(54.0*sig); // ~1 on pure noise (each site's two terms average ~3 sigma per axis)
    float e=3.0*sig+1.0e-5;
    float h2=(dh+e)*(dh+e),v2=(dv+e)*(dv+e);
    float wH=v2/(h2+v2),wV=1.0-wH,aniso=abs(wH-wV);
    // green at the 5x5 core's sites (Gs) and their colours
    float Gs[25];int Cs[25];
    for(int j=0;j<5;j++)for(int i=0;i<5;i++){
        int k=(j+2)*9+i+2;
        int c=phaseColor[(((N.y+j-2)&1)<<1)|((N.x+i-2)&1)];
        Cs[j*5+i]=c;
        if(c==1){Gs[j*5+i]=A[k];continue;}
        float gh=0.5*(A[k-1]+A[k+1])+0.125*(2.0*A[k]-A[k-2]-A[k+2]);
        float gv=0.5*(A[k-9]+A[k+9])+0.125*(2.0*A[k]-A[k-18]-A[k+18]);
        float lo=min(min(A[k-1],A[k+1]),min(A[k-9],A[k+9])),hi=max(max(A[k-1],A[k+1]),max(A[k-9],A[k+9]));
        float r=hi-lo;
        Gs[j*5+i]=clamp(wH*gh+wV*gv,lo-0.5*r,hi+0.5*r); // the Laplacian's overshoot bounded at an edge
    }
    vec2 P=O-vec2(N)+2.0; // O in core coordinates (the core's site (2, 2) is N)
    ivec2 p0=ivec2(floor(P));vec2 f=P-vec2(p0);
    p0=clamp(p0,ivec2(0),ivec2(3));
    float G=mix(mix(Gs[p0.y*5+p0.x],Gs[p0.y*5+p0.x+1],f.x),mix(Gs[(p0.y+1)*5+p0.x],Gs[(p0.y+1)*5+p0.x+1],f.x),f.y);
    float tauG=3.0*sig+0.08*max(G,0.0)+1.0e-4;
    float sa=4.0*bdU.y*aniso;
    vec2 fa=vec2(1.0+sa*wV,1.0+sa*wH); // across a vertical structure (horizontal variation, wV large) narrower in x
    float sR=0.0,nR=0.0,sB=0.0,nB=0.0;
    for(int k=0;k<25;k++){
        int c=Cs[k];
        if(c==1)continue;
        vec2 d=vec2(float(k%5),float(k/5))-P;
        float q=(Gs[k]-G)/tauG;
        float w=exp2(-0.72*(fa.x*d.x*d.x+fa.y*d.y*d.y))/(1.0+q*q);
        float df=A[((k/5)+2)*9+(k%5)+2]-Gs[k];
        if(c==0){sR+=w*df;nR+=w;}else{sB+=w*df;nB+=w;}
    }
    if(nR<=1.0e-6||nB<=1.0e-6)return false;
    float dR=sR/nR,dB=sB/nB;
    if(bdAliasU>0.0){
        // P335 (owner, 2026-10-25: Why's anti-zipper «попробуй его улучшить и добавить нам»): luma detail near the Bayer Nyquist
        // shows in one frame as a step between the two green phases and as false colour (the zipper / moire of a moving subject);
        // where the Gr / Gb step of the 5x5 core is large against the colour difference the colour is damped (Sabre's motion
        // fallback damping). Unlike that one the step is taken above its own noise (the two phase means' sigma x2) and is
        // reduced along a clear edge direction, so a noisy flat area or a straight edge keeps its colour.
        float g0=0.0,n0=0.0,g1=0.0,n1=0.0;
        for(int k=0;k<25;k++){
            if(Cs[k]!=1)continue;
            float v=A[((k/5)+2)*9+(k%5)+2];
            if(((N.y+k/5)&1)==0){g0+=v;n0+=1.0;}else{g1+=v;n1+=1.0;}
        }
        if(n0>0.0&&n1>0.0){
            float gd=max(abs(g0/n0-g1/n1)-2.0*sig*sqrt(1.0/n0+1.0/n1),0.0)*(1.0-0.7*aniso);
            float ar=1.4*gd/(sqrt(dR*dR+dB*dB)+0.02*max(G,0.0)+1.0e-6);
            float damp=mix(1.0,1.0/(1.0+ar*ar),clamp(bdAliasU,0.0,1.0));
            dR*=damp;dB*=damp;
        }
    }
    rgb=max(vec3(G+dR,G,G+dB),vec3(0.0))*fParam[0].x;
    return true;
}
#endif
#ifdef BENTO_PASS
// Bento colour pass (kHybBento; kE.z = 1/sigma^2 of the wide kernel): the colour sums of an ultrashort frame on a wider isotropic kernel (lattice
// sites +-2 per axis and phase), clipped samples counted apart per colour. Inside the mask the ultrashort frames are the only ones:
// with their 1 px kernel R and B come from the nearest R/B site, which steps every 2 px along a slanted edge (orange/blue dashes).
void usWide(inout vec3 num,inout vec3 den,inout vec3 clipW,int f,vec2 O,float r,float P){
    float g=fParam[f].x;
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=-2;dj<=4;dj+=2)for(int di=-2;di<=4;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=vec2(float(sx),float(sy))-O;
            float kw=r*exp2(-0.72135*dot(d,d)*P);
            uint fl=0u;
            float v=markU!=0?rawSite(f,sx,sy,fl):sampleRaw(f,sx,sy);
            if((fl&1u)!=0u)continue;
            if((fl&2u)!=0u||v>=clipLevel){clipW[c]+=kw;continue;}
            num[c]+=kw*v*g;den[c]+=kw;
        }
    }
}
#endif
)QZ";
// P480: kHybQzQuadDezip (P402's copy of kHybQzQuadPlain with its dezip lines) is gone: HybridGpu puts the dezipper into kHybQzQuadPlain with hybridDezipSource, as into kHybMergeCommon
static const char* kHybQzQuadCells=R"QZ(layout(rgba16ui,binding=0) writeonly uniform highp uimage2DArray qzQuadOut;
float qzQuadRaw(int f,int x,int y,out uint fl,out uint rawWord){
    x+=cfaShift.x;y+=cfaShift.y;
    if(x<0||y<0||x>=size.x||y>=size.y){x=reflectCfa(x,size.x);y=reflectCfa(y,size.y);}
    uvec4 geo=frameGeo[f];
    int ry=clamp(y-int(geo.y),0,int(geo.z)-1);
    uint idx=geo.x+uint(ry*size.x+x);
    uint word=frames[idx>>1];
    uint v=(idx&1u)==0u?(word&0xFFFFu):(word>>16);
    rawWord=v;
    fl=markU!=0?(v>>14):0u;v&=markU!=0?0x3FFFu:0xFFFFu;
    int phase=((y&1)<<1)|(x&1);
    return clamp((float(v)-black[phase])*inv[phase],-0.25,1.0);
}

uniform int cellFrameU; // first donor of this dispatch - 1
uniform int dcovU;      // 1: DCov holds every donor cell (merge mode bit 1) and gets the 6.1 precision; 0 / unset: not written
                        // (with the 6.1 kernel but without the per-frame covariance DCov is a 16-byte stub)
uniform ivec4 cellRowsU; // P364: x, y = the frame's cell rows [x, y) this dispatch computes (y <= x / unset: to the window's end);
                         // z, w = the rows [z, w) that get the 6.1 precision (w <= z / unset: every row). Absolute cell rows.
// P76: the 10x10 quads around the group's 8x8 cells, read once per group (sabreCov61 read each quad for 9 cells: 36 RAW sites a cell).
shared vec2 cellQuad[100];
// P364: the fine tensor (P332, s61Fine > 0, not the app's default) only in the CELL_FINE build (HybridGpu::merge swaps it in):
// without it the group holds 0.8 KB of shared memory instead of 2.7 KB and none of its code (k61cU.y > 0 is its only switch).
#ifdef CELL_FINE
// P332: the 22x22 sites around them for the fine tensor (sqrt of the gained greens, 0 at the other colours), also once per group
shared float cellSite[484];
// P364: sabreFine61U read straight from cellSite (its u[y*8+x] = cellSite[s0+y*22+x]): the 64 floats copied per cell into
// registers spilled on the Adreno 750. The same sums in the same order.
vec3 sabreFine61S(int i,int j,int s0){
    float dxx=0.0,dyy=0.0,dxy=0.0,n=0.0;
    for(int y=1;y<7;y++)for(int x=1;x<7;x++){
        if(phaseColor[(((2*j-3+y)&1)<<1)|((2*i-3+x)&1)]!=1)continue;
        float c=cellSite[s0+y*22+x],du=cellSite[s0+(y+1)*22+x+1]-c,dv=cellSite[s0+(y-1)*22+x+1]-c;
        dxx+=du*du;dyy+=dv*dv;dxy+=du*dv;n+=1.0;
    }
    return vec3(dxx,dyy,dxy)*(2.0/max(n,1.0));
}
#endif
// P364: cellU() of kHybHelpers from the four site values v, outlier bits and clip test c the tile fill read for the cell's own
// quad (cellU read the same four sites again); the same expressions in the same order (gd is not needed here).
vec3 cellUQ(int f,int i,int j,float v[4],uint hot,bool c,out bool clipped){
    float g=fParam[f].x;float g0=0.0,g1=0.0;int gi=0;vec3 r=vec3(0.0);
    if(hot!=0u)for(int p=0;p<4;p++){
        if(((hot>>uint(p))&1u)==0u)continue;
        if(phaseColor[p]==1&&((hot>>uint(3-p))&1u)==0u)v[p]=v[3-p];
        else v[p]=sampleRawM(f,2*(i>0?i-1:i+1)+(p&1),2*j+(p>>1));
    }
    for(int p=0;p<4;p++){
        int col=phaseColor[p];float x=v[p]*g;
        if(col==1){ if(gi==0)g0=x; else g1=x; gi++; }
        else if(col==0)r.r=x; else r.b=x;
    }
    r.g=0.5*(g0+g1);clipped=c;
    float e=epsU();
    return sqrt(max(r,vec3(0.0))+vec3(e));
}
void main(){
    int w2=size.x/2;
    int f=int(gl_GlobalInvocationID.z)+1+cellFrameU;
    int fs=clamp(f,0,frameCount-1); // the whole group has one frame (local size z = 1): every branch below is uniform for it
    bool cov=dcovU!=0&&k61aU.w>0.0&&fParam[fs].y>0.0&&int(fParam[fs].w)!=5;
    int bx=int(gl_WorkGroupID.x)*8-1,by=fCells[fs].x+chunkU+int(gl_WorkGroupID.y)*8-1;
    // P364: only the rows the merge reads get the precision; a group without such a row (its cells by + 1 .. by + 8) skips the tile
    bool covAll=cellRowsU.w<=cellRowsU.z;
    if(!covAll&&(by+8<cellRowsU.z||by+1>=cellRowsU.w))cov=false;
    int cx=int(gl_GlobalInvocationID.x),cy=fCells[fs].x+chunkU+int(gl_GlobalInvocationID.y);
    // a cell of the frame (f == fs then) inside the rows of this dispatch
    bool own=f<frameCount&&cx<w2&&cy<fCells[fs].x+fCells[fs].y&&(cellRowsU.y<=cellRowsU.x||cy<cellRowsU.y);
    uint idx=uint(fCells[fs].z)+uint((cy-fCells[fs].x)*w2+cx);
    if(cov){
        // P364: every invocation reads its own cell's quad into the tile (sabreQuad61's sites through rawSite, which returns the
        // same values with the flags) and takes the cell's colour from those values: 6.25 RAW sites a cell instead of 10.25. The 36
        // quads of the tile's border follow (the fill had 100 quads over 64 invocations before too).
        float g=fParam[fs].x;float v[4];uint hot=0u;bool c=false;uvec4 quadWords;
        for(int p=0;p<4;p++){
            uint fl;
            v[p]=qzQuadRaw(fs,2*cx+(p&1),2*cy+(p>>1),fl,quadWords[p]);
            if((fl&1u)!=0u)hot|=1u<<uint(p);
            else if(v[p]>=clipLevel)c=true;
        }
        float r=sqrt(max(v[0]*g,0.0)),g1=sqrt(max(v[1]*g,0.0)),g2=sqrt(max(v[2]*g,0.0)),b=sqrt(max(v[3]*g,0.0));
        cellQuad[(int(gl_LocalInvocationID.y)+1)*10+int(gl_LocalInvocationID.x)+1]=vec2(0.25*(r+g1+g2+b),abs(g1-g2));
        int t=int(gl_LocalInvocationIndex);
        if(t<36){
            int k=t<10?t:t<20?80+t:t<28?(t-19)*10:(t-27)*10+9; // rows 0 and 9, then columns 0 and 9 of rows 1..8
            cellQuad[k]=sabreQuad61(fs,bx+k%10,by+k/10);
        }
        if(own){bool cl;vec3 u=cellUQ(fs,cx,cy,v,hot,c,cl);cells[idx]=vec4(u,cl?1.0:0.0);imageStore(qzQuadOut,ivec3(cx,cy-fCells[fs].x,fs),quadWords);}
    } else if(own){bool cl;float gd;vec3 u=cellU(fs,cx,cy,cl,gd);cells[idx]=vec4(u,cl?1.0:0.0);uvec4 quadWords;for(int p=0;p<4;p++){uint fl;qzQuadRaw(fs,2*cx+(p&1),2*cy+(p>>1),fl,quadWords[p]);}imageStore(qzQuadOut,ivec3(cx,cy-fCells[fs].x,fs),quadWords);}
#ifdef CELL_FINE
    bool fine=cov&&k61cU.y>0.0;
    if(fine){
        float g=fParam[fs].x;
        for(int k=int(gl_LocalInvocationIndex);k<484;k+=64){
            int X=2*bx-1+k%22,Z=2*by-1+k/22;
            cellSite[k]=phaseColor[((Z&1)<<1)|(X&1)]==1?sqrt(max(sampleRawM(fs,X,Z)*g,0.0)):0.0;
        }
    }
#endif
    barrier();
    if(!own)return;
    if(cov&&(covAll||(cy>=cellRowsU.z&&cy<cellRowsU.w))){
        float Y[9];float qd=0.0,lum=0.0;
        int q0=(cy-by-1)*10+(cx-bx-1);
        for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
            vec2 q=cellQuad[q0+(dj+1)*10+di+1];
            float w=(di==0?0.5:0.25)*(dj==0?0.5:0.25);
            Y[(dj+1)*3+di+1]=q.x;qd+=q.y*w;lum+=q.x*w;
        }
        vec3 fineT=vec3(-1.0);
#ifdef CELL_FINE
        if(fine)fineT=sabreFine61S(cx,cy,(2*(cy-by)-2)*22+2*(cx-bx)-2); // site (2cx-3, 2cy-3) in cellSite
#endif
        vec3 P=sabreCov61Q(f,Y,qd,lum,fineT);
        dcov[idx]=uvec2(packHalf2x16(P.xy),packHalf2x16(vec2(P.z,0.0)));
    }
}
)QZ";
} // namespace scam
