package com.scamera.app.util;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import com.anggrayudi.storage.file.DocumentFileCompat;
import com.anggrayudi.storage.file.DocumentFileType;
import com.anggrayudi.storage.file.FileFullPath;
import com.anggrayudi.storage.file.StorageId;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper around SimpleStorage library for folder access and path resolution.
 * Uses DCIM/SCAMERA as the target path; persists via SAF so export/import and native writing work.
 *
 * <p>Call {@link #init(Context)} once from {@code ScameraApp.initModules()} so all
 * methods can operate without a per-call Context parameter.
 *
 * @see <a href="https://github.com/anggrayudi/SimpleStorage">SimpleStorage</a>
 */
public final class SimpleStorageHelper {
    /** The folder access is asked for (the picker opens there when it can), and the app's folder in it. */
    public static final String DCIM_DIR = "DCIM";

    /** The app's folder: RAW files (Raw/), logs (Log/). */
    public static final String SCAMERA_RELATIVE_PATH = "DCIM/SCAMERA";

    private static final String TAG = "SimpleStorageHelper";
    /** The application's context (init), for the calls that take none. */
    private static Context appContext;

    /** Keeps the application's context; called once from {@code ScameraApp.initModules()}. */
    public static void init(Context any) {
        appContext = any.getApplicationContext();
    }

    /** True if the app has SAF access to DCIM (a grant of DCIM, of a folder inside it, or of a whole storage root). */
    public static boolean hasStorageAccess(Context caller) {
        if (caller == null) return false;
        try {
            return dcimOf(accessiblePaths(caller)) != null;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "hasStorageAccess: " + t.getMessage());
            return false;
        }
    }

    private static java.util.List<String> accessiblePaths(Context context) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Map<String, Set<String>> pathMap = DocumentFileCompat.getAccessibleAbsolutePaths(context);
        if (pathMap == null) return out;
        for (Set<String> paths : pathMap.values()) if (paths != null) for (String p : paths) if (p != null) out.add(p);
        return out;
    }

    private static final java.util.regex.Pattern STORAGE_DCIM =
            java.util.regex.Pattern.compile("^(/storage/(?:emulated/[0-9]+|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}))(?:/(DCIM)(?:/.*)?)?/?$");

    /**
     * P302 (owner, 2026-10-20: «/storage/emulated/0/Download/SCAMERA/DCIM/Camera/ и …/Download/SCAMERA/DCIM/SCAMERA/Raw/ перенести
     * обратно в /storage/emulated/0/DCIM/»): the DCIM folder ("/storage/emulated/0/DCIM/") of the lasting grants - of DCIM
     * itself, of a folder inside it or of a whole storage root (the primary storage first); null for any other. The first grant
     * of all was taken before, whatever it was: the Download/SCAMERA grant of P191 made "Download/SCAMERA/DCIM/" the photos' place
     * and counted as DCIM access.
     */
    static String dcimOf(Iterable<String> paths) {
        String other = null;
        for (String path : paths) {
            if (path == null) continue;
            java.util.regex.Matcher m = STORAGE_DCIM.matcher(path);
            if (!m.matches()) continue;
            String dcim = m.group(1) + "/DCIM/";
            if (m.group(1).startsWith("/storage/emulated/")) return dcim;
            if (other == null) other = dcim;
        }
        return other;
    }

    /**
     * Updates FileManager static paths from SAF accessible paths: the DCIM of a grant ({@link #dcimOf}), else the primary
     * storage's DCIM (the File API writes photos and DNGs there itself).
     */
    public static void updateFileManagerPaths(Context context) {
        if (context == null) return;
        String dcimPath = null;
        try {
            dcimPath = dcimOf(accessiblePaths(context));
        } catch (Throwable t) {
            Log.e(TAG, "updateFileManagerPaths: " + t.getMessage());
        }
        if (dcimPath == null) dcimPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/";
        FileManager.sDCIM_CAMERA = new File(dcimPath + "Camera");
        FileManager.sSCAMERA_DIR = new File(dcimPath + "SCAMERA");
        FileManager.sRAW_DIR = new File(dcimPath + "SCAMERA/Raw");
        Log.d(TAG, "Updated paths: DCIM_CAMERA=" + FileManager.sDCIM_CAMERA);
    }

    /** Builds the initial path for RequestStorageAccessContract so the picker opens at DCIM. */
    public static FileFullPath createDcimInitialPath(Context context) {
        return new FileFullPath(context, StorageId.PRIMARY, DCIM_DIR);
    }

}
