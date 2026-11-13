package com.scamera.app.api;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.camera.data.CameraLensData;
import com.scamera.app.util.Log;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.scamera.app.settings.PreferenceKeys.Key.ALL_CAMERA_IDS_KEY;
import static com.scamera.app.settings.PreferenceKeys.Key.ALL_CAMERA_LENS_KEY;
import static com.scamera.app.settings.PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME;
import static com.scamera.app.settings.PreferenceKeys.Key.CAMERA_COUNT_KEY;

/**
 * The phone's photo lenses: the camera ids 0 .. 120 probed once (one lens per module, MONO / NIR / service streams and logical
 * cameras left out), each with its {@link CameraLensData}, kept in the cameras preference file; the ids the user added in the lens
 * discovery joined to them.
 */
public final class CameraManager2 {
    private static final String CAMERAS = CAMERAS_PREFERENCE_FILE_NAME.mValue;
    private static final String TAG = "CameraManager2";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** The highest camera id the scan probes. */
    private static final int LAST_PROBED_ID = 120;
    /** Probed ids with this bit (the 6th, value 32) are a vendor's auxiliary numbering, never a lens of their own. */
    private static final int AUX_ID_BIT = 1 << 5;

    private final Map<String, CameraLensData> lenses = new LinkedHashMap<>();
    private final SettingsManager settings;
    /** The lens ids in scan order, as stored. */
    private Set<String> ids = new LinkedHashSet<>();

    public CameraManager2(CameraManager cameraManager, SettingsManager settingsManager) {
        settings = settingsManager;
        // The scan is cached; a changed package spoof changes which cameras the service exposes (hidden lenses appear), so the
        // cached list would keep hiding them.
        final String spoof = com.scamera.app.capture.spoof.CameraPackageSpoof.signature(settings.getContext());
        if (!spoof.equals(settings.getString(CAMERAS, "spoof_signature", "off"))) {
            settings.set(CAMERAS, "spoof_signature", spoof);
            forgetScan("Package spoof changed (" + spoof + "): camera scan cache dropped");
        }
        // A scan from before the auxiliary sensors without lens / gain / exposure were left out (isAuxiliarySensor) runs once more:
        // aux3 vivo teleconverter / dummy profiles; aux4 (P120) vivo multispectral / pre-ISP sensors (the X300 Ultra's camera 7
        // took every camera down when opened) and depth-only cameras
        if (!"aux4".equals(settings.getString(CAMERAS, "aux_revision", ""))) {
            settings.set(CAMERAS, "aux_revision", "aux4");
            forgetScan("camera scan cache dropped (auxiliary sensors without lens / gain / exposure)");
        }
        // P66: a Pixel scanned before the sensor-crop modules were told apart (knownLens) scans once more.
        if (Build.BRAND.equalsIgnoreCase("google") && !"p66".equals(settings.getString(CAMERAS, "scan_revision", ""))) {
            settings.set(CAMERAS, "scan_revision", "p66");
            forgetScan("Pixel: camera scan cache dropped (P66 sensor-crop modules)");
        }
        if (settings.isSet(CAMERAS, ALL_CAMERA_IDS_KEY)) {
            readStored();
        } else {
            scan(cameraManager);
            store();
        }
        if (addUserCameras(cameraManager)) store();
    }

    private void forgetScan(String why) {
        settings.remove(CAMERAS, ALL_CAMERA_IDS_KEY);
        settings.remove(CAMERAS, ALL_CAMERA_LENS_KEY);
        settings.remove(CAMERAS, CAMERA_COUNT_KEY);
        Log.d(TAG, why);
    }

    /**
     * Lens discovery may expose vendor / physical ids the normal enumeration lacks: the user's choices join the list, so they
     * appear in the lens switcher after a restart. A service stream too (a vivo teleconverter profile, MONO / NIR): the user chose
     * it (owner, 2026-10-28: the X300 Ultra's camera 9 turns the picture for the telephoto extender).
     */
    private boolean addUserCameras(CameraManager cameraManager) {
        boolean added = false;
        for (String id : settings.getStringSet("default_scope", "user_camera_ids", new HashSet<>())) {
            if (lenses.containsKey(id)) continue;
            try {
                CameraCharacteristics characteristics;
                try {
                    characteristics = cameraManager.getCameraCharacteristics(id);
                } catch (Exception asListed) {
                    characteristics = cameraManager.getCameraCharacteristics(physicalPart(id));
                }
                final CameraLensData lens = lensData(id, characteristics);
                ids.add(id);
                lenses.put(id, lens);
                // against the scan's main lens of its side: the first lens of a map read from a stored set (any order) as the main
                // one made the 0.4x ultra-wide the main, the main 2.5x and the tele 6.1x (owner's log 2026-10-28, vivo V2562)
                lens.setZoomFactor(zoomAgainstMain(lens));
                added = true;
            } catch (Exception ignored) {
                // not a camera this phone opens
            }
        }
        return added;
    }

