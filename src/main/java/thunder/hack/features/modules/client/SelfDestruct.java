package thunder.hack.features.modules.client;

import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.misc.AutoBuild;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render3DEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The emergency stop: one keybind that takes the whole client back to "not running".
 *
 * <p>STOP -&gt; DISABLE -&gt; CLEANUP -&gt; RELEASE -&gt; SHUTDOWN. Every module off, the AutoBuild
 * printer and its queue stopped, timers and async work cancelled, render queues emptied, the ClickGUI
 * and HUD editor closed, integrations stopped. Minecraft itself keeps running and stays playable - this
 * shuts <em>Cloudan</em> down, nothing else.
 *
 * <p>It deliberately does not touch anything outside the client: no files of yours, no system settings,
 * no configuration. Your configs are still there when you toggle it back on.
 */
public class SelfDestruct extends Module {
    public SelfDestruct() {
        super("SelfDestruct", Category.CLIENT);
    }

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Shutdown);
    /**
     * The whole point of this module is that it fires when you need it, which is also exactly when a
     * misfire hurts most - so the bind has to be held, and a tap does nothing.
     */
    private final Setting<Boolean> holdToFire = new Setting<>("HoldToFire", true);
    private final Setting<Float> holdSeconds = new Setting<>("HoldSeconds", 1.0f, 0.2f, 5f, v -> holdToFire.getValue());
    private final Setting<Boolean> confirm = new Setting<>("Confirm", true);
    /**
     * Deletes the client's own jar from the mods folder.
     *
     * <p>Off by default and deliberately hard to reach: this is the one thing here that cannot be undone
     * by toggling something back on. It removes the mod jar and NOTHING else - not your configs, not
     * other mods, not a single file outside the one this class was loaded from.
     */
    private final Setting<Boolean> removeMod = new Setting<>("RemoveMod", false, v -> mode.getValue() == Mode.FullCleanup);
    private final Setting<Boolean> quitAfterRemove = new Setting<>("QuitAfterRemove", true, v -> removeMod.getValue() && mode.getValue() == Mode.FullCleanup);
    private final Setting<Boolean> debug = new Setting<>("Debug", false);

    /** Set by the first activation when Confirm is on; a second one inside this window actually fires. */
    private long armedUntil;
    private long heldSince;

    public enum Mode {
        /** Turns every Cloudan module off and leaves the runtime up. */
        Disable,
        /** Disable, plus tasks, caches, render resources and integrations. The runtime stops. */
        Shutdown,
        /** Everything Shutdown does, and clears the safe runtime caches on the way out. */
        FullCleanup
    }

    @Override
    public void onEnable() {
        // Enabling IS the trigger. The module turns itself back off the moment it has run, so it never
        // sits there "on" doing nothing.
        if (holdToFire.getValue()) {
            heldSince = System.currentTimeMillis();
            log("uzbrojony - trzymaj bind " + holdSeconds.getValue() + "s");
            return;
        }

        fire();
    }

    @Override
    public void onUpdate() {
        if (!holdToFire.getValue() || heldSince == 0) return;

        // Letting go of the bind before the timer is the whole accidental-trigger protection.
        if (!bindHeld()) {
            heldSince = 0;
            log(Formatting.YELLOW + "anulowane - bind puszczony");
            disable();
            return;
        }

        if (System.currentTimeMillis() - heldSince < (long) (holdSeconds.getValue() * 1000f)) return;

        heldSince = 0;
        fire();
    }

    private void fire() {
        if (confirm.getValue() && System.currentTimeMillis() > armedUntil) {
            armedUntil = System.currentTimeMillis() + 5000;
            sendMessage(Formatting.RED + "SELFDESTRUCT " + Formatting.RESET + "- wlacz jeszcze raz w ciagu 5s, zeby potwierdzic.");
            if (mode.getValue() == Mode.FullCleanup && removeMod.getValue())
                sendMessage(Formatting.RED + "UWAGA: " + Formatting.RESET + "usunie jara moda z folderu mods. Tego nie cofniesz.");
            disable();
            return;
        }

        armedUntil = 0;
        run(mode.getValue());
        // Never leave the trigger latched on: the next press has to be a fresh decision.
        disable();
    }

    /**
     * Runs the shutdown. Every stage is independent and wrapped, because a cleanup that stops halfway
     * through on one bad listener is worse than no cleanup at all - it leaves the client half alive.
     */
    public void run(@NotNull Mode mode) {
        log(Formatting.RED + "SELFDESTRUCT START" + Formatting.RESET + " (" + mode + ")");

        stage("MODULES DISABLED", this::disableModules);
        stage("AUTOBUILD STOPPED", SelfDestruct::stopAutoBuild);
        stage("GUI CLOSED", SelfDestruct::closeGui);

        if (mode == Mode.Disable) {
            log(Formatting.GREEN + "moduly wylaczone, runtime dziala dalej");
            return;
        }

        stage("TASKS CANCELLED", SelfDestruct::cancelTasks);
        stage("RENDER RESOURCES RELEASED", SelfDestruct::releaseRender);
        stage("INTEGRATIONS STOPPED", SelfDestruct::stopIntegrations);

        if (mode == Mode.FullCleanup)
            stage("CACHE CLEANED", SelfDestruct::cleanCaches);

        stage("CLOUDAN SHUTDOWN", SelfDestruct::shutdown);

        if (mode == Mode.FullCleanup && removeMod.getValue())
            stage("MOD REMOVED", this::removeSelf);

        log(Formatting.GREEN + "gotowe - Minecraft dziala normalnie");
    }

    /* ------------------------------------------------------------------ */
    /* Removing the jar                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Deletes the jar this class was loaded from.
     *
     * <p>It cannot be deleted while the game is running - the JVM holds the file open, and on Windows an
     * open file simply refuses to be unlinked. So the delete is handed to a detached shell that waits for
     * this process to exit first and then removes it. That is the only way the file actually goes away
     * without asking you to close the game and find it by hand.
     *
     * <p>One path, resolved from our own code source. Nothing is searched for, no folder is walked, and
     * nothing that is not this jar can be reached by it.
     */
    private void removeSelf() {
        Path jar;
        try {
            jar = Paths.get(SelfDestruct.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            log(Formatting.RED + "nie moge ustalic sciezki jara: " + e.getMessage());
            return;
        }

        // Running from loose classes in a dev environment - there is no jar to remove.
        if (!Files.isRegularFile(jar)) {
            log(Formatting.YELLOW + "nie uruchomiono z jara, nic do usuniecia");
            return;
        }

        // Try the straightforward delete first: it works on Linux and macOS, where an open file can be
        // unlinked, and costs nothing to attempt on Windows.
        try {
            if (Files.deleteIfExists(jar)) {
                log(Formatting.GREEN + "usunieto " + jar.getFileName());
                if (quitAfterRemove.getValue()) mc.scheduleStop();
                return;
            }
        } catch (IOException ignored) {
            // Expected on Windows while the jar is mapped; fall through to the deferred delete.
        }

        if (scheduleDelete(jar)) {
            log(Formatting.GREEN + "usune " + jar.getFileName() + " po zamknieciu gry");
            if (quitAfterRemove.getValue()) mc.scheduleStop();
        } else {
            log(Formatting.RED + "nie udalo sie - usun recznie: " + jar);
        }
    }

    /** Hands the delete to a detached shell that waits for this JVM to exit first. */
    private static boolean scheduleDelete(Path jar) {
        String os = System.getProperty("os.name", "").toLowerCase();
        long pid = ProcessHandle.current().pid();

        try {
            ProcessBuilder builder;

            if (os.contains("win")) {
                // Waits for our PID to disappear, then deletes. /T 2 is a two second poll, not a fixed
                // sleep, so it does not race a slow shutdown.
                String command = "for /l %x in () do ("
                        + "tasklist /FI \"PID eq " + pid + "\" | find \"" + pid + "\" >nul || ("
                        + "del /f /q \"" + jar.toAbsolutePath() + "\" & exit)"
                        + " & timeout /t 2 /nobreak >nul)";
                builder = new ProcessBuilder("cmd", "/c", "start", "/min", "\"\"", "cmd", "/c", command);
            } else {
                String command = "while kill -0 " + pid + " 2>/dev/null; do sleep 2; done; rm -f '"
                        + jar.toAbsolutePath() + "'";
                builder = new ProcessBuilder("sh", "-c", command);
            }

            builder.start();
            return true;
        } catch (Exception e) {
            ThunderHack.LOGGER.error("[SelfDestruct] could not schedule jar removal: {}", e.toString());
            return false;
        }
    }

    private void stage(String name, Runnable action) {
        try {
            action.run();
            log("-> " + name);
        } catch (Throwable t) {
            // Logged and stepped over: the remaining stages still have to run or the client is left in a
            // half-shut-down state, which is exactly what this module exists to prevent.
            ThunderHack.LOGGER.error("[SelfDestruct] {} failed: {}", name, t.toString());
            log(Formatting.YELLOW + "-> " + name + " (blad, ide dalej)");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Stages                                                              */
    /* ------------------------------------------------------------------ */

    /** Everything off except this module and UnHook, which is the one that has to survive to unhook. */
    private void disableModules() {
        List<Module> on = new ArrayList<>(Managers.MODULE.getEnabledModules());
        for (Module module : on) {
            if (module == this || module == ModuleManager.unHook) continue;
            try {
                module.disable();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Stops the printer, its queue, its schematic work and its preview - no worker may survive. */
    private static void stopAutoBuild() {
        AutoBuild.building = false;
        ModuleManager.autoBuild.disable();
        AutoBuild.schematic = null;
        AutoBuild.origin = null;
        AutoBuild.pos1 = null;
        AutoBuild.pos2 = null;
        AutoBuild.rebuildQueue();
    }

    private static void closeGui() {
        ModuleManager.clickGui.disable();
        ModuleManager.hudEditor.disable();
        if (mc.currentScreen != null && mc.currentScreen.getClass().getName().startsWith("thunder.hack"))
            mc.setScreen(null);
    }

    private static void cancelTasks() {
        Managers.ASYNC.cancelAll();
    }

    /** Empties the render queues so nothing is left half-drawn on the next frame. */
    private static void releaseRender() {
        Render3DEngine.FILLED_QUEUE.clear();
        Render3DEngine.OUTLINE_QUEUE.clear();
        Render3DEngine.FADE_QUEUE.clear();
        Render3DEngine.FILLED_SIDE_QUEUE.clear();
        Render3DEngine.OUTLINE_SIDE_QUEUE.clear();
        Render3DEngine.DEBUG_LINE_QUEUE.clear();
        Render3DEngine.LINE_QUEUE.clear();
    }

    private static void stopIntegrations() {
        ModuleManager.rpc.disable();
        if (mc.getSoundManager() != null) mc.getSoundManager().stopAll();
    }

    /** Only the client's own runtime caches. Nothing on disk, nothing of yours. */
    private static void cleanCaches() {
        thunder.hack.features.modules.render.BlockESP.blocks.clear();
        thunder.hack.utility.render.core.CloudanRenderCore.onWorldChange();
    }

    /** Unsubscribes the client from the event bus: nothing of ours runs after this. */
    private static void shutdown() {
        ModuleManager.unHook.enable();
    }

    /** True while the module bind is physically down. GLFW is the only thing that knows. */
    private boolean bindHeld() {
        int key = getBind().getKey();
        if (key <= 0 || mc.getWindow() == null) return false;
        if (getBind().isMouse())
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        return org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    private void log(String message) {
        if (debug.getValue()) sendMessage(message);
        ThunderHack.LOGGER.info("[SelfDestruct] {}", Formatting.strip(message));
    }

    @Override
    public String getDisplayInfo() {
        return mode.getValue().name();
    }
}
