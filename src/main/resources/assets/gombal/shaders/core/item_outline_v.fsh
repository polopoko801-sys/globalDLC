#version 330

// Pass 2: vertical combine -> exact euclidean distance to the hand mask, then the Rockstar outline colouring
// (flat colour, or the rainbow from outline.frag: hue from gl_FragCoord * rainbowStrength + speed, saturation set).
uniform sampler2D HSampler;

layout(std140) uniform OutlineConfig {
    vec4 Color;
    vec4 Params;
    vec4 Params2;
    vec4 Plasma;   // x = plasma fill on, y = time, z = scale, w = intensity
    vec4 PlasmaCol2;
    vec4 PlasmaCol3;
    vec4 Mist;     // x = mist on, y = strength, z = falloff, w = sample step in px
};

in vec2 texCoord;
out vec4 fragColor;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

vec3 outlineColor() {
    if (Params.y < 0.5) return Color.rgb;
    vec2 coords = gl_FragCoord.xy * vec2(Params2.w);
    vec3 rainbowColor = clamp(abs(fract(vec3(mod((coords.x + coords.y) + Params.z, 1.0)) + vec3(1.0, 0.6666667, 0.3333333)) * 6.0 - vec3(3.0)) - vec3(1.0), 0.0, 1.0);
    vec3 hsv = rgb2hsv(rainbowColor);
    hsv.y = Params.w;
    return hsv2rgb(hsv);
}

// ---- plasma fill (Legacy style). Deliberately NOT the sky's domain-warped fbm: a classic interference plasma
// (sum of sines around two orbiting centres) cut into glossy ribbons, so the item reads as liquid neon.
vec3 plasmaPalette(float t) {
    t = clamp(t, 0.0, 1.0);
    return t < 0.5 ? mix(Color.rgb, PlasmaCol2.rgb, t * 2.0) : mix(PlasmaCol2.rgb, PlasmaCol3.rgb, t * 2.0 - 1.0);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x), mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

vec3 finish(vec3 col) {
    col *= Plasma.w;
    return col / (1.0 + col * 0.35);
}

// square pixels whatever the window shape: scale by height only
vec2 plasmaUv() {
    return gl_FragCoord.xy * Params2.y * Plasma.z * 14.0;
}

// 2: Liquid - slow domain-warped fbm, smooth like oil on water
vec3 liquid(float t) {
    vec2 uv = plasmaUv() * 0.35;
    vec2 q = vec2(fbm(uv + vec2(0.0, t * 0.25)), fbm(uv + vec2(5.2, -t * 0.21)));
    float v = fbm(uv + q * 2.2 + vec2(t * 0.12, 0.0));
    vec3 col = plasmaPalette(v * 1.25 - 0.1) * (0.55 + 0.6 * v);
    col += vec3(1.0) * pow(smoothstep(0.55, 0.9, q.x * v * 1.8), 3.0) * 0.5;
    return finish(col);
}

// 3: Electric - dark body, branching bright veins (ridged noise) that crawl and flicker
vec3 electric(float t) {
    vec2 uv = plasmaUv() * 0.3;
    float r1 = 1.0 - abs(fbm(uv + vec2(t * 0.4, -t * 0.3)) * 2.0 - 1.0);
    float r2 = 1.0 - abs(fbm(uv * 1.9 - vec2(t * 0.55, t * 0.2) + 3.3) * 2.0 - 1.0);
    float vein = pow(r1, 14.0) + pow(r2, 18.0) * 0.7;
    float flicker = 0.8 + 0.2 * sin(t * 23.0 + uv.x * 3.0);
    vec3 col = plasmaPalette(0.15) * 0.18 + plasmaPalette(0.6 + 0.4 * r2) * vein * 1.4 * flicker;
    col += vec3(1.0) * pow(vein, 2.0) * 0.6;
    return finish(col);
}

// 4: Galaxy - deep space: a swirling nebula and twinkling stars
vec3 galaxy(float t) {
    vec2 uv = plasmaUv() * 0.25;
    float a = t * 0.08;
    mat2 rot = mat2(cos(a), -sin(a), sin(a), cos(a));
    float neb = fbm(rot * uv + fbm(uv * 1.5 + t * 0.05) * 1.5);
    vec3 col = vec3(0.02, 0.01, 0.05) + plasmaPalette(neb) * pow(neb, 2.2) * 1.6;
    vec2 sg = gl_FragCoord.xy * 0.5;
    float h = hash12(floor(sg));
    float star = step(0.985, h) * (0.5 + 0.5 * sin(t * 4.0 + h * 90.0));
    col += vec3(1.0) * star;
    return finish(col);
}

