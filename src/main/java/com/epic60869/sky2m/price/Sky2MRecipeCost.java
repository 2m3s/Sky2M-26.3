package com.epic60869.sky2m.price;

import com.epic60869.sky2m.features.itemlist.ItemListData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.epic60869.sky2m.config.Sky2MConfig;

/**
 * The "Recipe Cost" tooltip line: what it costs to craft an item from its recipe, from the Item List's recipes and the
 * price tooltip's market prices (bazaar insta-buy, lowest BIN, or an NPC shop's coin price). Results are cached per item
 * for a few seconds, because tooltips are rebuilt every frame; everything it reads is already in memory.
 */
final class Sky2MRecipeCost {
    private static final long TTL_MS = 5000;
    private static final int MAX_SUB_DEPTH = 4;
    private static final String COIN = "SKYBLOCK_COIN";

    /** One ingredient of the cheapest recipe: its total amount, the total coins it costs (NaN when unpriced) and where the price came from. */
    record Part(String id, double amount, double cost, String via) {}

    /** A recipe's cost per item made. {@code partial} when an ingredient has no price (its cost is then left out). */
    record Result(String kind, double perItem, boolean partial, List<Part> parts, int outputs, int seconds) {}

    private record Cached(Result result, long at) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static long lastLoadTry;

    private Sky2MRecipeCost() {}

