package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.nio.ByteBuffer;

final class OpposedWorkList {
    final int groupsX,groupsY;
    final long[][] parts;
    private OpposedWorkList(int x,int y,int count){groupsX=x;groupsY=y;parts=new long[count][(x*y+63)/64];}

    static OpposedWorkList create(int width,int height,int bytes,int count){
        long pixels=(long)width*height,gx=((long)width+15)/16,gy=((long)height+15)/16;
        long groups=gx*gy,words=(groups+63)/64;
        if(width<=0||height<=0||(width&15)!=0||pixels!=bytes||count<=0||count>128||groups>Integer.MAX_VALUE
                ||words*8*(count+2)+groups*4+64L*(count+4)>4L*1024*1024)return null;
        try{return new OpposedWorkList((int)gx,(int)gy,count);}catch(OutOfMemoryError ignored){return null;}
    }

    static long countPart(ByteBuffer flags,int start,int end,int width,OpposedWorkList work,int part){
        long total=0;int i=start;
        long[] mask=work==null?null:work.parts[part];
        for(;i+8<=end;i+=8){
            long value=flags.getLong(i);
            long low=((value&0x0707070707070707L)+0x7F7F7F7F7F7F7F7FL)&0x8080808080808080L;
            long us=~((value&0x3030303030303030L)+0x7F7F7F7F7F7F7F7FL)&0x8080808080808080L;
            total+=Long.bitCount(low&us);
            if(mask!=null){
                long any=((value&0x0F0F0F0F0F0F0F0FL)+0x7F7F7F7F7F7F7F7FL)&us&0x8080808080808080L;
                if(any!=0){int cell=(i/width/16)*work.groupsX+(i%width/16);mask[cell>>>6]|=1L<<(cell&63);}
            }
        }
        for(;i<end;i++){
            int value=flags.get(i)&255;
            if((value&7)!=0&&(value&48)==0)total++;
            if(mask!=null&&(value&15)!=0&&(value&48)==0){int cell=(i/width/16)*work.groupsX+(i%width/16);mask[cell>>>6]|=1L<<(cell&63);}
        }
        return total;
    }

    int[] sparseGroups(){
        try{
            int[] groups=finish();
            return groups.length>0&&groups.length<(long)groupsX*groupsY/2?groups:null;
        }catch(OutOfMemoryError ignored){return null;}
    }

    int[] finish(){
        int words=parts[0].length;long[] combined=new long[words],expanded=new long[words];
        for(long[] mask:parts)for(int i=0;i<words;i++)combined[i]|=mask[i];
        for(int i=0;i<words;i++)for(long bits=combined[i];bits!=0;bits&=bits-1){
            int cell=(i<<6)+Long.numberOfTrailingZeros(bits),y=cell/groupsX,x=cell%groupsX;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                int xx=x+dx,yy=y+dy;if(xx<0||yy<0||xx>=groupsX||yy>=groupsY)continue;
                int next=yy*groupsX+xx;expanded[next>>>6]|=1L<<(next&63);
            }
        }
        int count=0;for(long word:expanded)count+=Long.bitCount(word);
        int[] result=new int[count];int at=0;
        for(int i=0;i<words;i++)for(long bits=expanded[i];bits!=0;bits&=bits-1)result[at++]=(i<<6)+Long.numberOfTrailingZeros(bits);
        return result;
    }
}
