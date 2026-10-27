package com.epic60869.sky2m.features.profileviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Levels worked out from a profile's raw XP: skills, Catacombs, classes, slayers and pets, with NEU's tables. */
public final class ProfileStats {
    /**
     * A level with the progress into the next one (0 to 1); {@code maxed} once it's at the cap. {@code overflowed}: an
     * overflow level, past the cap (Overflow Skill Levels).
     */
    public record Level(int level, double progress, double xp, double xpIntoLevel, double xpForNext, boolean maxed, boolean overflowed) {
        public Level(int level, double progress, double xp, double xpIntoLevel, double xpForNext, boolean maxed) {
            this(level, progress, xp, xpIntoLevel, xpForNext, maxed, false);
        }

        public double exact() {
            return level + (maxed ? 0 : progress);
        }
    }

    /** {key in the API, name, icon item, colour}. */
    public static final String[][] SKILLS = {
        {"farming", "Farming", "golden_hoe"}, {"mining", "Mining", "stone_pickaxe"}, {"combat", "Combat", "stone_sword"},
        {"foraging", "Foraging", "jungle_sapling"}, {"fishing", "Fishing", "fishing_rod"}, {"enchanting", "Enchanting", "enchanting_table"},
        {"alchemy", "Alchemy", "brewing_stand"}, {"taming", "Taming", "bone"}, {"carpentry", "Carpentry", "crafting_table"},
        {"runecrafting", "Runecrafting", "magma_cream"}, {"social", "Social", "emerald"}, {"hunting", "Hunting", "lead"}};
    /** Skills in the skill average. */
    private static final List<String> AVERAGE = List.of("farming", "mining", "combat", "foraging", "fishing", "enchanting", "alchemy", "taming", "carpentry");

    public static final String[][] SLAYERS = {{"zombie", "Revenant Horror", "rotten_flesh"}, {"spider", "Tarantula Broodfather", "spider_eye"},
        {"wolf", "Sven Packmaster", "mutton"}, {"enderman", "Voidgloom Seraph", "ender_pearl"}, {"blaze", "Inferno Demonlord", "blaze_powder"},
        {"vampire", "Riftstalker Bloodfiend", "redstone"}};

    public static final String[][] CLASSES = {{"healer", "Healer", "potion"}, {"mage", "Mage", "blaze_rod"}, {"berserk", "Berserk", "iron_sword"},
        {"archer", "Archer", "bow"}, {"tank", "Tank", "leather_chestplate"}};

    // Fallbacks for when NEU's tables haven't loaded.
    private static final long[] SKILL_XP = {50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000, 30000,
        50000, 75000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000, 1300000,
        1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000, 2500000, 2600000, 2750000,
        2900000, 3100000, 3400000, 3700000, 4000000, 4300000, 4600000, 4900000, 5200000, 5500000, 5800000, 6100000, 6400000, 6700000, 7000000};
    private static final long[] CATA_XP = {50, 75, 110, 160, 230, 330, 470, 670, 950, 1340, 1890, 2665, 3760, 5260, 7380, 10300,
        14400, 20000, 27600, 38000, 52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000, 1065000, 1410000,
        1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000, 15000000, 19000000, 24000000, 30000000,
        38000000, 48000000, 60000000, 75000000, 93000000, 116250000};

    private ProfileStats() {}

    // ------------------------------------------------------------------------------------------------ skills

    /** Profile Viewer > Overflow Levels: levels go past Hypixel's maximum, like SkyHanni's. */
    public static boolean overflow() {
        com.epic60869.sky2m.config.Sky2MConfig c = com.epic60869.sky2m.config.Sky2MConfig.current();
        return c == null || c.misc.profileViewer.overflowLevels;
    }

    /** A skill's level, past its cap too with Overflow Levels on. */
    public static Level skill(JsonObject member, String skill) {
        return skill(member, skill, overflow());
    }

    public static Level skill(JsonObject member, String skill, boolean overflow) {
        double xp = Json.num(Json.path(member, "player_data.experience"), "SKILL_" + skill.toUpperCase(Locale.ROOT));
        JsonObject t = ProfileApi.leveling();
        long[] table = switch (skill) {
            case "runecrafting" -> table(t, "runecrafting_xp", null);
            case "social" -> table(t, "social", null);
            default -> table(t, "leveling_xp", SKILL_XP);
        };
        int cap = t != null && Json.obj(t, "leveling_caps") != null ? (int) Json.num(Json.obj(t, "leveling_caps"), skill) : 0;
        if (cap <= 0) cap = switch (skill) {
            case "runecrafting", "social" -> 25;
            case "mining", "combat", "enchanting", "farming", "taming" -> 60;
            default -> 50;
        };
        // Anita's farming level cap upgrades.
        if (skill.equals("farming")) cap += (int) Json.num(Json.path(member, "jacobs_contest.perks"), "farming_level_cap");
        if (overflow && !skill.equals("runecrafting") && !skill.equals("social")) return overflowLevel(xp, table, cap);
        return level(xp, table, cap, 0);
    }

