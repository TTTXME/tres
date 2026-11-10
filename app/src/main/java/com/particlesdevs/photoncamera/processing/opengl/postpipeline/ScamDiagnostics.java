package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.*;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import static android.opengl.GLES30.*;

/** Per-capture diagnostics: sampled previews plus the exact bounded native input. */
public final class ScamDiagnostics {
    private static final ThreadLocal<Job> active=new ThreadLocal<>();
    /**
     * P241 (speed-ux C6): one writer thread for the dumps' files (PFM, PNG, stages.txt) and the ZIP export, in order - the
     * processing thread only samples the stage (the copy it must make before the next pass) and goes on. With diagnostics the
     * X300U's 12 MP shot took 5.6 s against 3.0-3.6 s, ~1.5 s of it the five dumps' PNG / PFM writing. A full queue (32: about
     * six shots' dumps) writes on the calling thread, as before.
     */
    private static final java.util.concurrent.ThreadPoolExecutor exports =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(32), r -> {
                        Thread t=new Thread(r,"SCAM-diagnostic-export");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY+1);return t;
                    });
    static { exports.allowCoreThreadTimeOut(true); }
    private static final class Job {
        final Context context;final File dir;final String name;
        /** P371: the reference frame's Bayer preview, sampled beside the merge (joined in {@link #inputsDone}). */
        volatile Thread inputs;
        Job(Context c) throws IOException {
            context=c;name="SCAM-"+new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date())+"-"+UUID.randomUUID().toString().substring(0,8);
            dir=new File(c.getCacheDir(),name);if(!dir.mkdir())throw new IOException("diagnostic directory");
        }
    }
    private static Object rollingSkew(ImageFrame f) {
        android.hardware.camera2.CaptureResult r=f==null?null:f.getMatchedCaptureMetadata();
        return r==null?null:r.get(android.hardware.camera2.CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
    }
    /**
     * frames.txt of the diagnostic job: every frame of the burst with its role, exposure, ISO, rolling shutter skew and the
     * gap of its exposure start and midpoint to the base frame (RAW Bracket's pairing metadata).
     */
    public static void frames(java.util.List<ImageFrame> frames,ImageFrame base) {
        Job j=active.get();
        if(j==null||frames==null||base==null)return;
        StringBuilder out=new StringBuilder("frame role timestampNs exposureNs iso skewNs startGapMs midGapMs\n");
        double baseMid=base.timestamp+base.measuredExposure/2.0;
        for(int i=0;i<frames.size();i++){
            ImageFrame f=frames.get(i);
            double mid=f.timestamp+f.measuredExposure/2.0;
            out.append(i).append(' ').append(f.getCaptureRole()).append(' ').append(f.timestamp).append(' ').append(f.measuredExposure)
               .append(' ').append(f.measuredIso).append(' ').append(rollingSkew(f))
               .append(String.format(Locale.ROOT," %.3f %.3f%n",(f.timestamp-base.timestamp)/1e6,(mid-baseMid)/1e6));
        }
        try{Files.write(new File(j.dir,"frames.txt").toPath(),out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(Exception e){Log.e("SCAM_DIAG","frames.txt failed",e);}
        // P370 (B1): the newest N frame's colour beside the colour frame's (README.txt)
        try{
            String newest=newestNormal(frames);
            if(newest!=null)Files.write(new File(j.dir,"README.txt").toPath(),newest.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
        }catch(Exception e){Log.e("SCAM_DIAG","README newest frame failed",e);}
    }
    public static void begin(Context context,Parameters p,ImageFrame ref,ScamScene scene) {
        try {
            Job j=new Job(context);active.set(j);
            float[] uploaded=null;
            try{uploaded=p.CCT==null?null:WhyTone.colourMatrixNow(p);}catch(RuntimeException e){Log.d("SCAM_DIAG","colour matrix: "+e);}
            String info=readme(p,ref.measuredIso,ref.measuredExposure,ref.timestamp,ref.noiseSlope,ref.noiseOffset,scene.describe(),
                    rollingSkew(ref),uploaded);
            Files.write(new File(j.dir,"README.txt").toPath(),info.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final int width=ref.width,step=Math.max(1,(Math.max(ref.width,ref.height)/2+1023)/1024),w=ref.width/(2*step),h=ref.height/(2*step);
            final ByteBuffer raw=ref.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            final int cfa=p.cfaPattern,white=p.whiteLevel;
            final float[] black=p.blackLevel.clone();
            // P371 (speed, diagnostics on): the 0.8 M-pixel loop took ~180 ms in front of the merge (X8U header=184); it reads only
            // the base frame, which nothing writes until the merge returns (inputsDone joins it before its frames go), so it runs
            // beside the worker. The same values in the same order. scam_dev diag_sync 1: here, as before.
            final Runnable sample=()->{
                try {
                    ByteBuffer rgb=ByteBuffer.allocate(w*h*12).order(ByteOrder.nativeOrder());
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                        float[] c=new float[3];
                        for(int phase=0;phase<4;phase++) {
                            int ch=phase==cfa?0:phase==(cfa^3)?2:1;
                            int offset=((y*2*step+(phase>>1))*width+x*2*step+(phase&1))*2;
                            float value=Math.max(0,((raw.getShort(offset)&65535)-black[phase])/(white-black[phase]));
                            c[ch]+=ch==1?value*.5f:value;
                        }
                        for(float v:c)rgb.putFloat(v);
                    }
                    rgb.flip();bufferFor(j,"00-reference-bayer-no-wb",rgb,w,h,3,true,false);
                } catch(RuntimeException e){Log.e("SCAM_DIAG","Begin failed",e);}
            };
            if(sync()){sample.run();return;}
            final Thread t=new Thread(sample,"SCAM-diagnostic-begin");
            t.setDaemon(true);
            try{t.start();j.inputs=t;}catch(RuntimeException | OutOfMemoryError e){sample.run();}
        } catch(Exception e){Log.e("SCAM_DIAG","Begin failed",e);}
    }

    /** P371: scam_dev diag_sync 1 - the diagnostic previews sampled, converted and written on the calling thread, as before. */
    private static boolean sync() {
        try { return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("diag_sync", false); }
        catch(RuntimeException | LinkageError e) { return false; }
    }

    /**
     * P371: the reference frame's preview of {@link #begin} done (it reads the base frame: called before the shot's frames are
     * released); nothing without a diagnostic capture on this thread.
     */
    public static void inputsDone() {
        final Job j=active.get();
        final Thread t=j==null?null:j.inputs;
        if(t==null)return;
        try{t.join();}catch(InterruptedException e){Thread.currentThread().interrupt();}
        j.inputs=null;
    }

    /**
     * README.txt of a diagnostic capture (P370 B1: also the colour chain - the uploaded intermediateToSRGB, the camera matrix, their
     * product with sensorToProPhoto, the route, the scene light and the frame that supplied the colour). {@code uploaded}: the
     * intermediateToSRGB uniform (the «Цвет» white matrix after CCT.matrix), null when unknown.
     */
    static String readme(Parameters p,int iso,long exposureNs,long timestamp,float noiseSlope,float noiseOffset,String scene,
                         Object skew,float[] uploaded) {
            // P214b: the build the capture came from (BuildIdentity), second line of README.txt and the ZIP comment
            String info="SCAM diagnostic capture\nbuild: "+com.particlesdevs.photoncamera.util.BuildIdentity.line()
                    +"\nCamera="+p.physicalID+" CFA="+p.cfaPattern+" size="+p.rawSize
                    +"\nISO="+iso+" exposureNs="+exposureNs+" timestamp="+timestamp
                    +"\nCamera2 noise slope="+noiseSlope+" offset="+noiseOffset
                    +"\n"+scene
                    +"\nwhite="+p.whiteLevel+" black="+Arrays.toString(p.blackLevel)+" neutral="+Arrays.toString(p.whitePoint)
                    +"\nlevel sources: "+p.levelSources()
                    +"\nrolling shutter skew ns="+skew
                    +"\nsensorToProPhoto="+Arrays.toString(p.sensorToProPhoto)
                    +colourChain(p,uploaded)
                    +"\nPFM: little-endian float32 RGB; rows bottom first. Thumbnails are nearest sampled, sensor orientation."
                    +"\nPNG: clipped preview only; linear stages use gamma 1/2.2. PFM retains negative/HDR values."
                    +"\n00: Bayer-cell preview, black-subtracted, NO WB. 01: NPU tile before IVST."
                    +"\n02: reconstructed native RGB after IVST. Other files: named GPU stages."
                    +"\ninput.nch: exact native burst, NCH v8: 128-byte base header, 32-byte scene, seven AE records, then seven RAW16 planes."
                    +"\nIt retains every model slot and its exposure/ISO, black level and noise calibration for replay."
                    +"\nSensor portability is experimental; original model weights are unchanged.\n";
            return info;
    }

    /**
     * P370 (B1): the colour lines of README.txt. On the ISP / plain-table / DCP routes sensorToProPhoto is the white balance alone
     * (diag(1 / neutral)) and the camera's colour matrix is cameraMatrix (CCT.matrix); cameraToLinearSRGB is the whole chain of
     * the photo (why/low, why/combine: intermediateToSRGB . sensorToProPhoto . (ScamRgb * neutral)).
     */
    static String colourChain(Parameters p,float[] uploaded) {
        StringBuilder s=new StringBuilder();
        float[] camera=p.CCT==null?null:p.CCT.matrix;
        s.append("\nintermediateToSRGB=").append(uploaded==null?"unknown":Arrays.toString(uploaded))
         .append(" (uploaded: the colour look's white matrix after cameraMatrix)");
        s.append("\ncameraMatrix=").append(camera==null?"none":Arrays.toString(camera));
        if(uploaded!=null){
            float[] chain=new float[9];
            com.particlesdevs.photoncamera.processing.render.Converter.multiply(uploaded,p.sensorToProPhoto,chain);
            s.append("\ncameraToLinearSRGB=").append(Arrays.toString(chain)).append(" (camera RGB = ScamRgb x neutral)");
        }
        s.append("\ncolourSource=").append(p.colourSource.isEmpty()?"unknown":p.colourSource)
         .append(p.colourSourceWhy.isEmpty()?"":" ("+p.colourSourceWhy+")");
        s.append(String.format(Locale.ROOT,"\nsceneCct=%.0f source=%s Duv=%s",p.sceneCct,p.sceneCctSource.isEmpty()?"unknown":p.sceneCctSource,
                Float.isNaN(p.sceneDuv)?"none":String.format(Locale.ROOT,"%.4f",p.sceneDuv)));
        s.append("\ncolour frame: timestamp=").append(p.colourFrameTimestamp).append(" ISO=").append(p.colourFrameIso)
         .append(" AWBFrameControlCCT=").append(p.awbLabelK>0?p.awbLabelK+" K":"none");
        s.append("\ncolour frame CST=").append(p.resultCst==null?"none":Arrays.toString(p.resultCst)+String.format(Locale.ROOT," det=%.4f",det(p.resultCst)));
        s.append("\nDNG colour tags: ").append(p.colourTags.isEmpty()?"unknown":p.colourTags);
        return s.toString();
    }

    static double det(float[] m) {
        return m[0]*((double)m[4]*m[8]-(double)m[5]*m[7])-m[1]*((double)m[3]*m[8]-(double)m[5]*m[6])+m[2]*((double)m[3]*m[7]-(double)m[4]*m[6]);
    }

    /**
     * P370 (B1): the newest N frame's neutral and transform, for README.txt beside the colour frame's (the oldest N frame supplies
     * the colour, ScamProcessor). Null when the burst has no N frame with its metadata.
     */
    static String newestNormal(java.util.List<ImageFrame> frames) {
        ImageFrame newest=null;
        for(ImageFrame f:frames)
            if(f!=null&&f.getCaptureRole()==ImageFrame.CaptureRole.NORMAL&&f.getMatchedCaptureMetadata()!=null
                    &&(newest==null||f.timestamp>newest.timestamp))newest=f;
        if(newest==null)return null;
        android.hardware.camera2.CaptureResult r=newest.getMatchedCaptureMetadata();
        android.util.Rational[] n=r.get(android.hardware.camera2.CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        android.hardware.camera2.params.ColorSpaceTransform cst=r.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_TRANSFORM);
        return "newest N frame: timestamp="+newest.timestamp+" ISO="+newest.measuredIso+" neutral="+(n==null?"none":Arrays.toString(n))
                +" CST="+(cst==null?"none":cst.toString())+"\n";
    }
    /** A diagnostic capture is being written on this thread (the dumps below do something). */
    public static boolean active() { return active.get() != null; }
    public static void buffer(String stage,ByteBuffer bytes,int width,int height,int channels,boolean linear) {
        buffer(stage,bytes,width,height,channels,linear,false);
    }
    /** half: the samples are IEEE half floats (P80 hybrid RGB transport; read as float32 they ran past the buffer, 2026-10-12). */
    public static void buffer(String stage,ByteBuffer bytes,int width,int height,int channels,boolean linear,boolean half) {
        Job j=active.get();if(j==null)return;
        flushStages(j); // P602: the GPU stages queued before this dump are written first (the files' order as before)
        bufferFor(j,stage,bytes,width,height,channels,linear,half);
    }

    private static void bufferFor(Job j,String stage,ByteBuffer bytes,int width,int height,int channels,boolean linear,boolean half) {
        try {
            final int step=Math.max(1,(Math.max(width,height)+1023)/1024),w=width/step,h=height/step;
            ByteBuffer in=bytes.duplicate().order(ByteOrder.nativeOrder());
            // P371 (speed, diagnostics on): only the samples are taken here (the stage's buffer is freed or written next); the
            // statistics, the 2.4 M pow of the preview and the files on the writer thread (X8U: 02-after-ivst ~90 ms in the
            // shot's read, 03-ScamRgb / 03-WhyCombine 116-178 ms on the processing thread). The same values in the same order.
            final short[] halves=half?new short[w*h*3]:null;
            final float[] floats=half?null:new float[w*h*3];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<3;c++) {
                if(half)halves[(y*w+x)*3+c]=in.getShort(((y*step)*width+x*step)*channels*2+c*2);
                else floats[(y*w+x)*3+c]=in.getFloat(((y*step)*width+x*step)*channels*4+c*4);
            }
            queueConvert(j,stage,w,h,linear,halves,floats);
        } catch(Exception e){Log.e("SCAM_DIAG","Save stage failed: "+stage,e);}
    }

    /** The PFM / PNG of a stage's samples on the writer thread (here with diag_sync 1 or a full queue). */
    private static void queueConvert(Job j,String stage,int w,int h,boolean linear,short[] halves,float[] floats) {
        final Runnable convert=()->convertAndWrite(j,stage,w,h,linear,halves,floats);
        if(sync()){convert.run();return;}
        try { exports.execute(convert); } catch(java.util.concurrent.RejectedExecutionException full) { convert.run(); }
    }

    /** The PFM / PNG of one stage from its samples ({@code halves} or {@code floats}, w x h x 3) and its line in stages.txt. */
    private static void convertAndWrite(Job j,String stage,int w,int h,boolean linear,short[] halves,float[] floats) {
        try {
            final boolean half=halves!=null;
            ByteBuffer out=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN);
            int[] pixels=new int[w*h];long invalid=0;float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                int pixel=0xff000000;
                for(int c=0;c<3;c++) {
                    float v=half?android.util.Half.toFloat(halves[(y*w+x)*3+c]):floats[(y*w+x)*3+c];
                    out.putFloat(((h-1-y)*w+x)*12+c*4,v);
                    if(!Float.isFinite(v)){invalid++;v=0;}else{min=Math.min(min,v);max=Math.max(max,v);}
                    float display=Math.max(0,Math.min(1,v));if(linear)display=(float)Math.pow(display,1/2.2);
                    pixel|=Math.round(display*255)<<(16-8*c);
                }
                pixels[y*w+x]=pixel;
            }
            final String line=stage+": "+w+"x"+h+" min="+min+" max="+max+" nonfinite="+invalid+"\n";
            try(OutputStream f=new FileOutputStream(new File(j.dir,stage+".pfm"))){
                f.write(("PF\n"+w+" "+h+"\n-1.0\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));f.write(out.array());
            }
            Bitmap bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
            try(OutputStream f=new FileOutputStream(new File(j.dir,stage+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}finally{bitmap.recycle();}
            try(FileWriter f=new FileWriter(new File(j.dir,"stages.txt"),true)){f.write(line);}
            Log.i("SCAM_DIAG",line.trim());
        } catch(Exception e){Log.e("SCAM_DIAG","Save stage failed: "+stage,e);}
    }
    public static void nativeFiles(File source,String report) {
        Job j=active.get();if(j==null)return;
        // Take ownership before the client's finally block removes its job.
        // Both directories are in this app's cache: rename avoids another full
        // seven-frame copy and lets the existing export queue do compression.
        File burst=new File(source,"input.f32");
        if(burst.isFile()&&burst.length()>=128&&burst.length()<=160+7*176+32+16000000L*14) {
            if(!burst.renameTo(new File(j.dir,"input.nch")))
                Log.w("SCAM_DIAG","Could not retain native burst for replay");
        }
        try {
            File[] files=source.listFiles((d,n)->n.startsWith("scam-diag-")&&n.endsWith(".pfm"));
            if(files!=null)for(File f:files)Files.copy(f.toPath(),new File(j.dir,"01-"+f.getName()).toPath());
            Files.write(new File(j.dir,"native-report.txt").toPath(),report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception e){Log.e("SCAM_DIAG","Native diagnostic copy failed",e);}
    }
    public static void gpu(String name,GLTexture source) {
        final Job j=active.get();
        if(j==null||source==null||!Arrays.asList("ScamRgb","WhyFused","WhyLumaS","WhyDetailRef","WhyCombine","Sharpening").contains(name))return;
        final long t0=System.nanoTime();
        try {
            gpuStage(j,name,source);
        } finally {
            synchronized(ScamDiagnostics.class){gpuNs+=System.nanoTime()-t0;gpuStages++;}
        }
    }

    // ---- P602 (owner, 2026-11-10: «ускорить на нём обработку ещё сильнее», the Pixel 7 log of 31249 - a diagnostic shot: post
    // 4085 ms, its nodes 2986): each GPU stage's read-back below waited for the GPU to finish the pipeline so far, then read 768
    // full rows of floats (50 MB at 4080 px) - the 338 ms after ScamRgb, the 493 ms of the two (WhyFused, WhyLumaS) at the start of WhyCombine
    // (1451 - its own 958) and the 535 ms after WhyCombine of that shot on the Mali-G710. Now one compute pass copies the samples
    // (every step-th texel of every step-th row, texelFetch: the texel's own value, as the float read-back gives it) into a buffer
    // of w x h vec4 (12.5 MB at 1020 x 768) and the shot goes on; the buffer is mapped later, when the GPU is done with it: when a
    // third stage comes (MAX_STAGES_QUEUED in flight), before any other dump of the shot (the files keep their order) and at the
    // end of the run (GLBasePipeline.finishRun -> flushGpuStages). The same samples. A texture other than half / float, a GPU
    // without the pass, any GL error, scam_dev diag_sync 1 or diag_gpu_async 0: the read-back as before.
    private static final class QueuedStage {
        final Job job;final String stage;final int buffer,w,h;final boolean linear;final android.opengl.EGLContext context;
        QueuedStage(Job job,String stage,int buffer,int w,int h,boolean linear,android.opengl.EGLContext context){
            this.job=job;this.stage=stage;this.buffer=buffer;this.w=w;this.h=h;this.linear=linear;this.context=context;
        }
    }
    private static final ThreadLocal<ArrayDeque<QueuedStage>> queued=ThreadLocal.withInitial(ArrayDeque::new);
    static final int MAX_STAGES_QUEUED=2;
    /** P602: the CPU time of the GPU stages' dumps (gpu() and their buffers' maps) and their count since {@link #takeGpuStats}. */
    private static long gpuNs;private static int gpuStages;
    /** P602: the sample pass of the context it was built in (0: not built; -1 there: it does not build). */
    private static int sampleProgram,sampleSrc,sampleDims;private static android.opengl.EGLContext sampleContext;

    /** P602: {ms, stages} of the GPU stages' dumps since the last call (GLBasePipeline's timings table; 0, 0 without diagnostics). */
    public static synchronized long[] takeGpuStats() {
        final long[] out={gpuNs/1_000_000L,gpuStages};
        gpuNs=0;gpuStages=0;
        return out;
    }

    /** P602: the GPU stages queued on this thread written now (the end of a post run, its context current); nothing without any. */
    public static void flushGpuStages() {
        final ArrayDeque<QueuedStage> q=queued.get();
        if(q.isEmpty())return;
        final long t0=System.nanoTime();
        try{flushStages(active.get());}
        finally{synchronized(ScamDiagnostics.class){gpuNs+=System.nanoTime()-t0;}}
    }

    static boolean asyncStages() {
        if(sync())return false;
        try{return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("diag_gpu_async",true);}
        catch(RuntimeException|LinkageError e){return false;}
    }

    /**
     * P602: the textures the sample pass reads as the float read-back does: half floats, and floats sampled GL_NEAREST (a float
     * texture filtered GL_LINEAR is incomplete without OES_texture_float_linear: its texelFetch gives zeros).
     */
    static boolean sampleable(GLFormat f) {
        return f!=null&&(f.dataType()==GLFormat.DataType.FLOAT_16||f.dataType()==GLFormat.DataType.FLOAT_32&&f.filter==GL_NEAREST);
    }

    /** P602: the step and the w x h samples of a W x H stage (every step-th texel of every step-th row, as below). */
    static int[] sampleGrid(int width,int height) {
        final int step=Math.max(1,(Math.max(width,height)+1023)/1024);
        return new int[]{step,width/step,height/step};
    }

    /** P602: the queued stages of this thread written in order (only {@code j}'s; another shot's are dropped). */
    private static void flushStages(Job j) {
        final ArrayDeque<QueuedStage> q=queued.get();
        while(!q.isEmpty())writeStage(q.pollFirst(),j);
    }

    private static void writeStage(QueuedStage s,Job j) {
        final android.opengl.EGLContext now=android.opengl.EGL14.eglGetCurrentContext();
        final boolean here=s.context.equals(now);
        if(s.job!=j||!here){
            // an earlier shot's (a failed run) or another context's: its buffer goes with its context
            if(here)glDeleteBuffers(1,new int[]{s.buffer},0);
            Log.w("SCAM_DIAG","GPU stage dropped: "+s.stage+(s.job!=j?" (an earlier shot)":" (its GL context is not current)"));
            return;
        }
        final int[] old=new int[1];
        glGetIntegerv(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER_BINDING,old,0);
        try {
            glGetError();
            glBindBuffer(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,s.buffer);
            final Buffer mapped=glMapBufferRange(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,0,s.w*s.h*16,GL_MAP_READ_BIT);
            if(!(mapped instanceof ByteBuffer)){glGetError();throw new IOException("the samples' buffer did not map");}
            final FloatBuffer rows=((ByteBuffer)mapped).order(ByteOrder.nativeOrder()).asFloatBuffer();
            final float[] all=new float[s.w*s.h*4];
            rows.get(all);
            glUnmapBuffer(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER);
            final float[] floats=new float[s.w*s.h*3];
            for(int i=0,n=s.w*s.h;i<n;i++){floats[i*3]=all[i*4];floats[i*3+1]=all[i*4+1];floats[i*3+2]=all[i*4+2];}
            queueConvert(s.job,s.stage,s.w,s.h,s.linear,null,floats);
        }catch(Exception e){Log.e("SCAM_DIAG","GPU stage failed: "+s.stage,e);}
        finally{
            glBindBuffer(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,old[0]);
            glDeleteBuffers(1,new int[]{s.buffer},0);
            glGetError();
        }
    }

    private static final String SAMPLE_PASS="#version 310 es\n"
            +"precision highp float;\nprecision highp int;\n"
            +"layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;\n"
            +"uniform highp sampler2D srcU;\n"
            +"uniform ivec3 dimsU;\n" // w, h, step
            +"layout(std430, binding = 3) writeonly buffer DiagSamples { highp vec4 samples[]; };\n"
            +"void main() {\n"
            +"    ivec2 p = ivec2(gl_GlobalInvocationID.xy);\n"
            +"    if (p.x >= dimsU.x || p.y >= dimsU.y) return;\n"
            +"    samples[p.y * dimsU.x + p.x] = texelFetch(srcU, p * dimsU.z, 0);\n"
            +"}\n";

    /** P602: the sample pass of the current context (built once per context), 0 when it does not build there. */
    private static int sampleProgram() {
        final android.opengl.EGLContext now=android.opengl.EGL14.eglGetCurrentContext();
        if(now==null||now.equals(android.opengl.EGL14.EGL_NO_CONTEXT))return 0;
        // (a new context may get a destroyed one's handle: the program must still be this pass in this context)
        if(now.equals(sampleContext)&&(sampleProgram<0||sampleProgram>0&&android.opengl.GLES31.glIsProgram(sampleProgram)
                &&android.opengl.GLES31.glGetUniformLocation(sampleProgram,"dimsU")==sampleDims
                &&android.opengl.GLES31.glGetUniformLocation(sampleProgram,"srcU")==sampleSrc))return Math.max(0,sampleProgram);
        glGetError();
        sampleContext=now;sampleProgram=-1;
        final int shader=android.opengl.GLES31.glCreateShader(android.opengl.GLES31.GL_COMPUTE_SHADER);
        if(shader==0)return 0;
        android.opengl.GLES31.glShaderSource(shader,SAMPLE_PASS);
        android.opengl.GLES31.glCompileShader(shader);
        final int[] ok=new int[1];
        android.opengl.GLES31.glGetShaderiv(shader,android.opengl.GLES31.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){
            Log.w("SCAM_DIAG","sample pass: "+android.opengl.GLES31.glGetShaderInfoLog(shader)+"; the read-back as before");
            android.opengl.GLES31.glDeleteShader(shader);glGetError();return 0;
        }
        final int program=android.opengl.GLES31.glCreateProgram();
        android.opengl.GLES31.glAttachShader(program,shader);
        android.opengl.GLES31.glLinkProgram(program);
        android.opengl.GLES31.glDeleteShader(shader);
        android.opengl.GLES31.glGetProgramiv(program,android.opengl.GLES31.GL_LINK_STATUS,ok,0);
        if(ok[0]==0){
            Log.w("SCAM_DIAG","sample pass link: "+android.opengl.GLES31.glGetProgramInfoLog(program)+"; the read-back as before");
            android.opengl.GLES31.glDeleteProgram(program);glGetError();return 0;
        }
        sampleSrc=android.opengl.GLES31.glGetUniformLocation(program,"srcU");
        sampleDims=android.opengl.GLES31.glGetUniformLocation(program,"dimsU");
        if(sampleSrc<0||sampleDims<0){android.opengl.GLES31.glDeleteProgram(program);glGetError();return 0;}
        sampleProgram=program;
        return program;
    }

    /**
     * P602: the stage's samples copied on the GPU into a buffer of their own, queued (true); false when the pass is not possible
     * here (nothing is queued then: the caller reads the stage back as before). Every binding it touches is put back.
     */
    private static boolean queueStage(Job j,String stage,GLTexture source,boolean linear) {
        if(!asyncStages()||!sampleable(source.mFormat)||source.mTextureID==0)return false;
        final ArrayDeque<QueuedStage> q=queued.get();
        while(q.size()>=MAX_STAGES_QUEUED)writeStage(q.pollFirst(),j);
        final int program=sampleProgram();
        if(program==0)return false;
        final int[] grid=sampleGrid(source.mSize.x,source.mSize.y);
        final int step=grid[0],w=grid[1],h=grid[2];
        if(w<=0||h<=0||(long)w*h*16>Integer.MAX_VALUE)return false;
        final int[] oldProgram=new int[1],oldActive=new int[1],oldTexture=new int[1],oldBuffer=new int[1],oldIndexed=new int[1];
        glGetIntegerv(GL_CURRENT_PROGRAM,oldProgram,0);
        glGetIntegerv(GL_ACTIVE_TEXTURE,oldActive,0);
        glGetIntegerv(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER_BINDING,oldBuffer,0);
        android.opengl.GLES31.glGetIntegeri_v(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER_BINDING,3,oldIndexed,0);
        glActiveTexture(GL_TEXTURE15);
        glGetIntegerv(GL_TEXTURE_BINDING_2D,oldTexture,0);
        final int[] buffer=new int[1];
        int error;
        try {
            glGetError();
            glGenBuffers(1,buffer,0);
            glBindBuffer(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,buffer[0]);
            glBufferData(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,w*h*16,null,GL_STREAM_READ);
            glUseProgram(program);
            glBindTexture(GL_TEXTURE_2D,source.mTextureID);
            glUniform1i(sampleSrc,15);
            glUniform3i(sampleDims,w,h,step);
            android.opengl.GLES31.glBindBufferBase(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,3,buffer[0]);
            android.opengl.GLES31.glDispatchCompute((w+15)/16,(h+15)/16,1);
            android.opengl.GLES31.glMemoryBarrier(android.opengl.GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
            glFlush();
            error=glGetError();
        } catch(RuntimeException e) {
            error=-1;
        } finally {
            android.opengl.GLES31.glBindBufferBase(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,3,oldIndexed[0]);
            glBindBuffer(android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER,oldBuffer[0]);
            glBindTexture(GL_TEXTURE_2D,oldTexture[0]);
            glActiveTexture(oldActive[0]);
            glUseProgram(oldProgram[0]);
        }
        if(error!=GL_NO_ERROR){
            if(buffer[0]!=0)glDeleteBuffers(1,buffer,0);
            glGetError();
            sampleContext=null; // built again next time (a failure of this one stays a read-back per stage)
            Log.w("SCAM_DIAG","GPU stage "+stage+": the sample pass failed (0x"+Integer.toHexString(error)+"), read back as before");
            return false;
        }
        q.addLast(new QueuedStage(j,stage,buffer[0],w,h,linear,android.opengl.EGL14.eglGetCurrentContext()));
        return true;
    }

    private static void gpuStage(Job j,String name,GLTexture source) {
        for(int i=0;i<32;i++) {
            int error=glGetError();
            if(error==GL_NO_ERROR)break;
            Log.e("SCAM_DIAG","GL error before "+name+": 0x"+Integer.toHexString(error));
        }
        final boolean linear=!Arrays.asList("WhyCombine","Sharpening").contains(name);
        if(queueStage(j,"03-"+name,source,linear))return;
        flushStages(j); // the stages queued before this one first (the files' order)
        // Read sampled rows through a temporary READ framebuffer. No diagnostic
        // shader/texture bindings can disturb the processing program's state.
        int[] oldRead=new int[1],framebuffer=new int[1];
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING,oldRead,0);
        try {
            glGenFramebuffers(1,framebuffer,0);glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer[0]);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,source.mTextureID,0);
            if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                throw new IOException("Diagnostic framebuffer incomplete");
            int step=Math.max(1,(Math.max(source.mSize.x,source.mSize.y)+1023)/1024);
            int w=source.mSize.x/step,h=source.mSize.y/step;
            ByteBuffer sampled=ByteBuffer.allocate(w*h*12).order(ByteOrder.nativeOrder());
            // P241: the sampled rows through a pixel pack buffer, CHUNK rows a time - one wait for the GPU per chunk instead of
            // one per row (682 rows of a 50 MP stage); the same rows and pixels, so the same samples. Any GL error: row by row.
            if(!readRowsPacked(source.mSize.x,w,h,step,sampled)) {
                sampled.clear();
                ByteBuffer row=ByteBuffer.allocateDirect(source.mSize.x*16).order(ByteOrder.nativeOrder());
                for(int y=0;y<h;y++) {
                    row.clear();glReadPixels(0,y*step,source.mSize.x,1,GL_RGBA,GL_FLOAT,row);
                    if(glGetError()!=GL_NO_ERROR)throw new IOException("Diagnostic float readback unavailable");
                    for(int x=0;x<w;x++)for(int c=0;c<3;c++)sampled.putFloat(row.getFloat((x*step*4+c)*4));
                }
            }
            sampled.flip();
            buffer("03-"+name,sampled,w,h,3,linear);
        }catch(Exception e){Log.e("SCAM_DIAG","GPU stage failed: "+name,e);}
        finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,oldRead[0]);if(framebuffer[0]!=0)glDeleteFramebuffers(1,framebuffer,0);}
    }

    /** P241: sampled rows per pixel pack buffer fill (64 rows of an 8192-px float RGBA stage: 8 MB). */
    static final int CHUNK=64;

    /** P241: rows y * step (h of them) of the read framebuffer, w samples each (every step-th pixel) into {@code out}; false on any GL error. */
    private static boolean readRowsPacked(int width,int w,int h,int step,ByteBuffer out) {
        final long rowBytes=(long)width*16;
        if(rowBytes*CHUNK>Integer.MAX_VALUE)return false;
        final int[] pbo=new int[1];
        glGetError();
        try {
            glGenBuffers(1,pbo,0);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,pbo[0]);
            glBufferData(GL_PIXEL_PACK_BUFFER,(int)(rowBytes*CHUNK),null,GL_STREAM_READ);
            if(glGetError()!=GL_NO_ERROR)return false;
            for(int y0=0;y0<h;y0+=CHUNK) {
                final int n=Math.min(CHUNK,h-y0);
                for(int k=0;k<n;k++)glReadPixels(0,(y0+k)*step,width,1,GL_RGBA,GL_FLOAT,(int)(k*rowBytes));
                if(glGetError()!=GL_NO_ERROR)return false;
                final Buffer mapped=glMapBufferRange(GL_PIXEL_PACK_BUFFER,0,(int)(n*rowBytes),GL_MAP_READ_BIT);
                if(!(mapped instanceof ByteBuffer)){glGetError();return false;}
                final ByteBuffer rows=((ByteBuffer)mapped).order(ByteOrder.nativeOrder());
                for(int k=0;k<n;k++)for(int x=0;x<w;x++)for(int c=0;c<3;c++)out.putFloat(rows.getFloat((int)(k*rowBytes)+(x*step*4+c)*4));
                glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                if(glGetError()!=GL_NO_ERROR)return false;
            }
            return true;
        } catch(RuntimeException e) {
            return false;
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            if(pbo[0]!=0)glDeleteBuffers(1,pbo,0);
            glGetError();
        }
    }

    public static void finish() {
        inputsDone(); // P371: the reference preview's dump is queued before the export
        Job j=active.get();active.remove();if(j==null)return;
        try { exports.execute(() -> export(j)); }
        catch(java.util.concurrent.RejectedExecutionException e) {
            Log.w("SCAM_DIAG","Export queue full; diagnostic cache retained: " + j.dir);
        }
    }
    private static void export(Job j) {
        Uri uri=null;
        try {
            OutputStream stream;
            if(Build.VERSION.SDK_INT>=29) {
                ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,j.name+".zip");
                v.put(MediaStore.Downloads.MIME_TYPE,"application/zip");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOCUMENTS+"/SCAMERA/Dumps");v.put(MediaStore.Downloads.IS_PENDING,1);
                uri=j.context.getContentResolver().insert(MediaStore.Files.getContentUri("external_primary"),v);
                if(uri==null)throw new IOException("Cannot create diagnostic ZIP");stream=j.context.getContentResolver().openOutputStream(uri);
            } else {
                File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),"SCAMERA/Dumps");dir.mkdirs();stream=new FileOutputStream(new File(dir,j.name+".zip"));
            }
            if(stream==null)throw new IOException("Cannot open diagnostic ZIP");
            try(ZipOutputStream zip=new ZipOutputStream(stream)) {
                zip.setComment(com.particlesdevs.photoncamera.util.BuildIdentity.line()); // P214b: readable without unpacking
                File[] files=j.dir.listFiles();if(files!=null)for(File f:files){zip.putNextEntry(new ZipEntry(f.getName()));Files.copy(f.toPath(),zip);zip.closeEntry();}
            }
            if(uri!=null){ContentValues v=new ContentValues();v.put(MediaStore.Downloads.IS_PENDING,0);j.context.getContentResolver().update(uri,v,null,null);}
            Log.i("SCAM_DIAG","Saved Documents/SCAMERA/Dumps/"+j.name+".zip");
        }catch(Exception e){if(uri!=null)try{j.context.getContentResolver().delete(uri,null,null);}catch(Exception cleanup){Log.e("SCAM_DIAG","Partial ZIP cleanup failed",cleanup);}Log.e("SCAM_DIAG","Export failed; cache retained: "+j.dir,e);return;}
        File[] files=j.dir.listFiles();if(files!=null)for(File f:files)f.delete();j.dir.delete();
    }
}
