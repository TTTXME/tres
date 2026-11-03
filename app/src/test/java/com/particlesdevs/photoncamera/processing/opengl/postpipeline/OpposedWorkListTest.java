package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
public final class OpposedWorkListTest {
    static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static long scalar(byte[] flags){long count=0;for(byte flag:flags){int f=flag&255;if((f&7)!=0&&(f&48)==0)count++;}return count;}
    static int[] oracle(byte[] flags,int width,int height){
        int gx=(width+15)/16,gy=(height+15)/16;boolean[] occupied=new boolean[gx*gy];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int f=flags[y*width+x]&255;if((f&15)==0||(f&48)!=0)continue;
            // Independent pixel-halo test, wider than the shader's3-pixel halo by design.
            for(int yy=Math.max(0,y/16-1);yy<=Math.min(gy-1,y/16+1);yy++)
                for(int xx=Math.max(0,x/16-1);xx<=Math.min(gx-1,x/16+1);xx++)occupied[yy*gx+xx]=true;
        }
        return java.util.stream.IntStream.range(0,occupied.length).filter(i->occupied[i]).toArray();
    }
    static void check(byte[] flags,int width,int height,int parts)throws Exception{
        OpposedWorkList work=OpposedWorkList.create(width,height,flags.length,parts);
        boolean eligible=width>0&&height>0&&(width&15)==0&&(long)width*height==flags.length;
        require((work!=null)==eligible,"Eligibility");ByteBuffer buffer=ByteBuffer.wrap(flags);int n=flags.length,step=((n+parts-1)/parts+7)&~7;
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(4,parts));List<Future<Long>> results=new ArrayList<>();
        try{for(int p=0;p<parts;p++){final int part=p,start=Math.min(n,p*step),end=Math.min(n,start+step);results.add(pool.submit(()->OpposedWorkList.countPart(buffer,start,end,width,work,part)));}
            long count=0;for(Future<Long> result:results)count+=result.get();require(count==scalar(flags),"Count differs");
            if(work!=null)require(Arrays.equals(work.finish(),oracle(flags,width,height)),"Halo/list differs");
        }finally{pool.shutdownNow();}
    }
    @org.junit.Test public void parallelCountsAndHaloMatchIndependentPixelOracle()throws Exception{
        Random random=new Random(46599);int tests=0;
        for(int w:new int[]{1,16,17,32,128})for(int h:new int[]{1,3,17,41})for(int parts:new int[]{1,2,4,7,16}){
            byte[] f=new byte[w*h];random.nextBytes(f);check(f,w,h,parts);tests++;
            Arrays.fill(f,(byte)0);f[f.length-1]=8;check(f,w,h,parts);tests++;
        }
        byte[] all=new byte[16*16];for(int i=0;i<all.length;i++)all[i]=(byte)i;check(all,16,16,16);tests++;
        require(OpposedWorkList.create(4096,3072,4096*3072,129)==null,"Part cap");
        require(OpposedWorkList.create(4096,3072,1,16)==null,"Bad capacity");
        require(OpposedWorkList.create(Integer.MAX_VALUE,2,2,16)==null,"Overflow geometry");
        OpposedWorkList empty=OpposedWorkList.create(128,128,128*128,4);
        require(empty.sparseGroups()==null,"Empty must use full fallback");
        byte[] dense=new byte[128*128];Arrays.fill(dense,(byte)1);
        OpposedWorkList allWork=OpposedWorkList.create(128,128,dense.length,4);
        OpposedWorkList.countPart(ByteBuffer.wrap(dense),0,dense.length,128,allWork,0);
        require(allWork.sparseGroups()==null,"Dense must use full fallback");
        byte[] sparse=new byte[128*128];sparse[17*128+17]=8;
        OpposedWorkList few=OpposedWorkList.create(128,128,sparse.length,4);
        OpposedWorkList.countPart(ByteBuffer.wrap(sparse),0,sparse.length,128,few,0);
        require(Arrays.equals(few.sparseGroups(),oracle(sparse,128,128)),"Sparse list must retain pixel halo");
        System.out.println("PASS "+tests+" actual parallel scan/independent scalar+halo fixtures; malformed/overflow caps");
    }
}