// 5: Cells - voronoi plates, each its own colour, with glowing seams between them
vec3 cells(float t) {
    vec2 uv = plasmaUv() * 0.45;
    vec2 n = floor(uv), f = fract(uv);
    float f1 = 8.0, f2 = 8.0;
    float id = 0.0;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(i, j);
            float hh = hash12(n + g);
            vec2 o = 0.5 + 0.45 * sin(t * 0.8 + 6.2831 * vec2(hh, hash12(n + g + 7.1)));
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < f1) { f2 = f1; f1 = d; id = hh; } else if (d < f2) f2 = d;
        }
    float seam = 1.0 - smoothstep(0.0, 0.12, sqrt(f2) - sqrt(f1));
    vec3 col = plasmaPalette(id) * (0.45 + 0.35 * (1.0 - sqrt(f1)));
    col += mix(PlasmaCol3.rgb, vec3(1.0), 0.5) * seam * 1.2;
    return finish(col);
}

// 6: Waves - diagonal bands flowing across, with a bright scan line sweeping through
vec3 waves(float t) {
    vec2 uv = plasmaUv();
    float v = sin((uv.x + uv.y) * 0.45 - t * 2.0 + sin(uv.y * 0.3 + t) * 1.5);
    vec3 col = plasmaPalette(0.5 + 0.5 * v) * (0.6 + 0.4 * v);
    float scan = fract((uv.x - uv.y) * 0.03 - t * 0.35);
    col += vec3(1.0) * pow(1.0 - abs(scan - 0.5) * 2.0, 24.0) * 0.8;
    return finish(col);
}

// 7: Fire - flames rising up the item, palette from dark (Color) to hot (Col3)
vec3 fire(float t) {
    vec2 uv = plasmaUv() * 0.35;
    vec2 p = uv + vec2(0.0, -t * 1.6);
    float n = fbm(p + fbm(p * 1.3 + vec2(0.0, -t)) * 0.8);
    float heat = clamp(n * 1.5 - 0.25 + 0.25 * sin(uv.x * 0.7 + t), 0.0, 1.0);
    vec3 col = plasmaPalette(heat) * (0.25 + 1.1 * heat);
    col += vec3(1.0, 0.95, 0.8) * pow(heat, 6.0) * 0.8;
    return finish(col);
}

// 8: Aurora - soft vertical curtains swaying, like northern lights
vec3 aurora(float t) {
    vec2 uv = plasmaUv() * 0.3;
    float curtain = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float x = uv.x * (0.6 + fi * 0.25) + fbm(vec2(uv.y * 0.3 + fi * 4.0, t * 0.2 + fi)) * 3.0 + t * (0.15 + fi * 0.05);
        curtain += pow(0.5 + 0.5 * sin(x * 2.0), 6.0) * (1.0 - fi * 0.25);
    }
    float shimmer = 0.75 + 0.25 * vnoise(vec2(uv.x * 4.0, t * 2.0));
    vec3 col = vec3(0.01, 0.02, 0.04) + plasmaPalette(fract(uv.y * 0.08 + t * 0.05)) * curtain * shimmer * 0.9;
    return finish(col);
}

// 9: Lava - a dark crust broken into plates, magma glowing in the cracks and slowly pulsing
vec3 lava(float t) {
    vec2 uv = plasmaUv() * 0.4;
    vec2 n = floor(uv), f = fract(uv);
    float f1 = 8.0, f2 = 8.0;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(i, j);
            vec2 o = 0.5 + 0.35 * sin(t * 0.3 + 6.2831 * vec2(hash12(n + g), hash12(n + g + 3.7)));
            float d = length(g + o - f);
            if (d < f1) { f2 = f1; f1 = d; } else if (d < f2) f2 = d;
        }
    float crack = 1.0 - smoothstep(0.0, 0.18, f2 - f1);
    float heat = crack * (0.7 + 0.3 * sin(t * 2.0 + fbm(uv * 2.0) * 6.0));
    vec3 crust = plasmaPalette(0.05) * 0.12 * (0.6 + 0.8 * fbm(uv * 3.0));
    vec3 col = crust + plasmaPalette(0.4 + 0.6 * heat) * heat * 1.6 + vec3(1.0, 0.9, 0.6) * pow(heat, 5.0) * 0.6;
    return finish(col);
}

