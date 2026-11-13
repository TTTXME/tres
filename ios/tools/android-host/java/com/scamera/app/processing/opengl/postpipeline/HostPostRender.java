package com.scamera.app.processing.opengl.postpipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.PreferenceValue;
import com.scamera.app.settings.SettingsManager;

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
 * ScamNeuralClient reads the worker's; then PostPipeline.develop on the host GPU. Inputs (environment): SCAM_HOST_SHOT = a lab
 * shot folder (meta.json, burst.nch), SCAM_HOST_REPLAY = the hybrid_replay binary, SCAM_HOST_RESULT = the output folder.
 * Output: tuning.txt, report.txt, merge.f32 (SCRG float RGB), photo.rgba (SCR8 header + RGBA8 rows); with SCAM_HOST_DUMP each
 * node's output (ShadowScamDiagnostics). Photos above 16 MP come through the tile readback (ShadowTileBlitter).
 * Ultra HDR (the shot's pref_ultrahdr_key with a JPEG, Settings.ultraHdr): ScamProcessor.s RunHDRGainMap + GainMapComputer after
 * develop - gainmap.rgba (SCR8 header + the map's RGBA8 rows) and gainmap.txt (GainMapMin, GainMapMax, HDRCapacityMax as the floats'
 * raw bits in hex, then as Float.toString, then normalisedMax).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = {scam.host.ShadowGLES20.class, scam.host.ShadowGLES30.class,
        scam.host.ShadowGLES31.class, scam.host.ShadowGLES32.class, scam.host.ShadowEGL14.class, scam.host.ShadowScamDiagnostics.class,
        scam.host.ShadowTileBlitter.class},
        instrumentedPackages = {"com.scamera.app.processing.opengl.postpipeline.ScamDiagnostics",
                "com.scamera.app.processing.opengl.GLCoreBlockProcessing"})
