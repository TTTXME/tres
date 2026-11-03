#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <cstring>
#include <random>

using namespace scam;

int main(){
    constexpr int w=132,h=100;
    int cases=0;float maximum=0;
    for(int cfa=0;cfa<4;cfa++)for(int variant=0;variant<2;variant++){
        std::mt19937 random(462+cfa*17+variant);std::vector<std::vector<uint16_t>> raw(4,std::vector<uint16_t>(w*h));
        for(int f=0;f<4;f++)for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            const bool moving=variant&&f==2&&x>40&&x<80&&y>30&&y<65;
            float signal=.15f+.08f*std::sin(x*.31f)*std::cos(y*.19f)+(moving?.3f:0.f);
            if(x>95&&y<30)signal=1.2f;
            signal*=((y&1)*2+(x&1))==0?.65f:((y&1)*2+(x&1))==3?.8f:1.f;
            const float noise=(int(random()%101)-50)*.00015f;
            raw[f][y*w+x]=uint16_t(std::lround(64+std::clamp(signal+noise,0.f,1.f)*959));
        }
        HybridInput input;input.w=w;input.h=h;input.cfa=cfa;input.white=1023;input.black.fill(64);input.mosaic=1;input.grid=2;
        for(int f=0;f<4;f++){
            HybridFrame frame;frame.raw=raw[f].data();frame.role=kRoleNormal;frame.exposure=1;frame.iso=400;
            frame.slope=.0003f;frame.offset=.000002f;frame.orderMs=33.f*f;input.frames.push_back(frame);
        }
        HybridTuning tune;tune.grid=2;tune.auto2x=0;tune.bayerSplit=1;tune.localAlign=0;tune.gpuAlign=0;
        tune.sabre61=1;tune.s61Mode=7;tune.caCorrect=0;tune.chromaGpu=0;tune.chromaOverlap=0;
        tune.hotSigma=0;tune.hotBaseSigma=0;tune.widenBelow=4;tune.baseDemosaic=1;tune.chromaDiff=1;tune.dezipper=4.5f;
        ScamAlignment align=[](Burst&){return std::array<BackwardHomography,7>{};};
        std::vector<float> reference;std::vector<uint8_t> expectedMap,expectedFlags;
        for(int enabled=0;enabled<2;enabled++){
            if(enabled)unsetenv("SCAM_NO_DONOR_SUMS");else setenv("SCAM_NO_DONOR_SUMS","1",1);
            bool active=false;std::vector<uint8_t> map,flags;
            auto rgb=hybridReconstruct(input,tune,align,[&](const std::string& line){active|=line=="HYBRID DONOR SUMS: enabled";},nullptr,&map,nullptr,&flags);
            if(enabled!=int(active))throw std::runtime_error("Donor sums fixture did not exercise the requested GPU route");
            if(!enabled){reference=std::move(rgb);expectedMap=std::move(map);expectedFlags=std::move(flags);continue;}
            if(rgb.size()!=reference.size()||map!=expectedMap||flags!=expectedFlags)
                throw std::runtime_error("Donor sums changed output shape or trailer decisions");
            for(size_t i=0;i<rgb.size();i++){
                const float error=std::abs(rgb[i]-reference[i]);maximum=std::max(maximum,error);
                if(!std::isfinite(rgb[i])||error>2e-6f)throw std::runtime_error("Donor sums changed the FP32 colour calculation");
            }
        }
        ++cases;
    }
    unsetenv("SCAM_NO_DONOR_SUMS");
    std::printf("check_donor_sums_gpu: %d full CFA/motion/clip fixtures, max FP32 error %.9g, exact trailers\n",cases,maximum);
}
