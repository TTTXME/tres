// processing/color/ToneCurve.java (the parts the photo tone reads: parse, eval / samples, the AgX / ACES / Uchimura references).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <sstream>
#include <string>
#include <vector>

namespace scam_post {

struct ToneCurve {
    static constexpr int AGX = 0, ACES = 1, UCHIMURA = 2, CUSTOM = 3;
    static constexpr int SAMPLES = 256;
    static constexpr const char* DEFAULT_POINTS = "0.0000:0.0000,0.2500:0.1600,0.5000:0.5000,0.7500:0.8400,1.0000:1.0000";
    static constexpr const char* SETTING_POINTS = "0.0000:0.0000,0.0625:0.0314,0.1250:0.0599,0.1875:0.0881,0.2500:0.1282,0.3125:0.1971,0.3750:0.2867,0.4375:0.3915,0.5000:0.5100,0.5625:0.6347,0.6250:0.7542,0.6875:0.8577,0.7500:0.9350,0.8125:0.9757,0.8750:0.9937,0.9375:1.0000,1.0000:1.0000";
    static constexpr float MIN_GAP = 0.01f;
    struct P { float x, y; };
    std::vector<P> points;

    static float clamp01(float v) { return std::max(0.f, std::min(1.f, v)); }
    static std::string trim(const std::string& s) {
        const size_t a = s.find_first_not_of(" \t\n\r"), b = s.find_last_not_of(" \t\n\r");
        return a == std::string::npos ? "" : s.substr(a, b - a + 1);
    }
    static bool parseFloat(const std::string& t, float& out) {
        const std::string s = trim(t);
        if (s.empty()) return false;
        char* end = nullptr;
        out = std::strtof(s.c_str(), &end);
        return end && *end == 0;
    }
    static ToneCurve parse(const std::string& text) {
        ToneCurve c;
        std::istringstream in(text);
        std::string pair;
        while (std::getline(in, pair, ',')) {
            const std::string p = trim(pair);
            const size_t colon = p.find(':');
            if (colon == std::string::npos || p.find(':', colon + 1) != std::string::npos) continue;
            float x, y;
            if (!parseFloat(p.substr(0, colon), x) || !parseFloat(p.substr(colon + 1), y)) continue;
            if (std::isnan(x) || std::isnan(y)) continue;
            c.points.push_back({clamp01(x), clamp01(y)});
        }
        if (c.points.size() < 2) return parse(DEFAULT_POINTS);
        c.normalise();
        return c;
    }
    void normalise() {
        std::stable_sort(points.begin(), points.end(), [](const P& a, const P& b) { return a.x < b.x; });
        points.front().x = 0.f;
        points.back().x = 1.f;
        for (int i = int(points.size()) - 2; i >= 1; i--)
            if (points[size_t(i + 1)].x - points[size_t(i)].x < MIN_GAP || points[size_t(i)].x - points[size_t(i - 1)].x < MIN_GAP)
                points.erase(points.begin() + i);
    }
    std::vector<float> tangents() const {
        const int n = int(points.size());
        std::vector<float> d(size_t(n - 1)), m(static_cast<size_t>(n));
        for (int i = 0; i < n - 1; i++)
            d[size_t(i)] = (points[size_t(i + 1)].y - points[size_t(i)].y) / std::max(1e-6f, points[size_t(i + 1)].x - points[size_t(i)].x);
        m[0] = d[0];
        m[size_t(n - 1)] = d[size_t(n - 2)];
        for (int i = 1; i < n - 1; i++) m[size_t(i)] = d[size_t(i - 1)] * d[size_t(i)] <= 0.f ? 0.f : (d[size_t(i - 1)] + d[size_t(i)]) * 0.5f;
        for (int i = 0; i < n - 1; i++) {
            if (std::fabs(d[size_t(i)]) < 1e-7f) { m[size_t(i)] = 0.f; m[size_t(i + 1)] = 0.f; continue; }
            const float a = m[size_t(i)] / d[size_t(i)], b = m[size_t(i + 1)] / d[size_t(i)];
            if (a < 0.f) m[size_t(i)] = 0.f;
            if (b < 0.f) m[size_t(i + 1)] = 0.f;
            const float s = a * a + b * b;
            if (s > 9.f) {
                const float t = 3.f / float(std::sqrt(double(s)));
                m[size_t(i)] = t * a * d[size_t(i)];
                m[size_t(i + 1)] = t * b * d[size_t(i)];
            }
        }
        return m;
    }
    float eval(float x) const {
        const auto m = tangents();
        x = clamp01(x);
        size_t k = 0;
        while (k < points.size() - 2 && x > points[k + 1].x) k++;
        const P a = points[k], b = points[k + 1];
        const float h = std::max(b.x - a.x, 1e-6f), t = (x - a.x) / h, t2 = t * t, t3 = t2 * t;
        const float v = (2 * t3 - 3 * t2 + 1) * a.y + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * b.y + (t3 - t2) * h * m[k + 1];
        return clamp01(v);
    }
    std::vector<float> samples(int n) const {
        std::vector<float> out(static_cast<size_t>(n));
        for (int i = 0; i < n; i++) out[size_t(i)] = eval(n == 1 ? 0.f : float(i) / float(n - 1));
        return out;
    }
};

} // namespace scam_post
