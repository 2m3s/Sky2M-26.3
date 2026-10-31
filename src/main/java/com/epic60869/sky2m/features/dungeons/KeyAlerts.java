package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Key & Door Alerts: who picked up each Wither and Blood key, who opened each door and how long the key
 * was held. Hypixel names the player in "Name has obtained Wither Key!" and "Name opened a WITHER door!", but not in
 * "The BLOOD DOOR has been opened!", so the Blood Door is credited to whoever had the Blood Key. The messages are
 * the same ones Door Highlight reads (Odin's).
 */
public final class KeyAlerts {
    private static final Pattern KEY_OBTAINED = Pattern.compile("^(?:\\[[^]]*?] ?)?(\\w{1,16}) has obtained (Wither|Blood) Key!?$");
    private static final Pattern KEY_PICKED_UP = Pattern.compile("^A (Wither|Blood) Key was picked up!$");
    private static final Pattern WITHER_DOOR = Pattern.compile("^(?:\\[[^]]*?] ?)?(\\w{1,16}) opened a WITHER door!$");

    private static String witherHolder, bloodHolder;
    private static long witherAt, bloodAt;

    private KeyAlerts() {}

    private static FeatureConfigs.KeyAlerts config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.keyAlerts;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register("key_holders", "Key Holders", () -> config() != null && config().holderHud && Sky2MLocation.inDungeon(),
            KeyAlerts::lines,
            List.of(Component.literal("Wither Key: ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal("Steve").withStyle(ChatFormatting.WHITE)),
                Component.literal("Blood Key: ").withStyle(ChatFormatting.RED).append(Component.literal("Alex").withStyle(ChatFormatting.WHITE))),
            10, 290);
        Sky2MHuds.setting("key_holders", () -> config() != null && config().holderHud);
    }

    private static void reset() {
        witherHolder = bloodHolder = null;
        witherAt = bloodAt = 0;
    }

    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        FeatureConfigs.KeyAlerts c = config();
        Matcher m = KEY_OBTAINED.matcher(text);
        String key = null, who = null;
        if (m.matches()) {
            who = m.group(1);
            key = m.group(2);
        } else if ((m = KEY_PICKED_UP.matcher(text)).matches()) {
            who = "Someone";
            key = m.group(1);
        }
        if (key != null) {
            boolean blood = key.equals("Blood");
            if (blood) {
                bloodHolder = who;
                bloodAt = System.currentTimeMillis();
            } else {
                witherHolder = who;
                witherAt = System.currentTimeMillis();
            }
            if (c != null && c.pickupAlert) {
                Sky2MAlerts.title(Component.literal(key + " Key").withStyle(blood ? ChatFormatting.RED : ChatFormatting.DARK_GRAY, ChatFormatting.BOLD),
                    Component.literal("picked up by " + who).withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        if ((m = WITHER_DOOR.matcher(text)).matches()) {
            if (c != null && c.doorMessage) doorMessage("Wither Door", ChatFormatting.DARK_GRAY, m.group(1), witherAt);
            witherHolder = null;
            witherAt = 0;
        } else if (text.equals("The BLOOD DOOR has been opened!")) {
            if (c != null && c.doorMessage) doorMessage("Blood Door", ChatFormatting.RED, bloodHolder == null ? "Someone" : bloodHolder, bloodAt);
            bloodHolder = null;
            bloodAt = 0;
        }
    }

    private static void doorMessage(String door, ChatFormatting colour, String who, long keyAt) {
        String held = keyAt > 0 ? String.format(Locale.US, " (%.1fs after the key)", (System.currentTimeMillis() - keyAt) / 1000.0) : "";
        Sky2MAlerts.chat(Component.literal(who).withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" opened the ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(door).withStyle(colour))
            .append(Component.literal(held + ".").withStyle(ChatFormatting.GRAY)));
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        if (witherHolder != null) out.add(Component.literal("Wither Key: ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal(witherHolder).withStyle(ChatFormatting.WHITE)));
        if (bloodHolder != null) out.add(Component.literal("Blood Key: ").withStyle(ChatFormatting.RED).append(Component.literal(bloodHolder).withStyle(ChatFormatting.WHITE)));
        return out;
    }
}
