package com.epic60869.sky2m.features.portfolio;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Outbid and filled-order alerts, using the Bazaar Utils-style watched-order flow. */
public final class BazaarNotifications {
    private static final Pattern FILLED = Pattern.compile("^(?:\\[Bazaar] )?Your (?<side>Buy Order|Sell Offer) for [\\d,]+x (?<item>.+) was filled!$");
    private static final Pattern CANCELLED = Pattern.compile("^(?:\\[Bazaar] )?Your (?<side>Buy Order|Sell Offer) for (?:[\\d,]+x )?(?<item>.+) was cancelled!$");
    private static final long ORDER_CHECK_MS = 1_000L;
    private static final long SOUND_GAP_MS = 180L;

    private static final class WatchedOrder {
        final String key;
        final String name;
        final String productId;
        final int amount;
        final boolean buyOrder;
        double unitPrice;
        boolean positioned;
        boolean outbid;

        WatchedOrder(String key, String name, String productId, int amount, boolean buyOrder, double unitPrice) {
            this.key = key;
            this.name = name;
            this.productId = productId;
            this.amount = amount;
            this.buyOrder = buyOrder;
            this.unitPrice = unitPrice;
        }
    }

    private static final Map<String, WatchedOrder> ORDERS = new LinkedHashMap<>();
    private static final Queue<Integer> SOUND_BURSTS = new ArrayDeque<>();
    private static long lastCheck;
    private static long nextSoundAt;
    private static int screenTicks;

    private BazaarNotifications() {}

