package com.scamera.app.gallery.binding;

import android.graphics.Bitmap;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

import androidx.databinding.BindingAdapter;

import com.scamera.app.gallery.glide.GalleryKeys;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.views.Histogram;

/** The gallery layouts' binding adapters: app:imageFromBitmap shows a bitmap as it is (no animation). */
public final class CustomBinding {
    private CustomBinding() {
    }

    @BindingAdapter("imageFromBitmap")
    public static void bindBitmap(ImageView target, Bitmap shown) {
        target.setImageBitmap(shown);
    }
}
