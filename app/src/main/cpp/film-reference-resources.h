#pragma once
#include <cstddef>
#include <cstdint>

struct FilmResource {
    const uint8_t* data = nullptr;
    size_t bytes = 0;
};

FilmResource embeddedFilmResource(const char* name) noexcept;
