package com.particlesdevs.photoncamera.processing.color;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * P487: the film page's DIR / halation / development / spectral-model controls - upstream defaults leave the packet and the LUT
 * keys as before, every control reaches its own spektrafilm parameter, the colour ones reach the preview's table key and survive
 * the chain key's round trip, the spatial ones do not change the colour table.
 */
public class FilmAdvancedTest {
    @BeforeClass public static void assets() { FilmSpectralTest.assets(); }

    private static FilmSim.Settings settings(String stock, String format, FilmAdvanced advanced) {
        return new FilmSim.Settings(FilmSim.stock(stock), FilmSim.print("kodak_portra_endura"), FilmSim.format(format),
                0f, 1f, 0f, 0f, 0f, .25f, 0f, 0f, 1f, "", .5f, .6f, advanced);
    }

    private static FilmAdvanced with(String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return FilmAdvanced.read((k, d) -> m.containsKey(k) ? m.get(k) : d);
    }

    private static ByteBuffer packet(FilmSim.Settings s) {
        return FilmReferenceParameters.from(s, false, 1).buffer();
    }

    @Test public void untouchedControlsAreUpstreamDefaultsAndKeepTheOldKeys() {
        final FilmAdvanced none = with();
        assertTrue(none.isDefault());
        assertEquals("", none.lutToken());
        assertEquals("", none.describe());
        final ByteBuffer before = packet(new FilmSim.Settings(FilmSim.stock("kodak_portra_400"), FilmSim.print("kodak_portra_endura"),
                FilmSim.format("135"), 0f, 1f, 0f, 0f, 0f, .25f, 0f, 0f, 1f, "", .5f, .6f));
        final ByteBuffer now = packet(settings("kodak_portra_400", "135", none));
        assertEquals("an untouched recipe's packet is byte-identical", before, now);
        final ByteBuffer fresh = new FilmReferenceParameters().buffer();
        for (int offset : new int[]{12, 100}) assertEquals(fresh.getInt(offset), now.getInt(offset));
        for (int offset : new int[]{104, 112, 116, 200, 204, 208, 212, 216, 444, 452, 480, 484, 488})
            assertEquals("offset " + offset, fresh.getFloat(offset), now.getFloat(offset), 0f);
        assertEquals("Hanatos 2026 stays the default", 2, now.getInt(12));
        assertEquals(8, settings("kodak_portra_400", "135", none).lutKey().split("\\|", -1).length);
    }

    @Test public void everyControlReachesItsOwnRendererParameter() {
        final FilmAdvanced a = with(FilmAdvanced.DIR_SPREAD.key, "35", FilmAdvanced.DIR_TAIL.key, "400", FilmAdvanced.DIR_TAIL_WEIGHT.key, "0.20",
                FilmAdvanced.DIR_SAME.key, "0.50", FilmAdvanced.DIR_INTER.key, "1.50", FilmAdvanced.SCATTER.key, "0.50",
                FilmAdvanced.SCATTER_SIZE.key, "0.70", FilmAdvanced.HALO.key, "1.50", FilmAdvanced.HALO_SIZE.key, "0.80",
                FilmAdvanced.BOOST.key, "1.20", FilmAdvanced.BOOST_RANGE.key, "0.60", FilmAdvanced.PROTECT.key, "3.00",
                FilmAdvanced.PUSH.key, "0.10", FilmAdvanced.PUSH_MODE_KEY, "experimental", FilmAdvanced.DEV_GAMMA.key, "1.50",
                FilmAdvanced.BLEACH.key, "0.40", FilmAdvanced.LEUCO.key, "0.00", FilmAdvanced.SPECTRAL_KEY, "mallett2019",
                FilmAdvanced.DIR_GAMMA_KEYS[0], "0.400", FilmAdvanced.DIR_GAMMA_KEYS[8], "0.123");
        final ByteBuffer p = packet(settings("kodak_portra_400", "135", a));
        assertEquals("Mallett 2019", 1, p.getInt(12));
        assertEquals("experimental push / pull", 1, p.getInt(100));
        assertEquals(.1f, p.getFloat(104), 0f);
        assertEquals(.4f, p.getFloat(112), 0f);
        assertEquals(0f, p.getFloat(116), 0f);
        assertEquals("development gamma x the film contrast", (float) Math.pow(2, .25) * 1.5f, p.getFloat(124), 0f);
        assertEquals(35f, p.getFloat(200), 0f);
        assertEquals(400f, p.getFloat(204), 0f);
        assertEquals(.2f, p.getFloat(208), 0f);
        assertEquals(.5f, p.getFloat(212), 0f);
        assertEquals(1.5f, p.getFloat(216), 0f);
        assertEquals(.4f, p.getFloat(220), 0f);
        assertEquals("an untouched gamma keeps the stock's", new FilmReferenceParameters().buffer().getFloat(224), p.getFloat(224), 0f);
        assertEquals(.123f, p.getFloat(252), 0f);
        assertEquals("the halation on", 1, p.getInt(436));
        assertEquals("scatter x «Ореол»", .5f, p.getFloat(440), 0f);
        assertEquals(.7f, p.getFloat(444), 0f);
        assertEquals("halation x «Ореол»", 1.5f, p.getFloat(448), 0f);
        assertEquals(.8f, p.getFloat(452), 0f);
        assertEquals(1.2f, p.getFloat(480), 0f);
        assertEquals(.6f, p.getFloat(484), 0f);
        assertEquals(3f, p.getFloat(488), 0f);
        assertFalse(a.describe().isEmpty());
        // the frame format «off» still has no spread (the inhibitor works in micrometres of film)
        final ByteBuffer off = packet(settings("kodak_portra_400", "off", a));
        assertEquals(0f, off.getFloat(200), 0f);
        assertEquals(0f, off.getFloat(204), 0f);
    }

