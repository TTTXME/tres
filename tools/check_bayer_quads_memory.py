from pathlib import Path
import argparse,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--cxx',default=shutil.which('c++')or shutil.which('clang++'));args=parser.parse_args()
if not args.cxx:raise RuntimeError('A C++17 host compiler is required')
TEMP=tempfile.TemporaryDirectory(prefix='scamera-quads-memory-');P=Path(TEMP.name)
s=(ROOT/'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf8');a=s.index('    void qzQuadRelease(){');b=s.index('    std::string mergeBody,qzBody,qzQuadBody;',a)
methods=s[a:b]
prefix=r'''
#include "scam-bayer-quads.h"
#include <stddef.h>
using namespace scam;
using GLuint=unsigned;using GLenum=unsigned;using GLint=int;
constexpr unsigned GL_MAX_TEXTURE_SIZE=1,GL_MAX_ARRAY_TEXTURE_LAYERS=2,GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS=3,
GL_MAX_COMPUTE_IMAGE_UNIFORMS=4,GL_ACTIVE_TEXTURE=5,GL_TEXTURE9=9,GL_TEXTURE_2D_ARRAY=6,GL_RGBA16UI=7,
GL_NEAREST=8,GL_CLAMP_TO_EDGE=10,GL_TEXTURE_MIN_FILTER=11,GL_TEXTURE_MAG_FILTER=12,GL_TEXTURE_WRAP_S=13,
GL_TEXTURE_WRAP_T=14,GL_WRITE_ONLY=15,GL_TRUE=1;
static uint64_t available=uint64_t(2)<<30;
static int allocations=0,deletions=0,active=4,maxSize=16384,maxLayers=256,units=16,images=8;
inline uint64_t hybridMemAvailable(){return available;}
inline void glGetIntegerv(GLenum name,GLint* value){*value=name==GL_MAX_TEXTURE_SIZE?maxSize:name==GL_MAX_ARRAY_TEXTURE_LAYERS?maxLayers:name==GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS?units:name==GL_MAX_COMPUTE_IMAGE_UNIFORMS?images:active;}
inline void glBindImageTexture(GLuint,GLuint,GLint,unsigned,GLint,GLenum,GLenum){}
inline void glDeleteTextures(int,GLuint*){++deletions;}
inline void glGenTextures(int,GLuint* p){*p=123;}
inline void glBindTexture(GLenum,GLuint){}
inline void glActiveTexture(GLenum v){active=int(v);}
inline void glTexStorage3D(GLenum,int,GLenum,int,int,int){++allocations;}
inline void glTexParameteri(GLenum,GLenum,int){}
struct Quad {
    GLuint qzQuadTexture=0;int qzQuadWidth=0,qzQuadHeight=0,qzQuadLayers=0;
    size_t qzQuadBytes=0;
    void check(const char*){}
'''
suffix=r'''
};
int main(){
    Quad q;
    if(!q.qzQuadPrepare(2048,159,30)||allocations!=1||q.qzQuadBytes!=78151680||active!=4)return 1;
    if(!q.qzQuadPrepare(2048,120,30)||allocations!=1||active!=4)return 2;
    available=0;
    if(q.qzQuadPrepare(2048,120,30)||allocations!=1||q.qzQuadTexture||q.qzQuadBytes||deletions!=1)return 3;
    available=uint64_t(128)<<20;
    if(q.qzQuadPrepare(2048,159,30)||allocations!=1)return 4;
    available=uint64_t(2)<<30;
    if(q.qzQuadPrepare(16385,1,2)||allocations!=1)return 5;
    if(q.qzQuadPrepare(2048,1024,128)||allocations!=1)return 6;
    units=9;if(q.qzQuadPrepare(2048,159,30)||allocations!=1)return 7;units=16;
    images=0;if(q.qzQuadPrepare(2048,159,30)||allocations!=1)return 8;images=8;
    maxLayers=16;if(q.qzQuadPrepare(2048,159,30)||allocations!=1)return 9;maxLayers=256;
    if(!q.qzQuadPrepare(2048,128,30)||allocations!=2)return 10;
    available=uint64_t(1)<<30;
    if(q.qzQuadPrepare(2048,200,30)||allocations!=2||q.qzQuadTexture)return 11;
    available=uint64_t(2)<<30;
    if(!q.qzQuadPrepare(2048,200,30)||allocations!=3||active!=4)return 12;
    return 0;
}
'''
(P/'allocation-test.cpp').write_text(prefix+methods+suffix,encoding='utf8')
command=[args.cxx,'-std=c++17','-O2',str(P/'allocation-test.cpp'),'-I'+str(ROOT/'app/src/main/cpp'),'-o',str(P/'allocation-test.exe')]
r=subprocess.run(command,capture_output=True);assert r.returncode==0,(r.stdout+r.stderr).decode()
r=subprocess.run([str(P/'allocation-test.exe')]);assert r.returncode==0,('actual allocation guard',r.returncode)
print('PASS actual allocation methods: low/unknown/cap/overflow no new texture, reuse, capability fallback, active unit restoration')
