package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MCraftHelper;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.fishing.FishingData;
import com.epic60869.sky2m.features.slayer.SlayerBossProfit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.Expose;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profit Trackers, ported from Skysoft's Profit Tracker (https://github.com/Akinsoft/Skysoft, features/profit/,
 * config/ProfitTrackerConfig.kt and data/profit_tracker_presets.json, LGPL-3.0). One tracker per activity: Farming,
 * Fishing, Foraging, Mining, the Mythological Ritual and the six slayers, each shown while you're doing it (where the
 * preset's islands and areas say, with a slayer quest of its type, a fishing rod cast or a spade in your hotbar).
 * Items count when they come into your inventory (not from a chest or menu) or your sacks, if the preset tracks them;
 * crops Replenish takes back and items a compactor turns into enchanted ones are taken off again. Coins (Bountiful,
 * mob kills), quest costs, Kernels, the activity's actions (pests vacuumed, catches, burrows dug, bosses killed),
 * profit per hour and uptime (paused when you stop) are kept for this session, today and in total. The HUD is drawn
 * by {@link ProfitTrackerHud}.
 */
public final class ProfitTracker {
    public enum Period {
        SESSION("Session"), TODAY("Today"), TOTAL("Total");

        public final String label;

        Period(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Preset {
        ZOMBIE("Zombie Slayer", "Mob Kill Coins", "Bosses Killed", true),
        SPIDER("Spider Slayer", "Mob Kill Coins", "Bosses Killed", true),
        WOLF("Wolf Slayer", "Mob Kill Coins", "Bosses Killed", true),
        ENDERMAN("Enderman Slayer", "Mob Kill Coins", "Bosses Killed", true),
        BLAZE("Blaze Slayer", "Mob Kill Coins", "Bosses Killed", true),
        VAMPIRE("Vampire Slayer", "Mob Kill Coins", "Bosses Killed", true),
        FARMING("Farming", "Bountiful Coins", "Pests Vacuumed", false),
        FISHING("Fishing", "Coins", "Catches", true),
        FORAGING("Foraging", "Mob Kill Coins", "Bosses Killed", false),
        MINING("Mining", "Mob Kill Coins", "Bosses Killed", false),
        MYTHOLOGICAL_RITUAL("Mythological Ritual", "Coins", "Burrows Dug", true);

        public final String displayName;
        public final String coinLabel;
        public final String actionLabel;
        final boolean requiresPreference;

        Preset(String displayName, String coinLabel, String actionLabel, boolean requiresPreference) {
            this.displayName = displayName;
            this.coinLabel = coinLabel;
            this.actionLabel = actionLabel;
            this.requiresPreference = requiresPreference;
        }

        boolean slayer() {
            return ordinal() <= VAMPIRE.ordinal();
        }
    }

    /** One period's numbers. */
    static final class Stats {
        @Expose Map<String, Long> items = new LinkedHashMap<>();
        @Expose double coins;
        @Expose long kernels;
        @Expose long actions;
        @Expose Map<String, Long> pestKills = new LinkedHashMap<>();
        @Expose long activeMs;
        @Expose long costs;
    }

    static final class Saved {
        @Expose Stats total = new Stats();
        @Expose Stats today = new Stats();
        /** The day {@link #today} is for (epoch day), so it starts again at midnight. */
        @Expose long day;
    }

    private record PresetData(boolean anyIsland, Set<String> islands, Set<String> areas, Map<String, Set<String>> islandAreas, Set<String> items) {}

    /** Crafting an item from one kind of item, e.g. 160 Wheat -> 1 Enchanted Wheat. */
    private record Conversion(String input, int inputCount, int outputCount) {}

    private static final Pattern PEST_KILL = Pattern.compile("^You received (\\d+)x (.+) for killing an? (.+)!$");
    private static final String KERNEL_DONATION = "[NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse.";
    private static final Pattern NUMBER = Pattern.compile("[\\d,]+(?:\\.\\d+)?");
    private static final Pattern TREASURE_CATCH = Pattern.compile("^. (?:GOOD|GREAT|OUTSTANDING|MUSICAL) CATCH!|^. (?:GOOD|GREAT|OUTSTANDING) JUNK CATCH!|^. TROPHY (?:FISH|FROG)!");
    private static final long BOUNTIFUL_WINDOW_MS = 2_000L;
    private static final long COMPACTION_WINDOW_MS = 60_000L;
    private static final long SACK_WINDOW_MS = 60_000L;
    private static final long QUEST_COST_WINDOW_MS = 3_000L;
    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();

    private static final Map<Preset, PresetData> PRESETS = new EnumMap<>(Preset.class);
    private static final Map<Preset, Saved> SAVED = new EnumMap<>(Preset.class);
    private static final Map<Preset, Stats> SESSION = new EnumMap<>(Preset.class);
    private static final Map<Preset, Long> lastActivity = new EnumMap<>(Preset.class);
    /** Item quantities that just changed: "PRESET:ITEM" -> when. */
    static final Map<String, Long> HIGHLIGHTS = new ConcurrentHashMap<>();
    private static final Map<String, Conversion> conversions = new ConcurrentHashMap<>();
    private static final Map<String, Integer> replenishCosts = new HashMap<>();
    private static final Map<String, long[]> removals = new HashMap<>();
    private static final ArrayDeque<double[]> purseHistory = new ArrayDeque<>();

    private static Path file;
    private static boolean dirty;
    private static int ticks;
    private static long lastTick;
    private static Map<String, Integer> lastInventory;
    private static double lastPurse = -1;
    private static long lastFarmed;
    private static Preset shownPreset;
    /** The preset counting before the current one, and when you left it: sack drops arrive a while later. */
    private static Preset previousPreset;
    private static Preset lastCounting;
    private static long previousPresetLeftAt;
    private static long questStartedAt;
    private static Preset questPreset;
    private static boolean conversionsRequested;

    private ProfitTracker() {}

    static FeatureConfigs.ProfitTrackerSettings config(Preset preset) {
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null) return null;
        FeatureConfigs.ProfitTrackers t = c.profitTrackers;
        return switch (preset) {
            case FARMING -> t.farming;
            case FISHING -> t.fishing;
            case FORAGING -> t.foraging;
            case MINING -> t.mining;
            case MYTHOLOGICAL_RITUAL -> t.mythologicalRitual;
            case ZOMBIE -> t.zombie;
            case SPIDER -> t.spider;
            case WOLF -> t.wolf;
            case ENDERMAN -> t.enderman;
            case BLAZE -> t.blaze;
            case VAMPIRE -> t.vampire;
        };
    }

