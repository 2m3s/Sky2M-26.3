package com.epic60869.sky2m.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class Sky2MKeyMappings {
    private static final Identifier CATEGORY_ID =
        Identifier.fromNamespaceAndPath("sky2m", "main");

    /** The Sky2M category in Options > Controls > Key Binds. Command Keys' binds are in it too. */
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(CATEGORY_ID);
    public static KeyMapping SEARCH;
    /** Shares the held (or hovered) item in Sky2M chat. Unbound until you pick a key. */
    public static KeyMapping SHARE_ITEM;
    /** Diana: warp to the warp closest to your next burrow or guess (SBO's guess warp key). */
    public static KeyMapping DIANA_GUESS_WARP;
    /** Diana: warp to the warp closest to the newest rare mob your party shared. */
    public static KeyMapping DIANA_RARE_MOB_WARP;
    /** Diana: answer the Sphinx (Sphinx Solver). Use at your own risk. */
    public static KeyMapping DIANA_SPHINX_SOLVER;
    /** Slayer: cycle the player whose boss is shown in the phase HUD. */
    public static KeyMapping SLAYER_BOSS_SELECT;
    /** /s2 protect: the held item, or the hovered item in a menu. Unbound until you pick a key. */
    public static KeyMapping PROTECT_ITEM;

    private static boolean initialized;

    /**
     * Registers Sky2M's key mappings during Fabric's client initialization.
     *
     * This must NOT happen from the client tick. Minecraft 26.2 has already
     * initialized GameOptions by the time the first tick runs, and registering
     * a key mapping then throws:
     * "GameOptions has already been initialised".
     */
    public static void init() {
        if (initialized) {
            return;
        }

        SEARCH = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.sky2m.search",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_O,
                CATEGORY
            )
        );

        SHARE_ITEM = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.sky2m.share_item",
                InputConstants.Type.KEYBOARD,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY
            )
        );

        com.epic60869.sky2m.features.misc.system.ToggleSprint.registerKey();
        com.epic60869.sky2m.features.qol.ChatPeek.registerKey();
        com.epic60869.sky2m.features.core.GlobalSearchScreen.registerKey();

        DIANA_GUESS_WARP = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.diana_guess_warp", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
        DIANA_RARE_MOB_WARP = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.diana_rare_mob_warp", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
        DIANA_SPHINX_SOLVER = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.diana_sphinx_solver", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
        SLAYER_BOSS_SELECT = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.slayer_boss_select", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
        PROTECT_ITEM = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.protect_item", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));

        initialized = true;
    }

    private Sky2MKeyMappings() {}
}