    /** "logical/physical" or "logical-physical": the physical part; the id itself otherwise. */
    private static String physicalPart(String id) {
        if (id.contains("/")) return id.split("/", 2)[1];
        if (id.contains("-")) return id.split("-", 2)[1];
        return id;
    }

    /** A lens added after the scan: its zoom against the lens of its side the scan made 1x (else the first of its side). */
    private float zoomAgainstMain(CameraLensData lens) {
        CameraLensData main = null;
        for (CameraLensData other : lenses.values()) {
            if (other == lens || other.getFacing() != lens.getFacing()) continue;
            if (Math.abs(other.getZoomFactor() - 1f) < 1e-3f) {
                main = other;
                break;
            }
            if (main == null) main = other;
        }
        return main == null ? 1f : lens.getCamera35mmFocalLength() / main.getCamera35mmFocalLength();
    }

    private void readStored() {
        ids = settings.getStringSet(CAMERAS, ALL_CAMERA_IDS_KEY, null);
        for (String json : settings.getStringSet(CAMERAS, ALL_CAMERA_LENS_KEY, null)) {
            final CameraLensData lens = GSON.fromJson(json, CameraLensData.class);
            lenses.put(lens.getCameraId(), lens);
        }
    }

    private void store() {
        settings.set(CAMERAS, CAMERA_COUNT_KEY, ids.size());
        settings.set(CAMERAS, ALL_CAMERA_IDS_KEY, ids);
        // a fresh set: the stored one would keep each lens's old line beside its new one (two zooms of one camera)
        final Set<String> json = new LinkedHashSet<>();
        for (CameraLensData lens : lenses.values()) json.add(GSON.toJson(lens));
        settings.set(CAMERAS, ALL_CAMERA_LENS_KEY, json);
    }

