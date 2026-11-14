package scam.host;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

/** A java.nio.Buffer argument of a GL call as Android's GLES JNI takes it: the address at its position (a heap buffer through a direct copy). */
final class HostBuf {
    final Buffer direct;
    final int offset;
    private final Buffer heap;

    private HostBuf(Buffer direct, int offset, Buffer heap) {
        this.direct = direct;
        this.offset = offset;
        this.heap = heap;
    }

    static int shift(Buffer b) {
        if (b instanceof ByteBuffer) return 0;
        if (b instanceof ShortBuffer || b instanceof java.nio.CharBuffer) return 1;
        if (b instanceof IntBuffer || b instanceof FloatBuffer) return 2;
        return 3;
    }

    static HostBuf of(Buffer b) {
        if (b == null) return new HostBuf(null, 0, null);
        if (b.isDirect()) return new HostBuf(b, b.position() << shift(b), null);
        final ByteBuffer copy = ByteBuffer.allocateDirect(Math.max(1, b.remaining() << shift(b))).order(ByteOrder.nativeOrder());
        final Buffer d = b.duplicate();
        if (b instanceof ByteBuffer) copy.put((ByteBuffer) d);
        else if (b instanceof IntBuffer) copy.asIntBuffer().put((IntBuffer) d);
        else if (b instanceof FloatBuffer) copy.asFloatBuffer().put((FloatBuffer) d);
        else if (b instanceof ShortBuffer) copy.asShortBuffer().put((ShortBuffer) d);
        else if (b instanceof LongBuffer) copy.asLongBuffer().put((LongBuffer) d);
        else if (b instanceof DoubleBuffer) copy.asDoubleBuffer().put((DoubleBuffer) d);
        else throw new UnsupportedOperationException("host GL: buffer " + b.getClass());
        copy.clear();
        return new HostBuf(copy, 0, b);
    }

    /** A heap buffer gets back what the call wrote (its position unchanged, as Android's JNI leaves it). */
    void done() {
        if (heap == null || heap.isReadOnly()) return;
        final ByteBuffer from = ((ByteBuffer) direct).duplicate().order(ByteOrder.nativeOrder());
        final int at = heap.position();
        if (heap instanceof ByteBuffer) { ByteBuffer h = (ByteBuffer) heap.duplicate(); h.put(from); }
        else if (heap instanceof IntBuffer) { IntBuffer h = ((IntBuffer) heap).duplicate(); h.position(at); h.put(from.asIntBuffer()); }
        else if (heap instanceof FloatBuffer) { FloatBuffer h = ((FloatBuffer) heap).duplicate(); h.position(at); h.put(from.asFloatBuffer()); }
        else if (heap instanceof ShortBuffer) { ShortBuffer h = ((ShortBuffer) heap).duplicate(); h.position(at); h.put(from.asShortBuffer()); }
        else if (heap instanceof LongBuffer) { LongBuffer h = ((LongBuffer) heap).duplicate(); h.position(at); h.put(from.asLongBuffer()); }
        else if (heap instanceof DoubleBuffer) { DoubleBuffer h = ((DoubleBuffer) heap).duplicate(); h.position(at); h.put(from.asDoubleBuffer()); }
    }
}
