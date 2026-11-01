#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <random>

using namespace scam;

static GLuint program(const std::string& text) {
    GLuint shader=glCreateShader(GL_COMPUTE_SHADER);
    const char* source=text.c_str();glShaderSource(shader,1,&source,nullptr);glCompileShader(shader);
    GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[4096]{};glGetShaderInfoLog(shader,sizeof(log),nullptr,log);throw std::runtime_error(log);}
    GLuint p=glCreateProgram();glAttachShader(p,shader);glLinkProgram(p);glDeleteShader(shader);
    glGetProgramiv(p,GL_LINK_STATUS,&ok);
    if(!ok){char log[4096]{};glGetProgramInfoLog(p,sizeof(log),nullptr,log);throw std::runtime_error(log);}
    return p;
}

static void upload(GLuint buffer,int binding,const void* data,size_t bytes,GLenum target=GL_SHADER_STORAGE_BUFFER) {
    glBindBuffer(target,buffer);glBufferData(target,GLsizeiptr(bytes),data,GL_DYNAMIC_DRAW);glBindBufferBase(target,GLuint(binding),buffer);
}

static std::vector<float> read(GLuint buffer,size_t count) {
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);
    const auto* p=static_cast<const float*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(count*4),GL_MAP_READ_BIT));
    if(!p)throw std::runtime_error("US cache fixture readback failed");
    std::vector<float> result(p,p+count);glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);return result;
}

