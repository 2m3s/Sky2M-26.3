package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.misc.social.Sky2MAchievements;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dungeons > Floor 7 > M7 Dragon Arrows and Splits. The arrows point from your crosshair towards each dragon that's
 * spawning (where Wither Dragons expects it), turning as you look around, with its countdown. The splits are how long
 * each dragon was alive, from its entity appearing to it dying, in server ticks; the best for each colour is kept in
 * {@code sky2m/dragon-splits.json}.
 */
public final class DragonSplits {
    private static final Identifier ARROWS_ID = Identifier.fromNamespaceAndPath("sky2m", "dragon_arrows");
    private static final Gson GSON = new Gson();

    private record Split(String name, int colour, int ticks, boolean best) {}

    private static final List<Split> splits = new CopyOnWriteArrayList<>();
    private static Map<String, Integer> bests = new LinkedHashMap<>();
    private static Path file;

    private DragonSplits() {}

    private static FeatureConfigs.DragonSplits config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.f7.dragonSplits;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("dragon-splits.json");
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> splits.clear());
        Sky2MLocation.onAreaChange(area -> splits.clear());
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, ARROWS_ID, (graphics, delta) -> renderArrows(graphics));
        Sky2MHuds.register("dragon_splits", "Dragon Splits", () -> config() != null && config().hud && Sky2MLocation.inDungeon(),
            DragonSplits::lines,
            List.of(line("Red", 0xFF0000, 98, 90), line("Orange", 0xFFAA00, 74, 74), line("Purple", 0xAA00AA, 120, 101)),
            10, 320);
        Sky2MHuds.setting("dragon_splits", () -> config() != null && config().hud);
    }

    /** From Wither Dragons, on the server tick a dragon's entity is gone: {@code ticks} it was alive. */
    static void onDragonDeath(String name, int colour, int ticks) {
        if (ticks <= 0) return;
        Sky2MAchievements.add(Sky2MAchievements.Stat.DRAGONS, 1);
        Integer best = bests.get(name);
        boolean newBest = best == null || ticks < best;
        if (newBest) {
            bests.put(name, ticks);
            save();
        }
        splits.add(new Split(name, colour, ticks, newBest));
        FeatureConfigs.DragonSplits c = config();
        if (c != null && c.chat) {
            MutableComponent msg = Component.literal(name).withColor(colour)
                .append(Component.literal(" killed in " + seconds(ticks)).withStyle(ChatFormatting.GRAY));
            if (newBest && best != null) msg.append(Component.literal(" (new best, was " + seconds(best) + ")").withStyle(ChatFormatting.GREEN));
            else if (best != null) msg.append(Component.literal(" (best " + seconds(best) + ")").withStyle(ChatFormatting.DARK_GRAY));
            Sky2MAlerts.chat(msg);
        }
    }

    private static String seconds(int ticks) {
        return String.format(Locale.US, "%.2fs", ticks / 20f);
    }

    private static Component line(String name, int colour, int ticks, int best) {
        MutableComponent line = Component.literal(name + ": ").withColor(colour).append(Component.literal(seconds(ticks)).withStyle(ChatFormatting.WHITE));
        return line.append(Component.literal(" (" + seconds(best) + ")").withStyle(ticks <= best ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        for (Split s : splits) out.add(line(s.name(), s.colour(), s.ticks(), bests.getOrDefault(s.name(), s.ticks())));
        return out;
    }

    private static void renderArrows(GuiGraphicsExtractor graphics) {
        FeatureConfigs.DragonSplits c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.arrows || mc.player == null || !WitherDragons.inDragonPhase()) return;
        List<WitherDragons.Spawning> spawning = WitherDragons.spawningDragons();
        if (spawning.isEmpty()) return;
        float cx = graphics.guiWidth() / 2f, cy = graphics.guiHeight() / 2f;
        Vec3 eye = mc.player.getEyePosition();
        for (WitherDragons.Spawning d : spawning) {
            double dx = d.pos().x - eye.x, dz = d.pos().z - eye.z;
            // Minecraft yaw: 0 faces +Z, and it grows turning right; the arrow points up when the dragon is ahead.
            float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float relative = Mth.wrapDegrees(target - mc.player.getYRot());
            double rad = Math.toRadians(relative);
            float x = cx + (float) Math.sin(rad) * c.arrowRadius, y = cy - (float) Math.cos(rad) * c.arrowRadius;
            int argb = 0xFF000000 | d.colour();
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().rotate((float) rad);
            graphics.pose().scale(2f, 2f);
            graphics.text(mc.font, "▲", -mc.font.width("▲") / 2, -mc.font.lineHeight / 2, argb, true);
            graphics.pose().popMatrix();
            String time = String.format(Locale.US, "%.1f", d.ticks() / 20f);
            graphics.text(mc.font, time, (int) x - mc.font.width(time) / 2, (int) y + 9, argb, true);
        }
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, Integer> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Integer>>() {}.getType());
            if (read != null) bests = read;
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(bests), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
