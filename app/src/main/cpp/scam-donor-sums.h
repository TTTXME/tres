#pragma once
#include <stdexcept>
#include <string>

inline bool hybridDonorSumsEligible(bool split,int grid,int frames,bool normals,int bindings){
    return split&&grid==2&&frames==4&&normals&&bindings>21;
}

inline std::string hybridDonorSumsMerge(std::string source){
    const auto main=source.rfind("void main(){");
    const auto base=source.find("        // Base frame last:",main);
    if(main==std::string::npos||base==std::string::npos)
        throw std::runtime_error("Donor sums merge anchors");
    source.insert(base,R"(
        if(frames<kD.x){
            int si=((y*g+sy-oy0)*ow+x*g+sx)*5;
            donorSums[si]=a.num.x;donorSums[si+1]=a.num.z;
            donorSums[si+2]=a.den.x;donorSums[si+3]=a.den.y;donorSums[si+4]=a.den.z;
        }
)");
    source.insert(main,"layout(std430,binding=21) buffer DonorSums{float donorSums[];};\n");
    return source;
}

inline std::string hybridDonorSumsChroma(std::string source){
    const auto main=source.find("void main(){");
    const auto first=source.find("    Acc a;initAcc(a);",main);
    const auto last=source.find("    vec3 num0=a.num,den0=a.den;",first);
    if(main==std::string::npos||first==std::string::npos||last==std::string::npos)
        throw std::runtime_error("Donor sums chroma anchors");
    // The base is evaluated here with the original chroma-pass weighting, including its dezipper convention.
    source.replace(first,last-first,R"(
    Acc a;initAcc(a);int si=i*5;
    a.num=vec3(donorSums[si],0.0,donorSums[si+1]);
    a.den=vec3(donorSums[si+2],donorSums[si+3],donorSums[si+4]);
    float frames=0.0;
    for(int f=1;f<frameCount;f++){
        float r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
        if(r<0.004)continue;
        if(int(fParam[f].w)!=5)frames+=r/max(fParam[f].y,1.0e-6);
    }
)");
    source.insert(main,"layout(std430,binding=21) readonly buffer DonorSums{float donorSums[];};\n");
    return source;
}
