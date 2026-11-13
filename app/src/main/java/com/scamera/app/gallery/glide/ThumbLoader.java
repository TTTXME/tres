package com.scamera.app.gallery.glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

/**
 * P620: hands a {@link GalleryThumb} to {@link ThumbDecoder} as it is: the decoder opens the file itself (a mapped DNG, the EXIF
 * thumbnail's bytes, a sampled decode from the descriptor), so nothing is read or copied before it knows what it needs.
 */
public final class ThumbLoader implements ModelLoader<GalleryThumb, GalleryThumb> {
    @Nullable
    @Override
    public LoadData<GalleryThumb> buildLoadData(@NonNull GalleryThumb model, int width, int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey(model), new Fetcher(model));
    }

    @Override
    public boolean handles(@NonNull GalleryThumb model) {
        return true;
    }

    private static final class Fetcher implements DataFetcher<GalleryThumb> {
        private final GalleryThumb model;

        Fetcher(GalleryThumb model) {
            this.model = model;
        }

        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super GalleryThumb> callback) {
            callback.onDataReady(model);
        }

        @Override public void cleanup() {}

        @Override public void cancel() {}

        @NonNull
        @Override
        public Class<GalleryThumb> getDataClass() {
            return GalleryThumb.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }

    public static final class Factory implements ModelLoaderFactory<GalleryThumb, GalleryThumb> {
        @NonNull
        @Override
        public ModelLoader<GalleryThumb, GalleryThumb> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new ThumbLoader();
        }

        @Override public void teardown() {}
    }
}
