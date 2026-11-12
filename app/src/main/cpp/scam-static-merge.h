#pragma once
// P624 «Статика» (owner, 2026-11-11: «третий вариант склейки "Статика". Он будет чисто для статики и мы можем для него взять
// абсолютно всё что можно из статьи, кроме нейрошумодава»): the static merge route of the SCAM Hybrid worker, tune staticMerge 1.
// Lecouat, Ponce, Mairal, «Lucas-Kanade Reloaded: End-to-End Super-Resolution from Raw Image Bursts» (ICCV 2021): the explicit
// observation model on the RAW mosaic (scam-static-shaders.h) solved by half-quadratic splitting -
//   z-step: Jacobi-preconditioned gradient steps on 1/2 sum_k |W_k^1/2 (U_k z - y_k)|^2 + mu/2 |z - x|^2 + R(z)
//           (W_k: our per-frame noise model sigma^2 = S y + O at the prediction, clipped samples out, the robust weight);
//   p-step: one Gauss-Newton step per frame and tile against z (a translation field over the GPU homography);
//   x-step: the anchor x follows z every second iteration, mu grows geometrically - the paper's learned denoiser is replaced by
//           R(z) inside the z-step: a Tikhonov smoothness of the colour differences (the R / B null space of a 2x Bayer solve)
//           and a Huber-TV of the luma, both x the frame noise variance (P624 Phase 1: the plug-and-play sliding-DCT prior of
//           RawLens measured worse on our bursts).
// Not taken from the paper: the learned denoiser, the learned step sizes (fixed: c = 1.8 with the Jacobi preconditioner, mu from
// 8 sigma to sigma / sqrt(K)), the 4x output (the photo is 1x, 1.5x or 2x). Added: the outlier test (a moving car does not ghost)
// and the static-scene check (a moving frame is left out).
// P627 (owner, 2026-11-11: «Статика» takes Quad, Tetra and DCG bursts and never hands a shot to another mode): the observation
// model's CFA is the sensor's colour blocks (b x b sites of one colour, the period 2b: stChannel), each site's response measured
// and divided out (the native merge's site classes), the alignment on the binned plain Bayer, a colour prior from the base frame's
// binned colour for the start image; a DCG burst's two noise zones in the data weights. Whatever the burst does, the photo is this
// route's: moving frames / samples are left out down to the base frame alone (still the solve's reconstruction), a GPU backend
// that fails gives way to the next (Vulkan -> GLES -> the CPU transcription, scam-static-cpu.h), a refused buffer to smaller strips.
// Third-party reference: RawLens 2.2.45 (GPL-3.0-or-later, github.com/matthew777777/RawLens, commit b93544d) tools/lkr-pipeline
// for ideas and constants (3 z-steps per iteration, eta = c / (L + mu) with c < 2 and L a Schur bound - here per pixel, the
// geometric sigma schedule from ~10 sigma, the Wronski minimum filter of rejections, clipped samples out of the data term);
// no RawLens code is copied (THIRD_PARTY_NOTICES.md).
#include "scam-static-gpu.h"
#include "scam-static-cpu.h"

