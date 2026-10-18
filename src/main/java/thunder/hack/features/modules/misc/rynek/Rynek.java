package thunder.hack.features.modules.misc.rynek;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.Bind;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.SearchInvResult;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * McRynek — clean-room market automation for the server's economy. Auto-sells configured items via
 * the server's {@code /wystaw <amount>} command, snipes listings off {@code /ah}, parses purchase
 * confirmations from chat to track session spending, and reads the player's balance off the sidebar.
 * Config lives in {@link RynekConfigManager}; the visual config is {@link RynekScreen}; the overlay
 * is RynekHud.
 *
 * <p>Deliberately contains NO licensing / phone-home logic from the original mod.
 */
public final class Rynek extends Module {
    private final Setting<Boolean> autoSell = new Setting<>("AutoSell", false);
    private final Setting<Integer> sellDelay = new Setting<>("SellDelay", 250, 0, 2000);
    // command sent to list the held item; {price} = typed sell price, {amount} = quantity
    private final Setting<String> sellCommand = new Setting<>("SellCommand", "wystaw {price}");
    private final Setting<Boolean> autoBuy = new Setting<>("AutoBuy", false);
    /** Pause between two clicks of the sniper. The market answers in well under 100ms. */
    private final Setting<Integer> buyDelay = new Setting<>("BuyDelay", 120, 1, 2000);
    private final Setting<String> ahCommand = new Setting<>("AhCommand", "ah");
    private final Setting<Boolean> autoOpenAh = new Setting<>("AutoOpenAh", true);
    /**
     * Sort the listing must be on before sniping. The market remembers the sort per player, and on any
     * other setting the freshly listed items are simply not on page one — which is the whole game.
     */
    private final Setting<String> sortMode = new Setting<>("SortMode", "Najnowsze");
    /**
     * How many times to click the sort button once per {@code /ah}, to land on "Najnowsze". Defaults to
     * the 3 presses this market needs, so it works with nothing to configure. 0 falls back to reading
     * the button, which only works when the server labels the live mode legibly — this one does not, and
     * auto-detection then deliberately leaves the sort alone, i.e. clicks nothing at all.
     * <p>
     * Applied ONCE per market open, never after a purchase: the sort is a cycle, so repeating it after
     * every buy would walk straight back off "Najnowsze".
     */
    private final Setting<Integer> sortClicks = new Setting<>("SortClicks", 4, 0, 10);
    /** Refresh the page between scans (right-click + left-click on the refresh control). */
    private final Setting<Boolean> autoRefresh = new Setting<>("AutoRefresh", true);
    private final Setting<Integer> budget = new Setting<>("Budget", 0, 0, 10000000);          // 0 = bez limitu
    private final Setting<Integer> maxBuysPerItem = new Setting<>("MaxBuysPerItem", 0, 0, 64);  // 0 = bez limitu
    private final Setting<String> moneyRegex = new Setting<>("MoneyRegex", "(?i)(?:saldo|stan konta|money|balans|\\$)[^0-9]*([0-9 .,]+)");
    /**
     * What a full inventory means. Sniping on with nowhere to put anything is how a session burns its
     * budget on purchases the server hands back: the item lands on the floor, or the buy is refused
     * outright and the money is gone from the books anyway.
     * <p>
     * Pause is the default because a full backpack is usually temporary — you drop into a chest and the
     * session picks up where it left off, no re-typing {@code /ah} and no re-running the sort.
     */
    private final Setting<FullAction> whenFull = new Setting<>("WhenFull", FullAction.Pause);
    /** How many empty slots must stay free to keep buying. 0 = buy until the last one is taken. */
    private final Setting<Integer> keepFreeSlots = new Setting<>("KeepFreeSlots", 0, 0, 10, v -> whenFull.getValue() != FullAction.Ignore);
    private final Setting<Boolean> quickList = new Setting<>("ScrollOffer", true);
    private final Setting<Boolean> notify = new Setting<>("Notify", true);
    /** Discord webhook URL; empty = off. Every confirmed purchase is posted there. */
    private final Setting<String> webhook = new Setting<>("Webhook", "");
    /**
     * Report purchases this module did not make either - the ones another market mod (or your own hand on
     * the mouse) clicked through. The server announces every purchase in chat and shows the item in the
     * confirmation window, and both of those are readable no matter who did the clicking, so the webhook
     * does not have to be built into the thing doing the buying.
     * <p>
     * The module has to be ON for this: a disabled module is unsubscribed from the packet bus. AutoBuy
     * can stay off - then nothing here clicks anything, it only watches and reports.
     */
    private final Setting<Boolean> watchOthers = new Setting<>("WatchOtherMods", true);
    /**
     * Anti-AFK hop while the module is on. Sniping is a stand-still activity, and this server kicks —
     * or worse, flags — a player who has not moved in a while. A single jump on a long timer is the
     * cheapest thing that reads as present without disturbing anything the sniper is doing.
     */
    private final Setting<Boolean> autoJump = new Setting<>("AutoJump", true);
    private final Setting<Integer> jumpDelay = new Setting<>("JumpDelay", 120, 10, 600);   // seconds
    /**
     * Periodically tear the market down and open it again. The page the server hands out goes stale:
     * refresh keeps re-drawing the same window, and after a while the listing simply stops carrying
     * anything new. A fresh {@code /ah} also re-runs the sort, so the session lands back on "Najnowsze"
     * instead of quietly drifting off it.
     */
    private final Setting<Boolean> reopenAh = new Setting<>("ReopenAh", true);
    private final Setting<Integer> reopenDelay = new Setting<>("ReopenDelay", 45, 5, 600); // seconds
    private final Setting<Bind> openGui = new Setting<>("OpenGUI", new Bind(GLFW.GLFW_KEY_RIGHT_BRACKET, false, false));
    private final Setting<Bind> startKey = new Setting<>("StartBuy", new Bind(-1, false, false));
    private final Setting<Bind> stopKey = new Setting<>("StopBuy", new Bind(GLFW.GLFW_KEY_U, false, false));

    /** Latest balance parsed from the sidebar (-1 when unknown). Read by the HUD. */
    public static long balance = -1;
    public static final SessionStats stats = new SessionStats();

