package com.particlesdevs.photoncamera.processing.color;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;

public final class FilmReferenceEngine {
    private FilmReferenceEngine() {}
    private static final boolean LOADED;
    static {
        boolean loaded;
        try { System.loadLibrary("scameraFilm"); loaded=true; }
        catch (LinkageError error) { loaded=false; }
        LOADED=loaded;
    }
    private static File resources;
    private static volatile boolean capable;

    public static boolean available() {
        if(!LOADED)return false;
        if(capable)return true;
        try {
            capable=availableNative(resources(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext()).getAbsolutePath());
            return capable;
        } catch(java.io.IOException|RuntimeException|LinkageError|OutOfMemoryError error) {
            return false;
        }
    }

    private static synchronized File resources(Context context) throws java.io.IOException {
        if(resources!=null)return resources;
        File root=new File(context.getCodeCacheDir(),"film-86476afc-pool1");
        copyAssets(context,"film-reference",root);
        resources=root;
        return root;
    }

    private static void copyAssets(Context context,String asset,File target) throws java.io.IOException {
        String[] names=context.getAssets().list(asset);
        if(names!=null&&names.length>0){
            if(!target.isDirectory()&&!target.mkdirs())throw new java.io.IOException("Film resource directory");
            for(String name:names)copyAssets(context,asset+"/"+name,new File(target,name));
        }else{
            File temp=new File(target.getParentFile(),target.getName()+".tmp");
            try(InputStream in=context.getAssets().open(asset);FileOutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[32768];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String render(Context context,ByteBuffer input,ByteBuffer output,
                                FilmReferenceParameters parameters,int width,int height,long budget,double time) {
        if(!LOADED)throw new IllegalStateException("Spectral film library unavailable");
        if(input==null||output==null||parameters==null)throw new IllegalArgumentException("Film buffers");
        try{return renderNative(resources(context).getAbsolutePath(),input,output,parameters.buffer(),width,height,budget,time);}
        catch(java.io.IOException error){throw new IllegalStateException("Film resources unavailable",error);}
    }

    private static native String renderNative(String directory,ByteBuffer input,ByteBuffer output,
                                               ByteBuffer parameters,int width,int height,long budget,double time);
    private static native boolean availableNative(String directory);
}
