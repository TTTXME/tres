package com.scamera.app.processing;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * P665 (owner, 2026-11-13: «перенеси все настройки из нашей камеры в проявщик», «Все настройки обработки»): what the gallery's
 * developer needs to run the camera's own post pipeline again on a DNG of ours - kept in the DNG itself, appended at its very end
 * (after the TIFF structure and the P620 preview IFD; nothing before it moves, TIFF readers never look past their offsets), the
 * same way P633 keeps a photo's watermark record:
 * <ul>
 * <li>the post pipeline's Parameters of the shot as the lab keeps them (ScamLabParams.toJson, right before the pipeline), every
 * default preference (ScamLabParams.prefs: the settings the shot was processed with) and the scam_dev.txt lines in force;</li>
 * <li>the merge's numbers the pipeline's nodes read beside the RGB (ScamHybridBurst: Bento applied / factor / ultrashort clip,
 * the frame map's median, the noise factor, the normal frames, the merge's report);</li>
 * <li>the merge's per-pixel maps the nodes read (the clip flags, the effective-frame map, the base highlight mask), deflated, when
 * the shot's output is at most {@link #MAP_MAX_PIXELS} (a 50 / 200 MP shot keeps the numbers only: its maps would be 50-400 MB to
 * copy and deflate beside the shot);</li>
 * <li>the DNG's headroom (the merged mosaic is written over it, P165): the RGB the pipeline took is the DNG's level times it.</li>
 * </ul>
 * Layout: «SCDV», version (int), CRC-32 of the sections (int), then sections {tag (4 ASCII), w, h, raw length, deflated length,
 * deflated bytes}; the record ends with its length (int, big-endian) and «SCDVEND1». The camera's pixels are not touched: the
 * record is written after the photo and the DNG were saved, from copies.
 */
public final class DevelopRecord {
    public static final int VERSION = 1;
    static final byte[] MAGIC = {'S', 'C', 'D', 'V'};
    static final byte[] END = {'S', 'C', 'D', 'V', 'E', 'N', 'D', '1'};
    static final int FOOTER = 4 + END.length;
    /** The largest record read back (bytes). */
    static final long MAX_RECORD = 256L << 20;
    /** The largest output (pixels) whose per-pixel maps are kept. */
    public static final long MAP_MAX_PIXELS = 24_000_000L;
    static final String JSON = "JSON", CLIP = "CLIP", FRAMES = "EFFR", HL_MASK = "HLMK";

    /** {"v", "build", "params", "prefs", "dev", "worker": {...}, "dng": {"headroom"}}. */
    public JSONObject meta = new JSONObject();
    /** uint8 per output pixel ({@code mapW x mapH}), null when not kept. */
    @Nullable public byte[] clipFlags, frames;
    public int clipW, clipH, framesW, framesH;
    /** The base highlight mask: IEEE halves, native order, {@code hlW x hlH}; null when not kept. */
    @Nullable public byte[] hlMask;
    public int hlW, hlH;

    /** The merge's numbers the post pipeline's nodes read (ScamHybridBurst's statics of the shot). */
    public static final class Worker {
        public boolean bentoApplied;
        public float bentoFactor = 1f, bentoUsClipped, effectiveMedian = Float.NaN, noiseFactor = 1f;
        public int normals = 1;
        public String report = "";

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("bento", bentoApplied).put("bentoFactor", finite(bentoFactor))
                    .put("bentoUs", finite(bentoUsClipped)).put("effMedian", finite(effectiveMedian))
                    .put("noiseFactor", finite(noiseFactor)).put("normals", normals).put("report", report == null ? "" : report);
        }

