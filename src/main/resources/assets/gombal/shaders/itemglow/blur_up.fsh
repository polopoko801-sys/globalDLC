#version 150

// Dual Kawase blur - upsample pass, tinting the blurred mask with the glow colour.
uniform sampler2D InputTexture;
uniform vec2 Offset;
uniform vec2 HalfPixel;
uniform vec3 Color;

in vec2 TexCoord;
out vec4 OutColor;

void main() {
    vec2 px = HalfPixel * Offset;
    vec4 result = texture(InputTexture, TexCoord + vec2(-px.x * 2.0, 0.0));
    result += texture(InputTexture, TexCoord + vec2(-px.x, px.y)) * 2.0;
    result += texture(InputTexture, TexCoord + vec2(0.0, px.y * 2.0));
    result += texture(InputTexture, TexCoord + vec2(px.x, px.y)) * 2.0;
    result += texture(InputTexture, TexCoord + vec2(px.x * 2.0, 0.0));
    result += texture(InputTexture, TexCoord + vec2(px.x, -px.y)) * 2.0;
    result += texture(InputTexture, TexCoord + vec2(0.0, -px.y * 2.0));
    result += texture(InputTexture, TexCoord + vec2(-px.x, -px.y)) * 2.0;
    result /= 12.0;
    OutColor = vec4(result.rgb * Color, result.a);
}
