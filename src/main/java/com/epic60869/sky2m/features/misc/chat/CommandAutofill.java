package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.price.PriceService;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.minecraft.ChatFormatting;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client side argument suggestions for Hypixel commands whose arguments the server doesn't suggest: Bazaar products
 * for {@code /bz} and {@code /bazaar}, item names for {@code /ahs} and {@code /ahsearch}, and item ids for
 * {@code /viewrecipe} and {@code /recipe}; other commands go to {@link CommandTabComplete} (players, /warp, /gfs...). Shown in vanilla's command suggestion popup (see
 * {@code Sky2MCommandAutofillMixin}); nothing is sent to the server and no command is registered.
 *
 * <p>Matching ignores case and treats spaces and underscores the same ("enchanted dia" finds
 * {@code enchanted_diamond}). Prefix matches come first, then shorter, then alphabetical.
 */
public final class CommandAutofill {
    private static final int MAX_SUGGESTIONS = 100;

    /** A suggestion and its lower-case, underscored search key. */
    private record Entry(String text, String key) {}

    private static @Nullable Object bazaarSource;
    private static List<Entry> bazaar = List.of();
    private static @Nullable Object itemsSource;
    private static List<Entry> itemNames = List.of();
    private static List<Entry> itemIds = List.of();

    private CommandAutofill() {}

    /**
     * Suggestions for the chat input up to the cursor, or null when it isn't one of the supported commands (or the
     * feature is off). The suggestions replace the whole argument, from just after the command to the cursor.
     */
    public static @Nullable Suggestions suggest(String uptoCursor) {
        Config config = config();
        if (config == null || !config.enabled) return null;
        if (!uptoCursor.startsWith("/")) return null;
        int space = uptoCursor.indexOf(' ');
        if (space < 0) return null;
        String command = uptoCursor.substring(1, space).toLowerCase(Locale.ROOT);
        List<Entry> source = switch (command) {
            case "bz", "bzs", "bazaar" -> config.bazaar ? bazaarEntries() : null;
            case "ahs", "ahsearch" -> config.auctionHouse ? itemNameEntries() : null;
            case "viewrecipe", "recipe" -> config.recipes ? itemIdEntries() : null;
            default -> null;
        };
        if (source == null) return CommandTabComplete.suggest(config, uptoCursor);
        if (source.isEmpty()) return null;

        int start = space;
        while (start < uptoCursor.length() && uptoCursor.charAt(start) == ' ') start++;
        String query = normalise(uptoCursor.substring(start));

        List<Entry> prefix = new ArrayList<>(), contains = new ArrayList<>();
        for (Entry entry : source) {
            if (entry.key().startsWith(query)) prefix.add(entry);
            else if (entry.key().contains(query)) contains.add(entry);
        }
        Comparator<Entry> order = Comparator.<Entry>comparingInt(e -> e.key().length()).thenComparing(Entry::key);
        prefix.sort(order);
        List<Suggestion> list = new ArrayList<>();
        StringRange range = StringRange.between(start, uptoCursor.length());
        for (Entry entry : prefix) {
            if (list.size() >= MAX_SUGGESTIONS) break;
            list.add(new Suggestion(range, entry.text()));
        }
        if (list.size() < MAX_SUGGESTIONS) {
            contains.sort(order);
            for (Entry entry : contains) {
                if (list.size() >= MAX_SUGGESTIONS) break;
                list.add(new Suggestion(range, entry.text()));
            }
        }
        // Built directly (not with SuggestionsBuilder) so brigadier doesn't re-sort them alphabetically.
        return list.isEmpty() ? null : new Suggestions(range, list);
    }

    /**
     * Ours plus what the server or another mod suggested for the same argument (theirs first, no duplicates). Falls
     * back to brigadier's merge when the ranges differ.
     */
    public static Suggestions merge(String input, Suggestions theirs, Suggestions ours) {
        if (theirs.isEmpty()) return ours;
        if (!theirs.getRange().equals(ours.getRange())) return Suggestions.merge(input, List.of(theirs, ours));
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> list = new ArrayList<>();
        for (Suggestion suggestion : theirs.getList()) if (seen.add(suggestion.getText().toLowerCase(Locale.ROOT))) list.add(suggestion);
        for (Suggestion suggestion : ours.getList()) if (seen.add(suggestion.getText().toLowerCase(Locale.ROOT))) list.add(suggestion);
        return new Suggestions(ours.getRange(), list);
    }

    // ----- Data (rebuilt only when PriceService hands out a new snapshot) -----

