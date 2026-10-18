#version 330

// Mist fire trail - ONE continuous streak, not a row of sword copies.
// CurSampler = this frame's fire (straight alpha), PrevSampler = the history (premultiplied),
// CenCur / CenPrev = where the fire's centre was this frame and the last one (item_mist_centroid).
// The fire of this frame is swept back along the whole way the item moved since the last frame, so even a
// fast swing leaves a solid band instead of separate stamps; the history then rises a little, spreads,
// and burns out unevenly (flame tongues) over the trail time.
uniform sampler2D CurSampler;
uniform sampler2D PrevSampler;
uniform sampler2D CenCur;
uniform sampler2D CenPrev;

layout(std140) uniform TrailConfig {
    vec4 Trail;   // x = decay for this frame (0..1), y/z = texel size, w = time in seconds
};

in vec2 texCoord;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

vec2 centre(sampler2D s) {
    vec4 e = texture(s, vec2(0.5));
    return vec2(e.r + e.g / 255.0, e.b + e.a / 255.0);
}

vec4 premul(vec4 c) {
    return vec4(c.rgb * c.a, c.a);
}

void main() {
    vec2 px = Trail.yz;

    // how far the fire moved since the last frame (0 when unknown, tiny, or a jump across the screen)
    vec4 ec = texture(CenCur, vec2(0.5)), ep = texture(CenPrev, vec2(0.5));
    vec2 delta = vec2(0.0);
    if (dot(ec, ec) > 0.0 && dot(ep, ep) > 0.0) {
        delta = centre(CenCur) - centre(CenPrev);
        vec2 dPx = delta / px;
        if (dot(dPx, dPx) < 4.0 || length(delta) > 0.35) delta = vec2(0.0);
    }

    // this frame's fire, swept from where it was to where it is
    vec4 now = premul(texture(CurSampler, texCoord));
    vec4 sweep = vec4(0.0);
    if (delta != vec2(0.0)) {
        const int N = 24;
        for (int k = 1; k <= N; k++) {
            float t = float(k) / float(N);
            vec4 s = premul(texture(CurSampler, texCoord + delta * t));
            sweep = max(sweep, s * (1.0 - t * 0.35));
        }
    }

    // the history: climbs ~1.5 px a frame and spreads softly
    vec2 uv = texCoord - vec2(0.0, px.y * 1.5);
    vec4 old = texture(PrevSampler, uv) * 0.4
             + texture(PrevSampler, uv + vec2(px.x * 2.0, 0.0)) * 0.15
             + texture(PrevSampler, uv - vec2(px.x * 2.0, 0.0)) * 0.15
             + texture(PrevSampler, uv + vec2(0.0, px.y * 2.0)) * 0.15
             + texture(PrevSampler, uv - vec2(0.0, px.y * 2.0)) * 0.15;

    // burns away unevenly: where the noise is low the trail goes out faster -> tongues
    vec2 fp = gl_FragCoord.xy * vec2(0.045, 0.03) + vec2(0.0, -Trail.w * 2.5);
    float n = vnoise(fp) * 0.65 + vnoise(fp * 2.3 + 7.0) * 0.35;
    old *= Trail.x * mix(0.94, 1.0, n);   // never above 1: at high fps it would otherwise grow instead of fade
    old = max(old, sweep * Trail.x * 0.7);
    // drop the last faint bits so the history really empties instead of leaving a permanent haze
    if (old.a < 0.01) old = vec4(0.0);

    // the live fire on top, the trail showing through where it is thinner
    fragColor = now + old * (1.0 - now.a);
}
