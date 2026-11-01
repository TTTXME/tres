#!/usr/bin/env bash
set -euo pipefail

g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_strip_trailers.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-strip-trailers
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-strip-trailers
