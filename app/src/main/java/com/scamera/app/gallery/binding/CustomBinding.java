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

public class CustomBinding {
    @BindingAdapter("imageFromBitmap")
    public static void setImageBitmap(ImageView view, Bitmap bitmap) {
//        setBitmapWithAnimation(view, bitmap);
        view.setImageBitmap(bitmap);
    }

}
