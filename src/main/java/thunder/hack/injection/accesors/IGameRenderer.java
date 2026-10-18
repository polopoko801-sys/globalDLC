package thunder.hack.injection.accesors;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface IGameRenderer {
    // 1.21.11: renderHand(Camera, float, Matrix4f) -> renderHand(float tickProgress, boolean sleeping, Matrix4f)
    @Invoker("renderHand")
    void irenderHand(float tickProgress, boolean sleeping, Matrix4f matrix4f);

    // private float getFov(Camera, float tickDelta, boolean changingFov) — needed to rebuild the world
    // projection matrix for world->screen projection (RenderSystem no longer exposes it as a Matrix4f).
    @Invoker("getFov")
    float thGetFov(Camera camera, float tickDelta, boolean changingFov);
}
