package thunder.hack.injection;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import thunder.hack.core.Managers;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thunder.hack.ThunderHack;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.ClientSettings;
import thunder.hack.features.modules.player.NoEntityTrace;
import thunder.hack.utility.math.FrameRateCounter;
import thunder.hack.utility.render.BlockAnimationUtility;
import thunder.hack.utility.render.Render3DEngine;

import static thunder.hack.features.modules.Module.mc;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    // 1.21.11: GameRenderer.zoom/zoomX/zoomY removed (spyglass zoom refactored); viewDistance -> viewDistanceBlocks
    @Shadow
    private float viewDistanceBlocks;

    @Shadow
    public abstract void tick();

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiler/Profiler;pop()V", ordinal = 1, shift = At.Shift.BEFORE), method = "render")
    void postHudRenderHook(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        FrameRateCounter.INSTANCE.recordFrame();
    }

    // The world's projection (world FOV, view bobbing, hurt tilt, nausea), captured where renderWorld sets it.
    // By the time render3dHook runs, vanilla has already switched to the HAND projection (fixed FOV, no bobbing)
    // for renderHand - drawing world visuals with that one put them at the wrong place on screen, so lanterns,
    // particles etc. slid across the world whenever the camera turned.
    @Unique
    private static com.mojang.blaze3d.buffers.GpuBufferSlice thWorldProjection;

    @Redirect(method = "renderWorld", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/systems/ProjectionType;)V", ordinal = 0))
    private void captureWorldProjection(com.mojang.blaze3d.buffers.GpuBufferSlice slice, com.mojang.blaze3d.systems.ProjectionType type) {
        thWorldProjection = slice;
        RenderSystem.setProjectionMatrix(slice, type);
    }

    /** The world projection as a matrix (FOV + bobbing + hurt tilt + nausea), for the 2D world-to-screen projection. */
    @Unique
    private static final Matrix4f thWorldProjMatrix = new Matrix4f();
    @Unique
    private static boolean thHaveWorldProjMatrix;

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderWorld", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/RawProjectionMatrix;set(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private Matrix4f captureWorldProjMatrix(Matrix4f matrix) {
        thWorldProjMatrix.set(matrix);
        thHaveWorldProjMatrix = true;
        return matrix;
    }

    // 1.21.11: GameRenderer.renderHand boolean field removed. Re-anchor to just BEFORE the
    // renderHand(FZLorg/joml/Matrix4f;)V call (world drawn, hand not yet) — same point as the old gate.
    //
    // Now anchored just BEFORE the depth clear that precedes renderHand: world drawn, its depth still intact.
    // Right before renderHand(...) is AFTER renderWorld wipes the depth texture for the hand, so every 3D
    // visual was depth-tested against an empty buffer - terrain never hid anything, the sky dome painted over
    // trees and hills, and "ThroughWalls off" meant nothing.
    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V", shift = At.Shift.BEFORE))
    void render3dHook(RenderTickCounter tickCounter, CallbackInfo ci) {
        if (Module.fullNullCheck()) return;

        Camera camera = mc.gameRenderer.getCamera();
        MatrixStack matrixStack = new MatrixStack();
        matrixStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0f));
        // World geometry bakes the camera view-rotation into its vertices. The live vanilla modelview at
        // flush is already IDENTITY here, so we must NOT touch RenderSystem's modelview stack — doing so
        // (the old identity push) ended up applying the rotation a SECOND time and everything double-rotated
        // as the camera turned. Exactly one rotation (the baked one) = correct.

        float thFov = ((thunder.hack.injection.accesors.IGameRenderer) (Object) mc.gameRenderer)
                .thGetFov(camera, tickCounter.getTickProgress(true), true);
        // the exact matrix the terrain was drawn with, so projected ESP sits on the world even while bobbing
        if (thHaveWorldProjMatrix) Render3DEngine.lastProjMat.set(thWorldProjMatrix);
        else Render3DEngine.lastProjMat.set(mc.gameRenderer.getBasicProjectionMatrix(thFov));
        Render3DEngine.lastModMat.identity();
        Render3DEngine.lastWorldSpaceMatrix.set(matrixStack.peek().getPositionMatrix());

        // Vanilla has already set the hand projection here; draw the world visuals with the world's own
        // projection and hand the hand projection back afterwards so renderHand is untouched.
        var handProjection = RenderSystem.getProjectionMatrixBuffer();
        var handProjectionType = RenderSystem.getProjectionType();
        if (thWorldProjection != null)
            RenderSystem.setProjectionMatrix(thWorldProjection, com.mojang.blaze3d.systems.ProjectionType.PERSPECTIVE);

        // Custom Sky first: it only paints sky pixels (empty depth), and everything 3D below draws over it
        if (ModuleManager.customSky != null)
            ModuleManager.customSky.renderSky(Render3DEngine.lastProjMat, Render3DEngine.lastWorldSpaceMatrix);

        try {
            thunder.hack.utility.render.core.CloudanRenderCore.beginFrame3D(matrixStack);

            Managers.MODULE.onRender3D(matrixStack);

            Render3DEngine.onRender3D(matrixStack); // <- не двигать

            // Closes the frame: every core batch is flushed here, so a visual that returned early can never
            // strand a half-filled buffer for the next frame to draw in the wrong place.
            thunder.hack.utility.render.core.CloudanRenderCore.endFrame3D();
        } finally {
            if (handProjection != null)
                RenderSystem.setProjectionMatrix(handProjection, handProjectionType);
        }

        // ItemShader outline: last thing before renderHand, so "now vs. at the HUD" differs only by the hand
        if ((ModuleManager.itemShader != null && ModuleManager.itemShader.wantsOutline())
                || (ModuleManager.shaders != null && ModuleManager.shaders.wantsHandOutline()))
            thunder.hack.utility.render.ItemOutlineRenderer.captureBefore();
    }

    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderHand(FZLorg/joml/Matrix4f;)V", shift = At.Shift.AFTER))
    public void postRender3dHook(RenderTickCounter tickCounter, CallbackInfo ci) {
        if (Module.fullNullCheck()) return;
        Managers.SHADER.renderShaders();
    }

    @Redirect(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;lerp(FFF)F"))
    private float renderWorldHook(float delta, float first, float second) {
        if (ModuleManager.noRender.isEnabled() && ModuleManager.noRender.nausea.getValue()) return 0;
        return MathHelper.lerp(delta, first, second);
    }

    // 1.21.11: GameRenderer.loadPrograms -> preloadPrograms(ResourceFactory)
    @Inject(method = "preloadPrograms", at = @At(value = "RETURN"))
    private void loadSatinPrograms(ResourceFactory factory, CallbackInfo ci) {
    }

    // 1.21.11: findCrosshairTarget(Entity,DDF) removed; retarget FreeCam override to HEAD of updateCrosshairTarget.
    @Inject(method = "updateCrosshairTarget", at = @At("HEAD"), cancellable = true)
    private void onUpdateTargetedEntity(float tickDelta, CallbackInfo info) {
        if (Module.fullNullCheck()) return;

        if (ModuleManager.freeCam.isEnabled()) {
            info.cancel();
            mc.crosshairTarget = Managers.PLAYER.getRtxTarget(ModuleManager.freeCam.getFakeYaw(), ModuleManager.freeCam.getFakePitch(), ModuleManager.freeCam.getFakeX(), ModuleManager.freeCam.getFakeY(), ModuleManager.freeCam.getFakeZ());
        }
    }

    // 1.21.11: GameRenderer.findCrosshairTarget removed (raycast logic inlined into updateCrosshairTarget).
    // Disabled -> NoEntityTrace needs a new hook.
    /*
    @Inject(method = "findCrosshairTarget", at = @At("HEAD"), cancellable = true)
    private void findCrosshairTargetHook(Entity camera, double blockInteractionRange, double entityInteractionRange, float tickDelta, CallbackInfoReturnable<HitResult> cir) {
        if (ModuleManager.noEntityTrace.isEnabled() && (mc.player.getMainHandStack().isIn(net.minecraft.registry.tag.ItemTags.PICKAXES) || !NoEntityTrace.ponly.getValue())) {
            if (mc.player.getMainHandStack().isIn(net.minecraft.registry.tag.ItemTags.SWORDS) && NoEntityTrace.noSword.getValue()) return;
            double d = Math.max(blockInteractionRange, entityInteractionRange);
            Vec3d vec3d = camera.getCameraPosVec(tickDelta);
            HitResult hitResult = camera.raycast(d, tickDelta, false);
            cir.setReturnValue(ensureTargetInRangeCustom(hitResult, vec3d, blockInteractionRange));
        }
    }
    */

    // 1.21.11: getBasicProjectionMatrix(double)->(float); zoom fields removed.
    @Inject(method = "getBasicProjectionMatrix", at = @At("TAIL"), cancellable = true)
    public void getBasicProjectionMatrixHook(float fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (ModuleManager.aspectRatio.isEnabled()) {
            MatrixStack matrixStack = new MatrixStack();
            matrixStack.peek().getPositionMatrix().identity();
            matrixStack.peek().getPositionMatrix().mul(new Matrix4f().setPerspective((float) (fov * 0.01745329238474369), ModuleManager.aspectRatio.ratio.getValue(), 0.05f, viewDistanceBlocks * 4.0f));
            cir.setReturnValue(matrixStack.peek().getPositionMatrix());
        }
    }

    // 1.21.11: getFov now returns float (was double).
    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F", at = @At("TAIL"), cancellable = true)
    public void getFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cb) {
        if (ModuleManager.fov.isEnabled()) {
            if (cb.getReturnValue() == 70 && !ModuleManager.fov.itemFov.getValue() && mc.options.getPerspective() != Perspective.FIRST_PERSON)
                return;

            else if (ModuleManager.fov.itemFov.getValue() && cb.getReturnValue() == 70) {
                cb.setReturnValue(ModuleManager.fov.itemFovModifier.getValue().floatValue());
                return;
            }

            if (mc.player.isSubmergedInWater())
                return;

            cb.setReturnValue(ModuleManager.fov.fovModifier.getValue().floatValue());
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void bobViewHook(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (Module.fullNullCheck()) return;
        // No walking bob while flying/gliding — it shook the screen hard when flying.
        if (mc.player.getAbilities().flying || mc.player.isGliding()) {
            ci.cancel();
            return;
        }
        if (ModuleManager.noBob.isEnabled()) {
            ModuleManager.noBob.bobView(matrices, tickDelta);
            ci.cancel();
            return;
        }
        // CustomBob was a 1.21-era re-implementation of view bobbing driven by distanceTraveled, which on
        // 1.21.11 also advances while FALLING — so the whole view shook when walking/falling/running. Let
        // vanilla's native 1.21.11 bobView run instead; it's correct by definition. (Toggle kept as a
        // no-op for config compatibility; NoBob still overrides above.)
    }

    @Unique
    private HitResult ensureTargetInRangeCustom(HitResult hitResult, Vec3d cameraPos, double interactionRange) {
        Vec3d vec3d = hitResult.getPos();
        if (!vec3d.isInRange(cameraPos, interactionRange)) {
            Vec3d vec3d2 = hitResult.getPos();
            Direction direction = Direction.getFacing(vec3d2.x - cameraPos.x, vec3d2.y - cameraPos.y, vec3d2.z - cameraPos.z);
            return BlockHitResult.createMissed(vec3d2, direction, BlockPos.ofFloored(vec3d2));
        } else {
            return hitResult;
        }
    }

    @Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true)
    private void showFloatingItemHook(ItemStack floatingItem, CallbackInfo info) {
        if (ModuleManager.totemAnimation.isEnabled()) {
            ModuleManager.totemAnimation.showFloatingItem(floatingItem);
            info.cancel();
        }
    }

    // 1.21.11: GameRenderer.renderFloatingItem removed (floating-item render moved). Disabled
    // -> TotemAnimation custom floating-item render needs a new hook (render port already stubbed).
    /*
    @Inject(method = "renderFloatingItem", at = @At("HEAD"), cancellable = true)
    private void renderFloatingItemHook(DrawContext context, float tickDelta, CallbackInfo ci) {
        if (ModuleManager.totemAnimation.isEnabled()) {
            ModuleManager.totemAnimation.renderFloatingItem(tickDelta);
            ci.cancel();
        }
    }
    */

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void tiltViewWhenHurtHook(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (ModuleManager.noRender.isEnabled() && ModuleManager.noRender.hurtCam.getValue())
            ci.cancel();
    }
}
