package com.epic60869.sky2m.features.garden.helpers;

import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.sim.util.TabText;
import com.google.gson.JsonElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composter overlay, from SkyHanni's ComposterOverlay and ComposterApi (LGPL-2.1,
 * https://github.com/hannibal002/SkyHanni): in the Composter and Composter Upgrades menus, the time per compost, what a
 * compost costs with the cheapest Organic Matter and Fuel, how much of each fills the composter, the profit per hour
 * and a preview of the upgrade you hover. Organic Matter and Fuel values are SkyHanni's repo (Garden.json).
 * Differences: it always uses the cheapest items instead of the ones you select, and upgrade levels are kept for the
 * session only (open Composter Upgrades once).
 */
final class ComposterOverlay {
    private static final String HUD = "garden_composter_overlay";
    private static final String[] UPGRADES = {"Composter Speed", "Multi Drop", "Fuel Cap", "Organic Matter Cap", "Cost Reduction"};
    private static final int FIRST_UPGRADE_SLOT = 20;
    private static final Pattern UPGRADE_NAME = Pattern.compile("^(Composter Speed|Multi Drop|Fuel Cap|Organic Matter Cap|Cost Reduction)(?: ([IVXL]+))?$");
    private static final Pattern ORGANIC = Pattern.compile("^\\s*Organic Matter:\\s*(?<amount>[\\d.,]+[kKmM]?)\\s*/");
    private static final Pattern FUEL = Pattern.compile("^\\s*Fuel:\\s*(?<amount>[\\d.,]+[kKmM]?)\\s*/");
    /** Enchanted versions of the plain crops are worth 160 of them. */
    private static final Map<String, String> ENCHANTED = Map.ofEntries(
        Map.entry("CARROT_ITEM", "ENCHANTED_CARROT"), Map.entry("POTATO_ITEM", "ENCHANTED_POTATO"),
        Map.entry("PUMPKIN", "ENCHANTED_PUMPKIN"), Map.entry("MELON", "ENCHANTED_MELON"),
        Map.entry("RED_MUSHROOM", "ENCHANTED_RED_MUSHROOM"), Map.entry("BROWN_MUSHROOM", "ENCHANTED_BROWN_MUSHROOM"),
        Map.entry("INK_SACK:3", "ENCHANTED_COCOA"), Map.entry("SUGAR_CANE", "ENCHANTED_SUGAR"),
        Map.entry("CACTUS", "ENCHANTED_CACTUS"), Map.entry("NETHER_STALK", "ENCHANTED_NETHER_STALK"),
        Map.entry("SEEDS", "ENCHANTED_SEEDS"));

    private static final int[] levels = {-1, -1, -1, -1, -1};
    private static volatile Map<String, Double> organicFactors = new LinkedHashMap<>();
    private static volatile Map<String, Double> fuelFactors = new LinkedHashMap<>();
    private static volatile List<Component> lines = List.of();
    private static double organic = -1, fuel = -1;

    private ComposterOverlay() {}

    private record Stats(double secondsPerCompost, double multiDrop, double maxOrganic, double maxFuel, double organicPer, double fuelPer) {}

