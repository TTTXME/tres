package com.scamera.app.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import com.scamera.app.util.Log;

import java.util.Objects;

/**
 * P212h / P370: the photo's colour matrix for the RAW viewfinder's frames and its cache (camera, neutral, stream kind, the reported
 * transform, the session). P707: moved out of {@link CaptureController} unchanged; the controller's session counter
 * ({@code mVfCcmSession}, counted up by every configured session) and the previewed camera come in with each call. Camera thread.
 */
final class ViewfinderMatrixCache {
    /** P212h (A6): the camera, neutral (ln r, ln b), matrix (column-major, null: none) and source of the last native viewfinder matrix. */
    private String mVfCcmCamera;
    private float mVfCcmLnR = Float.NaN, mVfCcmLnB = Float.NaN;
    @Nullable private float[] mVfCcm;
    @Nullable private String mVfCcmHow;
    /** P370 (A2): the stream kind (mosaic) and whether the transform was the identity, of the cached pick. */
    private boolean mVfCcmMosaic, mVfCcmIdentity;
    /** P370 (review): the reported transform (column-major) the cached matrix of a non-identity transform was made from. */
    private final float[] mVfCcmReported = new float[9];
    /** P370 (B4): the scene light of the cached pick (K, 0 unknown) and the camera of the DNG white estimate below. */
    private float mVfCct;
    @Nullable private String mVfDngCamera;
    @Nullable private com.scamera.app.gallery.dng.DngColor mVfDng;
    private int mVfCcmSessionSeen = -1;

    /**
     * P212h (A6 = M11): the photo's colour matrix for a viewfinder frame, column-major as the viewfinder takes it, at the frame's
     * neutral (the inverse of its white-balance gains, which the OPPO session fills with its own). P370 (A2): every RAW viewfinder
     * stream by the photo's rules (PhotoMatrix.viewfinder): a transform that is no identity, its rows normalised (per frame); an
     * identity, the X7 Ultra's tuned matrix (OppoTunedColor.previewMatrix, any session), on a plain Bayer stream the frame's
     * com.oplus.custom.ccm, on a mosaic stream the camera's plain-session matrix at that light (PlainColourMemory) then the OPPO
     * matrix. Null: none (the identity stays). The pick is looked up again when the neutral moves by more than 0.01 ln, the stream
     * kind changes or a session starts; a change of source is logged once. P370 (B4): its scene light and the tuned flag go to the
     * viewfinder's look (PhotoMatrix.viewfinderSceneCct / viewfinderTuned).
     */
    @Nullable
    float[] matrix(String physicalID, int mVfCcmSession, @Nullable CameraCharacteristics c, @Nullable CaptureResult result, float[] gains,
                   float[] reportedColumnMajor, boolean mosaic) {
        if (c == null || gains == null || !(gains[0] > 0f) || !(gains[1] > 0f) || !(gains[2] > 0f)) return null;
        final float[] neutral = {gains[1] / gains[0], 1f, gains[1] / gains[2]};
        final float lnR = (float) Math.log(neutral[0]), lnB = (float) Math.log(neutral[2]);
        final String camera = physicalID;
        final int session = mVfCcmSession;
        if (session != mVfCcmSessionSeen) {
            // P212z (review): a new session looks again (and logs its source once)
            mVfCcmSessionSeen = session;
            mVfCcmCamera = null;
            mVfCcmHow = "";
        }
        // the transpose of an identity is one (OplusCcm.identity tests each element against the identity's)
        final boolean identity = com.scamera.app.processing.color.OplusCcm.identity(reportedColumnMajor);
        final boolean cached = camera.equals(mVfCcmCamera) && mosaic == mVfCcmMosaic && identity == mVfCcmIdentity
                && !NativeViewfinder.moved(mVfCcmLnR, mVfCcmLnB, lnR, lnB);
        if (!identity) {
            // the result's own transform, rows normalised; its scene light with the neutral. P370 (review): the same transform at a
            // cached neutral returns the matrix made for it - no arrays and no Pick per frame on the camera thread
            if (cached && mVfCcm != null && java.util.Arrays.equals(reportedColumnMajor, mVfCcmReported)) return mVfCcm;
            final float[] reported = NativeViewfinder.columnMajor(reportedColumnMajor); // the transpose: row-major
            final com.scamera.app.processing.color.PhotoMatrix.Pick pick =
                    com.scamera.app.processing.color.PhotoMatrix.viewfinder(reported, null, Float.NaN, mosaic, null, null);
            final float[] out = NativeViewfinder.columnMajor(pick.rowMajor);
            if (!cached) remember(c, result, camera, neutral, lnR, lnB, mosaic, false, pick, out);
            else mVfCcm = out;
            System.arraycopy(reportedColumnMajor, 0, mVfCcmReported, 0, 9);
            return out;
        }
        if (cached) return mVfCcm;
        com.scamera.app.processing.color.PhotoMatrix.Pick pick;
        try {
            float[] tuned = null;
            float tunedCct = Float.NaN;
            if (com.scamera.app.processing.render.OppoTunedColor.applies()) {
                tuned = com.scamera.app.processing.render.OppoTunedColor.previewMatrix(c, neutral, camera);
                if (tuned != null) tunedCct = com.scamera.app.processing.render.OppoTunedColor.previewCct(c, neutral, camera);
            }
            final com.scamera.app.processing.color.PlainColourMemory.Lookup table = tuned == null && mosaic
                    ? com.scamera.app.processing.color.PlainColourMemory.lookup(camera, neutral) : null;
            float[] oplus = null;
            if (tuned == null && table == null && result != null && com.scamera.app.settings.PreferenceKeys.scamDevSwitch("oplus_ccm", true)) {
                try {
                    oplus = com.scamera.app.processing.color.OplusCcm.parse(result.get(new CaptureResult.Key<>(
                            com.scamera.app.processing.color.OplusCcm.TAG_NAME, byte[].class)));
                } catch (RuntimeException noTag) {
                    oplus = null;
                }
            }
            pick = com.scamera.app.processing.color.PhotoMatrix.viewfinder(null, tuned, tunedCct, mosaic, table, oplus);
        } catch (RuntimeException e) {
            pick = com.scamera.app.processing.color.PhotoMatrix.viewfinder(null, null, Float.NaN, mosaic, null, null);
        }
        remember(c, result, camera, neutral, lnR, lnB, mosaic, true, pick,
                pick.rowMajor == null ? null : NativeViewfinder.columnMajor(pick.rowMajor));
        return mVfCcm;
    }

