package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * One F7/M7 terminal's board for the sim's terminal drill ({@link SimTerms}): what is in each slot of the chest
 * menu, and the real rules for a click on it. The server owns it ({@link SimTermMenu}), so every click is judged
 * here and the slots the client sees are whatever this wrote, as on Hypixel.
 *
 * <p>The layouts are Termism's ({@code features/dungeons/TermismScreen}, from Killer560's Mod, MIT), which already
 * match Hypixel's menus, cut to exactly the window sizes and slots the mod's solvers read
 * ({@code OdinTerminals}): the titles, the 4/5/6-row menus, red/lime panes, the numbered panes' stack counts, the
 * glint on a picked item and Melody's magenta column, lime pane and lime clay button. Termism's copy lives in a
 * client screen and is private to it, so the generation is repeated here rather than shared.
 * <ul>
 *   <li>Correct all the panes! (5 rows): red panes in rows 1-3, columns 2-6; a click turns red to lime and lime
 *   back to red.</li>
 *   <li>Change all to same color! (5 rows): the 3x3 in rows 1-3, columns 3-5; left click is the next colour of
 *   orange, yellow, green, blue, red, right click the one before. Solved when all nine match.</li>
 *   <li>Click in order! (4 rows): 10 red panes in rows 1-2, columns 2-6 (14 before the Minister update), numbered by stack size; the next number
 *   turns lime, anything else does nothing.</li>
 *   <li>What starts with: 'X'? (5 rows) and Select all the COLOUR items! (6 rows): every matching item gets the
 *   enchantment glint when clicked; a wrong item does nothing.</li>
 *   <li>Click the button on time! (Melody, 6 rows): three rows since Hypixel's 2026 dungeon update. A lime pane
 *   runs along columns 1-5 of the current row; the row's clay button (column 7) only counts while the pane is
 *   under the magenta column; pressed at the wrong moment, the pane stands still for 2 seconds.</li>
 * </ul>
 */
final class SimTermBoard {

    enum Type {
        PANES(5), RUBIX(5), NUMBERS(4), STARTS_WITH(5), SELECT(6), MELODY(6);

        final int rows;

        Type(int rows) {
            this.rows = rows;
        }
    }

