package thunder.hack.features.modules.misc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import thunder.hack.features.modules.Module;

import java.util.ArrayList;
import java.util.List;

/** 1:1 port of Celestial's {@code DeleteArmorStands}: removes every armour stand from your world (client side). */
public class DeleteArmorStands extends Module {
    public DeleteArmorStands() {
        super("DeleteArmorStands", Category.MISC);
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        List<Entity> stands = new ArrayList<>();
        for (Entity entity : mc.world.getEntities())
            if (entity instanceof ArmorStandEntity) stands.add(entity);
        for (Entity entity : stands)
            mc.world.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
    }
}
