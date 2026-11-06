package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.hunting.AttributeShards;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 upgrades: what your worn armour and held weapon are still missing, read from the items' data, priced at bazaar
 * insta-buy or lowest BIN like Estimated Item Value. It covers the Recombobulator, Hot and Fuming Potato Books, The Art of War
 * and Peace, essence stars and master stars. Only two gains are known to Sky2M (the Dungeon Sim's tables: potato books and
 * The Art of Peace), so those are ranked by coins per stat point; the rest have no stat number and are listed by cost.
 * Reforges, enchantments and gemstones aren't covered.
 */
public final class UpgradeAdvisor {
    public enum Kind { ARMOUR, WEAPON, OTHER }

    /** What can still go on one item: {@code count} of them at {@code each} coins; {@code each} is 0 when it has no price. */
    public record Option(String piece, String name, int count, double each, @Nullable String gainEach, int statsEach) {
        public double total() {
            return each * count;
        }

        public double coinsPerStat() {
            return statsEach <= 0 ? Double.MAX_VALUE : each / statsEach;
        }
    }

    /** What an item already has, from its data. */
    public record Gear(String piece, Kind kind, boolean dungeon, int potatoBooks, boolean recombobulated, boolean artOfWar, boolean artOfPeace,
                       int stars, @Nullable JsonArray starCosts) {}

