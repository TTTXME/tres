// P569: the fast route's Quad merge / guide programs without run-time indices are made from the reference text on every GPU (a
// pattern missing would silently give the reference's), and every program of the route still compiles and links with them.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
int main(){
    using namespace scam::fastmerge;
    const bool a=fastIndexFree(kFastMergeQuad)!=kFastMergeQuad,b=fastIndexFree(kFastMergeQuadQuad)!=kFastMergeQuadQuad,
               c=fastIndexFree(kFastGuideQuad)!=kFastGuideQuad,d=fastIndexFree(fastGuideQuadArrayFreeText())!=fastGuideQuadArrayFreeText();
    std::printf("index-free variants: merge_quad %d, merge_quad_quad %d, guide_quad %d, guide_quad (array-free) %d\n",a,b,c,d);
    if(!(a&&b&&c&&d))return 1;
    try{scam::FastMergeGpu g("");std::printf("fast route programs built (%s)\n",g.renderer.c_str());}
    catch(const std::exception& e){std::printf("fast route programs: %s\n",e.what());return 1;}
    std::printf("check_fast_index_free: OK\n");
    return 0;
}
