#pragma once
#include <stdexcept>
#include <string>

inline std::string hybridNativeTileSource(std::string source){
    const auto first=source.find("bool natDemosaic(vec2 S,float g,out vec3 rgb,out float detail){");
    const auto last=source.find("\n}\n#endif",first);
    if(first==std::string::npos||last==std::string::npos)
        throw std::runtime_error("Native tile function anchors");
    std::string function=source.substr(first,last+2-first);
    size_t count=0;
    for(size_t at=0;(at=function.find("natSite(0,",at))!=std::string::npos;){
        function.replace(at,10,"nativeTileSite(");at+=15;++count;
    }
    if(count!=5)throw std::runtime_error("Native tile sample anchors");
    const std::string helpers=R"(
shared float nativeTileValues[400];
shared uint nativeTileFlags[400];
ivec2 nativeTileOrigin(){
    int g=kG.x;
    vec2 X=vec2(float(int(gl_WorkGroupID.x)*8),float(2*cy0*g+chunkU+int(gl_WorkGroupID.y)*8));
#if NAT_B==4
    vec2 S=g==4?X-1.5:(X+0.5)*(4.0/float(g))-2.0;
#else
    vec2 S=(X+0.5)*(2.0/float(g))-1.0;
#endif
    return ivec2(floor(S+0.5))-ivec2(6);
}
float nativeTileSite(int x,int y,out uint flags){
    ivec2 p=ivec2(x,y)-nativeTileOrigin();int at=p.y*20+p.x;
    flags=nativeTileFlags[at];return nativeTileValues[at];
}
void nativeLoadTile(){
    ivec2 p=nativeTileOrigin();
    for(int at=int(gl_LocalInvocationIndex);at<400;at+=64){
        uint flags;float v=natSite(0,p.x+at%20,p.y+at/20,flags);
        nativeTileValues[at]=v;nativeTileFlags[at]=flags;
    }
    barrier();
}
)";
    source.replace(first,last+2-first,helpers+function);
    const auto main=source.rfind("void main(){");
    if(main==std::string::npos)throw std::runtime_error("Native tile main anchor");
    source.insert(main+std::string("void main(){").size(),"\n    nativeLoadTile();\n");
    return source;
}
