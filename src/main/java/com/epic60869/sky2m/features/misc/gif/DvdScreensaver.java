package com.epic60869.sky2m.features.misc.gif;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The DVD screensaver: text or a GIF from the GIF Player's folder bouncing around the screen, turning off each edge.
 * Hitting a corner exactly (both walls on the same frame) can change its colour, play a sound and show a message.
 * Several can bounce at once.
 * <p>
 * Ported from Killer560's Mod (dvd/DvdFeature), MIT License, Copyright (c) 2026 Killer560. Their per-DVD list is one
 * set of settings with a count here, and the corner sound is a game sound instead of a file.
 */
public final class DvdScreensaver {
    private static final Identifier LAYER = Identifier.fromNamespaceAndPath("sky2m", "dvd_screensaver");
    private static final float BASE_SPEED = 90f; // GUI pixels a second
    private static final int PADDING = 8;
    private static final int[] PALETTE = {0xFFFFFFFF, 0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55,
        0xFF55FFFF, 0xFF5599FF, 0xFFAA55FF, 0xFFFF55FF, 0xFFFF8888};

    private static final class Box {
        float x, y, vx, vy;
        int colour = -1; // -1: the setting's colour
        long last;
    }

    private static final List<Box> BOXES = new ArrayList<>();
    private static GifTexture gif;
    private static String gifName = "";
    private static boolean loading;
    private static long missedAt;

    private DvdScreensaver() {}

    private static FeatureConfigs.Dvd config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.dvd;
    }

    public static void init() {
        HudElementRegistry.addLast(LAYER, (graphics, delta) -> render(graphics));
    }

    private static void render(GuiGraphicsExtractor graphics) {
        FeatureConfigs.Dvd c = config();
        if (c == null || !c.enabled || Minecraft.getInstance().player == null
            || !com.epic60869.sky2m.features.core.Sky2MLocation.skyblockOnlyAllows()) {
            BOXES.clear();
            return;
        }
        boolean useGif = c.content == FeatureConfigs.Dvd.Content.GIF;
        if (useGif) syncGif(c.gifFile);
        while (BOXES.size() < c.count) BOXES.add(newBox());
        while (BOXES.size() > c.count) BOXES.removeLast();

        int w, h;
        Font font = Minecraft.getInstance().font;
        String text = c.text.isBlank() ? "DVD" : c.text;
        if (useGif && gif != null) {
            w = Math.max(1, Math.round(gif.width() * c.scale));
            h = Math.max(1, Math.round(gif.height() * c.scale));
            gif.advance(1f);
        } else {
            // Sized to the text, so the letters themselves reach the edges.
            w = Math.max(1, Math.round((font.width(text) + PADDING) * c.scale));
            h = Math.max(1, Math.round((font.lineHeight + PADDING) * c.scale));
        }
        int screenW = graphics.guiWidth(), screenH = graphics.guiHeight();
        long now = System.currentTimeMillis();
        for (Box box : BOXES) {
            move(box, c, w, h, screenW, screenH, now);
            int x = Math.round(box.x), y = Math.round(box.y);
            if (useGif && gif != null) {
                gif.draw(graphics, x, y, w, h);
                continue;
            }
            if (c.background) graphics.fill(x, y, x + w, y + h, 0x99000000);
            int colour = box.colour != -1 ? box.colour : 0xFF000000 | ChromaColours.parse(c.colour).getEffectiveColourRGB();
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + w / 2f, y + h / 2f);
            graphics.pose().scale(c.scale, c.scale);
            graphics.centeredText(font, text, 0, -font.lineHeight / 2 + 1, colour);
            graphics.pose().popMatrix();
        }
    }

    private static Box newBox() {
        Box box = new Box();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        box.x = 40 + random.nextInt(200);
        box.y = 40 + random.nextInt(150);
        double angle = random.nextDouble(Math.PI * 2);
        box.vx = (float) Math.cos(angle);
        box.vy = (float) Math.sin(angle);
        return box;
    }

    private static void move(Box box, FeatureConfigs.Dvd c, int w, int h, int screenW, int screenH, long now) {
        // At most a quarter second at once, so a lag spike doesn't fling it across the screen.
        float dt = box.last == 0 ? 0 : Math.min(0.25f, (now - box.last) / 1000f);
        box.last = now;
        float speed = BASE_SPEED * Math.max(0.05f, c.speed);
        float length = (float) Math.sqrt(box.vx * box.vx + box.vy * box.vy);
        if (length > 0.0001f) {
            box.vx = box.vx / length * speed;
            box.vy = box.vy / length * speed;
        }
        float x = box.x + box.vx * dt, y = box.y + box.vy * dt;
        boolean hitX = false, hitY = false;
        if (x < 0) { x = 0; box.vx = -box.vx; hitX = true; }
        else if (x + w > screenW) { x = Math.max(0, screenW - w); box.vx = -box.vx; hitX = true; }
        if (y < 0) { y = 0; box.vy = -box.vy; hitY = true; }
        else if (y + h > screenH) { y = Math.max(0, screenH - h); box.vy = -box.vy; hitY = true; }
        box.x = x;
        box.y = y;
        if (hitX && hitY) {
            if (c.colourOnCorner || c.colourOnWall) box.colour = nextColour(box.colour);
            if (c.cornerSound) Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1.2f);
            if (!c.cornerMessage.isBlank()) {
                String name = c.content == FeatureConfigs.Dvd.Content.GIF && !gifName.isBlank() ? stripExtension(gifName) : c.text;
                Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal(c.cornerMessage.replace("{name}", name))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            }
        } else if ((hitX || hitY) && c.colourOnWall) {
            box.colour = nextColour(box.colour);
        }
    }

    private static int nextColour(int current) {
        int i = ThreadLocalRandom.current().nextInt(PALETTE.length);
        return PALETTE[i] == current ? PALETTE[(i + 1) % PALETTE.length] : PALETTE[i];
    }

    /** Loads the GIF named in the settings (from the GIF Player's folder) when it changes. */
    private static void syncGif(String name) {
        String wanted = name == null ? "" : name.trim();
        if (loading || (wanted.equalsIgnoreCase(gifName) && (gif != null || System.currentTimeMillis() - missedAt < 3000))) return;
        if (gif != null) {
            gif.close();
            gif = null;
        }
        gifName = wanted;
        Path file = GifPlayer.find(wanted);
        if (file == null) {
            // Not in the folder (yet): look again in a few seconds, not every frame.
            missedAt = System.currentTimeMillis();
            return;
        }
        loading = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return GifDecoder.decode(file);
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't load DVD GIF " + wanted + ": " + e.getMessage());
                return null;
            }
        }).thenAccept(decoded -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (decoded != null && wanted.equalsIgnoreCase(gifName)) gif = new GifTexture(wanted, decoded);
        }));
    }

    private static String stripExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
