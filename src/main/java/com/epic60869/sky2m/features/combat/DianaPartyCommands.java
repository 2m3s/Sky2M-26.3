package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.features.combat.DianaProfitTracker.Period;
import com.epic60869.sky2m.features.combat.DianaTracker.Drop;
import com.epic60869.sky2m.features.combat.DianaTracker.Mob;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SBO's Diana party commands (https://github.com/SkyblockOverhaul/SBO, general/PartyCommands.kt, Apache-2.0): !chim,
 * !inq, !relic, !stick, !since, !burrows, !mobs, !profit and the rest, answered in party chat from this Diana season's
 * mob and drop tracker ({@link DianaTracker}) and profit tracker ({@link DianaProfitTracker}). Asked by anyone in the
 * party, you included; {@link com.epic60869.sky2m.features.misc.PartyCommands} sends the answer.
 */
public final class DianaPartyCommands {
    public static final List<String> HELP = List.of("!chim", "!chimls", "!inq", "!king", "!manti", "!sphinx", "!stick", "!relic",
        "!core", "!wool", "!food", "!stinger", "!feathers", "!profit", "!playtime", "!mobs", "!burrows", "!mf", "!stats <player>",
        "!since (chim, chimls, relic, stick, inq, king, manti, sphinx, core, corels, stinger, wool, woolls, food, foodls)");

    private static final Map<String, Supplier<String>> COMMANDS = new HashMap<>();

    static {
        cmd(() -> fmt("Chimera", own(Drop.CHIMERA), mob(Mob.MINOS_INQUISITOR)) + " +" + ls(Drop.CHIMERA) + " LS",
            "chim", "chimera", "chims", "chimeras", "book", "books");
        cmd(() -> fmt("Chimera LS", ls(Drop.CHIMERA), mobLs(Mob.MINOS_INQUISITOR)),
            "chimls", "chimerals", "bookls", "lschim", "lsbook", "lootsharechim", "lschimera");
        cmd(() -> "Inquisitor LS: " + mobLs(Mob.MINOS_INQUISITOR), "inqsls", "inquisitorls", "inquisls", "lsinq", "lsinqs", "lsinquisitor", "lsinquis", "inqls");
        cmd(() -> fmt("Inquisitor", mob(Mob.MINOS_INQUISITOR), totalMobs()), "inq", "inqs", "inquisitor", "inquis");
        cmd(() -> "King LS: " + mobLs(Mob.KING_MINOS), "kingls", "kingsls");
        cmd(() -> fmt("King", mob(Mob.KING_MINOS), totalMobs()), "king", "kings");
        cmd(() -> "Sphinx LS: " + mobLs(Mob.SPHINX), "sphinxls", "sphinxsls");
        cmd(() -> fmt("Sphinx", mob(Mob.SPHINX), totalMobs()), "sphinx", "sphinxs");
        cmd(() -> "Manticore LS: " + mobLs(Mob.MANTICORE), "mantils", "mantisls");
        cmd(() -> fmt("Manticore", mob(Mob.MANTICORE), totalMobs()), "manti", "mantis");
        cmd(() -> fmt("Dye", own(Drop.MYTHOLOGICAL_DYE), totalMobs()), "dye", "dyes");
        cmd(() -> {
            DianaProfitTracker.Totals t = DianaProfitTracker.totals(Period.SEASON);
            return "Burrows: " + String.format(Locale.US, "%,d", t.burrows()) + " (" + perHour(t.burrows(), t.activeMs()) + "/h)";
        }, "burrows", "burrow");
        cmd(() -> fmt("Relics", own(Drop.MINOS_RELIC), mob(Mob.MINOS_CHAMPION)), "relic", "relics");
        cmd(() -> fmt("Cores", own(Drop.MANTI_CORE), mob(Mob.MANTICORE)), "core", "manticore");
        cmd(() -> fmt("Core LS", ls(Drop.MANTI_CORE), mobLs(Mob.MANTICORE)), "corels", "manticorels", "lscore", "lsmanticore");
        cmd(() -> fmt("Stingers", own(Drop.FATEFUL_STINGER), mob(Mob.MANTICORE)), "stinger", "fatefulstinger");
        cmd(() -> fmt("Stinger LS", ls(Drop.FATEFUL_STINGER), mobLs(Mob.MANTICORE)), "stingerls", "fatefulstingerls", "lsstinger", "lsfatefulstinger");
        cmd(() -> fmt("Wool", own(Drop.SHIMMERING_WOOL), mob(Mob.KING_MINOS)), "wool", "shimmering", "shimmeringwool");
        cmd(() -> fmt("Wool LS", ls(Drop.SHIMMERING_WOOL), mobLs(Mob.KING_MINOS)), "woolls", "shimmeringwoolls", "lsshimmering", "lsshimmeringwool");
        cmd(() -> fmt("Brain Food", own(Drop.BRAIN_FOOD), mob(Mob.SPHINX)), "food", "brainfood", "brain");
        cmd(() -> fmt("Brain Food LS", ls(Drop.BRAIN_FOOD), mobLs(Mob.SPHINX)), "foodls", "brainfoodls", "lsbrainfood", "lsbrain");
        cmd(() -> fmt("Braided feathers", own(Drop.BRAIDED_GRIFFIN_FEATHER), totalMobs()), "braided", "braideds");
        cmd(() -> fmt("Crowns", own(Drop.CROWN_OF_GREED), mob(Mob.KING_MINOS)), "crown", "crowns", "cog");
        cmd(() -> fmt("King Shards", shards("King Minos"), mob(Mob.KING_MINOS)), "kingshard", "kingshards");
        cmd(() -> fmt("Sphinx Shards", shards("Sphinx"), mob(Mob.SPHINX)), "sphinxshard", "sphinxshards");
        cmd(() -> fmt("Minotaur Shards", shards("Minotaur"), mob(Mob.MINOTAUR)), "minotaurshard", "minotaurshards");
        cmd(() -> fmt("Cretan Shards", shards("Cretan Bull"), mob(Mob.CRETAN_BULL)), "cretanshard", "cretanshards", "certanshard", "certanshards");
        cmd(() -> fmt("Harpy Shards", shards("Harpy"), mob(Mob.HARPY)), "harpyshard", "harpyshards");
        cmd(() -> "Mytho Frags: " + own(Drop.MYTHOS_FRAGMENT), "mythofrag", "frags");
        cmd(() -> fmt("Urns", own(Drop.CRETAN_URN), mob(Mob.CRETAN_BULL)), "urns", "urn", "cretanurn");
        cmd(() -> fmt("Hilts", own(Drop.HILT_OF_REVELATIONS), mob(Mob.MINOS_HUNTER)), "hilt", "hiltofrevelations");
        cmd(() -> fmt("Sticks", own(Drop.DAEDALUS_STICK), mob(Mob.MINOTAUR)), "sticks", "stick");
        cmd(() -> "Feathers: " + own(Drop.GRIFFIN_FEATHER), "feathers", "feather");
        cmd(() -> "Coins: " + String.format(Locale.US, "%,d", DianaProfitTracker.totals(Period.SEASON).coins()), "coins", "coin");
        cmd(() -> {
            long mobs = totalMobs();
            return "Mobs: " + String.format(Locale.US, "%,d", mobs) + " (" + perHour(mobs, DianaProfitTracker.totals(Period.SEASON).activeMs()) + "/h)";
        }, "mobs", "mob");
        cmd(DianaPartyCommands::magicFind, "mf", "magicfind");
        cmd(() -> "Playtime: " + DianaProfitTracker.duration(DianaProfitTracker.totals(Period.SEASON).activeMs()), "playtime");
        cmd(() -> {
            DianaProfitTracker.Totals t = DianaProfitTracker.totals(Period.SEASON);
            String perHour = t.activeMs() >= 60_000L ? " " + CombatFeatures.formatCoins(t.profit() / (t.activeMs() / 3_600_000d)) + "/h" : "";
            return "Profit: " + CombatFeatures.formatCoins(t.profit()) + perHour;
        }, "profit", "profits");
    }

