package android.graphics;
/**
 * Host stub (ios/tools/watermark_host): text measured with fixed advances (em) per UTF-16 unit - the same table as
 * ios/Tests/WatermarkTests.swift's FixedFonts - so the layout of the real WatermarkPainter can be compared with the Swift port.
 */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1, SUBPIXEL_TEXT_FLAG = 128, LINEAR_TEXT_FLAG = 64;
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public static class FontMetrics { public float top, ascent, descent, bottom, leading; }
    private Typeface typeface;
    private float size = 12, spacing;
    public Paint() {}
    public Paint(int flags) {}
    public Paint(Paint p) { typeface = p.typeface; size = p.size; spacing = p.spacing; }
    public Typeface setTypeface(Typeface t) { typeface = t; return t; }
    public void setTextSize(float s) { size = s; }
    public void setLetterSpacing(float s) { spacing = s; }
    public void setStyle(Style s) {}
    public void setColor(int c) {}
    public void setStrokeWidth(float w) {}
    public void setShadowLayer(float r, float dx, float dy, int c) {}

    /** The advance of one UTF-16 unit (em). */
    public static float advance(char c, boolean mono) {
        if (mono) return .6f;
        if (c == ' ') return .28f;
        if (c >= '0' && c <= '9') return .56f;
        if (c == '…') return .9f;
        if (c >= 'A' && c <= 'Z') return .66f;
        return .52f;
    }

    public float measureText(String text) {
        final boolean mono = typeface != null && "mono".equals(typeface.family);
        final float k = typeface != null && typeface.weight >= 600 ? 1.04f : 1f;
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += size * (advance(text.charAt(i), mono) * k + spacing);
        return w;
    }

    public FontMetrics getFontMetrics() {
        FontMetrics m = new FontMetrics();
        m.ascent = -.93f * size;
        m.descent = .24f * size;
        return m;
    }
}
