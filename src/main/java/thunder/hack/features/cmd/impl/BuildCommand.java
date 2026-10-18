package thunder.hack.features.cmd.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.cmd.Command;
import thunder.hack.features.cmd.args.SchematicArgumentType;
import thunder.hack.features.modules.misc.AutoBuild;
import thunder.hack.gui.build.BuildScreen;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.world.Schematic;
import thunder.hack.utility.world.SchematicImporter;

import java.util.List;
import java.util.Map;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static thunder.hack.features.modules.client.ClientSettings.isRu;

/**
 * Litematica-style selection + printer control.
 * <p>
 * /build start -> first corner, /build end -> second corner (everything in between, interior included, is
 * captured), then save / load / place it wherever you want and let AutoBuild put it up.
 */
public class BuildCommand extends Command {
    public BuildCommand() {
        super("build", "autobuild");
    }

    @Override
    public void executeBuild(@NotNull LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("start").executes(context -> {
            BlockPos pos = targetedBlock();
            if (pos == null) return fail();

            AutoBuild.pos1 = pos;
            AutoBuild.pos2 = null;
            enableModule();
            sendMessage((isRu() ? "Первый угол: " : "First corner: ") + Formatting.AQUA + format(pos)
                    + Formatting.RESET + (isRu() ? " - иди в другой конец и напиши " : " - go to the other end and type ") + Formatting.GOLD + "/build end");
            return SINGLE_SUCCESS;
        }).then(arg("x", IntegerArgumentType.integer()).then(arg("y", IntegerArgumentType.integer()).then(arg("z", IntegerArgumentType.integer()).executes(context -> {
            AutoBuild.pos1 = argPos(context.getArgument("x", Integer.class), context.getArgument("y", Integer.class), context.getArgument("z", Integer.class));
            AutoBuild.pos2 = null;
            enableModule();
            sendMessage((isRu() ? "Первый угол: " : "First corner: ") + Formatting.AQUA + format(AutoBuild.pos1));
            return SINGLE_SUCCESS;
        })))));

        builder.then(literal("end").executes(context -> {
            if (AutoBuild.pos1 == null) {
                sendMessage(Formatting.RED + (isRu() ? "Сначала /build start!" : "Do /build start first!"));
                return SINGLE_SUCCESS;
            }

            BlockPos pos = targetedBlock();
            if (pos == null) return fail();

            AutoBuild.pos2 = pos;
            captureSelection();
            return SINGLE_SUCCESS;
        }).then(arg("x", IntegerArgumentType.integer()).then(arg("y", IntegerArgumentType.integer()).then(arg("z", IntegerArgumentType.integer()).executes(context -> {
            if (AutoBuild.pos1 == null) {
                sendMessage(Formatting.RED + (isRu() ? "Сначала /build start!" : "Do /build start first!"));
                return SINGLE_SUCCESS;
            }

            AutoBuild.pos2 = argPos(context.getArgument("x", Integer.class), context.getArgument("y", Integer.class), context.getArgument("z", Integer.class));
            captureSelection();
            return SINGLE_SUCCESS;
        })))));

        builder.then(literal("save").then(arg("name", SchematicArgumentType.create()).executes(context -> {
            if (AutoBuild.schematic == null) {
                sendMessage(Formatting.RED + (isRu() ? "Нечего сохранять - сделай выделение." : "Nothing to save - make a selection first."));
                return SINGLE_SUCCESS;
            }

            String name = context.getArgument("name", String.class);
            AutoBuild.schematic.setName(name);

            if (AutoBuild.schematic.save())
                sendMessage((isRu() ? "Сохранено: " : "Saved: ") + Formatting.GREEN + name + Formatting.RESET
                        + " (" + AutoBuild.schematic.size() + (isRu() ? " блоков)" : " blocks)"));
            else
                sendMessage(Formatting.RED + (isRu() ? "Не удалось сохранить!" : "Failed to save!"));

            return SINGLE_SUCCESS;
        })));