    private static Sky2MConfig.BazaarNotificationsSettings config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.bazaar;
    }

    public static void init() {
        Sky2MChat.onChat(BazaarNotifications::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(BazaarNotifications::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ORDERS.clear();
            SOUND_BURSTS.clear();
            lastCheck = 0;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ContainerScreen container)) return;
            ScreenEvents.afterTick(screen).register(s -> {
                if (++screenTicks % 20 != 0 || !Compat.isOnSkyblock() || !isOrdersScreen(container)) return;
                if (enabled()) rememberOrders(container);
                if (coloursEnabled()) Portfolio.refreshPrices(false);
            });
            ScreenEvents.remove(screen).register(s -> PARSED.clear());
        });
    }

    private static boolean enabled() {
        Sky2MConfig.BazaarNotificationsSettings c = config();
        return c != null && c.enabled && Compat.isOnSkyblock();
    }

    private static boolean coloursEnabled() {
        Sky2MConfig.BazaarNotificationsSettings c = config();
        return c != null && c.orderColours && Compat.isOnSkyblock();
    }

    // ------------------------------------------------------------ order colours (Bazaar Utils)

    private enum Position { BEST, MATCHED, OUTBID }

    /** An order item in the orders menu; {@code order} is null for items that aren't open orders. */
    private record ParsedSlot(WatchedOrder order, boolean full) {}

    /** Parsed order items, so lore isn't read every frame; cleared when the menu closes. */
    private static final Map<ItemStack, ParsedSlot> PARSED = new java.util.WeakHashMap<>();

    /**
     * Colours your orders' slots in the Bazaar orders menu by where they stand (Bazaar Utils' order highlight): green
     * when yours is the best price, yellow when another order matches it, red when outbid. Drawn behind the item.
     */
    public static void renderSlot(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.world.inventory.Slot slot) {
        if (!slot.hasItem() || slot.container instanceof Inventory || !coloursEnabled()) return;
        if (!(Minecraft.getInstance().gui.screen() instanceof ContainerScreen screen) || !isOrdersScreen(screen)) return;
        ItemStack stack = slot.getItem();
        ParsedSlot parsed = PARSED.computeIfAbsent(stack, s -> new ParsedSlot(parseOrderStack(s), isFull(s)));
        if (parsed.order == null || parsed.full) return;
        Position position = position(parsed.order);
        if (position == null) return;
        int colour = switch (position) {
            case BEST -> 0x9055DD55;
            case MATCHED -> 0x90FFDD33;
            case OUTBID -> 0x90FF4444;
        };
        g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour);
    }

    private static Position position(WatchedOrder order) {
        double best = Portfolio.bazaarOrderBookPrice(order.productId, order.buyOrder);
        if (best <= 0) return null;
        // Prices are shown to 0.1 coins.
        if (Math.abs(order.unitPrice - best) < 0.05) {
            return Portfolio.bazaarOrderBookCount(order.productId, order.buyOrder) > 1 ? Position.MATCHED : Position.BEST;
        }
        boolean outbid = order.buyOrder ? order.unitPrice < best : order.unitPrice > best;
        // Better than the API's best: yours is newer than the last price update.
        return outbid ? Position.OUTBID : Position.BEST;
    }

    /** A fully filled order is only waiting to be claimed, so it can't be outbid. */
    private static boolean isFull(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            String text = clean(line.getString());
            if (text.startsWith("Filled:") && text.contains("100%")) return true;
        }
        return false;
    }

    private static boolean isOrdersScreen(ContainerScreen screen) {
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        return title.contains("order") && (title.contains("bazaar") || title.contains("manage"));
    }

    private static void rememberOrders(AbstractContainerScreen<?> screen) {
        for (var slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.hasItem()) continue;
            WatchedOrder order = parseOrderStack(slot.getItem());
            if (order != null) remember(order);
        }
    }

    private static WatchedOrder parseOrderStack(ItemStack stack) {
        String title = clean(stack.getHoverName().getString());
        boolean buyOrder;
        String name;
        if (title.startsWith("BUY ")) {
            buyOrder = true;
            name = title.substring(4).trim();
        } else if (title.startsWith("SELL ")) {
            buyOrder = false;
            name = title.substring(5).trim();
        } else return null;

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return null;
        int amount = -1;
        double unitPrice = -1;
        for (Component line : lore.lines()) {
            String text = clean(line.getString());
            if (text.contains("Order amount:") || text.contains("Offer amount:")) {
                amount = firstInteger(text);
            }
            if (text.toLowerCase(Locale.ROOT).contains("per unit")) {
                unitPrice = firstDouble(text);
            }
        }
        return watchedOrder(name, amount, buyOrder, unitPrice);
    }

    private static void onChat(Sky2MChat.Message message) {
        if (!enabled()) return;
        String text = message.text().trim();
        Matcher filled = FILLED.matcher(text);
        if (filled.matches()) {
            remove(filled.group("item"), filled.group("side").equals("Buy Order"));
            Sky2MConfig.BazaarNotificationsSettings c = config();
            if (c.filledSound) queueSound(2);
            return;
        }
        Matcher cancelled = CANCELLED.matcher(text);
        if (cancelled.matches()) {
            remove(cancelled.group("item"), cancelled.group("side").equals("Buy Order"));
            return;
        }
        WatchedOrder order = parseCreatedOrder(message.component());
        if (order != null) remember(order);
    }

    private static WatchedOrder parseCreatedOrder(Component message) {
        List<Component> parts = new ArrayList<>(message.getSiblings());
        if (parts.size() <= 3) return null;
        String setup = clean(parts.get(2).getString());
        boolean buyOrder = setup.contains("Buy Order Setup!");
        if (!buyOrder && !setup.contains("Sell Offer Setup!")) return null;
        int amount = firstInteger(clean(parts.get(3).getString()));
        int nameIndex = parts.size() == 10 ? 6 : 5;
        if (nameIndex >= parts.size()) return null;
        String name = clean(parts.get(nameIndex).getString());

        int forIndex = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (clean(parts.get(i).getString()).equalsIgnoreCase("for")) forIndex = i;
        }
        if (forIndex < 0 || forIndex + 1 >= parts.size()) return null;
        double totalPrice = firstDouble(clean(parts.get(forIndex + 1).getString()));
        if (amount > 0 && totalPrice > 0) return watchedOrder(name, amount, buyOrder, totalPrice / amount);
        return null;
    }

    private static WatchedOrder watchedOrder(String name, int amount, boolean buyOrder, double unitPrice) {
        if (name == null || name.isBlank() || amount <= 0 || unitPrice <= 0) return null;
        String id = Portfolio.bazaarProductId(name);
        if (id == null) id = RepoItems.idByName(name);
        if (id == null || id.isBlank()) return null;
        String key = (buyOrder ? "buy:" : "sell:") + normalize(name) + ":" + amount;
        return new WatchedOrder(key, name, id, amount, buyOrder, unitPrice);
    }

    private static void remember(WatchedOrder newOrder) {
        WatchedOrder previous = ORDERS.get(newOrder.key);
        if (previous == null) {
            ORDERS.put(newOrder.key, newOrder);
        } else if (Math.abs(previous.unitPrice - newOrder.unitPrice) > 1e-6) {
            previous.unitPrice = newOrder.unitPrice;
            previous.positioned = false;
            previous.outbid = false;
        }
    }

    private static void remove(String itemName, boolean buyOrder) {
        String prefix = (buyOrder ? "buy:" : "sell:") + normalize(itemName) + ":";
        ORDERS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static void tick(Minecraft mc) {
        if (!enabled()) {
            ORDERS.clear();
            SOUND_BURSTS.clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck >= ORDER_CHECK_MS) {
            lastCheck = now;
            checkOrders();
        }
        if (!SOUND_BURSTS.isEmpty() && now >= nextSoundAt && mc.player != null) {
            SOUND_BURSTS.poll();
            nextSoundAt = now + SOUND_GAP_MS;
            Sky2MAlerts.play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
        }
    }

    private static void checkOrders() {
        Portfolio.refreshPrices(false);
        for (WatchedOrder order : ORDERS.values()) {
            double best = Portfolio.bazaarOrderBookPrice(order.productId, order.buyOrder);
            if (best <= 0) continue;
            boolean outbid = order.buyOrder ? order.unitPrice < best : order.unitPrice > best;
            if (order.positioned && outbid && !order.outbid) notifyOutbid(order);
            order.positioned = true;
            order.outbid = outbid;
        }
    }

    private static void notifyOutbid(WatchedOrder order) {
        Sky2MConfig.BazaarNotificationsSettings c = config();
        if (c.outbidSound) queueSound(3);
        if (!c.outbidChat) return;
        Component message = Component.literal("Your " + (order.buyOrder ? "buy order" : "sell offer") + " for "
                + String.format(Locale.US, "%,d", order.amount) + "x ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal(order.name).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" is now outdated. ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("[Open Bazaar Orders]").withStyle(style -> style.withColor(ChatFormatting.GOLD)
                .withClickEvent(new ClickEvent.RunCommand("managebazaarorders"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open your Bazaar orders")))));
        Sky2MAlerts.chat(message);
    }

    private static void queueSound(int count) {
        for (int i = 0; i < count; i++) SOUND_BURSTS.add(i);
    }

    private static int firstInteger(String text) {
        Matcher matcher = Pattern.compile("[\\d,]+").matcher(text);
        return matcher.find() ? (int) parseNumber(matcher.group()) : -1;
    }

    private static double firstDouble(String text) {
        Matcher matcher = Pattern.compile("[\\d,]+(?:\\.\\d+)?").matcher(text);
        return matcher.find() ? parseNumber(matcher.group()) : -1;
    }

    private static double parseNumber(String text) {
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String clean(String text) {
        String result = ChatFormatting.stripFormatting(text);
        return result == null ? "" : result.trim();
    }

    private static String normalize(String text) {
        return clean(text).replaceAll("[^A-Za-z0-9 ]", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}