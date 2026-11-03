#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>

using namespace scam;

static GLuint compile(const std::string& source){
    GLuint s=glCreateShader(GL_COMPUTE_SHADER);const char* p=source.c_str();glShaderSource(s,1,&p,nullptr);glCompileShader(s);
    GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[4096]{};glGetShaderInfoLog(s,sizeof(log),nullptr,log);throw std::runtime_error(log);}
    GLuint result=glCreateProgram();glAttachShader(result,s);glLinkProgram(result);glDeleteShader(s);
    glGetProgramiv(result,GL_LINK_STATUS,&ok);if(!ok)throw std::runtime_error("Tile fixture link failed");return result;
}
static void upload(GLuint p,GLuint binding,const void* data,size_t bytes,GLenum target=GL_SHADER_STORAGE_BUFFER){
    glBindBuffer(target,p);glBufferData(target,GLsizeiptr(bytes),data,GL_DYNAMIC_DRAW);glBindBufferBase(target,binding,p);
}
static std::vector<float> read(GLuint p,size_t values){
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,p);
    const auto* data=static_cast<const float*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(values*4),GL_MAP_READ_BIT));
    if(!data)throw std::runtime_error("Tile fixture readback failed");
    std::vector<float> result(data,data+values);if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("Tile fixture storage invalid");return result;
}
int main(){
    LaGpu context("");GLuint buffers[4]{};glGenBuffers(4,buffers);
    int fixtures=0,failures=0;size_t checked=0,rounded=0;float maximum=0;
    for(int block:{1,2,4}){
        std::string body=block==1?std::string("#define RIM_STATS 1\n#define CHROMA_PASS 1\n")+kHybMergeCommon+kHybChroma
            :"#define NAT_B "+std::to_string(block)+"\n#define NAT_R 3\n#define NAT_MARKED 0\n#define NAT_FILL 0\n#define NAT_BASEFILL 0\n#define NAT_DEMOSAIC 1\n"
                +std::string(kHybMergeCommon)+kHybNatAccess+kHybMergeMosaicFast;
        body.replace(body.rfind("void main(){"),12,"void unusedMain(){");
        body+=R"(
layout(std430,binding=2) writeonly buffer TileResult{vec4 result[];};
void main(){
    int g=kG.x,x=int(gl_GlobalInvocationID.x),y=int(gl_GlobalInvocationID.y);
    if(x>=kG.y||y>=size.y*g)return;
    vec2 X=vec2(float(x),float(2*cy0*g+chunkU+y));
#ifdef NAT_B
#if NAT_B==4
    vec2 S=g==4?X-1.5:(X+0.5)*(4.0/float(g))-2.0;
#else
    vec2 S=(X+0.5)*(2.0/float(g))-1.0;
#endif
    vec3 rgb=vec3(0.0);float detail=0.0;bool ok=natDemosaic(S,1.3,rgb,detail);
#else
    vec2 S=floor(X/float(g))+(g>1?(mod(X,float(g))+0.5)/float(g)-0.5:vec2(0.0));
    vec3 rgb=vec3(0.0);float detail=0.0;bool ok=bayerDemosaic(S,rgb,detail);
#endif
    result[y*kG.y+x]=ok?vec4(rgb,detail):vec4(0.0,0.0,0.0,-1.0);
}
)";
        const std::string prefix=std::string(kHybCommon)+kHybHelpers;
        GLuint reference=compile(prefix+body),tiled=compile(prefix+(block==1?hybridBayerTileSource(body):hybridNativeTileSource(body)));
        const int w=block==1?66:block==2?68:72,h=block==1?50:block==2?52:56;
        for(int cfa=0;cfa<4;cfa++)for(int marked:{0,1})for(int grid:{block,2*block})for(int chunk:{0,7}){
            const int ow=w/block*grid,oh=h/block*grid;
            std::vector<uint16_t> raw(size_t(w)*h);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                uint16_t value=uint16_t(20+(x*37+y*19+x*y*3)%850);
                if(x>20&&x<25&&y>15&&y<25)value=1023;
                if(marked){if((x+3*y)%97==0)value|=0x4000;if((x+5*y)%89==0)value|=0x8000;}
                raw[size_t(y)*w+x]=value;
            }
            upload(buffers[0],block==1?0:12,raw.data(),raw.size()*2);
            upload(buffers[1],2,nullptr,size_t(ow)*oh*16);
            std::vector<uint32_t> table(6*size_t(kHybridGpuFrames)*4,0);table[2]=h;
            const float fp[4]={1.3f,1.f,1.f,1.f},noise[4]={.0003f,.000001f,0.f,0.f};
            std::memcpy(table.data()+3*size_t(kHybridGpuFrames)*4,fp,16);std::memcpy(table.data()+4*size_t(kHybridGpuFrames)*4,noise,16);
            upload(buffers[2],0,table.data(),table.size()*4,GL_UNIFORM_BUFFER);
            std::vector<uint32_t> geometry(size_t(kHybridGpuFrames)*4,0);geometry[2]=h;
            upload(buffers[3],1,geometry.data(),geometry.size()*4,GL_UNIFORM_BUFFER);
            std::vector<float> expected;
            for(GLuint program:{reference,tiled}){
                glUseProgram(program);
                auto loc=[&](const char* name){return glGetUniformLocation(program,name);};
                glUniform2i(loc("size"),w/block,h/block);glUniform2i(loc("cfaShift"),cfa&1,cfa>>1);
                glUniform4i(loc("phaseColor"),0,1,1,2);glUniform4f(loc("black"),64,65,66,67);
                glUniform4f(loc("inv"),1.f/959,1.f/958,1.f/957,1.f/956);glUniform1f(loc("clipLevel"),.98f);
                glUniform1i(loc("markU"),marked);glUniform1i(loc("natMarkU"),marked);
                glUniform1i(loc("chunkU"),chunk);glUniform1i(loc("cy0"),1);glUniform4i(loc("kG"),grid,ow,0,0);
                glUniform4f(loc("bdU"),1,1,1,4);glUniform1f(loc("bdAliasU"),1);glUniform2f(loc("baseNoise"),.0003f,.000001f);
                glUniform4i(loc("natU"),block,w,h,3);glUniform4i(loc("natV"),block==4?2:1,1,0,0);
                glUniform4f(loc("natW"),1023,1022,0,0);glUniform4i(loc("natGF"),0,0,0,0);
                float gains[64];for(int k=0;k<64;k++)gains[k]=.9f+.003f*k;
                glUniform4fv(loc("natGain"),16,gains);glUniform4f(loc("natDemU"),1,1,.4f,block==4?5:3);
                glDispatchCompute((ow+7)/8,(oh+7)/8,1);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                auto actual=read(buffers[1],size_t(ow)*oh*4);
                if(expected.empty()){expected=std::move(actual);continue;}
                for(size_t k=0;k<actual.size();k++){
                    float diff=std::abs(expected[k]-actual[k]);maximum=std::max(maximum,diff);rounded+=diff!=0;++checked;
                    const bool status=k%4==3&&(expected[k]<0||actual[k]<0);
                    if(!std::isfinite(actual[k])||diff>(status?0.f:5e-6f*std::max(1.f,std::abs(expected[k])))){
                        if(failures++<8)std::printf("FAIL block=%d cfa=%d marked=%d grid=%d chunk=%d k=%zu ref=%.9g actual=%.9g\n",block,cfa,marked,grid,chunk,k,expected[k],actual[k]);
                    }
                }
            }
            ++fixtures;
        }
        glDeleteProgram(reference);glDeleteProgram(tiled);
    }
    glDeleteBuffers(4,buffers);if(glGetError()!=GL_NO_ERROR)++failures;
    std::printf("check_bayer_tile_gpu: %d fixtures, %zu components, %zu rounded, max %.9g, %d failures\n",fixtures,checked,rounded,maximum,failures);
    return failures?1:0;
}
