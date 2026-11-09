package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hypixel's dungeon blessings in the sim: Power, Wisdom, Life, Stone and Time, found in secret chests, item secrets
 * and puzzle reward chests, held by the whole team for the run.
 *
 * <p>What Hypixel shows, and what reads it here:
 * <ul>
 * <li>Chat, to everyone: {@code "DUNGEON BUFF! <name> found a Blessing of Power III!"} and a {@code "Granted you ..."}
 * line under it.</li>
 * <li>The tab list's footer, under "Dungeon Buffs": {@code "Blessing of Power XI"} - the team's total level of each
 * ({@link #footerLines}, sent by {@link SimTabList}). The mod's Blessing Display reads exactly that. Hypixel's sidebar
 * does not list blessings, so the sim's does not either.</li>
 * <li>The stats: {@link #apply} inside {@link SimStats#effective}, for every player in the party.</li>
 * </ul>
 *
 * <p>The stat effects are hypixelskyblock.minecraft.wiki's (Blessings): per level, a flat amount and a percentage of
 * the stat, both times the floor's multiplier (+20% from Floor III on); the flats add up, the percentages multiply, and
 * the stat becomes {@code (base + flat) x (1 + percent)}:
 * <ul>
 * <li>Power: Strength and Crit Damage, +4 and +2% each.</li>
 * <li>Wisdom: Intelligence +4 and +2% (Hypixel's Speed part is left to {@code /speed}).</li>
 * <li>Life: Health +3% and Health Regen +3%.</li>
 * <li>Stone: Defense +4 and +2%, and +6 weapon Damage.</li>
 * <li>Time: Health, Defense, Strength and Intelligence, +4 and +2% each; always level V, once a run.</li>
 * </ul>
 * Paul's Benediction, the Forbidden Blessing and the Faker shard (more modifiers on the wiki) are not modelled.
 *
 * <p>The ODDS are APPROXIMATE - Hypixel does not publish them. A secret chest holds one about 12% of the time plus 1%
 * per floor number (19% on F7/M7), an item secret half that, a puzzle reward chest always; which one is weighted
 * Power 30, Wisdom 20, Life 20, Stone 20, Time 10, and a secret's is level I or II (a puzzle's V). That comes to the
 * three to six blessings a typical Hypixel run finds.
 */
public final class SimBlessings {

    /** The five blessings, with Hypixel's names. */
    public enum Blessing {
        POWER("Power", 30), WISDOM("Wisdom", 20), LIFE("Life", 20), STONE("Stone", 20), TIME("Time", 10);

        public final String label;
        final int weight;

        Blessing(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }
    }

    /** The team's level of each blessing. Any thread. */
    private static final Map<Blessing, Integer> LEVELS = new ConcurrentHashMap<>();
    /** Positions that already rolled, so a chest clicked twice is one roll. */
    private static final Set<BlockPos> ROLLED = ConcurrentHashMap.newKeySet();
    private static volatile int version;

    private SimBlessings() {
    }

    /** A new run: no blessings. */
    public static void clear() {
        LEVELS.clear();
        ROLLED.clear();
        version++;
    }

    /** The team's level of a blessing (0 = none). */
    public static int level(Blessing b) {
        return LEVELS.getOrDefault(b, 0);
    }

    /** Bumped whenever a blessing changes, so the tab footer is resent at once. */
    static int version() {
        return version;
    }

    /** Adds a blessing to the team. Public for tests and {@code /simblessing}. */
    public static void add(Blessing b, int levels) {
        if (b == null || levels <= 0) {
            return;
        }
        if (b == Blessing.TIME) {
            LEVELS.put(b, 5);
        } else {
            LEVELS.merge(b, levels, Integer::sum);
        }
        version++;
    }

    // ------------------------------------------------------------------ finding them

    /** A secret chest was opened at {@code at} by {@code finder}: maybe a blessing. Any thread. */
    static void secretChest(MinecraftServer server, String finder, BlockPos at) {
        roll(server, finder, at, chestChance(), false);
    }

    /** An item secret was picked up at {@code at}: maybe a blessing, half a chest's odds. Any thread. */
    static void itemSecret(MinecraftServer server, String finder, BlockPos at) {
        roll(server, finder, at, chestChance() / 2, false);
    }

    /**
     * A puzzle reward chest: always a blessing, level V. The Quiz's on Floor IV and up (Normal and Master Mode) is the
     * Blessing of Time, which nothing else gives (hypixelskyblock.minecraft.wiki, Blessing). Any thread.
     */
    static void puzzleChest(MinecraftServer server, String finder, BlockPos at) {
        if (at != null && "Quiz".equalsIgnoreCase(SimState.roomNameAt(at.getX(), at.getZ())) && floorNumber() >= 4
                && level(Blessing.TIME) == 0) {
            if (SimState.isActive() && ROLLED.add(at.immutable())) {
                grant(server, finder, Blessing.TIME, 5);
            }
            return;
        }
        roll(server, finder, at, 1.0, true);
    }

    /** A room's last starred mob died at {@code at}: maybe a blessing, at a chest's odds, level V. Any thread. */
    static void lastStarredMob(MinecraftServer server, String finder, BlockPos at) {
        roll(server, finder, at, chestChance(), true);
    }

    /** 12% plus 1% per floor number. APPROXIMATE, see the class doc. */
    static double chestChance() {
        return 0.12 + 0.01 * floorNumber();
    }

    private static void roll(MinecraftServer server, String finder, BlockPos at, double chance, boolean puzzle) {
        if (!SimState.isActive() || (at != null && !ROLLED.add(at.immutable()))) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= chance) {
            return;
        }
        // Never Time: only the Quiz gives it (puzzleChest).
        int total = 0;
        for (Blessing b : Blessing.values()) {
            if (b != Blessing.TIME) {
                total += b.weight;
            }
        }
        int pick = r.nextInt(total);
        Blessing chosen = Blessing.POWER;
        for (Blessing b : Blessing.values()) {
            if (b == Blessing.TIME) {
                continue;
            }
            pick -= b.weight;
            if (pick < 0) {
                chosen = b;
                break;
            }
        }
        // Secrets give level I or II; puzzles and mobs level V.
        int lvl = puzzle ? 5 : 1 + r.nextInt(2);
        grant(server, finder, chosen, lvl);
    }

    /** Gives the team a blessing and says so the way Hypixel does. Any thread. */
    public static void grant(MinecraftServer server, String finder, Blessing b, int lvl) {
        add(b, lvl);
        if (server == null) {
            return;
        }
        Component found = Component.literal("§d§lDUNGEON BUFF! §f" + finder + " §ffound a §dBlessing of " + b.label
                + " " + roman(lvl) + "§f!");
        Component granted = Component.literal("     §7Granted you " + grantedText(b, lvl) + "§7.");
        server.execute(() -> {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                sp.sendSystemMessage(found);
                sp.sendSystemMessage(granted);
            }
        });
    }

    private static String grantedText(Blessing b, int lvl) {
        double m = floorMultiplier();
        String flat = fmt(4 * lvl * m);
        String pct = fmt(2 * lvl * m);
        return switch (b) {
            case POWER -> "§c+" + flat + " ❁ Strength §7and §9+" + flat + " ☠ Crit Damage §7(+" + pct + "%)";
            case WISDOM -> "§b+" + flat + " ✎ Intelligence §7(+" + pct + "%)";
            case LIFE -> "§c+" + fmt(3 * lvl * m) + "% ❤ Health §7and §c+" + fmt(3 * lvl * m) + "% ❣ Health Regen";
            case STONE -> "§a+" + flat + " ❈ Defense §7(+" + pct + "%) and §c+" + fmt(6 * lvl * m) + " Damage";
            case TIME -> "§a+" + flat + " §7to Health, Defense, Strength and Intelligence (+" + pct + "%)";
        };
    }

    // ------------------------------------------------------------------ stats

    /** +20% from Floor III on (Hypixel's "Floor III or higher" modifier). */
    public static double floorMultiplier() {
        return floorNumber() >= 3 ? 1.2 : 1.0;
    }

    private static int floorNumber() {
        String f = SimState.floorLabel();
        if (f == null || f.isEmpty()) {
            return 7;
        }
        char last = f.charAt(f.length() - 1);
        return Character.isDigit(last) ? last - '0' : 0;
    }

    /** What the team's blessings add, as {@code {flat, percent}} per stat (percent as a fraction). */
    static Map<SimStats.Stat, double[]> effects() {
        Map<SimStats.Stat, double[]> out = new EnumMap<>(SimStats.Stat.class);
        if (LEVELS.isEmpty()) {
            return out;
        }
        double m = floorMultiplier();
        for (Map.Entry<Blessing, Integer> e : LEVELS.entrySet()) {
            int lvl = e.getValue();
            switch (e.getKey()) {
                case POWER -> {
                    both(out, SimStats.Stat.STRENGTH, 4 * lvl * m, 0.02 * lvl * m);
                    both(out, SimStats.Stat.CRIT_DAMAGE, 4 * lvl * m, 0.02 * lvl * m);
                }
                case WISDOM -> both(out, SimStats.Stat.INTELLIGENCE, 4 * lvl * m, 0.02 * lvl * m);
                case LIFE -> both(out, SimStats.Stat.HEALTH, 0, 0.03 * lvl * m);
                case STONE -> both(out, SimStats.Stat.DEFENSE, 4 * lvl * m, 0.02 * lvl * m);
                case TIME -> {
                    for (SimStats.Stat s : new SimStats.Stat[]{SimStats.Stat.HEALTH, SimStats.Stat.DEFENSE,
                            SimStats.Stat.STRENGTH, SimStats.Stat.INTELLIGENCE}) {
                        both(out, s, 4 * lvl * m, 0.02 * lvl * m);
                    }
                }
            }
        }
        return out;
    }

    /** The percentages multiply across blessings, the flats add (the wiki's calculation). */
    private static void both(Map<SimStats.Stat, double[]> out, SimStats.Stat s, double flat, double pct) {
        double[] cur = out.computeIfAbsent(s, k -> new double[]{0, 0});
        cur[0] += flat;
        cur[1] = (1 + cur[1]) * (1 + pct) - 1;
    }

    /** {@code value} with the team's blessings for {@code stat} on it. */
    static double apply(SimStats.Stat stat, double value, Map<SimStats.Stat, double[]> effects) {
        double[] e = effects.get(stat);
        return e == null ? value : (value + e[0]) * (1 + e[1]);
    }

    /** Stone's weapon Damage. */
    static double bonusDamage() {
        return 6 * level(Blessing.STONE) * floorMultiplier();
    }

    /** Life's Health Regen, in percent. */
    static double bonusRegen() {
        return 3 * level(Blessing.LIFE) * floorMultiplier();
    }

    // ------------------------------------------------------------------ tab list

    /** Hypixel's footer block: "Dungeon Buffs" and one "Blessing of X N" per blessing held; empty with none. */
    public static List<String> footerLines() {
        List<String> out = new ArrayList<>();
        for (Blessing b : Blessing.values()) {
            int lvl = level(b);
            if (lvl > 0) {
                out.add("§7Blessing of " + b.label + " " + roman(lvl));
            }
        }
        if (!out.isEmpty()) {
            out.addFirst("§d§lDungeon Buffs");
        }
        return out;
    }

    /** A level in Hypixel's numerals (I .. L). */
    public static String roman(int n) {
        int[] values = {50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int left = Math.max(1, Math.min(89, n));
        for (int i = 0; i < values.length; i++) {
            while (left >= values[i]) {
                out.append(numerals[i]);
                left -= values[i];
            }
        }
        return out.toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
