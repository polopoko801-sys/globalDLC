package thunder.hack.features.cosmetics.render;

import com.google.gson.*;
import java.util.*;

/** Numeric Bedrock animation tracks with pre/post keyframes and Catmull-Rom interpolation. */
public final class BedrockAnimation {
    public record Pose(float[] position, float[] rotation, float[] scale) {
        public static final Pose IDENTITY = new Pose(new float[3],new float[3],new float[]{1,1,1});
    }
    private record Key(float time, AnimationVector pre, AnimationVector post, boolean catmull) {}
    public record Tracks(Channel position, Channel rotation, Channel scale) {}
    public record SoundCue(float time, String effect) {}
    public record Clip(String name, float duration, boolean loop, Map<String,Tracks> bones, List<SoundCue> soundCues) {
        public Clip {
            bones = Map.copyOf(bones);
            soundCues = soundCues.stream().sorted(Comparator.comparingDouble(SoundCue::time)).toList();
        }
        public Clip(String name, float duration, boolean loop, Map<String,Tracks> bones) {
            this(name, duration, loop, bones, List.of());
        }
        public Pose sample(String bone, float seconds) {
            Tracks tracks = bones.get(bone);
            if (tracks == null) return Pose.IDENTITY;
            float time = loop && duration > 0 ? Math.max(0,seconds) % duration : Math.min(Math.max(0,seconds),duration);
            return new Pose(tracks.position.sample(time), tracks.rotation.sample(time), tracks.scale.sample(time));
        }
    }
    public static final class Channel {
        private final List<Key> keys;
        private final float fallback;
        Channel(JsonElement json, float fallback) {
            this.fallback = fallback;
            List<Key> values = new ArrayList<>();
            if (json != null && json.isJsonObject()) {
                for (var entry : json.getAsJsonObject().entrySet()) {
                    try { values.add(key(Float.parseFloat(entry.getKey()),entry.getValue(),fallback)); }
                    catch (NumberFormatException ignored) { }
                }
            } else if (json != null) values.add(key(0,json,fallback));
            values.sort(Comparator.comparingDouble(Key::time));
            keys = List.copyOf(values);
        }
        public float[] sample(float time) {
            if (keys.isEmpty()) return new float[]{fallback,fallback,fallback};
            if (time < keys.getFirst().time()) return keys.getFirst().pre().sample(time);
            for (int i=1;i<keys.size();i++) if (time < keys.get(i).time()) {
                Key a=keys.get(i-1), b=keys.get(i);
                float t=(time-a.time())/(b.time()-a.time());
                float[] result=new float[3];
                for (int axis=0;axis<3;axis++) {
                    float p1=a.post().sample(axis,time), p2=b.pre().sample(axis,time);
                    if (a.catmull() || b.catmull()) {
                        float p0=keys.get(Math.max(0,i-2)).post().sample(axis,time), p3=keys.get(Math.min(keys.size()-1,i+1)).pre().sample(axis,time);
                        result[axis]=.5f*((2*p1)+(-p0+p2)*t+(2*p0-5*p1+4*p2-p3)*t*t+(-p0+3*p1-3*p2+p3)*t*t*t);
                    } else result[axis]=p1+(p2-p1)*t;
                }
                return result;
            }
            return keys.getLast().post().sample(time);
        }
        float end() { return keys.isEmpty()?0:keys.getLast().time(); }
        public boolean defined() { return !keys.isEmpty(); }
    }
    private static Key key(float time, JsonElement value, float fallback) {
        if (value.isJsonObject()) {
            JsonObject object=value.getAsJsonObject();
            JsonElement pre=object.has("pre")?object.get("pre"):object.has("post")?object.get("post"):object.get("vector");
            JsonElement post=object.has("post")?object.get("post"):pre;
            return new Key(time,new AnimationVector(pre,fallback),new AnimationVector(post,fallback),
                "catmullrom".equals(BedrockModel.string(object,"lerp_mode","linear")));
        }
        AnimationVector vector=new AnimationVector(value,fallback);
        return new Key(time,vector,vector,false);
    }
    public static Map<String,Clip> parse(JsonObject root) {
        JsonObject animations=root.has("animations")?root.getAsJsonObject("animations"):root;
        Map<String,Clip> result=new LinkedHashMap<>();
        for (var entry:animations.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject json=entry.getValue().getAsJsonObject();
            if (!json.has("bones") && !json.has("sound_effects") && !json.has("animation_length") && !json.has("loop")) continue;
            Map<String,Tracks> bones=new HashMap<>(); float duration=0;
            if (json.has("bones")) for (var bone:json.getAsJsonObject("bones").entrySet()) {
                JsonObject tracks=bone.getValue().getAsJsonObject();
                Channel p=new Channel(tracks.get("position"),0),r=new Channel(tracks.get("rotation"),0),s=new Channel(tracks.get("scale"),1);
                duration=Math.max(duration,Math.max(p.end(),Math.max(r.end(),s.end())));
                bones.put(bone.getKey(),new Tracks(p,r,s));
            }
            List<SoundCue> sounds = new ArrayList<>();
            if (json.has("sound_effects") && json.get("sound_effects").isJsonObject()) {
                for (var cue : json.getAsJsonObject("sound_effects").entrySet()) {
                    try {
                        float time = Float.parseFloat(cue.getKey());
                        if (!Float.isFinite(time) || time < 0) continue;
                        addSounds(sounds, time, cue.getValue());
                    } catch (NumberFormatException ignored) { }
                }
            }
            for (SoundCue sound : sounds) duration = Math.max(duration, sound.time());
            duration=BedrockModel.number(json,"animation_length",duration);
            result.put(entry.getKey(),new Clip(entry.getKey(),Math.max(.001f,duration),BedrockModel.bool(json,"loop",false),bones,sounds));
        }
        return Collections.unmodifiableMap(result);
    }
    private static void addSounds(List<SoundCue> sounds, float time, JsonElement value) {
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) addSounds(sounds, time, element);
        } else if (value.isJsonObject()) {
            JsonElement effect = value.getAsJsonObject().get("effect");
            if (effect != null && effect.isJsonPrimitive() && effect.getAsJsonPrimitive().isString()
                && !effect.getAsString().isBlank()) sounds.add(new SoundCue(time, effect.getAsString()));
        }
    }
}
