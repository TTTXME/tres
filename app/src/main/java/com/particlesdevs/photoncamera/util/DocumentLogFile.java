package com.particlesdevs.photoncamera.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** App-owned non-media files in public Documents on Android 10+. */
final class DocumentLogFile {
    static final String RELATIVE = "Documents/SCAMERA/Logs/";
    private static final Uri COLLECTION = MediaStore.Files.getContentUri("external_primary");
    private final ContentResolver resolver;
    private final String stem;
    private final String alternate;
    private BufferedWriter writer;
    private Uri uri;
    private String name;
    private long size;

    DocumentLogFile(Context context, String stem) {
        resolver = context.getContentResolver();
        this.stem = stem;
        alternate = RotatingLogFile.installStem(context, stem);
    }

    Uri uri() { return uri; }
    String name() { return name; }
    long size() { return size; }

    boolean find() {
        return (alternate != null && lookup(alternate + ".txt") && readable())
                || (lookup(stem + ".txt") && readable());
    }

    private boolean readable() {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (IOException | RuntimeException e) { return false; }
    }

    void removeExpired(long now) {
        try (Cursor cursor = resolver.query(COLLECTION,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DATE_MODIFIED + "<?",
                new String[]{RELATIVE, Long.toString(now / 1000 - 10L * 24 * 60 * 60)}, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                if (!cursor.getString(1).matches("SCAMERA_Log_[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[.][12])?[.]txt")) continue;
                try { resolver.delete(android.content.ContentUris.withAppendedId(COLLECTION, cursor.getLong(0)), null, null); }
                catch (RuntimeException ignored) { /* Earlier installs may own their rows. */ }
            }
        } catch (RuntimeException ignored) { }
    }

    private boolean lookup(String filename) {
        try (Cursor cursor = resolver.query(COLLECTION,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{RELATIVE, filename}, MediaStore.MediaColumns._ID + " DESC")) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            uri = android.content.ContentUris.withAppendedId(COLLECTION, cursor.getLong(0));
            name = cursor.getString(1);
            size = cursor.getLong(2);
            return true;
        } catch (RuntimeException e) { return false; }
    }

    void open() throws IOException {
        if (writer != null) return;
        IOException failure;
        if (alternate != null && lookup(alternate + ".txt")) {
            try { open(alternate); return; }
            catch (IOException e) { close(); }
        }
        try { open(stem); return; }
        catch (IOException e) { failure = e; close(); }
        if (alternate != null && !alternate.equals(stem)) { open(alternate); return; }
        throw failure;
    }

    private void open(String base) throws IOException {
        try {
            if (!lookup(base + ".txt")) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, base + ".txt");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE);
                uri = resolver.insert(COLLECTION, values);
                if (uri == null) throw new IOException("Cannot create public log");
                // MediaStore can disambiguate a name belonging to an earlier install.
                try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IOException("Cannot resolve public log name");
                    name = cursor.getString(0);
                }
                if (!(base + ".txt").equals(name)) {
                    // This row is new and still empty; do not keep an undiscoverable provider suffix.
                    resolver.delete(uri, null, null);
                    throw new IOException("Public log name belongs to an earlier install");
                }
                size = 0;
            }
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "wa");
            if (descriptor == null) throw new IOException("Cannot append public log");
            long actualSize = descriptor.getStatSize();
            if (actualSize >= 0) size = actualSize;
            if (size >= RotatingLogFile.CAP_BYTES) {
                descriptor.close();
                rotate(base);
                return;
            }
            writer = new BufferedWriter(new OutputStreamWriter(
                    new ParcelFileDescriptor.AutoCloseOutputStream(descriptor), StandardCharsets.UTF_8), 8192);
        } catch (RuntimeException e) { throw new IOException("Public log storage unavailable", e); }
    }

    private void rotate(String base) throws IOException {
        Uri previous = uri;
        String previousName = name;
        for (int g = RotatingLogFile.GENERATIONS - 1; g >= 1; g--) {
            if (!lookup(RotatingLogFile.name(base, ".txt", g))) continue;
            if (g == RotatingLogFile.GENERATIONS - 1) resolver.delete(uri, null, null);
            else rename(uri, RotatingLogFile.name(base, ".txt", g + 1));
        }
        // Never truncate an old file if a storage provider refuses the rotation.
        rename(previous, base + ".1.txt");
        uri = null;
        name = previousName;
        size = 0;
        open(base);
    }

    private void rename(Uri target, String filename) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        if (resolver.update(target, values, null, null) != 1) throw new IOException("Log rotation refused");
    }

    void write(String text) throws IOException {
        open();
        writer.write(text);
        size += RotatingLogFile.utf8Length(text);
    }

    void flush() throws IOException { if (writer != null) writer.flush(); }

    void close() {
        BufferedWriter output = writer;
        writer = null;
        if (output != null) try { output.close(); } catch (IOException ignored) { }
    }
}
