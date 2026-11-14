package com.scamera.app.processing.yuv;

import androidx.annotation.Nullable;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * P690 (owner, 2026-11-14: «При включенном YUV бурст не сохраняется»): «Сохранять RAW-серию» of a YUV shot. The RAW routes' series is
 * kept by the merge worker (the keep-burst marker); a YUV shot is merged in the app, so its series is written here, before the merge:
 * "SCAMYUV1", a little-endian int32 header length, the UTF-8 JSON header (size, bits, curve, range, per-frame role / exposure / ISO /
 * timestamp / noise / bytes), then every frame's packed planar 4:2:0 samples as the merge reads them (YuvMergeNative.pack).
 */
public final class YuvBurstFile {
    private YuvBurstFile() {}

    static final byte[] MAGIC = "SCAMYUV1".getBytes(StandardCharsets.US_ASCII);

    /** The JSON header of {@code frames} (the reference first, as YuvBurst merges them). */
    static String header(List<ImageFrame> frames, @Nullable String camera) throws JSONException {
        final ImageFrame first = frames.get(0);
        final JSONObject h = new JSONObject();
        h.put("version", 1);
        h.put("camera", camera == null ? "" : camera);
        h.put("width", first.width);
        h.put("height", first.height);
        h.put("bits", first.yuvBits);
        h.put("sampleBytes", first.yuvBits <= 8 ? 1 : 2);
        h.put("hlg", first.yuvHlg);
        h.put("range", first.yuvRange);
        h.put("layout", "planar420 Y,U,V");
        final JSONArray list = new JSONArray();
        for (ImageFrame f : frames) {
            final JSONObject o = new JSONObject();
            o.put("role", String.valueOf(f.getCaptureRole()));
            o.put("exposureNs", f.measuredExposure);
            o.put("iso", f.measuredIso);
            o.put("timestampNs", f.timestamp);
            if (Float.isFinite(f.noiseSlope)) o.put("noiseSlope", (double) f.noiseSlope);
            if (Float.isFinite(f.noiseOffset)) o.put("noiseOffset", (double) f.noiseOffset);
            o.put("bytes", f.buffer == null ? 0 : f.buffer.capacity());
            list.put(o);
        }
        h.put("frames", list);
        return h.toString();
    }

    /** Writes the series to {@code target}; false (logged) when it could not. The frames' buffers are read, never moved. */
    public static boolean write(List<ImageFrame> frames, @Nullable String camera, File target) {
        if (frames == null || frames.isEmpty() || target == null) return false;
        final long t0 = System.nanoTime();
        long total = 0;
        try (FileOutputStream out = new FileOutputStream(target); FileChannel ch = out.getChannel()) {
            final byte[] json = header(frames, camera).getBytes(StandardCharsets.UTF_8);
            final ByteBuffer head = ByteBuffer.allocate(MAGIC.length + 4 + json.length).order(ByteOrder.LITTLE_ENDIAN);
            head.put(MAGIC).putInt(json.length).put(json).flip();
            while (head.hasRemaining()) ch.write(head);
            for (ImageFrame f : frames) {
                if (f.buffer == null) continue;
                final ByteBuffer view = f.buffer.duplicate();
                view.clear();
                total += view.remaining();
                while (view.hasRemaining()) ch.write(view);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(YuvBurst.TAG, "YUV series not kept (" + target + "): " + e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        }
        Log.i(YuvBurst.TAG, String.format(Locale.ROOT, "YUV series kept: %s, %d frames, %.1f MB in %d ms", target.getAbsolutePath(),
                frames.size(), total / 1048576.0, (System.nanoTime() - t0) / 1_000_000));
        return true;
    }
}
