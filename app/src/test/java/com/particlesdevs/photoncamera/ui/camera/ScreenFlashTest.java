package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P507 (owner, 2026-11-05): the front lens's torch is the screen - a white layer over the viewfinder and the window at full brightness,
 * the system brightness back with the torch off, a back lens or the fragment paused; the torch stays available on a front lens.
 */
public class ScreenFlashTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void theFrontTorchLightsTheFaceWithTheScreen() throws IOException {
        final String frag = read("src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraFragment.java");
        assertTrue(frag.contains("final boolean on = frontLens && screenFlashShown && PreferenceKeys.getAeMode() == 0;"));
        assertTrue(frag.contains("on ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL"));
        assertTrue(frag.contains(": android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;"));
        assertTrue(frag.contains("ui().showFlashButton(flashAvailable != null && flashAvailable || frontLens);"));
        // off while paused, back on resume
        assertTrue(frag.contains("screenFlashShown = false; // P507"));
        assertTrue(frag.contains("screenFlashShown = true; // P507"));
        // the torch from the top bar or the shade
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraUIViewImpl.java").contains("cameraFragment.updateScreenFlash();"));
        // no torch request reaches a lens without a flash
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java")
                .contains("if (mFlashed || (mFlashSupported && PreferenceKeys.getAeMode() == 0)) captureBuilder.set(FLASH_MODE, FLASH_MODE_TORCH);"));
    }
}
