package com.epic60869.sky2m.features.misc.visual;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import com.epic60869.sky2m.features.misc.social.Sky2MAchievements;

/**
 * Misc > Custom Sounds: your own sounds for leaping, teleporting, dying and kills.
 * <ul>
 *     <li>Teleports: the enderman teleport sound that plays where you land (Aspect of the End and Void, Spirit Leap...)
 *     and the etherwarp's dragon hurt sound (pitch 0.54) are swapped for yours. A teleport sound is held back for a
 *     moment to see whether "You have teleported to Name!" comes with it, which makes it a leap.</li>
 *     <li>Death: Hypixel's " ☠ You ..." line. Kill: a mob you hit in melee in the last 5 seconds dies.</li>
 * </ul>
 */
public final class CustomSounds {
    private static final float ETHERWARP_PITCH = 0.53968257f;
    private static final long HOLD_MS = 150, LEAP_WINDOW_MS = 600, KILL_WINDOW_MS = 5000;

    private record Held(SoundEvent sound, float volume, float pitch, long at) {}

    private static Held held;
    private static long lastLeap, lastTeleportPacket;
    private static final Map<Integer, Long> attacked = new HashMap<>();

    private CustomSounds() {}

    private static FeatureConfigs.CustomSounds config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.customSounds;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (held != null && System.currentTimeMillis() - held.at() > HOLD_MS) resolve();
            if (attacked.size() > 64) attacked.values().removeIf(t -> System.currentTimeMillis() - t > KILL_WINDOW_MS);
        });
        Sky2MChat.onChat(message -> onChat(message.text()));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) attacked.put(entity.getId(), System.currentTimeMillis());
            return InteractionResult.PASS;
        });
    }

    /** Plays a sound setting (also the settings' Test button). */
    public static void play(FeatureConfigs.SoundSetting setting) {
        Identifier id = Identifier.tryParse(setting.sound == null ? "" : setting.sound.trim());
        if (id == null) return;
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), setting.pitch, setting.volume)));
    }

    /** From the client packet listener, when the server sets your position (a teleport). */
    public static void onTeleportPacket() {
        lastTeleportPacket = System.currentTimeMillis();
    }

    /** From the sound packet handler: true cancels the sound (it's swapped for yours). */
    public static boolean onSound(SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        FeatureConfigs.CustomSounds c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || mc.player == null || (!c.leap.enabled && !c.teleport.enabled)) return false;
        boolean yours = mc.player.distanceToSqr(x, y, z) < 9 || System.currentTimeMillis() - lastTeleportPacket < 300;
        if (!yours) return false;
        Identifier id = sound.location();
        if (c.teleport.enabled && id.equals(SoundEvents.ENDER_DRAGON_HURT.location()) && Math.abs(pitch - ETHERWARP_PITCH) < 0.01f) {
            play(c.teleport);
            return true;
        }
        if (!id.equals(SoundEvents.ENDERMAN_TELEPORT.location())) return false;
        held = new Held(sound, volume, pitch, System.currentTimeMillis());
        return true;
    }

    /** Plays the held teleport sound as a leap, a teleport, or itself when that sound isn't turned on. */
    private static void resolve() {
        Held h = held;
        held = null;
        FeatureConfigs.CustomSounds c = config();
        if (h == null || c == null) return;
        boolean leap = System.currentTimeMillis() - lastLeap < LEAP_WINDOW_MS;
        FeatureConfigs.SoundSetting setting = leap ? c.leap : c.teleport;
        if (setting.enabled) play(setting);
        else Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(h.sound(), h.pitch(), h.volume()));
    }

    private static void onChat(String text) {
        FeatureConfigs.CustomSounds c = config();
        if (c == null) return;
        if (text.startsWith("You have teleported to ")) {
            lastLeap = System.currentTimeMillis();
            if (held != null) resolve();
        } else if (text.matches("^ ?☠ You .*")) {
            Sky2MAchievements.add(Sky2MAchievements.Stat.DEATHS, 1);
            if (c.death.enabled) play(c.death);
        }
    }

    /** From the entity event handler when a mob dies. */
    public static void onEntityDeath(Entity entity) {
        if (entity == null) return;
        Long at = attacked.remove(entity.getId());
        if (at == null || System.currentTimeMillis() - at > KILL_WINDOW_MS) return;
        Sky2MAchievements.add(Sky2MAchievements.Stat.KILLS, 1);
        FeatureConfigs.CustomSounds c = config();
        if (c != null && c.kill.enabled) play(c.kill);
    }
}
