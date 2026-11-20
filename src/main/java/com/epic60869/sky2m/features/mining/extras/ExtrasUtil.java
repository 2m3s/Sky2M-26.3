package com.epic60869.sky2m.features.mining.extras;

import com.epic60869.sky2m.features.core.RepoPattern;

import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.sim.util.TabText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers of the Glacite Mineshaft and Fossil Excavator features: loot lines, prices and the tab list. */
final class ExtrasUtil {
    /** A loot line without colours: "☠ Fine Onyx Gemstone x2", "Tusk Fossil", "Enchanted Book (Ice Cold I)". */
    private static final RepoPattern LOOT = RepoPattern.of("mining.extras.ExtrasUtil.LOOT", "^(?<name>.+?)(?: x(?<amount>[\\d,]+))?$");
    static final RepoPattern DIVIDER = RepoPattern.of("mining.extras.ExtrasUtil.DIVIDER", "^\\s*▬{20,}\\s*$");

    record Loot(String name, int amount) {}

    /** Item ids by plain name: the repo lookup walks every item, so a name is looked up once ('' = unknown). */
    private static final java.util.concurrent.ConcurrentMap<String, String> IDS = new java.util.concurrent.ConcurrentHashMap<>();

    private ExtrasUtil() {}

    static Loot parseLoot(String line) {
        String text = line.replace("☠", "").trim();
        if (text.isEmpty()) return null;
        Matcher m = LOOT.matcher(text);
        if (!m.matches()) return null;
        int amount = 1;
        if (m.group("amount") != null) {
            try {
                amount = Integer.parseInt(m.group("amount").replace(",", ""));
            } catch (NumberFormatException ignored) {}
        }
        return new Loot(m.group("name").trim(), amount);
    }

    /** What one item is worth by its plain name, or -1 when it has no id or price. */
    static double priceByName(String name) {
        String id = IDS.get(name);
        if (id == null) {
            if (!RepoItems.itemsLoaded()) return -1;
            String found = bookOrShardId(name);
            if (found == null) found = RepoItems.idByLooseName(name);
            id = found == null ? "" : found;
            IDS.put(name, id);
        }
        if (id.isEmpty()) return -1;
        double price = Sky2MPriceTooltip.unitPrice(id);
        return price > 0 ? price : -1;
    }

    /**
     * The price id of a book ("Enchanted Book (Ice Cold I)" is ENCHANTMENT_ICE_COLD_1) or a shard ("Glacite Walker
     * Shard", its bazaar id), which no repo item is named; null for anything else.
     */
    static String bookOrShardId(String name) {
        Matcher book = BOOK.matcher(name);
        if (book.matches()) {
            String level = book.group(2);
            int n = level.chars().allMatch(Character::isDigit) ? Integer.parseInt(level) : roman(level);
            return "ENCHANTMENT_" + book.group(1).trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[ '-]+", "_") + "_" + n;
        }
        if (name.endsWith(" Shard") || name.endsWith(" Shards")) {
            var shard = com.epic60869.sky2m.features.hunting.AttributeShards.byName(name.replaceAll(" Shards?$", ""));
            if (shard != null) return shard.bazaarName();
        }
        return null;
    }

    private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\((.+) ([IVXL]+|\\d+)\\)$");

    private static int roman(String s) {
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) { case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; default -> 0; };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /** What one item costs to get (a key), 0 when unknown. */
    static double costById(String id) {
        return Sky2MPriceTooltip.costPrice(id);
    }

    /** The unit price of the Suspicious Scrap an excavation uses (what you'd get for selling it). */
    static double unitById(String id) {
        return Sky2MPriceTooltip.unitPrice(id);
    }

    static String coins(double value) {
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (abs >= 1_000_000_000) return sign + String.format(Locale.US, "%.2fB", abs / 1_000_000_000);
        if (abs >= 1_000_000) return sign + String.format(Locale.US, "%.2fM", abs / 1_000_000);
        if (abs >= 1_000) return sign + String.format(Locale.US, "%.1fk", abs / 1_000);
        return sign + String.format(Locale.US, "%.0f", abs);
    }

    /** The plain text of every tab list entry. */
    static List<String> tabLines() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        if (mc.getConnection() == null) return out;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String line = TabText.plain(info);
            if (line != null) out.add(com.epic60869.sky2m.features.core.Sky2MLocation.strip(line));
        }
        return out;
    }
}
