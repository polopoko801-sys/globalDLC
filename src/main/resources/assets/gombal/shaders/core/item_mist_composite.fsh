#version 330

// Draws the premultiplied fire history onto the frame (blend ONE, ONE_MINUS_SRC_ALPHA): the fire licking
// around the item right now plus the burning trail it leaves, fading over the trail time.
uniform sampler2D HistorySampler;
uniform sampler2D CurSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = texture(HistorySampler, texCoord);
}
