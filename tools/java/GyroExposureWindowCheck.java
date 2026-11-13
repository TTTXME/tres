import com.scamera.app.control.GyroBurst;
import com.scamera.app.control.GyroExposureWindow;

public final class GyroExposureWindowCheck {
    static void near(float a, float b) {
        if (Math.abs(a-b)>1e-5f) throw new AssertionError(a+" != "+b);
    }
    static GyroBurst get(long[] starts, GyroBurst ring, int next, long t, long duration) {
        return GyroExposureWindow.extract(starts,ring,next,t,duration,true);
    }
    public static void main(String[] args) {
        long ms=1_000_000L;
        GyroBurst ring=new GyroBurst(4);
        // Wrapped ring: slots 2,3,0,1 hold intervals [10,20] ... [40,50].
        long[] starts={30*ms,40*ms,10*ms,20*ms};
        for(int i=0;i<4;i++) {ring.sampleTimes[i]=starts[i]+10*ms;ring.angleSteps[0][i]=1;}
        GyroBurst b=get(starts,ring,2,25*ms,20*ms);
        if(b.sampleCount!=3 || b.sampleTimes[0]!=30*ms || b.sampleTimes[2]!=45*ms)
            throw new AssertionError("Wrong exposure window/order");
        near(b.angleSteps[0][0],.5f);near(b.angleSteps[0][2],.5f);
        near(b.integratedAngles[0],-2);near(b.shakeScore,4);
        // Duration doubles => squared path quadruples, with the same angular rate.
        near(get(starts,ring,2,25*ms,10*ms).shakeScore,1);
        if(get(starts,ring,2,5*ms,20*ms).sampleCount!=0) throw new AssertionError("Missing start accepted");
        if(get(starts,ring,2,45*ms,20*ms).sampleCount!=0) throw new AssertionError("Missing end accepted");
        if(GyroExposureWindow.extract(starts,ring,2,25*ms,20*ms,false).sampleCount!=0)
            throw new AssertionError("Unmatched clocks accepted");
        if(get(starts,ring,2,Long.MAX_VALUE-5,10).sampleCount!=0) throw new AssertionError("Overflow accepted");
        if(get(starts,ring,2,25*ms,0).sampleCount!=0) throw new AssertionError("Zero exposure accepted");
        // Return motion remains blur even if the signed integral cancels.
        ring.angleSteps[0][0]=-1;
        b=get(starts,ring,2,20*ms,20*ms);near(b.integratedAngles[0],0);near(b.shakeScore,4);
        GyroBurst clone=b.clone();b.angleSteps[0][0]=99;b.sampleTimes[0]=99;b.integratedAngles[0]=99;
        near(clone.angleSteps[0][0],1);near(clone.integratedAngles[0],0);
        if(clone.sampleTimes[0]!=30*ms) throw new AssertionError("Clone alias");
        clone.recomputeShake();near(clone.shakeScore,4);
        ring.angleSteps[0][0]=Float.NaN;
        if(get(starts,ring,2,25*ms,20*ms).sampleCount!=0) throw new AssertionError("NaN accepted");
        System.out.println("Gyro PASS: exposure start, fractional edges, ring wrap, squared path, missing samples/clocks, reversal, deep clone");
    }
}
