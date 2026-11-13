package com.scamera.app.gallery.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/** P504: the OCR helpers that need no engine - the language set, the text clean-up, what the index reads, the shipped models. */
public class OcrEngineTest {
    @Test
    public void autoLanguagesAddThePhonesOwn() {
        assertEquals("eng+rus", OcrEngine.autoLanguages("rus"));
        assertEquals("eng+rus+deu", OcrEngine.autoLanguages("deu"));
        assertEquals("eng+rus+chi_sim", OcrEngine.autoLanguages("zho"));
        assertEquals("eng+rus", OcrEngine.autoLanguages("xxx"));
        assertEquals("eng+rus", OcrEngine.autoLanguages(null));
    }

    @Test
    public void cleanDropsNoiseLinesAndKeepsParagraphs() {
        assertEquals("ВЫХОД\nEXIT\n\nнет", OcrEngine.clean("  ВЫХОД \n|\nEXIT\n\n\n нет\n"));
        assertEquals("", OcrEngine.clean("\n ~ \n"));
    }

    @Test
    public void onlyTheModelsAskedForAreCopied() {
        assertEquals(java.util.Arrays.asList("eng", "rus"), OcrEngine.modelsOf("eng+rus"));
        assertEquals(java.util.Arrays.asList("chi_sim", "eng"), OcrEngine.modelsOf("chi_sim+eng+eng"));
        assertEquals("no model of its own in the APK", java.util.Collections.singletonList("eng"), OcrEngine.modelsOf("eng+xxx"));
        assertTrue(OcrEngine.modelsOf(null).isEmpty());
    }

    @Test
    public void indexReadsDecodablePhotosOnly() {
        assertTrue(OcrIndex.indexable("IMG_1.JPG"));
        assertTrue(OcrIndex.indexable("a.heic"));
        assertFalse(OcrIndex.indexable("VID_1.mp4"));
        assertFalse(OcrIndex.indexable("IMG_1.dng"));
        assertFalse(OcrIndex.indexable(null));
    }

    @Test
    public void everyLanguageChipHasItsModel() {
        File dir = new File("src/main/assets/tessdata");
        if (!dir.isDirectory()) dir = new File("app/src/main/assets/tessdata");
        for (String code : OcrEngine.LANGUAGES) {
            File f = new File(dir, code + ".traineddata");
            assertTrue(code, f.isFile() && f.length() > 500_000);
        }
    }
}
