package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.*;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.BufferUtils;
import com.scamera.app.util.ParallelWork;
import static android.opengl.GLES20.*;

/**
 * Import reconstructed sensor RGB; apply WB and normalized lens shading once. On an SCAM Hybrid shot it also runs the
 * per-channel highlight recovery (research/hybrid5/highlights.md): clip levels per physical channel before WB, a clipped
 * green of a white highlight rebuilt from R and B, all channels clipped -> neutral white at the brightest level, a
 * saturated single colour left alone. Hybrid values stay signed ({@link #signedInput}).
 */
public final class ScamRgb extends Node {
    public ScamRgb(){super("","ScamRgb");}
    @Override public void bindShader(){}

    /**
     * P349: the glow share of the fully clipped cores (scamhdr/suncore.glsl) for WhyCombine's sun bloom, or null (off): the
     * share of R/G/B-clipped pixels whose clipped mean holds the ultrashort frame too (P366: clip flag bit 5; foam the normal
     * frames clip is no sun) per block (~192 blocks on the shorter side), spread by a max-gaussian of the setting's
     * radius. A few hundred kB; WhyCombine closes it.
     */
    /**
     * P506 (owner, 2026-11-05, a setting sun at the sensor's shortest exposure beside the reference app: its sun a soft warm-white
     * glow, ours a flat disc with a hard rim): a shot that took no short frames at all («us skipped (sensor floor)», Motion) has no
     * bit 5 anywhere, so P366's rule left every core without the bloom; there a core clipped in every channel counts by itself, and
     * WhyCombine's bloom touches only pixels already near white (WhyTone.sunBloomGate), so the water round a glitter stays as it was.
     * scam_dev hybrid_sun_bloom_no_short 0: as before.
     */
    static boolean noShortBloom(){
        return !ScamHybridBurst.lastBentoApplied&&PreferenceKeys.hybridSwitch("sun_bloom_no_short",true);
    }