    /*
     * Layout of the market GUI. These belong to the SERVER, not to us: which slot is the sort button and
     * where "yes" sits in the confirmation window cannot be derived from anything the client can see, and
     * the guesswork this module used to do instead — hunt for a redstone repeater in the bottom-left,
     * match the window title against a regex, click the first green-ish item — is why nothing was ever
     * actually bought. Taken from the working build of the market mod.
     */
    /** Sort button, bottom-right of the 54-slot listing. Clicking it cycles the sort mode. */
    private static final int SLOT_SORT = 53;
    /** Refresh control: right-click it, then left-click it, and the page reloads. */
    private static final int SLOT_REFRESH = 0;
    /** "Yes" in the confirmation window. Empty when someone else took the offer first. */
    private static final int SLOT_CONFIRM = 11;
    /** "No" in the confirmation window — the way out when the offer is gone. */
    private static final int SLOT_CANCEL = 15;
    /** Offers live in the first five rows; 45..53 is the navigation bar. */
    private static final int SCAN_SLOTS = 45;
    /** 54 container + 36 player = 90 slots on the listing; the confirmation window is far smaller. */
    private static final int AH_MIN_SLOTS = 80;
    /** Give up re-clicking the sort button rather than spinning on it forever. */
    private static final int SORT_RETRIES = 12;

    /**
     * The server's own answers, which turn out to be the only trustworthy signals here.
     * <p>
     * SERVER_COOLDOWN is the important one: this market enforces a 250ms gap between actions and says so
     * on every rejection. Clicking faster does not buy faster — it just gets everything thrown away. In a
     * two-minute session the client produced 24 of these and landed 2 purchases.
     */
    private static final Pattern SERVER_COOLDOWN =
            Pattern.compile("(?i)nast[ęe]pny raz mo[żz]esz to wykona[ćc] za\\s*(\\d+)\\s*ms");
    /** The real purchase confirmation, with the price actually charged. */
    private static final Pattern SERVER_BOUGHT =
            Pattern.compile("(?i)pomy[śs]lnie zakupi[łl]e[śs] produkt za\\s*([0-9][0-9 .,]*)\\s*\\$");
    /** Someone else got there first. */
    private static final Pattern SERVER_GONE =
            Pattern.compile("(?i)produkt ten zosta[łl] ju[żz] prawdopodobnie usuni[ęe]ty albo sprzedany");
    /** Lore price, as the market writes it: ($1,234). The brackets are what make it unambiguous. */
    private static final Pattern LORE_PRICE = Pattern.compile("\\(\\s*\\$\\s*([0-9][0-9 .,]*)\\)");
    /** Sword roll: {@code Dodatkowe obrażenia 15%}. */
    private static final Pattern LORE_DAMAGE = Pattern.compile("(?i)Dodatkowe obra[żz]enia\\s*(-?\\d+)\\s*%");
    /** Expiry stamp on timed listings: {@code wygaśnie: 24-01-2026, 17:16:00}. */
    private static final Pattern LORE_EXPIRY = Pattern.compile("(?i)wyga[śs]nie:?\\s*(\\d{2}-\\d{2}-\\d{4},\\s*\\d{2}:\\d{2}:\\d{2})");
    private static final SimpleDateFormat EXPIRY_FORMAT = new SimpleDateFormat("dd-MM-yyyy, HH:mm:ss");

    private final Timer sellTimer = new Timer();
    private final Timer guiCooldown = new Timer();
    private final Timer stateTimer = new Timer();
    private final Timer reopenTimer = new Timer();
    /** Anti-AFK hop clock; runs off the module being enabled, not off a buying session. */
    private final Timer jumpTimer = new Timer();
    /** Clock for the periodic full {@code /ah} restart. */
    private final Timer ahResetTimer = new Timer();

    private enum BuyState { IDLE, OPENING, SORTING, REFRESHING, SNIPING }

    /** What a full inventory does to the session. */
    public enum FullAction { Ignore, Pause, Stop }

    /** The "inventory full" line has already been printed; cleared as soon as there is room again. */
    private boolean fullNotified;

    private BuyState buyState = BuyState.IDLE;
    private boolean startWasDown;
    /** Previous state of the AutoBuy setting, so flipping it in the menu can start a real session. */
    private boolean lastAutoBuy;
    private int sortRetry;
    private long sessionSpent;
    private boolean stopAfterConfirm;
    private final Map<String, Integer> boughtCounts = new HashMap<>();

    /** The offer we clicked and are now confirming. */
    private RynekBuyEntry pendingEntry;
    private long pendingPrice;
    /**
     * Enchantments of the listing we clicked. The server's "KUPIONO" chat line carries the name, the
     * count and the price but nothing about the item itself, so the webhook would have no idea whether
     * it just bought Protection 6 boots or plain ones — this is where that comes from.
     */
    private List<String> pendingEnchants = List.of();
    /** The item's own anarchia text, captured off the listing before it was clicked. */
    private List<String> pendingLore = List.of();
    /** The listing we clicked, model components and all, so the HUD can draw its real texture. */
    private RynekIcon pendingIcon = new RynekIcon();
    private int pendingCount = 1;
    private String pendingDisplay = "";
    /** When the current buying session started; 0 when nothing has run yet. */
    private long sessionStart;
    /** When it stopped; 0 while running, so the clock freezes on STOP instead of ticking on forever. */
    private long sessionEnd;

    /**
     * Set the moment the server delivers new container contents. It lets the sniper act on the data the
     * instant it lands instead of waiting out BuyDelay and the next client tick — which is where nearly
     * all of the old latency was: up to 50ms of tick granularity on top of the configured delay, per
     * step, for three steps a purchase.
     */
    private volatile boolean contentsDirty;
    /** A state-machine run is already queued for this frame; further packets must not queue more. */
    private volatile boolean runScheduled;
    /** We clicked refresh and the new page has not landed yet — scanning the old one is wasted work. */
    private boolean awaitingContents;
    /** The sort has already been set for this market window; the cycle must not be walked again. */
    /** syncId of the market window we asked the server to close; -1 when nothing is closing. */
    private int closingSyncId = -1;
    private boolean sortDone;
    /** The sort button's text has been logged for this window. */
    private boolean sortDumped;
    /** Floor between two actions, purely so a burst of packets cannot turn into a click loop. */
    /**
     * Floor between two actions. Deliberately small: the market's 250ms limit applies to BUYING, and
     * putting that floor on everything made sorting, refreshing and scanning crawl for no reason. Those
     * run flat out; only the actions the server actually rate-limits wait for it.
     */
    private static final long MIN_ACTION_NANOS = 15_000_000L;
    /** Until when the server told us to hold off, in wall-clock millis. */
    private volatile long cooldownUntil;
    private long lastActionNanos;
    /** Balance parsing walks the whole sidebar through a regex — it does not need to run every tick. */
    private int balanceTick;