    /**
     * SkyHanni's overflow levels (SkillAPI.calculateSkillLevel): the normal table past the cap up to level 60, then
     * 7.6M XP for level 61, each level after 600k more, and that step doubling every ten levels.
     */
    private static Level overflowLevel(double xp, long[] steps, int cap) {
        double left = xp;
        int level = 0;
        while (level < Math.min(60, steps.length) && left >= steps[level]) {
            left -= steps[level];
            level++;
        }
        double next = level < steps.length ? steps[level] : 0;
        if (level >= Math.min(60, steps.length)) {
            double slope = 600_000;
            next = 7_000_000 + slope;
            while (left >= next) {
                level++;
                left -= next;
                next += slope;
                if (level % 10 == 0) slope *= 2;
            }
        }
        return new Level(level, next <= 0 ? 1 : left / next, xp, left, next, false, level >= cap);
    }

    public static boolean hasSkill(JsonObject member, String skill) {
        JsonObject xp = Json.path(member, "player_data.experience");
        return xp != null && xp.has("SKILL_" + skill.toUpperCase(Locale.ROOT));
    }

    /** The skill average (without Runecrafting, Social and Hunting), counting progress into the next level. */
    public static double skillAverage(JsonObject member) {
        double total = 0;
        // Capped levels, like SkyCrypt and SkyHanni's averages.
        for (String s : AVERAGE) total += skill(member, s, false).exact();
        return total / AVERAGE.size();
    }

    // ------------------------------------------------------------------------------------------------ dungeons

    /** Catacombs or a class level; past 50 every level takes another 200M, shown as overflow. */
    public static Level dungeon(double xp) {
        return level(xp, table(ProfileApi.leveling(), "catacombs", CATA_XP), 50, overflow() ? 200_000_000L : 0);
    }

    public static Level catacombs(JsonObject member) {
        return dungeon(Json.num(Json.path(member, "dungeons.dungeon_types.catacombs"), "experience"));
    }

    public static Level dungeonClass(JsonObject member, String name) {
        return dungeon(Json.num(Json.path(member, "dungeons.player_classes." + name), "experience"));
    }

    // ------------------------------------------------------------------------------------------------ slayers

    public static Level slayer(JsonObject member, String slayer) {
        double xp = Json.num(Json.path(member, "slayer.slayer_bosses." + slayer), "xp");
        long[] thresholds = cumulativeToSteps(table(Json.obj(ProfileApi.leveling(), "slayer_xp"), slayer, slayer.equals("vampire")
            ? new long[]{20, 75, 240, 840, 2400} : slayer.equals("zombie") || slayer.equals("spider")
            ? new long[]{5, 15, 200, 1000, 5000, 20000, 100000, 400000, 1000000} : new long[]{10, 30, 250, 1500, 5000, 20000, 100000, 400000, 1000000}));
        return level(xp, thresholds, thresholds.length, 0);
    }

    /** Kills of each tier (index 0 is tier I). */
    public static int[] slayerKills(JsonObject member, String slayer) {
        JsonObject boss = Json.path(member, "slayer.slayer_bosses." + slayer);
        int[] kills = new int[5];
        for (int i = 0; i < 5; i++) kills[i] = (int) Json.num(boss, "boss_kills_tier_" + i);
        return kills;
    }

    // ------------------------------------------------------------------------------------------------ pets

    public record Pet(String type, String tier, double exp, int level, int maxLevel, double progress, String heldItem, boolean active, String skin, int candy) {
        public String name() {
            JsonObject names = Json.obj(ProfileApi.pets(), "id_to_display_name");
            String n = Json.str(names, type);
            if (!n.isEmpty()) return n;
            StringBuilder sb = new StringBuilder();
            for (String w : type.toLowerCase(Locale.ROOT).split("_")) if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            return sb.toString().trim();
        }

        /** NEU's item id for the pet: TYPE;rarity number. */
        public String itemId() {
            return type + ";" + rarityIndex(tier);
        }
    }

