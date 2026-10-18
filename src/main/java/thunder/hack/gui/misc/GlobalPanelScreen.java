package thunder.hack.gui.misc;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import thunder.hack.core.manager.client.AppLink;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The owner's control panel, in the game: every UID with its Discord avatar and name, rank and ban (with the
 * reason), banning / unbanning with a reason, and publishing an update (for everybody or chosen UIDs, with the
 * message players see when their game starts).
 *
 * <p>It drives the GlobalDLC App running on this computer through its admin API (localhost only - nobody
 * else can ban or publish). All requests run off the render thread.
 */
public class GlobalPanelScreen extends Screen {
    private record User(String uid, String rank, boolean banned, String reason, String discord) {
    }

    private final List<User> users = new ArrayList<>();
    private final Map<String, Identifier> avatars = new HashMap<>();
    private final Map<String, Boolean> avatarAsked = new HashMap<>();
    private String info = "";
    private String status = "Łączę z GlobalDLC App...";
    private boolean online;
    private int selected = -1;
    private float scroll;

    private TextFieldWidget reason;
    private TextFieldWidget targets;
    private EditBoxWidget message;

    private float px, py, pw, ph, listX, listY, listW, listH;
    private static final float ROW = 24;

    public GlobalPanelScreen() {
        super(Text.literal("GlobalPanel"));
    }