    private Pattern compiledMoney;
    private String pendingName;
    private int pendingQty;

    public Rynek() {
        super("AutoMarket", Category.MISC);
    }

    @Override
    public void onEnable() {
        compileMoney();
        resetBuyState();
        closingSyncId = -1;
        startWasDown = false;
        lastAutoBuy = autoBuy.getValue();
        sellTimer.reset();
        jumpTimer.reset();
        ahResetTimer.reset();
        // The overlay is the point of running this thing — bring it up with the module instead of making
        // the user go hunt for it in the HUD category. It draws nothing while AutoMarket is off, so
        // leaving it enabled afterwards costs nothing.
        if (!ModuleManager.rynekHud.isEnabled())
            ModuleManager.rynekHud.enable();

        // left-clicking (enabling) the module opens the single AutoMarket window (only in-game)
        if (mc.player != null && mc.currentScreen == null)
            mc.setScreen(new RynekScreen());
    }

    private void resetBuyState() {
        buyState = BuyState.IDLE;
        sortRetry = 0;
        pendingEntry = null;
        pendingPrice = 0;
    }

    private void compileMoney() {
        compiledMoney = tryCompile(moneyRegex.getValue());
    }

    private static Pattern tryCompile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;

        // open the config screen on keybind (edge-guarded so it doesn't reopen every tick)
        if (isKeyPressed(openGui) && mc.currentScreen == null && guiCooldown.passedMs(400)) {
            guiCooldown.reset();
            mc.setScreen(new RynekScreen());
        }

        // start buying via bind (edge-detected so /ah is sent once per press)
        boolean startDown = isKeyPressed(startKey);
        if (startDown && !startWasDown) startBuying();
        startWasDown = startDown;

        // stop buying via bind
        if (isKeyPressed(stopKey) && (autoSell.getValue() || autoBuy.getValue())) stopBuying();

        doAntiAfkJump();

        // refresh balance for the HUD — five times a second is plenty for a number you read
        if (compiledMoney == null) compileMoney();
        if (++balanceTick >= 4) {
            balanceTick = 0;
            balance = ScoreboardUtils.getNumericBalance(compiledMoney);
        }

        // auto-sell loop
        if (autoSell.getValue() && sellTimer.passedMs(sellDelay.getValue())) {
            doAutoSell();
            sellTimer.reset();
        }

        // Ticking AutoBuy on in the menu has to mean exactly what pressing StartBuy means. It did not:
        // that path skipped startBuying(), so the session counters kept whatever the previous run left
        // behind — with a Budget or MaxBuysPerItem set, the second session was capped out before it
        // clicked anything, and buyState never left IDLE.
        if (autoBuy.getValue() && !lastAutoBuy) startBuying();
        lastAutoBuy = autoBuy.getValue();

