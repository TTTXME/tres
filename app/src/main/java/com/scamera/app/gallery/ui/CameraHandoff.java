package com.scamera.app.gallery.ui;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.files.ImageFile;

import java.io.File;

/**
 * P637 (owner, 2026-11-12, a screen recording of the X200 Ultra: the camera's thumbnail tapped right after an «Oldstyle» shot,
 * the viewer black for ~6 s, then the photo): what the camera hands to the gallery it opens, in the same process, so the viewer
 * has something to draw in its first frame and does not wait for MediaStore:
 * <ul>
 *   <li>the viewfinder's frame at the shutter of the shot still being processed (the viewer's placeholder under «Обработка»),</li>
 *   <li>the camera's thumbnail and the file of its last saved photo (shown from its path before the media lists are read),</li>
 *   <li>each file saved while the gallery is open ({@link Listener#saved}: the final photo takes the placeholder's place), and
 *   its MediaStore row once the scanner knows it ({@link Listener#scanned}).</li>
 * </ul>
 * The camera writes ({@link #shutter}, {@link #saved}, {@link #scanned}, {@link #latest}, {@link #open}); the gallery reads
 * ({@link #take}, {@link #listen}). Nothing here touches MediaStore or a file's content.
 */
public final class CameraHandoff {
    private CameraHandoff() {}

    /** The long side of the shutter frame kept for the placeholder (a 3:4 viewfinder: 540 x 720, 1.5 MB). */
    public static final int FRAME_WIDTH = 540;

    /** What a gallery opened from the camera's thumbnail starts with. */
    public static final class Open {
        /** Upright: the shutter frame of the pending shot, else the camera's thumbnail; null: nothing to show. */
        @Nullable public final Bitmap picture;
        /** A shot was still captured, queued, merged or saved at the tap. */
        public final boolean pending;
        /** The camera's last photo (its file, or its MediaStore row when known); null: unknown, or a shot is pending. */
        @Nullable public final ImageFile latest;
        /** The tap (uptime ms). */
        public final long tapAt;
        /** «shutter frame» / «camera thumbnail» / «none», for the log. */
        public final String what;
        /** The saves counted at the tap ({@link Saved#seq}): a later one is the shot the tap waited for. */
        public final int saves;

        Open(@Nullable Bitmap picture, boolean pending, @Nullable ImageFile latest, long tapAt, String what, int saves) {
            this.saves = saves;
            this.picture = picture;
            this.pending = pending;
            this.latest = latest;
            this.tapAt = tapAt;
            this.what = what;
        }
    }

    /** A file the camera saved: its path, when (uptime ms), its number among this process's saves, its MediaStore row once scanned. */
    public static final class Saved {
        public final String path;
        public final long at;
        public final int seq;
        @Nullable public final Uri row;

        Saved(String path, long at, int seq, @Nullable Uri row) {
            this.path = path;
            this.at = at;
            this.seq = seq;
            this.row = row;
        }
    }

    /** The open gallery's ears, on the main thread. */
    public interface Listener {
        /** The camera saved the photo at {@code path} (the photo format; a RAW of the same shot comes before it). */
        void saved(String path);

        /** The media scanner filed {@code path} as {@code row}. */
        void scanned(String path, Uri row);
    }

    private static final Object LOCK = new Object();
    private static Bitmap frame;
    private static Saved lastSaved;
    private static int saves;
    /** The newest photo MediaStore had when the camera made its thumbnail from it (no shot saved by this process since). */
    private static ImageFile storeLatest;
    private static Open opened;
    private static Listener listener;
    private static Handler main;

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /**
     * The viewfinder as it was at the shutter ({@code turn}: the degrees the camera's icons are turned by, 0 / 90 / -90 / 180, so
     * the frame is kept upright as the photo will be). Main thread.
     */
    public static void shutter(@Nullable Bitmap viewfinder, int turn) {
        if (viewfinder == null) return;
        Bitmap upright = upright(viewfinder, turn);
        synchronized (LOCK) {
            frame = upright;
        }
    }

    /** {@code b} turned back by the icons' turn; the same bitmap for 0. */
    static Bitmap upright(Bitmap b, int turn) {
        int t = ((turn % 360) + 360) % 360;
        if (t == 0) return b;
        Matrix m = new Matrix();
        m.setRotate(-t);
        try {
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            return b;
        }
    }

    /** A photo file is saved (any thread; the camera reports the photo format last). */
    public static void saved(@Nullable String path) {
        if (path == null) return;
        final Listener l;
        synchronized (LOCK) {
            lastSaved = new Saved(path, SystemClock.uptimeMillis(), ++saves, null);
            storeLatest = null;
            l = listener;
        }
        if (l != null) main().post(() -> {
            if (l == current()) l.saved(path);
        });
    }

