package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon splits, tick timers, mask timers, score display and M7 debuff alerts. Routes are in {@link DungeonRoutes}.
 * <p>
 * The splits, split PBs and mask (invincibility) timers follow Odin's SplitsManager, Splits, PersonalBest and
 * InvincibilityTimer (https://github.com/odtheking/Odin, BSD-3-Clause).
 */
public final class DungeonFeatures {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ----- Splits (Odin's SplitsManager) -----
    private static final class Split {
        final Pattern pattern;
        final String name;
        long time;
        long ticks;

        Split(String regex, String name) {
            this.pattern = Pattern.compile(regex);
            this.name = name;
        }
    }

    private record SplitRow(String name, long time, long ticks, boolean current) {}

    private static final String MORT = "\\[NPC] Mort: Here, I found this map when I first entered the dungeon\\.|\\[NPC] Mort: Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!";
    private static final String BLOOD_OPEN = "^\\[BOSS] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|Ah, we meet again\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|You've managed to scratch and claw your way here, eh\\?|I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|Things feel a little more roomy now, eh\\?)$|^The BLOOD DOOR has been opened!$";
    private static final String PORTAL_ENTRY = "\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.";
    private static final String CLEARED = "^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$";
    private static final String[][][] FLOOR_SPLITS = {
        {},
        {{"^\\[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable\\.$", "§cBonzo's Sike"}, {"\\[BOSS] Bonzo: Oh I'm dead!", "§4Cleared"}},
        {{"^\\[BOSS] Scarf: This is where the journey ends for you, Adventurers\\.$", "§cScarf's minions"}, {"^\\[BOSS] Scarf: Did you forget\\? I was taught by the best! Let's dance\\.$", "§4Cleared"}},
        {{"^\\[BOSS] The Professor: I was burdened with terrible news recently\\.\\.\\.$", "§cThe Guardians"}, {"^\\[BOSS] The Professor: Oh\\? You found my Guardians' one weakness\\?$", "§aThe Professor"}, {"^\\[BOSS] The Professor: What\\?! My Guardian power is unbeatable!$", "§4Cleared"}},
        {{"^\\[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!$", "§4Cleared"}},
        {{"^\\[BOSS] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\.$", "§4Cleared"}},
        {{"^\\[BOSS] Sadan: So you made it all the way here\\.\\.\\. Now you wish to defy me\\? Sadan\\?!$", "§cTerracottas"}, {"^\\[BOSS] Sadan: ENOUGH!$", "§aGiants"}, {"^\\[BOSS] Sadan: You did it\\. I understand now, you have earned my respect\\.$", "§4Cleared"}},
        {{"^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$", "§5Maxor"}, {"\\[BOSS] Storm: Pathetic Maxor, just like expected\\.", "§3Storm"}, {"\\[BOSS] Goldor: Who dares trespass into my domain\\?", "§6Terminals"}, {"The Core entrance is opening!", "§7Goldor"}, {"\\[BOSS] Necron: You went further than any human before, congratulations\\.", "§cNecron"}, {"^\\[BOSS] Necron: All this, for nothing\\.\\.\\.$|^\\[BOSS] (?:The )?Wither King: You\\.\\.\\. again\\?$", "§4Cleared"}},
    };
    private static final List<Split> SPLITS = new ArrayList<>();
    private static String splitFloor = "";
    /** Personal bests in seconds: floor ("F7") -> split name without colour -> seconds. */
    private static Map<String, Map<String, Float>> personalBests = new HashMap<>();
    private static Path pbFile;

    // ----- Tick timers (counted in server ticks from Hypixel's per-tick ping packets) -----
    private static long serverTicks;
    private static long stormStartTick = -1;
    private static long goldorStartTick = -1;
    /** The server tick Storm called his lightning (the purple pillar crush, Odin's PY timer), once per run. */
    private static long pyStartTick = -1;
    private static boolean pyTriggered;
    /** 55 ticks since Hypixel's 2026 dungeon update (95 before), as in Odin 0.3.6. */
    private static final int PY_TICKS = 55;
    /** Goldor hits everyone outside his safe spots every 60 server ticks (3 s), as in Odin's TickTimers. */
    private static final int GOLDOR_TICKS = 60;
    /** Maxor's first line to the fight starting (NoammAddons' Maxor Start). */
    private static final int MAXOR_START_TICKS = 83;
    /** Storm's first line to his lightning, and Storm's death to terminals opening (Odin 0.3.6's TickTimers). */
    private static final int STORM_LIGHTNING_TICKS = 520, GOLDOR_START_TICKS = 64;
    private static long maxorStartTick = -1;
    private static long stormDeathTick = -1;

    // ----- Masks (Odin's InvincibilityTimer), counted in server ticks -----
    private enum Invincibility {
        SPIRIT("Spirit Mask", Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!$"), 60, 30),
        BONZO("Bonzo's Mask", Pattern.compile("^Your (?:. )?Bonzo's Mask saved your life!$"), 60, 180),
        PHOENIX("Phoenix", Pattern.compile("^Your Phoenix Pet saved you from certain death!$"), 80, 60);

        final String display;
        final Pattern pattern;
        final int invincibilityTicks;
        final int cooldownSeconds;
        int active;
        int cooldown;

        Invincibility(String display, Pattern pattern, int invincibilityTicks, int cooldownSeconds) {
            this.display = display;
            this.pattern = pattern;
            this.invincibilityTicks = invincibilityTicks;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
    private static final Pattern COOLDOWN_LORE = Pattern.compile("^Cooldown: (\\d+)s$");

    // ----- Debuffs -----
    private static int lastBreath, iceSpray, lethality;
    private static boolean debuffAlerted;
    private static boolean dragonPhase;

    // ----- Last Breath release cue -----
    private static long lastBreathChargeStart = -1;
    private static boolean lastBreathCued;

    private DungeonFeatures() {}

    private static boolean goldorReached;
    /** The world Goldor was reached in: a new run is a new world, even when the area never stops being Catacombs. */
    private static java.lang.ref.WeakReference<Object> goldorWorld = new java.lang.ref.WeakReference<>(null);

    /** True once Goldor's first line has been seen this run (Storm defeated), until the run ends. */
    public static boolean goldorReached() {
        return goldorReached && Minecraft.getInstance().level == goldorWorld.get();
    }

    private static void reachGoldor() {
        goldorReached = true;
        goldorWorld = new java.lang.ref.WeakReference<>(Minecraft.getInstance().level);
    }

    /** True from Goldor's first line until Necron's (F7/M7 phase 3). */
    public static boolean inGoldorPhase() {
        return goldorStartTick >= 0;
    }

    /** True from Storm's first line until Goldor's (F7/M7 phase 2). */
    public static boolean inStormPhase() {
        return stormStartTick >= 0 && goldorStartTick < 0;
    }

    private static FeatureConfigs.Dungeons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons;
    }

    public static void init(Path configDir) {
        pbFile = configDir.resolve("sky2m-split-pbs.json");
        loadPersonalBests();

        Sky2MChat.onChat(DungeonFeatures::onChat);
        ServerTickCallback.EVENT.register(DungeonFeatures::onServerTick);
        SpiritBear.init();
        DungeonLividFinder.init();
        LividTimer.init();
        TerracottaTimer.init();
        AutoRequeue.init();
        PartyFinderStats.init();
        PfRequirements.init();
        BlessingDisplay.init();
        // A reconnect keeps the run's splits: leaving the dungeon or the next run's start clears them.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> resetRunState());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> resumeSplits());
        Sky2MLocation.onAreaChange(area -> {
            // An empty area is the tab list briefly not being read (world load, lag), not leaving the dungeon.
            if (!area.isEmpty() && !area.equals("Catacombs")) resetRun();
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && dragonPhase) countDebuffUse(player.getItemInHand(hand));
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && dragonPhase && entity instanceof EnderDragon && hasLore(player.getMainHandItem(), "Lethality")) {
                lethality++;
                checkDebuffs();
            }
            return InteractionResult.PASS;
        });
        DungeonRoutes.init(configDir);
        StarredMobs.init();
        TeammateHighlight.init();
        LeapMenu.init();
        PositionalMessages.init(configDir);
        DoorHighlight.init();
        BloodCamp.init();
        OdinPuzzleSolvers.init();
        ThreeByThree.init();
        WitherDragons.init();
        F7Extras.init();

        com.epic60869.sky2m.features.core.Sky2MHuds.setting("dungeon_splits", () -> config() != null && config().timers.splits);
        Sky2MHuds.register("dungeon_splits", "Dungeon Splits",
            () -> config() != null && config().timers.splits && !SPLITS.isEmpty() && SPLITS.getFirst().time != 0,
            DungeonFeatures::splitLines,
            List.of(kv("§2Blood Open: ", "24.51s"), kv("§bBlood Clear: ", "1m 10.20s"), kv("§dPortal Entry: ", "12.03s"), kv("§9Boss Entry: ", "1m 46.74s"), kv("§5Maxor: ", "38.10s")),
            8, 740);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("tick_timers", () -> config() != null && config().timers.tickTimers);
        Sky2MHuds.register("tick_timers", "Tick Timers",
            () -> config() != null && config().timers.tickTimers && (stormStartTick >= 0 || goldorStartTick >= 0 || maxorStartActive()),
            DungeonFeatures::tickLines,
            List.of(kv("Storm pillars: ", "12 ticks")),
            200, 740);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("storm_py", () -> config() != null && config().timers.pyTimer);
        Sky2MHuds.register("storm_py", "Storm PY Timer",
            () -> config() != null && config().timers.pyTimer && pyStartTick >= 0 && serverTicks - pyStartTick <= PY_TICKS,
            () -> List.of(pyLine(PY_TICKS - (serverTicks - pyStartTick))),
            List.of(pyLine(PY_TICKS)),
            200, 760);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("mask_timers", () -> config() != null && config().timers.maskTimers);
        Sky2MHuds.register("mask_timers", "Mask Timers",
            () -> config() != null && config().timers.maskTimers && Sky2MLocation.inDungeon(),
            DungeonFeatures::maskLines,
            List.of(maskPreview("Spirit Mask", "✔", ChatFormatting.GREEN, true), maskPreview("Bonzo's Mask", "2.45s", ChatFormatting.GOLD, false), maskPreview("Phoenix", "41.20s", ChatFormatting.RED, false)),
            200, 780);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("dungeon_score", () -> config() != null && config().score.display);
        Sky2MHuds.register("dungeon_score", "Dungeon Score",
            () -> config() != null && config().score.display && Sky2MLocation.inDungeon() && ScoreCalculator.started(),
            DungeonFeatures::scoreLines,
            List.of(Component.literal("§eScore: §a300")),
            200, 820);
    }

    private static Component kv(String key, String value) {
        return Component.literal(key).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static void resetRun() {
        SPLITS.clear();
        splitFloor = "";
        resetRunState();
    }

    /** Everything about the run but its splits. */
    private static void resetRunState() {
        stormStartTick = -1;
        goldorStartTick = -1;
        pyStartTick = -1;
        pyTriggered = false;
        maxorStartTick = -1;
        stormDeathTick = -1;
        goldorReached = false;
        dragonPhase = false;
        for (Invincibility type : Invincibility.values()) {
            type.active = 0;
            type.cooldown = 0;
        }
        resetDebuffs();
    }

    private static void onServerTick() {
        serverTicks++;
        for (Invincibility type : Invincibility.values()) {
            if (type.cooldown > 0) type.cooldown--;
            if (type.active > 0) type.active--;
        }
        tickLastBreath();
    }

    // ----- Splits -----

    private static void startSplits() {
        SPLITS.clear();
        resumed = false;
        String floor = Sky2MLocation.dungeonFloor();
        int number = floor.equals("E") ? 0 : floor.length() == 2 ? floor.charAt(1) - '0' : -1;
        if (number < 0 || number >= FLOOR_SPLITS.length) return;
        splitFloor = floor;
        SPLITS.add(new Split(MORT, "§2Blood Open"));
        SPLITS.add(new Split(BLOOD_OPEN, "§bBlood Clear"));
        SPLITS.add(new Split(PORTAL_ENTRY, "§dPortal Entry"));
        for (String[] split : FLOOR_SPLITS[number]) SPLITS.add(new Split(split[0], split[1]));
        SPLITS.add(new Split(CLEARED, "§1Total"));
    }

    private static final Pattern TIME_ELAPSED = Pattern.compile("Time Elapsed: (?:(\\d+)h )?(?:(\\d+)m )?(\\d+)s");

    /**
     * A run joined (or rejoined) after its start: the splits start from the sidebar's "Time Elapsed", so the timer and
     * total carry on. Parts whose start wasn't seen don't count towards PBs.
     */
    private static void resumeSplits() {
        if (!SPLITS.isEmpty() || !Sky2MLocation.inDungeon()) return;
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = TIME_ELAPSED.matcher(line);
            if (!m.find()) continue;
            long elapsed = ((m.group(1) == null ? 0 : Long.parseLong(m.group(1))) * 3600
                + (m.group(2) == null ? 0 : Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000L;
            if (elapsed <= 0) return;
            startSplits();
            if (SPLITS.isEmpty()) return;
            Split first = SPLITS.getFirst();
            first.time = System.currentTimeMillis() - elapsed;
            first.ticks = serverTicks - elapsed / 50;
            resumed = true;
            return;
        }
    }

    /** The splits were picked up mid-run: the Total isn't a full run, so it can't be a PB. */
    private static boolean resumed;

    private static void onSplitMessage(String message) {
        for (int index = 0; index < SPLITS.size(); index++) {
            Split split = SPLITS.get(index);
            if (split.time != 0 || !split.pattern.matcher(message).matches()) continue;
            split.time = System.currentTimeMillis();
            split.ticks = serverTicks;
            if (index == 0) return;
            Split previous = SPLITS.get(index - 1);
            // The part before this wasn't seen start (joined mid-run): no time for it, and no PB.
            if (previous.time == 0) return;
            float segment = (split.time - previous.time) / 1000f;
            if (index == SPLITS.size() - 1) {
                finishRun(previous, segment);
            } else {
                personalBest(previous.name, segment, "§6" + previous.name + " §7took §6");
            }
            return;
        }
    }

    private static void finishRun(Split lastSegment, float lastSegmentTime) {
        List<SplitRow> rows = currentRows();
        personalBest(lastSegment.name, lastSegmentTime, "§6" + lastSegment.name + " §7took §6");
        if (rows.isEmpty()) return;
        if (!resumed) personalBest("Total", rows.getLast().time() / 1000f, "§6Total time §7took §6");
        if (!splitMessagesEnabled()) return;
        for (int i = 0; i < rows.size(); i++) {
            SplitRow row = rows.get(i);
            String name = i == rows.size() - 1 ? "Total" : row.name();
            Sky2MAlerts.chat(Component.literal("§6" + name + " §7took §6" + formatTime(row.time()) + "§7."));
        }
    }

    private static boolean splitMessagesEnabled() {
        FeatureConfigs.Dungeons config = config();
        return config != null && config.timers.splits && config.timers.splitMessages;
    }

    /** Records {@code seconds} as the PB for {@code name} on {@code floor} if it beats the old one; true if it did. */
    static boolean newPersonalBest(String floor, String name, float seconds) {
        Map<String, Float> floorPbs = personalBests.computeIfAbsent(floor, k -> new HashMap<>());
        if (seconds >= floorPbs.getOrDefault(name, 9999f)) return false;
        floorPbs.put(name, seconds);
        savePersonalBests();
        return true;
    }

    /** Odin's PersonalBest.time: records a new PB and prints the time with the old PB. */
    private static void personalBest(String coloredName, float seconds, String prefix) {
        if (splitFloor.isEmpty()) return;
        String name = ChatFormatting.stripFormatting(coloredName);
        Map<String, Float> floorPbs = personalBests.computeIfAbsent(splitFloor, k -> new HashMap<>());
        float oldPb = floorPbs.getOrDefault(name, 9999f);
        String suffix;
        if (seconds < oldPb) {
            floorPbs.put(name, seconds);
            savePersonalBests();
            suffix = "§7(§d§lNew PB§r§7) Old PB was §8" + fixed(oldPb);
        } else {
            suffix = "§8(§7" + fixed(oldPb) + "§8)";
        }
        if (splitMessagesEnabled()) Sky2MAlerts.chat(Component.literal(prefix + fixed(seconds) + "s§7! " + suffix));
    }

    /** This run's finished splits as {name without colours, milliseconds}, for Run History. */
    public static List<Object[]> finishedSplits() {
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < SPLITS.size(); i++) {
            Split split = SPLITS.get(i), next = SPLITS.get(i + 1);
            if (split.time == 0 || next.time == 0) break;
            out.add(new Object[]{net.minecraft.ChatFormatting.stripFormatting(split.name), next.time - split.time});
        }
        return out;
    }

    private static List<SplitRow> currentRows() {
        List<SplitRow> rows = new ArrayList<>();
        if (SPLITS.isEmpty() || SPLITS.getFirst().time == 0) return rows;
        Split last = SPLITS.getLast();
        long latestTime = last.time != 0 ? last.time : System.currentTimeMillis();
        long latestTicks = last.ticks != 0 ? last.ticks : serverTicks;
        int currentIndex = -1;
        long[] times = new long[SPLITS.size()];
        long[] ticks = new long[SPLITS.size()];
        times[SPLITS.size() - 1] = latestTime - SPLITS.getFirst().time;
        ticks[SPLITS.size() - 1] = latestTicks - SPLITS.getFirst().ticks;
        for (int i = 0; i < SPLITS.size() - 1; i++) {
            Split next = SPLITS.get(i + 1);
            if (next.time != 0) {
                times[i] = next.time - SPLITS.get(i).time;
                ticks[i] = next.ticks - SPLITS.get(i).ticks;
            } else {
                times[i] = latestTime - SPLITS.get(i).time;
                ticks[i] = latestTicks - SPLITS.get(i).ticks;
                currentIndex = i;
                break;
            }
        }
        for (int i = 0; i < SPLITS.size(); i++) rows.add(new SplitRow(SPLITS.get(i).name, times[i], ticks[i], i == currentIndex));
        return rows;
    }

    private static List<Component> splitLines() {
        FeatureConfigs.Dungeons config = config();
        List<SplitRow> rows = currentRows();
        List<Component> lines = new ArrayList<>();
        if (rows.isEmpty() || config == null) return lines;
        List<SplitRow> segments = rows.subList(0, rows.size() - 1);
        for (int index = 0; index < segments.size(); index++) {
            SplitRow row = segments.get(index);
            if (row.time() != 0 || row.current()) lines.add(splitLine(row, config));
            if (config.timers.bossEntrySplit && index == 2 && rows.size() > 3 && row.time() != 0) {
                long time = 0, ticks = 0;
                for (SplitRow r : segments.subList(0, 3)) {
                    time += r.time();
                    ticks += r.ticks();
                }
                lines.add(splitLine(new SplitRow("§9Boss Entry", time, ticks, false), config));
            }
        }
        lines.add(splitLine(new SplitRow("§1Total", rows.getLast().time(), rows.getLast().ticks(), false), config));
        if (config.timers.splitEta && SPLITS.getLast().time == 0) {
            long eta = estimateTotal(rows);
            if (eta > 0) lines.add(Component.literal("§7ETA: §f" + formatTime(eta)));
        }
        return lines;
    }

    /**
     * Estimated total run time: the time so far plus the rest of the current split and every later one, each taken as
     * the average of that split in finished Run History runs for this floor (or the personal best if there are none).
     * 0 when nothing is known.
     */
    private static long estimateTotal(List<SplitRow> rows) {
        if (splitFloor.isEmpty() || rows.size() < 2) return 0;
        Map<String, long[]> sums = new HashMap<>();
        for (RunHistory.Run run : RunHistory.runs()) {
            if (!run.completed || !splitFloor.equals(run.floor)) continue;
            for (RunHistory.Split s : run.splits) {
                long[] v = sums.computeIfAbsent(s.name(), k -> new long[2]);
                v[0] += s.ms();
                v[1]++;
            }
        }
        Map<String, Float> pbs = personalBests.getOrDefault(splitFloor, Map.of());
        long remaining = 0;
        boolean started = false;
        for (int i = 0; i < rows.size() - 1; i++) {
            SplitRow row = rows.get(i);
            if (!row.current() && !started) continue;
            String name = ChatFormatting.stripFormatting(row.name());
            long[] v = sums.get(name);
            long expected;
            if (v != null && v[1] > 0) expected = v[0] / v[1];
            else if (pbs.containsKey(name)) expected = (long) (pbs.get(name) * 1000f);
            else return 0;
            remaining += started ? expected : Math.max(0, expected - row.time());
            started = true;
        }
        if (!started) return 0;
        return rows.getLast().time() + remaining;
    }

    private static Component splitLine(SplitRow row, FeatureConfigs.Dungeons config) {
        String time = formatTime(row.time());
        if (config.timers.splitTickTime) time += " §8(§7" + fixed(row.ticks() / 20f) + "§8)";
        return Component.literal(row.name() + ": §f" + time);
    }

    /** Odin's formatTime: "1h 2m 3.45s". */
    private static String formatTime(long millis) {
        if (millis == 0) return "0s";
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        float seconds = (millis % 60_000) / 1000f;
        return (hours > 0 ? hours + "h " : "") + (minutes > 0 ? minutes + "m " : "") + fixed(seconds) + "s";
    }

    private static String fixed(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static void loadPersonalBests() {
        try {
            if (Files.exists(pbFile)) {
                Map<String, Map<String, Float>> loaded = GSON.fromJson(Files.readString(pbFile, StandardCharsets.UTF_8), new TypeToken<Map<String, Map<String, Float>>>() {}.getType());
                if (loaded != null) personalBests = new HashMap<>(loaded);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read split PBs: " + e);
        }
    }

    private static void savePersonalBests() {
        try {
            Files.writeString(pbFile, GSON.toJson(personalBests), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save split PBs: " + e);
        }
    }

    // ----- Tick timers -----

    /**
     * Ticks left in a repeating timer, like Odin's TickTimers: the full period on the starting tick, then down to 0 on
     * the tick it happens (e.g. the Storm pad), then from period - 1 again.
     */
    private static long countdown(long elapsed, int period) {
        return elapsed <= 0 ? period : (period - elapsed % period) % period;
    }

    private static boolean maxorStartActive() {
        FeatureConfigs.Dungeons config = config();
        return config != null && config.timers.maxorStart && maxorStartTick >= 0 && serverTicks - maxorStartTick < MAXOR_START_TICKS;
    }

    private static List<Component> tickLines() {
        List<Component> lines = new ArrayList<>();
        FeatureConfigs.Dungeons config = config();
        if (config == null) return lines;
        if (maxorStartActive()) {
            lines.add(kv("Maxor start: ", (MAXOR_START_TICKS - (serverTicks - maxorStartTick)) + " ticks"));
        }
        if (stormStartTick >= 0 && goldorStartTick < 0) {
            lines.add(kv("Storm pillars: ", countdown(serverTicks - stormStartTick, 20) + " ticks"));
            long lightning = STORM_LIGHTNING_TICKS - (serverTicks - stormStartTick);
            if (config.timers.stormLightning && lightning > 0) lines.add(kv("Storm lightning: ", lightning + " ticks"));
        }
        if (config.timers.goldorStart && stormDeathTick >= 0 && serverTicks - stormDeathTick < GOLDOR_START_TICKS) {
            lines.add(kv("Goldor start: ", (GOLDOR_START_TICKS - (serverTicks - stormDeathTick)) + " ticks"));
        }
        if (goldorStartTick >= 0) {
            lines.add(kv("Goldor death tick: ", countdown(serverTicks - goldorStartTick, GOLDOR_TICKS) + " ticks"));
        }
        return lines;
    }

    /** Odin's formatTimer: "PY: 4.75s", green over two thirds of the time left, gold over a third, then red. */
    private static Component pyLine(long ticks) {
        ChatFormatting colour = ticks >= PY_TICKS * 0.66 ? ChatFormatting.GREEN : ticks >= PY_TICKS * 0.33 ? ChatFormatting.GOLD : ChatFormatting.RED;
        return Component.literal("PY: ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(String.format(java.util.Locale.US, "%.2fs", Math.max(0, ticks) / 20f)).withStyle(colour));
    }

    // ----- Masks -----

    private static Component maskPreview(String name, String value, ChatFormatting color, boolean worn) {
        MutableComponent line = Component.literal(worn ? "▌" : " ").withStyle(ChatFormatting.DARK_PURPLE);
        return line.append(Component.literal(name + ": ").withStyle(ChatFormatting.GRAY)).append(Component.literal(value).withStyle(color));
    }

    private static List<Component> maskLines() {
        List<Component> lines = new ArrayList<>();
        String helmet = helmetName();
        for (Invincibility type : Invincibility.values()) {
            boolean worn = type != Invincibility.PHOENIX && helmet.contains(type.display);
            String value;
            ChatFormatting color;
            if (type.active > 0) {
                value = fixed(type.active / 20f) + "s";
                color = ChatFormatting.GOLD;
            } else if (type.cooldown > 0) {
                value = fixed(type.cooldown / 20f) + "s";
                color = ChatFormatting.RED;
            } else {
                value = "✔";
                color = ChatFormatting.GREEN;
            }
            lines.add(maskPreview(type.display, value, color, worn));
        }
        return lines;
    }

    private static void onMaskMessage(String text, FeatureConfigs.Dungeons config) {
        for (Invincibility type : Invincibility.values()) {
            if (!type.pattern.matcher(text).matches()) continue;
            Integer seconds = type == Invincibility.BONZO ? helmetCooldownSeconds() : null;
            type.active = type.invincibilityTicks;
            type.cooldown = (seconds != null ? seconds : type.cooldownSeconds) * 20;
            int used = 0;
            for (Invincibility t : Invincibility.values()) if (t.cooldown > 0) used++;
            String name = type == Invincibility.PHOENIX ? "Phoenix" : type == Invincibility.BONZO ? "Bonzo" : "Spirit";
            if (config.timers.maskAnnounce) sendCommand("pc " + name + " Procced! (" + used + "/" + Invincibility.values().length + ")");
            if (config.timers.maskAlert) Sky2MAlerts.title(Component.literal(name + " Procced!").withStyle(ChatFormatting.RED), Component.empty());
            return;
        }
    }

    /** Bonzo's Mask cooldown from its lore, which is lower on starred masks. */
    private static Integer helmetCooldownSeconds() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        ItemLore lore = player.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.LORE);
        if (lore == null) return null;
        for (int i = lore.lines().size() - 1; i >= 0; i--) {
            Matcher m = COOLDOWN_LORE.matcher(lore.lines().get(i).getString());
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private static String helmetName() {
        var player = Minecraft.getInstance().player;
        if (player == null) return "";
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        // The SkyBlock ID survives renames from /s2 custom; the name is kept as a fallback.
        String id = com.epic60869.sky2m.custom.util.Compat.neuName(helmet);
        String name = com.epic60869.sky2m.custom.util.Compat.realName(helmet).getString();
        if (id.contains("SPIRIT_MASK")) name += " Spirit Mask";
        if (id.contains("BONZO_MASK")) name += " Bonzo's Mask";
        return name;
    }

    // ----- Score display -----

    private static List<Component> scoreLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§eScore: " + ScoreCalculator.colorizeScore(ScoreCalculator.score())));
        FeatureConfigs.Dungeons config = config();
        if (config != null && config.score.detailed) {
            int crypts = ScoreCalculator.crypts();
            int deaths = ScoreCalculator.deaths();
            lines.add(Component.literal("§6Secrets: §b" + ScoreCalculator.foundSecrets() + " §7(" + fixed((float) ScoreCalculator.secretPercentage()) + "%)"));
            lines.add(Component.literal(ScoreCalculator.colorByPercent(crypts, 5, false) + "Crypts: " + crypts));
            lines.add(Component.literal("§cDeaths: " + ScoreCalculator.colorByPercent(deaths, 4, true) + deaths));
            String prince = "§eP: " + (ScoreCalculator.princeKilled() ? "§a§l✔" : "§c§l✖");
            lines.add(Component.literal(ScoreCalculator.floorNumber() > 5
                ? "§cM: " + (ScoreCalculator.mimicKilled() ? "§a§l✔" : "§c§l✖") + " " + prince
                : prince));
        }
        return lines;
    }

    // ----- Chat -----

    private static void onChat(Sky2MChat.Message message) {
        String text = message.text();
        FeatureConfigs.Dungeons config = config();
        if (config == null) return;

        if (text.equals("Starting in 1 second.")) startSplits();
        else onSplitMessage(text);

        // Boss phases for the tick timers and debuff counting.
        if (!pyTriggered && (text.equals("[BOSS] Storm: ENERGY HEED MY CALL!") || text.equals("[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!"))) {
            pyTriggered = true;
            pyStartTick = serverTicks;
        }
        if (text.equals("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!")) maxorStartTick = serverTicks;
        if (text.startsWith("[BOSS] Storm: Pathetic Maxor, just like expected.")) stormStartTick = serverTicks;
        else if (text.startsWith("[BOSS] Goldor: Who dares trespass into my domain?")) {
            goldorStartTick = serverTicks;
            reachGoldor();
        }
        // Storm's death line starts the death tick too (Odin), in case Goldor's first line is missed; that line resyncs it.
        else if (text.startsWith("[BOSS] Storm: I should have known that I stood no chance.")) {
            goldorStartTick = serverTicks;
            stormDeathTick = serverTicks;
            reachGoldor();
        }
        // Any Goldor line also means Goldor has been reached, in case the first one was missed.
        else if (text.startsWith("[BOSS] Goldor:")) reachGoldor();
        else if (text.equals("The Core entrance is opening!")) {
            // The death tick stops once the core opens (Odin).
            stormStartTick = -1;
            goldorStartTick = -1;
        }
        else if (text.startsWith("[BOSS] Necron: You went further than any human before, congratulations.")) goldorStartTick = -1;
        else if (text.startsWith("[BOSS] Necron: All this, for nothing...") || isWitherKingStart(text)) dragonPhase = true;

        if (Sky2MLocation.inDungeon()) onMaskMessage(text, config);

        if (dragonPhase && text.contains("Dragon") && text.contains("spawning")) resetDebuffs();
    }

    /**
     * The Wither King's first line, which starts P5 since Hypixel's 2026 dungeon update removed Necron's "All this, for
     * nothing..." (Odin's SplitsManager). Older Hypixel versions said "The Wither King", so both are accepted.
     */
    static boolean isWitherKingStart(String text) {
        return text.equals("[BOSS] Wither King: You... again?") || text.equals("[BOSS] The Wither King: You... again?");
    }

    private static void sendCommand(String command) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.sendCommand(command);
    }

    // ----- Debuffs -----

    private static void countDebuffUse(ItemStack stack) {
        if (isLastBreath(stack)) lastBreath++;
        else if (isItem(stack, "ICE_SPRAY_WAND", "Ice Spray")) iceSpray++;
        else return;
        checkDebuffs();
    }

    private static void checkDebuffs() {
        FeatureConfigs.Dungeons config = config();
        if (config == null || !config.timers.debuffAlert || debuffAlerted) return;
        if (lastBreath >= 5 && iceSpray >= 1 && lethality >= 5) {
            debuffAlerted = true;
            Sky2MAlerts.title(Component.literal("MAX DEBUFF!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                Component.literal("5x Last Breath, Ice Spray, 5x Lethality").withStyle(ChatFormatting.WHITE));
        }
    }

    /**
     * Matches by SkyBlock item ID first, so items renamed with /s2 custom (or any other client-side rename)
     * are still recognised, then by name.
     */
    private static boolean isItem(ItemStack stack, String idPart, String namePart) {
        String id = com.epic60869.sky2m.custom.util.Compat.neuName(stack);
        if (!id.isEmpty()) return id.contains(idPart);
        return com.epic60869.sky2m.custom.util.Compat.realName(stack).getString().contains(namePart);
    }

    private static boolean isLastBreath(ItemStack stack) {
        return isItem(stack, "LAST_BREATH", "Last Breath");
    }

    private static void resetDebuffs() {
        lastBreath = 0;
        iceSpray = 0;
        lethality = 0;
        debuffAlerted = false;
    }

    /**
     * Counts server ticks (not client ticks, which drift with FPS and lag) while you charge Last Breath,
     * and plays the release cue once the configured number of ticks has passed.
     */
    private static void tickLastBreath() {
        FeatureConfigs.Dungeons config = config();
        var player = Minecraft.getInstance().player;
        boolean charging = config != null && config.timers.lastBreathRelease && player != null && player.isUsingItem()
            && isLastBreath(player.getUseItem());
        if (!charging) {
            lastBreathChargeStart = -1;
            lastBreathCued = false;
            return;
        }
        if (lastBreathChargeStart < 0) lastBreathChargeStart = serverTicks;
        if (!lastBreathCued && serverTicks - lastBreathChargeStart >= (int) config.timers.lastBreathTicks) {
            lastBreathCued = true;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                mc.gui.hud.setTimes(0, 10, 5);
                mc.gui.hud.setTitle(Component.literal("RELEASE").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                mc.gui.hud.setSubtitle(Component.empty());
                float volume = config.timers.lastBreathVolume;
                switch (config.timers.lastBreathSound) {
                    case BELL -> Sky2MAlerts.play(SoundEvents.BELL_BLOCK, 1.2f, volume);
                    case NOTE_BELL -> Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f, volume);
                    case DING -> Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 2f, volume);
                    case ORB -> Sky2MAlerts.play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, volume);
                    case NONE -> {}
                }
            });
        }
    }

    private static boolean hasLore(ItemStack stack, String text) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) if (line.getString().contains(text)) return true;
        return false;
    }
}
