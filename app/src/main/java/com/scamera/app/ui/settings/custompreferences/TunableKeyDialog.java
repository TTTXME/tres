package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.scamera.app.api.VendorTagUtils;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.TunableKeyManager;
import com.scamera.app.ui.settings.SettingsStyle;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Creating, editing and deleting a {@link VendorTagUtils.TunableKey}: a bottom sheet in the style of the other value editors
 * (P137). The key's type is an explicit choice: CaptureRequest (every request) or Session (the session parameters only - the
 * vivo X300 Ultra's vivo.control.forceSensorMode, P122). Pass slot = -1 to create a new key.
 */
public class TunableKeyDialog {
    static final String[] VALUE_TYPES = {"Integer", "Long", "Float", "Double", "Byte", "Short", "int[]", "byte[]", "long[]", "float[]", "String"};
    static final String TYPE_REQUEST = "CaptureRequest";

    private TunableKeyDialog() {}

    public static BottomSheetDialog show(Context c, String sensorId, int slot, Runnable onDone) {
        if (c == null || sensorId == null) return null;
        List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(c, sensorId);
        VendorTagUtils.TunableKey existing = (slot >= 0 && slot < keys.size()) ? keys.get(slot) : null;
        boolean adding = existing == null;
        int accent = SettingsStyle.accent(c);

        LinearLayout body = SettingsStyle.sheetBody(c, adding ? Lang.t(c, "Новый vendor tag", "New vendor tag") : "Vendor tag", null);
        body.addView(label(c, Lang.t(c, "Имя ключа", "Key name")));
        EditText name = field(c, existing != null ? existing.name : "", "vendor_name");
        name.setHint("vivo.control.forceSensorMode");
        body.addView(name, fieldParams(c));

        // the key's type: where it goes; an old CaptureResult key keeps its type as a third choice
        List<String> kinds = new ArrayList<>();
        kinds.add(TYPE_REQUEST);
        kinds.add(VendorTagUtils.TunableKey.TYPE_SESSION);
        String type = existing != null && existing.type != null ? existing.type : TYPE_REQUEST;
        if (!kinds.contains(type)) kinds.add(type);
        final String[] chosenType = {type};
        body.addView(label(c, Lang.t(c, "Тип ключа", "Key type")));
        LinearLayout typeRow = new LinearLayout(c);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> pills = new ArrayList<>();
        for (String t : kinds) {
            TextView pill = SettingsStyle.text(c, t, 15, SettingsStyle.ROW_TITLE);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 13), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 13));
            pill.setTag("vendor_type_" + t);
            // P592: what the kind means is the pill's long press (and TalkBack's), not a line under the pills
            pill.setContentDescription(t + ", " + typeHint(c, t));
            pill.setOnLongClickListener(v -> {
                com.scamera.app.ui.settings.SettingHelp.show(c, t, typeHint(c, t));
                return true;
            });
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -2, 1);
            if (!pills.isEmpty()) pp.leftMargin = SettingsStyle.dp(c, 8);
            typeRow.addView(pill, pp);
            pills.add(pill);
        }
        Runnable paintTypes = () -> {
            for (TextView pill : pills) {
                boolean on = pill.getText().toString().equals(chosenType[0]);
                pill.setSelected(on);
                pill.setTextColor(on ? accent : SettingsStyle.ROW_TITLE);
                pill.setTypeface(com.scamera.app.ui.style.Fonts.of(pill.getContext(), on ? 700 : 400));
                pill.setBackground(SettingsStyle.shape(c, on ? SettingsStyle.pillFill(accent) : SettingsStyle.ROW,
                        on ? 0 : SettingsStyle.HAIRLINE, 14));
            }
        };
        for (TextView pill : pills) pill.setOnClickListener(v -> { chosenType[0] = pill.getText().toString(); paintTypes.run(); });
        paintTypes.run();
        body.addView(typeRow, new LinearLayout.LayoutParams(-1, -2));

        body.addView(label(c, Lang.t(c, "Тип значения", "Value type")));
        final String[] valueType = {existing != null && existing.valueType != null ? existing.valueType : "Integer"};
        TextView valueTypeRow = SettingsStyle.text(c, valueType[0], 17, SettingsStyle.ROW_TITLE);
        valueTypeRow.setBackground(SettingsStyle.shape(c, SettingsStyle.ROW, 0, 12));
        valueTypeRow.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 14), SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 14));
        valueTypeRow.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        valueTypeRow.setTag("vendor_value_type");
        valueTypeRow.setOnClickListener(v -> {
            int selected = java.util.Arrays.asList(VALUE_TYPES).indexOf(valueType[0]);
            SettingsStyle.optionSheet(c, Lang.t(c, "Тип значения", "Value type"), VALUE_TYPES, VALUE_TYPES, selected, accent, i -> {
                valueType[0] = VALUE_TYPES[i];
                valueTypeRow.setText(valueType[0]);
            });
        });
        body.addView(valueTypeRow, fieldParams(c));

        body.addView(label(c, Lang.t(c, "Значение", "Value")));
        EditText value = field(c, existing != null ? existing.value : "0", "vendor_value");
        value.setHint(Lang.t(c, "массив — через запятую: 1,0,2", "an array: comma separated, 1,0,2"));
        body.addView(value, fieldParams(c));

        TextView save = SettingsStyle.capsule(c, Lang.t(c, "Сохранить", "Save"), accent);   // P574: the sheets' capsules
        save.setTag("sheet_save");
        body.addView(save, new LinearLayout.LayoutParams(-1, -2));
        TextView delete = null;
        if (!adding) {
            delete = SettingsStyle.quietCapsule(c, Lang.t(c, "Удалить", "Delete"), SettingsStyle.RED);
            delete.setTag("vendor_delete");
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
            dp.topMargin = SettingsStyle.dp(c, 10);
            body.addView(delete, dp);
        }

        BottomSheetDialog d = SettingsStyle.sheet(c, body);
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                ScameraApp.showToast(Lang.t(c, "Имя ключа не может быть пустым", "The key name can't be empty"));
                return;
            }
            String text = value.getText().toString().trim();
            try {
                VendorTagUtils.TunableKey.parseValue(valueType[0], text);
            } catch (RuntimeException wrong) {
                ScameraApp.showToast(Lang.t(c, "Значение не подходит к типу " + valueType[0], "The value does not fit " + valueType[0]));
                return;
            }
            VendorTagUtils.TunableKey key = new VendorTagUtils.TunableKey();
            key.type = chosenType[0];
            key.name = n;
            key.valueType = valueType[0];
            key.value = text;
            key.tested = existing != null && existing.tested;
            key.enabled = existing == null || existing.enabled;
            key.supported = existing != null && existing.supported;
            List<VendorTagUtils.TunableKey> list = TunableKeyManager.loadKeys(c, sensorId);
            if (adding) list.add(key);
            else if (slot < list.size()) list.set(slot, key);
            TunableKeyManager.saveKeys(c, sensorId, list);
            d.dismiss();
            if (onDone != null) onDone.run();
        });
        if (delete != null) delete.setOnClickListener(v -> {
            List<VendorTagUtils.TunableKey> list = TunableKeyManager.loadKeys(c, sensorId);
            if (slot >= 0 && slot < list.size()) {
                list.remove(slot);
                TunableKeyManager.saveKeys(c, sensorId, list);
            }
            d.dismiss();
            if (onDone != null) onDone.run();
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        if (adding) name.requestFocus();
        return d;
    }

    static String typeHint(Context c, String type) {
        if (VendorTagUtils.TunableKey.TYPE_SESSION.equals(type))
            return Lang.t(c, "Только в параметрах сессии, при её открытии (как forceSensorMode на X300 Ultra)",
                    "Only in the session parameters, when the session opens (like forceSensorMode on the X300 Ultra)");
        if (TYPE_REQUEST.equals(type))
            return Lang.t(c, "В каждом запросе кадра", "In every frame request");
        return Lang.t(c, "Читается из результата кадра", "Read from the frame result");
    }

    private static TextView label(Context c, String text) {
        TextView t = SettingsStyle.text(c, text, 13, SettingsStyle.SECTION);
        t.setPadding(SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 6), 0, SettingsStyle.dp(c, 8));
        return t;
    }

    private static EditText field(Context c, String value, String tag) {
        EditText e = SettingsStyle.field(new EditText(c));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value);
        e.setTag(tag);
        return e;
    }

    private static LinearLayout.LayoutParams fieldParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = SettingsStyle.dp(c, 12);
        return p;
    }
}
