package com.epic60869.sky2m;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sky2M-owned farming RNG detection. Profit tracking remains SkySoft-owned. */
public final class FarmingRngTracker {
    private static final FarmingRngTracker INSTANCE = new FarmingRngTracker();
    private static final long SHOW_MILLIS = 3000L;
    private static final Pattern RARE_MESSAGE = Pattern.compile(
        "(?i)^(?:.*?)(?:RARE CROP!|(?:VERY |CRAZY |PRAY TO RNGESUS |RNGESUS INCARNATE )?RARE DROP!?|VERY RARE DROP!?|CRAZY RARE DROP!?|PRAY TO RNGESUS!?|RNGESUS INCARNATE!?)[ ]*(?<item>.+?)\\s*(?:\\(\\+[^)]*\\))?[! ]*$"
    );
    private static final Pattern OLDER_DROP = Pattern.compile(
        "(?i)(?:you (?:found|dropped|got)|you received|drop(?:ped)?[: ])\\s*(?:an? |some )?(?<item>.+?)(?:!|$)"
    );
    private static final Pattern QUANTITY = Pattern.compile("(?i)^(?:x(?<x1>\\d+)\\s+|(?<x2>\\d+)x\\s+)(?<item>.+)$");

    private static final Set<String> DROPS = Set.of(
        "Cornucopia", "Carrot Zest", "Deepfries", "Aggourdian", "Cane Knot", "Melon Juice", "Cactus Flower",
        "Designer Coffee Beans", "Feastfungus", "Botroot", "Salted Sunflower Seeds", "Crystalized Moonlight",
        "Floral Gelatin", "Helianthus", "Seasoning",
        "Cropie", "Squash", "Fermento", "Burrowing Spores", "Overgrown Grass", "Green Bandana",
        "Dedication IV", "Dedication 4", "Flowering Bouquet", "Rooted Spores", "Fruit Bowl", "Atmospheric Filter",
        "Beady Eyes", "Chirping Stereo", "Clipped Wings", "Bookworm's Favorite Book", "Mantid Claw", "Wriggling Larva",
        "Locust Larva", "Squeaky Toy", "Squeaky Mousemat", "Vermin Vaporizer", "Vermin Vaporizer Chip",
        "Pesterminator I", "Slug Pet", "Rat Pet", "Synthesis", "Synthesis Chip", "Evergreen", "Evergreen Chip",
        "Quickdraw", "Quickdraw Chip", "Hypercharge", "Hypercharge Chip", "Fire in a Bottle", "Iridium",
        "Overclocker 3000", "Rabbit Hat", "Lucky Clover Core", "Bulky Stone", "Rarefinder Chip", "Cropshot Chip",
        "Sowledge Chip", "Mechamind Chip", "Overdrive Chip", "Sunset I",
        "Turbo-Wheat V", "Turbo-Carrot V", "Turbo-Potato V", "Turbo-Pumpkin V", "Turbo-Melon V", "Turbo-Cocoa V",
        "Turbo-Cactus V", "Turbo-Mushrooms V", "Turbo-Cane V", "Turbo-Warts V", "Cultivating X", "Cultivating 10"
    );

    private final ConcurrentHashMap<String, Drop> activeDrops = new ConcurrentHashMap<>();
    private FarmingRngTracker() {}
    public static FarmingRngTracker get() { return INSTANCE; }

