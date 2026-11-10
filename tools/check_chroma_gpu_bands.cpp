// P564: mosaicChromaMedian's GPU path in bands of rows (a frame above one GPU call) gives the CPU stream's floats over the whole frame
// (the GPU's float rounding apart), with and without the oscillation map, for the blocks of Quad / Tetra / 2x grids. A small
// MemAvailable makes the bands small, so that a frame of a few rows has several seams.
// P610: the bands give the floats of one GPU call over the whole frame bit for bit (each band uploaded from the kept rows above it
// and the frame, its own rows read back into the frame, its storage kept from band to band), and again on a context kept by
// chromaKeepNext (its storage given back, then taken again).
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <cstring>
#include <random>

int main(){
    std::mt19937 rng(20261108);std::normal_distribution<float> n(0.f,0.01f);
    int bad=0;
    for(int block:{2,4,8})for(bool withOsc:{false,true}){
        const int w=512,h=7*16*block+6*block; // a last band shorter than the others
        std::vector<float> a(size_t(w)*h*3),osc(size_t(w/block)*(h/block));
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            const float base=0.3f+0.2f*std::sin(x*0.05f)*std::cos(y*0.04f),lat=((x/block+y/block)&1)?0.03f:-0.03f;
            float*p=&a[(size_t(y)*w+x)*3];
            p[0]=base*(0.9f+lat)+n(rng);p[1]=base+n(rng);p[2]=base*(0.8f-lat)+n(rng);
            if(y>h/2&&((x*7+y*3)%97)<9)p[1]+=0.2f*std::sin(x*1.3f);
        }
        for(size_t i=0;i<osc.size();++i)osc[i]=float(i%23)/30.f;
        auto cpu=a,gpu=a,one=a,again=a;
        {scam::MosaicChromaStream st(cpu.data(),w,h,block,withOsc?&osc:nullptr,0,0,nullptr);st.advance(h);}
        {auto g=scam::chromaTake();g->process(one,w,h,block,withOsc?&osc:nullptr);scam::chromaKeep(std::move(g));} // (kept: the bands take it)
        const int d=std::max(1,block/2),reach=std::max(2*d+6*block+1,3*block+1)+2,halo=((reach+2*block-1)/(2*block))*(2*block);
        const uint64_t avail=uint64_t(4)*uint64_t(w)*48*uint64_t(4*halo+2*halo+4*block); // bands just above the 4-halo floor
        std::string t;
        scam::chromaKeepNext(true);
        if(!scam::mosaicChromaMedianBands(gpu,w,h,block,withOsc?&osc:nullptr,avail,&t)){std::printf("block %d: bands refused: %s\n",block,t.c_str());return 1;}
        std::string t2;
        scam::chromaKeepNext(true); // (kept again: no teardown beside the next case's context - Mesa's eglTerminate ends every context)
        if(!scam::mosaicChromaMedianBands(again,w,h,block,withOsc?&osc:nullptr,avail,&t2)){std::printf("block %d: bands refused: %s\n",block,t2.c_str());return 1;}
        double maxd=0;
        for(size_t i=0;i<cpu.size();++i)maxd=std::max(maxd,double(std::fabs(cpu[i]-gpu[i])));
        size_t notOne=0,notAgain=0;
        for(size_t i=0;i<one.size();++i){notOne+=std::memcmp(&one[i],&gpu[i],4)!=0;notAgain+=std::memcmp(&again[i],&gpu[i],4)!=0;}
        const bool several=t.find("1 bands")!=0&&t.find(" on the CPU")==std::string::npos;
        std::printf("block %d osc %d %dx%d: %s; max |cpu - gpu bands| %.3g; values off one call %zu, off the kept context's run %zu\n",
                    block,int(withOsc),w,h,t.c_str(),maxd,notOne,notAgain);
        if(maxd>2e-4||!several||notOne||notAgain)++bad;
    }
    std::printf(bad?"check_chroma_gpu_bands: FAILED\n":"check_chroma_gpu_bands: OK\n");
    return bad?1:0;
}
