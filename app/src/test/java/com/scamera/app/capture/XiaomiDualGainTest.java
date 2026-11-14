package com.scamera.app.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * P641: the Xiaomi 17 Ultra's dual-gain rows (DCG, DCG + LOFIC, the ultra wide's DSG) - the rows of the vendor's map against the
 * cameras' tables, the ladder of ways of asking, what a firmware is remembered by and the proof on the frames' depth. The tables
 * and the static lists are the owner's device report's (SCAM-TEST 2026-10-11, OS3.0.312.0.WPACNXM); nothing here ran on a phone.
 */
public class XiaomiDualGainTest {
    /** Camera 2 (the main, OVX10500U): org.quic.camera2.sensormode.info.SensorModeTable as dumped (report line 191486). */
    static final int[] MAIN = {43, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30,
            4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 8192, 6144, 30, 4096, 2304, 30, 4096, 2304, 30,
            4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30,
            8192, 4608, 30, 4096, 3072, 60, 4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60,
            3200, 2400, 30, 7680, 4320, 30, 4096, 2304, 120, 1920, 1080, 480, 1280, 720, 480, 2048, 1536, 30, 2048, 1152, 30, 5888, 4416, 30,
            2048, 1536, 30, 3072, 2304, 30, 4096, 3072, 30, 4096, 2304, 60, 5888, 4416, 30};
    /** Camera 3 (the ultra wide, S5KJN5), report line 206256. */
    static final int[] WIDE = {19, 3, 8192, 6144, 12, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 2304, 30, 4096, 2304, 30,
            4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 60, 1920, 1080, 120, 1280, 720, 120, 1920, 1080, 240, 1280, 720, 240, 2048, 1536, 30,
            4096, 2304, 30, 2048, 1536, 30, 1440, 1080, 30, 3072, 2304, 30, 1440, 1080, 10};
    /** Camera 4 (the 75 mm, S5KHPE), report line 220917. */
    static final int[] TELE = {37, 3, 16320, 12288, 7, 8160, 6144, 24, 8160, 6144, 24, 5440, 4096, 24, 4080, 3072, 30, 4080, 3072, 30,
            4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 60, 4080, 3072, 30, 8160, 4608, 24,
            5440, 4096, 24, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30,
            4080, 2560, 30, 4080, 2560, 30, 4080, 2560, 30, 4080, 2560, 30, 4080, 2560, 30, 4080, 2560, 60, 4080, 2560, 60, 4080, 2560, 60,
            4080, 2560, 60, 4080, 2560, 60, 4080, 2304, 120, 7680, 4320, 30, 1920, 1080, 240, 1280, 720, 240, 4080, 3072, 30};
    /** supportedHDRmodes.HDRDCGModes of cameras 2, 3 and 4 (dumpsys). */
    static final int[] MODES_MAIN = {3588, 658949}, MODES_WIDE = {3076}, MODES_TELE = {3588};

    @Test public void theSeventeenUltraOnly() {
        assertTrue(XiaomiDualGain.phone("Xiaomi", "Xiaomi", "nezha", "25128PNA1C"));
        assertTrue(XiaomiDualGain.phone("Xiaomi", "Xiaomi", "nezha", "2512BPNDAG"));
        assertFalse("the 15 Ultra keeps its own route (P230)", XiaomiDualGain.phone("Xiaomi", "Xiaomi", "xuanyuan", "25010PN30G"));
        assertFalse(XiaomiDualGain.phone("OnePlus", "OnePlus", "nezha", "PYB110"));
        assertFalse(XiaomiDualGain.phone(null, null, null, null));
    }

    @Test public void theCamerasAndTheirKinds() {
        assertEquals(XiaomiDualGain.Role.MAIN, XiaomiDualGain.role("2"));
        assertEquals(XiaomiDualGain.Role.ULTRA_WIDE, XiaomiDualGain.role("3"));
        assertEquals(XiaomiDualGain.Role.TELE, XiaomiDualGain.role("4"));
        assertNull("the logical cameras: the HAL's own choice", XiaomiDualGain.role("0"));
        assertNull("the front", XiaomiDualGain.role("1"));
        assertArrayEquals(new XiaomiDualGain.Kind[]{XiaomiDualGain.Kind.DCG, XiaomiDualGain.Kind.LOFIC}, XiaomiDualGain.kinds(XiaomiDualGain.Role.MAIN));
        assertArrayEquals("the ultra wide has DSG, not DCG", new XiaomiDualGain.Kind[]{XiaomiDualGain.Kind.DSG},
                XiaomiDualGain.kinds(XiaomiDualGain.Role.ULTRA_WIDE));
        assertArrayEquals(new XiaomiDualGain.Kind[]{XiaomiDualGain.Kind.DCG}, XiaomiDualGain.kinds(XiaomiDualGain.Role.TELE));
        assertEquals(0, XiaomiDualGain.kinds(null).length);
        assertEquals("DSG", XiaomiDualGain.Kind.DSG.label);
        assertEquals("DCG+LOFIC", XiaomiDualGain.Kind.LOFIC.label);
    }

