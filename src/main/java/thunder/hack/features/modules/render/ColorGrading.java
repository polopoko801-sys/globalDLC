package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.ScreenCapture;

import java.awt.Color;

/**
 * ColorGrading: a real colour pass over the frame, not just a tint.
 *
 * <p>Custom core shaders are gone in 1.21.6+, so the grade is built out of blend modes over this frame's
 * snapshot ({@link ScreenCapture}):
 * <ol>
 *   <li>multiply the frame by itself ({@code dst * dst}) - darks deepen and the channels pull apart, which
 *       is exactly what saturation and contrast look like;</li>
 *   <li>mix the untouched frame back in with alpha, so the strength is adjustable instead of all-or-nothing;</li>
 *   <li>add a slice of the frame back on top, because squaring costs brightness.</li>
 * </ol>
 * Tint, brightness and vignette are drawn after that as plain overlays.
 */
public class ColorGrading extends Module {
    public ColorGrading() {
        super("ColorGrading", Category.RENDER);
    }

    private final Setting<Integer> saturation = new Setting<>("Saturation", 45, 0, 100);
    private final Setting<Integer> punch = new Setting<>("Punch", 35, 0, 100);
    private final Setting<ColorSetting> tint = new Setting<>("Tint", new ColorSetting(new Color(0, 0, 0, 0)));
    private final Setting<Integer> brightness = new Setting<>("Brightness", 0, -90, 90);
    private final Setting<Integer> vignette = new Setting<>("Vignette", 25, 0, 200);

    public void onRender2D(DrawContext context) {
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();

        int sat = saturation.getValue();
        if (sat > 0 && ScreenCapture.available()) {
            // 1) dst = dst * dst
            Render2DEngine.drawMultiplyRect(context, 0, 0, w, h, Color.WHITE);

            // 2) pull it back towards the original by (100 - sat) percent
            int keep = Math.round(255f * (1f - sat / 100f));
            if (keep > 0)
                Render2DEngine.drawScreenSnapshot(context, new Color(255, 255, 255, keep).getRGB(), false);

            // 3) squaring darkened everything - hand some of the frame back, additively
            int lift = Math.round(255f * (punch.getValue() / 100f));
            if (lift > 0)
                Render2DEngine.drawScreenSnapshot(context, new Color(lift, lift, lift, 255).getRGB(), true);
        }

        Color t = tint.getValue().getColorObject();
        if (t.getAlpha() > 0)
            Render2DEngine.drawRect(context, 0, 0, w, h, t);

        int b = brightness.getValue();
        if (b > 0)
            Render2DEngine.drawRect(context, 0, 0, w, h, new Color(255, 255, 255, b));
        else if (b < 0)
            Render2DEngine.drawRect(context, 0, 0, w, h, new Color(0, 0, 0, -b));

        int v = vignette.getValue();
        if (v > 0) {
            // cheap vignette: darker gradient bands hugging each screen edge
            Color edge = new Color(0, 0, 0, v);
            Color clear = new Color(0, 0, 0, 0);
            float band = Math.min(w, h) * 0.22f;
            Render2DEngine.horizontalGradient(context, 0, 0, band, h, edge, clear);
            Render2DEngine.horizontalGradient(context, w - band, 0, w, h, clear, edge);
            Render2DEngine.verticalGradient(context, 0, 0, w, band, edge, clear);
            Render2DEngine.verticalGradient(context, 0, h - band, w, h, clear, edge);
        }
    }
}
