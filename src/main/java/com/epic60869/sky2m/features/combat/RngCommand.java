package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 rng [magicFind] [petLuck] [overbloom], /s2 rng find &lt;text&gt;: opens {@link RngScreen}, a searchable table of
 * every RNG drop the mod knows about (the drops the RNG drop totem tracks) with its base chance from the item list's
 * mob drop data and what it becomes with your stats.
 *
 * <p>Formulas, from the Hypixel SkyBlock wiki:
 * <ul>
 * <li>Mob drops: https://hypixelskyblock.minecraft.wiki/w/Magic_Find and https://hypixelskyblock.minecraft.wiki/w/Drop_Chance :
 *     chance = base x (1 + 0.15 x Looting) x (1 + 0.05 x Luck) x (1 + MF / 100), where Magic Find only applies while the
 *     base chance (after Looting) is strictly below 5%.</li>
 * <li>Pet drops: https://hypixelskyblock.minecraft.wiki/w/Pet_Luck : chance = base x (1 + (MF + Pet Luck) / 100); Looting
 *     does not apply to pets.</li>
 * <li>Pests / rare crops: https://hypixelskyblock.minecraft.wiki/w/Overbloom : chance = base x (1 + Overbloom / 100);
 *     Overbloom replaces Magic Find there.</li>
 * </ul>
 */
public final class RngCommand {
    private static final Pattern PERCENT = Pattern.compile("([0-9]*\\.?[0-9]+)\\s*%");
    private static final Pattern ONE_IN = Pattern.compile("1\\s*(?:/|in|:)\\s*([0-9,]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE);
    /** "1-3%" or "1.5 - 3%": the lower end counts. */
    private static final Pattern RANGE = Pattern.compile("([0-9]*\\.?[0-9]+)\\s*[-–]\\s*[0-9]*\\.?[0-9]+\\s*%");

    /** The formula that applies to a drop. */
    public enum Formula {
        MOB("Mob", "base x (1 + 0.15 x Looting) x (1 + 0.05 x Luck) x (1 + MF / 100)",
            "Magic Find only applies while the chance (after Looting) is under 5%. Wiki: Magic Find, Drop Chance."),
        PET("Pet", "base x (1 + (MF + Pet Luck) / 100)",
            "Pet Luck adds to Magic Find for pet drops; Looting does not apply to pets. Wiki: Pet Luck."),
        PEST("Pest", "base x (1 + Overbloom / 100)",
            "Garden pests and rare crops use Overbloom instead of Magic Find. Wiki: Overbloom."),
        PEST_PET("Pest pet", "base x (1 + Overbloom / 100 + Pet Luck / 600)",
            "Rat and Slug pet drops from pests use Overbloom and Pet Luck (Pet Luck / 600). Wiki: Pests."),
        NONE("Fixed", "base",
            "Chests and similar sources are not affected by Magic Find (not confirmed on the wiki).");

        public final String label;
        public final String formula;
        public final String note;

        Formula(String label, String formula, String note) {
            this.label = label;
            this.formula = formula;
            this.note = note;
        }
    }

    private RngCommand() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("rng")
                    .executes(c -> open(null))
                    .then(ClientCommands.literal("find").then(ClientCommands.argument("filter", StringArgumentType.greedyString())
                        .executes(c -> open(StringArgumentType.getString(c, "filter")))))
                    .then(ClientCommands.argument("magicFind", DoubleArgumentType.doubleArg(0, 100000))
                        .executes(c -> {
                            store(DoubleArgumentType.getDouble(c, "magicFind"), -1, -1);
                            return open(null);
                        })
                        .then(ClientCommands.argument("petLuck", DoubleArgumentType.doubleArg(0, 100000))
                            .executes(c -> {
                                store(DoubleArgumentType.getDouble(c, "magicFind"), DoubleArgumentType.getDouble(c, "petLuck"), -1);
                                return open(null);
                            })
                            .then(ClientCommands.argument("overbloom", DoubleArgumentType.doubleArg(0, 100000))
                                .executes(c -> {
                                    store(DoubleArgumentType.getDouble(c, "magicFind"), DoubleArgumentType.getDouble(c, "petLuck"),
                                        DoubleArgumentType.getDouble(c, "overbloom"));
                                    return open(null);
                                }))))));
            }
        });
    }

    private static int open(String search) {
        return Compat.queueOpenScreen(new RngScreen(search));
    }

    /** Saves the stats given on the command line (a negative value keeps the saved one). */
    private static void store(double magicFind, double petLuck, double overbloom) {
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null) return;
        FeatureConfigs.RareDrops s = c.combat.rareDrops;
        s.rngMagicFind = trim(magicFind);
        if (petLuck >= 0) s.rngPetLuck = trim(petLuck);
        if (overbloom >= 0) s.rngOverbloom = trim(overbloom);
    }

    /** The formula for a drop from its name and where it drops. */
    static Formula formulaFor(String drop, String source, boolean pet) {
        String s = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (s.contains("pest")) return pet ? Formula.PEST_PET : Formula.PEST;
        if (s.contains("chest") || s.contains("rng meter")) return Formula.NONE;
        return pet ? Formula.PET : Formula.MOB;
    }

    /** Which of Magic Find, Pet Luck and Overbloom change a drop with this base chance: {mf, pl, overbloom}. */
    static boolean[] affectedBy(Formula formula, double base) {
        boolean small = base < 0.05;
        return switch (formula) {
            case MOB -> new boolean[] {small, false, false};
            case PET -> new boolean[] {small, small, false};
            case PEST -> new boolean[] {false, false, true};
            case PEST_PET -> new boolean[] {false, true, true};
            default -> new boolean[] {false, false, false};
        };
    }

    /** A base chance as a fraction (0.01 = 1%), or -1 when the text has none. */
    static double parseChance(String text) {
        if (text == null) return -1;
        String plain = text.replaceAll("§.", "");
        Matcher m = RANGE.matcher(plain);
        if (m.find()) return Double.parseDouble(m.group(1)) / 100.0;
        m = PERCENT.matcher(plain);
        if (m.find()) return Double.parseDouble(m.group(1)) / 100.0;
        m = ONE_IN.matcher(plain);
        if (m.find()) {
            double n = Double.parseDouble(m.group(1).replace(",", ""));
            return n > 0 ? 1.0 / n : -1;
        }
        return -1;
    }

    /** The chance with your stats under the drop's formula (see the class comment), at most 100%. */
    static double chance(Formula formula, double base, double magicFind, double petLuck, double overbloom, double looting, double luck) {
        double p;
        switch (formula) {
            case PET -> p = base < 0.05 ? base * (1 + (magicFind + petLuck) / 100.0) : base;
            case PEST -> p = base * (1 + overbloom / 100.0);
            case PEST_PET -> p = base * (1 + overbloom / 100.0 + petLuck / 600.0);
            case NONE -> p = base;
            default -> {
                double withLooting = base * (1 + 0.15 * looting) * (1 + 0.05 * luck);
                p = withLooting < 0.05 ? withLooting * (1 + magicFind / 100.0) : withLooting;
            }
        }
        return Math.min(1.0, p);
    }

    static String percent(double p) {
        return p >= 0.1 ? String.format(Locale.US, "%.1f%%", p * 100)
            : p >= 0.001 ? String.format(Locale.US, "%.2f%%", p * 100) : String.format(Locale.US, "%.4f%%", p * 100);
    }

    static String chanceText(double p) {
        return percent(p) + " (1 in " + String.format(Locale.US, "%,.0f", 1.0 / p) + ")";
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }
}
