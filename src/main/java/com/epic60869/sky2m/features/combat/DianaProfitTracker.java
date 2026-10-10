package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.slayer.SlayerBossProfit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diana profit tracker, laid out like SBO's Diana loot tracker (every rare drop with its lootshares and rate per mob),
 * counting like SkyHanni's DianaProfitTracker (https://github.com/hannibal002/SkyHanni, LGPL-2.1): what
 * each Diana drop was worth (SkyHanni-REPO's constants/DianaDrops.json list, MIT), the coins you dug out, how many
 * burrows you dug, the total profit and the time you spent, for this session, this mayor term (Diana's season) or all
 * time. Drops are counted when they come into your inventory (not from a chest or menu) or your sacks.
 */
public final class DianaProfitTracker {
    /** SkyHanni-REPO's Diana drops. */
    private static final List<String> DROPS = List.of("ANCIENT_CLAW", "ANTIQUE_REMEDIES", "CROCHET_TIGER_PLUSHIE",
        "CROWN_OF_GREED", "DAEDALUS_STICK", "DWARF_TURTLE_SHELMET", "ENCHANTED_ANCIENT_CLAW", "ENCHANTED_GOLD",
        "ENCHANTED_IRON", "GRIFFIN_FEATHER", "MINOS_RELIC", "ULTIMATE_CHIMERA;1", "WASHED_UP_SOUVENIR",
        "HILT_OF_REVELATIONS", "CRETAN_URN", "MANTI_CORE", "SHIMMERING_WOOL", "BRAIN_FOOD", "FABLED_STINGER",
        "DYE_MYTHOLOGICAL", "MYTHOS_FRAGMENT", "ATTRIBUTE_SHARD_BIG_GAME_HUNTER;1", "ATTRIBUTE_SHARD_KING_OF_GREED;1",
        "ATTRIBUTE_SHARD_MYTHOLOGICAL_FORTUNE;1", "ATTRIBUTE_SHARD_MYTHOLOGICAL_RESISTANCE;1",
        "ATTRIBUTE_SHARD_TREE_LURKER;1", "BRAIDED_GRIFFIN_FEATHER", "FATEFUL_STINGER", "MYTH_THE_FISH");
    /** Every id a listed drop can have here (NEU's "ULTIMATE_CHIMERA;1" is the book ENCHANTMENT_ULTIMATE_CHIMERA_1). */
    private static final Set<String> DROP_IDS = new HashSet<>();

    static {
        for (String drop : DROPS) {
            int semi = drop.indexOf(';');
            if (semi < 0) {
                DROP_IDS.add(drop);
                continue;
            }
            String base = drop.substring(0, semi), level = drop.substring(semi + 1);
            DROP_IDS.addAll(List.of(drop, base, base + "_" + level, "ENCHANTMENT_" + base + "_" + level));
        }
    }

    private static final Pattern DUG_BURROW = Pattern.compile("^(?:You dug out a Griffin Burrow!|You finished the Griffin burrow chain!).*");
    private static final Pattern DUG_COINS = Pattern.compile("^Wow! You dug out ([\\d,.]+) coins!");
    /** Idle for longer than this and the clock stops. */
    private static final long ACTIVE_WINDOW_MS = 90_000L;

    public enum Period {
        SESSION("Session"), SEASON("Season"), ALL_TIME("All Time");

        private final String label;

        Period(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final class Data {
        @Expose Map<String, Long> items = new LinkedHashMap<>();
        @Expose long coins;
        @Expose long burrowsDug;
        @Expose long activeMs;
    }

    static final class Saved {
        @Expose Data allTime = new Data();
        /** Election year -> that mayor term's data. */
        @Expose Map<String, Data> seasons = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
    private static Path file;
    private static Saved saved = new Saved();
    private static Data session = new Data();
    private static Map<String, Integer> lastInventory;
    private static long lastActivity;
    private static long lastTick;
    private static boolean dirty;
    private static int ticks;

    private DianaProfitTracker() {}

    private static FeatureConfigs.DianaProfitTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mayors.diana.profitTracker;
    }

    private static boolean enabled() {
        FeatureConfigs.DianaProfitTracker c = config();
        return c != null && c.enabled;
    }

    /**
     * Whether to count: the HUD is on, or something else reads the totals (the mob tracker's mobs per hour, the Diana
     * party commands' !profit, !playtime and !burrows, and the achievements).
     */
    private static boolean counting() {
        if (enabled()) return true;
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null) return false;
        FeatureConfigs.Diana d = c.mayors.diana;
        return (d.mobTracker != null && d.mobTracker.enabled) || (d.achievements != null && d.achievements.enabled)
            || (c.misc.partyCommands.enabled && c.misc.partyCommands.diana);
    }

    /** One period's totals, for the Diana party commands and the mob tracker. */
    public record Totals(long burrows, long activeMs, long coins, double profit) {}

    public static Totals totals(Period period) {
        Data data = switch (period) {
            case SESSION -> session;
            case SEASON -> saved.seasons.computeIfAbsent(String.valueOf(electionYear()), k -> new Data());
            case ALL_TIME -> saved.allTime;
        };
        double profit = data.coins;
        for (Map.Entry<String, Long> e : data.items.entrySet()) profit += ItemPriceResolver.value(e.getKey()) * e.getValue();
        return new Totals(data.burrowsDug, data.activeMs, data.coins, profit);
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("diana-profit.json");
        load();
        ClientTickEvents.END_CLIENT_TICK.register(DianaProfitTracker::tick);
        Sky2MChat.onChat(message -> onChat(message.component(), message.text()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("dianatracker")
                    .then(ClientCommands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("session", "season", "alltime", "reset"), b))
                        .executes(c -> command(StringArgumentType.getString(c, "what"))))));
            }
        });
        Sky2MHuds.register("diana_profit", "Diana Profit Tracker",
            () -> enabled() && Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("Hub")
                && System.currentTimeMillis() - lastActivity < 10 * 60_000L,
            DianaProfitTracker::lines,
            List.of(Component.literal("Diana Loot Tracker").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                    .append(Component.literal(" (Session)").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false))),
                Component.literal("Chimera: ").withStyle(ChatFormatting.LIGHT_PURPLE).append(Component.literal("2").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" (1 LS)").withStyle(ChatFormatting.GRAY)).append(Component.literal(" [8.33%]").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(" 120.00M").withStyle(ChatFormatting.GOLD)),
                Component.literal("Daedalus Stick: ").withStyle(ChatFormatting.GOLD).append(Component.literal("1").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" [0.31%]").withStyle(ChatFormatting.DARK_GRAY)).append(Component.literal(" 25.0M").withStyle(ChatFormatting.GOLD)),
                Component.literal("Griffin Feather: ").withStyle(ChatFormatting.GOLD).append(Component.literal("14").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" 1.2M").withStyle(ChatFormatting.GOLD)),
                Component.literal("Coins: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("3.40M").withStyle(ChatFormatting.GOLD)),
                Component.literal("Total Burrows: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("512").withStyle(ChatFormatting.AQUA)),
                Component.literal("Total Profit: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("149.60M").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY)).append(Component.literal("74.80M/h").withStyle(ChatFormatting.GOLD)),
                Component.literal("Playtime: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("2h 0m").withStyle(ChatFormatting.AQUA))),
            8, 120);
    }

    private static int command(String what) {
        Minecraft.getInstance().execute(() -> {
            FeatureConfigs.DianaProfitTracker c = config();
            if (c == null) return;
            switch (what.toLowerCase(Locale.ROOT)) {
                case "session" -> c.period = Period.SESSION;
                case "season", "mayor" -> c.period = Period.SEASON;
                case "alltime", "all" -> c.period = Period.ALL_TIME;
                case "reset" -> {
                    // Resets what's shown.
                    Period period = c.period == null ? Period.SESSION : c.period;
                    switch (period) {
                        case SESSION -> session = new Data();
                        case SEASON -> saved.seasons.put(String.valueOf(electionYear()), new Data());
                        case ALL_TIME -> saved.allTime = new Data();
                    }
                    dirty = true;
                    Sky2MAlerts.chat(Component.literal("Diana profit tracker (" + period + ") reset.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                default -> {
                    Sky2MAlerts.chat(Component.literal("Use /s2 dianatracker session, season, alltime or reset.").withStyle(ChatFormatting.RED));
                    return;
                }
            }
            Sky2MAlerts.chat(Component.literal("Diana profit tracker shows: " + c.period).withStyle(ChatFormatting.YELLOW));
        });
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ SkyBlock time

    /**
     * The year of the current mayor's term. A SkyBlock year is 124 hours from 11 June 2019 15:55 UTC, and the new
     * mayor starts on Late Spring 27th, so the term before that belongs to last year's election (as SkyHanni's
     * getElectionYear).
     */
    static long electionYear() {
        long sinceEpoch = System.currentTimeMillis() - 1_560_275_700_000L;
        long yearMs = 446_400_000L;
        long year = sinceEpoch / yearMs + 1;
        long intoYear = sinceEpoch % yearMs;
        // Late Spring (the 3rd month) 27th: 2 months of 31 days and 26 days, 20 minutes a day.
        long electionAt = (2 * 31 + 26) * 1_200_000L;
        return intoYear < electionAt ? year - 1 : year;
    }

    // ------------------------------------------------------------------------------------------------ counting

    private static List<Data> targets() {
        return List.of(session, saved.allTime, saved.seasons.computeIfAbsent(String.valueOf(electionYear()), k -> new Data()));
    }

    private static void add(String id, long amount) {
        for (Data d : targets()) d.items.merge(id, amount, Long::sum);
        lastActivity = System.currentTimeMillis();
        dirty = true;
    }

    private static boolean doingDiana() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("Hub") && System.currentTimeMillis() - lastActivity < ACTIVE_WINDOW_MS;
    }

    private static void onChat(Component component, String text) {
        if (!counting() || !Sky2MLocation.onSkyblock() || !Sky2MLocation.areaIs("Hub")) return;
        String t = text.trim();
        if (DUG_BURROW.matcher(t).matches()) {
            for (Data d : targets()) d.burrowsDug++;
            lastActivity = System.currentTimeMillis();
            dirty = true;
            return;
        }
        Matcher coins = DUG_COINS.matcher(t);
        if (coins.find()) {
            long amount = (long) Double.parseDouble(coins.group(1).replace(",", ""));
            for (Data d : targets()) d.coins += amount;
            lastActivity = System.currentTimeMillis();
            dirty = true;
            return;
        }
        if (t.startsWith("[Sacks]") && doingDiana()) {
            for (Map.Entry<String, Long> e : SlayerBossProfit.sackGains(component).entrySet()) {
                if (e.getValue() <= 0) continue;
                String id = RepoItems.idByName(e.getKey());
                if (id != null && DROP_IDS.contains(id)) add(id, e.getValue());
            }
        }
    }

    private static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        long delta = lastTick == 0 ? 0 : Math.min(1_000L, now - lastTick);
        lastTick = now;
        if (!counting() || mc.player == null) {
            lastInventory = null;
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!held.isEmpty() && held.getHoverName().getString().contains("Spade") && Sky2MLocation.areaIs("Hub")) lastActivity = now;
        if (doingDiana()) {
            for (Data d : targets()) d.activeMs += delta;
            if (++ticks % 200 == 0) dirty = true;
        }
        // Items that come into your inventory while no menu is open (moving them from chests doesn't count).
        if (mc.gui.screen() != null) {
            lastInventory = null;
        } else {
            Map<String, Integer> now_ = inventory(mc);
            if (lastInventory != null && doingDiana()) {
                for (Map.Entry<String, Integer> e : now_.entrySet()) {
                    int gained = e.getValue() - lastInventory.getOrDefault(e.getKey(), 0);
                    if (gained > 0 && DROP_IDS.contains(e.getKey())) add(e.getKey(), gained);
                }
            }
            lastInventory = now_;
        }
        if (dirty && ticks % 100 == 0) save();
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

    // ------------------------------------------------------------------------------------------------ HUD

    private static List<Component> lines() {
        FeatureConfigs.DianaProfitTracker c = config();
        Period period = c == null || c.period == null ? Period.SESSION : c.period;
        Data data = switch (period) {
            case SESSION -> session;
            case SEASON -> saved.seasons.computeIfAbsent(String.valueOf(electionYear()), k -> new Data());
            case ALL_TIME -> saved.allTime;
        };
        // The mob tracker's counts for the same period: lootshares and drop rates per mob.
        DianaTracker.Data mobs = DianaTracker.data(period);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Diana Loot Tracker").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(" (" + period + ")").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false))));

        // Like SBO: every rare drop always listed, then the rest you've had, each with its count, lootshares,
        // its rate per mob it drops from and its value.
        double total = data.coins;
        Set<String> listed = new HashSet<>();
        for (DianaTracker.Drop drop : DianaTracker.Drop.values()) {
            listed.add(drop.itemId);
            long count = Math.max(data.items.getOrDefault(drop.itemId, 0L), mobs.drop(drop) + mobs.dropLs(drop));
            if (count == 0 && !drop.big) continue;
            double value = ItemPriceResolver.value(drop.itemId) * count;
            total += value;
            MutableComponent line = Component.literal(drop.label + ": ").withStyle(drop.colour)
                .append(Component.literal(String.format(Locale.US, "%,d", count)).withStyle(ChatFormatting.AQUA));
            if (drop.lootshare && mobs.dropLs(drop) > 0) {
                line.append(Component.literal(" (" + mobs.dropLs(drop) + " LS)").withStyle(ChatFormatting.GRAY));
            }
            if (drop.from != null && mobs.mob(drop.from) > 0) {
                double rate = 100.0 * mobs.drop(drop) / mobs.mob(drop.from);
                line.append(Component.literal(String.format(Locale.US, " [%.2f%%]", rate)).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (value > 0) line.append(Component.literal(" " + CombatFeatures.formatCoins(value)).withStyle(ChatFormatting.GOLD));
            lines.add(line);
        }
        List<Map.Entry<String, Long>> others = new ArrayList<>();
        for (Map.Entry<String, Long> e : data.items.entrySet()) if (!listed.contains(e.getKey()) && e.getValue() > 0) others.add(e);
        others.sort((a, b) -> Double.compare(ItemPriceResolver.value(b.getKey()) * b.getValue(), ItemPriceResolver.value(a.getKey()) * a.getValue()));
        for (Map.Entry<String, Long> e : others) {
            double value = ItemPriceResolver.value(e.getKey()) * e.getValue();
            total += value;
            String name = RepoItems.displayName(e.getKey());
            MutableComponent line = Component.literal((name == null ? e.getKey() : ChatFormatting.stripFormatting(name)) + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(String.format(Locale.US, "%,d", e.getValue())).withStyle(ChatFormatting.AQUA));
            if (value > 0) line.append(Component.literal(" " + CombatFeatures.formatCoins(value)).withStyle(ChatFormatting.GOLD));
            lines.add(line);
        }

        lines.add(Component.literal("Coins: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(CombatFeatures.formatCoins(data.coins)).withStyle(ChatFormatting.GOLD)));
        lines.add(Component.literal("Total Burrows: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(String.format(Locale.US, "%,d", data.burrowsDug)).withStyle(ChatFormatting.AQUA)));
        if (mobs.totalMobs() > 0) {
            lines.add(Component.literal("Total Mobs: ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(String.format(Locale.US, "%,d", mobs.totalMobs())).withStyle(ChatFormatting.AQUA)));
        }
        MutableComponent profit = Component.literal("Total Profit: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(CombatFeatures.formatCoins(total)).withStyle(ChatFormatting.GOLD));
        if (data.activeMs >= 60_000L) {
            profit.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(CombatFeatures.formatCoins(total / (data.activeMs / 3_600_000d)) + "/h").withStyle(ChatFormatting.GOLD));
        }
        lines.add(profit);
        lines.add(Component.literal("Playtime: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(duration(data.activeMs)).withStyle(ChatFormatting.AQUA)));
        return lines;
    }

    static String duration(long ms) {
        long minutes = ms / 60_000L;
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + minutes % 60 + "m" : minutes + "m " + (ms / 1000) % 60 + "s";
    }

    // ------------------------------------------------------------------------------------------------ saving

    private static void load() {
        try {
            if (Files.exists(file)) {
                Saved loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
                if (loaded != null) {
                    if (loaded.allTime == null) loaded.allTime = new Data();
                    if (loaded.seasons == null) loaded.seasons = new LinkedHashMap<>();
                    saved = loaded;
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the Diana profit tracker: " + e.getMessage());
        }
    }

    private static void save() {
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the Diana profit tracker: " + e.getMessage());
        }
    }
}
