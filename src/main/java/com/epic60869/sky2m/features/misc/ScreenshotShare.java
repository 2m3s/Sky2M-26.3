package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Screenshot sharing, like Skysoft's: after F2, the "Saved screenshot as ..." message gets an [Upload] button. It
 * uploads the picture and gives you the link with [Send in /s2c] and [Copy Link]. The links are direct images, so
 * they preview when hovered in chat.
 *
 * <p>By default it goes to ImgBB through the Sky2M server ({@code POST /mod-api/screenshots}), which holds the
 * ImgBB key, as Skysoft does with its own server; the links last 30 days. If that fails it goes to Catbox. Catbox
 * and Uguu can also be picked; neither needs an account or key.
 */
public final class ScreenshotShare {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Settings saved with a host that's gone (Litterbox, 0x0.st) load as null, which means the default. */
    public enum Host {
        SKY2M("Sky2M"),
        CATBOX("Catbox"),
        UGUU("Uguu");

        private final String label;

        Host(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private ScreenshotShare() {}

    private static Sky2MConfig.Screenshots config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.screenshots;
    }

    private static Host host(Sky2MConfig.Screenshots c) {
        return c == null || c.host == null ? Host.SKY2M : c.host;
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("screenshot")
                    .then(ClientCommands.literal("upload").then(ClientCommands.argument("file", StringArgumentType.greedyString())
                        .executes(c -> upload(StringArgumentType.getString(c, "file")))))));
            }
        });
    }

    /** Adds the [Upload] button to Minecraft's "Saved screenshot as ..." message (called from the chat mixin). */
    public static Component decorate(Component message) {
        Sky2MConfig.Screenshots c = config();
        if (message == null || c == null || !c.enabled) return message;
        if (!(message.getContents() instanceof TranslatableContents translatable) || !translatable.getKey().equals("screenshot.success")) return message;
        Object[] args = translatable.getArgs();
        if (args.length == 0 || !(args[0] instanceof Component file)) return message;
        String name = file.getString();
        if (!name.matches("[\\w .()-]+\\.png")) return message;
        Host host = host(c);
        return message.copy().append(Component.literal("  [Upload]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("/s2 screenshot upload " + name))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Upload it and get a link to share in /s2c.\n").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("Uploads publicly to " + host + ": anyone with the link can see it.").withStyle(ChatFormatting.YELLOW))))));
    }

    private static int upload(String name) {
        Minecraft mc = Minecraft.getInstance();
        Path file = mc.gameDirectory.toPath().resolve("screenshots").resolve(name).normalize();
        if (!file.startsWith(mc.gameDirectory.toPath().resolve("screenshots")) || !Files.isRegularFile(file)) {
            return say(Component.literal("Couldn't find that screenshot.").withStyle(ChatFormatting.RED));
        }
        Host host = host(config());
        say(Component.literal("Uploading " + name + "...").withStyle(ChatFormatting.GRAY));
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String link = uploadWithFallback(file, host);
                MutableComponent message = Component.literal("Screenshot uploaded: ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(link).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(link)))))
                    .append(Component.literal("  [Send in /s2c]").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/s2c " + link))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Post the link in Sky2M chat")))))
                    .append(Component.literal("  [Copy Link]").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(link))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy the link")))));
                say(message);
            } catch (Exception e) {
                say(Component.literal("Couldn't upload the screenshot: " + reason(e, host)).withStyle(ChatFormatting.RED)
                    .append(Component.literal("  [Retry]").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/s2 screenshot upload " + name))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Try uploading it again.\n").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("If " + host + " keeps failing, pick another host under Misc > Screenshots > Upload Host.")
                                .withStyle(ChatFormatting.GRAY)))))));
            }
        });
        return 1;
    }

    /** The Sky2M server couldn't take it (down, or ImgBB failing): Catbox instead. */
    private static String uploadWithFallback(Path file, Host host) throws Exception {
        try {
            return sendWithRetry(file, host);
        } catch (Exception e) {
            if (host != Host.SKY2M) throw e;
            say(Component.literal("The Sky2M upload didn't work (" + reason(e, host) + "), trying Catbox...").withStyle(ChatFormatting.GRAY));
            return sendWithRetry(file, Host.CATBOX);
        }
    }

    /** Free hosts sometimes fail for a moment (HTTP 500, a timeout): try once more before giving up. */
    private static String sendWithRetry(Path file, Host host) throws Exception {
        try {
            return send(file, host);
        } catch (Exception first) {
            Thread.sleep(2_000);
            return send(file, host);
        }
    }

    /** Multipart upload; each host answers with the file's link as plain text. */
    private static String send(Path file, Host host) throws Exception {
        String boundary = "----Sky2M" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String url;
        String fileField;
        switch (host) {
            case SKY2M -> {
                url = "https://shadowisabot.com/mod-api/screenshots";
                fileField = "image";
            }
            case UGUU -> {
                url = "https://uguu.se/upload?output=text";
                fileField = "files[]";
            }
            default -> {
                url = "https://catbox.moe/user/api.php";
                fileField = "fileToUpload";
                field(body, boundary, "reqtype", "fileupload");
            }
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
            + file.getFileName() + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(file));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(70))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("User-Agent", "Sky2M/" + com.epic60869.sky2m.features.sbc.SbcInfo.modVersion());
        // Lets the server rate-limit uploads per player.
        if (host == Host.SKY2M) builder.header("X-Minecraft-Uuid", String.valueOf(Minecraft.getInstance().getUser().getProfileId()));
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body().trim();
        if (host == Host.SKY2M) return sky2MLink(response.statusCode(), text);
        if (response.statusCode() != 200 || !text.startsWith("https://")) {
            // Error pages are whole HTML documents: only a short plain-text answer is worth showing.
            boolean readable = !text.isEmpty() && text.length() <= 200 && !text.contains("<");
            throw new IllegalStateException(host + " answered HTTP " + response.statusCode()
                + (readable ? ": " + text : response.statusCode() >= 500 ? " (its server is having problems, try again in a bit)" : ""));
        }
        return text.lines().findFirst().orElse(text).trim();
    }

    /**
     * The Sky2M server's answer: {@code {"url": "https://i.ibb.co/...png", "page": "https://ibb.co/..."}}, or
     * {@code {"error": "..."}} with a non-2xx status.
     */
    private static String sky2MLink(int status, String text) {
        com.google.gson.JsonObject json = null;
        try {
            json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception ignored) {}
        if (status < 200 || status > 299 || json == null || !json.has("url")) {
            String error = json != null && json.has("error") ? json.get("error").getAsString() : "";
            throw new IllegalStateException("the Sky2M server answered HTTP " + status + (error.isBlank() ? "" : ": " + error));
        }
        String link = json.get("url").getAsString();
        if (!link.startsWith("https://")) throw new IllegalStateException("the Sky2M server sent a bad link");
        return link;
    }

    /** A one-line reason for chat. */
    private static String reason(Exception e, Host host) {
        if (e instanceof IllegalStateException && e.getMessage() != null) return e.getMessage();
        if (e instanceof java.net.http.HttpTimeoutException) return host + " took too long to answer";
        if (e instanceof java.io.IOException) return "couldn't reach " + host;
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws java.io.IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static int say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(message));
        });
        return 1;
    }
}
