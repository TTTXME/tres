from pathlib import Path
import argparse,shutil,subprocess,tempfile

root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf-8')
start=s.index('    int mapMethod=-1;')
end=s.index('    static GLint loc(',start)
methods=s[start:end]
prefix=r'''
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <string>
#include <functional>
#include <iostream>
using GLenum=unsigned;using GLuint=unsigned;using GLbitfield=unsigned;
using GLintptr=long;using GLsizeiptr=long;
constexpr unsigned GL_NO_ERROR=0,GL_FALSE=0,GL_SHADER_STORAGE_BUFFER=1,GL_COPY_READ_BUFFER=2,GL_COPY_WRITE_BUFFER=3;
constexpr unsigned GL_MAP_READ_BIT=1,GL_MAP_UNSYNCHRONIZED_BIT=2,GL_DYNAMIC_READ=4;
static unsigned value=0x12345678,errorCode=0;
static bool failMap=false,failUnmap=false;
static int deletes=0;
void glBindBuffer(GLenum,GLuint){}
const void* glMapBufferRange(GLenum,GLintptr,GLsizeiptr,GLbitfield flags){
 if((flags&GL_MAP_READ_BIT)&&(flags&GL_MAP_UNSYNCHRONIZED_BIT))throw std::runtime_error("invalid ES read-map flags");
 return failMap?nullptr:&value;
}
GLenum glGetError(){return errorCode;}
bool glUnmapBuffer(GLenum){return !failUnmap;}
void glFinish(){}
void glBufferData(GLenum,GLsizeiptr,const void*,GLenum){}
void glCopyBufferSubData(GLenum,GLenum,GLintptr,GLintptr,GLsizeiptr){}
void glGenBuffers(int,GLuint* p){*p=99;}
void glDeleteBuffers(int,const GLuint*){++deletes;}
struct Readback {
 static constexpr int kSlots=19;
 GLuint buffers[kSlots]{};size_t capacity[kSlots]{};
 std::function<void(std::string)> trace;
'''
suffix=r'''
};
int main(){
 Readback gpu;gpu.mapMethod=4;unsigned output=0;
 gpu.getBuffer(1,3,&output,sizeof(output),false);
 if(output!=value||deletes!=1)throw std::runtime_error("valid fresh-buffer readback changed");
 failUnmap=true;
 try{gpu.getBuffer(1,3,&output,sizeof(output),false);throw std::runtime_error("invalid storage accepted");}
 catch(const std::runtime_error& e){if(std::string(e.what())!="HYBRID GPU storage invalidated")throw;}
 if(deletes!=2)throw std::runtime_error("temporary buffer leaked after invalidation");
 failMap=true;failUnmap=false;errorCode=0x0505;
 try{gpu.getBuffer(1,3,&output,sizeof(output),false);throw std::runtime_error("failed map accepted");}
 catch(const std::runtime_error& e){if(std::string(e.what()).find("glError=1285")==std::string::npos)throw;}
 errorCode=0;
 try{gpu.getBuffer(1,3,&output,sizeof(output),false);throw std::runtime_error("failed null-error map accepted");}
 catch(const std::runtime_error& e){if(std::string(e.what()).find("glError=0")==std::string::npos)throw;}
 failMap=false;gpu.mapMethod=0;gpu.getBuffer(1,3,&output,sizeof(output),true);
 if(output!=value)throw std::runtime_error("fenced readback changed");
 std::cout<<"PASS valid transfer, invalidated storage refusal and cleanup, error preservation/reset, fenced transfer\n";
}
'''
parser=argparse.ArgumentParser()
parser.add_argument('--cxx',default=shutil.which('c++') or shutil.which('clang++'))
parser.add_argument('--android-serial')
parser.add_argument('--android-sdk',type=Path)
args=parser.parse_args()
with tempfile.TemporaryDirectory(prefix='scamera-readback-') as directory:
    source=Path(directory)/'check.cpp';source.write_text(prefix+methods+suffix)
    binary=Path(directory)/'check'
    if args.android_serial:
        assert args.android_sdk,'--android-sdk is required for Android execution'
        cc=args.android_sdk/'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
        command=[str(cc),'--target=aarch64-linux-android26','-fPIE','-pie','-static-libstdc++']
    else:
        assert args.cxx,'A C++17 compiler is required'
        command=[args.cxx]
    subprocess.run([*command,'-std=c++17','-O2',str(source),'-o',str(binary)],check=True)
    if args.android_serial:
        a=[shutil.which('adb') or 'adb','-s',args.android_serial]
        remote='/data/local/tmp/scamera-readback-check'
        subprocess.run([*a,'push',str(binary),remote],check=True,capture_output=True)
        subprocess.run([*a,'shell','chmod 755 '+remote],check=True)
        result=subprocess.check_output([*a,'shell',remote]).decode()
    else:
        result=subprocess.check_output([str(binary)]).decode()
    assert result.startswith('PASS'),result
    print(result,end='')
