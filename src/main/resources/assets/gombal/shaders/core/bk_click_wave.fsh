#version 330

// A liquid-glass shock ring for ClickEffect: the same refraction, chromatic split and glare as
// bk_liquidglass, but on a ring band instead of a rounded box, so it can sweep across the whole screen.
// Per-wave values ride in the vertex (the GUI pipeline binds only its own uniforms):
//   texCoord0     = local corner (0..1) + 4 * size in quarter GUI pixels   (x = width, y = height)
//   vertexColor.r = band width / 64, .g = refraction strength, .b = gui scale / 8, .a = opacity
// The ring's radius is the quad's half size minus the band width.

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

const float PI = 3.14159265;
const float CHROMATIC_STRENGTH = 0.08;
const float GLARE_STRENGTH = 0.22;

vec2 FrameSize() {
    return vec2(textureSize(Sampler0, 0));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 blurred(vec2 uv, float radiusPx) {
    vec2 px = 1.0 / FrameSize();
    float rot = hash(floor(gl_FragCoord.xy)) * 6.2831;
    vec3 sum = vec3(0.0);
    const int TAPS = 10;
    for (int i = 0; i < TAPS; i++) {
        float f = (float(i) + 0.5) / float(TAPS);
        float a = rot + float(i) * 2.39996;
        vec2 o = vec2(cos(a), sin(a)) * sqrt(f) * radiusPx;
        sum += texture(Sampler0, clamp(uv + o * px, 0.001, 0.999)).rgb;
    }
    return sum / float(TAPS);
}

void main() {
    vec2 local = fract(texCoord0);
    vec2 Size = floor(texCoord0) / 16.0;
    float band = max(vertexColor.r * 64.0, 1.0);
    float strength = vertexColor.g;
    float guiScale = max(vertexColor.b * 8.0, 1.0);
    float opacity = vertexColor.a;

    vec2 center = Size * 0.5;
    vec2 pos = local * Size - center;
    float d = length(pos);
    float radius = max(min(center.x, center.y) - band, 0.0);

    // -1..1 across the band, 0 on the crest
    float x = (d - radius) / band;
    if (abs(x) >= 1.0) discard;

    // the band is a smooth bump of glass: thickest on the crest, nothing at its edges
    float h = cos(x * PI * 0.5);
    h *= h;
    // its slope bends the light outwards on the outer side and inwards on the inner one
    float slope = -sin(x * PI) * PI * 0.5;
    vec2 dir = d > 0.001 ? pos / d : vec2(0.0);

    vec2 refrPx = dir * slope * strength * band * 0.55;
    vec2 refrOffset = vec2(refrPx.x, -refrPx.y) * guiScale / FrameSize();
    vec2 screenUV = gl_FragCoord.xy / FrameSize();
    vec2 uv = clamp(screenUV + refrOffset, 0.0, 1.0);

    float blurPx = 2.0 * guiScale * h;
    vec2 chrom = refrOffset * CHROMATIC_STRENGTH * 4.0;
    float r = blurred(clamp(uv + chrom, 0.0, 1.0), blurPx).r;
    vec3 g = blurred(uv, blurPx);
    float b = blurred(clamp(uv - chrom, 0.0, 1.0), blurPx).b;
    vec3 color = vec3(r, g.g, b);

    // fresnel rim on both faces of the band and the diagonal water glare, as on BK's glass
    float rim = pow(1.0 - h, 6.0) * smoothstep(1.0, 0.8, abs(x));
    float angle = atan(pos.y, pos.x);
    if (angle < 0.0) angle += 6.2831853;
    float a1 = abs(angle - 0.785398), a2 = abs(angle - 3.92699);
    float ad = min(min(a1, a2), 6.2831853 - min(a1, a2));
    float glare = exp(-ad * ad * 1.5) * GLARE_STRENGTH * exp(-x * x * 6.0);
    color += vec3(glare + rim * 0.08);

    float alpha = smoothstep(1.0, 0.75, abs(x)) * opacity;
    if (alpha < 0.002) discard;
    fragColor = vec4(color, alpha) * ColorModulator;
}
