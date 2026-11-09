// P568: the Quad native merge with its colour blocks in shared memory (mosaicNatTile 1, NAT_TILE) gives the original program's
// floats (mosaicNatTile 0) bit for bit: a synthetic 4-frame Quad burst (shifted frames, a textured scene, noise) through
// hybridReconstructMosaicNative both ways.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <random>
#include <unistd.h>

using namespace scam;

int main(){
    const int W=1024,H=768,N=4;
    const char* temp=std::getenv("TMPDIR");
    std::string path=std::string(temp?temp:"/tmp")+"/scamera-nat-tile-XXXXXX";
    const int fd=mkstemp(path.data());
    assert(fd>=0);
    uint32_t words[32+8*N]{};
    words[0]=0x3143484e;words[1]=11;words[2]=W;words[3]=H;words[4]=0;words[5]=N;
    const float white=1023,black=64,exposure=1,slope=4e-5f,offset=6e-7f;
    std::memcpy(words+6,&white,4);
    for(int i=7;i<11;++i)std::memcpy(words+i,&black,4);
    words[13]=1;words[14]=2; // grid 1, colour block 2 (Quad)
    for(int i=0;i<N;++i){
        uint32_t* f=words+32+8*i;
        f[0]=kRoleNormal;std::memcpy(f+1,&exposure,4);f[2]=100;std::memcpy(f+3,&slope,4);std::memcpy(f+4,&offset,4);
        const float order=float(i);std::memcpy(f+5,&order,4);
    }
    assert(write(fd,words,sizeof(words))==ssize_t(sizeof(words)));
    std::mt19937 rng(568);std::normal_distribution<float> n01(0.f,1.f);
    const int sx[N]={0,2,1,3},sy[N]={0,1,3,2};
    std::vector<uint16_t> plane(size_t(W)*H);
    for(int f=0;f<N;++f){
        for(int y=0;y<H;++y)for(int x=0;x<W;++x){
            const float X=float(x+sx[f]),Y=float(y+sy[f]);
            const int c=(((y>>1)&1)<<1)|((x>>1)&1); // Quad RGGB: R, G, G, B blocks of 2x2
            float v=0.2f+0.15f*std::sin(X*0.07f)*std::cos(Y*0.05f)+(((int(X)/37+int(Y)/29)&1)?0.25f:0.f);
            v*=c==0?0.8f:c==3?0.6f:1.f;
            v+=std::sqrt(slope*std::max(v,0.f)+offset)*n01(rng);
            plane[size_t(y)*W+x]=uint16_t(std::clamp(std::lround(black+v*(white-black)),0L,1023L));
        }
        assert(write(fd,plane.data(),plane.size()*2)==ssize_t(plane.size()*2));
    }
    assert(close(fd)==0);
    MappedHybridBurst burst(path);
    std::vector<float> out[2];
    for(int v=0;v<2;++v){
        HybridTuning t;t.mosaicNatTile=v;
        std::string tileLine;
        const auto report=[&](const std::string& l){if(l.find("shared tiles")!=std::string::npos)tileLine=l;};
        hybridNatTileWanted().store(v);
        out[v]=hybridReconstructMosaicNative(burst.input,2,t,{},report,nullptr,nullptr,nullptr,nullptr);
        std::printf("mosaicNatTile %d: %zu floats; %s\n",v,out[v].size(),tileLine.c_str());
        if(v==1&&tileLine.find(": on")==std::string::npos){std::printf("check_nat_tile: the tiled program did not run\n");unlink(path.c_str());return 1;}
    }
    unlink(path.c_str());
    const bool same=out[0].size()==out[1].size()&&!out[0].empty()&&std::memcmp(out[0].data(),out[1].data(),out[0].size()*4)==0;
    std::printf(same?"check_nat_tile: OK (bit for bit)\n":"check_nat_tile: FAILED (the outputs differ)\n");
    return same?0:1;
}