// 10: Frost - ice crystals: sharp facets catching a cold sheen that slides across
vec3 frost(float t) {
    vec2 uv = plasmaUv() * 0.5;
    float a = abs(fract(fbm(uv * 1.3) * 4.0) - 0.5) * 2.0;
    float facet = pow(a, 3.0);
    float sheen = pow(0.5 + 0.5 * sin((uv.x - uv.y) * 0.6 - t * 1.4), 12.0);
    float sparkle = step(0.992, hash12(floor(gl_FragCoord.xy * 0.5) + floor(t * 6.0)));
    vec3 col = mix(plasmaPalette(0.2), plasmaPalette(0.8), facet) * (0.4 + 0.5 * facet);
    col += vec3(0.9, 0.97, 1.0) * (sheen * 0.7 + sparkle);
    return finish(col);
}

// 11: Rainbow - every hue flowing across in soft diagonal bands
vec3 rainbowFill(float t) {
    vec2 uv = plasmaUv() * 0.05;
    float h = fract(uv.x + uv.y * 0.6 + t * 0.15 + fbm(uv * 6.0) * 0.25);
    return finish(hsv2rgb(vec3(h, 0.75, 1.0)) * 0.85);
}

// 12: Hex - a honeycomb grid whose cells light up in waves
vec3 hexGrid(float t) {
    vec2 uv = plasmaUv() * 0.35;
    vec2 r = vec2(1.0, 1.7320508);
    vec2 h = r * 0.5;
    vec2 a = mod(uv, r) - h;
    vec2 b = mod(uv - h, r) - h;
    vec2 gv = dot(a, a) < dot(b, b) ? a : b;
    vec2 id = uv - gv;
    float edge = 0.5 - max(abs(gv.x) * 0.866 + abs(gv.y) * 0.5, abs(gv.y));
    float line = 1.0 - smoothstep(0.0, 0.05, edge);
    float wave = 0.5 + 0.5 * sin(length(id) * 0.6 - t * 2.5 + hash12(floor(id * 10.0)) * 2.0);
    vec3 col = plasmaPalette(wave) * (0.15 + 0.55 * pow(wave, 3.0)) + mix(PlasmaCol3.rgb, vec3(1.0), 0.4) * line * 0.9;
    return finish(col);
}

// 13: Storm - churning dark clouds lit by lightning flashes from inside
vec3 storm(float t) {
    vec2 uv = plasmaUv() * 0.3;
    float c = fbm(uv + fbm(uv + t * 0.3) * 1.8 - vec2(t * 0.2, 0.0));
    float flash = pow(max(0.0, sin(t * 1.7) * sin(t * 4.3 + 1.0)), 12.0);
    float bolt = pow(1.0 - abs(fbm(uv * 2.2 + vec2(0.0, t * 3.0)) * 2.0 - 1.0), 20.0) * flash;
    vec3 col = plasmaPalette(c * 0.6) * (0.15 + 0.6 * c * c) + vec3(1.0) * (bolt * 1.5 + flash * c * 0.4);
    return finish(col);
}

// 14: Vortex - everything spiralling into a slowly turning whirlpool
vec3 vortex(float t) {
    vec2 uv = gl_FragCoord.xy * Params2.y * Plasma.z;
    vec2 c = uv - vec2(0.5 * Params2.y / Params2.x * Plasma.z, 0.5 * Plasma.z);
    float r = length(c) * 14.0;
    float ang = atan(c.y, c.x);
    float s = sin(ang * 3.0 + r * 1.2 - t * 2.0 + fbm(vec2(ang * 2.0, r * 0.3)) * 2.0);
    vec3 col = plasmaPalette(0.5 + 0.5 * s) * (0.35 + 0.65 * (0.5 + 0.5 * s));
    col += vec3(1.0) * pow(0.5 + 0.5 * s, 16.0) * 0.6;
    return finish(col);
}

