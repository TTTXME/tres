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
            "org.codeaurora.qcamera3.sessionParameters.EnableSnapshotOnlyInsensorZoom",
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
        NubiaRemosaicProbe.probeForTest(true); // P605: the probe is off by default (scam_dev nubia_probe 1)
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
        NubiaRemosaicProbe.probeForTest(true); // P605: the probe is off by default (scam_dev nubia_probe 1)
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

    // ---------------------------------------------------------------- P605: the remosaic route

    @Test public void theRouteTakesAConfidentBayerAtTheFullSizeAndAShotProvesIt() {
        NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, "2", STAMP);
        assertNotNull(c);
        assertTrue(c.route);
        // P608: the route's first way (was «remosaic», the plain session); P626: without the camera's session keys the first is
        // the per-shot plain way (no vendor key at all), then P608's mode key alone
        assertEquals(NubiaRemosaicProbe.MAXRES_RAW_ONLY, c.candidate.id);
        assertTrue(c.candidate.perShot);
        assertTrue("no vendor key: the plain maximum-resolution session", c.candidate.keys.isEmpty());
        assertEquals(1, c.start);
        assertEquals("run:1", NubiaRemosaicProbe.routeState(p, "2", STAMP));
        assertNull("a confident Bayer at 8192x6144: taken",
                NubiaRemosaicProbe.routeFrames(p, STAMP, c, 1, 8192, 6144, 8192, 6144));
        assertEquals(NubiaRemosaicProbe.FRAMES, NubiaRemosaicProbe.routeState(p, "2", STAMP));
        c = NubiaRemosaicProbe.routeChoose(p, "2", STAMP);
        assertTrue("verified: no more counting", c.known);
        assertEquals(0, c.start);
        assertTrue(NubiaRemosaicProbe.routeShot(p, STAMP, c));
        assertEquals(NubiaRemosaicProbe.SHOT, NubiaRemosaicProbe.routeState(p, "2", STAMP));
        assertFalse("said once", NubiaRemosaicProbe.routeShot(p, STAMP, c));
        assertNull("a later Bayer verdict keeps «shot»", NubiaRemosaicProbe.routeFrames(p, STAMP, c, 1, 8192, 6144, 8192, 6144));
        assertEquals(NubiaRemosaicProbe.SHOT, NubiaRemosaicProbe.routeState(p, "2", STAMP));
        assertNull(NubiaRemosaicProbe.routeStatus(p, "2", STAMP));
        assertFalse(NubiaRemosaicProbe.routeFailed(NubiaRemosaicProbe.routeState(p, "2", STAMP)));
        // the tele's own size
        final NubiaRemosaicProbe.Choice tele = NubiaRemosaicProbe.routeChoose(p, "3", STAMP);
        assertNull(NubiaRemosaicProbe.routeFrames(p, STAMP, tele, 1, 9248, 6944, 9248, 6944));
    }

    @Test public void theWrongSizeOrBlockFailsTheRouteOnThisFirmwareWithOneNotice() {
        final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, "2", STAMP);
        final String size = NubiaRemosaicProbe.routeFrames(p, STAMP, c, 1, 4096, 3072, 8192, 6144);
        assertNotNull("binned frames are no 50 MP", size);
        assertTrue(size, size.contains("wrong size"));
        final String tetra = NubiaRemosaicProbe.routeFrames(p, STAMP, c, 4, 8192, 6144, 8192, 6144);
        assertNotNull(tetra);
        assertEquals(FullResTrials.MOSAIC, NubiaRemosaicProbe.code(tetra));
        assertEquals(NubiaRemosaicProbe.WRONG_SIZE, NubiaRemosaicProbe.code(size));
        // P626: two ways without session keys (the per-shot plain one, P608's mode key alone): the first gives way to the next
        final NubiaRemosaicProbe.Outcome first = NubiaRemosaicProbe.routeFail(p, STAMP, c, "camera error 4");
        assertTrue("the next way", first.next);
        assertTrue(first.message, first.message.contains(NubiaRemosaicProbe.wayName(NubiaRemosaicProbe.MAXRES_RAW_ONLY)));
        assertNull("a way left: not off", NubiaRemosaicProbe.routeStatus(p, "2", STAMP));
        final NubiaRemosaicProbe.Choice second = NubiaRemosaicProbe.routeChoose(p, "2", STAMP);
        assertEquals("fullmode", second.candidate.id);
        final NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.routeFail(p, STAMP, second, "camera error 4");
        assertFalse("off: no other setting", o.next);
        assertEquals(NubiaRemosaicProbe.routeUnavailable(FullResTrials.DIED), o.message);
        assertTrue(NubiaRemosaicProbe.routeFailed(NubiaRemosaicProbe.routeState(p, "2", STAMP)));
        assertNull("off on this firmware: the normal session", NubiaRemosaicProbe.routeChoose(p, "2", STAMP));
        final String status = NubiaRemosaicProbe.routeStatus(p, "2", STAMP);
        assertTrue(status, status.startsWith(o.message + ".") && status.contains("2 ("));
        assertNotNull("a firmware update tries it again", NubiaRemosaicProbe.routeChoose(p, "2", "r1 nubia/NX721J/2"));
    }

    @Test public void theRouteWithoutAVerdictGetsThreeSessions() {
        // P626: each of the two ways without session keys gets its three sessions
        for (String way : new String[]{NubiaRemosaicProbe.MAXRES_RAW_ONLY, "fullmode"})
            for (int start = 1; start <= NubiaRemosaicProbe.STARTS; start++) {
                final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, "2", STAMP);
                assertEquals(way, c.candidate.id);
                assertEquals(start, c.start);
            }
        assertNull(NubiaRemosaicProbe.routeChoose(p, "2", STAMP));
        assertEquals("fail:" + NubiaRemosaicProbe.NO_VERDICT, NubiaRemosaicProbe.routeState(p, "2", STAMP));
    }

    @Test public void aQuadOnTheRouteIsTheSensorsOwnMosaic() {
        final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, "2", STAMP);
        assertNull(NubiaRemosaicProbe.routeFrames(p, STAMP, c, 2, 8192, 6144, 8192, 6144));
        assertEquals(NubiaRemosaicProbe.QUAD, NubiaRemosaicProbe.routeState(p, "2", STAMP));
        assertFalse("a shot keeps «quad»", NubiaRemosaicProbe.routeShot(p, STAMP, c));
    }

    @Test public void onNubiaTheFiftyMegapixelsAreTheRemosaicOnceTheyWork() {
        ShadowBuild.setManufacturer("nubia");
        ShadowBuild.setBrand("nubia");
        ShadowBuild.setFingerprint("nubia/NX721J/test");
        NubiaRemosaicProbe.probeForTest(false);
        NubiaFullRes.learnMaxRes("2", new PixelPro10.FullRes(android.graphics.ImageFormat.RAW_SENSOR, new android.util.Size(8192, 6144)),
                new android.util.Size(4096, 3072));
        NubiaRemosaicProbe.listedForTest("2", NX721J);
        // the owner's phone: every probe setting failed on build 31251 - the route does not care
        NubiaRemosaicProbe.Choice probe;
        while ((probe = NubiaRemosaicProbe.choose(p, "2", NX721J, NubiaFullRes.stamp(), 0)) != null)
            NubiaRemosaicProbe.fail(p, NX721J, NubiaFullRes.stamp(), probe, BAYER);
        assertTrue(NubiaRemosaicProbe.exhausted(p, "2", NX721J, NubiaFullRes.stamp()));
        assertTrue("50 Мп on again (the remosaic)", NubiaFullRes.offered("2"));
        assertFalse(NubiaFullRes.exhausted("2"));
        assertTrue("«50 Мп (ремозаик)»", NubiaRemosaicProbe.remosaicRoute("2"));
        assertFalse("not a maximum-resolution camera", NubiaRemosaicProbe.remosaicRoute("1"));

        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertTrue(NubiaRemosaicProbe.routeRunning("2"));
        assertEquals("merged as a plain Bayer", 1, NubiaRemosaicProbe.fullResBlock("2"));
        assertEquals("another camera's session: the Quad routes as before", 2, NubiaRemosaicProbe.fullResBlock("0"));
        // P626: the first way is the stock camera's QCFA snapshot, per shot: its feature bit on the session, its operation mode
        assertTrue(NubiaRemosaicProbe.perShot("2"));
        assertTrue("the ZTE feature bit on the session", NubiaRemosaicProbe.wantsSession("2"));
        assertEquals(NubiaRemosaicProbe.STOCK_QCFA_MODE, NubiaRemosaicProbe.operationMode("2"));
        assertEquals("P608: the way's own stream (the old «remosaic» block came from binned frames)", "|probe-qcfa_snapshot", NubiaRemosaicProbe.streamKey());
        assertNull("the HAL's remosaic at 8192x6144: taken", NubiaRemosaicProbe.frames("2", 1, 8192, 6144));
        assertNull("a completed shot proves it", NubiaRemosaicProbe.shot("2", true, null));
        assertEquals(NubiaRemosaicProbe.SHOT, NubiaRemosaicProbe.routeState(p, "2", NubiaFullRes.stamp()));
        assertNull("a session closed under a shot is no failure", NubiaRemosaicProbe.shot("2", false, "the session closed"));
        assertTrue(NubiaRemosaicProbe.routeRunning("2"));
        final String died = NubiaRemosaicProbe.shot("2", false, "camera error 4");
        assertEquals(NubiaRemosaicProbe.routeUnavailable(FullResTrials.DIED), died);
        assertFalse(NubiaRemosaicProbe.routeRunning("2"));
        assertFalse("off on this firmware", NubiaFullRes.offered("2"));
        assertTrue("dimmed, a tap says why", NubiaFullRes.exhausted("2"));
        // P626: every way left takes the shot's verdict (off at once), each listed in the explanation
        final String diedStatus = NubiaFullRes.status(p, "2", NubiaFullRes.stamp());
        assertTrue(diedStatus, diedStatus.startsWith(died + ".") && diedStatus.contains("5 ("));
        assertFalse(NubiaRemosaicProbe.sessionStart("2"));
        FullResTrials.clear(p, NubiaFullRes.base("2"));
        assertTrue("«Попробовать снова»", NubiaFullRes.offered("2"));

        // a refused session / wrong frames through failed(): P608 the next way (the camera lists both in-sensor zoom keys), then
        // the next, then one notice
        String wrong = null;
        NubiaRemosaicProbe.Outcome o = null;
        for (String id : new String[]{"qcfa_snapshot", "maxres_raw_only", "fullmode", "fullmode_isz", "fullmode_isz_snap"}) {
            assertTrue(id, NubiaRemosaicProbe.sessionStart("2"));
            assertEquals("|probe-" + id, NubiaRemosaicProbe.streamKey());
            if ("fullmode".equals(id)) { // no table row learned here: the plain maximum-resolution session, no parameters
                assertFalse("no session parameters", NubiaRemosaicProbe.wantsSession("2"));
                assertEquals(0, NubiaRemosaicProbe.operationMode("2"));
                assertFalse(NubiaRemosaicProbe.perShot("2"));
            }
            if ("maxres_raw_only".equals(id)) {
                assertTrue(NubiaRemosaicProbe.perShot("2"));
                assertFalse("no vendor key, no row key per shot", NubiaRemosaicProbe.wantsSession("2"));
                assertEquals(0, NubiaRemosaicProbe.operationMode("2"));
            }
            wrong = NubiaRemosaicProbe.frames("2", 1, 4096, 3072);
            assertNotNull(wrong);
            o = NubiaRemosaicProbe.failed("2", wrong);
            assertNotNull(o);
            assertEquals(id, !"fullmode_isz_snap".equals(id), o.next);
        }
        assertEquals(NubiaRemosaicProbe.routeUnavailable(NubiaRemosaicProbe.WRONG_SIZE), o.message);
        assertFalse(NubiaFullRes.offered("2"));

        // the probe again by the dev key (scam_dev nubia_probe 1): its own memory decides («Попробовать снова» forgot it)
        NubiaRemosaicProbe.probeForTest(true);
        assertFalse(NubiaRemosaicProbe.remosaicRoute("2"));
        assertTrue(NubiaFullRes.offered("2"));
        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertFalse("a probe setting, not the route", NubiaRemosaicProbe.routeRunning("2"));
        assertEquals("plain", NubiaRemosaicProbe.describe().substring(1, 6));
    }

    // ---------------------------------------------------------------- P608: the full mode asked and proven

    private static void nubia() {
        ShadowBuild.setManufacturer("nubia");
        ShadowBuild.setBrand("nubia");
        ShadowBuild.setFingerprint("nubia/NX721J/test");
        NubiaRemosaicProbe.probeForTest(false);
    }

    private static void learn(String camera, int w, int h) {
        NubiaFullRes.learnMaxRes(camera, new PixelPro10.FullRes(android.graphics.ImageFormat.RAW_SENSOR, new android.util.Size(w, h)),
                new android.util.Size(w / 2, h / 2), camera.equals("3") ? NubiaFullResTest.TELE_STOCK : NubiaFullResTest.WIDE_STOCK);
        NubiaRemosaicProbe.listedForTest(camera, NX721J);
    }

    @Test public void theRouteTriesItsWaysToTheFullModeInTurnThenOneNotice() {
        // P626: the per-shot ways first, then P608's
        assertEquals("qcfa_snapshot maxres_raw_only fullmode fullmode_isz fullmode_isz_snap", ids(NubiaRemosaicProbe.attempts(NX721J)));
        assertEquals("not learned: the ways without session keys", "maxres_raw_only fullmode", ids(NubiaRemosaicProbe.attempts(null)));
        final NubiaRemosaicProbe.Candidate isz = NubiaRemosaicProbe.attempts(NX721J).get(3);
        assertEquals(NubiaRemosaicProbe.INSENSOR_ZOOM, isz.keys.get(0).name);
        assertTrue(isz.keys.get(0).session);
        assertEquals(1, isz.keys.get(0).value);
        final String binned = "nubia: the results report sensor mode 1, not the full row 0 in 15 results: the HAL stays in its binned sensor mode "
                + NubiaFullRes.BINNED_TAG;
        for (int way = 1; way <= 5; way++) {
            final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, "2", NX721J, STAMP);
            assertNotNull(c);
            assertEquals(way, c.index);
            assertEquals(5, c.total);
            assertEquals(1, c.start);
            final NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.routeFail(p, NX721J, STAMP, c, binned);
            assertEquals(way < 5, o.next);
            assertTrue(o.message, o.message.contains(NubiaRemosaicProbe.phrase(NubiaRemosaicProbe.BINNED)));
            if (o.next) assertTrue("P626: the notice names the way", o.message.contains(NubiaRemosaicProbe.wayName(c.candidate.id)));
        }
        assertNull("every way failed: off on this firmware", NubiaRemosaicProbe.routeChoose(p, "2", NX721J, STAMP));
        assertEquals("fail:" + NubiaRemosaicProbe.BINNED, NubiaRemosaicProbe.routeState(p, "2", STAMP));
        final String status = NubiaRemosaicProbe.routeStatus(p, "2", STAMP);
        assertNotNull(status);
        assertTrue(status, status.contains("5 (") && status.contains(NubiaRemosaicProbe.wayName("fullmode_isz_snap"))
                && status.contains(NubiaRemosaicProbe.wayName(NubiaRemosaicProbe.QCFA_SNAPSHOT)));
    }

    @Test public void aWayWithoutAVerdictGivesWayToTheNext() {
        for (int start = 1; start <= NubiaRemosaicProbe.STARTS; start++)
            assertEquals(NubiaRemosaicProbe.QCFA_SNAPSHOT, NubiaRemosaicProbe.routeChoose(p, "2", NX721J, STAMP).candidate.id);
        final NubiaRemosaicProbe.Choice next = NubiaRemosaicProbe.routeChoose(p, "2", NX721J, STAMP);
        assertEquals(NubiaRemosaicProbe.MAXRES_RAW_ONLY, next.candidate.id);
        assertEquals(1, next.start);
        assertEquals(NubiaRemosaicProbe.NO_VERDICT, NubiaRemosaicProbe.failsOf(p, "2", NubiaRemosaicProbe.ATTEMPT_FAIL, STAMP)
                .get(NubiaRemosaicProbe.QCFA_SNAPSHOT));
    }

    @Test public void aShotOfUnprovenFramesProvesNothing() {
        final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, "2", STAMP);
        assertFalse("run:1 - no frame verdict yet", NubiaRemosaicProbe.routeShot(p, STAMP, c));
        assertEquals("run:1", NubiaRemosaicProbe.routeState(p, "2", STAMP));
        assertNull(NubiaRemosaicProbe.routeFrames(p, STAMP, c, 1, 8192, 6144, 8192, 6144));
        assertTrue("proven frames, then a shot", NubiaRemosaicProbe.routeShot(p, STAMP, c));
    }

    @Test public void theBinnedPhraseNamesTheCamerasSizes() {
        nubia();
        learn("2", 8192, 6144);
        learn("3", 9248, 6944);
        final String wide = NubiaRemosaicProbe.phrase(NubiaRemosaicProbe.BINNED, "2"), tele = NubiaRemosaicProbe.phrase(NubiaRemosaicProbe.BINNED, "3");
        final String quarter = NubiaRemosaicProbe.megapixels(8192 * 6144 / 4e6);
        assertEquals(com.particlesdevs.photoncamera.util.Lang.ru() ? "12,6" : "12.6", quarter);
        assertTrue(wide, wide.contains(quarter) && wide.contains("50"));
        assertTrue(tele, tele.contains(NubiaRemosaicProbe.megapixels(9248 * 6944 / 4e6)) && tele.contains("64"));
        assertEquals("64", NubiaRemosaicProbe.megapixels(64.2));
        assertEquals("16", NubiaRemosaicProbe.megapixels(16.0));
        assertTrue("the tele's notice names its 64 MP", NubiaRemosaicProbe.routeUnavailable(NubiaRemosaicProbe.BINNED, "3").startsWith("64 "));
        assertNotNull(NubiaRemosaicProbe.phrase(NubiaRemosaicProbe.CROP));
        assertTrue("the notice of an unlearned camera keeps «50»", NubiaRemosaicProbe.routeUnavailable(NubiaRemosaicProbe.BINNED, "7").startsWith("50 "));
    }

    @Test public void theSessionAsksForTheFullRowAndItsVerdictWaitsForTheMode() {
        nubia();
        learn("2", 8192, 6144);
        failPerShotWays("2"); // P626: P608's way «fullmode» runs after the per-shot ones
        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertEquals("fullmode", NubiaRemosaicProbe.describe().substring(1, 9));
        assertTrue("the row's key on the session parameters too", NubiaRemosaicProbe.wantsSession("2"));
        assertTrue(NubiaRemosaicProbe.describe(), NubiaRemosaicProbe.describe().contains(SensorModePick.QCOM_KEY + "=0"));
        NubiaFullRes.watchMaxRes("2");
        assertFalse("nothing reported: the verdict counts", NubiaRemosaicProbe.defer("2", 1, 8192, 6144));
        NubiaFullRes.observeForTest(1);
        assertTrue("the results report the binned row: the verdict waits", NubiaRemosaicProbe.defer("2", 1, 8192, 6144));
        assertNull("the mode not proven: nothing to take", NubiaRemosaicProbe.takePending("2"));
        assertEquals("run:1", NubiaRemosaicProbe.routeState(p, "2", NubiaFullRes.stamp()));
        NubiaFullRes.observeForTest(0);
        final int[] pending = NubiaRemosaicProbe.takePending("2");
        assertNotNull("the full row reported: the kept verdict goes on", pending);
        assertEquals(1, pending[0]);
        assertEquals(8192, pending[1]);
        assertNull("taken once", NubiaRemosaicProbe.takePending("2"));
        assertFalse("proven: no more waiting", NubiaRemosaicProbe.defer("2", 1, 8192, 6144));
        assertNull(NubiaRemosaicProbe.frames("2", pending[0], pending[1], pending[2]));
        assertEquals(NubiaRemosaicProbe.FRAMES, NubiaRemosaicProbe.routeState(p, "2", NubiaFullRes.stamp()));
        NubiaRemosaicProbe.noSession();
        assertFalse(NubiaRemosaicProbe.wantsSession("2"));
    }

    /** P626: the two per-shot ways failed on this firmware (P608's ways run next). */
    private void failPerShotWays(String camera) {
        for (String way : new String[]{NubiaRemosaicProbe.QCFA_SNAPSHOT, NubiaRemosaicProbe.MAXRES_RAW_ONLY}) {
            final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, camera, NX721J, NubiaFullRes.stamp());
            assertEquals(way, c.candidate.id);
            assertTrue(NubiaRemosaicProbe.routeFail(p, NX721J, NubiaFullRes.stamp(), c, "nubia: binned " + NubiaFullRes.BINNED_TAG).next);
        }
    }

    @Test public void theVerdictsOfTheBinnedSessionsAreForgotten() {
        nubia();
        learn("2", 8192, 6144);
        // the owner's phone before P608: «frames» of the route and the probe's eight «hwremosaic», all from binned frames
        final String old = FullResTrials.stamp(1);
        p.edit().putString(NubiaFullRes.base("2") + FullResTrials.STAMP, old)
                .putString(NubiaFullRes.base("2") + NubiaRemosaicProbe.ROUTE, NubiaRemosaicProbe.FRAMES)
                .putString(NubiaFullRes.base("2") + NubiaRemosaicProbe.FAIL, "plain:hwremosaic,zte_qcfa:hwremosaic").commit();
        assertEquals(NubiaRemosaicProbe.FRAMES, NubiaRemosaicProbe.routeState(p, "2", old));
        assertNull("revision 2 passes them over", NubiaRemosaicProbe.routeState(p, "2", NubiaFullRes.stamp()));
        assertTrue(NubiaRemosaicProbe.fails(p, "2", NubiaFullRes.stamp()).isEmpty());
        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertEquals("tried once again, unverified", "run:1", NubiaRemosaicProbe.routeState(p, "2", NubiaFullRes.stamp()));
        assertFalse("the old keys went with the new trial", p.contains(NubiaFullRes.base("2") + NubiaRemosaicProbe.FAIL));
    }

    // ---------------------------------------------------------------- P626: the per-shot ways

    @Test public void theFailedWaysOfTheOwnersPhoneGiveWayToThePerShotOnes() {
        nubia();
        learn("2", 8192, 6144);
        final String stamp = NubiaFullRes.stamp();
        // the owner's camera 2 on 31256: P608's three ways failed «binned», the route stored «fail:binned»
        p.edit().putString(NubiaFullRes.base("2") + FullResTrials.STAMP, stamp)
                .putString(NubiaFullRes.base("2") + NubiaRemosaicProbe.ATTEMPT_FAIL, "fullmode:binned,fullmode_isz:binned,fullmode_isz_snap:binned")
                .putString(NubiaFullRes.base("2") + NubiaRemosaicProbe.ROUTE, "fail:binned").commit();
        assertFalse("ways left: not off", NubiaRemosaicProbe.exhausted("2"));
        assertTrue("50 Мп offered again", NubiaFullRes.offered("2"));
        assertNull("not dimmed: no explanation", NubiaRemosaicProbe.routeStatus(p, "2", stamp));
        final NubiaRemosaicProbe.Choice c = NubiaRemosaicProbe.routeChoose(p, "2", NX721J, stamp);
        assertNotNull(c);
        assertEquals(NubiaRemosaicProbe.QCFA_SNAPSHOT, c.candidate.id);
        assertEquals("1 of the five", 1, c.index);
        assertEquals(5, c.total);
        assertEquals("run:1", NubiaRemosaicProbe.routeState(p, "2", stamp));
        assertEquals("the old verdicts stay", "binned", NubiaRemosaicProbe.failsOf(p, "2", NubiaRemosaicProbe.ATTEMPT_FAIL, stamp).get("fullmode"));
        NubiaRemosaicProbe.Outcome o = NubiaRemosaicProbe.routeFail(p, NX721J, stamp, c, "nubia: binned " + NubiaFullRes.BINNED_TAG);
        assertTrue(o.next);
        final NubiaRemosaicProbe.Choice d = NubiaRemosaicProbe.routeChoose(p, "2", NX721J, stamp);
        assertEquals(NubiaRemosaicProbe.MAXRES_RAW_ONLY, d.candidate.id);
        o = NubiaRemosaicProbe.routeFail(p, NX721J, stamp, d, NubiaRemosaicProbe.noProofWhy(NubiaMaxResPlan.PROOF_TIMEOUT_MS));
        assertFalse("all five have a verdict: off", o.next);
        assertEquals(NubiaRemosaicProbe.routeUnavailable(NubiaRemosaicProbe.NO_PROOF, "2"), o.message);
        assertNull(NubiaRemosaicProbe.routeChoose(p, "2", NX721J, stamp));
        assertTrue(NubiaRemosaicProbe.exhausted("2"));
        FullResTrials.clear(p, NubiaFullRes.base("2"));
        assertEquals("«Попробовать снова»: the first way again", NubiaRemosaicProbe.QCFA_SNAPSHOT,
                NubiaRemosaicProbe.routeChoose(p, "2", NX721J, stamp).candidate.id);
    }

    @Test public void theQcfaSnapshotIsTheStockCamerasModelPerShot() {
        final NubiaRemosaicProbe.Candidate q = NubiaRemosaicProbe.attempts(NX721J).get(0);
        assertEquals(NubiaRemosaicProbe.QCFA_SNAPSHOT, q.id);
        assertTrue(q.perShot);
        assertEquals(0x8012, q.operationMode);
        assertEquals(3, q.keys.size());
        NubiaRemosaicProbe.Key feature = null, appMode = null, remosaic = null;
        for (NubiaRemosaicProbe.Key k : q.keys) {
            if (k.name.equals(NubiaRemosaicProbe.ZTE_FEATURE)) feature = k;
            if (k.name.equals(NubiaRemosaicProbe.NUBIA_APP_MODE)) appMode = k;
            if (k.name.equals(NubiaRemosaicProbe.ZTE_CUSTOM_REMOSAIC)) remosaic = k;
        }
        assertTrue("the feature bit is a listed session key", feature.session && !feature.still);
        assertEquals(0x20, feature.value);
        assertTrue("app_mode on every request", !appMode.session && !appMode.still);
        assertTrue("CustomRemosiac on the snapshot only", remosaic.still && !remosaic.session);
        assertEquals((byte) 1, remosaic.value);
        assertTrue(q.describe(), q.describe().startsWith("per shot") && q.describe().contains("0x8012")
                && q.describe().contains("(stills)"));
        final NubiaRemosaicProbe.Candidate plain = NubiaRemosaicProbe.attempts(NX721J).get(1);
        assertTrue(plain.perShot && plain.keys.isEmpty() && plain.operationMode == 0);
        for (NubiaRemosaicProbe.Candidate old : NubiaRemosaicProbe.attempts(NX721J).subList(2, 5)) assertFalse(old.id, old.perShot);
    }

    @Test public void aPerShotWayAsksNoRowAndProvesItselfOnce() {
        nubia();
        learn("2", 8192, 6144);
        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertTrue(NubiaRemosaicProbe.perShot("2"));
        assertFalse("another camera", NubiaRemosaicProbe.perShot("3"));
        assertFalse("no row key per shot (the stock camera sends none)", NubiaRemosaicProbe.describe().contains(SensorModePick.QCOM_KEY));
        assertFalse("nothing sent yet", NubiaRemosaicProbe.proofPending("2"));
        assertTrue(NubiaRemosaicProbe.proofWanted("2"));
        assertFalse("once a session", NubiaRemosaicProbe.proofWanted("2"));
        assertTrue("in flight until a verdict", NubiaRemosaicProbe.proofPending("2"));
        assertTrue(NubiaRemosaicProbe.fillOnce("2"));
        assertFalse("described once", NubiaRemosaicProbe.fillOnce("2"));
        // the proof's results: the mode watch counts the MAXIMUM_RESOLUTION ones only
        NubiaFullRes.watchMaxRes("2", true);
        assertNull(NubiaFullRes.observeForTest(0));
        assertNull("proven by a full-size Bayer", NubiaRemosaicProbe.frames("2", 1, 8192, 6144));
        assertFalse("proven: no longer pending", NubiaRemosaicProbe.proofPending("2"));
        NubiaRemosaicProbe.noSession();
        assertFalse(NubiaRemosaicProbe.perShot("2"));
        assertTrue(NubiaRemosaicProbe.sessionStart("2"));
        assertFalse("a proven way needs no test frames", NubiaRemosaicProbe.proofWanted("2"));
        assertEquals(NubiaRemosaicProbe.NO_PROOF, NubiaRemosaicProbe.code(NubiaRemosaicProbe.noProofWhy(4000)));
        assertNotNull(NubiaRemosaicProbe.phrase(NubiaRemosaicProbe.NO_PROOF));
        for (NubiaRemosaicProbe.Candidate c : NubiaRemosaicProbe.ATTEMPTS) assertNotNull(c.id, NubiaRemosaicProbe.wayName(c.id));
        assertNull(NubiaRemosaicProbe.wayName("plain"));
    }

    @Test public void theStillKeysRideOnTheStillsOnly() {
        final NubiaRemosaicProbe.Candidate q = NubiaRemosaicProbe.attempts(NX721J).get(0);
        assertEquals(Arrays.asList(NubiaRemosaicProbe.ZTE_FEATURE, NubiaRemosaicProbe.NUBIA_APP_MODE),
                names(NubiaRemosaicProbe.requestKeys(q, false)));
        assertEquals(Arrays.asList(NubiaRemosaicProbe.ZTE_FEATURE, NubiaRemosaicProbe.NUBIA_APP_MODE, NubiaRemosaicProbe.ZTE_CUSTOM_REMOSAIC),
                names(NubiaRemosaicProbe.requestKeys(q, true)));
        final NubiaRemosaicProbe.Candidate old = NubiaRemosaicProbe.byId("zte_qcfa");
        assertEquals("the probe's settings: every key on every request as before", 2, NubiaRemosaicProbe.requestKeys(old, false).size());
    }

    private static List<String> names(List<NubiaRemosaicProbe.Key> keys) {
        final java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (NubiaRemosaicProbe.Key k : keys) out.add(k.name);
        return out;
    }
}
