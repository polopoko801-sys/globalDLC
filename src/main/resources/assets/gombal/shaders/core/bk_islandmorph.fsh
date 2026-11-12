#version 330

// BKClient's Dynamic Island surface (assets/bkclient/shaders/core/island_morph/fragment.fsh, MIT, bkubiakdev),
// ported to a 1.21.11 GUI pipeline: the capsule and the logo bubble melted together by a smooth union, then
// BK's liquid glass through that outline (refraction, glare, chromatic split) with the theme fill over it.
//
// The GUI renderer binds only its own uniforms, so everything rides in the vertex:
//   texCoord0     = local corner (0..1) + 4 * size in quarter pixels of the quad as it lands on screen
//   vertexColor.r = bubble progress (0..1), .g = HUD scale / 4, .b = gui scale / 8, .a = opacity
// Everything else BK passed as a uniform follows from those, exactly as BK's drawIslandMorph derives it:
// quad = capsule + 5 px margin, capsule width = quad - 10 - 19 * progress, bubble radius 7.5 at
// capsule width - 7.5 + 19 * progress, merge = max(0, 8 - 2 * |19 * progress - 15|).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// BK's Interface defaults (render quality "Best", glass on, dark theme)
const float FRESNEL_POWER = 26.0;
const float DISTORT_STRENGTH = 0.3;
const float GLARE_STRENGTH = 0.2;
const float CHROMATIC_STRENGTH = 0.05;
const float CORNER_SMOOTHNESS = 2.0;
const float SMOOTHNESS = 0.5;
const vec4 PANEL_COLOR = vec4(12.0 / 255.0, 12.0 / 255.0, 12.0 / 255.0, 0.5);
const float GLASS_MIX = 1.0;
const float BLUR_RADIUS = 3.0;

