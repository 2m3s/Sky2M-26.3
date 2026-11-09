package com.epic60869.sky2m.commandkeys;

import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sim.dungeonclass.DungeonClass;
import com.epic60869.sky2m.sim.leapmenu.PartyTracker;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart command keys: a message of a macro can start with a condition in square brackets, like
 * {@code [dungeon] /gfs spirit_leap}, {@code [garden,hub] /warp x} or {@code [tank] /pc ready}. The message only counts
 * while one of the listed words is true where you are; otherwise the key press skips it.
 *
 * <p>It lives in the message text, so saved macros load as they always did. Brackets only count as a condition when every
 * word in them is a known one: {@code [hello] world} is still an ordinary chat message.
 */
public final class KeyConditions {
    private static final Pattern PREFIX = Pattern.compile("^\\[([^\\[\\]]+)]\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern FLOOR = Pattern.compile("[fm][1-7]");
    private static final Set<String> DUNGEON_WORDS = Set.of("dungeon", "dungeons", "catacombs");
    private static final String MINING = "mining";
    private static final List<String> MINING_AREAS = List.of("Dwarven Mines", "Crystal Hollows", "Mineshaft");
    /** Words for a dungeon class. Both spellings of berserker, as the game and players use both. */
    private static final Map<String, String> CLASSES = Map.of(
        "mage", "mage", "tank", "tank", "healer", "healer", "archer", "archer", "berserk", "berserker", "berserker", "berserker");
    /** Word without spaces or apostrophes -> the tab list's "Area: ..." name. */
    private static final Map<String, String> AREAS = new HashMap<>();
    static {
        area("Hub", "hub");
        area("Dungeon Hub", "dungeonhub", "dhub");
        area("Garden", "garden");
        area("Private Island", "privateisland", "island");
        area("Crimson Isle", "crimsonisle", "crimson", "isle");
        area("The End", "theend", "end");
        area("The Park", "thepark", "park");
        area("Spider's Den", "spidersden", "spiders");
        area("The Rift", "therift", "rift");
        area("Dwarven Mines", "dwarvenmines", "dwarven", "mines");
        area("Crystal Hollows", "crystalhollows", "hollows", "ch");
        area("Mineshaft", "mineshaft");
        area("Gold Mine", "goldmine");
        area("Deep Caverns", "deepcaverns");
        area("The Farming Islands", "thefarmingislands", "farmingislands", "farming");
        area("Galatea", "galatea");
        area("Kuudra", "kuudra");
        area("Jerry's Workshop", "jerrysworkshop", "jerry");
        area("Dark Auction", "darkauction");
    }

    private KeyConditions() {}

    private static void area(String name, String... words) {
        for (String word : words) AREAS.put(word, name);
    }

    /** A message split into its condition words (empty when it has none) and the text to send. */
    public record Parsed(Set<String> conditions, String text) {}

    private static String word(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace(" ", "").replace("'", "");
    }

    private static boolean known(String word) {
        return DUNGEON_WORDS.contains(word) || word.equals(MINING) || word.equals("entrance") || FLOOR.matcher(word).matches()
            || CLASSES.containsKey(word) || AREAS.containsKey(word);
    }

    /** Splits off the condition. Anything that isn't a condition comes back whole, with no conditions. Public for the game test. */
    public static Parsed parse(String message) {
        Matcher m = PREFIX.matcher(message == null ? "" : message);
        if (!m.matches()) return new Parsed(Set.of(), message == null ? "" : message);
        Set<String> words = new LinkedHashSet<>();
        for (String raw : m.group(1).split(",")) {
            String word = word(raw);
            if (!known(word)) return new Parsed(Set.of(), message);
            words.add(word);
        }
        return words.isEmpty() ? new Parsed(Set.of(), message) : new Parsed(words, m.group(2));
    }

    /**
     * True when any word holds. No words means no condition. Public for the game test.
     *
     * @param area         the tab list's area, like "Garden"
     * @param floor        the dungeon floor like "F7", or ""
     * @param dungeonClass your dungeon class name in any case, or null outside a dungeon
     */
    public static boolean matches(Set<String> conditions, String area, String floor, boolean inDungeon, String dungeonClass) {
        if (conditions.isEmpty()) return true;
        for (String word : conditions) {
            if (DUNGEON_WORDS.contains(word)) {
                if (inDungeon) return true;
            } else if (word.equals(MINING)) {
                if (MINING_AREAS.stream().anyMatch(a -> a.equalsIgnoreCase(area))) return true;
            } else if (word.equals("entrance")) {
                if ("E".equalsIgnoreCase(floor)) return true;
            } else if (FLOOR.matcher(word).matches()) {
                if (word.equalsIgnoreCase(floor)) return true;
            } else if (CLASSES.containsKey(word)) {
                if (CLASSES.get(word).equalsIgnoreCase(dungeonClass)) return true;
            } else if (AREAS.get(word).equalsIgnoreCase(area)) {
                return true;
            }
        }
        return false;
    }

    /** What to send for this message where you are now: its text without the condition, or null when it should be skipped. */
    public static String resolve(String message) {
        Parsed parsed = parse(message);
        if (parsed.conditions().isEmpty()) return parsed.text();
        boolean inDungeon = Sky2MLocation.inDungeon();
        DungeonClass self = inDungeon ? PartyTracker.selfClass() : null;
        boolean ok = matches(parsed.conditions(), Sky2MLocation.area(), Sky2MLocation.dungeonFloor(), inDungeon, self == null ? null : self.name());
        return ok ? parsed.text() : null;
    }
}