    private static List<Entry> bazaarEntries() {
        PriceService.Bazaar snapshot = PriceService.bazaar();
        if (snapshot != bazaarSource) {
            Set<String> ids = new LinkedHashSet<>(snapshot.buy().keySet());
            ids.addAll(snapshot.sell().keySet());
            Map<String, JsonObject> items = PriceService.items();
            List<Entry> next = new ArrayList<>(ids.size());
            Set<String> seen = new LinkedHashSet<>();
            for (String id : ids) {
                // The item's real name, as the Bazaar shows it: spaces stay spaces, and a hyphen only where the
                // name has one. Products without a known name fall back to their id with spaces.
                JsonObject item = items == null ? null : items.get(id);
                JsonElement nameElement = item == null ? null : item.get("name");
                String name = nameElement != null && nameElement.isJsonPrimitive() ? cleanName(nameElement.getAsString()) : "";
                if (name.isEmpty()) name = id.toLowerCase(Locale.ROOT).replace('_', ' ');
                if (seen.add(name)) next.add(new Entry(name, normalise(name) + " " + normalise(id)));
            }
            bazaar = next;
            bazaarSource = snapshot;
        }
        return bazaar;
    }

    private static List<Entry> itemNameEntries() {
        loadItems();
        return itemNames;
    }

    private static List<Entry> itemIdEntries() {
        loadItems();
        return itemIds;
    }

    private static void loadItems() {
        Map<String, JsonObject> items = PriceService.items();
        if (items == itemsSource) return;
        List<Entry> ids = new ArrayList<>(items.size());
        List<Entry> names = new ArrayList<>(items.size());
        Set<String> seenNames = new LinkedHashSet<>();
        for (Map.Entry<String, JsonObject> item : items.entrySet()) {
            String id = item.getKey();
            ids.add(new Entry(id, normalise(id)));
            JsonElement nameElement = item.getValue().get("name");
            if (nameElement == null || !nameElement.isJsonPrimitive()) continue;
            String name = cleanName(nameElement.getAsString());
            if (!name.isEmpty() && seenNames.add(name)) names.add(new Entry(name, normalise(name)));
        }
        itemIds = ids;
        itemNames = names;
        itemsSource = items;
    }

    /** "§6Rough Ruby Gemstone" (with any resource pack glyph in front) as "rough ruby gemstone". */
    private static String cleanName(String name) {
        String plain = ChatFormatting.stripFormatting(name);
        if (plain == null) return "";
        int start = 0;
        while (start < plain.length() && !Character.isLetterOrDigit(plain.charAt(start))) start++;
        return plain.substring(start).trim().toLowerCase(Locale.ROOT);
    }

    /** Lower case, with spaces as underscores. */
    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static @Nullable Config config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.chat.commandAutofill;
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Command Autofill", desc = "Suggest arguments for Hypixel commands while you type them, in the normal command suggestion list (Tab completes): Bazaar products for /bz, items for /ahs and item ids for /viewrecipe.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Bazaar (/bz)", desc = "Suggest Bazaar products for /bz, /bzs and /bazaar. Search matches names and product IDs, with spaces, underscores and hyphens interchangeable; Tab inserts the item's name as the Bazaar shows it (spaces, and a hyphen only where the name has one).")
        @ConfigEditorBoolean
        public boolean bazaar = true;

        @Expose
        @ConfigOption(name = "Auction House (/ahs)", desc = "Suggest matching item names for /ahs and /ahsearch, including partial searches such as grand. Item names retain their original spaces and hyphens. /ah itself takes a player name, so it is left alone.")
        @ConfigEditorBoolean
        public boolean auctionHouse = true;

        @Expose
        @ConfigOption(name = "Recipes (/viewrecipe)", desc = "Suggest SkyBlock item ids for /viewrecipe and /recipe.")
        @ConfigEditorBoolean
        public boolean recipes = true;

        @Expose
        @ConfigOption(name = "Player Names", desc = "Like SkyHanni: suggest player names and subcommands for /p, /f, /g, /msg, /w, /visit, /pv, /invite, /ah, /boop and /trade (players on your island; party, friends and guild below).")
        @ConfigEditorBoolean
        public boolean players = true;

        @Expose
        @ConfigOption(name = "Party Members", desc = "Include your party members (and /p kick, promote, transfer... them).")
        @ConfigEditorBoolean
        public boolean party = true;

        @Expose
        @ConfigOption(name = "Friends", desc = "Include your friends, learnt from /f list and friend messages.")
        @ConfigEditorBoolean
        public boolean friends = true;

        @Expose
        @ConfigOption(name = "Guild Members", desc = "Include your guild members, learnt from /g online and /g list.")
        @ConfigEditorBoolean
        public boolean guild = false;

        @Expose
        @ConfigOption(name = "VIP Visits", desc = "Suggest the special islands for /visit (like PortalHub and the Cake Hub).")
        @ConfigEditorBoolean
        public boolean vipVisits = true;

        @Expose
        @ConfigOption(name = "Warps (/warp)", desc = "Suggest /warp destinations.")
        @ConfigEditorBoolean
        public boolean warps = true;

        @Expose
        @ConfigOption(name = "Sack Items (/gfs)", desc = "Suggest the items in your sacks for /gfs and /getfromsacks (open your sacks once so Sky2M knows them).")
        @ConfigEditorBoolean
        public boolean gfsSack = true;

        @Expose
        @ConfigOption(name = "Show Item (/show)", desc = "Suggest the slots /show, /showitem and /showoff take (item, helmet, pet...).")
        @ConfigEditorBoolean
        public boolean showItem = true;
    }
}
