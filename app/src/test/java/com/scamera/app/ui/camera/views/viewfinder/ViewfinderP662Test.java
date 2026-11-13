package com.scamera.app.ui.camera.views.viewfinder;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P662 (items 1, 2, 5, 6): the RAW viewfinder without vf_fused takes its two textures in turn by default, P314's tone goes straight
 * to the screen only where the copy pass after it is the identity (tools/checks/vf_direct_tone_gl.py), the off-screen targets are
 * invalidated before passes that draw them whole, and the frame's texture is uploaded from the pixel-unpack buffer only when it holds
 * that very frame.
 */
public class ViewfinderP662Test {
    private static final int[] VIEWPORT = {0, 0, 1440, 1920};

    @Test
    public void twoTexturesInTurnByDefault() {
        assertTrue("vf_twin_tex is on without a scam_dev line", LiveRawRenderer.twinTextures());
        final int[] versions = {-1, -1};
        int cur = 0;
        for (int frame = 1; frame < 6; frame++) {
            final int slot = LiveRawRenderer.slotFor(frame, cur, versions, LiveRawRenderer.twinTextures());
            if (frame > 1) assertEquals("a new frame goes into the texture not drawn last", 1 - cur, slot);
            versions[slot] = frame;
            cur = slot;
        }
    }

    @Test
    public void theToneGoesStraightToTheScreenOnlyWhereTheCopyPassChangesNothing() {
        assertTrue(MainRenderer.directTone(1440, 1920, VIEWPORT, false, 0, false, false, true));
        assertFalse("a mosaic's colour grid is scaled up by the copy pass", MainRenderer.directTone(810, 1080, VIEWPORT, false, 0, false, false, true));
        assertFalse("a lens filter", MainRenderer.directTone(1440, 1920, VIEWPORT, true, 0, false, false, true));
        assertFalse("focus peaking", MainRenderer.directTone(1440, 1920, VIEWPORT, false, 1, false, false, true));
        assertFalse("the Oldstyle style", MainRenderer.directTone(1440, 1920, VIEWPORT, false, 0, true, false, true));
        assertFalse("redraws of the same frame reuse the tone target", MainRenderer.directTone(1440, 1920, VIEWPORT, false, 0, false, true, true));
        assertFalse("vf_direct_tone 0", MainRenderer.directTone(1440, 1920, VIEWPORT, false, 0, false, false, false));
        assertFalse("a viewport off the origin", MainRenderer.directTone(1440, 1920, new int[]{0, 8, 1440, 1920}, false, 0, false, false, true));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void targetsAreInvalidatedOnlyBeforePassesThatDrawThemWhole() throws Exception {
        final String tone = source("src/main/java/com/scamera/app/processing/opengl/postpipeline/LiveWhyTone.java");
        // the linear frame, every chain target (draw(Tex)) and the tone target - each before its full-size quad
        final int draw = tone.indexOf("private void draw(Tex t) {");
        assertTrue(draw > 0 && tone.indexOf("invalidateBound(t.fbo);", draw) < tone.indexOf("quad(p[0]);", draw));
        assertTrue(tone.contains("invalidateBound(lin.fbo);"));
        assertTrue(tone.contains("invalidateBound(outFbo); // P662: the tone target (w x h) drawn whole"));
        // the fused pass never invalidates the screen nor a target drawn off its origin
        assertTrue(tone.contains("if (outFbo != 0 && x == 0 && y == 0) invalidateBound(outFbo);"));
        assertTrue(tone.contains("if (fbo != 0 && invalidateSwitch())"));
        assertTrue(source("src/main/java/com/scamera/app/ui/camera/views/viewfinder/LiveLensFilter.java")
                .contains("GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 2, PYRAMID_ATTACHMENTS, 0);"));
    }

    @Test
    public void theTextureIsUploadedFromTheBufferOnlyForItsOwnFrame() throws Exception {
        final String raw = source("src/main/java/com/scamera/app/ui/camera/views/viewfinder/LiveRawRenderer.java");
        final int at = raw.indexOf("private int unpackFromStaging(LiveRawFrame.Frame f){");
        assertTrue(at > 0);
        // this context's buffer, the frame's size, claimed for the frame's version before the unmap
        final int claim = raw.indexOf("if(!LiveRawFrame.claimStaging(s,f.version))return -1;", at);
        assertTrue(raw.indexOf("s.owner!=stagingOwner", at) < claim);
        assertTrue(claim > 0 && claim < raw.indexOf("GLES30.glUnmapBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER)", at));
        // the pixel-unpack binding never stays on (the shading map and the LUT upload from client memory)
        final int upload = raw.indexOf("int pboSlot=unpackFromStaging(f);");
        assertTrue(upload > 0 && raw.indexOf("GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0);", upload)
                < raw.indexOf("GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_RGB16F", upload));
        // the destroyed context's buffers are revoked before the context goes
        final String preview = source("src/main/java/com/scamera/app/ui/camera/views/viewfinder/GLPreview.java");
        assertTrue(preview.indexOf("renderer.onContextDestroying();") < preview.indexOf("egl.eglDestroyContext(display, context);"));
    }
}