vec2 FrameSize() {
    return vec2(textureSize(Sampler0, 0));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 blurred(vec2 uv, float guiScale) {
    vec2 px = 1.0 / FrameSize();
    float rot = hash(floor(gl_FragCoord.xy)) * 6.2831;
    float radius = BLUR_RADIUS * guiScale;
    vec3 sum = vec3(0.0);
    const int TAPS = 12;
    for (int i = 0; i < TAPS; i++) {
        float f = (float(i) + 0.5) / float(TAPS);
        float a = rot + float(i) * 2.39996;
        vec2 o = vec2(cos(a), sin(a)) * sqrt(f) * radius;
        sum += texture(Sampler0, clamp(uv + o * px, 0.001, 0.999)).rgb;
    }
    return sum / float(TAPS);
}

float roundedBoxSDF(vec2 p, vec2 b, float r, float smoothness) {
    vec2 q = abs(p) - b + r;
    vec2 q_clamped = max(q, 0.0);
    float len = pow(pow(q_clamped.x, smoothness) + pow(q_clamped.y, smoothness), 1.0 / smoothness);
    return min(max(q.x, q.y), 0.0) + len - r;
}

vec4 Pill;      // xy centre (relative to the quad centre), zw half size
vec3 Bubble;    // xy centre, z radius
float Merge;
float Radius;
bool HasBubble;

float islandSDF(vec2 p) {
    float a = roundedBoxSDF(p - Pill.xy, Pill.zw, Radius, CORNER_SMOOTHNESS);
    if (!HasBubble) return a;
    float b = length(p - Bubble.xy) - Bubble.z;
    float k = max(Merge, 0.0001);
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

void main() {
    vec2 local = fract(texCoord0);
    vec2 Size = floor(texCoord0) / 16.0;
    float progress = vertexColor.r;
    float scale = max(vertexColor.g * 4.0, 0.01);
    float guiScale = max(vertexColor.b * 8.0, 1.0);
    float opacity = vertexColor.a;

    float margin = 5.0 * scale;
    float pillW = Size.x - 2.0 * margin - 19.0 * progress * scale;
    float pillH = Size.y - 2.0 * margin;
    Pill = vec4(margin + pillW * 0.5 - Size.x * 0.5, 0.0, pillW * 0.5, pillH * 0.5);
    float bubbleX = pillW - 7.5 * scale + 19.0 * progress * scale;
    Bubble = vec3(margin + bubbleX - Size.x * 0.5, 0.0, 7.5 * scale);
    Merge = max(0.0, 8.0 - 2.0 * abs(19.0 * progress - 15.0)) * scale;
    Radius = min(7.0 * scale, Pill.w);
    HasBubble = progress > 0.001;

    vec2 center = Size * 0.5;
    vec2 box_half_size = Pill.zw;
    vec2 pos = local * Size - center;

    float sdfC = islandSDF(pos);
    float alpha = 1.0 - smoothstep(-SMOOTHNESS, SMOOTHNESS, sdfC);
    if (alpha <= 0.0) discard;

    float eps = 2.0;
    float sdfRx = islandSDF(pos + vec2(eps, 0.0));
    float sdfRy = islandSDF(pos + vec2(0.0, eps));
    vec2 grad = vec2(sdfRx - sdfC, sdfRy - sdfC);
    float gradLen = length(grad);
    vec2 edgeNormal = gradLen > 0.001 ? grad / gradLen : vec2(0.0);

    float nmerged = -sdfC;
    float refThickness = clamp(min(box_half_size.x, box_half_size.y) * 0.35, 2.0, 15.0);
    float xR = clamp(1.0 - nmerged / max(refThickness, 1.0), 0.0, 1.0);
    float thetaI = asin(xR * xR);
    float ior = 1.0 + DISTORT_STRENGTH * 4.0;
    float sinThetaT = clamp(sin(thetaI) / max(ior, 1.001), -1.0, 1.0);
    float thetaT = asin(sinThetaT);
    float edgeFactor = -tan(thetaT - thetaI);
    float refFade = 1.0 - smoothstep(refThickness * 0.25, refThickness, nmerged);
    edgeFactor *= refFade;

    vec2 refrPx = -edgeNormal * edgeFactor * DISTORT_STRENGTH * 90.0;
    vec2 refrOffset = vec2(refrPx.x, -refrPx.y) * guiScale / FrameSize();
    vec2 screenUV = gl_FragCoord.xy / FrameSize();
    vec2 baseTexCoord = clamp(screenUV + refrOffset, 0.0, 1.0);

    vec2 chromOffset = refrOffset * CHROMATIC_STRENGTH;
    float r = blurred(clamp(baseTexCoord + chromOffset, 0.0, 1.0), guiScale).r;
    vec3 g = blurred(baseTexCoord, guiScale);
    float b = blurred(clamp(baseTexCoord - chromOffset, 0.0, 1.0), guiScale).b;
    vec3 texColor = vec3(r, g.g, b);

    float seamBlend = smoothstep(refThickness * 0.5, refThickness, nmerged);
    if (seamBlend > 0.001) {
        texColor = mix(texColor, blurred(screenUV, guiScale), seamBlend);
    }

    // FresnelInvert, FresnelMix 0, BaseAlpha 1, FresnelAlpha 1 (BK's drawMorphPanel)
    vec3 finalColor = texColor;
    float finalAlpha = alpha;

    float edgeDist = abs(sdfC);
    float edgeGlow = exp(-edgeDist * edgeDist * 10.0);
    float angle = atan(pos.y, pos.x);
    if (angle < 0.0) angle += 6.2831853;
    float diff1 = abs(angle - 0.785398);
    float diff2 = abs(angle - 3.92699);
    float angleDiff = min(diff1, diff2);
    angleDiff = min(angleDiff, 6.2831853 - angleDiff);
    float angleGlow = exp(-angleDiff * angleDiff * 1.5);
    float waterGlare = edgeGlow * angleGlow * GLARE_STRENGTH * alpha;
    finalColor += vec3(waterGlare);
    finalAlpha = max(finalAlpha, waterGlare * 0.5);

    float glassAlpha = clamp(finalAlpha * GLASS_MIX, 0.0, 1.0);
    float fillAlpha = PANEL_COLOR.a * alpha;
    float combinedAlpha = fillAlpha + glassAlpha * (1.0 - fillAlpha);
    vec3 combined = (PANEL_COLOR.rgb * fillAlpha + finalColor * glassAlpha * (1.0 - fillAlpha)) / max(combinedAlpha, 0.0001);

    fragColor = vec4(combined, combinedAlpha * opacity) * ColorModulator;
}
