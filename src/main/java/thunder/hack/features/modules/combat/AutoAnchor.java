package thunder.hack.features.modules.combat;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.core.manager.player.CombatManager;
import thunder.hack.events.impl.*;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.Bind;
import thunder.hack.setting.impl.BooleanSettingGroup;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.world.ExplosionUtility;
import thunder.hack.utility.math.MathUtility;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.PlayerUtility;
import thunder.hack.utility.player.SearchInvResult;
import thunder.hack.utility.render.BlockModelRender;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.animation.Animation;
import thunder.hack.utility.render.animation.Easing;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.util.math.MathHelper.wrapDegrees;

public final class AutoAnchor extends Module {
    /*   MAIN   */
    private static final Setting<Pages> page = new Setting<>("Page", Pages.Main);
    /**
     * Auto does the targeting and the damage maths; Manual only reacts to what you do.
     *
     * <p>Two different jobs. Auto decides WHERE an anchor should go; Manual takes an anchor you have
     * already put down and sets it off as fast as the server will take it, and spams crystals for as
     * long as you hold the use button.
     */
    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Auto, v -> page.getValue() == Pages.Main);
    /** Place and break a crystal while the use button is held. */
    private final Setting<Boolean> crystalSpam = new Setting<>("CrystalSpam", true, v -> page.getValue() == Pages.Main && mode.getValue() == Mode.Manual);
    private final Setting<Integer> manualDelay = new Setting<>("ManualDelay", 0, 0, 500, v -> page.getValue() == Pages.Main && mode.getValue() == Mode.Manual);
    private final Setting<AK47> ak47 = new Setting<>("AK47", AK47.ON, v -> page.getValue() == Pages.Main && mode.getValue() == Mode.Auto);
    private final Setting<Timing> timing = new Setting<>("Timing", Timing.NORMAL, v -> page.getValue() == Pages.Main);
    private final Setting<Boolean> rotate = new Setting<>("Rotate", true, v -> page.getValue() == Pages.Main);
    private final Setting<BooleanSettingGroup> yawStep = new Setting<>("YawStep", new BooleanSettingGroup(false), v -> rotate.getValue() && page.getValue() == Pages.Main);
    private final Setting<Float> yawAngle = new Setting<>("YawAngle", 180.0f, 1.0f, 180.0f, v -> rotate.getValue() && page.getValue() == Pages.Main).addToGroup(yawStep);
    private final Setting<CombatManager.TargetBy> targetLogic = new Setting<>("TargetLogic", CombatManager.TargetBy.Distance, v -> page.getValue() == Pages.Main);
    private final Setting<Float> targetRange = new Setting<>("TargetRange", 10.0f, 1.0f, 15f, v -> page.getValue() == Pages.Main);
    public static final Setting<Integer> selfPredictTicks = new Setting<>("SelfPredictTicks", 3, 0, 20, v -> page.getValue() == Pages.Main);
    public final Setting<Boolean> useOptimizedCalc = new Setting<>("UseOptimizedCalc", true, v -> page.getValue() == Pages.Main);

    /*   PLACE   */
    private final Setting<InteractionUtility.Interact> interact = new Setting<>("Interact", InteractionUtility.Interact.Vanilla, v -> page.getValue() == Pages.Place);
    private final Setting<Integer> placeDelay = new Setting<>("PlaceDelay", 0, 0, 1000, v -> page.getValue() == Pages.Place);
    private final Setting<Integer> lowPlaceDelay = new Setting<>("LowPlaceDelay", 550, 0, 1000, v -> page.getValue() == Pages.Place);
    private final Setting<Float> placeRange = new Setting<>("PlaceRange", 5f, 1.0f, 6f, v -> page.getValue() == Pages.Place);
    private final Setting<Float> placeWallRange = new Setting<>("PlaceWallRange", 3.5f, 1.0f, 6f, v -> page.getValue() == Pages.Place);
    public static final Setting<Integer> predictTicks = new Setting<>("PredictTicks", 3, 0, 20, v -> page.getValue() == Pages.Place);

    /*   EXPLODE   */
    private final Setting<Integer> explodeDelay = new Setting<>("ExplodeDelay", 0, 0, 1000, v -> page.getValue() == Pages.Break);
    private final Setting<Integer> lowExplodeDelay = new Setting<>("LowExplodeDelay", 550, 0, 1000, v -> page.getValue() == Pages.Break);

    /*   PAUSE   */
    private final Setting<Boolean> mining = new Setting<>("Mining", true, v -> page.getValue() == Pages.Pause);
    private final Setting<Boolean> eating = new Setting<>("Eating", true, v -> page.getValue() == Pages.Pause);
    private final Setting<Boolean> aura = new Setting<>("Aura", false, v -> page.getValue() == Pages.Pause);
    private final Setting<Boolean> surround = new Setting<>("Surround", true, v -> page.getValue() == Pages.Pause);
    private final Setting<Boolean> middleClick = new Setting<>("MiddleClick", true, v -> page.getValue() == Pages.Pause);
    private final Setting<Float> pauseHP = new Setting<>("HP", 8.0f, 2.0f, 10f, v -> page.getValue() == Pages.Pause);
    private final Setting<BooleanSettingGroup> switchPause = new Setting<>("SwitchPause", new BooleanSettingGroup(true), v -> page.getValue() == Pages.Pause);
    private final Setting<Integer> switchDelay = new Setting<>("SwitchDelay", 100, 0, 1000, v -> page.getValue() == Pages.Pause).addToGroup(switchPause);

    /*   DAMAGES   */
    public final Setting<Sort> sort = new Setting<>("Sort", Sort.DAMAGE, v -> page.getValue() == Pages.Damages);
    public final Setting<Float> minDamage = new Setting<>("MinDamage", 6.0f, 2.0f, 20f, v -> page.getValue() == Pages.Damages);
    public final Setting<Float> maxSelfDamage = new Setting<>("MaxSelfDamage", 10.0f, 2.0f, 20f, v -> page.getValue() == Pages.Damages);
    private final Setting<Safety> safety = new Setting<>("Safety", Safety.NONE, v -> page.getValue() == Pages.Damages);
    private final Setting<Float> safetyBalance = new Setting<>("SafetyBalance", 1.1f, 0.1f, 3f, v -> page.getValue() == Pages.Damages && safety.getValue() == Safety.BALANCE);
    public final Setting<Boolean> protectFriends = new Setting<>("ProtectFriends", true, v -> page.getValue() == Pages.Damages);
    private final Setting<Boolean> overrideSelfDamage = new Setting<>("OverrideSelfDamage", true, v -> page.getValue() == Pages.Damages);
    private final Setting<Float> lethalMultiplier = new Setting<>("LethalMultiplier", 1.0f, 0.0f, 5f, v -> page.getValue() == Pages.Damages);
    private final Setting<BooleanSettingGroup> armorBreaker = new Setting<>("ArmorBreaker", new BooleanSettingGroup(true), v -> page.getValue() == Pages.Damages);
    private final Setting<Float> armorScale = new Setting<>("Armor %", 5.0f, 0.0f, 40f, v -> page.getValue() == Pages.Damages).addToGroup(armorBreaker);
    private final Setting<Float> facePlaceHp = new Setting<>("FacePlaceHp", 5.0f, 0.0f, 20f, v -> page.getValue() == Pages.Damages);
    private final Setting<Bind> facePlaceButton = new Setting<>("FacePlaceBtn", new Bind(GLFW.GLFW_KEY_LEFT_SHIFT, false, false), v -> page.getValue() == Pages.Damages);

    /*   SWITCH   */
    private final Setting<Switch> autoSwitch = new Setting<>("Switch", Switch.NORMAL, v -> page.getValue() == Pages.Switch);
    private final Setting<Switch> antiWeakness = new Setting<>("AntiWeakness", Switch.SILENT, v -> page.getValue() == Pages.Switch);

    /*   RENDER   */
    private final Setting<Boolean> render = new Setting<>("Render", true, v -> page.getValue() == Pages.Render);
    private final Setting<Render> renderMode = new Setting<>("RenderMode", Render.Fade, v -> page.getValue() == Pages.Render);
    private final Setting<Boolean> rselfDamage = new Setting<>("SelfDamage", true, v -> page.getValue() == Pages.Render);
    private final Setting<Boolean> drawDamage = new Setting<>("RenderDamage", true, v -> page.getValue() == Pages.Render);
    private final Setting<ColorSetting> fillColor = new Setting<>("Block Fill Color", new ColorSetting(HudEditor.getColor(0)), v -> page.getValue() == Pages.Render);
    private final Setting<ColorSetting> lineColor = new Setting<>("Block Line Color", new ColorSetting(HudEditor.getColor(0)), v -> page.getValue() == Pages.Render);
    private final Setting<Integer> lineWidth = new Setting<>("Block Line Width", 2, 1, 10, v -> page.getValue() == Pages.Render);
    private final Setting<Integer> slideDelay = new Setting<>("Slide Delay", 200, 1, 1000, v -> page.getValue() == Pages.Render);
    private final Setting<ColorSetting> textColor = new Setting<>("Text Color", new ColorSetting(Color.WHITE), v -> page.getValue() == Pages.Render);
    /** The real respawn-anchor block model at the target, instead of a plain cube. */
    private final Setting<Boolean> anchorModel = new Setting<>("AnchorModel", true, v -> page.getValue() == Pages.Render);
    private final Setting<Integer> anchorOpacity = new Setting<>("AnchorOpacity", 190, 40, 255, v -> page.getValue() == Pages.Render && anchorModel.getValue());

    /*   INFO   */
    private final Setting<Boolean> targetName = new Setting<>("TargetName", false, v -> page.getValue() == Pages.Info);
    private final Setting<Boolean> currentSide = new Setting<>("CurrentSide", true, v -> page.getValue() == Pages.Info);
    private final Setting<Boolean> speed = new Setting<>("Speed", true, v -> page.getValue() == Pages.Info);
    private final Setting<Boolean> confirmInfo = new Setting<>("ConfirmTime", true, v -> page.getValue() == Pages.Info);
    private final Setting<Boolean> calcInfo = new Setting<>("CalcInfo", false, v -> page.getValue() == Pages.Info);

    public static PlayerEntity target;
    private PlaceData bestPosition;
    private BlockHitResult bestAnchor;

    private final Timer placeTimer = new Timer();
    private final Timer breakTimer = new Timer();
    private final Timer pauseTimer = new Timer();

    private final Map<BlockPos, Long> placedAnchors = new HashMap<>();

    public float renderDamage, renderSelfDamage, rotationYaw, rotationPitch;

    private int prevAnchorAmmount, anchorSpeed, invTimer;

    private boolean rotated, facePlacing;

    private long confirmTime, calcTime;

    private BlockPos renderPos, prevRenderPos;
    /** Fades the anchor model in so it appears instead of popping into existence. */
    private final Animation modelFade = new Animation(180, Easing.EaseOut);
    private long renderMultiplier;
    private final Map<BlockPos, Long> renderPositions = new ConcurrentHashMap<>();

    public AutoAnchor() {
        super("AutoAnchor", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetVars();
    }

    @Override
    public void onDisable() {
        resetVars();
    }

    private void resetVars() {
        facePlacing = false;
        rotated = false;
        renderDamage = 0;
        renderSelfDamage = 0;
        renderMultiplier = 0;
        confirmTime = 0;
        renderPositions.clear();
        placedAnchors.clear();
        breakTimer.reset();
        placeTimer.reset();
        bestAnchor = null;
        bestPosition = null;
        renderPos = null;
        prevRenderPos = null;
        target = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTick(EventTick e) {
        if (mc.player == null || mc.world == null) return;

        long currentTime = System.currentTimeMillis();
        final List<PlaceData> blocks = getPossibleBlocks(target, mc.player.getEntityPos(), placeRange.getValue());
        calcPosition(blocks);
        getAnchorToExplode(blocks);
        calcTime = System.currentTimeMillis() - currentTime;

        if (timing.is(Timing.NORMAL)) {
            if (bestPosition != null && placeTimer.passedMs(facePlacing ? lowPlaceDelay.getValue() : (placeDelay.getValue())))
                placeAnchor(bestPosition, false);

            if (bestAnchor != null && breakTimer.passedMs(facePlacing ? lowExplodeDelay.getValue() : explodeDelay.getValue()))
                explodeAnchor(bestAnchor);
        }
    }

    @EventHandler
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (rotate.getValue())
            calcRotations();
    }

    @EventHandler
    public void onSync(EventSync e) {
        if (mc.player == null || mc.world == null) return;

        target = Managers.COMBAT.getTarget(targetRange.getValue(), targetLogic.getValue());

        if (target != null && (target.isDead() || target.getHealth() < 0)) {
            target = null;
            return;
        }

        if (invTimer++ >= 20) {
            anchorSpeed = MathUtility.clamp(prevAnchorAmmount - InventoryUtility.getItemCount(Items.RESPAWN_ANCHOR), 0, 255);
            prevAnchorAmmount = InventoryUtility.getItemCount(Items.RESPAWN_ANCHOR);
            invTimer = 0;
        }

        // Rotate
        if (rotate.getValue() && mc.player != null && rotationYaw != mc.player.getYaw() && rotationPitch != mc.player.getPitch()) {
            mc.player.setYaw(rotationYaw);
            mc.player.setPitch(rotationPitch);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Manual                                                              */
    /* ------------------------------------------------------------------ */

    private final Timer manualTimer = new Timer();

    /**
     * Manual mode: you place, it detonates.
     *
     * <p>No targeting, no damage maths, no waiting for a calculation to agree - an anchor you put down
     * yourself goes off the moment it exists, and a crystal goes down and breaks for as long as you hold
     * the button. The automatic mode decides WHERE to place; this one only decides HOW FAST, which is a
     * different job and the reason it is a mode rather than another delay setting.
     */
    private void manualTick() {
        if (!manualTimer.passedMs(manualDelay.getValue())) return;

        if (detonateAnchors()) {
            manualTimer.reset();
            return;
        }

        if (crystalSpam.getValue() && spamCrystal()) manualTimer.reset();
    }

    /**
     * Charges and sets off every anchor within reach.
     *
     * <p>Charging and detonating are the same right-click, so this does not have to tell them apart: an
     * uncharged anchor takes the glowstone, a charged one goes off. Doing both every pass means an
     * anchor you have just placed is charged on one tick and gone on the next.
     */
    private boolean detonateAnchors() {
        BlockPos origin = mc.player.getBlockPos();
        int reach = (int) Math.ceil(placeRange.getValue());

        for (BlockPos pos : BlockPos.iterate(origin.add(-reach, -reach, -reach), origin.add(reach, reach, reach))) {
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isOf(Blocks.RESPAWN_ANCHOR)) continue;
            if (InteractionUtility.squaredDistanceFromEyes(pos.toCenterPos()) > placeRange.getPow2Value()) continue;

            int charges = state.get(RespawnAnchorBlock.CHARGES);

            // An uncharged anchor needs glowstone in hand; a charged one is set off with anything.
            if (charges == 0) {
                SearchInvResult glowstone = InventoryUtility.findItemInHotBar(Items.GLOWSTONE);
                if (!glowstone.found()) continue;
                glowstone.switchTo();
            }

            BlockHitResult hit = new BlockHitResult(pos.toCenterPos(), Direction.UP, pos.toImmutable(), false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }

        return false;
    }

    /**
     * One place-and-break of a crystal, while the use button is held.
     *
     * <p>Tied to the button rather than running on its own: this is a spammer, and a spammer that fires
     * without being asked is just an aura with fewer checks. Holding the button is the whole input.
     */
    private boolean spamCrystal() {
        if (!mc.options.useKey.isPressed()) return false;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return false;

        // Crystals only sit on obsidian and bedrock, so anything else is not a miss worth sending.
        BlockState base = mc.world.getBlockState(hit.getBlockPos());
        if (!base.isOf(Blocks.OBSIDIAN) && !base.isOf(Blocks.BEDROCK)) return false;

        SearchInvResult crystal = InventoryUtility.findItemInHotBar(Items.END_CRYSTAL);
        if (!crystal.found()) return false;
        crystal.switchTo();

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);

        // And break whatever is now standing there, in the same pass - the gap between the two is the
        // only thing that lets somebody else take the crystal off you.
        Box above = new Box(hit.getBlockPos().up());
        for (net.minecraft.entity.Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof net.minecraft.entity.decoration.EndCrystalEntity)) continue;
            if (!entity.getBoundingBox().intersects(above)) continue;

            mc.interactionManager.attackEntity(mc.player, entity);
            mc.player.swingHand(Hand.MAIN_HAND);
            break;
        }

        return true;
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mode.getValue() == Mode.Manual) {
            manualTick();
            return;
        }

        if (timing.is(Timing.SEQUENTIAL)) {
            if (bestPosition != null && placeTimer.passedMs(facePlacing ? lowPlaceDelay.getValue() : (placeDelay.getValue())))
                placeAnchor(bestPosition, false);

            if (bestAnchor != null && breakTimer.passedMs(facePlacing ? lowExplodeDelay.getValue() : explodeDelay.getValue()))
                explodeAnchor(bestAnchor);
        }
    }

    @Override
    public String getDisplayInfo() {
        StringBuilder info = new StringBuilder();

        Direction side = bestPosition == null ? null : bestPosition.bhr().getSide();
        if (side != null) {
            if (targetName.getValue() && target != null) info.append(target.getName().getString()).append(" | ");
            if (speed.getValue()) info.append(anchorSpeed).append(" c/s").append(" | ");
            if (currentSide.getValue()) info.append(side.toString().toUpperCase()).append(" | ");
            if (confirmInfo.getValue()) info.append("c: ").append(confirmTime).append(" | ");
            if (calcInfo.getValue()) info.append("calc: ").append(calcTime).append(" | ");
        }
        return info.length() < 4 ? info.toString() : info.substring(0, info.length() - 3);
    }

    @EventHandler
    public void onBlockDestruct(EventSetBlockState e) {
        if (mc.player == null || mc.world == null) return;

        if (e.getPrevState() == null || e.getState() == null)
            return;

        if (target != null && target.squaredDistanceTo(e.getPos().toCenterPos()) <= 4 && e.getState().getBlock() instanceof RespawnAnchorBlock && e.getPrevState().isReplaceable()) {
            debug("Detected change of state " + e.getPos() + ", exploding...");
            //explodeAnchor(getInteractResult(e.getPos()));
        }
    }

    public void calcRotations() {
        if (rotate.getValue() && !shouldPause() && (bestPosition != null || bestAnchor != null) && mc.player != null) {
            Vec3d vec = bestPosition == null ? bestAnchor.getPos() : bestPosition.bhr().getPos();

            float yawDelta = wrapDegrees((float) wrapDegrees(Math.toDegrees(Math.atan2(vec.z - mc.player.getZ(), (vec.x - mc.player.getX()))) - 90) - rotationYaw);
            float pitchDelta = ((float) (-Math.toDegrees(Math.atan2(vec.y - (mc.player.getEntityPos().y + mc.player.getEyeHeight(mc.player.getPose())), Math.sqrt(Math.pow((vec.x - mc.player.getX()), 2) + Math.pow(vec.z - mc.player.getZ(), 2))))) - rotationPitch);

            yawDelta = (float) (yawDelta + Render2DEngine.interpolate(-1.2f, 1.2f, Math.sin(mc.player.age % 80)) + MathUtility.random(-1.2f, 1.2f));
            pitchDelta = pitchDelta + MathUtility.random(-0.8f, 0.8f);

            if (yawDelta > 180)
                yawDelta = yawDelta - 180;

            float yawStepVal = 180f;

            if (yawStep.getValue().isEnabled())
                yawStepVal = yawAngle.getValue();

            float clampedYawDelta = MathHelper.clamp(MathHelper.abs(yawDelta), -yawStepVal, yawStepVal);
            float clampedPitchDelta = MathHelper.clamp(pitchDelta, -45, 45);

            rotated = MathHelper.abs(yawDelta) <= yawStepVal || !yawStep.getValue().isEnabled();

            float newYaw = rotationYaw + (yawDelta > 0 ? clampedYawDelta : -clampedYawDelta);
            float newPitch = MathHelper.clamp(rotationPitch + clampedPitchDelta, -90.0F, 90.0F);

            double gcdFix = (Math.pow(mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2, 3.0)) * 1.2;

            rotationYaw = (float) (newYaw - (newYaw - rotationYaw) % gcdFix);
            rotationPitch = (float) (newPitch - (newPitch - rotationPitch) % gcdFix);

            ModuleManager.rotations.fixRotation = rotationYaw;
        } else {
            rotationYaw = mc.player.getYaw();
            rotationPitch = mc.player.getPitch();
        }
    }

    /**
     * The anchor preview itself: the real respawn anchor block model, drawn where the next one is going,
     * through walls and at full brightness. This is what makes it read as an anchor sitting in the world
     * rather than a cube floating somewhere near it.
     */
    @Override
    public void onRender3D(MatrixStack stack) {
        if (!render.getValue() || !anchorModel.getValue() || fullNullCheck()) return;

        BlockPos pos = renderPos;
        if (pos == null || renderPositions.isEmpty()) return;

        // Charged, because an uncharged anchor has no glowing face and the preview would be hard to spot.
        BlockState state = Blocks.RESPAWN_ANCHOR.getDefaultState().with(RespawnAnchorBlock.CHARGES, 1);
        Color accent = lineColor.getValue().getColorObject();

        modelFade.setForward(true);
        float fade = modelFade.get();

        BlockModelRender.render(stack, state, pos, (int) (anchorOpacity.getValue() * fade), 0.98f,
                1f, 1f, 1f, true);
        BlockModelRender.flush();
    }

    /**
     * Boxes, the fade trail and the damage numbers. They live on the 2D pass as projected geometry: the
     * world-space FILLED/OUTLINE queues double-rotate on 1.21.11, which is why these used to drift off
     * their block and show up as cubes floating in mid air as the camera turned.
     */
    @Override
    public void onRender2D(DrawContext context) {
        if (!render.getValue() || fullNullCheck() || mc.options.hudHidden) return;

        final Object2ObjectMap<BlockPos, Long> cache = new Object2ObjectOpenHashMap<>(renderPositions);

        cache.forEach((pos, time) -> {
            if (System.currentTimeMillis() - time > 500)
                renderPositions.remove(pos);
        });

        String dmg = MathUtility.round2(renderDamage) + (rselfDamage.getValue() ? " / " + MathUtility.round2(renderSelfDamage) : "");

        if (renderMode.getValue() == Render.Fade) {
            List<Render3DEngine.BoxSpec> boxes = new ArrayList<>();

            cache.forEach((pos, time) -> {
                long age = System.currentTimeMillis() - time;
                if (age >= 500) return;

                int alpha = (int) (100f * (1f - (age / 500f)));
                boxes.add(new Render3DEngine.BoxSpec(new Box(pos),
                        Render2DEngine.injectAlpha(fillColor.getValue().getColorObject(), alpha),
                        Render2DEngine.injectAlpha(lineColor.getValue().getColorObject(), Math.min(255, alpha * 2)),
                        lineWidth.getValue(), true, true));

                if (drawDamage.getValue())
                    Render3DEngine.drawTextIn3D(dmg, pos.toCenterPos(), 0, 0.1, 0, Render2DEngine.applyOpacity(textColor.getValue().getColorObject(), alpha / 100f));
            });

            Render3DEngine.drawProjectedBoxes(context, boxes);
            return;
        }

        if (renderPositions.isEmpty() || renderPos == null) return;

        if (renderMode.getValue() == Render.Slide) {
            if (prevRenderPos == null) prevRenderPos = renderPos;
            float mult = MathUtility.clamp((System.currentTimeMillis() - renderMultiplier) / (float) slideDelay.getValue(), 0f, 1f);
            renderBox(context, dmg, Render3DEngine.interpolateBox(new Box(prevRenderPos), new Box(renderPos), mult));
        } else {
            renderBox(context, dmg, new Box(renderPos));
        }
    }

    private void renderBox(DrawContext context, String dmg, Box box) {
        Render3DEngine.drawProjectedBox(context, box, fillColor.getValue().getColorObject(), fillColor.getValue().getColorObject(),
                Render2DEngine.injectAlpha(lineColor.getValue().getColorObject(), 255), lineWidth.getValue(), true, true);

        if (drawDamage.getValue())
            Render3DEngine.drawTextIn3D(dmg, box.getCenter(), 0, 0.1, 0, textColor.getValue().getColorObject());
    }

    public boolean shouldPause() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return true;

        boolean offhand = mc.player.getOffHandStack().getItem() == Items.RESPAWN_ANCHOR;
        boolean mainHand = mc.player.getMainHandStack().getItem() == Items.RESPAWN_ANCHOR;

        boolean offhandGlow = mc.player.getOffHandStack().getItem() == Items.GLOWSTONE;
        boolean mainHandGlow = mc.player.getMainHandStack().getItem() == Items.GLOWSTONE;

        if (!pauseTimer.passedMs(1000))
            return true;

        if (mc.interactionManager.isBreakingBlock() && !offhand && mining.getValue())
            return true;

        if (autoSwitch.is(Switch.NONE) && !offhand && !mainHand)
            return true;

        if ((autoSwitch.is(Switch.SILENT) || autoSwitch.is(Switch.NORMAL)) && !InventoryUtility.findItemInHotBar(Items.RESPAWN_ANCHOR).found() && !offhand)
            return true;

        if (autoSwitch.is(Switch.INVENTORY) && !InventoryUtility.findItemInInventory(Items.RESPAWN_ANCHOR).found() && !offhand)
            return true;

        if (autoSwitch.is(Switch.NONE) && !offhandGlow && !mainHandGlow)
            return true;

        if ((autoSwitch.is(Switch.SILENT) || autoSwitch.is(Switch.NORMAL)) && !InventoryUtility.findItemInHotBar(Items.GLOWSTONE).found() && !offhandGlow)
            return true;

        if (autoSwitch.is(Switch.INVENTORY) && !InventoryUtility.findItemInInventory(Items.GLOWSTONE).found() && !offhandGlow)
            return true;

        if (mc.player.isUsingItem() && eating.getValue())
            return true;

        if (rotationMarkedDirty())
            return true;

        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() < pauseHP.getValue())
            return true;

        boolean silentWeakness = antiWeakness.getValue() == Switch.SILENT || antiWeakness.getValue() == Switch.INVENTORY;

        boolean silent = autoSwitch.getValue() == Switch.SILENT || autoSwitch.getValue() == Switch.INVENTORY;

        return switchPause.getValue().isEnabled() && !Managers.PLAYER.switchTimer.passedMs(switchDelay.getValue()) && !silent && !silentWeakness;
    }

    public boolean rotationMarkedDirty() {
        if (ModuleManager.surround.isEnabled() && !ModuleManager.surround.inactivityTimer.passedMs(500) && surround.getValue())
            return true;

        if (ModuleManager.middleClick.isEnabled() && mc.options.pickItemKey.isPressed() && middleClick.getValue())
            return true;

        if (ModuleManager.autoTrap.isEnabled() && !ModuleManager.surround.inactivityTimer.passedMs(500))
            return true;
        if (ModuleManager.holeFill.isEnabled() && !HoleFill.inactivityTimer.passedMs(500))
            return true;

        if (ModuleManager.aura.isEnabled() && aura.getValue())
            return true;
        return false;
    }

    public void explodeAnchor(BlockHitResult bhr) {
        if (shouldPause() || mc.player == null) return;
        int prevSlot = -1;

        SearchInvResult glowResult = InventoryUtility.findItemInHotBar(Items.GLOWSTONE);
        SearchInvResult anchorResult = InventoryUtility.findInHotBar(stack -> !(stack.getItem() instanceof BlockItem));
        SearchInvResult glowInvResult = InventoryUtility.findItemInInventory(Items.GLOWSTONE);

        boolean offhand = mc.player.getOffHandStack().getItem() == Items.GLOWSTONE;
        boolean holdingAnchor = mc.player.getMainHandStack().getItem() == Items.GLOWSTONE || offhand;

        if (rotate.getValue() && !rotated)
            return;

        if (autoSwitch.getValue() != Switch.NONE && !holdingAnchor)
            prevSlot = switchTo(glowResult, glowInvResult, autoSwitch);

        if (!(mc.player.getMainHandStack().getItem() == Items.GLOWSTONE || offhand || autoSwitch.getValue() == Switch.SILENT))
            return;

        if (ak47.is(AK47.OFF)) {
            if (mc.player.isSneaking())
                sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, false, false)));

            if (mc.world.getBlockState(bhr.getBlockPos()).get(RespawnAnchorBlock.CHARGES) == 0) {
                sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(offhand ? Hand.OFF_HAND : Hand.MAIN_HAND, bhr, id));
                mc.player.swingHand(offhand ? Hand.OFF_HAND : Hand.MAIN_HAND);
            } else {
                anchorResult.switchTo();
                sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, id));
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        } else {
            if (mc.player.isSneaking())
                sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, false, false)));
            sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(offhand ? Hand.OFF_HAND : Hand.MAIN_HAND, bhr, id));
            mc.player.swingHand(offhand ? Hand.OFF_HAND : Hand.MAIN_HAND);

            anchorResult.switchTo();
            sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, id));
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        breakTimer.reset();

        postPlaceSwitch(prevSlot);
    }

    private int switchTo(SearchInvResult result, SearchInvResult resultInv, @NotNull Setting<Switch> switchMode) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return -1;

        int prevSlot = mc.player.getInventory().selectedSlot;

        switch (switchMode.getValue()) {
            case INVENTORY -> {
                if (resultInv.found()) {
                    prevSlot = resultInv.slot();
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, prevSlot, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
                    sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
                }
            }
            case NORMAL -> result.switchTo();
            case SILENT -> result.switchToSilent();
            case NONE -> {
            }
        }
        return prevSlot;
    }

    public void placeAnchor(PlaceData data, boolean instant) {
        if (shouldPause() || mc.player == null || data.anchor()) return;
        int prevSlot = -1;

        SearchInvResult anchorResult = InventoryUtility.findItemInHotBar(Items.RESPAWN_ANCHOR);
        SearchInvResult anchorInvResult = InventoryUtility.findItemInInventory(Items.RESPAWN_ANCHOR);

        boolean offhand = mc.player.getOffHandStack().getItem() == Items.RESPAWN_ANCHOR;
        boolean holdingAnchor = mc.player.getMainHandStack().getItem() == Items.RESPAWN_ANCHOR || offhand;

        if (rotate.getValue()) {
            if (instant) {
                float[] angle = InteractionUtility.calculateAngle(data.bhr().getPos());
                sendPacket(new PlayerMoveC2SPacket.Full(mc.player.getX(), mc.player.getY(), mc.player.getZ(), angle[0], angle[1], mc.player.isOnGround(), mc.player.horizontalCollision));
            } else if (!rotated)
                return;
        }

        if (autoSwitch.getValue() != Switch.NONE && !holdingAnchor)
            prevSlot = switchTo(anchorResult, anchorInvResult, autoSwitch);

        if (!(mc.player.getMainHandStack().getItem() == Items.RESPAWN_ANCHOR || offhand || autoSwitch.getValue() == Switch.SILENT))
            return;

        sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(offhand ? Hand.OFF_HAND : Hand.MAIN_HAND, data.bhr(), id));

        mc.player.swingHand(offhand ? Hand.OFF_HAND : Hand.MAIN_HAND);
        placeTimer.reset();

        if (!data.bp().equals(renderPos)) {
            renderMultiplier = System.currentTimeMillis();
            prevRenderPos = renderPos;
            renderPos = data.bp();
        }

        //if (!placedAnchors.containsKey(data.bp()))
        //placedAnchors.put(data.bp(), System.currentTimeMillis());

        renderPositions.put(data.bp(), System.currentTimeMillis());

        if (ak47.is(AK47.ON)) {
            BlockHitResult placeResult = getInteractResult(data.bp);
            if (placeResult != null)
                explodeAnchor(placeResult);
        }

        postPlaceSwitch(prevSlot);
    }

    private void postPlaceSwitch(int slot) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (autoSwitch.getValue() == Switch.SILENT )
            InventoryUtility.switchTo(slot);

        if (autoSwitch.getValue() == Switch.INVENTORY && slot != -1) {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
            sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
    }

    public void calcPosition(List<PlaceData> blocks) {
        if (mc.player == null || mc.world == null) return;
        if (target == null) {
            renderPos = null;
            prevRenderPos = null;
            bestPosition = null;
            return;
        }
        List<PlaceData> list = blocks.stream().filter(data -> isSafe(data.damage, data.selfDamage, data.overrideDamage)).toList();
        bestPosition = list.isEmpty() ? null : filterPositions(list);
    }

    private @NotNull List<PlaceData> getPossibleBlocks(PlayerEntity target, Vec3d center, float range) {
        List<PlaceData> blocks = new ArrayList<>();
        BlockPos playerPos = BlockPos.ofFloored(center);

        for (int x = (int) Math.floor(playerPos.getX() - range); x <= Math.ceil(playerPos.getX() + range); x++) {
            for (int y = (int) Math.floor(playerPos.getY() - range); y <= Math.ceil(playerPos.getY() + range); y++) {
                for (int z = (int) Math.floor(playerPos.getZ() - range); z <= Math.ceil(playerPos.getZ() + range); z++) {
                    PlaceData data = getPlaceData(new BlockPos(x, y, z), target);
                    if (data != null)
                        blocks.add(data);
                }
            }
        }

        return blocks;
    }

    public void getAnchorToExplode(List<PlaceData> blocks) {
        if (target == null) {
            bestAnchor = null;
            return;
        }

        List<PlaceData> list = blocks.stream().filter(data -> isSafe(data.damage, data.selfDamage, data.overrideDamage) && data.anchor()).toList();
        bestAnchor = list.isEmpty() ? null : filterAnchors(list);
    }

    public boolean isSafe(float damage, float selfDamage, boolean overrideDamage) {
        if (mc.player == null || mc.world == null) return false;

        if (overrideDamage)
            return true;

        if (selfDamage + 0.5 > mc.player.getHealth() + mc.player.getAbsorptionAmount()) return false;
        else if (safety.getValue() == Safety.STABLE) return damage - selfDamage > 0;
        else if (safety.getValue() == Safety.BALANCE) return (damage * safetyBalance.getValue()) - selfDamage > 0;
        return true;
    }

    private @Nullable PlaceData filterPositions(@NotNull List<PlaceData> clearedList) {
        PlaceData bestData = null;

        float bestVal = 0f;
        for (PlaceData data : clearedList) {
            if ((shouldOverride(data.damage) || data.damage > minDamage.getValue())) {
                if (sort.getValue() == Sort.DAMAGE) {
                    if (bestVal < data.damage) {
                        bestData = data;
                        bestVal = data.damage;
                    }
                } else {
                    if (bestVal < data.damage / data.selfDamage) {
                        bestData = data;
                        bestVal = data.damage / data.selfDamage;
                    }
                }
            }
        }

        if (bestData == null) return null;
        facePlacing = bestData.damage < minDamage.getValue();
        renderDamage = bestData.damage;
        renderSelfDamage = bestData.selfDamage;
        return bestData;
    }

    public boolean shouldOverride(float damage) {
        if (target == null) return false;

        boolean override = target.getHealth() + target.getAbsorptionAmount() <= facePlaceHp.getValue();

        if (armorBreaker.getValue().isEnabled())
            for (ItemStack armor : thunder.hack.utility.player.InventoryUtility.getArmor(target))
                if (armor != null && !armor.getItem().equals(Items.AIR) && ((armor.getMaxDamage() - armor.getDamage()) / (float) armor.getMaxDamage()) * 100 < armorScale.getValue()) {
                    override = true;
                    break;
                }

        if (facePlaceButton.getValue().getKey() != -1 && InputUtil.isKeyPressed(mc.getWindow(), facePlaceButton.getValue().getKey()))
            override = true;

        if ((target.getHealth() + target.getAbsorptionAmount()) - (damage * lethalMultiplier.getValue()) < 0.5)
            override = true;

        return override;
    }

    private @Nullable BlockHitResult filterAnchors(@NotNull List<PlaceData> clearedList) {
        PlaceData bestData = null;
        float bestVal = 0f;
        for (PlaceData data : clearedList) {
            if ((shouldOverride(data.damage) || data.damage > minDamage.getValue())) {
                if (sort.getValue() == Sort.DAMAGE) {
                    if (bestVal < data.damage) {
                        bestData = data;
                        bestVal = data.damage;
                    }
                } else {
                    if (bestVal < data.damage / data.selfDamage) {
                        bestData = data;
                        bestVal = data.damage / data.selfDamage;
                    }
                }
            }
        }

        if (bestData == null) return null;
        renderDamage = bestData.damage;
        renderSelfDamage = bestData.selfDamage;
        return bestData.bhr;
    }

    public @Nullable PlaceData getPlaceData(BlockPos bp, PlayerEntity target) {
        if (mc.player == null || mc.world == null)
            return null;

        if (target != null && target.getEntityPos().squaredDistanceTo(bp.toCenterPos()) > 144)
            return null;

        BlockState state = mc.world.getBlockState(bp);
        boolean isAnchor = state.getBlock() instanceof RespawnAnchorBlock;

        if (!state.isReplaceable() && !isAnchor)
            return null;

        if (isAnchor)
            mc.world.setBlockState(bp, Blocks.AIR.getDefaultState());

        BlockHitResult placeResult = InteractionUtility.getPlaceResult(bp, interact.getValue(), false);

        if (placeResult == null) {
            if (isAnchor)
                mc.world.setBlockState(bp, state);
            return null;
        }

        float damage = target == null ? 10f : ExplosionUtility.getAutoCrystalDamage(bp.toCenterPos(), target, predictTicks.getValue(), useOptimizedCalc.getValue());
        if (damage < 1.5f) {
            if (isAnchor)
                mc.world.setBlockState(bp, state);
            return null;
        }

        float selfDamage = ExplosionUtility.getAutoCrystalDamage(bp.toCenterPos(), mc.player, selfPredictTicks.getValue(), useOptimizedCalc.getValue());
        boolean overrideDamage = shouldOverrideDamage(damage, selfDamage);

        if (protectFriends.getValue()) {
            List<PlayerEntity> players = Lists.newArrayList(mc.world.getPlayers());
            for (PlayerEntity pl : players) {
                if (!Managers.FRIEND.isFriend(pl)) continue;
                float fdamage = ExplosionUtility.getAutoCrystalDamage(bp.toCenterPos(), pl, selfPredictTicks.getValue(), useOptimizedCalc.getValue());
                if (fdamage > selfDamage) {
                    selfDamage = fdamage;
                }
            }
        }

        if (isAnchor) {
            mc.world.setBlockState(bp, state);
            placeResult = getInteractResult(bp);
        }

        if (placeResult == null)
            return null;

        if (selfDamage > maxSelfDamage.getValue() && !overrideDamage) return null;

        return new PlaceData(placeResult, bp, damage, selfDamage, overrideDamage, isAnchor);
    }

    public boolean shouldOverrideDamage(float damage, float selfDamage) {
        if (overrideSelfDamage.getValue() && target != null) {
            if (mc.player == null || mc.world == null) return false;

            boolean targetSafe = (target.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING
                    || target.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING);

            boolean playerSafe = (mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING
                    || mc.player.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING);

            float targetHp = target.getHealth() + target.getAbsorptionAmount() - 1f;

            float playerHp = mc.player.getHealth() + mc.player.getAbsorptionAmount() - 1f;

            boolean canPop = damage > targetHp && targetSafe;

            boolean canKill = damage > targetHp && !targetSafe;

            boolean canPopSelf = selfDamage > playerHp && playerSafe;

            boolean canKillSelf = selfDamage > playerHp && !playerSafe;

            if (canPopSelf && canKill)
                return true;

            return selfDamage > maxSelfDamage.getValue() && (canPop || canKill) && !canKillSelf && !canPopSelf;
        }
        return false;
    }

    public BlockHitResult getInteractResult(BlockPos bp) {
        BlockHitResult interactResult = null;
        switch (interact.getValue()) {
            case Vanilla, AirPlace -> interactResult = getDefaultInteract(bp);
            case Strict -> interactResult = getStrictInteract(bp);
            case Legit -> interactResult = getLegitInteract(bp);
        }
        return interactResult;
    }

    private @Nullable BlockHitResult getDefaultInteract(BlockPos bp) {
        if (mc.player == null || mc.world == null) return null;

        Vec3d vec = bp.toCenterPos().add(0, -0.5, 0);

        if (PlayerUtility.squaredDistanceFromEyes(vec) > placeRange.getPow2Value())
            return null;

        BlockHitResult wallCheck = mc.world.raycast(new RaycastContext(InteractionUtility.getEyesPos(mc.player), vec, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

        if (wallCheck != null && wallCheck.getType() == HitResult.Type.BLOCK && wallCheck.getBlockPos() != bp)
            if (PlayerUtility.squaredDistanceFromEyes(vec) > placeWallRange.getPow2Value())
                return null;

        return new BlockHitResult(vec, mc.world.isInBuildLimit(bp.up()) ? Direction.UP : Direction.DOWN, bp, false);
    }

    public BlockHitResult getStrictInteract(@NotNull BlockPos bp) {
        if (mc.player == null || mc.world == null) return null;

        float bestDistance = Float.MAX_VALUE;
        Direction bestDirection = null;
        Vec3d bestVector = null;

        float upPoint = bp.up().getY();

        if (mc.player.getEyePos().getY() > upPoint) {
            bestDirection = Direction.UP;
            bestVector = new Vec3d(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);
        } else if (mc.player.getEyePos().getY() < bp.getY() && mc.world.isAir(bp.down())) {
            bestDirection = Direction.DOWN;
            bestVector = new Vec3d(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
        } else {
            for (Direction dir : InteractionUtility.getStrictBlockDirections(bp)) {
                if (dir == Direction.UP || dir == Direction.DOWN)
                    continue;

                Vec3d directionVec = new Vec3d(bp.getX() + 0.5 + dir.getVector().getX() * 0.5, bp.getY() + 0.9, bp.getZ() + 0.5 + dir.getVector().getZ() * 0.5);

                if (!mc.world.isAir(bp.offset(dir)))
                    continue;

                float distance = PlayerUtility.squaredDistanceFromEyes(directionVec);
                if (bestDistance > distance) {
                    bestDirection = dir;
                    bestVector = directionVec;
                    bestDistance = distance;
                }
            }
        }

        if (bestVector == null) return null;

        if (PlayerUtility.squaredDistanceFromEyes(bestVector) > placeRange.getPow2Value())
            return null;

        BlockHitResult wallCheck = mc.world.raycast(new RaycastContext(InteractionUtility.getEyesPos(mc.player), bestVector, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

        if (wallCheck != null && wallCheck.getType() == HitResult.Type.BLOCK && wallCheck.getBlockPos() != bp)
            if (PlayerUtility.squaredDistanceFromEyes(bestVector) > placeWallRange.getPow2Value())
                return null;

        return new BlockHitResult(bestVector, bestDirection, bp, false);
    }

    public BlockHitResult getLegitInteract(BlockPos bp) {
        if (mc.player == null || mc.world == null) return null;

        float bestDistance = Float.MAX_VALUE;
        BlockHitResult bestResult = null;
        for (float x = 0f; x <= 1f; x += 0.2f) {
            for (float y = 0f; y <= 1f; y += 0.2f) {
                for (float z = 0f; z <= 1f; z += 0.2f) {
                    Vec3d point = new Vec3d(bp.getX() + x, bp.getY() + y, bp.getZ() + z);
                    float distance = PlayerUtility.squaredDistanceFromEyes(point);

                    BlockHitResult wallCheck = mc.world.raycast(new RaycastContext(InteractionUtility.getEyesPos(mc.player), point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
                    if (wallCheck != null && wallCheck.getType() == HitResult.Type.BLOCK && wallCheck.getBlockPos() != bp)
                        if (distance > placeWallRange.getPow2Value())
                            continue;


                    BlockHitResult result = ExplosionUtility.rayCastBlock(new RaycastContext(InteractionUtility.getEyesPos(mc.player), point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player), bp);
                    if (distance > placeRange.getPow2Value())
                        continue;

                    if (distance < bestDistance) {
                        if (result != null && result.getType() == HitResult.Type.BLOCK) {
                            bestResult = result;
                            bestDistance = distance;
                        }
                    }
                }
            }
        }
        return bestResult;
    }

    public void pause() {
        pauseTimer.reset();
    }

    public record PlaceData(BlockHitResult bhr, BlockPos bp, float damage, float selfDamage, boolean overrideDamage,
                            boolean anchor) {
    }

    /** Whether the module does the deciding, or only reacts to what you do. */
    public enum Mode {
        Auto, Manual
    }

    private enum Pages {
        Place, Break, Pause, Render, Damages, Main, Switch, Info
    }

    private enum Switch {
        NONE, NORMAL, SILENT, INVENTORY
    }

    private enum Timing {
        NORMAL, SEQUENTIAL
    }

    public enum Safety {
        BALANCE, STABLE, NONE
    }

    public enum Sort {
        SAFE, DAMAGE
    }

    public enum Render {
        Fade, Slide, Default
    }

    public enum AK47 {
        OFF, ON, SEMI
    }
}