// 15: Glitter - a dark sheen covered in tiny twinkling sparks
vec3 glitter(float t) {
    vec2 g = floor(gl_FragCoord.xy * 0.6);
    float h = hash12(g);
    float tw = pow(0.5 + 0.5 * sin(t * 5.0 + h * 60.0), 20.0) * step(0.9, h);
    vec2 uv = plasmaUv() * 0.2;
    vec3 col = plasmaPalette(fbm(uv + t * 0.1)) * 0.35 + plasmaPalette(h) * tw * 1.8 + vec3(1.0) * tw * 0.8;
    return finish(col);
}

// 16: Toxic - bubbling acid: blobs rising and popping, bright rims
vec3 toxic(float t) {
    vec2 uv = plasmaUv() * 0.45 + vec2(0.0, -t * 0.6);
    vec2 n = floor(uv), f = fract(uv);
    float f1 = 8.0;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(i, j);
            float hh = hash12(n + g);
            vec2 o = 0.5 + 0.35 * sin(t * (0.6 + hh) + 6.2831 * vec2(hh, hash12(n + g + 1.3)));
            f1 = min(f1, length(g + o - f) / (0.6 + 0.4 * hh));
        }
    float blob = smoothstep(0.55, 0.35, f1);
    float rim = smoothstep(0.08, 0.0, abs(f1 - 0.45));
    vec3 col = plasmaPalette(0.25) * 0.25 + plasmaPalette(0.6) * blob * 0.8 + mix(PlasmaCol3.rgb, vec3(1.0), 0.5) * rim * 1.1;
    return finish(col);
}

// 17: Ocean - deep water with dancing caustic light on it
vec3 ocean(float t) {
    vec2 uv = plasmaUv() * 0.35;
    float c = 0.0;
    vec2 p = uv;
    for (int i = 0; i < 3; i++) {
        p += vec2(sin(p.y * 1.3 + t * 0.7), cos(p.x * 1.1 - t * 0.6)) * 0.6;
        c += 1.0 / max(0.06, abs(sin(p.x + p.y) * 2.0));
    }
    c = clamp(c / 18.0, 0.0, 1.0);
    vec3 col = plasmaPalette(0.15) * 0.35 + plasmaPalette(0.55) * fbm(uv + t * 0.1) * 0.5 + mix(PlasmaCol3.rgb, vec3(1.0), 0.5) * pow(c, 2.0);
    return finish(col);
}

// 18: Crystal - prismatic facets that shift hue as the light moves
vec3 crystal(float t) {
    vec2 uv = plasmaUv() * 0.4;
    vec2 n = floor(uv), f = fract(uv);
    float f1 = 8.0, id = 0.0;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(i, j);
            float hh = hash12(n + g);
            float d = length(g + 0.5 + 0.4 * sin(6.2831 * vec2(hh, hash12(n + g + 4.4))) - f);
            if (d < f1) { f1 = d; id = hh; }
        }
    vec3 col = hsv2rgb(vec3(fract(id + t * 0.08 + uv.x * 0.02), 0.55, 1.0)) * (0.35 + 0.5 * id);
    col += vec3(1.0) * pow(0.5 + 0.5 * sin(id * 40.0 + t * 2.0), 18.0) * 0.7;
    return finish(col);
}

// 19: Ink - dark ink blooming and curling through clear liquid
vec3 ink(float t) {
    vec2 uv = plasmaUv() * 0.25;
    vec2 q = vec2(fbm(uv + t * 0.06), fbm(uv + vec2(4.1, 1.3) - t * 0.05));
    float v = fbm(uv + q * 3.0);
    float edge = smoothstep(0.42, 0.5, v) - smoothstep(0.5, 0.62, v);
    vec3 col = mix(plasmaPalette(0.9) * 0.9, plasmaPalette(0.05) * 0.12, smoothstep(0.4, 0.6, v)) + PlasmaCol3.rgb * edge * 0.8;
    return finish(col);
}

// 20: Pulse - rings expanding from the middle of the item, one after another
vec3 pulse(float t) {
    vec2 uv = gl_FragCoord.xy * Params2.y * Plasma.z * 14.0;
    vec2 c = vec2(0.5 * Params2.y / Params2.x, 0.5) * Plasma.z * 14.0;
    float d = length(uv - c);
    float w = fract(d * 0.12 - t * 0.6);
    vec3 col = plasmaPalette(fract(d * 0.02 + t * 0.1)) * (0.25 + 0.5 * pow(1.0 - w, 3.0));
    col += vec3(1.0) * pow(1.0 - abs(w - 0.05) * 6.0, 6.0) * 0.6 * step(abs(w - 0.05), 0.166);
    return finish(col);
}

