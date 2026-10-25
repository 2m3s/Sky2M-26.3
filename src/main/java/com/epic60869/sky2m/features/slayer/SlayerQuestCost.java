package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the current slayer quest cost, as SkyHanni's SlayerProfitTracker works it out: what the purse actually went down
 * by around "SLAYER QUEST STARTED!", or around the boss spawning when Hypixel takes it then (up to 350k; Aatrox's discount and the Rift's motes come out right that way), or,
 * with auto-slayer, what "Took X coins from your bank for auto-slayer..." says. Worked out once per quest here so the
 * slayer profit trackers and the per-kill profit line agree on it.
 */
public final class SlayerQuestCost {
    /** One spawn cost paid, for {@code slayer}'s quest ("Revenant Horror"..., or null when no quest showed). */
    public record Charge(String slayer, long coins, boolean bank) {}

    /**
     * The spawn fee taken from the bank: SkyHanni's slayer.autoslayer.bank ("Took 1.9k coins from your bank for
     * auto-slayer..."), and the same message without "for auto-slayer" when Hypixel takes a normal quest's fee from the
     * bank because the purse is short.
     */
    private static final Pattern BANK_CHARGE = Pattern.compile("^Took (.+?) coins from your bank\\b(.*)$");
    /** A bank charge that doesn't say it's for auto-slayer only counts this close to a quest start (either side). */
    private static final long BANK_NEAR_START_MS = 10_000L;
    /** A bank charge this soon after a quest started is that quest's; later ones wait for the next start. */
    private static final long BANK_AFTER_START_MS = 3_000L;
    private static final Pattern NUMBER = Pattern.compile("[\\d,]+(?:\\.\\d+)?");
    /** How long after the quest started its slayer may take to show on the scoreboard. */
    private static final long QUEST_COST_WINDOW_MS = 3_000L;
    /** The highest cost that can be a spawn fee: anything more is some other purse change. */
    private static final long MAX_COST = 350_000L;
    /** The lowest spawn fee (a Tier 1 with Aatrox's discount): less is the purse wobbling, not a fee. */
    private static final long MIN_COST = 1_000L;