namespace scam {


// Vulkan devices / drivers the static merge keeps on GLES (substrings of the Vulkan device name; SCAM_STATIC_VK_DENY adds one).
// Empty: no device has been seen failing yet.
inline bool hybridStaticVkDenied(const std::string& device,std::string& why){
    static const char* deny[]={nullptr};
    for(const char* d:deny)if(d&&device.find(d)!=std::string::npos){why=std::string("denylist: ")+d;return true;}
    if(const char* e=std::getenv("SCAM_STATIC_VK_DENY"))if(*e&&device.find(e)!=std::string::npos){why=std::string("denylist (env): ")+e;return true;}
    return false;
}

// P627c: the worker's alarm for a static merge of w x h (seconds): 840 up to 12 MP on a GPU, in proportion above, x4 on the CPU
// transcription, at most 3540 (ScamHybridBurst.workerTimeoutSeconds is this + 60)
inline unsigned hybridStaticAlarmSeconds(int w,int h,bool cpu){
    const double mp=double(w)*double(h)/12e6;
    return unsigned(std::lround(std::min(3540.0,std::max(840.0,840.0*mp*(cpu?4.0:1.0)))));
}

// P627: the colour block the static merge models (1 Bayer, 2 Quad, 4 Tetra): the app's (header word 14), else measured
inline int hybridStaticBlock(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report){
    if(input.mosaic==1||input.mosaic==2||input.mosaic==4)return input.mosaic;
    const int b=hybridMosaicBlock(input,tune,report);
    return b==2||b==4?b:1;
}

namespace staticmerge {
// 3x3 helpers (row-major, h33 explicit)
using M3=std::array<double,9>;
inline M3 m3mul(const M3& a,const M3& b){M3 c{};for(int i=0;i<3;++i)for(int j=0;j<3;++j){double s=0;for(int k=0;k<3;++k)s+=a[size_t(3*i+k)]*b[size_t(3*k+j)];c[size_t(3*i+j)]=s;}return c;}
inline bool m3inv(const M3& m,M3& r){
    const double a=m[0],b=m[1],c=m[2],d=m[3],e=m[4],f=m[5],g=m[6],h=m[7],i=m[8];
    const double A=e*i-f*h,B=-(d*i-f*g),C=d*h-e*g,det=a*A+b*B+c*C;
    if(std::abs(det)<1e-12)return false;
    r={A/det,-(b*i-c*h)/det,(b*f-c*e)/det,B/det,(a*i-c*g)/det,-(a*f-c*d)/det,C/det,-(a*h-b*g)/det,(a*e-b*d)/det};
    return true;
}
inline std::array<double,2> m3apply(const M3& m,double x,double y){const double d=m[6]*x+m[7]*y+m[8];return {(m[0]*x+m[1]*y+m[2])/d,(m[3]*x+m[4]*y+m[5])/d};}
} // namespace staticmerge

// The alignment's fallback when the GPU homography of a frame fails (no coarse match, too few corners): a forward-additive
// Lucas-Kanade affine (P624 Phase 1's prototype) on the 2 x 2 cell grey, coarse to fine (from <= 64 px wide to <= 1024 px; the
// field's Gauss-Newton refines the rest), parameterised about the image centre. Returns base -> frame (centres at + 0.5, RAW px).
using staticmerge::M3;using staticmerge::m3inv;
struct StGrey { int w=0,h=0;float cell=2;std::vector<float> v; };
// (P627: the cell is one colour period, 2b x 2b sites: a Quad / Tetra cell holds every colour too)
inline StGrey stGreyCells(const HybridInput& in,const uint16_t* raw,float gain,int b=1){
    const int c=2*b;
    StGrey g;g.w=in.w/c;g.h=in.h/c;g.cell=float(c);g.v.resize(size_t(g.w)*g.h);
    const float inv=1.f/float(c*c);
    for(int y=0;y<g.h;++y)for(int x=0;x<g.w;++x){
        float s=0;for(int p=0;p<c*c;++p){const int X=c*x+(p%c),Y=c*y+(p/c);const int q=((Y&1)<<1)|(X&1);
            s+=(float(raw[size_t(Y)*in.w+X])-in.black[size_t(q)])/std::max(in.white-in.black[size_t(q)],1.f);}
        g.v[size_t(y)*g.w+x]=inv*s*gain;
    }
    return g;
}
inline StGrey stHalf(const StGrey& a){
    StGrey g;g.w=a.w/2;g.h=a.h/2;g.cell=a.cell*2;g.v.resize(size_t(g.w)*g.h);
    for(int y=0;y<g.h;++y)for(int x=0;x<g.w;++x){const float* r=a.v.data()+size_t(2*y)*a.w+2*x;g.v[size_t(y)*g.w+x]=0.25f*(r[0]+r[1]+r[a.w]+r[a.w+1]);}
    return g;
}
inline bool stLkAffine(const std::vector<StGrey>& ref,const std::vector<StGrey>& mov,M3& out){
    // p: frame u -> base A (u - c) + t' (the prototype's convention), solved per level from the coarsest
    double p[6]={1,0,0,0,1,0};bool any=false;
    for(int lv=int(ref.size())-1;lv>=0;--lv){
        const StGrey& R=ref[size_t(lv)];const StGrey& Mv=mov[size_t(lv)];
        if(R.w>1024)continue; // (P627c: the 2048-cell level cost 32 s of CPU on 12 MP for 15 frames; the field's cap is 6 px after it instead)
        const double c=R.cell,cx=R.w*c/2,cy=R.h*c/2;
        auto at=[&](const StGrey& g,double x,double y,double& v,double& gx,double& gy){ // x, y in grid px (centres on integers)
            if(x<1||y<1||x>g.w-2||y>g.h-2)return false;
            const int x0=int(x),y0=int(y);const double ax=x-x0,ay=y-y0;
            auto V=[&](int xx,int yy){return double(g.v[size_t(yy)*g.w+xx]);};
            v=(V(x0,y0)*(1-ax)+V(x0+1,y0)*ax)*(1-ay)+(V(x0,y0+1)*(1-ax)+V(x0+1,y0+1)*ax)*ay;
            gx=((V(x0+1,y0)-V(x0,y0))*(1-ay)+(V(x0+1,y0+1)-V(x0,y0+1))*ay)/c;
            gy=((V(x0,y0+1)-V(x0,y0))*(1-ax)+(V(x0+1,y0+1)-V(x0+1,y0))*ax)/c;
            return true;
        };
        for(int it=0;it<30;++it){
            double A[6][7]={};long n=0;
            for(int y=0;y<Mv.h;++y)for(int x=0;x<Mv.w;++x){
                const double ux=(x+0.5)*c-cx,uy=(y+0.5)*c-cy;
                const double bx=p[0]*ux+p[1]*uy+p[2]+cx,by=p[3]*ux+p[4]*uy+p[5]+cy; // base RAW px
                double v,gx,gy;if(!at(R,bx/c-0.5,by/c-0.5,v,gx,gy))continue;
                const double e=Mv.v[size_t(y)*Mv.w+x]-v,J[6]={gx*ux,gx*uy,gx,gy*ux,gy*uy,gy};
                for(int i=0;i<6;++i){for(int j=i;j<6;++j)A[i][j]+=J[i]*J[j];A[i][6]+=J[i]*e;}
                ++n;
            }
            if(n<64)break;
            for(int i=0;i<6;++i)for(int j=0;j<i;++j)A[i][j]=A[j][i];
            double d[6];for(int i=0;i<6;++i)d[i]=A[i][i]>0?1/std::sqrt(A[i][i]):1;
            for(int i=0;i<6;++i){for(int j=0;j<6;++j)A[i][j]*=d[i]*d[j];A[i][6]*=d[i];A[i][i]+=1e-6;}
            for(int col=0;col<6;++col){int pv=col;for(int r=col+1;r<6;++r)if(std::abs(A[r][col])>std::abs(A[pv][col]))pv=r;
                if(std::abs(A[pv][col])<1e-12)return false;
                if(pv!=col)for(int k=0;k<7;++k)std::swap(A[col][k],A[pv][k]);
                for(int r=0;r<6;++r)if(r!=col){const double f=A[r][col]/A[col][col];for(int k=col;k<7;++k)A[r][k]-=f*A[col][k];}}
            double dp[6];for(int i=0;i<6;++i)dp[i]=A[i][6]/A[i][i]*d[i];
            for(int i=0;i<6;++i)p[i]+=dp[i];
            any=true;
            const double move=std::max(std::abs(dp[2])+std::abs(dp[0])*cx+std::abs(dp[1])*cy,std::abs(dp[5])+std::abs(dp[3])*cx+std::abs(dp[4])*cy);
            if(move<1e-3)break;
        }
    }
    if(!any)return false;
    // frame -> base: b = A u + (t' + c - A c); base -> frame: its inverse
    const double cx=ref[0].w*ref[0].cell/2,cy=ref[0].h*ref[0].cell/2;
    const M3 fb{p[0],p[1],p[2]+cx-(p[0]*cx+p[1]*cy),p[3],p[4],p[5]+cy-(p[3]*cx+p[4]*cy),0,0,1};
    return m3inv(fb,out);
}

// Moving blocks of a frame against the base on the 1/8 thumbnails (fastThumb: the green mean of 8 x 8 RAW px): the base warped
// by the frame's homography, 4 x 4 thumbnail blocks (32 x 32 RAW px), the block mean difference against the noise of that mean
// (both frames, our noise model) plus a misregistration term; a block above 4 sigma moves. Returns the fraction of blocks.
// P627: n = the sites a thumbnail pixel averages (8 greens of fastThumb; 64 of a colour-block thumbnail, stThumbBlocks)
inline float hybridStaticMoving(const FastThumb& b,const FastThumb& f,const staticmerge::M3& Hinv,float slope,float offset,float n8=8.f){
    const int bw=f.w/4,bh=f.h/4;
    if(bw<1||bh<1)return 0.f;
    const float gain=f.mean>1e-6f&&b.mean>1e-6f?b.mean/f.mean:1.f; // AE drift between frames
    long moving=0,total=0;
    for(int by=0;by<bh;++by)for(int bx=0;bx<bw;++bx){
        double sd=0,sv=0,sg=0;int n=0;
        for(int j=0;j<4;++j)for(int i=0;i<4;++i){
            const int x=4*bx+i,y=4*by+j;
            const auto p=staticmerge::m3apply(Hinv,8.0*(x+0.5),8.0*(y+0.5)); // frame thumb centre -> base RAW
            const double tx=p[0]/8.0-0.5,ty=p[1]/8.0-0.5;
            if(tx<1||ty<1||tx>b.w-2||ty>b.h-2)continue;
            const int x0=int(tx),y0=int(ty);const double ax=tx-x0,ay=ty-y0;
            auto at=[&](int xx,int yy){return double(b.v[size_t(yy)*b.w+xx]);};
            const double v=(at(x0,y0)*(1-ax)+at(x0+1,y0)*ax)*(1-ay)+(at(x0,y0+1)*(1-ax)+at(x0+1,y0+1)*ax)*ay;
            const double fv=f.v[size_t(y)*f.w+x]*gain;
            sd+=fv-v;sv+=2.0*(slope*std::max(v,0.0)+offset)/n8;
            const double gx=0.5*(at(x0+1,y0)-at(x0-1,y0)),gy=0.5*(at(x0,y0+1)-at(x0,y0-1));sg+=gx*gx+gy*gy;
            ++n;
        }
        if(n<8)continue;
        const double m=sd/n,vm=sv/(double(n)*n)+0.25*0.25*sg/n+1e-12; // 0.25 thumbnail px = 2 RAW px of misregistration
        ++total;if(m*m/vm>16.0)++moving;
    }
    return total?float(moving)/float(total):1.f; // P627: no block of the frame overlaps the base (a diverged alignment): moving
}

// P627: the 1/8 thumbnail of a colour-block burst: the mean of every site of each 8 x 8 block (whole colour periods of Quad and
// Tetra: a grey that does not change with the colour under a shift, as fastThumb's two green rows would), in base units
inline FastThumb stThumbBlocks(const HybridInput& in,const uint16_t* raw,float gain){
    FastThumb t;t.w=in.w/8;t.h=in.h/8;t.v.assign(size_t(t.w)*t.h,0.f);
    const float bl=0.25f*(in.black[0]+in.black[1]+in.black[2]+in.black[3]),k=gain/std::max(in.white-bl,1.f);
    double sum=0;
    for(int by=0;by<t.h;++by)for(int bx=0;bx<t.w;++bx){
        long a=0;for(int y=0;y<8;++y){const uint16_t* r=raw+size_t(by*8+y)*in.w+size_t(bx*8);for(int x=0;x<8;++x)a+=r[x];}
        const float v=(float(a)/64.f-bl)*k;t.v[size_t(by)*t.w+bx]=v;sum+=v;
    }
    t.mean=float(sum/double(std::max<size_t>(1,t.v.size())));
    return t;
}

namespace staticmerge {
// One solve's input (CPU side, the backend-independent part): the frames used (base first), their homographies, the noise.
struct StJob {
    int W=0,H=0,cfa=0;float white=1023;std::array<float,4> black{};std::array<float,4> siteGain{{1,1,1,1}}; // per 2x2 position (green balance)
    std::vector<const uint16_t*> raw;std::vector<M3> Hb,Hinv;std::vector<float> slope,offset,expo; // expo: the divisor to base units
    bool hr=false;   // the HR solve as the result (1.5x / 2x), else its 2 x 2 box
    float maxShift=3.f; // P627c: the field's cap (6 when a frame's homography is the CPU affine)
    int s=2,T=8,Z=3,tile=64,stripMB=192;float chroma=0.3f,luma=1,huber=2,robust=3,wbR=1,wbB=1,chromaFloor=2,lumaFloor=1,flatK=0,edgeK=0;
    bool lanczos=false; // P627f: the 1x photo through a Lanczos-2 low-pass (output mode 3), else the 2 x 2 box
    float priorFrames=0; // P627e: the priors' strength as of at least this many frames (0 = as of the frames taken)
    // P627: the colour block (log2), the frames' rows as uploaded (the sites' response divided out; empty = the RAW as it is),
    // the colour prior of a colour-block CFA (R - G, B - G per block of the base, cdW x cdH), the DCG zones per frame (0 = one)
    int bs=0;
    std::function<void(int k,int y0,int rows,uint16_t* dst)> rawRows;
    std::vector<float> cd;int cdW=0,cdH=0;
    std::vector<float> dcgKnee,dcgLcg;
};
struct StResult {
    std::vector<float> out;int ow=0,oh=0,ntx=0,nty=0,strips=0,rowsPerStrip=0;
    std::vector<uint32_t> counters;std::vector<float> fld;
    double uploadMs=0,gpuMs=0,initMs=0;std::string backend;std::vector<std::pair<std::string,double>> passMs;
};
inline void stJobTuning(StJob& j,const HybridTuning& t){
    j.s=std::clamp(t.staticScale,1,2);j.T=std::clamp(t.staticIters,1,32);j.Z=std::clamp(t.staticZSteps,1,8);j.tile=std::clamp(t.staticTile,16,512);
    j.stripMB=std::max(32,t.staticStripMB);
    // P627c: within a quarter of the memory available (the GPU's buffers are the phone's RAM)
    if(const uint64_t avail=hybridMemAvailable())j.stripMB=std::max(32,std::min<int>(j.stripMB,int(avail>>22)));
    j.lanczos=t.staticLanczos!=0;j.flatK=t.staticFlatK;j.edgeK=t.staticEdgeK;j.chroma=t.staticChroma;j.chromaFloor=std::max(0.f,t.staticChromaFloor);j.lumaFloor=std::max(0.f,t.staticLumaFloor);j.luma=t.staticLuma;j.huber=t.staticHuber;j.robust=t.staticRobust;
    if(j.s!=2)j.hr=false;
}

// The solve on one backend: row strips (their margins solved and dropped), the HQS iterations recorded into one submission per
// strip, the strip guard on its rows (P613: a stamp the output pass did not overwrite fails the solve).
inline StResult stSolve(StBackend& gpu,const StJob& job){
    using Clock=std::chrono::steady_clock;
    auto ms=[](Clock::time_point a,Clock::time_point b){return std::chrono::duration<double,std::milli>(b-a).count();};
    StResult res;res.backend=gpu.name();
    const int W=job.W,H=job.H,K=int(job.raw.size()),s=job.s,WH=s*W,tile=job.tile;
    const int ntx=(W+tile-1)/tile,nty=(H+tile-1)/tile;
    const bool hr=job.hr;
    const int OW=hr?2*W:W,OH=hr?2*H:H;
    const int margin=16;
    // P627c: a frame's window starts at its own first row (below), so only the SPREAD of the rows a strip lands on matters: the
    // rotation / perspective across the width plus the scale over the strip's height - not the frame's shift (P624 counted
    // 2 x |dy|: the church burst's 12 MP strips of 36 rows read 120-row windows, 3x the work of every per-sample pass)
    double spread=0;
    for(int k=0;k<K;++k)for(double y:{0.0,0.5*H,double(H)}){
        const auto a0=m3apply(job.Hb[size_t(k)],0.0,y),a1=m3apply(job.Hb[size_t(k)],double(W),y);
        const auto b0=m3apply(job.Hb[size_t(k)],0.0,std::min(double(H),y+64.0));
        spread=std::max(spread,std::abs(a1[1]-a0[1])+std::max(0.0,std::abs(b0[1]-a0[1])-64.0)*8.0); // (x8: up to 512-row strips)
    }
    // strip rows: every buffer within the device's storage block and the strip within stripMB
    const size_t budget=size_t(job.stripMB)<<20;
    const int extra=2*margin+int(std::ceil(spread))+2*int(std::ceil(job.maxShift))+12; // the field's cap, the kernel, the window's own +-3
    auto bytesFor=[&](int R,size_t& hrBuf,size_t& lrBuf){
        const size_t rowsHr=size_t(s)*size_t(R+2*margin),rows=size_t(R+extra);
        hrBuf=size_t(WH)*rowsHr*16;lrBuf=size_t(K)*rows*size_t(W)*4;
        return 3*hrBuf+6*lrBuf+lrBuf/2;
    };
    int R=std::min(H,512)&~1;
    for(;;){size_t a,b;const size_t tot=bytesFor(R,a,b);
        if(R<=16||(tot<=budget&&a<=gpu.maxBuffer()&&b<=gpu.maxBuffer()))break;
        R=std::max(16,(R*3/4)&~1);}
    const int rowsWin=std::min(H,R+extra);
    const size_t hrBytes=size_t(WH)*size_t(s)*size_t(R+2*margin)*16,lrFloats=size_t(K)*size_t(rowsWin)*size_t(W);
    const int strips=(H+R-1)/R;
    gpu.alloc(bFrames,size_t(32*K+16)*4);gpu.alloc(bRaw,lrFloats*2+16);
    for(int b:{bZ,bZ2,bX})gpu.alloc(b,hrBytes);
    for(int b:{bR,bWb,bT,bW})gpu.alloc(b,lrFloats*4);
    gpu.alloc(bRW,lrFloats*8);
    const size_t fldBytes=size_t(K)*size_t(ntx)*size_t(nty)*8;
    gpu.alloc(bFld,fldBytes);gpu.alloc(bFld2,fldBytes);
    gpu.alloc(bCnt,size_t(3*K)*4);
    const size_t outFloats=size_t(hr?WH:W)*size_t(hr?s*R:R)*3;
    gpu.alloc(bOut,outFloats*4);
    // P627: the colour prior's block rows of a strip (its rows with 2 blocks of margin)
    const int cdb=1<<job.bs,cdRows=job.cd.empty()?0:std::min(job.cdH,(R+2*margin)/cdb+6);
    gpu.alloc(bCd,job.cd.empty()?16:size_t(job.cdW)*size_t(cdRows)*8);
    std::vector<uint16_t> rowsTmp(job.rawRows?size_t(rowsWin)*size_t(W):0);
    gpu.zero(bFld,fldBytes);
    // the schedule: mu from (1 / 8)^2 to K (the sigma of the iterate from 8 sigma to sigma / sqrt(K), sigma = the base frame's
    // noise at mid grey), the regularisers x the noise variance (relative to 0.0208^2, the Phase 1 tuning's ISO 400) x the
    // expected coverage of a green HR pixel K / (2 s^2)
    // (P627 DCG: the offset of the zone mid grey sits in)
    const float off18=job.offset[0]+(job.dcgLcg.size()&&job.dcgLcg[0]>0.f?(job.dcgLcg[0]-job.offset[0])*hybridDcgShare(0.18f,job.dcgKnee[0]):0.f);
    const float sig2=std::max(job.slope[0]*0.18f+off18,1e-10f),sigRef=std::sqrt(sig2);
    // P627e: a colour-block burst with few static frames (the base alone, 5 of 16) kept the priors of a K-frame solve - the noise of
    // K frames left unaveraged, its ringing beside every edge (stripes / b-px teeth); the priors as of priorFrames frames at least
    const float nf=sig2/(0.0208f*0.0208f),Dg=std::max(float(K),job.priorFrames)/(2.f*float(s*s));
    const int T=job.T,Z=job.Z;
    std::vector<float> mu(static_cast<size_t>(T));
    for(int t=0;t<T;++t){const double a0=8.0,a1=1.0/std::sqrt(double(K));const double sg=T>1?a0*std::pow(a1/a0,double(t)/(T-1)):a1;mu[size_t(t)]=float(1.0/(sg*sg));}
    StParams P;P.K=K;P.W=W;P.H=H;P.s=s;P.WH=WH;P.rows=rowsWin;P.cfa=job.cfa;P.tile=tile;P.ntx=ntx;P.nty=nty;P.spread=2;
    // P624 Phase 1 (synthetic bursts, three scenes x ISO 100 / 400 / 1600): the luma term x 1 up to the ISO 400 noise, x 0.3
    // from 4x its variance (ISO 1600) - log-linear between (the noise variance already scales both terms)
    const float lumaLut=1.f-0.7f*std::clamp(std::log2(std::max(nf,1e-6f))/2.f,0.f,1.f);
    // P627b (owner, 2026-11-11: the colour checker on quad_bm / tetra_tc): on a colour-block CFA the colour is sampled in b x b
    // clumps, its null space a period-2b pattern the data leave free at ANY noise; the colour prior's strength x the noise variance
    // fell to ~0 at ISO 50 - 100 (nf 0.02 - 0.1) and the solve drew the blocks as a colour / luma checker. Its noise factor is
    // held at chromaFloor or above (Quad / Tetra only; Bayer unchanged)
    // (P627c: per CFA - hybridStaticMerge sets the Quad / Tetra or the Bayer floors; 0 = the prior as P624)
    const float nfC=std::max(nf,job.chromaFloor);
    const float nfL=std::max(nf,job.lumaFloor); // the luma prior's floor (staticLumaFloor / staticBayerLumaFloor)
    // P627d: with flatK > 0 the floors (lcF / llF) act where the estimate is flat (the gather's own weight), lc / ll elsewhere
    const float lcF=job.chroma*nfC*Dg,llF=job.luma*lumaLut*nfL*Dg;
    P.stepc=1.8f;P.lc=job.flatK>0.f?job.chroma*nf*Dg:lcF;P.ll=job.flatK>0.f?job.luma*lumaLut*nf*Dg:llF;P.delta=std::max(job.huber*sigRef/std::sqrt(float(K)),1e-6f);
    P.robK=job.robust;P.robTau=0.15f;P.clipLvl=0.98f;P.sig2=sig2;P.lam=0.1f;P.lamFloor=1e-4f;P.maxShift=job.maxShift;P.wbR=job.wbR;P.wbB=job.wbB;P.bs=job.bs;
    std::vector<float> table(size_t(32*K+16),0.f);
    table[size_t(32*K+12)]=lcF;table[size_t(32*K+13)]=llF;table[size_t(32*K+14)]=std::max(0.f,job.flatK);table[size_t(32*K+15)]=job.flatK>0.f?std::max(0.f,job.edgeK):0.f;
    for(int k=0;k<K;++k){
        for(int i=0;i<9;++i){table[size_t(32*k+i)]=float(job.Hb[size_t(k)][size_t(i)]);table[size_t(32*k+9+i)]=float(job.Hinv[size_t(k)][size_t(i)]);}
        table[size_t(32*k+18)]=job.slope[size_t(k)];table[size_t(32*k+19)]=job.offset[size_t(k)];
        table[size_t(32*k+20)]=job.expo.size()>size_t(k)?job.expo[size_t(k)]:1.f;table[size_t(32*k+22)]=1.f;
        if(job.dcgLcg.size()>size_t(k)&&job.dcgLcg[size_t(k)]>0.f&&job.dcgKnee[size_t(k)]>0.f){
            table[size_t(32*k+23)]=job.dcgKnee[size_t(k)];table[size_t(32*k+24)]=job.dcgLcg[size_t(k)];}
    }
    for(int i=0;i<4;++i)table[size_t(32*K+i)]=job.black[size_t(i)];
    table[size_t(32*K+4)]=job.white;
    for(int i=0;i<4;++i)table[size_t(32*K+5+i)]=job.siteGain[size_t(i)];
    res.out.assign(size_t(OW)*size_t(OH)*3,0.f);res.ow=OW;res.oh=OH;res.ntx=ntx;res.nty=nty;res.strips=strips;res.rowsPerStrip=R;
    res.counters.assign(size_t(3*K),0);
    std::vector<uint32_t> cnt(size_t(3*K));
    const uint32_t kSentinel=0x7FA5C0DEu; // P613: a NaN no pass writes
    auto lr=[&](int prog,int mode,std::array<int,8> bind){StDispatch d;d.prog=prog;d.p=P;d.p.mode=mode;d.bind=bind;
        d.gx=uint32_t((W+15)/16);d.gy=uint32_t((rowsWin+15)/16);d.gz=uint32_t(K);return d;};
    for(int si=0;si<strips;++si){
        const int y0=si*R,y1=std::min(H,y0+R);
        const int r0=std::max(0,y0-margin)&~1,r1=std::min(H,y1+margin);
        P.r0=r0;P.RH=s*(r1-r0);
        const auto tu=Clock::now();
        for(int k=0;k<K;++k){ // the frame's window: the rows the strip's base rows land on (+-3)
            double lo=1e30;
            for(double x:{0.0,double(W)})for(double y:{double(r0),double(r1)}){const auto p=m3apply(job.Hb[size_t(k)],x,y);lo=std::min(lo,p[1]);}
            const int first=std::clamp(int(std::floor(lo))-int(std::ceil(job.maxShift)),0,std::max(0,H-rowsWin));
            table[size_t(32*k+21)]=float(first);
            const int n=std::min(rowsWin,H-first);
            if(job.rawRows){job.rawRows(k,first,n,rowsTmp.data());gpu.upload(bRaw,size_t(k)*size_t(rowsWin)*size_t(W)*2,rowsTmp.data(),size_t(n)*size_t(W)*2);}
            else gpu.upload(bRaw,size_t(k)*size_t(rowsWin)*size_t(W)*2,job.raw[size_t(k)]+size_t(first)*size_t(W),size_t(n)*size_t(W)*2);
        }
        if(cdRows>0){ // P627: the colour prior's rows of this strip
            const int c0=std::clamp(r0/cdb-2,0,std::max(0,job.cdH-cdRows)),cn=std::min(cdRows,job.cdH-c0);
            table[size_t(32*K+9)]=float(job.cdW);table[size_t(32*K+10)]=float(c0);table[size_t(32*K+11)]=float(cn);
            gpu.upload(bCd,0,job.cd.data()+size_t(c0)*size_t(job.cdW)*2,size_t(cn)*size_t(job.cdW)*8);
        }
        gpu.upload(bFrames,0,table.data(),table.size()*4);
        gpu.zero(bCnt,size_t(3*K)*4);
        const int ow=hr?WH:W,orows=hr?s*(y1-y0):(y1-y0);
        std::vector<size_t> stamps;
        for(int y=0;y<orows;++y){for(int x=0;x<ow;x+=64)stamps.push_back((size_t(y)*ow+x)*3+1);stamps.push_back((size_t(y)*ow+ow-1)*3+1);}
        std::sort(stamps.begin(),stamps.end());
        gpu.stamp(bOut,stamps,kSentinel);
        res.uploadMs+=ms(tu,Clock::now());
        const auto tg=Clock::now();
        gpu.begin();
        const uint32_t hx=uint32_t((WH+15)/16),hy=uint32_t((P.RH+15)/16);
        auto hrd=[&](int prog,int mode,std::array<int,8> bind){StDispatch d;d.prog=prog;d.p=P;d.p.mode=mode;d.bind=bind;d.gx=hx;d.gy=hy;return d;};
        gpu.dispatch(hrd(spInit,cdRows>0?1:0,{{-1,bFrames,bRaw,bZ,bX,cdRows>0?int(bCd):-1,bFld,bZ}}));
        int zc=bZ,zn=bZ2;
        for(int t=0;t<T;++t){
            P.mu=mu[size_t(t)];
            gpu.dispatch(lr(spResid,0,{{-1,bFrames,bRaw,zc,bR,bWb,bFld,-1}}));
            StDispatch rb=lr(spRobust,0,{{-1,bFrames,bRaw,bR,bWb,bT,-1,-1}});
            StDispatch mn=lr(spMin,t==T-1?1:0,{{-1,bFrames,bT,bWb,bW,bCnt,bR,-1}});
            if(t<2){rb.p.robK=0;mn.p.robK=0;} // the first two iterations: z is the start image / the field is still settling
            gpu.dispatch(rb);
            gpu.dispatch(mn);
            for(int zs=0;zs<Z;++zs){
                gpu.dispatch(lr(spResid,1,{{-1,bFrames,bRaw,zc,bR,bW,bFld,bRW}}));
                gpu.dispatch(hrd(spGather,0,{{-1,bFrames,zc,zn,bX,bRW,bFld,-1}}));
                std::swap(zc,zn);
            }
            if(K>1&&(t<4||(t-4)%2==1)){ // the p-step: every iteration of the first four, then every second
                // on the base weights (noise model, clipping): the robust weights would drop a misregistered frame's textured
                // samples - exactly the ones that measure its misregistration
                StDispatch d;d.prog=spField;d.p=P;d.bind={{-1,bFrames,bRaw,zc,bWb,-1,bFld,bFld2}};
                d.gx=uint32_t(ntx);d.gy=uint32_t(nty);d.gz=uint32_t(K-1);
                gpu.dispatch(d);
                gpu.copy(bFld2,bFld,fldBytes);
            }
            if(t%2==1||t==T-1)gpu.copy(zc,bX,size_t(WH)*size_t(P.RH)*16); // the anchor follows the estimate
        }
        {
            StDispatch d=hrd(spOutput,hr?2:job.lanczos?3:1,{{-1,bFrames,zc,bOut,-1,-1,-1,-1}});
            d.p.rows=s*(y0-r0);d.p.outRows=orows;
            d.gx=uint32_t((ow+15)/16);d.gy=uint32_t((orows+15)/16);
            gpu.dispatch(d);
        }
        gpu.submitWait();
        res.gpuMs+=ms(tg,Clock::now());
        const size_t n=size_t(ow)*size_t(orows)*3;
        float* dst=res.out.data()+size_t(hr?s*y0:y0)*size_t(OW)*3;
        gpu.read(bOut,0,n*4,dst);
        for(size_t w:stamps){uint32_t v;std::memcpy(&v,dst+w,4);
            if(v==kSentinel)throw std::runtime_error("HYBRID STATIC STRIP GUARD: strip "+std::to_string(si)+" rows "+std::to_string(y0)+".."
                +std::to_string(y1)+" not written (MemAvailable "+hybridMB(hybridMemAvailable())+" MB)");}
        gpu.read(bCnt,0,size_t(3*K)*4,cnt.data());
        for(size_t i=0;i<cnt.size();++i)res.counters[i]+=cnt[i];
        scam_progress::step(1);
    }
    res.fld.assign(size_t(K)*size_t(ntx)*size_t(nty)*2,0.f);
    gpu.read(bFld,0,res.fld.size()*4,res.fld.data());
    return res;
}

// ---- the backend choice (owner, 2026-11-11: «чтобы это работало на большинстве смартфонов и при этом работало быстро»):
// staticVulkan -1 (auto): on the first static shot of this app version (the shaders' hash) and this driver, a self-test solves a
// small synthetic burst with a known answer on both backends: Vulkan is kept when its result matches GLES (max |difference| <=
// kStSelfTestTol) and the truth, and it is the faster of the two; the verdict is stored in the program cache directory
// (static-backend.txt). Vulkan failing anywhere (init, the self-test, a pass mid-shot) marks it bad for this version / driver.
constexpr float kStSelfTestTol=2e-3f;
inline std::string stBackendFile(){const std::string& d=hybridProgramCacheDir();return d.empty()?"":d+"/static-backend.txt";}
inline std::string stVersionKey(const std::string& vkDevice){
    std::string all;for(int i=0;i<spCount;++i)all+=stSource(i,false);
    char t[40];std::snprintf(t,sizeof(t),"%016llx",(unsigned long long)stHash(all+"|"+vkDevice));return t;
}
inline std::string& stCachedVerdict(){static std::string v;return v;} // this process's verdict (a host replay without a cache dir)
inline void stWriteVerdict(const std::string& line){
    stCachedVerdict()=line;
    const std::string f=stBackendFile();if(!f.empty())std::ofstream(f)<<line<<"\n";
}
inline std::string stReadVerdict(){
    const std::string f=stBackendFile();
    if(!f.empty()){std::ifstream in(f);std::string l;if(std::getline(in,l))return l;}
    return stCachedVerdict();
}
inline std::string& stVkKey(){static std::string k;return k;}
inline void stMarkBad(const std::string& why){stWriteVerdict("key="+stVkKey()+" choice=gles why="+why);}

// the self-test burst: 192 x 128 RAW (RGGB), 4 frames shifted by known sub-pixel offsets, a smooth chart (gratings, edges,
// ramps), deterministic noise; truth: the chart at the RAW grid
inline StJob stSelfTestJob(std::vector<std::vector<uint16_t>>& frames,std::vector<float>& truth){
    StJob j;j.W=192;j.H=128;j.cfa=0;j.white=1023;j.black={64,64,64,64};j.T=4;j.Z=3;j.tile=64;j.stripMB=32;j.hr=false;
    auto scene=[](double x,double y,int c){
        const double g=0.35+0.25*std::sin(0.31*x+0.07*y)*std::cos(0.13*y),e=x>96?0.15:0.0,r=0.1*y/128.0;
        return std::clamp(g+e+r+0.05*c,0.02,0.95);
    };
    const double sh[4][2]={{0,0},{0.37,-0.21},{-0.44,0.29},{0.18,0.46}};
    uint32_t seed=12345;
    auto rnd=[&]{seed=seed*1664525u+1013904223u;return (double(seed>>8)/16777216.0)-0.5;};
    frames.assign(4,std::vector<uint16_t>(size_t(j.W)*j.H));
    for(int k=0;k<4;++k){
        for(int y=0;y<j.H;++y)for(int x=0;x<j.W;++x){
            const int c=((y&1)<<1|(x&1))==0?0:((y&1)&&(x&1))?2:1;
            const double v=scene(x+0.5+sh[k][0],y+0.5+sh[k][1],c)+0.004*(rnd()+rnd()+rnd());
            frames[size_t(k)][size_t(y)*j.W+x]=uint16_t(std::clamp(std::lround(64+v*959),0L,1023L));
        }
        j.raw.push_back(frames[size_t(k)].data());
        // frame k at u sees the scene at u + sh: base -> frame = u - sh
        j.Hb.push_back(M3{1,0,-sh[k][0],0,1,-sh[k][1],0,0,1});j.Hinv.push_back(M3{1,0,sh[k][0],0,1,sh[k][1],0,0,1});
        j.slope.push_back(2e-4f);j.offset.push_back(1e-6f);
    }
    truth.resize(size_t(j.W)*j.H*3);
    for(int y=0;y<j.H;++y)for(int x=0;x<j.W;++x)for(int c=0;c<3;++c)truth[(size_t(y)*j.W+x)*3+size_t(c)]=float(scene(x+0.5,y+0.5,c));
    return j;
}
// PSNR of a result against the self-test truth, the borders (8 px) left out
inline double stSelfTestPsnr(const std::vector<float>& out,const std::vector<float>& truth,int W,int H){
    double se=0;long n=0;
    for(int y=8;y<H-8;++y)for(int x=8;x<W-8;++x)for(int c=0;c<3;++c){const double d=out[(size_t(y)*W+x)*3+size_t(c)]-truth[(size_t(y)*W+x)*3+size_t(c)];se+=d*d;++n;}
    return 10*std::log10(1.0/std::max(se/std::max(n,1L),1e-20));
}
struct StSelfTest { bool ok=false;double vkMs=0,glMs=0,diff=0,psnrVk=0,psnrGl=0;std::string why; };
inline StSelfTest stSelfTest(StBackend& vk,StBackend& gl){
    StSelfTest r;
    std::vector<std::vector<uint16_t>> frames;std::vector<float> truth;
    const StJob j=stSelfTestJob(frames,truth);
    using Clock=std::chrono::steady_clock;
    try{
        stSolve(gl,j);stSolve(vk,j); // warm-up (allocation, first dispatches)
        auto a=Clock::now();const StResult g=stSolve(gl,j);auto b=Clock::now();const StResult v=stSolve(vk,j);auto c=Clock::now();
        r.glMs=std::chrono::duration<double,std::milli>(b-a).count();r.vkMs=std::chrono::duration<double,std::milli>(c-b).count();
        for(size_t i=0;i<g.out.size();++i)r.diff=std::max(r.diff,double(std::abs(g.out[i]-v.out[i])));
        r.psnrGl=stSelfTestPsnr(g.out,truth,j.W,j.H);r.psnrVk=stSelfTestPsnr(v.out,truth,j.W,j.H);
        if(!(r.diff<=kStSelfTestTol))r.why="Vulkan differs from GLES by "+std::to_string(r.diff);
        else if(!(r.psnrVk>=30.0))r.why="Vulkan self-test PSNR "+std::to_string(r.psnrVk)+" dB";
        else r.ok=true;
    }catch(const std::exception& e){r.why=std::string("self-test: ")+e.what();}
    return r;
}

// The backend for this shot. backendLine: what ran and why (the report).
inline std::unique_ptr<StBackend> stChoose(const HybridTuning& tune,std::string& line,const std::function<void(const std::string&)>& report){
    const std::string cache=hybridProgramCacheDir();
    const bool envGles=std::getenv("SCAM_STATIC_GLES")!=nullptr;
    if(tune.staticVulkan==0||envGles){
        auto g=std::make_unique<StGles>(cache);line="GLES ("+std::string(envGles?"SCAM_STATIC_GLES":"staticVulkan 0")+"): "+g->describe();return g;
    }
    std::unique_ptr<StVulkan> vk;std::string why;
    try{vk=std::make_unique<StVulkan>(cache,tune.staticProfile!=0);}catch(const std::exception& e){why=e.what();}
    if(vk){std::string deny;if(hybridStaticVkDenied(vk->props.deviceName,deny)){why=deny;vk.reset();}}
    if(!vk){auto g=std::make_unique<StGles>(cache);line="GLES fallback (Vulkan: "+why+"): "+g->describe();return g;}
    char dev[300];std::snprintf(dev,sizeof(dev),"%s/%08x",vk->props.deviceName,vk->props.driverVersion);
    stVkKey()=stVersionKey(dev);
    if(tune.staticVulkan==1&&!std::getenv("SCAM_STATIC_SELFTEST")){line="Vulkan (staticVulkan 1): "+vk->describe();return vk;}
    // auto: the stored verdict of this version and driver, else the self-test now
    const std::string v=stReadVerdict();
    if(v.rfind("key="+stVkKey()+" ",0)==0&&!std::getenv("SCAM_STATIC_SELFTEST")){
        if(v.find(" choice=vulkan")!=std::string::npos){line="Vulkan (self-test verdict: "+v.substr(v.find("choice="))+"): "+vk->describe();return vk;}
        vk.reset();auto g=std::make_unique<StGles>(cache);line="GLES (self-test verdict: "+v.substr(v.find("choice="))+"): "+g->describe();return g;
    }
    std::unique_ptr<StGles> gl=std::make_unique<StGles>(cache);
    const StSelfTest t=stSelfTest(*vk,*gl);
    char r[400];
    std::snprintf(r,sizeof(r),"vk %.1f ms gles %.1f ms diff %.2e psnr vk %.2f gles %.2f dB",t.vkMs,t.glMs,t.diff,t.psnrVk,t.psnrGl);
    const bool vkWins=t.ok&&t.vkMs<=t.glMs;
    stWriteVerdict("key="+stVkKey()+" choice="+(vkWins?"vulkan":"gles")+" "+r+(t.ok?"":" why="+t.why));
    report(std::string("HYBRID STATIC SELF-TEST: ")+r+(t.ok?(vkWins?" -> Vulkan (faster)":" -> GLES (faster)"):" -> GLES ("+t.why+")"));
    if(vkWins){vk->passMs.clear();line="Vulkan (self-test: faster, matches GLES): "+vk->describe();gl.reset();return vk;}
    vk.reset();line="GLES (self-test: "+std::string(t.ok?"faster":t.why)+"): "+gl->describe();gl->passMs.clear();return gl;
}
} // namespace staticmerge

// P627: the burst's frames binned to a plain Bayer of W/b x H/b (the mean of each b x b block of one colour), even sizes, for the
// alignment of a colour-block CFA (the GPU KLT / RANSAC homography reads 2 x 2 Bayer cells)
inline std::vector<uint16_t> stBinBayer(const HybridInput& in,const uint16_t* raw,int b,int bw,int bh){
    std::vector<uint16_t> o(size_t(bw)*size_t(bh));
    for(int y=0;y<bh;++y)for(int x=0;x<bw;++x){
        uint32_t a=0;for(int j=0;j<b;++j){const uint16_t* r=raw+size_t(y*b+j)*in.w+size_t(x*b);for(int i=0;i<b;++i)a+=r[i];}
        o[size_t(y)*bw+x]=uint16_t((a+uint32_t(b*b/2))/uint32_t(b*b));
    }
    return o;
}

// P627b / P627d: a box of k taps (offsets -k/2 .. k - 1 - k/2, times step: step P = the 8 samples of one phase) along x then y,
// clamped at the borders, on the worker's row bands
inline void stBox(const std::vector<float>& src,std::vector<float>& dst,int W,int H,int k,int step){
    const size_t n=size_t(W)*size_t(H);
    std::vector<float> tmp(n);dst.resize(n);
    const int lo=-(k/2),hi=k-1-k/2;
    mergeRowBands(H,[&](int y0,int y1){for(int yy=y0;yy<y1;++yy){const float* s=src.data()+size_t(yy)*W;float* d=tmp.data()+size_t(yy)*W;
        for(int x=0;x<W;++x){double a=0;for(int i=lo;i<=hi;++i)a+=s[std::clamp(x+i*step,0,W-1)];d[x]=float(a/k);}}});
    mergeRowBands(H,[&](int y0,int y1){for(int yy=y0;yy<y1;++yy){float* d=dst.data()+size_t(yy)*W;
        for(int x=0;x<W;++x){double a=0;for(int i=lo;i<=hi;++i)a+=tmp[size_t(std::clamp(yy+i*step,0,H-1))*W+x];d[x]=float(a/k);}}});
}
// P627b: the sensor-locked lattice of the colour period off the luma, on the CPU at any period P (Tetra 8; Quad 4 where the GPU pass
// of P571 does not run): the same steps as ChromaMedianGpu::quadLattice (kQuadLatticeGlsl) - r = Y - box_P(Y) (no P-periodic part
// passes a P x P box), clipped at 2.5 x its own local scale (1.2533 x the 32 x 32 mean of |r|: edges stay out of the estimate),
// averaged per phase (x mod P, y mod P) over 8 x 8 samples of its phase, less its P x P box, taken off R, G and B
inline void stLatticeCpu(std::vector<float>& rgb,int W,int H,int P){
    if(W<8*P||H<8*P)return;
    const size_t n=size_t(W)*size_t(H);
    std::vector<float> y(n),t,r(n),a(n);
    for(size_t i=0;i<n;++i)y[i]=0.25f*(rgb[3*i]+2.f*rgb[3*i+1]+rgb[3*i+2]);
    stBox(y,t,W,H,P,1);
    for(size_t i=0;i<n;++i)r[i]=y[i]-t[i];
    for(size_t i=0;i<n;++i)a[i]=std::abs(r[i]);
    stBox(a,t,W,H,32,1);
    for(size_t i=0;i<n;++i){const float c=2.5f*1.2533f*t[i];r[i]=std::clamp(r[i],-c,c);}
    stBox(r,a,W,H,8,P);   // the phase's mean over 8 x 8 of its samples
    stBox(a,t,W,H,P,1);   // less its box: the periodic part alone
    for(size_t i=0;i<n;++i){const float l=a[i]-t[i];rgb[3*i]-=l;rgb[3*i+1]-=l;rgb[3*i+2]-=l;}
}
// P627f (owner, 2026-11-11: «Деталь должна быть лучше у статики»): the detail the solve recovered, brought out on the luma Y =
// (R + 2G + B) / 4 of the 1x result in two bands - fine F = Y - G0.7(Y) and mid M = G0.7(Y) - Gsig(Y) - against the result's own
// noise s (the MAD of F over its 20 % flattest places, x sqrt of the local level): F shrunk below `shrink` s (F^3 / (F^2 + (c s)^2):
// the grain the solve left), M x (1 + amount) above `core` s (its noise not raised); the new Y clamped to the 3 x 3 range of the old
// (no halo past a neighbour), R G B scaled by new / old Y (the colour stays). Bands of 64 rows (hybridPureBands: the pass on the
// input as it was, whatever the threads). Returns the noise estimate s at level 1 (0: not run)
inline float stDetail(std::vector<float>& rgb,int W,int H,float amount,float shrink,float core,float sig){
    if(W<32||H<32||(amount<=0.f&&shrink<=0.f))return 0.f;
    sig=std::clamp(sig,0.7f,4.f);
    const int Rg=int(std::ceil(3.f*sig)),R=std::max(Rg,8)+2;
    auto gk=[](float sg){const int r=int(std::ceil(3.f*sg));std::vector<float> k(size_t(2*r+1));float t=0;
        for(int i=-r;i<=r;++i){k[size_t(i+r)]=std::exp(-0.5f*float(i*i)/(sg*sg));t+=k[size_t(i+r)];}for(float& v:k)v/=t;return k;};
    const std::vector<float> k1=gk(0.7f),k2=gk(sig);
    // a band's planes: Y, G0.7, Gsig over rows [ya, yb) (the clamped rows of the image)
    struct Planes{int ya=0,n=0;std::vector<float> y,g1,g2;};
    auto blur=[&](const std::vector<float>& in,int n,const std::vector<float>& k,std::vector<float>& out){
        const int r=int(k.size()/2);std::vector<float> t(in.size());out.assign(in.size(),0.f);
        for(int j=0;j<n;++j)for(int x=0;x<W;++x){float a=0;for(int i=-r;i<=r;++i)a+=k[size_t(i+r)]*in[size_t(j)*W+size_t(std::clamp(x+i,0,W-1))];t[size_t(j)*W+x]=a;}
        for(int j=0;j<n;++j)for(int x=0;x<W;++x){float a=0;for(int i=-r;i<=r;++i)a+=k[size_t(i+r)]*t[size_t(std::clamp(j+i,0,n-1))*W+x];out[size_t(j)*W+x]=a;}
    };
    auto planes=[&](int y0,int y1,const std::function<const float*(int)>& row){
        Planes p;p.ya=std::max(0,y0-R);const int yb=std::min(H,y1+R);p.n=yb-p.ya;p.y.resize(size_t(p.n)*W);
        for(int j=0;j<p.n;++j){const float* r=row(p.ya+j);for(int x=0;x<W;++x)p.y[size_t(j)*W+x]=0.25f*r[3*x]+0.5f*r[3*x+1]+0.25f*r[3*x+2];}
        blur(p.y,p.n,k1,p.g1);blur(p.y,p.n,k2,p.g2);
        return p;
    };
    // the noise: |F| / sqrt(level) on every 4th pixel, kept where the 9 x 9 mean of |Y - box3 Y| is in the lowest 20 %
    std::vector<std::pair<float,float>> smp;std::mutex mu;
    mergeRowBands(H,[&](int r0,int r1){
        std::vector<std::pair<float,float>> loc;
        for(int y0=r0;y0<r1;y0+=64){const int y1=std::min(r1,y0+64);
            const Planes p=planes(y0,y1,[&](int y){return rgb.data()+size_t(y)*W*3;});
            for(int y=y0+(4-y0%4)%4;y<y1;y+=4)for(int x=8;x+8<W;x+=4){
                const int j=y-p.ya;if(j<5||j+5>p.n)continue;
                float act=0;
                for(int dy=-4;dy<=4;dy+=2)for(int dx=-4;dx<=4;dx+=2){const size_t i=size_t(j+dy)*W+size_t(x+dx);
                    float b=0;for(int u=-1;u<=1;++u)for(int v=-1;v<=1;++v)b+=p.y[size_t(j+dy+u)*W+size_t(x+dx+v)];act+=std::abs(p.y[i]-b/9.f);}
                const size_t i=size_t(j)*W+x;const float lv=std::sqrt(std::max(p.g2[i],1e-5f));
                loc.push_back({act/lv,std::abs(p.y[i]-p.g1[i])/lv});
            }}
        std::lock_guard<std::mutex> g(mu);smp.insert(smp.end(),loc.begin(),loc.end());
    });
    if(smp.size()<100)return 0.f;
    const size_t q=smp.size()/5;
    std::nth_element(smp.begin(),smp.begin()+std::ptrdiff_t(q),smp.end());
    std::vector<float> f;f.reserve(q);for(size_t i=0;i<q;++i)f.push_back(smp[i].second);
    std::nth_element(f.begin(),f.begin()+std::ptrdiff_t(f.size()/2),f.end());
    const float k=1.4826f*f[f.size()/2];
    if(!(k>0.f))return 0.f;
    hybridPureBands(rgb,W,H,64,R,[&](int b,const HybridPureRows& row){
        const int y0=b*64,y1=std::min(H,y0+64);
        const Planes p=planes(y0,y1,[&](int y){return row(y);});
        std::vector<float> band(size_t(y1-y0)*W*3);
        for(int y=y0;y<y1;++y){const int j=y-p.ya;const float* src=row(y);
            for(int x=0;x<W;++x){
                const size_t i=size_t(j)*W+x;
                const float Y=p.y[i],G1=p.g1[i],G2=p.g2[i],s=k*std::sqrt(std::max(G2,1e-5f));
                const float F=Y-G1,M=G1-G2;
                const float Fo=shrink>0.f?F*F*F/(F*F+shrink*shrink*s*s+1e-20f):F;
                const float Mo=M+amount*M*M*M/(M*M+core*core*s*s+1e-20f);
                float lo=Y,hi=Y;
                for(int u=-1;u<=1;++u)for(int v=-1;v<=1;++v){const float t=p.y[size_t(std::clamp(j+u,0,p.n-1))*W+size_t(std::clamp(x+v,0,W-1))];lo=std::min(lo,t);hi=std::max(hi,t);}
                const float Yn=std::clamp(G2+Mo+Fo,lo,hi);
                const float r=std::clamp((std::max(Yn,0.f)+1e-6f)/(std::max(Y,0.f)+1e-6f),0.5f,2.f);
                float* o=&band[(size_t(y-y0)*W+x)*3];
                for(int c=0;c<3;++c)o[c]=src[3*x+c]*r;
            }}
        std::copy(band.begin(),band.end(),rgb.begin()+std::ptrdiff_t(size_t(y0)*W*3));
    });
    return k;
}

inline std::vector<float> hybridStaticMerge(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report,
                                           std::vector<uint16_t>* mergedDng,std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    using namespace staticmerge;
    using Clock=std::chrono::steady_clock;
    auto ms=[](Clock::time_point a,Clock::time_point b){return std::chrono::duration<double,std::milli>(b-a).count();};
    const auto t0=Clock::now();
    worker_crash::Stage stage("static merge");
    scam_progress::pass("static");
    const int W=input.w,H=input.h;
    // P627: what no reconstruction of this route can take (none of these reaches the worker from the app): the job fails, the app
    // saves the base frame alone and says so (HYBRID ROUTE) - never another mode's merge
    if(input.frames.empty()||!input.frames[0].raw)throw std::runtime_error("HYBRID ROUTE: «Статика» cannot run: no base frame");
    if(input.cfa<0||input.cfa>3)throw std::runtime_error("HYBRID ROUTE: «Статика» cannot run: CFA "+std::to_string(input.cfa)+" (not a colour mosaic)");
    if(W<16||H<16)throw std::runtime_error("HYBRID ROUTE: «Статика» cannot run: frame "+std::to_string(W)+"x"+std::to_string(H));
    const int b=hybridStaticBlock(input,tune,report),bs=b==4?2:b==2?1:0;
    const bool noGpu=tune.staticVulkan<=-2;
    // ---- frames: the base and the normal frames (up to staticFrames)
    std::vector<int> fidx{0};
    for(int i=1;i<int(input.frames.size())&&int(fidx.size())<std::max(1,tune.staticFrames);++i)
        if(input.frames[size_t(i)].role==kRoleNormal&&input.frames[size_t(i)].raw)fidx.push_back(i);
    const int others=int(input.frames.size())-int(fidx.size());
    {
        char t[260];std::snprintf(t,sizeof(t),"HYBRID ROUTE: «Статика» (the static merge), %s, %d normal frames of %d sent%s",
            b==1?"Bayer":b==2?"Quad 2x2 colour blocks":"Tetra 4x4 colour blocks",int(fidx.size()),int(input.frames.size()),
            input.dcg?(hybridDcgZones(input)?", DCG (two noise zones)":", DCG (one noise zone: not measured)"):"");
        report(t);
    }
    // ---- alignment: the GPU KLT + RANSAC homography of every frame (LaGpu, the «Качество» route's) on the RAW (Bayer) or on the
    // binned plain Bayer (Quad / Tetra), base -> frame; a frame it does not solve, or every frame when it does not start (no GPU),
    // the CPU Lucas-Kanade affine
    std::vector<M3> Hc(fidx.size(),M3{1,0,0,0,1,0,0,0,1});
    std::vector<char> ok(fidx.size(),0);ok[0]=1;
    std::string alignLine="HYBRID STATIC ALIGN: GPU homography inliers/rms (LK: the CPU affine after a failed one)";
    {
        worker_crash::Stage st("static align");
        const HybridFrame& b0=input.frames[0];
        const float slope=std::max(b0.slope,1e-9f),offset=std::max(b0.offset,0.f);
        const float eps=std::max(offset/slope,1e-5f);
        const int aw=b>1?(W/b)&~1:W,ah=b>1?(H/b)&~1:H; // the frames the GPU alignment reads
        HybridInput ain=input;ain.w=aw;ain.h=ah;
        const int win=std::clamp(tune.laWin,4,kLaMaxWin)&~3,stride=std::clamp(tune.laStride,2,win);
        LaImage l0,l1;l0.w=aw/2;l0.h=ah/2;l1.w=l0.w/2;l1.h=l0.h/2;
        std::unique_ptr<LaGpu> la;std::string laWhy=noGpu?"no GPU (staticVulkan -2)":"";
        std::vector<uint16_t> baseBin;
        if(!noGpu&&fidx.size()>1){
            try{
                if(aw<16||ah<16)throw std::runtime_error("binned frame "+std::to_string(aw)+"x"+std::to_string(ah));
                const LaGrid a=laGrid(l0,win,stride,2),c=laGrid(l1,16,8,4);
                LaGpuBurst gb;gb.w=aw;gb.h=ah;gb.cfa=input.cfa;gb.canonical=true;gb.white=input.white;gb.black=input.black;
                if(b>1){const float m=0.25f*(input.black[0]+input.black[1]+input.black[2]+input.black[3]);gb.black={m,m,m,m};
                    baseBin=stBinBayer(input,b0.raw,b,aw,ah);}
                LaGpuTune gt;gt.mu=tune.laMu;gt.kappa=tune.laKappa;gt.maxShift=tune.laMaxShift;gt.iters=tune.laIters;gt.itersCoarse=tune.laItersCoarse;gt.medians=tune.laMedian;
                la=std::make_unique<LaGpu>(hybridProgramCacheDir());
                la->base(gb,b>1?baseBin.data():b0.raw,eps,hybridLaV0(input,slope/float(b*b),offset/float(b*b)),LaGpuGrid{a.nx,a.ny,a.win,a.stride,a.s},LaGpuGrid{c.nx,c.ny,c.win,c.stride,c.s},gt);
            }catch(const std::exception& e){la.reset();laWhy=e.what();}
            if(!la)report("HYBRID STATIC ALIGN: the GPU alignment did not start ("+laWhy+"): the CPU Lucas-Kanade affine for every frame");
        }
        // index coordinates (centres on integers, canonical) -> ours (centres at + 0.5): T(0.5) H T(-0.5); binned: S(b) . S(1/b)
        const M3 Tp{1,0,0.5,0,1,0.5,0,0,1},Tm{1,0,-0.5,0,1,-0.5,0,0,1};
        const M3 Sb{double(b),0,0,0,double(b),0,0,0,1},Si{1.0/b,0,0,0,1.0/b,0,0,0,1};
        std::vector<StGrey> refPyr; // the CPU fallback's base pyramid (built on the first frame that needs it)
        auto cpuAffine=[&](size_t k,const std::string& why){
            if(refPyr.empty()){refPyr.push_back(stGreyCells(input,b0.raw,1.f,b));while(refPyr.back().w>=128&&refPyr.back().h>=64)refPyr.push_back(stHalf(refPyr.back()));}
            const HybridFrame& f=input.frames[size_t(fidx[k])];
            std::vector<StGrey> mp{stGreyCells(input,f.raw,1.f/std::max(f.exposure,1e-6f),b)};
            while(mp.size()<refPyr.size())mp.push_back(stHalf(mp.back()));
            M3 h;char t[160];
            if(stLkAffine(refPyr,mp,h)){Hc[k]=h;ok[k]=1;std::snprintf(t,sizeof(t)," %d:LK(%s)",fidx[k],why.substr(0,60).c_str());}
            else std::snprintf(t,sizeof(t)," %d:failed(%s)",fidx[k],why.substr(0,60).c_str());
            alignLine+=t;
        };
        if(la){
            size_t next=1;
            std::vector<size_t> inflight;
            while(next<fidx.size()||!inflight.empty()){
                while(next<fidx.size()&&inflight.size()<2){
                    const HybridFrame& f=input.frames[size_t(fidx[next])];
                    if(b>1){const std::vector<uint16_t> bin=stBinBayer(input,f.raw,b,aw,ah); // (LaGpu copies the frame at submit)
                        la->homSubmit(bin.data(),1.f/std::max(f.exposure,1e-6f),fidx[next]);}
                    else la->homSubmit(f.raw,1.f/std::max(f.exposure,1e-6f),fidx[next]);
                    inflight.push_back(next);++next;
                }
                const size_t k=inflight.front();inflight.erase(inflight.begin());
                const LaGpuHom r=la->homCollect();
                // P627c (Ark's school burst: 24 corners a frame, all inliers at 0.16 - 0.5 px, refused for < 30): a refused but solved
                // homography of >= 12 inliers within 0.75 px (LaGpuHom.hw) before the CPU affine
                const bool weakOk=!r.ok&&r.why=="too few inliers"&&r.inliers>=12&&r.rms<=0.75f;
                if(r.ok||weakOk){
                    const auto& hh=r.ok?r.h:r.hw;
                    const M3 h{hh[0],hh[1],hh[2],hh[3],hh[4],hh[5],hh[6],hh[7],1.0};
                    Hc[k]=b>1?m3mul(m3mul(Sb,m3mul(m3mul(Tp,h),Tm)),Si):m3mul(m3mul(Tp,h),Tm);ok[k]=1;
                    char t[96];std::snprintf(t,sizeof(t)," %d:%d/%.2f%s",fidx[k],r.inliers,r.rms,weakOk?"w":"");alignLine+=t;
                } else cpuAffine(k,r.why);
                scam_progress::tick();
            }
        } else for(size_t k=1;k<fidx.size();++k){cpuAffine(k,laWhy);scam_progress::tick();}
    }
    const auto tAlign=Clock::now();
    // ---- the static-scene check on the thumbnails: a frame with moving blocks above staticMotionMax is left out. P627: however
    // many move, the merge goes on with the frames that do not - down to the base frame alone (the solve of one frame: its own
    // reconstruction, the denoise of the app after it); never another mode's merge
    std::vector<M3> Hi(fidx.size());
    for(size_t k=0;k<fidx.size();++k)if(ok[k]&&!m3inv(Hc[k],Hi[k]))ok[k]=0;
    std::string checkLine="HYBRID STATIC CHECK: moving-block fraction per frame (limit "+std::to_string(tune.staticMotionMax).substr(0,5)+")";
    int movingFrames=0;
    std::vector<float> photo; // per frame: base mean / frame mean (exposure-normalised thumbnails)
    {
        const float off18=input.frames[0].offset+(hybridDcgZones(input)?(input.frames[0].dcgOffsetLcg-input.frames[0].offset)*hybridDcgShare(0.18f,input.frames[0].dcgKnee):0.f);
        const FastThumb tb=b>1?stThumbBlocks(input,input.frames[0].raw,1.f):fastThumb(input,input.frames[0].raw,1.f);
        photo.assign(fidx.size(),1.f);
        for(size_t k=1;k<fidx.size();++k){
            if(!ok[k])continue;
            const HybridFrame& f=input.frames[size_t(fidx[k])];
            const float gain=1.f/std::max(f.exposure,1e-6f);
            const FastThumb tf=b>1?stThumbBlocks(input,f.raw,gain):fastThumb(input,f.raw,gain);
            const float frac=hybridStaticMoving(tb,tf,Hi[k],input.frames[0].slope,off18,b>1?64.f:8.f);
            // the frame's brightness against the base (AE / light drift within a burst: up to 10 % seen, e193420): left in, the
            // frames' sites disagree by it and the 2x solve draws a Bayer-period checker
            if(tf.mean>1e-6f&&tb.mean>1e-6f)photo[k]=std::clamp(tb.mean/tf.mean,0.8f,1.25f);
            char t[48];std::snprintf(t,sizeof(t)," %d:%.3f",fidx[k],frac);checkLine+=t;
            if(frac>tune.staticMotionMax){ok[k]=0;++movingFrames;}
        }
    }
    std::vector<int> use;for(size_t k=0;k<fidx.size();++k)if(ok[k])use.push_back(int(k));
    const int K=int(use.size());
    checkLine+=" -> "+std::to_string(K)+" of "+std::to_string(fidx.size())+" frames ("+std::to_string(movingFrames)+" moving)";
    report(alignLine);
    report(checkLine);
    if(K<std::max(3,int(fidx.size()+1)/2))
        report("HYBRID STATIC CHECK: "+std::to_string(K)+(K==1?" frame (the base alone)":" frames")+" left of "+std::to_string(fidx.size())
            +": the static merge goes on with "+(K==1?"the base frame's own reconstruction":"them")+" (P627: «Статика» stays «Статика»)");
    const auto tCheck=Clock::now();
    // ---- the solve's input
    StJob job;
    job.W=W;job.H=H;job.cfa=input.cfa;job.white=input.white;job.black=input.black;job.bs=bs;
    // P627c (the school burst: the GPU homography refused every frame, the field at its 3 px cap): a CPU affine leaves more to the
    // field - its cap 6 px then
    if(alignLine.find(":LK(")!=std::string::npos)job.maxShift=6.f;
    const bool zones=hybridDcgZones(input);
    for(int k:use){const HybridFrame& f=input.frames[size_t(fidx[size_t(k)])];
        job.raw.push_back(f.raw);job.Hb.push_back(Hc[size_t(k)]);job.Hinv.push_back(Hi[size_t(k)]);job.slope.push_back(f.slope);job.offset.push_back(std::max(f.offset,0.f));
        job.expo.push_back(std::max(f.exposure,1e-6f)/(photo.size()>size_t(k)?photo[size_t(k)]:1.f));
        // P627 DCG (owner: never one noise curve over a DCG frame): the frame's two measured zones, as the fast route's noise LUT
        job.dcgKnee.push_back(zones&&f.dcgKnee>0.f?f.dcgKnee:0.f);job.dcgLcg.push_back(zones&&f.dcgKnee>0.f?std::max(f.dcgOffsetLcg,0.f):0.f);}
    { std::string line="HYBRID STATIC PHOTOMETRY: frame brightness vs the base";
      for(size_t j=1;j<job.expo.size();++j){char t[32];std::snprintf(t,sizeof(t)," %d:%.3f",fidx[size_t(use[j])],1.0/job.expo[j]);line+=t;}
      report(line); }
    if(zones){char t[200];std::snprintf(t,sizeof(t),"HYBRID STATIC DCG: two noise zones in the data weights (knee %.6g, offset %.6g / LCG %.6g)",
        double(input.frames[0].dcgKnee),double(input.frames[0].offset),double(input.frames[0].dcgOffsetLcg));report(t);}
    // sub-pixel coverage of the colour sites (P624: a tripod burst has no shift between its frames, and the 2x solve then has
    // only one quarter of the R / B positions - a demosaic, where weak colour priors leave zipper / checker patterns): the frames'
    // offsets at the image centre modulo the colour period (2 px Bayer; P627: 4 Quad, 8 Tetra), the b x b quadrants of the period
    // they reach; the colour smoothness x (1 + 3 (4 - quadrants)) - x1 with all four (hand-held), x10 with one
    int quadrants=0;
    {
        bool hit[4]={false,false,false,false};
        const double per=2.0*b;
        for(int k:use){const auto p=m3apply(Hi[size_t(k)],0.5*W,0.5*H);
            double fx=std::fmod(p[0]-0.5*W,per),fy=std::fmod(p[1]-0.5*H,per);if(fx<0)fx+=per;if(fy<0)fy+=per;
            // P627: a frame within 0.25 px of the base samples the base's sites (a tripod's jitter of +-0.05 px about 0 made
            // floor() over [0, b) / [b, 2b) count up to four quadrants)
            const double ax=p[0]-0.5*W,ay=p[1]-0.5*H;
            if(std::abs(ax)<0.25&&std::abs(ay)<0.25){hit[0]=true;continue;}
            hit[(fy>=b?2:0)+(fx>=b?1:0)]=true;}
        for(bool h:hit)quadrants+=h?1:0;
    }
    const float coverageGain=1.f+3.f*float(4-quadrants);
    { char t[140];std::snprintf(t,sizeof(t),"HYBRID STATIC COVERAGE: the frames reach %d of 4 %s quadrants -> colour smoothness x%.0f",quadrants,
        b==1?"Bayer":b==2?"Quad":"Tetra",coverageGain);report(t); }
    const auto outSize=hybridFastOut(input,tune);
    job.hr=outSize[0]>W&&std::clamp(tune.staticScale,1,2)==2;
    // the noise check of «Качество» (P71: flat blocks of two frames against the header model), its rule: x1.5..6 up / x0.02..0.67
    // down with 100 flat blocks scales every frame's model. P627: a Quad / Tetra burst on one site of a green block per period
    // (its green phase mixes colours); a DCG burst's zones were measured before this route
    if(!input.dcg){
        const HybridNoiseCheck c=hybridMeasureNoise(input,b);
        const bool up=c.ratio>=1.5&&c.ratio<=6.0,down=c.ratio>=0.02&&c.ratio<=0.67,scale=c.flat>=100&&(up||down);
        const double kk=up?std::min(c.ratio,4.0):std::max(c.ratio,1.0/16);
        char t[160];std::snprintf(t,sizeof(t),"HYBRID STATIC NOISE: %ld flat blocks, measured / model %.3f -> model x%.3f",c.flat,c.ratio,scale?kk:1.0);
        report(t);
        if(scale)for(size_t j=0;j<job.slope.size();++j){job.slope[j]*=float(kk);job.offset[j]*=float(kk);}
    }
    stJobTuning(job,tune);
    // P627e: Quad / Tetra only (Bayer as P627c: its edges took no stairs / stripes there)
    job.priorFrames=b>1?std::max(0.f,b>=4?tune.staticTetraPriorFrames:tune.staticPriorFrames):0.f;
    if(b==1)job.edgeK=0.f;
    if(b==1){job.chromaFloor=std::max(0.f,tune.staticBayerChromaFloor);job.lumaFloor=std::max(0.f,tune.staticBayerLumaFloor);} // P627c
    job.chroma*=coverageGain;
    // the sites' response: Bayer - the two green sites' balance in the programs (siteGain); Quad / Tetra - the native merge's site
    // classes (hybridMosaicGains: 64 classes of the 8 x 8 period, a zone field where the response drifts over the frame) and the
    // two green blocks' balance, divided out of every frame's rows as they are uploaded (clipped sites as they are)
    std::array<float,64> gain64;gain64.fill(1.f);
    HybridGainField gainField;
    if(b==1){ // green balance: the two green sites' mean ratio over the base frame's unclipped cells (sensor Gr / Gb imbalance, ~0.3 %,
              // otherwise fitted as a 2-px checker where the frames' shifts are small), half of it on each green site
        const int gx0=(input.cfa==0||input.cfa==3)?1:0; // the green of the first row: x parity (RGGB / BGGR: 1, GRBG / GBRG: 0)
        double s1=0,s2=0;
        const uint16_t* raw=input.frames[0].raw;
        for(int y=0;y+1<H;y+=2)for(int x=0;x+1<W;x+=2){
            const float a=raw[size_t(y)*W+x+gx0],c=raw[size_t(y+1)*W+x+(1-gx0)];
            if(a>=0.9f*input.white||c>=0.9f*input.white)continue;
            s1+=a-input.black[size_t(gx0)];s2+=c-input.black[size_t(2+(1-gx0))];
        }
        if(s1>0&&s2>0){
            const double r=std::clamp(s1/s2,0.97,1.03); // site 1 / site 2
            job.siteGain[size_t(gx0)]=float(1.0/std::sqrt(r));job.siteGain[size_t(2+(1-gx0))]=float(std::sqrt(r));
            char t[96];std::snprintf(t,sizeof(t),"HYBRID STATIC GREEN: first-row / second-row green %.4f (balanced)",s1/s2);report(t);
        }
    } else {
        if(tune.mosaicGain){
            std::vector<int> gf;for(int k:use)if(gf.size()<(tune.mosaicGain>=2?16u:4u))gf.push_back(fidx[size_t(k)]);
            float spread=0,err=0;long used=0;
            gain64=hybridMosaicGains(input,gf,b,spread,&used,tune.mosaicGain,&err,tune.mosaicGainField?&gainField:nullptr,tune.mosaicGainZones);
            char t[300];std::snprintf(t,sizeof(t),"HYBRID STATIC SITES: response of the 64 site classes from %d frames, %ld smooth tiles, gain %.4f..%.4f "
                "(spread %.2f %%), error %.3f %%, field %s",int(gf.size()),used,*std::min_element(gain64.begin(),gain64.end()),
                *std::max_element(gain64.begin(),gain64.end()),100.0*spread,100.0*err,gainField.on()?(std::to_string(gainField.zx)+"x"+std::to_string(gainField.zy)+" zones").c_str():"off");
            report(t);
        }
        // the two green blocks (the first block row's and the second's) of the base, after the site classes
        const int dx=input.cfa&1,dy=input.cfa>>1; // the R block's (x, y) parity of the block grid
        double s1=0,s2=0;
        const uint16_t* raw=input.frames[0].raw;
        const float bl=0.25f*(input.black[0]+input.black[1]+input.black[2]+input.black[3]);
        for(int y=0;y<H;y+=2)for(int x=0;x<W;x+=2){
            const int bx=(x>>bs)&1,by=(y>>bs)&1;
            if(((bx^dx)+(by^dy))!=1)continue; // a green block
            const float v=raw[size_t(y)*W+x];
            if(v>=0.9f*input.white)continue;
            const int k=((y&7)<<3)|(x&7);
            const double g=(gainField.on()?gainField.at(k,x,y):gain64[size_t(k)])*(double(v)-bl);
            ((by^dy)==0?s1:s2)+=g;
        }
        std::array<float,64> greenK;greenK.fill(1.f);
        if(s1>0&&s2>0){
            const double r=std::clamp(s1/s2,0.97,1.03);
            for(int k=0;k<64;++k){const int x=k&7,y=k>>3,bx=(x>>bs)&1,by=(y>>bs)&1;
                if(((bx^dx)+(by^dy))==1)greenK[size_t(k)]=float((by^dy)==0?1.0/std::sqrt(r):std::sqrt(r));}
            char t[110];std::snprintf(t,sizeof(t),"HYBRID STATIC GREEN: first-row / second-row green blocks %.4f (balanced)",s1/s2);report(t);
        }
        const std::array<float,4> black=input.black;const float white=input.white;
        const std::vector<const uint16_t*> raws=job.raw;
        job.rawRows=[raws,gain64,greenK,gainField,black,white,W](int k,int y0,int n,uint16_t* dst){
            auto band=[&](int ya,int yb){
                std::vector<float> g(size_t(W)+8);
                for(int y=ya;y<yb;++y){
                    // the row's gains (the zone field at each 8 px period's centre: it changes over ~W / 24 px)
                    for(int xb=0;xb<W;xb+=8)for(int i=0;i<8&&xb+i<W;++i){const int kk=((y&7)<<3)|i;
                        g[size_t(xb+i)]=(gainField.on()?gainField.at(kk,std::min(xb+4,W-1),y):gain64[size_t(kk)])*greenK[size_t(kk)];}
                    const uint16_t* r=raws[size_t(k)]+size_t(y)*size_t(W);uint16_t* d=dst+size_t(y-y0)*size_t(W);
                    for(int x=0;x<W;++x){
                        const float bl=black[size_t(((y&1)<<1)|(x&1))],v=float(r[x]);
                        if(v-bl>=0.98f*(white-bl)){d[x]=r[x];continue;} // clipped: as it is (out of the data term)
                        d[x]=uint16_t(std::clamp(std::lround(bl+(v-bl)*g[size_t(x)]),0L,65535L));
                    }
                }
            };
            const int t=std::clamp(int(std::thread::hardware_concurrency()),1,4);
            if(t<2||int64_t(n)*W<(int64_t(1)<<20)){band(y0,y0+n);return;}
            std::vector<std::thread> pool;
            for(int i=0;i<t;++i)pool.emplace_back([&,i]{band(y0+int(int64_t(n)*i/t),y0+int(int64_t(n)*(i+1)/t));});
            for(auto& th:pool)th.join();
        };
        // the colour prior of the start image: the base's blocks binned (its sites' response out) into a plain Bayer, a bilinear
        // demosaic of it, (R - G, B - G) per block in the programs' units
        const int cw=W/b,ch=H/b;
        std::vector<float> m(size_t(cw)*size_t(ch));
        std::vector<uint16_t> rowsBuf(size_t(b)*size_t(W));
        const float range=std::max(white-bl,1.f),e0=job.expo.empty()?1.f:job.expo[0];
        for(int Y=0;Y<ch;++Y){
            job.rawRows(0,Y*b,b,rowsBuf.data());
            for(int X=0;X<cw;++X){double a=0;for(int j=0;j<b;++j)for(int i=0;i<b;++i)a+=rowsBuf[size_t(j)*W+size_t(X*b+i)];
                m[size_t(Y)*cw+X]=float((a/double(b*b)-bl)/range)/e0;}
        }
        job.cdW=cw;job.cdH=ch;job.cd.assign(size_t(cw)*size_t(ch)*2,0.f);
        auto colour=[&](int X,int Y){const int cx=(X&1)^dx,cy=(Y&1)^dy;return cx+cy==0?0:cx+cy==2?2:1;};
        for(int Y=0;Y<ch;++Y)for(int X=0;X<cw;++X){
            double sum[3]={0,0,0},n[3]={0,0,0};
            for(int j=-1;j<=1;++j)for(int i=-1;i<=1;++i){const int xx=std::clamp(X+i,0,cw-1),yy=std::clamp(Y+j,0,ch-1);
                const int c=colour(xx,yy);const double w=(i==0&&j==0)?4.0:(i==0||j==0)?2.0:1.0;sum[c]+=w*m[size_t(yy)*cw+xx];n[c]+=w;}
            const double G=n[1]>0?sum[1]/n[1]:0,R=n[0]>0?sum[0]/n[0]:G,B=n[2]>0?sum[2]/n[2]:G;
            job.cd[2*(size_t(Y)*cw+X)]=float(R-G);job.cd[2*(size_t(Y)*cw+X)+1]=float(B-G);
        }
    }
    { // the white balance of the regularisers: the camera neutral the app sends (wbR / wbB), else the base frame's grey world
        const std::array<float,3> g=hybridBlendWb(input,tune);
        job.wbR=std::clamp(g[0]/std::max(g[1],1e-6f),0.1f,10.f);job.wbB=std::clamp(g[2]/std::max(g[1],1e-6f),0.1f,10.f);
    }
    // ---- the solve on the backend the device chose (Vulkan or GLES, staticmerge::stChoose). P627: a backend that fails gives
    // way inside the route - Vulkan -> GLES, GLES -> GLES again in smaller strips (a refused buffer, the strip guard) -> the CPU
    // transcription of the same programs; only when that fails as well the route cannot make the photo (the app saves the base frame)
    StResult res;
    std::string backendLine;
    {
        worker_crash::Stage st("static solve");
        std::unique_ptr<StBackend> gpu;
        if(noGpu){gpu=std::make_unique<StCpu>();backendLine="CPU (staticVulkan -2: no GPU for this shot): "+gpu->describe();}
        else try{gpu=stChoose(tune,backendLine,report);}
        catch(const std::exception& e){
            report(std::string("HYBRID STATIC GPU: no GPU backend started (")+e.what()+"); the CPU transcription of the same programs");
            gpu=std::make_unique<StCpu>();backendLine="CPU (no GPU backend): "+gpu->describe();
        }
        bool smaller=false;
        for(;;){
            try{res=stSolve(*gpu,job);break;}
            catch(const std::exception& e){
                const std::string was=gpu->name();
                gpu.reset();
                if(was=="Vulkan"){
                    stMarkBad(std::string("mid-shot: ")+e.what());
                    report(std::string("HYBRID STATIC GPU: Vulkan failed mid-shot (")+e.what()+"); the shot again on GLES, Vulkan off for this device and app version");
                    try{gpu=std::make_unique<StGles>(hybridProgramCacheDir());backendLine="GLES (after a Vulkan failure): "+gpu->describe();}
                    catch(const std::exception& e2){report(std::string("HYBRID STATIC GPU: GLES did not start (")+e2.what()+"); the CPU transcription");
                        gpu=std::make_unique<StCpu>();backendLine="CPU (after Vulkan and GLES failed): "+gpu->describe();}
                    continue;
                }
                if(was=="GLES"&&!smaller){
                    smaller=true;job.stripMB=std::max(32,job.stripMB/4);
                    report(std::string("HYBRID STATIC GPU: GLES failed (")+e.what()+"); the shot again on GLES in smaller strips ("+std::to_string(job.stripMB)+" MB)");
                    try{gpu=std::make_unique<StGles>(hybridProgramCacheDir());backendLine="GLES (smaller strips after a failure): "+gpu->describe();continue;}
                    catch(const std::exception& e2){report(std::string("HYBRID STATIC GPU: GLES did not start again (")+e2.what()+")");}
                }
                if(was!="CPU"){
                    report(std::string("HYBRID STATIC GPU: ")+was+" failed ("+e.what()+"); the shot on the CPU transcription of the same programs");
                    gpu=std::make_unique<StCpu>();backendLine="CPU (after a GPU failure): "+gpu->describe();
                    continue;
                }
                throw std::runtime_error(std::string("HYBRID ROUTE: «Статика» could not run on any backend (")+e.what()+")");
            }
        }
        res.passMs=gpu->passMs;
    }
    report("HYBRID STATIC GPU: "+backendLine);
    const int OW=res.ow,OH=res.oh,ntx=res.ntx,nty=res.nty,strips=res.strips,R=res.rowsPerStrip,T=job.T,Z=job.Z,tile=job.tile,s=job.s;
    const bool hr=job.hr;
    std::vector<float>& out=res.out;
    const std::vector<uint32_t>& counters=res.counters;
    const std::vector<float>& fld=res.fld;
    const double uploadMs=res.uploadMs,waitMs=res.gpuMs;
    { // the outlier test's share of the frames' samples - a static scene stays near 0 (the synthetic and the Ark bursts: <= 0.2 %);
      // P627: high or not, the robust weights already left those samples out - reported only
        double rej=0,val=0;for(int j=1;j<K;++j){rej+=counters[size_t(2*j+1)];val+=counters[size_t(2*j)];}
        const double share=val>0?rej/val:0;
        if(share>0.10){char t[200];std::snprintf(t,sizeof(t),"HYBRID STATIC DATA: %.1f %% of the frames' samples rejected as motion (left out of the solve)",100*share);report(t);}
    }
    // the app's size (1.5x: the 2x solve resampled; any other size the grid nearest it)
    int RW=OW,RHt=OH;
    if(outSize[0]!=OW||outSize[1]!=OH){hybridResampleResult(out,OW,OH,outSize[0],outSize[1],nullptr,nullptr);RW=outSize[0];RHt=outSize[1];}
    // P627: a colour-block result takes the native merge's chroma median (both other routes run it on Quad / Tetra) where the
    // frames' shifts do not reach every colour quadrant (a tripod: the colour comes from the prior, its block lattice shows) or
    // the outlier test left more than 5 % of the samples out (leaves in the wind: the colour of a pixel from a few frames' sites,
    // coloured specks) - staticMosaicChroma 1; 2 always, 0 never. Synthetic Quad / Tetra (P627): hand-held without it +2.0 / +3.0
    // dB, on a tripod with it +1.0 / +0.2 dB. P627b (owner: «артефакты на "статике"», tetra_tc's wall): the real bursts' colour
    // checker at the block period is there with full coverage and no rejection too (tc: 0.0043 against Quality's 0.0001) -
    // 1 = always on a colour-block CFA. Quad: then the fast route's lattice pass (staticQuadLattice)
    if(b==1&&tune.staticBayerChroma>0.f&&tune.plainChromaMedian>0){ // P627c: Bayer - «Качество»'s plain chroma median (its share)
        const auto c0=Clock::now();std::string how;
        std::vector<float> keep;const float k=std::min(1.f,tune.staticBayerChroma);
        if(k<1.f)keep=out;
        mosaicChromaMedian(out,RW,RHt,std::max(1,tune.plainChromaMedian)*std::max(1,int(std::lround(double(RW)/std::max(W,1)))),nullptr,tune.chromaGpu>0,&how,0,nullptr);
        if(!keep.empty())for(size_t i=0;i<out.size();++i)if(i%3!=1)out[i]=keep[i]+k*(out[i]-keep[i]);
        report("HYBRID STATIC POST: Bayer chroma median x"+std::to_string(k).substr(0,4)+" ("+how+") "+std::to_string(int(ms(c0,Clock::now())))+" ms");
    }
    if(b>1&&tune.mosaicChroma&&tune.staticMosaicChroma!=0){
        const auto c0=Clock::now();
        const int bo=std::max(1,int(std::lround(double(b)*RW/std::max(W,1))));
        std::string how;
        const bool lattice=b==2&&tune.staticQuadLattice!=0&&tune.chromaGpu>0;
        chromaKeepNext(lattice);
        mosaicChromaMedian(out,RW,RHt,bo,nullptr,tune.chromaGpu>0,&how);
        chromaKeepNext(false);

        report("HYBRID STATIC MOSAIC: chroma median (dual 5-point, taps "+std::to_string(std::max(1,bo/2))+" px, "+how+") "+std::to_string(int(ms(c0,Clock::now())))+" ms");
    }
    // P627b: the native merge's along-edge smoothing (P525 / P531: hybridAlongEdge, Tetra's taps step 2 px) on the whole result -
    // the solve's colour blocks leave b-px stairs on strong edges (tetra_tc's bell window, quad_bm's branch) wherever the frames'
    // shifts do not fill the block, which in the static merge is anywhere (coverage 1 for its share); staticAlongEdge 0 = off
    if((b>1?tune.staticAlongEdge:tune.staticBayerAlongEdge)>0.f&&tune.mosaicAlongEdge>0.f){
        const auto a0=Clock::now();
        const int s2=b>=4?2:1,g=std::max(1,int(std::lround(double(RW)/std::max(W,1))));
        const float K=std::clamp(s2>1?tune.mosaicTetraAlongEdgeK:tune.mosaicAlongEdgeK,0.01f,1000.f);
        const std::vector<float> one(size_t(RW)*size_t(RHt),1.f);
        // P627e: a Tetra result twice (staticTetraAlongEdgePasses): one pass's taps (+-2, +-4 px) left the 4 px stairs at half
        // (tetra_t3 0.157 -> 0.094, car 0.104 -> 0.085 with the priors of 32 frames)
        size_t changed=0;
        const int passes=b>=4?std::clamp(tune.staticTetraAlongEdgePasses,1,4):1;
        for(int pass=0;pass<passes;++pass)
            changed+=hybridAlongEdge(out,RW,RHt,one,(input.cfa&1)*g,(input.cfa>>1)*g,K,std::min(tune.mosaicAlongEdge,b>1?tune.staticAlongEdge:tune.staticBayerAlongEdge),s2);
        report("HYBRID STATIC MOSAIC: along-edge smoothing (taps step "+std::to_string(s2)+"): "+std::to_string(changed)+" px, "+std::to_string(int(ms(a0,Clock::now())))+" ms");
    }
    // P627b: Tetra (P = 8, the GPU pass is Quad's) and Quad without the GPU: the same lattice pass on the CPU
    if(tune.staticQuadLattice!=0&&(b==4||(b==2&&tune.chromaGpu<=0))){
        const auto q0=Clock::now();
        stLatticeCpu(out,RW,RHt,std::max(2,int(std::lround(2.0*b*RW/std::max(W,1)))));
        report("HYBRID STATIC MOSAIC: "+std::string(b==4?"Tetra":"Quad")+" lattice off the luma (CPU, period "+std::to_string(2*b)+"): "+std::to_string(int(ms(q0,Clock::now())))+" ms");
    }
    if(b==2&&tune.staticQuadLattice!=0&&tune.chromaGpu>0){
        const auto q0=Clock::now();
        try{auto gq=chromaTake();
            if(const uint64_t avail=hybridMemAvailable()){const int64_t rows=int64_t(avail/4/(uint64_t(RW)*36))-2*ChromaMedianGpu::kQlHalo;
                gq->qlStrip=int(std::max<int64_t>(64,std::min<int64_t>(1024,rows)));}
            const std::string t=gq->quadLattice(out,RW,RHt);chromaKeep(std::move(gq));
            report("HYBRID STATIC MOSAIC: Quad lattice off the luma: "+t+", "+std::to_string(int(ms(q0,Clock::now())))+" ms");}
        catch(const std::exception& e){report(std::string("HYBRID STATIC MOSAIC: Quad lattice pass skipped: ")+e.what());}
    }
    if(const float amount=std::max(0.f,b>1?tune.staticDetail:tune.staticBayerDetail);amount>0.f||(b>1&&tune.staticDetailShrink>0.f)){ // P627f, last
        const auto t0=Clock::now();
        const float shrink=b>1?std::max(0.f,tune.staticDetailShrink):0.f;
        const float k=stDetail(out,RW,RHt,amount,shrink,std::max(0.f,tune.staticDetailCore),tune.staticDetailSigma);
        char t[200];std::snprintf(t,sizeof(t),"HYBRID STATIC DETAIL: mid band x%.2f above %.1f sigma, fine grain shrunk below %.1f sigma (noise at level 1 %.5f), %d ms",
            double(1.f+amount),double(tune.staticDetailCore),double(shrink),double(k),int(ms(t0,Clock::now())));
        report(t);
    }
    // ---- the worker's side outputs
    if(effMap){effMap->assign(size_t(RW)*size_t(RHt),64);
        char line[160];std::snprintf(line,sizeof(line),"HYBRID EFFECTIVE MAP: median frames=%.3f (static merge: every frame everywhere) code scale=%.6f",float(K),64.f/float(K));
        report(line);}
    if(clipFlags){
        clipFlags->assign(size_t(RW)*size_t(RHt),0);
        long c[3]{};
        for(size_t i=0;i<clipFlags->size();++i){uint8_t v=0;for(int ch=0;ch<3;++ch)if(out[i*3+size_t(ch)]>=0.94f){v|=uint8_t(1<<ch);++c[ch];}(*clipFlags)[i]=v;}
        report("HYBRID CLIP FLAGS: trailer "+std::to_string(RW)+"x"+std::to_string(RHt)+" uint8 (static merge: bits R,G,B >= 0.94) R="+std::to_string(c[0])
            +" G="+std::to_string(c[1])+" B="+std::to_string(c[2])+" border=0");
    }
    if(mergedDng&&input.mergedDng){
        const int dngBlock=b>1&&tune.dngMosaic!=0?b:1; // P267: the merged mosaic layout (no remosaic)
        if(dngBlock>1)report("HYBRID DNG: merged mosaic layout, colour block "+std::to_string(dngBlock)+" (dngMosaic 1, no remosaic)");
        const double kx=double(RW)/W,ky=double(RHt)/H;
        auto at=[&](int x,int y,int c){
            if(RW==W&&RHt==H)return out[(size_t(y)*W+x)*3+size_t(c)];
            const double x0=x*kx,x1=(x+1)*kx,yy0=y*ky,yy1=(y+1)*ky;double sum=0,wsum=0;
            for(int oy=int(yy0);oy<std::min(RHt,int(std::ceil(yy1)));++oy){const double wy=std::min(yy1,oy+1.0)-std::max(yy0,double(oy));if(wy<=0)continue;
                for(int ox=int(x0);ox<std::min(RW,int(std::ceil(x1)));++ox){const double wx=std::min(x1,ox+1.0)-std::max(x0,double(ox));if(wx<=0)continue;
                    sum+=wx*wy*out[(size_t(oy)*RW+ox)*3+size_t(c)];wsum+=wx*wy;}}
            return float(sum/std::max(wsum,1e-12));
        };
        const float headroom=hybridWriteMergedDng(*mergedDng,W,H,input,at,tune.dngDither!=0,dngBlock);
        report(hybridDngReport(headroom,W,H,hybridDngCeiling(input),tune.dngDither!=0));
    }
    // ---- report (the SHOT SUMMARY reads these lines)
    {
        std::string line="HYBRID STATIC FIELD: |translation| mean/max RAW px per frame (Gauss-Newton on the tiles)";
        for(int j=1;j<K;++j){double sm=0,mx=0;const size_t nb=size_t(ntx)*size_t(nty);
            for(size_t i=0;i<nb;++i){const double v=std::hypot(fld[(size_t(j)*nb+i)*2],fld[(size_t(j)*nb+i)*2+1]);sm+=v;mx=std::max(mx,v);}
            char t[48];std::snprintf(t,sizeof(t)," %d:%.3f/%.2f",fidx[size_t(use[size_t(j)])],sm/double(nb),mx);line+=t;}
        report(line);
        line="HYBRID STATIC DATA: rejected % / residual (1 = noise) per frame, last iteration";
        for(int j=0;j<K;++j){const double v=std::max<double>(1,counters[size_t(2*j)]);
            char t[48];std::snprintf(t,sizeof(t)," %d:%.2f/%.2f",fidx[size_t(use[size_t(j)])],100.0*counters[size_t(2*j+1)]/v,counters[size_t(2*K+j)]/256.0/v);line+=t;}
        report(line);
    }
    if(!res.passMs.empty()){
        std::string line="HYBRID STATIC GPU ms (timestamps, all strips):";
        for(const auto& e:res.passMs){char t[48];std::snprintf(t,sizeof(t)," %s=%.1f",e.first.c_str(),e.second);line+=t;}
        report(line);
    }
    const auto tEnd=Clock::now();
    {
        char t[700];
        std::snprintf(t,sizeof(t),"HYBRID STATIC MERGE: %s, %d frames of %d taken (%d moving; %d of the burst not taken: other roles / above staticFrames), %dx%d -> %dx%d (%s, solve x%d, %s), %d strips of %d rows, "
            "%d iterations x %d data steps, chroma %.2f luma %.2f huber %.2f robust %.1f tile %d; ms: align %.0f, check %.0f, gpu choice %.0f, "
            "uploads %.0f, strips %.0f, total %.0f",
            res.backend.c_str(),K,int(fidx.size()),movingFrames,others,W,H,RW,RHt,hr?(RW==2*W?"2x":"1.5x"):"1x",s,b==1?"Bayer":b==2?"Quad":"Tetra",strips,R,T,Z,tune.staticChroma,tune.staticLuma,
            tune.staticHuber,tune.staticRobust,tile,ms(t0,tAlign),ms(tAlign,tCheck),res.initMs,uploadMs,waitMs,ms(t0,tEnd));
        report(t);
    }
    HybridStats stats;stats.mergeMs=ms(t0,tEnd);stats.alignMs=ms(t0,tAlign);stats.merged=K;stats.mergedNormals=K;
    hybridSeriesMerged(tune,input,stats.merged,stats.mergedNormals,report);
    report("HYBRID STAGES ms: align="+std::to_string(stats.alignMs)+" localAlign=0 mask=0 merge="+std::to_string(stats.mergeMs)+" total="
        +std::to_string(ms(t0,tEnd))+" merged="+std::to_string(K)+" droppedBracketed=0 bento=0 (static merge)");
    if(statsOut)*statsOut=stats;
    return out;
}

} // namespace scam
