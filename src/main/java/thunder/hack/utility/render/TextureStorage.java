package thunder.hack.utility.render;

import net.minecraft.util.Identifier;

public final class TextureStorage {
    public static final Identifier crystalTexture2 = Identifier.of("gombal", "textures/misc/end_crystal2.png");
    public static final Identifier skull = Identifier.of("gombal", "textures/hud/elements/skull.png");
    public static final Identifier star = Identifier.of("gombal", "textures/particles/star.png");
    public static final Identifier heart = Identifier.of("gombal", "textures/particles/heart.png");
    public static final Identifier dollar = Identifier.of("gombal", "textures/particles/dollar.png");
    public static final Identifier snowflake = Identifier.of("gombal", "textures/particles/snowflake.png");
    /**
     * Outlined square for the 2D particle path - white on OPAQUE black, like the rest of that set.
     *
     * <p>Deliberately NOT an alpha sprite: Particles draws additively, where the black backdrop
     * contributes nothing and vanishes on its own. See [[sprite-alpha-rule]] for the world-sprite case,
     * which is the exact opposite.
     */
    public static final Identifier squareOutline = Identifier.of("gombal", "textures/particles/square_outline.png");
    public static final Identifier firefly = Identifier.of("gombal", "textures/particles/firefly.png");

    /**
     * The firefly with a real alpha channel, for the world sprite path.
     *
     * <p>{@link #firefly} itself is white on an OPAQUE black square - fine for the additive 2D particle
     * path that still uses it, a black square anywhere that blends with alpha.
     */
    public static final Identifier fireflyAlpha = Identifier.of("gombal", "textures/particles/firefly_a.png");
    public static final Identifier arrow = Identifier.of("gombal", "textures/hud/elements/triangle.png");
    public static final Identifier capture = Identifier.of("gombal", "textures/hud/elements/capture.png");
    /** capture.png with its black background turned into alpha (white + alpha, see the sprite alpha rule). */
    public static final Identifier captureAlpha = Identifier.of("gombal", "textures/hud/elements/capture_a.png");
    /** Soft glow: small hot core, long fading halo - the ThunderHack V2 ghosts (firefly_a is a hard-edged disc). */
    public static final Identifier ghostGlow = Identifier.of("gombal", "textures/particles/ghost_glow.png");
    public static final Identifier bubble = Identifier.of("gombal", "textures/particles/hitbubble.png");
    public static final Identifier default_circle = Identifier.of("gombal", "textures/particles/circle.png");
    /** A crisp line: solid core, one soft texel each side - wireframe edges. */
    public static final Identifier fxLine = Identifier.of("gombal", "textures/particles/fx_line.png");
    /** Floating astronaut, a Cosmos-sky touch. */
    public static final Identifier fxAstronaut = Identifier.of("gombal", "textures/particles/fx_astronaut.png");
    /**
     * The body of the FloorWeather water: a slow swell plus a fine ripple in the alpha, so the ground shows
     * through unevenly the way it does under shallow water. Seamless, because the sheet is tiled in blocks
     * and every quad asks for the window its position falls into.
     */
    public static final Identifier fxWater = Identifier.of("gombal", "textures/particles/fx_water.png");
    /**
     * The reflective half of the FloorWeather water: dense tiny sparks over a faint sheen, drawn as the top
     * sheet and drifting at a different rate, so the light travels across the body instead of moving with it.
     */
    public static final Identifier fxWaterGlint = Identifier.of("gombal", "textures/particles/fx_water_glint.png");
    /**
     * The sky lying on the water: wide, soft patches of pale blue, drawn from the mid-distance outward
     * where the surface is seen at a grazing angle. This is what makes far water bright instead of a flat
     * coat of colour.
     */
    public static final Identifier fxWaterSheen = Identifier.of("gombal", "textures/particles/fx_water_sheen.png");
    /**
     * Sunlight on the bottom, drawn UNDER the body: thin bright cells of the web a moving surface focuses
     * onto the floor. It belongs to the ground rather than to the water, which is why it sits below it.
     */
    public static final Identifier fxWaterCaustic = Identifier.of("gombal", "textures/particles/fx_water_caustic.png");

