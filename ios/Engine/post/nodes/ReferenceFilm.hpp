// The reference film route one to one: ReferenceFilm.java (the scene light through why/low into RGBA16F, packed to its half bits
// by film/reference_pack, the spectral film engine on it with the shot's own exposure (whyAeEv), the result as the film's
// picture), ReferenceFilmMix.java (film/reference_mix: the picture mixed by the strength over the tone, the chosen LUT on its print)
// and FilmReferenceParameters.java (the engine's 652-byte parameter packet) with FilmReferenceEngine's availability.
// The engine is spektrafilm's Vulkan renderer (app/src/main/cpp/third_party/spektrafilm, libscameraFilm on Android). Its renderer
// is a hook here (FilmReferenceEngine::renderer): this build sets none (iOS has no Vulkan driver), so available() is false and the
// pipeline takes the old film route - as Android does without libscameraFilm (ReferenceFilm.supported false).
#pragma once

#include "WhyStats.hpp"

namespace scam_post {

/// FilmReferenceParameters: the scalar film settings shared with the spectral renderer (652 bytes, little endian)
class FilmReferenceParameters {
public:
    static constexpr int BYTES = 652;
    std::array<uint8_t, BYTES> data{};

    FilmReferenceParameters() {
        static const std::vector<std::pair<int, float>> reals = {{36, 203.f}, {40, 1000.f}, {44, 0.f}, {72, 410.f}, {80, 675.f}, {84, 0.f},
            {96, 0.f}, {104, 0.f}, {108, 0.f}, {112, 0.f}, {116, 1.f}, {120, 0.f}, {124, 1.f}, {128, 1.f}, {132, 0.f}, {136, 0.f}, {140, 0.f},
            {144, 0.f}, {148, 0.f}, {152, 1.f}, {156, 0.f}, {160, 0.f}, {164, 0.f}, {168, 0.f}, {172, 0.f}, {176, 0.f}, {180, 0.f}, {184, 0.f},
            {196, 0.f}, {200, 20.f}, {204, 200.f}, {208, 0.06f}, {212, 1.f}, {216, 1.f}, {220, 0.336f}, {224, 0.319f}, {228, 0.273f},
            {232, 0.353f}, {236, 0.302f}, {240, 0.154f}, {244, 0.353f}, {248, 0.168f}, {252, 0.226f}, {268, 1.f}, {272, 1.f}, {284, 0.1f},
            {288, 1.2f}, {292, 1.f}, {296, 2.5f}, {300, 6.f}, {304, 1.f}, {308, 0.4f}, {312, 0.04f}, {316, 0.05f}, {320, 0.06f}, {324, 0.99f},
            {328, 0.97f}, {332, 0.98f}, {336, 11.8f}, {340, 1.f}, {344, 0.2f}, {348, 30.f}, {360, 1.f}, {364, 1.f}, {368, 1.f}, {372, 1.f},
            {380, 0.25f}, {384, 0.f}, {388, 1.f}, {392, 1.f}, {396, 0.999f}, {400, 0.0001f}, {408, 1.2f}, {412, 1.f}, {416, 2.5f}, {420, 6.f},
            {424, 1.f}, {428, 0.4f}, {440, 1.f}, {444, 1.f}, {448, 1.f}, {452, 1.f}, {456, 0.05f}, {460, 0.015f}, {464, 0.f}, {468, 65.f},
            {472, 65.f}, {476, 65.f}, {480, 0.f}, {484, 0.3f}, {488, 4.f}, {500, 0.5f}, {504, 1.f}, {508, 0.f}, {512, 1.f}, {516, 1.f},
            {520, 1.f}, {524, 1.f}, {528, 1.f}, {532, 1.f}, {544, 0.5f}, {548, 1.f}, {552, 0.f}, {556, 1.f}, {560, 1.f}, {564, 1.f},
            {568, 1.f}, {572, 1.f}, {576, 1.f}, {592, 0.98f}, {596, 0.01f}, {600, 0.03f}, {604, 0.7f}, {608, 0.5f}, {612, 60.f},
            {616, 5.f}, {620, 0.7f}};
        static const std::vector<std::pair<int, int>> ints = {{0, 0}, {4, 0}, {8, 0}, {12, 2}, {16, 15}, {20, 17}, {24, 0}, {28, 0}, {32, 0},
            {48, 1}, {52, 0}, {56, 2}, {60, 3}, {64, 0}, {68, 0}, {76, 0}, {88, 0}, {92, 0}, {100, 0}, {188, 0}, {192, 1}, {256, 0}, {260, 1},
            {264, 4}, {276, 1}, {280, 1}, {352, 1}, {356, 0}, {376, 128}, {404, 32}, {432, 1}, {436, 0}, {492, 0}, {496, 1}, {536, 0},
            {540, 1}, {580, 0}, {584, 0}, {588, 0}, {624, 0}, {628, 1}, {632, 1}, {636, 1}, {640, 1}, {644, 0}};
        for (const auto& [o, v] : reals) put(o, &v);
        for (const auto& [o, v] : ints) put(o, &v);
    }
    FilmReferenceParameters& integer(int offset, int value) { check(offset); put(offset, &value); return *this; }
    FilmReferenceParameters& real(int offset, float value) {
        check(offset);
        if (!std::isfinite(value)) throw std::invalid_argument("Nonfinite film parameter");
        put(offset, &value);
        return *this;
    }
    float getFloat(int offset) const { float v; std::memcpy(&v, data.data() + offset, 4); return v; }

