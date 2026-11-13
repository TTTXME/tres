package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.scamera.app.util.Allocator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * P625: the RAW crop in place. Frames that are the only view of their arena slot (the ZSL ring's memfd) keep their slot: the kept
 * rows move to its start and the frame takes a view of the kept size there - the same samples as the copy into a new arena, in
 * the same order; any frame another view holds, or outside an arena, leaves the whole shot to the copy (nothing changed first).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = RawCropInPlaceTest.RingAllocator.class,
        instrumentedPackages = "com.scamera.app.util")
public class RawCropInPlaceTest {
    /** One ring memfd (arena 7) as a direct buffer, views counted per slot offset as allocator.cpp does; no new shot arena. */
    @Implements(Allocator.class)
    public static class RingAllocator {
        static ByteBuffer ring;
        static final Map<Long, Integer> refs = new HashMap<>();
        static final Map<ByteBuffer, Long> views = new IdentityHashMap<>();
        static int created;

        static void reset(int bytes) {
            ring = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
            refs.clear();
            views.clear();
            created = 0;
        }

        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static ByteBuffer allocate(int capacity) {
            return ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
        }
        @Implementation protected static int arenaCreate(long bytes) { created++; return 0; }
        @Implementation protected static ByteBuffer arenaView(int id, long offset, int bytes) {
            if (id != 7 || offset < 0 || offset + bytes > ring.capacity()) return null;
            final ByteBuffer d = ring.duplicate();
            d.limit((int) offset + bytes).position((int) offset);
            final ByteBuffer v = d.slice();
            views.put(v, offset);
            refs.merge(offset, 1, Integer::sum);
            return v;
        }
        @Implementation protected static long[] arenaOf(ByteBuffer buffer) {
            final Long at = views.get(buffer);
            return at == null ? null : new long[]{7, at};
        }
        @Implementation protected static int arenaSlotRefs(int id, long offset) {
            return id != 7 ? -1 : refs.getOrDefault(offset, 0);
        }
        @Implementation protected static void free(ByteBuffer buffer) {
            final Long at = views.remove(buffer);
            if (at != null) refs.merge(at, -1, (a, b) -> a + b <= 0 ? null : a + b);
        }
    }

    private static final int W = 520, H = 392, SLOT = 1 << 19;

    @Before public void ring() {
        RingAllocator.reset(SLOT * 8);
    }

