package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import thunder.hack.features.modules.Module;
import thunder.hack.injection.accesors.IOverlayTexture;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;

import java.awt.Color;

/**
 * Port of Celestial's {@code HitColor}: changes the colour of the hurt flash on entities.
 *
 * <p>On 1.21.11 that flash is the red half (rows 0-7) of the game's 16x16 overlay texture, so those rows
 * are repainted with the chosen colour and re-uploaded whenever it changes; vanilla red (0xB2FF0000) comes
 * back on disable.
 *
 * <p>The entity shader does {@code mix(overlay.rgb, model.rgb, overlay.a)}: the texel's alpha is how much of
 * the MODEL survives, so an opaque colour meant no tint at all - that is why the first version "did not
 * work" with a fully opaque colour picked. The strength is its own slider now and the alpha is derived from
 * it (vanilla = 0.3).
 */
public class HitColor extends Module {
    public HitColor() {
        super("HitColor", Category.RENDER);
    }

    private static final int VANILLA = 0xB2FF0000;

    private final Setting<ColorSetting> hitColor = new Setting<>("Color", new ColorSetting(new Color(255, 0, 0)));
    private final Setting<Float> strength = new Setting<>("Strength", 0.5f, 0.05f, 1.0f);

    private int applied = VANILLA;

    @Override
    public void onRender2D(DrawContext context) {
        int alpha = Math.round(255f * (1f - strength.getValue()));
        int argb = alpha << 24 | (hitColor.getValue().getColor() & 0xFFFFFF);
        if (argb != applied) paint(argb);
    }

    @Override
    public void onDisable() {
        if (applied != VANILLA && mc.gameRenderer != null) paint(VANILLA);
    }

    private void paint(int argb) {
        NativeImageBackedTexture texture = ((IOverlayTexture) mc.gameRenderer.getOverlayTexture()).getTexture();
        NativeImage image = texture.getImage();
        if (image == null) return;
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 16; u++)
                image.setColorArgb(u, v, argb);
        texture.upload();
        applied = argb;
    }
}
