#pragma once
// The worker's parallel loops over row bands and indices (P704: the SCAM HDR temporal reference merge that lived here is gone).
#include "scam-pool.h"
#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace scam {

// P364: true on a thread whose mergeRowBands calls run their rows in place, one after the other (the blend weight's bands beside the
// merge: small bands, where starting 8 threads per call cost more than the rows); every row is computed by the same body
inline bool& mergeRowBandsSerial(){thread_local bool serial=false;return serial;}
// Runs body(y0,y1) over row bands on all cores.
inline void mergeRowBands(int h,const std::function<void(int,int)>& body) {
    const int threads=std::max(1u,std::min(8u,std::thread::hardware_concurrency()));
    if(threads<=1||h<64||mergeRowBandsSerial()){body(0,h);return;}
    // Small chunks handed out dynamically: the work per row depends on the scene
    // (robust frames, clipped areas), and equal static bands left cores idle.
    const int chunk=std::max(4,h/(threads*16));
    // P31: inside a task of the shared pool (hybrid stages built during the alignment) the same chunks go to the pool's threads,
    // not to 8 new threads on top of them; every row is computed by the same body.
    if(ScamPool::inWorker()){ScamPool::get().rows(h,chunk,body);return;}
    // P682: the same chunks on the persistent team (scamRunTeam: no threads started per call) of scamComputeThreads() threads, the
    // caller among them (the big cores, at least 4: a weak 2 + 6 SoC no longer runs 8); an exception of the body comes back here
    // once every chunk taken is done (it ended the process before)
    std::atomic<int> next{0};
    std::exception_ptr error;std::mutex errorLock;
    scamRunTeam(std::min(threads,scamComputeThreads()),[&]{
        for(int y0=next.fetch_add(chunk);y0<h;y0=next.fetch_add(chunk)){
            try{body(y0,std::min(h,y0+chunk));}
            catch(...){std::lock_guard<std::mutex> l(errorLock);if(!error)error=std::current_exception();}
        }
    });
    if(error)std::rethrow_exception(error);
}

// P662: body(i) for i in [0, n) on min(n, scamComputeThreads()) threads (this one among them), one index at a time: the selector's thumbnails
// and shifts (each index computes alone, so any thread order gives the same values); was a thread per frame (21 on 8 cores)
inline void fastParallelFor(int n,const std::function<void(int)>& body){
    if(n<=0)return;
    const int threads=std::max(1,std::min(n,scamComputeThreads())); // P682: the persistent team, the big cores (scamRunTeam)
    std::atomic<int> next{0};
    std::exception_ptr error;std::mutex errorLock;
    scamRunTeam(threads,[&]{
        for(int i=next.fetch_add(1);i<n;i=next.fetch_add(1)){
            try{body(i);}catch(...){std::lock_guard<std::mutex> l(errorLock);if(!error)error=std::current_exception();}
        }
    });
    if(error)std::rethrow_exception(error);
}

}
