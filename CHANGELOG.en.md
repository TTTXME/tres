# SCAMERA changes

English copy of CHANGELOG.md (the Russian file is the one posted to Telegram), from 2026-11-09 on.

## 2026-11-11 — «Quality» on MediaTek

- MediaTek (Mali): «Quality» works again — a merge shader did not compile on Mali after the previous update.
- «Quality»: if the GPU faults during the merge, the shot is merged again or from one frame instead of being saved with repeated stripes.

## 2026-11-10 — nubia, OnePlus 16, night lights

- nubia: 50 MP on the 0.5x no longer shows a black viewfinder — the camera asks for the full sensor mode and checks that the HAL switched it on; 64 MP on the tele.
- OnePlus 16: the main camera tries more 50 MP modes with the ISP stream (as the stock camera); if they fail too, 50 MP is shot from the 200 MP mode, 2×2 binned per Tetra cell.
- RAW viewfinder while a photo is processed: up to 30 fps on the OnePlus 16 (was 15), up to 15 instead of 9-10 on the others.
- Night lights and signs: no dark specks, holes or dashed outlines inside the clipped core, in both «Fast» and «Quality».
- 50 and 200 MP: faster processing with less memory, the same picture.

## 2026-11-10 — Tetra in motion, lens settings in the shade, OnePlus 16, nubia

- Tetra: fewer beads and dots on and behind moving people, static areas unchanged.
- Lens settings can be pinned to the shade: they work only on their lens and are grey on the others.
- OnePlus 16: new 50 MP variants on the main camera; DAG no longer sticks on an unfit mode.
- nubia: 50 MP (remosaic) on every camera when the HAL gives it.

## 2026-11-10 — Descriptions on long press, white dots, Quad and Tetra

- Settings: only the value under a parameter, the description on a long press; texts rewritten to be clearer.
- No white dots on the edges of dark objects against a bright background, no bands in a clean sky.
- More local contrast in the photo.
- Quad: more detail on facades and textures; in «Fast» faces behind walking people stay visible.
- Tetra: thin lines without beads, the viewfinder without stutter.
- Quad / Tetra: the white balance no longer turns magenta when the camera is held still.
- «All denoisers» off now really leaves only the frame merge.
- OnePlus 16: 50 MP on the main camera through mode 25, DCG / DAG checked by the real bit depth.
- nubia: 50 MP tries the ways without remosaic; if none works, one honest notice.
- A shot with diagnostics and the debug log is processed faster.

## 2026-11-09 — Fast mode without stripes

- «Fast»: thin coloured stripes on moving subjects removed.

## 2026-11-09 — The interface as the concept, OnePlus 16

- Camera, settings and gallery as the concept: icons, Roboto Flex font, sizes.
- The phone's display size and font size no longer change the interface.
- OnePlus 16: FULLRES 50 and 200 MP, ISZ without remosaic, DCG and DAG.
- nubia: FULLRES without root through the maximum resolution.
- Merge: texture and bokeh kernel footprints and the transition between them.
- Flash: Off / Auto / On / Torch; a new lens switch animation.

## 2026-11-09 — New interface, FULLRES on nubia

- New camera, shade, settings and gallery interface.
- New sliders, switches, menus, number editor and animated icons.
- Swipe down from the top to edit the top row.
- nubia: FULLRES 50 MP and a 2× module without the phone's remosaic.
- Tetra: the viewfinder no longer stutters.
- Quad «Fast»: less grid in dark and blurred areas.
- Digital zoom: Sabre super-resolution instead of an upscale.
- Up to 65 frames in a merge.
- Colour: exact manual temperature (K), correct colour with an incomplete camera calibration, no face tint on black-and-white.
