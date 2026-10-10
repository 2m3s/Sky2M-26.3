package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MKeyMappings;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diana burrows, ported from SkyBlock Overhaul (https://github.com/SkyblockOverhaul/SBO, Apache-2.0):
 * BurrowDetector and ParticleTypes (Close Burrow Detection), ArrowGuessBurrow and GuessEntry (Arrow Guess, which SBO
 * credits to SidOfThe7Cs's logic in SkyHanni), PreciseGuessBurrow with PolynomialFitter and Matrix (Spade Guess),
 * DianaEvents (which burrow you dug), the guess clean-up from WaypointManager, and its warp keys (WarpPoint and
 * getClosestWarp). Waypoints are a coloured block border, optionally with a beacon beam in the same colour.
 */
public final class DianaBurrows {
    enum Kind {
        START("Start"), MOB("Mob"), TREASURE("Treasure"), SPADE("Guess"), ARROW("Guess");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        boolean guess() {
            return this == SPADE || this == ARROW;
        }
    }

    /** A burrow or guess waypoint. */
    static final class Waypoint {
        final BlockPos pos;
        final Kind kind;
        final long created = System.currentTimeMillis();
        int timesDug;
        boolean interacted;
        boolean hidden;

        Waypoint(BlockPos pos, Kind kind) {
            this.pos = pos;
            this.kind = kind;
        }

        boolean strongerThan(Waypoint other) {
            return timesDug > other.timesDug || interacted && !other.interacted;
        }

        void carryOver(Waypoint other) {
            if (other.timesDug > timesDug) timesDug = other.timesDug;
            if (other.interacted) interacted = true;
        }
    }

    /** SBO's GuessEntry: the blocks along an arrow that could be the burrow, nearest first. */
    static final class GuessChain {
        final List<BlockPos> guesses;
        int index;
        /** When you came within particle range of the current guess with a spade, or 0. */
        long nearSince;

        GuessChain(List<BlockPos> guesses) {
            this.guesses = guesses;
        }

        BlockPos current() {
            return guesses.get(index);
        }

        List<BlockPos> remaining() {
            return guesses.subList(index + 1, guesses.size());
        }

        boolean sameAs(GuessChain other) {
            return guesses.subList(index, guesses.size()).equals(other.guesses.subList(other.index, other.guesses.size()));
        }

        void removeAllWaypoints() {
            arrows.remove(current());
        }

        /** The current guess was wrong: on to the next block along the arrow. */
        boolean moveToNext() {
            Waypoint wp = arrows.get(current());
            if (wp != null) {
                // One you've dug is taken away once Hypixel says the burrow is done (dug twice).
                if (wp.interacted) toRemove.put(wp, () -> wp.timesDug != 1);
                else arrows.remove(wp.pos);
            }
            if (index + 1 >= guesses.size()) return false;
            index++;
            nearSince = 0;
            addArrowGuess(current());
            return true;
        }

        void removeSubGuess(BlockPos pos) {
            int i = guesses.indexOf(pos);
            if (i > index) guesses.remove(i);
        }
    }

    // ------------------------------------------------------------------------------------------------ constants

    private static final AABB HUB_BOUNDS = new AABB(-283, 60, -208, 175, 105, 205);
    private static final long RECENTLY_REMOVED_MS = 1_000L;
    /** Hypixel shows a burrow's particles about once a second: this long near a guess without any means it's wrong. */
    private static final long NO_PARTICLES_GRACE_MS = 2_500L;
    private static final int SHAFT_LENGTH = 20;
    private static final double PARTICLE_TOLERANCE = 0.12;
    private static final int COUNT_NEAR_TIP = 4;
    private static final int COUNT_NEAR_BASE = 2;
    private static final double EPSILON = 1e-6;

    /** "You dug out a Griffin Burrow! (1/4)" */
    private static final Pattern BURROW_DUG = Pattern.compile("^You (.*?) Griffin [Bb]urrow(.*?) \\((\\d+)/(\\d+)\\)$");
    private static final Pattern DUG_OUT = Pattern.compile("You (?:just )?dug out");
    private static final Pattern ENDS_WITH_COUNT = Pattern.compile("\\(\\d+/\\d+\\)$");

    /** SBO's hub warps: where /warp puts you. */
    private record Warp(String name, Vec3 pos, int extraBlocks) {}

    private static final List<Warp> WARPS = List.of(
        new Warp("hub", new Vec3(0.5, 77, -0.5), 0),
        new Warp("castle", new Vec3(-250, 130, 45), 0),
        new Warp("wizard", new Vec3(44.5, 119, 93.5), 0),
        new Warp("crypt", new Vec3(-160.5, 62, -106.5), 10),
        new Warp("stonks", new Vec3(-36.5, 70, -81.5), 0),
        new Warp("da", new Vec3(91.5, 75, 173.5), 0),
        new Warp("taylor", new Vec3(29.5, 73, -41.5), 0),
        new Warp("museum", new Vec3(29.5, 72, 1.5), 0));

    // ------------------------------------------------------------------------------------------------ state

    private static final Map<BlockPos, Waypoint> burrows = new LinkedHashMap<>();
    private static final Map<BlockPos, Waypoint> arrows = new LinkedHashMap<>();
    private static final List<Waypoint> spadeGuesses = new ArrayList<>();
    private static final List<GuessChain> chains = new ArrayList<>();
    private static final Map<Waypoint, BooleanSupplier> toRemove = new HashMap<>();
    private static final Map<BlockPos, Long> recentlyRemoved = new HashMap<>();
    private static final Map<BlockPos, Long> recentClickedBlocks = new HashMap<>();
    private static final Set<BlockPos> invalidCache = new HashSet<>();
    private static final Set<Vec3> arrowParticles = new HashSet<>();
    private static final Map<String, Long> recentArrows = new HashMap<>();
    private static final List<Vec3> lavaParticles = new ArrayList<>();

    private static BlockPos lastWaypointClicked;
    private static BlockPos lastDugOut;
    private static long lastGuessTime;
    private static boolean spadeGuessThisUse;
    private static long lastSpadeHeld;
    private static long lastWarpAt;

    private DianaBurrows() {}

    private static FeatureConfigs.DianaBurrows config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mayors.diana.burrows;
    }

    private static FeatureConfigs.DianaWarp warpConfig() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mayors.diana.warp;
    }

    private static boolean inHub() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("Hub");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DianaBurrows::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            FeatureConfigs.DianaBurrows config = config();
            if (config != null && config.keepOnWorldChange) {
                // The particles and arrows seen are of the last server.
                arrowParticles.clear();
                lavaParticles.clear();
                invalidCache.clear();
            } else {
                clear();
            }
        });
        Sky2MWorldRender.register(DianaBurrows::render);
        Sky2MChat.onChat(message -> onChat(message.text()));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide() && inHub()) {
                recentClickedBlocks.put(pos.immutable(), System.currentTimeMillis());
                blockInteraction(pos.immutable());
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && inHub() && isSpade(player.getItemInHand(hand))) blockInteraction(hit.getBlockPos().immutable());
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) onUseItem(player.getItemInHand(hand));
            return InteractionResult.PASS;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("clearburrows").executes(c -> {
                    Minecraft.getInstance().execute(() -> {
                        clear();
                        Sky2MAlerts.chat(Component.literal("Burrow waypoints cleared!").withStyle(ChatFormatting.RED));
                    });
                    return 1;
                })));
            }
        });
    }

    private static void clear() {
        burrows.clear();
        arrows.clear();
        spadeGuesses.clear();
        chains.clear();
        toRemove.clear();
        recentlyRemoved.clear();
        recentArrows.clear();
        arrowParticles.clear();
        lavaParticles.clear();
        invalidCache.clear();
    }

    private static boolean isSpade(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getHoverName().getString().contains("Spade");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static BlockPos blockOf(double x, double y, double z) {
        return BlockPos.containing(x, y, z);
    }

    private static void markRecentlyRemoved(BlockPos pos) {
        recentlyRemoved.put(pos, System.currentTimeMillis());
    }

    private static boolean wasRecentlyRemoved(BlockPos pos) {
        Long at = recentlyRemoved.get(pos);
        if (at == null) return false;
        if (System.currentTimeMillis() - at <= RECENTLY_REMOVED_MS) return true;
        recentlyRemoved.remove(pos);
        return false;
    }

    private static Waypoint spadeAt(BlockPos pos) {
        for (Waypoint w : spadeGuesses) if (w.pos.equals(pos)) return w;
        return null;
    }

    /** Multi Guesses off: every earlier guess goes when a new one is made. */
    private static void dropOldGuesses() {
        FeatureConfigs.DianaBurrows config = config();
        if (config == null || config.multiGuesses) return;
        spadeGuesses.clear();
        arrows.clear();
        chains.clear();
    }

    private static void addArrowGuess(BlockPos pos) {
        arrows.putIfAbsent(pos, new Waypoint(pos, Kind.ARROW));
    }

    private static List<Waypoint> allWaypoints() {
        List<Waypoint> all = new ArrayList<>(burrows.values());
        all.addAll(arrows.values());
        all.addAll(spadeGuesses);
        return all;
    }

    /** Whether a guess can be the burrow: grass (or a block you just broke) with air above, in the Hub's burrow area. */
    private static boolean isBlockValid(BlockPos pos) {
        if (!inside(pos)) return false;
        if (invalidCache.contains(pos)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.isLoaded(pos)) return true;
        var block = mc.level.getBlockState(pos);
        boolean grass = block.is(Blocks.GRASS_BLOCK);
        Long clicked = recentClickedBlocks.get(pos);
        boolean justBroken = block.isAir() && clicked != null && System.currentTimeMillis() - clicked < 4_000L;
        boolean valid = (grass || justBroken) && mc.level.getBlockState(pos.above()).isAir();
        if (!valid) invalidCache.add(pos);
        return valid;
    }

    private static boolean inside(BlockPos pos) {
        return pos.getX() > HUB_BOUNDS.minX && pos.getX() <= HUB_BOUNDS.maxX
            && pos.getY() > HUB_BOUNDS.minY && pos.getY() <= HUB_BOUNDS.maxY
            && pos.getZ() > HUB_BOUNDS.minZ && pos.getZ() <= HUB_BOUNDS.maxZ;
    }

    private static boolean insideVec(Vec3 v) {
        return v.x > HUB_BOUNDS.minX && v.x <= HUB_BOUNDS.maxX && v.y > HUB_BOUNDS.minY && v.y <= HUB_BOUNDS.maxY
            && v.z > HUB_BOUNDS.minZ && v.z <= HUB_BOUNDS.maxZ;
    }

    // ------------------------------------------------------------------------------------------------ chat and clicks

    private static void blockInteraction(BlockPos pos) {
        Waypoint wp = burrows.get(pos);
        if (wp == null) wp = arrows.get(pos);
        if (wp == null) wp = spadeAt(pos);
        if (wp != null) {
            wp.interacted = true;
            lastWaypointClicked = wp.pos;
        }
    }

    private static void onChat(String text) {
        FeatureConfigs.DianaBurrows config = config();
        if (config == null || !inHub()) return;
        String t = text.trim();
        Matcher dug = BURROW_DUG.matcher(t);
        if (dug.matches()) {
            int current = Integer.parseInt(dug.group(3));
            int max = Integer.parseInt(dug.group(4));
            if (config.arrowGuess && current != max) arrowParticles.clear();
            if (config.closeBurrowDetection) {
                lastDugOut = lastWaypointClicked;
                refreshBurrows(false, 2, null);
            }
            return;
        }
        if (t.startsWith("You finished the Griffin burrow chain!")) {
            lastDugOut = lastWaypointClicked;
            refreshBurrows(false, 2, null);
            return;
        }
        if (t.startsWith("☠ You ")) {
            refreshBurrows(true, 1, null);
            return;
        }
        // Mobs, feathers, coins and Myth the Fish: "Uh oh! You dug out a Minos Hunter!". Player chat has a ": ".
        if (DUG_OUT.matcher(t).find() && !ENDS_WITH_COUNT.matcher(t).find() && !t.contains(": ")) {
            lastDugOut = lastWaypointClicked;
            boolean treasure = t.contains("Griffin Feather") || t.contains(" coins!") || t.contains("Mythos Fragment") || t.contains("Myth the Fish");
            refreshBurrows(false, 1, treasure ? Kind.TREASURE : Kind.MOB);
        }
    }

    /** SBO's refreshBurrows: counts the dig on the burrow you last clicked and removes it once it's done. */
    private static void refreshBurrows(boolean death, int expectedTimesDug, Kind burrowType) {
        BlockPos pos = lastDugOut;
        if (pos == null) return;
        Waypoint known = burrows.get(pos);
        Waypoint dug = known != null ? known : arrows.get(pos);
        if (dug == null) dug = spadeAt(pos);

        if (dug != null) {
            boolean start = dug.kind == Kind.START;
            boolean mob = dug.kind == Kind.MOB || burrowType == Kind.MOB;
            if (!death && !start) dug.timesDug++;
            // Hypixel's count is right if the two ever disagree.
            dug.timesDug = expectedTimesDug;
            boolean diedToMob = death && dug.timesDug >= 1 && mob;
            if ((!death && start) || (!death && dug.timesDug >= 2) || diedToMob) {
                if (diedToMob) Sky2MAlerts.chat(Component.literal("Removed the Mob burrow waypoint since you died.").withStyle(ChatFormatting.YELLOW));
                markRecentlyRemoved(dug.pos);
                removeChainsWithSubGuess(dug.pos);
                removeChainsAt(dug.pos);
                removeWaypoint(dug);
            }
        }
        toRemove.entrySet().removeIf(e -> {
            if (!e.getValue().getAsBoolean()) return false;
            removeWaypoint(e.getKey());
            return true;
        });

        // A guess that turned out to be a real burrow before its particles were seen.
        if (dug != null && dug.kind.guess() && burrowType != null) {
            registerBurrow(pos, burrowType, expectedTimesDug);
            markRecentlyRemoved(dug.pos);
            removeWaypoint(dug);
        }
    }

    private static void removeWaypoint(Waypoint wp) {
        switch (wp.kind) {
            case ARROW -> arrows.remove(wp.pos, wp);
            case SPADE -> spadeGuesses.remove(wp);
            default -> burrows.remove(wp.pos, wp);
        }
    }

    // ------------------------------------------------------------------------------------------------ particles

    /** Every particle packet (Sky2MSlayerPacketsMixin). */
    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        FeatureConfigs.DianaBurrows config = config();
        if (config == null || !inHub()) return;
        if (config.spadeGuess) spadeParticle(packet);
        if (config.arrowGuess) arrowParticle(packet);
        if (config.closeBurrowDetection) burrowParticle(packet);
    }

    private static float round2(float f) {
        return Math.round(f * 100f) / 100f;
    }

    private static boolean dists(ClientboundLevelParticlesPacket p, float x, float y, float z) {
        return round2(p.xDist()) == x && round2(p.yDist()) == y && round2(p.zDist()) == z;
    }

    /** SBO's ParticleTypes: which particles mark a burrow, and its type. */
    private static void burrowParticle(ClientboundLevelParticlesPacket p) {
        var type = p.particle().getType();
        BlockPos pos = blockOf(p.x(), p.y(), p.z()).below();
        // The smoke of a burrow that's gone.
        if (type == ParticleTypes.LARGE_SMOKE && p.xMaxSpeed() == 0.01f && p.xDist() == 0f && p.yDist() == 0f && p.zDist() == 0f) {
            markRecentlyRemoved(pos);
            burrows.remove(pos);
            arrows.remove(pos);
            spadeGuesses.removeIf(w -> w.pos.equals(pos));
            for (GuessChain chain : chains) chain.removeSubGuess(pos);
            removeOrMoveChainsAt(pos);
            return;
        }
        Kind kind = null;
        if (type == ParticleTypes.ENCHANTED_HIT && p.count() == 4 && p.xMaxSpeed() == 0.01f && dists(p, 0.5f, 0.1f, 0.5f)) kind = Kind.START;
        else if (type == ParticleTypes.CRIT && p.count() == 3 && p.xMaxSpeed() == 0.01f && dists(p, 0.5f, 0.1f, 0.5f)) kind = Kind.MOB;
        else if (type == ParticleTypes.DRIPPING_LAVA && p.count() == 2 && p.xMaxSpeed() == 0.01f && dists(p, 0.35f, 0.1f, 0.35f)) kind = Kind.TREASURE;
        if (kind == null || wasRecentlyRemoved(pos)) return;
        registerBurrow(pos, kind, -1);
    }

    private static void registerBurrow(BlockPos pos, Kind kind, int carriedTimesDug) {
        FeatureConfigs.DianaBurrows config = config();
        if (config == null || !config.closeBurrowDetection) return;
        removeChainsWithSubGuess(pos);
        removeChainsAt(pos);
        Waypoint spade = spadeAt(pos);
        if (burrows.containsKey(pos)) {
            if (spade != null) spadeGuesses.remove(spade);
            return;
        }
        Waypoint arrow = arrows.get(pos);
        int timesDug = carriedTimesDug >= 0 ? carriedTimesDug
            : arrow != null ? arrow.timesDug : spade != null ? spade.timesDug : 0;
        if (spade != null) spadeGuesses.remove(spade);
        Waypoint wp = new Waypoint(pos, kind);
        wp.timesDug = timesDug;
        burrows.put(pos, wp);
    }

    // ------------------------------------------------------------------------------------------------ spade guess

    private static void onUseItem(ItemStack stack) {
        FeatureConfigs.DianaBurrows config = config();
        if (config == null || !config.spadeGuess || !inHub() || !isSpade(stack)) return;
        lavaParticles.clear();
        lastGuessTime = System.currentTimeMillis();
        spadeGuessThisUse = false;
    }

    /** SBO's PreciseGuessBurrow: the spade's trail of lava particles, fitted with a curve and followed to the ground. */
    private static void spadeParticle(ClientboundLevelParticlesPacket p) {
        if (p.particle().getType() != ParticleTypes.DRIPPING_LAVA || p.count() != 2 || p.xMaxSpeed() != -0.5f) return;
        if (System.currentTimeMillis() - lastGuessTime > 3_000L) return;
        Vec3 loc = new Vec3(p.x(), p.y(), p.z());
        if (lavaParticles.isEmpty()) {
            lavaParticles.add(loc);
            return;
        }
        double distToLast = lavaParticles.get(lavaParticles.size() - 1).distanceTo(loc);
        if (distToLast > 3 || distToLast == 0) return;
        lavaParticles.add(loc);
        Vec3 guess = guessFromParticles();
        if (guess == null) return;
        BlockPos pos = blockOf(guess.x, guess.y - 0.5, guess.z);
        if (burrows.containsKey(pos) || spadeAt(pos) != null || wasRecentlyRemoved(pos)) return;
        // The trail keeps refining one guess; only a new spade use counts as a new guess.
        if (!spadeGuessThisUse) dropOldGuesses();
        spadeGuessThisUse = true;
        spadeGuesses.add(new Waypoint(pos, Kind.SPADE));
    }

    private static Vec3 guessFromParticles() {
        if (lavaParticles.size() < 4) return null;
        try {
            double[][] coefficients = new double[3][];
            for (int axis = 0; axis < 3; axis++) {
                List<double[]> xs = new ArrayList<>();
                List<Double> ys = new ArrayList<>();
                for (int i = 0; i < lavaParticles.size(); i++) {
                    Vec3 v = lavaParticles.get(i);
                    double[] row = new double[4];
                    for (int d = 0; d <= 3; d++) row[d] = Math.pow(i, d);
                    xs.add(row);
                    ys.add(axis == 0 ? v.x : axis == 1 ? v.y : v.z);
                }
                coefficients[axis] = fit(xs, ys);
            }
            Vec3 derivative = new Vec3(derivativeAt(coefficients[0], 0), derivativeAt(coefficients[1], 0), derivativeAt(coefficients[2], 0));
            double pitch = pitchFromDerivative(derivative);
            double controlPointDistance = Math.sqrt(24 * Math.sin(pitch - Math.PI) + 25);
            double t = 3 * controlPointDistance / derivative.length();
            return new Vec3(evaluate(coefficients[0], t), evaluate(coefficients[1], t), evaluate(coefficients[2], t));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Least squares: (XᵀX)⁻¹Xᵀy, as SBO's PolynomialFitter. */
    private static double[] fit(List<double[]> xs, List<Double> ys) {
        int n = xs.get(0).length;
        double[][] xtx = new double[n][n];
        double[] xty = new double[n];
        for (int r = 0; r < xs.size(); r++) {
            double[] row = xs.get(r);
            for (int i = 0; i < n; i++) {
                xty[i] += row[i] * ys.get(r);
                for (int j = 0; j < n; j++) xtx[i][j] += row[i] * row[j];
            }
        }
        double[][] inv = invert(xtx);
        double[] result = new double[n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) result[i] += inv[i][j] * xty[j];
        return result;
    }

    private static double[][] invert(double[][] m) {
        int n = m.length;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, a[i], 0, n);
            a[i][i + n] = 1;
        }
        for (int i = 0; i < n; i++) {
            int max = i;
            for (int k = i + 1; k < n; k++) if (Math.abs(a[k][i]) > Math.abs(a[max][i])) max = k;
            double[] tmp = a[i];
            a[i] = a[max];
            a[max] = tmp;
            if (Math.abs(a[i][i]) < 1e-12) throw new IllegalArgumentException("singular");
            double pivot = a[i][i];
            for (int j = 0; j < 2 * n; j++) a[i][j] /= pivot;
            for (int k = 0; k < n; k++) {
                if (k == i) continue;
                double factor = a[k][i];
                for (int j = 0; j < 2 * n; j++) a[k][j] -= factor * a[i][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], n, inv[i], 0, n);
        return inv;
    }

    private static double evaluate(double[] c, double t) {
        double r = 0;
        for (int i = c.length - 1; i >= 0; i--) r = r * t + c[i];
        return r;
    }

    private static double derivativeAt(double[] c, double t) {
        double r = 0;
        for (int i = c.length - 1; i >= 1; i--) r = r * t + c[i] * i;
        return r;
    }

    private static double pitchFromDerivative(Vec3 d) {
        double xz = Math.sqrt(d.x * d.x + d.z * d.z);
        double pitch = -Math.atan2(d.y, xz);
        double guess = pitch, min = -Math.PI / 2, max = Math.PI / 2;
        for (int i = 0; i < 100; i++) {
            double result = Math.atan2(Math.sin(guess) - 0.75, Math.cos(guess));
            if (result == pitch) return guess;
            if (result < pitch) min = guess;
            else max = guess;
            guess = (min + max) / 2;
        }
        return guess;
    }

    // ------------------------------------------------------------------------------------------------ arrow guess

    /** SBO's ArrowGuessBurrow: the dust arrow after each burrow points at the next one. */
    private static void arrowParticle(ClientboundLevelParticlesPacket p) {
        if (p.particle().getType() != ParticleTypes.DUST || !(p.particle() instanceof DustParticleOptions)) return;
        if (p.count() != 0 || p.xMaxSpeed() != 1.0f) return;
        Vec3 loc = new Vec3(p.x(), p.y(), p.z());
        if (lastWaypointClicked != null && loc.distanceTo(Vec3.atLowerCornerOf(lastWaypointClicked)) > 7) return;
        int[] range = arrowRange(p.xDist(), p.yDist(), p.zDist());
        if (range == null) return;
        arrowParticles.add(loc);
        Vec3[] ray = detectArrow();
        if (ray == null) return;
        long now = System.currentTimeMillis();
        recentArrows.values().removeIf(at -> now - at > 18_000L);
        String key = ray[0] + "|" + ray[1];
        if (recentArrows.putIfAbsent(key, now) != null) return;
        arrowParticles.clear();
        guessAlongRay(ray[0], ray[1], range);
    }

    /** The arrow's colour says how far the burrow is: yellow close, red medium, black far. */
    private static int[] arrowRange(float x, float y, float z) {
        if (x == 0f && y == 128f && z == 0f) return new int[]{0, 117};
        if (x == 255f && y == 255f && z == 0f) return new int[]{112, 282};
        if (x == 255f && y == 0f && z == 0f) return new int[]{281, 600};
        return null;
    }

    /** {origin, direction} of the arrow, once a straight shaft of particles with a tip is found. */
    private static Vec3[] detectArrow() {
        List<Vec3> line = findLine();
        if (line.isEmpty()) return null;
        int count1 = pointsNear(line.get(1));
        int count2 = pointsNear(line.get(line.size() - 2));
        if (!(count1 == COUNT_NEAR_BASE && count2 == COUNT_NEAR_TIP || count1 == COUNT_NEAR_TIP && count2 == COUNT_NEAR_BASE)) return null;
        Vec3 base, tip;
        if (count1 == COUNT_NEAR_TIP) {
            base = line.get(line.size() - 1);
            tip = line.get(0);
        } else {
            base = line.get(0);
            tip = line.get(line.size() - 1);
        }
        Vec3 origin = base.add(0, -1.5, 0);
        Vec3 direction = tip.add(0, -1.5, 0).subtract(origin).normalize();
        return new Vec3[]{origin, direction};
    }

    private static List<Vec3> findLine() {
        List<Vec3> best = List.of();
        double bestScore = Double.POSITIVE_INFINITY;
        for (Vec3 start : arrowParticles) {
            List<Vec3> line = new ArrayList<>();
            Set<Vec3> visited = new HashSet<>();
            line.add(start);
            visited.add(start);
            if (!extendLine(line, visited)) continue;
            double score = scoreLine(line);
            if (score < bestScore || (score == bestScore && line.size() > best.size())) {
                bestScore = score;
                best = line;
            }
        }
        return best;
    }

    private static boolean extendLine(List<Vec3> line, Set<Vec3> visited) {
        while (line.size() < SHAFT_LENGTH) {
            Vec3 next = null;
            double minDist = Double.MAX_VALUE;
            for (Vec3 location : arrowParticles) {
                if (visited.contains(location)) continue;
                double dist = line.get(line.size() - 1).distanceTo(location);
                if (dist > PARTICLE_TOLERANCE) continue;
                Vec3 second = line.size() > 1 ? line.get(1) : line.get(0);
                if (!collinear(line.get(0), second, location)) continue;
                if (dist < minDist) {
                    minDist = dist;
                    next = location;
                }
            }
            if (next == null) return false;
            line.add(next);
            visited.add(next);
        }
        return true;
    }

    private static double scoreLine(List<Vec3> line) {
        if (line.size() < 2) return Double.POSITIVE_INFINITY;
        Vec3 origin = line.get(0);
        Vec3 direction = line.get(line.size() - 1).subtract(origin).normalize();
        double sum = 0;
        for (Vec3 point : line) {
            double projection = direction.dot(point.subtract(origin));
            sum += point.distanceTo(origin.add(direction.scale(projection)));
        }
        return sum;
    }

    private static boolean collinear(Vec3 a, Vec3 b, Vec3 c) {
        return b.subtract(a).cross(c.subtract(a)).lengthSqr() < EPSILON;
    }

    private static int pointsNear(Vec3 origin) {
        double max = PARTICLE_TOLERANCE * PARTICLE_TOLERANCE;
        int count = 0;
        for (Vec3 v : arrowParticles) if (!v.equals(origin) && v.distanceToSqr(origin) <= max) count++;
        return count;
    }

    /** SBO's findClosestValidBlockToRayNew: grass blocks along the arrow, within its range, closest to the ray first. */
    private static void guessAlongRay(Vec3 origin, Vec3 direction, int[] range) {
        if (!insideVec(origin)) return;
        Vec3 end = exitPoint(origin, direction);
        if (end == null) return;
        double[] diff = {end.x - origin.x, end.y - origin.y, end.z - origin.z};
        int axis = -1;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(diff[i]) > 0.9 && (axis < 0 || Math.abs(diff[i]) < Math.abs(diff[axis]))) axis = i;
        }
        if (axis < 0) return;
        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {direction.x, direction.y, direction.z};
        if (Math.abs(d[axis]) < 1e-12) return;
        Map<BlockPos, double[]> candidates = new LinkedHashMap<>();
        int iterations = (int) Math.abs(diff[axis]);
        for (int i = 1; i <= iterations; i++) {
            double value = o[axis] + i * Math.signum(d[axis]);
            double t = (value - o[axis]) / d[axis];
            Vec3 point = origin.add(direction.scale(t));
            BlockPos block = blockOf(point.x, point.y, point.z);
            if (!isBlockValid(block)) continue;
            double toRay = distanceToRay(origin, direction, Vec3.atCenterOf(block));
            double fromOrigin = point.distanceTo(origin);
            candidates.put(block, new double[]{toRay * 500000 / fromOrigin, fromOrigin});
        }
        if (candidates.isEmpty()) return;
        double bestScore = Double.MAX_VALUE;
        for (double[] v : candidates.values()) bestScore = Math.min(bestScore, v[0]);
        List<BlockPos> within = new ArrayList<>();
        for (Map.Entry<BlockPos, double[]> e : candidates.entrySet()) {
            double[] v = e.getValue();
            if (Math.abs(v[0] - bestScore) <= 1e-6 && (int) v[1] >= range[0] && (int) v[1] <= range[1]) within.add(e.getKey());
        }
        if (within.isEmpty()) return;
        GuessChain chain = new GuessChain(within);
        for (GuessChain other : chains) if (other.sameAs(chain)) return;
        dropOldGuesses();
        chains.add(chain);
        addArrowGuess(chain.current());
    }

    /** Where the ray leaves the Hub's burrow area. */
    private static Vec3 exitPoint(Vec3 origin, Vec3 direction) {
        double[] min = {HUB_BOUNDS.minX, HUB_BOUNDS.minY, HUB_BOUNDS.minZ};
        double[] max = {HUB_BOUNDS.maxX, HUB_BOUNDS.maxY, HUB_BOUNDS.maxZ};
        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {direction.x, direction.y, direction.z};
        double tmin = -Double.MAX_VALUE, tmax = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < min[i] || o[i] > max[i]) return null;
                continue;
            }
            double t1 = (min[i] - o[i]) / d[i], t2 = (max[i] - o[i]) / d[i];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return null;
        }
        return origin.add(direction.scale(tmax));
    }

    private static double distanceToRay(Vec3 origin, Vec3 direction, Vec3 point) {
        double along = direction.dot(point.subtract(origin));
        if (along < 0) return Double.MAX_VALUE;
        return origin.add(direction.scale(along)).distanceTo(point);
    }

    private static void removeChainsWithSubGuess(BlockPos pos) {
        chains.removeIf(chain -> {
            if (!chain.remaining().contains(pos)) return false;
            chain.removeAllWaypoints();
            return true;
        });
    }

    private static void removeChainsAt(BlockPos pos) {
        chains.removeIf(chain -> {
            if (!chain.current().equals(pos)) return false;
            chain.removeAllWaypoints();
            return true;
        });
    }

    private static void removeOrMoveChainsAt(BlockPos pos) {
        chains.removeIf(chain -> {
            if (!chain.current().equals(pos)) return false;
            if (chain.moveToNext()) return false;
            chain.removeAllWaypoints();
            return true;
        });
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (isSpade(mc.player.getMainHandItem())) lastSpadeHeld = now;
        recentClickedBlocks.values().removeIf(at -> now - at > 4_000L);
        handleWarpKeys(mc);
        FeatureConfigs.DianaBurrows config = config();
        if (config == null || !inHub()) return;

        if (config.arrowGuess && !chains.isEmpty()) {
            boolean hasSpade = now - lastSpadeHeld < 1_000L;
            Vec3 player = mc.player.position();
            chains.removeIf(chain -> {
                BlockPos current = chain.current();
                if (!isBlockValid(current)) return !chain.moveToNext();
                // Holding the spade within 32 blocks shows a real burrow's particles: none for a while means the guess is
                // wrong. Only Close Burrow Detection reads those particles, so without it the guess stays.
                boolean near = config.closeBurrowDetection && hasSpade && !burrows.containsKey(current)
                    && Vec3.atLowerCornerOf(current).distanceToSqr(player) <= 1024;
                if (!near) {
                    chain.nearSince = 0;
                    return false;
                }
                if (chain.nearSince == 0) chain.nearSince = now;
                if (now - chain.nearSince < NO_PARTICLES_GRACE_MS) return false;
                return !chain.moveToNext();
            });
        }

        // SBO's WaypointManager clean-up.
        spadeGuesses.removeIf(w -> !isBlockValid(w.pos));
        for (Waypoint arrow : new ArrayList<>(arrows.values())) {
            if (!isBlockValid(arrow.pos)) {
                if (now - arrow.created > 15_000L) {
                    arrows.remove(arrow.pos);
                    removeOrMoveChainsAt(arrow.pos);
                } else {
                    arrow.hidden = true;
                }
            } else {
                arrow.hidden = false;
            }
        }
        List<Waypoint> fixed = new ArrayList<>(burrows.values());
        fixed.addAll(arrows.values());
        // A spade guess near a known burrow or an arrow guess is the same burrow (arrow guesses are more precise).
        spadeGuesses.removeIf(guess -> {
            for (Waypoint other : fixed) {
                if (other.pos.equals(guess.pos) || other.pos.distSqr(guess.pos) <= 32 * 32) {
                    other.carryOver(guess);
                    return true;
                }
            }
            return false;
        });
        for (int i = 0; i < spadeGuesses.size(); i++) {
            for (int j = i + 1; j < spadeGuesses.size(); j++) {
                Waypoint a = spadeGuesses.get(i), b = spadeGuesses.get(j);
                if (a.pos.distSqr(b.pos) > 32 * 32) continue;
                Waypoint keep = a.strongerThan(b) ? a : b;
                Waypoint drop = keep == a ? b : a;
                keep.carryOver(drop);
                spadeGuesses.remove(drop);
                i = -1;
                break;
            }
        }
        for (Waypoint arrow : new ArrayList<>(arrows.values())) {
            Waypoint known = burrows.get(arrow.pos);
            if (known == null) continue;
            known.carryOver(arrow);
            arrows.remove(arrow.pos);
            removeChainsAt(arrow.pos);
        }
    }

    // ------------------------------------------------------------------------------------------------ render

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.DianaBurrows config = config();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || mc.player == null || !inHub()) return;
        for (Waypoint wp : allWaypoints()) {
            if (wp.hidden) continue;
            String colourSetting = switch (wp.kind) {
                case MOB -> config.mobColour;
                case TREASURE -> config.treasureColour;
                case START -> config.startColour;
                default -> config.guessColour;
            };
            float[] rgb = colour(colourSetting);
            collector.submitOutlinedBox(wp.pos, rgb, 3f, true);
            if (config.beaconBeam) collector.submitFilledBoxWithBeaconBeam(wp.pos, rgb, 0.3f, true);
            Vec3 label = Vec3.atCenterOf(wp.pos).add(0, 1.5, 0);
            double distance = mc.player.position().distanceTo(label);
            float scale = (float) Math.max(1, Math.min(6, distance / 10));
            int rgbInt = (int) (rgb[0] * 255) << 16 | (int) (rgb[1] * 255) << 8 | (int) (rgb[2] * 255);
            String text = wp.kind.label + " " + (int) distance + "m";
            // Digs so far out of what the burrow needs: a start burrow once, the others twice.
            String progress = config.showProgress && wp.timesDug > 0 ? "[" + wp.timesDug + "/" + (wp.kind == Kind.START ? 1 : 2) + "]" : null;
            FeatureConfigs.DianaBurrows.ProgressPosition where = config.progressPosition == null
                ? FeatureConfigs.DianaBurrows.ProgressPosition.RIGHT : config.progressPosition;
            if (progress != null && where == FeatureConfigs.DianaBurrows.ProgressPosition.RIGHT) text = text + " " + progress;
            if (progress != null && where == FeatureConfigs.DianaBurrows.ProgressPosition.LEFT) text = progress + " " + text;
            collector.submitText(Component.literal(text).withStyle(s -> s.withColor(rgbInt)), label, scale, true);
            if (progress != null && (where == FeatureConfigs.DianaBurrows.ProgressPosition.ABOVE || where == FeatureConfigs.DianaBurrows.ProgressPosition.BELOW)) {
                double offset = (where == FeatureConfigs.DianaBurrows.ProgressPosition.ABOVE ? 0.3 : -0.3) * scale;
                collector.submitText(Component.literal(progress).withStyle(ChatFormatting.WHITE), label.add(0, offset, 0), scale, true);
            }
        }
    }

    private static float[] colour(String value) {
        try {
            int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
        } catch (Exception e) {
            return new float[]{1f, 1f, 1f};
        }
    }

    // ------------------------------------------------------------------------------------------------ warp keys

    private static void handleWarpKeys(Minecraft mc) {
        if (Sky2MKeyMappings.DIANA_GUESS_WARP == null) return;
        while (Sky2MKeyMappings.DIANA_GUESS_WARP.consumeClick()) warpToGuess(mc);
        while (Sky2MKeyMappings.DIANA_RARE_MOB_WARP.consumeClick()) {
            if (mc.player == null) continue;
            Vec3 mob = DianaRareMobs.newestSharedRareMob();
            if (mob != null) warpTo(mc, mob, false);
            else warpFeedback("No shared rare mob to warp to.");
        }
    }

    private static void warpToGuess(Minecraft mc) {
        if (mc.player == null) return;
        // You press the key right after guessing: the newest guess is the one you mean.
        Waypoint newest = null;
        for (Waypoint wp : allWaypoints()) {
            if (wp.hidden || !wp.kind.guess()) continue;
            if (newest == null || wp.created >= newest.created) newest = wp;
        }
        if (newest == null) {
            warpFeedback("No burrow guess to warp to.");
            return;
        }
        if (System.currentTimeMillis() - lastSpadeHeld > 1_000L) {
            warpFeedback("Hold your spade to use the guess warp.");
            return;
        }
        warpTo(mc, Vec3.atCenterOf(newest.pos), true);
    }

    private static void warpFeedback(String message) {
        Sky2MAlerts.chat(Component.literal(message).withStyle(ChatFormatting.GRAY));
    }

    /** The guess nearest {@code from}: known burrows don't count, you'll dig them on the way. */
    private static Vec3 bestGuessAt(Vec3 from) {
        Vec3 best = null;
        for (Waypoint wp : allWaypoints()) {
            if (wp.hidden || !wp.kind.guess()) continue;
            Vec3 p = Vec3.atCenterOf(wp.pos);
            if (best == null || p.distanceToSqr(from) < best.distanceToSqr(from)) best = p;
        }
        return best;
    }

    /**
     * SBO's getFinalClosestWarp: the warp closest to the target, following on to the next guess from there when
     * {@code followGuesses} (up to ten warps ahead), so you don't warp somewhere you'd warp away from again.
     */
    private static void warpTo(Minecraft mc, Vec3 target, boolean followGuesses) {
        FeatureConfigs.DianaWarp config = warpConfig();
        if (config == null || !inHub() || mc.getConnection() == null) return;
        if (System.currentTimeMillis() - lastWarpAt < 500L) return;
        Vec3 player = mc.player.position();
        Vec3 goal = target;
        Warp last = null;
        for (int i = 0; i < 10; i++) {
            Warp warp = closestWarp(config, goal, player);
            if (warp == null || warp == last) break;
            last = warp;
            player = warp.pos();
            if (followGuesses) {
                Vec3 next = bestGuessAt(warp.pos());
                if (next == null) break;
                goal = next;
            }
        }
        if (last == null) {
            warpFeedback("No warp is closer than walking.");
            return;
        }
        lastWarpAt = System.currentTimeMillis();
        mc.getConnection().sendCommand("warp " + last.name());
    }

    /** The warp to take from {@code player} to {@code target}, or null when walking is as quick. */
    private static Warp closestWarp(FeatureConfigs.DianaWarp config, Vec3 target, Vec3 player) {
        Warp closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Warp warp : WARPS) {
            if (!allowed(config, warp.name())) continue;
            double distance = target.distanceTo(warp.pos());
            if (distance < closestDistance) {
                closest = warp;
                closestDistance = distance;
            }
        }
        if (closest == null) return null;
        double playerDistance = target.distanceTo(player);
        boolean worthIt = playerDistance > closestDistance + config.warpDiff + closest.extraBlocks();
        boolean farEnough = !config.dontWarpIfClose || (playerDistance > 60 && player.distanceTo(closest.pos()) > 60);
        return worthIt && farEnough ? closest : null;
    }

    private static boolean allowed(FeatureConfigs.DianaWarp config, String warp) {
        return switch (warp) {
            case "hub" -> true;
            case "castle" -> config.castle;
            case "wizard" -> config.wizard;
            case "crypt" -> config.crypt;
            case "stonks" -> config.stonks;
            case "da" -> config.da;
            case "taylor" -> config.taylor;
            case "museum" -> config.museum;
            default -> false;
        };
    }
}
