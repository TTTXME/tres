package com.scamera.app.processing.opengl;

import android.graphics.Point;

/**
 * A compute shader's layout as GLInterface.getLayouts reads it: either the binding of a buffer / image it declares, or its
 * local work group size (x, y in {@link #groupXY}, z).
 */
public class GLComputeLayout {
    Point groupXY;
    int groupZ;
    int bindingIndex;

    /** A declared binding. */
    public GLComputeLayout(int binding) {
        bindingIndex = binding;
    }

    /** The local work group size. */
    public GLComputeLayout(int x, int y, int z) {
        groupXY = new Point(x, y);
        groupZ = z;
    }
}
