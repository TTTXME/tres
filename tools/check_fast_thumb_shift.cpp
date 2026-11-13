// P663: the fast route's frame selector measures each donor's translation over ONE base window for every shift. The owner's shot
// (log 2026-11-11 22:54, Xiaomi 17 Ultra 8192x6144, ISO 3188, «Быстрый»): a texture-poor, noise-led frame (thumbnail sharpness
// 0.0515), every donor "dropped (shift 50.0 ...)" - the search's window shrank on every side with max(|sx|, |sy|), so the window's
// own noise level, not the alignment, decided, and the photo was one frame. Here: such a burst (a lit band over a dark rest, faint
// detail, the phone's noise model, small handheld shifts) keeps every donor; a textured burst still measures its shifts (within
// 1 thumbnail px) and still drops a donor moved past the gate; pure noise keeps its donors; a small replay frame (search +-12) still
// drops a donor moved past the gate through the wide search.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <string>
#include <vector>

using namespace scam;

namespace {
struct Rng { uint64_t s; explicit Rng(uint64_t v):s(v){}
    uint32_t u(){s=s*6364136223846793005ULL+1442695040888963407ULL;return uint32_t(s>>33);}
    float uni(){return (u()+0.5f)/2147483648.f;}
    float gauss(){const float a=uni(),b=uni();return std::sqrt(-2.f*std::log(a))*std::cos(6.2831853f*b);} };
// a smooth random texture: a bilinear grid of random values, `cell` px apart (periodic)
struct Texture { int n,cell;std::vector<float> g;
    Texture(int cells,int c,uint64_t seed):n(cells),cell(c),g(size_t(cells)*cells){Rng r(seed);for(auto& v:g)v=r.uni()*2.f-1.f;}
    float at(float x,float y)const{const float fx=x/cell,fy=y/cell;const int ix=int(std::floor(fx)),iy=int(std::floor(fy));
        const float ax=fx-ix,ay=fy-iy;auto G=[&](int i,int j){return g[size_t(((j%n)+n)%n)*n+size_t(((i%n)+n)%n)];};
        return (1-ay)*((1-ax)*G(ix,iy)+ax*G(ix+1,iy))+ay*((1-ax)*G(ix,iy+1)+ax*G(ix+1,iy+1));} };
enum Scene { kBand, kTextured, kNoise };
// one RAW (Bayer RGGB, black 64, white 1023) of the scene moved by (dx, dy) px, the phone's noise model (normalised units)
std::vector<uint16_t> frame(int w,int h,Scene sc,const Texture& t,int dx,int dy,uint64_t seed){
    std::vector<uint16_t> raw(size_t(w)*h);Rng r(seed);
    const float gain[4]={0.55f,1.f,1.f,0.6f};
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){
        const float X=float(x+dx),Y=float(y+dy);
        float L;
        if(sc==kBand)L=(0.07f+0.40f/(1.f+std::exp((Y-0.25f*h)/(120.f*h/6144.f))))*(1.f+0.01f*t.at(X,Y));
        else if(sc==kTextured)L=0.2f*(1.f+0.5f*t.at(X,Y));
        else L=0.25f;
        const float c=L*gain[((y&1)<<1)|(x&1)];
        const float v=c+r.gauss()*std::sqrt(0.00148f*c+7.65e-6f);
        raw[size_t(y)*w+x]=uint16_t(std::lround(std::min(1023.f,std::max(0.f,v*959.f+64.f))));
    }
    return raw;
}
struct Pick { int base=0,dropped=0;std::vector<int> donors;std::string line; };
Pick select(int w,int h,const std::vector<std::vector<uint16_t>>& raws){
    HybridInput in;in.w=w;in.h=h;in.cfa=0;in.white=1023.f;in.black={64.f,64.f,64.f,64.f};in.mosaic=1;
    std::vector<int> normals;
    for(size_t i=0;i<raws.size();++i){HybridFrame f;f.raw=raws[i].data();f.role=kRoleNormal;f.exposure=1.f;in.frames.push_back(f);normals.push_back(int(i));}
    HybridTuning tune;std::vector<FastThumb> thumbs(in.frames.size());Pick p;
    fastSelectFrames(in,tune,normals,{},thumbs,p.base,p.donors,p.dropped,p.line);
    return p;
}
int fails=0;
void expect(bool ok,const char* what,const std::string& line){std::printf("%s: %s\n  %s\n",ok?"ok":"FAIL",what,line.c_str());if(!ok)++fails;}
bool has(const std::vector<int>& v,int k){for(int x:v)if(x==k)return true;return false;}
} // namespace

