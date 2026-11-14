#pragma once
#include "scam-homography.h"
#include "scam-merge.h"
#include <fstream>
#include <functional>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <limits>
#include <chrono>
#include <atomic>
#include <future>

namespace scam {
// Noise model of a frame in normalised units: variance = slope x level + offset.
struct ScamNoise { float slope, offset; };
inline int reflectCfa(int x,int size);
// Camera2 adaptation around the recovered CRE tensor/VST contract. Motion estimation
// and sub-tile image padding remain SCAMERA adaptations, not stock MEE.
inline int reflectCfa(int x,int size) {
    // Even extension retains the parity/color of each extrapolated RAW site.
    while(x<0||x>=size){if(x<0)x=-x;else x=2*(size-1)-x;}
    return x;
}
struct Burst {
    int w=0,h=0,cfa=0;
    int noiseReferenceSlot=0; // v1-v3 noise belongs to N; v4 belongs to L
    float white=0;
    ScamNoise noise{},normalNoise{}; bool hasNormalNoise=false; bool cameraNoise=false,diagnostics=false,canonicalRggb=false;
    std::array<float,4> black{};
    std::array<float,7> exposure{}; // sensor exposure products relative to N ref
    std::array<unsigned,7> iso{};
    std::array<const uint16_t*,7> raw{};
    // Also produce the whole-burst merged Bayer RAW for the DNG (like the merged
    // DNG of SCAM), instead of the single reference frame.
    bool mergedDng=false;
    int color(int x,int y) const {
        const int phase=((y&1)<<1)|(x&1);
        const int red=canonicalRggb?0:cfa;
        return phase==red?0:phase==(red^3)?2:1;
    }
    float sample(int f,int x,int y) const {return sampleRaw(raw[f],x,y);}
    float sampleRaw(const uint16_t* data,int x,int y) const {
        // CRE UnpackAndToRGGB translates the sensor CFA before the network.
        // Keep this a view: seven additional full-frame copies are unnecessary.
        if(canonicalRggb){
            x+=cfa&1;y+=cfa>>1;
            // Border reflection only off the frame (the per-sample loop was ~4% of SCAM).
            if(__builtin_expect(unsigned(x)>=unsigned(w)||unsigned(y)>=unsigned(h),0)){x=reflectCfa(x,w);y=reflectCfa(y,h);}
        }
        const float b=black[((y&1)<<1)|(x&1)];
        return std::clamp((float(data[size_t(y)*w+x])-b)/(white-b),0.0f,1.0f);
    }
};
// "fd:N": a descriptor inherited from the app (memfd, worker without root)
// instead of a path; the app sandbox cannot reopen it through /proc.
inline int openArgument(const std::string& path,int flags) {
    if(path.rfind("fd:",0)==0) {
        const int fd=std::atoi(path.c_str()+3);
        return fd>2?fcntl(fd,F_DUPFD_CLOEXEC,3):-1;
    }
    return open(path.c_str(),flags|O_CLOEXEC,0600);
}
struct Guide {
    int w=0,h=0;std::vector<float> v;
    float at(int x,int y)const{return v[size_t(y)*w+x];}
};
inline std::vector<Guide> guides(const Burst& b,int f) {
    Guide g{b.w/4,b.h/4,{}};g.v.resize(size_t(g.w)*g.h);
    for(int y=0;y<g.h;++y)for(int x=0;x<g.w;++x){
        float sum=0;int count=0;
        for(int dy=0;dy<4;++dy)for(int dx=0;dx<4;++dx)
            if(b.color(4*x+dx,4*y+dy)==1){sum+=b.sample(f,4*x+dx,4*y+dy);++count;}
        g.v[size_t(y)*g.w+x]=sum/count;
    }
    std::vector<Guide> result;result.push_back(std::move(g));
    while(result.back().w>=64 && result.back().h>=64) {
        const auto& prev=result.back();Guide next{prev.w/2,prev.h/2,{}};next.v.resize(size_t(next.w)*next.h);
        for(int y=0;y<next.h;++y)for(int x=0;x<next.w;++x)
            next.v[size_t(y)*next.w+x]=(prev.at(x*2,y*2)+prev.at(x*2+1,y*2)+prev.at(x*2,y*2+1)+prev.at(x*2+1,y*2+1))*.25f;
        result.push_back(std::move(next));
    }
    return result;
}
struct Shift { float x=0,y=0; };
inline float matchScore(const Guide& r,const Guide& d,int dx,int dy,float ev,int cx,int cy,int radius,int step) {
    double sum=0;int n=0;
    for(int y=std::max(0,cy-radius);y<std::min(r.h,cy+radius+1);y+=step)
        for(int x=std::max(0,cx-radius);x<std::min(r.w,cx+radius+1);x+=step){
            int sx=x+dx,sy=y+dy;if(sx<0||sy<0||sx>=d.w||sy>=d.h)continue;
            float a=r.at(x,y),v=d.at(sx,sy);
            if(a>.97f||v>.97f||a<.002f||v<.002f)continue;
            // Bounded relative residual avoids bright areas dominating shadows.
            float residual=std::abs(a-v/ev)/(0.02f+a);
            sum+=std::min(residual,.5f);++n;
        }
    return n>=16?float(sum/n):INFINITY;
}
inline Shift globalShift(const std::vector<Guide>& ref,const std::vector<Guide>& donor,float ev) {
    int dx=0,dy=0;
    for(int l=int(ref.size())-1;l>=0;--l) {
        if(l!=int(ref.size())-1){dx*=2;dy*=2;}
        int range=l==int(ref.size())-1?6:2,bx=dx,by=dy;
        float best=matchScore(ref[l],donor[l],dx,dy,ev,ref[l].w/2,ref[l].h/2,std::max(ref[l].w,ref[l].h),std::max(1,ref[l].w/96));
        for(int y=dy-range;y<=dy+range;++y)for(int x=dx-range;x<=dx+range;++x){
            float error=matchScore(ref[l],donor[l],x,y,ev,ref[l].w/2,ref[l].h/2,std::max(ref[l].w,ref[l].h),std::max(1,ref[l].w/96));
            if(error<best){best=error;bx=x;by=y;}
        }
        dx=bx;dy=by;
    }
    return {float(dx*4),float(dy*4)};
}
inline void restoreSensorOrigin(std::vector<float>& rgb,int w,int h,int cfa) {
    // Same direction as CRE pixelShiftf32: dst reads max(dst-offset, 0).
    // Reverse traversal permits overlap, without another full-resolution RGB.
    for(int y=h-1;y>=0;--y)for(int x=w-1;x>=0;--x){
        const size_t src=(size_t(std::max(0,y-(cfa>>1)))*w+std::max(0,x-(cfa&1)))*3;
        const size_t dst=(size_t(y)*w+x)*3;
        for(int c=0;c<3;++c)rgb[dst+c]=rgb[src+c];
    }
}
// Relative sensitivity of the 64 site classes (y&7, x&7) of a Quad / Tetra mosaic: the class mean against the mean of all
// classes of its colour, over mid-tone sites (every class sees the same scene, so only the response differs).
inline std::array<float,64> mosaicSiteGain(const Burst& b,const uint16_t* data,int block,const std::array<int,4>& color) {
    std::array<double,64> sum{},cnt{};
    for(int y=0;y<b.h;++y){
        const uint16_t* row=data+size_t(y)*b.w;
        for(int x=0;x<b.w;++x){
            const float bl=b.black[((y&1)<<1)|(x&1)];
            const float v=(float(row[x])-bl)/(b.white-bl);
            if(v<0.03f||v>0.8f)continue;
            const int k=((y&7)<<3)|(x&7);sum[k]+=v;cnt[k]+=1;
        }
    }
    auto colourOf=[&](int k){return color[((((k>>3)/block)&1)<<1)|(((k&7)/block)&1)];};
    std::array<double,3> colSum{},colCnt{};
    for(int k=0;k<64;++k){const int c=colourOf(k);colSum[c]+=sum[k];colCnt[c]+=cnt[k];}
    std::array<float,64> gain;gain.fill(1.f);
    for(int k=0;k<64;++k){
        const int c=colourOf(k);
        if(cnt[k]<1000||colCnt[c]<1000)continue;
        const double mean=colSum[c]/colCnt[c],own=sum[k]/cnt[k];
        if(own>1e-6)gain[k]=float(std::clamp(mean/own,0.75,1.33));
    }
    return gain;
}
using ScamAlignment = std::function<std::array<BackwardHomography,7>(Burst&)>;
// P31 (W1.2): optional companion of the worker's ScamAlignment (the CRE). Told every frame of a burst before its groups are
// aligned (base: the groups' Burst with slots 1..6 still unset; donors: raw and exposure of each frame to align, in order), it
// builds their guides ahead; called with base == nullptr after the last group (or on an exception) to drop the unused ones.
using ScamAlignmentPrefetch = std::function<void(const Burst* base,const std::vector<std::pair<const uint16_t*,float>>& donors)>;
inline ScamAlignmentPrefetch& scamAlignmentPrefetch(){static ScamAlignmentPrefetch hook;return hook;}
} // namespace scam
