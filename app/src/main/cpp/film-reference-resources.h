#pragma once
#include <cstddef>
#include <cstdint>

struct FilmResource {
    const uint8_t* data = nullptr;
    /** The bytes stored in the library. */
    size_t bytes = 0;
    /** P683: 0 the plain bytes; 1 tools/film_pack.py's delta + shuffle + deflate of 4-byte words (decodeFilmResource). */
    uint32_t codec = 0;
    /** P683: the bytes once decoded (== bytes for codec 0). */
    size_t plainBytes = 0;
};

FilmResource embeddedFilmResource(const char* name) noexcept;

/**
 * P683: the resource's plain bytes into {@code out} ({@code outBytes} == its plainBytes): a copy for codec 0, else inflated,
 * the bytes back from their significance planes and the words' differences summed (wrapping) - the bytes tools/film_pack.py was
 * given, bit for bit. False when the stored data is damaged or the sizes disagree.
 */
bool decodeFilmResource(const FilmResource& resource, uint8_t* out, size_t outBytes) noexcept;
