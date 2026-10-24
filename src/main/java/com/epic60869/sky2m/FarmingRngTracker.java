package com.epic60869.sky2m;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
        com.epic60869.sky2m.features.core.Sky2MChat.onGameMessage((message, overlay) -> {
            // Drops are chat lines; the action bar changes many times a second and never holds one.
            if (!overlay) handle(message);
        });
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
        activeDrops.merge(key, new Drop(1, name, legendary ? "LEGENDARY" : "EPIC", price, shownUntil, -1),
            (current, added) -> new Drop(current.amount() + 1, current.name(), current.rarity(), price, shownUntil, current.colour()));
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

    private static final long BIG_DROP_PRICE = 5_000_000L;

    /**
     * Any other rare drop (slayer, Diana, fishing, dungeons...) from Combat's rare drop detection: shown on the RNG HUD
     * when one of it is worth 5M or more. Farming drops are already handled above, so they're skipped here.
     */
    public void onRareDrop(Component message, String rawItem, int amount, boolean rng) {
        String item = cleanName(rawItem);
        if (item.isEmpty() || isFarmingDrop(item) || item.toLowerCase(Locale.ROOT).contains("slug")) return;
        int colour = itemColour(message, item);
        ItemPriceResolver.valueByNameAsync(item).thenAccept(ignored -> priceWithRetries(item, colour, amount, rng, 0));
    }

    /** Waits before each retry, for prices that haven't loaded yet (just joined, or the price APIs were slow). */
    private static final long[] RETRY_MILLIS = {2000L, 5000L, 10000L};

    private void priceWithRetries(String item, int colour, int amount, boolean rng, int attempt) {
        long unit = Math.round(bigDropPrice(item, colour));
        if (unit <= 0 && attempt < RETRY_MILLIS.length) {
            CompletableFuture.delayedExecutor(RETRY_MILLIS[attempt], TimeUnit.MILLISECONDS)
                .execute(() -> priceWithRetries(item, colour, amount, rng, attempt + 1));
            return;
        }
        // An RNG drop whose price can't be found is still shown (without a price) rather than missed.
        boolean show = unit >= BIG_DROP_PRICE || (unit <= 0 && rng);
        System.out.println("[Sky2M] RNG HUD: " + amount + "x " + item + " worth " + (unit <= 0 ? "unknown" : unit)
            + (rng ? " (RNG drop)" : "") + (show ? ", shown" : ", under 5M so not shown"));
        if (!show) return;
        long shownUntil = System.currentTimeMillis() + SHOW_MILLIS;
        long price = Math.max(unit, 0L);
        activeDrops.merge(item.toLowerCase(Locale.ROOT), new Drop(amount, item, "BIG", price, shownUntil, colour),
            (current, added) -> new Drop(current.amount() + amount, current.name(), current.rarity(), price, shownUntil, colour));
    }

    /** "Enchanted Book (Chimera I)" → "Chimera I", "[Lvl 1] Baby Yeti" → "Baby Yeti", no colour codes or trailing "!". */
    private static final Pattern ENCHANTED_BOOK_NAME = Pattern.compile("^Enchanted Book \\((.+)\\)$");
    private static final Pattern PET_LEVEL_PREFIX = Pattern.compile("^\\[Lvl \\d+] ");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[!.]+$");

    private static String cleanName(String item) {
        String name = net.minecraft.ChatFormatting.stripFormatting(item).trim();
        Matcher book = ENCHANTED_BOOK_NAME.matcher(name);
        if (book.matches()) name = book.group(1);
        return TRAILING_PUNCTUATION.matcher(PET_LEVEL_PREFIX.matcher(name).replaceFirst("")).replaceAll("").trim();
    }

    private static final String ROMAN = "IVXLC";
    private static final Pattern ENCHANT = Pattern.compile("^(?<name>.+?) (?<level>[IVXLC]+|\\d+)$");

    /**
     * One of the item's value, trying every id it could have: by its name in the Hypixel item list and the NEU repo, as
     * an enchanted book ("Chimera I" is ENCHANTMENT_ULTIMATE_CHIMERA_1 or NEU's ULTIMATE_CHIMERA;1), as a pet in the
     * rarity its chat colour shows ("BABY_YETI;4"), and its name as an id. 0 if none of them has a price.
     */
    private static double bigDropPrice(String item, int colour) {
        double price = ItemPriceResolver.valueByName(item);
        if (price > 0) return price;
        List<String> ids = new ArrayList<>();
        String repo = com.epic60869.sky2m.custom.RepoItems.idByName(item);
        if (repo != null) ids.add(repo);
        String base = item.toUpperCase(Locale.ROOT).replace('-', '_').replaceAll("[^A-Z0-9_ ]", "").trim().replace(' ', '_');
        Matcher enchant = ENCHANT.matcher(item);
        if (enchant.matches()) {
            int level = level(enchant.group("level"));
            String name = enchant.group("name").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").trim().replace(' ', '_');
            for (String n : List.of("ULTIMATE_" + name, name)) {
                ids.add("ENCHANTMENT_" + n + "_" + level);
                ids.add(n + ";" + level);
            }
        }
        int tier = petTier(colour);
        if (tier >= 0) ids.add(base + ";" + tier);
        ids.add(base);
        for (String id : ids) {
            double value = ItemPriceResolver.value(id);
            if (value <= 0) value = Sky2MPriceTooltip.unitPrice(id);
            if (value > 0) return value;
        }
        return 0;
    }

    private static int level(String level) {
        if (level.chars().allMatch(Character::isDigit)) return Integer.parseInt(level);
        int[] values = {1, 5, 10, 50, 100};
        int total = 0;
        for (int i = 0; i < level.length(); i++) {
            int v = values[ROMAN.indexOf(level.charAt(i))];
            int next = i + 1 < level.length() ? values[ROMAN.indexOf(level.charAt(i + 1))] : 0;
            total += v < next ? -v : v;
        }
        return total;
    }

    /** A pet's rarity from its name colour (white common ... pink mythic), as NEU's ";tier" number; -1 if not a rarity colour. */
    private static int petTier(int colour) {
        return switch (colour & 0xFFFFFF) {
            case 0xFFFFFF -> 0;
            case 0x55FF55 -> 1;
            case 0x5555FF -> 2;
            case 0xAA00AA -> 3;
            case 0xFFAA00 -> 4;
            case 0xFF55FF -> 5;
            default -> -1;
        };
    }

    private static boolean isFarmingDrop(String item) {
        String lower = item.toLowerCase(Locale.ROOT);
        for (String known : DROPS) if (lower.contains(known.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** The colour of the item's name in the chat message (its rarity colour), white if it can't be found. */
    private static int itemColour(Component message, String item) {
        String first = item.split(" ")[0];
        Matcher legacy = Pattern.compile("§([0-9a-f])(?:§[k-or])*" + Pattern.quote(first)).matcher(message.getString());
        if (legacy.find()) {
            var rgb = net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.getByCode(legacy.group(1).charAt(0)));
            if (rgb != null) return 0xFF000000 | rgb.getValue();
        }
        int[] found = {-1};
        message.visit((style, text) -> {
            if (found[0] == -1 && text.contains(first) && style.getColor() != null) found[0] = 0xFF000000 | style.getColor().getValue();
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found[0] == -1 ? 0xFFFFFFFF : found[0];
    }

    private static final Pattern LEGACY_CODE = Pattern.compile("§[0-9a-fk-or]");
    private static final Pattern DROP_WORD = Pattern.compile("(?i)RARE CROP|RARE DROP|PET DROP|PRAY TO RNGESUS|RNGESUS INCARNATE|you (?:found|dropped|got|received)");

    /**
     * Someone's chat message ("Guild > [MVP+] Name: RARE CROP! Crystalized Moonlight", party, co-op, DMs, Sky2M chat):
     * a "Name:" comes before the drop. Hypixel's own drop lines never have one, so only your drops are shown.
     */
    private static boolean fromAPlayer(String raw) {
        Matcher drop = DROP_WORD.matcher(raw);
        int at = drop.find() ? drop.start() : raw.length();
        return raw.substring(0, at).contains(":");
    }

    private void handle(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String raw = LEGACY_CODE.matcher(message.getString()).replaceAll("").trim();
        if (fromAPlayer(raw)) return;
        if (handleSlug(message, raw)) return;
        Parsed parsed = parse(raw);
        if (parsed == null) return;

        String key = parsed.name().toLowerCase(Locale.ROOT);
        long shownUntil = System.currentTimeMillis() + SHOW_MILLIS;
        Drop existing = activeDrops.get(key);
        int newAmount = (existing == null ? 0 : existing.amount()) + parsed.amount();

        if ("Seasoning".equalsIgnoreCase(parsed.name())) {
            activeDrops.put(key, new Drop(newAmount, parsed.name(), rarity(raw), -1L, shownUntil, -1));
            return;
        }

        // Keep the entry immediately so the 3-second timer starts on the actual drop.
        // The price is filled asynchronously without losing any drops that arrive meanwhile.
        if (existing == null) {
            activeDrops.putIfAbsent(key, new Drop(newAmount, parsed.name(), rarity(raw), 0L, shownUntil, -1));
        } else {
            activeDrops.computeIfPresent(key, (ignored, current) ->
                new Drop(current.amount() + parsed.amount(), current.name(), current.rarity(), current.unitPrice(), shownUntil, current.colour()));
        }

        ItemPriceResolver.valueByNameAsync(parsed.name()).thenAccept(price ->
            activeDrops.computeIfPresent(key, (ignored, current) ->
                new Drop(current.amount(), current.name(), current.rarity(), Math.round(price), current.shownUntil(), current.colour()))
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

    /** {@code colour} is the item's chat colour for big drops, or -1 for farming drops (they use the HUD's colours). */
    public record Drop(int amount, String name, String rarity, long unitPrice, long shownUntil, int colour) {}
    private record Parsed(int amount, String name) {}
}
