package thunder.hack.events.impl;

import thunder.hack.events.Event;

import java.util.ArrayList;
import java.util.List;

public class EventSync extends Event {
    public EventSync(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    float yaw;
    float pitch;
    // Multiple modules may silently rotate in the same tick (e.g. an aura + MaceAura). Each one saves the
    // real look and registers a restore; keeping only one would leave the camera turned. We run them all,
    // in reverse registration order, so the first module's restore (to the true real angle) runs last.
    private final List<Runnable> postActions = new ArrayList<>();

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void addPostAction(Runnable r) {
        if (r != null) postActions.add(r);
    }

    public Runnable getPostAction() {
        if (postActions.isEmpty()) return null;
        return () -> {
            for (int i = postActions.size() - 1; i >= 0; i--)
                postActions.get(i).run();
        };
    }
}
