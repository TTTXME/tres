package com.scamera.app.ui.controls;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The shared iOS controls: the segmented control's labels keep the caller's size and lines (only made smaller, 11 sp at the
 * least), the menu's pin row is an action (not checkable) while its choices are, and the switch's tap toggles it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)   // real text widths
public class IosControlsTest {
    private static float sp(Activity a, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, a.getResources().getDisplayMetrics());
    }

    private static IosSegmented segmented(Activity a, int width, CharSequence[] labels, float sizeSp, int maxLines) {
        IosSegmented seg = new IosSegmented(a);
        seg.setItems(labels);
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView t = (TextView) seg.getChildAt(i);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(a, sizeSp));
            t.setMaxLines(maxLines);
        }
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        root.addView(seg, new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT));
        seg.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        seg.layout(0, 0, width, seg.getMeasuredHeight());
        seg.fitLabels();
        return seg;
    }

    @Test
    public void theSegmentsKeepTheCallersSizeWhenTheyFitAndOnlyShrinkToElevenSp() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        IosSegmented roomy = segmented(a, 2000, new CharSequence[]{"Формат", "Размер"}, 14f, 1);
        for (int i = 0; i < roomy.getChildCount(); i++)
            assertEquals("the caller's 14 sp, not 13", sp(a, 14f), ((TextView) roomy.getChildAt(i)).getTextSize(), 0.5f);
        // one line each, too narrow at 14 sp: smaller, never below 11 sp
        IosSegmented tight = segmented(a, 260, new CharSequence[]{"Очень длинная подпись", "Ещё одна длинная"}, 14f, 1);
        float size = ((TextView) tight.getChildAt(0)).getTextSize();
        assertTrue("made smaller " + size, size < sp(a, 14f) - 0.1f);
        assertTrue("11 sp at the least " + size, size >= sp(a, 11f) - 0.5f);
        assertEquals("one size for all", size, ((TextView) tight.getChildAt(1)).getTextSize(), 0.01f);
        // the same labels on two lines fit sooner: a label's own maxLines counts
        IosSegmented twoLines = segmented(a, 260, new CharSequence[]{"Очень длинная подпись", "Ещё одна длинная"}, 14f, 2);
        assertTrue(((TextView) twoLines.getChildAt(0)).getTextSize() >= size);
        // a second fit (another size change) starts from the caller's size, not from the last fit
        tight.fitLabels();
        assertEquals(size, ((TextView) tight.getChildAt(0)).getTextSize(), 0.01f);
    }

    @Test
    public void theMenusPinRowIsAnActionAndItsChoicesAreCheckable() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        View anchor = new View(a);
        root.addView(anchor, new FrameLayout.LayoutParams(100, 100));
        ShadowLooper.idleMainLooper();
        PopupWindow pw = IosMenu.show(anchor, null, new CharSequence[]{"Авто", "Вкл.", "Добавить в шторку"},
                new CharSequence[]{"auto", "on", "pin"}, 0, i -> {});
        View pin = pw.getContentView().findViewWithTag("option_pin");
        View choice = pw.getContentView().findViewWithTag("option_auto");
        assertNotNull(pin);
        assertNotNull(choice);
        AccessibilityNodeInfo pinInfo = pin.createAccessibilityNodeInfo();
        assertFalse("«Добавить в шторку» is no choice", pinInfo.isCheckable());
        AccessibilityNodeInfo choiceInfo = choice.createAccessibilityNodeInfo();
        assertTrue(choiceInfo.isCheckable());
        assertTrue(choiceInfo.isChecked());
        pw.dismiss();
    }

    @Test
    public void theSwitchsTapTogglesItAndABindIsQuiet() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) a.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        org.robolectric.shadows.ShadowAccessibilityManager sam = org.robolectric.Shadows.shadowOf(am);
        sam.setEnabled(true);
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        IosSwitch s = new IosSwitch(a);
        root.addView(s);
        boolean[] heard = {false};
        s.setOnCheckedChangeListener((v, on) -> heard[0] = true);
        s.setChecked(true);
        assertTrue(s.isChecked());
        assertFalse("a bind tells no listener", heard[0]);
        assertEquals("a bind is no click for TalkBack", 0, clicks(sam));
        s.performClick();
        assertFalse(s.isChecked());
        assertTrue(heard[0]);
        assertEquals("a tap: one click event", 1, clicks(sam));
    }

    private static long clicks(org.robolectric.shadows.ShadowAccessibilityManager sam) {
        return sam.getSentAccessibilityEvents().stream()
                .filter(e -> e.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED).count();
    }
}
