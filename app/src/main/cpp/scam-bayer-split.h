#pragma once
#include <array>
#include <stdexcept>
#include <string>

inline bool hybridBayerSplitEligible(int enabled,bool native,int grid,int frames,int mode,
                                     float widen,float base,float chroma) {
    return enabled>0&&!native&&grid==1&&frames>=2&&frames<=4&&(mode&4)!=0
        &&widen>0.f&&base>0.f&&chroma>0.f;
}

inline std::array<std::string,2> hybridBayerSplitSources(const char* original) {
    const std::string source(original);
    const auto main=source.find("void main(){");
    const auto split=source.find("    // P282: the base");
    const auto detail=source.find("    vec3 d;float detail;",split);
    if(main==std::string::npos||split==std::string::npos||detail==std::string::npos)
        throw std::runtime_error("Bayer split source anchors");
    std::array<std::string,2> shaders={source.substr(0,split)+"}\n",source.substr(0,main)+R"(
void main(){
    int g=kG.x,ow=kG.y,w2=size.x/2;
    int ox=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(ox>=ow||row>=2*(cy1-cy0)*g)return;
    int x=ox/g,y=2*cy0+row/g,sx=ox-(ox/g)*g,sy=row-(row/g)*g;
    int cx=x>>1,cy=y>>1;
    float m=kD.w>0.5?bmask[(cy-cy0)*w2+cx]:0.0;
    float wb=1.0-m;
    if(kE.x>=0.0||mergeModeU.y!=0)wb=0.0;
    if(wb<=0.0)return;
    float frames=0.0;
    for(int f=1;f<frameCount;f++){
        float r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
        if(r<0.004)continue;
        if(int(fParam[f].w)!=5)frames+=r/max(fParam[f].y,1.0e-6);
    }
    if(frames>=kD.x)return;
    float tDem=(1.0-smoothstep(0.0,kD.x,frames))*bdU.x;
    vec2 pos=vec2(float(x),float(y))+(g>1?(vec2(float(sx),float(sy))+0.5)/float(g)-0.5:vec2(0.0));
    int i=row*ow+ox;
)"+source.substr(detail)};
    // Preserve the original workgroup evaluation at the sensor boundary.
    const std::string coordinate="    int cx=x>>1,cy=y>>1;\n";
    for(auto& shader:shaders){
        const auto at=shader.find(coordinate);
        if(at==std::string::npos||shader.find(coordinate,at+1)!=std::string::npos)
            throw std::runtime_error("Bayer split coordinate anchor");
        shader.insert(at+coordinate.size(),"    if(y>=size.y-8)return;\n");
    }
    return shaders;
}
