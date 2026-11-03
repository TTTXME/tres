#include "../app/src/main/cpp/scam-bayer-quads.h"
using namespace scam;
int main(){
    if(!hybridBayerQzEligible(false,1,4096,3072,5,true,false,false))return 1;
    if(!hybridBayerQzEligible(false,1,4096,3072,128,true,false,false))return 2;
    if(hybridBayerQzEligible(true,1,4096,3072,30,true,false,false)
       ||hybridBayerQzEligible(false,2,4096,3072,30,true,false,false)
       ||hybridBayerQzEligible(false,1,0,3072,30,true,false,false)
       ||hybridBayerQzEligible(false,1,4096,-1,30,true,false,false)
       ||hybridBayerQzEligible(false,1,5000,3000,30,true,false,false)
       ||hybridBayerQzEligible(false,1,4096,3072,4,true,false,false)
       ||hybridBayerQzEligible(false,1,4096,3072,129,true,false,false)
       ||hybridBayerQzEligible(false,1,4096,3072,30,false,false,false)
       ||hybridBayerQzEligible(false,1,4096,3072,30,true,true,false)
       ||hybridBayerQzEligible(false,1,4096,3072,30,true,false,true))return 3;
    if(!hybridBayerQuadsEligible(1,true,true,false)
       ||hybridBayerQuadsEligible(0,true,true,false)
       ||hybridBayerQuadsEligible(-1,true,true,false)
       ||hybridBayerQuadsEligible(1,false,true,false)
       ||hybridBayerQuadsEligible(1,true,false,false)
       ||hybridBayerQuadsEligible(1,true,true,true))return 4;
    const uint64_t MiB=uint64_t(1)<<20;
    uint64_t bytes=123;
    if(!hybridBayerQuadStorage(2048,256,32,bytes)||bytes!=128*MiB)return 5;
    if(hybridBayerQuadStorage(2048,257,32,bytes)||bytes)return 6;
    if(hybridBayerQuadStorage(0,256,32,bytes)||bytes)return 7;
    if(hybridBayerQuadStorage(2048,-1,32,bytes)||bytes)return 8;
    if(hybridBayerQuadStorage(2048,256,1,bytes)||bytes)return 9;
    if(hybridBayerQuadStorage(2048,256,129,bytes)||bytes)return 10;
    if(hybridBayerQuadStorage(2147483647,2147483647,128,bytes)||bytes)return 11;
    if(hybridBayerQuadBudget(0)||hybridBayerQuadBudget(128*MiB)!=16*MiB
       ||hybridBayerQuadBudget(1024*MiB)!=128*MiB
       ||hybridBayerQuadBudget(~uint64_t(0))!=128*MiB)return 12;
    if(!hybridBayerQuadMemory(80*MiB,0,false,1024*MiB)
       ||!hybridBayerQuadMemory(64*MiB,80*MiB,true,1024*MiB)
       ||!hybridBayerQuadMemory(64*MiB,64*MiB,false,1024*MiB)
       ||!hybridBayerQuadMemory(80*MiB,80*MiB,false,2048*MiB))return 13;
    if(hybridBayerQuadMemory(80*MiB,0,false,0)
       ||hybridBayerQuadMemory(80*MiB,0,false,128*MiB)
       ||hybridBayerQuadMemory(64*MiB,80*MiB,false,1024*MiB)
       ||hybridBayerQuadMemory(0,0,false,1024*MiB)
       ||hybridBayerQuadMemory(80*MiB,64*MiB,true,1024*MiB)
       ||hybridBayerQuadMemory(80*MiB,~uint64_t(0),false,~uint64_t(0)))return 14;
    return 0;
}