    public void register() {
        ItemPriceResolver.warmup();
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> handle(message));
        com.epic60869.sky2m.features.core.Sky2MChat.onGameMessage((message, overlay) -> handle(message));
    }

    /** Returns every currently displayed RNG drop, removing entries idle for 3 seconds. */
    public List<Drop> active() {
        long now = System.currentTimeMillis();
        activeDrops.entrySet().removeIf(entry -> now > entry.getValue().shownUntil());
        return new ArrayList<>(activeDrops.values());
    }

    /** "PET DROP! Slug (+123% ✯ Magic Find)"; the pet's rarity is the colour of its name. */
    private static final Pattern SLUG_DROP = Pattern.compile("(?i)^(?:PET DROP|RARE DROP|VERY RARE DROP|CRAZY RARE DROP|PRAY TO RNGESUS)!? .*\\bSlug\\b");
    private static final long EPIC_SLUG_PRICE = 500_000L;
    private static final long LEGENDARY_SLUG_PRICE = 5_000_000L;

    /** Epic and Legendary slug pets from pests, with a set price for each rarity. */
    private boolean handleSlug(Component message, String raw) {
        if (!SLUG_DROP.matcher(raw).find()) return false;
        boolean legendary = "LEGENDARY".equals(slugRarity(message));
        String name = legendary ? "Legendary Slug Pet" : "Epic Slug Pet";
        long price = legendary ? LEGENDARY_SLUG_PRICE : EPIC_SLUG_PRICE;
        String key = name.toLowerCase(Locale.ROOT);
        long shownUntil = System.currentTimeMillis() + SHOW_MILLIS;
        activeDrops.merge(key, new Drop(1, name, legendary ? "LEGENDARY" : "EPIC", price, shownUntil),
            (current, added) -> new Drop(current.amount() + 1, current.name(), current.rarity(), price, shownUntil));
        return true;
    }

    private static final Pattern LEGACY_SLUG = Pattern.compile("§([0-9a-f])(?:§[k-or])*Slug");

    /** "LEGENDARY" when "Slug" is gold, "EPIC" when purple (the default). */
    private static String slugRarity(Component message) {
        Matcher legacy = LEGACY_SLUG.matcher(message.getString());
        if (legacy.find()) return legacy.group(1).equals("6") ? "LEGENDARY" : "EPIC";
        String[] found = {null};
        message.visit((style, text) -> {
            if (found[0] == null && text.contains("Slug") && style.getColor() != null) {
                found[0] = style.getColor().getValue() == 0xFFAA00 ? "LEGENDARY" : "EPIC";
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found[0] == null ? "EPIC" : found[0];
    }

    private void handle(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String raw = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
        if (handleSlug(message, raw)) return;
        Parsed parsed = parse(raw);
        if (parsed == null) return;

        String key = parsed.name().toLowerCase(Locale.ROOT);
        long shownUntil = System.currentTimeMillis() + SHOW_MILLIS;
        Drop existing = activeDrops.get(key);
        int newAmount = (existing == null ? 0 : existing.amount()) + parsed.amount();

        if ("Seasoning".equalsIgnoreCase(parsed.name())) {
            activeDrops.put(key, new Drop(newAmount, parsed.name(), rarity(raw), -1L, shownUntil));
            return;
        }

        // Keep the entry immediately so the 3-second timer starts on the actual drop.
        // The price is filled asynchronously without losing any drops that arrive meanwhile.
        if (existing == null) {
            activeDrops.putIfAbsent(key, new Drop(newAmount, parsed.name(), rarity(raw), 0L, shownUntil));
        } else {
            activeDrops.computeIfPresent(key, (ignored, current) ->
                new Drop(current.amount() + parsed.amount(), current.name(), current.rarity(), current.unitPrice(), shownUntil));
        }

        ItemPriceResolver.valueByNameAsync(parsed.name()).thenAccept(price ->
            activeDrops.computeIfPresent(key, (ignored, current) ->
                new Drop(current.amount(), current.name(), current.rarity(), Math.round(price), current.shownUntil()))
        );
    }

    private Parsed parse(String raw) {
        Matcher match = RARE_MESSAGE.matcher(raw);
        String item;
        if (match.find()) item = match.group("item").trim();
        else {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (!lower.contains("rare drop") && !lower.contains("rngesus") && !lower.contains("rare crop")) return null;
            Matcher older = OLDER_DROP.matcher(raw);
            if (!older.find()) return null;
            item = older.group("item").trim();
        }

        item = item.replaceAll("\\s*\\(\\+[^)]*\\)\\s*$", "")
            .replaceAll("!$", "").trim();
        int amount = 1;
        Matcher quantity = QUANTITY.matcher(item);
        if (quantity.matches()) {
            amount = parseInt(quantity.group("x1"), quantity.group("x2"));
            item = quantity.group("item").trim();
        }

        for (String known : DROPS) {
            if (item.equalsIgnoreCase(known) || item.toLowerCase(Locale.ROOT).contains(known.toLowerCase(Locale.ROOT)))
                return new Parsed(amount, known);
        }
        return null;
    }

    private static int parseInt(String first, String second) {
        try { return Integer.parseInt(first != null ? first : second); }
        catch (Exception ignored) { return 1; }
    }

    private static String rarity(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        if (text.contains("rare crop")) return "RARE CROP";
        if (text.contains("crazy rare")) return "CRAZY RARE";
        if (text.contains("rngesus")) return "RNGESUS";
        return "RARE DROP";
    }

    public record Drop(int amount, String name, String rarity, long unitPrice, long shownUntil) {}
    private record Parsed(int amount, String name) {}
}