    @Test public void slidesKeepTheirOwnGammasUnlessSetAndValuesAreClamped() {
        final float[] provia = FilmReferenceParameters.dirGamma("fujifilm_provia_100f");
        final ByteBuffer p = packet(settings("fujifilm_provia_100f", "135", with()));
        for (int i = 0; i < 9; i++) assertEquals(provia[i], p.getFloat(220 + 4 * i), 0f);
        assertEquals(.336f, FilmReferenceParameters.dirGamma("kodak_portra_400")[0], 0f);
        final FilmAdvanced wild = with(FilmAdvanced.PUSH.key, "9", FilmAdvanced.DEV_GAMMA.key, "0", FilmAdvanced.DIR_GAMMA_KEYS[1], "5",
                FilmAdvanced.SPECTRAL_KEY, "hanatos2024", FilmAdvanced.BLEACH.key, "abc");
        assertEquals(2f, wild.get(FilmAdvanced.PUSH), 0f);
        assertEquals(.5f, wild.get(FilmAdvanced.DEV_GAMMA), 0f);
        assertEquals(1f, wild.gamma(1), 0f);
        assertEquals("an unknown model is the default one", 2, wild.rgbToRawMethod());
        assertEquals(0f, wild.get(FilmAdvanced.BLEACH), 0f);
    }

    /** P487: Hanatos 2025 (method 0) with every film's archived bandpass in the generated native profiles. */
    @Test public void hanatos2025HasEveryFilmsBandpassAndItsOwnKey() throws Exception {
        final FilmAdvanced h25 = with(FilmAdvanced.SPECTRAL_KEY, "hanatos2025");
        assertEquals(0, h25.rgbToRawMethod());
        final FilmSim.Settings s = settings("kodak_portra_400", "135", h25);
        assertEquals(0, packet(s).getInt(12));
        assertEquals(0, FilmSim.Settings.forLut(s.lutKey(), 1f).advanced.rgbToRawMethod());
        final String cpp = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/cpp/third_party/spektrafilm/SpektraGeneratedProfileCurves.cpp")), java.nio.charset.StandardCharsets.UTF_8);
        final String table = cpp.substring(cpp.indexOf("ProfileCurveSet film_profiles[]"), cpp.indexOf("ProfileCurveSet paper_profiles[]"));
        final java.util.regex.Matcher row = java.util.regex.Pattern.compile("\\{\"(\\w+)\", [^}]*_log_sensitivity, (\\w+), ").matcher(table);
        int films = 0;
        while (row.find()) {
            assertEquals(row.group(1), "film_" + films + "_" + row.group(1) + "_bandpass_hanatos2025", row.group(2));
            assertTrue(row.group(1), cpp.contains("float " + row.group(2) + "[] = {"));
            films++;
        }
        assertEquals(20, films);
    }

