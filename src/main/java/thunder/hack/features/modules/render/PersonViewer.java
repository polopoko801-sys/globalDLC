package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Port of Celestial's {@code PersonViewer}: in second / third person the camera sits "FOV Modifier" blocks
 * away (vanilla 4) and is turned by "Viewer Yaw" / "Viewer Pitch". Applied in {@code MixinCamera}.
 */
public class PersonViewer extends Module {
    public PersonViewer() {
        super("PersonViewer", Category.RENDER);
    }

    public final Setting<Float> fovModifier = new Setting<>("FOV Modifier", 4.0f, 1.0f, 50.0f);
    public final Setting<Float> viewerYaw = new Setting<>("Viewer Yaw", 10.0f, -50.0f, 50.0f);
    public final Setting<Float> viewerPitch = new Setting<>("Viewer Pitch", 10.0f, -50.0f, 50.0f);
}
