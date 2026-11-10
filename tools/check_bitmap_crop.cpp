// P610: scamera_bitmap::cropRowsInPlace (the photo's crop packed in its own buffer, ScamPostDownscale.cropInPlace) gives the bytes
// of a copy of the region (Bitmap.createBitmap(source, x, y, w, h)) for every bpp of the photo's configs, padded strides, crops at
// every border and the 200 MP ISZ 4:3 shape; bad regions move nothing.
#include "../app/src/main/cpp/bitmap-crop.h"
#include <cstdio>
#include <random>
#include <vector>

int main() {
    std::mt19937 rng(20261110);
    int bad = 0, cases = 0;
    auto run = [&](int W, int H, int bpp, int pad, int x, int y, int w, int h) {
        const size_t stride = size_t(W) * bpp + pad;
        std::vector<uint8_t> img(stride * H), want(size_t(w) * bpp * h);
        for (auto& v : img) v = uint8_t(rng());
        for (int r = 0; r < h; ++r) std::memcpy(want.data() + size_t(r) * w * bpp, img.data() + size_t(y + r) * stride + size_t(x) * bpp, size_t(w) * bpp);
        const bool ok = scamera_bitmap::cropRowsInPlace(img.data(), img.size(), stride, W, H, x, y, w, h, bpp);
        ++cases;
        if (!ok || std::memcmp(img.data(), want.data(), want.size()) != 0) { std::printf("FAIL %dx%d bpp %d pad %d crop %d,%d %dx%d\n", W, H, bpp, pad, x, y, w, h); ++bad; }
    };
    for (int bpp : {4, 8}) for (int pad : {0, 12}) {
        run(80, 60, bpp, pad, 0, 0, 80, 60);
        run(80, 60, bpp, pad, 8, 0, 64, 60);
        run(80, 60, bpp, pad, 0, 6, 80, 48);
        run(80, 60, bpp, pad, 79, 59, 1, 1);
        run(81, 61, bpp, pad, 3, 5, 77, 55);
        for (int k = 0; k < 200; ++k) {
            const int W = 1 + int(rng() % 97), H = 1 + int(rng() % 53), w = 1 + int(rng() % W), h = 1 + int(rng() % H);
            run(W, H, bpp, pad, int(rng() % (W - w + 1)), int(rng() % (H - h + 1)), w, h);
        }
    }
    run(1632, 1228, 4, 0, 0, 2, 1632, 1224); // the 200 MP ISZ 4:3 shape (16320 x 12288 -> 16320 x 12240), at 1/10
    run(1228, 1632, 4, 0, 2, 0, 1224, 1632);
    std::vector<uint8_t> img(64 * 4 * 8, 7), copy = img;
    for (auto c : {std::vector<int>{-1, 0, 4, 4}, {0, -1, 4, 4}, {61, 0, 4, 4}, {0, 5, 4, 4}, {0, 0, 0, 4}, {0, 0, 4, 0}})
        if (scamera_bitmap::cropRowsInPlace(img.data(), img.size(), 256, 64, 8, c[0], c[1], c[2], c[3], 4) || img != copy) { std::printf("FAIL: a bad region was taken\n"); ++bad; }
    if (scamera_bitmap::cropRowsInPlace(img.data(), img.size() - 1, 256, 64, 8, 0, 0, 4, 4, 4)) { std::printf("FAIL: a short buffer was taken\n"); ++bad; }
    std::printf(bad ? "check_bitmap_crop: %d of %d FAILED\n" : "check_bitmap_crop: %d of %d cases OK\n", bad ? bad : cases, cases);
    return bad ? 1 : 0;
}
