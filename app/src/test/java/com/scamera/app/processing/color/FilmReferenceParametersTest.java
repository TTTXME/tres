package com.scamera.app.processing.color;

import org.junit.BeforeClass;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class FilmReferenceParametersTest {
    @BeforeClass public static void assets() { FilmSpectralTest.assets(); }

    private static FilmSim.Settings settings(String stock, String format, String diffusion, float strength) {
        return new FilmSim.Settings(FilmSim.stock(stock), FilmSim.print("kodak_portra_endura"),
                FilmSim.format(format), 1f, 1f, 1f, .5f, -.25f, .5f, .2f, -.3f,
                strength, diffusion, .5f, .8f);
    }

    @Test public void snapshotMapsPhotographicUnitsWithoutApplyingMixStrengthTwice() {
        ByteBuffer p = FilmReferenceParameters.from(settings("kodak_portra_160", "135", LensFilters.PEARLESCENT, .5f), false, 71).buffer();
        assertTrue(p.isDirect());
        assertEquals(ByteOrder.LITTLE_ENDIAN, p.order());
        assertEquals(652, p.remaining());
        assertEquals(1, p.getInt(56));
        assertEquals(4, p.getInt(60));
        assertEquals(.5f, p.getFloat(84), 0f);
        assertEquals(-.25f, p.getFloat(96), 0f);
        assertEquals((float) Math.sqrt(2), p.getFloat(124), 0f);
        assertEquals(.2f, p.getFloat(144), 0f);
        assertEquals(-.3f, p.getFloat(148), 0f);
        assertEquals(.8f, p.getFloat(196), 0f);
        assertEquals(1f, p.getFloat(268), 0f);
        assertEquals(71, p.getInt(352));
        assertEquals(2, p.getInt(496));
        assertEquals(.5f, p.getFloat(500), 0f);
        assertEquals(60f, p.getFloat(612), 0f);
    }

    @Test public void formatOffDisablesSpatialEffectsAndMediumFormatsRetainTheirPhysicalSize() {
        ByteBuffer off = FilmReferenceParameters.from(settings("kodak_portra_160", "off", LensFilters.BLACK_PRO_MIST, 1), false, 1).buffer();
        for (int offset : new int[]{256, 436, 492, 580}) assertEquals(0, off.getInt(offset));
        assertEquals(0f, off.getFloat(200), 0f);
        assertEquals(0f, off.getFloat(204), 0f);
        ByteBuffer medium = FilmReferenceParameters.from(settings("kodak_portra_160", "645", "", 1), false, 1).buffer();
        assertEquals(56f, medium.getFloat(648), 0f);
        assertEquals(0f, off.getFloat(648), 0f);
    }

    @Test public void slideAndP3SelectTheNativeOutputContract() {
        ByteBuffer p = FilmReferenceParameters.from(settings("fujifilm_provia_100f", "135", "", 1), true, 1).buffer();
        assertEquals(1, p.getInt(4));
        assertEquals(19, p.getInt(56));
        assertEquals(18, p.getInt(20));
        assertEquals(15, p.getInt(16));
        assertEquals(0, p.getInt(88));
    }

    @Test public void packetPositionDoesNotLeakAcrossNativeCalls() {
        FilmReferenceParameters parameters = new FilmReferenceParameters();
        ByteBuffer first = parameters.buffer(); first.position(100); first.limit(200);
        assertEquals(0, parameters.buffer().position());
        assertEquals(652, parameters.buffer().remaining());
    }

    @Test public void slideCouplersUseTheirProfileInsteadOfNegativeFilmCoefficients() {
        String[] stocks={"kodak_ektachrome_100","kodak_kodachrome_64","fujifilm_velvia_100","fujifilm_provia_100f"};
        float[] scale={1f,1f,.9f,1.3f};
        float[] gamma={.12f,.08f,.06f,.12f,.06f,.08f,.06f,.06f,.06f};
        for(int stock=0;stock<stocks.length;stock++){
            ByteBuffer p=FilmReferenceParameters.from(settings(stocks[stock],"super35","",1),false,1).buffer();
            for(int i=0;i<gamma.length;i++)assertEquals(stocks[stock],gamma[i]*scale[stock],p.getFloat(220+4*i),1e-7f);
            assertEquals(.8f,p.getFloat(196),0f);
        }
        ByteBuffer negative=FilmReferenceParameters.from(settings("kodak_portra_160","super35","",1),false,1).buffer();
        assertEquals(.336f,negative.getFloat(220),0f);
        assertEquals(.353f,negative.getFloat(232),0f);
    }

    @Test public void nonfiniteAndMisalignedPacketsAreRejected() {
        try { new FilmReferenceParameters().real(84, Float.NaN); fail(); }
        catch (IllegalArgumentException expected) { }
        try { new FilmReferenceParameters().integer(3, 1); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
