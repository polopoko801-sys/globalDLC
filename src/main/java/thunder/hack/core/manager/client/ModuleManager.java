package thunder.hack.core.manager.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.glfw.GLFW;
import thunder.hack.ThunderHack;
import thunder.hack.core.manager.IManager;
import thunder.hack.gui.clickui.ClickGUI;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.hud.impl.*;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.*;
import thunder.hack.features.modules.combat.*;
import thunder.hack.features.modules.misc.*;
import thunder.hack.features.modules.misc.rynek.Rynek;
import thunder.hack.features.modules.misc.rynek.RynekHud;
import thunder.hack.features.modules.movement.Timer;
import thunder.hack.features.modules.movement.*;
import thunder.hack.features.modules.player.*;
import thunder.hack.features.modules.render.Particles;
import thunder.hack.features.modules.render.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class ModuleManager implements IManager {
    public ArrayList<Module> modules = new ArrayList<>();
    public List<Module> sortedModules = new ArrayList<>();
    public List<Integer> activeMouseKeys = new ArrayList<>();

    public static ChunkAnimation chunkAnimation = new ChunkAnimation();
    public static Pets pets = new Pets();
    public static ItemPhysics itemPhysics = new ItemPhysics();
    public static DamageMarkers damageMarkers = new DamageMarkers();
    public static HitColor hitColor = new HitColor();
    public static PearlESP pearlESP = new PearlESP();
    public static Weather weather = new Weather();
    public static GlobalPanel globalPanel = new GlobalPanel();
    public static Ambience ambience = new Ambience();
    public static FogColor fogColor = new FogColor();
    public static WorldColor worldColor = new WorldColor();
    public static NightMode nightMode = new NightMode();
    public static EnchantmentColor enchantmentColor = new EnchantmentColor();
    public static ContainerBlur containerBlur = new ContainerBlur();
    public static PersonViewer personViewer = new PersonViewer();
    public static Radar radar = new Radar();
    public static BeaconRadius beaconRadius = new BeaconRadius();
    public static DeleteArmorStands deleteArmorStands = new DeleteArmorStands();
    public static DeathCoordinates deathCoordinates = new DeathCoordinates();
    public static DeathSounds deathSounds = new DeathSounds();
    public static PearlLogger pearlLogger = new PearlLogger();
    public static FlagDetector flagDetector = new FlagDetector();
    public static AntiPacketException antiPacketException = new AntiPacketException();
    public static LevitationControl levitationControl = new LevitationControl();
    public static InventoryCleaner inventoryCleaner = new InventoryCleaner();
    public static NoWaterCollision noWaterCollision = new NoWaterCollision();
    public static BaritoneSettings baritoneSettings = new BaritoneSettings();
    public static PortalInventory portalInventory = new PortalInventory();
    public static TotemPopCounter totemPopCounter = new TotemPopCounter();
    public static HotbarReplenish hotbarReplenish = new HotbarReplenish();
    public static DurabilityAlert durabilityAlert = new DurabilityAlert();
    public static PearlBlockThrow pearlBlockThrow = new PearlBlockThrow();
    public static ChatTranslator chatTranslator = new ChatTranslator();
    public static ClientSettings clientSettings = new ClientSettings();
    public static TimerIndicator timerIndicator = new TimerIndicator();
    public static NoServerRotate noServerRotate = new NoServerRotate();
    public static BreakHighLight breakHighLight = new BreakHighLight();
    public static BlockOverlay blockOverlay = new BlockOverlay();
    public static AntiBadEffects antiBadEffects = new AntiBadEffects();
    public static MouseElytraFix mouseElytraFix = new MouseElytraFix();
    public static TotemAnimation totemAnimation = new TotemAnimation();
    public static PortalGodMode portalGodMode = new PortalGodMode();
    public static Notifications notifications = new Notifications();
    public static NoEntityTrace noEntityTrace = new NoEntityTrace();
    public static MessageAppend messageAppend = new MessageAppend();
    public static EntityControl entityControl = new EntityControl();
    public static ElytraReplace elytraReplace = new ElytraReplace();
    public static MoreKnockback moreKnockback = new MoreKnockback();
    public static AntiServerAdd antiServerAdd = new AntiServerAdd();
    public static AntiLegitMiss antiLegitMiss = new AntiLegitMiss();
    public static TridentBoost tridentBoost = new TridentBoost();
    public static Trajectories trajectories = new Trajectories();
    public static TargetStrafe targetStrafe = new TargetStrafe();
    public static PVPResources pvpResources = new PVPResources();
    public static InventoryHud inventoryHud = new InventoryHud();
    public static NoServerSlot noServerSlot = new NoServerSlot();
    public static NoCameraClip noCameraClip = new NoCameraClip();
    public static ItemScroller itemScroller = new ItemScroller();
    public static HitParticles hitParticles = new HitParticles();
    public static ElytraRecast elytraRecast = new ElytraRecast();
    public static ChestStealer chestStealer = new ChestStealer();
    public static AutoTpAccept autoTpAccept = new AutoTpAccept();
    public static AntiServerRP antiServerRP = new AntiServerRP();
    public static TotemCounter totemCounter = new TotemCounter();
    public static PerfectDelay perfectDelay = new PerfectDelay();
    public static ServerHelper serverHelper = new ServerHelper();
    public static StashLogger stashLogger = new StashLogger();
    public static FastLatency fastLatency = new FastLatency();
    public static PearlChaser pearlChaser = new PearlChaser();
    public static WorldTweaks worldTweaks = new WorldTweaks();
    public static VisualRange visualRange = new VisualRange();
    public static Speedometer speedometer = new Speedometer();
    public static ReverseStep reverseStep = new ReverseStep();
    public static NoJumpDelay noJumpDelay = new NoJumpDelay();
    public static NameProtect nameProtect = new NameProtect();
    public static MiddleClick middleClick = new MiddleClick();
    public static LogoutSpots logoutSpots = new LogoutSpots();
    public static LagNotifier lagNotifier = new LagNotifier();
    public static AutoRespawn autoRespawn = new AutoRespawn();
    public static AutoCrystal autoCrystal = new AutoCrystal();
    public static EntitySpeed entitySpeed = new EntitySpeed();
    public static AspectRatio aspectRatio = new AspectRatio();
    public static ClientSpoof clientSpoof = new ClientSpoof();
    public static LegitHelper legitHelper = new LegitHelper();
    public static AutoAnchor autoAnchor = new AutoAnchor();
    public static WaterSpeed waterSpeed = new WaterSpeed();
    public static TriggerBot triggerBot = new TriggerBot();
    public static HitSound hitSound = new HitSound();
    public static HpAlert hpAlert = new HpAlert();
    public static InventoryMove inventoryMove = new InventoryMove();
    public static AirStuck airStuck = new AirStuck();
    public static Arrows arrows = new Arrows();
    public static Skeleton skeleton = new Skeleton();
    public static TPSCounter tpsCounter = new TPSCounter();
    public static StorageEsp storageEsp = new StorageEsp();
    public static StaffBoard staffBoard = new StaffBoard();
    public static NoInteract noInteract = new NoInteract();
    public static ModuleList moduleList = new ModuleList();
    public static KillEffect killEffect = new KillEffect();
    public static JumpCircle jumpCircle = new JumpCircle();
    public static HoleAnchor holeAnchor = new HoleAnchor();
    public static Fullbright fullbright = new Fullbright();
    public static FpsCounter fpsCounter = new FpsCounter();
    public static FakePlayer fakePlayer = new FakePlayer();
    public static ElytraSwap elytraSwap = new ElytraSwap();
    public static ElytraPlus elytraPlus = new ElytraPlus();
    public static AutoSprint autoSprint = new AutoSprint();
    public static AutoGApple autoGApple = new AutoGApple();
    public static AntiHunger antiHunger = new AntiHunger();
    public static Animations animations = new Animations();
    public static ItemShader itemShader = new ItemShader();
    public static thunder.hack.features.modules.render.SwingAnimations swingAnimations = new thunder.hack.features.modules.render.SwingAnimations();
    public static ClickEffect clickEffect = new ClickEffect();
    public static DamageTint damageTint = new DamageTint();
    public static AntiAttack antiAttack = new AntiAttack();
    public static GapplesHud gapplesHud = new GapplesHud();
    public static HitBubbles hitBubbles = new HitBubbles();
    public static AutoTrader autoTrader = new AutoTrader();
    public static KillStats killStats = new KillStats();
    public static Particles particles = new Particles();
    public static ToolSaver toolSaver = new ToolSaver();
    public static WayPoints wayPoints = new WayPoints();
    public static WaterMark waterMark = new WaterMark();
    public static ViewModel viewModel = new ViewModel();
    public static TunnelEsp tunnelEsp = new TunnelEsp();
    public static TickShift tickShift = new TickShift();
    public static TargetHud targetHud = new TargetHud();
    public static HealthIndicator healthIndicator = new HealthIndicator();
    public static LowFire lowFire = new LowFire();
    public static SmallShield smallShield = new SmallShield();
    public static SmallTotem smallTotem = new SmallTotem();
    public static ColorGrading colorGrading = new ColorGrading();
    public static SpeedMine speedMine = new SpeedMine();
    public static PotionHud potionHud = new PotionHud();
    public static DynamicIsland dynamicIsland = new DynamicIsland();
    public static PearlBait pearlBait = new PearlBait();
    public static PacketFly packetFly = new PacketFly();
    public static MultiTask multitask = new MultiTask();
    public static HudEditor hudEditor = new HudEditor();
    public static Crosshair crosshair = new Crosshair();
    public static Criticals criticals = new Criticals();
    public static ChatUtils chatUtils = new ChatUtils();
    public static AutoTotem autoTotem = new AutoTotem();
    public static AutoLeave autoLeave = new AutoLeave();
    public static AutoFlyme autoFlyme = new AutoFlyme();
    public static AutoArmor autoArmor = new AutoArmor();
    public static Cooldowns cooldowns = new Cooldowns();
    public static TapeMouse tapeMouse = new TapeMouse();
    public static Rotations rotations = new Rotations();
    public static MemoryHud memoryHud = new MemoryHud();
    public static AntiCrash antiCrash = new AntiCrash();
    public static AutoGear autoGear = new AutoGear();
    public static ViewLock viewLock = new ViewLock();
    public static Velocity velocity = new Velocity();
    public static Tooltips tooltips = new Tooltips();
    public static Surround surround = new Surround();
    public static Scaffold scaffold = new Scaffold();
    public static PopChams popChams = new PopChams();
    public static NoRender noRender = new NoRender();
    public static NameTags nameTags = new NameTags();
    public static LongJump longJump = new LongJump();
    public static KeyBinds keyBinds = new KeyBinds();
    public static HoleSnap holeSnap = new HoleSnap();
    public static HoleFill holeFill = new HoleFill();
    public static ExtraTab extraTab = new ExtraTab();
    public static ClickGui clickGui = new ClickGui();
    public static AutoTrap autoTrap = new AutoTrap();
    public static AutoCart autoCart = new AutoCart();
    public static AutoTool autoTool = new AutoTool();
    public static SoundESP soundESP = new SoundESP();
    public static AutoSoup autoSoup = new AutoSoup();
    public static AutoFish autoFish = new AutoFish();
    public static AutoBuff autoBuff = new AutoBuff();
    public static AutoAuth autoAuth = new AutoAuth();
    public static ArmorHud armorHud = new ArmorHud();
    public static AirPlace airPlace = new AirPlace();
    public static SelfTrap selfTrap = new SelfTrap();
    public static AntiVoid antiVoid = new AntiVoid();
    public static KillFeed killFeed = new KillFeed();
    public static AutoWalk autoWalk = new AutoWalk();
    public static AutoSign autoSign = new AutoSign();
    public static BlockESP blockESP = new BlockESP();
    public static SafeWalk safeWalk = new SafeWalk();
    public static Windows windows = new Windows();
    public static Breaker breaker = new Breaker();
    public static AutoEat autoEat = new AutoEat();
    public static AntiAFK antiAFK = new AntiAFK();
    public static SoundFX soundFX = new SoundFX();
    public static ClientSound clientSound = new ClientSound();
    public static Tracker tracker = new Tracker();
    public static TpsSync tpsSync = new TpsSync();
    public static Spammer spammer = new Spammer();
    public static Shaders shaders = new Shaders();
    public static PingHud pingHud = new PingHud();
    public static ItemESP itemESP = new ItemESP();
    public static HoleESP holeESP = new HoleESP();
    public static GuiMove guiMove = new GuiMove();
    public static FreeCam freeCam = new FreeCam();
    public static FastUse fastUse = new FastUse();
    public static BowSpam bowSpam = new BowSpam();
    public static BoatFly boatFly = new BoatFly();
    public static AutoWeb autoWeb = new AutoWeb();
    public static AntiWeb antiWeb = new AntiWeb();
    public static AntiBot antiBot = new AntiBot();
    public static AntiAim antiAim = new AntiAim();
    public static Tracers tracers = new Tracers();
    public static Parkour parkour = new Parkour();
    public static ClickTP clickTP = new ClickTP();
    public static BowPop bowPop = new BowPop();
    public static XCarry xCarry = new XCarry();
    public static Strafe strafe = new Strafe();
    public static Spider spider = new Spider();
    public static NoSlow noSlow = new NoSlow();
    public static NoFall noFall = new NoFall();
    public static Hotbar hotbar = new Hotbar();
    public static HitBox hitBox = new HitBox();
    public static Flight flight = new Flight();
    public static Coords coords = new Coords();
    public static Burrow burrow = new Burrow();
    public static AutoEZ autoEZ = new AutoEZ();
    public static AimBot aimBot = new AimBot();
    public static Quiver quiver = new Quiver();
    public static NoPush noPush = new NoPush();
    public static UnHook unHook = new UnHook();
    public static SelfDestruct selfDestruct = new SelfDestruct();
    public static ErrorModule errorModule = new ErrorModule();
    public static WaterGrabber waterGrabber = new WaterGrabber();
    public static Avoid avoid = new Avoid();
    public static Timer timer = new Timer();
    public static Regen regen = new Regen();
    public static Speed speed = new Speed();
    public static Reach reach = new Reach();
    public static Nuker nuker = new Nuker();
    public static Media media = new Media();
    public static Chams chams = new Chams();
    public static Blink blink = new Blink();
    public static Phase phase = new Phase();
    public static NoBob noBob = new NoBob();
    public static Jesus jesus = new Jesus();
    public static XRay xray = new XRay();
    public static Step step = new Step();
    public static Aura aura = new Aura();
    public static Kilaura kilaura = new Kilaura();
    public static thunder.hack.features.modules.combat.Backtrack backtrack = new thunder.hack.features.modules.combat.Backtrack();
    public static MaceAura maceAura = new MaceAura();
    public static SpearHelping spearHelping = new SpearHelping();
    public static FOV fov = new FOV();
    public static ESP esp = new ESP();
    public static RPC rpc = new RPC();
    public static ConfigManager configManager = new ConfigManager();
    public static Rynek rynek = new Rynek();
    public static RynekHud rynekHud = new RynekHud();
    public static Loadout loadout = new Loadout();
    public static FakeLag fakeLag = new FakeLag();
    public static AutoBuild autoBuild = new AutoBuild();
    public static WlotDetector wlotDetector = new WlotDetector();
    public static PlayerHead playerHead = new PlayerHead();
    public static NotifyHud notifyHud = new NotifyHud();
    public static RenderDebug renderDebug = new RenderDebug();
    public static HitBlock hitBlock = new HitBlock();
    public static BlockJump blockJump = new BlockJump();
    public static FootstepFX footstepFX = new FootstepFX();
    public static DamageShockwave damageShockwave = new DamageShockwave();
    public static CriticalSlash criticalSlash = new CriticalSlash();
    public static PlayerDrawing playerDrawing = new PlayerDrawing();
    public static Wings wings = new Wings();
    public static Lanterns lanterns = new Lanterns();
    public static ChinaHat chinaHat = new ChinaHat();
    public static BacktrackTest backtrackTest = new BacktrackTest();
    public static ChunkAnalyzer chunkAnalyzer = new ChunkAnalyzer();
    public static MusicWidget musicWidget = new MusicWidget();
    public static LyricsText lyricsText = new LyricsText();
    public static VoidRings voidRings = new VoidRings();
    public static GroundSigil groundSigil = new GroundSigil();
    public static CustomSky customSky = new CustomSky();
    public static ProjectileTrails projectileTrails = new ProjectileTrails();
    public static AimCamera aimCamera = new AimCamera();
    public static GeometryParticles geometryParticles = new GeometryParticles();
    public static BetterSound betterSound = new BetterSound();
    public static HitIndicator hitIndicator = new HitIndicator();
    public static FloorWeather floorWeather = new FloorWeather();
    public static Trails trails = new Trails();

    public ModuleManager() {
        for (Field field : getClass().getDeclaredFields()) {
            if (Module.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                try {
                    Module module = (Module) field.get(this);
                    // Retired modules: the objects stay because other code still reads their state or
                    // extends them (BlockJump IS a HitBlock), but they are not registered, so they never
                    // show up in any menu and never run.
                    if (module == aura || module == hitBlock) {
                        module.setEnabled(false);
                        continue;
                    }
                    modules.add(module);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public Module get(String name) {
        for (Module module : modules) {
            if (!module.getName().equalsIgnoreCase(name)) continue;
            return module;
        }
        return null;
    }

    public ArrayList<Module> getEnabledModules() {
        ArrayList<Module> enabledModules = new ArrayList<>();
        for (Module module : modules) {
            if (!module.isEnabled()) continue;
            enabledModules.add(module);
        }
        return enabledModules;
    }

    public ArrayList<Module> getModulesByCategory(Module.Category category) {
        ArrayList<Module> modulesCategory = new ArrayList<>();
        modules.forEach(module -> {
            if (module.getCategory() == category && !module.isHidden()) {
                modulesCategory.add(module);
            }
        });
        // Kilaura is pinned to the top of Combat; the rest stays alphabetical (see onLoad)
        if (category == Module.Category.COMBAT && modulesCategory.remove(kilaura))
            modulesCategory.add(0, kilaura);
        return modulesCategory;
    }

    public List<Module.Category> getCategories() {
        return new ArrayList<>(Module.Category.values());
    }

    public void onLoad(String category) {
        try {
            ThunderHack.EVENT_BUS.unsubscribe(unHook);
        } catch (Exception ignored) {
        }
        unHook.setEnabled(false);

        modules.sort(Comparator.comparing(Module::getName));

        modules.forEach(m -> {
            if (m.isEnabled() && (m.getCategory().getName().equalsIgnoreCase(category) || category.equals("none")))
                ThunderHack.EVENT_BUS.subscribe(m);
        });

        if (ConfigManager.firstLaunch) {
            ModuleManager.notifications.enable();
            rpc.enable();
            soundFX.enable();
        }
    }

    public void onUpdate() {
        if (Module.fullNullCheck()) return;
        modules.stream().filter(Module::isEnabled).forEach(Module::onUpdate);
    }

    public void onRender2D(DrawContext context) {
        // F1 hides everything, that is what it is for. F3 hides NOTHING of ours: it used to blank the whole
        // client render (particles, arrows and all), then only the HUD category — and "F3 and my ArrayList /
        // TargetInfo / KeyBinds are gone" is still the same complaint. The debug screen is a diagnostic
        // overlay, not a reason to lose the client HUD, so both now coexist (MixinInGameHud draws ours on
        // top of the debug panel so it stays readable).
        if (mc.options.hudHidden) return;
        // snapshot of the frame before any HUD geometry lands — the glass panels blur this
        thunder.hack.utility.render.ScreenCapture.captureFrame();
        // block previews (AutoTrap/Surround/Scaffold/AutoCrystal...) draw here now: the world-space 3D
        // queue double-rotates on 1.21.11 and made them drift off their block while walking
        thunder.hack.utility.render.BlockAnimationUtility.onRender2D(context);
        HudElement.anyHovered = false;
        // Isolate each element: one HUD element throwing (e.g. a null on some servers/gamemodes like
        // duels) previously aborted the whole forEach, making the ENTIRE HUD vanish with no crash.
        modules.stream().filter(Module::isEnabled).forEach(module -> {
            try {
                module.onRender2D(context);
            } catch (Exception ex) {
                reportOnce(module, "onRender2D", ex);
            }
        });
        if (!HudElement.anyHovered && !ClickGUI.anyHovered)
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
                GLFW.glfwSetCursor(mc.getWindow().getHandle(), thunder.hack.utility.render.Cursors.get(GLFW.GLFW_ARROW_CURSOR));
            }
        // Camera follow and the body spin both animate, so they step per frame, not per tick.
        ModuleManager.aimCamera.frame();
        thunder.hack.utility.render.core.CloudanRenderCore.endFrame2D(context);
        ThunderHack.core.onRender2D(context);
    }

    public void onRender3D(MatrixStack stack) {
        modules.stream().filter(Module::isEnabled).forEach(module -> {
            try {
                module.onRender3D(stack);
            } catch (Exception ex) {
                reportOnce(module, "onRender3D", ex);
            }
        });
    }

    private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A render hook that throws keeps being isolated (one module must not take the HUD down), but it is no
     * longer silent: the first failure of each module/hook goes to the log, once, with its stack trace. A
     * visual that "just draws nothing" was otherwise impossible to tell apart from one that is crashing.
     */
    private void reportOnce(Module module, String hook, Exception ex) {
        if (reported.add(module.getName() + ":" + hook))
            ThunderHack.LOGGER.error("[Cloudan] {}.{} threw - the module draws nothing until this is fixed", module.getName(), hook, ex);
    }

    public void sortModules() {
        sortedModules = getEnabledModules().stream().filter(Module::isDrawn).sorted(Comparator.comparing(module -> FontRenderers.getModulesRenderer().getStringWidth(module.getFullArrayString()) * -1)).collect(Collectors.toList());
    }

    /** The world the modules were last told about, held by identity only - never dereferenced. */
    private net.minecraft.client.world.ClientWorld lastWorld;

    /**
     * Fires {@link #onLogout()} when the world actually changes or disappears.
     *
     * <p>Called from the client tick BEFORE the usual null guard, because the case worth catching - the world
     * becoming null on a disconnect - is the exact case that guard skips. Comparing by identity covers all
     * three transitions with one check: a world to null (disconnect, quit to menu), null to a world (join,
     * reconnect) and a world to a different world (changing dimension or server).
     *
     * <p>The reference is only ever compared, never used, so holding it until the next transition cannot keep
     * a dead world alive in any way that matters - and it is replaced the moment one arrives.
     */
    /** Dev only (-Dgombal.devOpenGui=true): opens the ClickGUI once after joining, for screenshot checks. */
    private int devOpenGuiTicks = (System.getProperty("gombal.devOpenGui") != null || System.getProperty("gombal.devEnable") != null) ? 60 : -1;

    public void watchWorld() {
        if (devOpenGuiTicks > 0 && mc.world != null && mc.player != null && --devOpenGuiTicks == 0) {
            String en = System.getProperty("gombal.devEnable");
            if (en != null)
                for (String n : en.split(",")) {
                    Module m = get(n.trim());
                    if (m != null && !m.isEnabled()) m.enable();
                }
            // -Dgombal.devCmd="tp+@s+0+80+0+45+40;time+set+noon" (+ = space): commands run once after joining (screenshot setups)
            String cmd = System.getProperty("gombal.devCmd");
            if (cmd != null)
                for (String c : cmd.split(";"))
                    if (!c.isBlank()) mc.player.networkHandler.sendChatCommand(c.replace('+', ' ').trim());
            String gui = System.getProperty("gombal.devOpenGui");
            if (gui != null) {
                clickGui.enable();
                Module dev = get(gui);
                if (dev != null) thunder.hack.gui.clickui.ClickGUI.getInstance().devOpenSettings(dev);
            }
        }

        net.minecraft.client.world.ClientWorld world = mc.world;
        if (world == lastWorld) return;

        boolean hadWorld = lastWorld != null;
        lastWorld = world;

        if (hadWorld) onLogout();
    }

    public void onLogout() {
        // Caches, particles and batches all point at a world that is gone; keeping them is how a client
        // ends up rendering ghosts of the last server on the next one.
        thunder.hack.utility.render.core.CloudanRenderCore.onWorldChange();
        // Isolated per module: one module throwing during teardown must not stop the rest from cleaning up,
        // which is the whole reason this hook exists.
        modules.forEach(module -> {
            try {
                module.onLogout();
            } catch (Exception ignored) {
            }
        });
    }

    public void onLogin() {
        modules.forEach(Module::onLogin);
    }

    public void onUnload(String category) {
        modules.forEach(module -> {
            if (module.isEnabled() && (module.getCategory().getName().equalsIgnoreCase(category) || category.equals("none"))) {
                ThunderHack.EVENT_BUS.unsubscribe(module);
                module.setEnabled(false);
            }
        });
        modules.forEach(Module::onUnload);
    }

    public void onKeyPressed(int eventKey) {
        if (eventKey == -1 || eventKey == 0 || mc.currentScreen instanceof ClickGUI) {
            return;
        }
        modules.forEach(module -> {
            if (module.getBind().getKey() == eventKey)
                module.toggle();
        });
    }

    public void onKeyReleased(int eventKey) {
        if (eventKey == -1 || eventKey == 0 || mc.currentScreen instanceof ClickGUI)
            return;

        modules.forEach(module -> {
            if (module.getBind().getKey() == eventKey && module.getBind().isHold())
                module.disable();
        });
    }

    public void onMoseKeyPressed(int eventKey) {
        if (eventKey == -1 || mc.currentScreen instanceof ClickGUI) {
            return;
        }

        modules.forEach(module -> {
            if (Objects.equals(module.getBind().getBind(), "M" + eventKey)) {
                module.toggle();
            }
        });
    }

    public void onMoseKeyReleased(int eventKey) {
        if (eventKey == -1 || mc.currentScreen instanceof ClickGUI)
            return;

        activeMouseKeys.add(eventKey);

        modules.forEach(module -> {
            if (Objects.equals(module.getBind().getBind(), "M" + eventKey) && module.getBind().isHold())
                module.disable();
        });
    }

    public ArrayList<Module> getModulesSearch(String string) {
        ArrayList<Module> modulesCategory = new ArrayList<>();
        modules.forEach(module -> {
            if (!module.isHidden() && module.getName().toLowerCase().contains(string.toLowerCase()))
                modulesCategory.add(module);
        });
        return modulesCategory;
    }

    public void registerModule(Module module) {
        if (module == null) return;

        this.modules.add(module);

        if (module.isEnabled())
            ThunderHack.EVENT_BUS.subscribe(module);
    }

    public void registerHudElement(HudElement hudElement) {
        if (hudElement == null) return;

        this.modules.add(hudElement);
    }
}
