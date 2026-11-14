package com.scamera.app.capture;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
public class PreviewFrameMatcherTest {
    @Test public void matchesBothArrivalOrdersWithoutUsingAnotherExposure() {
        List<String> pairs=new ArrayList<>(),released=new ArrayList<>();
        PreviewFrameMatcher<String,String> m=new PreviewFrameMatcher<>((i,r)->pairs.add(i+":"+r),released::add);
        m.image(2,"raw2");m.result(1,"metadata1");assertTrue(pairs.isEmpty());
        m.result(2,"metadata2");m.image(1,"raw1");
        assertEquals(java.util.Arrays.asList("raw2:metadata2","raw1:metadata1"),pairs);assertTrue(released.isEmpty());
    }
    @Test public void missingMetadataCannotStarveReaderAndClearDropsOldSession() {
        List<Integer> released=new ArrayList<>(),delivered=new ArrayList<>();
        PreviewFrameMatcher<Integer,Integer> m=new PreviewFrameMatcher<>((i,r)->delivered.add(i),released::add);
        m.image(1,1);m.image(2,2);m.image(3,3);assertEquals(java.util.Arrays.asList(1),released);
        m.clear();assertEquals(java.util.Arrays.asList(1,2,3),released);
        m.result(3,3);assertTrue(delivered.isEmpty());
    }
    /** P707: a pair is delivered outside the matcher's lock: another thread's clear() does not wait for the delivery. */
    @Test public void deliveryRunsOutsideTheLock() {
        final boolean[] free = new boolean[2];
        final PreviewFrameMatcher<String,String>[] holder = new PreviewFrameMatcher[1];
        holder[0] = new PreviewFrameMatcher<>((i, r) -> {
            Thread other = new Thread(holder[0]::clear);
            other.start();
            try { other.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            free[i.equals("a") ? 0 : 1] = !other.isAlive();
        }, x -> { });
        holder[0].image(1, "a"); holder[0].result(1, "r"); // result-second
        holder[0].result(2, "r"); holder[0].image(2, "b"); // image-second
        assertTrue(free[0]);
        assertTrue(free[1]);
    }

    /** P707: the router delivers outside its lock too, in both arrival orders. */
    @Test public void theRouterDeliversOutsideItsLock() {
        final java.util.List<String> got = new ArrayList<>();
        final TimestampFrameRouter<String>[] holder = new TimestampFrameRouter[1];
        holder[0] = new TimestampFrameRouter<>((image, still) -> {
            Thread other = new Thread(holder[0]::clear);
            other.start();
            try { other.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (!other.isAlive()) got.add(image + still);
        }, x -> { });
        holder[0].image(1, "a"); holder[0].request(1, true);
        holder[0].request(2, false); holder[0].image(2, "b");
        assertEquals(java.util.Arrays.asList("atrue", "bfalse"), got);
    }
}
