package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import thunder.hack.features.hud.HudElement;
import thunder.hack.gui.font.BkFonts;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.BkLiquidGlass;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BKClient's Custom Chat ({@code ui/hud/impl/CustomChat}, MIT, bkubiakdev): the chat as message bubbles on liquid
 * glass - your own in the accent colour on the right, everyone else's on the left - sliding in as they arrive, with
 * BK's rounded glass input bar, emoji picker and send button. Clickable chat links keep working.
 *
 * <p>Typing, the cursor, history, tab completion and command suggestions stay vanilla's: the chat field is still
 * the screen's own text field, only drawn here in BK's style (BK mirrored that same field into its own).
 */
public class CustomChat extends HudElement {
    public CustomChat() {
        super("CustomChat", 240, 150);
    }

    private final Setting<Boolean> animation = new Setting<>("Animation", true);

    private static final float MAX_WIDTH = 240.0F;
    private static final float MSG_PADDING_H = 6.0F;
    private static final float MSG_PADDING_V = 3.0F;
    private static final float MSG_GAP = 3.0F;
    private static final float INPUT_HEIGHT = 20.0F;
    private static final float INPUT_RADIUS = 10.0F;
    private static final float MSG_RADIUS = 6.0F;
    private static final float FONT_SIZE = 6.0F;
    private static final int MAX_MESSAGES = 100;
    private static final Identifier EMOJI_ICON = Identifier.of("bkclient", "icons/hud/emoji.png");
    private static final Identifier SEND_ICON = Identifier.of("bkclient", "sf_symbold/chat/send.png");

    private static CustomChat instance;

    private final List<ChatMessage> messages = new ArrayList<>();
    private float scrollOffset, targetScroll, contentHeight, scrollVelocity;
    private boolean shouldAutoScroll = true;
    private float inputAnim, inputFrom;
    private long inputChangedAt;
    private boolean wasChatOpen;
    private boolean emojiPickerOpen;
    private float emojiPickerScroll;
    private float emojiPickerAnim;
    private long lastFrame;
    private final List<ClickableRegion> clickableRegions = new ArrayList<>();
    private float x, y, width, height;

    /** The chat screen's own text field, handed over by the screen every frame it renders. */
    private static TextFieldWidget field;
    private static ChatScreen screen;

    {
        instance = this;
    }

    /* ------------------------------------------------------------------ */
    /* Hooks                                                               */
    /* ------------------------------------------------------------------ */

    /** True while the custom chat replaces vanilla's. */
    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    /** Called from ChatHud after vanilla accepts any chat message, on the client thread. */
    public static void captureMessage(Text content) {
        if (instance == null) return;
        String text = content.getString();
        if (text.isBlank()) return;
        instance.messages.add(new ChatMessage(text, instance.extractSender(text), System.currentTimeMillis(), extractClickables(content)));
        while (instance.messages.size() > MAX_MESSAGES) instance.messages.removeFirst();
        instance.shouldAutoScroll = true;
    }

    public static void bind(ChatScreen chatScreen, TextFieldWidget chatField) {
        screen = chatScreen;
        field = chatField;
    }

    /** The vanilla field is drawn here instead, so its own rendering is skipped. */
    public static boolean hidesField(TextFieldWidget widget) {
        return active() && widget != null && widget == field && mc.currentScreen instanceof ChatScreen;
    }

    /* ------------------------------------------------------------------ */
    /* Layout and animation (BK's update)                                  */
    /* ------------------------------------------------------------------ */


