package com.epic60869.sky2m.features.garden;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the crop fever hue shift on and off. On 26.3 a player has a list of active post effects (SkyOcean's way);
 * the 26.2 version of this class uses GameRenderer's post effect instead. Each speed is its own post effect file,
 * {@code assets/sky2m/post_effect/crop_fever_hue_shift_<speed>.json}.
 */
final class CropFeverShader {
    private static Identifier current;

    private CropFeverShader() {}

    static void apply(String speed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        clear();
        current = Identifier.fromNamespaceAndPath("sky2m", "crop_fever_hue_shift_" + speed);
        List<Identifier> effects = new ArrayList<>(player.getActivePostEffects());
        effects.add(current);
        player.setActivePostEffects(effects);
    }

    static void clear() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (current != null && player != null) {
            List<Identifier> effects = new ArrayList<>(player.getActivePostEffects());
            effects.remove(current);
            player.setActivePostEffects(effects);
        }
        current = null;
    }
}
