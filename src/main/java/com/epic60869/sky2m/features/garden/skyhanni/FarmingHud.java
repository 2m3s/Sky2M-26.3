package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's farming display (https://github.com/hannibal002/SkyHanni, LGPL-2.1: GardenCropSpeed,
 * GardenCropMilestoneDisplay, GardenLevelDisplay): blocks per second, crops per second / minute / hour, coins per
 * hour, the crop milestone tier with its progress and ETA, and the Garden level with its experience.
 *
 * The milestone tier and percent come from the Crop Milestones tab widget; the time to the next tier is the crops
 * still needed divided by the crops per second (the crop's base drops times your Farming Fortune from the Stats
 * widget times your blocks per second), as in SkyHanni.
 */
public final class FarmingHud {
    private static final Pattern EXP_NEXT = Pattern.compile("(?<have>[\\d,.]+[kKmM]?)/(?<need>[\\d,.]+[kKmM]?)\\s*$");
    private static final Pattern OVERFLOW = Pattern.compile("^\\s{8,}(?<exp>[\\d,]+)\\s*$");
    private static final Pattern PROGRESS = Pattern.compile("Progress to Level (?<level>[^:]*)");
    private static final Pattern GARDEN_NAME = Pattern.compile("Garden Level (?<level>\\S+)");
    private static final Pattern VISITOR_EXP = Pattern.compile("^\\s*\\+(?<exp>[\\d,]+) Garden Experience");
    private static final int OVERFLOW_EXP = 10_000;

    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);

    private static final List<Consumer<GardenCrop>> BREAK_LISTENERS = new ArrayList<>();
    private static final Map<GardenCrop, long[]> MILESTONES = new EnumMap<>(GardenCrop.class);
    private static long[] gardenExpTiers = {0, 70, 70, 140, 240, 600, 1500, 2000, 2500, 3000, 10000, 10000, 10000, 10000, 10000};

    private static volatile GardenCrop lastBroken;
    private static volatile long lastBrokenAt;
    private static GardenCrop lastHeld;
    private static int blocksThisSecond;
    private static List<Integer> speedList = new ArrayList<>();
    private static int secondsStopped;
    private static double averageBps;
    private static double lastBpsSeen;
    private static long lastBpsTime;
    private static int ticks;
    private static long gardenExp = -1;
    private static int warnedTier = -1;

    private static List<Component> hudLines = List.of();
    private static List<Component> levelLines = List.of();

    private FarmingHud() {}

    private static SkyHanniGardenConfig.FarmingHud config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.skyhanniGarden.farmingHud;
    }

    public static void init() {
        SkyHanniRepo.load("Garden", FarmingHud::readRepo);
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            if (!level.isClientSide() || !Sky2MLocation.inGarden()) return;
            GardenCrop crop = GardenCrop.ofBlock(state.getBlock(), level.getGameTime());
            if (crop == null) return;
            lastBroken = crop;
            lastBrokenAt = System.currentTimeMillis();
            synchronized (FarmingHud.class) {
                blocksThisSecond++;
            }
            for (Consumer<GardenCrop> listener : BREAK_LISTENERS) listener.accept(crop);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        Sky2MChat.onChat(message -> {
            if (!Sky2MLocation.inGarden()) return;
            Matcher m = VISITOR_EXP.matcher(message.text());
            if (m.find() && gardenExp >= 0) {
                gardenExp += Long.parseLong(m.group("exp").replace(",", ""));
                updateLevelLines();
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ContainerScreen container) || !Sky2MLocation.inGarden()) return;
            String title = screen.getTitle().getString();
            int slot = title.equals("Desk") ? 4 : title.equals("SkyBlock Menu") ? 10 : -1;
            if (slot < 0) return;
            boolean[] done = {false};
            ScreenEvents.afterTick(screen).register(s -> {
                if (done[0] || container.getMenu().slots.size() <= slot) return;
                done[0] = readGardenItem(container.getMenu().slots.get(slot).getItem());
            });
        });

        Sky2MHuds.setting("sh_farming_hud", () -> config() != null && config().enabled);
        Sky2MHuds.register("sh_farming_hud", "Crop Milestones", () -> config() != null && config().enabled && Sky2MLocation.inGarden(),
            () -> hudLines,
            List.of(Component.literal("§6Crop Milestones"), Component.literal("§7Wheat §830➜§331"),
                Component.literal("§e1,234,567§8/§e2,000,000"), Component.literal("§7Percentage: §e61.7%"), Component.literal("§7In §b3m 12s"),
                Component.literal("§7Crops/Minute§8: §e24,300"), Component.literal("§7Blocks/Second§8: §e19.9"), Component.literal("§7Coins/Hour§8: §6§e1.2M")),
            8, 170);
        Sky2MHuds.setting("sh_garden_level", () -> config() != null && config().gardenLevel);
        Sky2MHuds.register("sh_garden_level", "Garden Level", () -> config() != null && config().gardenLevel && Sky2MLocation.inGarden(),
            () -> levelLines, List.of(Component.literal("§aGarden level 12 §7(§e3,500§7/§e10,000§7)")), 8, 150);
    }

    /** Called with the crop for every crop block you break in the Garden. */
    public static void onBreak(Consumer<GardenCrop> listener) {
        BREAK_LISTENERS.add(listener);
    }

    /** The crop you're farming: the tool in your hand, else the last crop you broke a moment ago. */
    public static GardenCrop currentCrop() {
        GardenCrop held = GardenCrop.held();
        if (held != null) return held;
        return System.currentTimeMillis() - lastBrokenAt < 10_000 ? lastBroken : null;
    }

    public static GardenCrop lastBroken() {
        return lastBroken;
    }

    public static long lastBrokenAt() {
        return lastBrokenAt;
    }

    public static double blocksPerSecond() {
        return averageBps;
    }

    /** The newest blocks per second of at least 15 or 0 (used to predict contest scores). */
    public static double latestBps() {
        return lastBpsSeen;
    }

    // ---------------------------------------------------------------------------------------------- repo

    private static void readRepo(JsonObject json) {
        if (json.has("garden_exp")) {
            JsonArray array = json.getAsJsonArray("garden_exp");
            long[] tiers = new long[array.size()];
            for (int i = 0; i < tiers.length; i++) tiers[i] = array.get(i).getAsLong();
            gardenExpTiers = tiers;
        }
        if (json.has("crop_milestones")) {
            JsonObject milestones = json.getAsJsonObject("crop_milestones");
            synchronized (MILESTONES) {
                for (GardenCrop crop : GardenCrop.values()) {
                    if (!milestones.has(crop.milestoneKey())) continue;
                    JsonArray array = milestones.getAsJsonArray(crop.milestoneKey());
                    long[] tiers = new long[array.size()];
                    for (int i = 0; i < tiers.length; i++) tiers[i] = array.get(i).getAsLong();
                    MILESTONES.put(crop, tiers);
                }
            }
        }
    }

    private static long tierCost(GardenCrop crop, int tier) {
        long[] tiers;
        synchronized (MILESTONES) {
            tiers = MILESTONES.get(crop);
        }
        if (tiers == null || tiers.length == 0) return -1;
        return tiers[Math.min(Math.max(tier, 0), tiers.length - 1)];
    }

    // ---------------------------------------------------------------------------------------------- speed

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !Sky2MLocation.inGarden()) return;
        SkyHanniGardenConfig.FarmingHud c = config();
        if (c == null) return;
        GardenCrop held = GardenCrop.held();
        if (held != lastHeld) {
            lastHeld = held;
            resetSpeed();
        }
        if (++ticks % 20 == 0) checkSpeed(c);
        if (ticks % 5 == 0) {
            if (c.enabled) hudLines = buildHud(c);
            if (c.gardenLevel) updateLevelLines();
        }
    }

    private static void resetSpeed() {
        averageBps = 0;
        speedList = new ArrayList<>();
        secondsStopped = 0;
    }

    /** SkyHanni's GardenCropSpeed.checkSpeed, once per second. */
    private static void checkSpeed(SkyHanniGardenConfig.FarmingHud c) {
        int broken;
        synchronized (FarmingHud.class) {
            broken = blocksThisSecond;
            blocksThisSecond = 0;
        }
        if (broken == 0) {
            if (speedList.isEmpty()) return;
            secondsStopped++;
            return;
        }
        if (secondsStopped >= c.resetSeconds) resetSpeed();
        List<Integer> list = new ArrayList<>(speedList);
        while (secondsStopped > 0) {
            list.add(0);
            secondsStopped--;
        }
        list.add(broken);
        if (list.size() == 2) {
            list.remove(0);
            list.add(broken);
        }
        speedList = list;
        if (list.size() > 5) averageBps = Math.min(20.0, mean(list.subList(3, list.size())));
        else if (list.size() > 1) averageBps = Math.min(20.0, mean(list.subList(1, list.size())));
        else averageBps = 0;
        if (averageBps > 1) {
            lastBpsSeen = averageBps;
            lastBpsTime = System.currentTimeMillis();
        }
    }

    private static double mean(List<Integer> list) {
        if (list.isEmpty()) return 0;
        double sum = 0;
        for (int v : list) sum += v;
        return sum / list.size();
    }

    /** Crops per second: base drops times (100 + Farming Fortune) / 100 times blocks per second. */
    public static double cropsPerSecond(GardenCrop crop) {
        double ff = GardenTabData.trueFortune();
        if (ff < 0 || averageBps <= 0) return 0;
        return (100.0 + ff) * crop.baseDrops * averageBps / 100.0;
    }

    /** Coins for one crop, from the Bazaar price of its enchanted form (or the crop itself), or 0. */
    public static double coinsPerCrop(GardenCrop crop) {
        double enchanted = Sky2MPriceTooltip.unitPrice(crop.priceItem) / crop.priceDivisor;
        double raw = Sky2MPriceTooltip.unitPrice(crop.collection);
        return Math.max(enchanted, raw);
    }

    // ---------------------------------------------------------------------------------------------- display

    private static List<Component> buildHud(SkyHanniGardenConfig.FarmingHud c) {
        GardenCrop crop = currentCrop();
        if (crop == null) return List.of();
        List<Component> out = new ArrayList<>();
        GardenTabData.Milestone ms = GardenTabData.milestone(crop);
        double cps = cropsPerSecond(crop);
        boolean farming = System.currentTimeMillis() - lastBrokenAt < 15_000;
        if (!farming) cps = 0;

        out.add(Component.literal("§6Crop Milestones"));
        if (ms == null) {
            out.add(Component.literal("§7" + crop.displayName + " §cNo milestone data"));
            out.add(Component.literal("§cTurn on the Crop Milestones tab widget (/widget)"));
        } else if (ms.maxed()) {
            out.add(Component.literal("§7" + crop.displayName + " §eMAXED §8(tier " + ms.tier() + ")"));
        } else {
            int tier = ms.tier();
            out.add(Component.literal("§7" + crop.displayName + " §8" + tier + "➜§3" + (tier + 1)));
            long cost = tierCost(crop, tier);
            if (cost > 0) {
                double remaining = cost * (1.0 - ms.percent() / 100.0);
                if (cps > 0) remaining = Math.max(0, remaining - cps * (System.currentTimeMillis() - ms.seenAt()) / 1000.0);
                double have = cost - remaining;
                out.add(Component.literal("§e" + INT.format(Math.round(have)) + "§8/§e" + INT.format(cost)));
                out.add(Component.literal("§7Percentage: §e" + String.format(Locale.US, "%.1f%%", have * 100.0 / cost)));
                if (c.showEta && cps > 0) {
                    long secs = (long) (remaining / cps);
                    out.add(Component.literal("§7In §b" + duration(secs)));
                    warn(c, crop, tier, secs);
                }
            } else {
                out.add(Component.literal("§7Percentage: §e" + String.format(Locale.US, "%.1f%%", ms.percent())));
            }
        }
        if (cps > 0) {
            if (c.showCropsPerMinute) {
                out.add(Component.literal("§7Crops/Second§8: §e" + INT.format(Math.round(cps))));
                out.add(Component.literal("§7Crops/Minute§8: §e" + INT.format(Math.round(cps * 60))));
                out.add(Component.literal("§7Crops/Hour§8: §e" + INT.format(Math.round(cps * 3600))));
            }
            out.add(Component.literal("§7Blocks/Second§8: §e" + String.format(Locale.US, "%.1f", averageBps)));
            if (c.showCoinsPerHour) {
                double coin = coinsPerCrop(crop);
                if (coin > 0) out.add(Component.literal("§7Coins/Hour§8: §6" + coins(coin * cps * 3600)));
            }
        } else if (GardenTabData.trueFortune() < 0) {
            out.add(Component.literal("§cNo Farming Fortune: turn on the Stats tab widget"));
        }
        return out;
    }

    private static void warn(SkyHanniGardenConfig.FarmingHud c, GardenCrop crop, int tier, long secs) {
        if (!c.warnClose || secs > 6 || tier == warnedTier) return;
        if (System.currentTimeMillis() - lastBrokenAt > 500) return;
        warnedTier = tier;
        Sky2MAlerts.title(Component.literal("§b" + crop.displayName + " " + (tier + 1) + " in " + secs + "s"), Component.empty());
    }

    // ---------------------------------------------------------------------------------------------- garden level

    private static boolean readGardenItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) return false;
        String name = stack.getHoverName().getString();
        Matcher n = GARDEN_NAME.matcher(name);
        if (!n.find() && !name.contains("Garden")) return true; // not the Garden item
        int currentLevel = 0;
        long nextExp = 0;
        for (Component line : lore.lines()) {
            String text = line.getString();
            Matcher m = PROGRESS.matcher(text);
            if (m.find()) {
                currentLevel = parseLevel(m.group("level")) - 1;
                continue;
            }
            if (text.contains("Max level reached")) {
                currentLevel = 15;
                continue;
            }
            m = OVERFLOW.matcher(text);
            if (m.find()) {
                gardenExp = Long.parseLong(m.group("exp").replace(",", ""));
                updateLevelLines();
                return true;
            }
            m = EXP_NEXT.matcher(text);
            if (m.find()) nextExp = parseAmount(m.group("have"));
        }
        gardenExp = expForLevel(currentLevel) + nextExp;
        updateLevelLines();
        return true;
    }

    private static void updateLevelLines() {
        if (gardenExp < 0) {
            String tab = GardenTabData.gardenLevelText();
            levelLines = List.of(Component.literal("§aGarden Level " + (tab == null ? "?" : tab) + " §cOpen the desk!"));
            return;
        }
        int level = levelFor(gardenExp);
        long base = expForLevel(level);
        long next = expForLevel(level + 1);
        levelLines = List.of(Component.literal("§aGarden level " + level + " §7(§e" + INT.format(gardenExp - base) + "§7/§e" + INT.format(next - base) + "§7)"));
    }

    private static long expForLevel(int level) {
        long total = 0;
        int tier = 0;
        for (long tierExp : gardenExpTiers) {
            total += tierExp;
            tier++;
            if (tier == level) return total;
        }
        while (tier < level) {
            total += OVERFLOW_EXP;
            tier++;
            if (tier == level) return total;
        }
        return 0;
    }

    private static int levelFor(long exp) {
        int tier = 0;
        long total = 0;
        for (long tierExp : gardenExpTiers) {
            total += tierExp;
            if (total > exp) return tier;
            tier++;
        }
        total += OVERFLOW_EXP;
        while (total < exp) {
            tier++;
            total += OVERFLOW_EXP;
        }
        return tier;
    }

    private static int parseLevel(String text) {
        text = text.trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return roman(text);
        }
    }

    private static int roman(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (Character.toUpperCase(s.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    private static long parseAmount(String text) {
        String s = text.replace(",", "").toLowerCase(Locale.ROOT);
        double mult = 1;
        if (s.endsWith("k")) {
            mult = 1000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = 1_000_000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(s) * mult);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------- formatting

    public static String duration(long seconds) {
        if (seconds < 0) return "?";
        long d = seconds / 86400;
        long h = seconds % 86400 / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    public static String coins(double value) {
        if (value >= 1_000_000_000) return String.format(Locale.US, "%.2fB", value / 1_000_000_000);
        if (value >= 1_000_000) return String.format(Locale.US, "%.2fM", value / 1_000_000);
        if (value >= 1_000) return String.format(Locale.US, "%.1fk", value / 1_000);
        return String.format(Locale.US, "%.0f", value);
    }

    public static String number(double value) {
        return INT.format(Math.round(value));
    }
}
