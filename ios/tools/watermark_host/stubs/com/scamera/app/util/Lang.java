package com.scamera.app.util;
/** Host stub (ios/tools/watermark_host): the interface language as a switch. */
public final class Lang {
    public static boolean RU = false;
    private Lang() {}
    public static boolean ru() { return RU; }
    public static String t(String ru, String en) { return RU ? ru : en; }
}
