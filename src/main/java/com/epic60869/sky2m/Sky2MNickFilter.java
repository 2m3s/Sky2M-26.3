package com.epic60869.sky2m;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Blocks swearing and slurs in nicknames. Text is normalised first (lowercase, common
 * letter substitutions like 4→a and $→s, repeated letters collapsed) so simple tricks do not
 * get through. Short words that often appear inside normal words are only matched as whole words.
 */
public final class Sky2MNickFilter {
    /** Matched anywhere in the name. */
    private static final List<String> ANYWHERE = List.of(
        "fuck", "fuk", "shit", "cunt", "bitch", "nigger", "nigga", "niga", "faggot", "fagot", "retard",
        "whore", "slut", "kike", "tranny", "hitler", "asshole", "bastard", "pussy", "dildo",
        "porn", "nazi", "penis", "vagina", "rapist", "molest");
    /** Matched only as a whole word, because they appear inside normal words (class, peacock, grape, ...). */
    private static final List<String> WHOLE_WORD = List.of(
        "ass", "cock", "dick", "fag", "kys", "rape", "twat", "tits", "sex", "coon", "spic", "chink", "cum", "pedo", "wank");

    private static final Pattern REPEATS = Pattern.compile("(.)\\1+");

    private Sky2MNickFilter() {}

    /** Lowercase with look-alike characters swapped for letters (4 -> a, $ -> s, ...). */
    private static String normalise(String name) {
        return Sky2MNickFonts.plain(name)
            .replace('0', 'o').replace('1', 'i').replace('!', 'i').replace('3', 'e')
            .replace('4', 'a').replace('@', 'a').replace('5', 's').replace('$', 's')
            .replace('7', 't').replace('8', 'b');
    }

    /**
     * Whether a nickname would pass for SJ staff: brackets (fake prefixes like "[OWNER]"), the words owner or
     * tester (or any rank from shadowisabot.com), or a ranked account's name (also with look-alike characters, e.g. "2M3S" or "Sv1nkus").
     */
    public static boolean impersonatesStaff(String name) {
        return impersonatesStaff(name, null);
    }

    /** As above, but a ranked account may use its own name and its own rank word ({@code owner} is that account). */
    public static boolean impersonatesStaff(String name, java.util.UUID owner) {
        if (name.indexOf('[') >= 0 || name.indexOf(']') >= 0) return true;
        String joined = normalise(name).replaceAll("[^a-z0-9]+", "");
        String letters = joined.replaceAll("[^a-z]+", "");
        for (String word : Sky2MStaff.roleWords(owner)) if (letters.contains(word)) return true;
        for (String staff : Sky2MStaff.names(owner)) {
            String target = normalise(staff).replaceAll("[^a-z0-9]+", "");
            if (joined.contains(target) || REPEATS.matcher(joined).replaceAll("$1").contains(REPEATS.matcher(target).replaceAll("$1"))) return true;
        }
        return false;
    }

    /** Whether the nickname contains a blocked word or pretends to be SJ staff. */
    public static boolean isBlocked(String name) {
        return isBlocked(name, null);
    }

    /** Whether {@code owner}'s nickname is blocked; ranked players may use their own name. */
    public static boolean isBlocked(String name, java.util.UUID owner) {
        if (name == null || name.isBlank()) return false;
        if (impersonatesStaff(name, owner)) return true;
        String lower = normalise(name);

        // Words separated by anything that is not a letter; collapsed version for "fuuuck" and "f.u.c.k".
        String spaced = lower.replaceAll("[^a-z]+", " ").trim();
        String joined = spaced.replace(" ", "");
        String collapsed = REPEATS.matcher(joined).replaceAll("$1");

        for (String word : ANYWHERE) {
            if (joined.contains(word) || collapsed.contains(REPEATS.matcher(word).replaceAll("$1"))) return true;
        }
        for (String word : spaced.split(" ")) {
            String single = REPEATS.matcher(word).replaceAll("$1");
            if (WHOLE_WORD.contains(word) || WHOLE_WORD.contains(single)) return true;
        }
        return false;
    }
}