    /** The vendor's rows (aispsensormodesettings.json) are 4:3 rows of the binned size at 30 fps in the report's tables. */
    @Test public void theVendorsRowsInTheTables() {
        assertEquals(4, XiaomiDualGain.row(XiaomiDualGain.Role.MAIN, XiaomiDualGain.Kind.DCG, MAIN, -1));
        assertEquals(5, XiaomiDualGain.row(XiaomiDualGain.Role.MAIN, XiaomiDualGain.Kind.LOFIC, MAIN, -1));
        assertEquals("the 75 mm's 4080x3072", 6, XiaomiDualGain.row(XiaomiDualGain.Role.TELE, XiaomiDualGain.Kind.DCG, TELE, -1));
        assertEquals("the only 4:3 row the map leaves unnamed", 3, XiaomiDualGain.row(XiaomiDualGain.Role.ULTRA_WIDE, XiaomiDualGain.Kind.DSG, WIDE, -1));
        assertEquals("no LOFIC on the 75 mm", -1, XiaomiDualGain.row(XiaomiDualGain.Role.TELE, XiaomiDualGain.Kind.LOFIC, TELE, -1));
        assertEquals("no DCG on the ultra wide", -1, XiaomiDualGain.row(XiaomiDualGain.Role.ULTRA_WIDE, XiaomiDualGain.Kind.DCG, WIDE, -1));
        assertEquals("no table: nothing offered", -1, XiaomiDualGain.row(XiaomiDualGain.Role.MAIN, XiaomiDualGain.Kind.DCG, null, -1));
        // a firmware whose rows moved: the main's table on the 75 mm's role has a 16:9-free row 6, a 4:3 one - but the ultra
        // wide's row 3 of the 75 mm's table is 5440x4096 at 24 fps
        assertEquals(-1, XiaomiDualGain.row(XiaomiDualGain.Role.ULTRA_WIDE, XiaomiDualGain.Kind.DSG, TELE, -1));
        assertEquals("scam_dev row: any row of the table", 14, XiaomiDualGain.row(XiaomiDualGain.Role.ULTRA_WIDE, XiaomiDualGain.Kind.DSG, WIDE, 14));
        assertEquals("not a row the table has", -1, XiaomiDualGain.row(XiaomiDualGain.Role.ULTRA_WIDE, XiaomiDualGain.Kind.DSG, WIDE, 40));
    }

    /** HDRDCGModes: type | long bits << 8 | short bits << 16 - the main {4 / 14, 5 / 14 + 10}, the ultra wide {4 / 12}. */
    @Test public void theStaticListsModes() {
        assertEquals(4, XiaomiDualGain.modeType(XiaomiDualGain.Kind.DCG, MODES_MAIN));
        assertEquals("DCGPlusVS: the second read", 5, XiaomiDualGain.modeType(XiaomiDualGain.Kind.LOFIC, MODES_MAIN));
        assertEquals(14, XiaomiDualGain.declaredBits(XiaomiDualGain.Kind.DCG, MODES_MAIN));
        assertEquals(14, XiaomiDualGain.declaredBits(XiaomiDualGain.Kind.LOFIC, MODES_MAIN));
        assertEquals(4, XiaomiDualGain.modeType(XiaomiDualGain.Kind.DSG, MODES_WIDE));
        assertEquals("the ultra wide's dual gain is a 12-bit one", 12, XiaomiDualGain.declaredBits(XiaomiDualGain.Kind.DSG, MODES_WIDE));
        assertEquals(12, XiaomiDualGain.Kind.DSG.bits);
        assertEquals(14, XiaomiDualGain.declaredBits(XiaomiDualGain.Kind.DCG, MODES_TELE));
        assertEquals("no second-read mode on the 75 mm", 0, XiaomiDualGain.modeType(XiaomiDualGain.Kind.LOFIC, MODES_TELE));
        assertEquals("unread: the report's types", 4, XiaomiDualGain.modeType(XiaomiDualGain.Kind.DCG, null));
        assertEquals(5, XiaomiDualGain.modeType(XiaomiDualGain.Kind.LOFIC, null));
        assertEquals("{3588 = type 4 / 14 bit, 658949 = type 5 / 14 + 10 bit}", XiaomiDualGain.describeModes(MODES_MAIN));
    }

