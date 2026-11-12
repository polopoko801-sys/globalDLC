#version 330

// BKClient particle light (assets/bkclient/shaders/world/particle_light.fsh, MIT, bkubiakdev). Same maths; the
// 48 LnPos/LnCol uniforms, InvViewProj and Ambient arrive as one std140 block on 1.21.11.

in vec2 texCoord;

uniform sampler2D Scene;
uniform sampler2D DepthSampler;

layout(std140) uniform Lights {
    mat4 InvViewProj;
    vec4 Header;        // x = ambient, y = light count
    vec4 Light[96];     // pairs: position + radius, colour + intensity
};

out vec4 fragColor;

const float GAIN = 2.0;
const float BOOST = 1.7;
const float LIFT = 0.12;

vec3 worldFromDepth(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 pos = InvViewProj * clip;
    return pos.xyz / pos.w;
}

vec3 faceNormal(vec2 uv, float depth, vec3 world) {
    vec2 texel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec2 uvL = uv - vec2(texel.x, 0.0);
    vec2 uvR = uv + vec2(texel.x, 0.0);
    vec2 uvD = uv - vec2(0.0, texel.y);
    vec2 uvU = uv + vec2(0.0, texel.y);
    float dL = texture(DepthSampler, uvL).r;
    float dR = texture(DepthSampler, uvR).r;
    float dD = texture(DepthSampler, uvD).r;
    float dU = texture(DepthSampler, uvU).r;
    vec3 ddx = abs(dL - depth) < abs(dR - depth) ? world - worldFromDepth(uvL, dL) : worldFromDepth(uvR, dR) - world;
    vec3 ddy = abs(dD - depth) < abs(dU - depth) ? world - worldFromDepth(uvD, dD) : worldFromDepth(uvU, dU) - world;
    vec3 n = cross(ddx, ddy);
    float len = length(n);
    if (len < 1e-8) return normalize(-world);
    n /= len;
    return dot(n, -world) < 0.0 ? -n : n;
}

vec3 contribution(vec3 world, vec3 normal, vec4 posRad, vec4 col, float Ambient) {
    float radius = posRad.w;
    if (radius <= 0.0001 || col.a <= 0.0001) return vec3(0.0);
    vec3 delta = posRad.xyz - world;
    float d = length(delta);
    if (d >= radius) return vec3(0.0);
    float falloff = smoothstep(0.0, 1.0, 1.0 - d / radius);
    float ndotl = d > 0.0001 ? dot(normal, delta / d) : 1.0;
    float lambert = clamp((ndotl + Ambient) / (1.0 + Ambient), 0.0, 1.0);
    return col.rgb * (col.a * falloff * lambert);
}

void main() {
    vec4 src = texture(Scene, texCoord);
    float depth = texture(DepthSampler, texCoord).r;
    if (depth >= 1.0) {
        fragColor = vec4(src.rgb, 1.0);
        return;
    }
    vec3 world = worldFromDepth(texCoord, depth);
    vec3 normal = faceNormal(texCoord, depth, world);
    int count = int(Header.y + 0.5);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 48; i++) {
        if (i >= count) break;
        sum += contribution(world, normal, Light[i * 2], Light[i * 2 + 1], Header.x);
    }
    vec3 lightAmount = vec3(1.0) - exp(-sum * GAIN);
    vec3 lit = src.rgb * (vec3(1.0) + lightAmount * BOOST) + lightAmount * LIFT;
    fragColor = vec4(clamp(lit, 0.0, 1.0), 1.0);
}
