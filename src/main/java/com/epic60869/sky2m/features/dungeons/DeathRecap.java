package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Run Recap > Death Recap: records each death of the run from Hypixel's " ☠ Name was killed by X and became
 * a ghost." lines (the same lines {@link DeathTracker} reads) with the cause, the room and the run time, prints the
 * list when the run ends and on /s2 deathrecap, and saves it with the run in Run History.
 * <p>
 * The room is the matched room you are in when you die yourself; for a teammate it is your room when they are within
 * 40 blocks of you, otherwise their coordinates (the dungeon map doesn't expose the room at an arbitrary position).
 */
final class DeathRecap {
    private static final Pattern DEATH = Pattern.compile("^ ?☠ (You|\\w{1,16}) (.+?)(?: and became a ghost)?\\.$");

    /** One death. {@code ms} is the time into the run, -1 if unknown. */
    public record DeathEntry(String name, String cause, String room, long ms) {}

    private static final List<DeathEntry> deaths = new ArrayList<>();
    private static long startedAt;

    private DeathRecap() {}

    static void init() {
        Sky2MChat.onChat(message -> onChat(message.text()));
    }

    private static boolean enabled() {
        DungeonRecapExtras.Config c = DungeonRecapExtras.config();
        return c != null && c.deathRecap.enabled;
    }

    private static void onChat(String raw) {
        String text = raw.trim();
        if (text.equals("Starting in 1 second.") && Sky2MLocation.inDungeon()) {
            deaths.clear();
            startedAt = System.currentTimeMillis();
            return;
        }
        if (!enabled() || !Sky2MLocation.inDungeon()) return;
        Matcher m = DEATH.matcher(raw);
        if (!m.matches()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean self = m.group(1).equals("You");
        String name = self ? mc.player.getGameProfile().name() : m.group(1);
        long ms = startedAt > 0 ? System.currentTimeMillis() - startedAt
            : ScoreCalculator.started() ? ScoreCalculator.secondsElapsed() * 1000L : -1;
        deaths.add(new DeathEntry(name, cause(m.group(2)), where(mc, name, self), ms));
    }

    /** "was killed by Withermancer" -> "killed by Withermancer", "fell to your death" -> "fell to your death". */
    private static String cause(String raw) {
        String s = raw.replaceFirst("^(?:was|were) ", "").trim();
        if (s.startsWith("disconnected")) return "disconnected";
        return s.isEmpty() ? "died" : s;
    }

    private static String where(Minecraft mc, String name, boolean self) {
        Player who = self ? mc.player : null;
        if (!self && mc.level != null) {
            for (Player p : mc.level.players()) {
                if (p.getGameProfile().name().equals(name)) {
                    who = p;
                    break;
                }
            }
        }
        Room room = DungeonManager.getCurrentRoom();
        String roomName = room != null && room.isMatched() && room.getName() != null ? room.getName() : null;
        boolean near = self || (who != null && who.distanceTo(mc.player) < 40);
        if (near && roomName != null) return roomName;
        if (who != null) return who.getBlockX() + ", " + who.getBlockY() + ", " + who.getBlockZ();
        return near ? "unknown room" : "unknown";
    }

    /** From RunHistory: attach the run's deaths to the saved run and print the recap. */
    static void onRunFinished(RunHistory.Run run) {
        if (!enabled()) return;
        run.deathLog = new ArrayList<>(deaths);
        RunHistory.save();
        DungeonRecapExtras.Config c = DungeonRecapExtras.config();
        if (c != null && c.deathRecap.chatAtEnd && !deaths.isEmpty()) print(run.deathLog, run.floor);
    }

    /** /s2 deathrecap: the run in progress, or the last saved run. */
    static void printLast() {
        if (!deaths.isEmpty() && Sky2MLocation.inDungeon()) {
            print(deaths, Sky2MLocation.dungeonFloor());
            return;
        }
        List<RunHistory.Run> runs = RunHistory.runs();
        if (runs.isEmpty() || runs.getFirst().deathLog == null) {
            Sky2MAlerts.chat(Component.literal(enabled() ? "No deaths recorded for the last run." : "Turn on Death Recap (Dungeon > Run Recap) to record deaths.")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (runs.getFirst().deathLog.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No deaths in the last run.").withStyle(ChatFormatting.GREEN));
            return;
        }
        print(runs.getFirst().deathLog, runs.getFirst().floor);
    }

    static void print(List<DeathEntry> list, String floor) {
        Sky2MAlerts.chat(Component.literal("Death recap" + (floor == null ? "" : " (" + floor + ")") + ": " + list.size()
            + (list.size() == 1 ? " death" : " deaths")).withStyle(ChatFormatting.GOLD));
        for (DeathEntry d : list) Sky2MAlerts.chat(Component.literal(line(d)).withStyle(ChatFormatting.GRAY));
    }

    /** "Steve: killed by Withermancer in Water Board at 3m 12.00s". */
    static String line(DeathEntry d) {
        return d.name() + ": " + d.cause() + " in " + d.room() + (d.ms() >= 0 ? " at " + RunHistory.time(d.ms()) : "");
    }
}
