package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Pest Finder teleport hotkey and /shtpinfested (https://github.com/hannibal002/SkyHanni, LGPL-2.1:
 * PestFinder, with the plot and pest tracking from PestApi, GardenPlotApi and GardenPlot): warps to the nearest
 * plot with pests on it. Which plots have pests comes from the sidebar, the tab list's Pests widget, the Configure
 * Plots menu, and the spawn, kill and "no pests" chat messages.
 */
public final class PestFinder {
    private static final double PLOT_SIZE = 96.0;
    private static final int PLOT_GRID_SIZE = 5;
    private static final double PLOT_GRID_MIN = -240.0;
    private static final double PLOT_GRID_MAX = 240.0;
    private static final int[][] PLOT_MAP = {
        {21, 13, 9, 14, 22},
        {15, 5, 1, 6, 16},
        {10, 2, 0, 3, 11},
        {17, 7, 4, 8, 18},
        {23, 19, 12, 20, 24},
    };

    // " ⏣ The Garden ൠ x1"
    private static final Pattern PESTS_IN_SCOREBOARD = Pattern.compile("^\\S The Garden \\S x(?<pests>\\d+)");
    // " ⏣ Plot - 22a" / " ⏣ The Garden" in green: no pests anywhere.
    private static final Pattern NO_PESTS_IN_SCOREBOARD = Pattern.compile("^\\S (?:The Garden|Plot - .+)$");
    // "   Plot - 4 ൠ x1"
    private static final Pattern PESTS_IN_PLOT_SCOREBOARD = Pattern.compile("\\s*Plot - (?<plot>.+) \\S x(?<pests>\\d+)");
    // " Plot - 3"
    private static final Pattern NO_PESTS_IN_PLOT_SCOREBOARD = Pattern.compile("\\s*Plot - (?<plot>.{1,3})");
    // "ൠ This plot has 5 ൠ Pests!"
    private static final Pattern PEST_INVENTORY = Pattern.compile("\\S This plot has (?<amount>\\d+) \\S Pests?!");
    // " Plots: 4, 12, 13, 18, 20"
    private static final Pattern INFESTED_PLOTS_TAB_LIST = Pattern.compile("\\s*Plots: (?<plots>.*)");
    // "You received 7x Enchanted Potato for killing a Locust!"
    private static final Pattern PEST_DEATH_CHAT = Pattern.compile("You received (?<amount>[0-9]*)x (?<item>.*) for killing an? (?<pest>.*)!");
    private static final String NO_PESTS_CHAT = "There are not any Pests on your Garden right now! Keep farming!";
    private static final Pattern PLOT_NAME = Pattern.compile("Plot - (?<name>.*)");
    private static final Pattern UNCLEANED_PLOT = Pattern.compile("Cleanup: .* Completed");
    private static final Pattern UNLOCK_PLOT_CHAT = Pattern.compile("Unlocked Garden Plot - (?<plot>.*)!");
    private static final Pattern CLEAN_PLOT_CHAT = Pattern.compile("Plot - (?<plot>.*) is now clean!");

    /** A plot's saved state (SkyHanni's GardenPlotApi.PlotData). */
    static final class PlotData {
        @Expose String name;
        @Expose int pests;
        @Expose boolean isPestCountInaccurate;
        @Expose boolean isBeingPasted;
        @Expose boolean locked = true;
        @Expose boolean uncleared;
        @Expose boolean greenhouse;
    }

    private static final class GardenData {
        @Expose Map<Integer, PlotData> plotData = new HashMap<>();
        @Expose int scoreboardPests;
    }

    /** One of the 25 plots: its id, where it is in Configure Plots, and its area. */
    record Plot(int id, int inventorySlot, double minX, double minZ, double maxX, double maxZ) {
        double middleX() {
            return (minX + maxX) / 2;
        }

        double middleZ() {
            return (minZ + maxZ) / 2;
        }

