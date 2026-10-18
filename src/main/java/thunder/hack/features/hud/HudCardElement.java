package thunder.hack.features.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;

import java.awt.Color;
import java.util.List;

/**
 * A HUD element that is a {@link HudCard}: a title bar with an icon and a column of rows.
 *
 * <p>Subclasses say what goes IN the card - a title, an icon, and the rows - and nothing about how it
 * is drawn. All the shared settings live here, so every card in the HUD has the same plate colour,
 * padding and font switch, and changing any of them is one edit rather than thirty.
 *
 * <p>An element with nothing to show returns an empty list and takes up no space at all, rather than
 * leaving an empty plate behind.
 */
public abstract class HudCardElement extends HudElement {
    /** How big the whole card is drawn. The layout is scaled as one piece, so proportions hold. */
    protected final Setting<Float> scale = new Setting<>("Scale", 1.3f, 0.6f, 3f);
    protected final Setting<Boolean> small = new Setting<>("SmallFont", true);
    /** Frosted backdrop behind the card. Independent of the global HudStyle. */
    protected final Setting<Boolean> blur = new Setting<>("Glass", false);
    protected final Setting<ColorSetting> plateColor = new Setting<>("PlateColor", new ColorSetting(new Color(12, 12, 15, 208)));

    public HudCardElement(String name, int width, int height) {
        super(name, width, height);
    }

    /** The heading. Defaults to the element's own name, which is almost always what it should say. */
    protected String cardTitle() {
        return getName();
    }

    /** The little picture in the title bar. Null draws none. */
    protected abstract @Nullable Identifier cardIcon();

    /** The card's contents, rebuilt every frame. */
    protected abstract List<HudCard.Row> cardRows();

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        List<HudCard.Row> rows = cardRows();
        if (rows.isEmpty()) {
            setBounds(getPosX(), getPosY(), 0, 0);
            return;
        }

        // Cards on the right half of the screen grow leftwards, so they stay on screen and their right
        // edges line up with each other instead of with nothing.
        boolean reverse = getPosX() > (mc.getWindow().getScaledWidth() / 2f);

        float[] size = HudCard.draw(context, getPosX(), getPosY(), reverse,
                cardTitle(), cardIcon(), rows, small.getValue(),
                plateColor.getValue().getColorObject(), blur.getValue(), null, scale.getValue());

        setBounds(getPosX(), getPosY(), (int) size[0] * (reverse ? -1 : 1), (int) size[1]);
    }
}
