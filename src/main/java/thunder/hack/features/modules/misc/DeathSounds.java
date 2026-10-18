package thunder.hack.features.modules.misc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WolfSoundVariants;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Port of Celestial's {@code DeathSounds}: plays a sound when a player dies within 10 blocks of you. The
 * mob death sounds are 1:1; Celestial's own oof / bruh / punch .wav files are not in its sources, so those
 * three modes are not here.
 */
public class DeathSounds extends Module {
    public DeathSounds() {
        super("DeathSounds", Category.MISC);
    }

    private enum Mode {Wolf, Villager, Ghast, Blaze, Guardian, IronGolem, Skeleton, Zombie, Chicken, Cow, Pig, Enderman, PolarBear, EnderDragon}

    private final Setting<Mode> deathSoundMode = new Setting<>("Death Sound Mode", Mode.Wolf);
    private final Setting<Integer> volume = new Setting<>("Volume", 50, 1, 100);

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof PlayerEntity player) || !(player.getHealth() <= 0.0f) || player.deathTime >= 1 || !(mc.player.distanceTo(entity) < 10.0f) || entity.age <= 5)
                continue;
            float vol = volume.getValue() / 10.0f;
            SoundEvent sound = switch (deathSoundMode.getValue()) {
                case Wolf -> SoundEvents.WOLF_SOUNDS.get(WolfSoundVariants.Type.CLASSIC).deathSound().value();
                case Villager -> SoundEvents.ENTITY_VILLAGER_DEATH;
                case Blaze -> SoundEvents.ENTITY_BLAZE_DEATH;
                case Chicken -> SoundEvents.ENTITY_CHICKEN_DEATH;
                case Enderman -> SoundEvents.ENTITY_ENDERMAN_DEATH;
                case EnderDragon -> SoundEvents.ENTITY_ENDER_DRAGON_DEATH;
                case Cow -> SoundEvents.ENTITY_COW_DEATH;
                case Pig -> SoundEvents.ENTITY_PIG_DEATH;
                case Skeleton -> SoundEvents.ENTITY_SKELETON_DEATH;
                case Ghast -> SoundEvents.ENTITY_GHAST_DEATH;
                case Zombie -> SoundEvents.ENTITY_ZOMBIE_DEATH;
                case PolarBear -> SoundEvents.ENTITY_POLAR_BEAR_DEATH;
                case Guardian -> SoundEvents.ENTITY_GUARDIAN_DEATH;
                case IronGolem -> SoundEvents.ENTITY_IRON_GOLEM_DEATH;
            };
            mc.player.playSound(sound, vol, 1.0f);
        }
    }

    @Override
    public String getDisplayInfo() {
        return deathSoundMode.getValue().name();
    }
}
