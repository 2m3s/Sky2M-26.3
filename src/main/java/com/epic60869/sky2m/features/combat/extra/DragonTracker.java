package com.epic60869.sky2m.features.combat.extra;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End > Dragons, ported from SkyHanni's DragonFeatures and DragonProfitTracker (https://github.com/hannibal002/SkyHanni,
 * LGPL-2.1; item list from SkyHanni-REPO's DragonProfitTrackerItems, MIT): the dragon weight (live and after the fight),
 * the Superior Dragon title and a profit tracker for the dragons killed with your Summoning Eyes. Counts are kept in
 * {@code sky2m/dragons.json}.
 */
public final class DragonTracker {
    private static final Pattern SPAWN = Pattern.compile("The (?<d>Protector|Old|Unstable|Young|Strong|Wise|Superior) Dragon has spawned!");
    private static final Pattern EYE_PLACED = Pattern.compile("You placed a Summoning Eye!");
    private static final Pattern EYE_REMOVED = Pattern.compile("You recovered a Summoning Eye!");
    private static final Pattern DOWN = Pattern.compile("^(?<d>[A-Z]+) DRAGON DOWN!$");
    private static final Pattern POSITION = Pattern.compile("Your Damage: (?<dmg>[\\d.,]+) (?:\\(NEW RECORD!\\) )?\\(Position #(?<pos>\\d+)\\)");
    private static final Pattern DAMAGER = Pattern.compile("^(?<pos>\\d+)(?:st|nd|rd|th) Damager - .* - (?<dmg>[\\d.,]+)$");
    private static final Pattern SCORE_DAMAGE = Pattern.compile("Your Damage: (?<dmg>[\\d.,]+[kKmMbB]?)");
    private static final Pattern TAB_DAMAGE = Pattern.compile("\\s*(?<name>[^:]+): (?<dmg>[\\d.]+[kKmM]?)❤?");
    /** SkyHanni-REPO's DragonProfitTrackerItems, plus the dragon armour sets (matched by name). */
    private static final Pattern LOOT = Pattern.compile("(?:OLD|PROTECTOR|YOUNG|STRONG|WISE|SUPERIOR|UNSTABLE)_DRAGON_(?:HELMET|CHESTPLATE|LEGGINGS|BOOTS)"
        + "|DRAGON_HORN|DRAGON_CLAW|DRAGON_SCALE|DRAGON_NEST_TRAVEL_SCROLL|ASPECT_OF_THE_DRAGON|ATTRIBUTE_SHARD_DRAGON_ESSENCE.*|DYE_PEARLESCENT|ENDER_DRAGON.*");

    private static final class Data {
        Map<String, Long> kills = new LinkedHashMap<>();
        Map<String, Long> items = new LinkedHashMap<>();
        long eyes, essence;
    }

    private static Data data = new Data();
    private static Path file;
    private static boolean dirty;
    private static int ticks;
    private static Map<String, Integer> lastInventory;

    // The fight in progress.
    private static int yourEyes;
    private static String currentType;
    private static boolean dragonSpawned, ending;
    private static double endTopDamage, endDamage;
    private static int endPlace;
    private static double lastWeight = -1;

    private DragonTracker() {}

