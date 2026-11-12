// P624 «Статика»: the static merge route on the host GPUs (Mesa: llvmpipe for GLES, lavapipe for Vulkan). Checks:
//   - a static hand-held burst with known sub-pixel motion and our noise model: the static merge beats the Sabre routes
//     («Быстрый», «Качество») in PSNR against the truth by a margin, on GLES and on Vulkan;
//   - the Gauss-Newton field converges: a frame whose homography is off by a known shift ends with the opposite field;
//   - P627: a burst with a moving object stays on «Статика» (no other route's line) and does not ghost; a burst where every frame
//     moves is the base frame's own reconstruction; Quad (2x2) and Tetra (4x4) bursts with a per-site response and a DCG burst with
//     two noise zones are merged by the route itself and measured against «Качество» / «Быстрый» (and Quad against a
//     remosaic-to-Bayer front end); the CPU transcription equals GLES; a GLES failure mid-shot reruns on the CPU, still «Статика»;
//   - Vulkan and GLES give the same result within kStSelfTestTol, the self-test passes on both, and a strip split (several
//     strips with margins) equals one strip within a tight tolerance; the strip guard leaves no stamp.
// No Vulkan ICD (no lavapipe): the Vulkan parts are skipped with a message, the GLES parts still run.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <random>
#include <sstream>

using namespace scam;

static int failures=0;
static void expect(bool ok,const std::string& what){if(!ok){std::printf("FAIL %s\n",what.c_str());++failures;}else std::printf("ok   %s\n",what.c_str());}

// the scene: a 1/f texture (24 sinusoids of random direction, frequency up to 0.45 cycles / RAW px, amplitude ~ 1/f), colour
// that varies slowly, an edge and a row of fine strokes - linear 0..1
static double scene(double x,double y,int c){
    static std::vector<std::array<double,4>> waves;
    if(waves.empty()){std::mt19937 r(7);std::uniform_real_distribution<double> u(0,1);
        for(int i=0;i<24;++i){const double f=0.02+0.43*u(r)*u(r),a=2*M_PI*u(r);waves.push_back({f*std::cos(a)*2*M_PI,f*std::sin(a)*2*M_PI,0.012/(f+0.03),2*M_PI*u(r)});}}
    double v=0.2+0.05*std::sin(0.02*x+0.6*c)+0.04*std::cos(0.017*y-c);
    for(const auto& w:waves)v+=w[2]*std::sin(w[0]*x+w[1]*y+w[3]);
    if(y>180&&y<200)v+=0.12*(std::fmod(std::floor(x/2.5),2.0)>0.5?1:-1); // strokes of 2.5 px
    v+=x+0.4*y>300?0.1:0.0;
    return std::clamp(v*(c==0?0.55:c==2?0.7:1.0),0.005,0.95);
}
struct SynBurst { int w=0,h=0,block=1;std::vector<std::vector<uint16_t>> raw;std::vector<float> truth;std::vector<std::array<double,2>> shift; };
// K frames, frame k sees the scene at u + shift_k (box over the pixel: 4 x 4 sub-samples); an object (moving > 0) moves. P627:
// block b - the colour of b x b blocks (RGGB order of the blocks, Quad 2 / Tetra 4), each of the 64 site classes of the 8 x 8
// period with its own response (1 +- siteSpread, the sensor's: the merge divides it out); dcgKnee > 0 - the read noise offset
// above the knee is dcgOl (the DCG's LCG zone, smoothstep over 0.9 .. 1.1 x the knee); hand: the shift's sigma (0 = a tripod)
static SynBurst makeBurst(int w,int h,int K,float slope,float offset,uint32_t seed,double moving=0,int block=1,double siteSpread=0,
                          double dcgKnee=0,double dcgOl=0,double hand=1.3){
    SynBurst b;b.w=w;b.h=h;b.block=block;
    std::mt19937 rng(seed);std::normal_distribution<double> n01(0,1),handN(0,std::max(hand,1e-9));
    for(int k=0;k<K;++k)b.shift.push_back(k==0||hand<=0?std::array<double,2>{0,0}:std::array<double,2>{handN(rng),handN(rng)});
    double site[64];{std::mt19937 r2(seed^0x5eed);std::uniform_real_distribution<double> u(-1,1);for(double& g:site)g=1.0+siteSpread*u(r2);}
    auto value=[&](double x,double y,int c,int k){
        if(moving>0){const double ox=60+moving*k;if(x>ox&&x<ox+90&&y>60&&y<170)return 0.6-0.2*((int(x-ox)/6+int(y)/6)%2);}
        return scene(x,y,c);
    };
    for(int k=0;k<K;++k){
        std::vector<uint16_t> f(size_t(w)*h);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            const int bx=(x/block)&1,by=(y/block)&1;
            const int c=bx+by==0?0:bx+by==2?2:1;
            double v=0;
            for(int j=0;j<4;++j)for(int i=0;i<4;++i)v+=value(x+(i+0.5)/4+b.shift[size_t(k)][0],y+(j+0.5)/4+b.shift[size_t(k)][1],c,k);
            v/=16;
            double off=offset;
            if(dcgKnee>0){const double t=std::clamp((v-0.9*dcgKnee)/(0.2*dcgKnee),0.0,1.0);off+=(dcgOl-offset)*t*t*(3-2*t);}
            v+=n01(rng)*std::sqrt(slope*v+off);
            v*=site[((y&7)<<3)|(x&7)];
            f[size_t(y)*w+x]=uint16_t(std::clamp(std::lround(64+v*959),0L,1023L));
        }
        b.raw.push_back(std::move(f));
    }
    b.truth.resize(size_t(w)*h*3);
    for(int y=0;y<h;++y)for(int x=0;x<w;++x)for(int c=0;c<3;++c){
        double v=0;for(int j=0;j<4;++j)for(int i=0;i<4;++i)v+=scene(x+(i+0.5)/4,y+(j+0.5)/4,c);
        b.truth[(size_t(y)*w+x)*3+size_t(c)]=float(v/16);
    }
    return b;
}
// PSNR against the truth after a per-channel gain (16 px borders out)
static double psnr(const std::vector<float>& img,const SynBurst& b){
    if(img.size()<b.truth.size())return 0;
    double se=0,g[3];long n=0;
    for(int c=0;c<3;++c){double num=0,den=0;
        for(int y=16;y<b.h-16;++y)for(int x=16;x<b.w-16;++x){const size_t i=(size_t(y)*b.w+x)*3+size_t(c);num+=double(img[i])*b.truth[i];den+=double(img[i])*img[i];}
        g[c]=den>0?num/den:1;}
    for(int y=16;y<b.h-16;++y)for(int x=16;x<b.w-16;++x)for(int c=0;c<3;++c){const size_t i=(size_t(y)*b.w+x)*3+size_t(c);const double d=g[c]*img[i]-b.truth[i];se+=d*d;++n;}
    return 10*std::log10(0.95*0.95/std::max(se/n,1e-20));
}
static HybridInput inputOf(const SynBurst& b,float slope,float offset){
    HybridInput in;in.w=b.w;in.h=b.h;in.cfa=0;in.white=1023;in.black.fill(64);in.mosaic=b.block;in.grid=1;
    for(size_t k=0;k<b.raw.size();++k){HybridFrame f;f.raw=b.raw[k].data();f.role=kRoleNormal;f.exposure=1;f.iso=400;f.slope=slope;f.offset=offset;f.orderMs=33.f*k;in.frames.push_back(f);}
    return in;
}
struct Run { std::vector<float> rgb;std::string log;bool threw=false; };
static Run merge(const HybridInput& in,const HybridTuning& t){
    Run r;ScamAlignment align=[](Burst&){return std::array<BackwardHomography,7>{};};
    try{r.rgb=hybridReconstruct(in,t,align,[&](const std::string& l){r.log+=l+"\n";},nullptr,nullptr,nullptr,nullptr);}
    catch(const std::exception& e){r.threw=true;r.log+=std::string("THREW ")+e.what()+"\n";}
    return r;
}
static bool has(const std::string& log,const char* s){return log.find(s)!=std::string::npos;}
// the mean rejected share (%) of the frames >= 1 in the last «HYBRID STATIC DATA: rejected %» line
static double meanRejected(const std::string& log){
    const size_t at=log.rfind("HYBRID STATIC DATA: rejected");if(at==std::string::npos)return -1;
    std::istringstream ls(log.substr(log.find("iteration",at)+9,log.find('\n',at)-log.find("iteration",at)-9));
    std::string t;double sum=0;int n=0;
    while(ls>>t){const size_t c=t.find(':'),sl=t.find('/');if(c==std::string::npos||sl==std::string::npos)continue;
        if(std::atoi(t.substr(0,c).c_str())==0)continue;sum+=std::atof(t.substr(c+1,sl-c-1).c_str());++n;}
    return n?sum/n:0;
}
static void lines(const std::string& log,const char* key){std::istringstream ls(log);std::string l;while(std::getline(ls,l))if(l.find(key)!=std::string::npos)std::printf("  %s\n",l.substr(0,300).c_str());}
// P627: no other route made the photo (the SCAM merge's / the fast route's own lines)
static bool staysStatic(const Run& r){
    size_t n=0;for(size_t at=r.log.find("HYBRID STAGES ms:");at!=std::string::npos;at=r.log.find("HYBRID STAGES ms:",at+1))++n;
    return !r.threw&&n==1&&has(r.log,"(static merge)")&&has(r.log,"HYBRID ROUTE: «Статика»")&&!has(r.log,"the SCAM merge");}
