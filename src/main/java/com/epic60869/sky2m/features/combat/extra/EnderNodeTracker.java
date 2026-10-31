package com.epic60869.sky2m.features.combat.extra;

import com.epic60869.sky2m.price.ItemPriceResolver;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.slayer.SlayerBossProfit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End > Ender Node Tracker, ported from SkyHanni's EnderNodeTracker (https://github.com/hannibal002/SkyHanni, LGPL-2.1):
 * the Ender Nodes you mine, what they gave, Endermite Nests, Enderman pets and the coins made. Counts are kept in
 * {@code sky2m/endernodes.json}.
 */
public final class EnderNodeTracker {
    private static final Pattern NODE = Pattern.compile("ENDER NODE! You found (?:(?<amount>\\d+)x )?(?<name>.+?)!?$");
    private static final Pattern PET = Pattern.compile("^(?:RARE|PET) DROP! Enderman \\(");
    /** Misc and armour drops of SkyHanni's EnderNode, by display name. */
    private static final List<String> MISC = List.of("Enchanted End Stone", "Enchanted Obsidian", "Enchanted Ender Pearl", "Grand Experience Bottle",
        "Titanic Experience Bottle", "End Stone Shulker", "End Stone Geode", "Magical Rune I", "Mite Gel", "Shrimp the Fish");
    private static final List<String> ARMOUR = List.of("Ender Helmet", "Ender Chestplate", "Ender Leggings", "Ender Boots", "Ender Necklace", "Ender Gauntlet");
    private static final double[] PET_PRICES = {100, 500, 2_000, 10_000, 1_000_000};
    private static final String[] PET_RARITY = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};

    private static final class Data {
        long nodes, nests;
        Map<String, Long> loot = new LinkedHashMap<>();
        long[] pets = new long[5];
    }

    private static Data data = new Data();
    private static Path file;
    private static boolean dirty;
    private static int ticks, lastMiteGel = -1;

    private EnderNodeTracker() {}

    private static CombatExtraConfig.EnderNodes config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.enderNodes;
    }

    private static boolean visible() {
        CombatExtraConfig.EnderNodes c = config();
        if (c == null || !c.enabled || !Sky2MLocation.onSkyblock() || !Sky2MLocation.areaIs("The End")) return false;
        if (!c.onlyPickaxe) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        String held = mc.player.getMainHandItem().getHoverName().getString();
        return held.contains("Pickaxe") || held.contains("Drill") || held.contains("Gauntlet") || held.contains("Pick");
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("endernodes.json");
        data = CombatExtras.load(file, Data.class, new Data());
        if (data.loot == null) data.loot = new LinkedHashMap<>();
        if (data.pets == null || data.pets.length != 5) data.pets = new long[5];
        Sky2MChat.onChat(message -> onChat(message.component(), message.text().trim()));
        ClientTickEvents.END_CLIENT_TICK.register(EnderNodeTracker::tick);
        Sky2MHuds.register("ender_nodes", "Ender Node Tracker", EnderNodeTracker::visible, EnderNodeTracker::lines,
            List.of(Component.literal("Ender Node Tracker").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD),
                Component.literal("312 Ender Nodes mined").withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.literal("4.2M Coins made").withStyle(ChatFormatting.GOLD),
                Component.literal("9 Endermite Nest").withStyle(ChatFormatting.RED),
                Component.literal("48 Enchanted Ender Pearl (480k)").withStyle(ChatFormatting.AQUA)),
            8, 270);
        Sky2MHuds.setting("ender_nodes", () -> config() != null && config().enabled);
    }

    /** The Reset Counts button. */
    public static void reset() {
        data = new Data();
        save();
        Sky2MAlerts.chat(Component.literal("Ender Node tracker reset.").withStyle(ChatFormatting.YELLOW));
    }

    private static void save() {
        dirty = false;
        CombatExtras.save(file, data);
    }

    private static void onChat(Component component, String text) {
        CombatExtraConfig.EnderNodes c = config();
        if (c == null || !c.enabled || !Sky2MLocation.onSkyblock() || !Sky2MLocation.areaIs("The End")) return;
        Matcher node = NODE.matcher(text);
        if (node.find()) {
            data.nodes++;
            String name = node.group("name").trim();
            long amount = node.group("amount") == null ? 1 : Long.parseLong(node.group("amount"));
            if (name.equals("Endermite Nest")) data.nests++;
            else if (MISC.contains(name) || ARMOUR.contains(name)) data.loot.merge(name, amount, Long::sum);
            dirty = true;
            return;
        }
        if (PET.matcher(text).find()) {
            data.pets[petRarity(component)]++;
            dirty = true;
            return;
        }
        if (text.startsWith("[Sacks]")) {
            for (Map.Entry<String, Long> e : SlayerBossProfit.sackGains(component).entrySet()) {
                if (e.getKey().equals("Mite Gel") && e.getValue() > 0) {
                    data.loot.merge("Mite Gel", e.getValue(), Long::sum);
                    dirty = true;
                }
            }
        }
    }

    /** The colour Hypixel gives "Enderman" in a pet drop: white common, green uncommon, blue rare, purple epic, gold legendary. */
    private static int petRarity(Component message) {
        int[] found = {2};
        message.visit((Style style, String text) -> {
            if (text.contains("Enderman") && style.getColor() != null) {
                TextColor colour = style.getColor();
                if (colour.equals(TextColor.fromLegacyFormat(ChatFormatting.WHITE))) found[0] = 0;
                else if (colour.equals(TextColor.fromLegacyFormat(ChatFormatting.GREEN))) found[0] = 1;
                else if (colour.equals(TextColor.fromLegacyFormat(ChatFormatting.BLUE))) found[0] = 2;
                else if (colour.equals(TextColor.fromLegacyFormat(ChatFormatting.DARK_PURPLE))) found[0] = 3;
                else if (colour.equals(TextColor.fromLegacyFormat(ChatFormatting.GOLD))) found[0] = 4;
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    private static void tick(Minecraft mc) {
        CombatExtraConfig.EnderNodes c = config();
        if (c == null || !c.enabled || mc.player == null || !Sky2MLocation.onSkyblock() || !Sky2MLocation.areaIs("The End")) {
            lastMiteGel = -1;
            return;
        }
        // Mite Gel that comes into your inventory (the rest of the loot is read from chat).
        if (mc.gui.screen() == null) {
            int gel = 0;
            var inventory = mc.player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getHoverName().getString().contains("Mite Gel")) gel += stack.getCount();
            }
            if (lastMiteGel >= 0 && gel > lastMiteGel) {
                data.loot.merge("Mite Gel", (long) (gel - lastMiteGel), Long::sum);
                dirty = true;
            }
            lastMiteGel = gel;
        } else {
            lastMiteGel = -1;
        }
        if (++ticks % 100 == 0 && dirty) save();
    }

    private static double price(String name, boolean armour) {
        return armour ? 10_000 : ItemPriceResolver.valueByName(name);
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Ender Node Tracker").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
        out.add(Component.literal(CombatExtras.commas(data.nodes) + " Ender Nodes mined").withStyle(ChatFormatting.LIGHT_PURPLE));
        double coins = 0;
        List<Component> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : data.loot.entrySet()) {
            boolean armour = ARMOUR.contains(e.getKey());
            double value = price(e.getKey(), armour) * e.getValue();
            coins += value;
            rows.add(Component.literal(CombatExtras.commas(e.getValue()) + " " + e.getKey()).withStyle(armour ? ChatFormatting.DARK_PURPLE : ChatFormatting.AQUA)
                .append(Component.literal(" (" + CombatExtras.shortNumber(value) + ")").withStyle(ChatFormatting.GOLD)));
        }
        long petCount = 0;
        double petValue = 0;
        for (int i = 0; i < 5; i++) {
            petCount += data.pets[i];
            petValue += data.pets[i] * PET_PRICES[i];
        }
        coins += petValue;
        out.add(Component.literal(CombatExtras.shortNumber(coins) + " Coins made").withStyle(ChatFormatting.GOLD));
        out.add(Component.literal(CombatExtras.commas(data.nests) + " Endermite Nest").withStyle(ChatFormatting.RED));
        if (petCount > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) sb.append(i == 0 ? "" : "-").append(data.pets[i]);
            out.add(Component.literal(sb + " Enderman Pet ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("(" + CombatExtras.shortNumber(petValue) + ")").withStyle(ChatFormatting.GOLD)));
        }
        out.addAll(rows);
        return out;
    }
}
