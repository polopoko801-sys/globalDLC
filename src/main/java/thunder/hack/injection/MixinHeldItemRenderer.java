package thunder.hack.injection;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventHeldItemRenderer;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.render.ItemShader;

import static thunder.hack.features.modules.Module.mc;

@Mixin(HeldItemRenderer.class)
public abstract class MixinHeldItemRenderer {

    // The real per-item render on 1.21.11 (resolves the model and submits to the command queue). Shadowed so
    // ItemShader can draw extra scaled copies of the SAME item for the glow - real geometry, not a box.
    @org.spongepowered.asm.mixin.Shadow
    protected abstract void renderItem(net.minecraft.entity.LivingEntity entity, ItemStack stack,
                                       net.minecraft.item.ItemDisplayContext displayContext, MatrixStack matrices,
                                       net.minecraft.client.render.command.OrderedRenderCommandQueue queue, int light);

    // 1.21.11: renderFirstPersonItem's VertexConsumerProvider param became OrderedRenderCommandQueue
    // (render overhaul), and the @At INVOKE renderItem(...VCP...) anchor no longer exists. All three
    // hooks disabled -> EventHeldItemRenderer, Chams hand items, and Animations first-person custom
    // render need the new render path.
    /*
    @Inject(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ItemDisplayContext;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V"))
    private void onRenderItem(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if(Module.fullNullCheck()) return;
        EventHeldItemRenderer event = new EventHeldItemRenderer(hand, item, equipProgress, matrices);
        ThunderHack.EVENT_BUS.post(event);
    }

    @Inject(method = "renderFirstPersonItem", at = @At(value = "RETURN"))
    private void onRenderItemPost(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (ModuleManager.chams.isEnabled() && ModuleManager.chams.handItems.getValue())
            thunder.hack.utility.render.RSCompat.setShaderColor(1f,1f,1f,1f);
    }

    @Inject(method = "renderFirstPersonItem", at = @At(value = "HEAD"), cancellable = true)
    private void onRenderItemHook(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (Managers.MODULE != null && ModuleManager.animations.shouldAnimate() && !(item.isEmpty()) && !(item.getItem() instanceof FilledMapItem)) {
            ci.cancel();
            ModuleManager.animations.renderFirstPersonItemCustom(player, tickDelta, pitch, hand, swingProgress, item, equipProgress, matrices, vertexConsumers, light);
        }
    }
    */