// P627b: the colour checker at the colour-block period (2b): the colour differences R - G, B - G (less the truth's) demodulated by
// the blocks' checker sign and averaged over 4b x 4b, mean |.| over the frame (16 px borders out) - the artifact of quad_bm / tetra_tc
static double blockChecker(const std::vector<float>& img,const SynBurst& b,int blk){
    const int w=b.w,h=b.h,k=4*blk;double sum=0;long n=0;
    for(int y0=16;y0+k<=h-16;y0+=k)for(int x0=16;x0+k<=w-16;x0+=k)for(int c:{0,2}){
        double a=0;for(int y=y0;y<y0+k;++y)for(int x=x0;x<x0+k;++x){const size_t i=(size_t(y)*w+x)*3;
            const double d=(img[i+size_t(c)]-img[i+1])-(b.truth[i+size_t(c)]-b.truth[i+1]);a+=((x/blk+y/blk)&1)?-d:d;}
        sum+=std::abs(a)/(k*k);++n;}
    return n?sum/n:0;
}
// PSNR of the truth inside a box (the moving object's place in the base frame)
static double psnrBox(const std::vector<float>& img,const SynBurst& b,int x0,int y0,int x1,int y1){
    double se=0;long n=0;
    for(int y=y0;y<y1;++y)for(int x=x0;x<x1;++x)for(int c=0;c<3;++c){const size_t i=(size_t(y)*b.w+x)*3+size_t(c);const double d=img[i]-b.truth[i];se+=d*d;++n;}
    return 10*std::log10(0.95*0.95/std::max(se/std::max(n,1L),1e-20));
}
// P627 measurement: a remosaic-to-Bayer front end for a colour-block burst - every site that is not the Bayer colour of its place
// gets that colour from the nearest same-colour sites of the frame (inverse-distance over the 2b + 1 window) - then the plain
// Bayer static merge; against the observation model on the colour blocks themselves
static SynBurst remosaicToBayer(const SynBurst& q){
    SynBurst o=q;o.block=1;const int b=q.block,r=b;
    for(size_t k=0;k<q.raw.size();++k){
        const std::vector<uint16_t>& f=q.raw[k];std::vector<uint16_t>& d=o.raw[k];
        for(int y=0;y<q.h;++y)for(int x=0;x<q.w;++x){
            const int want=((y&1)+(x&1))==0?0:((y&1)&&(x&1))?2:1;
            auto colourAt=[&](int xx,int yy){const int bx=(xx/b)&1,by=(yy/b)&1;return bx+by==0?0:bx+by==2?2:1;};
            if(colourAt(x,y)==want){d[size_t(y)*q.w+x]=f[size_t(y)*q.w+x];continue;}
            double s=0,ws=0;
            for(int j=-r;j<=r;++j)for(int i=-r;i<=r;++i){const int xx=x+i,yy=y+j;
                if(xx<0||yy<0||xx>=q.w||yy>=q.h||colourAt(xx,yy)!=want)continue;
                const double w=1.0/(0.5+i*i+j*j);s+=w*f[size_t(yy)*q.w+xx];ws+=w;}
            d[size_t(y)*q.w+x]=uint16_t(ws>0?std::lround(s/ws):f[size_t(y)*q.w+x]);
        }
    }
    return o;
}