    /** A frame of noise around black 64 in ring slot {@code slot} (the last 24 columns and 16 rows exact zeros), as a view. */
    private static ImageFrame ringFrame(int slot, long seed) {
        final ByteBuffer view = Allocator.arenaView(7, (long) slot * SLOT, W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(seed);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                view.putShort((y * W + x) * 2, (short) (x >= W - 24 || y >= H - 16 ? 0 : 60 + r.nextInt(900)));
        final ImageFrame f = ImageFrame.deferred((RingFrame) null, 0x20, W, W * 2, 0, W * H * 2);
        f.buffer = view;
        f.width = W;
        f.height = H;
        return f;
    }

    private static short[] samples(ByteBuffer b, int count) {
        final short[] out = new short[count];
        b.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    /** The copy path's samples of {@code area} (RawCrop.copyRows into a fresh buffer). */
    private static short[] copied(ByteBuffer frame, RawCrop.Area area) {
        final ByteBuffer out = ByteBuffer.allocateDirect(area.width * area.height * 2).order(ByteOrder.LITTLE_ENDIAN);
        RawCrop.copyRows(frame, W, area, out);
        return samples(out, area.width * area.height);
    }

    @Test public void framesAloneInTheirSlotsAreCutInPlaceWithTheCopysSamples() throws Exception {
        final RawCrop.Area[] areas = {
                new RawCrop.Area(0, 0, W - 24, H - 16),          // the X200 Pro's padding (right / bottom)
                new RawCrop.Area(16, 8, W - 16 - 24, H - 8 - 16), // manual margins on top of it (whole colour periods)
                new RawCrop.Area(128, 96, 256, 192),               // a zoom's window (SabreCrop)
                new RawCrop.Area(8, 0, W - 8, H)};                 // rows overlapping their own destination all the way
        for (RawCrop.Area area : areas) {
            ring();
            final List<ImageFrame> frames = new ArrayList<>();
            final List<short[]> want = new ArrayList<>();
            final List<ByteBuffer> before = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final ImageFrame f = ringFrame(i, 100 + i);
                want.add(copied(f.buffer, area));
                before.add(f.buffer);
                frames.add(f);
            }
            assertTrue(area.toString(), RawCrop.apply(frames, W, H, area));
            assertEquals("no new arena", 0, RingAllocator.created);
            for (int i = 0; i < frames.size(); i++) {
                final ImageFrame f = frames.get(i);
                assertEquals(area.width, f.width);
                assertEquals(area.height, f.height);
                assertEquals((long) area.width * area.height * 2, f.buffer.capacity());
                assertArrayEquals("frame " + i + " " + area, want.get(i), samples(f.buffer, area.width * area.height));
                assertArrayEquals("the same slot of the same memfd", new long[]{7, (long) i * SLOT}, Allocator.arenaOf(f.buffer));
                assertEquals("one view of the slot left (the old one freed)", 1, Allocator.arenaSlotRefs(7, (long) i * SLOT));
                assertNull("the full-size view is freed", Allocator.arenaOf(before.get(i)));
                assertEquals(ByteOrder.LITTLE_ENDIAN, f.buffer.order());
                assertEquals(0, f.buffer.position());
            }
        }
    }

    @Test public void theShotsCutLogsAndReportsTheX200ProPaddingInPlace() throws Exception {
        final List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(ringFrame(i, 7 + i));
        final RawCrop.Shot shot = RawCrop.cutShot(frames, 64f, true, new int[4], 90);
        assertArrayEquals(new int[]{0, 0, 24, 16}, shot.padding);
        assertEquals(new RawCrop.Area(0, 0, W - 24, H - 16), shot.area);
        for (ImageFrame f : frames) {
            assertNotNull("still in the ring's memfd", Allocator.arenaOf(f.buffer));
            assertEquals(W - 24, f.width);
        }
        assertEquals(0, RingAllocator.created);
    }

    @Test public void aSlotAnotherViewHoldsLeavesTheWholeShotToTheCopy() throws Exception {
        final List<ImageFrame> frames = new ArrayList<>();
        final List<short[]> whole = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final ImageFrame f = ringFrame(i, 30 + i);
            whole.add(samples(f.buffer, W * H));
            frames.add(f);
        }
        // the ring (or the viewfinder's upload) still holds frame 2's slot
        final ByteBuffer other = Allocator.arenaView(7, 2L * SLOT, W * H * 2);
        final RawCrop.Area area = new RawCrop.Area(0, 0, W - 24, H - 16);
        assertFalse("nothing in place", RawCrop.cropInPlace(frames, W, H, area));
        for (int i = 0; i < 3; i++) {
            assertEquals(W, frames.get(i).width);
            assertArrayEquals("untouched", whole.get(i), samples(frames.get(i).buffer, W * H));
            assertEquals(i == 2 ? 2 : 1, Allocator.arenaSlotRefs(7, (long) i * SLOT));
        }
        assertArrayEquals("the other holder sees the frame as captured", whole.get(2), samples(other, W * H));
        // the copy path (as before P625): the same samples in plain buffers
        assertFalse(RawCrop.apply(frames, W, H, area));
        assertEquals("the copy asked for a new arena first", 1, RingAllocator.created);
        for (int i = 0; i < 3; i++) {
            assertEquals(W - 24, frames.get(i).width);
            assertNull("a plain buffer", Allocator.arenaOf(frames.get(i).buffer));
        }
        assertArrayEquals(whole.get(2), samples(other, W * H));
    }

    @Test public void aFrameOutsideAnArenaOrReadOnlyLeavesTheShotToTheCopy() {
        final RawCrop.Area area = new RawCrop.Area(0, 0, W - 24, H - 16);
        final List<ImageFrame> frames = new ArrayList<>();
        frames.add(ringFrame(0, 1));
        final ImageFrame plain = ImageFrame.deferred((RingFrame) null, 0x20, W, W * 2, 0, W * H * 2);
        plain.buffer = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        plain.width = W;
        plain.height = H;
        frames.add(plain);
        final ByteBuffer first = frames.get(0).buffer;
        assertFalse(RawCrop.cropInPlace(frames, W, H, area));
        assertSame(first, frames.get(0).buffer);
        assertEquals("the trial view was freed again", 1, Allocator.arenaSlotRefs(7, 0));

        final List<ImageFrame> readOnly = new ArrayList<>();
        final ImageFrame f = ringFrame(1, 2);
        f.buffer = f.buffer.asReadOnlyBuffer();
        readOnly.add(f);
        assertFalse(RawCrop.cropInPlace(readOnly, W, H, area));
        assertEquals(W, f.width);
    }