    /* ---- Cloudan FX sprites: the visuals are painted, not computed ---- */
    /** Thin bright ring with a soft falloff on both edges - target and anchor rings. */
    public static final Identifier fxRing = Identifier.of("gombal", "textures/particles/fx_ring.png");
    /** Wide soft halo - the glow that sits under everything else. */
    public static final Identifier fxHalo = Identifier.of("gombal", "textures/particles/fx_halo.png");
    /** Hot core with four soft spikes - the first frames of an impact. */
    public static final Identifier fxFlash = Identifier.of("gombal", "textures/particles/fx_flash.png");
    /** Small round spark for pooled particles. */
    public static final Identifier fxSpark = Identifier.of("gombal", "textures/particles/fx_spark.png");
    /** Ring that is sharp on the outside and trails inwards - an expanding shockwave. */
    public static final Identifier fxShockwave = Identifier.of("gombal", "textures/particles/fx_shockwave.png");

    /**
     * The one wing: translucent crystal shards fanning from the back with bright glowing edges. White plus
     * alpha, so the module colour tints it; drawn as the right wing and mirrored for the left.
     */
    public static final Identifier wingShard = Identifier.of("gombal", "textures/particles/wing_shard.png");

    /* ---- Lanterns: painted paper shells, not Minecraft's iron block ---- */
    public static final Identifier lanternPaper = Identifier.of("gombal", "textures/particles/lantern_paper.png");
    public static final Identifier lanternSoul = Identifier.of("gombal", "textures/particles/lantern_soul.png");
    public static final Identifier lanternOrb = Identifier.of("gombal", "textures/particles/lantern_orb.png");

    /* ---- shapes for the visual modules ---- */
    /** Four glyphs. Not a real alphabet - strokes that read as writing at a glance. */
    public static final Identifier[] fxRunes = {
            Identifier.of("gombal", "textures/particles/fx_rune1.png"),
            Identifier.of("gombal", "textures/particles/fx_rune2.png"),
            Identifier.of("gombal", "textures/particles/fx_rune3.png"),
            Identifier.of("gombal", "textures/particles/fx_rune4.png")
    };
    /** Two rings, twelve segments, four spokes - the ground sigil. */
    public static final Identifier fxSigil = Identifier.of("gombal", "textures/particles/fx_sigil.png");
    /** Tapered motion streak, solid at the head and gone at the tail. */
    public static final Identifier fxStreak = Identifier.of("gombal", "textures/particles/fx_streak.png");

    /** Five-pointed star with a soft centre. */
    public static final Identifier fxStar = Identifier.of("gombal", "textures/particles/fx_star.png");
    public static final Identifier fxCube = Identifier.of("gombal", "textures/particles/fx_cube.png");
    public static final Identifier fxPlasma = Identifier.of("gombal", "textures/particles/fx_plasma.png");
    /** Seamless white value-noise fog - the BlockOverlay fill. Generated by tools/GenSmokeTexture.java. */
    public static final Identifier fxSmoke = Identifier.of("gombal", "textures/particles/fx_smoke.png");
    public static final Identifier fxMarker = Identifier.of("gombal", "textures/particles/fx_marker.png");
    public static final Identifier fxGombalV3 = Identifier.of("gombal", "textures/particles/fx_gombalv3.png");
    public static final Identifier fxBlackHole = Identifier.of("gombal", "textures/particles/fx_blackhole.png");
    /** Flat crystal shard - the diamonds that hang around a marked target. */
    public static final Identifier fxShard = Identifier.of("gombal", "textures/particles/fx_shard.png");
    /** Four-armed sparkle - reads better than a star at small sizes. */
    public static final Identifier fxSparkle = Identifier.of("gombal", "textures/particles/fx_sparkle.png");

