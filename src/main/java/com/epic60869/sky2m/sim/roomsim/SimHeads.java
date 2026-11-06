package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hypixel's own head textures for the sim's skull items - Bonzo's and the Spirit Mask, Wither Goggles, the Necron
 * heads, the Mender Crown, the Revive Stone, the pets - instead of a plain Steve or wither skeleton skull. The textures
 * are the item repo's ({@link RepoItems#heads()}, the same list the custom helmet textures use); an item the repo has
 * no head for, or a repo that has not loaded yet, keeps its vanilla look.
 */
final class SimHeads {

    /** Repo id to texture value, built once the repo has loaded. */
    private static volatile Map<String, String> byId;

    private SimHeads() {
    }

    /** The head texture for a SkyBlock id ("BONZO_MASK", or a pet as "WITHER_SKELETON;4"), or null. Any thread. */
    static String texture(String id) {
        Map<String, String> map = byId;
        if (map == null) {
            var heads = RepoItems.heads();
            if (heads.isEmpty()) {
                return null;
            }
            map = new HashMap<>();
            for (RepoItems.Head h : heads) {
                map.put(h.id(), h.texture());
            }
            byId = map;
        }
        return id == null ? null : map.get(id);
    }

    /** A pet's head: its own rarity's texture, else any rarity's (a pet's head is the same at most rarities). */
    static String petTexture(String petId, String rarity) {
        String[] order = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};
        for (int i = 0; i < order.length; i++) {
            if (order[i].equalsIgnoreCase(rarity)) {
                String t = texture(petId + ";" + i);
                if (t != null) {
                    return t;
                }
            }
        }
        for (int i = order.length - 1; i >= 0; i--) {
            String t = texture(petId + ";" + i);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** {@code stack} as a player head wearing {@code texture} (its name, lore and data kept), or unchanged. */
    static ItemStack withTexture(ItemStack stack, String texture) {
        if (texture == null || texture.isEmpty() || stack.isEmpty()) {
            return stack;
        }
        ItemStack head = stack.is(Items.PLAYER_HEAD) ? stack : stack.transmuteCopy(Items.PLAYER_HEAD);
        // A UUID from the texture itself, so the same head is always the same profile (and cached as one skin).
        UUID id = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
        head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(
                new GameProfile(id, "SimHead", Compat.propertyMapWithTexture(texture))));
        return head;
    }

    /** {@link #withTexture} with the repo's head for {@code skyblockId}. */
    static ItemStack apply(ItemStack stack, String skyblockId) {
        return withTexture(stack, texture(skyblockId));
    }
}
