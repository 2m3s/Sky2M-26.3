package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.custom.util.Compat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/** On-screen alerts, chat notices and the short "big drop" animation. */
public final class Sky2MAlerts {
    private static final Identifier ANIMATION_ID = Identifier.fromNamespaceAndPath("sky2m", "drop_animation");
    private static final int ANIMATION_TICKS = 60;

    private static Component animationText;
    private static int animationColor;
    private static long animationStart;

    private Sky2MAlerts() {}

    public static void init() {
        HudElementRegistry.addLast(ANIMATION_ID, (graphics, delta) -> renderAnimation(graphics));
    }

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

    /**
     * Plays the "big drop" animation: the text pops in the middle of the screen, bounces,
     * cycles colour and fades out over three seconds.
     */
    public static void dropAnimation(Component text, int color) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            animationText = text;
            animationColor = color;
            animationStart = System.currentTimeMillis();
            play(SoundEvents.PLAYER_LEVELUP, 1.2f);
            play(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f);
        });
    }

    private static void renderAnimation(GuiGraphicsExtractor graphics) {
        if (animationText == null) return;
        float t = (System.currentTimeMillis() - animationStart) / 50f;
        if (t > ANIMATION_TICKS) {
            animationText = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        // Pop in with an overshoot, hold, then fade out over the last second.
        float pop = t < 8 ? Mth.sin((t / 8f) * (float) Math.PI * 0.75f) * 1.25f : 1f;
        float scale = 2.5f * Math.max(0.1f, pop) + 0.08f * Mth.sin(t * 0.6f);
        float alpha = t > ANIMATION_TICKS - 20 ? (ANIMATION_TICKS - t) / 20f : 1f;
        float hue = (t / 40f) % 1f;
        int rainbow = Mth.hsvToRgb(hue, 0.6f, 1f);
        int color = ARGB.color(Mth.clamp(alpha, 0f, 1f), ARGB.srgbLerp(0.5f, animationColor, rainbow));

        graphics.pose().pushMatrix();
        graphics.pose().translate(width / 2f, height / 3f);
        graphics.pose().scale(scale, scale);
        int textWidth = mc.font.width(animationText);
        graphics.text(mc.font, animationText, -textWidth / 2, -mc.font.lineHeight / 2, color, true);
        graphics.pose().popMatrix();
    }
}