    @Test public void framesOfAnotherSizeAreLeftAlone() {
        final List<ImageFrame> frames = new ArrayList<>();
        frames.add(ringFrame(0, 1));
        final ImageFrame small = ringFrame(1, 2);
        small.width = W / 2; // another stream: the burst drops it, the crop leaves it
        frames.add(small);
        final ByteBuffer kept = small.buffer;
        assertTrue(RawCrop.cropInPlace(frames, W, H, new RawCrop.Area(0, 0, W - 24, H - 16)));
        assertEquals(W - 24, frames.get(0).width);
        assertSame(kept, small.buffer);
        assertEquals(W / 2, small.width);
    }

    /**
     * P662: the frames move on several threads (RawCrop.IN_PLACE_THREADS): a shot of 8 frames (more than the threads), frames of
     * another size between them, every window kind - each frame the copy's samples in its own slot, each old view freed, the
     * frames of another size untouched; the same as one frame after another.
     */
    @Test public void manyFramesMovedOnSeveralThreadsKeepTheCopysSamples() throws Exception {
        assertTrue("more than one mover", RawCrop.IN_PLACE_THREADS > 1);
        final RawCrop.Area[] areas = {new RawCrop.Area(0, 0, W - 24, H - 16), new RawCrop.Area(8, 0, W - 8, H),
                new RawCrop.Area(128, 96, 256, 192)};
        for (RawCrop.Area area : areas) {
            for (int round = 0; round < 3; round++) {
                ring();
                final List<ImageFrame> frames = new ArrayList<>();
                final List<short[]> want = new ArrayList<>();
                final List<ByteBuffer> before = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final ImageFrame f = ringFrame(i, 1000 * round + 10 * i);
                    if (i == 3 || i == 6) f.width = W / 2; // another stream: left alone
                    want.add(f.width == W ? copied(f.buffer, area) : samples(f.buffer, W * H));
                    before.add(f.buffer);
                    frames.add(f);
                }
                assertTrue(area.toString(), RawCrop.cropInPlace(frames, W, H, area));
                for (int i = 0; i < frames.size(); i++) {
                    final ImageFrame f = frames.get(i);
                    if (i == 3 || i == 6) {
                        assertSame("another size: its buffer kept", before.get(i), f.buffer);
                        assertArrayEquals("another size: its samples kept", want.get(i), samples(f.buffer, W * H));
                        assertEquals(1, Allocator.arenaSlotRefs(7, (long) i * SLOT));
                        continue;
                    }
                    assertEquals(area.width, f.width);
                    assertEquals(area.height, f.height);
                    assertArrayEquals("frame " + i + " " + area, want.get(i), samples(f.buffer, area.width * area.height));
                    assertArrayEquals(new long[]{7, (long) i * SLOT}, Allocator.arenaOf(f.buffer));
                    assertEquals("one view of the slot left", 1, Allocator.arenaSlotRefs(7, (long) i * SLOT));
                    assertNull("the full-size view is freed", Allocator.arenaOf(before.get(i)));
                    assertEquals(ByteOrder.LITTLE_ENDIAN, f.buffer.order());
                    assertEquals(0, f.buffer.position());
                }
            }
        }
    }

    /** compactRows against copyRows for random windows (whole colour periods or not): the same samples, the rest of the buffer after them. */
    @Test public void compactingMatchesTheCopyForAnyWindow() {
        final Random r = new Random(5);
        for (int t = 0; t < 200; t++) {
            final int w = 16 + r.nextInt(200), h = 8 + r.nextInt(120);
            final int aw = 1 + r.nextInt(w), ah = 1 + r.nextInt(h), ax = r.nextInt(w - aw + 1), ay = r.nextInt(h - ah + 1);
            final ByteBuffer frame = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < w * h; i++) frame.putShort(i * 2, (short) r.nextInt(65536));
            final RawCrop.Area area = new RawCrop.Area(ax, ay, aw, ah);
            final ByteBuffer out = ByteBuffer.allocateDirect(aw * ah * 2).order(ByteOrder.LITTLE_ENDIAN);
            final ByteBuffer src = frame.duplicate();
            // RawCrop.copyRows reads frames of rows of w samples: same arguments as the crop
            final ByteBuffer d = out.duplicate();
            for (int row = 0; row < ah; row++)
                for (int x = 0; x < aw; x++) d.putShort((row * aw + x) * 2, src.getShort(((ay + row) * w + ax + x) * 2));
            RawCrop.compactRows(frame, w, area, new byte[aw * 2]);
            final short[] got = new short[aw * ah], want = new short[aw * ah];
            frame.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(got);
            out.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(want);
            assertArrayEquals(w + "x" + h + " " + area, want, got);
        }
    }
}
