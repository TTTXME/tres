#pragma once
// P627 (owner, 2026-11-11: every processing mode stays on the mode the user chose - «Статика» never hands a shot to «Качество»):
// the static merge's third backend, the same programs (scam-static-shaders.h) written out in C++ on the same buffers with the
// same per-dispatch parameters. It runs when neither GPU backend can (no GLES 3.1 context, both failed mid-shot, the app's
// GPU-less retry after a worker died at GPU init): the shot is still «Статика»'s own reconstruction, only slower. Each program is
// a line-for-line transcription (the same float operations in the same order where the GPU's are defined; the field pass's sums
// and the counters in a different order: within float rounding of the GPU's - tools/check_static_merge compares).
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

namespace scam {
namespace staticmerge {

class StCpu : public StBackend {
public:
    std::array<std::vector<uint8_t>,kStBufs> buf;
    int threads=1;
    StCpu(){threads=std::clamp(int(std::thread::hardware_concurrency()),1,8);}
    const char* name() const override{return "CPU";}
    std::string describe() const override{return "C++ on "+std::to_string(threads)+" threads (the GPU programs written out)";}
    void alloc(int b,size_t bytes) override{bytes=std::max<size_t>(bytes,16);if(buf[size_t(b)].size()<bytes)buf[size_t(b)].resize(bytes);}
    void upload(int b,size_t off,const void* data,size_t bytes) override{std::memcpy(buf[size_t(b)].data()+off,data,bytes);}
    void zero(int b,size_t bytes) override{std::memset(buf[size_t(b)].data(),0,bytes);}
    void begin() override{}
    void submitWait() override{}
    void read(int b,size_t off,size_t bytes,void* dst) override{std::memcpy(dst,buf[size_t(b)].data()+off,bytes);}
    void stamp(int b,const std::vector<size_t>& words,uint32_t value) override{auto* m=reinterpret_cast<uint32_t*>(buf[size_t(b)].data());for(size_t w:words)m[w]=value;}
    void copy(int src,int dst,size_t bytes) override{std::memmove(buf[size_t(dst)].data(),buf[size_t(src)].data(),bytes);}
    size_t maxBuffer() const override{return size_t(1)<<34;}

