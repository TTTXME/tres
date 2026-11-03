# Slide-film DIR profile correction

The owner's APK31204 log and JPEG show cyan/blue solarization with Kodachrome
64, DIR couplers at 100%, Super 35 and film exposure -1 EV. The same failure
was reproduced from the preserved window scene. It occurs in both whole and
staged native rendering, and disappears when DIR alone is disabled.

`FilmReferenceParameters.from` selected the slide film but retained the generic
negative-film DIR coefficients. The pinned profiles have smaller, stock-specific
coefficients for Ektachrome, Kodachrome, Velvia and Provia. The packet now supplies
those nine coefficients for each slide; negative-film packets are unchanged.
The user-facing coupler strength continues to scale the chosen profile normally.

Validation:

- The unchanged native engine with the corrected Kodachrome packet matches
  Unspektrawesome 1.2.7 exactly on the same real scene crop with all effects.
- Four slides pass complete 4096x3072 Android PostPipeline/JPEG rendering, twice
  each. Complete outputs repeat exactly; image inspection shows normal tonal
  progression without the reproduced cyan solarization.
- The packet regression test passes. A new CI check compares all 36 slide
  coefficients with the pinned generated native tables.

Evidence: `../evidence/why220-after-delivery-20261102/`, including the submitted
logs' source paths, isolation images, original-engine comparison and
`slides-full-post.json`. This is a local fix awaiting the combined processing
delivery; it is not present in the published APK31204.
