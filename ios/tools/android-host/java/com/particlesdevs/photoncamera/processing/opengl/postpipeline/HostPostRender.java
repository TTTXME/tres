package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.PreferenceValue;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * The reference render of a lab shot for the iOS port: what ScamLabRun.side does with the shot's own settings («тогда»), on the
 * host. The merge is tools/hybrid_replay (the worker's C++ on Mesa, no CRE), its job made by the app's own code (the tuning
 * file and the markers ScamNeuralClient writes for a ScamLabBurst, without the lab's stage dump); its output is read as
 * ScamNeuralClient reads the worker's; then PostPipeline.Run on the host GPU. Inputs (environment): SCAM_HOST_SHOT = a lab
 * shot folder (meta.json, burst.nch), SCAM_HOST_REPLAY = the hybrid_replay binary, SCAM_HOST_RESULT = the output folder.
 * Output: tuning.txt, report.txt, merge.f32 (SCRG float RGB), photo.rgba (SCR8 header + RGBA8 rows); with SCAM_HOST_DUMP each
 * node's output (ShadowScamDiagnostics).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = {scam.host.ShadowGLES20.class, scam.host.ShadowGLES30.class,
        scam.host.ShadowGLES31.class, scam.host.ShadowGLES32.class, scam.host.ShadowEGL14.class, scam.host.ShadowScamDiagnostics.class},
        instrumentedPackages = "com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamDiagnostics")
public class HostPostRender {
    private MockedStatic<ScameraApp> camera;
    private SettingsManager manager;

