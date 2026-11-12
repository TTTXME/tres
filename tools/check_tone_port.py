#!/usr/bin/env python3
"""P630b «Oldstyle»: the owner's tone-port module as the app builds it (app/src/main/cpp/third_party/tone_port, unchanged) and
the app's JNI path into it (oldstyle-tone-jni.cpp, processing/oldstyle/OldstyleNative, OldstyleAe), on the host:
- the module's own regression (tests/regression.py, both profiles, --full-frame: 90 byte-for-byte cases);
- the owner's specification's numbers through the module: the LUT / inverse / RAW LUT SHA-256, the step test row y = 64 of 2.7 and
  3.1, the ramp / edge fixture hashes, the raw-ramp RAW8 / RAW10 hashes;
- the JNI library built for the host (the JDK's jni.h, the same sources and -ffp-contract=off) and driven through the shipped
  Java (javac): V1 SDR / HDR and V2 SDR / HDR / postview from a float frame through OldstyleAe.sample equal a direct C++ call of the
  module with the same int16 planes and parameters, byte for byte; the strip deinterleave; raw_hdr_gains_31 == OldstyleAe's gains;
- the vendored copy is the owner's archive with the P630e hook and the P636 threads (third_party/tone_port.p636.diff, then
  tone_port.p630e.diff reverse cleanly; with the extracted archive given, --archive DIR, the reversed copy is the archive
  byte for byte).
P636 (speed, the same bytes): the module's regression at 1, 2, 3, 4 and 7 threads (TONE_PORT_THREADS; more than one thread
takes the row kernels and the bands); the JNI checks below run on one thread and on four against the one-thread direct call;
tools/bench_oldstyle_tone.cpp: the app's path on one thread (P630e's) == the banded path, nine cases (V1 / V2, HDR / SDR, the
stages; three with P630h's rows away from their defaults: the curve's shape, half the low-frequency transfer, a matrix
stronger than a camera's) on frames that are and are not multiples of 8 / 32.
P630e, the owner's supplement (third_party/tone_supplement, hdr-ae-color-27-31.md):
- its 45 plan rows (2.7 YUV, 3.1 YUV, 3.1 RAW): plan27 / plan31 from the table's S*, L*, F give its HDR, S, L and fusion_gains
  (S0 = 8, P = 1, M = 16) its gS / gL, each within 2e-7 of the value; the same rows through OldstyleAe's Java port and through
  the JNI's plan (javac driver); the NV12 / NV21 / YUV444 SHA-256 of the 64 x 48 pattern;
- our metering (OldstyleAe.meter) on the 15 control scenes: the tables' HDR decision on 14 of 15 (the checkerboard 12 the miss)
  per profile, F of the HDR scenes within 0.07 of the tables';
- the YUV-route stages on our route in the JNI: dehaze / tint (multiple of 8 and not, unit and lens-shading grid) and output
  saturation equal a direct implementation from the supplement's functions; the low-frequency colour moves the bright exposure's
  shadow colour towards the linear reference (V1 HDR) and is a no-op on a frame whose colour it already has.
P630h, the rows of «Oldstyle Тон» that reach the module (section 7), on V1 SDR / V1 HDR / V2 SDR / V2 HDR:
- at their defaults the picture is the one without the rows, byte for byte; the tone curve's shape at all corners of its range
  keeps black and white and moves the midtones / the contrast its way; the widest gains the rows allow run; V1's matrix share
  0 / 1 / 1.5 and a matrix of rows up to 7.3 with a light past white (no Q16 wrap); the low-frequency transfer's share and its
  colour-only switch; the viewfinder's LUT with the curve (previewLutCurve(0, 0) == previewLut, monotonic as the module's own).
Usage: check_tone_port.py [--archive DIR]
"""
import argparse
import filecmp
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
TP = ROOT / 'app/src/main/cpp/third_party/tone_port'
JNI = ROOT / 'app/src/main/cpp/oldstyle-tone-jni.cpp'
SUPP = ROOT / 'app/src/main/cpp/third_party/tone_supplement/tone_supplement.cpp'
HOOK = ROOT / 'app/src/main/cpp/third_party/tone_port.p630e.diff'
THREADS_DIFF = ROOT / 'app/src/main/cpp/third_party/tone_port.p636.diff'
BENCH = ROOT / 'tools/bench_oldstyle_tone.cpp'
JAVA = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/oldstyle'
FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


SPEC_HARNESS = r'''
#include "tone_port.h"
#include "../src/rgb8_hdr.h"
#include <cstdio>
#include <vector>
static void put(const char* n, const void* d, size_t b) { FILE* f = fopen(n, "wb"); fwrite(d, 1, b, f); fclose(f); }
int main() {
    put("yuv_curve.bin", tone::yuv_curve().data(), 1024);
    auto inv = tone::invert(tone::yuv_curve());
    put("inverse.bin", inv.data(), 512);
    put("raw_curve.bin", tone::raw_curve_31().data(), 8192);
    const int W = 128, H = 128;
    for (int v = 0; v < 2; ++v) for (int fx = 0; fx < 2; ++fx) {
        std::vector<uint8_t> b(W * H * 3), d(W * H * 3);
        const int L[3] = {64, 88, 96}, R[3] = {248, 225, 192};
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) for (int c = 0; c < 3; c++) {
            const int val = fx == 0 ? x * 255 / 127 : (x < 64 ? L[c] : R[c]);
            b[(y * W + x) * 3 + c] = val; d[(y * W + x) * 3 + c] = val / 2;
        }
        tone::Image bi{b.data(), b.size(), W, H, W * 3, 0}, di{d.data(), d.size(), W, H, W * 3, 0};
        auto o = tone::detail::tone_virtual_rgb8(v ? tone::Version::v31 : tone::Version::v27, bi, di);
        char n[64]; snprintf(n, sizeof n, "fx_%s_%s.bin", v ? "31" : "27", fx ? "edge" : "ramp"); put(n, o.data(), o.size());
    }
    std::vector<int16_t> src(64 * 64 * 3);
    for (int c = 0; c < 3; c++) for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) src[c * 4096 + y * 64 + x] = x * 4095 / 63;
    tone::RawRgbImage r{src.data(), src.size(), 64, 64, 64, 4096};
    const float g[3][2] = {{1, 4}, {1, 1}, {.75f, 2}};
    for (int i = 0; i < 3; i++) for (int pv = 0; pv < 2; pv++) {
        auto o = tone::tone_raw31_hdr(r, g[i][0], g[i][1], pv, tone::raw_curve_31());
        std::vector<uint16_t> hwc(o.size());
        for (int c = 0; c < 3; c++) for (int p = 0; p < 4096; p++) hwc[p * 3 + c] = o[c * 4096 + p];
        char n[64]; snprintf(n, sizeof n, "raw_%g_%g_%s.bin", g[i][0], g[i][1], pv ? "8" : "10"); put(n, hwc.data(), hwc.size() * 2);
    }
    return 0;
}
'''

