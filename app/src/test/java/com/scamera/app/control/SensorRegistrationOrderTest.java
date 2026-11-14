package com.scamera.app.control;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** P705: the camera screen's sensor (un)registrations run off the main thread in the order they were asked for. */
public class SensorRegistrationOrderTest {

    @Test
    public void jobsRunInOrderOnOneThreadOtherThanTheCaller() throws Exception {
        final List<Integer> order = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        for (int i = 0; i < 50; i++) {
            final int n = i;
            SensorThread.register(() -> {
                synchronized (order) {
                    order.add(n);
                    threads.add(Thread.currentThread());
                }
                if (n % 7 == 0) Thread.yield();
            });
        }
        SensorThread.register(done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 50; i++) assertEquals(Integer.valueOf(i), order.get(i));
        for (Thread t : threads) {
            assertTrue(t != Thread.currentThread());
            assertEquals(threads.get(0), t);
        }
    }
}
