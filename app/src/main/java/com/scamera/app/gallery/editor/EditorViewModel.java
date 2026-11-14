package com.scamera.app.gallery.editor;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.scamera.app.util.Log;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * P351: what outlives a configuration change of the editor: the decoded source, the undo history and the running export
 * (its progress and result). The state itself also goes into the saved instance state as JSON (process death).
 */
public final class EditorViewModel extends AndroidViewModel {
    private static final String TAG = "EditorViewModel";
    /** P367: how long the export waits for the zoom's reader to close (a RAW open in progress finishes first). */
    static final long READY_WAIT_S = 30;

    /** null while decoding; the error text when it failed. */
    final MutableLiveData<EditSource> source = new MutableLiveData<>();
    final MutableLiveData<String> error = new MutableLiveData<>();
    final MutableLiveData<Float> exportProgress = new MutableLiveData<>();
    final MutableLiveData<EditExporter.Result> exported = new MutableLiveData<>();
    final MutableLiveData<String> exportError = new MutableLiveData<>();
    @Nullable EditHistory history;
    EditState state = new EditState();
    /** The tone before a style was picked (the style's 0 %). */
    @Nullable EditState styleBase;
    /** P619: the «LUT» tab's tables, loaded off the main thread, and the last ones delivered (the picture's, the copy's start). */
    final EditLook.Loader lookLoader = new EditLook.Loader();
    @Nullable volatile EditLook.Tables lookTables;
    /** P619: how long the copy waits for a film's reference table the picture is still waiting for. */
    static final long LOOK_WAIT_MS = 30_000;
    private volatile boolean loading;
    /**
     * The decoded source this ViewModel owns, set before it is posted to {@link #source}; {@code cleared} after onCleared;
     * {@code exporting} while an export reads the source. Guarded by {@link #lock}: the source is closed exactly once - by
     * onCleared, by the export that outlived it, or by the decode that finished after it (the full-size DNG copy in the cache
     * and LibRaw's native image stayed until the process died).
     */
    private final Object lock = new Object();
    @Nullable private EditSource opened;
    private boolean cleared, exporting;

    /**
     * P665 «Проявка»: the camera's develop of the photo (a DNG of ours with its record; null: none offered), the last developed
     * picture (posted when a develop ends) and the develop's status line (null when idle). Develops run one at a time on their own
     * thread; a request the next one overtook before it started is skipped.
     */
    @Nullable volatile EditDevelop develop;
    final MutableLiveData<EditSource> developed = new MutableLiveData<>();
    final MutableLiveData<String> developStatus = new MutableLiveData<>();
    private final java.util.concurrent.ExecutorService developer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EditorDevelop");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    @Nullable private volatile String developWanted;
    /** The changes' key the posted picture was developed with. */
    @Nullable volatile String developedKey;

    public EditorViewModel(@NonNull Application app) {
        super(app);
    }

    void load(Uri uri, String name, @Nullable String mime, @Nullable String path) {
        synchronized (lock) {
            // opened is set before loading is cleared: a recreated editor's load while the first source is still on its way to
            // the main thread (source still null) decoded a second copy, and the first was never closed
            if (loading || opened != null || cleared) return;
            loading = true;
        }
        Thread t = new Thread(() -> {
            try {
                EditSource s = EditSource.open(getApplication(), uri, name, mime);
                s.absolutePath = path;
                develop = EditDevelop.open(s); // P665: our DNG's develop record, if it has one (P696: else one from its tags)
                boolean drop;
                synchronized (lock) {
                    drop = cleared;
                    if (!drop) opened = s;
                }
                if (drop) s.close(); // the editor was left while decoding
                else source.postValue(s);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "open " + name + ": " + e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                loading = false;
            }
        }, "EditorDecode");
        t.start();
    }

    /**
     * P665: develops the preview with {@code s}'s camera settings unless the picture posted or the develop running is already
     * that one. The new picture comes in {@link #developed}.
     */
    void requestDevelop(EditState s) {
        final EditDevelop d = develop;
        final EditSource raw = source.getValue();
        if (d == null || raw == null || !s.develop) return;
        final java.util.TreeMap<String, Object> changes = new java.util.TreeMap<>(s.devChanges);
        final String key = EditDevelop.key(changes);
        if (key.equals(developWanted)) return;
        developWanted = key;
        developStatus.setValue(com.scamera.app.util.Lang.t("Проявляю конвейером камеры…", "Developing with the camera's pipeline…"));
        developer.execute(() -> {
            if (!key.equals(developWanted)) return; // overtaken
            try {
                final android.graphics.Bitmap b = d.preview(changes);
                final double k = d.scale();
                final EditSource pic = EditSource.developed(raw, b, (int) Math.round(b.getWidth() * k), (int) Math.round(b.getHeight() * k),
                        () -> d.full(changes));
                if (!key.equals(developWanted)) return;
                developedKey = key;
                developed.postValue(pic);
                developStatus.postValue(null);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "develop: " + e);
                if (key.equals(developWanted)) developWanted = null; // the same settings may be tried again
                developStatus.postValue(com.scamera.app.util.Lang.t("Проявка не удалась: ", "Develop failed: ")
                        + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    boolean exporting() {
        synchronized (lock) {
            return exporting;
        }
    }

    /**
     * The export on its own thread; P367: after {@code ready} (the editor zoom's reader closing, EditorView.closeFullRes) is
     * done - its memory is the export's - or {@link #READY_WAIT_S} passed.
     */
    void export(EditState s, @Nullable Future<?> ready) {
        export(s, ready, source.getValue());
    }

    /** P665: the export of {@code src} (the photo, or its developed picture with the copy's full-size develop). */
    void export(EditState s, @Nullable Future<?> ready, @Nullable EditSource src) {
        synchronized (lock) {
            if (src == null || exporting || cleared) return;
            exporting = true;
        }
        exportProgress.setValue(0f);
        final EditState snapshot = s.copy();
        final EditLook.Tables have = lookTables;
        Thread t = new Thread(() -> {
            try {
                if (ready != null) {
                    try {
                        ready.get(READY_WAIT_S, TimeUnit.SECONDS);
                    } catch (TimeoutException | ExecutionException e) {
                        Log.w(TAG, "the zoom's reader did not close in time: " + e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                // P619: the copy takes the look the picture ends at (the film's reference table, when it is coming)
                EditLook.Tables look = EditLook.on(snapshot) ? EditLook.blocking(getApplication(), snapshot, have, LOOK_WAIT_MS) : null;
                EditExporter.Result r = EditExporter.export(getApplication(), src, snapshot, look, exportProgress::postValue);
                exported.postValue(r);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "export: " + e);
                exportError.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                boolean close;
                EditSource own;
                synchronized (lock) {
                    exporting = false;
                    close = cleared;
                    own = opened;
                }
                // the editor was cleared while this export read the source (P665: the photo's, a developed picture owns nothing)
                if (close && own != null) own.close();
            }
        }, "EditorExport");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    @Override
    protected void onCleared() {
        lookLoader.shutdown();
        developWanted = null;
        developer.shutdown();
        EditDevelop d = develop;
        if (d != null) d.release();
        EditSource s;
        synchronized (lock) {
            cleared = true;
            s = exporting ? null : opened; // a running export closes it when it ends
        }
        if (s != null) s.close();
        super.onCleared();
    }
}