    private static String ids(List<XiaomiDualGain.Variant> ladder) {
        final StringBuilder sb = new StringBuilder();
        for (XiaomiDualGain.Variant v : ladder) sb.append(sb.length() == 0 ? "" : " ").append(v.id);
        return sb.toString();
    }

    @Test public void theLadders() {
        final List<XiaomiDualGain.Variant> dcg = XiaomiDualGain.ladder(XiaomiDualGain.Kind.DCG, 4, 4, true);
        assertEquals("hdr hdr+row type+row row night+row", ids(dcg));
        assertEquals("the listed key alone first: the HAL picks its row", "hdr {EnableHDRDCGMode 1}", dcg.get(0).toString());
        assertFalse(dcg.get(0).sendsRow());
        assertEquals("hdr+row {EnableHDRDCGMode 1, current_mode 4}", dcg.get(1).toString());
        assertEquals("type+row {EnableHDRDCGMode 4, current_mode 4}", dcg.get(2).toString());
        assertTrue(dcg.get(3).sendsRow());
        assertEquals("the 15 Ultra's session mode last", "night+row {current_mode 4} session mode 0x9203", dcg.get(4).toString());
        for (int i = 0; i < 4; i++) assertEquals(0, dcg.get(i).opMode);

        assertEquals("no session mode off the main camera", "hdr hdr+row type+row row", ids(XiaomiDualGain.ladder(XiaomiDualGain.Kind.DCG, 6, 4, false)));
        final List<XiaomiDualGain.Variant> dsg = XiaomiDualGain.ladder(XiaomiDualGain.Kind.DSG, 3, 4, false);
        assertEquals("hdr hdr+row type+row row", ids(dsg));
        assertEquals("row {current_mode 3}", dsg.get(3).toString());

        final List<XiaomiDualGain.Variant> lofic = XiaomiDualGain.ladder(XiaomiDualGain.Kind.LOFIC, 5, 5, true);
        // P641d / P700: the single-frame LOFIC rungs first, then the pair (LoficPairTest), then the row alone and the session mode
        assertEquals("lofic+hdr lofic+hdr+row lofic+type+row lofic-pair row night+row", ids(lofic));
        assertEquals("lofic+hdr {enableLofic 1, EnableHDRDCGMode 1}", lofic.get(0).toString());
        assertEquals("lofic+type+row {enableLofic 1, EnableHDRDCGMode 5, current_mode 5}", lofic.get(2).toString());
        assertEquals("night+row {enableLofic 1, current_mode 5} session mode 0x9203", lofic.get(5).toString());
        assertEquals("a list without the type: no variant of a made-up value", "hdr hdr+row row",
                ids(XiaomiDualGain.ladder(XiaomiDualGain.Kind.DCG, 4, 0, false)));
    }

    @Test public void whatAFirmwareIsRememberedBy() {
        final XiaomiDualGain.Memory m = new XiaomiDualGain.Memory();
        m.fingerprint = "Xiaomi/nezha/nezha:16/BP2A/OS3.0.312.0.WPACNXM:user/release-keys";
        m.failed.put("hdr", "10-bit frames in 90 results, scene ISO 100, exposure 10.00 ms");
        m.failed.put("hdr+row", "session configuration failed");
        m.proven = "row";
        final String stored = m.encode();
        final XiaomiDualGain.Memory back = XiaomiDualGain.Memory.decode(stored, m.fingerprint);
        assertEquals("row", back.proven);
        assertEquals(Arrays.asList("hdr", "hdr+row"), Arrays.asList(back.failed.keySet().toArray()));
        assertEquals("session configuration failed", back.failed.get("hdr+row"));
        // another firmware: its variants are tried anew
        final XiaomiDualGain.Memory other = XiaomiDualGain.Memory.decode(stored, "Xiaomi/nezha/nezha:16/BP2A/OS3.0.400.0.WPACNXM:user/release-keys");
        assertNull(other.proven);
        assertTrue(other.failed.isEmpty());
        assertTrue(XiaomiDualGain.Memory.decode(null, "f").failed.isEmpty());
        assertTrue(XiaomiDualGain.Memory.decode("garbage", "f").failed.isEmpty());
        assertNull(XiaomiDualGain.Memory.decode("f|P:|F:", "f").proven);
    }

