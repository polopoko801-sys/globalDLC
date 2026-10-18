package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Podświetla blok pod krzyżykiem dymem, który płynie po jego ściankach, i ramką wciśniętą w jego wnętrze.
 *
 * <p>Moduł przepisany od zera, bo poprzednia wersja była zła w dwóch miejscach:
 *
 * <ul>
 *   <li><b>Rozmiar.</b> W 1.21.11 {@code AbstractBlock.getOutlineShape} zwraca {@code VoxelShapes.fullCube()},
 *       czyli kostkę 0..1 w <i>lokalnych</i> współrzędnych bloku - dlatego offset o pozycję jest potrzebny
 *       i został. To, co psuło rozmiar, to rysowanie {@code ThroughWalls} na stałe: wypełniona półprzezroczysta
 *       bryła widoczna przez teren wygląda o pół bloku (albo i więcej) większa niż sam blok. Domyślnie
 *       wyłączone, a wypełnienie trzyma się ścianek.</li>
 *   <li><b>Obrys.</b> Linia rysowana na krawędzi boxa wystaje z niego o połowę swojej grubości, czyli
 *       "wychodziła za blok". Teraz ramka jest cofnięta do środka o dokładnie tyle, ile wynosi połowa
 *       szerokości linii w jednostkach świata.</li>
 * </ul>
 *
 * <p>Napisy "wychodzi za blok" dotyczą obrysu, a "większe o 1.5 bloku" - wypełnienia widzianego przez ścianę.
 * Stąd dwa osobne zabiegi zamiast jednego "magicznego" rozszerzenia.
 */
