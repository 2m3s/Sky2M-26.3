package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import com.epic60869.sky2m.sim.util.ModPaths;
import com.epic60869.sky2m.sim.util.SimItemIds;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dying in the Dungeon Sim the way Hypixel does it: a ghost until someone revives you.
 *
 * <p>When the health pool empties ({@link SimVitals}) and no death save took the hit - the Phoenix, a worn Bonzo's
 * Mask or Spirit Mask ({@code SimItemEffects}) - the player becomes a <b>ghost</b>: invisible, flying, unhurt
 * and unable to hit anything or use anything but a Revive Stone, as a Hypixel ghost is. The death is Hypixel's chat
 * line, {@code " ☠ You were killed by Zombie and became a ghost."}, which the mod's Death Tracker, Death Recap and
 * Score Calculator already read, and it is counted in {@link SimScore} (a Spirit pet's first death costs one point
 * instead of two).
 *
 * <p><b>Coming back.</b> Solo there is no teammate to revive you, so:
 * <ul>
 * <li>a <b>Revive Stone</b> ({@code /simitem revive_stone}) used while a ghost brings you back after a short delay;</li>
 * <li>the delay and the health you come back on are the Healer's Revive passive ({@link SimClassKit#reviveTicks},
 * {@link SimClassKit#reviveHealth}): 5 seconds and 20% for anyone, quicker and more for a Healer;</li>
 * <li>{@code /simrevive} brings you straight back, as a fallback.</li>
 * </ul>
 *
 * <p><b>The old way</b> - straight back to the middle of the room, full health - is still there for pure route
 * practice: {@code /simrevive mode teleport} (or the Stats screen's "Deaths" button). The death is counted either
 * way. Saved to {@code sky2m-sim-death.json}; Hypixel-style ghosts are the default.
 *
 * <p>Everything that touches a player runs on the integrated server's thread.
 */
public final class SimDeath {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");
    private static final Path FILE = ModPaths.config("sky2m-sim-death.json");

    public static final String REVIVE_STONE_ID = "REVIVE_STONE";
    public static final String BONZO_MASK_ID = "BONZO_MASK";
    public static final String SPIRIT_MASK_ID = "SPIRIT_MASK";

    /**
     * The masks' recharge, in ticks. APPROXIMATE, from Hypixel's tooltips: Bonzo's Mask 360 s (180 s starred), Spirit
     * Mask 30 s. The share of the pool each saves you on is the sim's own choice - Hypixel's masks leave you on very
     * little and lean on the immunity that follows.
     */
    private static final int BONZO_RECHARGE = 360 * 20;
    private static final int STARRED_BONZO_RECHARGE = 180 * 20;
    private static final int SPIRIT_MASK_RECHARGE = 30 * 20;

    /** A ghost's revive in progress: when it completes, and the share of the pool it brings back. */
    private static final class Ghost {
        long reviveAt = -1;
        double reviveShare;
    }

    private static final Map<UUID, Ghost> GHOSTS = new ConcurrentHashMap<>();
    /** Each player's own mask recharges (sim parties: every player wears his own mask). */
    private static final Map<UUID, Long> BONZO_READY_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SPIRIT_MASK_READY_AT = new ConcurrentHashMap<>();
    private static long lastDeadNote;

    /** How close a living Healer has to crouch beside a ghost to revive him without a stone, in blocks. */
    private static final double HEALER_REVIVE_RANGE = 3.0;

    /** Hypixel ghosts (true) or the old instant teleport home (false). */
    private static volatile boolean ghosts = true;
    private static boolean loaded;

    private SimDeath() {
    }

    public static void register() {
        load();
        // The masks' saves are SimItemEffects' now (Hypixel's real Bonzo's Mask / Spirit Mask, registered ahead of
        // the Phoenix); the old flat ones here would have given extra saves while those were on cooldown.
        // A ghost takes nothing.
        // ...and deals nothing by any route (arrows, abilities, beams), for whatever has no pool of its own; the
        // pooled mobs and boss bodies refuse a ghost's hit themselves (SimHealth, SimBoss), as they run first.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                (!(entity instanceof ServerPlayer sp) || !isGhost(sp)) && !isGhostAttack(source));
        // ...and hits nothing. Both halves: the client's stops the swing reaching the server at all.
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) ->
                SimState.isActive() && GHOSTS.containsKey(player.getUUID()) ? InteractionResult.FAIL
                        : InteractionResult.PASS);
        ServerTickEvents.END_SERVER_TICK.register(SimDeath::serverTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simrevive")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .then(ClientCommands.literal("mode")
                                .then(ClientCommands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, b) -> b.suggest("ghost").suggest("teleport").buildFuture())
                                        .executes(ctx -> {
                                            String m = StringArgumentType.getString(ctx, "mode");
                                            if (!m.equalsIgnoreCase("ghost") && !m.equalsIgnoreCase("teleport")) {
                                                ModChat.send("Sim", ModChat.text("Death mode is "),
                                                        ModChat.value("ghost"), ModChat.text(" or "),
                                                        ModChat.value("teleport"));
                                                return 0;
                                            }
                                            setGhostMode(m.equalsIgnoreCase("ghost"));
                                            announceMode();
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    announceMode();
                                    return 1;
                                }))
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (SimParty.sendAction("revive", "")) {
                                return 1;
                            }
                            MinecraftServer server = mc.getSingleplayerServer();
                            if (server == null || mc.player == null) {
                                return 0;
                            }
                            UUID id = mc.player.getUUID();
                            server.execute(() -> {
                                ServerPlayer sp = server.getPlayerList().getPlayer(id);
                                if (sp == null) {
                                    return;
                                }
                                if (!isGhost(sp)) {
                                    sp.sendSystemMessage(ModChat.line("Sim", ModChat.text("You are not dead.")));
                                    return;
                                }
                                revive(sp, SimClassKit.reviveHealth(sp));
                            });
                            return 1;
                        })));
    }

    // ------------------------------------------------------------------ the setting

    /** Whether a death makes a Hypixel ghost (the default) rather than teleporting home. */
    public static boolean ghostMode() {
        load();
        return ghosts;
    }

    public static void setGhostMode(boolean on) {
        load();
        ghosts = on;
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("ghosts", on);
            Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save the sim death mode", e);
        }
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                ghosts = !o.has("ghosts") || o.get("ghosts").getAsBoolean();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read the sim death mode, using ghosts", e);
        }
    }

    private static void announceMode() {
        ModChat.send("Sim", ModChat.text("Deaths: "), ModChat.value(ghostMode() ? "ghost (Hypixel)" : "teleport home"),
                ModChat.dim("  /simrevive mode <ghost|teleport>"));
    }

    // ------------------------------------------------------------------ dying

    /** Whether this player is a ghost right now. Any thread. */
    public static boolean isGhost(ServerPlayer sp) {
        return sp != null && GHOSTS.containsKey(sp.getUUID());
    }

    /**
     * Whether this damage comes from a ghost in the sim (his melee, arrows, abilities or beams: the source's
     * attacker is the player whichever it was). A ghost hits nothing. Server thread.
     */
    public static boolean isGhostAttack(DamageSource source) {
        return source != null && SimState.isActive() && source.getEntity() instanceof ServerPlayer sp && isGhost(sp);
    }

    /** Whether the client's own player is a ghost (singleplayer: the same id as the server's). */
    public static boolean isClientGhost() {
        Minecraft mc = Minecraft.getInstance();
        if (SimParty.guestGhost()) {
            // A party guest: the host's server told him (SimParty's hello).
            return true;
        }
        return mc != null && mc.player != null && GHOSTS.containsKey(mc.player.getUUID());
    }

    /**
     * A death: the pool is empty and no save took the hit. Counted, announced in Hypixel's words, then a ghost - or
     * home, in the teleport mode. Server thread.
     */
    static void die(ServerPlayer sp, DamageSource source) {
        if (isGhost(sp)) {
            return;
        }
        if (SimClassKit.spiritSaveAvailable(sp)) {
            SimClassKit.useSpiritSave(sp);
        }
        // The score's rule is Hypixel's: the run's first death, by a player with a Legendary Spirit pet in his pets,
        // not only the one out (SimClassKit.hasSpiritForScore).
        boolean spirit = SimScore.deaths() == 0 && SimClassKit.hasSpiritForScore(sp);
        SimScore.died(spirit);
        boolean ghost = ghostMode();
        String cause = cause(source);
        // Hypixel's line, exactly as the Death Tracker / Death Recap / Score Calculator patterns read it.
        Component line = Component.literal(" ☠ ").withStyle(ChatFormatting.RED)
                .append(Component.literal(cause == null ? "You died" : "You were killed by ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(cause == null ? "" : cause).withStyle(ChatFormatting.RED))
                .append(Component.literal(ghost ? " and became a ghost." : ".").withStyle(ChatFormatting.GRAY));
        sp.sendSystemMessage(line);
        // The party's copy, in Hypixel's third person - what every teammate's Death Tracker and Leap Menu read.
        if (sp.level().getServer() != null) {
            String name = sp.getGameProfile().name();
            Component party = Component.literal(" ☠ ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(name + (cause == null ? " died" : " was killed by "))
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(cause == null ? "" : cause).withStyle(ChatFormatting.RED))
                    .append(Component.literal(ghost ? " and became a ghost." : ".").withStyle(ChatFormatting.GRAY));
            for (ServerPlayer p : sp.level().getServer().getPlayerList().getPlayers()) {
                if (p != sp) {
                    p.sendSystemMessage(party);
                }
            }
        }
        LOGGER.info("Sim death #{}: {} ({})", SimScore.deaths(), cause == null ? "died" : cause,
                ghost ? "ghost" : "teleport");
        if (spirit) {
            sp.sendSystemMessage(ModChat.line("Sim", ModChat.dim("Your Spirit pet halved this death's score penalty.")));
        }
        if (!ghost) {
            SimSurvival.rescue(sp);
            SimVitals.refill(sp);
            return;
        }
        GHOSTS.put(sp.getUUID(), new Ghost());
        sp.setHealth(sp.getMaxHealth());
        sp.clearFire();
        sp.fallDistance = 0;
        sp.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false,
                false, false));
        sp.getAbilities().mayfly = true;
        sp.getAbilities().flying = true;
        sp.onUpdateAbilities();
        if (sp.level() instanceof ServerLevel level) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.PLAYER_DEATH, SoundSource.PLAYERS, 1f,
                    0.8f);
            level.sendParticles(ParticleTypes.SOUL, sp.getX(), sp.getY() + 1, sp.getZ(), 20, 0.4, 0.6, 0.4, 0.02);
        }
        sp.sendSystemMessage(ModChat.line("Sim", ModChat.text("You are a ghost. "),
                ModChat.dim("Use a Revive Stone (/simitem revive_stone) or /simrevive to come back"
                        + " - or a teammate can revive you with theirs, or a Healer by crouching beside you.")));
    }

    /** Who killed him, the way Hypixel names it: the mob's own name, without the star or the health. */
    private static String cause(DamageSource source) {
        if (source == null) {
            return null;
        }
        Entity killer = source.getEntity();
        if (killer == null) {
            killer = source.getDirectEntity();
        }
        if (killer == null) {
            return null;
        }
        SimHealth.Pool pool = SimHealth.get(killer);
        if (pool != null && pool.name != null && !pool.name.isBlank()) {
            return pool.name;
        }
        if (killer.hasCustomName() && killer.getCustomName() != null) {
            return killer.getCustomName().getString().replace("✯", "").trim();
        }
        return killer.getType().getDescription().getString();
    }

    /** "You can't do that while you are dead", at most once a second. Server thread. */
    static void sayDead(ServerPlayer sp) {
        long now = SimVitals.now();
        if (now - lastDeadNote < 20) {
            return;
        }
        lastDeadNote = now;
        sp.sendSystemMessage(Component.literal("You can't use that while you are dead!").withStyle(ChatFormatting.RED));
    }

    // ------------------------------------------------------------------ coming back

    /**
     * Right-click with a Revive Stone. A ghost starts coming back - after {@link SimClassKit#reviveTicks} on
     * {@link SimClassKit#reviveHealth} of the pool - and the stone is used up; a living player is told it is for the
     * dead. Server thread.
     */
    static boolean useReviveStone(ServerPlayer sp) {
        Ghost ghost = GHOSTS.get(sp.getUUID());
        if (ghost == null) {
            // A living player's stone is for a teammate: the nearest ghost in the party (sim parties).
            ServerPlayer dead = nearestGhost(sp, Double.MAX_VALUE);
            if (dead == null) {
                sp.sendSystemMessage(Component.literal("There is nobody to revive!").withStyle(ChatFormatting.RED));
                return false;
            }
            Ghost theirs = GHOSTS.get(dead.getUUID());
            if (theirs.reviveAt >= 0) {
                sp.sendSystemMessage(Component.literal(dead.getGameProfile().name() + " is already being revived!")
                        .withStyle(ChatFormatting.RED));
                return false;
            }
            consumeStone(sp);
            startRevive(sp, dead, theirs);
            return true;
        }
        if (ghost.reviveAt >= 0) {
            sp.sendSystemMessage(Component.literal("You are already being revived!").withStyle(ChatFormatting.RED));
            return false;
        }
        consumeStone(sp);
        startRevive(sp, sp, ghost);
        return true;
    }

    private static void consumeStone(ServerPlayer sp) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = sp.getItemInHand(hand);
            if (REVIVE_STONE_ID.equals(SimItemIds.skyblockId(held))) {
                held.shrink(1);
                break;
            }
        }
    }

    /** Starts {@code dead}'s revive, timed by the reviver's class (a Healer is faster, SimClassKit). */
    private static void startRevive(ServerPlayer reviver, ServerPlayer dead, Ghost ghost) {
        int ticks = SimClassKit.reviveTicks(reviver);
        ghost.reviveAt = SimVitals.now() + ticks;
        ghost.reviveShare = SimClassKit.reviveHealth(reviver);
        String in = String.format(java.util.Locale.US, "%.1f", ticks / 20.0) + "s...";
        if (reviver == dead) {
            dead.sendSystemMessage(Component.literal("Reviving in " + in).withStyle(ChatFormatting.GREEN));
        } else {
            reviver.sendSystemMessage(Component.literal("Reviving " + dead.getGameProfile().name() + " in " + in)
                    .withStyle(ChatFormatting.GREEN));
            dead.sendSystemMessage(Component.literal(reviver.getGameProfile().name() + " is reviving you in " + in)
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** The nearest ghost in the same level as {@code sp} (not him), within {@code range} blocks, or null. */
    private static ServerPlayer nearestGhost(ServerPlayer sp, double range) {
        MinecraftServer server = sp.level().getServer();
        if (server == null || GHOSTS.isEmpty()) {
            return null;
        }
        ServerPlayer best = null;
        double bestDist = range == Double.MAX_VALUE ? Double.MAX_VALUE : range * range;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == sp || p.level() != sp.level() || !GHOSTS.containsKey(p.getUUID())) {
                continue;
            }
            double d = p.distanceToSqr(sp);
            if (d <= bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Back to life on {@code share} of the pool, with a moment's immunity. Server thread. */
    static void revive(ServerPlayer sp, double share) {
        if (GHOSTS.remove(sp.getUUID()) == null) {
            return;
        }
        restore(sp);
        SimVitals.setHealthShare(sp, share);
        SimVitals.immune(sp, 40);
        if (sp.level() instanceof ServerLevel level) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS,
                    0.8f, 1.4f);
            level.sendParticles(ParticleTypes.HEART, sp.getX(), sp.getY() + 1, sp.getZ(), 10, 0.4, 0.6, 0.4, 0.02);
        }
        sp.sendSystemMessage(Component.literal(" ❣ You were revived!").withStyle(ChatFormatting.GREEN));
    }

    /** A ghost's state undone: visible, flight back to what /fly says. */
    private static void restore(ServerPlayer sp) {
        sp.removeEffect(MobEffects.INVISIBILITY);
        boolean fly = SimSurvival.flightOn();
        sp.getAbilities().mayfly = fly || sp.isCreative() || sp.isSpectator();
        if (!sp.getAbilities().mayfly) {
            sp.getAbilities().flying = false;
        }
        sp.onUpdateAbilities();
    }

    private static void serverTick(MinecraftServer server) {
        if (GHOSTS.isEmpty() || !SimState.isActive()) {
            return;
        }
        long now = SimVitals.now();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            Ghost ghost = GHOSTS.get(sp.getUUID());
            if (ghost == null) {
                continue;
            }
            if (!sp.getAbilities().mayfly) {
                sp.getAbilities().mayfly = true;
                sp.onUpdateAbilities();
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                continue;
            }
            // Mobs lose interest in a ghost.
            for (Mob mob : level.getEntitiesOfClass(Mob.class, sp.getBoundingBox().inflate(40),
                    m -> m.getTarget() == sp)) {
                mob.setTarget(null);
            }
            // Below the floor: back up to the room rather than falling for ever.
            if (sp.getY() < level.getMinY() + 2) {
                BlockPos home = SimSurvival.home();
                if (home != null) {
                    sp.teleportTo(level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5, java.util.Set.of(),
                            sp.getYRot(), sp.getXRot(), false);
                }
            }
            if (ghost.reviveAt < 0 && now % 10 == 0) {
                // Healer, Revive: a living Healer crouching beside a ghost brings him back, no stone needed.
                ServerPlayer healer = crouchingHealerNear(sp, level);
                if (healer != null) {
                    startRevive(healer, sp, ghost);
                }
            }
            if (ghost.reviveAt >= 0) {
                if (now % 4 == 0) {
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, sp.getX(), sp.getY() + 1, sp.getZ(), 4, 0.4,
                            0.6, 0.4, 0.02);
                }
                if (now >= ghost.reviveAt) {
                    revive(sp, ghost.reviveShare);
                }
            }
        }
    }

    private static ServerPlayer crouchingHealerNear(ServerPlayer ghost, ServerLevel level) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (p != ghost && p.level() == level && p.isShiftKeyDown() && !GHOSTS.containsKey(p.getUUID())
                    && SimParty.dungeonClass(p) == com.epic60869.sky2m.sim.roomsim.SimStats.DungeonClass.HEALER
                    && p.distanceToSqr(ghost) <= HEALER_REVIVE_RANGE * HEALER_REVIVE_RANGE) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the masks

    private static void masked(ServerPlayer sp, String name, ChatFormatting colour) {
        // Hypixel opens the Spirit Mask's line with "Second Wind Activated! "; the mask timers match the whole line.
        Component lead = name.equals("Spirit Mask")
                ? Component.literal("Second Wind Activated! ").withStyle(ChatFormatting.GREEN) : Component.empty();
        sp.sendSystemMessage(lead.copy().append(Component.literal("Your ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(name).withStyle(colour))
                .append(Component.literal(" saved your life!").withStyle(ChatFormatting.GREEN)));
        if (sp.level() instanceof ServerLevel level) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.5f,
                    1.5f);
        }
    }

    // ------------------------------------------------------------------ reset

    /**
     * Every ghost back to normal and the masks recharged, for a new map or leaving. Safe from the client thread; the
     * players are fixed up on the server when there still is one.
     */
    public static void reset(Minecraft client) {
        BONZO_READY_AT.clear();
        SPIRIT_MASK_READY_AT.clear();
        if (GHOSTS.isEmpty()) {
            return;
        }
        MinecraftServer server = client == null ? null : client.getSingleplayerServer();
        if (server == null) {
            GHOSTS.clear();
            return;
        }
        server.execute(() -> {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (GHOSTS.remove(sp.getUUID()) != null) {
                    restore(sp);
                    SimVitals.refill(sp);
                }
            }
            GHOSTS.clear();
        });
    }
}
