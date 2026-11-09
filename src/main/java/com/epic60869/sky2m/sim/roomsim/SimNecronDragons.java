package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * M7's last phase, the Wither King ({@link SimNecron}'s P5, at the bottom of the room), after
 * https://hypixelskyblock.minecraft.wiki/w/Wither_King.
 *
 * <p>Shaped to what the mod reads (Wither Dragons, Dragon Splits, Boss Phases, Rag Axe):
 * <ul>
 * <li>The Wither King's lines: "You... again?" opens the phase (Boss Phases' "Wither King", Dungeon Features),
 * "I no longer wish to fight, but I know that will not stop you." comes before the dragons (Rag Axe's prompt).
 * His boss bar is named "Wither King" (the relic place timer starts on it).</li>
 * <li>One dragon at a time, in a random order, every 30 seconds (sooner after a kill): each one is announced by Hypixel's flame particles (20 flames at
 * y 19 over its statue, which Wither Dragons' spawn alerts and timers read) and appears five seconds later at its
 * statue, an ender dragon with the relic "collar" armour stand Wither Dragons matches it by. Killed inside its
 * statue's box (Wither Dragons' kill area) it destroys the statue; killed outside it, it can come back, again and
 * again, until its statue is gone. All five alive at once fails the run.</li>
 * <li>Each dragon has its ability: Apex (green) charges at players, Flame (orange) throws fireballs that leave the
 * ground burning, Ice (blue) flies low and freezes the ground, trapping players, Power (red) flies to the middle
 * and drops TNT, Soul (purple) teleports round the room. All of them breathe fire as well (the sim's own generic
 * attack) and hit whoever comes close.</li>
 * <li>The five relics lie at the statues once Necron dies: walk into one (or click it) and "Name picked the
 * Corrupted Red Relic!" (the relic place timer's line), then walk it to its own cauldron by the throne. The wrong
 * cauldron fails the run. With all five placed the Wither King is summoned on his throne: he pulls in and kills
 * anyone who gets close, sweeps a beam round the throne room and calls lightning. He cannot be damaged.</li>
 * </ul>
 * All five statues down and the Wither King gives up (his Victory lines); the run ends. A failed run (or the whole
 * party dead) gets his Defeat lines instead.
 *
 * <p>The dragons have no AI (the fight flies them), so the vanilla ender dragon's own flight, block breaking and
 * End fight never run; their body parts, which are what arrows hit, are placed by the fight on the server.
 */
final class SimNecronDragons {

    /**
     * Ticks from the flames to the dragon. M7 POV videos show 3.4-4.3 s between the spawn marker and the dragon, so
     * 3.8 s (APPROXIMATE; Wither Dragons' own spawn timer counts 100 ticks, so it shows about a second left when
     * the dragon appears).
     */
    private static final int SPAWN_TICKS = 76;
    /** "The Wither King spawns one of five dragons every 30 seconds." */
    private static final int SPAWN_EVERY = 600;
    /**
     * After a dragon is slain at its statue the next comes this soon if the 30 s timer is further off: M7 POV videos
     * kill four dragons in 25-38 s of P5, one at a time, which the 30 s timer alone cannot do (APPROXIMATE).
     */
    private static final int NEXT_AFTER_KILL = 60;
    /**
     * Statues to destroy to finish: the wiki says "Destroying all five statues completes the floor". (In both M7 POV
     * videos the run ended about 5 s after the fourth kill; the wiki's five is kept.)
     */
    private static final int STATUES_TO_WIN = 5;
    private static final Vec3 MIDDLE = new Vec3(54.5, 15, 76.5);
    /** The dragons' room, for the Soul Dragon's teleports and the Power Dragon's TNT (APPROXIMATE bounds). */
    private static final AABB ROOM = new AABB(15, 10, 50, 95, 22, 130);
    /** The Wither King's throne, behind the cauldrons (APPROXIMATE spot). */
    private static final Vec3 THRONE = new Vec3(54.5, 8, 36.5);
    /** The Wither King's attacks: the wiki gives no numbers (APPROXIMATE: 2x the withers' base for beam/lightning). */
    private static final double KING_HIT_X = 2;
    private static final double PULL_RANGE = 7;

    private enum Ability { POWER, FLAME, APEX, ICE, SOUL }

