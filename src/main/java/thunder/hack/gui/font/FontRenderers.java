package thunder.hack.gui.font;

import org.jetbrains.annotations.NotNull;
import thunder.hack.ThunderHack;

import java.awt.*;
import java.io.IOException;
import java.util.Objects;

public class FontRenderers {
    public static FontRenderer settings;
    public static FontRenderer modules;
    public static FontRenderer categories;
    public static FontRenderer icons;
    public static FontRenderer small_icons;
    public static FontRenderer mid_icons;
    public static FontRenderer big_icons;
    public static FontRenderer thglitch;
    public static FontRenderer thglitchBig;
    public static FontRenderer monsterrat;
    public static FontRenderer sf_bold;
    public static FontRenderer sf_bold_big;
    public static FontRenderer sf_bold_mini;
    public static FontRenderer sf_bold_micro;
    public static FontRenderer sf_medium;
    public static FontRenderer sf_medium_mini;
    public static FontRenderer sf_medium_modules;
    public static FontRenderer minecraft;
    public static FontRenderer profont;
    /** Big, natively-sized face for the main menu wordmark and the intro (no ugly upscaling). */
    public static FontRenderer brand;
    public static FontRenderer brandSmall;
    /** Rockstar Free 2.0: mntsb (= mntsb_15), mntsb_16, mntsb_20 (Montserrat), i30 (icons2), neverlose500_14. */
    public static FontRenderer mntsb;
    public static FontRenderer mntsb_16;
    public static FontRenderer mntsb_20;
    public static FontRenderer i30;
    public static FontRenderer neverlose500_14;

    public static FontRenderer getModulesRenderer() {
        return modules;
    }

    /**
     * The namespaces the fonts may live under, newest first.
     *
     * <p>The rebrand moved {@code assets/thunderhack} to {@code assets/gombal} and this lookup was left
     * pointing at the old one, so every face came back null and the loading screen died on the first
     * string it tried to draw. Both are tried now: the rename is done, but a jar built from an older
     * resource tree still works rather than failing in a way that looks like the whole client is broken.
     */
    private static final String[] NAMESPACES = {"gombal", "thunderhack"};

    public static @NotNull FontRenderer create(float size, String name) throws IOException, FontFormatException {
        try (java.io.InputStream in = open(name)) {
            return new FontRenderer(Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.PLAIN, size / 2f), size / 2f);
        }
    }

    /** The font file, or an exception that says which one is missing rather than a bare NPE. */
    private static java.io.@NotNull InputStream open(String name) throws IOException {
        for (String namespace : NAMESPACES) {
            java.io.InputStream in = ThunderHack.class.getClassLoader()
                    .getResourceAsStream("assets/" + namespace + "/fonts/" + name + ".ttf");
            if (in != null) return in;
        }

        throw new IOException("font " + name + ".ttf is not in the jar (looked under "
                + String.join(", ", NAMESPACES) + ")");
    }
}
