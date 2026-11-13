package com.scamera.app.gallery.adapters;

import static org.junit.Assert.assertEquals;

import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The viewer's page transform: hidden off screen, the leaving page opaque, the coming page fading in as it nears. */
@RunWith(AndroidJUnit4.class)
public class DepthPageTransformerTest {
    private final DepthPageTransformer transform = new DepthPageTransformer();
    private View page;

    @Before
    public void makePage() {
        page = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    private float alphaAt(float position) {
        transform.transformPage(page, position);
        return page.getAlpha();
    }

    @Test
    public void pagesBeyondTheNeighboursAreHidden() {
        assertEquals(0f, alphaAt(-2f), 0f);
        assertEquals(0f, alphaAt(2f), 0f);
    }

    @Test
    public void theLeavingPageStaysOpaque() {
        assertEquals(1f, alphaAt(-1f), 0f);
        assertEquals(1f, alphaAt(0f), 0f);
    }

    @Test
    public void theComingPageFadesInAsItNears() {
        assertEquals(0.9f, alphaAt(0.1f), 1e-6f);
        assertEquals(0f, alphaAt(1f), 1e-6f);
    }
}
