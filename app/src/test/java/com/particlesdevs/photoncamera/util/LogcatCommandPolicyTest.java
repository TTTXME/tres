package com.particlesdevs.photoncamera.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class LogcatCommandPolicyTest {
    @Test public void androidEightThroughElevenUseTheSupportedStrictProcessFilter() {
        for(int sdk=26;sdk<=30;sdk++) assertEquals("--pid=4321",ScameraFileLog.clientFilter(sdk,10123,4321));
    }
    @Test public void androidTwelveAndLaterCaptureOnlyTheApplicationUid() {
        for(int sdk=31;sdk<=36;sdk++) assertEquals("--uid=10123",ScameraFileLog.clientFilter(sdk,10123,4321));
    }
}
