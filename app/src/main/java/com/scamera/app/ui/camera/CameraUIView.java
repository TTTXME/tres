package com.scamera.app.ui.camera;

import com.scamera.app.processing.ProcessingProgress;

/**
 * What the capture side asks of the camera screen's views: the shutter, the capture ring over the viewfinder, the processing
 * ring round the gallery card, the burst lock and the top bar. Every call is safe from any thread (the implementation posts to
 * the views) and does nothing by default, so a screen that is gone can be a bare {@code new CameraUIView() {}}.
 * <p>
 * The first version of this interface was created by Vibhor Srivastava on 25/09/2020.
 */
public interface CameraUIView {
    /** The shutter takes a shot (true) or is busy (false). */
    default void shutterReady(boolean ready) {
    }

    /** Every view again from the settings; with {@code processing} false the shutter, the ring and the controls are freed. */
    default void refresh(boolean processing) {
    }

    /** P158: a shot's processing starts (true: the ring round the gallery card runs) or ends (false: it closes). */
    default void processingRunning(boolean running) {
    }

    /** P158: the pipeline's milestone (0..1) of the shot being processed. */
    default void processingStage(float fraction) {
    }

    /** P220: the large-shot hint or the merge worker's measured share. */
    default void processingStage(ProcessingProgress progress) {
    }

    /** The capture ring empty and hidden. */
    default void captureRingReset() {
    }

    default void captureRingStep(int frames) {
    }

    default void captureRingAlpha(float alpha) {
    }

    default void captureRingMax(int frames) {
    }

    /** P234: the capture ring at {@code frames} (a long ZSL series: those frames are taken already). */
    default void captureRingAt(int frames) {
    }

    /** The receiver of the screen's clicks (the top bar, the shutter row). */
    default void listenTo(CameraUIEventsListener listener) {
    }

    /** The lens has a flash (the shade's flash tile is live) or not (dimmed, saying why). */
    default void flashAvailable(boolean available) {
    }

    /** The top bar's badges and the bottom row's modes after a setting changed. */
    default void rebindTopBar() {
    }

    /** During a burst everything but the shutter (the burst's own control) takes no touch and is dimmed. */
    default void lockForBurst(boolean locked) {
    }

    /** The video recording line under the shutter. */
    default void recordingLine(boolean visible) {
    }

    /** The screen's views are gone (onDestroy). */
    default void release() {
    }
}
