package com.epic60869.sky2m.features.core;

import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToIntFunction;

/**
 * Glowing outlines in a colour, for features that highlight entities (see Sky2MEntityGlowMixin and
 * Sky2MEntityGlowColourMixin). Each provider gives an entity's RGB colour, or -1 to leave it alone; the first
 * provider with a colour wins. Providers are asked for every entity every frame, so they should be map lookups.
 */
public final class EntityGlow {
    private static final List<ToIntFunction<Entity>> PROVIDERS = new CopyOnWriteArrayList<>();

    private EntityGlow() {}

    public static void register(ToIntFunction<Entity> provider) {
        PROVIDERS.add(provider);
    }

    /** The entity's glow colour, or -1 if no feature highlights it. */
    public static int colour(Entity entity) {
        for (ToIntFunction<Entity> provider : PROVIDERS) {
            int colour = provider.applyAsInt(entity);
            if (colour != -1) return colour;
        }
        return -1;
    }
}
