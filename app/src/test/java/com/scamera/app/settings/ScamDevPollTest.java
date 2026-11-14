package com.scamera.app.settings;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** P705: scam_dev.txt is looked for every 2 s while there is one, every 10 s while there is none; the app's own edit at once. */
public class ScamDevPollTest {

    @Test
    public void thePeriodFollowsWhetherTheFileWasThere() {
        assertEquals(2_000_000_000L, PreferenceKeys.scamDevPeriod(true));
        assertEquals(10_000_000_000L, PreferenceKeys.scamDevPeriod(false));
    }

    @Test
    public void theAppsOwnEditIsDueAtTheNextRead() throws Exception {
        PreferenceKeys.scamDevChanged();
        final Field checked = PreferenceKeys.class.getDeclaredField("scamDevChecked");
        checked.setAccessible(true);
        final long since = System.nanoTime() - (long) checked.get(null);
        assertTrue("due even after the absent file's period", since > PreferenceKeys.SCAM_DEV_ABSENT_PERIOD_NS);
    }
}
