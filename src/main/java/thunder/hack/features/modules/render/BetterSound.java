package thunder.hack.features.modules.render;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Nicer feedback sounds: a crisp ding on a critical hit and a soft sound when you finish eating/drinking. Uses
 * vanilla sound events, so it needs no files and works out of the box.
 */
public final class BetterSound extends Module {
    public BetterSound() {
        super("BetterSound", Category.RENDER);
    }

    public enum Crit {Ding, Pling, Bell, Arcade, Off}
    public enum Eat {Chime, Pop, Burp, Off}

    private final Setting<Crit> critSound = new Setting<>("CritSound", Crit.Ding);
    private final Setting<Eat> eatSound = new Setting<>("EatSound", Eat.Chime);
    private final Setting<Float> volume = new Setting<>("Volume", 0.8f, 0.1f, 2f);
    private final Setting<Float> pitch = new Setting<>("Pitch", 1.3f, 0.5f, 2f);

    private boolean wasUsing;

    @EventHandler
    public void onAttack(EventAttack e) {
        if (critSound.getValue() == Crit.Off || mc.player == null) return;
        // vanilla crit: swinging mid-fall, not on ground / in water / on a ladder, with the cooldown charged
        boolean crit = mc.player.fallDistance > 0f && !mc.player.isOnGround() && !mc.player.isTouchingWater()
                && !mc.player.isClimbing() && !mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)
                && mc.player.getVehicle() == null && mc.player.getAttackCooldownProgress(0.5f) > 0.9f;
        if (!crit) return;
        play(switch (critSound.getValue()) {
            case Ding -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
            case Pling -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
            case Bell -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
            case Arcade -> SoundEvents.ENTITY_ARROW_HIT_PLAYER;
            default -> null;
        });
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        if (mc.player == null) return;
        boolean using = mc.player.isUsingItem() && mc.player.getActiveItem().getUseAction() != net.minecraft.item.consume.UseAction.NONE;
        boolean eating = using && mc.player.getItemUseTimeLeft() <= 1; // about to finish consuming
        if (wasUsing && eating && eatSound.getValue() != Eat.Off) {
            play(switch (eatSound.getValue()) {
                case Chime -> SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value();
                case Pop -> SoundEvents.ENTITY_ITEM_PICKUP;
                case Burp -> SoundEvents.ENTITY_PLAYER_BURP;
                default -> null;
            });
        }
        wasUsing = using;
    }

    private void play(SoundEvent sound) {
        if (sound == null || mc.player == null || mc.world == null) return;
        mc.world.playSound(mc.player, mc.player.getBlockPos(), sound, SoundCategory.PLAYERS, volume.getValue(), pitch.getValue());
    }
}