    private GLTexture sunBloom(GLTexture flags,Point size,int shorter){
        final float[] set=WhyTone.sunBloom();
        if(set[0]<=0f)return null;
        final int block=shareBlock(shorter);
        final Point coarse=new Point((size.x+block-1)/block,(size.y+block-1)/block);
        final GLFormat one=new GLFormat(GLFormat.DataType.FLOAT_16,1);
        GLTexture share=null,half=null,out=null;
        try{
            share=new GLTexture(coarse,one,null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            if(!blockSums(0,flags,null,null,null,coarse,block,share)){
                shaderProg.setDefine("MODE",0);
                shaderProg.useAssetProgram("scamhdr/suncore",false);
                shaderProg.setTexture("InputBuffer",flags);
                shaderProg.setVar("blockU",block);
                shaderProg.setVar("noShortU",noShortBloom()?1:0); // P506
                shaderProg.drawBlocks(share);
            }
            final float radius=set[1]*shorter/block;
            half=new GLTexture(coarse,one,null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            shaderProg.setDefine("MODE",1);
            shaderProg.useAssetProgram("scamhdr/suncore",false);
            shaderProg.setTexture("InputBuffer",share);
            shaderProg.setVar("dirU",1,0);shaderProg.setVar("radiusU",radius);
            shaderProg.drawBlocks(half);
            out=new GLTexture(coarse,one,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            shaderProg.setDefine("MODE",1);
            shaderProg.useAssetProgram("scamhdr/suncore",false);
            shaderProg.setTexture("InputBuffer",half);
            shaderProg.setVar("dirU",0,1);shaderProg.setVar("radiusU",radius);
            shaderProg.drawBlocks(out);
            shaderProg.closed=true;
            final GLTexture done=out;out=null;
            return done;
        }finally{
            if(share!=null)share.close();
            if(half!=null)half.close();
            if(out!=null)out.close();
        }
    }

    /**
     * P479 (speed): suncore MODE 0 / 2's per-block sums (the sun bloom's share, the opposed landing's weights) by the compute pass
     * scamhdr/suncore_cs - an invocation per pixel, the block's sums on one invocation in suncore's order - and suncore MODE 4 / 5
     * (MODE 0 / 2's last lines) into {@code target}: the same values as one fragment per block looping over its pixels (51 ms
     * of GPU at 12 MP, latency-bound), in a few ms. False (nothing drawn) for the old GL model, scam_dev.txt "suncore_cs 0" or a
     * GL failure: the caller draws the fragment pass.
     */
    private boolean blockSums(int mode,GLTexture source,GLTexture flags,ChannelClip cc,PostPipeline p,Point coarse,int block,GLTexture target){
        if(com.scamera.app.processing.opengl.PostGlMode.legacy()||!PreferenceKeys.scamDevSwitch("suncore_cs",true))return false;
        GLTexture sums=null;
        try{
            while(android.opengl.GLES31.glGetError()!=android.opengl.GLES31.GL_NO_ERROR){
                // drain: only the pass below decides
            }
            sums=new GLTexture(coarse,new GLFormat(GLFormat.DataType.FLOAT_32,4),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            shaderProg.setDefine("MODE",mode);
            shaderProg.setLayout(16,16,1);
            shaderProg.useAssetProgram("scamhdr/suncore_cs",true);
            shaderProg.setTexture("InputBuffer",source);
            shaderProg.setVar("blockU",block);
            if(mode==0)shaderProg.setVar("noShortU",noShortBloom()?1:0); // P506
            if(mode==2){
                if(flags!=null)shaderProg.setTexture("ClipFlags",flags);
                shaderProg.setVar("clipFlagsU",flags!=null?1:0);
                shaderProg.setVar("clipLoU",cc.lo);shaderProg.setVar("clipHiU",cc.hi);
                shaderProg.setVar("toRenderU",toRender(p));
            }
            shaderProg.setTextureCompute("outCells",sums,true);
            android.opengl.GLES31.glDispatchCompute(coarse.x,coarse.y,1);
            android.opengl.GLES31.glMemoryBarrier(android.opengl.GLES31.GL_TEXTURE_FETCH_BARRIER_BIT|android.opengl.GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            final int error=android.opengl.GLES31.glGetError();
            if(error!=android.opengl.GLES31.GL_NO_ERROR){
                com.scamera.app.util.Log.w("SCAM_PIPELINE","suncore compute MODE "+mode+": GL error 0x"+Integer.toHexString(error)+", the fragment pass");
                return false;
            }
            shaderProg.setDefine("MODE",mode==0?4:5);
            shaderProg.useAssetProgram("scamhdr/suncore",false);
            shaderProg.setTexture("InputBuffer",sums);
            shaderProg.drawBlocks(target);
            shaderProg.closed=true;
            return true;
        }catch(RuntimeException failure){
            com.scamera.app.util.Log.w("SCAM_PIPELINE","suncore compute MODE "+mode+": "+failure+", the fragment pass");
            return false;
        }finally{
            if(sums!=null)sums.close();
        }
    }

    /**
     * P349 / P378: the shorter side of the frame the sun bloom's and the opposed landing's blocks divide (~192 blocks on it) -
     * P277: of the whole frame on a lab fragment (its whyLow height or width x the box, WhyCombine's highlight mask likewise takes
     * the whole frame's), so a fragment's lights fill the blocks and get the radius the photo's do.
     */
    static int wholeShorter(LabFrame fz,int lowFactor,Point size){
        if(fz!=null&&fz.lowWidth>0&&fz.lowHeight>0)return Math.min(fz.lowWidth,fz.lowHeight)*lowFactor;
        return Math.min(size.x,size.y);
    }

    /** P349 / P378: the maps' block, px: ~192 blocks on the frame's shorter side ({@link #wholeShorter}). */
    static int shareBlock(int shorter){
        return Math.max(1,Math.round(shorter/192f));
    }

    /** P378: the opposed landing's radius, a share of the frame's shorter side (the sun bloom's default). */
    static final float OPPOSED_RADIUS=0.033f;

    /**
     * P378 (owner, 2026-10-28: a pink / orange ring round a sun clipped at the sensor floor, «везде»: a light clipped with no
     * ultrashort data keeps a neutral core and a neutral ramp out of it): the ring is the light's warm, low-chroma glow beside
     * the clipped core that WhyCombine's highlight landing paints at full brightness. Where such a light is - the pixels «Inpaint
     * opposed» rebuilds, with the clip flags or without them (cpu-single; scamhdr/suncore.glsl MODE 2) - per block, a block's
     * weight 1 from 15 % of it rebuilt (a light a block wide or more, wherever it sits on the grid; a few clipped dots none),
     * spread by a max-gaussian of {@link #OPPOSED_RADIUS} (MODE 1) for the landing (HL_OPPOSED: a low-saturation glow there
     * lands on white). Null when the landing is not drawn (WhyCombine.landingDrawn). With file logging off, the map stays on
     * the GPU even when empty; logging or the rollback flag reads block statistics and skips an empty map. WhyCombine closes it.
     * P378 review: every channel blown (a G-clipped sky is no light), and per light whether its glow goes white (MODE 2 measures
     * the glow's saturation in the render's colour, MODE 3 gates each block's light, MODE 1 carries the nearest light's gate in
     * .g): a pale sun's aureole white, an orange lamp or a red rim keeps its colour over its whole glow - no contour where a
     * glow pales with distance.
     */
    private GLTexture opposedShare(PostPipeline p,GLTexture input,GLTexture flags,ChannelClip cc,Point size,int shorter){
        if(!WhyCombine.landingDrawn(p))return null;
        final int block=shareBlock(shorter);
        final Point coarse=new Point((size.x+block-1)/block,(size.y+block-1)/block);
        final GLFormat two=new GLFormat(GLFormat.DataType.FLOAT_16,2),cells=new GLFormat(GLFormat.DataType.FLOAT_32,4);
        GLTexture weights=null,sums=null,gates=null,half=null,out=null;
        try{
            weights=new GLTexture(coarse,cells,null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            if(!blockSums(2,input,flags,cc,p,coarse,block,weights)){
                shaderProg.setDefine("MODE",2);
                shaderProg.useAssetProgram("scamhdr/suncore",false);
                shaderProg.setTexture("InputBuffer",input);
                if(flags!=null)shaderProg.setTexture("ClipFlags",flags);
                shaderProg.setVar("clipFlagsU",flags!=null?1:0);
                shaderProg.setVar("clipLoU",cc.lo);shaderProg.setVar("clipHiU",cc.hi);
                shaderProg.setVar("toRenderU",toRender(p));
                shaderProg.setVar("blockU",block);
                shaderProg.drawBlocks(weights);
            }
            shaderProg.closed=true;
            // P602 (owner, 2026-11-10, Pixel 7 log of 31249, the debug log on): the debug log read the block statistics here - a
            // wait for the GPU in the middle of ScamRgb (scamrgb's full-size pass first) - and dropped an empty map, so a logged
            // shot ran another WhyCombine variant (no HL_OPPOSED) than the same shot unlogged: a program of its own to compile and
            // a node time no unlogged shot has. Now the logged shot is processed as the unlogged one (the map on the GPU, an empty one
            // a no-op in why/combine: nearOpp 0) and its statistics are read for the log line at the end of the run
            // (GLDeferredRead: queued here, mapped when the GPU is done). scam_dev hybrid_highlight_landing_async 0: read here and an
            // empty map dropped, as before.
            final boolean readStats=!PreferenceKeys.hybridSwitch("highlight_landing_async",true);
            final boolean logLater=!readStats&&com.scamera.app.util.ScameraDebugLog.isEnabled();
            int filled=-1;
            double glowW=0,glowS=0;
            if(readStats){
                final java.nio.ByteBuffer b=java.nio.ByteBuffer.allocateDirect(coarse.x*coarse.y*16).order(java.nio.ByteOrder.nativeOrder());
                weights.textureBuffer(cells,b);
                b.rewind();
                final double[] st=opposedStats(b.asFloatBuffer(),coarse.x*coarse.y);
                filled=(int)st[0];glowW=st[1];glowS=st[2];
                if(filled==0){
                    com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight opposed landing: no block of "+coarse.x+"x"+coarse.y+"@"+block+" filled");
                    return null;
                }
            }
            final float radius=OPPOSED_RADIUS*shorter/block;
            // each block's light by its nearest glow: along x, then along y with the gate
            sums=new GLTexture(coarse,cells,null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            shaderProg.setDefine("MODE",3);
            shaderProg.useAssetProgram("scamhdr/suncore",false);
            shaderProg.setTexture("InputBuffer",weights);
            shaderProg.setVar("dirU",1,0);shaderProg.setVar("radiusU",radius);shaderProg.setVar("finalU",0);shaderProg.setVar("glowFullU",0.1f*block*block);
            shaderProg.drawBlocks(sums);
            gates=new GLTexture(coarse,two,null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            shaderProg.setDefine("MODE",3);
            shaderProg.useAssetProgram("scamhdr/suncore",false);
            shaderProg.setTexture("InputBuffer",sums);
            shaderProg.setVar("dirU",0,1);shaderProg.setVar("radiusU",radius);shaderProg.setVar("finalU",1);shaderProg.setVar("glowFullU",0.1f*block*block);
            shaderProg.drawBlocks(gates);
            half=new GLTexture(coarse,two,null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            shaderProg.setDefine("MODE",1);
            shaderProg.useAssetProgram("scamhdr/suncore",false);
            shaderProg.setTexture("InputBuffer",gates);
            shaderProg.setVar("dirU",1,0);shaderProg.setVar("radiusU",radius);
            shaderProg.drawBlocks(half);
            out=new GLTexture(coarse,two,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            shaderProg.setDefine("MODE",1);
            shaderProg.useAssetProgram("scamhdr/suncore",false);
            shaderProg.setTexture("InputBuffer",half);
            shaderProg.setVar("dirU",0,1);shaderProg.setVar("radiusU",radius);
            shaderProg.drawBlocks(out);
            shaderProg.closed=true;
            if(readStats)com.scamera.app.util.Log.i("SCAM_PIPELINE",opposedStatsLine(filled,coarse,block,radius,flags!=null,glowW,glowS));
            else if(logLater&&queueOpposedStats(weights,coarse,block,radius,flags!=null)){} // P602: the line at the end of the run
            else com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight opposed landing: GPU only "+coarse.x+"x"+coarse.y+"@"+block+", radius "+radius);
            final GLTexture done=out;out=null;
            return done;
        }finally{
            if(weights!=null)weights.close();
            if(sums!=null)sums.close();
            if(gates!=null)gates.close();
            if(half!=null)half.close();
            if(out!=null)out.close();
        }
    }

    /** P378: the landing's statistics line (P602: also from the deferred read of a logged shot). */
    static String opposedStatsLine(int filled,Point coarse,int block,float radius,boolean withFlags,double glowW,double glowS){
        return "highlight opposed landing: "+filled+" of "+coarse.x+"x"+coarse.y+"@"+block
                +" blocks filled, radius "+radius+(withFlags?"":" (no clip flags)")+", glow saturation "
                +(glowW>0?String.format(java.util.Locale.ROOT,"%.3f over %.0f px",glowS/glowW,glowW):"none");
    }

    /** P378: {filled blocks, glow weight, glow weight x saturation} of the landing's weights (RGBA floats, a block a texel). */
    static double[] opposedStats(java.nio.FloatBuffer v,int cells){
        int filled=0;
        double glowW=0,glowS=0;
        for(int i=0;i<cells;i++){
            if(v.get(4*i)>0f)filled++;
            glowW+=v.get(4*i+3);glowS+=v.get(4*i+3)*v.get(4*i+2);
        }
        return new double[]{filled,glowW,glowS};
    }

    /** P602: the weights' statistics of a logged shot read at the end of the run (GLDeferredRead); false: not queued. */
    private static boolean queueOpposedStats(GLTexture weights,Point coarse,int block,float radius,boolean withFlags){
        final Point grid=new Point(coarse);
        return com.scamera.app.processing.opengl.GLDeferredRead.queue(weights,android.opengl.GLES30.GL_FLOAT,16,rgba->{
            final double[] st=opposedStats(rgba.asFloatBuffer(),grid.x*grid.y);
            com.scamera.app.util.Log.i("SCAM_PIPELINE",opposedStatsLine((int)st[0],grid,block,radius,withFlags,st[1],st[2])
                    +" (read at the end of the run, the map drawn either way)");
        });
    }

    /** P378 review: camera RGB (scamrgb's input) to the render's linear sRGB - why/low's chain (WhyTone.colourMatrix x sensorToProPhoto). */
    static float[] toRender(PostPipeline p){
        final float[] m=new float[9];
        com.scamera.app.processing.render.Converter.multiply(WhyTone.colourMatrix(p),p.shotParams.cameraToProPhoto,m);
        return m;
    }

    /**
     * Per-pixel clip flags of the last hybrid merge, or null: uint8 per OUTPUT pixel (the worker's clipFlags trailer,
     * scam-hybrid.h): bits 0-2 = R/G/B from the clipped mean, 3 = a clipped sample was excluded nearby, 4 = inside
     * the Bento mask, 5 = the clipped mean holds the ultrashort frame. Set by the client that reads the worker result
     * (null at the start of every merge), consumed and cleared by the next hybrid post-processing. Optional: without it
     * the clip level comes from the values alone (Bento shots: only the ultrashort's clip k is recovered).
     */
    public static volatile java.nio.ByteBuffer lastClipFlags;
    /**
     * P187: the clip flags as the client reads them, native memory (Allocator) instead of the Java heap - at 200 MP 200 MB
     * that a GC returned whenever it ran, through the whole pipeline. Freed right after their upload (glTexSubImage2D has
     * copied them on return); {@link #lastClipFlags} is this buffer until ScamRgb takes it.
     */
    private static java.nio.ByteBuffer nativeClipFlags;

    /**
     * P187 (owner, 2026-10-17: less memory at full resolution, the same picture): above 100 MP each big texture of the
     * pipeline goes back right after its last reader instead of at the end of its node or of the shot. scam_dev
     * "early_release 1" does the same at any size, for a post_ab A/B of the same pixels; the old GL model (post_ab's
     * reference run) keeps everything.
     */
    static boolean earlyRelease(Point size){
        return size!=null&&!com.scamera.app.processing.opengl.PostGlMode.legacy()
                &&((long)size.x*size.y>100_000_000L||PreferenceKeys.scamDevSwitch("early_release",false));
    }

    /** P187: the clip flags the client read into native memory (any earlier ones are dropped first). */
    static synchronized void keepNativeClipFlags(java.nio.ByteBuffer flags){
        dropClipFlags();
        nativeClipFlags=flags;
        lastClipFlags=flags;
    }

    /**
     * P187: the end of the shot whose merge returned {@code flags}: dropped (and freed when native) unless ScamRgb took them
     * already or they are another shot's by now.
     */
    public static synchronized void dropClipFlags(java.nio.ByteBuffer flags){
        if(flags==null)return;
        if(lastClipFlags==flags)lastClipFlags=null;
        freeClipFlags(flags);
    }

    /** P187: the clip flags of no shot any more (the next shot's read, a CPU fallback): freed when they are native. */
    static synchronized void dropClipFlags(){
        final java.nio.ByteBuffer flags=lastClipFlags;
        lastClipFlags=null;
        freeClipFlags(flags);
    }

    /** P187: gives back the native clip flags once they are uploaded or ignored; a Java buffer is left to the GC. */
    static synchronized void freeClipFlags(java.nio.ByteBuffer flags){
        if(flags==null||flags!=nativeClipFlags)return;
        nativeClipFlags=null;
        if(lastClipFlags==flags)lastClipFlags=null;
        com.scamera.app.util.Allocator.free(flags);
    }

    /**
     * P187: PostAb hands one set of clip flags to two runs, and ScamRgb frees native ones after the first: a Java copy of
     * them for both runs instead (PostAb runs only up to 16 MP).
     */
    static synchronized java.nio.ByteBuffer heapClipFlags(){
        final java.nio.ByteBuffer flags=lastClipFlags;
        if(flags==null||flags!=nativeClipFlags)return flags;
        final java.nio.ByteBuffer copy=java.nio.ByteBuffer.allocateDirect(flags.capacity());
        final java.nio.ByteBuffer all=flags.duplicate();
        all.clear();
        copy.put(all);
        copy.flip();
        freeClipFlags(flags);
        lastClipFlags=copy;
        return copy;
    }
    /**
     * White-balanced level (same units as this node's output) of the clipped white of the last hybrid shot: where every
     * channel clipped the recovery outputs a neutral white at about this level (Bento: k times the base white). 0 when
     * nothing could clip. For the tone stages that place the brightest data at white.
     */
    public static volatile float lastClipWhite;
    /** The shot's RGB has real clipped highlights (a clip plateau of the base or Bento frames): the WHY tone may use the
     *  Bento ceiling. False when the data only exceeds 1 by the white balance of unclipped saturated colour. */
    public static volatile boolean lastRealClip;

    /**
     * Per-channel level where the SCAM output saturates (the plateau of blown
     * windows/sky). Channels clip at different scene levels, so after white
     * balance a clipped highlight turned pink; the shader neutralises it near
     * this level. No plateau (nothing clipped): effectively disabled.
     * SCAM HDR (SCAM network) route only; the hybrid uses {@link #channelClip}.
     */
    private static float[] clipLevels(java.nio.ByteBuffer rgb){
        java.nio.FloatBuffer f=rgb.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        int n=f.limit()/3;float[] max=new float[3];
        for(int i=0;i<n;i+=17)for(int c=0;c<3;c++)max[c]=Math.max(max[c],f.get(i*3+c));
        int near=0,total=0;
        for(int i=0;i<n;i+=17){total++;
            for(int c=0;c<3;c++)if(max[c]>0&&f.get(i*3+c)>=0.97f*max[c]){near++;break;}}
        boolean plateau=total>0&&near>=total/2000;
        com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight clip levels="+max[0]+","+max[1]+","+max[2]
                +" plateau="+plateau+" ("+near+"/"+total+")");
        if(!plateau)return new float[]{1e30f,1e30f,1e30f};
        return max;
    }

    /** Clip levels of the hybrid merge per camera channel, raw units (base-frame white = 1, before WB and lens shading). */
    static final class ChannelClip {
        /** Base frames' clip (about 1.0) and the Bento ultrashort's clip (about k); all <= 0: that level is not used. */
        final float[] lo = new float[3], hi = new float[3];
        /** Sampled maximum per channel. */
        final float[] max = new float[3];
        /** Some sample reaches 85 % of a used level: the local chroma statistics are worth their pass. */
        boolean nearClip;
        /**
         * P58: the ultrashort clipped below the worker's flag threshold (a measured hi plateau under 0.975 k with no
         * ultrashort clip flagged): inside the Bento mask a pixel without flags may still sit at the hi clip (clipHiUnflaggedU).
         */
        boolean hiUnflagged;
        String log = "";
        boolean enabled() { return lo[1] > 0f || hi[1] > 0f; }
    }

    static final float HIST_T0 = 0.80f, HIST_BIN = 0.002f;
    static final int HIST_BINS = 150; // [0.80, 1.10) of the expected white
    private static final int PLATEAU_WINDOW = 5;

    /**
     * Centre of the clip plateau in units of the expected white (1.0 for the base frames, k for the Bento ultrashort):
     * clipped means pile up in a narrow spike just below that white, while real content thins out towards it. The
     * spike is the densest 1 % window in [searchLo, searchHi) and must stand well above the density of [0.80, 0.90);
     * its position is the mean of the samples inside it ({@code sum}: per-bin sums of the values, same units).
     * NaN: no plateau.
     */
    static float plateau(int[] h, double[] sum, long samples, float searchLo, float searchHi) {
        final int bg1 = Math.round((0.90f - HIST_T0) / HIST_BIN);
        long bg = 0;
        for (int b = 0; b < bg1; b++) bg += h[b];
        final double bgPerBin = bg / (double) Math.max(1, bg1);
        final int w = PLATEAU_WINDOW;
        final int s0 = Math.max(bg1, Math.round((searchLo - HIST_T0) / HIST_BIN));
        final int s1 = Math.min(HIST_BINS, Math.round((searchHi - HIST_T0) / HIST_BIN)) - w;
        int best = -1;
        long bestSum = 0;
        for (int b = s0; b <= s1; b++) {
            long s = 0;
            for (int j = 0; j < w; j++) s += h[b + j];
            if (s > bestSum) { bestSum = s; best = b; }
        }
        final double expected = w * bgPerBin;
        final long need = Math.max(16L, (long) Math.ceil(samples * 5e-6));
        if (best < 0 || bestSum < need || bestSum < 6.0 * expected + 4.0 * Math.sqrt(expected + 1.0)) return Float.NaN;
        double m = 0;
        for (int j = 0; j < w; j++) m += sum[best + j];
        return (float) (m / bestSum);
    }

    private static void histogram(int[] h, double[] sum, float t) {
        // P364: a sample below the first bin (most of them) leaves at once: for every t < 0.8 the bin below is negative (t - 0.8 is
        // negative and never rounds to 0), so nothing is counted, as before; a NaN still takes the old path (bin 0)
        if (t < HIST_T0) return;
        final int b = (int) Math.floor((t - HIST_T0) / HIST_BIN);
        if (b >= 0 && b < HIST_BINS) { h[b]++; sum[b] += t; }
    }

    /**
     * Per-channel clip levels of the hybrid merge. The nominal white of every channel is 1.0 after the worker's per-site
     * black/white normalisation (base frames) and k where the Bento ultrashort itself clipped; a histogram plateau near
     * either level confirms it and gives its exact position (a channel that saturates below the white level, a factor
     * that differs from the plan). Without the worker's clip flags a Bento shot uses only k: real ultrashort content
     * passes smoothly through 1.0 and must not be read as clipped there.
     */
    static ChannelClip channelClip(java.nio.ByteBuffer rgb, boolean bento, float k, float usClipped, boolean flags) {
        return channelClip(rgb, bento, k, usClipped, flags, ParallelWork.threads());
    }

    /** One part of the clip statistics: samples [s0, s1) of the every-17th-pixel walk. */
    private static final class ClipPart {
        final int[][] hLo = new int[3][HIST_BINS], hHi = new int[3][HIST_BINS];
        final double[][] sLo = new double[3][HIST_BINS], sHi = new double[3][HIST_BINS];
        final float[] max = new float[3];
    }

    /**
     * P48 (research/speed/PLAIN_SHOT_SPEED.md): the statistics in {@code parts} parts on {@link ParallelWork} (482 ms on one
     * core for the 50 MP Sabre 2x grid). The same result for any split: counts are integers, the maxima are maxima, and every
     * histogram sum is exact in double (its values lie in [0.8, 1.1), multiples of 2^-24: below 2^28 samples the sum needs
     * fewer than 53 bits), so the order of the additions does not matter. parts 1 = the single walk as before.
     */
    static ChannelClip channelClip(java.nio.ByteBuffer rgb, boolean bento, float k, float usClipped, boolean flags, int parts) {
        return channelClip(rgb, bento, k, usClipped, flags, parts, false);
    }
    /** P80: half = the RGB is IEEE half floats (the worker's rgb-half output): the same walk on the widened values. */
    static ChannelClip channelClip(java.nio.ByteBuffer rgb, boolean bento, float k, float usClipped, boolean flags, int parts, boolean half) {
        ChannelClip cc = new ChannelClip();
        final java.nio.ByteBuffer view = rgb.duplicate().order(java.nio.ByteOrder.nativeOrder());
        final int n = (half ? view.asShortBuffer().limit() : view.asFloatBuffer().limit()) / 3;
        final float inv = bento && k > 1f ? 1f / k : 0f;
        final long samples = (n + 16L) / 17L;
        final int chunks = parts <= 1 || samples < 65536 ? 1 : 4 * parts;
        final ClipPart[] part = new ClipPart[chunks];
        final java.util.function.IntConsumer walk = c -> {
            final ClipPart p = new ClipPart();
            final java.nio.FloatBuffer f = half ? null : view.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            final java.nio.ShortBuffer hb = half ? view.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer() : null;
            final long s1 = samples * (c + 1) / chunks;
            for (long s = samples * c / chunks; s < s1; s++) {
                final int i = (int) (s * 17L);
                for (int ch = 0; ch < 3; ch++) {
                    final float v = half ? HalfFloat.TABLE[hb.get(i * 3 + ch) & 0xffff] : f.get(i * 3 + ch);
                    if (v > p.max[ch]) p.max[ch] = v;
                    histogram(p.hLo[ch], p.sLo[ch], v);
                    if (inv > 0f) histogram(p.hHi[ch], p.sHi[ch], v * inv);
                }
            }
            part[c] = p;
        };
        if (chunks == 1) walk.accept(0); else ParallelWork.forEach(chunks, walk);
        final int[][] hLo = new int[3][HIST_BINS], hHi = new int[3][HIST_BINS];
        final double[][] sLo = new double[3][HIST_BINS], sHi = new double[3][HIST_BINS];
        for (ClipPart p : part)
            for (int c = 0; c < 3; c++) {
                if (p.max[c] > cc.max[c]) cc.max[c] = p.max[c];
                for (int b = 0; b < HIST_BINS; b++) {
                    hLo[c][b] += p.hLo[c][b]; sLo[c][b] += p.sLo[c][b];
                    hHi[c][b] += p.hHi[c][b]; sHi[c][b] += p.sHi[c][b];
                }
            }
        return clipFrom(cc, hLo, sLo, hHi, sHi, samples, bento, k, usClipped, flags);
    }

    /** The levels of {@link #channelClip} from its histograms and maxima (cc.max set): the CPU walk and the GPU one (P482). */
    static ChannelClip clipFrom(ChannelClip cc, int[][] hLo, double[][] sLo, int[][] hHi, double[][] sHi, long samples, boolean bento,
                                float k, float usClipped, boolean flags) {
        final boolean useLo = !bento || flags;
        final boolean useHi = bento && k > 1f;
        StringBuilder logLo = new StringBuilder(), logHi = new StringBuilder();
        final boolean[] hiMeasured = new boolean[3];
        float hiPlateauMax = 0f, hiPlateauMinFrac = Float.MAX_VALUE;
        for (int c = 0; c < 3; c++) {
            float lo = 0f, hi = 0f;
            if (useLo) {
                final float s = plateau(hLo[c], sLo[c], samples, 0.92f, 1.03f);
                lo = Float.isNaN(s) ? 1f : s;
                logLo.append(c == 0 ? "lo=" : ",").append(lo).append(Float.isNaN(s) ? "(nominal)" : "(plateau)");
            }
            if (useHi) {
                final float s = plateau(hHi[c], sHi[c], samples, 0.90f, 1.08f);
                // No plateau and the ultrashort never clipped: nothing in the data reaches k, the level is unused.
                hi = !Float.isNaN(s) ? s * k : (usClipped > 0f ? k : 0f);
                if (!Float.isNaN(s)) { hiMeasured[c] = true; hiPlateauMax = Math.max(hiPlateauMax, hi); hiPlateauMinFrac = Math.min(hiPlateauMinFrac, s); }
                logHi.append(c == 0 ? "hi=" : ",").append(hi)
                        .append(!Float.isNaN(s) ? "(plateau)" : hi > 0f ? "(nominal)" : "(off)");
            }
            cc.lo[c] = lo;
            cc.hi[c] = hi;
        }
        final String log = (logLo.length() > 0 && logHi.length() > 0 ? logLo + " " + logHi : logLo.toString() + logHi);
        // A level is used for all three channels or for none (the shader divides by every component).
        if (cc.hi[1] <= 0f) java.util.Arrays.fill(cc.hi, 0f);
        else for (int c = 0; c < 3; c++) if (cc.hi[c] <= 0f) {
            // P58: a channel without a plateau next to measured ones (OnePlus 15: R, G at 0.915 k, B "off") takes its own
            // sampled maximum, never below the measured clips and never above k: k (as before) left the blue of a blown
            // sky below its level, so R and G were recovered and B was not -> pink sky with white blotches.
            cc.hi[c] = hiPlateauMax > 0f ? Math.min(k, Math.max(hiPlateauMax, cc.max[c])) : k;
        }
        cc.hiUnflagged = cc.hi[1] > 0f && usClipped <= 0f && hiPlateauMinFrac < 0.975f;
        for (int c = 0; c < 3; c++)
            if ((cc.lo[1] > 0f && cc.max[c] >= 0.85f * cc.lo[c]) || (cc.hi[1] > 0f && cc.max[c] >= 0.85f * cc.hi[c])) cc.nearClip = true;
        cc.log = log + (cc.hiUnflagged ? " hiUnflagged" : "") + " max=" + cc.max[0] + "," + cc.max[1] + "," + cc.max[2] + " samples=" + samples;
        return cc;
    }

    /**
     * SCAM Hybrid: the worker's signed RGB passes through this node unclamped (scamrgb signedU). The merge keeps its noise
     * signed and clips once; a per-pixel max(0) here lifted the mean of every channel near zero by up to half its noise
     * (x1.5 red along the frame edge of a dark teal curtain where fewer donors overlap, OPPO X8U 2026-10-07), and the WHY
     * tone turned that into a red band and red mottling. The stages after it clamp only after the noise reduction or an
     * average. The SCAM HDR route keeps the clamp. scam_dev.txt "post_ab_clamp 1": the old run of post_ab (PostAb) gets the
     * former per-pixel clamp, so the A/B shows this change on the same worker result.
     */
    static boolean signedInput(boolean hybrid) {
        return hybrid && !(PostGlMode.legacy() && PreferenceKeys.scamDevSwitch("post_ab_clamp", false));
    }

    static boolean halfInput(boolean hybrid, boolean halfTransport, int w, int h, long bytes) {
        final long pixels = (long) w * h;
        return hybrid && halfTransport && w > 0 && h > 0 && pixels <= Long.MAX_VALUE / 12L
                && bytes >= pixels * 6L && bytes < pixels * 12L;
    }

    private static void requireTextureStorage(GLTexture texture) {
        final int[] size = new int[2];
        glBindTexture(GL_TEXTURE_2D, texture.id);
        android.opengl.GLES31.glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, android.opengl.GLES31.GL_TEXTURE_WIDTH, size, 0);
        android.opengl.GLES31.glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, android.opengl.GLES31.GL_TEXTURE_HEIGHT, size, 1);
        if (size[0] != texture.size.x || size[1] != texture.size.y)
            throw new IllegalStateException(com.scamera.app.util.Lang.t("SHDR: GPU не смог выделить текстуру склейки 2× ",
                    "SHDR: GPU could not allocate the Sabre 2x texture ") + texture.size);
        texture.renderInto();
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException(com.scamera.app.util.Lang.t("SHDR: GPU не поддерживает обработку текстуры склейки 2× ",
                    "SHDR: GPU cannot render the Sabre 2x texture ") + texture.size);
    }

    /** Every `stride`-th pixel of the linear RGB: enough for the percentile statistics of the later nodes. */
    private static java.nio.ByteBuffer decimate(java.nio.ByteBuffer rgb,int stride,boolean half){
        // P80: a half-float RGB gives the same float32 copy (widened) to the later nodes
        if(half){
            java.nio.ShortBuffer f=rgb.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            int n=f.limit()/3,m=(n+stride-1)/stride;
            java.nio.ByteBuffer out=java.nio.ByteBuffer.allocateDirect(m*12).order(java.nio.ByteOrder.nativeOrder());
            java.nio.FloatBuffer o=out.asFloatBuffer();
            // P147: in chunks on the worker threads (140 ms on one thread for the 50 MP grid, the slowest part of the input
            // once the repack went to the GPU); absolute reads and writes, each output sample written once
            final int chunk=1<<15,count=(m+chunk-1)/chunk;
            ParallelWork.forEach(count,j->{
                final int k0=j*chunk,k1=Math.min(m,k0+chunk);
                for(int k=k0;k<k1;k++){final int i=k*stride;for(int c=0;c<3;c++)o.put(k*3+c,HalfFloat.TABLE[f.get(i*3+c)&0xffff]);}
            });
            return out;
        }
        java.nio.FloatBuffer f=rgb.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        int n=f.limit()/3,m=(n+stride-1)/stride;
        java.nio.ByteBuffer out=java.nio.ByteBuffer.allocateDirect(m*12).order(java.nio.ByteOrder.nativeOrder());
        java.nio.FloatBuffer o=out.asFloatBuffer();
        for(int i=0,k=0;i<n;i+=stride,k++){o.put(k*3,f.get(i*3));o.put(k*3+1,f.get(i*3+1));o.put(k*3+2,f.get(i*3+2));}
        return out;
    }
    private static final ThreadLocal<short[][]> REPACK_HALF_ROW = ThreadLocal.withInitial(() -> new short[][]{new short[0], new short[0]});
    /** P80: rows [y0, y0 + rows) of the half-float RGB as RGBA16F (alpha 1.0 = 0x3C00) into dst from its start, the same bits. */
    static void halfRgbToRgbaRows(java.nio.ByteBuffer rgb, int w, int y0, int rows, java.nio.ByteBuffer dst) {
        final java.nio.ByteBuffer src = rgb.duplicate().order(java.nio.ByteOrder.nativeOrder());
        final java.nio.ByteBuffer out = dst.duplicate().order(java.nio.ByteOrder.nativeOrder());
        ParallelWork.forEach(rows, r -> {
            short[][] t = REPACK_HALF_ROW.get();
            if (t[0].length < 3 * w) { t[0] = new short[3 * w]; t[1] = new short[4 * w]; }
            final short[] a3 = t[0], a4 = t[1];
            final java.nio.ShortBuffer s = src.asShortBuffer();
            s.position((y0 + r) * 3 * w);
            s.get(a3, 0, 3 * w);
            for (int x = 0, i = 0, o = 0; x < w; x++, i += 3, o += 4) {
                a4[o] = a3[i]; a4[o + 1] = a3[i + 1]; a4[o + 2] = a3[i + 2]; a4[o + 3] = (short) 0x3C00;
            }
            final java.nio.ShortBuffer d = out.asShortBuffer();
            d.position(r * 4 * w);
            d.put(a4, 0, 4 * w);
        });
    }
    /**
     * P80: the half-float RGB uploaded as RGBA16F in row bands of ~16 MB (alpha 1.0), the next band repacked while the driver
     * takes the current one (as {@link #uploadRgba}); the first stage reads .rgb. Null when the staging memory is not there.
     */
    static GLTexture uploadHalfRgba(Point size, java.nio.ByteBuffer rgb) {
        final int w = size.x, h = size.y;
        final int band = Math.max(1, Math.min(h, (16 << 20) / (8 * w)));
        final java.nio.ByteBuffer[] stage = new java.nio.ByteBuffer[2];
        stage[0] = com.scamera.app.util.Allocator.allocate(band * w * 8);
        stage[1] = stage[0] == null ? null : com.scamera.app.util.Allocator.allocate(band * w * 8);
        if (stage[1] == null) {
            if (stage[0] != null) com.scamera.app.util.Allocator.free(stage[0]);
            return null;
        }
        final java.util.concurrent.ExecutorService repack = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SCAMERA-rgba16-repack");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.Future<?> next = null;
        GLTexture tex = null;
        try {
            tex = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            next = repack.submit(() -> halfRgbToRgbaRows(rgb, w, 0, Math.min(band, h), stage[0]));
            for (int y0 = 0, k = 0; y0 < h; y0 += band, k++) {
                next.get();
                next = null;
                final int rows = Math.min(band, h - y0), y1 = y0 + band, k1 = k + 1;
                if (y1 < h) next = repack.submit(() -> halfRgbToRgbaRows(rgb, w, y1, Math.min(band, h - y1), stage[k1 & 1]));
                glBindTexture(GL_TEXTURE_2D, tex.id);
                stage[k & 1].position(0);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y0, w, rows, GL_RGBA, android.opengl.GLES30.GL_HALF_FLOAT, stage[k & 1]);
            }
            return tex;
        } catch (java.util.concurrent.ExecutionException e) {
            if (tex != null) tex.close();
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            if (tex != null) tex.close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while uploading the RGB", e);
        } finally {
            if (next != null) try { next.get(); } catch (Exception ignored) {}
            repack.shutdown();
            com.scamera.app.util.Allocator.free(stage[0]);
            com.scamera.app.util.Allocator.free(stage[1]);
        }
    }
    private static final ThreadLocal<int[][]> REPACK_ROW = ThreadLocal.withInitial(() -> new int[][]{new int[0], new int[0]});

    /**
     * P48: rows [y0, y0 + rows) of the w-wide RGB float32 buffer as RGBA (alpha 1.0) into dst from its start: the same bits
     * (copied as raw ints), the rows on {@link ParallelWork}.
     */
    static void rgbToRgbaRows(java.nio.ByteBuffer rgb, int w, int y0, int rows, java.nio.ByteBuffer dst) {
        final java.nio.ByteBuffer src = rgb.duplicate().order(java.nio.ByteOrder.nativeOrder());
        final java.nio.ByteBuffer out = dst.duplicate().order(java.nio.ByteOrder.nativeOrder());
        out.position(0);
        final int one = Float.floatToRawIntBits(1f);
        ParallelWork.forEach(rows, r -> {
            int[][] t = REPACK_ROW.get();
            if (t[0].length < 3 * w) { t[0] = new int[3 * w]; t[1] = new int[4 * w]; }
            final int[] a3 = t[0], a4 = t[1];
            final java.nio.IntBuffer s = src.asIntBuffer();
            s.position((y0 + r) * 3 * w);
            s.get(a3, 0, 3 * w);
            for (int x = 0, i = 0, o = 0; x < w; x++, i += 3, o += 4) {
                a4[o] = a3[i]; a4[o + 1] = a3[i + 1]; a4[o + 2] = a3[i + 2]; a4[o + 3] = one;
            }
            final java.nio.IntBuffer d = out.asIntBuffer();
            d.position(r * 4 * w);
            d.put(a4, 0, 4 * w);
        });
    }

    /**
     * P48: the RGB result uploaded as RGBA32F in row bands of ~32 MB (alpha 1.0), the next band repacked while the driver takes
     * the current one. On Adreno the RGB32F upload is a CPU repack in the driver: 2240 ms for the 50 MP Sabre 2x grid on the
     * vivo X200 Ultra (Adreno 830), 230-258 ms at 12 MP on the Find X7 Ultra. scamrgb and chanprep read .rgb with texelFetch, so
     * the texture gives the same floats. Default on Adreno; scam_dev.txt "rgba_upload 0 / 1" overrides. Null when the staging
     * memory is not there (the caller uploads RGB32F as before).
     */
    static GLTexture uploadRgba(Point size, java.nio.ByteBuffer rgb) {
        final int w = size.x, h = size.y;
        final int band = Math.max(1, Math.min(h, (32 << 20) / (16 * w)));
        final java.nio.ByteBuffer[] stage = new java.nio.ByteBuffer[2];
        stage[0] = com.scamera.app.util.Allocator.allocate(band * w * 16);
        stage[1] = stage[0] == null ? null : com.scamera.app.util.Allocator.allocate(band * w * 16);
        if (stage[1] == null) {
            if (stage[0] != null) com.scamera.app.util.Allocator.free(stage[0]);
            return null;
        }
        final java.util.concurrent.ExecutorService repack = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SCAMERA-rgba-repack");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.Future<?> next = null;
        GLTexture tex = null;
        try {
            tex = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_32, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            next = repack.submit(() -> rgbToRgbaRows(rgb, w, 0, Math.min(band, h), stage[0]));
            for (int y0 = 0, k = 0; y0 < h; y0 += band, k++) {
                next.get();
                next = null;
                final int rows = Math.min(band, h - y0), y1 = y0 + band, k1 = k + 1;
                if (y1 < h) next = repack.submit(() -> rgbToRgbaRows(rgb, w, y1, Math.min(band, h - y1), stage[k1 & 1]));
                glBindTexture(GL_TEXTURE_2D, tex.id);
                stage[k & 1].position(0);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y0, w, rows, GL_RGBA, GL_FLOAT, stage[k & 1]);
            }
            return tex;
        } catch (java.util.concurrent.ExecutionException e) {
            if (tex != null) tex.close();
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            if (tex != null) tex.close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while uploading the RGB", e);
        } finally {
            if (next != null) try { next.get(); } catch (Exception ignored) {} // never free a buffer the repack still writes
            repack.shutdown();
            com.scamera.app.util.Allocator.free(stage[0]);
            com.scamera.app.util.Allocator.free(stage[1]);
        }
    }

    /** P48: whether the RGB goes up as RGBA32F bands ({@link #uploadRgba}): scam_dev.txt "rgba_upload", else on Adreno. */
    static boolean rgbaUploadWanted(String renderer) {
        // post_ab's old run is the pipeline before the speed work: the RGB32F upload; "post_ab_upload" runs the old run in the
        // new model with only this upload changed (PostAb.forceRgbUpload)
        if (com.scamera.app.processing.opengl.PostGlMode.legacy() || PostAb.forceRgbUpload) return false;
        if (PreferenceKeys.scamDevOverrides("rgba_upload")) return PreferenceKeys.scamDevSwitch("rgba_upload", false);
        return renderer != null && renderer.contains("Adreno");
    }

    /** P48: what the helper thread computes from the big RGB while the GL thread uploads it (both only read it). */
    private static final class InputStats {
        ChannelClip cc;
        float[] clip;
        /** P362: pixels with a clipped channel and no ultrashort content (clip flags), -1: no flags. */
        long oppPixels = -1;
        int[] oppGroups;
        java.nio.ByteBuffer decimated;
        long statMs, decimateMs;
    }

    /** The helper's result; its exception is rethrown here. */
    private static <T> T await(java.util.concurrent.FutureTask<T> task) {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    interrupted = true; // the helper reads the buffer this thread frees next: wait for it in any case
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(java.util.concurrent.FutureTask<?> task) {
        try { await(task); } catch (RuntimeException | Error ignored) {}
    }

    /**
     * P362: the «Inpaint opposed» chrominance correction per channel (white-balanced; hlrecovery/opposed): the mean of value -
     * opposed reference over the unclipped pixels around the clips of that channel. Null when the compute pass fails (the
     * caller uses 0). One 16 x 16 group per tile writes its sums and counts, added here.
     */
    private float[] opposedChroma(GLTexture input,GLTexture gainMap,GLTexture flagsTex,float[] whitePoint,ChannelClip cc,Point size,int ox,int oy,int[] groups){
        if(groups!=null&&flagsTex!=null&&ox==0&&oy==0&&input.size.equals(size)&&flagsTex.size.equals(size)){
            try{
                final float[] chroma=opposedChromaPass(input,gainMap,flagsTex,whitePoint,cc,size,ox,oy,groups);
                if(chroma!=null)return chroma;
            }catch(OutOfMemoryError failure){
                com.scamera.app.util.Log.w("SCAM_PIPELINE","highlight opposed sparse: "+failure);
            }
            com.scamera.app.util.Log.w("SCAM_PIPELINE","highlight opposed sparse: retry full grid");
        }
        return opposedChromaPass(input,gainMap,flagsTex,whitePoint,cc,size,ox,oy,null);
    }

    private float[] opposedChromaPass(GLTexture input,GLTexture gainMap,GLTexture flagsTex,float[] whitePoint,ChannelClip cc,Point size,int ox,int oy,int[] groups){
        final long t0=System.currentTimeMillis();
        final int gx=(size.x+15)/16,gy=(size.y+15)/16;
        while(android.opengl.GLES31.glGetError()!=android.opengl.GLES31.GL_NO_ERROR){
            // drain: only the pass below decides
        }
        try(com.scamera.app.processing.opengl.GLBuffer sums=new com.scamera.app.processing.opengl.GLBuffer(6*gx*gy,
                new GLFormat(GLFormat.DataType.UNSIGNED_32));
            com.scamera.app.processing.opengl.GLBuffer work=groups!=null?new com.scamera.app.processing.opengl.GLBuffer(groups.length,
                    new GLFormat(GLFormat.DataType.UNSIGNED_32)):null){
            if(work!=null){
                work.clientCopy.asIntBuffer().put(groups);
                android.opengl.GLES31.glBindBuffer(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,work.bufferId);
                android.opengl.GLES31.glBufferSubData(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,0,work.bytes,work.clientCopy);
                final int[] bytes=new int[1];
                android.opengl.GLES31.glGetBufferParameteriv(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,android.opengl.GLES31.GL_BUFFER_SIZE,bytes,0);
                if(android.opengl.GLES31.glGetError()!=android.opengl.GLES31.GL_NO_ERROR||bytes[0]!=work.bytes)
                    throw new IllegalStateException("opposed worklist upload failed");
            }
            shaderProg.setLayout(16,16,1);
            shaderProg.setDefine("SPARSE",groups!=null?1:0);
            shaderProg.useAssetProgram("hlrecovery/opposed",true);
            shaderProg.setTexture("InputBuffer",input);shaderProg.setTexture("GainMap",gainMap);
            if(flagsTex!=null)shaderProg.setTexture("ClipFlags",flagsTex);
            shaderProg.setVar("whitePoint",whitePoint);
            shaderProg.setVar("inverseSize",1f/size.x,1f/size.y);
            shaderProg.setVar("cropOffset",ox,oy);
            shaderProg.setVar("sizeU",size.x,size.y);
            shaderProg.setVar("clipLoU",cc.lo);shaderProg.setVar("clipHiU",cc.hi);
            shaderProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
            shaderProg.setVar("clipFlagsU",flagsTex!=null?1:0);
            shaderProg.setBufferCompute("oppSums",sums);
            if(work!=null){
                shaderProg.setBufferCompute("oppWorkGroups",work);
                shaderProg.setVar("occupiedCountU",groups.length);shaderProg.setVar("originalGroupsXU",gx);
                final int columns=Math.min(groups.length,65535);
                shaderProg.computeManual(columns,(groups.length+columns-1)/columns,1);
            } else shaderProg.computeManual(gx,gy,1);
            final int[] v=sums.readBufferIntegers(false);
            final int error=android.opengl.GLES31.glGetError();
            if(error!=android.opengl.GLES31.GL_NO_ERROR||v==null||v.length<6*gx*gy){
                com.scamera.app.util.Log.w("SCAM_PIPELINE","highlight opposed: GL error 0x"+Integer.toHexString(error)+", chrominance 0");
                return null;
            }
            final float[] chroma=opposedMeans(v,gx*gy);
            com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight opposed (RawTherapee inpaint opposed): chrominance "
                    +java.util.Arrays.toString(chroma)+(groups!=null?" sparse "+groups.length+"/"+(gx*gy):"")+" ms="+(System.currentTimeMillis()-t0));
            return chroma;
        }catch(RuntimeException e){
            com.scamera.app.util.Log.w("SCAM_PIPELINE","highlight opposed: "+e+", chrominance 0");
            return null;
        }
    }

    /**
     * P479b: {@link #opposedChromaPass}'s full grid with its sums added on the GPU (hlrecovery/opposed_mean) into a 1 x 1 rgba32f
     * texture for scamrgb (OPP_GPU); nothing waits for the GPU. Null when a GL call fails: the caller reads back as before.
     */
    private GLTexture opposedChromaGpu(GLTexture input,GLTexture gainMap,GLTexture flagsTex,float[] whitePoint,ChannelClip cc,Point size,int ox,int oy){
        final long t0=System.currentTimeMillis();
        final int gx=(size.x+15)/16,gy=(size.y+15)/16;
        while(android.opengl.GLES31.glGetError()!=android.opengl.GLES31.GL_NO_ERROR){
            // drain: only the passes below decide
        }
        GLTexture mean=null;
        try(com.scamera.app.processing.opengl.GLBuffer sums=new com.scamera.app.processing.opengl.GLBuffer(6*gx*gy,
                new GLFormat(GLFormat.DataType.UNSIGNED_32))){
            mean=new GLTexture(new Point(1,1),new GLFormat(GLFormat.DataType.FLOAT_32,4),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            shaderProg.setLayout(16,16,1);
            shaderProg.setDefine("SPARSE",0);
            shaderProg.useAssetProgram("hlrecovery/opposed",true);
            shaderProg.setTexture("InputBuffer",input);shaderProg.setTexture("GainMap",gainMap);
            if(flagsTex!=null)shaderProg.setTexture("ClipFlags",flagsTex);
            shaderProg.setVar("whitePoint",whitePoint);
            shaderProg.setVar("inverseSize",1f/size.x,1f/size.y);
            shaderProg.setVar("cropOffset",ox,oy);
            shaderProg.setVar("sizeU",size.x,size.y);
            shaderProg.setVar("clipLoU",cc.lo);shaderProg.setVar("clipHiU",cc.hi);
            shaderProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
            shaderProg.setVar("clipFlagsU",flagsTex!=null?1:0);
            shaderProg.setBufferCompute("oppSums",sums);
            shaderProg.computeManualAsync(gx,gy,1);
            shaderProg.setLayout(256,1,1);
            shaderProg.useAssetProgram("hlrecovery/opposed_mean",true);
            shaderProg.setBufferCompute("oppSums",sums);
            shaderProg.setVar("groupsU",gx*gy);
            shaderProg.setTextureCompute("outMean",mean,true);
            shaderProg.computeManualAsync(1,1,1);
            shaderProg.closed=true;
            final int error=android.opengl.GLES31.glGetError();
            if(error!=android.opengl.GLES31.GL_NO_ERROR){
                com.scamera.app.util.Log.w("SCAM_PIPELINE","highlight opposed (GPU mean): GL error 0x"+Integer.toHexString(error)+", read back");
                mean.close();
                return null;
            }
            com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight opposed (RawTherapee inpaint opposed): chrominance on the GPU (no read-back) ms="
                    +(System.currentTimeMillis()-t0));
            final GLTexture done=mean;mean=null;
            return done;
        }catch(RuntimeException e){
            com.scamera.app.util.Log.w("SCAM_PIPELINE","highlight opposed (GPU mean): "+e+", read back");
            if(mean!=null)mean.close();
            return null;
        }
    }

    /** P362: the pixels «Inpaint opposed» reconstructs: a clipped-mean channel (flag bits 0-2) and no ultrashort content (4, 5). */
    static long opposedPixels(java.nio.ByteBuffer flags){
        return opposedPixels(flags,null);
    }

    private static long opposedPixels(java.nio.ByteBuffer flags,OpposedWorkList work){
        return opposedPixels(flags,work,null);
    }

    /** P671: also the clip band's flagged tiles into {@code band} (its parts: {@link #flagParts}), in the same walk. */
    static long opposedPixels(java.nio.ByteBuffer flags,OpposedWorkList work,ClipBandTiles band){
        final java.nio.ByteBuffer f=flags.duplicate();
        final int n=f.capacity();
        final long[] counts=new long[flagParts()];
        final int parts=counts.length,step=((n+parts-1)/parts+7)&~7;
        ParallelWork.forEach(parts,k->{
            final int s=(int)Math.min(n,(long)k*step),e=(int)Math.min(n,(long)s+step);
            counts[k]=OpposedWorkList.countPart(f,s,e,work!=null?work.groupsX*16:0,work,k);
            if(band!=null)band.scan(f,s,e,k);
        });
        long t=0;for(long c:counts)t+=c;
        return t;
    }

    /** The parallel parts of the flags' walk ({@link #opposedPixels}). */
    static int flagParts(){
        return Math.max(1,4*ParallelWork.threads());
    }

    /**
     * P671: scam_dev scamrgb_band_tiles (default on; off or the old GL model: the clip band's two passes over every pixel as
     * before) - the band computed only in the tiles near a flagged pixel, skipped on a shot without one (ClipBandTiles).
     */
    static boolean bandTilesWanted(){
        return !com.scamera.app.processing.opengl.PostGlMode.legacy()&&PreferenceKeys.scamDevSwitch("scamrgb_band_tiles",true);
    }

    /** P362: per channel the sum over the groups' sums (float bits) by their counts; 0 for a channel with no pixel counted. */
    static float[] opposedMeans(int[] v,int groups){
        final double[] sum=new double[3];
        final long[] n=new long[3];
        for(int g=0;g<groups;g++){
            for(int c=0;c<3;c++){
                final float s=Float.intBitsToFloat(v[6*g+c]);
                if(Float.isFinite(s))sum[c]+=s;
                n[c]+=v[6*g+3+c]&0xFFFFFFFFL;
            }
        }
        final float[] out=new float[3];
        for(int c=0;c<3;c++)out[c]=n[c]>0?(float)(sum[c]/n[c]):0f;
        return out;
    }

    @Override public void runPass(){
        final java.nio.ByteBuffer flags=lastClipFlags;lastClipFlags=null;
        try{run(flags);}finally{freeClipFlags(flags);} // P187: uploaded, ignored, unused by this shot, or a failure
    }

    private void run(java.nio.ByteBuffer flags){
        PostPipeline p=(PostPipeline)hostPipeline;
        // Bento is applied only by the hybrid merge (reset for every SCAM burst): a safety net for the shot profile.
        final boolean hybrid=PreferenceKeys.isHybridShot()||ScamHybridBurst.lastBentoApplied;
        final Point size=p.shotParams.rawSize;
        final boolean largeTwoX=p.shotParams.outputScale>1f&&(long)size.x*size.y>64_000_000L;
        if(flags!=null&&(!hybrid||flags.capacity()!=(long)size.x*size.y)){
            com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight recovery: clip flags ignored (hybrid="+hybrid
                    +" bytes="+flags.capacity()+" expected="+(long)size.x*size.y+")");
            flags=null;
        }
        // P48: the clip statistics and the decimated copy on a helper thread while this thread uploads the big buffer (both
        // only read it); the big buffer is released once all three are done (2x grid: 604 MB). The same results as one after
        // the other (channelClip parallel: see there).
        float[] clip=null;ChannelClip cc=null;float strength=0f;
        final java.nio.ByteBuffer big=p.shotParams.scamRgb;
        final boolean owned=p.shotParams.scamRgbOwned;
        final boolean bento=ScamHybridBurst.lastBentoApplied;
        final float bentoK=ScamHybridBurst.lastBentoFactor,bentoUs=ScamHybridBurst.lastBentoUsClipped;
        final boolean withFlags=flags!=null;
        final java.nio.ByteBuffer flagsIn=flags;
        // P80: the worker's half-float RGB (6 B a pixel); its size confirms it
        final boolean half=big!=null&&halfInput(hybrid,ScamHybridBurst.lastRgbHalf,size.x,size.y,big.limit());
        if(hybrid)strength=Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_recovery",100f)/100f));
        final boolean sparseOpposed=PreferenceKeys.hybridSwitch("highlight_opposed_sparse",false);
        // P671: the clip band's flagged tiles, marked by the helper in the same walk of the flags
        final ClipBandTiles bandTiles=hybrid&&strength>0f&&flags!=null&&bandTilesWanted()
                ?ClipBandTiles.create(size.x,size.y,flags.capacity(),flagParts()):null;
        // P479 (speed): up to 16 MP the decimated copy and the release of the big buffer follow the statistics on the helper
        // thread (the release once this thread's upload is done with it, as before), so this thread goes on with the GL work as
        // soon as the statistics are in instead of waiting for the copy and spending the release (9 - 36 ms of munmap on the
        // OPPO) itself; the copy becomes the pipeline's scamRgb at the end of this node, before any later node asks for it.
        // The buffer goes back no later than before. Above 16 MP, scam_dev.txt "scamrgb_tail 0" or the old GL model: as before
        final boolean tail=owned&&big!=null&&(long)size.x*size.y<=16_000_000L
                &&!com.scamera.app.processing.opengl.PostGlMode.legacy()&&PreferenceKeys.scamDevSwitch("scamrgb_tail",true);
        final java.util.concurrent.CountDownLatch uploaded=new java.util.concurrent.CountDownLatch(1);
        final boolean[] uploadOk={false}; // written before uploaded.countDown(), read after its await
        final java.util.concurrent.FutureTask<java.nio.ByteBuffer> rest=!tail?null:new java.util.concurrent.FutureTask<>(()->{
            final java.nio.ByteBuffer d=decimate(big,61,half);
            boolean interrupted=false;
            for(;;){
                try{uploaded.await();break;}catch(InterruptedException e){interrupted=true;} // never under the upload
            }
            if(interrupted)Thread.currentThread().interrupt();
            if(uploadOk[0])com.scamera.app.util.Allocator.free(big); // else the caller keeps it (a failed upload)
            return d;
        });
        final java.util.concurrent.FutureTask<InputStats> side=new java.util.concurrent.FutureTask<>(()->{
            InputStats s=new InputStats();
            final long t0=System.currentTimeMillis();
            if(hybrid)s.cc=channelClip(big,bento,bentoK,bentoUs,withFlags,ParallelWork.threads(),half); else s.clip=clipLevels(big);
            if(hybrid&&flagsIn!=null){
                final OpposedWorkList work=sparseOpposed
                        ?OpposedWorkList.create(size.x,size.y,flagsIn.capacity(),flagParts()):null;
                s.oppPixels=opposedPixels(flagsIn,work,bandTiles);
                if(work!=null)s.oppGroups=work.sparseGroups();
            }
            final long t1=System.currentTimeMillis();
            // P187: the later nodes only ask whether the worker RGB is there (ScamDenoise.enabledFor; above 100 MP not even
            // that): one sample stands for the 40 MB copy there
            if(owned&&!tail)s.decimated=decimate(big,(long)size.x*size.y>100_000_000L?size.x*size.y:61,half);
            s.statMs=t1-t0;s.decimateMs=System.currentTimeMillis()-t1;
            return s;
        });
        final Thread sideThread=new Thread(rest==null?side:()->{side.run();rest.run();},"SCAMERA-scamrgb-stats");
        sideThread.setDaemon(true);
        sideThread.start();
        final long uploadStart=System.nanoTime();
        GLTexture input=null;
        boolean rgbaBands=false,gpuRepack=false;
        try{
            if(half){
                // P145: the 3 -> 4 halves repack as a compute pass (HalfRgbUpload); the CPU repack on drivers that would not keep
                // every half, for the old model (post_ab) and when a GL call fails
                // P497: scam_dev half_rgb_gpu 0 = the CPU repack (the same halves; an A/B for Adreno 5xx, whose storage buffer reads are slow)
                if(!com.scamera.app.processing.opengl.PostGlMode.legacy()&&PreferenceKeys.scamDevSwitch("half_rgb_gpu",true)){input=HalfRgbUpload.upload(shaderProg,size,big);gpuRepack=input!=null;}
                if(input==null)input=uploadHalfRgba(size,big);
                rgbaBands=input!=null;
                if(input==null)throw new IllegalStateException("no staging memory for the half-float RGB upload");
            } else {
            if(rgbaUploadWanted(glGetString(GL_RENDERER))){input=uploadRgba(size,big);rgbaBands=input!=null;}
            if(input==null)input=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_32,3),big,GL_NEAREST,GL_CLAMP_TO_EDGE);
            }
        }finally{
            if(input!=null)uploadOk[0]=true;
            uploaded.countDown(); // P479: the helper may release the big buffer now (after a successful upload only)
            if(input==null){awaitQuietly(side);if(rest!=null)awaitQuietly(rest);} // never release the buffer under the helper thread (an exception on its way out)
        }
        final long uploadDone=System.nanoTime();
        com.scamera.app.processing.opengl.PostGlMode.lap("scamrgb upload");
        // P479 (speed): the lens shading map and the clip flags (when the recovery can use them) go up while the helper still
        // counts: neither depends on the statistics (the flags are dropped below when the recovery turns out off); the flags'
        // native copy is freed only after the helper, which reads it. Not on the large 2x grid (its memory order, P187)
        GLTexture earlyFlags=null;
        final boolean earlyMaps=tail&&!largeTwoX;
        if(earlyMaps){
            try{
                p.GainMap=new GLTexture(p.shotParams.shadingGrid,new GLFormat(GLFormat.DataType.FLOAT_16,4),
                        BufferUtils.floatBuffer(p.shotParams.shadingMap),GL_LINEAR,GL_CLAMP_TO_EDGE);
                if(hybrid&&strength>0f&&flags!=null){
                    flags.rewind();
                    android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT,1);
                    earlyFlags=new GLTexture(size,new GLFormat(GLFormat.DataType.SIMPLE_8,1),flags,GL_NEAREST,GL_CLAMP_TO_EDGE);
                }
            }catch(RuntimeException|Error failure){
                awaitQuietly(side);if(rest!=null)awaitQuietly(rest);
                p.shotParams.scamRgb=null;p.shotParams.scamRgbOwned=false; // released by the helper (the upload was done)
                if(earlyFlags!=null)earlyFlags.close();
                if(p.GainMap!=null){p.GainMap.close();p.GainMap=null;}
                input.close();
                throw failure;
            }
        }
        final InputStats stats;
        try{
            stats=await(side);
        }catch(RuntimeException|Error failure){
            if(rest!=null){awaitQuietly(rest);p.shotParams.scamRgb=null;p.shotParams.scamRgbOwned=false;} // released by the helper
            if(earlyFlags!=null)earlyFlags.close();
            throw failure;
        }
        final long sideDone=System.nanoTime();
        if(hybrid){
            cc=stats.cc;
            // P277: a lab fragment takes the whole frame's clip levels (when the merges agree on Bento), a lab shot records them
            final LabFrame fz=p.frozen;
            if(fz!=null&&fz.ccLo!=null&&fz.ccBento==bento){
                System.arraycopy(fz.ccLo,0,cc.lo,0,3);System.arraycopy(fz.ccHi,0,cc.hi,0,3);System.arraycopy(fz.ccMax,0,cc.max,0,3);
                cc.nearClip=fz.nearClip;cc.hiUnflagged=fz.hiUnflagged;cc.log="whole frame (lab) "+cc.log;
            }
            if(p.record!=null){
                p.record.ccLo=cc.lo.clone();p.record.ccHi=cc.hi.clone();p.record.ccMax=cc.max.clone();
                p.record.nearClip=cc.nearClip;p.record.hiUnflagged=cc.hiUnflagged;p.record.ccBento=bento;
            }
            final float[] top=cc.hi[1]>0f?cc.hi:cc.lo;
            final float[] wp=p.shotParams.asShotNeutral;
            float white=0f;
            if(top[1]>0f)for(int c=0;c<3;c++)white=Math.max(white,top[c]/Math.max(wp[c],1e-6f));
            lastClipWhite=white;
            lastRealClip=cc.enabled();
            com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight recovery per channel: strength="+strength
                    +" bento="+bento+" k="+bentoK+" usClipped="+bentoUs
                    +" flags="+withFlags+" "+cc.log+" nearClip="+cc.nearClip+" clipWhite="+white
                    +" ms="+stats.statMs+" opposedPixels="+stats.oppPixels);
        } else {
            lastClipWhite=0f;
            clip=stats.clip;
            lastRealClip=clip[1]<1e29f;
        }
        final boolean perChannel=cc!=null&&strength>0f&&cc.enabled();
        if(owned&&tail){
            // P479: the helper releases it; the decimated copy takes its place at the end of this node (nothing reads it before)
            p.shotParams.scamRgb=null;
            p.shotParams.scamRgbOwned=false;
        } else if(owned){
            p.shotParams.scamRgb=stats.decimated;
            p.shotParams.scamRgbOwned=false;
            com.scamera.app.util.Allocator.free(big);
        }
        // P33 W2.4 / P48: where the time between the worker result and the lens shading goes (stats and decimate on the helper
        // thread, alongside the upload; wait = what the upload left of them)
        com.scamera.app.util.Log.i("SCAM_PIPELINE","scamrgb input ms: upload="+(uploadDone-uploadStart)/1000000
                +(gpuRepack?" (half rgb, gpu repack"+HalfRgbUpload.takeNote()+")":half?" (rgba16f bands)":rgbaBands?" (rgba bands)":" (rgb)")+" stats="+stats.statMs+" decimate="+(tail?"(helper, after the statistics)":String.valueOf(stats.decimateMs))
                +" wait="+(sideDone-uploadDone)/1000000+" free="+(System.nanoTime()-sideDone)/1000000);
        GLTexture flagsTex=null,chromaNear=null,chromaWide=null,chromaHuge=null,purpleBound=null,oppGpu=null,bandTileTex=null;
        try {
            if(largeTwoX)requireTextureStorage(input);
            float[] gm=p.shotParams.shadingMap;
            if(gm!=null&&p.shotParams.shadingGrid!=null&&gm.length>=4*p.shotParams.shadingGrid.x*p.shotParams.shadingGrid.y){
                int mw=p.shotParams.shadingGrid.x,mh=p.shotParams.shadingGrid.y;
                java.util.function.BiFunction<Integer,Integer,Float> g=(x,y)->{int i=4*(y*mw+x);return (gm[i]+gm[i+1]+gm[i+2]+gm[i+3])/4f;};
                float mean=0;for(int y=0;y<mh;y++)for(int x=0;x<mw;x++)mean+=g.apply(x,y);mean/=mw*mh;
                com.scamera.app.util.Log.i("SCAM_PIPELINE","lens shading map "+mw+"x"+mh+": centre="+g.apply(mw/2,mh/2)
                        +" corner="+g.apply(0,0)+" edge-mid="+g.apply(mw/2,0)+" mean="+mean);
            }
            if(!earlyMaps)p.GainMap=new GLTexture(p.shotParams.shadingGrid,new GLFormat(GLFormat.DataType.FLOAT_16,4),
                    BufferUtils.floatBuffer(p.shotParams.shadingMap),GL_LINEAR,GL_CLAMP_TO_EDGE);
            if(perChannel&&flags!=null&&earlyFlags!=null){
                flagsTex=earlyFlags;earlyFlags=null;
                freeClipFlags(flags); // P479: uploaded before the helper was done with them
            } else if(perChannel&&flags!=null){
                flags.rewind();
                android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT,1);
                // Normalised R8 (SIMPLE_8): the shaders read it through a float sampler2D (code = r * 255). UNSIGNED_8
                // is R8UI, which GL_RED cannot upload and a sampler2D cannot sample (an unset usampler2D would also
                // share unit 0 with the float samplers of the same program).
                flagsTex=new GLTexture(size,new GLFormat(GLFormat.DataType.SIMPLE_8,1),flags,GL_NEAREST,GL_CLAMP_TO_EDGE);
                // P187: uploaded (glTexSubImage2D copied them on return), and before main1 is made: the native copy is gone
                // before the 1.6 GB of main1 come
                freeClipFlags(flags);
            }
            if(earlyFlags!=null){earlyFlags.close();earlyFlags=null;} // P479: the recovery is off after all
            com.scamera.app.processing.opengl.PostGlMode.lap("scamrgb gainmap+flags");
            p.main1=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WORK_CHANNELS),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            // P179: above 100 MP main2 is made on its first use, after the input texture is gone, instead of next to it
            final boolean early=earlyRelease(size);
            if(((long)size.x*size.y>100_000_000L||early)&&!largeTwoX){
                final Point mainSize=new Point(size);
                p.lazyMain2=()->new GLTexture(mainSize,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WORK_CHANNELS),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            } else {
                p.main2=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WORK_CHANNELS),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            }
            if(largeTwoX){requireTextureStorage(p.main1);requireTextureStorage(p.main2);}
            com.scamera.app.processing.opengl.PostGlMode.lap("scamrgb mains");
            int ox=0,oy=0; // «16:9»: the frames arrive cropped (Wide169, P118)
            // Sabre 2x grid: the local chroma blocks keep their SENSOR size (8 x 8), sampled one sensor pixel apart.
            final int s=Math.max(1,Math.round(p.shotParams.outputScale));
            final int block=8*s;
            final float chromaLimit=PreferenceKeys.hybridValue("highlight_chroma",0.35f);
            // P368 clip guard (owner, 2026-10-27: «У нас нет блока который предотвращает умножение пересветов на коэффициенты CCM»):
            // the ultrashort frames' white k for scamrgb / chanprep ceilingShare - camera RGB at one common ceiling above the base
            // white (an ultrashort clip the worker did not flag) goes neutral before the white balance and the colour matrix; 0: off
            final float usWhite=bento&&bentoK>1f?bentoK:0f;
            final long gpuStart=System.currentTimeMillis();
            if(perChannel){
                if(cc.nearClip){
                    GLFormat rgba=new GLFormat(GLFormat.DataType.FLOAT_16,4);
                    Point near=new Point((size.x+block-1)/block,(size.y+block-1)/block);
                    chromaNear=new GLTexture(near,rgba,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
                    Point wide=new Point((near.x+3)/4,(near.y+3)/4);
                    chromaWide=new GLTexture(wide,rgba,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
                    chromaHuge=new GLTexture(new Point((wide.x+3)/4,(wide.y+3)/4),rgba,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
                    // P473 (speed): the compute version (one invocation per sample, the same sums in the same order) for 8 x 8 samples
                    // a block; scam_dev.txt "hl_chanprep_cs 0", another block or a GL failure: the fragment pass as before
                    final boolean cs=(block+s-1)/s==8&&!com.scamera.app.processing.opengl.PostGlMode.legacy()
                            &&PreferenceKeys.scamDevSwitch("hl_chanprep_cs",true);
                    boolean drawn=false;
                    for(int attempt=cs?0:1;attempt<2&&!drawn;attempt++){
                        if(attempt==0){shaderProg.setLayout(8,8,1);while(android.opengl.GLES31.glGetError()!=android.opengl.GLES31.GL_NO_ERROR){}}
                        shaderProg.useAssetProgram(attempt==0?"hlrecovery/chanprep_cs":"hlrecovery/chanprep",attempt==0);
                        shaderProg.setTexture("InputBuffer",input);shaderProg.setTexture("GainMap",p.GainMap);
                        if(flagsTex!=null)shaderProg.setTexture("ClipFlags",flagsTex);
                        shaderProg.setVar("whitePoint",p.shotParams.asShotNeutral);
                        shaderProg.setVar("inverseSize",1f/size.x,1f/size.y);
                        shaderProg.setVar("cropOffset",ox,oy);
                        shaderProg.setVar("clipLoU",cc.lo);shaderProg.setVar("clipHiU",cc.hi);
                        shaderProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
                        shaderProg.setVar("clipFlagsU",flagsTex!=null?1:0);
                        shaderProg.setVar("blockU",block);shaderProg.setVar("pxStepU",s);
                        shaderProg.setVar("usWhiteU",usWhite);
                        if(attempt==1){shaderProg.drawBlocks(chromaNear);drawn=true;continue;}
                        try{
                            shaderProg.setTextureCompute("outChroma",chromaNear,true);
                            android.opengl.GLES31.glDispatchCompute(near.x,near.y,1); // (no glFinish: the reduce passes sample it next)
                            android.opengl.GLES31.glMemoryBarrier(android.opengl.GLES31.GL_TEXTURE_FETCH_BARRIER_BIT|android.opengl.GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                            drawn=android.opengl.GLES31.glGetError()==android.opengl.GLES31.GL_NO_ERROR;
                        }catch(RuntimeException failure){
                            com.scamera.app.util.Log.w("SCAM_PIPELINE","highlight chanprep compute: "+failure+", the fragment pass");
                        }
                    }
                    // 32- and 128-blocks: the inside of a large clipped area still finds the colour of its light
                    shaderProg.useAssetProgram("hlrecovery/reduce",false);
                    shaderProg.setTexture("InputBuffer",chromaNear);
                    shaderProg.drawBlocks(chromaWide);
                    shaderProg.useAssetProgram("hlrecovery/reduce",false);
                    shaderProg.setTexture("InputBuffer",chromaWide);
                    shaderProg.drawBlocks(chromaHuge);
                }
            }
            // P362 (owner, 2026-10-27: «верни для пересветов там, где коротких кадров нет», «считай на гпу»): RawTherapee's
            // «Inpaint opposed» on the pixels without ultrashort content; its chrominance correction measured on the GPU
            // (hlrecovery/opposed), a lab fragment taking the whole frame's. scam_dev.txt "hybrid_highlight_opposed 0" = the
            // SCAM recovery there as before.
            com.scamera.app.processing.opengl.PostGlMode.lap("scamrgb chanprep");
            float[] oppChroma=null;
            // without clip flags every pixel of a Bento shot holds ultrashort content: nothing for it to do
            // with flags: only when some pixel has a clipped channel and no ultrashort content (the side thread counted them)
            final boolean opposed=perChannel&&cc.nearClip&&(flagsTex!=null?stats.oppPixels>0:cc.hi[1]<=0f)
                    &&PreferenceKeys.hybridValue("highlight_opposed",1f)>0f;
            if(opposed){
                final LabFrame fz=p.frozen;
                if(fz!=null&&fz.oppChroma!=null)oppChroma=fz.oppChroma.clone();
                else{
                    // P479b (speed, owner 2026-11-03: <= 1 level of the JPEG): the per-group sums added on the GPU (float pairwise,
                    // not the CPU's double) and handed to scamrgb as a texture: no read-back, no wait for the upload, chanprep and
                    // the pass itself here. Not for a lab shot (it records the values), the sparse work list, the old GL model or
                    // scam_dev.txt "opposed_gpu_mean 0": the read-back as before
                    if(p.record==null&&stats.oppGroups==null&&!com.scamera.app.processing.opengl.PostGlMode.legacy()
                            &&PreferenceKeys.scamDevSwitch("opposed_gpu_mean",true))
                        oppGpu=opposedChromaGpu(input,p.GainMap,flagsTex,p.shotParams.asShotNeutral,cc,size,ox,oy);
                    if(oppGpu==null)oppChroma=opposedChroma(input,p.GainMap,flagsTex,p.shotParams.asShotNeutral,cc,size,ox,oy,stats.oppGroups);
                }
                if(p.record!=null&&oppChroma!=null)p.record.oppChroma=oppChroma.clone();
            }
            com.scamera.app.processing.opengl.PostGlMode.lap("scamrgb opposed");
            // P479 (speed): the purple fringe's bound per 8 x 8 input block (scamhdr/purplebound; ~3 MB at 12 MP): scamrgb skips the
            // 32 taps where they cannot reach a light (their weight is exactly 0 there). scam_dev.txt "purple_bound 0", the old GL
            // model, a failure, or no purple pass: the taps everywhere as before
            final float purple=perChannel?Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_purple",1f))):0f;
            // P493: «Подавление аберраций» - the fringe net (CaNetP, after this pass and the clip band) or P399 here, never both; the
            // net's programs are built now, so a GPU that cannot build one (or bad weights, or a frame above 64 MP) gets P399
            final CaNetP.Settings fringeNet=CaNetP.Settings.read();
            final String fringeMethod=CaNetP.method();
            final long fringePixels=(long)size.x*size.y;
            final CaNetP.Route fringe=CaNetP.route(purple,fringeMethod,fringePixels,purple>0f&&CaNetP.NEURAL.equals(fringeMethod)
                    &&fringePixels<=CaNetP.MAX_PIXELS&&CaNetP.prepare(shaderProg,fringeNet));
            final float p399=fringe==CaNetP.Route.CLASSIC?purple:0f;
            if(p399>0f&&!com.scamera.app.processing.opengl.PostGlMode.legacy()&&PreferenceKeys.scamDevSwitch("purple_bound",true)){
                try{
                    purpleBound=new GLTexture(new Point((input.size.x+7)/8,(input.size.y+7)/8),new GLFormat(GLFormat.DataType.FLOAT_32,4),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
                    shaderProg.useAssetProgram("scamhdr/purplebound",false);
                    shaderProg.setTexture("InputBuffer",input);
                    shaderProg.setVar("whitePoint",p.shotParams.asShotNeutral);
                    shaderProg.drawBlocks(purpleBound);shaderProg.closed=true;
                }catch(RuntimeException failure){
                    com.scamera.app.util.Log.w("SCAM_PIPELINE","purple fringe bound: "+failure+", the taps everywhere");
                    if(purpleBound!=null)purpleBound.close();
                    purpleBound=null;
                }
            }
            if(purpleBound!=null)shaderProg.setDefine("PURPLE_BOUND",1);
            if(oppGpu!=null)shaderProg.setDefine("OPP_GPU",1);
            shaderProg.useAssetProgram("scamhdr/scamrgb");shaderProg.setTexture("InputBuffer",input);shaderProg.setTexture("GainMap",p.GainMap);
            if(purpleBound!=null)shaderProg.setTexture("PurpleBound",purpleBound);
            if(oppGpu!=null)shaderProg.setTexture("OppChroma",oppGpu);
            shaderProg.setVar("whitePoint",p.shotParams.asShotNeutral);
            final boolean signed=signedInput(hybrid);
            shaderProg.setVar("signedU",signed?1:0);
            p.signedRgb=signed;
            if(perChannel){
                shaderProg.setVar("hlModeU",1);
                shaderProg.setVar("clipLoU",cc.lo);shaderProg.setVar("clipHiU",cc.hi);
                shaderProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
                shaderProg.setVar("hlStrengthU",strength);
                shaderProg.setVar("clipFlagsU",flagsTex!=null?1:0);
                if(flagsTex!=null)shaderProg.setTexture("ClipFlags",flagsTex);
                shaderProg.setVar("chromaU",chromaNear!=null?1:0);
                if(chromaNear!=null){shaderProg.setTexture("Chroma8",chromaNear);shaderProg.setTexture("Chroma32",chromaWide);shaderProg.setTexture("Chroma128",chromaHuge);}
                shaderProg.setVar("blockU",block);
                shaderProg.setVar("chromaLimitU",chromaLimit);
                float defringe=PreferenceKeys.hybridValue("highlight_defringe",0.85f);
                shaderProg.setVar("defringeU",defringe>0f?defringe:-1f);
                // P539: a near-white light's surroundings at the top of the range go white (no ring round the sun), with the glow setting
                shaderProg.setVar("hlWhiteU",Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_glow",1f))));
                // P399: the lens's purple fringe against a bright light goes neutral; scam_dev.txt "hybrid_highlight_purple 0" = as before
                // P493: 0 when the fringe net runs instead (P399 is inside it)
                shaderProg.setVar("purpleU",p399);
                shaderProg.setVar("oppU",opposed?1:0);
                // P393: a clipped channel of a coloured surface (sunlit leaves) also takes the local light's colour (Chroma8 / 32 / 128);
                // scam_dev.txt "hybrid_highlight_opposed_local 0" = RawTherapee's reference alone, as before
                shaderProg.setVar("oppLocalU",opposed&&PreferenceKeys.hybridSwitch("highlight_opposed_local",true)?1:0);
                shaderProg.setVar("oppWarmU",opposed?Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_opposed_warm",1f))):0f); // P506
                // a failed measurement: chrominance 0 (RawTherapee's own result: its sum is always 0, see hlrecovery/opposed)
                final float[] oc=oppChroma!=null?oppChroma:new float[3];
                shaderProg.setVar("oppChromaU",oc[0],oc[1],oc[2]);
                shaderProg.setVar("usWhiteU",usWhite);
            } else {
                // SCAM HDR: neutralise at the plateau; hybrid with the recovery off: plain WB and lens shading.
                if(clip==null)clip=new float[]{1e30f,1e30f,1e30f};
                shaderProg.setVar("hlModeU",0);
                shaderProg.setVar("clipLevel",clip[0],clip[1],clip[2]);
            }
            shaderProg.setVar("cropOffset",ox,oy);
            shaderProg.setVar("inverseSize",1f/size.x,1f/size.y);
            // Keep the ping-pong cursor in sync for every following postprocessing pass.
            passOut=p.getMain();shaderProg.drawBlocks(passOut);shaderProg.closed=true;
            if(purpleBound!=null){purpleBound.close();purpleBound=null;}
            if(oppGpu!=null){oppGpu.close();oppGpu=null;}
            final int shorter=wholeShorter(p.frozen,WhyTone.lowFactor(p.shotParams),size);
            if(hybrid&&flagsTex!=null){
                p.sunBloom=sunBloom(flagsTex,size,shorter);p.sunBloomSize=p.sunBloom!=null?new Point(size):null;p.sunBloomBlock=shareBlock(shorter);
            }
            // P378: the low-chroma glow round a light «Inpaint opposed» rebuilt lands on white in WhyCombine (with the flags or without)
            if(opposed){
                p.opposedShare=opposedShare(p,input,flagsTex,cc,size,shorter);
                p.opposedShareSize=p.opposedShare!=null?new Point(size):null;p.opposedShareBlock=shareBlock(shorter);
            }
            final String chromaLog=chromaNear!=null?chromaNear.size.x+"x"+chromaNear.size.y+" block "+block:"neutral";
            // Clip band: the dashed orange/blue line where a slanted clip edge alternates between clipped-mean and
            // unclipped-sample colours (assets/shaders/scamhdr/clipband.glsl). pref_scam_hybrid_highlight_band 0..1.
            final float band=perChannel&&flagsTex!=null?Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_band",1f))):0f;
            // P537: the glow of a near-white clipped light takes the light's colour (Ark's white daytime running lights), in the same
            // pass (assets/shaders/scamhdr/clipband.glsl, glowU). pref_scam_hybrid_highlight_glow 0..1, on by default.
            final float glow=perChannel&&flagsTex!=null?Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_glow",1f))):0f;
            // P671: the tiles near a flagged pixel (the windows' reach widened to whole tiles); none: no pass changes a pixel
            final int bandReach=Math.max(Math.max(3*s,2*s),glow>0f?6*s:0);
            final byte[] bandMap=(band>0f||glow>0f)&&bandTiles!=null&&flagsTex!=null&&flagsTex.size.equals(size)
                    ?bandTiles.activeMap(bandReach):null;
            final boolean bandSkipped=(band>0f||glow>0f)&&bandTiles!=null&&flagsTex!=null&&flagsTex.size.equals(size)&&bandMap==null;
            final boolean bandPass=(band>0f||glow>0f)&&!bandSkipped;
            if(bandMap!=null){
                try{
                    android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT,1);
                    bandTileTex=new GLTexture(new Point(bandTiles.tilesX,bandTiles.tilesY),new GLFormat(GLFormat.DataType.SIMPLE_8,1),
                            java.nio.ByteBuffer.allocateDirect(bandMap.length).put(bandMap).rewind(),GL_NEAREST,GL_CLAMP_TO_EDGE);
                }catch(RuntimeException failure){
                    com.scamera.app.util.Log.w("SCAM_PIPELINE","clip band tiles: "+failure+", every pixel");
                    if(bandTileTex!=null)bandTileTex.close();
                    bandTileTex=null;
                }
            }
            // P187: the input (RGBA16F like a main, chanprep and scamrgb were its only readers) takes main2's place for the band
            // pass: no 1.6 GB main2 is made next to it. Its sampler becomes a main's (GL_LINEAR); the band pass writes every
            // pixel of it, as it would of a new main2.
            boolean inputIsMain2=false;
            if(early&&bandPass&&!largeTwoX&&p.main2==null&&p.lazyMain2!=null&&input.mainFormat()){
                input.setFilter(GL_LINEAR);
                p.main2=input;p.lazyMain2=null;input=null;
                inputIsMain2=true;
            }
            GLTexture unbanded=null;
            if(bandPass){
                // P479 (speed): the band's two flag windows as a vertical pass (scamhdr/clipmask, R8) and clipband's horizontal
                // taps - the same pixels; one R8 texture of the frame for the pass (none above 100 MP, scam_dev.txt "clipband_mask
                // 0", the old GL model or a GL failure: the 2-D taps as before)
                GLTexture bandMask=null;
                if(!ScamHdrDenoise.huge(size.x,size.y)&&flagsTex.size.equals(passOut.size)
                        &&!com.scamera.app.processing.opengl.PostGlMode.legacy()&&PreferenceKeys.scamDevSwitch("clipband_mask",true)){
                    try{
                        bandMask=new GLTexture(flagsTex.size,new GLFormat(GLFormat.DataType.SIMPLE_8,1),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
                        if(bandTileTex!=null)shaderProg.setDefine("BAND_TILES",1);
                        shaderProg.useAssetProgram("scamhdr/clipmask",false);
                        if(bandTileTex!=null){shaderProg.setTexture("BandTiles",bandTileTex);shaderProg.setVar("bandTileU",ClipBandTiles.TILE);}
                        shaderProg.setTexture("ClipFlags",flagsTex);
                        shaderProg.setVar("radiusU",3*s);shaderProg.setVar("zoneU",2*s);
                        shaderProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
                        shaderProg.setVar("glowRadiusU",glow>0f?6*s:0);
                        shaderProg.drawBlocks(bandMask);shaderProg.closed=true;
                    }catch(RuntimeException failure){
                        com.scamera.app.util.Log.w("SCAM_PIPELINE","clip band mask: "+failure+", the 2-D taps");
                        if(bandMask!=null)bandMask.close();
                        bandMask=null;
                    }
                }
                GLTexture banded=p.getMain();
                if(bandMask!=null)shaderProg.setDefine("BAND_MASK",1);
                if(bandTileTex!=null)shaderProg.setDefine("BAND_TILES",1);
                shaderProg.useAssetProgram("scamhdr/clipband",false);
                if(bandMask!=null)shaderProg.setTexture("BandMask",bandMask);
                if(bandTileTex!=null){shaderProg.setTexture("BandTiles",bandTileTex);shaderProg.setVar("bandTileU",ClipBandTiles.TILE);}
                shaderProg.setTexture("InputBuffer",passOut);
                shaderProg.setTexture("ClipFlags",flagsTex);
                shaderProg.setVar("radiusU",3*s);shaderProg.setVar("zoneU",2*s);
                shaderProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
                shaderProg.setVar("strengthU",band);
                shaderProg.setVar("glowU",glow);shaderProg.setVar("glowRadiusU",glow>0f?6*s:0);
                shaderProg.drawBlocks(banded);shaderProg.closed=true;
                if(bandMask!=null)bandMask.close();
                if(bandTileTex!=null){bandTileTex.close();bandTileTex=null;}
                unbanded=passOut;
                passOut=banded;
            }
            if(early){
                // P187: what nothing reads any more goes back now instead of through WhyStats .. WhyCombine: the input (when it
                // did not become main2), the flags, the chroma blocks and the unbanded scamrgb output (the pipeline makes main1
                // again for WhyCombine's output). glFinish first - storage is freed only when the GPU is done with it, and the
                // next nodes' textures should not come on top of it. Nulled, as GL may hand the freed names to new textures.
                android.opengl.GLES30.glFinish();
                if(input!=null){input.close();input=null;}
                if(flagsTex!=null){flagsTex.close();flagsTex=null;}
                if(chromaNear!=null){chromaNear.close();chromaNear=null;}
                if(chromaWide!=null){chromaWide.close();chromaWide=null;}
                if(chromaHuge!=null){chromaHuge.close();chromaHuge=null;}
                if(unbanded!=null&&!largeTwoX)p.releaseMain(unbanded);
            }
            // P493: the fringe net on scamrgb's output (after the clip band: its dashes are gone before the net looks) into the next
            // main. A GPU failure here leaves the frame as it is (purpleU was 0) and the ping-pong cursor where it was.
            if(fringe==CaNetP.Route.NEURAL){
                final GLTexture fringeIn=passOut,fringeOut=p.getMain();
                try{
                    CaNetP.run(shaderProg,fringeIn,fringeOut,p,cc.lo,fringeNet,purple);
                    passOut=fringeOut;
                    if(early&&!largeTwoX){android.opengl.GLES30.glFinish();p.releaseMain(fringeIn);}
                }catch(RuntimeException failure){
                    com.scamera.app.util.Log.e("SCAM_PIPELINE","fringe net (P493) failed, the frame without fringe suppression: "+failure);
                    p.texnum=p.texnum==1?2:1;
                    shaderProg.closed=true;
                }
            }
            if(hybrid)com.scamera.app.util.Log.i("SCAM_PIPELINE","scamrgb input "+(signed?"signed (clamped after the noise reduction or the WHY colour averages)":"clamped per pixel (post_ab_clamp)"));
            if(perChannel)com.scamera.app.util.Log.i("SCAM_PIPELINE","highlight recovery per channel: chroma="
                    +chromaLog
                    +" limit="+chromaLimit+" band="+band+" glow="+glow
                    +(bandSkipped?" (band skipped: no flagged pixel)":bandMap!=null?String.format(java.util.Locale.ROOT," (band tiles %.1f%%)",100f*ClipBandTiles.share(bandMap)):"")
                    +" clipGuard k="+usWhite+(early?" early release"+(inputIsMain2?" (input as main2)":""):"")
                    +" gpu ms="+(System.currentTimeMillis()-gpuStart));
        }catch(RuntimeException|Error failure){
            if(largeTwoX){
                if(p.main1!=null){p.main1.close();p.main1=null;}
                if(p.main2!=null){p.main2.close();p.main2=null;}
                if(p.GainMap!=null){p.GainMap.close();p.GainMap=null;}
            }
            throw failure;
        }finally{
            if(input!=null)input.close();
            if(flagsTex!=null)flagsTex.close();
            if(chromaNear!=null)chromaNear.close();
            if(chromaWide!=null)chromaWide.close();
            if(chromaHuge!=null)chromaHuge.close();
            if(purpleBound!=null)purpleBound.close();
            if(oppGpu!=null)oppGpu.close();
            if(bandTileTex!=null)bandTileTex.close();
            if(earlyFlags!=null)earlyFlags.close();
            // P479: the helper's decimated copy (it has released the big buffer by now or does so before this returns)
            if(rest!=null)p.shotParams.scamRgb=await(rest);
        }
    }
}
