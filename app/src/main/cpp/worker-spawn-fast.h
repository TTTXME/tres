#pragma once
#include <android/api-level.h>
#include <cerrno>
#include <climits>
#include <dlfcn.h>
#include <signal.h>
#include <spawn.h>
#include <sys/resource.h>
#include <vector>

namespace scam_spawn {
struct Api {
    int (*spawn)(pid_t*,const char*,const posix_spawn_file_actions_t*,const posix_spawnattr_t*,char* const*,char* const*)=nullptr;
    int (*attrInit)(posix_spawnattr_t*)=nullptr;
    int (*attrDestroy)(posix_spawnattr_t*)=nullptr;
    int (*flags)(posix_spawnattr_t*,short)=nullptr;
    int (*mask)(posix_spawnattr_t*,const sigset_t*)=nullptr;
    int (*actionsInit)(posix_spawn_file_actions_t*)=nullptr;
    int (*actionsDestroy)(posix_spawn_file_actions_t*)=nullptr;
    int (*dup)(posix_spawn_file_actions_t*,int,int)=nullptr;
    Api(){
        if(android_get_device_api_level()<33)return;
#define SCAM_SPAWN_SYMBOL(member,name) member=reinterpret_cast<decltype(member)>(dlsym(RTLD_DEFAULT,name))
        SCAM_SPAWN_SYMBOL(spawn,"posix_spawn");
        SCAM_SPAWN_SYMBOL(attrInit,"posix_spawnattr_init");
        SCAM_SPAWN_SYMBOL(attrDestroy,"posix_spawnattr_destroy");
        SCAM_SPAWN_SYMBOL(flags,"posix_spawnattr_setflags");
        SCAM_SPAWN_SYMBOL(mask,"posix_spawnattr_setsigmask");
        SCAM_SPAWN_SYMBOL(actionsInit,"posix_spawn_file_actions_init");
        SCAM_SPAWN_SYMBOL(actionsDestroy,"posix_spawn_file_actions_destroy");
        SCAM_SPAWN_SYMBOL(dup,"posix_spawn_file_actions_adddup2");
#undef SCAM_SPAWN_SYMBOL
    }
    bool available() const{return spawn&&attrInit&&attrDestroy&&flags&&mask&&actionsInit&&actionsDestroy&&dup;}
};
inline const Api& api(){static const Api value;return value;}

// Bionic's vfork path owns its child setup; no application C++ code runs in the shared address space.
inline pid_t fast(char* const* argv,char* const* env,const std::vector<int>& high,int output,int devNull,int nice){
    const Api& a=api();
    if(!a.available()||devNull<0)return -1;
    errno=0;const int inherited=getpriority(PRIO_PROCESS,0);
    if(errno||(nice!=INT_MIN&&nice!=inherited))return -1;
    posix_spawnattr_t attr{};posix_spawn_file_actions_t actions{};
    if(a.attrInit(&attr))return -1;
    if(a.actionsInit(&actions)){a.attrDestroy(&attr);return -1;}
    sigset_t none;sigemptyset(&none);
    // CLOEXEC_DEFAULT was added in Android 13. It closes unrelated app descriptors without enumerating a racing fd table.
    int rc=a.flags(&attr,short(64 /* POSIX_SPAWN_USEVFORK */|POSIX_SPAWN_SETSIGMASK|POSIX_SPAWN_CLOEXEC_DEFAULT));
    if(!rc)rc=a.mask(&attr,&none);
    if(!rc)rc=a.dup(&actions,devNull,0);
    if(!rc)rc=a.dup(&actions,output,1);
    if(!rc)rc=a.dup(&actions,output,2);
    for(size_t i=0;!rc&&i<high.size();++i)rc=a.dup(&actions,high[i],3+int(i));
    pid_t pid=-1;
    if(!rc)rc=a.spawn(&pid,argv[0],&actions,&attr,argv,env);
    a.actionsDestroy(&actions);a.attrDestroy(&attr);
    return rc?-1:pid;
}
}
