package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * P635: a lens's recipe as its page stores it - the switch, the fields the owner changed over the preset, «Сбросить к пресету» -
 * and the memory of a failed profile: kept for the lens until a field, the firmware or the preset changes; and the places of
 * CaptureController that run a recipe session, fall back from it and leave everything else alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FullResRecipeStoreTest {
    private static final String FW = "Xiaomi/nezha/nezha:16/BP2A/1:user/release-keys";
    private SharedPreferences prefs;

    @Before public void clean() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("recipe-test", 0);
        prefs.edit().clear().commit();
    }

    @Test public void theSwitchAndTheOwnersFields() {
        assertFalse(FullResRecipeStore.on(prefs, "back1"));
        assertFalse(FullResRecipeStore.on(prefs, null));
        prefs.edit().putBoolean(FullResRecipeStore.key("back1", FullResRecipe.ON), true).commit();
        assertTrue(FullResRecipeStore.on(prefs, "back1"));
        assertFalse("another lens", FullResRecipeStore.on(prefs, "back2"));
        assertEquals("pref_sensorconfig_back1_recipe", FullResRecipeStore.key("back1", FullResRecipe.ON));
        // no edit: the preset itself
        final FullResRecipe preset = FullResRecipeTest.xiaomi();
        assertEquals(preset.signature(), FullResRecipeStore.profile(prefs, "back1", preset, null).signature());
        assertTrue(FullResRecipeStore.edits(prefs, "back1").isEmpty());
        // an edited field lies over it, for its lens only
        prefs.edit().putString(FullResRecipeStore.key("back1", FullResRecipe.FRAMES), "2")
                .putString(FullResRecipeStore.key("back1", FullResRecipe.TAGS), "a.b=i32:7@still").commit();
        final FullResRecipe edited = FullResRecipeStore.profile(prefs, "back1", preset, null);
        assertEquals(2, edited.frames);
        assertEquals(1, edited.tags.size());
        assertEquals(8192, edited.width);
        assertEquals(4, FullResRecipeStore.profile(prefs, "back2", preset, null).frames);
        // a lens without a preset starts from the empty profile: nothing runs until a size is typed
        final FullResRecipe own = FullResRecipeStore.profile(prefs, "back3", null, null);
        assertEquals("size", FullResRecipeStore.blocked(prefs, "back3", own, 36, FW));
        prefs.edit().putString(FullResRecipeStore.key("back3", FullResRecipe.SIZE), "8160x6144").commit();
        assertNull(FullResRecipeStore.blocked(prefs, "back3", FullResRecipeStore.profile(prefs, "back3", null, null), 36, FW));
        // a text that does not parse is named, the preset's field kept
        prefs.edit().putString(FullResRecipeStore.key("back1", FullResRecipe.SIZE), "huge").commit();
        final List<String> problems = new ArrayList<>();
        assertEquals(8192, FullResRecipeStore.profile(prefs, "back1", preset, problems).width);
        assertEquals(1, problems.size());
        // «Сбросить к пресету»: the edits go, the switch stays
        FullResRecipeStore.resetToPreset(prefs, "back1");
        assertTrue(FullResRecipeStore.edits(prefs, "back1").isEmpty());
        assertTrue(FullResRecipeStore.on(prefs, "back1"));
        assertEquals(preset.signature(), FullResRecipeStore.profile(prefs, "back1", preset, null).signature());
    }

    @Test public void aFailedProfileIsRememberedUntilSomethingChanges() {
        final FullResRecipe preset = FullResRecipeTest.xiaomi();
        assertNull(FullResRecipeStore.failure(prefs, "back1", preset, FW));
        assertNull(FullResRecipeStore.blocked(prefs, "back1", preset, 36, FW));
        FullResRecipeStore.fail(prefs, "back1", preset, FW, "camera error 4");
        assertEquals("camera error 4", FullResRecipeStore.failure(prefs, "back1", preset, FW));
        assertEquals("failed:camera error 4", FullResRecipeStore.blocked(prefs, "back1", preset, 36, FW));
        assertNull("another lens", FullResRecipeStore.failure(prefs, "back2", preset, FW));
        // a changed field is a new trial
        prefs.edit().putString(FullResRecipeStore.key("back1", FullResRecipe.PREVIEW), "isp").commit();
        final FullResRecipe edited = FullResRecipeStore.profile(prefs, "back1", preset, null);
        assertNull(FullResRecipeStore.failure(prefs, "back1", edited, FW));
        assertNull(FullResRecipeStore.blocked(prefs, "back1", edited, 36, FW));
        // back to the failed fields: still failed
        prefs.edit().remove(FullResRecipeStore.key("back1", FullResRecipe.PREVIEW)).commit();
        assertNotNull(FullResRecipeStore.failure(prefs, "back1", FullResRecipeStore.profile(prefs, "back1", preset, null), FW));
        // a new firmware tries again
        assertNull(FullResRecipeStore.failure(prefs, "back1", preset, FW + ".next"));
        // the reset forgets it
        FullResRecipeStore.resetToPreset(prefs, "back1");
        assertNull(FullResRecipeStore.failure(prefs, "back1", preset, FW));
        // a blocker of the phone comes before a remembered failure
        FullResRecipeStore.fail(prefs, "back1", preset, FW, "x");
        assertEquals("usecase:33", FullResRecipeStore.blocked(prefs, "back1", preset, 32, FW));
        FullResRecipeStore.clearFailure(prefs, "back1");
        assertNull(FullResRecipeStore.blocked(prefs, "back1", preset, 36, FW));
    }

    private static String controller() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java")), StandardCharsets.UTF_8);
    }

    private static void ordered(String src, String... parts) {
        int at = -1;
        for (String part : parts) {
            final int next = src.indexOf(part, at + 1);
            assertTrue("missing or out of order: " + part, next > at);
            at = next;
        }
    }

    @Test public void theSessionIsBuiltFromThePlan() throws Exception {
        final String src = controller();
        // decided before every other full-size route, which it stands in for (P631's forced RAW-only row included: no pick)
        ordered(src, "recipeSession(sdkFullRes, map, characteristics);", "if (mRecipePlan != null) {\n            mTargetFormat = ImageFormat.RAW_SENSOR;",
                "if (mRecipePlan == null && !sdkFullRes && isRawFormat(mTargetFormat) && !sFullResRefused.contains(physicalID)",
                "final SensorModePick.Pick modePick = sdkFullRes || mFullResSession ");
        // the outputs: the use case and the pixel modes by the plan, on the plan's stream of each surface
        assertTrue(src.contains("if (mRecipePlan != null) recipeOutput(config, surfacei);"));
        assertTrue(src.contains("config.setStreamUseCase(o.useCase);") && src.contains("for (int pixelMode : o.pixelModes) config.addSensorPixelModeUsed(pixelMode);"));
        assertTrue("no CROPPED_RAW use case on a recipe's RAW", src.contains("&& !vivoQcomIsz && mRecipePlan == null"));
        assertTrue(src.contains("if (mRecipePlan != null) return recipeSurfaces(); // P635"));
        // the session: the profile's operation mode, template, frame rate and session tags
        assertTrue(src.contains(": mRecipePlan != null ? mRecipePlan.recipe.operationMode"));
        assertTrue(src.contains("&& mRecipePlan.recipe.sessionTemplate > 0 ? mRecipePlan.recipe.sessionTemplate : CameraDevice.TEMPLATE_PREVIEW"));
        assertTrue(src.contains("FullResRecipeTags.apply(scamSession, mRecipePlan.sessionTags())"));
        // one frame rate on the session, the repeating request and the stills
        assertTrue(src.contains("mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,\n                                        new Range<>(mRecipePlan.recipe.fpsLow, mRecipePlan.recipe.fpsHigh));"));
        // the viewfinder: the plan's streams, the capture RAW never in the repeating request, its frames never in the ring
        assertTrue(src.contains("mIspSurfaceOff = !mRecipePlan.ispStream();"));
        assertTrue(src.contains("mPreviewRequestBuilder.addTarget(mImageReaderRawView.getSurface());"));
        assertTrue(src.contains("NubiaMaxResPlan.rawInRepeating(perShot())"));
        assertTrue(src.contains("final boolean zslPass = isZslMode() && mRecipePlan == null && passModeBarrier(img, result);"));
        assertTrue(src.contains("return nubiaPerShot() || mRecipePlan != null;"));
        // every request's tags come with the other vendor keys
        final String vendor = new String(Files.readAllBytes(Paths.get("src/main/java/com/particlesdevs/photoncamera/api/VendorTagUtils.java")), StandardCharsets.UTF_8);
        assertTrue(vendor.contains("FullResRecipeSession.applyRequest(builder, burst);"));
    }

    @Test public void aStillIsTheProfilesRequest() throws Exception {
        final String src = controller();
        ordered(src, "private void captureStillPicture() {", "final FullResRecipePlan recipePlan = mRecipePlan;",
                "createCaptureRequest(recipePlan != null && recipePlan.recipe.stillTemplate > 0",
                // the template is left as it is: no preview settings copied over it
                "if (recipePlan == null && hybridZslRequested && mPreviewRequestBuilder != null && PreferenceKeys.scamDevSwitch(\"series_as_preview\", true))",
                "captureBuilder.addTarget(mImageReaderRaw.getSurface());",
                "if (recipePlan != null && recipeCompanion != null) captureBuilder.addTarget(recipeCompanion.getSurface());",
                "(recipePlan != null ? recipePlan.viewfinderCompanion() : NubiaMaxResPlan.viewfinderInStill(nubiaPerShot()))",
                "VendorTagUtils.builderSessionApply(captureBuilder, true, useMaximumResolutionKey, physicalID);",
                "final Integer pixelMode = recipePlan.pixelMode(true);",
                "if(recipePlan!=null) plan=plan.limitedTo(recipePlan.recipe.frames);",
                "if(recipePlan!=null&&key==CaptureRequest.CONTROL_CAPTURE_INTENT) continue;",
                "mImageSaver.recipeShot = recipeShot;");
        // the first frame is checked while it is copied; a doubled one is halved
        final String saver = new String(Files.readAllBytes(Paths.get("src/main/java/com/particlesdevs/photoncamera/processing/SaverImplementation.java")), StandardCharsets.UTF_8);
        ordered(saver, "recipe.judge(rows, image.getWidth(), height, rowStride);", "FullResTruth.Verdict.UPSCALED", "recipeHalved(image, buffer, offset, rowStride, height, truth.phase);");
        assertTrue(new String(Files.readAllBytes(Paths.get("src/main/java/com/particlesdevs/photoncamera/processing/ImageSaver.java")), StandardCharsets.UTF_8)
                .contains("implementation.recipeShot = recipeShot;"));
    }

    @Test public void everyFailureGoesBackToTheNormalSession() throws Exception {
        final String src = controller();
        // a refused configuration, the framework's own refusal, a camera error, a disconnect, no viewfinder frame, a shot without a still
        ordered(src, "if (mRecipe != null && (mLastPreviewResultMs == 0 || mShotInProgress || mZslCapturing)) recipeFailed(\"camera disconnected\");",
                "if (retryWithoutRecipe(null, \"camera error \" + error)) return;",
                "if (mRecipe != null && retryWithoutRecipe(mCaptureSession, \"no preview frame for \"",
                "if (retryWithoutRecipe(cameraCaptureSession, \"session configuration failed\")) return; // P635",
                "if (retryWithoutRecipe(null, \"session: \" + e.getMessage())) return; // P635",
                "private boolean retryWithoutRecipe(@Nullable CameraCaptureSession session, String why) {",
                "&& retryWithoutRecipe(session, \"no still of the shot arrived");
        // the failure is remembered with the whole field set in the log, the session's plan dropped, the camera restarted
        final int failed = src.indexOf("private void recipeFailed(String why) {"), retry = src.indexOf("private boolean retryWithoutRecipe(");
        final String body = src.substring(failed, retry);
        for (String part : new String[]{"FullResRecipeStore.failActive(active, why);", "plan.describe(String.valueOf(physicalID))", "mRecipe = null;",
                "mRecipePlan = null;", "FullResRecipeSession.session(null);", "mFullResSession = false;"})
            assertTrue(part, body.contains(part));
        assertTrue(src.substring(retry, retry + 900).contains("new Handler(Looper.getMainLooper()).post(this::restartCamera);"));
        // nothing of it outside a recipe session
        assertTrue(src.substring(retry, retry + 200).contains("if (mRecipe == null) return false;"));
    }
}
