package com.scamera.app.api;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P702: the photo mode is the only camera mode. Every number an older build stored (its enum's ordinal: 0 unlimited, 1 RAW
 * video, 2 the photo mode, 3 an older photo mode, 4 night, 5 video) reads as PHOTO, and the number stored for it is the one
 * every older build read as its photo mode.
 */
public class CameraModeTest {
    @Test public void everyStoredNumberIsThePhotoMode() {
        for (int stored : new int[]{0, 1, 2, 3, 4, 5, -1, 6, Integer.MAX_VALUE})
            assertEquals("stored " + stored, CameraMode.PHOTO, CameraMode.fromStored(stored));
        assertEquals(1, CameraMode.values().length);
    }

    @Test public void thePhotoModesNumberIsTheOneOlderBuildsReadAsTheirPhotoMode() throws Exception {
        assertEquals(2, CameraMode.STORED);
        File prefs = new File("src/main/res/values/default_prefs.xml");
        if (!prefs.isFile()) prefs = new File("app/src/main/res/values/default_prefs.xml");
        Matcher m = Pattern.compile("name=\"pref_camera_mode_default\"[^>]*>(\\d+)<")
                .matcher(new String(Files.readAllBytes(prefs.toPath()), StandardCharsets.UTF_8));
        assertTrue(m.find());
        assertEquals(CameraMode.STORED, Integer.parseInt(m.group(1)));
    }

    @Test public void aFreshSettingsCacheIsInThePhotoMode() {
        assertEquals(CameraMode.PHOTO, new Settings().mode);
    }
}
