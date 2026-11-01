#include "../app/src/main/cpp/scam-la-gpu.h"
#include <cstdio>
using namespace scam;

static constexpr int W=512,H=384;
static int fails=0;
static void expect(bool ok,const char* label){std::printf("%s: %s\n",ok?"ok":"FAIL",label);if(!ok)++fails;}
static std::vector<uint16_t> frame(float dx){
    std::vector<uint16_t> a(size_t(W)*H);
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)
        a[size_t(y)*W+x]=uint16_t(1400+300*std::sin(.17f*(x-dx)+.09f*y)+200*std::sin(.07f*(x-dx)-.19f*y));
    return a;
}
static bool same(const LaGpuResult& a,const LaGpuResult& b){return a.field==b.field&&a.motion==b.motion&&a.flags==b.flags;}
int main(){
    const auto base=frame(0),donor=frame(1.3f),other=frame(-2.4f);
    auto build=[&]{
        auto gpu=std::make_unique<LaGpu>();
        LaGpuBurst b;b.w=W;b.h=H;b.white=4095;b.black.fill(64);b.canonical=true;
        LaGpuGrid g0{(W/2-16)/8+1,(H/2-16)/8+1,16,8,2},g1{(W/4-16)/8+1,(H/4-16)/8+1,16,8,4};
        gpu->base(b,base.data(),.001f,.00001f,g0,g1,LaGpuTune{});return gpu;
    };
    LaGpuWorker::Job job;job.f=1;job.raw=donor.data();job.h={1,0,0,0,1,0,0,0};
    LaGpuWorker::Job savedJob;LaGpuResult saved;
    {
        LaGpuWorker worker(3,build);
        expect(!worker.takeReady(1,savedJob,saved),"unfinished field cannot be exported");
        worker.add(job);expect(worker.wait({1}),"original field completes");
        expect(worker.takeReady(1,savedJob,saved),"completed field exported");
        expect(savedJob.raw==job.raw&&savedJob.h==job.h&&!saved.field.empty(),"export retains its RAW and homography key");
        LaGpuResult missing;expect(!worker.takeReady(1,savedJob,missing),"field consumed only once");
        expect(!worker.prime(job,saved),"active worker refuses an import");
    }
    {
        LaGpuWorker worker(3,build);
        LaGpuResult bad=saved;bad.motion.clear();expect(!worker.prime(job,std::move(bad)),"malformed maps refused");
        auto outside=job;outside.f=3;expect(!worker.prime(outside,saved),"out-of-range frame refused");
        expect(worker.prime(job,saved),"fresh worker accepts a complete field");
        expect(!worker.prime(job,saved),"duplicate import refused");
        worker.add(job);expect(worker.wait({1}),"same job keeps the imported field");
        expect(same(worker.take(1),saved),"restored field, motion and flags match exactly");
        auto changed=job;changed.raw=other.data();worker.add(changed);
        expect(worker.wait({1}),"changed RAW recomputed");
        expect(!same(worker.take(1),saved),"changed RAW cannot use the stale field");
        expect(!worker.prime(outside,saved),"import stays disabled after work starts");
        worker.close();expect(!worker.prime(job,saved),"closed worker refuses an import");
    }
    return fails?1:0;
}
