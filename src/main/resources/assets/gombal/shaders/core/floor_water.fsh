#version 330

// FloorWeather: a thin sheet of clear water over the floor around the player, as one full-screen pass.
// Every pixel whose scene depth lands on an upward face (block top or slab top) inside the radius gets a
// gently waving water surface: the floor seen through it (slightly refracted and tinted), and on top the
// REAL surroundings reflected in it - screen-space reflections marched against the scene depth, falling back
// to the sky at the top of the screen when the reflected ray leaves the frame. Fresnel decides the mix, so
// at your feet you look into the water and towards the horizon it turns into a mirror.
uniform sampler2D DepthSampler;
uniform sampler2D SceneSampler;

layout(std140) uniform FloorWater {
    mat4 InvViewProj;
    mat4 ViewProj;
    vec4 Feet;    // xyz = player's feet relative to the camera, w = radius
    vec4 Cam;     // xyz = camera position (x, z wrapped), w = time (already * speed)
    vec4 Water;   // rgb = water colour, a = depth (how much of the water's own colour shows)
    vec4 Params;  // x = wave strength, y = wave scale, z = reflectivity, w = opacity
    vec4 Extra;   // x = weather: 0 water, 1 rain, 2 snow, 3 ice
};

in vec2 texCoord;
out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x), mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
}

// surface height: a few long directional swells that never line up, plus fine drifting chop
float height(vec2 w, float t) {
    float h = 0.0;
    h += 0.50 * sin(dot(w, vec2(0.83, 0.56)) * 1.15 + t * 1.10);
    h += 0.35 * sin(dot(w, vec2(-0.47, 0.88)) * 1.73 - t * 1.37);
    h += 0.22 * sin(dot(w, vec2(0.99, -0.12)) * 2.61 + t * 1.81);
    h += 0.14 * sin(dot(w, vec2(-0.71, -0.70)) * 3.97 - t * 2.23);
    h += 0.30 * vnoise(w * 2.3 + vec2(t * 0.35, -t * 0.27));
    h += 0.16 * vnoise(w * 4.9 - vec2(t * 0.52, t * 0.41));
    return h;
}

// rain: rings spreading from drops landing at random spots, each cell its own drop and timing
// Only a fraction of the cells (Extra.y = density) gets a drop in each cycle, re-rolled every cycle, so the
// rings land sparsely and at random instead of every cell pulsing at once.
float ripples(vec2 w, float t) {
    float h = 0.0;
    vec2 g = w * 0.8;
    vec2 cell = floor(g);
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            vec2 id = cell + vec2(i, j);
            float cyc = t * 0.45 + hash12(id);
            float ph = fract(cyc);
            float roll = hash12(id + floor(cyc) * 1.37 + 11.1);
            if (roll > Extra.y) continue;
            vec2 c = id + 0.2 + 0.6 * vec2(hash12(id + floor(cyc) + 3.1), hash12(id + floor(cyc) + 7.7));
            float dd = length(g - c);
            float rr = ph * 1.1;
            h += sin((dd - rr) * 22.0) * smoothstep(0.12, 0.0, abs(dd - rr)) * (1.0 - ph) * (1.0 - ph);
        }
    return h * 0.8;
}

float surf(vec2 w, float t, int mode) {
    if (mode == 1) return height(w, t) * 0.15 + ripples(w, t);
    return height(w, t);
}

// distances to the nearest and second-nearest cell points - the seams between them are ice cracks
vec2 voro(vec2 x) {
    vec2 n = floor(x), f = fract(x);
    float f1 = 8.0, f2 = 8.0;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(i, j);
            vec2 o = vec2(hash12(n + g), hash12(n + g + 19.19));
            vec2 r = g + o - f;
            float dd = dot(r, r);
            if (dd < f1) { f2 = f1; f1 = dd; } else if (dd < f2) f2 = dd;
        }
    return vec2(sqrt(f1), sqrt(f2));
}