    @Before public void setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        final Context context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        final com.particlesdevs.photoncamera.util.AssetLoader assets = new com.particlesdevs.photoncamera.util.AssetLoader(context);
        camera.when(ScameraApp::getAssetLoader).thenReturn(assets);
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        final com.particlesdevs.photoncamera.api.Settings settings = new com.particlesdevs.photoncamera.api.Settings();
        camera.when(ScameraApp::getSettings).thenReturn(settings);
    }

    @After public void tearDown() {
        PreferenceValue.overlay(null, null);
        PreferenceKeys.labDevOverlay(null);
        if (camera != null) camera.close();
    }

    private static String env(String name) {
        final String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalStateException(name + " is not set");
        return v;
    }

    @Test public void render() throws Exception {
        final File shotDir = new File(env("SCAM_HOST_SHOT")), out = new File(env("SCAM_HOST_RESULT"));
        final File replay = new File(env("SCAM_HOST_REPLAY"));
        out.mkdirs();
        final JSONObject meta = new JSONObject(new String(Files.readAllBytes(new File(shotDir, ScamLab.META).toPath()), StandardCharsets.UTF_8));
        final ScamLabBurst burst = new ScamLabBurst(ScamLab.burstOf(shotDir), null, meta.optString("tuningBurst", ""));
        final ScamLabRun.Shot shot = new ScamLabRun.Shot(shotDir, meta, burst);
        // ScamLabRun.side: the shot's settings and scam_dev lines as the overlay of this render
        final Map<String, Object> values = ScamLabRun.basePrefs(shot);
        PreferenceValue.overlay(manager.getDefaultPreferences(), values);
        PreferenceKeys.labDevOverlay(shot.dev == null ? java.util.Collections.emptyMap() : shot.dev);

        // the worker's job as ScamNeuralClient makes it for this burst
        final File job = new File(out, "job");
        job.mkdirs();
        for (File f : job.listFiles()) f.delete();
        if (burst.rgbHalfTransport()) new File(job, "rgb-half").createNewFile();
        new File(job, "hybrid-merge").createNewFile();
        if (burst.highlightMask()) new File(job, "hl-mask").createNewFile();
        final String tuning = burst.settingsTuning() + burst.burstTuning();
        Files.write(new File(job, "hybrid_tuning.txt").toPath(), tuning.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "tuning.txt").toPath(), tuning.getBytes(StandardCharsets.UTF_8));
        final File transport = new File(out, "transport.nch");
        burst.write(transport);

        final File mergeF32 = new File(out, "merge.f32"), workerOut = new File(out, "worker.out"), reportFile = new File(out, "report.txt");
        final long m0 = System.nanoTime();
        final Process p = new ProcessBuilder(replay.getAbsolutePath(), job.getAbsolutePath(), transport.getAbsolutePath(),
                mergeF32.getAbsolutePath(), workerOut.getAbsolutePath()).redirectErrorStream(true).redirectOutput(reportFile).start();
        if (p.waitFor() != 0) throw new IOException("hybrid_replay failed:\n" + new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8));
        final String report = new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8);
        System.out.println("HOST POST: merge " + (System.nanoTime() - m0) / 1_000_000 + " ms, transport deleted: " + transport.delete());

        final ByteBuffer rgb = readWorkerOutput(burst, workerOut, report);
        ScamHybridBurst.lastNormals = meta.optInt("normals", 1);
        final Parameters params = ScamLabRun.parameters(shot, null, burst);
        params.scamRgb = rgb;
        params.scamRgbOwned = true;
        params.scamHdrRawScale = 1f;
        final long t0 = System.nanoTime();
        final PostPipeline pipeline = new PostPipeline();
        Bitmap photo;
        try {
            photo = pipeline.Run(null, params);
        } finally {
            pipeline.close();
        }
        System.out.println("HOST POST: post " + (System.nanoTime() - t0) / 1_000_000 + " ms, " + photo.getWidth() + "x" + photo.getHeight()
                + " " + photo.getConfig());
        writePhoto(photo, out);
        ScamRgb.dropClipFlags();
        ScamHybridBurst.lastEffectiveFrames = null;
        ScamHybridBurst.lastHlMask = null;
        ScamHybridBurst.lastMergedDng = null;
    }

    /** ScamNeuralClient.processScamBurst's read of the worker output: the RGB, the trailers and the report's numbers. */
    private static ByteBuffer readWorkerOutput(ScamLabBurst burst, File file, String rep) throws IOException {
        final int ow = burst.outputWidth(), oh = burst.outputHeight();
        final boolean rgbHalf = burst.rgbHalfTransport();
        final long rgbBytes = (long) ow * oh * (rgbHalf ? 6 : 12);
        final long outputBytes = file.length();
        final boolean map = burst.effectiveMap();
        long[] layout = ScamNeuralClient.trailers(outputBytes, rgbBytes, burst.mergedRawPixels() * 2, (long) ow * oh, burst.mergedDng(), map,
                burst.clipFlags(), burst.highlightMask() ? ScamNeuralClient.highlightMaskBytes(burst.width(), burst.height()) : 0L);
        ByteBuffer hlMask = null;
        if (layout != null && layout[3] > 0) {
            hlMask = read(file, outputBytes - layout[3], (int) layout[3]);
            if (!ScamNeuralClient.highlightMaskOk(hlMask, burst.width(), burst.height())) {
                hlMask = null;
                final long[] l = ScamNeuralClient.trailers(outputBytes, rgbBytes, burst.mergedRawPixels() * 2, (long) ow * oh, burst.mergedDng(), map,
                        burst.clipFlags());
                layout = l == null ? null : new long[]{l[0], l[1], l[2], 0};
            }
        }
        if (layout == null) throw new IOException("worker output of " + outputBytes + " B does not match the request");
        final long dngBytes = layout[0], effBytes = layout[1], clipBytes = layout[2];
        ScamHybridBurst.lastHlMask = null;
        if (hlMask != null) {
            hlMask.position(16);
            ScamHybridBurst.lastHlMask = new ScamHybridBurst.HlMask(hlMask.slice().order(ByteOrder.nativeOrder()), burst.width() / 2, burst.height() / 2);
        }
        ScamHybridBurst.lastMergedDng = null;
        ScamHybridBurst.lastEffectiveFrames = null;
        ScamRgb.dropClipFlags();
        float effMedian = Float.NaN;
        for (String line : rep.split("\n"))
            if (line.contains(ScamNeuralClient.EFFECTIVE_MAP_LINE))
                effMedian = ScamNeuralClient.reportNumber(line, ScamNeuralClient.EFFECTIVE_MAP_LINE, "median frames=", Float.NaN);
        ScamHybridBurst.lastEffectiveMedian = effMedian;
        ScamHybridBurst.lastBentoApplied = rep.contains("HYBRID BENTO: applied");
        ScamHybridBurst.lastReport = rep;
        ScamHybridBurst.lastBentoFactor = ScamNeuralClient.reportNumber(rep, "HYBRID BENTO: applied", "factor=", 1f);
        ScamHybridBurst.lastMergedDngHeadroom = Math.max(1f, ScamNeuralClient.reportNumber(rep, "HYBRID DNG:", "headroom=", 1f));
        ScamHybridBurst.lastMergeFrames = com.particlesdevs.photoncamera.processing.DngMetadata.mergeFrames(rep);
        ScamHybridBurst.lastBentoUsClipped = ScamNeuralClient.reportNumber(rep, "HYBRID BENTO: applied", "usClippedRatio=", 0f);
        final float noiseK = ScamNeuralClient.reportNumber(rep, "HYBRID NOISE CHECK:", "-> model x", 1f);
        ScamHybridBurst.lastNoiseFactor = noiseK >= 1f / 16 && noiseK <= 4f ? noiseK : 1f;
        final float darkRead = ScamNeuralClient.reportNumber(rep, "HYBRID NOISE CHECK DARK:", "read term x", 1f);
        ScamHybridBurst.lastNoiseDarkRead = darkRead >= 1f && darkRead <= 16f ? darkRead : 1f;
        ScamHybridBurst.lastDcgNoise = ScamNeuralClient.dcgNoise(rep);
        ScamHybridBurst.lastRgbHalf = rgbHalf;
        final ByteBuffer result = com.particlesdevs.photoncamera.util.Allocator.allocate((int) rgbBytes);
        readInto(file, 0, result);
        result.order(ByteOrder.nativeOrder());
        if (clipBytes > 0) {
            final ByteBuffer clip = com.particlesdevs.photoncamera.util.Allocator.allocate((int) clipBytes);
            readInto(file, rgbBytes + dngBytes + effBytes, clip);
            ScamRgb.keepNativeClipFlags(clip);
        }
        if (effBytes > 0) ScamHybridBurst.lastEffectiveFrames = read(file, rgbBytes + dngBytes, (int) effBytes);
        System.out.println("HOST POST: worker output " + outputBytes + " B: RGB " + rgbBytes + (rgbHalf ? " (halves)" : "") + ", effective map "
                + effBytes + ", clip flags " + clipBytes + ", highlight mask " + (hlMask != null ? layout[3] : 0) + ", median frames " + effMedian);
        return result;
    }

    private static ByteBuffer read(File file, long at, int bytes) throws IOException {
        final ByteBuffer b = ByteBuffer.allocateDirect(bytes);
        readInto(file, at, b);
        return b;
    }

    private static void readInto(File file, long at, ByteBuffer into) throws IOException {
        try (FileChannel ch = new RandomAccessFile(file, "r").getChannel()) {
            long p = at;
            while (into.hasRemaining()) {
                final int n = ch.read(into, p);
                if (n < 0) throw new IOException("short worker output");
                p += n;
            }
        }
        into.flip();
    }

    private static void writePhoto(Bitmap photo, File out) throws IOException {
        final int w = photo.getWidth(), h = photo.getHeight();
        final ByteBuffer px = ByteBuffer.allocate(w * h * 4);
        photo.copyPixelsToBuffer(px);
        try (FileOutputStream f = new FileOutputStream(new File(out, "photo.rgba"))) {
            final ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x38524353).putInt(w).putInt(h).putInt(4);
            f.write(header.array());
            f.write(px.array());
        }
    }
}
