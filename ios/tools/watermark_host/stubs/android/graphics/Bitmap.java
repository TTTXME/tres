package android.graphics;
/** Host stub (ios/tools/watermark_host): ARGB pixels in an int array. */
public class Bitmap {
    public enum Config { ARGB_8888 }
    private final int w, h;
    private final int[] px;
    public Bitmap(int w, int h) { this.w = w; this.h = h; px = new int[w * h]; }
    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public int getPixel(int x, int y) { return px[y * w + x]; }
    public void eraseColor(int c) { java.util.Arrays.fill(px, c); }
    public boolean isMutable() { return true; }
}
