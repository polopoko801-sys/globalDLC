#version 330

// BKClient Target ESP lens (assets/bkclient/shaders/target_esp/lens.fsh, MIT, bkubiakdev). Same maths; the
// eighteen LensN uniforms and Count/Aspect/Strength arrive as one std140 block on 1.21.11.

in vec2 texCoord;

uniform sampler2D Scene;
uniform sampler2D DepthSampler;

layout(std140) uniform Lenses {
    vec4 Header;      // x = count, y = aspect, z = strength
    vec4 Lens[18];    // u, v, depth, radius
};

out vec4 fragColor;

const int MAX_LENSES = 18;
const float PULL = 0.85;
const float CHROMA = 0.35;

void main() {
    float Count = Header.x, Aspect = Header.y, Strength = Header.z;
    vec2 uv = texCoord;
    float sceneDepth = texture(DepthSampler, uv).r;

    int count = int(Count + 0.5);
    vec2 offset = vec2(0.0);

    for (int i = 0; i < MAX_LENSES; i++) {
        if (i >= count) break;
        vec4 c = Lens[i];
        if (c.w <= 0.0) continue;
        vec2 d = (uv - c.xy) * vec2(Aspect, 1.0);
        float len = length(d);
        float t = len / c.w;
        if (t >= 1.0 || len < 1e-5) continue;
        if (sceneDepth < c.z - 0.0005) continue;
        float p = t * (1.0 - t) * (1.0 - t) * 6.75;
        vec2 dir = d / len;
        offset -= (dir / vec2(Aspect, 1.0)) * (p * c.w * PULL * Strength);
    }

    if (dot(offset, offset) < 1e-12) {
        fragColor = vec4(texture(Scene, uv).rgb, 1.0);
        return;
    }

    vec2 uvR = clamp(uv + offset * (1.0 - CHROMA), vec2(0.0), vec2(1.0));
    vec2 uvG = clamp(uv + offset, vec2(0.0), vec2(1.0));
    vec2 uvB = clamp(uv + offset * (1.0 + CHROMA), vec2(0.0), vec2(1.0));
    fragColor = vec4(texture(Scene, uvR).r, texture(Scene, uvG).g, texture(Scene, uvB).b, 1.0);
}
