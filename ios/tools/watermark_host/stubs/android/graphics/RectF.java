package android.graphics;
/** Host stub (ios/tools/watermark_host): android.graphics.RectF, the framework's arithmetic. */
public class RectF {
    public float left, top, right, bottom;
    public RectF() {}
    public RectF(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
    public RectF(RectF r) { if (r != null) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; } }
    public final float width() { return right - left; }
    public final float height() { return bottom - top; }
    public final float centerX() { return (left + right) * 0.5f; }
    public final float centerY() { return (top + bottom) * 0.5f; }
    public final boolean isEmpty() { return left >= right || top >= bottom; }
    public void setEmpty() { left = right = top = bottom = 0; }
    public void set(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
    public void set(RectF r) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
    public void inset(float dx, float dy) { left += dx; top += dy; right -= dx; bottom -= dy; }
    public boolean intersect(float l, float t, float r, float b) {
        if (left < r && l < right && top < b && t < bottom) {
            if (left < l) left = l;
            if (top < t) top = t;
            if (right > r) right = r;
            if (bottom > b) bottom = b;
            return true;
        }
        return false;
    }
    public void union(float l, float t, float r, float b) {
        if ((l < r) && (t < b)) {
            if ((left < right) && (top < bottom)) {
                if (left > l) left = l;
                if (top > t) top = t;
                if (right < r) right = r;
                if (bottom < b) bottom = b;
            } else { left = l; top = t; right = r; bottom = b; }
        }
    }
    public void union(RectF r) { union(r.left, r.top, r.right, r.bottom); }
    public boolean contains(RectF r) {
        return left < right && top < bottom && left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom;
    }
    public static boolean intersects(RectF a, RectF b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }
    @Override public String toString() { return "RectF(" + left + ", " + top + ", " + right + ", " + bottom + ")"; }
}
