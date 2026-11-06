package com.epic60869.sky2m.sim.roomsim;

import com.google.gson.JsonObject;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel's Pet Rules (Autopet) "On throw fishing hook" trigger, the one a dungeon player fires on demand: casting
 * any fishing rod swaps the pet by the rule the player set for his class.
 *
 * <p>Each class has one rule ({@link Rule}): off, "swap to" a chosen pet (with its pet item), or "toggle" between two
 * (Golden Dragon for clear, Black Cat for boss). It is edited on the pets page of {@code /simwardrobe} and saved
 * with the class's gear ({@link SimGear}); a party guest's rule travels with his wardrobe to the host
 * ({@link SimParty}), whose server does the swap - so it works the same for everyone.
 *
 * <p>Hypixel's numbers (hypixelskyblock.minecraft.wiki/w/Autopet): a 2-tick (0.1 s) swap cooldown, and the chat
 * line {@code "Autopet equipped your [Lvl 100] Black Cat! VIEW RULE"}. Any rod counts, as on Hypixel; the sim's own
 * is the {@link #ROD_ID} "Pet Rules Rod" (a vanilla fishing rod), and the cast itself is vanilla's.
 */
public final class SimPetRules {

    /** The sim's rod: a plain vanilla fishing rod with a SkyBlock id and name. */
    public static final String ROD_ID = "PET_RULES_ROD";
    /** Hypixel's Autopet swap cooldown: 2 ticks. */
    public static final int SWAP_COOLDOWN_TICKS = 2;

    public enum Mode {
        OFF("Off"), SWAP("Swap to"), TOGGLE("Toggle");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /**
     * One class's rule.
     *
     * @param a the pet to swap to (SWAP), or the first of the two (TOGGLE)
     * @param b the second pet of a toggle (unused otherwise)
     */
    public record Rule(Mode mode, SimGear.PetBuild a, SimGear.PetBuild b) {
        public static final Rule OFF = new Rule(Mode.OFF, null, null);

        /** What a cast swaps to while {@code current} is out, or null for no swap. */
        public SimGear.PetBuild target(SimGear.PetBuild current) {
            return switch (mode) {
                case OFF -> null;
                case SWAP -> a == null || a.sameAs(current) ? null : a;
                case TOGGLE -> {
                    if (a == null || b == null) {
                        yield null;
                    }
                    yield a.sameAs(current) ? b : a;
                }
            };
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("mode", mode.name());
            if (a != null) {
                o.add("a", a.toJson());
            }
            if (b != null) {
                o.add("b", b.toJson());
            }
            return o;
        }

        public static Rule fromJson(com.google.gson.JsonElement el) {
            if (!(el instanceof JsonObject o)) {
                return OFF;
            }
            Mode m;
            try {
                m = Mode.valueOf(o.get("mode").getAsString());
            } catch (RuntimeException e) {
                m = Mode.OFF;
            }
            SimGear.PetBuild a = o.has("a") ? SimGear.PetBuild.fromJson(o.get("a")) : null;
            SimGear.PetBuild b = o.has("b") ? SimGear.PetBuild.fromJson(o.get("b")) : null;
            return new Rule(m, a, b);
        }

        public String describe() {
            return switch (mode) {
                case OFF -> "Off";
                case SWAP -> "Swap to " + (a == null ? "?" : a.label());
                case TOGGLE -> "Toggle " + (a == null ? "?" : a.label()) + " <-> " + (b == null ? "?" : b.label());
            };
        }
    }

    /** Server tick of each player's last swap, for the 2-tick cooldown. */
    private static final Map<UUID, Long> LAST_SWAP = new ConcurrentHashMap<>();

    private SimPetRules() {
    }

    /**
     * Listens for the cast. {@code UseItemCallback} fires on the integrated server (the swap) and on each client;
     * a party guest's client mirrors the swap into his own saved gear so the wardrobe he sends next agrees with the
     * host's. Both answer PASS: the rod still casts the vanilla way. Called from {@link SimGear#register}.
     */
    static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack held = player.getItemInHand(hand);
            if (!isRod(held) || player.fishing != null) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer sp) {
                if (SimAbilities.simServer(sp) || SimParty.guestOnServer(sp)) {
                    onUse(sp, held);
                }
            } else {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (player == mc.player && SimParty.guest() && SimState.canAct(mc)) {
                    mirrorOnGuest();
                }
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
    }

    private static long guestLastSwapMillis;

    /** A guest's own copy of the swap his host's server is making. Client thread. */
    private static void mirrorOnGuest() {
        long now = System.currentTimeMillis();
        if (now - guestLastSwapMillis < SWAP_COOLDOWN_TICKS * 50L) {
            return;
        }
        SimStats.DungeonClass c = SimStats.profile().selected();
        SimGear.ClassGear gear = SimGear.classGear(c);
        SimGear.PetBuild target = gear.rule().target(gear.pet());
        if (target != null) {
            guestLastSwapMillis = now;
            SimGear.setPet(c, target);
        }
    }

    /** The rod for a hotbar: a vanilla fishing rod named "Pet Rules Rod" (a rare item, as Hypixel's rods). */
    public static ItemStack rod() {
        ItemStack stack = new ItemStack(Items.FISHING_ROD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", ROD_ID);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        SimItemLore.applyCustom(stack, "§9Pet Rules Rod", List.of(
                "§7Cast it to fire your §aPet Rules§7:",
                "§7swaps your pet by the rule you set",
                "§7for your class in §e/simwardrobe§7.",
                "",
                "§7Any fishing rod does the same.",
                "",
                "§9§lRARE FISHING ROD"));
        return stack;
    }

    /** Whether a stack is a fishing rod (any rod triggers the rule, as on Hypixel). */
    public static boolean isRod(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof FishingRodItem;
    }

    /**
     * A right click with a rod on the sim's server: fires the rule when the hook is being THROWN (not reeled in).
     * Returns nothing to the click - vanilla still casts. Server thread.
     */
    public static void onUse(ServerPlayer sp, ItemStack held) {
        if (!isRod(held) || sp.fishing != null || !SimState.isActive()) {
            return;
        }
        cast(sp);
    }

    /** Fires the rule now (the cast). True when the pet was swapped. Server thread. */
    public static boolean cast(ServerPlayer sp) {
        long now = SimVitals.now();
        Long last = LAST_SWAP.get(sp.getUUID());
        if (last != null && now - last < SWAP_COOLDOWN_TICKS) {
            return false;
        }
        SimGear.ClassGear gear = SimParty.member(sp).gear();
        SimGear.PetBuild target = gear.rule().target(gear.pet());
        if (target == null) {
            return false;
        }
        LAST_SWAP.put(sp.getUUID(), now);
        SimGear.swapPetOnServer(sp, target);
        sp.sendSystemMessage(Component.literal("§cAutopet §eequipped your §7[Lvl " + target.pet().level() + "] §"
                + target.pet().colour() + target.pet().name() + "§e! §a§lVIEW RULE"));
        return true;
    }
}
