package thunder.hack.features.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.List;

/**
 * The shared HUD card: a title bar with an icon, then a column of rows.
 *
 * <p>Every informational element in the HUD is the same thing - a heading and some labelled values -
 * and drawing each of them its own way is what made the HUD look assembled from spare parts. One
 * renderer means they share an alignment, a corner radius, a padding and a plate colour, so a stack of
 * them reads as one interface instead of as a pile of widgets.
 *
 * <p>Every card is laid out against ONE width, measured across all its rows first. That is what puts
 * the values in a straight column; sizing each row to its own text is what makes a list look ragged,
 * because labels differ in length far more than values do.
 *
 * <p>The icon is the only thing a card does not share - that is the point of it. It sits at the right
 * of the title bar and is how you pick one card out of a column of them at a glance.
 */
public final class HudCard {
    private HudCard() {
    }

    /* Proportions, in scaled pixels. */
    public static final float PAD = 6f;
    public static final float ROW = 12f;
    public static final float HEADER = 15f;
    /** Row icons, in pixels. Small enough to sit inside a row, big enough to still be an image. */
    public static final float ROW_ICON = 9f;

    /**
     * One line of the card. {@code value} may be empty for a plain line of text.
     *
     * <p>{@code icon} is the row's own little picture, drawn at the left with the label pushed across
     * to make room. It is drawn in its NATURAL colours, unlike the card's title icon: a row icon is
     * usually a real game sprite - a potion effect, an item - and tinting those destroys the only thing
     * that makes them recognisable.
     */
    public record Row(String label, String value, Color labelColor, Color valueColor, Identifier icon) {
        public Row(String label, String value, Color labelColor, Color valueColor) {
            this(label, value, labelColor, valueColor, null);
        }

        public Row(String label, String value) {
            this(label, value, null, null, null);
        }

        public Row(String label) {
            this(label, "", null, null, null);
        }
    }

    /**
     * Draws a card and returns the space it took, as {@code {width, height}}.
     *
     * <p>The size is returned rather than taken as an argument because a card is sized by its contents:
     * the caller cannot know how wide it needs to be until the text has been measured, and the element
     * needs the answer to report its bounds to the HUD editor.
     */
    /**
     * A square picture at the left of the card, spanning all its rows.
     *
     * <p>{@code fill} is the square's own colour and {@code overlay} an optional glyph drawn on top of
     * it, which between them cover both cases: a real picture, and a stand-in for one that does not
     * exist. A stand-in whose colour is derived from what it represents still changes when the subject
     * does, which is the part that makes it read as artwork rather than as decoration.
     */
    public record Cover(Color fill, @Nullable Identifier overlay) {
    }

    public static float[] draw(@NotNull DrawContext context, float x, float y, boolean reverse,
                               @NotNull String title, @Nullable Identifier icon,
                               @NotNull List<Row> rows, boolean small,
                               @NotNull Color plate, boolean blur) {
        return draw(context, x, y, reverse, title, icon, rows, small, plate, blur, null, 1f);
    }

    /**
     * As above, at a size.
     *
     * <p>The card is laid out once at its natural size and the whole thing is then scaled on the matrix,
     * rather than every measurement being multiplied. Scaling the finished layout keeps the proportions
     * exact at any size - padding, row height, icon and text all grow together - where multiplying each
     * number separately accumulates rounding and the alignment drifts apart as it gets bigger.
     */
    public static float[] draw(@NotNull DrawContext context, float x, float y, boolean reverse,
                               @NotNull String title, @Nullable Identifier icon,
                               @NotNull List<Row> rows, boolean small,
                               @NotNull Color plate, boolean blur, @Nullable Cover cover, float scale) {
        // Past a point, growing the card by stretching the matrix is just magnifying pixels: the small
        // font is a bitmap and scaling it 2x makes a 2x-chunkier bitmap. So a large card is drawn with
        // the BIG font instead, laid out on constants grown by the same ratio, and the matrix then only
        // has to make up whatever is left. The result is the same size on screen and actually sharp.
        // Any upscale at all counts: the default card scale is 1.3, and a 1.3x-magnified bitmap is exactly
        // the pixelated look this is here to avoid. Shrinking the big font down is what stays sharp.
        boolean useLarge = !small || scale > 1.001f;

        float unit = 1f;
        if (useLarge && small) {
            // Measured rather than hardcoded - the two fonts' relative size is a property of the fonts.
            float mini = FontRenderers.sf_bold_mini.getStringWidth("MMMM");
            float large = FontRenderers.sf_bold.getStringWidth("MMMM");
            if (mini > 0.01f && large > 0.01f) unit = large / mini;
        }

        float matrix = scale / unit;
        if (matrix == 1f) return drawAt(context, x, y, reverse, title, icon, rows, !useLarge, plate, blur, cover, unit);

        // Scaled about the card's own anchor, so growing it does not walk it across the screen.
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(matrix, matrix);
        context.getMatrices().translate(-x, -y);

        float[] size = drawAt(context, x, y, reverse, title, icon, rows, !useLarge, plate, blur, cover, unit);

        context.getMatrices().popMatrix();
        return new float[]{size[0] * matrix, size[1] * matrix};
    }

