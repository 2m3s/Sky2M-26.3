/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The parts of survival mode that get in the way of practising, taken back out.
 *
 * <p>killer560 (2026-09-28): "make it so i dont take fall damage. also let me do /fly to fly. If i ever die i
 * shouldd be teleported back to the middle of the room."
 *
 * <p>All three follow from asking for survival instead of creative, and they are the right corrections rather
 * than a retreat from it. Survival is wanted because creative flight lets a route skip the jumps it exists to
 * rehearse - the point was never to be punished for rehearsing them. A drop that would cost half a health bar
 * on Hypixel is part of a route; dying to it in a practice room just means walking back.
 *
 * <p>So: fall damage off, flight on demand rather than always, and death turned into a trip back to the middle
 * of the room instead of a respawn screen. Cancelling the death rather than letting it happen and respawning
 * afterwards keeps the run's own state intact - a respawn would move him to the world spawn, hundreds of
 * blocks from the room, which is the exact problem the snap-to-room fix existed to solve.
 */
public final class SimSurvival {

    /** Where to put him back. Set when a room is built, so it is always the room he is actually in. */
    private static BlockPos homePoint;

    private SimSurvival() {
    }

    /** Remembered by the builder once it knows where the room's floor is. */
    public static void setHome(BlockPos pos) {
        homePoint = pos == null ? null : pos.immutable();
        // A new floor or room: the last floor's safe spots may be inside walls now.
        LAST_SAFE.clear();
    /** The current home, so a boss room can borrow it and give it back. */
    public static BlockPos home() {
        return homePoint;
    /** Each player's last safe spot on the floor: standing on something inside one of the map's rooms. */
    private static final java.util.Map<java.util.UUID, net.minecraft.world.phys.Vec3> LAST_SAFE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The dungeon's death barriers and borders: falling below the floor's lowest block (into the void under a room,
     * or out of a gap) or walking off the map puts him back where he last stood safely, instead of a void death.
     * Not in a boss arena or the terminal drill (they are off the map and handle their own falls). Server thread.
     */
    private static void keepInBounds(ServerPlayer sp) {
        if (SimBoss.isActive() || SimTerms.isActive() || sp.isSpectator()) {
            return;
        // Off the map counts only near the dungeon grid: the holding area a build waits in is far away on purpose.
        BlockPos middle = com.epic60869.sky2m.sim.livemap.DungeonLayout.cellCenter(60);
        if (Math.abs(sp.getX() - middle.getX()) > 220 || Math.abs(sp.getZ() - middle.getZ()) > 220) {
            return;
        boolean onMap = SimState.roomIdAt(sp.getBlockX(), sp.getBlockZ()) >= 0;
        boolean below = sp.getY() < SimAltitude.minWorldY() - 4;
        if (!below && onMap) {
            if (sp.onGround()) {
                LAST_SAFE.put(sp.getUUID(), sp.position());
            return;
        net.minecraft.world.phys.Vec3 back = LAST_SAFE.get(sp.getUUID());
        if (back == null && homePoint != null) {
            back = net.minecraft.world.phys.Vec3.atBottomCenterOf(homePoint);
        if (back == null) {
            return;
        sp.teleportTo(sp.level(), back.x, back.y, back.z, java.util.Set.of(), sp.getYRot(), sp.getXRot(), false);
        sp.fallDistance = 0;
        sp.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    /** When each player last bounced off lava, in server ticks, so one dip is one bounce. */
    private static final java.util.Map<java.util.UUID, Integer> LAST_BOUNCE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * SkyBlock's lava bounce: touching lava in a dungeon throws you up out of it instead of burning you, and higher when
     * you look up (the "super bounce" players use to climb). APPROXIMATE strengths: about 5 blocks, about 14 looking
     * straight up. Server thread.
     */
    private static void lavaBounce(ServerPlayer sp) {
        if (!sp.isInLava() || sp.isSpectator() || sp.getAbilities().flying) {
            return;
        int now = sp.level().getServer().getTickCount();
        Integer last = LAST_BOUNCE.get(sp.getUUID());
        if (last != null && now - last < 8) {
            return;
        LAST_BOUNCE.put(sp.getUUID(), now);
        // -90 is straight up: from level to straight up the bounce grows from 1.0 to 1.9 blocks a tick.
        double up = Math.max(0, -sp.getXRot()) / 90.0;
        net.minecraft.world.phys.Vec3 v = sp.getDeltaMovement();
        sp.setDeltaMovement(v.x, 1.0 + 0.9 * up, v.z);
        sp.syncVelocity = true;
        sp.fallDistance = 0;
    private static final net.minecraft.world.entity.EquipmentSlot[] ARMOUR_SLOTS = {
            net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};

    /**
     * Worn armour never wears out in the sim: Hypixel gear has no durability, but the vanilla pieces under it do, and
     * a big hit that got through to vanilla damage (an explosion, the killing blow) broke the helmet, chestplate and
     * boots on death. Made unbreakable the first time it is worn, and repaired if it was already damaged. Server
     * thread.
     */
    private static void keepArmour(ServerPlayer sp) {
        for (net.minecraft.world.entity.EquipmentSlot slot : ARMOUR_SLOTS) {
            net.minecraft.world.item.ItemStack worn = sp.getItemBySlot(slot);
            if (worn.isEmpty() || !worn.isDamageableItem()) {
                continue;
            worn.set(net.minecraft.core.component.DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
            worn.setDamageValue(0);
    }

    public static void register() {
        // No fall damage. Checked per damage rather than by resetting fallDistance every tick: resetting it
        // silently changes what the game thinks happened, while refusing the damage leaves everything else -
        // the landing, the sound, the animation - exactly as it was.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer) || !SimState.isActive()) {
                return true;
            }
            // The whole fall and fire TAGS rather than DamageTypes.FALL alone: IS_FIRE is what carries lava
            // (killer560, 2026-10-04: "Make it so lava doesn't do damage"), and IS_FALL is the set vanilla's own
            // FALL_DAMAGE rule switches off. SimWorld.freezeWorld turns both game rules off as well.
            // Drowning too (killer560, 2026-10-05: test sims kept drowning); DROWNING_DAMAGE is off as well.
            return !source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                    && !source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
                    && !source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING);
        });

        // Put the flames out too. Lava still sets him alight even when the burn does nothing, and a screen
        // full of fire in a practice room is the damage's noise without the damage.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SimState.isActive()) {
                return;
            }
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp.isOnFire()) {
                    sp.clearFire();
                }
                // The sim runs on EASY now (SimWorld), where hunger drains; PEACEFUL used to refill it. A route
                // practised for half an hour must not lose sprint to an empty food bar.
                if (sp.getFoodData().getFoodLevel() < 20) {
                    sp.getFoodData().eat(20, 1.0f);
                }
                keepInBounds(sp);
                keepArmour(sp);
                lavaBounce(sp);
            }
        });

        // A vanilla death (the void, /kill) never reaches a respawn screen: it is a sim death like an emptied
        // health pool - a ghost, or back to the middle of the room in the instant-teleport mode (SimDeath).
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player) || !SimState.isActive()) {
                return true;
            }
            player.setHealth(player.getMaxHealth());
            SimDeath.die(player, source);
            return false;
        });

        // Flight has to be re-asserted: the server resets abilities on respawn, on dimension change and
        // whenever it feels like syncing them, so setting the flag once does not keep it.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SimState.isActive() || !flying && GUEST_FLYERS.isEmpty()) {
                return;
            }
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if ((flying || GUEST_FLYERS.contains(sp.getUUID())) && !sp.getAbilities().mayfly) {
                    sp.getAbilities().mayfly = true;
                    sp.onUpdateAbilities();
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                // Sim-only, like /map: an ungated /fly would claim the name on Hypixel, where the server has
                // its own idea of what that command means.
                dispatcher.register(ClientCommands.literal("fly")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            toggle();
                            return 1;
                        })));
    }

    private static boolean flying;
    /** Staff party guests who turned /fly on for themselves (the host's own /fly is everyone's). Server thread. */
    private static final java.util.Set<java.util.UUID> GUEST_FLYERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether /fly is on, so a ghost brought back gets the flight he had before. */
    static boolean flightOn() {
        return flying;
    /**
     * What a death does in the sim: full health, back to the middle of the room. Server thread.
     *
     * <p>Shared by a vanilla death (cancelled above) and by {@link SimVitals} emptying the Hypixel health pool,
     * which is the usual way to die now that mobs hit for Hypixel numbers. Since the ghost deaths ({@link SimDeath}) this
     * is the instant-teleport death mode only.
     */
    static void rescue(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.getFoodData().eat(20, 1.0f);
        player.clearFire();
        player.fallDistance = 0;
        BlockPos home = homePoint;
        if (home != null && player.level() instanceof ServerLevel level) {
            player.teleportTo(level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5,
                    java.util.Set.of(), player.getYRot(), player.getXRot(), false);
        ModChat.send("Sim", ModChat.dim("back to the middle of the room"));
    /** A staff party guest's /fly, on the host: flight for that player alone. Server thread. */
    static void toggleFor(ServerPlayer sp) {
        boolean on = GUEST_FLYERS.add(sp.getUUID());
        if (!on) {
            GUEST_FLYERS.remove(sp.getUUID());
        sp.getAbilities().mayfly = on || flying;
        if (!sp.getAbilities().mayfly) {
            sp.getAbilities().flying = false;
        sp.onUpdateAbilities();
        sp.sendSystemMessage(ModChat.line("Sim", ModChat.text("Flight "), ModChat.value(on ? "ON" : "OFF")));

    private static void toggle() {
        if (SimParty.sendAction("staff_fly", "")) {
            return;
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        flying = !flying;
        boolean on = flying;
        server.execute(() -> {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                sp.getAbilities().mayfly = on;
                if (!on) {
                    // Dropped as well as disallowed, or he stays hovering with no way down.
                    sp.getAbilities().flying = false;
                }
                sp.onUpdateAbilities();
            }
        });
        ModChat.send("Sim", ModChat.text("Flight "), ModChat.value(on ? "ON" : "OFF"));
    }

    /** Turns flight back off and forgets the room, for leaving the sim. */
    public static void reset() {
        flying = false;
        GUEST_FLYERS.clear();
        LAST_BOUNCE.clear();
        homePoint = null;
        LAST_SAFE.clear();
    }
}
