package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.SimItemIds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floor 4's boss, Thorn, in his ring of spirit trees (https://hypixelskyblock.minecraft.wiki/w/Thorn).
 *
 * <p>Thorn floats over the middle and cannot be hurt by anything but the <b>Spirit Bow</b>. The loop:
 * <ol>
 * <li><b>Spirit animals</b> come in waves, each wave a little tougher than the last ({@link #WAVE_HP_GROWTH}):
 * Spirit Wolves (bite), Bulls (charge), Rabbits (launch you into the air, then run), Chickens (call lightning, then
 * settle into Chicken Mines), Sheep (walk up and explode) and Bats (go for players in the air). Each one killed
 * lights the next coal block of the ring round the arena into a sea lantern (the same blocks, in the same order,
 * that the mod's Spirit Bear HUD watches on Hypixel).</li>
 * <li>When this life's share of the ring is lit, the <b>Spirit Bear</b> appears {@link #BEAR_DELAY_TICKS} ticks
 * later (Hypixel's 68, Odin's number) - 25 kills in all on F4, 30 on M4. Killing it drops the Spirit Bow.</li>
 * <li>Pick the bow up and <b>shoot Thorn</b> with it: one hit takes one of his lives and stuns him for 5s, then he
 * cannot be hurt for 1s more. The bow is good for one arrow and crumbles if held too long; a miss or a crumbled bow
 * calls a new bear, without the kills.</li>
 * </ol>
 * The arena: now and then a circle on the floor turns <b>green</b> (a healing circle for players) or <b>orange</b>
 * (fire: spirit animals in it lose 5% of their health a second). Step up into the <b>crowd</b> and swarms of Spirit
 * Bats come for you. Thorn speeds up with every hit he takes, and on his last life turns his back to you.
 *
 * <p><b>Lives.</b> F4 takes {@link #LIVES_NORMAL} hits, M4 {@link #LIVES_MASTER}. Hypixel's Thorn has "4-6" health, one
 * taken per Spirit Bow hit; the range is read as F4-M4, the way the wiki writes every other Thorn number (the Spirit
 * Bear's "5M-180M" is its F4 and M4 health on its own page). M4's 30 ring blocks fill in six even shares of 5.
 *
 * <p><b>Health and damage.</b> The spirit animals and the Spirit Bear have Hypixel's health and hit ({@link Animal};
 * https://hypixelskyblock.minecraft.wiki/w/Spirit_Bear), before the player's Defense; the Bull's charge, the
 * Chicken's lightning and the Sheep's blast are the wiki's multiples of those.
 *
 * <p>Thorn's lines are Hypixel's, word for word (Boss Phases opens "Thorn" on the first). Thorn has no death line
 * the mod reads - Clean End and the splits end F4 on the chests line.
 */
final class SimThorn extends SimBoss.Fight {

    private static final int LIVES_NORMAL = 4;
    private static final int LIVES_MASTER = 6;
    private static final int BEAR_DELAY_TICKS = 68;
    /** A new bear after a miss or a crumbled bow. APPROXIMATE. */
    private static final int BEAR_AGAIN_TICKS = 100;
    /** How long a picked-up Spirit Bow lasts: F4, then M4. APPROXIMATE. */
    private static final int BOW_TICKS_NORMAL = 600;
    private static final int BOW_TICKS_MASTER = 400;
    /** How long a dropped bow lies on the ground. */
    private static final int DROP_TICKS = 600;
    private static final double PICKUP_RANGE = 1.8;
    private static final int MAX_SPIRITS_NORMAL = 8;
    private static final int MAX_SPIRITS_MASTER = 12;

    /** "After about 15 seconds of dialogue, he begins summoning Spirit Animals" (wiki). */
    private static final int INTRO_TICKS = 300;
    /** A bow hit stuns him for 5s, then 1s before he can be damaged again (wiki). */
    private static final int STUN_TICKS = 100;
    private static final int STUN_GRACE_TICKS = 20;
    /** Time between waves and each wave's size, F4 / M4. APPROXIMATE. */
    private static final int WAVE_EVERY_NORMAL = 200;
    private static final int WAVE_EVERY_MASTER = 160;
    private static final int WAVE_SIZE_NORMAL = 3;
    private static final int WAVE_SIZE_MASTER = 4;
    /** "Spirit Animals gain HP with each wave" (wiki); the step per wave is APPROXIMATE. */
    private static final double WAVE_HP_GROWTH = 0.05;
    /** Each life he loses makes him circle this much faster ("speeds up as he takes damage"). APPROXIMATE. */
    private static final double SPEED_PER_HIT = 0.4;

    /** Bull: winds up, then rushes in a straight line. The charge's hit is the wiki's; the rest APPROXIMATE. */
    private static final int BULL_EVERY = 120;
    private static final int BULL_WINDUP = 20;
    private static final int BULL_RUSH = 30;
    private static final double BULL_SPEED = 0.7;

    private static double bullChargeShare(boolean master) {
        return master ? 0.75 : 0.30;
    }

    /** Rabbit: launches a player it reaches, then runs. APPROXIMATE. */
    private static final double RABBIT_REACH = 1.6;
    private static final int RABBIT_EVERY = 80;
    private static final double RABBIT_LAUNCH = 1.4;

    /** Chicken: lightning at a nearby player after a warning, twice, then a Chicken Mine. APPROXIMATE. */
    private static final int CHICKEN_EVERY = 100;
    private static final int CHICKEN_WARN = 20;
    private static final double CHICKEN_REACH = 14;
    private static final double LIGHTNING_RADIUS = 2.0;
    private static final int CHICKEN_STRIKES = 2;
    private static final double MINE_TRIGGER = 1.5;
    private static final double MINE_RADIUS = 3.0;

    /** The chicken's lightning: 1.5x its base damage on F4, 0.35x on M4 (wiki). The mine reuses it, APPROXIMATE. */
    private static double chickenShare(boolean master) {
        return master ? 0.35 : 1.5;
    }

    /** Sheep: walks up, a creeper's fuse, a blast of up to 0.2x its base damage (wiki). Fuse and radius APPROXIMATE. */
    private static final double SHEEP_TRIGGER = 2.5;
    private static final int SHEEP_FUSE = 30;
    private static final double SHEEP_RADIUS = 4.0;
    private static final double SHEEP_SHARE = 0.2;

    /** Bats: 1,000 Defense (wiki), so a hit lands at 100 / 1,100; they ignore invincibility frames. */
    private static final double BAT_TAKEN = 100.0 / 1100.0;
    private static final double BAT_REACH = 1.4;
    private static final int BAT_BITE_EVERY = 10;
    /** The crowd's bats: "large damage" (wiki, no number) - a few Spirit Bat hits. APPROXIMATE, as are the rest. */
    private static final double CROWD_BAT_HITS = 3;
    private static final int CROWD_EVERY = 60;
    private static final int CROWD_SWARM = 3;
    private static final int CROWD_MAX = 12;

    /** The floor circles: how often one lights, how long, how big, and the green one's heal. APPROXIMATE. */
    private static final int CIRCLE_EVERY = 300;
    private static final int CIRCLE_TICKS = 200;
    private static final double CIRCLE_RADIUS = 3.5;
    private static final double CIRCLE_HEAL_PER_SECOND = 0.02;
    /** Orange fire: spirit animals in it lose 5% of their health per second (wiki). */
    private static final double FIRE_PER_SECOND = 0.05;

    /** The Spirit Wolf's 3.5x damage to Tanks (wiki). */
    private static final double WOLF_TANK_MULTIPLIER = 3.5;
    /** The Spirit Bear "heals over time, but very slowly" (wiki, no number): APPROXIMATE, 0.2% of its health a second. */
    private static final double BEAR_REGEN_PER_SECOND = 0.002;
    /**
     * The Spirit Bear's Spirit Bow (wiki: it attacks with a Golden Sword or a Spirit Bow, no separate numbers): a shot
     * at its base damage at a player out of sword reach. Range and cooldown APPROXIMATE.
     */
    private static final double BEAR_BOW_MIN = 5;
    private static final double BEAR_BOW_RANGE = 20;
    private static final int BEAR_BOW_EVERY = 60;

    /** The sim's own id for the boss's Spirit Bow. Not Hypixel's: nothing reads it but this class. */
    static final String SPIRIT_BOW_ID = "SIM_THORN_SPIRIT_BOW";

    /**
     * The ring of coal blocks round the arena, in the order they light, source coordinates (y 77). Odin's lists
     * ({@code SpiritBear}, F4 and M4), which are also exactly where this arena's coal blocks are.
     */
    private static final int[][] RING_F4 = {
            {-3, 33}, {-9, 31}, {-16, 26}, {-20, 20}, {-23, 13}, {-24, 6}, {-24, 0}, {-22, -7}, {-18, -13},
            {-12, -19}, {-5, -22}, {1, -24}, {8, -24}, {14, -23}, {21, -19}, {27, -14}, {31, -8}, {33, -1}, {34, 5},
            {33, 12}, {31, 19}, {27, 25}, {20, 30}, {14, 33}, {7, 34}};
    private static final int[][] RING_M4 = {
            {-2, 33}, {-7, 32}, {-13, 28}, {-17, 24}, {-21, 18}, {-23, 13}, {-24, 7}, {-24, 2}, {-23, -4}, {-21, -9},
            {-17, -14}, {-12, -19}, {-6, -22}, {-1, -23}, {5, -24}, {10, -24}, {16, -22}, {21, -19}, {27, -15},
            {30, -10}, {32, -5}, {34, 1}, {34, 7}, {33, 12}, {31, 18}, {28, 23}, {23, 28}, {18, 31}, {12, 33},
            {7, 34}};
    private static final int RING_Y = 77;

    /** Hypixel's lines, word for word. */
    private static final String[] INTRO_LINES = {
            "Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!",
            "My Arena is the most entertaining place in all the Catacombs!",
            "Today you'll be our spectacle.",
            "Dance! Dance with my Spirit animals! And may you perish in a delightful way!"};
    private static final String[] WAVE_LINES = {
            "Go my Spirits, go! Unsullied souls!",
            "More Spirits! Born of feathers and light!",
            "Dance for me humans! Your Art will be remembered!",
            "Can you hear the crowd?! The sound of death!",
            "Jump and laugh, scream and cry! Be passionate!"};
    private static final String[] HURT_LINES = {
            "I feel...dizzy...",
            "It hurts...what a delicate feeling...",
            "Round and round, another wound.",
            "My energy, it goes away..."};
    private static final String[] IMMUNE_LINES = {
            "Tickle tickle, you are in a pickle!",
            "I'm already dead adventurer! Earthly weapons won't affect me!",
            "Don't waste your energy on me, humans - I am Thorn the Spirit!",
            "I love your fighting spirit, but I am immune to your tricks!",
            "Trying to damage me like that...desperate..."};
    private static final String[] WIN_LINES = {
            "This is it...where shall I go now?",
            "All those memories will be lost in time, like tears in rain...",
            "Congratulations humans, you may pass."};

    /**
     * The spirit animals. Health and hit are Hypixel's for F4 and M4: the Thorn page lists each animal as a "min-max"
     * range, read as F4-M4 the way the wiki writes every boss's Normal and Master numbers; the bear has its own page.
     */
    private enum Animal {
        WOLF("Spirit Wolf", 450_000, 11_250_000, 3_000, 100_000),
        BULL("Spirit Bull", 1_350_000, 33_750_000, 2_822, 71_400),
        SHEEP("Spirit Sheep", 400_000, 10_000_000, 19_040, 666_400),
        CHICKEN("Spirit Chicken", 120_000, 3_000_000, 928, 26_880),
        RABBIT("Spirit Rabbit", 260_000, 6_500_000, 3_162, 60_000),
        BAT("Spirit Bat", 50_000, 1_250_000, 2_380, 59_500),
        /** Drops the Spirit Bow. Spawned by the ring, never by Thorn. */
        BEAR("Spirit Bear", 5_000_000, 180_000_000, 8_704, 154_700);

        final String display;
        final double healthF4;
        final double healthM4;
        final double damageF4;
        final double damageM4;

        Animal(String display, double healthF4, double healthM4, double damageF4, double damageM4) {
            this.display = display;
            this.healthF4 = healthF4;
            this.healthM4 = healthM4;
            this.damageF4 = damageF4;
            this.damageM4 = damageM4;
        }

        double damage() {
            return SimHealth.byMode(damageF4, damageM4);
        }
    }

    private static final Animal[] SUMMONS = {Animal.WOLF, Animal.WOLF, Animal.BULL, Animal.SHEEP, Animal.CHICKEN,
            Animal.RABBIT, Animal.BAT};

    private static final class Spirit {
        final Animal kind;
        final Mob body;
        final SimBoss.Health health;
        /** One of the crowd's bats: chases whoever is in the stands, never lights the ring. */
        final boolean crowd;
        int nextAct;
        int actUntil = -1;
        Vec3 dir;
        Vec3 mark;
        int strikes;
        boolean mine;
        int fuseAt = -1;
        int nextBite;

        Spirit(Animal kind, Mob body, double hp, boolean crowd) {
            this.kind = kind;
            this.body = body;
            this.health = new SimBoss.Health(hp);
            this.crowd = crowd;
        }
    }

    private enum Phase { INTRO, FIGHT, DYING, DEAD }

    /** Where the bow loop stands. */
    private enum Bow { GATHER, BEAR_SOON, BEAR, DROPPED, HELD, SHOT }

    /** A floor circle: green heals players, orange burns spirit animals. */
    private record Circle(Vec3 at, boolean green, int until) {
    }

    private Phase phase = Phase.INTRO;
    private int phaseTicks;
    private int clock;
    private Ghast thorn;
    private final int livesMax = master() ? LIVES_MASTER : LIVES_NORMAL;
    private int lives = livesMax;
    private final int[][] ring = master() ? RING_M4 : RING_F4;
    private int lit;
    private final List<Spirit> spirits = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();
    private Bow bow = Bow.GATHER;
    private int bowTimer;
    private Spirit bear;
    private ItemEntity drop;
    private UUID holder;
    private boolean arrowGiven;
    private AbstractArrow spiritArrow;
    private int nextWave;
    private int wave;
    private int nextCircle;
    private int nextCrowd;
    private int stunnedUntil = -1;
    private int vulnerableAt;
    private int hurtTicks;
    private int immuneLine;
    private double orbit;

    SimThorn(SimBossArena arena) {
        super(arena);
    }

    private static boolean master() {
        String f = SimState.floorLabel();
        return f != null && f.toUpperCase(Locale.ROOT).startsWith("M");
    }

    /** The ring blocks this life needs lit before its bear: an even share of the ring, the last life the rest. */
    private int threshold() {
        int life = livesMax - lives;
        return (int) Math.round((double) ring.length * (life + 1) / livesMax);
    }

    @Override
    String name() {
        return "Thorn";
    }

    @Override
    void start(ServerLevel level) {
        // Hypixel's first line, word for word: Boss Phases opens "Thorn" on it, and Dungeon Boss reads it.
        SimBoss.say(level.getServer(), "Thorn", INTRO_LINES[0]);
        phase = Phase.INTRO;
        phaseTicks = 0;
        spawnThorn(level);
    }

    @Override
    void tick(ServerLevel level, int t) {
        phaseTicks++;
        clock++;
        boolean master = master();
        switch (phase) {
            case INTRO -> {
                int line = phaseTicks / (INTRO_TICKS / INTRO_LINES.length);
                if (phaseTicks % (INTRO_TICKS / INTRO_LINES.length) == 0 && line > 0 && line < INTRO_LINES.length) {
                    SimBoss.say(level.getServer(), "Thorn", INTRO_LINES[line]);
                }
                hover(level, master);
                if (phaseTicks >= INTRO_TICKS) {
                    wave(level, master);
                    nextCircle = clock + CIRCLE_EVERY / 2;
                    phase = Phase.FIGHT;
                    phaseTicks = 0;
                }
            }
            case FIGHT -> {
                if (thorn == null || thorn.isRemoved()) {
                    spawnThorn(level);
                }
                boolean stunned = clock < stunnedUntil;
                if (!stunned) {
                    hover(level, master);
                    if (clock >= nextWave) {
                        wave(level, master);
                    }
                } else if (thorn != null && clock % 4 == 0) {
                    level.sendParticles(ParticleTypes.CRIT, thorn.getX(), thorn.getY() + 4.5, thorn.getZ(), 6,
                            0.6, 0.2, 0.6, 0.1);
                }
                tickSpirits(level, master);
                tickCircles(level);
                tickCrowd(level);
                tickBow(level, master);
                if (hurtTicks > 0) {
                    hurtTicks--;
                }
            }
            case DYING -> {
                if (phaseTicks == 30) {
                    SimBoss.say(level.getServer(), "Thorn", WIN_LINES[1]);
                } else if (phaseTicks == 60) {
                    SimBoss.say(level.getServer(), "Thorn", WIN_LINES[2]);
                } else if (phaseTicks >= 80) {
                    discard(level);
                    phase = Phase.DEAD;
                }
            }
            default -> {
            }
        }
        if (immuneLine > 0) {
            immuneLine--;
        }
        nameTags();
    }

    // ------------------------------------------------------------------ Thorn

    private Vec3 middle(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre);
        return new Vec3(c.getX() + 0.5, ground(level, c.getX(), c.getZ(), c.getY() + 2), c.getZ() + 0.5);
    }

    private static int ground(ServerLevel level, int x, int z, int fromY) {
        for (int y = fromY; y > fromY - 12; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                return y;
            }
        }
        return fromY;
    }

    private void spawnThorn(ServerLevel level) {
        Vec3 m = middle(level);
        Ghast g = new Ghast(EntityTypes.GHAST, level);
        g.setNoAi(true);
        g.setNoGravity(true);
        g.setPersistenceRequired();
        g.setCustomNameVisible(true);
        g.snapTo(m.x, m.y + 5, m.z, 180f, 0f);
        level.addFreshEntity(g);
        thorn = g;
    }

    /**
     * A slow circle over the middle, bobbing so a shot has to be aimed; faster for every life he has lost, and on
     * his last life he turns his back on the nearest player (wiki: "turns around, making him harder to hit").
     */
    private void hover(ServerLevel level, boolean master) {
        if (thorn == null) {
            return;
        }
        Vec3 m = middle(level);
        double speed = (master ? 0.025 : 0.015) * (1 + SPEED_PER_HIT * (livesMax - lives));
        orbit += speed;
        double x = m.x + Math.cos(orbit) * 4;
        double z = m.z + Math.sin(orbit) * 4;
        double y = m.y + 5 + Math.sin(clock * 0.06) * 1.0;
        ServerPlayer near = nearest(SimBoss.playersInRing(level), thorn.position());
        float yaw = thorn.getYRot();
        if (near != null) {
            yaw = (float) (Math.toDegrees(Math.atan2(near.getZ() - z, near.getX() - x)) - 90);
            if (lives == 1) {
                yaw += 180f;
            }
        }
        thorn.snapTo(x, y, z, yaw, 0f);
        thorn.setYHeadRot(yaw);
        thorn.setYBodyRot(yaw);
    }

    /** A wave of animals, each a little tougher than the last wave's, with one of Thorn's wave lines. */
    private void wave(ServerLevel level, boolean master) {
        wave++;
        nextWave = clock + (master ? WAVE_EVERY_MASTER : WAVE_EVERY_NORMAL);
        int n = master ? WAVE_SIZE_MASTER : WAVE_SIZE_NORMAL;
        if (wave == 1) {
            n += 2;
        }
        int made = 0;
        for (int i = 0; i < n; i++) {
            Animal kind = wave == 1 ? SUMMONS[i % SUMMONS.length]
                    : SUMMONS[ThreadLocalRandom.current().nextInt(SUMMONS.length)];
            if (summon(level, kind, false) != null) {
                made++;
            }
        }
        if (made > 0) {
            SimBoss.say(level.getServer(), "Thorn", WAVE_LINES[ThreadLocalRandom.current().nextInt(WAVE_LINES.length)]);
        }
    }

    // ------------------------------------------------------------------ the spirit animals

    private int maxSpirits() {
        return master() ? MAX_SPIRITS_MASTER : MAX_SPIRITS_NORMAL;
    }

    /** A random standable spot in the ring, away from the middle. */
    private Vec3 ringSpot(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double a = rnd.nextDouble() * Math.PI * 2;
        double d = 8 + rnd.nextDouble() * Math.max(1, arena.radius - 12);
        int x = (int) Math.floor(c.getX() + 0.5 + Math.cos(a) * d);
        int z = (int) Math.floor(c.getZ() + 0.5 + Math.sin(a) * d);
        return new Vec3(x + 0.5, ground(level, x, z, c.getY() + 4), z + 0.5);
    }

    private Spirit summon(ServerLevel level, Animal kind, boolean crowd) {
        int living = 0;
        int crowdLiving = 0;
        for (Spirit s : spirits) {
            if (s.crowd) {
                crowdLiving++;
            } else if (s.kind != Animal.BEAR) {
                living++;
            }
        }
        if (crowd ? crowdLiving >= CROWD_MAX : kind != Animal.BEAR && living >= maxSpirits()) {
            return null;
        }
        Mob body = switch (kind) {
            case WOLF -> new Wolf(EntityTypes.WOLF, level);
            case BULL -> new Cow(EntityTypes.COW, level);
            case SHEEP -> new Sheep(EntityTypes.SHEEP, level);
            case CHICKEN -> new Chicken(EntityTypes.CHICKEN, level);
            case RABBIT -> new Rabbit(EntityTypes.RABBIT, level);
            case BAT -> new Bat(EntityTypes.BAT, level);
            case BEAR -> new PolarBear(EntityTypes.POLAR_BEAR, level);
        };
        if (kind == Animal.BULL) {
            // A bull, not a cow: a size up. Looks only.
            body.getAttribute(Attributes.SCALE).setBaseValue(1.3);
        }
        Vec3 at = ringSpot(level);
        body.setPersistenceRequired();
        body.setCustomNameVisible(true);
        body.snapTo(at.x, at.y + (kind == Animal.BAT ? 2 : 0), at.z, ThreadLocalRandom.current().nextFloat() * 360,
                0f);
        level.addFreshEntity(body);
        level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.8, at.z, 12, 0.3, 0.5, 0.3, 0.02);
        double hp = SimHealth.byMode(kind.healthF4, kind.healthM4);
        if (kind != Animal.BEAR && !crowd) {
            hp *= 1 + WAVE_HP_GROWTH * Math.max(0, wave - 1);
        }
        double hit = kind.damage() * (crowd ? CROWD_BAT_HITS : 1);
        // The wolves' and the bear's own swings (vanilla AI) read this; the others' attacks are the fight's.
        SimHealth.setBossHit(body, hit);
        if (kind == Animal.WOLF) {
            // "3.5x Damage to Tank class" (wiki).
            SimHealth.setTankMultiplier(body, WOLF_TANK_MULTIPLIER);
        }
        Spirit s = new Spirit(kind, body, hp, crowd);
        s.nextAct = clock + 40 + ThreadLocalRandom.current().nextInt(40);
        spirits.add(s);
        if (kind == Animal.BEAR) {
            bear = s;
        }
        return s;
    }

    /** Each animal's own attack, and every one kept inside the ring. */
    private void tickSpirits(ServerLevel level, boolean master) {
        BlockPos c = arena.toWorld(arena.centre);
        double limit = (arena.radius + 2.0) * (arena.radius + 2.0);
        List<ServerPlayer> players = SimBoss.playersInRing(level);
        for (Spirit s : new ArrayList<>(spirits)) {
            if (!spirits.contains(s)) {
                continue;
            }
            if (s.body.isRemoved() || !s.body.isAlive()) {
                // Gone some other way (the void, /kill): forget it without counting it.
                spirits.remove(s);
                if (s == bear) {
                    bear = null;
                    bow = Bow.BEAR_SOON;
                    bowTimer = BEAR_AGAIN_TICKS;
                }
                continue;
            }
            if (!s.crowd) {
                double dx = s.body.getX() - (c.getX() + 0.5);
                double dz = s.body.getZ() - (c.getZ() + 0.5);
                if (dx * dx + dz * dz > limit || Math.abs(s.body.getY() - c.getY()) > 12) {
                    Vec3 back = ringSpot(level);
                    s.body.snapTo(back.x, back.y + (s.kind == Animal.BAT ? 2 : 0), back.z);
                }
            }
            switch (s.kind) {
                case WOLF -> {
                    if (clock % 20 == 0) {
                        ServerPlayer target = nearest(players, s.body.position());
                        if (target != null) {
                            s.body.setTarget(target);
                        }
                    }
                }
                case BEAR -> tickBear(level, s, players);
                case BULL -> tickBull(level, s, players, master);
                case RABBIT -> tickRabbit(level, s, players);
                case CHICKEN -> tickChicken(level, s, players, master);
                case SHEEP -> tickSheep(level, s, players);
                case BAT -> tickBat(level, s, s.crowd ? crowdPlayers(level) : players);
            }
        }
    }

    /**
     * Spirit Bear: its Golden Sword is the vanilla swing (its hit is the wiki's, {@link SimHealth#setBossHit}); a player
     * out of reach gets a Spirit Bow shot instead. It heals very slowly the whole time.
     */
    private void tickBear(ServerLevel level, Spirit s, List<ServerPlayer> players) {
        ServerPlayer target = nearest(players, s.body.position());
        if (clock % 20 == 0) {
            if (target != null) {
                s.body.setTarget(target);
            }
            s.health.hp = Math.min(s.health.max, s.health.hp + s.health.max * BEAR_REGEN_PER_SECOND);
        }
        if (target == null || clock < s.nextAct) {
            return;
        }
        double d = s.body.distanceTo(target);
        if (d < BEAR_BOW_MIN || d > BEAR_BOW_RANGE || !s.body.hasLineOfSight(target)) {
            return;
        }
        s.nextAct = clock + BEAR_BOW_EVERY;
        Vec3 from = s.body.getEyePosition();
        Vec3 to = target.getEyePosition().subtract(0, 0.4, 0);
        Vec3 step = to.subtract(from);
        int points = (int) Math.max(4, step.length() * 2);
        for (int i = 0; i <= points; i++) {
            Vec3 p = from.add(step.scale((double) i / points));
            level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        level.playSound(null, s.body.getX(), s.body.getY(), s.body.getZ(), SoundEvents.ARROW_SHOOT,
                SoundSource.HOSTILE, 1f, 0.8f);
        SimVitals.dealHypixel(level, target, level.damageSources().magic(), s.kind.damage(), false);
    }

    /** Spirit Bull: a wind-up facing its target, then a straight rush that tramples whoever it runs into. */
    private void tickBull(ServerLevel level, Spirit s, List<ServerPlayer> players, boolean master) {
        if (s.actUntil < 0) {
            if (clock % 20 == 0) {
                ServerPlayer target = nearest(players, s.body.position());
                if (target != null) {
                    s.body.getNavigation().moveTo(target, 1.0);
                }
            }
            if (clock >= s.nextAct) {
                ServerPlayer target = nearest(players, s.body.position());
                if (target != null) {
                    Vec3 d = target.position().subtract(s.body.position());
                    s.dir = new Vec3(d.x, 0, d.z).normalize();
                    s.actUntil = clock + BULL_WINDUP + BULL_RUSH;
                    s.body.getNavigation().stop();
                    level.playSound(null, s.body.getX(), s.body.getY(), s.body.getZ(), SoundEvents.RAVAGER_ROAR,
                            SoundSource.HOSTILE, 0.8f, 1.4f);
                } else {
                    s.nextAct = clock + 20;
                }
            }
            return;
        }
        Vec3 from = s.body.position();
        float yaw = (float) (Math.toDegrees(Math.atan2(s.dir.z, s.dir.x)) - 90);
        if (clock < s.actUntil - BULL_RUSH) {
            // Wind-up: stamps in place, facing the line it will run.
            s.body.snapTo(from.x, from.y, from.z, yaw, 0f);
            s.body.setYBodyRot(yaw);
            s.body.setYHeadRot(yaw);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, from.x, from.y + 1.6, from.z, 1, 0.3, 0.2, 0.3, 0);
            return;
        }
        double nx = from.x + s.dir.x * BULL_SPEED;
        double nz = from.z + s.dir.z * BULL_SPEED;
        int y = ground(level, (int) Math.floor(nx), (int) Math.floor(nz), (int) Math.floor(from.y) + 1);
        boolean blocked = Math.abs(y - from.y) > 1.5;
        if (!blocked) {
            s.body.snapTo(nx, y, nz, yaw, 0f);
            s.body.setYBodyRot(yaw);
            s.body.setYHeadRot(yaw);
            level.sendParticles(ParticleTypes.CLOUD, nx, y + 0.1, nz, 2, 0.3, 0, 0.3, 0.01);
            for (ServerPlayer p : players) {
                if (p.getBoundingBox().inflate(0.6).intersects(s.body.getBoundingBox())) {
                    SimVitals.dealHypixel(level, p, level.damageSources().magic(),
                            s.kind.damage() * bullChargeShare(master), false);
                    p.setDeltaMovement(s.dir.x * 1.5, 0.6, s.dir.z * 1.5);
                    p.syncVelocity = true;
                    blocked = true;
                    break;
                }
            }
        }
        if (blocked || clock >= s.actUntil) {
            s.actUntil = -1;
            s.nextAct = clock + BULL_EVERY;
        }
    }

    /** Spirit Rabbit: hops up to a player, launches him into the air, then runs off. */
    private void tickRabbit(ServerLevel level, Spirit s, List<ServerPlayer> players) {
        if (s.actUntil >= 0) {
            if (clock >= s.actUntil) {
                s.actUntil = -1;
            }
            return;
        }
        ServerPlayer target = nearest(players, s.body.position());
        if (target == null) {
            return;
        }
        if (clock % 10 == 0) {
            s.body.getNavigation().moveTo(target, 1.6);
        }
        if (clock >= s.nextAct && s.body.distanceTo(target) <= RABBIT_REACH) {
            SimVitals.dealHypixel(level, target, level.damageSources().magic(), s.kind.damage(), false);
            target.setDeltaMovement(target.getDeltaMovement().x, RABBIT_LAUNCH, target.getDeltaMovement().z);
            target.syncVelocity = true;
            level.playSound(null, s.body.getX(), s.body.getY(), s.body.getZ(), SoundEvents.RABBIT_JUMP,
                    SoundSource.HOSTILE, 1f, 0.8f);
            level.sendParticles(ParticleTypes.CLOUD, target.getX(), target.getY(), target.getZ(), 8, 0.4, 0.1, 0.4,
                    0.05);
            // Run: eight blocks straight away from him.
            Vec3 away = s.body.position().subtract(target.position());
            away = new Vec3(away.x, 0, away.z).normalize().scale(8);
            s.body.getNavigation().moveTo(s.body.getX() + away.x, s.body.getY(), s.body.getZ() + away.z, 2.0);
            s.actUntil = clock + 40;
            s.nextAct = clock + RABBIT_EVERY;
        }
    }

    /** Spirit Chicken: marks a nearby player's spot and calls lightning on it; after a few, it becomes a mine. */
    private void tickChicken(ServerLevel level, Spirit s, List<ServerPlayer> players, boolean master) {
        double damage = s.kind.damage() * chickenShare(master);
        if (s.mine) {
            if (clock % 10 == 0) {
                level.sendParticles(new DustParticleOptions(0xFF3030, 1.0f), s.body.getX(), s.body.getY() + 0.2,
                        s.body.getZ(), 3, 0.3, 0.05, 0.3, 0);
            }
            for (ServerPlayer p : players) {
                if (p.position().distanceTo(s.body.position()) <= MINE_TRIGGER) {
                    Vec3 at = s.body.position();
                    blast(level, at, MINE_RADIUS, damage, false);
                    s.body.discard();
                    spirits.remove(s);
                    return;
                }
            }
            return;
        }
        if (s.mark != null) {
            if (clock < s.actUntil) {
                if (clock % 3 == 0) {
                    ring(level, s.mark, LIGHTNING_RADIUS, new DustParticleOptions(0xFFFF55, 1.2f), 12);
                }
                return;
            }
            LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, level);
            bolt.setVisualOnly(true);
            bolt.snapTo(s.mark.x, s.mark.y, s.mark.z);
            level.addFreshEntity(bolt);
            for (ServerPlayer p : players) {
                if (horizontal(p.position(), s.mark) <= LIGHTNING_RADIUS && Math.abs(p.getY() - s.mark.y) < 3) {
                    SimVitals.dealHypixel(level, p, level.damageSources().magic(), damage, false);
                }
            }
            s.mark = null;
            s.actUntil = -1;
            s.nextAct = clock + CHICKEN_EVERY;
            if (++s.strikes >= CHICKEN_STRIKES) {
                // Settles on the ground as a Chicken Mine.
                s.mine = true;
                s.body.setNoAi(true);
                s.body.setGlowingTag(true);
                s.body.getNavigation().stop();
            }
            return;
        }
        if (clock >= s.nextAct) {
            ServerPlayer target = nearest(players, s.body.position());
            if (target != null && target.position().distanceTo(s.body.position()) <= CHICKEN_REACH) {
                s.mark = target.position();
                s.actUntil = clock + CHICKEN_WARN;
                level.playSound(null, s.body.getX(), s.body.getY(), s.body.getZ(), SoundEvents.CHICKEN_EGG,
                        SoundSource.HOSTILE, 1f, 0.6f);
            } else {
                s.nextAct = clock + 20;
            }
        }
    }

    /** Spirit Sheep: walks up to a player, swells like a creeper and explodes. */
    private void tickSheep(ServerLevel level, Spirit s, List<ServerPlayer> players) {
        if (s.fuseAt >= 0) {
            if (clock % 2 == 0) {
                level.sendParticles(ParticleTypes.SMOKE, s.body.getX(), s.body.getY() + 1, s.body.getZ(), 4,
                        0.3, 0.3, 0.3, 0.02);
            }
            if (clock >= s.fuseAt) {
                blast(level, s.body.position(), SHEEP_RADIUS, s.kind.damage() * SHEEP_SHARE, true);
                s.body.discard();
                spirits.remove(s);
            }
            return;
        }
        ServerPlayer target = nearest(players, s.body.position());
        if (target == null) {
            return;
        }
        if (clock % 20 == 0) {
            s.body.getNavigation().moveTo(target, 1.2);
        }
        if (s.body.distanceTo(target) <= SHEEP_TRIGGER) {
            s.fuseAt = clock + SHEEP_FUSE;
            s.body.getNavigation().stop();
            s.body.setGlowingTag(true);
            level.playSound(null, s.body.getX(), s.body.getY(), s.body.getZ(), SoundEvents.CREEPER_PRIMED,
                    SoundSource.HOSTILE, 1f, 0.8f);
        }
    }

    /** Spirit Bat: goes for players in the air (the crowd's go for whoever is in the stands), through i-frames. */
    private void tickBat(ServerLevel level, Spirit s, List<ServerPlayer> players) {
        ServerPlayer target = null;
        double best = 16 * 16;
        for (ServerPlayer p : players) {
            if (!s.crowd && p.onGround()) {
                continue;
            }
            double d = p.distanceToSqr(s.body);
            if (d < best) {
                best = d;
                target = p;
            }
        }
        if (target == null) {
            return;
        }
        Vec3 to = target.getEyePosition().subtract(0, 0.4, 0).subtract(s.body.position());
        if (to.length() > BAT_REACH * 0.8) {
            Vec3 step = to.normalize().scale(Math.min(to.length(), 0.45));
            s.body.snapTo(s.body.getX() + step.x, s.body.getY() + step.y, s.body.getZ() + step.z);
        }
        if (to.length() <= BAT_REACH && clock >= s.nextBite) {
            s.nextBite = clock + BAT_BITE_EVERY;
            // "Ignores invincibility frames" (wiki).
            target.setInvulnerableTime(0);
            SimVitals.dealHypixel(level, target, level.damageSources().magic(), SimHealth.hitOf(s.body), false);
        }
    }

    /** An explosion: {@code damage} at the middle, down to nothing at the edge when {@code fallOff}. */
    private static void blast(ServerLevel level, Vec3 at, double radius, double damage, boolean fallOff) {
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 0.6, 0.3, 0.6, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1f, 1.1f);
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            double d = p.position().distanceTo(at);
            if (d > radius) {
                continue;
            }
            double share = fallOff ? Math.max(0.1, 1 - d / radius) : 1;
            SimVitals.dealHypixel(level, p, level.damageSources().magic(), damage * share, false);
            Vec3 away = p.position().subtract(at);
            double len = Math.max(0.01, Math.sqrt(away.x * away.x + away.z * away.z));
            p.setDeltaMovement(away.x / len * 0.8, 0.5, away.z / len * 0.8);
            p.syncVelocity = true;
        }
    }

    private void killSpirit(ServerLevel level, Spirit s) {
        Vec3 at = s.body.position();
        level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.6, at.z, 15, 0.3, 0.4, 0.3, 0.05);
        level.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.6, at.z, 8, 0.3, 0.3, 0.3, 0.02);
        s.body.discard();
        spirits.remove(s);
        if (s.kind == Animal.BEAR) {
            bear = null;
            dropBow(level, at);
            return;
        }
        if (!s.crowd) {
            lightNext(level);
        }
    }

    /** One more ring block lit for a kill, while this life still needs it. */
    private void lightNext(ServerLevel level) {
        if (bow != Bow.GATHER || lit >= threshold() || lit >= ring.length) {
            return;
        }
        int[] r = ring[lit++];
        BlockPos pos = arena.toWorld(r[0], RING_Y, r[1]);
        level.setBlock(pos, Blocks.SEA_LANTERN.defaultBlockState(), 3);
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6,
                0.3, 0.3, 0.3, 0.02);
        if (lit >= threshold()) {
            bow = Bow.BEAR_SOON;
            bowTimer = BEAR_DELAY_TICKS;
        }
    }

    // ------------------------------------------------------------------ the arena: circles and the crowd

    private void tickCircles(ServerLevel level) {
        if (clock >= nextCircle) {
            nextCircle = clock + CIRCLE_EVERY;
            circles.add(new Circle(ringSpot(level), ThreadLocalRandom.current().nextBoolean(), clock + CIRCLE_TICKS));
        }
        if (circles.isEmpty()) {
            return;
        }
        circles.removeIf(cl -> clock >= cl.until());
        boolean second = clock % 20 == 0;
        for (Circle cl : circles) {
            if (clock % 4 == 0) {
                ring(level, cl.at(), CIRCLE_RADIUS,
                        new DustParticleOptions(cl.green() ? 0x55FF55 : 0xFF8800, 1.5f), 28);
                if (!cl.green()) {
                    level.sendParticles(ParticleTypes.FLAME, cl.at().x, cl.at().y + 0.1, cl.at().z, 6,
                            CIRCLE_RADIUS / 2, 0.05, CIRCLE_RADIUS / 2, 0.01);
                }
            }
            if (!second) {
                continue;
            }
            if (cl.green()) {
                for (ServerPlayer p : SimBoss.playersInRing(level)) {
                    if (horizontal(p.position(), cl.at()) <= CIRCLE_RADIUS) {
                        SimVitals.healFraction(p, CIRCLE_HEAL_PER_SECOND);
                        level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2, p.getZ(), 1, 0.2, 0.1,
                                0.2, 0);
                    }
                }
            } else {
                for (Spirit s : new ArrayList<>(spirits)) {
                    if (s.kind == Animal.BEAR || horizontal(s.body.position(), cl.at()) > CIRCLE_RADIUS) {
                        continue;
                    }
                    s.health.hp = Math.max(0, s.health.hp - s.health.max * FIRE_PER_SECOND);
                    level.sendParticles(ParticleTypes.FLAME, s.body.getX(), s.body.getY() + 0.5, s.body.getZ(), 4,
                            0.2, 0.3, 0.2, 0.01);
                    if (s.health.hp <= 0) {
                        killSpirit(level, s);
                    }
                }
            }
        }
    }

    /**
     * Players up in the stands round the ring: in the arena, past the ring's edge and above its floor. The bounds
     * are the sim's reading of where the crowd sits in this arena. APPROXIMATE.
     */
    private List<ServerPlayer> crowdPlayers(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre);
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            double dx = p.getX() - (c.getX() + 0.5);
            double dz = p.getZ() - (c.getZ() + 0.5);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (p.isAlive() && arena.contains(p.getX(), p.getY(), p.getZ()) && d > arena.radius
                    && d <= arena.radius + 14 && p.getY() >= c.getY() + 3) {
                out.add(p);
            }
        }
        return out;
    }

    /** Entering the crowd calls swarms of Spirit Bats on you; they leave once nobody is up there. */
    private void tickCrowd(ServerLevel level) {
        if (clock < nextCrowd) {
            return;
        }
        nextCrowd = clock + CROWD_EVERY;
        List<ServerPlayer> crowd = crowdPlayers(level);
        if (crowd.isEmpty()) {
            for (Spirit s : new ArrayList<>(spirits)) {
                if (s.crowd) {
                    s.body.discard();
                    spirits.remove(s);
                }
            }
            return;
        }
        for (ServerPlayer p : crowd) {
            for (int i = 0; i < CROWD_SWARM; i++) {
                Spirit s = summon(level, Animal.BAT, true);
                if (s == null) {
                    return;
                }
                s.body.snapTo(p.getX() + ThreadLocalRandom.current().nextDouble() * 4 - 2, p.getY() + 3,
                        p.getZ() + ThreadLocalRandom.current().nextDouble() * 4 - 2);
            }
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BAT_TAKEOFF, SoundSource.HOSTILE, 1f,
                    0.7f);
        }
    }

    // ------------------------------------------------------------------ the Spirit Bear and the Spirit Bow

    private void tickBow(ServerLevel level, boolean master) {
        switch (bow) {
            case BEAR_SOON -> {
                if (--bowTimer <= 0) {
                    summon(level, Animal.BEAR, false);
                    bow = Bow.BEAR;
                    SimBoss.tell(level.getServer(), Component.literal("A Spirit Bear has appeared!")
                            .withStyle(ChatFormatting.GOLD));
                    if (bear != null) {
                        level.playSound(null, bear.body.getX(), bear.body.getY(), bear.body.getZ(),
                                SoundEvents.POLAR_BEAR_WARNING, SoundSource.HOSTILE, 1.2f, 0.8f);
                    }
                }
            }
            case DROPPED -> {
                if (drop == null || drop.isRemoved() || --bowTimer <= 0) {
                    if (drop != null) {
                        drop.discard();
                        drop = null;
                    }
                    lost(level, "The Spirit Bow faded away.");
                    return;
                }
                level.sendParticles(ParticleTypes.END_ROD, drop.getX(), drop.getY() + 0.5, drop.getZ(), 1,
                        0.2, 0.2, 0.2, 0);
                for (ServerPlayer p : SimBoss.playersInRing(level)) {
                    if (p.distanceToSqr(drop) <= PICKUP_RANGE * PICKUP_RANGE) {
                        pickUp(level, p, master);
                        break;
                    }
                }
            }
            case HELD -> {
                ServerPlayer p = holderPlayer(level);
                if (p == null || --bowTimer <= 0) {
                    if (p != null) {
                        takeBow(p);
                        p.sendSystemMessage(Component.literal("The Spirit Bow crumbled in your hands!")
                                .withStyle(ChatFormatting.RED));
                    }
                    lost(level, p == null ? "The Spirit Bow was lost." : null);
                    return;
                }
                if (bowTimer % 20 == 0) {
                    p.sendSystemMessage(Component.literal("Spirit Bow: " + bowTimer / 20 + "s - shoot Thorn!")
                            .withStyle(ChatFormatting.GOLD), true);
                }
                watchForShot(level, p);
            }
            case SHOT -> {
                AbstractArrow a = spiritArrow;
                if (a != null && thorn != null && !a.isRemoved()
                        && thorn.getBoundingBox().inflate(1.0).intersects(a.getBoundingBox())) {
                    thornHit(level);
                } else if (a == null || a.isRemoved() || a.tickCount > 100
                        || a.tickCount > 5 && a.getDeltaMovement().lengthSqr() < 0.01) {
                    if (a != null) {
                        a.discard();
                    }
                    spiritArrow = null;
                    lost(level, "The Spirit Bow's arrow missed Thorn!");
                }
            }
            default -> {
            }
        }
    }

    private void dropBow(ServerLevel level, Vec3 at) {
        ItemEntity item = new ItemEntity(level, at.x, at.y + 0.5, at.z, spiritBow());
        // Picked up by hand ({@link #pickUp}), so the bow and its arrow arrive together.
        item.setNeverPickUp();
        item.setGlowingTag(true);
        level.addFreshEntity(item);
        drop = item;
        bow = Bow.DROPPED;
        bowTimer = DROP_TICKS;
        SimBoss.tell(level.getServer(), Component.literal("The Spirit Bear dropped the Spirit Bow!")
                .withStyle(ChatFormatting.GOLD));
    }

    private void pickUp(ServerLevel level, ServerPlayer p, boolean master) {
        if (drop != null) {
            drop.discard();
            drop = null;
        }
        p.getInventory().add(spiritBow());
        // A vanilla bow needs an arrow to draw; the sim's other bows do not use vanilla arrows.
        arrowGiven = p.getInventory().add(new ItemStack(Items.ARROW));
        holder = p.getUUID();
        bow = Bow.HELD;
        bowTimer = master ? BOW_TICKS_MASTER : BOW_TICKS_NORMAL;
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f,
                1.6f);
        p.sendSystemMessage(Component.literal("You picked up the Spirit Bow! One arrow - make it count.")
                .withStyle(ChatFormatting.GOLD));
    }

    /** The first arrow he looses while holding the Spirit Bow is the spirit arrow; the bow goes with it. */
    private void watchForShot(ServerLevel level, ServerPlayer p) {
        if (!holdsBow(p)) {
            return;
        }
        AABB box = p.getBoundingBox().inflate(4);
        for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, box)) {
            if (a.getOwner() == p && a.tickCount <= 3) {
                spiritArrow = a;
                a.pickup = AbstractArrow.Pickup.DISALLOWED;
                a.setGlowingTag(true);
                arrowGiven = false;
                takeBow(p);
                bow = Bow.SHOT;
                return;
            }
        }
    }

    private void thornHit(ServerLevel level) {
        if (spiritArrow != null) {
            spiritArrow.discard();
            spiritArrow = null;
        }
        if (clock < vulnerableAt) {
            // Still stunned, or in the second after: the arrow does nothing (wiki).
            lost(level, "Thorn can't be hurt yet!");
            return;
        }
        lives--;
        hurtTicks = 30;
        stunnedUntil = clock + STUN_TICKS;
        vulnerableAt = stunnedUntil + STUN_GRACE_TICKS;
        if (thorn != null) {
            level.sendParticles(ParticleTypes.EXPLOSION, thorn.getX(), thorn.getY() + 2, thorn.getZ(), 3, 1, 1, 1,
                    0);
            level.playSound(null, thorn.getX(), thorn.getY(), thorn.getZ(), SoundEvents.GHAST_HURT,
                    SoundSource.HOSTILE, 1.5f, 0.8f);
        }
        if (lives <= 0) {
            die(level);
            return;
        }
        SimBoss.say(level.getServer(), "Thorn", HURT_LINES[ThreadLocalRandom.current().nextInt(HURT_LINES.length)]);
        // His answer, once the stun wears off: the next wave, and the next share of the ring to light.
        nextWave = stunnedUntil;
        bow = Bow.GATHER;
        if (lit >= threshold()) {
            bow = Bow.BEAR_SOON;
            bowTimer = BEAR_DELAY_TICKS;
        }
    }

    /** A miss, a crumbled bow or a lost drop: the bear comes back on its own after a short wait. */
    private void lost(ServerLevel level, String why) {
        holder = null;
        if (why != null) {
            SimBoss.tell(level.getServer(), Component.literal(why + " A new Spirit Bear is coming.")
                    .withStyle(ChatFormatting.RED));
        }
        bow = Bow.BEAR_SOON;
        bowTimer = BEAR_AGAIN_TICKS;
    }

    private void die(ServerLevel level) {
        SimBoss.say(level.getServer(), "Thorn", WIN_LINES[0]);
        if (thorn != null) {
            level.sendParticles(ParticleTypes.EXPLOSION, thorn.getX(), thorn.getY() + 2, thorn.getZ(), 6, 1.5, 1.5,
                    1.5, 0);
            level.playSound(null, thorn.getX(), thorn.getY(), thorn.getZ(), SoundEvents.GHAST_DEATH,
                    SoundSource.HOSTILE, 1.5f, 0.8f);
        }
        clearAnimals(level);
        phase = Phase.DYING;
        phaseTicks = 0;
    }

    private ServerPlayer holderPlayer(ServerLevel level) {
        if (holder == null) {
            return null;
        }
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(holder);
        return p != null && p.isAlive() && holdsAnywhere(p) ? p : null;
    }

    static ItemStack spiritBow() {
        ItemStack stack = new ItemStack(Items.BOW);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Spirit Bow").withStyle(ChatFormatting.GOLD));
        CompoundTag tag = new CompoundTag();
        tag.putString("id", SPIRIT_BOW_ID);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static boolean isBow(ItemStack stack) {
        return SPIRIT_BOW_ID.equals(SimItemIds.skyblockId(stack));
    }

    private static boolean holdsBow(ServerPlayer p) {
        return isBow(p.getMainHandItem()) || isBow(p.getOffhandItem());
    }

    private static boolean holdsAnywhere(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (isBow(p.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /** Takes every Spirit Bow off him, and the arrow he was given if it was never shot. */
    private void takeBow(ServerPlayer p) {
        var inv = p.getInventory();
        boolean arrow = arrowGiven;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isBow(s)) {
                inv.setItem(i, ItemStack.EMPTY);
            } else if (arrow && s.is(Items.ARROW) && SimItemIds.skyblockId(s) == null) {
                s.shrink(1);
                arrow = false;
            }
        }
        arrowGiven = false;
    }

    // ------------------------------------------------------------------ shared

    private static ServerPlayer nearest(List<ServerPlayer> players, Vec3 from) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : players) {
            double d = p.position().distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void ring(ServerLevel level, Vec3 at, double radius, DustParticleOptions dust, int points) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            level.sendParticles(dust, at.x + Math.cos(a) * radius, at.y + 0.1, at.z + Math.sin(a) * radius, 1,
                    0, 0, 0, 0);
        }
    }

    private void nameTags() {
        if (thorn != null) {
            thorn.setCustomName(Component.literal("Thorn ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                    .append(Component.literal(lives + "/" + livesMax + " lives").withStyle(ChatFormatting.GREEN)));
        }
        for (Spirit s : spirits) {
            String name = s.mine ? "Chicken Mine" : s.kind.display;
            s.body.setCustomName(Component.literal(name + " ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(SimBoss.shortHealth(s.health.hp)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("❤").withStyle(ChatFormatting.RED)));
        }
    }

    @Override
    boolean isBoss(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity == thorn) {
            return true;
        }
        for (Spirit s : spirits) {
            if (s.body == entity) {
                return true;
            }
        }
        return false;
    }

    @Override
    boolean hit(ServerLevel level, LivingEntity body, float amount) {
        if (phase != Phase.FIGHT) {
            return false;
        }
        if (body == thorn) {
            // The spirit arrow, caught as it touches him (it bounces off, since the vanilla hit is refused).
            if (bow == Bow.SHOT && spiritArrow != null && !spiritArrow.isRemoved()
                    && thorn.getBoundingBox().inflate(2.5).intersects(spiritArrow.getBoundingBox())) {
                thornHit(level);
                return true;
            }
            if (immuneLine == 0) {
                immuneLine = 100;
                SimBoss.say(level.getServer(), "Thorn",
                        IMMUNE_LINES[ThreadLocalRandom.current().nextInt(IMMUNE_LINES.length)]);
            }
            return false;
        }
        for (Spirit s : new ArrayList<>(spirits)) {
            if (s.body == body) {
                float dealt = s.kind == Animal.BAT ? (float) (amount * BAT_TAKEN) : amount;
                boolean landed = s.health.hit(dealt);
                if (landed && s.health.hp <= 0) {
                    killSpirit(level, s);
                }
                return landed;
            }
        }
        return false;
    }

    @Override
    boolean defeated() {
        return phase == Phase.DEAD;
    }

    /** Every animal, the bow and its arrow gone; Thorn himself stays. */
    private void clearAnimals(ServerLevel level) {
        for (Spirit s : spirits) {
            s.body.discard();
        }
        spirits.clear();
        bear = null;
        if (drop != null) {
            drop.discard();
            drop = null;
        }
        if (spiritArrow != null) {
            spiritArrow.discard();
            spiritArrow = null;
        }
        ServerPlayer p = holder == null ? null : level.getServer().getPlayerList().getPlayer(holder);
        if (p != null) {
            takeBow(p);
        }
        holder = null;
        circles.clear();
    }

    @Override
    void discard(ServerLevel level) {
        if (thorn != null) {
            thorn.discard();
            thorn = null;
        }
        clearAnimals(level);
    }

    @Override
    Component barName() {
        String loop = switch (bow) {
            case GATHER -> "spirits " + lit + "/" + threshold();
            case BEAR_SOON, BEAR -> "Spirit Bear";
            case DROPPED -> "pick up the Spirit Bow";
            case HELD, SHOT -> "shoot Thorn";
        };
        if (clock < stunnedUntil) {
            loop = "stunned";
        }
        return Component.literal("Thorn - " + lives + "/" + livesMax + " lives - " + loop)
                .withStyle(ChatFormatting.RED);
    }

    @Override
    BossEvent.BossBarColor barColor() {
        return hurtTicks > 0 ? BossEvent.BossBarColor.WHITE : BossEvent.BossBarColor.GREEN;
    }

    @Override
    float barProgress() {
        return (float) lives / livesMax;
    }
}
