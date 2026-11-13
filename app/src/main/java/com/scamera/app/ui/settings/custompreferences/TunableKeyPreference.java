package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.scamera.app.R;
import com.scamera.app.api.VendorTagUtils;
import com.scamera.app.settings.TunableKeyManager;
import com.scamera.app.util.Lang;

import java.util.List;

/**
 * A user-defined {@link VendorTagUtils.TunableKey} of a module: the key name, «Сессия» / «Запрос», its value and whether the camera
 * takes it, and a switch that turns the key on or off without deleting it (P122, owner 2026-10-13: «дай возможность включать и
 * выключать реквесты если у меня вписано несколько штук»). A tap on the card edits the key. P384 (owner, 2026-10-28: «Сделай это
 * меню красивым… и красивыми иконками»): the icon is the key glyph with a small status dot in the settings' palette (taken: green,
 * refused: the warning colour, not tried yet or off: muted) - it was a saturated full circle; the switch updates the row in place
 * (the list rebuilt under the main page's diff faded every key row out and in).
 */
public class TunableKeyPreference extends Preference {
    static final int DOT_TAKEN = 0xFFABE0BA;
    static final int DOT_REFUSED = 0xFFFFB4A8;
    static final int DOT_UNKNOWN = 0xFFB2BAC9;

    private final String keyScope;
    private final int position;
    private final Runnable onChanged;
    @Nullable private final Runnable onSwitch;
    private boolean enabled = true;

    /**
     * @param onChanged run after the key's sheet saved or deleted it (the module's key rows are built again)
     * @param onSwitch  run after the switch changed the key (the page's summaries that count the keys)
     */
    public TunableKeyPreference(Context context, String keyScope, int position, Runnable onChanged, @Nullable Runnable onSwitch) {
        super(context);
        this.keyScope = keyScope;
        this.position = position;
        this.onChanged = onChanged;
        this.onSwitch = onSwitch;
        setLayoutResource(R.layout.preference_card);
        setWidgetLayoutResource(R.layout.settings_widget_key_switch);
        setIconSpaceReserved(true);
        bindData();
        setOnPreferenceClickListener(preference -> {
            TunableKeyDialog.show(getContext(), keyScope, position, onChanged);
            return true;
        });
    }

    /**
     * Set the displayed data from the stored key. Not from {@link #onBindViewHolder}: those setters call {@code notifyChanged()},
     * which crashes while the RecyclerView is computing a layout.
     */
    private void bindData() {
        List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(getContext(), keyScope);
        if (position < 0 || position >= keys.size()) return;
        VendorTagUtils.TunableKey key = keys.get(position);
        enabled = key.enabled;
        setTitle(key.tag);
        // P592: «Сессия · Integer 20 · принят камерой» is the key's state: it stays under the name
        com.scamera.app.ui.settings.SettingHelp.summaryIsState(this);
        com.scamera.app.ui.settings.SettingHelp.describe(this, Lang.t(getContext(),
                "Ключ камеры (vendor tag) этого модуля. Он уходит в сессию камеры или в каждый запрос кадра. Переключатель включает и выключает ключ, нажатие открывает его имя, тип и значение. Под названием видно, приняла ли камера ключ.",
                "A camera key (vendor tag) of this module. It goes into the camera session or into every frame request. The switch turns the key on and off, a tap opens its name, type and value. The line under the name says whether the camera took the key."));
        setSummary(summary(getContext(), key));
        setIcon(statusIcon(getContext(), key.enabled ? getStatusColor(key) : DOT_UNKNOWN));
    }

    /** «Сессия · Integer 20 · принят камерой» / «Выкл. · Запрос · …». */
    static String summary(Context context, VendorTagUtils.TunableKey key) {
        String kind = VendorTagUtils.TunableKey.TYPE_SESSION.equals(key.kind) ? Lang.t(context, "Сессия", "Session")
                : Lang.t(context, "Запрос", "Request");
        String status = !key.sent ? Lang.t(context, "ещё не проверен", "not tried yet")
                : key.known ? Lang.t(context, "принят камерой", "taken by the camera") : Lang.t(context, "камера не знает", "unknown to the camera");
        return (key.enabled ? "" : Lang.t(context, "Выкл. · ", "Off · ")) + kind + " · " + key.typeName + " " + key.text + " · " + status;
    }

    public boolean keyEnabled() { return enabled; }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View widget = holder.findViewById(R.id.switchWidget);
        if (widget instanceof com.scamera.app.ui.controls.IosSwitch) {
            // P574: the settings' switch; a recycled row shows this key's state at once
            com.scamera.app.ui.controls.IosSwitch sw = (com.scamera.app.ui.controls.IosSwitch) widget;
            sw.setOnCheckedChangeListener(null);
            if (sw.getTag(R.id.settings_bound_preference) != this) sw.setCheckedNow(enabled);
            else sw.setChecked(enabled);
            sw.setTag(R.id.settings_bound_preference, this);
            sw.setContentDescription(getTitle());
            sw.setOnCheckedChangeListener((button, on) -> {
                List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(getContext(), keyScope);
                if (position < 0 || position >= keys.size()) return;
                keys.get(position).enabled = on;
                TunableKeyManager.saveKeys(getContext(), keyScope, keys);
                enabled = on;
                // after the click has finished (not while the RecyclerView binds): this row's texts and dot, then the page
                button.post(() -> {
                    bindData();
                    notifyChanged();
                    if (onSwitch != null) onSwitch.run();
                });
            });
        }
        holder.itemView.setAlpha(enabled ? 1f : 0.6f);
    }

    private static int getStatusColor(VendorTagUtils.TunableKey key) {
        if (!key.sent) return DOT_UNKNOWN;
        return key.known ? DOT_TAKEN : DOT_REFUSED;
    }

    /** The key glyph with a 7 dp status dot at its bottom end (ringed in the tile colour, so it reads on the glyph). */
    static Drawable statusIcon(Context context, int color) {
        Drawable key = AppCompatResources.getDrawable(context, R.drawable.cs_ic_key);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(7 * density), ring = Math.max(1, Math.round(1.5f * density));
        dot.setStroke(ring, 0xFF232A2E);
        dot.setSize(size + 2 * ring, size + 2 * ring);
        if (key == null) return dot;
        int box = Math.round(22 * density);
        key.setBounds(0, 0, box, box);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{key, dot});
        layers.setLayerSize(0, box, box);
        layers.setLayerSize(1, size + 2 * ring, size + 2 * ring);
        layers.setLayerGravity(1, Gravity.BOTTOM | Gravity.END);
        return layers;
    }
}
