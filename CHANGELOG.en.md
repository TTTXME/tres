# SCAMERA changes

English copy of CHANGELOG.md (the Russian file is the one posted to Telegram), from 2026-11-09 on.

## 2026-11-12 — Full resolution by recipe

- Lens settings: a «Full resolution (recipe)» switch — 50 MP on the Xiaomi 17 Ultra's main camera with the usual viewfinder; waits for a check on the phone.
- nubia and OnePlus 16: trial presets of the same switch.

## 2026-11-12 — Black viewfinder

- Fixed the black viewfinder of the 11 November build (every mode but «Oldstyle»).

## 2026-11-11 — Watermark in the editor

- The gallery editor has a new «Mark» tab: add a watermark to any photo, change it or remove it. The same styles and settings as in the camera, shown on the photo at once; the model, focal length, aperture, shutter speed, ISO and date come from the photo's own EXIF, and what the EXIF lacks stays off the watermark (its row is greyed).
- Photos shot with the watermark in this version keep the pixels that were under it inside the file. «Remove» gives the photo back without it, losslessly; «Change» takes the old one off and draws a new one. For older and other apps' photos «Remove» is greyed and says why; adding a watermark works.
- A copy saved from the editor with a watermark keeps the pixels under it too, so it can be removed or changed again. Because of that, photos with a watermark are larger: 3–10 % for «Caption» and «Badge», 15–35 % for «Bar», 1.5–2 times for «Frame».

## 2026-11-11 — Oldstyle

- A new, third camera mode «Oldstyle», beside «Sport» and «Photo» in the bottom mode switch. The merge as in «Photo» (Sabre 1× / 1.5× / 2× too), with its own tone: «Oldstyle V1» the look of the old version 2.7, «Oldstyle V2» the look of version 3.1. The tone comes from the owner's tonemapping module (tone-port): the same curves, exposure blending and colour output as those versions. The Oldstyle tone works only in this mode.
- Oldstyle captures like the original versions: the whole burst at one exposure, no ultrashort and no long frames. Before the shot the mode meters the viewfinder's frame by the 2.7 / 3.1 rules (the HDR decision, the short and the long exposure) and takes the burst at the short exposure that keeps the light; the tone makes the long one from the merge digitally. A scene that is not HDR is taken at the normal exposure. A hand-set ISO / shutter is used as it is. The shot is taken after the press (not from the viewfinder's buffer); the shadows are a little noisier than in «Photo» with its long frames.
- The Oldstyle viewfinder shows the chosen version's tone (V1 / V2) — an approximation of the photo's — with the style on top in the photo's order; switching V1 / V2 shows at once. The style's «Exposure» now changes the exposure itself before the tone (as a real exposure), the other style parameters act after the tone.
- The Oldstyle tone gains the old versions' stages: haze and colour cast removal in the shadows, the low-frequency colour transfer in V1's HDR and the output saturation (neutral by default).
- In Oldstyle the manual controls (EV, ISO, shutter, white balance, focus) are always open under the viewfinder, with values of their own, apart from «Photo» and «Pro»; each still has «Auto».
- A round button on the right above them opens the style panel in their place: «Palette» — a large see-through pad over the viewfinder (left a cool grey, right a warm colour, up lighter, down darker; the dots under the finger grow, a «Base: Natural · Tone · Color» line on top); «Advanced» — exposure, aura, halo, grain, light warmth, hue shift, highlights, shadows, warm and cool boost, sharpness and vignette on a ruler with 0 in the middle (a double tap on the ruler: 0 again). Everything shows at once in the viewfinder and applies the same way to the photo. Reset asks «Restore the style to default?». A long press on a chip explains it.
- Main settings page: under «Tone settings», the group «Oldstyle Tone»: V1 / V2, the largest digital gain, V1's low-frequency colour, V2's saturation and levels, «Dehaze and tint» (on, darkest haze level, percentile, tint strength, tint by lens shading) and «Output saturation» (saturation, vibrance and their change per stop); the other version's rows are greyed. The brightness target and the exposure-ratio limits are gone: the 2.7 / 3.1 rules set them.

## 2026-11-11 — Watermark

- The watermark is redrawn in the app's style: the SCAM font, glass like the quick-settings panel, the camera icon with its yellow light. Four styles: «Caption» (text right on the photo), «Badge» (a glass pill), «Bar» (along the edge) and «Frame» (white or black over the photo's edges).
- Watermark settings: position (the corners and the centre, top or bottom), colour (auto by the photo, white, black), font, size, opacity, margin, and a preview on a sample photo — landscape and portrait, updated at once.
- What it shows — each field on its own, in any combination, even a single one: your text, the phone model, focal length, aperture, shutter speed, ISO, date and time (five formats), the icon. Fields that are off leave no empty lines or separators.
- By default the watermark no longer says «Shot on» — just «SCAMERA». If you kept the old text it changes by itself; your own text is kept.
- The watermark is drawn on the finished photo: always in the chosen corner and the right way up however the phone was held, the same size on portrait and landscape shots, sharp at 12, 50 and 200 MP, and no longer cut off by digital zoom.

