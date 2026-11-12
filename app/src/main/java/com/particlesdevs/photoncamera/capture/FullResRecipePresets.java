package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * P635: the built-in full-resolution recipes, by phone and camera - the only place a phone's numbers live (the engine,
 * FullResRecipePlan, has none).
 * <ul>
 * <li>Xiaomi 17 Ultra, camera 2 (the 23 mm main): the owner's documents, proven on the phone (2026-10-26 .. 2026-11-10) - RAW
 * 8192x6144 of usage 0x100003 with the vendor stream use case 0x80103, a 640x480 YUV companion in the still, the still template,
 * current_mode [0] and xiaomi.remosaic.enabled [1] on the session and every request, no sensor pixel mode; the viewfinder the
 * binned 4096x3072 RAW stream beside it (the documents' dual-stream run; the ISP SurfaceTexture beside it was not tried there).
 * </li>
 * <li>P635d, Xiaomi 17 Ultra, cameras 3 (the ultra wide) and 4 (the 75 mm), experimental: the phone's own stream table (the
 * owner's device report, 2026-10-11: mivivendorstreamconfig.json, the manual full-size RAW mode 0x900E of roles 0, 20, 21, 23 -
 * a RAW16 stream of use case 0x80103) says the main camera's recipe is the vendor's for every rear camera. The sizes are the
 * cameras' SensorModeTable rows (8192x6144 at 12 fps, row 0; the 75 mm 8160x6144 at 24 fps, the vendor's 50 MP row 2), so no
 * frame rate is asked. Not run on a phone.</li>
 * <li>nubia (a camera with a full-size row in its sensor mode table), experimental: the stock camera's QCFA snapshot as P626 has
 * it (session mode 0x8012, the ZTE feature bit, app_mode, CustomRemosiac on the stills, the capture streams annotated
 * MAXIMUM_RESOLUTION) plus what the documents add - a companion stream in the still. Not run on a phone.</li>
 * <li>OnePlus 16, camera 2 (the main), experimental: its 50 MP row 25 at the HAL's 8192x6144 frame with the engineer mode key on
 * the session and every request (P593 / P604: every RAW-only session of the row died 0.7 s after it was configured) plus the
 * documents' companion and still template, the ISP viewfinder kept. Not run on a phone.</li>
 * </ul>
 */
public final class FullResRecipePresets {
    private FullResRecipePresets() {}

    /** What a preset is matched by (android.os.Build's fields). */
    public static final class Device {
        public final String manufacturer, brand, device, model;

        public Device(@Nullable String manufacturer, @Nullable String brand, @Nullable String device, @Nullable String model) {
            this.manufacturer = low(manufacturer);
            this.brand = low(brand);
            this.device = low(device);
            this.model = low(model);
        }

        public static Device current() {
            return new Device(android.os.Build.MANUFACTURER, android.os.Build.BRAND, android.os.Build.DEVICE, android.os.Build.MODEL);
        }

        private static String low(@Nullable String s) {
            return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        }

        boolean xiaomi17Ultra() {
            return manufacturer.equals("xiaomi") && (device.startsWith("nezha") || model.equals("2512bpndag") || model.equals("25128pna1c"));
        }

        boolean nubia() {
            return manufacturer.equals("nubia") || brand.equals("nubia");
        }

        boolean onePlus16() {
            return manufacturer.equals("oneplus") && model.equals("pyb110");
        }

        @Override public String toString() {
            return manufacturer + " " + model + " (" + device + ")";
        }
    }

    static final String QCOM_MODE = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    static final String XIAOMI_REMOSAIC = "xiaomi.remosaic.enabled";
    static final String XIAOMI_17_ULTRA_WIDE = "Xiaomi 17 Ultra 50 MP (ultra wide)", XIAOMI_17_ULTRA_TELE = "Xiaomi 17 Ultra 50 MP (75 mm)";
    static final String XIAOMI_17_ULTRA = "Xiaomi 17 Ultra 50 MP", NUBIA = "nubia QCFA snapshot + companion", ONEPLUS_16 = "OnePlus 16 row 25 + companion";

