package thunder.hack.gui.mainmenu;

import com.mojang.authlib.minecraft.UserApiService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.client.session.report.AbuseReportContext;
import net.minecraft.client.session.report.ReporterEnvironment;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.Uuids;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.PremiumStore;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.injection.accesors.IMinecraftClient;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static thunder.hack.features.modules.Module.mc;

/** Built-in account switcher: Offline (nick), Microsoft (premium, device-code login) and the bundled IAS. */
public class AccountSwitcherScreen extends Screen {
    private static final Color PANEL   = new Color(17, 14, 25, 238);
    private static final Color CARD    = new Color(26, 22, 38, 205);
    private static final Color CARD_H  = new Color(42, 34, 62, 235);
    private static final Color FIELD   = new Color(11, 9, 18, 235);
    private static final Color ACCENT  = new Color(168, 85, 247);
    private static final Color MS      = new Color(0x2F, 0x8F, 0x4E);
    private static final Color GREEN   = new Color(120, 220, 130);
    private static final Color TEXT    = new Color(236, 236, 243);
    private static final Color DIM     = new Color(150, 150, 166);
    private static final Color FAINT   = new Color(110, 110, 128);
    private static final Color PASS    = new Color(196, 166, 240);

    private static final float PANEL_W = 430f, PANEL_H = 430f;
    private static final float PAD = 18f;
    private static final float ROW_H = 36f, ROW_GAP = 6f;

    /**
     * Zapisane konto offline. {@code pass} to hasło do /login na serwerach non-premium — trzymamy je
     * przy koncie i pokazujemy JAWNIE na liście, bo cały sens jest taki, żeby dało się je podejrzeć,
     * kiedy się zapomni. Puste = konto bez hasła.
     */
    private static final class Account {
        final String nick;
        String pass;

        Account(String nick, String pass) {
            this.nick = nick;
            this.pass = pass == null ? "" : pass;
        }
    }

    private final Screen parent;
    private final List<Account> accounts = new ArrayList<>();
    private final List<PremiumStore.Entry> premiums = new ArrayList<>();
    /** Hit-boxy wierszy premium (klik = zaloguj), przeliczane przy każdym rysowaniu. */
    private final List<float[]> rPremiumRow = new ArrayList<>();
    private String input = "";
    private String passInput = "";
    /** 0 = pole nicku, 1 = pole hasła. */
    private int focus = 0;
    private volatile String status = "";
    private float scroll;

    // hit rects, filled while rendering
    private float px, py;
    private float[] rField = z(), rPass = z(), rAdd = z(), rMicrosoft = z(), rIas = z();
    /** Hit-boxy haseł na liście (klik = kopiuj), przeliczane przy każdym rysowaniu. */
    private final List<float[]> rPassCopy = new ArrayList<>();
    private float listTop, listBottom;

    private static float[] z() {
        return new float[]{-1, -1, 0, 0};
    }

