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
 * difference is what they found this run, shown in chat with their class in its colour. Hypixel's API can lag behind,
 * so anyone still on 0 is looked up once more a bit later.
 */
public final class TeamSecrets {
    private static final long FIRST_LOOK_MS = 4000;
    private static final long SECOND_LOOK_MS = 30000;

    /** Total secrets per teammate when the run started (-1 while loading or if it failed). */
    private static final Map<String, Long> before = new ConcurrentHashMap<>();
    /** Each teammate's class, kept after they leave the tab or turn into a ghost. */
    private static final Map<String, DungeonClass> classes = new LinkedHashMap<>();
    private static long showAt, retryAt;
    private static int colourTicks;
    /** Goes up on every {@link #reset}, so a look that was still loading can tell the run has changed. */
    private static int runId;
    private static Map<String, Long> shown;
    /** This run's numbers for Teammate History, held while a second look can still raise someone's secrets. */
    private static TeammateHistory.Run historyRun;

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
            if (message.text().trim().equals("> EXTRA STATS <") && !com.epic60869.sky2m.sim.roomsim.SimState.isActive() && !before.isEmpty()) {
                showAt = System.currentTimeMillis() + FIRST_LOOK_MS;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            FeatureConfigs.TeamSecrets c = config();
            if (c == null || !c.enabled) return;
            if (Sky2MLocation.inDungeon() && showAt == 0 && shown == null) watchPlayers();
            long now = System.currentTimeMillis();
            if (showAt != 0 && now >= showAt) {
                showAt = 0;
                look(false);
            }
            if (retryAt != 0 && now >= retryAt) {
                retryAt = 0;
                look(true);
            }
        });
    }

    private static void reset() {
        if (historyRun != null) TeammateHistory.record(historyRun);
        historyRun = null;
        runId++;
        before.clear();
        classes.clear();
        showAt = retryAt = 0;
        shown = null;
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
            if (before.putIfAbsent(p.name(), -1L) == null) {
                String name = p.name();
                secrets(name).whenComplete((total, error) -> {
                    if (error == null && total != null && total >= 0) before.replace(name, -1L, total);
                    else before.remove(name, -1L);
                });
            }
        }
    }

    private static CompletableFuture<Long> secrets(String name) {
        return ProfileApi.secrets(name);
    }

    /** Loads everyone's totals now and prints this run's secrets; on the retry, only if someone's count went up. */
    private static void look(boolean retry) {
        // Kept here: requeueing or changing world while these load clears before (and starts the next run's state).
        Map<String, Long> start = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : before.entrySet()) if (e.getValue() >= 0) start.put(e.getKey(), e.getValue());
        if (start.isEmpty()) return;
        int run = runId;
        FeatureConfigs.TeamSecrets c = config();
        Map<String, RoomClears.Counts> rooms = c != null && c.roomsDone ? RoomClears.snapshot() : null;
        Map<String, Integer> deaths = DeathTracker.deathsThisRun();
        Map<String, CompletableFuture<Long>> loads = new LinkedHashMap<>();
        for (String name : start.keySet()) loads.put(name, secrets(name).exceptionally(t -> -1L));
        CompletableFuture.allOf(loads.values().toArray(CompletableFuture[]::new)).whenComplete((v, t) -> Minecraft.getInstance().execute(() -> {
            Map<String, Long> found = new LinkedHashMap<>();
            for (Map.Entry<String, CompletableFuture<Long>> e : loads.entrySet()) {
                long now = e.getValue().getNow(-1L);
                found.put(e.getKey(), now < 0 ? -1 : Math.max(0, now - start.get(e.getKey())));
            }
            // A new run started while loading: still say how the last one went, but leave the new run's state alone.
            if (run != runId) {
                if (!retry) {
                    show(found, false, rooms);
                    TeammateHistory.record(new TeammateHistory.Run(found, rooms, deaths));
                }
                return;
            }
            boolean someZero = found.values().stream().anyMatch(n -> n == 0);
            if (retry) {
                // A second look that failed for someone keeps what the first look found.
                if (historyRun != null) TeammateHistory.record(historyRun.withLaterSecrets(found));
                historyRun = null;
                if (shown != null && found.entrySet().stream().noneMatch(e -> e.getValue() > shown.getOrDefault(e.getKey(), -1L))) return;
            } else if (someZero) {
                retryAt = System.currentTimeMillis() + SECOND_LOOK_MS;
                historyRun = new TeammateHistory.Run(found, rooms, deaths);
            } else {
                TeammateHistory.record(new TeammateHistory.Run(found, rooms, deaths));
            }
            shown = found;
            show(found, retry, rooms);
        }));
    }

    private static void show(Map<String, Long> found, boolean updated, Map<String, RoomClears.Counts> rooms) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(
            Component.literal(updated ? "Team secrets (updated):" : "Team secrets this run:").withStyle(ChatFormatting.GOLD)));
        for (Map.Entry<String, Long> e : found.entrySet()) {
            MutableComponent line = line(e.getKey(), classes.get(e.getKey()), e.getValue(), PlayerNameColour.of(e.getKey()));
            if (rooms != null) line.append(roomsPart(rooms.getOrDefault(e.getKey().toLowerCase(java.util.Locale.ROOT), new RoomClears.Counts(List.of(), List.of()))));
            mc.gui.hud.getChat().addClientSystemMessage(line);
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
