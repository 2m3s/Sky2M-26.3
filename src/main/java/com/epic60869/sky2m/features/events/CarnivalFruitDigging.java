package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.events.WorldEvents;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Events > Carnival > Fruit Digging: labels the 7x7 sand board with what each dig told you (bombs nearby, the
 * Treasure and Anchor fruit hints and the fruit found), counts the fruit still hidden and points at the best next dig.
 * Ported from SkyHanni's CarnivalFruitDigging (https://github.com/hannibal002/SkyHanni, features/event/carnival/,
 * LGPL-2.1); the solver is in {@link FruitDiggingSolver}.
 */
public final class CarnivalFruitDigging {
    private static final int SIZE = FruitDiggingSolver.SIZE;
    private static final String REMAINING_HUD = "events_carnival_fruit_remaining";
    private static final String SOLVER_HUD = "events_carnival_fruit_solver";

    private static final Pattern TEXTURE_URL = Pattern.compile("texture/([0-9a-f]+)");

    public enum Fruit {
        UNKNOWN("Unknown", 0, 0, "", false),
        NO_FRUIT("No Fruit", 0, 0, "", false),
        BOMB("Bomb", 0, 10, "a76a2811d1e176a07b6d0a657b910f134896ce30850f6e80c7c83732d85381ea", false),
        RUM("Rum", 0, 5, "407b275d28b927b1bf7f6dd9f45fbdad2af8571c54c8f027d1bff6956fbf3c16", false),
        MANGO("Mango", 300, 10, "f363a62126a35537f8189343a22660de75e810c6ac004a7d3da65f1c040a839", true),
        APPLE("Apple", 100, 8, "17ea278d6225c447c5943d652798d0bbbd1418434ce8c54c54fdac79994ddd6c", true),
        WATERMELON("Watermelon", 100, 4, "efe4ef83baf105e8dee6cf03dfe7407f1911b3b9952c891ae34139560f2931d6", true),
        POMEGRANATE("Pomegranate", 200, 4, "40824d18079042d5769f264f44394b95b9b99ce689688cc10c9eec3f882ccc08", true),
        COCONUT("Coconut", 200, 3, "10ceb1455b471d016a9f06d25f6e468df9fcf223e2c1e4795b16e84fcca264ee", true),
        CHERRY("Cherry", 200, 2, "c92b099a62cd2fbf8ada09dec145c75d7fda4dc57b968bea3a8fa11e37aa48b2", true),
        DURIAN("Durian", 800, 2, "ac268d36c2c6047ffeec00124096376b56dbb4d756a55329363a1b27fcd659cd", true),
        DRAGON_FRUIT("Dragonfruit", 1200, 1, "3cc761bcb0579763d9b8ab6b7b96fa77eb6d9605a804d838fec39e7b25f95591", true);

        final String label;
        final int points;
        final int count;
        final String texture;
        final boolean edible;

        Fruit(String label, int points, int count, String texture, boolean edible) {
            this.label = label;
            this.points = points;
            this.count = count;
            this.texture = texture;
            this.edible = edible;
        }

        private CarnivalData.FruitInfo info() {
            return CarnivalData.get().fruits().get(this);
        }

        /** Name, points, how many are hidden and head texture: from Carnival.json, or the built-in value. */
        String label() {
            CarnivalData.FruitInfo i = info();
            return i == null ? label : i.label();
        }

        int points() {
            CarnivalData.FruitInfo i = info();
            return i == null ? points : i.points();
        }

        int count() {
            CarnivalData.FruitInfo i = info();
            return i == null ? count : i.count();
        }

        String texture() {
            CarnivalData.FruitInfo i = info();
            return i == null ? texture : i.texture();
        }

        /** Part of the 49 things hidden under the sand. */
        boolean inBag() {
            return edible || this == BOMB || this == RUM;
        }

        static Fruit byName(String name) {
            for (Fruit f : values()) if (f.label().equalsIgnoreCase(name)) return f;
            return null;
        }

