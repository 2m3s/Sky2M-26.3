package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.misc.social.Sky2MAchievements;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Mining > Scatha Tracker: worm spawns in the Crystal Hollows. "You hear the sound of something approaching..." means
 * a worm spawned near you; its nametag ("[Lv5] Worm" or "[Lv10] Scatha") says which. Spawns have a 30 second cooldown
 * after the last one. Scatha pet drops ("PET DROP! Scatha") are counted by rarity from the name's colour. Counts are
 * kept in {@code sky2m/scatha.json}.
 */
public final class ScathaTracker {
    private static final String SPAWN = "You hear the sound of something approaching...";
    private static final long COOLDOWN_MS = 30_000, IDENTIFY_MS = 3_000;
    private static final Gson GSON = new Gson();

    private static final class Data {
        int worms, scathas, wormsSinceScatha, scathasSincePet, rarePets, epicPets, legendaryPets;
    }

    private static Data data = new Data();
    private static Path file;
    private static long lastSpawn, pendingSince;
    private static boolean cooldownAlerted = true;

    private ScathaTracker() {}

    private static FeatureConfigs.ScathaTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.scatha;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("scatha.json");
        load();
        Sky2MChat.onChat(message -> onChat(message));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        Sky2MHuds.register("scatha_tracker", "Scatha Tracker", () -> config() != null && config().hud && Sky2MLocation.inCrystalHollows(),
            ScathaTracker::lines,
            List.of(Component.literal("Worms: ").withStyle(ChatFormatting.GRAY).append(Component.literal("142").withStyle(ChatFormatting.WHITE)),
                Component.literal("Scathas: ").withStyle(ChatFormatting.RED).append(Component.literal("31 (17.9%)").withStyle(ChatFormatting.WHITE)),
                Component.literal("Since Scatha: ").withStyle(ChatFormatting.GRAY).append(Component.literal("4 worms").withStyle(ChatFormatting.WHITE)),
                Component.literal("Pets: ").withStyle(ChatFormatting.GOLD).append(Component.literal("1 / 0 / 0").withStyle(ChatFormatting.WHITE)),
                Component.literal("Spawn: ").withStyle(ChatFormatting.GRAY).append(Component.literal("Ready").withStyle(ChatFormatting.GREEN))),
            10, 120);
        Sky2MHuds.setting("scatha_tracker", () -> config() != null && config().hud);
    }

    private static void onChat(Sky2MChat.Message message) {
        if (!Sky2MLocation.inCrystalHollows()) return;
        String text = message.text().trim();
        if (text.equals(SPAWN)) {
            lastSpawn = System.currentTimeMillis();
            pendingSince = lastSpawn;
            cooldownAlerted = false;
        } else if (text.startsWith("PET DROP! Scatha")) {
            String rarity = rarityOf(message.component());
            switch (rarity) {
                case "legendary" -> data.legendaryPets++;
                case "epic" -> data.epicPets++;
                default -> data.rarePets++;
            }
            data.scathasSincePet = 0;
            Sky2MAchievements.add(Sky2MAchievements.Stat.SCATHA_PETS, 1);
            save();
        }
    }

    /** The colour Hypixel gives "Scatha" in the pet drop line: blue rare, purple epic, gold legendary. */
    private static String rarityOf(Component message) {
        List<String> found = new ArrayList<>();
        message.visit((Style style, String text) -> {
            if (text.contains("Scatha") && style.getColor() != null) {
                TextColor colour = style.getColor();
                if (colour.equals(TextColor.fromLegacyFormat(ChatFormatting.GOLD))) found.add("legendary");
                else if (colour.equals(TextColor.fromLegacyFormat(ChatFormatting.DARK_PURPLE))) found.add("epic");
                else found.add("rare");
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found.isEmpty() ? "rare" : found.getFirst();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        // Leaving the Hollows ends any "waiting for Scatha loot" session, so an unrelated drop can't be credited to it.
        if (mc.level == null || mc.player == null || !Sky2MLocation.inCrystalHollows()) {
            pendingSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingSince > 0) {
            Boolean scatha = identify(mc);
            if (scatha != null || now - pendingSince > IDENTIFY_MS) {
                pendingSince = 0;
                onSpawn(Boolean.TRUE.equals(scatha));
            }
        }
        FeatureConfigs.ScathaTracker c = config();
        if (!cooldownAlerted && lastSpawn > 0 && now - lastSpawn >= COOLDOWN_MS) {
            cooldownAlerted = true;
            if (c != null && c.cooldownAlert) Sky2MAlerts.ding();
        }
    }

    /** True for a Scatha's nametag near you, false for a worm's, null for neither yet. */
    private static Boolean identify(Minecraft mc) {
        for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(20))) {
            // Only a nametag that just appeared: an older worm still around isn't this spawn.
            if (e.tickCount > 100) continue;
            Component name = e instanceof ArmorStand stand && stand.hasCustomName() ? stand.getCustomName()
                : e instanceof Display.TextDisplay display ? display.getEntityData().get(Display.TextDisplay.DATA_TEXT_ID) : null;
            if (name == null) continue;
            String text = Sky2MLocation.strip(name.getString());
            if (!text.contains("[Lv")) continue;
            if (text.contains("Scatha")) return true;
            if (text.contains("Worm")) return false;
        }
        return null;
    }

    private static void onSpawn(boolean scatha) {
        if (scatha) {
            data.scathas++;
            data.scathasSincePet++;
            data.wormsSinceScatha = 0;
            Sky2MAchievements.add(Sky2MAchievements.Stat.SCATHAS, 1);
        } else {
            data.worms++;
            data.wormsSinceScatha++;
            Sky2MAchievements.add(Sky2MAchievements.Stat.WORMS, 1);
        }
        save();
        FeatureConfigs.ScathaTracker c = config();
        if (c != null && c.spawnAlert) {
            Sky2MAlerts.title(scatha ? Component.literal("SCATHA!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                : Component.literal("Worm").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD), null);
        }
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        int spawns = data.worms + data.scathas;
        String rate = spawns == 0 ? "" : String.format(Locale.US, " (%.1f%%)", data.scathas * 100.0 / spawns);
        out.add(Component.literal("Worms: ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.valueOf(data.worms)).withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("Scathas: ").withStyle(ChatFormatting.RED).append(Component.literal(data.scathas + rate).withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("Since Scatha: ").withStyle(ChatFormatting.GRAY).append(Component.literal(data.wormsSinceScatha + " worms").withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("Pets: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(String.valueOf(data.rarePets)).withStyle(ChatFormatting.BLUE))
            .append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(String.valueOf(data.epicPets)).withStyle(ChatFormatting.DARK_PURPLE))
            .append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(String.valueOf(data.legendaryPets)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" (" + data.scathasSincePet + " since)").withStyle(ChatFormatting.DARK_GRAY)));
        long left = lastSpawn == 0 ? 0 : COOLDOWN_MS - (System.currentTimeMillis() - lastSpawn);
        out.add(Component.literal("Spawn: ").withStyle(ChatFormatting.GRAY).append(left <= 0
            ? Component.literal("Ready").withStyle(ChatFormatting.GREEN)
            : Component.literal(String.format(Locale.US, "%.0fs", Math.ceil(left / 1000.0))).withStyle(ChatFormatting.YELLOW)));
        return out;
    }

    /** The Reset Counts button. */
    public static void resetCounts() {
        data = new Data();
        save();
        Sky2MAlerts.chat(Component.literal("Scatha Tracker counts reset.").withStyle(ChatFormatting.YELLOW));
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (read != null) data = read;
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
