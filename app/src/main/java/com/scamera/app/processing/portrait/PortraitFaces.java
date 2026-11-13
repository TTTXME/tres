package com.scamera.app.processing.portrait;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.os.Build;
import android.util.Size;

import java.util.Arrays;

public final class PortraitFaces {
    public static final int MAX_FACES = 5;
    private static final float[] EMPTY = new float[0];
    private static final Result NONE = new Result(false, EMPTY);

    private PortraitFaces() {}

    public static final class Result {
        public final boolean detected;
        /** Normalized left, top, right, bottom in the unrotated RAW buffer. */
        public final float[] boxes;

        Result(boolean detected, float[] boxes) {
            this.detected = detected;
            this.boxes = boxes;
        }
    }

    public static Result fromResult(CameraCharacteristics c, CaptureResult metadata, String physicalId, String logicalId,
                                    int rawWidth, int rawHeight, long timestamp) {
        return fromResult(c, metadata, physicalId, logicalId, rawWidth, rawHeight, timestamp, false);
    }

    public static Result fromResult(CameraCharacteristics c, CaptureResult metadata, String physicalId, String logicalId,
                                    int rawWidth, int rawHeight, long timestamp, boolean vendorSensorWindow) {
        boolean detected = false;
        try {
            if (metadata == null) return NONE;
            final Long logicalTs = metadata.get(CaptureResult.SENSOR_TIMESTAMP);
            final Face[] logicalFaces = logicalTs != null && logicalTs == timestamp ? metadata.get(CaptureResult.STATISTICS_FACES) : null;
            detected = hasFaces(logicalFaces);
            CaptureResult r = metadata;
            if (Build.VERSION.SDK_INT >= 28 && metadata instanceof TotalCaptureResult && physicalId != null
                    && !physicalId.equals(logicalId)) {
                r = ((TotalCaptureResult) metadata).getPhysicalCameraResults().get(physicalId);
                if (r == null) return detected ? new Result(true, EMPTY) : NONE;
            }
            final Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null || ts != timestamp) return detected ? new Result(true, EMPTY) : NONE;
            final Face[] faces = r == metadata ? logicalFaces : r.get(CaptureResult.STATISTICS_FACES);
            final boolean ownFaces = hasFaces(faces);
            detected |= ownFaces;
            if (!detected) return NONE;
            // Logical faces protect contrast; skin sampling requires the RAW camera's own face coordinates.
            if (!ownFaces || c == null || rawWidth <= 0 || rawHeight <= 0) return new Result(true, EMPTY);

            boolean maximum = Build.VERSION.SDK_INT >= 31
                    && Integer.valueOf(CaptureResult.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION).equals(r.get(CaptureResult.SENSOR_PIXEL_MODE));
            final Rect active = maximum ? c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                    : c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Rect pre = maximum ? c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                    : c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
            final Size pixels = maximum ? c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                    : c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (active == null || active.isEmpty()) return new Result(true, EMPTY);
            if (pre == null) pre = active;
            if (pre.isEmpty()) return new Result(true, EMPTY);
            final Integer distortion = Build.VERSION.SDK_INT >= 28 ? r.get(CaptureResult.DISTORTION_CORRECTION_MODE) : null;
            final boolean uncorrected = Integer.valueOf(CaptureResult.DISTORTION_CORRECTION_MODE_OFF).equals(distortion);
            if (!uncorrected && (!active.equals(pre) || distorted(c))) return new Result(true, EMPTY);
            if (Build.VERSION.SDK_INT >= 31) {
                final Integer rotate = r.get(CaptureResult.SCALER_ROTATE_AND_CROP);
                if (rotate != null && rotate != CaptureResult.SCALER_ROTATE_AND_CROP_NONE) return new Result(true, EMPTY);
            }
            final Rect coordinates = uncorrected ? pre : active;
            final Float reportedZoom = Build.VERSION.SDK_INT >= 30 ? r.get(CaptureResult.CONTROL_ZOOM_RATIO) : null;
            final float zoom = reportedZoom == null ? 1f : reportedZoom;
            if (!Float.isFinite(zoom) || zoom < 1f) return new Result(true, EMPTY);
            Rect rawCrop = Build.VERSION.SDK_INT >= 34 ? r.get(CaptureResult.SCALER_RAW_CROP_REGION) : null;
            if (rawCrop != null && rawCrop.isEmpty()) rawCrop = null;
            if (vendorSensorWindow && rawCrop == null) return new Result(true, EMPTY);
            final float fl, ft, fw, fh;
            if (rawCrop != null) {
                fl = pre.left + rawCrop.left;
                ft = pre.top + rawCrop.top;
                fw = rawCrop.width();
                fh = rawCrop.height();
            } else if (pixels != null && sameAspect(rawWidth, rawHeight, pixels.getWidth(), pixels.getHeight())) {
                fl = ft = 0f;
                fw = pixels.getWidth();
                fh = pixels.getHeight();
            } else if (sameAspect(rawWidth, rawHeight, pre.width(), pre.height())) {
                fl = pre.left;
                ft = pre.top;
                fw = pre.width();
                fh = pre.height();
            } else return new Result(true, EMPTY); // Unknown sensor window: preserve protection, skip skin sampling.

            final float[] out = new float[MAX_FACES * 4];
            int n = 0;
            for (Face face : faces) {
                if (face == null || face.getScore() < 50 || face.getBounds().isEmpty()) continue;
                final Rect b = face.getBounds();
                final float l = (pre.left + ((b.left / (float) coordinates.width() - .5f) / zoom + .5f) * pre.width() - fl) / fw;
                final float t = (pre.top + ((b.top / (float) coordinates.height() - .5f) / zoom + .5f) * pre.height() - ft) / fh;
                final float rr = (pre.left + ((b.right / (float) coordinates.width() - .5f) / zoom + .5f) * pre.width() - fl) / fw;
                final float bb = (pre.top + ((b.bottom / (float) coordinates.height() - .5f) / zoom + .5f) * pre.height() - ft) / fh;
                if (append(out, n, l, t, rr, bb)) n += 4;
                if (n == out.length) break;
            }
            return new Result(true, Arrays.copyOf(out, n));
        } catch (RuntimeException unsupported) {
            return detected ? new Result(true, EMPTY) : NONE;
        }
    }

    private static boolean hasFaces(Face[] faces) {
        if (faces != null) for (Face face : faces)
            if (face != null && face.getScore() >= 50 && face.getBounds() != null && !face.getBounds().isEmpty()) return true;
        return false;
    }

    private static boolean distorted(CameraCharacteristics c) {
        final float[] distortion = Build.VERSION.SDK_INT >= 28 ? c.get(CameraCharacteristics.LENS_DISTORTION) : null;
        if (distortion != null) for (float x : distortion) if (!Float.isFinite(x) || Math.abs(x) > 1e-6f) return true;
        return false;
    }

    private static boolean sameAspect(int w, int h, int sw, int sh) {
        return sw > 0 && sh > 0 && Math.abs(w * (double) sh / (h * (double) sw) - 1) < .002;
    }

    public static float[] crop(float[] boxes, float left, float top, float width, float height) {
        if (boxes == null || !Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(width)
                || !Float.isFinite(height) || width <= 0f || height <= 0f) return EMPTY;
        final float[] out = new float[Math.min(boxes.length / 4, MAX_FACES) * 4];
        int n = 0;
        for (int i = 0; i + 3 < boxes.length && n < out.length; i += 4) {
            if (append(out, n, (boxes[i] - left) / width, (boxes[i + 1] - top) / height,
                    (boxes[i + 2] - left) / width, (boxes[i + 3] - top) / height)) n += 4;
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean append(float[] out, int at, float l, float t, float r, float b) {
        if (!Float.isFinite(l) || !Float.isFinite(t) || !Float.isFinite(r) || !Float.isFinite(b)) return false;
        l = Math.max(0f, l); t = Math.max(0f, t); r = Math.min(1f, r); b = Math.min(1f, b);
        if (r <= l || b <= t) return false;
        out[at] = l; out[at + 1] = t; out[at + 2] = r; out[at + 3] = b;
        return true;
    }

    /** Column-major viewport UV -> full RAW UV, following rawdevelop_vs and its texture-axis swap. */
    public static float[] viewTransform(float[] rotation, boolean mirror, float left, float top, float width, float height) {
        if (rotation == null || rotation.length < 16 || !Float.isFinite(left) || !Float.isFinite(top)
                || !Float.isFinite(width) || !Float.isFinite(height) || width <= 0f || height <= 0f) return null;
        for (float v : rotation) if (!Float.isFinite(v)) return null;
        final float det = rotation[0] * rotation[5] - rotation[4] * rotation[1];
        if (!Float.isFinite(det) || Math.abs(det) < 1e-6f) return null;
        final float mx = mirror ? -1f : 1f;
        final float ax = -rotation[1] / det, ay = rotation[0] / det;
        final float bx = rotation[5] / det * mx, by = -rotation[4] / det * mx;
        final float ao = .5f * (1f + (rotation[1] * (1f + rotation[12]) - rotation[0] * (1f + rotation[13])) / det);
        final float bo = .5f * (1f - mx * (rotation[5] * (1f + rotation[12]) - rotation[4] * (1f + rotation[13])) / det);
        return new float[]{width * ax, height * bx, 0f, width * ay, height * by, 0f,
                left + width * ao, top + height * bo, 1f};
    }
}
