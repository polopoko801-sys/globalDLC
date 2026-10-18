#version 330

// Custom Sky - proceduralne niebo liczone per piksel z KIERUNKU patrzenia (promień z odwróconej macierzy
// projekcja * widok). Szum jest trójwymiarowy i próbkowany na sferze kierunków, więc nie ma żadnego szwu,
// a przy obracaniu kamery wzór stoi w miejscu jak prawdziwe niebo. Rysowane tylko tam, gdzie bufor głębi
// jest pusty (= niebo), więc bloki, modele, chmury i HUD zostają nietknięte.
uniform sampler2D DepthSampler;

layout(std140) uniform SkyConfig {
    mat4 InvViewProj;
    vec4 ColPrimary;
    vec4 ColSecondary;
    vec4 ColAccent;
    vec4 P0;   // x = faza czasu (już przemnożona przez prędkość), y = skala, z = złożoność (oktawy), w = intensywność
    vec4 P1;   // x = deformacja, y = poświata, z = detal szumu, w = krycie
    vec4 P2;   // x = styl, y = tryb kolorów, z = jakość (0 niska, 1 wysoka), w = wir
    vec4 Flow; // xyz = kierunek przepływu, w = gęstość gwiazd
};

in vec2 texCoord;
out vec4 fragColor;

// ---------------------------------------------------------------- szum

