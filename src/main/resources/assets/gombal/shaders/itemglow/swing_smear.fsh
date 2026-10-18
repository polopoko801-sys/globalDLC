#version 150

// Optional swing smear + glow boost when the item swings.
uniform sampler2D InputTexture;
uniform float Swing;
uniform float SmearIntensity;
uniform float GlowBoost;
uniform vec2 SwipeDirection;

in vec2 TexCoord;
out vec4 OutColor;

void main() {
    vec4 original = texture(InputTexture, TexCoord);
    if (Swing <= 0.01) { OutColor = original; return; }
    vec2 offset = SwipeDirection * SmearIntensity * Swing * 0.05;
    vec4 smear = vec4(0.0);
    float weightSum = 0.0;
    for (int i = 0; i < 8; i++) {
        float t = float(i) / 7.0;
        vec2 sampleUV = TexCoord + offset * t;
        float weight = 1.0 - t * 0.5;
        smear += texture(InputTexture, sampleUV) * weight;
        weightSum += weight;
    }
    smear /= weightSum;
    vec4 result = mix(original, smear, Swing * 0.7);
    float luminance = dot(result.rgb, vec3(0.299, 0.587, 0.114));
    result.rgb += result.rgb * luminance * GlowBoost * Swing;
    OutColor = result;
}
