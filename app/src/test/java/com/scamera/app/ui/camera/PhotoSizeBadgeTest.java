package com.scamera.app.ui.camera;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P319 (user report 2026-10-22: «надпись "12 Мп" даже, если камера имеет большее или меньшее разрешение (не забываем про
 * фронталки)»): the size pill names the photo the lens really writes - its stream, the «16:9» crop, «Разрешение».
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PhotoSizeBadgeTest {
    @Test public void theOutputFollowsTheStreamTheCropAndTheResolution() {
        // a 50 MP main binned to 4096 x 3072: 12.6 MP, not «12»
        assertArrayEquals(new int[]{4096, 3072}, PhotoSize.output(4096, 3072, false, "sensor"));
        // an 8 MP front stays 8 MP; with 2x it is 32 MP
        assertArrayEquals(new int[]{3264, 2448}, PhotoSize.output(3264, 2448, false, "sensor"));
        assertArrayEquals(new int[]{6528, 4896}, PhotoSize.output(3264, 2448, false, "2x"));
        // «16:9»: 4096 x 2304 (whole colour blocks)
        assertArrayEquals(new int[]{4096, 2304}, PhotoSize.output(4096, 3072, true, "sensor"));
        // P501: «Разрешение» 1.5x: OUT = RAW x 1.5 (width on 4 px, height on 2 px); the old 16 / 20 MP read as 1.5x
        assertArrayEquals(new int[]{6144, 4608}, PhotoSize.output(4096, 3072, false, "1.5x"));
        assertArrayEquals(new int[]{6120, 4608}, PhotoSize.output(4080, 3072, false, "20"));
        assertArrayEquals(new int[]{6048, 4536}, PhotoSize.output(4032, 3024, false, "16"));
        assertArrayEquals(new int[]{4896, 3672}, PhotoSize.output(3264, 2448, false, "1.5x"));
        // a FULLRES 50 MP stream at the sensor size
        assertArrayEquals(new int[]{8192, 6144}, PhotoSize.output(8192, 6144, false, "sensor"));
        // a portrait stream is never cropped to 16:9
        assertArrayEquals(new int[]{2448, 3264}, PhotoSize.output(2448, 3264, true, "sensor"));
    }

    /** P688 (owner, 2026-11-14): a cold start with 50 MP chosen read «Обычный · 50 Мп» - a FULLRES session's stream is never the plain size. */
    @Test public void aFullResSessionStreamIsNeverTheNormalSize() {
        assertTrue(PhotoSize.normalStream(true, PhotoSize.NORMAL, 4080, 3064));
        // the sizes not bound yet at a cold start read NORMAL: the session itself says it is FULLRES
        assertFalse(PhotoSize.normalStream(false, PhotoSize.NORMAL, 8192, 6144));
        assertFalse(PhotoSize.normalStream(false, PhotoSize.NORMAL, 16320, 12256));
        assertFalse(PhotoSize.normalStream(true, PhotoSize.FULL, 4080, 3064));
        assertFalse(PhotoSize.normalStream(true, PhotoSize.NORMAL, 0, 0));
        PhotoSize.clearNormalStreams();
        PhotoSize.stream("3", 8192, 6144, false);
        assertFalse(PhotoSize.normalBadge("3").startsWith("50"));
        PhotoSize.clearNormalStreams();
    }
}
