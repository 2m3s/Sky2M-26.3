package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's SkyBlock XP in Chat (features/chat/SkyblockXPInChat.kt and data/ActionBarStatsData.kt, LGPL-2.1:
 * https://github.com/hannibal002/SkyHanni): the "+5 SkyBlock XP (Accessories) (35/100)" the action bar shows is also
 * sent to chat, once per new value.
 */
public final class SkyBlockXpChat {
    /** SkyHanni's actionbar.skyblock.xp pattern, on colourless text. */
    private static final Pattern XP = Pattern.compile(".*?\\+(\\d+) SkyBlock XP \\(([^()]+)\\) \\((\\d+/\\d+)\\).*");

    private static String lastValue = "";

    private SkyBlockXpChat() {}

    public static void init() {
        Sky2MChat.onActionBar(message -> onActionBar(message.text()));
    }

    private static void onActionBar(String text) {
        if (!Compat.isOnSkyblock()) return;
        Matcher m = XP.matcher(text);
        if (!m.matches()) return;
        String value = m.group(1) + "|" + m.group(2) + "|" + m.group(3);
        if (value.equals(lastValue)) return;
        lastValue = value;
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || c.chat == null || !c.chat.skyBlockXpInChat) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(SbcItems.parseLegacy(
            "§b+" + m.group(1) + " SkyBlock XP §7(" + m.group(2) + ")§b (" + m.group(3) + ")")));
    }
}