## 2026-11-11 — Xiaomi 17 Ultra 50 MP

- Xiaomi 17 Ultra, 50 MP on the main camera (a module's full-size sensor mode): the viewfinder no longer goes black with «ISP stream» On or «Fake off» — Xiaomi's camera shoots this mode only without the ISP stream, so it always runs with the RAW viewfinder. The 50 MP frames come without their black pedestal, and the camera could not tell a Quad from a finished Bayer; it now tells from the frames themselves, and the photo is merged the way the frame is built.

## 2026-11-11 — Quad «Fast» without a grid on branches

- OPPO Find X8 Ultra and other Quad cameras, «Fast»: dark branches against a bright sky and the dark side of a sharp edge no longer get a fine grid or dotted rows — the 4 px lattice is taken off relative to each pixel's own level, not by the bright sky beside it. Branch detail is unchanged.

## 2026-11-11 — Tone settings

- Main settings page: right under the «Why tone» V1 / V2 switch, the group «Tone settings». It holds the tone mapping: on top the brightness, contrast and saturation sliders (moved from «Image settings»), then «Exposure (AE)», «Global tone mapping (AgX, ACES, curve)», «Local tone mapping», the highlight shoulder above white (from «HDR settings»), «Highlights in the tone» — V1's highlight softness and white point, V2's highlight colour recovery and the HDR short frame denoise — and V1's «Colour and detail» (from «Chromatic aberrations and highlights»). Those pages and rows left «Image settings»; values and the pages' order in «Layout» are kept.
- Rows of one version only are still greyed while the other is chosen and wake at once on the switch. «Image settings» keeps the style and film, the warmth, sharpness and noise reduction sliders, colour, portrait, sharpening, the HDR frames and highlight recovery.

## 2026-11-11 — Noise reduction and merge

- Main settings page: right under «Processing mode», the group «Noise reduction and merge». It holds every setting of the former «Noise reduction» and «Merge» pages (the denoise after the merge, colour speckle cleaning, the pyramid denoiser, burst frames, «Sport», «Static», alignment, motion rejection, the merge kernel, Quad / Tetra, final assembly). Those pages left «Image settings»; values, and the groups' order set in «Layout», are kept. The «Resolve noise reduction» slider stays in «Image settings».
- Rows the chosen mode does not use are greyed with the reason: in «Fast» and «Static», «Quality»'s alignment, motion rejection, merge kernel and final assembly (in «Fast» only Tetra 4×4 shots need them, in «Static» only shots with motion and Quad / Tetra, which the «Quality» merge takes); in «Fast» and «Quality», the «Static» rows. The denoise, burst frames, «Sport» and Quad / Tetra act in every mode. Switching the mode greys or wakes rows at once; the same in the shade and the lab.

## 2026-11-11 — nubia 50 MP

- nubia: 50 / 64 MP try two new ways, as the stock camera does — the viewfinder in the normal mode, the full size on the shot only; after a failed 50 MP the normal viewfinder no longer goes black.

## 2026-11-11 — «Static» merge

- «Processing mode»: a third option, «Static» — for still scenes on a tripod or a very steady hand. The photo is built from all the burst's frames at once: the frames are aligned to each other precisely and fine detail none of them holds alone is recovered from them. Slower than «Quality».
- «Static» also works with Quad (2×2), Tetra (4×4) and DCG sensors — it merges such bursts itself, following their colour layout, the response of the block's pixels and the two noise levels of DCG.
- If something in the frame moves, «Static» leaves the moving frames and areas out and still makes the photo itself — down to a single frame.
- «Static» no longer shoots the extra short and long frames: only the buffered burst frames it merges — the photo is saved sooner.
- «Static» on Quad and Tetra: no more colour checker on bright leaves and walls, dark dots and stairs on branch and window edges, colour fringes at dark edges or a grainy grid on flat walls — like the other merges, with the same or more detail.
- «Static» on regular (Bayer) sensors: sky and flat areas without colour noise — as in «Quality», no green-magenta checker on foliage or dotted zipper along vertical edges, with the same fine detail of twigs and railings.
- «Static» processes a photo 2–4 times faster, and its wait grows with the resolution — 50 and 200 MP photos no longer stop on a timer.
- «Static» on Quad and Tetra: the detail the previous fix took is back, without the colour checker coming back.
- «Static» on Quad and Tetra: object edges are clean again — no stripes along them and no stair-step teeth, as in «Fast» and «Quality»; less grain when the burst has few still frames (down to one).
- «Static» shows more detail than «Quality»: fine structure (brickwork, twigs, lettering) is crisper, with no halos around edges and no extra noise on flat areas; on regular sensors the detail matches the reference camera, and the dotted pattern in bright windows is gone.
- Every processing mode («Fast», «Quality», «Static») always makes the photo its own way and never hands it to another: not for motion, not for the frame size (50 / 200 MP), not for a GPU failure. «Fast» now merges Tetra too, and a frame too large for the GPU in parts. On a GPU failure «Static» redoes the shot on the other GPU API, then on the CPU; if a mode cannot make the photo at all, the base frame is saved and the log says so.
- «Merge» → «Static»: resolution 1×, 1.5× or 2× (super-resolution) and the number of iterations.

## 2026-11-11 — Why tone V2

- Main settings page: a «Why tone» switch — V1 (the tone as before, the default) or V2 (the new version: its own exposure, local contrast, curve and colour). Sharpening, denoise, LUT and «Colour space» work with both. The viewfinder still shows V1.
- V2's settings live in «Image settings» with V1's: where the versions have the same setting (brightness, contrast, exposure, AgX / ACES / Uchimura, the own curve and its modifiers, the local tone's layers, vibrance and skin, the highlight shoulder, short frame denoise) it is one row for both. Settings of one version only are marked «· V1» / «· V2» or with a note and greyed out while the other is chosen; flipping «Why tone» greys or wakes them at once. The separate «Why tone V2 settings» page is gone; values set there were moved.
- Why tone V1, «Fast»: the sun and other large clipped lights in daylight no longer have a grey ring round a white disc — the glow falls off smoothly from the core, as in V2; glare beside a coloured light has no salmon blotches, and a headlamp is its amber lens with ridges and highlights, not an orange blob, its white glare streak fading into the lens's colour instead of a hard white patch; light above white the short frames measured keeps its fine detail. In both modes the rims of bright areas (lamp and grille edges, a hood's bright edge) have no dashes or teeth, and a dark strip between two bright areas stays dark: a rear-window sticker above a white body is readable, a trim's dark line is not filled with light. Small mid-tone text (a sticker's digits, a number plate, lettering) and corrugated sheet ribs are crisper: smoothing along strong edges stays only where the bright side reaches white (a lamp's rim, a window frame against the sky).
- Wider ranges on eight shared sliders: «AE highlight protection threshold» up to 10 (with V2, 10 turns the protection off), «Highlight rescue depth» and «Shadow brightening» up to 5, «Contrast» 0.5–2, «Gamma value» 1–3, «Shadow depth (Min EV)» −15 to −2, «AgX exposure (EV)» ±3, «Deep shadow softness» up to 0.3. Defaults are unchanged.

## 2026-11-11 — Viewfinder and gallery

- The gallery no longer waits for the whole media library before it opens: the photo list is read in the background, and a second open without new photos reads nothing again.
- Text search on photos no longer slows the gallery: reading starts a few seconds later, waits while you scroll, while a shot is processed and while the phone is hot, and pauses between photos.
- A photo whose text reading was cut short is no longer stored as empty — it is read again.
- DNG and photos: no PhotonCamera leftovers in the shot data — the capture time with milliseconds and time zone, the lens and 35 mm focal length of the camera used, SCAMERA as the software. Photos and DNGs now carry an embedded preview, so the gallery shows them faster.
- Gallery editor: a new «LUT» tab — the camera's LUTs and film simulations (print, format, grain, exposure, contrast) on the finished photo, with previews on the cards; saved as a copy like the other edits.
- Gallery thumbnails load faster, most of all for 50 and 200 MP photos and RAWs: the grid never decodes a full photo, a RAW is read in place and only the rows it needs, thumbnails are prepared ahead of the scroll and no longer pushed out of the cache, and an opened photo shows its thumbnail at once while the full picture loads.
- Gallery: three views to choose from («⋮» → «View»): «Grid» as before, «List» — rows with the thumbnail, name, date, size and RAW / HEIC / 50 MP / SHDR tags, folders as rows with a cover and a count of new files, and «Timeline» — edge-to-edge tiles by month that pinch out to months and years; switching is animated and keeps your place.
- 200 MP photos in the gallery: thumbnails of the camera's JPEGs decode in strips on several cores, a 200 MP DNG no longer runs out of memory in the viewer and opens several times faster, Ultra HDR photos and zoom tiles no longer hold the whole file in memory.
- SGallery can be chosen as the default gallery: it opens photos and videos from other apps («Open with», «Always»), returns a picked photo or video to other apps, and shows a camera's shot over the lock screen on its own.
- Less memory over a long session: opening the viewer no longer leaves a histogram thread and GL context behind each time, text reading frees its memory when done, a closed camera stays in memory while its shots are processed and is released afterwards.
- Viewfinder while a photo is processed (Snapdragon): the processing hands the GPU to the viewfinder in small portions and the viewfinder's RAW frame is half-size meanwhile — no ~0.3 s freeze at the start of the denoise; the photo is the same.
- The shot is prepared faster, the photo is the same: on the X200 Pro the RAW crop no longer copies the burst (~0.4 s) and the merge starts on the already warmed-up worker, DCG shots check their RAW on all cores, the first shot after a start no longer waits for code to compile.

## 2026-11-11 — CA

- The chromatic aberration net («Neural» mode) retrained on bursts: it removes the violet fringe next to dimmer lights too and no longer turns the fringe on branches and wires yellow or teal.

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
