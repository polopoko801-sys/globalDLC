package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.NotNull;
import thunder.hack.utility.render.animation.Animation;
import thunder.hack.utility.render.animation.Easing;

/**
 * How one piece of the menu arrives on screen, and how it leaves.
 *
 * <p>One driver for every motion in the menu, so nothing animates on its own schedule. It wraps the client's
 * existing {@link Animation} - which is already frame-rate independent and already clamps a hitch so a world
 * load cannot teleport an animation to its end - and turns its 0..1 value into the two things a caller
 * actually needs: an opacity, and a translation to push onto the matrix.
 *
 * <p><b>Why a translation and not a layout offset.</b> Sliding by moving every element's coordinates means
 * every element has to know about the slide. Pushing one matrix means none of them do: the panel is laid out
 * once, at its final position, and the transition moves the whole thing. That is also what keeps a slide from
 * drifting out of alignment at a different GUI scale.
 */
public final class MenuTransition {

    /** The motions the menu can use. */
    public enum Kind {
        /** Opacity only. */
        FADE,
        /** Rises from below its resting place. The menu's own opening motion. */
        SLIDE_UP,
        SLIDE_DOWN,
        SLIDE_LEFT,
        SLIDE_RIGHT,
        /** Grows into place from slightly smaller. */
        SCALE,
        /** A slide with the opacity ramp on top - the default for the main menu. */
        FADE_AND_SLIDE,
        /** Two images, one dissolving into the other. Used by the wallpaper, never by a panel. */
        CROSSFADE
    }

    private final Kind kind;
    private final Animation animation;
    /** How far the slide travels, in scaled pixels at its start. */
    private final float distance;

    public MenuTransition(Kind kind, long durationMs, float distance) {
        this(kind, durationMs, distance, Easing.CubicOut);
    }

    /**
     * @param easing CubicOut by default: it covers most of the distance early and settles without overshoot,
     *               which is the "polished, no bounce" feel asked for. Spring is available but deliberately
     *               not the default - a bounce on every screen open gets tiring within a minute.
     */
    public MenuTransition(Kind kind, long durationMs, float distance, Easing easing) {
        this.kind = kind;
        this.distance = distance;
        this.animation = new Animation(durationMs, easing);
    }

    /* ------------------------------------------------------------------ */
    /* Driving                                                             */
    /* ------------------------------------------------------------------ */

    /** Points the transition at its open or closed end. Safe to call every frame with the same value. */
    public MenuTransition direction(boolean opening) {
        animation.setForward(opening);
        return this;
    }

    /** Jumps straight to one end, for a screen that should open already settled. */
    public MenuTransition snap(boolean open) {
        animation.reset(open);
        return this;
    }

    /**
     * Advances time and returns the eased progress, 0 closed to 1 open.
     *
     * <p>Call this exactly once per frame per transition - it is what moves the clock. Use {@link #peek()}
     * for any further reads inside the same frame.
     */
    public float advance() {
        return animation.get();
    }

    public float peek() {
        return animation.peek();
    }

    public boolean settled() {
        return animation.isFinished();
    }

    /** True once a closing transition has fully run out, i.e. the panel is gone and need not be drawn. */
    public boolean gone() {
        return animation.isIdle();
    }

    /* ------------------------------------------------------------------ */
    /* Applying                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Pushes the transition's transform and returns its opacity.
     *
     * <p>Always paired with {@link #pop(DrawContext)} - the caller is expected to wrap its drawing in the two.
     * Returns 0 when there is nothing to draw, which the caller can use to skip the work entirely.
     *
     * @param progress the value from {@link #advance()}, passed in rather than read again so one frame cannot
     *                 accidentally advance the clock twice
     */
    public float push(@NotNull DrawContext context, float progress, float centreX, float centreY) {
        var matrices = context.getMatrices();
        matrices.pushMatrix();

        float remaining = 1f - progress;

        switch (kind) {
            case SLIDE_UP, FADE_AND_SLIDE -> matrices.translate(0f, remaining * distance);
            case SLIDE_DOWN -> matrices.translate(0f, -remaining * distance);
            case SLIDE_LEFT -> matrices.translate(remaining * distance, 0f);
            case SLIDE_RIGHT -> matrices.translate(-remaining * distance, 0f);
            case SCALE -> {
                // Scaled about the panel's own centre, not the window's origin, or it would fly in from the
                // top-left corner instead of growing where it sits.
                float scale = 0.92f + 0.08f * progress;
                matrices.translate(centreX, centreY);
                matrices.scale(scale, scale);
                matrices.translate(-centreX, -centreY);
            }
            case FADE, CROSSFADE -> {
                // Nothing to move.
            }
        }

        return switch (kind) {
            // A pure slide keeps full opacity: fading a slide as well makes short slides look washed out.
            case SLIDE_UP, SLIDE_DOWN, SLIDE_LEFT, SLIDE_RIGHT -> 1f;
            default -> progress;
        };
    }

    public void pop(@NotNull DrawContext context) {
        context.getMatrices().popMatrix();
    }

    /**
     * A staggered progress for item {@code index} of {@code count}.
     *
     * <p>This is what makes the circular buttons arrive left to right instead of all at once. Each item gets
     * the same curve shifted along the timeline, and the last one still finishes exactly when the transition
     * does - so the row never has one button lagging behind after everything has settled.
     *
     * @param spread how much of the timeline is given over to the stagger, 0 to 1. 0.45 leaves over half the
     *               duration for every button to still have a full ramp of its own.
     */
    public static float stagger(float progress, int index, int count, float spread) {
        if (count <= 1) return progress;

        float slice = spread / (count - 1);
        float start = slice * index;
        float span = 1f - spread;

        float local = (progress - start) / Math.max(0.0001f, span);
        return local < 0f ? 0f : Math.min(local, 1f);
    }

    /** Ease-out applied to an already-linear 0..1, for a staggered item's own ramp. */
    public static float ease(float t) {
        return Easing.CubicOut.applyClamped(t);
    }
}
