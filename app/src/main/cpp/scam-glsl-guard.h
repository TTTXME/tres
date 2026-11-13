#pragma once
// P662: the GPU programs' compile guard of the merge routes.
//  - scamGlslDump: SCAM_GLSL_DUMP=<dir> writes every compute program's whole source, as the driver gets it, to <dir> (one file a
//    source: <tag>-<FNV-1a 64>.comp / .vert / .frag). The host lanes compile the merge's Mali / PowerVR variants (llvmpipe is not Adreno) and
//    tools/check_glsl_loop_shadow.py --dump <dir> checks them as programs (its global / enclosing-scope loop leak test and glslc).
//  - ScamGlslRefusals: a source the driver refused to compile, remembered by its key (the driver and the whole source, the
//    program cache's own key). The vivo X200 Pro (Mali-G925, 31253, P612) refused blend0 with «3:676: S0001: Type mismatch ...
//    'int' and 'float'»: the helper thread's context, the local-align merge, its homography-only retry and the app's conservative
//    retry each built the same programs again - five contexts and five refusals a shot, every shot. A remembered refusal throws
//    the same «HYBRID GPU shader: ...» at once and is logged once per process; a new driver (GL_VERSION) or a changed source has
//    another key and compiles again.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

inline uint64_t scamGlslHash(const std::string& text,uint64_t hash=1469598103934665603ULL){
    for(unsigned char c:text){hash^=c;hash*=1099511628211ULL;}
    return hash;
}

inline void scamGlslDump(const char* tag,const std::string& source,const char* stage="comp"){
    const char* dir=std::getenv("SCAM_GLSL_DUMP");
    if(!dir||!*dir)return;
    ::mkdir(dir,0755);
    char name[64];std::snprintf(name,sizeof(name),"/%.24s-%016llx.%.4s",tag,static_cast<unsigned long long>(scamGlslHash(source)),stage);
    std::ofstream out(std::string(dir)+name,std::ios::binary|std::ios::trunc);
    out<<source;
}

#ifdef SCAM_GLSL_FAULTS
inline int& scamGlslInjectedRefusals(){static int n=0;return n;} // host check: the refusals SCAM_GLSL_REFUSE made at glCompileShader
#endif

struct ScamGlslRefusals {
    // A driver message that names a place in the source («3:676: S0001 ...», «ERROR: 0:12: ...», Mesa «0:12(3): error: ...») is the
    // compiler's verdict on the text: kept on disk. Anything else (an empty log, «out of memory», «Compile failed.») only for this
    // process, so a transient failure does not disable a program for good.
    static bool namesSourcePlace(const std::string& msg){
        for(size_t i=0;i+2<msg.size();++i){
            if(msg[i]>='0'&&msg[i]<='9'&&msg[i+1]==':'&&msg[i+2]>='0'&&msg[i+2]<='9')return true;
        }
        return false;
    }
    static std::mutex& lock(){static std::mutex m;return m;}
    static std::map<uint64_t,std::string>& known(){static std::map<uint64_t,std::string> m;return m;}
    static std::set<uint64_t>& logged(){static std::set<uint64_t> s;return s;}
    static std::string markerFile(const std::string& dir,const char* prefix,uint64_t key){
        if(dir.empty())return "";
        char name[48];std::snprintf(name,sizeof(name),"/%s%016llx.refused",prefix,static_cast<unsigned long long>(key));
        return dir+name;
    }
    // true (msg = the driver's message, firstTime = not yet logged in this process) when the source of `key` was refused before
    static bool find(uint64_t key,const std::string& dir,const char* prefix,std::string& msg,bool& firstTime,bool& fromDisk){
        std::lock_guard<std::mutex> l(lock());
        auto it=known().find(key);
        fromDisk=false;
        if(it==known().end()){
            const std::string file=markerFile(dir,prefix,key);
            if(file.empty())return false;
            std::ifstream in(file,std::ios::binary);
            if(!in)return false;
            std::string text((std::istreambuf_iterator<char>(in)),std::istreambuf_iterator<char>());
            if(text.empty())return false;
            it=known().emplace(key,text).first;fromDisk=true;
        }
        msg=it->second;
        firstTime=logged().insert(key).second;
        return true;
    }
    // the driver refused the source of `key` with `msg`; returns true when it was kept on disk
    static bool remember(uint64_t key,const std::string& dir,const char* prefix,const std::string& msg){
        std::lock_guard<std::mutex> l(lock());
        known()[key]=msg;logged().insert(key);
        const std::string file=markerFile(dir,prefix,key);
        if(file.empty()||!namesSourcePlace(msg))return false;
        const std::string tmp=file+".tmp"+std::to_string(getpid());
        {std::ofstream out(tmp,std::ios::binary|std::ios::trunc);if(!out)return false;out<<msg;if(!out){std::remove(tmp.c_str());return false;}}
        if(std::rename(tmp.c_str(),file.c_str())!=0){std::remove(tmp.c_str());return false;}
        return true;
    }
};
