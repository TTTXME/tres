package com.scamera.app.gallery.helper;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import com.scamera.app.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Ultra HDR and wide colour in the gallery: whether the display can show HDR, a header-only test for an Ultra HDR JPEG
 * (gain map), the colour space of a file, and the viewer window's colour mode.
 */
public final class UltraHdrGalleryUtil {
    private static final String TAG = "UltraHdrGallery";
    /** How much of a file's start the gain-map test reads: the metadata segments come first. */
    private static final int HEADER_BYTES = 64 * 1024;
    private static final byte[] XMP_ID = ascii("http://ns.adobe.com/xap/1.0/\0");
    private static final byte[] GAIN_MAP_XMP = ascii("hdr-gain-map");
    private static final byte[] MPF_ID = {'M', 'P', 'F', 0};
    private static final byte[] ISO_21496_ID = ascii("urn:iso:std:iso:ts:21496:-1");
    private static final int APP1 = 0xE1, APP2 = 0xE2;

    private UltraHdrGalleryUtil() {}

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Android 14+ on an HDR display: HWUI then draws a gain map in HDR. */
    public static boolean isDeviceHdrCapable(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm == null ? null : wm.getDefaultDisplay();
            return display != null && display.isHdr();
        } catch (Exception e) {
            Log.d(TAG, "no HDR display information: " + e);
            return false;
        }
    }

    /** Whether the file's first {@value #HEADER_BYTES} bytes carry an Ultra HDR segment ({@link #containsUltraHdrMarkers}). */
    public static boolean isUltraHdrJpeg(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] head = new byte[HEADER_BYTES];
            int filled = 0;
            for (int n; filled < head.length && (n = in.read(head, filled, head.length - filled)) > 0; ) filled += n;
            return containsUltraHdrMarkers(head, filled);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Walks the JPEG segments in {@code data[0, length)}: true at an APP1 XMP packet naming the hdrgm (gain map) namespace,
     * an APP2 multi-picture (MPF) segment or an APP2 ISO 21496-1 segment. Stops at a segment running past the data.
     */
    public static boolean containsUltraHdrMarkers(byte[] data, int length) {
        if (data == null || length < 4) return false;
        int at = 0;
        while (at + 4 <= length) {
            if ((data[at] & 0xFF) != 0xFF) {
                at++; // not at a marker (entropy-coded data, padding)
                continue;
            }
            int marker = data[at + 1] & 0xFF;
            boolean bare = marker == 0xD8 || marker == 0xD9 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7);
            if (bare) { // SOI, EOI, TEM, RSTn: no length field
                at += 2;
                continue;
            }
            int size = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            int end = at + 2 + size;
            if (size < 2 || end > length) return false;
            if (marksGainMap(marker, data, at + 4, end)) return true;
            at = end;
        }
        return false;
    }

    private static boolean marksGainMap(int marker, byte[] d, int from, int to) {
        if (marker == APP1) return begins(d, from, to, XMP_ID) && find(d, from, to, GAIN_MAP_XMP);
        if (marker == APP2) return begins(d, from, to, MPF_ID) || begins(d, from, to, ISO_21496_ID);
        return false;
    }

    private static boolean begins(byte[] d, int from, int to, byte[] sig) {
        return to - from >= sig.length && same(d, from, sig);
    }

    private static boolean find(byte[] d, int from, int to, byte[] sig) {
        for (int i = from; i <= to - sig.length; i++) if (same(d, i, sig)) return true;
        return false;
    }

    private static boolean same(byte[] d, int at, byte[] sig) {
        for (int k = 0; k < sig.length; k++) if (d[at + k] != sig[k]) return false;
        return true;
    }

    /** P46: colour of an image file ({@link #colourOf}): sRGB (or unknown), a wider gamut (Display P3, BT.2020), HDR (HLG / PQ). */
    public static final int COLOUR_SRGB = 0, COLOUR_WIDE = 1, COLOUR_HDR = 2;

    /**
     * P46: the colour space the platform decoder reads from the file header (ICC profile, nclx): a Display P3 photo
     * («Цветовое пространство» Display P3) is {@link #COLOUR_WIDE}, an HLG / PQ HEIC or AVIF («HDR в HEIC / AVIF», Android 14
     * names those spaces) {@link #COLOUR_HDR}. Bounds-only decode: no pixels.
     */
    public static int colourOf(Context context, Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context == null || uri == null) {
            return COLOUR_SRGB;
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return COLOUR_SRGB;
            }
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(in, null, options);
            return classify(options.outColorSpace);
        } catch (Throwable e) {
            Log.d(TAG, "colour space probe failed: " + e);
            return COLOUR_SRGB;
        }
    }

    /** {@link #COLOUR_SRGB} for sRGB (and spaces Android takes as sRGB) or none, HDR for BT.2020 HLG / PQ, wide otherwise. */
    static int classify(android.graphics.ColorSpace space) {
        if (space == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return COLOUR_SRGB;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && (space.equals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020_HLG))
                || space.equals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020_PQ)))) {
            return COLOUR_HDR;
        }
        if (space.isSrgb() || space.getModel() != android.graphics.ColorSpace.Model.RGB) {
            return COLOUR_SRGB;
        }
        return COLOUR_WIDE;
    }

    /**
     * P46: the window colour mode for a page: HDR for an active Ultra HDR rendition or an HLG / PQ file on an HDR display,
     * wide colour for a Display P3 (or other wide-gamut) file on a wide-gamut display, the default otherwise - so an sRGB
     * page gets exactly {@link #setWindowHdr}'s modes.
     */
    public static void setWindowMode(Activity activity, boolean hdr, int colour) {
        if (activity == null) {
            return;
        }
        if (hdr || colour == COLOUR_SRGB) {
            setWindowHdr(activity, hdr);
            return;
        }
        try {
            int mode = ActivityInfo.COLOR_MODE_DEFAULT;
            if (colour == COLOUR_HDR && isDeviceHdrCapable(activity)) {
                mode = ActivityInfo.COLOR_MODE_HDR;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && activity.getResources().getConfiguration().isScreenWideColorGamut()) {
                mode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT;
            }
            activity.getWindow().setColorMode(mode);
        } catch (Exception e) {
            Log.d(TAG, "Failed to set window color mode: " + e);
        }
    }

    /**
     * The window in HDR colour mode (HWUI then applies a bitmap's gain map on a hardware canvas) or back to the default.
     */
    public static void setWindowHdr(Activity activity, boolean enabled) {
        if (activity == null) return;
        try {
            activity.getWindow().setColorMode(enabled ? ActivityInfo.COLOR_MODE_HDR : ActivityInfo.COLOR_MODE_DEFAULT);
        } catch (Exception e) {
            Log.d(TAG, "window colour mode not set: " + e);
        }
    }
}
