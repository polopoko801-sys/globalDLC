#version 330

// BKClient JumpCircles "Liquid" (assets/bkclient/shaders/jump/jump_distort.fsh, MIT, bkubiakdev). Unchanged maths;
// on 1.21.11 it runs as one full-screen pass over the main target with a copy of the scene and the world depth.

uniform sampler2D Scene;
uniform sampler2D DepthSampler;

layout(std140) uniform Ripples {
    vec4 header;
    vec4 header2;
    vec4 header3;
    mat4 invViewProj;
    vec4 data[32];
    vec4 colors[64];
};

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.28318531;

vec3 worldFromDepth(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = invViewProj * clip;
    if (abs(world.w) < 1e-6) {
        return vec3(0.0, 0.0, 1000.0);
    }
    vec3 w = world.xyz / world.w;
    if (isnan(w.x) || isnan(w.y) || isnan(w.z) || isinf(w.x) || isinf(w.y) || isinf(w.z)) {
        return vec3(0.0, 0.0, 1000.0);
    }
    return w;
}

vec3 ringColorByAngle(int i, float ang) {
    int base = i * 4;
    float t = (ang < 0.0 ? ang + TAU : ang) / TAU * 4.0;
    int seg = int(floor(t)) & 3;
    float f = fract(t);
    vec3 a = colors[base + seg].rgb;
    vec3 b = colors[base + ((seg + 1) & 3)].rgb;
    return mix(a, b, f);
}

vec3 ringColor(int i, vec2 dir) {
    return ringColorByAngle(i, atan(dir.y, dir.x));
}

