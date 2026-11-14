package android.graphics;
/** Host stub (ios/tools/watermark_host). */
public class Path {
    public enum FillType { WINDING, EVEN_ODD }
    public enum Direction { CW, CCW }
    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }
    public void setFillType(FillType t) {}
    public void addRect(float l, float t, float r, float b, Direction d) {}
    public void addRoundRect(RectF r, float rx, float ry, Direction d) {}
    public void addCircle(float x, float y, float r, Direction d) {}
    public boolean op(Path p, Op op) { return true; }
}