    /** The purse over the last 15 seconds: {time, coins}. Hypixel takes the fee before "SLAYER QUEST STARTED!". */
    private static final ArrayDeque<double[]> purseHistory = new ArrayDeque<>();
    private static final List<Consumer<Charge>> listeners = new ArrayList<>();
    private static double lastPurse = -1;
    /**
     * Each quest gets a number, and its cost is kept under it: a boss that died before its fee was measured (inside
     * the 3 seconds, or an instant restart started the next quest first) still gets its own cost once it's found.
     */
    private static int serial;
    /** Per quest number: {purse fee, bank charge}, -1 when not seen. Only the last few quests are kept. */
    private static final java.util.Map<Integer, long[]> costs = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, long[]> eldest) {
            return size() > 8;
        }
    };

    /** A purse check waiting for the scoreboard to catch up: when, what it was ("quest start"/"boss spawn"), for which quest. */
    private static final class Measure {
        final long at;
        final String what;
        final int quest;
        String slayer;

        Measure(long at, String what, int quest) {
            this.at = at;
            this.what = what;
            this.quest = quest;
        }
    }

    private static final List<Measure> measures = new ArrayList<>();
    private static long bankAt, lastStartAt;
    /** A bank charge that came before its quest started, waiting for the start to confirm it, or -1. */
    private static long pendingBank = -1;
    private static boolean initialized;

    private SlayerQuestCost() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(SlayerQuestCost::tick);
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
    }

    /** Called with each spawn cost as it's worked out (the purse fee a moment after the quest starts, or the bank charge). */
    public static void onCharge(Consumer<Charge> listener) {
        listeners.add(listener);
    }

    private static long[] slot(int quest) {
        return costs.computeIfAbsent(quest, q -> new long[]{-1, -1});
    }

    /** A quest's spawn cost: the bank charge if one came, else the purse fee; -1 if unknown (or too old). */
    public static long costOf(int quest) {
        long[] c = costs.get(quest);
        if (c == null) return -1;
        return c[1] > 0 ? c[1] : c[0];
    }

    /** Whether a purse check for this quest's fee is still waiting for the scoreboard. */
    public static boolean measuring(int quest) {
        for (Measure m : measures) if (m.quest == quest) return true;
        return false;
    }

    /** The current quest's spawn cost; -1 if unknown. */
    public static long cost() {
        return costOf(serial);
    }

    /**
     * The quest of the boss that just died: right after the next quest started (an instant restart, in the same
     * tick as the kill), that's the previous quest, not the new one. Its cost ({@link #costOf}) may only be known a
     * few seconds later.
     */
    public static int questOfLastBoss() {
        return lastStartAt > 0 && System.currentTimeMillis() - lastStartAt < 5_000L ? serial - 1 : serial;
    }

    private static void onChat(String text) {
        if (!Sky2MLocation.onSkyblock()) return;
        long now = System.currentTimeMillis();
        if (text.equals("SLAYER QUEST STARTED!")) {
            // The boss that just died keeps the ending quest's number, and its cost even if that's measured later.
            serial++;
            slot(serial);
            lastStartAt = now;
            // The fee was taken from the bank just before the quest started: it's this quest's.
            if (pendingBank > 0 && now - bankAt <= BANK_NEAR_START_MS) {
                slot(serial)[1] = pendingBank;
                charge(new Charge(SlayerArea.questSlayer(), pendingBank, true));
            }
            pendingBank = -1;
            measures.add(new Measure(now, "quest start", serial));
            return;
        }
        // Hypixel can take the fee when the boss spawns instead (an instant restart shows no purse change at the
        // start): measure again there if the start didn't find it.
        if (text.startsWith("SLAYER BOSS!") && text.contains("spawned") && cost() < 0) {
            measures.add(new Measure(now, "boss spawn", serial));
            return;
        }
        Matcher bank = BANK_CHARGE.matcher(text);
        if (bank.matches()) {
            long cost = Math.round(parseCoins(bank.group(1)));
            if (cost <= 0 || cost > MAX_COST) return;
            bankAt = now;
            if (now - lastStartAt <= BANK_AFTER_START_MS) {
                slot(serial)[1] = cost;
                pendingBank = -1;
                charge(new Charge(SlayerArea.questSlayer(), cost, true));
            } else {
                // For the quest about to start: auto-slayer's "Took 96k coins from your bank" comes between "SLAYER
                // QUEST COMPLETE!" and the next "SLAYER QUEST STARTED!", so it isn't the ending quest's. Only counted
                // if a quest starts within 10 seconds.
                pendingBank = cost;
            }
        }
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) {
            lastPurse = -1;
            return;
        }
        long now = System.currentTimeMillis();
        double purse = purse();
        lastPurse = purse;
        if (purse >= 0) {
            purseHistory.addLast(new double[]{now, purse});
            while (!purseHistory.isEmpty() && now - purseHistory.getFirst()[0] > 15_000L) purseHistory.removeFirst();
        }
        if (measures.isEmpty()) return;
        for (java.util.Iterator<Measure> it = measures.iterator(); it.hasNext(); ) {
            Measure m = it.next();
            if (m.slayer == null && m.quest == serial) m.slayer = SlayerArea.questSlayer();
            // Always wait 3 seconds: the scoreboard's purse shows the fee a moment after the message, and with an
            // instant restart the old quest's slayer is already on the sidebar, so knowing the slayer isn't enough.
            if (now - m.at < QUEST_COST_WINDOW_MS) continue;
            it.remove();
            measure(m);
        }
    }

    private static void measure(Measure m) {
        long[] slot = slot(m.quest);
        if (costOf(m.quest) > 0) return;
        long at = m.at;
        // The fee: the highest purse in the 10 s before minus the lowest since (mob kill coins coming in after the fee
        // can't hide it that way).
        double highest = -1, lowest = -1;
        for (double[] entry : purseHistory) {
            if (entry[0] >= at - 10_000L && entry[0] <= at) highest = Math.max(highest, entry[1]);
            else if (entry[0] > at) lowest = lowest < 0 ? entry[1] : Math.min(lowest, entry[1]);
        }
        long cost = highest > 0 && lowest >= 0 ? Math.round(highest - lowest) : -1;
        boolean found = cost >= MIN_COST && cost <= MAX_COST;
        // One line per check, so a log shows why a cost came out "unknown".
        System.out.println("[Sky2M] Slayer quest cost at " + m.what + " (quest " + m.quest + "): " + (m.slayer == null ? "no quest seen" : m.slayer)
            + ", purse " + (highest < 0 ? "not readable" : Math.round(highest) + " -> " + Math.round(lowest))
            + (slot[1] > 0 ? ", bank charge " + slot[1] : "")
            + " -> " + (found ? cost : slot[1] > 0 ? "bank charge" : m.what.equals("quest start") ? "not yet, checking at boss spawn" : "unknown"));
        if (!found) return;
        slot[0] = cost;
        if (m.slayer != null) charge(new Charge(m.slayer, cost, false));
    }

    private static void charge(Charge charge) {
        for (Consumer<Charge> listener : listeners) listener.accept(charge);
    }

    /** The purse (or Piggy) from the scoreboard, or -1. */
    public static double purse() {
        String value = Sky2MLocation.scoreboardValue("Purse:");
        if (value == null) value = Sky2MLocation.scoreboardValue("Piggy:");
        if (value == null) return -1;
        Matcher m = NUMBER.matcher(value);
        if (!m.find()) return -1;
        try {
            return Double.parseDouble(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "1.9k", "250,000", "1.2M" -> coins (SkyHanni's formatDouble). */
    private static double parseCoins(String text) {
        String t = text.trim().replace(",", "").toLowerCase(java.util.Locale.ROOT);
        double scale = t.endsWith("k") ? 1e3 : t.endsWith("m") ? 1e6 : t.endsWith("b") ? 1e9 : 1;
        if (scale != 1) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t) * scale;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
