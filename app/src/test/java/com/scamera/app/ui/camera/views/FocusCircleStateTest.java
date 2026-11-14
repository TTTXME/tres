package com.scamera.app.ui.camera.views;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P705: the focus ring is drawn again only when its colour can change. */
public class FocusCircleStateTest {

    @Test
    public void onlyAMovedStateRedraws() {
        assertFalse(FocusCircleView.colourChanges(false, false, false, false));
        assertFalse(FocusCircleView.colourChanges(true, false, true, false));
        assertFalse(FocusCircleView.colourChanges(false, true, false, true));
        assertTrue(FocusCircleView.colourChanges(false, false, true, false));
        assertTrue(FocusCircleView.colourChanges(true, false, false, false));
        assertTrue(FocusCircleView.colourChanges(false, false, false, true));
        assertTrue(FocusCircleView.colourChanges(false, true, true, false));
    }
}
