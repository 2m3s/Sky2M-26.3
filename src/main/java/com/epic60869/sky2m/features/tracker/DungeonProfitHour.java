package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 hour &lt;run time&gt; &lt;floor&gt;: dungeon profit per hour at that speed, with and without the RNG meter. Only the run time
 * counts: the time between runs (chests, requeue, loading) is left out.
 * <ul>
 * <li>Chest profit per run is your own: Sky2M's Chest History profit on that floor divided by your completed runs there
 *     (Run History), so it already includes the rare drops you got and what the chests cost.</li>
 * <li>The RNG meter fills by your run score: its item is worth price x score / required score per run. Your average
 *     score on the floor comes from Run History (300 when there's none). Meter items and their required score are read
 *     from the dungeon RNG Meter menu whenever it's open, or set with /s2 hour meter &lt;floor&gt; set &lt;score&gt; &lt;item&gt;.</li>
 * </ul>
 */
public final class DungeonProfitHour {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path DIR = FabricLoader.getInstance().getConfigDir();
    /** "Turn on ... first", for every command of these features. */
    static final String TURN_ON = " in /s2 (Misc > Profit Trackers > Drop Tracker, Profit per Hour & RNG List) first.";
    /** The RNG meter items read from Hypixel's menus. */
    private static Path file = DIR.resolve("sky2m").resolve("dungeon-meters.json");
    private static final Pattern FLOOR = Pattern.compile("(?i)^(?:([fm])([1-7])|e|entrance|f0)$");
    private static final Pattern TITLE_FLOOR = Pattern.compile("(?i)\\(([FM])([1-7])\\)|\\b(Master(?: Mode)?\\s+)?(?:The\\s+)?(?:Catacombs\\s+)?Floor\\s+([IVX]+|[1-7])\\b|\\b([FM])([1-7])\\b");
    private static final Pattern PROGRESS = Pattern.compile("([\\d,.]+[kKmM]?)\\s*/\\s*([\\d,.]+[kKmM]?)\\s*$");
    private static final Pattern SELECTED_DROP = Pattern.compile("(?i)^Selected(?: Drop)?:\\s*(.+)$");
    /** Gaps longer than this between two runs were a break, not the time between runs. */
    private static final long MAX_GAP_MS = 5 * 60_000;

    /** A dungeon RNG meter item: its price id and the score the meter needs for it. */
    public static final class MeterItem {
        public String name;
        public @Nullable String id;
        public double required;
    }

    /** One floor's meter: what's selected, its progress, and the items Hypixel lists. */
    public static final class FloorMeter {
        public @Nullable String selected;
        public double progress;
        public Map<String, MeterItem> items = new LinkedHashMap<>();
    }

    private static Map<String, FloorMeter> meters;
    private static boolean openScreen;
    private static int ticks;

    private DungeonProfitHour() {}

    static boolean on() {
        var c = TrackLines.config();
        return c != null && c.profitHour;
    }

    public static void init() {
        file = TrackLines.movedFrom(file, DIR.resolve("sky2m-personal-dungeon-meters.json"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root).then(command()));
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Opened a tick later, after the chat screen the command was typed in has closed.
            if (openScreen) {
                openScreen = false;
                mc.gui.setScreen(new DungeonProfitScreen(null));
            }
            if (++ticks % 10 != 0 || !on()) return;
            if (ticks % 40 == 0) readMeterTab();
            if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
            String title = TrackLines.strip(screen.getTitle().getString());
            if (title.contains("RNG Meter")) readMeterMenu(screen, title);
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return ClientCommands.literal("hour")
                .executes(ctx -> {
                    if (!on()) {
                        ctx.getSource().sendFeedback(Component.literal("§cTurn on Dungeon Profit per Hour" + TURN_ON));
                        return 0;
                    }
                    openScreen = true;
                    return 1;
                })
                .then(ClientCommands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(ClientCommands.literal("meter")
                    .then(ClientCommands.argument("floor", StringArgumentType.word())
                        .executes(ctx -> listMeter(ctx.getSource(), StringArgumentType.getString(ctx, "floor")))
                        .then(ClientCommands.literal("set")
                            .then(ClientCommands.argument("score", StringArgumentType.word())
                                .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                    .executes(ctx -> setMeter(ctx.getSource(), StringArgumentType.getString(ctx, "floor"),
                                        StringArgumentType.getString(ctx, "score"), StringArgumentType.getString(ctx, "item"))))))
                        .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> {
                                String floor = floor(StringArgumentType.getString(ctx, "floor"));
                                FloorMeter meter = floor == null ? null : meters().get(floor);
                                builder.suggest("none");
                                if (meter != null) for (String name : meter.items.keySet()) builder.suggest(name);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> pickMeter(ctx.getSource(), StringArgumentType.getString(ctx, "floor"), StringArgumentType.getString(ctx, "item"))))))
                .then(ClientCommands.literal("collec")
                    .executes(ctx -> help(ctx.getSource()))
                    .then(ClientCommands.argument("floor", StringArgumentType.word())
                        .executes(ctx -> collection(ctx, null, null))
                        .then(ClientCommands.argument("target", StringArgumentType.word())
                            .executes(ctx -> collection(ctx, null, null))
                            .then(ClientCommands.argument("runtime", StringArgumentType.word())
                                .executes(ctx -> collection(ctx, StringArgumentType.getString(ctx, "runtime"), null))
                                .then(ClientCommands.argument("current", StringArgumentType.word())
                                    .executes(ctx -> collection(ctx, StringArgumentType.getString(ctx, "runtime"), StringArgumentType.getString(ctx, "current"))))))))
                .then(ClientCommands.argument("time", StringArgumentType.word())
                    .then(ClientCommands.argument("floor", StringArgumentType.word())
                        .executes(ctx -> estimate(ctx))));
    }

    /** The tab list's lines in Hypixel's order (its 80 slots), without colour codes. */
    private static List<String> orderedTab() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            String s;
            try {
                s = com.epic60869.sky2m.sb.skyblock.tabhud.util.PlayerListManager.strAt(i);
            } catch (RuntimeException e) {
                break;
            }
            out.add(s == null ? "" : TrackLines.strip(s).trim());
        }
        return out;
    }

    /** Hypixel's RNG Meter tab widget: "RNG Meter", "Catacombs Floor I", "Recombobulator 3000", "644/5,400". */
    private static void readMeterTab() {
        var meter = meterFromTab(orderedTab());
        if (meter == null) return;
        String floor = (String) meter[0], name = (String) meter[1];
        double[] progress = (double[]) meter[2];
        if (remember(floor, name, RepoItems.idByName(name), progress, true)) save();
    }

    /** {floor, item, {progress, required}} from the RNG Meter tab widget, or null when the tab doesn't show a dungeon meter. */
    public static Object @Nullable [] meterFromTab(List<String> tab) {
        for (int i = 0; i + 3 < tab.size(); i++) {
            if (!tab.get(i).startsWith("RNG Meter")) continue;
            String floor = tab.get(i + 1).contains("Catacombs") ? floorIn(tab.get(i + 1)) : null;
            double[] progress = progress(List.of(tab.get(i + 3)));
            String item = tab.get(i + 2).trim();
            if (floor == null || progress == null || item.isEmpty()) return null;
            return new Object[]{floor, item, progress};
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the estimate

    /** Profit per run and per hour; meter values are 0 when no meter item is picked. */
    public record Estimate(double runsPerHour, double chestPerRun, double meterPerRun, double scorePerRun, int runsToFill) {
        public double withoutMeter() {
            return chestPerRun * runsPerHour;
        }

        public double withMeter() {
            return (chestPerRun + meterPerRun) * runsPerHour;
        }
    }

    /** The meter item counts as price x score / required per run: the run score is what fills a dungeon RNG meter. */
    public static Estimate estimate(double runSeconds, double betweenSeconds, double chestPerRun, double meterPrice, double required,
                                    double progress, double scorePerRun) {
        double runsPerHour = 3600 / Math.max(1, runSeconds + betweenSeconds);
        double meterPerRun = required > 0 && meterPrice > 0 ? meterPrice * Math.min(1, scorePerRun / required) : 0;
        int runsToFill = required > 0 && scorePerRun > 0 ? (int) Math.ceil(Math.max(0, required - progress) / scorePerRun) : 0;
        return new Estimate(runsPerHour, chestPerRun, meterPerRun, scorePerRun, runsToFill);
    }

    private static int estimate(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        if (!on()) {
            source.sendFeedback(Component.literal("§cTurn on Dungeon Profit per Hour" + TURN_ON));
            return 0;
        }
        double run = seconds(StringArgumentType.getString(ctx, "time"));
        String floor = floor(StringArgumentType.getString(ctx, "floor"));
        if (run <= 0 || floor == null) return help(source);
        History history = history(floor);

        FloorMeter meter = meterFor(floor);
        MeterItem item = meter == null || meter.selected == null ? null : meter.items.get(meter.selected);
        double price = item == null || item.id == null ? 0 : Sky2MPriceTooltip.unitPrice(item.id);
        double score = history.score > 0 ? history.score : 300;
        Estimate e = estimate(run, 0, history.chestPerRun, price, item == null ? 0 : item.required, meter == null ? 0 : meter.progress, score);

        source.sendFeedback(Component.literal("§6§lDungeon Profit §7- §e" + floor + " §7at §f" + time(run)));
        source.sendFeedback(line("Runs per hour: ", String.format(Locale.US, "%.1f", e.runsPerHour()),
            time(run) + " a run"));
        if (history.runs == 0) {
            source.sendFeedback(Component.literal("§7Chests: §cno chests saved for " + floor + " yet. §7Open some with Sky2M's Chest History on."));
        } else {
            source.sendFeedback(line("Chest profit per run: ", coins(e.chestPerRun()),
                "Your Chest History on " + floor + ": " + coins(history.chestTotal) + " profit over " + history.runs + " runs, after chest and key costs."));
        }
        if (item == null) {
            source.sendFeedback(Component.literal("§7RNG meter: §8none picked. ")
                .append(button("[Pick]", "/s2 hour meter " + floor.toLowerCase(Locale.ROOT), "Choose this floor's meter item")));
        } else {
            String detail = item.name + ": " + (price > 0 ? coins(price) : "no price") + ", needs " + whole(item.required) + " score. "
                + "You score " + whole(score) + " a run" + (history.score > 0 ? " (your average)" : " (assumed S+)") + ", "
                + e.runsToFill() + " more runs to fill it.";
            source.sendFeedback(line("RNG meter per run: ", price > 0 ? coins(e.meterPerRun()) : "§cno price", detail)
                .append(Component.literal(" §7(" + item.name + ") ")).append(button("[Change]", "/s2 hour meter " + floor.toLowerCase(Locale.ROOT), "Choose another meter item")));
        }
        source.sendFeedback(line("Without meter: ", coins(e.withoutMeter()) + "/h", null));
        if (item != null && price > 0) source.sendFeedback(line("With meter: ", "§a" + coins(e.withMeter()) + "/h", null));
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ boss collection

    /** Each floor's boss, whose collection its runs fill (normal and Master Mode share one). */
    static final String[] BOSSES = {"", "Bonzo", "Scarf", "The Professor", "Thorn", "Livid", "Sadan", "Necron"};

    /** Boss collection gained per run: 1 for a normal floor, 2 for Master Mode. */
    public static int collectionPerRun(String floor) {
        return floor.startsWith("M") ? 2 : 1;
    }

    /**
     * The boss's collection from Hypixel's Collection tab widget ("Bonzo VI: 318/1k": tier, amount, next tier), or -1.
     * Also reads "Bonzo: 312" and "Bonzo Collection: 312/1,000".
     */
    public static double collectionFromTab(List<String> lines, String boss) {
        Matcher m = collectionLine(lines, boss);
        return m == null ? -1 : number(m.group(1));
    }

    /** The next tier's amount from the same line ("1k" in "Bonzo VI: 318/1k"), or -1. */
    public static double nextTierFromTab(List<String> lines, String boss) {
        Matcher m = collectionLine(lines, boss);
        return m == null || m.group(2) == null ? -1 : number(m.group(2));
    }

    private static @Nullable Matcher collectionLine(List<String> lines, String boss) {
        String short_ = boss.startsWith("The ") ? boss.substring(4) : boss;
        Pattern p = Pattern.compile("(?i)\\b(?:the\\s+)?" + Pattern.quote(short_) + "\\b(?:\\s+[IVXL]+)?(?:\\s+collection)?\\s*[:\\-]?\\s*([\\d,.]+[kKmM]?)(?:\\s*/\\s*([\\d,.]+[kKmM]?))?");
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find() && number(m.group(1)) >= 0) return m;
        }
        return null;
    }

    /** Your average time for a completed run on the floor (newest 20, from Run History) in seconds, or -1. */
    public static double averageRunSeconds(String floor, JsonArray runs) {
        return recentRunSeconds(floor, runs, 20);
    }

    /** Your average time over your newest {@code count} completed runs on the floor, in seconds, or -1. */
    public static double recentRunSeconds(String floor, JsonArray runs, int count) {
        List<JsonObject> done = new ArrayList<>();
        for (JsonElement e : runs) {
            JsonObject run = e.getAsJsonObject();
            if (floor.equalsIgnoreCase(string(run, "floor")) && run.has("completed") && run.get("completed").getAsBoolean()) done.add(run);
        }
        done.sort((a, b) -> Long.compare((long) number(b, "endedAt"), (long) number(a, "endedAt")));
        double total = 0;
        int n = 0;
        for (JsonObject run : done) {
            if (n >= count) break;
            double ms = number(run, "totalMs");
            if (ms <= 0) ms = number(run, "endedAt") - number(run, "startedAt");
            if (ms > 0) {
                total += ms;
                n++;
            }
        }
        return n == 0 ? -1 : total / n / 1000;
    }

    private static int collection(CommandContext<FabricClientCommandSource> ctx, @Nullable String runText, @Nullable String currentText) {
        FabricClientCommandSource source = ctx.getSource();
        if (!on()) {
            source.sendFeedback(Component.literal("§cTurn on Dungeon Profit per Hour" + TURN_ON));
            return 0;
        }
        String floor = floor(StringArgumentType.getString(ctx, "floor"));
        if (floor == null || floor.equals("E")) {
            source.sendFeedback(Component.literal("§6/s2 hour collec <floor> [target] [run time] [current] §7- e.g. §f/s2 hour collec m1 1k"));
            return 0;
        }
        String boss = BOSSES[floor.charAt(1) - '0'];
        List<String> tab = TrackLines.tabLines();
        String targetText = targetArg(ctx);
        double target = targetText != null ? number(targetText) : nextTierFromTab(tab, boss);
        if (target <= 0) {
            source.sendFeedback(Component.literal("§cNo target: §7give one (§f/s2 hour collec " + floor.toLowerCase(Locale.ROOT) + " 1k§7) or show your " + boss + " collection in the tab list."));
            return 0;
        }
        double current;
        String from;
        if (currentText != null) {
            current = number(currentText);
            from = "you gave";
        } else {
            current = collectionFromTab(tab, boss);
            from = "tab list";
        }
        if (current < 0) {
            source.sendFeedback(Component.literal("§cCouldn't find your " + boss + " collection in the tab list. §7Add Hypixel's collection widget to your tab, or give it: §f/s2 hour collec "
                + floor.toLowerCase(Locale.ROOT) + " " + whole(target) + " <run time> <current>"));
            return 0;
        }

        JsonArray runs = read(DIR.resolve("sky2m").resolve("run-history.json"));
        double run;
        String runNote;
        if (runText != null) {
            run = seconds(runText);
            runNote = "you gave";
        } else {
            run = averageRunSeconds(floor, runs);
            runNote = "your average";
        }
        if (run <= 0) {
            source.sendFeedback(Component.literal("§cNo " + floor + " runs in your Run History to average. §7Give a run time: §f/s2 hour collec "
                + floor.toLowerCase(Locale.ROOT) + " " + whole(target) + " 1:20"));
            return 0;
        }

        source.sendFeedback(Component.literal("§6§l" + boss + " Collection §7- §e" + floor));
        if (current >= target) {
            source.sendFeedback(Component.literal("§aYou already have " + whole(current) + ", past " + whole(target) + "."));
            return 1;
        }
        int perRun = collectionPerRun(floor);
        int runsNeeded = (int) Math.ceil((target - current) / perRun);
        double cycle = run;
        source.sendFeedback(line("Now: ", whole(current) + " §7/ §6" + whole(target), "From the " + from + ". " + whole(target - current) + " to go."));
        source.sendFeedback(line("Runs needed: ", whole(runsNeeded), floor + " gives " + perRun + " collection a run"
            + (perRun == 2 ? " (Master Mode counts double)." : ".")));
        source.sendFeedback(line("Run time: ", time(cycle), "From " + runNote + "."));
        source.sendFeedback(line("Time to reach it: ", hours(runsNeeded * cycle), String.format(Locale.US, "%.1f runs an hour", 3600 / cycle)));
        return 1;
    }

    private static @Nullable String targetArg(CommandContext<FabricClientCommandSource> ctx) {
        try {
            return StringArgumentType.getString(ctx, "target");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "13h 6m", "45m", "2d 4h". */
    public static String hours(double seconds) {
        long minutes = Math.round(seconds / 60);
        long d = minutes / 1440, h = minutes % 1440 / 60, m = minutes % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    private static int help(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("§6/s2 hour §7- the Dungeon Profit screen"));
        source.sendFeedback(Component.literal("§6/s2 hour <run time> <floor> §7- e.g. §f/s2 hour 1:20 m1"));
        source.sendFeedback(Component.literal("§6/s2 hour collec <floor> <target> [run time] [current] §7- how long to reach a boss collection, e.g. §f/s2 hour collec m1 1k"));
        source.sendFeedback(Component.literal("§6/s2 hour meter <floor> §7- pick the RNG meter item; open the floor's RNG Meter menu once so it's listed"));
        source.sendFeedback(Component.literal("§6/s2 hour meter <floor> set <score> <item> §7- set a meter item and its required score yourself"));
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ your history

    /** Chest profit per run, your average score and your usual gap between runs, on one floor. */
    public record History(int runs, double chestTotal, double chestPerRun, double score, double gapSeconds) {}

    public static History history(String floor) {
        return history(floor, read(chestFile()), runHistory());
    }

    /** Sky2M's Run History (config/sky2m/run-history.json), newest first. */
    public static JsonArray runHistory() {
        return read(DIR.resolve("sky2m").resolve("run-history.json"));
    }

    /** From Sky2M's chest-history and run-history JSON. Runs before your first chest on the floor don't count. */
    public static History history(String floor, JsonArray chests, JsonArray runs) {
        double total = 0;
        int chestCount = 0;
        long first = Long.MAX_VALUE;
        for (JsonElement e : chests) {
            JsonObject chest = e.getAsJsonObject();
            if (!floor.equalsIgnoreCase(string(chest, "floor"))) continue;
            total += number(chest, "profit");
            chestCount++;
            first = Math.min(first, (long) number(chest, "time"));
        }
        List<JsonObject> done = new ArrayList<>();
        List<JsonObject> all = new ArrayList<>();
        for (JsonElement e : runs) {
            JsonObject run = e.getAsJsonObject();
            all.add(run);
            if (floor.equalsIgnoreCase(string(run, "floor")) && run.has("completed") && run.get("completed").getAsBoolean()) done.add(run);
        }
        // Croesus chests can be claimed a while after their run, so allow a day before the first chest.
        long since = first == Long.MAX_VALUE ? Long.MAX_VALUE : first - 24 * 3600_000L;
        int counted = 0;
        for (JsonObject run : done) if ((long) number(run, "endedAt") >= since) counted++;
        if (counted == 0) counted = chestCount;

        double score = 0;
        int scored = 0;
        done.sort((a, b) -> Long.compare((long) number(b, "endedAt"), (long) number(a, "endedAt")));
        for (JsonObject run : done) {
            if (scored >= 20) break;
            double s = number(run, "hypixelScore");
            if (s <= 0) s = number(run, "score");
            if (s > 0) {
                score += s;
                scored++;
            }
        }
        return new History(counted, total, counted == 0 ? 0 : total / counted, scored == 0 ? 0 : score / scored, gapSeconds(all, floor));
    }

    /** The median gap from one run's end to the next one's start (this floor's runs first, else any), or -1. */
    public static double gapSeconds(List<JsonObject> runs, String floor) {
        List<JsonObject> sorted = new ArrayList<>(runs);
        sorted.sort((a, b) -> Long.compare((long) number(a, "startedAt"), (long) number(b, "startedAt")));
        List<Long> onFloor = new ArrayList<>(), any = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            JsonObject prev = sorted.get(i - 1), next = sorted.get(i);
            long gap = (long) number(next, "startedAt") - (long) number(prev, "endedAt");
            if (gap <= 0 || gap > MAX_GAP_MS) continue;
            any.add(gap);
            if (floor.equalsIgnoreCase(string(prev, "floor")) && floor.equalsIgnoreCase(string(next, "floor"))) onFloor.add(gap);
        }
        List<Long> gaps = onFloor.size() >= 3 ? onFloor : any;
        if (gaps.isEmpty()) return -1;
        gaps = gaps.subList(Math.max(0, gaps.size() - 30), gaps.size());
        List<Long> recent = new ArrayList<>(gaps);
        recent.sort(Long::compare);
        return recent.get(recent.size() / 2) / 1000.0;
    }

    static Path chestFile() {
        String profile = Sky2MStorageSearch.currentProfile();
        Path dir = DIR.resolve("sky2m");
        if (profile != null && !profile.isEmpty()) return dir.resolve("chest-history-" + profile + ".json");
        // No profile known yet: the most recently saved one.
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("chest-history"))
                .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                .orElse(dir.resolve("chest-history.json"));
        } catch (Exception e) {
            return dir.resolve("chest-history.json");
        }
    }

    static JsonArray read(Path file) {
        try {
            if (Files.exists(file)) return JsonParser.parseString(Files.readString(file)).getAsJsonArray();
        } catch (Exception e) {
            TrackLines.LOG.warn("Dungeon Profit per Hour couldn't read {}: {}", file, e.toString());
        }
        return new JsonArray();
    }

    // ------------------------------------------------------------------------------------------------ RNG meter

    private static int listMeter(FabricClientCommandSource source, String floorText) {
        String floor = floor(floorText);
        if (floor == null) return help(source);
        FloorMeter meter = meterFor(floor);
        if (meter == null || meter.items.isEmpty()) {
            source.sendFeedback(Component.literal("§7No meter items known for " + floor + " yet. Open its RNG Meter menu (Catacombs RNG Meter in /rng) once, or set one: §f/s2 hour meter "
                + floor.toLowerCase(Locale.ROOT) + " set <score> <item>"));
            return 1;
        }
        source.sendFeedback(Component.literal("§6" + floor + " RNG meter items §7(price per run at your score):"));
        double score = history(floor).score;
        if (score <= 0) score = 300;
        for (MeterItem item : meter.items.values()) {
            double price = item.id == null ? 0 : Sky2MPriceTooltip.unitPrice(item.id);
            MutableComponent row = Component.literal(item.name.equals(meter.selected) ? " §a▶ " : " §8- ")
                .append(Component.literal("§f" + item.name + " §7" + (price > 0 ? coins(price) : "§cno price") + " §8/ §7" + whole(item.required) + " score"));
            if (price > 0) row.append(Component.literal(" §8= §a" + coins(price * Math.min(1, score / item.required)) + "/run"));
            row.append(Component.literal(" ")).append(button("[Pick]", "/s2 hour meter " + floor.toLowerCase(Locale.ROOT) + " " + item.name, "Use " + item.name));
            source.sendFeedback(row);
        }
        source.sendFeedback(Component.literal(" ").append(button("[None]", "/s2 hour meter " + floor.toLowerCase(Locale.ROOT) + " none", "Don't count a meter item")));
        return 1;
    }

    private static int pickMeter(FabricClientCommandSource source, String floorText, String name) {
        String floor = floor(floorText);
        if (floor == null) return help(source);
        FloorMeter meter = meterFor(floor);
        if (meter == null) meter = meters().computeIfAbsent(floor, k -> new FloorMeter());
        if (name.equalsIgnoreCase("none")) {
            meter.selected = null;
            save();
            source.sendFeedback(Component.literal("§7No RNG meter item counted on " + floor + "."));
            return 1;
        }
        for (String known : meter.items.keySet()) {
            if (!known.equalsIgnoreCase(name.trim())) continue;
            if (!known.equals(meter.selected)) meter.progress = 0;
            meter.selected = known;
            save();
            source.sendFeedback(Component.literal("§aCounting " + known + " on " + floor + "'s RNG meter."));
            return 1;
        }
        source.sendFeedback(Component.literal("§c" + name + " isn't a known meter item for " + floor + ". §7See §f/s2 hour meter " + floor.toLowerCase(Locale.ROOT)));
        return 0;
    }

    private static int setMeter(FabricClientCommandSource source, String floorText, String scoreText, String name) {
        String floor = floor(floorText);
        double required = number(scoreText);
        if (floor == null || required <= 0) return help(source);
        FloorMeter meter = meters().computeIfAbsent(floor, k -> new FloorMeter());
        MeterItem item = new MeterItem();
        item.name = name.trim();
        item.id = RepoItems.idByName(item.name);
        item.required = required;
        meter.items.put(item.name, item);
        meter.selected = item.name;
        meter.progress = 0;
        save();
        source.sendFeedback(Component.literal("§aCounting " + item.name + " (" + whole(required) + " score) on " + floor + "'s RNG meter."
            + (item.id == null ? " §cNo item with that name, so it has no price." : "")));
        return 1;
    }

    /**
     * Reads a dungeon RNG Meter menu. A floor's own menu lists its drops with "progress/required" score; the overview
     * lists the floors with their selected drop. Slayer meters (XP, not score) are ignored.
     */
    private static void readMeterMenu(AbstractContainerScreen<?> screen, String title) {
        String menuFloor = floorIn(title);
        boolean changed = false;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            String name = TrackLines.strip(Compat.realName(stack).getString()).trim();
            List<String> lore = TrackLines.lore(stack);
            if (lore.stream().noneMatch(l -> l.toLowerCase(Locale.ROOT).contains("score"))) continue;
            double[] progress = progress(lore);
            String itemFloor = floorIn(name);
            if (itemFloor != null && menuFloor == null) {
                // The overview: "Floor VII" with "Selected Drop: Necron's Handle" and its progress.
                String selected = lore.stream().map(l -> SELECTED_DROP.matcher(l.trim())).filter(Matcher::matches).map(m -> m.group(1).trim()).findFirst().orElse(null);
                if (selected == null || progress == null) continue;
                changed |= remember(itemFloor, selected, null, progress, true);
            } else if (menuFloor != null && progress != null) {
                boolean selected = lore.stream().anyMatch(l -> l.trim().equalsIgnoreCase("SELECTED") || l.toLowerCase(Locale.ROOT).contains("click to deselect"));
                String id = Sky2MPriceTooltip.marketId(stack);
                changed |= remember(menuFloor, name, id == null || id.isEmpty() ? RepoItems.idByName(name) : id, progress, selected);
            }
        }
        if (changed) save();
    }

    private static boolean remember(String floor, String name, @Nullable String id, double[] progress, boolean selected) {
        FloorMeter meter = meters().computeIfAbsent(floor, k -> new FloorMeter());
        MeterItem item = meter.items.get(name);
        boolean changed = false;
        if (item == null) {
            item = new MeterItem();
            item.name = name;
            meter.items.put(name, item);
            changed = true;
        }
        if (id != null && !id.equals(item.id)) { item.id = id; changed = true; }
        if (item.id == null) item.id = RepoItems.idByName(name);
        if (item.required != progress[1]) { item.required = progress[1]; changed = true; }
        if (selected && (!name.equals(meter.selected) || meter.progress != progress[0])) {
            meter.selected = name;
            meter.progress = progress[0];
            changed = true;
        }
        return changed;
    }

    /** "Progress: 1,234/50k" on the meter item: {progress, required}, or null. */
    public static double @Nullable [] progress(List<String> lore) {
        for (String line : lore) {
            Matcher m = PROGRESS.matcher(line.trim());
            if (!m.find()) continue;
            double done = number(m.group(1)), required = number(m.group(2));
            if (required > 0 && done >= 0) return new double[]{done, required};
        }
        return null;
    }

    /** "F7" / "M7" from a menu title or item name ("Floor VII", "Master Mode Floor VII", "(M7)"), or null. */
    public static @Nullable String floorIn(String text) {
        Matcher m = TITLE_FLOOR.matcher(text);
        if (!m.find()) return null;
        if (m.group(1) != null) return m.group(1).toUpperCase(Locale.ROOT) + m.group(2);
        if (m.group(4) != null) {
            String n = m.group(4);
            int level = Character.isDigit(n.charAt(0)) ? Integer.parseInt(n) : roman(n);
            return level < 1 || level > 7 ? null : (m.group(3) != null ? "M" : "F") + level;
        }
        return m.group(5).toUpperCase(Locale.ROOT) + m.group(6);
    }

    /**
     * The floor's meter, else the other mode's of the same floor: the tab's RNG Meter widget shows "Catacombs Floor I"
     * while you run M1, so a floor's meter is read for both.
     */
    static @Nullable FloorMeter meterFor(String floor) {
        FloorMeter meter = meters().get(floor);
        if ((meter == null || meter.selected == null) && floor.length() == 2) {
            FloorMeter other = meters().get((floor.charAt(0) == 'M' ? "F" : "M") + floor.charAt(1));
            if (other != null && other.selected != null) return other;
        }
        return meter;
    }

    static Map<String, FloorMeter> meters() {
        if (meters == null) {
            try {
                if (Files.exists(file)) meters = GSON.fromJson(Files.readString(file), new com.google.gson.reflect.TypeToken<Map<String, FloorMeter>>() {}.getType());
            } catch (Exception e) {
                TrackLines.LOG.warn("Couldn't read {}: {}", file, e.toString());
            }
            if (meters == null) meters = new LinkedHashMap<>();
        }
        return meters;
    }

    static void save() {
        try {
            Files.writeString(file, GSON.toJson(meters()));
        } catch (Exception e) {
            TrackLines.LOG.warn("Couldn't save {}: {}", file, e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------ parsing and text

    /** "m1" -> "M1", "F7", "e" -> "E"; null if it isn't a floor. */
    public static @Nullable String floor(String text) {
        Matcher m = FLOOR.matcher(text.trim());
        if (!m.matches()) return null;
        return m.group(1) == null ? "E" : m.group(1).toUpperCase(Locale.ROOT) + m.group(2);
    }

    /** "1:20", "1:20.5", "80", "80s", "1m20s" in seconds, or -1. */
    public static double seconds(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.contains(":")) {
                String[] parts = t.split(":");
                double total = 0;
                for (String part : parts) total = total * 60 + Double.parseDouble(part);
                return total;
            }
            Matcher m = Pattern.compile("^(?:(\\d+(?:\\.\\d+)?)m)?(?:(\\d+(?:\\.\\d+)?)s?)?$").matcher(t);
            if (m.matches() && (m.group(1) != null || m.group(2) != null)) {
                return (m.group(1) == null ? 0 : Double.parseDouble(m.group(1)) * 60) + (m.group(2) == null ? 0 : Double.parseDouble(m.group(2)));
            }
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    public static String time(double seconds) {
        int s = (int) Math.round(seconds);
        return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    private static double number(String text) {
        String t = text.replace(",", "").trim().toLowerCase(Locale.ROOT);
        double mult = 1;
        if (t.endsWith("k")) mult = 1_000;
        else if (t.endsWith("m")) mult = 1_000_000;
        if (mult != 1) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double number(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsDouble();
    }

    private static String string(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static int roman(String numeral) {
        int total = 0, previous = 0;
        for (int i = numeral.length() - 1; i >= 0; i--) {
            int value = switch (Character.toUpperCase(numeral.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    static String coins(double value) {
        return (value < 0 ? "-" : "") + CombatFeatures.formatCoins(Math.abs(value));
    }

    public static String whole(double value) {
        return String.format(Locale.US, "%,d", Math.round(value));
    }

    private static MutableComponent line(String label, String value, @Nullable String hover) {
        MutableComponent c = Component.literal("§7" + label).append(Component.literal("§6" + value));
        if (hover != null) c.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover).withStyle(ChatFormatting.GRAY))));
        return c;
    }

    private static MutableComponent button(String label, String command, String hover) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
