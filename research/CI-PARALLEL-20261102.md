# CI latency (P457)

CI31202 (2be649c0), run41343105319, succeeded. Job duration3008s (50m08s). The serial Build debug APK step took2670s; Gradle reported9m53s. Independent host validation consumed the remaining approximately34m37s of that step. The exhaustive strip-trailer check occupied approximately584s of buffered log silence; its output confirms all seven full uint32 sweeps. Earlier CI31200 took approximately31min: host checks20min and Gradle6m42s. Runner/compiler variability affects the overhead.

Android compilation now runs alongside six host matrix lanes: shaders, three native/sanitizer groups, the full exhaustive trailer sweep and codecs/host transport. Each lane retains the exact original test commands, compiler flags, environments and arguments. JNI checks get the same NDK header; shader and codec lanes retain NDK glslc and Android36 for strict container/Java checks. No test-result caching, omitted patterns or relaxed tolerances.

Publish requires successful Android AND every host lane. Only publish restores pinned private assets and signs/posts the release. The internal APK template has a distinct name and one-day retention; it is not a release. Final artifact naming, certificate, SHA256SUMS and Telegram commands are unchanged.

Validation: original/relocated command multisets and the complete Gradle tasks/test-filter line match exactly. Workflow YAML, bash -n and actionlint1.7.12 pass. No application, native-processing or shader changes in this delivery.

Official CI31202 APK SHA256 f93e0fa16b0be65df8c49fb21e13b48fe892360faf5cbc32f07ea87c8caaca6f installed unchanged on OPPO. Two repeats each of full Android post/JPEG and film effects match the existing full-output hashes. Physical Nubia/12X/Mali evidence remains separate.

New Actions duration and final signed artifact verification must be recorded after the first parallel run. Approximately15min is a target, not a measured guarantee.

Reference: https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-jobs
