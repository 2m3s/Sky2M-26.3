package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Item Pickup Log, like SkyHanni's: what goes into and out of your inventory, for a few seconds after it happens, as
 * "+64 [icon] Enchanted Diamond". Optionally also what your sacks gain and lose (from Hypixel's [Sacks] messages),
 * shards sent to your Hunting Box, and coins in your purse, with the total value of what's shown. Items moving around
 * inside your inventory don't count: only the total of each item is compared.
 */
public final class ItemPickupLog {
    /** Hypixel's messages for shards you get, as SkyHanni reads them ("amount" is missing for one). */
    private static final List<Pattern> SHARD_MESSAGES = List.of(
        Pattern.compile("CATCH! You caught(?: [an]+)? (?<name>.+) Shard(?: x(?<amount>\\d+))?!"),
        Pattern.compile("^You caught(?: [an]+)?(?: x(?<amount>\\d+))? (?<name>.+) Shards?!"),
        Pattern.compile("^CAPTURE! You (?:caught an?|found) .+ and (?:gained|as a reward (?:he|she|they|it) gave you) (?:an?|(?<amount>\\d+)x) (?<name>.+) Shard!"),
        Pattern.compile("^CHARM! You charmed the .+ and received (?<amount>\\d+) (?<name>.+) Shards?!"),
        Pattern.compile("^FUSION! You obtained(?: an?)? (?<name>.+) Shard(?: x(?<amount>\\d+))?!"),
        Pattern.compile("^SHARD! Your contribution earned you the (?<name>.+) Shard!"),
        Pattern.compile("^FLOOR DROP! You found (?<name>.+) Shard on the ground!"),
        Pattern.compile("^You sent (?:an?|(?<amount>\\d+)) (?<name>.+) Shards? to your Hunting Box\\."));

    /** One line of the log: what it is, how many came and went, and when it last changed. */
    private static final class Entry {
        final String key;
        String name;
        ItemStack icon;
        String priceId;
        long added, removed;
        long changedAt;

        Entry(String key) {
            this.key = key;
        }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static Map<String, Integer> lastCounts;
    private static Map<String, ItemStack> lastStacks = Map.of();
    private static Object lastLevel;
    private static double lastPurse = -1;

    private ItemPickupLog() {}

    private static FeatureConfigs.ItemPickupLog config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.itemPickupLog;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ItemPickupLog::tick);
        SackTracker.onSackChange((name, delta) -> {
            FeatureConfigs.ItemPickupLog c = config();
            if (c == null || !c.enabled || !c.sacks) return;
            String display = titleCase(name);
            add("sack:" + name, "§7[Sack] §f" + display, RepoItems.itemStack(idFromName(name)), idFromName(name), delta);
        });
        Sky2MChat.onChat(message -> {
            FeatureConfigs.ItemPickupLog c = config();
            if (c == null || !c.enabled || !c.shards) return;
            String text = message.text().trim();
            for (Pattern pattern : SHARD_MESSAGES) {
                Matcher m = pattern.matcher(text);
                if (!m.find()) continue;
                long amount = groupOrNull(m, "amount") == null ? 1 : Long.parseLong(groupOrNull(m, "amount"));
                String name = m.group("name").trim() + " Shard";
                add("shard:" + name, "§d" + name, new ItemStack(Items.PRISMARINE_SHARD), null, amount);
                return;
            }
        });
        Sky2MHuds.registerCustom("item_pickup_log", "Item Pickup Log", () -> {
            FeatureConfigs.ItemPickupLog c = config();
            return c != null && c.enabled;
        }, new Sky2MHuds.CustomHud() {
            @Override public int width() { return Math.max(60, contentWidth(preview())); }
            @Override public int height() { return Math.max(10, lines(preview()).size() * 11); }
            @Override public boolean visible() { return !ENTRIES.isEmpty(); }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean preview) {
                draw(graphics, lines(preview && ENTRIES.isEmpty()));
            }
        }, 4, 140);
    }

    private static String groupOrNull(Matcher m, String group) {
        try {
            return m.group(group);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean preview() {
        return ENTRIES.isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ tracking

    private static void tick(Minecraft mc) {
        FeatureConfigs.ItemPickupLog c = config();
        long now = System.currentTimeMillis();
        if (c != null) ENTRIES.values().removeIf(e -> now - e.changedAt > c.expireAfter * 1000L);
        if (c == null || !c.enabled || mc.player == null || !Compat.isOnSkyblock()) {
            lastCounts = null;
            lastPurse = -1;
            return;
        }
        // A new world (joining, warping): start again from what you have now.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            lastCounts = null;
            lastPurse = -1;
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<String, ItemStack> stacks = new HashMap<>();
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) count(inventory.getItem(i), counts, stacks);
        // The item on your cursor in a menu is still yours, so moving things around doesn't log anything.
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) count(screen.getMenu().getCarried(), counts, stacks);
        if (lastCounts != null) {
            java.util.Set<String> keys = new java.util.HashSet<>(counts.keySet());
            keys.addAll(lastCounts.keySet());
            for (String key : keys) {
                int delta = counts.getOrDefault(key, 0) - lastCounts.getOrDefault(key, 0);
                if (delta == 0) continue;
                ItemStack stack = stacks.getOrDefault(key, lastStacks.get(key));
                if (stack == null) continue;
                String id = Compat.neuName(stack);
                add("item:" + key, Sky2MLocation.strip(stack.getHoverName().getString()).isEmpty() ? key : legacyName(stack), stack.copyWithCount(1),
                    id.isEmpty() ? null : id, delta);
            }
        }
        lastCounts = counts;
        lastStacks = stacks;
        if (c.coins) trackPurse();
    }

    /** Items by SkyBlock id (or vanilla item and name), so a renamed stack of the same thing still adds up. */
    private static void count(ItemStack stack, Map<String, Integer> counts, Map<String, ItemStack> stacks) {
        if (stack == null || stack.isEmpty()) return;
        String id = Compat.neuName(stack);
        // The SkyBlock menu star and other menu-only items aren't items you have.
        if ("SKYBLOCK_MENU".equals(id)) return;
        String key = id.isEmpty() ? stack.getItem().toString() + "|" + stack.getHoverName().getString() : id;
        counts.merge(key, stack.getCount(), Integer::sum);
        stacks.putIfAbsent(key, stack);
    }

    private static void trackPurse() {
        String value = Sky2MLocation.scoreboardValue("Purse:");
        if (value == null) value = Sky2MLocation.scoreboardValue("Piggy:");
        if (value == null) return;
        Matcher m = Pattern.compile("[\\d,]+(?:\\.\\d+)?").matcher(value);
        if (!m.find()) return;
        double purse = Double.parseDouble(m.group().replace(",", ""));
        if (lastPurse >= 0 && Math.abs(purse - lastPurse) >= 1) {
            add("coins", "§6Coins", new ItemStack(Items.GOLD_NUGGET), null, Math.round(purse - lastPurse));
        }
        lastPurse = purse;
    }

    private static void add(String key, String name, ItemStack icon, String priceId, long delta) {
        Entry e = ENTRIES.computeIfAbsent(key, Entry::new);
        e.name = name;
        e.icon = icon;
        e.priceId = priceId;
        if (delta > 0) e.added += delta;
        else e.removed -= delta;
        e.changedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private record Line(String amount, int amountColour, ItemStack icon, String name) {}

    private static List<Line> lines(boolean preview) {
        FeatureConfigs.ItemPickupLog c = config();
        List<Line> lines = new ArrayList<>();
        if (preview) {
            lines.add(new Line("+64", 0xFF55FF55, new ItemStack(Items.DIAMOND), "§9Enchanted Diamond"));
            lines.add(new Line("-1", 0xFFFF5555, new ItemStack(Items.ENDER_PEARL), "§fEnder Pearl"));
            return lines;
        }
        if (c == null) return lines;
        List<Entry> entries = new ArrayList<>(ENTRIES.values());
        double value = 0;
        for (Entry e : entries) {
            if (c.compactLines) {
                long net = e.added - e.removed;
                if (net != 0) lines.add(new Line((net > 0 ? "+" : "-") + number(Math.abs(net), c), net > 0 ? 0xFF55FF55 : 0xFFFF5555, e.icon, e.name));
            } else {
                if (e.added > 0) lines.add(new Line("+" + number(e.added, c), 0xFF55FF55, e.icon, e.name));
                if (e.removed > 0) lines.add(new Line("-" + number(e.removed, c), 0xFFFF5555, e.icon, e.name));
            }
            if (e.priceId != null) value += (e.added - e.removed) * Sky2MPriceTooltip.price(e.priceId, c.coinValue.priceSource);
        }
        if (c.coinValue.enabled && Math.abs(value) >= c.coinValue.threshold && !lines.isEmpty()) {
            lines.add(new Line("", 0, null, "§7Total Value: " + (value >= 0 ? "§a+" : "§c-") + compact(Math.abs(value))));
        }
        if (c.alignment == FeatureConfigs.ItemPickupLog.Alignment.BOTTOM) java.util.Collections.reverse(lines);
        return lines;
    }

    private static String number(long n, FeatureConfigs.ItemPickupLog c) {
        return c.shorten ? compact(n) : String.format(Locale.US, "%,d", n);
    }

    private static String compact(double n) {
        if (n >= 1e9) return String.format(Locale.US, "%.1fb", n / 1e9);
        if (n >= 1e6) return String.format(Locale.US, "%.1fm", n / 1e6);
        if (n >= 1e3) return String.format(Locale.US, "%.1fk", n / 1e3);
        return String.format(Locale.US, "%,.0f", n);
    }

    private static int contentWidth(boolean preview) {
        Font font = Minecraft.getInstance().font;
        int w = 0;
        for (Line l : lines(preview)) w = Math.max(w, font.width(l.amount()) + 14 + font.width(Component.literal(l.name())) + 8);
        return w;
    }

    private static void draw(GuiGraphicsExtractor g, List<Line> lines) {
        FeatureConfigs.ItemPickupLog c = config();
        List<FeatureConfigs.ItemPickupLog.Part> layout = c == null || c.layout.isEmpty()
            ? List.of(FeatureConfigs.ItemPickupLog.Part.AMOUNT, FeatureConfigs.ItemPickupLog.Part.ICON, FeatureConfigs.ItemPickupLog.Part.NAME) : c.layout;
        Font font = Minecraft.getInstance().font;
        int y = 0;
        for (Line line : lines) {
            int x = 0;
            for (FeatureConfigs.ItemPickupLog.Part part : layout) {
                switch (part) {
                    case AMOUNT -> {
                        if (line.amount().isEmpty()) continue;
                        g.text(font, line.amount(), x, y + 1, line.amountColour(), true);
                        x += font.width(line.amount()) + 3;
                    }
                    case ICON -> {
                        if (line.icon() == null) continue;
                        g.pose().pushMatrix();
                        g.pose().translate(x, y);
                        g.pose().scale(0.625f, 0.625f);
                        g.item(line.icon(), 0, 0);
                        g.pose().popMatrix();
                        x += 13;
                    }
                    case NAME -> {
                        Component name = com.epic60869.sky2m.features.sbc.SbcItems.parseLegacy(line.name());
                        g.text(font, name, x, y + 1, 0xFFFFFFFF, true);
                        x += font.width(name) + 3;
                    }
                }
            }
            y += 11;
        }
    }

    private static String legacyName(ItemStack stack) {
        return com.epic60869.sky2m.features.sbc.SbcItems.legacy(stack.getHoverName());
    }

    private static String titleCase(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split(" ")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** "enchanted diamond" -> ENCHANTED_DIAMOND, good enough for sack items' prices and icons. */
    private static String idFromName(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
