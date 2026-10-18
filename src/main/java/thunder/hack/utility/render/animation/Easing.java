package thunder.hack.utility.render.animation;

/**
 * The client's easing curves. Every one maps 0..1 to 0..1, so any of them can be dropped into
 * {@link Animation} or used on its own without knowing what it is driving.
 */
public enum Easing {
    Linear {
        @Override
        public float apply(float t) {
            return t;
        }
    },
    EaseIn {
        @Override
        public float apply(float t) {
            return t * t;
        }
    },
    EaseOut {
        @Override
        public float apply(float t) {
            return 1f - (1f - t) * (1f - t);
        }
    },
    EaseInOut {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
        }
    },
    CubicIn {
        @Override
        public float apply(float t) {
            return t * t * t;
        }
    },
    CubicOut {
        @Override
        public float apply(float t) {
            return 1f - (float) Math.pow(1f - t, 3);
        }
    },
    CubicInOut {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
        }
    },
    /** Overshoots a little and settles - the "it snapped into place" feel, without a real spring solver. */
    Spring {
        @Override
        public float apply(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            double decay = Math.pow(2, -9 * t);
            return (float) (1 - decay * Math.cos(t * 12));
        }
    };

    public abstract float apply(float t);

    /** Clamps first, so a caller does not have to. */
    public float applyClamped(float t) {
        return apply(t < 0f ? 0f : Math.min(t, 1f));
    }
}
