package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.pm.ApplicationInfo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * P662: the worker ships once, as lib/arm64-v8a/libscamera_worker.so (no copy in assets/scam-neural). The su launcher runs the
 * installed file in nativeLibraryDir, else unpacks that lib entry of the APK (never an asset), and names the path it took.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WorkerSuBinaryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private ApplicationInfo info;
    private File nativeDir;
    private final List<String> log = new ArrayList<>();
    private static final byte[] WORKER = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0, 42, 43, 44};

    @Before public void setUp() throws IOException {
        info = RuntimeEnvironment.getApplication().getApplicationInfo();
        nativeDir = tmp.newFolder("lib");
        info.nativeLibraryDir = nativeDir.getAbsolutePath();
    }

    private File apk(String entry) throws IOException {
        final File apk = tmp.newFile("base-" + System.nanoTime() + ".apk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(apk))) {
            z.putNextEntry(new ZipEntry("classes.dex"));
            z.write(new byte[]{1, 2, 3});
            if (entry != null) {
                z.putNextEntry(new ZipEntry(entry));
                z.write(WORKER);
            }
        }
        info.sourceDir = apk.getAbsolutePath();
        return apk;
    }

    @Test public void theOneEntryIsTheNativeLibrary() {
        assertEquals("lib/arm64-v8a/libscamera_worker.so", ScamNeuralClient.LIB_WORKER_ENTRY);
    }

    @Test public void runsTheInstalledWorkerFromNativeLibraryDir() throws IOException {
        apk(null); // the APK is not even read
        final File installed = new File(nativeDir, "libscamera_worker.so");
        Files.write(installed.toPath(), WORKER);
        assertTrue(installed.setExecutable(true));
        final File got = ScamNeuralClient.suWorker(RuntimeEnvironment.getApplication(), log::add);
        assertEquals(installed, got);
        assertEquals(1, log.size());
        assertTrue(log.get(0), log.get(0).startsWith("WORKER BINARY: ") && log.get(0).contains("(nativeLibraryDir, su)"));
    }

    @Test public void withoutAnInstalledCopyUnpacksTheApksLibEntry() throws IOException {
        apk("lib/arm64-v8a/libscamera_worker.so");
        final File got = ScamNeuralClient.suWorker(RuntimeEnvironment.getApplication(), log::add);
        assertEquals("scam-neural-worker", got.getName());
        assertArrayEquals(WORKER, Files.readAllBytes(got.toPath()));
        assertTrue(got.canExecute());
        assertTrue(log.get(0), log.get(0).contains("unpacked from the APK's lib/arm64-v8a/libscamera_worker.so")
                && log.get(0).contains("no installed copy"));
    }

    @Test public void anInstalledCopyThatIsNotExecutableFallsBackToTheLibEntry() throws IOException {
        apk("lib/arm64-v8a/libscamera_worker.so");
        final File installed = new File(nativeDir, "libscamera_worker.so");
        Files.write(installed.toPath(), WORKER);
        assertTrue(installed.setExecutable(false, false));
        final File got = ScamNeuralClient.suWorker(RuntimeEnvironment.getApplication(), log::add);
        assertTrue(!got.equals(installed));
        assertTrue(log.get(0), log.get(0).contains("installed copy not executable"));
    }

    @Test public void theOldAssetCopyIsNotUsed() throws IOException {
        apk("assets/scam-neural/arm64-v8a/scam-neural-worker");
        try {
            ScamNeuralClient.suWorker(RuntimeEnvironment.getApplication(), log::add);
            fail("the asset copy was taken");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("lib/arm64-v8a/libscamera_worker.so"));
        }
    }
}
