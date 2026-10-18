package thunder.hack.gui.anim;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import thunder.hack.gui.anim.AnimData.Pose;
import thunder.hack.gui.anim.AnimData.Preset;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Three-panel animation editor (Presets | Shared curve | Start/End/Preview), styled after the reference. Edits the
 * active {@link AnimData} preset live; the module reads it to animate the held item in-game.
 */
public class AnimationScreen extends Screen {

    private static final Color BG = new Color(18, 18, 24, 245);
    private static final Color CARD = new Color(30, 30, 40, 255);
    private static final Color INNER = new Color(24, 24, 32, 255);
    private static final Color TEXT = new Color(238, 238, 248);
    private static final Color DIM = new Color(150, 150, 165);
    private static final Color ACC = new Color(150, 90, 240);

    private enum Tab {Start, End, Preview}

    private Tab tab = Tab.Start;
    private String typing = "";
    private boolean nameFocused;
    private int drag = 0; // 1=ease p1, 2=ease p2, 3=pose rotate, 4=swing slider
    private final long loop = System.currentTimeMillis();

    // layout rects, recomputed each render
    private float px, py, pw, ph;
    private float[] presetRect = new float[4], nameRect = new float[4], curveRect = new float[4],
            swingRect = new float[4], returnRect = new float[4], poseRect = new float[4];
    private final float[][] tabRects = new float[3][4];
    private final java.util.List<float[]> presetRows = new java.util.ArrayList<>();

