package thunder.hack.features.cosmetics.render;

import java.util.*;
import java.util.random.RandomGenerator;
import static thunder.hack.features.cosmetics.render.BedrockAnimation.*;

/** Per-hand, render-only animation state. Time and movement are supplied by the caller. */
public final class MeleeAnimationController implements AutoCloseable {
    public interface Audio { void start(int track,Clip clip,boolean loop); void update(int track,float seconds); void stop(int track); }
    private final Map<String,Clip> clips;
    private final List<String> bones;
    private final String suffix;
    private final RandomGenerator random;
    private final Audio audio;
    private final float actionBlend;
    private Clip base,action,movement;
    private float baseTime,actionTime,movementTime,staticFade,movementFade;
    private float staticDuration,movementDuration,lastDistance;
    private Map<String,Pose> staticPose=new HashMap<>(),additivePose=new HashMap<>(),staticFrom=Map.of(),movementFrom=Map.of();
    private int attackIndex;
    private boolean running;
    public MeleeAnimationController(Map<String,Clip> clips,List<String> bones,String suffix,RandomGenerator random,Audio audio) {
        this(clips,bones,suffix,random,audio,.2f);
    }
    public MeleeAnimationController(Map<String,Clip> clips,List<String> bones,String suffix,RandomGenerator random,Audio audio,float actionBlend) {
        this.clips=clips;this.bones=List.copyOf(bones);this.suffix=suffix;this.random=random;this.audio=audio;
        this.actionBlend=Float.isFinite(actionBlend)?Math.clamp(actionBlend,0,.5f):.2f;
        base=clip("static_idle");if(base==null)base=clip("idle");
        if(base!=null)audio.start(0,base,true);
        compose();playAction(choose("draw"),0);compose();
    }
    private Clip clip(String name) { Clip c=clips.get(name+suffix);return c==null?clips.get(name):c; }
    private Clip choose(String prefix) {
        var choices=new ArrayList<Clip>();
        Clip c=clip(prefix);if(c!=null)choices.add(c);
        for(int i=1;i<=16;i++){c=clip(prefix+"_"+i);if(c!=null)choices.add(c);}
        return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
    }
    public void inspect(){playAction(choose("inspect"),actionBlend);}
    public void attack(boolean primary){
        Clip c=clip(primary?"melee_"+(attackIndex++%2+1):"melee_3");
        if(c==null)c=clip("melee_1");
        // Vanilla retries held use every few ticks. Let the visual action finish.
        if(!primary && c!=null && action==c)return;
        playAction(c,actionBlend);
    }
    /** Explicit clip selection also lets the development probe cover every authored action. */
    public boolean play(String name){Clip c=clip(name);if(c==null)return false;playAction(c,actionBlend);return true;}
    private void playAction(Clip next,float fade) {
        if(next==null)return;
        staticFrom=new HashMap<>(staticPose);staticFade=staticDuration=fade;
        audio.stop(1);action=next;actionTime=0;audio.start(1,next,false);
        compose();
    }
    private void playMovement(Clip next,float fade,boolean loop) {
        movementFrom=new HashMap<>(additivePose);movementFade=movementDuration=fade;
        audio.stop(2);movement=next;movementTime=0;
        if(next!=null)audio.start(2,next,loop);
    }
    public void tick(float dt,boolean sprinting,boolean onGround,float distance) {
        dt=Math.max(0,Math.min(dt,1));
        float travelled=Math.max(0,distance-lastDistance);lastDistance=distance;
        baseTime+=dt;staticFade=Math.max(0,staticFade-dt);movementFade=Math.max(0,movementFade-dt);
        if(base!=null)audio.update(0,baseTime);
        if(action!=null){
            actionTime+=dt;audio.update(1,Math.min(actionTime,action.duration()));
            if(actionTime>=action.duration()){
                staticFrom=new HashMap<>(staticPose);staticFade=staticDuration=.15f;
                action=null;
            }
        }
        if(movement!=null){
            boolean groundCycle=running&&onGround&&movement.name().equals(name("run"));
            movementTime+=groundCycle?travelled/2*movement.duration():dt;
            audio.update(2,movementTime);
        }
        boolean shouldRun=sprinting && action==null;
        if(shouldRun&&!running){running=true;playMovement(clip("run_start"),.2f,false);}
        else if(!shouldRun&&running){running=false;playMovement(clip("run_end"),.3f,false);}
        if(running && (movement==null || (!movement.name().equals(name("run")) && movementTime>=movement.duration()))) {
            playMovement(clip("run"),.2f,true);
        }
        if(!running&&movement!=null&&movementTime>=movement.duration() && movementFade<=0) {
            playMovement(null,.15f,false);
        }
        compose();
    }
    private String name(String name){Clip c=clip(name);return c==null?name:c.name();}
    public String actionName(){return action==null?"":action.name();}
    public String movementName(){return movement==null?"":movement.name();}
    private static Pose sampled(Clip c,String bone,float t,boolean loop) {
        if(c==null)return Pose.IDENTITY;
        return c.sample(bone,loop?t%c.duration():Math.min(t,Math.nextDown(c.duration())));
    }
    private static Pose overlay(Pose base,Clip clip,String bone,float time){
        if(clip==null)return base;
        Tracks t=clip.bones().get(bone);if(t==null)return base;
        Pose p=sampled(clip,bone,time,false);
        return new Pose(t.position().defined()?p.position():base.position(),t.rotation().defined()?p.rotation():base.rotation(),t.scale().defined()?p.scale():base.scale());
    }
    private void compose(){
        for(String bone:bones){
            Pose p=overlay(sampled(base,bone,baseTime,true),action,bone,actionTime);
            staticPose.put(bone,blend(staticFrom.getOrDefault(bone,Pose.IDENTITY),p,staticDuration==0?1:1-staticFade/staticDuration));
            Pose m=sampled(movement,bone,movementTime,running&&movement!=null&&movement.name().equals(name("run")));
            additivePose.put(bone,blend(movementFrom.getOrDefault(bone,Pose.IDENTITY),m,movementDuration==0?1:1-movementFade/movementDuration));
        }
    }
    private static Pose blend(Pose a,Pose b,float f){
        float[] p=new float[3],r=new float[3],s=new float[3];
        for(int i=0;i<3;i++){p[i]=a.position()[i]+(b.position()[i]-a.position()[i])*f;r[i]=a.rotation()[i]+wrapDegrees(b.rotation()[i]-a.rotation()[i])*f;s[i]=a.scale()[i]+(b.scale()[i]-a.scale()[i])*f;}
        return new Pose(p,r,s);
    }
    private static float wrapDegrees(float degrees){return degrees-(float)Math.floor((degrees+180)/360)*360;}
    public Pose pose(String bone){
        Pose a=staticPose.getOrDefault(bone,Pose.IDENTITY),b=additivePose.getOrDefault(bone,Pose.IDENTITY);
        float[] p=new float[3],r=new float[3],s=new float[3];
        for(int i=0;i<3;i++){p[i]=a.position()[i]+b.position()[i];r[i]=a.rotation()[i]+b.rotation()[i];s[i]=a.scale()[i]*b.scale()[i];}
        return new Pose(p,r,s);
    }
    @Override public void close(){audio.stop(0);audio.stop(1);audio.stop(2);action=movement=null;running=false;}
}
