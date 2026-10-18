package thunder.hack.features.modules.render;

import com.google.common.collect.Ordering;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector4d;
import org.lwjgl.opengl.GL11;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.player.FriendManager;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.features.hud.impl.PotionHud;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.misc.NameProtect;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.*;

public class NameTags extends Module {
    private final Map<RegistryKey<Enchantment>, String> encMap = new HashMap<>();

    public NameTags() {
        super("NameTags", Category.RENDER);
        encMap.put(Enchantments.BLAST_PROTECTION, "B");
        encMap.put(Enchantments.PROTECTION, "P");
        encMap.put(Enchantments.SHARPNESS, "S");
        encMap.put(Enchantments.EFFICIENCY, "E");
        encMap.put(Enchantments.UNBREAKING, "U");
        encMap.put(Enchantments.POWER, "PO");
        encMap.put(Enchantments.THORNS, "T");
    }

    /**
     * Hides the vanilla name floating over a player so only the Cloudan tag is left - two nicks stacked
     * on one head is the bug this setting exists for. It touches the world label and nothing else: chat,
     * tab, the scoreboard and inventory names are untouched.
     */
    public final Setting<Boolean> hideVanillaNames = new Setting<>("HideVanillaNames", true);
    private final Setting<Boolean> self = new Setting<>("Self", false);
    private final Setting<Float> scale = new Setting<>("Scale", 1f, 0.1f, 10f);
    private final Setting<Boolean> resize = new Setting<>("Resize", false);
    private final Setting<Float> height = new Setting<>("Height", 2f, 0.1f, 10f);
    /** Which side of the player the tag sits on. */
    public enum Side {Top, Bottom, Center, Left, Right}
    private final Setting<Side> side = new Setting<>("Side", Side.Top);
    private final Setting<Float> sideOffset = new Setting<>("SideOffset", 40f, 0f, 200f,
            v -> side.getValue() == Side.Left || side.getValue() == Side.Right);
    private final Setting<Boolean> gamemode = new Setting<>("Gamemode", false);
    private final Setting<Boolean> spawners = new Setting<>("SpawnerNameTag", false);
    private final Setting<Boolean> entityOwner = new Setting<>("EntityOwner", false);
    private final Setting<Boolean> ping = new Setting<>("Ping", false);
    private final Setting<Boolean> hp = new Setting<>("HP", true);
    private final Setting<Boolean> distance = new Setting<>("Distance", true);
    private final Setting<Boolean> pops = new Setting<>("TotemPops", true);
    private final Setting<OutlineColor> outline = new Setting<>("OutlineType", OutlineColor.New);
    private final Setting<OutlineColor> friendOutline = new Setting<>("FriendOutline", OutlineColor.None);
    private final Setting<ColorSetting> outlineColor = new Setting<>("OutlineColor", new ColorSetting(0x80000000));
    private final Setting<ColorSetting> friendOutlineColor = new Setting<>("FriendOutlineColor", new ColorSetting(0x80000000));
    private final Setting<Boolean> enchantss = new Setting<>("Enchants", true);
    private final Setting<Boolean> onlyHands = new Setting<>("OnlyHands", false, v -> enchantss.getValue());
    private final Setting<Boolean> funtimeHp = new Setting<>("FunTimeHp", false);
    private final Setting<Boolean> ignoreBots = new Setting<>("IgnoreBots", false);
    private final Setting<Boolean> potions = new Setting<>("Potions", true);
    private final Setting<Boolean> shulkers = new Setting<>("Shulkers", true);
    private final Setting<ColorSetting> fillColorA = new Setting<>("Fill", new ColorSetting(0x80000000));
    private final Setting<ColorSetting> fillColorF = new Setting<>("FriendFill", new ColorSetting(0x80000000));
    private final Setting<Font> font = new Setting<>("FontMode", Font.Fancy);
    private final Setting<Armor> armorMode = new Setting<>("ArmorMode", Armor.Full);
    /** Stack size on each item (53 on a pile of golden apples, 16 pearls) plus the vanilla durability bar. */
    private final Setting<Boolean> itemCount = new Setting<>("ItemCount", true, v -> armorMode.getValue() == Armor.Full);
    /** Durability in percent under every piece of the set and the held tools. */
    private final Setting<Boolean> armorDurability = new Setting<>("SetDurability", true, v -> armorMode.getValue() == Armor.Full);

    private static int durabilityPercent(ItemStack stack) {
        if (stack.getMaxDamage() <= 0) return 100;
        return Math.round((stack.getMaxDamage() - stack.getDamage()) * 100f / stack.getMaxDamage());
    }

