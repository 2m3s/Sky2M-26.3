// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/StorageData.kt
// (with its ProfileSpecificDataHolder storage done as a JSON file per SkyBlock profile).
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import com.epic60869.sky2m.Sky2MStorageSearch;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public final class StorageData {
    public final SortedMap<StoragePageSlot, StorageInventory> storageInventories = new TreeMap<>();
    public final SortedMap<StoragePageSlot, String> customNames = new TreeMap<>();

    /** The name to show for {@code slot}: the user assigned name if there is one, otherwise the vanilla default. */
    public String displayName(StoragePageSlot slot) {
        String custom = customNames.get(slot);
        return custom != null ? custom : slot.defaultName();
    }

    public static final class StorageInventory {
        public final StoragePageSlot slot;
        public VirtualInventory inventory;

        public StorageInventory(StoragePageSlot slot, VirtualInventory inventory) {
            this.slot = slot;
            this.inventory = inventory;
        }
    }

    // ---------------------------------------------------------------- per profile, saved in config/sky2m/storage-overlay.json

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, StorageData> PROFILES = new HashMap<>();
    private static Path file;
    private static boolean dirty;
    private static long lastSave;
    private static final StorageData NO_PROFILE = new StorageData();

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("storage-overlay.json");
        load();
    }

    /** The current profile's storage (Firmament's StorageOverlay.Data.data). */
    public static StorageData data() {
        String profile = Sky2MStorageSearch.currentProfile();
        if (profile == null || profile.isEmpty()) return NO_PROFILE;
        StorageData data = PROFILES.computeIfAbsent(profile, p -> new StorageData());
        if (data.storageInventories.isEmpty() && !data.imported) data.importFromStorageSearch();
        return data;
    }

    private boolean imported;

    /** The first time, take the pages Sky2M' Storage Search already saved, so they show straight away. */
    private void importFromStorageSearch() {
        List<Sky2MStorageSearch.StoragePage> old = Sky2MStorageSearch.storagePages();
        if (old.isEmpty()) return;
        imported = true;
        for (Sky2MStorageSearch.StoragePage page : old) {
            if (page.number() <= 0) continue;
            StoragePageSlot slot = page.type().equals("ENDER_CHEST")
                ? (page.number() <= 9 ? StoragePageSlot.ofEnderChestPage(page.number()) : null)
                : (page.number() <= 18 ? StoragePageSlot.ofBackPackPage(page.number()) : null);
            if (slot == null) continue;
            List<ItemStack> items = page.items();
            int rows = (items.size() - 9) / 9;
            if (rows < 1 || rows > 5) continue;
            storageInventories.put(slot, new StorageInventory(slot, new VirtualInventory(items.subList(9, 9 + rows * 9))));
        }
        markDirty();
    }

    /** Bumped on every change, so views built from the data (the overlay's search filter) know to rebuild. */
    private static int version;

    public static int version() {
        return version;
    }

    public static void markDirty() {
        dirty = true;
        version++;
    }

    /** Saves now and then (and when leaving), not on every change. */
    public static void tick() {
        if (dirty && System.currentTimeMillis() - lastSave > 5_000L) save();
    }

    public static void save() {
        if (file == null || !dirty) return;
        dirty = false;
        lastSave = System.currentTimeMillis();
        try {
            JsonObject root = new JsonObject();
            JsonObject profiles = new JsonObject();
            for (Map.Entry<String, StorageData> e : PROFILES.entrySet()) {
                JsonObject profile = new JsonObject();
                JsonObject pages = new JsonObject();
                for (StorageInventory inv : e.getValue().storageInventories.values()) {
                    JsonObject page = new JsonObject();
                    if (inv.inventory != null && inv.inventory.blob() != null) {
                        page.addProperty("rows", inv.inventory.rows());
                        page.addProperty("blob", inv.inventory.blob());
                    }
                    pages.add(String.valueOf(inv.slot.index()), page);
                }
                profile.add("pages", pages);
                JsonObject names = new JsonObject();
                e.getValue().customNames.forEach((slot, name) -> names.addProperty(String.valueOf(slot.index()), name));
                profile.add("names", names);
                profiles.add(e.getKey(), profile);
            }
            root.add("profiles", profiles);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save the storage overlay: " + e.getMessage());
        }
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("profiles")) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("profiles").entrySet()) {
                JsonObject profile = e.getValue().getAsJsonObject();
                StorageData data = new StorageData();
                data.imported = true;
                if (profile.has("pages")) {
                    for (Map.Entry<String, JsonElement> p : profile.getAsJsonObject("pages").entrySet()) {
                        StoragePageSlot slot = new StoragePageSlot(Integer.parseInt(p.getKey()));
                        JsonObject page = p.getValue().getAsJsonObject();
                        VirtualInventory inv = page.has("blob")
                            ? VirtualInventory.fromSaved(page.get("rows").getAsInt(), page.get("blob").getAsString()) : null;
                        data.storageInventories.put(slot, new StorageInventory(slot, inv));
                    }
                }
                if (profile.has("names")) {
                    for (Map.Entry<String, JsonElement> n : profile.getAsJsonObject("names").entrySet()) {
                        data.customNames.put(new StoragePageSlot(Integer.parseInt(n.getKey())), n.getValue().getAsString());
                    }
                }
                PROFILES.put(e.getKey(), data);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read the storage overlay: " + e.getMessage());
        }
    }
}