    static const std::vector<std::string>& FILMS() {
        static const std::vector<std::string> f = {"kodak_ektar_100", "kodak_portra_160", "kodak_portra_400", "kodak_portra_800",
            "kodak_portra_800_push1", "kodak_portra_800_push2", "kodak_gold_200", "kodak_ultramax_400", "kodak_vision3_50d", "kodak_vision3_250d",
            "kodak_verita_200d", "kodak_vision3_200t", "kodak_vision3_500t", "fujifilm_pro_400h", "fujifilm_c200", "fujifilm_xtra_400",
            "kodak_ektachrome_100", "kodak_kodachrome_64", "fujifilm_velvia_100", "fujifilm_provia_100f"};
        return f;
    }
    static const std::vector<std::string>& PAPERS() {
        static const std::vector<std::string> p = {"kodak_endura_premier", "kodak_ultra_endura", "kodak_ektacolor_edge", "kodak_supra_endura",
            "kodak_portra_endura", "fujifilm_crystal_archive_typeii", "kodak_2383", "kodak_2393"};
        return p;
    }
    static int index(const std::vector<std::string>& v, const std::string& key) {
        for (size_t i = 0; i < v.size(); i++) if (v[i] == key) return int(i);
        return -1;
    }
    static bool measured(const std::string& stockId) { return index(FILMS(), stockId) >= 0; }
    static bool supports(const FilmSettings* s) {
        return s && s->stock && s->print && index(FILMS(), s->stock->id) >= 0 && index(PAPERS(), s->print->id) >= 0;
    }
    static FilmReferenceParameters from(const FilmSettings& s, bool p3, int seed) {
        static const float SLIDE_DIR[4][9] = {{.12f, .08f, .06f, .12f, .06f, .08f, .06f, .06f, .06f},
                                             {.12f, .08f, .06f, .12f, .06f, .08f, .06f, .06f, .06f},
                                             {.108f, .072f, .054f, .108f, .054f, .072f, .054f, .054f, .054f},
                                             {.156f, .104f, .078f, .156f, .078f, .104f, .078f, .078f, .078f}};
        if (!supports(&s)) throw std::invalid_argument("Unsupported measured film profile");
        FilmReferenceParameters p;
        p.integer(0, 1).integer(4, s.stock->reversal() ? 1 : 0).integer(20, p3 ? 18 : 17).integer(56, index(FILMS(), s.stock->id))
            .integer(60, index(PAPERS(), s.print->id)).real(84, s.exposure).real(96, s.printEv).real(124, float(std::pow(2, double(s.tone))))
            .real(144, s.printM).real(148, s.printY).real(196, s.couplers).integer(352, seed);
        const int film = index(FILMS(), s.stock->id);
        if (film >= 16) for (int i = 0; i < 9; i++) p.real(220 + 4 * i, SLIDE_DIR[film - 16][i]);
        const bool physical = s.format->longMm > 0;
        const std::string& id = s.format->id;
        const int format = id == "8mm" ? 0 : id == "super8" ? 1 : id == "16mm" ? 2 : id == "super16" ? 3 : id == "super35" ? 5 : id == "65mm" ? 6
                         : id == "imax70" ? 7 : 4;
        p.integer(264, format).real(648, id == "645" || id == "67" ? s.format->longMm : 0.f);
        p.integer(256, physical && s.grain > 0 ? 1 : 0).real(268, s.grain);
        p.integer(436, physical && s.halation > 0 ? 1 : 0).real(440, s.halation).real(448, s.halation);
        p.integer(580, physical && s.softness > 0 ? 1 : 0).real(612, s.softness > 0 ? 60.f / s.softness : 0.f).real(620, 0.f);
        int diffusion = -1;
        for (size_t i = 0; i < FilmSim::DIFFUSIONS().size(); i++) if (FilmSim::DIFFUSIONS()[i] == s.diffusion) { diffusion = int(i) - 1; break; }
        if (diffusion == -1 && std::find(FilmSim::DIFFUSIONS().begin(), FilmSim::DIFFUSIONS().end(), s.diffusion) == FilmSim::DIFFUSIONS().end())
            diffusion = -2;
        p.integer(492, physical && diffusion >= 0 && s.diffusionGrade > 0 ? 1 : 0).integer(496, std::max(0, diffusion)).real(500, s.diffusionGrade);
        const FilmAdvanced& a = s.advanced;
        p.integer(12, a.rgbToRawMethod()).integer(100, a.pushMode).real(104, a.get(FilmAdvanced::PUSH)).real(112, a.get(FilmAdvanced::BLEACH))
            .real(116, a.get(FilmAdvanced::LEUCO)).real(124, float(std::pow(2, double(s.tone))) * a.get(FilmAdvanced::DEV_GAMMA))
            .real(200, a.get(FilmAdvanced::DIR_SPREAD)).real(204, a.get(FilmAdvanced::DIR_TAIL)).real(208, a.get(FilmAdvanced::DIR_TAIL_WEIGHT))
            .real(212, a.get(FilmAdvanced::DIR_SAME)).real(216, a.get(FilmAdvanced::DIR_INTER)).real(440, s.halation * a.get(FilmAdvanced::SCATTER))
            .real(444, a.get(FilmAdvanced::SCATTER_SIZE)).real(448, s.halation * a.get(FilmAdvanced::HALO)).real(452, a.get(FilmAdvanced::HALO_SIZE))
            .real(480, a.get(FilmAdvanced::BOOST)).real(484, a.get(FilmAdvanced::BOOST_RANGE)).real(488, a.get(FilmAdvanced::PROTECT));
        for (int i = 0; i < 9; i++) if (std::isfinite(a.gamma[size_t(i)])) p.real(220 + 4 * i, a.gamma[size_t(i)]);
        if (!physical) p.real(200, 0.f).real(204, 0.f);
        return p;
    }

private:
    void put(int offset, const void* v) { std::memcpy(data.data() + offset, v, 4); }
    static void check(int offset) {
        if (offset < 0 || offset > BYTES - 4 || (offset & 3) != 0) throw std::invalid_argument("Film parameter offset");
    }
};

/// FilmReferenceEngine: the spectral renderer behind a hook (none set: unavailable)
struct FilmReferenceEngine {
    /// RGBA16F rows in -> RGBA16F rows out with the packet; returns its report, throws on failure
    using Render = std::function<std::string(const uint16_t* input, uint16_t* output, const FilmReferenceParameters& params, int width,
                                             int height, int64_t budget)>;
    static Render& renderer() { static Render r; return r; }
    static bool available() { return static_cast<bool>(renderer()); }
};

/// ReferenceFilm.supported
inline bool referenceFilmSupported(const FilmSettings* s) { return FilmReferenceParameters::supports(s) && FilmReferenceEngine::available(); }

class ReferenceFilm : public Node {
public:
    ReferenceFilm() : Node("", "ReferenceFilm") {}
    void Compile() override {}

