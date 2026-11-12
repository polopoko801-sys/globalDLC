package thunder.hack.features.cosmetics.render;

import com.google.gson.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/** Immutable geometry data. Coordinates remain in Bedrock pixels until emitted. */
public final class BedrockModel {
    public record Face(float[] vertices, float[] uv, float nx, float ny, float nz) {}
    public record Cube(float[] pivot, float[] rotation, List<Face> faces) {}
    public record Bone(String name, String parent, float[] pivot, float[] rotation, List<Cube> cubes) {}
    public final List<Bone> bones;
    public final int textureWidth, textureHeight;
    private final Map<String, Integer> indices;

    private BedrockModel(List<Bone> bones, int width, int height) {
        this.bones = List.copyOf(bones);
        textureWidth = width;
        textureHeight = height;
        indices = new HashMap<>();
        for (int i = 0; i < bones.size(); i++) indices.put(bones.get(i).name(), i);
        for (Bone bone : bones) {
            Set<String> visited = new HashSet<>();
            Bone next = bone;
            while (next != null) {
                if (!visited.add(next.name())) throw new IllegalArgumentException("Cyclic cosmetic bone: " + bone.name());
                Integer parent = indices.get(next.parent());
                next = parent == null ? null : bones.get(parent);
            }
        }
    }

    public static BedrockModel parse(JsonObject json) {
        JsonObject geometry = json.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        int width = Math.max(1, (int) number(description, "texture_width", 64));
        int height = Math.max(1, (int) number(description, "texture_height", 64));
        List<Bone> bones = new ArrayList<>();
        for (JsonElement element : geometry.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            List<Cube> cubes = new ArrayList<>();
            boolean mirror = bool(bone, "mirror", false);
            if (bone.has("cubes")) for (JsonElement cube : bone.getAsJsonArray("cubes"))
                cubes.add(cube(cube.getAsJsonObject(), width, height, mirror, number(bone, "inflate", 0)));
            bones.add(new Bone(bone.get("name").getAsString(), string(bone, "parent", null),
                vector(bone.get("pivot"), 0), vector(bone.get("rotation"), 0), List.copyOf(cubes)));
        }
        return new BedrockModel(bones, width, height);
    }