    private static Color durabilityColor(int percent) {
        // red -> yellow -> green, continuous rather than three hard bands
        float t = Math.clamp(percent / 100f, 0f, 1f);
        return Color.getHSBColor(t * 0.33f, 0.85f, 1f);
    }
    private final Setting<Health> health = new Setting<>("Health", Health.Number);


    public void onRender2D(DrawContext context) {
        if (mc.options.hudHidden) return;
        for (PlayerEntity ent : mc.world.getPlayers()) {
            if (ent == mc.player && (mc.options.getPerspective().isFirstPerson() || !self.getValue())) continue;
            if (getEntityPing(ent) <= 0 && ignoreBots.getValue()) continue;

            double x = ent.lastX + (ent.getX() - ent.lastX) * Render3DEngine.getTickDelta();
            double y = ent.lastY + (ent.getY() - ent.lastY) * Render3DEngine.getTickDelta();
            double z = ent.lastZ + (ent.getZ() - ent.lastZ) * Render3DEngine.getTickDelta();
            float scale = resize.getValue() ? this.scale.getValue() / mc.player.distanceTo(ent) : this.scale.getValue();
            // Side chooses the world anchor height: Top above the head, Center at the chest, Bottom at the feet.
            // Left/Right keep the head anchor and shift on screen (handled after projection).
            double anchorY = switch (side.getValue()) {
                case Bottom -> y - 0.1;
                case Center -> y + ent.getHeight() * 0.5;
                default -> y + height.getValue();
            };
            Vec3d vector = new Vec3d(x, anchorY, z);

            Vector4d position = null;

            vector = Render3DEngine.worldSpaceToScreenSpace(vector);
            if (vector.z > 0 && vector.z < 1) {
                position = new Vector4d(vector.x, vector.y, vector.z, 0);
                position.x = Math.min(vector.x, position.x);
                position.y = Math.min(vector.y, position.y);
                position.z = Math.max(vector.x, position.z);
            }

            String final_string = "";

            if (ping.getValue()) final_string += getPingColor(getEntityPing(ent)) + getEntityPing(ent) + "ms " + Formatting.WHITE;
            if (gamemode.getValue()) final_string += translateGamemode(getEntityGamemode(ent)) + " ";

            if (FriendManager.friends.stream().anyMatch(i -> i.contains(ent.getDisplayName().getString())) && NameProtect.hideFriends.getValue() && ModuleManager.nameProtect.isEnabled()) {
                final_string += NameProtect.getCustomName() + " ";
            } else {
                final_string += ent.getDisplayName().getString() + " ";
            }

            if (hp.getValue() && health.is(Health.Number)) {
                final_string += getHealthColor(getHealth(ent)) + round2(getHealth(ent)) + " ";
            }

            if (distance.getValue()) final_string += String.format("%.1f", mc.player.distanceTo(ent)) + "m ";
            if (pops.getValue() && Managers.COMBAT.getPops(ent) != 0)
                final_string += (Formatting.RESET + "" + Managers.COMBAT.getPops(ent));

            if (position != null) {
                // Left/Right push the whole tag sideways on screen, scaled a little by distance so it stays
                // beside the player rather than drifting far off at range.
                double sideShift = side.getValue() == Side.Left ? -sideOffset.getValue()
                        : side.getValue() == Side.Right ? sideOffset.getValue() : 0;
                sideShift *= scale;
                double posX = position.x + sideShift;
                double posY = position.y;
                double endPosX = position.z + sideShift;
                double maxEnchantY = 0;

                float diff = (float) (endPosX - posX) / 2;
                float textWidth;

                if (font.getValue() == Font.Fancy) textWidth = (FontRenderers.sf_bold.getStringWidth(final_string) * 1);
                else textWidth = mc.textRenderer.getWidth(final_string);

                float tagX = (float) ((posX + diff - textWidth / 2) * 1);

                ArrayList<ItemStack> stacks = new ArrayList<>();

                if (armorMode.getValue() != Armor.Durability) stacks.add(ent.getOffHandStack());

                stacks.add(thunder.hack.utility.player.InventoryUtility.getArmor(ent).get(0));
                stacks.add(thunder.hack.utility.player.InventoryUtility.getArmor(ent).get(1));
                stacks.add(thunder.hack.utility.player.InventoryUtility.getArmor(ent).get(2));
                stacks.add(thunder.hack.utility.player.InventoryUtility.getArmor(ent).get(3));

                if (armorMode.getValue() != Armor.Durability) stacks.add(ent.getMainHandStack());

                context.getMatrices().pushMatrix();
                context.getMatrices().translate((float)(tagX - 2 + (textWidth + 4) / 2f), (float)( (float) (posY - 13f) + 6.5f));
                context.getMatrices().scale((float)(scale), (float)( scale));
                context.getMatrices().translate((float)(-(tagX - 2 + (textWidth + 4) / 2f)), (float)( -(float) ((posY - 13f) + 6.5f)));

                float item_offset = 0;
                float durLift = armorMode.getValue() == Armor.Full && armorDurability.getValue() ? 10f : 0f; // room for the % line: enchants move up
                if (armorMode.getValue() != Armor.None) for (ItemStack armorComponent : stacks) {
                    if (!armorComponent.isEmpty()) {
                        if (armorMode.getValue() == Armor.Full) {
                            context.getMatrices().pushMatrix();
                            context.getMatrices().translate((float)(posX - 55 + item_offset), (float)( (float) (posY - 33f)));
                            context.getMatrices().scale((float)(1.1f), (float)( 1.1f));
                            thunder.hack.utility.render.RSCompat.noop();
                            context.drawItem(armorComponent, 0, 0);
                            // Stack count ("53" on a pile of golden apples) and the vanilla durability bar.
                            if (itemCount.getValue())
                                context.drawStackOverlay(mc.textRenderer, armorComponent, 0, 0);
                            context.getMatrices().popMatrix();

                            // Durability as a number under every damageable piece - the bar alone is hard to
                            // read on a tag a few pixels high, and "how worn is his set" is the question.
                            // Drawn ABOVE the piece in the client font. The first version sat in the 5px gap under
                            // the icon at 0.6 scale - on a scaled-down tag that was a smudge nobody could read.
                            if (armorDurability.getValue() && armorComponent.isDamageable()) {
                                int percent = durabilityPercent(armorComponent);
                                String label = percent + "%";
                                float lw = FontRenderers.sf_bold_mini.getStringWidth(label);
                                FontRenderers.sf_bold_mini.drawString(context, label,
                                        posX - 55 + item_offset + 9.9f - lw / 2f, (float) (posY - 33f - 8f),
                                        durabilityColor(percent).getRGB());
                            }
                        } else {
                            context.getMatrices().pushMatrix();
                            context.getMatrices().translate((float)(posX - 35 + item_offset), (float)( (float) (posY - 20)));
                            context.getMatrices().scale((float)(0.7f), (float)( 0.7f));

                            float durability = armorComponent.getMaxDamage() - armorComponent.getDamage();
                            int percent = (int) ((durability / (float) armorComponent.getMaxDamage()) * 100F);

                            Color color;
                            if (percent < 33) {
                                color = Color.RED;
                            } else if (percent > 33 && percent < 66) {
                                color = Color.YELLOW;
                            } else {
                                color = Color.GREEN;
                            }
                            context.drawText(mc.textRenderer, percent + "%", 0, 0, color.getRGB(), false);
                            context.getMatrices().popMatrix();
                        }

                        float enchantmentY = 0;

                        ItemEnchantmentsComponent enchants = EnchantmentHelper.getEnchantments(armorComponent);


                        if (enchantss.getValue()) {
                            if (!onlyHands.getValue() || (armorComponent == ent.getOffHandStack() || armorComponent == ent.getMainHandStack())) {
                                for (RegistryKey<Enchantment> enchantment : encMap.keySet()) {
                                    if (enchants.getEnchantments().contains(mc.world.getRegistryManager().getOrThrow(Enchantments.PROTECTION.getRegistryRef()).getOrThrow(enchantment))) {
                                        String id = encMap.get(enchantment);
                                        int level = enchants.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.PROTECTION.getRegistryRef()).getOrThrow(enchantment));
                                        String encName = id + level;

                                        if (font.getValue() == Font.Fancy) {
                                            FontRenderers.sf_bold.drawString(context, encName, posX - 50 + item_offset, (float) posY - 45 - durLift + enchantmentY, -1);
                                        } else {
                                            context.getMatrices().pushMatrix();
                                            context.getMatrices().translate((float)((posX - 50f + item_offset)), (float)( (posY - 45f - durLift + enchantmentY)));
                                            context.drawText(mc.textRenderer, encName, 0, 0, -1, false);
                                            context.getMatrices().popMatrix();
                                        }
                                        enchantmentY -= 8;
                                        if (maxEnchantY > enchantmentY)
                                            maxEnchantY = enchantmentY;
                                    }
                                }
                            }
                        }
                    }
                    item_offset += 18f;
                }

                Color color = Managers.FRIEND.isFriend(ent) ? fillColorF.getValue().getColorObject() : fillColorA.getValue().getColorObject();

                OutlineColor cl = Managers.FRIEND.isFriend(ent) ? friendOutline.getValue() : outline.getValue();

                if (cl == OutlineColor.New)
                    Render2DEngine.drawRectWithOutline(context, tagX - 2, (float) (posY - 13f), textWidth + 4, 11, color, outlineColor.getValue().getColorObject());
                else
                    Render2DEngine.drawRect(context, tagX - 2, (float) (posY - 13f), textWidth + 4, 11, color);

                if (Managers.TELEMETRY.getOnlinePlayers().contains(ent.getGameProfile().name())) {
                    Render2DEngine.drawRect(context, tagX - 14, (float) (posY - 13f), 12, 11, color.brighter().brighter());
                    thunder.hack.utility.render.RSCompat.enableBlend();
                    thunder.hack.utility.render.RSCompat.noop();
                    Color lColor = HudEditor.getColor(0);
                    thunder.hack.utility.render.RSCompat.setShaderColor(lColor.getRed() / 255f, lColor.getGreen() / 255f, lColor.getBlue() / 255f, 1f);
                    thunder.hack.utility.render.RSCompat.noop();
                    Render2DEngine.renderTexture(context, tagX - 13, (float) (posY - 12.5f), 10, 10, 0, 0, 256, 256, 256, 256);
                    thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, 1f);
                    thunder.hack.utility.render.RSCompat.disableBlend();
                }

