package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.SimItemIds;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The weapons and wands the class setups carry, with Hypixel's base stats and abilities (research/weapons.md: the
 * Hypixel items resource, the NEU item repo and the wiki, with the Jun/Jul 2026 patch numbers).
 *
 * <p>Every ability runs on the integrated server against the {@link ServerPlayer} whose use packet fired it, so a sim
 * party guest's work exactly like the host's. Mana and cooldowns go through {@link SimVitals#cast}; Vitality, buffs and
 * debuffs through {@link SimItemEffects}; damage through {@link SimDamage}'s public entry points ({@code ability} for
 * magic, {@code strike} for a share of a melee hit).
 *
 * <p>Two left-click abilities (Gloomlock Grimoire's Life Tap, the Gyrokinetic Wand's Gravity Storm) need the click,
 * which in the air sends no packet: the client sees the press and asks its server (a guest sends the
 * {@code item_left} party action).
 */
public final class SimWeapons {

    public static final String HALBERD_ID = "AXE_OF_THE_SHREDDED";

    // ------------------------------------------------------------------ base stats

    private static SimDamage.Weapon w(double damage, double strength, double critDamage, double intelligence,
                                      double ferocity, double defense) {
        return new SimDamage.Weapon(damage, strength, 0, critDamage, intelligence, ferocity, 0, 0, defense, false);
    }

    /**
     * Hypixel's base stats (api.hypixel.net items resource, 2026-11-01), before stars, reforges and enchantments. The
     * offline fallback for {@link SimDamage#weapon}; Hypixel's live numbers win when {@link SimRealStats} has them.
     * The Ice Spray Wand's stats are tiered by upgrade level: tier 1 here.
     */
    private static final Map<String, SimDamage.Weapon> STATS = Map.ofEntries(
            Map.entry("LAST_BREATH", w(200, 180, 50, 0, 0, 0)),
            Map.entry("STARRED_LAST_BREATH", w(210, 190, 50, 0, 0, 0)),
            Map.entry("DEATH_BOW", w(300, 0, 0, 0, 0, 0)),
            Map.entry(HALBERD_ID, w(140, 115, 0, 0, 0, 0)),
            Map.entry("DARK_CLAYMORE", w(500, 100, 100, 0, 0, 0)),
            Map.entry("SOUL_WHIP", w(145, 175, 0, 0, 0, 0)),
            Map.entry("FLAMING_FLAY", w(200, 250, 0, 0, 25, 0)),
            Map.entry("RAGNAROCK_AXE", w(200, 100, 0, 0, 0, 0)),
            Map.entry("SWORD_OF_BAD_HEALTH", w(200, 50, 50, 0, 0, 0)),
            Map.entry("ROGUE_SWORD", w(20, 0, 0, 0, 0, 0)),
            Map.entry("WITHER_CLOAK", w(190, 135, 0, 0, 0, 250)),
            Map.entry("BONZO_STAFF", w(160, 0, 0, 250, 0, 0)),
            Map.entry("STARRED_BONZO_STAFF", w(160, 0, 0, 300, 0, 0)),
            Map.entry("JERRY_STAFF", w(80, 0, 0, 200, 0, 0)),
            Map.entry("ICE_SPRAY_WAND", w(52, 0, 0, 118, 0, 0)),
            Map.entry("STARRED_ICE_SPRAY_WAND", w(60, 0, 0, 135, 0, 0)));

    /**
     * A weapon's base stats by Skyblock id, or null for an id this file does not know. HOOK for
     * {@code SimDamage.weapon}: use it when its own table has no entry.
     */
    public static SimDamage.Weapon weapon(String skyblockId) {
        return skyblockId == null ? null : STATS.get(skyblockId.toUpperCase(java.util.Locale.ROOT));
    }

    // ------------------------------------------------------------------ which clicks are ours

    private static final java.util.Set<String> RIGHT_CLICK = java.util.Set.of(HALBERD_ID, "SOUL_WHIP", "FLAMING_FLAY",
            "RAGNAROCK_AXE", "SWORD_OF_BAD_HEALTH", "ROGUE_SWORD", "WITHER_CLOAK", "SOUL_ESOWARD", "GLOOMLOCK_GRIMOIRE",
            "BONZO_STAFF", "STARRED_BONZO_STAFF", "JERRY_STAFF", "ICE_SPRAY_WAND", "STARRED_ICE_SPRAY_WAND",
            "GYROKINETIC_WAND", "WEIRDER_TUBA", SimItemEffects.HAM_RADIO_ID, SimItemEffects.ALERT_FLARE_ID,
            SimItemEffects.SOS_FLARE_ID, SimItemEffects.JALAPENO_BOOK_ID);