    static boolean enabled(Preset preset) {
        FeatureConfigs.ProfitTrackerSettings c = config(preset);
        return c != null && c.enabled;
    }

    private static boolean anyEnabled() {
        for (Preset preset : Preset.values()) if (enabled(preset)) return true;
        return false;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("profit-trackers.json");
        loadPresets();
        for (Preset preset : Preset.values()) {
            SAVED.put(preset, new Saved());
            SESSION.put(preset, new Stats());
        }
        load(configDir);
        ClientTickEvents.END_CLIENT_TICK.register(ProfitTracker::tick);
        Sky2MChat.onChat(message -> onChat(message.component(), message.text().trim()));
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> onBreak(state.getBlock(), level.getGameTime()));
        ProfitTrackerHud.init();
    }

    // ------------------------------------------------------------------------------------------------ presets

    private static void loadPresets() {
        try (var stream = ProfitTracker.class.getResourceAsStream("/assets/sky2m/data/profit_tracker_presets.json")) {
            if (stream == null) return;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Preset preset : Preset.values()) {
                JsonObject data = switch (preset) {
                    case FARMING -> root.getAsJsonObject("farming");
                    case FISHING -> root.getAsJsonObject("fishing");
                    case FORAGING -> root.getAsJsonObject("foraging");
                    case MINING -> root.getAsJsonObject("mining");
                    case MYTHOLOGICAL_RITUAL -> root.getAsJsonObject("mythologicalRitual");
                    default -> root.getAsJsonObject("slayer").getAsJsonObject(preset.name());
                };
                if (data == null) continue;
                Map<String, Set<String>> islandAreas = new HashMap<>();
                if (data.has("islandAreas")) {
                    for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("islandAreas").entrySet()) islandAreas.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
                }
                Set<String> items = new LinkedHashSet<>();
                if (data.has("items")) for (String item : strings(data.getAsJsonArray("items"))) items.add(item.replace('-', ':'));
                PRESETS.put(preset, new PresetData(data.has("anyIsland") && data.get("anyIsland").getAsBoolean(),
                    data.has("islands") ? strings(data.getAsJsonArray("islands")) : Set.of(),
                    data.has("areas") ? strings(data.getAsJsonArray("areas")) : Set.of(), islandAreas, items));
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the profit tracker presets: " + e.getMessage());
        }
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement e : array) if (!e.getAsString().isBlank()) out.add(e.getAsString());
        return out;
    }

    /** Skysoft's ProfitTrackerPresets.forLocation: the preset for this island and area, preferring {@code preferred}. */
    private static Preset forLocation(String island, String area, Preset preferred) {
        if (island == null || island.isEmpty()) return null;
        List<Preset> matches = new ArrayList<>();
        for (Map.Entry<Preset, PresetData> e : PRESETS.entrySet()) {
            Preset type = e.getKey();
            PresetData data = e.getValue();
            if (type == Preset.FISHING && island.equals("Garden")) continue;
            if (type.requiresPreference && preferred != type) continue;
            if (!data.anyIsland() && !data.islands().contains(island)) continue;
            if (!data.areas().isEmpty() && !data.areas().contains(area)) continue;
            Set<String> islandAreas = data.islandAreas().get(island);
            if (islandAreas != null && !islandAreas.contains(area)) continue;
            matches.add(type);
        }
        if (preferred != null && matches.contains(preferred)) return preferred;
        List<Preset> nonSlayer = matches.stream().filter(p -> !p.slayer()).toList();
        if (nonSlayer.size() == 1) return nonSlayer.getFirst();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /** The slayer quest on the scoreboard, as a preset. */
    private static Preset slayerQuest() {
        for (String line : Sky2MLocation.scoreboard()) {
            if (line.contains("Revenant Horror") || line.contains("Atoned Horror")) return Preset.ZOMBIE;
            if (line.contains("Tarantula Broodfather") || line.contains("Conjoined Brood")) return Preset.SPIDER;
            if (line.contains("Sven Packmaster")) return Preset.WOLF;
            if (line.contains("Voidgloom Seraph")) return Preset.ENDERMAN;
            if (line.contains("Inferno Demonlord")) return Preset.BLAZE;
            if (line.contains("Riftstalker Bloodfiend")) return Preset.VAMPIRE;
        }
        return null;
    }

    private static boolean spadeInHotbar(Minecraft mc) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getHoverName().getString().contains("Spade")) return true;
        return false;
    }

    /** What you're doing, if it has a tracker that's turned on. */
    static Preset currentPreset() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !Sky2MLocation.onSkyblock()) return null;
        String island = Sky2MLocation.area(), area = Sky2MLocation.location();
        Preset preferred = null;
        if (enabled(Preset.MYTHOLOGICAL_RITUAL) && island.equals("Hub") && spadeInHotbar(mc)) preferred = Preset.MYTHOLOGICAL_RITUAL;
        if (preferred == null) preferred = slayerQuest();
        if (preferred == null && enabled(Preset.FISHING) && (mc.player.fishing != null || !paused(Preset.FISHING, System.currentTimeMillis()) && lastActivity.containsKey(Preset.FISHING))) {
            preferred = Preset.FISHING;
        }
        Preset preset = forLocation(island, area, preferred);
        return preset != null && enabled(preset) ? preset : null;
    }

    /** Where this preset's tracker is shown (and counts), as Skysoft's isInPresetArea. */
    static boolean inPresetArea(Preset preset) {
        return enabled(preset) && currentPreset() == preset;
    }

    /** The tracker to show now: the current activity's, or the last one shown while you're still there. */
    private static Preset shownCached;
    private static long shownCachedTick = Long.MIN_VALUE;

    /** Once per client tick: the HUD asks every frame for each of its 11 trackers. */
    static Preset shownPreset() {
        long tick = ticks;
        if (tick != shownCachedTick) {
            shownCached = computeShownPreset();
            shownCachedTick = tick;
        }
        return shownCached;
    }

    private static Preset computeShownPreset() {
        Preset current = currentPreset();
        if (current != null) shownPreset = current;
        return shownPreset != null && inPresetArea(shownPreset) ? shownPreset : null;
    }

    static Set<String> trackedItems(Preset preset) {
        PresetData data = PRESETS.get(preset);
        return data == null ? Set.of() : data.items();
    }

    private static boolean tracked(Preset preset, String id) {
        return id != null && (trackedItems(preset).contains(id) || conversions.containsKey(id) && trackedItems(preset).contains(conversions.get(id).input()));
    }

    // ------------------------------------------------------------------------------------------------ stats

    private static List<Stats> targets(Preset preset) {
        Saved saved = SAVED.get(preset);
        long day = LocalDate.now().toEpochDay();
        if (saved.day != day) {
            saved.day = day;
            saved.today = new Stats();
        }
        return List.of(SESSION.get(preset), saved.today, saved.total);
    }

    static Stats stats(Preset preset, Period period) {
        return switch (period) {
            case SESSION -> SESSION.get(preset);
            case TODAY -> targets(preset).get(1);
            case TOTAL -> SAVED.get(preset).total;
        };
    }

    static void reset(Preset preset, Period period) {
        switch (period) {
            case SESSION -> SESSION.put(preset, new Stats());
            case TODAY -> SAVED.get(preset).today = new Stats();
            case TOTAL -> SAVED.get(preset).total = new Stats();
        }
        dirty = true;
    }

    private static void activity(Preset preset) {
        lastActivity.put(preset, System.currentTimeMillis());
    }

    static boolean paused(Preset preset, long now) {
        FeatureConfigs.ProfitTrackerSettings c = config(preset);
        if (c == null || !c.settings.pauseAfter) return false;
        Long last = lastActivity.get(preset);
        return last == null || now - last > Math.clamp(c.settings.pauseAfterSeconds, 15, 900) * 1000L;
    }

    private static void addItem(Preset preset, String id, long amount) {
        if (amount == 0) return;
        for (Stats stats : targets(preset)) {
            long updated = Math.max(0, stats.items.getOrDefault(id, 0L) + amount);
            if (updated == 0) stats.items.remove(id);
            else stats.items.put(id, updated);
        }
        if (amount > 0) HIGHLIGHTS.put(preset.name() + ":" + id, System.currentTimeMillis());
        activity(preset);
        dirty = true;
    }

    // ------------------------------------------------------------------------------------------------ events

    private static void onBreak(Block block, long gameTime) {
        if (!inPresetArea(Preset.FARMING) || !isCrop(block)) return;
        long now = System.currentTimeMillis();
        activity(Preset.FARMING);
        lastFarmed = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        CompoundTag data = Compat.getCustomData(mc.player.getMainHandItem());
        if (!data.getCompoundOrEmpty("enchantments").contains("replenish")) return;
        String crop = replenishCrop(block, gameTime);
        if (crop != null) replenishCosts.merge(crop, 1, Integer::sum);
    }

    private static void onChat(Component component, String text) {
        if (!anyEnabled() || !Sky2MLocation.onSkyblock()) return;
        Preset current = currentPreset();
        if (current == Preset.FARMING) {
            if (text.equals(KERNEL_DONATION)) {
                for (Stats stats : targets(current)) stats.kernels++;
                activity(current);
                dirty = true;
                return;
            }
            Matcher pest = PEST_KILL.matcher(text);
            if (pest.matches()) {
                String item = pest.group(2), name = pest.group(3);
                // One message per drop: count the kill once (Skysoft's ParsedGardenPestKill).
                boolean kill = switch (name) {
                    case "Field Mouse" -> item.equals("Dung");
                    case "Lunar Moth" -> item.equals("Enchanted Sunflower");
                    default -> !item.equals("Overclocker 3000");
                };
                if (kill) {
                    for (Stats stats : targets(current)) {
                        stats.actions++;
                        stats.pestKills.merge(name, 1L, Long::sum);
                    }
                    activity(current);
                    dirty = true;
                }
                return;
            }
        }
        if (enabled(Preset.FISHING) && (TREASURE_CATCH.matcher(text).find() || isSeaCreatureCatch(text))) {
            // A catch means you're fishing, wherever you are.
            activity(Preset.FISHING);
            if (currentPreset() == Preset.FISHING) {
                for (Stats stats : targets(Preset.FISHING)) stats.actions++;
                dirty = true;
            }
            return;
        }
        if (current == Preset.MYTHOLOGICAL_RITUAL && (text.startsWith("You dug out a Griffin Burrow!") || text.startsWith("You finished the Griffin burrow chain!"))) {
            for (Stats stats : targets(current)) stats.actions++;
            activity(current);
            dirty = true;
            return;
        }
        if (text.equals("SLAYER QUEST STARTED!")) {
            questStartedAt = System.currentTimeMillis();
            questPreset = null;
            return;
        }
        if (text.equals("SLAYER QUEST COMPLETE!")) {
            Preset slayer = slayerQuest();
            if (slayer != null && inPresetArea(slayer)) {
                for (Stats stats : targets(slayer)) stats.actions++;
                activity(slayer);
                dirty = true;
            }
            return;
        }
        if (text.startsWith("[Sacks]")) {
            Preset target = current != null ? current
                : previousPreset != null && System.currentTimeMillis() - previousPresetLeftAt < SACK_WINDOW_MS && enabled(previousPreset) ? previousPreset : null;
            if (target == null) return;
            for (Map.Entry<String, Long> e : SlayerBossProfit.sackGains(component).entrySet()) {
                if (e.getValue() <= 0) continue;
                String id = normalise(RepoItems.idByName(e.getKey()));
                if (tracked(target, id)) addItem(target, id, e.getValue());
            }
        }
    }

    private static boolean isSeaCreatureCatch(String text) {
        for (FishingData.SeaCreature creature : FishingData.creatures()) if (creature.pattern().matcher(text).find()) return true;
        return false;
    }

    private static void tick(Minecraft mc) {
        ticks++;
        long now = System.currentTimeMillis();
        long delta = lastTick == 0 ? 0 : Math.min(1_000L, now - lastTick);
        lastTick = now;
        HIGHLIGHTS.values().removeIf(at -> now - at > 1_500L);
        if (!anyEnabled() || mc.player == null) {
            lastInventory = null;
            lastPurse = -1;
            return;
        }
        if (!conversionsRequested && RepoItems.itemsLoaded()) {
            conversionsRequested = true;
            RepoItems.runAsync(ProfitTracker::loadConversions);
        }
        Preset current = currentPreset();
        // A cast line keeps the fishing time going (Skysoft refreshes Fishing's activity while the hook is out).
        if (current == Preset.FISHING && mc.player.fishing != null) activity(Preset.FISHING);
        if (current != lastCounting) {
            if (lastCounting != null) {
                previousPreset = lastCounting;
                previousPresetLeftAt = now;
            }
            lastCounting = current;
        }
        if (current != null && mc.isWindowActive() && !paused(current, now)) {
            for (Stats stats : targets(current)) stats.activeMs += delta;
        }
        removals.values().removeIf(r -> r[1] < now);
        trackPurse(mc, current, now);
        trackQuestCost(now);
        if (mc.gui.screen() != null || current == null) {
            lastInventory = null;
        } else {
            Map<String, Integer> inventory = inventory(mc);
            if (lastInventory != null) countInventory(current, inventory, now);
            lastInventory = inventory;
        }
        if (ticks % 200 == 0 && current != null) dirty = true;
        if (dirty && ticks % 100 == 0) save();
    }


    private static void countInventory(Preset preset, Map<String, Integer> current, long now) {
        Map<String, Long> changes = new HashMap<>();
        Set<String> ids = new HashSet<>(current.keySet());
        ids.addAll(lastInventory.keySet());
        for (String id : ids) {
            if (!tracked(preset, id)) continue;
            long change = current.getOrDefault(id, 0) - lastInventory.getOrDefault(id, 0);
            if (change > 0) changes.put(id, change);
            else if (change < 0) {
                // Maybe a compactor's input: remember it for a minute (Skysoft's ProfitCraftingReconciliation).
                long[] r = removals.computeIfAbsent(id, k -> new long[2]);
                r[0] += -change;
                r[1] = now + COMPACTION_WINDOW_MS;
            }
        }
        if (changes.isEmpty()) return;
        Map<String, Long> counted = new HashMap<>(changes);
        for (Map.Entry<String, Long> e : changes.entrySet()) {
            Conversion conversion = conversions.get(e.getKey());
            if (conversion == null || e.getValue() % conversion.outputCount() != 0) continue;
            long[] r = removals.get(conversion.input());
            if (r == null) continue;
            long required = (long) conversion.inputCount() * e.getValue() / conversion.outputCount();
            long consumed = Math.min(r[0], required);
            r[0] -= consumed;
            if (r[0] <= 0) removals.remove(conversion.input());
            counted.merge(conversion.input(), -consumed, Long::sum);
        }
        if (preset == Preset.FARMING) {
            // Replenish took some of those crops back to replant (Skysoft's ProfitReplenishCosts).
            for (Map.Entry<String, Integer> cost : new ArrayList<>(replenishCosts.entrySet())) {
                String harvest = cost.getKey().equals("SEEDS") ? "WHEAT" : cost.getKey();
                if (changes.getOrDefault(harvest, 0L) <= 0) continue;
                counted.merge(cost.getKey(), (long) -cost.getValue(), Long::sum);
                replenishCosts.remove(cost.getKey());
            }
        }
        for (Map.Entry<String, Long> e : counted.entrySet()) addItem(preset, e.getKey(), e.getValue());
    }

    /**
     * Coins from the scoreboard's purse (Skysoft's coinTrackingTargets): with no menu open, gains between 1 and 100k,
     * for slayers, fishing and the Mythological Ritual, and Bountiful coins while farming.
     */
    private static void trackPurse(Minecraft mc, Preset preset, long now) {
        double purse = purse();
        double previous = lastPurse;
        lastPurse = purse;
        if (purse >= 0) {
            purseHistory.addLast(new double[]{now, purse});
            while (!purseHistory.isEmpty() && now - purseHistory.getFirst()[0] > 15_000L) purseHistory.removeFirst();
        }
        if (preset == null || purse < 0 || previous < 0 || mc.gui.screen() != null) return;
        double gained = purse - previous;
        if (gained <= 1 || gained >= 100_000) return;
        boolean counts = preset.slayer() || preset == Preset.FISHING || preset == Preset.MYTHOLOGICAL_RITUAL;
        if (preset == Preset.FARMING) {
            String modifier = Compat.getCustomData(mc.player.getMainHandItem()).getStringOr("modifier", "");
            counts = Sky2MLocation.inGarden() && now - lastFarmed <= BOUNTIFUL_WINDOW_MS && modifier.equals("bountiful");
        }
        if (!counts) return;
        for (Stats stats : targets(preset)) stats.coins += gained;
        activity(preset);
        dirty = true;
    }

    /**
     * The quest's cost (Skysoft's SlayerQuestCostCapture): what the purse went down by around "SLAYER QUEST STARTED!"
     * if that's the quest's price (or half, with Aatrox's Slashed Pricing), else the price.
     */
    private static void trackQuestCost(long now) {
        if (questStartedAt == 0) return;
        if (questPreset == null) questPreset = slayerQuest();
        if (now - questStartedAt < QUEST_COST_WINDOW_MS && questPreset == null) return;
        Preset preset = questPreset;
        long started = questStartedAt;
        questStartedAt = 0;
        if (preset == null || !enabled(preset) || preset == Preset.VAMPIRE) return;
        long standard = standardCost(preset);
        double highest = -1;
        for (double[] entry : purseHistory) if (entry[0] >= started - 10_000L) highest = Math.max(highest, entry[1]);
        long paid = highest > 0 && lastPurse >= 0 ? Math.round(highest - lastPurse) : -1;
        long cost = standard;
        for (long price : new long[]{standard, standard / 2}) {
            if (paid > 0 && price > 0 && Math.abs(paid - price) <= price * 0.05) {
                cost = price;
                break;
            }
        }
        if (cost <= 0) return;
        for (Stats stats : targets(preset)) stats.costs += cost;
        activity(preset);
        dirty = true;
    }

    private static long standardCost(Preset preset) {
        String tier = "";
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = Pattern.compile("(?:Horror|Broodfather|Brood|Packmaster|Seraph|Demonlord) (I{1,3}|IV|V)\\b").matcher(line);
            if (m.find()) tier = m.group(1);
            if (line.contains("Atoned Horror")) tier = "V";
        }
        boolean blaze = preset == Preset.BLAZE;
        return switch (tier) {
            case "I" -> blaze ? 10_000 : 2_000;
            case "II" -> blaze ? 25_000 : 7_500;
            case "III" -> blaze ? 60_000 : 20_000;
            case "IV" -> blaze ? 150_000 : 50_000;
            case "V" -> 100_000;
            default -> 0;
        };
    }

    private static double purse() {
        String value = Sky2MLocation.scoreboardValue("Purse:");
        if (value == null) value = Sky2MLocation.scoreboardValue("Piggy:");
        if (value == null) return -1;
        Matcher m = NUMBER.matcher(value);
        if (!m.find()) return -1;
        try {
            return Double.parseDouble(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, Integer> inventory(Minecraft mc) {
        Map<String, Integer> counts = new HashMap<>();
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = Sky2MPriceTooltip.marketId(stack);
            if (!id.isEmpty()) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static String normalise(String id) {
        return id == null ? null : id.replace('-', ':');
    }

    /** Crafting recipes with one kind of input between items a preset tracks, from the NEU repo. Blocking. */
    private static void loadConversions() {
        Set<String> all = new HashSet<>();
        for (PresetData data : PRESETS.values()) all.addAll(data.items());
        for (String output : all) {
            try {
                Sky2MCraftHelper.Recipe recipe = Sky2MCraftHelper.recipeOf(output);
                if (recipe == null || !recipe.type().equals("crafting") || recipe.inputs().size() != 1) continue;
                Sky2MCraftHelper.Input input = recipe.inputs().getFirst();
                String inputId = normalise(input.id());
                if (all.contains(inputId)) conversions.put(output, new Conversion(inputId, input.amount(), recipe.outputCount()));
            } catch (Exception ignored) {}
        }
    }

    private static boolean isCrop(Block block) {
        return block == Blocks.WHEAT || block == Blocks.CARROTS || block == Blocks.POTATOES || block == Blocks.NETHER_WART
            || block == Blocks.PUMPKIN || block == Blocks.CARVED_PUMPKIN || block == Blocks.MELON || block == Blocks.COCOA
            || block == Blocks.SUGAR_CANE || block == Blocks.CACTUS || block == Blocks.RED_MUSHROOM
            || block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM_BLOCK || block == Blocks.BROWN_MUSHROOM_BLOCK
            || block == Blocks.SUNFLOWER || block == Blocks.ROSE_BUSH;
    }

    /** What Replenish takes back to replant a crop, or null if it doesn't. */
    private static String replenishCrop(Block block, long gameTime) {
        if (block == Blocks.WHEAT) return "SEEDS";
        if (block == Blocks.CARROTS) return "CARROT_ITEM";
        if (block == Blocks.POTATOES) return "POTATO_ITEM";
        if (block == Blocks.NETHER_WART) return "NETHER_STALK";
        if (block == Blocks.COCOA) return "INK_SACK:3";
        if (block == Blocks.ROSE_BUSH) return "WILD_ROSE";
        if (block == Blocks.SUNFLOWER) return gameTime % 24_000L >= 12_000L ? "MOONFLOWER" : "DOUBLE_PLANT";
        return null;
    }

    // ------------------------------------------------------------------------------------------------ saving

    private static void load(Path configDir) {
        try {
            if (Files.exists(file)) {
                Map<String, Saved> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Saved>>() {}.getType());
                if (loaded != null) {
                    for (Map.Entry<String, Saved> e : loaded.entrySet()) {
                        try {
                            put(Preset.valueOf(e.getKey()), e.getValue());
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                return;
            }
            // The Farming tracker kept its own file before the other presets.
            Path farming = configDir.resolve("sky2m").resolve("farming-profit.json");
            if (Files.exists(farming)) {
                JsonObject old = JsonParser.parseString(Files.readString(farming, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String period : new String[]{"total", "today"}) {
                    if (!old.has(period)) continue;
                    JsonObject stats = old.getAsJsonObject(period);
                    if (stats.has("pests")) stats.add("actions", stats.get("pests"));
                }
                put(Preset.FARMING, GSON.fromJson(old, Saved.class));
                dirty = true;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the profit trackers: " + e.getMessage());
        }
    }

    private static void put(Preset preset, Saved saved) {
        if (saved == null) return;
        if (saved.total == null) saved.total = new Stats();
        if (saved.today == null) saved.today = new Stats();
        for (Stats stats : List.of(saved.total, saved.today)) {
            if (stats.items == null) stats.items = new LinkedHashMap<>();
            if (stats.pestKills == null) stats.pestKills = new LinkedHashMap<>();
        }
        SAVED.put(preset, saved);
    }

    private static void save() {
        dirty = false;
        try {
            Map<String, Saved> out = new LinkedHashMap<>();
            for (Map.Entry<Preset, Saved> e : SAVED.entrySet()) out.put(e.getKey().name(), e.getValue());
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(out), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the profit trackers: " + e.getMessage());
        }
    }

    static int tickCount() {
        return ticks;
    }

    static void markDirty() {
        dirty = true;
    }

    /** For the HUD: whether the inventory screen is a container (the tracker's controls work there). */
    static boolean containerOpen() {
        return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>;
    }

    static void registerScreenHooks() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> ProfitTrackerHud.renderInScreen(graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !ProfitTrackerHud.click(event.x(), event.y()));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> !ProfitTrackerHud.scroll(mouseX, mouseY, vertical));
        });
    }

    /** Placement key of a preset's HUD in /s2 gui. */
    static String hudId(Preset preset) {
        return "profit_tracker_" + preset.name().toLowerCase();
    }

    static Sky2MHuds.Placement placement(Preset preset) {
        return Sky2MHuds.placement(hudId(preset));
    }
}
