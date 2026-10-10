package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * Misc > Hide Status Effects, from Skysoft's Hide Status Effects (https://github.com/Akinsoft/Skysoft, LGPL-3.0): the
 * potion effect icons in the top-right of the screen. The list beside inventories is Sky2MEffectsInInventoryMixin.
 */
public final class HideStatusEffects {
    private HideStatusEffects() {}

    public static void init() {
        HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS, vanilla -> (graphics, delta) -> {
            Sky2MConfig c = Sky2MConfig.current();
            if (c == null || !c.misc.hideStatusEffects) vanilla.extractRenderState(graphics, delta);
        });
    }
}
