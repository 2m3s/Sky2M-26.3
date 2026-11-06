package com.epic60869.sky2m.sim.roomsim;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floor 5's boss, Livid, in her ring.
 *
 * <p>The fight as it goes on Hypixel (https://hypixelskyblock.minecraft.wiki/w/Livid):
 * <ul>
 * <li><b>The opening.</b> Her first line (Hypixel's exact words: Boss Phases, the Livid Invulnerability HUD and
 * Rag Axe all start on it), then 340 ticks in which nothing can hurt her - the same 340 the HUD counts down.</li>
 * <li><b>The split.</b> After 16 seconds she blinds everyone for a second and becomes nine Livids, one of each
 * colour (Vendetta, Crossed, Arcade, Smile, Hockey, Doctor, Frog, Scream, Purple), and the wool block above the
 * ring and the stained glass round it, red until then, turn the REAL one's colour. Same on F5 and M5.</li>
 * <li><b>Every Livid is a fighter.</b> Each clone has the real one's health and its own attack on top of its
 * swing: Vendetta teleports and throws a dagger, Crossed blinds its target, Hockey charges, Doctor throws a Splash
 * Potion of Harming (true damage), Frog poisons, Smile throws a Splash Potion of Slowness, Scream lets out a ring
 * of true damage that knocks everyone near it back, Purple leeches health, and Arcade moves erratically and shoots
 * a bow. The real one, whichever colour she is, fights with her colour's attack too.</li>
 * <li><b>Killing a clone gets you nothing.</b> It regenerates to full and heals the clones near it by 25% of their
 * health; in Master Mode every clone death also makes all the Livids hit harder. Only the real one's death ends
 * the fight.</li>
 * </ul>
 *
 * <h2>What the mod's Livid features read, reproduced</h2>
 * The Livids are what Hypixel's are: NPC players (fake {@link ServerPlayer}s that are never in the player list,
 * with a version-3 UUID rather than a real player's version 4) named "Hockey Livid", "Smile Livid" and so on,
 * each under an armour stand reading "﴾ Livid 7M❤ ﴿" in its colour. That is exactly what Livid Finder sorts
 * by name, and what its Hide Wrong Livids hides by the stand's colour. The wool is the block Livid Finder
 * watches ({@code (6, 109, 43)} in the arena's own coordinates, red until the split); in the sim it sits at
 * {@link #woolCue()} instead, because the arena is pasted a thousand blocks away from Hypixel's coordinates.
 *
 * <p>Her death line and the clones' "wrong one" lines are Hypixel's word for word; the two lines before the split
 * are the sim's own placing (the second is one of Hypixel's, from the end of her fight).
 */
public final class SimLivid extends SimBoss.Fight {

    /**
     * Livid's health and base damage, the real one's and every clone's alike: 7,000,000 and 9,360 on F5,
     * 600,000,000 and 216,000 on M5 (https://hypixelskyblock.minecraft.wiki/w/Livid, Variants table; the damage is
     * the 2026 value after the cut from 10,400 / 240,000). A Livid's swing deals it, before the player's Defense.
     * The wiki gives no numbers for the clones' own attacks, so those are shares of it (APPROXIMATE, see each).
     */
    private static double lividDamage() {
        return SimHealth.byMode(9_360, 216_000);
    }

    /** Hypixel's: 340 ticks from her first line until she can be damaged ({@code LividTimer}, Odin). */
    private static final int INVULNERABLE_TICKS = 340;
    /** The wiki's "after 16s" she splits, the wool turns and everyone is blinded. */
    private static final int SPLIT_TICK = 320;
    /** The wiki's one second of blindness at the split, on every floor. */
    private static final int BLIND_TICKS = 20;
    /** The wiki's 25% of max health a clone's death gives the clones near it. */
    private static final double CLONE_HEAL = 0.25;
    /** How near "nearby" is for that heal (APPROXIMATE: the wiki gives no distance). */
    private static final double HEAL_RADIUS = 10;
    /** Master Mode's damage boost per clone death (APPROXIMATE: the wiki gives no amount). */
    private static final double MASTER_DEATH_BUFF = 0.10;
    /**
     * Every Livid's 100 Defense (the wiki's), so a player's hit lands at 100 / (100 + 100) of itself. The sim's boss
     * pools take hits with no Defense factor ({@link SimBoss#onDamage}), so it is applied here, as Thorn's Spirit Bats'.
     */
    private static final double DAMAGE_TAKEN = 100.0 / (100.0 + 100.0);
    /** Frog Livid's poison: how long it lasts, how often it ticks and each tick's share of her base hit. APPROXIMATE. */
    private static final int POISON_TICKS = 100;
    private static final int POISON_EVERY = 20;
    private static final double POISON_SHARE = 0.05;
    /** How far from the middle of the ring the Livids walk, in blocks: the raised inner floor. */
    private static final double ROAM_RADIUS = 17;

    /** The block Livid Finder watches, in the arena's own (Hypixel's) coordinates. */
    private static final int[] WOOL = {6, 109, 43};
    /** The box of ceiling glass round it that changes with the wool, in the arena's coordinates. */
    private static final int[] GLASS_MIN = {-6, 107, 30};
    private static final int[] GLASS_MAX = {16, 110, 54};

    /** Hypixel's clone lines when the wrong Livid is killed (the wiki's Dialogue, Livid Enderman Clones). */
    private static final String[] WRONG_ONE = {
            "I am Livid, but I am not Livid.",
            "I am but a shadow.",
            "Keep looking!",
            "Wrong one!",
    };
    /** The real Livid's own lines when the wrong one is killed (the wiki's Dialogue, Livid). */
    private static final String[] FOOLED = {
            "You look like a real fool right now.",
            "So foolish!",
            "Yikes! Imagine getting that wrong.",
            "They couldn't even find the real Livid, psht!",
    };
    /**
     * Hypixel's opening after the first line, word for word (the wiki's Welcome Speech; the last is the line Livid
     * mods time their ults on). The spacing before the split is APPROXIMATE.
     */
    private static final String[] INTRO_LINES = {
            "This Orb you see, is Thorn, or what is left of him.",
            "In a way, I have to thank you for getting rid of him.",
            "I can now turn those Spirits into shadows of myself, identical to their creator.",
            "I respect you for making it to here, but I'll be your undoing.",
    };
    private static final int[] INTRO_AT = {60, 120, 190, 260};

    /** Where the sim's Livid wool is while a Livid fight is built, or null. Read by Livid Finder on the client. */
    private static volatile BlockPos woolCue;

    /**
     * The nine Livids, each with the colour Livid Finder knows it by (SkyHanni's LividSolver names) and the dye
     * of the wool that colour reads back as (Livid Finder's {@code woolColor}).
     */
    private enum Colour {
        VENDETTA("Vendetta", ChatFormatting.WHITE, DyeColor.WHITE),
        CROSSED("Crossed", ChatFormatting.LIGHT_PURPLE, DyeColor.MAGENTA),
        ARCADE("Arcade", ChatFormatting.YELLOW, DyeColor.YELLOW),
        SMILE("Smile", ChatFormatting.GREEN, DyeColor.LIME),
        HOCKEY("Hockey", ChatFormatting.RED, DyeColor.RED),
        DOCTOR("Doctor", ChatFormatting.GRAY, DyeColor.GRAY),
        FROG("Frog", ChatFormatting.DARK_GREEN, DyeColor.GREEN),
        SCREAM("Scream", ChatFormatting.BLUE, DyeColor.BLUE),
        PURPLE("Purple", ChatFormatting.DARK_PURPLE, DyeColor.PURPLE);

        final String display;
        final ChatFormatting chat;
        final DyeColor dye;

        Colour(String display, ChatFormatting chat, DyeColor dye) {
            this.display = display;
            this.chat = chat;
            this.dye = dye;
        }

        String speaker() {
            return display + " Livid";
        }
    }

    private enum Phase { INTRO, FIGHT, DYING, DEAD }

    /** What a thrown or shot thing does when it lands. */
    private enum ShotKind { DAGGER, ARROW, HARMING, SLOWNESS }

    private Phase phase = Phase.INTRO;
    private final boolean master = SimHealth.master(SimState.floorLabel());
    private final Colour real = Colour.values()[ThreadLocalRandom.current().nextInt(Colour.values().length)];
    private final List<Livid> livids = new ArrayList<>();
    private final List<BlockPos> glass = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    /** Players Frog Livid has poisoned, to the tick it wears off. */
    private final java.util.Map<UUID, Integer> poisoned = new java.util.HashMap<>();
    private int ticks;
    /** The tick the real Livid died on, for her last lines. */
    private int diedAt;
    private int spawnCounter;
    /** Master Mode's damage multiplier, up with every clone death. */
    private double damageBoost = 1.0;

    /** One Livid: its NPC body, its own pool, the name stand over it and its attack's state. */
    private static final class Livid {
        final Colour colour;
        final LividBody body;
        final SimBoss.Health health = new SimBoss.Health(lividHealth());
        ArmorStand stand;
        int swingCooldown;
        int abilityCooldown;
        /** Ticks into its attack's wind-up (Vendetta, Hockey, Scream), -1 when not in one. */
        int windup = -1;
        /** Hockey's charge: the direction it goes, the ticks left, and who it has already run through. */
        Vec3 chargeDir;
        int chargeTicks;
        final Set<UUID> chargeHit = new HashSet<>();
        /** Arcade's sideways drift, changed every so often. */
        double drift;
        /** Where round its target it closes in to, so nine Livids do not stand inside each other. */
        final double approachAngle;

        Livid(Colour colour, LividBody body, double approachAngle) {
            this.colour = colour;
            this.body = body;
            this.approachAngle = approachAngle;
        }
    }

    /** A dagger, arrow or splash potion in the air from {@code from} to {@code to}; arcs if lobbed. */
    private record Shot(ShotKind kind, Vec3 from, Vec3 to, int firedAt, int landsAt, boolean lobbed) {
    }

    SimLivid(SimBossArena arena) {
        super(arena);
    }

    /**
     * The sim's Livid wool (the block Livid Finder reads at {@code (6, 109, 43)} on Hypixel) while a Livid room is
     * built, else null. Safe from the client thread.
     */
    public static BlockPos woolCue() {
        return woolCue;
    }

    private static double lividHealth() {
        return SimHealth.byMode(7_000_000, 600_000_000);
    }

    @Override
    String name() {
        return "Livid";
    }

    @Override
    void start(ServerLevel level) {
        // Hypixel's first line, word for word: Boss Phases, the Livid Invulnerability HUD, Rag Axe and the
        // dungeon's boss detection (which Livid Finder waits on) all start on it.
        SimBoss.say(level.getServer(), "Livid",
                "Welcome, you've arrived right on time. I am Livid, the Master of Shadows.");
        BlockPos wool = arena.toWorld(WOOL);
        woolCue = wool;
        // Red until the split, as on Hypixel (Livid Finder assumes red on the way in).
        level.setBlock(wool, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState(), 3);
        findGlass(level);
        phase = Phase.INTRO;
        ticks = 0;
    }

    /** The red ceiling glass round the wool, which turns with it. */
    private void findGlass(ServerLevel level) {
        glass.clear();
        BlockPos lo = arena.toWorld(GLASS_MIN);
        BlockPos hi = arena.toWorld(GLASS_MAX);
        BlockState red = Blocks.STAINED_GLASS.pick(DyeColor.RED).defaultBlockState();
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            if (level.getBlockState(p) == red) {
                glass.add(p.immutable());
            }
        }
    }

    @Override
    void tick(ServerLevel level, int t) {
        ticks = t;
        switch (phase) {
            case INTRO -> intro(level, t);
            case FIGHT -> fight(level, t);
            case DYING -> {
                // Hypixel's last lines, from the real Livid by name, then the fight is over.
                if (t - diedAt == 30) {
                    SimBoss.say(level.getServer(), real.speaker(), "If you think this is the end, you are so wrong!");
                } else if (t - diedAt >= 60) {
                    SimBoss.say(level.getServer(), real.speaker(), "My shadows are everywhere, THEY WILL FIND YOU!!");
                    phase = Phase.DEAD;
                }
            }
            default -> {
            }
        }
        tickShots(level);
        for (Livid l : livids) {
            moveStand(l);
        }
    }

    private void intro(ServerLevel level, int t) {
        for (int i = 0; i < INTRO_AT.length; i++) {
            if (t == INTRO_AT[i]) {
                SimBoss.say(level.getServer(), "Livid", INTRO_LINES[i]);
            }
        }
        if (t == SPLIT_TICK) {
            split(level);
        } else if (t >= INVULNERABLE_TICKS) {
            phase = Phase.FIGHT;
            // Staggered, so nine attacks do not all open on the same tick.
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (Livid l : livids) {
                l.abilityCooldown = 20 + rnd.nextInt(60);
            }
        }
    }

    /** Nine Livids where there was one, the wool and glass turn the real one's colour, and everyone is blinded. */
    private void split(ServerLevel level) {
        // The cue first, so Livid Finder knows the colour the moment the bodies show up.
        BlockPos wool = arena.toWorld(WOOL);
        level.setBlock(wool, Blocks.WOOL.pick(real.dye).defaultBlockState(), 3);
        BlockState tinted = Blocks.STAINED_GLASS.pick(real.dye).defaultBlockState();
        for (BlockPos p : glass) {
            level.setBlock(p, tinted, 3);
        }
        Colour[] all = Colour.values();
        BlockPos c = arena.toWorld(arena.centre);
        double offset = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        for (int i = 0; i < all.length; i++) {
            double a = offset + Math.PI * 2 * i / all.length;
            double x = c.getX() + 0.5 + Math.cos(a) * 8;
            double z = c.getZ() + 0.5 + Math.sin(a) * 8;
            spawn(level, all[i], x, z, Math.PI * 2 * i / all.length);
        }
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE,
                2f, 0.8f);
        // The wiki's one second of blindness, F5 and M5 alike; Livid Finder's box shows through it.
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS, 0, false, false));
        }
    }

    private void spawn(ServerLevel level, Colour colour, double x, double z, double approach) {
        UUID id = UUID.nameUUIDFromBytes(("sky2m-livid-" + colour.display + "-" + spawnCounter++)
                .getBytes(StandardCharsets.UTF_8));
        LividBody body = new LividBody(level, new GameProfile(id, colour.speaker()));
        double y = ground(level, x, z);
        body.snapTo(x, y, z, 0f, 0f);
        body.getAbilities().invulnerable = false;
        int rgb = colour.dye.getTextureDiffuseColor() & 0xFFFFFF;
        body.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, rgb));
        body.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, rgb));
        body.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, rgb));
        body.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, rgb));
        body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(heldItem(colour)));
        // A client only builds a player body for a uuid it has player info for, so the info goes first (unlisted:
        // ADD_PLAYER alone leaves it out of the tab list, as Hypixel's NPCs are).
        level.getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(body)));
        level.addNewPlayer(body);
        Livid l = new Livid(colour, body, approach);
        l.stand = nameStand(level, body);
        livids.add(l);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1, z, 12, 0.3, 0.6, 0.3, 0.02);
        writeName(l);
    }

    /** What each Livid holds, so its attack reads at a glance. */
    private static Item heldItem(Colour colour) {
        return switch (colour) {
            case ARCADE -> Items.BOW;
            case DOCTOR, SMILE -> Items.SPLASH_POTION;
            default -> Items.IRON_SWORD;
        };
    }

    private static ItemStack dyed(Item item, int rgb) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
        return stack;
    }

    /** The name stand: a marker, so it has no hitbox to catch hits meant for the Livid (as SimMobs' tags). */
    private static ArmorStand nameStand(ServerLevel level, LivingEntity body) {
        ArmorStand tag = new ArmorStand(level, body.getX(), body.getY() + body.getBbHeight() + 0.3, body.getZ());
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setNoBasePlate(true);
        tag.setPermanentlyInvulnerable(true);
        byte flags = tag.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        tag.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        tag.setCustomNameVisible(true);
        level.addFreshEntity(tag);
        return tag;
    }

    /** Hypixel's Livid stand, "§c﴾ §c§lLivid§r§r §a7M§c❤ §c﴿", in the Livid's own colour, with its own health. */
    private static void writeName(Livid l) {
        if (l.stand == null) {
            return;
        }
        ChatFormatting c = l.colour.chat;
        MutableComponent name = Component.literal("﴾ ").withStyle(c)
                .append(Component.literal("Livid").withStyle(c, ChatFormatting.BOLD))
                .append(Component.literal(" "))
                .append(Component.literal(SimBoss.shortHealth(l.health.hp)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("❤").withStyle(ChatFormatting.RED))
                .append(Component.literal(" ﴿").withStyle(c));
        l.stand.setCustomName(name);
    }

    private static void moveStand(Livid l) {
        if (l.stand != null && !l.stand.isRemoved()) {
            l.stand.setPos(l.body.getX(), l.body.getY() + l.body.getBbHeight() + 0.3, l.body.getZ());
        }
    }

    private void fight(ServerLevel level, int t) {
        List<ServerPlayer> targets = SimBoss.playersInRing(level);
        for (Livid l : livids) {
            if (l.swingCooldown > 0) {
                l.swingCooldown--;
            }
            if (l.abilityCooldown > 0) {
                l.abilityCooldown--;
            }
            ServerPlayer target = nearest(targets, l.body.position());
            if (target == null) {
                continue;
            }
            boolean busy = ability(level, l, target, targets);
            if (!busy) {
                chase(level, l, target);
            }
        }
        if (t % 10 == 0) {
            for (Livid l : livids) {
                writeName(l);
            }
        }
        tickPoison(level);
        Livid her = realLivid();
        if (her == null || her.health.hp <= 0) {
            die(level);
        }
    }

    /** Frog Livid's poison: a small Hypixel hit once a second while it lasts, with green particles for the look. */
    private void tickPoison(ServerLevel level) {
        if (poisoned.isEmpty()) {
            return;
        }
        DustParticleOptions green = new DustParticleOptions(0x4E9331, 1f);
        poisoned.entrySet().removeIf(e -> {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || ticks >= e.getValue()) {
                return true;
            }
            if (ticks % 5 == 0) {
                level.sendParticles(green, p.getX(), p.getY() + 1, p.getZ(), 3, 0.3, 0.5, 0.3, 0);
            }
            if ((e.getValue() - ticks) % POISON_EVERY == 0) {
                hurtPlayer(level, p, damage(POISON_SHARE), false, 0, 0, 0, 0);
            }
            return false;
        });
    }

    /** A Livid's hit after Master Mode's buffs. */
    private double damage(double share) {
        return lividDamage() * share * damageBoost;
    }

    /** Walks a Livid at its target and swings when close. Every Livid does this, the real one included. */
    private void chase(ServerLevel level, Livid l, ServerPlayer target) {
        LividBody b = l.body;
        double gx;
        double gz;
        double speed = master ? 0.26 : 0.2;
        if (l.colour == Colour.ARCADE) {
            // Arcade keeps its distance for the bow and darts about: a sideways drift that keeps changing.
            if (ticks % 12 == 0) {
                l.drift = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.4;
            }
            double ax = b.getX() - target.getX();
            double az = b.getZ() - target.getZ();
            double d = Math.max(0.01, Math.sqrt(ax * ax + az * az));
            double keep = 7;
            gx = target.getX() + ax / d * keep - az / d * l.drift * 4;
            gz = target.getZ() + az / d * keep + ax / d * l.drift * 4;
            speed *= 1.4;
        } else {
            gx = target.getX() + Math.cos(l.approachAngle) * 1.6;
            gz = target.getZ() + Math.sin(l.approachAngle) * 1.6;
        }
        step(level, l, gx, gz, speed, target.position());
        if (l.swingCooldown == 0 && b.distanceToSqr(target) < 2.8 * 2.8) {
            b.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            hurtPlayer(level, target, damage(1.0), false, target.getX() - b.getX(),
                    target.getZ() - b.getZ(), 0.4, 0.25);
            if (l.colour == Colour.FROG) {
                // Frog's poison rides on its hit (the wiki: "Poisons players", no numbers). Dealt by the fight in
                // Hypixel damage ({@link #tickPoison}), not as vanilla Poison, whose attacker-less ticks SimVitals
                // would scale up from the vanilla amount.
                poisoned.put(target.getUUID(), ticks + POISON_TICKS);
            }
            l.swingCooldown = master ? 18 : 25;
        }
    }

    /** Moves a Livid toward (gx, gz) at {@code speed}, kept on the ring's inner floor, facing {@code look}. */
    private void step(ServerLevel level, Livid l, double gx, double gz, double speed, Vec3 look) {
        LividBody b = l.body;
        double dx = gx - b.getX();
        double dz = gz - b.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double x = b.getX();
        double z = b.getZ();
        if (len > 0.3) {
            double s = Math.min(speed, len);
            x += dx / len * s;
            z += dz / len * s;
        }
        BlockPos c = arena.toWorld(arena.centre);
        double ox = x - (c.getX() + 0.5);
        double oz = z - (c.getZ() + 0.5);
        double r = Math.sqrt(ox * ox + oz * oz);
        if (r > ROAM_RADIUS) {
            x = c.getX() + 0.5 + ox / r * ROAM_RADIUS;
            z = c.getZ() + 0.5 + oz / r * ROAM_RADIUS;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(look.z - z, look.x - x)) - 90);
        b.snapTo(x, ground(level, x, z), z, yaw, 0f);
        b.setYHeadRot(yaw);
        b.setYBodyRot(yaw);
    }

    /**
     * The Livid's own attack, by colour. The cooldowns, ranges and shares of her base damage are the sim's
     * (APPROXIMATE: the wiki names each attack but gives no numbers); Master Mode's come round a quarter sooner.
     *
     * @return true while the attack holds the Livid in place (a wind-up or a charge), so it does not walk
     */
    private boolean ability(ServerLevel level, Livid l, ServerPlayer target, List<ServerPlayer> targets) {
        LividBody b = l.body;
        double dist = Math.sqrt(b.distanceToSqr(target));
        return switch (l.colour) {
            case VENDETTA -> vendetta(level, l, target);
            case CROSSED -> {
                if (l.abilityCooldown == 0 && dist < 12) {
                    // Crossed: Blindness on its target (APPROXIMATE: three seconds).
                    target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, true));
                    level.sendParticles(ParticleTypes.SQUID_INK, target.getX(), target.getY() + 1.6, target.getZ(),
                            12, 0.3, 0.3, 0.3, 0.02);
                    level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.WITCH_THROW,
                            SoundSource.HOSTILE, 1f, 0.6f);
                    b.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                    l.abilityCooldown = cooldown(160);
                }
                yield false;
            }
            case HOCKEY -> hockey(level, l, target, targets);
            case DOCTOR -> {
                if (l.abilityCooldown == 0 && dist < 14) {
                    throwShot(level, l, ShotKind.HARMING, target.position(), true);
                    l.abilityCooldown = cooldown(120);
                }
                yield false;
            }
            case FROG -> false;    // its poison is on its swing
            case SMILE -> {
                if (l.abilityCooldown == 0 && dist < 14) {
                    throwShot(level, l, ShotKind.SLOWNESS, target.position(), true);
                    l.abilityCooldown = cooldown(140);
                }
                yield false;
            }
            case SCREAM -> scream(level, l, dist, targets);
            case PURPLE -> {
                if (l.abilityCooldown == 0 && dist < 6) {
                    leech(level, l, target);
                    l.abilityCooldown = cooldown(20);
                }
                yield false;
            }
            case ARCADE -> {
                if (l.abilityCooldown == 0 && dist < 24) {
                    throwShot(level, l, ShotKind.ARROW, target.position().add(0, 1, 0), false);
                    l.abilityCooldown = cooldown(40);
                }
                yield false;
            }
        };
    }

    private int cooldown(int ticks) {
        return master ? ticks * 3 / 4 : ticks;
    }

    /** Vendetta: vanishes, comes back beside its target and throws its dagger a moment later. */
    private boolean vendetta(ServerLevel level, Livid l, ServerPlayer target) {
        LividBody b = l.body;
        if (l.windup < 0) {
            if (l.abilityCooldown > 0) {
                return false;
            }
            level.sendParticles(ParticleTypes.PORTAL, b.getX(), b.getY() + 1, b.getZ(), 30, 0.3, 0.8, 0.3, 0.4);
            double a = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double x = target.getX() + Math.cos(a) * 4;
            double z = target.getZ() + Math.sin(a) * 4;
            step(level, l, x, z, 100, target.position());
            level.sendParticles(ParticleTypes.PORTAL, b.getX(), b.getY() + 1, b.getZ(), 30, 0.3, 0.8, 0.3, 0.4);
            level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE,
                    1f, 1f);
            l.windup = 0;
        }
        l.windup++;
        if (l.windup < 8) {
            step(level, l, b.getX(), b.getZ(), 0, target.position());
            return true;
        }
        l.windup = -1;
        throwShot(level, l, ShotKind.DAGGER, target.position().add(0, 1, 0), false);
        l.abilityCooldown = cooldown(120);
        return false;
    }

    /** Hockey: plants itself for three quarters of a second, then charges straight through. */
    private boolean hockey(ServerLevel level, Livid l, ServerPlayer target, List<ServerPlayer> targets) {
        LividBody b = l.body;
        if (l.chargeTicks > 0) {
            l.chargeTicks--;
            Vec3 to = b.position().add(l.chargeDir.scale(0.9));
            step(level, l, to.x, to.z, 0.9, to.add(l.chargeDir));
            level.sendParticles(ParticleTypes.CLOUD, b.getX(), b.getY() + 0.2, b.getZ(), 2, 0.2, 0.05, 0.2, 0);
            for (ServerPlayer p : targets) {
                if (!l.chargeHit.contains(p.getUUID()) && p.distanceToSqr(b) < 1.8 * 1.8) {
                    l.chargeHit.add(p.getUUID());
                    // APPROXIMATE: one and a half of her base damage, and a hard shove.
                    hurtPlayer(level, p, damage(1.5), false, l.chargeDir.x, l.chargeDir.z, 1.4, 0.5);
                }
            }
            return true;
        }
        if (l.windup < 0) {
            if (l.abilityCooldown > 0 || b.distanceToSqr(target) > 14 * 14) {
                return false;
            }
            l.windup = 0;
            level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE,
                    1f, 1.4f);
        }
        l.windup++;
        if (l.windup < 15) {
            step(level, l, b.getX(), b.getZ(), 0, target.position());
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, b.getX(), b.getY() + 2.2, b.getZ(), 1, 0.2, 0.1, 0.2,
                    0);
            return true;
        }
        l.windup = -1;
        Vec3 d = new Vec3(target.getX() - b.getX(), 0, target.getZ() - b.getZ());
        l.chargeDir = d.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : d.normalize();
        l.chargeTicks = 14;
        l.chargeHit.clear();
        l.abilityCooldown = cooldown(140);
        return true;
    }

    /** Scream: a ring that grows round it for a second, then true damage and a shove for everyone in it. */
    private boolean scream(ServerLevel level, Livid l, double dist, List<ServerPlayer> targets) {
        double radius = 6;
        LividBody b = l.body;
        if (l.windup < 0) {
            if (l.abilityCooldown > 0 || dist > radius) {
                return false;
            }
            l.windup = 0;
            level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE,
                    1f, 1.2f);
        }
        l.windup++;
        int telegraph = 20;
        if (l.windup < telegraph) {
            if (l.windup % 2 == 0) {
                DustParticleOptions blue = new DustParticleOptions(0x3355FF, 1.2f);
                double r = radius * l.windup / telegraph;
                for (int i = 0; i < 20; i++) {
                    double a = Math.PI * 2 * i / 20;
                    level.sendParticles(blue, b.getX() + Math.cos(a) * r, b.getY() + 0.2, b.getZ() + Math.sin(a) * r,
                            1, 0, 0, 0, 0);
                }
            }
            return true;
        }
        l.windup = -1;
        l.abilityCooldown = cooldown(160);
        level.sendParticles(ParticleTypes.SONIC_BOOM, b.getX(), b.getY() + 1, b.getZ(), 1, 0, 0, 0, 0);
        level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE,
                1.5f, 1.3f);
        for (ServerPlayer p : targets) {
            double dx = p.getX() - b.getX();
            double dz = p.getZ() - b.getZ();
            if (dx * dx + dz * dz <= radius * radius && Math.abs(p.getY() - b.getY()) < 3) {
                // True damage, the wiki's ("a knockback circle dealing True Damage"); the half share APPROXIMATE.
                hurtPlayer(level, p, damage(0.5), true, dx, dz, 1.6, 0.6);
            }
        }
        return false;
    }

    /** Purple: drains its target once a second and heals itself with it. */
    private void leech(ServerLevel level, Livid l, ServerPlayer target) {
        LividBody b = l.body;
        // APPROXIMATE: a quarter of her base damage drained, 1% of a Livid's health back.
        hurtPlayer(level, target, damage(0.25), false, 0, 0, 0, 0);
        l.health.hp = Math.min(l.health.max, l.health.hp + l.health.max * 0.01);
        DustParticleOptions purple = new DustParticleOptions(0x8B00FF, 1f);
        line(level, purple, target.position().add(0, 1, 0), b.position().add(0, 1.2, 0));
        level.sendParticles(ParticleTypes.HEART, b.getX(), b.getY() + 2.2, b.getZ(), 1, 0.2, 0.1, 0.2, 0);
    }

    private void throwShot(ServerLevel level, Livid l, ShotKind kind, Vec3 at, boolean lobbed) {
        LividBody b = l.body;
        Vec3 from = b.position().add(0, 1.5, 0);
        double dist = from.distanceTo(at);
        // Lobbed potions take a second; a dagger or arrow flies at about two blocks a tick.
        int flight = lobbed ? 20 : Math.max(3, (int) Math.ceil(dist / 2));
        shots.add(new Shot(kind, from, at, ticks, ticks + flight, lobbed));
        b.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        switch (kind) {
            case DAGGER -> level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.TRIDENT_THROW,
                    SoundSource.HOSTILE, 1f, 1.4f);
            case ARROW -> level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.ARROW_SHOOT,
                    SoundSource.HOSTILE, 1f, 1f);
            default -> level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.SPLASH_POTION_THROW,
                    SoundSource.HOSTILE, 1f, 1f);
        }
    }

    private void tickShots(ServerLevel level) {
        if (shots.isEmpty()) {
            return;
        }
        List<Shot> landed = new ArrayList<>();
        for (Shot s : shots) {
            double f = (double) (ticks - s.firedAt()) / Math.max(1, s.landsAt() - s.firedAt());
            Vec3 at = s.from().lerp(s.to(), Math.min(1, f));
            if (s.lobbed()) {
                at = at.add(0, Math.sin(Math.PI * Math.min(1, f)) * 4, 0);
            }
            level.sendParticles(trail(s.kind()), at.x, at.y, at.z, 2, 0.05, 0.05, 0.05, 0);
            if (ticks < s.landsAt()) {
                continue;
            }
            landed.add(s);
            land(level, s);
        }
        shots.removeAll(landed);
    }

    private static ParticleOptions trail(ShotKind kind) {
        return switch (kind) {
            case DAGGER -> ParticleTypes.ENCHANTED_HIT;
            case ARROW -> ParticleTypes.CRIT;
            case HARMING -> new DustParticleOptions(0xA9204A, 1.2f);
            case SLOWNESS -> new DustParticleOptions(0x5A6C81, 1.2f);
        };
    }

    private void land(ServerLevel level, Shot s) {
        Vec3 at = s.to();
        boolean splash = s.kind() == ShotKind.HARMING || s.kind() == ShotKind.SLOWNESS;
        double radius = splash ? 2.5 : 1.3;
        if (splash) {
            level.sendParticles(trail(s.kind()), at.x, at.y + 0.3, at.z, 30, 1.2, 0.3, 1.2, 0);
            level.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.3, at.z, 20, 1, 0.2, 1, 0.1);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.SPLASH_POTION_BREAK, SoundSource.HOSTILE, 1f, 1f);
        }
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            Vec3 c = splash ? p.position() : p.position().add(0, 1, 0);
            if (c.distanceToSqr(at) > radius * radius) {
                continue;
            }
            switch (s.kind()) {
                // APPROXIMATE: a dagger or arrow her base damage; the Harming potion half of it. True damage is the
                // wiki's ("Throws a Splash Potion of Harming, dealing True Damage"); the share is not.
                case DAGGER -> hurtPlayer(level, p, damage(1.0), false, p.getX() - s.from().x, p.getZ() - s.from().z,
                        0.3, 0.2);
                case ARROW -> hurtPlayer(level, p, damage(0.6), false, p.getX() - s.from().x, p.getZ() - s.from().z,
                        0.3, 0.15);
                case HARMING -> hurtPlayer(level, p, damage(0.5), true, 0, 0, 0, 0);
                // APPROXIMATE: four seconds of Slowness II.
                case SLOWNESS -> p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1));
            }
        }
    }

    private static void line(ServerLevel level, ParticleOptions particle, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        int steps = (int) Math.max(3, d.length() * 2);
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.add(d.scale((double) i / steps));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    /**
     * A clone at 0: it regenerates to full, the clones near it get a quarter of their health back, one of Hypixel's
     * "wrong one" lines, and in Master Mode every Livid hits harder from now on.
     */
    private void cloneDied(ServerLevel level, Livid clone) {
        LividBody b = clone.body;
        clone.health.hp = clone.health.max;
        level.sendParticles(ParticleTypes.LARGE_SMOKE, b.getX(), b.getY() + 1, b.getZ(), 20, 0.3, 0.7, 0.3, 0.02);
        level.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f,
                0.7f);
        for (Livid other : livids) {
            if (other == clone || other.colour == real) {
                continue;
            }
            if (other.body.distanceToSqr(b) <= HEAL_RADIUS * HEAL_RADIUS) {
                other.health.hp = Math.min(other.health.max, other.health.hp + other.health.max * CLONE_HEAL);
                level.sendParticles(ParticleTypes.HEART, other.body.getX(), other.body.getY() + 2.2,
                        other.body.getZ(), 3, 0.3, 0.2, 0.3, 0);
                writeName(other);
            }
        }
        if (master) {
            damageBoost += MASTER_DEATH_BUFF;
        }
        // Either the dead clone or the real Livid has a word about it (the wiki lists lines for both).
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextBoolean()) {
            SimBoss.say(level.getServer(), clone.colour.speaker(), WRONG_ONE[rnd.nextInt(WRONG_ONE.length)]);
        } else {
            SimBoss.say(level.getServer(), real.speaker(), FOOLED[rnd.nextInt(FOOLED.length)]);
        }
        writeName(clone);
    }

    private void die(ServerLevel level) {
        // Hypixel's last line, said by the real Livid by name ("[BOSS] Hockey Livid: Impossible! ...").
        SimBoss.say(level.getServer(), real.speaker(), "Impossible! How did you figure out which one I was?!");
        Livid her = realLivid();
        if (her != null) {
            level.sendParticles(ParticleTypes.EXPLOSION, her.body.getX(), her.body.getY() + 1, her.body.getZ(), 3,
                    0.5, 0.5, 0.5, 0);
        }
        removeLivids(level);
        shots.clear();
        poisoned.clear();
        diedAt = ticks;
        phase = Phase.DYING;
    }

    private Livid realLivid() {
        for (Livid l : livids) {
            if (l.colour == real) {
                return l;
            }
        }
        return null;
    }

    private Livid livid(Entity e) {
        for (Livid l : livids) {
            if (l.body == e) {
                return l;
            }
        }
        return null;
    }

    private void removeLivids(ServerLevel level) {
        List<UUID> ids = new ArrayList<>();
        for (Livid l : livids) {
            ids.add(l.body.getUUID());
            if (!l.body.isRemoved()) {
                level.removePlayerImmediately(l.body, Entity.RemovalReason.DISCARDED);
            }
            if (l.stand != null) {
                l.stand.discard();
            }
        }
        livids.clear();
        if (!ids.isEmpty()) {
            level.getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(ids));
        }
    }

    /** The top of the floor under (x, z), searched down from a few blocks above the ring. */
    private double ground(ServerLevel level, double x, double z) {
        BlockPos c = arena.toWorld(arena.centre);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos((int) Math.floor(x), c.getY() + 3,
                (int) Math.floor(z));
        for (int i = 0; i < 10; i++, p.move(Direction.DOWN)) {
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

    /**
     * Damage plus knockback: {@code damage} is Hypixel damage before Defense, or past it when {@code trueDamage}
     * ({@link SimVitals#dealHypixel}).
     */
    private static void hurtPlayer(ServerLevel level, ServerPlayer p, double damage, boolean trueDamage, double dx,
                                   double dz, double push, double lift) {
        DamageSource src = level.damageSources().magic();
        SimVitals.dealHypixel(level, p, src, damage, trueDamage);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01 || push <= 0) {
            return;
        }
        p.setDeltaMovement(dx / len * push, lift, dz / len * push);
        p.syncVelocity = true;
    }

    @Override
    boolean isBoss(Entity entity) {
        return entity instanceof LividBody && livid(entity) != null;
    }

    @Override
    boolean hit(ServerLevel level, LivingEntity body, float amount) {
        Livid l = livid(body);
        if (l == null || phase != Phase.FIGHT) {
            return false;
        }
        // Every Livid has its own pool; only the real one's running out ends the fight (checked in fight()).
        boolean landed = l.health.hit((float) (amount * DAMAGE_TAKEN));
        if (landed) {
            if (l.colour != real && l.health.hp <= 0) {
                cloneDied(level, l);
            } else if (ticks % 2 == 0) {
                writeName(l);
            }
        }
        return landed;
    }

    @Override
    boolean defeated() {
        return phase == Phase.DEAD;
    }

    @Override
    void discard(ServerLevel level) {
        removeLivids(level);
        shots.clear();
        poisoned.clear();
        woolCue = null;
    }

    @Override
    Component barName() {
        return Component.literal("Livid").withStyle(ChatFormatting.RED);
    }

    @Override
    BossEvent.BossBarColor barColor() {
        return phase == Phase.INTRO ? BossEvent.BossBarColor.WHITE : BossEvent.BossBarColor.RED;
    }

    /**
     * The invulnerability count-up, then a full bar: Hypixel's boss bar does not show Livid's health, so it cannot
     * be used to tell which one is real.
     */
    @Override
    float barProgress() {
        return phase == Phase.INTRO ? Math.min(1f, (float) ticks / INVULNERABLE_TICKS) : 1f;
    }

    /**
     * A Livid's body: an NPC player, like Hypixel's. Fabric's {@link FakePlayer} refuses all damage and cannot be
     * hit by players; this one can, so the hit reaches {@link SimBoss#onDamage} (which refuses the vanilla hit and
     * puts it on the pool). It never ticks (FakePlayer), so it only moves where the fight puts it.
     */
    private static final class LividBody extends FakePlayer {
        LividBody(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
            return false;
        }

        @Override
        public boolean canHarmPlayer(Player other) {
            return true;
        }
    }
}
