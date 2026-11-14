package com.scamera.app.capture;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * P635: the session and the requests of a full-resolution recipe (FullResRecipe) - the engine. It knows no phone: every size,
 * use case, usage bit, tag and template is the profile's.
 * <p>
 * The session's streams: the viewfinder as the profile has it (the ISP stream, the camera's own binned RAW stream, or both - the
 * viewfinder never depends on the full-size stream), the capture RAW (the profile's size, usage and vendor stream use case) and
 * the companion (a YUV stream of its own, or the viewfinder's ISP stream). The repeating request targets the viewfinder's
 * streams only; a still targets the capture RAW and the companion, is built from the profile's template and carries the tags of
 * every request and of the stills. The session parameters carry the session tags. With annotated pixel modes the capture
 * streams are MAXIMUM_RESOLUTION and the stills ask for it, the viewfinder stays DEFAULT; never: no stream and no request says
 * anything of a pixel mode.
 * <p>
 * P653 (owner, 2026-11-13, notes of a seamless 50 MP capture run on the Xiaomi 17 Ultra): the vendor stream use case goes on the
 * capture RAW alone (never the viewfinder's RAW or the companion); a still targets the capture RAW and the companion only (the
 * binned RAW in a still gave device error 4 on the 75 mm); a lost buffer fails a still only on the capture RAW (the HAL drops the
 * companion's buffer on full-size stills); with a use-case output configured the viewfinder's results arrive a few frames after
 * their images, so the RAW viewfinder's pairing keeps {@link #LATE_RESULT_IMAGES} unmatched images instead of
 * PreviewFrameMatcher's default.
 * <p>
 * Pure (sizes and formats as numbers); CaptureController builds the Camera2 objects from it.
 */
public final class FullResRecipePlan {
    /** The streams of a recipe session. */
    public enum Stream { VIEWFINDER, RAW_PREVIEW, RAW_CAPTURE, COMPANION }

    /** ImageFormat's numbers (the engine needs no Android class for them). */
    public static final int RAW_SENSOR = 0x20, YUV_420_888 = 0x23, PRIVATE = 0x22, RAW10 = 0x25;
    /** CameraMetadata.SENSOR_PIXEL_MODE_*. */
    public static final int PIXEL_DEFAULT = 0, PIXEL_MAXIMUM_RESOLUTION = 1;
    /**
     * P653: the buffers of the viewfinder's binned RAW reader, and the unmatched images its pairing keeps while the results run
     * late (of the reader's 6: one being developed, one being acquired).
     */
    public static final int RAW_PREVIEW_IMAGES = 6, LATE_RESULT_IMAGES = 4;

    /** One output of the session. */
    public static final class Output {
        public final Stream stream;
        public final int width, height, format;
        /** Reader usage bits (0: the reader's default). */
        public final long usage;
        /** OutputConfiguration.setStreamUseCase (0: not set). */
        public final long useCase;
        /** OutputConfiguration.addSensorPixelModeUsed (empty: not annotated). */
        public final int[] pixelModes;

        Output(Stream stream, int width, int height, int format, long usage, long useCase, int[] pixelModes) {
            this.stream = stream;
            this.width = width;
            this.usage = usage;
            this.height = height;
            this.useCase = useCase;
            this.format = format;
            this.pixelModes = pixelModes;
        }

        @Override public String toString() {
            return stream.name().toLowerCase(Locale.ROOT) + " " + width + "x" + height + " fmt 0x" + Integer.toHexString(format)
                    + (usage != 0 ? " usage 0x" + Long.toHexString(usage) : "") + (useCase != 0 ? " useCase 0x" + Long.toHexString(useCase) : "")
                    + (pixelModes.length == 0 ? "" : pixelModes[0] == PIXEL_MAXIMUM_RESOLUTION ? " MAXIMUM_RESOLUTION" : " DEFAULT");
        }
    }

    /** What the camera gives the plan. */
    public static final class Env {
        /** The ISP viewfinder stream's size (the SurfaceTexture); 0: none can be had. */
        public final int viewfinderWidth, viewfinderHeight;
        /** The camera's own RAW stream (the binned one the normal session runs): size and format; 0: none. */
        public final int previewRawWidth, previewRawHeight, previewRawFormat;
        /** The YUV sizes the camera lists for the capture's pixel mode ({w, h}), for a companion without a size of its own. */
        public final List<int[]> yuvSizes;

        public Env(int viewfinderWidth, int viewfinderHeight, int previewRawWidth, int previewRawHeight, int previewRawFormat,
                   @Nullable List<int[]> yuvSizes) {
            this.viewfinderWidth = viewfinderWidth;
            this.viewfinderHeight = viewfinderHeight;
            this.previewRawWidth = previewRawWidth;
            this.previewRawHeight = previewRawHeight;
            this.previewRawFormat = previewRawFormat;
            this.yuvSizes = yuvSizes == null ? Collections.<int[]>emptyList() : yuvSizes;
        }
    }

    public final FullResRecipe recipe;
    public final List<Output> outputs;
    /** The preview fell back from the profile's (no binned RAW stream of the camera: the ISP viewfinder; no ISP: the RAW one). */
    public final FullResRecipe.Preview preview;

    private FullResRecipePlan(FullResRecipe recipe, List<Output> outputs, FullResRecipe.Preview preview) {
        this.recipe = recipe;
        this.outputs = Collections.unmodifiableList(outputs);
        this.preview = preview;
    }

    /** The plan of {@code recipe} on a camera; null when the camera has no viewfinder stream for it at all. */
    @Nullable
    public static FullResRecipePlan of(FullResRecipe recipe, Env env) {
        final boolean hasIsp = env.viewfinderWidth > 0 && env.viewfinderHeight > 0;
        final boolean hasRaw = env.previewRawWidth > 0 && env.previewRawHeight > 0
                && !(env.previewRawWidth == recipe.width && env.previewRawHeight == recipe.height);
        FullResRecipe.Preview preview = recipe.preview;
        if (preview != FullResRecipe.Preview.ISP && !hasRaw) preview = FullResRecipe.Preview.ISP;
        if (preview != FullResRecipe.Preview.RAW && !hasIsp) preview = FullResRecipe.Preview.RAW;
        // P689: fake off needs both streams (the ISP one configured, the RAW one repeated): without either it is what is left
        if (preview == FullResRecipe.Preview.RAW && !hasRaw) return null;
        final boolean annotate = recipe.pixelMode == FullResRecipe.PixelMode.ANNOTATE;
        final int[] none = new int[0], def = annotate ? new int[]{PIXEL_DEFAULT} : none, max = annotate ? new int[]{PIXEL_MAXIMUM_RESOLUTION} : none;
        final List<Output> out = new ArrayList<>();
        if (preview != FullResRecipe.Preview.RAW)
            out.add(new Output(Stream.VIEWFINDER, env.viewfinderWidth, env.viewfinderHeight, PRIVATE, 0, 0, def));
        if (preview != FullResRecipe.Preview.ISP)
            out.add(new Output(Stream.RAW_PREVIEW, env.previewRawWidth, env.previewRawHeight, env.previewRawFormat, 0, 0, def));
        out.add(new Output(Stream.RAW_CAPTURE, recipe.width, recipe.height, recipe.format == FullResRecipe.RAW10 ? RAW10 : RAW_SENSOR,
                recipe.usage, recipe.useCase, max)); // P689: the profile's format (RAW10: the X9 Ultra recipe's photo stream)
        if (recipe.companion == FullResRecipe.Companion.YUV) {
            final int[] size = recipe.companionWidth > 0 && recipe.companionHeight > 0
                    ? new int[]{recipe.companionWidth, recipe.companionHeight} : companionSize(env.yuvSizes, recipe.width, recipe.height);
            if (size != null) out.add(new Output(Stream.COMPANION, size[0], size[1], YUV_420_888, 0, 0, max));
        }
        return new FullResRecipePlan(recipe, out, preview);
    }

    /**
     * A companion without a size of its own: the smallest size the camera lists of the capture RAW's aspect (within 2 %), else
     * the smallest it lists; null for an empty list.
     */
    @Nullable
    static int[] companionSize(List<int[]> sizes, int rawWidth, int rawHeight) {
        int[] best = null, any = null;
        final double aspect = rawHeight > 0 ? (double) rawWidth / rawHeight : 0;
        for (int[] s : sizes) {
            if (s == null || s.length < 2 || s[0] <= 0 || s[1] <= 0) continue;
            if (any == null || (long) s[0] * s[1] < (long) any[0] * any[1]) any = s;
            if (aspect > 0 && Math.abs((double) s[0] / s[1] - aspect) <= 0.02 * aspect
                    && (best == null || (long) s[0] * s[1] < (long) best[0] * best[1])) best = s;
        }
        return best != null ? best : any;
    }

    /**
     * P653: the viewfinder's binned RAW stream of a capture of {@code width} x {@code height} among the RAW sizes the camera lists
     * ({w, h}): the largest of at most half the capture's sides (the binned mode beside the full-size one; never the full size
     * itself or a larger mode the map may list), else the largest of the list; null for an empty list.
     */
    @Nullable
    public static int[] viewfinderRaw(@Nullable List<int[]> sizes, int width, int height) {
        if (sizes == null) return null;
        int[] binned = null, any = null;
        for (int[] s : sizes) {
            if (s == null || s.length < 2 || s[0] <= 0 || s[1] <= 0) continue;
            final long area = (long) s[0] * s[1];
            if (any == null || area > (long) any[0] * any[1]) any = s;
            if (s[0] * 2 <= width && s[1] * 2 <= height && (binned == null || area > (long) binned[0] * binned[1])) binned = s;
        }
        return binned != null ? binned : any;
    }

    /**
     * P709: the format of the viewfinder's binned RAW stream beside a capture RAW of {@code captureFormat}, of the RAW formats the
     * camera lists ({@code raw16}: RAW_SENSOR, {@code raw10}: RAW10): the capture's own format where it is listed - the X9 Ultra
     * full-size recipe runs two RAW10 streams, and the OPPO Find X9 Ultra's HAL died ~1.5 s after a session of a RAW_SENSOR
     * viewfinder beside the RAW10 photo stream was configured, before its first frame (owner's log of 2026-11-14, every camera) -
     * else RAW_SENSOR where listed (every RAW_SENSOR profile as before), else RAW10; 0: neither is listed.
     */
    public static int viewfinderRawFormat(int captureFormat, boolean raw16, boolean raw10) {
        if (captureFormat == RAW10 && raw10) return RAW10;
        return raw16 ? RAW_SENSOR : raw10 ? RAW10 : 0;
    }

    /** P653: a lost buffer on {@code stream} fails a still - the capture RAW's alone (the companion's is dropped by the HAL). */
    public static boolean lostBufferFailsStill(Stream stream) {
        return stream == Stream.RAW_CAPTURE;
    }

    /**
     * P653: the unmatched images the RAW viewfinder's pairing keeps in this session ({@link #LATE_RESULT_IMAGES} for the binned
     * RAW viewfinder of a recipe; 0 without one: the default stays).
     */
    public int viewfinderImagesKept() {
        return rawViewfinder() ? LATE_RESULT_IMAGES : 0;
    }

    @Nullable
    public Output output(Stream stream) {
        for (Output o : outputs) if (o.stream == stream) return o;
        return null;
    }

    /** P653: the outputs that carry a vendor stream use case. */
    public List<Stream> useCaseStreams() {
        final List<Stream> out = new ArrayList<>();
        for (Output o : outputs) if (o.useCase != 0) out.add(o.stream);
        return out;
    }

    /** The session runs the RAW viewfinder off the camera's own binned RAW stream. */
    public boolean rawViewfinder() {
        return output(Stream.RAW_PREVIEW) != null;
    }

    /** The session has the ISP viewfinder stream. */
    public boolean ispStream() {
        return output(Stream.VIEWFINDER) != null;
    }

    /**
     * P689: the ISP stream is in the session but in no request (fake off) - the RAW viewfinder repeats alone while the HAL keeps
     * the stream it runs its 3A on.
     */
    public boolean ispFake() {
        return preview == FullResRecipe.Preview.FAKE && ispStream() && rawViewfinder();
    }

    /** P689: the capture RAW's format (ImageFormat: RAW_SENSOR or RAW10). */
    public int captureFormat() {
        final Output o = output(Stream.RAW_CAPTURE);
        return o == null ? RAW_SENSOR : o.format;
    }

    /** A still also targets the viewfinder's ISP stream (the profile's «viewfinder» companion). */
    public boolean viewfinderCompanion() {
        return recipe.companion == FullResRecipe.Companion.VIEWFINDER && ispStream();
    }

    /**
     * The targets of a request: {@code still} a shot's frame, else the repeating one (the viewfinder's streams; P653: and the YUV
     * companion with {@link FullResRecipe#viewCompanion}).
     */
    public List<Stream> targets(boolean still) {
        final List<Stream> out = new ArrayList<>();
        if (still) {
            out.add(Stream.RAW_CAPTURE);
            if (output(Stream.COMPANION) != null) out.add(Stream.COMPANION);
            else if (viewfinderCompanion()) out.add(Stream.VIEWFINDER);
        } else {
            if (ispStream() && !ispFake()) out.add(Stream.VIEWFINDER); // P689: fake off - configured, never targeted
            if (rawViewfinder()) out.add(Stream.RAW_PREVIEW);
            // P653: the YUV companion on every request of the session (its frames are dropped), the profile's «YUV на видоискателе»
            if (recipe.viewCompanion && output(Stream.COMPANION) != null) out.add(Stream.COMPANION);
        }
        return out;
    }

    /** The tags of the session parameters. */
    public List<FullResRecipe.Tag> sessionTags() {
        final List<FullResRecipe.Tag> out = new ArrayList<>();
        for (FullResRecipe.Tag t : recipe.tags) if (t.onSession()) out.add(t);
        return out;
    }

    /**
     * The tags of a request ({@code still} or the repeating one). P635c: none on the repeating request of a profile whose
     * viewfinder carries no tags ({@link FullResRecipe#viewTags}): the session parameters and the stills keep theirs.
     */
    public List<FullResRecipe.Tag> requestTags(boolean still) {
        final List<FullResRecipe.Tag> out = new ArrayList<>();
        if (!still && !recipe.viewTags) return out;
        for (FullResRecipe.Tag t : recipe.tags) if (t.onRequest(still)) out.add(t);
        return out;
    }

    /** SENSOR_PIXEL_MODE of a request; null: the request does not set it. */
    @Nullable
    public Integer pixelMode(boolean still) {
        if (recipe.pixelMode != FullResRecipe.PixelMode.ANNOTATE) return null;
        return still ? PIXEL_MAXIMUM_RESOLUTION : PIXEL_DEFAULT;
    }

    /**
     * Every target of a request lists the request's pixel mode - what the camera service checks («Request settings
     * CONTROL_SENSOR_PIXEL_MODE are not consistent with streams configured», P626).
     */
    public boolean consistent(boolean still) {
        final Integer mode = pixelMode(still);
        for (Stream s : targets(still)) {
            final Output o = output(s);
            if (o == null) return false;
            if (o.pixelModes.length == 0) {
                if (mode != null && mode != PIXEL_DEFAULT) return false;
                continue;
            }
            boolean found = false;
            for (int m : o.pixelModes) found |= mode != null && m == mode;
            if (!found) return false;
        }
        return true;
    }

    private static String list(List<Stream> streams) {
        final StringBuilder sb = new StringBuilder("[");
        for (Stream s : streams) sb.append(sb.length() == 1 ? "" : ", ").append(s.name().toLowerCase(Locale.ROOT));
        return sb.append(']').toString();
    }

    private static String tags(List<FullResRecipe.Tag> tags) {
        if (tags.isEmpty()) return "none";
        final StringBuilder sb = new StringBuilder();
        for (FullResRecipe.Tag t : tags) sb.append(sb.length() == 0 ? "" : "; ").append(t);
        return sb.toString();
    }

    /** The session's log line: every stream, the requests' targets, templates, tags and pixel modes - the whole field set. */
    public String describe(String camera) {
        final StringBuilder sb = new StringBuilder("FULLRES RECIPE camera ").append(camera).append(' ')
                .append(recipe.id.isEmpty() ? "own profile" : "«" + recipe.id + "»").append(recipe.experimental ? " (experimental)" : "")
                .append(": streams");
        for (Output o : outputs) sb.append(" {").append(o).append('}');
        if (ispFake()) sb.append(" (the ISP stream as fake off: in the session, in no request, P689)");
        if (preview != recipe.preview) sb.append(" (viewfinder ").append(preview.name().toLowerCase(Locale.ROOT)).append(": the camera has no stream for «")
                .append(recipe.preview.name().toLowerCase(Locale.ROOT)).append("»)");
        sb.append("; repeating -> ").append(list(targets(false))).append(pixelMode(false) == null ? "" : " DEFAULT");
        sb.append("; stills x").append(recipe.frames).append(" template ").append(FullResRecipe.templateName(recipe.stillTemplate)).append(" -> ")
                .append(list(targets(true))).append(pixelMode(true) == null ? ", no sensor pixel mode" : " MAXIMUM_RESOLUTION");
        sb.append("; session template ").append(FullResRecipe.templateName(recipe.sessionTemplate)).append(" operation mode 0x")
                .append(Integer.toHexString(recipe.operationMode));
        if (recipe.fpsLow > 0) sb.append(" fps ").append(recipe.fpsLow).append('-').append(recipe.fpsHigh);
        sb.append("; session tags: ").append(tags(sessionTags())).append("; repeating tags: ").append(tags(requestTags(false)))
                .append("; still tags: ").append(tags(requestTags(true)));
        sb.append("; use case on ").append(list(useCaseStreams())).append("; a lost buffer fails a still on [raw_capture] only");
        if (viewfinderImagesKept() > 0) sb.append("; viewfinder pairing keeps ").append(viewfinderImagesKept()).append(" unmatched images (late results)");
        sb.append(" (P635, P653)");
        return sb.toString();
    }
}
