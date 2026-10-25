package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MItemRarity;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.hunting.AttributeShards;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
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
 * Item Pickup Log, ported from SkyHanni's ItemPickupLog (hannibal002/SkyHanni, LGPL-2.1): what goes into and out of
 * your inventory, for a few seconds after it happens, as "+64 [icon] Enchanted Diamond" with the item's name in its
 * rarity colour. Optionally also what your sacks gain and lose (from Hypixel's [Sacks] messages), shards you get, and
 * coins in your purse, with the coin value of what's shown on top. Like SkyHanni: items moving around inside your
 * inventory don't count (only the total of each item is compared), what changes while a menu is open is counted once
 * it closes, nothing is logged for 2 seconds after changing worlds, and an item's + and - lines expire together.
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

    /** SkyHanni's shop pattern: "Mite Gel x33" in a shop is the same item as "Mite Gel". */
    private static final Pattern SHOP_AMOUNT = Pattern.compile("^(?<name>.+?)(?: x\\d+)?$");
    private static final Pattern PET_LEVEL = Pattern.compile("^\\[Lvl \\d+] ");
    private static final List<String> PET_TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    private static final String COINS = "coins";
    /** No changes are logged this long after a world change, like SkyHanni (the inventory is still loading). */
    private static final long WORLD_CHANGE_COOLDOWN = 2000;

    /** What came in (or went out) of one item: its coloured name, icon, price id, amount and when it last changed. */
    private static final class Entry {
        String name;
        ItemStack icon;
        String priceId;
        long amount;
        long changedAt;
    }

    /** SkyHanni keeps what came in and what went out apart, each in the order it first happened. */
    private static final Map<String, Entry> ADDED = new LinkedHashMap<>();
    private static final Map<String, Entry> REMOVED = new LinkedHashMap<>();
    private static Map<String, Integer> lastCounts;
    private static Map<String, ItemStack> lastStacks = Map.of();
    private static Object lastLevel;
    private static long worldChangedAt;
    private static double lastPurse = -1;
    /** Features that want to know when you pick something up, e.g. the Shopping List ticking items off. */
    private static final List<PickupListener> PICKUP_LISTENERS = new ArrayList<>();

    /** One for each feature that reacts to pickups: the item's key/(price) id, its display name and how many came in. */
    public interface PickupListener {
        void onPickup(String key, @org.jspecify.annotations.Nullable String priceId, String name, long delta);
    }

    public static void addPickupListener(PickupListener listener) {
        PICKUP_LISTENERS.add(listener);
    }

    private ItemPickupLog() {}

    private static FeatureConfigs.ItemPickupLog config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.itemPickupLog;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> clientTicks++);
        ClientTickEvents.END_CLIENT_TICK.register(ItemPickupLog::tick);
        SackTracker.onSackChange((name, delta) -> {
            FeatureConfigs.ItemPickupLog c = config();
            if (c == null || !c.enabled || !c.sacks || !afterWorldChange()) return;
            String id = idFromName(name);
            String display = repoName(id, titleCase(name));
            add(key(id, display), display, icon(id, null), id, delta);
        });
        Sky2MChat.onChat(message -> {
            FeatureConfigs.ItemPickupLog c = config();
            if (c == null || !c.enabled || !c.shards) return;
            String text = message.text().trim();
            for (Pattern pattern : SHARD_MESSAGES) {
                Matcher m = pattern.matcher(text);
                if (!m.find()) continue;
                long amount = groupOrNull(m, "amount") == null ? 1 : Long.parseLong(groupOrNull(m, "amount"));
                String plain = m.group("name").trim() + " Shard";
                AttributeShards.Shard shard = AttributeShards.byName(plain);
                String id = shard == null ? null : shard.internalName();
                // The shard's name in its rarity colour (Common white ... Mythic pink).
                String display = (shard == null ? "§f" : shard.rarityColour()) + plain;
                add(id == null ? "shard:" + plain : key(id, display), display,
                    shard == null ? new ItemStack(Items.PRISMARINE_SHARD) : shard.icon(), id, amount);
                return;
            }
        });
        Sky2MHuds.registerCustom("item_pickup_log", "Item Pickup Log", () -> {
            FeatureConfigs.ItemPickupLog c = config();
            return c != null && c.enabled;
        }, new Sky2MHuds.CustomHud() {
            @Override public int width() { return Math.max(60, contentWidth(preview())); }
            @Override public int height() { return boxHeight(lines(preview()).size()); }
            @Override public boolean visible() { return !ADDED.isEmpty() || !REMOVED.isEmpty(); }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean preview) {
                draw(graphics, lines(preview && preview()));
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
        return ADDED.isEmpty() && REMOVED.isEmpty();
    }

    private static boolean afterWorldChange() {
        return System.currentTimeMillis() - worldChangedAt > WORLD_CHANGE_COOLDOWN;
    }

    // ------------------------------------------------------------------------------------------------ tracking

    private static void tick(Minecraft mc) {
        FeatureConfigs.ItemPickupLog c = config();
        long now = System.currentTimeMillis();
        if (c != null) {
            ADDED.values().removeIf(e -> now - e.changedAt > c.expireAfter * 1000L);
            REMOVED.values().removeIf(e -> now - e.changedAt > c.expireAfter * 1000L);
        }
        if (c == null || !c.enabled || mc.player == null || !Compat.isOnSkyblock()) {
            lastCounts = null;
            lastPurse = -1;
            return;
        }
        // A new world (joining, warping): forget the log and start again from what you have now.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            lastCounts = null;
            lastPurse = -1;
            worldChangedAt = now;
            ADDED.clear();
            REMOVED.clear();
        }
        // In a menu (not your own inventory) nothing is compared: menus such as the Experimentation Table move items
        // around as part of their UI, and Hypixel's menu items aren't yours. What changed shows once it closes.
        boolean inMenu = mc.gui.screen() instanceof AbstractContainerScreen<?> && !(mc.gui.screen() instanceof InventoryScreen);
        if (!inMenu) {
            Map<String, Integer> counts = new HashMap<>();
            Map<String, ItemStack> stacks = new HashMap<>();
            Inventory inventory = mc.player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) count(inventory.getItem(i), counts, stacks);
            // The item on your cursor in your inventory is still yours, so moving things around doesn't log anything.
            if (mc.gui.screen() instanceof InventoryScreen screen) {
                ItemStack carried = screen.getMenu().getCarried();
                if (lastCounts != null && !carried.isEmpty() && lastCounts.containsKey(key(carried))) count(carried, counts, stacks);
            }
            if (lastCounts != null && afterWorldChange()) {
                java.util.Set<String> keys = new java.util.HashSet<>(counts.keySet());
                keys.addAll(lastCounts.keySet());
                for (String key : keys) {
                    int delta = counts.getOrDefault(key, 0) - lastCounts.getOrDefault(key, 0);
                    if (delta == 0) continue;
                    ItemStack stack = stacks.getOrDefault(key, lastStacks.get(key));
                    if (stack == null) continue;
                    String marketId = Sky2MPriceTooltip.marketId(stack);
                    add(key, name(stack), stack.copyWithCount(1), marketId.isEmpty() ? null : marketId, delta);
                }
            }
            lastCounts = counts;
            lastStacks = stacks;
        }
        if (c.coins) trackPurse();
        // Compact Lines: an item that came and went in the same amount isn't shown at all (SkyHanni drops it).
        if (c.compactLines) {
            ADDED.entrySet().removeIf(e -> {
                Entry removed = REMOVED.get(e.getKey());
                if (removed == null || removed.amount != e.getValue().amount) return false;
                REMOVED.remove(e.getKey());
                return true;
            });
        }
    }

    /** Items by SkyBlock id and name (SkyHanni's hash), so a renamed or moved stack of the same thing still adds up. */
    private static void count(ItemStack stack, Map<String, Integer> counts, Map<String, ItemStack> stacks) {
        if (stack == null || stack.isEmpty()) return;
        // The SkyBlock menu star and other menu-only items aren't items you have.
        if ("SKYBLOCK_MENU".equals(Compat.neuName(stack))) return;
        String key = key(stack);
        counts.merge(key, stack.getCount(), Integer::sum);
        stacks.putIfAbsent(key, stack);
    }

    private static String key(ItemStack stack) {
        String id = Compat.neuName(stack);
        return key(id.isEmpty() ? stack.getItem().toString() : id, stack.getHoverName().getString());
    }

    /** "ENCHANTED_DIAMOND|enchanted diamond": the same key for an item in your inventory, sacks and shard messages. */
    private static String key(String id, String name) {
        String clean = Sky2MLocation.strip(name).trim();
        Matcher shop = SHOP_AMOUNT.matcher(clean);
        if (shop.matches()) clean = shop.group("name");
        return id + "|" + clean.toLowerCase(Locale.ROOT);
    }

    private static final Pattern PURSE_NUMBER = Pattern.compile("[\\d,]+(?:\\.\\d+)?");

    private static void trackPurse() {
        String value = Sky2MLocation.scoreboardValue("Purse:");
        if (value == null) value = Sky2MLocation.scoreboardValue("Piggy:");
        if (value == null) return;
        Matcher m = PURSE_NUMBER.matcher(value);
        if (!m.find()) return;
        double purse = Double.parseDouble(m.group().replace(",", ""));
        if (lastPurse >= 0 && Math.abs(purse - lastPurse) >= 1 && afterWorldChange()) {
            add(COINS, "§6Coins", icon("COIN_TALISMAN", new ItemStack(Items.GOLD_NUGGET)), null, Math.round(purse - lastPurse));
        }
        lastPurse = purse;
    }

    /** SkyHanni's updateItem: adds to the + or - side, and an update to either side keeps the other one showing too. */
    private static void add(String key, String name, ItemStack icon, String priceId, long delta) {
        if (delta == 0) return;
        long now = System.currentTimeMillis();
        Map<String, Entry> target = delta > 0 ? ADDED : REMOVED;
        Map<String, Entry> opposite = delta > 0 ? REMOVED : ADDED;
        Entry other = opposite.get(key);
        if (other != null) other.changedAt = now;
        Entry e = target.computeIfAbsent(key, k -> new Entry());
        e.name = name;
        e.icon = icon;
        e.priceId = priceId;
        e.amount += Math.abs(delta);
        e.changedAt = now;
        if (delta > 0) {
            for (PickupListener listener : PICKUP_LISTENERS) {
                try {
                    listener.onPickup(key, priceId, name, delta);
                } catch (Exception ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ names

    /**
     * The item's name in its rarity colour, like SkyHanni's dynamicName: pets as "Slug" (without "[Lvl 1]") in the
     * pet's rarity colour, enchanted books as their enchant ("Sharpness VI"), everything else the item's own coloured
     * name. A name with no colour of its own gets the rarity colour from the item list or the item's lore.
     */
    private static String name(ItemStack stack) {
        String id = Compat.neuName(stack);
        String plain = Sky2MLocation.strip(stack.getHoverName().getString()).trim();
        CompoundTag data = Compat.customDataView(stack);
        if (id.equals("PET")) {
            String tier = null;
            try {
                JsonObject pet = JsonParser.parseString(data.getStringOr("petInfo", "{}")).getAsJsonObject();
                if (pet.has("tier")) tier = pet.get("tier").getAsString();
            } catch (Exception ignored) {}
            if (tier == null) tier = loreRarity(stack);
            return RepoItems.tierColour(tier) + PET_LEVEL.matcher(plain).replaceFirst("");
        }
        if (id.equals("ENCHANTED_BOOK")) {
            CompoundTag enchants = data.getCompoundOrEmpty("enchantments");
            if (enchants.size() == 1) {
                for (String enchant : enchants.keySet()) {
                    String book = RepoItems.displayName(enchant.toUpperCase(Locale.ROOT) + ";" + enchants.getIntOr(enchant, 1));
                    if (book != null && !book.isBlank()) return book;
                }
            }
        }
        if (plain.isEmpty()) {
            String repo = id.isEmpty() ? null : RepoItems.displayName(id);
            return repo != null ? repo : "§c?";
        }
        String legacy = com.epic60869.sky2m.features.sbc.SbcItems.legacy(stack.getHoverName());
        if (legacy.startsWith("§") && legacy.length() > 1 && isColour(legacy.charAt(1))) return legacy;
        String tier = id.isEmpty() ? null : RepoItems.tier(id);
        if (tier == null) tier = loreRarity(stack);
        return RepoItems.tierColour(tier) + legacy;
    }

    private static boolean isColour(char code) {
        return "0123456789abcdefABCDEF".indexOf(code) >= 0;
    }

    /** The rarity in the item's last lore lines ("LEGENDARY ACCESSORY"), or null. */
    private static String loreRarity(ItemStack stack) {
        List<String> lore = AttributeShards.lore(stack);
        for (int i = lore.size() - 1, checked = 0; i >= 0 && checked < 3; i--) {
            String line = lore.get(i);
            if (line.isEmpty()) continue;
            checked++;
            if (!line.equals(line.toUpperCase(Locale.ROOT))) continue;
            var rarity = Sky2MItemRarity.containsName(line);
            if (rarity.isPresent() && rarity.get() != Sky2MItemRarity.UNKNOWN) return rarity.get().name();
        }
        return null;
    }

    /** The item list's coloured name for {@code id} (its rarity colour), else {@code fallback} in the id's rarity colour. */
    private static String repoName(String id, String fallback) {
        String repo = RepoItems.displayName(id);
        if (repo != null && !repo.isBlank()) {
            if (repo.startsWith("§")) return repo;
            return RepoItems.tierColour(RepoItems.tier(id)) + repo;
        }
        if (fallback.startsWith("§")) return fallback;
        return RepoItems.tierColour(RepoItems.tier(id)) + fallback;
    }

    private static ItemStack icon(String id, ItemStack fallback) {
        if (id == null) return fallback;
        ItemStack stack = RepoItems.itemStack(id);
        return stack.is(Items.BARRIER) ? fallback : stack;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /** {@code icon} null and {@code text} true is a text-only line (the coin value). */
    private record Line(String amount, int amountColour, ItemStack icon, String name, boolean text) {}

    private static long clientTicks;
    private static long linesTick = -1;
    private static List<Line> tickLines, tickPreviewLines;

    /**
     * {@link #buildLines} once per client tick (separately for the preview): render, width() and contentWidth() each
     * asked for the lines every frame, and building them copies the entries and looks up prices.
     */
    private static List<Line> lines(boolean preview) {
        if (linesTick != clientTicks) {
            tickLines = null;
            tickPreviewLines = null;
            linesTick = clientTicks;
        }
        if (preview) {
            if (tickPreviewLines == null) tickPreviewLines = buildLines(true);
            return tickPreviewLines;
        }
        if (tickLines == null) tickLines = buildLines(false);
        return tickLines;
    }

    private static final int GREEN = 0xFF55FF55, RED = 0xFFFF5555;

    /** SkyHanni's updateDisplay: + lines in the order items came in (with their - line), then items that only went out. */
    private static List<Line> buildLines(boolean preview) {
        FeatureConfigs.ItemPickupLog c = config();
        List<Line> lines = new ArrayList<>();
        if (preview) {
            lines.add(new Line("", 0, null, "§eValue: §61.2m coins", true));
            lines.add(new Line("+64", GREEN, new ItemStack(Items.DIAMOND), "§aEnchanted Diamond", false));
            lines.add(new Line("+1", GREEN, new ItemStack(Items.GOLDEN_SWORD), "§6Hyperion", false));
            lines.add(new Line("-1", RED, new ItemStack(Items.ENDER_PEARL), "§fEnder Pearl", false));
            return lines;
        }
        if (c == null) return lines;
        Map<String, Entry> removedLeft = new LinkedHashMap<>(REMOVED);
        for (Map.Entry<String, Entry> added : ADDED.entrySet()) {
            Entry a = added.getValue();
            Entry r = removedLeft.remove(added.getKey());
            if (c.compactLines && r != null) {
                long net = a.amount - r.amount;
                if (net != 0) lines.add(line(net > 0 ? "+" : "-", Math.abs(net), net > 0 ? GREEN : RED, a, c));
            } else {
                lines.add(line("+", a.amount, GREEN, a, c));
                if (r != null) lines.add(line("-", r.amount, RED, r, c));
            }
        }
        for (Entry r : removedLeft.values()) lines.add(line("-", r.amount, RED, r, c));
        if (c.coinValue.enabled && !lines.isEmpty()) {
            double total = 0;
            for (Entry e : ADDED.values()) total += coinValue(e, c);
            for (Entry e : REMOVED.values()) total -= coinValue(e, c);
            if (total >= c.coinValue.threshold || c.coinValue.threshold == 0) {
                lines.add(0, new Line("", 0, null, "§eValue: " + (total < 0 ? "§c-" : "§6") + compact(Math.abs(total)) + " coins", true));
            }
        }
        return lines;
    }

    private static Line line(String sign, long amount, int colour, Entry e, FeatureConfigs.ItemPickupLog c) {
        return new Line(sign + number(amount, c), colour, e.icon, e.name, false);
    }

    private static double coinValue(Entry e, FeatureConfigs.ItemPickupLog c) {
        if ("§6Coins".equals(e.name) && e.priceId == null) return e.amount;
        if (e.priceId == null) return 0;
        return e.amount * Sky2MPriceTooltip.price(e.priceId, c.coinValue.priceSource);
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

    private static final int LINE_HEIGHT = 11;
    /** SkyHanni draws the log in a 30 pixel tall column and aligns the lines in it, so Bottom grows upwards. */
    private static final int COLUMN_HEIGHT = 30;

    private static FeatureConfigs.ItemPickupLog.Alignment alignment() {
        FeatureConfigs.ItemPickupLog c = config();
        return c == null || c.alignment == null ? FeatureConfigs.ItemPickupLog.Alignment.TOP : c.alignment;
    }

    private static int boxHeight(int lineCount) {
        if (alignment() == FeatureConfigs.ItemPickupLog.Alignment.TOP) return Math.max(10, lineCount * LINE_HEIGHT);
        return COLUMN_HEIGHT;
    }

    private static int contentWidth(boolean preview) {
        Font font = Minecraft.getInstance().font;
        int w = 0;
        for (Line l : lines(preview)) {
            Component name = com.epic60869.sky2m.features.sbc.SbcItems.parseLegacy(l.name());
            w = Math.max(w, (l.text() ? 0 : font.width(l.amount()) + 16) + font.width(name) + 8);
        }
        return w;
    }

    private static void draw(GuiGraphicsExtractor g, List<Line> lines) {
        FeatureConfigs.ItemPickupLog c = config();
        List<FeatureConfigs.ItemPickupLog.Part> layout = c == null || c.layout.isEmpty()
            ? List.of(FeatureConfigs.ItemPickupLog.Part.AMOUNT, FeatureConfigs.ItemPickupLog.Part.ICON, FeatureConfigs.ItemPickupLog.Part.NAME) : c.layout;
        Font font = Minecraft.getInstance().font;
        int contentHeight = lines.size() * LINE_HEIGHT;
        int y = switch (alignment()) {
            case TOP -> 0;
            case CENTER -> (COLUMN_HEIGHT - contentHeight) / 2;
            case BOTTOM -> COLUMN_HEIGHT - contentHeight;
        };
        for (Line line : lines) {
            if (line.text()) {
                g.text(font, com.epic60869.sky2m.features.sbc.SbcItems.parseLegacy(line.name()), 0, y + 1, 0xFFFFFFFF, true);
                y += LINE_HEIGHT;
                continue;
            }
            int x = 0;
            for (FeatureConfigs.ItemPickupLog.Part part : layout) {
                switch (part) {
                    case AMOUNT -> {
                        g.text(font, line.amount(), x, y + 1, line.amountColour(), true);
                        x += font.width(line.amount()) + 3;
                    }
                    case ICON -> {
                        if (line.icon() == null || line.icon().isEmpty()) {
                            // SkyHanni's "?" for an item it can't draw.
                            g.text(font, "?", x, y + 1, RED, true);
                            x += font.width("?") + 3;
                            continue;
                        }
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
            y += LINE_HEIGHT;
        }
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

    /** Resolve sack display names against the actual item repository before falling back to a normalised ID. */
    private static String idFromName(String name) {
        String id = RepoItems.idByLooseName(name);
        return id != null ? id : name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
