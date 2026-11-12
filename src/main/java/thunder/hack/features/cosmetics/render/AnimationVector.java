package thunder.hack.features.cosmetics.render;

import com.google.gson.JsonElement;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.Pattern;

/** Numeric vectors and the time/sine/cosine expressions authored in the character assets. */
final class AnimationVector {
    private static final String NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)";
    private static final Pattern TIME = Pattern.compile("(?:query|q)\\.anim_time\\*(" + NUMBER + ")");
    private static final Pattern WAVE = Pattern.compile("(?:(" + NUMBER + ")\\+)?math\\.(sin|cos)\\((?:query|q)\\.anim_time\\*(" + NUMBER + ")\\)\\*(" + NUMBER + ")");
    private final DoubleUnaryOperator[] axes = new DoubleUnaryOperator[3];

    AnimationVector(JsonElement value, float fallback) {
        for (int axis = 0; axis < 3; axis++) {
            JsonElement scalar = value != null && value.isJsonArray()
                    ? (axis < value.getAsJsonArray().size() ? value.getAsJsonArray().get(axis) : null) : value;
            axes[axis] = compile(scalar, fallback);
        }
    }
    float sample(int axis, float time) {
        double result = axes[axis].applyAsDouble(time);
        return Double.isFinite(result) && Math.abs(result) <= Float.MAX_VALUE ? (float) result : 0;
    }
    float[] sample(float time) { return new float[]{sample(0,time),sample(1,time),sample(2,time)}; }

    private static DoubleUnaryOperator compile(JsonElement value, float fallback) {
        if (value == null || !value.isJsonPrimitive()) return t -> fallback;
        String expression = value.getAsString().replaceAll("\\s+", "");
        try { float number = Float.parseFloat(expression); if (Float.isFinite(number)) return t -> number; }
        catch (NumberFormatException ignored) { }
        var time = TIME.matcher(expression);
        if (time.matches()) { double speed = Double.parseDouble(time.group(1)); return t -> t * speed; }
        var wave = WAVE.matcher(expression);
        if (wave.matches()) {
            double offset = wave.group(1) == null ? 0 : Double.parseDouble(wave.group(1));
            boolean sine = wave.group(2).equals("sin");
            double speed = Double.parseDouble(wave.group(3)), amplitude = Double.parseDouble(wave.group(4));
            return t -> offset + (sine ? Math.sin(Math.toRadians(t * speed)) : Math.cos(Math.toRadians(t * speed))) * amplitude;
        }
        // Preserve the previous fallback for unsupported expressions; never execute asset strings as code.
        return t -> fallback;
    }
}
