package com.scamera.app.settings;

import android.app.Application;
import android.content.Context;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.circularbarlib.ui.views.scaleview.LinearScaleView;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.scamera.app.circularbarlib.camera.ManualWhiteBalance;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewfinderUiTest {
    private Context context;
    private MockedStatic<ScameraApp> camera;
    @Before public void setup(){
        context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager=new SettingsManager(context);
        camera=mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(()->ScameraApp.getStringStatic(anyInt())).thenAnswer(i->context.getString(i.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager);
    }
    @After public void teardown(){camera.close();}
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void compactControlsRenderAndResetWithoutChangingTabLabels() throws Exception {
        compactControlsRenderAndResetWithoutChangingTabLabels(new String[]{"ISO","Выдержка","Экспокоррекция","Фокус","Баланс белого"},"");
    }
    /** The same on an English system: the tab labels (content descriptions) are English. */
    @Test public void compactControlsRenderAndResetWithoutChangingTabLabelsInEnglish() throws Exception {
        compactControlsRenderAndResetWithoutChangingTabLabels(new String[]{"ISO","Shutter","Exposure compensation","Focus","White balance"},"-en");
    }
    private void compactControlsRenderAndResetWithoutChangingTabLabels(String[] labels,String png) throws Exception {
        // Render the production XML controls. The scene is a neutral placeholder;
        // no camera or neural processing is simulated by this screenshot.
        LinearLayout screen=new LinearLayout(context);screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xFF101316);screen.setPadding(12,0,12,0);   // P574: no vertical padding: the bar gets what the 880dp screen really leaves
        View top=LayoutInflater.from(context).inflate(R.layout.camera_top_row,screen,false);
        com.scamera.app.databinding.CameraTopRowBinding tb=com.scamera.app.databinding.CameraTopRowBinding.bind(top);
        tb.executePendingBindings();
        // P25 top bar: the route and format badges, filled as the camera screen fills them.
        com.scamera.app.ui.camera.CameraUIViewImpl.bindBadges(tb);
        screen.addView(top,new LinearLayout.LayoutParams(-1,64));
        // The 3:4 preview of a 400dp-wide phone; the bottom bar gets what an 880dp-tall 20:9 screen leaves it.
        FrameLayout preview=new FrameLayout(context);preview.setBackgroundColor(0xFF45525B);
        screen.addView(preview,new LinearLayout.LayoutParams(-1,533));
        View manual=LayoutInflater.from(context).inflate(R.layout.manual_palette,preview,false);
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);mp.setMargins(16,0,16,52);
        preview.addView(manual,mp);
        var panel=(com.scamera.app.circularbarlib.ui.ExpandingManualPanel)manual;
        assertFalse(panel.isExpanded());assertEquals(View.INVISIBLE,manual.findViewById(R.id.buttons_container).getVisibility());
        panel.setExpanded(true,false);
        View container=manual.findViewById(R.id.knobViewContainer);container.setVisibility(View.VISIBLE);
        LinearScaleView scale=manual.findViewById(R.id.linearScaleView);scale.setVisibility(View.VISIBLE);
        List<KnobItemInfo> items=new ArrayList<>();
        items.add(new KnobItemInfo("Auto",0,0));
        for(int k=2000;k<=10000;k+=100)items.add(new KnobItemInfo(k+"K",items.size(),k));
        scale.setTemperatureMode(true);scale.setItems(items,17);
        scale.setSelectedItem(items.get(17));assertEquals("3600K",scale.getSelected().label);
        ((com.scamera.app.circularbarlib.ui.views.ManualChipView)manual.findViewById(R.id.wb_option_tv)).setChosen(true);
        // The strip's order (MANUAL_TASK.md §2): ISO, shutter, EV, focus, WB; the names are content descriptions only.
        int[] ids={R.id.iso_option_tv,R.id.exposure_option_tv,R.id.ev_option_tv,R.id.focus_option_tv,R.id.wb_option_tv};
        for(int i=0;i<ids.length;i++)assertEquals(labels[i],manual.findViewById(ids[i]).getContentDescription().toString());
        // P25: the lens strip lives in the bottom bar now (above the shutter row, under the zoom ruler's place).
        View bottom=LayoutInflater.from(context).inflate(R.layout.camera_bottom_bar,screen,false);screen.addView(bottom,new LinearLayout.LayoutParams(-1,283));
        var lenses=(com.scamera.app.ui.camera.views.AuxButtonsLayout)bottom.findViewById(R.id.lens_strip);
        var lensModel=new com.scamera.app.ui.camera.model.AuxButtonsModel();
        java.util.List<com.scamera.app.ui.camera.data.CameraLensData> cameraData=new ArrayList<>();
        float[] zoom={2.4f,1f,.4f};for(int i=0;i<zoom.length;i++){var lens=new com.scamera.app.ui.camera.data.CameraLensData(""+i);lens.setZoomFactor(zoom[i]);cameraData.add(lens);}
        lensModel.setBackCameras(cameraData);lensModel.setFrontCameras(new ArrayList<>());lenses.setAuxButtonsModel(lensModel);lenses.setActiveId("1");
        bottom.findViewById(R.id.processing_ring).setVisibility(View.INVISIBLE);
        int exact=View.MeasureSpec.EXACTLY;screen.measure(View.MeasureSpec.makeMeasureSpec(400,exact),View.MeasureSpec.makeMeasureSpec(880,exact));screen.layout(0,0,400,880);
        // SIMPLE_UI §3: the ruler card is one row - the 54dp scale in 5dp padding (no header, no hint line).
        View chipsCard=manual.findViewById(R.id.buttons_container),toggleCard=manual.findViewById(R.id.manual_toggle);
        assertEquals("ruler card",64,container.getHeight());
        assertEquals(View.GONE,toggleCard.getVisibility());assertTrue(chipsCard.getHeight()>=58);   // SIMPLE_UI: «Про» in the bottom bar, no toggle card
        assertEquals(container.getHeight()+6+chipsCard.getHeight(),manual.getHeight());   // P583: the concept's 6px gap
        // SIMPLE_UI §1 top bar; P574 (the approved concept): one centred row - the format badge (format over size), torch,
        // timer, LUT (40dp round items, 16dp apart, x UiScale); nothing overlaps, the row stays inside and centred.
        float k=com.scamera.app.circularbarlib.ui.UiScale.k(context);
        View formatButton=top.findViewById(R.id.format_button),torch=top.findViewById(R.id.torch_button),timer=top.findViewById(R.id.timer_button),lut=top.findViewById(R.id.lut_button);
        assertTrue(formatButton.getRight()<=torch.getLeft());assertTrue(torch.getRight()<=timer.getLeft());assertTrue(timer.getRight()<=lut.getLeft());
        for(View b:new View[]{torch,timer,lut}){assertEquals(Math.round(40*k),b.getHeight());assertEquals("round",b.getHeight(),b.getWidth());}
        assertEquals(Math.round(40*k),formatButton.getHeight());
        assertEquals("centred",formatButton.getLeft(),top.getWidth()-lut.getRight(),2);
        assertNull("the gear is in the bottom row",top.findViewById(R.id.gear_button));
        TextView format=top.findViewById(R.id.format_badge),size=top.findViewById(R.id.size_badge);
        assertTrue(format.getContentDescription().toString().endsWith("JPEG"));assertEquals("JPEG",format.getText().toString());assertEquals(png.isEmpty()?"12 Мп":"12 MP",size.getText().toString());
        assertEquals(View.GONE,top.findViewById(R.id.dcg_badge).getVisibility());assertEquals(View.GONE,top.findViewById(R.id.timer_badge).getVisibility());
        assertEquals(86,bottom.findViewById(R.id.shutter_key).getWidth());   // P583: the concept's 86dp
        assertEquals(R.id.gallery_card,((View)bottom.findViewById(R.id.processing_ring).getParent()).getId());
        // Shutter row: gallery card | shutter exactly in the centre | front / back switch, none overlapping.
        View shutterBox=bottom.findViewById(R.id.shutter_box),gallery=bottom.findViewById(R.id.gallery_card),
                flipBox=bottom.findViewById(R.id.switch_box),row=(View)shutterBox.getParent();
        assertEquals(row.getWidth()/2f,(shutterBox.getLeft()+shutterBox.getRight())/2f,1f);
        assertTrue(gallery.getRight()<=shutterBox.getLeft());assertTrue(flipBox.getLeft()>=shutterBox.getRight());
        assertEquals(58,gallery.getWidth());assertEquals(58,flipBox.getWidth());   // P583: the concept's 58dp
        // P574: the bottom row under the shutter row - the gear, the Sport | Photo switch in the centre, «Про»; none overlapping
        View gearRow=bottom.findViewById(R.id.gear_button),modes=bottom.findViewById(R.id.mode_switch),pro=bottom.findViewById(R.id.pro_button);
        assertTrue(((View)gearRow.getParent()).getTop()>=shutterBox.getBottom());
        assertEquals(row.getWidth()/2f,(((View)modes.getParent()).getLeft()+modes.getLeft()+((View)modes.getParent()).getLeft()+modes.getRight())/2f,2f);
        assertTrue(gearRow.getRight()<=modes.getLeft());assertTrue(modes.getRight()<=pro.getLeft());
        assertEquals(50,gearRow.getWidth());assertEquals(50,pro.getWidth());assertEquals(52,modes.getHeight());
        assertNotNull(bottom.findViewById(R.id.mode_sport));assertNotNull(bottom.findViewById(R.id.mode_photo));
        // The strip: a centred pill above the shutter row, the ruler's place above it, all inside the bottom bar.
        View slot=bottom.findViewById(R.id.zoom_ruler_slot),rowRoot=bottom.findViewById(R.id.shutter_row);
        assertEquals(bottom.getWidth()/2f,(lenses.getLeft()+lenses.getRight())/2f,1f);
        assertTrue(lenses.getBottom()<=rowRoot.getTop());assertTrue(slot.getBottom()<=lenses.getTop());assertTrue("P583: the strip inside the bar",lenses.getTop()>=0);   // the zoom ruler's empty slot (no ruler since P574) may reach over the preview
        for(int i=0;i<lenses.getChildCount();i++){TextView t=(TextView)lenses.getChildAt(i);assertFalse(t.getText().toString().isEmpty());if(png.isEmpty())assertFalse("decimal comma in Russian",t.getText().toString().contains("."));else assertFalse("decimal point in English",t.getText().toString().matches(".*\\d,\\d.*"));assertTrue("Lens label must stay within its button",t.getLayout().getWidth()<=t.getWidth());}
        Bitmap image=Bitmap.createBitmap(400,880,Bitmap.Config.ARGB_8888);screen.draw(new Canvas(image));
        java.io.File dir=new java.io.File("build/reports/viewfinder");dir.mkdirs();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"concept-controls"+png+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
        // P32: with the phone in landscape only the labels turn; the buttons (and the selected pill) keep their portrait size.
        int[] widths=new int[lenses.getChildCount()];for(int i=0;i<widths.length;i++)widths[i]=lenses.getChildAt(i).getWidth();
        for(int orientation:new int[]{90,180,-90,0}){
            lenses.rotateLabels(orientation,0);screen.measure(View.MeasureSpec.makeMeasureSpec(400,exact),View.MeasureSpec.makeMeasureSpec(880,exact));screen.layout(0,0,400,880);
            for(int i=0;i<lenses.getChildCount();i++){var lens=(com.scamera.app.ui.camera.views.LensButton)lenses.getChildAt(i);
                assertEquals(0f,lens.getRotation(),0f);assertEquals((orientation+360)%360,(lens.getLabelRotation()+360)%360,0f);assertEquals(widths[i],lens.getWidth());assertEquals("every lens 44dp high, the active one too, x the screen scale (P583, the concept)",Math.round(44*com.scamera.app.ui.camera.views.BottomChrome.scale(400f)),lens.getHeight());}
            if(orientation==90){screen.draw(new Canvas(image));try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"concept-controls-landscape"+png+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}}
        }
        scale.setListener(new LinearScaleView.OnValueChangedListener(){
            public void onValueChanged(KnobItemInfo item,boolean user){}
            public void onDragStateChanged(boolean dragging){}
            public void onAutoRequested(){scale.setSelectedItem(items.get(0));}
        });
        scale.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,20,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,1,MotionEvent.ACTION_MOVE,150,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,2,MotionEvent.ACTION_UP,150,30,0));
        assertEquals(3600,scale.getSelected().cameraValue,0);assertFalse(scale.isDragging());
        scale.onTouchEvent(MotionEvent.obtain(0,3,MotionEvent.ACTION_DOWN,20,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,4,MotionEvent.ACTION_UP,20,30,0));
        assertEquals(0,scale.getSelected().cameraValue,0);
        panel.setExpanded(false,false);assertEquals(View.GONE,container.getVisibility());assertEquals(0,scale.getSelected().cameraValue,0);
        // Collapsing closes the ruler (§2): expanding again shows the strip, not the ruler.
        panel.setExpanded(true,false);assertEquals(View.GONE,container.getVisibility());assertEquals(View.VISIBLE,manual.findViewById(R.id.buttons_container).getVisibility());
        // The front / back switch took the place of «Фото | Ночь»: a tap reaches it once, a disabled switch ignores taps.
        try(var controller=Robolectric.buildActivity(android.app.Activity.class)){
            controller.setup();
            View bar=LayoutInflater.from(context).inflate(R.layout.camera_bottom_bar,null,false);
            controller.get().setContentView(bar);
            bar.measure(View.MeasureSpec.makeMeasureSpec(400,exact),View.MeasureSpec.makeMeasureSpec(259,exact));bar.layout(0,0,400,259);
            View flip=bar.findViewById(R.id.flip_button);int[] calls={0};flip.setOnClickListener(v->calls[0]++);
            assertTrue(flip.isClickable());assertFalse(flip.getContentDescription().toString().isEmpty());
            float fx=flip.getWidth()/2f,fy=flip.getHeight()/2f;
            flip.dispatchTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,fx,fy,0));flip.dispatchTouchEvent(MotionEvent.obtain(0,10,MotionEvent.ACTION_UP,fx,fy,0));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,calls[0]);
            flip.setEnabled(false);
            flip.dispatchTouchEvent(MotionEvent.obtain(0,20,MotionEvent.ACTION_DOWN,fx,fy,0));flip.dispatchTouchEvent(MotionEvent.obtain(0,30,MotionEvent.ACTION_UP,fx,fy,0));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,calls[0]);
        }
    }
    @Test public void animatedManualControlsRemainVisibleAndReceiveTouchesAfterReversal(){
        try(var controller=Robolectric.buildActivity(android.app.Activity.class)){
            controller.setup();var activity=controller.get();
            var panel=(com.scamera.app.circularbarlib.ui.ExpandingManualPanel)LayoutInflater.from(context).inflate(R.layout.manual_palette,null,false);
            activity.setContentView(panel);panel.measure(View.MeasureSpec.makeMeasureSpec(356,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(160,View.MeasureSpec.AT_MOST));panel.layout(0,0,356,panel.getMeasuredHeight());
            View tabs=panel.findViewById(R.id.buttons_container);int[] clicks={0};
            int[] ids={R.id.ev_option_tv,R.id.exposure_option_tv,R.id.iso_option_tv,R.id.wb_option_tv,R.id.focus_option_tv};
            for(int id:ids)panel.findViewById(id).setOnClickListener(v->clicks[0]++);
            panel.setExpanded(true,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertEquals(View.VISIBLE,tabs.getVisibility());assertEquals(1f,tabs.getAlpha(),.001f);assertNull(tabs.getClipBounds());
            for(int id:ids){View tab=panel.findViewById(id);int[] at=new int[2];tab.getLocationInWindow(at);int[] base=new int[2];panel.getLocationInWindow(base);float x=at[0]-base[0]+tab.getWidth()/2f,y=at[1]-base[1]+tab.getHeight()/2f;long now=android.os.SystemClock.uptimeMillis();
                assertTrue("Touch down must reach a manual control",panel.dispatchTouchEvent(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0)));panel.dispatchTouchEvent(MotionEvent.obtain(now,now+10,MotionEvent.ACTION_UP,x,y,0));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
            assertEquals(5,clicks[0]);
            panel.setExpanded(false,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(80));
            panel.setExpanded(true,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertEquals(View.VISIBLE,tabs.getVisibility());assertEquals(1f,tabs.getAlpha(),.001f);
            panel.setExpanded(false,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));assertEquals(View.INVISIBLE,tabs.getVisibility());
        }
    }
    @Test public void whiteBalanceUsesFiniteSensorGainsAndSupportsAuto(){
        android.hardware.camera2.CameraCharacteristics c=mock(android.hardware.camera2.CameraCharacteristics.class);
        android.hardware.camera2.params.ColorSpaceTransform identity=new android.hardware.camera2.params.ColorSpaceTransform(new int[]{1,1,0,1,0,1,0,1,1,1,0,1,0,1,0,1,1,1});
        when(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(identity);
        when(c.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{0,1});
        when(c.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{2});
        assertTrue(ManualWhiteBalance.isSupported(c));
        for(int k=2000;k<=10000;k+=100){
            double[] xyz=ManualWhiteBalance.xyz(k);for(double v:xyz)assertTrue(Double.isFinite(v)&&v>0);
            android.hardware.camera2.params.RggbChannelVector g=ManualWhiteBalance.gains(c,k);
            assertTrue(g.getRed()>=1 && g.getRed()<=8);assertTrue(g.getBlue()>=1 && g.getBlue()<=8);
        }
        com.scamera.app.circularbarlib.control.ManualParamModel m=new com.scamera.app.circularbarlib.control.ManualParamModel();
        m.reset();assertFalse(m.offAuto());m.setKelvinSetting(3600);assertTrue(m.offAuto());m.reset();assertEquals(0,m.kelvinSetting());
    }
}