    private static final java.util.Set<String> LEFT_CLICK = java.util.Set.of("GLOOMLOCK_GRIMOIRE", "GYROKINETIC_WAND");

    /** Whether a right click with this id is one of this file's abilities (and so never reaches vanilla). */
    static boolean consumesUse(String id) {
        return id != null && RIGHT_CLICK.contains(id);
    }

    static boolean hasLeftClick(String id) {
        return id != null && LEFT_CLICK.contains(id);
    }

    // ------------------------------------------------------------------ per-player state

    private static final class State {
        long nextFlay;
        long nextBalloon;
        long nextBullet;
        int jerryShots;
        long lastJerry;
        int throwChain;
        long lastThrow;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private static State st(ServerPlayer sp) {
        return STATES.computeIfAbsent(sp.getUUID(), k -> new State());
    }

    /** A Bonzo's Staff balloon in flight. */
    private static final class Balloon {
        final UUID owner;
        final String weapon;
        final ServerLevel level;
        Vec3 pos;
        final Vec3 vel;
        int life;

        Balloon(UUID owner, String weapon, ServerLevel level, Vec3 pos, Vec3 vel) {
            this.owner = owner;
            this.weapon = weapon;
            this.level = level;
            this.pos = pos;
            this.vel = vel;
            this.life = 60;
        }
    }

    private static final List<Balloon> BALLOONS = new ArrayList<>();

    private SimWeapons() {
    }

    static void reset() {
        STATES.clear();
        synchronized (BALLOONS) {
            BALLOONS.clear();
        }
    }

    // ------------------------------------------------------------------ right click (server thread)

    /**
     * A right click with one of this file's items. Always true for an id in {@link #consumesUse}, so a fishing rod or a
     * horn never does its vanilla thing even when the ability declines.
     */
    static boolean useOnServer(ServerPlayer sp, String id, ItemStack held) {
        boolean fired = switch (id) {
            case HALBERD_ID -> halberdThrow(sp);
            case "SOUL_WHIP", "FLAMING_FLAY" -> flay(sp, id);
            case "RAGNAROCK_AXE" -> ragnarock(sp, id);
            case "SWORD_OF_BAD_HEALTH" -> badHealth(sp);
            case "ROGUE_SWORD" -> rogue(sp);
            case "WITHER_CLOAK" -> creeperVeil(sp);
            case "SOUL_ESOWARD" -> soulward(sp);
            case "GLOOMLOCK_GRIMOIRE" -> extremeMeasures(sp);
            case "BONZO_STAFF", "STARRED_BONZO_STAFF" -> showtime(sp, id);
            case "JERRY_STAFF" -> rapidFire(sp);
            case "ICE_SPRAY_WAND", "STARRED_ICE_SPRAY_WAND" -> iceSpray(sp, id);
            case "GYROKINETIC_WAND" -> cellsAlignment(sp);
            case "WEIRDER_TUBA" -> howl(sp);
            case SimItemEffects.HAM_RADIO_ID -> {
                SimItemEffects.tuneRadio(sp, held);
                yield false;
            }
            case SimItemEffects.ALERT_FLARE_ID, SimItemEffects.SOS_FLARE_ID -> flare(sp, id, held);
            case SimItemEffects.JALAPENO_BOOK_ID -> {
                jalapeno(sp, held);
                yield false;
            }
            default -> false;
        };
        // A power scroll fires on a right-click ability - but not on Flay (Hypixel lists that as a bug, and keeps it).
        if (fired && !id.equals("SOUL_WHIP") && !id.equals("FLAMING_FLAY")) {
            SimItemEffects.powerScroll(sp, held);
        }
        return true;
    }

    /** A left click with one of the two left-click items, on the server thread. */
    static void leftClick(ServerPlayer sp) {
        if (!SimAbilities.simServer(sp) || SimDeath.isGhost(sp)) {
            return;
        }
        String id = SimItemIds.skyblockId(sp.getMainHandItem());
        if ("GLOOMLOCK_GRIMOIRE".equals(id)) {
            lifeTap(sp);
        } else if ("GYROKINETIC_WAND".equals(id)) {
            gravityStorm(sp);
        }
    }

    // ------------------------------------------------------------------ the abilities

