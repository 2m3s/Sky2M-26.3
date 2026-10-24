package com.epic60869.sky2m.features.combat.extra;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combat extras: boss damage indicator, Vampire Slayer, Ashfang, Ghost counter, End dragon and Ender Node trackers and
 * area bosses. Ports of SkyHanni (https://github.com/hannibal002/SkyHanni, LGPL-2.1) and Skyblocker
 * (https://github.com/SkyblockerMod/Skyblocker, LGPL-3.0). This class has the helpers they share.
 */
public final class CombatExtras {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern TEXTURE_HASH = Pattern.compile("texture/([0-9a-fA-F]+)");

    private CombatExtras() {}

    public static void init(Path configDir) {
        BossDamageIndicator.init();
        VampireSlayerHelper.init();
        AshfangHelper.init();
        GhostTracker.init(configDir);
        DragonTracker.init(configDir);
        EnderNodeTracker.init(configDir);
        AreaBossTracker.init();
    }

    /** A nametag's text as the server sent it: an armor stand's custom name, or a text display's text. */
    static Component nametag(Entity entity) {
        if (entity instanceof ArmorStand stand && stand.hasCustomName()) return stand.getCustomName();
        if (entity instanceof Display.TextDisplay display) return display.getEntityData().get(Display.TextDisplay.DATA_TEXT_ID);
        return null;
    }

    /** The mob a nametag floats above: the nearest living entity below it that is not a player or an armor stand. */
    static LivingEntity mobBelow(Minecraft mc, Entity tag) {
        AABB search = new AABB(tag.getX() - 1.5, tag.getY() - 3.5, tag.getZ() - 1.5, tag.getX() + 1.5, tag.getY() + 0.5, tag.getZ() + 1.5);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity mob : mc.level.getEntitiesOfClass(LivingEntity.class, search,
                e -> !(e instanceof ArmorStand) && !(e instanceof Player) && e.isAlive())) {
            double distance = mob.distanceToSqr(tag);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    /** "1,234", "12.5k", "3.4M", "1.2B" as a number, or -1. */
    static double parseNumber(String text) {
        if (text == null) return -1;
        String t = text.replace(",", "").trim();
        if (t.isEmpty()) return -1;
        double mult = 1;
        char last = Character.toLowerCase(t.charAt(t.length() - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Double.parseDouble(t) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 1234 -> "1.23k", 5_600_000 -> "5.6M". */
    static String shortNumber(double value) {
        double abs = Math.abs(value);
        if (abs >= 1e9) return trim(value / 1e9) + "B";
        if (abs >= 1e6) return trim(value / 1e6) + "M";
        if (abs >= 1e3) return trim(value / 1e3) + "k";
        return String.valueOf(Math.round(value));
    }

    private static String trim(double value) {
        String s = String.format(Locale.US, "%.2f", value);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static String commas(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** 75 -> "1m 15s", 5.5 -> "5.5s". */
    static String duration(double seconds) {
        if (seconds < 60) return String.format(Locale.US, "%.1fs", Math.max(0, seconds));
        long s = (long) seconds;
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    /** The texture hash of a base64 skull property (the part of the texture URL after "texture/"), or "". */
    static String textureHash(String base64) {
        if (base64 == null || base64.isEmpty()) return "";
        try {
            Matcher m = TEXTURE_HASH.matcher(new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8));
            return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** The texture hash of the skull on an armor stand's head, or "". */
    static String headHash(ArmorStand stand) {
        return textureHash(Compat.getHeadTexture(stand.getItemBySlot(EquipmentSlot.HEAD)));
    }

    static <T> T load(Path file, Class<T> type, T fallback) {
        try {
            if (file == null || !Files.exists(file)) return fallback;
            T read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            return read == null ? fallback : read;
        } catch (Exception e) {
            return fallback;
        }
    }

    static void save(Path file, Object data) {
        try {
            if (file == null) return;
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