    public AccountSwitcherScreen(Screen parent) {
        super(Text.of("AccountSwitcher"));
        this.parent = parent;
        load();
        reloadPremiums();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Rendering                                                           */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        float w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();
        px = (w - PANEL_W) / 2f;
        py = (h - PANEL_H) / 2f;

        Render2DEngine.drawRound(context, 0, 0, w, h, 0f, new Color(0, 0, 0, 120));
        Render2DEngine.drawBlurredShadow(context, px, py, PANEL_W, PANEL_H, 18, new Color(0, 0, 0, 170));
        Render2DEngine.drawRound(context, px, py, PANEL_W, PANEL_H, 14f, PANEL);
        Render2DEngine.drawRound(context, px + PANEL_W / 2f - 30, py + 1.5f, 60, 3, 1.5f, ACCENT);

        String current = mc.getSession() != null ? mc.getSession().getUsername() : "-";

        // ---- header ----
        FontRenderers.monsterrat.drawString(context, "ACCOUNTS", px + PAD, py + 16, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, accounts.size() + " zapisanych", px + PAD, py + 30, FAINT.getRGB());

        // ---- active account card ----
        float cardY = py + 46, cardH = 52, cardW = PANEL_W - PAD * 2;
        Render2DEngine.drawRound(context, px + PAD, cardY, cardW, cardH, 10f, CARD);
        Render2DEngine.drawRound(context, px + PAD, cardY + 8, 3.5f, cardH - 16, 1.75f, GREEN);
        drawHead(context, current, px + PAD + 14, cardY + 9, 34);
        FontRenderers.monsterrat.drawString(context, current, px + PAD + 58, cardY + 15, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, "aktywne konto", px + PAD + 58, cardY + 30, GREEN.getRGB());

        // ---- add offline: nick + hasło + button ----
        float rowY = cardY + cardH + 12, fieldH = 30;
        float addW = 84, gap = 6;
        float inputsW = cardW - addW - gap;
        float nickW = 148, passW = inputsW - nickW - gap;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;

        drawField(context, px + PAD, rowY, nickW, fieldH, input, "Nick...", focus == 0, blink, mouseX, mouseY);
        rField = new float[]{px + PAD, rowY, nickW, fieldH};

        float passX = px + PAD + nickW + gap;
        drawField(context, passX, rowY, passW, fieldH, passInput, "Hasło (opcjonalne)", focus == 1, blink, mouseX, mouseY);
        rPass = new float[]{passX, rowY, passW, fieldH};

        float addX = px + PAD + inputsW + gap;
        boolean addHov = Render2DEngine.isHovered(mouseX, mouseY, addX, rowY, addW, fieldH);
        if (addHov) Render2DEngine.drawBlurredShadow(context, addX, rowY, addW, fieldH, 10, new Color(168, 85, 247, 70));
        Render2DEngine.drawRound(context, addX, rowY, addW, fieldH, 8f, addHov ? ACCENT : Render2DEngine.injectAlpha(ACCENT, 175));
        FontRenderers.sf_medium.drawCenteredString(context, "+ Offline", addX + addW / 2f, rowY + fieldH / 2f - 4, Color.WHITE.getRGB());
        rAdd = new float[]{addX, rowY, addW, fieldH};

        // ---- microsoft + IAS ----
        float actY = rowY + fieldH + 8, actH = 28, actW = (cardW - 8) / 2f;
        boolean msHov = Render2DEngine.isHovered(mouseX, mouseY, px + PAD, actY, actW, actH);
        Render2DEngine.drawRound(context, px + PAD, actY, actW, actH, 8f, msHov ? MS.brighter() : MS);
        FontRenderers.sf_medium.drawCenteredString(context, "+ Microsoft", px + PAD + actW / 2f, actY + actH / 2f - 4, Color.WHITE.getRGB());
        rMicrosoft = new float[]{px + PAD, actY, actW, actH};

        float iasX = px + PAD + actW + 8;
        boolean iasOn = iasLoaded();
        boolean iasHov = iasOn && Render2DEngine.isHovered(mouseX, mouseY, iasX, actY, actW, actH);
        Render2DEngine.drawRound(context, iasX, actY, actW, actH, 8f, iasHov ? CARD_H : CARD);
        Render2DEngine.drawRound(context, iasX, actY + 6, 3f, actH - 12, 1.5f, iasOn ? ACCENT : FAINT);
        FontRenderers.sf_medium.drawCenteredString(context, iasOn ? "IAS switcher" : "IAS (brak)", iasX + actW / 2f, actY + actH / 2f - 4, (iasOn ? TEXT : FAINT).getRGB());
        rIas = new float[]{iasX, actY, actW, actH};

        // ---- status ----
        float listLabelY = actY + actH + 10;
        if (!status.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context, status, px + PAD, listLabelY, new Color(212, 190, 245).getRGB());
            listLabelY += 14;
        }

        // ---- saved accounts ----
        FontRenderers.sf_medium_mini.drawString(context, "ZAPISANE (klik = przełącz, klik w hasło = kopiuj)", px + PAD, listLabelY, FAINT.getRGB());
        listTop = listLabelY + 14;
        listBottom = py + PANEL_H - 22;
        clampScroll();

