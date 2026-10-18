#version 150

// Inner fill: a flat colour inside the item mask (the glow that fills the blade).
uniform sampler2D MaskTexture;
uniform vec3 Color;
uniform float Fill;
uniform float Alpha;

in vec2 TexCoord;
out vec4 OutColor;

void main() {
    float mask = texture(MaskTexture, TexCoord).a;
    float inside = smoothstep(0.05, 0.95, mask);
    float finalAlpha = clamp(Alpha * Fill * inside, 0.0, 1.0);
    if (finalAlpha < 0.001) discard;
    OutColor = vec4(Color, finalAlpha);
}
