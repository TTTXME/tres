package com.scamera.app.capture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Pairs camera-start metadata and images in either arrival order. Owns pending images until delivery. P707: an image is delivered
 * after the router's lock is left (the caller's thread, as before).
 */
public final class TimestampFrameRouter<T> {
    private final LinkedHashMap<Long,Boolean> requests=new LinkedHashMap<>();
    private final LinkedHashMap<Long,T> pending=new LinkedHashMap<>();
    private final BiConsumer<T,Boolean> deliver;
    private final Consumer<T> release;
    public TimestampFrameRouter(BiConsumer<T,Boolean> deliver,Consumer<T> release){this.deliver=deliver;this.release=release;}
    public void request(long timestamp,boolean still){
        final T image;
        synchronized(this){
            image=pending.remove(timestamp);
            if(image==null){
                requests.put(timestamp,still);
                while(requests.size()>64)requests.remove(requests.keySet().iterator().next());
                return;
            }
        }
        deliver.accept(image,still);
    }
    public void image(long timestamp,T image){
        final Boolean still;
        synchronized(this){
            still=requests.remove(timestamp);
            if(still==null){
                T duplicate=pending.put(timestamp,image);if(duplicate!=null)release.accept(duplicate);
                // Leave spare slots in a six-image reader so missing metadata cannot stall the camera.
                while(pending.size()>3){Map.Entry<Long,T> old=pending.entrySet().iterator().next();pending.remove(old.getKey());release.accept(old.getValue());}
                return;
            }
        }
        deliver.accept(image,still);
    }
    public synchronized void clear(){for(T image:pending.values())release.accept(image);pending.clear();requests.clear();}
}