    public AnimationScreen() {
        super(Text.literal("Animations"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, new Color(0, 0, 0, 170).getRGB());
        pw = Math.min(900, width - 40);
        ph = Math.min(430, height - 40);
        px = (width - pw) / 2f;
        py = (height - ph) / 2f;
        float colW = (pw - 48) / 3f;

        renderPresets(context, px + 12, py + 12, colW, ph - 24, mouseX, mouseY);
        renderShared(context, px + 24 + colW, py + 12, colW, ph - 24, mouseX, mouseY);
        renderEditor(context, px + 36 + colW * 2, py + 12, colW, ph - 24, mouseX, mouseY);
    }

    private void renderPresets(DrawContext c, float x, float y, float w, float h, int mx, int my) {
        Render2DEngine.drawRound(c, x, y, w, h, 8f, CARD);
        FontRenderers.sf_bold.drawString(c, "Presets", x + 12, y + 10, TEXT.getRGB());
        presetRows.clear();
        float ry = y + 34;
        for (String name : AnimData.names()) {
            boolean sel = name.equals(AnimData.active);
            boolean hov = Render2DEngine.isHovered(mx, my, x + 8, ry, w - 16, 20);
            if (sel || hov) Render2DEngine.drawRound(c, x + 8, ry, w - 16, 20, 4f,
                    sel ? new Color(55, 45, 90, 255) : new Color(40, 40, 52, 180));
            FontRenderers.sf_medium.drawString(c, name, x + 14, ry + 5, (sel ? TEXT : DIM).getRGB());
            if (sel) FontRenderers.sf_medium.drawString(c, "v", x + w - 22, ry + 5, ACC.getRGB());
            presetRows.add(new float[]{x + 8, ry, w - 16, 20});
            ry += 24;
        }
        // name field
        nameRect = new float[]{x + 8, y + h - 34, w - 16, 24};
        Render2DEngine.drawRound(c, nameRect[0], nameRect[1], nameRect[2], nameRect[3], 5f,
                nameFocused ? new Color(45, 45, 60, 255) : INNER);
        String label = typing.isEmpty() ? (nameFocused ? "" : "Enter name") : typing;
        FontRenderers.sf_medium.drawString(c, label + (nameFocused ? "_" : ""), nameRect[0] + 8, nameRect[1] + 7,
                (typing.isEmpty() && !nameFocused ? DIM : TEXT).getRGB());
    }

    private void renderShared(DrawContext c, float x, float y, float w, float h, int mx, int my) {
        Render2DEngine.drawRound(c, x, y, w, h, 8f, CARD);
        FontRenderers.sf_bold.drawString(c, "Shared", x + 12, y + 10, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(c, "Animation", x + 12, y + 30, DIM.getRGB());

        Preset p = AnimData.current();
        float cx = x + 12, cy = y + 44, cw = w - 24, chh = h - 150;
        curveRect = new float[]{cx, cy, cw, chh};
        Render2DEngine.drawRound(c, cx, cy, cw, chh, 6f, INNER);
        // the easing curve (cubic bezier) sampled as a polyline
        Color line = new Color(235, 235, 245);
        float prevX = cx, prevY = cy + chh;
        for (int i = 1; i <= 40; i++) {
            float t = i / 40f;
            float u = 1 - t;
            float by = 3 * u * u * t * p.e1y + 3 * u * t * t * p.e2y + t * t * t;
            float bx = 3 * u * u * t * p.e1x + 3 * u * t * t * p.e2x + t * t * t;
            float sx = cx + bx * cw, sy = cy + chh - by * chh;
            Render2DEngine.drawThickLine(c, prevX, prevY, sx, sy, line.getRGB(), 1.6f);
            prevX = sx; prevY = sy;
        }
        // control handles
        float h1x = cx + p.e1x * cw, h1y = cy + chh - p.e1y * chh;
        float h2x = cx + p.e2x * cw, h2y = cy + chh - p.e2y * chh;
        Render2DEngine.drawRound(c, h1x - 4, h1y - 4, 8, 8, 4f, ACC);
        Render2DEngine.drawRound(c, h2x - 4, h2y - 4, 8, 8, 4f, ACC);

        // Return toggle
        returnRect = new float[]{x + w - 46, y + h - 76, 36, 18};
        FontRenderers.sf_medium.drawString(c, "Return", x + 12, y + h - 74, TEXT.getRGB());
        Render2DEngine.drawRound(c, returnRect[0], returnRect[1], 36, 18, 9f, p.returnBack ? ACC : INNER);
        Render2DEngine.drawRound(c, returnRect[0] + (p.returnBack ? 20 : 2), returnRect[1] + 2, 14, 14, 7f, TEXT);

        // Swing speed slider
        FontRenderers.sf_medium.drawString(c, "Swing speed", x + 12, y + h - 46, TEXT.getRGB());
        FontRenderers.sf_medium.drawString(c, String.format("%.1f", p.swingSpeed), x + w - 34, y + h - 46, DIM.getRGB());
        swingRect = new float[]{x + 12, y + h - 28, w - 24, 6};
        Render2DEngine.drawRound(c, swingRect[0], swingRect[1], swingRect[2], 6, 3f, INNER);
        float frac = Math.clamp((p.swingSpeed - 0.3f) / (5f - 0.3f), 0f, 1f);
        Render2DEngine.drawRound(c, swingRect[0], swingRect[1], swingRect[2] * frac, 6, 3f, ACC);
    }

    private void renderEditor(DrawContext c, float x, float y, float w, float h, int mx, int my) {
        Render2DEngine.drawRound(c, x, y, w, h, 8f, CARD);
        FontRenderers.sf_bold.drawString(c, "Animation editor", x + 12, y + 10, TEXT.getRGB());

        // tabs
        Tab[] tabs = Tab.values();
        float tx = x + 12, tyy = y + 28;
        for (int i = 0; i < tabs.length; i++) {
            String t = tabs[i].name();
            float tw = FontRenderers.sf_medium.getStringWidth(t) + 14;
            boolean on = tab == tabs[i];
            if (on) Render2DEngine.drawRound(c, tx, tyy, tw, 18, 4f, new Color(55, 45, 90, 255));
            FontRenderers.sf_medium.drawString(c, t, tx + 7, tyy + 4, (on ? TEXT : DIM).getRGB());
            tabRects[i] = new float[]{tx, tyy, tw, 18};
            tx += tw + 6;
        }

        // preview area
        float bx = x + 12, by = y + 54, bw = w - 24, bh = h - 70;
        poseRect = new float[]{bx, by, bw, bh};
        Render2DEngine.drawRound(c, bx, by, bw, bh, 6f, INNER);
        float ccx = bx + bw / 2f, ccy = by + bh / 2f;

        Pose pose = tab == Tab.End ? AnimData.current().end : AnimData.current().start;
        float shownRz;
        if (tab == Tab.Preview) {
            // play start->end with the easing, looping
            double t = ((System.currentTimeMillis() - loop) % 2000L) / 2000.0;
            float e = AnimData.ease((float) (t < 0.5 ? t * 2 : (1 - t) * 2));
            Pose s = AnimData.current().start, en = AnimData.current().end;
            shownRz = AnimData.lerp(s.rz, en.rz, e);
            drawItem(c, ccx, ccy, AnimData.lerp(s.scale, en.scale, e), shownRz);
        } else {
            drawItem(c, ccx, ccy, pose.scale, pose.rz);
            // gizmo axes (visual) + rotation readout
            Render2DEngine.drawThickLine(c, ccx, ccy, ccx, ccy - 34, new Color(70, 230, 90).getRGB(), 2f);
            Render2DEngine.drawThickLine(c, ccx, ccy, ccx + 34, ccy + 14, new Color(235, 70, 70).getRGB(), 2f);
            Render2DEngine.drawThickLine(c, ccx, ccy, ccx - 24, ccy + 10, new Color(70, 120, 235).getRGB(), 2f);
            FontRenderers.sf_medium_mini.drawString(c,
                    String.format("X %.0f  Y %.0f  Z %.0f  s %.2f", pose.rx, pose.ry, pose.rz, pose.scale),
                    bx + 8, by + bh - 14, DIM.getRGB());
        }
    }

    private void drawItem(DrawContext c, float cx, float cy, float scale, float rz) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack stack = mc.player != null && !mc.player.getMainHandStack().isEmpty()
                ? mc.player.getMainHandStack() : new ItemStack(Items.DIAMOND_SWORD);
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.rotate((float) Math.toRadians(rz));
        float s = 6f * Math.max(0.3f, scale);
        m.scale(s, s);
        m.translate(-8f, -8f);
        c.drawItem(stack, 0, 0);
        m.popMatrix();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        nameFocused = hit(nameRect, mx, my);
        // presets
        for (int i = 0; i < presetRows.size(); i++) {
            if (hit(presetRows.get(i), mx, my)) {
                AnimData.active = AnimData.names().get(i);
                return true;
            }
        }
        // tabs
        for (int i = 0; i < tabRects.length; i++) if (hit(tabRects[i], mx, my)) {
            tab = Tab.values()[i];
            return true;
        }
        Preset p = AnimData.current();
        // return toggle
        if (hit(returnRect, mx, my)) { p.returnBack = !p.returnBack; return true; }
        // swing slider
        if (hit(swingRect, mx, my)) { drag = 4; setSwing(mx); return true; }
        // ease handles
        if (hit(curveRect, mx, my)) {
            float h1x = curveRect[0] + p.e1x * curveRect[2], h1y = curveRect[1] + curveRect[3] - p.e1y * curveRect[3];
            float h2x = curveRect[0] + p.e2x * curveRect[2], h2y = curveRect[1] + curveRect[3] - p.e2y * curveRect[3];
            drag = (Math.hypot(mx - h1x, my - h1y) < Math.hypot(mx - h2x, my - h2y)) ? 1 : 2;
            setEase(mx, my);
            return true;
        }
        // pose rotate (drag in preview, Start/End tabs)
        if (tab != Tab.Preview && hit(poseRect, mx, my)) { drag = 3; return true; }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        double mx = click.x(), my = click.y();
        switch (drag) {
            case 1, 2 -> { setEase(mx, my); return true; }
            case 4 -> { setSwing(mx); return true; }
            case 3 -> {
                Pose pose = tab == Tab.End ? AnimData.current().end : AnimData.current().start;
                pose.ry += (float) dx;   // horizontal drag = yaw
                pose.rx += (float) dy;   // vertical drag = pitch
                pose.rz += (float) dx * 0.3f;
                return true;
            }
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        drag = 0;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (tab != Tab.Preview && hit(poseRect, mx, my)) {
            Pose pose = tab == Tab.End ? AnimData.current().end : AnimData.current().start;
            pose.scale = Math.clamp(pose.scale + (float) v * 0.05f, 0.3f, 2f);
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (nameFocused) {
            int k = input.key();
            if (k == GLFW.GLFW_KEY_ENTER) { AnimData.saveAs(typing.trim()); typing = ""; nameFocused = false; return true; }
            if (k == GLFW.GLFW_KEY_BACKSPACE) { if (!typing.isEmpty()) typing = typing.substring(0, typing.length() - 1); return true; }
            if (k == GLFW.GLFW_KEY_ESCAPE) { nameFocused = false; return true; }
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        char chr = (char) input.codepoint();
        if (nameFocused && chr >= 32 && chr != 127 && typing.length() < 20) { typing += chr; return true; }
        return super.charTyped(input);
    }

    private void setEase(double mx, double my) {
        float fx = Math.clamp((float) ((mx - curveRect[0]) / curveRect[2]), 0f, 1f);
        float fy = Math.clamp((float) (1 - (my - curveRect[1]) / curveRect[3]), 0f, 1f);
        Preset p = AnimData.current();
        if (drag == 1) { p.e1x = fx; p.e1y = fy; } else { p.e2x = fx; p.e2y = fy; }
    }

    private void setSwing(double mx) {
        float f = Math.clamp((float) ((mx - swingRect[0]) / swingRect[2]), 0f, 1f);
        AnimData.current().swingSpeed = 0.3f + f * (5f - 0.3f);
    }

    private boolean hit(float[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
