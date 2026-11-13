package com.scamera.app.settings;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P654 (owner, 2026-11-13: «Настройки "Sensor mode" и Vendor Tags перемести в начало списка Lens Settings»): the module's
 * category heads the lens page and, in it, the sensor mode, the camera keys (vendor tags) and «Добавить ключ» come before the
 * module's other rows (orders 0 .. 20000).
 */
public class LensPageOrderTest {
    @Test public void sensorModeAndKeysComeFirst() throws Exception {
        assertTrue(SensorConfigPreferenceGenerator.SENSOR_MODE_ORDER < SensorConfigPreferenceGenerator.KEYS_ORDER);
        // room for any number of keys a module can hold before «Добавить ключ»
        assertTrue(SensorConfigPreferenceGenerator.KEYS_ORDER + 10000 < SensorConfigPreferenceGenerator.ADD_KEY_ORDER);
        assertTrue("before every other row of the module", SensorConfigPreferenceGenerator.ADD_KEY_ORDER < 0);
        final String g = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/settings/SensorConfigPreferenceGenerator.java")), StandardCharsets.UTF_8);
        assertTrue(g.contains("pick.setOrder(SENSOR_MODE_ORDER);"));
        assertTrue(g.contains("row.setOrder(KEYS_ORDER + index++);"));
        assertTrue(g.contains("add.setOrder(ADD_KEY_ORDER);"));
        final String x = new String(Files.readAllBytes(Paths.get("src/main/res/xml/preferences.xml")), StandardCharsets.UTF_8);
        final int lens = x.indexOf("android:key=\"lens_screen\"");
        assertTrue("the module's category is the first section of the lens page", lens > 0
                && x.indexOf("android:key=\"expert_module_sensor\"", lens) < x.indexOf("android:key=\"lens_raw_category\"", lens));
    }
}