        public static Worker of(@Nullable JSONObject o) {
            final Worker w = new Worker();
            if (o == null) return w;
            w.bentoApplied = o.optBoolean("bento", false);
            w.bentoFactor = number(o.opt("bentoFactor"), 1f);
            w.bentoUsClipped = number(o.opt("bentoUs"), 0f);
            w.effectiveMedian = number(o.opt("effMedian"), Float.NaN);
            w.noiseFactor = number(o.opt("noiseFactor"), 1f);
            w.normals = o.optInt("normals", 1);
            w.report = o.optString("report", "");
            return w;
        }
    }

    public Worker worker() {
        return Worker.of(meta.optJSONObject("worker"));
    }

    /** The DNG's headroom: its 16-bit white is this many times the N frames' white (1 when nothing was above it). */
    public float headroom() {
        final JSONObject d = meta.optJSONObject("dng");
        return Math.max(1f, d == null ? 1f : number(d.opt("headroom"), 1f));
    }

    static Object finite(float v) {
        return Float.isFinite(v) ? (Object) (double) v : (Object) (Float.isNaN(v) ? "NaN" : v > 0 ? "Infinity" : "-Infinity");
    }

    static float number(@Nullable Object o, float otherwise) {
        if (o instanceof Number) return ((Number) o).floatValue();
        if (o == null) return otherwise;
        try {
            return Float.parseFloat(o.toString());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    // ---------------------------------------------------------------- bytes

    /** The record as appended (without the footer). */
    public byte[] toBytes() throws IOException {
        final ByteArrayOutputStream sections = new ByteArrayOutputStream();
        section(sections, JSON, 0, 0, meta.toString().getBytes(StandardCharsets.UTF_8));
        if (clipFlags != null) section(sections, CLIP, clipW, clipH, clipFlags);
        if (frames != null) section(sections, FRAMES, framesW, framesH, frames);
        if (hlMask != null) section(sections, HL_MASK, hlW, hlH, hlMask);
        final byte[] body = sections.toByteArray();
        final CRC32 crc = new CRC32();
        crc.update(body);
        final ByteBuffer out = ByteBuffer.allocate(12 + body.length).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).putInt(VERSION).putInt((int) crc.getValue()).put(body);
        return out.array();
    }

    private static void section(ByteArrayOutputStream out, String tag, int w, int h, byte[] raw) {
        final byte[] packed = deflate(raw);
        final ByteBuffer head = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        head.put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(w).putInt(h).putInt(raw.length).putInt(packed.length);
        out.write(head.array(), 0, 20);
        out.write(packed, 0, packed.length);
    }

    static byte[] deflate(byte[] raw) {
        final Deflater d = new Deflater(Deflater.BEST_SPEED);
        try {
            d.setInput(raw);
            d.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            final byte[] chunk = new byte[64 * 1024];
            while (!d.finished()) {
                final int n = d.deflate(chunk);
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    static byte[] inflate(byte[] packed, int off, int len, int rawLength) throws IOException {
        final Inflater i = new Inflater();
        try {
            i.setInput(packed, off, len);
            final byte[] out = new byte[rawLength];
            int at = 0;
            while (at < rawLength) {
                final int n = i.inflate(out, at, rawLength - at);
                if (n == 0 && (i.finished() || i.needsInput() || i.needsDictionary())) break;
                at += n;
            }
            if (at != rawLength) throw new IOException("develop record: section cut short");
            return out;
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("develop record: " + e.getMessage());
        } finally {
            i.end();
        }
    }

    /** The record of {@link #toBytes}; null when it is not one (wrong magic, version, CRC, damaged sections). */
    @Nullable
    public static DevelopRecord fromBytes(byte[] b) {
        try {
            final ByteBuffer in = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            if (b.length < 12) return null;
            final byte[] magic = new byte[4];
            in.get(magic);
            if (!Arrays.equals(magic, MAGIC) || in.getInt() != VERSION) return null;
            final int crcStored = in.getInt();
            final CRC32 crc = new CRC32();
            crc.update(b, 12, b.length - 12);
            if ((int) crc.getValue() != crcStored) return null;
            final DevelopRecord r = new DevelopRecord();
            boolean json = false;
            while (in.remaining() >= 20) {
                final byte[] t = new byte[4];
                in.get(t);
                final String tag = new String(t, StandardCharsets.US_ASCII);
                final int w = in.getInt(), h = in.getInt(), raw = in.getInt(), packed = in.getInt();
                if (raw < 0 || packed < 0 || packed > in.remaining() || raw > MAX_RECORD * 4) return null;
                final byte[] data = inflate(b, in.position(), packed, raw);
                in.position(in.position() + packed);
                switch (tag) {
                    case JSON:
                        r.meta = new JSONObject(new String(data, StandardCharsets.UTF_8));
                        json = true;
                        break;
                    case CLIP:
                        if ((long) w * h != raw) return null;
                        r.clipFlags = data;
                        r.clipW = w;
                        r.clipH = h;
                        break;
                    case FRAMES:
                        if ((long) w * h != raw) return null;
                        r.frames = data;
                        r.framesW = w;
                        r.framesH = h;
                        break;
                    case HL_MASK:
                        if ((long) w * h * 2 != raw) return null;
                        r.hlMask = data;
                        r.hlW = w;
                        r.hlH = h;
                        break;
                    default:
                        break; // a later version's section: skipped
                }
            }
            return json && r.meta.optInt("v", 0) == VERSION ? r : null;
        } catch (IOException | JSONException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the file

    /** {@code record} appended to {@code file} with the footer. */
    public static void append(File file, DevelopRecord record) throws IOException {
        final byte[] body = record.toBytes();
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            final long end = f.length();
            f.seek(end);
            f.write(body);
            final ByteBuffer footer = ByteBuffer.allocate(FOOTER).order(ByteOrder.BIG_ENDIAN);
            footer.putInt(body.length).put(END);
            f.write(footer.array());
        }
    }

    /** The record at the end of {@code channel} (a whole file), null when there is none or it does not read. */
    @Nullable
    public static DevelopRecord read(FileChannel channel) throws IOException {
        final long size = channel.size();
        if (size < FOOTER + 16) return null;
        final ByteBuffer footer = ByteBuffer.allocate(FOOTER).order(ByteOrder.BIG_ENDIAN);
        readFully(channel, footer, size - FOOTER);
        footer.flip();
        final long len = footer.getInt() & 0xFFFFFFFFL;
        final byte[] end = new byte[END.length];
        footer.get(end);
        if (!Arrays.equals(end, END) || len < 12 || len > MAX_RECORD || len > size - FOOTER - 8) return null;
        final ByteBuffer rec = ByteBuffer.allocate((int) len);
        readFully(channel, rec, size - FOOTER - len);
        return fromBytes(rec.array());
    }

    /** The record of {@code file}, null when there is none or it cannot be read. */
    @Nullable
    public static DevelopRecord read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return read(in.getChannel());
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer b, long at) throws IOException {
        long pos = at;
        while (b.hasRemaining()) {
            final int n = ch.read(b, pos);
            if (n < 0) throw new IOException("short read");
            pos += n;
        }
    }

    /** A copy of {@code buffer}'s first {@code bytes} (from 0), or null when it holds fewer. */
    @Nullable
    public static byte[] copy(@Nullable ByteBuffer buffer, long bytes) {
        if (buffer == null || bytes <= 0 || bytes > Integer.MAX_VALUE || buffer.capacity() < bytes) return null;
        final ByteBuffer d = buffer.duplicate();
        d.clear();
        final byte[] out = new byte[(int) bytes];
        d.get(out);
        return out;
    }
}