vec3 viewPos(vec2 uv, float d) {
    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

vec3 project(vec3 p) {
    vec4 c = ViewProj * vec4(p, 1.0);
    vec3 n = c.xyz / max(c.w, 1e-5);
    return vec3(n.xy * 0.5 + 0.5, c.w);
}

bool onScreen(vec2 uv) {
    return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
}

// the sky the reflected ray would see when it leaves the frame: the top row of the frame where the ray was
// heading, which is sky (vanilla or CustomSky) in almost every view looking down at the floor
vec3 skyFallback(vec2 uv) {
    float x = clamp(uv.x, 0.02, 0.98);
    vec3 a = texture(SceneSampler, vec2(x, 0.985)).rgb;
    vec3 b = texture(SceneSampler, vec2(clamp(x + 0.08, 0.02, 0.98), 0.96)).rgb;
    return mix(a, b, 0.4);
}

void main() {
    float d = texture(DepthSampler, texCoord).r;
    vec3 p = viewPos(texCoord, d);
    float dist = length(p);

    // only inside the patch, faded out softly towards its rim
    vec2 off = p.xz - Feet.xz;
    float r = length(off);
    float mask = 1.0 - smoothstep(Feet.w * 0.70, Feet.w, r);
    // ...only on surfaces near the feet height band, so walls of a cliff never get water on them
    mask *= 1.0 - smoothstep(4.0, 6.0, abs(p.y - Feet.y));

    // an upward face: flat in world Y (from the depth derivatives) and lying on a block or slab top
    vec3 n0 = normalize(cross(dFdx(p), dFdy(p)));
    if (d >= 0.99999) discard;
    if (n0.y < 0.0) n0 = -n0;
    float wy = p.y + Cam.y;
    float grid = abs(fract(wy * 2.0 + 0.5) - 0.5) * 0.5;
    mask *= smoothstep(0.90, 0.97, n0.y);
    mask *= 1.0 - smoothstep(0.012 + dist * 0.0012, 0.03 + dist * 0.0025, grid);

    vec3 dir = p / max(dist, 1e-4);
    if (dir.y > -0.002) discard;
    if (mask < 0.004) discard;

    int mode = int(Extra.x + 0.5);
    vec2 wp = p.xz + Cam.xz;
    float t = Cam.w;

    if (mode == 2) {
        // Snow, built the way real snow reads:
        //  - a height field of soft drifts and fine grain, shaded from the sun, so it has relief instead of
        //    being a flat coat of paint
        //  - the light comes from the scene: the floor's own brightness divided by a typical block albedo
        //    estimates how lit this spot is (night, shade, torches), and snow takes that light
        //  - shadowed and thin parts go blue (snow scatters blue light), lit parts stay warm white
        //  - where the layer is thin the block shows through in patches, with soft edges
        //  - sparkles are tiny view-dependent glints of ice crystals: they flash as you move, not on a timer
        vec3 floorC = texture(SceneSampler, texCoord).rgb;
        float lum = dot(floorC, vec3(0.299, 0.587, 0.114));
        float light = clamp(lum / 0.32, 0.12, 1.25);

        float detailFade = 1.0 / (1.0 + dist * 0.05);
        float drift = vnoise(wp * 0.22) * 0.55 + vnoise(wp * 0.7 + 3.1) * 0.30 + vnoise(wp * 2.3 + 7.7) * 0.15;
        float sh0 = drift + vnoise(wp * 9.0) * 0.05 * detailFade;
        float shx = vnoise((wp + vec2(0.05, 0.0)) * 0.22) * 0.55 + vnoise((wp + vec2(0.05, 0.0)) * 0.7 + 3.1) * 0.30
                  + vnoise((wp + vec2(0.05, 0.0)) * 2.3 + 7.7) * 0.15 + vnoise((wp + vec2(0.05, 0.0)) * 9.0) * 0.05 * detailFade;
        float shz = vnoise((wp + vec2(0.0, 0.05)) * 0.22) * 0.55 + vnoise((wp + vec2(0.0, 0.05)) * 0.7 + 3.1) * 0.30
                  + vnoise((wp + vec2(0.0, 0.05)) * 2.3 + 7.7) * 0.15 + vnoise((wp + vec2(0.0, 0.05)) * 9.0) * 0.05 * detailFade;
        vec3 sn = normalize(vec3(-(shx - sh0) * 6.0, 1.0, -(shz - sh0) * 6.0));
        vec3 sunDirS = normalize(vec3(0.45, 0.75, 0.35));
        float diffuse = 0.62 + 0.38 * clamp(dot(sn, sunDirS), 0.0, 1.0);

        // thickness: mostly a full layer, thinning out in places so the ground peeks through
        float thick = smoothstep(0.28, 0.52, drift + (vnoise(wp * 4.0) - 0.5) * 0.12);
        float cover = mix(0.55, 1.0, thick);

        vec3 warm = vec3(0.97, 0.97, 0.95);
        vec3 cold = vec3(0.70, 0.80, 0.95);
        float shadow = (1.0 - diffuse) * 1.6 + (1.0 - thick) * 0.35 + (1.0 - min(light, 1.0)) * 0.4;
        vec3 snow = mix(warm, cold, clamp(shadow, 0.0, 1.0)) * diffuse * light;

        // ice-crystal glints, tied to the view direction so they twinkle as you move your head
        vec2 cellG = floor(wp * 30.0);
        float gh = hash12(cellG);
        float facing = dot(normalize(vec3(hash12(cellG + 1.3) - 0.5, 1.0, hash12(cellG + 2.7) - 0.5)), normalize(-dir + sunDirS));
        float glint = step(0.93, gh) * pow(clamp(facing, 0.0, 1.0), 60.0) * detailFade * thick;

        fragColor = vec4(mix(floorC, snow, cover) + vec3(1.0) * glint * 1.4 * min(light, 1.0), mask * Params.w);
        return;
    }

    // wave normal from the height field, calmer with distance so it never shimmers into noise
    vec2 w = wp * Params.y;
    float e = 0.06;
    float h0 = surf(w, t, mode);
    float hx = surf(w + vec2(e, 0.0), t, mode);
    float hz = surf(w + vec2(0.0, e), t, mode);
    float amp = Params.x * 0.13 / (1.0 + dist * 0.035);
    if (mode == 3) amp *= 0.10;      // ice is frozen: barely a ripple, just the unevenness of the sheet
    vec3 n = normalize(vec3(-(hx - h0) / e * amp, 1.0, -(hz - h0) / e * amp));

    // Fresnel (Schlick, water F0 = 0.02)
    float cosi = clamp(dot(-dir, n), 0.0, 1.0);
    float fres = 0.02 + 0.98 * pow(1.0 - cosi, 5.0);
    fres = clamp(fres * Params.z + 0.10 * Params.z, 0.0, 1.0);
    if (mode == 3) fres = clamp(fres * 1.5 + 0.12, 0.0, 1.0);

    // what lies under the water: the floor, nudged by the waves (refraction) and tinted by the water itself
    vec2 ruv = clamp(texCoord + n.xz * 0.018 / (1.0 + dist * 0.08), vec2(0.001), vec2(0.999));
    vec3 floorCol = texture(SceneSampler, ruv).rgb;
    float absorb = Water.a * (0.35 + 0.65 * (1.0 - cosi));
    vec3 under = mix(floorCol, floorCol * Water.rgb * 1.6, absorb * 0.55);
    under = mix(under, Water.rgb * 0.55, absorb * 0.42);
    if (mode == 3) {
        // clear ice: the floor shows through, a touch darker and shifted cyan the deeper you look into it
        float thickness = 0.35 + 0.65 * (1.0 - cosi);
        under = floorCol * mix(vec3(1.0), vec3(0.62, 0.85, 0.95), 0.45 + 0.35 * thickness);
        under = mix(under, Water.rgb * 0.45, 0.12 * thickness);
    }

    // screen-space reflection: march the reflected ray in world space, test it against the scene depth
    vec3 rd = reflect(dir, n);
    vec3 refl = skyFallback(project(p + rd * 40.0).xy);
    float found = 0.0;
    vec3 start = p + n * 0.02;
    float stepLen = 0.12 + dist * 0.01;
    float tr = 0.0;
    vec2 lastUv = texCoord;
    for (int i = 0; i < 40; i++) {
        tr += stepLen;
        stepLen *= 1.13;
        vec3 q = start + rd * tr;
        vec3 sp = project(q);
        if (sp.z <= 0.0 || !onScreen(sp.xy)) break;
        lastUv = sp.xy;
        float sd = texture(DepthSampler, sp.xy).r;
        if (sd >= 0.99999) continue;
        float sceneDist = length(viewPos(sp.xy, sd));
        float rayDist = length(q);
        float diff = rayDist - sceneDist;
        if (diff > 0.0 && diff < 0.6 + tr * 0.12) {
            // refine between the last two samples
            float lo = tr - stepLen / 1.13, hi = tr;
            for (int j = 0; j < 5; j++) {
                float mid = 0.5 * (lo + hi);
                vec3 mq = start + rd * mid;
                vec3 mp = project(mq);
                float md = texture(DepthSampler, mp.xy).r;
                if (length(mq) > length(viewPos(mp.xy, md))) hi = mid; else lo = mid;
            }
            vec2 huv = project(start + rd * hi).xy;
            vec2 edge = min(huv, 1.0 - huv);
            found = smoothstep(0.0, 0.06, min(edge.x, edge.y)) * (1.0 - smoothstep(0.6, 1.0, tr / 60.0));
            refl = mix(refl, texture(SceneSampler, huv).rgb, found);
            break;
        }
    }
    // a ray that left the frame through sky pixels keeps that sky (CustomSky included)
    if (found < 0.5 && texture(DepthSampler, lastUv).r >= 0.99999)
        refl = mix(refl, texture(SceneSampler, lastUv).rgb, 0.6);

    // the reflection of a real surface is a little darker and cooler than the thing itself
    refl *= mix(vec3(1.0), vec3(0.90, 0.96, 1.0), 0.6);

    vec3 col = mix(under, refl, fres);

    // sun glint: a hard highlight where the wave normal mirrors a high light - what makes the surface read
    // as WATER at a glance instead of as a tinted floor
    vec3 sunDir = normalize(vec3(0.35, 0.85, 0.40));
    float spec = pow(max(dot(reflect(dir, n), sunDir), 0.0), mode == 3 ? 220.0 : 90.0);
    col += vec3(1.0, 0.97, 0.90) * spec * (mode == 3 ? 0.9 : 0.55) * Params.z;

    if (mode == 3) {
        float fade = 1.0 / (1.0 + dist * 0.06);
        float lightI = clamp(dot(floorCol, vec3(0.299, 0.587, 0.114)) / 0.3, 0.15, 1.1);

        // frost: milky patches where the surface froze rough, with fine grain - absent elsewhere, so most of
        // the sheet stays glassy
        float frostField = vnoise(wp * 0.3) * 0.6 + vnoise(wp * 1.1 + 5.0) * 0.4;
        float frost = smoothstep(0.58, 0.80, frostField) * (0.75 + 0.25 * vnoise(wp * 14.0));
        col = mix(col, vec3(0.86, 0.92, 0.97) * lightI, frost * 0.65);

        // cracks: only some seams of a large cell pattern, broken up by noise so they start and stop like
        // real fractures; thin, a little brighter, with a faint dark edge from the refraction across them
        vec2 c1 = voro(wp * 0.35);
        float seam = c1.y - c1.x;
        float keep = smoothstep(0.45, 0.6, vnoise(wp * 0.9 + 2.0));
        float crack = (1.0 - smoothstep(0.0, 0.018, seam)) * keep;
        float crackEdge = (1.0 - smoothstep(0.018, 0.05, seam)) * keep - crack;
        col = mix(col, col * 0.78, crackEdge * 0.5 * fade);
        col = mix(col, vec3(0.90, 0.96, 1.0) * lightI, crack * 0.6 * fade);

        // trapped air bubbles: tiny, sparse, pale
        vec2 bc = floor(wp * 12.0);
        vec2 bf = fract(wp * 12.0) - 0.5 - (vec2(hash12(bc + 4.2), hash12(bc + 9.1)) - 0.5) * 0.6;
        float bubble = step(0.965, hash12(bc)) * (1.0 - smoothstep(0.05, 0.11, length(bf)));
        col = mix(col, vec3(0.92, 0.97, 1.0) * lightI, bubble * 0.45 * fade * (1.0 - frost));
    }


    fragColor = vec4(col, mask * Params.w);
}