// P634: the still-scene check on colour-block thumbnails (hybridStaticMoving with a tolerance). A still scene whose frame is
// shifted by 3.3 thumbnail px: a bright band leaves the window (the whole thumbnails' means differ by a few %), a flat sky at the
// noise of a 64-site mean (ISO 50), a colonnade of 2.3 thumbnail px columns. Without the tolerance (before) a large share of
// its blocks «move»; with it none, and the level is the aligned blocks' (1); a patch that really changed by 10 % still moves.
static void checkStillSceneCheck(){
    using namespace staticmerge;
    const int tw=128,th=96;const double sh=3.3;
    auto sceneT=[&](double x,double y){
        if(x<14.0)return 0.9;                                   // the band that leaves the window
        if(y<40.0)return 0.5+0.0008*x;                          // sky
        return 0.3+0.1*std::sin(2*M_PI*x/2.3)+0.03*std::sin(0.3*y); // colonnade
    };
    auto thumb=[&](double dx,bool patch,uint32_t seed){
        FastThumb t;t.w=tw;t.h=th;t.v.assign(size_t(tw)*th,0.f);
        std::mt19937 rng(seed);std::normal_distribution<double> n01(0,1);double sum=0;
        for(int y=0;y<th;++y)for(int x=0;x<tw;++x){
            double v=0;for(int i=0;i<8;++i)v+=sceneT(x+(i+0.5)/8+dx,y+0.5);
            v/=8;
            if(patch&&x>=60&&x<76&&y>=10&&y<26)v*=1.10;
            v+=n01(rng)*std::sqrt(2.8e-4*v/64.0);
            t.v[size_t(y)*tw+x]=float(v);sum+=v;
        }
        t.mean=float(sum/double(tw*th));return t;
    };
    const FastThumb tb=thumb(0,false,1),tf=thumb(sh,false,2),tm=thumb(sh,true,3);
    const M3 Hinv{1,0,8.0*sh,0,1,0,0,0,1}; // frame thumbnail centre (RAW px) -> base RAW px
    float level=0.f;
    const float before=hybridStaticMoving(tb,tf,Hinv,2.8e-4f,0.f,64.f,0.f);
    const float after=hybridStaticMoving(tb,tf,Hinv,2.8e-4f,0.f,64.f,0.005f,&level);
    const float mover=hybridStaticMoving(tb,tm,Hinv,2.8e-4f,0.f,64.f,0.005f);
    std::printf("still-scene check (P634): moving blocks before %.3f, with the tolerance %.3f (level %.4f), with a 10 %% patch %.3f\n",before,after,level,mover);
    expect(before>0.03f,"P634: without the tolerance the still scene's shifted frame counts as moving (the case)");
    expect(after<=0.005f&&std::abs(level-1.f)<0.003f,"P634: with the tolerance no block moves and the level is the aligned blocks'");
    expect(mover>=0.02f&&mover<=0.08f,"P634: a patch that changed by 10 % still moves (its blocks alone)");
    expect(HybridTuning().staticBlockTol==0.005f,"P634: staticBlockTol 0.005 by default");
}

// P634b: the lattice pass leaves lines (a colonnade of the colour period, column-only) and takes the two-axis lattice; the detail
// pass's added part loses what is locked to the colour period and keeps the rest
static void checkStaticStripes(){
    const int W=256,H=192,P=8;const size_t n=size_t(W)*H;
    auto amp=[&](const std::vector<float>& y,const std::vector<float>& pat){double a=0,b=0;for(size_t i=0;i<n;++i){a+=double(y[i])*pat[i];b+=double(pat[i])*pat[i];}return a/b;};
    std::vector<float> stripes(n),checker(n),bump(n);
    for(int y=0;y<H;++y)for(int x=0;x<W;++x){
        const size_t i=size_t(y)*W+x;
        stripes[i]=float(std::sin(2*M_PI*x/8.0));                          // column-only, the period
        checker[i]=(((x/4)+(y/4))&1)?1.f:-1.f;                             // both axes, the period
        bump[i]=float(std::sin(2*M_PI*x/23.0)*std::cos(2*M_PI*y/31.0));    // not of the period
    }
    auto run=[&](bool lines){
        std::vector<float> rgb(n*3);
        for(size_t i=0;i<n;++i){const float v=0.4f+0.02f*stripes[i]+0.004f*checker[i];rgb[3*i]=rgb[3*i+1]=rgb[3*i+2]=v;}
        stLatticeCpu(rgb,W,H,P,lines);
        std::vector<float> y(n);for(size_t i=0;i<n;++i)y[i]=rgb[3*i+1]-0.4f;
        return std::array<double,2>{amp(y,stripes)/0.02,amp(y,checker)/0.004};
    };
    const auto was=run(false),now=run(true);
    std::printf("lattice pass (P634b): a colonnade of the period kept %.2f -> %.2f, the two-axis lattice left %.2f -> %.2f\n",was[0],now[0],was[1],now[1]);
    expect(was[0]<0.6,"P634b: without it the lattice pass takes a colonnade of the colour period (the case)");
    expect(now[0]>0.95&&now[1]<0.2,"P634b: stLatticeCpu lines keeps the column-only part and takes the two-axis lattice");
    std::vector<float> yb(n,0.4f),rgb(n*3);
    for(size_t i=0;i<n;++i){const float v=0.4f+0.006f*stripes[i]+0.006f*bump[i];rgb[3*i]=rgb[3*i+1]=rgb[3*i+2]=v;}
    stDetailLatticeOff(rgb,yb,W,H,P);
    std::vector<float> d(n);for(size_t i=0;i<n;++i)d[i]=rgb[3*i+1]-0.4f;
    const double ks=amp(d,stripes)/0.006,kb=amp(d,bump)/0.006;
    std::printf("detail pass's added part (P634b): of the colour period %.2f left, the rest %.2f kept\n",ks,kb);
    expect(ks<0.1&&kb>0.9,"P634b: stDetailLatticeOff takes the added part locked to the colour period and keeps the rest");
    expect(HybridTuning().staticLatticeLines==1&&HybridTuning().staticDetailLattice==1,"P634b: on by default");
}

