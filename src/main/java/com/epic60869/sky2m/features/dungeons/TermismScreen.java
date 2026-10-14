package com.epic60869.sky2m.features.dungeons;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Termism: practice F7/M7 terminals, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod,
 * termism/), written for Sky2M. /s2 termism opens a fake terminal of the kind you pick (or a random one, never
 * Melody), laid out like the real chest menu, with its real mechanics:
 * <ul>
 *   <li>Correct all the panes: click the red panes to make them green.</li>
 *   <li>Change all to same color: left click a pane to move it to the next colour, right click to go back.</li>
 *   <li>Click in order: click the panes 1, 2, 3... in order.</li>
 *   <li>What starts with: click every item whose name starts with the letter.</li>
 *   <li>Select all the colour items: click every item of that colour.</li>
 *   <li>Click the button on time (Melody): click the row's button when the moving pane lines up with the purple one.</li>
 * </ul>
 * The timer starts with the board and stops when it's solved; your best time for each terminal is kept. Solution
 * highlights can be turned on to learn a terminal, and off to practise reading it yourself. Nothing here is a real
 * menu: it's all on your screen.
 */
public final class TermismScreen extends Screen {
    private static final Path PB_FILE = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("termism.json");
    private static final int SLOT = 18, COLS = 9;
    private static final int PANEL = 0xC0121722, EDGE = 0xFF3B465B, GOLD = 0xFFFFD35A, TEXT = 0xFF9AA5B8, BUTTON = 0xFF2A3345, BUTTON_HOVER = 0xFF3B5B8B;
    private static final List<DyeColor> RUBIX_ORDER = List.of(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED);
    private static final List<DyeColor> SELECT_COLOURS = List.of(DyeColor.RED, DyeColor.GREEN, DyeColor.BLUE, DyeColor.BROWN, DyeColor.WHITE,
        DyeColor.BLACK, DyeColor.YELLOW, DyeColor.LIGHT_GRAY, DyeColor.ORANGE, DyeColor.PURPLE, DyeColor.CYAN, DyeColor.PINK, DyeColor.LIME,
        DyeColor.MAGENTA, DyeColor.GRAY, DyeColor.LIGHT_BLUE);
    private static final Item[] STARTS_WITH_POOL = {Items.STICK, Items.STRING, Items.SADDLE, Items.SNOWBALL, Items.SPONGE, Items.SLIME_BALL,
        Items.SHEARS, Items.SUGAR, Items.SHIELD, Items.SALMON, Items.SPIDER_EYE, Items.SUGAR_CANE, Items.FEATHER, Items.FLINT, Items.FISHING_ROD,
        Items.FURNACE, Items.FLOWER_POT, Items.FIREWORK_ROCKET, Items.EGG, Items.EMERALD, Items.ENDER_PEARL, Items.ELYTRA, Items.ENCHANTED_BOOK,
        Items.EXPERIENCE_BOTTLE, Items.TORCH, Items.TNT, Items.TRIDENT, Items.TURTLE_EGG, Items.TRIPWIRE_HOOK, Items.TOTEM_OF_UNDYING,
        Items.LADDER, Items.LEAD, Items.LEATHER, Items.LAVA_BUCKET, Items.LANTERN, Items.LILY_PAD, Items.LAPIS_LAZULI, Items.BUCKET, Items.BREAD,
        Items.BONE, Items.BOOK, Items.BOW, Items.BRICK, Items.BELL, Items.BEETROOT, Items.BLAZE_ROD, Items.COAL, Items.COMPASS, Items.CLOCK,
        Items.CACTUS, Items.CARROT, Items.CHEST, Items.CHARCOAL, Items.CLAY_BALL, Items.COD, Items.DIAMOND, Items.DIAMOND_SWORD,
        Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_HELMET, Items.DIAMOND_BLOCK, Items.DRIED_KELP, Items.DIAMOND_BOOTS,
        Items.REDSTONE, Items.RAIL, Items.RABBIT, Items.ROTTEN_FLESH, Items.RABBIT_FOOT, Items.RAW_IRON, Items.QUARTZ, Items.PAPER,
        Items.PUMPKIN, Items.PUFFERFISH, Items.PRISMARINE_SHARD, Items.POTATO, Items.MAP, Items.MELON, Items.MILK_BUCKET, Items.MINECART,
        Items.MUSHROOM_STEW, Items.MAGMA_CREAM, Items.KELP, Items.KNOWLEDGE_BOOK, Items.ICE, Items.IRON_INGOT, Items.INK_SAC, Items.IRON_SWORD,
        Items.IRON_AXE, Items.IRON_HELMET, Items.OBSIDIAN, Items.OAK_BOAT, Items.OAK_SIGN, Items.OAK_DOOR, Items.APPLE, Items.ARROW,
        Items.ANVIL, Items.AMETHYST_SHARD, Items.ARMOR_STAND, Items.ANCIENT_DEBRIS, Items.GUNPOWDER, Items.GOLDEN_APPLE, Items.GLOWSTONE_DUST,
        Items.GOLD_INGOT, Items.GLASS_BOTTLE, Items.GHAST_TEAR, Items.VINE, Items.COBWEB, Items.WHEAT, Items.WATER_BUCKET, Items.WOODEN_SWORD,
        Items.WRITABLE_BOOK, Items.WOODEN_AXE};

