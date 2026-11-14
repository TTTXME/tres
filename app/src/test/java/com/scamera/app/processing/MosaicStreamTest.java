package com.scamera.app.processing;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * P695: the session's colour block against its own confident answers, and a row map's block at the session's start. The stream of
 * the owner's log (Xiaomi 15 Ultra, the periscope's 4x ISZ row 6): a stored Quad, 11 dark frames without an answer and one
 * confident Tetra («block 4 (confident; tile votes b1/b2/b4 0/0/395 of 29463)»).
 */
public class MosaicStreamTest {
    private static final String KEY = "5|4080x3072||0|pick-6";

    @Before
    public void setUp() {
        MosaicBlockStore.setInstance(new MosaicBlockStore(MosaicBlockStore.memoryBacking()));
        MosaicStream.reset();
    }

    @After
    public void tearDown() {
        MosaicBlockStore.setInstance(null);
        MosaicStream.reset();
    }

    private static MosaicBlockDetector.Result none(int tiles) {
        return new MosaicBlockDetector.Result(1, false, new int[]{0, 0, 0}, tiles);
    }

    private static MosaicBlockDetector.Result confident(int block, int b1, int b2, int b4, int tiles) {
        return new MosaicBlockDetector.Result(block, true, new int[]{b1, b2, b4}, tiles);
    }

    private static MosaicBlockDetector.Result tetra() {
        return confident(4, 0, 0, 395, 29463);
    }

    private static void store(int block) {
        MosaicBlockStore.get().observe(KEY, true, block, "an earlier session");
    }

    @Test
    public void theOwnersSessionNoLongerKeepsTheStoredQuad() {
        store(2);
        MosaicStream.startSession(KEY);
        assertEquals("the session starts from the stored block", 2, MosaicStream.block());
        for (int i = 0; i < 11; i++) assertEquals(2, MosaicStream.observe(none(12879)));
        assertEquals("the one decisive Tetra answer of the session, none for the Quad", 4, MosaicStream.observe(tetra()));
        assertEquals(4, MosaicStream.block());
        assertEquals("the stored Quad is replaced", 4, MosaicBlockStore.get().stored(KEY));
        MosaicStream.startSession(KEY);
        assertEquals("the next session starts right", 4, MosaicStream.block());
    }

    @Test
    public void aDecisiveAnswerWaitsForTheEndOfTheMeasurement() {
        store(2);
        MosaicStream.startSession(KEY);
        assertEquals("one answer early in the session: the rest of the frames may still agree with the stored block", 2,
                MosaicStream.observe(tetra()));
        for (int i = 0; i < 10; i++) assertEquals(2, MosaicStream.observe(none(100)));
        assertEquals(4, MosaicStream.observe(none(100)));
        assertEquals(4, MosaicBlockStore.get().stored(KEY));
    }

    @Test
    public void twoAgreeingConfidentAnswersSwitchAtOnce() {
        store(2);
        MosaicStream.startSession(KEY);
        assertEquals(2, MosaicStream.observe(confident(4, 10, 5, 60, 200)));
        assertEquals("two agreeing confident answers against it, none for it", 4, MosaicStream.observe(confident(4, 10, 5, 60, 200)));
        assertEquals(4, MosaicBlockStore.get().stored(KEY));
    }

    @Test
    public void anAnswerForTheSessionsBlockKeepsIt() {
        store(2);
        MosaicStream.startSession(KEY);
        assertEquals(2, MosaicStream.observe(confident(2, 0, 300, 0, 1000)));
        assertEquals(2, MosaicStream.observe(tetra()));
        assertEquals(2, MosaicStream.observe(tetra()));
        for (int i = 0; i < 9; i++) MosaicStream.observe(none(100));
        assertEquals("one confident answer for the block: the P35 rule (three agreeing in a row) as before", 2, MosaicStream.block());
    }

    @Test
    public void answersThatDisagreeAmongThemselvesChangeNothing() {
        store(2);
        MosaicStream.startSession(KEY);
        MosaicStream.observe(tetra());
        MosaicStream.observe(confident(1, 400, 0, 0, 1000));
        for (int i = 0; i < 10; i++) MosaicStream.observe(none(100));
        assertEquals(2, MosaicStream.block());
        assertEquals(2, MosaicBlockStore.get().stored(KEY));
    }