        // auto-buy / sniper loop (runs against the open /ah screen)
        if (autoBuy.getValue()) doSniper();
    }

    /** StartBuy bind: begin a buying session — optionally type {@code /ah}, then sort, refresh and snipe. */
    private void startBuying() {
        autoBuy.setValue(true);
        resetBuyState();
        closingSyncId = -1;
        sessionSpent = 0;
        stopAfterConfirm = false;
        boughtCounts.clear();
        reopenTimer.reset();
        sessionStart = System.currentTimeMillis();
        sessionEnd = 0L;
        cooldownUntil = 0L;
        awaitingContents = false;
        fullNotified = false;
        stats.reset();
        if (autoOpenAh.getValue()) openAh();
        else buyState = BuyState.SORTING;
        stateTimer.reset();
        if (notify.getValue()) sendMessage("Start kupowania");
    }

    /** StopBuy bind: halt the buying session (and auto-sell). */
    private void stopBuying() {
        autoBuy.setValue(false);
        autoSell.setValue(false);
        sessionEnd = System.currentTimeMillis();
        stopAfterConfirm = false;
        resetBuyState();
        closingSyncId = -1;
        if (notify.getValue()) sendMessage("Stop kupowania");
    }

    private void openAh() {
        sendChatCommand(ahCommand.getValue());
        buyState = BuyState.OPENING;
        sortRetry = 0;
        sortDone = false; // a fresh window may come back on whatever sort it likes
        sortDumped = false;
        stateTimer.reset();
        reopenTimer.reset();
        ahResetTimer.reset();
    }

    /** Close the market and type {@code /ah} again — the periodic reset, and the StartBuy path's retry. */
    private void restartAh() {
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            // Remember which window we are tearing down. Closing is not instant — the screen survives a
            // frame or two — and the sort clicks that followed went to the DYING window on its old
            // syncId, where the server drops them. With SortClicks=4 that ate the first one or two
            // presses every reset, so the listing ended up on whatever sort those leftovers landed on.
            closingSyncId = mc.player.currentScreenHandler.syncId;
            mc.player.closeHandledScreen();
        }
        awaitingContents = false;
        openAh();
    }

    /**
     * One hop every JumpDelay seconds while the module is on. Sniping is a stand-still activity and the
     * server treats a player who never moves as AFK; a single jump is the least intrusive thing that
     * reads as present. Skipped while sneaking or off the ground so it cannot shove you off a ledge.
     */
    private void doAntiAfkJump() {
        if (!autoJump.getValue()) return;
        if (!jumpTimer.passedMs(jumpDelay.getValue() * 1000L)) return;
        if (mc.player == null || !mc.player.isOnGround() || mc.player.isSneaking()) return;
        jumpTimer.reset();
        mc.player.jump();
    }

    /**
     * Sniper state machine, driving the real market GUI: type {@code /ah}, put the listing on the
     * configured sort, refresh the page, scan the offers, click one that is at or below its max price,
     * then answer the confirmation window.
     * <p>
     * Which window is open is decided by slot COUNT, not by the title: the listing is a 54-slot double
     * chest (90 slots counting the player inventory) and the confirmation window is much smaller. Titles
     * are decorated per server, and the title regex that used to gate all of this never matched the real
     * one — so the sniper sat on an open market doing nothing.
     */
    private void doSniper() {
        if (mc.player == null || mc.interactionManager == null) return;

        // Adding an offer beats sniping. The periodic ReopenAh tears the market down and types /ah again,
        // and doing that while you are mid-edit takes the window out from under your hands - which is
        // exactly what "adding an item closed my /ah" looks like from the outside. Both clocks are pushed
        // back too, so closing the editor does not immediately trigger the restart that was due.
        if (RynekOfferOverlay.isOpen()) {
            ahResetTimer.reset();
            stateTimer.reset();
            reopenTimer.reset();
            return;
        }

        // Only ever act on a server-opened container. Anything else — your own inventory, a crafting
        // table, our own config window — has completely different slots, and clicking 11/15 in there
        // would be throwing your items around.
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            // still waiting for /ah to open after a Start — give it a moment before retrying
            if (buyState == BuyState.OPENING && !stateTimer.passedMs(5000)) return;
            if (mc.currentScreen instanceof RynekScreen) return; // never fight our own config window
            if (!autoOpenAh.getValue() || !reopenTimer.passedMs(800)) return;
            openAh();
            return;
        }

        reopenTimer.reset(); // inside a GUI — the reopen delay counts from when it closes
        ScreenHandler handler = screen.getScreenHandler();
        if (handler == null) return;

        // Never act on the window we just asked the server to close: its clicks are thrown away, and the
        // sort counter would burn presses on it. Wait for a handler with a different syncId.
        if (closingSyncId >= 0) {
            if (handler.syncId == closingSyncId) return;
            closingSyncId = -1;
        }

        // Fresh contents from the server beat the clock: there is nothing to wait for once the data we
        // were waiting for has arrived. BuyDelay only paces the case where the server said nothing.
        boolean fresh = contentsDirty;
        if (!fresh && !stateTimer.passedMs(buyDelay.getValue())) return;
        if (System.nanoTime() - lastActionNanos < MIN_ACTION_NANOS) return;
        contentsDirty = false;
        lastActionNanos = System.nanoTime();

        // Confirmation window. Slot 11 holds "yes"; when it is empty the offer was taken by someone else
        // between our click and the window opening, and slot 15 backs out of it.
        if (handler.slots.size() <= AH_MIN_SLOTS) {
            // Confirming inside the server's cooldown just throws the purchase away — wait it out here,
            // and only here.
            if (System.currentTimeMillis() < cooldownUntil) return;
            if (slotStack(handler, SLOT_CONFIRM).isEmpty()) {
                click(handler, SLOT_CANCEL, 0);
                pendingEntry = null;
            } else {
                click(handler, SLOT_CONFIRM, 0);
                onConfirmed();
            }
            buyState = BuyState.SORTING;
            stateTimer.reset();
            return;
        }

        // Nowhere to put the next purchase. Checked here, after the confirmation branch: a buy already in
        // flight still gets its answer, so the session never leaves a confirmation window hanging open.
        if (whenFull.getValue() != FullAction.Ignore && pendingEntry == null && isInventoryFull()) {
            if (!fullNotified) {
                fullNotified = true;
                if (notify.getValue())
                    sendMessage(whenFull.getValue() == FullAction.Stop
                            ? "Pelne EQ - koniec kupowania"
                            : "Pelne EQ - pauza, zwolnij miejsce zeby wznowic");
            }
            if (whenFull.getValue() == FullAction.Stop) stopBuyingSession();
            return;
        }
        fullNotified = false;

        // Periodic hard restart of the market. Refreshing re-draws the page the server already gave us;
        // after a few minutes that page stops carrying anything newly listed, and the sniper sits on a
        // frozen listing. Closing and re-typing /ah gets a genuinely new window — and because openAh()
        // clears sortDone, the session walks back onto "Najnowsze" instead of drifting off it.
        // Placed after the confirmation branch on purpose: never abandon a window we are mid-purchase in.
        if (reopenAh.getValue() && pendingEntry == null
                && ahResetTimer.passedMs(reopenDelay.getValue() * 1000L)) {
            restartAh();
            return;
        }

        switch (buyState) {
            case IDLE, OPENING -> {
                buyState = BuyState.SORTING;
                sortRetry = 0;
            }
            case SORTING -> {
                int sortSlot = findSortSlot(handler);
                dumpSortButton(handler, sortSlot);
                int manual = sortClicks.getValue();
                if (manual > 0) {
                    // Told exactly how many presses it takes — just do them, no reading required.
                    if (!sortDone && sortRetry < manual) {
                        click(handler, sortSlot, 1);
                        sortRetry++;
                    } else {
                        sortDone = true;
                        sortRetry = 0;
                        buyState = autoRefresh.getValue() ? BuyState.REFRESHING : BuyState.SNIPING;
                    }
                    break;
                }
                SortState state = sortState(slotStack(handler, sortSlot), sortMode.getValue());
                // UNKNOWN means the button told us nothing we can read: clicking blindly is how a session
                // used to end up on "najwyższe ceny", so it stops here — set SortClicks instead.
                if (state != SortState.INACTIVE || sortRetry >= SORT_RETRIES) {
                    sortRetry = 0;
                    buyState = autoRefresh.getValue() ? BuyState.REFRESHING : BuyState.SNIPING;
                } else {
                    click(handler, sortSlot, 1);
                    sortRetry++;
                }
            }
            // The market reloads the page on a right-click followed by a left-click. Without it the
            // listing you scan is the one drawn when /ah opened, so a sniper sees the same stale offers
            // forever and never reacts to anything newly listed.
            case REFRESHING -> {
                click(handler, SLOT_REFRESH, 1);
                click(handler, SLOT_REFRESH, 0);
                awaitingContents = true;
                buyState = BuyState.SNIPING;
            }
            case SNIPING -> {
                // Wait for the page we just asked for. Without this the loop re-refreshed every 15ms
                // regardless of whether the server had answered — hundreds of packets a second, the same
                // stale offers scanned over and over, and the frame drops that come with it. Paced by the
                // server's replies it is both smoother AND faster, because every scan sees new data.
                if (awaitingContents && !fresh && !stateTimer.passedMs(150)) return;
                awaitingContents = false;
                if (!scanAndBuy(handler) && autoRefresh.getValue())
                    buyState = BuyState.REFRESHING;
            }
        }
        stateTimer.reset();
    }

    /**
     * The sort control. Slot 53 is where it sits, but the button is a redstone repeater and the row it
     * lives in is the server's to arrange — so if 53 is not the repeater, the navigation row is searched
     * for one rather than clicking whatever happens to be in that corner.
     */
    private void dumpSortButton(ScreenHandler handler, int sortSlot) {
        if (sortDumped) return;
        sortDumped = true;
        try {
            ItemStack button = slotStack(handler, sortSlot);
            StringBuilder sb = new StringBuilder("[McRynek] sort slot ").append(sortSlot)
                    .append(" item=").append(Registries.ITEM.getId(button.getItem()))
                    .append(" name=\"").append(Formatting.strip(button.getName().getString())).append('"');
            LoreComponent lore = button.get(DataComponentTypes.LORE);
            if (lore != null)
                for (Text line : lore.lines())
                    sb.append("\n  lore: \"").append(Formatting.strip(line.getString()))
                            .append("\" colors=").append(colorsOf(line));
            thunder.hack.ThunderHack.LOGGER.info(sb.toString());
        } catch (Exception ignored) {
        }
    }

    /** Every colour used in a text run, so the log can show which sort line the server highlights. */
    private static String colorsOf(Text text) {
        StringBuilder sb = new StringBuilder();
        text.visit((style, part) -> {
            TextColor c = style.getColor();
            sb.append(c == null ? "none" : c.getName()).append(' ');
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString().trim();
    }

    private int findSortSlot(ScreenHandler handler) {
        if (slotStack(handler, SLOT_SORT).getItem() == Items.REPEATER) return SLOT_SORT;
        for (int id = SCAN_SLOTS; id < Math.min(54, handler.slots.size()); id++)
            if (slotStack(handler, id).getItem() == Items.REPEATER) return id;
        return SLOT_SORT;
    }

    /**
     * Is there still room for what the sniper is about to buy? Only empty slots count: a purchase the
     * server cannot hand over is money spent for nothing, and a market item almost never stacks onto
     * what is already in the backpack (enchants, lore, durability all differ).
     */
    private boolean isInventoryFull() {
        if (mc.player == null) return false;

        int free = 0;
        for (int i = 0; i < 36; i++) // 0-8 hotbar, 9-35 backpack; armour and offhand are not storage
            if (mc.player.getInventory().getStack(i).isEmpty() && ++free > keepFreeSlots.getValue()) return false;

        return true;
    }

    /** Walks the offers; clicks the first one worth buying. Returns true when something was clicked. */
    private boolean scanAndBuy(ScreenHandler handler) {
        // Resolve the configured item ids ONCE per scan. The old loop built a registry-id string for
        // every occupied slot and compared it against every entry — 45 string allocations plus a
        // string compare per rule, on every single scan, and the scan is the thing that has to be fast.
        Map<Item, RynekBuyEntry> wanted = wantedItems();
        if (wanted.isEmpty()) return false;
        // Inside the cooldown a click on an offer is wasted, but refreshing is not — so skip the buy and
        // let the caller keep the page fresh, ready for the moment it lifts.
        if (System.currentTimeMillis() < cooldownUntil) return false;

        int limit = Math.min(SCAN_SLOTS, handler.slots.size());
        for (int id = 0; id < limit; id++) {
            if (id == SLOT_REFRESH) continue; // control, not an offer
            ItemStack stack = slotStack(handler, id);
            if (stack.isEmpty()) continue;
            if (!wanted.containsKey(stack.getItem())) continue; // cheapest possible reject

            RynekBuyEntry entry = matchEntry(stack);
            if (entry == null) continue;

            // per-item purchase cap
            if (maxBuysPerItem.getValue() > 0 && boughtCounts.getOrDefault(entry.getName(), 0) >= maxBuysPerItem.getValue())
                continue;

            long price = parseLorePrice(stack);
            if (price <= 0 || price > entry.getMaxPrice()) continue;

            // budget cap — never let a buy push the session over the configured budget
            if (budget.getValue() > 0 && sessionSpent + price > budget.getValue()) continue;

            // Read the listing BEFORE clicking it. slotStack() hands back the slot's live ItemStack, and
            // clickSlot applies the pick-up client-side straight away — so the object we were reading
            // afterwards had already been emptied. That is why Discord kept reporting purchases of
            // "Air" with no enchantments.
            pendingEntry = entry;
            pendingPrice = price;
            pendingEnchants = RynekBuyEntry.readEnchants(stack);
            pendingLore = RynekBuyEntry.readLore(stack);
            pendingIcon = RynekIcon.from(stack);
            pendingCount = Math.max(1, stack.getCount());
            pendingDisplay = Formatting.strip(stack.getName().getString());
            click(handler, id, 0);
            return true;
        }
        return false;
    }

    /**
     * Booked once the confirmation actually went out, not when the offer was clicked. A good share of
     * clicks land on an offer somebody else took first, and counting those spent budget that was never
     * spent — the session would stop itself long before the money ran out.
     */
    private void onConfirmed() {
        if (pendingEntry == null) return;
        sessionSpent += pendingPrice;
        boughtCounts.merge(pendingEntry.getName(), 1, Integer::sum);

        // Nothing is booked here. Clicking "yes" is not a purchase — the server still has to accept it,
        // and it often does not: the offer gets taken first, or the action lands inside the market's
        // 250ms cooldown. The books are kept in onServerBought(), off the server's own confirmation,
        // which also carries the price actually charged (it differs from the lore price).
        if (budget.getValue() > 0 && sessionSpent >= budget.getValue()) stopAfterConfirm = true;
    }

    /** The server confirmed a purchase: this, and only this, is what a bought item looks like. */
    private void onServerBought(long realPrice) {
        // A watched purchase can be the first thing that ever happens in a session that was never
        // started here, and a webhook reporting "0:00" for every one of them tells nobody anything.
        if (sessionStart == 0L) sessionStart = System.currentTimeMillis();

        String label = pendingDisplay == null || pendingDisplay.isBlank()
                ? (pendingEntry != null ? pendingEntry.getDisplayName() : "?") : pendingDisplay;
        long price = realPrice > 0 ? realPrice : pendingPrice;

        // Enchants first, then the item's own text: that is the order they read in on the listing, and
        // it is what tells "Anarchiczny hełm II" apart from the plain one in the HUD.
        List<String> details = new java.util.ArrayList<>(pendingEnchants);
        for (String line : pendingLore)
            if (!details.contains(line)) details.add(line);

        stats.addPurchase(label, pendingCount, price, pendingIcon, details);
        RynekWebhook.postPurchase(webhook.getValue(), label, pendingCount, price,
                pendingEnchants, balance, stats.getSpent(), stats.getBoughtItems(), getSessionTime());

        if (notify.getValue())
            sendMessage("Kupiono: " + label + " za $" + price
                    + (budget.getValue() > 0 ? " (wydano $" + stats.getSpent() + "/$" + budget.getValue() + ")" : ""));

        clearPending();
        if (stopAfterConfirm) {
            stopAfterConfirm = false;
            stopBuyingSession();
        }
    }

    /**
     * Remembers what the open confirmation window is offering, so a purchase clicked by something else -
     * another market mod, or you - still has a name, a price and an icon when the server confirms it.
     * Does nothing while this module has a purchase of its own in flight; that one was already read off
     * the listing, which is better data than the confirmation window carries.
     */
    private void snapshotOffer() {
        if (!watchOthers.getValue()) return;
        if (pendingEntry != null || (pendingDisplay != null && !pendingDisplay.isBlank())) return;
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;

        ScreenHandler handler = screen.getScreenHandler();
        if (handler == null || handler.slots.size() > AH_MIN_SLOTS) return;
        if (slotStack(handler, SLOT_CONFIRM).isEmpty()) return; // nothing left to confirm

        ItemStack offer = offeredStack(handler);
        if (offer.isEmpty()) return;

        pendingPrice = parseLorePrice(offer);
        pendingEnchants = RynekBuyEntry.readEnchants(offer);
        pendingLore = RynekBuyEntry.readLore(offer);
        pendingIcon = RynekIcon.from(offer);
        pendingCount = Math.max(1, offer.getCount());
        pendingDisplay = Formatting.strip(offer.getName().getString());
    }

    /** The item a confirmation window is about: the middle slot, or the first thing that is not furniture. */
    private static @NotNull ItemStack offeredStack(@NotNull ScreenHandler handler) {
        int container = Math.max(0, handler.slots.size() - 36);

        ItemStack center = slotStack(handler, 13);
        if (!center.isEmpty() && !isFiller(center)) return center;

        for (int id = 0; id < container; id++) {
            if (id == SLOT_CONFIRM || id == SLOT_CANCEL) continue;
            ItemStack stack = slotStack(handler, id);
            if (!stack.isEmpty() && !isFiller(stack)) return stack;
        }

        return ItemStack.EMPTY;
    }

    /** Glass panes and barriers are what servers pad a GUI with; they are never the thing being sold. */
    private static boolean isFiller(@NotNull ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        return path.endsWith("_pane") || path.equals("barrier") || path.equals("light_gray_dye");
    }

    private void clearPending() {
        pendingEntry = null;
        pendingPrice = 0;
        pendingCount = 1;
        pendingDisplay = "";
        pendingEnchants = List.of();
        pendingLore = List.of();
        pendingIcon = new RynekIcon();
    }

    private static ItemStack slotStack(ScreenHandler handler, int id) {
        if (id < 0 || id >= handler.slots.size()) return ItemStack.EMPTY;
        return handler.getSlot(id).getStack();
    }

    private void click(ScreenHandler handler, int slotId, int button) {
        if (slotId < 0 || slotId >= handler.slots.size()) return;
        mc.interactionManager.clickSlot(handler.syncId, slotId, button, SlotActionType.PICKUP, mc.player);
    }

    /**
     * Is {@code mode} the sort the listing is currently on? The sort button's lore names every mode, so
     * the text alone says nothing — the server marks the live one by COLOUR (yellow / gold / green) and
     * greys the rest out. That colour is the only thing separating "sorted by newest" from "there is a
     * line that says newest".
     */
    /** What the sort button lets us conclude about a given mode. */
    private enum SortState { ACTIVE, INACTIVE, UNKNOWN }

    /** Every sort the market offers, so "some other mode is live" is distinguishable from "no idea". */
    private static final String[] SORT_MODES = {
            "najnowsze", "najstarsze", "najtansze", "najdrozsze", "najwyzsze", "najnizsze"
    };
    /** Glyphs servers use to point at the live entry in a list. */
    private static final char[] SORT_MARKERS = {'\u25B6', '\u25BA', '\u27A4', '\u00BB', '\u203A',
            '\u2192', '\u2714', '\u2713', '\u25CF', '\u2022', '>'};

    private SortState sortState(ItemStack button, String mode) {
        if (button.isEmpty() || mode == null || mode.isBlank()) return SortState.UNKNOWN;
        String want = fold(mode);

        // 1. The button's NAME normally carries the live sort ("Sortowanie: Najwyższe ceny"). It is the
        //    one signal that states outright which mode is on, so it decides on its own — including the
        //    negative case, where naming a DIFFERENT mode proves ours is not the one.
        String name = fold(Formatting.strip(button.getName().getString()));
        if (name.contains(want)) return SortState.ACTIVE;
        for (String other : SORT_MODES)
            if (!other.equals(want) && name.contains(other)) return SortState.INACTIVE;

        // 2. Otherwise the lore lists every mode and marks the live one — with a pointer glyph, or by
        //    colouring it and greying the rest out.
        LoreComponent lore = button.get(DataComponentTypes.LORE);
        if (lore == null) return SortState.UNKNOWN;
        for (Text line : lore.lines()) {
            String raw = Formatting.strip(line.getString());
            if (!fold(raw).contains(want)) continue;
            // Deliberately NOT keyed on the "➤" glyph: the log shows the server puts one in front of
            // every mode, so it marks a list entry, not the live one. Colour is the only difference.
            if (hasColor(line, Formatting.GRAY, Formatting.DARK_GRAY)) return SortState.INACTIVE;
            if (hasColor(line, Formatting.YELLOW, Formatting.GOLD, Formatting.GREEN)) return SortState.ACTIVE;
        }
        return SortState.UNKNOWN;
    }

    private static boolean hasMarker(String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty()) return false;
        for (char c : SORT_MARKERS)
            if (t.charAt(0) == c) return true;
        return false;
    }

    /** Lowercase and strip Polish diacritics, so "Najtańsze" and "najtansze" compare equal. */
    private static String fold(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase().toCharArray())
            sb.append(switch (c) {
                case 'ą' -> 'a';
                case 'ć' -> 'c';
                case 'ę' -> 'e';
                case 'ł' -> 'l';
                case 'ń' -> 'n';
                case 'ó' -> 'o';
                case 'ś' -> 's';
                case 'ż', 'ź' -> 'z';
                default -> c;
            });
        return sb.toString();
    }

    /** Does any run of this text carry one of the given colours? */
    private static boolean hasColor(Text text, Formatting... colors) {
        boolean[] hit = {false};
        text.visit((style, part) -> {
            TextColor color = style.getColor();
            if (color != null)
                for (Formatting f : colors)
                    if (color.equals(TextColor.fromFormatting(f))) hit[0] = true;
            return Optional.empty();
        }, Style.EMPTY);
        return hit[0];
    }

    /**
     * Finds a buy entry for this listing: same item, none of its unwanted enchantments, and every
     * enchantment marked "musi mieć" actually on it at that level or better.
     * <p>
     * The required half is what makes an offer worth anything. A rule that is only "boots, max $X"
     * fires on the first pair of plain leather boots that is cheap enough, and the Protection 6 pair
     * you set the price for scrolls past untouched.
     */
    private RynekBuyEntry matchEntry(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        Map<String, Integer> present = null;
        for (RynekBuyEntry entry : RynekConfigManager.getInstance().getBuyEntries()) {
            if (!id.equals(entry.getName())) continue;
            // Name first: on this server it is the strongest discriminator there is. Two netherite
            // helmets can carry identical enchantment components and still be a 17k "Ana bania" and a
            // 200k "Ana bania 2" — the tier lives in the name, not in the components.
            if (entry.isMatchName() && !sameName(stack, entry)) continue;
            if (present == null) present = readEnchants(stack); // only pay for this once, and only if needed
            if (hasUnwantedEnchant(present, entry.getUnwantedEnchants())) continue;
            if (!hasRequiredEnchants(present, entry.getRequiredEnchants())) continue;
            if (!loreMatches(stack, entry)) continue;
            if (entry.getMinPercent() > 0 && damagePercent(stack) < entry.getMinPercent()) continue;
            if (entry.getMinDays() > 0 && daysLeft(stack) < entry.getMinDays()) continue;
            return entry;
        }
        return null;
    }

    /**
     * Items any offer is watching for, so the scan can reject a slot on an object identity check before
     * it touches lore, enchantments or strings. Rebuilt per scan — there are a handful of rules, not a
     * handful of thousands.
     */
    private Map<Item, RynekBuyEntry> wantedItems() {
        Map<Item, RynekBuyEntry> out = new HashMap<>();
        for (RynekBuyEntry entry : RynekConfigManager.getInstance().getBuyEntries()) {
            if (entry == null || entry.getName() == null) continue;
            try {
                Item item = Registries.ITEM.get(Identifier.of(entry.getName()));
                if (item != null && item != Items.AIR) out.putIfAbsent(item, entry);
            } catch (Exception ignored) {
                // a malformed id in the config must not take the whole scan down
            }
        }
        return out;
    }

    /**
     * Checks the offer's demands about the item's own text — the anarchia stat lines. Only lines the
     * user marked count: "musi mieć" has to be present, "nie chcę" must not be.
     */
    private static boolean loreMatches(ItemStack stack, RynekBuyEntry entry) {
        List<String> required = entry.getRequiredLore();
        List<String> unwanted = entry.getUnwantedLore();
        if (required.isEmpty() && unwanted.isEmpty()) return true;

        List<String> have = RynekBuyEntry.readLore(stack);
        StringBuilder joined = new StringBuilder();
        for (String line : have) joined.append(RynekBuyEntry.fold(line)).append('\n');
        String text = joined.toString();

        for (String r : required)
            if (!text.contains(RynekBuyEntry.fold(r))) return false;
        for (String u : unwanted)
            if (text.contains(RynekBuyEntry.fold(u))) return false;
        return true;
    }

    /** Does this listing carry the same display name the offer was captured from? */
    private static boolean sameName(ItemStack stack, RynekBuyEntry entry) {
        String want = entry.getDisplayName();
        if (want == null || want.isBlank()) return true; // nothing captured — do not block on it
        String have = Formatting.strip(stack.getName().getString());
        return have != null && have.trim().equalsIgnoreCase(want.trim());
    }

    /**
     * Enchantment path -> level for a listing, read once per stack.
     * <p>
     * Deliberately built from {@link RynekBuyEntry#readEnchants}, the same reader the editor captures
     * with: it merges the components, a book's stored enchantments and the levels written in the lore.
     * Reading the components directly here — as this used to — meant the rule you saved as
     * "protection 6" was checked against a component that says 5, so a tier-2 piece never matched.
     */
    private static Map<String, Integer> readEnchants(ItemStack stack) {
        Map<String, Integer> out = new HashMap<>();
        for (String spec : RynekBuyEntry.readEnchants(stack)) {
            String path = RynekBuyEntry.enchantPath(spec);
            if (!path.isEmpty()) out.merge(path, RynekBuyEntry.enchantLevel(spec), Math::max);
        }
        return out;
    }

    private boolean hasUnwantedEnchant(Map<String, Integer> present, List<String> unwanted) {
        if (unwanted == null || unwanted.isEmpty()) return false;
        for (String u : unwanted) {
            String path = RynekBuyEntry.enchantPath(u);
            if (path.isEmpty()) continue;
            for (String have : present.keySet())
                if (have.contains(path)) return true;
        }
        return false;
    }

    /** Every required enchantment present at its level or higher — "ochrona 6" means at least 6. */
    private boolean hasRequiredEnchants(Map<String, Integer> present, List<String> required) {
        if (required == null || required.isEmpty()) return true;
        for (String spec : required) {
            String path = RynekBuyEntry.enchantPath(spec);
            if (path.isEmpty()) continue;
            int want = RynekBuyEntry.enchantLevel(spec);
            int have = 0;
            for (Map.Entry<String, Integer> e : present.entrySet())
                if (e.getKey().contains(path)) have = Math.max(have, e.getValue());
            if (have < want) return false;
        }
        return true;
    }

    /**
     * Price of a listing, read from its lore. The market prints it bracketed — ($1,234) — and that
     * bracket is the whole point: an offer's lore also carries the seller, the listing time and
     * enchantment numbers, and "the first number on a line that mentions $" picked those up just as
     * happily as the price, so offers were rejected as too expensive or bought at the wrong figure.
     */
    private long parseLorePrice(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            Matcher m = LORE_PRICE.matcher(Formatting.strip(line.getString()));
            if (m.find()) {
                long p = parseNumber(m.group(1));
                if (p > 0) return p;
            }
        }
        return -1;
    }

    /**
     * The "Dodatkowe obrażenia" roll off a sword's lore, in percent; 0 when the line is not there.
     * That number is what separates two otherwise identical swords in price.
     */
    private int damagePercent(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return 0;
        for (Text line : lore.lines()) {
            Matcher m = LORE_DAMAGE.matcher(Formatting.strip(line.getString()));
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    /**
     * Whole days left before a timed listing (pet, costume) runs out, read off the {@code wygaśnie:}
     * line. Returns {@link Integer#MAX_VALUE} when the listing carries no expiry at all — an item that
     * never runs out must not be thrown away by a "minimum days" rule.
     */
    private int daysLeft(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return Integer.MAX_VALUE;
        for (Text line : lore.lines()) {
            Matcher m = LORE_EXPIRY.matcher(Formatting.strip(line.getString()));
            if (!m.find()) continue;
            try {
                long left = EXPIRY_FORMAT.parse(m.group(1)).getTime() - System.currentTimeMillis();
                return left <= 0 ? 0 : (int) TimeUnit.MILLISECONDS.toDays(left);
            } catch (ParseException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void doAutoSell() {
        String template = sellCommand.getValue();
        boolean needsPrice = template.contains("{price}");
        for (RynekItem item : RynekConfigManager.getInstance().getItems()) {
            if (item.getItem() == Items.AIR) continue;
            // don't list for free if the command uses a price and none was set
            if (needsPrice && item.getSellPrice() <= 0) continue;
            SearchInvResult result = InventoryUtility.findItemInHotBar(item.getItem());
            if (!result.found()) continue;
            result.switchTo();
            String cmd = template
                    .replace("{price}", String.valueOf(item.getSellPrice()))
                    .replace("{amount}", String.valueOf(item.getSellAmount()));
            sendChatCommand(cmd);
        }
    }

    /**
     * AutoMarket never belongs in the ArrayList — it is a module you run for hours with its own window
     * and its own overlay, and the HUD already says everything the array entry would.
     */
    @Override
    public boolean isDrawn() {
        return false;
    }

    /** How long the current session has been running, as {@code m:ss} or {@code h:mm:ss}. */
    public String getSessionTime() {
        if (sessionStart == 0L) return "0:00";
        long until = sessionEnd > 0L ? sessionEnd : System.currentTimeMillis();
        long seconds = (until - sessionStart) / 1000L;
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** Is a buying session running? Drives the START/STOP button in the AutoMarket window. */
    public boolean isBuying() {
        return autoBuy.getValue();
    }

    /**
     * START/STOP from the AutoMarket window. Goes through the same start/stop path as the keybind, and
     * refreshes the AutoBuy edge tracker so onUpdate does not immediately fire a second session.
     */
    public void toggleBuying() {
        if (autoBuy.getValue()) stopBuyingSession();
        else startBuying();
        lastAutoBuy = autoBuy.getValue();
    }

    /**
     * Ends the buying session without touching auto-sell. Escape out of the market means "I'm done
     * buying" — and it has to actually end the session, because AutoOpenAh would otherwise just type
     * /ah again 800ms later and the market would spring back open in your face.
     */
    public void stopBuyingSession() {
        if (!autoBuy.getValue()) return;
        autoBuy.setValue(false);
        lastAutoBuy = false;
        sessionEnd = System.currentTimeMillis();
        stopAfterConfirm = false;
        resetBuyState();
        closingSyncId = -1;
        if (notify.getValue()) sendMessage("Stop kupowania");
    }

    /** Whether scrolling over a market item should open the quick-offer screen (used by MixinHandledScreen). */
    public boolean scrollOffer() {
        return quickList.getValue();
    }

    /**
     * Runs the sniper the moment the server delivers a new page instead of on the next tick.
     * <p>
     * This is a ReceivePost handler for a reason: it fires at the TAIL of packet handling, by which
     * point the packet has already queued its own application onto the client thread — so the task we
     * queue here runs after it and sees the contents this packet just delivered. On Receive (HEAD) we
     * would be looking at the previous page.
     */
    @EventHandler
    @SuppressWarnings("unused")
    public void onPacketPost(PacketEvent.@NotNull Receive e) {
        if (!(e.getPacket() instanceof InventoryS2CPacket
                || e.getPacket() instanceof ScreenHandlerSlotUpdateS2CPacket
                || e.getPacket() instanceof OpenScreenS2CPacket)) return;

        // Two hops for the same reason the sniper uses them: this fires before the packet is applied, so
        // reading the window now would read the previous one.
        if (watchOthers.getValue() && mc != null)
            mc.execute(() -> mc.execute(this::snapshotOffer));

        if (!autoBuy.getValue()) return;
        contentsDirty = true;
        // One page refresh is ~54 slot packets. Scheduling a task per packet queued 54 runs of the state
        // machine onto the client thread for one page — most of them rejected, all of them costing frame
        // time. Coalesce them into a single pending run: that is where the stutter came from.
        if (mc != null && !runScheduled) {
            runScheduled = true;
            // Two hops on purpose. This fires BEFORE the packet is applied, and applying it is itself a
            // task on the client thread — so a single execute() would run us first and we would scan the
            // previous page. The outer task queues the inner one while the queue is already draining,
            // which puts us behind the packet's own task and therefore on fresh contents.
            mc.execute(() -> mc.execute(() -> {
                runScheduled = false;
                doSniper();
            }));
        }
    }

    @EventHandler
    public void onPacketReceive(PacketEvent.@NotNull Receive e) {
        if (!(e.getPacket() instanceof GameMessageS2CPacket msg)) return;
        String line;
        try {
            line = msg.content().getString();
        } catch (Exception ex) {
            return;
        }
        if (line == null || line.isEmpty()) return;

        String plain = Formatting.strip(line);
        if (plain == null) return;

        // "Następny raz możesz to wykonać za 250ms!" — the market's rate limiter talking. Obey it to the
        // millisecond: this is the difference between two purchases per session and a session that buys.
        Matcher cd = SERVER_COOLDOWN.matcher(plain);
        if (cd.find()) {
            try {
                long ms = Long.parseLong(cd.group(1));
                cooldownUntil = System.currentTimeMillis() + ms + 30L; // small margin for jitter
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        // "Pomyślnie zakupiłeś produkt za 1350$" — the only statement that a purchase really happened,
        // and the only place the price actually charged appears (it is not the lore price).
        Matcher bought = SERVER_BOUGHT.matcher(plain);
        if (bought.find()) {
            onServerBought(parseNumber(bought.group(1)));
            return;
        }

        // "Produkt ten został już prawdopodobnie usunięty albo sprzedany" — lost the race; drop it so the
        // next real purchase cannot be logged under this item's name.
        if (SERVER_GONE.matcher(plain).find()) clearPending();
    }

    private static long parseNumber(String raw) {
        if (raw == null) return 0;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String getDisplayInfo() {
        if (autoSell.getValue()) return "Sell";
        if (!autoBuy.getValue()) return null;
        return switch (buyState) {
            case IDLE, OPENING -> "Buy";
            case SORTING -> "Sort";
            case REFRESHING -> "Refresh";
            case SNIPING -> "Snipe";
        };
    }
}
