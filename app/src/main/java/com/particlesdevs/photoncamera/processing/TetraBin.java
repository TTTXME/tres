package com.particlesdevs.photoncamera.processing;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Allocator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P609 (owner, 2026-11-10: «Оба пути», the OnePlus 16 main): a 50 MP shot from the sensor's 200 MP row. The row's frame is the
 * sensor's own Tetra mosaic (4x4 sites of one colour, 8x8 colour period); each 2x2 box inside a colour cell is averaged into
 * one site, so the frame becomes the sensor's own Quad mosaic at half the sides (2x2 sites of one colour, 4x4 period) in the same
 * colour order - the CFA phase, the black and the white level stay (the mean keeps the levels; the noise of a site is the
 * sensor's over 4, ImageFrame.binnedSamples, which the merge's noise model divides by). Done once per still while it is copied
 * out of the camera's reader (into its slot of the shot's arena: 1/4 of the bytes, so 4x the frames fit), natively
 * (allocator.cpp Allocator.binBlocks2, RAW16 or packed RAW10 / RAW12 rows), the Java rows when the native library is not there
 * (host tests). The same box as RawBin's last-resort step on a block-4 mosaic.
 */
public final class TetraBin {
    private TetraBin() {}

    /** The binned size of a w x h Tetra frame: whole 8x8 colour periods only, halved (16320x12288 -> 8160x6144). */
    public static int[] outSize(int width, int height) {
        return new int[]{Math.max(0, width) / 8 * 4, Math.max(0, height) / 8 * 4};
    }

    /** Bytes of the binned uint16 frame. */
    public static long outBytes(int width, int height) {
        final int[] o = outSize(width, height);
        return (long) o[0] * o[1] * 2;
    }

    /**
     * Bins rows of {@code src} (from {@code offset}, {@code rowStride} bytes apart, {@code format} RAW_SENSOR 16-bit or packed
     * RAW10 0x25 / RAW12 0x26) into {@code dst} (outSize, packed uint16 rows, native order). False when the geometry does not
     * fit (nothing is written then or the rows are partial: the caller drops {@code dst}).
     */
    public static boolean bin(ByteBuffer dst, ByteBuffer src, int offset, int format, int width, int height, int rowStride) {
        if (dst == null || src == null || !dst.isDirect() || !src.isDirect()) return binJava(dst, src, offset, format, width, height, rowStride);
        try {
            return Allocator.binBlocks2(dst, src, offset, format, width, height, rowStride);
        } catch (LinkageError noLibrary) {
            return binJava(dst, src, offset, format, width, height, rowStride);
        }
    }

    /**
     * {@link #bin} into the next slot of the shot's {@code arena} (a view of it; the merge worker maps the same memfd), else into a
     * new native buffer; null when neither takes it.
     */
    @Nullable
    public static ByteBuffer binnedInto(@Nullable com.particlesdevs.photoncamera.util.ShotArena arena, ByteBuffer src, int offset,
                                        int format, int width, int height, int rowStride) {
        final long bytes = outBytes(width, height);
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) return null;
        final ByteBuffer slot = arena == null ? null : arena.allocate((int) bytes);
        if (slot != null) {
            slot.order(ByteOrder.nativeOrder());
            if (bin(slot, src, offset, format, width, height, rowStride)) {
                slot.position(0);
                return slot;
            }
            try { Allocator.free(slot); } catch (LinkageError ignored) { }
        }
        return binned(src, offset, format, width, height, rowStride);
    }

    /** {@link #bin} into a new native buffer (no arena); null when it fails. */
    @Nullable
    public static ByteBuffer binned(ByteBuffer src, int offset, int format, int width, int height, int rowStride) {
        final long bytes = outBytes(width, height);
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) return null;
        ByteBuffer out;
        try {
            out = Allocator.allocate((int) bytes);
        } catch (LinkageError noLibrary) {
            out = ByteBuffer.allocateDirect((int) bytes);
        }
        if (out == null) return null;
        out.order(ByteOrder.nativeOrder());
        if (!bin(out, src, offset, format, width, height, rowStride)) {
            try { Allocator.free(out); } catch (LinkageError ignored) { }
            return null;
        }
        out.position(0);
        return out;
    }

    /** The Java rows of {@link #bin}: RAW_SENSOR 16-bit only (a packed frame needs the native unpack). */
    static boolean binJava(ByteBuffer dst, ByteBuffer src, int offset, int format, int width, int height, int rowStride) {
        if (dst == null || src == null || format == 0x25 || format == 0x26) return false;
        final int[] o = outSize(width, height);
        final int ow = o[0], oh = o[1];
        if (ow <= 0 || oh <= 0 || offset < 0 || rowStride < width * 2) return false;
        if ((long) offset + (long) (2 * oh - 1) * rowStride + 4L * ow > src.capacity()) return false;
        if ((long) ow * oh * 2 > dst.capacity()) return false;
        final ByteBuffer in = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer out = dst.duplicate().order(ByteOrder.nativeOrder());
        for (int y = 0; y < oh; y++) {
            final int r0 = offset + 2 * y * rowStride, r1 = r0 + rowStride;
            for (int x = 0; x < ow; x++) {
                final int a = r0 + 4 * x, b = r1 + 4 * x;
                final int sum = (in.getShort(a) & 0xFFFF) + (in.getShort(a + 2) & 0xFFFF) + (in.getShort(b) & 0xFFFF) + (in.getShort(b + 2) & 0xFFFF);
                out.putShort((y * ow + x) * 2, (short) ((sum + 2) >> 2));
            }
        }
        return true;
    }

    /** The frame of a binned still of a {@code width} x {@code height} Tetra frame: its half size, 4 sensor samples a site. */
    public static ImageFrame frame(ByteBuffer binned, int width, int height) {
        final int[] o = outSize(width, height);
        final ImageFrame f = ImageFrame.wrap(binned, o[0], o[1]);
        f.binnedSamples = 4;
        return f;
    }

    /** The log line of a binned still. */
    public static String describe(int width, int height, long ms, boolean arena) {
        final int[] o = outSize(width, height);
        return "50 MP from the 200 MP mode, 2x2 binned per Tetra cell: " + width + "x" + height + " -> " + o[0] + "x" + o[1]
                + " Quad in " + ms + " ms (" + (arena ? "into the shot's arena" : "a native copy") + ")";
    }
}
