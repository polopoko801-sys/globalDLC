package thunder.hack.injection.accesors;

import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.21.11: velocityX/Y/Z (short-encoded ints) collapsed into a single Vec3d 'velocity' field.
@Mixin(EntityVelocityUpdateS2CPacket.class)
public interface ISPacketEntityVelocity {
    @Mutable
    @Accessor("velocity")
    void setVelocity(Vec3d velocity);
}