    private static final int COLS = 9;
    private static final List<DyeColor> RUBIX_ORDER = List.of(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED);
    private static final List<DyeColor> SELECT_COLOURS = List.of(DyeColor.RED, DyeColor.GREEN, DyeColor.BLUE, DyeColor.BROWN, DyeColor.WHITE,
            DyeColor.BLACK, DyeColor.YELLOW, DyeColor.LIGHT_GRAY, DyeColor.ORANGE, DyeColor.PURPLE, DyeColor.CYAN, DyeColor.PINK, DyeColor.LIME,
            DyeColor.MAGENTA, DyeColor.GRAY, DyeColor.LIGHT_BLUE);
    /** Termism's pool. Items that already shine (enchanted book, bottle o' enchanting...) are dropped when used: a glint means "clicked". */
    private static final Item[] STARTS_WITH_POOL = {Items.STICK, Items.STRING, Items.SADDLE, Items.SNOWBALL, Items.SPONGE, Items.SLIME_BALL,
            Items.SHEARS, Items.SUGAR, Items.SHIELD, Items.SALMON, Items.SPIDER_EYE, Items.SUGAR_CANE, Items.FEATHER, Items.FLINT, Items.FISHING_ROD,
            Items.FURNACE, Items.FLOWER_POT, Items.FIREWORK_ROCKET, Items.EGG, Items.EMERALD, Items.ENDER_PEARL, Items.ELYTRA,
            Items.TORCH, Items.TNT, Items.TRIDENT, Items.TURTLE_EGG, Items.TRIPWIRE_HOOK, Items.TOTEM_OF_UNDYING,
            Items.LADDER, Items.LEAD, Items.LEATHER, Items.LAVA_BUCKET, Items.LANTERN, Items.LILY_PAD, Items.LAPIS_LAZULI, Items.BUCKET, Items.BREAD,
            Items.BONE, Items.BOOK, Items.BOW, Items.BRICK, Items.BELL, Items.BEETROOT, Items.BLAZE_ROD, Items.COAL, Items.COMPASS, Items.CLOCK,
            Items.CACTUS, Items.CARROT, Items.CHEST, Items.CHARCOAL, Items.CLAY_BALL, Items.COD, Items.DIAMOND, Items.DIAMOND_SWORD,
            Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_HELMET, Items.DIAMOND_BLOCK, Items.DRIED_KELP, Items.DIAMOND_BOOTS,
            Items.REDSTONE, Items.RAIL, Items.RABBIT, Items.ROTTEN_FLESH, Items.RABBIT_FOOT, Items.RAW_IRON, Items.QUARTZ, Items.PAPER,
            Items.PUMPKIN, Items.PUFFERFISH, Items.PRISMARINE_SHARD, Items.POTATO, Items.MAP, Items.MELON, Items.MILK_BUCKET, Items.MINECART,
            Items.MUSHROOM_STEW, Items.MAGMA_CREAM, Items.KELP, Items.ICE, Items.IRON_INGOT, Items.INK_SAC, Items.IRON_SWORD,
            Items.IRON_AXE, Items.IRON_HELMET, Items.OBSIDIAN, Items.OAK_BOAT, Items.OAK_SIGN, Items.OAK_DOOR, Items.APPLE, Items.ARROW,
            Items.ANVIL, Items.AMETHYST_SHARD, Items.ARMOR_STAND, Items.ANCIENT_DEBRIS, Items.GUNPOWDER, Items.GLOWSTONE_DUST,
            Items.GOLD_INGOT, Items.GLASS_BOTTLE, Items.GHAST_TEAR, Items.VINE, Items.COBWEB, Items.WHEAT, Items.WATER_BUCKET, Items.WOODEN_SWORD,
            Items.WRITABLE_BOOK, Items.WOODEN_AXE};

    /** Melody: three lanes since the 2026 update (Odin 0.3.6 reads buttons 16, 25 and 34). */
    private static final int MELODY_ROWS = 3;
    /** Server ticks between the lime pane's steps (Termism's "Normal", 350 ms). An approximation of Hypixel's speed. */
    private static final int MELODY_STEP_TICKS = 7;
    /** A button clicked off the magenta column: the pane waits 2 seconds before moving again (the wiki's Melody rule). */
    private static final int MELODY_WRONG_PAUSE_TICKS = 40;

    final Type type;
    private final Random random;
    private final int size;
    private final boolean[] puzzle, target, done;
    private final int[] number;
    private final DyeColor[] colour;
    private String title;
    private int nextNumber;
    private char letter;
    private DyeColor selectColour;
    private int melodyRow, melodyTarget, melodyMarker, melodyDir, melodyTicks, melodyPause;
    private boolean melodyDone;
    private Container container;

    private SimTermBoard(Type type, Random random) {
        this.type = type;
        this.random = random;
        this.size = type.rows * COLS;
        puzzle = new boolean[size];
        target = new boolean[size];
        done = new boolean[size];
        number = new int[size];
        colour = new DyeColor[size];
    }

    /** A fresh board of the terminal's own type (a terminal keeps its type for the run; each open is a new board). */
    static SimTermBoard of(Type type, Random random) {
        return new SimTermBoard(type, random);
    }

    /**
     * Each section's terminal types, {@code perSection[i]} terminals in section i+1: drawn without repeats from the
     * six types (a shuffled deck per section), so a section never gets the same terminal twice, and Melody at most
     * once. Only a section with more terminals than types (none on Hypixel: 4/5/4/4) draws a second deck, without
     * Melody. Hypixel's own draw is not published; this keeps a section's terminals spread over the types.
     */
    static List<List<Type>> assign(int[] perSection, Random random) {
        List<List<Type>> out = new ArrayList<>();
        for (int n : perSection) {
            List<Type> section = new ArrayList<>();
            List<Type> deck = new ArrayList<>(List.of(Type.values()));
            Collections.shuffle(deck, random);
            while (section.size() < n) {
                if (deck.isEmpty()) {
                    deck = new ArrayList<>(List.of(Type.values()));
                    deck.remove(Type.MELODY);
                    Collections.shuffle(deck, random);
                }
                section.add(deck.remove(deck.size() - 1));
            }
            out.add(section);
        }
        return out;
    }

