package com.scamera.app.processing.opengl.postpipeline;

import android.opengl.GLES30;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;

final class ReferenceFilmMix extends Node {
    ReferenceFilmMix(){super("","ReferenceFilmMix");}
    @Override public void bindShader(){}
    @Override public void runPass(){
        PostPipeline p=(PostPipeline)hostPipeline;
        passOut=prevPass.passOut;shaderProg.closed=true;
        if(!p.referenceFilmApplied)return;
        int lutTexture=0;
        try{
            FilmLut lut=p.referenceFilmUserLut;
            if(lut==null&&p.referenceFilmSettings.strength>=1){
                passOut=p.referenceFilmTexture;
                return;
            }
            passOut=new GLTexture(prevPass.passOut.size,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,
                    GLES30.GL_NEAREST,GLES30.GL_CLAMP_TO_EDGE);
            if(lut!=null)shaderProg.setDefine("FILM_LUT",1);
            if(p.p3Output&&!p.referenceFilmNativeP3)shaderProg.setDefine("FILM_TO_P3",1);
            shaderProg.useAssetProgram("film/reference_mix",false);
            shaderProg.setTexture("InputBuffer",prevPass.passOut);
            shaderProg.setTexture("FilmBuffer",p.referenceFilmTexture);
            shaderProg.setVar("strengthU",p.referenceFilmSettings.strength);
            if(lut!=null){
                lutTexture=lut.upload();shaderProg.setTexture3D("FilmLut",lutTexture);
                shaderProg.setVar("lutU",p.filmUserStrength,(float)lut.size);
                shaderProg.setVar("lutMinU",lut.domainMin);
                shaderProg.setVar("lutScaleU",1f/(lut.domainMax[0]-lut.domainMin[0]),1f/(lut.domainMax[1]-lut.domainMin[1]),1f/(lut.domainMax[2]-lut.domainMin[2]));
            }
            shaderProg.drawBlocks(passOut);shaderProg.closed=true;
        }finally{
            p.film=p.referenceFilmSettings;
            if(lutTexture!=0)GLES30.glDeleteTextures(1,new int[]{lutTexture},0);
            if(p.referenceFilmTexture!=null&&p.referenceFilmTexture!=passOut)p.referenceFilmTexture.close();
            p.referenceFilmTexture=null;
        }
    }
}
