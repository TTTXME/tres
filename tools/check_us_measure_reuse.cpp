#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
using namespace scam;

int main(){
    constexpr int W=1024,H=768;int fails=0,withRows=0,withoutRows=0,localOnly=0;
    for(int scene=0;scene<4;++scene){
        std::vector<uint16_t> base(size_t(W)*H),raw(base.size());
        for(int y=0;y<H;++y)for(int x=0;x<W;++x){
            float v=.23f+.07f*std::sin(.011f*x)*std::cos(.009f*y);
            if((x/128+y/128)%9==0)v=2.f;
            float flicker=scene==0?1.f:scene==1?1.f+.25f*std::sin(.025f*y):scene==2?(x<W/3?.75f:x<2*W/3?1.f:1.25f):1.f+.16f*std::sin(.025f*y)+.18f*std::sin(.01f*x);
            base[size_t(y)*W+x]=uint16_t(64+4031*std::clamp(v,0.f,1.f));
            raw[size_t(y)*W+x]=uint16_t(64+4031*std::clamp(v*.125f*flicker,0.f,1.f));
        }
        HybridInput in;in.w=W;in.h=H;in.cfa=0;in.white=4095;in.black.fill(64);in.mosaic=1;
        HybridFrame b;b.raw=base.data();b.slope=1e-4f;b.offset=1e-6f;
        auto u=b;u.raw=raw.data();u.role=kRoleUltrashort;u.exposure=.125f;in.frames={b,u};
        HybridTuning t;t.usSceneEv=0;
        const auto gate=hybridUsGate(t);
        auto rows=hybridUltrashortRows(base.data(),raw.data(),W,H,0,in.black,in.white,.125f,t.mosaicUsRowsMin,gate.daylight());
        HybridUsLocal local;
        if(rows.apply)++withRows;else {++withoutRows;local=hybridUltrashortLocal(base.data(),raw.data(),W,H,0,in.black,in.white,.125f,t.mosaicUsRowsMin,t.usLocalAlign!=0,gate);}
        if(!rows.apply&&local.on())++localOnly;
        // A local field measured before a row correction must be ignored, even if supplied.
        if(rows.apply){local.apply=local.over=true;local.gw=local.gh=1;for(auto& f:local.f)f.assign(1,9.f);}
        auto a=raw,c=raw;std::vector<std::string> logA,logB;
        auto old=hybridUsRowsMeasure(in,1,base.data(),a.data(),W,H,t,"TEST",[&](const std::string& l){logA.push_back(l);});
        auto reused=hybridUsRowsMeasure(in,1,base.data(),c.data(),W,H,t,"TEST",[&](const std::string& l){logB.push_back(l);},&rows,&local);
        hybridUsRowsFinish(old,in,1,base.data(),a.data(),W,H,t,gate,"TEST",[&](const std::string& l){logA.push_back(l);});
        hybridUsRowsFinish(reused,in,1,base.data(),c.data(),W,H,t,gate,"TEST",[&](const std::string& l){logB.push_back(l);});
        bool ok=a==c&&old.corrected==reused.corrected&&old.refused==reused.refused&&logA==logB;
        std::printf("%s: scene %d, rows %s, corrected RAW and decisions/report identical\n",ok?"ok":"FAIL",scene,rows.apply?"applied":"unchanged");if(!ok)++fails;
    }
    if(!withRows||!withoutRows||!localOnly){std::printf("FAIL: missing row-correction branch coverage\n");++fails;}
    return fails?1:0;
}
