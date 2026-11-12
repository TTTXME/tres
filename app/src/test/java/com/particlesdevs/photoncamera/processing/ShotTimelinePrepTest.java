package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P625: HYBRID JAVA PREP's meta= split into its parts by ScamProcessor's marks; -1 for a part whose mark is missing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ShotTimelinePrepTest {
    @After public void cleanup() throws Exception {
        final Field f = ShotTimeline.class.getDeclaredField("processing");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Test public void theMetaSpanIsSplitIntoItsParts() throws Exception {
        cleanup();
        assertEquals("no shot being processed", "", ShotTimeline.prepParts());
        ShotTimeline.attach(null);
        final long ms = 1_000_000L;
        final String[] names = {"proc", "crop_done", "const_done", "sharp_start", "sharp_done", "levels_done", "dyn_done", "dcg_done",
                "neutral_done", "hybrid_call"};
        final long[] at = {0, 515, 520, 528, 533, 540, 552, 553, 555, 558};
        for (int i = 0; i < names.length; i++) ShotTimeline.markAt(names[i], 1000 * ms + at[i] * ms);
        assertEquals(" (crop=515 const=5 roles=8 levels=7 dyn=12 dcg=1 neutral=2 rest=3)", ShotTimeline.prepParts());
    }

    @Test public void aMissingMarkIsMinusOne() {
        ShotTimeline.attach(null);
        ShotTimeline.markAt("proc", 0);
        ShotTimeline.markAt("crop_done", 4_000_000L);
        assertEquals(" (crop=4 const=-1 roles=-1 levels=-1 dyn=-1 dcg=-1 neutral=-1 rest=-1)", ShotTimeline.prepParts());
    }
}
