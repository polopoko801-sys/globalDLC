package thunder.hack.gui.build;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.misc.AutoBuild;
import thunder.hack.gui.clickui.AbstractElement;
import thunder.hack.gui.clickui.ElementFactory;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.world.Schematic;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static thunder.hack.features.modules.Module.mc;

/**
 * The AutoBuild window: what the schematic will look like, what it costs, and every module setting -
 * opened by right-clicking the AutoBuild card in the ClickGUI or with {@code /build gui}.
 *
 * <p>A plain settings list was the wrong view for this module. Everything AutoBuild does is about a
 * shape you cannot see (a file on disk, a region you selected somewhere else), so the window is built
 * around the picture of it: schematics on the left, the isometric preview in the middle, materials and
 * settings on the right, and the printer controls along the bottom.
 */
public class BuildScreen extends Screen {
    private static final Color BG = new Color(19, 20, 27, 242);
    private static final Color TILE = new Color(28, 29, 39, 235);
    private static final Color CANVAS = new Color(13, 14, 19, 240);
    private static final Color ROW = new Color(25, 26, 35, 225);
    private static final Color ROW_HOV = new Color(38, 40, 53, 240);
    private static final Color TEXT = new Color(235, 236, 242);
    private static final Color DIM = new Color(146, 149, 164);
    private static final Color MUTED = new Color(96, 99, 114);
    private static final Color GREEN = new Color(112, 220, 128);
    private static final Color YELLOW = new Color(230, 200, 110);
    private static final Color RED = new Color(242, 112, 112);
    private static final Color DIVIDER = new Color(255, 255, 255, 16);
    private static final Color ACCENT_FALLBACK = new Color(139, 100, 246);

    private final BuildPreview preview = new BuildPreview();

    private float panelX, panelY, panelW, panelH;
    private static final float HEADER_H = 40f;
    private static final float BOTTOM_H = 40f;
    private static final float LIST_W = 132f;
    private static final float SIDE_W = 176f;
    private static final float ROW_H = 15f;

    /** 0 = materials, 1 = settings. */
    private int tab = 0;

    private List<String> saved = new ArrayList<>();
    private List<AbstractElement> elements;

    private int listScroll, matScroll;
    /** The folder is rescanned while the window is open - files get dropped in with the game running. */
    private long lastScan;
    private float settingsScroll;

    private boolean draggingView, draggingLayer;
    private float canvasX, canvasY, canvasW, canvasH;
    private float layerBarX, layerBarY, layerBarH;
    private float refreshX, refreshY, refreshW;
    private float lastMouseX, lastMouseY;

    private record Button(String label, String action, float x, float y, float w, float h) {
    }

    private final List<Button> buttons = new ArrayList<>();
    private final List<float[]> listRows = new ArrayList<>();
    private String status = "";
    private long statusTime;
    /** File the current schematic came from - .litematic files call themselves "Unnamed" inside. */
    private static String loadedName;

    public BuildScreen() {
        super(Text.of("AutoBuild"));
    }

    @Override
    protected void init() {
        saved = Schematic.getSavedNames();
        elements = ElementFactory.build(ModuleManager.autoBuild);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Theme                                                               */
    /* ------------------------------------------------------------------ */

    /** The client's own accent, computed the way the HUD computes it (ColorMode aware). */
    private static Color accent() {
        try {
            Color c = HudEditor.getColor(0);
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 255);
        } catch (Exception e) {
            return ACCENT_FALLBACK;
        }
    }

