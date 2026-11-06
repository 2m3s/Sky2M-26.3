package com.epic60869.sky2m.features.tracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@link RngListScreen} lists: the picked category (null for all), the All / Missing / Obtained filter and the search.
 * Kept while the game runs, so the screen opens where you left it.
 */
final class RngView {
    static final String[] FILTERS = {"All", "Missing", "Obtained"};

    static String category;
    static int filter;
    static String search = "";

    /** A line on the right: a category heading (item null) or a drop, with the category it's listed under. */
    record Line(String heading, String item, RngDropList.Category category) {}

    private RngView() {}

    static boolean shown(String item, Map<String, RngTracker.Got> got) {
        boolean has = got.containsKey(item);
        if (filter == 1 && has || filter == 2 && !has) return false;
        return search.isBlank() || item.toLowerCase(Locale.ROOT).contains(search.trim().toLowerCase(Locale.ROOT));
    }

    static List<Line> lines(Map<String, RngTracker.Got> got) {
        List<Line> out = new ArrayList<>();
        for (RngDropList.Category c : RngDropList.ALL) {
            if (category != null && !category.equals(c.name())) continue;
            List<Line> items = new ArrayList<>();
            for (String item : c.items()) if (shown(item, got)) items.add(new Line(null, item, c));
            if (items.isEmpty()) continue;
            if (category == null) out.add(new Line(c.name(), null, c));
            out.addAll(items);
        }
        return out;
    }

    static int have(RngDropList.Category c, Map<String, RngTracker.Got> got) {
        int n = 0;
        for (String item : c.items()) if (got.containsKey(item)) n++;
        return n;
    }

    /** The drops listed right now, for the game test. */
    static List<String> items(Map<String, RngTracker.Got> got) {
        List<String> out = new ArrayList<>();
        for (Line line : lines(got)) if (line.item() != null) out.add(line.item());
        return out;
    }
}
