package thunder.hack.injection;

import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.EventKeyPress;
import thunder.hack.events.impl.EventKeyRelease;
import thunder.hack.gui.clickui.ClickGUI;
import thunder.hack.gui.hud.HudEditorGui;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.features.modules.Module;

import static thunder.hack.features.modules.Module.mc;

@Mixin(Keyboard.class)
public class MixinKeyboard {

    /** Typing sound: every character typed into one of the client's own screens (ClickGUI, windows, HUD editor...). */
    @Inject(method = "onChar", at = @At("HEAD"))
    private void typingSoundChar(long window, net.minecraft.client.input.CharInput input, CallbackInfo ci) {
        if (window == mc.getWindow().getHandle() && isClientScreen()) Managers.SOUND.playTyping();
    }

    /** ...and the editing keys that type nothing themselves: backspace, delete, enter. */
    @Inject(method = "onKey", at = @At("HEAD"))
    private void typingSoundKey(long window, int action, net.minecraft.client.input.KeyInput input, CallbackInfo ci) {
        if (action == 0 || window != mc.getWindow().getHandle() || !isClientScreen()) return;
        int k = input.key();
        if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE || k == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                || k == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || k == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
            Managers.SOUND.playTyping();
    }

    private static boolean isClientScreen() {
        return mc.currentScreen != null && mc.currentScreen.getClass().getName().startsWith("thunder.hack.gui");
    }

    // 1.21.11: onKey(long, int key, int scancode, int action, int modifiers) -> onKey(long, int action, KeyInput input)
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void onKey(long windowPointer, int action, net.minecraft.client.input.KeyInput input, CallbackInfo ci) {
        if(Module.fullNullCheck()) return;
        int key = input.key();
        int scanCode = input.scancode();
        boolean whitelist = mc.currentScreen == null || mc.currentScreen instanceof ClickGUI || mc.currentScreen instanceof HudEditorGui;
        if (!whitelist) return;

        if (action == 0) Managers.MODULE.onKeyReleased(key);
        if (action == 1) Managers.MODULE.onKeyPressed(key);
        if (action == 2) action = 1;

        switch (action) {
            case 0 -> {
                EventKeyRelease event = new EventKeyRelease(key, scanCode);
                ThunderHack.EVENT_BUS.post(event);
                if (event.isCancelled()) ci.cancel();
            }
            case 1 -> {
                EventKeyPress event = new EventKeyPress(key, scanCode);
                ThunderHack.EVENT_BUS.post(event);
                if (event.isCancelled()) ci.cancel();
            }
        }
    }
}