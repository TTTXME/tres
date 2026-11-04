#pragma once
// The fast merge: the reference Sabre per-frame burst merge (research spec, see NOTES of the port) as a merge route of the SCAM Hybrid worker,
// tuning line «fastMerge 1» (owner, 2026-11-03: the app's «Быстрый» processing works exactly by the reference scheme, the SCAM merge
// stays as «Качество»). Spec: the research pipeline spec (§n below); the GLSL is the reference's own, byte for byte
// (scam-fast-shaders.h, generated). Stage 1: plain Bayer (cfa_sensor_type 0), NORMAL frames only (ultrashort / bracketed frames
// stay out of this route: no Bento, no long-frame pedestal / motion scope), sabre_scale 1.0 (OUT = RAW grid).
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
// Any GL failure throws: the caller merges the same input with the SCAM merge.
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
    // upload ring 6, the highlight path 6 and 5 per kept short frame, the effective frames 1 (none above 100 MP: flag 16)
    double gpu=7.5+5+1.33+1.5+0.5*frames+6+(shorts?6.0+5.0*shorts:0.0)+(px>double(kHybridHugePixels)?0.0:1.0);
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
        if(block==4&&tune.fastMergeTetra==0){why="4x4 colour block (fastMergeTetra 0)";return false;}}
    if(input.mosaic==4&&tune.fastMergeTetra==0){why="4x4 colour block (fastMergeTetra 0)";return false;} // P489: Tetra stays on SCAM by default
    if(hybridRequestedGrid(input,tune)!=1){why="output grid "+std::to_string(hybridRequestedGrid(input,tune));return false;}
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
inline float fastThumbShift(const FastThumb& ref,const FastThumb& cur){
    const float kr=1.f/std::max(ref.mean,1e-6f),kc=1.f/std::max(cur.mean,1e-6f);
    const int w=ref.w,h=ref.h,w4=(w+3)/4,h4=(h+3)/4;
    std::vector<float> r(ref.v.size()),c(cur.v.size()),r4(size_t(w4)*h4),c4(size_t(w4)*h4);
    for(size_t i=0;i<r.size();++i){r[i]=ref.v[i]*kr;c[i]=cur.v[i]*kc;}
    for(int y=0;y<h4;++y)for(int x=0;x<w4;++x){const size_t s=size_t(4*y)*w+4*x;r4[size_t(y)*w4+x]=r[s];c4[size_t(y)*w4+x]=c[s];}
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
    const int w2=(w+1)/2,h2=(h+1)/2;std::vector<float> r2[4],c2[4];
    for(int ph=0;ph<4;++ph){
        r2[ph].assign(size_t(w2)*h2,0.f);c2[ph].assign(size_t(w2)*h2,0.f);
        for(int y=ph>>1,j=0;y<h;y+=2,++j)for(int x=ph&1,i=0;x<w;x+=2,++i){r2[ph][size_t(j)*w2+i]=r[size_t(y)*w+x];c2[ph][size_t(j)*w2+i]=c[size_t(y)*w+x];}
    }
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

inline std::vector<float> hybridFastMerge(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report,
                                         std::vector<uint16_t>* mergedDng,std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
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
    {
        const bool select=tune.fastMergeSelect!=0&&normals.size()>1;
        std::vector<int> want;
        if(select)want=normals; else if(!longs.empty())want.push_back(0);
        for(int f:longs)want.push_back(f);
        {
            std::vector<std::future<void>> jobs;
            for(int f:want)jobs.push_back(std::async(std::launch::async,[&,f]{
                const HybridFrame& fr=input.frames[size_t(f)];thumbs[size_t(f)]=fastThumb(input,fr.raw,1.f/fr.exposure);}));
            for(auto& j:jobs)j.get();
        }
        thumbsMs=ms(selectStarted,Clock::now());
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
        char line[200];
        std::snprintf(line,sizeof(line),"FrameSelector: base idx=%d (sharpness %.4f%s)",baseIdx,select?thumbs[size_t(baseIdx)].sharp:0.f,select?(sportSel?", sport: moment base, gate 15 px / 0.75":""):", selector off");
        selectLine=line;
        std::vector<std::pair<int,float>> shifts;
        if(select){
            std::vector<std::future<std::pair<int,float>>> jobs;
            for(int f:order)if(f!=baseIdx)jobs.push_back(std::async(std::launch::async,[&,f]{return std::make_pair(f,fastThumbShift(thumbs[size_t(baseIdx)],thumbs[size_t(f)]));}));
            for(auto& j:jobs)shifts.push_back(j.get());
        }
        size_t si=0;
        for(int f:order){
            if(f==baseIdx)continue;
            if(select){
                const float sh=thumbs[size_t(f)].sharp,tr=shifts[si++].second;
                if(tr>shiftMax||sh<sharpMin*thumbs[size_t(baseIdx)].sharp){
                    ++dropped;std::snprintf(line,sizeof(line),"; dropped %d (shift %.1f, sharpness %.4f)",f,tr,sh);selectLine+=line;continue;
                }
            }
            donors.push_back(f);
        }
    }
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
    const float covBase=1.f/sigma,covShrink=2.2f*covBase,covStretch=0.25f*covBase;
    auto table=[](float x,std::initializer_list<std::pair<float,float>> t){
        const auto* p=t.begin();if(x<=p[0].first)return p[0].second;
        for(size_t i=1;i<t.size();++i)if(x<=p[i].first){const float a=(x-p[i-1].first)/(p[i].first-p[i-1].first);return p[i-1].second+a*(p[i].second-p[i-1].second);}
        return p[t.size()-1].second;
    };
    const float tabA=table(snr,{{6.f,5.f},{12.f,4.f},{30.f,3.f}});
    const float covBlurred=1.f/(tabA*sigma);
    const int lkTile0=snr>=10.8f?16:(snr>2.7f?32:64);
    const int tiles[5]={lkTile0,snr>=10.8f?16:32,16,8,8};
    // ---- white balance of the merge's gains (wbR / wbB of the app, else the base's grey world); undone at the end
    // a Quad stream (2x2 colour blocks; the header's block, or measured): the reference's cfa_sensor_type 1 programs (sh035 guide,
    // sh046 quad gray, QPC sh039 / sh025, sh093 hot pixels, sh051 merge_quad); u_cfa_pattern = the pattern of the blocks
    // stage 4: a 4x4 colour-block stream (Tetra / Quad-Quad) takes the same programs with cfa_sensor_type 2 and merge_quad_quad (sh103)
    const int mosaicBlock=input.mosaic!=0?input.mosaic:hybridMosaicBlock(input,tune,report);
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
    // the result vector (150 MB of zeroed pages at 12 MP) on a helper thread meanwhile
    // and then the worker's output memfd reserved and mapped (every page in place) for the halves (hybridFinalHalvesHook), on the
    // same thread (the result's data pointer names the stream)
    const bool streamWanted=hybridFinalHalvesHook()&&!(mergedDng&&input.mergedDng)&&!std::getenv("SCAM_NO_STREAM_OUT");
    std::future<std::vector<float>> outAlloc=std::async(std::launch::async,[n=size_t(W)*H*3,streamWanted,W,H]{
        std::vector<float> v(n);
        if(streamWanted)hybridFinalHalvesHook()(nullptr,W,H,0,0,v.data());
        return v;
    });
    // ---- row strips above 16 MP (owner, 2026-11-03): phase A (every frame's alignment, rejection chain and frame weights at HALF,
    // as below) keeps each frame's flow and rejection map; phase B then merges the output in strips of `stripRows` rows: per strip and
    // frame the frame's RAW rows the strip needs (its flow's vertical range + a margin) are uploaded, unpacked and guided again
    // (scissor), and merged into the strip's accumulators (the whole-frame viewport moved up by the strip's first row: the same
    // tex_coord per fragment); then the short frames, the base self-merge and the output pass of the strip, read back at once.
    // The accumulators (16 B per pixel, RGBA16F as the reference) and the readback hold one strip. SCAM_FM_STRIP_ROWS (replays):
    // strips of that many rows at any size (the equality check against the whole-frame merge).
    int stripRows=0;
    if(const char* e=std::getenv("SCAM_FM_STRIP_ROWS"))stripRows=std::atoi(e);
    if(stripRows<=0&&int64_t(W)*H>kHybridClassicPixels)stripRows=int(std::max<int64_t>(64,std::min<int64_t>(H,int64_t(kFastStripBytes)/(23*int64_t(W)))));
    if(stripRows>0)stripRows=std::min(H,std::max(64,stripRows&~15));
    const bool stripMode=stripRows>0&&(stripRows<H||std::getenv("SCAM_FM_STRIP_ROWS"));
    // ---- the context (prewarmed during the burst's delivery, else made here)
    const auto gpuStarted=Clock::now();
    std::string prewarmWhy;
    std::unique_ptr<FastMergeGpu> held=fastGpuPrewarmTake(prewarmWhy);
    bool prewarmed=held!=nullptr;
    if(held){try{held->acquire();}catch(const std::exception& e){held.reset();prewarmed=false;prewarmWhy=e.what();}}
    if(!held)held=std::make_unique<FastMergeGpu>(hybridProgramCacheDir());
    FastMergeGpu& g=*held;
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
    if(2*W>maxTex||H>maxTex)throw std::runtime_error("RAW byte stream "+std::to_string(2*W)+"x"+std::to_string(H)+" above GL_MAX_TEXTURE_SIZE "+std::to_string(maxTex));
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
    GLuint rejRaw=g.texture(GL_RG8,w2,h2,true),rejF=g.texture(GL_R8,w2,h2,true),rejD=g.texture(GL_R8,w2,h2,true),rejW=g.texture(GL_RG8,w2,h2,true);
    GLuint accW=g.texture(GL_R16F,w2,h2,true);
    GLuint acc0=g.texture(GL_RGBA16F,W,stripMode?stripRows:H,true),acc1=g.texture(GL_RGBA16F,W,stripMode?stripRows:H,true);
    if(stripMode){ // the strips' limits: the viewport moved up by a strip spans the frame, the upload ring holds a whole frame
        GLint vp[2]={0,0},blk=0;glGetIntegerv(GL_MAX_VIEWPORT_DIMS,vp);glGetIntegerv(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&blk);
        if(H>vp[1]||W>vp[0])throw std::runtime_error("frame "+std::to_string(W)+"x"+std::to_string(H)+" above GL_MAX_VIEWPORT_DIMS");
        if(blk>0&&uint64_t(W)*H*2>uint64_t(uint32_t(blk)))throw std::runtime_error("RAW frame above GL_MAX_SHADER_STORAGE_BLOCK_SIZE");
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
    for(int sl=1;sl<mergeBatch;++sl){
        slotBayer.push_back(g.texture(GL_RGBA16F,w2,h2,false));slotCov.push_back(g.texture(GL_RGB10_A2,w2,h2,true));
        slotGuideFbo.push_back(g.fbo({guideCur,slotCov.back(),pyrC[0]}));
        slotFlow.push_back(g.texture(GL_RGBA32F,gx[0],gy[0],lin32));slotFlowFbo.push_back(g.fbo({slotFlow.back()}));
        slotRejW.push_back(g.texture(GL_RG8,w2,h2,true));slotRejWFbo.push_back(g.fbo({slotRejW.back()}));
    }
    std::vector<std::function<void()>> pendingMerges; // the batch's merges, drawn in the loop's order
    auto flushMerges=[&]{if(pendingMerges.empty())return;for(auto& m:pendingMerges)m();pendingMerges.clear();glFlush();};
    // homography buffers: corners (576 x 16 B), tracks (576 x 32 B), one result slot per frame (bound by range, read at the end)
    const GLuint bCorners=g.buffer(576*16),bTracks=g.buffer(576*32);
    const size_t resStride=size_t((64+ssboAlign-1)/ssboAlign*ssboAlign);
    const int loopFrames=int(shorts.size()+donors.size()+longs.size());
    const GLuint bResult=g.buffer(resStride*size_t(loopFrames+1));
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
    const float fb[4]={1.5f/float(w2),1.5f/float(h2),1.f-1.5f/float(w2),1.f-1.5f/float(h2)};
    const float nsb[4]={63.f/64.f,0.5f,1.f/128.f,0.25f};
    const float covRangeRg[4]={0.367188f,24.44773f,0.367188f,25.68449f},covRangeB[2]={-6.97557f,14.00209f};
    const float identity[16]={1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    // hot pixels (§4.1): sabre_hot_pixel_strength 1 below sabre_hot_pixel_max_snr 15; [NOT RECOVERED] the value above it (0 here)
    // and u_threshold_offset (1 % of the range here)
    const float hotStrength=snr<15.f?1.f:0.f;
    {
        using A=FastMergeGpu;
        g.u1i(A::pUnpack,"u_width",w2);g.u1i(A::pUnpack,"u_height",h2);g.u1i(A::pUnpack,"u_cfa_pattern",cfa);g.u1i(A::pUnpack,"u_raw_format",32);
        g.u4f(A::pUnpack,"u_black_level",blackColour);g.u1f(A::pUnpack,"u_white_level",input.white);
        g.u1f(A::pUnpack,"u_threshold_offset",0.01f*(input.white-blackMean));g.u1f(A::pUnpack,"u_suppression_strength",hotStrength);g.u1f(A::pUnpack,"u_threshold_mult",3.f);
        // guide (§4.3)
        g.u4f(PG,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
        // (gains, black_levels_times_gains and the gray gain are the frame's: frontEnd)
        g.u1f(PG,"green_clipping_point",std::sqrt(0.95f*wb[1])); // [UNCERTAIN] the base green at 95 % of the range
        g.u4f(PG,"frame_border_padded",fb[0],fb[1],fb[2],fb[3]);
        g.u4f(PG,"covariance_parameters1",covShrink,covStretch,0.001f,covBase);
        g.u4f(PG,"covariance_parameters2",covBlurred,166.67f,0.f,0.f);
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
        g.u4f(A::pReject,"flow_scale_offset",fsx,fsy,fox,foy);
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

        // merge (§5.7, exact; mvp identity at sabre_scale 1)
        for(int p:{PMB,PMA}){
            g.u4f(p,"tex_size",float(w2),float(h2),1.f/float(w2),1.f/float(h2));
            g.uMat4(p,"mvp_matrix_tex",identity);
            g.u4f(p,"kernel_blurring_params",4.5f,1.5f,0.f,0.f); // [UNCERTAIN] §11.7: z = 0
            g.u1f(p,"accumulated_weight_scale",1.f);g.u1i(p,"u_cfa_pattern",cfa);
            g.u2f(p,"u_ca_k_r",0.f,0.f);g.u2f(p,"u_ca_k_b",0.f,0.f);
            // (gains / black_levels_times_gains / u_ev_scale: the frame's, set per merge)
            g.u4f(p,"frame_border_padded",fb[0],fb[1],fb[2],fb[3]);
            g.u4f(p,"cov_range_rg",covRangeRg[0],covRangeRg[1],covRangeRg[2],covRangeRg[3]);g.u2f(p,"cov_range_b",covRangeB[0],covRangeB[1]);
            g.u1i(p,"u_source_clip_guard",0);
            g.u4f(p,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
        }
        g.u4f(PMB,"flow_scale_offset",fsx,fsy,fox,foy);
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
            const GLenum w=glClientWaitSync(ringFence[slot],GL_SYNC_FLUSH_COMMANDS_BIT,GLuint64(5000000000ULL));
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
    auto quadHotPixels=[&](GLuint to){
        using A=FastMergeGpu;
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
        g.dispatch(A::pHotPixel,(w2+15)/16,(h2+15)/16);
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
            g.dispatch(unpackProg,(w2+15)/16,(h2+15)/16);
            ringFence[slot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0); // the slot is free again once this unpack ran
        } else {
            const GLuint rt=raw[rawSlot];rawSlot^=1; // two byte-stream textures: the next upload never waits for the previous unpack
            glPixelStorei(GL_UNPACK_ALIGNMENT,1);
            glBindTexture(GL_TEXTURE_2D,rt);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,2*W,H,GL_RED_INTEGER,GL_UNSIGNED_BYTE,r);
            uploadMs+=ms(u0,Clock::now());
            g.tex(A::pUnpack,"u_raw_tex",0,rt);
            glBindImageTexture(1,unpackTarget,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
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
    int validShorts=0,shortsDone=0;bool shortsRejected=false;std::string shortLog;
    // P497: the reach (OUT px) of the output pass's hole fill (kFastOutput): 24 covers a near wire's refused copy (~16 px + rims)
    const int holeFill=std::getenv("SCAM_FM_HOLE_FILL")?std::atoi(std::getenv("SCAM_FM_HOLE_FILL")):24;
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
        if(isShort&&!shortsRejected){
            sf.f=f;sf.ev=lf.ev;sf.bayer=g.texture(GL_RGBA16F,w2,h2,false);sf.guide=g.texture(GL_RGBA16F,w2,h2,true);sf.cov=g.texture(GL_RGB10_A2,w2,h2,true);
            sf.flow=g.texture(GL_RGBA32F,gx[0],gy[0],lin32);sf.fGuide=g.fbo({sf.guide,sf.cov,pyrC[0]});
            g.u1i(A::pShortRecover,"u_width",w2);g.u1i(A::pShortRecover,"u_height",h2);g.u1i(A::pShortRecover,"u_cfa_pattern",cfa);
            g.u4f(A::pShortRecover,"u_black_level",blackSensor);g.u1f(A::pShortRecover,"u_white_level",input.white);
            g.u4f(A::pShortRecover,"u_wb_gains",wb[0],wb[1],wb[2],1.f);
            lutFrame=&fr;frontEnd(fr.raw,sf.bayer,sf.fGuide,sf.guide,pyrC,fPyrC,next,lf.ev,fr.slope,fr.offset,bayerCur);
        } else if(isShort){ // the short frames are off (an earlier one failed): its upload staged already, skipped here
            lutFrame=&fr;frontEnd(fr.raw,bayerCur,fGuideCur,guideCur,pyrC,fPyrC,next,lf.ev,fr.slope,fr.offset);
            continue;
        } else {lutFrame=&fr;frontEnd(fr.raw,slotBayer[slot],slotGuideFbo[slot],guideCur,pyrC,fPyrC,next,lf.ev,fr.slope,fr.offset);}
        // global homography (§5.3): KLT of the base corners into the frame's 8-bit L1 guide, RANSAC on the GPU
        g.tex(A::pGuide8,"uInput",0,pyrC[1]);g.draw(A::pGuide8,fG8Cur,lw[1],lh[1],1);
        g.tex(A::pKlt,"u_ref_tex",0,g8Base);g.tex(A::pKlt,"u_cur_tex",1,g8Cur);g.u2i(A::pKlt,"u_guide_size",lw[1],lh[1]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bCorners);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,bTracks);
        g.dispatch(A::pKlt,(576+63)/64,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        g.u1i(A::pRansac,"u_frame_idx",f);g.u1f(A::pRansac,"u_max_reproj_error",2.f);g.u2i(A::pRansac,"u_guide_size",lw[1],lh[1]);g.u1i(A::pRansac,"u_is_short_frame",isShort?1:0);g.u1f(A::pRansac,"u_short_min_inliers",shortMinInliers);g.u1f(A::pRansac,"u_short_max_shift",shortMaxShift);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER,2,bResult,GLintptr(resStride*size_t(k+1)),GLsizeiptr(64));
        g.dispatch(A::pRansac,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
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
            g.u1i(A::pHlValidate,"u_is_first_short",shortsDone==0);g.u1f(A::pHlValidate,"u_err_thresh",0.8f);
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
                std::snprintf(line,sizeof(line),"; frame %d rejected (homography: inliers < 65 %% or motion > 15 px)",f);shortLog+=line;shortsRejected=true;
            } else if(st[2]>0.01f&&(motion>0.35f||clip>clipGate)){
                std::snprintf(line,sizeof(line),"; frame %d rejected by the safety gate (motion %.2f %%, clip %.2f %%, mask cells %.1f)",f,100.f*motion,100.f*clip,st[2]);
                shortLog+=line;shortsRejected=true;
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
            if(shortsRejected){validShorts=0;shortFrames.clear();} // "Disabling all short frames"
            glFlush();
            continue;
        }
        alignTiles();
        lap(3);
        GLuint flowK=slotFlow[slot],fFlowK=slotFlowFbo[slot],rejWK=slotRejW[slot],fRejWK=slotRejWFbo[slot];
        if(stripMode){
            kFlow[k]=flowK=g.texture(GL_RGBA32F,gx[0],gy[0],lin32);kFlowFbo[k]=fFlowK=g.fbo({flowK});
            kRejW[k]=rejWK=g.texture(GL_RG8,w2,h2,true);kRejWFbo[k]=fRejWK=g.fbo({rejWK});
            g.tex(A::pSparse,"uAlignment",0,alM[0]); // (making a texture binds it to the active unit: the sparse pass's input again)
        }
        g.draw(A::pSparse,fFlowK,gx[0],gy[0],1);
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
        g.tex(A::pRejFilter,"u_guide_luma",0,refLuma);g.tex(A::pRejFilter,"u_rejection",1,rejRaw);g.tex(A::pRejFilter,"u_pixel_difference",2,rejRaw);
        g.draw(A::pRejFilter,fRejF,w2,h2,1);
        lap(10);
        g.tex(A::pDilate,"rejection_texture",0,rejF);g.draw(A::pDilate,fRejD,w2,h2,1);
        g.tex(A::pCyclops,"rejection_texture",0,rejD);g.draw(A::pCyclops,fRejF,w2,h2,1);
        g.tex(A::pBlendEq,"u_donor_weight",0,rejF);g.draw(A::pBlendEq,fRejWK,w2,h2,1);
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
                g.u4f(mp,"flow_scale_offset",fsx,fsy,fox,foy);g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);
                g.u2f(mp,"u_long_frame_params",0.f,1.f);g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            if(isLong){
                g.u4f(mp,"flow_scale_offset",fsx,fsy,fox,foy);
                g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.88f);g.u2f(mp,"u_long_frame_params",weightA,covMul);
                g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            g.tex(mp,"bayer_texture",0,mBayer);g.tex(mp,"flow_texture",1,flowK);g.tex(mp,"kernel_covariance_texture",2,mCov);
            g.tex(mp,"rejection_texture",3,rejWK);g.tex(mp,"base_frame_guide_texture",4,refColor);g.tex(mp,"accumulated_frame_weights",5,accW);
            g.tex(mp,"highlight_mask_texture",6,hlMaskTex);g.tex(mp,"u_long_scope_tex",7,oneScope);
            glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE);
            g.draw(mp,fMerge,W,H);
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
            g.draw(mp,fMerge,W,H);
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
        g.draw(PMA,fMerge,W,H);
        glDisable(GL_BLEND);
        glFlush();
        FastMergeGpu::check("base self-merge");
        lap(6);
        // ---- normalisation and the readback buffers, in bands of rows (each buffer read from offset 0)
        const int bandRows=512;
        const int bands=(H+bandRows-1)/bandRows;
        std::vector<GLuint> bRgb(static_cast<size_t>(bands)),bFlags(static_cast<size_t>(bands));
        bEff=g.readBuffer(size_t(w2)*h2*4);
        g.tex(A::pOutput,"u_accum_color_r",0,acc0);g.tex(A::pOutput,"u_accum_weights_gb",1,acc1);g.tex(A::pOutput,"u_frame_weights",2,accW);
        g.u2i(A::pOutput,"u_size",W,H);g.u2i(A::pOutput,"u_half",w2,h2);
        g.u3f(A::pOutput,"u_inv_wb",1.f/wb[0],1.f/wb[1],1.f/wb[2]);g.u1f(A::pOutput,"u_clip",0.94f);g.u1i(A::pOutput,"u_fill",validShorts>0?holeFill:0);g.tex(A::pOutput,"u_hl_base",3,bento?hlBase:zeroMask);g.tex(A::pOutput,"u_hl_final",4,hlMaskTex);g.tex(A::pOutput,"u_base_bayer",5,bayerBase);g.u4f(A::pOutput,"u_black",blackSensor);g.u1f(A::pOutput,"u_white",input.white);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,bEff);
        for(int b=0;b<bands;++b){
            const int y0=b*bandRows,rows=std::min(bandRows,H-y0);
            bRgb[size_t(b)]=g.readBuffer(size_t(W)*rows*6);bFlags[size_t(b)]=g.readBuffer(size_t(W)*rows);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bRgb[size_t(b)]);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,bFlags[size_t(b)]);
            g.u1i(A::pOutput,"u_row0",y0);g.u1i(A::pOutput,"u_rows",rows);
            g.dispatch(A::pOutput,(W/4+63)/64,rows);
        }
        // P480: the base highlight mask (0x530) for the app's dezipper pass, only with the highlight path on (none: no pass, no trailer)
        if(hlMaskOut&&bento){
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
        if(clipFlags)clipFlags->assign(size_t(W)*H,0);
        // the halves straight into the worker's output memfd (hybridFinalHalvesHook): the floats are then not filled - nothing reads
        // them (the worker writes the streamed halves and only the trailers); a band the stream does not take, or a merged DNG (made
        // from the floats), fills the floats from the same buffers
        streamed=streamWanted&&halvesHook;
        for(int b=0;b<bands&&streamed;++b){
            const int y0=b*bandRows,rows=std::min(bandRows,H-y0);
            const auto t0=Clock::now();
            g.readBuffer(bRgb[size_t(b)],size_t(W)*rows*6,[&](const void* m){
                if(b==0)waitMs=ms(t0,Clock::now());
                streamed=halvesHook(static_cast<const uint16_t*>(m),W,H,y0,y0+rows,out.data());
            });
        }
        if(streamed)streamed=halvesHook(nullptr,W,H,-1,-1,out.data());
        outNote=streamed?"halves streamed into the output":"floats";
        if(!streamed&&halvesHook)outNote+=" (the stream did not take the halves)";
        for(int b=0;b<bands;++b){
            const int y0=b*bandRows,rows=std::min(bandRows,H-y0);
            const auto t0=Clock::now();
            if(!streamed)g.readBuffer(bRgb[size_t(b)],size_t(W)*rows*6,[&](const void* m){
                if(b==0&&waitMs==0)waitMs=ms(t0,Clock::now());
                const uint16_t* src=static_cast<const uint16_t*>(m);
                mergeRowBands(rows,[&](int r0,int r1){
                    const size_t a=size_t(r0)*W*3,e=size_t(r1)*W*3;
                    float* d=out.data()+size_t(y0)*W*3;
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
            if(clipFlags)g.readBuffer(bFlags[size_t(b)],size_t(W)*rows,[&](const void* m){std::memcpy(clipFlags->data()+size_t(y0)*W,m,size_t(W)*rows);});
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
    struct KFlow { std::vector<float> fy;float off=1.5f; };
    std::vector<KFlow> kfy(loop.size());
    auto readFlow=[&](GLuint fbo,float off,KFlow& kf){
        std::vector<float> v(size_t(gx[0])*gy[0]*4);
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glReadBuffer(GL_COLOR_ATTACHMENT0);
        glReadPixels(0,0,gx[0],gy[0],GL_RGBA,GL_FLOAT,v.data());
        kf.fy.resize(size_t(gx[0])*gy[0]);for(size_t i=0;i<kf.fy.size();++i)kf.fy[i]=v[4*i+1];kf.off=off;
    };
    for(size_t k=0;k<loop.size();++k)if(kMerged[k])readFlow(kFlowFbo[k],1.5f,kfy[k]);
    std::vector<KFlow> sflowY(shortFrames.size());
    for(size_t q=0;q<shortFrames.size();++q)readFlow(g.fbo({shortFrames[q].flow}),0.5f,sflowY[q]);
    FastMergeGpu::check("flow readback");
    // HALF rows [wa, wb) of a frame that the strip's HALF rows [ca, cb) sample: the flow's range over the tiles near the strip, a
    // margin of 8 rows (the merge's 3 x 3 sites, bilinear covariance and flow, the guide's 3 x 3); within 4 rows of an edge the
    // mirrored samples reach the edge
    auto window=[&](const KFlow& kf,int ca,int cb,int& wa,int& wb){
        float lo=0.f,hi=0.f;bool any=false;
        for(int j=0;j<gy[0];++j){
            const float cy=(float(j)+kf.off)*float(tiles[0]);
            if(cy<float(ca-2*tiles[0])||cy>float(cb+2*tiles[0]))continue;
            for(int i=0;i<gx[0];++i){const float d=kf.fy[size_t(j)*gx[0]+i]*float(h2);lo=any?std::min(lo,d):d;hi=any?std::max(hi,d):d;any=true;}
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
            const GLenum w=glClientWaitSync(ringFence[slot],GL_SYNC_FLUSH_COMMANDS_BIT,GLuint64(5000000000ULL));
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
        g.dispatch(A::pUnpackBuf,(w2+15)/16,(qb-qa+15)/16);
        g.u1i(A::pUnpackBuf,"u_row0q",0);
        ringFence[slot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        if(quad)quadHotPixels(unpackTo?unpackTo:bayer); // (over the frame: only the window's rows are read later)
        if(unpackTo){ // a short frame: sh011 over the whole frame (only its unpacked rows are read later)
            g.tex(A::pShortRecover,"u_input_extracted",0,unpackTo);
            glBindImageTexture(1,bayer,0,GL_FALSE,0,GL_WRITE_ONLY,GL_RGBA16F);
            g.dispatch(A::pShortRecover,(w2+15)/16,(h2+15)/16);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        std::array<float,4> gs,bs,gc,bc,cap;frameGains(ev,gs,bs,gc,bc);
        for(int q=0;q<4;++q)cap[size_t(q)]=grayCap[size_t(q)]*std::min(ev,1.f); // P497: as frontEnd
        g.u4f(PG,"gains",gs);g.u4f(PG,"black_levels_times_gains",bs);
        g.u1f(PG,"u_gray_gain",grayGain*ev);g.u4f(PG,"u_gray_cap_spatial",cap);
        lutFrame=&fr;noiseLutFor(fr.slope>0.f?fr.slope:S,fr.offset>=0.f?fr.offset:O,ev);
        g.tex(PG,"bayer_texture",0,bayer);g.tex(PG,"noise_estimates_texture",1,noiseLut);
        glEnable(GL_SCISSOR_TEST);glScissor(0,wa,w2,wb-wa);
        g.drawAt(PG,fGuide,0,0,w2,h2);
        glDisable(GL_SCISSOR_TEST);
    };
    // the accumulators of one strip, the readback of one strip (reused)
    const GLuint fStrip=fMerge;
    const GLuint bRgbS=g.readBuffer(size_t(W)*stripRows*6),bFlagsS=g.readBuffer(size_t(W)*stripRows);
    g.tex(A::pOutput,"u_accum_color_r",0,acc0);g.tex(A::pOutput,"u_accum_weights_gb",1,acc1);g.tex(A::pOutput,"u_frame_weights",2,accW);
    g.u2i(A::pOutput,"u_size",W,H);g.u2i(A::pOutput,"u_half",w2,h2);
    g.u3f(A::pOutput,"u_inv_wb",1.f/wb[0],1.f/wb[1],1.f/wb[2]);g.u1f(A::pOutput,"u_clip",0.94f);g.u1i(A::pOutput,"u_fill",validShorts>0?holeFill:0);g.tex(A::pOutput,"u_hl_base",3,bento?hlBase:zeroMask);g.tex(A::pOutput,"u_hl_final",4,hlMaskTex);g.tex(A::pOutput,"u_base_bayer",5,bayerBase);g.u4f(A::pOutput,"u_black",blackSensor);g.u1f(A::pOutput,"u_white",input.white);
    out=outAlloc.get();
    if(clipFlags)clipFlags->assign(size_t(W)*H,0);
    streamed=streamWanted&&halvesHook;bool streamTook=false;
    int strips=0;std::vector<int> winRows;
    for(int y0=0;y0<H;y0+=stripRows,++strips){
        const int y1=std::min(H,y0+stripRows),rows=y1-y0,ca=y0/2,cb=std::min(h2,(y1+1)/2);
        {const float z[4]={0,0,0,0};glBindFramebuffer(GL_FRAMEBUFFER,fStrip);glClearBufferfv(GL_COLOR,0,z);glClearBufferfv(GL_COLOR,1,z);}
        auto mergeDraw=[&](int mp){
            glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE);
            g.drawAt(mp,fStrip,0,-y0,W,H);
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
                g.u4f(mp,"flow_scale_offset",fsx,fsy,fox,foy);g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.95f);
                g.u2f(mp,"u_long_frame_params",0.f,1.f);g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            if(isLong){
                g.u4f(mp,"flow_scale_offset",fsx,fsy,fox,foy);
                g.u1f(mp,"u_source_clipping_point",blackMean+(input.white-blackMean)*0.88f);g.u2f(mp,"u_long_frame_params",kWeightA[k],kCovMul[k]);
                g.u4f(mp,"highlight_params",0.f,1.f,bento?1.f:0.f,float(shorts.size()));
            }
            g.tex(mp,"bayer_texture",0,bayerCur);g.tex(mp,"flow_texture",1,kFlow[k]);g.tex(mp,"kernel_covariance_texture",2,covCur);
            g.tex(mp,"rejection_texture",3,kRejW[k]);g.tex(mp,"base_frame_guide_texture",4,refColor);g.tex(mp,"accumulated_frame_weights",5,accW);
            g.tex(mp,"highlight_mask_texture",6,hlMaskTex);g.tex(mp,"u_long_scope_tex",7,oneScope);
            mergeDraw(mp);
            glFlush();
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
        g.dispatch(A::pOutput,(W/4+63)/64,rows);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        FastMergeGpu::check("strip");
        const auto t0=Clock::now();
        g.readBuffer(bRgbS,size_t(W)*rows*6,[&](const void* m){
            if(y0==0)waitMs=ms(t0,Clock::now());
            const uint16_t* src=static_cast<const uint16_t*>(m);
            if(streamed){streamed=halvesHook(src,W,H,y0,y1,out.data());if(streamed){streamTook=true;return;}}
            if(streamTook)throw std::runtime_error("FAST MERGE: the output stream refused a strip after taking the first ones");
            mergeRowBands(rows,[&](int r0,int r1){
                const size_t a=size_t(r0)*W*3,e=size_t(r1)*W*3;
                float* d=out.data()+size_t(y0)*W*3;
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
        if(clipFlags)g.readBuffer(bFlagsS,size_t(W)*rows,[&](const void* m){std::memcpy(clipFlags->data()+size_t(y0)*W,m,size_t(W)*rows);});
    }
    if(streamed)streamed=halvesHook(nullptr,W,H,-1,-1,out.data());
    outNote=streamed?"halves streamed into the output":"floats";
    if(!streamed&&halvesHook)outNote+=" (the stream did not take the halves)";
    {
        long wsum=0;for(int r:winRows)wsum+=r;
        char line[300];
        std::snprintf(line,sizeof(line),"HYBRID FAST MERGE STRIPS: %d strips of %d rows (accumulators %s MB); frames' rows re-read per strip %.2f x the HALF height on average",
            strips,stripRows,hybridMB(uint64_t(W)*stripRows*16).c_str(),winRows.empty()?0.0:double(wsum)/double(winRows.size())/double(h2)*double(strips));
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
    if(effMap)g.readBuffer(bEff,size_t(w2)*h2*4,[&](const void* m){eff.assign(static_cast<const float*>(m),static_cast<const float*>(m)+size_t(w2)*h2);});
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
        effMap->assign(size_t(W)*H,0);
        mergeRowBands(H,[&](int y0,int y1){
            for(int y=y0;y<y1;++y){
                const float* e=eff.data()+size_t(std::min(y/2,h2-1))*w2;uint8_t* d=effMap->data()+size_t(y)*W;
                for(int x=0;x<W;++x)d[x]=uint8_t(std::clamp(std::lround(e[std::min(x/2,w2-1)]*codeScale),1L,255L));
            }
        });
        char line[160];std::snprintf(line,sizeof(line),"HYBRID EFFECTIVE MAP: median frames=%.3f (1 + accumulated donor weight) code scale=%.6f",median,codeScale);
        effLine=line;
    }
    if(clipFlags){
        std::atomic<long> counts[4]{};
        mergeRowBands(H,[&](int y0,int y1){
            long c[4]{};
            const uint8_t* f=clipFlags->data();
            for(size_t i=size_t(y0)*W;i<size_t(y1)*W;++i){
                if(i%8==0&&i+8<=size_t(y1)*W){uint64_t q;std::memcpy(&q,f+i,8);if(q==0){i+=7;continue;}}
                const uint8_t v=f[i];if(v)for(int k=0;k<4;++k)if(v&(1<<k))++c[k];
            }
            for(int k=0;k<4;++k)counts[k]+=c[k];
        });
        report("HYBRID CLIP FLAGS: trailer "+std::to_string(W)+"x"+std::to_string(H)+" uint8 (fast merge: bits R,G,B merged >= 0.94, border 2..98 % clipped kernel weight) R="
            +std::to_string(counts[0].load())+" G="+std::to_string(counts[1].load())+" B="+std::to_string(counts[2].load())+" border="+std::to_string(counts[3].load()));
    }
    if(mergedDng&&input.mergedDng){
        const int dngBlock=quad&&tune.dngMosaic!=0?mosaicBlock:1; // P267
        if(dngBlock>1)report("HYBRID DNG: merged mosaic layout, colour block "+std::to_string(dngBlock)+" (dngMosaic 1, no remosaic)");
        const float headroom=hybridWriteMergedDng(*mergedDng,W,H,input,[&](int x,int y,int c){return out[(size_t(y)*W+x)*3+c];},tune.dngDither!=0,dngBlock);
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
            "homography=%.1f lk=%.1f sparse=%.1f rejection=%.1f filter=%.1f dilate+cyclops+blendEq=%.1f accW=%.1f merge=%.1f base=%.1f out=%.1f total=%.1f",
            N-1,stageMs[0],stageMs[8],stageMs[1],stageMs[2],stageMs[3],stageMs[12],stageMs[9],stageMs[10],stageMs[11],stageMs[4],stageMs[5],stageMs[6],stageMs[7],
            std::accumulate(stageMs.begin(),stageMs.end(),0.0));
        report(line);
    }
    HybridStats stats;
    stats.mergeMs=ms(started,finished);stats.merged=N+int(shortFrames.size());stats.mergedNormals=1+int(donors.size());stats.bento=validShorts>0;
    hybridSeriesMerged(tune,input,stats.merged,stats.mergedNormals,report);
    report("HYBRID STAGES ms: align=0 localAlign=0 mask=0 merge="+std::to_string(stats.mergeMs)+" total="+std::to_string(ms(started,finished))
        +" merged="+std::to_string(N)+" droppedBracketed="+std::to_string(longsDropped)+" bento=0 (fast merge)");
    if(statsOut)*statsOut=stats;
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

} // namespace scam
