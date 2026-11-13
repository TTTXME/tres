package com.scamera.app.ui.settings.custompreferences;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The private folder of square PNG lookup tables a {@link TunablePngPreference} picks from: listing, import with size
 * checks, removal. Messages are in the UI language; a null result means success.
 */
final class PngLibrary {
    private static final String TAG = "PngLibrary";
    static final String FOLDER = "tunable_pngs";
    /** Edge sizes accepted when the row names none (the usual square LUT layouts). */
    private static final int[] USUAL_EDGES = {512, 1000, 1728, 2744, 4096};

    private PngLibrary() {}

    static File folder(Context context) {
        return new File(context.getFilesDir(), FOLDER);
    }

    private static boolean isPng(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    /** File names in the folder, sorted; empty when the folder does not exist yet. */
    static List<String> names(Context context) {
        List<String> out = new ArrayList<>();
        String[] all = folder(context).list();
        if (all != null) for (String n : all) if (isPng(n)) out.add(n);
        out.sort(null);
        return out;
    }

    static void delete(Context context, String name) {
        File f = new File(folder(context), name);
        if (f.exists() && !f.delete()) Log.w(TAG, "could not delete " + name);
    }

    /** Edge sizes a row accepts, ascending. */
    static int[] edges(int[] allowed) {
        int[] e = allowed == null || allowed.length == 0 ? USUAL_EDGES.clone() : allowed.clone();
        Arrays.sort(e);
        return e;
    }

    /** Copies the picked image into the folder as {@code name}; returns null or the reason it was refused. */
    static String store(Context context, Uri source, String name, int[] allowed) {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException e) {
            Log.e(TAG, "image bounds unreadable", e);
            return Lang.t(context, "не удалось прочитать картинку: ", "Failed to read image: ") + e.getMessage();
        }
        int w = bounds.outWidth, h = bounds.outHeight;
        if (w <= 0 || h <= 0) return Lang.t(context, "это не картинка", "Invalid image file");
        String size = w + "x" + h;
        if (w != h) return Lang.t(context, "PNG должен быть квадратным. Сейчас: ", "PNG must be square. Current size: ") + size;
        int[] ok = edges(allowed);
        if (Arrays.binarySearch(ok, w) < 0) {
            StringBuilder list = new StringBuilder();
            for (int edge : ok) list.append(list.length() == 0 ? "" : ", ").append(edge).append('x').append(edge);
            return Lang.t(context, "размер PNG " + size + " не подходит. Подходят: " + list,
                    "PNG size " + size + " is not supported. Allowed sizes: " + list);
        }
        File dir = folder(context);
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "could not create " + dir);
        try (InputStream in = resolver.openInputStream(source);
             OutputStream out = new FileOutputStream(new File(dir, name))) {
            if (in == null) return Lang.t(context, "не удалось открыть файл", "Failed to open input stream");
            byte[] chunk = new byte[16 * 1024];
            for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
        } catch (IOException e) {
            Log.e(TAG, "copy failed", e);
            return Lang.t(context, "не удалось скопировать файл: ", "Failed to copy file: ") + e.getMessage();
        }
        return null;
    }

    /** A file name for the picked document: its display name, else the document id or path tail, always ending in .png. */
    static String nameFor(Context context, Uri uri) {
        String name = displayName(context, uri);
        if (name == null) name = documentTail(context, uri);
        if (name == null) name = uri.getLastPathSegment();
        if (name != null) {
            try {
                name = URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {
                // keep the encoded form
            }
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
            // Media providers name a document by its row number ("1000060931.png"): ask MediaStore for the real one.
            if (looksLikeRowNumber(name)) {
                String real = mediaStoreName(context, uri);
                if (real != null && !real.isEmpty()) name = real;
            }
        }
        if (name == null || name.isEmpty()) name = "image.png";
        return isPng(name) ? name : name + ".png";
    }

    private static String displayName(Context context, Uri uri) {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return null;
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            return col < 0 ? null : c.getString(col);
        }
    }

    /** "primary:Download/x.png" or "raw:/path/x.png" → the part after the last colon. */
    private static String documentTail(Context context, Uri uri) {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null;
        String id = DocumentsContract.getDocumentId(uri);
        if (id == null) return null;
        int colon = id.lastIndexOf(':');
        return colon >= 0 && colon < id.length() - 1 ? id.substring(colon + 1) : id;
    }

    static boolean looksLikeRowNumber(String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        // Some providers put one letter (a/v) before or after the number.
        return stem.replaceAll("^[av]?", "").replaceAll("[av]?$", "").matches("\\d+");
    }

    private static String mediaStoreName(Context context, Uri uri) {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null;
        String id = DocumentsContract.getDocumentId(uri);
        String[] kindAndRow = id == null ? new String[0] : id.split(":");
        if (kindAndRow.length < 2) return null;
        Uri table;
        if ("image".equals(kindAndRow[0])) table = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        else if ("video".equals(kindAndRow[0])) table = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        else return null;
        String column = MediaStore.MediaColumns.DISPLAY_NAME;
        try (Cursor c = context.getContentResolver().query(table, new String[]{column},
                MediaStore.MediaColumns._ID + "=?", new String[]{kindAndRow[1]}, null)) {
            if (c != null && c.moveToFirst() && c.getColumnIndex(column) >= 0) return c.getString(c.getColumnIndex(column));
        } catch (Exception e) {
            Log.w(TAG, "MediaStore name lookup failed", e);
        }
        return null;
    }
}
