precision highp float;
precision highp int;
#define LAYOUT //
LAYOUT
layout(rgba16ui,binding=0) readonly uniform highp uimage2D SourceBits;
layout(std430,binding=0) buffer packedHalf { uint words[]; };
uniform int originY;
uniform int rowsU;
void main(){
    ivec2 p=ivec2(gl_GlobalInvocationID.xy);
    ivec2 size=imageSize(SourceBits);
    if(p.x>=size.x||p.y>=rowsU)return;
    uvec4 v=imageLoad(SourceBits,p+ivec2(0,originY));
    int i=(p.y*size.x+p.x)*2;
    words[i]=v.x|(v.y<<16);
    words[i+1]=v.z|(v.w<<16);
}
