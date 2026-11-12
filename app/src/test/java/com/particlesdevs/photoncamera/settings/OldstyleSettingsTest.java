package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe;
import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleLive;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P630h (owner: «Добавь больше настроек для Oldstyle»): the rows of «Oldstyle Тон» as a model - every row of the page in its
 * sub-category with a range (SettingsNumericRules = the slider's), a default inside it and RU / EN title and help; untouched (and
 * put back to the XML default) the numbers are exactly the behaviour before the rows; each row reaches one number of
 * OldstyleAe.Params and no other; a value past an end is the end; and with any row at either end of its range - one at a time, all
 * together, and mixed at random - the metering, the plan of the capture and of the merge and the viewfinder's live tone stay
 * finite and inside their limits on scenes from black to burnt out (the module's own ends: tools/check_tone_port.py, section 7).
 */
public class OldstyleSettingsTest {
    private static final String K = "pref_scam_hybrid_";

    /** A row of the page: its key without pref_scam_hybrid_, its category, the slider's ends and default (null: a switch or a list). */
    static final class Row {
        String key, category, def;
        Float min, max;
        boolean integer;
    }

    static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    static String group() throws IOException {
        final String xml = read("src/main/res/xml/preferences.xml");
        final int at = xml.indexOf("<PreferenceScreen android:key=\"oldstyle_tone_screen\"");
        assertTrue(at > 0);
        return xml.substring(at, xml.indexOf("</PreferenceScreen>", at));
    }

    static String attr(String tag, String name) {
        final Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    static List<Row> rows() throws IOException {
        final List<Row> out = new ArrayList<>();
        String category = null;
        final Matcher m = Pattern.compile("<(/?)([A-Za-z.]+)([^>]*)>").matcher(group());
        while (m.find()) {
            final String tag = m.group(2), body = m.group(3);
            if (tag.equals("PreferenceCategory")) {
                category = m.group(1).isEmpty() ? attr(body, "android:key") : null;
                continue;
            }
            final String key = attr(body, "android:key");
            if (key == null || !key.startsWith(K + "oldstyle_")) continue;
            final Row r = new Row();
            r.key = key.substring(K.length());
            r.category = category;
            r.def = attr(body, "android:defaultValue");
            if (tag.endsWith("UniversalSeekBarPreference")) {
                r.min = Float.parseFloat(attr(body, "app:minValue"));
                r.max = Float.parseFloat(attr(body, "app:maxValue"));
                r.integer = "false".equals(attr(body, "app:isFloat"));
            }
            out.add(r);
        }
        return out;
    }

    /** A test's values of the rows: numbers and texts by key; a key not there is the caller's fallback. */
    static final class Values implements OldstyleMode.Source {
        final Map<String, Object> map = new HashMap<>();

        @Override public float number(String key, float fallback) {
            final Object v = map.get(key);
            return v instanceof Number ? ((Number) v).floatValue() : v instanceof String ? Float.parseFloat((String) v) : fallback;
        }

        @Override public boolean flag(String key, boolean fallback) {
            final Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : v instanceof String ? Boolean.parseBoolean((String) v) : fallback;
        }

        @Override public String text(String key, String fallback) {
            final Object v = map.get(key);
            return v == null ? fallback : String.valueOf(v);
        }

        Values with(String key, Object value) {
            map.put(key, value);
            return this;
        }
    }

    /** The public numbers of a Params, by name. */
    static Map<String, Object> fields(OldstyleAe.Params p) throws IllegalAccessException {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : OldstyleAe.Params.class.getFields()) if (!Modifier.isStatic(f.getModifiers())) out.put(f.getName(), f.get(p));
        return out;
    }

    static List<String> differing(OldstyleAe.Params a, OldstyleAe.Params b) throws IllegalAccessException {
        final Map<String, Object> x = fields(a), y = fields(b);
        final List<String> out = new ArrayList<>();
        for (String name : x.keySet()) if (!x.get(name).equals(y.get(name))) out.add(name);
        return out;
    }

    @Test public void thePagesRowsCategoriesRangesAndTexts() throws IOException {
        final String group = group();
        // the version, «Сбросить Oldstyle» right under it (in sight when the page opens), then the sub-categories in their order
        int at = group.indexOf("android:key=\"" + OldstyleMode.VERSION + "\"");
        assertTrue(at > 0);
        final int reset = group.indexOf("<Preference android:key=\"" + OldstyleMode.RESET + "\" android:persistent=\"false\"");
        assertTrue(reset > at && reset < group.indexOf("<PreferenceCategory "));
        assertFalse("nothing between the version and the reset row", group.substring(at + 20, reset).contains("android:key=\"pref_scam_hybrid_oldstyle_"));
        for (String category : OldstyleMode.CATEGORIES) {
            final int next = group.indexOf("<PreferenceCategory android:key=\"" + category + "\"");
            assertTrue(category, next > at);
            at = next;
        }
        assertEquals(OldstyleMode.CATEGORIES.length, group.split("<PreferenceCategory ", -1).length - 1);
        final Set<String> known = new HashSet<>();
        known.addAll(Arrays.asList(OldstyleMode.V1_ROWS));
        known.addAll(Arrays.asList(OldstyleMode.V2_ROWS));
        known.addAll(Arrays.asList(OldstyleMode.COMMON_ROWS));
        assertEquals("no key twice", OldstyleMode.V1_ROWS.length + OldstyleMode.V2_ROWS.length + OldstyleMode.COMMON_ROWS.length, known.size());
        final String ru = read("src/main/res/values-ru/strings_settings.xml"), en = read("src/main/res/values/strings_settings.xml");
        final Set<String> seen = new HashSet<>();
        for (Row r : rows()) {
            if (r.key.equals("oldstyle_version")) continue;
            assertTrue(r.key + " is in a sub-category", r.category != null && Arrays.asList(OldstyleMode.CATEGORIES).contains(r.category));
            assertTrue(r.key + " is a known row", known.contains(K + r.key));
            assertTrue(r.key, seen.add(K + r.key));
            assertNotNull(r.key + " has a default", r.def);
            if (r.min != null) {
                final double[] bounds = SettingsNumericRules.bounds(K + r.key);
                assertNotNull(r.key + " has a range", bounds);
                assertEquals(r.key, r.min, (float) bounds[0], 0f);
                assertEquals(r.key, r.max, (float) bounds[1], 0f);
                assertEquals(r.key, r.integer, bounds[2] > 0);
                final float def = Float.parseFloat(r.def);
                assertTrue(r.key + " default inside its range", def >= r.min && def <= r.max);
            }
            // a title and a help text in both languages, the help more than a name (plain words: what it does to the photo)
            for (String lang : new String[]{ru, en}) {
                for (String part : new String[]{"title", "summary"}) {
                    final Matcher m = Pattern.compile("<string name=\"prefs_scam_hybrid_" + r.key + "_" + part + "\">([^<]*)</string>").matcher(lang);
                    assertTrue(r.key + " " + part, m.find());
                    assertTrue(r.key + " " + part, m.group(1).trim().length() >= (part.equals("title") ? 3 : 40));
                }
            }
        }
        assertEquals("every known row is on the page", known, seen);
        for (String lang : new String[]{ru, en}) {
            for (String category : OldstyleMode.CATEGORIES)
                assertTrue(category, lang.contains("<string name=\"prefs_" + category + "_title\">"));
            assertTrue(lang.contains("<string name=\"prefs_oldstyle_tone_reset_title\">"));
            assertTrue(lang.contains("<string name=\"prefs_oldstyle_tone_reset_summary\">"));
        }
        assertTrue(ru.contains("<string name=\"prefs_oldstyle_tone_reset_title\">Сбросить Oldstyle</string>"));
        // a row of one version carries the version in its key (SettingsAvailability greys by it)
        for (String key : OldstyleMode.V1_ROWS) assertTrue(key, SettingsAvailability.oldstyleV1Only(key));
        for (String key : OldstyleMode.V2_ROWS) assertTrue(key, SettingsAvailability.oldstyleV2Only(key));
        for (String key : OldstyleMode.COMMON_ROWS)
            assertFalse(key, SettingsAvailability.oldstyleV1Only(key) || SettingsAvailability.oldstyleV2Only(key));
    }

    private static SettingsAvailability with(Object... pairs) {
        final Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(K + "oldstyle_" + pairs[i], pairs[i + 1]);
        return new SettingsAvailability(values);
    }

    /** A row another row of the page turns off is greyed with the reason; with the defaults every row of the chosen version acts. */
    @Test public void aRowAnotherRowTurnsOffIsGreyed() {
        final SettingsAvailability plain = with(), v2 = with("version", "v2");
        for (String key : OldstyleMode.COMMON_ROWS) assertEquals(key, null, plain.reason(key));
        for (String key : OldstyleMode.V1_ROWS) assertEquals(key, null, plain.reason(key));
        for (String key : OldstyleMode.V2_ROWS) assertEquals(key, null, v2.reason(key));
        final String o = K + "oldstyle_";
        // «HDR» by hand: the thresholds of the decision do not decide; off: nothing of the two exposures acts
        for (String mode : new String[]{"on", "off"}) {
            final SettingsAvailability a = with("hdr", mode), b = with("hdr", mode, "version", "v2");
            assertTrue(a.reason(o + "v1_hdr_min").contains("HDR"));
            assertTrue(a.reason(o + "v1_hdr_max").contains("HDR"));
            assertTrue(b.reason(o + "v2_hdr_min").contains("HDR"));
            assertTrue(b.reason(o + "v2_hdr_forgive").contains("HDR"));
            assertEquals(mode, mode.equals("off"), a.reason(o + "v1_hdr_cap") != null);
            assertEquals(mode, mode.equals("off"), b.reason(o + "v2_hdr_max") != null);
            assertEquals(mode, mode.equals("off"), a.reason(o + "fusion_base") != null);
            assertEquals(null, a.reason(o + "ev"));
            assertEquals(null, a.reason(o + "max_gain"));
            assertEquals(null, a.reason(o + "hdr"));
        }
        // the other version's reason comes first
        assertTrue(with("hdr", "on").reason(o + "v2_hdr_min").contains("V2"));
        final SettingsAvailability noHaze = with("dehaze", false), noTransfer = with("v1_lowfreq", false), noTone = with("vf_tone", false);
        for (String k : new String[]{"dehaze_max_dark", "dehaze_percentile", "dehaze_corner", "dehaze_shading"}) assertNotNull(k, noHaze.reason(o + k));
        assertEquals(null, noHaze.reason(o + "dehaze"));
        for (String k : new String[]{"v1_lowfreq_threshold", "v1_lowfreq_strength", "v1_lowfreq_chroma"}) assertNotNull(k, noTransfer.reason(o + k));
        assertEquals(null, noTransfer.reason(o + "v1_lowfreq"));
        assertEquals(null, noTransfer.reason(o + "v1_ccm"));
        for (String k : new String[]{"vf_ev", "vf_match", "vf_smooth"}) assertNotNull(k, noTone.reason(o + k));
        assertEquals(null, noTone.reason(o + "vf_tone"));
        assertEquals(null, noTone.reason(o + "curve_mid"));
    }

    @Test public void untouchedAndAtTheirDefaultsTheRowsAreTheBehaviourBefore() throws Exception {
        for (boolean v2 : new boolean[]{false, true}) {
            final OldstyleAe.Params plain = new OldstyleAe.Params();
            plain.v31 = v2;
            assertEquals(new ArrayList<String>(), differing(plain, OldstyleMode.params(v2, new Values())));
            // every row stored at its XML default (a slider put back by a double tap, the page's reset): the exact numbers still -
            // 0.9756 is fusion_gains' 0.9756097793579102, 6.8 plan27's 6.803999900817871, 16.7 ms 1/60 s
            final Values defaults = new Values();
            for (Row r : rows()) defaults.with(r.key, r.def);
            final OldstyleAe.Params stored = OldstyleMode.params(v2, defaults);
            assertEquals(new ArrayList<String>(), differing(plain, stored));
            assertEquals(OldstyleAe.FUSION_BASE, stored.fusionBase, 0f);
            assertEquals(OldstyleAe.V1_HDR_MAX, stored.v1HdrMax, 0f);
            assertEquals(OldstyleAe.HIGH_PERCENTILE, stored.highPercentile, 0f);
            assertEquals(0.6f, stored.memoryShare, 0f);
            assertEquals(16_666_667L, com.particlesdevs.photoncamera.capture.HybridPlan.oldstyleFloorNs(stored.shutterFloorMs));
        }
        // the numbers before the rows, by name (a default changed in OldstyleAe.Params would change the photo of every user)
        final OldstyleAe.Params p = new OldstyleAe.Params();
        assertEquals(OldstyleAe.HDR_AUTO, p.hdrMode);
        assertEquals(1.45f, p.v1HdrMin, 0f);
        assertEquals(6.804f, p.v1HdrMax, 0f);
        assertEquals(3.5f, p.v1RatioCap, 0f);
        assertEquals(1.33f, p.v2HdrMin, 0f);
        assertEquals(8f, p.v2RatioMax, 0f);
        assertEquals(1.4f, p.v2Forgiveness, 0f);
        assertEquals(0.9756097793579102f, p.fusionBase, 0f);
        assertEquals(0.997f, p.highPercentile, 0f);
        assertEquals(0.95f, p.shortWhite, 0f);
        assertEquals(0.47f, p.centerSigma, 0f);
        assertEquals(16f, p.maxGain, 0f);
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f, 1f, 1f}, new float[]{p.baseEv, p.shadowEv, p.curveMid, p.curveContrast, p.ccmStrength,
                p.lfStrength}, 0f);
        assertTrue(p.lfChroma && p.vfTone && p.dehaze && p.lowFrequency);
        assertEquals(0, p.burstFrames);
        assertArrayEquals(new float[]{0.7f, 1000f / 60f, 0f, 1f, 0.5f, 0.6f}, new float[]{p.burstSeconds, p.shutterFloorMs, p.vfEv, p.vfMatch,
                p.vfSmooth, p.memoryShare}, 0f);
    }

    @Test public void eachRowReachesOneNumberAndNoOther() throws Exception {
        final OldstyleAe.Params plain = OldstyleMode.params(false, new Values());
        final Set<String> reached = new HashSet<>();
        for (Row r : rows()) {
            if (r.key.equals("oldstyle_version")) continue;
            final List<Object> values = new ArrayList<>();
            if (r.min != null) {
                values.add(r.min);
                values.add(r.max);
            } else if (r.def.equals("true") || r.def.equals("false")) {
                values.add(!Boolean.parseBoolean(r.def));
            } else if (r.key.equals("oldstyle_hdr")) {
                values.add("on");
                values.add("off");
            } else {
                continue;   // V2's levels: OldstyleMode.v2Postview, not a number of Params
            }
            final Set<String> moved = new HashSet<>();
            for (Object v : values) {
                final float def = r.min != null ? Float.parseFloat(r.def) : 0f;
                if (r.min != null && Math.abs(((Float) v) - def) < 1e-6f) continue;   // an end that is the default moves nothing
                final List<String> d = differing(plain, OldstyleMode.params(false, new Values().with(r.key, v)));
                assertEquals(r.key + " = " + v + " moves one number: " + d, 1, d.size());
                moved.addAll(d);
            }
            assertEquals(r.key + " always the same number", 1, moved.size());
            assertTrue(r.key + " shares " + moved + " with another row", reached.add(moved.iterator().next()));
        }
        // and every number a row was promised for is reached (the version is the caller's argument, the style's exposure ShotLook's)
        final Set<String> all = new HashSet<>(fields(plain).keySet());
        all.remove("v31");
        all.remove("exposureEv");
        assertEquals(all, reached);
    }

    @Test public void aValuePastAnEndIsTheEnd() throws Exception {
        for (Row r : rows()) {
            if (r.min == null) continue;
            final OldstyleAe.Params low = OldstyleMode.params(false, new Values().with(r.key, -1e9f)),
                    atMin = OldstyleMode.params(false, new Values().with(r.key, r.min)),
                    high = OldstyleMode.params(false, new Values().with(r.key, 1e9f)),
                    atMax = OldstyleMode.params(false, new Values().with(r.key, r.max)),
                    nan = OldstyleMode.params(false, new Values().with(r.key, Float.NaN));
            assertEquals(r.key, new ArrayList<String>(), differing(low, atMin));
            assertEquals(r.key, new ArrayList<String>(), differing(high, atMax));
            assertEquals(r.key, new ArrayList<String>(), differing(nan, OldstyleMode.params(false, new Values())));
        }
    }

    // ---- the ends of the ranges through the exposure code ------------------------------------------------------------------------

    /** Linear scenes 32 x 24 (1 = white): black, a dark room, a grey card, a backlit window, a burnt-out sky, one hot pixel, noise. */
    static float[] scene(int n) {
        final float[] out = new float[32 * 24 * 3];
        final Random random = new Random(11);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++) {
            final double X = x / 31.0, Y = y / 23.0;
            double v;
            switch (n) {
                case 0: v = 0; break;
                case 1: v = 0.004 + 0.01 * X; break;
                case 2: v = 0.18; break;
                case 3: v = X > 0.6 && Y < 0.5 ? 1.0 : 0.02 + 0.05 * Y; break;
                case 4: v = 1.0; break;
                case 5: v = x == 7 && y == 5 ? 1.0 : 0.05; break;
                default: v = random.nextDouble() * random.nextDouble();
            }
            for (int c = 0; c < 3; c++) out[(y * 32 + x) * 3 + c] = (float) v * (c == 0 ? 1f : c == 1 ? 0.9f : 0.7f);
        }
        return out;
    }

    private static void finite(String what, float v) {
        assertTrue(what + " = " + v, Float.isFinite(v));
    }

    /** One setting of the rows through the meter, the capture's plan, the merge's plan and the viewfinder, on every scene. */
    private static void exercise(String what, Values values) {
        for (boolean v2 : new boolean[]{false, true}) {
            for (String hdr : new String[]{"auto", "on", "off"}) {
                final OldstyleAe.Params p = OldstyleMode.params(v2, new Values().with("oldstyle_hdr", hdr));
                final OldstyleAe.Params q = OldstyleMode.params(v2, values);
                if (!values.map.containsKey("oldstyle_hdr")) q.hdrMode = p.hdrMode;
                for (int n = 0; n < 7; n++) {
                    final String tag = what + " V" + (v2 ? 2 : 1) + " hdr " + q.hdrMode + " scene " + n;
                    final float[] rgb = scene(n);
                    for (boolean capture : new boolean[]{true, false}) {
                        final OldstyleAe.Meter m = OldstyleAe.meter(rgb, 32, 24, q, 1f, capture);
                        finite(tag + " S*", m.shortRel);
                        finite(tag + " L*", m.longRel);
                        assertTrue(tag + " S* " + m.shortRel + " L* " + m.longRel, m.shortRel > 0f && m.longRel > 0f);
                        assertTrue(tag + " F " + m.fractionLong, m.fractionLong >= 0f && m.fractionLong <= 1f);
                    }
                    final OldstyleAe.Meter m = OldstyleAe.meter(rgb, 32, 24, q, 1f, true);
                    final OldstyleAe.Capture c = OldstyleAe.capture(m, q, 1.0, 0.33f);
                    assertTrue(tag + " capture " + c, c.shortTet > 0 && c.longTet > 0 && c.frameTet > 0 && Double.isFinite(c.frameTet)
                            && Double.isFinite(c.shortTet) && Double.isFinite(c.longTet));
                    if (q.hdrMode == OldstyleAe.HDR_OFF) assertFalse(tag, c.hdr);
                    if (q.hdrMode == OldstyleAe.HDR_ON) assertEquals(tag, m.longRel > m.shortRel, c.hdr);
                    // the merge of a burst exposed at the plan (and of one the sensor could not give: a quarter of it)
                    for (double given : new double[]{1.0, 0.25}) {
                        final double tet = c.frameTet * given;
                        final float[] merged = new float[rgb.length];
                        for (int i = 0; i < rgb.length; i++) merged[i] = (float) Math.min(1.0, rgb[i] * tet);
                        for (OldstyleAe.Capture from : new OldstyleAe.Capture[]{c, null}) {
                            final OldstyleAe.Plan plan = OldstyleAe.plan(merged, 32, 24, q, 1f, from, tet);
                            finite(tag + " gS", plan.gainShort);
                            finite(tag + " gL", plan.gainLong);
                            assertTrue(tag + " gains " + plan.gainShort + " / " + plan.gainLong + " within (0, M]", plan.gainShort > 0f
                                    && plan.gainLong > 0f && plan.gainShort <= q.maxGain * 1.0011f && plan.gainLong <= q.maxGain * 1.0011f);
                            if (plan.hdr) assertTrue(tag + " short over long", plan.gainShort <= plan.gainLong * 1.0001f);
                            else assertEquals(tag, plan.gainShort, plan.gainLong, 0f);
                            if (q.hdrMode == OldstyleAe.HDR_OFF) assertFalse(tag, plan.hdr);
                            assertTrue(tag + " input scale", plan.inputScale > 0f && plan.inputScale <= 1f);
                            // the module's own limits of what the tone hands it (check_tone_port.py runs it at these)
                            // (L / S at most 16 by the rows, the base at least 0.95: 16 / 0.95 ^ 16 = 36.4)
                            assertTrue(tag + " ratio " + plan.gainLong / plan.gainShort, plan.gainLong / plan.gainShort < 37f);
                        }
                    }
                    for (float v : q.post()) finite(tag + " post", v);
                    // the viewfinder: a plan, a level, the gains to draw with
                    final OldstyleLive live = new OldstyleLive();
                    c.meterRgb = rgb;
                    c.meterW = 32;
                    c.meterH = 24;
                    live.plan(1_000_000_000L, v2 ? 2 : 1, c, q, 0.4f, null);
                    live.level(1_000_000_000L, 0.2f);
                    live.plan(1_300_000_000L, v2 ? 2 : 1, c, q, 0.4f, null);
                    final float[] g = live.gains(1_400_000_000L);
                    if (!q.vfTone || !(c.meterMean > 0f)) continue;
                    assertNotNull(tag + " viewfinder gains", g);
                    for (int i = 1; i <= 2; i++)
                        assertTrue(tag + " screen gain " + g[i], g[i] >= OldstyleLive.MIN_GAIN && g[i] <= OldstyleLive.MAX_GAIN);
                    assertTrue(tag + " white boost " + g[4], g[4] >= 1f && g[4] <= OldstyleLive.WHITE_BOOST_MAX);
                }
            }
        }
    }

    @Test public void everyRowAtBothEndsOfItsRange() throws IOException {
        exercise("defaults", new Values());
        final List<Row> rows = new ArrayList<>();
        for (Row r : rows()) if (r.min != null) rows.add(r);
        assertTrue(rows.size() >= 30);
        for (Row r : rows) {
            exercise(r.key + " at its least", new Values().with(r.key, r.min));
            exercise(r.key + " at its most", new Values().with(r.key, r.max));
        }
    }

    @Test public void allRowsAtTheirEndsTogether() throws IOException {
        final List<Row> rows = new ArrayList<>();
        for (Row r : rows()) if (r.min != null) rows.add(r);
        final Values least = new Values(), most = new Values();
        for (Row r : rows) {
            least.with(r.key, r.min);
            most.with(r.key, r.max);
        }
        exercise("all at their least", least);
        exercise("all at their most", most);
        // and mixed: each row at one of its ends, the switches either way (seeded: the same mixes every run)
        final Random random = new Random(630);
        for (int mix = 0; mix < 48; mix++) {
            final Values v = new Values();
            for (Row r : rows()) {
                if (r.min != null) v.with(r.key, random.nextBoolean() ? r.min : r.max);
                else if (r.def.equals("true") || r.def.equals("false")) v.with(r.key, random.nextBoolean());
            }
            exercise("mix " + mix, v);
        }
    }
}
