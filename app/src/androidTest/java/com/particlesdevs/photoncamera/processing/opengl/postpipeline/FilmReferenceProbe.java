package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import android.graphics.Point;
import android.util.Half;
import com.particlesdevs.photoncamera.processing.color.FilmReferenceEngine;
import com.particlesdevs.photoncamera.processing.color.FilmReferenceParameters;
import com.particlesdevs.photoncamera.processing.opengl.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static android.opengl.GLES31.*;

public final class FilmReferenceProbe {
    public static String verify(Context context){
        GLFormat format=new GLFormat(GLFormat.DataType.FLOAT_16,4);
        try(GLCoreBlockProcessing core=new GLCoreBlockProcessing(new Point(1,1),format,GLDrawParams.Allocate.None);
            GLProg prog=new GLProg();
            GLTexture source=new GLTexture(new Point(128,128),format,null,GL_NEAREST,GL_CLAMP_TO_EDGE)){
            ByteBuffer input=ByteBuffer.allocateDirect(65536*2).order(ByteOrder.nativeOrder());
            ByteBuffer copied=ByteBuffer.allocateDirect(input.capacity()).order(ByteOrder.nativeOrder());
            for(int i=0;i<65536;i++)input.putShort(i*2,(short)i);
            glBindTexture(GL_TEXTURE_2D,source.mTextureID);
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,128,128,GL_RGBA,GL_HALF_FLOAT,input);
            for(int rows:new int[]{32,47,256}){
                ReferenceFilm.readHalf(prog,source,copied,rows);
                for(int i=0;i<65536;i++)if(input.getShort(i*2)!=copied.getShort(i*2))
                    throw new AssertionError("Film transfer changed half bits at "+i+" rows="+rows);
            }
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Film transfer GL state");
        }
        int width=128,height=96;
        ByteBuffer input=ByteBuffer.allocateDirect(width*height*8).order(ByteOrder.nativeOrder());
        ByteBuffer output=ByteBuffer.allocateDirect(input.capacity()).order(ByteOrder.nativeOrder());
        ByteBuffer second=ByteBuffer.allocateDirect(input.capacity()).order(ByteOrder.nativeOrder());
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            input.putShort(Half.toHalf((float)Math.pow(2,x/(double)(width-1)*10-8)));
            input.putShort(Half.toHalf((float)Math.pow(2,y/(double)(height-1)*8-7)));
            input.putShort(Half.toHalf(.15f));input.putShort(Half.toHalf(1f));
        }
        input.clear();
        FilmReferenceParameters params=new FilmReferenceParameters().integer(0,1).integer(56,1).integer(60,4);
        String report=FilmReferenceEngine.render(context,input,output,params,width,height,64L<<20,0);
        FilmReferenceEngine.render(context,input,second,params,width,height,64L<<20,0);
        for(int i=0;i<input.capacity();i+=2){
            if(output.getShort(i)!=second.getShort(i))throw new AssertionError("Film JNI repeat differs");
            if(!Float.isFinite(Half.toFloat(output.getShort(i))))throw new AssertionError("Film output not finite");
        }
        boolean refused=false;
        try{FilmReferenceEngine.render(context,input,input,params,width,height,64L<<20,0);}
        catch(IllegalStateException expected){refused=true;}
        if(!refused)throw new AssertionError("Film accepted overlapping input/output");
        params.real(196,1f).integer(256,1).integer(436,1).integer(492,1);
        refused=false;
        ByteBuffer largeInput=ByteBuffer.allocateDirect(512*384*8),largeOutput=ByteBuffer.allocateDirect(512*384*8);
        try{FilmReferenceEngine.render(context,largeInput,largeOutput,params,512,384,32L<<20,0);}
        catch(IllegalStateException expected){refused=true;}
        if(!refused)throw new AssertionError("Film scratch budget was not enforced");
        params.real(196,0f).integer(256,0).integer(436,0).integer(492,0);
        FilmReferenceEngine.render(context,input,second,params,width,height,64L<<20,0);
        for(int i=0;i<input.capacity();i++)if(output.get(i)!=second.get(i))
            throw new AssertionError("Film did not recover after a refused request");
        return "65536 half codes exact; JNI/repeat/overlap/recovery passed; tinyBudgetRefused="+refused+" "+report;
    }
}
