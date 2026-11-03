#include "../app/src/main/cpp/scam-bayer-split.h"
#include "../app/src/main/cpp/scam-bayer-tile.h"
#include "../app/src/main/cpp/scam-native-tile.h"
#include "../app/src/main/cpp/scam-donor-sums.h"
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>

std::string shader(const std::string& source,const char* name){
    const std::string marker=std::string("static const char* ")+name+"=R\"(";
    const auto at=source.find(marker),end=source.find(")\";",at);
    if(at==std::string::npos||end==std::string::npos)throw std::runtime_error(name);
    return source.substr(at+marker.size(),end-at-marker.size());
}
int main(int argc,char** argv){
    if(argc!=3)return 2;
    try{
        std::ifstream input(argv[1]);std::ostringstream text;text<<input.rdbuf();
        std::string source=text.str();source.erase(std::remove(source.begin(),source.end(),'\r'),source.end());
        const std::string common=shader(source,"kHybCommon");
        const std::string helpers=shader(source,"kHybHelpers");
        const std::string merge=shader(source,"kHybMergeCommon");
        const std::string chroma=shader(source,"kHybChroma");
        const auto split=hybridBayerSplitSources(chroma.c_str());
        const std::string prefix="#define RIM_STATS 1\n#define CHROMA_PASS 1\n";
        std::filesystem::create_directories(argv[2]);
        int count=0;
        for(const auto& body:{chroma,split[1]}){
            const std::string tiled=hybridBayerTileSource(prefix+merge+body);
            const auto main=tiled.rfind("void main(){"),load=tiled.find("bayerLoadTile();",main);
            const auto firstReturn=tiled.find("return;",main);
            if(load==std::string::npos||load>firstReturn||tiled.find("float A[81];")!=std::string::npos)
                throw std::runtime_error("Bayer tile synchronization/source contract");
            for(bool local:{false,true}){
                std::ofstream out(std::filesystem::path(argv[2])/("bayer-"+std::to_string(count++)+".comp"));
                out<<common<<(local?"#define LOCAL_ALIGN 1\n":"")<<helpers<<tiled;
            }
        }
        for(const char* broken:{"", "void main(){return;}"}){
            bool rejected=false;
            try{hybridBayerTileSource(broken);}catch(const std::runtime_error&){rejected=true;}
            if(!rejected)throw std::runtime_error("Broken shader accepted");
        }
        const std::string native=shader(source,"kHybNatAccess")+shader(source,"kHybMergeMosaicFast");
        for(int block:{2,4})for(int fill:{0,1}){
            const std::string definitions="#define NAT_B "+std::to_string(block)+"\n#define NAT_R 3\n#define NAT_MARKED 0\n#define NAT_FILL "
                +std::to_string(fill)+"\n#define NAT_BASEFILL 0\n#define NAT_DEMOSAIC 1\n";
            const std::string tiled=hybridNativeTileSource(definitions+merge+native);
            const auto main=tiled.rfind("void main(){"),load=tiled.find("nativeLoadTile();",main);
            if(load==std::string::npos||load>tiled.find("return;",main))
                throw std::runtime_error("Native tile synchronization/source contract");
            std::ofstream out(std::filesystem::path(argv[2])/("native-"+std::to_string(count++)+".comp"));
            out<<common<<helpers<<tiled;
        }
        for(const char* broken:{"", "void main(){return;}"}){
            bool rejected=false;
            try{hybridNativeTileSource(broken);}catch(const std::runtime_error&){rejected=true;}
            if(!rejected)throw std::runtime_error("Broken native shader accepted");
        }
        const std::string donorBodies[]={hybridDonorSumsMerge(merge+shader(source,"kHybMergeMain1")),
            prefix+merge+hybridDonorSumsChroma(split[0])};
        for(const auto& body:donorBodies)for(bool local:{false,true}){
            std::ofstream out(std::filesystem::path(argv[2])/("donor-"+std::to_string(count++)+".comp"));
            out<<common<<(local?"#define LOCAL_ALIGN 1\n":"")<<helpers<<body;
        }
        if(!hybridDonorSumsEligible(true,2,4,true,24)
           ||hybridDonorSumsEligible(false,2,4,true,24)||hybridDonorSumsEligible(true,1,4,true,24)
           ||hybridDonorSumsEligible(true,2,3,true,24)||hybridDonorSumsEligible(true,2,4,false,24)
           ||hybridDonorSumsEligible(true,2,4,true,21))throw std::runtime_error("Donor sums route contract");
        for(auto function:{hybridDonorSumsMerge,hybridDonorSumsChroma}){
            bool rejected=false;try{function("void main(){return;}");}catch(const std::runtime_error&){rejected=true;}
            if(!rejected)throw std::runtime_error("Broken donor sums shader accepted");
        }
        size_t sites=0;
        for(int grid:{1,2,4,8})for(int width:{64,66,70,4080,4096})for(int chunk:{0,1,8,58,64,128}){
            const std::vector<int> points={0,1,7,8,width*grid-2,width*grid-1};
            for(int x:points)for(int y:points){
                const int groupX=x/8*8,groupY=y/8*8;
                const int rawX=x/grid,rawY=(chunk+y)/grid;
                const int originX=groupX/grid-4,originY=(chunk+groupY)/grid-4;
                for(int q=0;q<81;q++){
                    const int tileX=rawX-groupX/grid+q%9,tileY=rawY-(chunk+groupY)/grid+q/9;
                    if(tileX<0||tileX>=16||tileY<0||tileY>=16
                       ||originX+tileX!=rawX+q%9-4||originY+tileY!=rawY+q/9-4)
                        throw std::runtime_error("Bayer tile geometry");
                    ++sites;
                }
            }
        }
        size_t nativeSites=0;
        for(int block:{2,4})for(int grid:{block,2*block})for(int width:{64,66,70,4080,4096})
        for(int cy0:{0,1,29,128})for(int chunk:{0,1,8,58,64,128}){
            auto sensor=[&](int p){
                const float x=float(p);
                return block==4&&grid==4?x-1.5f:(x+0.5f)*(float(block)/grid)-0.5f*block;
            };
            const std::vector<int> points={0,1,7,8,width*grid-2,width*grid-1};
            for(int x:points)for(int y:points){
                const int nx=int(std::floor(sensor(x)+0.5f)),ny=int(std::floor(sensor(2*cy0*grid+chunk+y)+0.5f));
                const int ox=int(std::floor(sensor(x/8*8)+0.5f))-6;
                const int oy=int(std::floor(sensor(2*cy0*grid+chunk+y/8*8)+0.5f))-6;
                auto check=[&](int sx,int sy){
                    if(sx<ox||sy<oy||sx>=ox+20||sy>=oy+20)
                        throw std::runtime_error("Native tile geometry");
                    ++nativeSites;
                };
                const int nc=block==2?3:5;
                const int cx=int(std::floor(nx*0.5f))-nc/2,cy=int(std::floor(ny*0.5f))-nc/2;
                for(int j=0;j<nc;j++)for(int i=0;i<nc;i++)
                    for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)check(2*(cx+i)+dx,2*(cy+j)+dy);
                for(int j=-4;j<=4;j++)for(int i=-4;i<=4;i++)check(nx+i,ny+j);
            }
        }
        std::cout<<count<<" actual shader variants exported, "<<sites<<" Bayer and "<<nativeSites<<" native sensor-site mappings checked\n";
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
