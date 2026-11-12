package thunder.hack.utility.render;

/**
 * BKClient's {@code JumpDistortPost} (MIT, bkubiakdev): the "Liquid" jump circle - a ripple that bends the world
 * on the ground, with tint, turbulence and rising smoke - through {@link BkWorldPost} and BK's own shader.
 */
public final class JumpDistortRenderer {
    private JumpDistortRenderer() {
    }

    /** BK's std140 Ripples block: 3 header vec4, the inverse view-projection, 32 data vec4, 64 colour vec4. */
    public static final int FLOATS = 412;

    private static final BkWorldPost POST = new BkWorldPost("bk_jump_distort", "Ripples", FLOATS);

    public static boolean isBroken() {
        return POST.isBroken();
    }

    public static void apply(float[] data) {
        POST.apply(data);
    }
}
