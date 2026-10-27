package com.epic60869.sky2m.features.garden.helpers;

import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anita's shop helpers from SkyHanni (LGPL-2.1, https://github.com/hannibal002/SkyHanni): AnitaExtraFarmingFortune
 * (what it costs to max out Extra Farming Fortune; the per level costs are SkyHanni's repo AnitaUpgradeCosts.json) and
 * AnitaMedalProfit (profit per Bronze Medal of every item in the shop).
 */
final class AnitaShop {
    private static final String HUD = "garden_anita_medal_profit";
    /** {gold medals, Jacob's Tickets} to buy level 1..15 (SkyHanni repo AnitaUpgradeCosts.json). */
    private static final int[][] LEVELS = {{1, 0}, {1, 50}, {1, 50}, {2, 100}, {2, 100}, {3, 150}, {3, 150}, {4, 200}, {4, 200},
        {5, 250}, {6, 300}, {7, 350}, {8, 400}, {9, 450}, {10, 1000}};
    private static final Pattern FARMING_FORTUNE = Pattern.compile("^You have: \\+(\\d+)\\S* Farming Fortune");
    private static final Pattern TICKETS = Pattern.compile("^Jacob's Ticket x([\\d,]+)");
    private static final Pattern COST_HEADER = Pattern.compile("^Costs?:?$");
    private static final Pattern COST_LINE = Pattern.compile("^(?<name>.+?)(?: x(?<amount>[\\d,]+))?$");
    private static final Pattern REWARD_AMOUNT_AFTER = Pattern.compile("^(?<name>.+?) x(?<amount>[\\d,]+)$");
    private static final Pattern REWARD_AMOUNT_BEFORE = Pattern.compile("^(?<amount>[\\d,]+)x (?<name>.+)$");

    private static volatile List<Component> lines = List.of();

    private AnitaShop() {}

    static void init() {
        Sky2MHuds.setting(HUD, () -> {
            HelperConfig c = HelperUtil.cfg();
            return c != null && c.anita.medalProfit;
        });
        Sky2MHuds.register(HUD, "Anita Medal Profit", () -> false, () -> lines,
            List.of(SbcItems.parseLegacy("§eProfit per Bronze Medal"), SbcItems.parseLegacy("§aEnchanted Carrot §6 12.3k"),
                SbcItems.parseLegacy("§aEnchanted Potato §6 8.1k")), 5, 60);
        ItemTooltipCallback.EVENT.register((stack, context, type, tooltip) -> extraFortuneTooltip(stack, tooltip));
    }

    private static boolean inAnita(String title) {
        return title.equals("Anita") && Sky2MLocation.inGarden();
    }

    // ------------------------------------------------------------------------------------------ Extra Farming Fortune

    private static void extraFortuneTooltip(ItemStack stack, List<Component> tooltip) {
        HelperConfig c = HelperUtil.cfg();
        if (c == null || !c.anita.extraFarmingFortune) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null || !inAnita(HelperUtil.title(mc.gui.screen())) || !HelperUtil.name(stack).equals("Extra Farming Fortune")) return;

        int fortune = 0;
        double realTickets = 0;
        int fortuneLine = -1, tradeLine = -1;
        for (int i = 0; i < tooltip.size(); i++) {
            String line = HelperUtil.strip(tooltip.get(i).getString());
            Matcher m = FARMING_FORTUNE.matcher(line);
            if (m.find()) {
                fortune = Integer.parseInt(m.group(1));
                fortuneLine = i;
            }
            m = TICKETS.matcher(line);
            if (m.find()) realTickets = HelperUtil.parseAmount(m.group(1));
            if (line.equals("Click to trade!")) tradeLine = i;
        }
        int tier = fortune / 4;
        int next = tier + 1;
        if (next > LEVELS.length || tradeLine < 0) return;
        double base = LEVELS[next - 1][1];
        double factor = base > 0 ? realTickets / base : 0;
        int goldMedals = 0, tickets = 0;
        for (int level = tier + 1; level <= LEVELS.length; level++) {
            goldMedals += LEVELS[level - 1][0];
            tickets += LEVELS[level - 1][1];
        }
        tickets = (int) (factor * tickets);
        double price = tickets * Sky2MPriceTooltip.costPrice("JACOBS_TICKET");
        int at = Math.max(0, tradeLine - 1);
        tooltip.add(at, SbcItems.parseLegacy(""));
        tooltip.add(at + 1, SbcItems.parseLegacy("§7Cost to max out"));
        tooltip.add(at + 2, SbcItems.parseLegacy("§6Gold medals: §8x" + goldMedals));
        tooltip.add(at + 3, SbcItems.parseLegacy("§aJacob Tickets §8x" + String.format(Locale.US, "%,d", tickets)));
        tooltip.add(at + 4, SbcItems.parseLegacy("  §7Price: §6" + HelperUtil.shortNumber(price) + " coins"));
        if (fortuneLine >= 0) tooltip.add(fortuneLine + 1, SbcItems.parseLegacy("§7Current Tier: §e" + tier + "/" + LEVELS.length));
    }

    // ------------------------------------------------------------------------------------------ Medal profit

    private record Row(String name, double perBronze, double sell, double extra, int bronze) {}

    static void update(AbstractContainerScreen<?> screen, String title) {
        HelperConfig c = HelperUtil.cfg();
        if (c == null || !c.anita.medalProfit || !inAnita(title)) {
            lines = List.of();
            return;
        }
        List<Row> rows = new ArrayList<>();
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < Math.min(GardenHelpers.containerSize(screen), slots.size()); i++) {
            Row row = readItem(slots.get(i).getItem());
            if (row != null) rows.add(row);
        }
        rows.sort((a, b) -> Double.compare(b.perBronze, a.perBronze));
        List<Component> out = new ArrayList<>();
        out.add(SbcItems.parseLegacy("§eProfit per Bronze Medal"));
        for (Row row : rows) {
            out.add(SbcItems.parseLegacy("§f" + row.name + " " + (row.perBronze > 0 ? "§6" : "§c") + HelperUtil.shortNumber(row.perBronze)
                + " §8(" + row.bronze + " bronze)"));
        }
        lines = rows.isEmpty() ? List.of() : out;
    }

    private static Row readItem(ItemStack stack) {
        if (stack.isEmpty()) return null;
        List<String> lore = HelperUtil.lore(stack);
        if (lore.stream().noneMatch(l -> l.equals("Click to trade!") || l.equals("You can't afford this upgrade!"))) return null;
        int header = -1;
        for (int i = 0; i < lore.size(); i++) if (COST_HEADER.matcher(lore.get(i)).matches()) header = i;
        if (header < 0) return null;
        int bronze = -1;
        double extra = 0;
        for (int i = header + 1; i < lore.size() && !lore.get(i).isEmpty(); i++) {
            Matcher m = COST_LINE.matcher(lore.get(i).trim());
            if (!m.matches()) continue;
            String name = m.group("name").trim();
            int amount = m.group("amount") == null ? 1 : (int) HelperUtil.parseAmount(m.group("amount"));
            String lower = name.toLowerCase(Locale.ROOT);
            int factor = lower.contains("gold medal") ? 8 : lower.contains("silver medal") ? 2 : lower.contains("bronze medal") ? 1 : 0;
            if (factor > 0) {
                bronze = Math.max(0, bronze) + factor * amount;
                continue;
            }
            String id = RepoItems.idByName(name);
            if (id == null) return null; // can't price an extra cost: don't show a wrong profit
            extra += Sky2MPriceTooltip.costPrice(id) * amount;
        }
        if (bronze <= 0) return null; // no medal cost, e.g. the InfiniDirt Wand
        String name = HelperUtil.name(stack);
        int amount = 1;
        Matcher m = REWARD_AMOUNT_AFTER.matcher(name);
        if (m.matches()) {
            name = m.group("name");
            amount = (int) HelperUtil.parseAmount(m.group("amount"));
        } else if ((m = REWARD_AMOUNT_BEFORE.matcher(name)).matches()) {
            name = m.group("name");
            amount = (int) HelperUtil.parseAmount(m.group("amount"));
        }
        String id = RepoItems.idByName(name);
        if (id == null) return null;
        double sell = Sky2MPriceTooltip.unitPrice(id) * amount;
        if (sell <= 0) return null;
        double profit = sell - extra;
        return new Row(name + (amount > 1 ? " x" + amount : ""), profit / bronze, sell, extra, bronze);
    }

    static void draw(String title, GuiGraphicsExtractor graphics) {
        HelperConfig c = HelperUtil.cfg();
        if (c == null || !c.anita.medalProfit || !inAnita(title) || lines.isEmpty()) return;
        int[] at = Sky2MHuds.screenPosition(HUD, lines);
        Sky2MHuds.Placement p = Sky2MHuds.placement(HUD);
        Sky2MHuds.render(graphics, lines, at[0], at[1], p.scale, p.background);
    }
}
