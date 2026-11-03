#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <fstream>
#include <stdexcept>
#include <unistd.h>
using namespace scam;
int main(){
    if(HybridTuning{}.bayerQuads!=1)throw std::runtime_error("Bayer quad default disabled");
    char pattern[]="/tmp/scamera-quads-XXXXXX";
#if defined(__ANDROID__)
    char androidPattern[]="/data/local/tmp/scamera-quads-XXXXXX";
    char* folder=mkdtemp(androidPattern);
#else
    char* folder=mkdtemp(pattern);
#endif
    if(!folder)throw std::runtime_error("Cannot create isolated tuning fixture");
    const std::string path=std::string(folder)+"/hybrid_tuning.txt";
    try{
        for(int value:{0,1,-1}){
            {std::ofstream out(path);out<<"bayerQuads "<<value<<"\n";}
            const HybridTuning t=loadHybridTuning(folder,{});
            if(t.bayerQuads!=value)throw std::runtime_error("Bayer quad override lost");
        }
        std::remove(path.c_str());rmdir(folder);
    }catch(...){std::remove(path.c_str());rmdir(folder);throw;}
    std::puts("PASS Bayer quad default and real tuning overrides");
}
