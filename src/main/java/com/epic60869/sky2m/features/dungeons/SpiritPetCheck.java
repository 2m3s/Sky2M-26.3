package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.profileviewer.ProfileApi;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a player has a LEGENDARY Spirit pet, for the dungeon score. Hypixel's rule (the wiki's Dungeon Score page): if
 * the first player to die in a run has a Legendary Spirit pet in their pets menu, that death costs 1 skill point instead
 * of 2. The pet doesn't have to be out, so this reads the selected profile's whole {@code pets_data.pets} through
 * {@link ProfileApi} (an Epic one holding a Tier Boost shows as Legendary, so it counts too).
 *
 * <p>Answers are cached for the session (a pets menu hardly changes mid-run), and {@link #prefetch} looks teammates up
 * as soon as they're seen, so the first death is usually answered at once instead of after an API round trip. A failed
 * lookup isn't cached, so the next ask tries again.
 */
public final class SpiritPetCheck {
    private static final Map<String, Boolean> KNOWN = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Boolean>> IN_FLIGHT = new ConcurrentHashMap<>();

    private SpiritPetCheck() {}

    /** The cached answer for a player, or null while it isn't known yet. */
    public static Boolean known(String name) {
        return name == null ? null : KNOWN.get(name.toLowerCase(Locale.ROOT));
    }

    /** Looks a player up (once, shared with any lookup already running); completes with false when it can't tell. */
    public static CompletableFuture<Boolean> lookup(String name) {
        if (name == null || name.isBlank()) return CompletableFuture.completedFuture(false);
        String key = name.toLowerCase(Locale.ROOT);
        Boolean cached = KNOWN.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return IN_FLIGHT.computeIfAbsent(key, k -> {
            CompletableFuture<Boolean> out = ProfileApi.playerFast(name).handle((player, error) -> {
                if (error != null || player == null || player.profiles().isEmpty()) return null;
                boolean has = hasLegendarySpirit(player.profiles().getFirst().member());
                KNOWN.put(key, has);
                return has;
            }).thenApply(has -> has != null && has);
            out.whenComplete((r, e) -> IN_FLIGHT.remove(key));
            return out;
        });
    }

    /** Starts lookups for players not known yet, so a later death needn't wait for the API. */
    public static void prefetch(Iterable<String> names) {
        for (String name : names) {
            if (name != null && !name.isBlank() && known(name) == null) lookup(name);
        }
    }

    /**
     * Whether a profile member's pets menu ({@code pets_data.pets}) holds a Legendary Spirit pet. Public for the game test.
     */
    public static boolean hasLegendarySpirit(JsonObject member) {
        if (member == null || !(member.get("pets_data") instanceof JsonObject data) || !data.has("pets")
            || !data.get("pets").isJsonArray()) return false;
        for (JsonElement el : data.getAsJsonArray("pets")) {
            if (!(el instanceof JsonObject pet)) continue;
            if (!"SPIRIT".equals(string(pet, "type"))) continue;
            String tier = string(pet, "tier");
            if ("LEGENDARY".equals(tier)) return true;
            if ("EPIC".equals(tier) && "PET_ITEM_TIER_BOOST".equals(string(pet, "heldItem"))) return true;
        }
        return false;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
