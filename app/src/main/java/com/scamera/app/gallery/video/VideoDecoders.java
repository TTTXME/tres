package com.scamera.app.gallery.video;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P499: which video decoders the player tries, in order, for its «HW / HW+ / SW» choice: HW - the phone's hardware decoders only;
 * HW+ - hardware first, the software ones when none would open or one fails while playing; SW - the software (CPU) decoders only.
 */
final class VideoDecoders {
    private VideoDecoders() {}

    static final class Candidate {
        final String name;
        final boolean hardware;

        Candidate(String name, boolean hardware) {
            this.name = name;
            this.hardware = hardware;
        }
    }

    /** A software decoder by its name (for Android 9 and older, which cannot say). */
    static boolean softwareName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") || n.startsWith("c2.ffmpeg.")
                || n.contains(".sw.") || n.endsWith(".sw");
    }

    /** {@code all} in the order the mode tries them; after a hardware failure ({@code fallback}) HW+ keeps only the software ones. */
    static List<Candidate> order(List<Candidate> all, VideoEngine.Decoder mode, boolean fallback) {
        List<Candidate> out = new ArrayList<>();
        if (mode != VideoEngine.Decoder.SW && !fallback)
            for (Candidate c : all) if (c.hardware) out.add(c);
        if (mode != VideoEngine.Decoder.HW)
            for (Candidate c : all) if (!c.hardware) out.add(c);
        return out;
    }

    static List<Candidate> candidates(String mime, MediaFormat format, VideoEngine.Decoder mode, boolean fallback) {
        List<Candidate> all = new ArrayList<>();
        int w = 0, h = 0;
        try {
            w = format.getInteger(MediaFormat.KEY_WIDTH);
            h = format.getInteger(MediaFormat.KEY_HEIGHT);
        } catch (RuntimeException ignored) {
        }
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            if (Build.VERSION.SDK_INT >= 29 && info.isAlias()) continue;
            boolean supports = false;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) supports = true;
            if (!supports) continue;
            try {
                MediaCodecInfo.VideoCapabilities v = info.getCapabilitiesForType(mime).getVideoCapabilities();
                if (v != null && w > 0 && h > 0 && !v.isSizeSupported(w, h) && !v.isSizeSupported(h, w)) continue;
            } catch (RuntimeException ignored) {
            }
            boolean hardware = Build.VERSION.SDK_INT >= 29 ? info.isHardwareAccelerated() && !info.isSoftwareOnly() : !softwareName(info.getName());
            all.add(new Candidate(info.getName(), hardware));
        }
        return order(all, mode, fallback);
    }
}
