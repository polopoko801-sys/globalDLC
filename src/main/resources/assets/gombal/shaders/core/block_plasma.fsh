#version 330

// BlockOverlay plasma: a full-screen pass that shoots the camera ray of every pixel at the highlighted block's box
// (slab test, camera-relative coordinates) and paints the front faces it hits. The pattern is computed in the
// block's own 3D space, so it sits on the block and flows across its edges - and it is NOT the sky plasma:
// moving voronoi cells whose borders burn as energy veins, over a slow interference wash.
uniform sampler2D DepthSampler;

layout(std140) uniform BlockPlasma {
    mat4 InvViewProj;
    vec4 BoxMin;  // xyz = box min relative to the camera, w = through walls (1)
    vec4 BoxMax;  // xyz = box max relative to the camera, w = time (already * speed)
    vec4 Col1;    // rgb = main colour, a = opacity
    vec4 Col2;    // rgb = second colour, a = pattern scale
    vec4 Extra;   // x = edge glow, y = outline width in px (0 = off), z = outline alpha, w = intensity
    vec4 Pattern; // x = pattern type (0 = Veins, see patternColor)
};

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float vnoise3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(i), hash31(i + vec3(1, 0, 0)), f.x), mix(hash31(i + vec3(0, 1, 0)), hash31(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash31(i + vec3(0, 0, 1)), hash31(i + vec3(1, 0, 1)), f.x), mix(hash31(i + vec3(0, 1, 1)), hash31(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm3(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise3(p);
        p = p * 2.02 + vec3(1.7, 9.2, 4.1);
        a *= 0.5;
    }
    return v;
}

vec3 pal(float x) {
    return mix(Col1.rgb, Col2.rgb, clamp(x, 0.0, 1.0));
}

vec3 hsv(float h) {
    vec3 k = abs(fract(vec3(h) + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return clamp(k - 1.0, 0.0, 1.0);
}

vec2 voronoi(vec3 x, float t); // defined below

// Patterns 1..11, all in the block's own 3D space (q) so they flow across the block's edges.
vec3 patternColor(int type, vec3 q, float t) {
    if (type == 1) { // Liquid - domain-warped oil
        float w = fbm3(q * 0.6 + vec3(fbm3(q * 0.6 + t * 0.2), fbm3(q * 0.6 - t * 0.17), 0.0) * 2.0);
        return pal(w * 1.3 - 0.1) * (0.4 + 0.8 * w) + vec3(1.0) * pow(w, 6.0) * 0.6;
    }
    if (type == 2) { // Electric - ridged arcs crawling over the block
        float r = 1.0 - abs(fbm3(q * 0.8 + vec3(t * 0.5, -t * 0.4, t * 0.3)) * 2.0 - 1.0);
        float vein = pow(r, 14.0);
        return pal(0.1) * 0.12 + pal(0.8) * vein * 1.6 + vec3(1.0) * pow(vein, 3.0) * 0.6;
    }
    if (type == 3) { // Galaxy - nebula and stars
        float n = fbm3(q * 0.5 + fbm3(q * 0.7 + t * 0.05) * 1.5);
        float star = step(0.985, hash31(floor(q * 6.0))) * (0.5 + 0.5 * sin(t * 4.0 + hash31(floor(q * 6.0)) * 80.0));
        return vec3(0.02, 0.01, 0.05) + pal(n) * pow(n, 2.0) * 1.8 + vec3(star);
    }
    if (type == 4) { // Lava - glowing magma between dark crust plates
        vec2 v = voronoi(q * 0.9 + vec3(0.0, t * 0.1, 0.0), t * 0.3);
        float crack = exp(-(v.y - v.x) * 9.0);
        float heat = crack * (0.75 + 0.25 * sin(t * 2.0 + fbm3(q) * 6.0));
        return pal(0.0) * 0.1 * (0.5 + fbm3(q * 2.0)) + pal(0.4 + 0.6 * heat) * heat * 1.7 + vec3(1.0, 0.9, 0.6) * pow(heat, 5.0) * 0.6;
    }
    if (type == 5) { // Frost - ice facets with a cold sheen
        float facet = pow(abs(fract(fbm3(q * 0.9) * 4.0) - 0.5) * 2.0, 3.0);
        float sheen = pow(0.5 + 0.5 * sin(q.x - q.y + q.z - t * 1.5), 14.0);
        return mix(pal(0.2), pal(0.9), facet) * (0.35 + 0.5 * facet) + vec3(0.9, 0.97, 1.0) * sheen * 0.8;
    }
    if (type == 6) { // Fire - flames climbing the block
        vec3 p = q * 0.8 + vec3(0.0, -t * 1.6, 0.0);
        float n = fbm3(p + fbm3(p * 1.3) * 0.8);
        float heat = clamp(n * 1.6 - 0.3, 0.0, 1.0);
        return pal(heat) * (0.2 + 1.2 * heat) + vec3(1.0, 0.95, 0.8) * pow(heat, 6.0) * 0.8;
    }
    if (type == 7) { // Aurora - swaying curtains
        float c = pow(0.5 + 0.5 * sin(q.x * 1.3 + fbm3(vec3(q.y * 0.3, q.z * 0.3, t * 0.2)) * 4.0 + t * 0.3), 6.0);
        return vec3(0.01, 0.02, 0.04) + pal(fract(q.y * 0.12 + t * 0.05)) * c * 1.1;
    }
    if (type == 8) { // Hex - honeycomb cells lighting up in waves
        vec2 uv = q.xz + q.y * 0.5;
        vec2 r = vec2(1.0, 1.7320508), h = r * 0.5;
        vec2 a = mod(uv, r) - h, b = mod(uv - h, r) - h;
        vec2 gv = dot(a, a) < dot(b, b) ? a : b;
        float edge = 0.5 - max(abs(gv.x) * 0.866 + abs(gv.y) * 0.5, abs(gv.y));
        float wave = 0.5 + 0.5 * sin(length(uv - gv) * 0.8 - t * 2.5);
        return pal(wave) * (0.15 + 0.6 * pow(wave, 3.0)) + mix(Col2.rgb, vec3(1.0), 0.4) * (1.0 - smoothstep(0.0, 0.05, edge));
    }
    if (type == 9) { // Rainbow - every hue flowing through the block
        return hsv(fract((q.x + q.y + q.z) * 0.12 + t * 0.15 + fbm3(q * 0.5) * 0.3)) * 0.9;
    }
    if (type == 10) { // Storm - churning clouds with lightning
        float c = fbm3(q * 0.6 + fbm3(q * 0.6 + t * 0.3) * 1.8);
        float flash = pow(max(0.0, sin(t * 1.7) * sin(t * 4.3 + 1.0)), 12.0);
        float bolt = pow(1.0 - abs(fbm3(q * 1.5 + vec3(0.0, t * 3.0, 0.0)) * 2.0 - 1.0), 20.0) * flash;
        return pal(c * 0.6) * (0.12 + 0.6 * c * c) + vec3(1.0) * (bolt * 1.6 + flash * c * 0.4);
    }
    // 11: Toxic - bubbles rising through acid
    vec2 v = voronoi(q * 1.1 + vec3(0.0, -t * 0.7, 0.0), t * 0.8);
    float blob = smoothstep(0.55, 0.3, v.x);
    float rim = smoothstep(0.08, 0.0, abs(v.x - 0.42));
    return pal(0.2) * 0.2 + pal(0.65) * blob * 0.9 + mix(Col2.rgb, vec3(1.0), 0.5) * rim * 1.2;
}

in vec2 texCoord;
out vec4 fragColor;

vec3 hash33(vec3 p) {
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)), dot(p, vec3(269.5, 183.3, 246.1)), dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453);
}

// x = distance to the nearest moving cell point, y = to the second nearest
vec2 voronoi(vec3 x, float t) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    float f1 = 8.0, f2 = 8.0;
    for (int z = -1; z <= 1; z++)
        for (int y = -1; y <= 1; y++)
            for (int xx = -1; xx <= 1; xx++) {
                vec3 g = vec3(float(xx), float(y), float(z));
                vec3 o = hash33(i + g);
                o = 0.5 + 0.42 * sin(t + 6.2831 * o);
                vec3 r = g + o - f;
                float d = dot(r, r);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                } else if (d < f2) {
                    f2 = d;
                }
            }
    return sqrt(vec2(f1, f2));
}

