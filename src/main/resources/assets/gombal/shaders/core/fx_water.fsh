#version 330

// Water drawn into projected world shapes (Trails ribbon, JumpCircle ripple, ChinaHat). Sampler0 is the frame
// snapshot; the pixel's own screen position (gl_FragCoord / ScreenSize) is where the scene behind it is, and
// a per-pixel wave normal bends that lookup - refraction - so the world wobbles through the water.
//
// The GUI renderer only binds its own uniforms, so the shape's parameters ride on the vertex data:
//   texCoord0.x  = position along the shape (world units), NEGATIVE = ripple-ring mode
//   texCoord0.y  = 0..1 across the shape, + 2 * round(tint * 50) packed into the integer part
//   vertexColor  = tint colour, alpha = opacity (fade)

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

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}

// sum of travelling waves + a little noise: the water surface height
float waves(vec2 p, float t) {
    float h = sin(p.x * 3.1 - t * 2.2) * 0.35
            + sin(p.x * 5.3 + p.y * 4.0 + t * 1.7) * 0.22
            + sin(p.x * 9.7 - p.y * 6.5 - t * 3.1) * 0.12;
    h += (noise(p * 3.0 + vec2(t * 0.6, -t * 0.4)) - 0.5) * 0.5;
    return h;
}

void main() {
    bool ring = texCoord0.x < 0.0;
    float packedTint = floor(texCoord0.y / 2.0);
    float v = texCoord0.y - packedTint * 2.0;
    float tint = packedTint / 50.0;
    float u = abs(texCoord0.x);
    float t = GameTime * 1200.0;   // GameTime is the 0..1 day fraction - 1200 s per day

    vec2 p = vec2(u, v);
    float e = 0.02;
    vec2 n;
    float crest = 0.0;
    if (ring) {
        // one ripple: a crest across the band, bent a little along the circumference
        float h0 = sin(v * 3.14159);
        crest = h0 * h0;
        float hx = noise(vec2(u * 6.0, t)) - 0.5;
        n = vec2(hx * 0.4, cos(v * 3.14159) * 1.6);
    } else {
        float h = waves(p, t);
        n = vec2(waves(p + vec2(e, 0.0), t) - h, waves(p + vec2(0.0, e), t) - h) / e;
    }

    vec2 screen = gl_FragCoord.xy / ScreenSize;
    vec2 refr = n * (ring ? 0.006 : 0.004);
    vec3 scene = texture(Sampler0, clamp(screen + refr, 0.001, 0.999)).rgb;

    // water body: the scene seen through tinted water, a bit deeper (darker, more tint) towards the bottom
    vec3 col = mix(scene, scene * vertexColor.rgb * 1.25, clamp(tint * (1.15 - v * 0.4), 0.0, 1.0));

    // caustic light lines dancing through it
    if (!ring) {
        float c = abs(sin(u * 6.0 + noise(p * 4.0 + t * 0.5) * 6.0 - t * 1.5));
        col += vertexColor.rgb * pow(1.0 - c, 10.0) * 0.18;
    }

    // small specular sparkles where the surface faces the light - points, never a stripe
    vec3 nn = normalize(vec3(-n * 0.6, 1.0));
    float spec = pow(max(dot(nn, normalize(vec3(0.35, 0.55, 0.75))), 0.0), 60.0);
    col += vec3(1.0) * spec * (ring ? 0.35 : 0.5) + vertexColor.rgb * crest * 0.12;

    // soft edges instead of a hard (white) border
    float edge = smoothstep(0.0, 0.12, v) * smoothstep(1.0, 0.82, v);
    fragColor = vec4(col, vertexColor.a * edge) * ColorModulator;
}
