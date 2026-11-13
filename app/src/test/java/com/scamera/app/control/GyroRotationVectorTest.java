package com.scamera.app.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSensor;

/** P662: the rotation vector (SENSOR_DELAY_FASTEST) runs only while the horizon indicator is shown, the gyroscope always. */
@RunWith(RobolectricTestRunner.class)
public class GyroRotationVectorTest {
    private static Gyro gyro(SensorManager sensors) {
        shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE));
        shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR));
        return new Gyro(sensors);
    }

    @Test
    public void theRotationVectorFollowsTheHorizon() {
        final SensorManager sensors = (SensorManager) RuntimeEnvironment.getApplication().getSystemService(Context.SENSOR_SERVICE);
        final Gyro g = gyro(sensors);
        g.register();
        assertFalse("horizon off (the default): no rotation vector", g.rotationListening());
        g.setRotationWanted(true);
        assertTrue("horizon shown: registered", g.rotationListening());
        g.setRotationWanted(false);
        assertFalse("horizon hidden: unregistered", g.rotationListening());
        g.setRotationWanted(true);
        g.unregister();
        assertFalse("the camera screen paused: nothing", g.rotationListening());
        g.register();
        assertTrue("back with the horizon still shown: registered again", g.rotationListening());
        g.unregister();
    }

    @Test
    public void aHorizonShownBeforeTheSensorsStartWaitsForThem() {
        final SensorManager sensors = (SensorManager) RuntimeEnvironment.getApplication().getSystemService(Context.SENSOR_SERVICE);
        final Gyro g = gyro(sensors);
        g.setRotationWanted(true);
        assertFalse("not before the camera screen registers the sensors", g.rotationListening());
        g.register();
        assertTrue(g.rotationListening());
        g.unregister();
    }
}
