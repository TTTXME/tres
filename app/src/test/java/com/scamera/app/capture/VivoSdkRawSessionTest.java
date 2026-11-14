package com.scamera.app.capture;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class VivoSdkRawSessionTest {
    static final class Provider implements VivoSdkRawSession.Provider {
        final Map<Integer, Integer> values = new HashMap<>();
        int failKey = -1;
        int writes;
        boolean ignoreSdk;
        Provider() { values.put(40, 0); values.put(33, 1); }
        public int get(int key) { return values.get(key); }
        public void set(int key, int value) throws Exception {
            writes++;
            if (!(ignoreSdk && key == 40)) values.put(key, value);
            if (key == failKey) { failKey = -1; throw new Exception("partial Binder failure"); }
        }
        public void settle() {}
    }

    @Test public void restoresThePreviousValuesOnce() throws Exception {
        Provider provider = new Provider();
        provider.values.put(40, 3);
        VivoSdkRawSession session = VivoSdkRawSession.open(provider);
        assertEquals(Integer.valueOf(1), provider.values.get(40));
        session.close();
        int writes = provider.writes;
        session.close();
        assertEquals(writes, provider.writes);
        assertEquals(Integer.valueOf(3), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    @Test public void rollsBackAMutationThatThrows() throws Exception {
        Provider provider = new Provider();
        provider.failKey = 40;
        try { VivoSdkRawSession.open(provider); fail(); } catch (Exception expected) {}
        assertEquals(Integer.valueOf(0), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    @Test public void rejectsAnIgnoredSdkWrite() throws Exception {
        Provider provider = new Provider();
        provider.ignoreSdk = true;
        try { VivoSdkRawSession.open(provider); fail(); } catch (IllegalStateException expected) {}
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    static final class Journal implements VivoSdkRawSession.Journal {
        int[] lease, base;
        public int[] read() { return lease == null ? null : lease.clone(); }
        public void write(int pid, int sdk, int vcf) { lease = new int[]{pid, sdk, vcf}; }
        public void clear() { lease = null; }
        @Override public int[] baseline() { return base == null ? null : base.clone(); }
        @Override public void baseline(int sdk, int vcf) { base = new int[]{sdk, vcf}; }
    }

    /** P179: the lease is on disk while it is held and gone once the values are back. */
    @Test public void journalsTheLeaseWhileItIsHeld() throws Exception {
        Provider provider = new Provider();
        Journal journal = new Journal();
        VivoSdkRawSession session = VivoSdkRawSession.open(provider, journal, 100);
        assertArrayEquals(new int[]{100, 0, 1}, journal.lease);
        assertEquals("our own live lease is left alone", "none",
                VivoSdkRawSession.recoverStale(provider, journal, 100, () -> true));
        session.close();
        assertNull(journal.lease);
        assertEquals(Integer.valueOf(0), provider.values.get(40));
    }

    /** P179: killed while holding it (no close ran): the next start gives the values found back, only while they are ours. */
    @Test public void aDeadProcessLeaseIsGivenBackOnlyWhileOursAndIdle() throws Exception {
        Provider provider = new Provider();
        provider.values.put(40, 3);
        Journal journal = new Journal();
        VivoSdkRawSession.open(provider, journal, 100);   // the process dies here
        VivoSdkRawSession.died(provider);
        assertEquals(Integer.valueOf(1), provider.values.get(40));
        assertEquals("a camera in use: wait", "busy", VivoSdkRawSession.recoverStale(provider, journal, 200, () -> false));
        assertNotNull(journal.lease);
        assertEquals(Integer.valueOf(1), provider.values.get(40));
        assertEquals("restored", VivoSdkRawSession.recoverStale(provider, journal, 200, () -> true));
        assertEquals(Integer.valueOf(3), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
        assertNull(journal.lease);
        assertEquals("none", VivoSdkRawSession.recoverStale(provider, journal, 200, () -> true));
        // another client set its own values after we died: nothing is reset, the journal goes
        VivoSdkRawSession.open(provider, journal, 300);
        VivoSdkRawSession.died(provider);
        provider.values.put(40, 5);
        assertEquals("not ours", VivoSdkRawSession.recoverStale(provider, journal, 400, () -> true));
        assertEquals(Integer.valueOf(5), provider.values.get(40));
        assertNull(journal.lease);
    }

    /** P179: a new lease after a dead one keeps the values the dead process found, not its own leftovers. */
    @Test public void aNewLeaseAfterADeadOneRemembersTheOriginalValues() throws Exception {
        Provider provider = new Provider();
        provider.values.put(40, 3);
        Journal journal = new Journal();
        VivoSdkRawSession.open(provider, journal, 100);   // dies
        VivoSdkRawSession.died(provider);                 // (P640: a process's lease is one object - the next process has none)
        VivoSdkRawSession next = VivoSdkRawSession.open(provider, journal, 200);
        assertArrayEquals(new int[]{200, 3, 1}, journal.lease);
        next.close();
        assertEquals(Integer.valueOf(3), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    @Test public void attemptsBothRestoresAndCanRetryAFailedClose() throws Exception {
        Provider provider = new Provider();
        VivoSdkRawSession session = VivoSdkRawSession.open(provider);
        provider.failKey = 33;
        try { session.close(); fail(); } catch (Exception expected) {}
        assertEquals(Integer.valueOf(0), provider.values.get(40));
        try { session.apply(null, true, 0); fail(); } catch (IllegalStateException expected) {}
        session.close();
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }
    // ------------------------------------------------------------------ P640: the lease never stays set

    private static void assertValues(Provider provider, int sdk, int vcf) {
        assertEquals("SDK RAW (40)", Integer.valueOf(sdk), provider.values.get(40));
        assertEquals("VCF (33)", Integer.valueOf(vcf), provider.values.get(33));
    }

    /**
     * P640 (owner, X200 Ultra: the stock camera's viewfinder black after ours until a reboot): two sessions of one process are
     * one lease. Before, the second read our own values as «found», the first gave the phone's back, the second then gave OURS
     * back and cleared the journal - set for good, nothing left to say what had been there.
     */
    @Test public void aSecondSessionSharesTheLeaseAndTheLastOneGivesThePhonesValuesBack() throws Exception {
        Provider provider = new Provider();
        Journal journal = new Journal();
        VivoSdkRawSession fullRes = VivoSdkRawSession.open(provider, journal, 100);
        VivoSdkRawSession isz = VivoSdkRawSession.open(provider, journal, 100);
        assertArrayEquals("the journal keeps the phone's values, not ours", new int[]{100, 0, 1}, journal.lease);
        assertEquals("SDK RAW 0, VCF 1", isz.found());
        fullRes.close();
        assertValues(provider, 1, 2);
        assertNotNull("still held by the other session", journal.lease);
        fullRes.close();
        assertValues(provider, 1, 2);
        isz.close();
        assertValues(provider, 0, 1);
        assertNull(journal.lease);
    }

    /** P640: the same with the first give failing (the FULLRES session's «not restored», the ISZ lease taken meanwhile). */
    @Test public void aLeaseTakenWhileAGiveBackIsPendingKeepsThePhonesValues() throws Exception {
        Provider provider = new Provider();
        Journal journal = new Journal();
        VivoSdkRawSession fullRes = VivoSdkRawSession.open(provider, journal, 100);
        provider.failKey = 40;
        try { fullRes.close(); fail(); } catch (Exception expected) {}
        VivoSdkRawSession isz = VivoSdkRawSession.open(provider, journal, 100);
        assertValues(provider, 1, 2);
        assertEquals("SDK RAW 0, VCF 1", isz.found());
        isz.close();
        assertValues(provider, 0, 1);
        assertNull(journal.lease);
        int writes = provider.writes;
        fullRes.close(); // the retry of the first session finds its lease given back already
        assertEquals(writes, provider.writes);
        assertValues(provider, 0, 1);
    }

    /** P640: SDK RAW found set with no lease of this process is never «the value found». */
    @Test public void ourOwnValueIsNeverTakenForThePhones() throws Exception {
        // nothing known: the default, the VCF value as found
        Provider provider = new Provider();
        provider.values.put(40, 1);
        provider.values.put(33, 2);
        VivoSdkRawSession session = VivoSdkRawSession.open(provider, new Journal(), 100);
        assertEquals("SDK RAW " + VivoSdkRawSession.SDK_DEFAULT + ", VCF 2", session.found());
        session.close();
        assertValues(provider, VivoSdkRawSession.SDK_DEFAULT, 2);
        // the values of the last clean start are known: those
        provider = new Provider();
        provider.values.put(40, 3);
        Journal journal = new Journal();
        VivoSdkRawSession.open(provider, journal, 100).close();
        assertArrayEquals(new int[]{3, 1}, journal.base);
        assertNull(journal.lease);
        provider.values.put(40, 1);   // left set by something no journal covers
        provider.values.put(33, 2);
        session = VivoSdkRawSession.open(provider, journal, 200);
        assertArrayEquals("the baseline is not overwritten with ours", new int[]{3, 1}, journal.base);
        session.close();
        assertValues(provider, 3, 1);
        // a journal of this very process (a pid met again) holds the phone's values: those
        provider.values.put(40, 1);
        provider.values.put(33, 2);
        journal.lease = new int[]{300, 5, 7};
        VivoSdkRawSession.open(provider, journal, 300).close();
        assertValues(provider, 5, 7);
        // a journal an older build wrote with our own value: the baseline instead
        provider.values.put(40, 1);
        provider.values.put(33, 2);
        journal.lease = new int[]{400, 1, 2};
        VivoSdkRawSession.open(provider, journal, 500).close();
        assertValues(provider, 3, 1);
        // and its recovery at a start gives the default, not 1
        Provider stuck = new Provider();
        stuck.values.put(40, 1);
        stuck.values.put(33, 2);
        Journal old = new Journal();
        old.lease = new int[]{400, 1, 2};
        assertEquals("restored", VivoSdkRawSession.recoverStale(stuck, old, 500, () -> true));
        assertValues(stuck, VivoSdkRawSession.SDK_DEFAULT, 2);
    }

    /** P640: what is set and held by nobody goes back (the app's start, after a camera closed). */
    @Test public void theSweepGivesBackWhatNobodyHolds() throws Exception {
        Provider provider = new Provider();
        Journal journal = new Journal();
        assertEquals("none", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> true));
        assertEquals(0, provider.writes);
        // a lease in use is left alone
        VivoSdkRawSession session = VivoSdkRawSession.open(provider, journal, 100);
        assertEquals("held", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> false));
        assertValues(provider, 1, 2);
        // a give that failed: the same values, on the next sweep
        provider.failKey = 33;
        try { session.close(); fail(); } catch (Exception expected) {}
        assertNotNull(journal.lease);
        assertEquals("pending restored", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> false));
        assertValues(provider, 0, 1);
        assertNull(journal.lease);
        assertEquals("none", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> true));
        // a dead process's journal
        provider.values.put(40, 1);
        provider.values.put(33, 2);
        journal.lease = new int[]{50, 0, 1};
        assertEquals("busy", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> false));
        assertEquals("restored", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> true));
        assertValues(provider, 0, 1);
        // set with no journal at all (the journal cleared by the old double give-back): the last clean start's values
        provider.values.put(40, 1);
        provider.values.put(33, 2);
        assertEquals("a camera in use may be another app's lease", "busy", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> false));
        assertValues(provider, 1, 2);
        assertEquals("orphan restored", VivoSdkRawSession.Lease.sweep(provider, journal, 100, () -> true));
        assertValues(provider, 0, 1);
        // ... or the default when no clean start is known
        Provider unknown = new Provider();
        unknown.values.put(40, 1);
        unknown.values.put(33, 2);
        assertEquals("orphan restored", VivoSdkRawSession.Lease.sweep(unknown, null, 100, () -> true));
        assertValues(unknown, VivoSdkRawSession.SDK_DEFAULT, 2);
        // a provider that throws is a result, not a crash
        VivoSdkRawSession.Provider broken = new VivoSdkRawSession.Provider() {
            @Override public int get(int key) { throw new IllegalStateException("no service"); }
            @Override public void set(int key, int value) { }
            @Override public void settle() { }
        };
        assertTrue(VivoSdkRawSession.Lease.sweep(broken, journal, 100, () -> true).startsWith("failed: "));
    }

    /** P640: the guard process is told once a lease, and the dead holder's values go back only while SDK RAW is still ours. */
    @Test public void theGuardIsToldAndGivesADeadHoldersValuesBack() throws Exception {
        final StringBuilder told = new StringBuilder();
        VivoSdkRawSession.watch(new VivoSdkRawSession.Watch() {
            @Override public void taken(int sdk, int vcf) { told.append("taken ").append(sdk).append(' ').append(vcf).append(';'); }
            @Override public void back() { told.append("back;"); }
        });
        try {
            Provider provider = new Provider();
            provider.values.put(40, 3);
            VivoSdkRawSession a = VivoSdkRawSession.open(provider, new Journal(), 100), b = VivoSdkRawSession.open(provider, null, 100);
            a.close();
            assertEquals("taken 3 1;", told.toString());
            b.close();
            assertEquals("taken 3 1;back;", told.toString());
            // a refused lease is given back at once: the guard stands down
            told.setLength(0);
            provider.ignoreSdk = true;
            try { VivoSdkRawSession.open(provider); fail(); } catch (IllegalStateException expected) {}
            assertEquals("taken 3 1;back;", told.toString());
            // a watch that throws never breaks the camera
            VivoSdkRawSession.watch(new VivoSdkRawSession.Watch() {
                @Override public void taken(int sdk, int vcf) { throw new IllegalStateException("no guard"); }
                @Override public void back() { throw new IllegalStateException("no guard"); }
            });
            provider.ignoreSdk = false;
            VivoSdkRawSession.open(provider).close();
            assertValues(provider, 3, 1);
        } finally {
            VivoSdkRawSession.watch(null);
        }
        Provider dead = new Provider();
        dead.values.put(40, 1);
        dead.values.put(33, 2);
        assertEquals("restored", VivoSdkRawSession.Lease.restoreAfterDeath(dead, 0, 1));
        assertValues(dead, 0, 1);
        assertEquals("the phone's own value by now", "not ours", VivoSdkRawSession.Lease.restoreAfterDeath(dead, 0, 1));
        dead.values.put(40, 1);
        assertEquals("never our own value", "restored", VivoSdkRawSession.Lease.restoreAfterDeath(dead, 1, 2));
        assertValues(dead, VivoSdkRawSession.SDK_DEFAULT, 2);
    }

    /** P640: an uncaught exception sends the values back before the process goes down; without a lease it does nothing. */
    @Test public void aCrashGivesTheLeaseBack() throws Exception {
        Provider provider = new Provider();
        VivoSdkRawSession.system(provider);
        try {
            VivoSdkRawSession.onCrash();
            assertEquals(0, provider.writes);
            VivoSdkRawSession session = VivoSdkRawSession.open(provider, new Journal(), 100);
            assertValues(provider, 1, 2);
            VivoSdkRawSession.onCrash();
            assertValues(provider, 0, 1);
            int writes = provider.writes;
            VivoSdkRawSession.onCrash();
            assertEquals("once", writes, provider.writes);
            session.close();
            VivoSdkRawSession.onCrash();
            assertValues(provider, 0, 1);
            // a manager that throws never masks the crash
            VivoSdkRawSession.open(provider);
            provider.failKey = 33;
            VivoSdkRawSession.onCrash();
        } finally {
            VivoSdkRawSession.system(null);
        }
    }

    private static String source(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void ordered(String text, String... parts) {
        int at = 0;
        for (String part : parts) {
            final int found = text.indexOf(part, at);
            assertTrue("missing or out of order: " + part, found >= 0);
            at = found + part.length();
        }
    }

    /** P640: where the camera gives the lease back - every way a camera ends, not onClosed alone. */
    @Test public void theCameraGivesTheLeaseBackWhereverACameraEnds() throws Exception {
        final String c = source("src/main/java/com/scamera/app/capture/CaptureController.java");
        // a closed device: at once, on the lease thread; no device (an open that failed): too
        // P680: the lease (and the SDK session) of the camera that closes is taken when the close is scheduled
        ordered(c, "public void closeCamera() {", "final VivoSdkRawSession leaseAtClose = mIszLease;",
                "final VivoSdkRawSession sdkAtClose = mVivoSdkRawSession;", "device.close();", "giveIszLeaseBack(leaseAtClose);",
                "} else if (!wasOpening) {", "if (mVivoSdkRawSession == sdkAtClose) closeVivoSdkRawSession(null);",
                "giveIszLeaseBack(leaseAtClose);");
        // only the lease of the camera that closed, and a look 1.2 s later
        ordered(c, "private void giveIszLeaseBack() {", "giveIszLeaseBack(mIszLease);",
                "private void giveIszLeaseBack(@Nullable VivoSdkRawSession lease) {", "LeaseGuard.executor().execute(",
                "if (mIszLease == lease) closeIszLease();", "LeaseGuard.later(this::leaseWithoutCamera, 1200);");
        ordered(c, "private void leaseWithoutCamera() {", "if (mCameraDevice == null && !mCameraOpening.get()) {", "closeIszLease();",
                "closeVivoSdkRawSession(mVivoSdkRawDevice);", "LeaseGuard.sweep(\"a camera closed\", 0);");
        // a refused open has no onClosed: each of its four exits gives the lease back
        final String open = c.substring(c.indexOf("this.mCameraManager.openCamera(logicalID, mStateCallback,"), c.indexOf("private String mOpenRetryId"));
        assertEquals(4, open.split("openRefused\\(lockHeld\\);", -1).length - 1);
        ordered(c, "private void openRefused(boolean lockHeld) {", "closeVivoSdkRawSession(null);", "closeIszLease();",
                "if (lockHeld) mCameraOpenCloseLock.release();");
        // before a camera opens: what is set and held by nobody goes back first
        ordered(c, "private void prepareVivoSdkRawSession() throws Exception {", "VivoSdkRawSession.sweep(ScameraApp.getAppContext());",
                "closeIszLease();", "openIszLease(id);");
        // onClosed stays the second way
        ordered(c, "public void onClosed(@NonNull CameraDevice cameraDevice) {", "closeVivoSdkRawSession(cameraDevice);", "closeIszLease();");
        // the guard: its own process, nothing of the app in it, armed at the app's start; a crash gives back first
        final String manifest = source("src/main/AndroidManifest.xml");
        ordered(manifest, "android:name=\"com.scamera.app.capture.LeaseGuardService\"", "android:exported=\"false\"",
                "android:process=\":leaseguard\"");
        final String app = source("src/main/java/com/scamera/app/app/ScameraApp.java");
        ordered(app, "public void onCreate() {", "endsWith(\":leaseguard\")", "super.onCreate();", "return;", "initModules();",
                "com.scamera.app.capture.LeaseGuard.install(this);");
        ordered(source("src/main/java/com/scamera/app/util/ScameraCrashReporter.java"),
                "setDefaultUncaughtExceptionHandler", "VivoSdkRawSession.onCrash();", "write(appContext, thread, throwable);");
        final String guard = source("src/main/java/com/scamera/app/capture/LeaseGuardService.java");
        ordered(guard, "handler.removeCallbacks(restore);", "holder.linkToDeath(recipient, 0);");
        ordered(guard, "private void restore() {", "if (token != null) return;", "VivoSdkRawSession.restoreAfterDeath(sdk, vcf)");
        assertFalse("the guard process runs nothing of the app", guard.contains("ScameraApp") || guard.contains("PreferenceKeys"));
    }
}