    /**
     * The aurora, as one wide panorama that wraps the horizon exactly once.
     *
     * <p>Seamless by construction: every wave in it completes a whole number of cycles across the width,
     * so where the band meets itself there is nothing to see. It replaced three separate curtain sprites
     * that were stood in a ring - which looked like three sprites stood in a ring.
     */
    public static final Identifier skyAurora = Identifier.of("gombal", "textures/particles/sky_aurora.png");
    /** The second, higher, fainter curtain, on unrelated frequencies - this is what gives the sky depth. */
    public static final Identifier skyAuroraBack = Identifier.of("gombal", "textures/particles/sky_aurora_b.png");
    /**
     * JumpCircle glow rings, thin (index 0) -> thick (index 5).
     *
     * <p>These are the {@code _a} copies: white with a real alpha channel. The originals are white on an
     * OPAQUE black square, drawn for additive blending - through the alpha-blended world sprite path they
     * came out as black squares that ignored the colour setting entirely, because anything multiplied by
     * black is black. The originals stay where they are, because the 2D particle path still adds them.
     */
    public static final Identifier[] jumpCircles = {
            Identifier.of("gombal", "textures/particles/circle_a.png"),
            Identifier.of("gombal", "textures/particles/circle2_a.png"),
            Identifier.of("gombal", "textures/particles/circle3_a.png"),
            Identifier.of("gombal", "textures/particles/circle4_a.png"),
            Identifier.of("gombal", "textures/particles/circle5_a.png"),
            Identifier.of("gombal", "textures/particles/circle6_a.png")
    };
    public static final Identifier container = Identifier.of("gombal", "textures/hud/elements/container.png");
    public static final Identifier guiArrow = Identifier.of("gombal", "textures/gui/elements/arrow.png");
    public static final Identifier Gear = Identifier.of("gombal", "textures/gui/headers/client.png");
    public static final Identifier candle = Identifier.of("gombal", "textures/hud/elements/candle.png");
    public static final Identifier thTeam = Identifier.of("gombal", "textures/gui/elements/thteam.png");
    public static final Identifier donation = Identifier.of("gombal", "textures/gui/elements/donationalerts.png");
    public static final Identifier chestIcon = Identifier.of("gombal", "textures/hud/icons/chest.png");
    /** Outline backpack, 64x64 - the InventoryHud header. */
    public static final Identifier backpackIcon = Identifier.of("gombal", "textures/hud/icons/backpack.png");
    public static final Identifier brokenShield = Identifier.of("gombal", "textures/hud/elements/broken_shield.png");
    public static final Identifier miniLogo = Identifier.of("gombal", "textures/hud/icons/mini_logo.png");
    public static final Identifier playerIcon = Identifier.of("gombal", "textures/gui/headers/player.png");
    public static final Identifier serverIcon = Identifier.of("gombal", "textures/hud/icons/server.png");
    public static final Identifier proxyIcon = Identifier.of("gombal", "textures/hud/icons/proxy.png");
    public static final Identifier waypoint = Identifier.of("gombal", "textures/hud/elements/waypoint.png");
    public static final Identifier configIcon = Identifier.of("gombal", "textures/gui/elements/configpng.png");
    public static final Identifier waypointIcon = Identifier.of("gombal", "textures/gui/elements/waypointicon.png");
    public static final Identifier macrosIcon = Identifier.of("gombal", "textures/gui/elements/macrosicon.png");
    public static final Identifier speedometerIcon = Identifier.of("gombal", "textures/hud/icons/speedometer.png");
    public static final Identifier lagIcon = Identifier.of("gombal", "textures/hud/elements/lag.png");
    public static final Identifier fpsIcon = Identifier.of("gombal", "textures/hud/icons/fps.png");
    public static final Identifier pingIcon = Identifier.of("gombal", "textures/hud/icons/ping.png");
    public static final Identifier swordIcon = Identifier.of("gombal", "textures/hud/icons/sword.png");
    public static final Identifier memoryIcon = Identifier.of("gombal", "textures/hud/icons/memory.png");
    public static final Identifier tpsIcon = Identifier.of("gombal", "textures/hud/icons/tps.png");
    public static final Identifier boykisser = Identifier.of("gombal", "textures/hud/elements/boykisser.png");
    public static final Identifier paimon = Identifier.of("gombal", "textures/hud/elements/paimon.png");
    public static final Identifier baltika = Identifier.of("gombal", "textures/hud/elements/baltika.png");
    public static final Identifier kowk = Identifier.of("gombal", "textures/hud/elements/kowk.png");
    public static final Identifier thLogo = Identifier.of("gombal", "textures/gui/elements/th.png");
    public static final Identifier thudPic = Identifier.of("gombal", "textures/hud/elements/thud.png");
    public static final Identifier coordsIcon = Identifier.of("gombal", "textures/hud/icons/coords.png");

    /* ---- Card icons: white with alpha, so the HUD can tint them with the accent ---- */
    public static final Identifier keyboardIcon = Identifier.of("gombal", "textures/hud/icons/keyboard.png");
    public static final Identifier potionIcon = Identifier.of("gombal", "textures/hud/icons/potion.png");
    public static final Identifier clockIcon = Identifier.of("gombal", "textures/hud/icons/clock.png");
    public static final Identifier noteIcon = Identifier.of("gombal", "textures/hud/icons/note.png");
    public static final Identifier sunRiseSkin = Identifier.of("gombal", "textures/misc/sunskin.png");
    public static final Identifier crackedSkin = Identifier.of("gombal", "textures/gui/elements/cracked.png");
    /** Dialogs have no picture any more (the anime art was removed); DialogScreen skips a null one. */
    public static final Identifier questionPic = null;
}
