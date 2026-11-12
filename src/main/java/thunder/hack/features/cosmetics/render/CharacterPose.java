package thunder.hack.features.cosmetics.render;

import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;

/** Character fitting and authored idle/walk/attack poses; independent of the player hitbox. */
public final class CharacterPose {
    public record Fit(float scale, float floor) { }
    public static Fit fit(BedrockModel model) {
        float[] b = model.bounds(model.pose((BedrockAnimation.Clip)null, 0, null), Set.of());
        float scale = Math.min(1.8f / Math.max(.1f, b[4] - b[1]),
                2.4f / Math.max(.1f, Math.max(b[3] - b[0], b[5] - b[2])));
        return new Fit(scale, b[1]);
    }
    public static Matrix4f[] sample(BedrockModel model, Map<String,BedrockAnimation.Clip> clips,
            float seconds, float movement, float swing, float yaw, float pitch) {
        var idle = clips.get("idle");
        var walk = clips.get("walk");
        var attack = clips.get("attack");
        float weight = Math.clamp(movement * 4, 0, 1);
        return model.pose(bone -> {
            var a = idle == null ? BedrockAnimation.Pose.IDENTITY : idle.sample(bone, seconds);
            var b = walk == null ? a : walk.sample(bone, seconds);
            float[] p = blend(a.position(), b.position(), weight), r = blend(a.rotation(), b.rotation(), weight), s = blend(a.scale(), b.scale(), weight);
            if (swing > 0 && attack != null && attack.bones().containsKey(bone)) {
                var hit = attack.sample(bone, Math.min(.9999f, swing) * attack.duration());
                var tracks = attack.bones().get(bone);
                if (tracks.position().defined()) p = hit.position();
                if (tracks.rotation().defined()) r = hit.rotation();
                if (tracks.scale().defined()) s = hit.scale();
            }
            if (bone.equals("head")) { r[0] += Math.clamp(pitch, -60, 60); r[1] += Math.clamp(yaw, -75, 75); }
            return new BedrockAnimation.Pose(p, r, s);
        }, seconds, null, 0, 0);
    }
    private static float[] blend(float[] a, float[] b, float weight) {
        return new float[]{a[0]+(b[0]-a[0])*weight,a[1]+(b[1]-a[1])*weight,a[2]+(b[2]-a[2])*weight};
    }
    private CharacterPose() { }
}
