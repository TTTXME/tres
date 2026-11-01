#pragma once

inline std::string hybridUsFunction(const std::string& source,const std::string& signature){
    const size_t begin=source.find(signature),brace=source.find('{',begin);
    if(begin==std::string::npos||brace==std::string::npos)throw std::runtime_error("US cache function missing: "+signature);
    int depth=1;size_t end=brace+1;
    for(;end<source.size()&&depth;++end){if(source[end]=='{')++depth;else if(source[end]=='}')--depth;}
    if(depth)throw std::runtime_error("US cache function unterminated: "+signature);
    return source.substr(begin,end-begin)+"\n";
}

inline void hybridUsCopyUniforms(GLuint source,GLuint target){
    glUseProgram(target);
    GLint count=0;glGetProgramiv(source,GL_ACTIVE_UNIFORMS,&count);
    for(GLint i=0;i<count;++i){
        char name[256]{};GLint size=0;GLenum type=0;GLsizei len=0;
        glGetActiveUniform(source,GLuint(i),sizeof(name),&len,&size,&type,name);
        const GLint from=glGetUniformLocation(source,name),to=glGetUniformLocation(target,name);
        if(from<0||to<0)continue;
        if(size!=1)throw std::runtime_error("US cache: unexpected default uniform array");
        GLfloat f[16]{};GLint n[4]{};
        switch(type){
            case GL_FLOAT:glGetUniformfv(source,from,f);glUniform1fv(to,1,f);break;
            case GL_FLOAT_VEC2:glGetUniformfv(source,from,f);glUniform2fv(to,1,f);break;
            case GL_FLOAT_VEC3:glGetUniformfv(source,from,f);glUniform3fv(to,1,f);break;
            case GL_FLOAT_VEC4:glGetUniformfv(source,from,f);glUniform4fv(to,1,f);break;
            case GL_INT:case GL_BOOL:glGetUniformiv(source,from,n);glUniform1iv(to,1,n);break;
            case GL_INT_VEC2:glGetUniformiv(source,from,n);glUniform2iv(to,1,n);break;
            case GL_INT_VEC3:glGetUniformiv(source,from,n);glUniform3iv(to,1,n);break;
            case GL_INT_VEC4:glGetUniformiv(source,from,n);glUniform4iv(to,1,n);break;
            default:throw std::runtime_error("US cache: unsupported uniform "+std::string(name));
        }
    }
}

inline std::string hybridUsCacheDeclarations(){
    return R"(
layout(std430,binding=19) buffer UsCache{uvec4 usCache[];};
)";
}

inline std::string hybridUsCachedBlend(const std::string& original){
    // Integer-site RGB stays FP32; interpolation and the centre's clip test keep their original order.
    std::string source=original;
    const auto fn=hybridUsFunction(source,"vec3 usColourAt(int f,vec2 O,inout uint cl){");
    std::string fallback=fn;
    fallback.replace(0,std::strlen("vec3 usColourAt"),"vec3 usColourOriginalAt");
    const std::string cached=R"(
vec3 usColourAt(int f,vec2 O,inout uint cl){
    ivec2 p=ivec2(floor(O));vec2 t=O-vec2(p);
    uvec4 geo=usCache[f];
    int x=p.x+8,y=p.y-int(geo.y);
    if(geo.w==0u||x<0||x+1>=int(geo.w)||y<0||y+1>=int(geo.z))return usColourOriginalAt(f,O,cl);
    uint at=geo.x+uint(y)*geo.w+uint(x);
    vec4 v0=uintBitsToFloat(usCache[at]),v1=uintBitsToFloat(usCache[at+1u]);
    vec4 v2=uintBitsToFloat(usCache[at+geo.w]),v3=uintBitsToFloat(usCache[at+geo.w+1u]);
    vec3 o=vec3(0.0);
    o+=v0.xyz*(1.0-t.x)*(1.0-t.y);
    o+=v1.xyz*t.x*(1.0-t.y);
    o+=v2.xyz*(1.0-t.x)*t.y;
    o+=v3.xyz*t.x*t.y;
    uint nearClip=uint(v0.w);float g=fParam[f].x;
    for(int c=0;c<3;c++)if((nearClip&(1u<<uint(c)))!=0u&&o[c]>=0.97*clipLevel*g)cl|=1u<<uint(c);
    return o;
}
)";
    source.replace(source.find(fn),fn.size(),fallback+cached);
    return hybridUsCacheDeclarations()+source;
}

inline std::string hybridUsCacheProgram(){
    std::string fn=hybridUsFunction(kHybUsBlend,"vec3 usColourAt(int f,vec2 O,inout uint cl){");
    fn.replace(0,std::strlen("vec3 usColourAt(int f,vec2 O,inout uint cl)"),"vec4 usColourSite(int f,vec2 O)");
    const std::string read="uint fl;float v=rawSite(f,x0+i,y0+j,fl);";
    const auto readAt=fn.find(read);if(readAt==std::string::npos)throw std::runtime_error("US cache: site read missing");
    fn.replace(readAt,read.size(),"int ti=(int(gl_LocalInvocationID.y)+j)*12+int(gl_LocalInvocationID.x)+i;uint fl=usSiteFlags[ti];float v=usSites[ti];");
    const std::string loop="for(int q=0;q<4;q++){";
    auto at=fn.find(loop);if(at==std::string::npos)throw std::runtime_error("US cache: quad loop missing");
    fn.replace(at,loop.size(),"{const int q=0;");
    const std::string sum="o+=s3*((q&1)!=0?t.x:1.0-t.x)*((q>>1)!=0?t.y:1.0-t.y);";
    at=fn.find(sum);if(at==std::string::npos)throw std::runtime_error("US cache: quad sum missing");
    fn.replace(at,sum.size(),"return vec4(s3,float(nearClip));");
    at=fn.find("#ifdef US_AA");if(at==std::string::npos)throw std::runtime_error("US cache: AA boundary missing");
    fn.erase(at);fn+="return vec4(0.0);\n}\n";
    return hybridUsCacheDeclarations()+"shared float usSites[144];\nshared uint usSiteFlags[144];\n"+hybridUsFunction(kHybUsBlend,"float usGreenDir(")+fn+R"(
uniform int cacheFrameU;
void main(){
    int f=cacheFrameU,x=int(gl_GlobalInvocationID.x),y=chunkU+int(gl_GlobalInvocationID.y);
    uvec4 geo=usCache[f];
    int bx=int(gl_WorkGroupID.x)*8-10,by=chunkU+int(gl_WorkGroupID.y)*8+int(geo.y)-2;
    for(int k=int(gl_LocalInvocationIndex);k<144;k+=64){
        uint fl;float v=rawSite(f,bx+k%12,by+k/12,fl);
        usSites[k]=v;usSiteFlags[k]=fl;
    }
    barrier();
    if(x>=int(geo.w)||y>=int(geo.z))return;
    vec4 v=usColourSite(f,vec2(float(x-8),float(y+int(geo.y))));
    usCache[geo.x+uint(y)*geo.w+uint(x)]=floatBitsToUint(v);
}
)";
}