void main() {
    vec2 uv = texCoord;
    vec3 baseScene = texture(Scene, uv).rgb;
    if (isnan(baseScene.r) || isinf(baseScene.r)) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    int count = int(header.x + 0.5);
    if (count <= 0) {
        fragColor = vec4(baseScene, 1.0);
        return;
    }

    float aspect = max(header.y, 0.1);
    float time = header.z;
    float warpAmp = header.w;
    float tintAmount = header2.x;
    float satFactor = header2.y;
    float glowOn = header2.z;
    float glowIntensity = header2.w;
    float glowHeightMul = max(header3.x, 0.1);
    float glowWidthMul = max(header3.y, 0.1);
    float glowTint = header3.z;
    float glowAlpha = header3.w;

    float CONE_HEIGHT = 1.6 * glowHeightMul;

    float sceneDepth = texture(DepthSampler, uv).r;
    bool hasScene = sceneDepth < 1.0;

    vec3 scenePos = worldFromDepth(uv, hasScene ? sceneDepth : 1.0);
    float sceneDist = length(scenePos);
    if (sceneDist < 1e-4 || isnan(sceneDist) || isinf(sceneDist)) {
        sceneDist = 1e9;
    }
    float sceneDistSq = sceneDist * sceneDist;

    vec3 rayDir = sceneDist > 1e-4 ? scenePos / sceneDist : vec3(0.0, 0.0, 1.0);

    vec2 offset = vec2(0.0);
    float waveInfluence = 0.0;
    float tintAccum = 0.0;
    vec3 tintColor = vec3(0.0);
    float coneCovAccum = 0.0;
    vec3 coneGlow = vec3(0.0);

    for (int i = 0; i < count; i++) {
        vec3 center = data[i * 2].xyz;
        float ringRadius = data[i * 2].w;
        float ringWidth = max(data[i * 2 + 1].x, 1e-4);
        float amp = data[i * 2 + 1].y;
        float footprint = data[i * 2 + 1].z;
        float env = data[i * 2 + 1].w;

        if (glowOn > 0.5 && env > 0.001 && abs(rayDir.y) > 1e-5) {
            float smokeH = footprint * CONE_HEIGHT * env;
            float wallR = max(ringRadius, 1e-3);
            float wallHalf = max(ringWidth * 1.6, footprint * 0.10) * glowWidthMul;

            if (smokeH > 1e-4) {
                float tA = center.y / rayDir.y;
                float tB = (center.y + smokeH) / rayDir.y;
                float tLo = min(tA, tB);
                float tHi = max(tA, tB);
                tLo = max(tLo, 0.0);
                tHi = min(tHi, sceneDist);

                float maxHalf = wallHalf * 2.3;
                float outerMax = wallR + footprint * 0.04 + maxHalf;
                float innerMin = max(0.0, wallR - footprint * 0.07 - maxHalf);
                vec2 oXZ = -center.xz;
                vec2 dXZ = rayDir.xz;

                float aa = dot(dXZ, dXZ);
                float bb = dot(oXZ, dXZ);
                float rLoSq = dot(oXZ + dXZ * tLo, oXZ + dXZ * tLo);
                float rHiSq = dot(oXZ + dXZ * tHi, oXZ + dXZ * tHi);
                float rMaxSq = max(rLoSq, rHiSq);
                float rMinSq;
                if (aa > 1e-12) {
                    float tStar = clamp(-bb / aa, tLo, tHi);
                    vec2 pStar = oXZ + dXZ * tStar;
                    rMinSq = dot(pStar, pStar);
                } else {
                    rMinSq = min(rLoSq, rHiSq);
                }

                bool radialMiss = rMinSq > outerMax * outerMax || rMaxSq < innerMin * innerMin;
                if (tHi > tLo && !radialMiss) {
                    const int STEPS = 16;
                    float stepLen = (tHi - tLo) / float(STEPS);
                    float accum = 0.0;
                    vec3 accumCol = vec3(0.0);
                    vec2 accumDir = vec2(0.0);

                    for (int st = 0; st < STEPS; st++) {
                        float t = tLo + (float(st) + 0.5) * stepLen;
                        vec3 pos = rayDir * t;
                        float hWorld = pos.y - center.y;
                        if (hWorld <= 0.0 || hWorld >= smokeH) continue;

                        float ct = hWorld / smokeH;
                        vec2 cradial = pos.xz - center.xz;
                        float crd = length(cradial);

                        float halfHere = wallHalf * (1.0 + ct * 1.3);
                        float bandLo = wallR - footprint * 0.07 - halfHere;
                        float bandHi = wallR + footprint * 0.04 + halfHere;
                        if (crd <= bandLo || crd >= bandHi) continue;

                        float ang = atan(cradial.y, cradial.x);
                        float ripple = (sin(ang * 3.0 + ct * 4.0 + time * 0.8) + 0.5 * sin(ang * 6.0 - ct * 5.0 - time * 1.1)) / 1.5;
                        float wobble = ripple < 0.0 ? ripple * 0.07 : ripple * 0.04;
                        float curtainR = wallR + footprint * wobble;
                        float halfT = halfHere;
                        float radialDist = abs(crd - curtainR);
                        if (radialDist >= halfT) continue;

                        float across = 1.0 - smoothstep(halfT * 0.35, halfT, radialDist);
                        float churn = 0.78 + 0.22 * sin(ang * 5.0 + time * 2.5 - ct * 6.0);
                        float rise = (1.0 - ct) * (1.0 - ct);
                        float dens = across * rise * churn * env;
                        if (dens <= 0.0) continue;

                        accum += dens * stepLen;
                        vec2 cdir = crd > 1e-5 ? cradial / crd : vec2(0.0);
                        accumCol += ringColorByAngle(i, ang) * dens;
                        accumDir += cdir * dens;
                    }

                    if (accum > 1e-4) {
                        float cov = 1.0 - exp(-accum * 2.2);
                        coneCovAccum = coneCovAccum + cov - coneCovAccum * cov;
                        coneGlow += (accumCol / accum) * cov;
                        vec2 cscreen = accumDir;
                        cscreen.x /= aspect;
                        offset += cscreen * (0.012 * cov);
                    }
                }
            }
        }

        if (!hasScene || abs(rayDir.y) < 1e-4) continue;

        float tPlane = center.y / rayDir.y;
        if (tPlane <= 0.0) continue;

        vec3 hit = rayDir * tPlane;
        if (hasScene && sceneDistSq < dot(hit, hit) - 0.25) continue;

        vec2 d = hit.xz - center.xz;
        float dist = length(d);
        vec2 dir = dist > 1e-5 ? d / dist : vec2(0.0);

        float w = (dist - ringRadius) / ringWidth;
        float band = abs(w) < 1.0 ? smoothstep(0.0, 1.0, 1.0 - abs(w)) * env : 0.0;
        if (band <= 0.0) continue;

        waveInfluence = waveInfluence + band - waveInfluence * band;
        tintColor += ringColor(i, dir) * band;
        tintAccum += band;

        float wave = sin(w * PI) * (1.0 - abs(w));
        vec2 screenDir = dir;
        screenDir.x /= aspect;
        offset += screenDir * wave * amp;

        if (warpAmp > 0.0) {
            vec2 turb = vec2(
                sin(hit.z * 2.0 + time * 3.0) + 0.5 * sin(hit.z * 4.0 - time * 2.0),
                cos(hit.x * 2.0 - time * 3.0) + 0.5 * cos(hit.x * 4.0 + time * 2.0)
            );
            turb.x /= aspect;
            offset += turb * warpAmp * band;
        }
    }

    if (isnan(offset.x) || isnan(offset.y) || isinf(offset.x) || isinf(offset.y)) {
        offset = vec2(0.0);
    }
    offset = clamp(offset, vec2(-0.25), vec2(0.25));

    vec3 col = texture(Scene, clamp(uv + offset, 0.0, 1.0)).rgb;
    if (isnan(col.r) || isnan(col.g) || isnan(col.b) || isinf(col.r) || isinf(col.g) || isinf(col.b)) {
        col = baseScene;
    }

    if (abs(satFactor - 1.0) > 0.001 && waveInfluence > 0.001) {
        float f = mix(1.0, satFactor, waveInfluence);
        float lum = dot(col, vec3(0.299, 0.587, 0.114));
        col = max(mix(vec3(lum), col, f), vec3(0.0));
    }

    if (tintAmount > 0.001 && tintAccum > 0.001) {
        vec3 tint = tintColor / tintAccum;
        col += tint * (tintAmount * waveInfluence);
    }

    if (glowOn > 0.5 && coneCovAccum > 0.001) {
        vec3 smoke = coneGlow / max(coneCovAccum, 1e-4);
        float smokeLum = dot(smoke, vec3(0.299, 0.587, 0.114));
        smoke = mix(vec3(smokeLum), smoke, clamp(glowTint, 0.0, 1.0));
        col += smoke * (glowIntensity * coneCovAccum * glowAlpha);
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
