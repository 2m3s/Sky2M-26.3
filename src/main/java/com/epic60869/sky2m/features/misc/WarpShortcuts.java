package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.util.Locale;
import java.util.Set;

/**
 * Short warp commands: /dhub sends /warp dhub, /crypts sends /warp crypts, and so on for every warp name, from
 * Skysoft's WarpAliases (https://github.com/Akinsoft/Skysoft, LGPL-3.0) and SkyHanni's ShortenWarpCommand
 * (https://github.com/hannibal002/SkyHanni, LGPL-2.1, with its repo's Warps.json list). In the Garden, SkyHanni's
 * GardenWarpCommands: /home warps to the Garden, /barn goes to the barn and /tp &lt;plot&gt; to that plot.
 *
 * <p>The command is rewritten as it's sent (Sky2MCommandCaseMixin), so nothing needs registering and the setting
 * applies straight away. The names are also registered (without doing anything) so they tab-complete.
 */
public final class WarpShortcuts {
    private static final Set<String> WARPS = Set.of(
        "arachne", "atoll", "backwater", "barn", "base", "basecamp", "bayou", "camp", "canyon", "carnival", "castle", "ch",
        "ci", "cn", "crimson", "crypt", "crypts", "crystals", "da", "deep", "deeper", "desert", "dh", "dhub", "dmines",
        "drag", "dragons", "dungeon_hub", "dungeons", "dwarves", "elizabeth", "end", "farming", "foraging", "forge",
        "galatea", "garden", "glacite", "glowing", "gold", "gt", "hollows", "home", "howl", "howling_cave", "hub",
        "island", "isle", "jerry", "jungle", "kuudra", "loch", "lotus", "mines", "moby", "moonglade", "mound", "murk",
        "murkwater", "museum", "nest", "nether", "nuc", "nucleus", "park", "rift", "safari", "sepulture", "skull",
        "smold", "smoldering", "smoldering_tomb", "spider", "spiders", "spring", "springs", "stonks", "taylor",
        "the_rift", "top", "torrhus", "tower", "trap", "trapper", "trees", "tunnel", "tunnels", "village", "void",
        "winter", "wiz", "wizard", "wizard_tower", "workshop");

    private WarpShortcuts() {}

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.misc.warpShortcuts;
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String name : WARPS) {
                // No executes: an "incomplete" client command goes to the server, after rewrite() has run.
                if (dispatcher.getRoot().getChild(name) == null) dispatcher.register(ClientCommands.literal(name).requires(s -> enabled()));
            }
        });
    }

    /** The command to send instead of {@code command} (without its slash), or {@code command} itself. */
    public static String rewrite(String command) {
        if (command == null || !enabled() || !Sky2MLocation.onSkyblock()) return command;
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (Sky2MLocation.inGarden()) {
            if (normalized.equals("home")) return "warp garden";
            if (normalized.equals("barn")) return "plottp barn";
            if (normalized.startsWith("tp ")) {
                String plot = normalized.substring(3).trim();
                if (!plot.isEmpty()) return "plottp " + plot;
            }
        }
        // /jerry already means something on your island.
        if (normalized.equals("jerry") && Sky2MLocation.areaIs("Private Island")) return command;
        return WARPS.contains(normalized) ? "warp " + normalized : command;
    }
}
