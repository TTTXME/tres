/*
 *
 *  PhotonCamera
 *  AuxButtonsLayout.java
 *  Copyright (C) 2020 - 2021  Vibhor
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 * /
 */

package com.scamera.app.ui.camera.views;

import android.content.Context;
import com.scamera.app.settings.ModuleRegistry;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.scamera.app.R;
import com.scamera.app.ui.camera.binding.CustomBinding;
import com.scamera.app.ui.camera.data.CameraLensData;
import com.scamera.app.ui.camera.model.AuxButtonsModel;
import com.scamera.app.ui.settings.DialogStyle;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Container for multi-camera buttons.
 * <p>
 * SIMPLE_UI look (the concept's .lenses): one capsule in the card colour with a LINE stroke between «Про» and «Ещё», the
 * active lens filled with the camera accent (INK text, «1×», the live zoom «2,3×»), the others MUTED without the sign
 * («0,4», «2,4», «5»), 2dp apart, decimal commas. The gestures are unchanged (owner's answer 2): tap selects a lens, a horizontal drag zooms.
 * The buttons are {@link LensButton}s: in landscape only their labels turn (P32).
 * <p>
 * This layout's functionality is dependent on {@link AuxButtonsModel} which is provided
 * through DataBinding {@link CustomBinding#setAuxButtonModel(AuxButtonsLayout, AuxButtonsModel)}.
 */
public class AuxButtonsLayout extends LinearLayout {

    /**
     * this map stores dynamically generated view-ids and corresponding camera-ids attached to that view(or button)
     * for functional purpose
     */
    private final java.util.Map<Integer, String> slotOf = new HashMap<>();
    /** P72: zoom preset buttons (view id -> zoom) of a module (their slot in {@link #slotOf}): 17 Ultra tele 4.3x / 6.45x / 8.6x. */
    private final HashMap<Integer, Float> presetMap = new HashMap<>();
    /** Zooms the active module to a preset (CameraFragment.zoomTo). */
    private java.util.function.Consumer<Float> presetZoom;
    public void setPresetZoom(java.util.function.Consumer<Float> presetZoom) { this.presetZoom = presetZoom; }

    private final LayoutParams tileParams;
    /** P181: the gap on each side of a tile at fit 1 (px). */
    private final int baseGap;

    private AuxButtonsModel model;
    @Nullable private AuxButtonListener lensTaps;
    private long lastSwitch = -500;
    private int labelRotation;
    private final List<String> displayedSlots = new ArrayList<>();
    private final List<String> displayedLabels = new ArrayList<>();

    public AuxButtonsLayout(Context context, @Nullable AttributeSet set) {
        super(context, set);

        // P157: the strip in proportion to the screen's width (BottomChrome.scale)
        float density = context.getResources().getDisplayMetrics().density * BottomChrome.scale(context);
        // SIMPLE_UI: the concept's strip - the lenses 2dp apart, no dots, centred on one line (all of one height)
        tileParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tileParams.gravity = android.view.Gravity.CENTER_VERTICAL;
        baseGap = Math.round(1 * density);
        tileParams.setMargins(baseGap, 0, baseGap, 0);

        // The layout editor runs this constructor but not the data-binding adapters,
        // so populate a few sample buttons so the host preview shows the aux palette.
        if (isInEditMode()) {
            final String[] sample = {"1x", "2x", "5x"};
            for (int i = 0; i < sample.length; i++) addNewButton(String.valueOf(i), sample[i]);
            selectAndListen("0");
            showIfChoice();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        fitTiles(widthSpec);
        super.onMeasure(widthSpec, heightSpec);
    }

    /**
     * P181 (owner, 2026-10-16: «у нас модули выходят за границы экрана»): when the tiles at their own size (P161) and their
     * gaps are wider than the strip may be, all of them shrink together - least width, padding, text and gaps - so every
     * lens stays on the screen; they keep their own size whenever they fit. (The long press's module list stays for a strip
     * that would need more than {@link LensButton#MIN_FIT}.)
     */
    private void fitTiles(int widthMeasureSpec) {
        final int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || available <= 0) return;
        float need = 0f;
        int tiles = 0;
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final LensButton tile = tileAt(at, true);
            if (tile == null) continue;
            need += tile.naturalWidth() + 2 * baseGap;
            tiles++;
        }
        if (tiles == 0) return;
        // the rounded sizes must add up to no more than the strip: a step down until they do (a squeezed last tile otherwise)
        float f = fit(need, available);
        while (f > LensButton.MIN_FIT && roundedWidth(f) > available) f = Math.max(LensButton.MIN_FIT, f - 0.005f);
        final int gap = Math.round(baseGap * f);
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final LensButton tile = tileAt(at, false);
            if (tile == null) continue;
            tile.setFit(f);
            final android.view.ViewGroup.LayoutParams lp = tile.getLayoutParams();
            if (!(lp instanceof MarginLayoutParams)) continue;
            final MarginLayoutParams side = (MarginLayoutParams) lp;
            if (side.leftMargin != gap || side.rightMargin != gap) side.setMargins(gap, 0, gap, 0);
        }
    }

    /** P181: the tiles' total width at fit {@code f} as they will be measured (rounded sizes and gaps). */
    private int roundedWidth(float f) {
        final int gap = Math.round(baseGap * f);
        int total = 0;
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final LensButton tile = tileAt(at, true);
            if (tile != null) total += tile.widthAt(f) + 2 * gap;
        }
        return total;
    }

    /** P181: the tiles' total width at their own size (tests: a strip they fit only shrunk). */
    float naturalNeed() {
        float need = 0f;
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final LensButton tile = tileAt(at, true);
            if (tile != null) need += tile.naturalWidth() + 2 * baseGap;
        }
        return need;
    }

    /** The child at {@code at} when it is a lens tile ({@code shownOnly}: and not GONE), else null. */
    @Nullable
    private LensButton tileAt(int at, boolean shownOnly) {
        final View child = getChildAt(at);
        if (!(child instanceof LensButton) || (shownOnly && child.getVisibility() == View.GONE)) return null;
        return (LensButton) child;
    }

    /** P181: the share of their own size the tiles take: 1 when {@code need} fits {@code available}, at least MIN_FIT. */
    static float fit(float need, float available) {
        if (!(need > available) || available <= 0f) return 1f;
        // a pixel of slack per tile edge: the rounded sizes must not add up to more than the strip
        return Math.max(LensButton.MIN_FIT, (available - 2f) / need);
    }

    public void setAuxButtonsModel(AuxButtonsModel lenses) {
        model = lenses;
        lensTaps = lenses.getAuxButtonListener();
    }

    /** The strip of the side (front / back) that camera {@code cameraId} is on, with its module selected. */
    public void setActiveId(String cameraId) {
        if (model == null) return;
        final List<CameraLensData> front = model.getFrontCameras(), back = model.getBackCameras();
        if (front == null || back == null) return;
        boolean onFront = false;
        for (CameraLensData lens : front) onFront |= lens.getCameraId().equals(cameraId);
        rebuildStrip(onFront ? front : back, cameraId);
    }

    private void rebuildStrip(List<CameraLensData> lenses, String activeId) {
        SettingsManager manager = ScameraApp.getSettingsManagerStatic();
        ModuleRegistry.initialize("front", model.getFrontCameras());
        ModuleRegistry.initialize("back", model.getBackCameras());
        // P100: a main camera with in-sensor zoom (CROPPED_RAW, Galaxy S24 Ultra) gets its «2×» button once
        com.scamera.app.settings.IszModule.ensure(manager.getContext());
        // P108: the Pixel 10 Pro / Pro XL's in-sensor crops of the back logical camera (2x main, ~9x tele) as modules, once
        com.scamera.app.capture.PixelPro10.ensureModules(manager.getContext());
        // P109: the vivo X500 Pro Max's ISZ modules (main 2x, ultra-wide 2x, tele 2x / 4x), once
        // P122: the vivo X300 Ultra's tele 2x ISZ (forceSensorMode of camera_config.xml), once
        com.scamera.app.capture.VivoQcomIsz.ensureModules(manager.getContext());
        com.scamera.app.capture.VivoQcomModes.ensureModules(manager.getContext());
        com.scamera.app.capture.VivoPd2454Isz.ensureModules(manager.getContext());
        // A config imported before the slots existed (fresh install) maps its module profiles now.
        com.scamera.app.settings.BackupRestoreUtil.applyPending(manager.getContext());
        String side = lenses == model.getFrontCameras() ? "front" : "back";
        List<String> slots = ModuleRegistry.initialize(side, lenses);
        slots.sort(Comparator.comparingDouble(ModuleRegistry::zoom)); // zoom order, like the dial
        String currentSlot=ModuleRegistry.active();
        if(!ModuleRegistry.switching(activeId)&&(!slots.contains(currentSlot)||!ModuleRegistry.camera(currentSlot).equals(activeId))) {
            // The camera was changed from outside: the module of that camera nearest to the current zoom, not the first one.
            String best=null;float zoom=com.scamera.app.control.ZoomController.zoom();
            for(String slot:slots)if(ModuleRegistry.visible(slot)&&ModuleRegistry.camera(slot).equals(activeId)
                    &&(best==null||Math.abs(ModuleRegistry.zoom(slot)-zoom)<Math.abs(ModuleRegistry.zoom(best)-zoom)))best=slot;
            if(best!=null){
                ModuleRegistry.select(best);
                // P37: the zoom follows the module chosen here (the ruler started at the old zoom, e.g. 1x on a tele).
                com.scamera.app.control.ZoomController.syncToActive();
            }
        }
        List<String> shown = new ArrayList<>();
        for (String slot : slots) if (ModuleRegistry.visible(slot)) shown.add(slot);
        // P343: one button per stop (an ISZ module before a lens's own before a digital copy)
        shown = ZoomStops.unique(shown, ModuleRegistry::label, s -> ModuleRegistry.sensorMode(s) != 0 || ModuleRegistry.sensorCrop(s) ? 2
                : ModuleRegistry.duplicateOf(s) == null && Math.abs(ModuleRegistry.crop(s) - 1f) < 1e-4f ? 1 : 0, ModuleRegistry.active());
        logStrip(side, slots, shown);
        List<String> visible = new ArrayList<>(), labels = new ArrayList<>();
        for (String slot : shown) {
            visible.add(slot);labels.add(ModuleRegistry.label(slot));
            for (float p : presets(slot)) labels.add(com.scamera.app.capture.XiaomiTeleZoom.presetLabel(p));
        }
        if (!visible.equals(displayedSlots) || !labels.equals(displayedLabels)) {
            removeAllViews();slotOf.clear();presetMap.clear();
            displayedSlots.clear();displayedSlots.addAll(visible);
            displayedLabels.clear();displayedLabels.addAll(labels);
            for (String slot : visible) {
                addNewButton(slot, ModuleRegistry.label(slot));
                for (float p : presets(slot)) presetMap.put(addNewButton(slot, com.scamera.app.capture.XiaomiTeleZoom.presetLabel(p)), p);
            }
        }
        selectAndListen(activeId);
        showIfChoice();
    }

    private static String loggedStrip = "";

    /** P343: the strip once per change - every visible module: slot, camera, zoom, sensor mode / crop, and which ones are shown. */
    private static void logStrip(String side, List<String> slots, List<String> shown) {
        StringBuilder b = new StringBuilder("strip ").append(side).append(':');
        for (String slot : slots) {
            if (!ModuleRegistry.visible(slot)) continue;
            b.append(' ').append(slot).append("=cam").append(ModuleRegistry.camera(slot)).append(String.format(Locale.US, "@%.3f", ModuleRegistry.zoom(slot)));
            if (ModuleRegistry.sensorMode(slot) != 0) b.append(" mode").append(ModuleRegistry.sensorMode(slot));
            if (ModuleRegistry.sensorCrop(slot)) b.append(" sensorcrop");
            if (Math.abs(ModuleRegistry.crop(slot) - 1f) > 1e-4f) b.append(" crop").append(ModuleRegistry.crop(slot));
            if (!shown.contains(slot)) b.append(" (same stop, hidden)");
            b.append(',');
        }
        String line = b.toString();
        if (line.equals(loggedStrip)) return;
        loggedStrip = line;
        com.scamera.app.util.Log.i("ZOOM_STRIP", line);
    }

    private void selectAndListen(String activeId) {
        final View.OnClickListener tap = this::lensTapped;
        final float zoom = com.scamera.app.control.ZoomController.zoom();
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final View button = getChildAt(at);
            button.setOnClickListener(tap);
            String slot = slotOf.get(button.getId());
            boolean module = ModuleRegistry.active().equals(slot) ||
                    (!ModuleRegistry.slots().contains(ModuleRegistry.active()) && activeId.equals(ModuleRegistry.camera(slot)));
            button.setSelected(module && selectedHere(button.getId(), slot, zoom));
        }
        styleSelection();
    }

    /** P72: the 17 Ultra tele's zoom presets after its own button; empty elsewhere. */
    private static float[] presets(String slot) {
        return com.scamera.app.capture.XiaomiTeleZoom.presets(ModuleRegistry.zoom(slot));
    }

    /** Within the active module: its preset button at that zoom, else the module's own button. */
    private boolean selectedHere(int id, String slot, float zoom) {
        Float preset = presetMap.get(id);
        if (preset != null) return Math.abs(zoom - preset) < 0.03f;
        for (java.util.Map.Entry<Integer, Float> e : presetMap.entrySet())
            if (slot != null && slot.equals(slotOf.get(e.getKey())) && Math.abs(zoom - e.getValue()) < 0.03f) return false;
        return true;
    }

    /** One lens is no choice: the strip keeps its place but shows nothing. */
    private void showIfChoice() {
        setVisibility(getChildCount() > 1 ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * «0.6×» -> «0,6×»: the decimal comma on the strip in the Russian UI (owner's answer 11), the point in English; labels are
     * stored as they are.
     */
    static String display(String label) {
        if (label == null) return "";
        return Lang.ru() ? label.replaceAll("(\\d)\\.(\\d)", "$1,$2") : label;
    }

    /** The active lens in bold; the colours follow the selected state (P157: LensButton styles its own state). */
    private void styleSelection() {
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final View v = getChildAt(at);
            if (v instanceof Button && !(v instanceof LensButton))
                ((Button) v).setTypeface(com.scamera.app.ui.style.Fonts.of(v.getContext(), v.isSelected() ? 700 : 400));
        }
    }

    // ───── P495: the active lens's pill glides to the lens chosen next (iOS' segmented control)

    /** The settings' chip pill spring (SettingsMotion.GLIDE). */
    private static final com.scamera.app.ui.settings.SettingsMotion.Spring GLIDE =
            new com.scamera.app.ui.settings.SettingsMotion.Spring(0.34f, 0.78f);
    private final android.graphics.Paint pillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    /** The pill the strip draws while it glides (empty: the active lens draws its own), where the glide started, and the active lens's place. */
    private final android.graphics.RectF pill = new android.graphics.RectF(), pillFrom = new android.graphics.RectF(),
            pillAt = new android.graphics.RectF();
    /** The active lens at the last layout; the lens whose own pill is hidden while the strip's glides to it. */
    @Nullable private LensButton pillLens, pillHidden, fading;
    @Nullable private android.animation.ValueAnimator glide;

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        followSelection();
    }

    /**
     * After a layout: another lens of the same buttons became the active one - the pill glides from where it was to that lens on
     * the GLIDE spring (drawn by the strip under the labels while the lens hides its own); the same lens moved or grew (its
     * live zoom label, the fit): a running glide heads for its new place; new buttons or no motion: the lens's own pill at once.
     */
    private void followSelection() {
        LensButton now = null;
        for (int at = 0, n = getChildCount(); at < n && now == null; at++) {
            final View v = getChildAt(at);
            if (v instanceof LensButton && v.isSelected() && v.getVisibility() == VISIBLE) now = (LensButton) v;
        }
        final LensButton before = pillLens;
        final boolean had = before != null && before.getParent() == this && !pillAt.isEmpty();
        final float l0 = pillAt.left, t0 = pillAt.top, r0 = pillAt.right, b0 = pillAt.bottom;
        pillLens = now;
        if (now == null) {
            endGlide();
            pillAt.setEmpty();
            return;
        }
        pillAt.set(now.getLeft(), now.getTop(), now.getRight(), now.getBottom());
        if (now == before) {
            if (glide != null) invalidate();
            return;
        }
        if (!had || !com.scamera.app.ui.Motion.enabled(this)) {
            endGlide();
            return;
        }
        if (glide != null) pillFrom.set(pill);
        else pillFrom.set(l0, t0, r0, b0);
        endGlide();
        pill.set(pillFrom);
        pillPaint.setColor(com.scamera.app.ui.camera.ConceptStyle.CHOSEN);
        pillHidden = now;
        now.ownPill(false);
        // P589 (owner, 2026-11-09: the old switch «переделай и улучши её под нашу новую стилистику»): the pill flows - its leading
        // edge runs ahead on the spring and the trailing one follows (a short stretch toward the new lens that settles into it),
        // the labels cross-fade white <-> accent with it, and the new lens pops once as the pill lands
        final boolean right = pillAt.centerX() >= pillFrom.centerX();
        final LensButton old = before != null && before.getParent() == this ? before : null;
        fading = old;
        final int accent = com.scamera.app.circularbarlib.ui.AccentPalette.camera(getContext());
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.round(GLIDE.durationMs * 1.15f));
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        final LensButton target = now;
        a.addUpdateListener(x -> {
            final float t = (float) x.getAnimatedValue();
            final float lead = GLIDE.getInterpolation(Math.min(1f, t * 1.3f)), trail = GLIDE.getInterpolation(Math.max(0f, (t - 0.14f) / 0.86f));
            final float kl = right ? trail : lead, kr = right ? lead : trail, km = (lead + trail) / 2f;
            pill.set(pillFrom.left + (pillAt.left - pillFrom.left) * kl, pillFrom.top + (pillAt.top - pillFrom.top) * km,
                    pillFrom.right + (pillAt.right - pillFrom.right) * kr, pillFrom.bottom + (pillAt.bottom - pillFrom.bottom) * km);
            final float c = Math.max(0f, Math.min(1f, lead));
            target.labelColour(com.scamera.app.ui.settings.SettingsMotion.mix(0xFFFFFFFF, accent, c));
            if (old != null) old.labelColour(com.scamera.app.ui.settings.SettingsMotion.mix(accent, 0xFFFFFFFF, c));
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (glide == a) {
                    endGlide();
                    land(target);
                }
            }
        });
        glide = a;
        a.start();
    }

    /** P589: the lens the pill landed on swells a little and settles (1 -> 1.07 -> 1, 260 ms). */
    private static void land(View v) {
        if (!com.scamera.app.ui.Motion.enabled(v)) return;
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 1.07f, 1f);
        a.setDuration(260);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(x -> {
            final float k = (float) x.getAnimatedValue();
            v.setScaleX(k);
            v.setScaleY(k);
        });
        a.start();
    }

    /** The glide stops: the active lens draws its own pill again. */
    private void endGlide() {
        final android.animation.ValueAnimator g = glide;
        glide = null;
        if (g != null) g.cancel();
        if (pillHidden != null) {
            pillHidden.ownPill(true);
            pillHidden.restoreLabels();
        }
        pillHidden = null;
        if (fading != null) fading.restoreLabels();
        fading = null;
        if (!pill.isEmpty()) {
            pill.setEmpty();
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        if (!pill.isEmpty()) {
            final float r = pill.height() * LensButton.CORNER_DP / LensButton.HEIGHT_DP; // the active lens's own corners
            canvas.drawRoundRect(pill, r, r, pillPaint);
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        endGlide();
    }

    private void lensTapped(View view) {
        if (view.isSelected()) return;
        if (model != null && model.isEnabled() && android.os.SystemClock.elapsedRealtime()-lastSwitch>=500) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            // P495: the press springs the lens back (LensButton) and the active pill glides over to it (dispatchDraw); the old
            // click pulse would fight the press for the scale
            lastSwitch=android.os.SystemClock.elapsedRealtime();
            for (int at = getChildCount() - 1; at >= 0; at--) getChildAt(at).setSelected(getChildAt(at) == view);
            styleSelection();
            Float preset = presetMap.get(view.getId());
            if (preset != null && lensTaps != null) {
                // P72: a zoom preset of a module: the zoom only on the active module, else that module at this zoom
                String slot = slotOf.get(view.getId());
                if (slot.equals(ModuleRegistry.active())) {
                    if (presetZoom != null) presetZoom.accept(preset);
                } else {
                    ModuleRegistry.select(slot);
                    com.scamera.app.control.ZoomController.onPreset(slot, preset);
                    if (getContext() instanceof android.app.Activity) touchDial();
                    lensTaps.onAuxButtonClicked(ModuleRegistry.camera(slot));
                }
                return;
            }
            if (lensTaps != null) {
                final String slot = slotOf.get(view.getId());
                ModuleRegistry.select(slot);
                com.scamera.app.control.ZoomController.onButton(slot);
                if(getContext() instanceof android.app.Activity)touchDial();
                lensTaps.onAuxButtonClicked(ModuleRegistry.camera(slot));
            }
        }
    }

    int addNewButton(String cameraId, String buttonText) {
        final String shown = display(buttonText);
        final LensButton tile = new LensButton(getContext());
        tile.setLayoutParams(tileParams);
        tile.setText(shown);
        tile.setLabelRotation(labelRotation);
        tile.setContentDescription(Lang.t(getContext(), "Объектив ", "Lens ") + shown);
        tile.setOnLongClickListener(v -> showAllModules());
        final int id = View.generateViewId();
        tile.setId(id);
        slotOf.put(id, cameraId);
        addView(tile);
        return id;
    }

    private boolean modulesOverflow() {
        if (getWidth() <= 0) return false;
        int total = getPaddingLeft() + getPaddingRight();
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final View child = getChildAt(at);
            if (child.getVisibility() == View.GONE) continue;
            total += child.getMeasuredWidth();
            if (child.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams margins =
                        (android.view.ViewGroup.MarginLayoutParams) child.getLayoutParams();
                total += margins.leftMargin + margins.rightMargin;
            }
            if (child.getLeft() < getPaddingLeft() || child.getRight() > getWidth() - getPaddingRight()) return true;
            // P181: a tile measured narrower than its (fitted) least width was squeezed: it does not fit either
            if (child instanceof LensButton && child.getMeasuredWidth() < ((LensButton) child).getMinWidth()) return true;
        }
        return total > getWidth();
    }

    private boolean showAllModules() {
        if (!modulesOverflow() || model == null || !model.isEnabled()) return false;
        List<View> buttons = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final View button = getChildAt(at);
            final String slot = slotOf.get(button.getId());
            if (slot == null || presetMap.containsKey(button.getId()) || !ModuleRegistry.visible(slot)) continue;
            buttons.add(button);
            labels.add(display(ModuleRegistry.label(slot)) + Lang.t(getContext(), " · камера ", " · camera ")
                    + ModuleRegistry.camera(slot));
        }
        if (buttons.size() <= 1) return false;
        DialogStyle.builder(getContext()).setTitle(Lang.t(getContext(), "Модули", "Modules"))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    View button = buttons.get(which);
                    String slot = slotOf.get(button.getId());
                    if (button.getParent() == this && slot != null && ModuleRegistry.visible(slot)) lensTapped(button);
                }).show();
        return true;
    }

    /** The selected button shows the live zoom ("2.3×") while it differs from the module's own ratio. */
    public void setZoomLabel(float zoom) {
        String active = ModuleRegistry.active();
        for (int at = 0, n = getChildCount(); at < n; at++) {
            final View v = getChildAt(at);
            final String slot = slotOf.get(v.getId());
            if (!(v instanceof Button) || slot == null) continue;
            boolean selected = slot.equals(active) && selectedHere(v.getId(), slot, zoom);
            v.setSelected(selected);
            Float preset = presetMap.get(v.getId());
            if (preset != null) {
                ((Button) v).setText(display(com.scamera.app.capture.XiaomiTeleZoom.presetLabel(preset)));
                continue;
            }
            ((Button) v).setText(selected && Math.abs(zoom - ModuleRegistry.zoom(slot)) >= 0.05f
                    ? display(String.format(Locale.US, "%.1f×", zoom).replace(".0×", "×")) : display(ModuleRegistry.label(slot)));
        }
        styleSelection();
    }

    private Runnable dialRefresh;
    /** Lets the owner refresh and show the zoom ruler after a button tap. */
    public void setDialRefresh(Runnable dialRefresh){this.dialRefresh=dialRefresh;}
    private void touchDial(){if(dialRefresh!=null)dialRefresh.run();}

    /**
     * P32: only the labels turn upright with the phone; the buttons and the selected pill keep their portrait shape (a
     * turned button made the pill a tall capsule clipped flat by the strip).
     */
    public void rotateLabels(int orientation, long duration) {
        labelRotation=orientation;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            if(child instanceof LensButton)((LensButton)child).turnLabel(orientation,duration);
        }
    }

    /** Horizontal drag on the strip: continuous zoom (pixels since the previous event). */
    public interface ZoomDrag { void onDrag(float dx); }
    private ZoomDrag zoomDrag;
    public void setZoomDrag(ZoomDrag zoomDrag){this.zoomDrag=zoomDrag;}
    private float lastDragX;
    private float touchX,touchY;
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e){
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){touchX=e.getX();touchY=e.getY();}
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-touchX)>android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(e.getX()-touchX)>Math.abs(e.getY()-touchY)){
            // P37: the drag continues from here. A drag that starts on a lens button reaches onTouchEvent without its
            // DOWN, so lastDragX was stale (0 or the previous gesture's end): the first step jumped by the whole
            // distance, e.g. from a tele module straight back to 1x.
            lastDragX=e.getX();
            return true;
        }
        return super.onInterceptTouchEvent(e);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(zoomDrag!=null){
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)lastDragX=e.getX();
            else if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE){zoomDrag.onDrag(e.getX()-lastDragX);lastDragX=e.getX();}
        }
        // SIMPLE_UI §1: the old swipe-to-the-next-lens (unreachable with a drag zoom) is gone
        return true;
    }
    /** Told the camera id of a lens tile tapped (the module is selected already). */
    public interface AuxButtonListener {
        void onAuxButtonClicked(String id);
    }
}
