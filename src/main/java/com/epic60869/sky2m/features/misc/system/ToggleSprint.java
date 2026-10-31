package com.epic60869.sky2m.features.misc.system;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.input.Sky2MKeyMappings;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Toggle sprint: always sprint while it is on (like Odin's AutoSprint), switched with the "Toggle Sprint" key.
 * A small HUD shows "[Sprinting (Toggled)]" while it is on.
 */
public final class ToggleSprint {
    private static final String DEFAULT_HUD_TEXT = "[Sprinting (Toggled)]";
    private static KeyMapping key;
    private static String cachedText;
    private static Component cachedLine;

    private ToggleSprint() {}

    private static Sky2MConfig config() {
        return Sky2MConfig.current();
    }

    /** Must run during client init, before the options are loaded. */
    public static void registerKey() {
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sky2m.toggle_sprint", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), Sky2MKeyMappings.CATEGORY));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (key == null) return;
            while (key.consumeClick()) {
                Sky2MConfig config = config();
                if (config == null) continue;
                config.misc.toggleSprint.enabled = !config.misc.toggleSprint.enabled;
                Sky2MConfig.saveCurrent(config);
                if (mc.player != null) {
                    mc.gui.hud.setOverlayMessage(Component.literal("Toggle Sprint: " + (config.misc.toggleSprint.enabled ? "ON" : "OFF"))
                        .withStyle(config.misc.toggleSprint.enabled ? ChatFormatting.GREEN : ChatFormatting.RED), false);
                }
            }
        });
        Sky2MHuds.register("toggle_sprint", "Toggle Sprint",
            () -> active() && config().misc.toggleSprint.hud,
            () -> List.of(hudLine()),
            List.of(Component.literal(DEFAULT_HUD_TEXT).withStyle(ChatFormatting.GRAY)),
            2, 250);
    }

    /** The HUD text from the config (& or § colours, {key}), gray by default; parsed again only when the text or key changes. */
    private static Component hudLine() {
        Sky2MConfig config = config();
        String text = config == null ? null : config.misc.toggleSprint.hudText;
        if (text == null || text.isBlank()) text = DEFAULT_HUD_TEXT;
        if (text.contains("{key}")) text = text.replace("{key}", key == null ? "NONE" : key.getTranslatedKeyMessage().getString());
        if (!text.equals(cachedText) || cachedLine == null) {
            cachedText = text;
            cachedLine = Component.empty().withStyle(ChatFormatting.GRAY).append(SbcItems.parseLegacy(text.replace('&', '§')));
        }
        return cachedLine;
    }

    public static boolean active() {
        Sky2MConfig config = config();
        return config != null && config.misc.toggleSprint.enabled;
    }
}