# the module called directly with the planes and parameters the JNI gets (the reference of the JNI path); P630e: the gains
# directly (OldstyleAe's plan), awb_ccm with unit WB for V1, and the YUV-route stages written here from the supplement's functions
DIRECT = r'''
#include "tone_port.h"
#include "tone_supplement.cpp"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>
using namespace tone_supplement;
int main(int argc, char** argv) {
    // direct <version> <w> <h> <planes.i16> <gS> <gL> <hdr> <postview> <sat> <ccm9 csv> <out> <dehaze maxdark pct corner> <sat on s v sps vps> <shading.f32 sw sh | - 0 0>
    const int version = std::stoi(argv[1]); const size_t w = std::stoul(argv[2]), h = std::stoul(argv[3]);
    std::ifstream in(argv[4], std::ios::binary); std::vector<char> raw{std::istreambuf_iterator<char>(in), {}};
    std::vector<int16_t> src(raw.size() / 2); std::memcpy(src.data(), raw.data(), raw.size());
    const float gs = std::stof(argv[5]), gl = std::stof(argv[6]);
    const bool hdr = std::stoi(argv[7]), post = std::stoi(argv[8]); const float sat = std::stof(argv[9]);
    std::array<float, 9> ccm{}; { std::string s = argv[10]; size_t p = 0; for (int i = 0; i < 9; i++) { size_t q = s.find(',', p); ccm[i] = std::stof(s.substr(p, q - p)); p = q + 1; } }
    const int dh = std::stoi(argv[12]), maxdark = std::stoi(argv[13]); const float pct = std::stof(argv[14]), corner = std::stof(argv[15]);
    const int son = std::stoi(argv[16]); const float ss = std::stof(argv[17]), sv = std::stof(argv[18]), sps = std::stof(argv[19]), vps = std::stof(argv[20]);
    std::vector<float> shading; const int sw = std::stoi(argv[22]), shh = std::stoi(argv[23]);
    if (std::string(argv[21]) != "-") { std::ifstream f(argv[21], std::ios::binary); std::vector<char> b{std::istreambuf_iterator<char>(f), {}}; shading.resize(b.size() / 4); std::memcpy(shading.data(), b.data(), b.size()); }
    const size_t n = w * h * 3; std::vector<uint8_t> out;
    auto scaled = [&](float g) { std::vector<int16_t> o(n); for (size_t i = 0; i < n; i++) { float v = src[i] * g; o[i] = g == 1.f ? src[i] : (int16_t) std::lround(std::fmax(-32768.f, std::fmin(32767.f, v))); } return o; };
    if (version == 1) {
        const auto& curve = tone::yuv_curve();
        ccm = awb_ccm(ccm, 512, 512, 512, 512, false);
        // V1's input at most two whites (the module's Q16 CCM wraps past the int16 range: the JNI's V1_CEILING)
        std::vector<int16_t> f(n); const float g1 = hdr ? gs : gl;
        for (size_t i = 0; i < n; i++) f[i] = (int16_t) std::lround(std::fmax(-32768.f, std::fmin(8190.f, src[i] * g1)));
        out = tone::finish_raw27_rgb({f.data(), f.size(), w, h, w, w * h}, ccm, curve);
        if (hdr) {
            const size_t pw = (w + 31) / 32 * 32, ph = (h + 31) / 32 * 32; std::vector<uint8_t> pad(pw * ph * 3);
            for (size_t y = 0; y < ph; y++) for (size_t x = 0; x < pw; x++) for (int c = 0; c < 3; c++)
                pad[(y * pw + x) * 3 + c] = out[((y < h ? y : h - 1) * w + (x < w ? x : w - 1)) * 3 + c];
            auto inv = tone::invert(curve); const uint32_t q8 = (uint32_t) std::lround(std::fmin(64.f, gl / gs) * 256.f);
            std::array<tone::Curve10, 3> b{}, d{}; for (int c = 0; c < 3; c++) { b[c] = tone::retone(inv, curve, q8); d[c] = tone::retone(inv, curve, 256); }
            auto t = tone::tone_rgb8_hdr_luts(tone::Version::v27, tone::ColorInput::rgb, {pad.data(), pad.size(), pw, ph, pw * 3, 0}, b, d);
            for (size_t y = 0; y < h; y++) std::memcpy(out.data() + y * w * 3, t.data() + y * pw * 3, w * 3);
        }
    } else if (hdr) {
        out = tone::render_raw31_hdr({src.data(), src.size(), w, h, w, w * h}, {gs, gl}, post, tone::raw_curve_31(), sat);
    } else {
        auto f = scaled(gl);
        out = tone::render_raw31_sdr({f.data(), f.size(), w, h, w, w * h}, tone::raw_curve_31(), sat);
    }
    if (dh) {
        // the frame grown to multiples of 8 (edges repeated), YUV444 Q7, the tile gains bilinear at the tile centres (nodes at
        // (i + 0.5) / n of the frame), dehaze_yuv444, back Q6
        const size_t pw = (w + 7) / 8 * 8, ph = (h + 7) / 8 * 8, tw = pw / 8, th = ph / 8;
        std::vector<uint8_t> Y(pw * ph), U(pw * ph), V(pw * ph);
        for (size_t y = 0; y < ph; y++) for (size_t x = 0; x < pw; x++) {
            const uint8_t* q = out.data() + ((y < h ? y : h - 1) * w + (x < w ? x : w - 1)) * 3;
            const auto o = rgb8_to_yuv444({q[0], q[1], q[2]}); Y[y * pw + x] = o[0]; U[y * pw + x] = o[1]; V[y * pw + x] = o[2];
        }
        std::vector<ChromaTileGain> grid(tw * th, ChromaTileGain{1.f, 1.f});
        if (!shading.empty()) for (size_t ty = 0; ty < th; ty++) for (size_t tx = 0; tx < tw; tx++) {
            float u = std::fmin(1.f, (tx * 8 + 4.f) / w) * sw - .5f, v = std::fmin(1.f, (ty * 8 + 4.f) / h) * shh - .5f;
            u = std::fmax(0.f, std::fmin(float(sw - 1), u)); v = std::fmax(0.f, std::fmin(float(shh - 1), v));
            const int x0 = std::min(sw - 2, (int) u), y0 = std::min(shh - 2, (int) v); const float fx = u - x0, fy = v - y0;
            for (int c = 0; c < 2; c++) {
                auto at = [&](int x, int y) { return shading[(size_t(y) * sw + x) * 2 + c]; };
                const float top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx, bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx;
                grid[ty * tw + tx][c] = top + (bot - top) * fy;
            }
        }
        dehaze_yuv444(Y.data(), U.data(), V.data(), int(pw), int(ph), std::ptrdiff_t(pw), maxdark, pct, corner, grid.data(), std::ptrdiff_t(tw));
        for (size_t y = 0; y < h; y++) for (size_t x = 0; x < w; x++) { const auto o = yuv444_to_rgb8(Y[y * pw + x], U[y * pw + x], V[y * pw + x]); std::memcpy(out.data() + (y * w + x) * 3, o.data(), 3); }
    }
    if (son) {
        const auto p = output_saturation_params(ss, sv, gl, sps, vps);
        const auto t = make_saturation(version != 1, p[0], p[1]);
        for (size_t y = 0; y < h; y++) for (size_t x = 0; x < w; x++) {
            uint8_t* q = out.data() + (y * w + x) * 3;
            const auto o = saturate_rgb8({q[0], q[1], q[2]}, t, x < (w & ~size_t(15)) && y < (h & ~size_t(15))); std::memcpy(q, o.data(), 3);
        }
    }
    std::ofstream o(argv[11], std::ios::binary); o.write((const char*) out.data(), out.size());
    return 0;
}
'''

