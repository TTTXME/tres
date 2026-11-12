package com.particlesdevs.photoncamera.processing.watermark;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

import com.particlesdevs.photoncamera.gallery.ui.PhotoInfo;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.WatermarkPreviewPreference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P628: the watermark's text and layout - no «Shot on» by default, every field on its own (any subset, a single one included, with
 * nothing left behind by the others), long text shortened, every layout / position / size / orientation inside the photo, in the
 * chosen corner of the photo as it is viewed and the right way up. Renders the layouts on the settings' sample photo to
 * build/reports/watermark.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WatermarkLayoutTest {
    /** 12, 50 and 200 MP in landscape and portrait (the photo as it is saved: upright). */
    static final int[][] SIZES = {{4000, 3000}, {3000, 4000}, {8192, 6144}, {6144, 8192}, {16320, 12288}, {12288, 16320}, {1600, 900}, {900, 1600}};
    static final String[] FIELDS = {"text", "device", "lens", "aperture", "shutter", "iso", "time", "logo"};

    static final long NOW = at(2026, 11, 11, 14, 5);

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    static WatermarkText.Info info() {
        WatermarkText.Info i = WatermarkText.Info.sample("vivo X200 Ultra", NOW);
        i.zone = TimeZone.getTimeZone("UTC");
        return i;
    }

    static WatermarkPainter.Fonts fonts() {
        return new WatermarkFonts(RuntimeEnvironment.getApplication());
    }

    static WatermarkSpec single(WatermarkSpec base, String field) {
        return base.only(field.equals("text"), field.equals("device"), field.equals("lens"), field.equals("aperture"),
                field.equals("shutter"), field.equals("iso"), field.equals("time"), field.equals("logo"));
    }

    static String expected(String field) {
        switch (field) {
            case "text": return "SCAMERA";
            case "device": return "vivo X200 Ultra";
            case "lens": return "24mm";
            case "aperture": return "f/1.8";
            case "shutter": return "1/120s";
            case "iso": return "ISO 100";
            case "time": return "11.11.2026  14:05";
            default: return null;
        }
    }

    // ───── Text ─────

    @Test public void theDefaultIsTheAppNameWithoutShotOn() {
        WatermarkSpec spec = new WatermarkSpec();
        assertEquals("SCAMERA", spec.text);
        assertEquals(WatermarkSpec.Layout.TEXT, spec.layout);
        WatermarkText t = WatermarkText.of(spec, info());
        assertEquals("SCAMERA", t.title);
        assertEquals("", t.exif);
        assertEquals("", t.time);
        assertTrue(t.logo);
        for (String g : t.groups()) assertFalse(g, g.toLowerCase(Locale.ROOT).contains("shot on"));
        // the stored defaults read the same (no settings: the defaults)
        WatermarkSpec stored = WatermarkSpec.from(null);
        assertEquals("SCAMERA", stored.text);
        assertFalse(stored.showDevice || stored.showLens || stored.showAperture || stored.showShutter || stored.showIso || stored.showTime);
    }

    @Test public void everyFieldStandsAlone() {
        for (String f : FIELDS) {
            WatermarkText t = WatermarkText.of(single(new WatermarkSpec(), f), info());
            if (f.equals("logo")) {
                assertTrue(t.groups().isEmpty());
                assertTrue(t.any());
                continue;
            }
            assertEquals(f, 1, t.groups().size());
            assertEquals(f, expected(f), t.groups().get(0));
            assertEquals(f, t.groups(), t.column());
            assertFalse(f, t.logo);
            // a band puts a lone field on its leading side, nothing on the other
            assertEquals(f, t.groups(), t.leading());
            assertTrue(f, t.trailing().isEmpty());
        }
        // nothing on: nothing drawn (no forced phone, app name or glyph)
        WatermarkText none = WatermarkText.of(new WatermarkSpec().only(false, false, false, false, false, false, false, false), info());
        assertFalse(none.any());
    }

    @Test public void subsetsLeaveNoSeparatorsBehind() {
        WatermarkSpec all = new WatermarkSpec().only(true, true, true, true, true, true, true, true);
        WatermarkText t = WatermarkText.of(all, info());
        assertEquals("SCAMERA  ·  vivo X200 Ultra", t.title);
        assertEquals("24mm   f/1.8   1/120s   ISO 100", t.exif);
        assertEquals("11.11.2026  14:05", t.time);
        assertEquals(2, t.column().size());
        assertEquals("24mm   f/1.8   1/120s   ISO 100  ·  11.11.2026  14:05", t.column().get(1));
        // every subset of the eight fields: no empty group, no separator at an end or twice
        for (int mask = 0; mask < 256; mask++) {
            WatermarkSpec s = new WatermarkSpec().only((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                    (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0, (mask & 128) != 0);
            WatermarkText x = WatermarkText.of(s, info());
            List<String> all2 = new ArrayList<>(x.column());
            all2.addAll(x.leading());
            all2.addAll(x.trailing());
            for (String line : all2) {
                assertFalse(mask + ": " + line, line.isEmpty());
                assertEquals(mask + ": " + line, line.trim(), line);
                assertFalse(mask + ": " + line, line.startsWith("·") || line.endsWith("·") || line.contains("·  ·"));
            }
            assertTrue(mask + "", x.column().size() <= 2);
        }
        // a field without a value is left out with its separator
        WatermarkText.Info partial = info();
        partial.iso = 0;
        partial.fNumber = Double.NaN;
        WatermarkText p = WatermarkText.of(all, partial);
        assertEquals("24mm   1/120s", p.exif);
        partial.focal35 = 0;
        assertEquals("6.9mm   1/120s", WatermarkText.of(all, partial).exif);
    }

    @Test public void onlyTheDateAndTimeInEveryFormat() {
        WatermarkSpec s = single(new WatermarkSpec(), "time");
        String[][] formats = {{"datetime", "11.11.2026  14:05"}, {"date", "11.11.2026"}, {"time", "14:05"}, {"iso", "2026-11-11 14:05"},
                {"text", "11 November 2026, 14:05"}};
        for (String[] f : formats) {
            s.dateFormat = WatermarkSpec.dateFormat(f[0]);
            assertEquals(f[0], f[1], WatermarkText.time(NOW, TimeZone.getTimeZone("UTC"), s.dateFormat, false));
        }
        assertTrue(WatermarkText.time(NOW, TimeZone.getTimeZone("UTC"), WatermarkSpec.DateFormat.TEXT, true).startsWith("11 "));
        assertEquals("", WatermarkText.of(s, new WatermarkText.Info()).time); // no shot time: no date
    }

    @Test public void valuesAreFormattedAsOnCameras() {
        assertEquals("1/120s", WatermarkText.shutter(1 / 120.0));
        assertEquals("1/4s", WatermarkText.shutter(.25));
        assertEquals("0.5s", WatermarkText.shutter(.5));
        assertEquals("2s", WatermarkText.shutter(2));
        assertEquals("1.5s", WatermarkText.shutter(1.5));
        assertEquals("", WatermarkText.shutter(Double.NaN));
        assertEquals("f/2.0", WatermarkText.aperture(2));
        assertEquals("", WatermarkText.aperture(0));
        assertEquals(6.9, PhotoWatermark.rational("690/100"), 1e-9);
        assertTrue(Double.isNaN(PhotoWatermark.rational(null)));
    }

    @Test public void theCustomTextIsCleanedAndCut() {
        assertEquals("A B", WatermarkText.clean("  A\n\t B  "));
        String longText = new String(new char[200]).replace('\0', 'W');
        assertEquals(WatermarkSpec.MAX_TEXT, WatermarkText.clean(longText).length());
        WatermarkSpec s = single(new WatermarkSpec(), "text");
        s.text = longText;
        s.size = 20;
        for (WatermarkSpec.Layout layout : WatermarkSpec.Layout.values()) {
            s.layout = layout;
            WatermarkPainter.Plan plan = WatermarkPainter.plan(s, WatermarkText.of(s, info()), 1200, 900, fonts());
            assertEquals(layout + "", 1, plan.runs.size());
            WatermarkPainter.Run r = plan.runs.get(0);
            assertTrue(layout + ": " + r.text, r.text.endsWith("…"));
            assertInside(layout + " long text", plan, 1200, 900);
        }
    }

    // ───── Geometry ─────

    static void assertInside(String what, WatermarkPainter.Plan plan, int w, int h) {
        final float eps = .5f;
        RectF all = new RectF(-eps, -eps, w + eps, h + eps);
        assertTrue(what + " bounds " + plan.bounds, all.contains(plan.bounds));
        for (WatermarkPainter.Run r : plan.runs) {
            assertTrue(what + " run " + r.text + " " + r.box, all.contains(r.box));
            assertFalse(what, r.text.isEmpty());
        }
        if (plan.logo != null) assertTrue(what + " logo " + plan.logo, all.contains(plan.logo));
        if (plan.plate != null) assertTrue(what + " plate " + plan.plate, all.contains(plan.plate));
        if (plan.window != null) assertTrue(what + " window " + plan.window, all.contains(plan.window));
    }

    /** Every layout x position x single field (and all fields, and none) x 12 / 50 / 200 MP x landscape / portrait. */
    @Test public void everyLayoutPositionSizeAndOrientationStaysInside() {
        List<WatermarkSpec> specs = new ArrayList<>();
        for (String f : FIELDS) specs.add(single(new WatermarkSpec(), f));
        specs.add(new WatermarkSpec().only(true, true, true, true, true, true, true, true));
        specs.add(new WatermarkSpec());
        int checked = 0;
        for (int[] size : SIZES)
            for (WatermarkSpec.Layout layout : WatermarkSpec.Layout.values())
                for (WatermarkSpec.Position pos : WatermarkSpec.Position.values())
                    for (WatermarkSpec base : specs)
                        for (float px : new float[]{3, 7, 20}) {
                            WatermarkSpec s = base.copy();
                            s.layout = layout;
                            s.position = pos;
                            s.size = px;
                            WatermarkText t = WatermarkText.of(s, info());
                            WatermarkPainter.Plan plan = WatermarkPainter.plan(s, t, size[0], size[1], fonts());
                            String what = layout + " " + pos + " " + size[0] + "x" + size[1] + " size " + px + " " + t.groups() + " logo " + t.logo;
                            assertFalse(what, plan.empty());
                            assertInside(what, plan, size[0], size[1]);
                            // a single field is one line, the glyph alone none
                            int groups = t.groups().size();
                            if (groups <= 1) assertEquals(what, groups, plan.runs.size());
                            assertEquals(what, t.logo, plan.logo != null);
                            corner(what, plan, s, size[0], size[1]);
                            checked++;
                        }
        assertTrue(checked > 3000);
    }

    /** The watermark sits in the chosen corner / edge of the photo as viewed. */
    static void corner(String what, WatermarkPainter.Plan plan, WatermarkSpec s, int w, int h) {
        RectF content = new RectF();
        boolean any = false;
        for (WatermarkPainter.Run r : plan.runs) { if (any) content.union(r.box); else content.set(r.box); any = true; }
        if (plan.logo != null) { if (any) content.union(plan.logo); else content.set(plan.logo); any = true; }
        assertTrue(what, any || plan.window != null);
        if (!any) return;
        float cy = content.centerY(), cx = content.centerX();
        if (s.position.top()) assertTrue(what + " top " + content, cy < h / 2f);
        else assertTrue(what + " bottom " + content, cy > h / 2f);
        if (s.position.center()) assertEquals(what + " centre " + content, w / 2f, cx, w * .02f);
        else if (s.position.right()) assertTrue(what + " right " + content, content.right > w * .5f && content.right <= w);
        else assertTrue(what + " left " + content, content.left < w * .5f && content.left >= 0);
        if (plan.layout == WatermarkSpec.Layout.BAR) {
            assertNotNull(what, plan.plate);
            if (s.position.top()) assertEquals(what, 0f, plan.plate.top, .01f);
            else assertEquals(what, h, plan.plate.bottom, .01f);
            assertTrue(what, plan.plate.contains(content));
        }
        if (plan.layout == WatermarkSpec.Layout.BADGE) assertTrue(what, plan.plate != null && plan.plate.contains(content));
        if (plan.layout == WatermarkSpec.Layout.FRAME) {
            assertNotNull(what, plan.window);
            assertFalse(what + " text over the photo's window", RectF.intersects(plan.window, content));
        }
    }

    /** A portrait and a landscape photo of the same size get the same watermark size (measured on the short side). */
    @Test public void portraitAndLandscapeMatch() {
        WatermarkSpec s = new WatermarkSpec();
        WatermarkPainter.Plan land = WatermarkPainter.plan(s, WatermarkText.of(s, info()), 4000, 3000, fonts());
        WatermarkPainter.Plan port = WatermarkPainter.plan(s, WatermarkText.of(s, info()), 3000, 4000, fonts());
        assertEquals(land.unit, port.unit, 1e-3);
        assertEquals(land.runs.get(0).box.width(), port.runs.get(0).box.width(), 1e-2);
        assertEquals(3000 * 7 / 100f * WatermarkSpec.TITLE_SHARE, land.unit, 1e-2);
    }

    /**
     * The four ways the phone is held (the photo is stored upright: PostPipeline turns the pixels by the shot's rotation, no
     * Orientation tag): the watermark is drawn on the upright photo, so for every rotation the date lands in the viewed photo's
     * bottom-left corner and reads horizontally (its ink is wider than tall), never turned with the sensor.
     */
    @Test public void everyRotationIsUprightInTheChosenCorner() {
        WatermarkSpec s = single(new WatermarkSpec(), "time");
        s.colour = WatermarkSpec.Colour.WHITE;
        s.size = 12;
        for (int rotation : new int[]{0, 90, 180, 270}) {
            int[] viewed = PhotoInfo.oriented(800, 600, rotation);
            Bitmap photo = Bitmap.createBitmap(viewed[0], viewed[1], Bitmap.Config.ARGB_8888);
            photo.eraseColor(Color.BLACK);
            WatermarkPainter.Plan plan = WatermarkPainter.drawOn(photo, s, WatermarkText.of(s, info()), fonts());
            assertNotNull(plan);
            int[] ink = ink(photo);
            String what = "rotation " + rotation + " " + viewed[0] + "x" + viewed[1] + " ink " + java.util.Arrays.toString(ink);
            assertTrue(what, ink[2] > ink[0]);
            assertTrue(what + " in the left half", ink[2] < viewed[0] * .7f && ink[0] < viewed[0] * .2f);
            assertTrue(what + " in the bottom half", ink[1] > viewed[1] / 2f);
            assertTrue(what + " reads across", ink[2] - ink[0] > 3 * (ink[3] - ink[1]));
            photo.recycle();
        }
    }

    /** {left, top, right, bottom} of the bright pixels. */
    static int[] ink(Bitmap b) {
        int l = b.getWidth(), t = b.getHeight(), r = -1, bo = -1;
        for (int y = 0; y < b.getHeight(); y++)
            for (int x = 0; x < b.getWidth(); x++)
                if ((b.getPixel(x, y) & 0xFF) > 160) { l = Math.min(l, x); r = Math.max(r, x); t = Math.min(t, y); bo = Math.max(bo, y); }
        return new int[]{l, t, r, bo};
    }

    @Test public void autoPicksTheInkByThePhoto() {
        WatermarkSpec s = new WatermarkSpec();
        for (int bg : new int[]{Color.WHITE, Color.BLACK}) {
            Bitmap photo = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
            photo.eraseColor(bg);
            WatermarkPainter.Plan plan = WatermarkPainter.drawOn(photo, s, WatermarkText.of(s, info()), fonts());
            assertNotNull(plan);
            assertEquals(bg == Color.BLACK, plan.whiteInk);
        }
        // nothing to draw: the photo is left alone
        Bitmap photo = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        assertNull(WatermarkPainter.drawOn(photo, new WatermarkSpec().only(false, false, false, false, false, false, false, false),
                WatermarkText.of(new WatermarkSpec().only(false, false, false, false, false, false, false, false), info()), fonts()));
    }

    /**
     * The owner's previews: each layout with the defaults, everything, only the date and time, the exposure and phone, and
     * everything in black on the light plate / white frame at the bottom right, in landscape and portrait.
     */
    @Test public void renderPreviews() throws Exception {
        File dir = new File("build/reports/watermark");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        int n = 0;
        for (WatermarkSpec.Layout layout : WatermarkSpec.Layout.values())
            for (String set : new String[]{"default", "all", "date_time", "exif", "light"})
                for (boolean portrait : new boolean[]{false, true}) {
                    WatermarkSpec s = new WatermarkSpec();
                    if (set.equals("all")) s = s.only(true, true, true, true, true, true, true, true);
                    else if (set.equals("date_time")) s = single(s, "time");
                    else if (set.equals("exif")) s = s.only(false, true, true, true, true, true, false, true);
                    else if (set.equals("light")) {
                        s = s.only(true, true, true, true, true, true, true, true);
                        s.colour = WatermarkSpec.Colour.BLACK;
                        s.position = WatermarkSpec.Position.BOTTOM_RIGHT;
                    }
                    s.layout = layout;
                    if (layout == WatermarkSpec.Layout.BAR || layout == WatermarkSpec.Layout.FRAME) s.size = 6;
                    int w = portrait ? 1200 : 1600, h = portrait ? 1600 : 1200;
                    Bitmap b = WatermarkPreviewPreference.render(RuntimeEnvironment.getApplication(), s, true, w, h, NOW, "vivo X200 Ultra");
                    String name = layout.name().toLowerCase(Locale.ROOT) + "_" + set + "_" + (portrait ? "portrait" : "landscape") + ".png";
                    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                        assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, out));
                    }
                    b.recycle();
                    n++;
                }
        assertEquals(40, n);
    }

    /** Every setting the watermark reads is a row of its page, with the defaults the code assumes. */
    @Test public void thePageHasEveryRowWithTheCodesDefaults() throws Exception {
        File f = new File("src/main/res/xml/preferences.xml");
        if (!f.exists()) f = new File("app/src/main/res/xml/preferences.xml");
        String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        String[][] defaults = {{WatermarkSpec.KEY_LAYOUT, "text"}, {WatermarkSpec.KEY_POSITION, "bl"}, {WatermarkSpec.KEY_SHOW_TEXT, "true"},
                {WatermarkSpec.KEY_TEXT, "SCAMERA"}, {WatermarkSpec.KEY_SHOW_DEVICE, "false"}, {WatermarkSpec.KEY_SHOW_LENS, "false"},
                {WatermarkSpec.KEY_SHOW_APERTURE, "false"}, {WatermarkSpec.KEY_SHOW_SHUTTER, "false"}, {WatermarkSpec.KEY_SHOW_ISO, "false"},
                {WatermarkSpec.KEY_SHOW_TIME, "false"}, {WatermarkSpec.KEY_DATE_FORMAT, "datetime"}, {WatermarkSpec.KEY_LOGO, "true"},
                {WatermarkSpec.KEY_FONT, "scam"}, {WatermarkSpec.KEY_SIZE, "7"}, {WatermarkSpec.KEY_COLOR, "auto"},
                {WatermarkSpec.KEY_OPACITY, "100"}, {WatermarkSpec.KEY_MARGIN, "3"}};
        assertEquals(WatermarkSpec.KEYS.length - 1, defaults.length); // all but the switch (pref_show_watermark_key)
        for (String[] d : defaults) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<[^>]*android:key=\"" + d[0] + "\"[^>]*>").matcher(xml);
            assertTrue(d[0], m.find());
            assertTrue(d[0] + ": " + m.group(), m.group().contains("android:defaultValue=\"" + d[1] + "\""));
            assertFalse(d[0] + " twice", m.find());
        }
        assertFalse(xml.contains("pref_watermark_line1"));
        assertFalse(xml.contains("SHOT ON"));
    }

    /** The settings preview draws with the shot's code: the switch off leaves the sample alone. */
    @Test public void thePreviewIsTheShotsDrawing() {
        WatermarkSpec s = new WatermarkSpec();
        s.layout = WatermarkSpec.Layout.BADGE;
        Bitmap off = WatermarkPreviewPreference.render(RuntimeEnvironment.getApplication(), s, false, 400, 300, NOW, "X");
        Bitmap on = WatermarkPreviewPreference.render(RuntimeEnvironment.getApplication(), s, true, 400, 300, NOW, "X");
        Bitmap direct = WatermarkPreviewPreference.render(RuntimeEnvironment.getApplication(), s, false, 400, 300, NOW, "X");
        WatermarkPainter.drawOn(direct, s, WatermarkText.of(s, WatermarkText.Info.sample("X", NOW)), fonts());
        assertFalse(off.sameAs(on));
        assertTrue(on.sameAs(direct));
    }
}