    int rows() {
        return type.rows;
    }

    /** The menu title exactly as Hypixel writes it: what every terminal feature matches on. */
    String title() {
        if (title == null) {
            title = switch (type) {
                case PANES -> "Correct all the panes!";
                case RUBIX -> "Change all to same color!";
                case NUMBERS -> "Click in order!";
                case MELODY -> "Click the button on time!";
                // Picked now so the title is known before the menu opens; the items follow it in fill().
                case STARTS_WITH -> "What starts with: '" + (letter = pickLetter()) + "'?";
                case SELECT -> {
                    selectColour = SELECT_COLOURS.get(random.nextInt(SELECT_COLOURS.size()));
                    yield "Select all the " + selectColour.getName().replace('_', ' ').toUpperCase(Locale.ROOT) + " items!";
                }
            };
        }
        return title;
    }

    /** Lays the board into the menu's container. Call once, after {@link #title()}. */
    void fill(Container c) {
        container = c;
        title();
        for (int i = 0; i < size; i++) {
            c.setItem(i, black());
        }
        switch (type) {
            case PANES -> {
                List<Integer> slots = area(1, 3, 2, 6);
                int red = 4 + random.nextInt(slots.size() - 3);
                Collections.shuffle(slots, random);
                for (int i = 0; i < slots.size(); i++) {
                    int s = slots.get(i);
                    puzzle[s] = true;
                    target[s] = i < red;
                    c.setItem(s, pane(target[s] ? DyeColor.RED : DyeColor.LIME));
                }
            }
            case RUBIX -> {
                for (int s : area(1, 3, 3, 5)) {
                    puzzle[s] = true;
                    colour[s] = RUBIX_ORDER.get(random.nextInt(RUBIX_ORDER.size()));
                    c.setItem(s, pane(colour[s]));
                }
                // Already solved by chance: turn one pane on so there is something to do.
                if (rubixSolved()) {
                    int s = area(1, 3, 3, 5).get(random.nextInt(9));
                    colour[s] = RUBIX_ORDER.get((RUBIX_ORDER.indexOf(colour[s]) + 1) % RUBIX_ORDER.size());
                    c.setItem(s, pane(colour[s]));
                }
            }
            case NUMBERS -> {
                // Ten numbers since the Minister update (0.27.2: "from 14 → 10"), two rows of five.
                List<Integer> slots = area(1, 2, 2, 6);
                List<Integer> order = new ArrayList<>();
                for (int i = 1; i <= slots.size(); i++) {
                    order.add(i);
                }
                Collections.shuffle(order, random);
                for (int i = 0; i < slots.size(); i++) {
                    int s = slots.get(i);
                    puzzle[s] = true;
                    number[s] = order.get(i);
                    ItemStack st = pane(DyeColor.RED);
                    st.setCount(number[s]);
                    c.setItem(s, st);
                }
                nextNumber = 1;
            }
            case STARTS_WITH -> fillStartsWith(c);
            case SELECT -> fillSelect(c);
            case MELODY -> {
                melodyRow = 1;
                melodyTarget = 1 + random.nextInt(5);
                melodyMarker = 1;
                melodyDir = 1;
                melodyTicks = 0;
                drawMelody();
            }
        }
    }

    /** Slot numbers of rows r0..r1 and columns c0..c1. */
    private List<Integer> area(int r0, int r1, int c0, int c1) {
        List<Integer> out = new ArrayList<>();
        for (int r = r0; r <= r1 && r < type.rows; r++) {
            for (int col = c0; col <= c1; col++) {
                out.add(r * COLS + col);
            }
        }
        return out;
    }