        static Fruit byTexture(String hash) {
            if (hash == null || hash.isEmpty()) return null;
            for (Fruit f : values()) if (!f.texture().isEmpty() && f.texture().equals(hash)) return f;
            return null;
        }
    }

    private static final class Cell {
        Integer mines;
        Fruit treasure = Fruit.UNKNOWN;
        Fruit solved = Fruit.UNKNOWN;
        Fruit anchored = Fruit.UNKNOWN;
        Fruit uncovered = Fruit.UNKNOWN;
        boolean diggable = true;
        boolean counted;

        Fruit found() {
            return uncovered != Fruit.UNKNOWN ? uncovered : solved;
        }
    }

    private static boolean playing;
    private static Cell[][] grid = newGrid();
    private static int lastRow = -1, lastCol = -1;
    private static int digsUsed;
    private static final Map<Fruit, Integer> REMAINING = new EnumMap<>(Fruit.class);
    private static volatile FruitDiggingSolver.Recommendation recommendation;
    private static boolean dirty;
    private static final Set<Integer> SEEN_ITEMS = new HashSet<>();
    private static ClientLevel lastLevel;
    private static int ticks;
    private static final Random RANDOM = new Random();

    static {
        resetRemaining();
    }

    private CarnivalFruitDigging() {}

    private static EventsConfig.FruitDigging config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.carnival.fruitDigging;
    }

    private static boolean active() {
        EventsConfig.FruitDigging c = config();
        return c != null && c.enabled && playing;
    }

    static void init() {
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        WorldEvents.BLOCK_STATE_UPDATE.register(CarnivalFruitDigging::onBlockUpdate);
        ClientTickEvents.END_CLIENT_TICK.register(CarnivalFruitDigging::tick);
        Sky2MWorldRender.register(CarnivalFruitDigging::render);
        Sky2MHuds.register(REMAINING_HUD, "Fruit Digging Remaining", () -> {
            EventsConfig.FruitDigging c = config();
            return c != null && c.remainingFruit && active();
        }, CarnivalFruitDigging::remainingLines, List.of(
            Component.literal("Fruit Digging").withStyle(ChatFormatting.GOLD),
            Component.literal("Dragonfruit (1200): 1").withStyle(ChatFormatting.GRAY),
            Component.literal("Mango (300): 7").withStyle(ChatFormatting.GRAY)), 10, 220);
        Sky2MHuds.setSettingsSearch(REMAINING_HUD, "Remaining Fruit");
        Sky2MHuds.setting(REMAINING_HUD, () -> config() != null && config().enabled && config().remainingFruit);
        Sky2MHuds.register(SOLVER_HUD, "Fruit Digging Solver", () -> {
            EventsConfig.FruitDigging c = config();
            return c != null && c.bestDig && active() && recommendation != null;
        }, CarnivalFruitDigging::solverLines, List.of(
            Component.literal("Fruit Digging Solver").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
            Component.literal("Digs: 3/15").withStyle(ChatFormatting.GRAY),
            Component.literal("Dig: C4 with Anchor").withStyle(ChatFormatting.GREEN),
            Component.literal("Bomb chance: 4%").withStyle(ChatFormatting.RED)), 10, 300);
        Sky2MHuds.setSettingsSearch(SOLVER_HUD, "Best Dig");
        Sky2MHuds.setting(SOLVER_HUD, () -> config() != null && config().enabled && config().bestDig);
    }

    // ------------------------------------------------------------------------------------------------ state

    /** How many digs a game has (Carnival.json). */
    static int maxDigs() {
        return CarnivalData.get().maxDigs();
    }

    /** The board with a margin, for finding its armour stands and dropped fruit. */
    private static AABB board() {
        CarnivalData.Snapshot d = CarnivalData.get();
        return new AABB(d.boardX() - 1, d.boardY() - 2, d.boardZ() - 1, d.boardX() + SIZE + 1, d.boardY() + 4, d.boardZ() + SIZE + 1);
    }

    private static Cell[][] newGrid() {
        Cell[][] g = new Cell[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++) g[r][c] = new Cell();
        return g;
    }

    private static void resetRemaining() {
        REMAINING.clear();
        for (Fruit f : Fruit.values()) if (f.count() > 0) REMAINING.put(f, f.count());
    }

    /** Clears the board; {@code start} begins tracking a new game. */
    public static void reset(boolean start) {
        playing = start;
        grid = newGrid();
        resetRemaining();
        lastRow = lastCol = -1;
        digsUsed = 0;
        recommendation = null;
        SEEN_ITEMS.clear();
        dirty = start;
    }

    private static Cell cellAt(double x, double z) {
        CarnivalData.Snapshot d = CarnivalData.get();
        int col = (int) Math.floor(x) - d.boardX();
        int row = (int) Math.floor(z) - d.boardZ();
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return null;
        return grid[row][col];
    }

    private static Cell lastDug() {
        return lastRow < 0 ? null : grid[lastRow][lastCol];
    }

    private static Fruit lastDugFruit() {
        Cell cell = lastDug();
        return cell == null ? Fruit.NO_FRUIT : cell.uncovered;
    }

    private static void count(Cell cell, Fruit fruit) {
        if (fruit == Fruit.UNKNOWN || fruit == Fruit.NO_FRUIT || cell.counted || cell.diggable) return;
        Integer left = REMAINING.get(fruit);
        if (left == null) return;
        REMAINING.put(fruit, left - 1);
        cell.counted = true;
    }

    /** Points this fruit is worth on the next dig: Apples and Cherries grow, Pomegranate and Durian change the next dig. */
    private static int pointValue(Fruit fruit) {
        Fruit last = lastDugFruit();
        double multiplier = last == Fruit.POMEGRANATE ? 1.5 : last == Fruit.DURIAN ? 0.5 : 1.0;
        int dug = fruit.count() - REMAINING.getOrDefault(fruit, 0);
        int base = fruit == Fruit.APPLE ? fruit.points() + 100 * dug : fruit == Fruit.CHERRY ? fruit.points() + 300 * dug : fruit.points();
        return (int) (multiplier * base);
    }

    // ------------------------------------------------------------------------------------------------ events

    static void onChat(String text) {
        EventsConfig.FruitDigging c = config();
        if (c == null || !c.enabled) return;
        CarnivalData.Snapshot data = CarnivalData.get();
        if (text.equals(data.fruitStart())) {
            reset(true);
            return;
        }
        if (!playing) return;
        if (text.equals(data.fruitEnd())) {
            reset(false);
            return;
        }
        Cell last = lastDug();
        Matcher m = data.fruitMines().matcher(text);
        if (m.matches()) {
            if (last != null) {
                last.mines = Integer.parseInt(m.group(1));
                dirty = true;
            }
            return;
        }
        m = data.fruitTreasure().matcher(text);
        if (m.matches()) {
            Fruit fruit = Fruit.byName(m.group(1));
            if (last != null && fruit != null) {
                last.treasure = fruit;
                dirty = true;
            }
            return;
        }
        if (data.fruitNoFruit().matcher(text).matches() && last != null) {
            last.treasure = Fruit.NO_FRUIT;
            last.anchored = Fruit.NO_FRUIT;
            dirty = true;
        }
    }

    /** A server block change: sand turning to sandstone is a dig, to sandstone stairs a fruit a bomb destroyed. */
    public static void onBlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        CarnivalData.Snapshot d = CarnivalData.get();
        if (!playing || old == null || !old.is(Blocks.SAND) || pos.getY() != d.boardY()) return;
        int col = pos.getX() - d.boardX(), row = pos.getZ() - d.boardZ();
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return;
        Cell cell = grid[row][col];
        cell.diggable = false;
        if (updated.is(Blocks.SANDSTONE)) {
            lastRow = row;
            lastCol = col;
            digsUsed++;
        } else if (updated.is(Blocks.SANDSTONE_STAIRS)) {
            count(cell, cell.solved);
        }
        dirty = true;
    }

    private static void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            if (playing) reset(false);
        }
        if (!active() || mc.level == null || mc.player == null) return;
        if (++ticks % 2 == 0) scanEntities(mc.level);
        if (dirty) {
            dirty = false;
            updateRecommendation();
        }
    }

    /** Fruit names above dug cells (armour stands) and fruit dropped by the Anchor shovel (heads on the ground). */
    private static void scanEntities(ClientLevel level) {
        AABB board = board();
        Pattern reveal = CarnivalData.get().fruitReveal();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, board)) {
            if (!stand.hasCustomName() || stand.getCustomName() == null) continue;
            String name = Sky2MLocation.strip(stand.getCustomName().getString()).trim();
            Matcher m = reveal.matcher(name);
            if (!m.matches()) continue;
            Fruit fruit = Fruit.byName(m.group(1).trim());
            Cell cell = cellAt(stand.getX(), stand.getZ());
            if (fruit == null || cell == null) continue;
            if (cell.uncovered != fruit) {
                cell.uncovered = fruit;
                dirty = true;
            }
            if (!cell.counted && !cell.diggable) {
                count(cell, fruit);
                dirty = true;
            }
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, board)) {
            if (SEEN_ITEMS.contains(item.getId())) continue;
            Fruit fruit = Fruit.byTexture(textureHash(Compat.getHeadTexture(item.getItem())));
            if (fruit == null) continue;
            SEEN_ITEMS.add(item.getId());
            anchorReveal(cellAt(item.getX(), item.getZ()), fruit);
        }
    }

    /** The Anchor shovel shows a nearby fruit at its cell: that cell is known, and the dug cell's hint is that fruit. */
    static void anchorReveal(Cell solvedCell, Fruit fruit) {
        Cell dug = lastDug();
        if (solvedCell == null || dug == null) return;
        if (solvedCell.solved == Fruit.UNKNOWN) {
            solvedCell.solved = fruit;
            count(solvedCell, fruit);
            dirty = true;
        }
        if (solvedCell.diggable && dug.anchored == Fruit.UNKNOWN) {
            dug.anchored = fruit;
            dirty = true;
        }
    }

    private static String textureHash(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            Matcher m = TEXTURE_URL.matcher(new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void updateRecommendation() {
        FruitDiggingSolver.CellInput[][] input = new FruitDiggingSolver.CellInput[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                Cell cell = grid[r][c];
                Fruit content = cell.uncovered != Fruit.UNKNOWN ? cell.uncovered : cell.solved != Fruit.UNKNOWN ? cell.solved : null;
                input[r][c] = new FruitDiggingSolver.CellInput(content, cell.diggable, !cell.diggable && content == null, cell.mines,
                    cell.treasure == Fruit.UNKNOWN ? null : cell.treasure, cell.anchored == Fruit.UNKNOWN ? null : cell.anchored);
            }
        }
        Fruit last = lastDugFruit();
        double multiplier = last == Fruit.POMEGRANATE ? 1.5 : last == Fruit.DURIAN ? 0.5 : 1.0;
        recommendation = digsUsed >= maxDigs() ? null : FruitDiggingSolver.recommend(input, digsUsed, multiplier, last == Fruit.COCONUT, RANDOM);
    }

    // ------------------------------------------------------------------------------------------------ display

    private static String label(int row, int col) {
        return (char) ('A' + row) + String.valueOf(col + 1);
    }

    public static List<Component> remainingLines() {
        List<Fruit> left = new ArrayList<>();
        for (Map.Entry<Fruit, Integer> e : REMAINING.entrySet()) if (e.getValue() > 0) left.add(e.getKey());
        left.sort(Comparator.comparingInt(CarnivalFruitDigging::pointValue).reversed().thenComparing(Fruit::label));
        List<Component> lines = new ArrayList<>();
        if (left.isEmpty()) return lines;
        lines.add(Component.literal("Fruit Digging").withStyle(ChatFormatting.GOLD));
        for (Fruit f : left) {
            ChatFormatting colour = f == Fruit.BOMB ? ChatFormatting.RED : f == Fruit.RUM ? ChatFormatting.DARK_PURPLE : ChatFormatting.GRAY;
            String points = f.edible ? " (" + pointValue(f) + ")" : "";
            lines.add(Component.literal(f.label() + points + ": " + REMAINING.get(f)).withStyle(colour));
        }
        return lines;
    }

    public static List<Component> solverLines() {
        FruitDiggingSolver.Recommendation rec = recommendation;
        List<Component> lines = new ArrayList<>();
        if (rec == null) return lines;
        lines.add(Component.literal("Fruit Digging Solver").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        lines.add(Component.literal("Digs: " + digsUsed + "/" + maxDigs()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Dig: " + label(rec.row(), rec.col()) + " with " + rec.shovel()).withStyle(ChatFormatting.GREEN));
        lines.add(Component.literal(String.format(Locale.US, "Bomb chance: %.0f%%", rec.pBomb() * 100)).withStyle(ChatFormatting.RED));
        return lines;
    }

    /** The recommended cell as "C4", or empty. For the game test. */
    public static String recommendedCell() {
        FruitDiggingSolver.Recommendation rec = recommendation;
        return rec == null ? "" : label(rec.row(), rec.col());
    }

    public static boolean playing() {
        return playing;
    }

    private static void render(PrimitiveCollector collector) {
        EventsConfig.FruitDigging c = config();
        if (c == null || !active()) return;
        CarnivalData.Snapshot d = CarnivalData.get();
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                Cell cell = grid[row][col];
                BlockPos pos = new BlockPos(d.boardX() + col, d.boardY(), d.boardZ() + row);
                if (!cell.diggable) collector.submitFilledBox(new AABB(pos).expandTowards(0, 0.1, 0), new float[]{0.5f, 0.5f, 0.5f}, 0.45f, false);
                List<Component> label = new ArrayList<>();
                if (c.foundFruit && !cell.diggable && cell.found() != Fruit.UNKNOWN) {
                    label.add(Component.literal(cell.found().label()).withStyle(ChatFormatting.GREEN));
                }
                if (c.fruitGuesses && cell.diggable && cell.uncovered == Fruit.UNKNOWN && cell.solved != Fruit.UNKNOWN) {
                    label.add(Component.literal(cell.solved.label() + " (" + pointValue(cell.solved) + ")").withStyle(ChatFormatting.YELLOW));
                }
                if (c.hints) {
                    if (cell.treasure != Fruit.UNKNOWN) label.add(Component.literal("≤ " + cell.treasure.label()).withStyle(ChatFormatting.AQUA));
                    if (cell.anchored != Fruit.UNKNOWN && cell.anchored != cell.treasure) label.add(Component.literal("≥ " + cell.anchored.label()).withStyle(ChatFormatting.AQUA));
                    if (cell.mines != null) label.add(Component.literal(cell.mines + (cell.mines == 1 ? " bomb" : " bombs")).withStyle(ChatFormatting.RED));
                }
                Vec3 at = new Vec3(pos.getX() + 0.5, d.boardY() + 1.15, pos.getZ() + 0.5);
                for (int i = 0; i < label.size(); i++) collector.submitText(label.get(i), at, 0.6f, (label.size() - 1 - i) * 10f, true);
            }
        }
        FruitDiggingSolver.Recommendation rec = recommendation;
        if (c.bestDig && rec != null && grid[rec.row()][rec.col()].diggable) {
            BlockPos pos = new BlockPos(d.boardX() + rec.col(), d.boardY(), d.boardZ() + rec.row());
            collector.submitFilledBox(new AABB(pos).inflate(0.01), new float[]{0.2f, 1f, 0.2f}, 0.35f, true);
            collector.submitText(Component.literal(String.format(Locale.US, "Dig here (%s) %.0f%% bomb", rec.shovel(), rec.pBomb() * 100))
                .withStyle(ChatFormatting.GREEN), new Vec3(pos.getX() + 0.5, d.boardY() + 1.6, pos.getZ() + 0.5), 1f, true);
        }
    }
}
