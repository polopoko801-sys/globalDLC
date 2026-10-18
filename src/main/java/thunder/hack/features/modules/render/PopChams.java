package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;

/**
 * 1.21.6+ stub. PopChams spawned a throwaway {@link net.minecraft.entity.player.PlayerEntity} clone and
 * rendered it through the removed Tessellator/BufferBuilder immediate path plus the reworked
 * PlayerEntityModel animateModel/setAngles API. The fake-player construction also broke (changed
 * PlayerEntity constructor + new abstract getGameMode()). The whole pipeline needs reimplementing on
 * RenderPipeline + the entity render-state system, so the feature is disabled (settings preserved).
 */
public final class PopChams extends Module {
    public PopChams() {
        super("PopChams", Category.RENDER);
    }

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Textured);
    private final Setting<Boolean> secondLayer = new Setting<>("SecondLayer", true);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0x8800FF00));
    private final Setting<Integer> ySpeed = new Setting<>("YSpeed", 0, -10, 10);
    private final Setting<Integer> aSpeed = new Setting<>("AlphaSpeed", 5, 1, 100);
    private final Setting<Float> rotSpeed = new Setting<>("RotationSpeed", 0.25f, 0f, 6f);

    private enum Mode {
        Simple, Textured
    }

    @Override
    public void onUpdate() {
    }

    @Override
    public void onRender3D(MatrixStack stack) {
    }
}