    /**
     * Halberd of the Shredded, Throw: the halberd flies along your look, hitting everything in its path for 10% of your
     * melee damage. Each throw in a row costs and deals twice the last, up to 16x; 20 mana for the first. APPROXIMATE:
     * the path is a 12-block line (the real one is a flying projectile) and the chain resets after 3 s without a
     * throw (the wiki gives no reset timer). It is a melee strike: it heals 25 a hit like the swing.
     */
    private static boolean halberdThrow(ServerPlayer sp) {
        State s = st(sp);
        long now = SimVitals.now();
        if (now - s.lastThrow > 60) {
            s.throwChain = 0;
        }
        int mult = 1 << Math.min(4, s.throwChain);
        if (!SimVitals.cast(sp, "Throw", 20.0 * mult, 0)) {
            return false;
        }
        s.throwChain++;
        s.lastThrow = now;
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 from = sp.getEyePosition();
        Vec3 to = wallStop(level, sp, from, from.add(sp.getLookAngle().scale(12)));
        List<LivingEntity> hit = along(level, sp, from, to, 0.8, Integer.MAX_VALUE);
        line(level, from, to, ParticleTypes.SWEEP_ATTACK, 1.0);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS,
                1f, 1f);
        SimDamage.strike(sp, "Throw", SimDamage.Type.MELEE, HALBERD_ID, 0.10 * mult, () -> hurtAll(level, sp, hit));
        return true;
    }

