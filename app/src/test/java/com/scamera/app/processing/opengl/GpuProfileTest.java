package com.scamera.app.processing.opengl;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P683: the first-run GPU calibration's profile - kept under the first-run stamp (another install or system build: none), its
 * text damaged or not, and its use: the starting band sizes of the post (GpuPace, the post's own measurements win) and the RAW
 * viewfinder's starting tone size (RawPreviewLoad.seed, the windows correct it).
 */
public class GpuProfileTest {
    private static final class MapStore implements GpuProfile.Store {
        final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void put(Map<String, String> v) {
            values.putAll(v);
        }
    }

    @After
    public void forget() {
        GpuPace.forgetBands();
        GpuProfile.setCurrent(null);
    }

    @Test
    public void keptUnderItsStampOnly() {
        final MapStore store = new MapStore();
        final Map<String, Float> m = new LinkedHashMap<>();
        m.put(GpuProfile.VIEWFINDER_TONE, 6.5f);
        m.put("why/combine", 41.25f);
        m.put("why/lpyr", 3.125f);
        GpuProfile.save(store, "123|fp", m);
        assertEquals(m, GpuProfile.load(store, "123|fp"));
        assertTrue("an update or a new system build measures again", GpuProfile.load(store, "124|fp").isEmpty());
        assertTrue(GpuProfile.load(new MapStore(), "123|fp").isEmpty());
        assertTrue(GpuProfile.load(store, null).isEmpty());
    }

    @Test
    public void aDamagedTextKeepsOnlyItsGoodEntries() {
        final Map<String, Float> d = GpuProfile.decode("a=1.5;b=x;=3;c;d=-2;e=Infinity;f=NaN;g=0.25");
        assertEquals(2, d.size());
        assertEquals(1.5f, d.get("a"), 0f);
        assertEquals(0.25f, d.get("g"), 0f);
        assertTrue(GpuProfile.decode(null).isEmpty());
        final Map<String, Float> bad = new LinkedHashMap<>();
        bad.put("x=y", 1f);
        bad.put("nan", Float.NaN);
        bad.put("zero", 0f);
        bad.put("ok", 2f);
        assertEquals("ok=2.0000", GpuProfile.encode(bad));
    }

    @Test
    public void thePostStartsFromTheCalibrationAndKeepsItsOwnMeasurements() {
        GpuPace.forgetBands();
        final Map<String, Float> m = new LinkedHashMap<>();
        m.put("why/combine", 100f);           // 100 ns a pixel: a 4096-wide band of ~10 ms is 24 rows -> 16
        m.put("why/lpyr", 0.5f);              // fast: 256 rows stay
        m.put(GpuProfile.VIEWFINDER_TONE, 9f); // the viewfinder's: not the post's
        assertEquals(2, GpuPace.seed(m));
        assertNull(GpuPace.measuredNsPerPixel(GpuProfile.VIEWFINDER_TONE));
        final GpuPace.Pacer p = new GpuPace.Pacer(new GpuPaceTest.FakeGpu(0L), 2, 10f);
        assertEquals("the first band already sized", 16, p.bandRows("why/combine", 4096, 256));
        assertEquals(256, p.bandRows("why/lpyr", 4096, 256));
        // a program the post measured keeps its own value
        m.put("why/combine", 1f);
        assertEquals(0, GpuPace.seed(m));
        assertEquals(100f, GpuPace.measuredNsPerPixel("why/combine"), 0f);
        assertEquals(0, GpuPace.seed(null));
    }

    @Test
    public void theFirstRunDialogCalibratesAfterThePrograms() throws Exception {
        File f = new File("src/main/java/com/scamera/app/ui/camera/FirstRunCompile.java");
        if (!f.isFile()) f = new File("app/" + f.getPath());
        final String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        final int warm = src.indexOf("GLProg.warmUp(() -> true, (d, n) -> show(activity, bar, phases - 2, d, n, phases), true);");
        final int cal = src.indexOf("GpuProfile.calibrate(app, stamp, () -> true);");
        assertTrue(warm > 0 && cal > warm);
        assertTrue(src.contains("\"GPU calibration \""));
        assertTrue("the profile is kept under the dialog's own stamp", src.contains("public static String stamp(Context c) {"));
    }
}
