// «Oldstyle» for a host tool (tools/post_node_check, tools/shot_run_check): the owner's tone-port module's sources and
// ToneNative.cpp in the tool's one unit, as the app builds them (needs -I app/src/main/cpp -I
// app/src/main/cpp/third_party/tone_port/include, -ffp-contract=off). Two sources each have a mix() of their own in an anonymous
// namespace: the second is renamed for this unit. Not for the app (project.yml compiles the sources one by one).
#pragma once

#include "third_party/tone_port/src/curves.cpp"
#include "third_party/tone_port/src/processing.cpp"
#include "third_party/tone_port/src/rgb8_hdr.cpp"
#define mix tone_unity_linear_hdr31_mix
#include "third_party/tone_port/src/linear_hdr31.cpp"
#undef mix
#include "third_party/tone_port/src/raw_tone.cpp"
#include "third_party/tone_port/src/raw_finish31.cpp"
#include "third_party/tone_port/src/parallel.cpp"
#include "ToneNative.cpp"
