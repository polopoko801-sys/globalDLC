#version 330

// BKClient Target ESP "Frag Scan" (assets/bkclient/shaders/core/target_scan/scan.fsh, MIT, bkubiakdev). The shading
// is BK's. BK re-rendered the target into a private normal/depth mask; on 1.21.11 the target's surface is taken
// from the world depth instead, kept to the target's hitbox, with normals from neighbouring depth samples.

uniform sampler2D Scene;
uniform sampler2D DepthSampler;

layout(std140) uniform Scan {
    mat4 InverseViewProjection;
    vec4 OriginHeight;   // target's feet relative to the camera, w = height
    vec4 TintOpacity;    // rgb tint, a = opacity
    vec4 ScanParams;     // height progress, signed velocity, trail width / body height, brightness
    vec4 BoxPixel;       // xy = hitbox half width (x, z), zw = pixel size
};

in vec2 texCoord;
out vec4 fragColor;

float hash12(vec2 value) {
    vec3 p = fract(vec3(value.xyx) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec2 surfaceProjection(vec3 position, vec3 normal) {
    normal = abs(normal);
    if (normal.y >= normal.x && normal.y >= normal.z) return position.xz;
    return normal.x >= normal.z ? position.zy : position.xy;
}

vec3 reconstruct(vec2 uv, float depth) {
    vec4 relative = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return relative.xyz / relative.w - OriginHeight.xyz;
}

bool onTarget(vec3 body) {
    vec2 hw = BoxPixel.xy + 0.08;
    return abs(body.x) <= hw.x && abs(body.z) <= hw.y && body.y > 0.03 && body.y <= OriginHeight.w + 0.08;
}

vec4 shade(vec2 uv, vec3 normal, float depth) {
    vec2 PixelSize = BoxPixel.zw;
    vec3 Tint = TintOpacity.rgb;
    float Opacity = TintOpacity.a;
    vec4 Scan = ScanParams;
    vec3 body = reconstruct(uv, depth);
    vec3 px = reconstruct(uv + vec2(PixelSize.x, 0.0), depth) - body;
    vec3 py = reconstruct(uv + vec2(0.0, PixelSize.y), depth) - body;
    float height = OriginHeight.w;
    float scanY = height * mix(0.035, 0.965, Scan.x);
    float band = max(height * Scan.z, 0.035);
    float delta = scanY - body.y;
    float footprint = abs(px.y) + abs(py.y);
    float frontWidth = max(height * 0.018, footprint * 0.75);
    float front = exp(-pow(delta / frontWidth, 2.0));
    float glow = exp(-pow(delta / max(band * 0.32, frontWidth * 2.5), 2.0));
    float below = smoothstep(-frontWidth, frontWidth, delta) * (1.0 - smoothstep(0.0, band, delta));
    float above = smoothstep(-frontWidth, frontWidth, -delta) * (1.0 - smoothstep(0.0, band, -delta));
    float travel = smoothstep(-0.55, 0.55, Scan.y);
    float trail = mix(above, below, travel);

    float cellSize = max(height * 0.026, 0.025);
    vec2 surface = surfaceProjection(body, normal) / cellSize;
    vec2 cellFootprint = (abs(surfaceProjection(px, normal)) + abs(surfaceProjection(py, normal))) / cellSize;
    float footprintCell = max(cellFootprint.x, cellFootprint.y);
    vec2 cell = floor(surface);
    vec2 squareUv = abs(fract(surface) - 0.5);
    float aa = max(0.02, footprintCell * 0.5);
    float detail = 1.0 - smoothstep(0.3, 0.8, footprintCell);
    float squareCore = mix(0.8, 1.0 - smoothstep(0.40 - aa, 0.46 + aa, max(squareUv.x, squareUv.y)), detail);
    float noise = mix(0.5, hash12(cell), detail);
    float infection = trail * mix(0.40, 0.8, noise) * mix(0.65, 1.0, squareCore);
    float sparks = smoothstep(0.82, 0.94, noise) * squareCore * trail * trail * detail;
    float lineDetail = 1.0 - smoothstep(1.0, 3.14, footprint / height * 360.0);
    float scanlines = 0.5 + 0.5 * sin(body.y / height * 360.0) * lineDetail;
    vec3 color = mix(Tint, vec3(1.0), clamp(front * 0.85 + sparks * 0.15, 0.0, 1.0));
    float intensity = (front * 0.9 + glow * 0.20 + infection * 0.70 + sparks * 0.18 + scanlines * trail * 0.04) * Scan.w;
    float alpha = clamp(intensity * Opacity, 0.0, 0.95);
    return vec4(color * alpha, alpha);
}

vec3 depthNormal(vec2 uv, float depth) {
    vec2 px = BoxPixel.zw;
    vec3 c = reconstruct(uv, depth);
    vec3 dx = reconstruct(uv + vec2(px.x, 0.0), texture(DepthSampler, uv + vec2(px.x, 0.0)).r) - c;
    vec3 dy = reconstruct(uv + vec2(0.0, px.y), texture(DepthSampler, uv + vec2(0.0, px.y)).r) - c;
    vec3 n = cross(dx, dy);
    float len = length(n);
    return len < 1e-8 ? vec3(0.0, 1.0, 0.0) : n / len;
}

void main() {
    vec3 scene = texture(Scene, texCoord).rgb;
    vec4 accumulated = vec4(0.0);
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec2 uv = texCoord + (vec2(float(x), float(y)) - 0.5) * BoxPixel.zw * 0.5;
        float depth = texture(DepthSampler, uv).r;
        if (depth >= 1.0) continue;
        vec3 body = reconstruct(uv, depth);
        if (!onTarget(body)) continue;
        accumulated += shade(uv, depthNormal(uv, depth), depth);
    }
    if (accumulated.a < 0.004) {
        fragColor = vec4(scene, 1.0);
        return;
    }
    // BK blended its output with SRC_ALPHA, ONE_MINUS_SRC_ALPHA over the frame
    vec4 src = vec4(accumulated.rgb / accumulated.a, accumulated.a * 0.25);
    fragColor = vec4(src.rgb * src.a + scene * (1.0 - src.a), 1.0);
}
