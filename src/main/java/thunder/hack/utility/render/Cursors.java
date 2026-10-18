package thunder.hack.utility.render;

import org.lwjgl.glfw.GLFW;

/**
 * Standard GLFW cursors, created once.
 *
 * <p>Every call to {@code glfwCreateStandardCursor} allocates a new OS cursor object that lives until it is
 * destroyed, and the HUD and ClickGUI were creating one every frame - thousands of leaked handles after a
 * session, and the leak costs more on some systems than on others. One cached cursor per shape instead.
 */
public final class Cursors {
    private Cursors() {
    }

    private static final java.util.Map<Integer, Long> CACHE = new java.util.HashMap<>();

    public static long get(int shape) {
        return CACHE.computeIfAbsent(shape, GLFW::glfwCreateStandardCursor);
    }
}
