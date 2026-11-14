package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;

import com.scamera.app.util.RamClass;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P680: the ZSL ring by the phone's memory class (8 GB or less, or a low-RAM device: a smaller share and a shorter default ring),
 * the prefault in windows ahead of the camera's writes, and the kept ring that stays longer while the memory allows it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ZslRingRamClassTest {
    private static final long SLOT = 24L << 20, GB = 1L << 30;

    @After public void forget() {
        RamClass.forgetForTest();
    }

    private static RamClass phone(long totalBytes, boolean lowRamDevice) {
        final Context context = RuntimeEnvironment.getApplication();
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        info.totalMem = totalBytes;
        info.availMem = totalBytes / 2;
        shadowOf(am).setMemoryInfo(info);
        shadowOf(am).setIsLowRamDevice(lowRamDevice);
        RamClass.forgetForTest();
        return RamClass.get(context);
    }

    @Test public void theMemoryClassComesFromTheActivityManager() {
        assertTrue("a 6 GB phone", phone(6 * GB, false).low());
        assertTrue("an 8 GB phone (reports a little less)", phone(7_600L << 20, false).low());
        assertFalse("a 12 GB phone", phone(11_400L << 20, false).low());
        assertTrue("a low-RAM device whatever its total", phone(12 * GB, true).low());
        assertEquals(12 * GB, phone(12 * GB, true).totalBytes);
    }

    @Test public void anUnknownTotalIsNotLowAndIsAskedAgain() {
        assertFalse(RamClass.low(0, false));
        assertFalse(phone(0, false).low());
        // the next read sees the real total (an unknown answer is not kept)
        assertTrue(phone(4 * GB, false).low());
    }

    @Test public void theDefaultRingOfTheLowClassIsShorterAUserValueStays() {
        assertEquals(38, com.scamera.app.settings.PreferenceKeys.zslBufferCount(50, true));
        assertEquals("the default elsewhere", 50, com.scamera.app.settings.PreferenceKeys.zslBufferCount(50, false));
        assertEquals("a value the user set", 60, com.scamera.app.settings.PreferenceKeys.zslBufferCount(60, true));
        assertEquals(20, com.scamera.app.settings.PreferenceKeys.zslBufferCount(20, true));
    }

    @Test public void aNormalPhoneKeepsTheOldBudget() {
        for (long avail : new long[]{0, 100L << 20, 2560L << 20, 8 * GB})
            assertEquals(ZslRing.budgetSlots(avail, SLOT), ZslRing.budgetSlots(avail, 12 * GB, false, SLOT, 20));
    }

    @Test public void theLowClassTakesASmallerShareOfTheSmallerOfAvailableAndHalfTheTotal() {
        // 8 GB phone, 3 GB available: 28 % of 3 GB = 35 slots (was 40 % = 51)
        assertEquals(35, ZslRing.budgetSlots(3 * GB, 8 * GB, true, SLOT, 20));
        assertEquals(51, ZslRing.budgetSlots(3 * GB, SLOT));
        // 4 GB available just after a kill on a 6 GB phone: half the total (3 GB) is what counts
        assertEquals(35, ZslRing.budgetSlots(4 * GB, 6 * GB, true, SLOT, 20));
        // unknown total: the available alone
        assertEquals(35, ZslRing.budgetSlots(3 * GB, 0, true, SLOT, 20));
        // unknown memory: the class's default ring and its shot
        assertEquals(ZslRing.LOW_RAM_UNKNOWN, ZslRing.budgetSlots(0, 8 * GB, true, SLOT, 20));
    }

    @Test public void theMergesFramesStayReachableWhileTheUsualShareHoldsThem() {
        // 6 GB phone, 2 GB available, a 30-frame merge: the share gives 23, the merge needs 38 - 40 % of 2 GB holds 34
        assertEquals(34, ZslRing.budgetSlots(2 * GB, 6 * GB, true, SLOT, 30));
        // 3 GB available: 40 % holds the 38 the merge needs
        assertEquals(38, ZslRing.budgetSlots(3 * GB, 8 * GB, true, SLOT, 30));
        // little memory: the floor of the low class, the old floor of 12 for the merge's frames
        assertEquals(12, ZslRing.budgetSlots(100L << 20, 4 * GB, true, SLOT, 20));
        assertEquals(ZslRing.LOW_RAM_FLOOR, ZslRing.budgetSlots(100L << 20, 4 * GB, true, SLOT, -8));
    }

    @Test public void thePrefaultRunsAWindowAheadOfTheCamerasWrites() {
        final int w = ZslRing.PREFAULT_WINDOW;
        assertTrue(w >= 4 && w <= 8);
        assertEquals("the first window", w, ZslRing.nextPrefaultEnd(-1, 0, 34, w));
        // the camera at slot 1 of [0, 6): still half a window ahead, nothing due
        assertEquals(w, ZslRing.nextPrefaultEnd(1, w, 34, w));
        // at slot 3: the next window [6, 12)
        assertEquals(2 * w, ZslRing.nextPrefaultEnd(3, w, 34, w));
        // never past the ring part
        assertEquals(34, ZslRing.nextPrefaultEnd(31, 30, 34, w));
        assertEquals(34, ZslRing.nextPrefaultEnd(33, 34, 34, w));
        // a camera that outran the prefault: the window starts after its slot
        assertEquals(20 + w, ZslRing.nextPrefaultEnd(19, 12, 34, w));
        // no ring part (FULLRES, the switch off)
        assertEquals(0, ZslRing.nextPrefaultEnd(5, 0, 0, w));
        // every slot of the ring part is handed out exactly once, in order
        int to = ZslRing.nextPrefaultEnd(-1, 0, 50, w), handed = to;
        for (int slot = 0; slot < 50; slot++) {
            assertTrue("slot " + slot + " prefaulted before the camera writes it", slot < to);
            final int next = ZslRing.nextPrefaultEnd(slot, to, 50, w);
            assertTrue(next - to <= w + w);
            handed += next - to;
            to = next;
        }
        assertEquals(50, handed);
    }

    @Test public void aKeptRingStaysLongerOnlyWhileTheMemoryAllowsIt() {
        assertTrue(CaptureController.keepParkedRing(3000, 4 * GB, false, false));
        assertTrue(CaptureController.keepParkedRing(12_000, 4 * GB, false, false));
        assertFalse("at most PARKED_RING_MAX_MS", CaptureController.keepParkedRing(12_001, 4 * GB, false, false));
        assertFalse("the system low on memory", CaptureController.keepParkedRing(3000, 4 * GB, true, false));
        assertFalse("a phone of the low class", CaptureController.keepParkedRing(3000, 4 * GB, false, true));
        assertFalse("too little beside it", CaptureController.keepParkedRing(3000, GB, false, false));
        assertFalse("memory unknown", CaptureController.keepParkedRing(3000, -1, false, false));
    }
}
