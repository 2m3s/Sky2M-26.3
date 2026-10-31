package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.social.Sky2MPlayerNotes;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.misc.chat.PartyCommands;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Party Finder history: everyone who joined your groups (how often, when last, how often you kicked them) and, from
 * Run History, the dungeon runs you played together. When someone joins again you see "played with before": runs
 * together, how many finished, your best together and when you last played. /s2 pfhistory lists recent players;
 * /s2 pfhistory <name> shows one. Kept in config/sky2m/pf-history.json (runs come from Run History's own file).
 */
public final class PfHistory {
    public static final class Seen {
        public String name;
        public int joins;
        public int kicks;
        public long firstSeen;
        public long lastSeen;
        /** When you last kicked them (0 for kicks counted before this was saved). */
        public long lastKicked;
    }

    /** One flag per player per join, though a Party Finder join can also show "joined the party". */
    private static final long FLAG_GAP_MS = 30_000L;
    /** A kick this long after their last Party Finder join isn't a kick from a Party Finder group, so no auto-note. */
    private static final long PF_KICK_WINDOW_MS = 3 * 3_600_000L;
    private static final Map<String, Long> FLAGGED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Runs played with someone, from Run History. */
    record Together(int runs, int completed, RunHistory.Run best, RunHistory.Run last) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Seen> seen;

    private PfHistory() {}

    public static void init() {
        // Party joins and confirmed kicks come from PartyCommands' party tracking (no second parser for them).
        PartyCommands.onMemberJoined(PfHistory::flagIfKicked);
        PartyCommands.onMemberKicked(PfHistory::onKicked);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("pfhistory")
                    .executes(c -> {
                        listRecent();
                        return 1;
                    })
                    .then(ClientCommands.argument("player", StringArgumentType.word()).executes(c -> {
                        String name = StringArgumentType.getString(c, "player");
                        say(describe(name, true));
                        return 1;
                    }))));
            }
        });
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("pf-history.json");
    }

    private static synchronized Map<String, Seen> seen() {
        if (seen == null) {
            seen = new LinkedHashMap<>();
            try {
                if (Files.exists(file())) {
                    Map<String, Seen> loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Map<String, Seen>>() {}.getType());
                    if (loaded != null) loaded.forEach((k, v) -> { if (v != null) seen.put(k.toLowerCase(Locale.ROOT), v); });
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read pf-history.json: " + e.getMessage());
            }
        }
        return seen;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(seen()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save pf-history.json: " + e.getMessage());
        }
    }

    /** Someone joined your group from the Party Finder: counts it, and says if you've played with them before. */
    static void onJoined(String name, boolean announce) {
        Seen s;
        boolean before;
        synchronized (PfHistory.class) {
            s = seen().computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Seen());
            before = s.joins > 0;
            s.name = name;
            if (s.firstSeen == 0) s.firstSeen = System.currentTimeMillis();
            s.joins++;
            s.lastSeen = System.currentTimeMillis();
        }
        save();
        flagIfKicked(name);
        if (!announce) return;
        Together together = together(name);
        if (before || together.runs() > 0) say(describe(name, false));
    }

    /**
     * Hypixel confirmed a kick you sent (auto kick, a [KICK] button or /p kick). Only players who joined one of your
     * Party Finder groups are tracked. With Auto-Note Kicks, the first kick also leaves a note unless there is one.
     */
    static void onKicked(String name) {
        Seen s;
        boolean first;
        long now = System.currentTimeMillis();
        synchronized (PfHistory.class) {
            s = seen().get(name.toLowerCase(Locale.ROOT));
            if (s == null) return;
            s.kicks++;
            s.lastKicked = now;
            first = s.kicks == 1;
        }
        save();
        FeatureConfigs.PartyFinderStats c = config();
        if (!first || c == null || !c.autoNoteKicks || now - s.lastSeen > PF_KICK_WINDOW_MS) return;
        String floor = PartyFinderStats.currentFloorKey();
        String date = new java.text.SimpleDateFormat("MMM d, yyyy", Locale.US).format(new java.util.Date(now));
        String text = "Kicked from " + (floor == null ? "a Party Finder group" : floor) + " on " + date;
        if (Sky2MPlayerNotes.addIfAbsent(s.name, text)) {
            say(Component.literal("Noted " + s.name + ": " + text + ".").withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * "⚠ Name — you kicked them 2× before (last: 3 days ago)" with their note, [Kick] and [History], when someone
     * you kicked before joins your Party Finder group or your party.
     */
    static void flagIfKicked(String name) {
        FeatureConfigs.PartyFinderStats c = config();
        if (c == null || !c.flagKicked) return;
        Seen s;
        synchronized (PfHistory.class) {
            s = seen().get(name.toLowerCase(Locale.ROOT));
        }
        if (s == null || s.kicks < 1) return;
        long now = System.currentTimeMillis();
        Long last = FLAGGED.put(name.toLowerCase(Locale.ROOT), now);
        if (last != null && now - last < FLAG_GAP_MS) return;
        MutableComponent out = Component.literal("⚠ " + name).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
            .append(Component.literal(" — you kicked them " + s.kicks + "× before"
                + (s.lastKicked > 0 ? " (last: " + agoWords(s.lastKicked) + ")" : "")).withStyle(st -> st.withColor(ChatFormatting.YELLOW).withBold(false)));
        String note = Sky2MPlayerNotes.note(name);
        if (note != null) {
            out.append(Component.literal("  Note: ").withStyle(st -> st.withColor(ChatFormatting.GOLD).withBold(false)))
                .append(Component.literal(note).withStyle(st -> st.withColor(ChatFormatting.WHITE).withBold(false)));
        }
        out.append(Component.literal("  "));
        out.append(Component.literal("[Kick]").withStyle(st -> st.withColor(ChatFormatting.RED).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("/p kick " + name))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Kick " + name + " from the party")))));
        out.append(Component.literal(" "));
        out.append(Component.literal("[History]").withStyle(st -> st.withColor(ChatFormatting.AQUA).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("/s2 pfhistory " + name))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Show everything about " + name)))));
        say(out);
    }

    private static FeatureConfigs.PartyFinderStats config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.partyFinderStats;
    }

    /** Runs in Run History with {@code name} in the party. */
    static Together together(String name) {
        int runs = 0, completed = 0;
        RunHistory.Run best = null, last = null;
        for (RunHistory.Run run : RunHistory.runs()) {
            boolean with = false;
            for (RunHistory.Member m : run.party) if (m.name() != null && m.name().equalsIgnoreCase(name)) with = true;
            if (!with) continue;
            runs++;
            if (last == null) last = run; // newest first
            if (!run.completed) continue;
            completed++;
            if (best == null || run.effectiveScore() > best.effectiveScore()
                || run.effectiveScore() == best.effectiveScore() && run.totalMs < best.totalMs) best = run;
        }
        return new Together(runs, completed, best, last);
    }

    /** "Played with Name before: 5 runs (4 finished), best M5 S+ 3:12, last 2d ago · joined 7× · kicked 1×". */
    static MutableComponent describe(String name, boolean always) {
        Seen s;
        synchronized (PfHistory.class) {
            s = seen().get(name.toLowerCase(Locale.ROOT));
        }
        Together t = together(name);
        if (s == null && t.runs() == 0) {
            return Component.literal(always ? "You haven't played with " + name + " (or Run History is off)." : "").withStyle(ChatFormatting.GRAY);
        }
        MutableComponent out = Component.literal("Played with " + name + " before: ").withStyle(ChatFormatting.AQUA);
        List<String> parts = new ArrayList<>();
        if (t.runs() > 0) {
            parts.add(t.runs() + " run" + (t.runs() == 1 ? "" : "s") + " (" + t.completed() + " finished)");
            if (t.best() != null) {
                String rank = t.best().effectiveRank();
                parts.add("best " + t.best().floor + (rank == null ? "" : " " + rank) + " " + clock(t.best().totalMs));
            }
            if (t.last() != null) parts.add("last " + ago(t.last().endedAt > 0 ? t.last().endedAt : t.last().startedAt));
        } else {
            parts.add("no runs together in Run History");
        }
        if (s != null) {
            parts.add("joined your groups " + s.joins + "×");
            if (s.kicks > 0) parts.add("kicked " + s.kicks + "×");
        }
        out.append(Component.literal(String.join(" · ", parts)).withStyle(ChatFormatting.WHITE));
        // On a join the note shows by itself (Sky2MPlayerNotes); /s2 pfhistory <name> includes it.
        String note = always ? com.epic60869.sky2m.social.Sky2MPlayerNotes.note(name) : null;
        if (note != null) out.append(Component.literal("  Note: ").withStyle(ChatFormatting.GOLD)).append(Component.literal(note).withStyle(ChatFormatting.YELLOW));
        return out;
    }

    private static void listRecent() {
        List<Seen> recent;
        synchronized (PfHistory.class) {
            recent = new ArrayList<>(seen().values());
        }
        recent.sort(Comparator.comparingLong((Seen s) -> s.lastSeen).reversed());
        if (recent.isEmpty()) {
            say(Component.literal("Nobody has joined your Party Finder groups yet.").withStyle(ChatFormatting.GRAY));
            return;
        }
        say(Component.literal("Recent Party Finder players (click for details):").withStyle(ChatFormatting.AQUA));
        for (Seen s : recent.subList(0, Math.min(15, recent.size()))) {
            Together t = together(s.name);
            say(Component.literal(s.name).withStyle(st -> st.withColor(ChatFormatting.WHITE)
                    .withClickEvent(new ClickEvent.RunCommand("/s2 pfhistory " + s.name))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Show everything about " + s.name))))
                .append(Component.literal("  " + ago(s.lastSeen) + " · joined " + s.joins + "× · " + t.runs() + " runs together"
                    + (s.kicks > 0 ? " · kicked " + s.kicks + "×" : "")).withStyle(ChatFormatting.GRAY)));
        }
    }

    private static String clock(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static String ago(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60_000L);
        if (minutes < 60) return minutes + "m ago";
        if (minutes < 48 * 60) return minutes / 60 + "h ago";
        return minutes / (60 * 24) + "d ago";
    }

    /** "3 days ago", "5 hours ago", "just now": the long form for the kicked-before flag. */
    private static String agoWords(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 48) return hours + (hours == 1 ? " hour ago" : " hours ago");
        return hours / 24 + " days ago";
    }

    private static void say(Component text) {
        if (text.getString().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(text));
        });
    }
}
