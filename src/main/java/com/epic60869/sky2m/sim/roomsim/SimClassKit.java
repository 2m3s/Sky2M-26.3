package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.SimItemIds;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The five dungeon classes in the Dungeon Sim: each one's passives, its class ability and its Ultimate, scaled by
 * that class's level from the stats profile ({@link SimStats}).
 *
 * <p><b>How you cast them.</b> As Mort tells you on Hypixel - "Right-click the Orb for spells, and Left-click (or
 * Drop) to use your Ultimate!" - the class item is the Dungeon Orb in the last hotbar slot ({@link #ORB_ID}):
 * right-click it for the class ability, left-click it or press the drop key for the Ultimate. The drop key casts
 * the Ultimate with any item in hand, as it does in a Hypixel dungeon, and never drops the item. The class is
 * {@link SimStats.Profile#selected()}, switched with {@code /simclass}, on the stats screen or by a loadout preset.
 *
 * <p><b>What scales.</b> The class's stat bonuses are {@link SimStats#effective}'s, unchanged (and not counted again
 * here). What is here is everything that is not a plain stat: the abilities' damage and the passives' chances
 * grow with the class level, the cooldowns do not (Hypixel's do not either, apart from the Mage's Efficient Spells).
 *
 * <p><b>Damage</b> goes through {@link SimDamage} like every other sim hit - so pools ({@link SimHealth}),
 * minibosses and bosses ({@link SimBoss#onDamage}) take it, numbers show, and a mob without a pool still dies to
 * the vanilla hit underneath.
 *
 * <p><b>Every number below is APPROXIMATE</b> - Hypixel's exact class values are not available offline and change
 * with balance passes; each is commented where it is set.
 *
 * <p>Gameplay state is the integrated server's and changes on its thread; the few fields the client also reads
 * (the Ragnarok buff for the stats screen) are volatile.
 */
public final class SimClassKit {

    /** The class item's skyblock id. Hypixel's own is a per-class item; the sim has one orb for all five. */
    public static final String ORB_ID = "DUNGEON_ORB";

    /** The orb's slot: the last one on the hotbar, where Hypixel puts it. */
    static final int ORB_SLOT = 8;

    // ------------------------------------------------------------------ the numbers (APPROXIMATE)

    /** Class ability cooldowns in seconds. Hypixel's as remembered; none of them costs mana. */
    private static final int EXPLOSIVE_SHOT_COOLDOWN = 40;
    private static final int THROWING_AXE_COOLDOWN = 10;
    private static final int HEALING_CIRCLE_COOLDOWN = 2;
    private static final int GUIDED_SHEEP_COOLDOWN = 30;
    private static final int SEISMIC_WAVE_COOLDOWN = 15;

    /** Ultimate cooldowns in seconds. */
    private static final int RAPID_FIRE_COOLDOWN = 100;
    private static final int RAGNAROK_COOLDOWN = 60;
    private static final int WISH_COOLDOWN = 120;
    private static final int THUNDERSTORM_COOLDOWN = 500;
    private static final int CASTLE_OF_STONE_COOLDOWN = 150;

    /** Guided Sheep's explosion: a magic hit with this base (times 1 + 1% per Mage level) and 0.3 Intelligence
     *  scaling - Implosion's scaling, twice its base, so a sheep is worth about two Wither Impacts. */
    private static final double GUIDED_SHEEP_BASE = 20_000;
    /** Thunderstorm: one strike on every enemy within 10 blocks every half second for 5 seconds, each a magic hit
     *  of this base (times 1 + 1% per Mage level) with 0.3 scaling. */
    private static final double THUNDERSTORM_BASE = 15_000;

    private static final String[] ABILITY = {"Explosive Shot", "Throwing Axe", "Healing Circle", "Guided Sheep",
            "Seismic Wave"};
    private static final String[] ULTIMATE = {"Rapid Fire", "Ragnarok", "Wish", "Thunderstorm", "Castle of Stone"};

    // ------------------------------------------------------------------ state

    /** Server ticks (SimVitals' clock) until which each timed effect runs. */
    /**
     * One player's running effects and passive timers. Per player since sim parties (SimParty): two Mages'
     * Thunderstorms, a Berserk's Ragnarok and a Tank's Castle of Stone each belong to whoever cast them.
     */
    private static final class Kit {
        volatile long ragnarokUntil;
        volatile long castleUntil;
        long rapidFireUntil;
        int rapidFireHits;
        double rapidFireTotal;
        long thunderUntil;
        int thunderHits;
        double thunderTotal;
        long circleUntil;
        Vec3 circleAt;
        long bonePlatingReadyAt;
        long phoenixReadyAt;
        volatile long rekindleUntil;
        volatile double rekindleStrength;
        UUID lustTarget;
        int lustStacks;
        boolean spiritUsed;
        /** Whether this player has cast anything this session, so the effects tick has something to look at. */
        boolean caster;
    }

    private static final java.util.Map<UUID, Kit> KITS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Kit kit(ServerPlayer sp) {
        return KITS.computeIfAbsent(sp.getUUID(), id -> new Kit());
    }

    /** A thrown thing in flight: an Explosive Shot, a Throwing Axe, a Guided Sheep. Server thread. */
    private static final class Projectile {
        final String kind;
        final UUID owner;
        final Vec3 dir;
        final double speed;
        Vec3 pos;
        int ticksLeft;

        Projectile(String kind, UUID owner, Vec3 pos, Vec3 dir, double speed, int ticks) {
            this.kind = kind;
            this.owner = owner;
            this.pos = pos;
            this.dir = dir;
            this.speed = speed;
            this.ticksLeft = ticks;
        }
    }

    private static final List<Projectile> PROJECTILES = new ArrayList<>();

    /**
     * Something that can save a player from a lethal hit, for the death system (step 5): it answers the share of
     * the pool he lives on with, or 0 for no save. Asked in order before the Phoenix.
     */
    @FunctionalInterface
    public interface DeathSave {
        double trySave(ServerPlayer sp);
    }

    private static final List<DeathSave> DEATH_SAVES = new CopyOnWriteArrayList<>();

    private static boolean wasAttackingWithOrb;

    private SimClassKit() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimClassKit::serverTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simclass")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .then(ClientCommands.argument("class", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (SimStats.DungeonClass c : SimStats.DungeonClass.values()) {
                                        b.suggest(c.label.toLowerCase(Locale.ROOT));
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "class");
                                    SimStats.DungeonClass c = SimStats.classByName(name);
                                    if (c == null) {
                                        ModChat.send("Sim", ModChat.text("No class called "), ModChat.value(name),
                                                ModChat.dim(" - archer, berserk, healer, mage or tank"));
                                        return 0;
                                    }
                                    select(Minecraft.getInstance(), c);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            describe(SimStats.profile().selected());
                            ModChat.send("Sim", ModChat.dim("/simclass <archer|berserk|healer|mage|tank>"));
                            return 1;
                        })));
    }

    /** Forgets every running effect and cooldown-like timer, for leaving the sim. */
    public static void reset() {
        KITS.clear();
        synchronized (PROJECTILES) {
            PROJECTILES.clear();
        }
    }

    private static int level() {
        SimStats.Profile p = SimStats.profile();
        return p.classLevel(p.selected());
    }

    private static SimStats.DungeonClass selected() {
        return SimStats.profile().selected();
    }

    /** This player's class level and class: the host's own, or a party guest's (SimParty). Server thread. */
    private static int level(ServerPlayer sp) {
        return SimParty.classLevel(sp);
    }

    private static SimStats.DungeonClass selected(ServerPlayer sp) {
        return SimParty.dungeonClass(sp);
    }

    public static String abilityName(SimStats.DungeonClass c) {
        return ABILITY[c.ordinal()];
    }

    public static String ultimateName(SimStats.DungeonClass c) {
        return ULTIMATE[c.ordinal()];
    }

    static boolean isUltimate(String ability) {
        for (String u : ULTIMATE) {
            if (u.equals(ability)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ choosing a class (client thread)

    /** Switches class and puts that class's armour on. */
    public static void select(Minecraft client, SimStats.DungeonClass c) {
        SimStats.setSelected(c);
        SimGear.equip(client);
        ModChat.send("Sim", ModChat.text("Class: "), ModChat.value(c.label + " "
                + SimStats.profile().classLevel(c)));
        describe(c);
    }

    /** One line of what the class does, with this level's numbers. */
    static void describe(SimStats.DungeonClass c) {
        ModChat.send("Sim", ModChat.value(c.label), ModChat.dim(" - " + passivesText(c)
                + " | Orb right-click: " + abilityName(c) + " | left-click or drop: " + ultimateName(c)));
    }

    /** The passives in a few words, for chat and the loadout screen. */
    public static String passivesText(SimStats.DungeonClass c) {
        int l = SimStats.profile().classLevel(c);
        return switch (c) {
            case ARCHER -> String.format(Locale.ROOT, "Doubleshot %.0f%%, Bone Plating -%d, Bouncy Arrows %.0f%%",
                    doubleshotChance(l) * 100, (int) bonePlating(l), bouncyChance(l) * 100);
            case BERSERK -> String.format(Locale.ROOT, "Bloodlust %.1f%% heal a hit, Lust for Blood +%.1f%% a hit "
                    + "(5 max), Weapon Master (class bonus)", bloodlust(l) * 100, lustPerStack(l) * 100);
            case HEALER -> String.format(Locale.ROOT, "Renew (class regen), Healing Aura %.1f%% every 2s, Revive",
                    healingAura(l) * 100);
            case MAGE -> String.format(Locale.ROOT, "Mage Staff beam, Efficient Spells -%.0f%% cooldowns, "
                    + "-%.0f%% mana costs", efficientSpells(l) * 100, mageManaSaving(l) * 100);
            case TANK -> String.format(Locale.ROOT, "Protective Barrier -%.0f%% above half health, Taunt, Diversion",
                    protectiveBarrier(l) * 100);
        };
    }

    /**
     * Says the class at the start of a run and makes sure the orb is there - what {@code SimClass.applyMageOnly}
     * used to do when Mage was the only class.
     */
    public static void announce(Minecraft client) {
        SimStats.DungeonClass c = selected();
        ModChat.send("Sim", ModChat.text("Class: "), ModChat.value(c.label + " " + level()),
                ModChat.dim(" (Orb: " + abilityName(c) + " / " + ultimateName(c) + ")"));
        ensureOrb(client);
    }

    /** Puts a Dungeon Orb in the last hotbar slot if he has none (anywhere else if that slot is taken). */
    public static void ensureOrb(Minecraft client) {
        if (!SimState.canAct(client) || client.player == null) {
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        UUID id = client.player.getUUID();
        server.execute(() -> ensureOrbOnServer(server.getPlayerList().getPlayer(id)));
    }

    /**
     * A new floor starts with an empty inventory: everything in the main inventory, hotbar and off hand from the last
     * sim is cleared (worn armour stays - the wardrobe owns it, and is put on when none of it is worn), and the class
     * orb is given back. Mort's class presets
     * fill it again. Server thread, every player in the sim (party guests too).
     */
    public static void resetInventories(ServerLevel level) {
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            var inv = sp.getInventory();
            for (int i = 0; i < 36; i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            sp.containerMenu.setCarried(ItemStack.EMPTY);
            ensureOrbOnServer(sp);
            // And his wardrobe's armour on, if none of it is: a floor played in empty armour slots counted none of
            // his gear (SimGear.dressIfBare).
            SimGear.dressIfBare(sp);
            sp.inventoryMenu.broadcastChanges();
        }
    }

    /** {@link #ensureOrb} on the server thread, for any player in the sim (a party guest too). */
    static void ensureOrbOnServer(ServerPlayer sp) {
        ItemStack orb = SimItems.build(ORB_ID);
        if (sp == null || orb.isEmpty()) {
            return;
        }
        var inv = sp.getInventory();
        for (int i = 0; i < 36; i++) {
            if (ORB_ID.equals(SimItemIds.skyblockId(inv.getItem(i)))) {
                return;
            }
        }
        if (inv.getItem(ORB_SLOT).isEmpty()) {
            inv.setItem(ORB_SLOT, orb);
        } else if (!inv.add(orb)) {
            sp.drop(orb, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
    }

    // ------------------------------------------------------------------ input (client thread)

    /**
     * The orb's left click and the drop key, from {@code SimClass}'s tick. Returns true when the left click was the
     * orb's, so the Mage beam does not also fire.
     */
    static boolean clientInput(Minecraft client) {
        if (client.player == null || client.options == null) {
            return false;
        }
        boolean ultimate = false;
        // The drop key casts the Ultimate whatever is in hand, and nothing drops - Hypixel's dungeon behaviour.
        while (client.options.keyDrop.consumeClick()) {
            ultimate = true;
        }
        boolean orb = ORB_ID.equals(SimItemIds.skyblockId(client.player.getMainHandItem()));
        boolean attacking = client.options.keyAttack.isDown();
        if (orb) {
            // The orb has no swing: the click is the Ultimate, never a punch or a block break.
            while (client.options.keyAttack.consumeClick()) {
                // drained
            }
            if (attacking && !wasAttackingWithOrb) {
                ultimate = true;
            }
        }
        wasAttackingWithOrb = orb && attacking;
        if (ultimate && SimParty.guest()) {
            // A party guest: the host's server casts it for him.
            SimParty.sendAction("ultimate", "");
        } else if (ultimate) {
            var server = client.getSingleplayerServer();
            if (server != null) {
                UUID id = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(id);
                    if (sp != null && SimAbilities.simServer(sp)) {
                        ultimate(sp);
                    }
                });
            }
        }
        return orb;
    }

    // ------------------------------------------------------------------ the class ability (server thread)

    /** The orb's right click. Always consumes the click. */
    static boolean useAbility(ServerPlayer sp) {
        if (SimDeath.isGhost(sp)) {
            SimDeath.sayDead(sp);
            return false;
        }
        SimStats.DungeonClass c = selected(sp);
        String name = abilityName(c);
        int cooldown = switch (c) {
            case ARCHER -> EXPLOSIVE_SHOT_COOLDOWN;
            case BERSERK -> THROWING_AXE_COOLDOWN;
            case HEALER -> HEALING_CIRCLE_COOLDOWN;
            case MAGE -> GUIDED_SHEEP_COOLDOWN;
            case TANK -> SEISMIC_WAVE_COOLDOWN;
        };
        if (!SimVitals.cast(sp, name, 0, cooldown * 20)) {
            return true;
        }
        used(sp, name);
        Kit k = kit(sp);
        k.caster = true;
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        switch (c) {
            // A fast explosive arrow, 40 blocks at most, blowing up on the first enemy or wall.
            case ARCHER -> launch(new Projectile("shot", sp.getUUID(), eye, look, 3.0, 14));
            // The axe flies 25 blocks and stops in the first enemy.
            case BERSERK -> launch(new Projectile("axe", sp.getUUID(), eye, look, 1.5, 17));
            case HEALER -> {
                k.circleAt = sp.position();
                k.circleUntil = SimVitals.now() + 10 * 20;
            }
            // The sheep walks out slowly and explodes on what it meets, 20 blocks at most.
            case MAGE -> launch(new Projectile("sheep", sp.getUUID(), sp.position().add(0, 0.5, 0),
                    new Vec3(look.x, 0, look.z).normalize(), 0.8, 25));
            case TANK -> seismicWave(sp, level, look);
        }
        return true;
    }

    private static void launch(Projectile p) {
        synchronized (PROJECTILES) {
            PROJECTILES.add(p);
        }
    }

    /** "Used Guided Sheep!" - the line Hypixel prints, which the mod's class cooldown display reads. */
    private static void used(ServerPlayer sp, String name) {
        sp.sendSystemMessage(Component.literal("§aUsed §6" + name + "§a!"));
    }

    /**
     * Seismic Wave: every enemy up to 8 blocks in front of him (a 120-degree cone) takes a share of his melee hit -
     * 50% plus 1% per Tank level - is knocked up, and turns on him (it is a taunt as well).
     */
    private static void seismicWave(ServerPlayer sp, ServerLevel level, Vec3 look) {
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
        Vec3 at = sp.position();
        for (double d = 1; d <= 8; d += 0.5) {
            Vec3 p = at.add(flat.scale(d));
            level.sendParticles(ParticleTypes.CLOUD, p.x, p.y + 0.2, p.z, 3, 0.3, 0.05, 0.3, 0.02);
        }
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 0.5f, 0.6f);
        List<LivingEntity> hit = new ArrayList<>();
        for (LivingEntity e : enemies(level, sp, new AABB(at, at).inflate(8))) {
            Vec3 to = e.position().subtract(at);
            double dist = to.horizontalDistance();
            if (dist > 8 || dist < 0.01 || new Vec3(to.x, 0, to.z).normalize().dot(flat) < 0.5) {
                continue;
            }
            hit.add(e);
        }
        SimDamage.strike(sp, "Seismic Wave", SimDamage.Type.MELEE, meleeId(sp), 0.5 + 0.01 * level(sp), () -> {
            for (LivingEntity e : hit) {
                hurt(level, sp, e);
            }
        });
        for (LivingEntity e : hit) {
            if (e.isAlive()) {
                e.push(0, 0.5, 0);
                e.syncVelocity = true;
                if (e instanceof Mob m) {
                    m.setTarget(sp);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the Ultimate (server thread)

    static void ultimate(ServerPlayer sp) {
        if (SimDeath.isGhost(sp)) {
            SimDeath.sayDead(sp);
            return;
        }
        SimStats.DungeonClass c = selected(sp);
        String name = ultimateName(c);
        int cooldown = switch (c) {
            case ARCHER -> RAPID_FIRE_COOLDOWN;
            case BERSERK -> RAGNAROK_COOLDOWN;
            case HEALER -> WISH_COOLDOWN;
            case MAGE -> THUNDERSTORM_COOLDOWN;
            case TANK -> CASTLE_OF_STONE_COOLDOWN;
        };
        if (!SimVitals.cast(sp, name, 0, cooldown * 20)) {
            return;
        }
        used(sp, name);
        Kit k = kit(sp);
        k.caster = true;
        int l = level(sp);
        long now = SimVitals.now();
        ServerLevel level = (ServerLevel) sp.level();
        switch (c) {
            // A stream of arrows at the crosshair, one every 2 ticks for 4 seconds plus 0.04 s per Archer level
            // (6 s at 50), each worth 25% plus 0.5% per level of an arrow (50% at 50).
            case ARCHER -> {
                k.rapidFireUntil = now + 80 + (long) (0.8 * l);
                k.rapidFireHits = 0;
                k.rapidFireTotal = 0;
            }
            // +100 Strength plus 4 per Berserk level (+300 at 50) and +20% melee damage for 10 seconds. Hypixel's
            // also summons fighting allies, which the sim does not.
            case BERSERK -> {
                k.ragnarokUntil = now + 200;
                burst(level, sp, ParticleTypes.ANGRY_VILLAGER, 20);
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.RAVAGER_ROAR,
                        SoundSource.PLAYERS, 0.8f, 1.0f);
            }
            // Every player is healed to full and gets absorption of 10% plus 0.4% per Healer level of their health
            // (30% at 50) for 30 seconds.
            case HEALER -> {
                for (ServerPlayer p : sp.level().getServer().getPlayerList().getPlayers()) {
                    SimVitals.healFraction(p, 1.0);
                    double max = SimVitals.healthFraction(p) > 0 ? maxHealth(p) : 0;
                    SimVitals.addAbsorption(p, max * (0.1 + 0.004 * l), 30 * 20);
                    burst((ServerLevel) p.level(), p, ParticleTypes.HEART, 12);
                }
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.PLAYER_LEVELUP,
                        SoundSource.PLAYERS, 0.8f, 1.4f);
            }
            case MAGE -> {
                k.thunderUntil = now + 100;
                k.thunderHits = 0;
                k.thunderTotal = 0;
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER,
                        SoundSource.PLAYERS, 0.7f, 1.0f);
            }
            // Damage taken cut by 60% plus 0.4% per Tank level (80% at 50) for 5 seconds.
            case TANK -> {
                k.castleUntil = now + 100;
                burst(level, sp, ParticleTypes.WAX_ON, 30);
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ANVIL_LAND,
                        SoundSource.PLAYERS, 0.6f, 0.7f);
            }
        }
    }

    private static double maxHealth(ServerPlayer sp) {
        double[] snap = SimVitals.snapshot(sp.getUUID());
        return snap == null ? 0 : snap[1];
    }

    // ------------------------------------------------------------------ the tick

    private static void serverTick(MinecraftServer server) {
        if (!SimState.isActive() || KITS.isEmpty() && !anyPassiveClass(server)) {
            return;
        }
        long now = SimVitals.now();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!SimAbilities.simServer(sp)) {
                return;
            }
            ServerLevel level = (ServerLevel) sp.level();
            passives(sp, level, now);
            Kit k = KITS.get(sp.getUUID());
            if (k != null && k.caster) {
                effects(sp, k, level, now);
            }
        }
        tickProjectiles(server);
    }

    /** Taunt and Healing Aura are the passives that act on their own; the rest act on a hit. */
    private static boolean anyPassiveClass(MinecraftServer server) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            SimStats.DungeonClass c = selected(sp);
            if (c == SimStats.DungeonClass.TANK || c == SimStats.DungeonClass.HEALER) {
                return true;
            }
        }
        return false;
    }

    private static void passives(ServerPlayer sp, ServerLevel level, long now) {
        SimStats.DungeonClass c = selected(sp);
        int l = level(sp);
        if (c == SimStats.DungeonClass.TANK && now % 20 == 0) {
            // Taunt: every second, every pooled enemy within 12 blocks turns on the Tank.
            for (LivingEntity e : enemies(level, sp, sp.getBoundingBox().inflate(12))) {
                if (e instanceof Mob m && SimHealth.isPooled(e) && m.getTarget() != sp) {
                    m.setTarget(sp);
                }
            }
        }
        if (c == SimStats.DungeonClass.HEALER && now % 40 == 0) {
            // Healing Aura: every 2 seconds, everyone within 8 blocks (himself included) heals a share of their pool.
            for (ServerPlayer p : sp.level().getServer().getPlayerList().getPlayers()) {
                if (p.level() == sp.level() && p.distanceToSqr(sp) <= 64) {
                    SimVitals.healFraction(p, healingAura(l));
                }
            }
        }
    }

    private static void effects(ServerPlayer sp, Kit k, ServerLevel level, long now) {
        int l = level(sp);
        if (now < k.rapidFireUntil && now % 2 == 0) {
            rapidFireShot(sp, k, level, l);
            if (now + 2 >= k.rapidFireUntil && k.rapidFireHits > 0) {
                sp.sendSystemMessage(Component.literal("§7Your §cRapid Fire §7hit §c" + k.rapidFireHits
                        + " §7times for §c" + String.format(Locale.US, "%,.1f", k.rapidFireTotal) + " §7damage."));
            }
        }
        if (now < k.thunderUntil && now % 10 == 0) {
            List<LivingEntity> targets = enemies(level, sp, sp.getBoundingBox().inflate(10));
            int[] struck = {0};
            k.thunderTotal += SimDamage.ability(sp, null, magicId(sp),
                    THUNDERSTORM_BASE * (1 + 0.01 * l), 0.3, () -> {
                        for (LivingEntity e : targets) {
                            if (e.distanceToSqr(sp) > 100) {
                                continue;
                            }
                            for (double y = 0; y < 6; y += 0.5) {
                                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + y, e.getZ(),
                                        2, 0.1, 0.1, 0.1, 0.0);
                            }
                            hurt(level, sp, e);
                            struck[0]++;
                        }
                    });
            k.thunderHits += struck[0];
            if (now + 10 >= k.thunderUntil && k.thunderHits > 0) {
                sp.sendSystemMessage(Component.literal("§7Your §cThunderstorm §7hit §c" + k.thunderHits
                        + " §7times for §c" + String.format(Locale.US, "%,.1f", k.thunderTotal) + " §7damage."));
            }
            if (!targets.isEmpty()) {
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT,
                        SoundSource.PLAYERS, 0.4f, 1.2f);
            }
        }
        Vec3 circleAt = k.circleAt;
        if (circleAt != null && now < k.circleUntil) {
            if (now % 10 == 0) {
                for (int i = 0; i < 24; i++) {
                    double a = i * Math.PI * 2 / 24;
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, circleAt.x + Math.cos(a) * 6,
                            circleAt.y + 0.2, circleAt.z + Math.sin(a) * 6, 1, 0, 0, 0, 0);
                }
            }
            if (now % 20 == 0) {
                // Healing Circle: 1.5% plus 0.03% per Healer level (3% at 50) of the pool a second, for players
                // standing in it, for 10 seconds.
                for (ServerPlayer p : sp.level().getServer().getPlayerList().getPlayers()) {
                    if (p.level() == sp.level() && p.position().distanceToSqr(circleAt) <= 36) {
                        SimVitals.healFraction(p, 0.015 + 0.0003 * l);
                    }
                }
            }
        }
        if (now < k.castleUntil && now % 5 == 0) {
            burst(level, sp, ParticleTypes.WAX_ON, 4);
        }
        if (now < k.ragnarokUntil && now % 10 == 0) {
            burst(level, sp, ParticleTypes.ANGRY_VILLAGER, 1);
        }
    }

    private static void rapidFireShot(ServerPlayer sp, Kit k, ServerLevel level, int l) {
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        Vec3 stop = wallStop(level, sp, eye, eye.add(look.scale(40)));
        LivingEntity target = firstAlong(level, sp, eye, stop);
        Vec3 end = target != null ? target.getBoundingBox().getCenter() : stop;
        line(level, eye, end, ParticleTypes.CRIT, 1.5);
        if (target == null) {
            return;
        }
        k.rapidFireTotal += SimDamage.strike(sp, null, SimDamage.Type.ARROW, bowId(sp), 0.25 + 0.005 * l,
                () -> hurt(level, sp, target));
        k.rapidFireHits++;
    }

    private static void tickProjectiles(MinecraftServer server) {
        List<Projectile> live;
        synchronized (PROJECTILES) {
            if (PROJECTILES.isEmpty()) {
                return;
            }
            live = new ArrayList<>(PROJECTILES);
        }
        List<Projectile> done = new ArrayList<>();
        for (Projectile p : live) {
            ServerPlayer sp = server.getPlayerList().getPlayer(p.owner);
            if (sp == null) {
                done.add(p);
                continue;
            }
            ServerLevel level = (ServerLevel) sp.level();
            Vec3 next = p.pos.add(p.dir.scale(p.speed));
            Vec3 stop = wallStop(level, sp, p.pos, next);
            boolean wall = stop.distanceToSqr(next) > 1e-4;
            LivingEntity hit = firstAlong(level, sp, p.pos, stop);
            ParticleOptions trail = switch (p.kind) {
                case "shot" -> ParticleTypes.FLAME;
                case "axe" -> ParticleTypes.CRIT;
                default -> ParticleTypes.CLOUD;
            };
            line(level, p.pos, hit != null ? hit.getBoundingBox().getCenter() : stop, trail, 0.5);
            p.pos = hit != null ? hit.getBoundingBox().getCenter() : stop;
            if (hit != null || wall || --p.ticksLeft <= 0) {
                land(sp, level, p, hit);
                done.add(p);
            }
        }
        synchronized (PROJECTILES) {
            PROJECTILES.removeAll(done);
        }
    }

    private static void land(ServerPlayer sp, ServerLevel level, Projectile p, LivingEntity hit) {
        int l = level(sp);
        switch (p.kind) {
            // Explosive Shot: every enemy within 4 blocks of where it lands takes 150% plus 1% per Archer level of
            // an arrow (200% at 50).
            case "shot" -> {
                explode(level, p.pos, SoundEvents.GENERIC_EXPLODE.value());
                List<LivingEntity> in = within(level, sp, p.pos, 4);
                SimDamage.strike(sp, "Explosive Shot", SimDamage.Type.ARROW, bowId(sp), 1.5 + 0.01 * l, () -> {
                    for (LivingEntity e : in) {
                        hurt(level, sp, e);
                    }
                });
            }
            // Throwing Axe: the one enemy it hits takes 100% plus 2% per Berserk level of a melee hit (200% at 50).
            case "axe" -> {
                if (hit != null) {
                    SimDamage.strike(sp, "Throwing Axe", SimDamage.Type.MELEE, meleeId(sp), 1.0 + 0.02 * l,
                            () -> hurt(level, sp, hit));
                    level.playSound(null, p.pos.x, p.pos.y, p.pos.z, SoundEvents.PLAYER_ATTACK_STRONG,
                            SoundSource.PLAYERS, 0.8f, 1.0f);
                }
            }
            // Guided Sheep: a 5-block explosion of magic damage (see GUIDED_SHEEP_BASE).
            default -> {
                explode(level, p.pos, SoundEvents.GENERIC_EXPLODE.value());
                level.sendParticles(ParticleTypes.CLOUD, p.pos.x, p.pos.y, p.pos.z, 30, 1.5, 1.0, 1.5, 0.05);
                List<LivingEntity> in = within(level, sp, p.pos, 5);
                SimDamage.ability(sp, "Guided Sheep", magicId(sp), GUIDED_SHEEP_BASE * (1 + 0.01 * l), 0.3, () -> {
                    for (LivingEntity e : in) {
                        hurt(level, sp, e);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ the passives' numbers (APPROXIMATE)

    /** Archer, Doubleshot: a chance of a second arrow's worth on an arrow hit - 0.5% per level (25% at 50). */
    static double doubleshotChance(int l) {
        return 0.005 * l;
    }

    /** Archer, Bouncy Arrows: a chance an arrow hit bounces to the nearest other enemy within 6 blocks for half
     *  its damage - 0.4% per level (20% at 50). */
    static double bouncyChance(int l) {
        return 0.004 * l;
    }

    /** Archer, Bone Plating: the next hit is reduced by 250 plus 15 per level (1,000 at 50), every 5 seconds. */
    static double bonePlating(int l) {
        return 250 + 15.0 * l;
    }

    /** Berserk, Bloodlust: a melee hit heals 0.04% of the pool per level (2% at 50). */
    static double bloodlust(int l) {
        return 0.0004 * l;
    }

    /** Berserk, Lust for Blood: each hit on the same enemy in a row adds 2% plus 0.06% per level (5% at 50), five
     *  stacks at most. Weapon Master is the class's Strength and melee bonus in {@link SimStats#effective}. */
    static double lustPerStack(int l) {
        return 0.02 + 0.0006 * l;
    }

    /** Healer, Healing Aura: 0.4% plus 0.02% per level (1.4% at 50) of the pool every 2 seconds. Renew is the
     *  class's health regeneration bonus in {@link SimStats#effective}. */
    static double healingAura(int l) {
        return 0.004 + 0.0002 * l;
    }

    /** Mage, Efficient Spells: cooldowns cut by 25% plus 0.5% per level (50% at 50). */
    static double efficientSpells(int l) {
        return 0.25 + 0.005 * l;
    }

    /** Mage, Ultimate Wise style: mana costs cut by 0.3% per level (15% at 50), on top of the items' own
     *  Ultimate Wise. */
    static double mageManaSaving(int l) {
        return 0.003 * l;
    }

    /** Tank, Protective Barrier: damage cut by 0.4% per level (20% at 50) while above half health. */
    static double protectiveBarrier(int l) {
        return 0.004 * l;
    }

    // ------------------------------------------------------------------ hooks for the rest of the sim

    /** Ragnarok's Strength, for {@link SimStats#effective}. Any thread. */
    static double bonusStrength(SimStats.DungeonClass c, UUID who, int classLevel) {
        return c == SimStats.DungeonClass.BERSERK && SimVitals.now() < ragnarokUntil(who)
                ? 100 + 4.0 * classLevel : 0;
    }

    /** When this player's Ragnarok ends; null is this client's own player (the stats screens). */
    private static long ragnarokUntil(UUID who) {
        if (who == null) {
            Minecraft mc = Minecraft.getInstance();
            who = mc == null || mc.player == null ? null : mc.player.getUUID();
        }
        Kit k = who == null ? null : KITS.get(who);
        return k == null ? 0 : k.ragnarokUntil;
    }

    /** Ragnarok's melee damage bonus, in percent. */
    static double bonusMelee(SimStats.DungeonClass c, UUID who) {
        return c == SimStats.DungeonClass.BERSERK && SimVitals.now() < ragnarokUntil(who) ? 20 : 0;
    }

    /** What every mana cost is multiplied by: the Mage's saving and the Sheep's Mana Saver (20%). */
    static double manaCostMultiplier(ServerPlayer sp) {
        double m = 1.0;
        if (selected(sp) == SimStats.DungeonClass.MAGE) {
            m *= 1 - mageManaSaving(level(sp));
        }
        SimParty.Member member = SimParty.member(sp);
        if (member.pet().perk() == SimGear.Perk.MANA_SAVER) {
            // NEU petnums otherNums[0]: 20% at a legendary level 100 Sheep.
            m *= 1 - member.pet().perkNumber(0, 20) / 100.0;
        }
        // Wise Dragon's Wise Blood: abilities have 2/3 of the mana cost.
        m *= SimGear.manaCostMultiplier(member.gear());
        return m;
    }

    /** What a cooldown is multiplied by: Efficient Spells for a Mage, on everything but the Ultimates. */
    static double cooldownMultiplier(ServerPlayer sp, String ability) {
        if (selected(sp) != SimStats.DungeonClass.MAGE || isUltimate(ability)) {
            return 1.0;
        }
        return 1 - efficientSpells(level(sp));
    }

    /**
     * A hit he is about to take, after Defense: Castle of Stone, Protective Barrier and Bone Plating. Server thread.
     */
    static double incoming(ServerPlayer sp, double health, double maxHealth, double taken) {
        SimStats.DungeonClass c = selected(sp);
        int l = level(sp);
        Kit k = kit(sp);
        long now = SimVitals.now();
        if (c == SimStats.DungeonClass.TANK) {
            if (now < k.castleUntil) {
                taken *= 1 - Math.min(0.95, 0.6 + 0.004 * l);
            }
            if (maxHealth > 0 && health / maxHealth > 0.5) {
                taken *= 1 - protectiveBarrier(l);
            }
        }
        if (c == SimStats.DungeonClass.ARCHER && now >= k.bonePlatingReadyAt) {
            taken = Math.max(0, taken - bonePlating(l));
            k.bonePlatingReadyAt = now + 100;
        }
        return taken;
    }

    /**
     * A plain melee or arrow hit he has just rolled (not an ability's), for the passives that act per hit: Lust for
     * Blood and Bloodlust (Berserk), Doubleshot and Bouncy Arrows (Archer). Server thread, inside the damage event.
     */
    static SimDamage.Hit onPlayerHit(ServerPlayer sp, SimDamage.Type type, String weaponId, LivingEntity target,
                                     SimDamage.Hit hit) {
        SimStats.DungeonClass c = selected(sp);
        int l = level(sp);
        Kit k = kit(sp);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (c == SimStats.DungeonClass.BERSERK && type == SimDamage.Type.MELEE && target != null) {
            if (target.getUUID().equals(k.lustTarget)) {
                k.lustStacks = Math.min(5, k.lustStacks + 1);
            } else {
                k.lustTarget = target.getUUID();
                k.lustStacks = 0;
            }
            SimVitals.healFraction(sp, bloodlust(l));
            if (k.lustStacks > 0) {
                return new SimDamage.Hit(hit.perStrike() * (1 + k.lustStacks * lustPerStack(l)), hit.crit(),
                        hit.strikes());
            }
        }
        if (c == SimStats.DungeonClass.ARCHER && type == SimDamage.Type.ARROW) {
            if (target != null && rng.nextDouble() < bouncyChance(l) && sp.level() instanceof ServerLevel level) {
                bounce(sp, level, target, weaponId);
            }
            if (rng.nextDouble() < doubleshotChance(l)) {
                return new SimDamage.Hit(hit.perStrike(), hit.crit(), hit.strikes() + 1);
            }
        }
        return hit;
    }

    /** Bouncy Arrows' second hit, dealt on the next server task so it is not inside this hit's damage event. */
    private static void bounce(ServerPlayer sp, ServerLevel level, LivingEntity from, String weaponId) {
        LivingEntity next = null;
        double best = 36;
        for (LivingEntity e : enemies(level, sp, from.getBoundingBox().inflate(6))) {
            double d = e.distanceToSqr(from);
            if (e != from && d < best) {
                best = d;
                next = e;
            }
        }
        if (next == null) {
            return;
        }
        LivingEntity target = next;
        Vec3 a = from.getBoundingBox().getCenter();
        level.getServer().execute(() -> {
            if (!target.isAlive()) {
                return;
            }
            line(level, a, target.getBoundingBox().getCenter(), ParticleTypes.CRIT, 0.5);
            SimDamage.strike(sp, null, SimDamage.Type.ARROW, weaponId, 0.5, () -> hurt(level, sp, target));
        });
    }

    /**
     * Adds a death save, asked before the Phoenix's. For the death and revive system (step 5): a Bonzo or Spirit
     * Mask, the Spirit pet's first-death help, a Healer's revive.
     */
    public static void registerDeathSave(DeathSave save) {
        DEATH_SAVES.add(save);
    }

    /**
     * Whether a lethal hit is survived, and on what share of the pool. The Phoenix's Rekindle is the built-in one:
     * survive on 1 Health, immune with +20 Strength for 4 s (level 100), then a 60-second cooldown. Server thread.
     *
     * @return the share of the pool to live on, or 0 to die
     */
    static double tryDeathSave(ServerPlayer sp) {
        for (DeathSave save : DEATH_SAVES) {
            double got = save.trySave(sp);
            if (got > 0) {
                return got;
            }
        }
        long now = SimVitals.now();
        Kit k = kit(sp);
        SimGear.PetBuild pet = SimParty.member(sp).gear().pet();
        if (pet.pet().perk() == SimGear.Perk.DEATH_SAVE && now >= k.phoenixReadyAt) {
            // Rekindle as Hypixel intends it (the wiki's note that it misses most dungeon mobs' hits is ignored, at
            // the user's request): any lethal hit is survived on 1 Health, then immunity and +Strength for the
            // level's time (20 Strength for 4 s at 100), and a 60 s cooldown that a pet swap does not reset.
            k.phoenixReadyAt = now + PHOENIX_COOLDOWN_TICKS;
            k.rekindleUntil = now + pet.rekindleTicks();
            k.rekindleStrength = pet.rekindleStrength();
            SimVitals.immune(sp, pet.rekindleTicks());
            if (sp.level() instanceof ServerLevel level) {
                burst(level, sp, ParticleTypes.FLAME, 40);
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS,
                        0.6f, 1.2f);
            }
            sp.sendSystemMessage(Component.literal("§6Your Phoenix Pet saved you from certain death!"));
            // The smallest share: SimVitals keeps him on at least 1 Health.
            return Double.MIN_VALUE;
        }
        return 0;
    }

    /** Rekindle's cooldown: 60 seconds (research/pets.md). */
    static final int PHOENIX_COOLDOWN_TICKS = 60 * 20;

    /** Phoenix Rekindle's Strength while it lasts; null is this client's own player (the stats screens). */
    static double rekindleStrength(UUID who) {
        if (who == null) {
            Minecraft mc = Minecraft.getInstance();
            who = mc == null || mc.player == null ? null : mc.player.getUUID();
        }
        Kit k = who == null ? null : KITS.get(who);
        return k != null && SimVitals.now() < k.rekindleUntil ? k.rekindleStrength : 0;
    }

    /** Ticks until this player's Rekindle is ready again (0 = ready). */
    public static long phoenixCooldown(ServerPlayer sp) {
        return Math.max(0, kit(sp).phoenixReadyAt - SimVitals.now());
    }

    /** Spirit pet: whether its first-death help is still unused this run. A hook for the death system. */
    public static boolean spiritSaveAvailable(ServerPlayer sp) {
        return SimParty.member(sp).pet().perk() == SimGear.Perk.FIRST_DEATH_SAVE && !kit(sp).spiritUsed;
    }

    /**
     * Hypixel's score rule (the wiki's Dungeon Score page): the run's first death costs 1 skill point instead of 2 when
     * that player has a Legendary Spirit pet in their pets menu, out or not. The sim has no owned-pets list (every pet
     * can be picked), so the pets he carries this run stand for it: his active pet and his Pet Rules' pets.
     */
    public static boolean hasSpiritForScore(ServerPlayer sp) {
        SimGear.ClassGear gear = SimParty.member(sp).gear();
        return isLegendarySpirit(gear.pet()) || gear.rule() != null
                && (isLegendarySpirit(gear.rule().a()) || isLegendarySpirit(gear.rule().b()));
    }

    private static boolean isLegendarySpirit(SimGear.PetBuild build) {
        return build != null && build.pet() != null && "SPIRIT".equals(build.pet().id())
                && "LEGENDARY".equals(build.pet().rarity());
    }

    /** Spends the Spirit pet's first-death help. */
    public static void useSpiritSave(ServerPlayer sp) {
        kit(sp).spiritUsed = true;
    }

    /**
     * Healer, Revive: how long reviving a teammate takes, in ticks - 5 seconds, cut by 1% per Healer level for a
     * Healer (2.5 s at 50). A hook for the revive system.
     */
    public static int reviveTicks(ServerPlayer reviver) {
        return selected(reviver) == SimStats.DungeonClass.HEALER
                ? (int) Math.round(100 * (1 - 0.01 * level(reviver))) : 100;
    }

    /** Healer, Revive: the share of the pool a revived teammate comes back on - 20%, plus 0.6% per Healer level. */
    public static double reviveHealth(ServerPlayer reviver) {
        return selected(reviver) == SimStats.DungeonClass.HEALER ? 0.2 + 0.006 * level(reviver) : 0.2;
    }

    /**
     * Tank, Diversion: the share of a nearby teammate's damage the Tank takes for them - 10% plus 0.4% per level
     * (30% at 50). Taken off a hit on a teammate by {@code SimVitals} in a sim party.
     */
    public static double diversionShare(ServerPlayer tank) {
        return selected(tank) == SimStats.DungeonClass.TANK ? 0.1 + 0.004 * level(tank) : 0;
    }

    // ------------------------------------------------------------------ helpers

    /** The item a bow ability shoots with: his Terminator if one is on the hotbar, else what is in hand. */
    private static String bowId(ServerPlayer sp) {
        for (int i = 0; i < 9; i++) {
            if (SimTerminator.ITEM_ID.equals(SimItemIds.skyblockId(sp.getInventory().getItem(i)))) {
                return SimTerminator.ITEM_ID;
            }
        }
        return SimItemIds.skyblockId(sp.getMainHandItem());
    }

    /** The item a melee ability hits with: the held weapon, else the strongest melee weapon on the hotbar. */
    private static String meleeId(ServerPlayer sp) {
        String held = SimItemIds.skyblockId(sp.getMainHandItem());
        if (held != null && !SimTerminator.ITEM_ID.equals(held) && SimDamage.weapon(held).damage() > 0) {
            return held;
        }
        String best = null;
        double bestDamage = 0;
        for (int i = 0; i < 9; i++) {
            String id = SimItemIds.skyblockId(sp.getInventory().getItem(i));
            double d = SimDamage.weapon(id).damage();
            if (id != null && d > bestDamage && !SimTerminator.ITEM_ID.equals(id)) {
                best = id;
                bestDamage = d;
            }
        }
        return best != null ? best : bowId(sp);
    }

    /** The item a magic ability is cast with: the hotbar's highest-Intelligence weapon (a Hyperion). */
    private static String magicId(ServerPlayer sp) {
        String best = SimItemIds.skyblockId(sp.getMainHandItem());
        double bestInt = SimDamage.weapon(best).intelligence();
        for (int i = 0; i < 9; i++) {
            String id = SimItemIds.skyblockId(sp.getInventory().getItem(i));
            double v = SimDamage.weapon(id).intelligence();
            if (id != null && v > bestInt) {
                best = id;
                bestInt = v;
            }
        }
        return best;
    }

    /** The vanilla hit underneath every class hit: big enough to kill a mob without a pool, as the items' are. */
    private static void hurt(ServerLevel level, ServerPlayer sp, LivingEntity e) {
        if (e.isAlive()) {
            e.hurtServer(level, level.damageSources().playerAttack(sp), 10_000f);
        }
    }

    /** Living things a class hit may land on: not him, not another real player, not a damage-number stand. */
    private static List<LivingEntity> enemies(ServerLevel level, ServerPlayer sp, AABB box) {
        var players = level.getServer().getPlayerList();
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != sp
                && !(e instanceof ArmorStand)
                && !(e instanceof ServerPlayer p && players.getPlayer(p.getUUID()) == p));
    }

    private static List<LivingEntity> within(ServerLevel level, ServerPlayer sp, Vec3 at, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : enemies(level, sp, new AABB(at, at).inflate(radius))) {
            if (e.getBoundingBox().distanceToSqr(at) <= radius * radius) {
                out.add(e);
            }
        }
        return out;
    }

    private static Vec3 wallStop(ServerLevel level, ServerPlayer sp, Vec3 from, Vec3 to) {
        BlockHitResult wall = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, sp));
        return wall != null && wall.getType() == HitResult.Type.BLOCK ? wall.getLocation() : to;
    }

    /** The nearest enemy whose (slightly widened) box the segment crosses, or null. */
    private static LivingEntity firstAlong(ServerLevel level, ServerPlayer sp, Vec3 from, Vec3 to) {
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity e : enemies(level, sp, new AABB(from, to).inflate(1.0))) {
            var hit = e.getBoundingBox().inflate(0.4).clip(from, to);
            if (hit.isEmpty() && !e.getBoundingBox().inflate(0.4).contains(from)) {
                continue;
            }
            double d = hit.map(from::distanceToSqr).orElse(0.0);
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        return nearest;
    }

    private static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, double spacing) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.01) {
            return;
        }
        Vec3 dir = delta.scale(1.0 / length);
        for (double d = 0; d < length; d += spacing) {
            Vec3 at = from.add(dir.scale(d));
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    private static void burst(ServerLevel level, ServerPlayer sp, ParticleOptions particle, int count) {
        level.sendParticles(particle, sp.getX(), sp.getY() + 1, sp.getZ(), count, 0.6, 0.8, 0.6, 0.05);
    }

    private static void explode(ServerLevel level, Vec3 at, SoundEvent sound) {
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.8, 0.5, 0.8, 0.0);
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, 0.7f, 1.1f);
    }

    /** Seconds left on the class ability and Ultimate, for the loadout screen; {-1, -1} outside the sim. */
    public static int[] cooldowns(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (!SimState.canAct(client) || server == null || client.player == null) {
            return new int[]{-1, -1};
        }
        ServerPlayer sp = server.getPlayerList().getPlayer(client.player.getUUID());
        if (sp == null) {
            return new int[]{-1, -1};
        }
        SimStats.DungeonClass c = selected();
        return new int[]{SimVitals.cooldownSeconds(sp, abilityName(c)), SimVitals.cooldownSeconds(sp, ultimateName(c))};
    }
}
