package com.epic60869.sky2m.features.dungeons.party;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the party knows about the dungeon run, whoever noticed it, ported from Killer560's Mod (MIT,
 * https://github.com/Killer560-OG/killer560s-mod, interop/PartyInteropState). Each fact keeps who said it and how
 * ({@link Source}), and a fact is only replaced by a source at least as trusted, or by anything once it's gone stale.
 * Flags (the Mimic is dead...) only ever turn on; counters only climb within a run, except the P3 terminal, device and
 * lever counts, which start again each section. Everything resets with the run.
 */
public final class PartyDungeonState {
    private static final long STALE_MS = 30_000;
    private static final int MAX_ROOMS = 64, MAX_LOG = 24;

    /** Where a fact came from, least trusted first. */
    public enum Source {
        CHAT("party chat"), SOCKET("another mod's party socket"), RELAY("a teammate's Sky2M"), SELF("this client");

        public final String label;

        Source(String label) {
            this.label = label;
        }
    }

    public enum Flag {
        MIMIC_KILLED("Mimic killed", "mimic"), PRINCE_KILLED("Prince killed", "prince"), BAT_KILLED("Bat killed", "bat"),
        BLOOD_OPENED("Blood opened", "blood_opened"), BLOOD_DONE("Blood done", "blood_done");

        public final String label, wire;

        Flag(String label, String wire) {
            this.label = label;
            this.wire = wire;
        }

        public static Flag ofWire(String wire) {
            for (Flag f : values()) if (f.wire.equals(wire)) return f;
            return null;
        }
    }

    public enum Counter {
        SECRETS_FOUND("Secrets", "secrets"), CRYPTS("Crypts", "crypts"), DEATHS("Deaths", "deaths"),
        TERMINALS_DONE("Terminals", "terminals"), DEVICES_DONE("Devices", "devices"), LEVERS_DONE("Levers", "levers");

        public final String label, wire;

        Counter(String label, String wire) {
            this.label = label;
            this.wire = wire;
        }

        public static Counter ofWire(String wire) {
            for (Counter c : values()) if (c.wire.equals(wire)) return c;
            return null;
        }

        boolean section() {
            return this == TERMINALS_DONE || this == DEVICES_DONE || this == LEVERS_DONE;
        }
    }

    public record Fact<T>(T value, Source source, String reporter, long at) {}

    public record RoomSecrets(int found, int total) {}

    /** A teammate's Melody: which row of the band they're on (1 to 4) and whether it's done. */
    public record Melody(int row, int rows, boolean done) {}

    private static final Object LOCK = new Object();
    private static final Map<Flag, Fact<Boolean>> FLAGS = new EnumMap<>(Flag.class);
    private static final Map<Counter, Fact<Integer>> COUNTERS = new EnumMap<>(Counter.class);
    private static final Map<String, Fact<RoomSecrets>> ROOMS = new LinkedHashMap<>();
    private static final Map<String, Fact<Long>> DRAGONS = new LinkedHashMap<>();
    private static final Map<String, Fact<Melody>> MELODY = new LinkedHashMap<>();
    private static final Deque<String> LOG = new ArrayDeque<>();
    private static int sectionDone = -1, sectionTotal = -1;

    private PartyDungeonState() {}

    // ------------------------------------------------------------------------------------------------ writes

    /** True if this changed what's believed. Only "it happened" is ever accepted. */
    public static boolean offerFlag(Flag flag, Source source, String reporter) {
        if (flag == null || source == null) return false;
        synchronized (LOCK) {
            Fact<Boolean> held = FLAGS.get(flag);
            if (held != null && held.source().ordinal() >= source.ordinal()) return false;
            FLAGS.put(flag, new Fact<>(true, source, reporter, System.currentTimeMillis()));
            if (held == null) log(flag.label + " (" + who(source, reporter) + ")");
            return held == null;
        }
    }

    public static boolean offerCounter(Counter counter, int value, int total, Source source, String reporter) {
        if (counter == null || source == null || value < 0 || value > 999) return false;
        synchronized (LOCK) {
            if (counter.section() && total > 0) {
                boolean newSection = sectionTotal > 0 && (total != sectionTotal || (sectionDone >= sectionTotal && value < sectionDone));
                if (newSection) {
                    COUNTERS.remove(Counter.TERMINALS_DONE);
                    COUNTERS.remove(Counter.DEVICES_DONE);
                    COUNTERS.remove(Counter.LEVERS_DONE);
                    sectionDone = value;
                } else {
                    sectionDone = Math.max(sectionDone, value);
                }
                sectionTotal = total;
            }
            Fact<Integer> held = COUNTERS.get(counter);
            long now = System.currentTimeMillis();
            if (held != null) {
                boolean stale = now - held.at() > STALE_MS;
                if (!stale && source.ordinal() < held.source().ordinal()) return false;
                if (!stale && value < held.value()) return false;
                if (value == held.value() && source.ordinal() <= held.source().ordinal()) {
                    COUNTERS.put(counter, new Fact<>(value, held.source(), held.reporter(), now));
                    return false;
                }
            }
            COUNTERS.put(counter, new Fact<>(value, source, reporter, now));
            log(counter.label + " " + value + " (" + who(source, reporter) + ")");
            return true;
        }
    }

