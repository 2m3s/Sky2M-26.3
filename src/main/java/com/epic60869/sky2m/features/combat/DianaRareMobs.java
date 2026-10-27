package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diana rare mob sharing and the lootshare helper, ported from Skysoft (https://github.com/Akinsoft/Skysoft,
 * LGPL-3.0): DianaRareMobSharing, DianaRareMobTarget, DianaRareMobShare(Parser), DianaRareMobPartyMessages,
 * DianaRareMobLootshare, DianaRareMobRenderer, DianaLootshareReadyMarkers and DianaLootshareReadyMessage, with the
 * mob matching from SkyBlockMobEntityMatcher / SkyBlockMobTextParser and the damage attribution from
 * DamageSplashText / DamageSplashAttribution.
 *
 * <p>A rare mob is tracked once you dig it up (and share it) or a party member shares it in party chat. Your hits on
 * it are matched to the damage numbers that pop up; at 1% of its health you've secured lootshare, which is said in
 * party chat ("Loot share secured!") and marked with a checkmark above the head of everyone who has.
 */
public final class DianaRareMobs {
    // ------------------------------------------------------------------------------------------------ rare mobs

    enum RareMob {
        MINOS_HUNTER("Minos Hunter", "a"),
        SIAMESE_LYNXES("Siamese Lynxes", "", "Siamese Lynx", "Bagheera", "Azrael"),
        STRANDED_NYMPH("Stranded Nymph", "a"),
        CRETAN_BULL("Cretan Bull", "a"),
        HARPY("Harpy", "a"),
        GAIA_CONSTRUCT("Gaia Construct", "a"),
        MINOTAUR("Minotaur", "a"),
        MINOS_CHAMPION("Minos Champion", "a"),
        SPHINX("Sphinx", "a"),
        MINOS_INQUISITOR("Minos Inquisitor", "a"),
        MANTICORE("Manticore", "a"),
        KING_MINOS("King Minos", "a");

        final String label;
        final String article;
        final List<String> matchLabels;

        RareMob(String label, String article, String... aliases) {
            this.label = label;
            this.article = article;
            List<String> labels = new ArrayList<>();
            labels.add(label);
            labels.addAll(List.of(aliases));
            this.matchLabels = List.copyOf(labels);
        }

        String shareMarker() {
            return "Found " + (article.isEmpty() ? "" : article + " ") + label + "!";
        }

        String clearMarker() {
            return shareMarker().replaceFirst("Found", "Defeated");
        }

        static RareMob fromLabel(String text) {
            for (RareMob mob : values()) for (String l : mob.matchLabels) if (l.equalsIgnoreCase(text.trim())) return mob;
            return null;
        }

        static RareMob fromMobName(String text) {
            String clean = text.trim();
            for (RareMob mob : values()) {
                for (String l : mob.matchLabels) if (clean.toLowerCase(Locale.ROOT).contains(l.toLowerCase(Locale.ROOT))) return mob;
            }
            return null;
        }
    }

    enum Source { LOCAL, REMOTE }

    /** A rare mob that was shared, by you or a party member. */
    static final class Target {
        final long id;
        final String key;
        final String server;
        final RareMob mob;
        String sharedBy;
        final Source source;
        final long createdAt;
        long expiresAt;
        Vec3 location;

        LivingEntity entity;
        ArmorStand nameplate;
        long currentHealth = -1;
        long maxHealth = -1;
        long lastHealthChangeAt = -1;
        long lastSeenAt = -1;
        Long nearbyWithoutSignalSince;
        long cocoonHatchUntil;

        long localDamage;
        boolean lootshareEligible;
        Attack lastAttack;
        boolean lastAttackCanDamage;
        final Set<Integer> processedSplashes = new HashSet<>();

        Target(long id, String key, String server, RareMob mob, String sharedBy, Source source, long now, Vec3 location) {
            this.id = id;
            this.key = key;
            this.server = server;
            this.mob = mob;
            this.sharedBy = sharedBy;
            this.source = source;
            this.createdAt = now;
            this.expiresAt = now + TARGET_LIFETIME_MS;
            this.location = blockOf(location);
        }

        boolean visible() {
            return entity != null || nameplate != null;
        }

        boolean awaitingCocoonHatch(long now) {
            return now < cocoonHatchUntil;
        }

        boolean deathConfirmed() {
            return currentHealth == 0 || (entity != null && entity.isDeadOrDying());
        }

        Vec3 lineLocation() {
            if (entity != null) return entity.position();
            if (nameplate != null) return nameplate.position();
            return location.add(0.5, 0.5, 0.5);
        }

        boolean spawner(String localName) {
            return localName != null && sharedBy.equalsIgnoreCase(localName);
        }

        boolean showLootshare(String localName) {
            return !spawner(localName) && visible();
        }

        void update(Signal signal, long now) {
            location = blockOf(signal.entity.position());
            entity = signal.entity;
            nameplate = signal.nameplate;
            if (signal.current >= 0) {
                if (currentHealth >= 0 && currentHealth != signal.current) lastHealthChangeAt = now;
                currentHealth = signal.current;
                maxHealth = signal.max >= 0 ? signal.max : Math.max(maxHealth, signal.current);
            }
            lastSeenAt = now;
            nearbyWithoutSignalSince = null;
            cocoonHatchUntil = 0;
        }

        void prepareForCocoonHatch(long until) {
            entity = null;
            nameplate = null;
            currentHealth = -1;
            maxHealth = -1;
            localDamage = 0;
            lootshareEligible = false;
            lastAttack = null;
            lastAttackCanDamage = false;
            nearbyWithoutSignalSince = null;
            cocoonHatchUntil = until;
            processedSplashes.clear();
        }

        /** Returns true when this damage made you eligible for lootshare. */
        boolean addDamage(long damage) {
            if (!lastAttackCanDamage || damage <= 0) return false;
            localDamage += damage;
            if (maxHealth <= 0) return false;
            long threshold = Math.max(1L, (long) (maxHealth * LOOTSHARE_DAMAGE_FRACTION));
            boolean was = lootshareEligible;
            lootshareEligible = localDamage >= threshold;
            return !was && lootshareEligible;
        }
    }

    record Attack(long at, UUID entityUuid, Vec3 targetLocation) {}

    /** A rare mob seen in the world: its nametag, the mob under it and its health. */
    record Signal(RareMob mob, LivingEntity entity, ArmorStand nameplate, long current, long max) {}

    record Pending(String server, RareMob mob, long expiresAt) {}

    record RecentDeath(String server, RareMob mob, Vec3 location, long expiresAt) {}

    // ------------------------------------------------------------------------------------------------ constants

