#version 150

// Dual Kawase blur - downsample pass.
uniform sampler2D InputTexture;
uniform vec2 Offset;
uniform vec2 HalfPixel;

in vec2 TexCoord;
out vec4 OutColor;

void main() {
    vec2 px = HalfPixel * Offset;
    vec4 result = texture(InputTexture, TexCoord) * 4.0;
    result += texture(InputTexture, TexCoord - px);
    result += texture(InputTexture, TexCoord + px);
    result += texture(InputTexture, TexCoord + vec2(px.x, -px.y));
    result += texture(InputTexture, TexCoord - vec2(px.x, -px.y));
    OutColor = result / 8.0;
}