    /** P370 (A2, B4): the cache of {@link #matrix}, its scene light for the look, the log line of a new source. */
    private void remember(CameraCharacteristics c, @Nullable CaptureResult result, String camera, float[] neutral, float lnR, float lnB,
                          boolean mosaic, boolean identity, com.scamera.app.processing.color.PhotoMatrix.Pick pick,
                          @Nullable float[] columnMajor) {
        mVfCcmCamera = camera;
        mVfCcmLnR = lnR;
        mVfCcmLnB = lnB;
        mVfCcmMosaic = mosaic;
        mVfCcmIdentity = identity;
        mVfCcm = columnMajor;
        if (!camera.equals(mVfDngCamera)) {
            mVfDngCamera = camera;
            try {
                mVfDng = com.scamera.app.processing.color.PhotoMatrix.dngWhite(c);
            } catch (RuntimeException e) {
                mVfDng = null;
            }
        }
        int label = 0;
        try {
            label = result == null ? 0 : com.scamera.app.processing.color.PlainColourMemory.label(result.get(CaptureController.AWB_CCT_LABEL));
        } catch (RuntimeException noTag) {
            label = 0;
        }
        mVfCct = com.scamera.app.processing.color.PhotoMatrix.sceneCct(pick, camera, neutral, mVfDng, label);
        com.scamera.app.processing.color.PhotoMatrix.viewfinderSceneCct = mVfCct;
        com.scamera.app.processing.color.PhotoMatrix.viewfinderTuned = pick.tuned;
        final String how = pick.how + (mosaic ? " (mosaic stream)" : "");
        if (!Objects.equals(how, mVfCcmHow)) {
            mVfCcmHow = how;
            Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "viewfinder colour camera %s: %s (neutral %.3f / %.3f, scene %.0f K)",
                    camera, how, neutral[0], neutral[2], mVfCct));
        }
    }
}
