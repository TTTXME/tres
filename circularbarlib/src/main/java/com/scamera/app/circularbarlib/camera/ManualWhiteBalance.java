package com.scamera.app.circularbarlib.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;

/** Kelvin -> sensor neutral using the camera's own XYZ and calibration matrices.
 * The light: Krystek's (1985) Planckian locus in CIE 1960 uv ({@link #planckXyz}, the photo's white estimate DngColor reads the
 * neutral back on it); {@link #xyz} is Kang et al.'s (2002) xy fit, as documented by Colour Science:
 * https://colour.readthedocs.io/en/v0.3.9/_modules/colour/temperature/cct.html
 * The two calibrations are interpolated the DNG SDK's way (each ColorMatrix normalised, 1/T between the SDK's illuminant kelvin),
 * P581. Matrix directions and manual control contract: Android Camera2 characteristics/requests.
 */
public final class ManualWhiteBalance {
    private ManualWhiteBalance() {}
    public static boolean isSupported(CameraCharacteristics camera) {
        if(camera==null || camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)==null)return false;
        int[] modes=camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        int[] caps=camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean off=false,manual=false;
        if(modes!=null)for(int m:modes)off|=m==CaptureRequest.CONTROL_AWB_MODE_OFF;
        if(caps!=null)for(int c:caps)manual|=c==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING;
        return off && manual;
    }
    /**
     * P581: XYZ (Y 1) of the Planckian light at {@code kelvin} (2000 .. 10000) on Krystek's (1985) locus in CIE 1960 uv - the one
     * the photo's white estimate places a white on (DngColor.planckUv, |uv error| < 1e-4), so a hand-set kelvin reads back as
     * itself and a grey card at that light ends grey (Kang's fit {@link #xyz} is up to 3.2e-4 off it in uv: 0.58 % on the card at
     * 2500 K). {@link #xyz} stays the mosaic prior's.
     */
    public static double[] planckXyz(int kelvin) {
        double t=Math.max(2000,Math.min(10000,kelvin));
        double u=(0.860117757+1.54118254e-4*t+1.28641212e-7*t*t)/(1+8.42420235e-4*t+7.08145163e-7*t*t);
        double v=(0.317398726+4.22806245e-5*t+4.20481691e-8*t*t)/(1-2.89741816e-5*t+1.61456053e-7*t*t);
        double d=2*u-8*v+4,x=3*u/d,y=2*v/d;
        return new double[]{x/y,1,(1-x-y)/y};
    }
    public static double[] xyz(int kelvin) {
        double t=Math.max(2000,Math.min(10000,kelvin)),t2=t*t,t3=t2*t;
        double x=t<=4000 ? -0.2661239e9/t3-0.2343589e6/t2+0.8776956e3/t+0.179910
                : -3.0258469e9/t3+2.1070379e6/t2+0.2226347e3/t+0.240390;
        double y=t<=2222 ? -1.1063814*x*x*x-1.34811020*x*x+2.18555832*x-0.20219683
                : t<=4000 ? -0.9549476*x*x*x-1.37418593*x*x+2.09137015*x-0.16748867
                : 3.0817580*x*x*x-5.8733867*x*x+3.75112997*x-0.37001483;
        return new double[]{x/y,1,(1-x-y)/y};
    }
    /**
     * EXIF LightSource -> kelvin, the DNG SDK's table (as DngColor.illuminantTemperature, the photo's white estimate), so a hand-set
     * kelvin reads back as itself in the photo (sceneCct, the DNG's As-Shot temperature, the gallery's RAW «Температура»).
     */
    static double temperature(Number illuminant) {
        if(illuminant==null)return 0;
        switch(illuminant.intValue()) {
            case 1:case 4:case 9:case 18:case 20:return 5500;
            case 2:case 14:return 4150;
            case 3:case 17:return 2850;
            case 10:case 19:case 21:return 6500;
            case 11:case 22:return 7500;
            case 12:return 6400;
            case 13:return 5050;
            case 15:return 3525;
            case 16:return 2925;
            case 23:return 5000;
            case 24:return 3200;
            default:return 0;
        }
    }
    /** XYZ of D50 (xy 0.3457 / 0.3585, Y 1). */
    private static final double[] D50={0.3457/0.3585,1,(1-0.3457-0.3585)/0.3585};
    /**
     * The DNG SDK's NormalizeColorMatrix scale of a ColorMatrix: the largest component of ColorMatrix x D50 when it is outside
     * 0.99 .. 1.01, else 1 (as DngColor.sdkNormalised). The two calibrations are interpolated at that scale: the HAL's raw
     * rationals of the X200 Ultra differ by 13 % (CM2 x D50 1.13, CM1 0.99), which moved a hand-set 5000 K to 5048 K in the photo.
     */
    static double colourMatrixScale(ColorSpaceTransform m) {
        if(m==null)return 1;
        double[] v=multiply(m,D50);
        double max=Math.max(v[0],Math.max(v[1],v[2]));
        return !(max>0) || !Double.isFinite(max) || (max>=0.99 && max<=1.01) ? 1 : max;
    }
    private static double[] multiply(ColorSpaceTransform m,double[] v) {
        if(m==null)return v.clone();
        double[] out=new double[3];
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)out[row]+=m.getElement(col,row).doubleValue()*v[col];
        return out;
    }
    private static double[] scaled(double[] v,double s) {
        return new double[]{v[0]/s,v[1]/s,v[2]/s};
    }
    public static RggbChannelVector gains(CameraCharacteristics camera,int kelvin) {
        double[] white=planckXyz(kelvin);
        ColorSpaceTransform c1=camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1);
        ColorSpaceTransform c2=camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2);
        double[] n1=scaled(multiply(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1),multiply(c1,white)),colourMatrixScale(c1));
        double t1=temperature(camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1));
        double t2=temperature(camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2));
        if(c2!=null && t1>0 && t2>0 && t1!=t2) {
            double weight=Math.max(0,Math.min(1,(1.0/kelvin-1.0/t1)/(1.0/t2-1.0/t1)));
            double[] n2=scaled(multiply(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2),multiply(c2,white)),colourMatrixScale(c2));
            for(int i=0;i<3;i++)n1[i]=n1[i]*(1-weight)+n2[i]*weight;
        }
        double maximum=Math.max(n1[0],Math.max(n1[1],n1[2]));
        float[] gains=new float[3];
        for(int i=0;i<3;i++) {
            if(!Double.isFinite(n1[i]) || n1[i]<=0)throw new IllegalArgumentException("Invalid sensor white balance calibration");
            gains[i]=(float)Math.max(1,Math.min(8,maximum/n1[i]));
        }
        return new RggbChannelVector(gains[0],gains[1],gains[1],gains[2]);
    }
}
