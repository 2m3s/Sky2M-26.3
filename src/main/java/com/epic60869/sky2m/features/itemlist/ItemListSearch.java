package com.epic60869.sky2m.features.itemlist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Item List's search, written for Sky2M to behave like SkyBlock Item List's:
 * <ul>
 *   <li>{@code @pets wolf}: only that category (Items, Attributes, Enchants, Mobs, NPCs, Pets, Potions, Runes).</li>
 *   <li>{@code wise | hyperion}: either.</li>
 *   <li>{@code dragon & helmet}: both.</li>
 *   <li>{@code #ENCHANTED_}: items whose SkyBlock id starts with that.</li>
 *   <li>Otherwise the words are found in the name or the lore, optionally allowing small typos.</li>
 * </ul>
 */
public final class ItemListSearch {
    private ItemListSearch() {}

    /** A parsed search: its category (or null) and its OR groups of AND terms. */
    public record Query(ItemListData.Category category, List<List<String>> groups, String text) {
        public boolean isEmpty() {
            return groups.isEmpty() && category == null;
        }
    }

    public static Query parse(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        ItemListData.Category category = null;
        if (text.startsWith("@")) {
            int space = text.indexOf(' ');
            String name = space < 0 ? text.substring(1) : text.substring(1, space);
            category = category(name);
            if (category != null) text = space < 0 ? "" : text.substring(space + 1).trim();
        }
        List<List<String>> groups = new ArrayList<>();
        for (String group : text.split("\\|")) {
            List<String> terms = new ArrayList<>();
            for (String term : group.split("&")) {
                String t = term.trim();
                if (!t.isEmpty()) terms.add(t);
            }
            if (!terms.isEmpty()) groups.add(terms);
        }
        return new Query(category, groups, text);
    }

    /** The category whose name is exactly {@code name}, or null. */
    public static ItemListData.Category category(String name) {
        for (ItemListData.Category c : ItemListData.Category.values()) {
            if (c != ItemListData.Category.ALL && c.label.equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    /** "@pe" -> "ts", for the search box's grey suggestion. */
    public static String suggestion(String raw) {
        if (raw == null || !raw.startsWith("@") || raw.contains(" ")) return "";
        String typed = raw.substring(1).toLowerCase(Locale.ROOT);
        for (ItemListData.Category c : ItemListData.Category.values()) {
            String label = c.label.toLowerCase(Locale.ROOT);
            if (c != ItemListData.Category.ALL && label.startsWith(typed)) return label.substring(typed.length());
        }
        return "";
    }

    public static boolean matches(ItemListData.Entry e, Query q, boolean fuzzy) {
        if (q.category() != null && e.category() != q.category()) return false;
        if (q.groups().isEmpty()) return true;
        for (List<String> group : q.groups()) {
            boolean all = true;
            for (String term : group) {
                if (!term(e, term, fuzzy)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean term(ItemListData.Entry e, String term, boolean fuzzy) {
        if (term.startsWith("#")) return e.id().toLowerCase(Locale.ROOT).startsWith(term.substring(1));
        if (e.searchName().contains(term) || e.searchLore().contains(term)) return true;
        return fuzzy && (fuzzy(term, e.searchName()));
    }

    /** Whether the name starts with the search (those are listed first). */
    public static boolean prefix(ItemListData.Entry e, Query q) {
        if (q.groups().isEmpty()) return false;
        String first = q.groups().getFirst().getFirst();
        return !first.startsWith("#") && e.searchName().startsWith(first);
    }

    // ------------------------------------------------------------------------------------------------ typos

    /** A word of {@code text} within a typo or two of {@code query} (more allowed for longer words). */
    private static boolean fuzzy(String query, String text) {
        int allowed = query.length() <= 2 ? 0 : query.length() <= 5 ? 1 : 2;
        if (allowed == 0) return false;
        for (String word : text.split(" ")) {
            if (word.isEmpty() || query.length() > word.length() + allowed) continue;
            if (substringDistance(query, word) <= allowed) return true;
        }
        return distance(query.replace(" ", ""), text.replace(" ", "")) <= allowed;
    }

    /** Fewest edits to make {@code query} appear somewhere in {@code word}. */
    private static int substringDistance(String query, String word) {
        int[] prev = new int[word.length() + 1];
        int[] cur = new int[word.length() + 1];
        for (int i = 1; i <= query.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= word.length(); j++) {
                int cost = query.charAt(i - 1) == word.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(prev[j - 1] + cost, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        int best = Integer.MAX_VALUE;
        for (int v : prev) best = Math.min(best, v);
        return best;
    }

    private static int distance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) return Integer.MAX_VALUE;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(prev[j - 1] + cost, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
