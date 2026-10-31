package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boss spawn alert: a title and a loud sound when your slayer boss spawns, so it isn't missed on AFK-ish runs. The
 * sidebar switching to "Slay the boss!" is the first signal and only ever describes your own quest, and it works with
 * the boss off-screen or out of render range. Your boss's nametag appearing ({@link SlayerTimes}) is the fallback for
 * when the sidebar is hidden or late. Fires once per boss.
 */
public final class SlayerBossSpawnAlert {
    /** "Tarantula Broodfather V" on the sidebar, after padding symbols are removed. */
    private static final Pattern TIER = Pattern.compile("\\b([IVX]{1,4})$");
    private static final long REPEAT_MS = 2_000;
    private static final long REPEAT_FOR_MS = 10_000;
    /** The "Slay the boss!" line can blink while a scoreboard mod redraws: only re-arm after it's been gone this long. */
    private static final long REARM_MS = 3_000;
    /** Roughly the half-width of the view: a boss further round than this is "off-screen". */
    private static final double ON_SCREEN_DEGREES = 50;

    private static boolean alerted;
    private static long alertedAt;
    private static long lastSoundAt;
    private static long slayLineGoneSince;
    /** Whether the subtitle already says where the boss is (it's added once the nametag is seen, if it's off-screen). */
    private static boolean directionShown;
    private static Component subtitleName = Component.empty();
    private static int ticks;

