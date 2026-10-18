#version 150

// Outer-glow composite: blurred mask minus the solid mask = the glow that spills OUTSIDE the item, tinted with a
// top-to-bottom gradient.
uniform sampler2D MaskTexture;
uniform sampler2D BlurTexture;
uniform vec3 Color;
uniform vec3 Color2;
uniform float Exposure;

in vec2 TexCoord;
out vec4 OutColor;

void main() {
    vec4 blur = texture(BlurTexture, TexCoord);
    vec4 mask = texture(MaskTexture, TexCoord);
    float outerGlow = blur.a * (1.0 - mask.a);
    vec3 gradient = mix(Color, Color2, TexCoord.y);
    float intensity = clamp(outerGlow * Exposure, 0.0, 1.0);
    if (intensity < 0.001) discard;
    OutColor = vec4(gradient, intensity);
}
