# Vendored native sources

Copied without changes, only the files the library build needs (no tests, tools, examples, contrib).

| Directory | Source | Commit | Licence |
|---|---|---|---|
| `jpegli/lib/jpegli`, `jpegli/lib/base` | https://github.com/google/jpegli | `031a0077f5799a6041004267fc12b956c1f52a20` (2026-06-01) | BSD-3-Clause (`jpegli/LICENSE`, patent grant `jpegli/PATENTS`) |
| `jpegli/include/jpeglib.h`, `jmorecfg.h` | https://github.com/libjpeg-turbo/libjpeg-turbo (jpegli's submodule) | `8ecba3647edb6dd940463fedf38ca33a8e2a73d1` | IJG / BSD (`jpegli/include/LICENSE-libjpeg-turbo.md`, `README.ijg`) |
| `jpegli/include/jconfig.h` | generated from libjpeg-turbo `jconfig.h.in` as jpegli's CMake does (JPEG_LIB_VERSION 62, 8-bit, MEM_SRCDST) | | as above |
| `highway/hwy` | https://github.com/google/highway (jpegli's submodule) | `271a9a0ed9de1232d9117f1572c3fe28f8542ec1` | Apache-2.0 / BSD-3-Clause (`highway/LICENSE`) |

Used by `scamera-jpeg.cpp` (library `scameraJpeg`): every saved JPEG (plain, Ultra HDR base and gain map) is encoded with
jpegli, 4:4:4. Measured on the OPPO PHY110 (12.6 MP SCAMERA shot, arm64): q98 4:4:4 4.55 MB in 160 ms (the old
Bitmap.compress q98 4:2:0 file of the same shot: 4.56 MB), q95 4:4:4 2.66 MB in 147 ms.

## Fetched at build time

Not vendored (libaom alone is ~6.6 MB of source): `../scamera-avif-deps.cmake` downloads the pinned release archives
once into `app/build/native-deps` (override: `-DSCAMERA_DEPS_CACHE=`), checks their SHA-256 and builds them with the
app's NDK toolchain. Needs Perl (Git for Windows ships one) for libaom's generated headers; the host build of
`tools/check_avif.py` also needs nasm on x86.

| Library | Version | Archive | SHA-256 | Licence |
|---|---|---|---|---|
| libavif | 1.4.2 (2026-05-26) | https://github.com/AOMediaCodec/libavif/archive/refs/tags/v1.4.2.tar.gz | `2b645287340ba5a631d268b551dc2d72bd73ac33335962dd36dcdb6d8366921d` | BSD-2-Clause (libyuv subset BSD-3-Clause) |
| libaom | 3.15.1 (2026-09-21) | https://storage.googleapis.com/aom-releases/libaom-3.15.1.tar.gz | `8ca0c52746174603500f0adb6f2a215d69c9ca2aab2acb3caa06fb791d8d01bf` | BSD-2-Clause + AOMedia Patent License 1.0 |

Used by `scamera-avif.cpp` (library `scameraAvif`) through `scamera-avif-core.h`: the AVIF photo format, AV1 still
image (`AOM_USAGE_ALL_INTRA`), encoder only (Android decodes AVIF itself from Android 12), 8 / 10 / 12-bit, 4:4:4 /
4:2:0, lossless. arm64 code paths: NEON, dot product, i8mm, SVE / SVE2, chosen at run time.

## LibRaw (P352)

| Directory | Source | Version | Licence |
|---|---|---|---|
| `libraw/libraw`, `libraw/internal`, `libraw/src` | https://github.com/LibRaw/LibRaw (release archive `LibRaw-0.21.3.tar.gz`, SHA-256 `dc3d8b54e333d9d5441336049db255d14b27f19bd326a306cf5aea866806780a`) | 0.21.3 (2024-09-18) | LGPL-2.1 or CDDL-1.0 (`libraw/LICENSE.LGPL`, `libraw/LICENSE.CDDL`, `libraw/COPYRIGHT`) |

Copied without changes; left out: samples, bin, doc, RawSpeed / RawSpeed3, GoPro, DNG SDK glue builds (the glue sources
compile to nothing without USE_RAWSPEED / USE_DNGSDK), the `*_ph.cpp` placeholders (not built) and `src/Makefile`. Built as
`scameraLibRaw` (static, thread-safe: no LIBRAW_NOTHREADS; USE_ZLIB for deflate DNG; no JPEG, Jasper, OpenMP) and linked
into `scameraRaw` (`../scamera-raw.cpp`, the JNI wrapper the gallery calls through `gallery/raw/LibRawBridge`).

## tone-port (P630b)

| Directory | Source | Licence |
|---|---|---|
| `tone_port` | tone-port by the SCAMERA owner (`tone-port-27-31.zip`, 2026-11-11: «Используй как основу для Oldstyle») | the project's licence (the owner's own code) |

The owner's C++17 tonemapping module of the 2.7 and 3.1 looks, copied whole (sources, numeric tables `src/*.inc`,
`include/tone_port.h`, its CMake, README and regression tests), unchanged but for one hook (P630e, `tone_port.p630e.diff`
beside it: an overload of `tone_rgb8_hdr_luts` that calls a function on the bright virtual exposure between `make_exposures`
and `tone_pair` - the supplement's insertion point of the low-frequency colour; the original overload calls it with none, so
every result of the module is as before: its regression and the specification's numbers hold). Built by its own CMakeLists (`tone_port`,
static, `-ffp-contract=off`, FP32) and linked into `scameraTone` (`../oldstyle-tone-jni.cpp`): the «Oldstyle» camera
mode's tone (postpipeline/OldstyleTone). `tools/check_tone_port.py` builds it on the host and runs its regression
(both profiles, `--full-frame`) and the app's JNI path against it.

## tone-port supplement (P630e)

| Directory | Source | Licence |
|---|---|---|
| `tone_supplement` | the SCAMERA owner's supplement to tone-port (`hdr-ae-color-27-31.md`, 2026-11-12: «Я тебе скинул то, чего тебе не хватало, доделай») | the project's licence (the owner's own code) |

`tone_supplement.cpp` is the document's C++17 block copied unchanged (the HDR plans of 2.7 and 3.1, the TET cap, the fusion
gains, WB / CCM, the YUV conversions and output saturation, dehaze / tint, the low-frequency colour); the document itself is kept
beside it. Compiled into `scameraTone` through `../oldstyle-tone-jni.cpp` (-ffp-contract=off, FP32). `tools/check_tone_port.py`
checks its plans against the document's 45 tables and its YUV conversions against its hashes. The app uses its rules through
`processing/oldstyle/OldstyleAe` (a Java port in FP32, held to the same tables and to the C++ by the host check) and its
YUV-route stages on our RAW route in the JNI (dehaze / tint and output saturation on the module's RGB8, the low-frequency colour
inside the 2.7 HDR, our merge as its colour reference).