        PlotData data() {
            return garden().plotData.computeIfAbsent(id, k -> {
                PlotData d = new PlotData();
                d.name = String.valueOf(id);
                return d;
            });
        }

        String name() {
            String name = data().name;
            return name == null ? String.valueOf(id) : name;
        }

        boolean isBarn() {
            return id == 0;
        }

        String tpName() {
            return isBarn() ? "barn" : name();
        }

        boolean isPlayerInside() {
            return getCurrentPlot() == this;
        }
    }

    /** The 25 plots in map order: row by row from the north-west corner, as in Configure Plots. */
    static final List<Plot> PLOTS = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
    private static Map<String, GardenData> storage = new HashMap<>();
    private static Path file;
    private static boolean dirty;

    private static boolean firstScoreboardCheck;
    private static long gardenJoinTime;
    private static boolean wasInGarden;
    private static Set<String> lastScoreboard = Set.of();
    private static long lastKeyPress;
    private static boolean keyWasDown;
    private static final List<long[]> FIX_PESTS = new ArrayList<>(); // {runAt, loopsLeft}
    private static int ticks;

    static {
        int slot = 2;
        for (int y = 0; y < PLOT_MAP.length; y++) {
            for (int x = 0; x < PLOT_MAP[y].length; x++) {
                double minX = (x - 2) * 96 - 48;
                double minZ = (y - 2) * 96 - 48;
                PLOTS.add(new Plot(PLOT_MAP[y][x], slot, minX, minZ, minX + 96, minZ + 96));
                slot++;
            }
            slot += 4;
        }
    }

    private PestFinder() {}