    private static ItemStack pane(DyeColor c) {
        return new ItemStack(Items.STAINED_GLASS_PANE.pick(c));
    }

    /**
     * The filler pane, with a blank name as Hypixel's menus have: Odin's "What starts with" solver reads every
     * slot's name, and a "Black Stained Glass Pane" would count as a 'B'.
     */
    private static ItemStack black() {
        ItemStack st = new ItemStack(Items.STAINED_GLASS_PANE.black());
        st.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(" "));
        return st;
    }

    private static String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    private static boolean usable(Item item) {
        return !new ItemStack(item).hasFoil();
    }

    /** The letter with the most items out of a few random ones, so there are plenty to click (Termism's rule). */
    private char pickLetter() {
        char best = 'S';
        long bestCount = -1;
        for (int i = 0; i < 5; i++) {
            char l = name(STARTS_WITH_POOL[random.nextInt(STARTS_WITH_POOL.length)]).charAt(0);
            long count = 0;
            for (Item item : STARTS_WITH_POOL) {
                if (usable(item) && name(item).charAt(0) == l) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = l;
            }
        }
        return best;
    }

    private void fillStartsWith(Container c) {
        List<Integer> slots = area(1, 3, 1, 7);
        List<Item> matches = new ArrayList<>();
        List<Item> others = new ArrayList<>();
        for (Item item : STARTS_WITH_POOL) {
            if (usable(item)) {
                (name(item).charAt(0) == letter ? matches : others).add(item);
            }
        }
        Collections.shuffle(matches, random);
        Collections.shuffle(others, random);
        int count = Math.min(matches.size(), Math.max(3, slots.size() / 3 + random.nextInt(4)));
        List<Item> chosen = new ArrayList<>(matches.subList(0, count));
        for (int i = 0; chosen.size() < slots.size() && i < others.size(); i++) {
            chosen.add(others.get(i));
        }
        Collections.shuffle(chosen, random);
        for (int i = 0; i < slots.size() && i < chosen.size(); i++) {
            int s = slots.get(i);
            puzzle[s] = true;
            target[s] = name(chosen.get(i)).charAt(0) == letter;
            c.setItem(s, new ItemStack(chosen.get(i)));
        }
    }

    private void fillSelect(Container c) {
        DyeColor wanted = selectColour;
        List<Integer> slots = area(1, 4, 1, 7);
        int matches = 3 + random.nextInt(Math.max(1, slots.size() / 3));
        Collections.shuffle(slots, random);
        for (int i = 0; i < slots.size(); i++) {
            DyeColor col = i < matches ? wanted : SELECT_COLOURS.get(random.nextInt(SELECT_COLOURS.size()));
            int s = slots.get(i);
            puzzle[s] = true;
            colour[s] = col;
            target[s] = col == wanted;
            c.setItem(s, colourItem(col));
        }
    }

    /** A wool, glass, dye, concrete or terracotta of that colour, as Hypixel mixes them. */
    private ItemStack colourItem(DyeColor c) {
        return switch (random.nextInt(5)) {
            case 0 -> new ItemStack(Items.WOOL.pick(c));
            case 1 -> new ItemStack(Items.STAINED_GLASS.pick(c));
            case 2 -> new ItemStack(Items.DYE.pick(c));
            case 3 -> new ItemStack(Items.CONCRETE.pick(c));
            default -> new ItemStack(Items.DYED_TERRACOTTA.pick(c));
        };
    }

    private void drawMelody() {
        Container c = container;
        int bandEnd = MELODY_ROWS;
        for (int i = 0; i < size; i++) {
            int col = i % COLS;
            int row = i / COLS;
            boolean band = row >= 1 && row <= bandEnd;
            ItemStack st;
            // The magenta column marks the target above and below the lanes (rows 0 and 4); row 5 is filler.
            if (col == melodyTarget && (row == 0 || row == bandEnd + 1)) {
                st = pane(DyeColor.MAGENTA);
            } else if (row == melodyRow && col == melodyMarker) {
                st = pane(DyeColor.LIME);
            } else if (row == melodyRow && col >= 1 && col <= 5) {
                st = pane(DyeColor.RED);
            } else if (col == 7 && band) {
                st = new ItemStack(Items.DYED_TERRACOTTA.pick(row == melodyRow ? DyeColor.LIME : DyeColor.RED));
            } else if (band && col >= 1 && col <= 5) {
                st = pane(DyeColor.WHITE);
            } else {
                st = black();
            }
            if (!ItemStack.matches(c.getItem(i), st)) {
                c.setItem(i, st);
            }
        }
    }

    /** One server tick: Melody's pane moves. */
    void tick() {
        if (type != Type.MELODY || melodyDone || container == null) {
            return;
        }
        if (melodyPause > 0) {
            melodyPause--;
            return;
        }
        if (++melodyTicks < MELODY_STEP_TICKS) {
            return;
        }
        melodyTicks = 0;
        melodyMarker += melodyDir;
        if (melodyMarker >= 5 || melodyMarker <= 1) {
            melodyDir = -melodyDir;
        }
        drawMelody();
    }

    /** A click on a slot of the board: {@code button} 0 is left (and middle), 1 is right. */
    void click(int slot, int button) {
        if (solved() || slot < 0 || slot >= size || container == null) {
            return;
        }
        Container c = container;
        switch (type) {
            case PANES -> {
                if (!puzzle[slot]) {
                    return;
                }
                // Clicking a lime one turns it red again, like the real one.
                target[slot] = !target[slot];
                c.setItem(slot, pane(target[slot] ? DyeColor.RED : DyeColor.LIME));
            }
            case RUBIX -> {
                if (!puzzle[slot]) {
                    return;
                }
                int i = RUBIX_ORDER.indexOf(colour[slot]);
                colour[slot] = RUBIX_ORDER.get(Math.floorMod(i + (button == 1 ? -1 : 1), RUBIX_ORDER.size()));
                c.setItem(slot, pane(colour[slot]));
            }
            case NUMBERS -> {
                if (!puzzle[slot] || done[slot] || number[slot] != nextNumber) {
                    return;
                }
                done[slot] = true;
                ItemStack st = pane(DyeColor.LIME);
                st.setCount(number[slot]);
                c.setItem(slot, st);
                nextNumber++;
            }
            case STARTS_WITH, SELECT -> {
                if (!puzzle[slot] || done[slot] || !target[slot]) {
                    return;
                }
                done[slot] = true;
                ItemStack st = c.getItem(slot).copy();
                st.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                c.setItem(slot, st);
            }
            case MELODY -> {
                // Only the current lane's button counts, and only while the pane is under the magenta column. Pressing
                // it at the wrong moment holds the pane still for 2 seconds; while held, clicks do nothing.
                if (slot != melodyRow * COLS + 7 || melodyPause > 0) {
                    return;
                }
                if (melodyMarker != melodyTarget) {
                    melodyPause = MELODY_WRONG_PAUSE_TICKS;
                    return;
                }
                melodyRow++;
                if (melodyRow > MELODY_ROWS) {
                    melodyDone = true;
                    return;
                }
                melodyTarget = 1 + random.nextInt(5);
                melodyMarker = 1;
                melodyDir = 1;
                melodyTicks = 0;
                drawMelody();
            }
        }
    }

    private boolean rubixSolved() {
        DyeColor first = null;
        for (int i = 0; i < size; i++) {
            if (!puzzle[i]) {
                continue;
            }
            if (first == null) {
                first = colour[i];
            } else if (colour[i] != first) {
                return false;
            }
        }
        return true;
    }

    boolean solved() {
        if (container == null) {
            return false;
        }
        switch (type) {
            case RUBIX -> {
                return rubixSolved();
            }
            case MELODY -> {
                return melodyDone;
            }
            default -> {
                for (int i = 0; i < size; i++) {
                    if (!puzzle[i]) {
                        continue;
                    }
                    if (type == Type.PANES ? target[i] : type == Type.NUMBERS ? !done[i] : target[i] && !done[i]) {
                        return false;
                    }
                }
                return true;
            }
        }
    }
}
