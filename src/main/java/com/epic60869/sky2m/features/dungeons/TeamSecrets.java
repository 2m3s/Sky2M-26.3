package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.profileviewer.ProfileApi;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Team Secrets, like Odin's Secrets Counter and NoammAddons': each teammate's total secrets (live, from
 * {@link ProfileApi#secrets}, not the profile's dungeons.secrets, which only updates once Hypixel saves the profile) is
 * loaded when they show up in the dungeon tab, and again a few seconds after the run ends ("> EXTRA STATS <"); the
 * difference is what they found this run, shown in chat with their class in its colour. Hypixel's API can lag behind and
 * the proxy can refuse lookups, so anyone still on 0 or unknown is looked up again a few times over the next few minutes,
 * even if you've left the dungeon by then, and a total that comes late is taken out of the next run's count.
 */
public final class TeamSecrets {
    private static final long FIRST_LOOK_MS = 4000;
    /** After the first look, for anyone still on 0 or unknown. Spaced out, as the proxy only allows about 10 lookups a minute. */
    private static final long[] LATER_LOOKS_MS = {20_000, 45_000, 90_000, 150_000, 240_000};
    /** A starting total that failed to load isn't tried again for this long. */
    private static final long START_RETRY_MS = 15_000;

    /** A finished run still being looked up: its own copy of everything, as the next run clears the rest. */
    private static final class Finished {
        final Map<String, Long> start;
        final Map<String, DungeonClass> classes;
        final Map<String, RoomClears.Counts> rooms;
        final Map<String, Integer> deaths;
        final Map<String, Long> found = new LinkedHashMap<>();
        final int run;
        int laterLooks;
        long nextAt;
        boolean loading;
        /** No more looks to come. */
        boolean done;
        /** Its numbers changed outside a dungeon or during another run, so they wait (see {@link #holdUpdate}). */
        boolean unsaid;
        /** Added to Teammate History (once, when its looks are done or the next run ends). */
        boolean recorded;
        /** The numbers as they were last said in chat. */
        Map<String, Long> said = new LinkedHashMap<>();
        /** The next run has ended: anything this one still finds isn't said any more (it would be a second list). */
        boolean closed;
        /** The last run's gains that waited for this one, said as one line under its list. */
        Component carried;

        Finished(Map<String, Long> start, Map<String, DungeonClass> classes, Map<String, RoomClears.Counts> rooms, Map<String, Integer> deaths, int run) {
            this.start = start;
            this.classes = classes;
            this.rooms = rooms;
            this.deaths = deaths;
            this.run = run;
        }
    }

    /** Total secrets per teammate when the run started (-1 while loading or if it failed). */
    private static final Map<String, Long> before = new ConcurrentHashMap<>();
    /** When a starting total that failed can be tried again. */
    private static final Map<String, Long> startRetryAt = new ConcurrentHashMap<>();
    /** Each teammate's class, kept after they leave the tab or turn into a ghost. */
    private static final Map<String, DungeonClass> classes = new LinkedHashMap<>();
    private static long showAt;
    private static int colourTicks;
    /** Goes up on every {@link #reset}, so a late look can tell the run has changed. */
    private static int runId;
    /** This run has ended, so its starting totals are done with. */
    private static boolean ended;
    private static Finished finished;

    private TeamSecrets() {}

    private static FeatureConfigs.TeamSecrets config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.teamSecrets;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MChat.onChat(message -> {
            FeatureConfigs.TeamSecrets c = config();
            if (c == null || !c.enabled || !Sky2MLocation.inDungeon()) return;
            if (message.text().trim().equals("> EXTRA STATS <") && !com.epic60869.sky2m.sim.roomsim.SimState.isActive() && !(before.isEmpty() && classes.isEmpty())) {
                showAt = System.currentTimeMillis() + FIRST_LOOK_MS;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            FeatureConfigs.TeamSecrets c = config();
            if (c == null || !c.enabled) return;
            if (Sky2MLocation.inDungeon() && showAt == 0 && !ended) watchPlayers();
            long now = System.currentTimeMillis();
            if (showAt != 0 && now >= showAt) {
                showAt = 0;
                runEnded();
            }
            if (finished != null && !finished.loading && !finished.done && finished.nextAt != 0 && now >= finished.nextAt) look(finished);
            if (finished != null && finished.unsaid && !holdUpdate(finished.run, runId, Sky2MLocation.inDungeon(), ended)) {
                sayUnsaid(finished);
                if (finished.done) finishUp(finished);
            }
        });
    }

    /**
     * An update waits outside a dungeon, and while a different run is going on. It's said in the dungeon it's for, or
     * when the next run ends (as a line under that run's list). Public for the game test.
     */
    public static boolean holdUpdate(int updateRun, int currentRun, boolean inDungeon, boolean currentEnded) {
        return !inDungeon || updateRun != currentRun && !currentEnded;
    }

    /** The end-of-run list itself waits only while a different run is going on, so it shows wherever you are. */
    private static boolean holdFirst(int updateRun, int currentRun, boolean inDungeon, boolean currentEnded) {
        return updateRun != currentRun && inDungeon && !currentEnded;
    }

    /** Says a held update, unless all it did was turn "?" into 0 (Hypixel's API not caught up yet). */
    private static void sayUnsaid(Finished run) {
        run.unsaid = false;
        if (!gains(run.said, run.found).isEmpty()) show(run, "Team secrets last run (updated):");
    }

    /** Who has more secrets than when they were last said, and above 0. Public for the game test. */
    public static List<String> gains(Map<String, Long> said, Map<String, Long> found) {
        return found.entrySet().stream().filter(e -> e.getValue() > 0 && e.getValue() > said.getOrDefault(e.getKey(), -1L))
            .map(Map.Entry::getKey).toList();
    }

    /** " Last run, updated: Name: 10, Name: 7", or null with no gains. */
    private static Component gainsLine(Finished run) {
        List<String> names = gains(run.said, run.found);
        if (names.isEmpty()) return null;
        MutableComponent out = Component.literal(" Last run, updated: ").withStyle(ChatFormatting.GRAY);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Integer colour = PlayerNameColour.of(name);
            if (i > 0) out.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            out.append(colour == null ? Component.literal(name).withStyle(ChatFormatting.WHITE) : Component.literal(name).withColor(colour))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(run.found.get(name))).withStyle(ChatFormatting.AQUA));
        }
        return out;
    }

    private static void finishUp(Finished run) {
        recordHistory(run);
        if (finished == run) finished = null;
    }

    private static void reset() {
        runId++;
        before.clear();
        startRetryAt.clear();
        classes.clear();
        showAt = 0;
        ended = false;
    }

    /** Loads the starting total of anyone new in the tab and remembers their class. */
    private static void watchPlayers() {
        DungeonPlayerManager.DungeonPlayer[] players = DungeonPlayerManager.getPlayers();
        if (players == null) return;
        for (DungeonPlayerManager.DungeonPlayer p : players) {
            if (p == null) continue;
            if (p.dungeonClass() != DungeonClass.UNKNOWN) classes.put(p.name(), p.dungeonClass());
            // Their rank colour, kept for the end of the run even if they've left the tab list.
            if (++colourTicks % 20 == 0) PlayerNameColour.of(p.name());
            String name = p.name();
            Long retryAt = startRetryAt.get(name);
            if (retryAt != null && System.currentTimeMillis() < retryAt) continue;
            if (before.putIfAbsent(name, -1L) == null) {
                secrets(name).whenComplete((total, error) -> {
                    if (error == null && total != null && total >= 0) {
                        before.replace(name, -1L, total);
                    } else {
                        startRetryAt.put(name, System.currentTimeMillis() + START_RETRY_MS);
                        before.remove(name, -1L);
                    }
                });
            }
        }
    }

    private static CompletableFuture<Long> secrets(String name) {
        return ProfileApi.secrets(name);
    }

    /** The run is over: takes a copy of it and has the first look. */
    private static void runEnded() {
        Map<String, Long> start = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : before.entrySet()) if (e.getValue() >= 0) start.put(e.getKey(), e.getValue());
        FeatureConfigs.TeamSecrets c = config();
        Finished run = new Finished(start, new LinkedHashMap<>(classes), c != null && c.roomsDone ? RoomClears.snapshot() : null, DeathTracker.deathsThisRun(), runId);
        // Anyone whose starting total never loaded still gets a line, with "?".
        for (String name : before.keySet()) run.found.put(name, -1L);
        for (String name : classes.keySet()) run.found.put(name, -1L);
        if (run.found.isEmpty()) return;
        ended = true;
        if (finished != null) {
            // One message at the end of a run: the last run's held gains go on a line under this run's list.
            if (finished.unsaid) run.carried = gainsLine(finished);
            finished.unsaid = false;
            finished.closed = true;
            recordHistory(finished);
        }
        finished = run;
        look(run);
    }

    /** Loads the totals of everyone who still needs it, and says so if that changed anything. */
    private static void look(Finished run) {
        boolean first = run.laterLooks == 0 && run.nextAt == 0;
        run.loading = true;
        Map<String, CompletableFuture<Long>> loads = new LinkedHashMap<>();
        for (String name : stillToLook(run.found)) {
            if (run.start.containsKey(name)) loads.put(name, secrets(name).exceptionally(t -> -1L));
        }
        CompletableFuture.allOf(loads.values().toArray(CompletableFuture[]::new)).whenComplete((v, t) -> Minecraft.getInstance().execute(() -> {
            run.loading = false;
            boolean raised = false;
            for (Map.Entry<String, CompletableFuture<Long>> e : loads.entrySet()) {
                long total = e.getValue().getNow(-1L);
                if (total < 0) continue;
                long count = Math.max(0, total - run.start.get(e.getKey()));
                raised |= count > run.found.get(e.getKey());
                run.found.put(e.getKey(), count);
                // The next run started from a total that was behind: this much of it is this run's, not that one's.
                if (run.run != runId) before.computeIfPresent(e.getKey(), (name, from) -> from >= 0 && from < total ? total : from);
            }
            if (run.closed) return;
            boolean inDungeon = Sky2MLocation.inDungeon();
            if (first) {
                if (holdFirst(run.run, runId, inDungeon, ended)) run.unsaid = true;
                else show(run, "Team secrets this run:");
            } else if (raised) {
                if (holdUpdate(run.run, runId, inDungeon, ended)) run.unsaid = true;
                else show(run, run.run == runId ? "Team secrets (updated):" : "Team secrets last run (updated):");
            }
            boolean more = run.laterLooks < LATER_LOOKS_MS.length && stillToLook(run.found).stream().anyMatch(run.start::containsKey);
            if (more) {
                run.nextAt = System.currentTimeMillis() + LATER_LOOKS_MS[run.laterLooks] - (run.laterLooks == 0 ? 0 : LATER_LOOKS_MS[run.laterLooks - 1]);
                run.laterLooks++;
            } else {
                run.done = true;
                // One that's still waiting to be said finishes when it has been; its numbers are final already, so
                // Teammate History gets them now (you may not play another run).
                if (finished == run && !run.unsaid) finishUp(run);
                else recordHistory(run);
            }
        }));
    }

    private static void recordHistory(Finished run) {
        if (run.recorded) return;
        run.recorded = true;
        TeammateHistory.record(new TeammateHistory.Run(run.found, run.rooms, run.deaths));
    }

    /** Who's worth another look: on 0, which is often Hypixel's API behind, or unknown. Public for the game test. */
    public static List<String> stillToLook(Map<String, Long> found) {
        return found.entrySet().stream().filter(e -> e.getValue() <= 0).map(Map.Entry::getKey).toList();
    }

    private static void show(Finished run, String title) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal(title).withStyle(ChatFormatting.GOLD)));
        for (Map.Entry<String, Long> e : run.found.entrySet()) {
            MutableComponent line = line(e.getKey(), run.classes.get(e.getKey()), e.getValue(), PlayerNameColour.of(e.getKey()));
            if (run.rooms != null) line.append(roomsPart(run.rooms.getOrDefault(e.getKey().toLowerCase(java.util.Locale.ROOT), new RoomClears.Counts(List.of(), List.of()))));
            mc.gui.hud.getChat().addClientSystemMessage(line);
        }
        run.said = new LinkedHashMap<>(run.found);
        if (run.carried != null) {
            mc.gui.hud.getChat().addClientSystemMessage(run.carried);
            run.carried = null;
        }
    }

    /** " | 3-5 rooms", and the rooms themselves on hover. Public for the game test. */
    public static Component roomsPart(RoomClears.Counts counts) {
        int solo = counts.solo().size();
        int stacked = counts.stacked().size();
        List<String> names = new ArrayList<>();
        counts.solo().forEach(room -> names.add(room + " (solo)"));
        counts.stacked().forEach(room -> names.add(room + " (stacked)"));
        Component hover = Component.literal(names.isEmpty() ? "No rooms" : String.join("\n", names));
        return Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY).append(
            Component.literal(RoomClears.rangeText(solo, stacked) + (solo + stacked == 1 ? " room" : " rooms"))
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW).withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(hover))));
    }

    /** " Name Tank: 12", the class in its colour; "?" when their profile couldn't be loaded. */
    public static MutableComponent line(String name, DungeonClass dungeonClass, long found) {
        return line(name, dungeonClass, found, null);
    }

    /** The same with the name in its rank colour ({@link PlayerNameColour}), white when that isn't known. */
    public static MutableComponent line(String name, DungeonClass dungeonClass, long found, Integer nameColour) {
        MutableComponent out = Component.literal(" ").append(nameColour == null
            ? Component.literal(name).withStyle(ChatFormatting.WHITE) : Component.literal(name).withColor(nameColour));
        if (dungeonClass != null && dungeonClass != DungeonClass.UNKNOWN) {
            out.append(Component.literal(" " + dungeonClass.displayName()).withColor(dungeonClass.color() & 0xFFFFFF));
        }
        out.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        out.append(found < 0 ? Component.literal("?").withStyle(ChatFormatting.DARK_GRAY) : Component.literal(String.valueOf(found)).withStyle(ChatFormatting.AQUA));
        return out;
    }
}
