package com.scamera.app.app;

import android.app.Application;
import android.content.Context;
import com.scamera.app.api.Settings;
import com.scamera.app.control.Gravity;
import com.scamera.app.control.Gyro;
import com.scamera.app.control.Vibration;
import com.scamera.app.gallery.GalleryLauncherIcon;
import com.scamera.app.settings.MigrationManager;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.util.AssetLoader;
import com.scamera.app.util.ExitReasons;
import com.scamera.app.util.ScameraCrashReporter;
import com.scamera.app.util.ScameraDebugLog;
import com.scamera.app.util.SimpleStorageHelper;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class ScameraAppLoggingTest {
    @After public void clearApplication() { ReflectionHelpers.setStaticField(ScameraApp.class,"sApp",null); }

    private void start(String process,boolean guarded) throws Exception {
        ScameraApp app=new ScameraApp();
        ReflectionHelpers.callInstanceMethod(app,"attachBaseContext",ClassParameter.from(Context.class,RuntimeEnvironment.getApplication()));
        ReflectionHelpers.setStaticField(ScameraApp.class,"sApp",app);
        List<String> events=new ArrayList<>();
        try(var name=mockStatic(Application.class);
            var crash=mockStatic(ScameraCrashReporter.class);
            var storage=mockStatic(SimpleStorageHelper.class);
            var migration=mockStatic(MigrationManager.class);
            var keys=mockStatic(PreferenceKeys.class);
            var log=mockStatic(ScameraDebugLog.class);
            var exits=mockStatic(ExitReasons.class);
            var gallery=mockStatic(GalleryLauncherIcon.class);
            var settings=mockConstruction(SettingsManager.class);
            var gravity=mockConstruction(Gravity.class);
            var gyro=mockConstruction(Gyro.class);
            var vibration=mockConstruction(Vibration.class);
            var values=mockConstruction(Settings.class);
            var assets=mockConstruction(AssetLoader.class)) {
            name.when(Application::getProcessName).thenReturn(process);
            crash.when(()->ScameraCrashReporter.install(app)).thenAnswer(call->{events.add("crash");return null;});
            keys.when(()->PreferenceKeys.initialise(any(SettingsManager.class))).thenAnswer(call->{events.add("settings");return null;});
            log.when(()->ScameraDebugLog.init(app)).thenAnswer(call->{assertNotNull(app.getSettingsManager());events.add("logging");return null;});
            exits.when(()->ExitReasons.logPrevious(app)).thenAnswer(call->{events.add("exit");return null;});
            ReflectionHelpers.setStaticField(ScameraApp.class,"sApp",null);
            app.onCreate();
            crash.verify(()->ScameraCrashReporter.install(app));
            if(guarded){
                assertNull(ScameraApp.getAppContext());
                assertEquals(Arrays.asList("crash"),events);
                assertTrue(settings.constructed().isEmpty());log.verifyNoInteractions();exits.verifyNoInteractions();
            } else {
                assertEquals(Arrays.asList("crash","settings","logging","exit"),events);
                assertEquals(1,settings.constructed().size());log.verify(()->ScameraDebugLog.init(app));
            }
        }
    }

    @Test public void mainProcessStartsLoggingWithoutOpeningCameraActivity() throws Exception { start("com.scamera.app",false); }
    @Test public void firmwareProcessKeepsOnlyMinimalCrashReporter() throws Exception { start("com.scamera.app:scam",true); }
}
