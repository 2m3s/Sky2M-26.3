package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * NoammAddons' BossBarHealth (https://github.com/Noamm9/NoammAddons, CC0-1.0: features/impl/dungeon/BossBarHealth.kt): the
 * boss's health as a number after its boss bar's name, worked out from the bar and the boss's maximum. Bosses whose
 * maximum isn't known get the bar's percentage instead (Skytils shows the withers that way).
 */
public final class BossBarHealth {
    private BossBarHealth() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.bossBarHealth;
    }

    /** The name to draw for a boss bar (Sky2MBossBarMixin). */
    public static Component barName(Component name, float progress) {
        if (Sky2MLocation.inDungeon()) SadanInterest.onBossBar(Sky2MLocation.strip(name.getString()), progress);
        Config c = config();
        if (c == null || !c.enabled || !Sky2MLocation.inDungeon()) return name;
        String plain = Sky2MLocation.strip(name.getString()).trim();
        if (plain.isEmpty()) return name;
        float bar = Math.clamp(progress, 0f, 1f);
        long max = maxHealth(bossName(plain), ScoreCalculator.floorNumber(), Sky2MLocation.dungeonFloor().startsWith("M"));
        if (max > 0) {
            return name.copy().append(Component.literal(" §r§8- §a" + format(Math.round((double) bar * max)) + "§7/§a" + format(max) + "§c❤"));
        }
        if (!c.otherBosses || plain.matches(".*[\\d%/].*")) return name;
        return name.copy().append(Component.literal(" §r§8- §d" + Math.round(bar * 100f) + "%"));
    }

    /** The boss's name without whatever decoration surrounds it on the bar. */
    private static String bossName(String plain) {
        return plain.replaceAll("^[^A-Za-z]+|[^A-Za-z]+$", "");
    }

    /** The boss's maximum health on this floor, or 0 when it isn't known. Public for the game test. */
    public static long maxHealth(String boss, int floor, boolean master) {
        return switch (boss) {
            // Blood mobs left to kill.
            case "The Watcher" -> (master ? 12 : 8) + floor;
            // Hits, not health.
            case "Thorn" -> floor == 4 ? (master ? 6 : 4) : 0;
            case "Maxor" -> floor == 7 ? (master ? 800_000_000L : 100_000_000L) : 0;
            case "Storm" -> floor == 7 ? (master ? 1_000_000_000L : 400_000_000L) : 0;
            case "Goldor" -> floor == 7 ? (master ? 1_200_000_000L : 750_000_000L) : 0;
            case "Necron" -> floor == 7 ? (master ? 1_400_000_000L : 1_000_000_000L) : 0;
            default -> 0;
        };
    }

    /** "1.4B", "400M", "12k" or "6". Public for the game test. */
    public static String format(long health) {
        if (health >= 1_000_000_000L) {
            return health % 1_000_000_000L == 0 ? health / 1_000_000_000L + "B" : String.format(Locale.US, "%.1fB", health / 1e9);
        }
        if (health >= 1_000_000L) return health / 1_000_000L + "M";
        if (health >= 1_000L) return health / 1_000L + "k";
        return String.valueOf(health);
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In a dungeon, show the boss's health as a number after its boss bar's name: The Watcher, Thorn and the F7/M7 withers (Maxor, Storm, Goldor, Necron). The Watcher keeps Blood Camp's count while Watcher Bar is on.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Percent On Other Bosses", desc = "Any other boss bar in a dungeon, such as Sadan's and his Giants', shows its percentage, because their health on each floor isn't known.")
        @ConfigEditorBoolean
        public boolean otherBosses = true;
    }
}