    public static boolean offerRoomSecrets(String room, int found, int total, Source source, String reporter) {
        String key = room == null ? "" : room.trim().replaceAll("\\s+", " ");
        if (key.isEmpty() || key.length() > 64 || source == null || found < 0 || found > 99) return false;
        synchronized (LOCK) {
            Fact<RoomSecrets> held = ROOMS.get(key);
            long now = System.currentTimeMillis();
            if (held != null) {
                boolean stale = now - held.at() > STALE_MS;
                if (!stale && source.ordinal() < held.source().ordinal()) return false;
                if (!stale && found < held.value().found()) return false;
                if (found == held.value().found() && (total < 0 || total == held.value().total())) return false;
            }
            int keptTotal = total >= 0 ? total : held == null ? -1 : held.value().total();
            ROOMS.put(key, new Fact<>(new RoomSecrets(found, keptTotal), source, reporter, now));
            while (ROOMS.size() > MAX_ROOMS) ROOMS.remove(ROOMS.keySet().iterator().next());
            log(key + " secrets " + found + (keptTotal >= 0 ? "/" + keptTotal : "") + " (" + who(source, reporter) + ")");
            return true;
        }
    }

    public static boolean offerDragonSpawn(String colour, Source source, String reporter) {
        String key = colour == null ? "" : colour.trim().toLowerCase(Locale.ROOT);
        if (!List.of("red", "orange", "green", "blue", "purple").contains(key) || source == null) return false;
        synchronized (LOCK) {
            Fact<Long> held = DRAGONS.get(key);
            long now = System.currentTimeMillis();
            if (held != null && held.source().ordinal() >= source.ordinal() && now - held.at() < STALE_MS) return false;
            DRAGONS.put(key, new Fact<>(now, source, reporter, now));
            log(key + " dragon spawning (" + who(source, reporter) + ")");
            return true;
        }
    }

    /** A player's Melody progress (their own reading of their own terminal, however it reached us). */
    public static boolean offerMelody(String player, int row, int rows, boolean done, Source source) {
        if (player == null || !player.matches("\\w{1,16}") || row < 0 || row > 5 || rows < 3 || rows > 4 || source == null) return false;
        synchronized (LOCK) {
            String key = player.toLowerCase(Locale.ROOT);
            Fact<Melody> held = MELODY.get(key);
            long now = System.currentTimeMillis();
            if (held != null && now - held.at() < STALE_MS && held.source().ordinal() > source.ordinal()) return false;
            MELODY.put(key, new Fact<>(new Melody(row, rows, done), source, player, now));
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------ reads

    public static boolean flag(Flag flag) {
        synchronized (LOCK) {
            return FLAGS.containsKey(flag);
        }
    }

    public static Fact<Boolean> flagFact(Flag flag) {
        synchronized (LOCK) {
            return FLAGS.get(flag);
        }
    }

    public static Fact<Integer> counter(Counter counter) {
        synchronized (LOCK) {
            return COUNTERS.get(counter);
        }
    }

    public static Map<String, Fact<RoomSecrets>> rooms() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(ROOMS);
        }
    }

    public static Map<String, Fact<Long>> dragons() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(DRAGONS);
        }
    }

    /** Everyone's Melody, by lower-case name, newest kept. */
    public static Map<String, Fact<Melody>> melody() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(MELODY);
        }
    }

    public static List<String> log(int limit) {
        synchronized (LOCK) {
            List<String> out = new ArrayList<>();
            for (String line : LOG) {
                if (out.size() >= limit) break;
                out.add(line);
            }
            return out;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            FLAGS.clear();
            COUNTERS.clear();
            ROOMS.clear();
            DRAGONS.clear();
            MELODY.clear();
            LOG.clear();
            sectionDone = sectionTotal = -1;
        }
    }

    private static void log(String text) {
        LOG.addFirst(text);
        while (LOG.size() > MAX_LOG) LOG.removeLast();
    }

    private static String who(Source source, String reporter) {
        return source.label + (reporter == null || reporter.isBlank() ? "" : ", " + reporter);
    }
}
