package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.ChestHistory;
import com.epic60869.sky2m.features.dungeons.SavedJson;
import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Inventory > Item Origin: when an item with a SkyBlock UUID first turns up in your inventory, remember the date and where,
 * and say so on its tooltip: "Got 6 Nov, M6 chest" when the source is known, "First seen 6 Nov, Hub" when only the place is.
 * <p>
 * A false date is worse than none, so what you already had gets no date: everything in your inventory the first time this
 * runs on a profile, and anything seen in a storage menu (ender chest, backpack, wardrobe, equipment, ...) before it reached
 * your inventory. Saved in config/sky2m/item-origin.json.
 */
public final class ItemOrigin {
    /** Dated items kept; the oldest past this lose their date but stay known, so they can't get a new false one. */
    public static final int MAX_DATED = 5000;
    /** Items known without a date. */
    public static final int MAX_KNOWN = 20000;
    private static final int MENU_EVERY_TICKS = 5, INVENTORY_EVERY_TICKS = 20;
    /** Hypixel can still be sending your inventory just after a world change. */
    private static final long WORLD_CHANGE_WAIT_MS = 5000;
    /** A chest's item counts as from that chest when it turns up this soon after. */
    private static final long CHEST_WINDOW_MS = 120_000;
    /** An item counts as bought or crafted when it turns up this soon after that menu. */
    private static final long MENU_WINDOW_MS = 15_000;
    private static final long SAVE_EVERY_MS = 10_000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** When and where. {@code sure} is true when the source was seen (a chest, the auction house, crafting), not just the place. */
    public record Entry(long time, @Nullable String source, boolean sure) {}

    /** The saved items. Public for the game test. */
    public static final class Store {
        private final Set<String> profiles = new HashSet<>();
        private final Map<String, Entry> dated = new LinkedHashMap<>();
        private final Set<String> known = new LinkedHashSet<>();

        public boolean baselined(String profile) {
            return profiles.contains(profile);
        }

        /** Marks this profile's items as already held, with no date. Items already dated keep their date. */
        public void baseline(String profile, Iterable<String> uuids) {
            profiles.add(profile);
            for (String uuid : uuids) markKnown(uuid);
        }

        public boolean isKnown(String uuid) {
            return dated.containsKey(uuid) || known.contains(uuid);
        }

        /** Remembers an item with no date, unless it's known already. Returns whether it was new. */
        public boolean markKnown(String uuid) {
            if (isKnown(uuid)) return false;
            known.add(uuid);
            trimKnown();
            return true;
        }

        /** Remembers a new item's date. Does nothing and returns false for a known one. */
        public boolean note(String uuid, Entry entry) {
            if (isKnown(uuid)) return false;
            dated.put(uuid, entry);
            while (dated.size() > MAX_DATED) {
                Iterator<String> oldest = dated.keySet().iterator();
                known.add(oldest.next());
                oldest.remove();
            }
            trimKnown();
            return true;
        }

        private void trimKnown() {
            Iterator<String> oldest = known.iterator();
            while (known.size() > MAX_KNOWN) {
                oldest.next();
                oldest.remove();
            }
        }

        public @Nullable Entry get(String uuid) {
            return dated.get(uuid);
        }

        public int size() {
            return dated.size();
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray profileList = new JsonArray();
            profiles.forEach(profileList::add);
            root.add("profiles", profileList);
            JsonObject items = new JsonObject();
            dated.forEach((uuid, entry) -> {
                JsonObject item = new JsonObject();
                item.addProperty("time", entry.time());
                if (entry.source() != null) item.addProperty("source", entry.source());
                if (entry.sure()) item.addProperty("sure", true);
                items.add(uuid, item);
            });
            root.add("items", items);
            JsonArray knownList = new JsonArray();
            known.forEach(knownList::add);
            root.add("known", knownList);
            return new GsonBuilder().create().toJson(root);
        }