    @Test public void theNextVariant() {
        final List<XiaomiDualGain.Variant> ladder = XiaomiDualGain.ladder(XiaomiDualGain.Kind.DCG, 4, 4, true);
        final XiaomiDualGain.Memory m = new XiaomiDualGain.Memory();
        assertEquals("hdr", XiaomiDualGain.next(ladder, m, -1).id);
        m.failed.put("hdr", "x");
        assertEquals("a failed variant is never asked again", "hdr+row", XiaomiDualGain.next(ladder, m, -1).id);
        m.proven = "row";
        assertEquals("the proven one at once", "row", XiaomiDualGain.next(ladder, m, -1).id);
        m.failed.put("row", "the session died");
        assertEquals("a proven variant that failed since: the ladder again", "hdr+row", XiaomiDualGain.next(ladder, m, -1).id);
        for (XiaomiDualGain.Variant v : ladder) m.failed.put(v.id, "x");
        assertNull("nothing left: the kind steps down", XiaomiDualGain.next(ladder, m, -1));
        assertEquals("scam_dev: one variant, whatever is remembered", "type+row", XiaomiDualGain.next(ladder, m, 2).id);
        assertNull(XiaomiDualGain.next(ladder, m, 9));
        assertNull(XiaomiDualGain.next(Collections.<XiaomiDualGain.Variant>emptyList(), new XiaomiDualGain.Memory(), -1));
    }

    /**
     * P700: the log of 2026-11-14 11:18:25 - the rung «lofic-pair» lost every request in a plain session. The ladder starts with the
     * single-frame LOFIC rung now; a firmware that remembers the pair as failed (PreviewStall.refused) never asks it again, and the
     * screen names the rung that failed.
     */
    @Test public void thePairRungIsNotFirstAndARefusedOneIsNotAskedAgain() {
        final List<XiaomiDualGain.Variant> lofic = XiaomiDualGain.ladder(XiaomiDualGain.Kind.LOFIC, 5, 5, true);
        final XiaomiDualGain.Memory m = new XiaomiDualGain.Memory();
        final XiaomiDualGain.Variant first = XiaomiDualGain.next(lofic, m, -1);
        assertEquals("lofic+hdr", first.id);
        assertFalse("no overflow stream on the first rung", first.pair);
        // the firmware of the log: the pair refused, the single-frame rungs failed by depth
        m.failed.put("lofic-pair", "refused every request: 8 failed in a row, no result since the session started");
        m.failed.put("lofic+hdr", "10-bit frames in 90 results");
        m.failed.put("lofic+hdr+row", "10-bit frames in 90 results");
        m.failed.put("lofic+type+row", "10-bit frames in 90 results");
        assertEquals("the pair is skipped", "row", XiaomiDualGain.next(lofic, m, -1).id);
        assertEquals("DCG+LOFIC 4/6", XiaomiDualGain.label(XiaomiDualGain.Kind.LOFIC, lofic.indexOf(lofic.get(3)), lofic.size()));
        assertEquals("lofic-pair", lofic.get(3).id);
        assertNull("no session, no label", XiaomiDualGain.sessionLabel());
    }

    /**
     * P700 (owner: «Для работы LOFIC нужна поддержка двух потоков одновременно»): «DCG+LOFIC» is the two-RAW recipe (XiaomiLofic);
     * under it this ladder runs DCG, never its single-frame LOFIC rungs; scam_dev xiaomi17_lofic 0 keeps them.
     */
    @Test public void theLoficRungsStayOutOfTheRecipesPath() {
        assertEquals(XiaomiDualGain.Kind.DCG, XiaomiDualGain.ladderKind(XiaomiDualGain.Kind.LOFIC, true));
        assertEquals(XiaomiDualGain.Kind.LOFIC, XiaomiDualGain.ladderKind(XiaomiDualGain.Kind.LOFIC, false));
        assertEquals(XiaomiDualGain.Kind.DSG, XiaomiDualGain.ladderKind(XiaomiDualGain.Kind.DSG, true));
        assertEquals(XiaomiDualGain.Kind.DCG, XiaomiDualGain.ladderKind(XiaomiDualGain.Kind.DCG, true));
        assertNull(XiaomiDualGain.ladderKind(null, true));
        for (XiaomiDualGain.Variant v : XiaomiDualGain.ladder(XiaomiDualGain.ladderKind(XiaomiDualGain.Kind.LOFIC, true), 4, 4, true))
            assertFalse("no overflow stream on the DCG ladder", v.pair);
    }

