package com.particlesdevs.photoncamera.util;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AsyncLogTest {
    @Test public void disabledLoggingNeverQueriesSafOrStartsPermanentFiles() throws Exception {
        ScameraFileLog.setEnabled(false);
        Log.setLogEnabled(true);
        android.app.Application app=RuntimeEnvironment.getApplication();
        java.io.File[] roots={ScameraFileLog.directory(),
                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"SCAMERA"),
                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),"SCAMERA/Log"),
                new java.io.File(app.getExternalFilesDir(null),"logs")};
        String before=inventory(roots);
        try (org.mockito.MockedStatic<SimpleStorageHelper> storage = mockStatic(SimpleStorageHelper.class)) {
            Log.setLogFolder(app);
            for (int i=0;i<5000;i++) Log.d("UI", "entry " + i);
            ScameraTimingLog.shot(RuntimeEnvironment.getApplication(), true, "profile remains independent");
            ScameraTimingLog.summary("SHOT SUMMARY still does not enable file logging");
            storage.verifyNoInteractions();
            assertFalse(ScameraFileLog.isEnabled());
        }
        java.util.concurrent.CountDownLatch drained=new java.util.concurrent.CountDownLatch(1);
        ScameraFileLog.afterFlush(drained::countDown);
        assertTrue(drained.await(5,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("OFF creates no normal log or permanent mirror",before,inventory(roots));
    }

    private static String inventory(java.io.File[] roots) throws Exception {
        java.util.List<String> files=new java.util.ArrayList<>();
        for(java.io.File root:roots) if(root.exists()) {
            try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(root.toPath())) {
                paths.filter(java.nio.file.Files::isRegularFile).forEach(path -> {
                    try { files.add(path+":"+java.nio.file.Files.size(path)); }
                    catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                });
            }
        }
        java.util.Collections.sort(files);
        return files.toString();
    }
}
