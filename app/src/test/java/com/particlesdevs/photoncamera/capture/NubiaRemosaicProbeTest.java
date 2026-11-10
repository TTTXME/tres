package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * P598: the nubia maximum-resolution FULLRES's settings against the HAL's remosaic (NubiaRemosaicProbe), with the session keys
 * of the NX721J's camera 2 (SCAM TEST 2026-11-09 17:13, «Available session keys») and the stock camera's values.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class NubiaRemosaicProbeTest {
    /** The vendor session keys of the NX721J's rear cameras (the report lists the same 59 on cameras 0, 2, 3 and 4). */
    static final List<String> NX721J = Arrays.asList(
            "android.control.aeTargetFpsRange", "android.control.extendedSceneMode", "android.control.videoStabilizationMode",
            "com.zte.camera.sessionParameters.ZTEOfflineSession", "com.zte.camera.sessionParameters.feature",
            "com.zte.camera.sessionParameters.forcesingleife", "com.zte.camera.sessionParameters.function",
            "com.zte.camera.sessionParameters.rawaiprocess", "com.zte.camera.sessionParameters.reprocesrawbpp",
            "com.zte.camera.sessionParameters.skip_stream_check", "org.codeaurora.qcamera3.sessionParameters.EISMode",
            "org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW", "org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom",
            "org.codeaurora.qcamera3.sessionParameters.EnableXCFAOptimization", "org.codeaurora.qcamera3.sessionParameters.RawCbSourceType",
            "org.codeaurora.qcamera3.sessionParameters.availableStreamMap");
    static final String BAYER = "nubia: the maximum-resolution frames are remosaicked by the HAL (plain Bayer, no mosaic)";
    static final String STAMP = "r1 nubia/NX721J/1";

    private SharedPreferences p;

    @Before public void setUp() {
        p = RuntimeEnvironment.getApplication().getSharedPreferences("nubia_probe", 0);
        p.edit().clear().commit();
        NubiaRemosaicProbe.resetForTest(p);
    }

    @After public void tearDown() {
        NubiaRemosaicProbe.resetForTest(null);
        NubiaFullRes.resetForTest();
        ShadowBuild.reset();
    }

    private static String ids(List<NubiaRemosaicProbe.Candidate> list) {
        final StringBuilder sb = new StringBuilder();
        for (NubiaRemosaicProbe.Candidate c : list) sb.append(sb.length() == 0 ? "" : " ").append(c.id);
        return sb.toString();
    }

    @Test public void theCameraListsTheKeysOfEverySetting() {
        assertEquals("plain zte_qcfa zte_qcfa_mode rawcb_1 rawcb_2 rawcb_3 xcfa_opt ideal_raw", ids(NubiaRemosaicProbe.applicable(NX721J)));
        assertEquals("a camera without vendor session keys: the plain session only", "plain",
                ids(NubiaRemosaicProbe.applicable(Collections.singletonList("android.control.aeTargetFpsRange"))));
        assertEquals("not learned: the plain session only", "plain", ids(NubiaRemosaicProbe.applicable(null)));
        assertEquals("no ZTE keys: the Qualcomm ones", "plain rawcb_1 rawcb_2 rawcb_3 xcfa_opt ideal_raw", ids(NubiaRemosaicProbe.applicable(Arrays.asList(
                NubiaRemosaicProbe.RAW_CB_SOURCE, NubiaRemosaicProbe.XCFA_OPTIMIZATION, NubiaRemosaicProbe.IDEAL_RAW))));
    }

    @Test public void theStockCamerasValues() {
        final NubiaRemosaicProbe.Candidate keys = NubiaRemosaicProbe.byId("zte_qcfa");
        assertNotNull(keys);
        assertEquals(0, keys.operationMode);
        assertEquals(2, keys.keys.size());
        assertEquals("com.zte.camera.sessionParameters.feature", keys.keys.get(0).name);
        assertEquals("the QCFA picture size's feature bit", 0x20, keys.keys.get(0).value);
        assertTrue(keys.keys.get(0).session);
        assertEquals("com.zte.chi.control.CustomRemosiac", keys.keys.get(1).name);
        assertEquals((byte) 1, keys.keys.get(1).value);
        assertFalse("not a listed session key: on every request", keys.keys.get(1).session);
        final NubiaRemosaicProbe.Candidate mode = NubiaRemosaicProbe.byId("zte_qcfa_mode");
        assertNotNull(mode);
        assertEquals("QcfaStreamController's session mode", 0x8012, mode.operationMode);
        assertTrue(mode.describe(), mode.describe().contains("session mode 0x8012"));
        assertTrue(mode.describe(), mode.describe().contains("com.nubia.chi.app_mode=1 (request)"));
        assertTrue(NubiaRemosaicProbe.byId("plain").describe().startsWith("no vendor key"));
        assertEquals(2, NubiaRemosaicProbe.byId("rawcb_2").keys.get(0).value);
        assertEquals((byte) 1, NubiaRemosaicProbe.byId("xcfa_opt").keys.get(0).value);
        assertEquals("plain", NubiaRemosaicProbe.numbered(1).id);
        assertEquals("ideal_raw", NubiaRemosaicProbe.numbered(8).id);
        assertNull(NubiaRemosaicProbe.numbered(0));
        assertNull(NubiaRemosaicProbe.numbered(9));
    }

    @Test public void oneSettingASessionUntilAllFailedThenOneNotice() {
        final List<NubiaRemosaicProbe.Candidate> all = NubiaRemosaicProbe.applicable(NX721J);
        for (int i = 0; i < all.size(); i++) {
            final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0);
            assertNotNull(c);
            assertEquals(all.get(i).id, c.candidate.id);
            assertEquals(i + 1, c.index);
            assertEquals(8, c.total);
            assertEquals(1, c.start);
            assertFalse(NubiaRemosaicProbe.exhausted(p, "2", NX721J, STAMP));
            final NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.fail(p, NX721J, STAMP, c, i == 2 ? "session configuration failed" : BAYER);
            assertEquals("another setting is left", i < all.size() - 1, o.next);
            if (o.next) assertTrue(o.message, o.message.contains((i + 1) + " ") && o.message.contains("8"));
            else assertEquals("the one notice", NubiaRemosaicProbe.unavailable(), o.message);
        }
        assertNull("none left: the normal session", NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0));
        assertTrue(NubiaRemosaicProbe.exhausted(p, "2", NX721J, STAMP));
        assertFalse("another camera has its own trials", NubiaRemosaicProbe.exhausted(p, "0", NX721J, STAMP));
        final Map<String, String> fails = NubiaRemosaicProbe.fails(p, "2", STAMP);
        assertEquals(8, fails.size());
        assertEquals(FullResTrials.HW_REMOSAIC, fails.get("plain"));
        assertEquals(FullResTrials.REFUSED, fails.get("zte_qcfa_mode"));
        final String status = NubiaRemosaicProbe.status(p, "2", NX721J, STAMP);
        assertNotNull(status);
        assertTrue(status, status.startsWith(NubiaRemosaicProbe.unavailable()));
        assertTrue(status, status.contains("8 (") && status.contains("«zte_qcfa_mode»"));
        assertNull("no trial: nothing said", NubiaRemosaicProbe.status(p, "0", NX721J, STAMP));
    }

    @Test public void aQuadKeepsTheSettingAndALaterBayerMovesOn() {
        NubiaRemosaicProbe.fail(p, NX721J, STAMP, NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0), BAYER);
        final NubiaRemosaicProbe.Choice stock = NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0);
        assertEquals("zte_qcfa", stock.candidate.id);
        assertTrue("newly kept", NubiaRemosaicProbe.succeed(p, STAMP, stock));
        assertEquals("zte_qcfa", NubiaRemosaicProbe.kept(p, "2", STAMP));
        final NubiaRemosaicProbe.Choice again = NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0);
        assertEquals("the kept setting every session", "zte_qcfa", again.candidate.id);
        assertTrue(again.known);
        assertEquals(0, again.start);
        assertFalse("known: no second notice", NubiaRemosaicProbe.succeed(p, STAMP, again));
        assertFalse(NubiaRemosaicProbe.exhausted(p, "2", NX721J, STAMP));
        assertTrue(NubiaRemosaicProbe.status(p, "2", NX721J, STAMP).contains("«zte_qcfa»"));
        // a later firmware-free failure of the kept one (its frames a plain Bayer again): no longer kept, the next one
        final NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.fail(p, NX721J, STAMP, again, BAYER);
        assertTrue(o.next);
        assertNull(NubiaRemosaicProbe.kept(p, "2", STAMP));
        assertEquals("zte_qcfa_mode", NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0).candidate.id);
    }

    @Test public void aSettingWithoutAVerdictGetsThreeSessions() {
        for (int start = 1; start <= NubiaRemosaicProbe.STARTS; start++) {
            final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0);
            assertEquals("plain", c.candidate.id);
            assertEquals(start, c.start);
        }
        final NubiaRemosaicProbe.Choice next = NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0);
        assertEquals("three sessions without a verdict fail it", "zte_qcfa", next.candidate.id);
        assertEquals(NubiaRemosaicProbe.NO_VERDICT, NubiaRemosaicProbe.fails(p, "2", STAMP).get("plain"));
        assertTrue(NubiaRemosaicProbe.status(p, "2", NX721J, STAMP).contains(NubiaRemosaicProbe.phrase(NubiaRemosaicProbe.NO_VERDICT)));
    }

    @Test public void aForcedSettingRunsWhateverTheTrialsSay() {
        NubiaRemosaicProbe.Choice c;
        while ((c = NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0)) != null) NubiaRemosaicProbe.fail(p, NX721J, STAMP, c, BAYER);
        assertTrue(NubiaRemosaicProbe.exhausted(p, "2", NX721J, STAMP));
        final NubiaRemosaicProbe.Choice forced = NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 4);
        assertNotNull(forced);
        assertEquals("rawcb_1", forced.candidate.id);
        assertTrue(forced.forced);
        assertEquals(4, forced.index);
        final NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.fail(p, NX721J, STAMP, forced, BAYER);
        assertFalse("a forced setting never goes on to another", o.next);
        assertTrue(o.message, o.message.contains("nubia_probe_force"));
        // a forced Quad is kept as well
        assertTrue(NubiaRemosaicProbe.succeed(p, STAMP, NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 8)));
        assertEquals("ideal_raw", NubiaRemosaicProbe.kept(p, "2", STAMP));
        assertFalse(NubiaRemosaicProbe.exhausted(p, "2", NX721J, STAMP));
        assertNull("a forced setting whose session key the camera does not list",
                NubiaRemosaicProbe.choose(p, "3", Collections.singletonList("android.control.aeTargetFpsRange"), STAMP, 2));
    }

    @Test public void theTrialsAreThisFirmwaresAndTryAgainForgetsThem() {
        NubiaRemosaicProbe.fail(p, NX721J, STAMP, NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0), BAYER);
        assertEquals("zte_qcfa", NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0).candidate.id);
        assertEquals("a firmware update starts again", "plain", NubiaRemosaicProbe.choose(p, "2", NX721J, "r1 nubia/NX721J/2", 0).candidate.id);
        NubiaRemosaicProbe.fail(p, NX721J, STAMP, NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0), BAYER);
        assertTrue("«Попробовать снова» (FullResTrials.retry) forgets the probe with the rows", FullResTrials.clear(p, NubiaFullRes.base("2")) > 0);
        assertTrue(NubiaRemosaicProbe.fails(p, "2", STAMP).isEmpty());
        assertEquals("plain", NubiaRemosaicProbe.choose(p, "2", NX721J, STAMP, 0).candidate.id);
        // scam_dev nubia_probe_reset forgets the probe's keys only (a row's trial of the Magisk module's table stays)
        p.edit().putInt(NubiaFullRes.base("2"), 1).commit();
        assertTrue(NubiaRemosaicProbe.reset(p, "2") > 0);
        assertNull(p.getString(NubiaFullRes.base("2") + NubiaRemosaicProbe.RUN, null));
        assertEquals(1, p.getInt(NubiaFullRes.base("2"), 0));
    }

    @Test public void theSessionRunsItsSettingAndTheVerdictsMoveIt() {
        ShadowBuild.setManufacturer("nubia");
        ShadowBuild.setBrand("nubia");
        ShadowBuild.setFingerprint("nubia/NX721J/test");
        NubiaRemosaicProbe.listedForTest("2", NX721J);
        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertTrue(NubiaRemosaicProbe.running("2"));
        assertFalse(NubiaRemosaicProbe.running("0"));
        assertEquals("|probe-plain", NubiaRemosaicProbe.streamKey());
        assertFalse("the plain session has no session parameters", NubiaRemosaicProbe.wantsSession("2"));
        assertEquals(0, NubiaRemosaicProbe.operationMode("2"));
        assertNull("another camera's failure is not this session's", NubiaRemosaicProbe.failed("0", BAYER));
        NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.failed("2", BAYER);
        assertTrue(o.next);
        assertFalse("the session's setting is done", NubiaRemosaicProbe.running("2"));
        assertNull("said once", NubiaRemosaicProbe.failed("2", BAYER));

        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertTrue(NubiaRemosaicProbe.wantsSession("2"));
        assertTrue(NubiaRemosaicProbe.describe(), NubiaRemosaicProbe.describe().contains("com.zte.camera.sessionParameters.feature=32"));
        o = NubiaRemosaicProbe.failed("2", "camera error 4");
        assertTrue(o.next);
        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertEquals("the stock QCFA mode's session mode", 0x8012, NubiaRemosaicProbe.operationMode("2"));
        final String kept = NubiaRemosaicProbe.quad("2");
        assertNotNull("the sensor's Quad: kept, said", kept);
        assertTrue(kept, kept.contains("3"));
        assertNull("said once", NubiaRemosaicProbe.quad("2"));
        assertFalse(NubiaRemosaicProbe.exhausted("2"));
        NubiaRemosaicProbe.noSession();
        assertFalse(NubiaRemosaicProbe.running("2"));
        assertEquals("", NubiaRemosaicProbe.streamKey());
        assertTrue("the kept setting again", NubiaRemosaicProbe.sessionStart("2"));
        assertEquals("zte_qcfa_mode", NubiaRemosaicProbe.describe().substring(1, "zte_qcfa_mode".length() + 1));
    }

    @Test public void anExhaustedCameraDimsItsFiftyMegapixels() {
        ShadowBuild.setManufacturer("nubia");
        ShadowBuild.setBrand("nubia");
        ShadowBuild.setFingerprint("nubia/NX721J/test");
        NubiaFullRes.learnMaxRes("2", new PixelPro10.FullRes(android.graphics.ImageFormat.RAW_SENSOR, new android.util.Size(8192, 6144)),
                new android.util.Size(4096, 3072));
        NubiaRemosaicProbe.listedForTest("2", NX721J);
        assertTrue(NubiaFullRes.maxRes("2"));
        assertTrue("settings left: 50 Мп on", NubiaFullRes.offered("2"));
        assertFalse(NubiaFullRes.exhausted("2"));
        while (NubiaRemosaicProbe.sessionStart("2")) {
            final NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.failed("2", BAYER);
            assertNotNull(o);
        }
        assertFalse("every setting remosaicked: 50 Мп off", NubiaFullRes.offered("2"));
        assertTrue("and dimmed (a tap says why)", NubiaFullRes.exhausted("2"));
        final String status = NubiaFullRes.status(p, "2", NubiaFullRes.stamp());
        assertNotNull(status);
        assertTrue(status, status.startsWith(NubiaRemosaicProbe.unavailable()));
        FullResTrials.clear(p, NubiaFullRes.base("2"));
        assertTrue("«Попробовать снова»: on again", NubiaFullRes.offered("2"));
    }
}
