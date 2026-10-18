package thunder.hack.utility.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * 1.21.6+ compatibility shim. Mojang's RenderPipeline overhaul removed the immediate-mode
 * GL-state surface of {@code com.mojang.blaze3d.systems.RenderSystem} (blend/depth/cull/color
 * masks, shader/texture binding, fog uniforms, etc.). Those concerns are now owned by the
 * pipeline/RenderLayer that a draw is submitted on, so the old per-call state toggles are no-ops.
 *
 * This class lets the ported call sites compile unchanged (thunder.hack.utility.render.RSCompat.x -> RSCompat.x) without
 * sprinkling deletions through control flow. Geometry that was built via the removed
 * Tessellator/BufferRenderer immediate path still needs a real RenderPipeline reimplementation.
 */
public final class RSCompat {
    private RSCompat() {}

    public static void noop() {}
    public static void noop(Object... ignored) {}

    public static void setShaderColor(float r, float g, float b, float a) {}
    public static void enableBlend() {}
    public static void disableBlend() {}
    public static void defaultBlendFunc() {}
    public static void enableDepthTest() {}
    public static void disableDepthTest() {}
    public static void enableCull() {}
    public static void disableCull() {}
    public static void depthMask(boolean mask) {}
    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {}
    public static void texParameter(int target, int pname, int param) {}
    public static void clearColor(float r, float g, float b, float a) {}
    public static void clear(int mask, boolean getError) {}
    public static void assertOnRenderThreadOrInit() {}
    public static void assertOnRenderThread() {}
    public static void assertInInitPhase() {}
    public static boolean isOnRenderThread() { return true; }
    public static void setShaderFogStart(float v) {}
    public static void setShaderFogStart(int v) {}
    public static void setShaderFogEnd(float v) {}
    public static void setShaderFogEnd(int v) {}
    public static void setShaderFogColor(float r, float g, float b) {}
    public static void depthFunc(int func) {}
    public static void lineWidth(float w) {}
    public static void resetTextureMatrix() {}
    public static void recordRenderCall(Runnable r) { if (r != null) r.run(); }
    public static void applyModelViewMatrix() {}
    public static Matrix4f getProjectionMatrix() { return new Matrix4f(); }
    public static Matrix4f getModelViewMatrix() { return new Matrix4f(); }

    // Must be a stable instance: callers do balanced pushMatrix()/popMatrix() pairs and a fresh
    // stack per call would pop an empty stack ("already at the bottom of the stack"). Render thread only.
    private static final Matrix4fStack MODEL_VIEW_STACK = new Matrix4fStack(16);
    public static Matrix4fStack getModelViewStack() { return MODEL_VIEW_STACK; }
}