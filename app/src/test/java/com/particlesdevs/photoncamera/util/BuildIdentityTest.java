package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;

import com.particlesdevs.photoncamera.BuildConfig;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P214b (engineering.md A4): one line names the build - version, versionCode, package, commit (+dirty), CI or local - and
 * every session of the daily log starts with it (before, log-2026-10-17.txt had no version line at any of its 6 starts).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BuildIdentityTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void lineFormat() {
        assertEquals("SCAMERA 0.98 (30966) android.camera.cts cab4480a CI",
                BuildIdentity.line("0.98", 30966, "android.camera.cts", "cab4480a02f0dd0d69e3a1dd2bbd6f76e0b2fb1b", false, true));
        assertEquals("SCAMERA 0.98 (27075) org.codeaurora.snapcam cab4480a+dirty local",
                BuildIdentity.line("0.98", 27075, "org.codeaurora.snapcam", "cab4480a02f0dd0d69e3a1dd2bbd6f76e0b2fb1b", true, false));
        assertEquals("a build without git", "SCAMERA 0.98 (1) p unknown local", BuildIdentity.line("0.98", 1, "p", "", false, false));
        assertEquals("unknown", BuildIdentity.shortSha(null));
        assertEquals("unknown", BuildIdentity.shortSha("unknown"));
    }

    @Test public void thisBuildHasItsIdentity() {
        String line = BuildIdentity.line();
        assertTrue(line, line.matches("SCAMERA \\S+ \\(\\d+\\) [\\w.]+ ([0-9a-f]{8}|unknown)(\\+dirty)? (CI|local)"));
        assertTrue(line.startsWith("SCAMERA " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") "
                + BuildConfig.APPLICATION_ID + " "));
        assertTrue("the full commit stays in BuildConfig: " + BuildConfig.GIT_SHA,
                BuildConfig.GIT_SHA.equals("unknown") || BuildConfig.GIT_SHA.matches("[0-9a-f]{40,64}"));
        assertEquals(BuildConfig.CI_BUILD, line.endsWith(" CI"));
    }

    private static Field field(String name) throws Exception {
        Field f = Log.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * Every log session names its build right after its start line (P214b). P454 removed the permanent DCIM daily log this test
     * used to read; the session log (ScameraFileLog) writes the header now.
     */
    @Test public void everyLogSessionStartsWithTheBuildLine() {
        String[] lines = ScameraFileLog.sessionHeader(new Date(0)).split("\n");
        assertTrue(lines[1], lines[1].startsWith("=== SCAMERA log session "));
        assertEquals(BuildIdentity.line(), lines[2]);
        assertTrue(lines[3], lines[3].startsWith("Device: "));
    }
}
