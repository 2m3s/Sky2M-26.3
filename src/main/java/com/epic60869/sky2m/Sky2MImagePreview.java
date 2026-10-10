package com.epic60869.sky2m;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Shows a preview of an image when hovering an image link in chat (including Sky2M chat). Links ending in an
 * image extension preview straight away; Discord, Imgur and Gyazo links (and any other link that turns out to be an
 * image) are tried too. While it downloads a "Loading image..." box shows, so you can tell it's working. The preview
 * replaces the link's own hover text (the URL).
 */
public final class Sky2MImagePreview {
    private static final Pattern IMAGE_URL = Pattern.compile(
        "(?i)^https?://[^\\s]+\\.(?:png|jpe?g|gif|webp|bmp)(?:\\?[^\\s]*)?$"
    );
    /** Hosts whose links are usually images even without an extension. */
    private static final Pattern IMAGE_HOST = Pattern.compile(
        "(?i)^https?://(?:(?:cdn|media)\\.discordapp\\.(?:com|net)|(?:i\\.)?imgur\\.com|(?:i\\.)?gyazo\\.com|(?:i\\.)?ibb\\.co|prnt\\.sc|prntscr\\.com|(?:i\\.)?postimg\\.cc|(?:media\\.)?tenor\\.com)/\\S+$"
    );
    /** The picture a web page shares, e.g. ImgBB's page for a screenshot: <meta property="og:image" content="...">. */
    private static final Pattern OG_IMAGE = Pattern.compile(
        "(?is)<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*content=[\"']([^\"']+)[\"']|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"'](?:og:image|twitter:image)[\"']"
    );
    /** Links that were downloaded and turned out not to be images. */
    private static final Map<String, Boolean> NOT_IMAGE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOADING = new ConcurrentHashMap<>();
    private static final int MAX_WIDTH = 320;
    private static final int MAX_HEIGHT = 180;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private record Loaded(Identifier id, int width, int height) {}

    private static final Map<String, Loaded> TEXTURES = new ConcurrentHashMap<>();
    /** PNG bytes downloaded off-thread, waiting to be uploaded as a texture on the render thread. */
    private static final Map<String, byte[]> READY = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REQUESTED = new ConcurrentHashMap<>();

    private Sky2MImagePreview() {}

    public static void render(GuiGraphicsExtractor graphics, Style style, int mouseX, int mouseY) {
        String url = imageUrl(style);
        if (url == null) return;

        Loaded texture = texture(url);
        // The image (or its "Loading image..." box) takes the place of the link's hover text.
        if (texture != null || LOADING.containsKey(url)) {
            ((com.epic60869.sky2m.mixin.Sky2MDeferredTooltipAccessor) graphics).sky2m$setDeferredTooltip(null);
        }
        // Draw above the link's own hover text.
        graphics.nextStratum();
        if (texture == null) {
            if (LOADING.containsKey(url)) {
                var font = Minecraft.getInstance().font;
                String text = "Loading image...";
                int x = Math.max(6, Math.min(mouseX + 14, graphics.guiWidth() - font.width(text) - 14));
                int y = Math.max(6, mouseY - 24);
                graphics.fill(x - 4, y - 4, x + font.width(text) + 4, y + 12, 0xF0101116);
                graphics.fill(x - 4, y - 4, x + font.width(text) + 4, y - 2, 0xFF55FFFF);
                graphics.text(font, text, x, y, 0xFFAAAAAA, false);
            }
            return;
        }

        float scale = Math.min((float) MAX_WIDTH / texture.width(), (float) MAX_HEIGHT / texture.height());
        int width = Math.max(1, Math.round(texture.width() * scale));
        int height = Math.max(1, Math.round(texture.height() * scale));
        int x = Math.max(6, Math.min(mouseX + 14, graphics.guiWidth() - width - 6));
        int y = Math.max(6, Math.min(mouseY - height - 10, graphics.guiHeight() - height - 6));

        graphics.fill(x - 4, y - 4, x + width + 4, y + height + 4, 0xF0101116);
        graphics.fill(x - 4, y - 4, x + width + 4, y - 2, 0xFF55FFFF);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, 0, 0, width, height, width, height, width, height);
    }

    private static String imageUrl(Style style) {
        if (style == null) return null;

        String url = null;
        if (style.getClickEvent() instanceof ClickEvent.OpenUrl open) {
            url = open.uri().toString();
        } else if (style.getHoverEvent() instanceof HoverEvent.ShowText showText) {
            url = extractUrl(showText.value());
        }

        if (url == null || NOT_IMAGE.containsKey(url)) return null;
        url = url.trim();
        String normalized = normalizeUrl(url);
        if (normalized == null) return null;
        return IMAGE_URL.matcher(normalized).matches() || IMAGE_HOST.matcher(normalized).matches() ? normalized : null;
    }

    private static String extractUrl(Component component) {
        if (component == null) return null;
        String plain = component.getString();
        if (plain != null) {
            String extracted = findUrl(plain);
            if (extracted != null) return extracted;
        }
        for (Component sibling : component.toFlatList()) {
            String found = findUrl(sibling.getString());
            if (found != null) return found;
        }
        return null;
    }

    private static String findUrl(String text) {
        if (text == null) return null;
        var matcher = java.util.regex.Pattern.compile("https?://[^\\s<>'\"\\)]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) return normalizeUrl(matcher.group());
        return null;
    }

    private static String normalizeUrl(String value) {
        if (value == null) return null;
        String normalized = value.trim().replace("&amp;", "&");
        normalized = normalized.replaceAll("[\\u0000-\\u001F\\u007F]+$", "");
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isEmpty() ? null : normalized;
    }

    private static Loaded texture(String url) {
        Loaded existing = TEXTURES.get(url);
        if (existing != null) return existing;

        byte[] png = READY.remove(url);
        if (png != null) {
            try {
                NativeImage image = NativeImage.read(png);
                Identifier id = Identifier.fromNamespaceAndPath("sky2m", "chat_preview/" + sha256(url));
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
                Loaded loaded = new Loaded(id, image.getWidth(), image.getHeight());
                TEXTURES.put(url, loaded);
                return loaded;
            } catch (Exception e) {
                return null;
            }
        }

        if (REQUESTED.putIfAbsent(url, Boolean.TRUE) == null) {
            LOADING.put(url, System.currentTimeMillis());
            CompletableFuture.runAsync(() -> {
                try {
                    download(url);
                } finally {
                    LOADING.remove(url);
                }
            });
        }
        return null;
    }

    private static void download(String url) {
        try {
            Path cacheDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("sky2m").resolve("image-cache");
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve(sha256(url) + ".png");

            if (!Files.exists(file)) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(fetchUrl(url)))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Sky2M)")
                    .header("Accept", "image/png,image/jpeg,image/gif,image/*;q=0.8")
                    .GET().build();
                HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                String type = response.headers().firstValue("Content-Type").orElse("");
                if (type.startsWith("text/html")) {
                    // An image host's page (ImgBB, Imgur, Gyazo, Lightshot, ...): fetch the picture it shares.
                    String page = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher og = OG_IMAGE.matcher(page);
                    if (!og.find()) {
                        NOT_IMAGE.put(url, Boolean.TRUE);
                        return;
                    }
                    String imageUrl = (og.group(1) != null ? og.group(1) : og.group(2)).replace("&amp;", "&");
                    if (imageUrl.startsWith("//")) imageUrl = "https:" + imageUrl;
                    HttpRequest imageRequest = HttpRequest.newBuilder(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Mozilla/5.0 (Sky2M)")
                        .header("Accept", "image/png,image/jpeg,image/gif,image/*;q=0.8")
                        .GET().build();
                    response = CLIENT.send(imageRequest, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                    type = response.headers().firstValue("Content-Type").orElse("");
                }
                if (!type.isEmpty() && !type.startsWith("image/")) {
                    NOT_IMAGE.put(url, Boolean.TRUE); // not an image: stop trying
                    return;
                }
                // Minecraft can only read PNG, so JPG/GIF are converted (GIFs show their first frame).
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
                if (image == null) {
                    NOT_IMAGE.put(url, Boolean.TRUE); // e.g. WebP, which Java can't read
                    return;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                Files.write(file, out.toByteArray());
            }
            READY.put(url, Files.readAllBytes(file));
        } catch (Exception e) {
            // Allow a retry the next time the link is hovered.
            REQUESTED.remove(url);
        }
    }

    /** Asks image hosts for a format Java can read: Discord media as PNG, Imgur page links as the image itself. */
    private static String fetchUrl(String url) {
        if (url.matches("(?i)^https?://media\\.discordapp\\.net/.*")) {
            String cleaned = url.replaceAll("([?&])format=[^&]*&?", "$1").replaceAll("[?&]$", "");
            return cleaned + (cleaned.contains("?") ? "&" : "?") + "format=png";
        }
        return url;
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
}