    private static Cube cube(JsonObject json, int width, int height, boolean inheritedMirror, float inflate) {
        float[] origin = vector(json.get("origin"), 0), size = vector(json.get("size"), 0);
        inflate = number(json, "inflate", inflate);
        boolean mirror = bool(json, "mirror", inheritedMirror);
        float x0 = origin[0] - inflate, y0 = origin[1] - inflate, z0 = origin[2] - inflate;
        float x1 = origin[0] + size[0] + inflate, y1 = origin[1] + size[1] + inflate, z1 = origin[2] + size[2] + inflate;
        // Each face is ordered around its exterior; the renderer uses a no-cull layer for flat wing membranes.
        float[][] positions = {
            {x1,y1,z0, x0,y1,z0, x0,y0,z0, x1,y0,z0},
            {x0,y1,z1, x1,y1,z1, x1,y0,z1, x0,y0,z1},
            {x1,y1,z1, x1,y1,z0, x1,y0,z0, x1,y0,z1},
            {x0,y1,z0, x0,y1,z1, x0,y0,z1, x0,y0,z0},
            {x1,y1,z0, x0,y1,z0, x0,y1,z1, x1,y1,z1},
            {x1,y0,z1, x0,y0,z1, x0,y0,z0, x1,y0,z0}
        };
        String[] names = {"north", "south", "west", "east", "up", "down"};
        float[][] normals = {{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
        JsonElement uv = json.get("uv");
        float u = uv != null && uv.isJsonArray() ? uv.getAsJsonArray().get(0).getAsFloat() : 0;
        float v = uv != null && uv.isJsonArray() ? uv.getAsJsonArray().get(1).getAsFloat() : 0;
        float w = size[0], h = size[1], d = size[2];
        float[][] box = {{u+d,v+d,w,h},{u+2*d+w,v+d,w,h},{u,v+d,d,h},{u+d+w,v+d,d,h},{u+d,v,w,d},{u+d+w,v,w,d}};
        List<Face> faces = new ArrayList<>();
        for (int f = 0; f < 6; f++) {
            float[] rect = box[mirror && f==2 ? 3 : mirror && f==3 ? 2 : f];
            int rotation = 0;
            if (uv != null && uv.isJsonObject()) {
                JsonObject face = uv.getAsJsonObject().getAsJsonObject(names[f]);
                if (face == null) continue;
                float[] start = vector(face.get("uv"), 0);
                float[] extent = face.has("uv_size") ? vector(face.get("uv_size"), 0) : new float[]{rect[2],rect[3],0};
                rect = new float[]{start[0],start[1],extent[0],extent[1]};
                rotation = Math.floorMod((int) number(face, "uv_rotation", 0) / 90, 4);
            }
            float left = rect[0] / width, right = (rect[0] + rect[2]) / width;
            float top = rect[1] / height, bottom = (rect[1] + rect[3]) / height;
            if (mirror && (uv==null || !uv.isJsonObject())) { float swap = left; left = right; right = swap; }
            float[] corners = {left,top,right,top,right,bottom,left,bottom};
            float[] mapped = new float[8];
            for (int c = 0; c < 4; c++) {
                int source = ((c + rotation) % 4) * 2;
                mapped[c*2] = corners[source]; mapped[c*2+1] = corners[source+1];
            }
            // Match outward normals after the renderer converts Bedrock's X axis.
            if(f>=4){
                for(int axis=0;axis<3;axis++){float swap=positions[f][3+axis];positions[f][3+axis]=positions[f][9+axis];positions[f][9+axis]=swap;}
                for(int axis=0;axis<2;axis++){float swap=mapped[2+axis];mapped[2+axis]=mapped[6+axis];mapped[6+axis]=swap;}
            }
            faces.add(new Face(positions[f], mapped, normals[f][0], normals[f][1], normals[f][2]));
        }
        return new Cube(json.has("pivot") ? vector(json.get("pivot"), 0) : origin,
            vector(json.get("rotation"), 0), List.copyOf(faces));
    }

    public Matrix4f[] pose(BedrockAnimation.Clip clip, float seconds, String procedural) {
        return pose(name -> clip==null?BedrockAnimation.Pose.IDENTITY:clip.sample(name,seconds),seconds,procedural,0,0);
    }
    public Matrix4f[] pose(java.util.function.Function<String,BedrockAnimation.Pose> sampler,float seconds,String procedural,float flight,float elytra) {
        Matrix4f[] transforms=new Matrix4f[bones.size()];
        for(int i=0;i<bones.size();i++)pose(i,sampler,seconds,procedural,flight,elytra,transforms);
        return transforms;
    }
    private Matrix4f pose(int index,java.util.function.Function<String,BedrockAnimation.Pose> sampler,float seconds,String procedural,float flight,float elytra,Matrix4f[] transforms) {
        if(transforms[index]!=null)return transforms[index];
        Bone bone=bones.get(index);Integer parent=indices.get(bone.parent());
        Matrix4f matrix=parent==null?new Matrix4f():new Matrix4f(pose(parent,sampler,seconds,procedural,flight,elytra,transforms));
        BedrockAnimation.Pose animated=sampler.apply(bone.name());
        BedrockAnimation.Pose extra=procedural==null?BedrockAnimation.Pose.IDENTITY:ProceduralWingPose.sample(procedural,bone.name(),seconds,parent==null,flight,elytra);
        float[] r=bone.rotation().clone(),p=new float[3],scale=new float[3];
        for(int i=0;i<3;i++){r[i]+=animated.rotation()[i]+extra.rotation()[i];p[i]=animated.position()[i]+extra.position()[i];scale[i]=animated.scale()[i]*extra.scale()[i];}
        matrix.translate(-p[0]/16,p[1]/16,p[2]/16);transform(matrix,bone.pivot(),r,scale);
        return transforms[index]=matrix;
    }

    public static void transform(Matrix4f matrix, float[] pivot, float[] rotation, float[] scale) {
        float x = -pivot[0] / 16, y = pivot[1] / 16, z = pivot[2] / 16;
        matrix.translate(x,y,z).rotateZ((float)Math.toRadians(rotation[2]))
            .rotateY((float)Math.toRadians(-rotation[1])).rotateX((float)Math.toRadians(-rotation[0]));
        if (scale != null) matrix.scale(scale[0],scale[1],scale[2]);
        matrix.translate(-x,-y,-z);
    }

    public float[] bounds(Matrix4f[] pose, Set<String> hidden) {
        float[] bounds = {Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        Vector3f point = new Vector3f();
        for (int i = 0; i < bones.size(); i++) {
            if (hidden(bones.get(i), hidden)) continue;
            for (Cube cube : bones.get(i).cubes()) {
                Matrix4f matrix = new Matrix4f(pose[i]); transform(matrix,cube.pivot(),cube.rotation(),null);
                for (Face face : cube.faces()) for (int v = 0; v < 12; v += 3) {
                    matrix.transformPosition(-face.vertices()[v]/16,face.vertices()[v+1]/16,face.vertices()[v+2]/16,point);
                    bounds[0]=Math.min(bounds[0],point.x); bounds[1]=Math.min(bounds[1],point.y); bounds[2]=Math.min(bounds[2],point.z);
                    bounds[3]=Math.max(bounds[3],point.x); bounds[4]=Math.max(bounds[4],point.y); bounds[5]=Math.max(bounds[5],point.z);
                }
            }
        }
        return Float.isFinite(bounds[0]) ? bounds : new float[]{-.5f,-.5f,-.5f,.5f,.5f,.5f};
    }

    public boolean hidden(Bone bone, Set<String> hidden) {
        while (bone != null) {
            if (hidden.contains(bone.name())) return true;
            Integer parent = indices.get(bone.parent()); bone = parent == null ? null : bones.get(parent);
        }
        return false;
    }

    static float[] vector(JsonElement element, float fallback) {
        float[] result = {fallback,fallback,fallback};
        if (element == null || element.isJsonNull()) return result;
        if (element.isJsonPrimitive()) { Arrays.fill(result, scalar(element, fallback)); return result; }
        if (element.isJsonArray()) for (int i=0;i<Math.min(3,element.getAsJsonArray().size());i++) result[i]=scalar(element.getAsJsonArray().get(i),fallback);
        return result;
    }
    static float scalar(JsonElement element, float fallback) {
        try { float result = element.getAsFloat(); return Float.isFinite(result) ? result : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    static float number(JsonObject object, String name, float fallback) { return object == null || !object.has(name) ? fallback : scalar(object.get(name),fallback); }
    static String string(JsonObject object, String name, String fallback) { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback; }
    static boolean bool(JsonObject object, String name, boolean fallback) { return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsBoolean() : fallback; }
}
