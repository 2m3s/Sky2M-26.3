package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sacks Value: what everything in your sacks is worth ("3.8M-5.9M in sacks (212,543 items)"), like the value line of
 * SkyHanni's Sack Display (an idea from SkyHanni's SackApi / SackDisplay, LGPL-2.1; no code was copied).
 * <p>
 * The amounts come from {@link SackTracker} (sack menus and "[Sacks]" messages). Unlike SkyHanni, bazaar insta-sells
 * are followed too, so you don't need to reopen your sacks after each one: for every "[Bazaar] Sold 1,234x Enchanted
 * Carrot for 5,678 coins!" the part that wasn't in your inventory just before (Hypixel sells the inventory first) is
 * taken out of the sacks. A bulk sale with no per-item amounts marks the sacks "(reopen sacks)" instead.
 */
public final class SacksValue {
    /** "[Bazaar] Sold 1,234x Enchanted Carrot for 5,678 coins!" (Sell Instantly, and each item of a bulk sell). */
    private static final Pattern SOLD_ITEM = Pattern.compile("^\\[Bazaar] Sold (?<amount>[\\d,]+)x (?<item>.+?) for (?<price>[\\d,.]+) coins?!?$");
    /** A bulk sale's summary: "[Bazaar] Sold 1,234 items for 5,678 coins!" (any "[Bazaar] ... sold N items ..."). */
    private static final Pattern SOLD_BULK = Pattern.compile("^\\[Bazaar] (?:You )?[Ss]old (?<amount>[\\d,]+) items?\\b.*$");
    /** One item in a bulk sale's hover: "1,234x Enchanted Carrot ..." or "Enchanted Carrot x1,234 ...". */
    private static final Pattern HOVER_AMOUNT_FIRST = Pattern.compile("^\\s*[+-]?(?<amount>[\\d,]+)x (?<item>.+?)(?: for .*| [(].*)?$");
    private static final Pattern HOVER_AMOUNT_LAST = Pattern.compile("^\\s*(?<item>[^\\d].*?) x(?<amount>[\\d,]+)\\b.*$");
    /** How far back the inventory is remembered, to know what was in it just before a sale. */
    private static final long INVENTORY_HISTORY_MS = 4_000L;

    private record InventorySnapshot(long time, Map<String, Long> counts, long total) {}

    private static final Deque<InventorySnapshot> INVENTORY = new ArrayDeque<>();
    /** Lower-case item name -> item id (or "" when unknown), so the repo is searched once per name. */
    private static final Map<String, String> IDS = new ConcurrentHashMap<>();
    private static final AtomicBoolean COMPUTING = new AtomicBoolean();

    private static volatile List<Component> lines = List.of();
    private static volatile boolean dirty = true;
    private static long lastCompute;
    private static long lastItemSold;
    private static long bulkPendingAt;
    private static long bulkPendingAmount;
    private static String lastProfile = "";
    private static FeatureConfigs.SacksPriceMode lastMode;
    private static int ticks;

    private SacksValue() {}

