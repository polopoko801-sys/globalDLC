package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.ColorHelper;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ShaderManager;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.injection.accesors.IGameRenderer;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.setting.impl.SettingGroup;
import thunder.hack.utility.render.Render3DEngine;

//Thanks to @0x3C50 for Shader rendering example
public class Shaders extends Module {
    public Shaders() {
        super("Shaders", Category.RENDER);
    }

    private final Setting<SettingGroup> select = new Setting<>("Select", new SettingGroup(false, 0));
    private final Setting<Boolean> hands = new Setting<>("Hands", true).addToGroup(select);
    private final Setting<Boolean> players = new Setting<>("Players", true).addToGroup(select);
    private final Setting<Boolean> self = new Setting<>("Self", true, v -> players.getValue()).addToGroup(select);
    private final Setting<Boolean> friends = new Setting<>("Friends", true).addToGroup(select);
    private final Setting<Boolean> crystals = new Setting<>("Crystals", true).addToGroup(select);
    private final Setting<Boolean> creatures = new Setting<>("Creatures", false).addToGroup(select);
    private final Setting<Boolean> monsters = new Setting<>("Monsters", false).addToGroup(select);
    private final Setting<Boolean> ambients = new Setting<>("Ambients", false).addToGroup(select);
    private final Setting<Boolean> others = new Setting<>("Others", false).addToGroup(select);

    public Setting<ShaderManager.Shader> mode = new Setting<>("Mode", ShaderManager.Shader.Default);
    public Setting<ShaderManager.Shader> handsMode = new Setting<>("HandsMode", ShaderManager.Shader.Default);

    public final Setting<Integer> maxRange = new Setting<>("MaxRange", 64, 16, 256, v -> players.getValue() || crystals.getValue() || friends.getValue() || creatures.getValue() || monsters.getValue() || ambients.getValue() || others.getValue());
    public final Setting<Float> factor = new Setting<>("GradientFactor", 2f, 0f, 20f, v -> mode.is(ShaderManager.Shader.Gradient) || handsMode.is(ShaderManager.Shader.Gradient));
    public final Setting<Float> gradient = new Setting<>("Gradient", 2f, 0f, 20f, v -> mode.is(ShaderManager.Shader.Gradient) || handsMode.is(ShaderManager.Shader.Gradient));
    public final Setting<Integer> alpha2 = new Setting<>("GradientAlpha", 170, 0, 255, v -> mode.is(ShaderManager.Shader.Gradient) || handsMode.is(ShaderManager.Shader.Gradient));
    public final Setting<Integer> lineWidth = new Setting<>("LineWidth", 2, 0, 500);
    public final Setting<Integer> quality = new Setting<>("Quality", 3, 0, 6);
    public final Setting<Integer> octaves = new Setting<>("SmokeOctaves", 10, 5, 30);
    public final Setting<Integer> fillAlpha = new Setting<>("FillAlpha", 170, 0, 255);
    public final Setting<Boolean> glow = new Setting<>("SmokeGlow", true);

    private final Setting<SettingGroup> colors = new Setting<>("Colors", new SettingGroup(false, 0));
    public final Setting<ColorSetting> outlineColor = new Setting<>("Outline", new ColorSetting(0x8800FF00)).addToGroup(colors);
    public final Setting<ColorSetting> outlineColor1 = new Setting<>("SmokeOutline", new ColorSetting(0x8800FF00), v -> mode.is(ShaderManager.Shader.Smoke) || handsMode.is(ShaderManager.Shader.Smoke)).addToGroup(colors);
    public final Setting<ColorSetting> outlineColor2 = new Setting<>("SmokeOutline2", new ColorSetting(0x8800FF00), v -> mode.is(ShaderManager.Shader.Smoke) || handsMode.is(ShaderManager.Shader.Smoke)).addToGroup(colors);
    public final Setting<ColorSetting> fillColor1 = new Setting<>("Fill", new ColorSetting(0x8800FF00)).addToGroup(colors);
    public final Setting<ColorSetting> fillColor2 = new Setting<>("SmokeFill", new ColorSetting(0x8800FF00)).addToGroup(colors);
    public final Setting<ColorSetting> fillColor3 = new Setting<>("SmokeFil2", new ColorSetting(0x8800FF00)).addToGroup(colors);
    public final Setting<ColorSetting> friendColor = new Setting<>("FriendColor", new ColorSetting(0xFF30FF00), v -> friends.getValue()).addToGroup(colors);

