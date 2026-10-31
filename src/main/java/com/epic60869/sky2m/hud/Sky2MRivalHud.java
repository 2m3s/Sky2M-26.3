package com.epic60869.sky2m.hud;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.garden.Crops;
import com.epic60869.sky2m.features.misc.system.CollectionTracker;
import com.epic60869.sky2m.features.sbc.SbcNet;
import com.epic60869.sky2m.features.skills.Gathering;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.social.Sky2MLeaderboards;
import com.epic60869.sky2m.social.Sky2MRivals;

/**
 * Rival HUD (Misc > Collections > Rival HUD): the Sky2M collection board for what you're doing right now, against
 * your rivals on it — both amounts, who's ahead and by how much. Farming Wheat shows the Wheat board; switch to Cactus
 * and it shows Cactus. Hidden when you have no rival on that board.
 *
 * <p>What you're doing, freshest first: the block you're breaking (a crop, an ore, gemstone or other mining block, a
 * log — see {@link Crops} and {@link Gathering}), the crop your farming tool is for, the last fishing item you caught
 * while fishing, then the collection the Collection Tracker saw you gather (when that's on). A pickaxe or drill alone
 * says nothing (it mines everything). The last one is kept for a while after you stop, so the HUD doesn't blink out
 * between crops.
 *
 * <p>Rival Alerts: when a new copy of the shown board's standings puts a rival above you who was below (or the other
 * way round), a title and a sound, at most once a minute per rival and board.
 *
 * <p>The standings are the ones {@link Sky2MLeaderboards} already caches (the server only refreshes them every 15
 * minutes); while the HUD is on and has a rival board it asks for that board when it has none, then every
 * {@link #REFRESH_MS}, never while another board request is out or a screen is open (so it can't take the
 * leaderboard screen's answer). The lines are built once per tick, not per frame.
 */
public final class Sky2MRivalHud {
    private static final String ID = "rival_hud";
    private static final long REFRESH_MS = 5 * 60_000L;
    /** Don't ask again sooner than this when an answer didn't come (offline, or the server had none). */
    private static final long RETRY_MS = 30_000L;
    /** Breaking a crop counts as "what you're doing" this long after the last hit. */
    private static final long BREAKING_MS = 5_000L;
    /** The board stays up this long after you stop gathering. */
    private static final long KEEP_MS = 90_000L;
    /** You're still fishing this long after the bobber was last out (between casts). */
    private static final long FISHING_MS = 30_000L;
    /** A caught fishing item picks the board this long after the catch. */
    private static final long CATCH_MS = 90_000L;
    /** At most one alert per rival per board this often. */
    private static final long ALERT_MS = 60_000L;

    private static String breaking = "";
    private static long breakingAt;
    private static String board = "";
    private static long boardAt;
    private static String requested = "";
    private static long requestedAt;
    private static List<Component> lines = List.of();
    private static long fishingAt;
    private static String caught = "";
    private static long caughtAt;
    /** Fishing collection items in your inventory last tick while fishing (null: count afresh). */
    private static Map<String, Integer> fishCounts;
    /** Per board: which standings ({@link Sky2MLeaderboards.StatResult#at()}) the alerts last looked at. */
    private static final Map<String, Long> seenAt = new HashMap<>();
    /** Per "board|rival": whether the rival was above you in those standings. */
    private static final Map<String, Boolean> rivalAbove = new HashMap<>();
    /** Per "board|rival": when the last alert for them was. */
    private static final Map<String, Long> alertedAt = new HashMap<>();

    private static final List<Component> PREVIEW = List.of(
        Component.literal("⚔ Wheat").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
        Component.literal("You #12 ").withStyle(ChatFormatting.GREEN).append(Component.literal("12,345,678").withStyle(ChatFormatting.YELLOW)),
        Component.literal("Rival #10 ").withStyle(ChatFormatting.GOLD).append(Component.literal("13,000,000").withStyle(ChatFormatting.YELLOW)),
        Component.literal("654,322 behind").withStyle(ChatFormatting.RED));

    private Sky2MRivalHud() {}