    private SlayerBossSpawnAlert() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 2 == 0) tick(mc);
        });
        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            // A new quest (or a failed one) is a new boss to alert for, even if the sidebar never showed it gone.
            if (text.equals("SLAYER QUEST STARTED!") || text.equals("SLAYER QUEST FAILED!")) reset();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
    }

    private static FeatureConfigs.BossSpawnAlert config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.bossSpawnAlert;
    }

    /** Whether the boss scan in {@link SlayerFeatures} should run for the nametag fallback. */
    static boolean enabled() {
        FeatureConfigs.BossSpawnAlert config = config();
        return config != null && config.enabled;
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || !Sky2MLocation.onSkyblock()) return;
        long now = System.currentTimeMillis();
        if (slayLineShown()) {
            slayLineGoneSince = 0;
            if (!alerted && enabled()) alert(questBossName());
        } else if (alerted) {
            if (slayLineGoneSince == 0) slayLineGoneSince = now;
            // Gone for a while with no boss being timed: the fight is over, so the next boss alerts again.
            if (now - slayLineGoneSince > REARM_MS && SlayerTimes.bossSpawnedAt() == 0) reset();
        }
        if (alerted) repeat(mc, now);
    }

    /**
     * Your boss's nametag appeared ({@link SlayerTimes}), {@code key} its boss key ("Tarantula Broodfather V") or
     * null. Alerts if the sidebar didn't; otherwise, if the boss is off-screen, says where it is once.
     */
    static void onNametagSpawn(String key) {
        if (!enabled()) return;
        if (!alerted) {
            alert(key != null ? Component.literal(key).withStyle(ChatFormatting.RED) : questBossName());
            return;
        }
        if (directionShown || System.currentTimeMillis() - alertedAt > REPEAT_FOR_MS) return;
        Component direction = direction(Minecraft.getInstance());
        if (direction == null) return;
        directionShown = true;
        Sky2MAlerts.title(bossTitle(), Component.empty().append(subtitleName).append(direction), false);
    }

    private static void alert(Component name) {
        alerted = true;
        alertedAt = System.currentTimeMillis();
        lastSoundAt = alertedAt;
        subtitleName = name;
        Component direction = direction(Minecraft.getInstance());
        directionShown = direction != null;
        Sky2MAlerts.title(bossTitle(), direction == null ? name : Component.empty().append(name).append(direction), false);
        playSound();
    }

    /** "Repeat Until Seen": the sound again every 2 s until the boss is on-screen or targeted, for up to 10 s. */
    private static void repeat(Minecraft mc, long now) {
        FeatureConfigs.BossSpawnAlert config = config();
        if (config == null || !config.enabled || !config.repeatUntilSeen) return;
        if (now - alertedAt > REPEAT_FOR_MS || now - lastSoundAt < REPEAT_MS) return;
        LivingEntity boss = SlayerFeatures.boss();
        if (boss != null && (mc.crosshairPickEntity == boss || offAngle(mc, boss) <= ON_SCREEN_DEGREES)) {
            alertedAt = 0; // seen: stop repeating
            return;
        }
        lastSoundAt = now;
        playSound();
    }

    private static void playSound() {
        FeatureConfigs.BossSpawnAlert config = config();
        if (config == null) return;
        float volume = config.volume;
        Minecraft.getInstance().execute(() -> {
            switch (config.sound) {
                case ANVIL -> Sky2MAlerts.play(SoundEvents.ANVIL_LAND, 1f, volume);
                case BELL -> Sky2MAlerts.play(SoundEvents.BELL_BLOCK, 1f, volume);
                case PLING -> Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, volume);
                case LEVEL_UP -> Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1f, volume);
                case WITHER -> Sky2MAlerts.play(SoundEvents.WITHER_SPAWN, 1f, volume);
                case DRAGON -> Sky2MAlerts.play(SoundEvents.ENDER_DRAGON_GROWL, 1f, volume);
            }
        });
    }

    private static Component bossTitle() {
        return Component.literal("BOSS SPAWNED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    /** " → behind you, 23 m" when your boss is known and off-screen, else null. */
    private static Component direction(Minecraft mc) {
        LivingEntity boss = SlayerFeatures.boss();
        if (boss == null || mc.player == null) return null;
        double angle = signedAngle(mc, boss);
        if (Math.abs(angle) <= ON_SCREEN_DEGREES) return null;
        String where;
        if (Math.abs(angle) > 135) where = "↓ behind you";
        else if (angle > 0) where = "→ to your right";
        else where = "← to your left";
        int metres = (int) Math.round(mc.player.distanceTo(boss));
        return Component.literal(" " + where + ", " + metres + " m").withStyle(ChatFormatting.YELLOW);
    }

    private static double offAngle(Minecraft mc, LivingEntity boss) {
        return Math.abs(signedAngle(mc, boss));
    }

    /** Degrees from where you look to the boss, positive to your right (yaw grows clockwise seen from above). */
    private static double signedAngle(Minecraft mc, LivingEntity boss) {
        Vec3 to = boss.position().subtract(mc.player.position());
        double targetYaw = Math.toDegrees(Math.atan2(-to.x, to.z));
        return Mth.wrapDegrees(targetYaw - mc.player.getYRot());
    }

    private static boolean slayLineShown() {
        return contains(Sky2MLocation.scoreboard()) || contains(Sky2MLocation.teamLines());
    }

    private static boolean contains(List<String> lines) {
        for (String line : lines) if (line.contains("Slay the boss")) return true;
        return false;
    }

    /** "Tarantula Broodfather V" from the sidebar's quest line, or the slayer alone when its tier isn't there. */
    private static MutableComponent questBossName() {
        String slayer = SlayerArea.questSlayer();
        if (slayer == null) return Component.literal("Your slayer boss").withStyle(ChatFormatting.RED);
        String compact = slayer.replace(" ", "");
        String name = slayer;
        List<String> lines = Sky2MLocation.scoreboard().isEmpty() ? Sky2MLocation.teamLines() : Sky2MLocation.scoreboard();
        for (String raw : lines) {
            String clean = raw.replaceAll("[^A-Za-z ]", "").replaceAll("\\s+", " ").trim();
            if (!clean.replace(" ", "").contains(compact)) continue;
            Matcher m = TIER.matcher(clean);
            if (m.find()) name = slayer + " " + m.group(1);
            break;
        }
        return Component.literal(name).withStyle(ChatFormatting.RED);
    }

    private static void reset() {
        alerted = false;
        alertedAt = 0;
        lastSoundAt = 0;
        slayLineGoneSince = 0;
        directionShown = false;
    }
}
