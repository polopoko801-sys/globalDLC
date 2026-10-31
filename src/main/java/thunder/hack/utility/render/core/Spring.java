package thunder.hack.utility.render.core;

/**
 * A damped spring: a value that is pulled toward its target and carries momentum, so it eases in, overshoots a
 * touch and settles - the motion that reads as physical rather than as a fade. Damping below
 * {@code 2 * sqrt(stiffness)} overshoots, above it creeps in without one.
 *
 * <p>Stepped in fixed small slices, so it behaves the same at 30 and at 300 FPS and never explodes on a long frame.
 */
public final class Spring {
    public float value, velocity;
    private float stiffness, damping;

    /** Changes how tight and how damped the spring is, keeping where it is and how fast it moves. */
    public Spring tune(float stiffness, float damping) {
        this.stiffness = stiffness;
        this.damping = damping;
        return this;
    }

    public Spring(float stiffness, float damping, float start) {
        this.stiffness = stiffness;
        this.damping = damping;
        this.value = start;
    }

    /** Advances by {@code deltaMs} toward {@code target} and returns the new value. */
    public float update(float target, float deltaMs) {
        float dt = Math.min(deltaMs, 100f) / 1000f;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = stiffness * (target - value) - damping * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return value;
    }

    /** Jumps straight to {@code v}, at rest. */
    public void snap(float v) {
        value = v;
        velocity = 0f;
    }
}
