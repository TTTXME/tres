package com.scamera.app.capture.spoof;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** P680: the spoof's diagnosis (every listed camera's characteristics, ids 0 - 9 probed) once per process and spoof signature. */
public class SpoofDiagnosisOnceTest {
    @After public void forget() {
        CameraPackageSpoof.forgetDiagnosisForTest();
    }

    @Test public void onceForASignatureAgainWhenItChanges() {
        CameraPackageSpoof.forgetDiagnosisForTest();
        assertTrue("the first open of the process", CameraPackageSpoof.diagnosisDue("on:a|b|c"));
        assertFalse("every later open", CameraPackageSpoof.diagnosisDue("on:a|b|c"));
        assertFalse(CameraPackageSpoof.diagnosisDue("on:a|b|c"));
        assertTrue("another package configured", CameraPackageSpoof.diagnosisDue("on:a|x|c"));
        assertFalse(CameraPackageSpoof.diagnosisDue("on:a|x|c"));
        assertTrue("back to the first one", CameraPackageSpoof.diagnosisDue("on:a|b|c"));
        assertFalse("no signature", CameraPackageSpoof.diagnosisDue(null));
    }
}
