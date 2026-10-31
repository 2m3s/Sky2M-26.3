package com.epic60869.sky2m.features.misc.gif;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GIF Player: every GIF in config/sky2m/gifs plays on loop as its own HUD, moved and sized in /s2 gui. Files are
 * picked up and dropped while the game runs. Decoding happens off the game thread.
 * <p>
 * Ported from Killer560's Mod (gifplayer/GifPlayerFeature), MIT License, Copyright (c) 2026 Killer560. Their audio
 * playback and built-in GIF are left out.
 */
public final class GifPlayer {
    private static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("gifs");
    private static final int RESCAN_TICKS = 40;

    private record Playing(GifTexture texture, int width, int height) {}

    private static final Map<String, Playing> PLAYING = new LinkedHashMap<>();
    /** What the last scan saw, so nothing is decoded again unless the folder or the settings changed. */
    private static String lastState = "";
    private static int ticks;

    private GifPlayer() {}

    public static Path folder() {
        return FOLDER;
    }

    private static FeatureConfigs.GifPlayer config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.gifPlayer;
    }

    public static void init() {
        try {
            Files.createDirectories(FOLDER);
        } catch (IOException ignored) {}
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % RESCAN_TICKS == 1) rescan(false);
        });
    }

    /** Every GIF file name in the folder, sorted. */
    public static List<String> files() {
        try (Stream<Path> stream = Files.list(FOLDER)) {
            return stream.filter(Files::isRegularFile).filter(GifDecoder::isGif)
                .map(p -> p.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Re-reads the folder; with {@code force}, decodes every GIF again (the Reload button). */
    public static void rescan(boolean force) {
        FeatureConfigs.GifPlayer c = config();
        if (c == null) return;
        Set<String> disabled = Arrays.stream(c.disabledGifs.split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        List<String> wanted = !c.enabled ? List.of()
            : files().stream().filter(f -> !disabled.contains(f.toLowerCase(Locale.ROOT))).toList();
        String state = wanted + "|" + c.maxSize;
        if (!force && state.equals(lastState)) return;
        lastState = state;

        for (String name : List.copyOf(PLAYING.keySet())) {
            if (force || !wanted.contains(name)) stop(name);
        }
        int index = 0;
        for (String name : wanted) {
            int slot = index++;
            if (PLAYING.containsKey(name)) continue;
            int maxSize = c.maxSize;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return GifDecoder.decode(FOLDER.resolve(name));
                } catch (Exception e) {
                    System.err.println("[Sky2M] Couldn't load GIF " + name + ": " + e.getMessage());
                    return null;
                }
            }).thenAccept(gif -> Minecraft.getInstance().execute(() -> {
                if (gif == null || PLAYING.containsKey(name) || !lastState.equals(state)) return;
                double scale = Math.min(1.0, maxSize / (double) Math.max(gif.width(), gif.height()));
                Playing playing = new Playing(new GifTexture(name, gif),
                    Math.max(1, (int) Math.round(gif.width() * scale)), Math.max(1, (int) Math.round(gif.height() * scale)));
                PLAYING.put(name, playing);
                register(name, playing, slot);
            }));
        }
    }

    private static String hudId(String name) {
        return "gif_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static void register(String name, Playing playing, int slot) {
        Sky2MHuds.registerCustom(hudId(name), "GIF: " + name, () -> {
            FeatureConfigs.GifPlayer c = config();
            return c != null && c.enabled;
        }, new Sky2MHuds.CustomHud() {
            @Override public int width() { return playing.width(); }
            @Override public int height() { return playing.height(); }
            @Override public boolean visible() { return true; }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean preview) {
                FeatureConfigs.GifPlayer c = config();
                playing.texture().advance(c == null ? 1f : c.speed);
                playing.texture().draw(graphics, 0, 0, playing.width(), playing.height());
            }
        }, 20 + slot * 20, 20 + slot * 20);
    }

    private static void stop(String name) {
        Playing playing = PLAYING.remove(name);
        if (playing == null) return;
        playing.texture().close();
        Sky2MHuds.unregister(hudId(name));
    }

    public static void openFolder() {
        try {
            Files.createDirectories(FOLDER);
        } catch (IOException ignored) {}
        com.mojang.blaze3d.Blaze3D.openPath(FOLDER);
    }

    /** The GIF in the folder whose name matches (case-insensitively), or null. */
    public static Path find(String name) {
        if (name == null || name.isBlank()) return null;
        return files().stream().filter(f -> f.equalsIgnoreCase(name.trim()))
            .map(FOLDER::resolve).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
