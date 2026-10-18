#version 330

// Frosted glass drawn into projected world shapes. Per-pixel blur of the frame snapshot (Sampler0) around the
// pixel's own screen position, a fine frosted grain that scatters the lookups, a tint and a soft vertical
// sheen. Same vertex packing as fx_water: texCoord0.x along the shape, texCoord0.y = 0..1 across + the tint
// packed into its integer part, vertexColor = tint colour with the opacity in alpha.

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

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float packedTint = floor(texCoord0.y / 2.0);
    float v = texCoord0.y - packedTint * 2.0;
    float tint = packedTint / 50.0;

    vec2 px = 1.0 / ScreenSize;
    vec2 screen = gl_FragCoord.xy * px;
    // frosted grain: every pixel starts its spiral at a different angle, so the blur has no visible pattern
    float rot = hash(floor(gl_FragCoord.xy)) * 6.2831;
    float radius = 9.0;

    vec3 sum = vec3(0.0);
    const int TAPS = 16;
    for (int i = 0; i < TAPS; i++) {
        float f = (float(i) + 0.5) / float(TAPS);
        float a = rot + float(i) * 2.39996;
        vec2 o = vec2(cos(a), sin(a)) * sqrt(f) * radius;
        sum += texture(Sampler0, clamp(screen + o * px, 0.001, 0.999)).rgb;
    }
    vec3 col = sum / float(TAPS);

    // a little lift and saturation, then the tint
    col = mix(col, col * vertexColor.rgb * 1.3, clamp(tint, 0.0, 1.0));
    col += vec3(0.04) * (0.5 + 0.5 * v);

    float edge = smoothstep(0.0, 0.1, v) * smoothstep(1.0, 0.86, v);
    fragColor = vec4(col, vertexColor.a * edge) * ColorModulator;
}
