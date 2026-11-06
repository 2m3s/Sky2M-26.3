package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.compat.McEntities;
import com.epic60869.sky2m.sim.util.ModChat;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floor 7's boss room, phase by phase: Maxor, Storm, Goldor (the terminals), Necron, and on Master Mode the
 * Wither King's dragons ({@link SimNecronDragons}).
 *
 * <p>The room is the real one at Hypixel's own coordinates ({@link SimBossArena#isNative}), so the mod's F7 features
 * read the sim the way they read Hypixel: the lines below that the mod parses are Hypixel's exact wording (Boss
 * Phases, F7 Extras, Dungeon Features, Rag Axe, Wither Dragons), the crystal pads, terminals, levers, devices,
 * gates and dragon spawn boxes are where those features look for them, and the phase-by-height works because
 * every phase is fought at its own level of the room. Lines nothing reads are the sim's own words.
 *
 * <h2>The phases</h2>
 * <ul>
 * <li><b>P1 Maxor</b> (y 221). Maxor starts invulnerable, with waves of Wither Miners and Wither Guards. Two Energy
 * Crystals spawn on the high ledges; pick one up (walk into it or click it, it goes to the last hotbar slot as
 * Hypixel's does) and walk it to one of the two pads by the walls. With both pads lit the Energy Laser in the
 * middle charges and fires: if Maxor is over it he is stunned, takes the laser's flat hit and can be hurt,
 * otherwise the crystals are wasted. The crystals come back 34 ticks after each laser. The first laser lets him
 * lose 75% of his health, then he enrages; later lasers have no cap. He chases the closest player and fires
 * wither skulls from his three heads.</li>
 * <li><b>P2 Storm</b> (y 165). Storm can only be hurt for 10 s after a crusher hits him: stand on a pad and, about a
 * second after Storm drifts over it, its crusher comes down on him (as in M7 POV videos; he is held over the pad
 * meanwhile). The purple, green and yellow crushers work once each; the red one is broken. Two crushes kill him:
 * the first window cannot take him below 45% (about where M7 parties get him), the second has no cap. A Shadow Assassin guards each working pad, with
 * Withermancers and Wither Guards about. He throws fireballs that land as lightning, enrages on his first crush and
 * at 50%, and on a timer calls the big storm (Giga Lightning, after his two lightning lines the first time; no
 * crusher works before the first one): a countdown, then two strikes on everyone not under an intact crusher. A spent crusher gets one
 * of his "pillar destroyed" lines; all three spent gets "Not a single intact pillar remains!" (and the sim re-arms
 * them where Hypixel would softlock).</li>
 * <li><b>P3 Goldor</b> (y 107-135). The four sections with their terminals, levers and devices
 * ({@link SimTerms#startInArena}: the drill's boards and devices, at the real room's spots); a section's gate
 * opens two seconds after it is done and the section blows up behind the players a little later. Then "The Core
 * entrance is opening!" and Goldor fought in the core. Until then he walks the current section and cannot be
 * hurt; every 60 ticks from Storm's death (the timer Boss Phases shows) his death tick hits everyone not in the
 * current section or the core. Throughout, his frenzy burns anyone close, and he throws his Greatsword, which sets
 * off the TNT traps in the sections. Hitting him in the sections does nothing but slow him (and earns one of his
 * "Stop touching those terminals!" lines); getting ahead of a shut gate earns "What do you think you are doing
 * there!", and a gate going down "The gate has been destroyed!" and "I will replace that gate with a stronger one!"
 * (two seconds after the section, as in M7 videos). In the core his swords now and
 * then block every hit and he fires wither skulls fast.</li>
 * <li><b>P4 Necron</b> (y 64). The platform they land on is destroyed in his speech and they drop into the lava
 * (which throws you back up, as on Hypixel). He circles firing wither skulls from three heads (and now and then
 * stops and fires much faster), launches four Diamond Swords, calls lightning and throws fireballs; every so often
 * he flies up and turns four beam groups round the room for 15 s. At 80% and 5% he frenzies in the middle: everyone
 * is blinded and an explosion ring grows round him, shrinking as he is hurt; when it closes ("ARGH!") he breaks
 * off a piece of the outer ring and fights on ten seconds later. Damage stops at each threshold (a hit cannot take
 * him past 80% or 5%), goes only into the ring during a frenzy, and lands not at all in the ten seconds after. On
 * M7 his death leads into P5 with "Let's make some space!". On M7 a shield now and then blocks every attack.</li>
 * <li><b>P5 the Wither King</b> (M7 only, y 5): see {@link SimNecronDragons}.</li>
 * </ul>
 *
 * <p>A party wiped in a phase hears that wither's two "FINALLY! This took way too long." lines (the Wither King's
 * Defeat lines in P5). F7 ends at Necron: there is no P5 off Master Mode.
 *
 * <p>Between phases the players are moved down to the next level (Hypixel drops them through the floor), and death
 * sends them to the start of the phase they are in. The gaps between phases are short, after Hypixel 0.27.2.
 *
 * <h2>Health and damage</h2>
 * Hypixel's, before the player's Defense unless marked true damage:
 * <ul>
 * <li>Maxor 100,000,000 on F7 / 800,000,000 on M7 (https://hypixelskyblock.minecraft.wiki/w/Maxor), Storm
 * 400,000,000 / 1,000,000,000 (https://hypixelskyblock.minecraft.wiki/w/Storm) and Goldor 750,000,000 /
 * 1,200,000,000 (https://hypixelskyblock.minecraft.wiki/w/Goldor). Their pages give one "min-max" range; the low end
 * is taken as F7 and the high as M7, the wiki's convention (its damage changelogs confirm it for damage). Necron
 * 1,000,000,000 / 1,400,000,000, stated per floor (https://hypixelskyblock.minecraft.wiki/w/Necron).</li>
 * <li>Base damage 7,200 on F7 and 720,000 on M7 for Maxor, Storm and Goldor; Necron's M7 base is 300,000 (the 2026
 * values). Their touch and wither skulls, Necron's fireballs and Goldor's frenzy are built on it - the wiki gives
 * those no number of their own (APPROXIMATE as per-attack numbers).</li>
 * <li>Maxor's laser hits a stunned Maxor for a flat 150,000 / 800,000. Storm's fireball lightning is a flat
 * 75,000 / 1,000,000. Goldor's Greatsword is 1,000,000 direct and 500,000 from a TNT trap on M7 (F7 APPROXIMATE).
 * Necron's lightning is 2x his base, his Diamond Swords 5x.</li>
 * <li>Storm's big storm: each strike is 75% of the player's max health, true damage (Storm's page, for F7; the
 * sim uses it on M7 too).</li>
 * <li>Necron's beam is 25% of the player's max health, true damage (Necron's page), at most once a second.</li>
 * <li>Goldor's death tick has no number on the wiki: the sim's is half the player's max health, true damage
 * (APPROXIMATE).</li>
 * <li>The lava does no damage at all in the sim, by request; Hypixel's takes 20% of max health per bounce (the
 * Storm and Goldor pages).</li>
 * </ul>
 */
final class SimNecron extends SimBoss.Fight {

    /** On everything the fight spawns: crystals, relics, the dragons' collars. */
    static final String TAG = "sky2m_simnecron";

    // ---------------------------------------------------------------------------------------------- numbers

    /** The withers' health, F7 then M7 (see the class comment for the sources). */
    private static final double[] MAXOR_HEALTH = {100_000_000, 800_000_000};
    private static final double[] STORM_HEALTH = {400_000_000, 1_000_000_000};
    private static final double[] GOLDOR_HEALTH = {750_000_000, 1_200_000_000};
    private static final double[] NECRON_HEALTH = {1_000_000_000, 1_400_000_000};
    /** Maxor's, Storm's and Goldor's base damage, F7 then M7. */
    private static final double[] WITHER_DAMAGE = {7_200, 720_000};
    /** Necron's base damage: his M7 number was cut to 300,000 in 2026. */
    private static final double[] NECRON_DAMAGE = {7_200, 300_000};
    /** Storm's big storm strike, Necron's beam and Goldor's death tick: shares of max health, true damage. */
    private static final double STORM_STRIKE_SHARE = 0.75;
    private static final double NECRON_BEAM_SHARE = 0.25;
    private static final double DEATH_TICK_SHARE = 0.5;
    /** An instant kill (the Wither King's pull, a section blowing up with you in it): ten times max health, true. */
    static final double KILL_SHARE = 10;
    /**
     * How long a dead wither's empty bar stays up before the next phase (F7 Extras' "Dead!" titles read it). Hypixel
     * 0.27.2 sped the F7/M7 phase changes up; one second is the sim's guess at the new gap (APPROXIMATE).
     */
    private static final int DEAD_HOLD = 20;

    /** How often a wither says one of his random lines (the wiki's "Random Dialogue"; the rate is APPROXIMATE). */
    private static final int TAUNT_EVERY = 500;
    /** Each wither's random lines, word for word (the wiki's Dialogue tables). */
    private static final String[] MAXOR_TAUNTS = {
            "YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!",
            "YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
            "I HOPE YOU LIKE EXPLOSIONS TOO!",
            "MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!"};
    private static final String[] MAXOR_SKULL_LINES = {
            "How about you taste some rapid fire Wither Skulls!",
            "Eat Wither Skulls, scum!",
            "Time for me to blast you away for good!"};
    private static final String[] STORM_TAUNTS = {
            "The Age of Men is over, we are creating tens, hundreds of withers!!",
            "Not just your land, but every kingdom will soon be ruled by our army of undead!",
            "The days are numbered until I am finally unleashed again on the world!",
            "No more adventurers, no more heroes, death and thunder!"};
    private static final String[] GOLDOR_TAUNTS = {
            "Closer to me!",
            "CLOSER!",
            "Come Closer!",
            "Do you really think we won't repair everything? Your impact will be minuscule!",
            "I am the death zone, you are smart to flee.",
            "You are breaking precious materials, unforgivable.",
            // Two more seen in M7 POV videos (not on the wiki).
            "YOUR END IS NEAR!!",
            "The little ants have a brain it seems."};
    /** Goldor hit while he cannot be hurt, in the terminal sections (the wiki's "When hit in a terminal section"). */
    private static final String[] GOLDOR_HIT_LINES = {
            "Stop touching those terminals!",
            "You can't damage me, you can barely slow me down!",
            "Slowing me down only prolongs your pain!",
            "There is no stopping me down there!"};
    private static final String[] NECRON_TAUNTS = {
            "You merely adopted the Catacombs! I was molded by them!",
            "I - Necron - was destined to rule over Mankind! Dead or Alive!",
            "The Catacombs made you stronger, but can you beat me?!",
            "Show me how you beat Storm!!!",
            "Not just your Village, but the whole world will end!",
            "Fight for your life!"};
    private static final String[] NECRON_PRE_FRENZY = {
            "Sometimes when you have a problem, you just need to destroy it all and start again.",
            "WITNESS MY RAW NUCLEAR POWER!"};
    /** What each wither says when the whole party is dead (the "All players die" rows of the wiki's Dialogue tables). */
    private static final String[] WIPE_LINES = {
            "FINALLY! This took way too long.",
            "Now that you're a Ghost, can you help me clean up?"};
    private static final String[] NECRON_FRENZY = {
            "IF YOU WANT TO STAY COOL, DON'T LOOK!",
            "BOOOOOOOOOOOOOOOOOOOOOOOOOM",
            "HASTA LA VISTA, BABY!"};

    // ---------------------------------------------------------------------------------------------- P1

    /** The Energy Laser: the beacon in the middle of Maxor's room. */
    private static final Vec3 LASER = new Vec3(73.5, 221, 73.5);
    /** The two crystal pads (the pressure plates on the pillars): a placed crystal stands at y 224, as F7 Extras expects. */
    private static final int[][] PADS = {{52, 224, 41}, {94, 224, 41}};
    /** The two top platforms the Energy Crystals spawn on (from a recorded Hypixel P1: y 238, x 64.5 and 82.5, z 50.5). */
    private static final double[][] CRYSTAL_SPOTS = {{64.5, 238, 50.5}, {82.5, 238, 50.5}};
    /** How close (horizontally) Maxor has to be to the laser for a firing to stun him, feet at y 225 or above. */
    private static final double LASER_REACH = 3.5;
    /** Hypixel's crystal respawn after a laser (F7 Extras' timer). */
    private static final int CRYSTAL_RESPAWN_TICKS = 34;
    private static final int LASER_CHARGE_TICKS = 50;
    /** The laser's own hit on a stunned Maxor: a flat 150,000 / 800,000 since Jul 2026 (was 30% of max health). */
    private static final double[] LASER_DAMAGE = {150_000, 800_000};
    /** The first laser lets him lose 75% of his health, then he enrages and is invulnerable again (Maxor's page). */
    private static final double FIRST_STUN_FLOOR = 0.25;
    /** How long a stun leaves him open (APPROXIMATE: the wiki gives no length). */
    private static final int[] MAXOR_STUN = {200, 140};

    /** The Wither Miners and Guards: wiki ranges, low end F7 / high end M7 (the class comment's convention). */
    private static final double[] MINER_HEALTH = {10_000_000, 300_000_000};
    private static final double[] MINER_HIT = {6_000, 80_000};
    private static final double[] GUARD_HEALTH = {3_500_000, 300_000_000};
    private static final double[] GUARD_HIT = {18_000, 202_500};
    private static final double[] MANCER_HEALTH = {180_000, 150_000_000};
    private static final double[] MANCER_HIT = {1_879, 216_000};
    /**
     * Raw M7 hits read off M7 POV videos (before Defense), F7 scaled by the withers' 7,200 / 720,000 base: Maxor's
     * Frenzy 1,986,931 (his touch), Storm's Static Field 1,080,000 (his touch), a Crypt Wither Skull 324,000 (the
     * Withermancers' skulls; the videos also show 120,000, not matched to an attack) and Goldor's Frenzy 3,533,045.
     * The F7 numbers are APPROXIMATE (scaled, not seen).
     */
    private static final double[] MAXOR_FRENZY = {19_869, 1_986_931};
    private static final double[] STORM_STATIC_FIELD = {10_800, 1_080_000};
    private static final double[] MANCER_SKULL = {3_240, 324_000};
    private static final double[] GOLDOR_FRENZY = {35_330, 3_533_045};
    /** Maxor's adds come in waves while he is invulnerable; at most this many alive (APPROXIMATE). */
    private static final int MAXOR_ADDS_CAP = 6;
    private static final int MAXOR_WAVE_EVERY = 300;
    /** "Large numbers" of them spawn as the fight starts (Maxor's page): the first wave fills the cap (APPROXIMATE). */
    private static final int MAXOR_FIRST_WAVE = MAXOR_ADDS_CAP;
    private static final int MAXOR_WAVE = 3;

    // ---------------------------------------------------------------------------------------------- P2

    private static final Vec3 P2_LANDING = new Vec3(73.5, 165, 53.5);
    /** Storm floats this high: the pads' top is y 168 and the pillars hang from y 175. */
    private static final double STORM_Y = 170.5;
    /**
     * The four pads, {pillar centre x, z, pad x0, x1, z0, z1, colour, broken}: green, yellow, red and purple at y 168
     * with a polished diorite pillar over the middle (the fan copy of the room has the west pillars two blocks off the
     * pads' centre; the footprint is read from the blocks, not assumed). The red crusher is broken (Storm's page): it
     * never drops. The fan copy's fourth pad is blue-ish terracotta; it is Hypixel's purple one.
     */
    private static final int[][] STORM_PADS = {
        {46, 41, 38, 58, 32, 46, 0x55FF55, 0},
        {46, 65, 38, 58, 60, 74, 0xFFFF55, 0},
        {100, 41, 90, 110, 32, 46, 0xFF5555, 1},
        {100, 65, 90, 110, 60, 74, 0xAA00AA, 0},
    };
    private static final int PAD_TOP = 168;
    private static final int PILLAR_COOLDOWN = 160;
    /**
     * M7 POV videos: the crusher comes down within about a second of Storm drifting over a pad a player stands on
     * (no lever). The sim waits this long once both are true, then drops the crusher's layers one a tick, holding
     * Storm over the pad meanwhile so the crush lands (APPROXIMATE: 14 ticks + the ~6 layers is ~1 s).
     */
    private static final int CRUSHER_ARM_TICKS = 14;
    /**
     * The first crush's damage window cannot take him below this share of his health: the wiki needs "at least two
     * pillars and enough damage", and in M7 POV videos the party gets him from 1B to about 450M in the first window
     * and to 0 in the second (APPROXIMATE cap, from the videos). The second window has no cap.
     */
    private static final double STORM_FIRST_FLOOR = 0.45;
    /** A crush leaves Storm open for 10 seconds (Storm's page), F7 and M7; only then does damage land. */
    private static final int STORM_VULNERABLE = 200;
    /** His fireballs' lightning: a flat 75,000 / 1,000,000 since Jul 2026 (was 15% / 40% of max health, true). */
    private static final double[] STORM_BOLT = {75_000, 1_000_000};
    /** Storm's lines, word for word (the wiki's Dialogue table): before his first Giga Lightning (the big storm). */
    private static final String[] STORM_STORM_INTRO = {
            "The power of lightning is quite phenomenal. A single strike can vaporize a person whole.",
            "I'd be happy to show you what that's like!"};
    /** When one of his crushers is destroyed (a crusher that has crushed him is spent). */
    private static final String[] STORM_PILLAR_LINES = {
            "THAT WAS ONLY IN MY WAY!",
            "This factory is too small for me!",
            "Slowing me down will be your greatest accomplishment!"};
    /** When all three working crushers are gone. */
    private static final String[] STORM_NO_PILLARS = {
            "Bahahaha! Not a single intact pillar remains!",
            "Rejoice, your last moments are with me and my lightning."};
    /** Ticks from his first lightning line to the second, and from it to the storm's own line (APPROXIMATE). */
    private static final int STORM_INTRO_GAP = 40;
    /**
     * "Storm must use Giga Lightning at least once before being able to be crushed" (The Catacombs - Floor VII page),
     * and in M7 POV videos both storm lines come before the first crush (about 33 s into P2). So the storms run on a
     * timer, not on his health (he cannot be hurt before a crush): his lightning lines after his opening, the first
     * storm, then one every so often (APPROXIMATE timings).
     */
    private static final int STORM_FIRST_GIGA = 110, STORM_GIGA_EVERY = 400;
    /** Shadow Assassin guarding a pad: 20M (Storm's page; the sim uses it on F7, M7 takes the Shadow Assassin table). */
    private static final double PAD_ASSASSIN_HEALTH = 20_000_000;

    // ---------------------------------------------------------------------------------------------- P3

    /** The start of section 1, where Hypixel drops the players from Storm's room. */
    private static final Vec3 P3_LANDING = new Vec3(102.5, 116, 36.5);
    /** The room's own levers, {x, y, z, section}: two per section, where the arena has them. */
    private static final int[][] P3_LEVERS = {
        {94, 124, 113, 1}, {106, 124, 113, 1},
        {27, 124, 127, 2}, {23, 132, 138, 2},
        {2, 122, 55, 3}, {14, 122, 55, 3},
        {84, 121, 34, 4}, {86, 128, 46, 4},
    };
    /** NoammAddons' four P3 sections (as F7 Extras boxes them). */
    private static final AABB[] P3_SECTIONS = {
        new AABB(90, 105, 32, 111, 158, 123),
        new AABB(16, 105, 122, 111, 158, 143),
        new AABB(-3, 106, 48, 19, 158, 142),
        new AABB(-3, 106, 30, 91, 158, 50),
    };
    /** The core, behind the gold door in the inner wall of section 4 (F7 Extras' "in the core" box). */
    private static final AABB CORE = new AABB(42, 106, 54, 68, 155, 119);
    private static final Vec3 GOLDOR_CORE_SPOT = new Vec3(54.5, 114, 85.5);
    /** Goldor's death tick: every 60 ticks from Storm's death line (Boss Phases' timer). */
    private static final int DEATH_TICK = 60;
    /**
     * The three gates, {plane axis (0 = x, 1 = z), plane, barrier plane, from, to}: Hypixel's portcullis is a sheet
     * of cobblestone, cobblestone walls and nether bricks one block thick, with a sheet of barriers behind it,
     * from y 115 to 134. Read off the arena's blocks.
     */
    private static final int[][] GATES = {{1, 123, 122, 96, 104}, {0, 17, 18, 128, 136}, {1, 49, 50, 4, 12}};
    private static final int GATE_Y0 = 115, GATE_Y1 = 134;
    /**
     * From a section's last item to its gate going down. The wiki says five seconds, but M7 POV videos show "The gate
     * has been destroyed!" 1.4-3.8 s after the last item, and no "will open in 5 seconds" line: 2 s (APPROXIMATE).
     */
    static final int GATE_DELAY = 40;
    /** A finished section blows up once Goldor has passed it: five seconds after its gate (APPROXIMATE). */
    private static final int SECTION_BLOW_DELAY = 200;
    /** Goldor's Greatsword: M7 1,000,000 direct and 500,000 from a TNT trap it sets off (Goldor's page, Jul 2026). */
    private static final double[] GREATSWORD = {5 * 7_200, 1_000_000};
    private static final double[] TNT_TRAP = {2.5 * 7_200, 500_000};
    private static final int GREATSWORD_EVERY = 200;
    /** TNT traps placed in each section (APPROXIMATE count). */
    private static final int TRAPS_PER_SECTION = 3;
    /** Goldor's frenzy: "extremely high damage" round him, no number (APPROXIMATE: 3x base every second, 5 blocks). */
    private static final double GOLDOR_FRENZY_RADIUS = 5;
    /** Goldor's walk through the sections, and how much a hit slows him and for how long (APPROXIMATE numbers). */
    private static final double GOLDOR_SPEED = 0.12, GOLDOR_SLOWED = 0.06;
    private static final int GOLDOR_SLOW_TICKS = 20;
    /** How often he says his hit or "passing a gate early" lines at most (APPROXIMATE). */
    private static final int GOLDOR_LINE_GAP = 100;

    // ---------------------------------------------------------------------------------------------- P4

    private static final Vec3 P4_LANDING = new Vec3(54.5, 65, 76.5);
    private static final Vec3 P4_CENTRE = new Vec3(54.5, 70, 76.5);
    static final Vec3 P5_LANDING = new Vec3(54.5, 5, 76.5);
    /** The intro's ticks (shortened for 0.27.2's faster transitions, APPROXIMATE): second line, platform, open. */
    private static final int NECRON_LINE2 = 40, NECRON_DROP = 55, NECRON_OPEN = 75;
    /** The frenzies: at 80% and 5% health (Necron's page). */
    private static final double[] FRENZY_AT = {0.8, 0.05};
    /**
     * The frenzy's explosion ring: it grows while he is left alone and shrinks as he is hurt (Necron's page, no
     * numbers). Since 0.27.2 the fire rings start at half size: 6 blocks of an old 12 (APPROXIMATE sizes).
     */
    private static final double FRENZY_START_RADIUS = 6;
    private static final double FRENZY_MAX_RADIUS = 30;
    /** Blocks a tick the ring grows (APPROXIMATE). */
    private static final double FRENZY_GROWTH = 0.05;
    /** Damage worth this share of his max health closes a 12-block ring (APPROXIMATE). */
    private static final double FRENZY_SHRINK_SHARE = 0.03;
    /** The frenzy's explosions: "extremely high" damage, no number (APPROXIMATE: 4x base, once a second). */
    private static final double FRENZY_DAMAGE_X = 4;
    /** From the ring closing (and a piece of the outer ring going) to his attacks again: "This takes 10 seconds". */
    private static final int FRENZY_RESUME = 200;
    /**
     * The first part of those 10 s he cannot be hurt at all; after it he is stunned ("A threshold ends the frenzy and
     * stuns him", The Catacombs - Floor VII page) and can be hurt again, down to the next threshold. The page gives no
     * length; "a couple of seconds" (APPROXIMATE).
     */
    private static final int FRENZY_IMMUNE = 40;
    /** The beam attack: four rotating horizontal beam groups for 15 s (Necron's page). */
    private static final int BEAM_TICKS = 300;
    private static final double BEAM_LENGTH = 30;
    /** His lightning: 2x base damage since Jul 2026 (was 15% of max health, true). */
    private static final double LIGHTNING_X = 2;
    /** His Diamond Swords: 5x base (the page's "Greatsword 10x -> 5x base" note, taken to be these swords). */
    private static final double SWORD_X = 5;
    /** M7 only: a shield that "protects him from all attacks" (no numbers: every 30 s for 5 s, APPROXIMATE). */
    private static final int SHIELD_EVERY = 600, SHIELD_TICKS = 100;

    private enum Phase { MAXOR, STORM, GOLDOR, NECRON, WITHER_KING, DONE }

    /** The fight the use/attack callbacks hand clicks to. Server thread. */
    private static SimNecron live;

    /** Master Mode: the same F/M split as every number here ({@link SimHealth#byMode}). */
    final boolean master = SimHealth.master(SimState.floorLabel());
    private Phase phase = Phase.MAXOR;
    /** The phase {@link #start} begins at: Maxor for the whole fight, a later one from {@code /boss storm} etc. */
    private Phase startAt = Phase.MAXOR;
    private int phaseTicks;
    /** Fight ticks, from {@link #tick}. */
    int now;
    private ServerLevel level;

    /** The current wither's body and pool; null between phases (and in P5, whose pool is the dragons'). */
    private WitherBody body;
    private SimBoss.Health health = new SimBoss.Health(1);
    /** The pool cannot be hit below this (a stun's share; 0 when it may die). */
    private double floorHp;
    private int deadAt = -1;
    private String bodyName = "Maxor";
    private int contactCooldown;
    /** When the run failed (all five dragons up, a wrong cauldron), or -1. */
    private int failAt = -1;
    /** When the whole party was found dead this phase (for the wither's two wipe lines), or -1. */
    private int wipedAt = -1;

    private final List<Blast> blasts = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    private final List<Zone> zones = new ArrayList<>();
    /** The phase's adds (Wither Miners, Guards, Withermancers, Shadow Assassins). */
    private final List<Mob> adds = new ArrayList<>();
    private final List<Mob> mancers = new ArrayList<>();
    /** Blocks the fight broke or placed (Necron's platform and ring, Goldor's TNT), put back on clean-up. */
    private final Map<BlockPos, BlockState> changed = new HashMap<>();
    private final Map<UUID, Integer> frozenUntil = new HashMap<>();
    private int nextSkulls;
    /** What was in a player's last hotbar slot before he picked up a crystal or a relic. */
    private final Map<UUID, ItemStack> savedSlot = new HashMap<>();
    private final Map<UUID, String> carrying = new HashMap<>();
    private final Map<UUID, Integer> bounceUntil = new HashMap<>();

    // P1
    private final List<EndCrystal> pickups = new ArrayList<>();
    private final EndCrystal[] placed = new EndCrystal[PADS.length];
    private int crystalsAt = -1;
    private int laserAt = -1;
    private int stuns;
    private int stunEnd = -1;
    private boolean enraged;
    private int nextVolley;
    private int nextWave;

    // P2
    private final List<Pillar> pillars = new ArrayList<>();
    private int crushes;
    /** The crusher arming or dropping on Storm, which holds him over its pad until it lands; null when none. */
    private Pillar holding;
    private int pyStep;
    private int pyNext;
    private int pyDone;
    private int pyBursts;
    /** When Storm started his lines before the first big storm, or -1. */
    private int pyIntroAt = -1;
    /** When the next Giga Lightning is due, after the first. */
    private int pyNextStorm;
    /** Whether he has mocked someone caught out in this big storm ("Fool, I'd hide under something..."). */
    private boolean pyMocked;

    // P3
    private int deathTickBase = -1;
    private final List<int[]> pendingGates = new ArrayList<>();
    private final List<int[]> pendingBlows = new ArrayList<>();
    private final List<Vec3> traps = new ArrayList<>();
    private int nextGreatsword;
    private int nextFrenzyPulse;
    /** "Damaging him slows him down" (Goldor's page): until this tick he walks at {@link #GOLDOR_SLOWED}. */
    private int goldorSlowUntil = -1;
    private int goldorHitLineAt = -1000;
    private int goldorGateLineAt = -1000;
    /** When the last gate went down (the game test reads it), or -1. */
    private int lastGateAt = -1;

    // P4
    /** 0 attacking, 1 beam, 2 frenzy, 3 after a frenzy. */
    private int act;
    private int actAt;
    private double orbit;
    private int frenzies;
    private double frenzyRadius;
    /** Damage dealt to him during the frenzy since the last tick: it shrinks the ring, not his health. */
    private double frenzyDamage;
    /** When the current frenzy started, for his line in it. */
    private int frenzyAt;
    /** Whether he has said his sword line this fight. */
    private boolean saidSwords;
    private boolean saidArgh;
    private int nextBeam;
    private int nextSwords;
    private int swordsAt = -1;
    private int nextLightning;
    private int nextFireball;
    private int stillUntil = -1;
    private int nextStill;
    private int nextShield;
    private int shieldUntil = -1;
    private double beamAngle;
    private final Map<UUID, Integer> beamHitAt = new HashMap<>();

    // P5
    private SimNecronDragons dragons;

    /** A telegraphed blast: a ring on the ground, then a hit on everyone inside it. */
    private record Blast(Vec3 at, double radius, int goesOff, double damage, int colour, boolean lightning) {
    }

    /** What a {@link Shot} is: how it looks and what it does when it lands. */
    enum ShotKind { SKULL, SWORD, FIREBALL, BOLT, GREATSWORD, FLAME }

    /**
     * A projectile the fight flies itself: particles, not a vanilla entity, so no block damage and no vanilla hit -
     * the damage is Hypixel's, through {@link #hurtPlayer}.
     */
    private static final class Shot {
        final ShotKind kind;
        final Vec3 vel;
        final int dies;
        final double damage;
        Vec3 at;

        Shot(ShotKind kind, Vec3 at, Vec3 vel, int dies, double damage) {
            this.kind = kind;
            this.at = at;
            this.vel = vel;
            this.dies = dies;
            this.damage = damage;
        }
    }

    /** Ground left burning (the Flame Dragon) or frozen (the Ice Dragon). */
    private record Zone(Vec3 at, double radius, int until, boolean ice, double damage) {
    }

    /** One of Storm's pillars and its pad. */
    private static final class Pillar {
        final int cx, cz;
        final AABB pad;
        final int colour;
        final boolean broken;
        final List<int[]> cells = new ArrayList<>();
        int bottom = 175;
        /** 0 up, 1 dropping, 2 down. */
        int state;
        int layers;
        int ticks;
        int readyAt;
        /** Has crushed Storm once: on Hypixel each crusher works once (three crushes and no kill softlocks). */
        boolean used;

        Pillar(int[] d) {
            cx = d[0];
            cz = d[1];
            pad = new AABB(d[2], PAD_TOP + 0.5, d[4], d[3] + 1, PAD_TOP + 4, d[5] + 1);
            colour = d[6];
            broken = d[7] != 0;
        }
    }

    SimNecron(SimBossArena arena) {
        super(arena);
    }

    /** Server-side clicks on the fight's crystals and relics. Called once from {@link SimBoss#register}. */
    static void register() {
        AttackEntityCallback.EVENT.register((player, lvl, hand, entity, hit) -> {
            if (!(entity instanceof EndCrystal || entity instanceof ArmorStand) || !entity.entityTags().contains(TAG)) {
                return InteractionResult.PASS;
            }
            if (!lvl.isClientSide() && player instanceof ServerPlayer sp && live != null) {
                live.touch(sp, entity);
            }
            // Never a vanilla hit: an End Crystal blows up when hit.
            return InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((player, lvl, hand, entity, hit) -> {
            if (!(entity instanceof EndCrystal || entity instanceof ArmorStand) || !entity.entityTags().contains(TAG)) {
                return InteractionResult.PASS;
            }
            if (!lvl.isClientSide() && player instanceof ServerPlayer sp && live != null) {
                live.touch(sp, entity);
            }
            return InteractionResult.SUCCESS;
        });
    }

    // ---------------------------------------------------------------------------------------------- the frame

    @Override
    String name() {
        // Hypixel's end line names every wither the run fought (and on M7 the Wither King).
        return master ? "Maxor, Storm, Goldor, Necron, and Wither King" : "Maxor, Storm, Goldor, and Necron";
    }

    /**
     * Starts the fight at phase {@code n} (1 Maxor to 5 the Wither King) instead of P1, before {@link #start}. Each
     * phase's start already clears the one before and moves everyone to its landing, so a later one begins exactly
     * as if the phases before it had just been beaten.
     */
    void startAt(int n) {
        Phase[] order = {Phase.MAXOR, Phase.STORM, Phase.GOLDOR, Phase.NECRON, Phase.WITHER_KING};
        // F7 ends at Necron, as on Hypixel: there is no P5 to start at.
        startAt = order[Math.max(1, Math.min(master ? order.length : order.length - 1, n)) - 1];
    }

    @Override
    void start(ServerLevel level) {
        this.level = level;
        live = this;
        patchSection3(level);
        switch (startAt) {
            case STORM -> startStorm(level);
            case GOLDOR -> startGoldor(level);
            case NECRON -> startNecron(level);
            case WITHER_KING -> startWitherKing(level);
            default -> startMaxor(level);
        }
    }

    @Override
    void tick(ServerLevel level, int t) {
        this.level = level;
        now = t;
        phaseTicks++;
        lavaBounce(level);
        if (failAt >= 0) {
            if (phase == Phase.WITHER_KING && wipedAt < 0 && (now - failAt) % 20 == 0) {
                // The Wither King's Defeat lines as the hand closes (the wiki; the spacing is APPROXIMATE).
                String text = SimNecronDragons.defeatLine((now - failAt) / 20);
                if (text != null) {
                    SimBoss.say(level.getServer(), "Wither King", text);
                }
            }
            if (now - failAt == 60) {
                // Out of the tick: leaving discards this fight.
                MinecraftServer server = level.getServer();
                server.execute(() -> SimBoss.leave(server));
            }
            return;
        }
        wipeLines(level);
        switch (phase) {
            case MAXOR -> tickMaxor(level);
            case STORM -> tickStorm(level);
            case GOLDOR -> tickGoldor(level);
            case NECRON -> tickNecron(level);
            case WITHER_KING -> {
                dragons.tick(level);
                if (dragons.finished()) {
                    toPhase(Phase.DONE);
                    cleanUp(level);
                }
            }
            default -> {
            }
        }
        tickBlasts(level);
        tickShots(level);
        tickZones(level);
        tickAdds(level);
        nameTag();
    }

    private void toPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
        deadAt = -1;
        wipedAt = -1;
        blasts.clear();
        shots.clear();
        zones.clear();
        clearAdds();
    }

    /**
     * The phase's wipe lines, once, when everyone in the room is a ghost: the wither's "FINALLY! This took way too
     * long." and "Now that you're a Ghost, can you help me clean up?" a second apart (the spacing is APPROXIMATE), or
     * in P5 the Wither King's Defeat lines ({@link SimNecronDragons#defeatLine}). Spectators do not count either way.
     */
    private void wipeLines(ServerLevel level) {
        if (phase == Phase.DONE) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (wipedAt < 0) {
            boolean ghosts = false;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                ghosts |= !p.isSpectator() && SimDeath.isGhost(p) && arena.contains(p.getX(), p.getY(), p.getZ());
            }
            if (!ghosts || !players(level).isEmpty()) {
                return;
            }
            wipedAt = now;
        }
        int line = (now - wipedAt) / 20;
        if ((now - wipedAt) % 20 != 0) {
            return;
        }
        if (phase == Phase.WITHER_KING) {
            String text = SimNecronDragons.defeatLine(line);
            if (text != null) {
                SimBoss.say(server, "Wither King", text);
            }
        } else if (line < WIPE_LINES.length) {
            SimBoss.say(server, bodyName, WIPE_LINES[line]);
        }
    }

    private static String pick(String[] lines) {
        return lines[ThreadLocalRandom.current().nextInt(lines.length)];
    }

    /** Now and then one of the phase's random lines, while its wither is alive. */
    private void taunt(MinecraftServer server, String speaker, String[] lines) {
        if (deadAt < 0 && phaseTicks % TAUNT_EVERY == TAUNT_EVERY / 2) {
            SimBoss.say(server, speaker, pick(lines));
        }
    }

    /**
     * Fails the run, as Hypixel does when all five dragons are up at once or a relic goes in the wrong cauldron:
     * everything stops, and three seconds later the players are sent back out of the boss room.
     */
    void failRun(ServerLevel level, String why) {
        if (failAt >= 0) {
            return;
        }
        failAt = now;
        blasts.clear();
        shots.clear();
        MinecraftServer server = level.getServer();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            title(p, Component.literal("FAILED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1f, 0.5f);
        }
        SimBoss.tell(server, ModChat.text("The run failed: " + why));
        if (phase == Phase.WITHER_KING && wipedAt < 0) {
            // The first of his Defeat lines; tick says the others.
            SimBoss.say(server, "Wither King", SimNecronDragons.defeatLine(0));
        }
    }

    boolean failed() {
        return failAt >= 0;
    }

    @Override
    boolean isBoss(Entity entity) {
        return (body != null && entity == body) || (dragons != null && dragons.isDragon(entity));
    }

    @Override
    boolean hit(ServerLevel level, LivingEntity hitBody, float amount) {
        if (dragons != null && dragons.isDragon(hitBody)) {
            return dragons.hit(hitBody, amount);
        }
        if (hitBody != body || deadAt >= 0) {
            return false;
        }
        if (phase == Phase.NECRON && shieldUntil > now || phase == Phase.GOLDOR && swordsGuarding()) {
            // Necron's M7 shield and Goldor's swords in the core: the hit is blocked.
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.6f, 1.8f);
            return false;
        }
        if (phase == Phase.GOLDOR && coreAt < 0 && health.invulnerable) {
            // In the sections he cannot be hurt, but a hit slows him, and now and then he says so.
            goldorSlowUntil = now + GOLDOR_SLOW_TICKS;
            if (now - goldorHitLineAt >= GOLDOR_LINE_GAP) {
                goldorHitLineAt = now;
                SimBoss.say(level.getServer(), "Goldor", pick(GOLDOR_HIT_LINES));
            }
            return false;
        }
        if (phase == Phase.NECRON && act == 2) {
            // In his frenzy the damage goes into closing the ring; his health stays at the threshold.
            if (health.invulnerable) {
                return false;
            }
            frenzyDamage += amount;
            return true;
        }
        if (!health.hit(amount)) {
            return false;
        }
        // Each window's cap: Maxor's first stun (75%), Storm's first crush, Necron's next frenzy threshold.
        health.hp = Math.max(health.hp, floorHp);
        return true;
    }

    @Override
    boolean defeated() {
        return phase == Phase.DONE;
    }

    @Override
    void discard(ServerLevel level) {
        cleanUp(level);
        if (live == this) {
            live = null;
        }
    }

    /** Removes everything the fight placed and gives back the players' last hotbar slots. */
    private void cleanUp(ServerLevel level) {
        removeBody();
        for (EndCrystal c : pickups) {
            c.discard();
        }
        pickups.clear();
        for (int i = 0; i < placed.length; i++) {
            if (placed[i] != null) {
                placed[i].discard();
                placed[i] = null;
            }
        }
        if (dragons != null) {
            dragons.discard();
        }
        for (Pillar p : pillars) {
            raise(level, p);
        }
        SimTerms.stopInArena(level.getServer());
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            uncarry(p);
        }
        savedSlot.clear();
        carrying.clear();
        blasts.clear();
        shots.clear();
        zones.clear();
        clearAdds();
        // Flag 2 only: no neighbour updates, so lava next to a put-back block is not told to flow.
        for (Map.Entry<BlockPos, BlockState> e : changed.entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
        }
        changed.clear();
        traps.clear();
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e.entityTags().contains(TAG)) {
                doomed.add(e);
            }
        }
        for (Entity e : doomed) {
            e.discard();
        }
    }

    @Override
    Component barName() {
        String name = phase == Phase.WITHER_KING || phase == Phase.DONE && master ? "Wither King" : bodyName;
        ChatFormatting colour = switch (phase) {
            case MAXOR -> ChatFormatting.DARK_PURPLE;
            case STORM -> ChatFormatting.AQUA;
            case GOLDOR -> ChatFormatting.GOLD;
            case NECRON -> ChatFormatting.RED;
            default -> ChatFormatting.DARK_RED;
        };
        return Component.literal(name).withStyle(colour, ChatFormatting.BOLD);
    }

    @Override
    BossEvent.BossBarColor barColor() {
        return switch (phase) {
            case MAXOR -> BossEvent.BossBarColor.PURPLE;
            case STORM -> BossEvent.BossBarColor.BLUE;
            case GOLDOR -> BossEvent.BossBarColor.YELLOW;
            case NECRON -> BossEvent.BossBarColor.RED;
            default -> BossEvent.BossBarColor.PINK;
        };
    }

    @Override
    float barProgress() {
        if (phase == Phase.WITHER_KING && dragons != null) {
            return dragons.progress();
        }
        return deadAt >= 0 ? 0f : health.fraction();
    }

    // ---------------------------------------------------------------------------------------------- P1 Maxor

    private void startMaxor(ServerLevel level) {
        toPhase(Phase.MAXOR);
        newPool("Maxor", MAXOR_HEALTH);
        Vec3 at = Vec3.atBottomCenterOf(arena.toWorld(arena.bossSpawn));
        body = spawnWither(level, at);
        // Hypixel's first line, word for word: Boss Phases, Dungeon Features and F7 Extras start P1 on it.
        SimBoss.say(level.getServer(), "Maxor", "WELL! WELL! WELL! LOOK WHO'S HERE!");
        crystalsAt = now + 40;
        nextSkulls = now + 60;
        nextWave = now + 20;
        stuns = 0;
        enraged = false;
        SimSurvival.setHome(arena.toWorld(arena.entry));
    }

    private void tickMaxor(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (deadAt >= 0) {
            if (now - deadAt == DEAD_HOLD / 2) {
                // The second of Hypixel's two death lines (the wiki).
                SimBoss.say(server, "Maxor", "I'LL MAKE YOU REMEMBER MY DEATH!!");
            }
            if (now - deadAt >= DEAD_HOLD) {
                startStorm(level);
            }
            return;
        }
        if (phaseTicks == 30) {
            SimBoss.say(server, "Maxor", "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.");
        } else if (phaseTicks == 70) {
            SimBoss.say(server, "Maxor", "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.");
        }
        taunt(server, "Maxor", MAXOR_TAUNTS);
        boolean stunned = stunEnd > now;
        if (stunned) {
            glide(body, new Vec3(LASER.x, LASER.y + 5, LASER.z), 0.6);
            beam(level, ParticleTypes.END_ROD, new Vec3(LASER.x, LASER.y + 1, LASER.z), new Vec3(LASER.x, LASER.y + 20, LASER.z), 0.5);
            // The first laser: once he has lost 75% he enrages and the stun is over.
            if (stuns == 1 && health.hp <= floorHp) {
                stunEnd = now;
            }
        } else {
            chase(body, 226, enraged ? 0.24 : 0.18, 5);
        }
        if (crystalsAt >= 0 && now >= crystalsAt) {
            crystalsAt = -1;
            spawnCrystals(level);
        }
        tickCrystals(level);
        if (laserAt >= 0) {
            if (now % 4 == 0) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, LASER.x, LASER.y + 2, LASER.z, 12, 0.6, 1.5, 0.6, 0.05);
            }
            if (now >= laserAt) {
                fireLaser(level);
            }
        }
        if (stunEnd >= 0 && now >= stunEnd) {
            stunEnd = -1;
            health.invulnerable = true;
            floorHp = 0;
            nextWave = now + 40;
            if (!enraged) {
                // F7 Extras turns this subtitle into its coloured "enraged" title.
                enraged = true;
                subtitleAll(server, Component.literal("⚠ Maxor is enraged! ⚠").withStyle(ChatFormatting.DARK_PURPLE));
            }
        }
        // His attack is wither skulls (the sim's old ground blasts are gone).
        if (!stunned && now >= nextSkulls) {
            fireSkulls(level, 1.1, baseDamage());
            // Now and then one of his Wither Skull lines as he fires (how often is APPROXIMATE).
            if (ThreadLocalRandom.current().nextInt(8) == 0) {
                SimBoss.say(server, "Maxor", pick(MAXOR_SKULL_LINES));
            }
            nextSkulls = now + (enraged ? 30 : 45) - (master ? 10 : 0);
        }
        // Wither Miners and Guards pour in while he cannot be hurt.
        if (health.invulnerable && !stunned && now >= nextWave) {
            // The first wave, as the fight starts, is the big one.
            maxorWave(level, phaseTicks <= 40 ? MAXOR_FIRST_WAVE : MAXOR_WAVE);
            nextWave = now + MAXOR_WAVE_EVERY;
        }
        contact(level);
        if (health.hp <= 0) {
            SimBoss.say(server, "Maxor", "I'M TOO YOUNG TO DIE AGAIN!");
            killBody(level);
        }
    }

    /**
     * A wave of {@code count} adds round the room's edge, two Wither Miners to each Wither Guard, up to
     * {@link #MAXOR_ADDS_CAP} alive.
     */
    private void maxorWave(ServerLevel level, int count) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count && adds.size() < MAXOR_ADDS_CAP; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int x = (int) Math.floor(LASER.x + Math.cos(a) * 22);
            int z = (int) Math.floor(LASER.z - 18 + Math.sin(a) * 14);
            double y = ground(level, x, z, 226, 214);
            Vec3 at = new Vec3(x + 0.5, y, z + 0.5);
            if (i % 3 < 2) {
                spawnAdd(level, "Wither Miner", at, false, MINER_HEALTH, MINER_HIT,
                        new ItemStack(Items.WITHER_SKELETON_SKULL), new ItemStack(Items.STONE_PICKAXE));
            } else {
                spawnAdd(level, "Wither Guard", at, true, GUARD_HEALTH, GUARD_HIT,
                        new ItemStack(Items.WITHER_SKELETON_SKULL), new ItemStack(Items.BOW));
            }
        }
    }

    private void spawnCrystals(ServerLevel level) {
        for (EndCrystal c : pickups) {
            c.discard();
        }
        pickups.clear();
        for (double[] at : CRYSTAL_SPOTS) {
            EndCrystal c = new EndCrystal(level, at[0], at[1], at[2]);
            c.setShowBottom(false);
            c.setPermanentlyInvulnerable(true);
            c.addTag(TAG);
            if (level.addFreshEntity(c)) {
                pickups.add(c);
            }
        }
        level.playSound(null, LASER.x, LASER.y, LASER.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 2f, 1.2f);
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }


    /** A pickup by walking into a crystal, a placement by walking a carried one up to a free pad. */
    private void tickCrystals(ServerLevel level) {
        for (ServerPlayer p : players(level)) {
            if (!carrying.containsKey(p.getUUID())) {
                for (EndCrystal c : new ArrayList<>(pickups)) {
                    if (p.distanceToSqr(c) < 2.2 * 2.2) {
                        pickCrystal(level, p, c);
                        break;
                    }
                }
                continue;
            }
            if (!"crystal".equals(carrying.get(p.getUUID()))) {
                continue;
            }
            for (int i = 0; i < PADS.length; i++) {
                int[] pad = PADS[i];
                double dx = p.getX() - (pad[0] + 0.5);
                double dz = p.getZ() - (pad[2] + 0.5);
                if (placed[i] == null && dx * dx + dz * dz < 2.8 * 2.8 && Math.abs(p.getY() - pad[1]) < 4.5) {
                    placeCrystal(level, p, i);
                    break;
                }
            }
        }
    }

    private void pickCrystal(ServerLevel level, ServerPlayer p, EndCrystal c) {
        pickups.remove(c);
        c.discard();
        ItemStack item = new ItemStack(Items.END_CRYSTAL);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Energy Crystal").withStyle(ChatFormatting.AQUA));
        carry(p, "crystal", item);
        // Hypixel's line: F7 Extras starts its crystal place timer on it.
        broadcast(level.getServer(), Component.literal(p.getGameProfile().name() + " picked up an Energy Crystal!"));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1f, 1f);
    }

    private void placeCrystal(ServerLevel level, ServerPlayer p, int i) {
        uncarry(p);
        int[] pad = PADS[i];
        EndCrystal c = new EndCrystal(level, pad[0] + 0.5, pad[1], pad[2] + 0.5);
        c.setShowBottom(false);
        c.setPermanentlyInvulnerable(true);
        c.setBeamTarget(BlockPos.containing(LASER.x, LASER.y + 6, LASER.z));
        c.addTag(TAG);
        level.addFreshEntity(c);
        placed[i] = c;
        int active = 0;
        for (EndCrystal e : placed) {
            if (e != null) {
                active++;
            }
        }
        MinecraftServer server = level.getServer();
        // Hypixel's subtitles: F7 Extras shows the first as its "Crystal (1/2)" title and hides the second.
        subtitleAll(server, Component.literal(active + "/" + PADS.length + " Energy Crystals are now active!")
                .withStyle(ChatFormatting.AQUA));
        if (active == PADS.length) {
            subtitleAll(server, Component.literal("The Energy Laser is charging up!").withStyle(ChatFormatting.AQUA));
            laserAt = now + LASER_CHARGE_TICKS;
            level.playSound(null, LASER.x, LASER.y, LASER.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 3f, 1f);
        }
    }

    private void fireLaser(ServerLevel level) {
        MinecraftServer server = level.getServer();
        laserAt = -1;
        for (int i = 0; i < placed.length; i++) {
            if (placed[i] != null) {
                placed[i].discard();
                placed[i] = null;
            }
        }
        crystalsAt = now + CRYSTAL_RESPAWN_TICKS;
        level.playSound(null, LASER.x, LASER.y, LASER.z, SoundEvents.GUARDIAN_ATTACK, SoundSource.HOSTILE, 3f, 0.6f);
        beam(level, ParticleTypes.END_ROD, new Vec3(LASER.x, LASER.y + 1, LASER.z), new Vec3(LASER.x, LASER.y + 24, LASER.z), 0.3);
        // Only a Maxor lured into the laser is stunned; otherwise it fires for nothing (Hypixel says nothing then -
        // there is no "missed" line) and the crystals go round again.
        double dx = body.getX() - LASER.x;
        double dz = body.getZ() - LASER.z;
        if (dx * dx + dz * dz > LASER_REACH * LASER_REACH || body.getY() < 225) {
            return;
        }
        stuns++;
        // Hypixel's two stun lines, in the M7 videos' order: F7 Extras' crystal respawn timer and "Maxor Stunned!"
        // start on them.
        SimBoss.say(server, "Maxor", stuns == 1 ? "YOU TRICKED ME!" : "THAT BEAM! IT HURTS! IT HURTS!!");
        stunEnd = now + (master ? MAXOR_STUN[1] : MAXOR_STUN[0]);
        health.invulnerable = false;
        // The first laser can take him down to a quarter (75% of his health); after that there is no cap.
        floorHp = stuns == 1 ? health.max * FIRST_STUN_FLOOR : 0;
        health.hp = Math.max(floorHp, health.hp - SimHealth.byMode(LASER_DAMAGE[0], LASER_DAMAGE[1]));
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 2f, 0.8f);
    }

    // ---------------------------------------------------------------------------------------------- P2 Storm

    private void startStorm(ServerLevel level) {
        removeBody();
        clearCarried(level);
        toPhase(Phase.STORM);
        moveAll(level, P2_LANDING, 180f);
        newPool("Storm", STORM_HEALTH);
        body = spawnWither(level, new Vec3(P2_LANDING.x, STORM_Y + 6, P2_LANDING.z + 12));
        // Hypixel's line, word for word: Storm's phase (and Rag Axe's prompt) start on it.
        SimBoss.say(level.getServer(), "Storm", "Pathetic Maxor, just like expected.");
        pillars.clear();
        for (int[] d : STORM_PADS) {
            Pillar p = new Pillar(d);
            for (int x = p.cx - 6; x <= p.cx + 6; x++) {
                for (int z = p.cz - 6; z <= p.cz + 6; z++) {
                    if (level.getBlockState(new BlockPos(x, 190, z)).is(Blocks.POLISHED_DIORITE)) {
                        p.cells.add(new int[]{x, z});
                    }
                }
            }
            for (int y = PAD_TOP + 1; y < 210; y++) {
                if (level.getBlockState(new BlockPos(p.cx, y, p.cz)).is(Blocks.POLISHED_DIORITE)) {
                    p.bottom = y;
                    break;
                }
            }
            if (p.cells.isEmpty()) {
                // Nothing at y 190 in this copy of the room: read the footprint off the pillar's own bottom layer.
                for (int x = p.cx - 6; x <= p.cx + 6; x++) {
                    for (int z = p.cz - 6; z <= p.cz + 6; z++) {
                        if (level.getBlockState(new BlockPos(x, p.bottom, z)).is(Blocks.POLISHED_DIORITE)) {
                            p.cells.add(new int[]{x, z});
                        }
                    }
                }
            }
            pillars.add(p);
        }
        crushes = 0;
        holding = null;
        enraged = false;
        pyStep = 0;
        pyDone = 0;
        pyBursts = 0;
        pyIntroAt = -1;
        nextVolley = now + 60;
        stormAdds(level);
    }

    /**
     * Storm's adds: a Shadow Assassin guarding each working pad (Storm's page), and two Withermancers and two Wither
     * Guards in the room (APPROXIMATE spots and counts).
     */
    private void stormAdds(ServerLevel level) {
        for (Pillar pl : pillars) {
            if (pl.broken) {
                continue;
            }
            Vec3 c = pl.pad.getCenter();
            int x = (int) Math.floor(c.x), z = (int) Math.floor(c.z);
            Mob m = spawnAdd(level, "Shadow Assassin", new Vec3(x + 0.5, ground(level, x, z, 175, 150), z + 0.5), false,
                    new double[]{PAD_ASSASSIN_HEALTH, PAD_ASSASSIN_HEALTH}, null,
                    null, new ItemStack(Items.IRON_SWORD));
            SimHealth.Pool pool = SimHealth.get(m);
            if (pool != null && !master) {
                pool.max = PAD_ASSASSIN_HEALTH;
                pool.hp = PAD_ASSASSIN_HEALTH;
            }
        }
        int[][] guards = {{73, 36}, {73, 71}};
        int[][] mancerSpots = {{62, 53}, {85, 53}};
        for (int[] g : guards) {
            spawnAdd(level, "Wither Guard", new Vec3(g[0] + 0.5, ground(level, g[0], g[1], 175, 150), g[1] + 0.5), true,
                    GUARD_HEALTH, GUARD_HIT, new ItemStack(Items.WITHER_SKELETON_SKULL), new ItemStack(Items.BOW));
        }
        for (int[] g : mancerSpots) {
            Mob m = spawnAdd(level, "Withermancer", new Vec3(g[0] + 0.5, ground(level, g[0], g[1], 175, 150), g[1] + 0.5),
                    false, MANCER_HEALTH, MANCER_HIT, new ItemStack(Items.WITHER_SKELETON_SKULL), new ItemStack(Items.BONE));
            if (m != null) {
                mancers.add(m);
            }
        }
    }

    private int crushesNeeded() {
        // At least two crushes (purple, green or yellow) and enough damage, F7 and M7 (Storm's page).
        return 2;
    }

    private void tickStorm(ServerLevel level) {
        MinecraftServer server = level.getServer();
        tickPillars(level);
        if (deadAt >= 0) {
            if (now - deadAt == DEAD_HOLD / 2) {
                // The second of Hypixel's two death lines (the wiki).
                SimBoss.say(server, "Storm", "At least my son died by your hands.");
            }
            if (now - deadAt >= DEAD_HOLD) {
                startGoldor(level);
            }
            return;
        }
        // The rest of Hypixel's opening, word for word (the spacing is APPROXIMATE).
        if (phaseTicks == 30) {
            SimBoss.say(server, "Storm", "Don't boast about beating this simple-minded Wither.");
        } else if (phaseTicks == 60) {
            SimBoss.say(server, "Storm", "My abilities are unparalleled, in many ways I am the last bastion.");
        } else if (phaseTicks == 90) {
            SimBoss.say(server, "Storm", "The memory of your death will be your fondest, focus up!");
        }
        taunt(server, "Storm", STORM_TAUNTS);
        boolean stunned = stunEnd > now;
        if (holding != null) {
            // A crusher is coming down on him: he is held over its pad until it lands.
            glide(body, new Vec3(holding.cx + 0.5, STORM_Y, holding.cz + 0.5), 0.6);
        } else if (!stunned) {
            chase(body, STORM_Y, enraged ? 0.2 : 0.15, 2.5);
        }
        if (stunEnd >= 0 && now >= stunEnd) {
            // The 10 s window is over: he cannot be hurt again until the next crush.
            stunEnd = -1;
            health.invulnerable = true;
            floorHp = 0;
            rearmIfSpent(server);
        }
        double frac = health.fraction();
        if (frac <= 0.5) {
            enrage(server);
        }
        // The big storm (Giga Lightning) on its timer (STORM_FIRST_GIGA): Hypixel's lines, which Boss Phases'
        // lightning timer and Dungeon Features start on.
        boolean stormDue = pyStep == 0 && pyBursts == 0 && deadAt < 0
                && (pyDone == 0 && phaseTicks >= STORM_FIRST_GIGA || pyDone > 0 && now >= pyNextStorm);
        if (stormDue && pyDone == 0 && pyIntroAt < 0) {
            // Before the first storm, his two lines about lightning (the wiki's "About to start Thunder Strike").
            pyIntroAt = now;
            SimBoss.say(server, "Storm", STORM_STORM_INTRO[0]);
        } else if (pyIntroAt >= 0 && now - pyIntroAt == STORM_INTRO_GAP) {
            SimBoss.say(server, "Storm", STORM_STORM_INTRO[1]);
        }
        if (stormDue && (pyDone > 0 || now - pyIntroAt >= 2 * STORM_INTRO_GAP)) {
            SimBoss.say(server, "Storm", pyDone % 2 == 0 ? "ENERGY HEED MY CALL!" : "THUNDER LET ME BE YOUR CATALYST!");
            pyDone++;
            pyNextStorm = now + STORM_GIGA_EVERY;
            pyMocked = false;
            // F7 Extras reads the first number as the storm's length (1.35 s a number).
            pyStep = master ? 6 : 4;
            pyNext = now + 20;
        }
        if (pyStep > 0 && now >= pyNext) {
            stormCount(level, pyStep);
            pyStep--;
            pyNext = now + 27;
            if (pyStep == 0) {
                pyBursts = 2;
            }
        } else if (pyBursts > 0 && now >= pyNext) {
            // After the countdown, two bursts on everyone not under an intact crusher (Storm's page).
            stormBurst(level);
            pyBursts--;
            pyNext = now + 12;
        }
        if (pyStep == 0 && pyBursts == 0 && !stunned && now >= nextVolley) {
            // His fireballs: each lands as an area of lightning, a flat number (not a share of health).
            for (ServerPlayer p : players(level)) {
                shoot(ShotKind.BOLT, body.position().add(0, 2.5, 0), p.position().add(0, 0.5, 0), 0.9,
                        SimHealth.byMode(STORM_BOLT[0], STORM_BOLT[1]));
            }
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, 0.7f);
            nextVolley = now + (enraged ? 50 : 70) - (master ? 10 : 0);
        }
        contact(level);
        if (health.hp <= 0) {
            // Hypixel's death line, word for word: Goldor's death tick timer and Storm Dead! start on it.
            SimBoss.say(server, "Storm", "I should have known that I stood no chance.");
            deathTickBase = now;
            killBody(level);
        }
    }

    private void enrage(MinecraftServer server) {
        if (!enraged) {
            enraged = true;
            subtitleAll(server, Component.literal("⚠ Storm is enraged! ⚠").withStyle(ChatFormatting.AQUA));
        }
    }

    /**
     * On Hypixel each crusher works once, and three crushes without enough damage softlock the fight. The sim gives
     * the crushers back instead (sim-only), so a practice run never gets stuck.
     */
    private void rearmIfSpent(MinecraftServer server) {
        for (Pillar pl : pillars) {
            if (!pl.broken && !pl.used) {
                return;
            }
        }
        // Hypixel's lines for this moment, before the sim's re-arm.
        SimBoss.say(server, "Storm", STORM_NO_PILLARS[0]);
        SimBoss.say(server, "Storm", STORM_NO_PILLARS[1]);
        for (Pillar pl : pillars) {
            pl.used = false;
        }
        SimBoss.tell(server, ModChat.text("Every crusher is spent and Storm still stands: the sim re-arms them "
                + "(Hypixel would softlock here)."));
    }

    /** One number of the big storm's countdown. */
    private void stormCount(ServerLevel level, int number) {
        for (ServerPlayer p : players(level)) {
            title(p, Component.literal(String.valueOf(number)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
    }

    /**
     * One burst of the big storm (Storm's Giga Lightning): a strike on everyone who is not under an intact crusher.
     * The first time it catches someone in a storm he says his "hide under something" line (the wiki lists it as
     * "Player under a pillar"; when exactly is APPROXIMATE).
     */
    private void stormBurst(ServerLevel level) {
        boolean caught = false;
        for (ServerPlayer p : players(level)) {
            if (underIntactPillar(p)) {
                continue;
            }
            lightning(level, p.position());
            hurtPlayerTrue(level, p, STORM_STRIKE_SHARE, 0.4);
            caught = true;
        }
        if (caught && !pyMocked) {
            pyMocked = true;
            SimBoss.say(level.getServer(), "Storm", "Fool, I'd hide under something next time if I were you!");
        }
    }

    /** Under a crusher that is up and has neither broken nor been spent. */
    private boolean underIntactPillar(ServerPlayer p) {
        int bx = p.getBlockX(), bz = p.getBlockZ();
        for (Pillar pl : pillars) {
            if (pl.broken || pl.used || pl.state != 0 || p.getY() >= pl.bottom) {
                continue;
            }
            for (int[] c : pl.cells) {
                if (c[0] == bx && c[1] == bz) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The crushers, as in M7 POV videos: a player stands on a working pad (purple, green or yellow; the red one is
     * broken) and Storm drifts over it; about a second later the crusher (the white block mass hanging over the pad)
     * comes down on him. Storm is held over the pad from the moment it arms, so a crush that arms always lands.
     * States: 0 up, 3 arming, 1 dropping, 2 down.
     */
    private void tickPillars(ServerLevel level) {
        for (Pillar pl : pillars) {
            switch (pl.state) {
                case 0 -> {
                    // No crush before his first Giga Lightning (The Catacombs - Floor VII page).
                    if (pl.broken || pl.used || deadAt >= 0 || body == null || now < pl.readyAt || stunEnd > now
                            || holding != null || pyDone == 0 || !over(pl)) {
                        continue;
                    }
                    boolean manned = false;
                    for (ServerPlayer p : players(level)) {
                        manned |= standsOnPad(level, pl, p);
                    }
                    if (manned) {
                        pl.state = 3;
                        pl.ticks = 0;
                        holding = pl;
                        level.playSound(null, pl.cx, pl.bottom, pl.cz, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 2f, 0.5f);
                    }
                }
                case 3 -> {
                    if (++pl.ticks >= CRUSHER_ARM_TICKS) {
                        pl.state = 1;
                        pl.layers = 0;
                    }
                }
                case 1 -> {
                    int y = pl.bottom - 1 - pl.layers;
                    if (y > PAD_TOP) {
                        fillLayer(level, pl, y, Blocks.POLISHED_DIORITE.defaultBlockState());
                        pl.layers++;
                        continue;
                    }
                    pl.state = 2;
                    pl.ticks = 0;
                    if (holding == pl) {
                        holding = null;
                    }
                    if (body != null && deadAt < 0 && under(pl)) {
                        crush(level, pl);
                    }
                }
                default -> {
                    if (++pl.ticks >= 30) {
                        raise(level, pl);
                        if (pl.used && body != null && deadAt < 0) {
                            // The crusher that got him is spent: one of his "pillar destroyed" lines (the wiki's
                            // trigger; saying it as the crusher goes back up is APPROXIMATE).
                            SimBoss.say(level.getServer(), "Storm", pick(STORM_PILLAR_LINES));
                        }
                    }
                }
            }
        }
    }

    /**
     * On the ground, on the pad itself, as on Hypixel: the block under his feet is the pad's block (the one under the
     * crusher). Being anywhere in the pad's box used to count, so jumping or flying over it, or walking past, dropped it.
     */
    private static boolean standsOnPad(ServerLevel level, Pillar pl, ServerPlayer p) {
        if (!p.onGround() || !pl.pad.contains(p.position())) {
            return false;
        }
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.1, p.getZ());
        if (below.getY() != PAD_TOP) {
            return false;
        }
        BlockState padBlock = level.getBlockState(new BlockPos(pl.cx, PAD_TOP, pl.cz));
        return !padBlock.isAir() && level.getBlockState(below).is(padBlock.getBlock());
    }

    /** Storm over the pad (its box, a block of slack), where a manned pad arms its crusher. */
    private boolean over(Pillar pl) {
        return body.getX() >= pl.pad.minX - 1 && body.getX() <= pl.pad.maxX + 1
                && body.getZ() >= pl.pad.minZ - 1 && body.getZ() <= pl.pad.maxZ + 1;
    }

    private boolean under(Pillar pl) {
        double dx = body.getX() - (pl.cx + 0.5);
        double dz = body.getZ() - (pl.cz + 0.5);
        return dx * dx + dz * dz < 4.5 * 4.5;
    }

    /** One layer of a dropping pillar, leaving out any cell a player stands in (no one is buried alive). */
    private void fillLayer(ServerLevel level, Pillar pl, int y, BlockState state) {
        for (int[] c : pl.cells) {
            AABB cell = new AABB(c[0], y, c[1], c[0] + 1, y + 1, c[1] + 1);
            boolean taken = false;
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                taken |= p.getBoundingBox().intersects(cell);
            }
            if (!taken) {
                level.setBlock(new BlockPos(c[0], y, c[1]), state, Block.UPDATE_CLIENTS);
            }
        }
    }

    private void raise(ServerLevel level, Pillar pl) {
        if (pl.state == 0) {
            return;
        }
        for (int y = PAD_TOP + 1; y < pl.bottom; y++) {
            for (int[] c : pl.cells) {
                BlockPos pos = new BlockPos(c[0], y, c[1]);
                if (level.getBlockState(pos).is(Blocks.POLISHED_DIORITE)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        pl.state = 0;
        pl.readyAt = now + PILLAR_COOLDOWN;
    }

    private void crush(ServerLevel level, Pillar pl) {
        MinecraftServer server = level.getServer();
        crushes++;
        pl.used = true;
        // M7 POV videos: "Ouch, that hurt!" after each crush (F7 Extras' "Storm Crushed!" title starts on it). The
        // wiki's other crush line, "Oof", is in the videos too but not tied to a crush there, so the sim leaves it out.
        SimBoss.say(server, "Storm", "Ouch, that hurt!");
        level.sendParticles(ParticleTypes.EXPLOSION, body.getX(), body.getY() + 1, body.getZ(), 4, 1, 1, 1, 0);
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 2f, 0.6f);
        // Open for 10 seconds: the players' damage in the window is what takes his health. The first window cannot
        // take him past STORM_FIRST_FLOOR, so he never dies before his second crush; later windows have no cap.
        floorHp = crushes >= crushesNeeded() ? 0 : health.max * STORM_FIRST_FLOOR;
        health.invulnerable = false;
        stunEnd = now + STORM_VULNERABLE;
        if (crushes == 1) {
            // M7 POV videos: "⚠ Storm is enraged! ⚠" right after the first crush.
            enrage(server);
        }
    }

    // ---------------------------------------------------------------------------------------------- P3 Goldor

    /** When Goldor took his stand in the core, or -1. */
    private int coreAt = -1;

    private void startGoldor(ServerLevel level) {
        for (Pillar pl : pillars) {
            raise(level, pl);
        }
        removeBody();
        toPhase(Phase.GOLDOR);
        moveAll(level, P3_LANDING, 0f);
        newPool("Goldor", GOLDOR_HEALTH);
        body = spawnWither(level, new Vec3(100.5, 117, 70.5));
        // Hypixel's line, word for word: Terminals 1, the death tick's resync and Rag Axe start on it.
        SimBoss.say(level.getServer(), "Goldor", "Who dares trespass into my domain?");
        // Boss Phases and Dungeon Features restart the death tick timer on this line, so the fight does too.
        deathTickBase = now;
        coreAt = -1;
        goldorSlowUntil = -1;
        lastGateAt = -1;
        pendingGates.clear();
        pendingBlows.clear();
        nextGreatsword = now + GREATSWORD_EVERY;
        nextFrenzyPulse = now + 20;
        placeTraps(level);
        SimTerms.startInArena(level, new SimTerms.Arena() {
            @Override
            public void openGate(ServerLevel l, int section) {
                SimNecron.this.sectionDone(section);
            }

            @Override
            public void openCore(ServerLevel l) {
                SimNecron.this.openCore(l);
                // Section 4 goes up behind the players once Goldor has gone into the core.
                pendingBlows.add(new int[]{4, now + SECTION_BLOW_DELAY});
            }
        }, P3_LEVERS);
        SimBoss.tell(level.getServer(), ModChat.text(
                "P3: each section's terminals, two levers and its device open the gate to the next."));
    }

    /** A section is done: its gate opens five seconds later, and the section blows up once Goldor has passed it. */
    private void sectionDone(int section) {
        pendingGates.add(new int[]{section, now + GATE_DELAY});
        pendingBlows.add(new int[]{section, now + GATE_DELAY + SECTION_BLOW_DELAY});
    }

    private void tickGoldor(ServerLevel level) {
        MinecraftServer server = level.getServer();
        for (int[] g : new ArrayList<>(pendingGates)) {
            if (now >= g[1]) {
                pendingGates.remove(g);
                openGate(level, g[0]);
                lastGateAt = now;
                // Hypixel's line as the gate goes down, then Goldor's answer (both from M7 POV videos).
                SimTerms.announceGate(server);
                if (deadAt < 0) {
                    SimBoss.say(server, "Goldor", "I will replace that gate with a stronger one!");
                }
            }
        }
        for (int[] b : new ArrayList<>(pendingBlows)) {
            if (now >= b[1]) {
                pendingBlows.remove(b);
                blowSection(level, b[0]);
            }
        }
        if (deadAt >= 0) {
            if (now - deadAt == DEAD_HOLD / 2) {
                // The second of Hypixel's two death lines (the wiki, Dungeons Guide's chat log).
                SimBoss.say(server, "Goldor", "Necron, forgive me.");
            }
            if (now - deadAt >= DEAD_HOLD) {
                startNecron(level);
            }
            return;
        }
        // The rest of Hypixel's opening, word for word (the spacing is APPROXIMATE).
        if (phaseTicks == 30) {
            SimBoss.say(server, "Goldor", "Little ants, plotting and scheming, thinking they are invincible…");
        } else if (phaseTicks == 60) {
            SimBoss.say(server, "Goldor", "I won't let you break the factory core, I gave my life to my Master.");
        } else if (phaseTicks == 90) {
            SimBoss.say(server, "Goldor", "No one matches me in close quarters.");
        }
        // His core lines, a couple of seconds apart after the first.
        if (coreAt >= 0 && now - coreAt == 30) {
            SimBoss.say(server, "Goldor", "But you have nowhere to hide anymore!");
        } else if (coreAt >= 0 && now - coreAt == 60) {
            SimBoss.say(server, "Goldor", "YOU ARE FACE TO FACE WITH GOLDOR!");
        }
        taunt(server, "Goldor", GOLDOR_TAUNTS);
        if (now >= nextFrenzyPulse) {
            nextFrenzyPulse = now + 20;
            goldorFrenzy(level);
        }
        if (now >= nextGreatsword) {
            nextGreatsword = now + GREATSWORD_EVERY - (master ? 40 : 0);
            List<ServerPlayer> in = p3Players(level);
            if (!in.isEmpty()) {
                ServerPlayer target = in.get(ThreadLocalRandom.current().nextInt(in.size()));
                shoot(ShotKind.GREATSWORD, body.position().add(0, 3, 0), target.position().add(0, 0.5, 0), 1.2,
                        SimHealth.byMode(GREATSWORD[0], GREATSWORD[1]));
                level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 2f, 0.6f);
            }
        }
        boolean coreOpen = SimTerms.coreOpen() || !SimTerms.isActive();
        if (!coreOpen) {
            int section = Math.max(1, Math.min(4, SimTerms.section()));
            ServerPlayer target = nearestIn(level, P3_SECTIONS[section - 1]);
            if (target != null) {
                glide(body, new Vec3(target.getX(), Math.max(108, target.getY() + 0.5), target.getZ()),
                        goldorSlowUntil > now ? GOLDOR_SLOWED : GOLDOR_SPEED);
            }
            aheadOfGate(level, section);
            if (deathTickBase >= 0 && (now - deathTickBase) % DEATH_TICK == 0) {
                deathTick(level, section);
            }
            contact(level);
            return;
        }
        if (health.invulnerable && phaseTicks > 0 && deadAt < 0 && body.distanceToSqr(GOLDOR_CORE_SPOT) > 1) {
            // The core is open: Goldor goes in to make his stand, and can be hurt from now on.
            body.snapTo(GOLDOR_CORE_SPOT.x, GOLDOR_CORE_SPOT.y, GOLDOR_CORE_SPOT.z, 180f, 0f);
            health.invulnerable = false;
            floorHp = 0;
            coreAt = now;
            nextSkulls = now + 20;
            // Hypixel's line, word for word (with its "…"), as Odin and Dungeons Guide match it.
            SimBoss.say(server, "Goldor", "You have done it, you destroyed the factory…");
            SimSurvival.setHome(BlockPos.containing(54.5, 115, 57.5));
        }
        ServerPlayer target = nearestIn(level, CORE);
        if (target != null) {
            glide(body, new Vec3(target.getX(), Math.max(114, target.getY() + 0.5), target.getZ()), 0.1);
        }
        coreSwords(level);
        // In the core he launches wither skulls at a fast rate (Goldor's page; the rate is APPROXIMATE).
        if (now >= nextSkulls) {
            List<ServerPlayer> in = new ArrayList<>();
            for (ServerPlayer p : players(level)) {
                if (CORE.contains(p.position())) {
                    in.add(p);
                }
            }
            if (!in.isEmpty()) {
                Vec3[] heads = heads();
                ServerPlayer p = in.get(ThreadLocalRandom.current().nextInt(in.size()));
                shoot(ShotKind.SKULL, heads[ThreadLocalRandom.current().nextInt(3)], p.position().add(0, 1, 0), 1.2, baseDamage());
            }
            nextSkulls = now + (master ? 8 : 10);
        }
        contact(level);
        if (health.hp <= 0) {
            SimBoss.say(server, "Goldor", "....");
            killBody(level);
            SimTerms.stopInArena(server);
        }
    }

    /**
     * Someone in a later section than the one being done (past a gate that is still shut, by a leap or a pearl):
     * Goldor's "What do you think you are doing there!" (the wiki), at most every {@link #GOLDOR_LINE_GAP} ticks.
     */
    private void aheadOfGate(ServerLevel level, int section) {
        if (now - goldorGateLineAt < GOLDOR_LINE_GAP) {
            return;
        }
        int open = section;
        for (int[] g : pendingGates) {
            // A finished section's gate is on its way down: the next section counts as reached.
            open = Math.max(open, g[0] + 1);
        }
        for (ServerPlayer p : players(level)) {
            Vec3 pos = p.position();
            boolean ahead = false;
            for (int s = 0; s < P3_SECTIONS.length; s++) {
                if (P3_SECTIONS[s].contains(pos)) {
                    if (s + 1 <= open) {
                        ahead = false;
                        break;
                    }
                    ahead = true;
                }
            }
            if (ahead && !CORE.contains(pos)) {
                goldorGateLineAt = now;
                SimBoss.say(level.getServer(), "Goldor", "What do you think you are doing there!");
                return;
            }
        }
    }

    private List<ServerPlayer> p3Players(ServerLevel level) {
        List<ServerPlayer> in = new ArrayList<>();
        for (ServerPlayer p : players(level)) {
            if (p.getY() > 100 && p.getY() < 160) {
                in.add(p);
            }
        }
        return in;
    }

    /** In the core, swords circle him and every few seconds close up to block attacks (APPROXIMATE timing). */
    boolean swordsGuarding() {
        return coreAt >= 0 && deadAt < 0 && (now - coreAt) % 120 < 40;
    }

    private void coreSwords(ServerLevel level) {
        if (now % 2 != 0) {
            return;
        }
        boolean guard = swordsGuarding();
        double r = guard ? 1.6 : 3.0;
        for (int i = 0; i < 4; i++) {
            double a = now * 0.15 + Math.PI / 2 * i;
            level.sendParticles(guard ? ParticleTypes.ENCHANTED_HIT : ParticleTypes.CRIT, body.getX() + Math.cos(a) * r,
                    body.getY() + 1.5, body.getZ() + Math.sin(a) * r, 3, 0.1, 0.4, 0.1, 0);
        }
    }

    /** Goldor's frenzy: everyone close to him takes a heavy hit once a second, in the sections and in the core. */
    private void goldorFrenzy(ServerLevel level) {
        circle(level, new DustParticleOptions(0xFFAA00, 1.4f), body.position(), GOLDOR_FRENZY_RADIUS);
        for (ServerPlayer p : players(level)) {
            if (p.distanceToSqr(body) < GOLDOR_FRENZY_RADIUS * GOLDOR_FRENZY_RADIUS) {
                hurtPlayer(level, p, SimHealth.byMode(GOLDOR_FRENZY[0], GOLDOR_FRENZY[1]), p.getX() - body.getX(),
                        p.getZ() - body.getZ(), 1.2, 0.5);
            }
        }
    }

    /**
     * A done section blowing up behind Goldor (Goldor's page): explosions through it, and anyone still in it who is
     * not in a later section or the core dies (APPROXIMATE: the wiki gives no number).
     */
    private void blowSection(ServerLevel level, int section) {
        AABB box = P3_SECTIONS[section - 1];
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 24; i++) {
            double x = box.minX + r.nextDouble() * (box.maxX - box.minX);
            double z = box.minZ + r.nextDouble() * (box.maxZ - box.minZ);
            double y = ground(level, (int) Math.floor(x), (int) Math.floor(z), 125, 105);
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 1, z, 1, 0, 0, 0, 0);
        }
        Vec3 c = box.getCenter();
        level.playSound(null, c.x, 118, c.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 6f, 0.6f);
        for (ServerPlayer p : players(level)) {
            Vec3 pos = p.position();
            if (!box.contains(pos) || CORE.contains(pos)) {
                continue;
            }
            boolean ahead = false;
            for (int s = section; s < P3_SECTIONS.length; s++) {
                ahead |= P3_SECTIONS[s].contains(pos);
            }
            if (!ahead) {
                hurtPlayerTrue(level, p, KILL_SHARE, 0.8);
            }
        }
        for (Vec3 t : new ArrayList<>(traps)) {
            if (box.contains(t)) {
                removeTrap(level, t);
            }
        }
    }

    /** The TNT traps: a few TNT blocks on each section's floor (APPROXIMATE spots), set off by a Greatsword. */
    private void placeTraps(ServerLevel level) {
        traps.clear();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (AABB box : P3_SECTIONS) {
            for (int i = 0, tries = 0; i < TRAPS_PER_SECTION && tries < 40; tries++) {
                int x = (int) Math.floor(box.minX + 3 + r.nextDouble() * (box.maxX - box.minX - 6));
                int z = (int) Math.floor(box.minZ + 3 + r.nextDouble() * (box.maxZ - box.minZ - 6));
                int y = (int) ground(level, x, z, 125, 105);
                BlockPos pos = new BlockPos(x, y, z);
                if (y <= 106 || !level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
                    continue;
                }
                changed.putIfAbsent(pos, level.getBlockState(pos));
                level.setBlock(pos, Blocks.TNT.defaultBlockState(), Block.UPDATE_CLIENTS);
                traps.add(Vec3.atBottomCenterOf(pos));
                i++;
            }
        }
    }

    private void removeTrap(ServerLevel level, Vec3 t) {
        traps.remove(t);
        BlockPos pos = BlockPos.containing(t);
        if (level.getBlockState(pos).is(Blocks.TNT)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** A Greatsword landing next to a trap sets it off. */
    private void setOffTraps(ServerLevel level, Vec3 at) {
        for (Vec3 t : new ArrayList<>(traps)) {
            if (t.distanceToSqr(at) > 4 * 4) {
                continue;
            }
            removeTrap(level, t);
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, t.x, t.y + 0.5, t.z, 1, 0, 0, 0, 0);
            level.playSound(null, t.x, t.y, t.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 3f, 0.9f);
            for (ServerPlayer p : players(level)) {
                if (p.position().distanceToSqr(t) < 5 * 5) {
                    hurtPlayer(level, p, SimHealth.byMode(TNT_TRAP[0], TNT_TRAP[1]), p.getX() - t.x, p.getZ() - t.z, 1.4, 0.8);
                }
            }
        }
    }

    /**
     * Goldor's death tick: every 60 ticks from Storm's death, everyone in P3 who is not in the section being done
     * (or the core) takes {@link #DEATH_TICK_SHARE} of his max health as true damage (APPROXIMATE size - the wiki
     * has no number). On Hypixel it is what punishes being left behind.
     */
    private void deathTick(ServerLevel level, int section) {
        for (ServerPlayer p : players(level)) {
            Vec3 pos = p.position();
            if (pos.y < 100 || pos.y > 160 || P3_SECTIONS[section - 1].contains(pos) || CORE.contains(pos)) {
                continue;
            }
            hurtPlayerTrue(level, p, DEATH_TICK_SHARE, 0.3);
            p.sendSystemMessage(Component.literal("Goldor's death tick! Keep up with the section.")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    /** Takes a section's gate down: the portcullis sheet and the barriers behind it. */
    private void openGate(ServerLevel level, int section) {
        int[] g = GATES[section - 1];
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = GATE_Y0; y <= GATE_Y1; y++) {
            for (int a = g[3]; a <= g[4]; a++) {
                BlockPos gate = g[0] == 1 ? new BlockPos(a, y, g[1]) : new BlockPos(g[1], y, a);
                BlockPos barrier = g[0] == 1 ? new BlockPos(a, y, g[2]) : new BlockPos(g[2], y, a);
                level.setBlock(gate, air, Block.UPDATE_ALL);
                if (level.getBlockState(barrier).is(Blocks.BARRIER)) {
                    level.setBlock(barrier, air, Block.UPDATE_ALL);
                }
            }
        }
        level.playSound(null, g[0] == 1 ? (g[3] + g[4]) / 2.0 : g[1], 120, g[0] == 1 ? g[1] : (g[3] + g[4]) / 2.0,
                SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 3f, 0.5f);
    }

    /** Opens the core: the gold blocks in the inner wall of section 4. */
    private void openCore(ServerLevel level) {
        for (int x = 51; x <= 57; x++) {
            for (int y = 113; y <= 126; y++) {
                for (int z = 53; z <= 55; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.GOLD_BLOCK)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        level.playSound(null, 54.5, 117, 54.5, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 3f, 0.7f);
    }

    /**
     * Rebuilds section 3's west edge. The fan copy of the room was cut at x 0 and Hypixel's section 3 reaches x -3:
     * three of its terminals and the Arrow Align frames are at x -2, against the wall at x -3. So the corridor's
     * first column (x 1) is copied over x -2..0 and a wall is put at x -3, which gives the terminals somewhere to
     * stand and the frames something to hang on. APPROXIMATE: the corridor's real west side is not known.
     */
    private static void patchSection3(ServerLevel level) {
        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        BlockPos.MutableBlockPos from = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos to = new BlockPos.MutableBlockPos();
        for (int z = 48; z <= 124; z++) {
            for (int y = 100; y <= 158; y++) {
                BlockState copy = level.getBlockState(from.set(1, y, z));
                for (int x = -2; x <= 0; x++) {
                    if (level.getBlockState(to.set(x, y, z)) != copy) {
                        level.setBlock(to, copy, Block.UPDATE_CLIENTS);
                    }
                }
                if (!level.getBlockState(to.set(-3, y, z)).is(Blocks.STONE_BRICKS)) {
                    level.setBlock(to, wall, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- P4 Necron

    private void startNecron(ServerLevel level) {
        removeBody();
        toPhase(Phase.NECRON);
        moveAll(level, P4_LANDING, 0f);
        newPool("Necron", NECRON_HEALTH);
        body = spawnWither(level, new Vec3(P4_CENTRE.x, P4_CENTRE.y + 4, P4_CENTRE.z + 20));
        // Hypixel's line, word for word: Necron's phase starts on it (and Goldor's timers stop).
        SimBoss.say(level.getServer(), "Necron", "You went further than any human before, congratulations.");
        act = 0;
        frenzies = 0;
        saidArgh = false;
        swordsAt = -1;
        shieldUntil = -1;
        stillUntil = -1;
        beamHitAt.clear();
    }

    private void tickNecron(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (deadAt >= 0) {
            if (master) {
                if (now - deadAt == DEAD_HOLD) {
                    removeBody();
                    // M7's line into P5, from M7 POV videos.
                    SimBoss.say(server, "Necron", "Let's make some space!");
                }
                if (now - deadAt >= DEAD_HOLD + 20) {
                    startWitherKing(level);
                }
            } else {
                // F7: Hypixel's other two last lines (the wiki, Dungeons Guide's chat log), then the boss is over.
                if (now - deadAt == DEAD_HOLD) {
                    SimBoss.say(server, "Necron", "I understand your words now, my master.");
                } else if (now - deadAt >= DEAD_HOLD * 2) {
                    SimBoss.say(server, "Necron", "The Catacombs... are no more.");
                    toPhase(Phase.DONE);
                    cleanUp(level);
                }
            }
            return;
        }
        if (phaseTicks == NECRON_LINE2) {
            SimBoss.say(server, "Necron", "I'm afraid, your journey ends now.");
        }
        if (phaseTicks == NECRON_DROP) {
            // His last intro line for a party that has beaten him before, and the platform goes.
            SimBoss.say(server, "Necron", "Goodbye.");
            dropPlatform(level);
        }
        if (phaseTicks == (NECRON_DROP + NECRON_OPEN) / 2) {
            SimBoss.say(server, "Necron", "That's a very impressive trick. I guess I'll have to handle this myself.");
        }
        if (phaseTicks == NECRON_OPEN) {
            health.invulnerable = false;
            // Damage stops at his first frenzy's threshold (80%), whatever the hit.
            floorHp = health.max * FRENZY_AT[0];
            nextSkulls = now + 20;
            nextLightning = now + 60;
            nextSwords = now + 160;
            nextFireball = now + 100;
            nextStill = now + 300;
            nextBeam = now + 400;
            nextShield = now + SHIELD_EVERY;
        }
        if (phaseTicks < NECRON_OPEN) {
            glide(body, new Vec3(P4_CENTRE.x, P4_CENTRE.y + 4, P4_CENTRE.z), 0.2);
            return;
        }
        // Held at the threshold by the window's cap, so compare the pool itself (the float fraction can sit a hair above).
        if ((act == 0 || act == 1) && frenzies < FRENZY_AT.length && health.hp <= health.max * FRENZY_AT[frenzies] + 1e-6) {
            startFrenzy(level);
        }
        if (act == 0) {
            taunt(server, "Necron", NECRON_TAUNTS);
        }
        switch (act) {
            case 0 -> necronAttacks(level);
            case 1 -> necronBeam(level);
            case 2 -> necronFrenzy(level);
            default -> {
                // After a frenzy: he gathers himself in the middle, out of reach for a moment, then stunned (open
                // again, down to the next frenzy's threshold: 5%, then after the last one 0), then fights on.
                glide(body, new Vec3(P4_CENTRE.x, P4_CENTRE.y + 3, P4_CENTRE.z), 0.3);
                if (health.invulnerable && now >= actAt - FRENZY_RESUME + FRENZY_IMMUNE) {
                    health.invulnerable = false;
                    floorHp = frenzies < FRENZY_AT.length ? health.max * FRENZY_AT[frenzies] : 0;
                }
                if (now >= actAt) {
                    act = 0;
                    nextSkulls = now + 10;
                    nextBeam = Math.max(nextBeam, now + 200);
                }
            }
        }
        necronShield(level);
        contact(level);
        if (health.hp <= 0) {
            // "ARGH!" comes before his death (F7 Extras arms its Necron death check on it), if no frenzy said it.
            argh(server);
            if (master) {
                // Since Hypixel's 2026 update Necron has no last line on M7; his death is a loud wither hurt
                // sound, which starts F7 Extras' relic spawn timer.
                level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 15f, 1f);
                deadAt = now;
                health.invulnerable = true;
                shots.clear();
            } else {
                // Hypixel's F7 last line: the end of the boss.
                SimBoss.say(server, "Necron", "All this, for nothing...");
                killBody(level);
            }
        }
    }

    /**
     * His ordinary attacks (Necron's page): wither skulls from all three heads (and now and then he stops and fires
     * much faster), four Diamond Swords launched at players, lightning, fireballs, and the beam every so often. The
     * timings are APPROXIMATE. The sim's old charge is gone.
     */
    private void necronAttacks(ServerLevel level) {
        boolean still = stillUntil > now;
        if (!still) {
            orbit += 0.02;
            glide(body, new Vec3(P4_CENTRE.x + Math.cos(orbit) * 18, P4_CENTRE.y + 3, P4_CENTRE.z + Math.sin(orbit) * 18), 0.35);
        } else {
            ServerPlayer t = nearest(level, body.position());
            if (t != null) {
                face(body, t.position());
            }
        }
        if (now >= nextStill) {
            stillUntil = now + 80;
            nextStill = now + 400;
        }
        if (now >= nextSkulls) {
            fireSkulls(level, 1.2, baseDamage());
            nextSkulls = now + (still ? 8 : 30);
        }
        if (now >= nextLightning) {
            List<ServerPlayer> ps = players(level);
            if (!ps.isEmpty()) {
                ServerPlayer p = ps.get(ThreadLocalRandom.current().nextInt(ps.size()));
                blasts.add(new Blast(p.position(), 3.0, now + 30, LIGHTNING_X * baseDamage(), 0xFFFF55, true));
            }
            nextLightning = now + 100;
        }
        if (now >= nextFireball) {
            ServerPlayer p = nearest(level, body.position());
            if (p != null) {
                shoot(ShotKind.FIREBALL, body.position().add(0, 3, 0), p.position().add(0, 0.5, 0), 0.9, baseDamage());
                level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, 0.8f);
            }
            nextFireball = now + 160;
        }
        if (swordsAt < 0 && now >= nextSwords) {
            swordsAt = now + 25;
            nextSwords = now + 240;
            if (!saidSwords) {
                // Hypixel's line while attacking with the swords (the wiki), the first time.
                saidSwords = true;
                SimBoss.say(level.getServer(), "Necron", "I ensure you, these blades are far sharper than Goldor's ever were.");
            }
        }
        if (swordsAt >= 0) {
            // The four swords hang round him, then fly.
            for (int i = 0; i < 4; i++) {
                Vec3 s = swordSpot(i);
                level.sendParticles(ParticleTypes.ENCHANTED_HIT, s.x, s.y, s.z, 2, 0.1, 0.5, 0.1, 0);
            }
            if (now >= swordsAt) {
                swordsAt = -1;
                List<ServerPlayer> ps = players(level);
                for (int i = 0; i < 4 && !ps.isEmpty(); i++) {
                    ServerPlayer p = ps.get(i % ps.size());
                    shoot(ShotKind.SWORD, swordSpot(i), p.position().add(0, 1, 0), 1.4, SWORD_X * baseDamage());
                }
                level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 2f, 1.2f);
            }
        }
        if (now >= nextBeam) {
            act = 1;
            actAt = now + BEAM_TICKS;
            swordsAt = -1;
            beamAngle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 4f, 0.5f);
        }
    }

    private Vec3 swordSpot(int i) {
        double a = Math.PI / 2 * i + now * 0.05;
        return body.position().add(Math.cos(a) * 2.5, 2.5, Math.sin(a) * 2.5);
    }

    /**
     * The beam: he flies up and four groups of horizontal beams turn round him for 15 s; touching one is 25% of max
     * health as true damage (Necron's page), at most once a second. The beams run at chest height over the platforms,
     * so a jump clears them. Turn speed and spread APPROXIMATE.
     */
    private void necronBeam(ServerLevel level) {
        glide(body, new Vec3(P4_CENTRE.x, P4_CENTRE.y + 6, P4_CENTRE.z), 0.4);
        beamAngle += 0.035;
        Vec3 hub = new Vec3(P4_CENTRE.x, P4_LANDING.y + 0.8, P4_CENTRE.z);
        DustParticleOptions dust = new DustParticleOptions(0xFF2200, 1.6f);
        for (int g = 0; g < 4; g++) {
            for (int k = 0; k < 2; k++) {
                double a = beamAngle + Math.PI / 2 * g + k * 0.09;
                Vec3 end = hub.add(Math.cos(a) * BEAM_LENGTH, 0, Math.sin(a) * BEAM_LENGTH);
                if (now % 2 == 0) {
                    beam(level, dust, hub, end, 1.0);
                }
                for (ServerPlayer p : players(level)) {
                    if (now >= beamHitAt.getOrDefault(p.getUUID(), 0)
                            && distanceToSegment(p.position().add(0, 0.9, 0), hub, end) < 1.0) {
                        beamHitAt.put(p.getUUID(), now + 20);
                        hurtPlayerTrue(level, p, NECRON_BEAM_SHARE, 0.2);
                    }
                }
            }
        }
        if (now >= actAt) {
            act = 0;
            nextBeam = now + 700;
            nextSkulls = now + 20;
        }
    }

    /** "ARGH!", once a fight. */
    private void argh(MinecraftServer server) {
        if (!saidArgh) {
            saidArgh = true;
            SimBoss.say(server, "Necron", "ARGH!");
        }
    }

    /** At 80% and 5% he goes to the middle, blinds everyone and starts the frenzy. */
    private void startFrenzy(ServerLevel level) {
        frenzies++;
        act = 2;
        swordsAt = -1;
        shieldUntil = -1;
        frenzyRadius = FRENZY_START_RADIUS;
        frenzyAt = now;
        // One of Hypixel's lines before a frenzy (the wiki).
        SimBoss.say(level.getServer(), "Necron", pick(NECRON_PRE_FRENZY));
        // His health holds at the threshold through the frenzy: hits shrink the ring instead ({@link #hit}).
        floorHp = health.hp;
        frenzyDamage = 0;
        body.snapTo(P4_CENTRE.x, P4_CENTRE.y, P4_CENTRE.z, body.getYRot(), 0f);
        for (ServerPlayer p : players(level)) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
        }
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 4f, 1f);
    }

    /**
     * The frenzy: explosions round him in a ring that grows while he is left alone and shrinks as he is hurt. When it
     * closes he breaks off a piece of the outer ring and fights on ten seconds later.
     */
    private void necronFrenzy(ServerLevel level) {
        body.snapTo(P4_CENTRE.x, P4_CENTRE.y, P4_CENTRE.z, body.getYRot() + 3f, 0f);
        // The ring grows while he is left alone and shrinks with the damage dealt to him (Necron's page).
        double lost = frenzyDamage;
        frenzyDamage = 0;
        frenzyRadius += FRENZY_GROWTH - lost * (2 * FRENZY_START_RADIUS) / (FRENZY_SHRINK_SHARE * health.max);
        frenzyRadius = Math.min(FRENZY_MAX_RADIUS, frenzyRadius);
        Vec3 floor = new Vec3(P4_CENTRE.x, P4_LANDING.y, P4_CENTRE.z);
        if (frenzyRadius <= 0) {
            // His cry when the frenzy fails (the wiki's "Frenzy fails"; M7 videos show "ARGH!" here).
            argh(level.getServer());
            breakRing(level);
            act = 3;
            actAt = now + FRENZY_RESUME;
            // "This takes 10 seconds" (Necron's page): he cannot be hurt until he fights on (APPROXIMATE that the
            // whole 10 s is invulnerable).
            health.invulnerable = true;
            return;
        }
        if (now % 3 == 0) {
            circle(level, ParticleTypes.FLAME, floor, frenzyRadius);
        }
        if (now - frenzyAt == 60) {
            // One of Hypixel's lines while in the frenzy (the wiki; when is APPROXIMATE).
            SimBoss.say(level.getServer(), "Necron", pick(NECRON_FRENZY));
        }
        if (now % 20 == 0) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 6; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * frenzyRadius;
                level.sendParticles(ParticleTypes.EXPLOSION, floor.x + Math.cos(a) * d, floor.y + 1, floor.z + Math.sin(a) * d,
                        1, 0, 0, 0, 0);
            }
            level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 3f, 0.8f);
            for (ServerPlayer p : players(level)) {
                double dx = p.getX() - floor.x, dz = p.getZ() - floor.z;
                if (dx * dx + dz * dz <= frenzyRadius * frenzyRadius) {
                    hurtPlayer(level, p, FRENZY_DAMAGE_X * baseDamage(), dx, dz, 1.0, 0.6);
                }
            }
        }
    }

    /** M7: now and then a shield that blocks every attack ({@link #hit}). */
    private void necronShield(ServerLevel level) {
        if (!master) {
            return;
        }
        if (act <= 1 && shieldUntil < 0 && now >= nextShield) {
            shieldUntil = now + SHIELD_TICKS;
            nextShield = now + SHIELD_EVERY;
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 3f, 0.6f);
        }
        if (shieldUntil >= 0 && now >= shieldUntil) {
            shieldUntil = -1;
        }
        if (shieldUntil > now && now % 3 == 0) {
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16, b = (now % 30) / 30.0 * Math.PI;
                level.sendParticles(ParticleTypes.END_ROD, body.getX() + Math.cos(a) * 2.2 * Math.sin(b),
                        body.getY() + 1.5 + Math.cos(b) * 2.2, body.getZ() + Math.sin(a) * 2.2 * Math.sin(b), 1, 0, 0, 0, 0);
            }
        }
    }

    /**
     * The intro: the platform they land on is destroyed and they drop into the lava below (which throws them back up
     * and, in the sim, does no damage). Only columns with lava under them are broken, so the sim never drops anyone
     * into the void; the radius is APPROXIMATE.
     */
    private void dropPlatform(ServerLevel level) {
        int cx = (int) Math.floor(P4_LANDING.x), cz = (int) Math.floor(P4_LANDING.z), top = (int) P4_LANDING.y - 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 18 || !lavaBelow(level, cx + dx, top - 1, cz + dz)) {
                    continue;
                }
                for (int y = top; y > top - 3; y--) {
                    breakBlock(level, m.set(cx + dx, y, cz + dz));
                }
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, P4_LANDING.x, P4_LANDING.y, P4_LANDING.z, 1, 0, 0, 0, 0);
        level.playSound(null, P4_LANDING.x, P4_LANDING.y, P4_LANDING.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 4f, 0.7f);
    }

    /** A piece of the outer ring goes when a frenzy closes: a 45-degree wedge of the platforms (APPROXIMATE size). */
    private void breakRing(ServerLevel level) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double mid = r.nextDouble() * Math.PI * 2;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -32; dx <= 32; dx++) {
            for (int dz = -32; dz <= 32; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                double off = Math.abs(Math.atan2(Math.sin(Math.atan2(dz, dx) - mid), Math.cos(Math.atan2(dz, dx) - mid)));
                if (d < 14 || d > 32 || off > Math.PI / 8) {
                    continue;
                }
                int x = (int) Math.floor(P4_CENTRE.x) + dx, z = (int) Math.floor(P4_CENTRE.z) + dz;
                for (int y = 72; y > 55; y--) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (!s.getFluidState().isEmpty()) {
                        break;
                    }
                    if (!s.isAir()) {
                        for (int k = 0; k < 3; k++) {
                            breakBlock(level, m.set(x, y - k, z));
                        }
                        break;
                    }
                }
                if (r.nextInt(40) == 0) {
                    level.sendParticles(ParticleTypes.EXPLOSION, x, P4_LANDING.y, z, 1, 0, 0, 0, 0);
                }
            }
        }
        level.playSound(null, P4_CENTRE.x, P4_CENTRE.y, P4_CENTRE.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 6f, 0.5f);
    }

    private static boolean lavaBelow(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int yy = y; yy > y - 12; yy--) {
            if (level.getBlockState(m.set(x, yy, z)).is(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /** Breaks a solid block, remembering it for {@link #cleanUp}; no neighbour updates, so lava does not flow in. */
    private void breakBlock(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir() || !s.getFluidState().isEmpty()) {
            return;
        }
        changed.putIfAbsent(pos.immutable(), s);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    // ---------------------------------------------------------------------------------------------- P5

    private void startWitherKing(ServerLevel level) {
        removeBody();
        toPhase(Phase.WITHER_KING);
        bodyName = "Wither King";
        moveAll(level, P5_LANDING, 0f);
        dragons = new SimNecronDragons(this, level);
    }

    // ---------------------------------------------------------------------------------------------- shared

    /** A wither's pool on this floor ({F7, M7} health), invulnerable until its phase opens it. */
    private void newPool(String name, double[] pool) {
        bodyName = name;
        health = new SimBoss.Health(SimHealth.byMode(pool[0], pool[1]));
        health.invulnerable = true;
        floorHp = 0;
        deadAt = -1;
        stunEnd = -1;
        laserAt = -1;
    }

    /** The current wither's base damage on this floor (Necron's own in P4 and after), before Defense. */
    double baseDamage() {
        double[] d = phase == Phase.NECRON || phase == Phase.WITHER_KING ? NECRON_DAMAGE : WITHER_DAMAGE;
        return SimHealth.byMode(d[0], d[1]);
    }

    /** Maxor's, Storm's and Goldor's base damage on this floor (the M7 dragons hit for it too). */
    static double witherDamage() {
        return SimHealth.byMode(WITHER_DAMAGE[0], WITHER_DAMAGE[1]);
    }

    /** The dying wither: empty bar kept up for {@link #DEAD_HOLD} ticks, then the next phase. */
    private void killBody(ServerLevel level) {
        deadAt = now;
        health.hp = 0;
        health.invulnerable = true;
        blasts.clear();
        if (body != null) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, body.getX(), body.getY() + 1.5, body.getZ(), 1, 0, 0, 0, 0);
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 2f, 1f);
            removeBody();
        }
    }

    private void removeBody() {
        if (body != null) {
            body.discard();
            body = null;
        }
    }

    WitherBody spawnWither(ServerLevel level, Vec3 at) {
        WitherBody w = new WitherBody(level);
        w.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        w.setHealth(1000);
        w.setNoAi(true);
        w.setNoGravity(true);
        w.setPersistenceRequired();
        w.setCustomNameVisible(true);
        w.addTag(TAG);
        w.snapTo(at.x, at.y, at.z, 0f, 0f);
        level.addFreshEntity(w);
        return w;
    }

    private void nameTag() {
        if (body == null) {
            return;
        }
        Component tag = Component.literal("﴾ " + bodyName + " ﴿ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(health.invulnerable ? "???" : SimBoss.shortHealth(health.hp))
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("❤").withStyle(ChatFormatting.RED)));
        body.setCustomName(tag);
    }

    /** Moves an entity a step toward a point, facing where it goes. */
    static void glide(Entity e, Vec3 to, double speed) {
        if (e == null) {
            return;
        }
        Vec3 from = e.position();
        Vec3 d = to.subtract(from);
        double len = d.length();
        Vec3 at = len <= speed ? to : from.add(d.scale(speed / len));
        float yaw = len < 0.01 ? e.getYRot() : (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
        e.snapTo(at.x, at.y, at.z, yaw, 0f);
        e.setYHeadRot(yaw);
        if (e instanceof LivingEntity le) {
            le.setYBodyRot(yaw);
        }
    }

    /** Floats the body toward the closest player at height {@code y}, stopping {@code keep} blocks short. */
    private void chase(Entity e, double y, double speed, double keep) {
        if (e == null) {
            return;
        }
        ServerPlayer target = nearest(level, e.position());
        if (target == null) {
            return;
        }
        double dx = target.getX() - e.getX();
        double dz = target.getZ() - e.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= keep) {
            glide(e, new Vec3(e.getX(), y, e.getZ()), speed);
            return;
        }
        glide(e, new Vec3(target.getX() - dx / d * keep, y, target.getZ() - dz / d * keep), speed);
    }

    /**
     * A hit on anyone touching the body (once a second): Maxor's Frenzy and Storm's Static Field at the videos'
     * numbers, the wither's base damage otherwise.
     */
    private void contact(ServerLevel level) {
        if (body == null || --contactCooldown > 0) {
            return;
        }
        double damage = switch (phase) {
            case MAXOR -> SimHealth.byMode(MAXOR_FRENZY[0], MAXOR_FRENZY[1]);
            case STORM -> SimHealth.byMode(STORM_STATIC_FIELD[0], STORM_STATIC_FIELD[1]);
            default -> baseDamage();
        };
        for (ServerPlayer p : players(level)) {
            if (p.distanceToSqr(body) < 2.6 * 2.6) {
                hurtPlayer(level, p, damage, p.getX() - body.getX(), p.getZ() - body.getZ(), 1.0, 0.5);
                contactCooldown = 20;
            }
        }
    }

    /** Marks one blast; {@code damage} is Hypixel damage before Defense. */
    void blast(Vec3 at, double radius, int warn, double damage, int colour) {
        blasts.add(new Blast(at, radius, now + warn, damage, colour, false));
    }

    /** Marks one blast that goes off as a lightning strike. */
    void lightningBlast(Vec3 at, double radius, int warn, double damage) {
        blasts.add(new Blast(at, radius, now + warn, damage, 0xFFFF55, true));
    }

    // ---------------------------------------------------------------------------------------------- shots

    /** The current wither's three heads: the middle one, and one to each side. */
    private Vec3[] heads() {
        double yaw = Math.toRadians(body.getYRot());
        double rx = Math.cos(yaw), rz = Math.sin(yaw);
        Vec3 c = body.position().add(0, 3.0, 0);
        return new Vec3[]{c, c.add(rx * 1.3, -0.4, rz * 1.3), c.add(-rx * 1.3, -0.4, -rz * 1.3)};
    }

    /** A wither skull from each of the three heads, each at the next player round (all at one when alone). */
    private void fireSkulls(ServerLevel level, double speed, double damage) {
        List<ServerPlayer> ps = players(level);
        if (body == null || ps.isEmpty()) {
            return;
        }
        Vec3[] heads = heads();
        for (int i = 0; i < heads.length; i++) {
            shoot(ShotKind.SKULL, heads[i], ps.get(i % ps.size()).position().add(0, 1, 0), speed, damage);
        }
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.5f, 1f);
    }

    /** Turns a body to face a point without moving it. */
    private static void face(Entity e, Vec3 at) {
        double dx = at.x - e.getX(), dz = at.z - e.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        e.snapTo(e.getX(), e.getY(), e.getZ(), yaw, 0f);
        e.setYHeadRot(yaw);
        if (e instanceof LivingEntity le) {
            le.setYBodyRot(yaw);
        }
    }

    /** Fires a shot from {@code from} at {@code to}; {@code damage} is Hypixel damage before Defense. */
    void shoot(ShotKind kind, Vec3 from, Vec3 to, double speed, double damage) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1e-3) {
            return;
        }
        shots.add(new Shot(kind, from, d.scale(speed / len), now + (int) Math.ceil(len / speed) + 40, damage));
    }

    private void tickShots(ServerLevel level) {
        if (shots.isEmpty()) {
            return;
        }
        List<Shot> done = new ArrayList<>();
        for (Shot s : new ArrayList<>(shots)) {
            Vec3 next = s.at.add(s.vel);
            trail(level, s, next);
            ServerPlayer hit = null;
            for (ServerPlayer p : players(level)) {
                if (distanceToSegment(p.position().add(0, 0.9, 0), s.at, next) < 1.2) {
                    hit = p;
                    break;
                }
            }
            s.at = next;
            if (hit != null || solid(level, BlockPos.containing(next)) || now >= s.dies) {
                done.add(s);
                land(level, s, hit);
            }
        }
        shots.removeAll(done);
    }

    private static void trail(ServerLevel level, Shot s, Vec3 next) {
        Vec3 a = s.at;
        switch (s.kind) {
            case SKULL -> level.sendParticles(ParticleTypes.SMOKE, a.x, a.y, a.z, 3, 0.1, 0.1, 0.1, 0);
            case SWORD -> beam(level, ParticleTypes.ENCHANTED_HIT, a, next, 0.5);
            case GREATSWORD -> {
                beam(level, ParticleTypes.CRIT, a, next, 0.5);
                level.sendParticles(new DustParticleOptions(0xFFAA00, 1.5f), a.x, a.y, a.z, 2, 0.2, 0.2, 0.2, 0);
            }
            case BOLT -> {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, a.x, a.y, a.z, 3, 0.15, 0.15, 0.15, 0.02);
                level.sendParticles(ParticleTypes.FLAME, a.x, a.y, a.z, 1, 0.1, 0.1, 0.1, 0);
            }
            default -> {
                level.sendParticles(ParticleTypes.FLAME, a.x, a.y, a.z, 3, 0.2, 0.2, 0.2, 0.01);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, a.x, a.y, a.z, 1, 0.1, 0.1, 0.1, 0);
            }
        }
    }

    /** Where a shot ends: on a player ({@code hit}), a block, or out of range. */
    private void land(ServerLevel level, Shot s, ServerPlayer hit) {
        Vec3 at = hit != null ? hit.position() : s.at;
        switch (s.kind) {
            case SKULL, SWORD -> {
                if (hit != null) {
                    hurtPlayer(level, hit, s.damage, s.vel.x, s.vel.z, s.kind == ShotKind.SKULL ? 1.2 : 0.9, 0.4);
                }
                level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 1, at.z, 6, 0.3, 0.3, 0.3, 0.02);
            }
            case GREATSWORD -> {
                if (hit != null) {
                    hurtPlayer(level, hit, s.damage, s.vel.x, s.vel.z, 1.4, 0.6);
                }
                level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.5f, 0.6f);
                setOffTraps(level, at);
            }
            case BOLT -> {
                // Storm's fireball: an area of lightning where it lands.
                lightning(level, at);
                aoe(level, at, 4.0, s.damage);
            }
            case FIREBALL -> {
                level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 2, 0.5, 0.3, 0.5, 0);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1f, 1.2f);
                aoe(level, at, 3.0, s.damage);
            }
            default -> {
                // The Flame Dragon's fireball: a blast, and the ground left burning.
                level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 2, 0.5, 0.3, 0.5, 0);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1f, 1.2f);
                aoe(level, at, 3.0, s.damage);
                double y = ground(level, (int) Math.floor(at.x), (int) Math.floor(at.z), (int) at.y + 2, (int) at.y - 12);
                zone(new Vec3(at.x, y, at.z), 3.0, 100, false, s.damage * 0.25);
            }
        }
    }

    private void aoe(ServerLevel level, Vec3 at, double radius, double damage) {
        for (ServerPlayer p : players(level)) {
            if (p.position().distanceToSqr(at) <= radius * radius) {
                hurtPlayer(level, p, damage, p.getX() - at.x, p.getZ() - at.z, 0.8, 0.5);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- ground

    /**
     * Ground left burning (once a second, {@code damage} each) or frozen: the Ice Dragon's ice holds whoever walks
     * onto it in place for two seconds (APPROXIMATE).
     */
    void zone(Vec3 at, double radius, int ticks, boolean ice, double damage) {
        zones.add(new Zone(at, radius, now + ticks, ice, damage));
    }

    private void tickZones(ServerLevel level) {
        if (zones.isEmpty()) {
            return;
        }
        zones.removeIf(z -> now >= z.until());
        for (Zone z : zones) {
            if (now % 4 == 0) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 6; i++) {
                    double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * z.radius();
                    level.sendParticles(z.ice() ? ParticleTypes.SNOWFLAKE : ParticleTypes.FLAME,
                            z.at().x + Math.cos(a) * d, z.at().y + 0.1, z.at().z + Math.sin(a) * d, 1, 0, 0.05, 0, 0.01);
                }
            }
            for (ServerPlayer p : players(level)) {
                double dx = p.getX() - z.at().x, dz = p.getZ() - z.at().z;
                if (dx * dx + dz * dz > z.radius() * z.radius() || Math.abs(p.getY() - z.at().y) > 2) {
                    continue;
                }
                if (z.ice()) {
                    if (now >= frozenUntil.getOrDefault(p.getUUID(), 0)) {
                        frozenUntil.put(p.getUUID(), now + 60);
                        p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 9, false, false));
                        p.setDeltaMovement(Vec3.ZERO);
                        p.syncVelocity = true;
                        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_PLACE, SoundSource.HOSTILE, 1f, 0.6f);
                    }
                } else if (now % 20 == 0) {
                    hurtPlayer(level, p, z.damage(), 0, 0, 0, 0);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- adds

    /**
     * One add: a zombie (or a skeleton archer) with a pool. {@code health} and {@code hit} are {F7, M7}; a name with
     * a wiki table in {@link SimHealth} takes that instead, and a null {@code hit} keeps the table's.
     */
    private Mob spawnAdd(ServerLevel level, String name, Vec3 at, boolean archer, double[] health, double[] hit,
                         ItemStack head, ItemStack hand) {
        Mob mob = archer ? new AddArcher(McEntities.SKELETON, level) : new AddZombie(level);
        if (head != null) {
            mob.setItemSlot(EquipmentSlot.HEAD, head);
            mob.setDropChance(EquipmentSlot.HEAD, 0f);
        }
        if (hand != null) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, hand);
            mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
        }
        mob.setPersistenceRequired();
        var range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(40);
        }
        var reinforcements = mob.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
        if (reinforcements != null) {
            reinforcements.setBaseValue(0);
        }
        if (mob instanceof AddArcher a) {
            a.reassessWeaponGoal();
        }
        mob.addTag(TAG);
        mob.snapTo(at.x, at.y, at.z, 0f, 0f);
        if (!level.addFreshEntity(mob)) {
            return null;
        }
        SimHealth.Pool pool = SimHealth.register(mob, SimHealth.byMode(health[0], health[1]), name, false, null);
        if (hit != null && pool != null) {
            pool.hit = SimHealth.byMode(hit[0], hit[1]);
        }
        ServerPlayer target = nearest(level, mob.position());
        if (target != null) {
            mob.setTarget(target);
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 10, 0.3, 0.6, 0.3, 0.02);
        adds.add(mob);
        return mob;
    }

    /** Drops the dead, keeps the living on a player, and lets the Withermancers' skulls fly at anyone close. */
    private void tickAdds(ServerLevel level) {
        if (adds.isEmpty()) {
            return;
        }
        adds.removeIf(m -> !m.isAlive() || m.isRemoved());
        mancers.removeIf(m -> !m.isAlive() || m.isRemoved());
        if (now % 20 == 0) {
            for (Mob m : adds) {
                if (m.getTarget() == null || !m.getTarget().isAlive()) {
                    ServerPlayer t = nearest(level, m.position());
                    if (t != null) {
                        m.setTarget(t);
                    }
                }
            }
        }
        if (now % 40 == 0) {
            for (Mob m : mancers) {
                ServerPlayer t = nearest(level, m.position());
                if (t != null && t.distanceToSqr(m) < 10 * 10) {
                    // A Crypt Wither Skull, at the M7 videos' number.
                    shoot(ShotKind.SKULL, m.position().add(0, 2.2, 0), t.position().add(0, 1, 0), 0.8,
                            SimHealth.byMode(MANCER_SKULL[0], MANCER_SKULL[1]));
                }
            }
        }
    }

    private void clearAdds() {
        for (Mob m : adds) {
            m.discard();
        }
        adds.clear();
        mancers.clear();
    }

    /** A melee add (Wither Miner, Withermancer, Shadow Assassin): a zombie that never burns or despawns. */
    private static final class AddZombie extends Zombie {
        AddZombie(Level level) {
            super(level);
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
        public boolean fireImmune() {
            return true;
        }
    }

    /** A Wither Guard: a skeleton with a bow whose arrows hit for the floor, like the room skeletons. */
    private static final class AddArcher extends Skeleton {
        AddArcher(EntityType<? extends Skeleton> type, Level level) {
            super(type, level);
        }

        @Override
        protected net.minecraft.world.entity.projectile.arrow.AbstractArrow getArrow(ItemStack arrow, float power,
                                                                                    ItemStack weapon) {
            var shot = super.getArrow(arrow, power, weapon);
            shot.setBaseDamage(getAttributeValue(Attributes.ATTACK_DAMAGE) / 2.0);
            return shot;
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public boolean fireImmune() {
            return true;
        }
    }

    private void tickBlasts(ServerLevel level) {
        if (blasts.isEmpty()) {
            return;
        }
        List<Blast> done = new ArrayList<>();
        for (Blast b : blasts) {
            if (b.goesOff() > now) {
                if (now % 3 == 0) {
                    circle(level, new DustParticleOptions(b.colour(), 1.2f), b.at(), b.radius());
                }
                continue;
            }
            done.add(b);
            if (b.lightning()) {
                lightning(level, b.at());
            } else {
                level.sendParticles(ParticleTypes.EXPLOSION, b.at().x, b.at().y + 0.5, b.at().z, 2, 0.5, 0.3, 0.5, 0);
                level.playSound(null, b.at().x, b.at().y, b.at().z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1f, 1.2f);
            }
            for (ServerPlayer p : players(level)) {
                double dx = p.getX() - b.at().x;
                double dz = p.getZ() - b.at().z;
                if (dx * dx + dz * dz <= b.radius() * b.radius() && Math.abs(p.getY() - b.at().y) < 3) {
                    hurtPlayer(level, p, b.damage(), dx, dz, 0.6, 0.5);
                }
            }
        }
        blasts.removeAll(done);
    }

    static void circle(ServerLevel level, ParticleOptions particle, Vec3 at, double r) {
        int n = Math.max(12, (int) (r * 6));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            level.sendParticles(particle, at.x + Math.cos(a) * r, at.y + 0.1, at.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    static void beam(ServerLevel level, ParticleOptions particle, Vec3 from, Vec3 to, double step) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        for (double s = 0; s <= len; s += step) {
            Vec3 p = from.add(d.scale(s / len));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    /** A lightning bolt for show (no fire, no vanilla damage: the hit is the fight's). */
    static void lightning(ServerLevel level, Vec3 at) {
        LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, level);
        bolt.setVisualOnly(true);
        bolt.snapTo(at.x, at.y, at.z);
        level.addFreshEntity(bolt);
    }

    /** True damage of {@code share} of the player's max health (Defense ignored), plus a lift. */
    static void hurtPlayerTrue(ServerLevel level, ServerPlayer p, double share, double lift) {
        SimVitals.dealMaxHealthShare(level, p, level.damageSources().magic(), share, true);
        if (lift > 0) {
            p.setDeltaMovement(p.getDeltaMovement().x, lift, p.getDeltaMovement().z);
            p.syncVelocity = true;
        }
    }

    /** Damage plus knockback: {@code damage} is Hypixel damage before Defense ({@link SimVitals#dealHypixel}). */
    static void hurtPlayer(ServerLevel level, ServerPlayer p, double damage, double dx, double dz, double push, double lift) {
        DamageSource src = level.damageSources().magic();
        SimVitals.dealHypixel(level, p, src, damage, false);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (push <= 0 && lift <= 0) {
            return;
        }
        if (len < 0.01) {
            double a = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
            len = 1;
        }
        p.setDeltaMovement(dx / len * push, lift, dz / len * push);
        p.syncVelocity = true;
    }

    /**
     * The room's lava throws you back up rather than burning you, as Hypixel's boss lava does (the sim's lava does
     * no damage at all, by request).
     */
    private void lavaBounce(ServerLevel level) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (!p.isInLava() || !arena.contains(p.getX(), p.getY(), p.getZ())) {
                continue;
            }
            Integer until = bounceUntil.get(p.getUUID());
            if (until != null && now < until) {
                continue;
            }
            bounceUntil.put(p.getUUID(), now + 10);
            p.clearFire();
            p.setDeltaMovement(p.getDeltaMovement().x * 0.5, 1.4, p.getDeltaMovement().z * 0.5);
            p.syncVelocity = true;
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LAVA_POP, SoundSource.PLAYERS, 1f, 1f);
        }
    }

    /** The living players in the room (ghosts and spectators left out). */
    List<ServerPlayer> players(ServerLevel level) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (!p.isSpectator() && !SimDeath.isGhost(p) && p.isAlive() && arena.contains(p.getX(), p.getY(), p.getZ())) {
                out.add(p);
            }
        }
        return out;
    }

    ServerPlayer nearest(ServerLevel level, Vec3 from) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : players(level)) {
            double d = p.position().distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private ServerPlayer nearestIn(ServerLevel level, AABB box) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : players(level)) {
            double d = body == null ? 0 : p.distanceToSqr(body);
            if (box.contains(p.position()) && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Every player (ghosts too) to the start of the next phase, which is also where death sends him now. */
    private void moveAll(ServerLevel level, Vec3 to, float yaw) {
        SimSurvival.setHome(BlockPos.containing(to));
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            p.teleportTo(level, to.x, to.y, to.z, Set.of(), yaw, 0f, false);
            p.fallDistance = 0;
            p.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** The first solid block's top going down from {@code fromY}, or {@code minY}. */
    static double ground(ServerLevel level, int x, int z, int fromY, int minY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = fromY; y > minY; y--) {
            if (!level.getBlockState(m.set(x, y, z)).isAir() && level.getBlockState(m.set(x, y + 1, z)).isAir()) {
                return y + 1;
            }
        }
        return minY;
    }

    /** A click on one of the fight's entities (a crystal to pick up, a relic). */
    private void touch(ServerPlayer p, Entity e) {
        if (phase == Phase.MAXOR && e instanceof EndCrystal c && pickups.contains(c)
                && !carrying.containsKey(p.getUUID()) && p.distanceToSqr(c) < 5 * 5) {
            pickCrystal((ServerLevel) p.level(), p, c);
        } else if (phase == Phase.WITHER_KING && dragons != null) {
            dragons.touch(p, e);
        }
    }

    /** Puts an item in the player's last hotbar slot (Hypixel's spot for a crystal or relic), keeping what was there. */
    void carry(ServerPlayer p, String kind, ItemStack stack) {
        savedSlot.putIfAbsent(p.getUUID(), p.getInventory().getItem(8).copy());
        carrying.put(p.getUUID(), kind);
        p.getInventory().setItem(8, stack);
    }

    /** What the player carries ("crystal", a relic's colour), or null. */
    String carried(ServerPlayer p) {
        return carrying.get(p.getUUID());
    }

    /** Gives back the player's last hotbar slot. */
    void uncarry(ServerPlayer p) {
        carrying.remove(p.getUUID());
        ItemStack old = savedSlot.remove(p.getUUID());
        if (old != null) {
            p.getInventory().setItem(8, old);
        }
    }

    private void clearCarried(ServerLevel level) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            uncarry(p);
        }
        for (EndCrystal c : pickups) {
            c.discard();
        }
        pickups.clear();
        for (int i = 0; i < placed.length; i++) {
            if (placed[i] != null) {
                placed[i].discard();
                placed[i] = null;
            }
        }
    }

    static void broadcast(MinecraftServer server, Component line) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
        }
    }

    /** A subtitle (with an empty title, as Hypixel sends them) to everyone. */
    static void subtitleAll(MinecraftServer server, Component subtitle) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 40, 10));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
            p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    private static void title(ServerPlayer p, Component title) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 5));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    int now() {
        return now;
    }

    // ---------------------------------------------------------------------------------------------- game test

    /** The phase being fought ("MAXOR" to "WITHER_KING", "DONE"), for Sky2MSimF7GameTest. */
    String phaseName() {
        return phase.name();
    }

    /** The current wither's body, or null between phases, for the game test's scripted hits. */
    LivingEntity bodyForTest() {
        return body;
    }

    /**
     * The game test's stand-in for each phase's mechanic (the laser, a crush, the terminals): the current wither can
     * be hurt now. Necron opens by himself after his speech, so his phase is left alone; Goldor's terminals are
     * ended, which opens the core as finishing them would.
     */
    void openForTest() {
        if (phase == Phase.GOLDOR && SimTerms.isActive() && level != null) {
            SimTerms.stopInArena(level.getServer());
        }
        if (phase == Phase.MAXOR || phase == Phase.STORM) {
            health.invulnerable = false;
            floorHp = 0;
        }
    }

    /** The current pool, {hp, max}, for the game test's window checks. */
    double[] healthForTest() {
        return new double[]{health.hp, health.max};
    }

    /** Fires the Energy Laser with Maxor in it, as two placed crystals and a lured Maxor would. */
    void laserForTest() {
        if (phase == Phase.MAXOR && body != null && level != null) {
            body.snapTo(LASER.x, LASER.y + 5, LASER.z, 0f, 0f);
            fireLaser(level);
        }
    }

    /** Drops the next working crusher on Storm, as a manned pad with Storm over it would. */
    void crushForTest() {
        if (phase != Phase.STORM || body == null || level == null) {
            return;
        }
        for (Pillar pl : pillars) {
            if (!pl.broken && !pl.used) {
                crush(level, pl);
                return;
            }
        }
    }

    /** Finishes a P3 section, as its last terminal, lever or device would. */
    void sectionDoneForTest(int section) {
        sectionDone(section);
    }

    /** When the last gate went down, or -1. */
    int lastGateAtForTest() {
        return lastGateAt;
    }

    /**
     * A boss wither's body: no AI (the fight moves it), no vanilla boss bar (SimBoss shows the fight's), no
     * skulls or block breaking, never despawns.
     */
    static final class WitherBody extends WitherBoss {
        WitherBody(Level level) {
            super(EntityTypes.WITHER, level);
        }

        @Override
        public void startSeenByPlayer(ServerPlayer player) {
        }

        @Override
        public void stopSeenByPlayer(ServerPlayer player) {
        }

        @Override
        protected void customServerAiStep(ServerLevel level) {
        }

        @Override
        public void checkDespawn() {
        }
    }
}
