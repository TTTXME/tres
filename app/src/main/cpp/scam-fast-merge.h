#pragma once
// The fast merge: the reference Sabre per-frame burst merge (research spec, see NOTES of the port) as a merge route of the SCAM Hybrid worker,
// tuning line «fastMerge 1» (owner, 2026-11-03: the app's «Быстрый» processing works exactly by the reference scheme, the SCAM merge
// stays as «Качество»). Spec: the research pipeline spec (§n below); the GLSL is the reference's own, byte for byte
// (scam-fast-shaders.h, generated). Stage 1: plain Bayer (cfa_sensor_type 0), NORMAL frames only (ultrashort / bracketed frames
// stay out of this route: no Bento, no long-frame pedestal / motion scope), sabre_scale 1.0 (OUT = RAW grid).
// P501 (owner, 2026-11-04: «В Арк камере есть Sabre 1.5х и 2х, возьми из неё»): sabre_scale 1.5 / 2 as the reference's super-res -
// OUT = RAW x scale (the app's size, hybridFastOut), the merge drawn at OUT (its tex_coord samples the HALF textures in uv, so the
// frames' passes stay at HALF), kernel_blurring_params.z = 1 (the reference's flag for sabre_scale > 1.001: MERGE_A's Demosaic1x
// fill of the base where the donors were rejected), the outputs of the worker contract at OUT.
//
// Per burst (§3, §4): every RAW is uploaded as its byte stream (R8UI, RAW16 little endian, §3.5) and unpacked on the GPU into
// RGBA16F quads of raw DN at HALF = W/2 x H/2 (sh108 = unpack + hot pixels). The base frame gets the guide (sh059: sqrt-domain YCoCg
// colour + std, the anisotropic kernel covariance in RGB10_A2, the gray pyramid L0), its B-spline reference colour (sh001), the
// unblocker (sh026), the gray pyramid L1..L4 (sh102, sh072 x3), the gradient products of every level (sh091) and the homography
// corners on the 8-bit guide of L1 (sh003, sh020). Per donor (§5): the same unpack / guide / pyramid, the global homography (KLT
// sh081, RANSAC sh032 on the GPU, no CPU readback), the tile Lucas-Kanade per level from the coarsest (seed: sh094 from the
// homography, then sh040 upsampling; LK sh112 x2-3; median sh000), the flow texture (sh064), the rejection chain (sh118 -> sh089 ->
// sh009 -> sh006 -> sh090), accumulate_weight (sh074, additive) and the additive merge into the two RGBA16F accumulators at OUT
// (sh002). After the loop (§6): the base self-merge (MERGE_A, with the donors' accumulated weight: hole fill where they were
// rejected) and the normalisation rgb = accum / weights (sh076's division). the reference's resolve / defringe / LSC / CCM do not run:
// the result is the merged linear camera RGB at the sensor grid in the worker's units ((DN - black) / (white - black) of the
// base frame, no white balance), as hybridReconstruct hands it back; the app's post pipeline (with the reference resolve denoise) follows.
// Any GL failure throws. P627 (owner, 2026-11-11: every mode stays the mode the user chose): the caller (hybridFastRoute) runs the
// fast route again on a new context, a frame past the GPU's size limits in column bands of the same route; never the SCAM merge.
//
// Decisions where the spec is [NOT RECOVERED] / [UNCERTAIN] (each repeated at its use; NOTES.md lists them):
//  - noise LUT per bin (§4.2): var = S I + O at the bin's linear level I = (bin / 63)^2 for both rows (the base model, the per-shot
//    model of a normal frame), stored (var, var, var, sqrt(var)); the RAW noise measurement (§3.4) is skipped: the HAL model as is
//  - hot pixels (§4.1): strength 1 below SNR 15, else 0 (the "alternative value" not recovered); threshold offset 1 % of the range
//  - white balance: the merge's gains carry it as the reference's (wb x ev / (white - black)); taken from wbR / wbB when the app sends
//    them, else the base frame's grey world (hybridBlendWb); undone in the normalisation (the output stays camera RGB)
//  - green_clipping_point sqrt(0.95 wbG); u_source_clipping_point black + 0.95 (white - black)
//  - unblocker input: the base guide (0x174); its noise from LUT row 0
//  - LK per level (§5.4, [UNCERTAIN] iteration rule): seed, LK, LK, (LK unless the finest level), median at every level; the
//    upsampling's grid minimum 1 (the products' / LK's tile origin is (tile + 1) stride, sh091 / sh112 "sic")
//  - coarsest seed: the homography always (sh094 fills zero flow when RANSAC found none); phase correlation / alignL1 not used
//  - flow_scale_offset (§11.8): the flow texel of tile i sits at HALF pixel (i + 1.5) stride: scale HALF / (stride grid), offset -1 / grid
//  - kernel_blurring_params.z (§11.7) = 0 (no Demosaic1x area upscale); rejection_filter u_clip_rejection 0, u_clipped_threshold 0
//  - constant rejection of the base self-merge (§11.9): (1, 0); FrameSelector (§1.3) not run: frames[0] is the base, every normal
//    frame a donor in the order sent
//  - outputs the worker contract needs: effective frames = 1 + the donors' accumulated weight (at HALF, nearest to the grid,
//    coded relative to its median as hybridReconstruct's map); clip flags bit 0/1/2 = merged R/G/B >= 0.94, bit 3 = 2 .. 98 % of
//    the kernel weight from clipped samples (clipW / totW of the merge); no P19 CA resample, no plain chroma median (not the reference's)
#include "scam-fast-gpu.h"
#include <numeric>

