package com.scamera.app.processing.opengl.postpipeline;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** A RAW burst the native worker consumes through `--scam-capture` (NCH v9 7-slot or the SCAM Hybrid v10 transport). */
interface ScamTransport {
    int width();
    int height();
    /** Size of the RGB the worker returns (the SCAM Hybrid can merge on the Sabre 2x grid and resize); default = sensor. */
    default int outputWidth() { return width(); }
    default int outputHeight() { return height(); }
    int cfa();
    /** Whether the worker should also return the merged Bayer RAW for the DNG. */
    boolean mergedDng();
    /** P195: pixels of that merged Bayer RAW (uint16 each): the sensor's, or the binned Bayer of a SCAM Hybrid without remosaic. */
    default long mergedRawPixels() { return (long) width() * height(); }
    boolean diagnostics();
    /**
     * Whether the worker should append the per-pixel clip flags (uint8 per output pixel, after the effective-frame map;
     * SCAM Hybrid only, header flag 4). The client then accepts that third trailer.
     */
    default boolean clipFlags() { return false; }
    /**
     * P187: whether the worker makes the effective-frame map (the trailer after the merged RAW). False (header flag 16): the
     * clip flags follow the RGB / the merged RAW directly.
     */
    default boolean effectiveMap() { return true; }
    /**
     * P480: whether the worker may append the base highlight mask (job marker hl-mask; the last trailer, HALF of the sensor grid,
     * only from the fast merge with its highlight path on) for {@link WhyHlDezipper}; scam_dev.txt "why_hl_dezipper 0" turns it off.
     * P501: on the Sabre 1.5x / 2x output too (the mask stays the sensor's HALF grid, the dezipper samples it in uv and steps its
     * filter by the scale, as the reference's u_sabre_scale).
     */
    default boolean highlightMask() {
        return hybrid() && com.scamera.app.settings.PreferenceKeys.scamDevSwitch("why_hl_dezipper", true);
    }
    /**
     * P30: the burst already lies in shared memory (the shot's arena): the transport header is written into it and a
     * descriptor of that memfd is returned (the caller owns it); null: write() the burst as before.
     */
    default android.os.ParcelFileDescriptor sharedBurst() { return null; }
    /** P277: the SCAM Hybrid merge (job markers hybrid-merge, rgb-half, cre-off and the tuning file); false: the 7-slot burst. */
    default boolean hybrid() { return false; }
    /** P80: the hybrid RGB comes back as half floats. */
    default boolean rgbHalfTransport() { return false; }
    /** The worker leaves the CRE motion out (job marker cre-off). */
    default boolean creOff() { return false; }
    /** The settings' part of the worker tuning (hybrid_tuning.txt), before {@link #burstTuning}. */
    default String settingsTuning() { return com.scamera.app.settings.PreferenceKeys.hybridTuningText(); }
    /** The burst's own tuning lines after the settings' (ScamHybridBurst.tuningOverride). */
    default String burstTuning() { return ""; }
    /** P277: whether the worker may keep this burst (keep_burst, the lab); false for a burst replayed from its file. */
    default boolean keepable() { return hybrid(); }
    /** Lab stage view: the worker writes the merge's stage maps (job marker stage-dump, ScamNeuralClient.lastStages). */
    default boolean stages() { return false; }
    void write(FileChannel out) throws IOException;
    void write(File file) throws IOException;
}
