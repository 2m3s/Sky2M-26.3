package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import java.util.regex.Pattern;

/**
 * Mutes the nether portal sound Hypixel plays with "OVERFLOW! Your Euclid's Wheat Sickle Mk. III has just dropped a
 * Tool Exp Capsule!". The sound can come just before or just after the message, so one that's already playing is
 * stopped and one that comes soon after is never played.
 */
public final class OverflowDropSound {
    private static final Pattern OVERFLOW_DROP = Pattern.compile("^OVERFLOW! Your .+ has just dropped an? .+!$");
    /** How close together the message and the sound have to be. */
    private static final long WINDOW_MS = 2_000L;

    private static long lastOverflowMessage;
    private static long lastPortalSound;
    private static String lastPortalSoundName;

    private OverflowDropSound() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.farming.garden.muteOverflowDropSound;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            if (!enabled() || !OVERFLOW_DROP.matcher(message.text()).matches()) return;
            long now = System.currentTimeMillis();
            lastOverflowMessage = now;
            // The sound came first: stop it.
            if (lastPortalSoundName != null && now - lastPortalSound < WINDOW_MS) {
                Minecraft.getInstance().getSoundManager().stop(Identifier.withDefaultNamespace(lastPortalSoundName), (SoundSource) null);
            }
        });
    }

    /** From the sound packet mixin: true cancels the sound. */
    public static boolean onPlaySound(String soundName) {
        if (!soundName.startsWith("block.portal.") || !enabled()) return false;
        long now = System.currentTimeMillis();
        if (now - lastOverflowMessage < WINDOW_MS) return true;
        lastPortalSound = now;
        lastPortalSoundName = soundName;
        return false;
    }
}
