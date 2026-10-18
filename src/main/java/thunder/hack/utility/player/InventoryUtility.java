package thunder.hack.utility.player;

import net.minecraft.block.Block;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.NotNull;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.injection.accesors.IInteractionManager;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static thunder.hack.features.modules.Module.mc;

public final class InventoryUtility {
    private static int cachedSlot = -1;

    // 1.21.6+ porting helper: PlayerInventory.armor / LivingEntity.getArmorItems() were removed.
    // Returns the four equipped armor stacks in vanilla order [feet, legs, chest, head].
    public static List<ItemStack> getArmor(net.minecraft.entity.LivingEntity e) {
        return List.of(
                e.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET),
                e.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS),
                e.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST),
                e.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD));
    }

    // 1.21.6+ porting helpers: ArmorItem/ElytraItem were removed in favor of data components.
    public static net.minecraft.entity.EquipmentSlot getArmorSlot(ItemStack stack) {
        net.minecraft.component.type.EquippableComponent eq = stack.get(DataComponentTypes.EQUIPPABLE);
        return eq == null ? null : eq.slot();
    }

    public static boolean isArmor(ItemStack stack) {
        net.minecraft.entity.EquipmentSlot slot = getArmorSlot(stack);
        return slot != null && slot.isArmorSlot();
    }

    public static boolean isElytra(ItemStack stack) {
        return stack.contains(DataComponentTypes.GLIDER);
    }

    public static boolean isElytraUsable(ItemStack stack) {
        return isElytra(stack) && !stack.willBreakNextUse();
    }

    public static double getArmorProtection(ItemStack stack) {
        return sumAttribute(stack, EntityAttributes.ARMOR);
    }

    public static double getArmorToughness(ItemStack stack) {
        return sumAttribute(stack, EntityAttributes.ARMOR_TOUGHNESS);
    }

    private static double sumAttribute(ItemStack stack, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr) {
        net.minecraft.component.type.AttributeModifiersComponent comp = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (comp == null) return 0;
        double total = 0;
        for (net.minecraft.component.type.AttributeModifiersComponent.Entry e : comp.modifiers())
            if (e.attribute().equals(attr)) total += e.modifier().value();
        return total;
    }

    public static int getItemCount(Item item) {
        if (mc.player == null) return 0;

        int counter = 0;

        for (int i = 0; i <= 44; ++i) {
            ItemStack itemStack = mc.player.getInventory().getStack(i);
            if (itemStack.getItem() != item) continue;
            counter += itemStack.getCount();
        }

        return counter;
    }

    public static SearchInvResult getAxe() {
        if (mc.player == null) return SearchInvResult.notFound();
        int slot = -1;
        float f = 1.0F;

        for (int b1 = 9; b1 < 45; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1 >= 36 ? b1 - 36 : b1);
            if (itemStack != null && itemStack.isIn(net.minecraft.registry.tag.ItemTags.AXES)) {
                float f1 = itemStack.getItem().getComponents().get(DataComponentTypes.MAX_DAMAGE);
                f1 += EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.SHARPNESS.getRegistryRef()).getOrThrow(Enchantments.SHARPNESS), itemStack);
                if (f1 > f) {
                    f = f1;
                    slot = b1;
                }
            }
        }

        if (slot >= 36) slot = slot - 36;

        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }

    public static SearchInvResult getPickAxeHotbar() {
        if (mc.player == null) return SearchInvResult.notFound();

        int slot = -1;
        float f = 1.0F;
        for (int b1 = 0; b1 < 9; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null && itemStack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                float f1 = 0;
                f1 += EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.EFFICIENCY.getRegistryRef()).getOrThrow(Enchantments.EFFICIENCY), itemStack);
                if (f1 > f) {
                    f = f1;
                    slot = b1;
                }
            }
        }

        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }

    public static SearchInvResult getPickAxe() {
        if (mc.player == null) return SearchInvResult.notFound();

        int slot = -1;
        float f = 1.0F;
        for (int b1 = 9; b1 < 45; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null && itemStack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                float f1 = 0;
                f1 += EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.EFFICIENCY.getRegistryRef()).getOrThrow(Enchantments.EFFICIENCY), itemStack);
                if (f1 > f) {
                    f = f1;
                    slot = b1;
                }
            }
        }

        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }

    public static SearchInvResult getPickAxeHotBar() {
        if (mc.player == null) return SearchInvResult.notFound();

        int slot = -1;
        float f = 1.0F;
        for (int b1 = 0; b1 < 9; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null && itemStack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                float f1 = 0;
                f1 += EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.EFFICIENCY.getRegistryRef()).getOrThrow(Enchantments.EFFICIENCY), itemStack);
                if (f1 > f) {
                    f = f1;
                    slot = b1;
                }
            }
        }

        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }

    public static SearchInvResult getSkull() {
        if (mc.player == null) return SearchInvResult.notFound();
        int slot = -1;
        for (int b1 = 0; b1 < 9; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null &&
                    (itemStack.getItem().equals(Items.SKELETON_SKULL)
                            || itemStack.getItem().equals(Items.WITHER_SKELETON_SKULL)
                            || itemStack.getItem().equals(Items.CREEPER_HEAD)
                            || itemStack.getItem().equals(Items.PLAYER_HEAD)
                            || itemStack.getItem().equals(Items.ZOMBIE_HEAD))) {
                slot = b1;
                break;
            }
        }
        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }

    public static SearchInvResult getSword() {
        if (mc.player == null) return SearchInvResult.notFound();

        int slot = -1;
        float f = 1.0F;
        for (int b1 = 9; b1 < 45; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null && itemStack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) {
                float f1 = itemStack.getItem().getComponents().get(DataComponentTypes.MAX_DAMAGE);
                f1 += EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.SHARPNESS.getRegistryRef()).getOrThrow(Enchantments.SHARPNESS), itemStack);
                if (f1 > f) {
                    f = f1;
                    slot = b1;
                }
            }
        }

        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }

    public static SearchInvResult getSwordHotBar() {
        if (mc.player == null) return SearchInvResult.notFound();

        int slot = -1;
        float f = 1.0F;
        for (int b1 = 0; b1 < 9; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null && itemStack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) {
                float f1 = itemStack.getItem().getComponents().get(DataComponentTypes.MAX_DAMAGE);
                f1 += EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.SHARPNESS.getRegistryRef()).getOrThrow(Enchantments.SHARPNESS), itemStack);
                if (f1 > f) {
                    f = f1;
                    slot = b1;
                }
            }
        }

        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }

    // TODO check
    public static SearchInvResult getAxeHotBar() {
        if (mc.player == null) return SearchInvResult.notFound();

        int slot = -1;
        float f = 1.0F;
        for (int b1 = 0; b1 < 9; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null && itemStack.isIn(net.minecraft.registry.tag.ItemTags.AXES)) {
                float f1 = itemStack.getItem().getComponents().get(DataComponentTypes.MAX_DAMAGE);
                f1 += EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.SHARPNESS.getRegistryRef()).getOrThrow(Enchantments.SHARPNESS), itemStack);
                if (f1 > f) {
                    f = f1;
                    slot = b1;
                }
            }
        }

        if (slot == -1) return SearchInvResult.notFound();
        return new SearchInvResult(slot, true, mc.player.getInventory().getStack(slot));
    }


    public static int getElytra() {
        for (ItemStack stack : getArmor(mc.player))
            if (stack.getItem() == Items.ELYTRA && stack.getDamage() < 430)
                return -2;

        int slot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() == Items.ELYTRA && s.getDamage() < 430) {
                slot = i;
                break;
            }
        }

        if (slot < 9 && slot != -1)
            slot = slot + 36;

        return slot;
    }

    public static SearchInvResult findInHotBar(Searcher searcher) {
        if (mc.player != null) {
            for (int i = 0; i < 9; ++i) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (searcher.isValid(stack)) {
                    return new SearchInvResult(i, true, stack);
                }
            }
        }

        return SearchInvResult.notFound();
    }

    public static SearchInvResult findItemInHotBar(List<Item> items) {
        return findInHotBar(stack -> items.contains(stack.getItem()));
    }

    public static SearchInvResult findItemInHotBar(Item... items) {
        return findItemInHotBar(Arrays.asList(items));
    }

    public static SearchInvResult findInInventory(Searcher searcher) {
        if (mc.player != null) {
            for (int i = 36; i >= 0; i--) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (searcher.isValid(stack)) {
                    if (i < 9) i += 36;
                    return new SearchInvResult(i, true, stack);
                }
            }
        }

        return SearchInvResult.notFound();
    }

    public static SearchInvResult findItemInInventory(List<Item> items) {
        return findInInventory(stack -> items.contains(stack.getItem()));
    }

    public static SearchInvResult findItemInInventory(Item... items) {
        return findItemInInventory(Arrays.asList(items));
    }

    public static SearchInvResult findBlockInHotBar(@NotNull List<Block> blocks) {
        return findItemInHotBar(blocks.stream().map(Block::asItem).toList());
    }

    public static SearchInvResult findBlockInHotBar(Block... blocks) {
        return findItemInHotBar(Arrays.stream(blocks).map(Block::asItem).toList());
    }

    public static SearchInvResult findBlockInInventory(@NotNull List<Block> blocks) {
        return findItemInInventory(blocks.stream().map(Block::asItem).toList());
    }

    public static SearchInvResult findBlockInInventory(Block... blocks) {
        return findItemInInventory(Arrays.stream(blocks).map(Block::asItem).toList());
    }

    public static void saveSlot() {
        cachedSlot = mc.player.getInventory().selectedSlot;
    }

    public static void returnSlot() {
        if (cachedSlot != -1)
            switchTo(cachedSlot);
        cachedSlot = -1;
    }

    public static void saveAndSwitchTo(int slot) {
        saveSlot();
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (mc.player.getInventory().selectedSlot == slot && Managers.PLAYER.serverSideSlot == slot)
            return;
        mc.player.getInventory().selectedSlot = slot;
        ((IInteractionManager) mc.interactionManager).syncSlot();
    }

    public static void switchTo(int slot) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (mc.player.getInventory().selectedSlot == slot && Managers.PLAYER.serverSideSlot == slot)
            return;
        mc.player.getInventory().selectedSlot = slot;
        ((IInteractionManager) mc.interactionManager).syncSlot();
    }

    public static void switchToSilent(int slot) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    public static SearchInvResult getAntiWeaknessItem() {
        if (mc.player == null) return SearchInvResult.notFound();

        Item mainHand = mc.player.getMainHandStack().getItem();
        if (mainHand.getRegistryEntry().isIn(net.minecraft.registry.tag.ItemTags.SWORDS)
                || mainHand.getRegistryEntry().isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)
                || mainHand.getRegistryEntry().isIn(net.minecraft.registry.tag.ItemTags.AXES)
                || mainHand.getRegistryEntry().isIn(net.minecraft.registry.tag.ItemTags.SHOVELS)) {
            return new SearchInvResult(mc.player.getInventory().selectedSlot, true, mc.player.getMainHandStack());
        }

        return findInHotBar(
                itemStack -> itemStack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)
                        || itemStack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)
                        || itemStack.isIn(net.minecraft.registry.tag.ItemTags.AXES)
                        || itemStack.isIn(net.minecraft.registry.tag.ItemTags.SHOVELS)
        );
    }

    public static float getHitDamage(@NotNull ItemStack weapon, PlayerEntity ent) {
        if (mc.player == null) return 0;
        float baseDamage = 1f;

        if (weapon.isIn(net.minecraft.registry.tag.ItemTags.SWORDS))
            baseDamage = 7;

        if (weapon.isIn(net.minecraft.registry.tag.ItemTags.AXES))
            baseDamage = 9;

        if (mc.player.fallDistance > 0 || ModuleManager.criticals.isEnabled())
            baseDamage += baseDamage / 2f;

        if (mc.player.hasStatusEffect(StatusEffects.STRENGTH)) {
            int strength = Objects.requireNonNull(mc.player.getStatusEffect(StatusEffects.STRENGTH)).getAmplifier() + 1;
            baseDamage += 3 * strength;
        }

        // Reduce by armour
        baseDamage = DamageUtil.getDamageLeft(ent, baseDamage, mc.world.getDamageSources().generic(), ent.getArmor(), (float) ent.getAttributeInstance(EntityAttributes.ARMOR_TOUGHNESS).getValue());
        return baseDamage;
    }

    public static SearchInvResult findBedInHotBar() {
        if (mc.player == null) return SearchInvResult.notFound();
        for (int b1 = 0; b1 < 9; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1);
            if (itemStack != null && itemStack.getItem() instanceof BedItem)
                return new SearchInvResult(b1, true, mc.player.getInventory().getStack(b1));
        }
        return SearchInvResult.notFound();
    }

    public static SearchInvResult findBed() {
        if (mc.player == null) return SearchInvResult.notFound();
        for (int b1 = 9; b1 < 45; b1++) {
            ItemStack itemStack = mc.player.getInventory().getStack(b1 >= 36 ? b1 - 36 : b1);
            if (itemStack != null && itemStack.getItem() instanceof BedItem)
                return new SearchInvResult(b1, true, mc.player.getInventory().getStack(b1));
        }
        return SearchInvResult.notFound();
    }

    public static Item getItem(String Name) {
        if (Name == null) return Items.AIR;
        for (Block block : Registries.BLOCK)
            if (block.getTranslationKey().replace("block.minecraft.", "").equals(Name.toLowerCase()))
                return Item.fromBlock(block);
        for (Item item : Registries.ITEM)
            if (item.getTranslationKey().replace("item.minecraft.", "").equals(Name.toLowerCase()))
                return item;
        return Items.DIRT;
    }

    public static int getBedsCount() {
        if (mc.player == null) return 0;

        int counter = 0;

        for (int i = 0; i <= 44; ++i) {
            ItemStack itemStack = mc.player.getInventory().getStack(i);
            if (!(itemStack.getItem() instanceof BedItem)) continue;
            counter += itemStack.getCount();
        }

        return counter;
    }


    public interface Searcher {
        boolean isValid(ItemStack stack);
    }
}