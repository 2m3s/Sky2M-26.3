package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.gui.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chocolate Factory helpers: SkyHanni's stats display, the best upgrade to buy, the Time Tower and the Stray Rabbit timer. Ported from
 * SkyHanni's chocolate factory features (https://github.com/hannibal002/SkyHanni, features/inventory/chocolatefactory/,
 * LGPL-2.1). The advice compares cost per extra chocolate per second like SkyHanni does for the rabbits and Coach
 * Jackrabbit, but does not factor the Time Tower in.
 */
public final class ChocolateFactoryFeatures {
    private static final String ADVICE_HUD = "events_cf_advice";
    private static final String STRAY_HUD = "events_cf_stray";
    private static final String TOWER_HUD = "events_cf_tower";
    private static final String STATS_HUD = "events_cf_stats";
    private static final int INFO_SLOT = 13;
    private static final int PRESTIGE_SLOT = 27;
    private static final int BARN_SLOT = 35;
    private static final int LEADERBOARD_SLOT = 52;
    private static final int TOWER_SLOT = 38;
    private static final int COACH_SLOT = 41;
    private static final int FIRST_RABBIT_SLOT = 28;
    private static final int LAST_RABBIT_SLOT = 34;
    private static final long STRAY_MS = 30_000L;
    private static final long TOWER_WARNING_MS = 5 * 60 * 1000L;
    private static final long TOWER_CHARGE_MS = 8 * 60 * 60 * 1000L;

    private static final Pattern PER_SECOND = Pattern.compile("([\\d.,]+) per second");
    private static final Pattern MULTIPLIER = Pattern.compile("Total Multiplier: ([\\d.,]+)x");
    private static final Pattern AMOUNT = Pattern.compile("([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern COST = Pattern.compile("^([\\d,]+) Chocolate$");
    private static final Pattern CHARGES = Pattern.compile("Charges: (\\d+)/(\\d+)");
    private static final Pattern STATUS = Pattern.compile("Status: (INACTIVE|ACTIVE)\\s*(.*)");
    private static final Pattern NEXT_CHARGE = Pattern.compile("Next Charge: (.+)");
    private static final Pattern ALL_TIME = Pattern.compile("All-time Chocolate: ([\\d,]+)");
    private static final Pattern PRESTIGE = Pattern.compile("^Chocolate Factory ([IVX]+)$");
    private static final Pattern THIS_PRESTIGE = Pattern.compile("Chocolate this Prestige: ([\\d,]+)");
    private static final Pattern FOR_PRESTIGE = Pattern.compile("Requires ([\\d.]+[kMBT]?) Chocolate this");
    private static final Pattern BARN = Pattern.compile("Your Barn: (\\d+)/(\\d+) Rabbits");
    private static final Pattern POSITION = Pattern.compile("You are #([\\d,]+)");
    private static final Pattern PERCENTILE = Pattern.compile("You are in the top ([\\d.]+)% of players");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([dhms])");

    /** What the last look at the open factory found. */
    private record Option(int slot, String name, long cost, double extraPerSecond) {
        double paybackSeconds() {
            return cost / extraPerSecond;
        }
    }

    private static volatile Option best;
    private static volatile Option affordable;
    private static volatile double chocolate;
    private static volatile double perSecond;

    /** The factory's numbers from the last look, for the stats display; null before the factory was opened. */
    private record Stats(double current, double perSecond, double multiplier, long allTime, int prestige, long thisPrestige,
                         long forPrestige, int rabbits, int maxRabbits, String position, String percentile) {}

    private static volatile Stats stats;

    private static volatile int towerCharges = -1;
    private static volatile int towerMax;
    private static volatile String towerStatus = "";
    private static volatile long towerSeenAt;
    private static volatile long towerNextChargeAt;
    private static volatile boolean towerActive;
    private static long lastTowerWarning;

    private static boolean strayArmed;
    private static long strayRemaining;
    private static long strayLastTick;
    private static long lastDing;
    private static int ticks;

    private ChocolateFactoryFeatures() {}

    private static EventsConfig.ChocolateFactory config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.chocolateFactory;
    }

