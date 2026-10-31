package thunder.hack.injection;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.ScoreboardObjective;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.hud.impl.Hotbar;
import thunder.hack.gui.windows.WindowsScreen;
import thunder.hack.features.modules.Module;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static thunder.hack.core.manager.IManager.mc;

@Mixin(InGameHud.class)
public abstract class MixinInGameHud {

    /**
     * Na 1.21.11 GameRenderer woła {@code inGameHud.renderDebugHud(ctx)} DOPIERO po całym
     * {@code inGameHud.render(...)}, więc panel F3 ląduje w późniejszej warstwie GUI i przykrywa wszystko,
     * co narysowaliśmy na HEAD-zie — ArrayList, TargetInfo i KeyBinds nie znikały, tylko chowały się pod
     * ciemnym tłem debugu. Dlatego przy włączonym F3 rysujemy klienta z {@code renderDebugHud} (na wierzchu),
     * a bez F3 tak jak dotąd, żeby HUD dalej siedział pod otwartymi ekranami.
     */
    @Inject(at = @At(value = "HEAD"), method = "render")
    public void renderHook(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if(Module.fullNullCheck()) return;
        if (thunderHack$debugHudVisible()) return;
        Managers.MODULE.onRender2D(context);
        Managers.NOTIFICATION.onRender2D(context);
    }

    @Inject(at = @At(value = "RETURN"), method = "renderDebugHud")
    public void renderDebugHudHook(DrawContext context, CallbackInfo ci) {
        if(Module.fullNullCheck()) return;
        if (!thunderHack$debugHudVisible()) return;
        Managers.MODULE.onRender2D(context);
        // with F3 the client HUD (Hotbar background included) is drawn on this later layer, so the hotbar items
        // go here too, AFTER it - drawn in renderHotbar they ended up underneath and vanished
        if (ModuleManager.hotbar.isEnabled())
            Hotbar.renderHotBarItems(mc.getRenderTickCounter().getTickProgress(true), context);
        Managers.NOTIFICATION.onRender2D(context);
    }

    /**
     * F3 draws later than any open screen, so the client HUD moves up there only while no screen is open -
     * with an inventory open it stays in render, under the inventory where it belongs.
     */
    @Unique
    private boolean thunderHack$debugHudVisible() {
        return mc != null && mc.currentScreen == null && mc.getDebugHud() != null && mc.getDebugHud().shouldShowDebugHud();
    }

    @Inject(at = @At(value = "HEAD"), method = "renderStatusBars", cancellable = true)
    private void renderStatusBarsHook(DrawContext context, CallbackInfo ci) {
        if (mc != null && mc.currentScreen instanceof WindowsScreen) {
            ci.cancel();
        }
    }

    @Inject(at = @At(value = "HEAD"), method = "renderHotbar", cancellable = true)
    public void renderHotbarCustom(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (mc != null && mc.currentScreen instanceof WindowsScreen)
            ci.cancel();

        if (ModuleManager.hotbar.isEnabled()) {
            ci.cancel();
            if (!thunderHack$debugHudVisible())   // with F3 they are drawn in renderDebugHudHook instead
                Hotbar.renderHotBarItems(tickCounter.getTickProgress(true), context);
        }
    }


    @Inject(at = @At(value = "HEAD"), method = "renderHeldItemTooltip", cancellable = true)
    public void renderHeldItemTooltipHook(DrawContext context, CallbackInfo ci) {
        if (ModuleManager.noRender.isEnabled() && ModuleManager.noRender.hotbarItemName.getValue())
            ci.cancel();
    }

    @Inject(at = @At(value = "HEAD"), method = "renderStatusEffectOverlay", cancellable = true)
    public void renderStatusEffectOverlayHook(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (ModuleManager.potionHud.isEnabled()) {
            ci.cancel();
        }
    }

    // 1.21.11: InGameHud.renderExperienceBar(DrawContext,int) removed (XP bar folded into renderMainHud).
    // Disabled -> Hotbar custom XP bar / WindowsScreen XP-hide need a new hook.
    /*
    @Inject(method = "renderExperienceBar", at = @At(value = "HEAD"), cancellable = true)
    public void renderXpBarCustom(DrawContext context, int x, CallbackInfo ci) {
        if (mc != null && mc.currentScreen instanceof WindowsScreen)
            ci.cancel();

        if (ModuleManager.hotbar.isEnabled()) {
            ci.cancel();
            Hotbar.renderXpBar(x, context);
        }
    }
    */

    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At(value = "HEAD"), cancellable = true)
    private void renderScoreboardSidebarHook(DrawContext context, ScoreboardObjective objective, CallbackInfo ci) {
        if(ModuleManager.noRender.noScoreBoard.getValue() && ModuleManager.noRender.isEnabled()){
            ci.cancel();
        }
    }

    @Inject(method = "renderVignetteOverlay", at = @At(value = "HEAD"), cancellable = true)
    private void renderVignetteOverlayHook(DrawContext context, Entity entity, CallbackInfo ci) {
        if(ModuleManager.noRender.vignette.getValue())
            ci.cancel();
    }

    @Inject(method = "renderPortalOverlay", at = @At(value = "HEAD"), cancellable = true)
    private void renderPortalOverlayHook(DrawContext context, float nauseaStrength, CallbackInfo ci) {
        if(ModuleManager.noRender.portal.getValue())
            ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At(value = "HEAD"), cancellable = true)
    public void renderCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (ModuleManager.crosshair.isEnabled())
            ci.cancel();
    }
}
