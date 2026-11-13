package com.scamera.app.capture;

import android.hardware.camera2.CameraCharacteristics;

import androidx.annotation.Nullable;

import com.scamera.app.settings.ModuleRegistry;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

/**
 * P641 (owner, 2026-11-12: «придумай как сделать 4x ISZ полностью рабочим без рута на 17U», then «нам нужны реальные 4x isz, а не
 * просто вырезка с 200мп»): the 4x in-sensor zoom rows of the Xiaomi 17 Ultra's 75 mm camera (camera 4, Samsung S5KHPE, 16 cells a
 * colour). What the owner's device report (firmware OS3.0.312.0.WPACNXM) and the 4x ISZ kit's description say:
 * <ul>
 * <li>the camera's SensorModeTable has two rows read 1x1: row 0, the whole 16320x12288 array at 7 fps, and row 12, 4080x3072 at 30
 * fps - the 4x ISZ the sensor module declares (capability INTERNAL, no remosaic entry in the vendor's row map, which names only the
 * 2x ISZ rows 9 / 20). The kit's authors found that the stock module's row 12 cannot stream (a script pointer into the middle of
 * another script, a descriptor table the init program never builds: the sensor sends lines of the full array width and the frame
 * never ends);</li>
 * <li>every other row is binned: 1 / 2 / 13 the 2x2-binned 50 MP (24 fps - a 1x1 readout of 6144 lines takes 51 ms), 3 / 14 the
 * vendor's «QUADFULLCROP» of that 50 MP frame (ratio 1.5 = 8160 / 5440), 9 / 20 its 12.5 MP centre (the 2x ISZ the HAL already
 * runs by itself in the logical session, P72), 33 the 8K video crop of it;</li>
 * <li>nothing an app can send repairs row 12: the fault is in the sensor's register program inside the module file under /odm, a
 * read-only verified partition, and an app has no way to write sensor registers. So on the stock firmware there is no real
 * 4x-density row that streams; the kit's module file (rows 12, 26, 29 rebuilt; installed with root) is the only one.</li>
 * </ul>
 * This class is the kit's rows for a phone that has them: with the kit installed the table shows rows 26 and 29 as 4080x3072 at 30
 * fps (stock: 4080x2560). A row picked on the module («Режим сенсора») then runs as the kit describes it - rows 26 / 29 without
 * remosaic (the sensor's 16-cell mosaic: declared a Tetra block, RAW only; the frames' own measurement still decides), row 12 with
 * the sensor's remosaic (a Bayer: measured). Row 12 picked on the stock module is refused with a message instead of a hung
 * session (scam_dev xiaomi17_row12 1 lets it through). A session with a picked row, like one with the dual-gain switch on, opens
 * the camera alone ({@link #directReason}).
 */
public final class XiaomiTeleIsz {
    private XiaomiTeleIsz() {}

    static final String CAMERA = "4";
    /** The kit's rows: the sensor's remosaic (a Bayer), and the two without it (the 16-cell mosaic). */
    static final int ROW_REMOSAIC = 12, ROW_RAW_A = 26, ROW_RAW_B = 29;

    /** The sensor module is the kit's: rows 26 and 29 are 4080x3072 at 30 fps (the stock ones are 4080x2560 at 30 / 60). */
    static boolean kitTable(@Nullable int[] table) {
        return kitRow(SensorModeBand.modeEntry(table, ROW_RAW_A)) && kitRow(SensorModeBand.modeEntry(table, ROW_RAW_B));
    }

    private static boolean kitRow(@Nullable int[] e) {
        return e != null && e[0] == 4080 && e[1] == 3072 && e[2] == 30;
    }

    /** One of the 4x rows. */
    static boolean iszRow(int row) {
        return row == ROW_REMOSAIC || row == ROW_RAW_A || row == ROW_RAW_B;
    }

    /** What a picked row of the 75 mm is. */
    enum Verdict {
        /** Not one of the 4x rows (or not this camera): the pick as it is. */
        OTHER,
        /** Row 12 of the stock module: it cannot stream. */
        STOCK_BROKEN,
        /** The kit's row 12: the sensor's remosaic, a Bayer frame. */
        KIT_BAYER,
        /** The kit's rows 26 / 29: the 16-cell mosaic. */
        KIT_TETRA
    }