    @Override
    protected void init() {
        pw = Math.min(width - 20, 620);
        ph = Math.min(height - 20, 360);
        px = width / 2f - pw / 2;
        py = height / 2f - ph / 2;
        listX = px + 12;
        listY = py + 40;
        listW = pw * 0.55f - 18;
        listH = ph - 40 - 62;

        int rx = (int) (px + pw * 0.55f + 4), rw = (int) (pw * 0.45f - 16);
        int by = (int) (listY + listH + 6);
        reason = new TextFieldWidget(textRenderer, (int) listX, by, (int) listW, 16, Text.literal("Powód"));
        reason.setPlaceholder(Text.literal("powód bana..."));
        reason.setMaxLength(200);
        addDrawableChild(reason);
        int half = (int) (listW / 2 - 3);
        addDrawableChild(ButtonWidget.builder(Text.literal("Zbanuj"), b -> ban()).dimensions((int) listX, by + 20, half, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Odbanuj"), b -> unban()).dimensions((int) listX + half + 6, by + 20, half, 18).build());

        targets = new TextFieldWidget(textRenderer, rx, (int) listY + 12, rw, 16, Text.literal("Dla kogo"));
        targets.setPlaceholder(Text.literal("puste = wszyscy, albo 1, 5"));
        targets.setMaxLength(300);
        addDrawableChild(targets);
        addDrawableChild(ButtonWidget.builder(Text.literal("Tylko zaznaczony"), b -> {
            if (selected >= 0 && selected < users.size()) targets.setText(users.get(selected).uid());
        }).dimensions(rx, (int) listY + 32, rw, 16).build());
        message = EditBoxWidget.builder().placeholder(Text.literal("co dodano...")).x(rx).y((int) listY + 64)
                .build(textRenderer, rw, (int) (listH - 64 + 26), Text.literal("Wiadomość"));
        message.setMaxLength(2000);
        addDrawableChild(message);
        addDrawableChild(ButtonWidget.builder(Text.literal("Wydaj aktualizację"), b -> publish())
                .dimensions(rx, by + 20, rw, 18).build());

        reload();
    }

    private void reload() {
        CompletableFuture.runAsync(() -> {
            String u = AppLink.admin("/api/admin/users", null);
            String i = AppLink.admin("/api/admin/info", null);
            client.execute(() -> {
                if (u == null || u.startsWith("ERR")) {
                    online = false;
                    status = u == null ? "Nie widzę GlobalDLC App - włącz ją na tym komputerze." : "App odmówiła: " + u;
                    return;
                }
                online = true;
                users.clear();
                for (String line : u.split("\n")) {
                    String[] p = line.split("\\|", -1);
                    if (p.length >= 4 && !p[0].isBlank())
                        users.add(new User(p[0], p[1], p[2].equals("1"), p[3], p.length > 4 ? p[4] : ""));
                }
                info = i == null ? "" : i;
                status = users.size() + " UID  ·  połączono z aplikacją";
            });
        });
    }

    private User sel() {
        return selected >= 0 && selected < users.size() ? users.get(selected) : null;
    }

    private void ban() {
        User u = sel();
        if (u == null) {
            status = "Zaznacz UID na liście.";
            return;
        }
        String r = reason.getText().trim();
        status = "Banuję " + u.uid() + "...";
        CompletableFuture.runAsync(() -> {
            String res = AppLink.admin("/api/admin/ban?uid=" + enc(u.uid()), r);
            client.execute(() -> {
                status = res != null && !res.startsWith("ERR") ? "Zbanowano " + u.uid() + (r.isEmpty() ? "" : " - " + r) : "Nie udało się: " + res;
                reason.setText("");
                reload();
            });
        });
    }

    private void unban() {
        User u = sel();
        if (u == null) {
            status = "Zaznacz UID na liście.";
            return;
        }
        CompletableFuture.runAsync(() -> {
            String res = AppLink.admin("/api/admin/unban?uid=" + enc(u.uid()), "");
            client.execute(() -> {
                status = res != null && !res.startsWith("ERR") ? "Odbanowano " + u.uid() : "Nie udało się: " + res;
                reload();
            });
        });
    }

    private void publish() {
        String msg = message.getText().trim();
        if (msg.isEmpty()) {
            status = "Napisz najpierw, co dodałeś.";
            return;
        }
        String who = targets.getText().trim();
        status = "Wydaję aktualizację...";
        CompletableFuture.runAsync(() -> {
            String res = AppLink.admin("/api/admin/publish?targets=" + enc(who), msg);
            client.execute(() -> {
                if (res != null && res.startsWith("release=")) {
                    status = "Wydano #" + res.substring(8).trim() + " dla: " + (who.isEmpty() ? "wszyscy" : who);
                    message.setText("");
                } else {
                    status = "Nie udało się wydać: " + res;
                }
                reload();
            });
        });
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Fetches each UID's avatar once, registers it as a texture. */
    private Identifier avatar(String uid) {
        Identifier id = avatars.get(uid);
        if (id != null || avatarAsked.containsKey(uid)) return id;
        avatarAsked.put(uid, true);
        CompletableFuture.runAsync(() -> {
            byte[] png = AppLink.avatarBytes(uid);
            if (png == null) return;
            client.execute(() -> {
                try (var in = new java.io.ByteArrayInputStream(png)) {
                    NativeImage img = NativeImage.read(in);
                    Identifier tex = Identifier.of("gombal", "panel_avatar_" + Integer.toHexString(uid.hashCode()));
                    client.getTextureManager().registerTexture(tex, new NativeImageBackedTexture(tex::toString, img));
                    avatars.put(uid, tex);
                } catch (Exception ignored) {
                }
            });
        });
        return null;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        Color accent = HudEditor.getColor(0);
        Render2DEngine.drawBlurredShadow(context, px, py, pw, ph, 14, new Color(0, 0, 0, 160));
        Render2DEngine.drawRound(context, px, py, pw, ph, 8, new Color(16, 17, 24, 238));
        Render2DEngine.drawRound(context, px, py, pw, 3, 1.5f, accent);
        FontRenderers.sf_bold.drawString(context, "GLOBAL PANEL", px + 12, py + 10, accent.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, status, px + 12, py + 25, online ? new Color(150, 220, 160).getRGB() : new Color(255, 140, 140).getRGB());

        // ---- UID list ----
        Render2DEngine.drawRound(context, listX, listY, listW, listH, 5, new Color(24, 25, 34, 230));
        context.enableScissor((int) listX, (int) listY, (int) (listX + listW), (int) (listY + listH));
        float maxScroll = Math.max(0, users.size() * ROW - listH + 4);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (int i = 0; i < users.size(); i++) {
            float y = listY + 2 + i * ROW - scroll;
            if (y + ROW < listY || y > listY + listH) continue;
            User u = users.get(i);
            boolean hov = mouseX >= listX && mouseX <= listX + listW && mouseY >= y && mouseY < y + ROW;
            if (i == selected) Render2DEngine.drawRound(context, listX + 2, y, listW - 4, ROW - 2, 4, Render2DEngine.injectAlpha(accent, 70));
            else if (hov) Render2DEngine.drawRound(context, listX + 2, y, listW - 4, ROW - 2, 4, new Color(255, 255, 255, 14));

            Identifier face = avatar(u.uid());
            if (face != null) context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, face, (int) listX + 6, (int) y + 2, 0, 0, 18, 18, 18, 18);
            else Render2DEngine.drawRound(context, listX + 6, y + 2, 18, 18, 9, new Color(60, 62, 78));

            String shortId = u.uid().startsWith("CLOUDAN-") ? u.uid().substring(8).replace("-", "").replaceFirst("^0+(?=.)", "") : u.uid();
            FontRenderers.sf_bold_mini.drawString(context, "UID " + shortId, listX + 30, y + 4,
                    u.banned() ? new Color(255, 110, 110).getRGB() : -1);
            String sub = u.banned() ? "BAN" + (u.reason().isBlank() ? "" : " - " + u.reason()) : u.rank() + (u.discord().isBlank() ? "" : "  ·  " + u.discord());
            FontRenderers.sf_medium_mini.drawString(context, sub, listX + 30, y + 13,
                    u.banned() ? new Color(255, 150, 150).getRGB() : new Color(160, 165, 180).getRGB());
        }
        context.disableScissor();

        // ---- update side ----
        float rx = px + pw * 0.55f + 4;
        FontRenderers.sf_medium_mini.drawString(context, "WYDAJ AKTUALIZACJĘ  ·  dla kogo:", rx, listY + 2, new Color(170, 175, 190).getRGB());
        FontRenderers.sf_medium_mini.drawString(context, "Wiadomość (co dodano):", rx, listY + 54, new Color(170, 175, 190).getRGB());
        String rel = value(info, "release"), tg = value(info, "targets");
        FontRenderers.sf_medium_mini.drawString(context, "Aktualne wydanie: #" + (rel.isEmpty() ? "-" : rel) + "  dla: " + (tg.isEmpty() ? "-" : tg),
                rx, listY + listH + 8, new Color(150, 155, 170).getRGB());

        super.render(context, mouseX, mouseY, delta);
    }

    private static String value(String info, String key) {
        for (String l : info.split("\n")) if (l.startsWith(key + "=")) return l.substring(key.length() + 1).trim();
        return "";
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (mx >= listX && mx <= listX + listW && my >= listY && my <= listY + listH) {
            int i = (int) ((my - listY - 2 + scroll) / ROW);
            if (i >= 0 && i < users.size()) {
                selected = i;
                User u = users.get(i);
                if (u.banned() && !u.reason().isBlank()) reason.setText(u.reason());
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll -= (float) verticalAmount * ROW;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
