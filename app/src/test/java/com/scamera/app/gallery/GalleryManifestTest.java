package com.scamera.app.gallery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;

import com.scamera.app.gallery.ui.GalleryActivity;
import com.scamera.app.ui.SplashActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * The app claims only intents it can answer. P621 (owner, 2026-11-11: «сделай чтобы нашу галерею можно было выбирать в системе
 * как по умолчанию»): with the library's pick mode (a tap returns the item) and the gallery's video player (P499), PICK /
 * GET_CONTENT of photos and videos and video VIEW / REVIEW are claimed now, as are MediaStore's REVIEW / REVIEW_SECURE and the
 * launcher's «gallery» category; still no ACTION_PICK_IMAGES (the system photo picker's contract), no GET_CONTENT of other files,
 * no IMAGE_CAPTURE / STILL_IMAGE_CAMERA_SECURE (no capture-for-result or lock-screen camera).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryManifestTest {
    private static boolean offers(Intent intent, Class<?> activity) {
        PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        List<ResolveInfo> l = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo r : l) if (r.activityInfo != null && activity.getName().equals(r.activityInfo.name)) return true;
        return false;
    }

    private static boolean offersAny(Intent intent) {
        String own = RuntimeEnvironment.getApplication().getPackageName();
        for (ResolveInfo r : RuntimeEnvironment.getApplication().getPackageManager().queryIntentActivities(intent, 0))
            if (r.activityInfo != null && own.equals(r.activityInfo.packageName)) return true;
        return false;
    }

    @Test
    public void photosAreStillViewed() {
        Uri photo = Uri.parse("content://media/external/images/media/1");
        assertTrue(offers(new Intent(Intent.ACTION_VIEW).setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
        assertTrue(offers(new Intent("com.android.camera.action.REVIEW").setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_EDIT).setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
    }

    @Test
    public void picksReturnAnItem() {
        assertTrue(offers(new Intent(Intent.ACTION_PICK).setType("image/*"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_PICK).setType("video/*"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_PICK).setType("vnd.android.cursor.dir/image"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), GalleryActivity.class));
        assertFalse("other files", offersAny(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")));
        assertFalse("the system photo picker's", offersAny(new Intent("android.provider.action.PICK_IMAGES").setType("image/*")));
    }

    @Test
    public void videosPlay() {
        Uri video = Uri.parse("content://media/external/video/media/1");
        assertTrue(offers(new Intent(Intent.ACTION_VIEW).setDataAndType(video, "video/mp4"), GalleryActivity.class));
        assertTrue(offers(new Intent("com.android.camera.action.REVIEW").setDataAndType(video, "video/mp4"), GalleryActivity.class));
    }

    @Test
    public void theDefaultGalleryIntents() {
        Uri photo = Uri.parse("content://media/external/images/media/1");
        Uri foreign = Uri.parse("content://com.example.fileprovider/shared/IMG_1.heic");
        assertTrue(offers(new Intent("android.provider.action.REVIEW").setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
        assertTrue(offers(new Intent("android.provider.action.REVIEW_SECURE").setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_VIEW).setDataAndType(foreign, "image/heic"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_VIEW).setDataAndType(foreign, "image/x-adobe-dng"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_VIEW).setDataAndType(foreign, "image/avif"), GalleryActivity.class));
        assertTrue("the launcher's gallery category", offersAny(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)));
    }

    @Test
    public void noCaptureForResultWithoutAResult() {
        assertTrue("the camera itself", offers(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), SplashActivity.class));
        assertFalse(offersAny(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)));
        assertFalse(offersAny(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)));
    }
}