int main(int argc,char** argv) {
    LaGpu context("");
    constexpr int w=66,h=50,n=8192;
    const std::string prefix=std::string(kHybCommon)+kHybHelpers+"#define RIM_STATS 1\n#define BENTO_PASS 1\n"+kHybMergeCommon;
    std::string reference=kHybUsBlend,cached=hybridUsCachedBlend(kHybUsBlend);
    for(auto* s:{&reference,&cached}){
        const auto at=s->find("void main(){");
        if(at==std::string::npos)throw std::runtime_error("US cache fixture: blend main missing");
        s->replace(at,12,"void unusedBlendMain(){");
        *s+=R"(
layout(std430,binding=1) readonly buffer Points{vec4 points[];};
layout(std430,binding=2) writeonly buffer Result{vec4 result[];};
void main(){
    int k=int(gl_GlobalInvocationID.x);
    if(k>=8192||gl_GlobalInvocationID.y!=0u)return;
    uint cl=0u;vec3 rgb=usColourAt(1,points[k].xy,cl);
    result[k]=vec4(rgb,float(cl));
}
)";
    }
    if(argc==2){
        for(const auto& shader:std::vector<std::pair<std::string,std::string>>{{"reference",prefix+reference},{"cached",prefix+cached},{"prepare",prefix+hybridUsCacheProgram()}})
            std::ofstream(std::string(argv[1])+"/"+shader.first+".comp")<<shader.second;
    }
    GLuint ref=program(prefix+reference),test=program(prefix+cached),make=program(prefix+hybridUsCacheProgram());
    GLuint buffers[5]{};glGenBuffers(5,buffers);
    std::mt19937 random(448);
    std::vector<uint16_t> raw(size_t(w)*h);
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
        const int ramp=64+(x*31+y*17)%960;
        raw[size_t(y)*w+x]=uint16_t((x>20&&x<40&&y>10&&y<38)?1023:ramp);
    }
    std::vector<float> points(size_t(n)*4);
    for(int k=0;k<n;++k){
        points[4*k]=float(int(random()%(4*(w+24)))-48)*.25f;
        points[4*k+1]=float(int(random()%(4*(h+24)))-48)*.25f;
    }
    upload(buffers[1],1,points.data(),points.size()*4);
    upload(buffers[2],2,nullptr,size_t(n)*16);
    std::vector<uint32_t> geo(size_t(kHybridGpuFrames)*4,0);
    geo[4]=kHybridGpuFrames;geo[5]=uint32_t(-8);geo[6]=h+16;geo[7]=w+16;
    const size_t cacheBytes=(size_t(kHybridGpuFrames)+size_t(w+16)*(h+16))*16;
    upload(buffers[3],19,nullptr,cacheBytes);
    glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(geo.size()*4),geo.data());
    int failures=0,fixtures=0;float maximum=0;size_t changed=0;
    for(int cfa=0;cfa<4;++cfa)for(int marked=0;marked<2;++marked)for(float gain:{1.f,8.f}){
        std::vector<uint16_t> input=raw;
        if(marked)for(size_t k=0;k<input.size();++k){if(k%13==0)input[k]|=0x8000u;if(k%29==0)input[k]|=0x4000u;}
        upload(buffers[0],0,input.data(),input.size()*2);
        std::vector<uint32_t> table(6*size_t(kHybridGpuFrames)*4,0);
        table[4]=0;table[5]=0;table[6]=h;
        const size_t param=(3*size_t(kHybridGpuFrames)+1)*4;
        const float fp[4]={gain,1.f,1.f,5.f};std::memcpy(table.data()+param,fp,16);
        upload(buffers[4],0,table.data(),table.size()*4,GL_UNIFORM_BUFFER);
        const int phase[4]={cfa==0?0:cfa==3?2:1,cfa==1?0:cfa==2?2:1,cfa==2?0:cfa==1?2:1,cfa==3?0:cfa==0?2:1};
        for(GLuint p:{ref,test,make}){
            glUseProgram(p);
            glUniform2i(glGetUniformLocation(p,"size"),w,h);
            glUniform2i(glGetUniformLocation(p,"cfaShift"),cfa&1,cfa>>1);
            glUniform4iv(glGetUniformLocation(p,"phaseColor"),1,phase);
            glUniform4f(glGetUniformLocation(p,"black"),64.f,64.f,64.f,64.f);
            glUniform4f(glGetUniformLocation(p,"inv"),1.f/959,1.f/959,1.f/959,1.f/959);
            glUniform1i(glGetUniformLocation(p,"markU"),marked);
            glUniform1f(glGetUniformLocation(p,"clipLevel"),.98f);
            glUniform1i(glGetUniformLocation(p,"chunkU"),0);
        }
        glUseProgram(make);glUniform1i(glGetUniformLocation(make,"cacheFrameU"),1);
        glDispatchCompute((w+23)/8,4,1);
        glUniform1i(glGetUniformLocation(make,"chunkU"),32);glDispatchCompute((w+23)/8,4,1);
        glUniform1i(glGetUniformLocation(make,"chunkU"),64);glDispatchCompute((w+23)/8,(h+23-64)/8,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glUseProgram(ref);glDispatchCompute((n+7)/8,1,1);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        const auto expected=read(buffers[2],size_t(n)*4);
        glUseProgram(test);glDispatchCompute((n+7)/8,1,1);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        const auto actual=read(buffers[2],size_t(n)*4);
        for(size_t k=0;k<actual.size();++k){
            const float diff=std::abs(expected[k]-actual[k]);maximum=std::max(maximum,diff);changed+=diff!=0;
            const float tolerance=k%4==3?0.f:4e-6f*std::max({1.f,std::abs(expected[k]),std::abs(actual[k])});
            if(!std::isfinite(actual[k])||diff>tolerance){if(failures++<8)std::printf("FAIL cfa=%d marked=%d gain=%.0f component=%zu ref=%.9g actual=%.9g\n",cfa,marked,gain,k,expected[k],actual[k]);}
        }
        ++fixtures;
        // A cache miss must retain the original calculation, including the clip bits.
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[3]);uint32_t zero=0;
        glBufferSubData(GL_SHADER_STORAGE_BUFFER,7*sizeof(uint32_t),sizeof(zero),&zero);
        glUseProgram(test);glDispatchCompute((n+7)/8,1,1);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        const auto missed=read(buffers[2],size_t(n)*4);
        if(std::memcmp(expected.data(),missed.data(),expected.size()*4)!=0)++failures;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[3]);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER,7*sizeof(uint32_t),sizeof(uint32_t),geo.data()+7);
    }
    glDeleteBuffers(5,buffers);for(GLuint p:{ref,test,make})glDeleteProgram(p);
    if(glGetError()!=GL_NO_ERROR)++failures;
    std::printf("check_us_cache_gpu: %d fixtures, %d points each, %zu rounded components, max %.9g, %d failures\n",fixtures,n,changed,maximum,failures);
    return failures?1:0;
}
