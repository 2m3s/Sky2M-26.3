package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.DianaAchievements.Rarity;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Misc > Sky2M Achievements (/s2 achievements): Sky2M's own achievements, from stats Sky2M keeps while you play: dungeon
 * runs and S+ runs (Hypixel's "Team Score:" line), slayer bosses ("SLAYER QUEST COMPLETE!"), deaths, kills, Scathas,
 * carries, M7 dragons, hidden spam, close calls, networth and time played. Stats are always counted (only on your
 * computer, in {@code sky2m/achievements.json}); with the feature off, unlocks happen quietly.
 */
public final class Sky2MAchievements {
    public enum Stat {
        DUNGEON_RUNS("Dungeon runs"), S_PLUS_RUNS("S+ runs"), M7_RUNS("M7 runs"), DUNGEON_DEATHS("Deaths in dungeons"), DEATHS("Deaths"),
        SLAYER_BOSSES("Slayer bosses"), KILLS("Melee kills"), SCATHAS("Scathas"), WORMS("Worms"), SCATHA_PETS("Scatha pets"),
        CARRIES("Carries done"), DRAGONS("M7 dragons killed"), HIDDEN_MESSAGES("Spam hidden"), CLOSE_CALLS("Close calls"),
        NETWORTH("Best networth"), MINUTES_PLAYED("Minutes on SkyBlock"), DAYS_PLAYED("Days played");

        public final String label;

        Stat(String label) {
            this.label = label;
        }
    }

    public record Achievement(String id, String category, String name, String description, Stat stat, long goal, Rarity rarity) {}

    private static final class Saved {
        Map<Stat, Long> stats = new EnumMap<>(Stat.class);
        Set<String> unlocked = new LinkedHashSet<>();
        Set<String> days = new LinkedHashSet<>();
    }

    private static final List<Achievement> ALL = new ArrayList<>();
    private static final Gson GSON = new Gson();
    private static Saved saved = new Saved();
    private static Path file;
    private static boolean dirty, runCounted;
    private static int ticks;

    private Sky2MAchievements() {}

    private static FeatureConfigs.Achievements config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.achievements;
    }

    private static void add(String id, String category, String name, String description, Stat stat, long goal, Rarity rarity) {
        ALL.add(new Achievement(id, category, name, description, stat, goal, rarity));
    }

    static {
        add("runs_1", "Dungeons", "Into the Catacombs", "Finish a dungeon run", Stat.DUNGEON_RUNS, 1, Rarity.COMMON);
        add("runs_100", "Dungeons", "Regular", "Finish 100 dungeon runs", Stat.DUNGEON_RUNS, 100, Rarity.UNCOMMON);
        add("runs_1000", "Dungeons", "Dungeon Dweller", "Finish 1,000 dungeon runs", Stat.DUNGEON_RUNS, 1_000, Rarity.RARE);
        add("runs_5000", "Dungeons", "Lives Down There", "Finish 5,000 dungeon runs", Stat.DUNGEON_RUNS, 5_000, Rarity.EPIC);
        add("runs_10000", "Dungeons", "Catacombs Resident", "Finish 10,000 dungeon runs", Stat.DUNGEON_RUNS, 10_000, Rarity.LEGENDARY);
        add("splus_1", "Dungeons", "S+", "Get S+ on a run", Stat.S_PLUS_RUNS, 1, Rarity.COMMON);
        add("splus_100", "Dungeons", "Perfectionist", "Get S+ on 100 runs", Stat.S_PLUS_RUNS, 100, Rarity.RARE);
        add("splus_1000", "Dungeons", "Flawless", "Get S+ on 1,000 runs", Stat.S_PLUS_RUNS, 1_000, Rarity.EPIC);
        add("m7_1", "Dungeons", "Necron's Bane", "Finish Master Mode Floor 7", Stat.M7_RUNS, 1, Rarity.RARE);
        add("m7_100", "Dungeons", "Wither King Slayer", "Finish 100 M7 runs", Stat.M7_RUNS, 100, Rarity.EPIC);
        add("m7_1000", "Dungeons", "M7 Machine", "Finish 1,000 M7 runs", Stat.M7_RUNS, 1_000, Rarity.MYTHIC);
        add("dragons_100", "Dungeons", "Dragon Tamer", "Kill 100 M7 dragons", Stat.DRAGONS, 100, Rarity.EPIC);
        add("ddeaths_1", "Dungeons", "Ghost", "Die in a dungeon", Stat.DUNGEON_DEATHS, 1, Rarity.COMMON);
        add("ddeaths_100", "Dungeons", "Professional Ghost", "Die 100 times in dungeons", Stat.DUNGEON_DEATHS, 100, Rarity.RARE);

        add("slayer_1", "Slayers", "Slayer", "Finish a slayer quest", Stat.SLAYER_BOSSES, 1, Rarity.COMMON);
        add("slayer_100", "Slayers", "Hunter", "Finish 100 slayer quests", Stat.SLAYER_BOSSES, 100, Rarity.UNCOMMON);
        add("slayer_1000", "Slayers", "Boss Grinder", "Finish 1,000 slayer quests", Stat.SLAYER_BOSSES, 1_000, Rarity.EPIC);
        add("slayer_10000", "Slayers", "Nothing Left To Slay", "Finish 10,000 slayer quests", Stat.SLAYER_BOSSES, 10_000, Rarity.LEGENDARY);

        add("worms_100", "Mining", "Worm Food", "See 100 worms spawn", Stat.WORMS, 100, Rarity.UNCOMMON);
        add("scatha_1", "Mining", "Scatha!", "See a Scatha spawn", Stat.SCATHAS, 1, Rarity.COMMON);
        add("scatha_100", "Mining", "Scatha Farmer", "See 100 Scathas spawn", Stat.SCATHAS, 100, Rarity.RARE);
        add("scatha_1000", "Mining", "Scatha Whisperer", "See 1,000 Scathas spawn", Stat.SCATHAS, 1_000, Rarity.EPIC);
        add("scatha_pet", "Mining", "Pet Drop!", "Get a Scatha pet", Stat.SCATHA_PETS, 1, Rarity.LEGENDARY);

        add("kills_100", "Combat", "Swordsman", "Kill 100 mobs in melee", Stat.KILLS, 100, Rarity.COMMON);
        add("kills_10000", "Combat", "Blademaster", "Kill 10,000 mobs in melee", Stat.KILLS, 10_000, Rarity.RARE);
        add("kills_100000", "Combat", "Massacre", "Kill 100,000 mobs in melee", Stat.KILLS, 100_000, Rarity.LEGENDARY);
        add("deaths_1", "Combat", "Oops", "Die", Stat.DEATHS, 1, Rarity.COMMON);
        add("deaths_500", "Combat", "Respawn Expert", "Die 500 times", Stat.DEATHS, 500, Rarity.RARE);
        add("close_1", "Combat", "Close Call", "Drop below 5% health and live", Stat.CLOSE_CALLS, 1, Rarity.UNCOMMON);
        add("close_100", "Combat", "Cat With Nine Lives", "Have 100 close calls", Stat.CLOSE_CALLS, 100, Rarity.EPIC);

        add("carries_1", "Sky2M", "Helping Hand", "Finish a carry", Stat.CARRIES, 1, Rarity.COMMON);
        add("carries_100", "Sky2M", "Carry Service", "Finish 100 carries", Stat.CARRIES, 100, Rarity.RARE);
        add("carries_1000", "Sky2M", "Professional Carrier", "Finish 1,000 carries", Stat.CARRIES, 1_000, Rarity.LEGENDARY);
        add("spam_1000", "Sky2M", "Peace And Quiet", "Hide 1,000 spam messages", Stat.HIDDEN_MESSAGES, 1_000, Rarity.UNCOMMON);
        add("spam_100000", "Sky2M", "Silence", "Hide 100,000 spam messages", Stat.HIDDEN_MESSAGES, 100_000, Rarity.EPIC);
        add("nw_100m", "Sky2M", "Getting There", "Reach 100M networth", Stat.NETWORTH, 100_000_000L, Rarity.UNCOMMON);
        add("nw_1b", "Sky2M", "Billionaire", "Reach 1B networth", Stat.NETWORTH, 1_000_000_000L, Rarity.EPIC);
        add("nw_10b", "Sky2M", "Market Mover", "Reach 10B networth", Stat.NETWORTH, 10_000_000_000L, Rarity.MYTHIC);
        add("time_60", "Sky2M", "First Hour", "Play SkyBlock with Sky2M for an hour", Stat.MINUTES_PLAYED, 60, Rarity.COMMON);
        add("time_6000", "Sky2M", "No-Lifer", "Play SkyBlock with Sky2M for 100 hours", Stat.MINUTES_PLAYED, 6_000, Rarity.RARE);
        add("time_60000", "Sky2M", "Touch Grass", "Play SkyBlock with Sky2M for 1,000 hours", Stat.MINUTES_PLAYED, 60_000, Rarity.LEGENDARY);
        add("days_7", "Sky2M", "Week One", "Play on 7 different days", Stat.DAYS_PLAYED, 7, Rarity.COMMON);
        add("days_100", "Sky2M", "Dedicated", "Play on 100 different days", Stat.DAYS_PLAYED, 100, Rarity.RARE);
        add("days_365", "Sky2M", "A Year Of Sky2M", "Play on 365 different days", Stat.DAYS_PLAYED, 365, Rarity.LEGENDARY);
    }

    public static List<Achievement> all() {
        return ALL;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("achievements.json");
        load();
        Sky2MLocation.onAreaChange(area -> runCounted = false);
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 1200 != 0) return;
            if (Sky2MLocation.onSkyblock()) {
                add(Stat.MINUTES_PLAYED, 1);
                synchronized (Sky2MAchievements.class) {
                    if (saved.days.add(LocalDate.now().toString())) set(Stat.DAYS_PLAYED, saved.days.size());
                }
            }
            if (dirty) save();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("feats").executes(c -> {
                    open();
                    return 1;
                })));
            }
        });
    }

    private static void onChat(String text) {
        if (text.equals("SLAYER QUEST COMPLETE!")) add(Stat.SLAYER_BOSSES, 1);
        if (!runCounted && Sky2MLocation.inDungeon() && text.startsWith("Team Score:")) {
            runCounted = true;
            add(Stat.DUNGEON_RUNS, 1);
            if (text.contains("(S+)")) add(Stat.S_PLUS_RUNS, 1);
            if (Sky2MLocation.dungeonFloor().equals("M7")) add(Stat.M7_RUNS, 1);
        }
    }

    /** The Open button and /s2 achievements. */
    public static void open() {
        Compat.queueOpenScreen(new Sky2MAchievementsScreen(Sky2MAchievementsScreen.OVERVIEW));
    }

    public static synchronized long get(Stat stat) {
        return saved.stats.getOrDefault(stat, 0L);
    }

    public static boolean unlocked(Achievement a) {
        synchronized (Sky2MAchievements.class) {
            return saved.unlocked.contains(a.id());
        }
    }

    /** Adds to a stat from any feature; unlocks what it reaches. */
    public static void add(Stat stat, long amount) {
        if (amount == 0) return;
        set(stat, get(stat) + amount);
    }

    /** Raises a stat that keeps its best, like networth. */
    public static void max(Stat stat, long value) {
        if (value > get(stat)) set(stat, value);
    }

    private static void set(Stat stat, long value) {
        List<Achievement> fresh = new ArrayList<>();
        synchronized (Sky2MAchievements.class) {
            saved.stats.put(stat, value);
            dirty = true;
            for (Achievement a : ALL) if (a.stat() == stat && value >= a.goal() && saved.unlocked.add(a.id())) fresh.add(a);
        }
        if (fresh.isEmpty()) return;
        save();
        FeatureConfigs.Achievements c = config();
        if (c == null || !c.enabled) return;
        for (Achievement a : fresh) {
            if (c.popup) {
                Sky2MAlerts.title(Component.literal(a.name()).withStyle(a.rarity().colour, ChatFormatting.BOLD),
                    Component.literal("Sky2M Achievement").withStyle(ChatFormatting.GRAY), false);
                Sky2MAlerts.play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.6f);
            }
            if (c.chat) {
                Sky2MAlerts.chat(Component.literal("Achievement unlocked: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(a.name()).withStyle(a.rarity().colour, ChatFormatting.BOLD))
                    .append(Component.literal(" - " + a.description() + " (" + a.rarity().label + ")").withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Saved read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (read == null) return;
            if (read.stats == null) read.stats = new EnumMap<>(Stat.class);
            read.stats.keySet().removeIf(java.util.Objects::isNull);
            if (read.unlocked == null) read.unlocked = new LinkedHashSet<>();
            if (read.days == null) read.days = new LinkedHashSet<>();
            saved = read;
        } catch (Exception ignored) {}
    }

    private static synchronized void save() {
        if (file == null) return;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