    /** The report's DCG session: dynamic black 1024 / 1025 under the declared 64; a plain session 64.06. */
    @Test public void theDepthOfTheDynamicBlack() {
        assertEquals(14, XiaomiDualGain.bitsOfBlack(64f, 1024f));
        assertEquals(14, XiaomiDualGain.bitsOfBlack(64f, 1025f));
        assertEquals("a 12-bit stream's pedestal", 12, XiaomiDualGain.bitsOfBlack(64f, 256f));
        assertEquals(10, XiaomiDualGain.bitsOfBlack(64f, 64.0625f));
        assertEquals(10, XiaomiDualGain.bitsOfBlack(64f, 63.875f));
        assertEquals("no dynamic black", 0, XiaomiDualGain.bitsOfBlack(64f, -1f));
        assertEquals("560 over 64 is no depth's pedestal (the recipe viewfinder's raised floor)", 0, XiaomiDualGain.bitsOfBlack(64f, 560f));
    }

    @Test public void theDepthOfAFrame() {
        assertEquals("packed RAW14, darkest samples at 16 x the black", 14, XiaomiDualGain.bitsOfFrame(14, 1040, 64f, 0));
        assertEquals("packed RAW14 holding 10-bit values (P72)", 10, XiaomiDualGain.bitsOfFrame(14, 70, 64f, 0));
        assertEquals("packed RAW12, darkest samples at 4 x the black", 12, XiaomiDualGain.bitsOfFrame(12, 260, 64f, 0));
        assertEquals("a bright frame of a 12-bit packing", 12, XiaomiDualGain.bitsOfFrame(12, 856, 64f, 0));
        assertEquals("a 14-bit packing under 8 x the black: the declared scale", 10, XiaomiDualGain.bitsOfFrame(14, 300, 64f, 0));
        assertEquals(10, XiaomiDualGain.bitsOfFrame(12, 66, 64f, 0));
        assertEquals("plain samples that proved 14 bits", 14, XiaomiDualGain.bitsOfFrame(0, 0, 64f, 14));
        assertEquals("plain samples within the declared white", 10, XiaomiDualGain.bitsOfFrame(0, 0, 64f, 0));
        assertEquals("the plain RAW10 packing is no wider frame", 10, XiaomiDualGain.bitsOfFrame(10, 0, 64f, 0));
    }

    @Test public void aVariantIsProvenByDepthOnly() {
        final XiaomiDualGain.Proof p = new XiaomiDualGain.Proof(90);
        for (int i = 0; i < 14; i++) {
            p.results++;
            p.note(0, 0, "dynamic black 64");
        }
        assertEquals(XiaomiDualGain.Verdict.PENDING, p.verdict());
        p.results++;
        p.note(14, 0, "dynamic black 1024 over the declared 64"); // the report: the HAL switched at request 15
        assertEquals(XiaomiDualGain.Verdict.PROVEN, p.verdict());
        assertEquals(14, p.bestBits);
        assertEquals("dynamic black 1024 over the declared 64", p.how);

        final XiaomiDualGain.Proof frame = new XiaomiDualGain.Proof(90);
        frame.frames++;
        frame.note(0, 12, "a packed RAW12 payload");
        assertEquals("a frame's payload alone proves", XiaomiDualGain.Verdict.PROVEN, frame.verdict());

        final XiaomiDualGain.Proof none = new XiaomiDualGain.Proof(90);
        none.results = 90;
        assertEquals("no checked frame yet: no verdict", XiaomiDualGain.Verdict.PENDING, none.verdict());
        none.frames = 2;
        none.note(0, 10, "plain samples");
        assertEquals(XiaomiDualGain.Verdict.FAILED, none.verdict());
        none.results = 89;
        assertEquals("the window is not over", XiaomiDualGain.Verdict.PENDING, none.verdict());
        final XiaomiDualGain.Proof blind = new XiaomiDualGain.Proof(90);
        blind.results = 269;
        assertEquals(XiaomiDualGain.Verdict.PENDING, blind.verdict());
        blind.results = 270;
        assertEquals("three windows without a checked frame: no proof either", XiaomiDualGain.Verdict.FAILED, blind.verdict());
        assertEquals("never under ten results", 10, new XiaomiDualGain.Proof(0).window);
    }