float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0); // quintic - bez widocznej siatki
    return mix(mix(mix(hash(i + vec3(0, 0, 0)), hash(i + vec3(1, 0, 0)), f.x),
                   mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x),
                   mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

// obrót między oktawami - zabija powtarzalność wzdłuż osi
const mat3 OCT = mat3(0.00, 0.80, 0.60,
                     -0.80, 0.36, -0.48,
                     -0.60, -0.48, 0.64);

float fbm(vec3 p, int oct, float detail) {
    float a = 0.5, s = 0.0, n = 0.0;
    float lac = 1.85 + detail * 0.45;
    for (int i = 0; i < 8; i++) {
        if (i >= oct) break;
        s += a * noise(p);
        n += a;
        p = OCT * p * lac + vec3(1.7, 9.2, 3.1);
        a *= 0.5 + detail * 0.06;
    }
    return s / n;
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// ---------------------------------------------------------------- kolory

vec3 palette(float t) {
    t = clamp(t, 0.0, 1.0);
    int mode = int(P2.y + 0.5);
    if (mode == 0) return ColPrimary.rgb * (0.35 + 0.65 * t);
    if (mode == 1) return mix(ColPrimary.rgb, ColSecondary.rgb, t);
    if (mode == 3) return hsv2rgb(vec3(t * 0.85 + P0.x * 0.02, 0.78, 1.0));
    return t < 0.5 ? mix(ColPrimary.rgb, ColSecondary.rgb, t * 2.0)
                   : mix(ColSecondary.rgb, ColAccent.rgb, t * 2.0 - 1.0);
}

float stars(vec3 d, float density, float t) {
    if (density <= 0.0) return 0.0;
    vec3 p = d * 160.0;
    vec3 i = floor(p);
    float h = hash(i);
    if (h < 1.0 - 0.02 * density) return 0.0;
    vec3 c = i + 0.5 + (vec3(hash(i + 1.3), hash(i + 2.7), hash(i + 5.1)) - 0.5) * 0.7;
    float r = length(p - c);
    float tw = 0.65 + 0.35 * sin(t * (1.5 + h * 3.0) + h * 40.0);
    return (1.0 - smoothstep(0.0, 0.16, r)) * tw * (0.4 + 0.6 * fract(h * 91.7));
}

// ---------------------------------------------------------------- style

vec3 flowOffset(float t) {
    return Flow.xyz * t * 0.35;
}

vec3 swirl(vec3 d, float t) {
    if (P2.w < 0.5) return d;
    float a = t * 0.12 * (1.2 - abs(d.y));
    float c = cos(a), s = sin(a);
    return vec3(c * d.x - s * d.z, d.y, s * d.x + c * d.z);
}

vec3 plasma(vec3 d, float t, int oct, float detail, bool hq) {
    float scale = P0.y, dist = P1.x;
    vec3 q = swirl(d, t) * scale + flowOffset(t);
    // domain warping: przesuwamy punkt próbkowania o inny szum - stąd organiczne, płynące kształty
    // pole deformacji nie potrzebuje drobnych oktaw - to one najwięcej kosztują
    int wo = hq ? clamp(oct - 2, 2, 4) : 2;
    vec3 w = vec3(fbm(q + vec3(0.0, 0.0, t * 0.11), wo, detail),
                  fbm(q + vec3(5.2, 1.3, -t * 0.09), wo, detail),
                  fbm(q + vec3(-3.1, 7.7, t * 0.07), wo, detail));
    vec3 r = q + dist * 3.0 * (w - 0.5);
    if (hq) r += dist * 1.5 * (vec3(fbm(r * 1.7 + t * 0.05, 2, detail)) - 0.5);
    float f = fbm(r, oct, detail);
    float bands = 0.5 + 0.5 * sin(f * 11.0 + t * 0.7 + (w.x - w.y) * 5.0);
    float energy = pow(bands, 3.0) * smoothstep(0.25, 0.75, f);
    // druga, wolniejsza i większa warstwa daje głębię (paralaksa w czasie)
    float deep = fbm(q * 0.45 - flowOffset(t) * 0.5 + 11.0, max(oct - 1, 1), detail);
    vec3 col = palette(f) * (0.10 + 1.15 * energy);
    col += palette(1.0 - deep) * smoothstep(0.45, 0.9, deep) * 0.35;
    col += palette(f * 0.5 + 0.5) * pow(energy, 6.0) * P1.y * 1.6; // poświata jasnych pasm
    return col;
}

vec3 voidSky(vec3 d, float t, int oct, float detail) {
    vec3 q = swirl(d, t) * P0.y * 1.3 + flowOffset(t) * 0.6;
    float f = fbm(q + fbm(q * 0.8 + t * 0.05, oct, detail) * P1.x * 2.0, oct, detail);
    float wave = pow(abs(sin(f * 14.0 - t * 0.6)), 28.0);
    float wave2 = pow(abs(sin(f * 7.0 + t * 0.35 + 1.3)), 40.0);
    float mist = smoothstep(0.4, 0.9, f);
    vec3 col = vec3(0.006, 0.004, 0.018);
    col += palette(f) * mist * 0.12;
    col += palette(0.85) * wave * (0.45 + P1.y * 0.8);
    col += palette(0.3) * wave2 * (0.35 + P1.y * 0.6);
    col += vec3(stars(d, Flow.w, t)) * 0.9;
    return col;
}

vec3 nebula(vec3 d, float t, int oct, float detail) {
    vec3 q = swirl(d, t) * P0.y + flowOffset(t) * 0.4;
    vec3 col = vec3(0.004, 0.005, 0.014) + vec3(stars(d, Flow.w, t));
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        vec3 p = q * (0.7 + fi * 0.45) + vec3(fi * 13.1, fi * 4.7, -fi * 8.3);
        float n = fbm(p + P1.x * (fbm(p * 1.3 - t * 0.03, max(oct - 2, 1), detail) - 0.5) * 2.5, oct, detail);
        float dens = smoothstep(0.42 + fi * 0.04, 0.85, n);
        col = mix(col, palette(fi * 0.5) * (0.3 + 1.2 * dens), dens * 0.7);
        col += palette(fi * 0.5) * pow(dens, 4.0) * P1.y * 0.8;
    }
    return col;
}

// face of the cube the direction points at: xy = planar coords (-1..1), z = face id. Straight lines on a plane,
// so nothing converges into a single point overhead the way longitude/latitude lines do.
vec3 cubeUV(vec3 d) {
    vec3 a = abs(d);
    if (a.y >= a.x && a.y >= a.z) return vec3(d.x / a.y, d.z / a.y, d.y > 0.0 ? 4.0 : 5.0);
    if (a.x >= a.z) return vec3(d.z / a.x, d.y / a.x, d.x > 0.0 ? 0.0 : 1.0);
    return vec3(d.x / a.z, d.y / a.z, d.z > 0.0 ? 2.0 : 3.0);
}

vec3 aurora(vec3 d, float t, int oct, float detail) {
    float el = d.y;
    vec3 col = mix(vec3(0.004, 0.01, 0.03), vec3(0.01, 0.02, 0.05), smoothstep(-0.1, 0.6, el));
    col += vec3(stars(d, Flow.w, t)) * 0.8;
    if (el <= 0.0) return col;
    // the curtains live on flat sheets high above (projected onto a plane, not onto azimuth), so they drift
    // across the whole sky as ribbons instead of all pointing into the zenith
    vec3 glow = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        vec2 p = d.xz / (el + 0.06) * (0.22 + fi * 0.04) * P0.y + flowOffset(t).xz * 0.3 + vec2(fi * 3.7, fi * 1.9);
        float n = fbm(vec3(p * 0.45, t * 0.04 + fi), max(oct - 1, 2), detail);
        float w = fbm(vec3(p * 0.9 + 4.0, t * 0.07), 2, detail);
        float line = abs(n - 0.5 + (w - 0.5) * 0.15 * (1.0 + P1.x));
        float curtain = exp(-line * (38.0 - fi * 4.0));
        float rays = 0.45 + 0.55 * pow(noise(vec3(p * 5.5, t * 0.5 + fi)), 2.0);
        glow += palette(clamp(fi * 0.3 + n * 0.4, 0.0, 1.0)) * curtain * rays * (1.0 - fi * 0.15);
    }
    return col + glow * smoothstep(0.0, 0.18, el) * (1.0 + P1.y * 0.6);
}

