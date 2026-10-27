package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** On-screen alerts and chat notices. Big drops use the totem animation ({@link com.epic60869.sky2m.features.combat.RareDropTotem}). */
public final class Sky2MAlerts {
    private Sky2MAlerts() {}

    /** Shows a title and subtitle in the middle of the screen and plays a ding. */
    public static void title(Component title, Component subtitle) {
        title(title, subtitle, true);
    }

    /** Shows a title and subtitle in the middle of the screen, with a ding or silently. */
    public static void title(Component title, Component subtitle, boolean ding) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            mc.gui.hud.setTimes(5, 40, 10);
            mc.gui.hud.setTitle(title);
            mc.gui.hud.setSubtitle(subtitle == null ? Component.empty() : subtitle);
            if (ding) ding();
        });
    }

    public static void chat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(message));
        });
    }

    public static void ding() {
        play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f);
    }

    public static void play(SoundEvent sound, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    public static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
