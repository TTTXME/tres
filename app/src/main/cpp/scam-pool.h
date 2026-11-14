#pragma once
// P31 (shot speed, research/speed/SHOT_SPEED_PLAN.md W1.2 / W1.4): one persistent pool of worker threads for the hybrid front
// end. While the calling thread runs the CRE alignment (detect / track stay there, in their order), the pool builds what needs
// neither the alignment nor its results: the donor guides of the whole burst and the base-only stages (Shasta, Bento, F6 base,
// RAW CA). Every task computes exactly what the serial code computed (the same functions on the same data); only the thread
// and the time change.
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <condition_variable>
#include <deque>
#include <exception>
#include <fstream>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#if defined(__linux__)
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace scam {

// P682 (owner: «в начальную компиляцию встроить то что поможет ускорить и оптимизировать обработку»): the CPU classes measured once
// by the worker's prewarm (scamCalibrateCpus: a short timed loop on one core of each cpu_capacity class), kept in a small file
// beside the GPU program cache (the job's gl-cache directory) that every shot reads: "scam-cpu v1 <n>" then "cpu <i> <capacity>
// <score>" (score: loop rounds per us, 0 = not measured). "" = no file (the capacities alone).
inline std::string& scamCpuCalibrationPath(){static std::string path;return path;}
inline std::vector<long> scamCpuCapacities(int n){ // empty when the kernel does not tell them
    std::vector<long> capacity;
    for(int c=0;c<n;++c){
        std::ifstream f("/sys/devices/system/cpu/cpu"+std::to_string(c)+"/cpu_capacity");long v=0;
        if(!(f>>v)||v<=0)return {};
        capacity.push_back(v);
    }
    return capacity;
}
struct ScamCpuInfo {
    int n=1,big=0;           // big: the CPUs of at least half the fastest (0: unknown)
    bool measured=false;     // big from the calibration file (else from cpu_capacity)
    std::string note;        // the log's description
};
// P682 (speed on the weak SoCs): the CPUs of at least half the fastest - measured by the prewarm when its file is there and matches
// this CPU (every CPU scored), else by cpu_capacity (P213b's count, which mosaicChromaThreads takes).
inline const ScamCpuInfo& scamCpuInfo(){
    static const ScamCpuInfo info=[]{
        ScamCpuInfo r;
        r.n=int(std::clamp(std::thread::hardware_concurrency(),1u,64u));
        const std::vector<long> capacity=scamCpuCapacities(r.n);
        std::vector<double> score(size_t(r.n),0.0);
        bool scored=false;
        if(!scamCpuCalibrationPath().empty()){
            std::ifstream f(scamCpuCalibrationPath());std::string tag,version;int n=0;
            if(f>>tag>>version>>n&&tag=="scam-cpu"&&version=="v1"&&n==r.n){
                int seen=0;std::string word;int i=0;long cap=0;double sc=0;
                while(f>>word>>i>>cap>>sc){
                    if(word!="cpu"||i<0||i>=r.n)break;
                    if(!capacity.empty()&&cap!=capacity[size_t(i)])break; // another kernel's capacities: measured again by the next prewarm
                    score[size_t(i)]=sc;++seen;
                }
                scored=seen==r.n&&std::all_of(score.begin(),score.end(),[](double v){return v>0.0;});
            }
        }
        char line[160];
        if(scored){
            const double top=*std::max_element(score.begin(),score.end());
            r.big=int(std::count_if(score.begin(),score.end(),[&](double v){return 2.0*v>=top;}));
            r.measured=true;
            std::string classes;
            for(int c=0;c<r.n;++c){std::snprintf(line,sizeof(line),"%s%.0f%%",c?" ":"",100.0*score[size_t(c)]/top);classes+=line;}
            r.note="measured speed per CPU ("+classes+"): "+std::to_string(r.big)+" fast";
        } else if(!capacity.empty()){
            const long top=*std::max_element(capacity.begin(),capacity.end());
            r.big=int(std::count_if(capacity.begin(),capacity.end(),[&](long v){return 2*v>=top;}));
            r.note="cpu_capacity: "+std::to_string(r.big)+" of "+std::to_string(r.n)+" at least half the largest";
        } else r.note="no capacities";
        return r;
    }();
    return info;
}
inline int scamBigCores(){return scamCpuInfo().big;}
// P682: the threads of a stage spread over the cores (mergeRowBands, fastParallelFor, ScamPool): the big cores (scamBigCores),
// at least 4 (a 2 + 6 SoC: its two big cores and two of the little ones, the other four left to the camera and the viewfinder;
// the rows go out in small chunks, so a little core holds no other), at most 8; without capacities min(8, hardware threads) as
// before. Every SoC whose cores all reach half the largest (the 8 Elite, the Dimensity 9x00) keeps 8.
inline int scamComputeThreads(){
    static const int threads=[]{
        const int n=int(std::clamp(std::thread::hardware_concurrency(),1u,64u)),big=scamBigCores();
        if(big<=0)return std::min(n,8);
        return std::clamp(std::max(big,std::min(n,4)),1,8);
    }();
    return threads;
}
// P682: the measurement (the worker's prewarm, once per install / kernel): per cpu_capacity class (every CPU when there are no
// capacities, at most 8 measured) a thread pinned to one of its CPUs runs a fixed loop (14 independent chains) for
// 15 ms to wake the core's clock, then 3 x 15 ms: the best rate counts. ~0.1 - 0.4 s in all. Written to path ("scam-cpu v1"); a CPU
// the process may not run on (its cpuset) scores 0 and the shots size by cpu_capacity. Returns the log line.
inline std::string scamCalibrateCpus(const std::string& path){
    using Clock=std::chrono::steady_clock;
    const auto t0=Clock::now();
    const int n=int(std::clamp(std::thread::hardware_concurrency(),1u,64u));
    const std::vector<long> capacity=scamCpuCapacities(n);
    {std::ifstream f(path);std::string tag,version;int m=0;
     if(f>>tag>>version>>m&&tag=="scam-cpu"&&version=="v1"&&m==n){
         std::string word;int i=0,seen=0;long cap=0;double sc=0;bool same=true;
         while(f>>word>>i>>cap>>sc){if(word!="cpu"||i<0||i>=n||(!capacity.empty()&&cap!=capacity[size_t(i)])){same=false;break;}++seen;}
         if(same&&seen==n)return "CPU calibration: kept ("+path+")";
     }}
    std::vector<double> score(size_t(n),0.0);
    std::vector<int> probe; // the CPUs measured: the first of each capacity class
    for(int c=0;c<n&&int(probe.size())<8;++c){
        bool first=true;
        if(!capacity.empty())for(int p:probe)if(capacity[size_t(p)]==capacity[size_t(c)])first=false;
        if(first)probe.push_back(c);
    }
    std::string detail;
    for(int c:probe){
        double best=0;
        std::thread t([&]{
#if defined(__linux__)
            cpu_set_t set;CPU_ZERO(&set);CPU_SET(c,&set);
            if(sched_setaffinity(0,sizeof(set),&set)!=0)return; // not allowed here: not measured
#else
            return;
#endif
            volatile float sink=0;
            // 12 independent multiply-add chains and two xorshift chains: a wide out-of-order core runs many of them at once, an
            // in-order little core few (a single chain would time only the clock)
            auto rounds=[&](double ms){
                float v[12];for(int j=0;j<12;++j)v[j]=1.f+1e-3f*float(j);
                const float k=0.99991f,q=1e-4f;uint32_t x=2463534242u,y=88675123u;
                long count=0;const auto s0=Clock::now();double took=0;
                do{
                    for(int r=0;r<1024;++r){
                        for(int j=0;j<12;++j)v[j]=v[j]*k+q;
                        x^=x<<13;x^=x>>17;x^=x<<5;y^=y<<7;y^=y>>9;
                    }
                    count+=1024;took=std::chrono::duration<double,std::micro>(Clock::now()-s0).count();
                }while(took<ms*1000.0);
                float acc=float(x^y)*1e-12f;for(int j=0;j<12;++j)acc+=v[j];
                sink=sink+acc;
                return double(count)/took;
            };
            rounds(15.0);
            for(int k=0;k<3;++k)best=std::max(best,rounds(15.0));
        });
        t.join();
        for(int i=0;i<n;++i)if(i==c||(!capacity.empty()&&capacity[size_t(i)]==capacity[size_t(c)]))score[size_t(i)]=best;
        char d[80];std::snprintf(d,sizeof(d),"%scpu%d (capacity %ld) %.1f",detail.empty()?"":", ",c,capacity.empty()?0L:capacity[size_t(c)],best);
        detail+=d;
    }
    const std::string tmp=path+".tmp"+std::to_string(getpid());
    bool written=false;
    {std::ofstream out(tmp,std::ios::trunc);
     if(out){out<<"scam-cpu v1 "<<n<<"\n";for(int c=0;c<n;++c)out<<"cpu "<<c<<" "<<(capacity.empty()?0L:capacity[size_t(c)])<<" "<<score[size_t(c)]<<"\n";written=bool(out);}}
    if(written)written=std::rename(tmp.c_str(),path.c_str())==0;
    if(!written)std::remove(tmp.c_str());
    char t[64];std::snprintf(t,sizeof(t),"%.0f ms",std::chrono::duration<double,std::milli>(Clock::now()-t0).count());
    return "CPU calibration: "+detail+" (loop rounds per us), "+t+(written?"":" (not written: "+path+")");
}

class ScamPool {
    std::mutex lock;
    std::condition_variable wake;
    std::deque<std::function<void()>> queue;
    std::vector<std::thread> threads;
    static bool& workerFlag(){static thread_local bool flag=false;return flag;}
    explicit ScamPool(int n){
        for(int i=0;i<n;++i){
            try{threads.emplace_back([this]{
                workerFlag()=true;
                for(;;){
                    std::function<void()> task;
                    {std::unique_lock<std::mutex> l(lock);wake.wait(l,[&]{return !queue.empty();});task=std::move(queue.front());queue.pop_front();}
                    try{task();}catch(...){} // submit() and rows() keep their own exceptions; nothing may end the thread
                }
            });}catch(const std::exception&){break;} // fewer threads: the queue is shared
        }
    }
public:
    // Hardware threads - 2, 1..6: the calling thread aligns meanwhile and the camera preview keeps running in the app (replays on
    // the OPPO with the preview on: 6 threads aligned as fast as 7, the CRE thread less often slowed). Never destroyed: the threads
    // wait for work until the process exits (no join at exit, no task outliving its data: every caller waits for its own tasks).
    // P682: at most scamComputeThreads() - 1 (with the aligning thread, the stage's threads): 6 on the 8 Elite as before.
    static ScamPool& get(){static ScamPool* pool=new ScamPool(std::clamp(std::min(int(std::thread::hardware_concurrency())-2,scamComputeThreads()-1),1,6));return *pool;}
    // True on a pool thread: mergeRowBands then spreads its rows over the pool instead of starting threads of its own.
    static bool inWorker(){return workerFlag();}
    int size() const {return int(threads.size());}
    // A task at the back of the queue; its result (or exception) through the future. A task must not wait for another task.
    template<class F> auto submit(F&& f)->std::future<decltype(f())>{
        using R=decltype(f());
        auto task=std::make_shared<std::packaged_task<R()>>(std::forward<F>(f));
        std::future<R> result=task->get_future();
        if(threads.empty()){(*task)();return result;}
        {std::lock_guard<std::mutex> l(lock);queue.emplace_back([task]{(*task)();});}
        wake.notify_one();
        return result;
    }
    // body(y0, y1) over rows [0, h) in chunks of `chunk`, on the pool and on the calling thread; returns when every row is done.
    // The helpers go to the front of the queue (a stage that started finishes before new stages start) and never wait, so a pool
    // task may call this without deadlocking the pool: the caller takes every chunk itself when no thread is free.
    void rows(int h,int chunk,const std::function<void(int,int)>& body){
        if(h<=0)return;
        chunk=std::max(1,chunk);
        struct State {
            std::atomic<int> next{0},done{0};
            std::mutex m;std::condition_variable cv;
            std::exception_ptr error;
        };
        auto st=std::make_shared<State>();
        const std::function<void(int,int)>* fn=&body; // alive while a chunk runs: the caller returns only after the last one
        auto run=[st,fn,h,chunk]{
            for(int y0=st->next.fetch_add(chunk);y0<h;y0=st->next.fetch_add(chunk)){
                const int y1=std::min(h,y0+chunk);
                try{(*fn)(y0,y1);}
                catch(...){std::lock_guard<std::mutex> l(st->m);if(!st->error)st->error=std::current_exception();}
                if(st->done.fetch_add(y1-y0)+(y1-y0)==h){std::lock_guard<std::mutex> l(st->m);st->cv.notify_all();}
            }
        };
        const int helpers=std::min(size(),(h+chunk-1)/chunk-1);
        if(helpers>0){
            // A helper that cannot be queued (no memory) means fewer helpers, never an exception here: the helpers already queued
            // read `fn` only for a chunk they take, and the wait below outlasts every taken chunk.
            try{std::lock_guard<std::mutex> l(lock);for(int i=0;i<helpers;++i)queue.emplace_front(run);}catch(const std::exception&){}
            wake.notify_all();
        }
        run();
        {std::unique_lock<std::mutex> l(st->m);st->cv.wait(l,[&]{return st->done.load()==h;});}
        if(st->error)std::rethrow_exception(st->error);
    }
};

// P682 (speed): persistent helpers for the stages spread over the cores from a thread outside the pool (mergeRowBands ~90 call
// sites, fastParallelFor, the clipped lights' notch): each call started up to 8 threads (~0.1 ms a call on x86, more on the
// phone) and gave them back. The team's threads wait for a call; job() runs on `workers` threads, the caller among them, and run
// returns once each has returned from it. The callers hand out their work themselves (an atomic counter: the same chunks of the
// same rows whatever thread takes them), so the results are those of the threads they started before, bit for bit. One call at a
// time: a call on a team thread (nested), during another one, on a pool thread or from a thread of another nice value (the threads
// they started inherited it) gets false and starts its threads as before. Never destroyed (as ScamPool).
class ScamRowTeam {
    std::mutex callers,m;
    std::condition_variable wake,done;
    std::vector<std::thread> threads;
    const std::function<void()>* job=nullptr;
    unsigned long long gen=0;
    int want=0,left=0,nice=0;
    static bool& memberFlag(){static thread_local bool flag=false;return flag;}
    static int currentNice(){
#if defined(__linux__)
        errno=0;const int v=getpriority(PRIO_PROCESS,id_t(syscall(SYS_gettid)));
        return errno?0:v;
#else
        return 0;
#endif
    }
    explicit ScamRowTeam(int n):nice(currentNice()){
        for(int i=0;i<n;++i){
            try{threads.emplace_back([this,i]{
                memberFlag()=true;
                unsigned long long seen=0;
                for(;;){
                    const std::function<void()>* f=nullptr;
                    {std::unique_lock<std::mutex> l(m);wake.wait(l,[&]{return gen!=seen;});seen=gen;if(i>=want)continue;f=job;}
                    try{(*f)();}catch(...){} // the callers' jobs keep their own exceptions
                    {std::lock_guard<std::mutex> l(m);if(--left==0)done.notify_all();}
                }
            });}catch(const std::exception&){break;} // fewer threads: the work is shared
        }
    }
public:
    static ScamRowTeam& get(){static ScamRowTeam* team=new ScamRowTeam(scamComputeThreads()-1);return *team;}
    // job on min(workers - 1, team) team threads and this one; false (job not run) when the team cannot take the call
    bool run(int workers,const std::function<void()>& fn){
        if(memberFlag()||ScamPool::inWorker())return false;
        std::unique_lock<std::mutex> call(callers,std::try_to_lock);
        if(!call.owns_lock()||currentNice()!=nice)return false;
        const int helpers=std::min(workers-1,int(threads.size()));
        if(helpers>0){
            {std::lock_guard<std::mutex> l(m);job=&fn;want=helpers;left=helpers;++gen;}
            wake.notify_all();
        }
        try{fn();}catch(...){}
        if(helpers>0){std::unique_lock<std::mutex> l(m);done.wait(l,[&]{return left==0;});job=nullptr;}
        return true;
    }
};
// P682: job() on `workers` threads (this one among them): the team's when it takes the call, else threads started for it. job
// must take its share of the work from a shared counter and must not throw.
inline void scamRunTeam(int workers,const std::function<void()>& job){
    if(workers<=1){job();return;}
    if(ScamRowTeam::get().run(workers,job))return;
    std::vector<std::thread> pool;
    for(int t=1;t<workers;++t){try{pool.emplace_back(job);}catch(const std::exception&){break;}} // fewer threads: the work is shared
    job();
    for(auto& t:pool)t.join();
}

// Tasks of one caller: every task is waited for before the group goes (also when the caller leaves by an exception), so a task
// may use the caller's locals declared before the group.
class ScamTasks {
    struct State { std::mutex m;std::condition_variable cv;int running=0; };
    std::shared_ptr<State> st=std::make_shared<State>();
public:
    template<class F> auto run(F f)->std::future<decltype(f())>{
        auto s=st;
        {std::lock_guard<std::mutex> l(s->m);++s->running;}
        // counted off when f returns (or throws): from then on the task touches no caller data, only its own result
        struct Done { std::shared_ptr<State> s; ~Done(){std::lock_guard<std::mutex> l(s->m);if(--s->running==0)s->cv.notify_all();} };
        try{
            return ScamPool::get().submit([s,f=std::move(f)]() mutable {Done done{s};return f();});
        }catch(...){Done undo{s};throw;}
    }
    void wait(){std::unique_lock<std::mutex> l(st->m);st->cv.wait(l,[&]{return st->running==0;});}
    ScamTasks()=default;
    ScamTasks(const ScamTasks&)=delete;
    ~ScamTasks(){wait();}
};

} // namespace scam