    private static float[] drawAt(@NotNull DrawContext context, float x, float y, boolean reverse,
                                  @NotNull String title, @Nullable Identifier icon,
                                  @NotNull List<Row> rows, boolean small,
                                  @NotNull Color plate, boolean blur, @Nullable Cover cover, float unit) {
        var labelFont = small ? FontRenderers.sf_bold_mini : FontRenderers.sf_bold;
        var valueFont = small ? FontRenderers.sf_medium_mini : FontRenderers.sf_medium;

        // Every measurement below is in "units" so the whole layout grows with the font it is drawn in.
        float pad = PAD * unit, rowH = ROW * unit, header = HEADER * unit, rowIcon = ROW_ICON * unit;

        float iconRoom = icon != null ? 14f : 4f;
        float widest = labelFont.getStringWidth(title) + iconRoom + 8f;

        // One indent for the whole card, not one per row: if only some rows have an icon, indenting
        // only those leaves the labels in two columns, which reads as a mistake rather than as a list.
        boolean anyRowIcon = rows.stream().anyMatch(r -> r.icon() != null);
        float labelIndent = anyRowIcon ? rowIcon + 3f : 0f;

        for (Row row : rows)
            widest = Math.max(widest, labelIndent + labelFont.getStringWidth(row.label())
                    + (row.value().isEmpty() ? 0f : valueFont.getStringWidth(row.value()) + 10f));

        float h = header + Math.max(1, rows.size()) * rowH + pad;

        // The cover is square and as tall as the rows it sits beside, so the card's own height decides
        // its size - a fixed size would either float in a tall card or overflow a short one.
        float coverSize = cover == null ? 0f : Math.max(10f, h - header - pad);
        float coverRoom = cover == null ? 0f : coverSize + 5f;

        float w = widest + coverRoom + pad * 2f;

        float left = reverse ? x - w : x;

        if (blur) {
            Render2DEngine.drawRoundedBlur(context, left, y, w, h, 4f, HudEditor.blurColor.getValue().getColorObject());
            // The plate went on top of the glass at its own alpha - ~208 by default - and buried it, so
            // "Blur" looked like it did nothing. Over glass the plate is only a veil.
            plate = Render2DEngine.injectAlpha(plate, Math.min(plate.getAlpha(), 90));
        }
        Render2DEngine.drawRound(context, left, y, w, h, 4f, plate);

        // The title bar is the plate lifted by a touch of white rather than a second colour of its own,
        // so it keeps working whatever the plate is set to.
        Render2DEngine.drawRound(context, left, y, w, header, 4f, new Color(255, 255, 255, 18));

        labelFont.drawString(context, title, left + pad, y + header / 2f - 3f, new Color(0xE8E8EE).getRGB());

        if (icon != null) {
            // Tinted with the accent: the icon is the one part of the card that is allowed to carry the
            // client colour, so a column of cards is readable in greyscale and still feels like yours.
            float size = 8f;
            Render2DEngine.renderTexture(context, icon, left + w - pad - size, y + header / 2f - size / 2f,
                    size, size, 0, 0, size, size, size, size, HudEditor.getColor(0));
        }

        if (cover != null) {
            float cx = left + pad, cy = y + header + 1f;
            Render2DEngine.drawRound(context, cx, cy, coverSize, coverSize, 3f, cover.fill());

            if (cover.overlay() != null) {
                float glyph = coverSize * 0.55f;
                Render2DEngine.renderTexture(context, cover.overlay(),
                        cx + (coverSize - glyph) / 2f, cy + (coverSize - glyph) / 2f,
                        glyph, glyph, 0, 0, glyph, glyph, glyph, glyph, new Color(0xFFFFFFFF, true));
            }
        }

        float rowY = y + header + 1f;
        float textLeft = left + pad + coverRoom;

        for (Row row : rows) {
            if (row.icon() != null)
                Render2DEngine.renderTexture(context, row.icon(), textLeft, rowY + (rowH - rowIcon) / 2f,
                        rowIcon, rowIcon, 0, 0, rowIcon, rowIcon, rowIcon, rowIcon);

            Color label = row.labelColor() != null ? row.labelColor() : new Color(0xE8E8EE);
            labelFont.drawString(context, row.label(), textLeft + labelIndent, rowY + 3f, label.getRGB());

            if (!row.value().isEmpty()) {
                Color value = row.valueColor() != null ? row.valueColor() : new Color(0x8E8E96);
                valueFont.drawString(context, row.value(),
                        left + w - pad - valueFont.getStringWidth(row.value()), rowY + 3f, value.getRGB());
            }

            rowY += rowH;
        }

        return new float[]{w, h};
    }

    /** The bracketed form used for keys and short values: {@code [F]}. */
    public static String bracket(String text) {
        return "[" + text + "]";
    }
}
