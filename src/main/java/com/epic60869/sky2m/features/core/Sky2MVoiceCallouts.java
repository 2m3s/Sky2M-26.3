package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.mixin.gui.Sky2MGameNarratorAccessor;
import com.mojang.text2speech.Narrator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import java.util.Locale;

/**
 * Voice Callouts: speaks a title alert with the game's own text to speech. {@code GameNarrator.saySystemNow} does nothing
 * while the game's Narrator option is off, and that option is the player's, so this talks to the narrator underneath it
 * and leaves the option alone. Only {@link Sky2MAlerts} titles get here: chat is never read out.
 */
public final class Sky2MVoiceCallouts {
    public static final long MIN_GAP_MS = 3_000L;
    private static final int MAX_LENGTH = 120;

    private static long lastSpokenAt;
    /** The system has no text to speech, or speaking failed: said once, then nothing more this session. */
    private static boolean unavailable;

    private Sky2MVoiceCallouts() {}

    /** Called on the client thread for each title alert. */
    static void onTitle(Component title, Component subtitle) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.general.voiceCallouts || unavailable) return;
        String text = spokenText(title.getString(), subtitle == null ? "" : subtitle.getString());
        if (text.isEmpty() || !matchesWords(text, config.general.voiceCalloutWords)) return;
        long now = System.currentTimeMillis();
        if (!gapPassed(lastSpokenAt, now)) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            Narrator narrator = ((Sky2MGameNarratorAccessor) mc.getNarrator()).sky2m$narrator();
            if (!narrator.active()) {
                giveUp(null);
                return;
            }
            lastSpokenAt = now;
            // Interrupting: a new callout replaces one still being spoken, so they can't queue up.
            narrator.say(text, true, mc.options.getFinalSoundSourceVolume(SoundSource.VOICE));
        } catch (Exception | LinkageError e) {
            giveUp(e);
        }
    }

    private static void giveUp(Throwable cause) {
        unavailable = true;
        System.err.println("[Sky2M] Voice Callouts: no text to speech" + (cause == null ? "" : ": " + cause));
        Sky2MAlerts.chat(Component.literal("Voice Callouts: text to speech isn't available on this system.").withStyle(ChatFormatting.GRAY));
    }

    /** The words to say: formatting and symbols gone, one line, not too long. Public for the game test. */
    public static String spokenText(String title, String subtitle) {
        String a = clean(title), b = clean(subtitle);
        String text = a.isEmpty() ? b : b.isEmpty() ? a : a + ". " + b;
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH).trim();
    }

    private static String clean(String text) {
        String stripped = ChatFormatting.stripFormatting(text == null ? "" : text);
        if (stripped == null) return "";
        return stripped.replaceAll("[^\\p{L}\\p{N}\\p{Zs}.,!?:'%+-]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Whether the text has one of the comma-separated words, ignoring case. No words means every alert. Public for the game test. */
    public static boolean matchesWords(String text, String words) {
        if (words == null || words.isBlank()) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words.toLowerCase(Locale.ROOT).split(",")) {
            if (!word.isBlank() && lower.contains(word.trim())) return true;
        }
        return false;
    }

    /** Whether enough time has passed since the last callout. Public for the game test. */
    public static boolean gapPassed(long lastMs, long nowMs) {
        return nowMs - lastMs >= MIN_GAP_MS;
    }
}
