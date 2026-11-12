package thunder.hack.features.cosmetics.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.*;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.cosmetics.*;

import java.util.*;

/**
 * Melee weapon skins: animated first-person rig with the player's own arms, plus the third-person model.
 * Cosmetic-only state; never changes inventory, interaction timing or packets.
 *
 * <p>Ported from BKClient (MIT, bkubiakdev). Changes for 1.21.11: swords are a tag (SwordItem is gone), the
 * selected slot is a getter, items render with ItemDisplayContext through the command queue, and BK's
 * CosmeticGlowRenderer bloom pass (raw GL on 1.21.4) is not ported - emissive bones still render fullbright.
 */
public final class MeleeCosmeticRenderer {
    private record Variant(String suffix,Set<String> hidden) {}
    private static final class State {
        CosmeticEntry entry;BedrockModel model;MeleeAnimationController animation;
        Object world;Item item;int slot;float distance;boolean rendered;
        final CosmeticAnimationClock clock=new CosmeticAnimationClock();
        long advancedFrame=Long.MIN_VALUE;
        Variant variant;
        void stop(){if(animation!=null)animation.close();animation=null;entry=null;world=null;clock.reset();advancedFrame=Long.MIN_VALUE;}
    }
    private static final State[] STATES={new State(),new State()};
    private static final Map<String,Variant> VARIANTS=new HashMap<>();
    private static String variantSelection;
    private static boolean keyDown;
    private static State primary;
    private static Matrix4f primaryBase;
    private static Matrix4f[] primaryPose;
    private static boolean primaryMirrored;
    private static ItemStack deferred;
    private static float deferredEquip;
    private static long visualFrame;
    static {
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(equipped()==null){VARIANTS.clear();variantSelection=null;}
            if(!activeView() || client.player.isUsingItem() || (!eligible(client.player.getMainHandStack())&&!eligible(client.player.getOffHandStack()))) stopAnimations();
        });
    }
    private MeleeCosmeticRenderer(){}
    /** Forces the class (and its tick hook) to load during client init. */
    public static void init(){}
    public static boolean eligible(ItemStack stack){return stack.isIn(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem;}
    private static CosmeticEntry equipped(){return CosmeticManager.getInstance().equipped(CosmeticSlot.MELEE);}
    private static boolean activeView(){
        var c=MinecraftClient.getInstance();return c.player!=null&&c.world!=null&&equipped()!=null
            &&!c.player.isSpectator()&&c.options.getPerspective().isFirstPerson()&&c.getCameraEntity()==c.player
            &&(ModuleManager.freeCam==null||!ModuleManager.freeCam.isEnabled());
    }
    private static void stopAnimations(){for(State state:STATES)state.stop();primary=null;deferred=null;keyDown=false;}
    public static void reset(){stopAnimations();VARIANTS.clear();variantSelection=null;}
    private static Variant variant(CosmeticEntry entry){
        if(!Objects.equals(variantSelection,entry.id())){VARIANTS.clear();variantSelection=entry.id();}
        return VARIANTS.computeIfAbsent(entry.id(),key->{
            var meta=CosmeticRenderer.metadata(entry);
            if(meta==null||!meta.has("variants")||meta.getAsJsonArray("variants").isEmpty())return new Variant("",Set.of());
            var choices=meta.getAsJsonArray("variants");var v=choices.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(choices.size())).getAsJsonObject();
            var hidden=new HashSet<String>();if(v.has("hidden"))for(var b:v.getAsJsonArray("hidden"))hidden.add(b.getAsString());
            return new Variant(v.has("suffix")?v.get("suffix").getAsString():"",Set.copyOf(hidden));
        });
    }
    private static State prepare(AbstractClientPlayerEntity player,Hand hand,ItemStack stack,CosmeticEntry entry){
        State state=STATES[hand==Hand.MAIN_HAND?0:1];int slot=hand==Hand.MAIN_HAND?player.getInventory().getSelectedSlot():40;
        var client=MinecraftClient.getInstance();
        if(state.animation==null||state.entry!=entry||state.world!=client.world||state.item!=stack.getItem()||state.slot!=slot){
            state.stop();state.entry=entry;state.world=client.world;state.item=stack.getItem();state.slot=slot;
            state.variant=variant(entry);state.distance=0;
            state.model=CosmeticRenderer.model(entry);
            var metadata=CosmeticRenderer.metadata(entry);
            float actionBlend=metadata!=null&&metadata.has("actionBlend")?metadata.get("actionBlend").getAsFloat():.2f;
            var sounds=new CosmeticSoundPlayer[]{new CosmeticSoundPlayer(),new CosmeticSoundPlayer(),new CosmeticSoundPlayer()};
            state.animation=new MeleeAnimationController(CosmeticRenderer.animations(entry),state.model.bones.stream().map(BedrockModel.Bone::name).toList(),state.variant.suffix(),java.util.concurrent.ThreadLocalRandom.current(),new MeleeAnimationController.Audio(){
                public void start(int track,BedrockAnimation.Clip clip,boolean loop){sounds[track].start(clip,loop);}
                public void update(int track,float seconds){sounds[track].update(seconds);}
                public void stop(int track){sounds[track].stop();}
            },actionBlend);
        }
        if(state.advancedFrame!=visualFrame){
            float delta=state.clock.advance(visualFrame,System.nanoTime(),client.isPaused());
            state.distance+=(float)player.getVelocity().horizontalLength()*delta*20;
            state.animation.tick(delta,player.isSprinting(),player.isOnGround(),state.distance);
            state.advancedFrame=visualFrame;
        }
        state.rendered=true;
        return state;
    }
    private static State controlled(){return STATES[0].animation!=null?STATES[0]:STATES[1];}
    public static void inspect(){if(activeView()&&!MinecraftClient.getInstance().player.isUsingItem()){State s=controlled();if(s.animation!=null)s.animation.inspect();}}
    public static void attack(boolean primaryAttack){
        var c=MinecraftClient.getInstance();
        if(!activeView()||c.player.isUsingItem()||(!primaryAttack&&eligible(c.player.getOffHandStack())))return;
        State s=controlled();if(s.animation!=null)s.animation.attack(primaryAttack);
    }
    public static String actionName(){var s=controlled();return s.animation==null?"":s.animation.actionName();}
    public static String movementName(){var s=controlled();return s.animation==null?"":s.animation.movementName();}
    public static boolean play(String name){var s=controlled();return s.animation!=null&&s.animation.play(name);}
    /** One visual frame per hand pass: BK advanced it from GameRenderer, here from the HeldItemRenderer wrapper. */
    public static void beginVisualFrame(){visualFrame++;}
    public static void beginFrame(){primary=null;deferred=null;for(State s:STATES)s.rendered=false;}
    public static void endFrame(AbstractClientPlayerEntity player,OrderedRenderCommandQueue queue,int light){
        if(primary!=null&&deferred!=null&&!deferred.isEmpty()){
            Matrix4f transform=new Matrix4f(primaryBase).translate(0,-deferredEquip*.6f,0);
            transform.mul(CosmeticRenderer.firstPersonAnchor(primary.entry,primary.animation.pose("camera")));
            transform=MeleeArmRenderer.supportingItemTransform(primary.model,primaryPose,transform,primaryMirrored);
            if(transform!=null){
                MatrixStack matrices=new MatrixStack();matrices.multiplyPositionMatrix(transform);
                MinecraftClient.getInstance().gameRenderer.firstPersonRenderer.renderItem(player,deferred,
                    primaryMirrored?ItemDisplayContext.FIRST_PERSON_RIGHT_HAND:ItemDisplayContext.FIRST_PERSON_LEFT_HAND,matrices,queue,light);
            }
        }
        for(int i=0;i<STATES.length;i++)if(!STATES[i].rendered){
            State s=STATES[i];
            if(!activeView()||player.isUsingItem()||!eligible(player.getStackInHand(i==0?Hand.MAIN_HAND:Hand.OFF_HAND)))s.stop();
            else s.clock.reset(); // A skipped hand pass is not an equipment change.
        }
        primary=null;deferred=null;
    }
    public static boolean firstPerson(AbstractClientPlayerEntity player,Hand hand,ItemStack stack,float swing,float equip,MatrixStack matrices,OrderedRenderCommandQueue queue,int light){
        var client=MinecraftClient.getInstance();var entry=equipped();
        if(!activeView()||player!=client.player||player.isUsingItem()||entry==null)return false;
        // Preserve vanilla's two-handed map presentation and item use animations.
        if(player.getMainHandStack().isOf(Items.FILLED_MAP)||player.getOffHandStack().isOf(Items.FILLED_MAP))return false;
        if(!eligible(stack)){
            ItemStack other=player.getStackInHand(hand==Hand.MAIN_HAND?Hand.OFF_HAND:Hand.MAIN_HAND);
            if(eligible(other)&&CosmeticRenderer.available(entry)){deferred=stack;deferredEquip=equip;return true;}
            return false;
        }
        // HeldItemRenderer keeps the old stack during its equip transition.
        // Do not restart the old cosmetic after the inventory has already changed.
        ItemStack actual=player.getStackInHand(hand);
        if(!eligible(actual)||actual.getItem()!=stack.getItem())return false;
        if(!CosmeticRenderer.available(entry))return false;
        State state=prepare(player,hand,stack,entry);
        boolean pressed=client.currentScreen==null&&GLFW.glfwGetKey(client.getWindow().getHandle(),GLFW.GLFW_KEY_G)==GLFW.GLFW_PRESS;
        if(pressed&&!keyDown)inspect();keyDown=pressed;
        boolean mirrored=(hand==Hand.MAIN_HAND?player.getMainArm():player.getMainArm().getOpposite())==Arm.LEFT;
        // The authored draw already moves the hands; a current cosmetic does not also receive vanilla's equip drop.
        Matrix4f base=new Matrix4f(matrices.peek().getPositionMatrix());
        Matrix4f transform=new Matrix4f(base);
        if(mirrored)transform.scale(-1,1,1);
        transform.mul(CosmeticRenderer.firstPersonAnchor(entry,state.animation.pose("camera")));
        Matrix4f[] pose=state.model.pose(state.animation::pose,0,null,0,0);
        CosmeticSink.Recorder sink=CosmeticSink.queued();
        CosmeticRenderer.renderPose(entry,transform,sink,light,pose,0,state.variant.hidden());
        sink.flush(queue);
        if(primary==null){
            MeleeArmRenderer.render(player,state.model,pose,transform,queue,light,mirrored);
            primary=state;primaryBase=base;primaryPose=pose;primaryMirrored=mirrored;
        }
        return true;
    }
    public static boolean thirdPerson(LivingEntity entity,ItemStack stack,ItemDisplayContext mode,MatrixStack matrices,OrderedRenderCommandQueue queue,int light){
        var client=MinecraftClient.getInstance();var entry=equipped();
        boolean left=mode==ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        if(entity!=client.player||entry==null||!eligible(stack)||!CosmeticRenderer.available(entry)||(!left&&mode!=ItemDisplayContext.THIRD_PERSON_RIGHT_HAND))return false;
        var model=CosmeticRenderer.model(entry);var v=variant(entry);
        CosmeticSink.Recorder sink=CosmeticSink.queued();
        CosmeticRenderer.renderPose(entry,new Matrix4f(matrices.peek().getPositionMatrix()).mul(CosmeticRenderer.thirdPersonTransform(entry,left)),sink,light,model.pose(null,0,null),0,v.hidden());
        sink.flush(queue);
        return true;
    }
}
