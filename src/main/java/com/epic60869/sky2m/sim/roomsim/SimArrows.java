package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.SimItemIds;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Arrows and arrow poisons in the sim, and the bows' own on-hit passives (Last Breath, Death Bow).
 *
 * <p>An arrow a sim player fires - a Terminator's or a plain bow's - is tagged the moment it enters the world with
 * what it was loaded with: the arrow type (the ammunition the bow drew, or for the Terminator, which uses none in here,
 * the first arrow stack he carries, as Hypixel's quiver would) and the first arrow poison in his inventory, which is
 * used up then - one per arrow, as on Hypixel. The INFINITE Twilight Arrow Poison is the sim's own item for the user
 * and is never used up. Poisons do nothing while the Skeleton Master Chestplate is worn (Hypixel's rule).
 *
 * <p>Effects when a tagged arrow hits (Hypixel's wiki text, research/misc-talismans.md):
 * <ul>
 * <li><b>Bouncy Arrow</b>: 50% to bounce to another target, up to 4 times, each bounce 15% less damage.</li>
 * <li><b>Armorshred Arrow</b>: the hit ignores 5% of the target's Defense.</li>
 * <li><b>Nansorb Arrow</b>: a piercing arrow's later victims take +7.5% more than the one before, up to 5 victims
 *     (it replaces Piercing's damage reduction; that reduction belongs to the enchantment model).</li>
 * <li><b>Twilight Arrow Poison</b>: the target takes +10% damage from everything for 20 s.</li>
 * <li><b>Toxic Arrow Poison</b>: the arrow deals +10% damage (its 24% mob-healing cut has nothing to act on here).</li>
 * <li><b>Last Breath</b>: each hit takes 10% of the target's Defense, stacking 5 times.</li>
 * <li><b>Death Bow</b>: +100% damage to Undead, 50% to bounce to another target.</li>
 * </ul>
 */
public final class SimArrows {

    public static final String FLINT_ARROW_ID = "ARROW";
    public static final String BOUNCY_ARROW_ID = "BOUNCY_ARROW";
    public static final String ARMORSHRED_ARROW_ID = "ARMORSHRED_ARROW";
    public static final String NANSORB_ARROW_ID = "NANSORB_ARROW";
    public static final String TWILIGHT_POISON_ID = "TWILIGHT_ARROW_POISON";
    public static final String TOXIC_POISON_ID = "TOXIC_ARROW_POISON";
    /** The user's own sim item: a Twilight Arrow Poison that never runs out. Not a Hypixel item. */
    public static final String INFINITE_TWILIGHT_POISON_ID = "INFINITE_TWILIGHT_ARROW_POISON";

    public static final String LAST_BREATH_ID = "LAST_BREATH";
    public static final String DEATH_BOW_ID = "DEATH_BOW";

    private static final Set<String> ARROWS = Set.of(FLINT_ARROW_ID, BOUNCY_ARROW_ID, ARMORSHRED_ARROW_ID,
            NANSORB_ARROW_ID);
    private static final Set<String> POISONS = Set.of(TWILIGHT_POISON_ID, TOXIC_POISON_ID, INFINITE_TWILIGHT_POISON_ID);

    /** What one arrow was loaded with. {@code hits} counts the victims it has struck (Nansorb). */
    private static final class Load {
        final String arrow;
        final String poison;
        final String weapon;
        int hits;

        Load(String arrow, String poison, String weapon) {
            this.arrow = arrow;
            this.poison = poison;
            this.weapon = weapon;
        }
    }

    /** What an arrow hit does to the hit: a damage multiplier and a share of Defense ignored. */
    public record Effect(double multiplier, double ignoreDefense) {
        static final Effect NONE = new Effect(1, 0);
    }

    private static final Map<Entity, Load> LOADS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Set while a bounce's hits are dealt, so a bounce does not roll its own bounces. Server thread. */
    private static boolean bouncing;

    private SimArrows() {
    }

    static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AbstractArrow arrow && entity.tickCount == 0
                    && arrow.getOwner() instanceof ServerPlayer sp && SimState.isActive() && SimAbilities.simServer(sp)) {
                load(sp, arrow);
            }
        });
    }

    static void reset() {
        LOADS.clear();
    }

    /** Tags a freshly fired arrow and uses up its poison. Server thread. */
    private static void load(ServerPlayer sp, AbstractArrow arrow) {
        String weapon = SimTerminator.shotYaw(arrow) != null ? SimTerminator.ITEM_ID
                : SimItemIds.skyblockId(arrow.getWeaponItem());
        String arrowType = SimItemIds.skyblockId(arrow.getPickupItemStackOrigin());
        if (arrowType == null || !ARROWS.contains(arrowType)) {
            arrowType = firstOf(sp, ARROWS);
        }
        String poison = null;
        if (!skeletonMaster(sp)) {
            ItemStack stack = firstStack(sp, POISONS);
            if (stack != null) {
                poison = SimItemIds.skyblockId(stack);
                if (!INFINITE_TWILIGHT_POISON_ID.equals(poison)) {
                    stack.shrink(1);
                }
            }
        }
        LOADS.put(arrow, new Load(arrowType, poison, weapon));
    }

    /** Hypixel: arrow poisons do not work with the Skeleton Master Chestplate's passive. */
    static boolean skeletonMaster(ServerPlayer sp) {
        return "SKELETON_MASTER_CHESTPLATE".equals(SimItemIds.skyblockId(sp.getItemBySlot(EquipmentSlot.CHEST)));
    }

    private static String firstOf(ServerPlayer sp, Set<String> ids) {
        ItemStack s = firstStack(sp, ids);
        return s == null ? null : SimItemIds.skyblockId(s);
    }

    /** The first stack with one of these ids: off hand, main hand, then the inventory in order (vanilla's search). */
    private static ItemStack firstStack(ServerPlayer sp, Set<String> ids) {
        for (InteractionHand hand : new InteractionHand[]{InteractionHand.OFF_HAND, InteractionHand.MAIN_HAND}) {
            ItemStack st = sp.getItemInHand(hand);
            String id = SimItemIds.skyblockId(st);
            if (id != null && ids.contains(id) && !st.isEmpty()) {
                return st;
            }
        }
        for (ItemStack st : sp.getInventory().getNonEquipmentItems()) {
            String id = SimItemIds.skyblockId(st);
            if (id != null && ids.contains(id) && !st.isEmpty()) {
                return st;
            }
        }
        return null;
    }

    static boolean undead(LivingEntity e) {
        return e.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD);
    }

    /** A tagged arrow has hit {@code target}. Server thread, inside the damage event. */
    static Effect onArrowHit(ServerPlayer sp, AbstractArrow arrow, LivingEntity target) {
        Load l = LOADS.get(arrow);
        String weapon = l != null ? l.weapon : SimItemIds.skyblockId(sp.getMainHandItem());
        double mult = 1;
        double ignore = 0;
        if (LAST_BREATH_ID.equals(weapon)) {
            SimItemEffects.shred(target);
        }
        if (DEATH_BOW_ID.equals(weapon) && undead(target)) {
            mult *= 2.0;
        }
        if (l != null) {
            l.hits++;
            if (TOXIC_POISON_ID.equals(l.poison)) {
                mult *= 1.10;
            } else if (l.poison != null) {
                SimItemEffects.twilight(target);
            }
            if (ARMORSHRED_ARROW_ID.equals(l.arrow)) {
                ignore += 0.05;
            }
            if (NANSORB_ARROW_ID.equals(l.arrow) && l.hits > 1) {
                mult *= 1 + 0.075 * (Math.min(5, l.hits) - 1);
            }
        }
        if (!bouncing && sp.level() instanceof ServerLevel level) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            if (l != null && BOUNCY_ARROW_ID.equals(l.arrow) && rng.nextDouble() < 0.5) {
                bounce(sp, level, target, weapon, 1, 4);
            } else if (DEATH_BOW_ID.equals(weapon) && rng.nextDouble() < 0.5) {
                bounce(sp, level, target, weapon, 1, 1);
            }
        }
        return mult == 1 && ignore == 0 ? Effect.NONE : new Effect(mult, ignore);
    }

    /**
     * One bounce from {@code from} to the nearest other enemy within 6 blocks, dealt on the next server task so it is
     * not inside this hit's damage event; a Bouncy Arrow keeps bouncing (50% each time) up to {@code max}, 15% less
     * each time.
     */
    private static void bounce(ServerPlayer sp, ServerLevel level, LivingEntity from, String weapon, int n, int max) {
        LivingEntity next = null;
        double best = 36;
        var players = level.getServer().getPlayerList();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, from.getBoundingBox().inflate(6),
                e -> e.isAlive() && e != from && e != sp && !(e instanceof ArmorStand)
                        && !(e instanceof ServerPlayer p && players.getPlayer(p.getUUID()) == p))) {
            double d = e.distanceToSqr(from);
            if (d < best) {
                best = d;
                next = e;
            }
        }
        if (next == null) {
            return;
        }
        LivingEntity target = next;
        Vec3 a = from.getBoundingBox().getCenter();
        double factor = max > 1 ? Math.pow(0.85, n) : 1.0;
        level.getServer().execute(() -> {
            if (!target.isAlive()) {
                return;
            }
            Vec3 b = target.getBoundingBox().getCenter();
            Vec3 d = b.subtract(a);
            for (double t = 0; t < 1; t += 0.1) {
                Vec3 p = a.add(d.scale(t));
                level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
            boolean before = bouncing;
            bouncing = true;
            try {
                SimDamage.strike(sp, null, SimDamage.Type.ARROW, weapon, factor,
                        () -> target.hurtServer(level, level.damageSources().playerAttack(sp), 10_000f));
            } finally {
                bouncing = before;
            }
            if (n < max && ThreadLocalRandom.current().nextDouble() < 0.5) {
                bounce(sp, level, target, weapon, n + 1, max);
            }
        });
    }

    /** Whether this arrow was loaded with Nansorb, which replaces Piercing's damage reduction. HOOK for the
     *  enchantment model's Piercing. */
    public static boolean nansorb(Entity arrow) {
        Load l = LOADS.get(arrow);
        return l != null && NANSORB_ARROW_ID.equals(l.arrow);
    }
}