        rPassCopy.clear();
        float y = listTop - scroll;
        for (Account acc : accounts) {
            if (y + ROW_H > listTop - 1 && y < listBottom)
                drawAccountRow(context, acc, px + PAD, y, cardW, acc.nick.equalsIgnoreCase(current), mouseX, mouseY);
            else
                rPassCopy.add(z());
            y += ROW_H + ROW_GAP;
        }
        if (accounts.isEmpty()) {
            FontRenderers.sf_medium.drawString(context, "Brak zapisanych kont offline", px + PAD, y + 8, FAINT.getRGB());
            y += 24;
        }

        // ---- konta premium (Microsoft) ----
        rPremiumRow.clear();
        if (!premiums.isEmpty()) {
            y += 6;
            if (y + 12 > listTop - 1 && y < listBottom)
                FontRenderers.sf_medium_mini.drawString(context, "PREMIUM (klik = zaloguj bez hasła)", px + PAD, y, new Color(120, 190, 255).getRGB());
            y += 16;
            for (PremiumStore.Entry e : premiums) {
                if (y + ROW_H > listTop - 1 && y < listBottom)
                    drawPremiumRow(context, e, px + PAD, y, cardW, e.nick().equalsIgnoreCase(current), mouseX, mouseY);
                else
                    rPremiumRow.add(z());
                y += ROW_H + ROW_GAP;
            }
        }

