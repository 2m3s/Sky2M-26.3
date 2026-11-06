/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.epic60869.sky2m.sim.util.ModChat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.epic60869.sky2m.sim.util.ModLog;

/**
 * The hotbar and inventory the sim gives you every time a dungeon opens.
 *
 * <p>killer560 (2026-09-28): "Add an option somewhere to set a default hot bar/inventory loadout every time you
 * generate a new dungeon or load into one."
 *
 * <p>It matters more than convenience. Muscle memory for a route is partly muscle memory for WHICH SLOT things
 * are in - a pearl on 7 and the breaker on 3 - and a sim that hands you items in whatever order a command
 * produced them would train the wrong reach. Saving the layout once means every practice run starts identical
 * to the last, and identical to what he actually plays with.
 *
 * <p>Saved as Skyblock ITEM IDS per slot rather than as serialised stacks. An id is readable, hand-editable and
 * survives a change to how sim items are built; a serialised stack is none of those and would quietly rot the
 * first time an item's construction changed.
 *
 * <p><b>Presets</b> (2026-11-01): a named copy of the whole setup - the stats profile with its class, that class's
 * armour set and pet ({@link SimGear}) and the hotbar and inventory above - saved, loaded, renamed and deleted on
 * {@link SimLoadoutScreen} ({@code /simloadout}) or with {@code /simloadout preset ...}. Loading one makes it the
 * loadout, so it is also what the next dungeon opens with. A fresh file starts with one preset per class.
 */
public final class SimLoadout {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            ModPaths.config("sky2m-sim-loadout.json");

    /** Hotbar plus the main inventory - the whole player inventory, in slot order. */
    private static final int SLOTS = 36;

    /** Skyblock id per slot, or null for an empty slot. */
    private static String[] slots = new String[SLOTS];
    private static boolean applyOnLoad = true;
    private static boolean loaded;

    /**
     * One saved setup.
     *
     * @param profile the stats profile, class included
     * @param slots   skyblock id per inventory slot, like the loadout's own
     */
    public record Preset(String name, SimStats.Profile profile, String armour, String pet, String[] slots) {
        public SimStats.DungeonClass dungeonClass() {
            return profile.selected();
        }
    }

    private static final int MAX_PRESETS = 45;
    private static final int MAX_NAME = 48;
    private static final List<Preset> presets = new ArrayList<>();

