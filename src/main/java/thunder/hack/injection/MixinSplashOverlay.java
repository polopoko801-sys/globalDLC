package thunder.hack.injection;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import thunder.hack.features.modules.client.ClientSettings;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.gui.font.FontRenderers;

import java.awt.*;
import java.util.Optional;
import java.util.function.Consumer;

import static thunder.hack.features.modules.Module.mc;

@Mixin(SplashOverlay.class)
public abstract class MixinSplashOverlay {
    @Final @Shadow private boolean reloading;
    @Shadow private float progress;
    @Shadow private long reloadCompleteTime = -1L;
    @Shadow private long reloadStartTime = -1L;
    @Final @Shadow private ResourceReload reload;
    @Final @Shadow private Consumer<Optional<Throwable>> exceptionHandler;

    /** Start zegara animacji (ms). Zerowy = jeszcze nie policzono, bo ekran nie zawsze od razu wchodzi. */
    @Unique private long animStart = -1L;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    public void render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if(ModuleManager.unHook.isEnabled() || !ClientSettings.customLoadingScreen.getValue())
            return;

        // Our screen replaces the vanilla one entirely, so anything it cannot draw has to hand the frame
        // back rather than throw: an exception here is "Rendering overlay" and the game is gone before it
        // ever reaches a menu. A loading screen in the wrong font is a far better outcome than that.
        if (FontRenderers.monsterrat == null && FontRenderers.brand == null) return;

        ci.cancel();
        renderCustom(context, mouseX, mouseY, delta);
    }

    public void renderCustom(DrawContext context, int mouseX, int mouseY, float delta) {
        int i = mc.getWindow().getScaledWidth();
        int j = mc.getWindow().getScaledHeight();
        long l = Util.getMeasuringTimeMs();
        if (reloading && reloadStartTime == -1L) {
            reloadStartTime = l;
        }

        float f = reloadCompleteTime > -1L ? (float) (l - reloadCompleteTime) / 1000.0F : -1.0F;
        float g = reloadStartTime > -1L ? (float) (l - reloadStartTime) / 500.0F : -1.0F;
        float h;
        int k;
        if (f >= 1.0F) {
            if (mc.currentScreen != null)
                mc.currentScreen.render(context, 0, 0, delta);

            k = MathHelper.ceil((1.0F - MathHelper.clamp(f - 1.0F, 0.0F, 1.0F)) * 255.0F);
            context.fill(0, 0, i, j, withAlpha(new Color(0x070015).getRGB(), k));
            h = 1.0F - MathHelper.clamp(f - 1.0F, 0.0F, 1.0F);
        } else if (reloading) {
            if (mc.currentScreen != null && g < 1.0F)
                mc.currentScreen.render(context, mouseX, mouseY, delta);

            k = MathHelper.ceil(MathHelper.clamp((double) g, 0.15, 1.0) * 255.0);
            context.fill(0, 0, i, j, withAlpha(new Color(0x070015).getRGB(), k));
            h = MathHelper.clamp(g, 0.0F, 1.0F);
        } else {
            k = new Color(0x070015).getRGB();
            float m = (float) (k >> 16 & 255) / 255.0F;
            float n = (float) (k >> 8 & 255) / 255.0F;
            float o = (float) (k & 255) / 255.0F;
            thunder.hack.utility.render.RSCompat.clearColor(m, n, o, 1.0F);
            thunder.hack.utility.render.RSCompat.clear(16384, false);
            h = 1.0F;
        }

        k = (int) ((double) context.getScaledWindowWidth() * 0.5);
        int p = (int) ((double) context.getScaledWindowHeight() * 0.5);

        thunder.hack.utility.render.RSCompat.enableBlend();
        thunder.hack.utility.render.RSCompat.noop();

        // Sam pasek postępu. Animacja szklanego G (SplashArt) usunięta - user: laguje przy starcie.
        drawLoadingFallback(context, k, p - 8, progress);
        float t = this.reload.getProgress();
        this.progress = MathHelper.clamp(this.progress * 0.95F + t * 0.050000012F, 0.0F, 1.0F);

        thunder.hack.utility.render.RSCompat.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        thunder.hack.utility.render.RSCompat.defaultBlendFunc();
        thunder.hack.utility.render.RSCompat.disableBlend();

        if (f >= 2.0F) {
            mc.setOverlay(null);
        }

        if (reloadCompleteTime == -1L && reload.isComplete() && (!reloading || g >= 2.0F)) {
            try {
                reload.throwException();
                exceptionHandler.accept(Optional.empty());
            } catch (Throwable var23) {
                exceptionHandler.accept(Optional.of(var23));
            }

            reloadCompleteTime = Util.getMeasuringTimeMs();
            if (mc.currentScreen != null) {
                mc.currentScreen.init(mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
            }
        }
    }

    /** Plan B: sama geometria, zero tekstur i zero czcionek - nie ma czego tu rozjechać. */
    @Unique
    private void drawLoadingFallback(DrawContext context, float cx, float cy, float load) {
        float barW = 150f;
        float progress = MathHelper.clamp(load, 0f, 1f);
        Render2DEngine.drawRound(context, cx - barW / 2f, cy + 40f, barW, 3f, 1.5f, new Color(255, 255, 255, 40));
        Render2DEngine.drawRound(context, cx - barW / 2f, cy + 40f, barW * progress, 3f, 1.5f, new Color(150, 190, 255, 230));
    }

    private static int withAlpha(int color, int alpha) {
        return color & 16777215 | alpha << 24;
    }
}
