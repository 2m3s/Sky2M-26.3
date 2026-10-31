package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.wolf.WolfSoundVariant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rag Axe timer, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod, ragaxe/), whose cast
 * detection is Odin's (BSD-3-Clause, Ragnarock.kt): a wolf death sound at exactly pitch 1.4920635 while you hold a
 * Ragnarock Axe is your cast. From the wiki's numbers it times the 3s channel, the 10s Strength buff (1.5x the axe's
 * Strength) and the cooldown (20s, or what you set, less Mage's reduction), with alerts, and "rag now" prompts at the
 * moments of a boss fight you want the buff up for: each fires its lead (3s, the channel, by default) before that
 * moment. The M7 prompt and its rising tune are NoammAddons'. Nothing here clicks or casts: it's a timer and a prompt.
 */
public final class RagAxe {
    private static final long CHANNEL_MS = 3_000, BUFF_MS = 10_000;
    private static final float CAST_PITCH = 1.4920635f;
    private static final Pattern CANCEL = Pattern.compile("Ragnarock was cancelled due to (?:being hit|taking damage)!");
    private static final Pattern STRENGTH = Pattern.compile("Strength: \\+(\\d+)");
    private static final String LINE_WITHER_KING = "[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you.";
    private static final String LINE_NECRON = "[BOSS] Necron: You went further than any human before, congratulations.";
    private static final String LINE_STORM = "[BOSS] Storm: Pathetic Maxor, just like expected.";
    private static final String LINE_LIVID = "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.";
    /** NoammAddons' rag tune: when each note plays (ms) and its pitch. */
    private static final long[] CUE_MS = {0, 120, 240, 400, 520, 640, 780};
    private static final float[] CUE_PITCH = {1.22f, 1.13f, 1.29f, 1.60f, 1.60f, 1.72f, 1.89f};
    private static final int PROMPT_MS = 2000;

    private enum Prompt { M7_DRAGONS, M7_DRAGON_SPAWN, NECRON, STORM, LIVID }

    private static long buffStart, buffEnd, cooldownEnd;
    private static int strength;
    private static boolean strengthSent, endAlerted, readyAlerted;
    private static final long[] FIRE_AT = new long[Prompt.values().length];
    private static final Set<String> DRAGONS_PROMPTED = new HashSet<>();
    private static long promptUntil, cueStart = -1;
    private static int cuePlayed;

    private RagAxe() {}

    private static FeatureConfigs.RagAxe config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.timers.ragAxe;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register("rag_axe", "Rag Axe", () -> config() != null && config().enabled, RagAxe::lines,
            List.of(line("Buff", "7.4s", ChatFormatting.GREEN), line("Cooldown", "12.0s", ChatFormatting.YELLOW)), 10, 160);
        Sky2MHuds.register("rag_axe_prompt", "Rag Axe Prompt", () -> config() != null && config().enabled, RagAxe::promptLines,
            List.of(Component.literal("rag").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)), 200, 120);
        Sky2MHuds.setSettingsSearch("rag_axe_prompt", "Rag Axe");
    }

    private static void reset() {
        buffStart = buffEnd = cooldownEnd = 0;
        strength = 0;
        strengthSent = endAlerted = readyAlerted = false;
        java.util.Arrays.fill(FIRE_AT, 0);
        DRAGONS_PROMPTED.clear();
        promptUntil = 0;
        cueStart = -1;
    }

    // ------------------------------------------------------------------------------------------------ cast

    /** From the sound packet handler. */
    public static void onSound(SoundEvent sound, float pitch) {
        FeatureConfigs.RagAxe c = config();
        LocalPlayer player = Minecraft.getInstance().player;
        if (c == null || !c.enabled || player == null || pitch != CAST_PITCH || !isWolfDeath(sound.location())) return;
        ItemStack held = player.getMainHandItem();
        if (!"RAGNAROCK_AXE".equals(Compat.neuName(held))) return;
        long now = System.currentTimeMillis();
        strength = (int) (loreStrength(held) * 1.5);
        buffStart = c.soundIsBuffStart ? now : now + CHANNEL_MS;
        buffEnd = buffStart + BUFF_MS;
        float cooldown = c.cooldownSeconds;
        if (Sky2MLocation.inDungeon() && SelfClass.get() == DungeonClass.MAGE) cooldown *= 1f - c.mageReduction / 100f;
        cooldownEnd = now + (long) (cooldown * 1000);
        strengthSent = endAlerted = readyAlerted = false;
        if (c.castAlert) alert(Component.literal("Casted Rag").withStyle(ChatFormatting.GREEN));
    }

    private static boolean isWolfDeath(Identifier id) {
        for (WolfSoundVariant variant : SoundEvents.WOLF_SOUNDS.values()) {
            if (variant.adultSounds().deathSound().value().location().equals(id)) return true;
        }
        return false;
    }

    /** The axe's own "Strength: +N" lore line. */
    private static int loreStrength(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return 0;
        for (Component line : lore.lines()) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (plain != null && plain.startsWith("Strength:")) {
                Matcher m = STRENGTH.matcher(plain);
                return m.find() ? Integer.parseInt(m.group(1)) : 0;
            }
        }
        return 0;
    }

    private static void alert(Component title) {
        Sky2MAlerts.title(title, null, false);
        Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1f);
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        FeatureConfigs.RagAxe c = config();
        if (c == null || !c.enabled) return;
        if (CANCEL.matcher(text).matches()) {
            // The channel and buff are gone (the cooldown keeps running).
            buffStart = buffEnd = 0;
            strength = 0;
            strengthSent = endAlerted = true;
            if (c.cancelAlert) alert(Component.literal("Ragnarock Cancelled!").withStyle(ChatFormatting.RED));
            return;
        }
        String floor = Sky2MLocation.dungeonFloor();
        boolean f7 = floor.equals("F7") || floor.equals("M7"), m7 = floor.equals("M7"), f5 = floor.equals("F5") || floor.equals("M5");
        if (text.equals(LINE_WITHER_KING) && m7) schedule(Prompt.M7_DRAGONS, 3_000);
        else if (text.equals(LINE_NECRON) && f7) schedule(Prompt.NECRON, 8_000);
        else if (text.equals(LINE_STORM) && f7) schedule(Prompt.STORM, 5_000);
        else if (text.equals(LINE_LIVID) && f5) schedule(Prompt.LIVID, 19_500);
    }

    private static FeatureConfigs.RagPrompt prompt(FeatureConfigs.RagAxe c, Prompt p) {
        return switch (p) {
            case M7_DRAGONS -> c.m7Dragons;
            case M7_DRAGON_SPAWN -> c.m7DragonSpawn;
            case NECRON -> c.necron;
            case STORM -> c.storm;
            case LIVID -> c.livid;
        };
    }

    /** Prompts {@code lead} before the moment {@code offset} after the line, so the buff lands on it. */
    private static void schedule(Prompt p, long offset) {
        FeatureConfigs.RagAxe c = config();
        FeatureConfigs.RagPrompt settings = prompt(c, p);
        if (!settings.enabled || skippedForClass(c, p)) return;
        FIRE_AT[p.ordinal()] = System.currentTimeMillis() + Math.max(0, offset - settings.leadMs);
    }

    private static boolean skippedForClass(FeatureConfigs.RagAxe c, Prompt p) {
        if (!c.skipTankHealer || (p != Prompt.M7_DRAGONS && p != Prompt.M7_DRAGON_SPAWN)) return false;
        DungeonClass self = SelfClass.get();
        return self == DungeonClass.TANK || self == DungeonClass.HEALER;
    }

    private static void fire(FeatureConfigs.RagAxe c, Prompt p) {
        promptUntil = System.currentTimeMillis() + PROMPT_MS;
        if (prompt(c, p).sound) {
            cueStart = System.currentTimeMillis();
            cuePlayed = 0;
        }
        if (c.promptTitle) Sky2MAlerts.title(Component.literal(c.promptText).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), null, false);
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick() {
        FeatureConfigs.RagAxe c = config();
        if (c == null || !c.enabled) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (!strengthSent && buffStart > 0 && now >= buffStart) {
            // Said once the channel has finished, so a cancelled cast never claims the Strength.
            strengthSent = true;
            if (c.strengthMessage) {
                Sky2MAlerts.chat(Component.literal("Ragnarock gave you ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(strength + " Strength").withStyle(ChatFormatting.RED)));
                if (c.announceStrength && mc.getConnection() != null) mc.getConnection().sendCommand("pc Gained strength from Ragnarock: " + strength);
            }
        }
        if (!endAlerted && buffEnd > 0 && now >= buffEnd) {
            endAlerted = true;
            if (c.endAlert) alert(Component.literal("Ragnarock Ended").withStyle(ChatFormatting.RED));
        }
        if (!readyAlerted && cooldownEnd > 0 && now >= cooldownEnd) {
            readyAlerted = true;
            if (c.readyAlert) alert(Component.literal("Ragnarock Ready").withStyle(ChatFormatting.GREEN));
        }
        for (Prompt p : Prompt.values()) {
            int i = p.ordinal();
            if (FIRE_AT[i] == 0 || now < FIRE_AT[i]) continue;
            FIRE_AT[i] = 0;
            if (prompt(c, p).enabled) fire(c, p);
        }
        // M7 dragons: from Wither Dragons' spawn countdown, once per spawn.
        FeatureConfigs.RagPrompt spawn = c.m7DragonSpawn;
        if (spawn.enabled && Sky2MLocation.dungeonFloor().equals("M7") && !skippedForClass(c, Prompt.M7_DRAGON_SPAWN)) {
            Map<String, Integer> spawning = WitherDragons.spawningTicks();
            DRAGONS_PROMPTED.retainAll(spawning.keySet());
            for (Map.Entry<String, Integer> e : spawning.entrySet()) {
                if (e.getValue() * 50L <= spawn.leadMs && DRAGONS_PROMPTED.add(e.getKey())) fire(c, Prompt.M7_DRAGON_SPAWN);
            }
        }
        if (cueStart >= 0) {
            long elapsed = now - cueStart;
            while (cuePlayed < CUE_MS.length && elapsed >= CUE_MS[cuePlayed]) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), CUE_PITCH[cuePlayed], 0.25f));
                cuePlayed++;
            }
            if (cuePlayed >= CUE_MS.length) cueStart = -1;
        }
    }

    // ------------------------------------------------------------------------------------------------ HUD

    private static List<Component> lines() {
        FeatureConfigs.RagAxe c = config();
        List<Component> out = new ArrayList<>();
        if (c == null || !c.enabled) return out;
        long now = System.currentTimeMillis();
        if (c.channelTimer && buffStart > now) out.add(line("Channel", seconds(buffStart - now), ChatFormatting.AQUA));
        if (c.buffTimer && buffEnd > now && now >= buffStart) out.add(line("Buff", seconds(buffEnd - now), ChatFormatting.GREEN));
        if (c.cooldownTimer && cooldownEnd > now) out.add(line("Cooldown", seconds(cooldownEnd - now), ChatFormatting.YELLOW));
        return out;
    }

    private static List<Component> promptLines() {
        FeatureConfigs.RagAxe c = config();
        if (c == null || !c.enabled || System.currentTimeMillis() > promptUntil) return List.of();
        return List.of(Component.literal(c.promptText).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    private static Component line(String label, String value, ChatFormatting colour) {
        return Component.literal("Rag " + label + ": ").withStyle(ChatFormatting.GOLD).append(Component.literal(value).withStyle(colour));
    }
}