    private static float outCubic(float t) {
        t = Math.clamp(t, 0f, 1f);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    /** BK's entry curve: cubic-bezier(0.4, 1.12, 0.6, 1.0). */
    private static float entryEase(float progress) {
        float x1 = 0.4f, y1 = 1.12f, x2 = 0.6f, y2 = 1.0f;
        if (progress <= 0f) return 0f;
        if (progress >= 1f) return 1f;
        float t = progress;
        for (int i = 0; i < 8; i++) {
            float x = 3f * (1f - t) * (1f - t) * t * x1 + 3f * (1f - t) * t * t * x2 + t * t * t;
            float dx = 3f * ((1f - t) * (1f - 3f * t) * x1 + (2f * t - 3f * t * t) * x2) + 3f * t * t;
            if (Math.abs(x - progress) < 1e-5f || Math.abs(dx) < 1e-6f) break;
            t = Math.clamp(t - (x - progress) / dx, 0f, 1f);
        }
        return 3f * (1f - t) * (1f - t) * t * y1 + 3f * (1f - t) * t * t * y2 + t * t * t;
    }

    private void update() {
        float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        width = Math.min(MAX_WIDTH, sw * 0.7F);
        height = Math.min(150.0F, sh * 0.35F);
        x = 2.0F;
        y = sh - height - INPUT_HEIGHT - 6.0F;

        float minScroll = Math.min(0f, -(contentHeight - height));
        scrollVelocity *= 0.88F;
        if (Math.abs(scrollVelocity) < 0.5F) scrollVelocity = 0.0F;
        targetScroll += scrollVelocity;
        targetScroll = Math.max(minScroll, Math.min(0f, targetScroll));
        scrollOffset += (targetScroll - scrollOffset) * 0.12F;
        if (Math.abs(scrollOffset - targetScroll) < 0.1F) scrollOffset = targetScroll;

        boolean chatOpen = mc.currentScreen instanceof ChatScreen;
        if (chatOpen != wasChatOpen) {
            inputFrom = inputAnim;
            inputChangedAt = System.currentTimeMillis();
            if (!chatOpen) emojiPickerOpen = false;
        }
        wasChatOpen = chatOpen;
        if (!chatOpen) wasChatOpenForField = false;
        if (animation.getValue()) {
            float p = Math.min(1f, (System.currentTimeMillis() - inputChangedAt) / 200f);
            inputAnim = inputFrom + ((chatOpen ? 1f : 0f) - inputFrom) * outCubic(p);
        } else inputAnim = chatOpen ? 1f : 0f;

        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 16f : Math.min(100f, now - lastFrame);
        lastFrame = now;
        float target = emojiPickerOpen ? 1f : 0f;
        emojiPickerAnim += (target - emojiPickerAnim) * Math.min(1f, dt / 90f);
        if (Math.abs(target - emojiPickerAnim) < 0.005f) emojiPickerAnim = target;
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        update();
        setBounds(x, y, width, height + INPUT_HEIGHT + 4f);
        if (mc.currentScreen != null && !(mc.currentScreen instanceof ChatScreen)) return;
        renderComponent(context);
    }

    private void renderComponent(DrawContext ctx) {
        clickableRegions.clear();
        FontRenderer msgFont = BkFonts.MEDIUM.get(FONT_SIZE);
        float fontH = FONT_SIZE * 0.7f;

        if (!messages.isEmpty()) {
            float maxTextW = width - MSG_PADDING_H * 2.0F - 10.0F;
            float totalH = 0.0F;
            for (ChatMessage msg : messages) totalH += msg.getHeight(msgFont, maxTextW, fontH) + MSG_GAP;
            contentHeight = totalH;
            if (totalH > height) {
                if (shouldAutoScroll) {
                    targetScroll = -(totalH - height);
                    scrollVelocity = 0.0F;
                    shouldAutoScroll = false;
                }
                targetScroll = Math.min(0f, Math.max(-(totalH - height), targetScroll));
            } else {
                targetScroll = 0.0F;
                shouldAutoScroll = true;
            }

            float drawY = y + scrollOffset;
            float feather = 15.0F;
            Render2DEngine.addWindow(ctx, x, y - feather, x + width, y + height + feather, 1.0);
            String username = mc.getSession() != null ? mc.getSession().getUsername() : "";
            try {
                for (ChatMessage msg : messages) {
                    float msgH = msg.getHeight(msgFont, maxTextW, fontH);
                    if (drawY + msgH < y - feather || drawY > y + height + feather) {
                        drawY += msgH + MSG_GAP;
                        continue;
                    }
                    List<String> lines = msg.getLines(msgFont, maxTextW);
                    float bubbleW = msg.getMaxLineW(msgFont, maxTextW) + MSG_PADDING_H * 2.0F;
                    boolean isOwn = !username.isEmpty() && msg.sender.equals(username);
                    float p = animation.getValue() ? entryEase(Math.min(1f, (System.currentTimeMillis() - msg.timestamp) / 200f)) : 1f;
                    float inv = 1.0F - p;
                    float vRise = msgH * 0.25F * inv;
                    float hSlide = isOwn ? (bubbleW + 8.0F) * inv : -(bubbleW + 8.0F) * inv;
                    float pulse = 1.0F + 0.05F * (float) Math.sin(p * Math.PI * 2.5F) * (1.0F - p);
                    float bubbleX = (isOwn ? x + width - bubbleW - 4.0F : x + 4.0F) + hSlide;
                    float bubbleY = drawY + vRise;
                    float fade = Math.clamp((bubbleY + msgH - (y - feather)) / (feather * 2), 0.0F, 1.0F);

                    Color bubbleBg = isOwn ? alpha(accent(), 200 * fade) : new Color(40, 40, 45, Math.round(220 * fade));
                    Color textColor = isOwn ? alpha(Color.WHITE, 230 * fade) : alpha(Color.WHITE, 200 * fade);
                    Emoji single = singleEmoji(msg.text);

                    var m = ctx.getMatrices();
                    m.pushMatrix();
                    float pivotX = bubbleX + bubbleW / 2.0F, pivotY = bubbleY + msgH / 2.0F;
                    m.translate(pivotX, pivotY);
                    m.scale(pulse, pulse);
                    m.translate(-pivotX, -pivotY);
                    if (single != null) {
                        float eSize = 18.0F;
                        texture(ctx, single.texture, bubbleX + (bubbleW - eSize) / 2.0F, bubbleY + (msgH - eSize) / 2.0F, eSize,
                                alpha(Color.WHITE, textColor.getAlpha()));
                    } else {
                        BkLiquidGlass.glass(ctx, bubbleX, bubbleY, bubbleW, msgH, MSG_RADIUS, 0.05F, 36 / 255f * fade);
                        Render2DEngine.drawRoundSmooth(ctx, bubbleX, bubbleY, bubbleW, msgH, MSG_RADIUS, bubbleBg);
                    }
                    m.popMatrix();

                    if (single == null) {
                        float textY = bubbleY + MSG_PADDING_V + 1.0F;
                        int lineOffset = 0;
                        for (String line : lines) {
                            int found = msg.text.indexOf(line, lineOffset);
                            if (found >= 0) lineOffset = found;
                            drawTextWithEmojis(ctx, msgFont, fontH, line, bubbleX + MSG_PADDING_H, textY, textColor, 8.0F, msg.clickables, lineOffset);
                            lineOffset += line.length();
                            boolean hasEmoji = line.contains("[") && line.contains("]");
                            textY += hasEmoji ? 9.0F : fontH + 2.0F;
                        }
                    }
                    drawY += msgH + MSG_GAP;
                }
            } finally {
                Render2DEngine.popWindow();
            }
        }

        if (inputAnim <= 0.0F) return;
        float inputY = y + height + 4.0F + (1.0F - inputAnim) * 12.0F;
        float inputX = x, inputW = width;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(inputX + inputW / 2.0F, inputY + INPUT_HEIGHT / 2.0F);
        m.scale(inputAnim, inputAnim);
        m.translate(-(inputX + inputW / 2.0F), -(inputY + INPUT_HEIGHT / 2.0F));
        BkLiquidGlass.glass(ctx, inputX, inputY, inputW, INPUT_HEIGHT, INPUT_RADIUS, 0.08F, inputAnim);
        Render2DEngine.drawRoundSmooth(ctx, inputX, inputY, inputW, INPUT_HEIGHT, INPUT_RADIUS, new Color(12, 12, 12, Math.round(255 * 0.5f * inputAnim)));
        m.popMatrix();

        float emojiBtnW = 22.0F, sendBtnW = 34.0F, btnH = 16.0F;
        float fieldEndX = inputX + inputW - emojiBtnW - sendBtnW - 9.0F;
        drawField(ctx, inputX + 10.0F, inputY + 3.0F, fieldEndX - inputX - 10.0F, INPUT_HEIGHT - 6.0F);

        float emojiBtnX = fieldEndX + 3.0F, emojiBtnY = inputY + (INPUT_HEIGHT - btnH) / 2.0F;
        Render2DEngine.drawRoundSmooth(ctx, emojiBtnX, emojiBtnY, emojiBtnW, btnH, 7.0F, alpha(Color.WHITE, 15 * inputAnim));
        float emojiIconSize = 11.0F;
        icon(ctx, EMOJI_ICON, emojiBtnX + (emojiBtnW - emojiIconSize) / 2.0F, emojiBtnY + (btnH - emojiIconSize) / 2.0F, emojiIconSize,
                alpha(Color.WHITE, 180 * inputAnim));
        float sendBtnX = emojiBtnX + emojiBtnW + 3.0F;
        Render2DEngine.drawRoundSmooth(ctx, sendBtnX, emojiBtnY, sendBtnW, btnH, 7.0F, alpha(accent(), 162 * inputAnim));
        float sendIconSize = 10.0F;
        icon(ctx, SEND_ICON, sendBtnX + (sendBtnW - sendIconSize) / 2.0F, emojiBtnY + (btnH - sendIconSize) / 2.0F, sendIconSize,
                alpha(Color.WHITE, 200 * inputAnim));

        if (emojiPickerAnim > 0.0F) renderEmojiPicker(ctx, inputX, inputY, emojiBtnX, emojiBtnW);
    }

    /** The vanilla field's text in BK's style: placeholder, emojis, selection and a breathing caret. */
    private void drawField(DrawContext ctx, float fx, float fy, float fw, float fh) {
        FontRenderer font = BkFonts.MEDIUM.get(7f);
        float fontH = 7f * 0.7f;
        String text = field != null ? field.getText() : "";
        float ty = fy + (fh - fontH) / 2f - 0.5f;
        if (text.isEmpty()) {
            font.drawString(ctx, "Napisz wiadomość...", fx, ty, alpha(Color.WHITE, 90 * inputAnim).getRGB());
        }
        syncTyped(text);
        int cursor = field != null ? Math.clamp(field.getCursor(), 0, text.length()) : 0;
        float[] xs = layoutTyped(font);
        float caretX = xAtLive(xs, cursor);
        float shift = Math.max(0f, caretX - fw + 4f);
        Render2DEngine.addWindow(ctx, fx, fy, fx + fw, fy + fh, 1.0);
        try {
            if (field instanceof thunder.hack.injection.accesors.ITextFieldSelection sel) {
                int a = Math.clamp(Math.min(sel.gombal$selectionStart(), sel.gombal$selectionEnd()), 0, text.length());
                int b = Math.clamp(Math.max(sel.gombal$selectionStart(), sel.gombal$selectionEnd()), 0, text.length());
                if (b > a) {
                    float sx = fx - shift + xAtLive(xs, a);
                    float ex = fx - shift + xAtLive(xs, b);
                    Render2DEngine.drawRect(ctx, sx, fy + 2f, ex - sx, fh - 4f, alpha(accent(), 110 * inputAnim));
                }
            }
            drawTyped(ctx, font, fontH, fx - shift, ty, xs);
            float blink = 0.5f + 0.5f * (float) Math.cos(System.currentTimeMillis() / 1000.0 * Math.PI * 2 * 0.9);
            Render2DEngine.drawRect(ctx, fx - shift + caretX, fy + 2.5f, 0.7f, fh - 5f, alpha(Color.WHITE, 255 * blink * inputAnim));
        } finally {
            Render2DEngine.popWindow();
        }
    }

    /* ------------------------------------------------------------------ */
    /* BK's TextField letters: each one rises and fades in, a deleted one   */
    /* sinks and fades out while the text closes up behind it              */
    /* ------------------------------------------------------------------ */

    private static final class Typed {
        final char ch;
        boolean removing;
        float from;
        long changedAt = System.currentTimeMillis();

        Typed(char ch, float from) {
            this.ch = ch;
            this.from = from;
        }

        /** BK's 200 ms FIGMA ease-in-out towards shown (1) or gone (0). */
        float value() {
            float target = removing ? 0f : 1f;
            float p = Math.min(1f, (System.currentTimeMillis() - changedAt) / 200f);
            return from + (target - from) * easeInOut(p);
        }

        void remove() {
            if (removing) return;
            from = value();
            removing = true;
            changedAt = System.currentTimeMillis();
        }
    }

    private static float easeInOut(float p) {
        // cubic-bezier(0.42, 0, 0.58, 1)
        float t = p;
        for (int i = 0; i < 8; i++) {
            float x = 3f * (1f - t) * (1f - t) * t * 0.42f + 3f * (1f - t) * t * t * 0.58f + t * t * t;
            float dx = 3f * ((1f - t) * (1f - 3f * t) * 0.42f + (2f * t - 3f * t * t) * 0.58f) + 3f * t * t;
            if (Math.abs(x - p) < 1e-5f || Math.abs(dx) < 1e-6f) break;
            t = Math.clamp(t - (x - p) / dx, 0f, 1f);
        }
        return 3f * (1f - t) * t * t + t * t * t;
    }

    private final List<Typed> typed = new ArrayList<>();
    private String lastText = null;
    private final Map<Character, Float> pitchCache = new java.util.HashMap<>();
    /** Set by the send hooks: the field emptying after a send is not a deletion to be heard. */
    private static boolean justSent;

    /** Brings the letters in line with the field: inserted ones animate in, removed ones out, with BK's sounds. */
    private void syncTyped(String text) {
        typed.removeIf(t -> t.removing && t.value() <= 0.001f);
        if (lastText == null || !(mc.currentScreen instanceof ChatScreen) || !wasChatOpenForField) {
            // a freshly opened chat starts with its text already there
            justSent = false;
            typed.clear();
            for (char c : text.toCharArray()) typed.add(new Typed(c, 1f));
            lastText = text;
            wasChatOpenForField = mc.currentScreen instanceof ChatScreen;
            return;
        }
        if (text.equals(lastText)) return;

        int p = 0, oldLen = lastText.length(), newLen = text.length();
        while (p < oldLen && p < newLen && lastText.charAt(p) == text.charAt(p)) p++;
        int s = 0;
        while (s < oldLen - p && s < newLen - p && lastText.charAt(oldLen - 1 - s) == text.charAt(newLen - 1 - s)) s++;

        // the live letters, in order, as indices into typed
        List<Integer> live = new ArrayList<>();
        for (int i = 0; i < typed.size(); i++) if (!typed.get(i).removing) live.add(i);
        if (live.size() != oldLen) {
            typed.clear();
            for (char c : text.toCharArray()) typed.add(new Typed(c, 1f));
            lastText = text;
            return;
        }

        int removed = oldLen - p - s;
        for (int i = p; i < oldLen - s; i++) typed.get(live.get(i)).remove();
        int insertAt = oldLen - s < live.size() ? live.get(oldLen - s) : typed.size();
        String inserted = text.substring(p, newLen - s);
        for (int i = 0; i < inserted.length(); i++) typed.add(insertAt + i, new Typed(inserted.charAt(i), 0f));

        if (justSent && text.isEmpty()) {
            justSent = false;
        } else if (!inserted.isEmpty()) {
            char last = inserted.charAt(inserted.length() - 1);
            float pitch = last == ' ' ? 0.8f : pitchCache.computeIfAbsent(last, k -> 0.8f + (float) Math.random() * 0.4f);
            playSound("typing", 0.3f, inserted.length() > 1 ? 1.3f : pitch);
        } else if (removed > 0) {
            playSound("chat/remove", 0.3f, 1.2f);
        }
        lastText = text;
    }

    private boolean wasChatOpenForField;

    /** Each letter's left edge (animated widths), then the total width at the end. */
    private float[] layoutTyped(FontRenderer font) {
        float[] xs = new float[typed.size() + 1];
        float x = 0f;
        for (int i = 0; i < typed.size(); i++) {
            xs[i] = x;
            Typed t = typed.get(i);
            x += font.getStringWidth(String.valueOf(t.ch)) * t.value();
        }
        xs[typed.size()] = x;
        return xs;
    }

    /** The x of the live letter with this index (the caret before it). */
    private float xAtLive(float[] xs, int liveIndex) {
        int n = 0;
        for (int i = 0; i < typed.size(); i++) {
            if (typed.get(i).removing) continue;
            if (n == liveIndex) return xs[i];
            n++;
        }
        return xs[typed.size()];
    }

    private void drawTyped(DrawContext ctx, FontRenderer font, float fontH, float x, float y, float[] xs) {
        for (int i = 0; i < typed.size(); i++) {
            Typed t = typed.get(i);
            float v = t.value();
            if (v <= 0.01f) continue;
            // a whole "[code]" of live letters is an emoji, shown as its picture
            if (t.ch == '[' && !t.removing) {
                int close = -1;
                StringBuilder code = new StringBuilder();
                for (int j = i + 1; j < typed.size() && j - i < 20; j++) {
                    Typed u = typed.get(j);
                    if (u.removing) break;
                    if (u.ch == ']') {
                        close = j;
                        break;
                    }
                    code.append(u.ch);
                }
                Emoji e = close > 0 ? EMOJIS.get(code.toString()) : null;
                if (e != null) {
                    float w = xs[close + 1] - xs[i];
                    float es = 8f;
                    texture(ctx, e.texture, x + xs[i] + (w - es) / 2f, y + (fontH - es) / 2 + 1.5F, es,
                            alpha(Color.WHITE, 255 * v * inputAnim));
                    i = close;
                    continue;
                }
            }
            font.drawString(ctx, String.valueOf(t.ch), x + xs[i], y + 2f - 2f * v,
                    alpha(Color.WHITE, 0.85f * 255 * v * inputAnim).getRGB());
        }
    }

    /** BK's ClientSounds: the client's own UI sounds, volume then pitch. */
    public static void playSound(String id, float volume, float pitch) {
        try {
            mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(
                    net.minecraft.sound.SoundEvent.of(Identifier.of("bkclient", id)), pitch, volume));
        } catch (Throwable ignored) {
        }
    }