    @Test public void theKnownDepthFollowsTheEvidence() {
        assertEquals("nothing yet", -1, XiaomiDualGain.knownBits(0, 0));
        assertEquals(14, XiaomiDualGain.knownBits(0, 14));
        assertEquals("a packed 14-bit frame under results that report the declared black", 14, XiaomiDualGain.knownBits(14, 10));
        assertEquals(12, XiaomiDualGain.knownBits(12, 0));
        assertEquals("both at the declared depth: taken back", 0, XiaomiDualGain.knownBits(10, 10));
        assertEquals(0, XiaomiDualGain.knownBits(0, 10));
    }

    @Test public void theSwitchsStates() {
        final List<XiaomiDualGain.Kind> main = Arrays.asList(XiaomiDualGain.Kind.DCG, XiaomiDualGain.Kind.LOFIC);
        final List<XiaomiDualGain.Kind> wide = Collections.singletonList(XiaomiDualGain.Kind.DSG);
        assertNull(XiaomiDualGain.kindOf(Dcg14Mode.OFF, main));
        assertEquals(XiaomiDualGain.Kind.DCG, XiaomiDualGain.kindOf(Dcg14Mode.DCG, main));
        assertEquals(XiaomiDualGain.Kind.LOFIC, XiaomiDualGain.kindOf(Dcg14Mode.LOFIC, main));
        assertEquals("the switch on the ultra wide is DSG", XiaomiDualGain.Kind.DSG, XiaomiDualGain.kindOf(Dcg14Mode.DCG, wide));
        assertEquals("LOFIC chosen, a camera without it: its own mode", XiaomiDualGain.Kind.DSG, XiaomiDualGain.kindOf(Dcg14Mode.LOFIC, wide));
        assertNull(XiaomiDualGain.kindOf(Dcg14Mode.DCG, Collections.<XiaomiDualGain.Kind>emptyList()));
        // the stored switch: LOFIC only where the phone has the choice (any other phone: the old two states)
        assertEquals(Dcg14Mode.LOFIC, Dcg14Mode.stored(2, true));
        assertEquals("P107's stored 2 reads as DCG elsewhere", Dcg14Mode.DCG, Dcg14Mode.stored(2, false));
        assertEquals(Dcg14Mode.OFF, Dcg14Mode.stored(0, true));
        assertEquals(Dcg14Mode.DCG, Dcg14Mode.stored(1, true));
        assertEquals("the other phones' button: off - on - off", Dcg14Mode.OFF, Dcg14Mode.next(Dcg14Mode.DCG));
        assertEquals(Dcg14Mode.DCG, Dcg14Mode.next(Dcg14Mode.OFF));
        assertEquals(Dcg14Mode.LOFIC, Dcg14Mode.next(Dcg14Mode.DCG, true));
        assertEquals(Dcg14Mode.OFF, Dcg14Mode.next(Dcg14Mode.LOFIC, true));
        assertEquals("DCG+LOFIC", Dcg14Mode.name(Dcg14Mode.LOFIC));
        assertEquals("DCG", Dcg14Mode.name(Dcg14Mode.DCG));
    }

    /** No session of this route: nothing is applied, no depth is claimed, the frames are no dual-gain ones. */
    @Test public void withoutASessionNothingApplies() {
        XiaomiDualGain.sessionEnd();
        assertFalse(XiaomiDualGain.sessionActive());
        assertEquals("", XiaomiDualGain.apply(null));
        assertEquals(0, XiaomiDualGain.operationMode());
        assertEquals(-1, XiaomiDualGain.askedRow());
        assertFalse(XiaomiDualGain.frames(14));
        assertFalse(XiaomiDualGain.sessionFailed("no preview frame"));
        assertEquals(XiaomiDualGain.Verdict.PENDING, XiaomiDualGain.onResult(null, null));
    }
}
