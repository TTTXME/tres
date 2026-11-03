// P440 (owner, 2026-10-27: «нам нужно иметь как минимум не хуже скорость обработки чем у Why камеры»): the worker's trailers
// made strip by strip (app/src/main/cpp/scam-hybrid.h, tune stripTrailers) and its leftovers freed after the completion line
// (HybridLater, tune fastExit). Checked:
//   - hybridEffectiveCodes (FCVTAS eight at a time on arm64) against the scalar code of the effective map,
//     clamp(lround(v x scale), 1, 255), for every one of the 2^32 float bit patterns (NaN, infinities, subnormals, ties, beyond
//     int32) at several code scales, and at every length 0..40 and offset (the scalar tail);
//   - HybridLater: what it keeps lives until drop / its destructor, nothing before.
// The strip-by-strip clip flags and effective-map samples themselves are checked by replays (md5 of the worker's output against
// the base worker's on the OPPO replays: normal, forced outlier test, the app's memfd output, the GPU alignment off).
//
// Build (CI):
//   g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_strip_trailers.cpp -ldl -lEGL -lGLESv2
// (SCAM_FULL_SWEEP=0: every 4099th pattern only, for a sanitizer build on a slow host)
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>
using namespace scam;

static uint8_t scalarCode(float v,float scale){return uint8_t(std::clamp(std::lround(v*scale),1L,255L));}

int main(){
    int failures=0;
    // 1. every float bit pattern, in blocks of 4096 values, on all cores
    const float scales[]={1.737821f,64.f,1.f,0.25f,0.0173f,3.0e9f,1e-30f};
    const char* full=std::getenv("SCAM_FULL_SWEEP");
    const uint64_t stride=full&&std::strcmp(full,"0")==0?4099:1;
    const unsigned threads=std::max(1u,std::min(16u,std::thread::hardware_concurrency()));
    for(float scale:scales){
        std::atomic<uint64_t> bad{0},firstBad{~uint64_t(0)};
        std::vector<std::thread> pool;
        const uint64_t blocks=(uint64_t(1)<<32)/4096;
        for(unsigned t=0;t<threads;++t)pool.emplace_back([&,t]{
            std::vector<float> src(4096);std::vector<uint8_t> got(4096);
            for(uint64_t b=t;b<blocks;b+=threads){
                if(stride>1&&(b%stride)!=0)continue;
                for(uint32_t i=0;i<4096;++i){const uint32_t bits=uint32_t(b*4096+i);std::memcpy(&src[i],&bits,4);}
                hybridEffectiveCodes(src.data(),got.data(),4096,scale);
                for(uint32_t i=0;i<4096;++i)if(got[i]!=scalarCode(src[i],scale)){
                    if(bad.fetch_add(1)==0)firstBad=b*4096+i;
                }
            }
        });
        for(auto& th:pool)th.join();
        if(bad){
            ++failures;
            const uint32_t bits=uint32_t(firstBad.load());float v;std::memcpy(&v,&bits,4);
            std::printf("FAIL codes scale=%g: %llu patterns differ (first 0x%08x = %g: %u vs %u)\n",scale,(unsigned long long)bad.load(),bits,v,
                [&]{uint8_t d;hybridEffectiveCodes(&v,&d,1,scale);return unsigned(d);}(),unsigned(scalarCode(v,scale)));
        } else std::printf("codes scale=%g: every %s pattern as the scalar code\n",scale,stride>1?"4099th block's":"float bit");
    }
    // 2. lengths and offsets (the vector body and the scalar tail meet at every place)
    {
        std::vector<float> src(64);
        for(size_t i=0;i<src.size();++i)src[i]=0.37f*float(i)-3.f+(i%5==0?0.5f/1.737821f:0.f);
        bool ok=true;
        for(int off=0;off<8;++off)for(int n=0;n<=40;++n){
            std::vector<uint8_t> d(size_t(n)+2,0xEE);
            hybridEffectiveCodes(src.data()+off,d.data()+1,n,1.737821f);
            if(d[0]!=0xEE||d[size_t(n)+1]!=0xEE)ok=false; // nothing written outside [0, n)
            for(int i=0;i<n;++i)if(d[size_t(i)+1]!=scalarCode(src[size_t(off+i)],1.737821f))ok=false;
        }
        if(!ok){++failures;std::printf("FAIL codes: a length / offset differs or writes outside its range\n");}
        else std::printf("codes: lengths 0..40 at offsets 0..7 as the scalar code, nothing written outside\n");
    }
    // 3. HybridLater keeps until drop (and its destructor frees what is left)
    {
        auto probe=std::make_shared<int>(7);std::weak_ptr<int> seen=probe;
        std::vector<float> big(1<<20,1.f);const float* data=big.data();
        HybridLater later;
        later.keep(std::move(probe));later.keep(std::move(big));
        const bool kept=!seen.expired()&&big.empty()&&data!=nullptr;
        later.drop();
        const bool dropped=seen.expired();
        std::weak_ptr<int> seen2;
        {HybridLater scoped;auto p=std::make_shared<int>(1);seen2=p;scoped.keep(std::move(p));if(seen2.expired())++failures;}
        if(!kept||!dropped||!seen2.expired()){++failures;std::printf("FAIL HybridLater: kept=%d dropped=%d destructor=%d\n",kept,dropped,seen2.expired());}
        else std::printf("HybridLater: kept until drop, the rest freed by its destructor\n");
    }
    std::printf(failures?"check_hybrid_strip_trailers: %d FAILED\n":"check_hybrid_strip_trailers: OK\n",failures);
    return failures?1:0;
}
