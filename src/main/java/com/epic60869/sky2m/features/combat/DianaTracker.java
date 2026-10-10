package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.misc.PartyCommands;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
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
 * Diana mob and drop tracker. The mob side is SkyHanni's MythologicalCreatureTracker (https://github.com/hannibal002/SkyHanni,
 * LGPL-2.1): how many of each mythological creature you dug up, their share of the total, how many creatures since
 * each rare one, and that count added to Hypixel's "You dug out" line. The drop side is SBO's DianaTracker
 * (https://github.com/SkyblockOverhaul/SBO, Apache-2.0): the rare drops from "RARE DROP!" (split into your own and
 * lootshared ones), the rare mobs you lootshared, how many mobs it took to get each one, back-to-back drops, your best
 * Magic Find per drop, and the RNG drop announcement in chat, as a title and in party chat.
 *
 * <p>Everything is counted for this session, this mayor term (Diana's season) and all time, like the profit tracker.
 * The Diana party commands ({@link DianaPartyCommands}) and the achievements ({@link DianaAchievements}) read it.
 */
public final class DianaTracker {
    /** Mythological creatures, as SkyHanni-REPO's events/Diana.json lists them. */
    public enum Mob {
        GAIA_CONSTRUCT("Gaia Construct", false),
        MINOTAUR("Minotaur", false),
        MINOS_CHAMPION("Minos Champion", false),
        SIAMESE_LYNXES("Siamese Lynxes", false),
        MINOS_HUNTER("Minos Hunter", false),
        CRETAN_BULL("Cretan Bull", false),
        HARPY("Harpy", false),
        STRANDED_NYMPH("Stranded Nymph", false),
        SPHINX("Sphinx", true),
        MINOS_INQUISITOR("Minos Inquisitor", true),
        KING_MINOS("King Minos", true),
        MANTICORE("Manticore", true);

        public final String label;
        public final boolean rare;

        Mob(String label, boolean rare) {
            this.label = label;
            this.rare = rare;
        }

        public ChatFormatting colour() {
            return rare ? ChatFormatting.RED : ChatFormatting.DARK_GREEN;
        }

        static Mob fromLabel(String text) {
            String t = text.trim();
            for (Mob m : values()) if (m.label.equalsIgnoreCase(t)) return m;
            return null;
        }

        /** The rare mob a nametag or kill message is about ("Exalted Minos Inquisitor" is an Inquisitor). */
        public static Mob rareIn(String text) {
            for (Mob m : values()) if (m.rare && text.contains(m.label)) return m;
            return null;
        }
    }

    /**
     * Diana drops. {@code from} is the mob that drops it, so SBO's "Inquisitors since Chimera" counts can be kept;
     * {@code lootshare} drops are split into your own and lootshared ones; {@code big} ones are announced.
     */
    public enum Drop {
        CHIMERA("Chimera", "ENCHANTMENT_ULTIMATE_CHIMERA_1", Mob.MINOS_INQUISITOR, true, true, ChatFormatting.LIGHT_PURPLE),
        SHIMMERING_WOOL("Shimmering Wool", "SHIMMERING_WOOL", Mob.KING_MINOS, true, true, ChatFormatting.RED),
        MANTI_CORE("Manti-core", "MANTI_CORE", Mob.MANTICORE, true, true, ChatFormatting.RED),
        FATEFUL_STINGER("Fateful Stinger", "FATEFUL_STINGER", Mob.MANTICORE, true, true, ChatFormatting.LIGHT_PURPLE),
        BRAIN_FOOD("Brain Food", "BRAIN_FOOD", Mob.SPHINX, true, true, ChatFormatting.DARK_PURPLE),
        DAEDALUS_STICK("Daedalus Stick", "DAEDALUS_STICK", Mob.MINOTAUR, false, true, ChatFormatting.GOLD),
        MINOS_RELIC("Minos Relic", "MINOS_RELIC", Mob.MINOS_CHAMPION, false, true, ChatFormatting.DARK_PURPLE),
        CROWN_OF_GREED("Crown of Greed", "CROWN_OF_GREED", Mob.KING_MINOS, false, true, ChatFormatting.GOLD),
        MYTHOLOGICAL_DYE("Mythological Dye", "DYE_MYTHOLOGICAL", null, false, true, ChatFormatting.RED),
        MYTH_THE_FISH("Myth the Fish", "MYTH_THE_FISH", null, false, true, ChatFormatting.RED),
        // Before Griffin Feather, which is part of its name.
        BRAIDED_GRIFFIN_FEATHER("Braided Griffin Feather", "BRAIDED_GRIFFIN_FEATHER", null, false, true, ChatFormatting.DARK_PURPLE),
        HILT_OF_REVELATIONS("Hilt of Revelations", "HILT_OF_REVELATIONS", Mob.MINOS_HUNTER, false, false, ChatFormatting.BLUE),
        CRETAN_URN("Cretan Urn", "CRETAN_URN", Mob.CRETAN_BULL, false, false, ChatFormatting.BLUE),
        WASHED_UP_SOUVENIR("Washed-up Souvenir", "WASHED_UP_SOUVENIR", null, false, false, ChatFormatting.GOLD),
        DWARF_TURTLE_SHELMET("Dwarf Turtle Shelmet", "DWARF_TURTLE_SHELMET", null, false, false, ChatFormatting.BLUE),
        CROCHET_TIGER_PLUSHIE("Crochet Tiger Plushie", "CROCHET_TIGER_PLUSHIE", null, false, false, ChatFormatting.DARK_PURPLE),
        ANTIQUE_REMEDIES("Antique Remedies", "ANTIQUE_REMEDIES", null, false, false, ChatFormatting.DARK_PURPLE),
        GRIFFIN_FEATHER("Griffin Feather", "GRIFFIN_FEATHER", null, false, false, ChatFormatting.GOLD),
        MYTHOS_FRAGMENT("Mythos Fragment", "MYTHOS_FRAGMENT", null, false, false, ChatFormatting.GOLD);

        public final String label;
        public final String itemId;
        public final Mob from;
        public final boolean lootshare;
        public final boolean big;
        public final ChatFormatting colour;

        Drop(String label, String itemId, Mob from, boolean lootshare, boolean big, ChatFormatting colour) {
            this.label = label;
            this.itemId = itemId;
            this.from = from;
            this.lootshare = lootshare;
            this.big = big;
            this.colour = colour;
        }

        public String lsKey() {
            return name() + "_LS";
        }

        static Drop fromDropText(String text) {
            for (Drop d : values()) if (text.contains(d.label)) return d;
            return null;
        }
    }

    /** Charm and lootshare shards SBO counts for !kingshard, !sphinxshard and so on. */
    static final List<String> SHARDS = List.of("King Minos", "Sphinx", "Minotaur", "Cretan Bull", "Harpy");

    // ------------------------------------------------------------------------------------------------ patterns

    /** SkyHanni's genericMythologicalSpawnPattern, without the colour codes. */
    private static final Pattern DUG_MOB = Pattern.compile("^(?:Oh|Uh oh|Yikes|Oi|Good Grief|Danger|Woah)! You dug out (?:an? )?(?<mob>[A-Za-z ]+)!");
    private static final Pattern RARE_DROP = Pattern.compile("^RARE DROP! (?<drop>.+)$");
    private static final Pattern DUG_TREASURE = Pattern.compile("^RARE DROP! You dug out an? (?<drop>.+?)!$");
    private static final Pattern MAGIC_FIND = Pattern.compile("\\(\\+(?<mf>\\d+) ✯ Magic Find\\)");
    private static final Pattern DYE = Pattern.compile("^WOW! (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w+) found an? Mythological Dye");
    private static final Pattern CHARM = Pattern.compile("^CHARM! You charmed the (?<mob>.+?) and received (?<n>\\d+) (?<shard>.+?) Shards?!$");
    private static final Pattern LS_SHARDS = Pattern.compile("^LOOT SHARE You received (?<n>\\d+) (?<shard>.+?) Shards? for assisting .+!$");
    private static final Pattern PHOENIX = Pattern.compile("^(?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w+) found a Phoenix pet!");
    private static final Pattern COCOON = Pattern.compile("^CAUGHT! You cocooned an? (?<mob>[^!]+)!$");
    private static final List<String> PREFIXES = List.of("Empyrean", "Exalted", "Runic", "Venerable", "Stalwart", "Blessed");

    /** Hypixel sends LOOT SHARE just before the drops and around the time the mob dies. */
    private static final long LOOTSHARE_WINDOW_MS = 2_500L;
    private static final long TRACK_COOLDOWN_MS = 500L;

    // ------------------------------------------------------------------------------------------------ data

    public static final class Data {
        /** Mob -> dug up, and Mob_LS -> lootshared. */
        @Expose public Map<String, Long> mobs = new LinkedHashMap<>();
        /** SkyHanni's "creatures since": Mob -> creatures dug up since the last one. */
        @Expose public Map<String, Long> since = new LinkedHashMap<>();
        /** Drop -> your own, Drop_LS -> lootshared, and "<Mob> Shard" -> shards. */
        @Expose public Map<String, Long> drops = new LinkedHashMap<>();

        public long mob(Mob m) { return mobs.getOrDefault(m.name(), 0L); }
        public long mobLs(Mob m) { return mobs.getOrDefault(m.name() + "_LS", 0L); }
        public long drop(Drop d) { return drops.getOrDefault(d.name(), 0L); }
        public long dropLs(Drop d) { return drops.getOrDefault(d.lsKey(), 0L); }
        public long shards(String mob) { return drops.getOrDefault(mob + " Shard", 0L); }

        public long totalMobs() {
            long total = 0;
            for (Mob m : Mob.values()) total += mob(m);
            return total;
        }

        void add(Map<String, Long> map, String key, long n) {
            map.merge(key, n, Long::sum);
        }
    }

    /** SBO's since counters and back-to-back flags, kept across seasons. */
    public static final class Streaks {
        /** Drop (or Drop_LS) -> mobs of its kind since you last got it. */
        @Expose public Map<String, Long> sinceDrop = new LinkedHashMap<>();
        /** Mob, Drop or Drop_LS whose last one was back-to-back. */
        @Expose public Set<String> b2b = new HashSet<>();
        /** Drop -> the highest Magic Find you got it with. */
        @Expose public Map<String, Integer> bestMagicFind = new LinkedHashMap<>();
        /** Mob -> Diana time (all-time profit tracker clock) when you last dug one up. */
        @Expose public Map<String, Long> lastRareAt = new LinkedHashMap<>();

        public long since(String key) {
            return sinceDrop.getOrDefault(key, 0L);
        }
    }

    static final class Saved {
        @Expose Data allTime = new Data();
        @Expose Map<String, Data> seasons = new LinkedHashMap<>();
        @Expose Streaks streaks = new Streaks();
    }

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
    private static Path file;
    private static Saved saved = new Saved();
    private static Data session = new Data();
    private static boolean dirty;
    private static int ticks;

    private static long lastLootShare;
    private static long lastActivity;
    private static long lastDianaMobDeath;
    private static final Map<String, Long> cooldowns = new HashMap<>();
    /** Rare mobs that died near you and weren't counted as lootshared yet: mob -> time of death. */
    private static final Map<Mob, Long> recentRareDeaths = new HashMap<>();
    /** The last creature you dug up, so a cocooned one of yours isn't mistaken for someone else's. */
    private static Mob lastSpawned;
    private static long lastSpawnedAt;
    /** SkyHanni: the since count to add to the "You dug out" line that's being shown. */
    private static Long pendingSince;
    private static String pendingSinceMob;
    private static Map<String, Integer> lastInventory;

    private DianaTracker() {}

    // ------------------------------------------------------------------------------------------------ config

    static FeatureConfigs.Diana diana() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mayors.diana;
    }

    private static FeatureConfigs.DianaMobTracker mobConfig() {
        FeatureConfigs.Diana d = diana();
        return d == null ? null : d.mobTracker;
    }

    private static FeatureConfigs.DianaDrops dropConfig() {
        FeatureConfigs.Diana d = diana();
        return d == null ? null : d.drops;
    }

    static boolean inHub() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("Hub");
    }

    // ------------------------------------------------------------------------------------------------ init

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("diana-tracker.json");
        load();
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientReceiveMessageEvents.MODIFY_GAME.register((component, overlay) -> overlay ? component : addSince(component));
        ClientTickEvents.END_CLIENT_TICK.register(DianaTracker::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("mobtracker")
                    .then(ClientCommands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("session", "season", "alltime", "reset"), b))
                        .executes(c -> command(StringArgumentType.getString(c, "what"))))));
            }
        });
        Sky2MHuds.register("diana_mobs", "Mythological Creature Tracker",
            () -> {
                FeatureConfigs.DianaMobTracker c = mobConfig();
                return c != null && c.enabled && inHub() && (holdingSpade() || System.currentTimeMillis() - lastActivity < 10 * 60_000L);
            },
            DianaTracker::hudLines,
            List.of(Component.literal("Mythological Creature Tracker (Session)").withStyle(ChatFormatting.GRAY),
                line(" - ", 142, Mob.GAIA_CONSTRUCT, "31.4%"),
                line(" - ", 3, Mob.MINOS_INQUISITOR, "0.7%"),
                Component.literal("Total Mythological Creatures: ").withStyle(ChatFormatting.GRAY).append(Component.literal("452").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Creatures since:").withStyle(ChatFormatting.GRAY),
                Component.literal(" - ").withStyle(ChatFormatting.GRAY).append(Component.literal("Minos Inquisitor").withStyle(ChatFormatting.RED))
                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(Component.literal("87").withStyle(ChatFormatting.YELLOW))),
            8, 200);
        Sky2MHuds.setting("diana_mobs", () -> {
            FeatureConfigs.DianaMobTracker c = mobConfig();
            return c != null && c.enabled;
        });
    }

    private static int command(String what) {
        Minecraft.getInstance().execute(() -> {
            FeatureConfigs.DianaMobTracker c = mobConfig();
            if (c == null) return;
            switch (what.toLowerCase(Locale.ROOT)) {
                case "session" -> c.period = DianaProfitTracker.Period.SESSION;
                case "season", "mayor" -> c.period = DianaProfitTracker.Period.SEASON;
                case "alltime", "all" -> c.period = DianaProfitTracker.Period.ALL_TIME;
                case "reset" -> {
                    DianaProfitTracker.Period period = c.period == null ? DianaProfitTracker.Period.SESSION : c.period;
                    switch (period) {
                        case SESSION -> session = new Data();
                        case SEASON -> saved.seasons.put(String.valueOf(DianaProfitTracker.electionYear()), new Data());
                        case ALL_TIME -> saved.allTime = new Data();
                    }
                    dirty = true;
                    Sky2MAlerts.chat(Component.literal("Mythological creature tracker (" + period + ") reset.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                default -> {
                    Sky2MAlerts.chat(Component.literal("Use /s2 mobtracker session, season, alltime or reset.").withStyle(ChatFormatting.RED));
                    return;
                }
            }
            Sky2MAlerts.chat(Component.literal("Mythological creature tracker shows: " + c.period).withStyle(ChatFormatting.YELLOW));
        });
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ data access

    public static Data data(DianaProfitTracker.Period period) {
        return switch (period) {
            case SESSION -> session;
            case SEASON -> season();
            case ALL_TIME -> saved.allTime;
        };
    }

    public static Data season() {
        return saved.seasons.computeIfAbsent(String.valueOf(DianaProfitTracker.electionYear()), k -> new Data());
    }

    /** Every season you have data for, oldest first (for the achievements' backtrack). */
    static List<Data> allSeasons() {
        return new ArrayList<>(saved.seasons.values());
    }

    public static Streaks streaks() {
        return saved.streaks;
    }

    private static List<Data> targets() {
        return List.of(session, saved.allTime, season());
    }

    private static boolean onCooldown(String key) {
        return onCooldown(key, TRACK_COOLDOWN_MS);
    }

    private static boolean onCooldown(String key, long ms) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(key);
        if (until != null && now < until) return true;
        cooldowns.put(key, now + ms);
        return false;
    }

    static boolean gotLootShareRecently() {
        return System.currentTimeMillis() - lastLootShare <= LOOTSHARE_WINDOW_MS;
    }

    /** A mythological creature died near you in the last few seconds. */
    static boolean dianaMobDiedRecently(long ms) {
        return System.currentTimeMillis() - lastDianaMobDeath <= ms;
    }

    private static String me() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? mc.getUser().getName() : mc.player.getGameProfile().name();
    }

    private static boolean holdingSpade() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        ItemStack held = mc.player.getMainHandItem();
        return !held.isEmpty() && held.getHoverName().getString().contains("Spade");
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        if (!inHub()) return;
        if (text.startsWith("LOOT SHARE You received loot for assisting")) {
            lastLootShare = System.currentTimeMillis();
            // The rare mob's health can reach 0 a moment before the message.
            recentRareDeaths.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > LOOTSHARE_WINDOW_MS);
            for (Mob mob : List.copyOf(recentRareDeaths.keySet())) {
                recentRareDeaths.remove(mob);
                onLootshareMob(mob);
            }
            return;
        }
        if (text.startsWith("You dug out a Griffin Burrow!") || text.startsWith("You finished the Griffin burrow chain!")) {
            lastActivity = System.currentTimeMillis();
            return;
        }
        Matcher m;
        if ((m = DUG_MOB.matcher(text)).find()) {
            Mob mob = Mob.fromLabel(m.group("mob"));
            if (mob != null && !onCooldown("mob:" + mob)) onMobSpawn(mob);
            return;
        }
        if ((m = COCOON.matcher(text)).matches()) {
            // SBO: a cocooned creature you dug up yourself hatches again, so it counts like a new one.
            String name = m.group("mob").trim();
            for (String prefix : PREFIXES) if (name.startsWith(prefix + " ")) name = name.substring(prefix.length() + 1);
            Mob mob = Mob.fromLabel(name);
            if (mob == null) mob = Mob.rareIn(name);
            if (mob != null && mob == lastSpawned && System.currentTimeMillis() - lastSpawnedAt < 5 * 60_000L) onMobSpawn(mob);
            if (mob != null && mob.rare) {
                FeatureConfigs.DianaDrops c = dropConfig();
                if (c != null && c.enabled && c.announceCocoon) PartyCommands.partyChat("Cocooned a " + mob.label + "!");
            }
            return;
        }
        if ((m = DUG_TREASURE.matcher(text)).matches()) {
            Drop drop = Drop.fromDropText(m.group("drop"));
            if (drop != null) onDrop(drop, 0, false);
            lastActivity = System.currentTimeMillis();
            return;
        }
        if ((m = RARE_DROP.matcher(text)).matches()) {
            String dropText = m.group("drop");
            Drop drop = Drop.fromDropText(dropText);
            // "Enchanted Book (Chimera 1)"; other books aren't Diana drops.
            if (drop == null || (dropText.startsWith("Enchanted Book") && drop != Drop.CHIMERA)) return;
            Matcher mf = MAGIC_FIND.matcher(dropText);
            int magicFind = mf.find() ? Integer.parseInt(mf.group("mf")) : 0;
            onDrop(drop, magicFind, gotLootShareRecently());
            return;
        }
        if ((m = DYE.matcher(text)).find()) {
            if (m.group("name").equalsIgnoreCase(me())) onDrop(Drop.MYTHOLOGICAL_DYE, 0, false);
            return;
        }
        if (text.contains("You just dug out") && text.contains("Myth the Fish")) {
            onDrop(Drop.MYTH_THE_FISH, 0, false);
            DianaAchievements.unlock(119);
            return;
        }
        if ((m = CHARM.matcher(text)).matches()) {
            addShards(m.group("shard"), Integer.parseInt(m.group("n")));
            return;
        }
        if ((m = LS_SHARDS.matcher(text)).matches()) {
            addShards(m.group("shard"), Integer.parseInt(m.group("n")));
            return;
        }
        if ((m = PHOENIX.matcher(text)).find() && m.group("name").equalsIgnoreCase(me()) && dianaMobDiedRecently(3_000L)) {
            DianaAchievements.unlock(77);
            return;
        }
        if (text.contains("King Minos") && text.contains("soul to your Summoning Ring")) DianaAchievements.unlock(118);
    }

    private static void addShards(String shard, int n) {
        String mob = shard.trim();
        if (!SHARDS.contains(mob) || n <= 0) return;
        for (Data d : targets()) d.add(d.drops, mob + " Shard", n);
        dirty = true;
    }

    /** SkyHanni: the number of creatures since the last one of this kind, after Hypixel's "You dug out" line. */
    private static Component addSince(Component component) {
        if (pendingSince == null) return component;
        String text = Sky2MLocation.strip(component.getString()).trim();
        Matcher m = DUG_MOB.matcher(text);
        if (!m.find() || !m.group("mob").trim().equalsIgnoreCase(pendingSinceMob)) return component;
        long since = pendingSince;
        pendingSince = null;
        FeatureConfigs.DianaMobTracker c = mobConfig();
        if (c == null || !c.sinceInChat) return component;
        return component.copy().append(Component.literal(" (" + since + ")").withStyle(ChatFormatting.YELLOW));
    }

    // ------------------------------------------------------------------------------------------------ mobs

    private static void onMobSpawn(Mob mob) {
        long now = System.currentTimeMillis();
        lastActivity = now;
        lastSpawned = mob;
        lastSpawnedAt = now;
        if (mob == Mob.MINOS_INQUISITOR) InquisitorGamble.onYourInquisitor();
        Streaks s = saved.streaks;

        // SkyHanni's creatures since, per period.
        Long allTimeSince = saved.allTime.since.get(mob.name());
        for (Data d : targets()) {
            d.add(d.mobs, mob.name(), 1);
            for (Mob other : Mob.values()) {
                if (other == mob) d.since.put(other.name(), 0L);
                else d.add(d.since, other.name(), 1);
            }
        }
        pendingSince = allTimeSince;
        pendingSinceMob = mob.label;

        // SBO: the drops this mob can give are one mob further away.
        for (Drop drop : Drop.values()) {
            if (drop.from != mob || drop == Drop.CROWN_OF_GREED) continue;
            s.sinceDrop.merge(drop.name(), 1L, Long::sum);
            if (s.since(drop.name()) >= 2) s.b2b.remove(drop.name());
        }

        if (mob.rare) {
            // allTimeSince is the number of creatures between the last one and this one.
            long took = allTimeSince == null ? -1 : allTimeSince + 1;
            FeatureConfigs.DianaDrops c = dropConfig();
            if (c != null && c.enabled && c.sinceMessages && took > 0) {
                long clock = DianaProfitTracker.totals(DianaProfitTracker.Period.ALL_TIME).activeMs();
                Long last = s.lastRareAt.get(mob.name());
                String time = last != null && last > 0 && clock > last ? " and " + DianaProfitTracker.duration(clock - last) : "";
                info(Component.literal("Took ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(took + "").withStyle(ChatFormatting.RED))
                    .append(Component.literal(" mobs" + time + " to get " + article(mob.label) + " ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(mob.label).withStyle(ChatFormatting.RED))
                    .append(Component.literal("!").withStyle(ChatFormatting.YELLOW)));
            }
            s.lastRareAt.put(mob.name(), DianaProfitTracker.totals(DianaProfitTracker.Period.ALL_TIME).activeMs());
            if (took == 1) backToBack(mob.name(), mob.label, true);
            else s.b2b.remove(mob.name());
        }
        dirty = true;
        DianaAchievements.check();
    }

    /** Rare mob died near you (from its nametag health). Counted as lootshared if LOOT SHARE came around then. */
    static void onRareMobDeath(Mob mob) {
        if (!inHub()) return;
        if (mob == Mob.MINOS_INQUISITOR) InquisitorGamble.onInquisitorDeath(gotLootShareRecently());
        if (gotLootShareRecently()) onLootshareMob(mob);
        else recentRareDeaths.put(mob, System.currentTimeMillis());
    }

    static void onDianaMobDeath() {
        lastDianaMobDeath = System.currentTimeMillis();
    }

    private static void onLootshareMob(Mob mob) {
        if (onCooldown("ls:" + mob)) return;
        if (mob == Mob.MINOS_INQUISITOR) InquisitorGamble.onInquisitorLootshare();
        for (Data d : targets()) d.add(d.mobs, mob.name() + "_LS", 1);
        for (Drop drop : Drop.values()) {
            if (drop.from != mob || !drop.lootshare) continue;
            saved.streaks.sinceDrop.merge(drop.lsKey(), 1L, Long::sum);
            if (saved.streaks.since(drop.lsKey()) >= 2) saved.streaks.b2b.remove(drop.lsKey());
        }
        dirty = true;
        DianaAchievements.check();
    }

    // ------------------------------------------------------------------------------------------------ drops

    private static void onDrop(Drop drop, int magicFind, boolean lootshare) {
        if (drop == Drop.CHIMERA) InquisitorGamble.onChimera();
        // Chimera can drop twice from one Inquisitor (yours and a lootshare), so it has its own cooldown per kind.
        // Crown of Greed and Hilt can be seen both in chat and when they reach your inventory.
        boolean pickup = drop == Drop.CROWN_OF_GREED || drop == Drop.HILT_OF_REVELATIONS;
        if (onCooldown("drop:" + drop + (lootshare ? "_LS" : ""), pickup ? 5_000L : TRACK_COOLDOWN_MS)) return;
        lastActivity = System.currentTimeMillis();
        boolean ls = lootshare && drop.lootshare;
        String key = ls ? drop.lsKey() : drop.name();
        for (Data d : targets()) d.add(d.drops, key, 1);
        Streaks s = saved.streaks;
        FeatureConfigs.DianaDrops c = dropConfig();

        if (drop.from != null && drop != Drop.CROWN_OF_GREED) {
            long took = s.since(key);
            String mobs = plural(drop.from);
            if (c != null && c.enabled && c.sinceMessages && drop.big && took > 0) {
                info(Component.literal("Took ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(took + "").withStyle(ChatFormatting.RED))
                    .append(Component.literal(" " + mobs + " to " + (ls ? "lootshare " : "get ")).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(drop.label).withStyle(drop.colour))
                    .append(Component.literal("!").withStyle(ChatFormatting.YELLOW)));
            }
            if (took == 1) backToBack(key, (ls ? "Lootshare " : "") + drop.label, drop.big);
            s.sinceDrop.put(key, 0L);
        }
        if (!lootshare && magicFind > 0) s.bestMagicFind.merge(drop.name(), magicFind, Math::max);
        if (lootshare) {
            if (c != null && c.enabled && c.chatMessage) {
                info(Component.literal("Lootshared " + article(drop.label) + " ").withStyle(ChatFormatting.RED).append(Component.literal(drop.label + "!").withStyle(drop.colour)));
            }
            if (drop == Drop.DAEDALUS_STICK) DianaAchievements.unlock(15);
            if (drop == Drop.MINOS_RELIC) DianaAchievements.unlock(17);
        } else {
            DianaAchievements.onMagicFind(magicFind, drop == Drop.CHIMERA);
        }
        dirty = true;
        InquisitorGamble.afterReveal(() -> announce(drop, magicFind, ls, c));
        DianaAchievements.check();
    }

    /** "b2b Chimera!" and "b2b2b Chimera!", with their achievements. */
    private static void backToBack(String key, String label, boolean show) {
        Streaks s = saved.streaks;
        boolean already = s.b2b.contains(key);
        FeatureConfigs.DianaDrops c = dropConfig();
        if (show && c != null && c.enabled && c.sinceMessages) info(Component.literal((already ? "b2b2b " : "b2b ") + label + "!").withStyle(ChatFormatting.RED));
        s.b2b.add(key);
        DianaAchievements.onBackToBack(key, already);
    }

    /** SBO's loot announcer: the drop in chat with its count and price, as a title, and in party chat. */
    private static void announce(Drop drop, int magicFind, boolean ls, FeatureConfigs.DianaDrops c) {
        if (c == null || !c.enabled) return;
        Data season = season();
        long own = season.drop(drop), shared = season.dropLs(drop);
        String count = drop.lootshare ? (ls ? " Total #" + (own + shared) + " LS #" + shared : " #" + (own + shared)) : " #" + own;
        double price = ItemPriceResolver.value(drop.itemId);
        String mf = magicFind > 0 ? " (+" + magicFind + " ✯ Magic Find)" : "";

        if (c.chatMessage && (drop.big || c.allDrops)) {
            MutableComponent line = Component.literal("RARE DROP! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(drop.label).withStyle(drop.colour))
                .append(Component.literal(mf).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(ls ? " (LS)" : "").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(count).withStyle(ChatFormatting.YELLOW));
            if (price > 0) line.append(Component.literal(" (+" + CombatFeatures.formatCoins(price) + " coins)").withStyle(ChatFormatting.GOLD));
            info(line);
        }
        if (!drop.big) {
            if (c.sound) Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.2f);
            return;
        }
        if (c.title && (drop != Drop.CROWN_OF_GREED || c.crownTitle)) {
            Sky2MAlerts.title(Component.literal(drop.label + (ls ? " (LS)" : "") + "!").withStyle(drop.colour, ChatFormatting.BOLD),
                price > 0 ? Component.literal(CombatFeatures.formatCoins(price) + " coins").withStyle(ChatFormatting.GOLD) : null);
        } else if (c.sound) {
            Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1.0f);
        }
        if (c.sendToParty && partyDrop(drop, c)) {
            String msg = "RARE DROP! " + drop.label + mf + (ls ? " (LS)" : "") + count + (price > 0 ? " (+" + CombatFeatures.formatCoins(price) + " coins)" : "");
            PartyCommands.partyChat(msg);
        }
    }

    private static boolean partyDrop(Drop drop, FeatureConfigs.DianaDrops c) {
        FeatureConfigs.DianaPartyDrops p = c.partyDrops == null ? new FeatureConfigs.DianaPartyDrops() : c.partyDrops;
        return switch (drop) {
            case CHIMERA -> p.chimera;
            case SHIMMERING_WOOL -> p.wool;
            case MANTI_CORE -> p.core;
            case FATEFUL_STINGER -> p.stinger;
            case BRAIN_FOOD -> p.brainFood;
            case DAEDALUS_STICK -> p.stick;
            case MINOS_RELIC -> p.relic;
            case CROWN_OF_GREED -> p.crown;
            case MYTHOLOGICAL_DYE -> p.dye;
            case MYTH_THE_FISH -> p.mythFish;
            case BRAIDED_GRIFFIN_FEATHER -> p.braided;
            default -> false;
        };
    }

    static String plural(Mob mob) {
        return switch (mob) {
            case MINOS_INQUISITOR -> "Inquisitors";
            case KING_MINOS -> "King Minos";
            case SPHINX -> "Sphinxes";
            case MANTICORE -> "Manticores";
            case MINOTAUR -> "Minotaurs";
            case MINOS_CHAMPION -> "Champions";
            case MINOS_HUNTER -> "Minos Hunters";
            case CRETAN_BULL -> "Cretan Bulls";
            default -> mob.label;
        };
    }

    private static String article(String label) {
        return "AEIOU".indexOf(label.charAt(0)) >= 0 ? "an" : "a";
    }

    private static void info(Component message) {
        InquisitorGamble.afterReveal(() -> Sky2MAlerts.chat(message));
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        ticks++;
        if (holdingSpade() && inHub()) lastActivity = System.currentTimeMillis();
        pickupDrops(mc);
        if (dirty && ticks % 100 == 0) save();
    }

    /**
     * SBO's pickup-log tracking: Crown of Greed and Hilt of Revelations don't always get a RARE DROP line, so they
     * count when one comes into your inventory right after a Diana mob died near you.
     */
    private static void pickupDrops(Minecraft mc) {
        if (mc.player == null || mc.gui.screen() != null || !inHub()) {
            lastInventory = null;
            return;
        }
        Map<String, Integer> now = new HashMap<>();
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = Sky2MPriceTooltip.marketId(stack);
            if (id.equals("CROWN_OF_GREED") || id.equals("HILT_OF_REVELATIONS")) now.merge(id, stack.getCount(), Integer::sum);
        }
        if (lastInventory != null && dianaMobDiedRecently(4_000L)) {
            for (Map.Entry<String, Integer> e : now.entrySet()) {
                if (e.getValue() > lastInventory.getOrDefault(e.getKey(), 0)) {
                    onDrop(e.getKey().equals("CROWN_OF_GREED") ? Drop.CROWN_OF_GREED : Drop.HILT_OF_REVELATIONS, 0, false);
                }
            }
        }
        lastInventory = now;
    }

    // ------------------------------------------------------------------------------------------------ HUD

    private static MutableComponent line(String prefix, long amount, Mob mob, String percent) {
        MutableComponent c = Component.literal(prefix).withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d ", amount)).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(mob.label).withStyle(mob.colour()));
        if (percent != null) c.append(Component.literal(" " + percent).withStyle(ChatFormatting.GRAY));
        return c;
    }

    private static List<Component> hudLines() {
        FeatureConfigs.DianaMobTracker c = mobConfig();
        DianaProfitTracker.Period period = c == null || c.period == null ? DianaProfitTracker.Period.SESSION : c.period;
        Data data = data(period);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Mythological Creature Tracker (" + period + ")").withStyle(ChatFormatting.GRAY));
        long total = data.totalMobs();
        List<Mob> mobs = new ArrayList<>(List.of(Mob.values()));
        mobs.sort((a, b) -> Long.compare(data.mob(b), data.mob(a)));
        for (Mob mob : mobs) {
            long amount = data.mob(mob);
            if (amount <= 0 && !(mob.rare && data.mobLs(mob) > 0)) continue;
            String pct = c != null && c.showPercentage && total > 0 ? String.format(Locale.US, "%.1f%%", amount * 100.0 / total) : null;
            MutableComponent l = line(" - ", amount, mob, pct);
            if (mob.rare && c != null && c.showLootshare) {
                l.append(Component.literal(" [LS: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(data.mobLs(mob))).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("]").withStyle(ChatFormatting.GRAY));
            }
            lines.add(l);
        }
        MutableComponent totalLine = Component.literal("Total Mythological Creatures: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d", total)).withStyle(ChatFormatting.YELLOW));
        long activeMs = DianaProfitTracker.totals(period).activeMs();
        if (activeMs >= 60_000L) {
            totalLine.append(Component.literal(String.format(Locale.US, " (%.1f/h)", total / (activeMs / 3_600_000d))).withStyle(ChatFormatting.GRAY));
        }
        lines.add(totalLine);

        boolean header = false;
        List<Mob> rare = new ArrayList<>();
        for (Mob m : Mob.values()) if (m.rare && data.mob(m) > 0) rare.add(m);
        rare.sort((a, b) -> Long.compare(data.since.getOrDefault(a.name(), 0L), data.since.getOrDefault(b.name(), 0L)));
        for (Mob mob : rare) {
            if (!header) {
                lines.add(Component.literal("Creatures since:").withStyle(ChatFormatting.GRAY));
                header = true;
            }
            lines.add(Component.literal(" - ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(mob.label).withStyle(mob.colour()))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.US, "%,d", data.since.getOrDefault(mob.name(), 0L))).withStyle(ChatFormatting.YELLOW)));
        }
        return lines;
    }

    // ------------------------------------------------------------------------------------------------ saving

    private static void load() {
        try {
            if (Files.exists(file)) {
                Saved loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
                if (loaded != null) {
                    if (loaded.allTime == null) loaded.allTime = new Data();
                    if (loaded.seasons == null) loaded.seasons = new LinkedHashMap<>();
                    if (loaded.streaks == null) loaded.streaks = new Streaks();
                    saved = loaded;
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the Diana tracker: " + e.getMessage());
        }
    }

    static void save() {
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the Diana tracker: " + e.getMessage());
        }
    }
}