    // ---- the programs' shared helpers (kStCommon / kStField)
    struct Ctx {
        const StParams* P=nullptr;const float* fi=nullptr;
        const uint32_t* raw=nullptr;const float* zv=nullptr;const float* fld=nullptr;
        int channel(int x,int y) const{
            const int dx=((x>>P->bs)&1)^(P->cfa&1),dy=((y>>P->bs)&1)^(P->cfa>>1);
            return (dx+dy)==0?0:((dx+dy)==2?2:1);
        }
        void hom(int base,float px,float py,float& ox,float& oy) const{
            const float d=fi[base+6]*px+fi[base+7]*py+fi[base+8];
            ox=(fi[base+0]*px+fi[base+1]*py+fi[base+2])/d;oy=(fi[base+3]*px+fi[base+4]*py+fi[base+5])/d;
        }
        float kern(float dd) const{
            const float a=std::abs(dd);
            if(P->s==1)return std::max(0.f,1.f-a);
            return 0.5f*(std::max(0.f,1.f-std::abs(a-0.5f))+std::max(0.f,1.f-(a+0.5f)));
        }
        float reach() const{return P->s==1?1.f:1.5f;}
        static float mixf(float a,float b,float t){return a*(1.f-t)+b*t;}
        void fieldAt(int k,float pbx,float pby,float& fx,float& fy) const{
            const float tx=pbx/float(P->tile)-0.5f,ty=pby/float(P->tile)-0.5f;
            const float flx=std::floor(tx),fly=std::floor(ty);
            const float ax=std::clamp(tx-flx,0.f,1.f),ay=std::clamp(ty-fly,0.f,1.f);
            const int x0=std::clamp(int(flx),0,P->ntx-1),y0=std::clamp(int(fly),0,P->nty-1);
            const int x1=std::clamp(int(flx)+1,0,P->ntx-1),y1=std::clamp(int(fly)+1,0,P->nty-1);
            const int b=k*P->ntx*P->nty;
            const float* v00=fld+2*(b+y0*P->ntx+x0);const float* v10=fld+2*(b+y0*P->ntx+x1);
            const float* v01=fld+2*(b+y1*P->ntx+x0);const float* v11=fld+2*(b+y1*P->ntx+x1);
            fx=mixf(mixf(v00[0],v10[0],ax),mixf(v01[0],v11[0],ax),ay);
            fy=mixf(mixf(v00[1],v10[1],ax),mixf(v01[1],v11[1],ax),ay);
        }
        void hrPos(int k,int x,int y,float& qx,float& qy) const{
            float bx,by;hom(k*32+9,float(x)+0.5f,float(y)+0.5f,bx,by);
            float fx,fy;fieldAt(k,bx,by,fx,fy);bx+=fx;by+=fy;
            qx=float(P->s)*bx;qy=float(P->s)*by-float(P->s*P->r0);
        }
        float rawAt(int k,int x,int y) const{
            const int row=y-int(fi[k*32+21]);
            if(row<0||row>=P->rows||x<0||x>=P->W||y<0||y>=P->H)return -1.f;
            const int idx=(k*P->rows+row)*P->W+x;
            const uint32_t wd=raw[idx>>1];
            const float dn=float((idx&1)==1?(wd>>16):(wd&65535u));
            const int g=32*P->K,site=((y&1)<<1)|(x&1);
            const float bl=fi[g+site];
            return (dn-bl)/std::max(fi[g+4]-bl,1.f)*fi[g+5+site]/fi[k*32+20];
        }
        bool forward(float qx,float qy,int c,float& v) const{
            const float r=reach();
            const int px0=int(std::floor(qx-0.5f-r))+1,py0=int(std::floor(qy-0.5f-r))+1;
            float acc=0.f;v=0.f;
            for(int jy=0;jy<3;++jy){
                const int py=py0+jy;const float wy=kern(qy-(float(py)+0.5f));
                if(wy<=0.f)continue;
                if(py<0||py>=P->RH)return false;
                for(int jx=0;jx<3;++jx){
                    const int px=px0+jx;const float wx=kern(qx-(float(px)+0.5f));
                    if(wx<=0.f)continue;
                    if(px<0||px>=P->WH)return false;
                    acc+=wx*wy*zv[size_t(py*P->WH+px)*4+size_t(c)];
                }
            }
            v=acc;return true;
        }
    };
    template<class F> void rows(int n,F f){ // rows 0..n-1 on the threads
        if(n<=0)return;
        const int t=std::min(threads,n);
        if(t<=1){for(int y=0;y<n;++y)f(y);return;}
        std::atomic<int> next{0};std::vector<std::thread> pool;
        for(int i=0;i<t;++i)pool.emplace_back([&]{for(int y;(y=next.fetch_add(1))<n;)f(y);});
        for(auto& th:pool)th.join();
    }
    float* F(int b){return b<0?nullptr:reinterpret_cast<float*>(buf[size_t(b)].data());}
    uint32_t* U(int b){return b<0?nullptr:reinterpret_cast<uint32_t*>(buf[size_t(b)].data());}

