package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /s2 screenshots: your screenshots folder as a gallery, newest first, with small previews. Pick one to upload it,
 * copy or re-send its link (uploads are remembered in config/sky2m/screenshot-links.json), or open the folder.
 * Screenshots uploaded to the Sky2M host can be put on shadowisabot.com/screenshots with your name and head (Upload to
 * Website).
 */
public final class ScreenshotGallery extends Screen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int THUMB_W = 160, THUMB_H = 90, GAP = 8, MAX_FILES = 200;
    private static Map<String, String> links;

    /** Previews being decoded off the render thread, then uploaded as textures on it. */
    private final Map<String, NativeImage> decoded = new ConcurrentHashMap<>();
    private final Map<String, Identifier> textures = new HashMap<>();
    private final Map<String, Boolean> requested = new HashMap<>();
    private List<Path> files = List.of();
    private String selected;
    private int scroll;

    public ScreenshotGallery() {
        super(Component.literal("Screenshots"));
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("screenshots")
                    .executes(c -> Compat.queueOpenScreen(new ScreenshotGallery()))));
            }
        });
    }

    // ---------------------------------------------------------------- uploaded links

    private static Path linksFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("screenshot-links.json");
    }

    private static synchronized Map<String, String> links() {
        if (links == null) {
            links = new LinkedHashMap<>();
            try {
                if (Files.exists(linksFile())) {
                    Map<String, String> loaded = GSON.fromJson(Files.readString(linksFile(), StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
                    if (loaded != null) links.putAll(loaded);
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read screenshot-links.json: " + e.getMessage());
            }
        }
        return links;
    }

    /** ScreenshotShare uploaded {@code file}: remember its link. */
    static synchronized void uploaded(String file, String link) {
        links().put(file, link);
        try {
            Files.createDirectories(linksFile().getParent());
            Files.writeString(linksFile(), GSON.toJson(links()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save screenshot-links.json: " + e.getMessage());
        }
    }

    private static synchronized String link(String file) {
        return links().get(file);
    }

    // ---------------------------------------------------------------- layout

    private Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
    }

    private int columns() {
        return Math.max(1, (width - 40 + GAP) / (THUMB_W + GAP));
    }

    private int gridLeft() {
        return (width - (columns() * (THUMB_W + GAP) - GAP)) / 2;
    }

    private int gridTop() {
        return 44;
    }

    private int gridBottom() {
        return height - 34;
    }

    @Override
    protected void init() {
        try (var stream = Files.list(folder())) {
            files = stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".png") || n.endsWith(".jpg");
                })
                .sorted((a, b) -> Long.compare(modified(b), modified(a)))
                .limit(MAX_FILES).toList();
        } catch (Exception e) {
            files = List.of();
        }
        rebuild();
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private void rebuild() {
        clearWidgets();
        int y = height - 26, x = width / 2 - 305;
        String link = selected == null ? null : link(selected);
        addRenderableWidget(Button.builder(Component.literal(link == null ? "Upload" : "Upload Again"), b -> {
            if (selected != null) minecraft.player.connection.sendCommand("s2 screenshot upload " + selected);
            onClose();
        }).bounds(x, y, 96, 20).build()).active = selected != null;
        addRenderableWidget(Button.builder(Component.literal("Copy Link"), b -> {
            minecraft.keyboardHandler.setClipboard(link);
            b.setMessage(Component.literal("Copied!"));
        }).bounds(x + 100, y, 80, 20).build()).active = link != null;
        addRenderableWidget(Button.builder(Component.literal("Send in /s2c"), b -> {
            minecraft.player.connection.sendCommand("s2c " + link);
            onClose();
        }).bounds(x + 184, y, 90, 20).build()).active = link != null;
        // Only Sky2M-hosted uploads can be listed on the website's Screenshots page.
        addRenderableWidget(Button.builder(Component.literal("Upload to Website"), b -> {
            minecraft.player.connection.sendCommand("s2 screenshot website " + link);
            onClose();
        }).bounds(x + 278, y, 106, 20).build()).active = ScreenshotShare.onSky2MHost(link);
        addRenderableWidget(Button.builder(Component.literal("Open Folder"), b -> com.mojang.blaze3d.Blaze3D.openPath(folder()))
            .bounds(x + 388, y, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Website"), b -> com.mojang.blaze3d.Blaze3D
                .openUri(java.net.URI.create("https://shadowisabot.com/screenshots")))
            .bounds(x + 478, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 552, y, 58, 20).build());
    }

    // ---------------------------------------------------------------- previews

    /** The preview of {@code file}, or null while it's loading (it's decoded and shrunk off the render thread). */
    private Identifier preview(Path file) {
        String name = file.getFileName().toString();
        Identifier id = textures.get(name);
        if (id != null) return id;
        NativeImage image = decoded.remove(name);
        if (image != null) {
            id = Identifier.fromNamespaceAndPath("sky2m", "gallery/" + Integer.toHexString(name.hashCode()) + "_" + textures.size());
            Identifier key = id;
            minecraft.getTextureManager().register(id, new DynamicTexture(key::toString, image));
            textures.put(name, id);
            return id;
        }
        if (requested.putIfAbsent(name, true) == null) {
            CompletableFuture.runAsync(() -> {
                try (InputStream in = Files.newInputStream(file); NativeImage full = NativeImage.read(in)) {
                    NativeImage small = new NativeImage(THUMB_W, THUMB_H, false);
                    // Cover the 16:9 box: crop to its shape, then sample.
                    float scale = Math.max((float) full.getWidth() / THUMB_W, (float) full.getHeight() / THUMB_H);
                    int offX = (int) ((full.getWidth() - THUMB_W * scale) / 2), offY = (int) ((full.getHeight() - THUMB_H * scale) / 2);
                    for (int y = 0; y < THUMB_H; y++) {
                        for (int x = 0; x < THUMB_W; x++) {
                            int sx = Math.min(full.getWidth() - 1, Math.max(0, offX + (int) (x * scale)));
                            int sy = Math.min(full.getHeight() - 1, Math.max(0, offY + (int) (y * scale)));
                            small.setPixel(x, y, full.getPixel(sx, sy));
                        }
                    }
                    decoded.put(name, small);
                } catch (Exception e) {
                    System.err.println("[Sky2M] Couldn't preview " + name + ": " + e.getMessage());
                }
            });
        }
        return null;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF07090D);
        g.centeredText(font, Component.literal("Screenshots").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), width / 2, 10, 0xFFFFFFFF);
        long uploadedCount = files.stream().filter(p -> link(p.getFileName().toString()) != null).count();
        g.centeredText(font, files.size() + " screenshots · " + uploadedCount + " uploaded · click one, then Upload or share its link", width / 2, 24, 0xFF9AA5B8);
        if (files.isEmpty()) {
            g.centeredText(font, "No screenshots yet. Press F2 in game to take one.", width / 2, height / 2, 0xFF7A8290);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }
        int cols = columns(), rowH = THUMB_H + 14 + GAP;
        int visibleRows = Math.max(1, (gridBottom() - gridTop()) / rowH);
        int rows = (files.size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - visibleRows)));
        g.enableScissor(0, gridTop(), width, gridBottom());
        for (int i = scroll * cols; i < files.size() && i < (scroll + visibleRows + 1) * cols; i++) {
            Path file = files.get(i);
            String name = file.getFileName().toString();
            int x = gridLeft() + (i % cols) * (THUMB_W + GAP);
            int y = gridTop() + (i / cols - scroll) * rowH;
            boolean isSelected = name.equals(selected);
            boolean over = mouseX >= x && mouseX < x + THUMB_W && mouseY >= y && mouseY < y + THUMB_H + 12;
            g.fill(x - 2, y - 2, x + THUMB_W + 2, y + THUMB_H + 14, isSelected ? 0xFF55FFFF : over ? 0xFF3A3F48 : 0xFF1E222A);
            Identifier preview = preview(file);
            if (preview != null) g.blit(RenderPipelines.GUI_TEXTURED, preview, x, y, 0, 0, THUMB_W, THUMB_H, THUMB_W, THUMB_H, THUMB_W, THUMB_H);
            else {
                g.fill(x, y, x + THUMB_W, y + THUMB_H, 0xFF101318);
                g.centeredText(font, "Loading...", x + THUMB_W / 2, y + THUMB_H / 2 - 4, 0xFF555555);
            }
            boolean uploaded = link(name) != null;
            g.text(font, font.plainSubstrByWidth(name.replaceFirst("\\.(png|jpg)$", ""), THUMB_W - (uploaded ? 54 : 4)), x + 2, y + THUMB_H + 3, 0xFFB8C0CC, false);
            if (uploaded) g.text(font, "Uploaded", x + THUMB_W - font.width("Uploaded") - 2, y + THUMB_H + 3, 0xFF55FF55, false);
        }
        g.disableScissor();
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || event.y() < gridTop() || event.y() >= gridBottom()) return false;
        int cols = columns(), rowH = THUMB_H + 14 + GAP;
        int col = (int) ((event.x() - gridLeft()) / (THUMB_W + GAP));
        int row = (int) ((event.y() - gridTop()) / rowH) + scroll;
        if (col < 0 || col >= cols) return false;
        int index = row * cols + col;
        if (index < 0 || index >= files.size()) return false;
        String name = files.get(index).getFileName().toString();
        selected = name.equals(selected) ? null : name;
        rebuild();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public void removed() {
        // Free the previews' GPU memory and anything decoded but not shown.
        for (Identifier id : textures.values()) minecraft.getTextureManager().release(id);
        textures.clear();
        for (NativeImage image : decoded.values()) image.close();
        decoded.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
