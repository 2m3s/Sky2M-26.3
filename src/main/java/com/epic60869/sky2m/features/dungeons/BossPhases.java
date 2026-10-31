package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Boss Phases: one HUD for the F1-F7/M1-M7 boss fights with the current phase and its running time, the
 * phases already done, attack timers, and your best time for each phase and the whole fight (saved in
 * {@code sky2m/boss-phases.json}), plus optional title/sound/party alerts when the phase changes.
 * <p>
 * Phases change on the bosses' own lines. The lines and the "☠ Defeated" end line are the ones Odin's SplitsManager
 * uses (https://github.com/odtheking/Odin, BSD-3-Clause, as in {@link DungeonFeatures}); the F6 Sadan phase, the F7
 * terminal sections ("(7/7)" completions) and the M7 Wither King start follow SkyHanni's DungeonBossApi
 * (https://github.com/hannibal002/SkyHanni, LGPL-2.1). The attack timers are the fixed intervals already used here:
 * Livid's 340 tick invulnerability ({@link LividTimer}, Odin), Storm's 20 tick pillars, his 55 tick lightning (95 before the 2026 dungeon update) and
 * Goldor's 60 tick death tick ({@link DungeonFeatures}'s tick timers, Odin's TickTimers).
 * <p>
 * A phase only gets a record when the fight went through its start and the next phase's start (or the end) with none
 * missed; the whole fight only when it was seen from its first line.
 */
public final class BossPhases {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("boss-phases.json");
    private static final long KEEP_MS = 15_000;

    private static final Pattern DEFEATED = Pattern.compile("^\\s*☠ Defeated .+ in ");
    /** SkyHanni's goldorTerminalPattern, on stripped text. */
    private static final Pattern SECTION = Pattern.compile("^\\w{1,16} (?:activated|completed) a (?:lever|terminal|device)! \\((\\d)/(\\d)\\)$");
    private static final String STORM_DEATH = "[BOSS] Storm: I should have known that I stood no chance.";
    private static final String STORM_PY_1 = "[BOSS] Storm: ENERGY HEED MY CALL!";
    private static final String STORM_PY_2 = "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!";
    private static final String CORE = "The Core entrance is opening!";
    private static final int LIVID_TICKS = 340, PILLAR_TICKS = 20, PY_TICKS = 55, GOLDOR_TICKS = 60;

    /** A phase starting on an exact chat line, or (line null) on a terminal section being finished. */
    private record PhaseDef(String name, String line, boolean masterOnly, String altLine) {
        PhaseDef(String name, String line, boolean masterOnly) { this(name, line, masterOnly, null); }

        boolean matches(String text) {
            return text.equals(line) || text.equals(altLine);
        }
    }

    private record Boss(String name, Predicate<FeatureConfigs.BossPhases> enabled, List<PhaseDef> phases) {}

    private static PhaseDef p(String name, String line) { return new PhaseDef(name, line, false); }

    private static final Map<Character, Boss> BOSSES = Map.of(
        '1', new Boss("Bonzo", c -> c.bonzo, List.of(
            p("Bonzo 1", "[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable."),
            p("Bonzo 2", "[BOSS] Bonzo: Oh I'm dead!"))),
        '2', new Boss("Scarf", c -> c.scarf, List.of(
            p("Minions", "[BOSS] Scarf: This is where the journey ends for you, Adventurers."),
            p("Scarf", "[BOSS] Scarf: Did you forget? I was taught by the best! Let's dance."))),
        '3', new Boss("The Professor", c -> c.professor, List.of(
            p("Guardians", "[BOSS] The Professor: I was burdened with terrible news recently..."),
            p("Professor", "[BOSS] The Professor: Oh? You found my Guardians' one weakness?"),
            p("Professor 2", "[BOSS] The Professor: What?! My Guardian power is unbeatable!"))),
        '4', new Boss("Thorn", c -> c.thorn, List.of(
            p("Thorn", "[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!"))),
        '5', new Boss("Livid", c -> c.livid, List.of(
            p("Livid", "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows."))),
        '6', new Boss("Sadan", c -> c.sadan, List.of(
            p("Terracottas", "[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!"),
            p("Giants", "[BOSS] Sadan: ENOUGH!"),
            p("Sadan", "[BOSS] Sadan: You did it. I understand now, you have earned my respect."))),
        '7', new Boss("Necron", c -> c.necron, List.of(
            p("Maxor", "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"),
            p("Storm", "[BOSS] Storm: Pathetic Maxor, just like expected."),
            p("Terminals 1", "[BOSS] Goldor: Who dares trespass into my domain?"),
            p("Terminals 2", null),
            p("Terminals 3", null),
            p("Terminals 4", null),
            p("Goldor", CORE),
            p("Necron", "[BOSS] Necron: You went further than any human before, congratulations."),
            // Since Hypixel's 2026 dungeon update the Wither King's first line starts P5 (Necron's last line is gone).
            new PhaseDef("Wither King", "[BOSS] Wither King: You... again?", true, "[BOSS] Necron: All this, for nothing..."))));

    private record Done(String name, long ms, Long record) {}

    private static Map<String, Map<String, Long>> records = new HashMap<>();
    private static final List<Done> done = new CopyOnWriteArrayList<>();
    private static Boss boss;
    private static String floor = "";
    private static int index = -1;
    private static boolean clean, phaseClean;
    private static long fightStart, phaseStart, endedAt;
    private static long serverTicks, lividStart = -1, stormStart = -1, pyStart = -1, goldorStart = -1;
    private static Level lastLevel;

    private BossPhases() {}

    private static FeatureConfigs.BossPhases config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.bossPhases;
    }

    public static void init() {
        Sky2MChat.onChat(message -> onChat(message.text()));
        ServerTickCallback.EVENT.register(() -> serverTicks++);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == lastLevel) return;
            lastLevel = mc.level;
            reset();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root)
                .then(ClientCommands.literal("resetbossrecords").executes(context -> {
                    records().clear();
                    save();
                    Sky2MAlerts.chat(Component.literal("Boss phase records cleared.").withStyle(ChatFormatting.GRAY));
                    return 1;
                })));
        });
        Sky2MHuds.setting("boss_phases", () -> config() != null && config().enabled);
        Sky2MHuds.register("boss_phases", "Boss Phases",
            () -> config() != null && config().enabled && boss != null && (endedAt == 0 || System.currentTimeMillis() - endedAt < KEEP_MS),
            BossPhases::lines,
            List.of(
                Component.literal("Necron ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD).append(Component.literal("(M7)").withStyle(ChatFormatting.GRAY)),
                phaseLine("Maxor", 38_100, 40_150L, false),
                phaseLine("Storm", 62_300, 58_000L, false),
                phaseLine("Terminals 1", 12_400, null, true),
                Component.literal("Storm pillars: ").withStyle(ChatFormatting.GRAY).append(Component.literal("0.45s").withStyle(ChatFormatting.WHITE)),
                phaseLine("Total", 112_800, 120_000L, false)),
            8, 600);
    }

    private static void reset() {
        boss = null;
        floor = "";
        index = -1;
        done.clear();
        endedAt = 0;
        lividStart = stormStart = pyStart = goldorStart = -1;
    }

    private static boolean applies(PhaseDef def) {
        return !def.masterOnly() || floor.startsWith("M");
    }

    private static void onChat(String text) {
        FeatureConfigs.BossPhases config = config();
        if (config == null || !config.enabled && !config.alertTitle && !config.alertSound && !config.partyMessage) return;
        if (!Sky2MLocation.inDungeon()) return;
        long now = System.currentTimeMillis();

        // Attack timers (fixed intervals from Odin's LividSolver and TickTimers).
        if (STORM_DEATH.equals(text)) { goldorStart = serverTicks; stormStart = -1; }
        else if (CORE.equals(text) || text.startsWith("[BOSS] Necron:")) { goldorStart = -1; stormStart = -1; }
        if (pyStart < 0 && (STORM_PY_1.equals(text) || STORM_PY_2.equals(text))) pyStart = serverTicks;

        if (boss != null && endedAt == 0 && index >= 0 && DEFEATED.matcher(text).find()) {
            finish(now, config);
            return;
        }

        String currentFloor = Sky2MLocation.dungeonFloor();
        if (currentFloor.length() != 2) return;
        Boss candidate = BOSSES.get(currentFloor.charAt(1));
        if (candidate == null || !candidate.enabled().test(config)) return;
        if (boss != null && boss != candidate) reset();
        floor = currentFloor;

        List<PhaseDef> phases = candidate.phases();
        if (boss == candidate && index >= 0 && endedAt == 0) {
            Matcher section = SECTION.matcher(text);
            // SkyHanni: a section ends on its last lever/terminal/device ("(7/7)"); Terminals 4 ends on the core opening.
            if (section.matches() && section.group(1).equals(section.group(2))
                && index + 1 < phases.size() && phases.get(index + 1).line() == null) {
                advance(candidate, index + 1, now, config);
                return;
            }
        }
        for (int j = Math.max(0, index + 1); j < phases.size(); j++) {
            PhaseDef def = phases.get(j);
            if (def.line() == null || !def.matches(text) || !applies(def)) continue;
            if (boss == candidate && endedAt != 0) return; // a finished fight doesn't restart in the same world
            advance(candidate, j, now, config);
            return;
        }
    }

    private static void advance(Boss candidate, int next, long now, FeatureConfigs.BossPhases config) {
        Done finished = null;
        if (boss == null || index < 0) {
            boss = candidate;
            done.clear();
            fightStart = now;
            clean = next == 0;
            endedAt = 0;
        } else {
            boolean consecutive = next == index + 1;
            finished = complete(now, phaseClean && consecutive, config);
            if (!consecutive) clean = false;
        }
        index = next;
        phaseStart = now;
        phaseClean = true;
        String name = boss.phases().get(next).name();
        BossNotes.onPhase(floor, name);
        if (name.equals("Livid")) lividStart = serverTicks;
        if (name.equals("Storm")) stormStart = serverTicks;
        if (name.equals("Terminals 1")) { goldorStart = serverTicks; stormStart = -1; }

        if (config.alertTitle || config.alertSound) {
            Component subtitle = finished == null ? null
                : Component.literal(finished.name() + " took " + format(finished.ms())).withStyle(ChatFormatting.GRAY);
            if (config.alertTitle) Sky2MAlerts.title(Component.literal(name).withStyle(ChatFormatting.GOLD), subtitle, config.alertSound);
            else Sky2MAlerts.ding();
        }
        if (config.partyMessage && finished != null) party(finished.name() + " took " + format(finished.ms()) + " (" + floor + ")");
    }

    /** Ends the current phase; returns it as done. Records it when {@code valid}. */
    private static Done complete(long now, boolean valid, FeatureConfigs.BossPhases config) {
        String name = boss.phases().get(index).name();
        long ms = now - phaseStart;
        Long old = record(name);
        if (valid) checkRecord(name, ms, config);
        Done d = new Done(name, ms, old);
        done.add(d);
        return d;
    }

    private static void finish(long now, FeatureConfigs.BossPhases config) {
        boolean lastPhase = true;
        List<PhaseDef> phases = boss.phases();
        for (int j = index + 1; j < phases.size(); j++) if (applies(phases.get(j))) lastPhase = false;
        Done finished = complete(now, phaseClean && lastPhase, config);
        endedAt = now;
        long total = now - fightStart;
        if (clean && lastPhase) checkRecord("Total", total, config);
        if (config.partyMessage) party(finished.name() + " took " + format(finished.ms()) + ", " + boss.name() + " took " + format(total) + " (" + floor + ")");
    }

    private static Long record(String name) {
        Map<String, Long> floorRecords = records().get(floor);
        return floorRecords == null ? null : floorRecords.get(name);
    }

    private static void checkRecord(String name, long ms, FeatureConfigs.BossPhases config) {
        if (ms < 500) return;
        Long old = record(name);
        if (old != null && ms >= old) return;
        records().computeIfAbsent(floor, k -> new HashMap<>()).put(name, ms);
        save();
        if (old != null && config.recordMessages) {
            Sky2MAlerts.chat(Component.literal("New record! ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal(floor + " " + (name.equals("Total") ? boss.name() : name) + " ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("took ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(format(ms)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (" + format(old - ms) + " faster, old " + format(old) + ")").withStyle(ChatFormatting.GRAY)));
        }
    }

    private static void party(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.getConnection() != null) mc.getConnection().sendCommand("pc " + message);
        });
    }

    // ----- HUD -----

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        FeatureConfigs.BossPhases config = config();
        Boss b = boss;
        if (config == null || b == null || index < 0) return out;
        long now = System.currentTimeMillis();
        out.add(Component.literal(b.name() + " ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
            .append(Component.literal("(" + floor + ")").withStyle(ChatFormatting.GRAY)));
        if (config.showPrevious) {
            List<Done> list = done;
            int shown = endedAt == 0 ? list.size() : list.size() - 1;
            for (int i = 0; i < shown; i++) {
                Done d = list.get(i);
                out.add(phaseLine(d.name(), d.ms(), config.showRecords ? d.record() : null, false));
            }
        }
        if (endedAt == 0) {
            String name = b.phases().get(index).name();
            out.add(phaseLine(name, now - phaseStart, config.showRecords ? record(name) : null, true));
        } else if (!done.isEmpty()) {
            Done last = done.getLast();
            out.add(phaseLine(last.name(), last.ms(), config.showRecords ? last.record() : null, false));
        }
        if (config.showCooldowns && endedAt == 0) cooldownLines(out, b.phases().get(index).name());
        long total = (endedAt == 0 ? now : endedAt) - fightStart;
        out.add(phaseLine("Total", total, config.showRecords && clean ? record("Total") : null, false));
        return out;
    }

    private static void cooldownLines(List<Component> out, String phase) {
        if (phase.equals("Livid") && lividStart >= 0 && serverTicks - lividStart < LIVID_TICKS) {
            out.add(timer("Livid invulnerable: ", LIVID_TICKS - (serverTicks - lividStart)));
        }
        if (phase.equals("Storm") && stormStart >= 0) {
            out.add(timer("Storm pillars: ", countdown(serverTicks - stormStart, PILLAR_TICKS)));
        }
        if (pyStart >= 0 && serverTicks - pyStart <= PY_TICKS) {
            out.add(timer("Storm lightning (PY): ", PY_TICKS - (serverTicks - pyStart)));
        }
        if (goldorStart >= 0 && phase.startsWith("Terminals")) {
            out.add(timer("Goldor death tick: ", countdown(serverTicks - goldorStart, GOLDOR_TICKS)));
        }
    }

    private static long countdown(long elapsed, int period) {
        return elapsed <= 0 ? period : (period - elapsed % period) % period;
    }

    private static Component timer(String label, long ticks) {
        return Component.literal(label).withStyle(ChatFormatting.AQUA)
            .append(Component.literal(String.format(Locale.US, "%.2fs", Math.max(0, ticks) / 20f)).withStyle(ChatFormatting.WHITE));
    }

    private static Component phaseLine(String name, long ms, Long record, boolean current) {
        MutableComponent line = Component.literal((current ? "> " : "") + name + ": ").withStyle(current ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
        ChatFormatting colour = record == null ? ChatFormatting.WHITE : ms < record ? ChatFormatting.GREEN : current ? ChatFormatting.WHITE : ChatFormatting.RED;
        line.append(Component.literal(format(ms)).withStyle(colour));
        if (record != null) line.append(Component.literal(" (" + format(record) + ")").withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    private static String format(long ms) {
        if (ms < 60_000) return String.format(Locale.US, "%.2fs", ms / 1000.0);
        return String.format(Locale.US, "%dm %.1fs", ms / 60_000, (ms % 60_000) / 1000.0);
    }

    // ----- Storage -----

    private static boolean recordsLoaded;

    /** The saved records, read from disk on first use rather than at start-up. */
    private static Map<String, Map<String, Long>> records() {
        if (!recordsLoaded) {
            recordsLoaded = true;
            load();
        }
        return records;
    }

    private static void load() {
        try {
            if (!Files.exists(FILE)) return;
            Map<String, Map<String, Long>> loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Map<String, Long>>>() {}.getType());
            if (loaded != null) records = new HashMap<>(loaded);
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(records), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
