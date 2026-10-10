package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What each of your pets is holding, for the pet HUD. Hypixel's tab list doesn't say, so like SkyHanni it is
 * gathered from the Pets menu (every pet's data has its held item), "Your pet is now holding X." and "You removed X
 * from your pet!", and Autopet's "Held Item: X" hover. Saved per pet name, so switching pets shows the right item.
 */
public final class PetHeldItems {
    private static final Pattern NOW_HOLDING = Pattern.compile("^Your pet is now holding (?<item>.+?)\\.$");
    private static final Pattern REMOVED = Pattern.compile("^You removed (?<item>.+?) from your pet!$");
    private static final Pattern AUTOPET = Pattern.compile("^Autopet equipped your (?<pet>.+?)! VIEW RULE$");
    private static final Pattern HOVER_HELD = Pattern.compile(".*Held Item: (?<item>.+)$");
    private static final Gson GSON = new Gson();

    /** Pet key (see {@link #key}) -> held item id, or "" for none. */
    private static final Map<String, String> HELD = new ConcurrentHashMap<>();
    private static Path file;
    private static Supplier<String> currentPet = () -> "";

    private PetHeldItems() {}

    /** {@code current}: the pet the HUD shows now, for "Your pet is now holding X." (which doesn't name it). */
    public static void init(Path configDir, Supplier<String> current) {
        file = configDir.resolve("sky2m").resolve("pet-held-items.json");
        currentPet = current;
        load();
        Sky2MChat.onGameMessage((component, overlay) -> {
            if (!overlay) onMessage(component);
        });
    }

    /**
     * "[Lvl 200] [122✦] Golden Dragon ✦" or the Pets menu's "⭐ Golden Dragon" -> "golden dragon": symbols (a favourite
     * star, the skin mark...) and extra spaces don't count, so the menu's and the tab list's names match.
     */
    static String key(String petName) {
        String name = Sky2MLocation.strip(petName == null ? "" : petName).replaceAll("\\[[^]]*]", "")
            .replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ").trim();
        return name.toLowerCase(Locale.ROOT);
    }

    /** From the Pets menu: {@code heldItem} is the item id from the pet's data, or null/"" when it holds nothing. */
    public static void fromMenu(String petName, String heldItem) {
        set(petName, heldItem == null ? "" : heldItem);
    }

    private static void set(String petName, String id) {
        String key = key(petName);
        if (key.isEmpty()) return;
        String old = HELD.put(key, id);
        if (!id.equals(old)) save();
    }

    /** The held item's id, or null if unknown ("" for none). */
    public static String heldItemId(String petName) {
        return HELD.get(key(petName));
    }

    /** The held item's name in its rarity colour, or null if unknown or none. */
    public static Component heldItem(String petName) {
        String id = HELD.get(key(petName));
        if (id == null || id.isEmpty()) return null;
        // In its rarity's colour: a Legendary pet item in gold, an Epic one in purple...
        String name = RepoItems.displayName(id);
        String plain = name == null ? null : net.minecraft.ChatFormatting.stripFormatting(name);
        return Component.literal(plain != null ? plain : id.replace('_', ' ')).withStyle(RepoItems.tierColour(RepoItems.tier(id)));
    }

    private static void onMessage(Component component) {
        String text = Sky2MLocation.strip(component.getString()).trim();
        Matcher m;
        if ((m = NOW_HOLDING.matcher(text)).matches()) {
            String pet = currentPet.get();
            if (!pet.isEmpty()) set(pet, idFor(m.group("item")));
        } else if (REMOVED.matcher(text).matches()) {
            String pet = currentPet.get();
            if (!pet.isEmpty()) set(pet, "");
        } else if ((m = AUTOPET.matcher(text)).matches()) {
            String pet = m.group("pet");
            String held = hoverHeldItem(component);
            set(pet, held == null ? "" : idFor(held));
        }
    }

    /** The "Held Item: X" line in the Autopet message's hover, or null when the pet holds nothing. */
    private static String hoverHeldItem(Component component) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component hover)) {
            for (String line : Sky2MLocation.strip(hover.getString()).split("\n")) {
                Matcher m = HOVER_HELD.matcher(line.trim());
                if (m.matches()) return m.group("item").trim();
            }
        }
        for (Component sibling : component.getSiblings()) {
            String found = hoverHeldItem(sibling);
            if (found != null) return found;
        }
        return null;
    }

    /** An item's id from its name ("Lucky Clover" -> "PET_ITEM_LUCKY_CLOVER"), or the name itself if unknown. */
    private static String idFor(String name) {
        // Pet items first: "Saddle" is also a plain item, and SADDLE isn't the pet one.
        java.util.List<String> ids = RepoItems.idsByName(name.trim());
        for (String id : ids) if (id.startsWith("PET_ITEM_")) return id;
        return !ids.isEmpty() ? ids.getFirst() : name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            // Keys saved before the name matching ignored symbols ("⭐  golden dragon") are tidied the same way.
            root.entrySet().forEach(e -> {
                String key = key(e.getKey());
                if (!key.isEmpty() && (!HELD.containsKey(key) || !e.getValue().getAsString().isEmpty())) HELD.put(key, e.getValue().getAsString());
            });
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read pet-held-items.json: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(Map.copyOf(HELD)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save pet-held-items.json: " + e.getMessage());
        }
    }
}
