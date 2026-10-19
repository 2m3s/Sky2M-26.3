package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Misc > Visual > Hit Colour: changes the colour of the hurt flash on entities, like Lunar Client's Hit Color mod.
 * <p>
 * Vanilla tints hurt entities with the entity overlay texture (16x16), whose rows 0-7 are red and sampled at row
 * {@code OverlayTexture.v(true) == 3}. When enabled, rows 0-3 are repainted with the chosen colour, and rows 4-7 stay
 * vanilla red: entities filtered out by the options here get their overlay V moved to row 6, so they keep the normal
 * red flash. When disabled, the original red is painted back and every entity uses row 3 again (exactly vanilla).
 */
public final class HitColour {
    /** Vanilla's red overlay pixel (ARGB), from OverlayTexture's constructor. */
    public static final int VANILLA_RED = 0xB2FF0000;
    /** Overlay V row that is never repainted (still vanilla red), for entities filtered out. */
    public static final int VANILLA_RED_V = 6;
    /** Set on a render state when that entity should keep the vanilla red flash. */
    public static final RenderStateDataKey<Boolean> KEEP_VANILLA = RenderStateDataKey.create(() -> "sky2m:hit_colour_vanilla");

    /** How long (ticks) an entity you attacked counts as "hit by you". */
    private static final int HIT_MEMORY_TICKS = 40;

    private static DynamicTexture texture;
    private static int painted = VANILLA_RED;
    private static boolean registered;
    private static long tick;
    private static final Int2IntOpenHashMap hitByYou = new Int2IntOpenHashMap();

    private HitColour() {}

    /** Called from the OverlayTexture constructor mixin with its texture. */
    public static void init(DynamicTexture overlay) {
        texture = overlay;
        painted = VANILLA_RED;
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> HitColour.tick());
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) hitByYou.put(entity.getId(), (int) tick);
            return InteractionResult.PASS;
        });
        // Right-click abilities (Soul Whip, Flaming Flay...) hurt mobs at range without an attack: see markAbilityHits.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) abilityUntil = tick + ABILITY_TICKS;
            return InteractionResult.PASS;
        });
    }

    /** How long after a right-click (ticks) mobs hurt in front of you count as your hits: Flaming Flay keeps hitting. */
    private static final int ABILITY_TICKS = 60;
    /** How far, and how wide a cone around where you look, an ability's hits are looked for. */
    private static final double ABILITY_RANGE = 20, ABILITY_CONE_COS = Math.cos(Math.toRadians(40)), ABILITY_NEAR = 4;
    private static long abilityUntil;

    /**
     * For Only Entities You Hit: Soul Whip, Flaming Flay and other right-click weapons damage mobs without an attack,
     * so nothing marks those mobs as yours. Shortly after you right-click, a mob that is hurt within range in front of
     * you (or right next to you) counts as hit by you.
     */
    private static void markAbilityHits(Sky2MConfig.HitColour c) {
        if (!c.enabled || !c.onlyYourHits || tick > abilityUntil) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        for (LivingEntity entity : mc.level.getEntitiesOfClass(LivingEntity.class, mc.player.getBoundingBox().inflate(ABILITY_RANGE),
            e -> e != mc.player && e.hurtTime > 0)) {
            Vec3 to = entity.getBoundingBox().getCenter().subtract(eye);
            double distance = to.length();
            if (distance > ABILITY_RANGE) continue;
            if (distance <= ABILITY_NEAR || to.normalize().dot(look) >= ABILITY_CONE_COS) hitByYou.put(entity.getId(), (int) tick);
        }
    }

    private static Sky2MConfig.HitColour config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.hitColour;
    }

    public static boolean enabled() {
        Sky2MConfig.HitColour c = config();
        return c != null && c.enabled;
    }

    private static void tick() {
        tick++;
        if (!hitByYou.isEmpty()) hitByYou.int2IntEntrySet().removeIf(e -> tick - e.getIntValue() > HIT_MEMORY_TICKS);
        Sky2MConfig.HitColour c = config();
        if (c != null) markAbilityHits(c);
        repaint(c != null && c.enabled ? colour(c.colour) : VANILLA_RED);
    }

    /** ARGB of a MoulConfig colour string; alpha is the flash strength (read from the string, chroma supported). */
    private static int colour(String value) {
        int rgb = ChromaColours.parse(value).getEffectiveColourRGB() & 0xFFFFFF;
        int alpha = 0xB2;
        if (value != null) {
            String[] parts = value.split(":");
            if (parts.length >= 4) {
                try {
                    // MoulConfig's colour editor treats this component as transparency (0 = opaque,
                    // 255 = fully transparent), while the overlay texture needs alpha (0 = invisible).
                    int transparency = Math.max(0, Math.min(255, Integer.parseInt(parts[parts.length - 4].trim())));
                    alpha = 255 - transparency;
                } catch (NumberFormatException ignored) {}
            }
        }
        return alpha << 24 | rgb;
    }

    private static void repaint(int argb) {
        if (texture == null || argb == painted) return;
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 16; x++) pixels.setPixel(x, y, argb);
        }
        texture.upload();
        painted = argb;
    }

    /** Whether this hurt entity should keep the vanilla red flash instead of the custom colour. */
    public static boolean keepVanilla(LivingEntity entity) {
        Sky2MConfig.HitColour c = config();
        if (c == null || !c.enabled) return false;
        boolean player = entity instanceof Player p && p.getUUID().version() == 4;
        if (player ? !c.players : !c.mobs) return true;
        if (c.onlyYourHits) {
            Minecraft mc = Minecraft.getInstance();
            if (entity == mc.player) return false;
            return !hitByYou.containsKey(entity.getId());
        }
        return false;
    }
}
