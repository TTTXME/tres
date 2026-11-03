package com.particlesdevs.photoncamera.processing.color;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class FilmReferenceEngineTest {
    @Before public void setup(){FilmReferenceEngine.resetCapabilityForTests();}
    @After public void finish(){FilmReferenceEngine.resetCapabilityForTests();}

    @Test public void interactiveIsTheDefaultAndZeroIsTheExplicitRollback(){
        assertEquals(1,FilmReferenceEngine.DEFAULT_QUEUE_POLICY);
        assertEquals(0,FilmReferenceEngine.queuePolicy(0));
        assertEquals(0,FilmReferenceEngine.queuePolicy(-0f));
        for(float value:new float[]{1,2,-1,.5f,Float.NaN,Float.POSITIVE_INFINITY})
            assertEquals(1,FilmReferenceEngine.queuePolicy(value));
    }

    @Test public void bothLegacyAndPerCallQueueBindingsKeepTheirSignatures() throws Exception {
        Class<?>[] legacy={String.class,ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,int.class,int.class,long.class,double.class};
        assertTrue(Modifier.isNative(FilmReferenceEngine.class.getDeclaredMethod("renderNative",legacy).getModifiers()));
        assertTrue(Modifier.isNative(FilmReferenceEngine.class.getDeclaredMethod("availableNative",String.class).getModifiers()));
        Class<?>[] queued=java.util.Arrays.copyOf(legacy,legacy.length+1);
        queued[legacy.length]=int.class;
        assertTrue(Modifier.isNative(FilmReferenceEngine.class.getDeclaredMethod("renderWithQueueNative",queued).getModifiers()));
        assertTrue(Modifier.isNative(FilmReferenceEngine.class.getDeclaredMethod("availableWithQueueNative",String.class,int.class).getModifiers()));
    }

    @Test public void cachedCapabilityBelongsOnlyToItsPolicy() throws Exception {
        AtomicInteger probes=new AtomicInteger();
        FilmReferenceEngine.CapabilityProbe probe=policy->{probes.incrementAndGet();return policy==1;};
        assertTrue(FilmReferenceEngine.available(1,probe));
        assertTrue(FilmReferenceEngine.available(1,probe));
        assertEquals(1,probes.get());
        assertFalse(FilmReferenceEngine.available(0,probe));
        assertEquals(2,probes.get());
        assertTrue(FilmReferenceEngine.available(1,probe));
        assertEquals(3,probes.get());
    }

    @Test public void aTemporaryFailureIsRetriedForTheSamePolicy() throws Exception {
        AtomicInteger probes=new AtomicInteger();
        FilmReferenceEngine.CapabilityProbe probe=policy->probes.incrementAndGet()>1;
        assertFalse(FilmReferenceEngine.available(1,probe));
        assertTrue(FilmReferenceEngine.available(1,probe));
        assertTrue(FilmReferenceEngine.available(1,probe));
        assertEquals(2,probes.get());
    }

    @Test public void anOlderProbeCannotPublishItsResultUnderTheNewPolicy() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(2);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<Boolean> old=executor.submit(()->FilmReferenceEngine.available(0,policy->{
                assertFalse(Thread.holdsLock(FilmReferenceEngine.class));
                started.countDown();
                try {assertTrue(release.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
                return true;
            }));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            Future<Boolean> current=executor.submit(()->FilmReferenceEngine.available(1,policy->{
                assertEquals(1,policy);
                assertFalse(Thread.holdsLock(FilmReferenceEngine.class));
                return false;
            }));
            assertFalse("A slow probe must not hold a Java monitor around native work",current.get(2,TimeUnit.SECONDS));
            release.countDown();
            assertTrue(old.get(2,TimeUnit.SECONDS));
            AtomicInteger checked=new AtomicInteger(-1);
            assertFalse(FilmReferenceEngine.available(1,policy->{checked.set(policy);return false;}));
            assertEquals("The older success must not satisfy policy1",1,checked.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