public class BlockOverlay extends Module {
    public BlockOverlay() {
        super("BlockOverlay", Category.RENDER);
    }

    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(0xFFBB33FF)));
    private final Setting<Boolean> sync = new Setting<>("SyncColor", false);
    public enum FillMode {Plasma, Smoke, None}

    // Plasma = shader na ściankach bloku (block_plasma.fsh), inny wzór niż niebo: płynące komórki z żyłami energii
    private final Setting<FillMode> fillMode = new Setting<>("Fill", FillMode.Plasma);
    public enum PlasmaType {Veins, Liquid, Electric, Galaxy, Lava, Frost, Fire, Aurora, Hex, Rainbow, Storm, Toxic}

    private final Setting<PlasmaType> plasmaType = new Setting<>("PlasmaType", PlasmaType.Veins, v -> fillMode.is(FillMode.Plasma));
    private final Setting<ColorSetting> plasmaColor2 = new Setting<>("PlasmaColor2", new ColorSetting(0xFF22D3FF), v -> fillMode.is(FillMode.Plasma));
    private final Setting<Integer> plasmaAlpha = new Setting<>("PlasmaAlpha", 170, 20, 255, v -> fillMode.is(FillMode.Plasma));
    private final Setting<Float> plasmaSpeed = new Setting<>("PlasmaSpeed", 1f, 0f, 4f, v -> fillMode.is(FillMode.Plasma));
    private final Setting<Float> plasmaScale = new Setting<>("PlasmaScale", 1f, 0.3f, 4f, v -> fillMode.is(FillMode.Plasma));
    private final Setting<Float> plasmaIntensity = new Setting<>("PlasmaIntensity", 1f, 0.2f, 2f, v -> fillMode.is(FillMode.Plasma));
    private final Setting<Float> edgeGlow = new Setting<>("EdgeGlow", 0.8f, 0f, 2f, v -> fillMode.is(FillMode.Plasma));
    private final Setting<Integer> smokeAlpha = new Setting<>("SmokeAlpha", 95, 20, 255, v -> smoke());
    private final Setting<Float> speed = new Setting<>("FlowSpeed", 1f, 0f, 4f, v -> smoke());
    private final Setting<Float> tile = new Setting<>("Scale", 1.4f, 0.4f, 4f, v -> smoke());
    // Kolor dymu mieszany z bielą: przy pełnym nasyceniu neonu wyglądał tanio, a biel daje wrażenie
    // rozświetlonej mgły zamiast kolorowej plamy.
    private final Setting<Float> tint = new Setting<>("Whiteness", 0.55f, 0f, 1f, v -> smoke());
    private final Setting<Boolean> core = new Setting<>("Core", true, v -> smoke());
    private final Setting<Integer> coreAlpha = new Setting<>("CoreBrightness", 120, 20, 255, v -> smoke() && core.getValue());
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false, v -> !fillMode.is(FillMode.None));

    private float plasmaPhase;
    private long plasmaLast;

    private boolean smoke() {
        return fillMode.is(FillMode.Smoke);
    }
    private final Setting<Boolean> outline = new Setting<>("Outline", true);
    private final Setting<Float> lineWidth = new Setting<>("LineWidth", 1.2f, 0.5f, 4f, v -> outline.getValue());

    // Płynne przejście: ramka i plazma suną do nowego bloku zamiast przeskakiwać, a po zejściu z bloku gasną.
    private final Setting<Boolean> smooth = new Setting<>("Smooth", true);
    private final Setting<Float> smoothSpeed = new Setting<>("SmoothSpeed", 14f, 2f, 40f, v -> smooth.getValue());

    /** Pokazywana (animowana) bryła - obrys w 2D rysuje się do tej samej, co wypełnienie. */
    private Box current;
    /** Parts of the target block's outline shape (stairs = slab + step) and the box they fill; drawn mapped onto {@link #current}. */
    private List<Box> parts = List.of();
    private Box partsBounds;
    /** 0..1 widoczność: rośnie na bloku, gaśnie po zejściu z niego. */
    private float fade;
    private long lastFrame;

    @Override
    public void onDisable() {
        current = null;
        fade = 0f;
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (mc.world == null || mc.player == null) return;
        long now0 = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now0 - lastFrame) / 1e9f);
        lastFrame = now0;
        float k = smooth.getValue() ? 1f - (float) Math.exp(-dt * smoothSpeed.getValue()) : 1f;
        Box target = mc.crosshairTarget instanceof BlockHitResult bhr && mc.crosshairTarget.getType() == HitResult.Type.BLOCK
                ? blockBox(bhr) : null;
        if (target == null) {
            fade = smooth.getValue() ? Math.max(0f, fade - dt * smoothSpeed.getValue() * 0.6f) : 0f;
            if (fade <= 0.01f) {
                current = null;
                return;
            }
        } else {
            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            parts = blockParts(hit);
            partsBounds = target;
            current = current == null || fade <= 0.01f ? target : lerp(current, target, k);
            fade = smooth.getValue() ? Math.min(1f, fade + dt * smoothSpeed.getValue() * 0.6f) : 1f;
        }
        if (current == null) return;

        Color base = sync.getValue() ? HudEditor.getColor(0) : color.getValue().getColorObject();
        boolean shader = !thunder.hack.utility.render.BlockPlasmaRenderer.isBroken();
        // Obrys rysuje ten sam shader co plazmę (per piksel na tej samej bryle), więc nigdy nie odjeżdża od bloku.
        float lineW = outline.getValue() && !mc.options.hudHidden ? lineWidth.getValue() * (float) Render3DEngine.getScaleFactor() : 0f;
        if (fillMode.is(FillMode.None)) {
            if (shader && lineW > 0f) drawShader(base, 0f, lineW);
            return;
        }
        if (fillMode.is(FillMode.Plasma) && shader) {
            drawShader(base, plasmaAlpha.getValue() / 255f * fade, lineW);
            return;
        }

        // Kolor dymu mieszany z bielą: przy pełnym nasyceniu neonu wyglądał tanio, a biel daje wrażenie
        // rozświetlonej mgły zamiast kolorowej plamy.
        Color tinted = Render2DEngine.interpolateColorC(base, Color.WHITE, tint.getValue());
        // Tylko U. Sampler sprite'ów to REPEAT na U i CLAMP_TO_EDGE na V, więc scrollowanie V albo tile
        // większy od 1 rozciąga w pionie ostatni wiersz tekstury w smugi ("kreski" na ściankach).
        // V trzyma się 0..1, a powtarzanie w pionie zastępuje sam fakt, że tekstura jest cykliczna.
        float scroll = (System.currentTimeMillis() % 100000L) / 1000f * speed.getValue() * 0.12f;

        // Wysunięcie o 0.002 NA ZEWNĄTRZ: ściana na krawędzi z-fightuje, a cofnięta do środka chowa się za blokiem (niewidoczny dym).
        for (Box part : shownParts())
            Render3DEngine.drawTexturedBox3D(TextureStorage.fxSmoke, grow(part, 0.002),
                    scroll, 0f, tile.getValue(),
                    Render2DEngine.injectAlpha(tinted, Math.round(smokeAlpha.getValue() * fade)), throughWalls.getValue());

        // "Fajne w środku": mały rozświetlony rdzeń w centrum bloku, pulsujący. tego brakowało - sam dym jest
        // płaski, a świecące serce daje wrażenie, że blok podświetlono od środka.
        if (core.getValue()) {
            float pulse = 0.85f + 0.15f * (float) ((Math.sin(System.currentTimeMillis() / 1000.0 * 1.6) + 1) / 2);
            Box inner = grow(current, 0.002);
            float dx = (float) ((inner.maxX - inner.minX) * 0.28);
            float dy = (float) ((inner.maxY - inner.minY) * 0.28);
            float dz = (float) ((inner.maxZ - inner.minZ) * 0.28);
            Box heart = new Box(inner.minX + dx, inner.minY + dy, inner.minZ + dz,
                    inner.maxX - dx, inner.maxY - dy, inner.maxZ - dz);
            Render3DEngine.drawTexturedBox3D(TextureStorage.fxSmoke, heart,
                    scroll * 2.4f, 0f, tile.getValue() * 1.8f,
                    Render2DEngine.injectAlpha(Color.WHITE, Math.round(coreAlpha.getValue() * pulse * fade)),
                    throughWalls.getValue());
        }
        if (shader && lineW > 0f) drawShader(base, 0f, lineW);
    }

    /** Plazma (fillAlpha > 0) i/lub obrys jednym przebiegiem shadera na animowanej bryle. */
    private void drawShader(Color base, float fillAlpha, float lineW) {
        // zegar z realnego czasu klatki * prędkość - suwak nie przeskakuje wzoru
        long now = System.nanoTime();
        if (plasmaLast != 0) plasmaPhase += Math.min(0.1f, (now - plasmaLast) / 1e9f) * plasmaSpeed.getValue();
        plasmaLast = now;
        if (plasmaPhase > 10000f) plasmaPhase -= 10000f;
        thunder.hack.utility.render.BlockPlasmaRenderer.pattern = plasmaType.getValue().ordinal();
        for (Box part : shownParts())
            thunder.hack.utility.render.BlockPlasmaRenderer.render(part, base, plasmaColor2.getValue().getColorObject(),
                fillAlpha, plasmaScale.getValue(), plasmaPhase, plasmaIntensity.getValue(),
                edgeGlow.getValue(), lineW, fade, throughWalls.getValue());
    }

    @Override
    public void onRender2D(DrawContext context) {
        // zapasowy obrys 2D tylko gdy shader nie dziala - normalnie linie rysuje block_plasma.fsh
        if (!thunder.hack.utility.render.BlockPlasmaRenderer.isBroken()) return;
        if (!outline.getValue() || current == null || mc.options.hudHidden) return;
        Color base = sync.getValue() ? HudEditor.getColor(0) : color.getValue().getColorObject();

        // Ile pikseli ma blok w tym miejscu: rzutujemy punkt metr nad środkiem. Z tego liczymy, po ile
        // jednostek świata przypada piksel, i cofamy ramkę o połowę szerokości linii - inaczej linia
        // wychodzi o swoją połowę poza blok.
        for (Box part : shownParts()) drawFrame(context, part, base);
    }

    private void drawFrame(DrawContext context, Box current, Color base) {
        Vec3d centre = current.getCenter();
        float pixelsPerBlock = Math.abs((float) Render3DEngine.worldSpaceToScreenSpace(centre.add(0, 1, 0)).y
                - (float) Render3DEngine.worldSpaceToScreenSpace(centre).y);

        float inset = pixelsPerBlock > 0.01f ? (lineWidth.getValue() / 2f) / pixelsPerBlock : 0f;
        Box frame = inset > 0.0001f ? shrink(current, Math.min(inset, 0.05f)) : current;

        Render3DEngine.drawProjectedBox(context, frame, Render2DEngine.injectAlpha(base, 0),
                Render2DEngine.injectAlpha(base, 0), Render2DEngine.injectAlpha(base, Math.round(255 * fade)),
                lineWidth.getValue(), false, true);
    }

    /** Bryła bloku w układzie świata. Kształt z getOutlineShape jest lokalny (0..1), więc offsetujemy. */
    private static Box blockBox(BlockHitResult hit) {
        var shape = mc.world.getBlockState(hit.getBlockPos()).getOutlineShape(mc.world, hit.getBlockPos());
        if (shape.isEmpty()) return new Box(hit.getBlockPos());
        return shape.getBoundingBox().offset(hit.getBlockPos());
    }

    /** Every box of the block's outline shape (not just its bounding box), in world coordinates. */
    private static List<Box> blockParts(BlockHitResult hit) {
        var shape = mc.world.getBlockState(hit.getBlockPos()).getOutlineShape(mc.world, hit.getBlockPos());
        if (shape.isEmpty()) return List.of(new Box(hit.getBlockPos()));
        List<Box> out = new ArrayList<>();
        for (Box b : shape.getBoundingBoxes()) out.add(b.offset(hit.getBlockPos()));
        return out;
    }

    /** The shape's parts carried along with the animated box: each part keeps its place inside {@link #current}. */
    private List<Box> shownParts() {
        if (parts.size() <= 1 || partsBounds == null) return List.of(current);
        double sx = (current.maxX - current.minX) / Math.max(1e-6, partsBounds.maxX - partsBounds.minX);
        double sy = (current.maxY - current.minY) / Math.max(1e-6, partsBounds.maxY - partsBounds.minY);
        double sz = (current.maxZ - current.minZ) / Math.max(1e-6, partsBounds.maxZ - partsBounds.minZ);
        List<Box> out = new ArrayList<>(parts.size());
        for (Box p : parts)
            out.add(new Box(current.minX + (p.minX - partsBounds.minX) * sx, current.minY + (p.minY - partsBounds.minY) * sy, current.minZ + (p.minZ - partsBounds.minZ) * sz,
                    current.minX + (p.maxX - partsBounds.minX) * sx, current.minY + (p.maxY - partsBounds.minY) * sy, current.minZ + (p.maxZ - partsBounds.minZ) * sz));
        return out;
    }

    /** Ściąga box symetrycznie z każdej osi, nigdy nie schodząc do zera. */
    /** Każda krawędź bryły z osobna sunie do celu - przy zmianie kształtu (płyta → blok) też płynnie. */
    private static Box lerp(Box a, Box b, float k) {
        return new Box(a.minX + (b.minX - a.minX) * k, a.minY + (b.minY - a.minY) * k, a.minZ + (b.minZ - a.minZ) * k,
                a.maxX + (b.maxX - a.maxX) * k, a.maxY + (b.maxY - a.maxY) * k, a.maxZ + (b.maxZ - a.maxZ) * k);
    }

    private static Box grow(Box box, double amount) {
        return box.expand(amount);
    }

    private static Box shrink(Box box, float amount) {
        float dx = Math.min(amount, (float) ((box.maxX - box.minX) * 0.05));
        float dy = Math.min(amount, (float) ((box.maxY - box.minY) * 0.05));
        float dz = Math.min(amount, (float) ((box.maxZ - box.minZ) * 0.05));
        return new Box(box.minX + dx, box.minY + dy, box.minZ + dz,
                box.maxX - dx, box.maxY - dy, box.maxZ - dz);
    }
}
