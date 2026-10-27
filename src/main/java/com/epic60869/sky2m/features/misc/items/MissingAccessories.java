package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.itemlist.ItemListData;
import com.epic60869.sky2m.features.profileviewer.ProfileApi;
import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.price.PriceService;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 mp: the accessories you're missing, cheapest Magical Power first.
 *
 * <p>Which accessories exist and how they group into families (Talisman, Ring, Artifact, Relic) comes from the same
 * accessory list as the Accessory tooltip ({@link AccessoryTooltip#ACCESSORY_DATA}); rarity, soulbound and tradeable
 * flags from Hypixel's item list ({@link PriceService#items()}). Both are turned into one cached {@link Universe}.
 *
 * <p>What you own is merged from two sources, never losing either: your SkyBlock profile from the API (Accessory Bag,
 * inventory, Ender Chest and backpacks; loaded the first time you run /s2 mp, then refreshed every few minutes) and
 * what the game shows you right now (the Accessory Bag pages read by {@link AccessoryTooltip}, your inventory, your
 * saved Ender Chest and backpack pages, and accessories seen in your inventory since the last API refresh). A family
 * counts by its best tier you own; a family you're missing shows the tier that adds Magical Power most cheaply.
 *
 * <p>Nothing runs for a profile until /s2 mp has been used on it once. The API loads, NBT decoding, price lookups and
 * sorting run on one background thread; the screen only reads the last finished, immutable {@link View}.
 */
public final class MissingAccessories {
    /** Accessories that never go in the bag (the Rift Prism is consumed for its Magical Power). */
    private static final Set<String> EXCLUDED = Set.of("RIFT_PRISM");
    /** Magical Power by rarity (Hypixel's values; Divine has no accessories of its own, only recombobulated Mythics). */
    private static final Map<String, Integer> MAGICAL_POWER = Map.of("COMMON", 3, "UNCOMMON", 5, "RARE", 8, "EPIC", 12,
        "LEGENDARY", 16, "MYTHIC", 22, "DIVINE", 22, "SPECIAL", 3, "VERY_SPECIAL", 5);
    private static final List<String> RARITY_ORDER = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE");
    /** The Hegemony Artifact counts its own Magical Power twice. */
    private static final String HEGEMONY = "HEGEMONY_ARTIFACT";
    /** "Magical Power: 1,234" (Accessory Bag) or "1,234 Magical Power" (Thaumaturgy). */
    private static final Pattern MP_LINE = Pattern.compile("Magical Power: ([\\d,]+)|^([\\d,]+) Magical Power$");
    /** API refreshes: in the background this often, and when /s2 mp opens on data older than {@link #OPEN_REFRESH_MS}. */
    private static final long API_REFRESH_MS = 5 * 60 * 1000L;
    private static final long OPEN_REFRESH_MS = 2 * 60 * 1000L;
    private static final long API_RETRY_MS = 60 * 1000L;
    /** Accessories seen locally that the API still lacks this long after are dropped (sold or moved to a co-op member). */
    private static final long LOCAL_KEEP_MS = 30 * 60 * 1000L;
    private static final Gson GSON = new Gson();

    private static final String PULSE_RING = "PULSE_RING";
    private static final String THUNDER = "THUNDER_IN_A_BOTTLE";
    private static final String RECOMBOBULATOR = "RECOMBOBULATOR_3000";
    /** Charge in one full Thunder in a Bottle ("Charge: 50,000/50,000" in the item repo's THUNDER_IN_A_BOTTLE). */
    private static final long THUNDER_CHARGE = 50_000;
    /** A new Pulse Ring's first step, from the item repo's PULSE_RING ("UNCOMMON", "Reach 150k Charge for +1 Rarity!"). */
    private static final String PULSE_BASE_RARITY = "UNCOMMON";
    private static final long PULSE_FIRST_STEP = 150_000;
    private static final Pattern PULSE_NEXT = Pattern.compile("Reach ([\\d.,]+)\\s*([kKmMbB]?) Charge for \\+1 Rarity");
    private static final Pattern RARITY_LINE = Pattern.compile("\\b(VERY SPECIAL|UNCOMMON|COMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL)\\b.*\\bACCESSORY\\b");
    /**
     * Accessories that can't be had (or don't count), never listed: the item repo's constants/misc.json
     * "ignored_talisman" (NotEnoughUpdates-REPO), downloaded with the rest of the repo files. This copy of it is only
     * used until that download has worked.
     */
    private static final Set<String> IGNORED_FALLBACK = Set.of("GARLIC_FLAVORED_GUMMY_BEAR", "PUNCHCARD_ARTIFACT",
        "RING_OF_BROKEN_LOVE", "DEFECTIVE_MONITOR", "WARDING_TRINKET", "HARMONIOUS_SURGERY_TOOLKIT", "CRUX_TALISMAN_1",
        "CRUX_TALISMAN_2", "CRUX_TALISMAN_3", "CRUX_TALISMAN_4", "CRUX_TALISMAN_5", "CRUX_TALISMAN_6", "CRUX_TALISMAN_7",
        "BINGO_COMBAT_TALISMAN", "COMBO_MANIA_TALISMAN", "SATELITE", "RING_OF_ETERNAL_LOVE", "BINGO_HEIRLOOM", "LUCK_TALISMAN",
        "TALISMAN_OF_SPACE", "RING_OF_SPACE", "MASTER_SKULL_TIER_8", "MASTER_SKULL_TIER_9", "MASTER_SKULL_TIER_10",
        "COMPASS_TALISMAN", "ARTIFACT_OF_SPACE", "GRIZZLY_PAW", "ETERNAL_CRYSTAL", "OLD_BOOT", "BINGO_RIFT_TALISMAN");
    private static volatile Set<String> ignored = IGNORED_FALLBACK;

    /** Everything runs here, off the render thread: one at a time, so rebuilds never overlap. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Sky2M Missing Accessories");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });

    /** One accessory: its family (null when it has none) and tier within it. */
    private record Acc(String id, String family, int tier) {}

    /** The accessory list, grouped into families; built once per accessory list and item list download. */
    private record Universe(Map<String, Acc> byId, Map<String, List<Acc>> groups, Object dataFor, Object itemsFor) {}

    /**
     * One row of the list. {@code gain} is the Magical Power it adds (over {@code from}, the tier of its family you own,
     * when it's an upgrade); {@code price} 0 when unknown; {@code source} "AH", "Bazaar", "Craft", "Forge" or "NPC";
     * {@code reason} why it's in the bottom section ("Soulbound", "Co-op Soulbound", "Not tradeable", "No price"), null
     * when it's in the main list.
     */
    public record Row(String id, String name, String rarity, int gain, double price, String source, String from, String reason,
                      Kind kind, String note, List<String> members, String buyName) {
        public double coinsPerMp() {
            return price > 0 && gain > 0 ? price / gain : Double.POSITIVE_INFINITY;
        }
    }

    /**
     * What a row is: a missing accessory ({@code ITEM}), the next tier of a family you own ({@code UPGRADE}), a Pulse
     * Ring charged up a rarity ({@code RARITY}), or recombobulating your accessories of one rarity ({@code RECOMB}).
     * {@code note} says how it's paid for ("after selling Witch's Talisman", "3x Thunder in a Bottle"); {@code members}
     * the accessories a Recombobulate row covers; {@code buyName} the item to search the Auction House or Bazaar for.
     */
    public enum Kind { ITEM, UPGRADE, RARITY, RECOMB }

    /** A Pulse Ring's state: its rarity as shown, its charge, and the charge its next rarity needs (0: none, or unknown). */
    private record Pulse(String rarity, long charge, long next, long at) {}

    /** A way to pay: total coins, where the price came from, a note on the row, and what to search for. */
    private record Price(double cost, String source, String note, String buyName) {}

    /**
     * What /s2 mp shows, all worked out in the background. {@code main} is buyable accessories cheapest coins per
     * Magical Power first, also sorted {@code byPrice} and {@code byGain}; {@code bottom} soulbound, untradeable or
     * unpriced ones. {@code magicalPower} is the last reading (bag menu or API) moved on by what you've got since, or
     * without one worked out from what you own ({@code estimated}); -1 before anything is known. {@code cheapestCoins}
     * and {@code cheapestMp}: the first {@code cheapestCount} (up to 10) of {@code main} together. {@code status}: a
     * line about loading or a problem, "" when there's nothing to say.
     */
    public record View(List<Row> main, List<Row> byPrice, List<Row> byGain, List<Row> bottom, boolean ready, boolean known,
                       int owned, int magicalPower, boolean estimated, int cheapestCount, double cheapestCoins, int cheapestMp,
                       boolean loading, String status) {
        static final View EMPTY = new View(List.of(), List.of(), List.of(), List.of(), false, false, 0, -1, true, 0, 0, 0, false, "");
    }

    /** A Magical Power reading (bag menu or API), and what the bag's contents were worth by our count at the time. */
    private record Reading(int magicalPower, int bagEstimate, long at) {}

    /** The last API load of a profile: everything it holds, its Accessory Bag, recombobulated accessories, its Magical Power. */
    private record ApiData(Set<String> owned, Set<String> bag, Set<String> recombobulated, int magicalPower, Pulse pulse, long at) {}

    /** Per profile (all touched on {@link #WORKER} except where noted, and saved to missing-accessories.json). */
    private static final Set<String> TRACKED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Reading> READINGS = new ConcurrentHashMap<>();
    private static final Map<String, ApiData> API = new ConcurrentHashMap<>();
    /** Accessories seen in your inventory, by profile: id -> when, until the API has them too. */
    private static final Map<String, Map<String, Long>> SEEN = new ConcurrentHashMap<>();
    /** The last Pulse Ring seen in the game (bag menu or inventory), by profile. */
    private static final Map<String, Pulse> PULSE = new ConcurrentHashMap<>();

    private static Path file;
    private static volatile Universe universe;
    private static volatile View current = View.EMPTY;
    /** The last view's inputs; a new build starts when they change. Only touched on the client thread. */
    private static String builtKey = "";
    private static long builtAt;
    private static final AtomicBoolean BUILDING = new AtomicBoolean();
    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static volatile long lastFetchTry;
    private static volatile String apiError = "";
    /** The profile the API said is selected, for while Hypixel hasn't sent the profile id in chat yet. */
    private static volatile String apiProfile = "";

    /** Client thread only: the inventory's accessory ids, and a cheap fingerprint to tell when they change. */
    private static volatile Set<String> inventoryIds = Set.of();
    private static long inventoryPrint;
    /** Client thread only: accessory ids on the saved Ender Chest and backpack pages, refreshed every 10 seconds. */
    private static volatile Set<String> storageIds = Set.of();
    /** Client thread only: recombobulated accessories in the inventory. */
    private static volatile Set<String> inventoryRecomb = Set.of();
    /** Goes up when the inventory's accessories, a reading or an API load change. */
    private static volatile int changes;
    private static int tick;

    private MissingAccessories() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("missing-accessories.json");
        WORKER.execute(MissingAccessories::load);
        RepoItems.runAsync(MissingAccessories::loadIgnored);
        ClientTickEvents.END_CLIENT_TICK.register(MissingAccessories::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ContainerScreen container) || !tracking()) return;
            if (!container.getTitle().getString().startsWith("Accessory Bag")) return;
            int[] ticks = {0};
            ScreenEvents.afterTick(screen).register(s -> {
                if (ticks[0]++ % 10 == 0) readMagicalPower(container.getMenu());
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("mp").executes(c -> {
                    open();
                    return 1;
                })));
            }
        });
    }

    public static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null || config.inventory.missingAccessories.enabled;
    }

    private static FeatureConfigs.MissingAccessories config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? new FeatureConfigs.MissingAccessories() : config.inventory.missingAccessories;
    }

    /** The current profile's id: from Hypixel's chat line, else the one the API says is selected; "" if neither. */
    private static String profile() {
        String id = AccessoryTooltip.currentProfile();
        return id.isEmpty() ? apiProfile : id;
    }

    /** Whether /s2 mp has been used on this profile, so it's kept up to date. */
    public static boolean tracking() {
        String profile = profile();
        return enabled() && Compat.isOnSkyblock() && !profile.isEmpty() && TRACKED.contains(profile);
    }

    /** Opens the list (/s2 mp, and the settings' Open button), and starts tracking this profile. */
    public static void open() {
        if (!enabled()) {
            Sky2MAlerts.chat(Component.literal("Missing Accessories is off. Turn it on in /s2 under Inventory > Items & Tooltips.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        startTracking();
        Compat.queueOpenScreen(new MissingAccessoriesScreen());
    }

    /** Marks this profile as tracked and loads it from the API if that's more than two minutes old. */
    public static void startTracking() {
        ItemListData.ensureLoaded();
        String profile = profile();
        if (!profile.isEmpty() && TRACKED.add(profile)) {
            changes++;
            WORKER.execute(MissingAccessories::save);
        }
        ApiData api = profile.isEmpty() ? null : API.get(profile);
        if (api == null || System.currentTimeMillis() - api.at() > OPEN_REFRESH_MS) fetch(true);
        builtKey = "";
    }

    /** The last finished list (never blocks; may be a little behind while a rebuild runs). */
    public static View view() {
        return current;
    }

    /** Whether upgrades of families you already have are listed (the setting, also the screen's Upgrades button). */
    public static boolean includeUpgrades() {
        return config().includeUpgrades;
    }

    public static void setIncludeUpgrades(boolean include) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null) return;
        config.inventory.missingAccessories.includeUpgrades = include;
        Sky2MConfig.saveCurrent(config);
    }

    /** Whether the accessory list and Hypixel's item list have both loaded. */
    public static boolean dataLoaded() {
        return !AccessoryTooltip.ACCESSORY_DATA.isEmpty() && PriceService.itemsLoaded();
    }

    // ---------------------------------------------------------------- client thread: cheap checks

    private static void tick(Minecraft mc) {
        if (++tick % 10 != 0 || mc.player == null || !tracking()) return;
        Universe u = universe;
        if (u != null) scanInventory(mc.player.getInventory(), u);
        if (tick % 200 == 0) refreshStorage(u);
        long now = System.currentTimeMillis();
        ApiData api = API.get(profile());
        if ((api == null || now - api.at() > API_REFRESH_MS) && now - lastFetchTry > API_RETRY_MS) fetch(false);
        requestBuild(false);
    }

    /** Compares only accessory ids, by a fingerprint, so nothing is allocated unless they changed. */
    private static void scanInventory(Inventory inventory, Universe u) {
        long print = 0;
        int size = inventory.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = Compat.neuName(stack);
            if (id.isEmpty() || !u.byId().containsKey(id)) continue;
            print = print * 31 + id.hashCode() + 1;
            // The Pulse Ring changes charge (and rarity) in place.
            if (PULSE_RING.equals(id)) print = print * 31 + Compat.customDataView(stack).getLongOr("thunder_charge", 0);
        }
        if (print == inventoryPrint) return;
        inventoryPrint = print;
        Set<String> ids = new HashSet<>(), recomb = new HashSet<>();
        String profile = profile();
        long now = System.currentTimeMillis();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            String id = Compat.neuName(stack);
            if (id.isEmpty() || !u.byId().containsKey(id)) continue;
            ids.add(id);
            if (Compat.customDataView(stack).getIntOr("rarity_upgrades", 0) > 0) recomb.add(id);
            if (PULSE_RING.equals(id)) seePulse(profile, stack, now);
        }
        inventoryRecomb = Set.copyOf(recomb);
        Set<String> copy = Set.copyOf(ids);
        changes++;
        if (copy.equals(inventoryIds)) return;
        inventoryIds = copy;
        WORKER.execute(() -> {
            Map<String, Long> seen = SEEN.computeIfAbsent(profile, p -> new ConcurrentHashMap<>());
            boolean added = false;
            for (String id : copy) added |= seen.putIfAbsent(id, now) == null;
            if (added) save();
        });
        changes++;
    }

    /** The saved Ender Chest and backpack pages' accessories (their items are decoded and cached by the storage search). */
    private static void refreshStorage(Universe u) {
        if (u == null) return;
        Set<String> ids = new HashSet<>();
        try {
            for (String id : Sky2MStorageSearch.storedItemCounts().keySet()) if (u.byId().containsKey(id)) ids.add(id);
        } catch (Exception ignored) {}
        if (!ids.equals(storageIds)) {
            storageIds = Set.copyOf(ids);
            changes++;
        }
    }

    /** A Pulse Ring's rarity, charge and next step, from its item data and lore. */
    private static void seePulse(String profile, ItemStack stack, long now) {
        if (profile.isEmpty()) return;
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> lines = new ArrayList<>();
        if (lore != null) for (Component line : lore.lines()) lines.add(line.getString());
        Pulse pulse = pulse(Compat.customDataView(stack).getLongOr("thunder_charge", 0), lines, now);
        Pulse old = PULSE.get(profile);
        if (pulse == null || (old != null && old.rarity().equals(pulse.rarity()) && old.charge() == pulse.charge() && old.next() == pulse.next())) return;
        PULSE.put(profile, pulse);
        changes++;
        WORKER.execute(MissingAccessories::save);
    }

    /** A Pulse Ring from its charge and lore lines (with or without colour codes); null if its rarity can't be read. */
    private static Pulse pulse(long charge, List<String> lore, long at) {
        String rarity = null;
        long next = 0;
        for (String raw : lore) {
            String text = ChatFormatting.stripFormatting(raw);
            if (text == null) continue;
            Matcher r = RARITY_LINE.matcher(text);
            if (r.find()) rarity = r.group(1).replace(' ', '_');
            Matcher n = PULSE_NEXT.matcher(text);
            if (n.find()) {
                try {
                    double value = Double.parseDouble(n.group(1).replace(",", ""));
                    value *= switch (n.group(2).toLowerCase(Locale.ROOT)) {
                        case "k" -> 1_000;
                        case "m" -> 1_000_000;
                        case "b" -> 1_000_000_000;
                        default -> 1;
                    };
                    next = Math.round(value);
                } catch (NumberFormatException ignored) {}
            }
        }
        return rarity == null ? null : new Pulse(rarity, charge, next, at);
    }

    /** Reads "Magical Power: 1,234" (and the Pulse Ring) off the bag menu's items; the rest happens on the worker. */
    private static void readMagicalPower(ChestMenu menu) {
        String profile = profile();
        int size = Math.min(menu.getRowCount() * 9, menu.slots.size());
        long now = System.currentTimeMillis();
        for (int i = 0; i < size; i++) {
            ItemStack ring = menu.slots.get(i).getItem();
            if (!ring.isEmpty() && PULSE_RING.equals(Compat.neuName(ring))) seePulse(profile, ring, now);
        }
        for (int i = 0; i < size; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            ItemLore lore = stack.isEmpty() ? null : stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                String text = ChatFormatting.stripFormatting(line.getString());
                if (text == null || !text.contains("Magical Power")) continue;
                Matcher m = MP_LINE.matcher(text.trim());
                if (!m.find()) continue;
                String number = m.group(1) != null ? m.group(1) : m.group(2);
                int mp;
                try {
                    mp = Integer.parseInt(number.replace(",", ""));
                } catch (NumberFormatException e) {
                    continue;
                }
                WORKER.execute(() -> {
                    Universe u = universe();
                    int bagEstimate = estimate(AccessoryTooltip.bagContents(), Set.of(), pulse(profile, API.get(profile)), u);
                    Reading old = READINGS.get(profile);
                    if (old != null && old.magicalPower() == mp && old.bagEstimate() == bagEstimate) return;
                    READINGS.put(profile, new Reading(mp, bagEstimate, System.currentTimeMillis()));
                    changes++;
                    save();
                });
                return;
            }
        }
    }

    /** Starts a background rebuild when the inputs changed (at most every quarter second), or every 10 seconds. */
    private static void requestBuild(boolean force) {
        FeatureConfigs.MissingAccessories c = config();
        String profile = profile();
        String key = profile + "|" + AccessoryTooltip.bagVersion() + "|" + System.identityHashCode(AccessoryTooltip.ACCESSORY_DATA)
            + "|" + System.identityHashCode(ignored)
            + "|" + System.identityHashCode(PriceService.items()) + "|" + PriceService.version() + "|" + changes + "|" + c.includeUpgrades
            + "|" + c.priceSource + "|" + ItemListData.loaded() + "|" + FETCHING.get();
        long now = System.currentTimeMillis();
        if (!force && key.equals(builtKey) && now - builtAt < 10_000) return;
        if (!force && now - builtAt < 250) return;
        if (!BUILDING.compareAndSet(false, true)) return;
        builtKey = key;
        builtAt = now;
        Set<String> inventory = inventoryIds, storage = storageIds, inventoryRecombobulated = inventoryRecomb;
        boolean includeUpgrades = c.includeUpgrades;
        FeatureConfigs.MpPriceSource source = c.priceSource;
        boolean loading = FETCHING.get();
        WORKER.execute(() -> {
            try {
                current = build(profile, inventory, storage, inventoryRecombobulated, includeUpgrades, source, loading);
            } catch (Throwable e) {
                System.err.println("[Sky2M] Missing Accessories couldn't build its list: " + e);
            } finally {
                BUILDING.set(false);
            }
        });
    }

    /** For the screen: rebuild now if anything changed (still in the background). */
    public static void refreshIfChanged() {
        if (enabled()) requestBuild(false);
    }

    // ---------------------------------------------------------------- the API

    /** Loads your profile's accessories from the API, in the background. */
    private static void fetch(boolean now) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !FETCHING.compareAndSet(false, true)) return;
        lastFetchTry = System.currentTimeMillis();
        String name = mc.player.getGameProfile().name();
        String wanted = AccessoryTooltip.currentProfile();
        changes++;
        ProfileApi.playerFresh(name).whenCompleteAsync((player, error) -> {
            try {
                if (player == null || player.profiles().isEmpty()) {
                    apiError = error == null ? "no SkyBlock profiles" : ProfileApi.reason(error);
                    return;
                }
                ProfileApi.Profile profile = player.profiles().getFirst();
                String plainWanted = wanted.replace("-", "");
                for (ProfileApi.Profile p : player.profiles()) {
                    if (!wanted.isEmpty() && p.id().replace("-", "").equalsIgnoreCase(plainWanted)) profile = p;
                }
                String id = wanted.isEmpty() ? profile.id() : wanted;
                if (wanted.isEmpty()) apiProfile = id;
                if (now) TRACKED.add(id);
                ApiData data = read(profile.member());
                apiError = data.bag().isEmpty() && data.owned().isEmpty() ? "your Inventory API is off" : "";
                API.put(id, data);
                // Accessories seen locally that the API has now, or still lacks long after, are done with.
                Map<String, Long> seen = SEEN.get(id);
                if (seen != null) seen.entrySet().removeIf(e -> data.owned().contains(e.getKey()) || data.at() - e.getValue() > LOCAL_KEEP_MS);
                if (data.magicalPower() > 0) {
                    Reading old = READINGS.get(id);
                    if (old == null || old.at() < data.at()) READINGS.put(id, new Reading(data.magicalPower(), estimate(data.bag(), data.recombobulated(), data.pulse(), universe()), data.at()));
                }
                save();
            } catch (Throwable e) {
                apiError = String.valueOf(e.getMessage());
            } finally {
                FETCHING.set(false);
                changes++;
            }
        }, WORKER);
    }

    /** The accessories in a profile member's Accessory Bag, inventory, Ender Chest and backpacks (base64 NBT). */
    private static ApiData read(JsonObject member) {
        JsonObject inv = obj(member, "inventory");
        Set<String> bag = new HashSet<>(), owned = new HashSet<>(), recomb = new HashSet<>();
        Pulse[] pulse = {null};
        long at = System.currentTimeMillis();
        ids(path(inv, "bag_contents.talisman_bag"), bag, recomb, pulse, at);
        owned.addAll(bag);
        ids(obj(inv, "inv_contents"), owned, recomb, pulse, at);
        ids(obj(inv, "ender_chest_contents"), owned, recomb, pulse, at);
        JsonObject backpacks = obj(inv, "backpack_contents");
        if (backpacks != null) for (Map.Entry<String, JsonElement> e : backpacks.entrySet()) {
            if (e.getValue().isJsonObject()) ids(e.getValue().getAsJsonObject(), owned, recomb, pulse, at);
        }
        int mp = (int) num(obj(member, "accessory_bag_storage"), "highest_magical_power");
        return new ApiData(Set.copyOf(owned), Set.copyOf(bag), Set.copyOf(recomb), mp, pulse[0], at);
    }

    /** Item ids (and recombobulated ones) in an inventory blob ({"type": 0, "data": "..."}), without building item stacks. */
    private static void ids(JsonObject blob, Set<String> out, Set<String> recomb, Pulse[] pulse, long at) {
        if (blob == null || !blob.has("data")) return;
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(blob.get("data").getAsString())),
                NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("i");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i).getCompoundOrEmpty("tag");
                CompoundTag extra = tag.getCompoundOrEmpty("ExtraAttributes");
                String id = extra.getStringOr("id", "");
                if (id.isEmpty()) continue;
                out.add(id);
                if (extra.getIntOr("rarity_upgrades", 0) > 0) recomb.add(id);
                if (PULSE_RING.equals(id)) {
                    ListTag lore = tag.getCompoundOrEmpty("display").getListOrEmpty("Lore");
                    List<String> lines = new ArrayList<>();
                    for (int l = 0; l < lore.size(); l++) lines.add(lore.getStringOr(l, ""));
                    Pulse found = pulse(extra.getLongOr("thunder_charge", 0), lines, at);
                    // More than one: the most charged one is the one that counts.
                    if (found != null && (pulse[0] == null || found.charge() > pulse[0].charge())) pulse[0] = found;
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Missing Accessories couldn't read an inventory: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- worker: the list

    /** Every accessory you own on this profile, from all sources. Safe off the client thread. */
    public static Set<String> owned() {
        return owned(profile(), inventoryIds, storageIds, universe());
    }

    private static Set<String> owned(String profile, Set<String> inventory, Set<String> storage, Universe u) {
        Set<String> owned = new HashSet<>();
        ApiData api = API.get(profile);
        if (api != null) owned.addAll(api.owned());
        owned.addAll(AccessoryTooltip.bagContents());
        owned.addAll(inventory);
        owned.addAll(storage);
        Map<String, Long> seen = SEEN.get(profile);
        if (seen != null) owned.addAll(seen.keySet());
        owned.retainAll(u.byId().keySet());
        return owned;
    }

    private static Universe universe() {
        Map<String, AccessoryTooltip.Accessory> data = AccessoryTooltip.ACCESSORY_DATA;
        Map<String, JsonObject> items = PriceService.items();
        Universe u = universe;
        if (u != null && u.dataFor() == data && u.itemsFor() == items) return u;
        Map<String, Acc> byId = new LinkedHashMap<>();
        if (!data.isEmpty()) {
            for (AccessoryTooltip.Accessory a : data.values()) {
                if ("RIFT".equals(a.origin().orElse(""))) continue;
                byId.put(a.id(), new Acc(a.id(), a.family().orElse(null), a.tier()));
            }
        } else {
            // Without the accessory list, every accessory in Hypixel's item list, without families.
            for (JsonObject item : items.values()) {
                String category = string(item, "category");
                String id = string(item, "id");
                if (id != null && ("ACCESSORY".equals(category) || "HATCESSORY".equals(category))) byId.put(id, new Acc(id, null, 0));
            }
        }
        byId.keySet().removeIf(id -> EXCLUDED.contains(id) || "RIFT".equals(string(items.get(id), "origin")));
        Map<String, List<Acc>> groups = new LinkedHashMap<>();
        for (Acc a : byId.values()) groups.computeIfAbsent(a.family() != null ? a.family() : "#" + a.id(), k -> new ArrayList<>()).add(a);
        for (Map.Entry<String, List<Acc>> e : groups.entrySet()) e.setValue(List.copyOf(e.getValue()));
        u = new Universe(Map.copyOf(byId), groups, data, items);
        universe = u;
        return u;
    }

    private static View build(String profile, Set<String> inventory, Set<String> storage, Set<String> inventoryRecombobulated,
                              boolean includeUpgrades, FeatureConfigs.MpPriceSource priceSource, boolean loading) {
        Universe u = universe();
        Map<String, JsonObject> items = PriceService.items();
        Set<String> skip = ignored;
        boolean ready = !u.byId().isEmpty() && !items.isEmpty();
        ApiData api = API.get(profile);
        Set<String> recomb = new HashSet<>(inventoryRecombobulated);
        if (api != null) recomb.addAll(api.recombobulated());
        Pulse pulse = pulse(profile, api);
        Set<String> owned = owned(profile, inventory, storage, u);
        List<Row> main = new ArrayList<>();
        List<Row> bottom = new ArrayList<>();
        // Each family's best accessory you own, for the Recombobulate rows.
        List<String> counted = new ArrayList<>();
        for (List<Acc> members : u.groups().values()) {
            Acc best = null;
            int ownedMp = 0;
            for (Acc a : members) {
                if (!owned.contains(a.id())) continue;
                int mp = ownedMp(a.id(), items, recomb, pulse);
                if (best == null || mp > ownedMp || (mp == ownedMp && a.tier() > best.tier())) best = a;
                ownedMp = Math.max(ownedMp, mp);
            }
            if (best != null) counted.add(best.id());
            if (best != null && (best.family() == null || !includeUpgrades)) continue;
            Set<String> free = new HashSet<>();
            for (Acc a : members) if (owned.contains(a.id())) free.add(a.id());
            Row cheapest = null, next = null;
            int nextTier = Integer.MAX_VALUE;
            for (Acc a : members) {
                if (skip.contains(a.id()) || (best != null && a.tier() <= best.tier())) continue;
                int gain = baseMp(a.id(), items) - ownedMp;
                if (gain <= 0) continue;
                JsonObject item = items.get(a.id());
                Price price = price(a.id(), best == null ? null : best.id(), free, priceSource, items);
                String reason = soulbound(item);
                if (reason == null && price.cost() <= 0) reason = tradeable(item) ? "No price" : "Not tradeable";
                Row row = new Row(a.id(), name(a.id(), item), rarity(item), gain, price.cost(), price.source(),
                    best == null ? null : best.id(), reason, best == null ? Kind.ITEM : Kind.UPGRADE, price.note(), List.of(), price.buyName());
                if (reason == null) {
                    if (cheapest == null || row.coinsPerMp() < cheapest.coinsPerMp()) cheapest = row;
                } else if (a.tier() < nextTier) {
                    next = row;
                    nextTier = a.tier();
                }
            }
            if (cheapest != null) main.add(cheapest);
            else if (next != null) bottom.add(next);
        }
        Row ring = pulseRow(owned, pulse, items, skip);
        if (ring != null) (ring.reason() == null ? main : bottom).add(ring);
        main.addAll(recombRows(counted, owned, recomb, pulse, profile, inventory, api, items, u));

        main.sort(Comparator.comparingDouble(Row::coinsPerMp).thenComparing(Row::name));
        List<Row> byPrice = new ArrayList<>(main);
        byPrice.sort(Comparator.comparingDouble(Row::price).thenComparing(Row::name));
        List<Row> byGain = new ArrayList<>(main);
        byGain.sort(Comparator.comparingInt(Row::gain).reversed().thenComparingDouble(Row::coinsPerMp).thenComparing(Row::name));
        bottom.sort(Comparator.comparingInt(Row::gain).reversed().thenComparing(Row::name));
        int n = Math.min(10, main.size());
        double coins = 0;
        int gain = 0;
        for (int i = 0; i < n; i++) {
            coins += main.get(i).price();
            gain += main.get(i).gain();
        }

        int all = estimate(owned, recomb, pulse, u);
        Reading reading = READINGS.get(profile);
        boolean known = api != null || AccessoryTooltip.bagRead();
        int mp = reading != null ? reading.magicalPower() + (all - reading.bagEstimate()) : known ? all : -1;
        String error = apiError;
        String status = loading && !known ? "Loading your accessories..."
            : !known && !error.isEmpty() ? "Couldn't load your accessories (" + error + "). Open your Accessory Bag (each page) once."
            : !known ? "Open your Accessory Bag (each page) once, or run /s2 mp again to load it."
            : "";
        return new View(List.copyOf(main), List.copyOf(byPrice), List.copyOf(byGain), List.copyOf(bottom), ready, known, owned.size(),
            mp, reading == null, n, coins, gain, loading, status);
    }

    /** The newer of the Pulse Ring last seen in the game and the one in the last API load; null if neither. */
    private static Pulse pulse(String profile, ApiData api) {
        Pulse local = PULSE.get(profile);
        Pulse remote = api == null ? null : api.pulse();
        if (local == null) return remote;
        if (remote == null) return local;
        return local.at() >= remote.at() ? local : remote;
    }

    /**
     * The Pulse Ring charged up to its next rarity: the Thunder in a Bottle (50,000 charge each) still needed to reach
     * the charge its lore asks for. Only the next step is listed: the item repo has only the first step (150k from
     * Uncommon), and the ring's own lore names the next one.
     */
    private static Row pulseRow(Set<String> owned, Pulse pulse, Map<String, JsonObject> items, Set<String> skip) {
        if (!owned.contains(PULSE_RING) || skip.contains(PULSE_RING)) return null;
        String rarity;
        long charge, next;
        if (pulse != null) {
            rarity = pulse.rarity();
            charge = pulse.charge();
            next = pulse.next();
        } else {
            // Never seen it: a ring as the repo has it (Uncommon, uncharged). Wrong only for charged or recombobulated ones.
            rarity = PULSE_BASE_RARITY;
            charge = 0;
            next = PULSE_FIRST_STEP;
        }
        if (next <= 0) return null;
        String target = upgraded(rarity);
        int gain = MAGICAL_POWER.getOrDefault(target, 0) - MAGICAL_POWER.getOrDefault(rarity, 0);
        if (gain <= 0) return null;
        long bottles = Math.max(1, (long) Math.ceil(Math.max(0, next - charge) / (double) THUNDER_CHARGE));
        Price thunder = marketPrice(THUNDER);
        double cost = thunder.cost() * bottles;
        String note = bottles + "x Thunder in a Bottle";
        return new Row(PULSE_RING, "Pulse Ring " + target + " Upgrade", target, gain, cost, thunder.source(), PULSE_RING,
            cost > 0 ? null : "No price", Kind.RARITY, note, List.of(), name(THUNDER, items.get(THUNDER)));
    }

    /**
     * One row per rarity: recombobulating the accessories of that rarity that count (each family's best) and aren't
     * recombobulated yet. Only accessories whose state is known (from the bag, the API or your inventory), that Hypixel
     * lets you recombobulate, and that gain Magical Power from it.
     */
    private static List<Row> recombRows(List<String> counted, Set<String> owned, Set<String> recomb, Pulse pulse, String profile,
                                        Set<String> inventory, ApiData api, Map<String, JsonObject> items, Universe u) {
        Set<String> bag = AccessoryTooltip.bagContents();
        Map<String, List<String>> names = new LinkedHashMap<>();
        Map<String, Integer> gains = new HashMap<>();
        for (String id : counted) {
            if (ignored.contains(id) || recomb.contains(id) || AccessoryTooltip.isRecombobulated(id)) continue;
            boolean known = bag.contains(id) || inventory.contains(id) || (api != null && api.owned().contains(id));
            if (!known || !recombobulatable(id, items.get(id))) continue;
            String rarity = currentRarity(id, items, recomb, pulse);
            int before = MAGICAL_POWER.getOrDefault(rarity, 0), after = MAGICAL_POWER.getOrDefault(upgraded(rarity), 0);
            int gain = (after - before) * (HEGEMONY.equals(id) ? 2 : 1);
            if (gain <= 0) continue;
            names.computeIfAbsent(rarity, r -> new ArrayList<>()).add(name(id, items.get(id)));
            gains.merge(rarity, gain, Integer::sum);
        }
        Price recombobulator = marketPrice(RECOMBOBULATOR);
        List<Row> rows = new ArrayList<>();
        if (recombobulator.cost() <= 0) return rows;
        for (Map.Entry<String, List<String>> e : names.entrySet()) {
            List<String> list = new ArrayList<>(e.getValue());
            list.sort(null);
            int count = list.size();
            rows.add(new Row(RECOMBOBULATOR, "Recombobulate " + count + " " + e.getKey().replace('_', ' ') + " accessor" + (count == 1 ? "y" : "ies"),
                e.getKey(), gains.get(e.getKey()), recombobulator.cost() * count, recombobulator.source(), null, null, Kind.RECOMB,
                count + "x Recombobulator 3000", List.copyOf(list), name(RECOMBOBULATOR, items.get(RECOMBOBULATOR))));
        }
        return rows;
    }

    /** Whether Hypixel lets you recombobulate it (the accessory list's and the item list's flags). */
    private static boolean recombobulatable(String id, JsonObject item) {
        AccessoryTooltip.Accessory a = AccessoryTooltip.ACCESSORY_DATA.get(id);
        if (a != null && !a.recombobulatable()) return false;
        return item == null || bool(item, "can_recombobulate", true);
    }

    /** What these accessories are worth in Magical Power by our count: each family's best one. */
    private static int estimate(Set<String> owned, Set<String> recomb, Pulse pulse, Universe u) {
        Map<String, JsonObject> items = PriceService.items();
        int total = 0;
        for (List<Acc> members : u.groups().values()) {
            int best = 0;
            for (Acc a : members) if (owned.contains(a.id())) best = Math.max(best, ownedMp(a.id(), items, recomb, pulse));
            total += best;
        }
        return total;
    }

    // ---------------------------------------------------------------- item facts

    private static int baseMp(String id, Map<String, JsonObject> items) {
        int mp = MAGICAL_POWER.getOrDefault(rarity(items.get(id)), 0);
        return HEGEMONY.equals(id) ? mp * 2 : mp;
    }

    /** An owned accessory's Magical Power: a recombobulated one is a rarity higher; the Pulse Ring as last seen. */
    private static int ownedMp(String id, Map<String, JsonObject> items, Set<String> recomb, Pulse pulse) {
        int mp = MAGICAL_POWER.getOrDefault(currentRarity(id, items, recomb, pulse), 0);
        return HEGEMONY.equals(id) ? mp * 2 : mp;
    }

    private static String currentRarity(String id, Map<String, JsonObject> items, Set<String> recomb, Pulse pulse) {
        if (PULSE_RING.equals(id) && pulse != null) return pulse.rarity();
        String rarity = rarity(items.get(id));
        if (recomb.contains(id) || AccessoryTooltip.isRecombobulated(id)) rarity = upgraded(rarity);
        return rarity;
    }

    private static String upgraded(String rarity) {
        if ("SPECIAL".equals(rarity)) return "VERY_SPECIAL";
        int i = RARITY_ORDER.indexOf(rarity);
        return i >= 0 && i + 1 < RARITY_ORDER.size() ? RARITY_ORDER.get(i + 1) : rarity;
    }

    /** Hypixel's rarity for the item ("COMMON" when the list leaves it out, "DIVINE" for its old name "SUPREME"). */
    private static String rarity(JsonObject item) {
        String tier = string(item, "tier");
        if (tier == null) return "COMMON";
        tier = tier.toUpperCase(Locale.ROOT).replace(' ', '_');
        return "SUPREME".equals(tier) ? "DIVINE" : tier;
    }

    private static String soulbound(JsonObject item) {
        String soulbound = string(item, "soulbound");
        if (soulbound == null) return null;
        return "COOP".equalsIgnoreCase(soulbound) ? "Co-op Soulbound" : "Soulbound";
    }

    private static boolean tradeable(JsonObject item) {
        return item == null || (bool(item, "can_auction", true) && bool(item, "can_trade", true));
    }

    private static String name(String id, JsonObject item) {
        String name = string(item, "name");
        if (name == null) name = RepoItems.displayName(id);
        if (name == null) return id;
        String plain = ChatFormatting.stripFormatting(name);
        return plain == null ? id : plain.trim();
    }

    /** Buying one: the lower of its lowest BIN ("AH") and Bazaar insta-buy ("Bazaar"); cost 0 when neither is known. */
    private static Price marketPrice(String id) {
        double best = 0;
        String source = "";
        Double bin = Sky2MPriceTooltip.lowestBinPrice(id);
        if (bin != null && bin > 0) {
            best = bin;
            source = "AH";
        }
        Double bazaar = Sky2MPriceTooltip.bazaarBuyPrice(id);
        if (bazaar != null && bazaar > 0 && (best == 0 || bazaar < best)) {
            best = bazaar;
            source = "Bazaar";
        }
        return new Price(best, source, "", null);
    }

    /** What an owned accessory sells for: Bazaar insta-sell if it's sold there, else its lowest BIN; 0 if neither. */
    private static double sellValue(String id) {
        Double sell = Sky2MPriceTooltip.bazaarSellPrice(id);
        if (sell != null && sell > 0) return sell;
        Double bin = Sky2MPriceTooltip.lowestBinPrice(id);
        return bin != null && bin > 0 ? bin : 0;
    }

    /**
     * The cheapest way to get {@code id}. Buying it (lowest BIN or Bazaar insta-buy), less what {@code sold} (the lower
     * tier of the family you own) sells for when it's an upgrade; or, with Cheapest, crafting it (or an NPC shop)
     * without the ingredients you already have, which is how an upgrade usually uses up the lower tier.
     */
    private static Price price(String id, String sold, Set<String> free, FeatureConfigs.MpPriceSource priceSource, Map<String, JsonObject> items) {
        String name = name(id, items.get(id));
        Price buy = marketPrice(id);
        Price best = null;
        if (buy.cost() > 0) {
            double back = sold == null ? 0 : sellValue(sold);
            if (back > 0) {
                // Never free: a lower tier worth more than the upgrade still costs a coin, so it sorts first.
                best = new Price(Math.max(1, buy.cost() - back), buy.source(), "after selling " + name(sold, items.get(sold)), name);
            } else {
                best = new Price(buy.cost(), buy.source(), "", name);
            }
        }
        if (priceSource == FeatureConfigs.MpPriceSource.CHEAPEST) {
            String[] kind = {""};
            double craft = Sky2MPriceTooltip.craftCost(id, free, kind);
            if (craft > 0 && (best == null || craft < best.cost())) {
                String source = switch (kind[0]) {
                    case "NPC Shop" -> "NPC";
                    case "Forge" -> "Forge";
                    default -> "Craft";
                };
                best = new Price(craft, source, "", name);
            }
        }
        return best == null ? new Price(0, "", "", name) : best;
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** {@code o.a.b} for "a.b", or null. */
    private static JsonObject path(JsonObject o, String path) {
        for (String key : path.split("\\.")) o = obj(o, key);
        return o;
    }

    private static double num(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String string(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? fallback : e.getAsBoolean();
    }

    /** Opens what a row needs: the Auction House search, the Bazaar, or the recipe. */
    public static void act(Row row) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        String item = row.buyName() == null ? row.name() : row.buyName();
        String command = switch (row.source()) {
            case "AH" -> "ahs " + item;
            case "Bazaar" -> "bz " + item;
            default -> Compat.COMMAND_ROOTS[0] + " recipe " + item;
        };
        mc.gui.setScreen(null);
        mc.getConnection().sendCommand(command);
    }

    /** The item repo's constants/misc.json "ignored_talisman" (cached on disk by the repo download); the bundled copy if that fails. */
    private static void loadIgnored() {
        try {
            JsonObject misc = JsonParser.parseString(RepoItems.neuRepoFile("constants/misc.json")).getAsJsonObject();
            if (!misc.has("ignored_talisman") || !misc.get("ignored_talisman").isJsonArray()) return;
            Set<String> ids = new HashSet<>();
            for (JsonElement e : misc.getAsJsonArray("ignored_talisman")) ids.add(e.getAsString());
            if (!ids.isEmpty()) {
                ignored = Set.copyOf(ids);
                changes++;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Missing Accessories couldn't load the ignored accessories list: " + e.getMessage());
        }
    }

    /** Whether an accessory is never listed (can't be had); for the game test. */
    public static boolean isIgnored(String id) {
        return ignored.contains(id);
    }

    // ---------------------------------------------------------------- saving (worker thread)

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                String profile = e.getKey();
                JsonObject o = e.getValue().getAsJsonObject();
                if (o.has("tracked") && o.get("tracked").getAsBoolean()) TRACKED.add(profile);
                if (o.has("magicalPower")) READINGS.put(profile, new Reading(o.get("magicalPower").getAsInt(),
                    o.has("bagEstimate") ? o.get("bagEstimate").getAsInt() : 0, o.has("at") ? o.get("at").getAsLong() : 0));
                if (o.has("api") && o.get("api").isJsonObject()) {
                    JsonObject a = o.getAsJsonObject("api");
                    API.put(profile, new ApiData(strings(a, "owned"), strings(a, "bag"), strings(a, "recombobulated"),
                        a.has("magicalPower") ? a.get("magicalPower").getAsInt() : 0, readPulse(a), a.has("at") ? a.get("at").getAsLong() : 0));
                }
                Pulse pulse = readPulse(o);
                if (pulse != null) PULSE.put(profile, pulse);
                if (o.has("seen") && o.get("seen").isJsonObject()) {
                    Map<String, Long> seen = new ConcurrentHashMap<>();
                    for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("seen").entrySet()) seen.put(s.getKey(), s.getValue().getAsLong());
                    SEEN.put(profile, seen);
                }
            }
            changes++;
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read missing-accessories.json: " + e.getMessage());
        }
    }

    private static Pulse readPulse(JsonObject o) {
        JsonObject p = obj(o, "pulseRing");
        if (p == null || !p.has("rarity")) return null;
        return new Pulse(p.get("rarity").getAsString(), (long) num(p, "charge"), (long) num(p, "next"), (long) num(p, "at"));
    }

    private static JsonObject writePulse(Pulse pulse) {
        JsonObject p = new JsonObject();
        p.addProperty("rarity", pulse.rarity());
        p.addProperty("charge", pulse.charge());
        p.addProperty("next", pulse.next());
        p.addProperty("at", pulse.at());
        return p;
    }

    private static Set<String> strings(JsonObject o, String key) {
        Set<String> out = new HashSet<>();
        if (o.has(key) && o.get(key).isJsonArray()) for (JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsString());
        return Set.copyOf(out);
    }

    private static JsonArray array(Set<String> ids) {
        JsonArray array = new JsonArray();
        ids.stream().sorted().forEach(array::add);
        return array;
    }

    private static void save() {
        if (file == null) return;
        Set<String> profiles = new HashSet<>(TRACKED);
        profiles.addAll(READINGS.keySet());
        profiles.addAll(API.keySet());
        profiles.addAll(SEEN.keySet());
        profiles.addAll(PULSE.keySet());
        JsonObject root = new JsonObject();
        for (String profile : profiles) {
            JsonObject o = new JsonObject();
            o.addProperty("tracked", TRACKED.contains(profile));
            Reading r = READINGS.get(profile);
            if (r != null) {
                o.addProperty("magicalPower", r.magicalPower());
                o.addProperty("bagEstimate", r.bagEstimate());
                o.addProperty("at", r.at());
            }
            ApiData a = API.get(profile);
            if (a != null) {
                JsonObject api = new JsonObject();
                api.add("owned", array(a.owned()));
                api.add("bag", array(a.bag()));
                api.add("recombobulated", array(a.recombobulated()));
                api.addProperty("magicalPower", a.magicalPower());
                api.addProperty("at", a.at());
                if (a.pulse() != null) api.add("pulseRing", writePulse(a.pulse()));
                o.add("api", api);
            }
            Pulse pulse = PULSE.get(profile);
            if (pulse != null) o.add("pulseRing", writePulse(pulse));
            Map<String, Long> seen = SEEN.get(profile);
            if (seen != null && !seen.isEmpty()) {
                JsonObject s = new JsonObject();
                new HashMap<>(seen).forEach(s::addProperty);
                o.add("seen", s);
            }
            root.add(profile, o);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save missing-accessories.json: " + e.getMessage());
        }
    }
}