int main(){
    using namespace staticmerge;
    checkStillSceneCheck();
    checkStaticStripes();
    const float slope=2.4e-3f,offset=4e-6f; // ~ISO 400 of the Phase 1 tuning
    const SynBurst b=makeBurst(320,256,12,slope,offset,624);
    const HybridInput in=inputOf(b,slope,offset);
    HybridTuning base;base.grid=1;base.auto2x=0;base.caCorrect=0;base.chromaGpu=0;base.chromaOverlap=0;
    HybridTuning quality=base,fast=base,stat=base;
    fast.fastMerge=1;stat.staticMerge=1;
    { // P634: «Быстрый» on Tetra - the few-frame pass acts on the moving object's place, the still part of the frame stays
      const SynBurst bt=makeBurst(320,256,10,slope,offset,634,6.0,4,0.02);
      const HybridInput it=inputOf(bt,slope,offset);
      HybridTuning off=fast;off.fastTetraPost=0;off.fastTetraClipNotch=0;
      const Run on=merge(it,fast),was=merge(it,off);
      size_t movedObj=0,movedStill=0,nObj=0,nStill=0;
      if(on.rgb.size()==was.rgb.size()&&on.rgb.size()==size_t(bt.w)*bt.h*3)
          for(int y=8;y<bt.h-8;++y)for(int x=8;x<bt.w-8;++x){
              const size_t i=(size_t(y)*bt.w+x)*3;
              const bool ch=on.rgb[i]!=was.rgb[i]||on.rgb[i+1]!=was.rgb[i+1]||on.rgb[i+2]!=was.rgb[i+2];
              if(y>=205){++nStill;movedStill+=ch;} else if(x>=60&&x<210&&y>=60&&y<170){++nObj;movedObj+=ch;}
          }
      const double so=nObj?double(movedObj)/double(nObj):0,ss=nStill?double(movedStill)/double(nStill):1;
      const double pOn=psnr(on.rgb,bt),pWas=psnr(was.rgb,bt),bOn=psnrBox(on.rgb,bt,60,60,150,170),bWas=psnrBox(was.rgb,bt,60,60,150,170);
      std::printf("fast Tetra few-frame pass (P634): changed %.1f %% of the object's track, %.2f %% of the still rows; PSNR %.2f -> %.2f dB, the object's place %.2f -> %.2f dB\n",
          100*so,100*ss,pWas,pOn,bWas,bOn);
      if(!has(on.log,"Tetra few-frame pass (P634)"))lines(on.log,"FAST MERGE");
      expect(!on.threw&&!was.threw&&has(on.log,"Tetra few-frame pass (P634)")&&!has(was.log,"Tetra few-frame pass (P634)"),"P634: the fast route's Tetra few-frame pass runs, fastTetraPost 0 leaves it out");
      expect(so>0.03&&ss<0.02,"P634: it acts on the moving object's track, the still rows stay as merged");
      expect(pOn>=pWas-0.05&&bOn>=bWas-0.1,"P634: no loss against the truth (whole frame, the object's place)");
    }
    const Run rq=merge(in,quality),rf=merge(in,fast);
    HybridTuning statGl=stat;statGl.staticVulkan=0;
    const Run rs=merge(in,statGl);
    const double pq=psnr(rq.rgb,b),pf=psnr(rf.rgb,b),ps=psnr(rs.rgb,b);
    std::printf("PSNR: quality %.2f fast %.2f static(GLES) %.2f dB\n",pq,pf,ps);
    if(const char* d=std::getenv("STATIC_DUMP")){ // debugging: the results and the truth as float32 RGB
        auto dump=[&](const char* n,const std::vector<float>& v){std::FILE* f=std::fopen((std::string(d)+"/"+n+".f32").c_str(),"wb");if(f){std::fwrite(v.data(),4,v.size(),f);std::fclose(f);}};
        dump("quality",rq.rgb);dump("fast",rf.rgb);dump("static",rs.rgb);dump("truth",b.truth);}
    if(!has(rs.log,"HYBRID STATIC MERGE: GLES")){std::istringstream ls(rs.log);std::string l;while(std::getline(ls,l))if(l.find("STATIC")!=std::string::npos)std::printf("  %s\n",l.c_str());}
    expect(!rs.threw&&has(rs.log,"HYBRID STATIC MERGE: GLES"),"the static merge ran on GLES");
    expect(ps>=std::max(pq,pf)+0.5,"static beats both Sabre routes by 0.5 dB (static "+std::to_string(ps)+", best Sabre "+std::to_string(std::max(pq,pf))+")");
    expect(has(rs.log,"HYBRID STATIC FIELD")&&has(rs.log,"HYBRID STATIC DATA")&&has(rs.log,"HYBRID STATIC CHECK"),"the report lines");
    { HybridTuning p0=statGl;p0.staticPlan=0; // P639: the route's short schedule against the P624 one
      const Run r0=merge(in,p0);const double ps0=psnr(r0.rgb,b);
      std::printf("P624 schedule: static(GLES) %.2f dB (the short schedule %.2f)\n",ps0,ps);
      expect(has(r0.log,"8 iterations x 3 data steps")&&has(rs.log,"2 blocks x 6 data steps + 2 final")&&has(rs.log,"HYBRID STATIC PACE"),"P639: the schedules and the pacing in the report");
      expect(ps>=ps0-0.05,"P639: the route on the short schedule within 0.05 dB of the P624 schedule (Bayer)"); }

    // the field converges: frame 3's homography off by +0.4 / -0.3 RAW px (frame -> base), the solve with the true others
    std::vector<std::vector<uint16_t>> keep;
    StJob job;job.W=b.w;job.H=b.h;job.cfa=0;job.white=1023;job.black={64,64,64,64};job.T=8;job.Z=3;job.tile=64;job.stripMB=64;
    for(size_t k=0;k<b.raw.size();++k){
        job.raw.push_back(b.raw[k].data());
        const double sx=b.shift[k][0],sy=b.shift[k][1],ex=k==3?0.4:0.0,ey=k==3?-0.3:0.0;
        job.Hb.push_back(M3{1,0,-sx-ex,0,1,-sy-ey,0,0,1});job.Hinv.push_back(M3{1,0,sx+ex,0,1,sy+ey,0,0,1});
        job.slope.push_back(slope);job.offset.push_back(offset);
    }
    StGles gl("");
    const StResult r1=stSolve(gl,job);
    { const size_t nb=size_t(r1.ntx)*size_t(r1.nty);double fx=0,fy=0;
      for(size_t i=0;i<nb;++i){fx+=r1.fld[(3*nb+i)*2];fy+=r1.fld[(3*nb+i)*2+1];}
      fx/=double(nb);fy/=double(nb);
      std::printf("field of frame 3: %.3f %.3f (expected -0.4 +0.3)\n",fx,fy);
      expect(std::abs(fx+0.4)<0.08&&std::abs(fy-0.3)<0.08,"the Gauss-Newton field takes the homography's error out"); }
    // strips: several strips (each with its margins) against one
    { StJob small=job;small.stripMB=32; // forces strips of a few rows
      const StResult r2=stSolve(gl,small);
      double md=0;for(size_t i=0;i<r1.out.size();++i)md=std::max(md,double(std::abs(r1.out[i]-r2.out[i])));
      std::printf("strips %d vs %d: max |difference| %.2e\n",r2.strips,r1.strips,md);
      expect(r2.strips>r1.strips&&md<0.02,"a strip split gives the one-strip result (margins)"); }

    // P639 (owner's log 2026-11-12, Adreno 830, 12 MP x 16: 153 s, the viewfinder at 2 - 11 fps, a strip dropped by the driver).
    // (1) the passes as short submissions (StBackend::slice between them): the one-submission result bit for bit
    { StJob sl=job;sl.sliceMs=0.05f;
      const StResult rsl=stSolve(gl,sl);
      double md=0;for(size_t i=0;i<r1.out.size();++i)md=std::max(md,double(std::abs(r1.out[i]-rsl.out[i])));
      double mf=0;for(size_t i=0;i<r1.fld.size();++i)mf=std::max(mf,double(std::abs(r1.fld[i]-rsl.fld[i])));
      std::printf("short submissions (P639): %ld of them, max |difference| %.2e (field %.2e)\n",rsl.slices,md,mf);
      expect(r1.slices==0&&rsl.slices>200&&md==0.0&&mf==0.0,"P639: the passes as short submissions give the one-submission result bit for bit"); }
    // (2) the short schedule (the app's default) against the P624 one on the same job: the truth's PSNR within 0.05 dB or above,
    // the field found, the result independent of the strips, of the submissions and of the backend (the CPU transcription)
    { const HybridTuning dt;
      StJob fj=job;fj.plan=1;fj.blocks=dt.staticBlocks;fj.blockSteps=dt.staticBlockSteps;fj.finalSteps=dt.staticFinalSteps;fj.fieldHalf=dt.staticFieldHalf;
      const StResult rp=stSolve(gl,fj);
      const double p0=psnr(r1.out,b),p1=psnr(rp.out,b);
      const size_t nb=size_t(rp.ntx)*size_t(rp.nty);double fx=0,fy=0;
      for(size_t i=0;i<nb;++i){fx+=rp.fld[(3*nb+i)*2];fy+=rp.fld[(3*nb+i)*2+1];}
      fx/=double(nb);fy/=double(nb);
      std::printf("short schedule (P639): %.2f dB against the P624 schedule's %.2f dB; field of frame 3 %.3f %.3f (expected -0.4 +0.3)\n",p1,p0,fx,fy);
      expect(dt.staticPlan==1,"P639: the short schedule is the default");
      expect(p1>=p0-0.05,"P639: the short schedule within 0.05 dB of the P624 schedule (or above)");
      expect(std::abs(fx+0.4)<0.08&&std::abs(fy-0.3)<0.08,"P639: the field of the short schedule takes the homography's error out");
      StJob fs=fj;fs.stripMB=32;
      const StResult rp2=stSolve(gl,fs);
      double md=0;for(size_t i=0;i<rp.out.size();++i)md=std::max(md,double(std::abs(rp.out[i]-rp2.out[i])));
      StJob fl=fj;fl.sliceMs=0.05f;
      const StResult rp3=stSolve(gl,fl);
      double ms2=0;for(size_t i=0;i<rp.out.size();++i)ms2=std::max(ms2,double(std::abs(rp.out[i]-rp3.out[i])));
      StCpu cpu;
      const StResult rpc=stSolve(cpu,fj);
      double mc=0;for(size_t i=0;i<rp.out.size();++i)mc=std::max(mc,double(std::abs(rp.out[i]-rpc.out[i])));
      double mcf=0;for(size_t i=0;i<rp.fld.size();++i)mcf=std::max(mcf,double(std::abs(rp.fld[i]-rpc.fld[i])));
      std::printf("short schedule: strips %d vs %d max |difference| %.2e; short submissions (%ld) %.2e; CPU vs GLES %.2e (field %.2e)\n",
          rp2.strips,rp.strips,md,rp3.slices,ms2,mc,mcf);
      expect(rp2.strips>rp.strips&&md<0.02,"P639: the short schedule's strip split gives the one-strip result");
      expect(rp3.slices>100&&ms2==0.0,"P639: the short schedule as short submissions, bit for bit");
      expect(mc<=kStSelfTestTol&&mcf<=0.01,"P639: the short schedule's CPU transcription equals GLES within the tolerance"); }
    // (3) the slicing itself on a backend that only counts: every row of every frame dispatched once, the first submission one
    // group row of one frame, then sized to the time aimed at
    { struct Fake:StBackend{
          int rows=0,frames=0;std::vector<int> hit,units;int unit=16;double perUnitMs=0.2;int last=0,queue=1;
          using Clock=std::chrono::steady_clock;
          std::vector<Clock::time_point> done; // when each queued slice ends (the GPU runs them one after another)
          Clock::time_point free=Clock::now();
          static void until(Clock::time_point t){while(Clock::now()<t){}}
          const char* name() const override{return "fake";}
          std::string describe() const override{return "";}
          void alloc(int,size_t) override{}
          void upload(int,size_t,const void*,size_t) override{}
          void zero(int,size_t) override{}
          void begin() override{}
          void dispatch(const StDispatch& d) override{
              last=int(d.gy)*int(d.gz);units.push_back(last);
              for(int f=0;f<int(d.gz);++f)for(int r=0;r<int(d.gy);++r){
                  const int ff=d.p.frame0+f,rr=d.p.row0/unit+r;
                  if(ff>=0&&ff<frames&&rr>=0&&rr<rows)++hit[size_t(ff*rows+rr)];else units.push_back(-1);}
          }
          void copy(int,int,size_t) override{}
          void submitWait() override{}
          int inFlight() const override{return queue;}
          double slice() override{ // (busy until the simulated GPU has room: the pacing measures the wall)
              const Clock::time_point start=std::max(free,Clock::now());
              free=start+std::chrono::microseconds(long(perUnitMs*last*1000));done.push_back(free);
              while(int(done.size())>queue-1){until(done.front());done.erase(done.begin());}
              return 0;}
          void drain() override{while(!done.empty()){until(done.front());done.erase(done.begin());}}
          void read(int,size_t,size_t,void*) override{}
          void stamp(int,const std::vector<size_t>&,uint32_t) override{}
          size_t maxBuffer() const override{return size_t(1)<<30;}
      };
      bool once=true,first=true,sized=true;long total=0;
      for(int queue:{1,2})for(int kind:{0,1,2}){
          Fake f;f.queue=queue;f.rows=kind==0?90:13;f.frames=kind==0?1:15;f.unit=kind==2?1:16;f.hit.assign(size_t(f.rows*f.frames),0);
          StDispatch d;d.prog=kind==2?spField:spResid;d.gx=4;d.gy=uint32_t(f.rows);d.gz=uint32_t(f.frames);
          double rate=0,longest=0;long n=0;
          stSliced(f,d,kind,4.0,rate,n,longest);   // 4 ms a submission at 0.2 ms a unit: 20 units
          for(int h:f.hit)once=once&&h==1;
          for(int u:f.units)once=once&&u>0;
          first=first&&!f.units.empty()&&f.units[0]==1;
          std::vector<int> u(f.units.begin()+std::min<size_t>(f.units.size(),3),f.units.end());
          std::sort(u.begin(),u.end());
          const int med=u.empty()?0:u[u.size()/2];
          sized=sized&&med>=6&&med<=30;
          total+=n;
          std::printf("slicing (P639) kind %d, %d queued: %ld submissions for %d units, median %d units\n",kind,queue,n,f.rows*f.frames,med);
      }
      expect(once,"P639: every row of every frame is dispatched exactly once, whatever the split");
      expect(first,"P639: a program's first submission is one group row of one frame");
      expect(sized,"P639: the submissions are sized to the time aimed at (20 units here)");
      expect(HybridTuning().staticSliceMs>0.f&&HybridTuning().staticSliceMs<=10.f,"P639: short submissions by default");
      // the worker's alarm: 300 s per 12 MP on a GPU, 1200 s on the CPU (was 840 / 3360)
      expect(hybridStaticAlarmSeconds(4096,3072,false)==315&&hybridStaticAlarmSeconds(4096,3072,true)==1258&&hybridStaticAlarmSeconds(16320,12240,false)==3540
             &&hybridStaticAlarmSeconds(2000,1500,false)==300,"P639: the alarm 300 s per 12 MP on a GPU, 1200 s on the CPU, at most 3540 s"); }

    // P627: a moving object stays on «Статика» - the moving frames / samples left out, no ghost of the object (its place in the
    // base frame against the truth, against «Качество»'s)
    const SynBurst bm=makeBurst(320,256,12,slope,offset,625,3.0);
    const HybridInput inm=inputOf(bm,slope,offset);
    const Run rm=merge(inm,statGl),rmq=merge(inm,quality);
    const double pm=psnrBox(rm.rgb,bm,60,60,150,170),pmq=psnrBox(rmq.rgb,bm,60,60,150,170);
    std::printf("moving object: its place %.2f dB static, %.2f dB quality; whole frame %.2f / %.2f dB\n",pm,pmq,psnr(rm.rgb,bm),psnr(rmq.rgb,bm));
    if(!staysStatic(rm))lines(rm.log,"STATIC");
    expect(staysStatic(rm)&&rm.rgb.size()==size_t(bm.w)*bm.h*3,"a moving scene stays on «Статика» (no other route)");
    expect(pm>=pmq-1.0,"no ghost of the moving object (its place within 1 dB of «Качество»)");
    // most frames move (a bright disc at another place in every frame, half the frame): they are left out, the merge goes on with
    // what is left; the base frame alone (one frame taken) is still the static merge's own reconstruction
    { SynBurst ba=makeBurst(192,160,6,slope,offset,626,0,1,0,0,0,1.3);
      for(size_t k=1;k<ba.raw.size();++k)for(int y=0;y<ba.h;++y)for(int x=0;x<ba.w;++x){
          const double dx=x-(30.0+25.0*k),dy=y-80.0;uint16_t& v=ba.raw[k][size_t(y)*ba.w+x];if(dx*dx+dy*dy<70.0*70.0)v=uint16_t(std::clamp(int(v)+600,0,1023));}
      const Run ra=merge(inputOf(ba,slope,offset),statGl);
      std::printf("most frames moving: %.2f dB\n",psnr(ra.rgb,ba));
      if(!has(ra.log,"frames left of 6"))lines(ra.log,"STATIC CHECK");
      expect(staysStatic(ra)&&has(ra.log,"frames left of 6")&&psnr(ra.rgb,ba)>15,"most frames moving: left out, the static merge goes on");
      HybridTuning one=statGl;one.staticFrames=1;
      const Run r1f=merge(in,one);
      std::printf("the base frame alone: %.2f dB\n",psnr(r1f.rgb,b));
      expect(staysStatic(r1f)&&has(r1f.log,"the base frame's own reconstruction")&&psnr(r1f.rgb,b)>25,"one frame: the base frame alone through the static merge"); }

    // P627: the CPU transcription of the programs = GLES (the same job), and the route on the CPU (staticVulkan -2)
    { StCpu cpu;
      const StResult rc=stSolve(cpu,job);
      double md=0;for(size_t i=0;i<r1.out.size();++i)md=std::max(md,double(std::abs(r1.out[i]-rc.out[i])));
      double mf=0;for(size_t i=0;i<r1.fld.size();++i)mf=std::max(mf,double(std::abs(r1.fld[i]-rc.fld[i])));
      std::printf("CPU vs GLES: max |difference| %.2e (field %.2e)\n",md,mf);
      expect(md<=kStSelfTestTol&&mf<=0.01,"the CPU transcription equals GLES within the tolerance");
      HybridTuning statCpu=stat;statCpu.staticVulkan=-2;
      const Run rc2=merge(in,statCpu);
      std::printf("PSNR static(CPU) %.2f dB\n",psnr(rc2.rgb,b));
      expect(staysStatic(rc2)&&has(rc2.log,"HYBRID STATIC GPU: CPU")&&std::abs(psnr(rc2.rgb,b)-ps)<0.05,"staticVulkan -2: the route on the CPU, the GLES result"); }
    // a GLES failure mid-shot: smaller strips on GLES, then the CPU - the photo is still «Статика»'s
    { setenv("SCAM_STATIC_GLES_FAIL","1",1);
      const Run rf2=merge(in,statGl);
      unsetenv("SCAM_STATIC_GLES_FAIL");
      if(!has(rf2.log,"HYBRID STATIC GPU: CPU"))lines(rf2.log,"STATIC GPU");
      expect(staysStatic(rf2)&&has(rf2.log,"in smaller strips")&&has(rf2.log,"HYBRID STATIC GPU: CPU")&&std::abs(psnr(rf2.rgb,b)-ps)<0.05,
             "GLES failing mid-shot: GLES in smaller strips, then the CPU - still «Статика»"); }

    // P638: one strip fails once (a submission the driver gave up on): the shot again on GLES in strips of half the rows - not in
    // the smallest strips (16 rows, three rows of margin solved for every row kept: 145 s for 12 MP on the phone) - and the
    // result is the GLES one
    { setenv("SCAM_STATIC_GLES_FAIL_AT","1",1);
      const Run rh=merge(in,statGl);
      unsetenv("SCAM_STATIC_GLES_FAIL_AT");
      const int half=(std::min(b.h,512)&~1)/2&~1;
      const std::string want=" strips of "+std::to_string(half)+" rows";
      const std::string again="in smaller strips (strips of "+std::to_string(half)+" rows",ran=want+",";
      if(!has(rh.log,ran.c_str()))lines(rh.log,"STATIC");
      std::printf("one failed strip: %.2f dB in two strips (one strip %.2f dB)\n",psnr(rh.rgb,b),ps);
      // (a strip split moves the result a little: the tiles of the field are the strip's own - «a strip split gives the one-strip result»)
      expect(staysStatic(rh)&&has(rh.log,again.c_str())&&has(rh.log,"HYBRID STATIC MERGE: GLES")
             &&has(rh.log,ran.c_str())&&!has(rh.log,"HYBRID STATIC GPU: CPU")&&std::abs(psnr(rh.rgb,b)-ps)<0.3,
             "one failed GLES strip: again on GLES at half the rows, the GLES result"); }

    // P627: Quad (2x2) and Tetra (4x4) colour blocks with a +-2 % per-site response, hand-held and on a tripod; DCG (two zones)
    for(int blk:{2,4})for(double hand:{1.3,0.0}){
        const SynBurst bq=makeBurst(320,256,12,slope,offset,700+blk+(hand>0?0:10),0,blk,0.02,0,0,hand);
        const HybridInput iq=inputOf(bq,slope,offset);
        const Run qq=merge(iq,quality),qf=merge(iq,fast),qs=merge(iq,statGl);
        const double a=psnr(qq.rgb,bq),f=psnr(qf.rgb,bq),st=psnr(qs.rgb,bq);
        { HybridTuning p0=statGl;p0.staticPlan=0; // P639: the short schedule against the P624 one on the colour blocks
          const double st0=psnr(merge(iq,p0).rgb,bq);
          std::printf("  P624 schedule %.2f dB, the short schedule %.2f dB\n",st0,st);
          expect(st>=st0-0.05,std::string(blk==2?"Quad":"Tetra")+(hand>0?" hand-held":" tripod")+": P639 the short schedule within 0.05 dB of the P624 schedule"); }
        HybridTuning noPost=statGl;noPost.staticMosaicChroma=0;noPost.staticQuadLattice=0;
        const double sn=psnr(merge(iq,noPost).rgb,bq);
        HybridTuning chromaOnly=statGl;chromaOnly.staticQuadLattice=0;HybridTuning latticeOnly=statGl;latticeOnly.staticMosaicChroma=0;
        const double sc=psnr(merge(iq,chromaOnly).rgb,bq),sl=blk==2?psnr(merge(iq,latticeOnly).rgb,bq):sn;
        std::printf("  post passes: chroma median alone %.2f, lattice alone %.2f\n",sc,sl);
        lines(qs.log,"STATIC SITES");lines(qs.log,"STATIC COVERAGE");lines(qs.log,"STATIC CHECK");
        HybridTuning noSites=statGl;noSites.mosaicGain=0;
        const double sg=psnr(merge(iq,noSites).rgb,bq);
        const SynBurst rb=remosaicToBayer(bq);
        const double sr=psnr(merge(inputOf(rb,slope,offset),statGl).rgb,bq);
        std::printf("%s %s: quality %.2f fast %.2f static %.2f dB (no post passes %.2f, no site response %.2f, remosaic-to-Bayer front end %.2f)\n",
            blk==2?"Quad":"Tetra",hand>0?"hand-held":"tripod",a,f,st,sn,sg,sr);
        if(!staysStatic(qs))lines(qs.log,"STATIC");
        expect(staysStatic(qs)&&has(qs.log,blk==2?"(1x, solve x2, Quad)":"(1x, solve x2, Tetra)"),std::string(blk==2?"Quad":"Tetra")+": the static merge on the colour blocks");
        expect(has(qf.log,"(fast merge)"),std::string(blk==2?"Quad":"Tetra")+": «Быстрый» stays on the fast route");
        // hand-held: the super-resolution's margin; a tripod (one sample position) is a demosaic of the colour blocks: within 1 dB of
        // the better Sabre route (P627: Quad 27.2 / 27.6 dB fast, Tetra 23.4 / 23.1)
        expect(st>=std::max(a,f)+(hand>0?0.5:-1.0),std::string(blk==2?"Quad":"Tetra")+(hand>0?" hand-held: static beats both Sabre routes by 0.5 dB":" tripod: static within 1 dB of the better Sabre route"));
        expect(st>=sr,std::string(blk==2?"Quad":"Tetra")+": the observation model on the colour blocks beats the remosaic front end");
        const double cq=blockChecker(qq.rgb,bq,blk),cf=blockChecker(qf.rgb,bq,blk),cs=blockChecker(qs.rgb,bq,blk);
        HybridTuning p624=statGl;p624.staticChromaFloor=0;p624.staticLumaFloor=0;p624.staticMosaicChroma=0;p624.staticQuadLattice=0;p624.staticAlongEdge=0;
        const double c0=blockChecker(merge(iq,p624).rgb,bq,blk);
        std::printf("  colour checker at the block period: quality %.5f fast %.5f static %.5f (P627's passes off %.5f)\n",cq,cf,cs,c0);
        expect(cs<=std::max(cq,cf),std::string(blk==2?"Quad":"Tetra")+": no colour checker above the Sabre routes' (P627b)");
    }
    { const double knee=0.12,ol=offset*40; // DCG: the LCG zone's read noise 40x above the knee
      const SynBurst bd=makeBurst(320,256,12,slope,offset,800,0,1,0,knee,ol);
      HybridInput id=inputOf(bd,slope,offset);id.dcg=true;
      for(auto& f:id.frames){f.dcgKnee=float(knee);f.dcgOffsetLcg=float(ol);}
      HybridTuning tq=quality;tq.noiseMeasured=-1;HybridTuning tf=fast;tf.noiseMeasured=-1;HybridTuning ts=statGl;ts.noiseMeasured=-1;
      const Run dq=merge(id,tq),df=merge(id,tf),ds=merge(id,ts);
      HybridInput one=id;for(auto& f:one.frames){f.dcgKnee=0;f.dcgOffsetLcg=0;}
      const double p1=psnr(merge(one,ts).rgb,bd);
      const Run d1=merge(one,ts);
      const double rz=meanRejected(ds.log),r1=meanRejected(d1.log);
      std::printf("DCG: samples rejected as motion %.2f %% with the zones, %.2f %% with one curve\n",rz,r1);
      const double a=psnr(dq.rgb,bd),f=psnr(df.rgb,bd),st=psnr(ds.rgb,bd);
      std::printf("DCG: quality %.2f fast %.2f static %.2f dB (one noise curve %.2f)\n",a,f,st,p1);
      expect(staysStatic(ds)&&has(ds.log,"HYBRID STATIC DCG: two noise zones"),"DCG: the static merge with the two noise zones");
      expect(st>=std::max(a,f)+0.5,"DCG: static beats both Sabre routes");
      // the zones' job: the LCG zone's noise is not taken for motion (one curve under-states it above the knee); the photo within
      // 0.5 dB of the one-curve solve (P627's measurement: 36.35 / 36.62 dB - the weaker data weights above the knee smooth a little more)
      expect(rz<r1,"DCG: the two zones take less noise for motion than one curve");
      expect(st>=p1-0.5,"DCG: the two zones within 0.5 dB of one curve"); }

    // P627: «Быстрый» stays «Быстрый» - a frame past the GPU's texture size merged by the fast route in column bands (one frame
    // pick, blended seams: within 0.3 dB of the whole frame's fast merge); a fast route that fails twice fails the job with its
    // «HYBRID ROUTE» line - never the SCAM merge
    { const SynBurst bw=makeBurst(1280,192,6,slope,offset,900);
      const HybridInput iw=inputOf(bw,slope,offset);
      const Run whole=merge(iw,fast);
      setenv("SCAM_FM_MAX_TEX","768",1);
      const Run banded=merge(iw,fast);
      unsetenv("SCAM_FM_MAX_TEX");
      const double pw=psnr(whole.rgb,bw),pb=psnr(banded.rgb,bw);
      std::printf("fast route 1280 px: whole %.2f dB, column bands (texture limit 768) %.2f dB\n",pw,pb);
      if(!has(banded.log,"HYBRID FAST MERGE BANDS"))lines(banded.log,"FAST MERGE");
      expect(!banded.threw&&has(banded.log,"HYBRID FAST MERGE BANDS")&&has(banded.log,"(fast merge)")&&!has(banded.log,"the SCAM merge")
             &&std::abs(pb-pw)<0.3,"«Быстрый» past the GPU's texture size: the fast route in column bands");
      setenv("SCAM_FM_FAIL","1",1);
      const Run failed=merge(in,fast);
      unsetenv("SCAM_FM_FAIL");
      expect(failed.threw&&has(failed.log,"the fast route again on a new GPU context")&&has(failed.log,"HYBRID ROUTE: «Быстрый» could not run")
             &&!has(failed.log,"HYBRID STAGES"),"«Быстрый» failing twice: the job fails with its HYBRID ROUTE line, no other route's merge"); }

    // Vulkan (lavapipe): the same result as GLES, the self-test, the route on Vulkan
    std::unique_ptr<StVulkan> vk;
    try{vk=std::make_unique<StVulkan>("",true);}catch(const std::exception& e){std::printf("SKIP Vulkan parts: no Vulkan device (%s)\n",e.what());}
    if(vk){
        std::printf("Vulkan: %s\n",vk->describe().c_str());
        const StResult rv=stSolve(*vk,job);
        double md=0;for(size_t i=0;i<r1.out.size();++i)md=std::max(md,double(std::abs(r1.out[i]-rv.out[i])));
        std::printf("Vulkan vs GLES: max |difference| %.2e (tolerance %.0e)\n",md,double(kStSelfTestTol));
        expect(md<=kStSelfTestTol,"Vulkan equals GLES within the tolerance");
        StGles gl2(""); // (the merges above ended the display's earlier contexts: Mesa's eglTerminate ends every context of it)
        const StSelfTest t=stSelfTest(*vk,gl2);
        std::printf("self-test: vk %.1f ms gles %.1f ms diff %.2e psnr %.2f / %.2f %s\n",t.vkMs,t.glMs,t.diff,t.psnrVk,t.psnrGl,t.why.c_str());
        expect(t.ok,"the self-test passes");
        vk.reset();
        HybridTuning statVk=stat;statVk.staticVulkan=1;
        const Run rv2=merge(in,statVk);
        const double pv=psnr(rv2.rgb,b);
        std::printf("PSNR static(Vulkan) %.2f dB\n",pv);
        expect(!rv2.threw&&has(rv2.log,"HYBRID STATIC GPU: Vulkan"),"the static merge ran on Vulkan");
        expect(pv>=std::max(pq,pf)+0.5,"static on Vulkan beats both Sabre routes by 0.5 dB");
        // auto: the self-test's verdict picks a backend and says so
        HybridTuning statAuto=stat;statAuto.staticVulkan=-1;
        const Run ra=merge(in,statAuto);
        expect(!ra.threw&&has(ra.log,"HYBRID STATIC SELF-TEST")&&has(ra.log,"HYBRID STATIC GPU: "),"auto runs the self-test and picks a backend");
    }
    std::printf(failures?"check_static_merge: %d FAILED\n":"check_static_merge: all passed\n",failures);
    return failures?1:0;
}