vec3 energyWaves(vec3 d, float t, int oct, float detail) {
    vec3 origin = normalize(length(Flow.xyz) > 0.01 ? Flow.xyz : vec3(0.0, 1.0, 0.0));
    float ang = acos(clamp(dot(d, origin), -1.0, 1.0));
    float n = fbm(d * P0.y * 2.0 + t * 0.1, oct, detail);
    float ring = sin(ang * 9.0 * P0.y - t * 2.4 + n * P1.x * 8.0);
    float w = pow(0.5 + 0.5 * ring, 8.0);
    float w2 = pow(0.5 + 0.5 * sin(ang * 23.0 * P0.y - t * 4.0 + n * 5.0), 18.0);
    vec3 col = vec3(0.005, 0.008, 0.02) + palette(n) * 0.08;
    col += palette(0.5 + 0.5 * sin(ang * 1.7 + n * 3.0 + t * 0.2)) * w * (0.8 + P1.y);
    col += palette(1.0) * w2 * 0.5;
    return col;
}

vec3 cyber(vec3 d, float t, int oct, float detail) {
    vec3 c = cubeUV(d);
    float up = smoothstep(-0.05, 0.9, d.y);
    vec3 col = mix(palette(0.0) * 0.25, vec3(0.006, 0.006, 0.02), up);
    // siatka na ściankach sześcianu - linie proste, nie zbiegają się w zenicie
    vec2 g = c.xy * 4.0 * P0.y;
    g.y += t * 0.15;
    vec2 gl = abs(fract(g) - 0.5);
    vec2 fw = min(fwidth(g), vec2(0.3));
    float line = max(1.0 - smoothstep(0.0, max(fw.x, 1e-4) * 1.5, gl.x), 1.0 - smoothstep(0.0, max(fw.y, 1e-4) * 1.5, gl.y));
    float pulse = 0.5 + 0.5 * sin(dot(d.xz, vec2(3.0, 2.0)) * 2.0 + t * 1.5);
    col += palette(0.5 + 0.5 * sin(d.y * 4.0 + t * 0.3)) * line * (0.35 + 0.5 * pulse) * (1.0 - up * 0.6);
    // pas nad horyzontem i skanujący promień
    col += palette(1.0) * exp(-abs(d.y) * 18.0) * (0.9 + P1.y);
    float scan = exp(-pow((d.y - fract(t * 0.07) * 1.2 + 0.1) * 30.0, 2.0));
    col += palette(0.6) * scan * 0.35;
    col += palette(fbm(d * 3.0 + t * 0.05, oct, detail)) * 0.05;
    return col;
}

vec3 galaxy(vec3 d, float t, int oct, float detail) {
    vec3 n = normalize(vec3(0.6, 0.25, 0.75));
    float plane = dot(d, n);
    vec3 q = swirl(d, t * 0.3) * P0.y * 2.2;
    float dust = fbm(q + P1.x * (fbm(q * 1.4, max(oct - 2, 1), detail) - 0.5) * 2.0, oct, detail);
    float band = exp(-pow(plane * (3.4 - P1.x), 2.0));
    vec3 core = normalize(cross(n, vec3(0.0, 0.0, 1.0)));
    float coreGlow = pow(max(dot(d, core), 0.0), 18.0);
    vec3 col = vec3(0.003, 0.004, 0.012);
    col += vec3(stars(d, Flow.w * (1.5 + band * 5.0), t));
    col += palette(0.35) * band * 0.10;
    col += palette(dust) * band * (0.15 + smoothstep(0.3, 0.8, dust)) * 0.95;
    col *= 1.0 - band * smoothstep(0.55, 0.75, fbm(q * 2.5 + 3.0, oct, detail)) * 0.6; // ciemne pasma pyłu
    col += palette(1.0) * coreGlow * (0.6 + P1.y);
    return col;
}