    public static void init() {
        SackTracker.onUpdate(() -> dirty = true);
        Sky2MChat.onChat(message -> onChat(message.component(), message.text()));
        ClientTickEvents.END_CLIENT_TICK.register(SacksValue::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("resetsacks").executes(c -> {
                    Minecraft.getInstance().execute(() -> {
                        SackTracker.reset();
                        Sky2MAlerts.chat(Component.literal("Forgot what's in your sacks on this profile. Open your sacks to fill them in again.").withStyle(ChatFormatting.YELLOW));
                    });
                    return 1;
                })));
            }
        });
        Sky2MHuds.register("sacks_value", "Sacks Value",
            () -> config() != null && config().enabled && Compat.isOnSkyblock(),
            () -> lines,
            List.of(Component.literal("3.8M-5.9M").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" in sacks ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("(212,543 items)").withStyle(ChatFormatting.DARK_GRAY))),
            8, 180);
    }

    private static FeatureConfigs.SacksValue config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.sacksValue;
    }

    // ----- Keeping the sacks right through bazaar sales -----

    private static void tick(Minecraft mc) {
        ticks++;
        long now = System.currentTimeMillis();
        if (mc.player != null && ticks % 5 == 0 && Compat.isOnSkyblock()) {
            Map<String, Long> counts = new HashMap<>();
            long total = 0;
            for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
                if (stack.isEmpty()) continue;
                counts.merge(name(stack.getHoverName().getString()), (long) stack.getCount(), Long::sum);
                total += stack.getCount();
            }
            INVENTORY.addLast(new InventorySnapshot(now, counts, total));
            while (!INVENTORY.isEmpty() && now - INVENTORY.peekFirst().time() > INVENTORY_HISTORY_MS) INVENTORY.removeFirst();
        }
        // A bulk sale with no per-item amounts (none in its hover, no "Sold 64x Item" lines around it): the sacks
        // can't be followed, so ask for them to be reopened.
        if (bulkPendingAt > 0 && now - bulkPendingAt > 3_000L) {
            if (Math.abs(lastItemSold - bulkPendingAt) > 3_000L && bulkPendingAmount > maxInventoryTotal()) SackTracker.markStale();
            bulkPendingAt = 0;
        }

        FeatureConfigs.SacksValue c = config();
        if (c == null || !c.enabled) return;
        String profile = SackTracker.profileKey();
        if (!profile.equals(lastProfile) || c.priceMode != lastMode) {
            lastProfile = profile;
            lastMode = c.priceMode;
            dirty = true;
        }
        // Rebuilt when something changed (at most once a second), and every 30 seconds for new prices.
        if ((dirty && now - lastCompute >= 1_000L) || now - lastCompute >= 30_000L) compute(c.priceMode);
    }

    private static void onChat(Component component, String text) {
        if (text == null || !text.startsWith("[Bazaar]") || !Compat.isOnSkyblock()) return;
        Matcher item = SOLD_ITEM.matcher(text);
        if (item.matches()) {
            lastItemSold = System.currentTimeMillis();
            sold(item.group("item"), parseLong(item.group("amount")));
            return;
        }
        Matcher bulk = SOLD_BULK.matcher(text);
        if (!bulk.matches()) return;
        Map<String, Long> perItem = hoverItems(component);
        if (!perItem.isEmpty()) {
            lastItemSold = System.currentTimeMillis();
            perItem.forEach(SacksValue::sold);
            return;
        }
        bulkPendingAt = System.currentTimeMillis();
        bulkPendingAmount = parseLong(bulk.group("amount"));
    }

    /**
     * {@code amount} of an item was sold: Hypixel takes it from your inventory first, so only what's beyond what the
     * inventory held just before (the most seen in the last few seconds) came out of the sacks.
     */
    private static void sold(String itemName, long amount) {
        if (amount <= 0) return;
        String key = name(itemName);
        long inInventory = 0;
        for (InventorySnapshot s : INVENTORY) inInventory = Math.max(inInventory, s.counts().getOrDefault(key, 0L));
        long fromSacks = amount - inInventory;
        if (fromSacks > 0) SackTracker.removeSold(itemName, fromSacks);
    }

    private static long maxInventoryTotal() {
        long max = 0;
        for (InventorySnapshot s : INVENTORY) max = Math.max(max, s.total());
        return max;
    }

    /** The items and amounts listed in a bulk sale message's hover, if Hypixel gives them. */
    private static Map<String, Long> hoverItems(Component component) {
        Map<String, Long> items = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Component> parts = new ArrayList<>();
        collect(component, parts);
        for (Component part : parts) {
            if (!(part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component hover))) continue;
            String hoverText = ChatFormatting.stripFormatting(hover.getString());
            if (hoverText == null || !seen.add(hoverText)) continue;
            for (String line : hoverText.split("\n")) {
                Matcher m = HOVER_AMOUNT_FIRST.matcher(line);
                if (!m.matches()) m = HOVER_AMOUNT_LAST.matcher(line);
                if (!m.matches()) continue;
                long amount = parseLong(m.group("amount"));
                if (amount > 0) items.merge(m.group("item").trim(), amount, Long::sum);
            }
        }
        return items;
    }

    private static void collect(Component component, List<Component> out) {
        out.add(component);
        for (Component sibling : component.getSiblings()) collect(sibling, out);
    }

    // ----- The HUD line -----

    /** Prices the sacks off the render thread (item ids come from a search of the repo). */
    private static void compute(FeatureConfigs.SacksPriceMode mode) {
        if (!COMPUTING.compareAndSet(false, true)) return;
        dirty = false;
        lastCompute = System.currentTimeMillis();
        Map<String, Long> sacks = SackTracker.snapshot();
        boolean stale = SackTracker.isStale();
        String profile = SackTracker.profileKey();
        Sky2MPriceTooltip.warmup();
        CompletableFuture.runAsync(() -> {
            try {
                double low = 0, high = 0;
                long items = 0;
                for (Map.Entry<String, Long> e : sacks.entrySet()) {
                    long amount = e.getValue() == null ? 0 : e.getValue();
                    if (amount <= 0) continue;
                    items += amount;
                    String id = id(e.getKey());
                    if (id == null) continue;
                    double npc = orZero(Sky2MPriceTooltip.npcSellPrice(id));
                    double instant = orZero(Sky2MPriceTooltip.bazaarSellPrice(id));
                    double offer = orZero(Sky2MPriceTooltip.bazaarBuyPrice(id));
                    // Not on the bazaar: what an NPC pays. On it: never less than an NPC would pay.
                    double lowEach = Math.max(instant, npc);
                    double highEach = Math.max(offer > 0 ? offer : instant, npc);
                    low += lowEach * amount;
                    high += Math.max(lowEach, highEach) * amount;
                }
                lines = line(mode, low, high, items, stale, sacks.isEmpty());
                // A stale total (a sale it couldn't follow) would put a wrong point on the graph.
                if (!stale && !sacks.isEmpty()) SackValueHistory.record(profile, Math.round(mode == FeatureConfigs.SacksPriceMode.SELL_OFFER ? high : low));
            } finally {
                COMPUTING.set(false);
            }
        });
    }

    private static List<Component> line(FeatureConfigs.SacksPriceMode mode, double low, double high, long items,
                                        boolean stale, boolean empty) {
        if (empty && !stale) return List.of();
        String value = switch (mode) {
            case INSTANT_SELL -> CombatFeatures.formatCoins(low);
            case SELL_OFFER -> CombatFeatures.formatCoins(high);
            case RANGE -> CombatFeatures.formatCoins(low) + "-" + CombatFeatures.formatCoins(high);
        };
        var line = Component.literal(value).withStyle(ChatFormatting.GOLD)
            .append(Component.literal(" in sacks ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("(" + String.format(Locale.US, "%,d", items) + " items)").withStyle(ChatFormatting.DARK_GRAY));
        if (stale) line.append(Component.literal(" (reopen sacks)").withStyle(ChatFormatting.RED));
        return List.of(line);
    }

    /** The item id for a sack item's name, or null. Unknown names are looked up again once the repo has loaded. */
    private static String id(String name) {
        String cached = IDS.get(name);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String id = RepoItems.idByLooseName(name);
        if (id == null) {
            // Do not guess an API ID from a display name. A guessed ID can belong to a different Bazaar product,
            // assigning its price to this sack item and massively inflating the total. If the repo is not loaded yet,
            // try again on the next calculation; once loaded, remember unknown names as unknown.
            if (!RepoItems.allNames().isEmpty()) IDS.put(name, "");
            return null;
        }
        IDS.put(name, id);
        return id;
    }

    private static double orZero(Double value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static String name(String raw) {
        String plain = ChatFormatting.stripFormatting(raw);
        return plain == null ? "" : plain.trim().toLowerCase(Locale.ROOT);
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