# the supplement's tables: (n, S*, L*, HDR, F, S, L, gS, gL) - 2.7 YUV, 3.1 YUV, 3.1 RAW (RAW: 09 and 10 differ from YUV)
T27 = [(1, 989.201721, 315.177246), (2, 270.237793, 100.612755), (3, 35.0170898, 15.1982012), (4, 35.0170898, 15.1982012),
       (5, 322.131866, 223.905884), (6, 41.5780716, 36.3195), (7, 42.9457512, 18.7574635),
       (8, 53.0715027, 135.983078, 0.783658624, 53.0715027, 135.983078, 6.22721815, 16),
       (9, 38.8047676, 470.713745), (10, 41.8184509, 333.559784), (11, 40.6862411, 44.8460083), (12, 33.9252625, 35.5207481),
       (13, 45.378891, 174.472137, 0.752773225, 48.7554092, 170.643936, 5.58983946, 16),
       (14, 46.6515732, 108.750824, 0.752773225, 46.6515732, 108.750824, 5.50525713, 13.593853), (15, 49.9008942, 58.9369354)]
T31 = [(1, 984.76886, 378.037628), (2, 275.014069, 130.732986), (3, 35.3905334, 15.8302412), (4, 35.3905334, 15.8302412),
       (5, 359.182831, 221.97908), (6, 44.7052231, 36.4365959), (7, 43.1003838, 20.3144894),
       (8, 54.9586372, 118.221741, 0.782477736, 54.9586372, 118.221741, 6.5144515, 14.7777176),
       (9, 38.5246315, 431.15033), (10, 42.1738472, 326.639313), (11, 42.4299278, 49.17836), (12, 35.8402328, 36.7030449),
       (13, 47.7459755, 156.621124, 0.750348628, 47.7459755, 156.621124, 5.50388479, 16),
       (14, 48.8378639, 100.784462, 0.750348628, 48.8378639, 100.784462, 5.80144739, 12.5980577), (15, 53.0210648, 56.1362877)]
R31 = [r if r[0] not in (9, 10) else
       ((9, 38.5246315, 431.15033, 0.750348628, 49.5841408, 396.673157, 5.08700275, 16) if r[0] == 9 else
        (10, 42.1738472, 326.639313, 0.823265851, 42.1738472, 326.639313, 4.35407829, 16)) for r in T31]
TABLES = (('2.7 YUV', 27, 0, T27), ('3.1 YUV', 31, 0, T31), ('3.1 RAW', 31, 1, R31))

# the supplement called directly: rows from stdin "profile raw S* L* F" -> "hdr S L gS gL"; the colour pattern's RGB8 buffers
SUPP_HARNESS = r'''
#include "tone_supplement.cpp"
#include <cstdio>
#include <vector>
using namespace tone_supplement;
static void put(const char* n, const std::vector<unsigned char>& v) { FILE* f = fopen(n, "wb"); fwrite(v.data(), 1, v.size(), f); fclose(f); }
int main() {
    int prof, raw; float s, l, f;
    while (scanf("%d %d %f %f %f", &prof, &raw, &s, &l, &f) == 5) {
        const ExposurePlan p = prof == 27 ? plan27(s, l, f) : plan31(s, l, f, raw != 0, false, 0.f, 0.1f, 1000.f);
        const auto g = fusion_gains(8.f, p.short_plan, p.long_plan, 1.f, 16.f);
        printf("%d %.9g %.9g %.9g %.9g\n", p.use_hdr ? 1 : 0, p.short_plan, p.long_plan, g.short_gain, g.long_gain);
    }
    const int W = 64, H = 48;
    std::vector<unsigned char> nv12, nv21, y444;
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
        const int Y = ((7 * x + 11 * y) ^ (x * y)) & 255, cx = x / 2, cy = y / 2;
        const int U = ((29 * cx + 13 * cy) ^ (3 * cx)) & 255, V = ((19 * cx + 23 * cy) ^ (5 * cy)) & 255;
        const auto a = yuv_to_rgb8(Y, U, V, false); nv12.insert(nv12.end(), a.begin(), a.end());
        const auto b = yuv_to_rgb8(Y, V, U, true); nv21.insert(nv21.end(), b.begin(), b.end());
        const int U4 = ((29 * x + 13 * y) ^ (3 * x)) & 255, V4 = ((19 * x + 23 * y) ^ (5 * y)) & 255;
        const auto c = yuv444_to_rgb8(Y, U4, V4); y444.insert(y444.end(), c.begin(), c.end());
    }
    put("nv12.rgb", nv12); put("nv21.rgb", nv21); put("yuv444.rgb", y444);
    return 0;
}
'''

# OldstyleAe's Java port and the JNI's plan on the same rows; OldstyleAe.meter on the control scenes
PLAN_DRIVER = r'''
package com.particlesdevs.photoncamera.processing.oldstyle;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public final class PlanHost {
    public static void main(String[] a) throws Exception {
        boolean jni = OldstyleNative.load();
        if (a[0].equals("plan")) {
            for (String line : Files.readAllLines(Paths.get(a[1]))) {
                String[] t = line.trim().split(" ");
                int prof = Integer.parseInt(t[0]); boolean raw = t[1].equals("1");
                float s = Float.parseFloat(t[2]), l = Float.parseFloat(t[3]), f = Float.parseFloat(t[4]);
                OldstyleAe.ExposurePlan p = prof == 27 ? OldstyleAe.plan27(s, l, f) : OldstyleAe.plan31(s, l, f, raw, false, 0f, 0.1f, 1000f);
                float[] g = OldstyleAe.fusionGains(8f, p.shortPlan, p.longPlan, 1f, 16f);
                float[] n = new float[6];
                if (jni && (prof == 27 || raw)) OldstyleNative.plan(prof, s, l, f, 8f, 16f, n);
                System.out.println((p.useHdr ? 1 : 0) + " " + p.shortPlan + " " + p.longPlan + " " + g[0] + " " + g[1]
                        + " jni " + (int) n[1] + " " + n[2] + " " + n[3] + " " + n[4] + " " + n[5]);
            }
        } else {
            // meter <scene f32 32x24x3> ... : the fitted L*/S* of 2.7 and 3.1, F, the HDR decisions
            for (int i = 1; i < a.length; i++) {
                ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[i]))).order(ByteOrder.LITTLE_ENDIAN);
                float[] v = new float[32 * 24 * 3]; b.asFloatBuffer().get(v);
                OldstyleAe.Meter m27 = OldstyleAe.meter(v, 32, 24, false, 1f), m31 = OldstyleAe.meter(v, 32, 24, true, 1f);
                System.out.println(m27.ratio() + " " + m31.ratio() + " " + m27.fractionLong + " " + m31.fractionLong + " "
                        + OldstyleAe.rule(false, m27.shortRel, m27.longRel, m27.fractionLong).useHdr + " "
                        + OldstyleAe.rule(true, m31.shortRel, m31.longRel, m31.fractionLong).useHdr);
            }
        }
    }
}
'''