    private void applyEatOrDrinkTransformationCustom(MatrixStack matrices, float tickDelta, Arm arm, @NotNull ItemStack stack) {
        float f = (float) mc.player.getItemUseTimeLeft() - tickDelta + 1.0F;
        float g = f / (float) stack.getMaxUseTime(mc.player);
        float h;
        if (g < 0.8F) {
            h = MathHelper.abs(MathHelper.cos(f / 4.0F * 3.1415927F) * 0.005F);
            matrices.translate(0.0F, h, 0.0F);
        }
        h = 1.0F - (float) Math.pow(g, 27.0);
        int i = arm == Arm.RIGHT ? 1 : -1;

        matrices.translate(h * 0.6F * (float) i * ModuleManager.viewModel.eatX.getValue(), h * -0.5F * ModuleManager.viewModel.eatY.getValue(), h * 0.0F);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) i * h * 90.0F));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(h * 10.0F));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) i * h * 30.0F));
    }

    @Inject(method = "applyEatOrDrinkTransformation", at = @At(value = "HEAD"), cancellable = true)
    private void applyEatOrDrinkTransformationHook(MatrixStack matrices, float tickDelta, Arm arm, ItemStack stack, PlayerEntity player, CallbackInfo ci) {
        if (ModuleManager.animations.isEnabled()) {
            applyEatOrDrinkTransformationCustom(matrices, tickDelta, arm, stack);
            ci.cancel();
        }
    }

    /**
     * First-person item hook: posts {@link EventHeldItemRenderer}, then applies SmallShield/SmallTotem.
     *
     * <p>The event used to be posted from a separate hook anchored on an {@code INVOKE} of
     * {@code renderItem(...VertexConsumerProvider...)}, which 1.21.11's render overhaul deleted. That
     * hook was commented out during the port and never replaced, so nothing has posted this event since
     * - which is why ViewModel appeared to do nothing at all: its settings were read by a handler that
     * was never called. This method already sits at the HEAD of the right method with the new
     * {@link OrderedRenderCommandQueue} signature, so the event is posted from here instead of
     * reintroducing a second hook on the same instruction.
     *
     * <p>HEAD is also where it has to happen: these are transforms on the matrix the item is about to be
     * drawn with, so they must land before the draw, not after it.
     */
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void smallItemHook(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        if (ModuleManager.swingAnimations != null && ModuleManager.swingAnimations.isEnabled())
            ModuleManager.swingAnimations.saveHandMatrix(matrices);

        if (!Module.fullNullCheck())
            ThunderHack.EVENT_BUS.post(new EventHeldItemRenderer(hand, item, equipProgress, matrices));

        if (ModuleManager.smallShield.isEnabled() && ModuleManager.smallShield.shield.getValue()
                && item.getItem() instanceof ShieldItem) {
            if (!ModuleManager.smallShield.onlyBlocking.getValue() || (mc.player != null && mc.player.isBlocking())) {
                float s = ModuleManager.smallShield.scale.getValue();
                matrices.scale(s, s, s);
            }
        }

        // Totem: driven by SmallShieldTotem, with the old standalone SmallTotem still honoured as a
        // fallback. Only ONE of them may scale, otherwise the two multiply into a speck.
        if (item.getItem() == Items.TOTEM_OF_UNDYING) {
            float s = 1f;
            if (ModuleManager.smallShield.isEnabled() && ModuleManager.smallShield.totem.getValue())
                s = ModuleManager.smallShield.totemScale.getValue();
            else if (ModuleManager.smallTotem.isEnabled())
                s = ModuleManager.smallTotem.scale.getValue();

            if (s != 1f) matrices.scale(s, s, s);
        }
    }
    // 1.21.11: renderItem(...VCP$Immediate...) and renderFirstPersonItem(...VCP...) now use
    // OrderedRenderCommandQueue. Disabled -> NoRender.noSwing first-person equip needs the new path.
    /*
    @ModifyArgs(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V"))
    private void renderItem(Args args) {
        if (ModuleManager.noRender.noSwing.getValue()) {
            args.set(6, 0.0F);
        }
    }
    */


    /**
     * Right before the item is drawn - the matrix is already in the hand, after vanilla's swing and equip
     * transforms. A rotation applied here turns the item about its own grip instead of around the camera,
     * which is what the old HEAD-level transforms did. Both renderItem calls (crossbow and the rest) go
     * through it.
     */
    @Inject(method = "renderFirstPersonItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"))
    private void thBeforeItemDraw(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        if (Module.fullNullCheck()) return;
        if (ModuleManager.animations.isEnabled())
            ModuleManager.animations.beforeItemDraw(hand, item, swingProgress, matrices);
        if (ModuleManager.swingAnimations.isEnabled())
            ModuleManager.swingAnimations.beforeItemDraw(hand, item, tickDelta, equipProgress, matrices);

        // Chams HandItems: tint the held item with setShaderColor. Set here (right before the draw) and NOT reset on
        // RETURN - the item renders deferred, after this returns, so a RETURN reset wiped the colour before the GPU
        // saw it (why it "did nothing"). It is cleared at the HEAD of the next item below.
        thunder.hack.features.modules.render.Chams chams = ModuleManager.chams;
        ItemShader shader = ModuleManager.itemShader;
        boolean shapeGlow = hand == Hand.MAIN_HAND && !item.isEmpty() && shader != null && shader.isEnabled() && shader.shapeGlowOn();

        if (chams != null && chams.isEnabled() && chams.handItems.getValue() && !item.isEmpty()) {
            java.awt.Color c = chams.handItemsColor.getValue().getColorObject();
            thunder.hack.utility.render.RSCompat.setShaderColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
        } else if (shapeGlow) {
            // Shape glow: draw a few copies of the real model, scaled up and tinted with the glow colour, then set
            // the colour back to white for the real item on top. The enlarged tinted copies peek past the edges ->
            // a glow that hugs the item's silhouette (the teal outline look). Reset of leftover colour is in
            // CloudanRenderCore.beginFrame3D so it never bleeds into the world.
            net.minecraft.item.ItemDisplayContext ctx = (mc.player != null && mc.player.getMainArm() == Arm.RIGHT)
                    ? net.minecraft.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                    : net.minecraft.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
            java.awt.Color g = shader.glowColor();
            int rings = shader.glowRings();
            for (int i = rings; i >= 1; i--) {
                float grow = 1f + shader.shapeRadius() * i / rings;
                float a = Math.max(0.05f, (shader.shapeAlpha() / 255f) * (1f - (i - 1f) / rings));
                thunder.hack.utility.render.RSCompat.setShaderColor(g.getRed() / 255f, g.getGreen() / 255f, g.getBlue() / 255f, a);
                matrices.push();
                matrices.scale(grow, grow, grow);
                renderItem(player, item, ctx, matrices, queue, 0xF000F0);
                matrices.pop();
            }
            thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, 1f);
        } else {
            thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, 1f);
        }

        // ItemShader glow: project the item's ACTUAL position (origin of its hand matrix) to the screen and store
        // it, so ItemShader.onRender2D can draw the glow exactly ON the item - any item, any perspective - instead
        // of a fixed corner. matrices here is model->view; RenderSystem projection is view->clip.
        if (hand == Hand.MAIN_HAND && !item.isEmpty() && ModuleManager.itemShader != null && ModuleManager.itemShader.isEnabled()) {
            try {
                org.joml.Matrix4f mv = new org.joml.Matrix4f(matrices.peek().getPositionMatrix());
                org.joml.Vector4f p = mv.transform(new org.joml.Vector4f(0f, 0f, 0f, 1f));
                p.mul(thunder.hack.utility.render.Render3DEngine.lastProjMat);
                if (p.w > 0.0001f) {
                    float ndcX = p.x / p.w, ndcY = p.y / p.w;
                    float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
                    float sx = (ndcX * 0.5f + 0.5f) * sw;
                    float sy = (1f - (ndcY * 0.5f + 0.5f)) * sh;
                    // approximate on-screen size from how far the item is (closer = bigger glow)
                    float dist = Math.max(0.3f, -mv.m32());
                    ItemShader.itemScreenX = sx;
                    ItemShader.itemScreenY = sy;
                    ItemShader.itemScreenSize = Math.clamp(140f / dist, 60f, 320f);
                    ItemShader.itemScreenValid = true;
                }
            } catch (Throwable ignored) {
            }
        }
    }

}
