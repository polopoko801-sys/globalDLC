#version 330

// One pixel: where the fire is on screen this frame (alpha-weighted centre), so the trail pass knows how far
// the item moved since the last frame. RGBA8 only, so each coordinate is split over two channels
// (hi + lo / 255) - about 1/65000 of the screen. No fire at all writes 0, read as "unknown".
uniform sampler2D MistSampler;

out vec4 fragColor;

void main() {
    const int NX = 96;
    const int NY = 64;
    vec2 sum = vec2(0.0);
    float mass = 0.0;
    for (int y = 0; y < NY; y++) {
        for (int x = 0; x < NX; x++) {
            vec2 uv = (vec2(float(x), float(y)) + 0.5) / vec2(float(NX), float(NY));
            float a = texture(MistSampler, uv).a;
            sum += uv * a;
            mass += a;
        }
    }
    if (mass < 0.5) {
        fragColor = vec4(0.0);
        return;
    }
    vec2 c = clamp(sum / mass, 0.0, 0.9999);
    vec2 hi = floor(c * 255.0) / 255.0;
    vec2 lo = fract(c * 255.0);
    fragColor = vec4(hi.x, lo.x, hi.y, lo.y);
}