    enum Type {
        PANES("Correct all the panes!", 5), RUBIX("Change all to same color!", 5), NUMBERS("Click in order!", 4),
        STARTS_WITH("What starts with", 5), SELECT("Select all the", 6), MELODY("Click the button on time!", 6);

        final String title;
        final int rows;

        Type(String title, int rows) {
            this.title = title;
            this.rows = rows;
        }
    }

    private static final Type[] RANDOM_TYPES = {Type.PANES, Type.RUBIX, Type.NUMBERS, Type.STARTS_WITH, Type.SELECT};

    /** One slot of the board: what's in it and what it means. */
    private static final class Cell {
        ItemStack stack;
        boolean puzzle, target, done;
        int number;
        DyeColor colour;

        Cell(ItemStack stack) {
            this.stack = stack;
        }
    }

    private final Random random = new Random();
    private Type type;
    private boolean menu = true;
    private Cell[] cells;
    private String title;
    private long startedAt, solvedAt;
    private int mistakes, nextNumber;
    private char letter;
    private DyeColor colour;
    private int melodyRows = 4, melodyRow, melodyTarget, melodyMarker, melodyDir = 1;
    private long melodyMovedAt;
    private int melodySpeed = 1;
    private boolean highlights;
    private final List<int[]> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    public TermismScreen() {
        super(Component.literal("Termism"));
    }

