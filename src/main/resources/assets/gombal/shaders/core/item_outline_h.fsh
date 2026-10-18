#version 330

// Pass 1 of the item outline. The hand/item mask is "pixels that changed while the hand was drawn":
// BeforeSampler = frame right before renderHand, AfterSampler = frame right after. Per pixel this stores the
// horizontal distance (in pixels, /255) to the nearest mask pixel within Radius, and the mask itself.
uniform sampler2D BeforeSampler;
uniform sampler2D AfterSampler;

layout(std140) uniform OutlineConfig {
    vec4 Color;
    vec4 Params;   // x = radius px, y = rainbow on, z = rainbow phase, w = saturation
    vec4 Params2;  // x = texel w, y = texel h, z = fill alpha, w = rainbow strength
    vec4 Plasma;   // x = plasma fill on, y = time, z = scale, w = intensity
    vec4 PlasmaCol2;
    vec4 PlasmaCol3;
    vec4 Mist;     // x = mist on, y = strength, z = falloff, w = sample step in px
};

in vec2 texCoord;
out vec4 fragColor;

float handMask(vec2 uv) {
    vec3 d = abs(texture(AfterSampler, uv).rgb - texture(BeforeSampler, uv).rgb);
    return max(d.r, max(d.g, d.b)) > 0.002 ? 1.0 : 0.0;
}

void main() {
    int r = int(Params.x);
    float best = 255.0;
    // the mist reaches much further than an outline, so it samples every Mist.w-th pixel - the glow is soft,
    // a distance off by a pixel or two never shows in it
    int step = max(1, int(Mist.w));
    for (int dx = -r; dx <= r; dx += step) {
        if (handMask(texCoord + vec2(float(dx) * Params2.x, 0.0)) > 0.5)
            best = min(best, abs(float(dx)));
    }
    fragColor = vec4(best / 255.0, handMask(texCoord), 0.0, 1.0);
}
