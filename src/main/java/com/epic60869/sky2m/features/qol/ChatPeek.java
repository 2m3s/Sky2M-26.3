package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MKeyMappings;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Chat > Chat Rules & Player Format > Chat Peeking: while the key is held, the HUD draws chat as if it were open. */
public final class ChatPeek {
    private static KeyMapping key;

    private ChatPeek() {}

    /** Called from {@link Sky2MKeyMappings#init()}: keys must be registered before the game options load. */
    public static void registerKey() {
        key = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.peek_chat", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), Sky2MKeyMappings.CATEGORY));
    }

    /** True while chat should be drawn in full without a chat screen being open. */
    public static boolean peeking() {
        Sky2MConfig c = Sky2MConfig.current();
        if (key == null || c == null || !c.chat.chatRules.peeking) return false;
        return Minecraft.getInstance().gui.screen() == null && key.isDown();
    }
}
