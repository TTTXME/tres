package com.scamera.app.gallery.dng;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P706 (owner, 2026-11-14: «более подробные свойства фотографии»): what a DNG's tags say about its raw image, for the viewer's
 * details sheet - read from the IFDs only (a mapped file: no pixel is touched). Pure Java, testable on the JVM.
 */
public final class DngFacts {
    /** «1.4.0.0», null without DNGVersion. */
    @Nullable public String version;
    @Nullable public String uniqueCameraModel;
    /** «RGGB», «Quad RGGB» (a 4x4 pattern of 2x2 cells), «LinearRaw»; null when unknown. */
    @Nullable public String cfa;
    public int bits, width, height, compression;
    /** The mean black level and the white level (0: not given). */
    public double black, white;
    public double baselineExposure;
    /** How many ColorMatrix / ForwardMatrix tags the file has (0, 1, 2). */
    public int colorMatrices, forwardMatrices;
    public boolean noiseProfile;
    /** The opcodes of OpcodeList1 / 2 / 3 by name («GainMap ×4»), null for a list the file lacks. */
    @Nullable public String opcodes1, opcodes2, opcodes3;

    private DngFacts() {}

    /** The facts of the DNG in {@code buffer}; an IOException when it is not a TIFF. */
    public static DngFacts of(ByteBuffer buffer) throws IOException {
        final DngPreview p = DngPreview.parse(buffer);
        final DngFacts f = new DngFacts();
        if (p.dngVersion != null && p.dngVersion.length > 0) {
            final StringBuilder b = new StringBuilder();
            for (int i = 0; i < p.dngVersion.length; i++) b.append(i > 0 ? "." : "").append(p.dngVersion[i]);
            f.version = b.toString();
        }
        f.uniqueCameraModel = p.uniqueCameraModel == null || p.uniqueCameraModel.isEmpty() ? null : p.uniqueCameraModel;
        f.colorMatrices = (p.colorMatrix1 != null ? 1 : 0) + (p.colorMatrix2 != null ? 1 : 0);
        f.forwardMatrices = (p.forwardMatrix1 != null ? 1 : 0) + (p.forwardMatrix2 != null ? 1 : 0);
        f.noiseProfile = p.noiseProfile != null && p.noiseProfile.length >= 2;
        f.baselineExposure = p.baselineExposure;
        DngPreview.Ifd raw = null;
        for (DngPreview.Ifd i : p.ifds) // the full-size raw, as DngRaw picks it
            if ((i.photometric == 32803 || i.photometric == 34892) && (i.subType & 1) == 0
                    && (raw == null || (long) i.width * i.height > (long) raw.width * raw.height)) raw = i;
        if (raw == null) return f;
        f.width = raw.width;
        f.height = raw.height;
        f.bits = raw.bps.length > 0 ? raw.bps[0] : 0;
        f.compression = raw.compression;
        f.white = raw.white > 0 ? raw.white : 0;
        if (raw.black != null && raw.black.length > 0) {
            double sum = 0;
            for (double v : raw.black) sum += v;
            f.black = sum / raw.black.length;
        }
        f.cfa = raw.photometric == 34892 ? "LinearRaw" : cfaName(raw.cfa, raw.cfaRows, raw.cfaCols);
        f.opcodes1 = opcodes(raw.opcodeList1);
        f.opcodes2 = opcodes(raw.opcodeList2);
        f.opcodes3 = opcodes(raw.opcodeList3);
        return f;
    }

    /** «RGGB» of a 2x2 pattern, «Quad RGGB» of a 4x4 one of 2x2 cells, else the colours row by row; null without a pattern. */
    @Nullable
    static String cfaName(@Nullable byte[] cfa, int rows, int cols) {
        if (cfa == null || rows < 1 || cols < 1 || cfa.length < rows * cols) return null;
        if (rows == 2 && cols == 2) return colours(cfa[0], cfa[1], cfa[2], cfa[3]);
        if (rows == 4 && cols == 4) {
            boolean cells = true;
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++) cells &= cfa[y * 4 + x] == cfa[(y & ~1) * 4 + (x & ~1)];
            if (cells) return "Quad " + colours(cfa[0], cfa[2], cfa[8], cfa[10]);
        }
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < rows * cols; i++) b.append(i > 0 && i % cols == 0 ? "/" : "").append(colour(cfa[i]));
        return b.toString();
    }

    private static String colours(byte a, byte b, byte c, byte d) {
        return "" + colour(a) + colour(b) + colour(c) + colour(d);
    }

    private static char colour(byte c) {
        switch (c) {
            case 0: return 'R';
            case 1: return 'G';
            case 2: return 'B';
            case 3: return 'C';
            case 4: return 'M';
            case 5: return 'Y';
            case 6: return 'W';
            default: return '?';
        }
    }

    /** The opcodes of a big-endian opcode list by name and count («GainMap ×4, MapPolynomial»); null without a list. */
    @Nullable
    static String opcodes(@Nullable byte[] list) {
        if (list == null || list.length < 4) return null;
        final Map<String, Integer> seen = new LinkedHashMap<>();
        try {
            final ByteBuffer o = ByteBuffer.wrap(list).order(ByteOrder.BIG_ENDIAN);
            final int n = o.getInt();
            for (int i = 0; i < n && o.remaining() >= 16; i++) {
                final int id = o.getInt();
                o.getInt();
                o.getInt();
                final int bytes = o.getInt();
                if (bytes < 0 || bytes > o.remaining()) break;
                o.position(o.position() + bytes);
                final String name = opcodeName(id);
                final Integer had = seen.get(name);
                seen.put(name, had == null ? 1 : had + 1);
            }
        } catch (RuntimeException e) {
            // what was read so far
        }
        if (seen.isEmpty()) return "—";
        final StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey());
            if (e.getValue() > 1) b.append(" ×").append(e.getValue());
        }
        return b.toString();
    }

    static String opcodeName(int id) {
        switch (id) {
            case 1: return "WarpRectilinear";
            case 2: return "WarpFisheye";
            case 3: return "FixVignetteRadial";
            case 4: return "FixBadPixelsConstant";
            case 5: return "FixBadPixelsList";
            case 6: return "TrimBounds";
            case 7: return "MapTable";
            case 8: return "MapPolynomial";
            case 9: return "GainMap";
            case 10: return "DeltaPerRow";
            case 11: return "DeltaPerColumn";
            case 12: return "ScalePerRow";
            case 13: return "ScalePerColumn";
            case 14: return "WarpRectilinear2";
            default: return "#" + id;
        }
    }

    /** The compression's name («uncompressed», «lossless JPEG», ...). */
    public static String compressionName(int compression) {
        switch (compression) {
            case 1: return "uncompressed";
            case 7: return "lossless JPEG";
            case 8: return "Deflate";
            case 34892: return "lossy JPEG";
            case 52546: return "JPEG XL";
            default: return "#" + compression;
        }
    }
}
