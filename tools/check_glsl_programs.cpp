// P662: the «Качество» merge context's programs as a Mali / PowerVR phone gets them, dumped for tools/check_glsl_loop_shadow.py
// --dump (strict GLSL ES 3.10 and the Mali-G925 loop-index leak emulated: the 31253 blend0 refusal, P612). llvmpipe is not Adreno,
// so HybridGpu builds the variants without SHARED_TILES; the program switches are set the ways the tunings set them (local
// alignment on / off, the partial ultrashort blend with US_BOX + US_CACHE or US_AA, DCG two-zone sources, the reject / blend
// options, the native mosaic programs, Bento's colour pass, cellsPlain and the single blend program of a learned compiler crash);
// then the fast route's programs and the F6 alignment's (its homography programs; the field's are built with the context).
// --refusals <dir> (built with -DSCAM_GLSL_FAULTS): a refusal of the driver is remembered (scam-glsl-guard.h). blend0 refused once
// (SCAM_GLSL_REFUSE): the context throws «HYBRID GPU shader: <message>» and logs it once with «remembered for this driver and
// source»; the next contexts of the process throw the same without compiling it or another line; a new process (the memory
// cleared) reads the marker, skips it with one «skipped» line; the local-align source (another key) is compiled and refused afresh; a
// message without a place in the source («out of memory») is kept for the process only; other programs are not touched.
//   usage: check_glsl_programs <dump dir> | --refusals <empty dir>    (LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless)
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <string>
#include <vector>

using namespace scam;

static int countDumped(const char* dir){
    int n=0;
    if(DIR* d=opendir(dir)){while(dirent* e=readdir(d))if(e->d_name[0]!='.')++n;closedir(d);}
    return n;
}

#ifdef SCAM_GLSL_FAULTS
static int refusals(const char* dir){
    hybridProgramCacheDir()=dir;
    setenv("SCAM_GLSL_REFUSE","blend0",1);
    const std::string spec="#define BLEND_PARTIAL 1\n#define US_BOX 1\n#define US_CLIP_VOTE 1\n";
    int failures=0;
    auto expect=[&](bool ok,const std::string& what){std::printf("%s %s\n",ok?"ok  ":"FAIL",what.c_str());if(!ok)++failures;};
    // one context: its lines and the exception's text
    auto build=[&](bool la,std::vector<std::string>& lines)->std::string{
        try{HybridGpu gpu(true,la,false,true,[&](const std::string& l){if(l.find("HYBRID GPU REFUSED: blend0 ")==0)lines.push_back(l);},
                          false,false,true,true,false,"",false,spec);}
        catch(const std::exception& e){return e.what();}
        return "";
    };
    auto count=[](const std::vector<std::string>& lines,const char* what){int n=0;for(const auto& l:lines)if(l.find(what)!=std::string::npos)++n;return n;};
    const std::string expected="HYBRID GPU shader: 0:1: S0001: injected refusal\n";
    std::vector<std::string> a,b,c,d,e;
    const std::string first=build(false,a);
    expect(first==expected,"the refusal throws the driver's message («"+first+"»)");
    expect(scamGlslInjectedRefusals()==1&&count(a,"REFUSED: blend0 by the driver: 0:1: S0001: injected refusal (remembered for this driver and source, P662)")==1,
           "logged once as refused and kept on disk");
    for(const auto& l:a)std::printf("     %s\n",l.c_str());
    const std::string second=build(false,b),third=build(false,c);
    expect(second==expected&&third==expected,"the next contexts throw the same message");
    expect(scamGlslInjectedRefusals()==1,"without compiling blend0 again ("+std::to_string(scamGlslInjectedRefusals())+" compile)");
    expect(b.empty()&&c.empty(),"and without another line (the refusal is logged once a process)");
    ScamGlslRefusals::known().clear();ScamGlslRefusals::logged().clear(); // a new worker process
    const std::string fourth=build(false,d);
    expect(fourth==expected&&scamGlslInjectedRefusals()==1&&count(d,"skipped: this driver refused the same source in an earlier worker (P662)")==1,
           "a new process reads the marker: the same message, no compile");
    for(const auto& l:d)std::printf("     %s\n",l.c_str());
    const std::string fifth=build(true,e);
    expect(fifth==expected&&scamGlslInjectedRefusals()==2,"another source (local alignment) is compiled and refused afresh");
    setenv("SCAM_GLSL_REFUSE_MSG","out of memory",1);
    unsetenv("SCAM_GLSL_REFUSE");
    std::vector<std::string> f;
    expect(build(false,f).empty()==false,"the remembered refusal holds with SCAM_GLSL_REFUSE unset (the marker, not the injection)");
    setenv("SCAM_GLSL_REFUSE","blend1",1);
    ScamGlslRefusals::known().clear();ScamGlslRefusals::logged().clear();
    std::vector<std::string> g;
    { // blend1 refused with a message that names no place: this process only
        try{HybridGpu gpu(true,false,false,true,nullptr,false,false,true,true,false,"",false,spec+"#define US_PIXMARK 1\n");}catch(const std::exception&){}
        int markers=0;
        if(DIR* dd=opendir(dir)){while(dirent* en=readdir(dd))if(std::string(en->d_name).find(".refused")!=std::string::npos)++markers;closedir(dd);}
        expect(markers==2,"«out of memory» is not kept on disk ("+std::to_string(markers)+" markers: blend0 of both sources)");
    }
    unsetenv("SCAM_GLSL_REFUSE");unsetenv("SCAM_GLSL_REFUSE_MSG");
    ScamGlslRefusals::known().clear();ScamGlslRefusals::logged().clear();
    try{HybridGpu gpu(true,false,false,true,nullptr,false,false,true,false,false,"",false,"");std::printf("ok   a context without the blend builds\n");}
    catch(const std::exception& ex){std::printf("FAIL a context without the blend: %s\n",ex.what());++failures;}
    std::printf("%s\n",failures?"check_glsl_programs --refusals: FAIL":"check_glsl_programs --refusals: OK");
    return failures?1:0;
}
#endif