    static Verdict verdict(boolean phone, @Nullable String camera, @Nullable int[] table, int row) {
        if (!phone || !CAMERA.equals(camera) || !iszRow(row)) return Verdict.OTHER;
        final boolean kit = kitTable(table);
        if (row == ROW_REMOSAIC) return kit ? Verdict.KIT_BAYER : Verdict.STOCK_BROKEN;
        return kit ? Verdict.KIT_TETRA : Verdict.OTHER; // the stock rows 26 / 29 are 16:10 binned rows
    }

    private static volatile String sSaid;

    /**
     * SensorModePick.active: the pick of the 75 mm's 4x rows - the kit's raw rows declared a Tetra block in a RAW-only session, row
     * 12 of the stock module refused (null: the module runs as Auto, a message says why), anything else untouched.
     */
    @Nullable
    static SensorModePick.Pick mark(@Nullable String camera, @Nullable SensorModePick.Pick pick, @Nullable int[] table) {
        if (pick == null) return null;
        final Verdict v = verdict(XiaomiDualGain.device(), camera, table, pick.mode);
        if (v == Verdict.OTHER) return pick;
        final String key = camera + "|" + pick.mode + "|" + v;
        final boolean news = !key.equals(sSaid);
        sSaid = key;
        if (v == Verdict.STOCK_BROKEN) {
            if (PreferenceKeys.scamDevSwitch("xiaomi17_row12", false)) return pick;
            if (news) {
                Log.w("SCAM_CAPTURE", "XIAOMI 4x ISZ camera 4: row 12 picked on the stock sensor module (rows 26 / 29 are not the kit's"
                        + " 4080x3072): the row cannot stream there (the kit's finding: the frame never ends), the module runs as Auto;"
                        + " scam_dev xiaomi17_row12 1 sends it anyway (P641)");
                com.scamera.app.util.UiMessages.info(Lang.t(
                        "Режим сенсора 12 (4x ISZ) на стоковом модуле сенсора не работает: кадр не заканчивается. Нужен модуль из набора"
                                + " 4x ISZ (root). Модуль работает как «Авто».",
                        "Sensor mode 12 (4x ISZ) does not work with the stock sensor module: the frame never ends. It needs the 4x ISZ"
                                + " kit's module (root). The module runs as Auto."));
            }
            return null;
        }
        if (news) Log.i("SCAM_CAPTURE", "XIAOMI 4x ISZ camera 4: the kit's row " + pick.mode + " (" + (v == Verdict.KIT_TETRA
                ? "no remosaic: the 16-cell mosaic, declared a Tetra block, RAW only" : "the sensor's remosaic: a Bayer frame, the block measured")
                + "), a 1x1 centre crop of the array = 4x of the 75 mm; the frames' own measurement decides the merge (P641)");
        return v == Verdict.KIT_TETRA ? new SensorModePick.Pick(pick.mode, pick.width, pick.height, pick.fullRes, 4, pick.route, true) : pick;
    }

    /**
     * Why a session of this camera opens it alone instead of through the logical SAT camera (P41b), null when it does not: the
     * dual-gain switch is on and the camera has such a row, or the active module has a picked sensor row of this camera.
     */
    @Nullable
    static String directReason(@Nullable String physicalId, @Nullable CameraCharacteristics c) {
        if (physicalId == null || !XiaomiDualGain.device()) return null;
        try {
            if (Dcg14Mode.on() && !XiaomiDualGain.offered(physicalId, c).isEmpty())
                return "the " + Dcg14Mode.name(Dcg14Mode.mode()) + " switch asks the sensor for its dual-gain row";
            final String slot = ModuleRegistry.active();
            final int row = physicalId.equals(ModuleRegistry.camera(slot)) ? SensorModePick.picked(slot) : -1;
            return row >= 0 ? "the module's picked sensor mode row " + row : null;
        } catch (RuntimeException notReady) {
            return null;
        }
    }
}
