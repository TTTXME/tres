// P683: the film reference's resources as libscameraFilm holds them (tools/embed_film_reference.py) decode to the repo's files,
// bit for bit: the Hanatos spectra stored packed (tools/film_pack.py: delta + shuffle + deflate), the rest plain; a damaged or
// truncated packed resource is refused, never decoded into other bytes.
// usage: check_film_embed <assets/film-reference dir> <the generator's shaders dir>
#include "film-reference-resources.h"

#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

static int failures = 0;

static void check(bool ok, const std::string& what) {
    if (!ok) {
        std::printf("FAIL %s\n", what.c_str());
        ++failures;
    }
}

static std::vector<uint8_t> file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

static bool same(const char* name, const std::vector<uint8_t>& want, uint32_t* codec) {
    const FilmResource r = embeddedFilmResource(name);
    if (!r.data || r.plainBytes != want.size()) return false;
    std::vector<uint8_t> got(r.plainBytes);
    if (!decodeFilmResource(r, got.data(), got.size())) return false;
    if (codec) *codec = r.codec;
    return got == want;
}

int main(int argc, char** argv) {
    if (argc != 3) {
        std::printf("usage: check_film_embed <film-reference dir> <shaders dir>\n");
        return 2;
    }
    const std::string floats = argv[1], shaders = argv[2];
    uint32_t codec = 9;
    const auto spectra = file(floats + "/SpektraHanatos2025Spectra.f32");
    check(spectra.size() == 11943936, "the Hanatos spectra file (11943936 bytes)");
    check(same("SpektraHanatos2025Spectra.f32", spectra, &codec), "the Hanatos spectra decode to the file's bytes");
    check(codec == 1, "the Hanatos spectra are stored packed");
    const FilmResource packed = embeddedFilmResource("SpektraHanatos2025Spectra.f32");
    check(packed.bytes < 3u * 1024u * 1024u, "the packed spectra under 3 MB (" + std::to_string(packed.bytes) + ")");
    check(same("SpektraOutputGamutCompression.f32", file(floats + "/SpektraOutputGamutCompression.f32"), &codec) && codec == 0,
          "the gamut compression table, plain");
    int spv = 0;
    if (DIR* d = opendir(shaders.c_str())) {
        while (dirent* e = readdir(d)) {
            const std::string n = e->d_name;
            if (n.size() < 5 || n.substr(n.size() - 4) != ".spv") continue;
            ++spv;
            check(same(("shaders/" + n).c_str(), file(shaders + "/" + n), &codec) && codec == 0, "the shader " + n + ", plain");
        }
        closedir(d);
    }
    check(spv == 11, "11 SPIR-V shaders (" + std::to_string(spv) + ")");
    // damage: a flipped byte in the deflate stream, a truncated stream, a wrong size - refused
    std::vector<uint8_t> copy(packed.data, packed.data + packed.bytes), out(packed.plainBytes);
    FilmResource bad = packed;
    bad.data = copy.data();
    copy[copy.size() / 2] ^= 0x40;
    check(!decodeFilmResource(bad, out.data(), out.size()), "a damaged packed resource is refused");
    copy[copy.size() / 2] ^= 0x40;
    check(decodeFilmResource(bad, out.data(), out.size()) && out == spectra, "the undamaged copy decodes again");
    bad.bytes = copy.size() - 1000;
    check(!decodeFilmResource(bad, out.data(), out.size()), "a truncated packed resource is refused");
    bad.bytes = copy.size();
    check(!decodeFilmResource(bad, out.data(), out.size() - 4), "a wrong output size is refused");
    check(embeddedFilmResource("missing.f32").data == nullptr, "an unknown name gives nothing");
    std::printf("     the film reference's %d resources decoded bit for bit (spectra %zu -> %zu bytes stored)\n", spv + 2,
                packed.plainBytes, packed.bytes);
    std::printf(failures ? "FAILED: %d\n" : "ALL OK\n", failures);
    return failures ? 1 : 0;
}
