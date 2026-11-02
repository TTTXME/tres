uniform int portraitCountU;
uniform vec4 portraitFacesU[5];
uniform vec4 portraitAdjustU[5];

vec3 portraitSkin(vec3 rgb, vec2 uv) {
    float turn = 0.0;
    for (int i = 0; i < 5; i++) {
        if (i >= portraitCountU) break;
        vec4 box = portraitFacesU[i];
        if (portraitAdjustU[i].x == 0.0 || box.z <= box.x || box.w <= box.y) continue;
        vec2 p = (uv - 0.5 * (box.xy + box.zw)) / ((box.zw - box.xy) * vec2(0.46, 0.48));
        float candidate = portraitAdjustU[i].x * (1.0 - smoothstep(0.65, 1.0, dot(p, p)));
        if (abs(candidate) > abs(turn)) turn = candidate;
    }
    if (turn == 0.0 || any(lessThanEqual(rgb, vec3(0.0001))) || any(greaterThanEqual(rgb, vec3(0.98)))
            || any(isnan(rgb)) || any(isinf(rgb)) || dot(rgb, vec3(0.2126, 0.7152, 0.0722)) < 0.008) return rgb;
    vec3 lms = mat3(0.4122214708, 0.2119034982, 0.0883024619,
                    0.5363325363, 0.6806995451, 0.2817188376,
                    0.0514459929, 0.1073969566, 0.6299787005) * rgb;
    lms = pow(max(lms, vec3(0.0)), vec3(1.0 / 3.0));
    vec3 lab = mat3(0.2104542553, 1.9779984951, 0.0259040371,
                    0.7936177850, -2.4285922050, 0.7827717662,
                    -0.0040720468, 0.4505937099, -0.8086757660) * lms;
    float hue = degrees(atan(lab.z, lab.y));
    float ratio = length(lab.yz) / max(lab.x, 0.0001);
    float skin = smoothstep(-35.0, -15.0, hue) * (1.0 - smoothstep(95.0, 115.0, hue))
            * smoothstep(0.03, 0.05, ratio) * (1.0 - smoothstep(0.30, 0.40, ratio))
            * smoothstep(0.12, 0.20, lab.x);
    float need = turn < 0.0 ? min(radians(70.0 - hue), 0.0) : max(radians(25.0 - hue), 0.0);
    turn = sign(turn) * min(min(abs(turn), abs(need)), 0.1745329252) * skin;
    if (turn == 0.0) return rgb;
    float ct = cos(turn), st = sin(turn);
    lab.yz = mat2(ct, st, -st, ct) * lab.yz;
    vec3 root = mat3(1.0, 1.0, 1.0,
                    0.3963377774, -0.1055613458, -0.0894841775,
                    0.2158037573, -0.0638541728, -1.2914855480) * lab;
    root = root * root * root;
    vec3 corrected = mat3(4.0767416621, -1.2684380046, -0.0041960863,
                          -3.3077115913, 2.6097574011, -0.7034186147,
                          0.2309699292, -0.3413193965, 1.7076147010) * root;
    if (any(lessThanEqual(corrected, vec3(0.0)))) return rgb;
    const vec3 luma = vec3(0.2126, 0.7152, 0.0722);
    return corrected * (dot(rgb, luma) / max(dot(corrected, luma), 0.000001));
}