                switch (cl) {
                    case None, New -> {

                    }
                    case Sync -> {
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), textWidth + 6, 1, HudEditor.getColor(270));
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 3f), textWidth + 6, 1, HudEditor.getColor(0));
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), 1, 11, HudEditor.getColor(180));
                        Render2DEngine.drawRect(context, tagX + textWidth + 2, (float) (posY - 14f), 1, 11, HudEditor.getColor(90));
                    }
                    case Custom -> {
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), textWidth + 6, 1, Managers.FRIEND.isFriend(ent) ? friendOutlineColor.getValue().getColorObject() : outlineColor.getValue().getColorObject());
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 3f), textWidth + 6, 1, Managers.FRIEND.isFriend(ent) ? friendOutlineColor.getValue().getColorObject() : outlineColor.getValue().getColorObject());
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), 1, 11, Managers.FRIEND.isFriend(ent) ? friendOutlineColor.getValue().getColorObject() : outlineColor.getValue().getColorObject());
                        Render2DEngine.drawRect(context, tagX + textWidth + 2, (float) (posY - 14f), 1, 11, Managers.FRIEND.isFriend(ent) ? friendOutlineColor.getValue().getColorObject() : outlineColor.getValue().getColorObject());
                    }
                }


                if (font.getValue() == Font.Fancy) {
                    FontRenderers.sf_bold.drawString(context, final_string, tagX, (float) posY - 10, -1);
                } else {
                    context.getMatrices().pushMatrix();
                    context.getMatrices().translate((float)(tagX), (float)( ((float) posY - 11)));
                    context.drawText(mc.textRenderer, final_string, 0, 0, -1, false);
                    context.getMatrices().popMatrix();
                }

                if (!health.is(Health.Number)) {
                    int i = MathHelper.ceil(ent.getHealth());
                    float f = (float) ent.getAttributeValue(EntityAttributes.MAX_HEALTH);
                    int p = MathHelper.ceil(ent.getAbsorptionAmount());
                    context.getMatrices().pushMatrix();
                    context.getMatrices().translate((float)(posX - 44), (float)( posY));
                    context.getMatrices().scale((float)(1.1f), (float)( 1.1f));
                    renderHealthBar(context, ent, f, i, p);
                    context.getMatrices().popMatrix();
                }

                if (potions.getValue())
                    renderStatusEffectOverlay(context, (float) posX, (float) (posY + maxEnchantY - 60), ent);

                Item handItem = ent.getMainHandStack().getItem();

                if (shulkers.getValue())
                    renderShulkerToolTip(context, (int) posX - 90, (int) posY - 120, (handItem instanceof BlockItem bi) && (bi.getBlock() instanceof ShulkerBoxBlock) ? ent.getMainHandStack() : ent.getOffHandStack());
                context.getMatrices().popMatrix();
            }
        }

        if (spawners.getValue()) drawSpawnerNameTag(context);
        if (entityOwner.getValue()) drawEntityOwner(context);
    }

    private void drawSpawnerNameTag(DrawContext context) {
        for (BlockEntity blockEntity : StorageEsp.getBlockEntities()) {
            if (blockEntity instanceof MobSpawnerBlockEntity spawner) {
                Vec3d vector = new Vec3d(spawner.getPos().getX() + 0.5, spawner.getPos().getY() + 1.5, spawner.getPos().getZ() + 0.5);
                Vector4d position = null;
                vector = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(vector.x, vector.y, vector.z));
                if (vector.z > 0 && vector.z < 1) {
                    position = new Vector4d(vector.x, vector.y, vector.z, 0);
                    position.x = Math.min(vector.x, position.x);
                    position.y = Math.min(vector.y, position.y);
                    position.z = Math.max(vector.x, position.z);
                }
                if (spawner.getLogic() == null || spawner.getLogic().getRenderedEntity(mc.world, spawner.getPos()) == null)
                    continue;
                String final_string = spawner.getLogic().getRenderedEntity(mc.world, spawner.getPos()).getName().getString() + " " + String.format("%.1f", ((float) spawner.getLogic().spawnDelay / 20f)) + "s";

                if (spawner.getLogic().getRotation() == spawner.getLogic().getLastRotation() && spawner.getLogic().getRotation() == 0f && (float) spawner.getLogic().spawnDelay / 20f == 1f)
                    final_string = spawner.getLogic().getRenderedEntity(mc.world, spawner.getPos()).getName().getString() + " loot!";


                if (position != null) {
                    double posX = position.x;
                    double posY = position.y;
                    double endPosX = position.z;

                    float diff = (float) (endPosX - posX) / 2;
                    float textWidth = (FontRenderers.sf_bold.getStringWidth(final_string) * 1);
                    float tagX = (float) ((posX + diff - textWidth / 2) * 1);

                    Render2DEngine.drawRect(context, tagX - 2, (float) (posY - 13f), textWidth + 4, 11, fillColorA.getValue().getColorObject());

                    switch (outline.getValue()) {
                        case None -> {

                        }
                        case Sync -> {
                            Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), textWidth + 6, 1, HudEditor.getColor(270));
                            Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 3f), textWidth + 6, 1, HudEditor.getColor(0));
                            Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), 1, 11, HudEditor.getColor(180));
                            Render2DEngine.drawRect(context, tagX + textWidth + 2, (float) (posY - 14f), 1, 11, HudEditor.getColor(90));
                        }
                        case Custom -> {
                            Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), textWidth + 6, 1, outlineColor.getValue().getColorObject());
                            Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 3f), textWidth + 6, 1, outlineColor.getValue().getColorObject());
                            Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), 1, 11, outlineColor.getValue().getColorObject());
                            Render2DEngine.drawRect(context, tagX + textWidth + 2, (float) (posY - 14f), 1, 11, outlineColor.getValue().getColorObject());
                        }
                    }


                    FontRenderers.sf_bold.drawString(context, final_string, tagX, (float) posY - 10, -1);
                }
            }
        }
    }

    public void drawEntityOwner(DrawContext context) {
        for (Entity ent : mc.world.getEntities()) {
            String ownerName = "";
            if (ent instanceof ProjectileEntity pe) {
                if (pe.getOwner() != null) ownerName = pe.getOwner().getDisplayName().getString();
            } else if (ent instanceof HorseEntity he) {
                if ((he.getOwnerReference() == null ? null : he.getOwnerReference().getUuid()) != null) ownerName = (he.getOwnerReference() == null ? null : he.getOwnerReference().getUuid()).toString();
            } else if (ent instanceof TameableEntity te && te.isTamed() && te.getOwner() != null) {
                ownerName = te.getOwner().getDisplayName().getString();
            } else continue;

            String final_string = "Owned by " + ownerName;
            double x = ent.lastX + (ent.getX() - ent.lastX) * Render3DEngine.getTickDelta();
            double y = ent.lastY + (ent.getY() - ent.lastY) * Render3DEngine.getTickDelta();
            double z = ent.lastZ + (ent.getZ() - ent.lastZ) * Render3DEngine.getTickDelta();
            Vec3d vector = new Vec3d(x, y + 2, z);
            Vector4d position = null;
            vector = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(vector.x, vector.y, vector.z));
            if (vector.z > 0 && vector.z < 1) {
                position = new Vector4d(vector.x, vector.y, vector.z, 0);
                position.x = Math.min(vector.x, position.x);
                position.y = Math.min(vector.y, position.y);
                position.z = Math.max(vector.x, position.z);
            }

            if (position != null) {
                double posX = position.x;
                double posY = position.y;
                double endPosX = position.z;

                float diff = (float) (endPosX - posX) / 2;
                float textWidth = (FontRenderers.sf_bold.getStringWidth(final_string) * 1);
                float tagX = (float) ((posX + diff - textWidth / 2) * 1);

                Render2DEngine.drawRect(context, tagX - 2, (float) (posY - 13f), textWidth + 4, 11, fillColorA.getValue().getColorObject());

                switch (outline.getValue()) {
                    case None -> {

                    }
                    case Sync -> {
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), textWidth + 6, 1, HudEditor.getColor(270));
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 3f), textWidth + 6, 1, HudEditor.getColor(0));
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), 1, 11, HudEditor.getColor(180));
                        Render2DEngine.drawRect(context, tagX + textWidth + 2, (float) (posY - 14f), 1, 11, HudEditor.getColor(90));
                    }
                    case Custom -> {
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), textWidth + 6, 1, outlineColor.getValue().getColorObject());
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 3f), textWidth + 6, 1, outlineColor.getValue().getColorObject());
                        Render2DEngine.drawRect(context, tagX - 3, (float) (posY - 14f), 1, 11, outlineColor.getValue().getColorObject());
                        Render2DEngine.drawRect(context, tagX + textWidth + 2, (float) (posY - 14f), 1, 11, outlineColor.getValue().getColorObject());
                    }
                }
                FontRenderers.sf_bold.drawString(context, final_string, tagX, (float) posY - 10, -1);
            }
        }
    }

    public static int getEntityPing(PlayerEntity entity) {
        if (mc.getNetworkHandler() == null) return 0;
        PlayerListEntry playerListEntry = mc.getNetworkHandler().getPlayerListEntry(entity.getUuid());
        if (playerListEntry == null) return 0;
        return playerListEntry.getLatency();
    }

    public static GameMode getEntityGamemode(PlayerEntity entity) {
        if (entity == null) return null;
        PlayerListEntry playerListEntry = mc.getNetworkHandler().getPlayerListEntry(entity.getUuid());
        return playerListEntry == null ? null : playerListEntry.getGameMode();
    }

    private String translateGamemode(GameMode gamemode) {
        if (gamemode == null) return "[BOT]";
        return switch (gamemode) {
            case SURVIVAL -> "[S]";
            case CREATIVE -> "[C]";
            case SPECTATOR -> "[SP]";
            case ADVENTURE -> "[A]";
        };
    }

    public float getHealth(PlayerEntity ent) {
        // Первый в комьюнити хп резольвер. Правда, еж?
        if ((mc.getNetworkHandler() != null && mc.getNetworkHandler().getServerInfo() != null && mc.getNetworkHandler().getServerInfo().address.contains("funtime") || funtimeHp.getValue())) {
            ScoreboardObjective scoreBoard = null;
            String resolvedHp = "";
            if ((mc.world.getScoreboard()).getObjectiveForSlot(ScoreboardDisplaySlot.BELOW_NAME) != null) {
                scoreBoard = (mc.world.getScoreboard()).getObjectiveForSlot(ScoreboardDisplaySlot.BELOW_NAME);
                if (scoreBoard != null) {
                    ReadableScoreboardScore readableScoreboardScore = mc.world.getScoreboard().getScore(ent, scoreBoard);
                    MutableText text2 = ReadableScoreboardScore.getFormattedScore(readableScoreboardScore, scoreBoard.getNumberFormatOr(StyledNumberFormat.EMPTY));
                    resolvedHp = text2.getString();
                }
            }
            float numValue = 0;
            try {
                numValue = Float.parseFloat(resolvedHp);
            } catch (NumberFormatException ignored) {
            }
            return numValue;
        } else return ent.getHealth() + ent.getAbsorptionAmount();
    }

    private void renderHealthBar(DrawContext context, PlayerEntity player, float maxHealth, int lastHealth, int absorption) {
        int i = MathHelper.ceil((double) maxHealth / 2.0);
        int j = MathHelper.ceil((double) absorption / 2.0);
        int k = i * 2;
        int cont = 0;

        for (int l = i + j - 1; l >= 0; --l) {
            int n = l % 10;
            int o = n * 8;

            if (cont < 10) {
                drawHeart(context, HeartType.CONTAINER, o, false, player);
                cont++;
            }

            int q = l * 2;
            if (q < lastHealth) {
                drawHeart(context, HeartType.NORMAL, o, q + 1 == lastHealth, player);
            }

            if (l >= i) {
                int r = q - k;
                if (q - k < absorption) {
                    context.getMatrices().pushMatrix();
                    context.getMatrices().translate((float)(0), (float)( 0));
                    drawHeart(context, HeartType.ABSORBING, o, r + 1 == absorption, player);
                    context.getMatrices().popMatrix();
                }
            }
        }

    }

    private void drawHeart(DrawContext context, HeartType type, int x, boolean half, PlayerEntity player) {
        if (health.is(Health.Dots)) {

            Color color = Managers.FRIEND.isFriend(player) ? fillColorF.getValue().getColorObject() : fillColorA.getValue().getColorObject();
            if (type == HeartType.CONTAINER) {
                Render2DEngine.drawRect(context, x, 0, 7, 3, color);
            } else if (type == HeartType.NORMAL) {
                if (half) {
                    Render2DEngine.drawRect(context, x, 0, 3, 3, getHealthColor2(player.getHealth() + player.getAbsorptionAmount()));
                    Render2DEngine.drawRect(context, x + 3, 0, 4, 3, color);
                } else {
                    Render2DEngine.drawRect(context, x, 0, 7, 3, getHealthColor2(player.getHealth() + player.getAbsorptionAmount()));
                }
            }
        } else context.drawGuiTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, type.getTexture(half), x, 0, 9, 9);
    }

    private enum HeartType {
        CONTAINER(Identifier.of("hud/heart/container"), Identifier.of("hud/heart/container")), NORMAL(Identifier.of("hud/heart/full"), Identifier.of("hud/heart/half")), ABSORBING(Identifier.of("hud/heart/absorbing_full"), Identifier.of("hud/heart/absorbing_half"));

        private final Identifier fullTexture;
        private final Identifier halfTexture;

        HeartType(Identifier fullTexture, Identifier halfTexture) {
            this.fullTexture = fullTexture;
            this.halfTexture = halfTexture;
        }

        public Identifier getTexture(boolean half) {
            return half ? halfTexture : fullTexture;
        }
    }

    public @NotNull String getHealthColor(float health) {
        if (health <= 15 && health > 7) return Formatting.YELLOW + "";
        if (health > 15) return Formatting.GREEN + "";
        return Formatting.RED + "";
    }

    public @NotNull String getPingColor(int ping) {
        if (ping <= 60) return Formatting.GREEN + "";
        if (ping > 60 && ping < 120) return Formatting.YELLOW + "";
        return Formatting.RED + "";
    }

    private @NotNull Color getHealthColor2(float health) {
        if (health <= 15 && health > 7) return Color.YELLOW;
        if (health > 15) return Color.GREEN;
        return Color.RED;
    }

    public static float round2(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 1f;
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(1, RoundingMode.HALF_UP);
        return bd.floatValue();
    }

    private void renderStatusEffectOverlay(DrawContext context, float x, float y, PlayerEntity player) {
        ArrayList<StatusEffectInstance> effects = new ArrayList<>(player.getStatusEffects());
        if (effects.isEmpty()) return;
        x += effects.size() * 12.5f;
        thunder.hack.utility.render.RSCompat.enableBlend();
        thunder.hack.utility.render.RSCompat.defaultBlendFunc();
        for (StatusEffectInstance statusEffectInstance : Ordering.natural().reverse().sortedCopy(effects)) {
            x -= 25;
            String power = "";
            switch (statusEffectInstance.getAmplifier()) {
                case 0 -> power = "I";
                case 1 -> power = "II";
                case 2 -> power = "III";
                case 3 -> power = "IV";
                case 4 -> power = "V";
            }

            context.getMatrices().pushMatrix();
            context.getMatrices().translate((float)(x), (float)( y));
            context.drawGuiTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, net.minecraft.client.gui.hud.InGameHud.getEffectTexture(statusEffectInstance.getEffectType()), 0, 0, 18, 18);
            FontRenderers.sf_bold_mini.drawCenteredString(context, PotionHud.getDuration(statusEffectInstance), 9, -8, -1);
            FontRenderers.categories.drawCenteredString(context, power, 9, -16, -1);
            context.getMatrices().popMatrix();

        }
        thunder.hack.utility.render.RSCompat.disableBlend();
    }

    public boolean renderShulkerToolTip(DrawContext context, int offsetX, int offsetY, ItemStack stack) {
        try {
            ContainerComponent compoundTag = stack.get(DataComponentTypes.CONTAINER);
            if (compoundTag == null) return false;

            float[] colors = new float[]{1F, 1F, 1F};
            Item focusedItem = stack.getItem();
            if (focusedItem instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                try {
                    Color c = new Color(Objects.requireNonNull(((ShulkerBoxBlock) bi.getBlock()).getColor()).getEntityColor());
                    colors = new float[]{c.getRed() / 255f, c.getGreen() / 255f, c.getRed() / 255f, c.getAlpha() / 255f};
                } catch (NullPointerException npe) {
                    colors = new float[]{1F, 1F, 1F};
                }
            } else {
                return false;
            }
            draw(context, compoundTag.stream().toList(), offsetX, offsetY, colors);
        } catch (Exception ignore) {
            return false;
        }
        return true;
    }

    private void draw(DrawContext context, List<ItemStack> itemStacks, int offsetX, int offsetY, float[] colors) {
        thunder.hack.utility.render.RSCompat.disableDepthTest();
        // no raw glClear: GUI draws are deferred on 1.21.11, so this cleared the depth of whatever pass was bound at that moment

        offsetX += 8;
        offsetY -= 82;

        drawBackground(context, offsetX, offsetY, colors);

        thunder.hack.utility.render.RSCompat.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        thunder.hack.utility.render.RSCompat.noop();
        int row = 0;
        int i = 0;
        for (ItemStack itemStack : itemStacks) {
            context.drawItem(itemStack, offsetX + 8 + i * 18, offsetY + 7 + row * 18);
            context.drawItem(itemStack, offsetX + 8 + i * 18, offsetY + 7 + row * 18);
            i++;
            if (i >= 9) {
                i = 0;
                row++;
            }
        }
        thunder.hack.utility.render.RSCompat.noop();
        thunder.hack.utility.render.RSCompat.enableDepthTest();
    }

    private void drawBackground(DrawContext context, int x, int y, float[] colors) {
        thunder.hack.utility.render.RSCompat.disableBlend();
        thunder.hack.utility.render.RSCompat.setShaderColor(colors[0], colors[1], colors[2], 1F);
        thunder.hack.utility.render.RSCompat.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        thunder.hack.utility.render.RSCompat.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, TextureStorage.container, x, y, 0, 0, 176, 67, 176, 67);
        thunder.hack.utility.render.RSCompat.enableBlend();
    }

    /**
     * A live preview for the ClickGUI: draws your own player with a sample name tag above, honouring the current
     * side / font / colour / armour settings, so you can see how the tag will look before closing the menu.
     * Called from the module panel (ModuleButton) when NameTags is open.
     */
    public void drawGuiPreview(DrawContext context, float boxX, float boxY, float boxW, float boxH) {
        if (mc.player == null) return;

        // panel background for the preview
        Render2DEngine.drawRound(context, boxX, boxY, boxW, boxH, 6f, new Color(14, 14, 20, 230));

        float cx = boxX + boxW / 2f;
        int size = (int) (boxH * 0.42f);
        int ex1 = (int) (cx - boxW * 0.28f), ey1 = (int) (boxY + boxH * 0.30f);
        int ex2 = (int) (cx + boxW * 0.28f), ey2 = (int) (boxY + boxH - 8);
        try {
            net.minecraft.client.gui.screen.ingame.InventoryScreen.drawEntity(
                    context, ex1, ey1, ex2, ey2, size, 0.0625f, cx, (ey1 + ey2) / 2f, mc.player);
        } catch (Exception ignored) {
        }

        // sample tag text using the live settings
        String tag = "";
        if (ping.getValue()) tag += Formatting.GREEN + "12ms " + Formatting.WHITE;
        tag += mc.player.getName().getString() + " ";
        if (hp.getValue()) tag += getHealthColor(20f) + "20 ";
        if (distance.getValue()) tag += "4.2m";

        float tw = FontRenderers.sf_bold.getStringWidth(tag);
        // place the tag per the Side setting, relative to the model box
        float tagY = switch (side.getValue()) {
            case Bottom -> ey2 - 4;
            case Center -> (ey1 + ey2) / 2f;
            default -> ey1 - 6;
        };
        float tagX = cx - tw / 2f;
        if (side.getValue() == Side.Left) tagX = ex1 - tw - 4;
        else if (side.getValue() == Side.Right) tagX = ex2 + 4;

        Render2DEngine.drawRound(context, tagX - 2, tagY - 2, tw + 4, 11, 2f,
                new Color(0, 0, 0, 150));
        FontRenderers.sf_bold.drawString(context, tag, tagX, tagY, HudEditor.getColor(0).getRGB());

        // armour row, like the real tag
        if (armorMode.getValue() != Armor.None) {
            java.util.List<ItemStack> armor = thunder.hack.utility.player.InventoryUtility.getArmor(mc.player);
            float ax = cx - (4 * 9) + 2;
            for (int i = 3; i >= 0; i--) {
                ItemStack st = armor.get(i);
                if (st != null && !st.isEmpty()) context.drawItem(st, (int) ax, (int) (boxY + 4));
                ax += 18;
            }
        }
    }

    public enum Font {
        Fancy, Fast
    }

    public enum Armor {
        None, Full, Durability
    }

    public enum Health {
        Number, Hearts, Dots
    }

    private enum OutlineColor {
        Sync, Custom, None, New
    }
}