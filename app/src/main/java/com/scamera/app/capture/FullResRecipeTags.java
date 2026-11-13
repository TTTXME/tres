package com.scamera.app.capture;

import android.hardware.camera2.CaptureRequest;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * P635: a recipe's vendor tags on a Camera2 request builder (the session parameters, the repeating request, a still), by the
 * profile's type of each: i32 / i64 / u8 / f32 a scalar, i32[] / u8[] an array - the Xiaomi keys of the owner's documents are
 * arrays the camera does not list as session keys, which Java Camera2 still takes.
 */
public final class FullResRecipeTags {
    private FullResRecipeTags() {}

    /** Sets {@code tags}; the log text of what was set and what the builder refused. */
    public static String apply(@Nullable CaptureRequest.Builder builder, List<FullResRecipe.Tag> tags) {
        if (builder == null || tags.isEmpty()) return "none";
        final StringBuilder sb = new StringBuilder();
        for (FullResRecipe.Tag t : tags) {
            sb.append(sb.length() == 0 ? "" : "; ");
            try {
                set(builder, t);
                sb.append(t);
            } catch (RuntimeException refused) {
                sb.append(t.key).append(" refused (").append(refused.getMessage()).append(')');
            }
        }
        return sb.toString();
    }

    /**
     * P635c: what a result reports for the profile's tags ("key=[values]", the key's last two parts; "key=?" for a key the
     * result does not carry) - whether the camera took the mode the tags ask for, also on a viewfinder request without them.
     */
    public static String read(@Nullable android.hardware.camera2.CaptureResult result, List<FullResRecipe.Tag> tags) {
        if (result == null || tags.isEmpty()) return "none";
        final StringBuilder sb = new StringBuilder();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (FullResRecipe.Tag t : tags) {
            if (!seen.add(t.key)) continue;
            sb.append(sb.length() == 0 ? "" : " ").append(shortKey(t.key)).append('=').append(value(result, t));
        }
        return sb.toString();
    }

    /** The last two dot-separated parts of a key. */
    static String shortKey(String key) {
        final int last = key.lastIndexOf('.'), before = last <= 0 ? -1 : key.lastIndexOf('.', last - 1);
        return before < 0 ? key : key.substring(before + 1);
    }

    private static String value(android.hardware.camera2.CaptureResult result, FullResRecipe.Tag t) {
        final boolean bytes = t.type == FullResRecipe.TagType.U8 || t.type == FullResRecipe.TagType.U8_ARRAY;
        try {
            if (bytes) {
                final byte[] v = result.get(new android.hardware.camera2.CaptureResult.Key<>(t.key, byte[].class));
                if (v != null) return java.util.Arrays.toString(v);
            } else {
                final int[] v = result.get(new android.hardware.camera2.CaptureResult.Key<>(t.key, int[].class));
                if (v != null) return java.util.Arrays.toString(v);
            }
        } catch (RuntimeException ignored) {
            // not an array of that type, or no such key in the result
        }
        try {
            final Object v = bytes ? result.get(new android.hardware.camera2.CaptureResult.Key<>(t.key, Byte.class))
                    : result.get(new android.hardware.camera2.CaptureResult.Key<>(t.key, Integer.class));
            if (v != null) return String.valueOf(v);
        } catch (RuntimeException ignored) {
            // no such key in the result
        }
        return "?";
    }

    static void set(CaptureRequest.Builder builder, FullResRecipe.Tag t) {
        switch (t.type) {
            case I32:
                builder.set(new CaptureRequest.Key<>(t.key, Integer.class), (int) t.values[0]);
                break;
            case I64:
                builder.set(new CaptureRequest.Key<>(t.key, Long.class), (long) t.values[0]);
                break;
            case U8:
                builder.set(new CaptureRequest.Key<>(t.key, Byte.class), (byte) (int) t.values[0]);
                break;
            case F32:
                builder.set(new CaptureRequest.Key<>(t.key, Float.class), (float) t.values[0]);
                break;
            case I32_ARRAY: {
                final int[] v = new int[t.values.length];
                for (int i = 0; i < v.length; i++) v[i] = (int) t.values[i];
                builder.set(new CaptureRequest.Key<>(t.key, int[].class), v);
                break;
            }
            case U8_ARRAY: {
                final byte[] v = new byte[t.values.length];
                for (int i = 0; i < v.length; i++) v[i] = (byte) (int) t.values[i];
                builder.set(new CaptureRequest.Key<>(t.key, byte[].class), v);
                break;
            }
            default:
                throw new IllegalArgumentException("type " + t.type);
        }
    }
}