    private static Color alpha(Color c, int a) {
        return Render2DEngine.injectAlpha(c, a);
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();

        panelW = Math.min(640f, sw - 20f);
        panelH = Math.min(400f, sh - 20f);
        panelX = (sw - panelW) / 2f;
        panelY = (sh - panelH) / 2f;

        Color ac = accent();

        Render2DEngine.drawRect(context, 0, 0, sw, sh, new Color(0, 0, 0, 120));
        Render2DEngine.drawBlurredShadow(context, panelX, panelY, panelW, panelH, 16, new Color(0, 0, 0, 150));
        Render2DEngine.drawRound(context, panelX - 1f, panelY - 1f, panelW + 2f, panelH + 2f, 10f, alpha(ac, 45));
        Render2DEngine.drawRound(context, panelX, panelY, panelW, panelH, 9f, BG);

        drawHeader(context, mouseX, mouseY, ac);

        float bodyY = panelY + HEADER_H;
        float bodyH = panelH - HEADER_H - BOTTOM_H;

        rescanIfStale();
        drawSchematicList(context, mouseX, mouseY, panelX + 10f, bodyY, LIST_W, bodyH - 6f, ac);

        canvasX = panelX + 10f + LIST_W + 8f;
        canvasY = bodyY;
        canvasW = panelW - LIST_W - SIDE_W - 36f;
        canvasH = bodyH - 6f;
        drawCanvas(context, mouseX, mouseY, ac);

        float sideX = panelX + panelW - 10f - SIDE_W;
        drawSidePanel(context, mouseX, mouseY, sideX, bodyY, SIDE_W, bodyH - 6f, ac, delta);

        drawBottomBar(context, mouseX, mouseY, ac);
    }

    private void drawHeader(DrawContext context, int mouseX, int mouseY, Color ac) {
        Render2DEngine.drawRect(context, panelX + 10f, panelY + HEADER_H - 1f, panelW - 20f, 1f, DIVIDER);

        boolean on = ModuleManager.autoBuild.isEnabled();
        float dotX = panelX + 16f, dotY = panelY + 15f;
        Render2DEngine.drawRound(context, dotX, dotY, 9f, 9f, 4.5f, on ? ac : MUTED);
        FontRenderers.monsterrat.drawString(context, "AUTOBUILD", panelX + 32f, panelY + 10f, TEXT.getRGB());

        Schematic schematic = AutoBuild.schematic;
        String sub;
        if (schematic == null) {
            sub = "brak schematu - wczytaj z listy albo zaznacz /build start + /build end";
        } else {
            Vec3i dim = schematic.getDimensions();
            sub = displayName(schematic) + "  " + dim.getX() + "x" + dim.getY() + "x" + dim.getZ()
                    + "  |  " + schematic.size() + " bl."
                    + (AutoBuild.origin != null ? "  |  " + format(AutoBuild.origin) : "  |  bez pozycji")
                    + (AutoBuild.getTotal() > 0 ? "  |  " + (AutoBuild.getTotal() - AutoBuild.getRemaining()) + "/" + AutoBuild.getTotal() : "");
        }
        FontRenderers.sf_medium_mini.drawString(context, sub, panelX + 32f, panelY + 22f, DIM.getRGB());

        drawProgress(context, ac);

        // status line (right side) - what the last button press actually did
        if (!status.isEmpty() && System.currentTimeMillis() - statusTime < 4000L) {
            float w = FontRenderers.sf_medium_mini.getStringWidth(status);
            FontRenderers.sf_medium_mini.drawString(context, status, panelX + panelW - 30f - w, panelY + 22f, ac.getRGB());
        }

        boolean closeHov = Render2DEngine.isHovered(mouseX, mouseY, panelX + panelW - 26f, panelY + 10f, 16f, 16f);
        thunder.hack.gui.windows.WindowBase.cross(context, panelX + panelW - 18f, panelY + 18f, 9f, closeHov);
    }

