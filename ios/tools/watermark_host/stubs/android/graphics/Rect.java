package android.graphics;
/** Host stub (ios/tools/watermark_host): android.graphics.Rect as WatermarkArea uses it. */
public class Rect {
    public int left, top, right, bottom;
    public Rect() {}
    public Rect(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public Rect(Rect r) { this(r.left, r.top, r.right, r.bottom); }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
    public boolean isEmpty() { return left >= right || top >= bottom; }
    public void union(Rect r) {
        if (r.left < r.right && r.top < r.bottom) {
            if (left < right && top < bottom) {
                left = Math.min(left, r.left); top = Math.min(top, r.top); right = Math.max(right, r.right); bottom = Math.max(bottom, r.bottom);
            } else { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
        }
    }
    public boolean intersect(int l, int t, int r, int b) {
        if (left < r && l < right && top < b && t < bottom) {
            left = Math.max(left, l); top = Math.max(top, t); right = Math.min(right, r); bottom = Math.min(bottom, b);
            return true;
        }
        return false;
    }
    public String toShortString() { return "[" + left + "," + top + "][" + right + "," + bottom + "]"; }
}
