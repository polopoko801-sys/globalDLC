package thunder.hack.utility.interfaces;

import thunder.hack.features.modules.combat.Aura;

import java.util.List;

public interface IEntityLiving {
    double getPrevServerX();

    double getPrevServerY();

    double getPrevServerZ();

    double getServerX();

    double getServerY();

    double getServerZ();

    List<Aura.Position> getPositionHistory();
}