    /**
     * MONO / NIR sensors are auxiliary streams, not photo lenses: Hybrid and SCAM HDR merge Bayer RAW only, and configuring the
     * vivo X100 Ultra's MONO id 6 with the RAW reader killed its camera HAL ('Error configuring streams: Broken pipe', every id
     * 'unknown device' for 4.5 s; owner's log 2026-10-05).
     */
    public static boolean isAuxiliarySensor(CameraCharacteristics characteristics) {
        if (characteristics == null) return false;
        final Integer cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa != null && (cfa == 5 || cfa == 6)) return true; // SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO / _NIR (API 29)
        // vivo teleconverter / dummy profiles (owner, 2026-10-13: «X300 его не поддерживает»); scam_dev vivo_teleconverter 1 keeps them
        if (AuxiliarySensors.vivoProfileOnly(vivoCameraType(characteristics))
                && !com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vivo_teleconverter", false)) return true;
        if (AuxiliarySensors.notAPhotoCamera(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))) return true;
        final float[] focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        final android.util.Range<Integer> iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        final android.util.Range<Long> exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        return AuxiliarySensors.noLensGainOrExposure(focal != null && focal.length > 0 ? focal[0] : Float.NaN,
                iso != null ? iso.getUpper() : -1, exposure != null ? exposure.getUpper() : -1L);
    }

    /** com.vivo.chi.override.CameraType («Master», «Tele3xLong», «Tele3xLongExternal», «DUMMY»…), null when absent. */
    @Nullable
    public static String vivoCameraType(CameraCharacteristics characteristics) {
        try {
            final byte[] text = characteristics.get(new CameraCharacteristics.Key<>("com.vivo.chi.override.CameraType", byte[].class));
            if (text == null) return null;
            int end = 0;
            while (end < text.length && text[end] != 0) end++;
            return new String(text, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isAuxiliarySensor(CameraManager cameraManager, String id) {
        if (cameraManager == null || id == null) return false;
        try {
            return isAuxiliarySensor(cameraManager.getCameraCharacteristics(id));
        } catch (Exception direct) {
            final boolean compound = id.contains("/") || id.contains("-");
            try {
                return compound && isAuxiliarySensor(cameraManager.getCameraCharacteristics(physicalPart(id)));
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /**
     * The probe of ids 0 .. 120 (Samsung and Pixel list them as "0-N"). First the module of id 0's focal length is found and
     * taken first (it becomes the back 1x), then every other module; with none at all, ids 0 and 1 as they are. Each id's
     * characteristics are read once for both passes (they were read twice, 242 camera-service calls on a first start).
     */
    private void scan(CameraManager cameraManager) {
        final boolean compoundIds = Build.BRAND.equalsIgnoreCase("samsung") || Build.BRAND.equalsIgnoreCase("google");
        final boolean byField = Build.BRAND.equalsIgnoreCase("google");
        final CameraCharacteristics[] probed = new CameraCharacteristics[LAST_PROBED_ID + 1];
        for (int num = 0; num <= LAST_PROBED_ID; num++) {
            try {
                probed[num] = cameraManager.getCameraCharacteristics(String.valueOf(num));
                Log.d(TAG, "BitAnalyser:" + num + ":" + bitsLowFirst(num));
            } catch (Exception absent) {
                probed[num] = null;
            }
        }
        CameraLensData main = null;
        try {
            main = lensData("0", cameraManager.getCameraCharacteristics("0"));
        } catch (Exception noMain) {
            // no id 0: no main pass
        }
        if (main != null) {
            for (int num = 0; num <= LAST_PROBED_ID; num++)
                if (takeProbed(num, probed[num], compoundIds, byField, main.getCameraFocalLength())) break;
        }
        for (int num = 0; num <= LAST_PROBED_ID; num++) takeProbed(num, probed[num], compoundIds, byField, Float.NaN);
        if (ids.isEmpty()) {
            for (int num = 0; num < 2; num++) {
                try {
                    final String id = compoundIds ? "0-" + num : String.valueOf(num);
                    final CameraLensData lens = lensData(id, cameraManager.getCameraCharacteristics(String.valueOf(num)));
                    ids.add(id);
                    lenses.put(id, lens);
                } catch (Exception ignored) {
                    // not even ids 0 and 1
                }
            }
        }
        setZoomFactors();
    }

    /**
     * Adds probed id {@code num} when it is a photo lens of its own: not an auxiliary-numbered id, not a lens the list has, not a
     * logical camera, not a MONO / NIR / service stream; with a {@code focal} given, only a lens of that focal length.
     */
    private boolean takeProbed(int num, @Nullable CameraCharacteristics characteristics, boolean compoundIds, boolean byField,
                               float focal) {
        if (characteristics == null) return false;
        try {
            final String id = compoundIds ? "0-" + num : String.valueOf(num);
            final CameraLensData lens = lensData(id, characteristics);
            if (!Float.isNaN(focal) && focal != lens.getCameraFocalLength()) return false;
            final boolean logical = Build.VERSION.SDK_INT >= 28 && !characteristics.getPhysicalCameraIds().isEmpty();
            if ((num & AUX_ID_BIT) != 0 || knownLens(lens, byField) || logical || isAuxiliarySensor(characteristics)) return false;
            ids.add(id);
            lenses.put(id, lens);
            return true;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /**
     * Whether the scan already holds this lens. CameraLensData equals by facing, focal length, aperture and flash; P66: on a
     * Pixel (Pixel 7: camera 4 is the main sensor's 2x in-sensor crop, 6.81 mm f/1.85 like camera 2 on half the sensor width,
     * 50 mm instead of 25 mm equivalent; front 5 is a wider crop of the front sensor) an ID whose sensor area gives another
     * field of view is another module, so the 35 mm focal length must match too.
     */
    private boolean knownLens(CameraLensData lens, boolean byField) {
        if (!byField) return lenses.containsValue(lens);
        for (CameraLensData known : lenses.values())
            if (CameraLensData.sameLens(known, lens, true)) return true;
        return false;
    }

    private static CameraLensData lensData(String cameraId, CameraCharacteristics characteristics) {
        final CameraLensData lens = new CameraLensData(cameraId);
        lens.setFacing(characteristics.get(CameraCharacteristics.LENS_FACING));
        lens.setCameraFocalLength(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0]);
        lens.setCameraAperture(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)[0]);
        final float sensorWidth = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE).getWidth();
        lens.setCamera35mmFocalLength(36.0f / sensorWidth * lens.getCameraFocalLength());
        lens.setFlashSupported(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        return lens;
    }

    /** Each lens's zoom against the first lens of its side in scan order (the main one). */
    private void setZoomFactors() {
        CameraLensData mainBack = null, mainFront = null;
        for (CameraLensData lens : lenses.values()) {
            final int facing = lens.getFacing();
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                if (mainFront == null) mainFront = lens;
                lens.setZoomFactor(lens.getCamera35mmFocalLength() / mainFront.getCamera35mmFocalLength());
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                if (mainBack == null) mainBack = lens;
                lens.setZoomFactor(lens.getCamera35mmFocalLength() / mainBack.getCamera35mmFocalLength());
            }
        }
    }

    /** The scanned camera ids in scan order. */
    public String[] getCameraIdList() {
        final String[] list = ids.toArray(new String[0]);
        Log.d(TAG, "CameraCount:" + list.length + ", CameraIDs:" + java.util.Arrays.toString(list));
        return list;
    }

    /** The lens data by camera id. */
    public Map<String, CameraLensData> getCameraLensDataMap() {
        Log.d(TAG, "LensData : \n" + lenses);
        return lenses;
    }

    /** The id's 8 low bits, lowest first (the log of the probe). */
    private static String bitsLowFirst(int num) {
        final StringBuilder bits = new StringBuilder(8);
        for (int bit = 0; bit < 8; bit++) bits.append(((num >> bit) & 1) == 1 ? '1' : '0');
        return bits.toString();
    }
}
