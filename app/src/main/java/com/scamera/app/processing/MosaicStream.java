package com.scamera.app.processing;

import com.scamera.app.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Colour block of the current RAW stream (P13), shared by the capture thread (which measures it) and the viewfinder.
 *
 * <p>Per session the first frames are measured with {@link MosaicBlockDetector}; three confident results that agree fix the
 * block for the session. The result is remembered per module and its vendor requests (the key the capture controller gives),
 * so the next session of the same module starts with the right preview instead of a purple one for the first frames.
 * P35: the block is also persisted per key ({@link MosaicBlockStore}): a session starts from the stored block (the first
 * viewfinder frame is already right, also after an app restart), and the session's measurements are the background check of
 * the stored block (a confident disagreement replaces it after three agreeing answers, never an unconfident one).
 * <p>P695 (Xiaomi 15 Ultra, the periscope's 4x ISZ row 6: a stored Quad block on a Tetra stream - the viewfinder and the shot
 * purple, every 2x2 cell holding all four colours; three sessions kept it, one with a confident Tetra answer «1/3», two whose dark
 * frames gave no answer at all): the session does not keep a block its own confident answers contradict. With no confident answer
 * for the session's block, two agreeing confident answers against it, one that agrees with the row map's block, or at the end of the
 * measurement one decisive answer (at least {@link #DECISIVE_VOTES} tile votes, {@link #DECISIVE_SHARE} of them for one block)
 * switch the session to the measured block and replace the stored one. A row map's block (SensorModePick.Pick#rowMap) starts the
 * session where nothing is stored and replaces a stored mosaic block of another size (never a stored 1: the HAL's remosaic proved
 * on the frames).
 */
public final class MosaicStream {
    private MosaicStream() {}

    private static final ConcurrentHashMap<String, Integer> KNOWN = new ConcurrentHashMap<>();
    private static volatile int block;          // 0 = not measured yet
    private static volatile String key = "";
    private static int votes, voteBlock, measured;
    /** Frames measured per session. */
    static final int WINDOW = 12;
    /** P695: a decisive answer - at least this many tile votes, this share of them for one block. */
    static final int DECISIVE_VOTES = 100;
    static final float DECISIVE_SHARE = 0.9f;
    /** P695: the row map's block of this session (0 none); confident answers for and against the session's block. */
    private static int declared, agree, contra, contraBlock;
    private static boolean contraMixed, contraDecisive;

    /** A new session of the module / request set {@code streamKey}: the remembered block, if any, applies at once. */
    public static synchronized void startSession(String streamKey) {
        startSession(streamKey, 0);
    }

    /**
     * {@link #startSession(String)} of a stream whose camera's row map declares {@code rowMapBlock} (2 / 4; 0 none, P695): the
     * session starts from it where nothing is stored, and a stored mosaic block of another size gives way to it.
     */
    public static synchronized void startSession(String streamKey, int rowMapBlock) {
        key = streamKey == null ? "" : streamKey;
        // a stream that is not measured ("off": scam_dev mosaic_preview 0) starts as before
        declared = (rowMapBlock == 2 || rowMapBlock == 4) && MosaicBlockStore.storable(key) ? rowMapBlock : 0;
        final MosaicBlockStore store = MosaicBlockStore.get();
        int stored = store.stored(key);
        if (declared != 0 && stored > 1 && stored != declared)
            stored = store.replace(key, declared, "the camera's row map declares block " + declared + " for this row (P695)");
        Integer known = stored != 0 ? Integer.valueOf(stored) : KNOWN.get(key);
        block = known != null ? known : declared;
        votes = 0; voteBlock = 0; measured = 0;
        agree = contra = contraBlock = 0;
        contraMixed = contraDecisive = false;
    }

    /** P35: the key of the current session ("off" when the stream is not measured). */
    public static String key() { return key; }

    /** 0 until measured (or remembered), then 1 / 2 / 4. */
    public static int block() { return block; }

    /** True while this session still wants frames measured. */
    public static synchronized boolean wantsFrame() { return measured < WINDOW && votes < 3; }

    /** One measurement of this session's stream; returns the block once three agree (0 before). */
    public static synchronized int observe(MosaicBlockDetector.Result r) {
        final int out = observeOne(r);
        // P631: the session's measurement ends without three agreeing answers - said once (the Xiaomi 17 Ultra's 50 MP row had no
        // answer in 12 frames and the viewfinder fell back to a plain Bayer without a word in the log)
        if (measured == WINDOW && votes < 3)
            Log.i("MosaicStream", "RAW stream colour block not settled for " + key + " after 12 frames (last: " + r + "), the session keeps "
                    + (block == 0 ? "none (the viewfinder: a plain Bayer)" : "block " + block));
        return out;
    }

    private static int observeOne(MosaicBlockDetector.Result r) {
        measured++;
        if (r == null || !r.confident) return contradicted() ? switchTo(contraBlock) : block;
        // P695: the answer for or against the block the session runs (before this answer changes it)
        if (block != 0) {
            if (r.block == block) agree++;
            else if (contra == 0 || r.block == contraBlock) {
                contraBlock = r.block;
                contra++;
                contraDecisive |= decisive(r);
            } else contraMixed = true;
        }
        // P35: with a stored block every confident frame checks it (three agreeing disagreements in a row replace it).
        MosaicBlockStore store = MosaicBlockStore.get();
        if (store.stored(key) != 0) {
            int now = store.observe(key, true, r.block, "viewfinder");
            if (now != block && now != 0) {
                Log.i("MosaicStream", "RAW stream colour block " + block + " -> " + now + " for " + key + " (stored block replaced)");
                block = now;
                KNOWN.put(key, now);
            }
        }
        if (r.block == voteBlock) votes++; else { voteBlock = r.block; votes = 1; }
        if (votes >= 3) {
            Integer previous = KNOWN.put(key, voteBlock);
            if (previous == null || previous != voteBlock || block != voteBlock)
                Log.i("MosaicStream", "RAW stream colour block " + voteBlock + " for " + key + " (" + r + ")");
            block = voteBlock;
            if (store.stored(key) == 0) store.observe(key, true, voteBlock, "viewfinder, three agreeing frames");
        }
        return contradicted() ? switchTo(contraBlock) : block;
    }

    /** P695: at least {@link #DECISIVE_VOTES} tile votes, {@link #DECISIVE_SHARE} of them for the answer's block. */
    static boolean decisive(MosaicBlockDetector.Result r) {
        if (r == null || !r.confident || r.votes == null || r.votes.length < 3) return false;
        final int total = r.votes[0] + r.votes[1] + r.votes[2];
        final int own = r.votes[r.block == 4 ? 2 : r.block == 2 ? 1 : 0];
        return total >= DECISIVE_VOTES && own >= DECISIVE_SHARE * total;
    }

    /**
     * P695: the session's block is contradicted - no confident answer for it, every confident one against it for the same block, and
     * two of them, or one that agrees with the row map's block, or (the measurement over) a decisive one.
     */
    private static boolean contradicted() {
        if (block == 0 || agree > 0 || contraMixed || contra == 0 || contraBlock == block) return false;
        return contra >= 2 || contraBlock == declared || measured >= WINDOW && contraDecisive;
    }

    private static int switchTo(int measuredBlock) {
        final int was = block;
        Log.i("MosaicStream", "RAW stream colour block " + was + " -> " + measuredBlock + " for " + key + " (" + contra
                + " confident answer" + (contra == 1 ? "" : "s") + " against it, none for it" + (measuredBlock == declared ? ", the row map's block"
                : contra < 2 ? ", decisive" : "") + "; P695)");
        block = measuredBlock;
        KNOWN.put(key, measuredBlock);
        final MosaicBlockStore store = MosaicBlockStore.get();
        if (store.stored(key) != 0) store.replace(key, measuredBlock, "viewfinder: the session's confident answers contradict it, P695");
        else store.observe(key, true, measuredBlock, "viewfinder, the session's confident answers (P695)");
        agree = contra;
        contra = contraBlock = 0;
        contraMixed = contraDecisive = false;
        return block;
    }

    /**
     * The block this session's own frames agreed on (three confident agreeing frames), 0 before: not the stored block, which a
     * row that failed once would otherwise keep reporting (P575: «Попробовать снова» / a firmware update must test it afresh).
     */
    public static synchronized int sessionVerdict() { return votes >= 3 ? voteBlock : 0; }

    /** Tests. */
    static synchronized void reset() {
        KNOWN.clear(); block = 0; key = ""; votes = voteBlock = measured = 0;
        declared = agree = contra = contraBlock = 0; contraMixed = contraDecisive = false;
    }
}
