package thunder.hack.utility.render.core;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;

/**
 * Text that belongs to a place in the world - a name over a head, a distance under a marker, a damage
 * number at a hit.
 *
 * <p>World text is really screen text at a projected point, and the two things that make it look wrong
 * are both handled here. It has to shrink with distance or a far-away label covers half the screen, and
 * it has to disappear when its anchor goes behind the camera instead of smearing across the view - which
 * is what a projected point does the moment its depth leaves the 0..1 range.
 */
public final class CloudanTextRenderer {
    private CloudanTextRenderer() {
    }

    /** Distance at which text is drawn at its nominal size; closer is capped, further shrinks. */
    private static final double REFERENCE_DISTANCE = 8.0;

    /**
     * Draws centred text at a world position.
     *
     * @param scaleWithDistance false pins the size, for labels that must stay readable at any range
     * @return true when it was actually drawn
     */
    public static boolean draw(@NotNull DrawContext context, @NotNull String text, @NotNull Vec3d worldPos,
                               @NotNull Color color, float size, boolean scaleWithDistance, double maxDistance) {
        if (text.isEmpty()) return false;

        double distance = CloudanCullingSystem.distanceTo(worldPos);
        if (distance > maxDistance) {
            CloudanRenderCore.countCulled(1);
            return false;
        }

        Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(worldPos);
        // Depth outside 0..1 means the anchor is behind the near plane; its x/y are meaningless there.
        if (!(screen.z > 0 && screen.z < 1)) {
            CloudanRenderCore.countCulled(1);
            return false;
        }

        float scale = scaleWithDistance
                ? (float) Math.min(1.0, REFERENCE_DISTANCE / Math.max(1.0, distance))
                : 1f;

        FontRenderer font = fontFor(size * scale);
        float width = font.getStringWidth(text);

        // Fades out over the last fifth of its range rather than vanishing on one frame.
        float fade = (float) Math.min(1.0, (maxDistance - distance) / Math.max(1.0, maxDistance * 0.2));
        Color shown = Render2DEngine.injectAlpha(color, Math.round(color.getAlpha() * fade));

        font.drawString(context, text, (float) screen.x - width / 2f, (float) screen.y, shown.getRGB());
        CloudanRenderCore.countVisible(1);
        return true;
    }

    /** Text in the world with a dark rounded plate behind it, so it reads over any background. */
    public static void plate(@NotNull DrawContext context, @NotNull String text, @NotNull Vec3d worldPos,
                             @NotNull Color color, @NotNull Color background, float size, double maxDistance) {
        if (text.isEmpty()) return;

        double distance = CloudanCullingSystem.distanceTo(worldPos);
        if (distance > maxDistance) return;

        Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(worldPos);
        if (!(screen.z > 0 && screen.z < 1)) return;

        float scale = (float) Math.min(1.0, REFERENCE_DISTANCE / Math.max(1.0, distance));
        FontRenderer font = fontFor(size * scale);
        float width = font.getStringWidth(text);
        float height = font.getFontHeight(text);

        float x = (float) screen.x - width / 2f;
        float y = (float) screen.y;

        Render2DEngine.drawRound(context, x - 3f, y - 2f, width + 6f, height + 4f, 3f, background);
        font.drawString(context, text, x, y, color.getRGB());
        CloudanRenderCore.countVisible(1);
    }

    /** Picks the closest font the client has to the requested size. */
    private static FontRenderer fontFor(float size) {
        if (size <= 0.45f) return FontRenderers.sf_bold_micro;
        if (size <= 0.7f) return FontRenderers.sf_bold_mini;
        if (size <= 1.2f) return FontRenderers.sf_bold;
        return FontRenderers.sf_bold_big;
    }
}
