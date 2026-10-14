package com.epic60869.sky2m.features.itemlist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The Item List's favourites: items, recipes and the recipe pinned at the top of the Favourites panel, saved to
 * config/sky2m/item-list-favourites.json. Recipes are saved by a key (what kind, from what, making what, from what) and
 * found again in the list when it loads.
 */
public final class ItemListFavourites {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("item-list-favourites.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Saved {
        List<String> items = new ArrayList<>();
        List<String> recipes = new ArrayList<>();
        String pinned;
    }

    private static Saved saved;
    /** Bumped on every change, so panels know to lay themselves out again. */
    private static int version;

    private ItemListFavourites() {}

    private static Saved saved() {
        if (saved == null) {
            try {
                saved = Files.exists(FILE) ? GSON.fromJson(Files.readString(FILE), Saved.class) : null;
            } catch (Exception e) {
                System.err.println("[Sky2M] Item List couldn't read its favourites: " + e.getMessage());
            }
            if (saved == null) saved = new Saved();
            if (saved.items == null) saved.items = new ArrayList<>();
            if (saved.recipes == null) saved.recipes = new ArrayList<>();
        }
        return saved;
    }

    private static void save() {
        version++;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(saved()));
        } catch (Exception e) {
            System.err.println("[Sky2M] Item List couldn't save its favourites: " + e.getMessage());
        }
    }

    public static int version() {
        return version;
    }

    public static List<String> items() {
        return List.copyOf(saved().items);
    }

    public static boolean isItem(String id) {
        return saved().items.contains(id);
    }

    public static void toggleItem(String id) {
        if (!saved().items.remove(id)) saved().items.add(id);
        save();
    }

    /** "Crafting|ASPECT_OF_THE_END|ASPECT_OF_THE_END|ENCHANTED_EYE_OF_ENDER,ENCHANTED_DIAMOND": the same recipe each load. */
    public static String key(ItemListData.Recipe recipe) {
        String outputs = recipe.outputs().stream().map(ItemListData.Ingredient::id).collect(Collectors.joining(","));
        String inputs = recipe.inputs().stream().map(ItemListData.Ingredient::id).collect(Collectors.joining(","));
        return recipe.kind() + "|" + recipe.owner() + "|" + outputs + "|" + inputs;
    }

    public static boolean isRecipe(ItemListData.Recipe recipe) {
        return saved().recipes.contains(key(recipe));
    }

    public static void toggleRecipe(ItemListData.Recipe recipe) {
        String key = key(recipe);
        if (!saved().recipes.remove(key)) saved().recipes.add(key);
        else if (key.equals(saved().pinned)) saved().pinned = null;
        save();
    }

    /** The favourite recipes that are in the list (none until it has loaded). */
    public static List<ItemListData.Recipe> recipes() {
        List<ItemListData.Recipe> out = new ArrayList<>();
        for (String key : saved().recipes) {
            ItemListData.Recipe recipe = find(key);
            if (recipe != null) out.add(recipe);
        }
        return out;
    }

    public static ItemListData.Recipe pinned() {
        return saved().pinned == null ? null : find(saved().pinned);
    }

    public static boolean isPinned(ItemListData.Recipe recipe) {
        return recipe != null && key(recipe).equals(saved().pinned);
    }

    public static void pin(ItemListData.Recipe recipe) {
        saved().pinned = recipe == null ? null : key(recipe);
        save();
    }

    private static ItemListData.Recipe find(String key) {
        String[] parts = key.split("\\|", -1);
        if (parts.length < 4) return null;
        LinkedHashSet<ItemListData.Recipe> candidates = new LinkedHashSet<>();
        for (String output : parts[2].split(",")) candidates.addAll(ItemListData.recipes(output));
        candidates.addAll(ItemListData.usages(parts[1]));
        for (ItemListData.Recipe recipe : candidates) if (key(recipe).equals(key)) return recipe;
        return null;
    }
}
