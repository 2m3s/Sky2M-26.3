package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inferno Demonlord helpers, ported from SkyHanni (https://github.com/hannibal002/SkyHanni, LGPL-2.1):
 * HellionShield and HellionShieldHelper (shield colours, from DamageIndicatorManager.checkBlazeSlayer),
 * BlazeSlayerDaggerHelper, BlazeSlayerFirePitsWarning, BlazeSlayerClearView and FirePillarDisplay. The phase numbers
 * are in {@link SlayerFeatures}.
 */
public final class BlazeSlayer {
    public enum Shield {
        AURIC("Auric", 0xFFFF55, ChatFormatting.YELLOW),
        ASHEN("Ashen", 0x555555, ChatFormatting.DARK_GRAY),
        SPIRIT("Spirit", 0xFFFFFF, ChatFormatting.WHITE),
        CRYSTAL("Crystal", 0x55FFFF, ChatFormatting.AQUA);

        final String cleanName;
        final int rgb;
        final ChatFormatting colour;
        boolean active;

        Shield(String cleanName, int rgb, ChatFormatting colour) {
            this.cleanName = cleanName;
            this.rgb = rgb;
            this.colour = colour;
        }

        Shield other() {
            for (Dagger dagger : Dagger.values()) {
                if (dagger.shields[0] == this) return dagger.shields[1];
                if (dagger.shields[1] == this) return dagger.shields[0];
            }
            return this;
        }
    }

    enum Dagger {
        TWILIGHT(List.of("Twilight Dagger", "Mawdredge Dagger", "Deathripper Dagger"), Shield.SPIRIT, Shield.CRYSTAL),
        FIREDUST(List.of("Firedust Dagger", "Kindlebane Dagger", "Pyrochaos Dagger"), Shield.ASHEN, Shield.AURIC);

        final List<String> names;
        final Shield[] shields;
        /** The attunement is known (from a title or the dagger's lore), not guessed. */
        boolean updated;

        Dagger(List<String> names, Shield... shields) {
            this.names = names;
            this.shields = shields;
        }

        Dagger other() {
            return this == TWILIGHT ? FIREDUST : TWILIGHT;
        }

        Shield active() {
            return shields[1].active && !shields[0].active ? shields[1] : shields[0];
        }
    }

    /** The "First Dagger" dropdown. */
    public enum FirstDagger {
        TWILIGHT("Twilight"), FIREDUST("Firedust");

        private final String label;

        FirstDagger(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** "Strike using the SPIRIT attunement on your dagger!" */
    private static final Pattern ATTUNEMENT = Pattern.compile("^Strike using the .+ attunement on your dagger!$");
    /** A Fire Pillar's nametag: "2s 8 hits". */
    private static final Pattern FIRE_PILLAR = Pattern.compile("^(\\d+)s 8 hits$");
    /** A Hellion Shield line: "ASHEN ♨3". */
    private static final Pattern SHIELD_TAG = Pattern.compile("\\b(AURIC|ASHEN|SPIRIT|CRYSTAL)\\b.*?♨\\s*(\\d)");

    /** Mobs with a Hellion Shield (the boss and its demons), with the number after the shield's ♨. */
    private static Map<LivingEntity, Shield> shieldMobs = Map.of();
    private static Map<LivingEntity, String> shieldCharges = Map.of();
    private static List<Component> daggerLines = List.of();
    private static List<Component> pillarLines = List.of();
    private static int pillarEntity = -1;
    private static boolean nearBlaze;
    private static boolean clientSideClicked;
    private static long lastDaggerCheck;
    private static long firePitsAt;
    private static int ticks;

    private BlazeSlayer() {}

    private static FeatureConfigs.BlazeSlayer config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.blaze;
    }

    private static boolean inCrimsonIsle() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("Crimson Isle");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BlazeSlayer::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MWorldRender.register(BlazeSlayer::render);
        EntityGlow.register(entity -> {
            FeatureConfigs.BlazeSlayer config = config();
            if (config == null || !config.coloredMobs || !(entity instanceof LivingEntity mob)) return -1;
            Shield shield = shieldMobs.get(mob);
            return shield == null ? -1 : shield.rgb;
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            FeatureConfigs.BlazeSlayer config = config();
            if (overlay || config == null || !config.hideDaggerChat || !Sky2MLocation.onSkyblock()) return true;
            String text = Sky2MLocation.strip(message.getString()).trim();
            return !ATTUNEMENT.matcher(text).matches() && !text.equals("Your hit was reduced by Hellion Shield!");
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) onRightClick(player.getItemInHand(hand));
            return InteractionResult.PASS;
        });

        Sky2MHuds.register("blaze_daggers", "Blaze Slayer Daggers",
            () -> config() != null && config().daggers,
            () -> daggerLines,
            List.of(Component.literal("[Spirit] ").withStyle(ChatFormatting.GRAY).append(Component.literal("Ashen").withStyle(ChatFormatting.DARK_GRAY)),
                Component.literal("Crystal ").withStyle(ChatFormatting.AQUA).append(Component.literal("Auric").withStyle(ChatFormatting.YELLOW))),
            8, 260);
        Sky2MHuds.register("blaze_fire_pillar", "Blaze Slayer Fire Pillar",
            () -> config() != null && config().firePillarDisplay,
            () -> pillarLines,
            List.of(Component.literal("Fire Pillar: ").withStyle(ChatFormatting.RED).append(Component.literal("5s").withStyle(ChatFormatting.AQUA))),
            8, 290);
    }

    private static void reset() {
        shieldMobs = Map.of();
        shieldCharges = Map.of();
        daggerLines = List.of();
        pillarLines = List.of();
        pillarEntity = -1;
        nearBlaze = false;
        for (Dagger dagger : Dagger.values()) dagger.updated = false;
        for (Shield shield : Shield.values()) shield.active = false;
    }

    /** SlayerFeatures only finds your boss while something needs it. */
    static boolean needsBoss() {
        FeatureConfigs.BlazeSlayer config = config();
        return config != null && (config.phaseDisplay || config.firePitsWarning);
    }

    static boolean phaseNumbers() {
        FeatureConfigs.BlazeSlayer config = config();
        return config != null && config.phaseDisplay;
    }

    private static void tick(Minecraft mc) {
        ticks++;
        FeatureConfigs.BlazeSlayer config = config();
        if (config == null || mc.level == null || mc.player == null || !inCrimsonIsle()) {
            if (!shieldMobs.isEmpty() || !pillarLines.isEmpty() || nearBlaze) {
                shieldMobs = Map.of();
                shieldCharges = Map.of();
                pillarLines = List.of();
                nearBlaze = false;
            }
            daggerLines = List.of();
            return;
        }

        // Fire Pits: the orb sound every half second for two seconds after the warning.
        if (firePitsAt > 0 && System.currentTimeMillis() - firePitsAt < 2_000L && ticks % 10 == 0) {
            Sky2MAlerts.play(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f);
        }

        if (ticks % 5 == 0 && (config.coloredMobs || config.markRightDagger)) scanShields(mc);
        else if (!config.coloredMobs && !config.markRightDagger && !shieldMobs.isEmpty()) shieldMobs = Map.of();
        if (ticks % 60 == 0) nearBlaze = config.clearView && blazeBossNear(mc);
        if (config.firePillarDisplay) updatePillar(mc);
        else pillarLines = List.of();
        daggerLines = config.daggers ? daggerLines(mc, config) : List.of();
    }

    // ------------------------------------------------------------------------------------------------ shields

    private static void scanShields(Minecraft mc) {
        Map<LivingEntity, Shield> mobs = new HashMap<>();
        Map<LivingEntity, String> charges = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > 30 * 30) continue;
            Component tag = SlayerFeatures.nametag(entity);
            if (tag == null) continue;
            String text = tag.getString();
            if (!text.contains("♨")) continue;
            Matcher m = SHIELD_TAG.matcher(Sky2MLocation.strip(text));
            if (!m.find()) continue;
            LivingEntity mob = mobBelow(mc, entity);
            if (mob == null) continue;
            mobs.put(mob, Shield.valueOf(m.group(1)));
            charges.put(mob, m.group(2));
        }
        shieldMobs = mobs;
        shieldCharges = charges;
    }

    /** As SkyHanni's getNameTagWith(3, ...): the shield line is a nametag within three blocks above the mob. */
    private static LivingEntity mobBelow(Minecraft mc, Entity tag) {
        AABB search = new AABB(tag.getX() - 1.5, tag.getY() - 3.5, tag.getZ() - 1.5, tag.getX() + 1.5, tag.getY() + 0.5, tag.getZ() + 1.5);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity mob : mc.level.getEntitiesOfClass(LivingEntity.class, search,
                e -> !(e instanceof ArmorStand) && !(e instanceof Player) && e.isAlive())) {
            double distance = mob.distanceToSqr(tag);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    /** The shield of the nearest shielded mob within ten blocks, for Mark Right Dagger. */
    private static Shield nearestShield(Minecraft mc) {
        Shield nearest = null;
        double bestDistance = 10 * 10;
        for (Map.Entry<LivingEntity, Shield> entry : shieldMobs.entrySet()) {
            LivingEntity mob = entry.getKey();
            if (!mob.isAlive() || mob.getHealth() <= 0) continue;
            double distance = mob.distanceToSqr(mc.player);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = entry.getValue();
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------------------------------------ daggers

    private static Dagger daggerOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String name = stack.getHoverName().getString();
        for (Dagger dagger : Dagger.values()) {
            for (String daggerName : dagger.names) if (name.contains(daggerName)) return dagger;
        }
        return null;
    }

    private static List<Component> daggerLines(Minecraft mc, FeatureConfigs.BlazeSlayer config) {
        Dagger holding = daggerOf(mc.player.getMainHandItem());
        if (holding == null) return List.of();
        checkActiveDagger(mc);
        Shield nearest = config.markRightDagger ? nearestShield(mc) : null;
        Dagger first = config.firstDagger == FirstDagger.FIREDUST ? Dagger.FIREDUST : Dagger.TWILIGHT;
        Dagger second = first.other();
        return List.of(
            Component.empty().append(format(holding, true, first, nearest)).append(" ").append(format(holding, true, second, nearest)),
            Component.empty().append(format(holding, false, first, nearest)).append(" ").append(format(holding, false, second, nearest)));
    }

    /**
     * One attunement in the dagger HUD. The top line is each dagger's current attunement, the bottom line the one it
     * swaps to. The held dagger's current one is in brackets: green and in capitals when it matches the nearest
     * shield, struck through in red when it doesn't. Any other attunement matching the nearest shield is in gold
     * brackets.
     */
    private static Component format(Dagger holding, boolean top, Dagger slot, Shield nearest) {
        boolean inHand = holding == slot;
        Dagger dagger = inHand ? holding : holding.other();
        Shield shield = dagger.active();
        if (!top) shield = shield.other();

        if (inHand && top) {
            if (nearest == null) return bracketed(Component.literal(shield.cleanName).withStyle(shield.colour), ChatFormatting.GRAY);
            if (shield == nearest) {
                return bracketed(Component.literal(shield.cleanName.toUpperCase(java.util.Locale.ROOT)).withStyle(shield.colour), ChatFormatting.GREEN);
            }
            return bracketed(Component.literal(shield.cleanName).withStyle(shield.colour, ChatFormatting.STRIKETHROUGH), ChatFormatting.RED);
        }
        if (shield == nearest) return bracketed(Component.literal(shield.cleanName).withStyle(shield.colour), ChatFormatting.GOLD);
        return Component.literal(shield.cleanName).withStyle(shield.colour);
    }

    private static Component bracketed(Component inner, ChatFormatting bracket) {
        return Component.literal("[").withStyle(bracket).append(inner).append(Component.literal("]").withStyle(bracket));
    }

    /** Once a second, reads a dagger's attunement from its lore ("Attuned: Spirit") until it's known. */
    private static void checkActiveDagger(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastDaggerCheck < 1_000L) return;
        lastDaggerCheck = now;
        for (Dagger dagger : Dagger.values()) {
            if (dagger.updated || dagger.shields[0].active || dagger.shields[1].active) continue;
            Shield shield = readFromInventory(mc, dagger);
            if (shield != null) {
                shield.active = true;
                dagger.updated = true;
            } else {
                dagger.shields[0].active = true;
            }
        }
    }

    private static Shield readFromInventory(Minecraft mc, Dagger dagger) {
        var inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (daggerOf(stack) != dagger) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                String text = line.getString();
                if (!text.contains("Attuned: ")) continue;
                for (Shield shield : dagger.shields) if (text.contains(shield.cleanName)) return shield;
            }
        }
        return null;
    }

    /** Right-clicking a dagger swaps its attunement; the title that follows confirms which one it is. */
    private static void onRightClick(ItemStack stack) {
        FeatureConfigs.BlazeSlayer config = config();
        if (config == null || !config.daggers || clientSideClicked || !inCrimsonIsle()) return;
        Dagger dagger = daggerOf(stack);
        if (dagger == null) return;
        for (Shield shield : dagger.shields) shield.active = !shield.active;
        clientSideClicked = true;
    }

    /** Hypixel's attunement title ("ASHEN"): sets that dagger's attunement. Returns true to hide the title. */
    public static boolean onTitle(Component title) {
        FeatureConfigs.BlazeSlayer config = config();
        if (config == null || !config.daggers || title == null || !inCrimsonIsle()) return false;
        String text = Sky2MLocation.strip(title.getString()).trim();
        for (Shield shield : Shield.values()) {
            if (!text.matches(".*\\b" + shield.name() + "\\b.*")) continue;
            for (Dagger dagger : Dagger.values()) {
                if (dagger.shields[0] != shield && dagger.shields[1] != shield) continue;
                for (Shield s : dagger.shields) s.active = false;
                dagger.updated = true;
            }
            shield.active = true;
            clientSideClicked = false;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ fire pits

    /** Called with your tier III or IV Inferno Demonlord's health: warns when it drops below a third. */
    static void onBossHealth(double previous, double health, double maxHealth) {
        FeatureConfigs.BlazeSlayer config = config();
        if (config == null || !config.firePitsWarning || maxHealth <= 0) return;
        double third = maxHealth * 0.33;
        if (health < third && previous > third) {
            firePitsAt = System.currentTimeMillis();
            Minecraft mc = Minecraft.getInstance();
            mc.gui.hud.setTimes(0, 40, 5);
            mc.gui.hud.setTitle(Component.literal("Fire Pits!").withStyle(ChatFormatting.RED));
            mc.gui.hud.setSubtitle(Component.empty());
        }
    }

    // ------------------------------------------------------------------------------------------------ clear view

    /** An Inferno Demonlord, or one of its demons, within ten blocks (checked every three seconds). */
    private static boolean blazeBossNear(Minecraft mc) {
        for (Entity entity : mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(10), e -> SlayerFeatures.nametag(e) != null)) {
            String text = SlayerFeatures.nametag(entity).getString();
            if (text.contains("Inferno Demonlord") || text.contains("Quazii") || text.contains("Typhoeus")) return true;
        }
        return false;
    }

    public static boolean hideParticle() {
        FeatureConfigs.BlazeSlayer config = config();
        return nearBlaze && config != null && config.clearView;
    }

    public static boolean hideFireballs() {
        return hideParticle();
    }

    // ------------------------------------------------------------------------------------------------ fire pillar

    private static void updatePillar(Minecraft mc) {
        if (pillarEntity != -1) {
            Entity entity = mc.level.getEntity(pillarEntity);
            Component tag = entity == null ? null : SlayerFeatures.nametag(entity);
            Matcher m = tag == null ? null : FIRE_PILLAR.matcher(Sky2MLocation.strip(tag.getString()).trim());
            if (m != null && m.matches()) {
                pillarLines = pillarLine(m.group(1));
                return;
            }
            pillarEntity = -1;
            pillarLines = List.of();
        }
        if (ticks % 5 != 0) return;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > 48 * 48) continue;
            Component tag = SlayerFeatures.nametag(entity);
            if (tag == null) continue;
            Matcher m = FIRE_PILLAR.matcher(Sky2MLocation.strip(tag.getString()).trim());
            if (!m.matches()) continue;
            pillarEntity = entity.getId();
            pillarLines = pillarLine(m.group(1));
            return;
        }
    }

    private static List<Component> pillarLine(String seconds) {
        return List.of(Component.literal("Fire Pillar: ").withStyle(ChatFormatting.RED).append(Component.literal(seconds + "s").withStyle(ChatFormatting.AQUA)));
    }

    // ------------------------------------------------------------------------------------------------ render

    private static void render(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector) {
        FeatureConfigs.BlazeSlayer config = config();
        if (config == null || !config.coloredMobs || shieldMobs.isEmpty()) return;
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        for (Map.Entry<LivingEntity, Shield> entry : shieldMobs.entrySet()) {
            LivingEntity mob = entry.getKey();
            if (!mob.isAlive()) continue;
            Shield shield = entry.getValue();
            MutableComponent label = Component.literal(shield.name() + " ♨" + shieldCharges.getOrDefault(mob, "")).withStyle(shield.colour);
            Vec3 pos = mob.getPosition(partial).add(0, mob.getBbHeight() + 1.2, 0);
            collector.submitText(label, pos, 1.5f, false);
        }
    }
}
