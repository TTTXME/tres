package com.scamera.app.ui.camera.views.viewfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P631b (build 31257, «Чёрный экран»): the viewfinder's main program draws only while its samplers of different types sit on
 * different texture units. P630e's tone LUT (uOtLut, a 2D sampler) was given its unit only while the Oldstyle tone was live;
 * outside that mode it stayed on unit 0 beside the camera's external frame and every draw failed with GL_INVALID_OPERATION.
 * Every sampler of the program as MainRenderer builds it has a unit of its own, set once the program is there and with the tone
 * off too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MainRendererSamplerUnitsTest {
    private static String asset(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/assets/shaders/" + path)), StandardCharsets.UTF_8);
    }

    /** The fragment source of the main program, spliced as MainRenderer.onSurfaceCreated does. */
    private static String mainFragment() throws Exception {
        return MainRenderer.oldstyleSplice(LiveLensFilter.splice(asset("preview/main_fs.glsl"), asset("preview/lensfilter_live.glsl")),
                asset("utils/import_oldstyle_adjust.glsl"), asset("utils/import_oldstyle_tone_live.glsl"));
    }

    private static Map<String, String> types(String fragment) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (String[] u : MainRenderer.samplers(fragment)) out.put(u[1], u[0]);
        return out;
    }

    @Test public void theProgramsSamplersAreFound() throws Exception {
        final Map<String, String> t = types(mainFragment());
        assertEquals("samplerExternalOES", t.get("sTexture"));
        assertEquals("sampler3D", t.get("uLut"));
        assertEquals("sampler2D", t.get("uLfHiTex"));
        assertEquals("sampler2D", t.get("uLfLinTex"));
        assertEquals("the tone's LUT, with its precision qualifier", "sampler2D", t.get("uOtLut"));
        // a sampler parameter of a function is no uniform
        assertFalse(t.containsKey("t"));
        final String source = mainFragment();
        int declared = 0;
        for (String line : source.split("\n")) if (line.trim().startsWith("uniform") && line.contains("sampler")) declared++;
        assertEquals("every uniform sampler line of the source is found", declared, t.size());
    }

    @Test public void everySamplerHasAUnitOfItsOwnAndOnlyTheFrameIsOnUnit0() throws Exception {
        for (String fragment : new String[]{mainFragment(), LiveLensFilter.to2d(mainFragment())}) {
            final Set<Integer> units = new HashSet<>();
            for (String[] u : MainRenderer.samplers(fragment)) {
                final int unit = MainRenderer.samplerUnit(u[1]);
                assertTrue(u[1] + " has no unit in MainRenderer.samplerUnit: a sampler nobody sets shares the frame's unit 0", unit >= 0);
                assertTrue(u[1] + " unit " + unit, unit < 16); // GL ES 3: at least 16 fragment texture units
                assertEquals(u[1], MainRenderer.FRAME_SAMPLER.equals(u[1]), unit == 0);
                assertTrue(u[1] + " shares unit " + unit, units.add(unit));
            }
            assertTrue(units.contains(0));
        }
        assertEquals(13, MainRenderer.samplerUnit("uOtLut"));
        assertEquals(MainRenderer.OT_LUT_UNIT, MainRenderer.samplerUnit("uOtLut"));
        assertEquals(LiveLensFilter.UNIT_LUT, MainRenderer.samplerUnit("uLut"));
        assertEquals(-1, MainRenderer.samplerUnit("uSomethingNew"));
    }

    /** A recorder for pinSamplers: locations by name (10, 11, ...), the units set. */
    private static final class Recorder implements MainRenderer.SamplerSink {
        final Map<String, Integer> locations = new HashMap<>();
        final Map<Integer, Integer> set = new HashMap<>();
        final Set<String> missing = new HashSet<>();

        @Override public int location(String name) {
            if (missing.contains(name)) return -1;
            return locations.computeIfAbsent(name, n -> 10 + locations.size());
        }

        @Override public void unit(int location, int unit) {
            assertTrue("no uniform is set at location -1", location >= 0);
            set.put(location, unit);
        }

        int unitOf(String name) {
            final Integer u = set.get(locations.get(name));
            return u == null ? -1 : u;
        }
    }

    @Test public void theLinkedProgramGetsEveryUnitBeforeItsFirstDrawWithTheToneOff() throws Exception {
        com.scamera.app.processing.oldstyle.OldstyleLive.shared().off(); // outside the Oldstyle mode: the owner's case
        final Recorder gl = new Recorder();
        MainRenderer.pinSamplers(mainFragment(), gl);
        assertEquals("the tone's LUT off unit 0", 13, gl.unitOf("uOtLut"));
        assertEquals(0, gl.unitOf("sTexture"));
        assertEquals(1, gl.unitOf("uLut"));
        assertEquals(2, gl.unitOf("uLfHiTex"));
        assertEquals(3, gl.unitOf("uLfLinTex"));
        // no two samplers of different types on one unit: what GL ES fails the draw for
        final Map<String, String> types = types(mainFragment());
        final Map<Integer, String> typeOfUnit = new HashMap<>();
        for (Map.Entry<String, String> e : types.entrySet()) {
            final String before = typeOfUnit.put(gl.unitOf(e.getKey()), e.getValue());
            assertTrue(e.getKey() + " on a unit with a " + before, before == null || before.equals(e.getValue()));
        }
    }

    @Test public void aSamplerTheDriverRemovedIsSkipped() throws Exception {
        final Recorder gl = new Recorder();
        gl.missing.add("uOtLut");
        MainRenderer.pinSamplers(mainFragment(), gl);
        assertEquals(2, gl.unitOf("uLfHiTex"));
        assertFalse(gl.locations.containsKey("uOtLut"));
        assertEquals(4, gl.set.size());
    }

    @Test public void aSamplerSplicedInLaterGetsASpareUnitNotTheFrames() {
        final String fragment = "uniform samplerExternalOES sTexture;\nuniform highp sampler2D uNewA;\n"
                + "  uniform lowp sampler3D uNewB;\nvec3 f(sampler2D t) { return vec3(0.0); }\n// uniform sampler2D uComment;\n";
        final List<Object[]> pins = MainRenderer.samplerPins(fragment);
        assertEquals(3, pins.size());
        assertEquals("sTexture", pins.get(0)[0]);
        assertEquals(0, pins.get(0)[1]);
        assertEquals(MainRenderer.SPARE_UNIT, pins.get(1)[1]);
        assertEquals(MainRenderer.SPARE_UNIT + 1, pins.get(2)[1]);
        assertNotEquals(0, pins.get(1)[1]);
        final Recorder gl = new Recorder();
        MainRenderer.pinSamplers(fragment, gl);
        assertEquals(14, gl.unitOf("uNewA"));
        assertEquals(15, gl.unitOf("uNewB"));
    }

    @Test public void theRendererPinsBothProgramsAndTheTonesUnitWithTheToneOff() throws Exception {
        final String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("pinSamplers(ispProg, fragmentSrc);"));
        assertTrue(src.contains("pinSamplers(hProgram2d, LiveLensFilter.to2d(fragmentSrc));"));
        // each draw: the LUT's unit before the «tone off» return (31257 set it after)
        final int method = src.indexOf("private void oldstyleTone(int[] l, com.scamera.app.processing.oldstyle.OldstyleLive.Grid g)"); // P643d: the draw's grid or none
        final int unit = src.indexOf("GLES20.glUniform1i(l[1], OT_LUT_UNIT);", method), off = src.indexOf("if (g == null) return;", method);
        assertTrue(method > 0 && unit > method && off > unit);
        // the RAW viewfinder drops another pass's GL error instead of failing on it
        final String raw = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/ui/camera/views/viewfinder/LiveRawRenderer.java")), StandardCharsets.UTF_8);
        final int draw = raw.indexOf("dropForeignErrors(); // P631b: before this renderer's own calls");
        assertTrue(draw > 0 && draw < raw.indexOf("if(program==0 && !init())return false;"));
        final int bind = raw.indexOf("dropForeignErrors(); // P631b\n");
        assertTrue(bind > 0 && bind < raw.indexOf("if(!upload(f,fusedOn()))return false;", bind));
    }
}