int main(int argc,char** argv){
#ifdef SCAM_GLSL_FAULTS
    if(argc==3&&std::string(argv[1])=="--refusals")return refusals(argv[2]);
#endif
    if(argc!=2){std::fprintf(stderr,"usage: check_glsl_programs <dump dir>\n");return 2;}
    setenv("SCAM_GLSL_DUMP",argv[1],1);
    HybridTuning t; // the defaults
    HybridTuning all=t; // every reject / blend program switch on
    all.rejPool=3.f;all.rejNoiseCdm=1.f;all.rejMatchBlur=1.f;all.rejPerChannel=1.f;all.rejVarExact=1.f;all.rejMeanDiff=1.f;all.rejClipSym=1.f;
    all.rejKneeLo=0.1f;all.rejKneeHi=0.5f;all.rejSkipUs=1;
    const std::string rejAll=hybridRejSpec(all),rejDefault=hybridRejSpec(t);
    const std::string partialBox="#define BLEND_PARTIAL 1\n#define SHASTA_SCOPE 1\n#define DEZIP 1\n#define US_FIELD_BILIN 1\n#define US_PIXMARK 1\n"
        "#define US_BOX 1\n#define US_CLIP_VOTE 1\n#define US_CACHE 1\n#define US_SOFTCLIP 0.900000\n#define REJ_PD 1\n";
    const std::string partialAa="#define BLEND_PARTIAL 1\n#define SHASTA_SCOPE 1\n#define DEZIP 1\n#define US_FIELD_BILIN 1\n#define US_PIXMARK 1\n"
        "#define US_CLIP_VOTE 1\n#define US_AA 0.500000\n#define US_SOFTCLIP 0.900000\n#define REJ_PD 1\n";
    struct Variant {const char* name;bool la,bento,nat,blend,dcg;std::string rej;bool blendOpts;std::string partial;bool cellsPlain,stagesOff;};
    const Variant variants[]={
        {"plain",false,false,true,true,false,"",false,"",false,false},
        {"local align, US box + cache, options",true,false,false,true,false,rejAll,true,partialBox,false,false},
        {"local align, US AA, DCG, native",true,false,true,true,true,rejAll,true,partialAa,false,false},
        {"defaults, Bento colour pass, DCG",false,true,false,false,true,rejDefault,false,hybridPartialSpec(t),false,false},
        {"defaults",true,false,false,true,false,rejDefault,hybridBlendOptions(t),hybridPartialSpec(t),false,false},
        {"cellsPlain, one blend program (a learned compiler crash)",true,false,false,true,false,rejAll,true,partialBox,true,true},
    };
    int failed=0;
    for(const Variant& v:variants){
        hybridCellsPlain()=v.cellsPlain;hybridBlendStagesOff()=v.stagesOff;
        try{
            HybridGpu gpu(true,v.la,v.bento,true,[](const std::string& line){
                    if(line.find("REFUSED")!=std::string::npos||line.find("not built")!=std::string::npos||line.find("reference blend")!=std::string::npos)
                        std::printf("  %s\n",line.c_str());
                },false,v.nat,true,v.blend,v.dcg,v.rej,v.blendOpts,v.partial);
            std::printf("ok   %s (%s)\n",v.name,gpu.compileMs.c_str());
        }catch(const std::exception& e){
            std::printf("FAIL %s: %s\n",v.name,e.what());++failed;
        }
    }
    int contexts=int(std::size(variants));
    // the fast route's programs (FastMergeGpu builds them at its start, the flow pick's on first use) and the F6 alignment's
    ++contexts;
    try{FastMergeGpu g("");g.flowPickProgram(true);FastMergeGpu::check("flow pick programs");std::printf("ok   fast route\n");}
    catch(const std::exception& e){std::printf("FAIL fast route: %s\n",e.what());++failed;}
    ++contexts;
    try{auto la=std::make_unique<LaGpu>();la->prepareHomography();std::printf("ok   F6 alignment\n");}
    catch(const std::exception& e){std::printf("FAIL F6 alignment: %s\n",e.what());++failed;}
    const int dumped=countDumped(argv[1]);
    std::printf("%d of %d contexts built, %d programs dumped to %s\n",contexts-failed,contexts,dumped,argv[1]);
    return failed||dumped==0?1:0;
}
