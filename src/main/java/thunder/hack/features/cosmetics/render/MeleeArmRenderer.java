package thunder.hack.features.cosmetics.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/** Renders the player's skin at the authored melee hand attachments. Ported from BKClient (MIT, bkubiakdev). */
public final class MeleeArmRenderer {
    private static final String RIGHT_HAND = "righthand_pos";
    private static final String LEFT_HAND = "lefthand_pos";
    private static final float DEG = (float) (Math.PI / 180.0);
    private static final Matrix4f SUPPORTING_ITEM_ALIGN = supportingItemAlignment();

    private MeleeArmRenderer() {}

    /**
     * The shared transform is exactly the weapon transform: view matrix, equip drop,
     * optional X mirror, animated camera, and view anchor, in that order. Bone poses
     * are the fully composed BedrockModel poses, including ancestor transforms.
     */
    public static void render(AbstractClientPlayerEntity player, BedrockModel model,
            Matrix4f[] bonePose, Matrix4f sharedTransform, OrderedRenderCommandQueue queue,
            int light, boolean mirrored) {
        render(player, model, bonePose, sharedTransform, queue, light, mirrored, true, true);
    }

    /** Flags refer to authored hands, before the player's main-arm mirror is applied. */
    public static void render(AbstractClientPlayerEntity player, BedrockModel model,
            Matrix4f[] bonePose, Matrix4f sharedTransform, OrderedRenderCommandQueue queue,
            int light, boolean mirrored, boolean rightHand, boolean leftHand) {
        if (!rightHand && !leftHand) return;
        if (!(MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(player)
                instanceof PlayerEntityRenderer<?> renderer)) return;
        Identifier skin = player.getSkin().body().texturePath();
        if (rightHand) renderArm(player, renderer, skin, model, bonePose, sharedTransform,
                queue, light, mirrored, true);
        if (leftHand) renderArm(player, renderer, skin, model, bonePose, sharedTransform,
                queue, light, mirrored, false);
    }

    private static void renderArm(AbstractClientPlayerEntity player, PlayerEntityRenderer<?> renderer,
            Identifier skin, BedrockModel model, Matrix4f[] bonePose, Matrix4f sharedTransform,
            OrderedRenderCommandQueue queue, int light, boolean mirrored, boolean authoredRight) {
        Matrix4f transform = attachmentTransform(model, bonePose, sharedTransform, authoredRight);
        if (transform == null) return;
        // Mirror the attachment, but use the actual opposite arm rather than a mirrored skin.
        if (mirrored) transform.scale(-1, 1, 1);
        MatrixStack matrices = new MatrixStack();
        // This also computes the normal matrix, including animated nonuniform bone scales.
        matrices.multiplyPositionMatrix(transform);
        if (authoredRight != mirrored) {
            renderer.renderRightArm(matrices, queue, light, skin,
                    player.isModelPartVisible(PlayerModelPart.RIGHT_SLEEVE));
        } else {
            renderer.renderLeftArm(matrices, queue, light, skin,
                    player.isModelPartVisible(PlayerModelPart.LEFT_SLEEVE));
        }
    }

    /**
     * Transform for a deferred vanilla offhand item held by the authored supporting hand.
     * Use FIRST_PERSON_LEFT_HAND when unmirrored and FIRST_PERSON_RIGHT_HAND when mirrored.
     * Returns null when this model has no supporting attachment or it is scaled away.
     */
    public static Matrix4f supportingItemTransform(BedrockModel model, Matrix4f[] bonePose,
            Matrix4f sharedTransform, boolean mirrored) {
        Matrix4f transform = attachmentTransform(model, bonePose, sharedTransform, false);
        if (transform == null) return null;
        transform.mul(SUPPORTING_ITEM_ALIGN);
        if (mirrored) transform.scale(-1, 1, 1);
        return transform;
    }

    private static Matrix4f attachmentTransform(BedrockModel model, Matrix4f[] bonePose,
            Matrix4f sharedTransform, boolean authoredRight) {
        String name = authoredRight ? RIGHT_HAND : LEFT_HAND;
        for (int index = 0; index < model.bones.size(); index++) {
            if (!model.bones.get(index).name().equals(name)) continue;
            if (index >= bonePose.length || bonePose[index] == null) return null;
            // A pose already rotates around the authored pivot; translating to the pivot
            // again would displace the hand and break its alignment with the weapon.
            Matrix4f transform = new Matrix4f(sharedTransform).mul(bonePose[index])
                    .translate(authoredRight ? -1f / 16 : 1f / 16, .5f, 0);
            float determinant = transform.determinant();
            return Float.isFinite(determinant) && Math.abs(determinant) >= 1e-12f ? transform : null;
        }
        return null;
    }

    private static Matrix4f supportingItemAlignment() {
        // Undo vanilla's left-arm presentation, then apply its left-held-item origin.
        Matrix4f vanillaArm = new Matrix4f().translate(-.64f, -.6f, -.72f)
                .rotateY(-45 * DEG).translate(1, 3.6f, 3.5f)
                .rotateZ(-120 * DEG).rotateX(200 * DEG).rotateY(135 * DEG)
                .translate(-5.6f, 0, 0);
        return vanillaArm.invert().mul(new Matrix4f().translate(-.56f, -.52f, -.72f));
    }
}