// ---------------------------------------------------------------- style 9..18

vec3 hash33(vec3 p) {
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)), dot(p, vec3(269.5, 183.3, 246.1)), dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453);
}

// x = F1, y = F2, z = hash id of the nearest cell
vec3 voronoiId(vec3 x, float t) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    float f1 = 8.0, f2 = 8.0, id = 0.0;
    for (int z = -1; z <= 1; z++)
        for (int y = -1; y <= 1; y++)
            for (int xx = -1; xx <= 1; xx++) {
                vec3 g = vec3(float(xx), float(y), float(z));
                vec3 h = hash33(i + g);
                vec3 o = 0.5 + 0.42 * sin(t + 6.2831 * h);
                vec3 r = g + o - f;
                float d = dot(r, r);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                    id = h.x;
                } else if (d < f2) {
                    f2 = d;
                }
            }
    return vec3(sqrt(f1), sqrt(f2), id);
}

// orthonormal pair perpendicular to n
void basis(vec3 n, out vec3 u, out vec3 v) {
    u = normalize(abs(n.y) < 0.95 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    v = cross(n, u);
}

vec3 sunset(vec3 d, float t, int oct, float detail) {
    float el = d.y;
    vec3 sunDir = normalize(vec3(0.0, 0.05, 1.0));
    vec3 col = mix(palette(0.0), palette(0.5), smoothstep(0.0, 0.22, el));
    col = mix(col, palette(1.0) * 0.45, smoothstep(0.18, 0.85, el));
    if (el < 0.0) col = palette(0.0) * 0.5;
    float sd = max(dot(d, sunDir), 0.0);
    col += vec3(1.0, 0.9, 0.7) * smoothstep(0.9993, 0.9996, sd) * 2.5;
    col += palette(0.0) * pow(sd, 10.0) * (0.7 + P1.y);
    // band of clouds lit from the sun side
    vec2 cp = d.xz / (max(el, 0.02) + 0.12) * 0.55 * P0.y + flowOffset(t).xz;
    float c = fbm(vec3(cp, t * 0.02), oct, detail);
    c = fbm(vec3(cp + P1.x * (c - 0.5), t * 0.03 + 4.0), oct, detail);
    float cl = smoothstep(0.48, 0.75, c) * smoothstep(0.0, 0.08, el) * (1.0 - smoothstep(0.45, 0.85, el));
    vec3 cc = mix(palette(0.5) * 0.55, vec3(1.0, 0.78, 0.55), pow(sd, 3.0));
    col = mix(col, cc, cl * 0.85);
    col += vec3(stars(d, Flow.w, t)) * smoothstep(0.45, 0.9, el) * 0.5;
    return col * 1.3;
}

vec3 inferno(vec3 d, float t, int oct, float detail) {
    vec3 q = d * P0.y * 2.0;
    q.y -= t * 0.35;
    float w = fbm(q * 1.5 + vec3(0.0, -t * 0.2, t * 0.1), max(oct - 2, 2), detail);
    float n = fbm(q + P1.x * 2.0 * (w - 0.5), oct, detail);
    float heat = smoothstep(0.3, 0.78, n * (1.15 - 0.45 * clamp(d.y, 0.0, 1.0)));
    vec3 col = vec3(0.025, 0.0, 0.0) + palette(heat) * pow(heat, 1.4) * 2.0;
    col += palette(1.0) * pow(heat, 6.0) * 1.6 * (1.0 + P1.y * 0.5);
    // embers drifting upwards
    vec3 ep = d * 60.0 * P0.y;
    ep.y -= t * 3.0;
    vec3 ei = floor(ep);
    float eh = hash(ei);
    if (eh > 0.985) {
        float r = length(fract(ep) - 0.5);
        col += palette(1.0) * (1.0 - smoothstep(0.0, 0.25, r)) * (0.6 + 0.4 * sin(t * 4.0 + eh * 50.0)) * (1.0 + P1.y);
    }
    return col;
}

vec3 viewRay(vec2 ndc) {
    vec4 r = InvViewProj * vec4(ndc, 1.0, 1.0);
    return normalize(r.xyz / r.w);
}

vec3 matrixRain(vec3 d, float t, int oct, float detail) {
    // The rain is laid out on the SCREEN (columns always vertical, never converging overhead the way any
    // vertical line in 3D does when you look up) and slides with the camera's yaw/pitch so it still feels fixed
    // in the world while turning.
    vec3 c = viewRay(vec2(0.0));
    float tanH = tan(acos(clamp(dot(c, viewRay(vec2(1.0, 0.0))), -1.0, 1.0)));
    float tanV = tan(acos(clamp(dot(c, viewRay(vec2(0.0, 1.0))), -1.0, 1.0)));
    vec2 ndc = texCoord * 2.0 - 1.0;
    float density = floor(7.0 * P0.y * 6.28318) / 6.28318; // whole columns per turn - no seam behind you
    float yaw = atan(c.z, c.x);
    float pitch = asin(clamp(c.y, -1.0, 1.0));
    vec2 g = vec2((ndc.x * tanH - yaw) * density, (ndc.y * tanV + pitch) * density * 1.6);
    float colId = mod(floor(g.x), floor(density * 6.28318 + 0.5));
    float sp = 0.5 + hash(vec3(colId, 1.0, 2.0)) * 1.3;
    float cell = floor(g.y);
    // the trail: brightest at the falling head, fading upwards
    float trail = fract(cell / 28.0 + t * sp * 0.22 + hash(vec3(colId, 3.0, 4.0)));
    float bright = pow(trail, 5.0);
    vec2 f = fract(g);
    vec2 sub = floor(f * vec2(3.0, 5.0));
    float glyphT = floor(t * (2.0 + sp * 3.0) + hash(vec3(colId, cell, 9.0)) * 10.0);
    float bit = step(0.42, hash(vec3(colId * 7.0 + sub.x, cell * 5.0 + sub.y, glyphT)));
    float inner = step(0.12, f.x) * step(f.x, 0.88) * step(0.08, f.y) * step(f.y, 0.92);
    vec3 col = palette(0.0) * 0.04;
    col += palette(0.5 + 0.5 * bright) * bright * bit * inner * 1.3;
    col += vec3(0.85, 1.0, 0.9) * step(0.985, trail) * bit * inner * (1.0 + P1.y);
    return col;
}

vec3 storm(vec3 d, float t, int oct, float detail) {
    vec3 q = swirl(d, t) * P0.y * 1.5 + flowOffset(t) * 0.5;
    float c = fbm(q + P1.x * (fbm(q * 2.0 + t * 0.05, max(oct - 2, 1), detail) - 0.5), oct, detail);
    vec3 col = mix(vec3(0.03, 0.035, 0.055), palette(0.0) * 0.75, smoothstep(0.3, 0.8, c));
    col += palette(0.5) * pow(smoothstep(0.55, 0.9, c), 2.0) * 0.35;
    float slot = floor(t * 0.6);
    float ph = fract(t * 0.6);
    float on = step(0.45, hash(vec3(slot, 1.0, 7.0)));
    float flash = on * exp(-ph * 9.0) * (0.65 + 0.35 * sin(ph * 90.0));
    vec3 bd = normalize(vec3(hash(vec3(slot, 2.0, 3.0)) - 0.5, 0.0, hash(vec3(slot, 4.0, 5.0)) - 0.5));
    float az = atan(d.z, d.x);
    float daz = az - atan(bd.z, bd.x);
    daz = mod(daz + 3.14159, 6.28318) - 3.14159;
    float jag = (fbm(vec3(d.y * 14.0, slot * 3.1, 0.0), 3, 0.5) - 0.5) * 0.3;
    float bolt = exp(-abs(daz - jag) * 160.0) * smoothstep(-0.02, 0.08, d.y) * (1.0 - smoothstep(0.35, 0.7, d.y));
    col += palette(1.0) * bolt * flash * 5.0;
    col += palette(0.5) * flash * smoothstep(0.35, 0.8, c) * (0.4 + 0.6 * max(dot(d, bd), 0.0)) * (0.6 + P1.y);
    return col;
}

vec3 blackHole(vec3 d, float t, int oct, float detail) {
    // Gargantua-like: black shadow, thin photon ring, an almost edge-on disk crossing in front, and the far side
    // of the disk bent by gravity into an arc over the top and under the bottom of the shadow
    vec3 bh = normalize(vec3(0.3, 0.38, 1.0));
    float rs = 0.085 * P0.y;
    vec3 u = normalize(cross(vec3(0.0, 1.0, 0.0), bh)); // keeps the disk level with the horizon
    vec3 v = cross(bh, u);
    float facing = dot(d, bh);
    vec2 p = vec2(dot(d, u), dot(d, v)) / rs; // in units of the shadow radius
    float r = facing > 0.0 ? length(p) : 1.0e3;
    float ang = atan(p.y, p.x);
    float spin = t * 0.5;
    float glowK = 1.0 + P1.y * 0.5;

    // background, pulled around the hole
    vec3 bent = normalize(d - (d - bh * facing) * (1.6 / (r * r + 0.3)));
    vec3 col = vec3(0.002, 0.002, 0.007) + vec3(stars(bent, Flow.w * 1.5, t));
    col += palette(0.1) * pow(fbm(bent * 2.5 + 3.0, 4, detail), 2.0) * 0.08;

    // lensed far side of the disk: an arc hugging the shadow, strongest above and below it
    float halo = exp(-pow((r - 1.45) * 2.6, 2.0));
    float haloTex = 0.55 + 0.45 * fbm(vec3(cos(ang + spin) * 3.0, sin(ang + spin) * 3.0, r * 4.0), oct, detail);
    float topBot = 0.35 + 0.65 * abs(sin(ang));
    vec3 hcol = mix(palette(0.5), palette(1.0), exp(-(r - 1.1) * 3.0));
    col += hcol * halo * haloTex * topBot * (1.0 + 0.55 * cos(ang)) * 1.3 * glowK;

    // the shadow itself
    float outside = smoothstep(0.96, 1.04, r);
    col *= outside;
    col += palette(1.0) * exp(-abs(r - 1.06) * 28.0) * 1.4 * (0.8 + 0.4 * cos(ang));

    // the disk, seen almost edge-on; its near half (below the centre) passes in front of the shadow
    vec2 dp = vec2(p.x, p.y / 0.16);
    float rd = length(dp);
    float da = atan(dp.y, dp.x) + spin * 2.0 / pow(max(rd, 1.0), 1.5);
    float band = smoothstep(1.4, 1.8, rd) * (1.0 - smoothstep(3.0, 6.0, rd));
    float tex = fbm(vec3(rd * 3.0, cos(da) * 2.0, sin(da) * 2.0), oct, detail);
    float streaks = 0.5 + 0.5 * sin(rd * 14.0 + tex * 6.0);
    float vis = p.y < 0.0 ? 1.0 : outside;
    vec3 dcol = palette(clamp(1.0 - (rd - 1.4) / 3.5, 0.0, 1.0));
    dcol = mix(dcol, vec3(1.0, 0.95, 0.85), exp(-(rd - 1.4) * 1.5) * 0.6);
    col += dcol * band * (0.35 + 1.1 * tex * streaks) * (1.0 + 0.6 * dp.x / max(rd, 1e-3)) * vis * 1.4 * glowK;

    // soft light spilling around it
    col += palette(0.5) * exp(-max(r - 1.0, 0.0) * 0.9) * 0.1 * outside;
    return col;
}

vec3 synthwave(vec3 d, float t, int oct, float detail) {
    float el = d.y;
    vec3 sunDir = normalize(vec3(0.0, 0.14, 1.0));
    vec3 col = mix(palette(0.0) * 0.9, vec3(0.03, 0.0, 0.08), smoothstep(0.0, 0.55, el));
    col += vec3(stars(d, Flow.w, t)) * smoothstep(0.25, 0.7, el) * 0.7;
    // striped retro sun
    vec3 su, sv;
    basis(sunDir, su, sv);
    vec2 sp = vec2(dot(d - sunDir, su), dot(d - sunDir, sv)) / 0.2;
    float sr = length(sp);
    float sy = d.y - sunDir.y;
    float cut = sy < 0.0 ? step(0.0, sin(sy / 0.2 * 55.0 + t * 1.5) + 0.9 - (-sy / 0.2) * 2.2) : 1.0;
    vec3 sunCol = mix(palette(0.5), palette(1.0), clamp(sy / 0.2 * 0.5 + 0.5, 0.0, 1.0));
    col = mix(col, sunCol * 1.4, (1.0 - smoothstep(0.97, 1.0, sr)) * cut);
    col += palette(0.5) * exp(-max(sr - 1.0, 0.0) * 3.0) * 0.35 * (1.0 + P1.y);
    // mountains on the horizon
    float az = atan(d.z, d.x);
    float h = 0.02 + 0.07 * fbm(vec3(az * 2.5, 3.0, 1.0), 4, 0.4);
    if (el < h && el > -0.01) {
        col = vec3(0.02, 0.0, 0.04);
        col += palette(0.0) * exp(-(h - el) * 120.0) * 1.2;
    }
    // neon grid on the ground below the horizon
    if (el < -0.01) {
        vec2 gp = d.xz / -el * 0.5 * P0.y;
        gp.y += t * 0.8;
        vec2 gf = abs(fract(gp) - 0.5);
        vec2 fw = fwidth(gp);
        float line = max(1.0 - smoothstep(0.0, max(fw.x, 1e-4) * 1.5, gf.x), 1.0 - smoothstep(0.0, max(fw.y, 1e-4) * 1.5, gf.y));
        col = vec3(0.02, 0.0, 0.05) + palette(0.0) * line * exp(el * 2.0) * 1.4;
    }
    return col;
}

vec3 crystal(vec3 d, float t, int oct, float detail) {
    vec3 q = swirl(d, t) * P0.y * 3.0 + flowOffset(t) * 0.3;
    vec3 v = voronoiId(q, t * 0.25);
    float edge = exp(-(v.y - v.x) * 22.0);
    float shimmer = 0.5 + 0.5 * sin(t * 1.8 + v.z * 40.0);
    vec3 col = palette(v.z) * (0.12 + 0.55 * v.x) * (0.5 + 0.5 * shimmer);
    col += mix(palette(v.z), vec3(1.0), 0.5) * edge * (0.8 + P1.y);
    col += palette(1.0) * pow(shimmer, 12.0) * 0.3;
    col += vec3(stars(d, Flow.w * 0.5, t)) * 0.4;
    return col;
}

vec3 warp(vec3 d, float t, int oct, float detail) {
    vec3 fw = normalize(length(Flow.xyz) > 0.01 ? Flow.xyz : vec3(1.0, 0.0, 0.0));
    vec3 u, v;
    basis(fw, u, v);
    float ang = acos(clamp(dot(d, fw), -1.0, 1.0));
    float phi = atan(dot(d, v), dot(d, u));
    vec3 col = vec3(0.004, 0.004, 0.015) + palette(0.0) * pow(max(dot(d, fw), 0.0), 25.0) * (1.0 + P1.y);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float n = 90.0 + fi * 70.0;
        float slot = floor((phi + 3.14159) / 6.28318 * n);
        float h = hash(vec3(slot, fi, 3.0));
        float lp = fract((phi + 3.14159) / 6.28318 * n) - 0.5;
        float s = fract(h * 7.3 + t * (0.25 + h * 0.5) * (1.0 + fi * 0.4));
        float head = s * 3.0;
        float len = 0.15 + 0.5 * s;
        float along = smoothstep(head - len, head, ang) * (1.0 - smoothstep(head, head + 0.02, ang));
        float width = exp(-lp * lp * 60.0);
        col += palette(fract(h + fi * 0.33)) * along * width * step(0.55, h) * s * 2.0;
    }
    return col;
}

