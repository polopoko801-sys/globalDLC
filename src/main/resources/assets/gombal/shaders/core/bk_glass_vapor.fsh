#version 330

// BKClient Glass Vapor (assets/bkclient/shaders/vapor/glass_vapor.fsh, MIT, bkubiakdev), unchanged; run as a
// full-screen pass over the main target with a copy of the scene and the world depth.

uniform sampler2D Scene;
uniform sampler2D DepthSampler;

const int MAX_ELEMENTS = 64;

layout(std140) uniform Vapor {
    vec4 header;
    vec4 header2;
    vec4 header3;
    vec4 header4;
    vec4 data[MAX_ELEMENTS * 4];
};

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265;
const float FIELD_T = 0.5;

float linearizeDepth(float d, float near, float far) {
    float z = d * 2.0 - 1.0;
    return (2.0 * near * far) / max(far + near - z * (far - near), 1e-4);
}

vec4 sn_permute(vec4 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }
vec4 sn_taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i  = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + 1.0 * C.xxx;
    vec3 x2 = x0 - i2 + 2.0 * C.xxx;
    vec3 x3 = x0 - 1.0 + 3.0 * C.xxx;
    i = mod(i, 289.0);
    vec4 p = sn_permute(sn_permute(sn_permute(
        i.z + vec4(0.0, i1.z, i2.z, 1.0))
        + i.y + vec4(0.0, i1.y, i2.y, 1.0))
        + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 1.0 / 7.0;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = sn_taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

void main() {
    vec2 uv = texCoord;
    vec3 baseScene = texture(Scene, uv).rgb;
    if (isnan(baseScene.r) || isinf(baseScene.r)) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    int count = int(header.x + 0.5);
    if (count <= 0) {
        fragColor = vec4(baseScene, 1.0);
        return;
    }

    float aspect = max(header.y, 0.1);
    float time = header.z;
    float refrAmp = header.w;
    float rimStrength = header2.x;
    float tintStrength = header2.y;
    float noiseAmp = header2.z;
    float depthGateOn = header2.w;
    float near = max(header3.x, 0.01);
    float far = max(header3.y, 1.0);
    float chroma = header3.z;
    float paletteOn = header3.w;

    float sceneDepth = texture(DepthSampler, uv).r;
    bool hasScene = sceneDepth < 1.0;
    float sceneViewDist = hasScene ? linearizeDepth(sceneDepth, near, far) : 1.0e9;

    float field = 0.0;
    vec2 grad = vec2(0.0);
    vec3 colorAcc = vec3(0.0);
    float shineAcc = 0.0;

    for (int i = 0; i < MAX_ELEMENTS; i++) {
        if (i >= count) break;

        vec4 d0 = data[i * 4];
        float env = d0.w;
        if (env <= 0.001) continue;

        vec4 d1 = data[i * 4 + 1];
        vec2 c = d0.xy;
        vec2 e1 = d1.xy;
        vec2 e2 = d1.zw;

        float extX = (abs(e1.x) + abs(e2.x)) * 1.35 + 0.004;
        float extY = (abs(e1.y) + abs(e2.y)) * 1.35 + 0.004;
        if (uv.x < c.x - extX || uv.x > c.x + extX || uv.y < c.y - extY || uv.y > c.y + extY) {
            continue;
        }

        vec2 pa = vec2((uv.x - c.x) * aspect, uv.y - c.y);
        vec2 E1 = vec2(e1.x * aspect, e1.y);
        vec2 E2 = vec2(e2.x * aspect, e2.y);
        float det = E1.x * E2.y - E1.y * E2.x;
        if (abs(det) < 1.0e-8) continue;

        vec2 p = vec2(E2.y * pa.x - E2.x * pa.y, -E1.y * pa.x + E1.x * pa.y) / det;

        vec4 d2 = data[i * 4 + 2];
        float wobAmt = d2.z;
        if (wobAmt > 0.0) {
            float ang = atan(p.y, p.x);
            float wob = 1.0 + wobAmt * (0.6 * sin(ang * 3.0 + time * 2.1 + d2.y)
                                      + 0.4 * sin(ang * 5.0 - time * 1.6 + d2.y * 1.7));
            p /= max(wob, 0.1);
        }

        float r2 = dot(p, p);
        float fi = 1.0 - r2;
        if (fi <= 0.0) continue;

        float depthFade = 1.0;
        if (depthGateOn > 0.5 && hasScene) {
            depthFade = 1.0 - smoothstep(0.10, 0.90, d0.z - sceneViewDist);
            if (depthFade <= 0.0) continue;
        }

        float scale = env * depthFade;
        float w = fi * fi * scale;
        field += w;
        vec2 mtp = vec2(E2.y * p.x - E1.y * p.y, -E2.x * p.x + E1.x * p.y) / det;
        grad += -4.0 * fi * scale * mtp;

        vec3 elemColor = paletteOn > 0.5 ? header4.rgb : data[i * 4 + 3].rgb;
        colorAcc += elemColor * w;
        shineAcc += d2.w * w;
    }

    if (field <= FIELD_T * 0.35) {
        fragColor = vec4(baseScene, 1.0);
        return;
    }

    float mask = smoothstep(FIELD_T * 0.82, FIELD_T * 1.18, field);
    float lens = sin(clamp((field - FIELD_T * 0.82) / (FIELD_T * 1.9), 0.0, 1.0) * PI);

    float gl = length(grad);
    vec2 gDir = gl > 1.0e-6 ? grad / gl : vec2(0.0, 1.0);

    vec2 off = -gDir * lens * refrAmp;
    float noise = snoise(vec3(uv * 22.0, time * 0.35));
    off += vec2(noise * noiseAmp) * mask;
    off.x /= aspect;

    if (isnan(off.x) || isnan(off.y) || isinf(off.x) || isinf(off.y)) {
        off = vec2(0.0);
    }
    off = clamp(off, vec2(-0.2), vec2(0.2));

    vec3 refr;
    if (chroma > 0.001) {
        refr.r = texture(Scene, clamp(uv + off * (1.0 + chroma), 0.0, 1.0)).r;
        refr.g = texture(Scene, clamp(uv + off, 0.0, 1.0)).g;
        refr.b = texture(Scene, clamp(uv + off * (1.0 - chroma), 0.0, 1.0)).b;
    } else {
        refr = texture(Scene, clamp(uv + off, 0.0, 1.0)).rgb;
    }
    if (isnan(refr.r) || isinf(refr.r)) {
        refr = baseScene;
    }

    float luma = dot(refr, vec3(0.299, 0.587, 0.114));
    vec3 lit = mix(refr, vec3(1.0), clamp(luma, 0.0, 1.0) * 0.35 * mask);

    vec3 tint = clamp(colorAcc / max(field, 1.0e-4), 0.0, 1.0);
    vec3 col = mix(lit, lit * (tint * 0.85 + 0.35) + tint * 0.10, tintStrength * mask);

    float rim = smoothstep(FIELD_T * 0.82, FIELD_T * 1.05, field)
              * (1.0 - smoothstep(FIELD_T * 1.05, FIELD_T * 1.85, field));
    col += (0.30 + tint * 0.70) * rim * rimStrength;

    float shine = clamp(shineAcc / max(field, 1.0e-4), 0.0, 1.0);
    float spec = pow(max(dot(-gDir, normalize(vec2(-0.42, 0.86))), 0.0), 7.0);
    col += vec3(spec * (0.18 + 0.55 * shine) * lens * rimStrength);

    fragColor = vec4(clamp(mix(baseScene, min(col, vec3(1.0)), mask), 0.0, 1.0), 1.0);
}