    void dispatch(const StDispatch& d) override{
        const StParams& P=d.p;Ctx c;c.P=&P;c.fi=F(d.bind[1]);
        switch(d.prog){
        case spInit:{ // kStInit
            c.raw=U(d.bind[2]);c.fld=F(d.bind[6]);
            float* zo=F(d.bind[3]);float* xo=F(d.bind[4]);const float* cd=F(d.bind[5]);
            rows(P.RH,[&](int py){for(int px=0;px<P.WH;++px){
                const float pbx=(float(px)+0.5f)/float(P.s),pby=(float(py)+0.5f)/float(P.s)+float(P.r0);
                float num[3]={0,0,0},den[3]={0,0,0};
                const float sg=0.6f*float(P.s);
                for(int k=0;k<P.K;++k){
                    if(c.fi[k*32+22]<0.5f)continue;
                    float fx,fy;c.fieldAt(k,pbx,pby,fx,fy);
                    float ux,uy;c.hom(k*32,pbx-fx,pby-fy,ux,uy);
                    const int cx0=int(std::floor(ux-0.5f))-1,cy0=int(std::floor(uy-0.5f))-1;
                    for(int ny=0;ny<3;++ny)for(int nx=0;nx<3;++nx){
                        const int cx=cx0+nx,cy=cy0+ny;
                        const float yv=c.rawAt(k,cx,cy);
                        if(yv<-0.5f)continue;
                        float qx,qy;c.hrPos(k,cx,cy,qx,qy);
                        const float dx=qx-(float(px)+0.5f),dy=qy-(float(py)+0.5f);
                        const float wgt=std::exp(-(dx*dx+dy*dy)/(2.f*sg*sg));
                        const int ch=c.channel(cx,cy);
                        num[ch]+=wgt*yv;den[ch]+=wgt;
                    }
                }
                float z[3];for(int i=0;i<3;++i)z[i]=num[i]/std::max(den[i],1e-6f);
                if(P.mode==1){
                    const int g=32*P.K;const int cw=int(c.fi[g+9]),c0=int(c.fi[g+10]),cr=int(c.fi[g+11]);
                    const float bsz=float(1<<P.bs);const float ccx=pbx/bsz-0.5f,ccy=pby/bsz-0.5f;
                    const float fx=std::floor(ccx),fy=std::floor(ccy);const float ax=std::clamp(ccx-fx,0.f,1.f),ay=std::clamp(ccy-fy,0.f,1.f);
                    const int x0=std::clamp(int(fx),0,cw-1),x1=std::clamp(int(fx)+1,0,cw-1);
                    const int y0=std::clamp(int(fy)-c0,0,cr-1),y1=std::clamp(int(fy)+1-c0,0,cr-1);
                    float dd[2];
                    for(int i=0;i<2;++i)dd[i]=Ctx::mixf(Ctx::mixf(cd[2*(y0*cw+x0)+i],cd[2*(y0*cw+x1)+i],ax),Ctx::mixf(cd[2*(y1*cw+x0)+i],cd[2*(y1*cw+x1)+i],ax),ay);
                    const float w0=0.25f,wr=den[0]>1e-6f?w0:0.f,wb=den[2]>1e-6f?w0:0.f;
                    const float gd=den[1]+wr+wb;
                    const float gg=gd>1e-6f?(num[1]+wr*(z[0]-dd[0])+wb*(z[2]-dd[1]))/gd:0.f;
                    z[0]=(num[0]+w0*(gg+dd[0]))/(den[0]+w0);z[1]=gg;z[2]=(num[2]+w0*(gg+dd[1]))/(den[2]+w0);
                } else {
                    const float gfill=den[1]>1e-6f?z[1]:(den[0]>1e-6f?z[0]:z[2]);
                    for(int i=0;i<3;++i)if(den[i]<=1e-6f)z[i]=gfill;
                }
                float* o=zo+size_t(py*P.WH+px)*4;float* o2=xo+size_t(py*P.WH+px)*4;
                o[0]=o2[0]=z[0];o[1]=o2[1]=z[1];o[2]=o2[2]=z[2];o[3]=o2[3]=0.f;
            }});
            break;}
        case spResid:{ // kStResid
            c.raw=U(d.bind[2]);c.zv=F(d.bind[3]);c.fld=F(d.bind[6]);
            float* sr=F(d.bind[4]);float* sw=F(d.bind[5]);float* srw=F(d.bind[7]);
            rows(int(d.gz)*P.rows,[&](int row){
                const int k=row/P.rows+P.frame0,wr=row%P.rows;
                if(k>=P.K)return;
                for(int x=0;x<P.W;++x){
                    const int y=wr+int(c.fi[k*32+21]);
                    const int o=(k*P.rows+wr)*P.W+x;
                    const float yv=c.rawAt(k,x,y);
                    float pred=0.f;bool ok=c.fi[k*32+22]>0.5f&&y>=0&&y<P.H&&yv>-0.5f;
                    if(ok){float qx,qy;c.hrPos(k,x,y,qx,qy);ok=c.forward(qx,qy,c.channel(x,y),pred);}
                    if(P.mode==0){
                        float var=c.fi[k*32+18]*std::max(pred,0.f)+c.fi[k*32+19];
                        const float ol=c.fi[k*32+24];
                        if(ol>0.f){const float kn=c.fi[k*32+23];
                            const float t=std::clamp((std::max(pred,0.f)*c.fi[k*32+20]-0.9f*kn)/(0.2f*kn),0.f,1.f);
                            var+=(ol-c.fi[k*32+19])*t*t*(3.f-2.f*t);}
                        const bool use=ok&&yv<P.clipLvl;
                        sr[o]=use?pred-yv:0.f;
                        sw[o]=use?std::min(P.sig2/std::max(var,1e-10f),16.f):0.f;
                    } else {
                        srw[2*o]=ok?pred-yv:0.f;srw[2*o+1]=ok?sw[o]:0.f;
                    }
                }
            });
            break;}
        case spRobust:{ // kStRobust
            const uint32_t* raw=U(d.bind[2]);const float* sr=F(d.bind[3]);const float* sw=F(d.bind[4]);float* st=F(d.bind[5]);
            auto raw2=[&](int k,int x,int yy){
                const int y=std::clamp(yy,0,P.H-1);
                int row=y-int(c.fi[k*32+21]);row=std::clamp(row,0,P.rows-1);
                const int xc=std::clamp(x,0,P.W-1);
                const int idx=(k*P.rows+row)*P.W+xc;const uint32_t wd=raw[idx>>1];
                const float dn=float((idx&1)==1?(wd>>16):(wd&65535u));
                const int g=32*P.K,site=((y&1)<<1)|(xc&1);
                return (dn-c.fi[g])/std::max(c.fi[g+4]-c.fi[g],1.f)*c.fi[g+5+site]/c.fi[k*32+20];
            };
            rows(int(d.gz)*P.rows,[&](int row){
                const int k=row/P.rows+P.frame0,wr=row%P.rows;
                if(k>=P.K)return;
                for(int x=0;x<P.W;++x){
                    const int o=(k*P.rows+wr)*P.W+x;
                    if(k==0||P.robK<=0.f){st[o]=1.f;continue;}
                    float n=0.f,srs=0.f,svs=0.f;
                    for(int dy=-2;dy<=2;++dy){const int rr=std::clamp(wr+dy,0,P.rows-1);
                        for(int dx=-2;dx<=2;++dx){const int t=(k*P.rows+rr)*P.W+std::clamp(x+dx,0,P.W-1);
                            const float wb=sw[t];if(wb<=0.f)continue;n+=1.f;srs+=sr[t];svs+=P.sig2/wb;}}
                    float w=1.f;
                    if(n>0.f){
                        const int y=wr+int(c.fi[k*32+21]);float gx,gy;
                        if(P.bs==0){gx=(raw2(k,x+2,y)-raw2(k,x-2,y))*0.25f;gy=(raw2(k,x,y+2)-raw2(k,x,y-2))*0.25f;}
                        else{const int bm=(1<<P.bs)-1;
                            const int xa=std::max(x-1,x&~bm),xb=std::min(x+1,(x&~bm)+bm),ya=std::max(y-1,y&~bm),yb=std::min(y+1,(y&~bm)+bm);
                            gx=(raw2(k,xb,y)-raw2(k,xa,y))/float(xb-xa);gy=(raw2(k,x,yb)-raw2(k,x,ya))/float(yb-ya);}
                        const float m=srs/n,vm=svs/(n*n)+P.robTau*P.robTau*(gx*gx+gy*gy)+1e-12f;
                        const float e=m*m/vm,k2=P.robK*P.robK;
                        w=1.f/(1.f+std::max(e-k2,0.f)/k2);
                    }
                    st[o]=w;
                }
            });
            break;}
        case spMin:{ // kStMin
            const float* st=F(d.bind[2]);const float* sw0=F(d.bind[3]);float* sw=F(d.bind[4]);uint32_t* cnt=U(d.bind[5]);const float* sr=F(d.bind[6]);
            std::vector<std::atomic<uint32_t>> acc(size_t(3*P.K));for(auto& a:acc)a=0;
            rows(int(d.gz)*P.rows,[&](int row){
                const int k=row/P.rows+P.frame0,wr=row%P.rows;
                if(k>=P.K)return;
                uint32_t a0=0,a1=0,a2=0;
                for(int x=0;x<P.W;++x){
                    const int o=(k*P.rows+wr)*P.W+x;
                    float w=1.f;
                    if(k>0&&P.robK>0.f)for(int dy=-P.spread;dy<=P.spread;++dy){const int rr=std::clamp(wr+dy,0,P.rows-1);
                        for(int dx=-P.spread;dx<=P.spread;++dx)w=std::min(w,st[(k*P.rows+rr)*P.W+std::clamp(x+dx,0,P.W-1)]);}
                    const float b=sw0[o];sw[o]=b*w;
                    const int y=wr+int(c.fi[k*32+21]);
                    if(P.mode==1&&b>0.f&&y>=P.r0&&float(y)<float(P.r0)+float(P.RH)/float(P.s)){
                        ++a0;if(k>0&&w<0.5f)++a1;
                        const float e=std::min(b*w*sr[o]*sr[o]/P.sig2,4.f);a2+=uint32_t(e*256.f+0.5f);
                    }
                }
                if(a0){acc[size_t(2*k)]+=a0;acc[size_t(2*k+1)]+=a1;acc[size_t(2*P.K+k)]+=a2;}
            });
            if(P.mode==1)for(int i=0;i<3*P.K;++i)cnt[i]+=acc[size_t(i)];
            break;}
        case spGather:{ // kStGather
            const float* z=F(d.bind[2]);float* zo=F(d.bind[3]);const float* xa=F(d.bind[4]);const float* rw=F(d.bind[5]);c.fld=F(d.bind[6]);
            const float wR=P.wbR,wB=P.wbB;
            auto luma=[&](const float* v){return 0.25f*wR*v[0]+0.5f*v[1]+0.25f*wB*v[2];};
            auto hub=[&](float dd){return dd/std::max(1.f,std::abs(dd)/P.delta);};
            rows(P.RH,[&](int py){for(int px=0;px<P.WH;++px){
                const float pcx=float(px)+0.5f,pcy=float(py)+0.5f;
                const float pbx=pcx/float(P.s),pby=pcy/float(P.s)+float(P.r0);
                const float reachLr=(P.s==1?1.f:1.5f)/float(P.s);
                float g[3]={0,0,0},dg[3]={0,0,0};
                for(int k=0;k<P.K;++k){
                    if(c.fi[k*32+22]<0.5f)continue;
                    float fdx,fdy;c.fieldAt(k,pbx,pby,fdx,fdy);
                    float ux,uy;c.hom(k*32,pbx-fdx,pby-fdy,ux,uy);
                    const int cx0=int(std::ceil(ux-0.5f-reachLr)),cy0=int(std::ceil(uy-0.5f-reachLr));
                    const int row0=int(c.fi[k*32+21]);
                    for(int ny=0;ny<2;++ny){
                        const int cy=cy0+ny,wr=cy-row0;
                        if(wr<0||wr>=P.rows||cy<0||cy>=P.H)continue;
                        for(int nx=0;nx<2;++nx){
                            const int cx=cx0+nx;
                            if(cx<0||cx>=P.W)continue;
                            const float* rv=rw+2*size_t((k*P.rows+wr)*P.W+cx);
                            if(rv[1]<=0.f)continue;
                            float q0x,q0y;c.hom(k*32+9,float(cx)+0.5f,float(cy)+0.5f,q0x,q0y);q0x+=fdx;q0y+=fdy;
                            const float qx=float(P.s)*q0x,qy=float(P.s)*q0y-float(P.s*P.r0);
                            const float wgt=c.kern(qx-pcx)*c.kern(qy-pcy)*rv[1];
                            if(wgt<=0.f)continue;
                            const int ch=c.channel(cx,cy);
                            g[ch]+=wgt*rv[0];dg[ch]+=wgt;
                        }
                    }
                }
                const int o=py*P.WH+px;
                const float* zc=z+size_t(o)*4;
                const float* zl=z+size_t(py*P.WH+std::max(px-1,0))*4;const float* zr=z+size_t(py*P.WH+std::min(px+1,P.WH-1))*4;
                const float* zu=z+size_t(std::max(py-1,0)*P.WH+px)*4;const float* zd=z+size_t(std::min(py+1,P.RH-1)*P.WH+px)*4;
                const float* xo=xa+size_t(o)*4;
                float grad[3];for(int i=0;i<3;++i)grad[i]=g[i]+P.mu*(zc[i]-xo[i]);
                const int gt=32*P.K;
                const float gyx=luma(zr)-luma(zl),gyy=luma(zd)-luma(zu);
                const float g2=gyx*gyx+gyy*gyy;
                float flat=c.fi[gt+14]>0.f?1.f/(1.f+g2/(c.fi[gt+14]*P.sig2)):1.f;
                if(c.fi[gt+15]>0.f)flat=std::max(flat,g2/(g2+c.fi[gt+15]*P.sig2)); // P627e
                const float lcP=P.lc+(c.fi[gt+12]-P.lc)*flat,llP=P.ll+(c.fi[gt+13]-P.ll)*flat;
                auto cdx=[&](const float* v){return wR*v[0]-v[1];};auto cdz=[&](const float* v){return wB*v[2]-v[1];};
                const float lapx=cdx(zl)+cdx(zr)+cdx(zu)+cdx(zd)-4.f*cdx(zc),lapz=cdz(zl)+cdz(zr)+cdz(zu)+cdz(zd)-4.f*cdz(zc);
                grad[0]-=lcP*wR*lapx;grad[2]-=lcP*wB*lapz;grad[1]+=lcP*(lapx+lapz);
                const float yc=luma(zc);
                const float div=hub(luma(zr)-yc)-hub(yc-luma(zl))+hub(luma(zd)-yc)-hub(yc-luma(zu));
                const float q[3]={0.25f*wR,0.5f,0.25f*wB};
                for(int i=0;i<3;++i)grad[i]-=llP*q[i]*div;
                const float diag[3]={dg[0]+P.mu+8.f*lcP*(wR*(wR+1.f))+8.f*llP*q[0],dg[1]+P.mu+8.f*lcP*(wR+wB+2.f)+8.f*llP*q[1],
                                     dg[2]+P.mu+8.f*lcP*(wB*(wB+1.f))+8.f*llP*q[2]};
                float* out=zo+size_t(o)*4;
                for(int i=0;i<3;++i)out[i]=zc[i]-P.stepc*grad[i]/diag[i];
                out[3]=0.f;
            }});
            break;}
        case spField: case spFieldSg:{ // kStFieldStep: one "workgroup" per (tile, frame >= 1)
            c.raw=U(d.bind[2]);c.zv=F(d.bind[3]);const float* sw=F(d.bind[4]);c.fld=F(d.bind[6]);float* fldo=F(d.bind[7]);
            const int ntiles=int(d.gx)*int(d.gy)*int(d.gz);
            rows(ntiles,[&](int t){
                const int tix=t%int(d.gx),tiy=(t/int(d.gx))%int(d.gy),k=t/(int(d.gx)*int(d.gy))+1;
                const int ti=(k*P.nty+tiy)*P.ntx+tix;
                const float bx0=float(tix*P.tile),by0=float(tiy*P.tile);
                const float sy0=float(P.r0),sy1=float(P.r0)+float(P.RH)/float(P.s);
                const bool onStrip=c.fi[k*32+22]>0.5f&&by0+float(P.tile)>sy0+2.f&&by0<sy1-2.f;
                double a11=0,a12=0,a22=0,b1=0,b2=0;
                if(onStrip){
                    float c0x,c0y,c1x,c1y,c2x,c2y,c3x,c3y;
                    c.hom(k*32,bx0,std::max(by0,sy0),c0x,c0y);c.hom(k*32,bx0+float(P.tile),std::max(by0,sy0),c1x,c1y);
                    c.hom(k*32,bx0,std::min(by0+float(P.tile),sy1),c2x,c2y);c.hom(k*32,bx0+float(P.tile),std::min(by0+float(P.tile),sy1),c3x,c3y);
                    int fx0=int(std::floor(std::min(std::min(c0x,c1x),std::min(c2x,c3x))))-1,fx1=int(std::ceil(std::max(std::max(c0x,c1x),std::max(c2x,c3x))))+1;
                    int fy0=int(std::floor(std::min(std::min(c0y,c1y),std::min(c2y,c3y))))-1,fy1=int(std::ceil(std::max(std::max(c0y,c1y),std::max(c2y,c3y))))+1;
                    const int row0=int(c.fi[k*32+21]);
                    fy0=std::max(fy0,row0);fy1=std::min(fy1,row0+P.rows);fx0=std::max(fx0,0);fx1=std::min(fx1,P.W);
                    const float sf=float(P.s);
                    for(int y=fy0;y<fy1;++y)for(int x=fx0;x<fx1;++x){
                        const int o=(k*P.rows+(y-row0))*P.W+x;
                        const float wgt=sw[o];
                        if(wgt<=0.f)continue;
                        float pbx,pby;c.hom(k*32+9,float(x)+0.5f,float(y)+0.5f,pbx,pby);
                        if(pbx<bx0||pbx>=bx0+float(P.tile)||pby<by0||pby>=by0+float(P.tile))continue;
                        float fdx,fdy;c.fieldAt(k,pbx,pby,fdx,fdy);pbx+=fdx;pby+=fdy;
                        const float qx=sf*pbx,qy=sf*pby-float(P.s*P.r0);
                        const int ch=c.channel(x,y);
                        float f0,fxp,fxm,fyp,fym;
                        if(!c.forward(qx,qy,ch,f0)||!c.forward(qx+1.f,qy,ch,fxp)||!c.forward(qx-1.f,qy,ch,fxm)
                           ||!c.forward(qx,qy+1.f,ch,fyp)||!c.forward(qx,qy-1.f,ch,fym))continue;
                        const float e=f0-c.rawAt(k,x,y);
                        const float jx=0.5f*sf*(fxp-fxm),jy=0.5f*sf*(fyp-fym);
                        a11+=wgt*jx*jx;a12+=wgt*jx*jy;a22+=wgt*jy*jy;b1+=wgt*jx*e;b2+=wgt*jy*e;
                    }
                }
                const float d0x=c.fld[2*ti],d0y=c.fld[2*ti+1];
                if(!onStrip){fldo[2*ti]=d0x;fldo[2*ti+1]=d0y;return;}
                const float A11=float(a11),A12=float(a12),A22=float(a22),B1=float(b1),B2=float(b2);
                const int bk=k*P.ntx*P.nty;
                float mx=0,my=0,nm=0;
                auto add=[&](int i){mx+=c.fld[2*i];my+=c.fld[2*i+1];nm+=1.f;};
                if(tix>0)add(bk+tiy*P.ntx+tix-1);
                if(tix+1<P.ntx)add(bk+tiy*P.ntx+tix+1);
                if(tiy>0)add(bk+(tiy-1)*P.ntx+tix);
                if(tiy+1<P.nty)add(bk+(tiy+1)*P.ntx+tix);
                if(nm>0.f){mx/=nm;my/=nm;}else{mx=d0x;my=d0y;}
                const float lam=P.lam*0.5f*(A11+A22)+P.lamFloor;
                const float rx=A11*d0x+A12*d0y-B1+lam*mx,ry=A12*d0x+A22*d0y-B2+lam*my;
                const float m11=A11+lam,m22=A22+lam,det=m11*m22-A12*A12;
                float dx=d0x,dy=d0y;
                if(std::abs(det)>1e-20f){dx=(m22*rx-A12*ry)/det;dy=(m11*ry-A12*rx)/det;}
                const float len=std::sqrt(dx*dx+dy*dy);
                if(len>P.maxShift){dx*=P.maxShift/len;dy*=P.maxShift/len;}
                fldo[2*ti]=dx;fldo[2*ti+1]=dy;
            });
            break;}
        case spOutput:{ // kStOutput
            const float* z=F(d.bind[2]);float* ob=F(d.bind[3]);
            const int ow=P.mode!=2?P.WH/P.s:P.WH;
            auto lz=[](float d){return std::abs(d)<1e-4f?1.f:2.f*std::sin(3.14159265f*d)*std::sin(1.57079633f*d)/(9.8696044f*d*d);};
            rows(P.outRows,[&](int y){for(int x=0;x<ow;++x){
                float v[3]={0,0,0};
                if(P.mode==3){ // P627f: the Lanczos-2 1x photo
                    const float fs=float(P.s),cx=(float(x)+0.5f)*fs,cy=float(P.rows)+(float(y)+0.5f)*fs;
                    const int ix=int(std::floor(cx-2.f*fs)),iy=int(std::floor(cy-2.f*fs));
                    float ws=0;
                    for(int j=0;j<=4*P.s;++j){
                        const float dy=(float(iy+j)+0.5f-cy)/fs;
                        if(std::abs(dy)>=2.f)continue;
                        const float wy=lz(dy);const int ry=std::clamp(iy+j,0,P.RH-1);
                        for(int i=0;i<=4*P.s;++i){
                            const float dx=(float(ix+i)+0.5f-cx)/fs;
                            if(std::abs(dx)>=2.f)continue;
                            const float w=wy*lz(dx);const float* p=z+size_t(ry*P.WH+std::clamp(ix+i,0,P.WH-1))*4;
                            v[0]+=w*p[0];v[1]+=w*p[1];v[2]+=w*p[2];ws+=w;
                        }
                    }
                    for(float& t:v)t/=ws;
                } else if(P.mode==1){
                    const int f=P.s,hy=P.rows+y*f,hx=x*f;
                    for(int j=0;j<f;++j)for(int i=0;i<f;++i){const float* p=z+size_t((hy+j)*P.WH+hx+i)*4;v[0]+=p[0];v[1]+=p[1];v[2]+=p[2];}
                    for(float& t:v)t/=float(f*f);
                } else {const float* p=z+size_t((P.rows+y)*P.WH+x)*4;v[0]=p[0];v[1]=p[1];v[2]=p[2];}
                float* o=ob+size_t(y*ow+x)*3;o[0]=v[0];o[1]=v[1];o[2]=v[2];
            }});
            break;}
        default:throw std::runtime_error("static CPU: unknown program");
        }
        scam_progress::tick(); // P627c: the app's watchdog sees each program end (a CPU strip takes minutes at 12 MP)
    }
};

} // namespace staticmerge
} // namespace scam
