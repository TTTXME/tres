#include "film-reference-resources.h"
#include "embedded_film_index.h"

extern "C" const uint8_t scamera_film_blob[];

FilmResource embeddedFilmResource(const char* name) noexcept {
    if (!name) return {};
    uint64_t id = 0xcbf29ce484222325ull;
    for (const auto* p = reinterpret_cast<const uint8_t*>(name); *p; ++p)
        id = (id ^ *p) * 0x100000001b3ull;
    for (const auto& item : kFilmResourceIndex)
        if (item.id == id && item.offset <= kFilmResourceBytes && item.bytes <= kFilmResourceBytes - item.offset)
            return {scamera_film_blob + item.offset, item.bytes};
    return {};
}
