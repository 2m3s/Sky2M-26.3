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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RNG drop totem animation, ported from Skyblocker's RareDropSpecialEffects and SpecialEffects: when a big RNG drop
 * lands, the item pops up like a Totem of Undying, with particles and the challenge-complete sound. It plays for
 * Skyblocker's list of RNG drops, any PRAY TO RNGESUS or RNG METER drop, and dyes; with Garden Drops on, also for the
 * special Garden drops (farming dyes and the Ray of Helios) in any Garden drop line.
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

    private static boolean gardenEnabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.combat.rareDrops.totemAnimation && c.combat.rareDrops.totemGardenDrops;
    }

    /** An RNG drop: one on Skyblocker's list (slayer, Diana and fishing RNG items), any PRAY TO RNGESUS or RNG METER drop, or any dye. */
    static boolean isRngDrop(String type, String item) {
        return RNG_DROPS.containsKey(item) || type.startsWith("PRAY TO RNGESUS") || type.startsWith("RNG METER") || item.endsWith(" Dye");
    }

    /** A special Garden drop: a farming dye or a Ray of Helios. */
    private static final Pattern GARDEN_SPECIAL = Pattern.compile("\\b(?<item>(?:[A-Z][\\w'-]* )+Dye|Ray of Helios)\\b");

    /** Whether this item is a special Garden drop (a dye or a Ray of Helios). */
    static boolean isGardenSpecial(String item) {
        return item.endsWith(" Dye") || item.equals("Ray of Helios");
    }

    /**
     * The special Garden drop in a drop line ("RARE DROP!", "RARE CROP!", "WOW! You found ...", "... found ..."), or
     * null. Lines from players ("Guild > Name: ...", "Name: ...") are not drops.
     */
    static String gardenSpecialDrop(String line) {
        boolean dropLine = line.contains("DROP!") || line.contains("CROP!") || line.trim().startsWith("WOW!") || line.contains(" found ");
        if (!dropLine) return null;
        Matcher m = GARDEN_SPECIAL.matcher(line);
        if (!m.find() || line.substring(0, m.start()).contains(":")) return null;
        return m.group("item").trim();
    }

    /** A rare drop line ({@link CombatFeatures}): {@code type} is "RARE DROP!", "PRAY TO RNGESUS DROP!", .... */
    static void onDrop(String type, String item) {
        if (!enabled() || !isRngDrop(type, item)) return;
        play(item, false);
    }

    /** A special Garden drop (dye or Ray of Helios) in the Garden: plays with Rare Drop Totem > Garden Drops on. */
    static void onGardenDrop(String item) {
        if (!gardenEnabled()) return;
        play(item, true);
    }

    private static String lastItem;
    private static long lastAt;

    /** The item the totem animation last played for (for the game tests). */
    public static String lastAnimated() {
        return lastItem;
    }

    /**
     * Pops the item up like a Totem of Undying. The same item twice within 3 seconds (one drop read from two chat
     * lines, or by two triggers) plays once. With {@code fallback}, an item missing from the item repo pops up as a
     * Nether Star instead of not playing.
     */
    private static void play(String item, boolean fallback) {
        String id = RNG_DROPS.get(item);
        if (id == null) id = RepoItems.idByName(item);
        if (id == null && item.endsWith(" Dye")) id = "DYE_" + item.substring(0, item.lastIndexOf(' ')).replace(' ', '_').toUpperCase(Locale.ROOT);
        if (id == null && fallback) id = item.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").trim().replace(' ', '_');
        ItemStack found = id == null ? ItemStack.EMPTY : RepoItems.itemStack(id);
        if (found.isEmpty() || found.is(Items.BARRIER)) {
            if (!fallback) return;
            found = new ItemStack(Items.NETHER_STAR);
        }
        ItemStack stack = found;
        long now = System.currentTimeMillis();
        synchronized (RareDropTotem.class) {
            if (item.equals(lastItem) && now - lastAt < 3000) return;
            lastItem = item;
            lastAt = now;
        }
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