    /**
     * Colour of this entity's outline, ARGB, or 0 for "no outline".
     *
     * <p>1.21.11: the old satin post-process chain is gone, so the ESP is drawn by VANILLA's entity-outline
     * pass instead - the same one behind the glowing effect. It already renders through walls and is already
     * wired into the frame graph. MixinEntityRenderer writes this value into
     * {@code EntityRenderState.outlineColor} and vanilla does the rest. Gradient mode animates the colour
     * with the client accent, since a real per-pixel gradient needs the shader that no longer exists.
     */
    public int outlineColorFor(Entity entity) {
        if (!shouldRender(entity)) return 0;

        if (entity instanceof PlayerEntity player && Managers.FRIEND.isFriend(player))
            return ColorHelper.fullAlpha(friendColor.getValue().getColor());

        if (mode.is(ShaderManager.Shader.Gradient))
            return ColorHelper.fullAlpha(HudEditor.getColor(entity.getId() * Math.max(1, (int) (float) factor.getValue())).getRGB());

        return ColorHelper.fullAlpha(outlineColor.getValue().getColor());
    }

    public boolean shouldRender(Entity entity) {
        if (entity == null)
            return false;

        if (mc.player == null)
            return false;

        if (mc.player.squaredDistanceTo(entity.getEntityPos()) > maxRange.getPow2Value())
            return false;

        if (entity instanceof PlayerEntity) {
            if (entity == mc.player && !self.getValue())
                return false;
            if (Managers.FRIEND.isFriend((PlayerEntity) entity))
                return friends.getValue();
            return players.getValue();
        }

        if (entity instanceof EndCrystalEntity)
            return crystals.getValue();

        return switch (entity.getType().getSpawnGroup()) {
            case CREATURE, WATER_CREATURE -> creatures.getValue();
            case MONSTER -> monsters.getValue();
            case AMBIENT, WATER_AMBIENT -> ambients.getValue();
            default -> others.getValue();
        };
    }

    /**
     * Hands: the old path re-rendered the whole hand inside our 3D pass and let the satin shader chain turn that
     * copy into an outline. That chain does not exist on 1.21.11, so the copy itself landed on screen - a second,
     * offset sword. The hand outline now goes through the same real outline shader as ItemShader (mask = frame
     * before vs after the hand), so nothing is drawn twice. ItemShader wins when both are on.
     */
    public boolean wantsHandOutline() {
        return isEnabled() && hands.getValue() && !thunder.hack.utility.render.ItemOutlineRenderer.isBroken()
                && !(thunder.hack.core.manager.client.ModuleManager.itemShader != null && thunder.hack.core.manager.client.ModuleManager.itemShader.isEnabled());
    }

    public void onRender2D(net.minecraft.client.gui.DrawContext context) {
        if (!wantsHandOutline() || mc.player == null || mc.currentScreen != null) return;
        java.awt.Color c = handsMode.is(ShaderManager.Shader.Gradient) ? HudEditor.getColor(0) : outlineColor.getValue().getColorObject();
        thunder.hack.utility.render.ItemOutlineRenderer.render(c, Math.max(1, Math.min(12, lineWidth.getValue())), false, 0f, 0.5f, 0.25f,
                fillAlpha.getValue() / 255f * (fillColor1.getValue().getAlpha() / 255f));
    }

    @Override
    public void onDisable() {
        Managers.SHADER.reloadShaders();
    }
}
