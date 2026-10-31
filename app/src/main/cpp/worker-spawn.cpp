// Starts the native SCAM/neural worker as a child of the app (no su): the
// worker runs in the app sandbox from nativeLibraryDir, with chosen file
// descriptors (memfd burst/result) mapped to 3, 4, ... and stdout+stderr on a pipe.
#include <jni.h>
#include <android/api-level.h>
#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <vector>
#include "worker-spawn-fast.h"

#ifndef __NR_close_range
#define __NR_close_range 436
#endif

namespace {
std::vector<std::string> strings(JNIEnv* env,jobjectArray array) {
    std::vector<std::string> out;
    const jsize n=array?env->GetArrayLength(array):0;
    for(jsize i=0;i<n;++i) {
        auto s=static_cast<jstring>(env->GetObjectArrayElement(array,i));
        const char* c=env->GetStringUTFChars(s,nullptr);
        out.emplace_back(c);
        env->ReleaseStringUTFChars(s,c);env->DeleteLocalRef(s);
    }
    return out;
}
std::vector<char*> pointers(std::vector<std::string>& v) {
    std::vector<char*> p;
    for(auto& s:v)p.push_back(&s[0]);
    p.push_back(nullptr);
    return p;
}
}

// Returns {pid, readFd of the child's stdout+stderr}, or null on failure.
static jintArray spawnWorker(JNIEnv* env,jobjectArray jargv,jobjectArray jenv,jintArray jfds,bool requireFast) {
    auto argvStrings=strings(env,jargv),envStrings=strings(env,jenv);
    if(argvStrings.empty())return nullptr;
    std::vector<int> fds;
    if(jfds) {
        const jsize n=env->GetArrayLength(jfds);fds.resize(n);
        env->GetIntArrayRegion(jfds,0,n,fds.data());
    }
    auto argv=pointers(argvStrings),envp=pointers(envStrings);
    // Sources are moved above the target range first, so mapping never clobbers one.
    std::vector<int> high;
    for(int fd:fds) {
        int h=fcntl(fd,F_DUPFD_CLOEXEC,std::max(64,3+int(fds.size())));
        if(h<0){for(int x:high)close(x);return nullptr;}
        high.push_back(h);
    }
    int pipeFds[2];
    if(pipe2(pipeFds,O_CLOEXEC)){for(int x:high)close(x);return nullptr;}
    const int devNull=open("/dev/null",O_RDONLY|O_CLOEXEC);
    // P319 (nubia NX563J, Android 10: «WORKER EXIT: signal 31» at every shot): close_range is not in the app seccomp allowlist
    // of older Android - the child died of SIGSYS before execve, so no merge ever ran there. It stays on Android 13+, where the
    // allowlist has it; below, a close() loop up to the fd limit (both read here: the child calls async-signal-safe code only).
    const bool closeRange=android_get_device_api_level()>=33;
    rlimit files{};
    const int maxFd=getrlimit(RLIMIT_NOFILE,&files)==0 && files.rlim_cur!=RLIM_INFINITY && files.rlim_cur<=65536
            ?int(files.rlim_cur):32768;
    // P371 (speed): the worker's nice value from its environment (WorkerEnv: SCAM_WORKER_NICE, scam_dev worker_nice), set in the
    // child before execve so its loader, its static init and every thread it starts have it; without the line it keeps the
    // spawning thread's (the processing thread at Java priority 4: nice 10). Read here: the child calls async-signal-safe code only.
    int workerNice=INT32_MIN;
    for(const auto& e:envStrings)if(e.rfind("SCAM_WORKER_NICE=",0)==0){
        char* end=nullptr;const long v=std::strtol(e.c_str()+17,&end,10);
        if(end&&*end==0&&end!=e.c_str()+17&&v>=-8&&v<=19)workerNice=int(v);
    }
    timespec spawnAt{};clock_gettime(CLOCK_MONOTONIC,&spawnAt);
    pid_t pid=scam_spawn::fast(argv.data(),envp.data(),high,pipeFds[1],devNull,workerNice);
    const bool fast=pid>=0;
    if(pid<0&&!requireFast)pid=fork();
    if(pid==0) {
        // Child: async-signal-safe calls only.
        if(workerNice!=INT32_MIN&&setpriority(PRIO_PROCESS,0,workerNice)!=0){} // the calling (only) thread; best effort
        if(devNull>=0)dup2(devNull,0);
        dup2(pipeFds[1],1);dup2(pipeFds[1],2);
        for(size_t i=0;i<high.size();++i)dup2(high[i],3+int(i));
        const unsigned first=3u+unsigned(high.size());
        if(closeRange)syscall(__NR_close_range,first,~0u,0u);
        else for(int fd=int(first);fd<maxFd;++fd)close(fd);
        sigset_t none;sigemptyset(&none);sigprocmask(SIG_SETMASK,&none,nullptr);
        execve(argv[0],argv.data(),envp.data());
        _exit(127);
    }
    for(int x:high)close(x);
    if(devNull>=0)close(devNull);
    close(pipeFds[1]);
    if(pid<0){close(pipeFds[0]);return nullptr;}
    timespec spawnedAt{};clock_gettime(CLOCK_MONOTONIC,&spawnedAt);
    __android_log_print(ANDROID_LOG_INFO,"SCAM_SPAWN","method=%s pid=%d setup_ms=%.3f nice=%d",
            fast?"posix_spawn":"fork",int(pid),double(spawnedAt.tv_sec-spawnAt.tv_sec)*1000.+double(spawnedAt.tv_nsec-spawnAt.tv_nsec)/1e6,workerNice);
    jintArray result=env->NewIntArray(2);
    const jint values[2]={pid,pipeFds[0]};
    env->SetIntArrayRegion(result,0,2,values);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_particlesdevs_photoncamera_util_WorkerSpawn_spawn(JNIEnv* env,jclass,jobjectArray argv,jobjectArray vars,jintArray fds){
    return spawnWorker(env,argv,vars,fds,false);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_particlesdevs_photoncamera_util_WorkerSpawn_spawnNoFork(JNIEnv* env,jclass,jobjectArray argv,jobjectArray vars,jintArray fds){
    return spawnWorker(env,argv,vars,fds,true);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_util_WorkerSpawn_supportsFastSpawn(JNIEnv*,jclass){
    return scam_spawn::api().available()?JNI_TRUE:JNI_FALSE;
}

// Exit status (0..255), 128+signal, or -1 while still running after timeoutMs.
extern "C" JNIEXPORT jint JNICALL
Java_com_particlesdevs_photoncamera_util_WorkerSpawn_waitFor(JNIEnv*,jclass,jint pid,jlong timeoutMs) {
    timespec start{};clock_gettime(CLOCK_MONOTONIC,&start);
    for(;;) {
        int status=0;
        const pid_t r=waitpid(pid,&status,WNOHANG);
        if(r==pid)return WIFEXITED(status)?WEXITSTATUS(status):WIFSIGNALED(status)?128+WTERMSIG(status):255;
        if(r<0 && errno!=EINTR)return 255;
        timespec now{};clock_gettime(CLOCK_MONOTONIC,&now);
        const long long elapsed=(now.tv_sec-start.tv_sec)*1000LL+(now.tv_nsec-start.tv_nsec)/1000000;
        if(elapsed>=timeoutMs)return -1;
        usleep(2000);
    }
}
