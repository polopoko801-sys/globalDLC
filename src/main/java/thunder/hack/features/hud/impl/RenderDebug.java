package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.ClientSettings;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanBatchRenderer;
import thunder.hack.utility.render.core.CloudanParticleRenderer;
import thunder.hack.utility.render.core.CloudanRenderCache;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * What the render core actually did this frame.
 *
 * <p>Off by default: it is the thing you turn on when a visual is slow or a cache is not being hit, not
 * something to fly around with. The numbers come straight out of {@link CloudanRenderCore}, so they
 * count real submissions rather than anybody's estimate - a visual that thinks it is culling and is not
 * shows up here immediately as a Visible count that does not drop when you look away.
 */
public class RenderDebug extends HudElement {
    public RenderDebug() {
        super("RenderDebug", 150, 90);
    }

    private final Setting<Boolean> showCounts = new Setting<>("Counts", true);
    private final Setting<Boolean> showCache = new Setting<>("Cache", true);
    private final Setting<Boolean> showParticles = new Setting<>("Particles", true);
    /** The machine: GPU, driver, framebuffer, GUI scale and which fallbacks are active. */
    private final Setting<Boolean> showGpu = new Setting<>("GPU", true);

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        CloudanRenderCore.Stats stats = CloudanRenderCore.stats();
        List<String> lines = new ArrayList<>();

        lines.add("FPS " + mc.getCurrentFps() + "   " + String.format("%.2f ms", CloudanRenderCore.renderMillis()));
        lines.add("Quality " + ClientSettings.quality.getValue()
                + (thunder.hack.utility.render.core.FrameBudget.throttling()
                ? "  x" + String.format("%.2f", thunder.hack.utility.render.core.FrameBudget.scale()) + " (FPS)" : ""));

        if (showGpu.getValue()) {
            lines.add("GPU    " + thunder.hack.utility.render.core.GpuCapabilities.vendorKind() + "  "
                    + trimTo(thunder.hack.utility.render.core.GpuCapabilities.renderer(), 34));
            lines.add("Driver " + trimTo(thunder.hack.utility.render.core.GpuCapabilities.driver(), 40));
            lines.add("Frame  " + String.format("%.1f ms", thunder.hack.utility.render.core.FrameBudget.frameMillis())
                    + "   fb " + mc.getWindow().getFramebufferWidth() + "x" + mc.getWindow().getFramebufferHeight()
                    + "  gui " + mc.getWindow().getScaleFactor());
            lines.add("Glass  " + (thunder.hack.utility.render.core.GpuCapabilities.backdropAvailable() ? "backdrop" : "FALLBACK painted")
                    + (thunder.hack.utility.render.core.GpuCapabilities.displayScaled() ? "   OS scaling" : ""));
        }

        if (showCounts.getValue()) {
            lines.add("Draw calls  " + stats.drawCalls);
            lines.add("Visible     " + stats.visibleObjects);
            lines.add("Culled      " + stats.culled);
            lines.add("Pending     " + CloudanBatchRenderer.pending());
        }

        if (showParticles.getValue())
            lines.add("Particles   " + CloudanParticleRenderer.alive() + " (+" + CloudanParticleRenderer.pooled() + " pooled)");

        if (showCache.getValue()) {
            int hits = stats.cacheHits, misses = stats.cacheMisses;
            int total = hits + misses;
            // A hit rate is the only number that says whether a cache is worth its memory.
            String rate = total == 0 ? "-" : Math.round(hits * 100f / total) + "%";
            lines.add("Cache  " + hits + "/" + misses + "  " + rate);
            lines.add("Cached      " + CloudanRenderCache.totalEntries());
        }

        float width = 0;
        for (String line : lines) width = Math.max(width, FontRenderers.sf_bold_mini.getStringWidth(line));
        width += 14f;
        float height = lines.size() * 11f + 8f;

        float x = getPosX() > mc.getWindow().getScaledWidth() / 2f ? getPosX() - width : getPosX();
        float y = getPosY();

        if (HudEditor.hudStyle.is(HudEditor.HudStyle.Blurry))
            Render2DEngine.drawRoundedBlur(context, x, y, width, height, 4f, HudEditor.blurColor.getValue().getColorObject());
        else
            Render2DEngine.drawRound(context, x, y, width, height, 4f, new Color(12, 12, 15, 200));

        Render2DEngine.drawRound(context, x, y, 2.2f, height, 1.1f, HudEditor.getColor(0));

        float textY = y + 5f;
        for (String line : lines) {
            FontRenderers.sf_bold_mini.drawString(context, line, x + 7f, textY, -1);
            textY += 11f;
        }

        setBounds(x, y, width, height);
    }
}