    /**
     * Soul Whip / Flaming Flay, Flay: whips in an arc 13 blocks forward and 2 up, dealing your melee damage to
     * everything along it - the first 3 enemies in full, the rest at half. No mana; a hidden 0.5 s cooldown, so held
     * right click repeats it.
     */
    private static boolean flay(ServerPlayer sp, String id) {
        State s = st(sp);
        long now = SimVitals.now();
        if (now < s.nextFlay) {
            return false;
        }
        s.nextFlay = now + 10;
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        List<LivingEntity> hit = new ArrayList<>();
        Vec3 prev = eye;
        for (int i = 1; i <= 13; i++) {
            double t = i / 13.0;
            Vec3 at = eye.add(look.scale(13 * t)).add(0, 2 * 4 * t * (1 - t), 0);
            Vec3 stop = wallStop(level, sp, prev, at);
            for (LivingEntity e : along(level, sp, prev, stop, 1.0, Integer.MAX_VALUE)) {
                if (!hit.contains(e)) {
                    hit.add(e);
                }
            }
            level.sendParticles(id.equals("FLAMING_FLAY") ? ParticleTypes.FLAME : ParticleTypes.SOUL, stop.x, stop.y,
                    stop.z, 2, 0.05, 0.05, 0.05, 0);
            if (!stop.equals(at)) {
                break;
            }
            prev = at;
        }
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS,
                1f, 0.6f);
        List<LivingEntity> full = hit.subList(0, Math.min(3, hit.size()));
        List<LivingEntity> half = hit.size() > 3 ? hit.subList(3, hit.size()) : List.of();
        SimDamage.strike(sp, "Flay", SimDamage.Type.MELEE, id, 1.0, () -> hurtAll(level, sp, full));
        if (!half.isEmpty()) {
            SimDamage.strike(sp, null, SimDamage.Type.MELEE, id, 0.5, () -> hurtAll(level, sp, half));
        }
        return true;
    }

    /**
     * Ragnarock: a 3 s channel; if no damage interrupts it you gain 1.5x this weapon's Strength for 10 s. 500 mana, 20 s
     * cooldown. "This weapon's Strength" is the axe's whole Strength line - base, reforge, potato books, The Art of
     * War, gems (the wiki recommends Withered, Fuming books and a Jasper for this reason) - read from its build, plus
     * the pet's Strength that Chimera adds to it.
     */
    private static boolean ragnarock(ServerPlayer sp, String id) {
        if (!SimVitals.cast(sp, "Ragnarock", 500, 20 * 20)) {
            return false;
        }
        SimItemBuild build = SimEnchants.buildFor(sp, id);
        double weaponStrength = build == SimItemBuild.EMPTY ? 0 : build.stats(new SimItemBuild.Context(
                SimState.floorLabel(), 0, SimParty.dungeonClass(sp))).stats()
                .getOrDefault(SimStats.Stat.STRENGTH, 0.0);
        if (weaponStrength <= 0) {
            weaponStrength = SimDamage.weapon(id).strength();
        }
        // Chimera: the pet's Strength counts as the axe's own, so Ragnarock multiplies it too.
        weaponStrength += SimEnchants.chimeraStat(sp, build, SimStats.Stat.STRENGTH);
        if (weaponStrength <= 0) {
            weaponStrength = STATS.get("RAGNAROCK_AXE").strength();
        }
        SimItemEffects.startRagnarock(sp, 1.5 * weaponStrength);
        sp.sendSystemMessage(Component.literal("§6Ragnarock §7is channeling... don't take damage for 3s!"));
        ((ServerLevel) sp.level()).sendParticles(ParticleTypes.ANGRY_VILLAGER, sp.getX(), sp.getY() + 1, sp.getZ(), 8,
                0.5, 0.5, 0.5, 0);
        return true;
    }

    /**
     * Sword of Bad Health: +5 Strength for every 5% of your total health you have, up to +100, for 5 s. Costs 100 Health
     * (5% of max before 0.26.1), 5 s cooldown.
     */
    private static boolean badHealth(ServerPlayer sp) {
        SimVitals.Vitals v = SimVitals.of(sp);
        if (v.health <= 100) {
            sp.sendSystemMessage(Component.literal("§cYou don't have enough Health to do this!"));
            return false;
        }
        if (!SimVitals.cast(sp, "Bad Health", 0, 100)) {
            return false;
        }
        double share = v.maxHealth <= 0 ? 0 : v.health / v.maxHealth;
        double strength = Math.min(100, 5 * Math.floor(share * 100 / 5));
        v.health -= 100;
        SimItemEffects.addBuff(sp, "Bad Health", 100, SimItemEffects.Bonus.strength(strength));
        sp.sendSystemMessage(Component.literal("§aBad Health: §c+" + SimDamage.commas(strength)
                + " ❁ Strength §afor 5s."));
        return true;
    }

    /**
     * Rogue Sword, Speed Boost: +100 Speed for 30 s, none while it is still running. 50 mana, 5 s cooldown. Speed is
     * not modelled in the sim (its speed is the absolute number {@code /speed} sets), so only the timer runs.
     */
    private static boolean rogue(ServerPlayer sp) {
        if (!SimVitals.cast(sp, "Speed Boost", 50, 100)) {
            return false;
        }
        if (!SimItemEffects.hasBuff(sp, "Speed Boost")) {
            SimItemEffects.addBuff(sp, "Speed Boost", 600, SimItemEffects.Bonus.NONE);
            sp.sendSystemMessage(Component.literal("§aYou used your §6Speed Boost§a! §f+100 ✦ Speed §7(30s)"));
        }
        return true;
    }

    /**
     * Wither Cloak Sword, Creeper Veil (revamped 0.26.1, 2026-07-22): a veil of layers that negates damage for 10 s;
     * each hit takes a layer (30 Vitality, up to 1,000 damage times the Dungeon Stat Boost). No attacking and no
     * Vitality regeneration while it is up. Right click again to put it down, which halves the cooldown. 10 s cooldown.
     */
    private static boolean creeperVeil(ServerPlayer sp) {
        ServerLevel level = (ServerLevel) sp.level();
        if (SimItemEffects.veilUp(sp)) {
            SimItemEffects.toggleVeil(sp);
            sp.sendSystemMessage(Component.literal("§cCreeper Veil De-activated!"));
            return false;
        }
        if (SimItemEffects.vitality(sp) < 30 && SimParty.profile(sp).manaCosts()) {
            sp.sendSystemMessage(Component.literal("§cYou do not have enough Vitality to do this!"));
            return false;
        }
        if (!SimVitals.cast(sp, "Creeper Veil", 0, 200)) {
            return false;
        }
        SimItemEffects.toggleVeil(sp);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 1f,
                0.8f);
        sp.sendSystemMessage(Component.literal("§aCreeper Veil Activated!"));
        return true;
    }

    /**
     * Soul Esoward, Soulward: invulnerable for 5 s (not to true damage) but unable to deal damage, then half damage for
     * 2 s. 350 mana, 20 s cooldown. Its 10 Soulflow is not modelled.
     */
    private static boolean soulward(ServerPlayer sp) {
        if (!SimVitals.cast(sp, "Soulward", 350, 20 * 20)) {
            return false;
        }
        SimItemEffects.soulward(sp);
        ((ServerLevel) sp.level()).sendParticles(ParticleTypes.SOUL, sp.getX(), sp.getY() + 1, sp.getZ(), 30, 0.6,
                0.8, 0.6, 0.02);
        sp.sendSystemMessage(Component.literal("§aSoulward: §7invulnerable for 5s, but you can't deal damage."));
        return true;
    }

    /** Gloomlock Grimoire, Extreme Measures (RIGHT CLICK): heals 1,000 Health for 45 Vitality (50 before 2026-07-20). */
    private static boolean extremeMeasures(ServerPlayer sp) {
        if (!SimItemEffects.spendVitality(sp, 45)) {
            return false;
        }
        SimVitals.heal(sp, 1_000);
        ((ServerLevel) sp.level()).sendParticles(ParticleTypes.HEART, sp.getX(), sp.getY() + 1.8, sp.getZ(), 5, 0.4,
                0.3, 0.4, 0);
        return true;
    }

    /**
     * Gloomlock Grimoire, Life Tap (LEFT CLICK): 800 Health (25% of it before 2026-07-15) for up to 600 mana, 1 s
     * cooldown. APPROXIMATE: Hypixel gives it as Overflow Mana above the pool; the sim's pool has no overflow, so it is
     * capped at full. Its 15 Soulflow is not modelled.
     */
    private static void lifeTap(ServerPlayer sp) {
        SimVitals.Vitals v = SimVitals.of(sp);
        if (v.health <= 800) {
            sp.sendSystemMessage(Component.literal("§cYou don't have enough Health to do this!"));
            return;
        }
        if (!SimVitals.cast(sp, "Life Tap", 0, 20)) {
            return;
        }
        v.health -= 800;
        v.mana = Math.min(v.maxMana, v.mana + 600);
        ((ServerLevel) sp.level()).sendParticles(ParticleTypes.WITCH, sp.getX(), sp.getY() + 1, sp.getZ(), 15, 0.4,
                0.6, 0.4, 0.02);
    }

    /**
     * Bonzo's Staff, Showtime: fires a slow balloon (up to 4 a second) that bursts on a block or a mob for 1,000 damage
     * (1,100 fragged) to mobs within 2 blocks, scaling 0.2. 90 mana. Caught in your own blast, you are thrown up to 1.7
     * blocks up and 8 back.
     */
    private static boolean showtime(ServerPlayer sp, String id) {
        State s = st(sp);
        long now = SimVitals.now();
        if (now < s.nextBalloon) {
            return false;
        }
        if (!SimVitals.cast(sp, "Showtime", 90, 0)) {
            return false;
        }
        s.nextBalloon = now + 5;
        Vec3 look = sp.getLookAngle();
        synchronized (BALLOONS) {
            BALLOONS.add(new Balloon(sp.getUUID(), id, (ServerLevel) sp.level(), sp.getEyePosition().add(look.scale(0.6)),
                    look.scale(0.6)));
        }
        ((ServerLevel) sp.level()).playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BLAZE_SHOOT,
                SoundSource.PLAYERS, 0.5f, 1.8f);
        return true;
    }

    private static void tickBalloons(MinecraftServer server) {
        List<Balloon> live;
        synchronized (BALLOONS) {
            if (BALLOONS.isEmpty()) {
                return;
            }
            live = new ArrayList<>(BALLOONS);
        }
        List<Balloon> done = new ArrayList<>();
        for (Balloon b : live) {
            ServerPlayer owner = server.getPlayerList().getPlayer(b.owner);
            if (owner == null || owner.level() != b.level || --b.life <= 0) {
                done.add(b);
                continue;
            }
            Vec3 next = b.pos.add(b.vel);
            BlockHitResult wall = b.level.clip(new ClipContext(b.pos, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, owner));
            boolean blocked = wall != null && wall.getType() == HitResult.Type.BLOCK;
            Vec3 at = blocked ? wall.getLocation() : next;
            boolean mob = !along(b.level, owner, b.pos, at, 0.4, 1).isEmpty();
            b.pos = at;
            b.level.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0);
            if (blocked || mob) {
                burst(owner, b);
                done.add(b);
            }
        }
        synchronized (BALLOONS) {
            BALLOONS.removeAll(done);
        }
    }

    private static void burst(ServerPlayer sp, Balloon b) {
        ServerLevel level = b.level;
        Vec3 at = b.pos;
        double base = b.weapon.startsWith("STARRED_") ? 1_100 : 1_000;
        List<LivingEntity> hit = within(level, sp, at, 2.0);
        SimDamage.ability(sp, "Showtime", b.weapon, base, 0.2, () -> hurtAll(level, sp, hit));
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.8f, 1.2f);
        if (sp.position().add(0, 0.9, 0).distanceTo(at) <= 2.5) {
            Vec3 away = sp.position().subtract(at);
            Vec3 flat = new Vec3(away.x, 0, away.z);
            flat = flat.lengthSqr() < 1.0e-4 ? sp.getLookAngle().multiply(-1, 0, -1) : flat;
            flat = flat.normalize().scale(0.75);
            sp.setDeltaMovement(flat.x, 0.6, flat.z);
            sp.syncVelocity = true;
        }
    }

    /**
     * Jerry-chine Gun, Rapid-fire: a single-target bullet for 500 damage (scaling 0.2) - it has not exploded since
     * 2021. Within 2.5 blocks of the impact you are knocked 2.5 up and back. Mana starts at 30 and each shot costs 30
     * more, resetting after 4 s without firing; about 5 shots a second. APPROXIMATE: the bullet is instant here.
     */
    private static boolean rapidFire(ServerPlayer sp) {
        State s = st(sp);
        long now = SimVitals.now();
        if (now < s.nextBullet) {
            return false;
        }
        if (now - s.lastJerry > 80) {
            s.jerryShots = 0;
        }
        if (!SimVitals.cast(sp, "Rapid-fire", 30.0 * (s.jerryShots + 1), 0)) {
            return false;
        }
        s.jerryShots++;
        s.lastJerry = now;
        s.nextBullet = now + 4;
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 from = sp.getEyePosition();
        Vec3 to = wallStop(level, sp, from, from.add(sp.getLookAngle().scale(30)));
        List<LivingEntity> first = along(level, sp, from, to, 0.3, 1);
        Vec3 impact = first.isEmpty() ? to : first.get(0).getBoundingBox().getCenter();
        line(level, from, impact, ParticleTypes.CRIT, 0.8);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS,
                0.4f, 2f);
        if (!first.isEmpty()) {
            SimDamage.ability(sp, "Rapid-fire", "JERRY_STAFF", 500, 0.2, () -> hurtAll(level, sp, first));
        }
        if (sp.position().add(0, 0.9, 0).distanceTo(impact) <= 2.5) {
            Vec3 back = sp.getLookAngle().multiply(-1, 0, -1);
            back = back.lengthSqr() < 1.0e-4 ? Vec3.ZERO : back.normalize().scale(0.35);
            sp.setDeltaMovement(back.x, 0.7, back.z);
            sp.syncVelocity = true;
        }
        return true;
    }

    /**
     * Ice Spray Wand, Ice Spray: a cone of ice 7 blocks long and 60 degrees wide that passes through walls, for 17,000
     * damage (19,000 fragged), scaling 0.1. Freezes what it hits for 5 s; frozen mobs take 10% more damage from
     * everything. 50 mana, 5 s cooldown.
     */
    private static boolean iceSpray(ServerPlayer sp, String id) {
        if (!SimVitals.cast(sp, "Ice Spray", 50, 100)) {
            return false;
        }
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle().normalize();
        double cos = Math.cos(Math.toRadians(30));
        List<LivingEntity> hit = new ArrayList<>();
        for (LivingEntity e : enemies(level, sp, new AABB(eye, eye).inflate(7.5))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double d = to.length();
            if (d <= 7.5 && (d < 1.0 || to.scale(1 / d).dot(look) >= cos)) {
                hit.add(e);
            }
        }
        for (int i = 1; i <= 7; i++) {
            Vec3 at = eye.add(look.scale(i));
            level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 4 + i, 0.15 * i, 0.15 * i, 0.15 * i, 0.01);
        }
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.SNOW_BREAK, SoundSource.PLAYERS, 1f, 0.6f);
        double base = id.startsWith("STARRED_") ? 19_000 : 17_000;
        SimDamage.ability(sp, "Ice Spray", id, base, 0.1, () -> hurtAll(level, sp, hit));
        for (LivingEntity e : hit) {
            if (e.isAlive()) {
                SimItemEffects.freeze(e, 100);
            }
        }
        return true;
    }

    /**
     * Gyrokinetic Wand, Gravity Storm (LEFT CLICK): a rift of 10 blocks radius where you aim, up to 24 blocks away,
     * that pulls mobs to its centre and holds them for 4 s; your mana regenerates 10x slower for 3 s. 1,200 mana, 30 s
     * cooldown. Its 10 Soulflow is not modelled.
     */
    private static void gravityStorm(ServerPlayer sp) {
        if (!SimVitals.cast(sp, "Gravity Storm", 1_200, 30 * 20)) {
            return;
        }
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 from = sp.getEyePosition();
        Vec3 centre = wallStop(level, sp, from, from.add(sp.getLookAngle().scale(24)));
        // Held with their feet on the floor under the rift, never below it: aimed at the ground, the centre is the
        // floor's own surface, and holding them half a body below it buried them in the floor.
        double floor = floorUnder(level, centre);
        for (LivingEntity e : within(level, sp, centre, 10)) {
            double feet = Math.max(floor, centre.y - e.getBbHeight() / 2);
            SimItemEffects.holdAt(e, new Vec3(centre.x, feet, centre.z), 80);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, centre.x, centre.y, centre.z, 120, 4, 2, 4, 0.1);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 1f, 0.5f);
        SimItemEffects.slowMana(sp, 60);
    }

    /** The top of the first solid block at or below {@code at} (up to 8 down), or {@code at.y} when there is none. */
    private static double floorUnder(ServerLevel level, Vec3 at) {
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        int x = net.minecraft.util.Mth.floor(at.x), z = net.minecraft.util.Mth.floor(at.z);
        for (int y = net.minecraft.util.Mth.floor(at.y); y >= net.minecraft.util.Mth.floor(at.y) - 8; y--) {
            var shape = level.getBlockState(m.set(x, y, z)).getCollisionShape(level, m);
            if (!shape.isEmpty()) {
                return y + shape.max(net.minecraft.core.Direction.Axis.Y);
            }
        }
        return at.y;
    }

    /**
     * Gyrokinetic Wand, Cells Alignment (RIGHT CLICK): Aligned on you and 4 nearby players for 6 s - damage they take is
     * split and taken over 3 s. 220 mana, 10 s cooldown. APPROXIMATE: "nearby" is 20 blocks (not given).
     */
    private static boolean cellsAlignment(ServerPlayer sp) {
        if (!SimVitals.cast(sp, "Cells Alignment", 220, 200)) {
            return false;
        }
        for (ServerPlayer p : nearbyPlayers(sp, 20, 4)) {
            SimItemEffects.align(p);
            p.sendSystemMessage(Component.literal("§aYou are §dAligned §afor 6s!"));
        }
        ((ServerLevel) sp.level()).sendParticles(ParticleTypes.PORTAL, sp.getX(), sp.getY() + 1, sp.getZ(), 40, 1, 1,
                1, 0.2);
        return true;
    }

    /**
     * Weirder Tuba, Howl: you and up to 4 players within 16 blocks gain +40 Strength, +5 Attack Speed, +10 Crit Damage
     * (and +30 Speed, not a sim stat) for 30 s; it does not stack. 150 mana, 20 s cooldown.
     */
    private static boolean howl(ServerPlayer sp) {
        if (!SimVitals.cast(sp, "Howl", 150, 20 * 20)) {
            return false;
        }
        SimItemEffects.Bonus howl = new SimItemEffects.Bonus(0, 0, 40, 0, 10, 0, 5, 0);
        for (ServerPlayer p : nearbyPlayers(sp, 16, 4)) {
            SimItemEffects.addBuff(p, "Howl", 30 * 20, howl);
            p.sendSystemMessage(Component.literal("§aYou gained §c+40 ❁ Strength§a, §e+5 ⚔ Attack Speed§a and §9+10 ☠"
                    + " Crit Damage §afrom a §5Weirder Tuba §afor 30s!"));
        }
        ((ServerLevel) sp.level()).playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.PLAYERS, 1f, 0.5f);
        return true;
    }

    /**
     * Alert Flare / SOS Flare: deploys at your feet for 3 minutes, 40 blocks: Alert +50% base mana regeneration, +15
     * Vitality, +10 Ferocity; SOS +125% mana regeneration, +25 Vitality, +10 Ferocity, +5 Attack Speed (their True
     * Defense is not a sim stat; the heal only works on the Crimson Isle). Alert 300 mana + 10 Vitality, 20 s
     * cooldown; SOS 250 mana + 10 Vitality, 10 s cooldown. Only one deployable buff applies. Not used up.
     */
    private static boolean flare(ServerPlayer sp, String id, ItemStack held) {
        boolean sos = SimItemEffects.SOS_FLARE_ID.equals(id);
        if (SimItemEffects.vitality(sp) < 10 && SimParty.profile(sp).manaCosts()) {
            sp.sendSystemMessage(Component.literal("§cYou do not have enough Vitality to do this!"));
            return false;
        }
        if (!SimVitals.cast(sp, sos ? "SOS Flare" : "Alert Flare", sos ? 250 : 300, sos ? 200 : 400)) {
            return false;
        }
        SimItemEffects.spendVitality(sp, 10);
        SimItemEffects.deployFlare(sp, sos, jalapenoOn(held));
        sp.sendSystemMessage(Component.literal("§aYou deployed an " + (sos ? "§5SOS Flare" : "§9Alert Flare")
                + "§a!"));
        return true;
    }

    static boolean jalapenoOn(ItemStack stack) {
        var data = stack == null ? null : stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getIntOr("jalapeno_count", 0) > 0;
    }

    /**
     * Jalapeno Book: on Hypixel it goes on a deployable in an anvil (once). Here a right click puts it on the first flare
     * you carry that has none, and is used up: that flare then adds +5 Crit Damage and +1 Crit Chance while deployed.
     */
    private static void jalapeno(ServerPlayer sp, ItemStack book) {
        for (ItemStack st : sp.getInventory().getNonEquipmentItems()) {
            String id = SimItemIds.skyblockId(st);
            if ((SimItemEffects.ALERT_FLARE_ID.equals(id) || SimItemEffects.SOS_FLARE_ID.equals(id)) && !jalapenoOn(st)) {
                var data = st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                var tag = data == null ? new net.minecraft.nbt.CompoundTag() : data.copyTag();
                tag.putInt("jalapeno_count", 1);
                st.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(tag));
                SimItemLore.apply(st, id);
                book.shrink(1);
                sp.sendSystemMessage(Component.literal("§aApplied the §5Jalapeno Book §ato your flare."));
                return;
            }
        }
        sp.sendSystemMessage(Component.literal("§cYou have no flare without a Jalapeno Book."));
    }

    // ------------------------------------------------------------------ the left click (client)

    private static boolean wasAttacking;
    private static boolean clientRegistered;

    /** The left press with a left-click item, seen on the client: the host's server runs it, a guest asks it to. */
    static void registerClient() {
        if (clientRegistered) {
            return;
        }
        clientRegistered = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!SimState.canAct(client) && !SimParty.guest() || client.player == null || client.options == null
                    || com.epic60869.sky2m.sim.compat.McCompat.screen(client) != null) {
                wasAttacking = false;
                return;
            }
            boolean attacking = client.options.keyAttack.isDown();
            boolean pressed = attacking && !wasAttacking;
            wasAttacking = attacking;
            if (!pressed || !hasLeftClick(SimItemIds.skyblockId(client.player.getMainHandItem()))) {
                return;
            }
            if (SimParty.sendAction("item_left", "")) {
                return;
            }
            var server = client.getSingleplayerServer();
            if (server == null || !SimState.canAct(client)) {
                return;
            }
            UUID id = client.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(id);
                if (sp != null) {
                    leftClick(sp);
                }
            });
        });
    }

    // ------------------------------------------------------------------ the tick

    static void tick(MinecraftServer server) {
        tickBalloons(server);
    }

    // ------------------------------------------------------------------ helpers

    /** Living things an ability may hit: not him, not another real player, not a damage-number stand. */
    static List<LivingEntity> enemies(ServerLevel level, ServerPlayer sp, AABB box) {
        var players = level.getServer().getPlayerList();
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != sp
                && !(e instanceof ArmorStand)
                && !(e instanceof ServerPlayer p && players.getPlayer(p.getUUID()) == p));
    }

    static List<LivingEntity> within(ServerLevel level, ServerPlayer sp, Vec3 at, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : enemies(level, sp, new AABB(at, at).inflate(radius))) {
            if (e.getBoundingBox().distanceToSqr(at) <= radius * radius) {
                out.add(e);
            }
        }
        return out;
    }

    /** Enemies whose box (widened by {@code width}) the segment crosses, nearest first, at most {@code limit}. */
    static List<LivingEntity> along(ServerLevel level, ServerPlayer sp, Vec3 from, Vec3 to, double width, int limit) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : enemies(level, sp, new AABB(from, to).inflate(width + 1))) {
            AABB box = e.getBoundingBox().inflate(width);
            if (box.contains(from) || box.clip(from, to).isPresent()) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(from)));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    static Vec3 wallStop(ServerLevel level, ServerPlayer sp, Vec3 from, Vec3 to) {
        BlockHitResult wall = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                sp));
        return wall != null && wall.getType() == HitResult.Type.BLOCK ? wall.getLocation() : to;
    }

    /** He and up to {@code others} living teammates within {@code range}, nearest first. */
    static List<ServerPlayer> nearbyPlayers(ServerPlayer sp, double range, int others) {
        List<ServerPlayer> out = new ArrayList<>();
        out.add(sp);
        List<ServerPlayer> mates = new ArrayList<>();
        for (ServerPlayer p : SimParty.players(sp.level().getServer())) {
            if (p != sp && p.level() == sp.level() && !SimDeath.isGhost(p) && p.distanceToSqr(sp) <= range * range) {
                mates.add(p);
            }
        }
        mates.sort(Comparator.comparingDouble(p -> p.distanceToSqr(sp)));
        out.addAll(mates.subList(0, Math.min(others, mates.size())));
        return out;
    }

    /** The vanilla hit each target takes; a pooled mob takes the rolled Hypixel hit instead (SimHealth). */
    private static void hurtAll(ServerLevel level, ServerPlayer sp, List<LivingEntity> targets) {
        for (LivingEntity e : targets) {
            if (e.isAlive()) {
                e.hurtServer(level, level.damageSources().playerAttack(sp), 10_000f);
            }
        }
    }

    static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, double spacing) {
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
}