        /** Throws on a file that isn't JSON at all; one bad item is skipped so the rest still load. */
        public static Store fromJson(String json) {
            Store store = new Store();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("profiles")) for (JsonElement e : root.getAsJsonArray("profiles")) store.profiles.add(e.getAsString());
            if (root.has("known")) for (JsonElement e : root.getAsJsonArray("known")) store.known.add(e.getAsString());
            JsonObject items = root.getAsJsonObject("items");
            if (items != null) {
                for (var e : items.entrySet()) {
                    try {
                        JsonObject item = e.getValue().getAsJsonObject();
                        store.dated.put(e.getKey(), new Entry(item.get("time").getAsLong(),
                            item.has("source") ? item.get("source").getAsString() : null, item.has("sure") && item.get("sure").getAsBoolean()));
                    } catch (RuntimeException ignored) {
                        // Only this item is lost.
                    }
                }
            }
            return store;
        }
    }

    private static Path file;
    private static Store store;
    private static int ticks;
    private static Object lastLevel;
    private static long readyAt;
    private static boolean dirty;
    private static long savedAt;
    /** The last menu open and when, for items that turn up just after it. */
    private static String lastMenu = "";
    private static long lastMenuAt;

    private ItemOrigin() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.inventory.itemOrigin;
    }

    public static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("item-origin.json");
        ClientTickEvents.END_CLIENT_TICK.register(ItemOrigin::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> save());
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!enabled() || !Compat.isOnSkyblock()) return;
            String uuid = uuid(stack);
            if (uuid.isEmpty()) return;
            Entry entry = store().get(uuid);
            if (entry != null) lines.add(Component.literal(text(entry, System.currentTimeMillis(), ZoneId.systemDefault())).withStyle(ChatFormatting.DARK_GRAY));
        });
    }

    private static Store store() {
        if (store == null) store = SavedJson.load(file, Store::fromJson, new Store());
        return store;
    }

    private static String uuid(ItemStack stack) {
        return stack.isEmpty() ? "" : Compat.customDataView(stack).getStringOr("uuid", "");
    }

    /** "Got 6 Nov, M6 chest", "First seen 6 Nov, Hub", "First seen 6 Nov" (with the year when it isn't this year). Public for the game test. */
    public static String text(Entry entry, long now, ZoneId zone) {
        ZonedDateTime when = Instant.ofEpochMilli(entry.time()).atZone(zone);
        boolean thisYear = when.getYear() == Instant.ofEpochMilli(now).atZone(zone).getYear();
        String date = (thisYear ? DATE : DATE_YEAR).format(when);
        return (entry.sure() ? "Got " : "First seen ") + date + (entry.source() == null ? "" : ", " + entry.source());
    }

    /**
     * Whether a menu holds items you already own, so anything first seen in it has no date. Reward chests, shops and the
     * auction house don't count. Public for the game test.
     */
    public static boolean storageMenu(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        return t.equals("chest") || t.equals("large chest") || t.contains("ender chest") || t.contains("backpack") || t.contains("storage")
            || t.contains("wardrobe") || t.contains("equipment") || t.contains("accessory bag") || t.contains("personal vault");
    }

    /**
     * Where a new item came from: the chest it was in, the menu you just left, else only the dungeon floor or area you were
     * in. Public for the game test.
     */
    public static Entry entryFor(long now, @Nullable String chestFloor, String menu, long menuAgeMs, String floor, String area) {
        if (chestFloor != null && !chestFloor.equals("?")) return new Entry(now, chestFloor + " chest", true);
        if (menuAgeMs <= MENU_WINDOW_MS) {
            if (menu.contains("Auction")) return new Entry(now, "Auction House", true);
            if (menu.contains("Craft")) return new Entry(now, "crafted", true);
        }
        if (floor != null && !floor.isEmpty()) return new Entry(now, floor, false);
        if (area != null && !area.isEmpty()) return new Entry(now, area, false);
        return new Entry(now, null, false);
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (!enabled() || mc.player == null || !Compat.isOnSkyblock() || com.epic60869.sky2m.sim.roomsim.SimState.isActive()) return;
        long now = System.currentTimeMillis();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            readyAt = now + WORLD_CHANGE_WAIT_MS;
        }
        if (now < readyAt) return;
        if (ticks % MENU_EVERY_TICKS == 0 && mc.gui.screen() instanceof AbstractContainerScreen<?> screen && !(screen instanceof InventoryScreen)) readMenu(screen, now);
        if (ticks % INVENTORY_EVERY_TICKS == 0) readInventory(mc, now);
        if (dirty && now - savedAt > SAVE_EVERY_MS) save();
    }

    private static void readMenu(AbstractContainerScreen<?> screen, long now) {
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString()).trim();
        lastMenu = title;
        lastMenuAt = now;
        if (!storageMenu(title)) return;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            String uuid = uuid(slot.getItem());
            if (!uuid.isEmpty() && store().markKnown(uuid)) dirty = true;
        }
    }

    private static void readInventory(Minecraft mc, long now) {
        // A different profile's items would all look new, so nothing is dated until the profile is known and baselined.
        String profile = Sky2MStorageSearch.currentProfile();
        if (profile == null || profile.isEmpty()) return;
        Inventory inventory = mc.player.getInventory();
        List<ItemStack> held = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!uuid(inventory.getItem(i)).isEmpty()) held.add(inventory.getItem(i));
        }
        if (!store().baselined(profile)) {
            // An empty inventory may just not have arrived yet.
            if (held.isEmpty()) return;
            List<String> uuids = new ArrayList<>();
            for (ItemStack stack : held) uuids.add(uuid(stack));
            store().baseline(profile, uuids);
            dirty = true;
            return;
        }
        for (ItemStack stack : held) {
            String uuid = uuid(stack);
            if (store().isKnown(uuid)) continue;
            String chestFloor = ChestHistory.recentChestFloor(Sky2MPriceTooltip.marketId(stack), Compat.neuName(stack), now, CHEST_WINDOW_MS);
            if (store().note(uuid, entryFor(now, chestFloor, lastMenu, now - lastMenuAt, Sky2MLocation.dungeonFloor(), Sky2MLocation.area()))) dirty = true;
        }
    }

    private static void save() {
        if (!dirty || store == null) return;
        dirty = false;
        savedAt = System.currentTimeMillis();
        String json = store.toJson();
        CompletableFuture.runAsync(() -> SavedJson.save(file, json));
    }
}
