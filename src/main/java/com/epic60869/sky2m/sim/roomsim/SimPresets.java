package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import com.epic60869.sky2m.sim.util.SimItemIds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The user's class presets (2026-11-01): one per class, read from {@code assets/sky2m/sim/presets/<class>.json}, with
 * the baseline (green) choice of everything his class setups list - wardrobe slots of per-piece armour builds with
 * their own equipment (Mage: LCM in slot 1, RCM in slot 2; Tank: the boss set in slot 1, the Giant Goldor's
 * terminals set in slot 2), the pets in priority order (the first goes out, the others keep their pet items for when
 * they are picked), the Pet Rule (the rod toggles the clear pet and the boss pet) and the hotbar and inventory.
 *
 * <p>Applied by picking a class in Mort's menu ({@link SimMort}) - a party guest's too, since that menu is the
 * server's - or with {@code /simpreset <class>} (the party leader's). {@link #apply} runs on the server: the class,
 * the wardrobe and pet go through {@link SimGear} (the host's own saved gear, or the guest's entry on the host via
 * {@link SimParty}), slot 1 is worn, and the items are put in their slots with their builds ({@link SimItemBuild}).
 * An item the sim has no version of yet stays in the data and is skipped with a log line.
 *
 * <p>JSON: each item is an id string or a {@link SimItemBuild#fromJson} object ({@code totalStars} counts master
 * stars, or Crimson stars on a Crimson Isle item; gems as {@code "PERFECT JASPER"}; Art of Peace as the
 * {@code art_of_peace} enchantment). {@code hotbar} fills slots 1-9 and {@code inventory} the slots after it in order.
 */
public final class SimPresets {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");

    private static final int SLOTS = 36;

    /**
     * One class's preset.
     *
     * @param wardrobe the wardrobe slots from slot 1, each named
     * @param pets     in priority order, each with its pet item; the first is put out
     * @param rule     the Pet Rule the rod fires
     * @param items    a build per inventory slot (hotbar first), {@link SimItemBuild#EMPTY} for none
     */
    public record Preset(SimStats.DungeonClass dungeonClass, String name, List<SimGear.WardrobeSlot> wardrobe,
                         List<SimGear.PetBuild> pets, SimPetRules.Rule rule, List<SimItemBuild> items) {

        /** The class gear this preset gives: its wardrobe with slot 1 on, its first pet out, its Pet Rule. */
        public SimGear.ClassGear gear() {
            SimGear.ClassGear g = SimGear.ClassGear.EMPTY;
            for (int i = 0; i < wardrobe.size() && i < SimGear.SLOTS; i++) {
                g = g.withSlot(i, wardrobe.get(i));
            }
            g = g.withActive(wardrobe.isEmpty() ? -1 : 0);
            // Last to first, so every pet's item is remembered and the first one ends up out.
            for (int i = pets.size() - 1; i >= 0; i--) {
                g = g.withPet(pets.get(i));
            }
            return g.withRule(rule);
        }
    }

    private static final Map<SimStats.DungeonClass, Preset> PRESETS = new EnumMap<>(SimStats.DungeonClass.class);
    private static volatile boolean loaded;

    /** The class each player last had a preset applied for, so Mort can say "already selected". */
    private static final Map<UUID, SimStats.DungeonClass> APPLIED = new ConcurrentHashMap<>();

    /** Item ids already reported as missing, so each is logged once. */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private SimPresets() {
    }

    /** {@code /simpreset <class>}; called from {@link SimMort#register}, before {@link SimCommandGate} gates it. */
    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simpreset")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .then(ClientCommands.argument("class", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (SimStats.DungeonClass c : SimStats.DungeonClass.values()) {
                                        b.suggest(c.name().toLowerCase(Locale.ROOT));
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> {
                                    applyFromCommand(Minecraft.getInstance(),
                                            StringArgumentType.getString(ctx, "class"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ModChat.send("Sim", ModChat.text("Usage: "),
                                    ModChat.value("/simpreset <archer|berserk|healer|mage|tank>"));
                            return 1;
                        })));
    }

    private static void applyFromCommand(Minecraft client, String name) {
        SimStats.DungeonClass c = SimStats.classByName(name);
        if (c == null) {
            ModChat.send("Sim", ModChat.bad("No class called " + name + "."));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            return;
        }
        if (SimRun.hasStarted()) {
            ModChat.send("Sim", ModChat.bad("You can't change your class once the dungeon has started!"));
            return;
        }
        UUID id = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) {
                apply(sp, c);
            }
        });
    }

    // ------------------------------------------------------------------ data

    /** A class's preset, or null when its file is missing or broken (logged). Any thread. */
    public static Preset get(SimStats.DungeonClass c) {
        load();
        synchronized (PRESETS) {
            return PRESETS.get(c);
        }
    }

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (PRESETS) {
            if (loaded) {
                return;
            }
            for (SimStats.DungeonClass c : SimStats.DungeonClass.values()) {
                String path = "/assets/sky2m/sim/presets/" + c.name().toLowerCase(Locale.ROOT) + ".json";
                try (InputStream in = SimPresets.class.getResourceAsStream(path)) {
                    if (in == null) {
                        LOGGER.warn("Sim presets: {} is missing", path);
                        continue;
                    }
                    JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    PRESETS.put(c, parse(c, o));
                } catch (Exception e) {
                    LOGGER.warn("Sim presets: could not read {}", path, e);
                }
            }
            loaded = true;
        }
    }

    /** One preset file. Package-private for the tests. */
    static Preset parse(SimStats.DungeonClass c, JsonObject o) {
        String name = o.has("name") ? o.get("name").getAsString() : c.label;
        List<SimGear.WardrobeSlot> wardrobe = new ArrayList<>();
        if (o.get("wardrobe") instanceof JsonArray slots) {
            for (JsonElement el : slots) {
                if (!(el instanceof JsonObject so)) {
                    continue;
                }
                wardrobe.add(new SimGear.WardrobeSlot(so.has("name") ? so.get("name").getAsString() : "",
                        builds(so.get("armour")), builds(so.get("equipment"))));
            }
        }
        List<SimGear.PetBuild> pets = new ArrayList<>();
        if (o.get("pets") instanceof JsonArray arr) {
            for (JsonElement el : arr) {
                SimGear.PetBuild p = SimGear.PetBuild.fromJson(el);
                if (p.item() == SimGear.PetItem.NONE) {
                    // Maxed like the items: a pet the preset gives no item holds its usual one.
                    p = new SimGear.PetBuild(p.pet(), SimGear.defaultPetItem(p.id()));
                }
                if (SimGear.NONE_ID.equals(p.id())) {
                    LOGGER.info("Sim presets: {} - pet {} is not in the sim, skipped", name, el);
                } else {
                    pets.add(p);
                }
            }
        }
        SimPetRules.Rule rule = SimPetRules.Rule.OFF;
        if (o.get("petRules") instanceof JsonObject r && r.has("mode")) {
            String mode = r.get("mode").getAsString().toLowerCase(Locale.ROOT);
            if (mode.equals("toggle") && r.has("clear") && r.has("boss")) {
                rule = new SimPetRules.Rule(SimPetRules.Mode.TOGGLE, petOf(pets, r.get("clear").getAsString()),
                        petOf(pets, r.get("boss").getAsString()));
            } else if (mode.equals("swap") && r.has("pet")) {
                rule = new SimPetRules.Rule(SimPetRules.Mode.SWAP, petOf(pets, r.get("pet").getAsString()), null);
            }
        }
        List<SimItemBuild> items = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            items.add(SimItemBuild.EMPTY);
        }
        List<SimItemBuild> hotbar = builds(o.get("hotbar"));
        for (int i = 0; i < hotbar.size() && i < 9; i++) {
            items.set(i, hotbar.get(i));
        }
        List<SimItemBuild> inv = builds(o.get("inventory"));
        for (int i = 0; i < inv.size() && 9 + i < SLOTS; i++) {
            items.set(9 + i, inv.get(i));
        }
        return new Preset(c, name, List.copyOf(wardrobe), List.copyOf(pets), rule, List.copyOf(items));
    }

    /** The preset's own entry for a pet (with its pet item), or the plain pet. */
    private static SimGear.PetBuild petOf(List<SimGear.PetBuild> pets, String id) {
        for (SimGear.PetBuild p : pets) {
            if (p.id().equalsIgnoreCase(id)) {
                return p;
            }
        }
        return new SimGear.PetBuild(SimGear.pet(id), SimGear.defaultPetItem(id));
    }

    /** A preset's items, each maxed ({@link SimItemData#maxed}): the preset picks them, the sim maxes them. */
    private static List<SimItemBuild> builds(JsonElement el) {
        List<SimItemBuild> out = new ArrayList<>();
        if (el instanceof JsonArray a) {
            for (JsonElement e : a) {
                out.add(SimItemData.maxed(SimItemBuild.fromJson(e)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ applying

    /** The class this player last had a preset applied for, or null. */
    static SimStats.DungeonClass appliedFor(ServerPlayer sp) {
        return sp == null ? null : APPLIED.get(sp.getUUID());
    }

    /** Forgets who had which preset, for a new floor. */
    static void reset() {
        APPLIED.clear();
    }

    /**
     * Applies a class's preset to one player: the class, the wardrobe (slot 1 worn), equipment, pets and Pet Rule,
     * and the hotbar and inventory. The host's change is saved as his own gear; a guest's is his entry on the host.
     * Server thread.
     *
     * @return false when there is no such preset
     */
    public static boolean apply(ServerPlayer sp, SimStats.DungeonClass c) {
        Preset p = c == null ? null : get(c);
        if (sp == null || p == null) {
            return false;
        }
        SimGear.ClassGear gear = p.gear();
        reportDropped(p, gear);
        if (SimParty.isHost(sp)) {
            // The host's class and wardrobe live in his client's settings (saved, and what the stats read).
            SimAbilities.onClient(() -> {
                SimGear.setClassGear(c, gear);
                SimClassKit.select(Minecraft.getInstance(), c);
            });
        } else {
            SimParty.setGuestClass(sp, c, gear);
        }
        SimGear.equipOnServer(sp, gear);
        giveItems(sp, p);
        SimClassKit.ensureOrbOnServer(sp);
        APPLIED.put(sp.getUUID(), c);
        LOGGER.info("Sim presets: {} gets the {} preset ({} wardrobe slot(s), pet {}, rule {})",
                sp.getGameProfile().name(), p.name(), p.wardrobe().size(), gear.pet().label(), gear.rule().describe());
        return true;
    }

    /** Wardrobe builds {@link SimGear} dropped (an id the sim's item data does not know), logged once each. */
    private static void reportDropped(Preset p, SimGear.ClassGear gear) {
        for (int s = 0; s < p.wardrobe().size() && s < SimGear.SLOTS; s++) {
            SimGear.WardrobeSlot want = p.wardrobe().get(s);
            SimGear.WardrobeSlot got = gear.slots().get(s);
            for (int i = 0; i < 4; i++) {
                missing(want.armour().get(i), got.armour().get(i), p.name() + " armour");
                missing(want.equipment().get(i), got.equipment().get(i), p.name() + " equipment");
            }
        }
    }

    private static void missing(SimItemBuild want, SimItemBuild got, String where) {
        if (!want.isEmpty() && got.isEmpty() && MISSING.add(want.id())) {
            LOGGER.info("Sim presets: {} {} is not a sim item yet - skipped", where, want.id());
        }
    }

    /**
     * Puts the preset's items in their slots. A sim item in a slot the preset leaves empty is taken out (as loading
     * a loadout preset does); anything that is not a sim item stays.
     */
    private static void giveItems(ServerPlayer sp, Preset p) {
        var inv = sp.getInventory();
        for (int i = 0; i < SLOTS; i++) {
            SimItemBuild b = p.items().get(i);
            ItemStack stack = b.isEmpty() ? ItemStack.EMPTY : stackOf(b);
            if (stack.isEmpty()) {
                if (SimItemIds.skyblockId(inv.getItem(i)) != null) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                continue;
            }
            inv.setItem(i, stack);
        }
        giveArrows(sp);
    }

    /**
     * A stack of arrows with every class, as a quiver would hold on Hypixel: the class bows (and a Mage's or Tank's
     * spare bow) fire nothing without them. Only when he carries no arrows already, so loading twice does not stack up.
     */
    private static void giveArrows(ServerPlayer sp) {
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(net.minecraft.world.item.Items.ARROW)) {
                return;
            }
        }
        ItemStack arrows = SimItems.build(SimArrows.FLINT_ARROW_ID);
        if (arrows.isEmpty()) {
            arrows = new ItemStack(net.minecraft.world.item.Items.ARROW);
        }
        arrows.setCount(64);
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, arrows);
                return;
            }
        }
        inv.add(arrows);
    }

    /** The sim's item for a build, with the build on it; empty (logged once) when the sim has no such item. */
    static ItemStack stackOf(SimItemBuild b) {
        ItemStack base = SimPetRules.ROD_ID.equals(b.id()) ? SimPetRules.rod() : SimItems.build(b.id());
        if (base.isEmpty()) {
            if (MISSING.add(b.id())) {
                LOGGER.info("Sim presets: item {} is not a sim item yet - skipped", b.id());
            }
            return ItemStack.EMPTY;
        }
        return hasModifiers(b) ? b.applyTo(base) : base;
    }

    /** Whether a build carries anything beyond its id (a plain id keeps the sim item exactly as it is built). */
    private static boolean hasModifiers(SimItemBuild b) {
        return !b.equals(SimItemBuild.of(b.id()));
    }
}
