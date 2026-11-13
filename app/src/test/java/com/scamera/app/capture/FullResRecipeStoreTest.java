package com.scamera.app.capture;

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

    /** P635b: a module's picked row as wide as its camera's proven preset runs as the recipe (the row alone gives no full frame). */
    @Test public void aPickedFullSizeRowRunsAsTheRecipe() {
        final FullResRecipe preset = FullResRecipeTest.xiaomi();
        final int[] table = XiaomiSensorRowTest.MAIN;
        // the owner's rows of the third log: 11 and 0 (8192x6144), 22 (8192x4608, the 16:9 one: the recipe's 8192x6144 frame)
        assertTrue(FullResRecipeStore.autoRoute(11, table, preset));
        assertTrue(FullResRecipeStore.autoRoute(0, table, preset));
        assertTrue(FullResRecipeStore.autoRoute(22, table, preset));
        // a binned row, a narrower one, Auto: the row itself
        assertFalse(FullResRecipeStore.autoRoute(1, table, preset));
        assertFalse("7680x4320", FullResRecipeStore.autoRoute(31, table, preset));
        assertFalse("5888x4416", FullResRecipeStore.autoRoute(37, table, preset));
        assertFalse(FullResRecipeStore.autoRoute(-1, table, preset));
        assertFalse("no preset: nothing to run instead", FullResRecipeStore.autoRoute(11, table, null));
        assertFalse(FullResRecipeStore.autoRoute(11, null, preset));
        // a guess never replaces what the owner picked
        assertFalse(FullResRecipeStore.autoRoute(0, FullResRecipeTest.TABLE, FullResRecipePresets.preset(FullResRecipeTest.NUBIA, "2", FullResRecipeTest.TABLE)));
        assertEquals(-1, new FullResRecipeStore.Active("back1", preset).autoRow);
        assertEquals(11, new FullResRecipeStore.Active("back1", preset, 11).autoRow);
    }

    /** P635b: a profile whose frame was judged real needs no proof still again - until a field, the firmware or the preset changes. */
    @Test public void aProvenProfileIsRememberedLikeAFailedOne() {
        final FullResRecipe preset = FullResRecipeTest.xiaomi();
        assertFalse(FullResRecipeStore.proved(prefs, "back1", preset, FW));
        FullResRecipeStore.prove(prefs, "back1", preset, FW);
        assertTrue(FullResRecipeStore.proved(prefs, "back1", preset, FW));
        assertFalse("another lens", FullResRecipeStore.proved(prefs, "back2", preset, FW));
        assertFalse("a new firmware", FullResRecipeStore.proved(prefs, "back1", preset, FW + ".next"));
        prefs.edit().putString(FullResRecipeStore.key("back1", FullResRecipe.FRAMES), "1").commit();
        assertFalse("a changed field", FullResRecipeStore.proved(prefs, "back1", FullResRecipeStore.profile(prefs, "back1", preset, null), FW));
        prefs.edit().remove(FullResRecipeStore.key("back1", FullResRecipe.FRAMES)).commit();
        assertTrue(FullResRecipeStore.proved(prefs, "back1", preset, FW));
        FullResRecipeStore.resetToPreset(prefs, "back1");
        assertFalse(FullResRecipeStore.proved(prefs, "back1", preset, FW));
        assertFalse(FullResRecipeStore.provedActive(null));
    }

    @Test public void aBrokenStreamIsCaughtBeforeAndAtTheShot() throws Exception {
        final String src = controller();
        // a picked row's first viewfinder frame is judged; not real: the module back on Auto with the message that names the recipe
        ordered(src, "private void rowNotReal(SensorModePick.Pick pick, FullResTruth.Result truth) {",
                "putString(SensorModePick.prefKey(slot), \"-1\")", "truth.line(\"FULLRES ROW\")",
                "включите «Полное разрешение (рецепт)» в настройках объектива", "mainHandler().post(this::restartCamera);");
        ordered(src, "private void xiaomiRowFrame(Image img, TotalCaptureResult result) {", "final FullResTruth.Result truth = FullResTruth.check(",
                "if (truth.notReal(true) && PreferenceKeys.scamDevSwitch(\"fullres_row_truth\", true)) rowNotReal(pick, truth);");
        // an unproven recipe: one proof still after the first preview result, its frame judged before the owner shoots
        ordered(src, "if (mRecipeProof == 2 && mRecipePlan != null && !mNativeRawPslCapture && !mScamRouted) {", "recipeProofFrame(proof);",
                "if (mRecipeProof == 1 && mRecipePlan != null && session == mCaptureSession) sendRecipeProof();",
                "mRecipeProof = FullResRecipeStore.provedActive(active) ? 0 : 1;", "private void sendRecipeProof() {",
                "retryWithoutRecipe(mCaptureSession, \"no frame of the proof still in \"", "private void recipeProofFrame(Image img) {",
                "if (truth.notReal(XiaomiSensorRow.device())) {", "FullResRecipeStore.proveActive(active);");
        // the shot: partial and doubled frames alike fail the profile; a real one proves it
        ordered(src, "private void recipeVerdict(FullResRecipeShot shot) {", "if (!shot.notReal()) {", "FullResRecipeStore.proveActive(mRecipe);",
                "recipeFailed(\"the stills are not the sensor's full readout (\"");
        final String saver = new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/processing/SaverImplementation.java")), StandardCharsets.UTF_8);
        // the truth check on the whole buffer before the 16:9 crop; a frame that is neither real nor a binned frame is refused
        ordered(saver, "recipe.judge(buffer, image.getWidth(), rows.height, rows.stride);", "FullResTruth.Verdict.PARTIAL",
                "return recipeFrame(image, buffer, rows, truth);", "rows.width > rows.height) cropTo169(image, rows);");
        ordered(saver, "private ImageFrame recipeFrame(", "recipeBinned(image, buffer, rows.stride, rows.height, truth);",
                "refused.rawPayloadError = \"the full-size buffer holds no full frame");
        assertTrue(saver.contains("FullResTruth.unpackQuarter(out, buffer, width, height, rowStride)"));
    }

    private static String controller() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/capture/CaptureController.java")), StandardCharsets.UTF_8);
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
        assertTrue(src.contains("if (mRecipePlan != null) recipeOutput(config, out);"));
        assertTrue(src.contains("config.setStreamUseCase(o.useCase);") && src.contains("for (int pixelMode : o.pixelModes) config.addSensorPixelModeUsed(pixelMode);"));
        assertTrue("no CROPPED_RAW use case on a recipe's RAW", src.contains("&& !vivoQcomIsz && mRecipePlan == null"));
        assertTrue(src.contains("if (mRecipePlan != null) return recipeSurfaces(); // P635"));
        // the session: the profile's operation mode, template, frame rate and session tags
        assertTrue(src.contains(": mRecipePlan != null ? mRecipePlan.recipe.operationMode"));
        assertTrue(src.contains("&& mRecipePlan.recipe.sessionTemplate > 0 ? mRecipePlan.recipe.sessionTemplate : CameraDevice.TEMPLATE_PREVIEW"));
        assertTrue(src.contains("FullResRecipeTags.apply(scamSession, mRecipePlan.sessionTags())"));
        // one frame rate on the session, the repeating request and the stills
        assertTrue(src.contains("preview.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mRecipePlan.recipe.fpsLow, mRecipePlan.recipe.fpsHigh));"));
        // the viewfinder: the plan's streams, the capture RAW never in the repeating request, its frames never in the ring
        assertTrue(src.contains("mIspSurfaceOff = !mRecipePlan.ispStream();"));
        assertTrue(src.contains("b.addTarget(mImageReaderRawView.getSurface());"));
        assertTrue(src.contains("NubiaMaxResPlan.rawInRepeating(perShot())"));
        assertTrue(src.contains("final boolean zslPass = isZslMode() && mRecipePlan == null && passModeBarrier(img, result);"));
        assertTrue(src.contains("return nubiaPerShot() || mRecipePlan != null;"));
        // every request's tags come with the other vendor keys
        final String vendor = new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/api/VendorTagUtils.java")), StandardCharsets.UTF_8);
        assertTrue(vendor.contains("FullResRecipeSession.applyRequest(builder, burst);"));
    }

    @Test public void aStillIsTheProfilesRequest() throws Exception {
        final String src = controller();
        ordered(src, "private void takeStill() {", "final FullResRecipePlan recipePlan = mRecipePlan;",
                "createCaptureRequest(recipePlan != null && recipePlan.recipe.stillTemplate > 0",
                // the template is left as it is: no preview settings copied over it
                "if (recipePlan == null && hybridZslRequested && mPreviewRequestBuilder != null && PreferenceKeys.scamDevSwitch(\"series_as_preview\", true))",
                "stillRequest.addTarget(mImageReaderRaw.getSurface());",
                "if (recipePlan != null && recipeCompanion != null) stillRequest.addTarget(recipeCompanion.getSurface());",
                "(recipePlan != null ? recipePlan.viewfinderCompanion() : NubiaMaxResPlan.viewfinderInStill(nubiaPerShot()))",
                "VendorTagUtils.builderSessionApply(stillRequest, true, useMaximumResolutionKey, physicalID);",
                "final Integer pixelMode = recipePlan.pixelMode(true);",
                "if(recipePlan!=null) plan=plan.limitedTo(recipePlan.recipe.frames);",
                "if(recipePlan!=null&&key==CaptureRequest.CONTROL_CAPTURE_INTENT) continue;",
                "mImageSaver.recipeShot = recipeShot;");
        // the first frame is checked while it is copied; a doubled one is halved
        final String saver = new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/processing/SaverImplementation.java")), StandardCharsets.UTF_8);
        ordered(saver, "recipe.judge(buffer, image.getWidth(), rows.height, rows.stride);", "FullResTruth.Verdict.UPSCALED",
                "recipeBinned(image, buffer, rows.stride, rows.height, truth);",
                "FullResTruth.halve(out, buffer, 0, width, height, rowStride, truth.phase)");
        assertTrue(new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/processing/ImageSaver.java")), StandardCharsets.UTF_8)
                .contains("saverImpl.recipeShot = recipeShot;"));
    }

    /**
     * P635c: the viewfinder frames of a recipe session are measured before anything reads them, every reader of a viewfinder
     * frame's samples takes the session's viewfinder levels, the photo's levels stay its own frames'; the AF line; a still keeps
     * a hand-set focus; the counter shows the profile's frames.
     */
    @Test public void theViewfinderOfARecipeSessionHasItsOwnLevelsAndItsAfIsLogged() throws Exception {
        final String src = controller(), code = FullResRecipeTest.code(src);
        ordered(src, "private void onMatchedLiveRaw(Image img, TotalCaptureResult result) {", "recipeViewFloor(img, result);", "watchRawPayload(img);",
                "observeRawLevel(img, result);", "publishLiveRawFrame(img, result);");
        // the developed viewfinder, the motion level and the RAW meters
        ordered(src, "private void observeRawLevel(Image img, CaptureResult result) {", "levels = liveLevels(c, result);");
        ordered(src, "private LiveRawFrame.Meta liveRawMeta(Image img, TotalCaptureResult matchedResult) {", "levels = liveLevels(c, colorResult);",
                ": mRecipePlan != null ? 1 // P635");
        for (String meter : new String[]{"private void unspektraMeter(", "private void nativeExposure(", "private void plainTeacher(",
                "private void ettrMeter(", "private void plainMeter(", "private void vivoAeAudit("})
            ordered(src, meter, "liveLevels(c, result);");
        assertEquals("the session's levels with a frame's result are read in liveLevels and for its log line alone", 2,
                code.split("RawLevels\\.current\\(c, result\\)", -1).length - 1);
        final int levels = src.indexOf("private com.scamera.app.processing.RawLevels.Levels liveLevels(");
        final String body = src.substring(levels, src.indexOf("private void recipeViewFloor(", levels));
        ordered(body, "if (!recipeViewFloorOn()) return levels;", "mRecipeViewFloor.black(mSessionGeneration.get(), levels.black, levels.white);");
        ordered(src, "private boolean recipeViewFloorOn() {", "return mRecipePlan != null && PreferenceKeys.scamDevSwitch(\"recipe_vf_floor\", true);");
        assertTrue(com.scamera.app.settings.DevKeys.keys().contains("recipe_vf_floor"));
        // the log line: the frame's stats by CFA site, the declared levels, the black used, the gains, the exposure
        ordered(src, "private void recipeViewFloor(Image img, CaptureResult result) {", "RaisedPedestal.measure(", "mRecipeViewFloor.observe(generation, stats);",
                "viewfinder levels: frame ", "block means by CFA site ", "declared black ", " -> black used ", "; WB gains ", " ms ISO ");
        // the AF line off the repeating request's total results
        ordered(src, "publishPreview(request, result);", "if (mRecipePlan != null && session == mCaptureSession) recipeAfTrace(request, result);");
        ordered(src, "private void recipeAfTrace(CaptureRequest request, TotalCaptureResult result) {", "CaptureResult.CONTROL_AF_MODE", "CaptureResult.CONTROL_AF_STATE",
                "CaptureResult.LENS_STATE", "CaptureResult.LENS_FOCUS_DISTANCE", "FullResRecipeTags.read(result, plan.recipe.tags)");
        // a still and the proof still keep a hand-set focus (the template alone would let the lens go)
        ordered(src, "private void sendRecipeProof() {", "VendorTagUtils.builderSessionApply(b, true, false, physicalID);", "recipeFocus(b);");
        ordered(src, "private void takeStill() {", "if (recipePlan != null) frameCount = Math.min(frameCount, recipePlan.recipe.frames);",
                "cameraEventsListener.burstSize(frameCount);", "if(recipePlan!=null) plan=plan.limitedTo(recipePlan.recipe.frames);",
                "if(recipePlan!=null) recipeFocus(stillRequest);");
        ordered(src, "private void recipeFocus(CaptureRequest.Builder still) {", "af != CaptureRequest.CONTROL_AF_MODE_OFF",
                "still.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);");
    }

    /**
     * P653: the seamless 50 MP capture of the notes in CaptureController - the use case from the plan's output alone, the session
     * parameters and the viewfinder's requests with the plan's (still-only: none) tags, a lost companion buffer on a still or the
     * proof still ignored and said once, the binned viewfinder's pairing tolerant to late results, its binned stream picked; the
     * tap / regions AF of the viewfinder not switched off for a recipe.
     */
    @Test public void theSeamlessCaptureOfTheNotesIsWired() throws Exception {
        final String src = controller(), code = FullResRecipeTest.code(src);
        // the use case comes from the plan's output (the capture RAW alone has one), never set elsewhere for a recipe
        ordered(src, "private void recipeOutput(OutputConfiguration config, Surface output) {", "final FullResRecipePlan.Output o = plan.output(stream);",
                "if (o.useCase != 0 && Build.VERSION.SDK_INT >= 33) {", "config.setStreamUseCase(o.useCase);");
        // the session parameters: the plan's session tags, and the request tags of a non-still (builderSessionApply(.., false, ..))
        ordered(src, "VendorTagUtils.builderSessionApply(scamSession, false, useMaximumResolutionKey, physicalID);",
                "FullResRecipeTags.apply(scamSession, mRecipePlan.sessionTags())");
        final String vendor = new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/api/VendorTagUtils.java")), StandardCharsets.UTF_8);
        assertTrue(vendor.contains("FullResRecipeSession.applyRequest(builder, burst);"));
        // the binned viewfinder stream and its reader; the pairing's limit per session
        ordered(src, "private void recipeSession(boolean sdkFullRes, StreamConfigurationMap map, CameraCharacteristics characteristics) {",
                "mRecipeCompanionLostLogged = false;", "FullResRecipePlan.viewfinderRaw(ownSizes, active.recipe.width, active.recipe.height);",
                "ownView == null ? 0 : ownView[0], ownView == null ? 0 : ownView[1], ownFormat, yuv));");
        assertTrue(src.contains("ImageReader.newInstance(view.width, view.height, view.format, FullResRecipePlan.RAW_PREVIEW_IMAGES);"));
        ordered(src, "mLiveRawSession = mRecipePlan != null ? liveRawWanted && mRecipePlan.rawViewfinder()",
                "mLiveMetadata.imageLimit(mRecipePlan != null && mLiveRawSession && mRecipePlan.viewfinderImagesKept() > 0",
                "? mRecipePlan.viewfinderImagesKept() : PreviewFrameMatcher.DEFAULT_IMAGES);");
        assertEquals("one place sets the pairing's limit", 1, code.split("mLiveMetadata\\.imageLimit\\(", -1).length - 1);
        ordered(src, "private void recipeAfTrace(CaptureRequest request, TotalCaptureResult result) {", "\"; viewfinder pairing: \" + mLiveMetadata.stats()");
        // a still: the companion's lost buffer returns before anything fails the still; only the capture RAW's is the still's
        ordered(src, "final Surface recipeCompanionSurface = recipePlan != null && recipeCompanion != null ? recipeCompanion.getSurface() : null;",
                "public void onCaptureBufferLost(@NonNull CameraCaptureSession session,",
                "if (recipeCompanionSurface != null && target == recipeCompanionSurface", "recipeCompanionLost(frameNumber, \"a still\");", "return;",
                "if (scamSequence != null && target == scamRawSurface)");
        // the repeating request: the binned RAW and, as the plan says, the YUV companion (its reader drops its frames)
        ordered(src, "private void newPreviewRequest() throws CameraAccessException {",
                "b.addTarget(mImageReaderRawView.getSurface());", "final ImageReader recipeViewCompanion = mRecipeCompanion;",
                "mRecipePlan.targets(false).contains(FullResRecipePlan.Stream.COMPANION)", "b.addTarget(recipeViewCompanion.getSurface());");
        ordered(src, "private void recipeReaders(FullResRecipePlan plan) {", "final FullResRecipePlan.Output companion = plan.output(FullResRecipePlan.Stream.COMPANION);",
                "final Image image = r.acquireLatestImage();", "if (image != null) image.close();");
        // the stills' first result says what the HAL reports for the tags
        ordered(src, "if (!recipeStillLogged.getAndSet(true)) Log.i(\"SCAM_CAPTURE\", \"FULLRES RECIPE camera \" + physicalID",
                "FullResRecipeTags.read(result, recipePlan.recipe.tags)");
        // the proof still: the same policy, a lost full-size buffer fails it at once
        ordered(src, "private void sendRecipeProof() {", "@Override public void onCaptureBufferLost(", "if (target != raw.getSurface()) {",
                "recipeCompanionLost(frameNumber, \"the proof still\");", "the proof still's full-size buffer was lost");
        ordered(src, "private void recipeCompanionLost(long frameNumber, String what) {", "if (mRecipeCompanionLostLogged) return;",
                "mRecipeCompanionLostLogged = true;", "said once a session (P653)");
        // the viewfinder's AF: nothing of a recipe turns the tap or the regions off
        for (String line : code.split("\n"))
            if (line.contains("mRecipePlan") || line.contains("recipePlan"))
                for (String af : new String[]{"CONTROL_AF_REGIONS", "CONTROL_AF_MODE_OFF", "CONTROL_AF_TRIGGER"})
                    assertFalse(line, line.contains(af));
        for (String control : new String[]{"control/TouchFocus.java", "ui/camera/CameraFragment.java"}) {
            final String c = new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/" + control)), StandardCharsets.UTF_8);
            assertFalse(control, FullResRecipeTest.code(c).contains("Recipe"));
        }
    }

    @Test public void everyFailureGoesBackToTheNormalSession() throws Exception {
        final String src = controller();
        // a refused configuration, the framework's own refusal, a camera error, a disconnect, no viewfinder frame, a shot without a still
        ordered(src, "if (mRecipe != null && (mLastPreviewResultMs == 0 || mShotInProgress || mZslCapturing)) recipeFailed(\"camera disconnected\");",
                "if (retryWithoutRecipe(null, \"camera error \" + error)) return;",
                "if (mRecipe != null && retryWithoutRecipe(mCaptureSession, \"no preview frame for \"",
                "if (retryWithoutRecipe(refused, \"session configuration failed\")) return; // P635",
                "if (retryWithoutRecipe(null, \"session: \" + e.getMessage())) return; // P635",
                "private boolean retryWithoutRecipe(@Nullable CameraCaptureSession session, String why) {",
                "&& retryWithoutRecipe(session, \"no still of the shot arrived");
        // the failure is remembered with the whole field set in the log, the session's plan dropped, the camera restarted
        final int failed = src.indexOf("private void recipeFailed(String why) {"), retry = src.indexOf("private boolean retryWithoutRecipe(");
        final String body = src.substring(failed, retry);
        for (String part : new String[]{"FullResRecipeStore.failActive(active, why);", "plan.describe(String.valueOf(physicalID))", "mRecipe = null;",
                "mRecipePlan = null;", "FullResRecipeSession.session(null);", "mFullResSession = false;"})
            assertTrue(part, body.contains(part));
        assertTrue(src.substring(retry, retry + 900).contains("mainHandler().post(this::restartCamera);"));
        // nothing of it outside a recipe session
        assertTrue(src.substring(retry, retry + 200).contains("if (mRecipe == null) return false;"));
    }
}
