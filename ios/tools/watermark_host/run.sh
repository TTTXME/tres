#!/usr/bin/env bash
# The Android watermark's Java (processing/watermark: WatermarkSpec, WatermarkText, WatermarkPainter.plan, WatermarkArea, with the
# settings' PreferenceValue / PreferenceNumber / SettingsNumericRules) compiled and run in a plain JVM on the stubs beside this
# script, writing ios/Tests/WatermarkGolden.swift: the values ios/Tests/WatermarkTests.swift checks the Swift port against.
#   ios/tools/watermark_host/run.sh            regenerate the golden file
#   ios/tools/watermark_host/run.sh --check    fail when the golden file is not what the Java gives now
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
java_src="$root/app/src/main/java/com/particlesdevs/photoncamera"
out="${SCAM_WATERMARK_HOST_OUT:-$(mktemp -d)}"
mkdir -p "$out/classes"
javac -nowarn -encoding UTF-8 -d "$out/classes" \
    $(find "$here/stubs" -name '*.java') \
    "$java_src/settings/PreferenceValue.java" "$java_src/settings/PreferenceNumber.java" "$java_src/settings/SettingsNumericRules.java" \
    "$java_src/processing/watermark/WatermarkSpec.java" "$java_src/processing/watermark/WatermarkText.java" \
    "$java_src/processing/watermark/WatermarkPainter.java" "$java_src/processing/watermark/WatermarkArea.java" \
    "$here/Golden.java"
golden="$root/ios/Tests/WatermarkGolden.swift"
java -Duser.language=en -Duser.country=US -cp "$out/classes" com.particlesdevs.photoncamera.processing.watermark.Golden > "$out/WatermarkGolden.swift"
if [ "${1:-}" = "--check" ]; then
    diff -u "$golden" "$out/WatermarkGolden.swift" && echo "WatermarkGolden.swift matches the Android Java"
else
    cp "$out/WatermarkGolden.swift" "$golden"
    echo "wrote $golden ($(wc -l < "$golden") lines)"
fi
