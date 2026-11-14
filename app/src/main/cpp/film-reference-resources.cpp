#include "film-reference-resources.h"
#include "embedded_film_index.h"

#include <cstring>
#include <memory>
#include <new>
#include <zlib.h>

extern "C" const uint8_t scamera_film_blob[];

FilmResource embeddedFilmResource(const char* name) noexcept {
    if (!name) return {};
    uint64_t id = 0xcbf29ce484222325ull;
    for (const auto* p = reinterpret_cast<const uint8_t*>(name); *p; ++p)
        id = (id ^ *p) * 0x100000001b3ull;
    for (const auto& item : kFilmResourceIndex)
        if (item.id == id && item.offset <= kFilmResourceBytes && item.bytes <= kFilmResourceBytes - item.offset)
            return {scamera_film_blob + item.offset, item.bytes, item.codec, item.plain};
    return {};
}

bool decodeFilmResource(const FilmResource& resource, uint8_t* out, size_t outBytes) noexcept {
    if (!resource.data || !out || outBytes != resource.plainBytes) return false;
    if (resource.codec == 0) {
        if (resource.bytes != outBytes) return false;
        std::memcpy(out, resource.data, outBytes);
        return true;
    }
    if (resource.codec != 1 || outBytes % 4 || resource.bytes > 0xffffffffu) return false;
    const size_t words = outBytes / 4;
    // one significance plane at a time (a quarter of the output, not a second full copy), scattered into its byte of each word
    std::unique_ptr<uint8_t[]> plane(new (std::nothrow) uint8_t[words ? words : 1]);
    if (!plane) return false;
    z_stream z{};
    if (inflateInit(&z) != Z_OK) return false;
    z.next_in = const_cast<Bytef*>(resource.data);
    z.avail_in = static_cast<uInt>(resource.bytes);
    bool ok = true;
    for (size_t k = 0; k < 4 && ok; ++k) {
        size_t got = 0;
        while (got < words) {
            const size_t want = words - got;
            z.next_out = plane.get() + got;
            z.avail_out = static_cast<uInt>(want > 0x40000000u ? 0x40000000u : want);
            const uInt before = z.avail_out;
            const int r = inflate(&z, Z_NO_FLUSH);
            got += before - z.avail_out;
            if (r == Z_STREAM_END) break;
            if (r != Z_OK || before == z.avail_out) { ok = false; break; }
        }
        if (got != words) ok = false;
        for (size_t i = 0; ok && i < words; ++i) out[4 * i + k] = plane[i];
    }
    if (ok) {
        // the stream ends here (its Adler-32 checked by inflate), nothing after it
        uint8_t extra = 0;
        z.next_out = &extra;
        z.avail_out = 1;
        ok = inflate(&z, Z_FINISH) == Z_STREAM_END && z.avail_out == 1 && z.avail_in == 0;
    }
    inflateEnd(&z);
    if (!ok) return false;
    uint32_t sum = 0;
    for (size_t i = 0; i < words; ++i) {
        uint32_t d;
        std::memcpy(&d, out + 4 * i, 4);  // little-endian words (Android's ABIs and the hosts)
        sum += d;
        std::memcpy(out + 4 * i, &sum, 4);
    }
    return true;
}
