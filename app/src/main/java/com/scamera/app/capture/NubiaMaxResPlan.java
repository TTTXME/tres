package com.scamera.app.capture;

import android.hardware.camera2.CameraMetadata;
import android.util.Size;

import androidx.annotation.Nullable;

/**
 * P626 (owner, 2026-11-11, nubia Z60 Ultra NX721J, build 31256: camera 2's three ways of P608 all «HAL остаётся в режиме
 * биннинга: кадр 12,6 Мп в буфере 50 Мп»): which stream of a nubia maximum-resolution FULLRES session takes which sensor pixel
 * mode, and which requests target which stream.
 * <p>
 * Two layouts:
 * <ul>
 * <li>all in maximum resolution (P108 / P588, the ways «fullmode…» of P608): the only stream of the session is the RAW of the
 * maximum-resolution map, in MAXIMUM_RESOLUTION; the repeating request and every still are MAXIMUM_RESOLUTION, the ZSL ring and
 * the RAW viewfinder are fed by it. The owner's log of 31256: a session of exactly that one stream still ran the binned mode
 * (results «sensor mode 1, asked 0», the 4096x3072 frame in the top of the 8192x6144 buffer) - the HAL never left its binned
 * mode for a repeating MAXIMUM_RESOLUTION request.</li>
 * <li>per shot (P626, the ways «qcfa_snapshot» and «maxres_raw_only»): what the platform's ultra-high-resolution model and the
 * stock camera do (its 50 MP JPEG: preview in DEFAULT, SENSOR_PIXEL_MODE MAXIMUM_RESOLUTION on the snapshot only, P598). The
 * viewfinder (the ISP stream of the default map, the binned size) is DEFAULT and is the repeating request's only target; the RAW
 * of the maximum-resolution map is MAXIMUM_RESOLUTION and only the stills (and the way's short proof burst) target it, with
 * SENSOR_PIXEL_MODE MAXIMUM_RESOLUTION. The camera service rejects a request whose pixel mode a target stream was not configured
 * for («Request settings CONTROL_SENSOR_PIXEL_MODE are not consistent with streams configured», the log of 31256), so a still
 * never carries the viewfinder and the repeating request never carries the RAW: no ZSL ring of 50 MP frames, no RAW viewfinder,
 * the N frames are taken after the shutter (the shot's memfd slots, at most {@link #SHOT_FRAMES}).</li>
 * </ul>
 * Pure: the session builder of CaptureController asks it per stream and per request.
 */
public final class NubiaMaxResPlan {
    private NubiaMaxResPlan() { }

    /** The streams of the session: the ISP viewfinder (SurfaceTexture), the RAW reader, anything else (a YUV reader, a recorder). */
    public enum Stream { VIEWFINDER, RAW, OTHER }

    /** The frames of the per-shot proof burst: MosaicStream needs three confident agreeing frames, one spare. */
    public static final int PROOF_FRAMES = 4;
    /** How long the proof burst may take before its way fails «noproof» (the HAL's mode switch, four 50 MP frames at 20 fps). */
    public static final long PROOF_TIMEOUT_MS = 4000;
    /**
     * A per-shot 50 MP shot's frames after the shutter: the 50 MP ring's shot slots (ZslRing: 8 of 96 MB, ~770 MB) - the Hybrid
     * plan of an empty ring is 4 N + up to 3 ultrashort + 1 long (HybridPlan.fullResLongFrames), «Движение» 4 N.
     */
    public static final int SHOT_FRAMES = 8;

    /**
     * The sensor pixel modes {@code stream} is configured for (OutputConfiguration.addSensorPixelModeUsed): none outside a
     * maximum-resolution session (the camera service's own default); all in maximum resolution: MAXIMUM_RESOLUTION; per shot:
     * the RAW MAXIMUM_RESOLUTION, every other stream DEFAULT.
     */
    public static int[] pixelModes(boolean fullRes, boolean perShot, Stream stream) {
        if (!fullRes) return new int[0];
        if (!perShot || stream == Stream.RAW) return new int[]{CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION};
        return new int[]{CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT};
    }

    /** The repeating request's SENSOR_PIXEL_MODE is MAXIMUM_RESOLUTION (VendorTagUtils.builderSessionApply's flag). */
    public static boolean repeatingMaxRes(boolean fullRes, boolean perShot) {
        return fullRes && !perShot;
    }

