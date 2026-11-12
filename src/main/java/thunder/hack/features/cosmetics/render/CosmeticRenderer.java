package thunder.hack.features.cosmetics.render;

import com.google.gson.*;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;
import thunder.hack.features.cosmetics.*;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bedrock cosmetic geometry: loading, posing and emitting.
 *
 * <p>Ported from BKClient (MIT, bkubiakdev). Loading, posing, the vertex math and the preview framing are BK's
 * code unchanged. What differs is 1.21.11 plumbing:
 * <ul>
 *   <li>vertices go to a {@link CosmeticSink} instead of a {@code VertexConsumerProvider};</li>
 *   <li>layers come from {@code RenderLayers} (1.21.11 moved the factories off {@code RenderLayer});</li>
 *   <li>BK's custom {@code cosmetics_emissive}/{@code cosmetics_holy} core shaders used the 1.21.4 shader-json
 *       system, which 1.21.11 removed - those bones use vanilla's emissive translucent layer instead;</li>
 *   <li>the GPU-uploaded melee preview cache and the raw GL state juggling are gone: 1.21.11 GUI previews render
 *       into their own off-screen target, so neither is needed.</li>
 * </ul>
 */
public final class CosmeticRenderer {
    private record Asset(BedrockModel model, Map<String,BedrockAnimation.Clip> animations, Identifier texture, int frames) {}
    private record PreviewPose(Matrix4f orientation,float[] bounds,List<PreviewPart> parts) {}
    /** One bone of a static preview, already posed: 4 vertices per quad as x,y,z,u,v,nx,ny,nz plus each quad's cube determinant. */
    private record PreviewPart(RenderLayer layer,boolean emissive,float[] vertices,float[] determinants) {}
    private record Emission(Set<String> bones, int color, float strength) {
        private static final Emission NONE = new Emission(Set.of(), 0xFFFFFF, 0);
    }
    private static final Map<String,Asset> ASSETS = new HashMap<>();
    private static final Map<String,PreviewPose> MELEE_PREVIEWS = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<String,JsonObject> MELEE = new HashMap<>();
    private static final Map<String,Emission> EMISSIONS = new HashMap<>();
    private static boolean metadataLoaded;
    static {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("gombal", "cosmetics"); }
            @Override public void reload(ResourceManager manager) { ASSETS.clear(); FAILED.clear(); MELEE.clear(); MELEE_PREVIEWS.clear(); EMISSIONS.clear(); metadataLoaded=false; MeleeCosmeticRenderer.reset(); CharacterCosmeticRenderer.reset(); }
        });
    }
    private CosmeticRenderer() {}

    /** Forces the class (and so its reload listener) to load during client init, before the first reload. */
    public static void init() {}

    private static JsonObject json(String path) throws IOException {
        try (Reader reader = new InputStreamReader(MinecraftClient.getInstance().getResourceManager()
                .getResourceOrThrow(Identifier.of("bkclient",path)).getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static Asset asset(CosmeticEntry entry) {
        if (entry==null) return null;
        String key=entry.model()+"|"+entry.texture()+"|"+entry.animation();
        if (FAILED.contains(key)) return null;
        Asset known=ASSETS.get(key); if (known!=null) return known;
        try {
            BedrockModel model=BedrockModel.parse(json(entry.model()));
            Map<String,BedrockAnimation.Clip> animations=entry.animation()==null?Map.of():BedrockAnimation.parse(json(entry.animation()));
            Identifier texture=Identifier.of("bkclient",entry.texture());
            int frames=1;
            if (entry.slot()==CosmeticSlot.WINGS && entry.id().equals("wota")) {
                try (InputStream stream=MinecraftClient.getInstance().getResourceManager().getResourceOrThrow(texture).getInputStream()) {
                    var image=ImageIO.read(stream); if (image!=null) frames=Math.max(1,image.getHeight()/64);
                }
            }
            known=new Asset(model,animations,texture,frames); ASSETS.put(key,known); return known;
        } catch (Exception exception) {
            FAILED.add(key); LoggerFactory.getLogger("GlobalDLC/Cosmetics").warn("Cannot load cosmetic {}",entry.id(),exception); return null;
        }
    }
    public static BedrockModel model(CosmeticEntry entry){Asset a=asset(entry);return a==null?null:a.model();}
    public static Map<String,BedrockAnimation.Clip> animations(CosmeticEntry entry){Asset a=asset(entry);return a==null?Map.of():a.animations();}
    public static boolean available(CosmeticEntry entry) { return asset(entry)!=null; }
    public static BedrockAnimation.Clip clip(CosmeticEntry entry, String... names) {
        Asset asset=asset(entry); if (asset==null) return null;
        for (String name:names) {
            var exact=asset.animations().get(name); if (exact!=null) return exact;
            for (var candidate:asset.animations().entrySet()) if (candidate.getKey().endsWith("."+name)) return candidate.getValue();
        }
        return null;
    }
    public static BedrockAnimation.Clip idle(CosmeticEntry entry) {
        var idle=clip(entry,"static_idle","idle","animation","sway");
        Asset asset=asset(entry);
        return idle!=null?idle:asset==null||asset.animations().isEmpty()?null:asset.animations().values().iterator().next();
    }
    private static Set<String> hidden(CosmeticEntry entry) {
        Set<String> result=new HashSet<>();
        if (entry.slot()==CosmeticSlot.MELEE) {
            // These are attachment guides for the original animated player arms, never weapon geometry.
            result.addAll(List.of("righthand","lefthand","righthand_pos","lefthand_pos"));
            JsonObject metadata=metadata(entry);
            if (metadata!=null && metadata.has("variants") && !metadata.getAsJsonArray("variants").isEmpty()) {
                JsonObject variant=metadata.getAsJsonArray("variants").get(0).getAsJsonObject();
                if (variant.has("hidden")) for (JsonElement name:variant.getAsJsonArray("hidden")) result.add(name.getAsString());
            }
        }
        return result;
    }
    static JsonObject metadata(CosmeticEntry entry) {
        if (!metadataLoaded) {
            metadataLoaded=true;
            try { for (JsonElement item:json("cosmetics/melee/index.json").getAsJsonArray("melee")) {
                JsonObject object=item.getAsJsonObject(); MELEE.put(object.get("key").getAsString(),object);
            }} catch (Exception exception) { LoggerFactory.getLogger("GlobalDLC/Cosmetics").warn("Cannot read melee transforms",exception); }
        }
        return MELEE.get(entry.id());
    }
    private static Emission emission(CosmeticEntry entry) {
        if (entry == null || entry.slot() != CosmeticSlot.MELEE) return Emission.NONE;
        return EMISSIONS.computeIfAbsent(entry.id(), key -> {
            JsonObject meta = metadata(entry);
            if (meta == null || !meta.has("emissionBones")) return Emission.NONE;
            Set<String> bones = new HashSet<>();
            for (JsonElement bone : meta.getAsJsonArray("emissionBones")) bones.add(bone.getAsString());
            if (bones.isEmpty()) return Emission.NONE;
            float[] tint = BedrockModel.vector(meta.get("emissionColor"), 1);
            int color = emissionChannel(tint[0]) << 16 | emissionChannel(tint[1]) << 8 | emissionChannel(tint[2]);
            float strength = meta.has("emissionStrength") ? meta.get("emissionStrength").getAsFloat() : 1.2f;
            strength = Float.isFinite(strength) ? Math.clamp(strength, 0, 4) : 1.2f;
            return new Emission(Set.copyOf(bones), color, strength);
        });
    }
    private static int emissionChannel(float value) {
        return Math.round((Float.isFinite(value) ? Math.clamp(value, 0, 1) : 1) * 255);
    }
    static boolean hasEmission(CosmeticEntry entry) { return !emission(entry).bones().isEmpty(); }
    static float emissionStrength(CosmeticEntry entry) {
        return emission(entry).strength();
    }
    public static Matrix4f firstPersonAnchor(CosmeticEntry entry, BedrockAnimation.Clip clip, float seconds) {
        return firstPersonAnchor(entry,clip==null?BedrockAnimation.Pose.IDENTITY:clip.sample("camera",seconds));
    }
    public static Matrix4f firstPersonAnchor(CosmeticEntry entry,BedrockAnimation.Pose camera) {
        Matrix4f matrix=new Matrix4f();Asset asset=asset(entry);if(asset==null)return matrix;
        BedrockModel.Bone anchor=null;
        for (var bone:asset.model().bones) if (bone.name().equals("idle_view")) { anchor=bone; break; }
        if (anchor==null) for (var bone:asset.model().bones) if (bone.name().equals("camera")) { anchor=bone; break; }
        {
            float[] p=camera.position(),r=camera.rotation();
            matrix.translate(p[0]/16,-p[1]/16,-p[2]/16).rotateZ((float)java.lang.Math.toRadians(-r[2]))
                .rotateY((float)java.lang.Math.toRadians(r[1])).rotateX((float)java.lang.Math.toRadians(r[0]));
        }
        if (anchor!=null) matrix.translate(anchor.pivot()[0]/16,-anchor.pivot()[1]/16,-anchor.pivot()[2]/16);
        return matrix;
    }
    static Matrix4f thirdPersonTransform(CosmeticEntry entry, boolean left) {
        Matrix4f matrix=new Matrix4f(); JsonObject meta=metadata(entry);
        if (meta!=null && meta.has("transforms")) {
            JsonObject transform=meta.getAsJsonObject("transforms").getAsJsonObject(left?"thirdperson_lefthand":"thirdperson_righthand");
            if (transform!=null) {
                float[] p=BedrockModel.vector(transform.get("translation"),0),r=BedrockModel.vector(transform.get("rotation"),0),s=BedrockModel.vector(transform.get("scale"),1);
                matrix.translate(p[0]/16,p[1]/16,p[2]/16).rotateX((float)java.lang.Math.toRadians(r[0]))
                    .rotateY((float)java.lang.Math.toRadians(r[1])).rotateZ((float)java.lang.Math.toRadians(r[2])).scale(s[0],s[1],s[2]);
            }
        }
        matrix.rotateZ((float)java.lang.Math.PI);
        Asset asset=asset(entry);
        if (asset!=null) for (var bone:asset.model().bones) if (bone.name().equals("thirdperson_hand")) {
            matrix.translate(bone.pivot()[0]/16,-bone.pivot()[1]/16,-bone.pivot()[2]/16); break;
        }
        return matrix;
    }
    public static void render(CosmeticEntry entry, Matrix4f transform, CosmeticSink consumers,
                              int light, BedrockAnimation.Clip clip, float seconds, float alpha) {
        Asset asset=asset(entry); if (asset==null) return;
        String procedural=entry.slot()==CosmeticSlot.WINGS && entry.animation()==null?entry.model():null;
        Matrix4f[] pose=asset.model().pose(clip,seconds,procedural);
        emit(entry,asset,transform,consumers,light,pose,seconds,alpha,null,false);
    }
    public static void renderPose(CosmeticEntry entry,Matrix4f transform,CosmeticSink consumers,int light,Matrix4f[] pose,float seconds,Set<String> hidden) {
        Asset a=asset(entry);if(a!=null)emit(entry,a,transform,consumers,light,pose,seconds,1,hidden,false);
    }
    /** Draw only authored emissive weapon geometry using the already sampled first-person pose. */
    public static void renderEmissionPose(CosmeticEntry entry, Matrix4f transform, CosmeticSink consumers,
                                         Matrix4f[] pose, Set<String> hidden) {
        Asset a=asset(entry);
        if(a!=null && hasEmission(entry))emit(entry,a,transform,consumers,LightmapTextureManager.MAX_LIGHT_COORDINATE,pose,0,1,hidden,true);
    }
    private static void emit(CosmeticEntry entry, Asset asset, Matrix4f transform, CosmeticSink consumers,
                             int light, Matrix4f[] pose, float seconds, float alpha,Set<String> extraHidden,boolean emissionOnly) {
        emit(entry,asset,transform,consumers,light,pose,seconds,alpha,extraHidden,emissionOnly,OverlayTexture.DEFAULT_UV,0xFFFFFF);
    }
    public static void renderCharacter(CosmeticEntry entry, Matrix4f transform, CosmeticSink consumers,
            int light, int overlay, int color, Matrix4f[] pose, float seconds) {
        Asset asset=asset(entry);
        if(asset!=null)emit(entry,asset,transform,consumers,light,pose,seconds,((color>>>24)&255)/255f,null,false,overlay,color&0xFFFFFF);
    }
    private static RenderLayer normalLayer(CosmeticEntry entry, Identifier texture) {
        // Melee has separately textured front/back faces, including zero-thickness blades.
        // Culling prevents coplanar sides fighting over the same depth value.
        return entry.slot()==CosmeticSlot.MELEE?RenderLayers.itemEntityTranslucentCull(texture):RenderLayers.entityTranslucent(texture);
    }
    private static RenderLayer glowLayer(Identifier texture) {
        return RenderLayers.entityTranslucentEmissive(texture);
    }
    private static void emit(CosmeticEntry entry, Asset asset, Matrix4f transform, CosmeticSink consumers,
                             int light, Matrix4f[] pose, float seconds, float alpha,Set<String> extraHidden,boolean emissionOnly,int overlay,int tint) {
        RenderLayer normalLayer=normalLayer(entry,asset.texture());
        Set<String> holy=new HashSet<>();
        if(entry.slot()==CosmeticSlot.MELEE){var meta=metadata(entry);if(meta!=null&&meta.has("holyBones"))for(var b:meta.getAsJsonArray("holyBones"))holy.add(b.getAsString());}
        RenderLayer holyLayer=holy.isEmpty()?normalLayer:glowLayer(asset.texture());
        Emission emission=emission(entry);
        Set<String> hidden=extraHidden==null?hidden(entry):new HashSet<>(extraHidden);
        if(extraHidden!=null)hidden.addAll(List.of("righthand","lefthand","righthand_pos","lefthand_pos"));
        Vector3f normal=new Vector3f();
        int frame=Math.floorMod((int)(seconds/.15f),asset.frames());
        int color=(Math.max(0,Math.min(255,(int)(alpha*255)))<<24)|tint;
        for (int i=0;i<asset.model().bones.size();i++) {
            var bone=asset.model().bones.get(i); if (asset.model().hidden(bone,hidden)) continue;
            boolean emissive=asset.model().hidden(bone,emission.bones());
            if(emissionOnly&&!emissive)continue;
            VertexConsumer vertices=consumers.buffer(emissive?glowLayer(asset.texture()):asset.model().hidden(bone,holy)?holyLayer:normalLayer);
            int boneColor=emissive?(color&0xFF000000)|emission.color():color;
            int boneLight=emissive?LightmapTextureManager.MAX_LIGHT_COORDINATE:light;
            for (var cube:bone.cubes()) {
                Matrix4f matrix=new Matrix4f(transform).mul(pose[i]); BedrockModel.transform(matrix,cube.pivot(),cube.rotation(),null);
                float determinant=matrix.determinant();
                if (Math.abs(determinant)<1e-12f) continue;
                Matrix3f normals=matrix.normal(new Matrix3f());
                for (var face:cube.faces()) {
                    normals.transform(-face.nx(),face.ny(),face.nz(),normal).normalize();
                    for (int corner=0;corner<4;corner++) {
                        int ordered=determinant<0?3-corner:corner;
                        int p=ordered*3,t=ordered*2;
                        vertices.vertex(matrix,-face.vertices()[p]/16,face.vertices()[p+1]/16,face.vertices()[p+2]/16)
                            .color(boneColor).texture(face.uv()[t],(face.uv()[t+1]+frame)/asset.frames())
                            .overlay(overlay).light(boneLight).normal(normal.x,normal.y,normal.z);
                    }
                }
            }
        }
    }
    // Melee previews have a static pose, so the posed geometry is built once instead of per card per frame.
    private static List<PreviewPart> bake(CosmeticEntry entry,Asset asset,Matrix4f[] pose,Set<String> hidden) {
        RenderLayer normalLayer=RenderLayers.itemEntityTranslucentCull(asset.texture());
        Set<String> holy=new HashSet<>();
        var meta=metadata(entry);if(meta!=null&&meta.has("holyBones"))for(var b:meta.getAsJsonArray("holyBones"))holy.add(b.getAsString());
        RenderLayer holyLayer=holy.isEmpty()?normalLayer:glowLayer(asset.texture());
        Emission emission=emission(entry);
        List<PreviewPart> parts=new ArrayList<>();
        Vector3f normal=new Vector3f(),point=new Vector3f();
        for (int i=0;i<asset.model().bones.size();i++) {
            var bone=asset.model().bones.get(i); if (asset.model().hidden(bone,hidden)) continue;
            boolean emissive=asset.model().hidden(bone,emission.bones());
            int quads=0; for (var cube:bone.cubes()) quads+=cube.faces().size();
            float[] vertices=new float[quads*32],determinants=new float[quads]; int q=0;
            for (var cube:bone.cubes()) {
                Matrix4f matrix=new Matrix4f(pose[i]); BedrockModel.transform(matrix,cube.pivot(),cube.rotation(),null);
                float determinant=matrix.determinant();
                Matrix3f normals=matrix.normal(new Matrix3f());
                for (var face:cube.faces()) {
                    normals.transform(-face.nx(),face.ny(),face.nz(),normal).normalize();
                    determinants[q]=determinant;
                    for (int corner=0;corner<4;corner++) {
                        int p=corner*3,t=corner*2,o=(q*4+corner)*8;
                        matrix.transformPosition(-face.vertices()[p]/16,face.vertices()[p+1]/16,face.vertices()[p+2]/16,point);
                        vertices[o]=point.x;vertices[o+1]=point.y;vertices[o+2]=point.z;vertices[o+3]=face.uv()[t];vertices[o+4]=face.uv()[t+1];
                        vertices[o+5]=normal.x;vertices[o+6]=normal.y;vertices[o+7]=normal.z;
                    }
                    q++;
                }
            }
            if (quads>0) parts.add(new PreviewPart(emissive?glowLayer(asset.texture()):asset.model().hidden(bone,holy)?holyLayer:normalLayer,emissive,vertices,determinants));
        }
        return List.copyOf(parts);
    }
    /** Same vertices, order and layers as {@link #emit} for the baked pose; only the card transform is applied per frame. */
    private static void emitBaked(CosmeticEntry entry,Asset asset,List<PreviewPart> parts,Matrix4f transform,CosmeticSink consumers,float seconds,float alpha) {
        Emission emission=emission(entry);
        float outer=transform.determinant();
        Matrix3f normals=transform.normal(new Matrix3f());
        Vector3f normal=new Vector3f();
        int frame=Math.floorMod((int)(seconds/.15f),asset.frames());
        int color=(Math.max(0,Math.min(255,(int)(alpha*255)))<<24)|0xFFFFFF;
        for (var part:parts) {
            VertexConsumer vertices=consumers.buffer(part.layer());
            int boneColor=part.emissive()?(color&0xFF000000)|emission.color():color;
            float[] data=part.vertices();
            for (int q=0;q<part.determinants().length;q++) {
                float determinant=outer*part.determinants()[q];
                if (Math.abs(determinant)<1e-12f) continue;
                for (int corner=0;corner<4;corner++) {
                    int o=(q*4+(determinant<0?3-corner:corner))*8;
                    normals.transform(data[o+5],data[o+6],data[o+7],normal).normalize();
                    vertices.vertex(transform,data[o],data[o+1],data[o+2])
                        .color(boneColor).texture(data[o+3],(data[o+4]+frame)/asset.frames())
                        .overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(normal.x,normal.y,normal.z);
                }
            }
        }
    }
    private static float[] orientedBounds(BedrockModel model,Matrix4f[] pose,Set<String> hidden,Matrix4f orientation) {
        Matrix4f[] rotated=new Matrix4f[pose.length];
        for (int i=0;i<pose.length;i++) rotated[i]=new Matrix4f(orientation).mul(pose[i]);
        return model.bounds(rotated,hidden);
    }
    private static PreviewPose meleePreview(CosmeticEntry entry,Asset asset,Matrix4f[] pose) {
        return MELEE_PREVIEWS.computeIfAbsent(entry.id(),key->{
            Set<String> hidden=hidden(entry);
            float[] bounds=asset.model().bounds(pose,hidden);
            float dx=bounds[3]-bounds[0],dy=bounds[4]-bounds[1],dz=bounds[5]-bounds[2];
            // Face the blade's broad plane. Most imported knives are authored along Z with their thin axis on X.
            Matrix4f facing=new Matrix4f();
            if (dx<=dy && dx<=dz) facing.rotateY((float)Math.PI/2);
            else if (dy<=dx && dy<=dz) facing.rotateX((float)Math.PI/2);
            bounds=orientedBounds(asset.model(),pose,hidden,facing);
            float tilt=bounds[4]-bounds[1]>bounds[3]-bounds[0]?-65:-15;
            Matrix4f orientation=new Matrix4f().rotateZ((float)Math.toRadians(tilt)).rotateY(.08f).mul(facing);
            Matrix4f[] oriented=new Matrix4f[pose.length];
            for (int i=0;i<pose.length;i++) oriented[i]=new Matrix4f(orientation).mul(pose[i]);
            return new PreviewPose(orientation,asset.model().bounds(oriented,hidden),bake(entry,asset,oriented,hidden));
        });
    }
    /**
     * Actual geometry preview, framed inside the given box of {@code transform}'s space (GUI units, Y down).
     * The caller owns the buffer and draws it.
     */
    public static void renderPreview(CosmeticEntry entry, Matrix4f transform, float x,float y,float width,float height,float alpha,CosmeticSink sink) {
        if (entry==null || FAILED.contains("preview:"+entry.id())) return;
        Asset asset=asset(entry); if (asset==null || width<=0 || height<=0) return;
        try {
            float seconds=(System.nanoTime()%3600000000000L)/1_000_000_000f;
            if (entry.slot()==CosmeticSlot.MELEE) {
                PreviewPose preview=MELEE_PREVIEWS.get(entry.id());
                if (preview==null) preview=meleePreview(entry,asset,asset.model().pose(null,seconds,null));
                float[] bounds=preview.bounds();
                float scale=.86f*Math.min(width/Math.max(.01f,bounds[3]-bounds[0]),height/Math.max(.01f,bounds[4]-bounds[1]));
                // The orientation is already part of the baked vertices.
                Matrix4f matrix=new Matrix4f(transform).translate(x+width/2,y+height/2,0).scale(scale,-scale,scale)
                    .translate(-(bounds[0]+bounds[3])/2,-(bounds[1]+bounds[4])/2,-(bounds[2]+bounds[5])/2);
                emitBaked(entry,asset,preview.parts(),matrix,sink,seconds,alpha);
            } else {
                var clip=idle(entry);
                Matrix4f[] pose=asset.model().pose(clip,seconds,entry.slot()==CosmeticSlot.WINGS&&entry.animation()==null?entry.model():null);
                float[] bounds=asset.model().bounds(pose,hidden(entry));
                float span=Math.max(bounds[3]-bounds[0],Math.max(bounds[4]-bounds[1],bounds[5]-bounds[2]));
                float scale=Math.min(width,height)*.70f/Math.max(.1f,span);
                Matrix4f matrix=new Matrix4f(transform).translate(x+width/2,y+height/2,0)
                    .scale(scale,-scale,scale).rotateX(.12f)
                    .rotateY(entry.slot()==CosmeticSlot.CHARACTER?(float)Math.PI:.45f+(float)java.lang.Math.sin(seconds*.45f)*.28f)
                    .translate(-(bounds[0]+bounds[3])/2,-(bounds[1]+bounds[4])/2,-(bounds[2]+bounds[5])/2);
                emit(entry,asset,matrix,sink,LightmapTextureManager.MAX_LIGHT_COORDINATE,pose,seconds,alpha,null,false);
            }
        } catch (Exception exception) {
            if (FAILED.add("preview:"+entry.id())) LoggerFactory.getLogger("GlobalDLC/Cosmetics").warn("Cannot preview {}",entry.id(),exception);
        }
    }
}
