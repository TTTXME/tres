package com.scamera.app.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P683 (APK and install size): assets/film's arrays are stored packed (SFD2: tools/film_pack.py's delta + shuffle + deflate,
 * 7.36 MB -> 4.40 MB). FilmSpectral.unpack decodes every array into the little-endian words of the SFD1 files before P683, bit for
 * bit: their SHA-256 are tools/film_arrays.sha256 (made from the SFD1 files). parse gives the same values from an SFD1 and an SFD2
 * file, and a damaged array is refused.
 */
public class FilmPackTest {
    private static File root() {
        File f = new File("app/src/main/assets/film");
        return f.isDirectory() ? new File(".") : new File("..");
    }

    private static String hex(byte[] d) throws Exception {
        final byte[] h = MessageDigest.getInstance("SHA-256").digest(d);
        final StringBuilder s = new StringBuilder();
        for (byte b : h) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    /** "file name dtype dims sha256" of every array of an SFD2 file, as tools/film_pack.py manifest writes them. */
    private static List<String> rows(String file, byte[] bytes) throws Exception {
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final byte[] magic = new byte[4];
        b.get(magic);
        assertEquals(file, "SFD2", new String(magic, StandardCharsets.US_ASCII));
        final int count = b.getInt();
        final List<String> out = new ArrayList<>();
        for (int a = 0; a < count; a++) {
            final byte[] nb = new byte[b.get() & 0xff];
            b.get(nb);
            final int dt = b.get(), nd = b.get();
            final StringBuilder dims = new StringBuilder();
            int n = 1;
            for (int i = 0; i < nd; i++) {
                final int d = b.getInt();
                n *= d;
                if (i > 0) dims.append('x');
                dims.append(d);
            }
            final int codec = b.get();
            b.getInt(); // the CRC-32 (FilmSpectral.parse checks it)
            final byte[] stored = new byte[b.getInt()];
            b.get(stored);
            final byte[] plain = codec == 0 ? stored : FilmSpectral.unpack(stored, dt == 1 ? 2 : 4, n);
            out.add(file + " " + new String(nb, StandardCharsets.US_ASCII) + " " + dt + " " + dims + " " + hex(plain));
        }
        assertEquals(file + ": nothing after the arrays", 0, b.remaining());
        return out;
    }

    @Test
    public void everyArrayDecodesToTheSfd1Words() throws Exception {
        final File dir = new File(root(), "app/src/main/assets/film");
        final File[] files = dir.listFiles((d, n) -> n.endsWith(".bin"));
        assertTrue(files != null && files.length == 29);
        java.util.Arrays.sort(files);
        final List<String> got = new ArrayList<>();
        for (File f : files) got.addAll(rows(f.getName(), Files.readAllBytes(f.toPath())));
        final List<String> want = Files.readAllLines(new File(root(), "tools/film_arrays.sha256").toPath(), StandardCharsets.UTF_8);
        assertEquals(259, want.size());
        assertEquals(want, got);
    }

    /** An SFD1 file of {@code name}'s arrays (the plain words) from an SFD2 one. */
    private static byte[] sfd1(byte[] sfd2) throws Exception {
        final ByteBuffer b = ByteBuffer.wrap(sfd2).order(ByteOrder.LITTLE_ENDIAN);
        b.position(4);
        final int count = b.getInt();
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[]{'S', 'F', 'D', '1'});
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array());
        for (int a = 0; a < count; a++) {
            final int start = b.position();
            final int ln = b.get() & 0xff;
            b.position(b.position() + ln);
            final int dt = b.get(), nd = b.get();
            int n = 1;
            for (int i = 0; i < nd; i++) n *= b.getInt();
            out.write(sfd2, start, b.position() - start);
            final int codec = b.get();
            b.getInt();
            final byte[] stored = new byte[b.getInt()];
            b.get(stored);
            out.write(codec == 0 ? stored : FilmSpectral.unpack(stored, dt == 1 ? 2 : 4, n));
        }
        return out.toByteArray();
    }

    @Test
    public void parseGivesTheSameValuesFromSfd1AndSfd2() throws Exception {
        for (String name : new String[]{"kodak_portra_400.bin", "kodak_2383.bin", "fujifilm_velvia_100.bin"}) {
            final byte[] packed = Files.readAllBytes(new File(root(), "app/src/main/assets/film/" + name).toPath());
            final Map<String, FilmSpectral.Arr> a = FilmSpectral.parse(packed), b = FilmSpectral.parse(sfd1(packed));
            assertEquals(name, b.keySet(), a.keySet());
            for (String k : a.keySet()) {
                assertArrayEquals(name + " " + k, b.get(k).dims, a.get(k).dims);
                final double[] x = a.get(k).v, y = b.get(k).v;
                assertEquals(y.length, x.length);
                for (int i = 0; i < x.length; i++)
                    if (Double.doubleToRawLongBits(x[i]) != Double.doubleToRawLongBits(y[i])) fail(name + " " + k + "[" + i + "]");
            }
        }
    }

    @Test
    public void aDamagedArrayIsRefused() throws Exception {
        final byte[] packed = Files.readAllBytes(new File(root(), "app/src/main/assets/film/kodak_portra_400.bin").toPath());
        for (int at : new int[]{packed.length / 2, packed.length - 3}) {
            final byte[] bad = packed.clone();
            bad[at] ^= 0x10;
            try {
                FilmSpectral.parse(bad);
                fail("a flipped byte at " + at + " was accepted");
            } catch (IOException | RuntimeException expected) {
                // refused
            }
        }
    }

    @Test
    public void wrappingDifferencesOfBothWidths() throws Exception {
        // the words 0xffffffff, 0, 0x80000000, 1 (differences that wrap) as tools/film_pack.py stores them: deflated planes of the
        // differences 0xffffffff, 1, 0x80000000, 0x80000001
        final int[] diffs = {0xffffffff, 1, 0x80000000, 0x80000001};
        final byte[] planes = new byte[16];
        for (int k = 0; k < 4; k++) for (int i = 0; i < 4; i++) planes[k * 4 + i] = (byte) (diffs[i] >>> (8 * k));
        final java.util.zip.Deflater z = new java.util.zip.Deflater(9);
        z.setInput(planes);
        z.finish();
        final byte[] buf = new byte[256];
        final int len = z.deflate(buf);
        z.end();
        final byte[] words = FilmSpectral.unpack(java.util.Arrays.copyOf(buf, len), 4, 4);
        final ByteBuffer w = ByteBuffer.wrap(words).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xffffffff, w.getInt());
        assertEquals(0, w.getInt());
        assertEquals(0x80000000, w.getInt());
        assertEquals(1, w.getInt());
    }
}