    /**
     * The preset of {@code camera} on {@code device}; {@code table}: the camera's SensorModeTable (null: none) - a preset that
     * takes its size from the camera's full-size row has none without it. Null: no preset (the page starts empty).
     */
    @Nullable
    public static FullResRecipe preset(@Nullable Device device, @Nullable String camera, @Nullable int[] table) {
        if (device == null || camera == null) return null;
        if (device.xiaomi17Ultra() && "2".equals(camera))
            return new FullResRecipe.Builder().id(XIAOMI_17_ULTRA).size(8192, 6144).usage(0x100003L).useCase(0x80103L)
                    .companion(FullResRecipe.Companion.YUV, 640, 480)
                    .tag(QCOM_MODE, FullResRecipe.TagType.I32_ARRAY, FullResRecipe.Target.BOTH, 0)
                    .tag(XIAOMI_REMOSAIC, FullResRecipe.TagType.U8_ARRAY, FullResRecipe.Target.BOTH, 1)
                    .stillTemplate("still").sessionTemplate("still").fps(30, 30)
                    .pixelMode(FullResRecipe.PixelMode.NEVER).preview(FullResRecipe.Preview.RAW).frames(4).build();
        if (device.xiaomi17Ultra() && ("3".equals(camera) || "4".equals(camera))) {
            final boolean tele = "4".equals(camera);
            return new FullResRecipe.Builder().id(tele ? XIAOMI_17_ULTRA_TELE : XIAOMI_17_ULTRA_WIDE).size(tele ? 8160 : 8192, 6144)
                    .usage(0x100003L).useCase(0x80103L).companion(FullResRecipe.Companion.YUV, 640, 480)
                    .tag(QCOM_MODE, FullResRecipe.TagType.I32_ARRAY, FullResRecipe.Target.BOTH, tele ? 2 : 0)
                    .tag(XIAOMI_REMOSAIC, FullResRecipe.TagType.U8_ARRAY, FullResRecipe.Target.BOTH, 1)
                    .stillTemplate("still").sessionTemplate("still")
                    .pixelMode(FullResRecipe.PixelMode.NEVER).preview(FullResRecipe.Preview.RAW).frames(4).experimental(true).build();
        }
        if (device.nubia()) {
            final int[] full = fullRow(table);
            if (full == null) return null;
            return new FullResRecipe.Builder().id(NUBIA).size(full[1], full[2]).usage(0).useCase(0)
                    .companion(FullResRecipe.Companion.YUV, 0, 0)
                    .tag(NubiaRemosaicProbe.ZTE_FEATURE, FullResRecipe.TagType.I32, FullResRecipe.Target.SESSION, NubiaRemosaicProbe.ZTE_FEATURE_QCFA)
                    .tag(NubiaRemosaicProbe.NUBIA_APP_MODE, FullResRecipe.TagType.I32, FullResRecipe.Target.REQUEST, 1)
                    .tag(NubiaRemosaicProbe.ZTE_CUSTOM_REMOSAIC, FullResRecipe.TagType.U8, FullResRecipe.Target.STILL, 1)
                    .stillTemplate("still").sessionTemplate("preview")
                    .pixelMode(FullResRecipe.PixelMode.ANNOTATE).preview(FullResRecipe.Preview.ISP).frames(4)
                    .operationMode(NubiaRemosaicProbe.STOCK_QCFA_MODE).experimental(true).build();
        }
        if (device.onePlus16() && "2".equals(camera))
            return new FullResRecipe.Builder().id(ONEPLUS_16).size(8192, 6144).usage(0).useCase(0)
                    .companion(FullResRecipe.Companion.YUV, 640, 480)
                    .tag(OppoNativeFullRes.MODE_KEY, FullResRecipe.TagType.U8, FullResRecipe.Target.BOTH, 25)
                    .stillTemplate("still").sessionTemplate("preview")
                    .pixelMode(FullResRecipe.PixelMode.NEVER).preview(FullResRecipe.Preview.ISP).frames(4).experimental(true).build();
        return null;
    }

    /**
     * {row, width, height} of a binning sensor's full-size row: the largest row of the table while a row of half its sides
     * exists (the binned mode beside it); null without one.
     */
    @Nullable
    static int[] fullRow(@Nullable int[] table) {
        int[] best = null;
        for (int[] r : SensorModePick.rows(table))
            if (best == null || (long) r[1] * r[2] > (long) best[1] * best[2]) best = r;
        if (best == null) return null;
        for (int[] r : SensorModePick.rows(table))
            if (r[1] * 2 == best[1] && r[2] * 2 == best[2]) return new int[]{best[0], best[1], best[2]};
        return null;
    }
}