// 21: Circuit - glowing traces with pulses running along them
vec3 circuit(float t) {
    vec2 uv = plasmaUv() * 0.6;
    vec2 cell = floor(uv), f = fract(uv);
    float h = hash12(cell);
    float line = h > 0.5 ? smoothstep(0.08, 0.0, abs(f.x - 0.5)) : smoothstep(0.08, 0.0, abs(f.y - 0.5));
    float node = smoothstep(0.18, 0.1, length(f - 0.5)) * step(0.8, hash12(cell + 3.0));
    float run = pow(fract((h > 0.5 ? f.y : f.x) + h * 7.0 - t * 1.2), 8.0);
    vec3 col = plasmaPalette(0.1) * 0.15 + plasmaPalette(0.6) * (line * (0.35 + 1.2 * run) + node * 1.2);
    return finish(col);
}

// 22: Holo - a hologram: scanlines, a sweep and colour fringes
vec3 holo(float t) {
    vec2 uv = plasmaUv();
    float scan = 0.75 + 0.25 * sin(gl_FragCoord.y * 1.4 - t * 6.0);
    float sweep = pow(1.0 - abs(fract(gl_FragCoord.y * Params2.y * 0.8 - t * 0.35) - 0.5) * 2.0, 10.0);
    vec3 col = vec3(plasmaPalette(0.3).r, plasmaPalette(0.6).g, plasmaPalette(0.9).b) * (0.5 + 0.2 * fbm(uv * 0.3 + t)) * scan;
    col += vec3(1.0) * sweep * 0.5;
    col *= 0.85 + 0.15 * step(0.97, hash12(vec2(floor(t * 12.0), floor(gl_FragCoord.y * 0.1))));
    return finish(col);
}

// 23: PlasmaBall - lightning tendrils reaching out from the centre
vec3 plasmaBall(float t) {
    vec2 uv = gl_FragCoord.xy * Params2.y * Plasma.z;
    vec2 c = vec2(0.5 * Params2.y / Params2.x, 0.5) * Plasma.z;
    vec2 d = uv - c;
    float ang = atan(d.y, d.x), rad = length(d) * 10.0;
    float ten = 0.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float a = ang + fbm(vec2(rad * 1.5 - t * 1.2, fi * 7.0)) * 1.4 + t * (0.2 + fi * 0.1) + fi * 1.57;
        ten += pow(1.0 - abs(sin(a * 2.0)), 30.0);
    }
    vec3 col = plasmaPalette(0.1) * 0.18 + plasmaPalette(0.7) * ten * 1.1 + vec3(1.0) * pow(min(ten, 1.0), 4.0) * 0.5;
    return finish(col);
}

// 24: Halftone - comic-print dots that swell and shrink in waves
vec3 halftone(float t) {
    vec2 uv = gl_FragCoord.xy * 0.18 * Plasma.z;
    vec2 f = fract(uv) - 0.5;
    float wave = 0.5 + 0.5 * sin(uv.x * 0.25 + uv.y * 0.18 - t * 2.0);
    float dot_ = smoothstep(wave * 0.5, wave * 0.5 - 0.06, length(f));
    vec3 col = mix(plasmaPalette(0.1) * 0.3, plasmaPalette(0.5 + 0.5 * wave), dot_);
    return finish(col);
}

