package thunder.hack.features.cosmetics.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import org.joml.Matrix4f;
import thunder.hack.features.cosmetics.*;

/** Backpacks and wings on the local player's torso. Ported from BKClient (MIT, bkubiakdev) to the 1.21.11 command queue. */
public final class CosmeticFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState,PlayerEntityModel> {
    private float flightWeight,elytraWeight;
    public CosmeticFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState,PlayerEntityModel> context) { super(context); }
    @Override public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                 PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        var client=MinecraftClient.getInstance();
        if (client.player==null || state.id!=client.player.getId() || state.invisible) return;
        float seconds=state.age/20f;
        boolean gliding=client.player.isGliding();
        flightWeight+=((!gliding&&client.player.getAbilities().flying?1:0)-flightWeight)*.1f;
        elytraWeight+=((gliding?1:0)-elytraWeight)*.1f;
        CosmeticSink.Recorder sink=CosmeticSink.queued();
        for (CosmeticSlot slot:new CosmeticSlot[]{CosmeticSlot.BACKPACK,CosmeticSlot.WINGS}) {
            CosmeticEntry entry=CosmeticManager.getInstance().equipped(slot);
            if (entry==null) continue;
            if(slot==CosmeticSlot.BACKPACK&&client.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA))continue;
            matrices.push();
            try {
                getContextModel().body.applyTransform(matrices);
                // The authored assets attach to the torso with the Bedrock X/Y axes inverted.
                float backOffset=slot==CosmeticSlot.BACKPACK && CosmeticManager.getInstance().equipped(CosmeticSlot.WINGS)!=null?.1375f:0;
                if (slot==CosmeticSlot.BACKPACK && !client.player.getEquippedStack(EquipmentSlot.CHEST).isEmpty())
                    backOffset=Math.max(backOffset,.078125f);
                Matrix4f matrix=new Matrix4f(matrices.peek().getPositionMatrix()).rotateZ((float)Math.PI)
                    .translate(0,entry.offsetY()-.3f,entry.offsetZ()+backOffset).scale(entry.scale());
                if (slot==CosmeticSlot.BACKPACK && entry.id().equals("mage_staff")) matrix.rotateY((float)Math.PI);
                var model=CosmeticRenderer.model(entry);if(model==null)continue;
                var idle=CosmeticRenderer.idle(entry);
                var pose=model.pose(name->idle==null?BedrockAnimation.Pose.IDENTITY:idle.sample(name,seconds),seconds,
                    slot==CosmeticSlot.WINGS&&entry.animation()==null?entry.model():null,flightWeight,elytraWeight);
                CosmeticRenderer.renderPose(entry,matrix,sink,light,pose,seconds,null);
            } finally { matrices.pop(); }
        }
        sink.flush(queue);
    }
}