        builder.then(literal("load").then(arg("name", SchematicArgumentType.create()).executes(context -> {
            String name = context.getArgument("name", String.class);
            Schematic loaded = Schematic.loadAny(name);

            if (loaded == null) {
                sendMessage(Formatting.RED + (isRu() ? "Схематика не найдена: " : "Schematic not found: ") + name);
                return SINGLE_SUCCESS;
            }

            AutoBuild.schematic = loaded;
            AutoBuild.building = false;
            AutoBuild.rebuildQueue();
            enableModule();

            Vec3i dim = loaded.getDimensions();
            sendMessage((isRu() ? "Загружено: " : "Loaded: ") + Formatting.GREEN + name + Formatting.RESET
                    + " " + dim.getX() + "x" + dim.getY() + "x" + dim.getZ() + " (" + loaded.size() + (isRu() ? " блоков)" : " blocks)"));
            printMaterials(loaded);
            sendMessage(isRu() ? "Теперь смотри куда поставить и напиши " : "Now look where you want it and type ", Formatting.GOLD + "/build place");
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("list").executes(context -> {
            List<String> names = Schematic.getSavedNames();
            if (names.isEmpty()) sendMessage(isRu() ? "Нет сохраненных схематик." : "No saved schematics.");
            else sendMessage((isRu() ? "Схематики: " : "Schematics: ") + Formatting.AQUA + String.join(", ", names));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("folder").executes(context -> {
            Util.getOperatingSystem().open(Schematic.getFolder());
            sendMessage(isRu() ? "Кидай сюда .litematic / .nbt / .schem файлы." : "Drop .litematic / .nbt / .schem files in here.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("download").then(arg("url", StringArgumentType.greedyString()).executes(context -> {
            String url = context.getArgument("url", String.class).trim();

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                sendMessage(Formatting.RED + (isRu() ? "Нужна ссылка http(s)://" : "Need an http(s):// link"));
                return SINGLE_SUCCESS;
            }

            sendMessage(isRu() ? "Качаю..." : "Downloading...");

            // Off the render thread: a slow host would otherwise freeze the whole game.
            Thread thread = new Thread(() -> {
                try {
                    String fileName = SchematicImporter.download(url, null);
                    Schematic loaded = Schematic.loadAny(fileName);

                    if (loaded == null) {
                        mc.execute(() -> sendMessage(Formatting.RED + (isRu() ? "Скачано, но формат не читается: " : "Downloaded, but cannot read the format: ") + fileName));
                        return;
                    }

                    // Back on the main thread: the printer state is read by the tick and render loops.
                    mc.execute(() -> {
                        AutoBuild.schematic = loaded;
                        AutoBuild.building = false;
                        AutoBuild.rebuildQueue();
                        if (ModuleManager.autoBuild.isOff()) ModuleManager.autoBuild.enable();

                        Vec3i dim = loaded.getDimensions();
                        sendMessage((isRu() ? "Скачано и загружено: " : "Downloaded and loaded: ") + Formatting.GREEN + fileName + Formatting.RESET
                                + " " + dim.getX() + "x" + dim.getY() + "x" + dim.getZ() + " (" + loaded.size() + (isRu() ? " блоков)" : " blocks)"));
                        printMaterials(loaded);
                        sendMessage(Formatting.GRAY + (isRu() ? "Смотри куда поставить и напиши /build place" : "Look where you want it and type /build place"));
                    });
                } catch (Exception e) {
                    mc.execute(() -> sendMessage(Formatting.RED + (isRu() ? "Не скачалось: " : "Download failed: ") + e.getMessage()));
                }
            }, "Cloudan-SchematicDownload");
            thread.setDaemon(true);
            thread.start();

            return SINGLE_SUCCESS;
        })));

        builder.then(literal("delete").then(arg("name", SchematicArgumentType.create()).executes(context -> {
            String name = context.getArgument("name", String.class);
            if (Schematic.deleteAny(name))
                sendMessage((isRu() ? "Удалено: " : "Deleted: ") + Formatting.GREEN + name);
            else
                sendMessage(Formatting.RED + (isRu() ? "Схематика не найдена: " : "Schematic not found: ") + name);
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("place").executes(context -> {
            if (noSchematic()) return SINGLE_SUCCESS;

            BlockPos pos = placementTarget();
            if (pos == null) return fail();

            setOrigin(pos);
            return SINGLE_SUCCESS;
        }).then(arg("x", IntegerArgumentType.integer()).then(arg("y", IntegerArgumentType.integer()).then(arg("z", IntegerArgumentType.integer()).executes(context -> {
            if (noSchematic()) return SINGLE_SUCCESS;

            setOrigin(argPos(context.getArgument("x", Integer.class), context.getArgument("y", Integer.class), context.getArgument("z", Integer.class)));
            return SINGLE_SUCCESS;
        })))));

        builder.then(literal("offset").then(arg("x", IntegerArgumentType.integer()).then(arg("y", IntegerArgumentType.integer()).then(arg("z", IntegerArgumentType.integer()).executes(context -> {
            if (AutoBuild.origin == null) {
                sendMessage(Formatting.RED + (isRu() ? "Сначала /build place!" : "Do /build place first!"));
                return SINGLE_SUCCESS;
            }

            AutoBuild.origin = AutoBuild.origin.add(context.getArgument("x", Integer.class), context.getArgument("y", Integer.class), context.getArgument("z", Integer.class));
            AutoBuild.rebuildQueue();
            sendMessage((isRu() ? "Новая позиция: " : "New position: ") + Formatting.AQUA + format(AutoBuild.origin));
            return SINGLE_SUCCESS;
        })))));

        builder.then(literal("rotate").executes(context -> {
            if (noSchematic()) return SINGLE_SUCCESS;

            AutoBuild.schematic.rotate(BlockRotation.CLOCKWISE_90);
            AutoBuild.rebuildQueue();
            sendMessage(isRu() ? "Повернуто на 90°." : "Rotated by 90°.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("mirror").executes(context -> {
            if (noSchematic()) return SINGLE_SUCCESS;

            AutoBuild.schematic.mirror(BlockMirror.LEFT_RIGHT);
            AutoBuild.rebuildQueue();
            sendMessage(isRu() ? "Отзеркалено." : "Mirrored.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("materials").executes(context -> {
            if (noSchematic()) return SINGLE_SUCCESS;

            printMaterials(AutoBuild.schematic);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("mats").executes(context -> {
            if (noSchematic()) return SINGLE_SUCCESS;

            printMaterials(AutoBuild.schematic);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("go").executes(context -> {
            if (noSchematic()) return SINGLE_SUCCESS;

            if (AutoBuild.origin == null) {
                sendMessage(Formatting.RED + (isRu() ? "Сначала выбери место: /build place" : "Pick a spot first: /build place"));
                return SINGLE_SUCCESS;
            }

            AutoBuild.rebuildQueue();
            AutoBuild.building = true;
            enableModule();
            sendMessage(Formatting.GREEN + (isRu() ? "Строю! " : "Building! ") + Formatting.RESET + AutoBuild.getRemaining() + (isRu() ? " блоков осталось." : " blocks to go."));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("stop").executes(context -> {
            AutoBuild.building = false;
            sendMessage(isRu() ? "Стройка остановлена." : "Building stopped.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("gui").executes(context -> {
            // Deferred: the chat screen closes itself right after the command runs, and that close would
            // otherwise wipe the window we just opened.
            mc.execute(() -> mc.setScreen(new BuildScreen()));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            AutoBuild.pos1 = null;
            AutoBuild.pos2 = null;
            AutoBuild.origin = null;
            AutoBuild.schematic = null;
            AutoBuild.building = false;
            AutoBuild.rebuildQueue();
            sendMessage(isRu() ? "Все сброшено." : "Everything cleared.");
            return SINGLE_SUCCESS;
        }));

        builder.executes(context -> {
            sendMessage(Formatting.GOLD + "/build start" + Formatting.RESET + (isRu() ? " - первый угол" : " - first corner"));
            sendMessage(Formatting.GOLD + "/build end" + Formatting.RESET + (isRu() ? " - второй угол + список блоков" : " - second corner + material list"));
            sendMessage(Formatting.GOLD + "/build save|load|list|delete <name>" + Formatting.RESET + (isRu() ? " - схематики" : " - schematics"));
            sendMessage(Formatting.GOLD + "/build download <url>" + Formatting.RESET + (isRu() ? " - скачать .litematic из интернета" : " - grab a .litematic off the internet"));
            sendMessage(Formatting.GOLD + "/build folder" + Formatting.RESET + (isRu() ? " - папка со схематиками (.litematic/.nbt/.schem)" : " - schematics folder (.litematic/.nbt/.schem)"));
            sendMessage(Formatting.GOLD + "/build place [x y z]" + Formatting.RESET + (isRu() ? " - куда поставить" : " - where to put it"));
            sendMessage(Formatting.GOLD + "/build offset <x y z> | rotate | mirror" + Formatting.RESET + (isRu() ? " - подгонка" : " - fine tuning"));
            sendMessage(Formatting.GOLD + "/build mats" + Formatting.RESET + (isRu() ? " - что нужно" : " - what you need"));
            sendMessage(Formatting.GOLD + "/build go | stop | clear" + Formatting.RESET + (isRu() ? " - стройка" : " - the printer"));
            sendMessage(Formatting.GOLD + "/build gui" + Formatting.RESET + (isRu() ? " - окно с превью и настройками" : " - the window with the preview and the settings"));

            if (AutoBuild.schematic != null) {
                Vec3i dim = AutoBuild.schematic.getDimensions();
                sendMessage((isRu() ? "Текущая: " : "Current: ") + Formatting.AQUA + AutoBuild.schematic.getName() + Formatting.RESET
                        + " " + dim.getX() + "x" + dim.getY() + "x" + dim.getZ()
                        + " | " + (AutoBuild.getTotal() - AutoBuild.getRemaining()) + "/" + AutoBuild.getTotal());
            }

            return SINGLE_SUCCESS;
        });
    }

    private void captureSelection() {
        BlockPos min = AutoBuild.getMin(), max = AutoBuild.getMax();
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);

        if (volume > 2_000_000L) {
            sendMessage(Formatting.RED + (isRu() ? "Область слишком большая: " : "Region too big: ") + volume);
            return;
        }

        Schematic schematic = Schematic.capture("selection", AutoBuild.pos1, AutoBuild.pos2);
        AutoBuild.schematic = schematic;
        AutoBuild.origin = min;
        AutoBuild.building = false;
        AutoBuild.rebuildQueue();
        enableModule();

        Vec3i dim = schematic.getDimensions();
        sendMessage((isRu() ? "Второй угол: " : "Second corner: ") + Formatting.AQUA + format(AutoBuild.pos2));
        sendMessage((isRu() ? "Выделено " : "Selected ") + Formatting.GREEN + dim.getX() + "x" + dim.getY() + "x" + dim.getZ()
                + Formatting.RESET + " - " + Formatting.GREEN + schematic.size() + Formatting.RESET + (isRu() ? " блоков" : " blocks"));
        printMaterials(schematic);
        sendMessage(Formatting.GRAY + (isRu() ? "Сохрани: /build save <имя>, потом /build place и /build go"
                : "Save it: /build save <name>, then /build place and /build go"));
    }

    private void setOrigin(BlockPos pos) {
        AutoBuild.origin = pos;
        AutoBuild.building = false;
        AutoBuild.rebuildQueue();
        enableModule();

        sendMessage((isRu() ? "Постройка встанет тут: " : "Build anchored at: ") + Formatting.AQUA + format(pos)
                + Formatting.RESET + " - " + Formatting.GOLD + "/build go" + Formatting.RESET + (isRu() ? " чтобы начать" : " to start"));
    }

    private static void printMaterials(@NotNull Schematic schematic) {
        List<Map.Entry<Item, Integer>> materials = schematic.getMaterials();
        if (materials.isEmpty()) {
            sendMessage(isRu() ? "Пусто." : "Empty.");
            return;
        }

        sendMessage(isRu() ? "Нужно (есть/надо):" : "Materials (have/need):");

        int shown = 0;
        for (Map.Entry<Item, Integer> entry : materials) {
            if (shown++ >= 20) {
                sendMessage(Formatting.GRAY + "... +" + (materials.size() - 20) + (isRu() ? " типов блоков" : " more block types"));
                break;
            }

            int have = InventoryUtility.getItemCount(entry.getKey());
            Formatting color = have >= entry.getValue() ? Formatting.GREEN : (have > 0 ? Formatting.YELLOW : Formatting.RED);
            int stacks = entry.getValue() / 64;
            String stackInfo = stacks > 0 ? Formatting.GRAY + " (" + stacks + (isRu() ? " стаков)" : " stacks)") : "";

            sendMessage(color + entry.getKey().getName().getString() + Formatting.RESET + ": " + color + have + "/" + entry.getValue() + stackInfo);
        }
    }

    private boolean noSchematic() {
        if (AutoBuild.schematic == null) {
            sendMessage(Formatting.RED + (isRu() ? "Нет схематики - сделай /build start + /build end или /build load <имя>."
                    : "No schematic - do /build start + /build end, or /build load <name>."));
            return true;
        }
        return false;
    }

    private void enableModule() {
        if (ModuleManager.autoBuild.isOff()) ModuleManager.autoBuild.enable();
    }

    private static void sendMessage(String a, String b) {
        sendMessage(a + b);
    }

    private static int fail() {
        sendMessage(Formatting.RED + (isRu() ? "Не на что смотреть - наведись на блок." : "Nothing to aim at - look at a block."));
        return SINGLE_SUCCESS;
    }

    private static @NotNull String format(@NotNull BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static @NotNull BlockPos argPos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** The block you are looking at, falling back to the one you are standing on. */
    private static @Nullable BlockPos targetedBlock() {
        if (mc.player == null) return null;

        if (mc.crosshairTarget instanceof BlockHitResult bhr && mc.crosshairTarget.getType() == HitResult.Type.BLOCK)
            return bhr.getBlockPos();

        return mc.player.getBlockPos().down();
    }

    /** Where a block would go if you right clicked right now - the natural anchor for a paste. */
    private static @Nullable BlockPos placementTarget() {
        if (mc.player == null) return null;

        if (mc.crosshairTarget instanceof BlockHitResult bhr && mc.crosshairTarget.getType() == HitResult.Type.BLOCK)
            return bhr.getBlockPos().offset(bhr.getSide());

        return mc.player.getBlockPos();
    }
}
