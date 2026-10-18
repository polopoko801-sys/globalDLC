package thunder.hack.features.modules.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.io.InputStream;
import java.util.Optional;

/**
 * Port of Celestial's {@code EnchantmentColor}: the enchantment glint on items and armour takes the
 * Rainbow / Client / Custom colour.
 *
 * <p>The glint shader just scrolls its texture over the item, so the two vanilla glint textures are
 * repainted in place: every pixel keeps its brightness and alpha and takes the chosen hue. Writing into the
 * existing GPU textures keeps their repeat/linear sampler, which the scrolling needs. Disabling writes the
 * original pixels back; a resource reload makes new textures, which are detected and repainted.
 */
public class EnchantmentColor extends Module {
    public EnchantmentColor() {
        super("EnchantmentColor", Category.RENDER);
    }

    private enum Mode {Rainbow, Client, Custom}

    private final Setting<Mode> colorMode = new Setting<>("Crumbs Color", Mode.Rainbow);
    private final Setting<ColorSetting> customColor = new Setting<>("Custom Enchantment", new ColorSetting(new Color(0xFFFFFF)), v -> colorMode.is(Mode.Custom));

    private static final Identifier[] GLINTS = {
            Identifier.ofVanilla("textures/misc/enchanted_glint_item.png"),
            Identifier.ofVanilla("textures/misc/enchanted_glint_armor.png")
    };

    private final Glint[] glints = new Glint[GLINTS.length];
    private long lastPaint;

    @Override
    public void onRender2D(DrawContext context) {
        Color color = switch (colorMode.getValue()) {
            case Rainbow -> Render2DEngine.rainbow(1, 1.0f, 1.0f);
            case Client -> HudEditor.getColor(0);
            case Custom -> customColor.getValue().getColorObject();
        };
        long now = System.currentTimeMillis();
        boolean animated = colorMode.getValue() != Mode.Custom;
        for (int i = 0; i < GLINTS.length; i++) {
            Glint g = glint(i);
            if (g == null) continue;
            if (g.painted != color.getRGB() && (!animated || now - lastPaint >= 50)) g.paint(color.getRGB());
        }
        if (animated && now - lastPaint >= 50) lastPaint = now;
    }

    @Override
    public void onDisable() {
        for (int i = 0; i < glints.length; i++) {
            Glint g = glints[i];
            if (g != null && g.texture == mc.getTextureManager().getTexture(GLINTS[i])) g.restore();
            glints[i] = null;
        }
    }

    private Glint glint(int i) {
        AbstractTexture texture = mc.getTextureManager().getTexture(GLINTS[i]);
        if (texture == null || texture.getGlTexture() == null) return null;
        if (glints[i] == null || glints[i].texture != texture) {
            if (glints[i] != null) glints[i].close();
            glints[i] = Glint.load(GLINTS[i], texture);
        }
        return glints[i];
    }

    private static final class Glint {
        final AbstractTexture texture;
        final NativeImage original;
        final NativeImage work;
        int painted = 0;

        Glint(AbstractTexture texture, NativeImage original) {
            this.texture = texture;
            this.original = original;
            this.work = new NativeImage(original.getWidth(), original.getHeight(), false);
        }

        static Glint load(Identifier id, AbstractTexture texture) {
            Optional<Resource> res = mc.getResourceManager().getResource(id);
            if (res.isEmpty()) return null;
            try (InputStream in = res.get().getInputStream()) {
                NativeImage img = NativeImage.read(in);
                if (img.getWidth() != texture.getGlTexture().getWidth(0) || img.getHeight() != texture.getGlTexture().getHeight(0)) {
                    img.close();
                    return null;
                }
                return new Glint(texture, img);
            } catch (Exception e) {
                return null;
            }
        }

        void paint(int rgb) {
            float r = (rgb >> 16 & 0xFF) / 255f, g = (rgb >> 8 & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
            for (int y = 0; y < original.getHeight(); y++) {
                for (int x = 0; x < original.getWidth(); x++) {
                    int p = original.getColorArgb(x, y);
                    float lum = Math.max(p >> 16 & 0xFF, Math.max(p >> 8 & 0xFF, p & 0xFF)) / 255f;
                    work.setColorArgb(x, y, (p & 0xFF000000)
                            | Math.round(lum * r * 255f) << 16 | Math.round(lum * g * 255f) << 8 | Math.round(lum * b * 255f));
                }
            }
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getGlTexture(), work);
            painted = rgb;
        }

        void restore() {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getGlTexture(), original);
            close();
        }

        void close() {
            original.close();
            work.close();
        }
    }
}