    /** The media scanner's answer for a saved file (its thread). */
    public static void scanned(@Nullable String path, @Nullable Uri row) {
        if (path == null || row == null) return;
        final Listener l;
        synchronized (LOCK) {
            if (lastSaved != null && path.equals(lastSaved.path)) lastSaved = new Saved(path, lastSaved.at, lastSaved.seq, row);
            l = listener;
        }
        if (l != null) main().post(() -> {
            if (l == current()) l.scanned(path, row);
        });
    }

    /** The camera's thumbnail now shows MediaStore's newest photo {@code f} (the camera's resume; null: no photo there). */
    public static void latest(@Nullable ImageFile f) {
        synchronized (LOCK) {
            storeLatest = f;
            // a photo this process saved stays the camera's last one until MediaStore's newest is another, newer file (or it is gone)
            if (lastSaved != null && f != null && newer(f, lastSaved)) lastSaved = null;
        }
    }

    /** Whether MediaStore's {@code f} is another, newer file than the one this process saved last (which may be gone: trash). */
    private static boolean newer(ImageFile f, Saved s) {
        if (s.path.equals(f.filePath())) return false;
        File file = new File(s.path);
        return !file.exists() || f.modifiedMs() > file.lastModified() + 1000;
    }

    /**
     * The camera's thumbnail was tapped: {@code thumbnail} is what it shows, {@code busy} whether a shot is still captured,
     * queued, merged or saved. Main thread, before the gallery activity starts.
     */
    public static void open(@Nullable Bitmap thumbnail, boolean busy) {
        synchronized (LOCK) {
            final long now = SystemClock.uptimeMillis();
            if (busy) {
                // the newest shutter frame is the shot in work's (each shot's press replaces it)
                opened = new Open(frame, true, null, now, frame != null ? "shutter frame" : "none", saves);
            } else {
                frame = null; // every shot is saved: its frame is not needed any more
                Bitmap shown = copyOf(thumbnail);
                opened = new Open(shown, false, latestFile(), now, shown != null ? "camera thumbnail" : "none", saves);
            }
        }
    }

    /**
     * The camera's thumbnail as the gallery's own software bitmap (200 px): the camera's is Glide's - it may be a hardware bitmap,
     * and is replaced when the camera comes back.
     */
    @Nullable
    private static Bitmap copyOf(@Nullable Bitmap thumbnail) {
        if (thumbnail == null || thumbnail.isRecycled()) return null;
        try {
            return thumbnail.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The camera's last photo as a gallery file: its MediaStore row when the scanner answered, else its path; null: unknown. */
    @Nullable
    private static ImageFile latestFile() {
        ImageFile own = lastSaved != null ? fileOf(lastSaved.path, lastSaved.row) : null;
        return own != null ? own : storeLatest;
    }

    /**
     * The gallery file of a saved photo: {@code row} its MediaStore row when known, else read from its path (a file URI, id -1);
     * null when the file is not there.
     */
    @Nullable
    public static ImageFile fileOf(@Nullable String path, @Nullable Uri row) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0) return null;
        long id = -1;
        if (row != null) try {
            id = android.content.ContentUris.parseId(row);
        } catch (RuntimeException e) {
            id = -1;
        }
        return new ImageFile(id, row != null ? row : Uri.fromFile(f), f.getName(), f.lastModified(), f.length(), f.getAbsolutePath());
    }

    /** Whether {@code f} is a photo known only by its path so far (no MediaStore row: nothing to share, edit or delete yet). */
    public static boolean unfiled(@Nullable com.scamera.app.gallery.files.MediaFile f) {
        return f != null && f.contentUri() != null && "file".equals(f.contentUri().getScheme()) && f.rowId() < 0;
    }

    /** The tap's hand-over, once (the gallery activity's first creation); null when the camera did not open this gallery. */
    @Nullable
    public static Open take() {
        synchronized (LOCK) {
            Open o = opened;
            opened = null;
            return o;
        }
    }

    /** The newest file the camera saved (null: none since the process began or since MediaStore's newest replaced it). */
    @Nullable
    public static Saved lastSaved() {
        synchronized (LOCK) {
            return lastSaved;
        }
    }

    /** The open viewer listens ({@code null}: stops). Main thread. */
    public static void listen(@Nullable Listener l) {
        synchronized (LOCK) {
            listener = l;
        }
    }

    @Nullable
    private static Listener current() {
        synchronized (LOCK) {
            return listener;
        }
    }

    /** Tests: as a new process. */
    @androidx.annotation.VisibleForTesting
    public static void reset() {
        synchronized (LOCK) {
            frame = null;
            lastSaved = null;
            storeLatest = null;
            opened = null;
            listener = null;
        }
    }
}
