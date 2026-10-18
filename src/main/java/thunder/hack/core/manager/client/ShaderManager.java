package thunder.hack.core.manager.client;

/**
 * 1.21.6+ stub. The original ShaderManager drove the custom "satin" ManagedCoreShader / ShaderEffect
 * framework (Gradient/Smoke hand & world post-process shaders), all of which sat on the removed
 * JsonEffectShaderProgram / GlUniform / FabricShaderProgram / PostEffectPass API. That subsystem was
 * removed during the RenderPipeline port and needs a full reimplementation on RenderPipeline + UBO.
 * Until then this stub keeps the public API so callers compile; shader effects are no-ops.
 */
public class ShaderManager {
    public enum Shader {
        Default, Gradient, Smoke
    }

    public void renderShader(Runnable drawer, Shader shader) {
        if (drawer != null) drawer.run();
    }

    public void renderShaders() {
    }

    public void reloadShaders() {
    }
}