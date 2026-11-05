package com.particlesdevs.photoncamera.ui.camera.views;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;

/**
 * The preview's frame, as the concept draws it (SIMPLE_UI §1: .frame, 24dp corners on the black camera screen): the corners
 * masked in black (the screen's colour) without cropping or resizing the camera buffer (only with «Включить закруглённые
 * края», P118), times the screen's scale ({@link com.particlesdevs.photoncamera.circularbarlib.ui.UiScale}); no outline and
 * no corner marks. The preview stays full-bleed (owner's answer 4: the viewfinder shows the whole shot).
 */
public class ViewfinderFrame extends View {
    /** The camera screen's background (the concept's black phone). */
    public static final int SCREEN = 0xFF000000;
    /** Corner radius before the scale (the concept's .frame). */
    public static final float RADIUS_DP = 24f;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mask=new Path();
    private final RectF frame=new RectF();
    public ViewfinderFrame(Context context,AttributeSet attrs){super(context,attrs);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        if(!rounded())return;
        float r=com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(getContext(),RADIUS_DP);
        frame.set(0,0,getWidth(),getHeight());
        mask.reset();mask.setFillType(Path.FillType.EVEN_ODD);mask.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);mask.addRoundRect(frame,r,r,Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);paint.setColor(screen());canvas.drawPath(mask,paint);
    }
    /**
     * P517 (owner, 2026-11-05: «исправь закругления видоискателя, они чёрные»): the corners in the camera screen's own colour (the
     * theme's cameraFragmentBackground, settings_bg #101416 since the restyle), black when the theme has none.
     */
    private int screen(){
        if(screenColour==0){
            android.util.TypedValue v=new android.util.TypedValue();
            screenColour=getContext().getTheme().resolveAttribute(com.particlesdevs.photoncamera.R.attr.cameraFragmentBackground,v,true)
                    &&v.type>=android.util.TypedValue.TYPE_FIRST_COLOR_INT&&v.type<=android.util.TypedValue.TYPE_LAST_COLOR_INT?v.data:SCREEN;
        }
        return screenColour;
    }
    private int screenColour;
    /** «Включить закруглённые края» (default on; also when the settings are not loaded yet, as in the layout editor). */
    private boolean rounded(){
        if(isInEditMode())return true;
        try{return PreferenceKeys.isRoundEdgeOn();}catch(RuntimeException e){return true;}
    }
}
