package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Misc > Visual Tweaks: client-side rendering options. Fullbright (ambient light or gamma), fog distance, hiding
 * fire, lightning, clouds, dead and far-away entities, an armor hider and a particle hider with presets, each with an
 * optional list of islands it applies to; plus NoFrills' disconnect fix. Ported from NoFrills (Fullbright, No Render,
 * Disconnect Fix), SkyHanni (hide armor, hide particles) and SkyOcean. These only change what is drawn on your own
 * screen; none of them changes what the server sees or gives any gameplay advantage.
 */
public final class VisualTweaks {
    /** Hypixel's health stand over a dead mob: "Zealot 0❤" or "Zealot 0/13M❤". */
    private static final Pattern DEAD_STAND = Pattern.compile(".* 0(?:/.*)?❤");

    private static Set<String> hiddenParticles = Set.of();
    private static String hiddenParticlesSource;
    private static Preset hiddenParticlesPreset;

    private VisualTweaks() {}

    public enum Light {
        OFF("Off"), AMBIENT("Ambient"), GAMMA("Gamma");

        private final String label;

        Light(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public enum ArmorHide {
        OFF("Off"), SELF("Only yours"), OTHERS("Others"), ALL("Everyone's");

        private final String label;

        ArmorHide(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public enum Preset {
        OFF("Off"), MINIMAL("Minimal"), NO_AMBIENT("No Ambient"), ALL("All particles");

        private final String label;

        Preset(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    private static final Set<String> MINIMAL = new HashSet<>(java.util.List.of("smoke", "large_smoke", "white_smoke", "poof", "cloud", "campfire_cosy_smoke",
        "campfire_signal_smoke", "dust_plume", "gust", "small_gust", "explosion", "explosion_emitter", "sneeze"));
    private static final Set<String> NO_AMBIENT = new HashSet<>(java.util.List.of("smoke", "large_smoke", "white_smoke", "poof", "cloud", "campfire_cosy_smoke",
        "campfire_signal_smoke", "dust_plume", "gust", "small_gust", "explosion", "explosion_emitter", "sneeze",
        "enchant", "portal", "reverse_portal", "witch", "end_rod", "happy_villager", "note", "heart", "flame", "small_flame",
        "soul_fire_flame", "soul", "ash", "white_ash", "crimson_spore", "warped_spore", "mycelium", "firework", "flash",
        "falling_dust", "dripping_water", "dripping_lava", "falling_water", "falling_lava", "splash", "bubble", "underwater",
        "rain", "snowflake", "lava", "wax_on", "wax_off", "scrape", "electric_spark", "glow", "dolphin", "nautilus",
        "trial_spawner_detection", "vault_connection", "infested", "sculk_charge_pop", "sculk_soul"));

    public static final class Config {
        @Expose
        @ConfigOption(name = "Fullbright", desc = "Light everything up so caves and the dark are as bright as day. Ambient lights the world up without changing your gamma; Gamma turns your brightness way up while it is on. Only affects your own screen.")
        @ConfigEditorDropdown
        public Light fullbright = Light.OFF;

        @Expose
        @ConfigOption(name = "Fullbright Islands", desc = "Only use Fullbright on these islands/areas, separated by commas (for example Dwarven Mines, Crystal Hollows, Mineshaft). Empty: everywhere.")
        @ConfigEditorText
        public String fullbrightIslands = "";

        @Expose
        @ConfigOption(name = "Remove Fog", desc = "Take the distance fog away so you can see the whole render distance. Only affects your own screen.")
        @ConfigEditorBoolean
        public boolean removeFog = false;

        @Expose
        @ConfigOption(name = "Fog Distance", desc = "How far away the fog starts, as a percentage of vanilla (100 = vanilla). Ignored while Remove Fog is on.")
        @ConfigEditorSlider(minValue = 100, maxValue = 1000, minStep = 25)
        public float fogDistance = 100;

        @Expose
        @ConfigOption(name = "Fog Islands", desc = "Only change the fog on these islands/areas, separated by commas. Empty: everywhere.")
        @ConfigEditorText
        public String fogIslands = "";

        @Expose
        @ConfigOption(name = "Hide Clouds", desc = "Don't draw the clouds.")
        @ConfigEditorBoolean
        public boolean hideClouds = false;

        @Expose
        @ConfigOption(name = "Hide Lightning", desc = "Don't draw lightning bolts (Storm, the Thunder sea creature and Hypixel's lightning effects).")
        @ConfigEditorBoolean
        public boolean hideLightning = false;

        @Expose
        @ConfigOption(name = "Hide Fire Overlay", desc = "Don't draw the fire on your screen while you are burning.")
        @ConfigEditorBoolean
        public boolean hideFireOverlay = false;

        @Expose
        @ConfigOption(name = "Hide Entity Fire", desc = "Don't draw the fire on burning mobs and players.")
        @ConfigEditorBoolean
        public boolean hideEntityFire = false;

        @Expose
        @ConfigOption(name = "Hide Effects Islands", desc = "Only hide clouds, lightning and fire on these islands/areas, separated by commas. Empty: everywhere.")
        @ConfigEditorText
        public String hideIslands = "";

        @Expose
        @ConfigOption(name = "Hide Dead Entities", desc = "Don't draw mobs that have died (their fading corpse) and the health stand left over them, as soon as they hit 0 health.")
        @ConfigEditorBoolean
        public boolean hideDead = false;

        @Expose
        @ConfigOption(name = "Hide Far Entities", desc = "Don't draw mobs and armor stands further away than the distance below. Players are always drawn.")
        @ConfigEditorBoolean
        public boolean hideFar = false;

        @Expose
        @ConfigOption(name = "Far Distance", desc = "How many blocks away mobs stop being drawn for Hide Far Entities.")
        @ConfigEditorSlider(minValue = 8, maxValue = 128, minStep = 4)
        public float farDistance = 48;

        @Expose
        @ConfigOption(name = "Armor Hider", desc = "Don't draw armor on players, like SkyHanni's hide armor: only yours, other players', or everyone's. Choose the pieces below.")
        @ConfigEditorDropdown
        public ArmorHide armor = ArmorHide.OFF;

        @Expose @ConfigOption(name = "Hide Helmets", desc = "Armor Hider also hides helmets.") @ConfigEditorBoolean public boolean armorHead = true;
        @Expose @ConfigOption(name = "Hide Chestplates", desc = "Armor Hider also hides chestplates.") @ConfigEditorBoolean public boolean armorChest = true;
        @Expose @ConfigOption(name = "Hide Leggings", desc = "Armor Hider also hides leggings.") @ConfigEditorBoolean public boolean armorLegs = true;
        @Expose @ConfigOption(name = "Hide Boots", desc = "Armor Hider also hides boots.") @ConfigEditorBoolean public boolean armorFeet = true;

        @Expose
        @ConfigOption(name = "Particle Hider", desc = "Hide particles in presets: smoke and puffs, all ambient effects (enchant, portal, flames, hearts, notes, drips...), or every particle. Your damage numbers and mob names are not particles and stay.")
        @ConfigEditorDropdown
        public Preset particles = Preset.OFF;

        @Expose
        @ConfigOption(name = "Hidden Particles", desc = "More particles to hide, by name, separated by commas (for example crit, damage_indicator, flame, happy_villager).")
        @ConfigEditorText
        public String particleList = "";

        @Expose
        @ConfigOption(name = "Disconnect Fix", desc = "NoFrills' fix: an entity data packet Hypixel sends in a broken form is skipped instead of kicking you with \"Network Protocol Error\".")
        @ConfigEditorBoolean
        public boolean disconnectFix = false;
    }

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.visualTweaks;
    }

    /** True if {@code islands} is empty or names the island/area you are on (case insensitive). */
    private static boolean onIsland(String islands) {
        if (islands == null || islands.isBlank()) return true;
        String area = Sky2MLocation.area();
        if (area == null || area.isEmpty()) return false;
        for (String part : islands.split(",")) {
            String p = part.trim();
            if (!p.isEmpty() && p.equalsIgnoreCase(area)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ light and fog

    /** True if the lightmap should be fully lit. */
    public static boolean ambientLight() {
        Config c = config();
        return c != null && c.fullbright == Light.AMBIENT && onIsland(c.fullbrightIslands);
    }

    /** True if the gamma should be forced up. */
    public static boolean gammaLight() {
        Config c = config();
        return c != null && c.fullbright == Light.GAMMA && onIsland(c.fullbrightIslands);
    }

    /** What the fog distances are multiplied by: 1 for vanilla, a huge number to remove it. */
    public static float fogFactor() {
        Sky2MConfig root = Sky2MConfig.current();
        if (root != null && root.misc.random.removeAllFog) return 1_000_000f;
        Config c = config();
        if (c == null || !onIsland(c.fogIslands)) return 1f;
        if (c.removeFog) return 1_000_000f;
        return Math.max(1f, c.fogDistance / 100f);
    }

    // ------------------------------------------------------------------------------------------ hiding

    public static boolean hideClouds() {
        Config c = config();
        return c != null && c.hideClouds && onIsland(c.hideIslands);
    }

    public static boolean hideFireOverlay() {
        Config c = config();
        return c != null && c.hideFireOverlay && onIsland(c.hideIslands);
    }

    public static boolean hideEntityFire() {
        Config c = config();
        return c != null && c.hideEntityFire && onIsland(c.hideIslands);
    }

    /** True if the entity should not be drawn at all. */
    public static boolean hideEntity(Entity entity) {
        Config c = config();
        if (c == null) return false;
        if (c.hideLightning && entity instanceof LightningBolt && onIsland(c.hideIslands)) return true;
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player || entity == mc.getCameraEntity() || entity instanceof Player) return false;
        if (c.hideDead) {
            if (entity instanceof LivingEntity living && !(entity instanceof ArmorStand) && living.isDeadOrDying()) return true;
            if (entity instanceof ArmorStand stand && stand.hasCustomName()
                && DEAD_STAND.matcher(Sky2MLocation.strip(stand.getCustomName().getString())).matches()) return true;
        }
        if (c.hideFar && mc.player != null && (entity instanceof LivingEntity || entity instanceof ArmorStand)) {
            double max = c.farDistance;
            return entity.distanceToSqr(mc.player) > max * max;
        }
        return false;
    }

    /** True if the armor in this slot should not be drawn on this player ({@code self}: it is you). */
    public static boolean hideArmor(EquipmentSlot slot, boolean self) {
        Config c = config();
        if (c == null || c.armor == ArmorHide.OFF) return false;
        boolean who = switch (c.armor) {
            case SELF -> self;
            case OTHERS -> !self;
            default -> true;
        };
        if (!who) return false;
        return switch (slot) {
            case HEAD -> c.armorHead;
            case CHEST -> c.armorChest;
            case LEGS -> c.armorLegs;
            case FEET -> c.armorFeet;
            default -> false;
        };
    }

    public static boolean hideParticle(ParticleOptions options) {
        Config c = config();
        if (c == null || (c.particles == Preset.OFF && (c.particleList == null || c.particleList.isBlank()))) return false;
        if (c.particles == Preset.ALL) return true;
        if (!c.particleList.equals(hiddenParticlesSource) || c.particles != hiddenParticlesPreset) {
            Set<String> set = new HashSet<>();
            if (c.particles == Preset.MINIMAL) set.addAll(MINIMAL);
            if (c.particles == Preset.NO_AMBIENT) set.addAll(NO_AMBIENT);
            for (String part : c.particleList.split(",")) {
                String p = part.trim().toLowerCase(Locale.ROOT);
                if (p.startsWith("minecraft:")) p = p.substring("minecraft:".length());
                if (!p.isEmpty()) set.add(p);
            }
            hiddenParticles = set;
            hiddenParticlesSource = c.particleList;
            hiddenParticlesPreset = c.particles;
        }
        var key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        return key != null && hiddenParticles.contains(key.getPath());
    }

    public static boolean disconnectFix() {
        Config c = config();
        return c != null && c.disconnectFix;
    }
}
