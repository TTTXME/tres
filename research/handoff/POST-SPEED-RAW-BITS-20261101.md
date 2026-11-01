# Exact half-bit noise fallback (P445, 2026-11-01)

P444's sampled histogram deliberately declines maps with zero or subnormal values:
the sampler and CPU readback can flush half subnormals differently. The new fallback
reads the same stored RGBA16F statistics as an RGBA16UI image, without modifying or
rerendering the statistic. This uses the common GLES3.1 typed-image API, not vendor
extensions or an application/sensor/HAL change. See the
[Khronos API reference](https://registry.khronos.org/OpenGL-Refpages/es3.1/html/glBindImageTexture.xhtml)
and [Android GLES31 API](https://developer.android.com/reference/android/opengl/GLES31).

The ordinary sampler remains first: maps it accepts keep their existing path.
Before the integer fallback is allowed, a once-per-process probe uploads all65536
half codes, reads both the integer image and GL_FLOAT conversion and checks every
finite value exactly (NaNs must remain NaN). Any probe error or mismatch retains
the CPU selection. The histogram filters the same positive non-NaN values and
selects the same `count/4`; a separate visited-pixel count validates coverage even
when the selected map has no valid values. Bound image, framebuffer and viewport
state is restored. Existing GPU override/legacy behavior remains.

## Evidence and limits

Full inputs, original settings, outputs, scripts and receipts remain local in
`evidence/gcam61-post-speed-20261101`; follow-up release receipts are in
`evidence/gcam61-post-raw-bits-20261101`.

- Adreno750 / OPPO PHY110: actual typed-image probe preserves all65536 half codes,
  including all1023 positive subnormals, and agrees with CPU GL_FLOAT readback.
- Final actual Android PostPipeline on all8 recent Штрихи bursts plus Moving8,
  original full12MP RGB/maps/settings:9/9 final Bitmap RGBA outputs byte-identical
  to the original baseline, two repeats each. Six use the original sampled GPU
  path, three use the new raw-bit fallback. Frame counts/resolution unchanged.
-11 actual Android GPU fixtures vs independent CPU sort; forced sampled-path
  checks retain all original accept/decline guards.24 host shader/sort fixtures
  plus the production all-half probe shader pass.
- Six injected Android failures: sampler histogram/selection with raw capability
  unavailable, raw histogram/selection, probe program failure, and a successful
  probe dispatch returning wrong bits. All return exactly the original CPU noise
  estimate, without GL errors. These simulate failures; they are not Mali proof.
- Balanced20-run stage AB, four warmups discarded: old P444 fallback vs new raw
  fallback28->8.5ms (063029) and28.5->8ms (Moving); unaffected normal control3->3ms.
  A diagnostic forces only the raw-probe cache off/on after its actual successful
  hardware verification; it is not a new persisted production setting.
- Separate nonsynchronized full-post medians: control643->643ms, window651->653ms
  (no full-post benefit claimed there), Moving913->896ms. All samples retained;
  capture, merge, input loading and JPEG encoding are outside the timer. This
  does not establish GCam6.1 latency equivalence or a universal speed percentage.
- No physical Mali was connected. The runtime probe, sampled GPU route and CPU
  route are retained for unsupported/nonexact drivers; no Mali performance or
  image-quality claim. All tone/sharpening shaders and protected Main1 unchanged.

Earlier diagnostic-only failures are preserved: an inline-layout parser failure,
then an incorrect probe upload type (float vs half), corrected before the actual
all-code proof. Neither was used as evidence of a driver defect or shipped.

Final Actions APK/SHA256SUMS/signature/runtime checks and official-APK device
replay/smoke are recorded outside Git after CI. A local source-test APK is not
the delivery artifact. P444's report remains the history of its earlier path.