    private static final Pattern RARITY_LINE = Pattern.compile("\\b(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL)\\b(.*)");
    private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};

    private UpgradeAdvisor() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("upgrades").executes(c -> {
                    Minecraft.getInstance().execute(UpgradeAdvisor::show);
                    return 1;
                })));
            }
        });
    }

    /** Armour, weapon or neither from the item's rarity line ("LEGENDARY DUNGEON SWORD"). Public for the game test. */
    public static Kind kindOf(List<String> lore) {
        String type = typeLine(lore);
        if (type == null) return Kind.OTHER;
        if (type.contains("HELMET") || type.contains("CHESTPLATE") || type.contains("LEGGINGS") || type.contains("BOOTS")) return Kind.ARMOUR;
        if (type.contains("SWORD") || type.contains("BOW") || type.contains("FISHING ROD")) return Kind.WEAPON;
        return Kind.OTHER;
    }

    /** What follows the rarity on the item's rarity line, or null. */
    private static @Nullable String typeLine(List<String> lore) {
        for (int i = lore.size() - 1, checked = 0; i >= 0 && checked < 3; i--) {
            String line = lore.get(i);
            if (line.isBlank()) continue;
            checked++;
            Matcher m = RARITY_LINE.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** Everything still missing from one item, priced by {@code price} (0 for no price). Public for the game test. */
    public static List<Option> options(Gear gear, ToDoubleFunction<String> price) {
        List<Option> out = new ArrayList<>();
        String piece = gear.piece();
        boolean armour = gear.kind() == Kind.ARMOUR;
        String bookGain = armour ? "+4 Health, +2 Defense" : "+2 Strength, +2 Damage";
        int bookStats = armour ? 6 : 4;
        int hot = 10 - Math.min(gear.potatoBooks(), 10);
        int fuming = 15 - Math.clamp(gear.potatoBooks(), 10, 15);
        if (hot > 0) out.add(new Option(piece, "Hot Potato Book", hot, price.applyAsDouble("HOT_POTATO_BOOK"), bookGain, bookStats));
        if (fuming > 0) out.add(new Option(piece, "Fuming Potato Book", fuming, price.applyAsDouble("FUMING_POTATO_BOOK"), bookGain, bookStats));
        if (armour && !gear.artOfPeace()) out.add(new Option(piece, "The Art of Peace", 1, price.applyAsDouble("THE_ART_OF_PEACE"), "+40 Health", 40));
        if (!armour && !gear.artOfWar()) out.add(new Option(piece, "The Art of War", 1, price.applyAsDouble("THE_ART_OF_WAR"), null, 0));
        if (!gear.recombobulated()) out.add(new Option(piece, "Recombobulator 3000", 1, price.applyAsDouble("RECOMBOBULATOR_3000"), null, 0));
        JsonArray costs = gear.starCosts();
        if (costs != null) {
            if (gear.stars() < costs.size()) {
                out.add(new Option(piece, "Star " + (gear.stars() + 1), 1, starCost(costs.get(gear.stars()), price), null, 0));
            } else if (gear.dungeon() && costs.size() <= 5 && gear.stars() >= 5 && gear.stars() < 10) {
                out.add(new Option(piece, "Master Star " + (gear.stars() - 4), 1, price.applyAsDouble(MASTER_STARS[gear.stars() - 5]), null, 0));
            }
        }
        return out;
    }

    /** One star's cost from the repo: essence, items and coins, or 0 if any part has no price. */
    private static double starCost(JsonElement star, ToDoubleFunction<String> price) {
        double total = 0;
        for (JsonElement entry : star.getAsJsonArray()) {
            JsonObject cost = entry.getAsJsonObject();
            int amount = cost.has("amount") ? cost.get("amount").getAsInt() : 1;
            String type = cost.has("type") ? cost.get("type").getAsString() : "";
            if (type.equals("COINS")) {
                total += amount;
                continue;
            }
            String id = cost.has("essence_type") ? "ESSENCE_" + cost.get("essence_type").getAsString() : cost.has("item_id") ? cost.get("item_id").getAsString() : "";
            double each = id.isEmpty() ? 0 : price.applyAsDouble(id);
            if (each <= 0) return 0;
            total += each * amount;
        }
        return total;
    }

    /** Priced options with a known gain, fewest coins per stat point first. Public for the game test. */
    public static List<Option> knownGain(List<Option> options) {
        return options.stream().filter(o -> o.each() > 0 && o.statsEach() > 0).sorted(Comparator.comparingDouble(Option::coinsPerStat)).toList();
    }

    /** Priced options with no known gain, cheapest first. Public for the game test. */
    public static List<Option> unknownGain(List<Option> options) {
        return options.stream().filter(o -> o.each() > 0 && o.statsEach() <= 0).sorted(Comparator.comparingDouble(Option::total)).toList();
    }

    private static @Nullable Gear gear(String piece, ItemStack stack) {
        if (stack.isEmpty() || Compat.neuName(stack).isEmpty()) return null;
        List<String> lore = AttributeShards.lore(stack);
        Kind kind = kindOf(lore);
        if (kind == Kind.OTHER) return null;
        String type = typeLine(lore);
        CompoundTag data = Compat.customDataView(stack);
        int stars = Math.max(data.getIntOr("upgrade_level", 0), data.getIntOr("dungeon_item_level", 0));
        return new Gear(piece, kind, type != null && type.contains("DUNGEON"), data.getIntOr("hot_potato_count", 0),
            data.getIntOr("rarity_upgrades", 0) > 0, data.getIntOr("art_of_war_count", 0) > 0, data.getIntOr("artOfPeaceApplied", 0) > 0,
            stars, RepoItems.starCosts(Compat.neuName(stack)));
    }

    private static void show() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!Compat.isOnSkyblock()) {
            Sky2MAlerts.chat(Component.literal("Only on SkyBlock: your items have no upgrade data elsewhere.").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!Sky2MPriceTooltip.pricesLoaded()) {
            Sky2MPriceTooltip.warmup();
            Sky2MAlerts.chat(Component.literal("Prices are still loading. Try again in a moment.").withStyle(ChatFormatting.GRAY));
            return;
        }
        List<Option> options = new ArrayList<>();
        int checked = 0;
        Object[][] worn = {{"Helmet", player.getItemBySlot(EquipmentSlot.HEAD)}, {"Chestplate", player.getItemBySlot(EquipmentSlot.CHEST)},
            {"Leggings", player.getItemBySlot(EquipmentSlot.LEGS)}, {"Boots", player.getItemBySlot(EquipmentSlot.FEET)},
            {"Weapon", player.getMainHandItem()}};
        for (Object[] slot : worn) {
            Gear gear = gear((String) slot[0], (ItemStack) slot[1]);
            if (gear == null) continue;
            checked++;
            options.addAll(options(gear, Sky2MPriceTooltip::costPrice));
        }
        if (checked == 0) {
            Sky2MAlerts.chat(Component.literal("No armour worn or weapon held to look at.").withStyle(ChatFormatting.GRAY));
            return;
        }
        List<Option> known = knownGain(options), unknown = unknownGain(options);
        long unpriced = options.stream().filter(o -> o.each() <= 0).count();
        MutableComponent out = Component.literal("Upgrades").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(" for your worn armour and held weapon").withStyle(ChatFormatting.GRAY));
        if (known.isEmpty() && unknown.isEmpty()) {
            out.append(Component.literal(unpriced > 0 ? "\nNothing with a price is missing." : "\nNothing this looks at is missing.").withStyle(ChatFormatting.GREEN));
        }
        if (!known.isEmpty()) {
            out.append(Component.literal("\nFewest coins per stat point first").withStyle(ChatFormatting.YELLOW));
            for (Option o : known) {
                out.append(Component.literal("\n " + o.piece() + ": ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(o.name() + (o.count() > 1 ? " x" + o.count() : "")).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" " + CombatFeatures.formatCoins(o.each()) + (o.count() > 1 ? " each" : "")).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" " + o.gainEach() + (o.count() > 1 ? " each" : "")).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(" (" + CombatFeatures.formatCoins(o.coinsPerStat()) + " a point)").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (!unknown.isEmpty()) {
            out.append(Component.literal("\nStat gain not known, cheapest first").withStyle(ChatFormatting.YELLOW));
            for (Option o : unknown) {
                out.append(Component.literal("\n " + o.piece() + ": ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(o.name()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" " + CombatFeatures.formatCoins(o.total())).withStyle(ChatFormatting.GOLD));
            }
        }
        String note = "Every stat point counts the same, whichever stat. Reforges, enchantments and gemstones aren't covered."
            + (unpriced > 0 ? String.format(Locale.US, " %d more had no price.", unpriced) : "");
        out.append(Component.literal("\n" + note).withStyle(ChatFormatting.DARK_GRAY));
        Sky2MAlerts.chat(out);
    }
}