    @Test public void colourControlsReachThePreviewTableAndSurviveTheChainKey() {
        final FilmSim.Settings base = settings("kodak_gold_200", "135", with());
        final String baseKey = FilmPreviewLut.key(base, 33, FilmPreviewLut.Domain.SCENE);
        final String[][] colour = {{FilmAdvanced.SPECTRAL_KEY, "mallett2019"}, {FilmAdvanced.SPECTRAL_KEY, "hanatos2025"}, {FilmAdvanced.PUSH.key, "0.50"},
                {FilmAdvanced.DEV_GAMMA.key, "1.20"}, {FilmAdvanced.BLEACH.key, "0.30"}, {FilmAdvanced.DIR_INTER.key, "1.30"},
                {FilmAdvanced.DIR_GAMMA_KEYS[4], "0.250"}};
        for (String[] kv : colour) {
            final FilmSim.Settings s = settings("kodak_gold_200", "135", with(kv));
            assertNotEquals(kv[0], base.lutKey(), s.lutKey());
            assertNotEquals(kv[0], baseKey, FilmPreviewLut.key(s, 33, FilmPreviewLut.Domain.SCENE));
            // the viewfinder / page rebuild the table from the key alone (FilmChain.lut / loadLut)
            final FilmSim.Settings back = FilmSim.Settings.forLut(s.lutKey(), s.strength);
            assertEquals(kv[0], s.lutKey(), back.lutKey());
            assertEquals(kv[0], FilmPreviewLut.key(s, 33, FilmPreviewLut.Domain.SCENE), FilmPreviewLut.key(back, 33, FilmPreviewLut.Domain.SCENE));
            final String chain = FilmChain.current(s, "", 0f).key.substring(FilmChain.PREFIX.length());
            final String[] parts = chain.split("\\|", -1);
            assertEquals(9, FilmChain.lutFields(parts));
        }
        assertEquals(8, FilmChain.lutFields(FilmChain.current(base, "", 0f).key.substring(FilmChain.PREFIX.length()).split("\\|", -1)));
        final String[][] spatial = {{FilmAdvanced.DIR_SPREAD.key, "50"}, {FilmAdvanced.DIR_TAIL.key, "500"},
                {FilmAdvanced.DIR_TAIL_WEIGHT.key, "0.30"}, {FilmAdvanced.SCATTER.key, "0.20"}, {FilmAdvanced.SCATTER_SIZE.key, "1.50"},
                {FilmAdvanced.HALO.key, "0.30"}, {FilmAdvanced.HALO_SIZE.key, "1.70"}, {FilmAdvanced.BOOST.key, "2.00"},
                {FilmAdvanced.BOOST_RANGE.key, "0.80"}, {FilmAdvanced.PROTECT.key, "6.00"}};
        for (String[] kv : spatial) {
            final FilmSim.Settings s = settings("kodak_gold_200", "135", with(kv));
            assertEquals(kv[0], base.lutKey(), s.lutKey());
            assertEquals(kv[0], baseKey, FilmPreviewLut.key(s, 33, FilmPreviewLut.Domain.SCENE));
            assertNotEquals(kv[0], packet(base), packet(s));
        }
    }

    @Test public void theHalationSizeAndAmountReachThePreviewAndLegacyHalation() {
        final FilmSim.Settings base = settings("kodak_gold_200", "135", with());
        final FilmSim.Settings s = settings("kodak_gold_200", "135", with(FilmAdvanced.HALO.key, "2.00", FilmAdvanced.HALO_SIZE.key, "0.50"));
        assertEquals(base.halationAmounts()[0] * 2f, s.halationAmounts()[0], 1e-6f);
        assertEquals(base.halationUm() * .5f, s.halationUm(), 1e-6f);
        assertEquals(base.stock.halationUm, base.halationUm(), 0f);
    }

    @Test public void everyKeyIsStoredOnceDescribedAndGated() {
        final java.util.List<String> keys = Arrays.asList(FilmSim.KEYS);
        assertEquals(new HashSet<>(keys).size(), keys.size());
        for (String k : FilmAdvanced.KEYS) {
            assertTrue(k, keys.contains(k));
            assertTrue(k, k.startsWith("pref_film_sim_"));
            assertNotNull(k, com.particlesdevs.photoncamera.settings.ParamHelp.of(null, k));
        }
        assertEquals(FilmSim.DIR_KEY, FilmAdvanced.gate(FilmAdvanced.DIR_GAMMA_KEYS[3]));
        assertEquals(FilmSim.HALATION_KEY, FilmAdvanced.gate(FilmAdvanced.BOOST.key));
        assertEquals(FilmAdvanced.BOOST.key, FilmAdvanced.gate(FilmAdvanced.PROTECT.key));
        assertEquals(FilmAdvanced.BLEACH.key, FilmAdvanced.gate(FilmAdvanced.LEUCO.key));
        assertNull(FilmAdvanced.gate(FilmAdvanced.SPECTRAL_KEY));
        for (FilmAdvanced.Param p : FilmAdvanced.SLIDERS) {
            assertTrue(p.key, p.min <= p.def && p.def <= p.max && p.step > 0);
            assertEquals(p.key, 0, Math.abs((p.def - p.min) / p.step - Math.round((p.def - p.min) / p.step)), 1e-3);
        }
    }
}
