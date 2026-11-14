package com.scamera.app.capture;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

/**
 * P695 (owner, 2026-11-14: «Не работает нормально ISZ 4x, исправь»; Xiaomi 15 Ultra, build d082f255, the «16.4x» module = the
 * periscope camera 5 with its SensorModeTable row 6 picked): the colour block of the periscope's 4x in-sensor-zoom rows.
 * <p>
 * The periscope is a 200 MP sensor of 16 cells a colour: row 0 is the whole 16320x12288 array, row 1 its 2x2 bin 8160x6144, and
 * rows 2..10 are all 4080x3072 - the full row's sides over 4. The table cannot tell those rows apart: a 4x4 bin (a plain Bayer) and
 * a 1x1 centre crop (the 16-cell mosaic itself) have the same size, so «full row / integer crop» is no rule (SensorModePick
 * declaredBlock declares only the full-size row). The HAL's own map of this camera's rows (aispsensormodesettings.json, PhysicalId 5;
 * research/scam_test_reports/xiaomi.md, «P4 ... periscope») does: binning [2, 13], quad 12.5M ISZ [4, 6, 15, 17], quad 50M [11, 22],
 * LBMF + quad [12, 23], DOL2 [8, 19] - the pairs match this table's 4:3 / 16:9 rows exactly (2 / 13, 11 / 22, 12 / 23 and 8 / 19,
 * 4 and 6 / 15 and 17) - and its smartae config names an ISZQUAD_4X4 mode for the camera.
 * <p>
 * Row 6 (4080x3072 at 29 fps; 16:9 twin 17) is the 4x ISZ: 16.4x on a 4.1x lens is a 4x crop of the 16320-wide array, and its
 * frames measured a Tetra block (owner's log 07:10:59: «block 4 (confident; tile votes b1/b2/b4 0/0/395 of 29463)»; the white balance
 * of the shot merged as a Quad measured 0.997 / 1.003, every 2x2 cell holding all four colours). The row is declared a Tetra block
 * from the row map ({@link SensorModePick.Pick#rowMap}); the other ISZ pair (4 / 15) is not declared: which of the two is the 2x one
 * is not in the map, its frames are measured as before. scam_dev xiaomi15_isz_block 0: no declaration.
 */
public final class XiaomiPeriscopeIsz {
    private XiaomiPeriscopeIsz() {}

    /** The 4x ISZ rows of the row map: 4:3 and its 16:9 twin. */
    static final int ROW_4X = 6, ROW_4X_WIDE = 17;

    /**
     * The periscope's table: the whole 16320x12288 array, its 2x2 bin, the map's binning row 2 at 4080x3072 and the 4x rows 4080x3072
     * / 4080x2304 (the Xiaomi 17 Ultra's 75 mm, a sensor of the same array, has a 2x2 bin at row 2: not this table).
     */
    static boolean periscopeTable(@Nullable int[] table) {
        return size(table, 0, 16320, 12288) && size(table, 1, 8160, 6144) && size(table, 2, 4080, 3072)
                && size(table, ROW_4X, 4080, 3072) && size(table, ROW_4X_WIDE, 4080, 2304);
    }

    private static boolean size(@Nullable int[] table, int row, int w, int h) {
        final int[] e = SensorModeBand.modeEntry(table, row);
        return e != null && e[0] == w && e[1] == h;
    }

    /** The block the row map gives {@code row} on this phone ({@code phone}: the Xiaomi 15 Ultra), 0 for none. */
    static int rowBlock(boolean phone, @Nullable int[] table, int row) {
        return phone && (row == ROW_4X || row == ROW_4X_WIDE) && periscopeTable(table) ? 4 : 0;
    }

    private static volatile String sSaid;

    /** SensorModePick.active: the pick of a 4x ISZ row declared a Tetra block by the row map; any other pick untouched. */
    @Nullable
    static SensorModePick.Pick mark(@Nullable String camera, @Nullable SensorModePick.Pick pick, @Nullable int[] table) {
        if (pick == null || pick.block > 0) return pick;
        final int block = rowBlock(XiaomiDcg.device() && PreferenceKeys.scamDevSwitch("xiaomi15_isz_block", true), table, pick.mode);
        if (block == 0) return pick;
        final String key = camera + "|" + pick.mode;
        if (!key.equals(sSaid)) {
            sSaid = key;
            Log.i("SCAM_CAPTURE", "XIAOMI 15 ULTRA 4x ISZ camera " + camera + ": row " + pick.mode + " is a 4x ISZ row of the HAL's map"
                    + " (a 1x1 crop of the 16-cell array): declared a Tetra block; the frames' confident measurements still decide (P695)");
        }
        return pick.withRowMapBlock(block);
    }
}