    /** Adds the Recipe Cost line (and with Shift held, its ingredients) for one item. {@code id} is the item's recipe id. */
    static void addLines(Sky2MConfig.PriceTooltip config, String id, String apiId, int count, List<Component> lines) {
        if (!config.recipeCost || id == null || id.isEmpty()) return;
        if (!ItemListData.loaded()) {
            long now = System.currentTimeMillis();
            if (now - lastLoadTry > 30000) {
                lastLoadTry = now;
                ItemListData.ensureLoaded();
            }
            return;
        }
        Result result = resultFor(id, config.recipeSubCrafts);
        if (result == null) return;

        double each = result.perItem();
        MutableComponent line = Component.literal("Recipe Cost: ").withStyle(ChatFormatting.GOLD);
        if (each <= 0 && result.partial()) {
            line.append(Component.literal("? Coins").withStyle(ChatFormatting.RED));
        } else {
            String eachText = coins(each);
            if (count == 1) line.append(Component.literal(eachText + " Coins").withStyle(ChatFormatting.DARK_AQUA));
            else line.append(Component.literal(coins(each * count) + " Coins ").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal("(" + eachText + " each)").withStyle(ChatFormatting.GRAY));
            if (result.partial()) line.append(Component.literal(" (partial, some prices missing)").withStyle(ChatFormatting.RED));
            else if (!apiId.isEmpty()) {
                double buy = Sky2MPriceTooltip.costPrice(apiId);
                if (buy > 0 && Math.abs(buy - each) >= 1) {
                    double diff = (buy - each) * count;
                    boolean saves = diff > 0;
                    line.append(Component.literal(" (" + (saves ? "save " : "costs ") + shortCoins(Math.abs(diff)) + (saves ? " vs buying" : " more than buying") + ")")
                        .withStyle(saves ? ChatFormatting.GREEN : ChatFormatting.RED));
                }
            }
        }
        lines.add(line);

        boolean shift;
        try { shift = net.minecraft.client.Minecraft.getInstance().hasShiftDown(); } catch (Throwable t) { shift = false; }
        if (!shift) {
            lines.add(Component.literal("  Hold Shift for the recipe's ingredients").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        String header = "  " + result.kind();
        if (result.outputs() > 1) header += " (makes " + result.outputs() + ")";
        if (result.seconds() > 0) header += ", takes " + duration(result.seconds());
        lines.add(Component.literal(header).withStyle(ChatFormatting.GRAY));
        for (Part part : result.parts()) {
            MutableComponent row = Component.literal("   " + trim(part.amount()) + "x " + name(part.id())).withStyle(ChatFormatting.GRAY);
            if (Double.isNaN(part.cost())) row.append(Component.literal("  ?").withStyle(ChatFormatting.RED));
            else {
                row.append(Component.literal("  " + coins(part.cost())).withStyle(ChatFormatting.DARK_AQUA));
                if (!part.via().isEmpty()) row.append(Component.literal(" (" + part.via() + ")").withStyle(ChatFormatting.DARK_GRAY));
            }
            lines.add(row);
        }
    }

    // ------------------------------------------------------------------ calculation

    private static Result resultFor(String id, boolean subCrafts) {
        String key = id + (subCrafts ? "|s" : "|b");
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(key);
        if (cached != null && now - cached.at() < TTL_MS) return cached.result();
        Result result = compute(id, subCrafts);
        if (CACHE.size() > 2000) CACHE.clear();
        CACHE.put(key, new Cached(result, now));
        return result;
    }

    /**
     * What it costs to make one {@code id} with its cheapest complete crafting, forge or NPC shop recipe, leaving out
     * the ingredients in {@code free} (ones you already have, like the Talisman a Ring is crafted from). Ingredients are
     * priced at their market or NPC shop price (no sub-crafts). 0 when it has no such recipe or an ingredient has no
     * price; {@code kind[0]} gets the recipe's kind ("Crafting", "Forge", "NPC Shop").
     */
    static double costWithout(String id, Set<String> free, String[] kind) {
        if (id == null || id.isEmpty()) return 0;
        if (!ItemListData.loaded()) {
            ItemListData.ensureLoaded();
            return 0;
        }
        double best = 0;
        for (ItemListData.Recipe recipe : ItemListData.recipes(id)) {
            Set<String> visiting = new HashSet<>();
            visiting.add(id);
            Result r = evaluate(recipe, id, false, 0, visiting);
            if (r == null) continue;
            double total = 0;
            boolean priced = true;
            for (Part part : r.parts()) {
                if (free.contains(part.id())) continue;
                if (Double.isNaN(part.cost())) {
                    priced = false;
                    break;
                }
                total += part.cost();
            }
            if (!priced || r.outputs() <= 0) continue;
            double each = total / r.outputs();
            if (each > 0 && (best == 0 || each < best)) {
                best = each;
                kind[0] = r.kind();
            }
        }
        return best;
    }

    /** The cheapest of the item's crafting, forge and NPC shop recipes (a complete one over a partial one), or null if it has none. */
    private static Result compute(String id, boolean subCrafts) {
        Set<String> visiting = new HashSet<>();
        visiting.add(id);
        Result best = null;
        for (ItemListData.Recipe recipe : ItemListData.recipes(id)) {
            Result r = evaluate(recipe, id, subCrafts, 0, visiting);
            if (r == null) continue;
            if (best == null || (best.partial() && !r.partial()) || (best.partial() == r.partial() && r.perItem() < best.perItem())) best = r;
        }
        return best;
    }

    /** The cost of one recipe per item made; null for kinds that aren't a way to make the item (drops, trades, Kat upgrades). */
    private static Result evaluate(ItemListData.Recipe recipe, String id, boolean subCrafts, int depth, Set<String> visiting) {
        if (!(recipe instanceof ItemListData.Crafting || recipe instanceof ItemListData.Forge || recipe instanceof ItemListData.Shop)) return null;
        double made = 0;
        for (ItemListData.Ingredient out : recipe.outputs()) if (out != null && id.equals(out.id())) made += out.amount();
        if (made <= 0) return null;
        Map<String, Double> inputs = new LinkedHashMap<>();
        for (ItemListData.Ingredient in : recipe.inputs()) {
            if (in == null || in.id() == null || in.id().isEmpty() || in.amount() <= 0) continue;
            inputs.merge(in.id(), in.amount(), Double::sum);
        }
        if (inputs.isEmpty()) return null;
        List<Part> parts = new ArrayList<>();
        double total = 0;
        boolean partial = false;
        for (Map.Entry<String, Double> e : inputs.entrySet()) {
            double amount = e.getValue();
            String[] via = {""};
            double unit = unitCost(e.getKey(), subCrafts, depth, visiting, via);
            if (Double.isNaN(unit)) {
                partial = true;
                parts.add(new Part(e.getKey(), amount, Double.NaN, ""));
            } else {
                total += unit * amount;
                parts.add(new Part(e.getKey(), amount, unit * amount, via[0]));
            }
        }
        int seconds = recipe instanceof ItemListData.Forge f ? f.seconds() : 0;
        return new Result(recipe.kind(), total / made, partial, parts, (int) Math.round(made), seconds);
    }

    /** What one {@code id} costs as an ingredient: its market price or NPC shop price, or with sub-crafts the cheaper of that and crafting it. NaN when unknown. */
    private static double unitCost(String id, boolean subCrafts, int depth, Set<String> visiting, String[] via) {
        if (id.equals(COIN)) return 1;
        double best = Double.NaN;
        String how = "";
        double market = marketPrice(id);
        if (market > 0) {
            best = market;
            how = "";
        }
        double npc = npcShopPrice(id);
        if (npc > 0 && (Double.isNaN(best) || npc < best)) {
            best = npc;
            how = "NPC";
        }
        if (subCrafts && depth < MAX_SUB_DEPTH && !visiting.contains(id)) {
            visiting.add(id);
            try {
                for (ItemListData.Recipe recipe : ItemListData.recipes(id)) {
                    Result r = evaluate(recipe, id, true, depth + 1, visiting);
                    if (r == null || r.partial() || r.perItem() <= 0) continue;
                    if (Double.isNaN(best) || r.perItem() < best) {
                        best = r.perItem();
                        how = "crafted";
                    }
                }
            } finally {
                visiting.remove(id);
            }
        }
        via[0] = how;
        return best;
    }

    /** Bazaar insta-buy, else lowest BIN, for an item list id; 0 if none. */
    private static double marketPrice(String id) {
        String key = marketKey(id);
        double price = Sky2MPriceTooltip.costPrice(key);
        if (price <= 0 && !key.equals(id)) price = Sky2MPriceTooltip.costPrice(id);
        return price;
    }

    /** The coin price of an item an NPC shop sells for coins only (0 if none). */
    private static double npcShopPrice(String id) {
        double best = 0;
        for (ItemListData.Recipe recipe : ItemListData.recipes(id)) {
            if (!(recipe instanceof ItemListData.Shop shop)) continue;
            double made = 0, coins = 0;
            boolean onlyCoins = true;
            for (ItemListData.Ingredient out : shop.outputs()) if (out != null && id.equals(out.id())) made += out.amount();
            for (ItemListData.Ingredient in : shop.inputs()) {
                if (in == null) continue;
                if (COIN.equals(in.id())) coins += in.amount();
                else onlyCoins = false;
            }
            if (!onlyCoins || made <= 0 || coins <= 0) continue;
            double unit = coins / made;
            if (best == 0 || unit < best) best = unit;
        }
        return best;
    }

    /** The price API key for an Item List id: "SHARPNESS;5" is ENCHANTMENT_SHARPNESS_5, "AMBER_RUNE;1" is AMBER_RUNE_1. */
    private static String marketKey(String id) {
        int semi = id.indexOf(';');
        if (semi <= 0 || semi >= id.length() - 1) return id;
        String base = id.substring(0, semi), n = id.substring(semi + 1);
        if (base.endsWith("_RUNE")) return base + "_" + n;
        return "ENCHANTMENT_" + base + "_" + n;
    }

    // ------------------------------------------------------------------ formatting

    private static String name(String id) {
        if (id.equals(COIN)) return "Coins";
        ItemListData.Entry entry = ItemListData.entry(id);
        if (entry != null) {
            String stripped = ChatFormatting.stripFormatting(entry.name());
            if (stripped != null && !stripped.isBlank()) return stripped.trim();
        }
        return id;
    }

    private static String trim(double amount) {
        return amount == Math.rint(amount) ? String.format(Locale.ENGLISH, "%,d", (long) amount) : String.format(Locale.ENGLISH, "%.1f", amount);
    }

    private static String coins(double value) {
        return String.format(Locale.ENGLISH, "%,.1f", value);
    }

    private static String shortCoins(double value) {
        if (value >= 1e9) return String.format(Locale.ENGLISH, "%.2fB", value / 1e9);
        if (value >= 1e6) return String.format(Locale.ENGLISH, "%.2fM", value / 1e6);
        if (value >= 1e4) return String.format(Locale.ENGLISH, "%.1fk", value / 1e3);
        return String.format(Locale.ENGLISH, "%,.0f", value);
    }

    private static String duration(int seconds) {
        if (seconds >= 3600) return (seconds / 3600) + "h" + (seconds % 3600 >= 60 ? " " + (seconds % 3600 / 60) + "m" : "");
        if (seconds >= 60) return (seconds / 60) + "m" + (seconds % 60 > 0 ? " " + (seconds % 60) + "s" : "");
        return seconds + "s";
    }
}
