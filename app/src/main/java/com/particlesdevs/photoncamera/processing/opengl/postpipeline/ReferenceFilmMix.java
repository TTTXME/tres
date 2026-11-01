package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.opengl.GLES30;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

final class ReferenceFilmMix extends Node {
    ReferenceFilmMix(){super("","ReferenceFilmMix");}
    @Override public void Compile(){}
    @Override public void Run(){
        PostPipeline p=(PostPipeline)basePipeline;
        WorkingTexture=previousNode.WorkingTexture;glProg.closed=true;
        if(!p.referenceFilmApplied)return;
        int lutTexture=0;
        try{
            FilmLut lut=p.referenceFilmUserLut;
            if(lut==null&&p.referenceFilmSettings.strength>=1){
                WorkingTexture=p.referenceFilmTexture;
                return;
            }
            WorkingTexture=new GLTexture(previousNode.WorkingTexture.mSize,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,
                    GLES30.GL_NEAREST,GLES30.GL_CLAMP_TO_EDGE);
            if(lut!=null)glProg.setDefine("FILM_LUT",1);
            if(p.p3Output&&!p.referenceFilmNativeP3)glProg.setDefine("FILM_TO_P3",1);
            glProg.useAssetProgram("film/reference_mix",false);
            glProg.setTexture("InputBuffer",previousNode.WorkingTexture);
            glProg.setTexture("FilmBuffer",p.referenceFilmTexture);
            glProg.setVar("strengthU",p.referenceFilmSettings.strength);
            if(lut!=null){
                lutTexture=lut.upload();glProg.setTexture3D("FilmLut",lutTexture);
                glProg.setVar("lutU",p.filmUserStrength,(float)lut.size);
                glProg.setVar("lutMinU",lut.domainMin);
                glProg.setVar("lutScaleU",1f/(lut.domainMax[0]-lut.domainMin[0]),1f/(lut.domainMax[1]-lut.domainMin[1]),1f/(lut.domainMax[2]-lut.domainMin[2]));
            }
            glProg.drawBlocks(WorkingTexture);glProg.closed=true;
        }finally{
            p.film=p.referenceFilmSettings;
            if(lutTexture!=0)GLES30.glDeleteTextures(1,new int[]{lutTexture},0);
            if(p.referenceFilmTexture!=null&&p.referenceFilmTexture!=WorkingTexture)p.referenceFilmTexture.close();
            p.referenceFilmTexture=null;
        }
    }
}
