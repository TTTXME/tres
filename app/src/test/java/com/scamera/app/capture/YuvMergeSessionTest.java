package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.scamera.app.processing.yuv.YuvFrames;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * P667 (owner, build 31266, vivo X200 Pro front camera: «первое фото делается нормальным, а далее - или сразу в этот белый режим
 * уходит ... или надо переключить с фронталки на задний модуль и обратно и уже все белое»): every session on one camera takes one
 * route - the first open, a restart, a flip back - whatever module the settings still name; the route's stream is 8-bit unless
 * «YUV: 10 бит» is switched on; an HLG frame's range is its dataspace's (Camera2's HLG10 is full range), not a guess.
 */
public class YuvMergeSessionTest {
    /** The owner's X200 Pro modules: back0 = camera 2 (1x), back1 = camera 4 (0.6x), back2 = camera 3 (3.7x), front0 = camera 1. */
    private static final Map<String, String> CAMERA = new HashMap<>();
    static {
        CAMERA.put("back0", "2");
        CAMERA.put("back1", "4");
        CAMERA.put("back2", "3");
        CAMERA.put("front0", "1");
        CAMERA.put("front1", "1");
    }
    private static final List<String> SLOTS = Arrays.asList("back0", "back1", "back2", "front0", "front1");
    private static final Function<String, String> CAMERA_OF = s -> CAMERA.getOrDefault(s, s);
    private static final Set<String> VISIBLE = new HashSet<>(Arrays.asList("back0", "back1", "back2", "front0"));
    private static final Predicate<String> VISIBLE_OF = VISIBLE::contains;

    private static String slot(String camera, String active, List<String> slots) {
        return YuvMergeRoute.slotFor(camera, active, slots, CAMERA_OF, VISIBLE_OF);
    }

    @Test public void theModuleOfTheOpenedCameraDecides() {
        assertEquals("the active module is this camera", "front0", slot("1", "front0", SLOTS));
        assertEquals("a second module of this camera, active", "front1", slot("1", "front1", SLOTS));
        // build 31262 / 31266: camera "1" opened while the stored module was still a back one - the route was off for that session
        assertEquals("the stored module is another camera: this camera's visible module", "front0", slot("1", "back0", SLOTS));
        assertEquals("a bare camera id as the stored module", "front0", slot("1", "2", SLOTS));
        assertEquals("a back camera keeps its own module", "back0", slot("2", "front0", SLOTS));
        assertNull("no module of this camera yet (the first start, before the modules are made)", slot("1", "0", Collections.emptyList()));
        assertEquals("only a hidden module of this camera", "front1", slot("1", "back0", Arrays.asList("back0", "front1")));
        assertNull(slot(null, "front0", SLOTS));
    }

    @Test public void everySessionOfTheFrontCameraTakesTheSameRoute() {
        final Map<String, Object> none = new HashMap<>();
        // the first open (stored module still back0), the first start (no modules), a restart, the flip back: all on the route
        for (String active : new String[]{"back0", "front0", "back1", "0"})
            for (List<String> slots : Arrays.asList(SLOTS, Collections.<String>emptyList())) {
                final String s = slot("1", active, slots);
                assertTrue(active + " " + slots, YuvMergeRoute.onFor(none, "1", s, true));
                assertFalse("not on another phone", YuvMergeRoute.onFor(none, "1", s, false));
                assertFalse("not on a back camera", YuvMergeRoute.onFor(none, "2", slot("2", active, slots), true));
            }
        // the module's own switch, off: off in every session of that camera, whichever module is stored
        final Map<String, Object> off = new HashMap<>();
        off.put(YuvMergeRoute.key("front0"), Boolean.FALSE);
        for (String active : new String[]{"back0", "front0", "back2"})
            assertFalse(active, YuvMergeRoute.onFor(off, "1", slot("1", active, SLOTS), true));
        // and on for a RAW lens only when its module switched it on, in every session
        final Map<String, Object> on = new HashMap<>();
        on.put(YuvMergeRoute.key("back2"), Boolean.TRUE);
        for (String active : new String[]{"back0", "front0", "back2"})
            assertTrue(active, YuvMergeRoute.onFor(on, "3", slot("3", active, SLOTS), false));
        assertFalse(YuvMergeRoute.onFor(none, null, null, true));
    }

    @Test public void theRouteIsEightBitUnlessTenBitIsSwitchedOn() throws Exception {
        // the series after the shutter runs in the session's stream (it never reconfigures): one default for every session
        assertFalse(YuvMergeRoute.TEN_BIT_DEFAULT);
        final NodeList rows = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("src/main/res/xml/preferences.xml")).getElementsByTagName("SwitchPreferenceCompat");
        String xml = null;
        for (int i = 0; i < rows.getLength(); i++) {
            final Element e = (Element) rows.item(i);
            if ("pref_scam_hybrid_yuv_10bit".equals(e.getAttribute("android:key"))) xml = e.getAttribute("android:defaultValue");
        }
        assertEquals("the row's default is the code's", String.valueOf(YuvMergeRoute.TEN_BIT_DEFAULT), xml);
    }

    @Test public void anHlgFramesRangeIsItsDataspaces() {
        // android.hardware.DataSpace: STANDARD_BT2020 6 << 16, TRANSFER_HLG 8 << 22, RANGE_FULL 1 << 27, RANGE_LIMITED 2 << 27
        final int bt2020Hlg = 168165376, bt2020ItuHlg = 302383104, jfif = 146931712;
        assertEquals("DATASPACE_BT2020_HLG (Camera2's HLG10)", (6 << 16) | (8 << 22) | (1 << 27), bt2020Hlg);
        assertEquals("DATASPACE_BT2020_ITU_HLG", (6 << 16) | (8 << 22) | (2 << 27), bt2020ItuHlg);
        assertEquals(YuvFrames.TRANSFER_HLG, YuvFrames.transfer(bt2020Hlg));
        assertEquals(YuvFrames.RANGE_FULL, YuvFrames.range(bt2020Hlg));
        assertEquals(YuvFrames.RANGE_LIMITED, YuvFrames.range(bt2020ItuHlg));
        assertEquals(0, YuvFrames.range(0));
        assertTrue(YuvFrames.transfer(jfif) != YuvFrames.TRANSFER_HLG);
        assertEquals("full range declared: no guess", 2, YuvFrames.decodeMode(true, YuvFrames.range(bt2020Hlg)));
        assertEquals("narrow range declared", 3, YuvFrames.decodeMode(true, YuvFrames.range(bt2020ItuHlg)));
        assertEquals("no range declared: the guess from the Y extremes", 1, YuvFrames.decodeMode(true, 0));
        assertEquals("8-bit JFIF", 0, YuvFrames.decodeMode(false, YuvFrames.range(jfif)));
    }
}