    /// ReferenceFilm.readHalf: the half bits of an RGBA16F texture through film/reference_pack (rows in storage-buffer-sized strips)
    static void readHalf(GLProg& prog, GLTexture& source, std::vector<uint16_t>& destination) {
        GLint64 storageLimit = 0;
        glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &storageLimit);
        if (glGetError() != GL_NO_ERROR) throw std::runtime_error("Film storage buffer limit unavailable");
        const int width = source.mSize.x, height = source.mSize.y;
        const int64_t rowBytes = int64_t(width) * 8, limit = std::min<int64_t>(16LL << 20, storageLimit);
        if (width <= 0 || height <= 0 || rowBytes > limit) throw std::invalid_argument("Film transfer exceeds the storage buffer limit");
        const int rows = int(std::min<int64_t>(std::min(height, 256), limit / rowBytes));
        destination.assign(size_t(width) * size_t(height) * 4, 0);
        GLint generic = 0, binding = 0;
        glGetIntegerv(GL_SHADER_STORAGE_BUFFER_BINDING, &generic);
        glGetIntegeri_v(GL_SHADER_STORAGE_BUFFER_BINDING, 0, &binding);
        GLBuffer packed(width * rows * 2, GLFormat(DataType::UNSIGNED_32));
        glBindImageTexture(0, source.mTextureID, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16UI);
        try {
            for (int y = 0; y < height; y += rows) {
                const int count = std::min(rows, height - y);
                const size_t bytes = size_t(width) * size_t(count) * 8;
                prog.setLayout(8, 8, 1);
                prog.useAssetProgram("film/reference_pack", true);
                prog.setBufferCompute("packedHalf", packed);
                prog.setVar("originY", y);
                prog.setVar("rowsU", count);
                glDispatchCompute(GLuint((width + 7) / 8), GLuint((count + 7) / 8), 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
                packed.Bind();
                const void* mapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(bytes), GL_MAP_READ_BIT);
                if (!mapped) throw std::runtime_error("Film half-image readback failed");
                std::memcpy(destination.data() + size_t(y) * size_t(width) * 4, mapped, bytes);
                if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) throw std::runtime_error("Film half-image mapping invalidated");
                if (glGetError() != GL_NO_ERROR) throw std::runtime_error("Film half-image transfer error");
            }
        } catch (...) {
            packed.close();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, GLuint(binding));
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, GLuint(generic));
            throw;
        }
        packed.close();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, GLuint(binding));
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, GLuint(generic));
        prog.closed = true;
    }

    /// ReferenceFilm.whyAeEv: inScale x ae of WhyStats' metering of the same scene, in EV (NaN without the statistics)
    float whyAeEv(PostPipeline& p) {
        GLProg& prog = *glProg;
        const float inScale = float(std::pow(2.0, double(WhyTone::value("input_ev", 0.f))));
        Texture gainMap = p.GainMap, fallback;
        if (!gainMap) {
            const float one[4] = {1.f, 1.f, 1.f, 1.f};
            fallback = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        const int factor = WhyTone::lowFactor(p.mParameters);
        Texture low = makeTexture(WhyTone::reduced(WorkingTexture->mSize, factor), GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.useAssetProgram("why/low", false);
        prog.setTexture("InputBuffer", WorkingTexture);
        WhyTone::setColour(prog, p, *gainMap, inScale);
        prog.setVar("factorU", factor);
        prog.drawBlocks(low);
        prog.closed = true;
        std::optional<std::string> clampLine;
        const WhyAe::AeSettings settings = WhyStats::aeSettings(p, WorkingTexture->mSize.x, WorkingTexture->mSize.y, inScale, clampLine);
        const std::optional<WhyAe::Stats> stats = WhyAeGpu::chained(prog, *low, settings.metering);
        prog.closed = true;
        low->close();
        if (fallback) fallback->close();
        if (!stats) return NAN;
        const float gain = inScale * WhyAe::finish(*stats, settings, p.mParameters.iso, 10000).ae;
        return gain > 0.f && std::isfinite(gain) ? float(std::log(double(gain)) / std::log(2.0)) : NAN;
    }

    void Run() override {
        PostPipeline& p = *basePipeline;
        GLProg& prog = *glProg;
        WorkingTexture = previousNode->WorkingTexture;
        prog.closed = true;
        Texture scene, result;
        try {
            const int64_t bytes = int64_t(WorkingTexture->mSize.x) * WorkingTexture->mSize.y * 8;
            if (bytes > 2147483647LL) throw std::runtime_error("Insufficient film memory headroom");
            int64_t budget = 3072LL << 20;
            const float forced = Settings::current().hybridValue("film_reference_budget_mb", 0.f);
            if (std::isfinite(forced) && forced >= 32.f) budget = std::min(budget, int64_t(double(std::min(forced, 3072.f)) * double(1LL << 20)));
            const bool shotAe = Settings::current().scamDevSwitch("film_why_ae", true);
            const float aeEv = shotAe ? whyAeEv(p) : NAN;
            if (p.mParameters.portraitEnabled && p.mParameters.portraitFaceDetected && p.mParameters.portraitSkinWarmth)
                throw std::runtime_error("post port: the faces' skin measure (Portrait) is not ported");
            scene = makeTexture(WorkingTexture->mSize, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("why/low", false);
            prog.setTexture("InputBuffer", WorkingTexture);
            Texture gainMap = p.GainMap, fallback;
            if (!gainMap) {
                const float one[4] = {1.f, 1.f, 1.f, 1.f};
                fallback = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR, GL_CLAMP_TO_EDGE);
                gainMap = fallback;
            }
            WhyTone::setColour(prog, p, *gainMap, 1.f);
            prog.setVar("factorU", 1);
            prog.drawBlocks(scene);
            if (fallback) fallback->close();
            std::vector<uint16_t> input, output(size_t(bytes / 2));
            readHalf(prog, *scene, input);
            scene->close();
            scene = nullptr;
            const FilmLutP user = p.filmUserStrength > 0 && !p.filmUserLut.empty() ? FilmLutFiles::chosen(p.filmUserLut) : nullptr;
            const bool nativeP3 = p.p3Output && !user;
            FilmReferenceParameters settings = FilmReferenceParameters::from(*p.film, nativeP3, 1);
            if (std::isfinite(aeEv)) settings.real(84, p.film->exposure + aeEv);
            else if (shotAe) settings.integer(88, 1);
            const std::string report = FilmReferenceEngine::renderer()(input.data(), output.data(), settings, WorkingTexture->mSize.x,
                                                                       WorkingTexture->mSize.y, budget);
            result = makeTexture(WorkingTexture->mSize, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            GLint previous = 0;
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
            glBindTexture(GL_TEXTURE_2D, result->mTextureID);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, result->mSize.x, result->mSize.y, GL_RGBA, GL_HALF_FLOAT, output.data());
            glBindTexture(GL_TEXTURE_2D, GLuint(previous));
            if (glGetError() != GL_NO_ERROR) throw std::runtime_error("Film image upload failed");
            p.referenceFilmSettings = p.film;
            p.referenceFilmTexture = result;
            result = nullptr;
            p.referenceFilmUserLut = user;
            p.referenceFilmNativeP3 = nativeP3;
            p.referenceFilmApplied = true;
            if (p.film->strength >= 1) WorkingTexture = p.referenceFilmTexture;
            else p.film = nullptr;
            logI("SCAM_PIPELINE", "spectral film " + report + " full=" + (p.referenceFilmSettings->strength >= 1 ? "true" : "false") + " stock="
                 + p.referenceFilmSettings->stock->id + " paper=" + p.referenceFilmSettings->print->id);
        } catch (const std::exception& error) {
            logI("SCAM_PIPELINE", std::string("spectral film unavailable; retained legacy film: ") + error.what());
        }
        if (scene) scene->close();
        if (result) result->close();
        prog.closed = true;
    }
};

class ReferenceFilmMix : public Node {
public:
    ReferenceFilmMix() : Node("", "ReferenceFilmMix") {}
    void Compile() override {}
    void Run() override {
        PostPipeline& p = *basePipeline;
        GLProg& prog = *glProg;
        WorkingTexture = previousNode->WorkingTexture;
        prog.closed = true;
        if (!p.referenceFilmApplied) return;
        GLuint lutTexture = 0;
        auto finish = [&] {
            p.film = p.referenceFilmSettings;
            if (lutTexture) glDeleteTextures(1, &lutTexture);
            if (p.referenceFilmTexture && p.referenceFilmTexture != WorkingTexture) p.referenceFilmTexture->close();
            p.referenceFilmTexture = nullptr;
        };
        try {
            const FilmLutP lut = p.referenceFilmUserLut;
            if (!lut && p.referenceFilmSettings->strength >= 1) {
                WorkingTexture = p.referenceFilmTexture;
                finish();
                return;
            }
            WorkingTexture = makeTexture(previousNode->WorkingTexture->mSize, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            if (lut) prog.setDefine("FILM_LUT", 1);
            if (p.p3Output && !p.referenceFilmNativeP3) prog.setDefine("FILM_TO_P3", 1);
            prog.useAssetProgram("film/reference_mix", false);
            prog.setTexture("InputBuffer", previousNode->WorkingTexture);
            prog.setTexture("FilmBuffer", p.referenceFilmTexture);
            prog.setVar("strengthU", p.referenceFilmSettings->strength);
            if (lut) {
                lutTexture = lut->upload();
                prog.setTexture3D("FilmLut", lutTexture);
                prog.setVar("lutU", p.filmUserStrength, float(lut->size));
                prog.setVar("lutMinU", lut->domainMin[0], lut->domainMin[1], lut->domainMin[2]);
                prog.setVar("lutScaleU", 1.f / (lut->domainMax[0] - lut->domainMin[0]), 1.f / (lut->domainMax[1] - lut->domainMin[1]),
                            1.f / (lut->domainMax[2] - lut->domainMin[2]));
            }
            prog.drawBlocks(WorkingTexture);
            prog.closed = true;
        } catch (...) {
            finish();
            throw;
        }
        finish();
    }
};

} // namespace scam_post