    private static final long TARGET_LIFETIME_MS = 75_000L;
    private static final long LOCAL_SPAWN_LINK_MS = 30_000L;
    private static final long LOCAL_DEATH_LOCATION_MS = 2_000L;
    private static final long COCOON_HATCH_ATTACH_MS = 12_000L;
    private static final double REMOTE_LINK_DISTANCE = 40;
    private static final double LOCAL_SPAWN_LINK_DISTANCE = 35;
    private static final double REMOTE_MISSING_CLEAR_DISTANCE = 50;
    private static final long REMOTE_MISSING_GRACE_MS = 10_000L;
    private static final long REMOTE_LOST_GRACE_MS = 3_000L;
    private static final long REMOTE_KING_MINOS_LOST_GRACE_MS = 30_000L;
    private static final double LOOTSHARE_DAMAGE_FRACTION = 0.01;
    private static final double LOOTSHARE_RADIUS = 30;
    private static final double LOOTSHARE_RADIUS_RENDER_DISTANCE = 50;
    private static final long READY_MARKER_LIFETIME_MS = 75_000L;
    private static final String LOOTSHARE_MESSAGE = "Loot share secured!";
    private static final int RARE_MOB_COLOUR = 0xFF55FF;
    private static final int READY_COLOUR = 0x55FFFF;
    private static final int LYNX_COLOUR = 0x55FF55;
    /** The rare mobs worth lootsharing: only these glow. */
    private static final Set<RareMob> SHARABLE = Set.of(RareMob.MINOS_INQUISITOR, RareMob.MANTICORE, RareMob.KING_MINOS);
    /** How long a lynx stays marked after its last angry villager particle. */
    private static final long LYNX_PARTICLE_MS = 1_500L;
    /** Particles this recent decide which lynx it is, so a switch after a hit shows within half a second. */
    private static final long LYNX_PARTICLE_WINDOW_MS = 500L;
    /** How far to the side of a cat its particles can be (Hypixel spreads them a little). */
    private static final double LYNX_PARTICLE_RADIUS = 1.0;

    private static final String HEALTH_ZERO = "health reached zero";
    private static final String MOB_DIED = "mob died";
    private static final Set<String> BROADCAST_CLEAR_REASONS = Set.of(HEALTH_ZERO, MOB_DIED, "player died", "burrow progressed");
    private static final Set<String> LOCAL_DEATH_REASONS = Set.of(HEALTH_ZERO, MOB_DIED);

    // Damage splash attribution (DamageSplashAttributionConfig.DEFAULT).
    private static final long MAX_ATTACK_AGE_MS = 900L;
    private static final long HIGH_CONFIDENCE_ATTACK_AGE_MS = 400L;
    private static final double MAX_SPLASH_DISTANCE = 5.0;
    private static final double HIGH_CONFIDENCE_SPLASH_DISTANCE = 2.5;
    private static final long HEALTH_CHANGE_WINDOW_MS = 500L;