    private static boolean turnedOn() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.rivalHud;
    }

    public static void init() {
        Sky2MHuds.setting(ID, Sky2MRivalHud::turnedOn);
        Sky2MHuds.setSettingsSearch(ID, "Rival HUD");
        Sky2MHuds.register(ID, "Rival HUD", () -> turnedOn() && !lines.isEmpty(), () -> lines, PREVIEW, 8, 190);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        // The crop, ore or log you break (crops the way the Farming profit tracker sees them).
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            String collection = turnedOn() ? blockCollection(state.getBlock(), level.getGameTime()) : null;
            if (collection != null) {
                breaking = collection;
                breakingAt = System.currentTimeMillis();
            }
        });
    }

    private static String blockCollection(Block block, long gameTime) {
        String crop = Crops.collectionOf(block, gameTime);
        if (crop != null) return crop;
        String mined = Gathering.miningCollection(block);
        return mined != null ? mined : Gathering.foragingCollection(block);
    }

    private static void tick(Minecraft mc) {
        if (!turnedOn() || mc.player == null || mc.level == null || !Sky2MLocation.onSkyblock()) {
            lines = List.of();
            return;
        }
        long now = System.currentTimeMillis();
        String collection = current(mc, now);
        if (!collection.isEmpty()) {
            board = "collection|" + collection;
            boardAt = now;
        } else if (now - boardAt > KEEP_MS) {
            board = "";
        }
        if (board.isEmpty() || !Sky2MRivals.hasRivalsOn(board)) {
            lines = List.of();
            return;
        }
        Sky2MLeaderboards.StatResult result = Sky2MLeaderboards.cachedStat(board);
        boolean stale = result == null || now - result.at() > REFRESH_MS;
        boolean waited = !board.equals(requested) || now - requestedAt > RETRY_MS;
        if (stale && waited && mc.gui.screen() == null && SbcNet.online() && !Sky2MLeaderboards.statBusy()) {
            int bar = board.indexOf('|');
            if (Sky2MLeaderboards.requestStat(board.substring(0, bar), board.substring(bar + 1))) {
                requested = board;
                requestedAt = now;
            }
        }
        alerts(result, now);
        lines = build(result);
    }

    /** The collection you're working on right now ("WHEAT"), or "". */
    private static String current(Minecraft mc, long now) {
        if (!breaking.isEmpty() && now - breakingAt < BREAKING_MS) return breaking;
        String tool = Crops.toolCollection(Compat.neuName(mc.player.getMainHandItem()), mc.level.getGameTime());
        if (tool != null) return tool;
        if (fishing(mc, now)) {
            if (!caught.isEmpty() && now - caughtAt < CATCH_MS) return caught;
            // Caught straight into your sacks: the Collection Tracker reads the "[Sacks]" messages.
            String gathering = CollectionTracker.gathering();
            if (Gathering.isFishingCollection(gathering)) return gathering;
        }
        return CollectionTracker.gathering();
    }

    /**
     * Whether you're fishing (bobber out now or lately), and if so the fishing item you last caught: a fishing
     * collection item that turned up in your inventory while no menu was open.
     */
    private static boolean fishing(Minecraft mc, long now) {
        if (mc.player.fishing != null) fishingAt = now;
        if (now - fishingAt > FISHING_MS || mc.gui.screen() != null) {
            fishCounts = null;
            return now - fishingAt <= FISHING_MS;
        }
        Map<String, Integer> counts = new HashMap<>();
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = Compat.neuName(stack);
            if (Gathering.isFishingCollection(id)) counts.merge(id, stack.getCount(), Integer::sum);
        }
        if (fishCounts != null) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > fishCounts.getOrDefault(e.getKey(), 0)) {
                    caught = e.getKey();
                    caughtAt = now;
                }
            }
        }
        fishCounts = counts;
        return true;
    }

    private static boolean alertsOn() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.rivalAlerts;
    }

    /**
     * Compares new standings of the shown board with the last ones seen: a rival who went from below you to above
     * (or the other way) gets a title and a sound. The first standings of a board only set the baseline, and
     * rivals or you outside the top list count as below everyone in it.
     */
    private static void alerts(Sky2MLeaderboards.StatResult result, long now) {
        if (result == null || !alertsOn()) return;
        Long seen = seenAt.put(board, result.at());
        if (seen != null && seen == result.at()) return;
        String self = Minecraft.getInstance().getUser().getName();
        Sky2MLeaderboards.StatEntry you = entry(result, self);
        for (String rival : Sky2MRivals.rivalsOn(board)) {
            if (rival.equalsIgnoreCase(self)) continue;
            Sky2MLeaderboards.StatEntry them = entry(result, rival);
            String key = board + "|" + rival.toLowerCase(Locale.ROOT);
            if (you == null && them == null) {
                rivalAbove.remove(key);
                continue;
            }
            boolean above = you == null || them != null && them.rank() < you.rank();
            Boolean before = rivalAbove.put(key, above);
            if (before == null || before == above) continue;
            Long last = alertedAt.get(key);
            if (last != null && now - last < ALERT_MS) continue;
            alertedAt.put(key, now);
            String name = them != null ? them.username() : rival;
            String boardName = Sky2MRivals.boardName(board);
            if (above) {
                Sky2MAlerts.title(Component.literal(name + " passed you").withStyle(ChatFormatting.RED),
                    Component.literal(boardName).withStyle(ChatFormatting.GOLD), false);
                Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6f);
            } else {
                Sky2MAlerts.title(Component.literal("You passed " + name).withStyle(ChatFormatting.GREEN),
                    Component.literal(boardName).withStyle(ChatFormatting.GOLD), false);
                Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1.0f);
            }
        }
    }

    private static Sky2MLeaderboards.StatEntry entry(Sky2MLeaderboards.StatResult result, String name) {
        for (Sky2MLeaderboards.StatEntry e : result.entries()) if (e.username().equalsIgnoreCase(name)) return e;
        return null;
    }

    /** "⚔ Wheat", your line, then each rival's line and the gap to them. */
    private static List<Component> build(Sky2MLeaderboards.StatResult result) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("⚔ " + Sky2MRivals.boardName(board)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (result == null) {
            out.add(Component.literal(SbcNet.online() ? "loading..." : "Can't reach the Sky2M server").withStyle(ChatFormatting.DARK_GRAY));
            return out;
        }
        String self = Minecraft.getInstance().getUser().getName();
        Sky2MLeaderboards.StatEntry you = null;
        for (Sky2MLeaderboards.StatEntry e : result.entries()) if (e.username().equalsIgnoreCase(self)) you = e;
        out.add(you == null ? Component.literal("You: not in the top " + result.entries().size()).withStyle(ChatFormatting.GRAY)
            : Component.literal("You #" + you.rank() + " ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(fmt(you.value())).withStyle(ChatFormatting.YELLOW)));
        for (String rival : Sky2MRivals.rivalsOn(board)) {
            Sky2MLeaderboards.StatEntry them = null;
            for (Sky2MLeaderboards.StatEntry e : result.entries()) if (e.username().equalsIgnoreCase(rival)) them = e;
            if (them == null) {
                // Rivals on every board (from before per-board rivals) who aren't on this one aren't worth a line.
                if (!Sky2MRivals.isRivalEverywhere(rival)) {
                    out.add(Component.literal(rival + ": not in the top " + result.entries().size()).withStyle(ChatFormatting.GRAY));
                }
                continue;
            }
            out.add(Component.literal(them.username() + " #" + them.rank() + " ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(fmt(them.value())).withStyle(ChatFormatting.YELLOW)));
            if (you != null) {
                long gap = Math.abs(them.value() - you.value());
                out.add(them.value() == you.value() ? Component.literal("  tied").withStyle(ChatFormatting.YELLOW)
                    : them.value() > you.value() ? Component.literal("  " + fmt(gap) + " behind").withStyle(ChatFormatting.RED)
                    : Component.literal("  " + fmt(gap) + " ahead").withStyle(ChatFormatting.GREEN));
            }
        }
        // Only rivals on every board, none of them on this one: nothing to race here.
        return out.size() <= 2 && Sky2MRivals.rivalsOn(board).stream().allMatch(Sky2MRivals::isRivalEverywhere) ? List.of() : out;
    }

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }
}
