package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.UseAction;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.EventSync;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.gui.notification.Notification;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.setting.impl.SettingGroup;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.HeadTextures;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TargetEspStyles;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 1:1 port of Celestial's {@code org.celestial.client.feature.impl.combat.KillAura} to 1.21.11.
 *
 * <p>Event mapping: EventPreMotion -> {@link EventSync} (target, rotations, crit gate, attack - in that order,
 * so the hit goes out before the movement packet, which is what GrimAC's PacketOrder check wants; event.setYaw /
 * setPitch become the packet rotation applied for the sync and restored after it), EventPostMotion -> the sync's
 * post action, EventUpdate -> {@link PlayerUpdateEvent}, EventAttackSilent -> {@link EventAttack} (pre),
 * EventStrafe / EventJump -> Rotations' MoveFix through {@code fixRotation}. EventRender3D is drawn from
 * onRender2D by projection, because the 3D queues double-rotate on 1.21.11.
 *
 * <p>Celestial's helpers (RotationHelper, KillAuraHelper, EntityHelper, MovementHelper, InventoryHelper,
 * MathematicHelper) were not part of the source and are written here from what KillAura asks of them.
 * Celestial features that do not exist in this client (its Criticals modes, Flight "Sunrise Disabler",
 * CustomModel sizes, TargetStrafe) are left out of the conditions that test for them. Its TargetHUD was a
 * separate draggable component; this client's target huds and target ESP are kept in its place.
 */
public class Kilaura extends Module {
    public Kilaura() {
        super("Kilaura", Category.COMBAT);
    }

    public enum RotationMode {Packet, Client, AAC, Snap, Sunrise, ReallyWorld, None}

    public enum StrafeMode {Off, Always, NoAir}

    public enum RotStrafeMode {Default, Silent}

    public enum SortMode {Distance, HigherArmor, BlockingStatus, LowestArmor, Health, Angle, HurtTime}

    public enum ClickMode {V1_9, V1_8}

    public enum CircleMode {Jello, Astolfo}

    public enum PaletteMode {Astolfo, Rainbow, Client, Custom}

    public enum TargetHudMode {Off, Astolfo, Beach, CelestialPremium, Nursultan, GlobalOld, Global, GlobalNew}

    /** NurikZapen + Rockstar's TargetESP modes; Celestial's circles are separate toggles in the same TargetESP group. */
    public enum EspVariant {None, NurikZapen, RockstarJello, RockstarSims, Ghosts, Crystals}

    // =====================================================================================
    // SETTINGS - Celestial's, sorted into collapsible groups (names unchanged, so saved configs still load)
    // =====================================================================================
    public final Setting<SettingGroup> gAttack = new Setting<>("Attack", new SettingGroup(false, 0));
    public final Setting<Float> range = new Setting<>("AttackRange", 3.6f, 3.0f, 7.0f).addToGroup(gAttack);
    public final Setting<ClickMode> clickMode = new Setting<>("Click Mode", ClickMode.V1_9).addToGroup(gAttack);
    public final Setting<Float> attackCoolDown = new Setting<>("Attack Cooldown", 0.9f, 0.1f, 1.0f, v -> clickMode.getValue() == ClickMode.V1_9).addToGroup(gAttack);
    public final Setting<Float> oldPvpSystemDelayMin = new Setting<>("Min APS", 12.0f, 1.0f, 20.0f, v -> clickMode.getValue() == ClickMode.V1_8).addToGroup(gAttack);
    public final Setting<Float> oldPvpSystemDelayMax = new Setting<>("Max APS", 13.0f, 1.0f, 20.0f, v -> clickMode.getValue() == ClickMode.V1_8).addToGroup(gAttack);
    public final Setting<Boolean> tpsSync = new Setting<>("TPS Sync", false).addToGroup(gAttack);
    public final Setting<Float> hitChance = new Setting<>("HitChance", 100.0f, 1.0f, 100.0f).addToGroup(gAttack);
    /** Master switch: Kilaura never changes your hotbar slot (blocks Auto Weapon and the Shield Breaker axe swap). */
    public final Setting<Boolean> noSlotSwitch = new Setting<>("No Slot Switch", true).addToGroup(gAttack);
    public final Setting<Boolean> autoWeapon = new Setting<>("Auto Weapon", false, v -> !noSlotSwitch.getValue()).addToGroup(gAttack);
    public final Setting<Boolean> weaponOnly = new Setting<>("Weapon Only", false).addToGroup(gAttack);
    public final Setting<Boolean> usingItemCheck = new Setting<>("Using Item Check", false).addToGroup(gAttack);
    /** When the aura stands down entirely (no aim, no hits): each one its own switch. */
    public final Setting<SettingGroup> gPause = new Setting<>("Pause", new SettingGroup(false, 0));
    public final Setting<Boolean> pauseMining = new Setting<>("While Mining", true).addToGroup(gPause);
    public final Setting<Boolean> pausePlacing = new Setting<>("While Placing", true).addToGroup(gPause);
    public final Setting<Boolean> pauseEating = new Setting<>("While Eating", true).addToGroup(gPause);
    public final Setting<Boolean> pauseUsing = new Setting<>("While Using Item", true).addToGroup(gPause);
    public final Setting<Boolean> pauseInventory = new Setting<>("In Inventory", true).addToGroup(gPause);
    public final Setting<Boolean> sprinting = new Setting<>("Stop Sprinting", false).addToGroup(gAttack);
    public final Setting<Boolean> autoDisable = new Setting<>("Auto Disable", false).addToGroup(gAttack);
    public final Setting<SettingGroup> gCrits = new Setting<>("Crits", new SettingGroup(false, 0));
    /** Never delays a hit; when a hit lands while you fall it drops the sprint first so it counts as a crit. */
    public final Setting<Boolean> smartCrits = new Setting<>("Smart Crits", false).addToGroup(gCrits);
    public final Setting<Boolean> onlyCrit = new Setting<>("Only Crits", false).addToGroup(gCrits);
    public final Setting<Boolean> spaceOnly = new Setting<>("Space Only", false, v -> onlyCrit.getValue()).addToGroup(gCrits);
    public final Setting<Boolean> adaptiveCrits = new Setting<>("Adaptive Crits", false, v -> onlyCrit.getValue()).addToGroup(gCrits);
    public final Setting<Float> critFallDistance = new Setting<>("Crits Fall Distance", 0.2f, 0.08f, 0.42f, v -> onlyCrit.getValue()).addToGroup(gCrits);
    public final Setting<Float> adaptiveCritsHealth = new Setting<>("Adaptive Crits Health", 5.0f, 0.5f, 10.0f, v -> onlyCrit.getValue() && adaptiveCrits.getValue()).addToGroup(gCrits);
    public final Setting<SettingGroup> gRotation = new Setting<>("Rotation", new SettingGroup(false, 0));
    public final Setting<RotationMode> rotationMode = new Setting<>("Rotation Mode", RotationMode.Packet).addToGroup(gRotation);
    public final Setting<Float> rotSpeed = new Setting<>("Rotation Speed", 5.0f, 0.5f, 5.0f, v -> rotTuned()).addToGroup(gRotation);
    public final Setting<Float> rotPredict = new Setting<>("Rotation Predict", 0.0f, 0.0f, 5.0f, v -> rotTuned()).addToGroup(gRotation);
    public final Setting<Float> rotYawRandom = new Setting<>("Rotation Yaw Random", 2.0f, 0.0f, 5.0f, v -> rotTuned()).addToGroup(gRotation);
    public final Setting<Float> rotPitchRandom = new Setting<>("Rotation Pitch Random", 2.0f, 0.0f, 5.0f, v -> rotTuned()).addToGroup(gRotation);
    public final Setting<Float> rotPitchDown = new Setting<>("Rotation Pitch Down", 0.2f, 0.0f, 1.0f, v -> rotTuned()).addToGroup(gRotation);
    public final Setting<Float> preAimRange = new Setting<>("Pre Aim Range", 0.0f, 0.0f, 4.0f, v -> rotationMode.getValue() != RotationMode.None).addToGroup(gRotation);
    public final Setting<Float> fov = new Setting<>("FOV", 180.0f, 5.0f, 180.0f).addToGroup(gRotation);
    public final Setting<Boolean> rayTrace = new Setting<>("Ray-Trace", true, v -> !sunriseLike()).addToGroup(gRotation);
    public final Setting<Float> rayTraceBox = new Setting<>("Ray-Trace Box", 0.06f, -0.5f, 0.5f, v -> rayTrace.getValue() && !sunriseLike()).addToGroup(gRotation);
    public final Setting<Boolean> visualYaw = new Setting<>("Visual Yaw", true).addToGroup(gRotation);
    public final Setting<Boolean> visualPitch = new Setting<>("Visual Pitch", true).addToGroup(gRotation);
    public final Setting<SettingGroup> gTargets = new Setting<>("Targets", new SettingGroup(false, 0));
    public final Setting<SortMode> sort = new Setting<>("TargetSort Mode", SortMode.Health).addToGroup(gTargets);
    public final Setting<Boolean> players = new Setting<>("Players", true).addToGroup(gTargets);
    public final Setting<Boolean> armorStands = new Setting<>("Armor Stands", false).addToGroup(gTargets);
    public final Setting<Boolean> mobs = new Setting<>("Mobs", false).addToGroup(gTargets);
    public final Setting<Boolean> invis = new Setting<>("Invisible", true).addToGroup(gTargets);
    public final Setting<Boolean> team = new Setting<>("Team Check", false).addToGroup(gTargets);
    public final Setting<Boolean> nakedPlayer = new Setting<>("Ignore Naked Players", false).addToGroup(gTargets);
    public final Setting<Boolean> walls = new Setting<>("Walls", true).addToGroup(gTargets);
    public final Setting<Boolean> wallsBypass = new Setting<>("Walls Bypass", false).addToGroup(gTargets);
    public final Setting<SettingGroup> gMovement = new Setting<>("Movement", new SettingGroup(false, 0));
    public final Setting<StrafeMode> strafeMode = new Setting<>("Strafe Mode", StrafeMode.Off).addToGroup(gMovement);
    public final Setting<RotStrafeMode> rotationStrafeMode = new Setting<>("Rotation Strafe Mode", RotStrafeMode.Default).addToGroup(gMovement);
    public final Setting<Boolean> autoJump = new Setting<>("Auto Jump", false).addToGroup(gMovement);
    public final Setting<Boolean> strafing = new Setting<>("Strafing", false).addToGroup(gMovement);
    public final Setting<Boolean> pullDown = new Setting<>("Pull Down", false).addToGroup(gMovement);
    public final Setting<Float> pullStength = new Setting<>("Pull Strength", 0.005f, 0.001f, 0.1f, v -> pullDown.getValue()).addToGroup(gMovement);
    public final Setting<SettingGroup> gShield = new Setting<>("Shield", new SettingGroup(false, 0));
    public final Setting<Boolean> autoShieldUnPress = new Setting<>("Auto Shield UnPress", false).addToGroup(gShield);
    public final Setting<Boolean> shieldBlockCheck = new Setting<>("Shield Block Check", false).addToGroup(gShield);
    public final Setting<Boolean> shieldBreaker = new Setting<>("Shield Breaker", false).addToGroup(gShield);
    public final Setting<Boolean> setRotations = new Setting<>("Set Rotations", false, v -> shieldBreaker.getValue()).addToGroup(gShield);
    public final Setting<Float> shieldBlockTicks = new Setting<>("Shield Block Ticks", 2.0f, 1.0f, 5.0f, v -> shieldBreaker.getValue()).addToGroup(gShield);
    public final Setting<Float> breakRadius = new Setting<>("Break Radius", 75.0f, 5.0f, 180.0f, v -> shieldBreaker.getValue()).addToGroup(gShield);
    public final Setting<Float> breakerDelay = new Setting<>("Breaker Delay", 50.0f, 0.0f, 1000.0f, v -> shieldBreaker.getValue()).addToGroup(gShield);
    public final Setting<Boolean> shieldFixer = new Setting<>("Shield Fixer", false).addToGroup(gShield);
    public final Setting<Float> fixerDelay = new Setting<>("Fixer Delay", 150.0f, 0.0f, 600.0f, v -> shieldFixer.getValue()).addToGroup(gShield);
    public final Setting<Boolean> shieldDesync = new Setting<>("Shield Desync", false).addToGroup(gShield);
    public final Setting<Boolean> autoBlock = new Setting<>("Auto Block", false).addToGroup(gShield);
    public final Setting<Float> sendDelay = new Setting<>("Send Block Delay", 100.0f, 0.0f, 300.0f, v -> autoBlock.getValue()).addToGroup(gShield);
    public final Setting<Float> outDelay = new Setting<>("Out Block Delay", 0.0f, 0.0f, 300.0f, v -> autoBlock.getValue()).addToGroup(gShield);
    public final Setting<SettingGroup> gTargetHud = new Setting<>("TargetHUD", new SettingGroup(false, 0));
    public final Setting<Boolean> targetHud = new Setting<>("TargetHUD", true).addToGroup(gTargetHud);
    public final Setting<TargetHudMode> targetHudMode = new Setting<>("TargetHud Mode", TargetHudMode.Global, v -> targetHud.getValue()).addToGroup(gTargetHud);
    public final Setting<Integer> thudX = new Setting<>("TargetHud X", 20, -500, 500, v -> targetHud.getValue()).addToGroup(gTargetHud);
    public final Setting<Integer> thudY = new Setting<>("TargetHud Y", 30, -300, 300, v -> targetHud.getValue()).addToGroup(gTargetHud);
    public final Setting<SettingGroup> esp = new Setting<>("TargetESP", new SettingGroup(false, 0));
    public final Setting<EspVariant> espVariant = new Setting<>("Variant", EspVariant.NurikZapen).addToGroup(esp);
    public final Setting<thunder.hack.utility.render.Render3DEngine.GhostStyle> ghostStyle = new Setting<>("Ghost Style",
            thunder.hack.utility.render.Render3DEngine.GhostStyle.Orbit, v -> espVariant.is(EspVariant.Ghosts)).addToGroup(esp);
    public final Setting<Float> espScale = new Setting<>("ESPScale", 1f, 0.5f, 2f, v -> espVariant.is(EspVariant.NurikZapen)).addToGroup(esp);
    // Ghosts ("Dusze") and Crystals ("Kryształy"): their colour
    public final Setting<ColorSetting> espColor = new Setting<>("Color", new ColorSetting(0xFF8A5CFF), v -> espVariant.is(EspVariant.Crystals)).addToGroup(esp);
    // Rockstar TargetESP's "Circle Size" (its DepthTest has nothing to switch here: the ESP is drawn on the 2D layer)
    public final Setting<Float> circleSize = new Setting<>("Circle Size", 0.4f, 0.1f, 1f, v -> espVariant.is(EspVariant.RockstarJello)).addToGroup(esp);
    // Celestial KillAura's own target ESPs (Jello / Astolfo circle, Sims Mark)
    public final Setting<Boolean> circle = new Setting<>("Circle around entity", false).addToGroup(esp);
    public final Setting<CircleMode> circleMode = new Setting<>("Circle Mode", CircleMode.Jello, v -> circle.getValue()).addToGroup(esp);
    public final Setting<PaletteMode> jelloMode = new Setting<>("Jello Mode", PaletteMode.Client, v -> circle.getValue() && circleMode.getValue() == CircleMode.Jello).addToGroup(esp);
    public final Setting<ColorSetting> jelloColor = new Setting<>("Jello Color", new ColorSetting(Color.WHITE.getRGB()), v -> circle.getValue() && jelloMode.getValue() == PaletteMode.Custom).addToGroup(esp);
    public final Setting<Float> circleSpeed = new Setting<>("Circle Speed", 0.01f, 0.001f, 0.05f, v -> circle.getValue() && circleMode.getValue() == CircleMode.Jello).addToGroup(esp);
    public final Setting<Float> circleRange = new Setting<>("Circle Range", 1.5f, 0.5f, 7.0f, v -> circle.getValue() && circleMode.getValue() == CircleMode.Astolfo).addToGroup(esp);
    public final Setting<Float> segments = new Setting<>("Circle Segments", 45.0f, 3.0f, 45.0f, v -> circle.getValue() && circleMode.getValue() == CircleMode.Astolfo).addToGroup(esp);
    public final Setting<Boolean> simsMark = new Setting<>("Sims Mark", false).addToGroup(esp);
    public final Setting<PaletteMode> simsMarkMode = new Setting<>("Sims Mark Mode", PaletteMode.Client, v -> simsMark.getValue()).addToGroup(esp);
    public final Setting<ColorSetting> simsMarkColor = new Setting<>("Sims Mark Color", new ColorSetting(Color.WHITE.getRGB()), v -> simsMark.getValue() && simsMarkMode.getValue() == PaletteMode.Custom).addToGroup(esp);