    static boolean inFactory() {
        return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
            && Sky2MLocation.strip(screen.getTitle().getString()).trim().equals("Chocolate Factory");
    }

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ChocolateFactoryFeatures::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> highlight(container, graphics));
        });
        Sky2MChat.onChat(message -> onChat(message.text().trim()));

        ScreenHuds.register(ADVICE_HUD, ChocolateFactoryFeatures::adviceLines);
        Sky2MHuds.register(ADVICE_HUD, "Chocolate Factory Advice", () -> false, List::of, List.of(
            Component.literal("Best upgrade: ").withStyle(ChatFormatting.GRAY).append(Component.literal("Rabbit Bro").withStyle(ChatFormatting.GREEN)),
            Component.literal(" 1.2M, pays back in 3h 20m").withStyle(ChatFormatting.DARK_GRAY)), 10, 40);
        Sky2MHuds.setSettingsSearch(ADVICE_HUD, "Upgrade Advice");
        Sky2MHuds.setting(ADVICE_HUD, () -> config() != null && config().upgradeAdvice);

        ScreenHuds.register(STRAY_HUD, ChocolateFactoryFeatures::strayLines);
        Sky2MHuds.register(STRAY_HUD, "Stray Rabbit Timer", () -> false, List::of, List.of(
            Component.literal("Stray Timer").withStyle(ChatFormatting.YELLOW),
            Component.literal("24.50s").withStyle(ChatFormatting.AQUA)), 10, 80);
        Sky2MHuds.setSettingsSearch(STRAY_HUD, "Stray Rabbit Timer");
        Sky2MHuds.setting(STRAY_HUD, () -> config() != null && config().strayTimer);

        ScreenHuds.register(STATS_HUD, ChocolateFactoryFeatures::statsLines);
        Sky2MHuds.register(STATS_HUD, "Chocolate Factory Stats", () -> false, List::of, List.of(
            Component.literal("§6§lChocolate Factory §7(Prestige V)"), Component.literal("§7Chocolate §f5,272,230"),
            Component.literal("§7All-time §f641,119,115 §8· §7this prestige §f483,023,853"),
            Component.literal("§7Production §f3,780.72§7/s §8· §f226,843.2§7/m"),
            Component.literal("§7Multiplier §f1.77x §8· §7Barn §f171/190")), 10, 130);
        Sky2MHuds.setSettingsSearch(STATS_HUD, "Stats Display");
        Sky2MHuds.setting(STATS_HUD, () -> config() != null && config().stats);

        Sky2MHuds.register(TOWER_HUD, "Time Tower", () -> {
            EventsConfig.ChocolateFactory c = config();
            return c != null && c.timeTower && Sky2MLocation.onSkyblock() && towerCharges >= 0;
        }, ChocolateFactoryFeatures::towerLines, List.of(
            Component.literal("Time Tower: ").withStyle(ChatFormatting.GOLD).append(Component.literal("2/3 charges").withStyle(ChatFormatting.AQUA)),
            Component.literal(" Inactive").withStyle(ChatFormatting.GRAY)), 10, 100);
        Sky2MHuds.setSettingsSearch(TOWER_HUD, "Time Tower Display");
        Sky2MHuds.setting(TOWER_HUD, () -> config() != null && config().timeTower);
    }

    // ------------------------------------------------------------------------------------------------ chat / timer

    private static void onChat(String text) {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.strayTimer || !text.startsWith("HOPPITY'S HUNT You found")) return;
        if (text.contains("Stray Rabbit")) {
            strayArmed = false;
        } else if (text.contains("Egg ")) {
            strayArmed = true;
            strayRemaining = STRAY_MS;
            strayLastTick = System.currentTimeMillis();
        }
    }

    private static void tick(Minecraft mc) {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        boolean factory = inFactory();
        if (c.strayTimer && strayArmed) {
            long dt = Math.min(500, now - strayLastTick);
            if (factory) {
                strayRemaining -= dt;
                if (strayRemaining <= 0) {
                    strayArmed = false;
                } else if (c.strayDing > 0 && strayRemaining < c.strayDing * 1000L && now - lastDing > 1000) {
                    Sky2MAlerts.ding();
                    lastDing = now;
                }
            } else {
                strayRemaining = STRAY_MS;
            }
        }
        strayLastTick = now;

        if (factory && ++ticks % 5 == 0 && (c.stats || c.upgradeAdvice || c.timeTower || c.timeTowerWarning)) read(mc);
        if (!factory && c.timeTowerWarning && Sky2MLocation.onSkyblock() && towerCharges >= 0 && towerCharges >= towerMax && towerMax > 0
            && now - lastTowerWarning > TOWER_WARNING_MS) {
            lastTowerWarning = now;
            EventsFeatures.sayClickable("Your Time Tower was full (" + towerCharges + "/" + towerMax + " charges) when you last looked. Use one so none go to waste. Click to open the Chocolate Factory.",
                "/cf", "Click to run /cf");
        }
    }

    // ------------------------------------------------------------------------------------------------ reading the factory

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> out = new ArrayList<>();
        if (lore != null) for (Component line : lore.lines()) out.add(Sky2MLocation.strip(line.getString()).trim());
        return out;
    }

    private static String name(ItemStack stack) {
        return Sky2MLocation.strip(stack.getHoverName().getString()).trim();
    }

    private static Long cost(List<String> lore) {
        boolean afterCost = false;
        for (String line : lore) {
            if (line.equals("Cost")) {
                afterCost = true;
                continue;
            }
            if (!afterCost) continue;
            Matcher m = COST.matcher(line);
            if (m.matches()) return Long.parseLong(m.group(1).replace(",", ""));
        }
        return null;
    }

    private static double number(String text) {
        return Double.parseDouble(text.replace(",", ""));
    }

    private static void read(Minecraft mc) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        try {
            var slots = screen.getMenu().slots;
            if (slots.size() <= COACH_SLOT) return;
            ItemStack info = slots.get(INFO_SLOT).getItem();
            if (info.isEmpty()) return;
            Matcher m = AMOUNT.matcher(name(info));
            double current = m.find() ? number(m.group(1)) : -1;
            double cps = -1;
            double multiplier = -1;
            for (String line : lore(info)) {
                Matcher p = PER_SECOND.matcher(line);
                if (p.find()) cps = number(p.group(1));
            }
            for (int i = 0; i < Math.min(54, slots.size()) && multiplier < 0; i++) {
                for (String line : lore(slots.get(i).getItem())) {
                    Matcher p = MULTIPLIER.matcher(line);
                    if (p.find()) {
                        multiplier = number(p.group(1));
                        break;
                    }
                }
            }
            readTower(slots.get(TOWER_SLOT).getItem());
            if (current < 0 || cps <= 0 || multiplier <= 0) return;
            chocolate = current;
            perSecond = cps;
            readStats(slots, current, cps, multiplier, info);
            double raw = cps / multiplier;

            List<Option> options = new ArrayList<>();
            for (int slot = FIRST_RABBIT_SLOT; slot <= LAST_RABBIT_SLOT; slot++) {
                ItemStack stack = slots.get(slot).getItem();
                if (stack.isEmpty() || !name(stack).startsWith("Rabbit ")) continue;
                Long cost = cost(lore(stack));
                if (cost == null) continue;
                options.add(new Option(slot, name(stack), cost, (slot - FIRST_RABBIT_SLOT + 1) * multiplier));
            }
            ItemStack coach = slots.get(COACH_SLOT).getItem();
            if (!coach.isEmpty() && name(coach).startsWith("Coach Jackrabbit")) {
                Long cost = cost(lore(coach));
                if (cost != null) options.add(new Option(COACH_SLOT, name(coach), cost, raw * 0.01));
            }
            Option bestOption = null;
            Option affordableOption = null;
            for (Option o : options) {
                if (o.extraPerSecond() <= 0) continue;
                if (bestOption == null || o.paybackSeconds() < bestOption.paybackSeconds()) bestOption = o;
                if (o.cost() <= current && (affordableOption == null || o.paybackSeconds() < affordableOption.paybackSeconds())) affordableOption = o;
            }
            best = bestOption;
            affordable = affordableOption;
        } catch (Exception ignored) {
            // The menu changed while it was read; try again next time.
        }
    }

    private static void readTower(ItemStack stack) {
        if (stack.isEmpty()) return;
        for (String line : lore(stack)) {
            Matcher m = CHARGES.matcher(line);
            if (m.find()) {
                towerCharges = Integer.parseInt(m.group(1));
                towerMax = Integer.parseInt(m.group(2));
                towerSeenAt = System.currentTimeMillis();
                if (towerCharges < towerMax) lastTowerWarning = System.currentTimeMillis();
                continue;
            }
            m = STATUS.matcher(line);
            if (m.find()) {
                towerActive = m.group(1).equals("ACTIVE");
                towerStatus = towerActive ? "Active " + m.group(2).trim() : "Inactive";
                towerSeenAt = System.currentTimeMillis();
                continue;
            }
            m = NEXT_CHARGE.matcher(line);
            if (m.find()) towerNextChargeAt = System.currentTimeMillis() + durationMs(m.group(1));
        }
    }

    /** SkyHanni's CFDataLoader: the prestige, barn and leaderboard items, and the all-time chocolate. */
    private static void readStats(List<Slot> slots, double current, double cps, double multiplier, ItemStack info) {
        long allTime = 0;
        for (String line : lore(info)) {
            Matcher m = ALL_TIME.matcher(line);
            if (m.find()) allTime = (long) number(m.group(1));
        }
        int prestige = 1;
        long thisPrestige = 0;
        long forPrestige = -1;
        ItemStack prestigeItem = slots.get(PRESTIGE_SLOT).getItem();
        Matcher p = PRESTIGE.matcher(name(prestigeItem));
        if (p.find()) prestige = roman(p.group(1));
        for (String line : lore(prestigeItem)) {
            Matcher m = THIS_PRESTIGE.matcher(line);
            if (m.find()) thisPrestige = (long) number(m.group(1));
            m = FOR_PRESTIGE.matcher(line);
            if (m.find()) forPrestige = shortNumber(m.group(1));
        }
        int rabbits = -1;
        int maxRabbits = -1;
        for (String line : lore(slots.get(BARN_SLOT).getItem())) {
            Matcher m = BARN.matcher(line);
            if (m.find()) {
                rabbits = Integer.parseInt(m.group(1));
                maxRabbits = Integer.parseInt(m.group(2));
            }
        }
        String position = null;
        String percentile = null;
        for (String line : lore(slots.get(LEADERBOARD_SLOT).getItem())) {
            Matcher m = POSITION.matcher(line);
            if (m.find()) position = m.group(1);
            m = PERCENTILE.matcher(line);
            if (m.find()) percentile = m.group(1);
        }
        stats = new Stats(current, cps, multiplier, allTime, prestige, thisPrestige, forPrestige, rabbits, maxRabbits, position, percentile);
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private static int roman(String text) {
        for (int i = 1; i < ROMAN.length; i++) if (ROMAN[i].equals(text)) return i;
        return 1;
    }

    /** "150M", "4B", "1.5k" as a number. */
    private static long shortNumber(String text) {
        char last = text.charAt(text.length() - 1);
        double scale = switch (last) {
            case 'k' -> 1e3;
            case 'M' -> 1e6;
            case 'B' -> 1e9;
            case 'T' -> 1e12;
            default -> 1;
        };
        return (long) (number(scale == 1 ? text : text.substring(0, text.length() - 1)) * scale);
    }

    /** "4h 32m 10s" (or "4h32m") in milliseconds. */
    private static long durationMs(String text) {
        long ms = 0;
        Matcher m = DURATION_PART.matcher(text);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            ms += switch (m.group(2)) {
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1000L;
            };
        }
        return ms;
    }

    // ------------------------------------------------------------------------------------------------ display

    private static void highlight(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.upgradeAdvice || !inFactory()) return;
        Option bestOption = best;
        Option affordableOption = affordable;
        Sky2MContainerScreenAccessor access = (Sky2MContainerScreenAccessor) screen;
        if (bestOption != null) box(graphics, screen, access, bestOption.slot(), 0xFF55FF55);
        if (affordableOption != null && (bestOption == null || affordableOption.slot() != bestOption.slot())) {
            box(graphics, screen, access, affordableOption.slot(), 0xFFFFAA00);
        }
    }

    private static void box(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, Sky2MContainerScreenAccessor access, int index, int argb) {
        if (index >= screen.getMenu().slots.size()) return;
        Slot slot = screen.getMenu().slots.get(index);
        int x = access.sky2m$getLeftPos() + slot.x;
        int y = access.sky2m$getTopPos() + slot.y;
        g.fill(x - 1, y - 1, x + 17, y, argb);
        g.fill(x - 1, y + 16, x + 17, y + 17, argb);
        g.fill(x - 1, y, x, y + 16, argb);
        g.fill(x + 16, y, x + 17, y + 16, argb);
        g.fill(x, y, x + 16, y + 16, (argb & 0x00FFFFFF) | 0x38000000);
    }

    private static List<Component> adviceLines() {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.upgradeAdvice || !inFactory()) return List.of();
        Option bestOption = best;
        if (bestOption == null) return List.of();
        List<Component> lines = new ArrayList<>();
        lines.add(option("Best upgrade", ChatFormatting.GREEN, bestOption));
        if (bestOption.cost() > chocolate && perSecond > 0) {
            lines.add(Component.literal(" Affordable in " + EventsFeatures.duration((long) ((bestOption.cost() - chocolate) / perSecond * 1000)))
                .withStyle(ChatFormatting.GRAY));
        }
        Option affordableOption = affordable;
        if (affordableOption != null && affordableOption.slot() != bestOption.slot()) {
            lines.add(option("Best affordable", ChatFormatting.GOLD, affordableOption));
        }
        return lines;
    }

    private static Component option(String label, ChatFormatting colour, Option o) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(o.name()).withStyle(colour))
            .append(Component.literal(" (" + EventsFeatures.compact(o.cost()) + ", pays back in "
                + EventsFeatures.duration((long) (o.paybackSeconds() * 1000)) + ")").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static List<Component> strayLines() {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.strayTimer || !strayArmed || !inFactory() || strayRemaining <= 0) return List.of();
        return List.of(Component.literal("Stray Timer").withStyle(ChatFormatting.YELLOW),
            Component.literal(String.format(Locale.US, "%.2fs", strayRemaining / 1000.0)).withStyle(ChatFormatting.AQUA));
    }

    /** The stats display: SkyHanni's CFStats numbers, laid out Sky2M's way (grey labels, white values, related stats on one line). */
    private static List<Component> statsLines() {
        EventsConfig.ChocolateFactory c = config();
        Stats s = stats;
        if (c == null || !c.stats || s == null || !inFactory()) return List.of();
        String dot = " §8· ";
        List<String> lines = new ArrayList<>();
        lines.add("§6§lChocolate Factory" + (s.prestige() > 1 ? " §7(Prestige " + (s.prestige() < ROMAN.length ? ROMAN[s.prestige()] : String.valueOf(s.prestige())) + ")" : ""));
        lines.add("§7Chocolate §f" + EventsFeatures.grouped((long) s.current()));
        lines.add("§7All-time §f" + EventsFeatures.grouped(s.allTime())
            + (s.prestige() != 1 ? dot + "§7this prestige §f" + EventsFeatures.grouped(s.thisPrestige()) : ""));
        if (s.forPrestige() > 0) {
            long left = s.forPrestige() - s.thisPrestige();
            lines.add(left <= 0 ? "§7Prestige §aready now"
                : "§7Prestige in " + untilGoal(s.thisPrestige(), s.forPrestige(), s.perSecond()) + " §8(" + EventsFeatures.grouped(left) + " to go)");
        }
        lines.add("§7Production §f" + separators(s.perSecond()) + "§7/s" + dot + "§f" + separators(s.perSecond() * 60) + "§7/m");
        lines.add("  §f" + separators(s.perSecond() * 3600) + "§7/h" + dot + "§f" + separators(s.perSecond() * 86400) + "§7/day");
        lines.add("§7Multiplier §f" + separators(s.multiplier()) + "x"
            + (s.rabbits() >= 0 ? dot + "§7Barn " + (s.rabbits() >= s.maxRabbits() ? "§c" : "§f") + s.rabbits() + "/" + s.maxRabbits() : ""));
        if (towerCharges >= 0) {
            String tower = "§7Time Tower " + (towerActive ? "§d§lActive" : "§f" + towerCharges + "/" + towerMax);
            if (towerMax > 0 && towerCharges >= towerMax) {
                tower += dot + "§cfull now";
            } else if (towerMax > 0 && towerNextChargeAt > 0) {
                long fullAt = towerNextChargeAt + (towerMax - towerCharges - 1) * TOWER_CHARGE_MS;
                tower += dot + "§7full in " + "§b" + longDuration(fullAt - System.currentTimeMillis())
                    + " §8(" + new java.text.SimpleDateFormat("EEE h:mm a", Locale.US).format(new java.util.Date(fullAt)) + ")";
            }
            lines.add(tower);
        }
        if (s.position() != null) lines.add("§7Leaderboard §b#" + s.position() + (s.percentile() == null ? "" : " §8(top " + s.percentile() + "%)"));
        Option bestOption = best;
        if (bestOption != null) {
            lines.add("§7Best upgrade " + ((long) s.current() >= bestOption.cost() ? "§aaffordable now"
                : "in " + untilGoal((long) s.current(), bestOption.cost(), s.perSecond())));
        }
        List<Component> out = new ArrayList<>();
        for (String line : lines) out.add(Component.literal(line));
        return out;
    }

    /** SkyHanni's formattedTimeUntilGoal: "Now" when there is enough, otherwise how long production takes to get there. */
    private static String untilGoal(long have, long goal, double perSecond) {
        if (have >= goal) return "§aNow";
        if (perSecond <= 0) return "§cNever";
        return "§b" + longDuration((long) ((goal - have) / perSecond * 1000));
    }

    /** "1d 13h 59m 4s", like SkyHanni's Duration.format(). */
    private static String longDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        StringBuilder out = new StringBuilder();
        long[] parts = {seconds / 86400, seconds % 86400 / 3600, seconds % 3600 / 60, seconds % 60};
        String[] units = {"d", "h", "m", "s"};
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == 0 && !(i == 3 && out.isEmpty())) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(parts[i]).append(units[i]);
        }
        return out.toString();
    }

    /** SkyHanni's addSeparators() for decimals: "1,234.56". */
    private static String separators(double value) {
        return new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }

    private static List<Component> towerLines() {
        List<Component> lines = new ArrayList<>();
        boolean full = towerCharges >= towerMax && towerMax > 0;
        lines.add(Component.literal("Time Tower: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(towerCharges + "/" + towerMax + " charges").withStyle(full ? ChatFormatting.RED : ChatFormatting.AQUA)));
        if (!towerStatus.isEmpty()) {
            lines.add(Component.literal(" " + towerStatus + " (seen " + EventsFeatures.duration(System.currentTimeMillis() - towerSeenAt) + " ago)").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }
}