    public static List<Pet> pets(JsonObject member) {
        List<Pet> out = new ArrayList<>();
        JsonArray list = Json.arr(Json.obj(member, "pets_data"), "pets");
        for (JsonElement e : list) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            String type = Json.str(p, "type"), tier = Json.str(p, "tier");
            double exp = Json.num(p, "exp");
            String held = Json.str(p, "heldItem");
            // A Tier Boost raises the pet's rarity by one.
            if (held.equals("PET_ITEM_TIER_BOOST")) tier = bump(tier);
            int[] levelInfo = new int[2];
            double progress = petLevel(type, tier, exp, levelInfo);
            out.add(new Pet(type, tier, exp, levelInfo[0], levelInfo[1], progress, held, Json.bool(p, "active"), Json.str(p, "skin"), (int) Json.num(p, "candyUsed")));
        }
        out.sort((a, b) -> {
            if (a.active() != b.active()) return a.active() ? -1 : 1;
            int r = Integer.compare(rarityIndex(b.tier()), rarityIndex(a.tier()));
            if (r != 0) return r;
            r = Integer.compare(b.level(), a.level());
            return r != 0 ? r : a.type().compareTo(b.type());
        });
        return out;
    }

    /** Sets {level, max level} in {@code out} and returns the progress into the next level. */
    private static double petLevel(String type, String tier, double exp, int[] out) {
        JsonObject t = ProfileApi.pets();
        if (t == null) {
            out[0] = 1;
            out[1] = 100;
            return 0;
        }
        int offset = (int) Json.num(Json.obj(t, "pet_rarity_offset"), tier);
        JsonArray all = Json.arr(t, "pet_levels");
        List<Long> steps = new ArrayList<>();
        for (int i = offset; i < Math.min(all.size(), offset + 99); i++) steps.add(all.get(i).getAsLong());
        int maxLevel = 100;
        JsonObject custom = Json.obj(Json.obj(t, "custom_pet_leveling"), type);
        if (custom != null) {
            if (custom.has("max_level")) maxLevel = custom.get("max_level").getAsInt();
            if ((int) Json.num(custom, "type") == 1) for (JsonElement e : Json.arr(custom, "pet_levels")) steps.add(e.getAsLong());
        }
        int level = 1;
        for (long step : steps) {
            if (level >= maxLevel) break;
            if (exp < step) {
                out[0] = level;
                out[1] = maxLevel;
                return exp / step;
            }
            exp -= step;
            level++;
        }
        out[0] = Math.min(level, maxLevel);
        out[1] = maxLevel;
        return 1;
    }

    private static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

    public static int rarityIndex(String tier) {
        for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equals(tier)) return i;
        return 0;
    }

    private static String bump(String tier) {
        int i = rarityIndex(tier);
        return i + 1 < RARITIES.length ? RARITIES[i + 1] : tier;
    }

    public static ChatFormatting rarityColour(String tier) {
        return switch (tier) {
            case "UNCOMMON" -> ChatFormatting.GREEN;
            case "RARE" -> ChatFormatting.BLUE;
            case "EPIC" -> ChatFormatting.DARK_PURPLE;
            case "LEGENDARY" -> ChatFormatting.GOLD;
            case "MYTHIC" -> ChatFormatting.LIGHT_PURPLE;
            case "DIVINE" -> ChatFormatting.AQUA;
            case "SPECIAL", "VERY_SPECIAL" -> ChatFormatting.RED;
            default -> ChatFormatting.WHITE;
        };
    }

    // ------------------------------------------------------------------------------------------------ misc

    public static double skyblockLevel(JsonObject member) {
        return Json.num(Json.obj(member, "leveling"), "experience") / 100.0;
    }

    public static double purse(JsonObject member) {
        return Json.num(Json.obj(member, "currencies"), "coin_purse");
    }

    /** The profile's bank, or -1 when the banking API is off. */
    public static double bank(JsonObject profile) {
        JsonObject banking = Json.obj(profile, "banking");
        return banking == null ? -1 : Json.num(banking, "balance");
    }

    public static double personalBank(JsonObject member) {
        return Json.num(Json.obj(member, "profile"), "bank_account");
    }

    public static int fairySouls(JsonObject member) {
        return (int) Json.num(Json.obj(member, "fairy_soul"), "total_collected");
    }

    public static int magicalPower(JsonObject member) {
        return (int) Json.num(Json.obj(member, "accessory_bag_storage"), "highest_magical_power");
    }

    public static String selectedPower(JsonObject member) {
        String power = Json.str(Json.obj(member, "accessory_bag_storage"), "selected_power");
        return power.isEmpty() ? "None" : Character.toUpperCase(power.charAt(0)) + power.substring(1).replace('_', ' ');
    }

    // ------------------------------------------------------------------------------------------------ tables

    private static Level level(double xp, long[] steps, int cap, long overflowStep) {
        double left = xp;
        int level = 0;
        for (int i = 0; i < Math.min(cap, steps.length); i++) {
            if (left < steps[i]) return new Level(level, left / steps[i], xp, left, steps[i], false);
            left -= steps[i];
            level++;
        }
        if (overflowStep > 0) {
            int extra = (int) (left / overflowStep);
            double into = left - extra * (double) overflowStep;
            return new Level(level + extra, into / overflowStep, xp, into, overflowStep, false, true);
        }
        return new Level(level, 1, xp, left, 0, true);
    }

    private static long[] table(JsonObject source, String key, long[] fallback) {
        JsonArray array = Json.arr(source, key);
        if (array.isEmpty()) return fallback == null ? new long[]{50} : fallback;
        long[] out = new long[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsLong();
        return out;
    }

    /** Slayer thresholds are totals; turn them into the XP each level takes. */
    private static long[] cumulativeToSteps(long[] totals) {
        long[] out = new long[totals.length];
        long previous = 0;
        for (int i = 0; i < totals.length; i++) {
            out[i] = Math.max(1, totals[i] - previous);
            previous = totals[i];
        }
        return out;
    }

    /** 1.2M, 34.5k, 999. */
    public static String shortNumber(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
        return String.valueOf((long) v);
    }

    public static String commas(double v) {
        return String.format(Locale.ROOT, "%,d", (long) v);
    }
}
