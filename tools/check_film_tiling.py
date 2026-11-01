from pathlib import Path
import argparse,shutil,subprocess,tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/cpp/third_party/spektrafilm/SpektraVulkanRenderer.cpp').read_text()
a=source.index('  uint64_t scameraTilePixelCapacity(');z=source.index('\n  #include "ScameraDiffusionTiles.h"',a)
capacity=source[a:z]
a=source.index('    const bool exceedsStorage=');z=source.index('\n    if(stagedSupported',a)
selection=source[a:z].replace('impl_->','')
check=r"""
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <iostream>
#include "SpektraParameters.h"
using namespace spektrafilm;
struct Planner {
 struct Limits{uint64_t maxStorageBufferRange;};
 struct Properties{Limits limits;} physicalDeviceProperties;
 uint64_t scratchBudgetBytes;
CAPACITY
 bool staged(const RenderParams& params,uint64_t pixels,int componentBytes) const {
  const bool spatial=params.grainEnabled||params.cameraDiffusionEnabled||params.halationEnabled||params.dirCouplersAmount>0||params.scannerEnabled;
  struct Destination{int bytesPerComponent;} destination{componentBytes};
SELECTION
  return largeSpatial;
 }
};
int main(){
 constexpr uint64_t M=1024*1024,pixels=4096ull*3072;
 RenderParams p;p.process=ProcessMode::PrintSimulation;p.grainEnabled=true;p.grainAmount=1;
 p.grainModel=GrainModel::Production;p.halationEnabled=true;p.cameraDiffusionEnabled=true;p.cameraDiffusionStrength=.5f;
 p.printDiffusionEnabled=false;p.scannerEnabled=false;p.dirCouplersAmount=0;
 Planner plan{{{2048*M}},2675813376ull};
 assert(pixels<plan.physicalDeviceProperties.limits.maxStorageBufferRange/36);
 assert(plan.scameraTilePixelCapacity(p,2,-1)<pixels&&plan.staged(p,pixels,2));
 assert(!plan.staged(p,512*384,2));
 plan.scratchBudgetBytes=4096*M;assert(!plan.staged(p,pixels,2));
 plan.physicalDeviceProperties.limits.maxStorageBufferRange=128*M;assert(plan.staged(p,pixels,2));
 plan.physicalDeviceProperties.limits.maxStorageBufferRange=2048*M;plan.scratchBudgetBytes=64*M;
 assert(!plan.staged(p,pixels,2));
 for(unsigned bits=0;bits<128;bits++){
  p.grainEnabled=bits&1;p.halationEnabled=bits&2;p.cameraDiffusionEnabled=bits&4;
  p.printDiffusionEnabled=bits&8;p.scannerEnabled=bits&16;p.dirCouplersAmount=bits&32?1:0;
  p.process=bits&64?ProcessMode::ScanNegative:ProcessMode::PrintSimulation;
  for(uint64_t limit:{128*M,256*M,2048*M}){
   plan.physicalDeviceProperties.limits.maxStorageBufferRange=limit;uint64_t previous=0;
   for(uint64_t budget:{32*M,64*M,256*M,512*M,2048*M,4096*M}){
    plan.scratchBudgetBytes=budget;auto n=plan.scameraTilePixelCapacity(p,2,-1);
    assert(n>=previous);assert(n<=limit/16);previous=n;
    bool spatial=p.grainEnabled||p.cameraDiffusionEnabled||p.halationEnabled||p.dirCouplersAmount>0||p.scannerEnabled;
    if(spatial&&pixels>limit/36)assert(plan.staged(p,pixels,2));
   }
  }
 }
 std::cout<<"PASS film budget routing, legacy storage routing, small/core preservation, milestone headroom and 2304 capacity cases\n";
}
""".replace('CAPACITY',capacity).replace('SELECTION',selection)
p=argparse.ArgumentParser();p.add_argument('--cxx',default=shutil.which('c++') or shutil.which('clang++'));p.add_argument('--android-serial');p.add_argument('--android-sdk',type=Path);p.add_argument('--adb',default=shutil.which('adb') or 'adb');args=p.parse_args()
with tempfile.TemporaryDirectory(prefix='scamera-film-tiling-') as folder:
 src=Path(folder)/'check.cpp';src.write_text(check);binary=Path(folder)/'check'
 if args.android_serial:
  assert args.android_sdk
  command=[str(args.android_sdk/'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'),'--target=aarch64-linux-android26','-fPIE','-pie','-static-libstdc++']
 else:
  assert args.cxx,'C++17 compiler required';command=[args.cxx]
 subprocess.run([*command,'-std=c++17','-O2','-I'+str(root/'app/src/main/cpp/third_party/spektrafilm'),str(src),'-o',str(binary)],check=True)
 if args.android_serial:
  adb=[args.adb,'-s',args.android_serial];remote='/data/local/tmp/scamera-film-tiling-check'
  subprocess.run([*adb,'push',str(binary),remote],check=True,capture_output=True)
  subprocess.run([*adb,'shell','chmod','755',remote],check=True)
  result=subprocess.check_output([*adb,'shell',remote]).decode()
 else:result=subprocess.check_output([str(binary)]).decode()
 assert result.startswith('PASS'),result
 print(result,end='')