    private void drawSchematicList(DrawContext context, int mouseX, int mouseY, float x, float y, float w, float h, Color ac) {
        Render2DEngine.drawRound(context, x, y, w, h, 6f, TILE);
        FontRenderers.sf_medium_mini.drawString(context, "SCHEMATY", x + 8f, y + 7f, MUTED.getRGB());

        // manual rescan, for the case where the folder timestamp never moved (network drives, odd tools)
        String refresh = "odswiez";
        refreshW = FontRenderers.sf_medium_mini.getStringWidth(refresh) + 8f;
        refreshX = x + w - 6f - refreshW;
        refreshY = y + 4f;
        boolean refreshHov = Render2DEngine.isHovered(mouseX, mouseY, refreshX, refreshY, refreshW, 12f);
        FontRenderers.sf_medium_mini.drawString(context, refresh, refreshX + 4f, refreshY + 3f,
                (refreshHov ? ac : MUTED).getRGB());

        float listY = y + 20f;
        float listH = h - 26f;
        int visible = (int) (listH / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, saved.size() - visible)));

        listRows.clear();
        if (saved.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context, "pusto - wrzuc pliki", x + 8f, listY + 6f, MUTED.getRGB());
            FontRenderers.sf_medium_mini.drawString(context, "do folderu ponizej", x + 8f, listY + 15f, MUTED.getRGB());
            return;
        }

        Render2DEngine.addWindow(context, x, listY, x + w, listY + listH, 1.0);
        for (int i = listScroll; i < saved.size() && i < listScroll + visible + 1; i++) {
            String name = saved.get(i);
            float ry = listY + (i - listScroll) * ROW_H;
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x + 4f, ry, w - 8f, ROW_H - 2f);
            boolean current = AutoBuild.schematic != null && (name.equals(loadedName) || name.equals(AutoBuild.schematic.getName()));

            if (hovered || current)
                Render2DEngine.drawRound(context, x + 4f, ry, w - 8f, ROW_H - 2f, 3f, current ? alpha(ac, 60) : ROW_HOV);

            FontRenderers.sf_medium_mini.drawString(context, trim(FontRenderers.sf_medium_mini, name, w - 26f),
                    x + 8f, ry + 3.5f, (current ? TEXT : DIM).getRGB());

            if (hovered)
                FontRenderers.sf_medium_mini.drawString(context, "x", x + w - 12f, ry + 3.5f, RED.getRGB());

            listRows.add(new float[]{x + 4f, ry, w - 8f, ROW_H - 2f, i});
        }
        Render2DEngine.popWindow();

        if (saved.size() > visible) {
            float frac = visible / (float) saved.size();
            float barH = Math.max(12f, listH * frac);
            float barY = listY + (listH - barH) * (listScroll / (float) Math.max(1, saved.size() - visible));
            Render2DEngine.drawRound(context, x + w - 4f, barY, 2f, barH, 1f, alpha(ac, 120));
        }
    }

    private void drawCanvas(DrawContext context, int mouseX, int mouseY, Color ac) {
        Render2DEngine.drawRound(context, canvasX, canvasY, canvasW, canvasH, 6f, CANVAS);

        Render2DEngine.addWindow(context, canvasX + 1f, canvasY + 1f, canvasX + canvasW - 1f, canvasY + canvasH - 1f, 1.0);
        boolean drawn = preview.render(context, canvasX, canvasY, canvasW - 14f, canvasH);
        Render2DEngine.popWindow();

        if (!drawn) {
            FontRenderers.sf_medium.drawCenteredString(context, "brak podgladu", canvasX + canvasW / 2f, canvasY + canvasH / 2f - 10f, MUTED.getRGB());
            FontRenderers.sf_medium_mini.drawCenteredString(context, "wczytaj schemat z listy po lewej",
                    canvasX + canvasW / 2f, canvasY + canvasH / 2f + 2f, MUTED.getRGB());
            return;
        }

        // layer slider - drag it down to look inside the build layer by layer
        layerBarX = canvasX + canvasW - 9f;
        layerBarY = canvasY + 8f;
        layerBarH = canvasH - 16f;
        Render2DEngine.drawRound(context, layerBarX, layerBarY, 3f, layerBarH, 1.5f, alpha(TEXT, 25));

        float frac = preview.getLayer() / (float) Math.max(1, preview.getHeight());
        float handleY = layerBarY + layerBarH * (1f - frac);
        Render2DEngine.drawRound(context, layerBarX - 2f, Math.max(layerBarY, handleY - 3f), 7f, 6f, 3f, ac);
        Render2DEngine.drawRound(context, layerBarX, handleY, 3f, layerBarY + layerBarH - handleY, 1.5f, alpha(ac, 150));

        String hint = "warstwa " + preview.getLayer() + "/" + preview.getHeight()
                + (preview.isThinnedOut() ? "  |  podglad uproszczony (1 kostka = " + preview.getCell() + " bloki)" : "");
        FontRenderers.sf_medium_mini.drawString(context, hint, canvasX + 8f, canvasY + canvasH - 12f, MUTED.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, "LPM obrot  |  PPM przesun  |  kolko zoom",
                canvasX + 8f, canvasY + 6f, alpha(MUTED, 170).getRGB());
    }

    private void drawSidePanel(DrawContext context, int mouseX, int mouseY, float x, float y, float w, float h, Color ac, float delta) {
        Render2DEngine.drawRound(context, x, y, w, h, 6f, TILE);

        float tabW = (w - 16f) / 2f;
        drawTab(context, mouseX, mouseY, x + 8f, y + 6f, tabW, "MATERIALY", 0, ac);
        drawTab(context, mouseX, mouseY, x + 8f + tabW, y + 6f, tabW, "USTAWIENIA", 1, ac);
        Render2DEngine.drawRect(context, x + 8f, y + 22f, w - 16f, 1f, DIVIDER);

        if (tab == 0) drawMaterials(context, x, y + 26f, w, h - 32f, ac);
        else drawSettings(context, mouseX, mouseY, x, y + 26f, w, h - 32f, delta);
    }

    private void drawTab(DrawContext context, int mouseX, int mouseY, float x, float y, float w, String label, int id, Color ac) {
        boolean active = tab == id;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, w, 15f);
        FontRenderers.sf_medium_mini.drawCenteredString(context, label, x + w / 2f, y + 4f,
                (active ? TEXT : hovered ? DIM : MUTED).getRGB());
        if (active) Render2DEngine.drawRect(context, x + w / 2f - 18f, y + 15f, 36f, 1.5f, ac);
    }

    private void drawMaterials(DrawContext context, float x, float y, float w, float h, Color ac) {
        Schematic schematic = AutoBuild.schematic;
        if (schematic == null) {
            FontRenderers.sf_medium_mini.drawString(context, "nic nie wczytane", x + 10f, y + 6f, MUTED.getRGB());
            return;
        }

        List<Map.Entry<Item, Integer>> materials = schematic.getMaterials();
        float rowH = 18f;
        int visible = (int) (h / rowH);
        matScroll = Math.max(0, Math.min(matScroll, Math.max(0, materials.size() - visible)));

        Render2DEngine.addWindow(context, x, y, x + w, y + h, 1.0);
        for (int i = matScroll; i < materials.size() && i < matScroll + visible + 1; i++) {
            Map.Entry<Item, Integer> entry = materials.get(i);
            float ry = y + (i - matScroll) * rowH;

            Render2DEngine.drawRound(context, x + 6f, ry, w - 12f, rowH - 2f, 3f, ROW);
            context.drawItem(new ItemStack(entry.getKey()), (int) (x + 9f), (int) (ry + 1f));

            int have = InventoryUtility.getItemCount(entry.getKey());
            Color color = have >= entry.getValue() ? GREEN : have > 0 ? YELLOW : RED;

            String count = have + "/" + entry.getValue();
            float countW = FontRenderers.sf_medium_mini.getStringWidth(count);
            FontRenderers.sf_medium_mini.drawString(context, count, x + w - 12f - countW, ry + 5f, color.getRGB());

            String name = trim(FontRenderers.sf_medium_mini, entry.getKey().getName().getString(), w - 40f - countW);
            FontRenderers.sf_medium_mini.drawString(context, name, x + 28f, ry + 5f, TEXT.getRGB());
        }
        Render2DEngine.popWindow();

        if (materials.size() > visible) {
            float frac = visible / (float) materials.size();
            float barH = Math.max(12f, h * frac);
            float barY = y + (h - barH) * (matScroll / (float) Math.max(1, materials.size() - visible));
            Render2DEngine.drawRound(context, x + w - 5f, barY, 2f, barH, 1f, alpha(ac, 120));
        }
    }

    private void drawSettings(DrawContext context, int mouseX, int mouseY, float x, float y, float w, float h, float delta) {
        float total = 0f;
        for (AbstractElement el : elements) if (el.isVisible()) total += ElementFactory.height(el);

        float maxScroll = Math.max(0f, total - h);
        settingsScroll = Math.max(-maxScroll, Math.min(0f, settingsScroll));

        Render2DEngine.addWindow(context, x, y, x + w, y + h, 1.0);
        float offY = settingsScroll;
        for (AbstractElement el : elements) {
            if (!el.isVisible()) continue;
            el.setOffsetY(offY);
            el.setX(x + 8f);
            el.setY(y);
            el.setWidth(w - 16f);
            ElementFactory.applyHeight(el);

            float elY = y + offY;
            if (elY + el.getHeight() >= y && elY <= y + h)
                el.render(context, mouseX, mouseY, delta);
            offY += el.getHeight();
        }
        Render2DEngine.popWindow();
    }

    /**
     * A progress bar that means something: the fill is blocks VERIFIED in the world, never attempts sent.
     *
     * <p>The counts underneath split the remainder up, because "412 left" on its own does not tell you
     * whether the printer is working or stuck - 412 left with 200 parked for no material is a different
     * situation from 412 left and nothing parked, and only one of them is fixed by waiting.
     */
    private void drawProgress(DrawContext context, Color ac) {
        int total = AutoBuild.getTotal();
        if (total <= 0) return;

        int done = total - AutoBuild.getRemaining();
        float fraction = Math.max(0f, Math.min(1f, done / (float) total));

        float x = panelX + 32f;
        float y = panelY + 34f;
        float w = panelW - 64f;

        Render2DEngine.drawRound(context, x, y, w, 4f, 2f, ROW);
        if (fraction > 0f)
            Render2DEngine.drawRound(context, x, y, Math.max(4f, w * fraction), 4f, 2f, ac);

        int pending = AutoBuild.pendingByReason().values().stream().mapToInt(Integer::intValue).sum();
        int failed = AutoBuild.getFailedCount();
        int wrong = AutoBuild.getIncorrectCount();

        StringBuilder line = new StringBuilder();
        line.append(Math.round(fraction * 100f)).append("%   ")
                .append(done).append(" / ").append(total)
                .append("   zostalo ").append(AutoBuild.getRemaining());

        if (pending > 0) line.append("   odlozone ").append(pending);
        if (wrong > 0) line.append("   zly stan ").append(wrong);
        if (failed > 0) line.append("   nieudane ").append(failed);

        if (AutoBuild.isPreparing()) line.append("   (przygotowuje...)");
        else if (AutoBuild.isPaused()) line.append("   (pauza)");

        FontRenderers.sf_medium_mini.drawString(context, line.toString(), x, y + 8f, DIM.getRGB());
    }

    private void drawBottomBar(DrawContext context, int mouseX, int mouseY, Color ac) {
        float y = panelY + panelH - BOTTOM_H + 4f;
        Render2DEngine.drawRect(context, panelX + 10f, y - 4f, panelW - 20f, 1f, DIVIDER);

        boolean running = AutoBuild.building;
        String[][] defs = {
                {"POZYCJA", "place"},
                {running ? "STOP" : "BUDUJ", "toggle"},
                {AutoBuild.isPaused() ? "WZNOW" : "PAUZA", "pause"},
                {"OBROC", "rotate"},
                {"LUSTRO", "mirror"},
                {"WYCZYSC", "clear"},
                {"FOLDER", "folder"},
        };

        buttons.clear();
        float gap = 6f;
        float bw = (panelW - 20f - gap * (defs.length - 1)) / defs.length;
        float bh = 22f;

        for (int i = 0; i < defs.length; i++) {
            float bx = panelX + 10f + i * (bw + gap);
            boolean primary = defs[i][1].equals("toggle");
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, bx, y, bw, bh);

            Color fill = primary
                    ? (running ? alpha(RED, hovered ? 235 : 200) : alpha(ac, hovered ? 235 : 200))
                    : (hovered ? ROW_HOV : ROW);
            Render2DEngine.drawRound(context, bx, y, bw, bh, 4f, fill);
            FontRenderers.sf_medium_mini.drawCenteredString(context, defs[i][0], bx + bw / 2f, y + 8f,
                    (primary ? Color.WHITE : hovered ? TEXT : DIM).getRGB());

            buttons.add(new Button(defs[i][0], defs[i][1], bx, y, bw, bh));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        int mx = (int) click.x();
        int my = (int) click.y();
        int button = click.button();
        lastMouseX = mx;
        lastMouseY = my;

        if (Render2DEngine.isHovered(mx, my, panelX + panelW - 26f, panelY + 10f, 16f, 16f)) {
            close();
            return true;
        }

        // header dot toggles the module itself
        if (Render2DEngine.isHovered(mx, my, panelX + 12f, panelY + 11f, 17f, 17f)) {
            ModuleManager.autoBuild.toggle();
            return true;
        }

        if (Render2DEngine.isHovered(mx, my, refreshX, refreshY, refreshW, 12f)) {
            saved = Schematic.getSavedNames();
            lastScan = System.currentTimeMillis();
            setStatus(saved.size() + " plikow w folderze");
            return true;
        }

        for (Button b : buttons) {
            if (!Render2DEngine.isHovered(mx, my, b.x(), b.y(), b.w(), b.h())) continue;
            action(b.action());
            return true;
        }

        // schematic list: left click loads, the x (or right click) deletes
        for (float[] row : listRows) {
            if (!Render2DEngine.isHovered(mx, my, row[0], row[1], row[2], row[3])) continue;
            String name = saved.get((int) row[4]);

            if (button == 1 || mx >= row[0] + row[2] - 14f) {
                if (Schematic.deleteAny(name)) {
                    saved = Schematic.getSavedNames();
                    setStatus("usunieto " + name);
                }
            } else {
                load(name);
            }
            return true;
        }

        float tabW = (SIDE_W - 16f) / 2f;
        float sideX = panelX + panelW - 10f - SIDE_W;
        if (Render2DEngine.isHovered(mx, my, sideX + 8f, panelY + HEADER_H + 6f, tabW, 15f)) {
            tab = 0;
            return true;
        }
        if (Render2DEngine.isHovered(mx, my, sideX + 8f + tabW, panelY + HEADER_H + 6f, tabW, 15f)) {
            tab = 1;
            return true;
        }

        if (tab == 1 && Render2DEngine.isHovered(mx, my, sideX, panelY + HEADER_H, SIDE_W, panelH - HEADER_H - BOTTOM_H)) {
            for (AbstractElement el : elements)
                if (el.isVisible()) el.mouseClicked(mx, my, button);
            return true;
        }

        if (Render2DEngine.isHovered(mx, my, canvasX, canvasY, canvasW, canvasH)) {
            if (Render2DEngine.isHovered(mx, my, layerBarX - 5f, layerBarY - 4f, 14f, layerBarH + 8f)) {
                draggingLayer = true;
                setLayerFromMouse(my);
            } else if (button == 2) {
                preview.resetView();
            } else {
                draggingView = true;
            }
            return true;
        }

        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        float mx = (float) click.x();
        float my = (float) click.y();

        if (draggingLayer) {
            setLayerFromMouse(my);
            return true;
        }
        if (draggingView) {
            float dx = mx - lastMouseX;
            float dy = my - lastMouseY;
            lastMouseX = mx;
            lastMouseY = my;

            if (click.button() == 1) preview.pan(dx, dy);
            else preview.rotate(-dx * 0.012f, -dy * 0.008f);
            return true;
        }

        lastMouseX = mx;
        lastMouseY = my;
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        draggingView = false;
        draggingLayer = false;
        for (AbstractElement el : elements)
            el.mouseReleased((int) click.x(), (int) click.y(), click.button());
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int dir = verticalAmount > 0 ? -1 : verticalAmount < 0 ? 1 : 0;
        if (dir == 0) return true;

        if (Render2DEngine.isHovered(mouseX, mouseY, canvasX, canvasY, canvasW, canvasH)) {
            preview.zoom(dir < 0 ? 1.12f : 1f / 1.12f);
            return true;
        }
        if (Render2DEngine.isHovered(mouseX, mouseY, panelX + 10f, panelY + HEADER_H, LIST_W, panelH - HEADER_H - BOTTOM_H)) {
            listScroll = Math.max(0, listScroll + dir);
            return true;
        }
        if (tab == 0) matScroll = Math.max(0, matScroll + dir);
        else settingsScroll -= dir * 14f;
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        for (AbstractElement el : elements)
            el.charTyped((char) input.codepoint(), input.modifiers());
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int key = input.key();
        for (AbstractElement el : elements)
            el.keyTyped(key);

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        elements.forEach(AbstractElement::onClose);
        super.close();
    }

    /* ------------------------------------------------------------------ */
    /* Actions                                                             */
    /* ------------------------------------------------------------------ */

    private void action(@NotNull String action) {
        switch (action) {
            case "place" -> {
                BlockPos target = placementTarget();
                if (target == null) {
                    setStatus("wejdz do gry");
                    return;
                }
                if (AutoBuild.schematic == null) {
                    setStatus("najpierw wczytaj schemat");
                    return;
                }
                AutoBuild.origin = target;
                AutoBuild.building = false;
                AutoBuild.rebuildQueue();
                enableModule();
                setStatus("pozycja: " + format(target));
            }
            case "toggle" -> {
                if (AutoBuild.schematic == null) {
                    setStatus("brak schematu");
                    return;
                }
                if (AutoBuild.origin == null) {
                    setStatus("najpierw ustaw pozycje");
                    return;
                }
                if (AutoBuild.building) {
                    AutoBuild.building = false;
                    setStatus("zatrzymane");
                } else {
                    AutoBuild.rebuildQueue();
                    AutoBuild.building = true;
                    enableModule();
                    setStatus("buduje - " + AutoBuild.getRemaining() + " blokow");
                }
            }
            case "pause" -> {
                if (AutoBuild.schematic == null) {
                    setStatus("brak schematu");
                    return;
                }
                // Pause keeps the queue and the progress; only Stop throws the run away.
                AutoBuild.setPaused(!AutoBuild.isPaused());
                setStatus(AutoBuild.isPaused() ? "pauza" : "wznowione");
            }
            case "rotate" -> {
                if (AutoBuild.schematic == null) return;
                AutoBuild.schematic.rotate(BlockRotation.CLOCKWISE_90);
                AutoBuild.rebuildQueue();
                setStatus("obrocone o 90");
            }
            case "mirror" -> {
                if (AutoBuild.schematic == null) return;
                AutoBuild.schematic.mirror(BlockMirror.LEFT_RIGHT);
                AutoBuild.rebuildQueue();
                setStatus("odbite");
            }
            case "clear" -> {
                AutoBuild.pos1 = null;
                AutoBuild.pos2 = null;
                AutoBuild.origin = null;
                AutoBuild.schematic = null;
                loadedName = null;
                AutoBuild.building = false;
                AutoBuild.rebuildQueue();
                preview.resetView();
                setStatus("wyczyszczone");
            }
            case "folder" -> {
                Util.getOperatingSystem().open(Schematic.getFolder());
                saved = Schematic.getSavedNames();
                setStatus("wrzuc .litematic / .nbt / .schem");
            }
            default -> {
            }
        }
    }

    private void load(@NotNull String name) {
        Schematic loaded = Schematic.loadAny(name);
        if (loaded == null) {
            setStatus("nie moge odczytac " + name);
            return;
        }

        AutoBuild.schematic = loaded;
        loadedName = name;
        AutoBuild.building = false;
        AutoBuild.rebuildQueue();
        enableModule();
        preview.resetView();

        Vec3i dim = loaded.getDimensions();
        setStatus(dim.getX() + "x" + dim.getY() + "x" + dim.getZ() + ", " + loaded.size() + " blokow");
    }

    private void setLayerFromMouse(float my) {
        float frac = 1f - Math.max(0f, Math.min(1f, (my - layerBarY) / Math.max(1f, layerBarH)));
        preview.setLayer(Math.round(frac * preview.getHeight()));
    }

    /** Half a second is fast enough to feel instant when a file is dropped in, cheap enough to ignore. */
    private void rescanIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastScan < 500L) return;

        lastScan = now;
        List<String> current = Schematic.getSavedNames();
        if (!current.equals(saved)) saved = current;
    }

    private void setStatus(String text) {
        status = text;
        statusTime = System.currentTimeMillis();
    }

    private static void enableModule() {
        if (ModuleManager.autoBuild.isOff()) ModuleManager.autoBuild.enable();
    }

    /** Where a block would go if you right clicked right now - the same anchor {@code /build place} uses. */
    private static @Nullable BlockPos placementTarget() {
        if (mc.player == null) return null;

        if (mc.crosshairTarget instanceof BlockHitResult bhr && mc.crosshairTarget.getType() == HitResult.Type.BLOCK)
            return bhr.getBlockPos().offset(bhr.getSide());

        return mc.player.getBlockPos();
    }

    /** The file name beats the metadata name: half the .litematic files in the wild are called "Unnamed". */
    private static String displayName(@NotNull Schematic schematic) {
        String own = schematic.getName();
        if (loadedName != null && (own == null || own.isBlank() || own.equalsIgnoreCase("Unnamed")))
            return loadedName;
        return own == null || own.isBlank() ? "?" : own;
    }

    private static @NotNull String format(@NotNull BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String trim(FontRenderer font, String text, float maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) return text;

        String s = text;
        while (s.length() > 1 && font.getStringWidth(s + "..") > maxWidth)
            s = s.substring(0, s.length() - 1);
        return s + "..";
    }
}
