package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floor 6's boss, Sadan, in his tomb.
 *
 * <p>Three phases, the way the fight goes on Hypixel (https://hypixelskyblock.minecraft.wiki/w/Sadan):
 * <ul>
 * <li><b>Terracottas.</b> Terracotta soldiers rise along both sides of the tomb and come at you, for as long as
 * Sadan's Interest Meter lasts: two minutes, less one second for every Terracotta killed. Each one that dies
 * leaves a flower pot behind and rises again from it 10 seconds later - the pot is what the Terracotta Timer
 * counts down over - the pot turning back into terracotta two seconds before it does. When the meter runs out,
 * Sadan's "ENOUGH!" and the Terracottas are gone. Sleeping Golems stand in the hall all the while: hit one and it
 * wakes and runs at you (Sadan has a word about it). They are left out of the phases: nothing needs them dead.</li>
 * <li><b>Giants.</b> The four Giants wake in the corners and walk at you, hitting whoever they reach, each with its own
 * attack too:
 * <i>Bigfoot</i> leaps and stomps every seven seconds (get out of the ring of cloud round him; it hurts less the
 * further out you are), <i>L.A.S.R.</i> locks his eye beam on a player and keeps it there for five seconds, the
 * beam following you and hitting harder the longer it is on you, the <i>Jolly Pink Giant</i> lobs boulders at
 * your feet, and the <i>Diamond Giant</i> throws his giant sword down onto a player. In Master Mode they are four
 * Mutant Giants instead, each with two of those four attacks at random, used side by side. Kill all four.</li>
 * <li><b>The Giant One.</b> A cutscene: Sadan merges with The Giant One, a hole opens in the middle of the hall and
 * the Giant is hooked up out of it. Falling into the hole kills you - and every Tank on the team, through
 * Diversion. The hole closes when the cutscene ends. Sadan then fights with all four Giants' attacks at once; in
 * Master Mode the four Mutant Giants come back with him, at the four corners. Killing Sadan ends the fight.</li>
 * </ul>
 *
 * <p>Every line is Hypixel's word for word (the wiki's Dialogue), the ones the mod reads among them: the opening
 * ("So you made it all the way here...": Boss Phases' Terracottas, the dungeon's boss detection the Terracotta
 * Timer waits on), "ENOUGH!" (Giants) and "You did it. I understand now, you have earned my respect." (Sadan).
 * When the in-between lines come is the sim's own.
 *
 * <p>The Giants and Sadan are vanilla {@link Giant}s with no AI, moved by the fight, their health in pools of
 * this fight's own ({@link SimBoss.Health}) like every boss. The Terracottas are ordinary chasing zombies with
 * room-mob pools ({@link SimHealth#TERRACOTTA}).
 *
 * <p><b>Health and damage are Hypixel's</b>, before the player's Defense:
 * <ul>
 * <li>Sadan 40,000,000 on F6 and 600,000,000 on M6 (https://hypixelskyblock.minecraft.wiki/w/Sadan, the Variants
 * table; the infobox's range says 800,000,000 for M6), his own hit (his golden sword) 30,960 / 720,000. As The Giant
 * One he also has the Giants' attacks, each at that Giant's number (APPROXIMATE: the wiki gives his last form no
 * numbers for them).</li>
 * <li>Terracottas 12,000,000 / 250,000,000, hitting for 9,900 / 95,900 ({@link SimHealth#TERRACOTTA}); Golems
 * 20,000,000 / 300,000,000 with 200 Defense, hitting for 96,000 / 1,000,000 (the Sadan page's mob table).</li>
 * <li>F6's Giants, 25,000,000 each, a 17,600 hit when they reach you: Bigfoot's stomp up to 88,000
 * (https://hypixelskyblock.minecraft.wiki/w/Bigfoot),
 * one shot of L.A.S.R.'s beam 8,800, every half second for about five seconds, then five seconds' rest
 * (https://hypixelskyblock.minecraft.wiki/w/L.A.S.R.), the Jolly Pink Giant's boulder up to 52,800
 * (https://hypixelskyblock.minecraft.wiki/w/Jolly_Pink_Giant), the Diamond Giant's thrown sword 25,160
 * (https://hypixelskyblock.minecraft.wiki/w/The_Diamond_Giant).</li>
 * <li>M6's Mutant Giants (https://hypixelskyblock.minecraft.wiki/w/Mutant_Giant), 600,000,000 each, a 1,000,000 hit,
 * two random attacks each at stomp 4,000,000, beam 500,000 a shot, boulder 3,000,000 and thrown sword 200,000.</li>
 * </ul>
 */
final class SimSadan extends SimBoss.Fight {

    /** Sadan's health, F6 then M6. */
    private static double sadanHealth() {
        return SimHealth.byMode(40_000_000, 600_000_000);
    }

    /** Sadan's own hit (his golden sword), F6 then M6. */
    private static double sadanDamage() {
        return SimHealth.byMode(30_960, 720_000);
    }

    /** A Giant's health: 25,000,000 on F6, a Mutant Giant's 600,000,000 on M6. */
    private static double giantHealth() {
        return SimHealth.byMode(25_000_000, 600_000_000);
    }

    /** A Giant's base damage, its hit when it reaches you: 17,600 on F6, a Mutant Giant's 1,000,000 on M6. */
    private static double giantDamage() {
        return SimHealth.byMode(17_600, 1_000_000);
    }

    /** A Giant's (or Sadan's) melee: reach and time between hits. APPROXIMATE (the wiki: "when in range"). */
    private static final double MELEE_REACH = 5;
    private static final int MELEE_EVERY = 30;

    /** A Golem's health and hit, F6 then M6 (the Sadan page's Sleeping / Woke Golem). */
    private static double golemHealth() {
        return SimHealth.byMode(20_000_000, 300_000_000);
    }

    private static double golemDamage() {
        return SimHealth.byMode(96_000, 1_000_000);
    }

    /** A Golem's 200 Defense (the wiki's): a player's hit lands at 100 / (100 + 200). */
    private static final double GOLEM_TAKEN = 100.0 / (100.0 + 200.0);
    /** Where the Sleeping Golems stand, in the arena's coordinates: down the middle of the hall. APPROXIMATE. */
    private static final int[] GOLEM_X = {-19, 1};
    private static final int[] GOLEM_Z = {54, 66, 78};

    /** The tomb, in the arena's own coordinates: the floor the fight is on. */
    private static final int[] TOMB_MIN = {-29, 69, 39};
    private static final int[] TOMB_MAX = {11, 69, 93};
    /** Where the Terracottas stand: both long walls, eight a side. */
    private static final int[] TERRACOTTA_X = {-26, 8};
    private static final int[] TERRACOTTA_Z = {45, 51, 57, 63, 69, 75, 81, 87};
    /** The four Giants' corners (the pillars the Mutant Giants come back at). */
    private static final int[][] GIANT_SPOTS = {{-22, 44}, {4, 44}, {-22, 88}, {4, 88}};

    /** The wiki's Interest Meter: two minutes. */
    private static final int INTEREST_TICKS = 2400;
    /** The wiki's one second off the meter per Terracotta killed. */
    private static final int INTEREST_PER_KILL = 20;
    /** From a Terracotta's death to its rising again: the wiki's 10s, F6 and M6 alike (the Terracotta Timer counts the same). */
    private int respawnTicks() {
        return 200;
    }
    /** The hole in the middle of the hall during the cutscene, in blocks (APPROXIMATE). */
    private static final int HOLE_RADIUS = 4;
    private static final int HOLE_DEPTH = 6;
    /** The cutscene's length (APPROXIMATE: Hypixel sped it up in 0.19.4 and gives no length). */
    private static final int MERGE_TICKS = 200;
    /** When in the cutscene the Giant One starts coming up out of the hole on the hook. */
    private static final int HOOK_TICK = 80;

    private enum Phase { TERRACOTTAS, GIANTS_WAKING, GIANTS, MERGE, SADAN, DYING, DEAD }

    /** A Giant's attack (Sadan's own kind has all four). */
    private enum Kind {
        /** The stomp: up to 88,000 / 4,000,000. */
        BIGFOOT("Bigfoot", 88_000, 4_000_000),
        /** One shot of the beam: 8,800 / 500,000. */
        LASR("L.A.S.R.", 8_800, 500_000),
        /** The boulder: up to 52,800 / 3,000,000. */
        JOLLY("Jolly Pink Giant", 52_800, 3_000_000),
        /** The thrown sword: 25,160 / 200,000. */
        DIAMOND("The Diamond Giant", 25_160, 200_000),
        SADAN("Sadan", 0, 0);

        static final Kind[] ATTACKS = {BIGFOOT, LASR, JOLLY, DIAMOND};

        final String display;
        final double attackF6;
        final double attackM6;

        Kind(String display, double attackF6, double attackM6) {
            this.display = display;
            this.attackF6 = attackF6;
            this.attackM6 = attackM6;
        }

        /** This attack's damage on the current floor's mode, before Defense. */
        double attack() {
            return SimHealth.byMode(attackF6, attackM6);
        }
    }

    /** Hypixel's lines (the wiki's Dialogue). */
    private static final String[] PHASE_ONE = {
            "My Terracotta Army is using some of the finest souls - old warriors and wizards!",
            "You will be humbled, reckless humans!",
            "Can you at least enjoy my science, pushing the boundaries of Necromancy!",
            "Corpses are deficient, full of weaknesses. Statues are stronger, faster, and easier to repair.",
            "Can't you see that there is no winning? This room will be your tomb.",
            "So much effort, for what? You should have joined us instead, Necron will cure this world!",
            "Terrible...terrible...,",
    };
    private static final String[] PHASE_TWO = {
            "Rumor has it those are dead Precursors, legendary civilization.",
            "My first time using them, extremely interesting!",
            "Which one is your favorite? Those laser eyes...beautiful!",
            "This is what peak Necromancy looks like!",
            "Boulders, lasers, stomping, and giant swords! Oh my!",
    };
    private static final String[][] GIANT_KILLED = {
            {"You've managed to kill a Giant! Good luck with the rest of them!",
                    "Wow, one Giant down already? You guys are good!",
                    "How many players does it take to kill a Giant?",
                    "You killed my weakest Giant!"},
            {"Uh oh! Half of my Giant creations have bit the dust! Whatever will I do?",
                    "2 of my Giants down?! No matter, these last two are tough!",
                    "That Giant was weak! I clearly screwed up during the necromancy process."},
            {"I'm starting to sweat!",
                    "Down to 1 Giant!",
                    "Whoops! There goes my third Giant!"},
    };

    /** Hypixel's lines when a Golem wakes (the wiki's Dialogue); {@code %s} is the player who woke it. */
    private static final String[] FIRST_GOLEM = {
            "You weren't supposed to wake up that Golem, %s!",
            "My Terracotta Army wasn't enough? You had to awaken a Golem on top, %s?!",
    };
    private static final String GOLEM_WOKEN = "All my Golems want is some peace and quiet!";
    private static final String ALL_GOLEMS = "Thanks to %s, all of my Golems have been awoken!";

    private Phase phase = Phase.TERRACOTTAS;
    private int phaseTicks;
    private int ticks;
    private final boolean master = SimHealth.master(SimState.floorLabel());
    private final List<Slot> slots = new ArrayList<>();
    private final List<Big> giants = new ArrayList<>();
    private Big sadan;
    private final List<Bomb> bombs = new ArrayList<>();
    private final List<Golem> golems = new ArrayList<>();
    private int golemsWoken;
    /** Sadan's Interest Meter, in ticks left. */
    private int interest = INTEREST_TICKS;
    private boolean tiredSaid;
    private boolean restlessSaid;
    private int giantsKilled;
    /** The blocks the hole took out, to put back when it closes; empty while there is no hole. */
    private final Map<BlockPos, BlockState> hole = new LinkedHashMap<>();
    private BlockPos holeCentre;
    private double holeFloor;

    /** One Terracotta's place by the wall: its mob, or the pot it rises from again. */
    private static final class Slot {
        final BlockPos at;
        Terracotta mob;
        /** Tick it rises again, or -1 while it is up. */
        int risesAt = -1;
        boolean potPlaced;

        Slot(BlockPos at) {
            this.at = at;
        }
    }

    /** One attack's timing on one Giant. */
    private static final class Move {
        int cooldown;
        /** Ticks into a telegraphed attack, -1 when not in one. */
        int windup = -1;
        Vec3 aim;
        /** L.A.S.R.: shots fired so far in this beam, for the ramp. */
        int shots;
    }

    /** A Giant (or Sadan): its body, pool and its attacks' timing. */
    private static final class Big {
        final Kind kind;
        final List<Kind> moves;
        final Map<Kind, Move> state = new EnumMap<>(Kind.class);
        final GiantBody body;
        final SimBoss.Health health;
        final boolean mutant;
        boolean dead;

        Big(Kind kind, List<Kind> moves, GiantBody body, double max, boolean mutant) {
            this.kind = kind;
            this.moves = moves;
            this.body = body;
            this.health = new SimBoss.Health(max);
            this.mutant = mutant;
            for (Kind k : moves) {
                state.put(k, new Move());
            }
        }

        /** Ticks until its next melee hit. */
        int meleeCooldown = MELEE_EVERY;

        /** Held in place by Bigfoot's leap. */
        boolean rooted() {
            Move stomp = state.get(Kind.BIGFOOT);
            return stomp != null && stomp.windup >= 0;
        }

        String name() {
            return mutant ? "Mutant Giant" : kind.display;
        }
    }

    private record Bomb(Vec3 at, int landsAt, double damage) {
    }

    /** One Golem in the hall: asleep (no AI) until hit, then it chases. */
    private static final class Golem {
        final GolemBody body;
        final SimBoss.Health health = new SimBoss.Health(golemHealth());
        boolean awake;

        Golem(GolemBody body) {
            this.body = body;
        }
    }

    SimSadan(SimBossArena arena) {
        super(arena);
    }

    @Override
    String name() {
        return "Sadan";
    }

    @Override
    void start(ServerLevel level) {
        // Hypixel's first line, word for word: Boss Phases' Terracottas and the boss detection start on it.
        SimBoss.say(level.getServer(), "Sadan", "So you made it all the way here... Now you wish to defy me? Sadan?!");
        slots.clear();
        for (int x : TERRACOTTA_X) {
            for (int z : TERRACOTTA_Z) {
                BlockPos p = arena.toWorld(x, TOMB_MIN[1], z);
                slots.add(new Slot(new BlockPos(p.getX(), (int) Math.ceil(ground(level, p.getX() + 0.5,
                        p.getZ() + 0.5)), p.getZ())));
            }
        }
        // They rise a couple of seconds in, staggered, rather than all on the opening line.
        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).risesAt = 40 + (i % 8) * 4;
        }
        interest = INTEREST_TICKS;
        spawnGolems(level);
        toPhase(Phase.TERRACOTTAS);
    }

    @Override
    void tick(ServerLevel level, int t) {
        ticks = t;
        phaseTicks++;
        switch (phase) {
            case TERRACOTTAS -> terracottaPhase(level);
            case GIANTS_WAKING -> {
                if (phaseTicks == 40) {
                    SimBoss.say(level.getServer(), "Sadan", "My giants! Unleashed!");
                    wakeGiants(level);
                    toPhase(Phase.GIANTS);
                }
            }
            case GIANTS -> {
                boolean anyAlive = false;
                for (Big g : giants) {
                    if (!g.dead) {
                        tickBig(level, g);
                        if (g.dead) {
                            giantDown(level);
                        }
                        anyAlive |= !g.dead;
                    }
                }
                if (anyAlive && phaseTicks % 500 == 250) {
                    say(level, pick(PHASE_TWO));
                }
                if (!anyAlive) {
                    // Hypixel's line: Boss Phases' Sadan phase starts on it.
                    say(level, "You did it. I understand now, you have earned my respect.");
                    clearTerracottas(level);
                    clearGolems();
                    giants.clear();
                    toPhase(Phase.MERGE);
                }
            }
            case MERGE -> merge(level);
            case SADAN -> {
                tickSlots(level, false);
                for (Big g : giants) {
                    if (!g.dead) {
                        tickBig(level, g);
                    }
                }
                if (sadan != null && !sadan.dead) {
                    tickBig(level, sadan);
                }
                if (sadan == null || sadan.dead) {
                    // Hypixel's death lines.
                    say(level, "NOOOOOOOOO!!! THIS IS IMPOSSIBLE!!");
                    clearTerracottas(level);
                    clearGolems();
                    for (Big g : giants) {
                        g.body.discard();
                    }
                    giants.clear();
                    toPhase(Phase.DYING);
                }
            }
            case DYING -> {
                if (phaseTicks == 40) {
                    say(level, "FATHER, FORGIVE ME!!!");
                    toPhase(Phase.DEAD);
                }
            }
            default -> {
            }
        }
        tickHole(level);
        tickBombs(level);
        tickGolems(level);
        if (t % 20 == 0) {
            clearDrops(level);
        }
    }

    private void toPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    private void say(ServerLevel level, String line) {
        SimBoss.say(level.getServer(), "Sadan", line);
    }

    private static String pick(String[] lines) {
        return lines[ThreadLocalRandom.current().nextInt(lines.length)];
    }

    // ------------------------------------------------------------------ Terracottas

    /** Phase one: the Interest Meter runs down, faster for every Terracotta killed. */
    private void terracottaPhase(ServerLevel level) {
        interest -= INTEREST_PER_KILL * tickSlots(level, true);
        interest--;
        // The opening's other two lines, then one of his phase one lines every so often (when: the sim's own).
        if (phaseTicks == 80) {
            say(level, "The audacity! I have been the ruler of these floors for a hundred years!");
        } else if (phaseTicks == 160) {
            say(level, "I am the bridge between this realm and the world below! You shall not pass!");
        } else if (phaseTicks > 160 && phaseTicks % 400 == 0 && interest > 600) {
            say(level, pick(PHASE_ONE));
        }
        if (!tiredSaid && interest <= 600) {
            tiredSaid = true;
            say(level, "I'm growing tired of this folly. My Giants are restless.");
        }
        if (!restlessSaid && interest <= 200) {
            restlessSaid = true;
            say(level, "It's about time we see what my Giants can do...");
        }
        if (interest <= 0) {
            interest = 0;
            // Hypixel's line: Boss Phases' Giants start on it.
            say(level, "ENOUGH!");
            // The wiki's Terracottas attack "until the Interest Meter runs out", on F6 and M6 alike.
            clearTerracottas(level);
            toPhase(Phase.GIANTS_WAKING);
        }
    }

    /**
     * Each slot: a dead Terracotta leaves a pot, the pot turns to terracotta two seconds before it rises, then it
     * rises. {@code rising} false lets the dead stay dead (the pots go too).
     *
     * @return how many Terracottas died this tick
     */
    private int tickSlots(ServerLevel level, boolean rising) {
        int died = 0;
        for (Slot s : slots) {
            if (s.mob != null) {
                if (s.mob.isAlive() && !s.mob.isRemoved()) {
                    continue;
                }
                s.mob = null;
                died++;
                if (!rising) {
                    continue;
                }
                // The pot the Terracotta Timer counts down over.
                level.setBlock(s.at, Blocks.FLOWER_POT.defaultBlockState(), 3);
                s.potPlaced = true;
                s.risesAt = ticks + respawnTicks();
                continue;
            }
            if (!rising) {
                if (s.potPlaced) {
                    level.setBlock(s.at, Blocks.AIR.defaultBlockState(), 3);
                    s.potPlaced = false;
                }
                continue;
            }
            if (s.risesAt < 0) {
                // Cleared at the end of a phase.
                continue;
            }
            if (s.potPlaced && ticks == s.risesAt - 40) {
                level.setBlock(s.at, Blocks.TERRACOTTA.defaultBlockState(), 3);
            }
            if (ticks >= s.risesAt) {
                level.setBlock(s.at, Blocks.AIR.defaultBlockState(), 3);
                s.potPlaced = false;
                s.risesAt = -1;
                s.mob = spawnTerracotta(level, s.at);
            }
        }
        return died;
    }

    private Terracotta spawnTerracotta(ServerLevel level, BlockPos at) {
        Terracotta t = new Terracotta(level, SimHealth.meleeDamage(SimState.floorLabel(), SimHealth.Role.STARRED));
        t.setPersistenceRequired();
        t.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TERRACOTTA));
        t.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0x8E5A3C));
        t.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x8E5A3C));
        t.setDropChance(EquipmentSlot.HEAD, 0f);
        t.setDropChance(EquipmentSlot.CHEST, 0f);
        t.setDropChance(EquipmentSlot.LEGS, 0f);
        double x = at.getX() + 0.5;
        double z = at.getZ() + 0.5;
        BlockPos c = arena.toWorld(arena.centre);
        float yaw = (float) (Math.toDegrees(Math.atan2(c.getZ() - z, c.getX() - x)) - 90);
        t.snapTo(x, at.getY(), z, yaw, 0f);
        ServerPlayer target = nearest(playersInTomb(level), t.position());
        if (target != null) {
            t.setTarget(target);
        }
        level.addFreshEntity(t);
        // Registered by its Hypixel name, the pool takes the wiki's health and hit (SimHealth.statsFor).
        SimHealth.register(t, SimHealth.maxHealth(SimState.floorLabel(), SimHealth.Role.STARRED), "Terracotta",
                false, null);
        level.sendParticles(ParticleTypes.POOF, x, at.getY() + 0.5, z, 10, 0.3, 0.4, 0.3, 0.02);
        return t;
    }

    /** Every Terracotta down and every pot gone, for a phase that has none. */
    private void clearTerracottas(ServerLevel level) {
        for (Slot s : slots) {
            if (s.mob != null) {
                level.sendParticles(ParticleTypes.POOF, s.mob.getX(), s.mob.getY() + 1, s.mob.getZ(), 8, 0.3, 0.5,
                        0.3, 0.02);
                s.mob.discard();
                s.mob = null;
            }
            if (s.potPlaced) {
                level.setBlock(s.at, Blocks.AIR.defaultBlockState(), 3);
                s.potPlaced = false;
            }
            s.risesAt = -1;
        }
    }

    /** The Terracottas' zombie drops and experience, which the tomb would otherwise fill up with. */
    private void clearDrops(ServerLevel level) {
        AABB box = tombBox().inflate(4, 6, 4);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            item.discard();
        }
        for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, box)) {
            orb.discard();
        }
    }

    // ------------------------------------------------------------------ Giants

    private void wakeGiants(ServerLevel level) {
        spawnGiants(level, 60);
        BlockPos c = arena.toWorld(arena.centre);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1f,
                0.6f);
    }

    /**
     * The four Giants in the corners, in shuffled order. On F6 one of each; on M6 four Mutant Giants, each with
     * two of the four attacks at random.
     */
    private void spawnGiants(ServerLevel level, int firstAttack) {
        List<int[]> spots = new ArrayList<>(List.of(GIANT_SPOTS));
        java.util.Collections.shuffle(spots);
        for (int i = 0; i < Kind.ATTACKS.length; i++) {
            int[] s = spots.get(i);
            BlockPos at = arena.toWorld(s[0], TOMB_MIN[1], s[1]);
            List<Kind> moves;
            if (master) {
                List<Kind> pool = new ArrayList<>(List.of(Kind.ATTACKS));
                java.util.Collections.shuffle(pool);
                moves = List.of(pool.get(0), pool.get(1));
            } else {
                moves = List.of(Kind.ATTACKS[i]);
            }
            Big g = spawnBig(level, moves.get(0), moves, at, giantHealth(), master);
            for (Move m : g.state.values()) {
                m.cooldown = firstAttack + i * 20 + ThreadLocalRandom.current().nextInt(20);
            }
            giants.add(g);
        }
    }

    /** One of Sadan's lines for the first, second and third Giant down. */
    private void giantDown(ServerLevel level) {
        giantsKilled++;
        if (giantsKilled <= GIANT_KILLED.length) {
            say(level, pick(GIANT_KILLED[giantsKilled - 1]));
        }
    }

    private Big spawnBig(ServerLevel level, Kind kind, List<Kind> moves, BlockPos at, double max, boolean mutant) {
        GiantBody body = new GiantBody(level);
        body.setNoAi(true);
        body.setNoGravity(true);
        body.setPersistenceRequired();
        body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        body.setHealth(1000);
        dress(body, kind);
        double x = at.getX() + 0.5;
        double z = at.getZ() + 0.5;
        body.snapTo(x, ground(level, x, z), z, 0f, 0f);
        body.setCustomNameVisible(true);
        level.addFreshEntity(body);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, body.getY() + 1, z, 1, 0, 0, 0, 0);
        Big g = new Big(kind, moves, body, max, mutant);
        nameTag(g);
        return g;
    }

    private static void dress(GiantBody body, Kind kind) {
        switch (kind) {
            case BIGFOOT -> {
                body.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, 0x5C3A1E));
                body.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x5C3A1E));
            }
            case LASR -> {
                body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            }
            case JOLLY -> {
                body.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, 0xFF7FD0));
                body.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0xFF7FD0));
                body.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0xFF7FD0));
            }
            case DIAMOND -> {
                body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                body.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
                body.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
                body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            }
            case SADAN -> {
                body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
                body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
            }
        }
    }

    private static ItemStack dyed(Item item, int rgb) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
        return stack;
    }

    /** One Giant's tick: walk, use each of its attacks, die. */
    private void tickBig(ServerLevel level, Big g) {
        if (g.health.hp <= 0) {
            g.dead = true;
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, g.body.getX(), g.body.getY() + 4, g.body.getZ(),
                    2, 1, 2, 1, 0);
            level.playSound(null, g.body.getX(), g.body.getY(), g.body.getZ(), SoundEvents.GENERIC_EXPLODE,
                    SoundSource.HOSTILE, 2f, 0.6f);
            g.body.discard();
            return;
        }
        List<ServerPlayer> targets = playersInTomb(level);
        ServerPlayer target = nearest(targets, g.body.position());
        if (target != null && !g.rooted()) {
            walk(level, g, target);
        }
        melee(level, g, target);
        for (Kind k : g.moves) {
            Move m = g.state.get(k);
            if (m.cooldown > 0) {
                m.cooldown--;
            }
            // Two attacks at once (a Mutant Giant, or Sadan with all four), each on its own timing.
            switch (k) {
                case BIGFOOT -> stomp(level, g, m, targets);
                case LASR -> laser(level, g, m, targets);
                case JOLLY -> lob(level, g, m, targets);
                case DIAMOND -> throwSword(level, g, m, targets);
                default -> {
                }
            }
        }
        if (ticks % 5 == 0) {
            nameTag(g);
        }
    }

    /** A slow walk at the nearest player, on the tomb floor, facing him. */
    private void walk(ServerLevel level, Big g, ServerPlayer target) {
        GiantBody b = g.body;
        double dx = target.getX() - b.getX();
        double dz = target.getZ() - b.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double x = b.getX();
        double z = b.getZ();
        // Every Giant "runs at the player and damages them when in range" (the wiki's, all four).
        if (len > MELEE_REACH - 1) {
            double speed = (g.kind == Kind.SADAN ? 0.07 : 0.05) * (master ? 1.3 : 1.0);
            x += dx / len * speed;
            z += dz / len * speed;
        }
        AABB tomb = tombBox();
        x = Math.max(tomb.minX + 2, Math.min(tomb.maxX - 2, x));
        z = Math.max(tomb.minZ + 2, Math.min(tomb.maxZ - 2, z));
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        b.snapTo(x, ground(level, x, z), z, yaw, 0f);
        b.setYHeadRot(yaw);
        b.setYBodyRot(yaw);
    }

    /**
     * Bigfoot's stomp, every seven seconds (the wiki's): a ring of cloud round him while he leaps, then he lands
     * and everything inside it is thrown up, harder the nearer him (APPROXIMATE: full damage at his feet, a third
     * at the ring's edge).
     */
    private void stomp(ServerLevel level, Big g, Move m, List<ServerPlayer> targets) {
        double radius = 9;
        int telegraph = 30;
        GiantBody b = g.body;
        if (m.windup < 0) {
            ServerPlayer near = nearest(targets, b.position());
            if (m.cooldown > 0 || near == null || b.distanceToSqr(near) > 14 * 14) {
                return;
            }
            m.windup = 0;
        }
        m.windup++;
        if (m.windup < telegraph) {
            if (m.windup % 3 == 0) {
                for (int i = 0; i < 28; i++) {
                    double a = Math.PI * 2 * i / 28;
                    level.sendParticles(ParticleTypes.CLOUD, b.getX() + Math.cos(a) * radius, b.getY() + 0.2,
                            b.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0);
                }
            }
            // Up into the leap.
            b.snapTo(b.getX(), b.getY() + (m.windup < telegraph / 2 ? 0.12 : -0.12), b.getZ(), b.getYRot(), 0f);
            return;
        }
        m.windup = -1;
        m.cooldown = 140 - telegraph;
        b.snapTo(b.getX(), ground(level, b.getX(), b.getZ()), b.getZ(), b.getYRot(), 0f);
        level.sendParticles(ParticleTypes.EXPLOSION, b.getX(), b.getY() + 0.5, b.getZ(), 8, radius / 2, 0.3,
                radius / 2, 0);
        level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2f,
                0.5f);
        for (ServerPlayer p : targets) {
            double dx = p.getX() - b.getX();
            double dz = p.getZ() - b.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 <= radius * radius && p.getY() - b.getY() < 3) {
                double falloff = 1 - Math.sqrt(d2) / radius * (2.0 / 3);
                hurtPlayer(level, p, Kind.BIGFOOT.attack() * falloff, dx, dz, 0.9, 0.9);
            }
        }
    }

    /**
     * L.A.S.R.'s eye beam: a second locking on, then a beam held on the player for five seconds, one shot every
     * half second, then five seconds' rest (the wiki's). The beam swings after you rather than snapping, so it can
     * be outrun, and each shot in a row hits harder than the last (APPROXIMATE: +15% a shot; the Sadan page says
     * the beam's damage rises over time, the L.A.S.R. page gives only the first shot's number).
     */
    private void laser(ServerLevel level, Big g, Move m, List<ServerPlayer> targets) {
        int lockOn = 20;
        int firing = 100;
        GiantBody b = g.body;
        Vec3 eye = new Vec3(b.getX(), b.getY() + 10, b.getZ());
        ServerPlayer t = nearest(targets, b.position());
        if (m.windup < 0) {
            if (m.cooldown > 0 || t == null) {
                return;
            }
            m.windup = 0;
            m.shots = 0;
            m.aim = t.position().add(0, 1, 0);
            level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.GUARDIAN_ATTACK, SoundSource.HOSTILE, 2f, 1.4f);
        }
        m.windup++;
        if (t != null) {
            // Follows the target at a lag: snapped on while locking, then swinging after him at a fixed rate.
            Vec3 want = t.position().add(0, 1, 0);
            if (m.windup <= lockOn) {
                m.aim = want;
            } else {
                Vec3 d = want.subtract(m.aim);
                double max = 0.22;
                m.aim = d.length() <= max ? want : m.aim.add(d.normalize().scale(max));
            }
        }
        Vec3 end = beamEnd(eye, m.aim);
        if (m.windup <= lockOn) {
            if (m.windup % 2 == 0) {
                beam(level, new DustParticleOptions(0xFF6060, 0.7f), eye, end);
            }
            return;
        }
        if (m.windup % 2 == 0) {
            float size = Math.min(2.4f, 1.0f + 0.14f * m.shots);
            beam(level, new DustParticleOptions(0xFF0000, size), eye, end);
        }
        int into = m.windup - lockOn;
        if (into % 10 == 0) {
            double damage = Kind.LASR.attack() * (1 + 0.15 * m.shots);
            m.shots++;
            level.sendParticles(ParticleTypes.FLAME, end.x, end.y, end.z, 6, 0.3, 0.1, 0.3, 0.01);
            for (ServerPlayer p : targets) {
                if (distanceToSegment(p.position().add(0, 1, 0), eye, end) < 1.4) {
                    hurtPlayer(level, p, damage, 0, 0, 0, 0);
                }
            }
        }
        if (into >= firing) {
            m.windup = -1;
            m.cooldown = 100;
        }
    }

    /** The beam goes through the aim point and on to the floor beyond it. */
    private static Vec3 beamEnd(Vec3 eye, Vec3 aim) {
        Vec3 d = aim.subtract(eye);
        if (d.y >= -0.1) {
            return aim;
        }
        double k = (eye.y - aim.y) / -d.y;
        return eye.add(d.scale(Math.max(1, k)));
    }

    private static void beam(ServerLevel level, net.minecraft.core.particles.ParticleOptions particle, Vec3 from,
                             Vec3 to) {
        Vec3 d = to.subtract(from);
        int steps = (int) Math.max(4, d.length() * 2);
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.add(d.scale((double) i / steps));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    /**
     * The Jolly Pink Giant's boulders: a pink ring at each player's feet (and one beside), then a blast that hurts
     * less away from the middle (the wiki's "up to"; APPROXIMATE: half at the edge).
     */
    private void lob(ServerLevel level, Big g, Move m, List<ServerPlayer> targets) {
        if (m.cooldown > 0 || targets.isEmpty()) {
            return;
        }
        m.cooldown = master ? 90 : 140;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double damage = Kind.JOLLY.attack();
        for (ServerPlayer p : targets) {
            bombs.add(new Bomb(p.position(), ticks + 30, damage));
            double a = rnd.nextDouble() * Math.PI * 2;
            bombs.add(new Bomb(p.position().add(Math.cos(a) * 4, 0, Math.sin(a) * 4), ticks + 38, damage));
        }
        g.body.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        level.playSound(null, g.body.getX(), g.body.getY() + 8, g.body.getZ(), SoundEvents.SNOWBALL_THROW,
                SoundSource.HOSTILE, 2f, 0.5f);
    }

    private void tickBombs(ServerLevel level) {
        if (bombs.isEmpty()) {
            return;
        }
        double radius = 3;
        DustParticleOptions pink = new DustParticleOptions(0xFF7FD0, 1.3f);
        List<Bomb> done = new ArrayList<>();
        for (Bomb b : bombs) {
            if (ticks < b.landsAt()) {
                if (ticks % 3 == 0) {
                    for (int i = 0; i < 16; i++) {
                        double a = Math.PI * 2 * i / 16;
                        level.sendParticles(pink, b.at().x + Math.cos(a) * radius, b.at().y + 0.1,
                                b.at().z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
                    }
                }
                continue;
            }
            done.add(b);
            level.sendParticles(ParticleTypes.EXPLOSION, b.at().x, b.at().y + 0.5, b.at().z, 2, 0.5, 0.3, 0.5, 0);
            level.playSound(null, b.at().x, b.at().y, b.at().z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
                    1f, 1.3f);
            for (ServerPlayer p : playersInTomb(level)) {
                double dx = p.getX() - b.at().x;
                double dz = p.getZ() - b.at().z;
                double d2 = dx * dx + dz * dz;
                if (d2 <= radius * radius && Math.abs(p.getY() - b.at().y) < 3) {
                    double falloff = 1 - Math.sqrt(d2) / radius * 0.5;
                    hurtPlayer(level, p, b.damage() * falloff, dx, dz, 0.7, 0.5);
                }
            }
        }
        bombs.removeAll(done);
    }

    /**
     * The hit every Giant (and Sadan) lands on whoever it reaches: the Giant's base damage, Sadan's own. Not while
     * Bigfoot's leap holds him in the air.
     */
    private void melee(ServerLevel level, Big g, ServerPlayer target) {
        if (g.meleeCooldown > 0) {
            g.meleeCooldown--;
        }
        if (target == null || g.meleeCooldown > 0 || g.rooted() || horizontal(target, g.body) > MELEE_REACH
                || target.getY() - g.body.getY() > 6) {
            return;
        }
        g.meleeCooldown = MELEE_EVERY;
        GiantBody b = g.body;
        b.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE,
                2f, 0.5f);
        double damage = g.kind == Kind.SADAN ? sadanDamage() : giantDamage();
        hurtPlayer(level, target, damage, target.getX() - b.getX(), target.getZ() - b.getZ(), 1.0, 0.4);
    }

    /**
     * The Diamond Giant's sword: now and then he throws his giant sword down onto a player (the wiki's), a marked
     * spot it lands on a second and a half later. Cooldown, mark and radius APPROXIMATE.
     */
    private void throwSword(ServerLevel level, Big g, Move m, List<ServerPlayer> targets) {
        double radius = 2.5;
        int fall = 30;
        GiantBody b = g.body;
        if (m.windup < 0) {
            if (m.cooldown > 0) {
                return;
            }
            ServerPlayer t = nearest(targets, b.position());
            if (t == null || horizontal(t, b) > 30) {
                return;
            }
            m.windup = 0;
            m.aim = t.position();
            b.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            level.playSound(null, b.getX(), b.getY() + 8, b.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE,
                    2f, 0.5f);
        }
        m.windup++;
        Vec3 at = m.aim;
        if (m.windup < fall) {
            if (m.windup % 3 == 0) {
                DustParticleOptions cyan = new DustParticleOptions(0x55FFFF, 1.3f);
                for (int i = 0; i < 16; i++) {
                    double a = Math.PI * 2 * i / 16;
                    level.sendParticles(cyan, at.x + Math.cos(a) * radius, at.y + 0.1, at.z + Math.sin(a) * radius, 1,
                            0, 0, 0, 0);
                }
                // The sword coming down.
                double h = 14.0 * (fall - m.windup) / fall;
                level.sendParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y + h, at.z, 6, 0.2, 1.0, 0.2, 0);
            }
            return;
        }
        m.windup = -1;
        m.cooldown = master ? 100 : 140;
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 0.6, 0.3, 0.6, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.5f, 0.6f);
        for (ServerPlayer p : targets) {
            double dx = p.getX() - at.x;
            double dz = p.getZ() - at.z;
            if (dx * dx + dz * dz <= radius * radius && Math.abs(p.getY() - at.y) < 3) {
                hurtPlayer(level, p, Kind.DIAMOND.attack(), dx, dz, 0.8, 0.5);
            }
        }
    }

    private static double horizontal(Entity a, Entity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void nameTag(Big g) {
        Component tag = Component.literal(g.name() + " ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(SimBoss.shortHealth(g.health.hp)).withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("❤").withStyle(ChatFormatting.RED)));
        g.body.setCustomName(tag);
    }

    // ------------------------------------------------------------------ Golems

    /** The Sleeping Golems down the hall, asleep until hit. */
    private void spawnGolems(ServerLevel level) {
        clearGolems();
        golemsWoken = 0;
        BlockPos c = arena.toWorld(arena.centre);
        for (int x : GOLEM_X) {
            for (int z : GOLEM_Z) {
                BlockPos p = arena.toWorld(x, TOMB_MIN[1], z);
                GolemBody body = new GolemBody(level);
                body.setNoAi(true);
                body.setPersistenceRequired();
                body.setCustomNameVisible(true);
                double gx = p.getX() + 0.5;
                double gz = p.getZ() + 0.5;
                float yaw = (float) (Math.toDegrees(Math.atan2(c.getZ() - gz, c.getX() - gx)) - 90);
                body.snapTo(gx, ground(level, gx, gz), gz, yaw, 0f);
                level.addFreshEntity(body);
                // Its swing, once awake, is the wiki's hit (SimVitals reads it).
                SimHealth.setBossHit(body, golemDamage());
                Golem g = new Golem(body);
                golems.add(g);
                golemTag(g);
            }
        }
    }

    /** A Sleeping Golem hit: it wakes, with one of Sadan's lines about whoever woke it. */
    private void wake(ServerLevel level, Golem g) {
        g.awake = true;
        g.body.setNoAi(false);
        golemsWoken++;
        level.playSound(null, g.body.getX(), g.body.getY(), g.body.getZ(), SoundEvents.IRON_GOLEM_REPAIR,
                SoundSource.HOSTILE, 1.5f, 0.6f);
        // The hit carries no attacker here, so "whoever woke it" is the nearest player (APPROXIMATE).
        ServerPlayer who = nearest(playersInTomb(level), g.body.position());
        String name = who == null ? "you" : who.getGameProfile().name();
        if (golemsWoken == GOLEM_X.length * GOLEM_Z.length) {
            say(level, String.format(ALL_GOLEMS, name));
        } else if (golemsWoken == 1) {
            say(level, String.format(pick(FIRST_GOLEM), name));
        } else {
            say(level, GOLEM_WOKEN);
        }
    }

    /** The woken Golems run at the nearest player; a dead one goes. */
    private void tickGolems(ServerLevel level) {
        if (golems.isEmpty()) {
            return;
        }
        List<ServerPlayer> targets = ticks % 20 == 0 ? playersInTomb(level) : null;
        for (Golem g : new ArrayList<>(golems)) {
            if (g.body.isRemoved()) {
                golems.remove(g);
                continue;
            }
            if (g.health.hp <= 0) {
                level.sendParticles(ParticleTypes.POOF, g.body.getX(), g.body.getY() + 1.4, g.body.getZ(), 15, 0.5,
                        0.8, 0.5, 0.02);
                g.body.discard();
                golems.remove(g);
                continue;
            }
            if (g.awake && targets != null) {
                ServerPlayer t = nearest(targets, g.body.position());
                if (t != null) {
                    g.body.setTarget(t);
                }
            }
            if (ticks % 10 == 0) {
                golemTag(g);
            }
        }
    }

    private static void golemTag(Golem g) {
        g.body.setCustomName(Component.literal(g.awake ? "Woke Golem " : "Sleeping Golem ")
                .withStyle(ChatFormatting.RED)
                .append(Component.literal(SimBoss.shortHealth(g.health.hp)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("❤").withStyle(ChatFormatting.RED)));
    }

    private void clearGolems() {
        for (Golem g : golems) {
            g.body.discard();
        }
        golems.clear();
    }

    private Golem golem(Entity e) {
        for (Golem g : golems) {
            if (g.body == e) {
                return g;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ The Giant One

    /**
     * The cutscene: his phase three lines, the hole opening in the middle of the hall, The Giant One hooked up out
     * of it, the hole closing, and (M6) the four Mutant Giants back at the corners.
     */
    private void merge(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre);
        if (phaseTicks == 40) {
            say(level, "If only you had become my disciples instead of this incompetent bunch.");
        } else if (phaseTicks == 60) {
            openHole(level, c);
        } else if (phaseTicks == HOOK_TICK) {
            say(level, "Maybe in another life. Until then, meet my ultimate corpse.");
            sadan = spawnBig(level, Kind.SADAN, List.of(Kind.ATTACKS), c, sadanHealth(), false);
            // Untouchable while the hook brings him up, from a Giant's height under the floor.
            sadan.health.invulnerable = true;
            sadan.body.snapTo(c.getX() + 0.5, holeFloor - sadan.body.getBbHeight(), c.getZ() + 0.5, 0f, 0f);
        } else if (phaseTicks > HOOK_TICK && phaseTicks < MERGE_TICKS - 20 && sadan != null) {
            GiantBody b = sadan.body;
            double rise = sadan.body.getBbHeight() / (MERGE_TICKS - 20 - HOOK_TICK);
            b.snapTo(b.getX(), Math.min(holeFloor, b.getY() + rise), b.getZ(), b.getYRot(), 0f);
            if (phaseTicks % 2 == 0) {
                // The hook's chain, down from the ceiling to his head.
                Vec3 top = new Vec3(b.getX(), holeFloor + 30, b.getZ());
                beam(level, ParticleTypes.CRIT, top, new Vec3(b.getX(), b.getY() + b.getBbHeight(), b.getZ()));
            }
            if (phaseTicks % 20 == 0) {
                level.playSound(null, b.getX(), holeFloor, b.getZ(), SoundEvents.CHAIN_PLACE, SoundSource.HOSTILE,
                        2f, 0.5f);
            }
        } else if (phaseTicks == MERGE_TICKS - 20) {
            say(level, "I'm sorry but I need to concentrate. I wish it didn't have to come to this.");
        } else if (phaseTicks >= MERGE_TICKS) {
            closeHole(level);
            if (sadan != null) {
                GiantBody b = sadan.body;
                b.snapTo(b.getX(), ground(level, b.getX(), b.getZ()), b.getZ(), b.getYRot(), 0f);
                sadan.health.invulnerable = false;
                for (Move m : sadan.state.values()) {
                    m.cooldown = 40 + ThreadLocalRandom.current().nextInt(60);
                }
            }
            if (master) {
                say(level, "You thought I'd fall for the same tricks as those before me? Not today!");
                spawnGiants(level, 80);
            }
            toPhase(Phase.SADAN);
        }
    }

    /** The hole in the middle of the hall, its blocks kept to put back. */
    private void openHole(ServerLevel level, BlockPos c) {
        closeHole(level);
        holeCentre = c;
        holeFloor = ground(level, c.getX() + 0.5, c.getZ() + 0.5);
        int top = (int) Math.ceil(holeFloor) - 1;
        for (int dx = -HOLE_RADIUS; dx <= HOLE_RADIUS; dx++) {
            for (int dz = -HOLE_RADIUS; dz <= HOLE_RADIUS; dz++) {
                if (dx * dx + dz * dz > HOLE_RADIUS * HOLE_RADIUS) {
                    continue;
                }
                for (int y = top; y > top - HOLE_DEPTH; y--) {
                    BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                    BlockState state = level.getBlockState(p);
                    if (!state.isAir()) {
                        hole.put(p, state);
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2f,
                0.4f);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, c.getX() + 0.5, holeFloor, c.getZ() + 0.5, 60, HOLE_RADIUS / 2.0,
                0.5, HOLE_RADIUS / 2.0, 0.02);
    }

    private void closeHole(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> e : hole.entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), 2);
        }
        hole.clear();
        holeCentre = null;
    }

    /**
     * Anyone who falls into the hole dies on the spot, and so does every Tank on the team (Diversion, the wiki's
     * reason).
     */
    private void tickHole(ServerLevel level) {
        if (holeCentre == null) {
            return;
        }
        for (ServerPlayer p : SimBoss.players(level.getServer())) {
            if (p.isSpectator() || p.isCreative() || SimDeath.isGhost(p)) {
                continue;
            }
            double dx = p.getX() - (holeCentre.getX() + 0.5);
            double dz = p.getZ() - (holeCentre.getZ() + 0.5);
            if (dx * dx + dz * dz > (HOLE_RADIUS + 0.5) * (HOLE_RADIUS + 0.5) || p.getY() > holeFloor - 0.5) {
                continue;
            }
            DamageSource src = level.damageSources().fellOutOfWorld();
            SimDeath.die(p, src);
            for (ServerPlayer tank : SimBoss.players(level.getServer())) {
                if (tank != p && !SimDeath.isGhost(tank)
                        && SimParty.dungeonClass(tank) == SimStats.DungeonClass.TANK) {
                    SimDeath.die(tank, src);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private AABB tombBox() {
        BlockPos lo = arena.toWorld(TOMB_MIN);
        BlockPos hi = arena.toWorld(TOMB_MAX);
        return new AABB(lo.getX(), lo.getY() - 2 - HOLE_DEPTH, lo.getZ(), hi.getX() + 1, hi.getY() + 16,
                hi.getZ() + 1);
    }

    /**
     * Real players on the tomb floor. The tomb is a rectangle longer than the arena's ring, so this is used
     * instead of {@link SimBoss#playersInRing}, which would miss its far ends.
     */
    private List<ServerPlayer> playersInTomb(ServerLevel level) {
        AABB box = tombBox();
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : SimBoss.players(level.getServer())) {
            if (box.contains(p.position()) && !p.isSpectator() && !p.isCreative()) {
                out.add(p);
            }
        }
        return out;
    }

    /** The top of the floor under (x, z), searched down from a few blocks above the tomb floor. */
    private double ground(ServerLevel level, double x, double z) {
        BlockPos c = arena.toWorld(arena.centre);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos((int) Math.floor(x), c.getY() + 6,
                (int) Math.floor(z));
        for (int i = 0; i < 12; i++, p.move(Direction.DOWN)) {
            VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
            if (!shape.isEmpty()) {
                return p.getY() + shape.max(Direction.Axis.Y);
            }
        }
        return c.getY();
    }

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

    /** Damage plus knockback: {@code damage} is Hypixel damage before Defense ({@link SimVitals#dealHypixel}). */
    private static void hurtPlayer(ServerLevel level, ServerPlayer p, double damage, double dx, double dz,
                                   double push, double lift) {
        DamageSource src = level.damageSources().magic();
        SimVitals.dealHypixel(level, p, src, damage, false);
        if (push <= 0 && lift <= 0) {
            return;
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            double a = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
            len = 1;
        }
        p.setDeltaMovement(dx / len * push, lift, dz / len * push);
        p.syncVelocity = true;
    }

    private Big big(Entity e) {
        if (sadan != null && sadan.body == e) {
            return sadan;
        }
        for (Big g : giants) {
            if (g.body == e) {
                return g;
            }
        }
        return null;
    }

    @Override
    boolean isBoss(Entity entity) {
        return entity instanceof GiantBody ? big(entity) != null
                : entity instanceof GolemBody && golem(entity) != null;
    }

    @Override
    boolean hit(ServerLevel level, LivingEntity body, float amount) {
        Golem golem = golem(body);
        if (golem != null) {
            boolean landed = golem.health.hit((float) (amount * GOLEM_TAKEN));
            if (landed) {
                if (!golem.awake) {
                    wake(level, golem);
                }
                golemTag(golem);
            }
            return landed;
        }
        Big g = big(body);
        if (g == null || g.dead) {
            return false;
        }
        boolean landed = g.health.hit(amount);
        if (landed) {
            nameTag(g);
        }
        return landed;
    }

    @Override
    boolean defeated() {
        return phase == Phase.DEAD;
    }

    @Override
    void discard(ServerLevel level) {
        closeHole(level);
        for (Slot s : slots) {
            if (s.mob != null) {
                s.mob.discard();
                s.mob = null;
            }
        }
        for (Big g : giants) {
            g.body.discard();
        }
        giants.clear();
        if (sadan != null) {
            sadan.body.discard();
            sadan = null;
        }
        bombs.clear();
        clearGolems();
    }

    @Override
    Component barName() {
        String name = switch (phase) {
            case TERRACOTTAS, GIANTS_WAKING -> "Sadan's Interest Meter";
            case GIANTS -> "Sadan - Giants";
            default -> "Sadan";
        };
        return Component.literal(name).withStyle(ChatFormatting.RED);
    }

    @Override
    BossEvent.BossBarColor barColor() {
        return switch (phase) {
            case TERRACOTTAS, GIANTS_WAKING -> BossEvent.BossBarColor.YELLOW;
            case GIANTS -> BossEvent.BossBarColor.PURPLE;
            default -> BossEvent.BossBarColor.RED;
        };
    }

    @Override
    float barProgress() {
        return switch (phase) {
            case TERRACOTTAS -> Math.max(0f, (float) interest / INTEREST_TICKS);
            case GIANTS_WAKING -> 0f;
            case GIANTS -> {
                double hp = 0;
                double max = 0;
                for (Big g : giants) {
                    hp += g.health.hp;
                    max += g.health.max;
                }
                yield max <= 0 ? 1f : (float) (hp / max);
            }
            case MERGE -> 1f;
            case SADAN -> sadan == null ? 1f : sadan.health.fraction();
            default -> 0f;
        };
    }

    /** A Giant's body: no AI (the fight moves it), never despawns. */
    private static final class GiantBody extends Giant {
        GiantBody(Level level) {
            super(EntityTypes.GIANT, level);
        }

        @Override
        public void checkDespawn() {
        }
    }

    /**
     * A Golem's body: an iron golem that goes for players only (a vanilla one would fight the Terracottas, which
     * would take seconds off the Interest Meter), and never despawns.
     */
    private static final class GolemBody extends IronGolem {
        GolemBody(Level level) {
            super(EntityTypes.IRON_GOLEM, level);
        }

        @Override
        protected void registerGoals() {
            super.registerGoals();
            targetSelector.removeAllGoals(g -> true);
            targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
        }

        @Override
        public void checkDespawn() {
        }
    }

    /** A Terracotta: a chasing zombie that never despawns, never burns and drops nothing. */
    private static final class Terracotta extends Zombie {
        Terracotta(Level level, float damage) {
            super(level);
            getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
            setHealth(40);
            getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(damage);
        }

        /** Players only: a zombie goes for iron golems too, and the hall's Golems are iron golems. */
        @Override
        protected void registerGoals() {
            super.registerGoals();
            targetSelector.removeAllGoals(g -> g instanceof NearestAttackableTargetGoal<?>);
            targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        protected boolean isSunSensitive() {
            return false;
        }

        @Override
        protected boolean convertsInWater() {
            return false;
        }

        @Override
        protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
        }

        @Override
        public boolean shouldDropExperience() {
            return false;
        }
    }
}
