package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmetics from the Sky2M server: each player's supporter tier symbol and shown badge (drawn before their name in
 * chat, tab and nametags) and cape. The table ({@code cosmetics}) is sent after hello and on every change. Capes are
 * downloaded once per URL (the URL changes when the texture does) and kept in config/sky2m/capes.
 */
public final class SbcCosmetics {
    public record Symbol(String id, String name, String symbol, int colour) {}

    public record Entry(Symbol tier, Symbol badge, String capeName, String capeUrl) {}

    public record Cape(Identifier texture, int width, int height) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static volatile Map<UUID, Entry> table = Map.of();
    private static final Map<String, Cape> CAPES = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /** Your cosmetics (cosmeticsProfile), for /s2 cosmetics. */
    static JsonObject profile;

    private SbcCosmetics() {}

    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "cosmetics" -> {
                Map<UUID, Entry> parsed = new HashMap<>();
                for (JsonElement e : Sbc.arr(packet, "players")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    UUID uuid = Sbc.uuid(Sbc.str(o, "minecraftUuid"));
                    if (uuid == null) continue;
                    JsonObject cape = Sbc.obj(o, "cape");
                    parsed.put(uuid, new Entry(symbol(Sbc.obj(o, "tier")), symbol(Sbc.obj(o, "badge")),
                        Sbc.str(cape, "name"), Sbc.str(cape, "url")));
                }
                if (!parsed.equals(table)) com.epic60869.sky2m.Sky2MNick.invalidateCache();
                table = Map.copyOf(parsed);
            }
            case "cosmeticsProfile" -> {
                profile = packet;
                if (Minecraft.getInstance().gui.screen() instanceof SbcCosmeticsScreen screen) screen.refresh();
            }
            case "cosmeticsError" -> Sbc.error(Sbc.str(packet, "message").isBlank() ? "Couldn't change your cosmetics (" + Sbc.str(packet, "code") + ")." : Sbc.str(packet, "message"));
            case "badgeEarned" -> {
                Symbol badge = symbol(Sbc.obj(packet, "badge"));
                String message = Sbc.str(packet, "message");
                MutableComponent line = Component.literal("Badge earned! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
                if (badge != null) line.append(Component.literal(badge.symbol() + " " + badge.name()).withStyle(Style.EMPTY.withColor(badge.colour()).withBold(false)));
                if (!message.isBlank()) line.append(Component.literal(" " + message).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)));
                Sbc.say(line);
                Sbc.toast(Component.literal("Badge earned!").withStyle(ChatFormatting.GOLD),
                    Component.literal(badge == null ? message : badge.name()));
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1f);
            }
            default -> {}
        }
    }

    static Symbol symbol(JsonObject o) {
        if (o == null || o.size() == 0) return null;
        String symbol = Sbc.str(o, "symbol");
        String name = Sbc.str(o, "name");
        if (symbol.isBlank() && name.isBlank()) return null;
        return new Symbol(Sbc.str(o, "id"), name, symbol, Sbc.hex(Sbc.str(o, "color"), 0xFFFFFF));
    }

    public static Entry entry(UUID uuid) {
        return uuid == null ? null : table.get(uuid);
    }

    /** The tier symbol and badge (with a space after), or null if the player has neither or symbols are off. */
    public static Component symbols(UUID uuid) {
        if (!Sbc.config().cosmetics.symbols || !Flags.isEnabled("cosmetics")) return null;
        Entry entry = entry(uuid);
        if (entry == null || (entry.tier() == null && entry.badge() == null)) return null;
        MutableComponent out = Component.empty();
        for (Symbol s : new Symbol[]{entry.tier(), entry.badge()}) {
            if (s == null || s.symbol().isBlank()) continue;
            out.append(Component.literal(s.symbol()).withStyle(Style.EMPTY.withColor(s.colour()).withBold(false).withItalic(false)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(s.name()).withStyle(Style.EMPTY.withColor(s.colour()))))));
        }
        if (out.getString().isEmpty()) return null;
        return out.append(Component.literal(" "));
    }

    /** {@code name} with the player's symbols in front. */
    public static Component decorate(UUID uuid, Component name) {
        Component symbols = symbols(uuid);
        return symbols == null ? name : Component.empty().append(symbols).append(name);
    }

    /** Players who have a symbol to show. */
    public static Set<UUID> decoratedPlayers() {
        if (!Sbc.config().cosmetics.symbols) return Set.of();
        return table.keySet();
    }

    // ------------------------------------------------------------------------------------------------ capes

    /** The player's cape texture, once downloaded (starts the download the first time). */
    public static Identifier capeTexture(UUID uuid) {
        if (!Sbc.config().cosmetics.capes || !Flags.isEnabled("cosmetics.capes")) return null;
        Entry entry = entry(uuid);
        if (entry == null || entry.capeUrl().isBlank()) return null;
        Cape cape = cape(entry.capeUrl());
        return cape == null ? null : cape.texture();
    }

    /** A downloaded cape by URL, or null while it downloads. */
    public static Cape cape(String url) {
        if (url == null || url.isBlank()) return null;
        Cape cape = CAPES.get(url);
        if (cape == null && !FAILED.contains(url) && LOADING.add(url)) download(url);
        return cape;
    }

    private static void download(String url) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("sky2m").resolve("capes");
        String hash = hash(url);
        Path file = dir.resolve(hash + ".png");
        Thread thread = new Thread(() -> {
            try {
                byte[] bytes;
                if (Files.exists(file)) {
                    bytes = Files.readAllBytes(file);
                } else {
                    URI uri = URI.create(url.startsWith("/") ? "https://shadowisabot.com" + url : url);
                    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "Sky2M/" + SbcInfo.modVersion()).GET().build();
                    HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200 || response.body().length == 0 || response.body().length > 2_000_000) {
                        throw new IllegalStateException("HTTP " + response.statusCode());
                    }
                    bytes = response.body();
                    Files.createDirectories(dir);
                    Files.write(file, bytes);
                }
                NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                Minecraft.getInstance().execute(() -> {
                    Identifier id = Compat.id("capes/" + hash);
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "sky2m cape " + hash, image));
                    CAPES.put(url, new Cape(id, image.getWidth(), image.getHeight()));
                    LOADING.remove(url);
                });
            } catch (Exception e) {
                FAILED.add(url);
                LOADING.remove(url);
                System.err.println("[Sky2M] Cape download failed (" + url + "): " + e.getMessage());
            }
        }, "Sky2M cape download");
        thread.setDaemon(true);
        thread.start();
    }

    private static String hash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    // ------------------------------------------------------------------------------------------------ /s2 cosmetics

    public static void openScreen() {
        if (!Flags.check("cosmetics")) return;
        SbcNet.sendAuthed(Sbc.packet("cosmeticsGet"));
        Compat.queueOpenScreen(new SbcCosmeticsScreen());
    }

    static void select(String badge, String cape) {
        JsonObject p = Sbc.packet("cosmeticsSelect");
        p.addProperty("badge", badge == null ? "" : badge);
        p.addProperty("cape", cape == null ? "" : cape);
        SbcNet.sendAuthed(p);
    }
}
