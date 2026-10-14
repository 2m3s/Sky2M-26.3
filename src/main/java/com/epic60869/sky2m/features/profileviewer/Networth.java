package com.epic60869.sky2m.features.profileviewer;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.features.profileviewer.ProfileItems.PvItem;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Networth estimate, in the categories Killer560's Mod uses (MIT, https://github.com/Killer560-OG/killer560s-mod,
 * profileviewer/data/Networth, after skyblock-pv's NetworthCategory). Unlike theirs, items are valued with what's applied
 * to them (Sky2M's Estimated Item Value: recombobulator, books, enchantments, stars, gemstones...), not just the base
 * item. Pets use Sky2M's {@code LVL_<level>_<TIER>_<TYPE>} price, else Coflnet's {@code TYPE;rarity} lowest BIN.
 */
public final class Networth {
    public record Category(String name, long total, List<Map.Entry<String, Long>> top) {}

    public record Result(long total, List<Category> categories, boolean museumIncluded) {}

    private Networth() {}

    private static final Map<String, String> SKYHELPER_NAMES = Map.ofEntries(
        Map.entry("armor", "Armour"), Map.entry("equipment", "Equipment"), Map.entry("wardrobe", "Wardrobe"), Map.entry("inventory", "Inventory"),
        Map.entry("enderchest", "Ender Chest"), Map.entry("accessories", "Accessory Bag"), Map.entry("personal_vault", "Personal Vault"),
        Map.entry("storage", "Backpacks"), Map.entry("fishing_bag", "Fishing Bag"), Map.entry("potion_bag", "Potion Bag"),
        Map.entry("sacks_bag", "Sack of Sacks"), Map.entry("candy_inventory", "Candy Bag"), Map.entry("carnival_mask_inventory", "Carnival Masks"),
        Map.entry("quiver", "Quiver"), Map.entry("museum", "Museum"), Map.entry("farming_toolkit", "Farming Toolkit"),
        Map.entry("hunting_toolkit", "Hunting Toolkit"), Map.entry("sacks", "Sacks"), Map.entry("essence", "Essence"), Map.entry("pets", "Pets"));

    /**
     * The networth SkyHelper (and the bots using it) would give: Sky2M's port of SkyHelper-Networth, with its prices,
     * in this class's categories. {@code museumAll} is the museum endpoint's {@code members} (null leaves it out).
     */
    public static java.util.concurrent.CompletableFuture<Result> skyHelper(JsonObject profile, JsonObject member, JsonObject museumAll, java.util.UUID uuid) {
        JsonObject museum = museumAll == null || uuid == null ? null : Json.obj(museumAll, uuid.toString().replace("-", ""));
        double bank = Json.num(Json.obj(profile, "banking"), "balance");
        return com.epic60869.sky2m.features.profileviewer.networth.SkyHelperNetworth.calculate(member, museum, bank).thenApply(r -> {
            List<Category> cats = new ArrayList<>();
            Map<String, Long> coins = new LinkedHashMap<>();
            if (r.purse() > 0) coins.put("Purse", (long) r.purse());
            if (r.bank() > 0) coins.put("Profile Bank", (long) r.bank());
            if (r.personalBank() > 0) coins.put("Personal Bank", (long) r.personalBank());
            cats.add(category("Purse & Bank", coins));
            for (var c : r.categories()) {
                if (c.total() <= 0) continue;
                List<Map.Entry<String, Long>> top = new ArrayList<>();
                for (Map.Entry<String, Double> e : c.top()) top.add(Map.entry(e.getKey(), (long) (double) e.getValue()));
                cats.add(new Category(SKYHELPER_NAMES.getOrDefault(c.name(), c.name()), (long) c.total(), top));
            }
            cats.sort((a, b) -> Long.compare(b.total(), a.total()));
            return new Result((long) r.total(), cats, museum != null);
        });
    }

    /** Every inventory of a profile member by networth category, with their wardrobe sets flattened into one list. */
    public static Map<String, List<PvItem>> inventories(JsonObject member, List<PvItem> wardrobe) {
        JsonObject inv = Json.obj(member, "inventory");
        Map<String, List<PvItem>> out = new LinkedHashMap<>();
        if (inv == null) return out;
        out.put("Inventory", ProfileItems.decode(Json.obj(inv, "inv_contents")));
        out.put("Armour", ProfileItems.decode(Json.obj(inv, "inv_armor")));
        out.put("Equipment", ProfileItems.decode(Json.obj(inv, "equipment_contents")));
        out.put("Wardrobe", wardrobe);
        out.put("Ender Chest", ProfileItems.decode(Json.obj(inv, "ender_chest_contents")));
        List<PvItem> backpacks = new ArrayList<>();
        for (List<PvItem> bp : ProfileItems.numbered(Json.obj(inv, "backpack_contents"))) backpacks.addAll(bp);
        out.put("Backpacks", backpacks);
        out.put("Accessory Bag", ProfileItems.decode(Json.path(inv, "bag_contents.talisman_bag")));
        out.put("Personal Vault", ProfileItems.decode(Json.obj(inv, "personal_vault_contents")));
        List<PvItem> bags = new ArrayList<>(ProfileItems.decode(Json.path(inv, "bag_contents.fishing_bag")));
        bags.addAll(ProfileItems.decode(Json.path(inv, "bag_contents.quiver")));
        bags.addAll(ProfileItems.decode(Json.path(inv, "bag_contents.potion_bag")));
        out.put("Bags", bags);
        return out;
    }

    /**
     * The museum items a member owns (donated and not borrowed back, plus special items), from the museum endpoint's
     * {@code members}, as the profile viewer's museum page reads them. Null when their museum isn't there.
     */
    public static List<PvItem> museumItems(JsonObject museum, java.util.UUID uuid) {
        JsonObject m = museum == null || uuid == null ? null : Json.obj(museum, uuid.toString().replace("-", ""));
        if (m == null) return null;
        List<PvItem> out = new ArrayList<>();
        JsonObject items = Json.obj(m, "items");
        if (items != null) for (String id : items.keySet()) {
            JsonObject entry = Json.obj(items, id);
            if (entry == null || Json.bool(entry, "borrowing")) continue;
            for (PvItem item : ProfileItems.decode(Json.obj(entry, "items"))) if (item != null) out.add(item);
        }
        for (com.google.gson.JsonElement el : Json.arr(m, "special")) {
            if (!el.isJsonObject()) continue;
            for (PvItem item : ProfileItems.decode(Json.obj(el.getAsJsonObject(), "items"))) if (item != null) out.add(item);
        }
        return out;
    }

    /** Every wardrobe piece of a member: the loadout sets, or the old wardrobe for profiles nobody has opened since. */
    public static List<PvItem> wardrobeItems(JsonObject member) {
        List<PvItem> out = new ArrayList<>();
        JsonObject armour = Json.path(member, "loadout.armor");
        if (armour != null) for (String key : armour.keySet()) {
            if (key.equals("equipped_set")) continue;
            JsonObject pieces = Json.obj(armour, key);
            for (String piece : new String[]{"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) out.addAll(ProfileItems.decode(Json.obj(pieces, piece)));
        }
        if (out.isEmpty()) out.addAll(ProfileItems.decode(Json.obj(Json.obj(member, "inventory"), "wardrobe_contents")));
        return out;
    }

    /** {@code inventories} are the decoded item lists by category; {@code museum} is null when it wasn't loaded. */
    public static Result calculate(JsonObject profile, JsonObject member, Map<String, List<PvItem>> inventories, List<PvItem> museum,
                                   Map<String, Double> neuPrices) {
        Map<String, Double> pets = neuPrices == null ? Map.of() : neuPrices;
        List<Category> cats = new ArrayList<>();
        Map<String, Long> coins = new LinkedHashMap<>();
        coins.put("Purse", (long) ProfileStats.purse(member));
        double bank = ProfileStats.bank(profile);
        if (bank > 0) coins.put("Profile Bank", (long) bank);
        double personal = ProfileStats.personalBank(member);
        if (personal > 0) coins.put("Personal Bank", (long) personal);
        cats.add(category("Purse & Bank", coins));
        for (Map.Entry<String, List<PvItem>> e : inventories.entrySet()) cats.add(items(e.getKey(), e.getValue()));

        Map<String, Long> sacks = new LinkedHashMap<>();
        JsonObject counts = Json.path(member, "inventory.sacks_counts");
        if (counts != null) for (String id : counts.keySet()) {
            double amount = Json.num(counts, id);
            double unit = amount > 0 ? Sky2MPriceTooltip.costPrice(id) : 0;
            if (unit > 0) sacks.merge(ProfileExtras.titleCase(id), (long) (unit * amount), Long::sum);
        }
        cats.add(category("Sacks", sacks));

        Map<String, Long> essence = new LinkedHashMap<>();
        JsonObject ess = Json.path(member, "currencies.essence");
        if (ess != null) for (String type : ess.keySet()) {
            double amount = Json.num(Json.obj(ess, type), "current");
            double unit = Sky2MPriceTooltip.costPrice("ESSENCE_" + type.toUpperCase(Locale.ROOT));
            if (amount > 0 && unit > 0) essence.put(ProfileExtras.titleCase(type) + " Essence", (long) (unit * amount));
        }
        cats.add(category("Essence", essence));

        Map<String, Long> petValues = new LinkedHashMap<>();
        for (ProfileStats.Pet pet : ProfileStats.pets(member)) {
            double v = petPrice(pet, pets);
            if (!pet.heldItem().isEmpty()) v += Sky2MPriceTooltip.costPrice(pet.heldItem());
            if (!pet.skin().isEmpty()) v += Sky2MPriceTooltip.costPrice("PET_SKIN_" + pet.skin());
            if (v > 0) petValues.merge("[Lvl " + pet.level() + "] " + pet.name(), (long) v, Long::sum);
        }
        cats.add(category("Pets", petValues));
        if (museum != null) cats.add(items("Museum", museum));

        long total = 0;
        for (Category c : cats) total += c.total();
        cats.sort((a, b) -> Long.compare(b.total(), a.total()));
        return new Result(total, cats, museum != null);
    }

    private static double petPrice(ProfileStats.Pet pet, Map<String, Double> neuPrices) {
        double v = Sky2MPriceTooltip.costPrice("LVL_" + pet.level() + "_" + pet.tier() + "_" + pet.type());
        if (v > 0) return v;
        Double bin = neuPrices.get(pet.itemId());
        return bin == null ? 0 : bin;
    }

    private static Category items(String name, List<PvItem> items) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (PvItem item : items) {
            if (item == null) continue;
            double v = Sky2MPriceTooltip.itemValue(item.stack());
            if (v > 0) values.merge(ChatFormatting.stripFormatting(item.stack().getHoverName().getString()), (long) v, Long::sum);
        }
        return category(name, values);
    }

    private static Category category(String name, Map<String, Long> values) {
        long total = 0;
        List<Map.Entry<String, Long>> list = new ArrayList<>(values.entrySet());
        for (Map.Entry<String, Long> e : list) total += e.getValue();
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return new Category(name, total, list.size() > 8 ? new ArrayList<>(list.subList(0, 8)) : list);
    }
}
