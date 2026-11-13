package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneLumaCacheTest {
    @Test public void preservesHeadroomAndBoundsOptionalPlane() {
        assertTrue(SceneLumaCache.fits(4096, 3072, 512L << 20));
        assertTrue(SceneLumaCache.fits(8192, 6144, 1024L << 20));
        assertFalse(SceneLumaCache.fits(8192, 6144, 512L << 20));
        assertFalse(SceneLumaCache.fits(16384, 12288, 8L << 30));
        assertFalse(SceneLumaCache.fits(0, 3072, Long.MAX_VALUE));
        assertFalse(SceneLumaCache.fits(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(SceneLumaCache.fits(4096, 3072, -1));
        assertFalse(SceneLumaCache.fits(4096, 3072, 0));
    }
}