    @Test
    public void aConfidentButNotDecisiveSingleAnswerChangesNothing() {
        store(2);
        MosaicStream.startSession(KEY);
        MosaicStream.observe(confident(4, 20, 10, 50, 5000)); // confident (62 %) but not decisive (90 % of at least 100)
        for (int i = 0; i < 11; i++) MosaicStream.observe(none(100));
        assertEquals(2, MosaicStream.block());
        assertEquals(2, MosaicBlockStore.get().stored(KEY));
    }

    @Test
    public void decisiveNeedsVotesAndShare() {
        assertEquals(true, MosaicStream.decisive(tetra()));
        assertEquals(false, MosaicStream.decisive(confident(4, 0, 0, 99, 1000)));
        assertEquals(false, MosaicStream.decisive(confident(4, 15, 0, 85, 1000)));
        assertEquals(true, MosaicStream.decisive(confident(1, 180, 10, 10, 1000)));
        assertEquals(false, MosaicStream.decisive(none(1000)));
        assertEquals(false, MosaicStream.decisive(null));
    }

    @Test
    public void theRowMapsBlockReplacesAStoredMosaicOfAnotherSize() {
        store(2);
        MosaicStream.startSession(KEY, 4);
        assertEquals("the row map's Tetra over the stored Quad, from the first viewfinder frame", 4, MosaicStream.block());
        assertEquals(4, MosaicBlockStore.get().stored(KEY));
        MosaicBlockStore.setShotKey(KEY, 4);
        assertEquals("the shot merges the Tetra", 4, MosaicBlockStore.blockForShot(0));
        MosaicBlockStore.setShotKey("");
    }

    @Test
    public void theRowMapsBlockNeverReplacesAProvedBayer() {
        store(1);
        MosaicStream.startSession(KEY, 4);
        assertEquals("a stored 1 is the HAL's remosaic proved on the frames", 1, MosaicStream.block());
        assertEquals(1, MosaicBlockStore.get().stored(KEY));
        assertEquals("one confident answer for the row map's block is enough", 4, MosaicStream.observe(tetra()));
        assertEquals(4, MosaicBlockStore.get().stored(KEY));
    }

    @Test
    public void theRowMapsBlockStartsAStreamWithNothingStored() {
        MosaicStream.startSession(KEY, 4);
        assertEquals(4, MosaicStream.block());
        assertEquals("nothing stored before a measurement", 0, MosaicBlockStore.get().stored(KEY));
        assertEquals(4, MosaicStream.observe(tetra()));
        MosaicStream.observe(tetra());
        MosaicStream.observe(tetra());
        assertEquals(4, MosaicBlockStore.get().stored(KEY));
        MosaicStream.startSession(KEY, 0);
        assertEquals(4, MosaicStream.block());
    }

    @Test
    public void framesThatProveABayerOverruleTheRowMap() {
        MosaicStream.startSession(KEY, 4);
        assertEquals(4, MosaicStream.observe(confident(1, 400, 0, 0, 1000)));
        assertEquals("two agreeing plain-Bayer answers", 1, MosaicStream.observe(confident(1, 400, 0, 0, 1000)));
        assertEquals(1, MosaicBlockStore.get().stored(KEY));
    }

    @Test
    public void noRowMapKeepsTheEarlierStart() {
        MosaicStream.startSession(KEY);
        assertEquals("nothing known: a plain Bayer until measured", 0, MosaicStream.block());
        assertEquals(0, MosaicStream.observe(tetra()));
        assertEquals("nothing to contradict: the three agreeing answers of P13", 0, MosaicStream.observe(tetra()));
        assertEquals(4, MosaicStream.observe(tetra()));
        MosaicStream.startSession("off", 4);
        assertEquals("a stream that is not measured starts as before", 0, MosaicStream.block());
        assertEquals("and is never stored", 0, MosaicBlockStore.get().stored("off"));
    }

    @Test
    public void replaceSetsTheBlockAtOnce() {
        final MosaicBlockStore s = MosaicBlockStore.get();
        s.observe(KEY, true, 2, "day");
        s.observe(KEY, true, 4, "vf"); // one disagreement counted
        assertEquals(4, s.replace(KEY, 4, "test"));
        assertEquals(4, s.stored(KEY));
        assertEquals("an invalid block changes nothing", 4, s.replace(KEY, 3, "test"));
        assertEquals(0, s.replace("off", 2, "test"));
        assertEquals(2, s.replace(KEY, 2, "test"));
    }
}
