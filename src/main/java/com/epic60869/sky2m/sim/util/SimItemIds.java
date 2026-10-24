/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560; see THIRD_PARTY_NOTICES.md.
 */
package com.epic60869.sky2m.sim.util;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Item ids, skull textures and the Hypixel check the sim needs (Killer560's CheatUtils and LegacyItems helpers). */
public final class SimItemIds {

    private static final Map<String, ResolvableProfile> PROFILE_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_PROFILE_CACHE = 256;

    private SimItemIds() {
    }

    /** The SkyBlock id in an item's custom data, or null. */
    public static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }

    /** Whether the client is on Hypixel (the sim's features stand down there; Sky2M's own run instead). */
    public static boolean isOnDungeonServer(Minecraft client) {
        if (client == null) {
            return false;
        }
        ServerData server = client.getCurrentServer();
        return server != null && com.epic60869.sky2m.custom.util.Compat.isHypixelAddress(server.ip);
    }

    public static ResolvableProfile skullProfile(String textureValue) {
        if (PROFILE_CACHE.size() >= MAX_PROFILE_CACHE && !PROFILE_CACHE.containsKey(textureValue)) {
            PROFILE_CACHE.clear();
        }
        return PROFILE_CACHE.computeIfAbsent(textureValue, value -> {
            Multimap<String, Property> backing = LinkedHashMultimap.create();
            backing.put("textures", new Property("textures", value));
            UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ResolvableProfile.createResolved(new GameProfile(uuid, "SkyblockItem", new PropertyMap(backing)));
        });
    }
}