    private static CombatExtraConfig.Dragons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.dragons;
    }

    private static boolean inEnd() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("The End");
    }

    private static boolean inNest() {
        return inEnd() && Sky2MLocation.location().contains("Dragon's Nest");
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("dragons.json");
        data = CombatExtras.load(file, Data.class, new Data());
        if (data.kills == null) data.kills = new LinkedHashMap<>();
        if (data.items == null) data.items = new LinkedHashMap<>();
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientTickEvents.END_CLIENT_TICK.register(DragonTracker::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            endFight();
            yourEyes = 0;
            lastInventory = null;
        });
        Sky2MHuds.register("dragon_profit", "Dragon Profit Tracker", () -> config() != null && config().profitTracker && inNest(), DragonTracker::profitLines,
            List.of(Component.literal("Dragon Profit Tracker").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.literal("Superior Dragon kills: ").withStyle(ChatFormatting.GOLD).append(Component.literal("3").withStyle(ChatFormatting.AQUA)),
                Component.literal("Dragon Claw: ").withStyle(ChatFormatting.GRAY).append(Component.literal("2 (1.2M)").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Summoning Eye: ").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal("-24 eyes (-14.4M)").withStyle(ChatFormatting.RED)),
                Component.literal("Total profit: ").withStyle(ChatFormatting.GOLD).append(Component.literal("18.2M").withStyle(ChatFormatting.YELLOW))),
            8, 190);
        Sky2MHuds.setting("dragon_profit", () -> config() != null && config().profitTracker);
        Sky2MHuds.register("dragon_weight", "Dragon Weight", () -> config() != null && config().weightDisplay && inEnd() && dragonSpawned, DragonTracker::weightLines,
            List.of(Component.literal("Current Weight: ").withStyle(ChatFormatting.GOLD).append(Component.literal("513.2").withStyle(ChatFormatting.WHITE)),
                Component.literal("Eyes: 4  Place: 2").withStyle(ChatFormatting.GRAY)),
            8, 230);
        Sky2MHuds.setting("dragon_weight", () -> config() != null && config().weightDisplay);
    }

    /** The Reset Dragon Tracker button. */
    public static void reset() {
        data = new Data();
        save();
        Sky2MAlerts.chat(Component.literal("Dragon profit tracker reset.").withStyle(ChatFormatting.YELLOW));
    }

    private static void save() {
        dirty = false;
        CombatExtras.save(file, data);
    }

    private static void endFight() {
        dragonSpawned = false;
        ending = false;
        endTopDamage = endDamage = 0;
        endPlace = 0;
        currentType = null;
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        CombatExtraConfig.Dragons c = config();
        if (c == null || !inEnd() || !(c.profitTracker || c.weightDisplay || c.weightChat || c.superiorNotify)) return;
        Matcher spawn = SPAWN.matcher(text);
        if (spawn.find()) {
            dragonSpawned = true;
            currentType = spawn.group("d").toUpperCase(Locale.ROOT);
            if (c.superiorNotify && "SUPERIOR".equals(currentType)) {
                Sky2MAlerts.title(Component.literal("Superior Dragon Spawned!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), null);
            }
            if (yourEyes > 0) {
                data.eyes += yourEyes;
                dirty = true;
            }
            return;
        }
        if (EYE_PLACED.matcher(text).find()) {
            yourEyes++;
            return;
        }
        if (EYE_REMOVED.matcher(text).find()) {
            yourEyes = Math.max(0, yourEyes - 1);
            return;
        }
        Matcher down = DOWN.matcher(text);
        if (down.find() && !"ENDSTONE".equals(down.group("d"))) {
            ending = true;
            if (currentType == null) currentType = down.group("d");
            endTopDamage = 0;
            return;
        }
        if (!ending) return;
        Matcher damager = DAMAGER.matcher(text);
        if (damager.find()) {
            if (damager.group("pos").equals("1")) endTopDamage = CombatExtras.parseNumber(damager.group("dmg"));
            return;
        }
        Matcher position = POSITION.matcher(text);
        if (position.find()) {
            endPlace = Integer.parseInt(position.group("pos"));
            endDamage = CombatExtras.parseNumber(position.group("dmg"));
            double weight = weight(yourEyes, endPlace, endTopDamage, endDamage);
            if (endDamage > 0 && (yourEyes > 0 || c.countLeeched)) {
                data.kills.merge(currentType == null ? "UNKNOWN" : currentType, 1L, Long::sum);
                data.essence += "SUPERIOR".equals(currentType) ? 10 : 5;
                dirty = true;
            }
            if (c.weightChat) {
                Sky2MAlerts.chat(Component.literal("Your Weight: ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(CombatExtras.commas(Math.round(weight))).withStyle(ChatFormatting.GREEN)));
            }
            lastWeight = weight;
            endFight();
        }
    }

    private static int placementWeight(int place) {
        return switch (place) {
            case -1 -> 10;
            case 1 -> 200;
            case 2 -> 175;
            case 3 -> 150;
            case 4 -> 125;
            case 5 -> 110;
            case 6, 7, 8 -> 100;
            case 9, 10 -> 90;
            case 11, 12 -> 80;
            default -> 70;
        };
    }

    private static double weight(int eyes, int place, double firstDamage, double yourDamage) {
        return placementWeight(yourDamage == 0 ? -1 : place) + 100 * (eyes + yourDamage / (firstDamage != 0 ? firstDamage : 1));
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        CombatExtraConfig.Dragons c = config();
        if (c == null || mc.player == null || !inEnd()) {
            lastInventory = null;
            return;
        }
        ticks++;
        if (!dragonSpawned && ticks % 20 == 0) {
            for (String line : Sky2MLocation.scoreboard()) {
                if (line.startsWith("Dragon HP:")) dragonSpawned = true;
            }
        }
        // Dragon loot that comes into your inventory in the nest, while no menu is open.
        if (c.profitTracker && inNest() && mc.gui.screen() == null) {
            Map<String, Integer> now = inventory(mc);
            if (lastInventory != null) {
                for (Map.Entry<String, Integer> e : now.entrySet()) {
                    int gained = e.getValue() - lastInventory.getOrDefault(e.getKey(), 0);
                    if (gained > 0 && LOOT.matcher(e.getKey()).matches()) {
                        data.items.merge(e.getKey(), (long) gained, Long::sum);
                        dirty = true;
                    }
                }
            }
            lastInventory = now;
        } else {
            lastInventory = null;
        }
        if (dirty && ticks % 100 == 0) save();
    }

    private static Map<String, Integer> inventory(Minecraft mc) {
        Map<String, Integer> counts = new HashMap<>();
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = Sky2MPriceTooltip.marketId(stack);
            if (!id.isEmpty()) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------------------------------------ HUDs

    private static List<Component> profitLines() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Dragon Profit Tracker").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        long total = 0;
        for (Map.Entry<String, Long> e : data.kills.entrySet()) {
            total += e.getValue();
            out.add(Component.literal(label(e.getKey()) + " Dragon kills: ").withStyle(ChatFormatting.GOLD).append(Component.literal(String.valueOf(e.getValue())).withStyle(ChatFormatting.AQUA)));
        }
        double profit = 0;
        for (Map.Entry<String, Long> e : data.items.entrySet()) {
            double value = ItemPriceResolver.value(e.getKey()) * e.getValue();
            profit += value;
            out.add(Component.literal(prettify(e.getKey()) + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(e.getValue() + (value > 0 ? " (" + CombatExtras.shortNumber(value) + ")" : "")).withStyle(ChatFormatting.YELLOW)));
        }
        double essence = ItemPriceResolver.value("ESSENCE_DRAGON") * data.essence;
        if (data.essence > 0) {
            profit += essence;
            out.add(Component.literal("Dragon Essence: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(data.essence + (essence > 0 ? " (" + CombatExtras.shortNumber(essence) + ")" : "")).withStyle(ChatFormatting.YELLOW)));
        }
        double eyeCost = ItemPriceResolver.value("SUMMONING_EYE") * data.eyes;
        profit -= eyeCost;
        out.add(Component.literal("Summoning Eye: ").withStyle(ChatFormatting.DARK_PURPLE)
            .append(Component.literal("-" + data.eyes + " eyes (-" + CombatExtras.shortNumber(eyeCost) + ")").withStyle(ChatFormatting.RED)));
        out.add(Component.literal("Total profit: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(CombatExtras.shortNumber(profit)).withStyle(profit < 0 ? ChatFormatting.RED : ChatFormatting.YELLOW))
            .append(Component.literal(total == 0 ? "" : " (" + CombatExtras.shortNumber(profit / total) + "/dragon)").withStyle(ChatFormatting.GRAY)));
        return out;
    }

    private static String label(String type) {
        return type.isEmpty() ? type : type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String word : id.split("[_;]")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static List<Component> weightLines() {
        double yourDamage = 0;
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = SCORE_DAMAGE.matcher(line);
            if (m.find()) yourDamage = CombatExtras.parseNumber(m.group("dmg"));
        }
        // The Dragon tab widget: players with their damage, best first. Only the top three are shown.
        double top = 0;
        int place = 0;
        String me = Minecraft.getInstance().getUser().getName();
        int index = 0;
        for (Component line : Sky2MTabWidgetManager.get("Dragon").lines()) {
            Matcher m = TAB_DAMAGE.matcher(Sky2MLocation.strip(line.getString()));
            if (!m.find()) continue;
            index++;
            if (index == 1) top = CombatExtras.parseNumber(m.group("dmg"));
            if (m.group("name").trim().equals(me)) place = index;
        }
        int shownPlace = place == 0 ? 6 : place;
        double weight = weight(yourEyes, shownPlace, top, yourDamage);
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Current Weight: ").withStyle(ChatFormatting.GOLD).append(Component.literal(String.format(Locale.US, "%,.1f", weight)).withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("Eyes: " + yourEyes + "  Place: " + (place == 0 ? (yourDamage == 0 ? "not damaged yet" : "unknown, assuming 6th") : place)).withStyle(ChatFormatting.GRAY));
        if (top == 0) out.add(Component.literal("Enable the Dragon tab widget with /widget").withStyle(ChatFormatting.RED));
        return out;
    }
}