    /** From the chat screen's sendMessage: BK's send sound, once per message. */
    public static void onSend(String text) {
        if (!active() || text == null || text.isBlank()) return;
        justSent = true;
        playSound("chat_send", 0.2f, 1f);
    }

    private void renderEmojiPicker(DrawContext ctx, float inputX, float inputY, float emojiBtnX, float emojiBtnW) {
        float a = emojiPickerAnim;
        float cellSize = 26.0F, gridPad = 4.0F;
        int cols = 6;
        float pickerW = cols * cellSize + gridPad * 2, pickerH = 130.0F;
        float pickerX = Math.max(2.0F, Math.min(mc.getWindow().getScaledWidth() - pickerW - 2.0F, emojiBtnX - 5.0F));
        float pickerY = Math.max(2.0F, inputY - pickerH - 6.0F);

        var m = ctx.getMatrices();
        m.pushMatrix();
        float s = 0.5F + a * 0.5F;
        m.translate(pickerX + pickerW / 2f, pickerY + pickerH / 2f);
        m.scale(s, s);
        m.translate(-(pickerX + pickerW / 2f), -(pickerY + pickerH / 2f));
        try {
            BkLiquidGlass.panel(ctx, pickerX, pickerY, pickerW, pickerH, 7f, a);
            Render2DEngine.addWindow(ctx, pickerX, pickerY, pickerX + pickerW, pickerY + pickerH, 1.0);
            try {
                float startX = pickerX + gridPad, startY = pickerY + gridPad + emojiPickerScroll;
                int i = 0;
                for (Emoji e : EMOJIS.values()) {
                    float ex = startX + (i % cols) * cellSize, ey = startY + (i / cols) * cellSize;
                    float es = 18.0F;
                    texture(ctx, e.texture, ex + (cellSize - es) / 2.0F, ey + (cellSize - es) / 2.0F, es, alpha(Color.WHITE, 255 * a));
                    i++;
                }
            } finally {
                Render2DEngine.popWindow();
            }
            float totalEmojiH = ((EMOJIS.size() + cols - 1) / cols) * cellSize + gridPad * 2;
            float maxScroll = Math.max(0.0F, totalEmojiH - pickerH);
            emojiPickerScroll = Math.clamp(emojiPickerScroll, -maxScroll, 0f);
            if (maxScroll > 0.0F) {
                float sbW = 3.0F, sbX = pickerX + pickerW - sbW - 3.0F, sbY = pickerY + gridPad, sbH = pickerH - gridPad * 2;
                float thumbH = sbH * (pickerH / totalEmojiH);
                float thumbY = sbY + (-emojiPickerScroll / maxScroll) * (sbH - thumbH);
                Render2DEngine.drawRound(ctx, sbX, sbY, sbW, sbH, sbW / 2f, new Color(255, 255, 255, 8));
                Render2DEngine.drawRound(ctx, sbX, thumbY, sbW, thumbH, sbW / 2f, new Color(255, 255, 255, 40));
            }
            // the little pointer under the picker, towards the emoji button
            float triSize = 6.0F, triCx = emojiBtnX + emojiBtnW / 2.0F, triY = pickerY + pickerH;
            int triColor = new Color(30, 30, 35, Math.round(240 * a)).getRGB();
            var pose = GuiRender.pose(ctx);
            GuiRender.quads(ctx, Render2DEngine.currentClip(), vc ->
                    GuiRender.tri(vc, pose, triCx, triY + triSize, triCx + triSize / 2f, triY, triCx - triSize / 2f, triY, triColor));
        } finally {
            m.popMatrix();
        }
    }