vec3 ocean(vec3 d, float t, int oct, float detail) {
    float el = d.y;
    vec3 col = mix(palette(0.0) * 0.08, palette(0.5) * 0.55, smoothstep(-0.6, 0.9, el));
    // caustic net from two moving voronoi layers
    vec3 q = d * P0.y * 4.0 + flowOffset(t) * 0.5;
    vec3 v1 = voronoiId(q + vec3(0.0, t * 0.12, 0.0), t * 0.8);
    vec3 v2 = voronoiId(q * 1.6 + vec3(t * 0.1, 0.0, 3.0), t * 0.6 + 2.0);
    float c = exp(-(v1.y - v1.x) * 10.0) * exp(-(v2.y - v2.x) * 6.0);
    col += palette(1.0) * c * 1.4 * smoothstep(-0.2, 0.6, el) * (1.0 + P1.y);
    // god rays from above
    float az = atan(d.z, d.x);
    float rays = pow(noise(vec3(az * 9.0, t * 0.25, 0.0)), 3.0) * smoothstep(0.1, 0.95, el);
    col += palette(1.0) * rays * 0.5;
    // drifting bubbles
    vec3 bp = d * 45.0 * P0.y;
    bp.y -= t * 2.0;
    vec3 bi = floor(bp);
    float bh = hash(bi);
    if (bh > 0.99) {
        float r = length(fract(bp) - 0.5);
        col += vec3(0.8, 0.95, 1.0) * (smoothstep(0.18, 0.22, r) - smoothstep(0.24, 0.3, r)) * 0.6;
    }
    return col;
}