namespace scam {

// P480 (owner, 2026-11-03: the dezipper «полностью идентично» the reference's): the reference's highlight_chroma_dezipper (spec §6
// item 5) runs after its resolve, only with the highlight path on (its flag at 0x17b5b4), on the BASE highlight mask (0x530: sh060 ->
// sh087 -> sh016, at HALF; 0x17b630 binds 0x530 whenever it exists, the final mask 0x534 only without it - the validity and the
// "all short frames rejected" clear act on 0x534, never on 0x530). Our resolve runs in the app, so the mask goes there: set (the
// worker, on the job marker hl-mask) to a vector that a finished fast merge with the highlight path on fills with the trailer: the
// 16-byte header (hybridHighlightMaskHeader) then the HALF mask's halves row by row; empty otherwise.
inline std::vector<uint16_t>*& hybridHighlightMaskOut(){static std::vector<uint16_t>* out=nullptr;return out;}
// The trailer's 16-byte header: "SCHLMSK1", HALF width, HALF height (int32 little endian; aarch64 is)
inline void hybridHighlightMaskHeader(std::vector<uint16_t>& v,int w2,int h2){
    v.assign(8,0);
    const char tag[8]={'S','C','H','L','M','S','K','1'};const int32_t dims[2]={w2,h2};
    std::memcpy(v.data(),tag,8);std::memcpy(v.data()+4,dims,8);
}

// P501: the route's output grid OUT: the app's size (header words 15 / 16: Sabre 1.5x), else the requested grid (1 or 2) x RAW
inline std::array<int,2> hybridFastOut(const HybridInput& in,const HybridTuning& tune){
    if(in.outW>0&&in.outH>0)return {in.outW,in.outH};
    const int g=hybridRequestedGrid(in,tune);
    return {in.w*g,in.h*g};
}
// The route takes the burst: plain Bayer, the sensor grid, up to kHybridClassicPixels, a width of 4 px multiples
// Above 16 MP (owner, 2026-11-03: «Серии больше 16 Мп ... должны работать через "быструю" обработку») the fast merge runs in row
// strips: the GPU memory it holds then, estimated per RAW pixel from its textures (scam-fast-merge.h, the strip branch) and the CPU
// side of the worker's contract (the float result, the trailers). Upper bounds: every frame counted, the highlight path and every
// short frame assumed on, the strips at their default height (kFastStripBytes of accumulators).
constexpr uint64_t kFastStripBytes=uint64_t(256)<<20;
inline uint64_t hybridFastStripBytes(const HybridInput& in){
    const double px=double(in.w)*double(in.h);
    int frames=0,shorts=0;
    for(const auto& f:in.frames){frames+=f.role!=kRoleUltrashort;shorts+=f.role==kRoleUltrashort;}
    // per RAW pixel: base (Bayer 2, guide 2, covariance 1, reference colour 2 + luma 0.5), the current frame (Bayer 2, guide 2,
    // covariance 1), gray pyramids 1.33, the rejection chain and the frame weights 1.5, every frame's own rejection map 0.5, the
    // upload ring 6, the highlight path 6 and 5 per kept short frame, the effective frames 1 (none above 100 MP: flag 16); P601: every
    // frame's flow on 4 x 4 HALF blocks 0.25 (fastFlowPick)
    double gpu=7.5+5+1.33+1.5+0.75*frames+6+(shorts?6.0+5.0*shorts:0.0)+(px>double(kHybridHugePixels)?0.0:1.0);
    const double stripRows=std::max(64.0,std::min(double(in.h),double(kFastStripBytes)/(23.0*in.w)));
    gpu+=23.0*stripRows/double(in.h); // the accumulators (16 B) and the readback band (7 B) of one strip
    const double cpu=12.0+2.0; // the float result, the clip flags and the effective map
    return uint64_t((gpu+cpu)*px);
}
inline bool hybridFastEligible(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report,std::string& why){
    if(input.frames.empty()||!input.frames[0].raw){why="no base frame";return false;}
    if(input.subFrames>1){why="colour-block sub-frames";return false;}
    // colour-block streams are merged natively: 2x2 blocks (Quad: cfa_sensor_type 1 of the reference), 4x4 blocks (Tetra / Quad-Quad:
    // cfa_sensor_type 2); any other block stays on the SCAM merge
    if(input.mosaic!=0&&input.mosaic!=1&&input.mosaic!=2&&input.mosaic!=4){why="colour-block mosaic "+std::to_string(input.mosaic);return false;}
    if(input.mosaic==0){const int block=hybridMosaicBlock(input,tune,report);if(block!=1&&block!=2&&block!=4){why="measured colour block "+std::to_string(block);return false;}
        if(block==4&&tune.fastMergeTetra==0)report("HYBRID FAST MERGE: fastMergeTetra 0 ignored (P627: «Быстрый» stays «Быстрый»: Tetra on the fast route)");}
    if(input.mosaic==4&&tune.fastMergeTetra==0)report("HYBRID FAST MERGE: fastMergeTetra 0 ignored (P627: «Быстрый» stays «Быстрый»: Tetra on the fast route)");
    { // P501: sabre_scale 1, 1.5 (the app's OUT) or 2
        const int rg=hybridRequestedGrid(input,tune);
        if(rg!=1&&rg!=2){why="output grid "+std::to_string(rg);return false;}
        const auto o=hybridFastOut(input,tune);
        if(o[0]%4||o[1]%2||o[0]<input.w||o[1]<input.h||o[0]>2*input.w||o[1]>2*input.h){why="output "+std::to_string(o[0])+"x"+std::to_string(o[1]);return false;}
    }
    if(input.w%4||input.h%2||input.w<64||input.h<64){why="frame "+std::to_string(input.w)+"x"+std::to_string(input.h);return false;}
    if(int64_t(input.w)*input.h>kHybridClassicPixels){ // row strips (hybridFastMerge): the memory they need against MemAvailable
        const uint64_t need=hybridFastStripBytes(input);
        uint64_t avail=hybridMemAvailable();
        if(const char* fake=std::getenv("SCAM_FM_MEMAVAIL_MB"))avail=uint64_t(std::max(0L,std::atol(fake)))<<20;
        // P495 (owner, 2026-11-03: «Убери лимит»): only reported. The bound counts every short frame and the highlight path on,
        // and the SCAM merge it sent the Nubia Z17 (22 MP, MemAvailable 1441 MB) to took 52 s at a 1513 MB peak itself
        if(avail>0&&need>avail/10*6)
            report("HYBRID FAST MERGE: row strips may need ~"+hybridMB(need)+" MB, above 60 % of MemAvailable "+hybridMB(avail)+" MB; taken anyway");
    }
    if(input.cfa<0||input.cfa>3){why="CFA "+std::to_string(input.cfa);return false;}
    return true;
}

// The frame selector's signals (§1.3; the reference measures them on its ring's thumbnails, which this worker does not get): a
// thumbnail of every frame at 1/8 of the RAW (the mean of the green sites of one 2-row pair per 8 x 8 block, in base units), its
// sharpness (mean |gradient| / mean level: exposure-free) and its translation against the base thumbnail (SAD, coarse 1/4 then
// fine, in thumbnail pixels). [NOT RECOVERED] the reference's own sharpness metric and thumbnail scale: these are the closest.
struct FastThumb { std::vector<float> v;int w=0,h=0;float sharp=0,mean=0; };
inline FastThumb fastThumb(const HybridInput& in,const uint16_t* raw,float gain){
    FastThumb t;t.w=in.w/8;t.h=in.h/8;t.v.assign(size_t(t.w)*t.h,0.f);
    const int dx=in.cfa&1,dy=in.cfa>>1; // the R site of the 2x2 cell: greens at (1 - dx, dy) and (dx, 1 - dy)
    const float bl=0.5f*(in.black[size_t((dy<<1)|(1-dx))]+in.black[size_t(((1-dy)<<1)|dx)]),k=gain/std::max(in.white-bl,1.f);
    double sum=0;
    for(int by=0;by<t.h;++by){
        const uint16_t* r0=raw+size_t(by*8)*in.w;const uint16_t* r1=r0+in.w;
        for(int bx=0;bx<t.w;++bx){
            int a=0;const int x0=bx*8;
            for(int c=0;c<4;++c){a+=r0[x0+2*c+(1-dx)];a+=r1[x0+2*c+dx];}
            const float v=(float(a)*0.125f-bl)*k;t.v[size_t(by)*t.w+bx]=v;sum+=v;
        }
    }
    t.mean=float(sum/double(std::max<size_t>(1,t.v.size())));
    double gsum=0;
    for(int y=0;y+1<t.h;++y)for(int x=0;x+1<t.w;++x){const float c=t.v[size_t(y)*t.w+x];
        gsum+=std::abs(t.v[size_t(y)*t.w+x+1]-c)+std::abs(t.v[size_t(y+1)*t.w+x]-c);}
    t.sharp=float(gsum/double(std::max<size_t>(1,size_t(t.w-1)*size_t(t.h-1))))/std::max(t.mean,1e-6f);
    return t;
}
// translation of `cur` against `ref` (thumbnail pixels): exposure-normalised SAD over the inner area, a 4-px search of +-48 then +-3.
// The coarse samples sit on multiples of 4 (the margin and the shift are), so it reads 1/4 lattices of the normalised copies
// contiguously (vectorised; the sums' order changes, the sampled pixels do not)
// P662: the normalised copies of one thumbnail (its 1/4 lattice and the 4 phases of its 1/2 lattice), built once per frame: the base's
// for every donor (they were built again in each of ~20 calls), the same values (v x 1 / max(mean, 1e-6)) in the same places
struct FastThumbNorm {
    int w=0,h=0,w4=0,h4=0,w2=0,h2=0;
    std::vector<float> r4,r2[4];
    explicit FastThumbNorm(const FastThumb& t):w(t.w),h(t.h),w4((t.w+3)/4),h4((t.h+3)/4),w2((t.w+1)/2),h2((t.h+1)/2){
        const float k=1.f/std::max(t.mean,1e-6f);
        std::vector<float> r(t.v.size());
        for(size_t i=0;i<r.size();++i)r[i]=t.v[i]*k;
        r4.resize(size_t(w4)*h4);
        for(int y=0;y<h4;++y)for(int x=0;x<w4;++x)r4[size_t(y)*w4+x]=r[size_t(4*y)*w+4*x];
        for(int ph=0;ph<4;++ph){
            r2[ph].assign(size_t(w2)*h2,0.f);
            for(int y=ph>>1,j=0;y<h;y+=2,++j)for(int x=ph&1,i=0;x<w;x+=2,++i)r2[ph][size_t(j)*w2+i]=r[size_t(y)*w+x];
        }
    }
};
// P663: the variable-window search below (kept as the wide search of small frames): its window shrank on every side with
// max(|sx|, |sy|), so its area-normalised SAD compared a different set of base pixels at every shift - on a texture-poor, noise-led
// thumbnail (owner's log 2026-11-11 22:54, Xiaomi 17 Ultra 8192x6144 ISO 3188, sharpness 0.0515) the window's own noise level decided,
// and every donor landed on the search's edge ("dropped 1 (shift 50.0 ...)" x 18: the photo of one frame)
inline float fastThumbShiftWide(const FastThumbNorm& rn,const FastThumbNorm& cn){
    const int w=rn.w,h=rn.h,w4=rn.w4;
    const std::vector<float>& r4=rn.r4;const std::vector<float>& c4=cn.r4;
    auto sad4=[&](int sx,int sy){
        const int m=8+std::max(std::abs(sx),std::abs(sy)),i0=m/4,i1=(w-m+3)/4,j1=(h-m+3)/4;
        if(i1<=i0||j1<=i0)return 1e30;
        double s=0;
        for(int j=i0;j<j1;++j){
            const float* a=r4.data()+size_t(j)*w4;const float* b=c4.data()+size_t(j+sy/4)*w4+sx/4;float rs=0.f;
            {
#pragma clang fp reassociate(on)
                for(int i=i0;i<i1;++i)rs+=std::abs(a[i]-b[i]);
            }
            s+=rs;
        }
        return s/double(long(j1-i0)*(i1-i0));
    };
    // the fine search samples every 2nd pixel: the 4 phases of a 1/2 lattice, contiguous the same way
    const int w2=rn.w2;
    const std::vector<float>* r2=rn.r2;const std::vector<float>* c2=cn.r2;
    auto sad=[&](int sx,int sy){
        const int m=8+std::max(std::abs(sx),std::abs(sy)),n2=(w-m-m+1)/2,j2=(h-m-m+1)/2;
        if(n2<=0||j2<=0)return 1e30;
        const float* a0=r2[((m&1)<<1)|(m&1)].data()+size_t(m>>1)*w2+(m>>1);
        const float* b0=c2[(((m+sy)&1)<<1)|((m+sx)&1)].data()+size_t((m+sy)>>1)*w2+((m+sx)>>1);
        double s=0;
        for(int j=0;j<j2;++j){
            const float* a=a0+size_t(j)*w2;const float* b=b0+size_t(j)*w2;float rs=0.f;
            {
#pragma clang fp reassociate(on)
                for(int i=0;i<n2;++i)rs+=std::abs(a[i]-b[i]);
            }
            s+=rs;
        }
        return s/double(long(j2)*n2);
    };
    int bx=0,by=0;double best=1e30;
    for(int sy=-48;sy<=48;sy+=4)for(int sx=-48;sx<=48;sx+=4){const double v=sad4(sx,sy);if(v<best){best=v;bx=sx;by=sy;}}
    int fx=bx,fy=by;best=1e30;
    for(int sy=by-3;sy<=by+3;++sy)for(int sx=bx-3;sx<=bx+3;++sx){const double v=sad(sx,sy);if(v<best){best=v;fx=sx;fy=sy;}}
    return std::sqrt(float(fx*fx+fy*fy));
}
// P663: the search radius (thumbnail px, a multiple of 4) whose fixed window keeps at least half of each side: 48 from 236 thumbnail
// px up (a 1888 px short side: every phone stream), less on the small replay crops (12 on a 1024x768 RAW's 128x96); 0 below 60 px
inline int fastThumbRadius(int w,int h){return std::max(0,std::min(48,(std::min(w,h)/4-11)/4*4));}
// P663: the translation of `cur` against `ref` (thumbnail px) over ONE window of the base for every shift (margin 8 + the reach): a
// 4-px search of +-R then +-3 (R = fastThumbRadius), the SAD of each shift over the same base pixels. Each component of the best
// shift is taken only when it beats the shift without it beyond the noise: the paired |difference| sums of the two over the fine
// window, z = mean / (sd / sqrt n) >= 5 (pure-noise thumbnails reach ~3.3 at their best shift); else that component is not
// measurable (flat: 0; a donor of a flat thumbnail stays). A small frame (R < 48) whose fine minimum sits on the reach's
// border or holds a component not measurable is measured by the wide search instead (its translation may lie past the reach).
struct FastShift { float shift=0.f; bool flat=false,wide=false; };
inline FastShift fastThumbShiftFixed(const FastThumbNorm& rn,const FastThumbNorm& cn){
    FastShift out;
    const int w=rn.w,h=rn.h,w4=rn.w4,R=fastThumbRadius(w,h);
    if(R<4){out.shift=fastThumbShiftWide(rn,cn);out.wide=true;return out;}
    const int M=8+R+3,M4=(M+3)/4*4;
    const std::vector<float>& r4=rn.r4;const std::vector<float>& c4=cn.r4;
    const int i0=M4/4,i1=(w-M4)/4,j0=M4/4,j1=(h-M4)/4;
    auto sad4=[&](int sx,int sy){
        double s=0;
        for(int j=j0;j<j1;++j){
            const float* a=r4.data()+size_t(j)*w4;const float* b=c4.data()+size_t(j+sy/4)*w4+sx/4;float rs=0.f;
            {
#pragma clang fp reassociate(on)
                for(int i=i0;i<i1;++i)rs+=std::abs(a[i]-b[i]);
            }
            s+=rs;
        }
        return s;
    };
    const int w2=rn.w2,n2=(w-2*M+1)/2,m2=(h-2*M+1)/2;
    const std::vector<float>* r2=rn.r2;const std::vector<float>* c2=cn.r2;
    const float* a0=r2[((M&1)<<1)|(M&1)].data()+size_t(M>>1)*w2+(M>>1);
    auto donor=[&](int sx,int sy){return c2[(((M+sy)&1)<<1)|((M+sx)&1)].data()+size_t((M+sy)>>1)*w2+((M+sx)>>1);};
    auto sad=[&](int sx,int sy){
        const float* b0=donor(sx,sy);
        double s=0;
        for(int j=0;j<m2;++j){
            const float* a=a0+size_t(j)*w2;const float* b=b0+size_t(j)*w2;float rs=0.f;
            {
#pragma clang fp reassociate(on)
                for(int i=0;i<n2;++i)rs+=std::abs(a[i]-b[i]);
            }
            s+=rs;
        }
        return s;
    };
    int bx=0,by=0;double best=1e300;
    for(int sy=-R;sy<=R;sy+=4)for(int sx=-R;sx<=R;sx+=4){const double v=sad4(sx,sy);if(v<best){best=v;bx=sx;by=sy;}}
    int fx=bx,fy=by;best=1e300;
    for(int sy=by-3;sy<=by+3;++sy)for(int sx=bx-3;sx<=bx+3;++sx){const double v=sad(sx,sy);if(v<best){best=v;fx=sx;fy=sy;}}
    // each component against the same shift without it, sample by sample over the same window: kept only when it beats that
    // beyond the noise (a scene with structure along one axis alone leaves the other free: its value would be the noise's)
    auto beats=[&](int ax,int ay,int bx2,int by2){
        const float* pa=donor(ax,ay);const float* pb=donor(bx2,by2);
        double sum=0,sq=0;
        for(int j=0;j<m2;++j){
            const float* a=a0+size_t(j)*w2;const float* b=pa+size_t(j)*w2;const float* c=pb+size_t(j)*w2;
            for(int i=0;i<n2;++i){const double d=double(std::abs(a[i]-b[i]))-double(std::abs(a[i]-c[i]));sum+=d;sq+=d*d;}
        }
        const double n=double(m2)*n2,mean=sum/n,var=std::max(0.0,sq/n-mean*mean);
        return mean>0.0&&mean*std::sqrt(n)>=5.0*std::sqrt(var);
    };
    if(fx!=0&&!beats(0,fy,fx,fy)){fx=0;out.flat=true;}
    if(fy!=0&&!beats(fx,0,fx,fy)){fy=0;out.flat=true;}
    // a small frame (R < 48): a minimum on the reach's border or a component not measurable may be a move past the reach - the
    // wide search (the selector before P663) measures it, as it did
    if(R<48&&(out.flat||std::max(std::abs(fx),std::abs(fy))>=R+3)){out.shift=fastThumbShiftWide(rn,cn);out.flat=false;out.wide=true;return out;}
    out.shift=std::sqrt(float(fx*fx+fy*fy));
    return out;
}
inline FastShift fastThumbShift(const FastThumbNorm& rn,const FastThumbNorm& cn){return fastThumbShiftFixed(rn,cn);}
inline FastShift fastThumbShift(const FastThumb& ref,const FastThumb& cur){return fastThumbShift(FastThumbNorm(ref),FastThumbNorm(cur));}
// P662: body(i) for i in [0, n) on min(n, cores, 8) threads (this one among them), one index at a time: the selector's thumbnails
// and shifts (each index computes alone, so any thread order gives the same values); was a thread per frame (21 on 8 cores)
inline void fastParallelFor(int n,const std::function<void(int)>& body){
    if(n<=0)return;
    const int threads=std::max(1,std::min({n,int(std::thread::hardware_concurrency()),8}));
    std::atomic<int> next{0};
    std::exception_ptr error;std::mutex errorLock;
    auto run=[&]{
        for(int i=next.fetch_add(1);i<n;i=next.fetch_add(1)){
            try{body(i);}catch(...){std::lock_guard<std::mutex> l(errorLock);if(!error)error=std::current_exception();}
        }
    };
    std::vector<std::thread> team;
    for(int t=1;t<threads;++t){try{team.emplace_back(run);}catch(const std::exception&){break;}} // fewer threads: shared indices
    run();
    for(auto& t:team)t.join();
    if(error)std::rethrow_exception(error);
}

// P627: a frame past one of the GPU's limits for the fast route (texture / viewport size, the storage block of a strip's ring):
// maxW - the widest frame (RAW px) the limit takes at this height (0: the height itself is past it)
struct FastMergeLimit : std::runtime_error { int maxW=0; FastMergeLimit(const std::string& m,int w):std::runtime_error(m),maxW(w){} };

// The frame selector (§1.3, photo) of hybridFastMerge: base = the first normal frame in buffer order with sharpness >= 0.7 x the
// sharpest; donors kept with translation <= 35 thumbnail px and sharpness >= 0.6 x the base's; thumbs of the frames it read (and of
// the long frames). P627: also the column bands' one pick for the whole frame (tune.fastPick).
inline void fastSelectFrames(const HybridInput& input,const HybridTuning& tune,const std::vector<int>& normals,const std::vector<int>& longs,
                             std::vector<FastThumb>& thumbs,int& baseIdx,std::vector<int>& donors,int& dropped,std::string& selectLine){
    if(!tune.fastPick.empty()){ // P627: the column bands' pick of the whole frame (base first, then the donors it kept)
        baseIdx=tune.fastPick[0];donors.assign(tune.fastPick.begin()+1,tune.fastPick.end());
        for(int f:longs)thumbs[size_t(f)]=fastThumb(input,input.frames[size_t(f)].raw,1.f/input.frames[size_t(f)].exposure);
        thumbs[size_t(baseIdx)]=fastThumb(input,input.frames[size_t(baseIdx)].raw,1.f/input.frames[size_t(baseIdx)].exposure);
        selectLine="FrameSelector: base idx="+std::to_string(baseIdx)+", "+std::to_string(donors.size())+" donors (the whole frame's pick: column bands)";
        return;
    }
    {
        const bool select=tune.fastMergeSelect!=0&&normals.size()>1;
        std::vector<int> want;
        if(select)want=normals; else if(!longs.empty())want.push_back(0);
        for(int f:longs)want.push_back(f);
        // P662: on a fixed team of threads (fastParallelFor), one frame at a time
        fastParallelFor(int(want.size()),[&](int i){const int f=want[size_t(i)];
            const HybridFrame& fr=input.frames[size_t(f)];thumbs[size_t(f)]=fastThumb(input,fr.raw,1.f/fr.exposure);});
        // "buffer order" [UNCERTAIN]: the order the app sends (frames[0] = its base, next to the shutter), as the reference's log
        // shows its base at idx 0 on both test phones
        const std::vector<int>& order=normals;
        // P488 fastMergeSport bit 2: the reference Sport's donor gate (15 px, 0.75 x the base); its base (the sharpest frame) is not
        // taken: frames[0] is the app's press moment (its own +-1 frame sharpness check ran there)
        const bool sportSel=(tune.fastMergeSport&2)!=0;
        if(select&&!sportSel){
            float best=0.f;for(int f:normals)best=std::max(best,thumbs[size_t(f)].sharp);
            for(int f:order)if(thumbs[size_t(f)].sharp>=0.7f*best){baseIdx=f;break;}
        }
        const float shiftMax=sportSel?15.f:35.f,sharpMin=sportSel?0.75f:0.6f;
        char line[280];
        std::snprintf(line,sizeof(line),"FrameSelector: base idx=%d (sharpness %.4f%s)",baseIdx,select?thumbs[size_t(baseIdx)].sharp:0.f,select?(sportSel?", sport: moment base, gate 15 px / 0.75":""):", selector off");
        selectLine=line;
        std::vector<std::pair<int,float>> shifts;int flats=0,wides=0;float keptMax=0.f;
        if(select){ // P662: the base's normalised copies once, the donors on the fixed team (the same shift per frame)
            std::vector<int> sf;for(int f:order)if(f!=baseIdx)sf.push_back(f);
            const FastThumbNorm baseNorm(thumbs[size_t(baseIdx)]);
            std::vector<FastShift> tr(sf.size());
            fastParallelFor(int(sf.size()),[&](int i){tr[size_t(i)]=fastThumbShift(baseNorm,FastThumbNorm(thumbs[size_t(sf[size_t(i)])]));});
            for(size_t i=0;i<sf.size();++i){shifts.emplace_back(sf[i],tr[i].shift);flats+=tr[i].flat;wides+=tr[i].wide;}
        }
        size_t si=0;
        for(int f:order){
            if(f==baseIdx)continue;
            if(select){
                const float sh=thumbs[size_t(f)].sharp,tr=shifts[si++].second;
                if(tr>shiftMax||sh<sharpMin*thumbs[size_t(baseIdx)].sharp){
                    ++dropped;std::snprintf(line,sizeof(line),"; dropped %d (shift %.1f, sharpness %.4f)",f,tr,sh);selectLine+=line;continue;
                }
                keptMax=std::max(keptMax,tr);
            }
            donors.push_back(f);
        }
        // P663: what the shifts were (one window for every shift; the phone log shows the kept donors' largest one)
        if(select&&!shifts.empty()){
            std::snprintf(line,sizeof(line),"; shift (P663: one window, search +-%d): kept donors' largest %.1f thumbnail px, %d not measurable (flat: 0)%s",
                fastThumbRadius(thumbs[size_t(baseIdx)].w,thumbs[size_t(baseIdx)].h),keptMax,flats,wides?(", "+std::to_string(wides)+" by the wide search (small frame)").c_str():"");
            selectLine+=line;
        }
    }
}

inline std::vector<float> hybridFastMerge(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report,
                                         std::vector<uint16_t>* mergedDng,std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    // P509b (Pixel 10 Pro XL, PowerVR, 31226: the merge hung 40 s+ behind «pass=prepare»): its own pass, a tick at every stage
    // boundary and the GPU waits as fences, so the app's watchdog stops a hung fast merge early and retries without it
    scam_progress::pass("fast");
    auto ms=[](Clock::time_point a,Clock::time_point b){return std::chrono::duration<double,std::milli>(b-a).count();};
    // SCAM_FM_DCG_TEST "knee,factor" (replays only: the two-zone path on a burst without measured zones): every frame gets the knee
    // and an LCG offset of factor x its offset
    if(const char* t=std::getenv("SCAM_FM_DCG_TEST"))if(!hybridDcgZones(input)){
        float knee=0.f,factor=0.f;
        if(std::sscanf(t,"%f,%f",&knee,&factor)==2&&knee>0.f&&factor>0.f){
            HybridInput z=input;z.dcg=true;
            for(auto& fr:z.frames){fr.dcgKnee=knee;fr.dcgOffsetLcg=std::max(fr.offset,1e-9f)*factor;}
            report("HYBRID FAST MERGE DCG TEST: knee "+std::to_string(knee)+", LCG offset x"+std::to_string(factor));
            return hybridFastMerge(z,tune,report,mergedDng,effMap,statsOut,clipFlags);
        }
    }
    const int W=input.w,H=input.h,w2=W/2,h2=H/2,cfa=input.cfa;
    // P501: OUT (the accumulators, the output pass and every output of the contract); sabre: OUT != RAW
    const std::array<int,2> outWH=hybridFastOut(input,tune);
    const int OW=outWH[0],OH=outWH[1];
    const bool sabre=OW!=W||OH!=H;
    if(sabre){
        char line[200];std::snprintf(line,sizeof(line),"HYBRID FAST MERGE SABRE: OUT %dx%d (sabre_scale %.3f x %.3f), Demosaic1x hole fill on",OW,OH,double(OW)/W,double(OH)/H);
        report(line);
    }
    // P480: the base highlight mask for the app's dezipper (hybridHighlightMaskOut), filled only by a finished merge
    std::vector<uint16_t>* const hlMaskOut=hybridHighlightMaskOut();
    if(hlMaskOut)hlMaskOut->clear();
    // ---- frame classes (§5.0): normal frames (the base exposure), short frames (ultrashort, exposure factor >= 2: the highlight
    // path), long frames (bracketed, ratio >= 1.2; below it a normal donor). ev = the gain that brings a frame to the base's units
    // (1 / its exposure ratio). The ratio is the capture metadata (the reference's RAW ratio measurement, which overrides the
    // metadata only when they disagree, is not run).
    const auto selectStarted=Clock::now();
    std::vector<int> normals,shorts,longs;int other=0,shortsWeak=0,longsAsNormal=0;
    for(int f=0;f<int(input.frames.size());++f){
        const HybridFrame& fr=input.frames[size_t(f)];
        if(!fr.raw||!(fr.exposure>0.f)){if(f)++other;continue;}
        if(f==0||(fr.role==kRoleNormal&&std::abs(fr.exposure-1.f)<0.02f))normals.push_back(f);
        else if(fr.role==kRoleUltrashort){if(1.f/fr.exposure>=2.f)shorts.push_back(f); else ++shortsWeak;}
        else if(fr.role==kRoleBracketed){if(fr.exposure>=1.2f)longs.push_back(f); else {normals.push_back(f);++longsAsNormal;}}
        else ++other;
    }
    // ---- frame selector (§1.3, photo): base = the first normal frame in buffer order with sharpness >= 0.7 x the sharpest;
    // donors kept with translation <= 35 thumbnail px and sharpness >= 0.6 x the base's. tune fastMergeSelect 0: frames[0] the
    // base, every normal frame a donor.
    int baseIdx=0;std::vector<int> donors;int dropped=0;std::string selectLine;
    std::vector<FastThumb> thumbs(input.frames.size());
    double thumbsMs=0;
    fastSelectFrames(input,tune,normals,longs,thumbs,baseIdx,donors,dropped,selectLine);
    thumbsMs=ms(selectStarted,Clock::now());
    // SCAM_FM_MAX_DONORS (replays: a reference with fewer donors, e.g. 0 = the base frame alone through the same passes)
    if(const char* e=std::getenv("SCAM_FM_MAX_DONORS"))donors.resize(std::min(donors.size(),size_t(std::max(0,std::atoi(e)))));
    const HybridFrame& base=input.frames[size_t(baseIdx)];
    // long frames rejected before the GPU (0x1751ac): sharpness ratio to the base < 0.35
    std::vector<float> longSharp;int longsDropped=0;
    {
        std::vector<int> kept;
        for(int f:longs){
            const float r=thumbs[size_t(baseIdx)].sharp>0.f?thumbs[size_t(f)].sharp/thumbs[size_t(baseIdx)].sharp:1.f;
            if(r<0.35f){++longsDropped;report("HYBRID FAST MERGE: long frame "+std::to_string(f)+" rejected before the GPU (sharp_ratio "+std::to_string(r)+" < 0.35)");continue;}
            kept.push_back(f);longSharp.push_back(r);
        }
        longs.swap(kept);
    }
    const double selectMs=ms(selectStarted,Clock::now());
    // ---- noise model, SNR and the SNR-adaptive kernel parameters (§3.2, exact)
    const float S=std::max(base.slope,1e-9f),O=std::max(base.offset,0.f);
    // P320 DCG (owner: never one noise curve over a DCG frame): with two measured zones (hybridDcgZones: the noise check ran before
    // this route, the frames carry dcgKnee / dcgOffsetLcg) every place that reads the (S, O) model takes the offset of the level's
    // zone, blended over 0.9 .. 1.1 x the knee as the SCAM programs (hybridDcgShare): the noise LUT per bin, the long frames' weight
    // at 0.01; the scalar SNR (kernel parameters, LK tile, hot pixels) stays one number, keyed with the offset at 0.18 as the SCAM
    // merge's key. Every other burst: the expressions below unchanged.
    const bool dcgZones=hybridDcgZones(input)&&base.dcgKnee>0.f&&base.dcgOffsetLcg>0.f;
    auto zoneOffset=[&](const HybridFrame& fr,float level){
        const float oh=std::max(fr.offset,0.f);
        if(!dcgZones||!(fr.dcgKnee>0.f))return oh;
        return oh+(std::max(fr.dcgOffsetLcg,0.f)-oh)*hybridDcgShare(level,fr.dcgKnee);
    };
    const float keyO=dcgZones?zoneOffset(base,0.18f):O;
    const float snr=0.18f/std::sqrt(std::max(keyO+S*0.18f,1e-9f));
    if(dcgZones){
        char line[260];
        std::snprintf(line,sizeof(line),"HYBRID FAST MERGE DCG: two noise zones (knee %.6g, offset %.6g / %.6g): the noise LUT per bin, long-frame weights at "
            "0.01; SNR %.2f from the offset at 0.18 (LCG share %.3f)",base.dcgKnee,O,base.dcgOffsetLcg,snr,hybridDcgShare(0.18f,base.dcgKnee));
        report(line);
    }
    const float sigma=snr<=8.f?0.33f:(snr<16.f?0.41f-0.01f*snr:0.25f);
    const float covBase0=1.f/sigma,covShrink0=2.2f*covBase0,covStretch0=0.25f*covBase0;
    auto table=[](float x,std::initializer_list<std::pair<float,float>> t){
        const auto* p=t.begin();if(x<=p[0].first)return p[0].second;
        for(size_t i=1;i<t.size();++i)if(x<=p[i].first){const float a=(x-p[i-1].first)/(p[i].first-p[i-1].first);return p[i-1].second+a*(p[i].second-p[i-1].second);}
        return p[t.size()-1].second;
    };
    const float tabA=table(snr,{{6.f,5.f},{12.f,4.f},{30.f,3.f}});
    const float covBlurred0=1.f/(tabA*sigma);
    // P587: the texture / bokeh footprints (hybridKernelFootprint: base / texture, blurred / bokeh, the ramp at transition x the
    // gradient; shrink and stretch keep the reference's 2.2 / 0.25 x the base precision before it); 1 = the reference values
    // P647: x the automatic factors of the SNR key (hybridKernelFootprintSnr; the block of the stream: the app's, else measured)
    const int mosaicBlock=input.mosaic!=0?input.mosaic:hybridMosaicBlock(input,tune,report);
    std::string snrFootLine;
    HybridKernelFootprint footprint=hybridKernelFootprintSnr(tune,snr,mosaicBlock,true,&snrFootLine,1+int(donors.size())); // P647b: the frames merged
    report("HYBRID FAST MERGE: "+snrFootLine);
    if(input.mosaic==2){ // P596: the Quad stream's texture footprint (the app sends the block); P647: the night ramp
        hybridQuadTexture(footprint,tune,hybridSnrFootprintKey(snr,2));
        const float q=hybridQuadTextureFactor(tune,hybridSnrFootprintKey(snr,2));
        if(tune.kernelSnr&&q!=tune.mosaicQuadTexture)report("HYBRID FAST MERGE: SNR footprints: Quad texture x"+std::to_string(q)+" (by day x"+std::to_string(tune.mosaicQuadTexture)+")");
    }
    std::array<float,4> fpA{covShrink0,covStretch0,0.001f,covBase0},fpB{covBlurred0,166.67f,0.f,0.f};
    footprint.apply61(fpA,fpB);
    const float covShrink=fpA[0],covStretch=fpA[1],covBase=fpA[3],covBlurred=fpB[0],covClip=fpA[2],covTrans=fpB[1];
    if(footprint.active()){
        char line[200];
        std::snprintf(line,sizeof(line),"HYBRID FAST MERGE: kernel footprint texture x%.3f, bokeh x%.3f, transition x%.3f",
            footprint.texture,footprint.bokeh,footprint.transition);
        report(line);
    }
    const int lkTile0=snr>=10.8f?16:(snr>2.7f?32:64);
    const int tiles[5]={lkTile0,snr>=10.8f?16:32,16,8,8};
    // ---- white balance of the merge's gains (wbR / wbB of the app, else the base's grey world); undone at the end
    // a Quad stream (2x2 colour blocks; the header's block, or measured): the reference's cfa_sensor_type 1 programs (sh035 guide,
    // sh046 quad gray, QPC sh039 / sh025, sh093 hot pixels, sh051 merge_quad); u_cfa_pattern = the pattern of the blocks
    // stage 4: a 4x4 colour-block stream (Tetra / Quad-Quad) takes the same programs with cfa_sensor_type 2 and merge_quad_quad (sh103)
    const bool quad=mosaicBlock==2||mosaicBlock==4;
    const int sensorType=mosaicBlock==4?2:1; // (quad only)
    // its white balance: the grey world over whole 4x4 colour periods (hybridBlendWb reads 2x2 Bayer cells)
    auto quadWb=[&]{
        if(tune.wbR>0.f&&tune.wbB>0.f)return hybridBlendWb(input,tune);
        if(sensorType==2){ // 4x4 blocks: whole 8x8 colour periods
            const int sx=4*(cfa&1),sy=4*(cfa>>1);double sum[3]{};
            const uint16_t* r=base.raw;
            for(int y=0;y+8<=H;y+=32)for(int x=0;x+8<=W;x+=32){
                float v[4]={0,0,0,0};bool clip=false;
                for(int b=0;b<4;++b)for(int k=0;k<16;++k){
                    const int X=x+((sx+4*(b&1))&7)+(k&3),Y=y+((sy+4*(b>>1))&7)+(k>>2),q=((Y&1)<<1)|(X&1);
                    const float n=(float(r[size_t(Y)*W+X])-input.black[size_t(q)])/std::max(input.white-input.black[size_t(q)],1.f);
                    v[b]+=0.0625f*n;clip=clip||n>=0.98f;
                }
                if(clip)continue;
                sum[0]+=v[0];sum[1]+=0.5*(v[1]+v[2]);sum[2]+=v[3];
            }
            if(sum[0]<=0.0||sum[1]<=0.0||sum[2]<=0.0)return std::array<float,3>{1.f,1.f,1.f};
            return std::array<float,3>{float(std::clamp(sum[1]/sum[0],0.1,10.0)),1.f,float(std::clamp(sum[1]/sum[2],0.1,10.0))};
        }
        const int sx=2*(cfa&1),sy=2*(cfa>>1);double sum[3]{};
        const uint16_t* r=base.raw;
        for(int y=0;y+4<=H;y+=16)for(int x=0;x+4<=W;x+=16){
            float v[4]={0,0,0,0};bool clip=false;
            for(int b=0;b<4;++b)for(int k=0;k<4;++k){
                const int X=x+((sx+2*(b&1))&3)+(k&1),Y=y+((sy+2*(b>>1))&3)+(k>>1),q=((Y&1)<<1)|(X&1);
                const float n=(float(r[size_t(Y)*W+X])-input.black[size_t(q)])/std::max(input.white-input.black[size_t(q)],1.f);
                v[b]+=0.25f*n;clip=clip||n>=0.98f;
            }
            if(clip)continue;
            sum[0]+=v[0];sum[1]+=0.5*(v[1]+v[2]);sum[2]+=v[3];
        }
        if(sum[0]<=0.0||sum[1]<=0.0||sum[2]<=0.0)return std::array<float,3>{1.f,1.f,1.f};
        return std::array<float,3>{float(std::clamp(sum[1]/sum[0],0.1,10.0)),1.f,float(std::clamp(sum[1]/sum[2],0.1,10.0))};
    };
    const std::array<float,3> wb=quad?quadWb():hybridBlendWb(input,tune);
    // sensor 2x2 phase -> colour index (0 R, 1 G on the R row, 2 G on the B row, 3 B): the reference's colour-ordered uniforms
    const int dx=cfa&1,dy=cfa>>1;
    int colourOf[4];
    for(int p=0;p<4;++p){const int x=p&1,y=p>>1;colourOf[p]=(x==dx&&y==dy)?0:(x!=dx&&y!=dy)?3:(y==dy?1:2);}
    std::array<float,4> blackSensor{},blackColour{},gainSensor{},bltgSensor{},gainColour{},bltgColour{},grayCap{};
    const float wbOf[4]={wb[0],wb[1],wb[1],wb[2]};
    const float grayGain=16.f*1023.f/std::max(input.white,1023.f); // sh046's uGain 16 at the reference's 10-bit range; the same gray scale at any depth
    for(int p=0;p<4;++p){
        const int c=colourOf[p];const float bl=input.black[size_t(p)],range=std::max(input.white-bl,1.f);
        blackSensor[size_t(p)]=bl;blackColour[size_t(c)]=bl;
        gainSensor[size_t(p)]=wbOf[c]/range;bltgSensor[size_t(p)]=-bl*gainSensor[size_t(p)];
        gainColour[size_t(c)]=gainSensor[size_t(p)];bltgColour[size_t(c)]=bltgSensor[size_t(p)];
        grayCap[size_t(p)]=(std::max(input.white,1023.f)-bl)*grayGain;
    }
    const float blackMean=0.25f*(input.black[0]+input.black[1]+input.black[2]+input.black[3]);
    // the pyramid (§4.6): L0 HALF, then /2, /4, /4, /4 (ceil); cumulative scale to HALF
    int lw[5],lh[5];float lscale[5];
    lw[0]=w2;lh[0]=h2;lscale[0]=1.f;
    for(int l=1;l<5;++l){const int f=l==1?2:4;lw[l]=(lw[l-1]+f-1)/f;lh[l]=(lh[l-1]+f-1)/f;lscale[l]=lscale[l-1]*float(f);}
    int gx[5],gy[5];
    for(int l=0;l<5;++l){gx[l]=(lw[l]+tiles[l]-1)/tiles[l];gy[l]=(lh[l]+tiles[l]-1)/tiles[l];}
    // ---- the highlight path's decision (§3.3, 0x16dc28..0x16e128): the base's RAW sites at >= black + 0.98 (white - black) on a
    // grid (step max(4, 2 (W / 240)) px, 4 px borders) over the sites seen; above 0.000390 the highlight path runs, with every short
    // frame of exposure factor >= 2 (none: the path is off). SCAM_FM_NO_SHORT: off (A/B).
    float clipFraction=0.f;
    {
        const int stepX=std::max(4,2*(W/240)),stepY=std::max(4,2*(H/240));
        long clipped=0,seen=0;
        const float at=input.black[0]+0.98f*(input.white-input.black[0]);
        for(int y=4;y<H-4;y+=stepY){const uint16_t* r=base.raw+size_t(y)*W;for(int x=4;x<W-4;x+=stepX){++seen;clipped+=float(r[x])>=at?1:0;}}
        clipFraction=seen?float(clipped)/float(seen):0.f;
    }
    // P497 (owner, 2026-11-04, the sun in fog, SCA_20261104_063920: a grey disc): 0.0001 (two grid samples) in place of the scheme's
    // 0.00039 - a small sun or lamp hits the sparse grid only once or twice; the short frames' own gates still decide
    const float clipPath=std::getenv("SCAM_FM_CLIP_PATH")?float(std::atof(std::getenv("SCAM_FM_CLIP_PATH"))):0.0001f;
    bool bento=clipFraction>clipPath&&!shorts.empty()&&!std::getenv("SCAM_FM_NO_SHORT");
    const int shortsSent=int(shorts.size());
    if(!bento)shorts.clear();
    const int N=1+int(donors.size())+int(longs.size()); // num_frames of the merge: the short frames apart (as 0x16dc00)
    {
        char line[700];
        std::snprintf(line,sizeof(line),"HYBRID FAST MERGE: Sabre per-frame merge, %d frames (base %d + %d normal donors + %d long; %d of %d short frames; left out: %d weak short, %d other; "
            "%d long as normal), %dx%d, SNR=%.2f: shrink=%.2f base=%.2f stretch=%.2f blurred=%.2f, LK tiles %d/%d/%d/%d/%d (L0 grid %dx%d), wb %.3f/%.3f, clip fraction %.6f -> highlight path %s, select %.0f ms (thumbnails %.0f)",
            N,baseIdx,int(donors.size()),int(longs.size()),int(shorts.size()),shortsSent,shortsWeak,other,longsAsNormal,W,H,snr,covShrink,covBase,covStretch,covBlurred,
            tiles[0],tiles[1],tiles[2],tiles[3],tiles[4],gx[0],gy[0],wb[0],wb[2],clipFraction,bento?"on":"off",selectMs,thumbsMs);
        report(line);
        report("HYBRID FAST MERGE "+selectLine+(dropped?"":"; no donor dropped")+((tune.fastMergeSport&1)?"; sport rejection (P488): boost 10, pixel difference 0.35":""));
    }
    // ---- page faults of the mapped burst ahead of the uploads (the frames in merge order)
    std::atomic<bool> stopTouch{false};
    std::thread toucher;
    try{toucher=std::thread([&]{
        std::vector<const uint16_t*> order{base.raw};
        for(int f:shorts)order.push_back(input.frames[size_t(f)].raw);
        for(int f:donors)order.push_back(input.frames[size_t(f)].raw);
        for(int f:longs)order.push_back(input.frames[size_t(f)].raw);
        const size_t bytes=size_t(W)*H*2;volatile unsigned sink=0;
        for(const uint16_t* r:order){
            const char* p=reinterpret_cast<const char*>(r);
            for(size_t o=0;o<bytes&&!stopTouch.load(std::memory_order_relaxed);o+=4096)sink+=unsigned(p[o]);
        }
        (void)sink;
    });}catch(const std::exception&){}
    struct ToucherJoin{std::atomic<bool>& s;std::thread& t;~ToucherJoin(){s=true;if(t.joinable())t.join();}} toucherJoin{stopTouch,toucher};
    // P566: the Quad sites' response (the native merge's, over the base and the normal donors) on a CPU thread while the context
    // and the first uploads are made; the unpack applies it (u_site_gains) and the QPC estimate is left out
    std::array<float,64> siteGain;siteGain.fill(1.f);
    HybridGainField siteField;
    std::string siteLine;
    std::future<void> siteJob;
    if(quad&&sensorType==1&&mosaicBlock==2&&tune.fastSiteGain!=0&&tune.mosaicGain){
        try{siteJob=std::async(std::launch::async,[&]{
            std::vector<int> gf{baseIdx};
            for(int f:donors)if(gf.size()<(tune.mosaicGain>=2?16u:4u)&&input.frames[size_t(f)].role==kRoleNormal)gf.push_back(f);
            float spread=0,err=0;long used=0;
            siteGain=hybridMosaicGains(input,gf,2,spread,&used,tune.mosaicGain,&err,tune.mosaicGainField?&siteField:nullptr,tune.mosaicGainZones);
            char line[300];std::snprintf(line,sizeof(line),"HYBRID FAST MERGE MOSAIC: site response from %d frames, %ld smooth tiles, gain range %.4f..%.4f "
                "(spread %.2f %%), error %.3f %%, field %s (P566: applied in the unpack, the QPC left out)",int(gf.size()),used,
                *std::min_element(siteGain.begin(),siteGain.end()),*std::max_element(siteGain.begin(),siteGain.end()),100.0*spread,100.0*err,
                siteField.on()?(std::to_string(siteField.zx)+"x"+std::to_string(siteField.zy)+" zones").c_str():"off");
            siteLine=line;
        });}catch(const std::exception&){}
    }
    // the result vector (150 MB of zeroed pages at 12 MP) on a helper thread meanwhile
    // and then the worker's output memfd reserved and mapped (every page in place) for the halves (hybridFinalHalvesHook), on the
    // same thread (the result's data pointer names the stream)
    // P563: a Quad / Tetra result takes the chroma median below (it reads the whole result): not streamed
    const bool mosaicChroma=quad&&tune.fastMosaicChroma!=0&&tune.mosaicChroma;
    // P564: its GPU context builds during the merge; P610: with the Quad lattice's programs when the lattice follows the median
    const bool latticeAfter=quad&&sensorType==1&&tune.fastQuadLattice!=0&&tune.chromaGpu>0;
    if(mosaicChroma&&tune.chromaGpu>0)chromaPrewarm(false,latticeAfter);
    const bool streamWanted=hybridFinalHalvesHook()&&!(mergedDng&&input.mergedDng)&&!std::getenv("SCAM_NO_STREAM_OUT")&&!mosaicChroma;
    // P662: a result the stream prepared for is only reserved (its data pointer names the stream; nothing reads its floats when the
    // stream takes the halves): no 600 MB of zeroed pages at 50 MP; a stream that refuses (at the preparation, or later) gets the
    // floats as before (ensureOut)
    std::future<std::vector<float>> outAlloc=std::async(std::launch::async,[n=size_t(OW)*OH*3,streamWanted,OW,OH]{
        std::vector<float> v;
        if(!streamWanted){v.resize(n);return v;}
        v.reserve(n);
        if(!hybridFinalHalvesHook()(nullptr,OW,OH,0,0,v.data()))v.resize(n);
        return v;
    });
    const size_t outFloats=size_t(OW)*OH*3;
    auto ensureOut=[outFloats](std::vector<float>& v){if(v.size()<outFloats)v.resize(outFloats);}; // (within its reservation: the same data)
    // ---- row strips above 16 MP (owner, 2026-11-03): phase A (every frame's alignment, rejection chain and frame weights at HALF,
    // as below) keeps each frame's flow and rejection map; phase B then merges the output in strips of `stripRows` rows: per strip and
    // frame the frame's RAW rows the strip needs (its flow's vertical range + a margin) are uploaded, unpacked and guided again
    // (scissor), and merged into the strip's accumulators (the whole-frame viewport moved up by the strip's first row: the same
    // tex_coord per fragment); then the short frames, the base self-merge and the output pass of the strip, read back at once.
    // The accumulators (16 B per pixel, RGBA16F as the reference) and the readback hold one strip. SCAM_FM_STRIP_ROWS (replays):
    // strips of that many rows at any size (the equality check against the whole-frame merge).
    int stripRows=0;
    if(const char* e=std::getenv("SCAM_FM_STRIP_ROWS"))stripRows=std::atoi(e);
    // P501: strips of OUT rows; also when the whole OUT's accumulators pass 512 MB (2x of 12 MP: 800 MB; 1.5x: 450 MB stays whole)
    if(stripRows<=0&&(int64_t(W)*H>kHybridClassicPixels||int64_t(OW)*OH*16>2*int64_t(kFastStripBytes)))
        stripRows=int(std::max<int64_t>(64,std::min<int64_t>(OH,int64_t(kFastStripBytes)/(23*int64_t(OW)))));
    if(stripRows>0)stripRows=std::min(OH,std::max(64,stripRows&~15));
    const bool stripMode=stripRows>0&&(stripRows<OH||std::getenv("SCAM_FM_STRIP_ROWS"));
    // ---- the context (prewarmed during the burst's delivery, else made here)
    const auto gpuStarted=Clock::now();
    std::string prewarmWhy;
    std::unique_ptr<FastMergeGpu> held=fastGpuPrewarmTake(prewarmWhy);
    bool prewarmed=held!=nullptr;
    if(held){try{held->acquire();}catch(const std::exception& e){held.reset();prewarmed=false;prewarmWhy=e.what();}}
    if(!held)held=std::make_unique<FastMergeGpu>(hybridProgramCacheDir());
    FastMergeGpu& g=*held;
    for(const std::string& n:g.notes)report(n); // P500
    g.notes.clear();
    struct Release{std::unique_ptr<FastMergeGpu>& g;~Release(){
        if(!g)return;
        // the merge's textures and the context go on a helper thread (joined at the exit); a refused thread destroys them here
        try{g->freeObjects();g->release();
            FastMergeTeardown& td=fastMergeTeardown();if(td.t.joinable())td.t.join();
            td.t=std::thread([p=std::move(g)]() mutable {try{p->acquire();}catch(...){}p.reset();});
        }catch(...){g.reset();}
    }} releaseGuard{held};
    if(std::getenv("SCAM_FM_FAIL"))throw std::runtime_error("SCAM_FM_FAIL (replay check of the fallback)");
    g.selectRejFilter(tune.fmFilterGather!=0); // P484
    GLint maxTex=0;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&maxTex);
    // P627: the byte stream is a texture only with SCAM_FM_DIRECT_UPLOAD (else storage buffers: the widest texture is HALF / OUT)
    const int widest=std::getenv("SCAM_FM_DIRECT_UPLOAD")?2*W:w2;
    if(const char* e=std::getenv("SCAM_FM_MAX_TEX"))maxTex=std::min<GLint>(maxTex,std::atoi(e)); // host checks of the column bands
    if(widest>maxTex||H>maxTex)throw FastMergeLimit("RAW "+std::string(widest==2*W?"byte stream ":"HALF ")+std::to_string(widest)+"x"+std::to_string(H)+" above GL_MAX_TEXTURE_SIZE "+std::to_string(maxTex),
        H>maxTex?0:int(int64_t(W)*maxTex/widest));
    if(OW>maxTex||OH>maxTex)throw FastMergeLimit("output "+std::to_string(OW)+"x"+std::to_string(OH)+" above GL_MAX_TEXTURE_SIZE "+std::to_string(maxTex),
        OH>maxTex?0:int(int64_t(W)*maxTex/OW));
    GLint ssboAlign=256;glGetIntegerv(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT,&ssboAlign);ssboAlign=std::max(ssboAlign,64);
    const auto gpuReady=Clock::now();
    // tune profile: GPU timestamps (EXT_disjoint_timer_query, no stall) at every stage boundary, summed per stage after the readback
    typedef void (*QueryCounterFn)(GLuint,GLenum);typedef void (*GetQueryObjectui64Fn)(GLuint,GLenum,GLuint64*);
    QueryCounterFn queryCounter=nullptr;GetQueryObjectui64Fn queryResult=nullptr;
    if(tune.profile!=0){
        queryCounter=reinterpret_cast<QueryCounterFn>(eglGetProcAddress("glQueryCounterEXT"));
        queryResult=reinterpret_cast<GetQueryObjectui64Fn>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
    }
    // profile 2: a glFinish at every boundary and the CPU clock (Adreno's timestamps read 0 around compute passes); replays only
    const bool syncProfile=tune.profile==2;
    const bool profile=syncProfile||(queryCounter&&queryResult);
    std::array<double,16> stageMs{}; // profile: see the profile line
    std::vector<std::pair<GLuint,int>> stamps; // (query, stage that ends at it)
    auto syncAt=Clock::now();
    auto lap=[&](int k){
        scam_progress::tick();
        if(syncProfile){glFinish();const auto t=Clock::now();if(k>=0)stageMs[size_t(k)]+=ms(syncAt,t);syncAt=t;return;}
        // P497 (owner, 2026-11-04: the viewfinder stutters while a shot is processed, every phone): a submission at every stage
        // boundary - the GPU lets the viewfinder's context in between submissions (Xiaomi 12X: single viewfinder draws of up to
        // 274 ms during the merge, one submission per frame of ~100 ms GPU there)
        if(!profile){glFlush();return;}
        GLuint q=0;glGenQueries(1,&q);queryCounter(q,0x8E28 /*GL_TIMESTAMP_EXT*/);stamps.emplace_back(q,k);
    };
    lap(-1);
    const int PG=quad?int(FastMergeGpu::pGuideQuad):int(FastMergeGpu::pGuide);
    const int PQ=sensorType==2?int(FastMergeGpu::pMergeQuadQuad):int(FastMergeGpu::pMergeQuad);
    const int PMB=quad?PQ:int(FastMergeGpu::pMergeB),PMA=quad?PQ:int(FastMergeGpu::pMergeA);
    if(quad)report("HYBRID FAST MERGE MOSAIC: "+std::to_string(mosaicBlock)+"x"+std::to_string(mosaicBlock)+" colour blocks, cfa_sensor_type "
        +std::to_string(sensorType)+(sensorType==2?" (merge_quad_quad)":" (merge_quad)"));
    // ---- textures (§3.6) and framebuffers (static, as the reference's)
    const bool lin32=g.floatLinear; // filtered RGBA32F / R32F where the GPU can (OES_texture_float_linear), else nearest
    GLuint raw[2]={};
    if(std::getenv("SCAM_FM_DIRECT_UPLOAD")){raw[0]=g.texture(GL_R8UI,2*W,H,false);raw[1]=g.texture(GL_R8UI,2*W,H,false);}
    GLuint bayerBase=g.texture(GL_RGBA16F,w2,h2,false),bayerCur=g.texture(GL_RGBA16F,w2,h2,false);
    // Quad: the unpacked quads before the hot pixels (sh093 reads one texture and writes another), the QPC grid (16 x 12)
    GLuint bayerTmp=0,qpcRaw=0,qpcMap=0;
    GLuint siteTex=0;double siteWaitMs=0; // P566
    if(quad){bayerTmp=g.texture(GL_RGBA16F,w2,h2,false);qpcRaw=g.texture(GL_RGBA16F,16,12,false);qpcMap=g.texture(GL_RGBA16F,16,12,true);}
    GLuint guideBase=g.texture(GL_RGBA16F,w2,h2,true),covBaseT=g.texture(GL_RGB10_A2,w2,h2,true);
    GLuint guideCur=g.texture(GL_RGBA16F,w2,h2,true),covCur=g.texture(GL_RGB10_A2,w2,h2,true);
    GLuint refColor=g.texture(GL_RGBA16F,w2,h2,true),refLuma=g.texture(GL_R16F,w2,h2,true);
    GLuint noiseLut=g.texture(GL_RGBA32F,64,2,lin32);
    const int ubw=std::max(1,w2/8),ubh=std::max(1,h2/8);
    GLuint unblock=g.texture(GL_R32F,ubw,ubh,lin32);
    GLuint pyrB[5],pyrC[5];
    for(int l=0;l<5;++l){pyrB[l]=g.texture(GL_R16F,lw[l],lh[l],true);pyrC[l]=g.texture(GL_R16F,lw[l],lh[l],true);}
    GLuint g8Base=g.texture(GL_RGBA8,lw[1],lh[1],true),g8Cur=g.texture(GL_RGBA8,lw[1],lh[1],true);
    GLuint prod0[5],prod1[5],alA[5],alB[5],alM[5];
    for(int l=0;l<5;++l){
        prod0[l]=g.texture(GL_RGBA32F,gx[l],gy[l],false);prod1[l]=g.texture(GL_R32F,gx[l],gy[l],false);
        alA[l]=g.texture(GL_RGBA32F,gx[l],gy[l],false);alB[l]=g.texture(GL_RGBA32F,gx[l],gy[l],false);alM[l]=g.texture(GL_RGBA32F,gx[l],gy[l],false);
    }
    GLuint flowT=g.texture(GL_RGBA32F,gx[0],gy[0],lin32);
    // P601 fastFlowPick: every donor's flow on a grid of 4 x 4 HALF blocks (kFastFlowPick), read by the rejection and the merge in
    // place of the L0 tile field; 0 = the tile field (no texture, no pass, the uniforms as before)
    // (a GPU whose compiler refuses the pick's programs merges without it, as fastFlowPick 0; the note is reported)
    const bool flowPick=tune.fastFlowPick!=0&&g.flowPickReady(tune.fastFlowPick>=2),staticRescue=flowPick&&tune.fastFlowPick>=2;
    for(const std::string& n:g.notes)report(n);
    g.notes.clear();
    // (texel (i, j) of the block grid at HALF (4 i, 4 j): its nodes hold the tile field's own nodes ((n + 1.5) 16), so a block that
    // keeps the field is read back as the field itself, kinks included)
    const int pickB=4,pgx=w2/pickB+2,pgy=h2/pickB+2;
    GLuint flowP=flowPick?g.texture(GL_RGBA32F,pgx,pgy,lin32):0;
    // (the surroundings' flow of the tiles, the blocks' fit: one each, used by the frame's own passes right after the pick)
    GLuint flowBg=flowPick?g.texture(GL_RGBA32F,gx[0],gy[0],lin32):0,pickFit=flowPick?g.texture(GL_RG16F,pgx,pgy,true):0;
    const GLuint fFlowBg=flowPick?g.fbo({flowBg}):0;
    GLuint rejRaw=g.texture(GL_RG8,w2,h2,true),rejF=g.texture(GL_R8,w2,h2,true),rejD=g.texture(GL_R8,w2,h2,true),rejW=g.texture(GL_RG8,w2,h2,true);
    GLuint accW=g.texture(GL_R16F,w2,h2,true);
    GLuint acc0=g.texture(GL_RGBA16F,OW,stripMode?stripRows:OH,true),acc1=g.texture(GL_RGBA16F,OW,stripMode?stripRows:OH,true);
    if(stripMode){ // the strips' limits: the viewport moved up by a strip spans the frame, the upload ring holds a whole frame
        GLint vp[2]={0,0},blk=0;glGetIntegerv(GL_MAX_VIEWPORT_DIMS,vp);glGetIntegerv(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&blk);
        if(const char* e=std::getenv("SCAM_FM_MAX_BLOCK_MB"))blk=std::min<GLint>(blk>0?blk:INT32_MAX,std::atoi(e)<<20); // host checks
        if(OH>vp[1]||OW>vp[0])throw FastMergeLimit("output "+std::to_string(OW)+"x"+std::to_string(OH)+" above GL_MAX_VIEWPORT_DIMS",OH>vp[1]?0:int(int64_t(W)*vp[0]/OW));
        if(blk>0&&uint64_t(W)*H*2>uint64_t(uint32_t(blk)))throw FastMergeLimit("RAW frame above GL_MAX_SHADER_STORAGE_BLOCK_SIZE ("+std::to_string(blk>>20)+" MB)",
            int(uint64_t(uint32_t(blk))/(uint64_t(H)*2)));
    }
    GLuint zeroFlow=g.texture(GL_RGBA16F,1,1,false),constRej=g.texture(GL_RG8,1,1,false),gainOne=g.texture(GL_RGBA16F,1,1,false),
           offsetZero=g.texture(GL_RGBA16F,1,1,false),zeroMask=g.texture(GL_R8,1,1,false),oneScope=g.texture(GL_R8,1,1,false);
    // the highlight chain (§4.5, §5.8; HALF unless said): base clip mask (0x94, RG), its blur (0x1b8 as RG), the base highlight mask
    // (0x530), the final mask (0x534), the short frame's validation (0x158), the accumulated validity (0x148), its clean copy, the
    // quarantine blocks at HALF / 8 (0x298, 0x280), the safety gate's sums
    const int qw=std::max(1,w2/8),qh=std::max(1,h2/8);
    GLuint hlClip=0,hlBlur=0,hlBase=0,hlFinal=0,hlValid=0,hlAccum=0,hlClean=0,quar[2]={0,0},bStats=0;
    GLuint fHlClip=0,fHlBlur=0,fHlBase=0,fHlFinal=0,fHlValid=0,fHlAccum=0,fHlClean=0,fQuar[2]={0,0};
    if(bento){
        hlClip=g.texture(GL_RG16F,w2,h2,true);hlBlur=g.texture(GL_RG16F,w2,h2,true);hlBase=g.texture(GL_R16F,w2,h2,true);hlFinal=g.texture(GL_R16F,w2,h2,true);
        hlValid=g.texture(GL_RGBA16F,w2,h2,true);hlAccum=g.texture(GL_R16F,w2,h2,true);hlClean=g.texture(GL_R16F,w2,h2,true);
        quar[0]=g.texture(GL_RGBA16F,qw,qh,true);quar[1]=g.texture(GL_RGBA16F,qw,qh,true);
        fHlClip=g.fbo({hlClip});fHlBlur=g.fbo({hlBlur});fHlBase=g.fbo({hlBase});fHlFinal=g.fbo({hlFinal});fHlValid=g.fbo({hlValid});
        fHlAccum=g.fbo({hlAccum});fHlClean=g.fbo({hlClean});fQuar[0]=g.fbo({quar[0]});fQuar[1]=g.fbo({quar[1]});
        bStats=g.buffer(16,GL_DYNAMIC_READ);
    }
    // every short frame keeps its Bayer, guide, covariance and flow (uv, the L0 grid) to its merge after the loop
    struct ShortFrame { int f=0;float ev=1;GLuint bayer=0,guide=0,cov=0,flow=0,fGuide=0;bool valid=false; };
    std::vector<ShortFrame> shortFrames;
    // P540: the clip of the valid short frames in the base's units (0.94 x the lowest gain), for the output's clip flags
    auto clipShort=[&]{float e=0.f;for(const ShortFrame& q:shortFrames)if(q.valid)e=e>0.f?std::min(e,q.ev):q.ev;return e>1.f?0.94f*e:0.f;};
    {
        const uint16_t h0[4]={0,0,0,0},h1[4]={0x3c00,0x3c00,0x3c00,0x3c00};
        const uint8_t rej10[2]={255,0},z8=0;
        glPixelStorei(GL_UNPACK_ALIGNMENT,1);
        glBindTexture(GL_TEXTURE_2D,zeroFlow);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RGBA,GL_HALF_FLOAT,h0);
        glBindTexture(GL_TEXTURE_2D,offsetZero);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RGBA,GL_HALF_FLOAT,h0);
        glBindTexture(GL_TEXTURE_2D,gainOne);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RGBA,GL_HALF_FLOAT,h1);
        // [UNCERTAIN] §11.9: the base self-merge's constant rejection (weight 1, blend_eq 0)
        glBindTexture(GL_TEXTURE_2D,constRej);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RG,GL_UNSIGNED_BYTE,rej10);
        glBindTexture(GL_TEXTURE_2D,zeroMask);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RED,GL_UNSIGNED_BYTE,&z8);
        const uint8_t one8=255;
        glBindTexture(GL_TEXTURE_2D,oneScope);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RED,GL_UNSIGNED_BYTE,&one8);
    }
    // ---- the noise LUT (§4.2, helper 0x184574, decoded): 64 bins at the sqrt-domain luma (i + 0.5) / 64, linear level I = luma^2;
    // row 0 = the reference (base) model, row 1 = the current frame's model brought to base units by e = min(ev, 1): S e, O e^2.
    // var = max(S I + k O, 1e-7) x scale, stored (var, var, var, sqrt var). k = 1 + (1 - smoothstep(0.003, 0.012, I)) x boost on the
    // dark-read path (taken for ev < 0.85 or a measured dark read); boost and scale come from the RAW noise measurement, which is
    // not run ([NOT RECOVERED] its rule): boost 0, scale 1 (the HAL model). Recomputed when the current model changes.
    float lutS=-1.f,lutO=-1.f,lutE=-1.f;const HybridFrame* lutZ=nullptr;
    const HybridFrame* lutFrame=&base; // DCG: the frame whose zones row 1 takes (set before each frame's guide)
    auto noiseLutFor=[&](float Sc,float Oc,float ev){
        const float e=std::min(ev,1.f),s1=Sc*e,o1=Oc*e*e;
        const HybridFrame* z=dcgZones?lutFrame:nullptr;
        if(s1==lutS&&o1==lutO&&e==lutE&&z==lutZ)return;
        lutS=s1;lutO=o1;lutE=e;lutZ=z;
        std::vector<float> lut(64*2*4);
        for(int r=0;r<2;++r)for(int i=0;i<64;++i){
            const float l=(float(i)+0.5f)/64.f,I=l*l;
            float var=std::max((r?s1:S)*I+(r?o1:O),1e-7f);
            // DCG: row 0 the base's zone at I, row 1 the frame's zone at its own level I / e, brought to base units (e^2)
            if(z)var=std::max(r?(s1*I+zoneOffset(*z,I/std::max(e,1e-6f))*e*e):(S*I+zoneOffset(base,I)),1e-7f);
            float* q=&lut[size_t((r*64+i)*4)];q[0]=q[1]=q[2]=var;q[3]=std::sqrt(var);
        }
        glBindTexture(GL_TEXTURE_2D,noiseLut);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,64,2,GL_RGBA,GL_FLOAT,lut.data());
        glBindTexture(GL_TEXTURE_2D,0);
    };
    noiseLutFor(S,O,1.f);
    const GLuint fGuideBase=g.fbo({guideBase,covBaseT,pyrB[0]}),fGuideCur=g.fbo({guideCur,covCur,pyrC[0]});
    const GLuint fGrayB=quad?g.fbo({pyrB[0]}):0,fGrayC=quad?g.fbo({pyrC[0]}):0; // Quad: gray L0 by sh046
    const GLuint fRef=g.fbo({refColor,refLuma});
    GLuint fPyrB[5]{},fPyrC[5]{},fMedian[5]{};
    for(int l=1;l<5;++l){fPyrB[l]=g.fbo({pyrB[l]});fPyrC[l]=g.fbo({pyrC[l]});}
    for(int l=0;l<5;++l)fMedian[l]=g.fbo({alM[l]});
    const GLuint fG8Base=g.fbo({g8Base}),fG8Cur=g.fbo({g8Cur}),fFlow=g.fbo({flowT});
    const GLuint fRej=g.fbo({rejRaw}),fRejF=g.fbo({rejF}),fRejD=g.fbo({rejD}),fRejW=g.fbo({rejW}),fAccW=g.fbo({accW}),fMerge=g.fbo({acc0,acc1});
    // P484 fmMergeBatch: slot 0 = the frame textures above, slots 1.. their copies for the frames whose merges wait for the batch
    const int loopMerges=int(donors.size()+longs.size());
    const int mergeBatch=stripMode?1:std::max(1,std::min({tune.fmMergeBatch,8,std::max(loopMerges,1)}));
    std::vector<GLuint> slotBayer{bayerCur},slotCov{covCur},slotGuideFbo{fGuideCur},slotFlow{flowT},slotFlowFbo{fFlow},slotRejW{rejW},slotRejWFbo{fRejW};
    std::vector<GLuint> slotPick{flowP},slotPickFbo{flowPick?g.fbo({flowP,pickFit}):0}; // P601
    for(int sl=1;sl<mergeBatch;++sl){
        slotBayer.push_back(g.texture(GL_RGBA16F,w2,h2,false));slotCov.push_back(g.texture(GL_RGB10_A2,w2,h2,true));
        slotGuideFbo.push_back(g.fbo({guideCur,slotCov.back(),pyrC[0]}));
        slotFlow.push_back(g.texture(GL_RGBA32F,gx[0],gy[0],lin32));slotFlowFbo.push_back(g.fbo({slotFlow.back()}));
        slotRejW.push_back(g.texture(GL_RG8,w2,h2,true));slotRejWFbo.push_back(g.fbo({slotRejW.back()}));
        if(flowPick){slotPick.push_back(g.texture(GL_RGBA32F,pgx,pgy,lin32));slotPickFbo.push_back(g.fbo({slotPick.back(),pickFit}));}
    }
    std::vector<std::function<void()>> pendingMerges; // the batch's merges, drawn in the loop's order
    auto flushMerges=[&]{if(pendingMerges.empty())return;for(auto& m:pendingMerges)m();pendingMerges.clear();glFlush();};
    // homography buffers: corners (576 x 16 B), tracks (576 x 32 B), one result slot per frame (bound by range, read at the end)
    const GLuint bCorners=g.buffer(576*16),bTracks=g.buffer(576*32),bCoarse=g.buffer(17*17*4);
    const size_t resStride=size_t((64+ssboAlign-1)/ssboAlign*ssboAlign);
    const int loopFrames=int(shorts.size()+donors.size()+longs.size());
    const GLuint bResult=g.buffer(resStride*size_t(loopFrames+2)); // P497: + a spare slot (the unseeded homography)
    FastMergeGpu::check("objects");
    const auto objectsMade=Clock::now();
    // clears (§4.7): the accumulators and the accumulated frame weights to 0
    {
        const float z[4]={0,0,0,0};
        glBindFramebuffer(GL_FRAMEBUFFER,fMerge);glClearBufferfv(GL_COLOR,0,z);glClearBufferfv(GL_COLOR,1,z);
        glBindFramebuffer(GL_FRAMEBUFFER,fAccW);glClearBufferfv(GL_COLOR,0,z);
    }
    // ---- per-pass uniforms that do not change over the burst
    // flow_scale_offset (§11.8 [UNCERTAIN]): the flow texel of tile i sits at HALF pixel (i + 1.5) stride
    const float fsx=float(w2)/float(tiles[0]*gx[0]),fsy=float(h2)/float(tiles[0]*gy[0]),fox=-1.f/float(gx[0]),foy=-1.f/float(gy[0]);
    // the flow_scale_offset the rejection and the merge read a donor's flow with: the tile field's, or (P601) the block grid's,
    // whose texel (i, j) sits at HALF (4 i, 4 j)
    const std::array<float,4> dso=flowPick?std::array<float,4>{float(w2)/float(pickB*pgx),float(h2)/float(pickB*pgy),0.5f/float(pgx),0.5f/float(pgy)}:std::array<float,4>{fsx,fsy,fox,foy};
    const float fb[4]={1.5f/float(w2),1.5f/float(h2),1.f-1.5f/float(w2),1.f-1.5f/float(h2)};
    const float nsb[4]={63.f/64.f,0.5f,1.f/128.f,0.25f};
    // the reference's packing range of the kernel precisions (RGB10_A2): its top (~24.8 = precision 5) caps every narrower kernel -
    // the shrink across edges (8.8 at day) always, the base beyond x0.81. P647: by day the footprint's lift (hybridSnrFactors: 1 / e^2
    // of the across-edge factor e; the Bayer stream its own, P647b) lifts the range (10-bit steps that much coarser); lift 1 (kernelSnr
    // 0, the pivot and below): the reference's range, bit for bit
    const float rangeLift=std::max(footprint.lift,1.f);
    const float covRangeRg[4]={0.367188f,24.44773f*rangeLift,0.367188f,25.68449f*rangeLift},covRangeB[2]={-6.97557f*rangeLift,14.00209f*rangeLift};
    const float identity[16]={1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    // hot pixels (§4.1): sabre_hot_pixel_strength 1 below sabre_hot_pixel_max_snr 15; [NOT RECOVERED] the value above it (0 here)
    // and u_threshold_offset (1 % of the range here)
    const float hotStrength=snr<15.f?1.f:0.f;
    {
        using A=FastMergeGpu;
        g.u1i(A::pUnpack,"u_width",w2);g.u1i(A::pUnpack,"u_height",h2);g.u1i(A::pUnpack,"u_cfa_pattern",cfa);g.u1i(A::pUnpack,"u_raw_format",32);
        g.u4f(A::pUnpack,"u_black_level",blackColour);g.u1f(A::pUnpack,"u_white_level",input.white);
        g.u1f(A::pUnpack,"u_threshold_offset",0.01f*(input.white-blackMean));g.u1f(A::pUnpack,"u_suppression_strength",hotStrength);g.u1f(A::pUnpack,"u_threshold_mult",3.f);
        if(siteJob.valid()){
            {const auto w0=Clock::now();siteJob.get();siteWaitMs=ms(w0,Clock::now());}
            const bool fieldOn=siteField.on();
            const int nzx=fieldOn?siteField.zx:1,nzy=fieldOn?siteField.zy:1;
            const float zw=fieldOn?float(siteField.zw):float(W),zh=fieldOn?float(siteField.zh):float(H);
            std::vector<float> t(size_t(nzx)*4*size_t(nzy)*4*4);
            for(int Z=0;Z<nzy;++Z)for(int X=0;X<nzx;++X)for(int c=0;c<4;++c)for(int a=0;a<4;++a)for(int s4=0;s4<4;++s4){
                const int x=2*a+(s4&1),y=2*c+(s4>>1),k=(y<<3)|x;
                t[((size_t(Z)*4+c)*size_t(nzx)*4+size_t(X)*4+a)*4+s4]=fieldOn?siteField.g[(size_t(Z)*nzx+X)*64+k]:siteGain[size_t(k)];
            }
            siteTex=g.texture(GL_RGBA32F,nzx*4,nzy*4,false);
            glBindTexture(GL_TEXTURE_2D,siteTex);glPixelStorei(GL_UNPACK_ALIGNMENT,4);
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,nzx*4,nzy*4,GL_RGBA,GL_FLOAT,t.data());
            for(int p:{int(A::pUnpack),int(A::pUnpackBuf)}){
                g.u1i(p,"u_site_gain",1);g.u4f(p,"u_site_zone",zw,zh,float(nzx),float(nzy));g.u4f(p,"u_site_black",blackSensor);
                g.u1f(p,"u_site_clip",input.white-1.f);g.u1f(p,"u_site_white",input.white);
            }
            if(!siteLine.empty())report(siteLine+", waited "+std::to_string(int(siteWaitMs))+" ms");
        }
        // guide (§4.3)
        g.u4f(PG,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
        // (gains, black_levels_times_gains and the gray gain are the frame's: frontEnd)
        g.u1f(PG,"green_clipping_point",std::sqrt(0.95f*wb[1])); // [UNCERTAIN] the base green at 95 % of the range
        g.u4f(PG,"frame_border_padded",fb[0],fb[1],fb[2],fb[3]);
        g.u4f(PG,"covariance_parameters1",covShrink,covStretch,covClip,covBase);
        g.u4f(PG,"covariance_parameters2",covBlurred,covTrans,0.f,0.f);
        {const auto sh=footprint.shade61();g.u4f(PG,"u_day_shade",sh[0],sh[1],sh[2],sh[3]);} // P666
        g.u4f(PG,"force_reference_color_rgb",0,0,0,0);g.u4f(PG,"debug_options",1,0,0,0);
        g.u4f(PG,"noise_texture_scale_bias",nsb[0],nsb[1],nsb[2],nsb[3]);
        g.u4f(PG,"cov_range_rg_factors",-covRangeRg[0]/covRangeRg[1],1.f/covRangeRg[1],-covRangeRg[2]/covRangeRg[3],1.f/covRangeRg[3]);
        g.u2f(PG,"cov_range_b_factor",-covRangeB[0]/covRangeB[1],1.f/covRangeB[1]);
        g.u1i(PG,"u_cfa_sensor_type",quad?sensorType:0);g.u1i(PG,"u_cfa_pattern",cfa);
        g.u4f(PG,"u_gray_bl_spatial",blackSensor); // (sh035 has no gray output: a location -1 is ignored)
        g.u4f(A::pRef,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
        g.u1i(A::pUnblock,"u_tiles_x",ubw);g.u1i(A::pUnblock,"u_tiles_y",ubh);g.u4f(A::pUnblock,"u_noise_scale_bias",nsb[0],nsb[1],nsb[2],nsb[3]);
        // rejection (§5.6, exact; P488 fastMergeSport bit 1: the reference Sport's boost 10 and pixel difference threshold 0.35)
        g.u4f(A::pReject,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
        g.u4f(A::pReject,"frame_border_padded",fb[0],fb[1],fb[2],fb[3]);
        g.u4f(A::pReject,"flow_scale_offset",dso[0],dso[1],dso[2],dso[3]);
        g.u4f(A::pReject,"noise_texture_scale_bias",nsb[0],nsb[1],nsb[2],nsb[3]);
        // (color_difference_multiplier: the frame's, z > 0 marks a long donor)
        g.u2f(A::pReject,"unblocker_scale",1.f,1.f);
        g.u1f(A::pReject,"unblocker_reduction_threshold",0.003f*(2016.f/float(h2)));
        g.u1f(A::pReject,"extra_motion_robustness_boost",(tune.fastMergeSport&1)?10.f:6.f);g.u1f(A::pReject,"motion_robustness_boost_variance_threshold",25.f);
        g.u1f(A::pReject,"extra_motion_robustness_motion_threshold",1e-4f*(2016.f/float(h2)));
        g.u1i(A::pReject,"enable_photometric_correction",0);
        g.u2i(A::pRejFilter,"u_size",w2,h2);g.u1i(A::pRejFilter,"u_radius",3);g.u1f(A::pRejFilter,"u_sigma_spatial",1.5f);
        g.u1f(A::pRejFilter,"u_color_sigma",0.05f);g.u1f(A::pRejFilter,"u_color_sigma_boost",2.f);g.u1i(A::pRejFilter,"u_clip_rejection",0);
        g.u1f(A::pRejFilter,"u_pixel_difference_threshold",(tune.fastMergeSport&1)?0.35f:0.5f);g.u1f(A::pRejFilter,"u_clipped_threshold",0.f);
        g.u4f(A::pDilate,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));g.u1f(A::pDilate,"u_rejection_dilate_div",3.5f);
        g.u4f(A::pCyclops,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));g.u1f(A::pCyclops,"u_rejection_dilate_div",3.5f);
        g.u4f(A::pBlendEq,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
        if(flowPick){ // P601: the pick (kFastFlowPick), the surroundings' flow (kFastFlowBg), the rescue (kFastCyclopsRescue)
            g.u2i(A::pFlowBg,"u_grid",gx[0],gy[0]);g.u1i(A::pFlowBg,"u_stride",4); // (5 x 5 tiles at stride 4: +-128 HALF px)
            g.u4f(A::pFlowPick,"u_tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
            g.u4f(A::pFlowPick,"u_flow_so",fsx,fsy,fox,foy);g.u4f(A::pFlowPick,"u_noise_sb",nsb[0],nsb[1],nsb[2],nsb[3]);
            // a misfit above 0.25 of the rejection's distance; the best of the 4 x 4 tiles within 2 HALF px of the surroundings' flow
            // and that flow, when it halves the misfit
            g.u2i(A::pFlowPick,"u_grid",gx[0],gy[0]);g.u4f(A::pFlowPick,"u_geom",float(tiles[0]),float(pickB),0.25f,0.5f);
            g.u1i(A::pFlowPick,"u_reach",2);g.u1f(A::pFlowPick,"u_tol",2.f);
            g.u1f(A::pFlowPick,"u_bgpref",0.1f); // round 2: the surroundings' flow where it fits within 10 % of the field's misfit
            if(staticRescue){ // within the noise (2 .. 4), at the surroundings' flow (0.75 .. 1.5 HALF px), sh009's mass 0.15 .. 0.35
                g.u4f(A::pCyclopsRescue,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));g.u1f(A::pCyclopsRescue,"u_rejection_dilate_div",3.5f);
                g.u4f(A::pCyclopsRescue,"u_rescue",2.f,4.f,0.75f,1.5f);g.u2f(A::pCyclopsRescue,"u_rescue_mass",0.15f,0.35f);
            }
        }

        // merge (§5.7, exact; mvp identity: no zoom crop, OUT spans the frame at any sabre_scale)
        for(int p:{PMB,PMA}){
            g.u4f(p,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
            g.uMat4(p,"mvp_matrix_tex",identity);
            g.u4f(p,"kernel_blurring_params",4.5f,1.5f,sabre?1.f:0.f,0.f); // [UNCERTAIN] §11.7: z = 0 at sabre_scale 1 (P501: 1 above, the reference's)
            g.u1f(p,"accumulated_weight_scale",1.f);g.u1i(p,"u_cfa_pattern",cfa);
            g.u2f(p,"u_ca_k_r",0.f,0.f);g.u2f(p,"u_ca_k_b",0.f,0.f);
            // (gains / black_levels_times_gains / u_ev_scale: the frame's, set per merge)
            g.u4f(p,"frame_border_padded",fb[0],fb[1],fb[2],fb[3]);
            g.u4f(p,"cov_range_rg",covRangeRg[0],covRangeRg[1],covRangeRg[2],covRangeRg[3]);g.u2f(p,"cov_range_b",covRangeB[0],covRangeB[1]);
            g.u1i(p,"u_source_clip_guard",0);
            g.u4f(p,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
        }
        g.u4f(PMB,"flow_scale_offset",dso[0],dso[1],dso[2],dso[3]);
        g.u1f(PMB,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);g.u2f(PMB,"u_long_frame_params",0.f,1.f);

    }
    FastMergeGpu::check("uniforms");
    // ---- one frame: upload of the byte stream (RAW16 LE), unpack + hot pixels into `bayer`, guide (+ gray L0), pyramid L1..L4
    int rawSlot=0;double uploadMs=0,fenceMs=0,copyMs=0,stagedWaitMs=0;
    // The uploads (§3.5): the byte stream goes into storage buffers (a ring of 3), copied on the CPU's cores while the GL thread
    // submits the previous frame, and the unpack reads it from there (kFastUnpackFusedBuf: sh108 with rawByte() in place of its
    // texelFetch; the same bytes). Measured on the Adreno 750 at 12 MP: glTexSubImage2D from the mapped burst blocked the GL thread
    // ~22 ms per frame (the driver's CPU copy), a pixel unpack buffer cost the GPU ~5 ms per frame (its texture copy).
    // SCAM_FM_DIRECT_UPLOAD: the R8UI texture upload of the reference (A/B).
    const bool bufUpload=stripMode||!std::getenv("SCAM_FM_DIRECT_UPLOAD"); // (row strips upload rows into the ring)
    const size_t frameBytes=size_t(W)*H*2;
    GLuint ring[3]{};GLsync ringFence[3]{};
    if(bufUpload){
        for(GLuint& b:ring)b=g.writeBuffer(frameBytes); // P495: AHardwareBuffers where the driver takes them
        g.u1i(FastMergeGpu::pUnpackBuf,"u_width",w2);g.u1i(FastMergeGpu::pUnpackBuf,"u_height",h2);g.u1i(FastMergeGpu::pUnpackBuf,"u_cfa_pattern",cfa);
        g.u1i(FastMergeGpu::pUnpackBuf,"u_raw_format",32);g.u1i(FastMergeGpu::pUnpackBuf,"u_row_bytes",2*W);
        if(quad){ // Quad: the unpack only (strength 0); the hot pixels with the quad step and the QPC map in sh093
            using A=FastMergeGpu;
            g.u1i(A::pHotPixel,"u_width",w2);g.u1i(A::pHotPixel,"u_height",h2);g.u4f(A::pHotPixel,"u_black_level",blackColour);
            g.u1f(A::pHotPixel,"u_threshold_offset",0.01f*(input.white-blackMean));g.u1i(A::pHotPixel,"u_cfa_sensor_type",sensorType);
            g.u1i(A::pHotPixel,"u_cfa_pattern",cfa);g.u1f(A::pHotPixel,"u_suppression_strength",hotStrength);
            g.u1f(A::pHotPixel,"u_threshold_mult",3.f);g.u1i(A::pHotPixel,"u_has_qpc_map",0);
            g.u1i(A::pQpcEstimate,"u_width",w2);g.u1i(A::pQpcEstimate,"u_height",h2);g.u1i(A::pQpcEstimate,"u_grid_w",16);g.u1i(A::pQpcEstimate,"u_grid_h",12);
            g.u4f(A::pQpcEstimate,"u_black_level",blackColour);g.u1f(A::pQpcEstimate,"u_white_level",input.white);
            g.u1i(A::pQpcEstimate,"u_cfa_sensor_type",sensorType);g.u1i(A::pQpcEstimate,"u_cfa_pattern",cfa);
            g.u1i(A::pQpcSmooth,"u_grid_w",16);g.u1i(A::pQpcSmooth,"u_grid_h",12);
            g.u4f(A::pRawToGray,"uBlackLevels",blackColour);g.u1f(A::pRawToGray,"uWhiteLevel",input.white);
            g.u1i(A::pRawToGray,"uCfaSensorType",sensorType);g.u1i(A::pRawToGray,"uCfaPattern",cfa);
            g.u2f(PQ,"u_quad_kernel_scale",1.f,0.85f);g.u1f(PQ,"u_quad_color_reg_thresh",0.35f); // §5.7 defaults (Quad and Quad-Quad)
            if(sensorType==1)g.u1f(PQ,"u_quad_cone",std::clamp(tune.fastQuadCone,0.f,2.f)); // P596: the fallback cone along the edge
        }
        g.u4f(FastMergeGpu::pUnpackBuf,"u_black_level",blackColour);
        g.u1f(FastMergeGpu::pUnpackBuf,"u_threshold_offset",0.01f*(input.white-blackMean));
        g.u1f(FastMergeGpu::pUnpackBuf,"u_suppression_strength",quad?0.f:hotStrength);g.u1f(FastMergeGpu::pUnpackBuf,"u_threshold_mult",3.f);
    }
    struct FenceFree{GLsync* f;~FenceFree(){for(int i=0;i<3;++i)if(f[i])glDeleteSync(f[i]);}} fenceFree{ringFence};
    std::future<void> staged;int stagedSlot=-1;int ringNext=0;
    auto stage=[&](const uint16_t* r){ // the next buffer of the ring (its last unpack finished), mapped and filled beside the GL thread
        if(!bufUpload||!r)return;
        const int slot=ringNext;ringNext=(ringNext+1)%3;
        if(ringFence[slot]){
            const auto f0=Clock::now();
            GLenum w;{scam_progress::FenceWait fw;w=glClientWaitSync(ringFence[slot],GL_SYNC_FLUSH_COMMANDS_BIT,GLuint64(5000000000ULL));}
            if(w==GL_TIMEOUT_EXPIRED||w==GL_WAIT_FAILED)throw std::runtime_error("FAST MERGE GPU: upload buffer still in use");
            glDeleteSync(ringFence[slot]);ringFence[slot]=nullptr;
            fenceMs+=ms(f0,Clock::now());
        }
        void* m=g.mapWrite(ring[slot],0,frameBytes);
        if(!m)throw std::runtime_error("FAST MERGE GPU: upload buffer not mapped (GL error "+std::to_string(glGetError())+")");
        stagedSlot=slot;
        staged=std::async(std::launch::async,[m,r,frameBytes,H,&copyMs]{
            const auto c0=Clock::now();
            const size_t row=frameBytes/size_t(H);
            mergeRowBands(H,[&](int y0,int y1){std::memcpy(static_cast<char*>(m)+size_t(y0)*row,reinterpret_cast<const char*>(r)+size_t(y0)*row,size_t(y1-y0)*row);});
            copyMs+=std::chrono::duration<double,std::milli>(Clock::now()-c0).count();
        });
    };
    // the frame's gains (§5.7: wb x ev / (white - black), sensor order for the guide, colour order for the merges) and the gray gain
    // (16 x ev, 0x175fa0)
    auto frameGains=[&](float ev,std::array<float,4>& gs,std::array<float,4>& bs,std::array<float,4>& gc,std::array<float,4>& bc){
        for(int q=0;q<4;++q){gs[size_t(q)]=gainSensor[size_t(q)]*ev;bs[size_t(q)]=bltgSensor[size_t(q)]*ev;gc[size_t(q)]=gainColour[size_t(q)]*ev;bc[size_t(q)]=bltgColour[size_t(q)]*ev;}
    };
    auto mergeFrame=[&](int p,float ev){
        std::array<float,4> gs,bs,gc,bc;frameGains(ev,gs,bs,gc,bc);
        g.u4f(p,"gains",gc);g.u4f(p,"black_levels_times_gains",bc);g.u1f(p,"u_ev_scale",ev);
    };
    // Quad (§4.1): the base's QPC zonal gain from its unpacked quads (sh039 -> sh025, once), then every frame's hot pixels with the
    // quad step and the QPC map (sh093) from bayerTmp into `to`
    bool qpcMade=false;
    auto quadHotPixels=[&](GLuint to,int row0=0,int row1=-1){ // P497: rows [row0, row1) only (a row strip; -1 = to the end)
        using A=FastMergeGpu;
        // P566: the site response is in the unpack; the QPC stays on top of it over a whole frame (its residual zonal phase gains),
        // not in row strips (estimated there from the first strip's rows, the other zones unwritten)
        if(!qpcMade&&siteTex&&stripMode){g.u1i(A::pHotPixel,"u_has_qpc_map",0);qpcMade=true;}
        if(!qpcMade){
            g.tex(A::pQpcEstimate,"u_extracted_bayer",0,bayerTmp);glBindImageTexture(1,qpcRaw,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
            g.dispatch(A::pQpcEstimate,16,12);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            g.tex(A::pQpcSmooth,"u_qpc_raw_grid",0,qpcRaw);glBindImageTexture(1,qpcMap,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
            g.dispatch(A::pQpcSmooth,2,2);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            g.u1i(A::pHotPixel,"u_has_qpc_map",1);qpcMade=true;
        }
        g.tex(A::pHotPixel,"u_input_extracted",0,bayerTmp);g.tex(A::pHotPixel,"u_qpc_map",2,qpcMap);
        glBindImageTexture(1,to,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
        const int r1=row1<0?h2:row1;g.u1i(A::pHotPixel,"u_row0",row0);
        g.dispatch(A::pHotPixel,(w2+15)/16,(r1-row0+15)/16);
        g.u1i(A::pHotPixel,"u_row0",0);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    };
    auto frontEnd=[&](const uint16_t* r,GLuint bayer,GLuint fGuide,GLuint guide,GLuint* pyr,GLuint* fPyr,const uint16_t* next,
                      float ev=1.f,float Sc=-1.f,float Oc=-1.f,GLuint unpackTo=0){
        using A=FastMergeGpu;
        {
            std::array<float,4> gs,bs,gc,bc,cap;frameGains(ev,gs,bs,gc,bc);
            // P497: a brightened (short) frame's gray clips at the BASE's white, as the reference (its tracker then sees the base's
            // blown window where the base is blown; *ev let the window's outdoors through and the corners there failed)
            for(int q=0;q<4;++q)cap[size_t(q)]=grayCap[size_t(q)]*std::min(ev,1.f);
            g.u4f(PG,"gains",gs);g.u4f(PG,"black_levels_times_gains",bs);
            g.u1f(PG,"u_gray_gain",grayGain*ev);g.u4f(PG,"u_gray_cap_spatial",cap);
            noiseLutFor(Sc>0.f?Sc:S,Oc>=0.f?Oc:O,ev);
        }
        const GLuint unpackTarget=quad?bayerTmp:unpackTo?unpackTo:bayer;
        const auto u0=Clock::now();
        int unpackProg=A::pUnpack;
        if(bufUpload){
            if(stagedSlot<0)stage(r); // the first frame: staged right here
            {const auto w0=Clock::now();staged.get();stagedWaitMs+=ms(w0,Clock::now());}
            const int slot=stagedSlot;stagedSlot=-1;
            if(!g.unmapWrite(ring[slot]))throw std::runtime_error("FAST MERGE GPU: upload buffer lost");
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,ring[slot]);
            unpackProg=A::pUnpackBuf;
            stage(next); // the next frame's bytes while this one's passes are submitted
            uploadMs+=ms(u0,Clock::now());
            glBindImageTexture(1,unpackTarget,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
            if(siteTex)g.tex(unpackProg,"u_site_gains",2,siteTex);
            g.dispatch(unpackProg,(w2+15)/16,(h2+15)/16);
            ringFence[slot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0); // the slot is free again once this unpack ran
        } else {
            const GLuint rt=raw[rawSlot];rawSlot^=1; // two byte-stream textures: the next upload never waits for the previous unpack
            glPixelStorei(GL_UNPACK_ALIGNMENT,1);
            glBindTexture(GL_TEXTURE_2D,rt);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,2*W,H,GL_RED_INTEGER,GL_UNSIGNED_BYTE,r);
            uploadMs+=ms(u0,Clock::now());
            g.tex(A::pUnpack,"u_raw_tex",0,rt);
            glBindImageTexture(1,unpackTarget,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
            if(siteTex)g.tex(A::pUnpack,"u_site_gains",2,siteTex);
            g.dispatch(A::pUnpack,(w2+15)/16,(h2+15)/16);
        }
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        if(quad)quadHotPixels(unpackTo?unpackTo:bayer);
        if(unpackTo){ // a short frame (§5.1): short_highlight_recover (sh011) from the unpacked quads into its own Bayer
            g.tex(A::pShortRecover,"u_input_extracted",0,unpackTo);
            glBindImageTexture(1,bayer,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
            g.dispatch(A::pShortRecover,(w2+15)/16,(h2+15)/16);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        lap(0);
        g.tex(PG,"bayer_texture",0,bayer);g.tex(PG,"noise_estimates_texture",1,noiseLut);
        g.draw(PG,fGuide,w2,h2,quad?2:3);
        (void)guide;
        if(quad){ // gray L0 (§4.6): rawToGray's quad branch, uGain 16 x ev
            g.tex(A::pRawToGray,"uExtracted",0,bayer);g.u1f(A::pRawToGray,"uGain",grayGain*ev);
            g.draw(A::pRawToGray,fGuide==fGuideBase?fGrayB:fGrayC,w2,h2,1);
        }
        lap(8);
        for(int l=1;l<5;++l){
            const int p=l==1?A::pDown2:A::pDown4;
            g.tex(p,"uInput",0,pyr[l-1]);g.u2i(p,"uInputSize",lw[l-1],lh[l-1]);
            g.draw(p,fPyr[l],lw[l],lh[l],1);
        }
        lap(1);
    };
    using A=FastMergeGpu;
    // ---- the base frame (§4)
    // the loop's frame order: short frames first (their validity finishes the highlight mask before any normal merge), then the
    // normal donors, then the long frames ([UNCERTAIN] §11.12)
    struct LoopFrame { int f=0;int cls=0;float ev=1.f; }; // cls 0 normal, 1 short, 2 long
    std::vector<LoopFrame> loop;
    for(int f:shorts)loop.push_back({f,1,1.f/input.frames[size_t(f)].exposure});
    for(int f:donors)loop.push_back({f,0,1.f/input.frames[size_t(f)].exposure});
    for(int f:longs)loop.push_back({f,2,1.f/input.frames[size_t(f)].exposure});
    frontEnd(base.raw,bayerBase,fGuideBase,guideBase,pyrB,fPyrB,loop.empty()?nullptr:input.frames[size_t(loop[0].f)].raw);
    g.tex(A::pRef,"base_frame_guide_texture",0,guideBase);g.draw(A::pRef,fRef,w2,h2,2);
    g.tex(A::pUnblock,"u_guide_tex",0,guideBase);g.tex(A::pUnblock,"u_noise_lut",1,noiseLut); // [UNCERTAIN] its guide: the base guide
    glBindImageTexture(2,unblock,0,GL_FALSE,0,GL_WRITE_ONLY,GL_R32F);
    g.dispatch(A::pUnblock,(ubw+15)/16,(ubh+15)/16);
    for(int l=0;l<5;++l){ // gradient products of every base level (§4.7)
        glBindImageTexture(0,prod0[l],0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA32F);glBindImageTexture(1,prod1[l],0,GL_FALSE,0,GL_WRITE_ONLY,GL_R32F);
        g.tex(A::pProducts,"uReference",2,pyrB[l]);
        g.u2i(A::pProducts,"uImageSize",lw[l],lh[l]);g.u2i(A::pProducts,"uGridSize",gx[l],gy[l]);
        g.u1i(A::pProducts,"uTileStride",tiles[l]);g.u1i(A::pProducts,"uTileSize",tiles[l]);g.u1i(A::pProducts,"uNormalize",l!=0);
        g.dispatch(A::pProducts,gx[l],gy[l]);
    }
    g.tex(A::pGuide8,"uInput",0,pyrB[1]);g.draw(A::pGuide8,fG8Base,lw[1],lh[1],1);
    g.tex(A::pCorners,"u_guide_tex",0,g8Base);g.u2i(A::pCorners,"u_guide_size",lw[1],lh[1]);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bCorners);
    g.dispatch(A::pCorners,24,24);
    glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
    const std::array<float,4> halfSize{float(w2),float(h2),1.f/float(w2),1.f/float(h2)};
    if(bento){ // the base highlight mask (§4.5, 0x173dfc): sh060 -> sh087 -> sh016 -> copy into the final mask; validity accum = 0
        g.tex(A::pHlClipDilate,"u_base_bayer",0,bayerBase);g.u4f(A::pHlClipDilate,"u_tex_size",halfSize);
        g.u4f(A::pHlClipDilate,"u_black_level",blackSensor);g.u1f(A::pHlClipDilate,"u_white_level",input.white);
        g.draw(A::pHlClipDilate,fHlClip,w2,h2,1);
        g.tex(A::pHlGaussH,"u_input_mask",0,hlClip);g.u4f(A::pHlGaussH,"u_tex_size",halfSize);g.draw(A::pHlGaussH,fHlBlur,w2,h2,1);
        g.tex(A::pHlGaussV,"u_input_mask",0,hlBlur);g.u4f(A::pHlGaussV,"u_tex_size",halfSize);g.draw(A::pHlGaussV,fHlBase,w2,h2,1);
        g.tex(A::pPass,"u_tex",0,hlBase);g.draw(A::pPass,fHlFinal,w2,h2,1);
        const float one[4]={1,1,1,1};glBindFramebuffer(GL_FRAMEBUFFER,fHlAccum);glClearBufferfv(GL_COLOR,0,one); // P497: MIN below
    }
    glFlush();
    FastMergeGpu::check("base frame");
    const auto baseSubmitted=Clock::now();auto firstDonorSubmitted=baseSubmitted;
    lap(6);
    // the safety gate's clip limit: 0.35 in the scheme (0x179164), 0.5 here - our ultrashort frames measure 43 % clipped inside the
    // mask on lamp2324 (the SCAM merge's own check 38.7 %), 0.35 kept every lamp clipped; SCAM_FM_SHORT_CLIP_GATE (replays, A/B)
    const float clipGate=std::getenv("SCAM_FM_SHORT_CLIP_GATE")?float(std::atof(std::getenv("SCAM_FM_SHORT_CLIP_GATE"))):0.5f;
    // P497 (owner, 2026-11-04, a ship's bridge, SCA_20261104_073312: windows blown although three short frames came): 0.5 and 40
    // quads in place of the scheme's 0.65 / 15 - our short frames are darker (1/8), so fewer corners track (166 of 273), and the
    // base sits early in the ring, so a handheld drift reaches them (10.9 quads at the centre, the long frames after them 11);
    // the validation against the base inside the highlight mask (motion 0.54 % there) still rejects a misplaced one
    const float shortMinInliers=std::getenv("SCAM_FM_SHORT_INLIERS")?float(std::atof(std::getenv("SCAM_FM_SHORT_INLIERS"))):0.5f;
    const float shortMaxShift=std::getenv("SCAM_FM_SHORT_SHIFT")?float(std::atof(std::getenv("SCAM_FM_SHORT_SHIFT"))):40.f;
    // ---- the per-frame loop (§5)
    int validShorts=0,shortsDone=0;std::string shortLog;
    // P497: the reach (OUT px) of the output pass's hole fill (kFastOutput): 24 covers a near wire's refused copy (~16 px + rims)
    const int holeFill=std::getenv("SCAM_FM_HOLE_FILL")?std::atoi(std::getenv("SCAM_FM_HOLE_FILL")):24;
    // P501: the reach in OUT px (the same sensor distance at any sabre_scale); the Quad de-grid / anti-moire step their sensor
    // offsets by the scale (u_step)
    const int outFill=int(std::lround(double(holeFill)*double(OW)/double(W)));
    const float moire=quad&&!std::getenv("SCAM_FM_NO_MOIRE")?1.f:0.f;
    const bool quadLift=quad&&!std::getenv("SCAM_FM_NO_QUAD_LIFT"); // P506: the Quad lattice round recovered lights notched
    double longWeightSum=0;std::string longLog;
    const float sfx=float(w2)/float(tiles[0]*gx[0]),sfy=float(h2)/float(tiles[0]*gy[0]); // flow_scale_offset of a short frame's uv flow
    // the highlight mask's validity finished (0x175204..0x175654): when the first frame after the short frames comes (or after
    // the loop): the accumulated validity cleaned (sh066, no quarantine) and applied to the base mask (sh042) -> final mask; no
    // valid short frame: the final mask cleared to 0 (the short frames are then not merged)
    bool hlFinished=!bento;
    auto finishHighlight=[&]{
        if(hlFinished)return;
        hlFinished=true;
        if(validShorts>0){
            g.tex(A::pHlCleanValid,"u_valid_tex",0,hlAccum);g.tex(A::pHlCleanValid,"u_quarantine_tex",1,quar[0]);
            g.u1i(A::pHlCleanValid,"u_use_quarantine",0);g.u4f(A::pHlCleanValid,"u_tex_size",halfSize);
            g.draw(A::pHlCleanValid,fHlClean,w2,h2,1);
            g.tex(A::pHlApplyValidity,"u_base_mask",0,hlBase);g.tex(A::pHlApplyValidity,"u_valid_accum",1,hlClean);
            g.u4f(A::pHlApplyValidity,"u_tex_size",halfSize);g.u1f(A::pHlApplyValidity,"u_scale_factor",1.f);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bStats);
            g.draw(A::pHlApplyValidity,fHlFinal,w2,h2,1);
            report("HYBRID FAST MERGE HIGHLIGHT: accumulated validity of "+std::to_string(validShorts)+" valid short frame(s) applied to the highlight mask"+shortLog);
        } else {
            const float z[4]={0,0,0,0};glBindFramebuffer(GL_FRAMEBUFFER,fHlFinal);glClearBufferfv(GL_COLOR,0,z);
            report("HYBRID FAST MERGE HIGHLIGHT: every short frame rejected, highlight mask cleared"+shortLog);
        }
    };
    // the tile alignment (§5.4) of a donor against the base from its homography seed (the result in alM[0], the sparse pass's input set)
    auto alignTiles=[&]{
    // tile alignment per level, coarsest first (§5.4)
    for(int l=4;l>=0;--l){
        if(l==4){ // seed: the homography's flow at the tile centres (zero flow without a valid homography)
            glBindImageTexture(0,alA[4],0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA32F);
            g.u2i(A::pFill,"u_grid_size",gx[4],gy[4]);g.u1i(A::pFill,"u_tile_stride",tiles[4]);g.u2f(A::pFill,"u_bayer_size",float(w2),float(h2));
            g.u1i(A::pFill,"u_is_short_frame",0);g.u1f(A::pFill,"u_scale_to_bayer",lscale[4]);
            g.dispatch(A::pFill,(gx[4]+15)/16,(gy[4]+15)/16);
        } else { // seed: the coarser level's alignment upsampled (best of three candidates by SAD)
            glBindImageTexture(0,alA[l],0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA32F);
            g.tex(A::pUpsample,"uReference",1,pyrB[l]);g.tex(A::pUpsample,"uCurrent",2,pyrC[l]);g.tex(A::pUpsample,"uInitialAlignment",3,alM[l+1]);
            g.u2i(A::pUpsample,"uImageSize",lw[l],lh[l]);g.u2i(A::pUpsample,"uTargetGridSize",gx[l],gy[l]);g.u2i(A::pUpsample,"uInitialGridSize",gx[l+1],gy[l+1]);
            g.u1i(A::pUpsample,"uInitialGridMin",1);g.u1i(A::pUpsample,"uTargetGridMin",1); // [UNCERTAIN] the (tile + 1) origin of sh091 / sh112
            g.u1i(A::pUpsample,"uInitialTileStride",tiles[l+1]);g.u1i(A::pUpsample,"uTargetTileStride",tiles[l]);g.u1i(A::pUpsample,"uTargetTileSize",tiles[l]);
            g.u1f(A::pUpsample,"uInitialScale",lscale[l+1]/lscale[l]);
            g.dispatch(A::pUpsample,gx[l],gy[l]);
        }
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        // [UNCERTAIN] §11.6: two Gauss-Newton steps, a third except at the finest level
        g.tex(A::pLk,"uReference",1,pyrB[l]);g.tex(A::pLk,"uCurrent",2,pyrC[l]);g.tex(A::pLk,"uProducts0",3,prod0[l]);g.tex(A::pLk,"uProducts1",4,prod1[l]);
        g.u2i(A::pLk,"uImageSize",lw[l],lh[l]);g.u2i(A::pLk,"uGridSize",gx[l],gy[l]);g.u1i(A::pLk,"uTileStride",tiles[l]);g.u1i(A::pLk,"uTileSize",tiles[l]);
        g.u1i(A::pLk,"uNormalize",l!=0);g.u1i(A::pLk,"uHasInitialAlignment",1);
        const int steps=l==0?2:3;GLuint in=alA[l],outT=alB[l];
        for(int st=0;st<steps;++st){
            g.tex(A::pLk,"uInitialAlignment",5,in);
            glBindImageTexture(0,outT,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA32F);
            g.dispatch(A::pLk,gx[l],gy[l]);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            std::swap(in,outT);
        }
        g.tex(A::pMedian,"uAlignment",0,in);g.u2i(A::pMedian,"uGridSize",gx[l],gy[l]);
        g.draw(A::pMedian,fMedian[l],gx[l],gy[l],1);
    }
    g.tex(A::pSparse,"uAlignment",0,alM[0]);g.u2i(A::pSparse,"uGridSize",gx[0],gy[0]);
    g.u1f(A::pSparse,"uAlignmentScale",1.f);g.u2f(A::pSparse,"uFlowNormalizationSize",float(w2),float(h2));
    };
    const GLuint hlMaskTex=bento?hlFinal:zeroMask;
    // row strips: every frame's own flow (L0 grid) and rejection map (frame weight, blend_eq) for phase B, its long-frame weights
    std::vector<GLuint> kFlow(loop.size(),0),kFlowFbo(loop.size(),0),kRejW(loop.size(),0),kRejWFbo(loop.size(),0);
    std::vector<float> kWeightA(loop.size(),0.f),kCovMul(loop.size(),1.f);std::vector<char> kMerged(loop.size(),0);
    for(size_t k=0;k<loop.size();++k){
        const LoopFrame& lf=loop[k];const int f=lf.f;const HybridFrame& fr=input.frames[size_t(f)];
        const bool isShort=lf.cls==1,isLong=lf.cls==2;
        if(!isShort)finishHighlight();
        const uint16_t* next=k+1<loop.size()?input.frames[size_t(loop[k+1].f)].raw:nullptr;
        ShortFrame sf;
        const size_t slot=pendingMerges.size(); // P484: this frame's textures (slot 0 without a batch; short frames come first)
        if(isShort){
            sf.f=f;sf.ev=lf.ev;sf.bayer=g.texture(GL_RGBA16F,w2,h2,false);sf.guide=g.texture(GL_RGBA16F,w2,h2,true);sf.cov=g.texture(GL_RGB10_A2,w2,h2,true);
            sf.flow=g.texture(GL_RGBA32F,gx[0],gy[0],lin32);sf.fGuide=g.fbo({sf.guide,sf.cov,pyrC[0]});
            g.u1i(A::pShortRecover,"u_width",w2);g.u1i(A::pShortRecover,"u_height",h2);g.u1i(A::pShortRecover,"u_cfa_pattern",cfa);
            g.u4f(A::pShortRecover,"u_black_level",blackSensor);g.u1f(A::pShortRecover,"u_white_level",input.white);
            g.u4f(A::pShortRecover,"u_wb_gains",wb[0],wb[1],wb[2],1.f);
            lutFrame=&fr;frontEnd(fr.raw,sf.bayer,sf.fGuide,sf.guide,pyrC,fPyrC,next,lf.ev,fr.slope,fr.offset,bayerCur);
        } else {lutFrame=&fr;frontEnd(fr.raw,slotBayer[slot],slotGuideFbo[slot],guideCur,pyrC,fPyrC,next,lf.ev,fr.slope,fr.offset);}
        // global homography (§5.3): KLT of the base corners into the frame's 8-bit L1 guide, RANSAC on the GPU
        g.tex(A::pGuide8,"uInput",0,pyrC[1]);g.draw(A::pGuide8,fG8Cur,lw[1],lh[1],1);
        // P497: frames after the press (short, long) seed the KLT with a coarse translation of the 1/8 guides (+-8 px there = 64
        // quads); the frames before it (the ring's, close in time) track from zero as before
        const int coarseRange=(isShort||isLong)&&!std::getenv("SCAM_FM_NO_COARSE")?8:0;
        if(coarseRange>0){
            g.tex(A::pCoarseShift,"u_ref",0,pyrB[2]);g.tex(A::pCoarseShift,"u_cur",1,pyrC[2]);
            g.u2i(A::pCoarseShift,"u_size",lw[2],lh[2]);g.u1i(A::pCoarseShift,"u_range",coarseRange);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bCoarse);
            g.dispatch(A::pCoarseShift,((2*coarseRange+1)*(2*coarseRange+1)+63)/64,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
        g.u1i(A::pKlt,"u_coarse_range",coarseRange);g.u1f(A::pKlt,"u_coarse_scale",lscale[2]/lscale[1]);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,bCoarse);
        g.tex(A::pKlt,"u_ref_tex",0,g8Base);g.tex(A::pKlt,"u_cur_tex",1,g8Cur);g.u2i(A::pKlt,"u_guide_size",lw[1],lh[1]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bCorners);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,bTracks);
        g.dispatch(A::pKlt,(576+63)/64,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        g.u1i(A::pRansac,"u_frame_idx",f);g.u1f(A::pRansac,"u_max_reproj_error",2.f);g.u2i(A::pRansac,"u_guide_size",lw[1],lh[1]);g.u1i(A::pRansac,"u_is_short_frame",isShort?1:0);g.u1f(A::pRansac,"u_short_min_inliers",shortMinInliers);g.u1f(A::pRansac,"u_short_max_shift",shortMaxShift);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER,2,bResult,GLintptr(resStride*size_t(k+1)),GLsizeiptr(64));
        g.dispatch(A::pRansac,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        if(coarseRange>0){ // P497: tracked again from zero into the spare slot; the better homography stays in the frame's slot
            const GLintptr spare=GLintptr(resStride*size_t(loopFrames+1));
            g.u1i(A::pKlt,"u_coarse_range",0);
            g.tex(A::pKlt,"u_ref_tex",0,g8Base);g.tex(A::pKlt,"u_cur_tex",1,g8Cur);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bCorners);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,bTracks);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,bCoarse);
            g.dispatch(A::pKlt,(576+63)/64,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER,2,bResult,spare,GLsizeiptr(64));
            g.dispatch(A::pRansac,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER,2,bResult,GLintptr(resStride*size_t(k+1)),GLsizeiptr(64));
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER,3,bResult,spare,GLsizeiptr(64));
            g.dispatch(A::pHomoPick,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
        lap(2);
        if(isShort){
            // §5.3 / §5.8: the short frame's flow = the homography's, uv at the L0 tile centres (sh094 short branch: tile offset 0.5),
            // [UNCERTAIN] used as the flow itself (no tile LK: the branch writes uv flow, not a level flow). P497: the tile LK from
            // this seed did not move it at a near wire 16 quads off (SCA_20261104_073400) - the validity below refuses such a copy
            glBindImageTexture(0,sf.flow,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA32F);
            g.u2i(A::pFill,"u_grid_size",gx[0],gy[0]);g.u1i(A::pFill,"u_tile_stride",tiles[0]);g.u2f(A::pFill,"u_bayer_size",float(w2),float(h2));
            g.u1i(A::pFill,"u_is_short_frame",1);g.u1f(A::pFill,"u_scale_to_bayer",1.f);
            g.dispatch(A::pFill,(gx[0]+15)/16,(gy[0]+15)/16);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            // validation (sh058, 0x178e00): u_ev_scale = the exposure factor, u_err_thresh 0.8 (default of 0x16d1a0)
            g.tex(A::pHlValidate,"u_input_mask",0,hlBase);g.tex(A::pHlValidate,"u_base_bayer",1,bayerBase);g.tex(A::pHlValidate,"u_short_bayer",2,sf.bayer);
            g.tex(A::pHlValidate,"u_flow_tex",3,sf.flow);g.tex(A::pHlValidate,"u_prev_valid_accum",4,hlAccum);
            g.u4f(A::pHlValidate,"u_flow_scale_offset",sfx,sfy,0.f,0.f);g.u4f(A::pHlValidate,"u_black_level",blackSensor);
            g.u1f(A::pHlValidate,"u_white_level",input.white);g.u1f(A::pHlValidate,"u_ev_scale",lf.ev);
            g.u1i(A::pHlValidate,"u_is_first_short",shortsDone==0);g.u1f(A::pHlValidate,"u_err_thresh",0.8f);g.u3f(A::pHlValidate,"u_wb",wb[0],wb[1],wb[2]);
            g.draw(A::pHlValidate,fHlValid,w2,h2,1);
            // quarantine blocks (sh100) and the safety gate (sh018, sums read back): rejected when the homography failed (inliers
            // < 65 % or a shift > 15 px), motion (invalid / mask) > 0.35 or clip (clipped / mask) > 0.35; then every short frame is off
            g.tex(A::pQuarDown,"u_valid_tex",0,hlValid);g.u4f(A::pQuarDown,"u_src_tex_size",halfSize);
            g.draw(A::pQuarDown,fQuar[0],qw,qh,1);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
            g.tex(A::pQuarReduce,"u_quarantine_tex",0,quar[0]);g.u2i(A::pQuarReduce,"u_size",qw,qh);
            g.u1f(A::pQuarReduce,"u_safety_gate_motion_thresh",0.35f);g.u1i(A::pQuarReduce,"u_reset_reject",shortsDone==0);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bStats);
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER,1,bResult,GLintptr(resStride*size_t(k+1)),GLsizeiptr(64));
            g.dispatch(A::pQuarReduce,1,1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            float st[4]{};
            g.readBuffer(bStats,16,[&](const void* m){std::memcpy(st,m,16);});
            ++shortsDone;
            const float motion=st[2]>0.01f?st[0]/st[2]:0.f,clip=st[2]>0.01f?st[1]/st[2]:0.f;
            char line[200];
            if(!(st[3]>0.5f)){
                std::snprintf(line,sizeof(line),"; frame %d rejected (homography: inliers < 65 %% or motion > 15 px)",f);shortLog+=line;
            } else if(st[2]>0.01f&&(motion>0.35f||clip>clipGate)){
                std::snprintf(line,sizeof(line),"; frame %d rejected by the safety gate (motion %.2f %%, clip %.2f %%, mask cells %.1f)",f,100.f*motion,100.f*clip,st[2]);
                shortLog+=line;
            } else {
                std::snprintf(line,sizeof(line),"; frame %d passed (motion %.2f %%, clip %.2f %%, mask cells %.1f)",f,100.f*motion,100.f*clip,st[2]);shortLog+=line;
                // quarantine (0x179370..0x179820): cluster seed (mode 2, r 1), 20 geodesic dilations (mode 0, r 2), halo (mode 1, r 2)
                const std::array<float,4> qs{float(qw),float(qh),1.f/float(qw),1.f/float(qh)};
                auto qpass=[&](int mode,int radius){
                    g.tex(A::pQuarDilateH,"u_input_tex",0,quar[0]);g.u4f(A::pQuarDilateH,"u_tex_size",qs);g.u1i(A::pQuarDilateH,"u_radius",radius);g.u1i(A::pQuarDilateH,"u_mode",mode);
                    g.draw(A::pQuarDilateH,fQuar[1],qw,qh,1);
                    g.tex(A::pQuarDilateV,"u_input_tex",0,quar[1]);g.u4f(A::pQuarDilateV,"u_tex_size",qs);g.u1i(A::pQuarDilateV,"u_radius",radius);g.u1i(A::pQuarDilateV,"u_mode",mode);
                    g.draw(A::pQuarDilateV,fQuar[0],qw,qh,1);
                };
                qpass(2,1);for(int it=0;it<20;++it)qpass(0,2);qpass(1,2);
                // the clean validity with the quarantine (sh066), MIN-accumulated over the short frames (PASS, GL_MIN since P497), and the
                // final mask so far (sh042)
                g.tex(A::pHlCleanValid,"u_valid_tex",0,hlValid);g.tex(A::pHlCleanValid,"u_quarantine_tex",1,quar[0]);
                g.u1i(A::pHlCleanValid,"u_use_quarantine",1);g.u4f(A::pHlCleanValid,"u_tex_size",halfSize);
                g.draw(A::pHlCleanValid,fHlClean,w2,h2,1);
                // P497: MIN, not the scheme's MAX - every valid short frame is merged under the one final mask, so a pixel takes them
                // only where all are valid (MAX let the copies of a near wire through where one frame's copy sat off another's)
                g.tex(A::pPass,"u_tex",0,hlClean);
                glEnable(GL_BLEND);glBlendEquation(GL_MIN);glBlendFunc(GL_ONE,GL_ONE);
                g.draw(A::pPass,fHlAccum,w2,h2);
                glDisable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);
                sf.valid=true;++validShorts;
                shortFrames.push_back(sf);
            }
            // P497: a rejected short frame is left out alone; the scheme disabled every short frame after one failure (an LED-lit
            // room, scam_burst-20261020-193420: two good frames lost to the third's failed homography)
            glFlush();
            continue;
        }
        alignTiles();
        lap(3);
        GLuint flowK=slotFlow[slot],fFlowK=slotFlowFbo[slot],rejWK=slotRejW[slot],fRejWK=slotRejWFbo[slot];
        GLuint pickK=flowPick?slotPick[slot]:0,fPickK=flowPick?slotPickFbo[slot]:0;
        if(stripMode){
            if(flowPick){kFlow[k]=pickK=g.texture(GL_RGBA32F,pgx,pgy,lin32);fPickK=g.fbo({pickK,pickFit});kFlowFbo[k]=g.fbo({pickK});} // P601: phase B reads the blocks
            else {kFlow[k]=flowK=g.texture(GL_RGBA32F,gx[0],gy[0],lin32);kFlowFbo[k]=fFlowK=g.fbo({flowK});}
            kRejW[k]=rejWK=g.texture(GL_RG8,w2,h2,true);kRejWFbo[k]=fRejWK=g.fbo({rejWK});
            g.tex(A::pSparse,"uAlignment",0,alM[0]); // (making a texture binds it to the active unit: the sparse pass's input again)
        }
        g.draw(A::pSparse,fFlowK,gx[0],gy[0],1);
        if(flowPick){ // P601: the block grid from the tile field, the guides and the noise model the rejection reads next
            g.tex(A::pFlowBg,"u_flow",0,flowK);g.draw(A::pFlowBg,fFlowBg,gx[0],gy[0],1);
            g.tex(A::pFlowPick,"u_flow",0,flowK);g.tex(A::pFlowPick,"u_base",1,refColor);g.tex(A::pFlowPick,"u_cur",2,guideCur);g.tex(A::pFlowPick,"u_noise",3,noiseLut);
            g.tex(A::pFlowPick,"u_bg",4,flowBg);
            g.draw(A::pFlowPick,fPickK,pgx,pgy,2);
            flowK=pickK;fFlowK=fPickK;
        }
        lap(12);
        // a long frame (§5.3 0x1761fc..0x176330, decoded): weight_A = 1 + smoothstep(0.7, 0.9, sharp) (min(r, 32) - 1) with
        // r = max(1, var_base(0.01) / var_long(0.01))^0.85, both noise models at the base level 0.01 in base units; cov_mul =
        // 1 / (1 + 0.41421 clamp(0.05 (weight_A - 1) - 0.45, 0, 1))^2 (no widening when the term is negative)
        float weightA=0.f,covMul=1.f;
        if(isLong){
            const float e=lf.ev;
            float varBase=S*0.01f+O,varLong=std::max((std::max(fr.slope,1e-9f)*0.01f+std::max(fr.offset,0.f)*e)*e,1e-12f);
            if(dcgZones){ // DCG: each model's zone at the level (the long frame's own level 0.01 / e)
                varBase=S*0.01f+zoneOffset(base,0.01f);
                varLong=std::max((std::max(fr.slope,1e-9f)*0.01f+zoneOffset(fr,0.01f/e)*e)*e,1e-12f);
            }
            const float r=std::pow(std::max(varBase/varLong,1.f),0.85f);
            const float sharp=longSharp[size_t(k-shorts.size()-donors.size())];
            const float t=std::clamp(sharp*5.f-3.5f,0.f,1.f),sm=t*t*(3.f-2.f*t);
            weightA=1.f+sm*(std::min(r,32.f)-1.f);
            const float c=0.05f*(weightA-1.f)-0.45f;
            const float sc=c<0.f?1.f:1.f+std::min(c,1.f)*0.41421356f;
            covMul=1.f/(sc*sc);
            kWeightA[k]=weightA;kCovMul[k]=covMul;
            longWeightSum+=weightA;
            char line[160];std::snprintf(line,sizeof(line)," %d:ratio %.2fx sharp %.2f weight_A %.2f cov_mul %.3f",f,1.f/e,sharp,weightA,covMul);longLog+=line;
        }
        // rejection chain (§5.6)
        g.u4f(A::pReject,"color_difference_multiplier",0.25f,0.f,lf.ev>=0.85f?0.f:std::sqrt(0.8f*lf.ev),0.f);
        g.tex(A::pReject,"noise_estimates_texture",0,noiseLut);g.tex(A::pReject,"base_frame_guide_texture",1,refColor);
        g.tex(A::pReject,"alt_frame_guide_texture",2,guideCur);g.tex(A::pReject,"alt_flow_texture",3,flowK);
        g.tex(A::pReject,"unblocker_texture",4,unblock);g.tex(A::pReject,"gain_texture",5,gainOne);g.tex(A::pReject,"offset_texture",6,offsetZero);
        g.draw(A::pReject,fRej,w2,h2,1);
        lap(9);
        // SCAM_FM_REJ_DUMP=<dir> (replays only, whole-frame merges): every donor's rejection stages at HALF to <dir>/rej_<k>.bin -
        // int32 'FMRJ', burst frame, w2, h2, 6, then six uint8 planes (raw rejection, raw pixel difference, filtered, dilated,
        // donor weight after cyclops, the weight the merge reads) and the flow it read (nx, ny: the L0 tiles, or P601's 4 x 4 HALF
        // blocks; then RGBA float)
        static const char* rejDump=std::getenv("SCAM_FM_REJ_DUMP");
        std::vector<uint8_t> dumpPlanes;
        auto dumpRead=[&](GLuint fbo,int ch){
            if(!rejDump||stripMode)return;
            std::vector<uint8_t> v(size_t(w2)*h2*4);
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glReadBuffer(GL_COLOR_ATTACHMENT0);glReadPixels(0,0,w2,h2,GL_RGBA,GL_UNSIGNED_BYTE,v.data());
            for(size_t i=0;i<size_t(w2)*h2;++i)dumpPlanes.push_back(v[4*i+size_t(ch)]);
        };
        dumpRead(fRej,0);dumpRead(fRej,1);
        g.tex(A::pRejFilter,"u_guide_luma",0,refLuma);g.tex(A::pRejFilter,"u_rejection",1,rejRaw);g.tex(A::pRejFilter,"u_pixel_difference",2,rejRaw);
        g.draw(A::pRejFilter,fRejF,w2,h2,1);
        dumpRead(fRejF,0);
        lap(10);
        g.tex(A::pDilate,"rejection_texture",0,rejF);g.draw(A::pDilate,fRejD,w2,h2,1);
        dumpRead(fRejD,0);
        if(staticRescue){ // P601: the chain's weight, raised back where the donor's block fits at the surroundings' flow
            g.tex(A::pCyclopsRescue,"rejection_texture",0,rejD);g.tex(A::pCyclopsRescue,"u_fit",1,pickFit);
            g.draw(A::pCyclopsRescue,fRejF,w2,h2,1);
        } else {g.tex(A::pCyclops,"rejection_texture",0,rejD);g.draw(A::pCyclops,fRejF,w2,h2,1);}
        dumpRead(fRejF,0);
        g.tex(A::pBlendEq,"u_donor_weight",0,rejF);g.draw(A::pBlendEq,fRejWK,w2,h2,1);
        dumpRead(fRejWK,0);
        if(rejDump&&!stripMode){
            const int nx=flowPick?pgx:gx[0],ny=flowPick?pgy:gy[0];
            std::vector<float> fl(size_t(nx)*ny*4);
            glBindFramebuffer(GL_FRAMEBUFFER,fFlowK);glReadBuffer(GL_COLOR_ATTACHMENT0);glReadPixels(0,0,nx,ny,GL_RGBA,GL_FLOAT,fl.data());
            std::ofstream o(std::string(rejDump)+"/rej_"+std::to_string(k)+".bin",std::ios::binary);
            const int32_t hd[5]={0x4a524d46,int32_t(f),w2,h2,6},fg[2]={nx,ny};
            o.write(reinterpret_cast<const char*>(hd),sizeof(hd));o.write(reinterpret_cast<const char*>(dumpPlanes.data()),std::streamsize(dumpPlanes.size()));
            o.write(reinterpret_cast<const char*>(fg),sizeof(fg));o.write(reinterpret_cast<const char*>(fl.data()),std::streamsize(fl.size()*4));
            if(flowPick){ // P601: the blocks' fit within the noise and distance to the surroundings' flow (o_fit), RGBA float
                glReadBuffer(GL_COLOR_ATTACHMENT1);glReadPixels(0,0,nx,ny,GL_RGBA,GL_FLOAT,fl.data());glReadBuffer(GL_COLOR_ATTACHMENT0);
                o.write(reinterpret_cast<const char*>(fl.data()),std::streamsize(fl.size()*4));
            }
        }
        lap(11);
        // [NOT DONE] the long frame's pedestal and motion scope (§5.5): the scope is 1 everywhere
        g.u1f(A::pAccum,"u_long_weight_A",weightA);g.u1f(A::pAccum,"u_ev_scale",lf.ev);
        g.tex(A::pAccum,"u_rejection",0,rejWK);g.tex(A::pAccum,"u_base_guide",1,refColor);g.tex(A::pAccum,"u_long_scope",2,oneScope);
        glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE);
        g.draw(A::pAccum,fAccW,w2,h2);
        glDisable(GL_BLEND);
        lap(4);
        kMerged[k]=1;
        if(stripMode){glFlush();if(k==shorts.size()){FastMergeGpu::check("first donor");firstDonorSubmitted=Clock::now();}continue;} // merged in phase B
        // additive merge into the accumulators at OUT (§5.7): sh002 for a normal donor, MERGE_A for a long frame
        const int mp=isLong?PMA:PMB;
        const GLuint mBayer=slotBayer[slot],mCov=slotCov[slot];const float mEv=lf.ev; // (flowK, rejWK: the slot's)
        pendingMerges.push_back([&,k,isLong,mp,mBayer,mCov,flowK,rejWK,mEv,weightA,covMul]{
            mergeFrame(mp,mEv);
            g.u4f(mp,"frame_info",float(k+1),0.f,float(N),1.f);
            if(quad&&!isLong){ // (sh051 serves every kind: the normal donor's values again)
                g.u4f(mp,"flow_scale_offset",dso[0],dso[1],dso[2],dso[3]);g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);
                g.u2f(mp,"u_long_frame_params",0.f,1.f);g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            if(isLong){
                g.u4f(mp,"flow_scale_offset",dso[0],dso[1],dso[2],dso[3]);
                g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.88f);g.u2f(mp,"u_long_frame_params",weightA,covMul);
                g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            g.tex(mp,"bayer_texture",0,mBayer);g.tex(mp,"flow_texture",1,flowK);g.tex(mp,"kernel_covariance_texture",2,mCov);
            g.tex(mp,"rejection_texture",3,rejWK);g.tex(mp,"base_frame_guide_texture",4,refColor);g.tex(mp,"accumulated_frame_weights",5,accW);
            g.tex(mp,"highlight_mask_texture",6,hlMaskTex);g.tex(mp,"u_long_scope_tex",7,oneScope);
            glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE);
            g.draw(mp,fMerge,OW,OH);
            glDisable(GL_BLEND);
        });
        // P484: the batch's merges back to back once it is full (then a flush); else this frame's passes are flushed now
        if(pendingMerges.size()>=size_t(mergeBatch))flushMerges();else glFlush();
        lap(5);
        if(k==shorts.size()){FastMergeGpu::check("first donor");firstDonorSubmitted=Clock::now();}
    }
    flushMerges(); // P484: the last batch
    finishHighlight();
    if(!longs.empty())report("HYBRID FAST MERGE LONG:"+longLog+"; weight_sum="+std::to_string(longWeightSum)+" (no pedestal / motion scope)");
    std::vector<float> out;double waitMs=0;bool streamed=false;std::string outNote;GLuint bEff=0,bHlMask=0;
    Clock::time_point submitted;
    const auto& halvesHook=hybridFinalHalvesHook();
    if(!stripMode){
        // the short frames' merge (0x17a5c4..0x17a690): MERGE_A with the short frame's Bayer, its uv flow, the constant rejection and
        // its own covariance, highlight_params (1, factor, 1, count): only inside the highlight mask
        if(validShorts>0)for(const ShortFrame& q:shortFrames){
            const int mp=PMA;
            mergeFrame(mp,q.ev);
            g.u4f(mp,"frame_info",0.f,0.f,float(N),1.f);g.u4f(mp,"flow_scale_offset",sfx,sfy,0.f,0.f);
            g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);g.u2f(mp,"u_long_frame_params",0.f,1.f);
            g.u4f(mp,"highlight_params",1.f,q.ev,1.f,float(shortFrames.size()));
            g.tex(mp,"bayer_texture",0,q.bayer);g.tex(mp,"flow_texture",1,q.flow);g.tex(mp,"kernel_covariance_texture",2,q.cov);
            g.tex(mp,"rejection_texture",3,constRej);g.tex(mp,"base_frame_guide_texture",4,refColor);g.tex(mp,"accumulated_frame_weights",5,accW);
            g.tex(mp,"highlight_mask_texture",6,hlMaskTex);g.tex(mp,"u_long_scope_tex",7,oneScope);
            glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE);
            g.draw(mp,fMerge,OW,OH);
            glDisable(GL_BLEND);
            glFlush();
        }
        // ---- after the loop (§6): the base self-merge (MERGE_A: zero flow, constant rejection, the base covariance; hole fill by the
        // donors' accumulated weight)
        mergeFrame(PMA,1.f);
        g.u4f(PMA,"frame_info",0.f,0.f,float(N),0.f);g.u4f(PMA,"flow_scale_offset",1.f,1.f,0.f,0.f);
        g.u1f(PMA,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);g.u2f(PMA,"u_long_frame_params",0.f,1.f);
        g.u4f(PMA,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shortFrames.size()));
        g.tex(PMA,"bayer_texture",0,bayerBase);g.tex(PMA,"flow_texture",1,zeroFlow);g.tex(PMA,"kernel_covariance_texture",2,covBaseT);
        g.tex(PMA,"rejection_texture",3,constRej);g.tex(PMA,"base_frame_guide_texture",4,refColor);g.tex(PMA,"accumulated_frame_weights",5,accW);
        g.tex(PMA,"highlight_mask_texture",6,hlMaskTex);g.tex(PMA,"u_long_scope_tex",7,oneScope);
        glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE);
        g.draw(PMA,fMerge,OW,OH);
        glDisable(GL_BLEND);
        glFlush();
        FastMergeGpu::check("base self-merge");
        lap(6);
        // ---- normalisation and the readback buffers, in bands of rows (each buffer read from offset 0)
        const int bandRows=512;
        const int bands=(OH+bandRows-1)/bandRows;
        std::vector<GLuint> bRgb(static_cast<size_t>(bands)),bFlags(static_cast<size_t>(bands));
        bEff=g.readBuffer(size_t(w2)*h2*4);
        g.tex(A::pOutput,"u_accum_color_r",0,acc0);g.tex(A::pOutput,"u_accum_weights_gb",1,acc1);g.tex(A::pOutput,"u_frame_weights",2,accW);
        g.u2i(A::pOutput,"u_size",OW,OH);g.u2i(A::pOutput,"u_half",w2,h2);
        g.u3f(A::pOutput,"u_inv_wb",1.f/wb[0],1.f/wb[1],1.f/wb[2]);g.u1f(A::pOutput,"u_clip",0.94f);g.u1f(A::pOutput,"u_clip_short",validShorts>0?clipShort():0.f);g.u1i(A::pOutput,"u_fill",validShorts>0?outFill:0);g.u1f(A::pOutput,"u_fill_light",std::clamp(tune.fastLightFill,0.f,1.f));g.u1f(A::pOutput,"u_quad_lift",quadLift&&validShorts>0?1.f:0.f);g.u1f(A::pOutput,"u_moire",moire);g.u1f(A::pOutput,"u_step",float(OW)/float(W));g.tex(A::pOutput,"u_hl_base",3,bento?hlBase:zeroMask);g.tex(A::pOutput,"u_hl_final",4,hlMaskTex);g.tex(A::pOutput,"u_base_bayer",5,bayerBase);g.u4f(A::pOutput,"u_black",blackSensor);g.u1f(A::pOutput,"u_white",input.white);
        // P596: the de-grid's «flat» from the merged noise, its line notch where few frames merged (Quad; Tetra as before)
        g.u4f(A::pOutput,"u_flat_noise",S,O,quad&&sensorType==1?std::clamp(tune.fastQuadFlatNoise,0.f,8.f):0.f,quad&&sensorType==1?std::clamp(tune.fastQuadLineFrames,0.f,64.f):0.f);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,bEff);
        for(int b=0;b<bands;++b){
            const int y0=b*bandRows,rows=std::min(bandRows,OH-y0);
            bRgb[size_t(b)]=g.readBuffer(size_t(OW)*rows*6);bFlags[size_t(b)]=g.readBuffer(size_t(OW)*rows);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bRgb[size_t(b)]);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,bFlags[size_t(b)]);
            g.u1i(A::pOutput,"u_row0",y0);g.u1i(A::pOutput,"u_rows",rows);
            g.dispatch(A::pOutput,(OW/4+63)/64,rows);
        }
        // P480: the base highlight mask (0x530) for the app's dezipper pass, only with the highlight path on (none: no pass, no trailer)
        if(hlMaskOut&&bento){ // (P501: the sensor's HALF grid at any sabre_scale: the app's dezipper samples it in uv)
            g.hlMaskOutProgram();
            bHlMask=g.readBuffer(size_t(w2)*h2*2);
            g.tex(A::pHlMaskOut,"u_mask",0,hlBase);g.u2i(A::pHlMaskOut,"u_half",w2,h2);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bHlMask);
            g.dispatch(A::pHlMaskOut,(w2/2+63)/64,h2);
        }
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        lap(7);
        glFlush();
        FastMergeGpu::check("output");
        submitted=Clock::now();
        // ---- readback: the RGB halves into the worker's floats (rows on all cores), the flags, the effective frames
        // the result vector (the worker's contract: W x H x 3 floats) was allocated on a helper thread during the GPU work
        out=outAlloc.get();
        if(clipFlags)clipFlags->assign(size_t(OW)*OH,0);
        // the halves straight into the worker's output memfd (hybridFinalHalvesHook): the floats are then not filled - nothing reads
        // them (the worker writes the streamed halves and only the trailers); a band the stream does not take, or a merged DNG (made
        // from the floats), fills the floats from the same buffers
        streamed=streamWanted&&halvesHook;
        for(int b=0;b<bands&&streamed;++b){
            const int y0=b*bandRows,rows=std::min(bandRows,OH-y0);
            const auto t0=Clock::now();
            g.readBuffer(bRgb[size_t(b)],size_t(OW)*rows*6,[&](const void* m){
                if(b==0)waitMs=ms(t0,Clock::now());
                streamed=halvesHook(static_cast<const uint16_t*>(m),OW,OH,y0,y0+rows,out.data());
            });
        }
        if(streamed)streamed=halvesHook(nullptr,OW,OH,-1,-1,out.data());
        outNote=streamed?"halves streamed into the output":"floats";
        if(!streamed&&halvesHook)outNote+=" (the stream did not take the halves)";
        if(!streamed)ensureOut(out);
        for(int b=0;b<bands;++b){
            const int y0=b*bandRows,rows=std::min(bandRows,OH-y0);
            const auto t0=Clock::now();
            if(!streamed)g.readBuffer(bRgb[size_t(b)],size_t(OW)*rows*6,[&](const void* m){
                if(b==0&&waitMs==0)waitMs=ms(t0,Clock::now());
                const uint16_t* src=static_cast<const uint16_t*>(m);
                mergeRowBands(rows,[&](int r0,int r1){
                    const size_t a=size_t(r0)*OW*3,e=size_t(r1)*OW*3;
                    float* d=out.data()+size_t(y0)*OW*3;
    #if defined(__aarch64__)
                    const __fp16* s=reinterpret_cast<const __fp16*>(src);
                    size_t i=a;
                    for(;i+8<=e;i+=8){const float16x8_t v=vld1q_f16(reinterpret_cast<const float16_t*>(s+i));
                        vst1q_f32(d+i,vcvt_f32_f16(vget_low_f16(v)));vst1q_f32(d+i+4,vcvt_f32_f16(vget_high_f16(v)));}
                    for(;i<e;++i)d[i]=float(s[i]);
    #else
                    for(size_t i=a;i<e;++i)d[i]=hybridHalfToFloat(src[i]);
    #endif
                });
            });
            if(clipFlags)g.readBuffer(bFlags[size_t(b)],size_t(OW)*rows,[&](const void* m){std::memcpy(clipFlags->data()+size_t(y0)*OW,m,size_t(OW)*rows);});
        }
    // ---- phase B (row strips): see the strip decision above
    } else {
    bEff=g.readBuffer(size_t(w2)*h2*4);
    if(hlMaskOut&&bento){ // P480: as the whole-frame branch
        g.hlMaskOutProgram();
        bHlMask=g.readBuffer(size_t(w2)*h2*2);
        g.tex(A::pHlMaskOut,"u_mask",0,hlBase);g.u2i(A::pHlMaskOut,"u_half",w2,h2);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bHlMask);
        g.dispatch(A::pHlMaskOut,(w2/2+63)/64,h2);
    }
    // every merged frame's vertical flow (uv) on the L0 grid, read once: the rows of the frame a strip needs
    // (P601: a grid of its own - the donors' 4 x 4 HALF blocks of fastFlowPick, texel (i, j) at HALF (4 i, 4 j))
    struct KFlow { std::vector<float> fy;float off=1.5f;int nx=0,ny=0,tile=16; };
    std::vector<KFlow> kfy(loop.size());
    auto readFlow=[&](GLuint fbo,float off,KFlow& kf,int nx,int ny,int tile){
        std::vector<float> v(size_t(nx)*ny*4);
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glReadBuffer(GL_COLOR_ATTACHMENT0);
        glReadPixels(0,0,nx,ny,GL_RGBA,GL_FLOAT,v.data());
        kf.fy.resize(size_t(nx)*ny);for(size_t i=0;i<kf.fy.size();++i)kf.fy[i]=v[4*i+1];kf.off=off;kf.nx=nx;kf.ny=ny;kf.tile=tile;
    };
    for(size_t k=0;k<loop.size();++k)if(kMerged[k]){
        if(flowPick)readFlow(kFlowFbo[k],0.f,kfy[k],pgx,pgy,pickB);
        else readFlow(kFlowFbo[k],1.5f,kfy[k],gx[0],gy[0],tiles[0]);
    }
    std::vector<KFlow> sflowY(shortFrames.size());
    for(size_t q=0;q<shortFrames.size();++q)readFlow(g.fbo({shortFrames[q].flow}),0.5f,sflowY[q],gx[0],gy[0],tiles[0]);
    FastMergeGpu::check("flow readback");
    // HALF rows [wa, wb) of a frame that the strip's HALF rows [ca, cb) sample: the flow's range over the tiles near the strip, a
    // margin of 8 rows (the merge's 3 x 3 sites, bilinear covariance and flow, the guide's 3 x 3); within 4 rows of an edge the
    // mirrored samples reach the edge
    auto window=[&](const KFlow& kf,int ca,int cb,int& wa,int& wb){
        float lo=0.f,hi=0.f;bool any=false;
        // (the reach: the tiles within 2 L0 tiles of the strip, also on the block grid)
        for(int j=0;j<kf.ny;++j){
            const float cy=(float(j)+kf.off)*float(kf.tile);
            if(cy<float(ca-2*tiles[0])||cy>float(cb+2*tiles[0]))continue;
            for(int i=0;i<kf.nx;++i){const float d=kf.fy[size_t(j)*kf.nx+i]*float(h2);lo=any?std::min(lo,d):d;hi=any?std::max(hi,d):d;any=true;}
        }
        if(!any){lo=hi=0.f;for(float d:kf.fy){lo=std::min(lo,d*float(h2));hi=std::max(hi,d*float(h2));}}
        wa=std::max(0,ca+int(std::floor(lo))-8);wb=std::min(h2,cb+int(std::ceil(hi))+8);
        if(wa<4)wa=0;
        if(wb>h2-4)wb=h2;
    };
    // a frame's RAW rows for HALF rows [wa, wb): uploaded into the ring (at their place in the frame), unpacked (+1 quad row each
    // side: the hot-pixel test's neighbours), the guide (its covariance) on [wa, wb) under a scissor
    auto stripFrame=[&](const HybridFrame& fr,float ev,int wa,int wb,GLuint bayer,GLuint fGuide,GLuint unpackTo){
        const int qm=quad?(sensorType==2?8:4):1,qa=std::max(0,wa-qm),qb=std::min(h2,wb+qm);
        const int ra=std::max(0,2*qa-2),rb=std::min(H,2*qb+2);
        const auto u0=Clock::now();
        const int slot=ringNext;ringNext=(ringNext+1)%3;
        if(ringFence[slot]){
            const auto f0=Clock::now();
            GLenum w;{scam_progress::FenceWait fw;w=glClientWaitSync(ringFence[slot],GL_SYNC_FLUSH_COMMANDS_BIT,GLuint64(5000000000ULL));}
            if(w==GL_TIMEOUT_EXPIRED||w==GL_WAIT_FAILED)throw std::runtime_error("FAST MERGE GPU: upload buffer still in use");
            glDeleteSync(ringFence[slot]);ringFence[slot]=nullptr;
            fenceMs+=ms(f0,Clock::now());
        }
        const size_t row=size_t(W)*2;
        void* m=g.mapWrite(ring[slot],size_t(ra)*row,size_t(rb-ra)*row);
        if(!m)throw std::runtime_error("FAST MERGE GPU: upload rows not mapped (GL error "+std::to_string(glGetError())+")");
        {
            const auto c0=Clock::now();
            const char* src=reinterpret_cast<const char*>(fr.raw)+size_t(ra)*row;
            mergeRowBands(rb-ra,[&](int y0,int y1){std::memcpy(static_cast<char*>(m)+size_t(y0)*row,src+size_t(y0)*row,size_t(y1-y0)*row);});
            copyMs+=ms(c0,Clock::now());
        }
        if(!g.unmapWrite(ring[slot]))throw std::runtime_error("FAST MERGE GPU: upload rows lost");
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,ring[slot]);
        uploadMs+=ms(u0,Clock::now());
        g.u1i(A::pUnpackBuf,"u_row0q",qa);
        glBindImageTexture(1,quad?bayerTmp:unpackTo?unpackTo:bayer,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
        if(siteTex)g.tex(A::pUnpackBuf,"u_site_gains",2,siteTex);
        g.dispatch(A::pUnpackBuf,(w2+15)/16,(qb-qa+15)/16);
        g.u1i(A::pUnpackBuf,"u_row0q",0);
        ringFence[slot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        // P497: over the unpacked rows only (50 MP Quad, 5 strips: the whole frame's passes per strip were most of phase B; the rows
        // outside were stale data nobody read)
        if(quad)quadHotPixels(unpackTo?unpackTo:bayer,qa,qb);
        if(unpackTo){ // a short frame: sh011 over its unpacked rows
            g.tex(A::pShortRecover,"u_input_extracted",0,unpackTo);
            glBindImageTexture(1,bayer,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
            g.u1i(A::pShortRecover,"u_row0",qa);
            g.dispatch(A::pShortRecover,(w2+15)/16,(qb-qa+15)/16);
            g.u1i(A::pShortRecover,"u_row0",0);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        if(profile)lap(13); // P567: phase B's upload + unpack + hot pixels (profile only: no extra flushes)
        std::array<float,4> gs,bs,gc,bc,cap;frameGains(ev,gs,bs,gc,bc);
        for(int q=0;q<4;++q)cap[size_t(q)]=grayCap[size_t(q)]*std::min(ev,1.f); // P497: as frontEnd
        g.u4f(PG,"gains",gs);g.u4f(PG,"black_levels_times_gains",bs);
        g.u1f(PG,"u_gray_gain",grayGain*ev);g.u4f(PG,"u_gray_cap_spatial",cap);
        lutFrame=&fr;noiseLutFor(fr.slope>0.f?fr.slope:S,fr.offset>=0.f?fr.offset:O,ev);
        g.tex(PG,"bayer_texture",0,bayer);g.tex(PG,"noise_estimates_texture",1,noiseLut);
        glEnable(GL_SCISSOR_TEST);glScissor(0,wa,w2,wb-wa);
        g.drawAt(PG,fGuide,0,0,w2,h2);
        glDisable(GL_SCISSOR_TEST);
        if(profile)lap(14); // P567: phase B's guide
    };
    // the accumulators of one strip, the readback of one strip (reused)
    const GLuint fStrip=fMerge;
    const GLuint bRgbS=g.readBuffer(size_t(OW)*stripRows*6),bFlagsS=g.readBuffer(size_t(OW)*stripRows);
    g.tex(A::pOutput,"u_accum_color_r",0,acc0);g.tex(A::pOutput,"u_accum_weights_gb",1,acc1);g.tex(A::pOutput,"u_frame_weights",2,accW);
    g.u2i(A::pOutput,"u_size",OW,OH);g.u2i(A::pOutput,"u_half",w2,h2);
    g.u3f(A::pOutput,"u_inv_wb",1.f/wb[0],1.f/wb[1],1.f/wb[2]);g.u1f(A::pOutput,"u_clip",0.94f);g.u1f(A::pOutput,"u_clip_short",validShorts>0?clipShort():0.f);g.u1i(A::pOutput,"u_fill",validShorts>0?outFill:0);g.u1f(A::pOutput,"u_fill_light",std::clamp(tune.fastLightFill,0.f,1.f));g.u1f(A::pOutput,"u_quad_lift",quadLift&&validShorts>0?1.f:0.f);g.u1f(A::pOutput,"u_moire",moire);g.u1f(A::pOutput,"u_step",float(OW)/float(W));g.tex(A::pOutput,"u_hl_base",3,bento?hlBase:zeroMask);g.tex(A::pOutput,"u_hl_final",4,hlMaskTex);g.tex(A::pOutput,"u_base_bayer",5,bayerBase);g.u4f(A::pOutput,"u_black",blackSensor);g.u1f(A::pOutput,"u_white",input.white);
    // P596: the de-grid's «flat» from the merged noise, its line notch where few frames merged (Quad; Tetra as before)
    g.u4f(A::pOutput,"u_flat_noise",S,O,quad&&sensorType==1?std::clamp(tune.fastQuadFlatNoise,0.f,8.f):0.f,quad&&sensorType==1?std::clamp(tune.fastQuadLineFrames,0.f,64.f):0.f);
    out=outAlloc.get();
    if(clipFlags)clipFlags->assign(size_t(OW)*OH,0);
    streamed=streamWanted&&halvesHook;bool streamTook=false;
    int strips=0;std::vector<int> winRows;
    for(int y0=0;y0<OH;y0+=stripRows,++strips){
        // the strip's HALF rows (P501: OUT rows y over OH -> y h2 / OH; at sabre_scale 1 y / 2 and (y1 + 1) / 2 as before)
        const int y1=std::min(OH,y0+stripRows),rows=y1-y0,ca=int(int64_t(y0)*h2/OH),cb=std::min<int>(h2,int((int64_t(y1)*h2+OH-1)/OH));
        {const float z[4]={0,0,0,0};glBindFramebuffer(GL_FRAMEBUFFER,fStrip);glClearBufferfv(GL_COLOR,0,z);glClearBufferfv(GL_COLOR,1,z);}
        auto mergeDraw=[&](int mp){
            glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE);
            g.drawAt(mp,fStrip,0,-y0,OW,OH);
            glDisable(GL_BLEND);
        };
        for(size_t k=0;k<loop.size();++k){ // the merges in the loop's order (the whole-frame branch's order of the sums)
            if(!kMerged[k])continue;
            const LoopFrame& lf=loop[k];const HybridFrame& fr=input.frames[size_t(lf.f)];const bool isLong=lf.cls==2;
            int wa=0,wb=h2;window(kfy[k],ca,cb,wa,wb);winRows.push_back(wb-wa);
            stripFrame(fr,lf.ev,wa,wb,bayerCur,fGuideCur,0);
            const int mp=isLong?PMA:PMB;
            mergeFrame(mp,lf.ev);
            g.u4f(mp,"frame_info",float(k+1),0.f,float(N),1.f);
            if(quad&&!isLong){ // (sh051 serves every kind: the normal donor's values again)
                g.u4f(mp,"flow_scale_offset",dso[0],dso[1],dso[2],dso[3]);g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);
                g.u2f(mp,"u_long_frame_params",0.f,1.f);g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            if(isLong){
                g.u4f(mp,"flow_scale_offset",dso[0],dso[1],dso[2],dso[3]);
                g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.88f);g.u2f(mp,"u_long_frame_params",kWeightA[k],kCovMul[k]);
                g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            g.tex(mp,"bayer_texture",0,bayerCur);g.tex(mp,"flow_texture",1,kFlow[k]);g.tex(mp,"kernel_covariance_texture",2,covCur);
            g.tex(mp,"rejection_texture",3,kRejW[k]);g.tex(mp,"base_frame_guide_texture",4,refColor);g.tex(mp,"accumulated_frame_weights",5,accW);
            g.tex(mp,"highlight_mask_texture",6,hlMaskTex);g.tex(mp,"u_long_scope_tex",7,oneScope);
            mergeDraw(mp);
            glFlush();
            if(profile)lap(15); // P567: phase B's merge
        }
        if(validShorts>0)for(size_t q=0;q<shortFrames.size();++q){ // their Bayer and covariance were kept from phase A
            const ShortFrame& sq=shortFrames[q];
            const int mp=PMA;
            mergeFrame(mp,sq.ev);
            g.u4f(mp,"frame_info",0.f,0.f,float(N),1.f);g.u4f(mp,"flow_scale_offset",sfx,sfy,0.f,0.f);
            g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);g.u2f(mp,"u_long_frame_params",0.f,1.f);
            g.u4f(mp,"highlight_params",1.f,sq.ev,1.f,float(shortFrames.size()));
            g.tex(mp,"bayer_texture",0,sq.bayer);g.tex(mp,"flow_texture",1,sq.flow);g.tex(mp,"kernel_covariance_texture",2,sq.cov);
            g.tex(mp,"rejection_texture",3,constRej);g.tex(mp,"base_frame_guide_texture",4,refColor);g.tex(mp,"accumulated_frame_weights",5,accW);
            g.tex(mp,"highlight_mask_texture",6,hlMaskTex);g.tex(mp,"u_long_scope_tex",7,oneScope);
            mergeDraw(mp);
        }
        mergeFrame(PMA,1.f);
        g.u4f(PMA,"frame_info",0.f,0.f,float(N),0.f);g.u4f(PMA,"flow_scale_offset",1.f,1.f,0.f,0.f);
        g.u1f(PMA,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);g.u2f(PMA,"u_long_frame_params",0.f,1.f);
        g.u4f(PMA,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shortFrames.size()));
        g.tex(PMA,"bayer_texture",0,bayerBase);g.tex(PMA,"flow_texture",1,zeroFlow);g.tex(PMA,"kernel_covariance_texture",2,covBaseT);
        g.tex(PMA,"rejection_texture",3,constRej);g.tex(PMA,"base_frame_guide_texture",4,refColor);g.tex(PMA,"accumulated_frame_weights",5,accW);
        g.tex(PMA,"highlight_mask_texture",6,hlMaskTex);g.tex(PMA,"u_long_scope_tex",7,oneScope);
        mergeDraw(PMA);
        // the strip's output pass and its readback
        g.tex(A::pOutput,"u_accum_color_r",0,acc0);g.tex(A::pOutput,"u_accum_weights_gb",1,acc1);g.tex(A::pOutput,"u_frame_weights",2,accW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bRgbS);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,bFlagsS);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,bEff);
        g.u1i(A::pOutput,"u_row0",y0);g.u1i(A::pOutput,"u_rows",rows);g.u1i(A::pOutput,"u_acc_row0",y0);
        g.dispatch(A::pOutput,(OW/4+63)/64,rows);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        FastMergeGpu::check("strip");
        const auto t0=Clock::now();
        g.readBuffer(bRgbS,size_t(OW)*rows*6,[&](const void* m){
            if(y0==0)waitMs=ms(t0,Clock::now());
            const uint16_t* src=static_cast<const uint16_t*>(m);
            if(streamed){streamed=halvesHook(src,OW,OH,y0,y1,out.data());if(streamed){streamTook=true;return;}}
            if(streamTook)throw std::runtime_error("FAST MERGE: the output stream refused a strip after taking the first ones");
            ensureOut(out);
            mergeRowBands(rows,[&](int r0,int r1){
                const size_t a=size_t(r0)*OW*3,e=size_t(r1)*OW*3;
                float* d=out.data()+size_t(y0)*OW*3;
#if defined(__aarch64__)
                const __fp16* s16=reinterpret_cast<const __fp16*>(src);
                size_t i=a;
                for(;i+8<=e;i+=8){const float16x8_t v=vld1q_f16(reinterpret_cast<const float16_t*>(s16+i));
                    vst1q_f32(d+i,vcvt_f32_f16(vget_low_f16(v)));vst1q_f32(d+i+4,vcvt_f32_f16(vget_high_f16(v)));}
                for(;i<e;++i)d[i]=float(s16[i]);
#else
                for(size_t i=a;i<e;++i)d[i]=hybridHalfToFloat(src[i]);
#endif
            });
        });
        if(clipFlags)g.readBuffer(bFlagsS,size_t(OW)*rows,[&](const void* m){std::memcpy(clipFlags->data()+size_t(y0)*OW,m,size_t(OW)*rows);});
    }
    if(streamed)streamed=halvesHook(nullptr,OW,OH,-1,-1,out.data());
    outNote=streamed?"halves streamed into the output":"floats";
    if(!streamed&&halvesHook)outNote+=" (the stream did not take the halves)";
    {
        long wsum=0;for(int r:winRows)wsum+=r;
        char line[300];
        std::snprintf(line,sizeof(line),"HYBRID FAST MERGE STRIPS: %d strips of %d rows (accumulators %s MB); frames' rows re-read per strip %.2f x the HALF height on average",
            strips,stripRows,hybridMB(uint64_t(OW)*stripRows*16).c_str(),winRows.empty()?0.0:double(wsum)/double(winRows.size())/double(h2)*double(strips));
        report(line);
    }
    submitted=Clock::now();
    }
    {
        const auto rss=hybridRssMB();
        report("HYBRID FAST MERGE MEMORY: GPU textures and buffers "+hybridMB(g.objBytes)+" MB (readback buffers in hardware buffers not counted), worker RSS "
            +std::to_string(rss[0])+" MB, peak "+std::to_string(rss[1])+" MB");
    }
    std::vector<float> eff;
    const int NL=1+int(loop.size());
    std::vector<int> hom(size_t(NL)*4,0);std::vector<float> homRatio(size_t(NL),0.f),homSharp(size_t(NL),0.f),homShift(size_t(NL),0.f);
    g.readBuffer(bResult,resStride*size_t(NL),[&](const void* m){
        for(int k=1;k<NL;++k){
            const uint8_t* r=static_cast<const uint8_t*>(m)+resStride*size_t(k);
            uint32_t v[14];std::memcpy(v,r,sizeof(v));
            hom[size_t(k)*4]=int(v[9]);hom[size_t(k)*4+1]=int(v[10]);hom[size_t(k)*4+2]=int(v[12]);
            std::memcpy(&homRatio[size_t(k)],&v[11],4);std::memcpy(&homSharp[size_t(k)],&v[13],4);
            // P497: the homography's shift at the guide's centre, in Bayer quads (the short frames' 15-quad rule measures this way)
            float h[9];std::memcpy(h,r,sizeof(h));const float cx=0.5f*float(lw[1]),cy=0.5f*float(lh[1]),dn=h[6]*cx+h[7]*cy+1.f;
            if(std::fabs(dn)>1e-6f)homShift[size_t(k)]=2.f*std::hypot((h[0]*cx+h[1]*cy+h[2])/dn-cx,(h[3]*cx+h[4]*cy+h[5])/dn-cy);
        }
    });
    // P634: a Tetra result's few-frame pass reads the frame map too
    const bool tetraPost=quad&&sensorType==2&&tune.fastTetraPost!=0&&OW==W&&OH==H;
    if(effMap||tetraPost)g.readBuffer(bEff,size_t(w2)*h2*4,[&](const void* m){eff.assign(static_cast<const float*>(m),static_cast<const float*>(m)+size_t(w2)*h2);});
    std::vector<uint16_t> hlMask; // P480
    if(bHlMask){
        hybridHighlightMaskHeader(hlMask,w2,h2);hlMask.resize(8+size_t(w2)*h2);
        g.readBuffer(bHlMask,size_t(w2)*h2*2,[&](const void* m){std::memcpy(hlMask.data()+8,m,size_t(w2)*h2*2);});
    }
    FastMergeGpu::check("readback");
    const auto readDone=Clock::now();
    if(!syncProfile&&profile&&stamps.size()>1){
        GLuint64 prev=0;
        for(size_t i=0;i<stamps.size();++i){
            GLuint64 t=0;queryResult(stamps[i].first,0x8866 /*GL_QUERY_RESULT*/,&t);
            if(i>0&&stamps[i].second>=0)stageMs[size_t(stamps[i].second)]+=double(t-prev)*1e-6;
            prev=t;glDeleteQueries(1,&stamps[i].first);
        }
        glGetError();
    }
    // ---- side outputs of the worker contract
    std::string effLine;
    if(effMap){
        std::vector<float> sample;sample.reserve(eff.size()/7+1);
        for(size_t i=0;i<eff.size();i+=7)sample.push_back(eff[i]);
        float median=1.f;
        if(!sample.empty()){std::nth_element(sample.begin(),sample.begin()+sample.size()/2,sample.end());median=std::max(sample[sample.size()/2],1.f);}
        const float codeScale=64.f/median;
        // P662: every byte is written below (the map only sized here: no assign of zeros over a map of this size); the HALF column of
        // each OUT column from a table and each HALF cell's code once per source row (the same lround / clamp of the same float), an
        // OUT row that reads the row before's HALF row a copy of it: the same codes as one 64-bit division and lround per pixel
        effMap->resize(size_t(OW)*OH);
        std::vector<int> colOf(static_cast<size_t>(OW));
        for(int x=0;x<OW;++x)colOf[size_t(x)]=std::min(int(int64_t(x)*w2/OW),w2-1);
        mergeRowBands(OH,[&](int y0,int y1){ // (P501: the HALF cell of an OUT pixel, x w2 / OW; x / 2 at sabre_scale 1)
            std::vector<uint8_t> code(static_cast<size_t>(w2));int coded=-1;
            for(int y=y0;y<y1;++y){
                const int sy=std::min(int(int64_t(y)*h2/OH),h2-1);uint8_t* d=effMap->data()+size_t(y)*OW;
                if(y>y0&&sy==std::min(int(int64_t(y-1)*h2/OH),h2-1)){std::memcpy(d,d-OW,size_t(OW));continue;}
                if(sy!=coded){
                    const float* e=eff.data()+size_t(sy)*w2;
                    for(int i=0;i<w2;++i)code[size_t(i)]=uint8_t(std::clamp(std::lround(e[i]*codeScale),1L,255L));
                    coded=sy;
                }
                for(int x=0;x<OW;++x)d[x]=code[size_t(colOf[size_t(x)])];
            }
        });
        char line[160];std::snprintf(line,sizeof(line),"HYBRID EFFECTIVE MAP: median frames=%.3f (1 + accumulated donor weight) code scale=%.6f",median,codeScale);
        effLine=line;
    }
    if(clipFlags){
        std::atomic<long> counts[4]{};
        mergeRowBands(OH,[&](int y0,int y1){
            long c[4]{};
            const uint8_t* f=clipFlags->data();
            for(size_t i=size_t(y0)*OW;i<size_t(y1)*OW;++i){
                if(i%8==0&&i+8<=size_t(y1)*OW){uint64_t q;std::memcpy(&q,f+i,8);if(q==0){i+=7;continue;}}
                const uint8_t v=f[i];if(v)for(int k=0;k<4;++k)if(v&(1<<k))++c[k];
            }
            for(int k=0;k<4;++k)counts[k]+=c[k];
        });
        report("HYBRID CLIP FLAGS: trailer "+std::to_string(OW)+"x"+std::to_string(OH)+" uint8 (fast merge: bits R,G,B merged >= 0.94, border 2..98 % clipped kernel weight) R="
            +std::to_string(counts[0].load())+" G="+std::to_string(counts[1].load())+" B="+std::to_string(counts[2].load())+" border="+std::to_string(counts[3].load()));
    }
    if(mergedDng&&input.mergedDng){
        const int dngBlock=quad&&tune.dngMosaic!=0?mosaicBlock:1; // P267
        if(dngBlock>1)report("HYBRID DNG: merged mosaic layout, colour block "+std::to_string(dngBlock)+" (dngMosaic 1, no remosaic)");
        // P501: OUT != RAW: the OUT pixels over the sensor pixel, area weighted (sabre_scale 2: the mean of its 2 x 2, the SCAM
        // merge's grid 2 DNG)
        const double kx=double(OW)/W,ky=double(OH)/H;
        auto at=[&](int x,int y,int c){
            if(!sabre)return out[(size_t(y)*W+x)*3+c];
            const double x0=x*kx,x1=(x+1)*kx,y0=y*ky,y1=(y+1)*ky;
            double sum=0,wsum=0;
            for(int oy=int(y0);oy<std::min(OH,int(std::ceil(y1)));++oy){
                const double wy=std::min(y1,oy+1.0)-std::max(y0,double(oy));if(wy<=0)continue;
                for(int ox=int(x0);ox<std::min(OW,int(std::ceil(x1)));++ox){
                    const double wx=std::min(x1,ox+1.0)-std::max(x0,double(ox));if(wx<=0)continue;
                    sum+=wx*wy*out[(size_t(oy)*OW+ox)*3+c];wsum+=wx*wy;
                }
            }
            return float(sum/std::max(wsum,1e-12));
        };
        const float headroom=hybridWriteMergedDng(*mergedDng,W,H,input,at,tune.dngDither!=0,dngBlock);
        report(hybridDngReport(headroom,W,H,hybridDngCeiling(input),tune.dngDither!=0));
    }
    {
        std::string line="HYBRID FAST MERGE HOMOGRAPHY: frame inliers/tracked/valid@centre shift (quads)";int valid=0;
        for(int k=1;k<NL;++k){
            const LoopFrame& lf=loop[size_t(k-1)];
            char v[80];std::snprintf(v,sizeof(v)," %d%s:%d/%d/%d@%.1f",lf.f,lf.cls==1?"s":lf.cls==2?"l":"",hom[size_t(k)*4],hom[size_t(k)*4+1],hom[size_t(k)*4+2],homShift[size_t(k)]);line+=v;valid+=hom[size_t(k)*4+2]!=0;
        }
        report(line+" ("+std::to_string(valid)+" of "+std::to_string(NL-1)+" valid)");
    }
    if(!effLine.empty())report(effLine);
    const auto finished=Clock::now();
    {
        char t[400];std::snprintf(t,sizeof(t),"HYBRID FAST MERGE ms: front %.0f, context %.0f, objects %.0f, submit %.0f (base %.0f, first donor %.0f; uploads %.0f%s: copy waited %.0f, fences %.0f; copies %.0f), readback %.0f, side outputs %.0f",
            ms(started,gpuStarted),ms(gpuStarted,gpuReady),ms(gpuReady,objectsMade),ms(objectsMade,submitted),ms(objectsMade,baseSubmitted),ms(baseSubmitted,firstDonorSubmitted),uploadMs,bufUpload&&g.ahbUpload(ring[0])?" (AHB)":"",stagedWaitMs,fenceMs,copyMs,ms(submitted,readDone),ms(readDone,finished));
        report(t);
    }
    char line[600];
    std::snprintf(line,sizeof(line),"HYBRID FAST MERGE GPU: %s%s, context %s%s %.0f ms (%s), objects+submit %.0f ms, first readback waited %.0f ms, readback %.0f ms (%s, %s; map %.0f, use %.0f, unmap %.0f)",
        g.renderer.c_str(),(g.gpuPrio+", filter "+(g.rejFilterGather?"gather":"fetch")+", merge batch "+std::to_string(mergeBatch)).c_str(),prewarmed?"prewarmed":"made",prewarmWhy.empty()?"":(" ("+prewarmWhy+")").c_str(),prewarmed?g.initMs:ms(gpuStarted,gpuReady),g.cacheNote().c_str(),
        ms(gpuReady,submitted),waitMs,ms(submitted,readDone),outNote.c_str(),!g.ahbOf.empty()?"cached hardware buffers":g.ahbFailed?"default storage (hardware buffer refused)":"default storage",g.mapMs,g.useMs,g.unmapMs);
    report(line);
    if(profile){
        std::snprintf(line,sizeof(line),"HYBRID FAST MERGE GPU ms (timestamps, sums over %d donors; a compute pass may count in the next draw's stage): unpack=%.1f guide=%.1f pyr=%.1f "
            "homography=%.1f lk=%.1f sparse=%.1f rejection=%.1f filter=%.1f dilate+cyclops+blendEq=%.1f accW=%.1f merge=%.1f base=%.1f out=%.1f"
            " strips: unpack=%.1f guide=%.1f merge=%.1f total=%.1f",
            N-1,stageMs[0],stageMs[8],stageMs[1],stageMs[2],stageMs[3],stageMs[12],stageMs[9],stageMs[10],stageMs[11],stageMs[4],stageMs[5],stageMs[6],stageMs[7],
            stageMs[13],stageMs[14],stageMs[15],
            std::accumulate(stageMs.begin(),stageMs.end(),0.0));
        report(line);
    }
    HybridStats stats;
    stats.mergeMs=ms(started,finished);stats.merged=N+int(shortFrames.size());stats.mergedNormals=1+int(donors.size());stats.bento=validShorts>0;
    hybridSeriesMerged(tune,input,stats.merged,stats.mergedNormals,report);
    report("HYBRID STAGES ms: align=0 localAlign=0 mask=0 merge="+std::to_string(stats.mergeMs)+" total="+std::to_string(ms(started,finished))
        +" merged="+std::to_string(N)+" droppedBracketed="+std::to_string(longsDropped)+" bento=0 (fast merge)");
    HybridLatticeFuse fuse; // P662
    if(mosaicChroma&&!streamed){
        // P563: the native merge's chroma median (dual 5-point, taps max(1, b / 2) output px; the false-colour suppression on the
        // luma's oscillation): the blocks' colour lattice of the per-frame merge (R / B at 1/4 cycles / px 2-3x the quality route's)
        const auto c0=Clock::now();
        const int b=std::max(1,int(std::lround(double(mosaicBlock)*OW/std::max(W,1))));
        std::string how;
        chromaKeepNext(latticeAfter); // P610: the lattice below takes the median's context (not torn down and made again)
        // P662: in the median's GPU bands (50 MP) the lattice follows there, each band's result on the GPU
        if(latticeAfter){fuse.relative=tune.fastQuadLattice>=2;fuse.clipK=2.5f;}
        mosaicChromaMedian(out,OW,OH,b,nullptr,tune.chromaGpu>0,&how,0,nullptr,latticeAfter?&fuse:nullptr);
        chromaKeepNext(false);
        report("HYBRID FAST MERGE MOSAIC: chroma median (dual 5-point, taps "+std::to_string(std::max(1,b/2))+" px of the "+std::to_string(OW)+"x"
            +std::to_string(OH)+" result, "+how+") "+std::to_string(int(ms(c0,Clock::now())))+" ms (P563)");
    }
    if(tetraPost&&!streamed&&eff.size()==size_t(w2)*h2&&tune.mosaicAlongEdge>0.f&&tune.fastTetraAlongEdgeK>0.f){
        // P634 (owner, 2026-11-12: «Займись улучшением результата на Tetra»; «максимум детализации, при этом не ухудшив движущиеся
        // объекты и не добавив новых артефактов»): where this route merged the base frame nearly alone (a moving subject: 1 - 2
        // frames in its map against 15 - 30 beside it) the 4x4 blocks' one-frame reconstruction keeps 4 / 8 px stair-steps along
        // edges. The native merge's along-edge smoothing (P525 / P531, taps step 2) on this route's result by its own frame map
        // (bilinear from its half grid), its share 1 - smoothstep(1, 1 + fastTetraAlongEdgeK, frames): it averages along an
        // edge only, across it and in texture nothing; pixels of 4 frames and more are not touched. (The motion smoothing of
        // P603 was tried here as well: the splash's noise -22 % but its texture -8 % - not taken.)
        const auto c0=Clock::now();
        std::vector<float> ef(size_t(OW)*OH);
        mergeRowBands(OH,[&](int y0,int y1){
            for(int y=y0;y<y1;++y){
                const float fy=std::clamp((float(y)+0.5f)*float(h2)/float(OH)-0.5f,0.f,float(h2-1));
                const int ya=int(fy),yb=std::min(ya+1,h2-1);const float ty=fy-float(ya);
                const float* ra=eff.data()+size_t(ya)*w2;const float* rb=eff.data()+size_t(yb)*w2;float* d=ef.data()+size_t(y)*OW;
                for(int x=0;x<OW;++x){
                    const float fx=std::clamp((float(x)+0.5f)*float(w2)/float(OW)-0.5f,0.f,float(w2-1));
                    const int xa=int(fx),xb=std::min(xa+1,w2-1);const float tx=fx-float(xa);
                    d[x]=(ra[xa]*(1.f-tx)+ra[xb]*tx)*(1.f-ty)+(rb[xa]*(1.f-tx)+rb[xb]*tx)*ty;
                }
            }
        });
        const size_t nEdge=hybridAlongEdge(out,OW,OH,ef,0,0,tune.fastTetraAlongEdgeK,tune.mosaicAlongEdge,2);
        char line[220];
        std::snprintf(line,sizeof(line),"HYBRID FAST MERGE MOSAIC: Tetra few-frame pass (P634): along-edge smoothing (K %.0f, step 2) %zu px (%.2f %%), cpu, %d ms",
            double(tune.fastTetraAlongEdgeK),nEdge,100.0*double(nEdge)/std::max(1.0,double(OW)*OH),int(ms(c0,Clock::now())));
        report(line);
    }
    if(quad&&sensorType==2&&!streamed&&tune.fastTetraClipNotch!=0&&tune.mosaicTetraNotchClipReach>0.f&&OW==W&&clipFlags&&clipFlags->size()==size_t(OW)*OH){
        // P634: the Tetra sensor's own diagonal checker in the glow round a clipped light (P548's pass of the native merge: the
        // base frame has it, so has this route's result - a car's headlight on the reference app's Tetra burst 2)
        const auto c0=Clock::now();
        const size_t n=hybridClipLatticeNotch(out,OW,OH,*clipFlags,tune.mosaicTetraNotchClipReach);
        report("HYBRID FAST MERGE MOSAIC: clipped lights' Tetra checker (P548 / P634, reach "+std::to_string(int(tune.mosaicTetraNotchClipReach))+" px) "
            +std::to_string(n)+" px, "+std::to_string(int(ms(c0,Clock::now())))+" ms");
    }
    if(quad&&sensorType==1&&tune.fastQuadLattice!=0&&!streamed&&tune.chromaGpu>0){
        // P571 (owner, 2026-11-09: «Сетку сделай такой же как на качестве и даже лучше»): the 2 / 4 px lattice the base's own Quad
        // interpolation leaves where few frames merged, off the luma (ChromaMedianGpu::quadLattice), after the chroma median (which moves R / B: the luma's lattice as the result holds it)
        const auto q0=Clock::now();
        if(fuse.done)report(std::string("HYBRID FAST MERGE MOSAIC: Quad lattice off the luma (P571, ")+(fuse.relative?"relative P632":"luma units P591")+"): "+fuse.how
            +", in the chroma median's GPU bands (P662: its time in the median's line)");
        else try{auto gq=chromaTake();
            // the strip's GPU buffers (36 B a pixel with its halo) within a quarter of MemAvailable, as the chroma median's bands
            if(const uint64_t avail=hybridMemAvailable()){const int64_t rows=int64_t(avail/4/(uint64_t(OW)*36))-2*ChromaMedianGpu::kQlHalo;
                gq->qlStrip=int(std::max<int64_t>(64,std::min<int64_t>(1024,rows)));}
            const bool rel=tune.fastQuadLattice>=2; // P632: the lattice relative to the luma (1 = in luma units, P591)
            const std::string t=gq->quadLattice(out,OW,OH,2.5f,rel,mosaicChroma);chromaKeep(std::move(gq)); // P662: the median's result on the GPU
            report(std::string("HYBRID FAST MERGE MOSAIC: Quad lattice off the luma (P571, ")+(rel?"relative P632":"luma units P591")+"): "+t+", "+std::to_string(int(ms(q0,Clock::now())))+" ms");}
        catch(const std::exception& e){report(std::string("HYBRID FAST MERGE MOSAIC: Quad lattice pass skipped: ")+e.what());}
    }
    if(statsOut)*statsOut=stats;
    // P662: streamed: the floats stay a reservation (size 0) and the worker learns their count here; else every float is there
    if(streamed)hybridStreamedFloats()=outFloats; else ensureOut(out);
    if(hlMaskOut&&!hlMask.empty()){ // P480: last, once nothing of the route can fail any more
        double sum=0;size_t on=0,seen=0;
        for(size_t i=8;i<hlMask.size();i+=7){const float v=hybridHalfToFloat(hlMask[i]);sum+=v;on+=v>=0.005f;++seen;} // every 7th cell (the log line)
        char line[220];std::snprintf(line,sizeof(line),"HYBRID HIGHLIGHT MASK: base mask %dx%d for the app's dezipper (%s), mean %.5f, %.3f %% of cells >= 0.005",
            w2,h2,validShorts>0?"valid short frames":"every short frame rejected",sum/double(std::max<size_t>(seen,1)),100.0*double(on)/double(std::max<size_t>(seen,1)));
        report(line);
        *hlMaskOut=std::move(hlMask);
    }
    return out;
}