def scene(n):
    """The supplement's control scene n (32 x 24, X = x / 31, Y = y / 23), the meter's short input (12-bit, rounded)."""
    y, x = np.mgrid[0:24, 0:32]
    X, Y = x / 31, y / 23
    m = (1, 1, 1)
    v = {1: lambda: np.full(X.shape, 0.035), 2: lambda: np.full(X.shape, 0.12), 3: lambda: np.full(X.shape, 0.42),
         4: lambda: np.full(X.shape, 0.76), 5: lambda: 0.015 + 0.085 * X, 6: lambda: 0.06 + 0.34 * X, 7: lambda: 0.11 + 0.77 * X,
         8: lambda: 0.02 + 0.55 * X ** 3, 9: lambda: np.where(X < 0.72, 0.016, 0.82),
         10: lambda: np.where((X > 0.35) & (X < 0.62) & (Y > 0.25) & (Y < 0.73), 0.85, 0.018),
         11: lambda: np.where(X < 0.46, 0.075, 0.51), 12: lambda: np.where(((x // 4 + y // 3) % 2) == 1, 0.72, 0.07),
         13: lambda: np.where(X < 0.72, 0.04, 0.70), 14: lambda: np.where(X < 0.72, 0.065, 0.70),
         15: lambda: np.where(X < 0.72, 0.11, 0.70)}[n]()
    if n in (5, 10):
        m = (1.15, 1, 0.78)
    elif n == 12:
        m = (0.76, 1, 1.12)
    return (np.clip(np.round(4095 * v[..., None] * np.array(m)), 0, 4095) / 4095).astype(np.float32)


def rel(a, b):
    return abs(float(a) - float(b)) <= 2e-7 * abs(float(b))


def table_rows(lines, tag):
    """The 45 rows against a runner's output lines ("hdr S L gS gL ...")."""
    i = 0
    for name, prof, raw, table in TABLES:
        for r in table:
            t = lines[i].split()
            i += 1
            hdr = len(r) > 3
            ok = int(t[0]) == hdr
            if hdr:
                ok = ok and all(rel(t[k + 1], r[k + 4]) for k in range(4))
            check(ok, '%s %s scene %02d: HDR %s%s' % (tag, name, r[0], 'yes' if hdr else 'no',
                  (' S %s L %s gains %s / %s' % tuple(t[1:5])) if hdr else ''))


DRIVER = r'''
package com.particlesdevs.photoncamera.processing.oldstyle;
import java.nio.*;
import java.nio.file.*;
public final class ToneHost {
    public static void main(String[] a) throws Exception {
        if (!OldstyleNative.load()) throw new IllegalStateException("no library");
        if (a[0].equals("lut")) {
            // P630h: the viewfinder's LUT of a version with a shaped curve ("-": previewLut itself)
            byte[] lut = new byte[4096];
            if (a[2].equals("-")) OldstyleNative.previewLut(Integer.parseInt(a[1]), lut);
            else OldstyleNative.previewLutCurve(Integer.parseInt(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]), lut);
            Files.write(Paths.get(a[4]), lut);
            return;
        }
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]);
        ByteBuffer f = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[3]))).order(ByteOrder.LITTLE_ENDIAN);
        float scale = Float.parseFloat(a[4]);
        // the GPU's RGBA16I frame as GL_INT strips -> the planar frame (OldstyleTone's readback), 7 rows a strip
        ByteBuffer planar = OldstyleNative.planar(w, h);
        int stripRows = 7;
        ByteBuffer strip = ByteBuffer.allocateDirect(w * stripRows * 16).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y += stripRows) {
            int rows = Math.min(stripRows, h - y);
            strip.clear();
            for (int r = 0; r < rows; r++) for (int x = 0; x < w; x++) for (int c = 0; c < 4; c++)
                strip.putInt(c < 3 ? OldstyleAe.sample(f.getFloat((((y + r) * w + x) * 3 + c) * 4), scale) : 0);
            strip.flip();
            OldstyleNative.deinterleave(strip, planar, w, h, y, rows);
        }
        byte[] pl = new byte[w * h * 6];
        planar.position(0); planar.get(pl);
        Files.write(Paths.get(a[5]), pl);
        // P636: the same frame as GL_SHORT strips (deinterleave16) gives the same planes
        ByteBuffer planar16 = OldstyleNative.planar(w, h);
        for (int y = 0; y < h; y += stripRows) {
            int rows = Math.min(stripRows, h - y);
            strip.clear();
            for (int r = 0; r < rows; r++) for (int x = 0; x < w; x++) for (int c = 0; c < 4; c++)
                strip.putShort((short) (c < 3 ? OldstyleAe.sample(f.getFloat((((y + r) * w + x) * 3 + c) * 4), scale) : 0));
            strip.flip();
            OldstyleNative.deinterleave16(strip, planar16, w, h, y, rows);
        }
        byte[] pl16 = new byte[w * h * 6];
        planar16.position(0); planar16.get(pl16);
        if (!java.util.Arrays.equals(pl, pl16)) throw new IllegalStateException("deinterleave16 != deinterleave");
        if (OldstyleNative.threadsFor(8, 0f) != 7 || OldstyleNative.threadsFor(8, 1f) != 1 || OldstyleNative.threadsFor(2, 0f) != 2)
            throw new IllegalStateException("threadsFor");
        float[] ccm = new float[9];
        String[] cs = a[6].split(",");
        for (int i = 0; i < 9; i++) ccm[i] = Float.parseFloat(cs[i]);
        // the post array as OldstyleAe.Params.post builds it
        OldstyleAe.Params p = new OldstyleAe.Params();
        String[] ps = a[12].split(",");
        p.dehaze = ps[0].equals("1"); p.maxDark = Integer.parseInt(ps[1]); p.percentile = Float.parseFloat(ps[2]);
        p.cornerStrength = Float.parseFloat(ps[3]); p.outSat = Float.parseFloat(ps[4]); p.vibrance = Float.parseFloat(ps[5]);
        p.satPerStop = Float.parseFloat(ps[6]); p.vibrancePerStop = Float.parseFloat(ps[7]); p.lowFrequency = ps[8].equals("1");
        p.lfThreshold = Integer.parseInt(ps[9]);
        // P630h: the rows' entries (absent: the defaults), and the CCM's share through OldstyleNative.ccmStrength
        if (ps.length > 10) p.lfChroma = ps[10].equals("1");
        if (ps.length > 11) p.lfStrength = Float.parseFloat(ps[11]);
        if (ps.length > 12) p.curveMid = Float.parseFloat(ps[12]);
        if (ps.length > 13) p.curveContrast = Float.parseFloat(ps[13]);
        if (ps.length > 14) ccm = OldstyleNative.ccmStrength(ccm, Float.parseFloat(ps[14]));
        float[] shading = null; int sw = 0, sh = 0;
        if (!a[13].equals("-")) {
            ByteBuffer sb = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[13]))).order(ByteOrder.LITTLE_ENDIAN);
            shading = new float[sb.remaining() / 4]; sb.asFloatBuffer().get(shading);
            sw = Integer.parseInt(a[14]); sh = Integer.parseInt(a[15]);
        }
        ByteBuffer out = ByteBuffer.allocateDirect(w * h * 3);
        float[] info = new float[6];
        OldstyleNative.render(Integer.parseInt(a[0]), planar, w, h, ccm, Float.parseFloat(a[7]), Float.parseFloat(a[8]),
                a[9].equals("1"), a[10].equals("1"), Float.parseFloat(a[11]), p.post(), shading, sw, sh, info, out);
        byte[] o = new byte[w * h * 3];
        out.position(0); out.get(o);
        Files.write(Paths.get(a[16]), o);
        System.out.println("info " + info[0] + " " + info[1] + " " + info[2] + " " + info[3]);
    }
}
'''


def sh(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--archive', type=Path)
    args = ap.parse_args()
    d = Path(tempfile.mkdtemp(prefix='tone_port_'))
    try:
        rev = d / 'rev'
        rev.mkdir()
        shutil.copytree(TP, rev / 'tone_port')
        r = subprocess.run(['patch', '-R', '-p0', '-s', '-i', str(THREADS_DIFF)], cwd=rev, capture_output=True, text=True)
        check(r.returncode == 0 and not (rev / 'tone_port/src/parallel.cpp').exists(),
              'the vendored tone_port is the P630e module plus the P636 threads (tone_port.p636.diff reverses cleanly)')
        r = subprocess.run(['patch', '-R', '-p0', '-s', '-i', str(HOOK)], cwd=rev, capture_output=True, text=True)
        check(r.returncode == 0, 'the vendored tone_port is the archive plus the P630e hook (the hook reverses cleanly)')
        if args.archive:
            cmp = filecmp.dircmp(args.archive, rev / 'tone_port')
            def same(c):
                return not c.left_only and not c.right_only and not c.diff_files and all(same(s) for s in c.subdirs.values())
            check(same(cmp), 'the vendored tone_port without its hook is the owner\'s archive byte for byte')
        # 1. the module's own regression, both profiles, full frame
        sh(['cmake', '-S', str(TP), '-B', str(d / 'build'), '-DCMAKE_BUILD_TYPE=Release', '-DTONE_BUILD_TESTS=ON'])
        sh(['cmake', '--build', str(d / 'build'), '--parallel'])
        # P636: on one thread (the module as it was) and on several (the row kernels, the bands): the same 90 hashes
        for threads in (1, 2, 3, 4, 7):
            for profile in ('2.7', '3.1'):
                r = subprocess.run([sys.executable, str(TP / 'tests/regression.py'), str(d / 'build/tone_check'), '--profile', profile,
                                    '--full-frame', '--prefix', 'env', '--prefix', 'TONE_PORT_THREADS=%d' % threads],
                                   capture_output=True, text=True)
                last = (r.stdout.strip().splitlines() or [''])[-1]
                check(r.returncode == 0 and 'passed' in last, 'tone-port regression %s, %d thread%s: %s'
                      % (profile, threads, '' if threads == 1 else 's', last))
        # P636: the app's path (oldstyle-tone-core.h) on one thread == on several, frames of awkward and of whole sizes
        sh(['g++', '-std=c++17', '-O2', '-ffp-contract=off', '-I' + str(JNI.parent), '-I' + str(TP / 'include'), str(BENCH)]
           + [str(q) for q in sorted((TP / 'src').glob('*.cpp'))] + ['-o', str(d / 'bench'), '-lpthread'])
        r = subprocess.run([str(d / 'bench'), '--exhaustive'], capture_output=True, text=True)
        check(r.returncode == 0 and ': 0 differ' in r.stdout, 'the banded pass\'s pixel functions == the supplement\'s: ' + r.stdout.strip())
        for w, h, threads in ((1234, 1001, 4), (1000, 744, 3), (2048, 1536, 7), (67, 45, 2), (640, 20, 5)):
            r = subprocess.run([str(d / 'bench'), str(w), str(h), str(threads), '1'], capture_output=True, text=True)
            rows = r.stdout.strip().splitlines()
            check(r.returncode == 0 and len(rows) == 9 and all('SAME BYTES' in row for row in rows),
                  'Oldstyle tone %dx%d: one thread == %d threads, %d of 9 cases the same bytes' % (
                      w, h, threads, sum('SAME BYTES' in row for row in rows)))
        # 2. the specification's numbers through the module
        (d / 'spec').mkdir()
        (d / 'spec/spec.cpp').write_text(SPEC_HARNESS.replace('../src/rgb8_hdr.h', str(TP / 'src/rgb8_hdr.h')))
        sh(['g++', '-std=c++17', '-O2', '-ffp-contract=off', '-I' + str(TP / 'include'), str(d / 'spec/spec.cpp'),
            str(d / 'build/libtone_port.a'), '-o', str(d / 'spec/spec')])
        sh([str(d / 'spec/spec')], cwd=d / 'spec')
        hx = lambda n: hashlib.sha256((d / 'spec' / n).read_bytes()).hexdigest()
        want = {'yuv_curve.bin': '5bbd14e306c4943a', 'inverse.bin': '454a21c88b3e5a75', 'raw_curve.bin': '8c0a5088518ef732',
                'fx_27_ramp.bin': '57e458c45adab2bf', 'fx_27_edge.bin': 'b042dcad125a4d29', 'fx_31_ramp.bin': '95ecb752a8eae800',
                'fx_31_edge.bin': 'f4454ea79b3933ed', 'raw_1_4_8.bin': 'b6e7eaa7a3e58e87', 'raw_1_4_10.bin': 'c05def63740bebca',
                'raw_1_1_8.bin': '40f500f18f07d92c', 'raw_1_1_10.bin': '40f500f18f07d92c', 'raw_0.75_2_8.bin': '06d452cde40dce2e',
                'raw_0.75_2_10.bin': '06d452cde40dce2e'}
        for n, h in want.items():
            check(hx(n).startswith(h), 'specification SHA-256 %s... of %s through the module' % (h, n))
        rows = {'27': {0: (64, 87, 96), 56: (37, 62, 70), 63: (11, 40, 54), 64: (151, 144, 127), 72: (157, 146, 127), 127: (133, 119, 103)},
                '31': {0: (64, 87, 96), 56: (37, 60, 69), 63: (11, 38, 53), 64: (147, 140, 123), 72: (153, 143, 125), 127: (127, 114, 98)}}
        for v, row in rows.items():
            o = np.frombuffer((d / 'spec' / ('fx_%s_edge.bin' % v)).read_bytes(), np.uint8).reshape(128, 128, 3)
            check(all(tuple(int(c) for c in o[64, x]) == rgb for x, rgb in row.items()), 'specification step test y=64 of %s through the module' % v)
        # 3. P630e: the supplement on its own - the 45 plan rows and the colour hashes
        (d / 'supp').mkdir()
        (d / 'supp/supp.cpp').write_text(SUPP_HARNESS)
        sh(['g++', '-std=c++17', '-O2', '-ffp-contract=off', '-I' + str(SUPP.parent), str(d / 'supp/supp.cpp'), '-o', str(d / 'supp/supp')])
        rows_in = ''.join('%d %d %.9g %.9g %.9g\n' % (prof, raw, r[1], r[2], r[3] if len(r) > 3 else 0.0)
                          for _, prof, raw, table in TABLES for r in table)
        (d / 'rows.txt').write_text(rows_in)
        r = subprocess.run([str(d / 'supp/supp')], input=rows_in, capture_output=True, text=True, cwd=d / 'supp', check=True)
        table_rows(r.stdout.strip().splitlines(), 'supplement')
        hashes = {'nv12.rgb': '2a313081d739663a149fc1576b7626b000ca5b6878b21bc2e28a6ac419a0b16d',
                  'nv21.rgb': '40a2d5bb5f122d21d29094afbf02dc70578ed3f4ab0dd240dbc7b7086b4557c9',
                  'yuv444.rgb': 'bda2a548c67c376d6318ebacaa3c4a07946752517adb4823bf04c3314f2cf376'}
        for n, want_h in hashes.items():
            got = hashlib.sha256((d / 'supp' / n).read_bytes()).hexdigest()
            check(got == want_h, 'supplement colour hash %s of the 64x48 pattern: %s' % (n.split('.')[0].upper(), got))
        # 4. the JNI path: the library built for the host, driven by the shipped Java
        java_home = Path(os.environ.get('JAVA_HOME') or Path(shutil.which('javac')).resolve().parent.parent)
        inc = [str(java_home / 'include'), str(java_home / 'include/linux')]
        lib = d / 'libscameraTone_host.so'
        srcs = [str(p) for p in sorted((TP / 'src').glob('*.cpp'))] + [str(JNI)]
        sh(['g++', '-std=c++17', '-O2', '-fPIC', '-shared', '-ffp-contract=off', '-I' + str(TP / 'include')] + ['-I' + i for i in inc]
           + srcs + ['-o', str(lib), '-lpthread'])
        (d / 'direct.cpp').write_text(DIRECT)
        sh(['g++', '-std=c++17', '-O2', '-ffp-contract=off', '-I' + str(TP / 'include'), '-I' + str(SUPP.parent), str(d / 'direct.cpp'),
            str(d / 'build/libtone_port.a'), '-o', str(d / 'direct'), '-lpthread'])
        pkg = d / 'java/com/particlesdevs/photoncamera/processing/oldstyle'
        pkg.mkdir(parents=True)
        for f in ('OldstyleNative.java', 'OldstyleAe.java'):
            shutil.copy(JAVA / f, pkg / f)
        (pkg / 'ToneHost.java').write_text(DRIVER)
        (pkg / 'PlanHost.java').write_text(PLAN_DRIVER)
        (d / 'classes').mkdir()
        sh(['javac', '-nowarn', '-encoding', 'UTF-8', '-d', str(d / 'classes')] + [str(p) for p in pkg.glob('*.java')])
        java = ['java', '-Dscamera.tone.lib=' + str(lib), '-cp', str(d / 'classes')]
        # the rows through OldstyleAe's Java port, and the JNI's plan (2.7 YUV and 3.1 RAW: the app's two rules)
        r = sh(java + ['com.particlesdevs.photoncamera.processing.oldstyle.PlanHost', 'plan', str(d / 'rows.txt')])
        lines = r.stdout.strip().splitlines()
        table_rows(lines, 'OldstyleAe (Java)')
        k = 0
        for name, prof, raw, table in TABLES:
            for row in table:
                t = lines[k].split()
                k += 1
                if prof == 27 or raw:
                    hdr = len(row) > 3
                    ok = int(t[6]) == hdr and (not hdr or all(rel(t[7 + q], row[4 + q]) for q in range(4)))
                    check(ok, 'JNI plan (%s) scene %02d == the table' % (name, row[0]))
        # our metering on the control scenes
        files = []
        for n in range(1, 16):
            fp = d / ('scene%02d.f32' % n)
            np.ascontiguousarray(scene(n), '<f4').tofile(fp)
            files.append(str(fp))
        r = sh(java + ['com.particlesdevs.photoncamera.processing.oldstyle.PlanHost', 'meter'] + files)
        agree = {27: [], 31: []}
        for n, line in enumerate(r.stdout.strip().splitlines(), 1):
            r27, r31, f27, f31, h27, h31 = line.split()
            t27, t31 = T27[n - 1], R31[n - 1]
            agree[27].append((h27 == 'true') == (len(t27) > 3))
            agree[31].append((h31 == 'true') == (len(t31) > 3))
            print('INFO meter scene %02d: L*/S* 2.7 %.3f (table %.3f) 3.1 %.3f (table %.3f) F %.3f / %.3f hdr %s / %s'
                  % (n, float(r27), t27[2] / t27[1], float(r31), t31[2] / t31[1], float(f27), float(f31), h27, h31))
            for prof, t, f in ((27, t27, f27), (31, t31, f31)):
                if len(t) > 3 and n != 8:
                    check(abs(float(f) - t[3]) < 0.07, 'meter F of scene %02d (%d) %.3f vs the table %.3f' % (n, prof, float(f), t[3]))
        for prof in (27, 31):
            misses = [n for n, ok in enumerate(agree[prof], 1) if not ok]
            check(len(misses) <= 1 and set(misses) <= {12}, 'meter: the table\'s HDR decision on %d of 15 scenes (%s; misses %s)'
                  % (15 - len(misses), '2.7 YUV' if prof == 27 else '3.1 RAW', misses))
        rng = np.random.default_rng(6302)
        nothing = '0,48,0.2,0.2,0,1,0,0,0,16'
        # P636: the JNI on one thread (P630e's path) and on four (the banded path); the direct call on one thread both times
        for jni_threads in (1, 4):
          tag = '' if jni_threads == 1 else ' (JNI on %d threads)' % jni_threads
          jenv = dict(os.environ, TONE_PORT_THREADS=str(jni_threads))
          denv = dict(os.environ, TONE_PORT_THREADS='1')
          for w, h in ((161, 97), (256, 128)):
              y, x = np.indices((h, w))
              base = np.exp(rng.normal(-2.0, 1.2, (h, w, 1))) * np.array([1.0, 0.9, 0.8])
              base = base * (1 + 0.5 * np.sin(x / 9.0))[..., None] + (x > w * 0.7)[..., None] * 2.5   # a light past white
              base[3, 4] = (-0.05, 0.02, 9.0)
              lin = base.astype(np.float16).astype(np.float32)
              np.ascontiguousarray(lin, '<f4').tofile(d / 'lin.f32')
              ccm = '1.62,-0.48,-0.14,-0.21,1.41,-0.20,0.03,-0.52,1.49'
              # version, input scale, gS, gL, hdr, postview, saturation_adjustment
              cases = [(1, 1.0, 1.3, 1.3, 0, 0, 0.0), (1, 1.0, 0.47, 3.2, 1, 0, 0.0), (2, 1.0, 1.2, 1.2, 0, 0, 0.0),
                       (2, 0.25, 0.98, 10.0, 1, 0, 0.0), (2, 0.25, 1.1, 6.4, 1, 1, 0.2), (2, 1.0, 0.8, 7.0, 1, 0, -0.3)]
              for ver, scale, gs, gl, hdr, post, sat in cases:
                  cm = ccm if ver == 1 else '1,0,0,0,1,0,0,0,1'
                  sh(java + ['com.particlesdevs.photoncamera.processing.oldstyle.ToneHost', str(ver), str(w), str(h), str(d / 'lin.f32'),
                             str(scale), str(d / 'planes.i16'), cm, str(gs), str(gl), str(hdr), str(post), str(sat), nothing, '-', '0', '0',
                             str(d / 'jni.rgb')], env=jenv)
                  planes = np.fromfile(d / 'planes.i16', '<i2').reshape(3, h, w)
                  want_planes = np.clip(np.floor(lin * np.float32(scale) * np.float32(4095) + np.float32(0.5)), -32768, 32767).transpose(2, 0, 1)
                  check(np.array_equal(planes, want_planes), 'deinterleave: %dx%d planes from GL_INT strips (scale %g)%s' % (w, h, scale, tag))
                  sh([str(d / 'direct'), str(ver), str(w), str(h), str(d / 'planes.i16'), str(gs), str(gl), str(hdr), str(post), str(sat), cm,
                      str(d / 'direct.rgb'), '0', '48', '0.2', '0.2', '0', '0', '1', '0', '0', '-', '0', '0'], env=denv)
                  same = (d / 'jni.rgb').read_bytes() == (d / 'direct.rgb').read_bytes()
                  check(same, 'JNI == module: V%d %dx%d hdr %d postview %d gains %s/%s%s' % (ver, w, h, hdr, post, gs, gl, tag))
              # 5. the YUV-route stages on our route: dehaze / tint (unit and lens-shading grid; this size a multiple of 8 or not) and
              # the output saturation, JNI == the supplement's functions called here
              sw, shh = 6, 5
              gy, gx = np.mgrid[0:shh, 0:sw]
              shading = (1 + 1.5 * (((gx + 0.5) / sw - 0.5) ** 2 + ((gy + 0.5) / shh - 0.5) ** 2) * 2).astype(np.float32)
              np.ascontiguousarray(np.repeat(shading[..., None], 2, -1), '<f4').tofile(d / 'shading.f32')
              for ver, gs, gl, hdr in ((1, 0.47, 3.2, 1), (2, 1.2, 1.2, 0)):
                  cm = ccm if ver == 1 else '1,0,0,0,1,0,0,0,1'
                  for dh, son, shd, params in ((1, 0, '-', (48, 0.2, 0.2)), (1, 0, 'shading', (64, 0.5, 0.35)), (0, 1, '-', (48, 0.2, 0.2)),
                                               (1, 1, 'shading', (32, 1.0, 0.1))):
                      sat = ('0.25', '1.3', '0.05', '0.02') if son else ('0', '1', '0', '0')
                      post = '%d,%d,%g,%g,%s,%s,%s,%s,0,16' % ((dh,) + params + sat)
                      sf = [str(d / 'shading.f32'), str(sw), str(shh)] if shd != '-' else ['-', '0', '0']
                      sh(java + ['com.particlesdevs.photoncamera.processing.oldstyle.ToneHost', str(ver), str(w), str(h), str(d / 'lin.f32'),
                                 '1', str(d / 'planes.i16'), cm, str(gs), str(gl), str(hdr), '0', '0', post] + sf + [str(d / 'jni.rgb')], env=jenv)
                      sh([str(d / 'direct'), str(ver), str(w), str(h), str(d / 'planes.i16'), str(gs), str(gl), str(hdr), '0', '0', cm,
                          str(d / 'direct.rgb'), str(dh), str(params[0]), str(params[1]), str(params[2]), str(son)] + list(sat) + sf, env=denv)
                      same = (d / 'jni.rgb').read_bytes() == (d / 'direct.rgb').read_bytes()
                      check(same, 'JNI stages == the supplement: V%d %dx%d dehaze %d (%s grid, %s) saturation %d%s' % (
                          ver, w, h, dh, 'shading' if shd != '-' else 'unit', params, son, tag))
        # 5b. V1 lights far past white at a high SDR gain (and the low-frequency reference at the long gain) stay white: no wrap of
        # the module's Q16 CCM into a black hole (140250's sun before the JNI's V1_CEILING)
        w, h = 96, 64
        y, x = np.indices((h, w))
        frame = np.full((h, w, 3), 0.02, np.float32)
        frame[np.hypot(x - 48, y - 32) < 10] = (7.5, 7.0, 6.5)
        np.ascontiguousarray(frame, '<f4').tofile(d / 'lin.f32')
        for gs, gl, hdr, lf in ((16.0, 16.0, 0, 0), (0.9, 6.0, 1, 1)):
            sh(java + ['com.particlesdevs.photoncamera.processing.oldstyle.ToneHost', '1', str(w), str(h), str(d / 'lin.f32'), '1',
                       str(d / 'planes.i16'), '1.62,-0.48,-0.14,-0.21,1.41,-0.20,0.03,-0.52,1.49', str(gs), str(gl), str(hdr), '0', '0',
                       '0,48,0.2,0.2,0,1,0,0,%d,16' % lf, '-', '0', '0', str(d / 'jni.rgb')])
            o8 = np.fromfile(d / 'jni.rgb', np.uint8).reshape(h, w, 3)
            core = o8[np.hypot(x - 48, y - 32) < 6]
            check(core.min() >= 240, 'V1 light at %s gain %s%s stays white (darkest code %d)' % ('HDR' if hdr else 'SDR', gl,
                  ' with the low-frequency colour' if lf else '', core.min()))
        # 6. the low-frequency colour (V1 HDR): towards the linear reference in the shadows, about nothing where colour is kept
        w, h = 192, 128
        y, x = np.indices((h, w))
        dark = np.stack([0.010 + 0.004 * np.sin(x / 13.0), 0.008 + 0.0 * x, 0.006 + 0.004 * np.cos(y / 11.0)], -1)
        frame = np.where((x > 120)[..., None], 0.75, dark).astype(np.float32)
        np.ascontiguousarray(frame, '<f4').tofile(d / 'lin.f32')
        outs = {}
        for lf in (0, 1):
            post = '0,48,0.2,0.2,0,1,0,0,%d,16' % lf
            r = sh(java + ['com.particlesdevs.photoncamera.processing.oldstyle.ToneHost', '1', str(w), str(h), str(d / 'lin.f32'), '1',
                           str(d / 'planes.i16'), '1,0,0,0,1,0,0,0,1', '0.9', '3.15', '1', '0', '0', post, '-', '0', '0', str(d / 'jni.rgb')])
            outs[lf] = np.fromfile(d / 'jni.rgb', np.uint8).reshape(h, w, 3).astype(np.float64)
            if lf:
                scale = float(r.stdout.split()[1])
        sh([str(d / 'direct'), '1', str(w), str(h), str(d / 'planes.i16'), '3.15', '3.15', '0', '0', '0', '1,0,0,0,1,0,0,0,1',
            str(d / 'direct.rgb'), '0', '48', '0.2', '0.2', '0', '0', '1', '0', '0', '-', '0', '0'])
        ref = np.fromfile(d / 'direct.rgb', np.uint8).reshape(h, w, 3).astype(np.float64)
        region = (slice(16, h - 16), slice(16, 104))
        chroma = lambda a: a - a.mean(-1, keepdims=True)
        err = {lf: np.abs(chroma(outs[lf][region]) - chroma(ref[region])).mean() for lf in (0, 1)}
        check(scale >= 0 and err[1] <= err[0], 'low-frequency colour: the shadows\' colour error to the linear reference %.3f -> %.3f (scale %d/32)'
              % (err[0], err[1], scale))
        # 7. P630h, the rows of «Oldstyle Тон» that reach the module: each at both ends of its range on every route (no error, black
        # and white kept), and its direction on the picture
        tone = 'com.particlesdevs.photoncamera.processing.oldstyle.ToneHost'
        w, h = 160, 96
        y, x = np.indices((h, w))
        ramp = (x / (w - 1.0)) ** 2.2 * (0.6 + 0.4 * y / (h - 1.0))
        frame = np.stack([ramp * 1.00, ramp * 0.85, ramp * 0.60], -1).astype(np.float32)
        frame[:8, :8] = 0.0
        frame[-8:, -8:] = 4.0
        np.ascontiguousarray(frame, '<f4').tofile(d / 'lin.f32')
        cam = '1.62,-0.48,-0.14,-0.21,1.41,-0.20,0.03,-0.52,1.49'
        routes = (('V1 SDR', '1', '2.5', '2.5', '0'), ('V1 HDR', '1', '0.8', '3.2', '1'), ('V2 SDR', '2', '2.5', '2.5', '0'),
                  ('V2 HDR', '2', '0.8', '3.2', '1'))

        def run(route, post, ccm=None, gains=None):
            name, ver, gs, gl, hdr = route
            if gains:
                gs, gl = gains
            sh(java + [tone, ver, str(w), str(h), str(d / 'lin.f32'), '1', str(d / 'planes.i16'),
                       ccm or (cam if ver == '1' else '1,0,0,0,1,0,0,0,1'), gs, gl, hdr, '0', '0', post, '-', '0', '0', str(d / 'jni.rgb')])
            return np.fromfile(d / 'jni.rgb', np.uint8).reshape(h, w, 3).astype(np.float64)

        base_post = '0,24,0.2,0.2,0,1,0,0,1,16'
        luma = lambda a: a @ np.array([0.2126, 0.7152, 0.0722])
        for route in routes:
            name = route[0]
            base = run(route, base_post)
            check(np.array_equal(base, run(route, base_post + ',1,1,0,0,1')), '%s: the rows at their defaults give the picture without them' % name)
            got = {}
            for mid, con in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (-1, 1), (1, -1)):
                o = run(route, base_post + ',1,1,%d,%d' % (mid, con))
                got[(mid, con)] = o
                check(o[:8, :8].max() <= 12 and o[-8:, -8:].min() >= 243, '%s: curve %+d / %+d keeps black and white (%d, %d)'
                      % (name, mid, con, o[:8, :8].max(), o[-8:, -8:].min()))
            body = (slice(12, h - 12), slice(12, w - 12))
            m = {k: luma(v[body]).mean() for k, v in got.items()}
            b = luma(base[body]).mean()
            check(m[(-1, 0)] < b - 4 < b + 4 < m[(1, 0)], '%s: «Средние тона» -1 / 0 / +1 mean %.1f / %.1f / %.1f' % (name, m[(-1, 0)], b, m[(1, 0)]))
            # the S-contrast spreads the picture about the middle: darker below, brighter above
            lo, hi = luma(base[body]) < 90, luma(base[body]) > 170
            dl = lambda k: (luma(got[k][body])[lo].mean() - luma(base[body])[lo].mean(), luma(got[k][body])[hi].mean() - luma(base[body])[hi].mean())
            check(dl((0, 1))[0] < -1 and dl((0, 1))[1] > 1 and dl((0, -1))[0] > 1 and dl((0, -1))[1] < -1,
                  '%s: «Контраст кривой» +1 shadows %+.1f lights %+.1f, -1 shadows %+.1f lights %+.1f' % ((name,) + dl((0, 1)) + dl((0, -1))))
            # the widest exposures the rows allow (V2 max 16 x forgiveness 2, the base 0.95: gL / gS 36; the gains' limit 64)
            for gains in (('0.3', '11'), ('1.7', '64'), ('0.02', '0.05')):
                o = run(route, base_post, gains=gains)
                check(o.shape == (h, w, 3), '%s: gains %s / %s run' % ((name,) + gains))
        # V1: the matrix's share - 0 none (grey ramp stays the frame's own colour), 1.5 more colour, and no wrap of a light with it
        v1 = routes[0]
        chroma = lambda a: (a.max(-1) - a.min(-1))[12:h - 12, 12:w - 12].mean()
        c0, c1, c15 = (chroma(run(v1, base_post + ',1,1,0,0,%s' % k)) for k in ('0', '1', '1.5'))
        check(c0 < c1 < c15, 'V1: «Сила цветовой матрицы» 0 / 1 / 1.5 colour spread %.1f / %.1f / %.1f' % (c0, c1, c15))
        for route in routes[:2]:
            o = run(route, base_post + ',1,1,0,0,1.5', ccm='2.9,-1.5,-0.4,-0.9,2.6,-0.7,-0.2,-1.6,2.8', gains=('16', '16') if route[4] == '0' else ('0.9', '6'))
            check(o[-6:, -6:].min() >= 240, '%s: a light past white with a matrix of rows up to 7.3 at share 1.5 stays white (darkest %d)'
                  % (route[0], o[-6:, -6:].min()))
        # V1 HDR: the transfer's share 0 is the transfer off, 0.5 between; colour-only off runs
        dark = np.stack([0.010 + 0.004 * np.sin(x / 13.0), 0.008 + 0.0 * x, 0.006 + 0.004 * np.cos(y / 11.0)], -1)
        np.ascontiguousarray(np.where((x > 100)[..., None], 0.75, dark).astype(np.float32), '<f4').tofile(d / 'lin.f32')
        hdr1 = ('V1 HDR', '1', '0.9', '3.15', '1')
        off, full = run(hdr1, '0,24,0.2,0.2,0,1,0,0,0,16', ccm='1,0,0,0,1,0,0,0,1'), run(hdr1, base_post + ',1,1', ccm='1,0,0,0,1,0,0,0,1')
        zero, half = (run(hdr1, base_post + ',1,%s' % k, ccm='1,0,0,0,1,0,0,0,1') for k in ('0', '0.5'))
        d_full, d_half = np.abs(full - off).mean(), np.abs(half - off).mean()
        check(np.array_equal(zero, off) and 0 < d_half < d_full, 'V1 HDR: «Сила переноса цвета» 0 == off, 0.5 moves %.3f of the full %.3f' % (d_half, d_full))
        # colour-only off: the brightness is transferred too and counts in the motion rejection - at the default threshold this
        # scene's difference rejects the transfer (scale 0: the picture without it), at the row's largest it runs and differs
        wide = '0,24,0.2,0.2,0,1,0,0,1,64'
        strict, loose, colour = run(hdr1, base_post + ',0,1', ccm='1,0,0,0,1,0,0,0,1'), run(hdr1, wide + ',0,1', ccm='1,0,0,0,1,0,0,0,1'), \
            run(hdr1, wide + ',1,1', ccm='1,0,0,0,1,0,0,0,1')
        check(np.array_equal(strict, off) and not np.array_equal(loose, off) and not np.array_equal(loose, colour),
              'V1 HDR: «Переносить только цвет» off - rejected at threshold 16, transfers colour and brightness at 64 (%.3f from colour only)'
              % np.abs(loose - colour).mean())
        # the viewfinder's LUT with the curve: unshaped it is previewLut's, shaped it stays monotonic and moves the way the photo does
        for ver in ('1', '2'):
            def lut(*c):
                sh(java + [tone, 'lut', ver] + list(c) + [str(d / 'lut.bin')])
                return np.fromfile(d / 'lut.bin', np.uint8).reshape(1024, 4).astype(np.int32)
            plain = lut('-', '-')
            dip = np.diff(plain[:, :3], axis=0).min()   # the module's own LUT: -1 on V2 (its dither)
            for mid, con in ((-1, -1), (-1, 1), (1, -1), (1, 1)):
                l = lut(str(mid), str(con))
                check(np.diff(l[:, :3], axis=0).min() >= dip and l[0, :3].max() <= 2 and l[-1, :3].min() >= 250,
                      'LUT V%s: curve %+d / %+d monotonic as the module\'s own (step >= %d), black and white kept' % (ver, mid, con, dip))
            check(lut('1', '0')[200, 1] > plain[200, 1] > lut('-1', '0')[200, 1], 'LUT V%s: «Средние тона» moves the LUT as the photo' % ver)
    finally:
        shutil.rmtree(d, ignore_errors=True)
    if FAIL:
        print('%d FAILED' % len(FAIL))
        sys.exit(1)
    print('OK')


if __name__ == '__main__':
    main()
