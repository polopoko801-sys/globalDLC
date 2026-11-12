package thunder.hack.gui.font;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * BKClient's typeface (SF Pro Display: Regular, Medium, Semibold, Bold) at BK's exact sizes.
 *
 * <p>BK draws text with MSDF atlases whose size is the em height in GUI pixels; the same size handed to our
 * glyph-page {@link FontRenderer} gives the same metrics, so {@code BkFonts.MEDIUM.get(7)} is BK's
 * {@code Fonts.MEDIUM.getFont(7.0F)}. Renderers are built lazily (they allocate textures) and cached per size.
 */
public enum BkFonts {
    REGULAR("regular"), MEDIUM("medium"), SEMIBOLD("semi_bold"), BOLD("bold");

    private final String file;
    private Font base;
    private boolean failed;
    private final Map<Integer, FontRenderer> sizes = new HashMap<>();

    BkFonts(String file) {
        this.file = file;
    }

    /** The renderer for {@code size} GUI pixels; falls back to sf_medium if the font file cannot be read. */
    public FontRenderer get(float size) {
        int key = Math.round(size * 10f);
        FontRenderer known = sizes.get(key);
        if (known != null) return known;
        try {
            if (base == null && !failed) {
                try (InputStream in = BkFonts.class.getClassLoader().getResourceAsStream("assets/bkclient/fonts/" + file + ".otf")) {
                    if (in == null) throw new IllegalStateException("missing " + file + ".otf");
                    base = Font.createFont(Font.TRUETYPE_FONT, in);
                }
            }
            if (base == null) return FontRenderers.sf_medium;
            FontRenderer renderer = new FontRenderer(base.deriveFont(Font.PLAIN, size), size);
            sizes.put(key, renderer);
            return renderer;
        } catch (Exception e) {
            failed = true;
            thunder.hack.ThunderHack.LOGGER.warn("[BkFonts] cannot load {}: {}", file, e.toString());
            return FontRenderers.sf_medium;
        }
    }
}
