// P569: the fast route's Quad merge / guide programs without run-time indices are made from the reference text on every GPU (a
// pattern missing would silently give the reference's), and every program of the route still compiles and links with them
// (P601: the flow pick's programs too; P675: the rejection with the night motion test).
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
int main(){
    using namespace scam::fastmerge;
    const bool a=fastIndexFree(kFastMergeQuad)!=kFastMergeQuad,b=fastIndexFree(kFastMergeQuadQuad)!=kFastMergeQuadQuad,
               c=fastIndexFree(kFastGuideQuad)!=kFastGuideQuad,d=fastIndexFree(fastGuideQuadArrayFreeText())!=fastGuideQuadArrayFreeText();
    std::printf("index-free variants: merge_quad %d, merge_quad_quad %d, guide_quad %d, guide_quad (array-free) %d\n",a,b,c,d);
    if(!(a&&b&&c&&d))return 1;
    // P601: and the flow pick's programs (built on first use: kFastFlowPick, kFastFlowBg, kFastCyclopsRescue)
    try{scam::FastMergeGpu g("");g.flowPickProgram(true);scam::FastMergeGpu::check("flow pick programs");
        if(!g.prog[scam::FastMergeGpu::pFlowPick]||!g.prog[scam::FastMergeGpu::pFlowBg]||!g.prog[scam::FastMergeGpu::pCyclopsRescue])throw std::runtime_error("a flow pick program missing");
        // P675: the rejection with the night motion test (built on first use) and the init's program back without it
        const GLuint plain=g.prog[scam::FastMergeGpu::pReject];
        if(!g.selectReject(true)||!g.prog[scam::FastMergeGpu::pReject]||g.prog[scam::FastMergeGpu::pReject]==plain)throw std::runtime_error("the night motion rejection did not build");
        if(!g.selectReject(false)||g.prog[scam::FastMergeGpu::pReject]!=plain)throw std::runtime_error("the rejection without the night motion test not restored");
        scam::FastMergeGpu::check("night motion rejection");
        std::printf("fast route programs built (%s), night motion rejection too\n",g.renderer.c_str());}
    catch(const std::exception& e){std::printf("fast route programs: %s\n",e.what());return 1;}
    std::printf("check_fast_index_free: OK\n");
    return 0;
}
