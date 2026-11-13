package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;
import android.opengl.GLES20;
import android.opengl.GLES31;
import com.scamera.app.processing.opengl.GLBuffer;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.util.Log;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ScamRgbOpposedSparseTest {
    private void check(boolean uploadFails,boolean compileFails,int crop,boolean flags,int expectedBuffers) throws Exception {
        Point size=mock(Point.class);size.x=64;size.y=64;
        GLTexture input=mock(GLTexture.class),gain=mock(GLTexture.class),flag=mock(GLTexture.class);
        input.size=size;flag.size=size;
        ScamRgb node=new ScamRgb();node.shaderProg=mock(GLProg.class);
        if(compileFails)doThrow(new IllegalStateException("test compile failure")).doNothing().when(node.shaderProg).useAssetProgram("hlrecovery/opposed",true);
        int[] sums=new int[16*6];sums[6]=Float.floatToIntBits(16777216f);sums[9]=1;
        sums[7*6]=Float.floatToIntBits(1f);sums[7*6+3]=1;
        sums[15*6]=Float.floatToIntBits(-16777216f);sums[15*6+3]=1;
        try(MockedStatic<GLES20> gl=mockStatic(GLES20.class);MockedStatic<Log> log=mockStatic(Log.class);
            MockedConstruction<GLBuffer> buffers=mockConstruction(GLBuffer.class,(b,ctx)->{
                b.bytes=(Integer)ctx.arguments().get(0)*4;
                b.clientCopy=ByteBuffer.allocateDirect(b.bytes).order(ByteOrder.nativeOrder());
                when(b.readBufferIntegers(false)).thenReturn(sums);
            })){
            gl.when(()->GLES31.glGetBufferParameteriv(eq(GLES31.GL_SHADER_STORAGE_BUFFER),eq(GLES31.GL_BUFFER_SIZE),any(int[].class),eq(0)))
                    .thenAnswer(inv->{((int[])inv.getArgument(2))[0]=uploadFails?0:buffers.constructed().get(buffers.constructed().size()-1).bytes;return null;});
            Method method=ScamRgb.class.getDeclaredMethod("opposedChroma",GLTexture.class,GLTexture.class,GLTexture.class,float[].class,ScamRgb.ChannelClip.class,Point.class,int.class,int.class,int[].class);
            method.setAccessible(true);
            float[] actual=(float[])method.invoke(node,input,gain,flags?flag:null,new float[]{1,1,1},new ScamRgb.ChannelClip(),size,crop,0,new int[]{1,7,15});
            assertArrayEquals(new float[]{1f/3f,0,0},actual,0f);
            assertEquals(expectedBuffers,buffers.constructed().size());
            for(GLBuffer buffer:buffers.constructed())verify(buffer).close();
            if(uploadFails||crop!=0||!flags)verify(node.shaderProg,never()).setDefine("SPARSE",1);
            else verify(node.shaderProg).setDefine("SPARSE",1);
            if(uploadFails||compileFails||crop!=0||!flags){
                verify(node.shaderProg).setDefine("SPARSE",0);
                verify(node.shaderProg).computeManual(4,4,1);
                verify(node.shaderProg,never()).setBufferCompute(eq("oppWorkGroups"),any());
            }else{
                verify(node.shaderProg).computeManual(3,1,1);
                verify(node.shaderProg).setBufferCompute("oppWorkGroups",buffers.constructed().get(1));
                verify(node.shaderProg,never()).setDefine("SPARSE",0);
            }
        }
    }

    @Test public void uploadFailureRetriesOriginalAndClosesBuffers() throws Exception {check(true,false,0,true,3);}
    @Test public void compileFailureRetriesOriginalAndReplacesPendingDefine() throws Exception {check(false,true,0,true,3);}
    @Test public void cropUsesOriginalGrid() throws Exception {check(false,false,1,true,1);}
    @Test public void missingFlagsUseOriginalGrid() throws Exception {check(false,false,0,false,1);}
    @Test public void sparseKeepsFullOutputSlotsAndDoubleSumOrder() throws Exception {check(false,false,0,true,2);}
}
