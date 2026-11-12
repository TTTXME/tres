// P643d «ScamMaster»: the viewfinder's preview of the tone - the base the style (import_oldstyle_adjust) is applied on. The module
// (a whole-frame CPU pass, no live use) draws the photo of the viewfinder's frame as a thumbnail; OldstyleLive fits a bilateral
// grid from the ISP frame to that picture - per cell of 16 x 12 over the frame and per 8 bins of the pixel's luma an affine 3 x 4
// colour transform - and here it is applied to the full-resolution frame trilinearly: the cell bilinearly by the pixel's place in
// the texture (the coordinates LiveSceneMeter's tile was sampled at), the two bins round the pixel's luma. The module's local
// tone and colour, smooth in space and in luma; the ISP frame's detail. P630e .. P643b drew a tone of their own here.
uniform int uOtOn;               // 0 off, 1 on
uniform highp sampler2D uOtLut;  // the grid: 16 x (3 rows x 8 bins x 12) RGBA16F, texel (gx, (row x 8 + bin) x 12 + gy) = the row's r, g, b, offset
uniform highp float uOtLevel;    // the frame's light as the grid's fit saw it, over the frame's now (linear; 1: as it is - OldstyleLive)

const highp float OT_GX = 16.0, OT_GY = 12.0, OT_GL = 8.0;

highp vec4 otRow(highp vec2 cell, highp float bin, highp float row) {
    highp float y = cell.y + (row * OT_GL + bin) * OT_GY;
    return texture(uOtLut, vec2(cell.x / OT_GX, y / (OT_GY * OT_GL * 3.0)));
}

highp vec3 otNode(highp vec2 cell, highp float bin, highp vec4 c) {
    return vec3(dot(otRow(cell, bin, 0.0), c), dot(otRow(cell, bin, 1.0), c), dot(otRow(cell, bin, 2.0), c));
}

vec3 oldstyleToneLive(highp vec2 uv, vec3 colour) {
    highp vec3 c = clamp(colour, 0.0, 1.0);
    if (uOtLevel != 1.0) c = pow(clamp(pow(c, vec3(2.2)) * uOtLevel, 0.0, 1.0), vec3(1.0 / 2.2));
    highp float l = clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0) * (OT_GL - 1.0);
    highp float b0 = min(floor(l), OT_GL - 2.0);
    // the cell's texel centres: bilinear by the texture's filter, held at the grid's edge
    highp vec2 cell = clamp(uv * vec2(OT_GX, OT_GY), vec2(0.5), vec2(OT_GX, OT_GY) - 0.5);
    highp vec4 x = vec4(c, 1.0);
    return clamp(mix(otNode(cell, b0, x), otNode(cell, b0 + 1.0, x), l - b0), 0.0, 1.0);
}
