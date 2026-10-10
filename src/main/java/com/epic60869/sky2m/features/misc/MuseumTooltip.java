package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MStorageSearch;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Museum tooltip, following Skyblocker's MuseumTooltip and MuseumItemCache: shows whether an item is donated to
 * your museum. Which items (and armor sets) go in the museum, and which upgrades count their lower tiers as
 * donated, comes from NEU's constants/museum.json. Like Skyblocker, it asks the Hypixel API what you've donated: the
 * SBC server reads Hypixel's museum API for your profile ("museum" -> "museumResult"), when you join and every 10
 * minutes. It also remembers, per profile, the "Museum ➜ Combat" and other category menus as you look through them
 * (a donated item shows as itself, or lime dye while borrowed; a missing one as gray dye), so it works while SBC
 * can't answer.
 */
public final class MuseumTooltip {
    private static final Pattern MUSEUM_TITLE = Pattern.compile("^Museum ➜ (.+)$");
    private static final Gson GSON = new Gson();

    /** Museum id (item id, or set id for armor) -> category ("Combat", ..., "Special"). */
    private static volatile Map<String, String> categories = Map.of();
    /** Armor piece id -> set id. */
    private static volatile Map<String, String> pieceToSet = Map.of();
    /** Tier -> the upgrade above it ("children" in museum.json maps each upgrade to the tier below). */
    private static volatile Map<String, String> higherTier = Map.of();
    /** Item id -> the id the museum counts it as (skins, celebration hats, starred items). */
    private static volatile Map<String, String> mappedIds = Map.of();
    /** Lowercase armor set name ("abyssal armor") -> set id. */
    private static volatile Map<String, String> setNames = Map.of();

    /** Profile id -> what the museum menus showed. */
    private static final Map<String, ProfileMuseum> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, String> NAME_CACHE = new ConcurrentHashMap<>();
    private static Path file;

    private record ProfileMuseum(Set<String> donated, Set<String> missing) {
        ProfileMuseum() {
            this(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
        }
    }