// ---- P627 (owner, 2026-11-11: «Every processing mode must ALWAYS stay on the mode the user selected»): «Быстрый» never hands a
// shot to the SCAM merge. A frame past the GPU's limits (FastMergeLimit: a 200 MP frame's ring above Mali's 128 MB storage block,
// an output past the texture size) is merged by the same route in column bands: the whole frame's frame pick once (tune.fastPick),
// each band (its columns + kFastBandMargin RAW px on each side, copied) a fast merge of its own, the bands blended over
// kFastBandBlend px at each seam; the merged DNG, the clip flags and the effective map made from the stitched result. Any other
// failure: the fast route again on a new context; a second failure, or a burst the route cannot take at all, fails the job
// («HYBRID ROUTE: ...»: the app saves the base frame alone and says so).
constexpr int kFastBandMargin=256,kFastBandBlend=64;
inline std::vector<float> hybridFastMergeBands(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report,
                                              std::vector<uint16_t>* mergedDng,std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags,int maxW){
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    const int W=input.w,H=input.h;
    const std::array<int,2> o=hybridFastOut(input,tune);
    const int OW=o[0],OH=o[1];
    const double sx=double(OW)/W;
    const int bandMax=(std::min(maxW,W)-0)&~15;
    if(bandMax<2*kFastBandMargin+256)throw std::runtime_error("HYBRID ROUTE: «Быстрый» cannot run: the GPU takes a frame "+std::to_string(maxW)+" px wide at most");
    const int core=bandMax-2*kFastBandMargin;
    const int nb=(W+core-1)/core;
    // the seams (RAW px, multiples of 16), evenly spaced
    std::vector<int> seam(size_t(nb+1));
    for(int i=0;i<=nb;++i)seam[size_t(i)]=i==nb?W:int(int64_t(W)*i/nb)&~15;
    // the whole frame's pick (the bands select nothing of their own: one base, the same donors everywhere)
    std::vector<int> normals,longs;
    for(int f=0;f<int(input.frames.size());++f){const HybridFrame& fr=input.frames[size_t(f)];
        if(!fr.raw||!(fr.exposure>0.f))continue;
        if(f==0||(fr.role==kRoleNormal&&std::abs(fr.exposure-1.f)<0.02f))normals.push_back(f);
        else if(fr.role==kRoleBracketed){if(fr.exposure>=1.2f)longs.push_back(f); else normals.push_back(f);}}
    std::vector<FastThumb> thumbs(input.frames.size());int baseIdx=0,dropped=0;std::vector<int> donors;std::string selectLine;
    fastSelectFrames(input,tune,normals,longs,thumbs,baseIdx,donors,dropped,selectLine);
    HybridTuning bt=tune;bt.fastPick.clear();bt.fastPick.push_back(baseIdx);for(int f:donors)bt.fastPick.push_back(f);
    {char t[300];std::snprintf(t,sizeof(t),"HYBRID FAST MERGE BANDS: %dx%d in %d column bands of <= %d RAW px (+%d px margins, %d px blends): "
        "the GPU's limit (%d px wide at this height); %s",W,H,nb,core,kFastBandMargin,kFastBandBlend,maxW,selectLine.c_str());report(t);}
    std::vector<float> out(size_t(OW)*size_t(OH)*3,0.f);
    std::vector<float> wsum(size_t(OW),0.f);
    std::vector<uint8_t> eff(effMap?size_t(OW)*size_t(OH):0),clip(clipFlags?size_t(OW)*size_t(OH):0);
    std::vector<float> best(size_t(OW),-1.f);
    // the stream of halves and the highlight mask trailer are whole-frame contracts: off for the bands
    std::function<bool(const uint16_t*,int,int,int,int,const float*)> halves;std::swap(halves,hybridFinalHalvesHook());
    std::vector<uint16_t>* hl=hybridHighlightMaskOut();hybridHighlightMaskOut()=nullptr;
    struct Restore{std::function<bool(const uint16_t*,int,int,int,int,const float*)>& h;std::vector<uint16_t>* m;
        ~Restore(){std::swap(h,hybridFinalHalvesHook());hybridHighlightMaskOut()=m;}} restore{halves,hl};
    if(hl)hl->clear();
    HybridStats stats;double mergeMs=0;
    for(int bi=0;bi<nb;++bi){
        const int x0=std::max(0,seam[size_t(bi)]-kFastBandMargin)&~15,x1=std::min(W,(seam[size_t(bi)+1]+kFastBandMargin+15)&~15);
        const int bw=x1-x0;
        HybridInput bin=input;bin.w=bw;bin.outW=int(std::lround(bw*sx));bin.outH=OH;bin.mergedDng=false;
        if(bin.outW%4)bin.outW-=bin.outW%4;
        std::vector<std::vector<uint16_t>> rows(input.frames.size());
        for(size_t f=0;f<input.frames.size();++f){
            if(!input.frames[f].raw)continue;
            rows[f].resize(size_t(bw)*size_t(H));
            for(int y=0;y<H;++y)std::memcpy(rows[f].data()+size_t(y)*bw,input.frames[f].raw+size_t(y)*W+x0,size_t(bw)*2);
            bin.frames[f].raw=rows[f].data();
        }
        std::vector<uint8_t> be,bc;HybridStats bs;
        const auto b0=Clock::now();
        std::vector<float> r=hybridFastMerge(bin,bt,[&](const std::string& l){report("[band "+std::to_string(bi+1)+"/"+std::to_string(nb)+"] "+l);},
                                             nullptr,effMap?&be:nullptr,&bs,clipFlags?&bc:nullptr);
        mergeMs+=std::chrono::duration<double,std::milli>(Clock::now()-b0).count();
        if(bi==0)stats=bs;
        const int bow=bin.outW;
        if(r.size()<size_t(bow)*size_t(OH)*3)throw std::runtime_error("HYBRID FAST MERGE BANDS: band "+std::to_string(bi)+" returned "+std::to_string(r.size()/3)+" px");
        rows.clear();
        // its weight per output column: 1 inside, ramps of kFastBandBlend RAW px across the seams to its neighbours
        const double s0=seam[size_t(bi)],s1=seam[size_t(bi)+1],B=kFastBandBlend;
        for(int X=int(std::floor(x0*sx));X<std::min(OW,int(std::floor(x0*sx))+bow);++X){
            const double x=(X+0.5)/sx;const int bx=X-int(std::floor(x0*sx));
            if(bx<0||bx>=bow)continue;
            const double wl=bi==0?1.0:std::clamp((x-(s0-B/2))/B,0.0,1.0),wr=bi==nb-1?1.0:std::clamp(((s1+B/2)-x)/B,0.0,1.0);
            const float w=float(wl*wr);
            if(w<=0.f)continue;
            for(int y=0;y<OH;++y){const float* src=r.data()+(size_t(y)*bow+bx)*3;float* d=out.data()+(size_t(y)*OW+X)*3;d[0]+=w*src[0];d[1]+=w*src[1];d[2]+=w*src[2];}
            wsum[size_t(X)]+=w;
            if(w>best[size_t(X)]){best[size_t(X)]=w;
                for(int y=0;y<OH;++y){if(!be.empty())eff[size_t(y)*OW+X]=be[size_t(y)*bow+bx];if(!bc.empty())clip[size_t(y)*OW+X]=bc[size_t(y)*bow+bx];}}
        }
    }
    for(int X=0;X<OW;++X){const float w=wsum[size_t(X)];if(w>0.f&&w!=1.f)for(int y=0;y<OH;++y){float* d=out.data()+(size_t(y)*OW+X)*3;d[0]/=w;d[1]/=w;d[2]/=w;}}
    if(effMap)effMap->swap(eff);
    if(clipFlags)clipFlags->swap(clip);
    if(mergedDng&&input.mergedDng){
        const int block=input.mosaic!=0?input.mosaic:hybridMosaicBlock(input,tune,report);
        const int dngBlock=(block==2||block==4)&&tune.dngMosaic!=0?block:1;
        const double kx=double(OW)/W,ky=double(OH)/H;
        auto at=[&](int x,int y,int c){
            if(OW==W&&OH==H)return out[(size_t(y)*W+x)*3+size_t(c)];
            const double xa=x*kx,xb=(x+1)*kx,ya=y*ky,yb=(y+1)*ky;double sum=0,ws=0;
            for(int oy=int(ya);oy<std::min(OH,int(std::ceil(yb)));++oy){const double wy=std::min(yb,oy+1.0)-std::max(ya,double(oy));if(wy<=0)continue;
                for(int ox=int(xa);ox<std::min(OW,int(std::ceil(xb)));++ox){const double wx=std::min(xb,ox+1.0)-std::max(xa,double(ox));if(wx<=0)continue;
                    sum+=wx*wy*out[(size_t(oy)*OW+ox)*3+size_t(c)];ws+=wx*wy;}}
            return float(sum/std::max(ws,1e-12));
        };
        const float headroom=hybridWriteMergedDng(*mergedDng,W,H,input,at,tune.dngDither!=0,dngBlock);
        report(hybridDngReport(headroom,W,H,hybridDngCeiling(input),tune.dngDither!=0));
    }
    stats.mergeMs=std::chrono::duration<double,std::milli>(Clock::now()-started).count();
    report("HYBRID FAST MERGE BANDS: "+std::to_string(nb)+" bands merged in "+std::to_string(int(mergeMs))+" ms, "+std::to_string(int(stats.mergeMs))+" ms in all");
    if(statsOut)*statsOut=stats;
    return out;
}

inline std::vector<float> hybridFastRoute(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report,
                                         std::vector<uint16_t>* mergedDng,std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    std::string why;
    if(!hybridFastEligible(input,tune,report,why))throw std::runtime_error("HYBRID ROUTE: «Быстрый» cannot run: "+why);
    for(int attempt=0;;++attempt){
        try{return hybridFastMerge(input,tune,report,mergedDng,effMap,statsOut,clipFlags);}
        catch(const FastMergeLimit& e){
            report(std::string("HYBRID FAST MERGE: ")+e.what()+": the fast route in column bands");
            if(e.maxW<=0)throw std::runtime_error(std::string("HYBRID ROUTE: «Быстрый» cannot run: ")+e.what()+" (the frame's height)");
            return hybridFastMergeBands(input,tune,report,mergedDng,effMap,statsOut,clipFlags,e.maxW);
        }
        catch(const std::exception& e){
            if(attempt>=1)throw std::runtime_error(std::string("HYBRID ROUTE: «Быстрый» could not run (")+e.what()+", twice)");
            report(std::string("HYBRID FAST MERGE: failed (")+e.what()+"); the fast route again on a new GPU context (P627: never another mode's merge)");
        }
    }
}

} // namespace scam