        FontRenderers.sf_medium_mini.drawString(context, "ESC — powrót   ·   TAB — nick / hasło", px + PAD, py + PANEL_H - 14, FAINT.getRGB());
    }

    /** Wiersz konta premium: awatar, nick, plakietka PREMIUM i status zapamiętanego tokenu. */
    private void drawPremiumRow(DrawContext ctx, PremiumStore.Entry e, float x, float y, float width, boolean active, float mx, float my) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, width, ROW_H) && my > listTop && my < listBottom;
        Render2DEngine.drawRound(ctx, x, y, width, ROW_H, 8f, hov ? CARD_H : CARD);
        Color tag = new Color(90, 170, 255);
        Render2DEngine.drawRound(ctx, x, y + 7, 3f, ROW_H - 14, 1.5f, active ? GREEN : tag);

        drawHead(ctx, e.nick(), x + 12, y + 8, 20);
        FontRenderers.sf_medium.drawString(ctx, e.nick(), x + 42, y + 8, (active ? GREEN : TEXT).getRGB());

        boolean canRelog = e.refreshToken() != null && !e.refreshToken().isBlank();
        String sub = canRelog ? "zapamiętane · klik = zaloguj" : "brak tokenu · zaloguj przez Microsoft";
        FontRenderers.sf_medium_mini.drawString(ctx, sub, x + 42, y + 21, (canRelog ? new Color(150, 200, 255) : FAINT).getRGB());

        // plakietka PREMIUM po prawej
        String badge = "PREMIUM";
        float bw = FontRenderers.sf_medium_mini.getStringWidth(badge) + 12;
        Render2DEngine.drawRound(ctx, x + width - bw - 10, y + ROW_H / 2f - 8, bw, 16, 5f, Render2DEngine.injectAlpha(tag, 45));
        FontRenderers.sf_medium_mini.drawString(ctx, badge, x + width - bw - 4, y + ROW_H / 2f - 4, tag.getRGB());

        rPremiumRow.add(new float[]{x, y, width, ROW_H});
    }

    /** Pojedyncze pole tekstowe panelu (nick / hasło). */
    private void drawField(DrawContext ctx, float x, float y, float w, float h, String value, String placeholder,
                           boolean focused, boolean blink, float mx, float my) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, w, h);
        Render2DEngine.drawRound(ctx, x, y, w, h, 8f, FIELD);
        Render2DEngine.drawRound(ctx, x, y, w, h, 8f,
                new Color(168, 85, 247, focused ? 95 : (hov ? 60 : 32)));

        String shown = value.isEmpty()
                ? placeholder
                : value + (focused && blink ? "_" : "");
        // długie hasło przycinamy od lewej, żeby zawsze było widać koniec, który się właśnie pisze
        while (FontRenderers.sf_medium.getStringWidth(shown) > w - 18 && shown.length() > 1)
            shown = shown.substring(1);
        FontRenderers.sf_medium.drawString(ctx, shown, x + 10, y + h / 2f - 4,
                (value.isEmpty() ? FAINT : TEXT).getRGB());
    }

    private void drawAccountRow(DrawContext ctx, Account acc, float x, float y, float width, boolean active, float mx, float my) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, width, ROW_H) && my > listTop && my < listBottom;
        Render2DEngine.drawRound(ctx, x, y, width, ROW_H, 8f, hov ? CARD_H : CARD);
        if (active) Render2DEngine.drawRound(ctx, x, y + 7, 3f, ROW_H - 14, 1.5f, GREEN);

        drawHead(ctx, acc.nick, x + 12, y + 8, 20);
        FontRenderers.sf_medium.drawString(ctx, acc.nick, x + 42, y + 8, (active ? GREEN : TEXT).getRGB());

        // hasło JAWNIE — po to tu jest, żeby dało się je odczytać po zapomnieniu
        boolean has = !acc.pass.isEmpty();
        String pw = has ? acc.pass : "brak hasła";
        float pwW = FontRenderers.sf_medium_mini.getStringWidth(pw);
        boolean pwHov = has && Render2DEngine.isHovered(mx, my, x + 42, y + 19, pwW + 4, 12)
                && my > listTop && my < listBottom;
        FontRenderers.sf_medium_mini.drawString(ctx, pw, x + 42, y + 21,
                (has ? (pwHov ? Color.WHITE : PASS) : FAINT).getRGB());
        if (pwHov)
            FontRenderers.sf_medium_mini.drawString(ctx, "kliknij = kopiuj", x + 42 + pwW + 8, y + 21, FAINT.getRGB());
        rPassCopy.add(has ? new float[]{x + 42, y + 19, pwW + 4, 12} : z());

        float xx = x + width - 18;
        boolean delHov = Render2DEngine.isHovered(mx, my, xx - 5, y + 8, 18, 18);
        FontRenderers.sf_medium.drawString(ctx, "x", xx, y + ROW_H / 2f - 5, (delHov ? new Color(235, 110, 110) : FAINT).getRGB());
    }

    /** Head + hat layer: the real skin for the account we are signed in on, default skins for the rest. */
    private void drawHead(DrawContext ctx, String name, float x, float y, float size) {
        Identifier skin = thunder.hack.utility.render.HeadTextures.of(name, mc.getGameProfile());
        if (skin == null) {
            Render2DEngine.drawRound(ctx, x, y, size, size, 5f, new Color(120, 80, 200));
            return;
        }
        thunder.hack.utility.render.HeadTextures.draw(ctx, skin, x, y, size);
    }

    private void clampScroll() {
        float content = accounts.size() * (ROW_H + ROW_GAP);
        if (!premiums.isEmpty()) content += 22 + premiums.size() * (ROW_H + ROW_GAP);
        float view = listBottom - listTop;
        float max = Math.max(0, content - view);
        scroll = Math.max(0, Math.min(scroll, max));
    }

    /* ------------------------------------------------------------------ */
    /* Actions                                                             */
    /* ------------------------------------------------------------------ */

    private void addAccount(String name) {
        addAccount(name, "");
    }

    /**
     * Dodaje konto, a jeśli już istnieje — nadpisuje hasło (o ile podano nowe). Dzięki temu zapisanie
     * hasła do konta, które już jest na liście, to wpisanie nicku + hasła i "+ Offline".
     */
    private void addAccount(String name, String pass) {
        if (name == null) return;
        final String n = name.trim();
        final String p = pass == null ? "" : pass.trim();
        if (n.isEmpty() || n.length() > 16) return;

        for (Account a : accounts)
            if (a.nick.equalsIgnoreCase(n)) {
                if (!p.isEmpty() && !p.equals(a.pass)) {
                    a.pass = p;
                    save();
                    status = "Zapisano hasło dla: " + a.nick;
                }
                return;
            }

        accounts.add(new Account(n, p));
        save();
    }

    private void doSwitchOffline(String name) {
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || name.length() > 16) return;
        try {
            Session session = new Session(name, Uuids.getOfflinePlayerUuid(name), "", Optional.empty(), Optional.empty());
            IMinecraftClient mca = (IMinecraftClient) mc;
            mca.setSessionT(session);
            mc.getGameProfile().properties().clear();
            UserApiService api = UserApiService.OFFLINE;
            mca.setUserApiService(api);
            mca.setSocialInteractionsManagerT(new SocialInteractionsManager(mc, api));
            mca.setProfileKeys(ProfileKeys.create(api, session, mc.runDirectory.toPath()));
            mca.setAbuseReportContextT(AbuseReportContext.create(ReporterEnvironment.ofIntegratedServer(), api));
            thunder.hack.utility.render.HeadTextures.invalidate();
            addAccount(name);
            status = "Przełączono na: " + name;
        } catch (Exception ignored) {
        }
    }

    /** Premium session — sets the real access token so servers accept the join. */
    private void setPremiumSession(String name, UUID uuid, String mcToken, String refreshToken) {
        try {
            Session session = new Session(name, uuid, mcToken, Optional.empty(), Optional.empty());
            IMinecraftClient mca = (IMinecraftClient) mc;
            mca.setSessionT(session);
            mc.getGameProfile().properties().clear();
            thunder.hack.utility.render.HeadTextures.invalidate();
            // Zapamietaj konto premium (nick + uuid + refresh token) — zeby dalo sie na nie wrocic pozniej.
            thunder.hack.core.manager.client.PremiumStore.save(name, uuid, refreshToken);
            reloadPremiums();
            status = "Zalogowano (premium): " + name;
        } catch (Exception e) {
            status = "Błąd sesji: " + e.getMessage();
        }
    }

    /** Ponowne wejście na zapisane konto premium — z refresh tokenu, bez hasła i kodu. */
    private void doSwitchPremium(PremiumStore.Entry e) {
        if (e.refreshToken() == null || e.refreshToken().isBlank()) {
            status = "To konto nie ma zapisanego tokenu — zaloguj przez Microsoft";
            startMicrosoft();
            return;
        }
        status = "Loguję na " + e.nick() + "...";
        MicrosoftAuth.refreshAsync(e.refreshToken(), new MicrosoftAuth.Callback() {
            public void onCode(String userCode, String verificationUri) {
            }

            public void onStatus(String msg) {
                status = "Premium: " + msg;
            }

            public void onSuccess(String name, UUID uuid, String mcToken, String refreshToken) {
                mc.execute(() -> setPremiumSession(name, uuid, mcToken, refreshToken));
            }

            public void onError(String msg) {
                status = "Błąd: " + msg;
            }
        });
    }

    private void reloadPremiums() {
        premiums.clear();
        premiums.addAll(PremiumStore.load());
    }

    private static boolean iasLoaded() {
        return FabricLoader.getInstance().isModLoaded("ias");
    }

    /** IAS ships as a nested jar, so it is not on our compile classpath - reach its screen reflectively. */
    private void openIas() {
        if (!iasLoaded()) {
            status = "IAS nie jest załadowany";
            return;
        }
        try {
            Class<?> screen = Class.forName("ru.vidtu.ias.screen.AccountScreen");
            mc.setScreen((Screen) screen.getConstructor(Screen.class).newInstance(this));
        } catch (Throwable t) {
            status = "Nie mogę otworzyć IAS: " + t.getClass().getSimpleName();
        }
    }

    private void startMicrosoft() {
        status = "Microsoft: otwieram przeglądarkę...";
        MicrosoftAuth.loginAsync(new MicrosoftAuth.Callback() {
            public void onCode(String userCode, String verificationUri) {
                status = "Wpisz kod: " + userCode + "  (skopiowany, w przeglądarce)";
                try {
                    mc.keyboard.setClipboard(userCode);
                    Util.getOperatingSystem().open(new URI(verificationUri));
                } catch (Exception ignored) {
                }
            }

            public void onStatus(String msg) {
                status = "Microsoft: " + msg;
            }

            public void onSuccess(String name, UUID uuid, String mcToken, String refreshToken) {
                mc.execute(() -> setPremiumSession(name, uuid, mcToken, refreshToken));
            }

            public void onError(String msg) {
                status = "Błąd: " + msg;
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        float mx = (float) click.x(), my = (float) click.y();

        if (hovered(mx, my, rField)) {
            focus = 0;
            return true;
        }
        if (hovered(mx, my, rPass)) {
            focus = 1;
            return true;
        }
        if (hovered(mx, my, rAdd)) {
            addAccount(input, passInput);
            input = "";
            passInput = "";
            focus = 0;
            return true;
        }
        if (hovered(mx, my, rMicrosoft)) {
            startMicrosoft();
            return true;
        }
        if (hovered(mx, my, rIas)) {
            openIas();
            return true;
        }

        if (my > listTop && my < listBottom) {
            float y = listTop - scroll;
            float width = PANEL_W - PAD * 2;
            for (int i = 0; i < accounts.size(); i++, y += ROW_H + ROW_GAP) {
                if (!Render2DEngine.isHovered(mx, my, px + PAD, y, width, ROW_H)) continue;
                if (Render2DEngine.isHovered(mx, my, px + PAD + width - 23, y + 8, 18, 18)) {
                    accounts.remove(i);
                    save();
                } else if (i < rPassCopy.size() && hovered(mx, my, rPassCopy.get(i))) {
                    mc.keyboard.setClipboard(accounts.get(i).pass);
                    status = "Skopiowano hasło: " + accounts.get(i).nick;
                } else {
                    doSwitchOffline(accounts.get(i).nick);
                }
                return true;
            }
            // wiersze premium — klik loguje z zapisanego tokenu
            for (int i = 0; i < premiums.size() && i < rPremiumRow.size(); i++) {
                if (hovered(mx, my, rPremiumRow.get(i))) {
                    doSwitchPremium(premiums.get(i));
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll -= (float) vertical * 18f;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput in) {
        int key = in.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            mc.setScreen(parent);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            focus = focus == 0 ? 1 : 0;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (focus == 1) {
                if (!passInput.isEmpty()) passInput = passInput.substring(0, passInput.length() - 1);
            } else if (!input.isEmpty()) {
                input = input.substring(0, input.length() - 1);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            // Enter w polu nicku przeskakuje do hasła, dopiero drugi Enter zapisuje konto
            if (focus == 0 && !input.isEmpty() && passInput.isEmpty()) {
                focus = 1;
                return true;
            }
            addAccount(input, passInput);
            input = "";
            passInput = "";
            focus = 0;
            return true;
        }
        return super.keyPressed(in);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput in) {
        char c = (char) in.codepoint();
        if (focus == 1) {
            // hasło do /login bywa dowolne — blokujemy tylko separator pliku i znaki sterujące
            if (c >= ' ' && c != '|' && passInput.length() < 32) {
                passInput += c;
                return true;
            }
            return false;
        }
        if ((Character.isLetterOrDigit(c) || c == '_') && input.length() < 16) {
            input += c;
            return true;
        }
        return false;
    }

    private static boolean hovered(float mx, float my, float[] r) {
        return Render2DEngine.isHovered(mx, my, r[0], r[1], r[2], r[3]);
    }

    /* ------------------------------------------------------------------ */
    /* Persistence                                                         */
    /* ------------------------------------------------------------------ */

    private Path file() {
        return mc.runDirectory.toPath().resolve("gombal").resolve("accounts.txt");
    }

    /** Format: {@code nick|hasło}. Stare pliki (sam nick, bez kreski) wczytują się jako konto bez hasła. */
    private void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                int bar = s.indexOf('|');
                String nick = bar < 0 ? s : s.substring(0, bar).trim();
                String pass = bar < 0 ? "" : s.substring(bar + 1);
                if (nick.isEmpty()) continue;
                if (accounts.stream().noneMatch(a -> a.nick.equalsIgnoreCase(nick)))
                    accounts.add(new Account(nick, pass));
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            for (Account a : accounts)
                lines.add(a.pass.isEmpty() ? a.nick : a.nick + "|" + a.pass);
            Files.write(f, lines);
        } catch (Exception ignored) {
        }
    }
}