    static FeatureConfigs.PestFinder config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.garden.pestFinder;
    }

    public static void init() {
        file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("sky2m").resolve("garden-plots.json");
        load();
        Sky2MChat.onChat(message -> {
            if (Sky2MLocation.inGarden()) onChat(message.text());
        });
        ClientTickEvents.END_CLIENT_TICK.register(PestFinder::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> save());
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof ContainerScreen container && Sky2MLocation.inGarden()
                && screen.getTitle().getString().equals("Configure Plots")) {
                boolean[] done = {false};
                ScreenEvents.afterTick(screen).register(s -> {
                    if (!done[0]) done[0] = readConfigurePlots(container);
                });
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            List<String> roots = new ArrayList<>();
            if (!FabricLoader.getInstance().isModLoaded("skyhanni")) roots.add("shtpinfested");
            roots.add("s2tpinfested");
            for (String root : roots) {
                dispatcher.register(ClientCommands.literal(root).executes(ctx -> {
                    teleportNearestInfestedPlot();
                    return 1;
                }));
            }
        });
    }

    // ---------------------------------------------------------------------------------------------- teleport

    private static void tick(Minecraft mc) {
        boolean inGarden = mc.player != null && Sky2MLocation.inGarden();
        if (inGarden && !wasInGarden) {
            gardenJoinTime = System.currentTimeMillis();
            firstScoreboardCheck = false;
            lastScoreboard = Set.of();
        }
        wasInGarden = inGarden;
        if (!inGarden) {
            keyWasDown = false;
            return;
        }

        // KeyPressEvent: the Teleport Hotkey, with no menu open.
        FeatureConfigs.PestFinder c = config();
        int key = c == null ? InputConstants.UNKNOWN.getValue() : c.teleportHotkey;
        boolean down = key > 0 && mc.gui.screen() == null && InputConstants.isKeyDown(key);
        if (down && !keyWasDown && System.currentTimeMillis() - lastKeyPress >= 2_000) {
            lastKeyPress = System.currentTimeMillis();
            teleportNearestInfestedPlot();
        }
        keyWasDown = down;

        // Delayed pest count fixes.
        long now = System.currentTimeMillis();
        for (long[] run : List.copyOf(FIX_PESTS)) {
            if (run[0] <= now) {
                FIX_PESTS.remove(run);
                fixPestsNow((int) run[1]);
            }
        }

        if (!firstScoreboardCheck && now - gardenJoinTime > 5_000) {
            List<SidebarLine> lines = sidebar(mc);
            checkScoreboardLines(lines);
            lastScoreboard = new HashSet<>(lines.stream().map(SidebarLine::text).toList());
            firstScoreboardCheck = true;
            updatePests();
        } else if (firstScoreboardCheck && ++ticks % 5 == 0) {
            // ScoreboardUpdateEvent: only the lines that changed.
            List<SidebarLine> lines = sidebar(mc);
            Set<String> current = new HashSet<>();
            List<SidebarLine> added = new ArrayList<>();
            for (SidebarLine line : lines) {
                current.add(line.text());
                if (!lastScoreboard.contains(line.text())) added.add(line);
            }
            lastScoreboard = current;
            if (!added.isEmpty()) checkScoreboardLines(added);
            readTabList();
        }
        if (ticks % 1200 == 0) save();
    }

    static void teleportNearestInfestedPlot() {
        // need to check again for the command
        if (!Sky2MLocation.inGarden()) {
            userError("This command only works while on the Garden!");
        }
        FeatureConfigs.PestFinder c = config();
        Plot plot = getNearestInfestedPlot();
        if (plot == null) {
            if (c != null && c.backToGarden) {
                sendCommand("warp garden");
                return;
            }
            userError("No infested plots detected to warp to!");
            return;
        }
        if (plot.isPlayerInside() && (c == null || !c.alwaysTp)) {
            userError("You're already in an infested plot!");
            return;
        }
        sendCommand("plottp " + plot.tpName());
    }

    // ---------------------------------------------------------------------------------------------- plots

    static Plot getCurrentPlot() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        return getPlot(player.getX(), player.getY(), player.getZ());
    }

    private static Plot getPlot(double x, double y, double z) {
        if (y < 0 || y >= 256) return null;
        Integer plotX = toPlotIndex(x);
        Integer plotZ = toPlotIndex(z);
        if (plotX == null || plotZ == null) return null;
        return getPlotById(PLOT_MAP[plotZ][plotX]);
    }

    private static Integer toPlotIndex(double value) {
        if (value < PLOT_GRID_MIN || value > PLOT_GRID_MAX) return null;
        if (value >= PLOT_GRID_MAX) return PLOT_GRID_SIZE - 1;
        return Math.max(0, Math.min(PLOT_GRID_SIZE - 1, (int) Math.floor((value - PLOT_GRID_MIN) / PLOT_SIZE)));
    }

    private static Plot getPlotById(int id) {
        for (Plot plot : PLOTS) if (plot.id() == id) return plot;
        return null;
    }

    private static Plot getPlotByName(String name) {
        for (Plot plot : PLOTS) if (plot.name().equals(name)) return plot;
        return null;
    }

    static List<Plot> getInfestedPlots() {
        return PLOTS.stream().filter(p -> p.data().pests > 0 || p.data().isPestCountInaccurate).toList();
    }

    private static Plot getNearestInfestedPlot() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        return getInfestedPlots().stream().min(Comparator.comparingDouble(p -> {
            double dx = p.middleX() - player.getX(), dy = 10 - player.getY(), dz = p.middleZ() - player.getZ();
            return dx * dx + dy * dy + dz * dz;
        })).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------- pest tracking

    /** PestSpawnEvent, from {@link PestTimer}: amount null for the "while you were offline" message. */
    static void onPestSpawn(Integer amount, List<String> plotNames) {
        if (!Sky2MLocation.inGarden()) return;
        for (String plotName : plotNames) {
            Plot plot = getPlotByName(plotName);
            if (plot == null) {
                userError("Open Plot Management Menu to load plot names and pest locations!");
                return;
            }
            if (amount != null) {
                plot.data().pests += amount;
                plot.data().isPestCountInaccurate = false;
            } else {
                plot.data().isPestCountInaccurate = true;
            }
        }
        if (amount != null) garden().scoreboardPests += amount;
        dirty = true;
        updatePests();
    }

    private static void onChat(String message) {
        if (message.equals(NO_PESTS_CHAT)) {
            resetAllPests();
            return;
        }
        Matcher m = PEST_DEATH_CHAT.matcher(message);
        if (m.matches()) {
            removeNearestPest();
            return;
        }
        m = CLEAN_PLOT_CHAT.matcher(message);
        if (m.matches()) {
            Plot plot = parseIdPlot(m.group("plot"));
            if (plot != null) plot.data().uncleared = false;
        }
        m = UNLOCK_PLOT_CHAT.matcher(message);
        if (m.matches()) {
            Plot plot = parseIdPlot(m.group("plot"));
            if (plot != null) plot.data().locked = false;
        }
    }

    private static Plot parseIdPlot(String text) {
        try {
            return getPlotById(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Configure Plots: infested plots' slots in red (SkyHanni's plot menu highlighting), behind the item. */
    public static void renderSlot(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.world.inventory.Slot slot) {
        FeatureConfigs.PestFinder c = config();
        if (c == null || !c.highlightInfestedPlots || !Sky2MLocation.inGarden()) return;
        if (!(Minecraft.getInstance().gui.screen() instanceof ContainerScreen screen)
            || !screen.getTitle().getString().equals("Configure Plots") || slot.container != screen.getMenu().getContainer()) return;
        for (Plot plot : PLOTS) {
            if (plot.inventorySlot() != slot.index) continue;
            if (plot.data().pests > 0 || plot.data().isPestCountInaccurate) g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0xA0FF3030);
            return;
        }
    }

    /** Configure Plots: every plot's name, whether it's locked or uncleared, and how many pests it has. */
    private static boolean readConfigurePlots(ContainerScreen container) {
        var menu = container.getMenu();
        boolean any = false;
        for (Plot plot : PLOTS) {
            if (plot.inventorySlot() >= menu.slots.size()) continue;
            ItemStack stack = menu.slots.get(plot.inventorySlot()).getItem();
            if (stack.isEmpty()) continue;
            any = true;
            String hoverName = Sky2MLocation.strip(stack.getHoverName().getString()).trim();
            Matcher name = PLOT_NAME.matcher(hoverName);
            if (name.matches()) plot.data().name = name.group("name");
            if (hoverName.equals("The Barn")) plot.data().name = "The Barn";
            List<String> lore = lore(stack);
            plot.data().locked = false;
            plot.data().isBeingPasted = false;
            plot.data().greenhouse = false;
            for (String line : lore) {
                if (line.contains("Cost:")) plot.data().locked = true;
                if (line.contains("Pasting in progress:")) plot.data().isBeingPasted = true;
                plot.data().uncleared = false;
                if (UNCLEANED_PLOT.matcher(line).matches()) plot.data().uncleared = true;
                if (line.equals("Greenhouse Plot")) plot.data().greenhouse = true;
            }
            // PestApi: the pest count.
            if (plot.isBarn() || plot.data().locked || plot.data().uncleared) continue;
            plot.data().pests = 0;
            plot.data().isPestCountInaccurate = false;
            for (String line : lore) {
                Matcher pests = PEST_INVENTORY.matcher(line);
                if (pests.find()) {
                    plot.data().pests = Integer.parseInt(pests.group("amount"));
                    break;
                }
            }
        }
        if (any) {
            dirty = true;
            updatePests();
        }
        return any;
    }

    /** WidgetUpdateEvent for the Pests widget: " Plots: 4, 12, 13". */
    private static void readTabList() {
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = Compat.rawTabName(info);
            if (name == null) continue;
            Matcher m = INFESTED_PLOTS_TAB_LIST.matcher(Sky2MLocation.strip(name.getString()));
            if (!m.matches()) continue;
            Set<Integer> tabListPlots = new HashSet<>();
            String plots = m.group("plots").trim();
            if (plots.equalsIgnoreCase("None")) return;
            for (String part : plots.split(", ")) {
                try {
                    tabListPlots.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException e) {
                    return;
                }
            }
            Set<Integer> apiPlots = new HashSet<>();
            for (Plot p : getInfestedPlots()) apiPlots.add(p.id());
            if (tabListPlots.equals(apiPlots)) return;
            for (Plot plot : PLOTS) {
                if (tabListPlots.contains(plot.id())) {
                    if (!plot.data().isPestCountInaccurate && plot.data().pests == 0) plot.data().isPestCountInaccurate = true;
                } else {
                    plot.data().pests = 0;
                    plot.data().isPestCountInaccurate = false;
                }
            }
            dirty = true;
            updatePests();
            return;
        }
    }

    private record SidebarLine(String text, boolean green) {}

    /** The SkyBlock sidebar's lines, and whether each line's location text is green. */
    private static List<SidebarLine> sidebar(Minecraft mc) {
        List<SidebarLine> lines = new ArrayList<>();
        if (mc.level == null) return lines;
        Scoreboard board = mc.level.getScoreboard();
        Objective objective = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        for (Objective o : board.getObjectives()) {
            if (Sky2MLocation.strip(o.getDisplayName().getString()).contains("SKYBLOCK")) objective = o;
        }
        if (objective == null) return lines;
        List<PlayerScoreEntry> entries = new ArrayList<>(board.listPlayerScores(objective));
        entries.removeIf(PlayerScoreEntry::isHidden);
        for (PlayerScoreEntry entry : entries) {
            PlayerTeam team = board.getPlayersTeam(entry.owner());
            Component component = PlayerTeam.formatNameForTeam(team, entry.ownerName());
            String text = SUPPLEMENTARY.matcher(Sky2MLocation.strip(component.getString())).replaceAll("").trim();
            lines.add(new SidebarLine(text, locationIsGreen(component)));
        }
        return lines;
    }

    // Compiled once: these ran for every sidebar line on every check.
    private static final Pattern SUPPLEMENTARY = Pattern.compile("[\\x{10000}-\\x{10FFFF}]");
    private static final Pattern LOCATION_COLOUR = Pattern.compile("§(.)(?:The Garden|Plot)");

    /** Whether "The Garden" / "Plot" is green: Hypixel shows it red while there are pests in the Garden. */
    private static boolean locationIsGreen(Component component) {
        String raw = component.getString();
        if (raw.indexOf('§') >= 0) {
            Matcher m = LOCATION_COLOUR.matcher(raw);
            return m.find() && m.group(1).equals("a");
        }
        boolean[] green = {false};
        component.visit((style, value) -> {
            if (value.contains("The Garden") || value.startsWith("Plot")) {
                TextColor colour = style.getColor();
                green[0] = colour != null && colour.getValue() == 0x55FF55;
                return Optional.of(true);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return green[0];
    }

    private static void checkScoreboardLines(List<SidebarLine> list) {
        GardenData garden = garden();
        for (SidebarLine line : list) {
            String text = line.text();
            // gets if there are no pests remaining in the garden
            if (line.green() && NO_PESTS_IN_SCOREBOARD.matcher(text).matches() && !text.contains(" x")) {
                if (garden.scoreboardPests != 0 || !getInfestedPlots().isEmpty()) resetAllPests();
                return;
            }
            // gets the total amount of pests in the garden
            Matcher m = PESTS_IN_SCOREBOARD.matcher(text);
            if (m.find()) {
                int newPests = Integer.parseInt(m.group("pests"));
                if (newPests != garden.scoreboardPests) {
                    garden.scoreboardPests = newPests;
                    dirty = true;
                    updatePests();
                }
            }
            // gets the amount of pests in the current plot
            m = PESTS_IN_PLOT_SCOREBOARD.matcher(text);
            if (m.matches()) {
                Plot plot = getPlotByName(m.group("plot"));
                if (plot == null) return;
                int pestsInPlot = Integer.parseInt(m.group("pests"));
                if (pestsInPlot != plot.data().pests || plot.data().isPestCountInaccurate) {
                    plot.data().pests = pestsInPlot;
                    plot.data().isPestCountInaccurate = false;
                    dirty = true;
                    updatePests();
                }
            }
            // gets if there are no pests remaining in the current plot
            m = NO_PESTS_IN_PLOT_SCOREBOARD.matcher(text);
            if (m.matches()) {
                Plot plot = getPlotByName(m.group("plot"));
                if (plot == null) return;
                if (plot.data().pests != 0 || plot.data().isPestCountInaccurate) {
                    plot.data().pests = 0;
                    plot.data().isPestCountInaccurate = false;
                    dirty = true;
                    updatePests();
                }
            }
        }
    }

    private static void updatePests() {
        if (!firstScoreboardCheck) return;
        FIX_PESTS.add(new long[]{System.currentTimeMillis() + 2_000, 2});
    }

    private static void fixPestsNow(int loop) {
        GardenData garden = garden();
        List<Plot> accurate = PLOTS.stream().filter(p -> p.data().pests > 0 && !p.data().isPestCountInaccurate).toList();
        List<Plot> inaccurate = PLOTS.stream().filter(p -> p.data().isPestCountInaccurate).toList();
        int accurateAmount = accurate.stream().mapToInt(p -> p.data().pests).sum();
        int inaccurateAmount = inaccurate.size();
        if (garden.scoreboardPests == accurateAmount + inaccurateAmount) {
            // all inaccurate plots have 1 pest each
            for (Plot plot : inaccurate) {
                plot.data().pests = 1;
                plot.data().isPestCountInaccurate = false;
            }
        } else if (inaccurateAmount == 1) {
            // all the inaccurate pests are in the only inaccurate plot
            Plot plot = inaccurate.getFirst();
            plot.data().pests = garden.scoreboardPests - accurateAmount;
            plot.data().isPestCountInaccurate = false;
        } else if (accurateAmount + inaccurateAmount > garden.scoreboardPests) {
            // impossible pest counts
            for (Plot plot : getInfestedPlots()) {
                plot.data().pests = 0;
                plot.data().isPestCountInaccurate = true;
            }
            if (loop > 0) FIX_PESTS.add(new long[]{System.currentTimeMillis() + 2_000, loop - 1});
        }
        dirty = true;
    }

    private static void removeNearestPest() {
        Plot plot = getNearestInfestedPlot();
        if (plot == null) {
            updatePests();
            return;
        }
        if (!plot.data().isPestCountInaccurate) plot.data().pests--;
        garden().scoreboardPests--;
        dirty = true;
        updatePests();
    }

    private static void resetAllPests() {
        garden().scoreboardPests = 0;
        for (Plot plot : PLOTS) {
            plot.data().pests = 0;
            plot.data().isPestCountInaccurate = false;
        }
        dirty = true;
        updatePests();
    }

    // ---------------------------------------------------------------------------------------------- storage

    private static GardenData garden() {
        String id = Sky2MStorageSearch.currentProfile();
        return storage.computeIfAbsent(id == null || id.isEmpty() ? "unknown" : id, k -> new GardenData());
    }

    private static void load() {
        try {
            if (Files.exists(file)) {
                Map<String, GardenData> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, GardenData>>() {}.getType());
                if (loaded != null) {
                    storage = new HashMap<>(loaded);
                    storage.values().removeIf(java.util.Objects::isNull);
                    for (GardenData g : storage.values()) if (g.plotData == null) g.plotData = new HashMap<>();
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load garden-plots.json: " + e.getMessage());
        }
    }

    private static void save() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(storage), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save garden-plots.json: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (Component c : lore.lines()) lines.add(Sky2MLocation.strip(c.getString()).trim());
        return lines;
    }

    static void sendCommand(String command) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.sendCommand(command);
    }

    private static void userError(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal("§c" + message)));
        });
    }
}
