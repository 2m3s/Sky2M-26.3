/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), mobskin/MobSkins. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.features.misc.playasmob;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which mobs Play as Mob offers: every living mob except the bosses and giants (ender dragon, wither, giant, elder
 * guardian, warden) and the ones that make no sense following a player (shulker, illusioner, armor stands, mannequins).
 * An id this Minecraft version lacks is simply not offered.
 */
public final class PlayAsMobs {

    public static final List<String> IDS = List.of(
            "allay", "armadillo", "axolotl", "bat", "bee", "blaze", "bogged", "breeze", "camel", "cat", "cave_spider",
            "chicken", "cod", "copper_golem", "cow", "creaking", "creeper", "dolphin", "donkey", "drowned", "enderman",
            "endermite", "evoker", "fox", "frog", "ghast", "glow_squid", "goat", "guardian", "happy_ghast", "hoglin",
            "horse", "husk", "iron_golem", "llama", "magma_cube", "mooshroom", "mule", "ocelot", "panda", "parrot",
            "phantom", "pig", "piglin", "piglin_brute", "pillager", "polar_bear", "pufferfish", "rabbit", "ravager",
            "salmon", "sheep", "silverfish", "skeleton", "skeleton_horse", "slime", "sniffer", "snow_golem", "spider",
            "squid", "stray", "strider", "tadpole", "trader_llama", "tropical_fish", "turtle", "vex", "villager",
            "vindicator", "wandering_trader", "witch", "wither_skeleton", "wolf", "zoglin", "zombie", "zombie_horse",
            "zombie_villager", "zombified_piglin");

    private static final Object NO_TYPE = new Object();
    private static final Map<String, Object> TYPES = new ConcurrentHashMap<>();
    private static volatile List<String> available;

    private PlayAsMobs() {
    }

    /** {@code id} when it is on the list, else null. */
    public static String sanitize(String id) {
        if (id == null || id.isEmpty()) return null;
        return id.toLowerCase(Locale.ROOT).equals(id) && IDS.contains(id) ? id : null;
    }

    /** The entity type for an allowed id in this Minecraft version, or null. Remembered per id. */
    public static EntityType<?> type(String id) {
        if (id == null) return null;
        Object known = TYPES.get(id);
        if (known != null) return known == NO_TYPE ? null : (EntityType<?>) known;
        EntityType<?> type = typeUncached(id);
        if (TYPES.size() < 256) TYPES.put(id, type == null ? NO_TYPE : type);
        return type;
    }

    private static EntityType<?> typeUncached(String id) {
        String ok = sanitize(id);
        if (ok == null) return null;
        Identifier key = Identifier.withDefaultNamespace(ok);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        // ENTITY_TYPE is a defaulted registry: never accept its default standing in for a missing id.
        return type != null && key.equals(EntityType.getKey(type)) ? type : null;
    }

    /** The allowed ids this version actually has, in list order. */
    public static List<String> available() {
        List<String> a = available;
        if (a == null) {
            List<String> out = new ArrayList<>();
            for (String id : IDS) if (type(id) != null) out.add(id);
            a = List.copyOf(out);
            available = a;
        }
        return a;
    }

    /** The mob's in-game name ("Zombified Piglin"), or "Player" for none. */
    public static String displayName(String id) {
        EntityType<?> type = type(id);
        return type == null ? "Player" : type.getDescription().getString();
    }
}