    private boolean rotTuned() {
        RotationMode m = rotationMode.getValue();
        return m != RotationMode.None && m != RotationMode.Sunrise && m != RotationMode.ReallyWorld;
    }

    private boolean sunriseLike() {
        return rotationMode.getValue() == RotationMode.Sunrise || rotationMode.getValue() == RotationMode.ReallyWorld;
    }

    // =====================================================================================
    // STATE
    // =====================================================================================
    public static final Timer oldTimerPvp = new Timer();
    public static final Timer timer = new Timer();
    public static LivingEntity target;
    private final Timer blockTimer = new Timer();
    private final Timer blockTimer1 = new Timer();
    private final Timer shieldFixerTimer = new Timer();
    private final Timer shieldBreakerTimer = new Timer();
    public static boolean isAttacking;
    private boolean isBlocking;
    private double circleAnim;
    private double circleValue;
    private boolean canDown;
    private int changeSlotCounter;
    public static int critCounter;
    public static int shieldHitCounter;
    public static boolean canDo;
    public static boolean isBreaked;
    public static int shieldTicks;

    /** RotationHelper.Rotation.packetYaw / packetPitch: the rotation the server is given this tick. */
    public static float packetYaw, packetPitch;
    /** event.setYaw / setPitch for this sync. */
    private boolean packetRotSet;
    private float eventYaw, eventPitch;
    private boolean rotationApplied;
    private float savedYaw, savedPitch;

    // visual (head / body) rotation, read by the renderer mixins
    private boolean silentLive;
    private float visYaw, visPitch, prevVisYaw, prevVisPitch;

    // target hud animation
    public static float progress, progress2;
    public static double healthBarWidth, hudHeight, hurttimeBarWidth;
    private long lastMS;
    private long lastFrameNanos = System.nanoTime();
    private long lastCircleNanos = System.nanoTime();

    public static boolean canApsAttack(Kilaura aura) {
        int apsMultiplier = 14 / middleRandomize((int) (float) aura.oldPvpSystemDelayMax.getValue(), (int) (float) aura.oldPvpSystemDelayMin.getValue());
        if (oldTimerPvp.passedMs(50L * apsMultiplier)) {
            oldTimerPvp.reset();
            return true;
        }
        return false;
    }

    private int getAxe() {
        for (int i = 0; i < 9; ++i) {
            ItemStack itemStack = mc.player.getInventory().getStack(i);
            if (!itemStack.isIn(ItemTags.AXES)) continue;
            return i;
        }
        return 1;
    }

