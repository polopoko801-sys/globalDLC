package thunder.hack.utility.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

import java.util.concurrent.CopyOnWriteArrayList;

// Self-contained resize event. Previously backed by Fabric API's EventFactory, which crashed at
// runtime under Feather (no fabric-api on the classpath). This is the only fabric-api usage in the
// mod, so reimplementing it here keeps ThunderHack fully standalone.
public interface WindowResizeCallback {
    Event EVENT = new Event();

    void onResized(MinecraftClient client, Window window);

    final class Event {
        private final CopyOnWriteArrayList<WindowResizeCallback> callbacks = new CopyOnWriteArrayList<>();

        public void register(WindowResizeCallback callback) {
            callbacks.add(callback);
        }

        public WindowResizeCallback invoker() {
            return (client, window) -> {
                for (WindowResizeCallback callback : callbacks) {
                    callback.onResized(client, window);
                }
            };
        }
    }
}
