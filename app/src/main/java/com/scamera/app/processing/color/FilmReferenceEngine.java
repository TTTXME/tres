package com.scamera.app.processing.color;

import android.content.Context;
import java.io.File;
import java.nio.ByteBuffer;

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
    static final int DEFAULT_QUEUE_POLICY=1;
    // A probe may finish after another policy's probe; publish its key and result together.
    private static volatile Capability capability;

    private static final class Capability {
        final int policy;
        final boolean supported;
        Capability(int policy,boolean supported){this.policy=policy;this.supported=supported;}
    }

    interface CapabilityProbe { boolean available(int policy) throws java.io.IOException; }

    static boolean available(int policy,CapabilityProbe probe) throws java.io.IOException {
        Capability cached=capability;
        if(cached!=null&&cached.policy==policy&&cached.supported)return true;
        boolean supported=probe.available(policy);
        capability=new Capability(policy,supported);
        return supported;
    }

    static void resetCapabilityForTests(){capability=null;}

    static int queuePolicy(float value){return value==0f?0:DEFAULT_QUEUE_POLICY;}

    private static int queuePolicy(){
        try{return queuePolicy(com.scamera.app.settings.PreferenceKeys.scamDevNumber("film_vk_queue_policy",DEFAULT_QUEUE_POLICY));}
        catch(RuntimeException|LinkageError error){return DEFAULT_QUEUE_POLICY;}
    }

    public static boolean available() {
        if(!LOADED)return false;
        final int policy=queuePolicy();
        try {
            return available(policy,selected->{
                final long started=System.nanoTime();
                boolean supported=availableWithQueueNative(resources(com.scamera.app.app.ScameraApp.getAppContext()).getAbsolutePath(),selected);
                com.scamera.app.util.Log.i("SCAM_PIPELINE","spectral film initialization available="+supported
                        +" queuePolicy="+selected+" ms="+(System.nanoTime()-started)/1_000_000);
                return supported;
            });
        } catch(java.io.IOException|RuntimeException|LinkageError|OutOfMemoryError error) {
            return false;
        }
    }

    private static synchronized File resources(Context context) throws java.io.IOException {
        if(resources!=null)return resources;
        File root=new File(context.getCodeCacheDir(),"film-native-v1");
        if(!root.isDirectory()&&!root.mkdirs())throw new java.io.IOException("Film cache directory");
        resources=root;
        return root;
    }

    public static String render(Context context,ByteBuffer input,ByteBuffer output,
                                FilmReferenceParameters parameters,int width,int height,long budget,double time) {
        if(!LOADED)throw new IllegalStateException("Spectral film library unavailable");
        if(input==null||output==null||parameters==null)throw new IllegalArgumentException("Film buffers");
        final int policy=queuePolicy();
        try{return renderWithQueueNative(resources(context).getAbsolutePath(),input,output,parameters.buffer(),width,height,budget,time,policy);}
        catch(java.io.IOException error){throw new IllegalStateException("Film resources unavailable",error);}
    }

    private static native String renderNative(String directory,ByteBuffer input,ByteBuffer output,
                                               ByteBuffer parameters,int width,int height,long budget,double time);
    private static native boolean availableNative(String directory);
    private static native boolean availableWithQueueNative(String directory,int policy);
    private static native String renderWithQueueNative(String directory,ByteBuffer input,ByteBuffer output,
                                                       ByteBuffer parameters,int width,int height,long budget,double time,int policy);
}