vec3 daydream(vec3 d, float t, int oct, float detail) {
    float el = d.y;
    vec3 sunDir = normalize(vec3(0.4, 0.55, 0.7));
    vec3 col = mix(palette(0.5) * 1.1, palette(0.0) * 0.8, smoothstep(0.0, 0.8, el));
    if (el < 0.0) col = palette(0.5);
    float sd = max(dot(d, sunDir), 0.0);
    col += vec3(1.0, 0.97, 0.88) * smoothstep(0.9990, 0.9994, sd) * 2.0;
    col += vec3(1.0, 0.95, 0.8) * pow(sd, 8.0) * 0.35 * (1.0 + P1.y);
    // soft fluffy clouds on a flat layer above
    vec2 cp = d.xz / (max(el, 0.03) + 0.08) * 0.35 * P0.y + flowOffset(t).xz;
    float c = fbm(vec3(cp, t * 0.015), oct, detail);
    c = fbm(vec3(cp + P1.x * 0.8 * (c - 0.5), 2.0 + t * 0.02), oct, detail);
    float cl = smoothstep(0.45, 0.7, c) * smoothstep(0.0, 0.12, el);
    float shade = fbm(vec3(cp * 1.3 - sunDir.xz * 0.3, 5.0), max(oct - 1, 1), detail);
    vec3 cc = mix(vec3(1.0), palette(1.0) * 0.85, smoothstep(0.4, 0.75, shade));
    col = mix(col, cc * 1.15, cl);
    return col * 1.25;
}