    private MuseumTooltip() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("museum-donations.json");
        load();
        RepoItems.runAsync(MuseumTooltip::loadMuseumData);

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            if (!MUSEUM_TITLE.matcher(plainTitle(container)).matches()) return;
            int[] ticks = {0};
            ScreenEvents.afterTick(screen).register(s -> {
                if (++ticks[0] % 10 == 1) scan(container);
            });
        });

        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null || !config.misc.collectionTooltips.museum || !Compat.isOnSkyblock()) return;
            addTooltip(stack, lines);
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 100 == 0) maybeFetch();
        });
    }

    // ---------------------------------------------------------------- from the Hypixel API (through SBC)

    /** How often your museum is asked for again while you play. */
    private static final long FETCH_EVERY_MS = 10 * 60_000L;
    private static final Map<String, Long> FETCHED_AT = new ConcurrentHashMap<>();
    private static int ticks;

    /**
     * Asks the SBC server for your museum (it reads Hypixel's museum API for your profile), once per profile when you
     * join and every 10 minutes after, so the tooltip knows what you've donated without opening the museum menus.
     */
    private static void maybeFetch() {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.collectionTooltips.museum || !Compat.isOnSkyblock() || categories.isEmpty()) return;
        String profile = Sky2MStorageSearch.currentProfile();
        if (profile.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long last = FETCHED_AT.get(profile);
        if (last != null && now - last < FETCH_EVERY_MS) return;
        com.google.gson.JsonObject packet = com.epic60869.sky2m.features.sbc.Sbc.packet("museum");
        packet.addProperty("profile", profile);
        // Offline: try again in a minute rather than waiting the full 10.
        FETCHED_AT.put(profile, com.epic60869.sky2m.features.sbc.SbcNet.sendQuietly(packet) ? now : now - FETCH_EVERY_MS + 60_000L);
    }

    /**
     * museumResult {ok, profile, donated: [museum ids], message}: everything in the museum category lists that isn't
     * donated is missing. A failed answer (API off, SBC can't reach Hypixel) leaves what the menus showed.
     */
    public static void handle(JsonObject packet) {
        if (!packet.has("ok") || !packet.get("ok").getAsBoolean() || !packet.has("donated") || !packet.get("donated").isJsonArray()) return;
        String profile = packet.has("profile") ? packet.get("profile").getAsString() : Sky2MStorageSearch.currentProfile();
        if (profile.isEmpty() || categories.isEmpty()) return;
        ProfileMuseum museum = new ProfileMuseum();
        for (JsonElement id : packet.getAsJsonArray("donated")) {
            String museumId = museumId(id.getAsString());
            if (categories.containsKey(museumId)) museum.donated().add(museumId);
        }
        categories.forEach((id, category) -> {
            if (!category.equals("Special") && !museum.donated().contains(id)) museum.missing().add(id);
        });
        PROFILES.put(profile, museum);
        save();
    }

    private static String plainTitle(AbstractContainerScreen<?> container) {
        String title = ChatFormatting.stripFormatting(container.getTitle().getString());
        return title == null ? "" : title.trim();
    }

    // ---------------------------------------------------------------- repo data

    private static void loadMuseumData() {
        try {
            JsonObject json = JsonParser.parseString(RepoItems.neuRepoFile("constants/museum.json")).getAsJsonObject();
            Map<String, String> sets = new HashMap<>();
            json.getAsJsonObject("sets_to_items").asMap().forEach((set, pieces) -> {
                for (JsonElement piece : pieces.getAsJsonArray()) sets.put(piece.getAsString(), set);
            });

            Map<String, String> cats = new HashMap<>();
            json.getAsJsonObject("items").asMap().forEach((category, items) -> {
                String label = category.substring(0, 1).toUpperCase(Locale.ROOT) + category.substring(1);
                for (JsonElement item : items.getAsJsonArray()) cats.put(item.getAsString(), label);
            });

            Map<String, String> higher = new HashMap<>();
            json.getAsJsonObject("children").asMap().forEach((upgrade, tier) -> higher.put(tier.getAsString(), upgrade));

            Map<String, String> mapped = new HashMap<>();
            json.getAsJsonObject("mapped_ids").asMap().forEach((id, to) -> mapped.put(id, to.getAsString()));

            // Set names as the museum menu writes them: "Abyssal Armor", "Flamebreaker Armor" (set_exceptions holds
            // the spelling of sets whose id differs from their name).
            Map<String, String> exceptions = new HashMap<>();
            json.getAsJsonObject("set_exceptions").asMap().forEach((name, set) -> exceptions.put(set.getAsString(), name));
            Map<String, String> names = new HashMap<>();
            for (String set : json.getAsJsonObject("sets_to_items").keySet()) {
                names.put(normalizeSetName(set.replace('_', ' ')), set);
                String exception = exceptions.get(set);
                if (exception != null) names.put(normalizeSetName(exception.replace('_', ' ')), set);
            }

            pieceToSet = sets;
            categories = cats;
            higherTier = higher;
            mappedIds = mapped;
            setNames = names;
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load museum data: " + e.getMessage());
        }
    }

    /** "Abyssal Armor", "Adaptive Equipment", "Diver's Set" -> "abyssal", "adaptive", "diver's". */
    private static String normalizeSetName(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        for (String suffix : List.of(" armor", " equipment", " set")) {
            if (n.endsWith(suffix)) n = n.substring(0, n.length() - suffix.length()).trim();
        }
        return n;
    }

    /** The id the museum files an item under: its mapped id, without STARRED_, and the set id for armor pieces. */
    private static String museumId(String itemId) {
        String id = mappedIds.getOrDefault(itemId, itemId);
        if (id.startsWith("STARRED_")) id = id.substring("STARRED_".length());
        id = mappedIds.getOrDefault(id, id);
        return pieceToSet.getOrDefault(id, id);
    }

    // ---------------------------------------------------------------- museum menus

    private static void scan(AbstractContainerScreen<?> container) {
        if (categories.isEmpty()) return;
        ProfileMuseum museum = PROFILES.computeIfAbsent(Sky2MStorageSearch.currentProfile(), p -> new ProfileMuseum());
        boolean changed = false;
        List<Slot> slots = container.getMenu().slots;
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            boolean missing = stack.is(Items.DYE.gray());
            String id;
            if (missing || stack.is(Items.DYE.lime())) {
                // Not donated (gray) or donated but borrowed (lime): only the name says which item it is.
                id = idByName(stack);
            } else {
                String itemId = Compat.neuName(stack);
                id = itemId.isEmpty() ? null : museumId(itemId);
            }
            if (id == null || !categories.containsKey(id)) continue;
            if (missing) {
                changed |= museum.donated().remove(id);
                changed |= museum.missing().add(id);
            } else {
                changed |= museum.missing().remove(id);
                changed |= museum.donated().add(id);
            }
        }
        if (changed) save();
    }

    private static String idByName(ItemStack stack) {
        String name = ChatFormatting.stripFormatting(Compat.realName(stack).getString());
        if (name == null || name.isBlank()) return null;
        name = name.trim();
        String cached = NAME_CACHE.get(name);
        if (cached != null) return cached;
        String id = setNames.get(normalizeSetName(name));
        if (id == null) {
            String itemId = RepoItems.idByName(name);
            if (itemId != null) id = museumId(itemId);
        }
        // Misses aren't cached: the item list may just not have loaded yet.
        if (id != null) NAME_CACHE.put(name, id);
        return id;
    }

    // ---------------------------------------------------------------- tooltip

    private static void addTooltip(ItemStack stack, List<Component> lines) {
        String itemId = Compat.neuName(stack);
        if (itemId.isEmpty()) return;
        String id = museumId(itemId);
        String category = categories.get(id);
        if (category == null) return;

        // Special items can be donated any number of times, so they are never "not donated" (as in Skyblocker).
        if (category.equals("Special")) {
            lines.add(Component.literal("Museum: (Special)").withStyle(ChatFormatting.LIGHT_PURPLE));
            return;
        }
        ProfileMuseum museum = PROFILES.get(Sky2MStorageSearch.currentProfile());
        Component state;
        if (museum != null && isDonated(museum, id)) {
            state = Component.literal("✔").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal(" Donated").withStyle(ChatFormatting.GREEN));
        } else if (museum != null && museum.missing().contains(id)) {
            state = Component.literal("✖").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(" Not Donated").withStyle(ChatFormatting.RED));
        } else {
            state = Component.literal("? Open your Museum to check").withStyle(ChatFormatting.GRAY);
        }
        lines.add(Component.literal("Museum (" + category + "): ").withStyle(ChatFormatting.LIGHT_PURPLE).append(state));
    }

    /** Donated, or an upgrade of it is (donating Wither Goggles counts the goggles below them). */
    private static boolean isDonated(ProfileMuseum museum, String id) {
        Set<String> seen = new HashSet<>();
        for (String tier = id; tier != null && seen.add(tier); tier = higherTier.get(tier)) {
            if (museum.donated().contains(tier)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- saving

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : root.entrySet()) {
                JsonObject profile = entry.getValue().getAsJsonObject();
                ProfileMuseum museum = new ProfileMuseum();
                for (JsonElement id : profile.getAsJsonArray("donated")) museum.donated().add(id.getAsString());
                for (JsonElement id : profile.getAsJsonArray("missing")) museum.missing().add(id.getAsString());
                PROFILES.put(entry.getKey(), museum);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read museum-donations.json: " + e.getMessage());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        PROFILES.forEach((profile, museum) -> {
            JsonObject o = new JsonObject();
            o.add("donated", toArray(museum.donated()));
            o.add("missing", toArray(museum.missing()));
            root.add(profile, o);
        });
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save museum-donations.json: " + e.getMessage());
        }
    }

    private static JsonArray toArray(Set<String> ids) {
        JsonArray array = new JsonArray();
        ids.stream().sorted().forEach(array::add);
        return array;
    }
}
