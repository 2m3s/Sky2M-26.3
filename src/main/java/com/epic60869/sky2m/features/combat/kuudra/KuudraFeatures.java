package com.epic60869.sky2m.features.combat.kuudra;

import java.nio.file.Path;

/**
 * Combat > Kuudra: supply, pile and pre-spot waypoints and alerts, phase splits with personal bests, chest profit,
 * Fresh Tools and build helpers, Kuudra's hitbox and health, and the Perk Menu cleaner. Everything is display only.
 */
public final class KuudraFeatures {
    private KuudraFeatures() {}

    public static void init(Path configDir) {
        KuudraState.init();
        KuudraHelpers.init();
        KuudraSplits.init(configDir);
        KuudraMenus.init();
    }
}