public class HostPostRender {
    private MockedStatic<ScameraApp> camera;
    private SettingsManager manager;
    private com.scamera.app.api.Settings settings;

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
        final com.scamera.app.util.AssetLoader assets = new com.scamera.app.util.AssetLoader(context);
        camera.when(ScameraApp::getAssetLoader).thenReturn(assets);
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        settings = new com.scamera.app.api.Settings();
        camera.when(ScameraApp::getSettings).thenReturn(settings);
        // run.sh SCAM_HOST_FILM=1 (the spectral film engine built for the host): ReferenceFilm's memory headroom as a phone's (its
        // budget then 3 GB, as the C++ port's)
        if ("1".equals(System.getenv("SCAM_HOST_FILM"))) {
            final android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
            memory.availMem = 16L << 30;
            memory.totalMem = 16L << 30;
            org.robolectric.Shadows.shadowOf((android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
        }
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
        // Settings.loadCache's gain-map switch under the shot's settings (the HDR HEIC / AVIF's stays off)
        settings.ultraHdr = PreferenceKeys.isUltraHdrActive();

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
        com.scamera.app.processing.ultrahdr.GainMapComputer.Result gain = null;
        try {
            photo = pipeline.develop(null, params);
            // ScamProcessor: the gain-map pass on the photo as the pipeline produced it, then GainMapComputer
            if (PostPipeline.gainMapWanted(ScameraApp.getSettings().gainMapPass(), pipeline.film)) {
                final PostPipeline.GainMapRaw gm = pipeline.RunHDRGainMap(null, params, photo,
                        com.scamera.app.processing.ultrahdr.GainMapComputer.SCALE_DOWN,
                        com.scamera.app.processing.ultrahdr.GainMapComputer.SCALE);
                final boolean gpu = !Float.isNaN(gm.normalisedMax);
                gain = gpu ? com.scamera.app.processing.ultrahdr.GainMapComputer.normalised(gm.bitmap, gm.normalisedMax)
                        : com.scamera.app.processing.ultrahdr.GainMapComputer.compute(gm.bitmap, gm.down, gm.scale);
                writeGainMap(gain, gm.normalisedMax, out);
                System.out.println("HOST POST: gain map " + gain.gainW + "x" + gain.gainH + " min=" + gain.gainMapMin + " max=" + gain.gainMapMax
                        + " capacity=" + gain.hdrCapacityMax + (gpu ? " (GPU)" : " (CPU)"));
            }
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
        // P501 Sabre 1.5x: the worker returns the size of header words 15 / 16 (ScamHybridBurst.sabreWords), as the app's
        // ScamNeuralClient reads it (ScamHybridBurst.outputWidth); ScamLabBurst's own size is the whole grid's
        final int[] sabre = sabreWords(burst.file);
        final int ow = sabre[0] > 0 ? sabre[0] : burst.outputWidth(), oh = sabre[0] > 0 ? sabre[1] : burst.outputHeight();
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
        ScamHybridBurst.lastMergeFrames = com.scamera.app.processing.DngMetadata.mergeFrames(rep);
        ScamHybridBurst.lastBentoUsClipped = ScamNeuralClient.reportNumber(rep, "HYBRID BENTO: applied", "usClippedRatio=", 0f);
        final float noiseK = ScamNeuralClient.reportNumber(rep, "HYBRID NOISE CHECK:", "-> model x", 1f);
        ScamHybridBurst.lastNoiseFactor = noiseK >= 1f / 16 && noiseK <= 4f ? noiseK : 1f;
        final float darkRead = ScamNeuralClient.reportNumber(rep, "HYBRID NOISE CHECK DARK:", "read term x", 1f);
        ScamHybridBurst.lastNoiseDarkRead = darkRead >= 1f && darkRead <= 16f ? darkRead : 1f;
        ScamHybridBurst.lastDcgNoise = ScamNeuralClient.dcgNoise(rep);
        ScamHybridBurst.lastRgbHalf = rgbHalf;
        final ByteBuffer result = com.scamera.app.util.Allocator.allocate((int) rgbBytes);
        readInto(file, 0, result);
        result.order(ByteOrder.nativeOrder());
        if (clipBytes > 0) {
            final ByteBuffer clip = com.scamera.app.util.Allocator.allocate((int) clipBytes);
            readInto(file, rgbBytes + dngBytes + effBytes, clip);
            ScamRgb.keepNativeClipFlags(clip);
        }
        if (effBytes > 0) ScamHybridBurst.lastEffectiveFrames = read(file, rgbBytes + dngBytes, (int) effBytes);
        System.out.println("HOST POST: worker output " + outputBytes + " B: RGB " + rgbBytes + (rgbHalf ? " (halves)" : "") + ", effective map "
                + effBytes + ", clip flags " + clipBytes + ", highlight mask " + (hlMask != null ? layout[3] : 0) + ", median frames " + effMedian);
        return result;
    }

    /** Header words 15 / 16 of an NCH burst (0, 0: the result is the grid's own size). */
    private static int[] sabreWords(File burst) throws IOException {
        final ByteBuffer h = read(burst, 0, ScamLabBurst.HEADER).order(ByteOrder.LITTLE_ENDIAN);
        return new int[]{h.getInt(60), h.getInt(64)};
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

    private static void writeGainMap(com.scamera.app.processing.ultrahdr.GainMapComputer.Result gain, float normalisedMax,
                                     File out) throws IOException {
        final Bitmap map = gain.gainMap;
        final int w = map.getWidth(), h = map.getHeight();
        final ByteBuffer px = ByteBuffer.allocate(w * h * 4);
        map.copyPixelsToBuffer(px);
        try (FileOutputStream f = new FileOutputStream(new File(out, "gainmap.rgba"))) {
            final ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x38524353).putInt(w).putInt(h).putInt(4);
            f.write(header.array());
            f.write(px.array());
        }
        final String text = Integer.toHexString(Float.floatToRawIntBits(gain.gainMapMin)) + " "
                + Integer.toHexString(Float.floatToRawIntBits(gain.gainMapMax)) + " "
                + Integer.toHexString(Float.floatToRawIntBits(gain.hdrCapacityMax)) + "\n"
                + gain.gainMapMin + " " + gain.gainMapMax + " " + gain.hdrCapacityMax + " " + normalisedMax + "\n";
        Files.write(new File(out, "gainmap.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
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
