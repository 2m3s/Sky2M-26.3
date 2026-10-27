package com.epic60869.sky2m.features.misc.clips;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

/**
 * Sky2M owns the Clips settings and save key. Recording is provided by the separate Sky2M Clipping Fabric mod
 * (github.com/2m3s/Sky2m-Clipping), discovered through the "sky2m-clipping" Fabric entrypoint.
 * <p>
 * Players without it are asked once, on the title screen, whether to download it: Yes fetches the JAR for this
 * Minecraft version from the latest GitHub release into the mods folder and asks them to restart the game.
 * <p>
 * Clipping entrypoint contract:
 *   configure(Map<String, Object> settings)
 *   save(String name) -> CompletableFuture<Map<String, Object>> (result includes path and optional seconds)
 *   status() -> Map<String, Object> (recording, and optional encoder and status)
 *   shutdown()
 */
public final class Clips {
    private static final Logger LOGGER = LoggerFactory.getLogger("sky2m-clips");
    private static final String CLIPPING_ENTRYPOINT = "sky2m-clipping";
    private static final String CLIPPING_MOD_ID = "sky2m-clipping";
    private static final String RELEASES_URL = "https://github.com/2m3s/Sky2m-Clipping/releases";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/2m3s/Sky2m-Clipping/releases/latest";
    private static final long MAX_DOWNLOAD = 50L * 1024 * 1024;
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);
    private static final Path DEFAULT_FOLDER = Path.of(
        System.getProperty("user.home", "."), "Videos", "Sky2M Clips"
    );

    public enum Quality {
        P720("720p", 720), P1080("1080p", 1080), P1440("1440p", 1440), WINDOW("Window size", 0);
        private final String label;
        final int height;
        Quality(String label, int height) { this.label = label; this.height = height; }
        @Override public String toString() { return label; }
    }

    public enum Fps {
        FPS30("30", 30), FPS60("60", 60);
        private final String label;
        final int fps;
        Fps(String label, int fps) { this.label = label; this.fps = fps; }
        @Override public String toString() { return label; }
    }

    public enum Sound {
        GAME("Game only", "game"),
        GAME_DISCORD("Game+Discord", "game_discord"),
        DESKTOP("Everything", "desktop"),
        NONE("No sound", "none");
        private final String label;
        final String wire;
        Sound(String label, String wire) { this.label = label; this.wire = wire; }
        @Override public String toString() { return label; }
    }

    private static volatile Object clipping;
    private static boolean askedThisSession;
    private static boolean promptShown;
    private static boolean keyWasDown;
    private static int tick;
    private static String lastConfig = "";
    private static volatile boolean recording;
    private static volatile String encoder = "";
    private static volatile String clippingStatus = "";
    private static volatile boolean downloading;
    /** The JAR downloaded this session; the mod only loads after a restart. */
    private static volatile Path downloaded;

    private Clips() {}

    private static Sky2MConfig.Clips config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.clips;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        Runtime.getRuntime().addShutdownHook(new Thread(Clips::stop, "Sky2M clips stop"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("clip")
                    .executes(c -> { save(""); return 1; })
                    .then(ClientCommands.literal("download").executes(c -> { download(); return 1; }))
                    .then(ClientCommands.literal("folder").executes(c -> { openFolder(); return 1; }))
                    .then(ClientCommands.literal("status").executes(c -> { status(); return 1; }))
                    .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                        save(StringArgumentType.getString(c, "name"));
                        return 1;
                    }))));
            }
        });
    }

    private static void tick() {
        Sky2MConfig.Clips cfg = config();
        if (cfg == null) return;
        if (!cfg.askedClippingMod) askOnTitleScreen(cfg);
        if (!cfg.enabled) {
            if (clipping != null) stop();
            return;
        }
        if (!windows()) {
            if (!askedThisSession) {
                askedThisSession = true;
                say(Component.literal("Sky2M Clips currently supports Windows only.").withStyle(ChatFormatting.RED));
            }
            return;
        }
        if (++tick % 20 == 0) {
            Object api = findClipping();
            if (api == null) {
                if (!askedThisSession && !downloading && downloaded == null) {
                    askedThisSession = true;
                    askToDownload();
                }
            } else {
                if (clipping != api) {
                    clipping = api;
                    lastConfig = "";
                    LOGGER.info("Connected to Sky2M Clipping Fabric mod");
                }
                applyConfig(cfg);
                refreshStatus();
            }
        }
        boolean down = cfg.saveKey > 0 && keyDown(cfg.saveKey);
        if (down && !keyWasDown && !(Minecraft.getInstance().gui.screen() instanceof ChatScreen)) save("");
        keyWasDown = down;
    }

    /**
     * On the title screen, asks whether to download the Sky2M Clipping mod - every player once, including ones who
     * answered the old recorder-program question. Yes downloads it and turns Clips on; No leaves it (it can still be
     * downloaded from /s2 > Clips or /s2 clip download). It is only asked again if the game closed before they answered.
     */
    private static void askOnTitleScreen(Sky2MConfig.Clips cfg) {
        Minecraft mc = Minecraft.getInstance();
        if (promptShown || !(mc.gui.screen() instanceof TitleScreen title)) return;
        if (!windows() || findClipping() != null) {
            answered(cfg);
            return;
        }
        promptShown = true;
        mc.gui.setScreen(new ConfirmScreen(yes -> {
            answered(cfg);
            mc.gui.setScreen(title);
            if (yes) download();
        }, Component.literal("Sky2M Clipping"),
            Component.literal("Would you like to automatically download the clipping mod?\n\n"
                + "Sky2M Clipping saves the last few seconds of the game as a video with one key, and has an in-game clip editor. "
                + "It goes in your mods folder and starts after you restart the game. If you say no, you can download it later in /s2 > Clips."),
            Component.literal("Yes, download"), Component.literal("No")));
    }

    private static void answered(Sky2MConfig.Clips cfg) {
        cfg.askedClippingMod = true;
        Sky2MConfig c = Sky2MConfig.current();
        if (c != null) Sky2MConfig.saveCurrent(c);
    }

    private static Object findClipping() {
        try {
            List<Object> entries = FabricLoader.getInstance().getEntrypoints(CLIPPING_ENTRYPOINT, Object.class);
            return entries.isEmpty() ? null : entries.get(0);
        } catch (Throwable e) {
            LOGGER.debug("Sky2M Clipping entrypoint isn't available", e);
            return null;
        }
    }

    private static Map<String, Object> configMap(Sky2MConfig.Clips cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", ProcessHandle.current().pid());
        m.put("seconds", cfg.seconds);
        m.put("fps", cfg.fps.fps);
        m.put("height", cfg.quality.height);
        m.put("audio", cfg.sound.wire);
        m.put("mic", cfg.mic);
        m.put("gameVolume", cfg.gameVolume);
        m.put("discordVolume", cfg.discordVolume);
        m.put("desktopVolume", cfg.desktopVolume);
        m.put("micVolume", cfg.micVolume);
        m.put("folder", folder().toString());
        return m;
    }

    private static void applyConfig(Sky2MConfig.Clips cfg) {
        Object api = clipping;
        if (api == null) return;
        Map<String, Object> settings = configMap(cfg);
        String signature = settings.toString();
        if (signature.equals(lastConfig)) return;
        try {
            invoke(api, "configure", new Class<?>[]{Map.class}, settings);
            lastConfig = signature;
        } catch (Exception e) {
            LOGGER.error("Could not send Sky2M Clips settings to the clipping mod", e);
            say(Component.literal("Couldn't configure Sky2M Clipping: " + rootMessage(e)).withStyle(ChatFormatting.RED));
        }
    }

    private static void refreshStatus() {
        Object api = clipping;
        if (api == null) return;
        try {
            Object result = invoke(api, "status", new Class<?>[0]);
            if (result instanceof Map<?, ?> map) {
                Object r = map.get("recording");
                if (r instanceof Boolean b) recording = b;
                Object e = map.get("encoder");
                if (e != null) encoder = e.toString();
                Object s = map.get("status");
                if (s != null) clippingStatus = s.toString();
            }
        } catch (Exception e) {
            LOGGER.debug("Couldn't read clipping status", e);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        return method.invoke(target, args);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static Path folder() {
        return DEFAULT_FOLDER;
    }

    private static void stop() {
        Object api = clipping;
        clipping = null;
        lastConfig = "";
        recording = false;
        if (api == null) return;
        try {
            invoke(api, "shutdown", new Class<?>[0]);
        } catch (Exception e) {
            LOGGER.debug("Couldn't stop Sky2M Clipping cleanly", e);
        }
    }

    private static MutableComponent link(String text, ChatFormatting colour, ClickEvent click, String hover) {
        return Component.literal(text).withStyle(s -> s.withColor(colour).withClickEvent(click)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    public static void save(String name) {
        Sky2MConfig.Clips cfg = config();
        if (cfg == null || !cfg.enabled) {
            say(Component.literal("Clips are off: turn them on in /s2 > Clips.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Object api = clipping != null ? clipping : findClipping();
        if (api == null) {
            if (downloaded != null) {
                say(Component.literal("Sky2M Clipping is downloaded: restart Minecraft to start recording.").withStyle(ChatFormatting.YELLOW));
            } else {
                askToDownload();
            }
            return;
        }
        try {
            Object result = invoke(api, "save", new Class<?>[]{String.class}, name == null ? "" : name.trim());
            if (result instanceof CompletableFuture<?> future) {
                say(Component.literal("Saving clip...").withStyle(ChatFormatting.GRAY));
                future.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        say(Component.literal("Clip failed: " + rootMessage(error)).withStyle(ChatFormatting.RED));
                        return;
                    }
                    savedMessage(value);
                }));
            } else {
                savedMessage(result);
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't request a clip from Sky2M Clipping", e);
            say(Component.literal("Couldn't save clip: " + rootMessage(e)).withStyle(ChatFormatting.RED));
        }
    }

    private static void savedMessage(Object value) {
        String pathString = null;
        double seconds = 0;
        if (value instanceof Map<?, ?> map) {
            Object path = map.get("path");
            if (path != null) pathString = path.toString();
            Object duration = map.get("seconds");
            if (duration instanceof Number n) seconds = n.doubleValue();
        } else if (value != null) {
            pathString = value.toString();
        }
        if (pathString == null || pathString.isBlank()) {
            say(Component.literal("Clip saved.").withStyle(ChatFormatting.GREEN));
            return;
        }
        Path file = Path.of(pathString);
        MutableComponent line = Component.literal(String.format(Locale.ROOT, "Clip saved%s ", seconds > 0 ? String.format(Locale.ROOT, " (%.0f s)", seconds) : ""))
            .withStyle(ChatFormatting.GREEN)
            .append(link("[Open]", ChatFormatting.AQUA, new ClickEvent.OpenFile(file.toAbsolutePath()), file.getFileName().toString()))
            .append(Component.literal(" "))
            .append(link("[Folder]", ChatFormatting.YELLOW, new ClickEvent.OpenFile(file.toAbsolutePath().getParent()), "Sky2M Clips folder"));
        say(line);
    }

    public static void openFolder() {
        try {
            Files.createDirectories(folder());
        } catch (Exception e) {
            LOGGER.debug("Couldn't create clips folder", e);
        }
        openPath(folder());
    }

    private static void status() {
        Sky2MConfig.Clips cfg = config();
        String text;
        if (cfg == null || !cfg.enabled) text = "Clips are off.";
        else if (clipping == null && findClipping() == null)
            text = downloaded != null ? "Sky2M Clipping is downloaded: restart Minecraft to use it." : "Sky2M Clipping mod isn't installed.";
        else if (recording) text = "Recording" + (encoder.isBlank() ? "" : " with " + encoder) + ".";
        else text = "Sky2M Clipping: " + (clippingStatus.isBlank() ? "loaded" : clippingStatus) + ".";
        say(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    private static void askToDownload() {
        say(Component.literal("Clips need the Sky2M Clipping mod. ")
            .withStyle(ChatFormatting.YELLOW)
            .append(link("[Download]", ChatFormatting.GREEN, new ClickEvent.RunCommand("/s2 clip download"),
                "Download Sky2M Clipping for Minecraft " + minecraftVersion() + " into your mods folder"))
            .append(Component.literal(" "))
            .append(link("[GitHub]", ChatFormatting.AQUA, new ClickEvent.OpenUrl(URI.create(RELEASES_URL)), RELEASES_URL)));
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    /**
     * Downloads the Sky2M Clipping JAR for this Minecraft version from the latest GitHub release into the mods folder,
     * checks it (size, GitHub's SHA-256 when it gives one, and that it really is the sky2m-clipping mod), turns Clips
     * on, and asks the player to restart. Nothing in the mods folder is touched if anything fails.
     */
    public static void download() {
        if (downloading) return;
        if (!windows()) {
            say(Component.literal("Clips only works on Windows.").withStyle(ChatFormatting.RED));
            return;
        }
        if (findClipping() != null) {
            say(Component.literal("Sky2M Clipping is already installed.").withStyle(ChatFormatting.GREEN));
            return;
        }
        downloading = true;
        String mcVersion = minecraftVersion();
        notify("Sky2M Clipping", "Downloading the clipping mod...");
        Thread t = new Thread(() -> {
            Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
            Path part = null;
            try {
                HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15)).build();
                HttpResponse<String> api = http.send(HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "Sky2M")
                    .header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString());
                if (api.statusCode() != 200) throw new IllegalStateException("GitHub said HTTP " + api.statusCode());
                JsonObject asset = findAsset(JsonParser.parseString(api.body()).getAsJsonObject(), mcVersion);
                if (asset == null) throw new IllegalStateException("the latest release has no JAR for Minecraft " + mcVersion);
                String name = asset.get("name").getAsString();
                if (!name.matches("[A-Za-z0-9._+-]+\\.jar")) throw new IllegalStateException("unexpected file name " + name);
                String url = asset.get("browser_download_url").getAsString();
                String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";

                Files.createDirectories(mods);
                part = mods.resolve(name + ".part");
                HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5)).header("User-Agent", "Sky2M").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) throw new IllegalStateException("download said HTTP " + response.statusCode());
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_DOWNLOAD) throw new IllegalStateException("the download is far bigger than expected");
                        sha.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
                String hash = HexFormat.of().formatHex(sha.digest());
                if (digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(hash))
                    throw new IllegalStateException("the download doesn't match GitHub's checksum");
                checkIsClippingMod(part);
                Path jar = mods.resolve(name);
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                downloaded = jar;
                LOGGER.info("Downloaded Sky2M Clipping to {} (SHA-256 {})", jar, hash);
                Minecraft.getInstance().execute(Clips::downloadDone);
            } catch (Exception e) {
                LOGGER.error("Sky2M Clipping download failed", e);
                String message = rootMessage(e);
                Minecraft.getInstance().execute(() -> {
                    notify("Sky2M Clipping", "Download failed: " + message);
                    say(Component.literal("Couldn't download Sky2M Clipping: " + message + " ").withStyle(ChatFormatting.RED)
                        .append(link("[GitHub]", ChatFormatting.AQUA, new ClickEvent.OpenUrl(URI.create(RELEASES_URL)), RELEASES_URL)));
                });
                if (part != null) {
                    try {
                        Files.deleteIfExists(part);
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                downloading = false;
            }
        }, "Sky2M clipping download");
        t.setDaemon(true);
        t.start();
    }

    /** The release asset built for this Minecraft version: sky2m-clipping-<version>+<mc>.jar. */
    private static JsonObject findAsset(JsonObject release, String mcVersion) {
        if (!release.has("assets")) return null;
        for (JsonElement e : release.getAsJsonArray("assets")) {
            JsonObject asset = e.getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (name.startsWith("sky2m-clipping-") && name.endsWith("+" + mcVersion + ".jar")) return asset;
        }
        return null;
    }

    private static void checkIsClippingMod(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IllegalStateException("the download isn't a Fabric mod");
            try (InputStream in = zip.getInputStream(entry)) {
                JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!json.has("id") || !CLIPPING_MOD_ID.equals(json.get("id").getAsString()))
                    throw new IllegalStateException("the download isn't the Sky2M Clipping mod");
            }
        }
    }

    private static void downloadDone() {
        Sky2MConfig.Clips cfg = config();
        if (cfg != null && !cfg.enabled) {
            cfg.enabled = true;
            Sky2MConfig c = Sky2MConfig.current();
            if (c != null) Sky2MConfig.saveCurrent(c);
        }
        say(Component.literal("Sky2M Clipping downloaded into your mods folder. Restart Minecraft to start recording clips.")
            .withStyle(ChatFormatting.GREEN));
        Minecraft mc = Minecraft.getInstance();
        Screen back = mc.gui.screen();
        mc.gui.setScreen(new ConfirmScreen(quit -> {
            if (quit) mc.stop();
            else mc.gui.setScreen(back);
        }, Component.literal("Sky2M Clipping downloaded"),
            Component.literal("Restart Minecraft to start recording clips. Clips are turned on in /s2 > Clips."),
            Component.literal("Quit game"), Component.literal("Later")));
    }

    private static void notify(String title, String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, Component.literal(title), Component.literal(message)));
    }

    private static void say(Component message) {
        Sky2MAlerts.chat(message);
    }

    // ---------------------------------------------------------------- Minecraft 26.3 specifics

    private static boolean keyDown(int key) {
        int mouse = com.epic60869.sky2m.custom.util.Input.bindMouseButton(key);
        if (mouse != -1) return com.epic60869.sky2m.custom.util.Input.mouseDown(mouse);
        return key > 9 && com.epic60869.sky2m.custom.util.Input.keyDown(key);
    }

    private static void openPath(Path path) {
        com.mojang.blaze3d.Blaze3D.openPath(path);
    }
}
