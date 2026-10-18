#version 150

// Item silhouette mask from the depth difference: pixels where the item wrote depth (DepthAfter closer than
// DepthBefore) become white. This is how the glow gets the item's real shape with no separate model re-render.
uniform sampler2D DepthBefore;
uniform sampler2D DepthAfter;

in vec2 TexCoord;
out vec4 OutColor;

void main() {
    float before = texture(DepthBefore, TexCoord).r;
    float after = texture(DepthAfter, TexCoord).r;
    float difference = before - after;
    float mask = step(0.0001, difference);
    OutColor = vec4(mask, mask, mask, mask);
}
