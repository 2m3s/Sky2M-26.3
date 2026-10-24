package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.google.gson.Gson;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bazaar extras: the best way to sell the item on a product page (instant sell or sell offer after the 1.25% tax),
 * and a counter of the coins you sell on the Bazaar each UTC day, read from the Bazaar chat messages.
 */
final class BazaarExtras {
    private static final Pattern SOLD = Pattern.compile("^\\[Bazaar] Sold ([\\d,]+)x .+ for ([\\d,.]+) coins!");
    private static final Pattern CLAIMED = Pattern.compile("^\\[Bazaar] Claimed ([\\d,.]+) coins from selling ");
    private static final double TAX = 0.0125;
    private static final Gson GSON = new Gson();

    private static final class State {
        String day = "";
        double sold;
    }

    private static State state = new State();
    private static Path file;
    private static boolean warned;

    private BazaarExtras() {}

    static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("bazaar-daily.json");
        try {
            if (Files.exists(file)) {
                State loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
                if (loaded != null) state = loaded;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read bazaar-daily.json: " + e.getMessage());
        }
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register("economy_bazaar_daily", "Bazaar Daily Sales",
            () -> {
                EconomyConfig c = Economy.config();
                return c != null && c.bazaarDailyLimit && Compat.isOnSkyblock();
            },
            BazaarExtras::hudLines,
            List.of(Component.literal("Bazaar sold today: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("12,345,678 coins").withStyle(ChatFormatting.YELLOW))),
            4, 120);
    }

    private static boolean inBazaarMenu() {
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return false;
        String title = Economy.screenTitle(screen);
        return title.startsWith("Bazaar") || title.contains("➜");
    }

    private static List<Component> hudLines() {
        if (!inBazaarMenu()) return List.of();
        roll();
        return List.of(Component.literal("Bazaar sold today: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(ValueBreakdown.coins(state.sold) + " coins").withStyle(ChatFormatting.YELLOW)));
    }

    private static void roll() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (!today.equals(state.day)) {
            state = new State();
            state.day = today;
            warned = false;
        }
    }

    private static void onChat(String text) {
        EconomyConfig c = Economy.config();
        if (c == null || !c.bazaarDailyLimit || !text.startsWith("[Bazaar]")) return;
        double coins = 0;
        Matcher m = SOLD.matcher(text);
        if (m.find()) {
            coins = parse(m.group(2));
        } else {
            m = CLAIMED.matcher(text);
            if (m.find()) coins = parse(m.group(1));
        }
        if (coins <= 0) return;
        roll();
        state.sold += coins;
        save();
        long limit = c.bazaarDailyLimitWarnMillions * 1_000_000L;
        if (limit > 0 && state.sold >= limit && !warned) {
            warned = true;
            Economy.message(Component.literal("You have sold " + ValueBreakdown.coins(state.sold) + " coins on the Bazaar today (UTC).").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static double parse(String text) {
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save bazaar-daily.json: " + e.getMessage());
        }
    }

    /** On a product page, the product icon's tooltip says which way of selling pays more. */
    static void tooltip(ItemStack stack, List<Component> lines) {
        EconomyConfig c = Economy.config();
        if (c == null || !c.bazaarBestSell || !inBazaarMenu()) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container) || container.getMenu().slots.size() <= 13) return;
        // The product icon is slot 13 of a product page.
        if (container.getMenu().slots.get(13).getItem() != stack) return;
        String id = Sky2MPriceTooltip.marketId(stack);
        if (id.isEmpty()) return;
        Double instant = Sky2MPriceTooltip.bazaarSellPrice(id);
        Double offerPrice = Sky2MPriceTooltip.bazaarBuyPrice(id);
        if (instant == null || offerPrice == null || instant <= 0 || offerPrice <= 0) return;
        double offer = (offerPrice - 0.1) * (1 - TAX);
        boolean offerBetter = offer > instant;
        lines.add(Component.empty());
        lines.add(Component.literal("Best Sell Method: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(offerBetter ? "Sell Offer" : "Instant Sell").withStyle(ChatFormatting.GREEN)));
        lines.add(Component.literal(String.format(Locale.ENGLISH, " Instant Sell: %,.1f  Sell Offer (after tax): %,.1f  (%+,.1f each)",
            instant, offer, offer - instant)).withStyle(ChatFormatting.GRAY));
    }
}