    private DianaPartyCommands() {}

    private static void cmd(Supplier<String> answer, String... aliases) {
        for (String alias : aliases) COMMANDS.put(alias, answer);
    }

    /**
     * The answer to a Diana party command, or null when it isn't one.
     *
     * @param command the command without "!", lower case
     * @param arg     the word after it, or null
     * @param me      your name (for !stats)
     */
    public static String reply(String command, String arg, String me) {
        switch (command) {
            case "since" -> {
                return since(arg);
            }
            case "stats", "stat" -> {
                return arg != null && arg.equalsIgnoreCase(me) ? stats(Period.SEASON) : null;
            }
            case "totalstats", "totalstat" -> {
                return arg != null && arg.equalsIgnoreCase(me) ? stats(Period.ALL_TIME) : null;
            }
            case "sessionstats", "sessionstat" -> {
                return arg != null && arg.equalsIgnoreCase(me) ? stats(Period.SESSION) : null;
            }
            default -> {
                Supplier<String> answer = COMMANDS.get(command);
                return answer == null || arg != null ? null : answer.get();
            }
        }
    }

    /** SBO's !since: how many mobs (or rare mobs) since your last one. */
    private static String since(String arg) {
        if (arg == null) return null;
        DianaTracker.Streaks s = DianaTracker.streaks();
        DianaTracker.Data all = DianaTracker.data(Period.ALL_TIME);
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "chimera", "chim", "chims", "chimeras", "book", "books" -> "Inqs since chim: " + s.since(Drop.CHIMERA.name());
            case "lschim", "chimls", "lschimera", "chimerals", "lsbook", "bookls", "lootsharechim" -> "Inqs since lootshare chim: " + s.since(Drop.CHIMERA.lsKey());
            case "stick", "sticks" -> "Minos since stick: " + s.since(Drop.DAEDALUS_STICK.name());
            case "relic", "relics" -> "Champs since relic: " + s.since(Drop.MINOS_RELIC.name());
            case "inq", "inqs", "inquisitor", "inquisitors", "inquis" -> "Mobs since inq: " + all.since.getOrDefault(Mob.MINOS_INQUISITOR.name(), 0L);
            case "king", "kings" -> "Mobs since king: " + all.since.getOrDefault(Mob.KING_MINOS.name(), 0L);
            case "manti", "mantis" -> "Mobs since manti: " + all.since.getOrDefault(Mob.MANTICORE.name(), 0L);
            case "sphinx", "sphinxs" -> "Mobs since sphinx: " + all.since.getOrDefault(Mob.SPHINX.name(), 0L);
            case "core", "cores" -> "Mantis since core: " + s.since(Drop.MANTI_CORE.name());
            case "corels", "lscore" -> "Mantis since lootshare core: " + s.since(Drop.MANTI_CORE.lsKey());
            case "stinger", "stingers" -> "Mantis since stinger: " + s.since(Drop.FATEFUL_STINGER.name());
            case "wool", "wools" -> "Kings since wool: " + s.since(Drop.SHIMMERING_WOOL.name());
            case "woolls", "lswool" -> "Kings since lootshare wool: " + s.since(Drop.SHIMMERING_WOOL.lsKey());
            case "food", "brainfood" -> "Sphinx since food: " + s.since(Drop.BRAIN_FOOD.name());
            case "foodls", "lsfood" -> "Sphinx since lootshare food: " + s.since(Drop.BRAIN_FOOD.lsKey());
            default -> null;
        };
    }

    /** !stats <you>: a one-line summary (SBO sends its own player stats here). */
    private static String stats(Period period) {
        DianaTracker.Data d = DianaTracker.data(period);
        DianaProfitTracker.Totals t = DianaProfitTracker.totals(period);
        long mobs = d.totalMobs();
        return period + ": Burrows " + String.format(Locale.US, "%,d", t.burrows())
            + " | Mobs " + String.format(Locale.US, "%,d", mobs)
            + " | Inqs " + d.mob(Mob.MINOS_INQUISITOR) + " (" + percent(d.mob(Mob.MINOS_INQUISITOR), mobs) + "%)"
            + " | Chims " + d.drop(Drop.CHIMERA) + " +" + d.dropLs(Drop.CHIMERA) + " LS"
            + " | Kings " + d.mob(Mob.KING_MINOS) + " | Mantis " + d.mob(Mob.MANTICORE)
            + " | Profit " + CombatFeatures.formatCoins(t.profit())
            + " | " + DianaProfitTracker.duration(t.activeMs());
    }

    private static String magicFind() {
        Map<String, Integer> best = DianaTracker.streaks().bestMagicFind;
        return "Wool (" + best.getOrDefault(Drop.SHIMMERING_WOOL.name(), 0) + "% ✯) Manticore (" + best.getOrDefault(Drop.MANTI_CORE.name(), 0)
            + "% ✯) Stinger (" + best.getOrDefault(Drop.FATEFUL_STINGER.name(), 0) + "% ✯) Chim (" + best.getOrDefault(Drop.CHIMERA.name(), 0)
            + "% ✯) Relic (" + best.getOrDefault(Drop.MINOS_RELIC.name(), 0) + "% ✯) Food (" + best.getOrDefault(Drop.BRAIN_FOOD.name(), 0)
            + "% ✯) Stick (" + best.getOrDefault(Drop.DAEDALUS_STICK.name(), 0) + "% ✯)";
    }

    // ------------------------------------------------------------------------------------------------ this season

    private static long own(Drop d) { return DianaTracker.season().drop(d); }
    private static long ls(Drop d) { return DianaTracker.season().dropLs(d); }
    private static long mob(Mob m) { return DianaTracker.season().mob(m); }
    private static long mobLs(Mob m) { return DianaTracker.season().mobLs(m); }
    private static long shards(String mob) { return DianaTracker.season().shards(mob); }
    private static long totalMobs() { return DianaTracker.season().totalMobs(); }

    private static String fmt(String label, long count, long of) {
        return label + ": " + count + " (" + percent(count, of) + "%)";
    }

    private static String percent(long count, long of) {
        return of <= 0 ? "0" : String.format(Locale.US, "%.1f", count * 100.0 / of);
    }

    private static String perHour(long count, long activeMs) {
        return activeMs < 60_000L ? "0" : String.format(Locale.US, "%.1f", count / (activeMs / 3_600_000d));
    }
}