    /** One dragon: Wither Dragons' spawn spot, kill box and relic cauldron (NoammAddons' coordinates). */
    private record Def(String name, String relicId, ChatFormatting colour, int rgb, Vec3 spawn, AABB area,
                       int[] cauldron, Ability ability) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("Red", "RED_KING_RELIC", ChatFormatting.RED, 0xFF0000, new Vec3(27, 14, 59),
                    new AABB(14.5, 5, 45.5, 39.5, 28, 70.5), new int[]{51, 7, 42}, Ability.POWER),
            new Def("Orange", "ORANGE_KING_RELIC", ChatFormatting.GOLD, 0xFFAA00, new Vec3(85, 14, 56),
                    new AABB(72, 5, 47, 102, 28, 77), new int[]{57, 7, 42}, Ability.FLAME),
            new Def("Green", "GREEN_KING_RELIC", ChatFormatting.GREEN, 0x00FF00, new Vec3(27, 14, 94),
                    new AABB(7, 5, 80, 37, 28, 110), new int[]{49, 7, 44}, Ability.APEX),
            new Def("Blue", "BLUE_KING_RELIC", ChatFormatting.AQUA, 0x55FFFF, new Vec3(84, 14, 94),
                    new AABB(71.5, 5, 82.5, 96.5, 26, 107.5), new int[]{59, 7, 44}, Ability.ICE),
            new Def("Purple", "PURPLE_KING_RELIC", ChatFormatting.DARK_PURPLE, 0xAA00AA, new Vec3(56, 14, 125),
                    new AABB(45.5, 6, 113.5, 68.5, 23, 136.5), new int[]{54, 7, 41}, Ability.SOUL));

    /** The Wither King's lines, word for word (the wiki's Dialogue; his "Random" ones come every so often). */
    private static final String[] KING_RANDOM = {
            "Does that mean Necron is no more? I have been a mediocre master.",
            "Storm was my favorite sparring partner, you must have enjoyed fighting him!",
            "This world isn't worth fighting for, how can you be so determined?",
            "Mortals fighting against supreme beings, what a moving spectacle.",
            "I enjoy your passion, if the circumstances had been different, we could have been friends.",
            "I have worked on this spell for centuries.",
            "Some of you do have some talent, I can feel it, the other ones not so much."};
    private static final String[] KING_IMMUNE = {
            "You are wasting your time.",
            "Did you forget? I am invincible!",
            "You don't have your sword this time!"};
    private static final String[] KING_RESPAWN = {
            "Futile.",
            "You just made a terrible mistake!",
            "I am not impressed.",
            "Your skills have faded, humans."};
    private static final String[] KING_STATUE = {
            "Oh, this one hurts!",
            "My soul is disposable.",
            "I have more of those."};
    /** His victory lines after "Incredible...", a couple of seconds apart (the spacing is APPROXIMATE). */
    private static final String[] KING_VICTORY = {
            "In a way I should thank you, I lost all hopes centuries ago that it would ever end.",
            "I hope you'll become the Heroes I could never be.",
            "So long champions of this mad world!",
            "My strengths are depleting, this… this is it…"};
    /** His Defeat lines: the run failing (all five dragons up, a wrong relic) or the whole party dead. */
    private static final String[] KING_DEFEAT = {
            "Thank you for coming all the way here.",
            "Now I will truly have peace.",
            "Let's enjoy your final moments in silence."};
    private static final int VICTORY_LINE_EVERY = 40;
    private static final int KING_RANDOM_EVERY = 600;

    private final SimNecron fight;
    private final double maxHealth;
    private final int start;
    private final List<Dragon> dragons = new ArrayList<>();
    private final List<Relic> relics = new ArrayList<>();
    private final boolean[] relicPlaced = new boolean[DEFS.size()];
    private final boolean[] statueDown = new boolean[DEFS.size()];
    /** Dragons killed away from their statue, whose next spawn is a respawn (for his respawn line). */
    private final boolean[] diedAway = new boolean[DEFS.size()];
    private boolean firstSpawn = true;
    private int lastImmuneLine = -1000;
    private int slain;
    private int nextSpawnAt;
    private int endAt = -1;
    /** When he said "Incredible...", for the victory lines after it. */
    private int victoryAt = -1;
    private boolean finished;

    /** The Wither King on his throne, once the relics are in; null before. */
    private SimNecron.WitherBody king;
    private int nextPull;
    private ServerPlayer pulling;
    private int pullEnd;
    private int nextLightning;
    private double kingBeam;
    private final Map<UUID, Integer> beamHitAt = new HashMap<>();

    private static final class Dragon {
        final Def def;
        final int startedAt;
        DragonBody body;
        ArmorStand collar;
        double hp;
        int age;
        double angle;
        int nextBreath;
        int nextAbility;
        int touchCooldown;
        /** Apex: the charge's end, start and target. */
        int chargeEnd = -1;
        Vec3 chargeFrom;
        Vec3 chargeTo;

        Dragon(Def def, int startedAt) {
            this.def = def;
            this.startedAt = startedAt;
        }
    }

    private static final class Relic {
        final Def def;
        ArmorStand stand;

        Relic(Def def) {
            this.def = def;
        }
    }

    SimNecronDragons(SimNecron fight, ServerLevel level) {
        this.fight = fight;
        this.maxHealth = dragonHealth();
        this.start = fight.now();
        nextSpawnAt = start + 100;
        // Since Hypixel's 2026 dungeon update this is the first line of P5 (Boss Phases, Dungeon Features).
        SimBoss.say(level.getServer(), "Wither King", "You... again?");
        for (Def d : DEFS) {
            Relic r = new Relic(d);
            r.stand = relicStand(level, d, new Vec3(d.spawn().x + 0.5,
                    SimNecron.ground(level, (int) d.spawn().x, (int) d.spawn().z, 12, 1), d.spawn().z + 0.5));
            relics.add(r);
        }
    }

    /**
     * A dragon's health: 1,000,000,000 for each of the five (raised from 900M in 0.19.4), base damage 720,000 on M7
     * (https://hypixelskyblock.minecraft.wiki/w/Power_Dragon and the Flame, Apex, Ice and Soul Dragon pages). The
     * dragons are M7's only; elsewhere (/boss from another floor) they keep that health and hit for the F7 withers'
     * base damage ({@link SimNecron#witherDamage}, APPROXIMATE). The wiki gives the abilities no numbers of their
     * own, so they deal the base damage. The Wither King himself has no health: Hypixel's cannot be damaged.
     */
    private static double dragonHealth() {
        return 1_000_000_000;
    }

    /** The Wither King's {@code i}th Defeat line, or null past the last. */
    static String defeatLine(int i) {
        return i >= 0 && i < KING_DEFEAT.length ? KING_DEFEAT[i] : null;
    }

    boolean finished() {
        return finished;
    }

    /** The Wither King's bar: the share of statues still standing. */
    float progress() {
        return Math.max(0, STATUES_TO_WIN - slain) / (float) STATUES_TO_WIN;
    }

    /** A dragon's body, or the Wither King's (which takes no damage: {@link #hit} refuses it). */
    boolean isDragon(Entity e) {
        if (king != null && e == king) {
            return true;
        }
        for (Dragon d : dragons) {
            if (d.body != null && d.body == e) {
                return true;
            }
        }
        return false;
    }

    boolean hit(LivingEntity e, float amount) {
        if (king != null && e == king && e.level() instanceof ServerLevel sl && fight.now() - lastImmuneLine > 100) {
            // Hypixel's lines when he is hit directly (he cannot be hurt).
            lastImmuneLine = fight.now();
            SimBoss.say(sl.getServer(), "Wither King", KING_IMMUNE[ThreadLocalRandom.current().nextInt(KING_IMMUNE.length)]);
        }
        for (Dragon d : dragons) {
            if (d.body == e && d.hp > 0) {
                d.hp = Math.max(0, d.hp - amount);
                mirror(d);
                return true;
            }
        }
        return false;
    }

    void tick(ServerLevel level) {
        int now = fight.now();
        MinecraftServer server = level.getServer();
        if (now - start == 60) {
            // Hypixel's line, word for word: Rag Axe's dragon prompt.
            SimBoss.say(server, "Wither King", "I no longer wish to fight, but I know that will not stop you.");
        } else if (now - start == 90) {
            SimBoss.say(server, "Wither King", "We will decide it all, here, now.");
        }
        if (endAt >= 0) {
            int line = (now - victoryAt) / VICTORY_LINE_EVERY - 1;
            if ((now - victoryAt) % VICTORY_LINE_EVERY == 0 && line >= 0 && line < KING_VICTORY.length) {
                SimBoss.say(server, "Wither King", KING_VICTORY[line]);
            }
            if (now >= endAt) {
                finished = true;
            }
            return;
        }
        if (now - start > 200 && (now - start) % KING_RANDOM_EVERY == 0 && !fight.failed()) {
            SimBoss.say(server, "Wither King", KING_RANDOM[ThreadLocalRandom.current().nextInt(KING_RANDOM.length)]);
        }
        tickRelics(level);
        if (fight.failed()) {
            return;
        }
        tickKing(level, now);
        if (now >= nextSpawnAt) {
            nextSpawnAt = now + SPAWN_EVERY;
            Def next = pickNext();
            if (next != null) {
                dragons.add(new Dragon(next, now));
                int i = DEFS.indexOf(next);
                if (firstSpawn) {
                    // One at a time, as in M7 POV videos (a random one of those left: the order differs every run).
                    firstSpawn = false;
                    SimBoss.say(server, "Wither King", "My enemies are now my strongest allies!");
                } else if (diedAway[i]) {
                    diedAway[i] = false;
                    SimBoss.say(server, "Wither King", KING_RESPAWN[ThreadLocalRandom.current().nextInt(KING_RESPAWN.length)]);
                }
            }
        }
        for (Dragon d : new ArrayList<>(dragons)) {
            tickDragon(level, d, now);
        }
        int alive = 0;
        for (Dragon d : dragons) {
            if (d.body != null && !d.body.isRemoved()) {
                alive++;
            }
        }
        if (alive >= DEFS.size()) {
            // Hypixel lifts the party into a ritual and a giant hand takes them: the run is over.
            fight.failRun(level, "all five dragons were alive at once.");
            return;
        }
        if (slain >= STATUES_TO_WIN) {
            SimBoss.say(server, "Wither King", "Incredible. You did what I couldn't do myself.");
            victoryAt = now;
            endAt = now + VICTORY_LINE_EVERY * (KING_VICTORY.length + 1);
        }
    }

    /** A dragon whose statue still stands and that is not already up (or coming), at random; null when none. */
    private Def pickNext() {
        List<Def> free = new ArrayList<>();
        for (int i = 0; i < DEFS.size(); i++) {
            Def def = DEFS.get(i);
            boolean up = false;
            for (Dragon d : dragons) {
                up |= d.def == def;
            }
            if (!statueDown[i] && !up) {
                free.add(def);
            }
        }
        return free.isEmpty() ? null : free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    private void tickDragon(ServerLevel level, Dragon d, int now) {
        int since = now - d.startedAt;
        if (d.body == null) {
            if (since % 10 == 0) {
                flames(level, d.def);
            }
            if (since >= SPAWN_TICKS) {
                spawn(level, d);
            }
            return;
        }
        if (d.body.isRemoved()) {
            dragons.remove(d);
            return;
        }
        d.age++;
        if (d.chargeEnd >= 0) {
            charge(level, d, now);
        } else {
            fly(d);
        }
        if (d.collar != null) {
            d.collar.snapTo(d.body.getX(), d.body.getY() + 1.5, d.body.getZ());
        }
        if (now >= d.nextAbility) {
            ability(level, d, now);
        }
        if (now >= d.nextBreath) {
            ServerPlayer target = fight.nearest(level, d.body.position());
            if (target != null) {
                SimNecron.beam(level, ParticleTypes.FLAME, d.body.position().add(0, 1, 0), target.position(), 1.0);
                fight.blast(target.position(), 3.0, 25, SimNecron.witherDamage(), d.def.rgb());
                level.playSound(null, d.body.getX(), d.body.getY(), d.body.getZ(), SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 2f, 1f);
            }
            d.nextBreath = now + 70;
        }
        if (--d.touchCooldown <= 0) {
            for (ServerPlayer p : fight.players(level)) {
                if (p.distanceToSqr(d.body) < 5 * 5) {
                    SimNecron.hurtPlayer(level, p, SimNecron.witherDamage(), p.getX() - d.body.getX(), p.getZ() - d.body.getZ(), 1.0, 0.6);
                    d.touchCooldown = 20;
                }
            }
        }
        if (d.hp <= 0) {
            die(level, d, now);
        }
    }

    /** Each dragon's own move (the wiki's descriptions; timings APPROXIMATE). */
    private void ability(ServerLevel level, Dragon d, int now) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ServerPlayer target = fight.nearest(level, d.body.position());
        switch (d.def.ability()) {
            case APEX -> {
                // Flies at a player to hit him.
                d.nextAbility = now + 160;
                if (target != null) {
                    d.chargeFrom = d.body.position();
                    d.chargeTo = target.position().add(0, 1, 0);
                    d.chargeEnd = now + 25;
                    level.playSound(null, d.body.getX(), d.body.getY(), d.body.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 3f, 1.4f);
                }
            }
            case FLAME -> {
                // Fireballs that leave fire on the ground.
                d.nextAbility = now + 80;
                if (target != null) {
                    fight.shoot(SimNecron.ShotKind.FLAME, d.body.position().add(0, 1, 0), target.position(), 1.0,
                            SimNecron.witherDamage());
                    level.playSound(null, d.body.getX(), d.body.getY(), d.body.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, 0.8f);
                }
            }
            case ICE -> {
                // Flies low and freezes the ground under it, trapping whoever is on it.
                d.nextAbility = now + 50;
                double y = SimNecron.ground(level, d.body.getBlockX(), d.body.getBlockZ(), d.body.getBlockY(), 1);
                fight.zone(new Vec3(d.body.getX(), y, d.body.getZ()), 4.0, 120, true, 0);
                level.playSound(null, d.body.getX(), y, d.body.getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2f, 0.6f);
            }
            case POWER -> {
                // Drops TNT round the middle of the room.
                d.nextAbility = now + 80;
                for (int i = 0; i < 3; i++) {
                    double a = r.nextDouble() * Math.PI * 2, dist = r.nextDouble() * 10;
                    int x = (int) Math.floor(MIDDLE.x + Math.cos(a) * dist), z = (int) Math.floor(MIDDLE.z + Math.sin(a) * dist);
                    double y = SimNecron.ground(level, x, z, 14, 1);
                    fight.blast(new Vec3(x + 0.5, y, z + 0.5), 4.0, 40, SimNecron.witherDamage(), 0xFF0000);
                    level.sendParticles(ParticleTypes.LARGE_SMOKE, x + 0.5, d.body.getY(), z + 0.5, 4, 0.2, 0.2, 0.2, 0);
                }
                level.playSound(null, MIDDLE.x, MIDDLE.y, MIDDLE.z, SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 3f, 1f);
            }
            case SOUL -> {
                // Teleports somewhere else in the room.
                d.nextAbility = now + 100;
                Vec3 to = new Vec3(ROOM.minX + r.nextDouble() * (ROOM.maxX - ROOM.minX),
                        ROOM.minY + r.nextDouble() * (ROOM.maxY - ROOM.minY), ROOM.minZ + r.nextDouble() * (ROOM.maxZ - ROOM.minZ));
                level.sendParticles(ParticleTypes.PORTAL, d.body.getX(), d.body.getY() + 1, d.body.getZ(), 60, 2, 2, 2, 0.5);
                d.body.snapTo(to.x, to.y, to.z, d.body.getYRot(), 0f);
                level.sendParticles(ParticleTypes.PORTAL, to.x, to.y + 1, to.z, 60, 2, 2, 2, 0.5);
                level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 3f, 0.6f);
            }
        }
    }

    /** The Apex Dragon's charge: straight at where the player stood, hitting through the touch check. */
    private static void charge(ServerLevel level, Dragon d, int now) {
        double f = 1 - Math.max(0, d.chargeEnd - now) / 25.0;
        Vec3 at = d.chargeFrom.lerp(d.chargeTo, f);
        face(d, at.subtract(d.body.position()));
        d.body.snapTo(at.x, at.y, at.z, d.body.getYRot(), 0f);
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 1, at.z, 2, 0.5, 0.5, 0.5, 0);
        if (now >= d.chargeEnd) {
            d.chargeEnd = -1;
        }
    }

    /**
     * Hypixel's spawn warning, as Wither Dragons reads it: FLAME, 20 of them, at y 19 over the statue, spread
     * 2/3/2, speed 0, on whole x and z. Sent to every player whatever the distance (vanilla's 32-block cut-off
     * would drop it for half the room).
     */
    private static void flames(ServerLevel level, Def def) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            level.sendParticles(p, ParticleTypes.FLAME, true, false, Math.floor(def.spawn().x), 19, Math.floor(def.spawn().z),
                    20, 2, 3, 2, 0);
        }
    }

    private void spawn(ServerLevel level, Dragon d) {
        DragonBody body = new DragonBody(level);
        body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        body.setHealth(1000);
        body.setPersistenceRequired();
        body.addTag(SimNecron.TAG);
        body.setCustomName(Component.literal(d.def.name() + " Dragon").withStyle(d.def.colour(), ChatFormatting.BOLD));
        body.snapTo(d.def.spawn().x + 0.5, d.def.spawn().y, d.def.spawn().z + 0.5, 0f, 0f);
        if (!level.addFreshEntity(body)) {
            return;
        }
        d.body = body;
        d.hp = maxHealth;
        d.nextBreath = fight.now() + 60;
        d.nextAbility = fight.now() + 40;
        d.angle = Math.random() * Math.PI * 2;
        mirror(d);
        // The collar Wither Dragons tells the dragons apart by: an armour stand over it wearing its relic.
        ArmorStand collar = new ArmorStand(level, body.getX(), body.getY() + 1.5, body.getZ());
        collar.setInvisible(true);
        collar.setNoGravity(true);
        collar.setPermanentlyInvulnerable(true);
        collar.addTag(SimNecron.TAG);
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", d.def.relicId());
        head.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        collar.setItemSlot(EquipmentSlot.HEAD, head);
        if (level.addFreshEntity(collar)) {
            d.collar = collar;
        }
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 4f, 1f);
    }

    /**
     * Circles a point that drifts from the statue toward the middle of the room and back (about twenty seconds a
     * round), so a dragon spends part of its time outside its kill box. The Power Dragon keeps to the middle and the
     * Ice Dragon flies low. Moves at most a block a tick, so it eases back after a charge or a teleport.
     */
    private static void fly(Dragon d) {
        Vec3 statue = new Vec3(d.def.spawn().x + 0.5, d.def.spawn().y, d.def.spawn().z + 0.5);
        double w = d.def.ability() == Ability.POWER ? 1.0 : 0.5 - 0.5 * Math.cos(Math.PI * 2 * d.age / 400.0);
        Vec3 anchor = statue.lerp(MIDDLE, w * 0.8);
        double baseY = d.def.ability() == Ability.ICE ? 8 : anchor.y;
        d.angle += 0.05;
        Vec3 to = new Vec3(anchor.x + Math.cos(d.angle) * 5, baseY + 2 * Math.sin(d.age / 20.0), anchor.z + Math.sin(d.angle) * 5);
        Vec3 from = d.body.position();
        Vec3 v = to.subtract(from);
        double len = v.length();
        Vec3 at = len <= 1.0 ? to : from.add(v.scale(1.0 / len));
        face(d, v);
        d.body.snapTo(at.x, at.y, at.z, d.body.getYRot(), 0f);
    }

    /** The dragon's model (and its head part) faces (sin yaw, -cos yaw). */
    private static void face(Dragon d, Vec3 v) {
        if (v.horizontalDistanceSqr() > 1e-4) {
            d.body.setYRot((float) Math.toDegrees(Math.atan2(v.x, -v.z)));
        }
    }

    /** The vanilla health, for Wither Dragons' health display: the pool's share of 1000, never 0 (that is death). */
    private void mirror(Dragon d) {
        if (d.body != null) {
            d.body.setHealth((float) Math.max(1, 1000 * d.hp / Math.max(1, maxHealth)));
        }
    }

    private void die(ServerLevel level, Dragon d, int now) {
        MinecraftServer server = level.getServer();
        boolean inArea = d.def.area().contains(d.body.position());
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, d.body.getX(), d.body.getY(), d.body.getZ(), 2, 1, 1, 1, 0);
        level.playSound(null, d.body.getX(), d.body.getY(), d.body.getZ(), SoundEvents.ENDER_DRAGON_DEATH, SoundSource.HOSTILE, 3f, 1.2f);
        d.body.discard();
        if (d.collar != null) {
            d.collar.discard();
        }
        dragons.remove(d);
        Component name = Component.literal("The " + d.def.name().toUpperCase(Locale.ROOT) + " Dragon").withStyle(d.def.colour());
        if (inArea) {
            slain++;
            statueDown[DEFS.indexOf(d.def)] = true;
            SimNecron.broadcast(server, name.copy().append(Component.literal(" was slain at its statue! (" + slain + "/"
                    + STATUES_TO_WIN + ")").withStyle(ChatFormatting.GREEN)));
            nextSpawnAt = Math.min(nextSpawnAt, now + NEXT_AFTER_KILL);
            if (slain < STATUES_TO_WIN) {
                SimBoss.say(server, "Wither King", KING_STATUE[ThreadLocalRandom.current().nextInt(KING_STATUE.length)]);
            }
        } else {
            diedAway[DEFS.indexOf(d.def)] = true;
            // Killed away from its statue: the statue stands, so it can come back (Hypixel: "indefinitely").
            SimNecron.broadcast(server, name.copy().append(Component.literal(" died away from its statue and will be back!")
                    .withStyle(ChatFormatting.RED)));
        }
    }

    // ---------------------------------------------------------------------------------------------- the Wither King

    /** With all five relics in, the Wither King appears on his throne. */
    private void summonKing(ServerLevel level) {
        double y = SimNecron.ground(level, (int) Math.floor(THRONE.x), (int) Math.floor(THRONE.z), 20, 1);
        king = fight.spawnWither(level, new Vec3(THRONE.x, y + 1, THRONE.z));
        king.setCustomName(Component.literal("Wither King").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        int now = fight.now();
        nextPull = now + 40;
        nextLightning = now + 60;
        kingBeam = 0;
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, king.getX(), king.getY() + 1, king.getZ(), 1, 0, 0, 0, 0);
        level.playSound(null, king.getX(), king.getY(), king.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 6f, 0.7f);
    }

    /**
     * The Wither King's attacks (his page; no numbers, timings and damage APPROXIMATE): a pull that drags a close
     * player in and kills him, a beam that sweeps round the throne room, and lightning on players.
     */
    private void tickKing(ServerLevel level, int now) {
        if (king == null) {
            return;
        }
        if (pulling != null) {
            Vec3 v = king.position().subtract(pulling.position());
            pulling.setDeltaMovement(v.normalize().scale(0.8));
            pulling.syncVelocity = true;
            if (now >= pullEnd || pulling.distanceToSqr(king) < 3 * 3) {
                if (pulling.isAlive()) {
                    SimNecron.hurtPlayerTrue(level, pulling, SimNecron.KILL_SHARE, 0);
                }
                pulling = null;
            }
        } else if (now >= nextPull) {
            for (ServerPlayer p : fight.players(level)) {
                if (p.distanceToSqr(king) < PULL_RANGE * PULL_RANGE) {
                    pulling = p;
                    pullEnd = now + 20;
                    nextPull = now + 100;
                    level.playSound(null, king.getX(), king.getY(), king.getZ(), SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 4f, 0.5f);
                    break;
                }
            }
        }
        // The beam swings back and forth across the room in front of the throne.
        kingBeam += 0.03;
        double a = Math.PI / 2 + Math.sin(kingBeam) * 1.1;
        Vec3 from = king.position().add(0, 1, 0);
        Vec3 to = from.add(Math.cos(a) * 30, 0, Math.sin(a) * 30);
        if (now % 2 == 0) {
            SimNecron.beam(level, new DustParticleOptions(0x8800FF, 1.6f), from, to, 1.0);
        }
        for (ServerPlayer p : fight.players(level)) {
            if (now >= beamHitAt.getOrDefault(p.getUUID(), 0) && segmentDistance(p.position().add(0, 0.9, 0), from, to) < 1.2) {
                beamHitAt.put(p.getUUID(), now + 20);
                SimNecron.hurtPlayer(level, p, KING_HIT_X * SimNecron.witherDamage(), p.getX() - from.x, p.getZ() - from.z, 0.8, 0.4);
            }
        }
        if (now >= nextLightning) {
            for (ServerPlayer p : fight.players(level)) {
                fight.lightningBlast(p.position(), 2.5, 20, KING_HIT_X * SimNecron.witherDamage());
            }
            nextLightning = now + 120;
        }
    }

    private static double segmentDistance(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    // ---------------------------------------------------------------------------------------------- relics

    private static ArmorStand relicStand(ServerLevel level, Def d, Vec3 at) {
        ArmorStand s = new ArmorStand(level, at.x, at.y, at.z);
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setPermanentlyInvulnerable(true);
        s.setGlowingTag(true);
        s.addTag(SimNecron.TAG);
        s.setCustomName(Component.literal("Corrupted " + d.name() + " Relic").withStyle(d.colour()));
        s.setCustomNameVisible(true);
        s.setItemSlot(EquipmentSlot.HEAD, relicItem(d));
        level.addFreshEntity(s);
        return s;
    }

    private static ItemStack relicItem(Def d) {
        ItemStack item = new ItemStack(Items.PLAYER_HEAD);
        // The name is what Wither Dragons' relic place timer checks a placed relic's stand by.
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Corrupted " + d.name() + " Relic").withStyle(d.colour()));
        return item;
    }

    private void tickRelics(ServerLevel level) {
        for (ServerPlayer p : fight.players(level)) {
            String held = fight.carried(p);
            if (held == null) {
                for (Relic r : relics) {
                    if (r.stand != null && p.distanceToSqr(r.stand) < 2.0 * 2.0) {
                        pick(level, p, r);
                        break;
                    }
                }
                continue;
            }
            // The closest empty cauldron within reach takes it, right or wrong (they stand close together).
            int best = -1;
            double bestD = 1.5 * 1.5;
            for (int i = 0; i < DEFS.size(); i++) {
                int[] c = DEFS.get(i).cauldron();
                double dx = p.getX() - (c[0] + 0.5);
                double dz = p.getZ() - (c[2] + 0.5);
                if (!relicPlaced[i] && dx * dx + dz * dz < bestD && Math.abs(p.getY() - c[1]) < 3) {
                    best = i;
                    bestD = dx * dx + dz * dz;
                }
            }
            if (best >= 0) {
                if (DEFS.get(best).name().equals(held)) {
                    place(level, p, best);
                } else {
                    fight.uncarry(p);
                    SimNecron.broadcast(level.getServer(), Component.literal(p.getGameProfile().name()
                            + " put the Corrupted " + held + " Relic in the wrong cauldron!").withStyle(ChatFormatting.RED));
                    fight.failRun(level, "a relic went in the wrong cauldron.");
                }
            }
        }
    }

    private void pick(ServerLevel level, ServerPlayer p, Relic r) {
        r.stand.discard();
        r.stand = null;
        fight.carry(p, r.def.name(), relicItem(r.def));
        // Hypixel's line, word for word: Wither Dragons' relic place timer waits for this relic from here.
        SimNecron.broadcast(level.getServer(), Component.literal(p.getGameProfile().name() + " picked the Corrupted "
                + r.def.name() + " Relic!"));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1f, 1f);
    }

    private void place(ServerLevel level, ServerPlayer p, int i) {
        Def d = DEFS.get(i);
        fight.uncarry(p);
        relicPlaced[i] = true;
        int[] c = d.cauldron();
        // Where NoammAddons' place timer looks for a placed relic: one block over from the cauldron on x and z.
        ArmorStand s = new ArmorStand(level, c[0] + 1.0, c[1] - 1, c[2] + 1.0);
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setPermanentlyInvulnerable(true);
        s.addTag(SimNecron.TAG);
        s.setItemSlot(EquipmentSlot.HEAD, relicItem(d));
        level.addFreshEntity(s);
        level.playSound(null, c[0] + 0.5, c[1], c[2] + 0.5, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.PLAYERS, 1.5f, 1f);
        SimNecron.broadcast(level.getServer(), Component.literal("The Corrupted " + d.name() + " Relic was placed!")
                .withStyle(d.colour()));
        boolean all = true;
        for (boolean b : relicPlaced) {
            all &= b;
        }
        if (all && king == null) {
            summonKing(level);
        }
    }

    /** A click on a relic (the fight's use/attack callbacks). */
    void touch(ServerPlayer p, Entity e) {
        if (fight.carried(p) != null) {
            return;
        }
        for (Relic r : relics) {
            if (r.stand == e && p.distanceToSqr(e) < 5 * 5) {
                pick((ServerLevel) p.level(), p, r);
                return;
            }
        }
    }

    void discard() {
        for (Dragon d : dragons) {
            if (d.body != null) {
                d.body.discard();
            }
            if (d.collar != null) {
                d.collar.discard();
            }
        }
        dragons.clear();
        for (Relic r : relics) {
            if (r.stand != null) {
                r.stand.discard();
                r.stand = null;
            }
        }
        if (king != null) {
            king.discard();
            king = null;
        }
        pulling = null;
    }

    /**
     * A dragon's body: no AI, so none of the vanilla dragon's flight, block breaking or End fight. That also stops
     * vanilla moving its parts, so they are put round the body here (the hit boxes arrows find), roughly where the
     * vanilla dragon has them: head and neck ahead, wings to the sides, tail behind.
     */
    static final class DragonBody extends EnderDragon {
        DragonBody(Level level) {
            super(EntityTypes.ENDER_DRAGON, level);
            setNoAi(true);
        }

        @Override
        public void aiStep() {
            super.aiStep();
            if (level().isClientSide()) {
                return;
            }
            double s = Math.sin(Math.toRadians(getYRot()));
            double c = Math.cos(Math.toRadians(getYRot()));
            EnderDragonPart[] parts = getSubEntities();
            // Vanilla's order: head, neck, body, tail x3, wing x2.
            double[][] at = {
                {s * 6.5, 1, -c * 6.5}, {s * 5.5, 1, -c * 5.5}, {s * 0.5, 0, -c * 0.5},
                {-s * 2.5, 0, c * 2.5}, {-s * 4.5, 0, c * 4.5}, {-s * 6.5, 0, c * 6.5},
                {c * 4.5, 2, s * 4.5}, {-c * 4.5, 2, -s * 4.5},
            };
            for (int i = 0; i < parts.length && i < at.length; i++) {
                parts[i].setPos(getX() + at[i][0], getY() + at[i][1], getZ() + at[i][2]);
            }
        }

        @Override
        public void checkDespawn() {
        }
    }
}
