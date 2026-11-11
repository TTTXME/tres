// P613: a merge strip whose GPU work was lost (vivo X200 Pro, Mali-G925: five GROUP_ERROR_FATAL write faults, strips 6 - 10 dropped)
// must not reach the photo as the stale rows of its output bank (strip k - 2). Checks, on the host GPU (Mesa):
//   - the sentinel helpers: stamped rows found, written rows not;
//   - the normal merge with the strip guard is bit for bit the merge without it (RGB, effective map, clip flags);
//   - a dropped strip without the guard reproduces the phone: its rows equal the rows two strips above (the other bank's turn);
//   - a dropped strip with the guard fails the merge (no stale rows handed over), with the guard's log line;
//   - with the local alignment, a strip dropped once: the merge without F6 runs again and its rows are the clean merge's.
#define SCAM_STRIP_FAULTS 1
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <cstring>
#include <random>

using namespace scam;

static int failures=0;
static void expect(bool ok,const char* what){if(!ok){std::printf("FAIL %s\n",what);++failures;}}

int main(){
    { // the helpers
        const int ow=203,rows=5;std::vector<float> rgb(size_t(ow)*rows*3,0.25f);
        expect(hybridStripUnwritten(rgb.data(),rows,ow)==0,"unstamped strip read as unwritten");
        hybridStripStamp(rgb.data(),rows,ow);
        int first=-1;
        expect(hybridStripUnwritten(rgb.data(),rows,ow,&first)==rows&&first==0,"stamped strip not found");
        for(int r=0;r<rows;++r)if(r!=3)for(size_t k=0;k<size_t(ow)*3;++k)rgb[size_t(r)*ow*3+k]=0.5f; // every row written but row 3
        expect(hybridStripUnwritten(rgb.data(),rows,ow,&first)==1&&first==3,"a single unwritten row not found");
        rgb[size_t(3)*ow*3+size_t(ow-1)*3+1]=0.5f;rgb[size_t(3)*ow*3+1]=0.5f; // its first and last stamps written, the middle not
        expect(hybridStripUnwritten(rgb.data(),rows,ow)==1,"a row written only at its ends read as written");
        float nan=std::numeric_limits<float>::quiet_NaN();
        for(size_t k=0;k<rgb.size();++k)rgb[k]=nan;
        expect(hybridStripUnwritten(rgb.data(),rows,ow)==0,"a GPU NaN read as the sentinel");
    }
    constexpr int w=132,h=1100; // 550 cell rows: strips of 128 cells (256 rows) 0 - 3 and a short strip 4
    std::mt19937 random(613);std::vector<std::vector<uint16_t>> raw(4,std::vector<uint16_t>(size_t(w)*h));
    for(int f=0;f<4;f++)for(int y=0;y<h;y++)for(int x=0;x<w;x++){
        float signal=.15f+.08f*std::sin(x*.31f+f*.05f)*std::cos(y*.019f)+.1f*std::sin(y*.0043f);
        if(x>95&&y>300&&y<340)signal=1.2f;
        signal*=((y&1)*2+(x&1))==0?.65f:((y&1)*2+(x&1))==3?.8f:1.f;
        const float noise=(int(random()%101)-50)*.00015f;
        raw[f][size_t(y)*w+x]=uint16_t(std::lround(64+std::clamp(signal+noise,0.f,1.f)*959));
    }
    HybridInput input;input.w=w;input.h=h;input.cfa=0;input.white=1023;input.black.fill(64);input.mosaic=1;input.grid=1;
    for(int f=0;f<4;f++){
        HybridFrame frame;frame.raw=raw[f].data();frame.role=kRoleNormal;frame.exposure=1;frame.iso=400;
        frame.slope=.0003f;frame.offset=.000002f;frame.orderMs=33.f*f;input.frames.push_back(frame);
    }
    HybridTuning tune;tune.grid=1;tune.auto2x=0;tune.localAlign=0;tune.gpuAlign=0;tune.caCorrect=0;tune.chromaGpu=0;tune.chromaOverlap=0;
    tune.plainChromaMedian=0;
    ScamAlignment align=[](Burst&){return std::array<BackwardHomography,7>{};};
    struct Run{std::vector<float> rgb;std::vector<uint8_t> map,flags;bool guardLine=false,threw=false,retried=false;std::string error;};
    auto run=[&](const HybridTuning& t,int drop,int times){
        Run r;hybridDropStripAt()=drop;hybridDropStripLeft()=times;
        try{
            r.rgb=hybridReconstruct(input,t,align,[&](const std::string& line){
                if(line.find("HYBRID GPU STRIP GUARD")!=std::string::npos)r.guardLine=true;
                if(line.find("merging with the homographies only")!=std::string::npos)r.retried=true;
            },nullptr,&r.map,nullptr,&r.flags);
        }catch(const std::exception& e){r.threw=true;r.error=e.what();}
        hybridDropStripAt()=-1;hybridDropStripLeft()=0;
        return r;
    };
    const size_t row=size_t(w)*3;
    HybridTuning off=tune;off.stripGuard=0;
    const Run clean=run(off,-1,0),guarded=run(tune,-1,0);
    expect(!clean.threw&&!guarded.threw,"a clean merge failed");
    expect(clean.rgb.size()==size_t(w)*h*3,"output size");
    expect(guarded.rgb.size()==clean.rgb.size()&&std::memcmp(guarded.rgb.data(),clean.rgb.data(),clean.rgb.size()*4)==0,"the strip guard changed the RGB");
    expect(guarded.map==clean.map&&guarded.flags==clean.flags,"the strip guard changed the trailers");
    expect(!guarded.guardLine,"the strip guard spoke on a clean merge");
    // strip 2 (rows 512 - 767) dropped without the guard: the bank's last strip (0, rows 0 - 255), as on the phone
    const Run stale=run(off,2,1);
    expect(!stale.threw,"the unguarded dropped strip threw");
    int same=0,differs=0;
    if(stale.rgb.size()==clean.rgb.size())for(int y=512;y<768;++y){
        same+=std::memcmp(stale.rgb.data()+size_t(y)*row,clean.rgb.data()+size_t(y-512)*row,row*4)==0;
        differs+=std::memcmp(stale.rgb.data()+size_t(y)*row,clean.rgb.data()+size_t(y)*row,row*4)!=0;
    }
    expect(same==256&&differs>200,"the dropped strip did not repeat the strip two above (the phone's stripes)");
    // the same with the guard: the merge fails, nothing stale is returned
    const Run caught=run(tune,2,1);
    expect(caught.threw&&caught.guardLine&&caught.error.find("not written")!=std::string::npos,"the guard let a dropped strip through");
    expect(caught.rgb.empty(),"the guarded merge returned an image");
    // the last, short strip dropped: its bank held strip 2
    const Run lastStrip=run(tune,4,1);
    expect(lastStrip.threw&&lastStrip.guardLine,"the guard missed the last strip");
    // with the local alignment: dropped once, the retry without F6 gives a full, clean image
    HybridTuning la=tune;la.localAlign=1;la.laGpu=0;
    HybridTuning laNoF6=la;laNoF6.localAlign=0;
    const Run laClean=run(laNoF6,-1,0),laRetry=run(la,1,1);
    expect(!laRetry.threw&&laRetry.guardLine&&laRetry.retried,"the local-alignment merge did not retry without F6");
    expect(laRetry.rgb.size()==laClean.rgb.size()&&std::memcmp(laRetry.rgb.data(),laClean.rgb.data(),laClean.rgb.size()*4)==0,"the retry without F6 is not the clean merge without F6");
    std::printf("check_hybrid_strip_guard: helpers, clean merge bit for bit with the guard, dropped strip = rows 512 above without it (%d of 256 rows), "
                "caught with it (%s), retry without F6 clean; %d failures\n",same,caught.error.c_str(),failures);
    return failures?1:0;
}
