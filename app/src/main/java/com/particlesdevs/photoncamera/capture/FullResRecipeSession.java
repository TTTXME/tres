package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;

import androidx.annotation.Nullable;

/**
 * P635: the recipe plan of the session being configured or running (CaptureController sets it; null: a normal session), for
 * the request builders: every request of such a session carries the profile's request tags, a still its still tags as well
 * (VendorTagUtils.builderSessionApply, as the module's picked sensor mode, P276).
 */
public final class FullResRecipeSession {
    private FullResRecipeSession() {}

    private static volatile FullResRecipePlan sPlan;

    public static void session(@Nullable FullResRecipePlan plan) {
        sPlan = plan;
    }

    @Nullable
    public static FullResRecipePlan session() {
        return sPlan;
    }

    /** The plan's tags of a request ({@code still}: a shot's frame) on its builder; "" outside a recipe session. */
    public static String applyRequest(@Nullable CaptureRequest.Builder builder, boolean still) {
        final FullResRecipePlan plan = sPlan;
        if (plan == null || builder == null) return "";
        return FullResRecipeTags.apply(builder, plan.requestTags(still));
    }
}
