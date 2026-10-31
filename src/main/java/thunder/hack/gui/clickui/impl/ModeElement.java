package thunder.hack.gui.clickui.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.clickui.AbstractElement;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;
import java.util.Objects;

import static thunder.hack.utility.render.animation.AnimationUtility.fast;

public class ModeElement extends AbstractElement {
    public Setting setting2;
    private boolean open;
    private double wheight;
    private String prevMode;

    private float animation, animation2;

    public ModeElement(Setting setting) {
        super(setting);
        this.setting2 = setting;
        prevMode = setting.currentEnumName();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        animation = fast(animation, open ? 0 : 1, 15f);
        animation2 = fast(animation2, 1f, 10f);

        float tx = x + width - 11;
        float ty = y + 7.5f;

        DrawContext matrixStack = context;

        float thetaRotation = -180f * animation;
        matrixStack.getMatrices().pushMatrix();

        matrixStack.getMatrices().translate((float)(tx), (float)(ty));
        matrixStack.getMatrices().rotate((float) Math.toRadians(thetaRotation));
        matrixStack.getMatrices().translate((float)(-tx), (float)(-ty));

        matrixStack.getMatrices().translate((float)((x + width - 14)), (float)(y + 4.5f));
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, TextureStorage.guiArrow, 0, 0, 0, 0, 6, 6, 6, 6);
        matrixStack.getMatrices().translate((float)(-(x + width - 14)), (float)(-y - 4.5f));

        matrixStack.getMatrices().popMatrix();

        if (setting.group != null)
            Render2DEngine.drawRect(context, x + 4, y, 1f, 17, HudEditor.getColor(1));


        FontRenderers.sf_medium_mini.drawString(matrixStack, setting2.getName(), (setting.group != null ? 2f : 0f) + (x + 6), (y + wheight / 2 - (6 / 2f)) + 3, new Color(-1).getRGB());

        if (animation2 < 0.99 && !Objects.equals(setting2.currentEnumName(), prevMode)) {
            FontRenderers.sf_medium_mini.drawString(matrixStack, prevMode, (int) (x + width - 18 - FontRenderers.sf_medium_mini.getStringWidth(prevMode)), 3 + (y + wheight / 2 - 3f) - animation2 * 5, Render2DEngine.applyOpacity(new Color(-1), animation2));
            FontRenderers.sf_medium_mini.drawString(matrixStack, setting2.currentEnumName(), (x + width - 18 - FontRenderers.sf_medium_mini.getStringWidth(setting2.currentEnumName())), 3 + (y + wheight / 2 - 3f) - animation2 * 5 + 5, Render2DEngine.applyOpacity(new Color(-1), 1f - animation2));
        } else
            FontRenderers.sf_medium_mini.drawString(matrixStack, setting2.currentEnumName(), (x + width - 18 - FontRenderers.sf_medium_mini.getStringWidth(setting.currentEnumName())), 3 + (y + wheight / 2 - 3f), new Color(-1).getRGB());

        // The list unrolls: the row grows (see listHeight), each mode slides and fades in a beat after the one
        // above, and the accent pill behind the chosen mode glides to a new choice instead of jumping.
        float amount = openAmount();
        if (amount > 0.01f) {
            Color color = HudEditor.getColor(0);
            String[] modes = setting2.getModes();
            int chosen = 0;
            for (int i = 0; i < modes.length; i++) if (setting2.currentEnumName().equalsIgnoreCase(modes[i])) chosen = i;
            pillY = pillY < 0f ? chosen : pillY + (chosen - pillY) * 0.25f;

            float visible = listHeight();
            float pillTop = (float) (y + wheight + pillY * 12f);
            if (pillY * 12f + 11f <= visible + 1f)
                Render2DEngine.drawRound(context, x + 6f, pillTop, width - 12f, 11f, 3f,
                        Render2DEngine.injectAlpha(color, Math.round(55 * amount)));

            for (int i = 0; i < modes.length; i++) {
                if (i * 12f + 10f > visible) break;
                float item = Math.clamp((amount * (modes.length + 2f) - i) / 3f, 0f, 1f);
                if (item <= 0.02f) continue;
                int a = Math.round(255 * item);
                int c = i == chosen ? Render2DEngine.injectAlpha(color, a).getRGB() : new Color(255, 255, 255, a).getRGB();
                float tx2 = x + width / 2f - FontRenderers.sf_medium_mini.getStringWidth(modes[i]) / 2f - (1f - item) * 8f;
                FontRenderers.sf_medium_mini.drawString(matrixStack, modes[i], tx2, (float) (y + wheight + 2 + i * 12), c);
            }
        }
    }

    /** Eased position of the highlight behind the chosen mode, in rows. */
    private float pillY = -1f;
    /** Open/close animation: where it started from and when. */
    private float fromAmount;
    private long toggledAt;
    private static final float OPEN_MS = 240f;

    /** How far the list is unrolled, 0..1, eased - and picking up from wherever it was if toggled mid-way. */
    public float openAmount() {
        float t = Math.clamp((System.currentTimeMillis() - toggledAt) / OPEN_MS, 0f, 1f);
        float u = 1f - t;
        float e = 1f - u * u * u;
        float target = open ? 1f : 0f;
        return fromAmount + (target - fromAmount) * e;
    }

    /** The height the unrolled part takes right now - the panel lays the row out with this. */
    public float listHeight() {
        return setting2.getModes().length * 12f * openAmount();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (Render2DEngine.isHovered(mouseX, mouseY, x, y, width, wheight)) {
            if (button == 0) {
                prevMode = setting2.currentEnumName();
                animation2 = 0;
                setting2.increaseEnum();
                Managers.SOUND.playBoolean();
            } else {
                fromAmount = openAmount();
                toggledAt = System.currentTimeMillis();
                open = !open;
                if (open) {
                    Managers.SOUND.playSwipeIn();
                } else {
                    Managers.SOUND.playSwipeOut();
                }
            }
        }

        if (open) {
            double offsetY = 0;
            for (int i = 0; i <= setting2.getModes().length - 1; i++) {
                if (Render2DEngine.isHovered(mouseX, mouseY, x, y + wheight + offsetY, width, 12) && button == 0) {
                    prevMode = setting2.currentEnumName();
                    animation2 = 0;
                    setting2.setEnumByNumber(i);
                    Managers.SOUND.playBoolean();
                }
                offsetY += 12;
            }
        }
        return;
    }

    public void setWHeight(double height) {
        this.wheight = height;
    }

    public boolean isOpen() {
        return open;
    }
}