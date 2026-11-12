package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * P619 follow-up: the JPEG export's region decoder opens from a seekable file descriptor (no copy of the file in native memory)
 * and keeps it until the reader closes; the stream only without one (a pipe, no descriptor, a descriptor the decoder refuses),
 * every descriptor or stream it does not keep closed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class EditSourceRegionTest {

    /** A fake opener over a real file's descriptor: what was opened, what was closed. */
    static final class Fake implements EditSource.RegionOpener<String> {
        boolean seekable = true, noDescriptor, descriptorFails, streamFails;
        int streams;
        ParcelFileDescriptor pfd;
        boolean streamClosed;
        final File file;

        Fake(File file) {
            this.file = file;
        }

        @Nullable
        @Override
        public ParcelFileDescriptor descriptor() throws IOException {
            if (noDescriptor) throw new FileNotFoundException("no descriptor from this provider");
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            return pfd;
        }

        @Override
        public boolean seekable(ParcelFileDescriptor p) {
            return seekable;
        }

        @Nullable
        @Override
        public String fromDescriptor(FileDescriptor fd) throws IOException {
            if (descriptorFails) throw new IOException("refused");
            return "fd";
        }

        @Nullable
        @Override
        public InputStream stream() {
            streams++;
            return new ByteArrayInputStream(new byte[]{1, 2, 3}) {
                @Override
                public void close() {
                    streamClosed = true;
                }
            };
        }

        @Nullable
        @Override
        public String fromStream(InputStream in) {
            return streamFails ? null : "stream";
        }
    }

    private static File file() throws IOException {
        File f = File.createTempFile("region", ".jpg");
        f.deleteOnExit();
        return f;
    }

    private static boolean closed(ParcelFileDescriptor p) {
        try {
            p.getFileDescriptor().sync();
            return !p.getFileDescriptor().valid();
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    @Test
    public void aSeekableDescriptorIsReadInPlace() throws IOException {
        Fake f = new Fake(file());
        EditSource.Opened<String> o = EditSource.openRegion(f);
        assertEquals("fd", o.decoder);
        assertTrue(o.descriptor);
        assertSame("the reader keeps the descriptor open until it closes", f.pfd, o.source);
        assertEquals("no stream, no copy of the file", 0, f.streams);
        assertFalse("open while the reader lives", closed(f.pfd));
        o.source.close();
        assertTrue(closed(f.pfd));
    }

    @Test
    public void aPipeTakesTheStream() throws IOException {
        Fake f = new Fake(file());
        f.seekable = false;
        EditSource.Opened<String> o = EditSource.openRegion(f);
        assertEquals("stream", o.decoder);
        assertFalse(o.descriptor);
        assertEquals(1, f.streams);
        assertTrue("the unused descriptor is closed", closed(f.pfd));
    }

    @Test
    public void noDescriptorOrARefusedOneTakesTheStream() throws IOException {
        Fake a = new Fake(file());
        a.noDescriptor = true;
        assertEquals("stream", EditSource.openRegion(a).decoder);
        Fake b = new Fake(file());
        b.descriptorFails = true;
        EditSource.Opened<String> o = EditSource.openRegion(b);
        assertEquals("stream", o.decoder);
        assertTrue(closed(b.pfd));
    }

    @Test
    public void aStreamWithoutADecoderIsClosedAndSaysSo() throws IOException {
        Fake f = new Fake(file());
        f.seekable = false;
        f.streamFails = true;
        try {
            EditSource.openRegion(f);
            fail();
        } catch (IOException expected) {
            assertTrue(f.streamClosed);
            assertTrue(closed(f.pfd));
        }
    }
}
