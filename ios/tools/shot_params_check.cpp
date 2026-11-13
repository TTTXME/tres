// The shot's parameters as the iOS app builds them against the Android app's: the colour chain (Parameters.ReCalcColor from
// the DNG colour tags of the shot) and the worker tuning of its settings (PreferenceKeys.hybridTuningText), bit for bit.
//   shot_params_check <meta.json of a lab burst> <tuning.txt of the Android host run> [--dng <the shot's tags as a DNG>]
// --dng: the app's DNG reader and shotParameters (tools/make_test_dng.py writes the Parameters' tags as a DNG) against them.
#include "../Engine/post/Json.hpp"
#include "../Engine/post/HybridTuning.hpp"
#include "../Engine/post/DngShot.hpp"

#include <fstream>
#include <iterator>

using namespace scam_post;

static std::string readText(const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error(std::string("cannot read ") + path);
    return std::string((std::istreambuf_iterator<char>(f)), {});
}

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: shot_params_check <meta.json> <tuning.txt>\n"); return 2; }
    try {
        const Json meta = Json::parse(readText(argv[1]));
        Settings settings = Settings::fromJson(meta);
        Settings::Scope scope(settings);
        int bad = 0;
        const std::string tuning = HybridTuning::text(settings), ref = readText(argv[2]);
        if (tuning != ref) {
            std::printf("tuning differs:\n--- ours\n%s--- Android\n%s", tuning.c_str(), ref.c_str());
            bad++;
        } else std::printf("tuning: %zu lines identical\n", size_t(std::count(tuning.begin(), tuning.end(), '\n')));

        const Parameters a = Parameters::fromJson(meta["params"]);
        if (a.colourSource != "DNG_FM" && a.colourSource != "DNG_CM_ONLY") {
            std::printf("colour: the shot's route %s is not a DNG route (nothing to compare)\n", a.colourSource.c_str());
            return bad ? 1 : 0;
        }
        ShotColourTags t;
        t.colorMatrix1 = *a.ColorMatrix1; t.colorMatrix2 = *a.ColorMatrix2;
        t.forwardMatrix1 = *a.ForwardTransform1; t.forwardMatrix2 = *a.ForwardTransform2;
        t.calibration1 = *a.calibrationTransform1; t.calibration2 = *a.calibrationTransform2;
        t.illuminant1 = a.calibrationIlluminant1; t.illuminant2 = a.calibrationIlluminant2;
        t.neutral = *a.whitePoint;
        Parameters p;
        ShotColour::reCalcColor(p, t, [](const std::string& l) { std::printf("  %s\n", l.c_str()); });
        auto cmp = [&](const char* name, const std::vector<float>& x, const std::vector<float>& y) {
            for (size_t i = 0; i < x.size() && i < y.size(); i++)
                if (x[i] != y[i]) { std::printf("%s[%zu] %.9g, Android %.9g\n", name, i, x[i], y[i]); bad++; }
        };
        cmp("sensorToProPhoto", *p.sensorToProPhoto, *a.sensorToProPhoto);
        cmp("CCT", p.CCT->matrix, a.CCT->matrix);
        cmp("whitePoint", *p.whitePoint, *a.whitePoint);
        if (p.sceneCct != a.sceneCct || !(p.sceneDuv == a.sceneDuv || (std::isnan(p.sceneDuv) && std::isnan(a.sceneDuv))) || p.colourSource != a.colourSource) {
            std::printf("scene %.9g K Duv %.9g %s, Android %.9g K Duv %.9g %s\n", p.sceneCct, p.sceneDuv, p.colourSource.c_str(), a.sceneCct,
                        a.sceneDuv, a.colourSource.c_str());
            bad++;
        }
        std::printf("colour: %s\n", bad ? "DIFFERS" : "identical (matrices, neutral, scene light)");
        if (argc >= 5 && std::string(argv[3]) == "--dng") {
            const std::string bytes = readText(argv[4]);
            const DngShot dng = readDng(reinterpret_cast<const uint8_t*>(bytes.data()), bytes.size());
            const Parameters q = shotParameters(dng, a.rawSize->x, a.rawSize->y, [](const std::string& l) { std::printf("  %s\n", l.c_str()); });
            const int before = bad;
            cmp("dng sensorToProPhoto", *q.sensorToProPhoto, *a.sensorToProPhoto);
            cmp("dng CCT", q.CCT->matrix, a.CCT->matrix);
            cmp("dng whitePoint", *q.whitePoint, *a.whitePoint);
            cmp("dng gainMap", *q.gainMap, *a.gainMap);
            if (q.mapSize->x != a.mapSize->x || q.mapSize->y != a.mapSize->y || q.gainMap->size() != a.gainMap->size()) { std::printf("dng mapSize differs\n"); bad++; }
            for (size_t c = 0; c < 3; c++)
                if (q.noiseModeler->baseModel[c] != a.noiseModeler->baseModel[c]) { std::printf("dng noise model %zu differs\n", c); bad++; }
            if (q.cameraRotation != a.cameraRotation || q.iso != a.iso || q.cfaPattern != a.cfaPattern || q.whiteLevel != a.whiteLevel
                || q.sceneCct != a.sceneCct) { std::printf("dng rotation / ISO / CFA / white / scene differ\n"); bad++; }
            std::printf("dng: %s\n", bad == before ? "the reader gives the same parameters" : "DIFFERS");
        }
        return bad ? 1 : 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "shot_params_check: %s\n", e.what());
        return 2;
    }
}