    private void drawTextWithEmojis(DrawContext ctx, FontRenderer font, float fontH, String text, float x, float y,
                                    Color color, float emojiSize, List<ClickSpan> clickables, int lineOffset) {
        float currentX = x;
        for (int pos = 0; pos < text.length(); ) {
            int end = pos + 1;
            Style style = Style.EMPTY;
            for (ClickSpan span : clickables)
                if (lineOffset + pos >= span.start && lineOffset + pos < span.end) {
                    style = span.style;
                    break;
                }
            Emoji emoji = null;
            if (text.charAt(pos) == '[') {
                int close = text.indexOf(']', pos);
                if (close >= 0) {
                    emoji = EMOJIS.get(text.substring(pos + 1, close));
                    if (emoji != null) end = close + 1;
                }
            }
            float w;
            if (emoji != null) {
                texture(ctx, emoji.texture, currentX, y + (fontH - emojiSize) / 2 + 1.5F, emojiSize, alpha(Color.WHITE, color.getAlpha()));
                w = emojiSize + 1;
            } else {
                while (end < text.length() && text.charAt(end) != '[') {
                    boolean boundary = false;
                    for (ClickSpan span : clickables)
                        if (span.start == lineOffset + end || span.end == lineOffset + end) {
                            boundary = true;
                            break;
                        }
                    if (boundary) break;
                    end++;
                }
                String part = text.substring(pos, end);
                w = font.getStringWidth(part);
                Color c = style.getClickEvent() == null ? color : alpha(accent(), color.getAlpha());
                if (c.getAlpha() > 2) font.drawString(ctx, part, currentX, y, c.getRGB());
            }
            if (style.getClickEvent() != null) clickableRegions.add(new ClickableRegion(currentX, y, w, fontH + 1, style));
            currentX += w;
            pos = end;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    /** From the chat screen's mouseClicked. @return true when the click was ours */
    public static boolean click(double mouseX, double mouseY, int button) {
        return instance != null && active() && instance.onClick(mouseX, mouseY, button);
    }

    private boolean onClick(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= y && mouseY <= y + height) {
            for (ClickableRegion region : clickableRegions) {
                if (region.contains((float) mouseX, (float) mouseY)) {
                    if (screen instanceof thunder.hack.injection.accesors.IChatScreen s) s.gombal$handleClickEvent(region.style, false);
                    return true;
                }
            }
        }
        float inputY = y + height + 4.0F;
        float emojiBtnW = 22.0F, sendBtnW = 34.0F, btnH = 16.0F;
        float fieldEndX = x + width - emojiBtnW - sendBtnW - 9.0F;
        float emojiBtnX = fieldEndX + 3.0F, emojiBtnY = inputY + (INPUT_HEIGHT - btnH) / 2.0F;

        if (emojiPickerOpen) {
            float cellSize = 26.0F, gridPad = 4.0F;
            int cols = 6;
            float pickerW = cols * cellSize + gridPad * 2, pickerH = 130.0F;
            float pickerX = Math.max(2.0F, Math.min(mc.getWindow().getScaledWidth() - pickerW - 2.0F, emojiBtnX - 5.0F));
            float pickerY = Math.max(2.0F, inputY - pickerH - 6.0F);
            if (inside(mouseX, mouseY, pickerX, pickerY, pickerW, pickerH)) {
                if (button == 0) {
                    int cx = (int) ((mouseX - (pickerX + gridPad)) / cellSize);
                    int cy = (int) ((mouseY - (pickerY + gridPad + emojiPickerScroll)) / cellSize);
                    int index = cy * cols + cx, i = 0;
                    if (cx >= 0 && cx < cols && cy >= 0)
                        for (Emoji e : EMOJIS.values()) {
                            if (i++ == index && field != null) {
                                field.write("[" + e.code + "]");
                                break;
                            }
                        }
                }
                return true;
            }
            if (!inside(mouseX, mouseY, emojiBtnX, emojiBtnY, emojiBtnW, btnH)) emojiPickerOpen = false;
        }
        if (inside(mouseX, mouseY, emojiBtnX, emojiBtnY, emojiBtnW, btnH)) {
            emojiPickerOpen = !emojiPickerOpen;
            emojiPickerScroll = 0.0F;
            return true;
        }
        float sendBtnX = emojiBtnX + emojiBtnW + 3.0F;
        if (inside(mouseX, mouseY, sendBtnX, emojiBtnY, sendBtnW, btnH)) {
            send();
            return true;
        }
        // clicks in the field place the vanilla cursor
        return false;
    }

    /** BK's send button: send through the screen like Enter does, but keep the chat open and the field empty. */
    private void send() {
        if (field == null || screen == null) return;
        String text = field.getText().trim();
        if (text.isEmpty()) return;
        screen.sendMessage(text, true);
        field.setText("");
    }

    /** From the chat screen's mouseScrolled. @return true when the scroll was ours */
    /** Whether the point is on the chat (messages or the input bar). */
    public static boolean covers(double mx, double my) {
        if (instance == null) return false;
        CustomChat c = instance;
        return inside(mx, my, c.x, c.y, c.width, c.height + INPUT_HEIGHT + 4f);
    }

    public static boolean scroll(double mouseX, double mouseY, double amount) {
        if (instance == null || !active()) return false;
        CustomChat c = instance;
        if (c.emojiPickerOpen) {
            float cellSize = 26.0F, gridPad = 4.0F;
            int cols = 6;
            float pickerW = cols * cellSize + gridPad * 2, pickerH = 130.0F;
            float emojiBtnX = c.x + c.width - 22f - 34f - 9f + 3f;
            float pickerX = Math.max(2.0F, Math.min(mc.getWindow().getScaledWidth() - pickerW - 2.0F, emojiBtnX - 5.0F));
            float pickerY = Math.max(2.0F, c.y + c.height + 4f - pickerH - 6.0F);
            if (inside(mouseX, mouseY, pickerX, pickerY, pickerW, pickerH)) {
                c.emojiPickerScroll += (float) (amount * 16.0F);
                return true;
            }
        }
        if (c.contentHeight > c.height && inside(mouseX, mouseY, c.x, c.y, c.width, c.height + INPUT_HEIGHT + 4.0F)) {
            c.scrollVelocity += (float) (amount * 8.0F);
            if (amount > 0.0) c.shouldAutoScroll = false;
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Messages                                                            */
    /* ------------------------------------------------------------------ */

    private String extractSender(String text) {
        String username = mc.getSession() != null ? mc.getSession().getUsername() : "";
        if (text.startsWith("<" + username + ">")) return username;
        int bracketEnd = text.indexOf('>');
        if (bracketEnd > 0 && text.startsWith("<")) return text.substring(1, bracketEnd);
        return "";
    }

    private static List<ClickSpan> extractClickables(Text text) {
        List<ClickSpan> spans = new ArrayList<>();
        int[] offset = {0};
        text.visit((style, part) -> {
            if (style.getClickEvent() != null) spans.add(new ClickSpan(offset[0], offset[0] + part.length(), style));
            offset[0] += part.length();
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return spans;
    }

    private record ClickSpan(int start, int end, Style style) {
    }

    private record ClickableRegion(float x, float y, float width, float height, Style style) {
        boolean contains(float mx, float my) {
            return mx >= x && mx <= x + width && my >= y && my <= y + height;
        }
    }

    private static final class ChatMessage {
        final String text, sender;
        final long timestamp;
        final List<ClickSpan> clickables;
        List<String> cachedLines;
        float cachedHeight = -1, cachedMaxLineW = -1, cachedWrapWidth = Float.NaN;

        ChatMessage(String text, String sender, long timestamp, List<ClickSpan> clickables) {
            this.text = text;
            this.sender = sender;
            this.timestamp = timestamp;
            this.clickables = clickables;
        }

        List<String> getLines(FontRenderer font, float maxTextW) {
            float layoutWidth = Math.max(1.0F, maxTextW);
            if (cachedLines == null || Float.compare(cachedWrapWidth, layoutWidth) != 0) {
                cachedLines = wrapText(text, font, layoutWidth);
                cachedWrapWidth = layoutWidth;
                cachedHeight = -1;
                cachedMaxLineW = -1;
            }
            return cachedLines;
        }

        float getHeight(FontRenderer font, float maxTextW, float fontH) {
            List<String> lines = getLines(font, maxTextW);
            if (cachedHeight < 0) {
                float msgH = 0;
                for (String line : lines) msgH += (line.contains("[") && line.contains("]")) ? 10.0F : fontH + 2.0F;
                cachedHeight = Math.max(fontH + MSG_PADDING_V * 2.0F, msgH + MSG_PADDING_V * 2.0F);
            }
            return cachedHeight;
        }

        float getMaxLineW(FontRenderer font, float maxTextW) {
            List<String> lines = getLines(font, maxTextW);
            if (cachedMaxLineW < 0) {
                float maxW = 0;
                for (String line : lines) maxW = Math.max(maxW, visualWidth(line, font));
                cachedMaxLineW = maxW;
            }
            return cachedMaxLineW;
        }
    }

    private static float visualWidth(String text, FontRenderer font) {
        float w = 0;
        int pos = 0;
        while (pos < text.length()) {
            if (text.charAt(pos) == '[') {
                int close = text.indexOf(']', pos);
                if (close != -1 && EMOJIS.containsKey(text.substring(pos + 1, close))) {
                    w += 9.0F;
                    pos = close + 1;
                    continue;
                }
            }
            int next = text.indexOf('[', pos + 1);
            if (next < 0) next = text.length();
            w += font.getStringWidth(text.substring(pos, next));
            pos = next;
        }
        return w;
    }

    private static List<String> wrapText(String text, FontRenderer font, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        StringBuilder testBuf = new StringBuilder();
        for (String word : text.split(" ")) {
            if (visualWidth(word, font) > maxWidth) {
                if (!currentLine.isEmpty()) {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                }
                testBuf.setLength(0);
                for (int i = 0; i < word.length(); i++) {
                    testBuf.append(word.charAt(i));
                    if (visualWidth(testBuf.toString(), font) > maxWidth && testBuf.length() > 1) {
                        lines.add(testBuf.substring(0, testBuf.length() - 1));
                        testBuf.setLength(0);
                        testBuf.append(word.charAt(i));
                    }
                }
                if (!testBuf.isEmpty()) currentLine.append(testBuf);
            } else {
                testBuf.setLength(0);
                if (!currentLine.isEmpty()) testBuf.append(currentLine).append(' ').append(word);
                else testBuf.append(word);
                if (visualWidth(testBuf.toString(), font) > maxWidth && !currentLine.isEmpty()) {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentLine.append(word);
                } else {
                    currentLine.setLength(0);
                    currentLine.append(testBuf);
                }
            }
        }
        if (!currentLine.isEmpty()) lines.add(currentLine.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /* ------------------------------------------------------------------ */
    /* BK's emoji set                                                      */
    /* ------------------------------------------------------------------ */

    private record Emoji(String code, Identifier texture) {
    }

    private static final Map<String, Emoji> EMOJIS = new LinkedHashMap<>();

    private static void register(String code, String file) {
        EMOJIS.put(code, new Emoji(code, Identifier.of("bkclient", "emojis/faces/" + file)));
    }

    static {
        register("grin", "grinning-face_1f600.png");
        register("happy", "grinning-face-with-big-eyes_1f603.png");
        register("smile", "grinning-face-with-smiling-eyes_1f604.png");
        register("sweat_smile", "grinning-face-with-sweat_1f605.png");
        register("lol", "grinning-squinting-face_1f606.png");
        register("beaming", "beaming-face-with-smiling-eyes_1f601.png");
        register("joy", "face-with-tears-of-joy_1f602.png");
        register("rofl", "rolling-on-the-floor-laughing_1f923.png");
        register("slight_smile", "slightly-smiling-face_1f642.png");
        register("upside_down", "upside-down-face_1f643.png");
        register("wink", "winking-face_1f609.png");
        register("blush", "smiling-face-with-smiling-eyes_1f60a.png");
        register("heart_eyes", "smiling-face-with-heart-eyes_1f60d.png");
        register("kiss", "face-blowing-a-kiss_1f618.png");
        register("tongue", "face-with-tongue_1f61b.png");
        register("squint_tongue", "squinting-face-with-tongue_1f61d.png");
        register("wink_tongue", "winking-face-with-tongue_1f61c.png");
        register("money", "money-mouth-face_1f911.png");
        register("hug", "hugging-face_1f917.png");
        register("thinking", "thinking-face_1f914.png");
        register("shush", "shushing-face_1f92b.png");
        register("oops", "face-with-hand-over-mouth_1f92d.png");
        register("shocked", "face-with-open-eyes-and-hand-over-mouth_1fae2.png");
        register("peek", "face-with-peeking-eye_1fae3.png");
        register("melting", "melting-face_1fae0.png");
        register("salute", "saluting-face_1fae1.png");
        register("star_struck", "star-struck_1f929.png");
        register("halo", "smiling-face-with-halo_1f607.png");
        register("hearts", "smiling-face-with-hearts_1f970.png");
        register("tear_smile", "smiling-face-with-tear_1f972.png");
        register("zany", "zany-face_1f92a.png");
        register("kiss_face", "kissing-face_1f617.png");
        register("kiss_closed", "kissing-face-with-closed-eyes_1f61a.png");
        register("kiss_smile", "kissing-face-with-smiling-eyes_1f619.png");
        register("smiling", "smiling-face_263a-fe0f.png");
        register("yummy", "face-savoring-food_1f60b.png");
    }

    private Emoji singleEmoji(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '[') return null;
        int close = text.indexOf(']');
        if (close != text.length() - 1) return null;
        return EMOJIS.get(text.substring(1, close));
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static Color accent() {
        return BkMusicUi.accent();
    }

    private static Color alpha(Color c, float a255) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(a255))));
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static void icon(DrawContext ctx, Identifier id, float x, float y, float size, Color color) {
        Render2DEngine.renderTexture(ctx, id, x, y, size, size, 0, 0, size, size, size, size, color);
    }

    private static void texture(DrawContext ctx, Identifier id, float x, float y, float size, Color color) {
        Render2DEngine.renderTexture(ctx, id, x, y, size, size, 0, 0, size, size, size, size, color);
    }
}