    private SimLoadout() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simloadout")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .then(ClientCommands.literal("show").executes(ctx -> {
                            show();
                            return 1;
                        }))
                        .then(ClientCommands.literal("preset")
                                .then(ClientCommands.literal("list").executes(ctx -> {
                                    listPresets();
                                    return 1;
                                }))
                                .then(ClientCommands.literal("save").then(ClientCommands.argument("name",
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            savePreset(Minecraft.getInstance(), com.mojang.brigadier.arguments
                                                    .StringArgumentType.getString(ctx, "name"));
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("load").then(ClientCommands.argument("name",
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .suggests((ctx, b) -> {
                                            for (Preset pr : presets()) {
                                                b.suggest(pr.name());
                                            }
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            loadPreset(Minecraft.getInstance(), com.mojang.brigadier.arguments
                                                    .StringArgumentType.getString(ctx, "name"));
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("delete").then(ClientCommands.argument("name",
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String name = com.mojang.brigadier.arguments.StringArgumentType
                                                    .getString(ctx, "name");
                                            ModChat.send("Sim", ModChat.text(deletePreset(name)
                                                    ? "Deleted preset " : "No preset called "), ModChat.value(name));
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("save").executes(ctx -> {
                            save(Minecraft.getInstance());
                            return 1;
                        }))
                        .then(ClientCommands.literal("apply").executes(ctx -> {
                            apply(Minecraft.getInstance());
                            return 1;
                        }))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            slots = new String[SLOTS];
                            persist();
                            ModChat.send("Sim", ModChat.text("Loadout cleared"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("auto").executes(ctx -> {
                            applyOnLoad = !applyOnLoad;
                            persist();
                            ModChat.send("Sim", ModChat.text("Apply on entering the sim: "),
                                    ModChat.value(applyOnLoad ? "ON" : "OFF"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("slot")
                                .then(ClientCommands.argument("index", IntegerArgumentType.integer(0, SLOTS - 1))
                                        .then(ClientCommands.argument("id",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(ctx -> {
                                                    setSlot(IntegerArgumentType.getInteger(ctx, "index"),
                                                            com.mojang.brigadier.arguments.StringArgumentType
                                                                    .getString(ctx, "id"));
                                                    return 1;
                                                }))))
                        .executes(ctx -> {
                            // Next tick: the chat screen is still closing.
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> com.epic60869.sky2m.sim.compat.McCompat.setScreen(client,
                                    new SimLoadoutScreen(com.epic60869.sky2m.sim.compat.McCompat.screen(client))));
                            return 1;
                        })));
    }

    /**
     * Records the inventory exactly as it is now.
     *
     * <p>Taken from what he is holding rather than asked for as a list: arranging a hotbar in game and pressing
     * save is a thing he can do in ten seconds, and typing nine item ids in the right order is not.
     */
    public static void save(Minecraft client) {
        if (client.player == null) {
            return;
        }
        load();
        String[] next = new String[SLOTS];
        int filled = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            String id = com.epic60869.sky2m.sim.util.SimItemIds.skyblockId(stack);
            if (id != null) {
                next[i] = id;
                filled++;
            }
        }
        slots = next;
        persist();
        ModChat.send("Sim", ModChat.text("Loadout saved - "), ModChat.value(String.valueOf(filled)),
                ModChat.text(" item(s), slots kept exactly as they are"));
    }

    /** Puts the saved loadout back, in the same slots. */
    public static void apply(Minecraft client) {
        apply(client, false);
    }

    /**
     * Puts the saved loadout back. With {@code clearOthers} a sim item in a slot the loadout leaves empty is taken
     * out, so loading a preset swaps the hotbar rather than piling one onto the other; anything that is not a sim
     * item stays.
     */
    private static void apply(Minecraft client, boolean clearOthers) {
        load();
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("Loadouts only apply inside the sim."));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        var uuid = client.player.getUUID();
        String[] wanted = slots.clone();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            for (int i = 0; i < SLOTS; i++) {
                String id = wanted[i];
                if (id == null) {
                    if (clearOthers && com.epic60869.sky2m.sim.util.SimItemIds.skyblockId(
                            sp.getInventory().getItem(i)) != null) {
                        sp.getInventory().setItem(i, ItemStack.EMPTY);
                    }
                    continue;
                }
                ItemStack stack = SimItems.build(id);
                if (!stack.isEmpty()) {
                    // setItem, not add: the slot IS the point. Adding would drop things wherever there was
                    // room and undo the layout this feature exists to preserve.
                    sp.getInventory().setItem(i, stack);
                }
            }
        });
    }

    /** Called when a sim world finishes loading. */
    public static void onSimEntered(Minecraft client) {
        load();
        if (applyOnLoad && hasAnything()) {
            apply(client);
        }
        // The class's armour and the Dungeon Orb, every time, like the hotbar.
        SimGear.equip(client);
        SimClassKit.ensureOrb(client);
    }

    // ------------------------------------------------------------------ presets

    /** Every preset, in order. A copy. */
    public static List<Preset> presets() {
        load();
        synchronized (presets) {
            return List.copyOf(presets);
        }
    }

    private static Preset find(String name) {
        for (Preset p : presets) {
            if (p.name().equalsIgnoreCase(name.trim())) {
                return p;
            }
        }
        return null;
    }

    /** Saves what he has now - stats, class, that class's armour and pet, inventory - under a name. */
    public static boolean savePreset(Minecraft client, String name) {
        load();
        String clean = cleanName(name);
        if (clean == null || client.player == null) {
            ModChat.send("Sim", ModChat.text("A preset needs a name."));
            return false;
        }
        String[] inv = new String[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            inv[i] = com.epic60869.sky2m.sim.util.SimItemIds.skyblockId(client.player.getInventory().getItem(i));
        }
        SimStats.Profile p = SimStats.profile();
        Preset next = new Preset(clean, p, SimGear.armourFor(p.selected()).id(), SimGear.petFor(p.selected()).id(),
                inv);
        synchronized (presets) {
            Preset old = find(clean);
            if (old != null) {
                presets.set(presets.indexOf(old), next);
            } else if (presets.size() >= MAX_PRESETS) {
                ModChat.send("Sim", ModChat.text("That is the most presets there can be - delete one first."));
                return false;
            } else {
                presets.add(next);
            }
        }
        persist();
        ModChat.send("Sim", ModChat.text("Saved preset "), ModChat.value(clean));
        return true;
    }

    /**
     * Loads a preset: its stats and class, the class's armour and pet, and its hotbar as the loadout - put on now
     * inside the sim, and on every dungeon after.
     */
    public static boolean loadPreset(Minecraft client, String name) {
        load();
        Preset p;
        synchronized (presets) {
            p = name == null ? null : find(name);
        }
        if (p == null) {
            ModChat.send("Sim", ModChat.text("No preset called "), ModChat.value(String.valueOf(name)));
            return false;
        }
        SimStats.setProfile(p.profile());
        SimGear.setArmour(p.dungeonClass(), p.armour());
        SimGear.setPet(p.dungeonClass(), p.pet());
        slots = p.slots().clone();
        persist();
        if (SimState.canAct(client)) {
            apply(client, true);
            SimGear.equip(client);
            SimClassKit.ensureOrb(client);
        }
        ModChat.send("Sim", ModChat.text("Loaded preset "), ModChat.value(p.name()),
                ModChat.dim(" (" + p.dungeonClass().label + ", " + SimGear.armour(p.armour()).name() + ", "
                        + SimGear.pet(p.pet()).name() + ")"));
        return true;
    }

    public static boolean deletePreset(String name) {
        load();
        boolean removed;
        synchronized (presets) {
            Preset p = name == null ? null : find(name);
            removed = p != null && presets.remove(p);
        }
        if (removed) {
            persist();
        }
        return removed;
    }

    /** Renames a preset; false when the old one is missing or the new name is taken or empty. */
    public static boolean renamePreset(String from, String to) {
        load();
        String clean = cleanName(to);
        if (clean == null) {
            return false;
        }
        synchronized (presets) {
            Preset p = find(from);
            Preset clash = find(clean);
            if (p == null || clash != null && clash != p) {
                return false;
            }
            presets.set(presets.indexOf(p), new Preset(clean, p.profile(), p.armour(), p.pet(), p.slots()));
        }
        persist();
        return true;
    }

    /** Puts back any of the default presets (one per class) that are missing, keeping his own. */
    public static void restoreDefaultPresets() {
        load();
        synchronized (presets) {
            for (Preset d : defaultPresets()) {
                if (find(d.name()) == null && presets.size() < MAX_PRESETS) {
                    presets.add(d);
                }
            }
        }
        persist();
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String t = name.replaceAll("§.", "").strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > MAX_NAME ? t.substring(0, MAX_NAME) : t;
    }

    private static void listPresets() {
        List<String> names = new ArrayList<>();
        for (Preset p : presets()) {
            names.add(p.name());
        }
        ModChat.send("Sim", ModChat.text("Presets: "), ModChat.dim(names.isEmpty() ? "none" : String.join(", ", names)));
        ModChat.send("Sim", ModChat.dim("/simloadout preset save|load|delete <name>"));
    }

    /**
     * One sensible setup per class. The hotbar is the same route kit for all five (the sim's items), with the class's
     * weapon first and the Dungeon Orb in the last slot; the stats are the default profile with that class picked.
     */
    public static List<Preset> defaultPresets() {
        List<Preset> out = new ArrayList<>();
        out.add(defaultPreset("Mage - Hyperion/Storm/Sheep", SimStats.DungeonClass.MAGE,
                "HYPERION", "BAT_WAND"));
        out.add(defaultPreset("Berserk - Terminator/Necron/Golden Dragon", SimStats.DungeonClass.BERSERK,
                "TERMINATOR", "HYPERION"));
        out.add(defaultPreset("Archer - Terminator/Maxor/Ender Dragon", SimStats.DungeonClass.ARCHER,
                "TERMINATOR", "HYPERION"));
        out.add(defaultPreset("Tank - Terminator/Goldor/Baby Yeti", SimStats.DungeonClass.TANK,
                "TERMINATOR", "HYPERION"));
        out.add(defaultPreset("Healer - Terminator/Superior/Phoenix", SimStats.DungeonClass.HEALER,
                "TERMINATOR", "BAT_WAND"));
        return out;
    }

    private static Preset defaultPreset(String name, SimStats.DungeonClass c, String weapon, String second) {
        SimStats.Profile d = SimStats.defaults();
        SimStats.Profile p = new SimStats.Profile(d.stats(), d.catacombs(), d.classLevels(), c, d.manaCosts());
        String[] inv = new String[SLOTS];
        inv[0] = weapon;
        inv[1] = "ASPECT_OF_THE_VOID";
        inv[2] = second;
        inv[3] = "SUPERBOOM_TNT";
        inv[4] = "ENDER_PEARL";
        inv[5] = "DUNGEONBREAKER";
        inv[6] = "TACTICAL_INSERTION";
        inv[SimClassKit.ORB_SLOT] = SimClassKit.ORB_ID;
        return new Preset(name, p, SimGear.defaultArmour(c), SimGear.defaultPet(c), inv);
    }

    private static boolean hasAnything() {
        for (String s : slots) {
            if (s != null) {
                return true;
            }
        }
        return false;
    }

    private static void setSlot(int index, String id) {
        load();
        slots[index] = "none".equalsIgnoreCase(id) || "empty".equalsIgnoreCase(id) ? null : id.toUpperCase();
        persist();
        ModChat.send("Sim", ModChat.text("Slot " + index + " = "),
                ModChat.value(slots[index] == null ? "empty" : slots[index]));
    }

    private static void show() {
        load();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            parts.add(i + ":" + (slots[i] == null ? "-" : slots[i]));
        }
        ModChat.send("Sim", ModChat.text("Hotbar "), ModChat.dim(String.join("  ", parts)));
        ModChat.send("Sim", ModChat.dim("/simloadout (screen) | show | save | apply | clear | auto | slot <0-35> <ID>"
                + " | preset list|save|load|delete <name>"));
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(FILE)) {
            synchronized (presets) {
                presets.addAll(defaultPresets());
            }
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            applyOnLoad = !o.has("applyOnLoad") || o.get("applyOnLoad").getAsBoolean();
            JsonArray arr = o.getAsJsonArray("slots");
            for (int i = 0; arr != null && i < arr.size() && i < SLOTS; i++) {
                slots[i] = arr.get(i).isJsonNull() ? null : arr.get(i).getAsString();
            }
            synchronized (presets) {
                if (!(o.get("presets") instanceof JsonArray list)) {
                    // A loadout saved before presets existed: it gets the defaults once.
                    presets.addAll(defaultPresets());
                } else {
                    for (var el : list) {
                        if (el instanceof JsonObject po && presets.size() < MAX_PRESETS) {
                            Preset p = readPreset(po);
                            if (p != null) {
                                presets.add(p);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read the sim loadout", e);
        }
    }

    private static Preset readPreset(JsonObject o) {
        String name = o.has("name") ? cleanName(o.get("name").getAsString()) : null;
        if (name == null) {
            return null;
        }
        SimStats.Profile profile = o.get("profile") instanceof JsonObject po
                ? SimStats.fromJson(po, SimStats.defaults()) : SimStats.defaults();
        String[] inv = new String[SLOTS];
        if (o.get("slots") instanceof JsonArray arr) {
            for (int i = 0; i < arr.size() && i < SLOTS; i++) {
                inv[i] = arr.get(i).isJsonNull() ? null : arr.get(i).getAsString();
            }
        }
        String armour = o.has("armour") ? SimGear.armour(o.get("armour").getAsString()).id() : SimGear.NONE_ID;
        String pet = o.has("pet") ? SimGear.pet(o.get("pet").getAsString()).id() : SimGear.NONE_ID;
        return new Preset(name, profile, armour, pet, inv);
    }

    private static JsonArray slotsJson(String[] ids) {
        JsonArray arr = new JsonArray();
        for (String s : ids) {
            if (s == null) {
                arr.add((String) null);
            } else {
                arr.add(s);
            }
        }
        return arr;
    }

    private static void persist() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("applyOnLoad", applyOnLoad);
            o.add("slots", slotsJson(slots));
            JsonArray list = new JsonArray();
            synchronized (presets) {
                for (Preset p : presets) {
                    JsonObject po = new JsonObject();
                    po.addProperty("name", p.name());
                    po.add("profile", SimStats.toJson(p.profile()));
                    po.addProperty("armour", p.armour());
                    po.addProperty("pet", p.pet());
                    po.add("slots", slotsJson(p.slots()));
                    list.add(po);
                }
            }
            o.add("presets", list);
            Files.writeString(FILE, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save the sim loadout", e);
        }
    }
}