    // ------------------------------------------------------------------------------------------------ patterns

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final String LABELS;
    static {
        List<String> all = new ArrayList<>();
        for (RareMob mob : RareMob.values()) for (String l : mob.matchLabels) all.add(Pattern.quote(l));
        LABELS = String.join("|", all);
    }
    private static final Pattern LEGACY_FOUND = Pattern.compile("^(?<marker>Found (?:a |an )?(?<mob>" + LABELS + ")!) @ (?<x>-?\\d+) (?<y>-?\\d+) (?<z>-?\\d+)$");
    private static final Pattern COORDINATE_SHARE = Pattern.compile("^(?<marker>x:) (?<x>" + NUMBER + "),? y: (?<y>" + NUMBER + "),? z: (?<z>" + NUMBER + ")(?: \\| (?<mob>.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INQUISITOR_SPAWN = Pattern.compile("^(?<marker>A MINOS INQUISITOR) has spawned near \\[.*] at Coords (?<x>" + NUMBER + ") (?<y>" + NUMBER + ") (?<z>" + NUMBER + ")$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_CLEAR = Pattern.compile("^(?<marker>Defeated (?:by )?(?:a |an )?(?<mob>" + LABELS + ")!)$");
    private static final Pattern GENERIC_CLEAR = Pattern.compile("^(?<marker>(?:Inquisitor|Rare Diana Mob) dead!)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_DEATH = Pattern.compile("^(?:☠ )?(?<player>[A-Za-z0-9_]{1,16}) was killed by (?<killer>.+)\\.$");
    private static final Pattern PARTY_COCOON = Pattern.compile("^(?<marker>Cocooned an? (?<mob>[^!]+)!)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_COCOON = Pattern.compile("^CAUGHT! You cocooned an? (?<mob>[^!]+)!$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DUG_OUT = Pattern.compile("You dug out (?:a |an )?(?<mob>[^!§(]+)!", Pattern.CASE_INSENSITIVE);
    /** "Party > [MVP+] Name ♲: message". */
    private static final Pattern PARTY_LINE = Pattern.compile("^Party > (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>[A-Za-z0-9_]{1,16})[^:]*: (?<body>.+)$");
    private static final Pattern DAMAGE = Pattern.compile("^[✧✯]?(?<damage>[0-9,.]+[KMBkmb]?)[⚔+✧❤♞☄✷ﬗ✯]*$");
    private static final Pattern FULL_HEALTH = Pattern.compile("(?<current>[0-9,.]+[KMBkmb]?)\\s*/\\s*(?<max>[0-9,.]+[KMBkmb]?)");
    private static final Pattern CURRENT_HEALTH = Pattern.compile("(?<current>[0-9,.]+[KMBkmb]?)❤");
    private static final Pattern LEVEL_PREFIX = Pattern.compile("^\\[Lv\\d+]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIER_SUFFIX = Pattern.compile("\\s+[IVX]+$");
    private static final Set<String> MODIFIER_PREFIXES = Set.of("Empyrean", "Exalted", "Runic", "Venerable", "Stalwart", "Blessed");

    // ------------------------------------------------------------------------------------------------ state

    private static final Map<String, Target> targets = new LinkedHashMap<>();
    private static final List<Pending> pendingLocalSpawns = new ArrayList<>();
    private static final List<RecentDeath> recentLocalDeaths = new ArrayList<>();
    /** Lowercase name -> (name, expiry) of players who said "Loot share secured!". */
    private static final Map<String, Map.Entry<String, Long>> readyPlayers = new HashMap<>();
    private static Map<Entity, Integer> glow = Map.of();
    /** Recent angry villager particles and when they came: they're over the Siamese Lynx you can hit. */
    private static final List<Map.Entry<Vec3, Long>> lynxParticles = new ArrayList<>();
    private static LivingEntity hittableLynx;
    private static long lynxSeenAt;
    private static long nextTargetId;
    private static int ticks;
    private static String serverName;
    private static long serverReadAt;

    private DianaRareMobs() {}

    private static FeatureConfigs.Diana config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.events.diana;
    }

    private static boolean onHub() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("Hub");
    }

    private static boolean enabledOnHub(FeatureConfigs.Diana config) {
        return config != null && (config.rareMobSharing || config.lootshare) && onHub();
    }

    private static String localName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getGameProfile().name();
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            clear();
            PartyChat.reset();
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || onMessage(message));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) onAttack(entity);
            return InteractionResult.PASS;
        });
        Sky2MWorldRender.register(DianaRareMobs::render);
        EntityGlow.register(entity -> glow.isEmpty() ? -1 : glow.getOrDefault(entity, -1));
    }

    /** The Hypixel server you're on ("mini84BT"), from the tab list's "Server:" line. */
    private static String server() {
        long now = System.currentTimeMillis();
        if (now - serverReadAt < 1_000L) return serverName;
        serverReadAt = now;
        String found = null;
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = com.epic60869.sky2m.custom.util.Compat.rawTabName(info);
            if (name == null) continue;
            String text = Sky2MLocation.strip(name.getString()).trim();
            if (text.startsWith("Server: ")) {
                found = text.substring(8).trim();
                break;
            }
        }
        serverName = found == null || found.isBlank() ? null : found;
        return serverName;
    }

    private static List<Target> currentTargets() {
        String server = server();
        if (server == null) return List.of();
        List<Target> list = new ArrayList<>();
        for (Target t : targets.values()) if (t.server.equals(server)) list.add(t);
        return list;
    }

    /** Where the newest rare mob a party member shared is, or null (the Diana rare mob warp key). */
    public static Vec3 newestSharedRareMob() {
        return currentTargets().stream().filter(t -> t.source == Source.REMOTE)
            .max(Comparator.comparingLong((Target t) -> t.createdAt).thenComparingLong(t -> t.id))
            .map(Target::lineLocation).orElse(null);
    }

    /** The rare mobs shared in party chat and received from it (Shared Mobs). */
    private static Set<RareMob> sharedMobs(FeatureConfigs.Diana config) {
        FeatureConfigs.DianaSharedMobs picked = config.sharedMobs == null ? new FeatureConfigs.DianaSharedMobs() : config.sharedMobs;
        Set<RareMob> mobs = java.util.EnumSet.noneOf(RareMob.class);
        if (picked.inquisitor) mobs.add(RareMob.MINOS_INQUISITOR);
        if (picked.kingMinos) mobs.add(RareMob.KING_MINOS);
        if (picked.manticore) mobs.add(RareMob.MANTICORE);
        if (picked.sphinx) mobs.add(RareMob.SPHINX);
        return mobs;
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick() {
        PartyChat.tick();
        FeatureConfigs.Diana config = config();
        long now = System.currentTimeMillis();
        readyPlayers.values().removeIf(e -> now >= e.getValue());
        updateHittableLynx(config, now);
        if (config == null || (!config.rareMobSharing && !config.lootshare)) {
            if (!targets.isEmpty() || !pendingLocalSpawns.isEmpty()) clear();
            glow = hittableLynx == null ? Map.of() : Map.of(hittableLynx, LYNX_COLOUR);
            return;
        }
        pruneTargets(now);
        pendingLocalSpawns.removeIf(p -> now >= p.expiresAt());
        recentLocalDeaths.removeIf(d -> now >= d.expiresAt());
        if (!config.rareMobSharing) {
            pendingLocalSpawns.clear();
            recentLocalDeaths.clear();
            for (Target t : List.copyOf(targets.values())) if (t.source == Source.LOCAL) clearTarget(t, "sharing disabled", false);
        }
        Minecraft mc = Minecraft.getInstance();
        if (!onHub() || mc.level == null || mc.player == null) {
            readyPlayers.clear();
            glow = Map.of();
            return;
        }
        detachUnloaded();

        List<Signal> signals = null;
        if (++ticks % 2 == 0 && (!currentPending().isEmpty() || !currentTargets().isEmpty())) signals = visibleSignals(mc);
        if (signals != null) {
            if (config.rareMobSharing) trySharePending(config, signals, now);
            linkTargets(signals, now);
        }
        List<Target> active = currentTargets();
        if (signals != null) pruneStaleRemoteTargets(active, now, mc.player.position());
        if (config.lootshare) scanDamageSplashes(mc, config, active, now);
        else readyPlayers.clear();
        updateGlow(config, active);
        if (active.isEmpty()) readyPlayers.clear();
    }

    private static List<Pending> currentPending() {
        String server = server();
        if (server == null) return List.of();
        return pendingLocalSpawns.stream().filter(p -> p.server().equals(server)).toList();
    }

    /** A tracked mob or nametag that was removed from the world: it died, or went out of range. */
    private static void detachUnloaded() {
        for (Target t : currentTargets()) {
            boolean entityGone = t.entity != null && t.entity.isRemoved();
            boolean nameplateGone = t.nameplate != null && t.nameplate.isRemoved();
            if (!entityGone && !nameplateGone) continue;
            String reason = null;
            if (t.currentHealth == 0) reason = HEALTH_ZERO;
            else if (entityGone && t.entity.isDeadOrDying()) reason = MOB_DIED;
            if (reason != null) {
                clearTarget(t, reason);
                continue;
            }
            if (entityGone) t.entity = null;
            if (nameplateGone) t.nameplate = null;
        }
    }

    private static void pruneTargets(long now) {
        for (Target t : List.copyOf(targets.values())) {
            if (now >= t.expiresAt) clearTarget(t, "expired");
            else if (t.awaitingCocoonHatch(now)) continue;
            else if (t.currentHealth == 0) clearTarget(t, HEALTH_ZERO);
            else if (t.deathConfirmed()) clearTarget(t, MOB_DIED);
        }
    }

    private static void trySharePending(FeatureConfigs.Diana config, List<Signal> signals, long now) {
        Minecraft mc = Minecraft.getInstance();
        String server = server();
        if (server == null) return;
        Vec3 player = mc.player.position();
        var iterator = pendingLocalSpawns.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            if (!pending.server().equals(server)) continue;
            Signal closest = signals.stream()
                .filter(s -> s.mob() == pending.mob() && s.entity().position().distanceTo(player) <= LOCAL_SPAWN_LINK_DISTANCE)
                .min(Comparator.comparingDouble(s -> s.entity().position().distanceToSqr(player))).orElse(null);
            if (closest != null) {
                String local = localName();
                if (local == null) continue;
                Vec3 location = blockOf(closest.entity().position());
                Target target = rememberShare(pending.mob(), location, local, Source.LOCAL, server, now);
                target.update(closest, now);
                PartyChat.send(formatShare(pending.mob(), location));
                iterator.remove();
            } else if (now >= pending.expiresAt()) {
                iterator.remove();
            }
        }
    }

    private static Target rememberShare(RareMob mob, Vec3 location, String sender, Source source, String server, long now) {
        Vec3 block = blockOf(location);
        String key = server + ":" + sender.toLowerCase(Locale.ROOT) + ":" + mob.name() + ":" + (int) block.x + "," + (int) block.y + "," + (int) block.z;
        Target target = targets.computeIfAbsent(key, k -> new Target(++nextTargetId, k, server, mob, sender, source, now, location));
        target.sharedBy = sender;
        target.expiresAt = Math.max(target.expiresAt, now + TARGET_LIFETIME_MS);
        return target;
    }

    private static void linkTargets(List<Signal> signals, long now) {
        for (Target target : currentTargets()) {
            boolean awaitingHatch = target.awaitingCocoonHatch(now);
            Signal match = null;
            if (target.entity != null) {
                UUID uuid = target.entity.getUUID();
                for (Signal s : signals) {
                    if (s.mob() == target.mob && s.entity().getUUID().equals(uuid)) {
                        match = s;
                        break;
                    }
                }
            } else {
                double best = Double.MAX_VALUE;
                Vec3 line = target.lineLocation();
                for (Signal s : signals) {
                    if (s.mob() != target.mob || (awaitingHatch && s.current() == 0)) continue;
                    double distance = s.entity().position().distanceTo(line);
                    if (distance <= REMOTE_LINK_DISTANCE && distance < best) {
                        best = distance;
                        match = s;
                    }
                }
            }
            if (match == null) continue;
            if (match.current() == 0) {
                if (!target.awaitingCocoonHatch(now)) clearTarget(target, HEALTH_ZERO);
                continue;
            }
            target.update(match, now);
        }
    }

    private static void pruneStaleRemoteTargets(List<Target> active, long now, Vec3 player) {
        for (Target t : active) {
            if (t.source != Source.REMOTE) continue;
            if (t.awaitingCocoonHatch(now) || t.visible() || t.lineLocation().distanceTo(player) > REMOTE_MISSING_CLEAR_DISTANCE) {
                t.nearbyWithoutSignalSince = null;
                continue;
            }
            if (t.nearbyWithoutSignalSince == null) t.nearbyWithoutSignalSince = now;
            long grace = t.lastSeenAt < 0 ? REMOTE_MISSING_GRACE_MS : t.mob == RareMob.KING_MINOS ? REMOTE_KING_MINOS_LOST_GRACE_MS : REMOTE_LOST_GRACE_MS;
            if (now - t.nearbyWithoutSignalSince >= grace) {
                clearTarget(t, t.lastSeenAt < 0 ? "not found after arrival" : "lost after arrival", false);
            }
        }
    }

    private static void clearTarget(Target target, String reason) {
        boolean broadcast = target.server.equals(server()) && target.source == Source.LOCAL && BROADCAST_CLEAR_REASONS.contains(reason);
        clearTarget(target, reason, broadcast);
    }

    private static void clearTarget(Target target, String reason, boolean broadcast) {
        targets.remove(target.key);
        if (broadcast && target.source == Source.LOCAL && LOCAL_DEATH_REASONS.contains(reason)) {
            recentLocalDeaths.add(new RecentDeath(target.server, target.mob, blockOf(target.lineLocation()), System.currentTimeMillis() + LOCAL_DEATH_LOCATION_MS));
        }
        if (broadcast) PartyChat.send(target.mob.clearMarker());
    }

    private static void clear() {
        targets.clear();
        pendingLocalSpawns.clear();
        recentLocalDeaths.clear();
        readyPlayers.clear();
        glow = Map.of();
        hittableLynx = null;
        lynxParticles.clear();
        nextTargetId = 0;
        ticks = 0;
    }

    // ------------------------------------------------------------------------------------------------ mob matching

    /** Rare mobs in the world: nametags with a rare mob's name, paired with the mob under them. */
    private static List<Signal> visibleSignals(Minecraft mc) {
        List<Entity> entities = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) entities.add(e);
        Map<Integer, Entity> byId = new HashMap<>();
        for (Entity e : entities) byId.put(e.getId(), e);
        Map<UUID, Signal> found = new LinkedHashMap<>();
        for (Entity e : entities) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            String text = Sky2MLocation.strip(stand.getCustomName().getString());
            RareMob mob = matchLabel(text);
            if (mob == null) continue;
            LivingEntity physical = physicalEntity(mc, stand, byId, entities);
            if (physical == null) continue;
            long[] health = parseHealth(text);
            Signal signal = new Signal(mob, physical, stand, health[0], health[1]);
            Signal previous = found.get(physical.getUUID());
            // Prefer the nametag right after the mob (id + 1), as Hypixel spawns them.
            if (previous == null || (stand.getId() == physical.getId() + 1 && previous.nameplate().getId() != physical.getId() + 1)) {
                found.put(physical.getUUID(), signal);
            }
        }
        return new ArrayList<>(found.values());
    }

    private static RareMob matchLabel(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean any = false;
        for (RareMob mob : RareMob.values()) {
            for (String l : mob.matchLabels) if (lower.contains(l.toLowerCase(Locale.ROOT))) any = true;
        }
        if (!any) return null;
        String name = parseName(text);
        if (name == null) name = text;
        name = TIER_SUFFIX.matcher(name).replaceAll("").trim();
        for (String prefix : MODIFIER_PREFIXES) {
            if (name.regionMatches(true, 0, prefix + " ", 0, prefix.length() + 1)) {
                name = name.substring(prefix.length() + 1);
                break;
            }
        }
        // Longest labels first, so "Siamese Lynxes" wins over "Siamese Lynx".
        RareMob best = null;
        int bestLength = -1;
        for (RareMob mob : RareMob.values()) {
            for (String l : mob.matchLabels) {
                if (name.equalsIgnoreCase(l) && l.length() > bestLength) {
                    best = mob;
                    bestLength = l.length();
                }
            }
        }
        return best;
    }

    /** "[Lv750] ♆ Minos Inquisitor 40M/40M❤" -> "Minos Inquisitor". */
    private static String parseName(String text) {
        int start = healthStart(text);
        if (start < 0) return null;
        String before = LEVEL_PREFIX.matcher(text.substring(0, start).trim()).replaceAll("");
        int i = 0;
        while (i < before.length() && !Character.isLetterOrDigit(before.charAt(i))) i++;
        String name = before.substring(i).trim();
        return name.isEmpty() ? null : name;
    }

    private static int healthStart(String text) {
        Matcher full = FULL_HEALTH.matcher(text);
        if (full.find()) return full.start();
        Matcher current = CURRENT_HEALTH.matcher(text);
        return current.find() ? current.start() : -1;
    }

    /** {current, max}, -1 where unknown. */
    private static long[] parseHealth(String text) {
        Matcher full = FULL_HEALTH.matcher(text);
        if (full.find()) {
            long current = compact(full.group("current"));
            long max = compact(full.group("max"));
            if (current >= 0 && max >= 0) return new long[]{current, max};
        }
        Matcher current = CURRENT_HEALTH.matcher(text);
        if (current.find()) return new long[]{compact(current.group("current")), -1};
        return new long[]{-1, -1};
    }

    private static long compact(String value) {
        String v = value.replace(",", "").trim();
        if (v.isEmpty()) return -1;
        double multiplier = 1;
        char last = Character.toUpperCase(v.charAt(v.length() - 1));
        if (last == 'K' || last == 'M' || last == 'B') {
            multiplier = last == 'K' ? 1e3 : last == 'M' ? 1e6 : 1e9;
            v = v.substring(0, v.length() - 1);
        }
        try {
            return (long) (Double.parseDouble(v) * multiplier);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The mob a nametag belongs to: the entity spawned just before it, or the only mob right under it. */
    private static LivingEntity physicalEntity(Minecraft mc, ArmorStand nameplate, Map<Integer, Entity> byId, List<Entity> entities) {
        Entity before = byId.get(nameplate.getId() - 1);
        if (before instanceof ArmorStand model && !model.isMarker() && tightPair(model, nameplate)) return model;
        LivingEntity single = null;
        int count = 0;
        for (Entity e : entities) {
            if (!(e instanceof LivingEntity living) || !possibleMob(mc, living) || !tightPair(living, nameplate)) continue;
            if (living.getId() == nameplate.getId() - 1) return living;
            single = living;
            count++;
        }
        return count == 1 ? single : null;
    }

    private static boolean possibleMob(Minecraft mc, LivingEntity entity) {
        if (entity == mc.player || entity instanceof ArmorStand) return false;
        // Real players have version 4 UUIDs; Hypixel's player-shaped mobs don't.
        return !(entity instanceof Player) || entity.getUUID().version() != 4;
    }

    private static boolean tightPair(LivingEntity entity, ArmorStand nameplate) {
        double dx = entity.getX() - nameplate.getX();
        double dz = entity.getZ() - nameplate.getZ();
        double dy = nameplate.getY() - entity.getY();
        return dx * dx + dz * dz <= 1.0 && dy >= 0 && dy <= 4.0;
    }

    // ------------------------------------------------------------------------------------------------ lootshare

    /** Only damage dealt with a Griffin pet out counts (the tab list's Pet widget); unknown counts as yes. */
    private static boolean canDamageRareMob() {
        var pet = Sky2MTabWidgetManager.get("Pet");
        List<Component> lines = new ArrayList<>(pet.lines());
        lines.add(pet.detail());
        boolean anyPet = false;
        for (Component line : lines) {
            String text = Sky2MLocation.strip(line.getString());
            if (text.contains("[Lvl ")) {
                anyPet = true;
                if (text.contains("Griffin")) return true;
            }
        }
        return !anyPet;
    }

    private static void onAttack(Entity entity) {
        FeatureConfigs.Diana config = config();
        if (config == null || !config.lootshare || !onHub()) return;
        for (Target target : currentTargets()) {
            boolean hit = (target.entity != null && target.entity.getUUID().equals(entity.getUUID()))
                || (target.nameplate != null && target.nameplate.getUUID().equals(entity.getUUID()));
            if (!hit) continue;
            target.lastAttack = new Attack(System.currentTimeMillis(), entity.getUUID(), entity.position());
            target.lastAttackCanDamage = canDamageRareMob();
            return;
        }
    }

    /** Damage numbers near a rare mob you're lootsharing, matched to your last hit on it. */
    private static void scanDamageSplashes(Minecraft mc, FeatureConfigs.Diana config, List<Target> active, long now) {
        String local = localName();
        List<Target> showing = active.stream().filter(t -> t.showLootshare(local) && t.lastAttack != null).toList();
        if (showing.isEmpty()) return;
        for (Target target : showing) {
            AABB area = new AABB(target.lineLocation(), target.lineLocation()).inflate(MAX_SPLASH_DISTANCE + 2);
            for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, area, ArmorStand::hasCustomName)) {
                long damage = parseDamage(Sky2MLocation.strip(stand.getCustomName().getString()));
                if (damage < 0) continue;
                attribute(config, stand, damage, showing, now);
            }
        }
    }

    private static long parseDamage(String name) {
        Matcher m = DAMAGE.matcher(name.replace(",", "").trim());
        return m.matches() ? compact(m.group("damage")) : -1;
    }

    /** As DamageSplashAttribution: the target with the best score takes the splash; each splash counts once. */
    private static void attribute(FeatureConfigs.Diana config, ArmorStand splash, long damage, List<Target> candidates, long now) {
        Target best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestScore = Double.MAX_VALUE;
        List<Target> scored = new ArrayList<>();
        for (Target t : candidates) {
            if (t.processedSplashes.contains(splash.getId()) || t.lastAttack == null) continue;
            long age = now - t.lastAttack.at();
            if (age < 0 || age > MAX_ATTACK_AGE_MS) continue;
            Vec3 at = splash.position();
            double attackDistance = at.distanceTo(t.lastAttack.targetLocation());
            // The mob's position and where it was when you hit it both count as the target.
            double targetDistance = Math.min(at.distanceTo(t.lineLocation()), attackDistance);
            double bestDistance = targetDistance;
            if (bestDistance > MAX_SPLASH_DISTANCE) continue;
            boolean exact = (t.entity != null && t.entity.getUUID().equals(t.lastAttack.entityUuid()))
                || (t.nameplate != null && t.nameplate.getUUID().equals(t.lastAttack.entityUuid()));
            boolean healthConfirmed = t.lastHealthChangeAt >= 0 && Math.abs(now - t.lastHealthChangeAt) <= HEALTH_CHANGE_WINDOW_MS;
            boolean high = age <= HIGH_CONFIDENCE_ATTACK_AGE_MS && bestDistance <= HIGH_CONFIDENCE_SPLASH_DISTANCE
                && (exact || attackDistance <= HIGH_CONFIDENCE_SPLASH_DISTANCE)
                && (healthConfirmed || targetDistance <= HIGH_CONFIDENCE_SPLASH_DISTANCE);
            int rank = high ? 0 : 1;
            double score = attackDistance + targetDistance + age / 1000.0 + (exact ? -8.0 : 0) + (healthConfirmed ? -2.0 : 0);
            scored.add(t);
            if (rank < bestRank || (rank == bestRank && score < bestScore)) {
                best = t;
                bestRank = rank;
                bestScore = score;
            }
        }
        for (Target t : scored) t.processedSplashes.add(splash.getId());
        if (best != null && best.addDamage(damage)) lootshareSecured(config);
    }

    /** You've done enough damage: check yourself off and tell the party. */
    private static void lootshareSecured(FeatureConfigs.Diana config) {
        String local = localName();
        if (config.partyCheckmarks && local != null) markReady(local, System.currentTimeMillis());
        if (config.shareSecuredMessage) PartyChat.send(LOOTSHARE_MESSAGE);
    }

    private static void markReady(String name, long now) {
        readyPlayers.put(name.toLowerCase(Locale.ROOT), Map.entry(name, now + READY_MARKER_LIFETIME_MS));
    }

    private static void updateGlow(FeatureConfigs.Diana config, List<Target> active) {
        Map<Entity, Integer> map = new HashMap<>();
        if (hittableLynx != null) map.put(hittableLynx, LYNX_COLOUR);
        String local = localName();
        for (Target t : active) {
            if (t.entity == null || !t.entity.isAlive() || !SHARABLE.contains(t.mob)) continue;
            int colour = t.spawner(local) || !config.lootshare ? RARE_MOB_COLOUR
                : rgb(t.lootshareEligible ? config.lootshareReadyColor : config.lootshareMissingColor, t.lootshareEligible ? READY_COLOUR : 0xFF5555);
            map.put(t.entity, colour);
        }
        glow = map;
    }

    // ------------------------------------------------------------------------------------------------ siamese lynxes

    /** Every particle packet (Sky2MSlayerPacketsMixin): angry villager particles mark the lynx that can be hit. */
    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        FeatureConfigs.Diana config = config();
        if (config == null || !config.lynxHighlight || packet.particle().getType() != ParticleTypes.ANGRY_VILLAGER || !onHub()) return;
        lynxParticles.add(Map.entry(new Vec3(packet.x(), packet.y(), packet.z()), System.currentTimeMillis()));
    }

    /**
     * The Siamese Lynx the angry villager particles are over. The cats are matched to the particles directly, not
     * through their nametags: the two cats stand close together and Hypixel spawns both before their nametags, so a
     * nametag's "entity spawned just before it" is often the other cat.
     */
    private static void updateHittableLynx(FeatureConfigs.Diana config, long now) {
        lynxParticles.removeIf(p -> now - p.getValue() > LYNX_PARTICLE_WINDOW_MS);
        Minecraft mc = Minecraft.getInstance();
        if (config == null || !config.lynxHighlight || mc.level == null || mc.player == null || !onHub()) {
            lynxParticles.clear();
            hittableLynx = null;
            return;
        }
        if (lynxParticles.isEmpty()) {
            // Particles stop for a moment between hits: keep the last cat until they've been gone a while.
            if (hittableLynx != null && (!hittableLynx.isAlive() || now - lynxSeenAt > LYNX_PARTICLE_MS)) hittableLynx = null;
            return;
        }
        List<Entity> entities = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) entities.add(e);
        List<Vec3> lynxTags = new ArrayList<>();
        for (Entity e : entities) {
            if (e instanceof ArmorStand stand && stand.hasCustomName()
                && matchLabel(Sky2MLocation.strip(stand.getCustomName().getString())) == RareMob.SIAMESE_LYNXES) {
                lynxTags.add(stand.position());
            }
        }
        LivingEntity best = null;
        int bestHits = 0;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : entities) {
            if (!(e instanceof LivingEntity cat) || !cat.isAlive() || !(e instanceof Cat || e instanceof Ocelot)) continue;
            // Only the lynxes: a cat with a lynx nametag above it (any cat if no lynx nametag can be read).
            boolean lynx = lynxTags.isEmpty();
            for (Vec3 tag : lynxTags) {
                double dx = tag.x - cat.getX(), dz = tag.z - cat.getZ(), dy = tag.y - cat.getY();
                if (dx * dx + dz * dz <= 4.0 && dy >= -0.5 && dy <= 4.0) lynx = true;
            }
            if (!lynx) continue;
            // Which cat most of the recent particles are over, then the closest to the newest one.
            int hits = 0;
            double newest = Double.MAX_VALUE;
            for (Map.Entry<Vec3, Long> p : lynxParticles) {
                Vec3 at = p.getKey();
                double dx = at.x - cat.getX(), dz = at.z - cat.getZ(), dy = at.y - cat.getY();
                double horizontal = dx * dx + dz * dz;
                if (horizontal > LYNX_PARTICLE_RADIUS * LYNX_PARTICLE_RADIUS || dy < -0.5 || dy > 3.0) continue;
                hits++;
                newest = horizontal;
            }
            if (hits == 0) continue;
            if (hits > bestHits || (hits == bestHits && newest < bestDistance)) {
                best = cat;
                bestHits = hits;
                bestDistance = newest;
            }
        }
        if (best != null) {
            hittableLynx = best;
            lynxSeenAt = now;
        }
    }

    // ------------------------------------------------------------------------------------------------ chat

    /** Returns false to hide the message. */
    private static boolean onMessage(Component component) {
        FeatureConfigs.Diana config = config();
        if (config == null || (!config.rareMobSharing && !config.lootshare)) return true;
        String text = Sky2MLocation.strip(component.getString()).trim();
        long now = System.currentTimeMillis();
        PartyChat.onMessage(text, now);

        Matcher party = PARTY_LINE.matcher(text);
        if (party.matches()) {
            String sender = party.group("name");
            String body = party.group("body").trim();
            if (config.lootshare && body.equalsIgnoreCase(LOOTSHARE_MESSAGE)) return onLootshareMessage(config, sender, body, now);
            if (!enabledOnHub(config)) return true;
            return onPartyMessage(config, sender, body, now);
        }
        if (!enabledOnHub(config) || !systemLike(text)) return true;

        Matcher cocoon = LOCAL_COCOON.matcher(text);
        if (cocoon.matches()) {
            RareMob mob = RareMob.fromMobName(cocoon.group("mob"));
            if (mob != null && config.rareMobSharing) onLocalCocoon(config, mob, now);
            return true;
        }
        String server = server();
        if (server != null && config.rareMobSharing && !text.regionMatches(true, 0, "RARE DROP!", 0, 10)) {
            Matcher dug = DUG_OUT.matcher(text);
            if (dug.find()) {
                String label = dug.group("mob").trim();
                RareMob mob = label.chars().anyMatch(Character::isDigit) ? null : RareMob.fromLabel(label);
                if (mob != null && sharedMobs(config).contains(mob)) pendingLocalSpawns.add(new Pending(server, mob, now + LOCAL_SPAWN_LINK_MS));
            }
        }
        if (text.startsWith("You dug out a Griffin Burrow!") || text.startsWith("You finished the Griffin burrow chain!")) {
            for (Target t : currentTargets()) if (t.source == Source.LOCAL) clearTarget(t, "burrow progressed");
        }
        if (text.startsWith("You died") || text.startsWith("You were killed by ") || text.startsWith("☠ You died") || text.startsWith("☠ You were killed by ")) {
            for (Target t : currentTargets()) if (t.source == Source.LOCAL) clearTarget(t, "player died");
        }
        Matcher death = PLAYER_DEATH.matcher(text);
        if (death.matches()) {
            RareMob mob = mobFromKiller(death.group("killer"));
            String player = death.group("player");
            if (mob != null) {
                currentTargets().stream()
                    .filter(t -> t.source == Source.REMOTE && t.sharedBy.equalsIgnoreCase(player) && t.mob == mob)
                    .max(Comparator.comparingLong((Target t) -> t.createdAt).thenComparingLong(t -> t.id))
                    .ifPresent(t -> clearTarget(t, "shared player died", false));
            }
        }
        return true;
    }

    /** Hypixel's own messages, not a player's chat. */
    private static boolean systemLike(String text) {
        return !text.startsWith("Party >") && !text.startsWith("Guild >") && !text.startsWith("Co-op >")
            && !text.startsWith("From ") && !text.startsWith("To ") && !text.matches("^\\[\\d+] .*?: .*");
    }

    private static RareMob mobFromKiller(String killer) {
        String clean = killer.trim();
        if (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1).trim();
        RareMob mob = RareMob.fromLabel(clean);
        if (mob != null) return mob;
        for (String prefix : MODIFIER_PREFIXES) {
            if (clean.startsWith(prefix + " ")) return RareMob.fromLabel(clean.substring(prefix.length() + 1));
        }
        return null;
    }

    private static boolean onLootshareMessage(FeatureConfigs.Diana config, String sender, String body, long now) {
        if (PartyChat.isLocal(sender) && PartyChat.consumeEcho(body, now)) return config.showPartyMessages;
        if (config.partyCheckmarks && !currentTargets().isEmpty() && !PartyChat.isLocal(sender)) markReady(sender, now);
        return config.showPartyMessages;
    }

    private static boolean onPartyMessage(FeatureConfigs.Diana config, String sender, String body, long now) {
        boolean local = PartyChat.isLocal(sender);
        if (local && PartyChat.consumeEcho(body, now)) return config.showPartyMessages;
        Set<RareMob> received = sharedMobs(config);

        Matcher cocoon = PARTY_COCOON.matcher(body);
        if (cocoon.matches()) {
            RareMob mob = RareMob.fromMobName(stripShareText(cocoon.group("mob")));
            if (mob == null || local || !received.contains(mob)) return true;
            for (Target t : currentTargets()) {
                if (t.source != Source.REMOTE || t.mob != mob || !t.sharedBy.equalsIgnoreCase(sender)) continue;
                t.expiresAt = Math.max(t.expiresAt, now + TARGET_LIFETIME_MS);
                t.prepareForCocoonHatch(now + COCOON_HATCH_ATTACH_MS);
            }
            if (config.rareMobSharing) {
                if (!config.showPartyMessages) Sky2MAlerts.chat(name(sender).append(Component.literal(" cocooned a ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(mob.label).withStyle(ChatFormatting.LIGHT_PURPLE)));
                alert(mob, Component.literal("Cocooned by ").withStyle(ChatFormatting.GRAY).append(name(sender)));
            }
            return config.showPartyMessages;
        }

        Matcher explicit = EXPLICIT_CLEAR.matcher(body);
        Matcher generic = GENERIC_CLEAR.matcher(body);
        if (explicit.matches() || generic.matches()) {
            RareMob mob = explicit.matches() ? RareMob.fromLabel(explicit.group("mob")) : null;
            if (explicit.matches() && mob == null) return true;
            if (!local) {
                for (Target t : currentTargets()) {
                    if ((mob == null || t.mob == mob) && t.sharedBy.equalsIgnoreCase(sender)) clearTarget(t, "shared clear", false);
                }
            }
            return config.showPartyMessages;
        }

        Object[] share = parseShare(body);
        if (share == null) return true;
        RareMob mob = (RareMob) share[0];
        Vec3 location = (Vec3) share[1];
        if (!received.contains(mob) || local) return true;
        String server = server();
        if (server != null) rememberShare(mob, location, sender, Source.REMOTE, server, now);
        if (config.rareMobSharing) {
            if (!config.showPartyMessages) {
                Sky2MAlerts.chat(name(sender).append(Component.literal(" found a ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(mob.label).withStyle(ChatFormatting.LIGHT_PURPLE))
                    .append(Component.literal(" at " + (int) location.x + " " + (int) location.y + " " + (int) location.z).withStyle(ChatFormatting.GRAY)));
            }
            alert(mob, Component.literal("Found by ").withStyle(ChatFormatting.GRAY).append(name(sender)));
        }
        return config.showPartyMessages;
    }

    /** Whether a party message is a rare mob share (those get their own waypoint here). */
    static boolean isRareMobShare(String body) {
        return parseShare(body.trim()) != null;
    }

    /** {mob, location} from any of the share formats, or null. */
    private static Object[] parseShare(String body) {
        Matcher legacy = LEGACY_FOUND.matcher(body);
        if (legacy.matches()) {
            RareMob mob = RareMob.fromLabel(legacy.group("mob"));
            return mob == null ? null : shareOf(mob, legacy);
        }
        Matcher coords = COORDINATE_SHARE.matcher(body);
        if (coords.matches()) {
            String mobText = coords.group("mob");
            RareMob mob = mobText == null ? null : RareMob.fromMobName(stripShareText(mobText));
            return mob == null ? null : shareOf(mob, coords);
        }
        Matcher inquisitor = INQUISITOR_SPAWN.matcher(body);
        if (inquisitor.matches()) return shareOf(RareMob.MINOS_INQUISITOR, inquisitor);
        return null;
    }

    private static Object[] shareOf(RareMob mob, Matcher m) {
        try {
            return new Object[]{mob, new Vec3(Double.parseDouble(m.group("x")), Double.parseDouble(m.group("y")), Double.parseDouble(m.group("z")))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripShareText(String text) {
        String clean = text.trim();
        if (clean.startsWith("|")) clean = clean.substring(1).trim();
        if (clean.endsWith("!")) clean = clean.substring(0, clean.length() - 1).trim();
        return clean;
    }

    private static String formatShare(RareMob mob, Vec3 location) {
        return "x: " + (int) location.x + ", y: " + (int) location.y + ", z: " + (int) location.z + " | " + mob.label;
    }

    /** You cocooned your rare mob: say so, and share where it will hatch. */
    private static void onLocalCocoon(FeatureConfigs.Diana config, RareMob mob, long now) {
        if (!sharedMobs(config).contains(mob)) return;
        String local = localName();
        String server = server();
        if (local == null || server == null) return;
        List<Target> current = currentTargets();
        Vec3 location = current.stream().filter(t -> t.source == Source.LOCAL && t.mob == mob)
            .max(Comparator.comparingLong((Target t) -> t.createdAt).thenComparingLong(t -> t.id))
            .map(t -> blockOf(t.lineLocation()))
            .orElseGet(() -> recentLocalDeaths.stream().filter(d -> d.mob() == mob)
                .max(Comparator.comparingLong(RecentDeath::expiresAt)).map(RecentDeath::location).orElse(null));
        if (location == null && Minecraft.getInstance().player != null) location = blockOf(Minecraft.getInstance().player.position().add(0, -1, 0));
        pendingLocalSpawns.removeIf(p -> p.server().equals(server) && p.mob() == mob);
        recentLocalDeaths.removeIf(d -> d.server().equals(server) && d.mob() == mob);
        List<Target> locals = current.stream().filter(t -> t.source == Source.LOCAL && t.mob == mob).toList();
        // "cocooned", not MOB_DIED: the mob isn't defeated, and this already shares where it will hatch right below.
        for (Target t : locals) clearTarget(t, "cocooned");

        PartyChat.send("Cocooned a " + mob.label + "!");
        if (location == null) return;
        Target target = rememberShare(mob, location, local, Source.LOCAL, server, now);
        target.prepareForCocoonHatch(now + COCOON_HATCH_ATTACH_MS);
        PartyChat.send(formatShare(mob, location));
    }

    private static MutableComponent name(String player) {
        return Component.literal(player).withStyle(ChatFormatting.AQUA);
    }

    /** The rare mob in big letters with a subtitle, and three pings. */
    private static void alert(RareMob mob, Component subtitle) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.hud.setTimes(0, 50, 5);
        mc.gui.hud.setTitle(Component.literal(mob.label).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        mc.gui.hud.setSubtitle(subtitle == null ? Component.empty() : subtitle);
        pingsLeft = 3;
        nextPingAt = 0;
    }

    private static int pingsLeft;
    private static long nextPingAt;

    // ------------------------------------------------------------------------------------------------ render

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.Diana config = config();
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (pingsLeft > 0 && now >= nextPingAt) {
            Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.8f);
            pingsLeft--;
            nextPingAt = now + 450;
        }
        if (!enabledOnHub(config) || mc.player == null) return;
        List<Target> active = currentTargets();
        if (active.isEmpty()) return;
        String local = localName();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 camera = mc.gameRenderer.mainCamera().position();

        for (Target t : active) {
            if (!t.visible()) {
                if (!config.rareMobSharing) continue;
                float[] pink = {1f, 0.33f, 1f};
                collector.submitFilledBox(net.minecraft.core.BlockPos.containing(t.location), new float[]{0.67f, 0f, 1f}, 0.25f, true);
                collector.submitOutlinedBox(new AABB(t.location, t.location.add(1, 1, 1)), pink, 0.9f, 3f, true);
                Vec3 label = t.location.add(0.5, 1.8, 0.5);
                float scale = labelScale(camera, label, 7f);
                collector.submitText(Component.literal(t.mob.label.toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), label.add(0, 0.3 * scale, 0), scale, true);
                collector.submitText(Component.literal("Found by " + t.sharedBy).withStyle(ChatFormatting.GRAY), label, scale * 0.8f, true);
                continue;
            }
            if (!config.lootshare || !t.showLootshare(local)) continue;
            Entity anchor = t.nameplate != null ? t.nameplate : t.entity;
            Vec3 above = anchor.getPosition(partial).add(0, anchor.getBbHeight() + 0.8, 0);
            float scale = labelScale(camera, above, 6f);
            int colour = rgb(t.lootshareEligible ? config.lootshareReadyColor : config.lootshareMissingColor, t.lootshareEligible ? READY_COLOUR : 0xFF5555);
            MutableComponent text = Component.literal("Lootsharing")
                .withStyle(s -> s.withColor(colour).withBold(t.lootshareEligible));
            collector.submitText(text, above.add(0, 0.25 * scale, 0), scale, true);
            if (config.lootshareRadius && t.lineLocation().distanceTo(mc.player.position()) <= LOOTSHARE_RADIUS_RENDER_DISTANCE) {
                Vec3 centre = t.entity != null ? t.entity.getPosition(partial) : t.lineLocation();
                collector.submitOutlinedCircle(centre.add(0, 0.05, 0), (float) LOOTSHARE_RADIUS, 2f, 96, 0xE6000000 | colour);
            }
        }

        if (config.rareMobSharing && config.crosshairLine) {
            Vec3 player = mc.player.position();
            Target nearest = active.stream().min(Comparator.comparingDouble(t -> t.lineLocation().distanceToSqr(player))).orElse(null);
            if (nearest != null) {
                Entity lineEntity = nearest.entity != null ? nearest.entity : nearest.nameplate;
                if (lineEntity == null || mc.player.hasLineOfSight(lineEntity)) {
                    Vec3 point = lineEntity != null ? lineEntity.getPosition(partial) : nearest.lineLocation();
                    collector.submitLineFromCursor(point, new float[]{1f, 0.33f, 1f}, 0.9f, 3f);
                }
            }
        }

        if (config.lootshare && config.partyCheckmarks) renderCheckmarks(collector, mc, active, local, partial, camera);
    }

    /** Cyan ✓ above party members who secured lootshare; pink ✓ above whoever spawned the rare mob. */
    private static void renderCheckmarks(PrimitiveCollector collector, Minecraft mc, List<Target> active, String local, float partial, Vec3 camera) {
        Map<String, Integer> marks = new HashMap<>();
        for (Map.Entry<String, Long> ready : readyPlayers.values()) marks.put(ready.getKey().toLowerCase(Locale.ROOT), READY_COLOUR);
        for (Target t : active) marks.put(t.sharedBy.toLowerCase(Locale.ROOT), RARE_MOB_COLOUR);
        if (marks.isEmpty()) return;
        for (Player player : mc.level.players()) {
            String name = player.getGameProfile().name();
            if (name.equalsIgnoreCase(local)) continue;
            Integer colour = marks.get(name.toLowerCase(Locale.ROOT));
            if (colour == null) continue;
            Vec3 pos = player.getPosition(partial).add(0, player.getBbHeight() + 0.9, 0);
            if (pos.distanceTo(camera) > 80) continue;
            float scale = labelScale(camera, pos, 6f);
            collector.submitText(Component.literal("✓").withStyle(s -> s.withColor(colour).withBold(true)), pos.add(0, 0.25 * scale, 0), scale, true);
        }
    }

    /** Labels grow with distance (up to {@code max}) so they stay readable far away. */
    private static float labelScale(Vec3 camera, Vec3 pos, float max) {
        return (float) Math.max(1.0, Math.min(max, camera.distanceTo(pos) / 8.0));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Vec3 blockOf(Vec3 v) {
        return new Vec3(Math.floor(v.x), Math.floor(v.y), Math.floor(v.z));
    }

    private static int rgb(String value, int fallback) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB() & 0xFFFFFF;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------------------------------------ party chat

    /**
     * Sends party messages one at a time (Hypixel drops messages sent too fast), only while you're in a party, and
     * remembers them for five seconds so their echo can be recognised. Party membership is read from Hypixel's party
     * messages, as Skysoft's SkysoftPartyShare does with the Hypixel mod API.
     */
    static final class PartyChat {
        private static final long SEND_INTERVAL_MS = 600L;
        private static final long ECHO_WINDOW_MS = 5_000L;
        private static final List<String> queue = new ArrayList<>();
        private static final List<Map.Entry<String, Long>> sent = new ArrayList<>();
        /** Null until a party message says either way: then messages are sent, as you may already be in one. */
        private static Boolean inParty;
        private static long lastSentAt;

        private PartyChat() {}

        static void reset() {
            queue.clear();
            sent.clear();
            inParty = null;
        }

        static void send(String message) {
            if (!Boolean.FALSE.equals(inParty)) queue.add(message);
        }

        static void tick() {
            long now = System.currentTimeMillis();
            if (queue.isEmpty() || now - lastSentAt < SEND_INTERVAL_MS) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null) {
                queue.clear();
                return;
            }
            String message = queue.remove(0);
            mc.getConnection().sendCommand("pc " + message);
            sent.add(Map.entry(message.trim(), now + ECHO_WINDOW_MS));
            lastSentAt = now;
        }

        static boolean consumeEcho(String body, long now) {
            sent.removeIf(e -> now > e.getValue());
            for (int i = 0; i < sent.size(); i++) {
                if (sent.get(i).getKey().equals(body.trim())) {
                    sent.remove(i);
                    return true;
                }
            }
            return false;
        }

        static boolean isLocal(String name) {
            String local = localName();
            return local != null && local.equalsIgnoreCase(name);
        }

        static void onMessage(String text, long now) {
            if (text.startsWith("Party > ") || text.startsWith("Party Leader: ") || text.startsWith("Party Members (")
                || text.matches("^You have joined .+ party!$") || text.matches("^.+ joined the party\\.$")
                || text.matches("^(?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?\\w+ invited (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?\\w+ to the party! They have 60 seconds to accept\\.$")) {
                inParty = true;
            } else if (text.equals("You left the party.") || text.startsWith("The party was disbanded")
                || text.startsWith("You have been kicked from the party") || text.equals("You are not currently in a party.")
                || text.equals("You are not in a party.") || text.startsWith("You are not in a party")) {
                inParty = false;
                queue.clear();
            }
        }
    }
}
