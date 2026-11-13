package com.scamera.app.processing.color;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class FilmProfileMappingTest {
    private static final String[] FILMS = {
            "kodak_ektar_100", "kodak_portra_160", "kodak_portra_400", "kodak_portra_800",
            "kodak_portra_800_push1", "kodak_portra_800_push2", "kodak_gold_200", "kodak_ultramax_400",
            "kodak_vision3_50d", "kodak_vision3_250d", "kodak_verita_200d", "kodak_vision3_200t",
            "kodak_vision3_500t", "fujifilm_pro_400h", "fujifilm_c200", "fujifilm_xtra_400",
            "kodak_ektachrome_100", "kodak_kodachrome_64", "fujifilm_velvia_100", "fujifilm_provia_100f"};
    private static final String[] PAPERS = {"kodak_endura_premier", "kodak_ultra_endura", "kodak_ektacolor_edge",
            "kodak_supra_endura", "kodak_portra_endura", "fujifilm_crystal_archive_typeii", "kodak_2383", "kodak_2393"};

    private static FilmSim.Settings settings(FilmSim.Stock stock, String paper) {
        return new FilmSim.Settings(stock, FilmSim.print(paper), FilmSim.format("off"), 0, 0, 0, 0, 0, 0, 1);
    }

    @Test public void everyMeasuredFilmAndPaperSelectsItsOwnNativeIndex() {
        int measured = 0;
        for (FilmSim.Stock stock : FilmSim.STOCKS) {
            if (stock.spectral == null) {
                assertFalse(stock.id, FilmReferenceParameters.supports(settings(stock, PAPERS[0])));
                continue;
            }
            measured++;
            int film = java.util.Arrays.asList(FILMS).indexOf(stock.id);
            assertTrue(stock.id, film >= 0);
            for (int paper = 0; paper < PAPERS.length; paper++) {
                FilmSim.Settings s = settings(stock, PAPERS[paper]);
                assertTrue(stock.id, FilmReferenceParameters.supports(s));
                ByteBuffer packet = FilmReferenceParameters.from(s, false, 1).buffer();
                assertEquals(stock.id, film, packet.getInt(56));
                assertEquals(PAPERS[paper], paper, packet.getInt(60));
                assertEquals(stock.id, stock.reversal() ? 1 : 0, packet.getInt(4));
            }
        }
        assertEquals(FILMS.length, measured);
    }

    @Test public void switchingStocksNeverReusesThePreviousPacketOrLutKey() {
        Set<String> keys = new HashSet<>();
        Set<Integer> indices = new HashSet<>();
        for (String film : FILMS) {
            FilmSim.Settings s = settings(FilmSim.stock(film), "kodak_portra_endura");
            assertTrue(film, keys.add(s.lutKey()));
            ByteBuffer packet = FilmReferenceParameters.from(s, false, 1).buffer();
            assertTrue(film, indices.add(packet.getInt(56)));
        }
        FilmSim.Settings portra = settings(FilmSim.stock("kodak_portra_400"), "kodak_portra_endura");
        FilmSim.Settings gold = settings(FilmSim.stock("kodak_gold_200"), "kodak_portra_endura");
        ByteBuffer first = FilmReferenceParameters.from(portra, false, 1).buffer();
        ByteBuffer second = FilmReferenceParameters.from(gold, false, 1).buffer();
        assertEquals(2, first.getInt(56));
        assertEquals(6, second.getInt(56));
        second.putInt(56, 0);
        assertEquals("Packets never share writable storage", 2, first.getInt(56));
    }

    @Test public void anUnsetMeasuredRecipeKeepsTheReferencePaperAcrossStocks() {
        for (String key : new String[]{FilmSim.GRAIN_KEY, FilmSim.HALATION_KEY, FilmSim.SOFTNESS_KEY, FilmSim.DIR_KEY})
            assertEquals(key, 0, FilmSim.defaultPercent(key));
        assertEquals(0, FilmSim.couplers(null), 0);
        assertEquals("Explicit effects remain available", 1, FilmSim.couplers("100"), 0);
        for (String film : FILMS) {
            FilmSim.Stock stock = FilmSim.stock(film);
            assertEquals(film, "kodak_supra_endura", FilmSim.print("", stock).id);
            assertEquals(film, "kodak_supra_endura", FilmSim.print(null, stock).id);
            FilmSim.Settings defaults = new FilmSim.Settings(stock, FilmSim.print("", stock), FilmSim.format("off"),
                    0, 0, 0, 0, 0, 0, 1);
            assertEquals(film, 3, FilmReferenceParameters.from(defaults, false, 1).buffer().getInt(60));
            for (String paper : PAPERS) assertEquals(film, paper, FilmSim.print(paper, stock).id);
        }
        assertEquals("unmeasured monochrome fallback is preserved", "kodak_portra_endura",
                FilmSim.print("", FilmSim.stock("trix400")).id);
    }
}
