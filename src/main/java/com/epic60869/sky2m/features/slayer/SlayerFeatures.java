package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayer boss phase display: your boss's health (or hits while Voidgloom's shield is up) from its nametag, in a HUD,
 * with your Tarantula's egg sacs (time left and hits) under it. The same scan times your kills for {@link SlayerTimes}.
 */
public final class SlayerFeatures {
    /** How far from you your own boss is looked for. */
    private static final double SEARCH_RANGE = 48;
    /** How far from you a Tarantula's egg sacs are looked for. */
    private static final double SACK_RANGE = 20;

    private static final Pattern EGG_SAC = Pattern.compile("\\d+s \\d+/\\d+");

    private static List<Component> bossLines = List.of();
    private static LivingEntity boss;
    /** Null follows your boss; otherwise the owner whose boss phase is shown. */
    private static String selectedBossOwner;

    private static int ticks;

    private SlayerFeatures() {}

    private static FeatureConfigs.Slayer config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.huds;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 2 == 0) tick(mc);
        });

        Sky2MHuds.register("slayer_phase", "Slayer Boss Phase",
            () -> config() != null && config().phaseDisplay,
            () -> bossLines,
            List.of(Component.literal("☠ Voidgloom Seraph 45M❤").withStyle(ChatFormatting.RED)),
            8, 220);
    }

    private static void tick(Minecraft mc) {
        FeatureConfigs.Slayer config = config();
        boolean needed = (config != null && config.phaseDisplay) || SlayerTimes.enabled()
            || EndermanSlayer.needsBoss() || BlazeSlayer.needsBoss();
        // Without an explicit target, only scan during your quest; a selected player's boss can be observed separately.
        if (mc.player == null || mc.level == null || !needed || !Sky2MLocation.onSkyblock()
            || (selectedBossOwner == null && !onSlayerQuest())) {
            clearBoss();
            return;
        }
        updateBossLines(mc);
    }

    /**
     * A nametag's text as the server sent it, from an armor stand's custom name or (as Hypixel now uses) a text
     * display. The text display's text is read from its synced data rather than getText(), which the nickname
     * mixin rewrites: with a nickname set, "Spawned by: you" would otherwise never match your username.
     */
    static Component nametag(Entity entity) {
        if (entity instanceof ArmorStand stand && stand.hasCustomName()) return stand.getCustomName();
        if (entity instanceof Display.TextDisplay display) return display.getEntityData().get(Display.TextDisplay.DATA_TEXT_ID);
        return null;
    }

    /** The scoreboard shows "Slayer Quest" from starting a quest until it's done (also after rejoining mid-quest). */
    static boolean onSlayerQuest() {
        for (String line : Sky2MLocation.scoreboard()) if (line.contains("Slayer Quest")) return true;
        return false;
    }

    private static boolean isOwnerTag(String text, String name) {
        String owner = ownerName(text);
        return owner != null && owner.equalsIgnoreCase(name);
    }

    private static String ownerName(String text) {
        if (!text.contains("Spawned by")) return null;
        for (String line : Sky2MLocation.strip(text).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Spawned by:")) {
                String owner = trimmed.substring("Spawned by:".length()).trim();
                return owner.isEmpty() ? null : owner.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static List<Entity> ownerTags(Minecraft mc) {
        double rangeSq = SEARCH_RANGE * SEARCH_RANGE;
        AABB searchArea = mc.player.getBoundingBox().inflate(SEARCH_RANGE);
        List<Entity> owners = mc.level.getEntities((Entity) null, searchArea, entity -> {
            if (entity.distanceToSqr(mc.player) > rangeSq) return false;
            Component tag = nametag(entity);
            return tag != null && ownerName(tag.getString()) != null;
        });
        owners.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
        return owners;
    }

    /** Cycles the phase HUD between nearby slayer bosses, returning to your own boss after the last one. */
    public static void selectNextBossOwner() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        String ownName = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        candidates.add(ownName);
        ownerTags(mc).stream()
            .map(entity -> ownerName(nametag(entity).getString()))
            .filter(owner -> !owner.equals(ownName))
            .distinct()
            .forEach(candidates::add);

        String current = selectedBossOwner == null ? ownName : selectedBossOwner;
        int index = candidates.indexOf(current);
        String next = candidates.get(index < 0 ? 0 : (index + 1) % candidates.size());
        selectedBossOwner = next.equals(ownName) ? null : next;
        phaseBoss = "";
        maxHealth = 0;
        mc.player.sendSystemMessage(Component.translatable("chat.sky2m.slayer_boss_target", next));
    }

    /**
     * Nametag lines of your own boss: the nametags stacked around the "Spawned by: you" line. Hypixel draws them
     * with armor stands or text displays, and a text display can hold several lines.
     */
    private static void updateBossLines(Minecraft mc) {
        String name = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        List<Entity> owners = ownerTags(mc);
        Entity ownOwner = owners.stream().filter(entity -> isOwnerTag(nametag(entity).getString(), name)).findFirst().orElse(null);
        boss = ownOwner == null ? null : mobNear(mc, ownOwner);
        List<Component> ownLines = ownOwner == null ? List.of() : bossHealthLines(mc, ownOwner);
        SlayerTimes.onBoss(ownLines.isEmpty() ? null : Sky2MLocation.strip(ownLines.get(0).getString()));

        String targetOwner = selectedBossOwner == null ? name : selectedBossOwner;
        Entity owner = owners.stream().filter(entity -> isOwnerTag(nametag(entity).getString(), targetOwner)).findFirst().orElse(null);
        List<Component> lines = new ArrayList<>();
        if (owner != null) {
            // Someone else's boss picked with the cycle key: everything that follows "the boss" follows theirs.
            if (!targetOwner.equals(name)) boss = mobNear(mc, owner);
            List<Component> health = targetOwner.equals(name) ? ownLines : bossHealthLines(mc, owner);
            String text = health.isEmpty() ? "" : Sky2MLocation.strip(health.get(0).getString());
            if (!health.isEmpty()) {
                // Hypixel's "SLAYER BOSS" line above the name and health.
                Component label = labelNear(mc, owner, "SLAYER BOSS");
                if (label != null) lines.add(label);
                lines.add(withPhase(health.get(0), text));
            }
            if (targetOwner.equals(name) && isTarantula(text)) addEggSacLines(mc, lines);
        } else {
            maxHealth = 0;
            phaseBoss = "";
        }
        FeatureConfigs.Slayer config = config();
        if (config != null && config.showMinibosses) addMinibossLines(mc, lines);
        bossLines = lines;
    }

    /** A "SLAYER BOSS" / "SLAYER MINIBOSS" nametag line around {@code anchor}, or null. */
    private static Component labelNear(Minecraft mc, Entity anchor, String label) {
        AABB area = anchor.getBoundingBox().inflate(1.5, 3, 1.5);
        for (Entity e : mc.level.getEntities((Entity) null, area, e -> nametag(e) != null)) {
            for (Component line : splitLines(nametag(e))) {
                if (Sky2MLocation.strip(line.getString()).trim().equalsIgnoreCase(label)) return line;
            }
        }
        return null;
    }

    /**
     * Slayer minibosses near you: Hypixel writes "SLAYER MINIBOSS" above their name and health, either on the same
     * text display or on its own nametag just above. Each one gets its label and health line, nearest first.
     */
    private static void addMinibossLines(Minecraft mc, List<Component> lines) {
        List<Entity> tags = mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(24), e -> nametag(e) != null);
        tags.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
        int shown = 0;
        for (Entity tagEntity : tags) {
            List<Component> parts = splitLines(nametag(tagEntity));
            for (int i = 0; i < parts.size(); i++) {
                if (!Sky2MLocation.strip(parts.get(i).getString()).trim().equalsIgnoreCase("SLAYER MINIBOSS")) continue;
                Component health = i + 1 < parts.size() ? parts.get(i + 1) : healthBelow(mc, tagEntity);
                if (health == null) continue;
                lines.add(parts.get(i));
                lines.add(health);
                if (++shown >= 3) return;
            }
        }
    }

    /** The name and health nametag just under a label nametag. */
    private static Component healthBelow(Minecraft mc, Entity label) {
        AABB below = label.getBoundingBox().inflate(0.6, 0, 0.6).expandTowards(0, -1.2, 0);
        Entity best = null;
        for (Entity e : mc.level.getEntities(label, below, e -> nametag(e) != null && e.getY() < label.getY())) {
            String text = Sky2MLocation.strip(nametag(e).getString());
            if (!text.contains("❤") && !text.contains(" Hits")) continue;
            if (best == null || e.getY() > best.getY()) best = e;
        }
        return best == null ? null : nametag(best);
    }

    private static List<Component> bossHealthLines(Minecraft mc, Entity owner) {
        AABB area = owner.getBoundingBox().inflate(1.5, 3, 1.5);
        List<Entity> tags = mc.level.getEntities((Entity) null, area, e -> nametag(e) != null);
        tags.sort(Comparator.comparingDouble((Entity e) -> e.getY()).reversed());
        // Only the boss's own health line: the other tags around it are pets and floating damage numbers.
        List<Component> lines = new ArrayList<>();
        for (Entity tagEntity : tags) {
            for (Component line : splitLines(nametag(tagEntity))) {
                if (isBossHealth(Sky2MLocation.strip(line.getString()))) {
                    lines.add(line);
                    break;
                }
            }
            if (!lines.isEmpty()) break;
        }
        return lines;
    }

    private static void clearBoss() {
        bossLines = List.of();
        boss = null;
        maxHealth = 0;
        phaseBoss = "";
        SlayerTimes.onBoss(null);
    }

    /** Your slayer boss (the mob under your "Spawned by" nametag), or null without one. */
    public static LivingEntity boss() {
        return boss != null && boss.isAlive() ? boss : null;
    }

    /** The mob nearest your boss's nametags: players, armor stands and text displays aren't it. */
    private static LivingEntity mobNear(Minecraft mc, Entity tag) {
        AABB area = tag.getBoundingBox().inflate(1.5, 3, 1.5);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity mob : mc.level.getEntitiesOfClass(LivingEntity.class, area,
                e -> !(e instanceof ArmorStand) && !(e instanceof Player) && e.isAlive())) {
            double distance = mob.distanceToSqr(tag);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    /** "☠ Voidgloom Seraph IV 45M❤": the boss, its tier and its health. */
    private static final Pattern PHASE_HEALTH = Pattern.compile(
        "(?<boss>Voidgloom Seraph|Inferno Demonlord) (?<tier>IV|III|II|I)\\b.*?(?<hp>[\\d.,]+)(?<unit>[kKMB]?)❤");

    /** The highest health seen for the current boss: its full health, as it starts full. */
    private static double maxHealth;
    private static String phaseBoss = "";

    /**
     * As in SkyHanni's damage indicator: a Voidgloom's health is split into three phases (six for tier IV) and an
     * Inferno Demonlord's into two (three for tiers III and IV). The phase goes in front of the health line.
     */
    private static Component withPhase(Component line, String health) {
        Matcher m = PHASE_HEALTH.matcher(health);
        if (!m.find()) return line;
        String bossName = m.group("boss");
        String tier = m.group("tier");
        double hp = parseHealth(m.group("hp"), m.group("unit"));
        if (hp < 0) return line;
        String key = bossName + " " + tier;
        if (!key.equals(phaseBoss)) {
            phaseBoss = key;
            maxHealth = 0;
        }
        double previous = maxHealth == 0 ? hp : lastHealth;
        maxHealth = Math.max(maxHealth, hp);
        lastHealth = hp;

        boolean voidgloom = bossName.startsWith("Voidgloom");
        int phases = voidgloom ? (tier.equals("IV") ? 6 : 3) : (tier.equals("III") || tier.equals("IV") ? 3 : 2);
        if (!voidgloom && phases == 3) BlazeSlayer.onBossHealth(previous, hp, maxHealth);

        boolean show = voidgloom ? EndermanSlayer.phaseNumbers() : BlazeSlayer.phaseNumbers();
        if (!show || maxHealth <= 0) return line;
        double step = maxHealth / phases;
        int phase = Math.min(phases, Math.max(1, phases - (int) Math.ceil(hp / step) + 1));
        ChatFormatting colour = phase == 1 ? ChatFormatting.RED : phase == phases ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        return Component.empty().append(Component.literal(phase + "/" + phases + " ").withStyle(colour)).append(line);
    }

    private static double lastHealth;

    private static double parseHealth(String number, String unit) {
        try {
            double value = Double.parseDouble(number.replace(",", ""));
            return switch (unit) {
                case "k", "K" -> value * 1_000;
                case "M" -> value * 1_000_000;
                case "B" -> value * 1_000_000_000;
                default -> value;
            };
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isTarantula(String text) {
        return text.contains("Tarantula") || text.contains("Brood");
    }

    /**
     * Your Tarantula's egg sacs, nearest first, under its health line. As in SkyHanni's SpiderEggSacHighlighter, an
     * egg sac is a "5s 1/3" nametag (time left, hits taken/needed) next to a "SHOOT ME!" one.
     */
    private static void addEggSacLines(Minecraft mc, List<Component> lines) {
        List<Entity> tags = mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(SACK_RANGE), e -> nametag(e) != null);
        List<Entity> shootMe = tags.stream()
            .filter(e -> Sky2MLocation.strip(nametag(e).getString()).trim().equalsIgnoreCase("SHOOT ME!")).toList();
        tags.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
        MutableComponent row = null;
        for (Entity tagEntity : tags) {
            Component tag = nametag(tagEntity);
            if (!EGG_SAC.matcher(Sky2MLocation.strip(tag.getString()).trim()).matches()) continue;
            if (shootMe.stream().noneMatch(e -> e.distanceToSqr(tagEntity) < 2.5 * 2.5)) continue;
            // Two egg sacs per line.
            if (row == null) {
                row = Component.literal("Egg Sac ").withStyle(ChatFormatting.YELLOW).append(tag);
            } else {
                lines.add(row.append(Component.literal("   Egg Sac ").withStyle(ChatFormatting.YELLOW)).append(tag));
                row = null;
            }
        }
        if (row != null) lines.add(row);
    }

    private static final List<String> BOSSES = List.of("Revenant Horror", "Atoned Horror", "Tarantula Broodfather",
        "Conjoined Brood", "Sven Packmaster", "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend");

    /** "☠ Voidgloom Seraph IV 45M❤" (or "... 15 Hits" while its shield is up). */
    private static boolean isBossHealth(String text) {
        if (!text.contains("❤") && !text.contains(" Hits")) return false;
        if (text.contains("☠")) return true;
        for (String boss : BOSSES) if (text.contains(boss)) return true;
        return false;
    }

    /** Splits a nametag on its line breaks, keeping each part's colours. */
    static List<Component> splitLines(Component tag) {
        List<Component> lines = new ArrayList<>();
        MutableComponent[] current = {Component.empty()};
        tag.visit((style, value) -> {
            String[] parts = value.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    lines.add(current[0]);
                    current[0] = Component.empty();
                }
                if (!parts[i].isEmpty()) current[0].append(Component.literal(parts[i]).withStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        lines.add(current[0]);
        return lines;
    }
}