    @Override
    public void onDisable() {
        target = null;
        holdSprintForCrit = false;
        circleAnim = 0.0;
        if (isBlocking && mc.player != null) {
            sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, Direction.DOWN));
            isBlocking = false;
        }
        critCounter = 0;
        isBreaked = false;
        shieldTicks = 0;
        restoreRotation();
        silentLive = false;
        ModuleManager.rotations.fixRotation = Float.NaN;
        ModuleManager.rotations.forced = null;
    }

    @Override
    public void onEnable() {
        circleAnim = 0.0;
        lastMS = System.currentTimeMillis();
        progress = 0f;
        progress2 = 0f;
        if (mc.player != null) {
            packetYaw = mc.player.getYaw();
            packetPitch = mc.player.getPitch();
        }
    }

    // =====================================================================================
    // EventUpdate
    // =====================================================================================
    @EventHandler
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        restoreRotation();
        // target + aim once per tick, before the player moves (see onSync)
        packetRotSet = false;
        silentLive = false;
        if (mc.player.getHealth() > 0.0f) {
            setDisplayInfoMode();
            target = getSortEntities();
        }
        if (paused()) {
            // stand down completely: no aim (it would drag placing/mining off the block), no hit, no move fix
            holdSprintForCrit = false;
            ModuleManager.rotations.fixRotation = Float.NaN;
            ModuleManager.rotations.forced = null;
            return;
        }
        smartCritSprint();
        onRotations();
        onStrafeUpdate();
        onStrafe();
        onAuraUpdate();
        // The hit goes out HERE, before the player moves - vanilla order (aim, attack, move, packet). Attacking in
        // EventSync (after the move) put the sprint-hit slowdown (velocity x0.6) on the NEXT tick while Grim applies
        // it to THIS tick's movement -> Simulation; and the server still had last tick's fall state -> crits that
        // the client thought were crits went out one tick too early. Here the client state IS the server's state.
        packetYaw = packetRotSet ? eventYaw : mc.player.getYaw();
        packetPitch = packetRotSet ? eventPitch : mc.player.getPitch();
        doAuraPre();
        onPreMotion();
    }

    /**
     * Smart Crits' sprint reset: in the air, with the hit (nearly) ready, the sprint is dropped at the start of
     * the tick - before moving - near the top of the jump, so the STOP_SPRINTING packet goes out in vanilla order
     * and the server already knows by the time the fall (and the crit) starts.
     */
    // read by MixinClientPlayerEntity.canStartSprinting: without it vanilla turned the sprint straight back on
    // (sprint key / toggle sprint) in the same tick, the server still saw you sprinting, and no hit ever crit
    public boolean holdSprintForCrit;

    private void smartCritSprint() {
        holdSprintForCrit = false;
        if (!smartCrits.getValue() || target == null) return;
        if (mc.player.isOnGround() || noCritHere()) return;
        if (mc.player.fallDistance <= 0 && mc.player.getVelocity().y >= 0.2) return;
        if (mc.player.getAttackCooldownProgress(0.5f) < 0.7f) return;
        holdSprintForCrit = true;
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    /** Vanilla never crits here (PlayerEntity.attack), so Smart Crits must not wait for one. */
    private boolean noCritHere() {
        return mc.player.hasVehicle() || mc.player.isClimbing() || isInLiquid() || isInWeb()
                || mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.LEVITATION)
                || mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS);
    }

    /** Stops the sprint on the server AND the client at once, so the move the server simulates matches. */
    private void stopSprintNow() {
        if (!thunder.hack.core.Core.serverSprint && !mc.player.isSprinting()) return;
        mc.player.setSprinting(false);
        mc.player.lastSprinting = false;
        sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
    }

    /** onStrafeUpdate(EventUpdate). */
    private void onStrafeUpdate() {
        if (!strafing.getValue()) return;
        if (target == null) return;
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.hasVehicle()) return;
        if (isMoving()) setSpeed(getSpeed() * 1.013f);
    }

    /**
     * onStrafe(EventStrafe) + onJumpMotion(EventJump): movement (and the jump) along packetYaw. Default and
     * Silent are both "move by the server yaw" here, which Rotations' MoveFix does from fixRotation.
     */
    private void onStrafe() {
        // Always on while there is a live target (no Strafe Mode needed): movement follows the aura's aim, so
        // W walks you at the target even with the camera turned away - except "Silent" (Rotation Strafe
        // Mode), which keeps W on the camera and only sends the aim to the server. None does not aim at all.
        // Strafe Mode NoAir still lets go while you hold jump.
        boolean active = target != null && target.isAlive()
                && rotationMode.getValue() != RotationMode.None
                // AAC and Snap turn only at the moment of the hit, later in the tick - their packet yaw is not known
                // when the player moves, so steering movement by it would itself mismatch
                && rotationMode.getValue() != RotationMode.AAC && rotationMode.getValue() != RotationMode.Snap
                && !(strafeMode.getValue() == StrafeMode.NoAir && mc.options.jumpKey.isPressed());
        // THIS tick's aim (computed just above in onPlayerUpdate) - the same yaw the move packet will carry
        ModuleManager.rotations.fixRotation = active ? (packetRotSet ? eventYaw : mc.player.getYaw()) : Float.NaN;
        ModuleManager.rotations.forced = !active ? null
                : rotationStrafeMode.getValue() == RotStrafeMode.Silent
                ? thunder.hack.features.modules.client.Rotations.MoveFix.Free
                : thunder.hack.features.modules.client.Rotations.MoveFix.Focused;
    }

    /** onUpdate(EventUpdate) - renamed, Module already has an onUpdate(). */
    private void onAuraUpdate() {
        if (autoDisable.getValue() && (mc.currentScreen instanceof DeathScreen && !mc.player.isAlive() || mc.player.age <= 1)) {
            if (isEnabled()) disable();
            Managers.NOTIFICATION.publicity("AutoDisable", "KillAura was toggled off!", 4, Notification.Type.INFO);
        }
        if (target == null) return;
        if (autoJump.getValue() && mc.player.isOnGround() && !mc.player.hasVehicle() && !mc.player.isClimbing() && !isInLiquid() && !isInWeb()) {
            mc.options.jumpKey.setPressed(false);
            strafePlayer();
            mc.player.jump();
        }
        if (autoShieldUnPress.getValue() && mc.player.getOffHandStack().isOf(Items.SHIELD)) {
            if (target.getMainHandStack().isIn(ItemTags.AXES)) {
                if (mc.options.useKey.isPressed()) mc.options.useKey.setPressed(false);
            } else {
                mc.options.useKey.setPressed(GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS);
            }
        }
        if (shieldDesync.getValue() && mc.player.getOffHandStack().isOf(Items.SHIELD)) {
            sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, new BlockPos(900, 900, 900), Direction.NORTH));
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        }
    }

    // =====================================================================================
    // EventPreMotion
    // =====================================================================================
    @EventHandler
    public void onSync(EventSync event) {
        if (mc.player == null || mc.world == null) return;
        // Target and aim were worked out at the start of this tick (onPlayerUpdate), BEFORE the player moved, and
        // movement used exactly that yaw - so the packet carries the same rotation the movement was made with.
        // Recomputing here (one smoothing step later) is what made Grim's Simulation check flag.
        // a late rotation (AAC / Snap / shield breaker, applied with the hit at tick start) wins over the aim
        if (packetRotSet && !rotationApplied) {
            savedYaw = mc.player.getYaw();
            savedPitch = mc.player.getPitch();
            mc.player.setYaw(eventYaw);
            mc.player.setPitch(eventPitch);
            rotationApplied = true;
        }
        // Always restore after the packet: AAC, Snap and the shield breaker turn the player later in this same
        // sync (applyLateRotation), and without this their aim was never undone - the camera stayed glued to
        // the target and W walked you into it.
        event.addPostAction(() -> {
            restoreRotation();
            onPostMotion();
        });
        packetYaw = mc.player.getYaw();
        packetPitch = mc.player.getPitch();
    }

    private String suffix = "";

    private void setDisplayInfoMode() {
        suffix = rotationMode.getValue().name();
    }

    @Override
    public String getDisplayInfo() {
        return suffix;
    }

    /** onRotations(EventPreMotion). */
    private void onRotations() {
        RotationMode mode = rotationMode.getValue();
        int speedMultiplier = 50;
        if (target == null) return;
        if (target.isAlive()) {
            if (!mc.player.getMainHandStack().isIn(ItemTags.SWORDS) && !mc.player.getMainHandStack().isIn(ItemTags.AXES) && weaponOnly.getValue())
                return;
            float[] rots = getRotationsCustom(target, rotSpeed.getValue() * (float) speedMultiplier, mode != RotationMode.Sunrise);
            if (mode == RotationMode.Packet) {
                setEvent(rots[0], rots[1]);
            } else if (mode == RotationMode.Sunrise) {
                setEvent(rots[0], rots[1]);
            } else if (mode == RotationMode.ReallyWorld) {
                setEvent(rots[0], rots[1]);
            } else if (mode == RotationMode.Client) {
                mc.player.setYaw(rots[0]);
                mc.player.setPitch(rots[1]);
            }
            if (!(mode == RotationMode.AAC || mode == RotationMode.None || mode == RotationMode.Snap)) {
                setVisual(rots[0], rots[1]);
            }
        }
    }

    /** doAuraPre(EventPreMotion), from the point where the target has been chosen. */
    private void doAuraPre() {
        boolean flag;
        if (mc.player.getHealth() > 0.0f) {
            if (target == null) return;
            if (mc.player.isUsingItem() && usingItemCheck.getValue()) return;
            if (!mc.player.getMainHandStack().isIn(ItemTags.SWORDS) && !mc.player.getMainHandStack().isIn(ItemTags.AXES) && weaponOnly.getValue())
                return;
            if (isActiveItemStackBlocking(target, 5) && target.isBlocking() && target.isUsingItem()
                    && (target.getOffHandStack().isOf(Items.SHIELD) || target.getMainHandStack().isOf(Items.SHIELD))
                    && shieldBlockCheck.getValue() && isAimAtMe(target, 85.0f))
                return;
        } else if (autoDisable.getValue()) {
            toggle();
            Managers.NOTIFICATION.publicity("KillAura", "KillAura was disabled because of Death!", 3, Notification.Type.INFO);
        }
        if (target == null) return;
        // Auto Weapon only arms you when you are holding something that is not a weapon - a sword (or axe, mace,
        // trident) already in hand is never swapped for whatever hits a little harder
        if (!noSlotSwitch.getValue() && autoWeapon.getValue() && !isWeapon(mc.player.getMainHandStack())) {
            int best = getBestWeapon();
            if (best != mc.player.getInventory().getSelectedSlot()) {
                mc.player.getInventory().setSelectedSlot(best);
                sendPacket(new UpdateSelectedSlotC2SPacket(best));
            }
        }
        if (pullDown.getValue() && !mc.player.isOnGround()) {
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, v.y - (double) pullStength.getValue(), v.z);
        }
        RotationMode mode = rotationMode.getValue();
        float[] rots = getRotationsCustom(target, Float.MAX_VALUE, mode != RotationMode.Sunrise);
        boolean packetLike = mode == RotationMode.Packet || mode == RotationMode.Sunrise || mode == RotationMode.ReallyWorld || mode == RotationMode.AAC;
        float yaw = packetLike ? packetYaw : mc.player.getYaw();
        float pitch = packetLike ? packetPitch : mc.player.getPitch();
        ((thunder.hack.injection.accesors.ILivingEntity) mc.player).setLastJumpCooldown(0);
        BlockPos blockPos = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() - 0.1, mc.player.getZ());
        boolean liquidBelow = mc.world.getBlockState(blockPos).getBlock() instanceof FluidBlock;
        float f2 = mc.player.getAttackCooldownProgress(0.5f);
        flag = f2 > 0.9f;
        if (!flag && onlyCrit.getValue()) return;
        if (smartCrits.getValue()) {
            // Smart Crits: standing on the ground (no jump held) it hits straight away; once you jump it waits for
            // the fall, so a jump never wastes the hit on a non-crit. The client state here is the state the server
            // has (we are at tick start, before moving), so the check is exactly vanilla's crit condition.
            if (!noCritHere() && (!mc.player.isOnGround() || mc.options.jumpKey.isPressed())) {
                if (mc.player.isOnGround()) return;                      // jumping now - hit lands in the fall
                if (mc.player.fallDistance <= 0 || !flag) return;        // still going up / cooldown not > 0.9
                // the sprint was dropped by smartCritSprint() and its packet goes out with this tick's movement,
                // after this hit - wait that one tick until the server knows, else the hit is a sprint hit
                if (thunder.hack.core.Core.serverSprint) return;
            }
        } else if (!(adaptiveCrits.getValue() && target.getHealth() / 2.0f <= adaptiveCritsHealth.getValue() || !mc.options.jumpKey.isPressed() && spaceOnly.getValue())) {
            if (airBlockAboveHead()) {
                // in a cobweb the fall is too slow to ever reach Crits Fall Distance - any fall there already crits
                if (!(mc.player.fallDistance >= critFallDistance.getValue() || liquidBelow || !onlyCrit.getValue() || mc.player.hasVehicle() || mc.player.isClimbing() || isInLiquid()
                        || (isInWeb() && (mc.player.isOnGround() || mc.player.fallDistance > 0.0f)))) {
                    stopSprintNow();
                    return;
                }
            } else if (!(!(mc.player.fallDistance > 0.0f) || mc.player.isOnGround() || !onlyCrit.getValue() || mc.player.hasVehicle() || mc.player.isClimbing() || isInLiquid() || isInWeb())) {
                stopSprintNow();
                return;
            }
        }
        if (mode == RotationMode.AAC) {
            // AAC sets the rotation here, after the crit gate - it only turns when it is about to hit
            applyLateRotation(rots[0], rots[1]);
            setVisual(rots[0], rots[1]);
            yaw = rots[0];
            pitch = rots[1];
            if (!isLookingAtEntity(yaw, pitch, rayTraceBox.getValue(), rayTraceBox.getValue(), rayTraceBox.getValue(), target, range.getValue() + preAimRange.getValue()))
                return;
        }
        if (sunriseLike()
                ? !isLookingAtEntity(yaw, pitch, 0.12f, 0.12f, 0.12f, target, range.getValue() + preAimRange.getValue())
                : mode != RotationMode.Snap && !isLookingAtEntity(yaw, pitch, rayTraceBox.getValue(), rayTraceBox.getValue(), rayTraceBox.getValue(), target, range.getValue() + preAimRange.getValue()) && rayTrace.getValue()) {
            return;
        }
        canDo = true;
        if (mode == RotationMode.Snap) {
            float[] rots1 = getRotationsCustom(target, rotSpeed.getValue() * 50.0f, true);
            if (mc.player.getAttackCooldownProgress(0.0f) >= attackCoolDown.getValue())
                applyLateRotation(rots1[0], rots1[1]);   // sent with this packet, then restored
        }
        attackEntitySuccess(rayCast(target, range.getValue()));
    }

    private void attackEntitySuccess(LivingEntity target) {
        if (target == null || mc.player.getHealth() < 0.0f) return;
        if (mc.player.distanceTo(target) > range.getValue()) return;
        if (target.isAlive()) {
            switch (clickMode.getValue()) {
                case V1_9 -> {
                    float attackDelay = tpsSync.getValue() ? attackCoolDown.getValue() * Managers.SERVER.getTPS() / 20.0f : attackCoolDown.getValue();
                    if (!(mc.player.getAttackCooldownProgress(0.0f) >= attackDelay) || !((float) spikeRandomize(100, 0) < hitChance.getValue()))
                        break;
                    if (sprinting.getValue())
                        stopSprintNow();
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                case V1_8 -> {
                    if (!canApsAttack(this)) break;
                    if (isBlocking && autoBlock.getValue() && mc.player.getMainHandStack().isIn(ItemTags.SWORDS) && blockTimer1.passedMs((long) (float) sendDelay.getValue())) {
                        sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, new BlockPos(-1, -1, -1), Direction.DOWN));
                        isBlocking = false;
                        blockTimer1.reset();
                    }
                    if (!((float) spikeRandomize(100, 0) < hitChance.getValue())) break;
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }
        }
    }

    /** onPostMotion(EventPostMotion). */
    private void onPostMotion() {
        if (target == null || mc.player == null) return;
        if (mc.player.getMainHandStack().isIn(ItemTags.SWORDS) && !isBlocking && autoBlock.getValue() && blockTimer.passedMs((long) (float) outDelay.getValue())) {
            sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, mc.player.getYaw(), mc.player.getPitch()));
            blockTimer.reset();
            isBlocking = true;
        }
    }

    /** onPreMotion(EventPreMotion): the shield breaker. */
    private void onPreMotion() {
        if (target == null) return;
        if (!doesHotbarHaveAxe()) return;
        if (noSlotSwitch.getValue() || autoWeapon.getValue()) return;
        if (shieldBreaker.getValue() && (target.getOffHandStack().isOf(Items.SHIELD) || target.getMainHandStack().isOf(Items.SHIELD))) {
            if (target.isBlocking() && target.isUsingItem() && isActiveItemStackBlocking(target, (int) (float) shieldBlockTicks.getValue())) {
                RotationMode mode = rotationMode.getValue();
                boolean packetLike = mode == RotationMode.Packet || mode == RotationMode.Sunrise || mode == RotationMode.ReallyWorld || mode == RotationMode.AAC;
                float yaw = packetLike ? packetYaw : mc.player.getYaw();
                float pitch = packetLike ? packetPitch : mc.player.getPitch();
                if (!isLookingAtEntity(yaw, pitch, 0.06f, 0.06f, 0.06f, target, range.getValue() + preAimRange.getValue()) && mode != RotationMode.None)
                    return;
                if (isAimAtMe(target, breakRadius.getValue())) {
                    if (setRotations.getValue()) {
                        float[] rots = getRotationsCustom(target, Float.MAX_VALUE, mode != RotationMode.Sunrise);
                        applyLateRotation(rots[0], rots[1]);
                    }
                    if (mc.player.getInventory().getSelectedSlot() != getAxe()) {
                        mc.player.getInventory().setSelectedSlot(getAxe());
                        sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().getSelectedSlot()));
                    }
                    if (mc.player.getInventory().getSelectedSlot() == getAxe()) {
                        if (shieldBreakerTimer.passedMs((long) (float) breakerDelay.getValue())) {
                            isBreaked = true;
                            mc.interactionManager.attackEntity(mc.player, target);
                            mc.player.swingHand(Hand.MAIN_HAND);
                            mc.player.resetTicksSinceLastAttack();
                            shieldBreakerTimer.reset();
                        }
                        changeSlotCounter = -1;
                    } else {
                        changeSlotCounter = 0;
                    }
                }
            } else if (!(mc.player.getInventory().getSelectedSlot() == getSwordAtHotbar() || changeSlotCounter != -1 || getSwordAtHotbar() == -1
                    || target.isBlocking() && target.isUsingItem() && isActiveItemStackBlocking(target, 2))) {
                mc.player.getInventory().setSelectedSlot(getSwordAtHotbar());
                sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().getSelectedSlot()));
                changeSlotCounter = 0;
                Managers.NOTIFICATION.publicity(Formatting.GREEN + "Shield-Breaker", Formatting.RESET + "Successfully destroyed " + target.getName().getString() + " shield", 2, Notification.Type.SUCCESS);
                isBreaked = false;
            }
        }
    }

    /** onAttackSilent(EventAttackSilent). */
    @EventHandler
    public void onAttackSilent(EventAttack event) {
        if (!event.isPre() || mc.player == null) return;
        isAttacking = true;
        if (mc.player.isBlocking() && shieldFixerTimer.passedMs((long) (float) fixerDelay.getValue()) && mc.player.getOffHandStack().isOf(Items.SHIELD) && shieldFixer.getValue()) {
            sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, new BlockPos(900, 900, 900), Direction.UP));
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            shieldFixerTimer.reset();
        }
    }

    // =====================================================================================
    // rotation plumbing (event.setYaw / player.renderYawOffset / rotationPitchHead)
    // =====================================================================================
    private void setEvent(float y, float p) {
        packetRotSet = true;
        eventYaw = y;
        eventPitch = p;
    }

    /** AAC's event.setYaw comes after the sync rotation was applied, so it is applied straight away. */
    private void applyLateRotation(float y, float p) {
        if (!rotationApplied) {
            savedYaw = mc.player.getYaw();
            savedPitch = mc.player.getPitch();
            rotationApplied = true;
        }
        mc.player.setYaw(y);
        mc.player.setPitch(p);
        packetYaw = y;
        packetPitch = p;
    }

    private void setVisual(float y, float p) {
        prevVisYaw = !silentLive && visYaw == 0f ? y : visYaw;
        prevVisPitch = !silentLive && visPitch == 0f ? p : visPitch;
        visYaw = visualYaw.getValue() ? y : mc.player.getYaw();
        visPitch = visualPitch.getValue() ? p : mc.player.getPitch();
        silentLive = visualYaw.getValue() || visualPitch.getValue();
        if (visualYaw.getValue()) {
            mc.player.setBodyYaw(y);
            mc.player.setHeadYaw(y);
        }
    }

    private void restoreRotation() {
        if (rotationApplied && mc.player != null) {
            rotationApplied = false;
            mc.player.setYaw(savedYaw);
            mc.player.setPitch(savedPitch);
        }
    }

    public boolean showsSilentLook() {
        return isEnabled() && silentLive && target != null;
    }

    public float silentRenderYaw(float tickDelta) {
        return prevVisYaw + MathHelper.wrapDegrees(visYaw - prevVisYaw) * tickDelta;
    }

    public float silentRenderPitch(float tickDelta) {
        return prevVisPitch + (visPitch - prevVisPitch) * tickDelta;
    }

    public LivingEntity getTarget() {
        return target;
    }

    // =====================================================================================
    // RotationHelper
    // =====================================================================================

    /** RotationHelper.getRotationsCustom (1:1, including its randomizeFloat(-x, -x) - a constant -x). */
    private float[] getRotationsCustom(LivingEntity entityIn, float speed, boolean random) {
        RotationMode mode = rotationMode.getValue();
        boolean fromPacket = mode == RotationMode.Packet || mode == RotationMode.Sunrise || mode == RotationMode.AAC;
        float yawCurrent = fromPacket ? packetYaw : mc.player.getYaw();
        float pitchCurrent = fromPacket ? packetPitch : mc.player.getPitch();
        boolean rw = mode == RotationMode.ReallyWorld;
        float randomYaw = 0.0f;
        if (random)
            randomYaw = randomizeFloat(rw ? -2.0f : -rotYawRandom.getValue(), rw ? -2.0f : -rotYawRandom.getValue());
        float randomPitch = 0.0f;
        if (random)
            randomPitch = randomizeFloat(rw ? -2.0f : -rotPitchRandom.getValue(), rw ? -2.0f : -rotPitchRandom.getValue());
        float step = rotSpeed.getValue() >= 5.0f && mode != RotationMode.Sunrise || rw ? Float.MAX_VALUE : (mode == RotationMode.Sunrise ? 25.0f : speed);
        float yaw = updateRotation(yawCurrent + randomYaw, getRotations(entityIn, random)[0], step);
        // Standing in / right on the target: the direction to it flips every tick (atan2 of ~0), so the aim - and the
        // Focused move fix that walks along it - spun around and kept pushing you through the target's hitbox, which
        // flagged Simulation. Hold the yaw until there is a real horizontal direction again.
        double cdx = entityIn.getX() - mc.player.getX(), cdz = entityIn.getZ() - mc.player.getZ();
        if (cdx * cdx + cdz * cdz < 0.6 * 0.6) yaw = yawCurrent;
        float pitch = updateRotation(pitchCurrent + randomPitch, getRotations(entityIn, random)[1], step);
        return new float[]{yaw, pitch};
    }

    /** RotationHelper.getRotations. */
    private float[] getRotations(LivingEntity entityIn, boolean random) {
        RotationMode mode = rotationMode.getValue();
        float predict = rotPredict.getValue();
        Vec3d motion = mc.player.getVelocity();
        boolean bypass = walls.getValue() && wallsBypass.getValue() && !entityIn.canSee(mc.player);
        boolean sunrisePos = mode == RotationMode.Sunrise && posCheck(entityIn);
        double diffX = entityIn.getX() + (entityIn.getX() - entityIn.lastX) * predict - mc.player.getX() - motion.x * predict;
        double diffZ = entityIn.getZ() + (entityIn.getZ() - entityIn.lastZ) * predict - mc.player.getZ() - motion.z * predict;
        double diffY = entityIn.getEyeY() - mc.player.getEyeY()
                - (bypass ? -0.38 : (sunrisePos ? 1.0 : (mode == RotationMode.ReallyWorld ? 0.2 : (double) rotPitchDown.getValue())));
        double diffXZ = MathHelper.sqrt((float) (diffX * diffX + diffZ * diffZ));
        if (sunrisePos) diffY /= diffXZ;
        float randomYaw = 0.0f;
        if (random) randomYaw = randomizeFloat(-rotYawRandom.getValue(), rotYawRandom.getValue());
        float randomPitch = 0.0f;
        if (random) randomPitch = randomizeFloat(-rotPitchRandom.getValue(), rotPitchRandom.getValue());
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI - 90.0) + randomYaw;
        float pitch = (float) (-(Math.atan2(diffY, diffXZ) * 180.0 / (bypass ? 3.1 : Math.PI + (sunrisePos ? 10 : 0)))) + randomPitch;
        if (sunrisePos) pitch += 26.0f;
        yaw = mc.player.getYaw() + getFixedRotation(MathHelper.wrapDegrees(yaw - mc.player.getYaw()));
        pitch = mc.player.getPitch() + getFixedRotation(MathHelper.wrapDegrees(pitch - mc.player.getPitch()));
        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        return new float[]{yaw, pitch};
    }

    /** RotationHelper.posCheck: the target's feet are within 1.5 blocks of yours, vertically. */
    private boolean posCheck(Entity entity) {
        double y = mc.player.getY();
        return y <= entity.getY() + 1.5 && y >= entity.getY() - 1.5;
    }

    private boolean isLookingAtEntity(float yaw, float pitch, float xExp, float yExp, float zExp, Entity entity, double range) {
        Vec3d src = mc.player.getEyePos();
        Vec3d dir = Vec3d.fromPolar(pitch, yaw);
        Vec3d dest = src.add(dir.x * range, dir.y * range, dir.z * range);
        return entity.getBoundingBox().expand(xExp, yExp, zExp).raycast(src, dest).isPresent();
    }

    private static float updateRotation(float current, float newValue, float speed) {
        float f = MathHelper.wrapDegrees(newValue - current);
        if (f > speed) f = speed;
        if (f < -speed) f = -speed;
        return current + f;
    }

    private boolean isAimAtMe(Entity entity, float breakRadius) {
        float entityYaw = MathHelper.wrapDegrees(entity.getYaw());
        return Math.abs(MathHelper.wrapDegrees(getYawToEntity(entity, mc.player)) - entityYaw) <= breakRadius;
    }

    private static float getYawToEntity(Entity mainEntity, Entity targetEntity) {
        double dX = mainEntity.getX() - targetEntity.getX();
        double dZ = mainEntity.getZ() - targetEntity.getZ();
        return (float) (Math.toDegrees(Math.atan2(dZ, dX)) + 90.0);
    }

    private float getGCDValue() {
        float f1 = (float) (mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2);
        return (float) (f1 * f1 * f1 * 8.0f * 0.15);
    }

    private float getFixedRotation(float rot) {
        float g = getGCDValue();
        return Math.round(rot / g) * g;
    }

    // =====================================================================================
    // KillAuraHelper / EntityHelper
    // =====================================================================================

    /** KillAuraHelper.getSortEntities. */
    private LivingEntity getSortEntities() {
        ArrayList<LivingEntity> entity = new ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity player)) continue;
            if (player.getHealth() > 0.0f && player.isAlive()) {
                if (!(mc.player.distanceTo(player) <= range.getValue() + preAimRange.getValue()) || !canAttack(player)) continue;
                entity.add(player);
                continue;
            }
            entity.remove(player);
        }
        switch (sort.getValue()) {
            case Angle -> entity.sort((o1, o2) -> (int) (Math.abs(getAngleEntity(o1) - mc.player.getYaw()) - Math.abs(getAngleEntity(o2) - mc.player.getYaw())));
            case HigherArmor -> entity.sort(Comparator.comparingInt(LivingEntity::getArmor).reversed());
            case LowestArmor -> entity.sort(Comparator.comparingInt(LivingEntity::getArmor));
            case Health -> entity.sort((o1, o2) -> (int) (o1.getHealth() - o2.getHealth()));
            case Distance -> entity.sort(Comparator.comparingDouble(mc.player::distanceTo));
            case HurtTime -> entity.sort(Comparator.comparingInt((LivingEntity o) -> o.hurtTime).reversed());
            case BlockingStatus -> entity.sort(Comparator.comparing(LivingEntity::isUsingItem).reversed());
        }
        if (entity.isEmpty()) return null;
        return entity.get(0);
    }

    /** KillAuraHelper.canAttack. */
    private boolean canAttack(LivingEntity player) {
        // client-side props: the Pets animal and the FakeLag ghost do not exist on the server
        if (thunder.hack.features.modules.render.Pets.isPet(player) || player.getId() == -1337420) return false;
        if (player instanceof PlayerEntity p && Managers.FRIEND.isFriend(p)) return false;
        if (ModuleManager.antiBot.isEnabled() && player instanceof PlayerEntity p && AntiBot.bots.contains(p)) return false;
        if (nakedPlayer.getValue() && player instanceof PlayerEntity && player.getArmor() < 1) return false;
        if ((player instanceof net.minecraft.entity.mob.SlimeEntity || player instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity
                || player instanceof net.minecraft.entity.mob.EndermanEntity || player instanceof net.minecraft.entity.passive.WaterAnimalEntity) && !mobs.getValue())
            return false;
        if (player instanceof PlayerEntity || player instanceof AnimalEntity || player instanceof Monster || player instanceof VillagerEntity) {
            if (player instanceof PlayerEntity && !players.getValue()) return false;
            if (player instanceof AnimalEntity && !mobs.getValue()) return false;
            if (player instanceof Monster && !mobs.getValue()) return false;
            if (player instanceof VillagerEntity && !mobs.getValue()) return false;
        }
        if (player instanceof ArmorStandEntity && !armorStands.getValue()) return false;
        if (player.isInvisible() && !invis.getValue()) return false;
        if (!(mc.player.distanceTo(player) <= range.getValue() + preAimRange.getValue())) return false;
        if (!canSeeEntityAtFov(player, fov.getValue() * 2.0f)) return false;
        if (team.getValue() && isTeamWithYou(player)) return false;
        if (!player.canSee(mc.player)) return walls.getValue();
        return player != mc.player && mc.player != null && mc.world != null;
    }

    private boolean canSeeEntityAtFov(Entity entityLiving, float scope) {
        double diffZ = entityLiving.getZ() - mc.player.getZ();
        double diffX = entityLiving.getX() - mc.player.getX();
        float yaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0);
        return angleDifference(yaw, mc.player.getYaw()) <= scope;
    }

    private static double angleDifference(double a, double b) {
        float yaw360 = (float) (Math.abs(a - b) % 360.0);
        if (yaw360 > 180.0f) yaw360 = 360.0f - yaw360;
        return yaw360;
    }

    /** RotationHelper.getAngleEntity. */
    private float getAngleEntity(Entity entity) {
        float yaw = mc.player.getYaw();
        float yaw1 = (float) (Math.atan2(entity.getZ() - mc.player.getZ(), entity.getX() - mc.player.getX()) * 180.0 / Math.PI - 90.0);
        return yaw + MathHelper.wrapDegrees(yaw1 - yaw);
    }

    /** EntityHelper.isTeamWithYou: the same scoreboard team, or a name in the same colour. */
    private boolean isTeamWithYou(LivingEntity entity) {
        if (mc.player.getScoreboardTeam() != null && entity.getScoreboardTeam() != null && mc.player.getScoreboardTeam().isEqual(entity.getScoreboardTeam()))
            return true;
        var mine = mc.player.getDisplayName().getStyle().getColor();
        var theirs = entity.getDisplayName().getStyle().getColor();
        return mine != null && mine.equals(theirs);
    }

    /** EntityHelper.rayCast: whatever living entity is actually first on the line to the target, or null. */
    private LivingEntity rayCast(LivingEntity entityIn, double range) {
        Vec3d vec = entityIn.getEntityPos().add(0.0, entityIn.getStandingEyeHeight(), 0.0);
        Vec3d from = mc.player.getEntityPos().add(0.0, mc.player.getStandingEyeHeight(), 0.0);
        Box axis = mc.player.getBoundingBox().stretch(vec.subtract(from)).expand(1.0, 1.0, 1.0);
        LivingEntity entityRayCast = null;
        for (Entity entity : mc.world.getOtherEntities(mc.player, axis)) {
            if (thunder.hack.features.modules.render.Pets.isPet(entity) || entity.getId() == -1337420) continue; // client-side props
            if (entity.canHit() && entity instanceof LivingEntity living) {
                float size = entity.getTargetingMargin();
                Box axis1 = entity.getBoundingBox().expand(size, size, size);
                Optional<Vec3d> rayTrace = axis1.raycast(from, vec);
                if (axis1.contains(from)) {
                    if (range >= 0.0) {
                        entityRayCast = living;
                        range = 0.0;
                    }
                } else if (rayTrace.isPresent()) {
                    double dist = from.distanceTo(rayTrace.get());
                    if (range == 0.0 || dist < range) {
                        entityRayCast = living;
                        range = dist;
                    }
                }
            }
        }
        return entityRayCast;
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.isOf(Items.MACE) || stack.isOf(Items.TRIDENT);
    }

    /** Last time a block was placed (a PlayerInteractBlock packet went out). */
    private volatile long lastPlaceMs;

    @EventHandler
    public void onPlacePacket(thunder.hack.events.impl.PacketEvent.Send event) {
        if (event.getPacket() instanceof net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket)
            lastPlaceMs = System.currentTimeMillis();
    }

    /** The Pause switches: mining, placing, using an item, or an inventory screen open. */
    private boolean paused() {
        if (pauseMining.getValue() && (mc.interactionManager.isBreakingBlock()
                || mc.options.attackKey.isPressed() && mc.crosshairTarget != null && mc.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK))
            return true;
        if (pausePlacing.getValue() && (System.currentTimeMillis() - lastPlaceMs < 300
                || mc.options.useKey.isPressed() && (mc.player.getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem
                || mc.player.getOffHandStack().getItem() instanceof net.minecraft.item.BlockItem)))
            return true;
        // eating / drinking (food, potions, milk) - its own switch, so the others can stay off
        if (pauseEating.getValue() && mc.player.isUsingItem()) {
            UseAction action = mc.player.getActiveItem().getUseAction();
            if (action == UseAction.EAT || action == UseAction.DRINK) return true;
        }
        if (pauseUsing.getValue() && mc.player.isUsingItem()) return true;
        return pauseInventory.getValue() && mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen;
    }

    /** EntityHelper.getBestWeapon: the hotbar slot that hits hardest (bows, pickaxes and shovels skipped). */
    private int getBestWeapon() {
        int originalSlot = mc.player.getInventory().getSelectedSlot();
        int weaponSlot = -1;
        float weaponDamage = 1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack itemStack = mc.player.getInventory().getStack(slot);
            if (itemStack.isOf(Items.BOW) || itemStack.isIn(ItemTags.PICKAXES) || itemStack.isIn(ItemTags.SHOVELS)) continue;
            var modifiers = itemStack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            float damage = modifiers == null ? 1.0f : (float) modifiers.applyOperations(EntityAttributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
            if (!(damage > weaponDamage)) continue;
            weaponDamage = damage;
            weaponSlot = slot;
        }
        return weaponSlot != -1 ? weaponSlot : originalSlot;
    }

    public static boolean isActiveItemStackBlocking(LivingEntity other, int time) {
        if (other.isUsingItem() && !other.getActiveItem().isEmpty()) {
            ItemStack stack = other.getActiveItem();
            if (stack.getUseAction() != UseAction.BLOCK) return false;
            return stack.getMaxUseTime(other) - other.getItemUseTimeLeft() >= time;
        }
        return false;
    }

    // =====================================================================================
    // InventoryHelper / MovementHelper / MathematicHelper
    // =====================================================================================
    private boolean doesHotbarHaveAxe() {
        for (int i = 0; i < 9; ++i)
            if (mc.player.getInventory().getStack(i).isIn(ItemTags.AXES)) return true;
        return false;
    }

    private int getSwordAtHotbar() {
        for (int i = 0; i < 9; ++i)
            if (mc.player.getInventory().getStack(i).isIn(ItemTags.SWORDS)) return i;
        return -1;
    }

    /** MovementHelper.airBlockAboveHead: nothing solid above the head (1.5 blocks up in the air, 2.5 on the ground). */
    private boolean airBlockAboveHead() {
        Box bb = new Box(mc.player.getX() - 0.3, mc.player.getEyeY(), mc.player.getZ() + 0.3,
                mc.player.getX() + 0.3, mc.player.getY() + (!mc.player.isOnGround() ? 1.5 : 2.5), mc.player.getZ() - 0.3);
        return mc.world.isSpaceEmpty(mc.player, bb);
    }

    private boolean isInLiquid() {
        return mc.player.isTouchingWater() || mc.player.isInLava();
    }

    private boolean isInWeb() {
        Box pBox = mc.player.getBoundingBox();
        for (BlockPos bp : BlockPos.iterate(BlockPos.ofFloored(pBox.minX, pBox.minY, pBox.minZ), BlockPos.ofFloored(pBox.maxX, pBox.maxY, pBox.maxZ)))
            if (mc.world.getBlockState(bp).isOf(Blocks.COBWEB)) return true;
        return false;
    }

    private boolean isMoving() {
        return mc.player.input.getMovementInput().lengthSquared() > 0;
    }

    private float getSpeed() {
        Vec3d v = mc.player.getVelocity();
        return (float) Math.sqrt(v.x * v.x + v.z * v.z);
    }

    /** MovementHelper.setSpeed: the given horizontal speed, in the direction you are walking. */
    private void setSpeed(float speed) {
        var input = mc.player.input.getMovementInput();
        float forward = input.y, strafe = input.x;
        float yaw = mc.player.getYaw();
        if (forward == 0 && strafe == 0) {
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(0, v.y, 0);
            return;
        }
        if (forward != 0) {
            if (strafe > 0) yaw += forward > 0 ? -45 : 45;
            else if (strafe < 0) yaw += forward > 0 ? 45 : -45;
            strafe = 0;
            forward = forward > 0 ? 1 : -1;
        }
        double rad = Math.toRadians(yaw + 90.0f);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(forward * speed * cos + strafe * speed * sin, v.y, forward * speed * sin - strafe * speed * cos);
    }

    /** MovementHelper.strafePlayer: keep the current speed, turned to where you are walking. */
    private void strafePlayer() {
        setSpeed(getSpeed());
    }

    private static int middleRandomize(int max, int min) {
        return (int) (Math.random() * (double) (max - min)) + min;
    }

    private static int spikeRandomize(int max, int min) {
        return -min + (int) (Math.random() * (double) (max - -min + 1));
    }

    private static float randomizeFloat(float startInclusive, float endInclusive) {
        if (startInclusive == endInclusive || endInclusive - startInclusive <= 0.0f) return startInclusive;
        return (float) ((double) startInclusive + (double) (endInclusive - startInclusive) * Math.random());
    }

    // =====================================================================================
    // RENDER (EventRender2D + EventRender3D)
    // =====================================================================================
    @Override
    public void onRender3D(MatrixStack stack) {
        // ThunderHack V2 ghosts: the same souls (and defaults) as Aura's GombalV2 ESP
        if (target != null && espVariant.is(EspVariant.Ghosts))
            Render3DEngine.renderGhosts(14, 8, 1.8f, 3f, target, ghostStyle.getValue());
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (mc.player == null) return;
        drawEsp(context);
        onRenderCircle(context);
        drawTargetHud(context);
    }

    private int paletteColor(PaletteMode mode, Setting<ColorSetting> custom) {
        return switch (mode) {
            case Client -> HudEditor.getColor(0).getRGB();
            case Custom -> custom.getValue().getColor();
            case Astolfo -> Render2DEngine.astolfo(false, 1).getRGB();
            case Rainbow -> Render2DEngine.rainbow(300, 1.0f, 1.0f).getRGB();
        };
    }

    /** onRenderCircle(EventRender3D): Jello / Astolfo circle and the Sims mark, projected onto the screen. */
    private void onRenderCircle(DrawContext context) {
        if (target == null) return;
        long now = System.nanoTime();
        // Minecraft.frameTime is the last frame's length in ms
        double frameTime = Math.min(100.0, (now - lastCircleNanos) / 1_000_000.0);
        lastCircleNanos = now;
        float pt = Render3DEngine.getTickDelta();
        double x = MathHelper.lerp(pt, target.lastRenderX, target.getX());
        double y = MathHelper.lerp(pt, target.lastRenderY, target.getY());
        double z = MathHelper.lerp(pt, target.lastRenderZ, target.getZ());

        if (circle.getValue() && mc.player.distanceTo(target) <= range.getValue() + preAimRange.getValue()) {
            if (circleMode.getValue() == CircleMode.Astolfo) {
                if (target.isAlive()) {
                    circleAnim += 0.05f * frameTime / 10.0;
                    circleAnim = MathHelper.clamp(circleAnim, 0.0, circleRange.getValue());
                    int segs = (int) (float) segments.getValue();
                    drawCircle3D(context, x, y, z, circleAnim - 0.006, segs, 6.0f, Color.BLACK.getRGB());
                    drawCircle3D(context, x, y, z, circleAnim + 0.006, segs, 6.0f, Color.BLACK.getRGB());
                    drawCircle3D(context, x, y, z, circleAnim, segs, 2.0f, -1);
                } else {
                    circleAnim = 0.0;
                }
            } else if (circleMode.getValue() == CircleMode.Jello && target.isAlive()) {
                int color = paletteColor(jelloMode.getValue(), jelloColor);
                circleValue += circleSpeed.getValue() * (frameTime * 0.1);
                float targetHeight = (float) (0.5 * (1.0 + Math.sin(Math.PI * 2 * (circleValue * 0.3f))));
                float size = target.getWidth();
                float endYValue = (float) ((target.getHeight() * 1.0 + 0.2) * targetHeight);
                if (targetHeight > 0.99) canDown = true;
                else if (targetHeight < 0.01) canDown = false;
                float alpha = (canDown ? 255.0f * targetHeight : 255.0f * (1.0f - targetHeight)) / 255.0f;
                double tail = canDown ? -0.5f * (1.0f - targetHeight) : 0.5f * targetHeight;
                int solid = withAlpha(color, alpha);
                int clear = withAlpha(color, 0f);

                // GL_TRIANGLE_STRIP: the ring at endY, fading to nothing towards the tail
                int steps = 120;
                float[] bx = new float[steps + 1], by = new float[steps + 1], tx = new float[steps + 1], ty = new float[steps + 1];
                boolean[] ok = new boolean[steps + 1];
                for (int i = 0; i <= steps; i++) {
                    double a = Math.toRadians(i * 360.0 / steps);
                    double iSin = Math.sin(a) * size, iCos = Math.cos(a) * size;
                    Vec3d b = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x + iCos, y + endYValue, z - iSin));
                    Vec3d t = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x + iCos, y + endYValue + tail, z - iSin));
                    ok[i] = b.z > 0 && b.z < 1 && t.z > 0 && t.z < 1;
                    bx[i] = (float) b.x;
                    by[i] = (float) b.y;
                    tx[i] = (float) t.x;
                    ty[i] = (float) t.y;
                }
                Matrix3x2f pose = GuiRender.pose(context);
                GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
                    for (int i = 0; i < steps; i++) {
                        if (!ok[i] || !ok[i + 1]) continue;
                        GuiRender.quad(vc, pose, bx[i], by[i], tx[i], ty[i], tx[i + 1], ty[i + 1], bx[i + 1], by[i + 1],
                                solid, clear, clear, solid);
                    }
                });
                // GL_LINE_LOOP at endY
                for (int i = 0; i < steps; i++) {
                    if (!ok[i] || !ok[i + 1]) continue;
                    Render2DEngine.drawThickLine(context, bx[i], by[i], bx[i + 1], by[i + 1], color, 2.0f);
                }
            }
        } else {
            circleAnim = 0.0;
        }

        if (simsMark.getValue()) {
            int color = paletteColor(simsMarkMode.getValue(), simsMarkColor);
            double d = target.getStandingEyeHeight() + 0.35;
            double d2 = target.isSneaking() ? 0.25 : 0.0;
            double middle = 0.6;
            double yy = y + d - d2;
            // two cones, apex up at yy+0.7 and apex down at yy+0.1, meeting in one base ring
            drawCone(context, x, yy + middle + 0.1, z, 0.2f, 0.3f, 60, color);
            drawCone(context, x, yy + middle - 0.5, z, 0.2f, -0.3f, 60, color);
        }
    }

    /** RenderHelper.drawCircle3D: a ring of {@code segments} sides lying at the feet. */
    private void drawCircle3D(DrawContext context, double x, double y, double z, double radius, int segments, float width, int color) {
        if (radius <= 0) return;
        Vec3d prev = null;
        for (int i = 0; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            Vec3d s = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x + Math.cos(a) * radius, y, z + Math.sin(a) * radius));
            boolean ok = s.z > 0 && s.z < 1;
            if (ok && prev != null)
                Render2DEngine.drawThickLine(context, (float) prev.x, (float) prev.y, (float) s.x, (float) s.y, color, width);
            prev = ok ? s : null;
        }
    }

    /** RenderHelper.drawCone: apex at the origin, base ring {@code height} below it. */
    private void drawCone(DrawContext context, double x, double y, double z, float radius, float height, int segments, int color) {
        Vec3d apex = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x, y, z));
        if (!(apex.z > 0 && apex.z < 1)) return;
        float[] rx = new float[segments + 1], ry = new float[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            Vec3d s = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x + Math.cos(a) * radius, y - height, z + Math.sin(a) * radius));
            if (!(s.z > 0 && s.z < 1)) return;
            rx[i] = (float) s.x;
            ry[i] = (float) s.y;
        }
        Matrix3x2f pose = GuiRender.pose(context);
        float ax = (float) apex.x, ay = (float) apex.y;
        GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
            for (int i = 0; i < segments; i++) {
                // a little shading round the cone so it reads as a solid, not a flat triangle
                float shade = 0.75f + 0.25f * (float) Math.cos(Math.PI * 2 * i / segments);
                GuiRender.tri(vc, pose, ax, ay, rx[i], ry[i], rx[i + 1], ry[i + 1], shadeColor(color, shade));
            }
        });
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.round(MathHelper.clamp(alpha, 0f, 1f) * 255) << 24) | (color & 0xFFFFFF);
    }

    private static int shadeColor(int color, float k) {
        int r = Math.round(((color >> 16) & 0xFF) * k), g = Math.round(((color >> 8) & 0xFF) * k), b = Math.round((color & 0xFF) * k);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // ------- Gombal extra: projected target ESP -------
    private void drawEsp(DrawContext context) {
        if (target == null || espVariant.getValue() == EspVariant.None) return;
        float td = Render3DEngine.getTickDelta();
        Vec3d p = new Vec3d(
                MathHelper.lerp(td, target.lastRenderX, target.getX()),
                MathHelper.lerp(td, target.lastRenderY, target.getY()),
                MathHelper.lerp(td, target.lastRenderZ, target.getZ()));
        switch (espVariant.getValue()) {
            case NurikZapen -> TargetEspStyles.nurikZapen(context, target, p, espScale.getValue());
            // Rockstar TargetESP: only within aura range (RangeHelper.getRange() + addrange)
            case RockstarJello -> {
                if (target.getHealth() > 0 && mc.player.distanceTo(target) <= range.getValue() + preAimRange.getValue())
                    TargetEspStyles.rockstarJello(context, target, p, circleSize.getValue());
            }
            // Ghosts are the ThunderHack V2 souls, drawn in the world - see onRender3D
            case Ghosts -> {
            }
            case Crystals -> TargetEspStyles.crystals(context, target, p, espColor.getValue().getColor());
            case RockstarSims -> {
                if (target.getHealth() > 0 && mc.player.distanceTo(target) <= range.getValue() + preAimRange.getValue())
                    TargetEspStyles.rockstarSims(context, target, p);
            }
            case None -> {
            }
        }
    }

    // ------- Gombal target huds (Celestial's TargetHUDComponent was not part of the source) -------
    private void drawTargetHud(DrawContext context) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrameNanos) / 1.0E9f);
        lastFrameNanos = now;

        TargetHudMode mode = targetHudMode.getValue();
        if (mode == TargetHudMode.Global || mode == TargetHudMode.GlobalNew) {
            if (progress2 < 1.0f) progress2 = (System.currentTimeMillis() - this.lastMS) / 250.0f;
            else progress2 = 1.0f;
            if (progress2 >= 0.7f) {
                if (progress < 1.0f) progress = (System.currentTimeMillis() - this.lastMS) / 550.0f;
                else progress = 1.0f;
            }
        } else {
            if (progress2 >= 1.0f) progress2 = 1.0f;
            else progress2 = (System.currentTimeMillis() - this.lastMS) / 550.0f;
            if (progress >= 1.0f) progress = 1.0f;
            else if (progress2 >= 1.0f) progress = (System.currentTimeMillis() - this.lastMS) / 1050.0f;
        }
        progress = Math.min(progress, 1f);
        progress2 = Math.min(progress2, 1f);

        if (target == null || mc.world == null || !targetHud.getValue() || mode == TargetHudMode.Off || !(target instanceof PlayerEntity player)) {
            progress = 0.0f;
            progress2 = 0.0f;
            this.lastMS = System.currentTimeMillis();
            healthBarWidth = 92.0;
            hudHeight = 0.0;
            return;
        }

        float x = mc.getWindow().getScaledWidth() / 2f + thudX.getValue() - 122;
        float y = mc.getWindow().getScaledHeight() / 2f + thudY.getValue() + 14;
        Color client = HudEditor.getColor(0);
        Color clientDark = client.darker();
        Color clientBright = client.brighter();
        String name = player.getName().getString();
        Identifier skin = HeadTextures.ofPlayer(player);

        switch (mode) {
            case Global, GlobalNew -> {
                double hpWidth = (target.getHealth() / target.getMaxHealth() * 78);
                healthBarWidth = animate(hpWidth, healthBarWidth, 3 * dt);
                float nameW = FontRenderers.sf_bold.getStringWidth(name);
                float panelW = (nameW > 60 ? nameW + 60 : 60 + 70) * progress2;
                float px = x + 182 - 60 * progress2, py = y - 13.5f * progress2, ph = 9.5f + 30 * progress2;
                // Same plate as Beach: dark, translucent, soft shadow
                Render2DEngine.drawBlurredShadow(context, px, py, panelW, ph, 10, new Color(15, 15, 15, 150));
                Render2DEngine.drawRound(context, px, py, panelW, ph, 7, new Color(15, 15, 15, 150));

                int sx1 = (int) (x + 182 - 60), sy1 = (int) (y - 13.5f);
                int sx2 = (int) (x + (nameW > 60 ? nameW + 115 : 115 + 70) - 60 + 128), sy2 = (int) (y - 13.5f + 9.5f + 30);
                context.enableScissor(sx1, sy1, sx2, sy2);
                if (skin != null && !name.isEmpty())
                    HeadTextures.draw(context, skin, (int) (x + 216 + (nameW > 60 ? nameW + 30 : 30 + 70) - 100 * progress), (int) (y - 9f), 30);
                FontRenderers.sf_bold_mini.drawString(context, "Distance: " + String.format("%.1f", mc.player.distanceTo(target)),
                        x + 101.0f - 70 + 105.0f * progress - FontRenderers.sf_medium.getStringWidth(String.valueOf((int) target.getHealth() / 2.0f)) / 2.0f, y + 5.5f, -1);
                FontRenderers.sf_bold.drawString(context, name, x + 128 - 105 + 105 * progress, y - 7.0f, -1);
                context.drawItem(target.getOffHandStack(), (int) x + 222, (int) y - 35);

                if (target.hurtTime > 0) {
                    float cx = (float) (x - 105 + 105 * progress + 160 + (healthBarWidth - 32.5f));
                    Color bubble = new Color(50, 50, 50, 255 * target.hurtTime / 10);
                    Render2DEngine.drawRound(context, cx - 4, y + 2, 8, 8, 4, bubble);
                    FontRenderers.sf_bold_micro.drawCenteredString(context, "" + (int) target.getHealth(), cx, y + 4, -1);
                }
                float bx1 = x + 160 - 32 - 105 + 105 * progress;
                float bx2 = (float) (x - 105 + 105 * progress + 160.0f + (healthBarWidth - 32));
                if (bx2 > bx1)
                    Render2DEngine.drawGradientRound(context, bx1, y + 13.0f, bx2 - bx1, 7, 0, clientBright, clientBright, client, client);
                context.disableScissor();
            }
            case GlobalOld -> {
                double hpWidth = (target.getHealth() / target.getMaxHealth() * 78);
                healthBarWidth = animate(hpWidth, healthBarWidth, 3 * dt);
                Render2DEngine.drawRect(context, x + 120, y - 14, 2, 39 * progress2, client);
                if (progress2 >= 1.0f) {
                    Render2DEngine.drawRect(context, x + 122, y - 13.5f, 128 * progress, 38, new Color(0, 0, 0, 150));
                    Render2DEngine.drawRect(context, x + 120 + 130 * progress, y - 14, 2, 39, client);
                }
                if (progress == 1.0f) {
                    if (skin != null) HeadTextures.draw(context, skin, (int) x + 216, (int) (y - 9f), 30);
                    FontRenderers.sf_medium.drawString(context, "Hp: " + (int) target.getHealth() + " | Distance: " + (int) mc.player.distanceTo(target),
                            x + 101.0f + 35.0f - FontRenderers.sf_medium.getStringWidth(String.valueOf((int) target.getHealth() / 2.0f)) / 2.0f, y + 4f, -1);
                    FontRenderers.sf_medium.drawString(context, name, x + 128, y - 7.0f, -1);
                    context.drawItem(target.getOffHandStack(), (int) x + 222, (int) y - 35);
                }
                if (progress >= 0.5 && progress2 >= 1.0f) {
                    float w = (float) ((healthBarWidth - 32) * progress) + 32;
                    Render2DEngine.drawGradientRound(context, x + 128, y + 13.0f, Math.max(0, w), 7, 0, client, client, clientDark, clientDark);
                }
            }
            case Beach -> {
                double hpWidth = (target.getHealth() / target.getMaxHealth() * 78);
                healthBarWidth = animate(hpWidth, healthBarWidth, 5 * dt);
                Render2DEngine.drawBlurredShadow(context, x + 122, y - 14, 128, 39, 10, new Color(15, 15, 15, 150));
                Render2DEngine.drawRound(context, x + 122, y - 14, 128, 39, 8, new Color(15, 15, 15, 150));
                if (skin != null) HeadTextures.draw(context, skin, (int) x + 129, (int) (y - 4f), 23);
                Render2DEngine.drawRect(context, x + 160, y + 13.0f, (float) healthBarWidth, 5, client);
                FontRenderers.sf_medium_mini.drawString(context, "Hp: " + (int) target.getHealth() / 2.0f + " | Ground: " + (target.isOnGround() ? "true" : "false"),
                        x + 121.0f + 46.0f - FontRenderers.sf_medium.getStringWidth(String.valueOf((int) target.getHealth() / 2.0f)) / 2.0f, y + 6f, -1);
                FontRenderers.sf_medium.drawString(context, name, x + 158, y - 5.0f, -1);
                context.drawItem(target.getOffHandStack(), (int) x + 228, (int) y - 35);
            }
            case Nursultan -> {
                double hpWidth = (target.getHealth() / target.getMaxHealth() * 78);
                healthBarWidth = animate(hpWidth, healthBarWidth, 5 * dt);
                Render2DEngine.drawBlurredShadow(context, x + 122, y - 14, 138, 54, 10, new Color(15, 15, 15, 150));
                Render2DEngine.drawRound(context, x + 122, y - 14, 138, 54, 8, new Color(15, 15, 15, 150));
                if (skin != null) HeadTextures.draw(context, skin, (int) x + 128, (int) (y - 8f), 30);
                Render2DEngine.drawGradientRound(context, x + 128, y + 25, (float) (healthBarWidth * 1.43f), 7.5f, 0, clientDark, clientDark, client, client);
                FontRenderers.sf_medium.drawString(context, "" + (int) target.getHealth() / 2.0f, x + 128 + healthBarWidth * 1.43f + 1, y + 27.5f, -1);
                FontRenderers.sf_medium.drawString(context, name, x + 160, y, -1);
                FontRenderers.sf_medium.drawString(context, "Distance:" + (int) mc.player.distanceTo(target), x + 160, y + 9.0f, -1);
                context.drawItem(target.getOffHandStack(), (int) x + 228, (int) y - 35);
            }
            case Astolfo -> {
                float ax = x + 122, ay = y - 14;
                double healthWid = MathHelper.clamp(target.getHealth() / target.getMaxHealth() * 120, 0.0, 120.0);
                double check = target.getHealth() < 18 && target.getHealth() > 1 ? 8 : 0;
                healthBarWidth = lerp((float) healthWid, (float) healthBarWidth, 5 * dt);
                Render2DEngine.drawRound(context, ax, ay, 155, 62, 6, new Color(20, 20, 20, 255));
                context.drawTextWithShadow(mc.textRenderer, ModuleManager.nameProtect.isEnabled() ? "Protected" : name, (int) ax + 31, (int) ay + 5, -1);
                var m = context.getMatrices();
                m.pushMatrix();
                m.translate(ax, ay);
                m.scale(2.5f, 2.5f);
                m.translate(-ax - 3, -ay - 2);
                context.drawTextWithShadow(mc.textRenderer, Math.round(target.getHealth() / 2.0f * 10) / 10.0 + " ❤", (int) ax + 16, (int) ay + 10, client.getRGB());
                m.popMatrix();
                context.drawItem(target.getOffHandStack(), (int) ax + 137, (int) ay + 1);
                InventoryScreen.drawEntity(context, (int) ax + 2, (int) ay + 5, (int) ax + 30, (int) ay + 60, 25, 0.0625f, ax + 16, ay + 30, target);
                Render2DEngine.drawRect(context, ax + 30, ay + 48, 120, 8, client.darker().darker().darker());
                Render2DEngine.drawRect(context, ax + 30, ay + 48, (float) Math.min(120, healthBarWidth + check), 8, client.darker().darker());
                Render2DEngine.drawRect(context, ax + 30, ay + 48, (float) healthWid, 8, client);
            }
            case CelestialPremium -> {
                double hpPercentage = MathHelper.clamp(target.getHealth() / target.getMaxHealth(), 0.0, 1.0);
                double hpWidth = 110 * hpPercentage;
                healthBarWidth = animate(hpWidth, healthBarWidth, 5 * dt);
                hudHeight = animate(40.0, hudHeight, 5 * dt);
                Render2DEngine.drawBlurredShadow(context, x + 120, y - 26, 160, 61, 15, new Color(32, 31, 32, 150));
                Render2DEngine.drawRect(context, x + 125, y - 19, 150, 48, new Color(32, 31, 32, 150));
                Render2DEngine.drawRound(context, x + 127.5f, y - 11.5f, 145.5f, 0.5f, 0, new Color(140, 140, 140));
                Render2DEngine.drawRound(context, x + 162f, y + 18, (float) healthBarWidth, 2, 1, Render2DEngine.injectAlpha(client, 115));
                Render2DEngine.drawRound(context, x + 162f, y + 18, (float) hpWidth, 2, 1, client);
                FontRenderers.sf_medium_mini.drawString(context, "Ground: " + (target.isOnGround() ? "true;" : "false;"), x + 162f, y - 3f, -1);
                FontRenderers.sf_medium_mini.drawString(context, "HurtTime", x + 162.5f, y + 7f, -1);
                FontRenderers.sf_medium_mini.drawCenteredString(context, name, x + 275 / 1.38, y - 16.7, -1);
                double hurttimePercentage = MathHelper.clamp(target.hurtTime, 0.3, 1.0);
                hurttimeBarWidth = animate(71.0 * hurttimePercentage, hurttimeBarWidth, 0.053);
                Render2DEngine.drawRound(context, x + 201f, y + 9f, 71, 2, 1, new Color(40, 40, 40));
                Render2DEngine.drawRound(context, x + 201f, y + 9f, (float) hurttimeBarWidth, 2, 1, client);
                context.drawItem(target.getOffHandStack(), (int) x + 259, (int) y - 10);
                Render2DEngine.drawRound(context, x + 125.5f, y - 20.5f, 149.5f, 2.5f, 1, client);
                if (skin != null)
                    HeadTextures.draw(context, skin, (int) x + 127 + target.hurtTime / 2, (int) (y - 8f) + target.hurtTime / 2, 32 - target.hurtTime);
            }
        }
    }

    private static double animate(double target, double current, double speed) {
        double f = MathHelper.clamp(speed, 0.0, 1.0);
        return current + (target - current) * f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
