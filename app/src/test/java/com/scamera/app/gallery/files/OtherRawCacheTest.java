package com.scamera.app.gallery.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P645: the other RAWs (camera RAWs MediaStore does not file as images) come from a cache: no query while MediaStore is
 * unchanged, the known ids and the rows changed since the last check when only its generation moved, everything otherwise; the
 * cache survives the process (its file), and a failed query keeps it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OtherRawCacheTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    /** MediaStore's Files collection, as far as the RAW check sees it: rows with a generation, queries counted. */
    static final class FakeFiles implements OtherRawCache.Source {
        final List<long[]> rows = new ArrayList<>(); // id, generation_modified, date added
        String version = "v1";
        long generation = 10;
        boolean known = true, fail;
        int full, byId, delta;
        long sleepMs;

        void add(long id, long gen, long added) {
            rows.add(new long[]{id, gen, added});
            generation = Math.max(generation, gen);
        }

        void remove(long id) {
            rows.removeIf(r -> r[0] == id);
            generation++;
        }

        @Override
        public List<OtherRawCache.Volume> volumes() {
            return known ? Arrays.asList(new OtherRawCache.Volume("external_primary", version, generation)) : null;
        }

        @Override
        public List<OtherRawCache.Row> query(String volume, String selection, String[] args) {
            if (fail) throw new IllegalStateException("provider died");
            if (sleepMs > 0) try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
            }
            assertTrue(selection, selection.contains(OtherRawCache.selection()));
            List<OtherRawCache.Row> out = new ArrayList<>();
            Matcher in = Pattern.compile("_id IN \\(([0-9,]*)\\)").matcher(selection);
            if (in.find()) {
                byId++;
                List<String> ids = Arrays.asList(in.group(1).split(","));
                for (long[] r : rows) if (ids.contains(Long.toString(r[0]))) out.add(row(volume, r));
            } else if (selection.startsWith("generation_modified > ?")) {
                delta++;
                long since = Long.parseLong(args[0]);
                for (long[] r : rows) if (r[1] > since) out.add(row(volume, r));
            } else {
                full++;
                for (long[] r : rows) out.add(row(volume, r));
            }
            return out;
        }

        static OtherRawCache.Row row(String volume, long[] r) {
            return new OtherRawCache.Row(volume, r[0], "IMG_" + r[0] + ".CR3", r[2], 1000 + r[0], "/storage/emulated/0/DCIM/Canon/IMG_" + r[0] + ".CR3", 7, "Canon");
        }
    }

    private File file;
    private FakeFiles store;

    @Before
    public void setUp() throws Exception {
        OtherRawCache.forget();
        file = new File(dir.getRoot(), OtherRawCache.FILE_NAME);
        store = new FakeFiles();
        store.add(1, 3, 100);
        store.add(2, 5, 200);
    }

    @After
    public void tearDown() {
        OtherRawCache.forget();
    }

    private static List<Long> ids(List<OtherRawCache.Row> rows) {
        List<Long> out = new ArrayList<>();
        for (OtherRawCache.Row r : rows) out.add(r.id);
        java.util.Collections.sort(out);
        return out;
    }

    @Test
    public void theFirstCheckReadsEverythingThenAnUnchangedMediaStoreIsNotQueried() {
        assertTrue("no cache yet: the list has no other RAW", OtherRawCache.rows(file).isEmpty());
        OtherRawCache.Result r = OtherRawCache.check(store, file);
        assertTrue(r.changed);
        assertEquals(1, store.full);
        assertEquals(Arrays.asList(1L, 2L), ids(OtherRawCache.rows(file)));
        OtherRawCache.Result again = OtherRawCache.check(store, file);
        assertFalse(again.changed);
        assertEquals("the same MediaStore: no query at all", 1, store.full + store.byId + store.delta);
    }

    @Test
    public void aNewGenerationReadsTheKnownIdsAndOnlyTheRowsChangedSince() {
        OtherRawCache.check(store, file);
        store.add(3, 12, 300); // a new RAW
        store.remove(1);       // one deleted (or trashed)
        OtherRawCache.Result r = OtherRawCache.check(store, file);
        assertTrue(r.changed);
        assertEquals("never the whole query again", 1, store.full);
        assertEquals(1, store.byId);
        assertEquals(1, store.delta);
        assertEquals(Arrays.asList(2L, 3L), ids(OtherRawCache.rows(file)));
        store.generation++; // a photo saved: nothing RAW changed
        assertFalse(OtherRawCache.check(store, file).changed);
    }

    @Test
    public void aNewProcessHasTheRowsFromTheFileWithoutAQuery() {
        OtherRawCache.check(store, file);
        int queries = store.full + store.byId + store.delta;
        OtherRawCache.forget(); // the process ends; the file stays
        assertEquals(Arrays.asList(1L, 2L), ids(OtherRawCache.rows(file)));
        assertEquals("file", OtherRawCache.origin());
        assertFalse(OtherRawCache.check(store, file).changed);
        assertEquals("reopened in a new process: no query", queries, store.full + store.byId + store.delta);
    }

    @Test
    public void aNewMediaStoreVersionOrNoGenerationReadsEverything() {
        OtherRawCache.check(store, file);
        store.version = "v2"; // the database was rebuilt: the ids mean nothing
        OtherRawCache.check(store, file);
        assertEquals(2, store.full);
        store.known = false; // before Android 11
        OtherRawCache.check(store, file);
        OtherRawCache.check(store, file);
        assertEquals("no generation: every check reads all (still after the list)", 4, store.full);
    }

    @Test
    public void aFailedQueryKeepsTheCache() {
        OtherRawCache.check(store, file);
        store.generation++;
        store.fail = true;
        OtherRawCache.Result r = OtherRawCache.check(store, file);
        assertFalse(r.changed);
        assertTrue(r.how, r.how.startsWith("failed"));
        assertEquals(Arrays.asList(1L, 2L), ids(OtherRawCache.rows(file)));
        store.fail = false;
        store.add(4, 20, 400);
        assertTrue("the next check catches up from the last good generation", OtherRawCache.check(store, file).changed);
        assertEquals(Arrays.asList(1L, 2L, 4L), ids(OtherRawCache.rows(file)));
    }

    @Test
    public void aRowSurvivesTheFileAsItWas() {
        OtherRawCache.Row r = new OtherRawCache.Row("external_primary", 42, "a\tb.CR3", 1700000000L, 123, null, 9, "Canon");
        OtherRawCache.Row back = OtherRawCache.Row.parse(r.line());
        assertEquals(42, back.id);
        assertEquals("a b.CR3", back.name);
        assertEquals(null, back.data);
        assertEquals(1700000000L, back.addedSec);
        assertEquals("Canon", back.bucket);
        assertEquals(1700000000_000L, r.file().modifiedMs());
        assertEquals("content://media/external/file/42", r.file().contentUri().toString());
    }
}
