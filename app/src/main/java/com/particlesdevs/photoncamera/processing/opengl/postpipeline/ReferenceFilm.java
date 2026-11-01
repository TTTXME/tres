package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.GLES31;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmReferenceEngine;
import com.particlesdevs.photoncamera.processing.color.FilmReferenceParameters;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.opengl.GLBuffer;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.nio.ByteBuffer;
import static android.opengl.GLES31.*;

final class ReferenceFilm extends Node {
    ReferenceFilm(){super("","ReferenceFilm");}
    @Override public void Compile(){}

    static boolean supported(FilmSim.Settings settings){
        return FilmReferenceParameters.supports(settings)&&FilmReferenceEngine.available();
    }

    private static void dump(Context context,ByteBuffer input,ByteBuffer output,FilmReferenceParameters params,int width,int height){
        if(!PreferenceKeys.scamDevSwitch("film_reference_dump",false))return;
        try{
            java.io.File root=context.getExternalFilesDir("film-reference-test");
            if(root==null||!root.isDirectory()&&!root.mkdirs())throw new java.io.IOException("Film diagnostic directory");
            ByteBuffer[] buffers={input.duplicate(),output.duplicate(),params.buffer()};
            String[] names={"input.rgba16f","output.rgba16f","params.bin"};
            for(int i=0;i<buffers.length;i++){
                buffers[i].clear();
                try(java.io.FileOutputStream file=new java.io.FileOutputStream(new java.io.File(root,names[i]))){
                    while(buffers[i].hasRemaining())file.getChannel().write(buffers[i]);
                }
            }
            String meta=new org.json.JSONObject().put("width",width).put("height",height).toString();
            java.nio.file.Files.write(new java.io.File(root,"geometry.json").toPath(),meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception error){Log.w("SCAM_PIPELINE","film diagnostic export failed: "+error);}
    }

    static void readHalf(GLProg prog,GLTexture source,ByteBuffer destination){
        readHalf(prog,source,destination,256);
    }

    static int transferRows(int width,int height,int requested,long storageLimit){
        long rowBytes=(long)width*8;
        long limit=Math.min(16L<<20,storageLimit);
        if(width<=0||height<=0||requested<=0||rowBytes>limit)
            throw new IllegalArgumentException("Film transfer exceeds the storage buffer limit");
        return (int)Math.min(Math.min(height,requested),limit/rowBytes);
    }

    static void readHalf(GLProg prog,GLTexture source,ByteBuffer destination,int requestedRows){
        if(!NoiseQuantileGpu.rawReadbackExact(prog))throw new IllegalStateException("Exact half-image access unavailable");
        long[] storageLimit=new long[1];glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,storageLimit,0);
        if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("Film storage buffer limit unavailable");
        final int width=source.mSize.x,height=source.mSize.y;
        final int rows=transferRows(width,height,requestedRows,storageLimit[0]);
        int[] generic=new int[1],binding=new int[1];
        glGetIntegerv(GL_SHADER_STORAGE_BUFFER_BINDING,generic,0);
        glGetIntegeri_v(GL_SHADER_STORAGE_BUFFER_BINDING,0,binding,0);
        try(GLBuffer packed=new GLBuffer(width*rows*2,new GLFormat(GLFormat.DataType.UNSIGNED_32));
            NoiseQuantileGpu.ImageBinding image=new NoiseQuantileGpu.ImageBinding(source)){
            destination.clear();
            for(int y=0;y<height;y+=rows){
                int count=Math.min(rows,height-y),bytes=width*count*8;
                prog.setLayout(8,8,1);
                prog.useAssetProgram("film/reference_pack",true);
                prog.setBufferCompute("packedHalf",packed);
                prog.setVar("originY",y);prog.setVar("rowsU",count);
                glDispatchCompute((width+7)/8,(count+7)/8,1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
                packed.Bind();
                ByteBuffer mapped=(ByteBuffer)glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,bytes,GL_MAP_READ_BIT);
                if(mapped==null)throw new IllegalStateException("Film half-image readback failed");
                try{mapped.position(0);mapped.limit(bytes);destination.put(mapped);}
                finally{if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw new IllegalStateException("Film half-image mapping invalidated");}
                if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("Film half-image transfer error");
            }
            destination.clear();
        }finally{
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,binding[0]);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,generic[0]);
        }
    }

    @Override public void Run(){
        PostPipeline p=(PostPipeline)basePipeline;
        WorkingTexture=previousNode.WorkingTexture;glProg.closed=true;
        ByteBuffer input=null,output=null;
        GLTexture scene=null,result=null;
        try{
            Context context=ScameraApp.getAppContext();
            ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
            ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
            long bytes=(long)WorkingTexture.mSize.x*WorkingTexture.mSize.y*8;
            if(bytes>Integer.MAX_VALUE||memory.availMem<2*bytes+(256L<<20))
                throw new IllegalStateException("Insufficient film memory headroom");
            long budget=Math.min(3072L<<20,Math.max(64L<<20,(memory.availMem-2*bytes)*3/4));
            float forced=PreferenceKeys.hybridValue("film_reference_budget_mb",0f);
            if(Float.isFinite(forced)&&forced>=32f)budget=Math.min(budget,(long)(Math.min(forced,3072f)*(1L<<20)));
            input=Allocator.allocate((int)bytes);output=Allocator.allocate((int)bytes);
            if(input==null||output==null)throw new OutOfMemoryError("Film image buffers");
            scene=new GLTexture(WorkingTexture.mSize,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("why/low",false);
            glProg.setTexture("InputBuffer",WorkingTexture);
            WhyTone.setColour(glProg,p,p.GainMap,1f);
            glProg.setVar("factorU",1);
            glProg.drawBlocks(scene);
            readHalf(glProg,scene,input);
            scene.close();scene=null;
            FilmLut user=p.filmUserStrength>0&&!p.filmUserLut.isEmpty()?FilmLut.chosen(context,p.filmUserLut):null;
            boolean nativeP3=p.p3Output&&user==null;
            FilmReferenceParameters settings=FilmReferenceParameters.from(p.film,nativeP3,1);
            String report=FilmReferenceEngine.render(context,input,output,settings,WorkingTexture.mSize.x,WorkingTexture.mSize.y,budget,0);
            dump(context,input,output,settings,WorkingTexture.mSize.x,WorkingTexture.mSize.y);
            result=new GLTexture(WorkingTexture.mSize,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            int[] previous=new int[1];glGetIntegerv(GL_TEXTURE_BINDING_2D,previous,0);
            glBindTexture(GL_TEXTURE_2D,result.mTextureID);
            try{glTexSubImage2D(GL_TEXTURE_2D,0,0,0,result.mSize.x,result.mSize.y,GL_RGBA,GL_HALF_FLOAT,output);}
            finally{glBindTexture(GL_TEXTURE_2D,previous[0]);}
            if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("Film image upload failed");
            p.referenceFilmSettings=p.film;
            p.referenceFilmTexture=result;result=null;
            p.referenceFilmUserLut=user;p.referenceFilmNativeP3=nativeP3;
            p.referenceFilmApplied=true;
            if(p.film.strength>=1)WorkingTexture=p.referenceFilmTexture;
            else p.film=null;
            Log.i("SCAM_PIPELINE","spectral film "+report+" full="+(p.referenceFilmSettings.strength>=1));
        }catch(RuntimeException|LinkageError|OutOfMemoryError error){
            Log.w("SCAM_PIPELINE","spectral film unavailable; retained legacy film: "+error);
        }finally{
            if(scene!=null)scene.close();if(result!=null)result.close();
            if(input!=null)Allocator.free(input);if(output!=null)Allocator.free(output);
            glProg.closed=true;
        }
    }
}
