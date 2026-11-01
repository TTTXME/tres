#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-la-gpu.h"
#include <fstream>
#include <cstdio>

int main(int argc,char** argv) {
    const char* path=argc>1?argv[1]:"app/src/main/assets/shaders/scamdn/ark_resolve.glsl";
    std::ifstream input(path);
    const std::string text((std::istreambuf_iterator<char>(input)),std::istreambuf_iterator<char>());
    const auto begin=text.find("float hash(highp ivec2 c) {");
    const auto end=text.find("\n}",begin);
    if(begin==std::string::npos||end==std::string::npos)return 2;
    const std::string source=std::string("#version 310 es\nprecision highp float;\nprecision highp int;\n")
        +text.substr(begin,end+2-begin)+R"(
layout(local_size_x=64) in;
layout(std430,binding=0) writeonly buffer Result {float values[];};
uniform ivec2 origin;
void main(){uint i=gl_GlobalInvocationID.x;values[i]=hash(ivec2(int(i)%257,int(i)/257)+origin);}
)";
    scam::LaGpu context("");
    const GLuint shader=glCreateShader(GL_COMPUTE_SHADER);
    const char* body=source.c_str();glShaderSource(shader,1,&body,nullptr);glCompileShader(shader);
    GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
    if(!ok){char error[4096];glGetShaderInfoLog(shader,sizeof(error),nullptr,error);std::puts(error);return 1;}
    const GLuint program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);
    glGetProgramiv(program,GL_LINK_STATUS,&ok);if(!ok)return 1;
    constexpr size_t count=1<<20;
    GLuint buffer;glGenBuffers(1,&buffer);glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);
    glBufferData(GL_SHADER_STORAGE_BUFFER,count*sizeof(float),nullptr,GL_STREAM_READ);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);glUseProgram(program);
    size_t failed=0;
    for(int offset:{0,8192,16320}){
        glUniform2i(glGetUniformLocation(program,"origin"),offset,offset/2);
        glDispatchCompute(GLuint(count/64),1,1);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        const auto* values=static_cast<const float*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,count*sizeof(float),GL_MAP_READ_BIT));
        if(!values)return 1;
        for(size_t i=0;i<count;i++){
            const uint32_t x=uint32_t(i%257)+uint32_t(offset),y=uint32_t(i/257)+uint32_t(offset/2);
            const uint32_t bits=(0x3504f333u*x*x+y)*(0xf1bbcdcbu*y*y+x);
            int32_t signedBits;std::memcpy(&signedBits,&bits,sizeof(bits));
            const float expected=float(signedBits)*0x1p-32f;
            if(values[i]!=expected){if(failed++<4)std::printf("FAIL %u,%u: %.9g != %.9g\n",x,y,values[i],expected);}
        }
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    }
    if(glGetError()!=GL_NO_ERROR)++failed;
    glDeleteBuffers(1,&buffer);glDeleteProgram(program);
    std::printf("resolve grain: %zu coordinates, %zu wrap/float mismatches\n",count*3,failed);
    return failed?1:0;
}
