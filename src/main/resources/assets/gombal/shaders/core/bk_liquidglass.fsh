#version 330

// BKClient's liquid glass (assets/bkclient/shaders/core/liquidglass/fragment.fsh, MIT, bkubiakdev), ported to a
// 1.21.11 GUI pipeline. The maths below is BK's: rounded-box SDF, edge refraction through Snell's law, fresnel
// rim, water glare and chromatic split. What changed is how the numbers arrive - 1.21.11's GUI renderer binds
// only its own uniforms, so every per-panel value rides in the vertex:
//   texCoord0  = local corner (0..1) + 4 * size in quarter GUI pixels   (x = width, y = height)
//   vertexColor.r = radius / 32, .g = distortion / 0.5, .b = gui scale / 8, .a = opacity
// and BK's pre-blurred backdrop texture is replaced by blurring the frame snapshot (Sampler0) right here.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// BK's Interface defaults (render quality "Best")
const float FRESNEL_POWER = 26.0;
const float GLARE_STRENGTH = 0.2;
const float CHROMATIC_STRENGTH = 0.05;
const float CORNER_SMOOTHNESS = 2.0;
const float SMOOTHNESS = 0.5;
const float BLUR_RADIUS = 3.0;   // GUI pixels; BK's kawase pass at blur strength 0.6

float roundedBoxSDF(vec2 p, vec2 b, float r, float smoothness) {
    vec2 q = abs(p) - b + r;
    vec2 q_clamped = max(q, 0.0);
    float len = pow(pow(q_clamped.x, smoothness) + pow(q_clamped.y, smoothness), 1.0 / smoothness);
    return min(max(q.x, q.y), 0.0) + len - r;
}

// The snapshot is a copy of the framebuffer, so its size is the screen in pixels. Globals.ScreenSize is not
// filled on every screen (the title screen leaves it zero, which turned the glass black).
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

void main() {
    vec2 local = fract(texCoord0);
    vec2 Size = floor(texCoord0) / 16.0;          // 4 * quarter-pixels -> GUI pixels
    float radius = vertexColor.r * 32.0;
    float distort = vertexColor.g * 0.5;
    float guiScale = max(vertexColor.b * 8.0, 1.0);
    float opacity = vertexColor.a;

    vec2 center = Size * 0.5;
    vec2 box_half_size = center - 1.0;
    vec2 pos = local * Size - center;

    float distance = roundedBoxSDF(-pos, box_half_size, radius, CORNER_SMOOTHNESS);
    float alpha = 1.0 - smoothstep(1.0 - SMOOTHNESS, 1.0, distance);

    float eps = 2.0;
    float sdfC = roundedBoxSDF(pos, box_half_size, radius, CORNER_SMOOTHNESS);
    float sdfRx = roundedBoxSDF(pos + vec2(eps, 0.0), box_half_size, radius, CORNER_SMOOTHNESS);
    float sdfRy = roundedBoxSDF(pos + vec2(0.0, eps), box_half_size, radius, CORNER_SMOOTHNESS);
    vec2 grad = vec2(sdfRx - sdfC, sdfRy - sdfC);
    float gradLen = length(grad);
    vec2 edgeNormal = gradLen > 0.001 ? grad / gradLen : vec2(0.0);

    float nmerged = -sdfC;
    float refThickness = clamp(min(box_half_size.x, box_half_size.y) * 0.35, 2.0, 15.0);
    float xR = clamp(1.0 - nmerged / max(refThickness, 1.0), 0.0, 1.0);
    float thetaI = asin(xR * xR);
    float ior = 1.0 + distort * 4.0;
    float sinThetaT = clamp(sin(thetaI) / max(ior, 1.001), -1.0, 1.0);
    float thetaT = asin(sinThetaT);
    float edgeFactor = -tan(thetaT - thetaI);
    float refFade = 1.0 - smoothstep(refThickness * 0.25, refThickness, nmerged);
    edgeFactor *= refFade;

    // GUI pixels -> framebuffer UV (the snapshot's rows run bottom-up, hence the flipped y like BK's)
    vec2 refrPx = -edgeNormal * edgeFactor * distort * 90.0;
    vec2 refrOffset = vec2(refrPx.x, -refrPx.y) * guiScale / FrameSize();
    vec2 screenUV = gl_FragCoord.xy / FrameSize();
    vec2 baseTexCoord = clamp(screenUV + refrOffset, 0.0, 1.0);

    vec3 texColor;
    if (CHROMATIC_STRENGTH > 0.001) {
        vec2 chromOffset = refrOffset * CHROMATIC_STRENGTH;
        float r = blurred(clamp(baseTexCoord + chromOffset, 0.0, 1.0), guiScale).r;
        vec3 g = blurred(baseTexCoord, guiScale);
        float b = blurred(clamp(baseTexCoord - chromOffset, 0.0, 1.0), guiScale).b;
        texColor = vec3(r, g.g, b);
    } else {
        texColor = blurred(baseTexCoord, guiScale);
    }

    float seamBlend = smoothstep(refThickness * 0.5, refThickness, nmerged);
    if (seamBlend > 0.001) {
        texColor = mix(texColor, blurred(screenUV, guiScale), seamBlend);
    }

    float fresnelRange = clamp(min(box_half_size.x, box_half_size.y) * 0.5, 2.0, 30.0);
    float edge_gradient = 1.0 - smoothstep(0.0, fresnelRange, -sdfC);
    // FresnelInvert = true, FresnelMix = 0, BaseAlpha = 1, FresnelAlpha = opacity (BK's drawClientRect)
    float fresnel = clamp(exp(FRESNEL_POWER * log(clamp(edge_gradient, 0.001, 1.0))), 0.0, 1.0);

    vec3 finalColor = texColor;
    float finalAlpha = mix(1.0, opacity, fresnel) * alpha;
    if (finalAlpha < 0.001) discard;

    float edgeDist = abs(distance);
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

    fragColor = vec4(finalColor, finalAlpha * opacity) * ColorModulator;
}