void main() {
    // tylko niebo: tam, gdzie świat nic nie narysował, głębia została wyczyszczona do 1.0
    if (texture(DepthSampler, texCoord).r < 0.99999) discard;

    vec4 ray = InvViewProj * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec3 d = normalize(ray.xyz / ray.w);

    float t = P0.x;
    bool hq = P2.z > 0.5;
    int oct = int(P0.z + 0.5);
    if (!hq) oct = min(oct, 3);
    oct = clamp(oct, 1, 8);
    float detail = P1.z;

    int style = int(P2.x + 0.5);
    vec3 col;
    if (style == 3) col = voidSky(d, t, oct, detail);
    else if (style == 4) col = nebula(d, t, oct, detail);
    else if (style == 5) col = aurora(d, t, oct, detail);
    else if (style == 6) col = energyWaves(d, t, oct, detail);
    else if (style == 7) col = cyber(d, t, oct, detail);
    else if (style == 8) col = galaxy(d, t, oct, detail);
    else if (style == 9) col = sunset(d, t, oct, detail);
    else if (style == 10) col = inferno(d, t, oct, detail);
    else if (style == 11) col = matrixRain(d, t, oct, detail);
    else if (style == 12) col = storm(d, t, oct, detail);
    else if (style == 13) col = blackHole(d, t, oct, detail);
    else if (style == 14) col = synthwave(d, t, oct, detail);
    else if (style == 15) col = crystal(d, t, oct, detail);
    else if (style == 16) col = warp(d, t, oct, detail);
    else if (style == 17) col = ocean(d, t, oct, detail);
    else if (style == 18) col = daydream(d, t, oct, detail);
    else col = plasma(d, t, oct, detail, hq) + vec3(stars(d, Flow.w * 0.4, t)) * 0.5;

    col *= P0.w;
    // pod horyzontem lekko przygaszone - nie świeci spod ziemi przy niskim terenie
    col *= mix(1.0, 0.55, smoothstep(0.0, 0.35, -d.y));
    // kontrolowana jasność: miękkie nasycenie zamiast przepalania
    col = col / (1.0 + col * 0.55);
    // delikatny dithering - gradienty na 8 bitach bez schodków
    col += (hash(vec3(gl_FragCoord.xy, t)) - 0.5) / 255.0;

    fragColor = vec4(col, P1.w);
}
