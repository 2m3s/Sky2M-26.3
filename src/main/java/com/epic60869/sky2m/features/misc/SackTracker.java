package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MStorageSearch;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many of each item are in your sacks, like SkyHanni's SackApi: read from the sack menus when you open them
 * ("Stored: 1,234/20k") and kept up to date with the "[Sacks]" messages' hovers. Saved per profile.
 */
public final class SackTracker {
    private static final Pattern STORED = Pattern.compile("^Stored: ([0-9,]+)/.*$");
    /** One line of a "[Sacks]" hover: "+64 Gold Ingot (Mining Sack)". */
    private static final Pattern SACK_CHANGE = Pattern.compile("^ *([+-])([0-9,]+) (.+?) [(].*[)] *$");
    private static final Gson GSON = new Gson();

    /** Profile id -> lowercase item name -> amount. */
    private static final Map<String, Map<String, Long>> PROFILES = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static Path file;
    private static boolean dirty;
    private static int ticks;

    private SackTracker() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("sacks.json");
        load();
        Sky2MChat.onChat(message -> onChat(message.component()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 == 0 && mc.gui.screen() instanceof AbstractContainerScreen<?> screen) scanMenu(screen);
            if (dirty && ticks % 200 == 0) save();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> save());
    }

    private static final List<java.util.function.BiConsumer<String, Long>> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

    /** Runs for each item a "[Sacks]" message says went in (+) or out (-): its lower-case name and the amount. */
    public static void onSackChange(java.util.function.BiConsumer<String, Long> listener) {
        CHANGE_LISTENERS.add(listener);
    }

    /** Runs when the known sack amounts change. */
    public static void onUpdate(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** How many of this item (plain name, any case) are in your sacks, or -1 if it's never been seen in them. */
    public static long amount(String itemName) {
        Long amount = profile().get(itemName.toLowerCase(Locale.ROOT).trim());
        return amount == null ? -1 : amount;
    }

    private static Map<String, Long> profile() {
        String id = Sky2MStorageSearch.currentProfile();
        return PROFILES.computeIfAbsent(id.isEmpty() ? "default" : id, k -> new ConcurrentHashMap<>());
    }

    private static void scanMenu(AbstractContainerScreen<?> screen) {
        if (!Compat.isOnSkyblock()) return;
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        if (!title.contains("Sack") || title.contains("Sack of Sacks")) return;
        boolean changed = false;
        boolean read = false;
        int containerSize = screen.getMenu().slots.size() - 36;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.index >= containerSize) break;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                Matcher m = STORED.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
                if (!m.matches()) continue;
                String name = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim().toLowerCase(Locale.ROOT);
                long amount = Long.parseLong(m.group(1).replace(",", ""));
                Long old = profile().put(name, amount);
                if (old == null || old != amount) changed = true;
                read = true;
                break;
            }
        }
        // A sack menu was read: the "(reopen sacks)" hint can go.
        if (read && STALE.remove(profileKey())) changed = true;
        if (changed) changed();
    }

    private static void onChat(Component component) {
        if (!component.getString().contains("[Sacks]")) return;
        Map<String, Long> net = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Component part : flatten(component)) {
            if (!(part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component hover))) continue;
            String text = ChatFormatting.stripFormatting(hover.getString());
            if (!seen.add(text)) continue;
            for (String line : text.split("\n")) {
                Matcher m = SACK_CHANGE.matcher(line);
                if (!m.matches()) continue;
                long amount = Long.parseLong(m.group(2).replace(",", ""));
                net.merge(m.group(3).trim().toLowerCase(Locale.ROOT), m.group(1).equals("-") ? -amount : amount, Long::sum);
            }
        }
        if (net.isEmpty()) return;
        for (Map.Entry<String, Long> e : net.entrySet()) {
            if (e.getValue() != 0) for (var listener : CHANGE_LISTENERS) listener.accept(e.getKey(), e.getValue());
        }
        Map<String, Long> sacks = profile();
        for (Map.Entry<String, Long> e : net.entrySet()) {
            long delta = e.getValue();
            // Already taken out for a bazaar sale (removeSold): don't take it out twice if Hypixel reports it too.
            if (delta < 0) delta = -absorbSold(e.getKey(), -delta);
            if (delta != 0) sacks.merge(e.getKey(), delta, (a, b) -> Math.max(0, a + b));
        }
        changed();
    }

    // ----- Bazaar sales and staleness (Sacks Value) -----

    /** Lower-case item name -> {amount taken out by removeSold, when}, so a later "[Sacks]" -X isn't counted again. */
    private static final Map<String, long[]> RECENTLY_SOLD = new ConcurrentHashMap<>();
    /** Profiles whose model is known to be off (a bulk bazaar sale with no per-item amounts) until a sack menu is opened. */
    private static final Set<String> STALE = ConcurrentHashMap.newKeySet();

    private static long absorbSold(String name, long removed) {
        long[] sold = RECENTLY_SOLD.get(name);
        if (sold == null) return removed;
        if (System.currentTimeMillis() - sold[1] > 60_000L) {
            RECENTLY_SOLD.remove(name);
            return removed;
        }
        long absorbed = Math.min(removed, sold[0]);
        sold[0] -= absorbed;
        if (sold[0] <= 0) RECENTLY_SOLD.remove(name);
        return removed - absorbed;
    }

    /** A copy of the current profile's sacks: lower-case item name -> amount. */
    public static Map<String, Long> snapshot() {
        return new HashMap<>(profile());
    }

    /** The current profile's id in the save file ("default" before it's known), to notice profile switches. */
    public static String profileKey() {
        String id = Sky2MStorageSearch.currentProfile();
        return id.isEmpty() ? "default" : id;
    }

    /**
     * Takes up to {@code amount} of an item (plain name, any case) out of the sacks, for a bazaar sale that took it
     * from them. Returns how many were taken (never more than the sacks were known to hold).
     */
    public static long removeSold(String itemName, long amount) {
        if (amount <= 0) return 0;
        Map<String, Long> sacks = profile();
        String key = findKey(sacks, itemName);
        if (key == null) return 0;
        long have = sacks.getOrDefault(key, 0L);
        long taken = Math.min(have, amount);
        if (taken <= 0) return 0;
        sacks.put(key, have - taken);
        RECENTLY_SOLD.merge(key, new long[]{taken, System.currentTimeMillis()},
            (a, b) -> new long[]{a[0] + b[0], b[1]});
        changed();
        return taken;
    }

    /** The key an item name is saved under: exact (lower-case), else ignoring symbols (gemstone glyphs). */
    private static String findKey(Map<String, Long> sacks, String itemName) {
        String exact = ChatFormatting.stripFormatting(itemName).trim().toLowerCase(Locale.ROOT);
        if (sacks.containsKey(exact)) return exact;
        String loose = loose(exact);
        if (loose.isEmpty()) return null;
        for (String key : sacks.keySet()) if (loose(key).equals(loose)) return key;
        return null;
    }

    private static String loose(String name) {
        return name.replaceAll("[^\\p{L}\\p{N}' -]", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Marks the current profile's sacks as unknown until a sack menu is opened again. */
    public static void markStale() {
        if (STALE.add(profileKey())) changed();
    }

    /** Whether the current profile's sacks need a sack menu opened to be right again. */
    public static boolean isStale() {
        return STALE.contains(profileKey());
    }

    /** Forgets everything in the current profile's sacks (/s2 resetsacks). */
    public static void reset() {
        profile().clear();
        STALE.remove(profileKey());
        RECENTLY_SOLD.clear();
        changed();
    }

    private static List<Component> flatten(Component component) {
        List<Component> out = new ArrayList<>();
        out.add(component);
        for (Component sibling : component.getSiblings()) out.addAll(flatten(sibling));
        return out;
    }

    private static void changed() {
        dirty = true;
        Minecraft.getInstance().execute(() -> LISTENERS.forEach(Runnable::run));
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, Map<String, Long>> data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Map<String, Long>>>() {}.getType());
            if (data != null) data.forEach((k, v) -> PROFILES.put(k, new ConcurrentHashMap<>(v)));
        } catch (Exception ignored) {}
    }

    private static void save() {
        if (!dirty || file == null) return;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(PROFILES), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
