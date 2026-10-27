package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;

/**
 * RNG drop totem animation, ported from Skyblocker's RareDropSpecialEffects and SpecialEffects: when a big RNG drop
 * lands, the item pops up like a Totem of Undying, with particles and the challenge-complete sound. It plays for
 * Skyblocker's list of RNG drops, any PRAY TO RNGESUS or RNG METER drop, and dyes.
 */
public final class RareDropTotem {
    /** Skyblocker's list: the drop name in chat -> its item id. */
    private static final Map<String, String> RNG_DROPS = Map.ofEntries(
        // Mythological Ritual
        Map.entry("Enchanted Book (Chimera I)", "ULTIMATE_CHIMERA;1"),
        Map.entry("Chimera I", "ULTIMATE_CHIMERA;1"),
        Map.entry("Fateful Stinger", "FATEFUL_STINGER"),
        Map.entry("Manti-core", "MANTI_CORE"),
        Map.entry("Minos Relic", "MINOS_RELIC"),
        Map.entry("Shimmering Wool", "SHIMMERING_WOOL"),
        // Slayer
        Map.entry("Scythe Blade", "SCYTHE_BLADE"),
        Map.entry("Shredded Sinew", "SHARD_OF_THE_SHREDDED"),
        Map.entry("Severed Hand", "SEVERED_HAND"),
        Map.entry("Warden Heart", "WARDEN_HEART"),
        Map.entry("Shriveled Wasp", "SHRIVELED_WASP"),
        Map.entry("Digested Mosquito", "DIGESTED_MOSQUITO"),
        Map.entry("Ensnared Snail", "ENSNARED_SNAIL"),
        Map.entry("Primordial Eye", "PRIMORDIAL_EYE"),
        Map.entry("Overflux Capacitor", "OVERFLUX_CAPACITOR"),
        Map.entry("End Stone Idol", "END_STONE_IDOL"),
        Map.entry("Judgement Core", "JUDGEMENT_CORE"),
        Map.entry("High Class Archfiend Dice", "HIGH_CLASS_ARCHFIEND_DICE"),
        // Fishing
        Map.entry("Prince's Crown Jewel", "PRINCES_CROWN_JEWEL"),
        Map.entry("Pocket-sized Igloo", "POCKET_SIZED_IGLOO"),
        Map.entry("Radioactive Vial", "RADIOACTIVE_VIAL"),
        Map.entry("Tiki Mask", "TIKI_MASK"),
        Map.entry("Titanoboa Shed", "TITANOBOA_SHED"));

    private RareDropTotem() {}

    /** The known RNG drops: name in chat -> item id (for /s2 rng). */
    static Map<String, String> rngDrops() {
        return RNG_DROPS;
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.combat.rareDrops.totemAnimation;
    }

    /** An RNG drop: one on Skyblocker's list (slayer, Diana and fishing RNG items), any PRAY TO RNGESUS or RNG METER drop, or any dye. */
    static boolean isRngDrop(String type, String item) {
        return RNG_DROPS.containsKey(item) || type.startsWith("PRAY TO RNGESUS") || type.startsWith("RNG METER") || item.endsWith(" Dye");
    }

    /** A rare drop line ({@link CombatFeatures}): {@code type} is "RARE DROP!", "PRAY TO RNGESUS DROP!", .... */
    static void onDrop(String type, String item) {
        if (!enabled() || !isRngDrop(type, item)) return;
        String id = RNG_DROPS.get(item);
        if (id == null) id = RepoItems.idByName(item);
        if (id == null && item.endsWith(" Dye")) id = "DYE_" + item.substring(0, item.lastIndexOf(' ')).replace(' ', '_').toUpperCase(Locale.ROOT);
        if (id == null) return;
        ItemStack stack = RepoItems.itemStack(id);
        if (stack.isEmpty() || stack.is(Items.BARRIER)) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayItemActivation(stack);
            if (mc.player != null) {
                mc.particleEngine.createTrackingEmitter(mc.player, ParticleTypes.SCRAPE, 30);
                mc.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1, 1f);
            }
        });
    }
}