vec3 plasma() {
    float t = Plasma.y * 1.6;
    int type = int(Plasma.x + 0.5);
    if (type == 17) return ocean(t);
    if (type == 18) return crystal(t);
    if (type == 19) return ink(t);
    if (type == 20) return pulse(t);
    if (type == 21) return circuit(t);
    if (type == 22) return holo(t);
    if (type == 23) return plasmaBall(t);
    if (type == 24) return halftone(t);
    if (type == 9) return lava(t);
    if (type == 10) return frost(t);
    if (type == 11) return rainbowFill(t);
    if (type == 12) return hexGrid(t);
    if (type == 13) return storm(t);
    if (type == 14) return vortex(t);
    if (type == 15) return glitter(t);
    if (type == 16) return toxic(t);
    if (type == 2) return liquid(t);
    if (type == 3) return electric(t);
    if (type == 4) return galaxy(t);
    if (type == 5) return cells(t);
    if (type == 6) return waves(t);
    if (type == 7) return fire(t);
    if (type == 8) return aurora(t);
    // square pixels whatever the window shape: scale by height only
    vec2 uv = gl_FragCoord.xy * Params2.y * Plasma.z * 14.0;
    vec2 c1 = uv - vec2(4.0 + 3.0 * sin(t * 0.37), 4.0 + 3.0 * cos(t * 0.29));
    vec2 c2 = uv - vec2(5.0 + 4.0 * cos(t * 0.23 + 1.7), 3.0 + 3.0 * sin(t * 0.41 + 0.6));
    float v = sin(uv.x * 0.9 + t)
            + sin((uv.y * 0.7 - t * 0.8))
            + sin((uv.x + uv.y) * 0.5 + t * 0.6)
            + sin(length(c1) * 1.3 - t * 1.4)
            + sin(length(c2) * 1.1 + t);
    v *= 0.2; // -1..1
    float hue = 0.5 + 0.5 * sin(v * 3.14159 + t * 0.35);
    vec3 col = plasmaPalette(hue) * (0.45 + 0.55 * (0.5 + 0.5 * cos(v * 6.28318)));
    // glossy ribbons along the iso-lines + a thin white-hot core inside each
    float rib = abs(sin(v * 9.42477 - t * 0.9));
    col += mix(PlasmaCol3.rgb, vec3(1.0), 0.35) * pow(1.0 - rib, 10.0) * 0.9;
    col += vec3(1.0) * pow(1.0 - rib, 40.0) * 0.6;
    col *= Plasma.w;
    return col / (1.0 + col * 0.35);
}

void main() {
    int r = int(Params.x);
    vec4 center = texture(HSampler, texCoord);
    if (center.g > 0.5) {
        if (Params2.z <= 0.0) discard;
        fragColor = vec4(Plasma.x > 0.5 ? plasma() : outlineColor(), Params2.z);
        return;
    }
    float best = 1.0e9;
    int step = max(1, int(Mist.w));
    for (int dy = -r; dy <= r; dy += step) {
        float gx = texture(HSampler, texCoord + vec2(0.0, float(dy) * Params2.y)).r * 255.0;
        if (gx < 254.5) best = min(best, gx * gx + float(dy * dy));
    }
    float dist = sqrt(best);

    if (Mist.x > 0.5) {
        // Mist: a soft glow spilling OUTWARDS from the silhouette - brightest and whitest right at the edge,
        // fading to nothing at the radius, with a slow drifting noise so it reads as haze, not a flat halo
        // Fire licking outwards from the outline: noise stretched upright and scrolling up (gl_FragCoord.y grows
        // upwards) cut into tongues - near the edge almost everything burns, further out only the noise peaks
        // survive, so the flames break up and vanish as they rise
        if (best > 1.0e8) discard;
        float g = clamp(1.0 - dist / float(r), 0.0, 1.0);
        float t = Plasma.y * 1.3;
        vec2 fp = vec2(gl_FragCoord.x * 0.05, gl_FragCoord.y * 0.028);
        float n = vnoise(fp + vec2(0.0, -t * 2.4)) * 0.55
                + vnoise(fp * 2.1 + vec2(t * 0.35, -t * 3.6)) * 0.30
                + vnoise(fp * 4.3 + vec2(-t * 0.5, -t * 5.0)) * 0.15;
        float flame = clamp(pow(g, Mist.z * 0.45) * 1.8 - (1.0 - n) * 1.0, 0.0, 1.0);
        float a = pow(flame, 1.3) * Mist.y * 0.75;   // soft: a big fire that does not glare
        if (a <= 0.003) discard;
        // hot core right at the edge: towards white-yellow, the tips keep the Mist colour
        vec3 tip = outlineColor();
        vec3 core = mix(tip, vec3(1.0, 0.95, 0.8), 0.25);
        vec3 col = mix(tip, core, pow(flame, 2.5) * g);
        fragColor = vec4(col, clamp(a, 0.0, 1.0) * Color.a);
        return;
    }
    float a = clamp(float(r) + 0.5 - dist, 0.0, 1.0);
    if (a <= 0.0) discard;
    fragColor = vec4(outlineColor(), a * Color.a);
}
