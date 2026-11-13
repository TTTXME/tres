package com.scamera.app.processing;

import java.nio.file.Path;

/** The processing of a captured shot as the camera screen follows it (progress, the saved file, failures). */
public interface ProcessingEvents {
    /** The text a failed shot reports. */
    String SAVE_FAILED = "Image Processing/Saving Failed!";

    /** Processing of a shot began ({@code name}: what runs). */
    void processingStarted(String name);

    /** Progress of the running processing. */
    void processingProgress(Object what);

    /** The processing of a shot ended. */
    void processingFinished(Object what);

    /** The shot's file was ({@code saved}) or was not written at {@code file}. */
    void imageSaved(boolean saved, Path file);

    /** The processing failed. */
    void processingFailed(Object what);
}