int main(){
    { // the owner's shot: noise-led, structure along y alone (a lit band), small handheld shifts
        const int w=4096,h=3072;const Texture t(64,96,7);
        const int sh[5][2]={{0,0},{3,-2},{6,1},{-4,5},{9,-3}};
        std::vector<std::vector<uint16_t>> raws;
        for(int i=0;i<5;++i)raws.push_back(frame(w,h,kBand,t,sh[i][0],sh[i][1],100+i));
        const Pick p=select(w,h,raws);
        expect(p.dropped==0&&p.donors.size()==4,"noise-led burst (the 2026-11-11 22:54 shot): every donor kept",p.line);
    }
    { // textured: the shifts measured; a donor 320 px off (40 thumbnail px) dropped
        const int w=4096,h=3072;const Texture t(64,24,11);
        const int sh[4][2]={{0,0},{16,-8},{-24,32},{320,0}};
        std::vector<std::vector<uint16_t>> raws;
        for(int i=0;i<4;++i)raws.push_back(frame(w,h,kTextured,t,sh[i][0],sh[i][1],200+i));
        const Pick p=select(w,h,raws);
        expect(p.dropped==1&&has(p.donors,1)&&has(p.donors,2)&&!has(p.donors,3),"textured burst: small shifts kept, 40 px dropped",p.line);
#ifndef P663_OLD_API // (defined: builds against the header before P663, whose selector this check fails)
        HybridInput in;in.w=w;in.h=h;in.cfa=0;in.white=1023.f;in.black={64.f,64.f,64.f,64.f};
        const FastThumbNorm b(fastThumb(in,raws[0].data(),1.f));
        for(int i=1;i<4;++i){const FastShift s=fastThumbShift(b,FastThumbNorm(fastThumb(in,raws[size_t(i)].data(),1.f)));
            const float truth=std::hypot(sh[i][0]/8.f,sh[i][1]/8.f);
            char l[160];std::snprintf(l,sizeof(l),"frame %d: measured %.2f, true %.2f thumbnail px%s",i,s.shift,truth,s.flat?" (flat)":"");
            expect(std::fabs(s.shift-truth)<=1.01f&&!s.flat,"textured shift measured",l);}
#endif
    }
    { // pure noise: nothing to measure, the donors stay
        const int w=4096,h=3072;const Texture t(4,64,1);
        std::vector<std::vector<uint16_t>> raws;
        for(int i=0;i<5;++i)raws.push_back(frame(w,h,kNoise,t,0,0,300+i));
        const Pick p=select(w,h,raws);
        expect(p.dropped==0,"pure-noise burst: every donor kept",p.line);
    }
    { // a small replay frame (1024x768: search +-12): a 300 px drift (37 thumbnail px) past the reach -> the wide search drops it
        const int w=1024,h=768;const Texture t(160,12,5);
        const int sh[3][2]={{0,0},{24,8},{300,0}};
        std::vector<std::vector<uint16_t>> raws;
        for(int i=0;i<3;++i)raws.push_back(frame(w,h,kTextured,t,sh[i][0],sh[i][1],400+i));
        const Pick p=select(w,h,raws);
        expect(p.dropped==1&&has(p.donors,1)&&!has(p.donors,2),"small frame: 3 px kept, 37 px dropped (wide search)",p.line);
    }
    std::printf("check_fast_thumb_shift: %s\n",fails?"FAILED":"OK");
    return fails?1:0;
}
