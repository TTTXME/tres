#pragma once
#include <stdexcept>
#include <string>

inline std::string hybridBayerTileSource(std::string source){
    const auto first=source.find("bool bayerDemosaic(vec2 O,out vec3 rgb,out float detail){");
    const auto last=source.find("\n}\n#endif",first);
    if(first==std::string::npos||last==std::string::npos)
        throw std::runtime_error("Bayer tile function anchors");
    std::string function=source.substr(first,last+2-first);
    const auto load=function.find("    float A[81];");
    const auto gradient=function.find("    float dh=0.0,dv=0.0;",load);
    if(load==std::string::npos||gradient==std::string::npos)
        throw std::runtime_error("Bayer tile load anchors");
    function.replace(load,gradient-load,R"(
    for(int j=0;j<9;j++)for(int i=0;i<9;i++){
        ivec2 local=bayerTileLocal();
        if(bayerTileBad[(local.y+j)*16+local.x+i]!=0u)return false;
    }
)");
    for(size_t at=0;(at=function.find("A[",at))!=std::string::npos;){
        const auto end=function.find(']',at+2);
        if(end==std::string::npos)throw std::runtime_error("Bayer tile array index");
        const std::string read="bayerTileAt("+function.substr(at+2,end-at-2)+")";
        function.replace(at,end+1-at,read);at+=read.size();
    }
    const std::string helpers=R"(
shared float bayerTileValues[256];
shared uint bayerTileBad[256];
ivec2 bayerTileLocal(){
    int g=max(kG.x,1);
    return ivec2(int(gl_GlobalInvocationID.x)/g-int(gl_WorkGroupID.x)*8/g,
        (chunkU+int(gl_GlobalInvocationID.y))/g-(chunkU+int(gl_WorkGroupID.y)*8)/g);
}
float bayerTileAt(int q){
    ivec2 p=bayerTileLocal();return bayerTileValues[(p.y+q/9)*16+p.x+q%9];
}
void bayerLoadTile(){
    int g=max(kG.x,1),x=int(gl_WorkGroupID.x)*8/g-4;
    int y=2*cy0+(chunkU+int(gl_WorkGroupID.y)*8)/g-4;
    for(int q=int(gl_LocalInvocationIndex);q<256;q+=64){
        uint flags;float v=rawSite(0,x+q%16,y+q/16,flags);
        bayerTileValues[q]=v;bayerTileBad[q]=((flags&3u)!=0u||v>=clipLevel)?1u:0u;
    }
    barrier();
}
)";
    source.replace(first,last+2-first,helpers+function);
    const auto main=source.rfind("void main(){");
    if(main==std::string::npos)throw std::runtime_error("Bayer tile main anchor");
    // All workgroup invocations load and synchronize before any per-pixel early return.
    source.insert(main+std::string("void main(){").size(),"\n    bayerLoadTile();\n");
    return source;
}