    /** The repeating request targets the RAW stream (the ZSL ring, the RAW viewfinder); per shot never. */
    public static boolean rawInRepeating(boolean perShot) {
        return !perShot;
    }

    /** A still may also feed the viewfinder (P90's series viewfinder); per shot never (a MAXIMUM_RESOLUTION request). */
    public static boolean viewfinderInStill(boolean perShot) {
        return !perShot;
    }

    /** The session keeps its ISP viewfinder stream: per shot always (the nubia maximum-resolution session otherwise drops it, P588). */
    public static boolean keepsIspStream(boolean perShot) {
        return perShot;
    }

    /** The session runs the live RAW viewfinder: never per shot (the RAW stream is not repeated). */
    public static boolean liveRaw(boolean perShot, boolean wanted) {
        return wanted && !perShot;
    }

    /**
     * The targets of a request ({@code still}: a shot's or the proof's frame, else the repeating one): a normal session both
     * streams; the nubia all-maximum-resolution session its RAW alone (no ISP stream, P588); per shot the viewfinder repeats, the
     * stills take the RAW alone.
     */
    public static Stream[] targets(boolean fullRes, boolean perShot, boolean still) {
        if (!fullRes) return still ? new Stream[]{Stream.RAW, Stream.VIEWFINDER} : new Stream[]{Stream.VIEWFINDER, Stream.RAW};
        if (!perShot) return new Stream[]{Stream.RAW};
        return still ? new Stream[]{Stream.RAW} : new Stream[]{Stream.VIEWFINDER};
    }

    /** The SENSOR_PIXEL_MODE of a request ({@code still} or the repeating one) of a maximum-resolution session. */
    public static int requestPixelMode(boolean fullRes, boolean perShot, boolean still) {
        if (!fullRes) return CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT;
        return still || !perShot ? CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION : CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT;
    }

    /**
     * The request's targets agree with the streams' configured modes - what the camera service checks on every request
     * (CameraDeviceClient: every target stream must list the request's pixel mode).
     */
    public static boolean consistent(boolean fullRes, boolean perShot, boolean still) {
        final int mode = requestPixelMode(fullRes, perShot, still);
        for (Stream s : targets(fullRes, perShot, still)) {
            final int[] modes = pixelModes(fullRes, perShot, s);
            if (modes.length == 0) {
                if (mode != CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT) return false;
                continue;
            }
            boolean found = false;
            for (int m : modes) found |= m == mode;
            if (!found) return false;
        }
        return true;
    }

    static String mode(int m) {
        return m == CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION ? "MAXIMUM_RESOLUTION" : "DEFAULT";
    }

    private static String list(Stream[] streams) {
        final StringBuilder sb = new StringBuilder("[");
        for (Stream s : streams) sb.append(sb.length() == 1 ? "" : ", ").append(s.name().toLowerCase(java.util.Locale.ROOT));
        return sb.append(']').toString();
    }

    /**
     * The log line of the session's layout: each stream with its size and mode, the requests' modes and targets, the way's
     * operation mode and keys ({@code way}: NubiaRemosaicProbe.describe()).
     */
    public static String describe(String camera, @Nullable String way, boolean perShot, @Nullable Size viewfinder, @Nullable Size raw,
                                  int operationMode) {
        final StringBuilder sb = new StringBuilder("nubia FULLRES camera ").append(camera).append(" session layout (P626): ");
        if (perShot) {
            sb.append("viewfinder ").append(viewfinder).append(' ').append(mode(pixelModes(true, true, Stream.VIEWFINDER)[0]))
                    .append(" (repeating), RAW ").append(raw).append(' ').append(mode(pixelModes(true, true, Stream.RAW)[0]))
                    .append(" (stills only; no ZSL ring, no RAW viewfinder, N after the shutter, at most ").append(SHOT_FRAMES)
                    .append(" frames a shot)");
        } else {
            sb.append("RAW ").append(raw).append(" MAXIMUM_RESOLUTION, no ISP stream (RAW viewfinder, ZSL ring)");
        }
        sb.append("; repeating ").append(mode(requestPixelMode(true, perShot, false))).append(" -> ").append(list(targets(true, perShot, false)))
                .append("; stills ").append(mode(requestPixelMode(true, perShot, true))).append(" -> ").append(list(targets(true, perShot, true)));
        sb.append("; session operation mode 0x").append(Integer.toHexString(operationMode));
        if (way != null) sb.append("; way ").append(way);
        return sb.toString();
    }
}