// world distance from p (local, 0..size) to the nearest edge of the face whose normal axis is n
float edgeDist(vec3 p, vec3 size, int n) {
    vec3 d = min(p, size - p);
    if (n == 0) return min(d.y, d.z);
    if (n == 1) return min(d.x, d.z);
    return min(d.x, d.y);
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 far = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w);
    vec3 safe = vec3(abs(dir.x) < 1e-6 ? 1e-6 : dir.x, abs(dir.y) < 1e-6 ? 1e-6 : dir.y, abs(dir.z) < 1e-6 ? 1e-6 : dir.z);
    vec3 inv = 1.0 / safe;
    vec3 t0 = BoxMin.xyz * inv;
    vec3 t1 = BoxMax.xyz * inv;
    vec3 tmin = min(t0, t1);
    vec3 tmax = max(t0, t1);
    float tn = max(max(tmin.x, tmin.y), tmin.z);
    float tf = min(min(tmax.x, tmax.y), tmax.z);
    bool hit = tn > 0.0 && tf >= tn;

    vec3 size = max(BoxMax.xyz - BoxMin.xyz, vec3(1e-4));
    vec3 local = dir * tn - BoxMin.xyz;
    int nIn = tn == tmin.x ? 0 : (tn == tmin.y ? 1 : 2);
    int nOut = tf == tmax.x ? 0 : (tf == tmax.y ? 1 : 2);

    // The outline is drawn here, per pixel on the same box as the plasma, so it can never drift off the block
    // the way the separately projected 2D frame did. Pixel distance = world distance to the face edge / world
    // size of one pixel at that depth (taken from the ray's screen derivative, before any discard).
    float pixAng = max(length(fwidth(dir)), 1e-6);
    float dFront = edgeDist(clamp(local, vec3(0.0), size), size, nIn) / (pixAng * max(tn, 1e-3));
    float dBack = edgeDist(clamp(dir * tf - BoxMin.xyz, vec3(0.0), size), size, nOut) / (pixAng * max(tf, 1e-3));

    if (!hit) discard; // missed, or the camera is inside the box

    if (BoxMin.w < 0.5) {
        // the face is the block's own surface, so it lies at the scene depth: hide it only when something is
        // clearly in front of it
        float d = texture(DepthSampler, texCoord).r;
        vec4 sp = InvViewProj * vec4(ndc, d * 2.0 - 1.0, 1.0);
        float sd = length(sp.xyz / sp.w);
        if (tn > sd + 0.02 + sd * 0.004) discard;
    }

    vec3 lp = clamp(local / size, 0.0, 1.0);

    // glow towards the edges of the face that was hit (the two axes that are not the face normal)
    vec2 fuv = nIn == 0 ? lp.yz : (nIn == 1 ? lp.xz : lp.xy);
    vec2 e = min(fuv, 1.0 - fuv);
    float edge = exp(-min(e.x, e.y) * 14.0);

    float t = BoxMax.w;
    vec3 q = local * Col2.a * 3.0;
    vec2 v = voronoi(q + vec3(0.0, t * 0.35, t * 0.2), t * 1.3);
    float gap = v.y - v.x;
    float vein = exp(-gap * 13.0);

    float s = sin(q.x * 2.1 + t * 1.3) + sin(q.y * 2.7 - t * 1.1) + sin(q.z * 2.3 + t * 0.9)
            + sin(length(q - vec3(1.5)) * 4.0 - t * 2.0);
    s *= 0.25;
    vec3 base = mix(Col1.rgb, Col2.rgb, 0.5 + 0.5 * s);
    float pulse = 0.5 + 0.5 * sin(v.x * 9.0 - t * 3.0);

    vec3 col;
    int type = int(Pattern.x + 0.5);
    if (type == 0) {
        col = base * (0.22 + 0.38 * pulse);
        col += mix(Col2.rgb, vec3(1.0), 0.4) * vein * 1.4;
        col += vec3(1.0) * pow(vein, 6.0) * 0.7;
    } else {
        col = patternColor(type, q, t);
    }
    col += mix(Col1.rgb, vec3(1.0), 0.25) * edge * Extra.x;
    col *= Extra.w;
    col = col / (1.0 + col * 0.4);

    float lum = max(max(col.r, col.g), col.b);
    float fillA = Col1.a * clamp(0.4 + 0.6 * lum, 0.0, 1.0);

    // outline: the edges of the face in front, and dimmer the far edges seen through the box
    float hw = Extra.y * 0.5;
    float lineF = Extra.y > 0.0 ? clamp(hw + 0.5 - dFront, 0.0, 1.0) : 0.0;
    float lineB = Extra.y > 0.0 ? clamp(hw + 0.5 - dBack, 0.0, 1.0) * 0.5 : 0.0;
    float lineA = max(lineF, lineB) * Extra.z;
    vec3 lineCol = mix(Col1.rgb, vec3(1.0), 0.12);

    float a = lineA + fillA * (1.0 - lineA);
    if (a < 0.003) discard;
    fragColor = vec4((lineCol * lineA + col * fillA * (1.0 - lineA)) / a, a);
}