    /** /s2 termism opens the practice menu. */
    public static void registerCommand() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : com.epic60869.sky2m.custom.util.Compat.COMMAND_ROOTS) {
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(root)
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("termism").executes(c -> {
                        com.epic60869.sky2m.custom.util.Compat.queueOpenScreen(new TermismScreen());
                        return 1;
                    })));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ boards

    private void start(Type t) {
        type = t;
        menu = false;
        mistakes = 0;
        solvedAt = 0;
        startedAt = System.currentTimeMillis();
        int rows = t == Type.MELODY ? melodyRows + 2 : t.rows;
        cells = new Cell[rows * COLS];
        for (int i = 0; i < cells.length; i++) cells[i] = new Cell(new ItemStack(Items.STAINED_GLASS_PANE.black()));
        switch (t) {
            case PANES -> {
                title = t.title;
                List<Integer> slots = area(rows, 1, 3, 2, 6);
                int red = 4 + random.nextInt(slots.size() - 3);
                Collections.shuffle(slots, random);
                for (int i = 0; i < slots.size(); i++) puzzle(slots.get(i), pane(i < red ? DyeColor.RED : DyeColor.LIME)).target = i < red;
            }
            case RUBIX -> {
                title = t.title;
                for (int slot : area(rows, 1, 3, 3, 5)) {
                    Cell c = puzzle(slot, null);
                    c.colour = RUBIX_ORDER.get(random.nextInt(RUBIX_ORDER.size()));
                    c.stack = pane(c.colour);
                }
            }
            case NUMBERS -> {
                title = t.title;
                List<Integer> slots = area(rows, 1, 2, 1, 7);
                List<Integer> order = new ArrayList<>();
                for (int i = 1; i <= slots.size(); i++) order.add(i);
                Collections.shuffle(order, random);
                for (int i = 0; i < slots.size(); i++) {
                    Cell c = puzzle(slots.get(i), pane(DyeColor.RED));
                    c.number = order.get(i);
                    c.stack.setCount(c.number);
                }
                nextNumber = 1;
            }
            case STARTS_WITH -> startsWith(rows);
            case SELECT -> select(rows);
            case MELODY -> {
                title = t.title;
                melodyRow = 1;
                melodyTarget = 1 + random.nextInt(5);
                melodyMarker = 1;
                melodyDir = 1;
                melodyMovedAt = System.currentTimeMillis();
                rebuildMelody();
            }
        }
    }

    /** Slot numbers of rows r0..r1 and columns c0..c1 of a board {@code rows} high. */
    private static List<Integer> area(int rows, int r0, int r1, int c0, int c1) {
        List<Integer> out = new ArrayList<>();
        for (int r = r0; r <= r1 && r < rows; r++) for (int c = c0; c <= c1; c++) out.add(r * COLS + c);
        return out;
    }

    private Cell puzzle(int slot, ItemStack stack) {
        Cell c = cells[slot];
        c.puzzle = true;
        if (stack != null) c.stack = stack;
        return c;
    }

    private static ItemStack pane(DyeColor colour) {
        return new ItemStack(Items.STAINED_GLASS_PANE.pick(colour));
    }

    private void startsWith(int rows) {
        List<Integer> slots = area(rows, 1, 3, 1, 7);
        // Pick the letter with the most items out of a few random ones, so there are plenty to click.
        char best = 'S';
        long bestCount = -1;
        for (int i = 0; i < 5; i++) {
            char l = name(STARTS_WITH_POOL[random.nextInt(STARTS_WITH_POOL.length)]).charAt(0);
            long count = 0;
            for (Item item : STARTS_WITH_POOL) if (name(item).charAt(0) == l) count++;
            if (count > bestCount) {
                bestCount = count;
                best = l;
            }
        }
        letter = best;
        title = "What starts with: '" + letter + "'?";
        List<Item> matches = new ArrayList<>(), others = new ArrayList<>();
        for (Item item : STARTS_WITH_POOL) (name(item).charAt(0) == letter ? matches : others).add(item);
        Collections.shuffle(matches, random);
        Collections.shuffle(others, random);
        int count = Math.min(matches.size(), Math.max(3, slots.size() / 3 + random.nextInt(4)));
        List<Item> chosen = new ArrayList<>(matches.subList(0, count));
        for (int i = 0; chosen.size() < slots.size() && i < others.size(); i++) chosen.add(others.get(i));
        Collections.shuffle(chosen, random);
        for (int i = 0; i < slots.size() && i < chosen.size(); i++) puzzle(slots.get(i), new ItemStack(chosen.get(i))).target = name(chosen.get(i)).charAt(0) == letter;
    }

    private void select(int rows) {
        List<Integer> slots = area(rows, 1, 4, 1, 7);
        colour = SELECT_COLOURS.get(random.nextInt(SELECT_COLOURS.size()));
        title = "Select all the " + colour.getName().replace('_', ' ').toUpperCase(Locale.ROOT) + " items!";
        int matches = 3 + random.nextInt(Math.max(1, slots.size() / 3));
        Collections.shuffle(slots, random);
        for (int i = 0; i < slots.size(); i++) {
            DyeColor c = i < matches ? colour : SELECT_COLOURS.get(random.nextInt(SELECT_COLOURS.size()));
            Cell cell = puzzle(slots.get(i), colourItem(c));
            cell.colour = c;
            cell.target = c == colour;
        }
    }

    /** A wool, glass, dye, concrete or terracotta of that colour. */
    private ItemStack colourItem(DyeColor c) {
        return switch (random.nextInt(5)) {
            case 0 -> new ItemStack(Items.WOOL.pick(c));
            case 1 -> new ItemStack(Items.STAINED_GLASS.pick(c));
            case 2 -> new ItemStack(Items.DYE.pick(c));
            case 3 -> new ItemStack(Items.CONCRETE.pick(c));
            default -> new ItemStack(Items.DYED_TERRACOTTA.pick(c));
        };
    }

    private String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    private void rebuildMelody() {
        int rows = melodyRows + 2;
        for (int i = 0; i < cells.length; i++) {
            int col = i % COLS, row = i / COLS;
            boolean band = row >= 1 && row <= melodyRows;
            Cell c = cells[i];
            c.puzzle = false;
            if (col == melodyTarget && !band) c.stack = pane(DyeColor.MAGENTA);
            else if (row == melodyRow && col == melodyMarker) c.stack = pane(DyeColor.LIME);
            else if (row == melodyRow && col >= 1 && col <= 5) c.stack = pane(DyeColor.RED);
            else if (col == 7 && band) {
                c.stack = new ItemStack(Items.DYED_TERRACOTTA.pick(row == melodyRow ? DyeColor.LIME : DyeColor.RED));
                c.puzzle = row == melodyRow;
            } else if (band && col >= 1 && col <= 5) c.stack = pane(DyeColor.WHITE);
            else c.stack = new ItemStack(Items.STAINED_GLASS_PANE.black());
            if (row >= rows) c.stack = ItemStack.EMPTY;
        }
    }

    private long melodyInterval() {
        return switch (melodySpeed) {
            case 0 -> 500;
            case 2 -> 250;
            default -> 350;
        };
    }

    // ------------------------------------------------------------------------------------------------ clicks

    private void click(int slot, int button) {
        if (solvedAt > 0 || slot < 0 || slot >= cells.length) return;
        Cell c = cells[slot];
        switch (type) {
            case PANES -> {
                if (!c.puzzle) return;
                if (c.target) {
                    c.target = false;
                    c.stack = pane(DyeColor.LIME);
                } else {
                    // Clicking a green one turns it red again, like the real one.
                    c.target = true;
                    c.stack = pane(DyeColor.RED);
                    mistakes++;
                }
            }
            case RUBIX -> {
                if (!c.puzzle) return;
                int i = RUBIX_ORDER.indexOf(c.colour);
                c.colour = RUBIX_ORDER.get(Math.floorMod(i + (button == 1 ? -1 : 1), RUBIX_ORDER.size()));
                c.stack = pane(c.colour);
            }
            case NUMBERS -> {
                if (!c.puzzle || c.done) return;
                if (c.number == nextNumber) {
                    c.done = true;
                    c.stack = pane(DyeColor.LIME);
                    c.stack.setCount(c.number);
                    nextNumber++;
                } else {
                    mistakes++;
                }
            }
            case STARTS_WITH, SELECT -> {
                if (!c.puzzle || c.done) return;
                if (c.target) {
                    c.done = true;
                    c.stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                } else {
                    mistakes++;
                }
            }
            case MELODY -> {
                if (!c.puzzle) return;
                if (melodyMarker == melodyTarget) {
                    melodyRow++;
                    if (melodyRow > melodyRows) {
                        solve();
                        return;
                    }
                    melodyTarget = 1 + random.nextInt(5);
                    melodyMarker = 1;
                    melodyDir = 1;
                    melodyMovedAt = System.currentTimeMillis();
                    rebuildMelody();
                } else {
                    mistakes++;
                }
                return;
            }
        }
        if (solved()) solve();
    }

    private boolean solved() {
        for (Cell c : cells) {
            if (!c.puzzle) continue;
            switch (type) {
                case PANES -> {
                    if (c.target) return false;
                }
                case RUBIX -> {
                    for (Cell o : cells) if (o.puzzle && o.colour != c.colour) return false;
                    return true;
                }
                case NUMBERS -> {
                    if (!c.done) return false;
                }
                case STARTS_WITH, SELECT -> {
                    if (c.target && !c.done) return false;
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    private void solve() {
        solvedAt = System.currentTimeMillis();
        long time = solvedAt - startedAt;
        JsonObject pbs = pbs();
        String key = type.name();
        if (!pbs.has(key) || pbs.get(key).getAsLong() > time) {
            pbs.addProperty(key, time);
            savePbs(pbs);
            newPb = true;
        } else {
            newPb = false;
        }
    }

    private boolean newPb;

    private static JsonObject pbs() {
        try {
            if (Files.exists(PB_FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(PB_FILE), JsonObject.class);
                if (o != null) return o;
            }
        } catch (Exception ignored) {}
        return new JsonObject();
    }

    private static void savePbs(JsonObject pbs) {
        try {
            Files.createDirectories(PB_FILE.getParent());
            Files.writeString(PB_FILE, pbs.toString());
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean selected, int mouseX, int mouseY, Runnable action) {
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, selected ? BUTTON_HOVER : over ? 0xFF33405A : BUTTON);
        g.outline(x, y, w, h, EDGE);
        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, selected ? GOLD : 0xFFFFFFFF);
        buttons.add(new int[]{x, y, w, h});
        actions.add(action);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        buttons.clear();
        actions.clear();
        if (menu) {
            drawMenu(g, mouseX, mouseY);
            return;
        }
        if (type == Type.MELODY && solvedAt == 0 && System.currentTimeMillis() - melodyMovedAt >= melodyInterval()) {
            melodyMovedAt = System.currentTimeMillis();
            melodyMarker += melodyDir;
            if (melodyMarker >= 5 || melodyMarker <= 1) melodyDir = -melodyDir;
            rebuildMelody();
        }
        int rows = cells.length / COLS;
        int w = COLS * SLOT + 14, h = rows * SLOT + 24;
        int x = (width - w) / 2, y = (height - h) / 2 - 20;
        // The chest, like the real menu.
        g.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        g.outline(x, y, w, h, 0xFF555555);
        g.text(font, title, x + 7, y + 6, 0xFF404040, false);
        int[] rubixCost = highlights && type == Type.RUBIX ? rubixClicks() : null;
        for (int i = 0; i < cells.length; i++) {
            int sx = x + 7 + (i % COLS) * SLOT, sy = y + 17 + (i / COLS) * SLOT;
            g.fill(sx, sy, sx + SLOT, sy + SLOT, 0xFF8B8B8B);
            g.fill(sx + 1, sy + 1, sx + SLOT, sy + SLOT, 0xFFFFFFFF);
            g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0xFF8B8B8B);
            Cell c = cells[i];
            if (highlights && solvedAt == 0 && highlighted(c)) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0xC055FF55);
            g.item(c.stack, sx + 1, sy + 1);
            g.itemDecorations(font, c.stack, sx + 1, sy + 1);
            if (rubixCost != null && c.puzzle && rubixCost[i] != 0) {
                String n = String.valueOf(rubixCost[i]);
                g.text(font, n, sx + 9 - font.width(n) / 2, sy + 5, rubixCost[i] > 0 ? 0xFF55FF55 : 0xFFFF5555, true);
            }
            if (mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x80FFFFFF);
        }

        // Timer and controls below, in Sky2M's colours.
        int by = y + h + 6;
        g.fill(x, by, x + w, by + 52, PANEL);
        g.outline(x, by, w, 52, EDGE);
        long now = solvedAt > 0 ? solvedAt : System.currentTimeMillis();
        String time = String.format(Locale.ROOT, "%.2fs", (now - startedAt) / 1000.0);
        JsonObject pbs = pbs();
        String pb = pbs.has(type.name()) ? String.format(Locale.ROOT, "%.2fs", pbs.get(type.name()).getAsLong() / 1000.0) : "-";
        g.text(font, (solvedAt > 0 ? "§aSolved in " : "§7Time: §f") + time + (solvedAt > 0 && newPb ? " §6(new PB!)" : ""), x + 6, by + 5, 0xFFFFFFFF, true);
        g.text(font, "§7PB: §6" + pb + "   §7Misclicks: §c" + mistakes, x + 6, by + 16, 0xFFFFFFFF, true);
        int bw = (w - 16) / 3;
        button(g, x + 4, by + 30, bw, 16, "New (R)", false, mouseX, mouseY, () -> start(type));
        button(g, x + 8 + bw, by + 30, bw, 16, "Random", false, mouseX, mouseY, () -> start(RANDOM_TYPES[random.nextInt(RANDOM_TYPES.length)]));
        button(g, x + 12 + 2 * bw, by + 30, bw, 16, "Menu", false, mouseX, mouseY, () -> menu = true);
        button(g, x + w - 92, by + 4, 88, 12, "Solution: " + (highlights ? "On" : "Off"), highlights, mouseX, mouseY, () -> highlights = !highlights);
        if (type == Type.MELODY) {
            String[] speeds = {"Slow", "Normal", "Fast"};
            button(g, x + w - 92, by + 17, 88, 12, "Speed: " + speeds[melodySpeed], false, mouseX, mouseY, () -> melodySpeed = (melodySpeed + 1) % 3);
        }
    }

    /** Whether the solution highlights this slot. */
    private boolean highlighted(Cell c) {
        if (!c.puzzle) return false;
        return switch (type) {
            case PANES -> c.target;
            case NUMBERS -> !c.done && c.number == nextNumber;
            case STARTS_WITH, SELECT -> c.target && !c.done;
            case MELODY -> melodyMarker == melodyTarget;
            default -> false;
        };
    }

    /** Rubix: clicks each pane needs to reach the colour that takes the fewest clicks (positive: left, negative: right). */
    private int[] rubixClicks() {
        int n = RUBIX_ORDER.size(), best = -1, bestTotal = Integer.MAX_VALUE;
        for (int t = 0; t < n; t++) {
            int total = 0;
            for (Cell c : cells) {
                if (!c.puzzle) continue;
                int d = Math.floorMod(t - RUBIX_ORDER.indexOf(c.colour), n);
                total += Math.min(d, n - d);
            }
            if (total < bestTotal) {
                bestTotal = total;
                best = t;
            }
        }
        int[] out = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            if (!cells[i].puzzle) continue;
            int d = Math.floorMod(best - RUBIX_ORDER.indexOf(cells[i].colour), n);
            out[i] = d <= n - d ? d : -(n - d);
        }
        return out;
    }

    private void drawMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int w = 220, x = (width - w) / 2, y = height / 2 - 110;
        g.fill(x - 8, y - 30, x + w + 8, y + 196, PANEL);
        g.outline(x - 8, y - 30, w + 16, 226, EDGE);
        g.centeredText(font, "Termism: Terminal Practice", width / 2, y - 22, GOLD);
        g.centeredText(font, "A fake terminal to practise on, never a real one.", width / 2, y - 11, TEXT);
        JsonObject pbs = pbs();
        button(g, x, y, w, 18, "Random", false, mouseX, mouseY, () -> start(RANDOM_TYPES[random.nextInt(RANDOM_TYPES.length)]));
        y += 24;
        for (Type t : Type.values()) {
            String pb = pbs.has(t.name()) ? String.format(Locale.ROOT, "  §7(PB %.2fs)", pbs.get(t.name()).getAsLong() / 1000.0) : "";
            String label = switch (t) {
                case STARTS_WITH -> "What starts with: '?'";
                case SELECT -> "Select all the ... items!";
                default -> t.title;
            };
            button(g, x, y, w, 18, label + pb, false, mouseX, mouseY, () -> start(t));
            y += 22;
        }
        y += 4;
        button(g, x, y, w / 2 - 2, 16, "Melody rows: " + melodyRows, false, mouseX, mouseY, () -> melodyRows = melodyRows == 4 ? 3 : 4);
        button(g, x + w / 2 + 2, y, w / 2 - 2, 16, "Solution: " + (highlights ? "On" : "Off"), highlights, mouseX, mouseY, () -> highlights = !highlights);
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = 0; i < buttons.size(); i++) {
            int[] b = buttons.get(i);
            if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
                actions.get(i).run();
                return true;
            }
        }
        if (!menu && cells != null) {
            int rows = cells.length / COLS;
            int w = COLS * SLOT + 14, h = rows * SLOT + 24;
            int x = (width - w) / 2 + 7, y = (height - h) / 2 - 20 + 17;
            int col = (int) Math.floor((event.x() - x) / SLOT), row = (int) Math.floor((event.y() - y) / SLOT);
            if (col >= 0 && col < COLS && row >= 0 && row < rows) {
                click(row * COLS + col, event.button());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!menu && event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_R) {
            start(type);
            return true;
        }
        if (!menu && event.isEscape()) {
            menu = true;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
