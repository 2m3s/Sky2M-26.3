package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Dungeons > Timers > Run Review: after a run, which splits lost or gained time against your best finished run on the
 * floor (the same run Pace vs PB uses), and which rooms were slower than your room best when a time counted this run.
 */
public final class RunReview {
    /** A split of this run against the same split of the best run. */
    public record SplitDiff(String name, long ms, long best) {
        public long delta() {
            return ms - best;
        }
    }

    private static final int ROOMS_LISTED = 5;

    private RunReview() {}

    static void onRunFinished(RunHistory.Run run) {
        FeatureConfigs.RunHistory c = RunHistory.config();
        if (c == null || !c.runReview) return;
        RunHistory.Run best = bestBefore(RunHistory.runs(), run);
        List<SplitDiff> splits = best == null ? List.of() : splitDiffs(run, best);
        List<RoomBests.RunRoom> rooms = slowerRooms(RoomBests.runRooms());
        if (!splits.isEmpty()) {
            MutableComponent header = Component.literal("Run review against your best, " + RunHistory.time(best.totalMs)).withStyle(ChatFormatting.GOLD);
            if (run.completed && run.totalMs > 0) header.append(Component.literal(": ").withStyle(ChatFormatting.GOLD)).append(deltaPart(run.totalMs - best.totalMs));
            Sky2MAlerts.chat(header);
            MutableComponent line = Component.literal(" ");
            for (int i = 0; i < splits.size(); i++) {
                SplitDiff split = splits.get(i);
                if (i > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                HoverEvent hover = new HoverEvent.ShowText(Component.literal(split.name() + ": " + RunHistory.time(split.ms()) + ", best run " + RunHistory.time(split.best())));
                line.append(Component.literal(split.name() + " ").withStyle(style -> style.withColor(ChatFormatting.GRAY).withHoverEvent(hover)))
                    .append(deltaPart(split.delta()).withStyle(style -> style.withHoverEvent(hover)));
            }
            Sky2MAlerts.chat(line);
        }
        if (!rooms.isEmpty()) Sky2MAlerts.chat(roomsLine(rooms));
    }

    private static MutableComponent deltaPart(long ms) {
        return Component.literal(deltaText(ms)).withStyle(ms <= 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static Component roomsLine(List<RoomBests.RunRoom> rooms) {
        MutableComponent line = Component.literal("Slower than your room best: ").withStyle(ChatFormatting.GOLD);
        List<String> all = new ArrayList<>();
        for (RoomBests.RunRoom room : rooms) all.add(room.name() + ": " + RoomClears.timeText(room.ms()) + ", best " + RoomClears.timeText(room.previous()));
        HoverEvent hover = new HoverEvent.ShowText(Component.literal(String.join("\n", all)));
        for (int i = 0; i < Math.min(rooms.size(), ROOMS_LISTED); i++) {
            RoomBests.RunRoom room = rooms.get(i);
            if (i > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(Component.literal(room.name() + " ").withStyle(style -> style.withColor(ChatFormatting.GRAY).withHoverEvent(hover)))
                .append(deltaPart(room.ms() - room.previous()).withStyle(style -> style.withHoverEvent(hover)));
        }
        if (rooms.size() > ROOMS_LISTED) {
            line.append(Component.literal(" and " + (rooms.size() - ROOMS_LISTED) + " more").withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY).withHoverEvent(hover)));
        }
        return line;
    }

    /** Your fastest finished run on the floor, not counting {@code run}, or null. Public for the game test. */
    public static RunHistory.Run bestBefore(List<RunHistory.Run> runs, RunHistory.Run run) {
        RunHistory.Run best = null;
        for (RunHistory.Run other : runs) {
            if (other == run || !other.completed || other.totalMs <= 0 || run.floor == null || !run.floor.equals(other.floor)) continue;
            if (best == null || other.totalMs < best.totalMs) best = other;
        }
        return best;
    }

    /** This run's splits that the best run has too, in this run's order. Public for the game test. */
    public static List<SplitDiff> splitDiffs(RunHistory.Run run, RunHistory.Run best) {
        List<SplitDiff> diffs = new ArrayList<>();
        for (RunHistory.Split split : run.splits) {
            for (RunHistory.Split other : best.splits) {
                if (other.name().equals(split.name())) {
                    diffs.add(new SplitDiff(split.name(), split.ms(), other.ms()));
                    break;
                }
            }
        }
        return diffs;
    }

    /** The rooms slower than the best they had before this run, most time lost first. Public for the game test. */
    public static List<RoomBests.RunRoom> slowerRooms(List<RoomBests.RunRoom> rooms) {
        return rooms.stream().filter(room -> room.previous() != null && room.ms() > room.previous())
            .sorted(Comparator.comparingLong((RoomBests.RunRoom room) -> room.ms() - room.previous()).reversed()).toList();
    }

    /** "+3.1s" or "-1.2s". Public for the game test. */
    public static String deltaText(long ms) {
        return (ms <= 0 ? "-" : "+") + String.format(Locale.ROOT, "%.1fs", Math.abs(ms) / 1000.0);
    }
}