    static void init() {
        SkyHanniRepo.load("Garden", json -> {
            Map<String, Double> o = new LinkedHashMap<>(), f = new LinkedHashMap<>();
            if (json.has("organic_matter") && json.get("organic_matter").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("organic_matter").entrySet()) {
                    String id = e.getKey().replace('-', ':');
                    double factor = e.getValue().getAsDouble();
                    o.put(id, factor);
                    String enchanted = ENCHANTED.get(id);
                    if (enchanted != null) o.put(enchanted, factor * 160);
                }
            }
            if (json.has("fuel") && json.get("fuel").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("fuel").entrySet()) f.put(e.getKey(), e.getValue().getAsDouble());
            }
            organicFactors = o;
            fuelFactors = f;
        });
        Sky2MHuds.setting(HUD, () -> {
            HelperConfig c = HelperUtil.cfg();
            return c != null && c.composter.enabled;
        });
        Sky2MHuds.register(HUD, "Composter Overlay",
            () -> false, // drawn by the menu
            () -> lines,
            List.of(SbcItems.parseLegacy("§e§lComposter Overlay"), SbcItems.parseLegacy("§7Time per Compost: §b7m 30s"),
                SbcItems.parseLegacy("§7Cost per Compost: §6 5.2k"), SbcItems.parseLegacy("§7Profit per hour: §6 120k")),
            5, 40);
    }

    private static boolean enabled() {
        HelperConfig c = HelperUtil.cfg();
        return c != null && c.composter.enabled && Sky2MLocation.inGarden();
    }

    private static boolean inMenu(String title) {
        return title.equals("Composter") || title.equals("Composter Upgrades");
    }

    // ------------------------------------------------------------------------------------------ data

    static void update(AbstractContainerScreen<?> screen, String title) {
        if (!enabled() || !inMenu(title)) return;
        if (title.equals("Composter Upgrades")) readUpgrades(screen);
        readTab();
        lines = build(screen, title);
    }

    private static void readUpgrades(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < Math.min(GardenHelpers.containerSize(screen), slots.size()); i++) {
            Matcher m = UPGRADE_NAME.matcher(HelperUtil.name(slots.get(i).getItem()));
            if (!m.matches()) continue;
            for (int u = 0; u < UPGRADES.length; u++) {
                if (UPGRADES[u].equals(m.group(1))) levels[u] = m.group(2) == null ? 0 : HelperUtil.roman(m.group(2));
            }
        }
    }

    private static void readTab() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        double o = -1, f = -1;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String line = TabText.plain(info);
            if (line == null) continue;
            Matcher m = ORGANIC.matcher(line);
            if (m.find()) {
                o = HelperUtil.parseAmount(m.group("amount"));
                continue;
            }
            m = FUEL.matcher(line);
            if (m.find()) f = HelperUtil.parseAmount(m.group("amount"));
        }
        organic = o;
        fuel = f;
    }

    private static Stats stats(int plus) {
        // plus: the index of the upgrade to add one level to, or -1.
        int speed = lv(0, plus), multi = lv(1, plus), fuelCap = lv(2, plus), matterCap = lv(3, plus), cost = lv(4, plus);
        double factor = 1.0 - cost / 100.0;
        return new Stats(600.0 / (1 + speed * 0.2), 1 + multi * 0.03, 40_000 + matterCap * 30_000, 100_000 + fuelCap * 30_000,
            4_000 * factor, 2_000 * factor);
    }

    private static int lv(int index, int plus) {
        return Math.max(0, levels[index]) + (index == plus ? 1 : 0);
    }

    private record Pick(String id, double factor, double price, double perUnit) {}

    private static List<Pick> cheapest(Map<String, Double> factors, int rows) {
        List<Pick> picks = new ArrayList<>();
        for (Map.Entry<String, Double> e : factors.entrySet()) {
            double price = Sky2MPriceTooltip.costPrice(e.getKey());
            if (price <= 0 || e.getValue() <= 0) continue;
            picks.add(new Pick(e.getKey(), e.getValue(), price, price / e.getValue()));
        }
        picks.sort((a, b) -> Double.compare(a.perUnit, b.perUnit));
        return picks.size() > rows ? picks.subList(0, rows) : picks;
    }

    private static String itemName(String id) {
        String name = RepoItems.displayName(id);
        return name == null ? "§f" + id : name;
    }

    private static int itemsNeeded(double missing, double factor, boolean roundDown) {
        if (missing <= 0) return 0;
        double amount = missing / factor;
        if (!roundDown) return (int) Math.ceil(amount);
        return amount > 0.75 && amount < 1.0 ? 1 : (int) Math.floor(amount);
    }

    private static List<Component> build(AbstractContainerScreen<?> screen, String title) {
        HelperConfig config = HelperUtil.cfg();
        List<Component> out = new ArrayList<>();
        out.add(SbcItems.parseLegacy("§e§lComposter Overlay"));
        boolean known = true;
        for (int level : levels) if (level < 0) known = false;
        if (!known) {
            out.add(SbcItems.parseLegacy("§cOpen Composter Upgrades to read your upgrade levels!"));
            return out;
        }
        if (organicFactors.isEmpty() || fuelFactors.isEmpty()) {
            out.add(SbcItems.parseLegacy("§7Waiting for SkyHanni's repo..."));
            return out;
        }
        int plus = -1;
        if (title.equals("Composter Upgrades")) {
            Slot hovered = GardenHelpers.hovered(screen);
            if (hovered != null) {
                int index = screen.getMenu().slots.indexOf(hovered) - FIRST_UPGRADE_SLOT;
                if (index >= 0 && index < UPGRADES.length && levels[index] < 25) plus = index;
            }
        }
        Stats now = stats(-1), then = stats(plus);
        if (plus >= 0) {
            out.add(SbcItems.parseLegacy("§7Upgrade preview: §a" + UPGRADES[plus] + " " + levels[plus] + " §7to §a" + (levels[plus] + 1)));
        }
        out.add(SbcItems.parseLegacy("§7Time per Compost: " + change(HelperUtil.duration(now.secondsPerCompost), HelperUtil.duration(then.secondsPerCompost), plus >= 0 && then.secondsPerCompost != now.secondsPerCompost)));
        out.add(SbcItems.parseLegacy("§7Compost factor: §e" + String.format(Locale.US, "%.2f", now.multiDrop)
            + (plus >= 0 && then.multiDrop != now.multiDrop ? " §c➜ §e" + String.format(Locale.US, "%.2f", then.multiDrop) : "")));
        out.add(SbcItems.parseLegacy("§7Organic Matter per Compost: §e" + HelperUtil.shortNumber(now.organicPer)
            + (plus >= 0 && then.organicPer != now.organicPer ? " §c➜ §e" + HelperUtil.shortNumber(then.organicPer) : "")));
        out.add(SbcItems.parseLegacy("§7Fuel per Compost: §2" + HelperUtil.shortNumber(now.fuelPer)
            + (plus >= 0 && then.fuelPer != now.fuelPer ? " §c➜ §2" + HelperUtil.shortNumber(then.fuelPer) : "")));
        out.add(SbcItems.parseLegacy("§7Organic Matter full lasts: §b" + HelperUtil.duration(Math.floor(now.maxOrganic / now.organicPer) * now.secondsPerCompost)
            + (plus >= 0 && (then.maxOrganic != now.maxOrganic || then.organicPer != now.organicPer || then.secondsPerCompost != now.secondsPerCompost)
            ? " §c➜ §b" + HelperUtil.duration(Math.floor(then.maxOrganic / then.organicPer) * then.secondsPerCompost) : "")));
        out.add(SbcItems.parseLegacy("§7Fuel full lasts: §b" + HelperUtil.duration(Math.floor(now.maxFuel / now.fuelPer) * now.secondsPerCompost)
            + (plus >= 0 && (then.maxFuel != now.maxFuel || then.fuelPer != now.fuelPer || then.secondsPerCompost != now.secondsPerCompost)
            ? " §c➜ §b" + HelperUtil.duration(Math.floor(then.maxFuel / then.fuelPer) * then.secondsPerCompost) : "")));

        List<Pick> organics = cheapest(organicFactors, config.composter.rows);
        List<Pick> fuels = cheapest(fuelFactors, config.composter.rows);
        double compostPrice = Sky2MPriceTooltip.unitPrice("COMPOST");
        if (!organics.isEmpty() && !fuels.isEmpty()) {
            double perCompostNow = organics.get(0).perUnit * now.organicPer + fuels.get(0).perUnit * now.fuelPer;
            double perCompostThen = organics.get(0).perUnit * then.organicPer + fuels.get(0).perUnit * then.fuelPer;
            out.add(SbcItems.parseLegacy("§7Cost per Compost: §6" + HelperUtil.shortNumber(perCompostNow)
                + (plus >= 0 && perCompostThen != perCompostNow ? " §c➜ §6" + HelperUtil.shortNumber(perCompostThen) : "")));
            if (compostPrice > 0) {
                double profitNow = (compostPrice * now.multiDrop - perCompostNow) * 3600 / now.secondsPerCompost;
                double profitThen = (compostPrice * then.multiDrop - perCompostThen) * 3600 / then.secondsPerCompost;
                out.add(SbcItems.parseLegacy("§7Profit per hour: " + (profitNow >= 0 ? "§6" : "§c") + HelperUtil.shortNumber(profitNow)
                    + (plus >= 0 && profitThen != profitNow ? " §c➜ " + (profitThen >= 0 ? "§6" : "§c") + HelperUtil.shortNumber(profitThen) : "")));
            }
        }
        if (organic >= 0 && fuel >= 0) {
            out.add(SbcItems.parseLegacy(""));
            out.add(SbcItems.parseLegacy("§eOrganic Matter §7(" + HelperUtil.shortNumber(organic) + "/" + HelperUtil.shortNumber(now.maxOrganic) + ") §7to fill:"));
            for (Pick p : organics) {
                int n = itemsNeeded(now.maxOrganic - organic, p.factor, config.composter.roundDown);
                out.add(SbcItems.parseLegacy(" " + itemName(p.id) + " §8x" + n + " §7(§6" + HelperUtil.shortNumber(n * p.price) + "§7)"));
            }
            out.add(SbcItems.parseLegacy("§2Fuel §7(" + HelperUtil.shortNumber(fuel) + "/" + HelperUtil.shortNumber(now.maxFuel) + ") §7to fill:"));
            for (Pick p : fuels) {
                int n = itemsNeeded(now.maxFuel - fuel, p.factor, config.composter.roundDown);
                out.add(SbcItems.parseLegacy(" " + itemName(p.id) + " §8x" + n + " §7(§6" + HelperUtil.shortNumber(n * p.price) + "§7)"));
            }
        } else {
            out.add(SbcItems.parseLegacy("§7(Turn on the tab list Composter widget to see how much to buy)"));
        }
        return out;
    }

    private static String change(String now, String then, boolean differs) {
        return differs ? "§b" + now + " §c➜ §b" + then : "§b" + now;
    }

    static void draw(AbstractContainerScreen<?> screen, String title, GuiGraphicsExtractor graphics) {
        if (!enabled() || !inMenu(title) || lines.isEmpty()) return;
        int[] at = Sky2MHuds.screenPosition(HUD, lines);
        Sky2MHuds.Placement p = Sky2MHuds.placement(HUD);
        Sky2MHuds.render(graphics, lines, at[0], at[1], p.scale, p.background);
    }
}
