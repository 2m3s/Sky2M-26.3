package com.epic60869.sky2m.reports;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bug reports, suggestions and feedback.
 *
 * Anyone can send one with /s2 bugreport, /s2 suggest or /s2 feedback (a window with a title and a description).
 * They are stored on shadowisabot.com. Only the owner account (2m3s, by UUID) can read and delete them, with
 * /s2 viewboth, /s2 viewreport and /s2 viewsuggest, and gets a message in chat when some are waiting.
 *
 * Reading them proves who you are the way Minecraft servers do: the site hands out a random server id, the mod
 * "joins" it through Mojang's session server with your login, and the site asks Mojang whether 2m3s joined it.
 * The site API is:
 * <pre>
 * POST   /mod-api/reports                 {type: bug|suggestion|feedback, title, description, uuid, username, modVersion}
 * GET    /mod-api/auth/challenge          -> {serverId}
 * POST   /mod-api/auth/verify             {username, serverId} -> {token}   (checks Mojang hasJoined, owner only)
 * GET    /mod-api/reports?type=bug|suggestion|all   (Authorization: Bearer token) -> {reports: [{id, type, title,
 *        description, username, uuid, createdAt}]}
 * DELETE /mod-api/reports/{id}            (Authorization: Bearer token)
 * </pre>
 */
public final class Reports {
    public static final UUID OWNER = UUID.fromString("9761ccfb-2ccb-45cf-b6b4-fac991b7a019"); // 2m3s
    static final String API = "https://shadowisabot.com/mod-api";

    public enum Type {
        BUG("bug", "Bug Report"), SUGGESTION("suggestion", "Suggestion"), FEEDBACK("feedback", "Feedback");

        final String id;
        public final String label;

        Type(String id, String label) {
            this.id = id;
            this.label = label;
        }

        static Type of(String id) {
            for (Type t : values()) if (t.id.equalsIgnoreCase(id)) return t;
            return FEEDBACK;
        }
    }

    public record Report(String id, Type type, String title, String description, String username, long createdAt) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static String token;
    private static long tokenAt;
    private static boolean announced;

    private Reports() {}

    public static boolean isOwner() {
        return OWNER.equals(Minecraft.getInstance().getUser().getProfileId());
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("sky2m").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                var node = ClientCommands.literal(root)
                    .then(ClientCommands.literal("bugreport").executes(c -> open(Type.BUG)))
                    .then(ClientCommands.literal("suggest").executes(c -> open(Type.SUGGESTION)))
                    .then(ClientCommands.literal("feedback").executes(c -> open(Type.FEEDBACK)));
                // The viewing commands only exist for the owner account.
                if (isOwner()) {
                    node = node
                        .then(ClientCommands.literal("viewboth").executes(c -> view(ReportsViewScreen.Filter.ALL)))
                        .then(ClientCommands.literal("viewreport").executes(c -> view(ReportsViewScreen.Filter.BUGS)))
                        .then(ClientCommands.literal("viewsuggest").executes(c -> view(ReportsViewScreen.Filter.SUGGESTIONS)));
                }
                dispatcher.register(node);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!announced && isOwner()) {
                announced = true;
                CompletableFuture.delayedExecutor(4, TimeUnit.SECONDS).execute(Reports::announcePending);
            }
        });
    }

    private static int open(Type type) {
        return Compat.queueOpenScreen(new ReportScreen(type));
    }

    private static int view(ReportsViewScreen.Filter filter) {
        if (!isOwner()) return 0;
        return Compat.queueOpenScreen(new ReportsViewScreen(filter));
    }

    // ---------------------------------------------------------------- sending (anyone)

    /** Sends a report; the future says whether the site accepted it. */
    static CompletableFuture<Boolean> submit(Type type, String title, String description) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject body = new JsonObject();
        body.addProperty("type", type.id);
        body.addProperty("title", title);
        body.addProperty("description", description);
        body.addProperty("uuid", mc.getUser().getProfileId().toString());
        body.addProperty("username", mc.getUser().getName());
        body.addProperty("modVersion", version());
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/reports"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Sky2M/" + version())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 300)
            .exceptionally(e -> false);
    }

    // ---------------------------------------------------------------- reading (owner only)

    /** A token proving to the site that this is the owner, via Mojang's session server. */
    private static CompletableFuture<String> auth() {
        if (!isOwner()) return CompletableFuture.failedFuture(new IllegalStateException("not the owner"));
        if (token != null && System.currentTimeMillis() - tokenAt < 30 * 60_000L) return CompletableFuture.completedFuture(token);
        Minecraft mc = Minecraft.getInstance();
        return HTTP.sendAsync(get("/auth/challenge", null), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) throw new IllegalStateException("challenge HTTP " + r.statusCode());
            String serverId = JsonParser.parseString(r.body()).getAsJsonObject().get("serverId").getAsString();
            try {
                mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
            } catch (Exception e) {
                throw new IllegalStateException("Mojang session: " + e.getMessage());
            }
            JsonObject verify = new JsonObject();
            verify.addProperty("username", mc.getUser().getName());
            verify.addProperty("serverId", serverId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/auth/verify"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(verify.toString())).build();
            try {
                HttpResponse<String> v = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (v.statusCode() != 200) throw new IllegalStateException("verify HTTP " + v.statusCode());
                token = JsonParser.parseString(v.body()).getAsJsonObject().get("token").getAsString();
                tokenAt = System.currentTimeMillis();
                return token;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage());
            }
        });
    }

    private static HttpRequest get(String path, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Sky2M/" + version()).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return b.build();
    }

    /** Every report (bugs, suggestions and feedback), newest first. */
    static CompletableFuture<List<Report>> fetchAll() {
        return auth().thenCompose(t -> HTTP.sendAsync(get("/reports?type=all", t), HttpResponse.BodyHandlers.ofString())).thenApply(r -> {
            if (r.statusCode() == 401 || r.statusCode() == 403) token = null;
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
            JsonElement root = JsonParser.parseString(r.body());
            JsonArray array = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("reports");
            List<Report> out = new ArrayList<>();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Report(str(o, "id"), Type.of(str(o, "type")), str(o, "title"), str(o, "description"),
                    str(o, "username"), o.has("createdAt") ? o.get("createdAt").getAsLong() : 0));
            }
            out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
            return out;
        });
    }

    static CompletableFuture<Boolean> delete(String id) {
        return auth().thenCompose(t -> HTTP.sendAsync(HttpRequest.newBuilder(URI.create(API + "/reports/" + id))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + t).DELETE().build(),
            HttpResponse.BodyHandlers.ofString())).thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 300);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** "You have 2 bug reports, 1 suggestion and 3 feedback waiting. [View]" */
    private static void announcePending() {
        fetchAll().thenAccept(list -> {
            long bugs = list.stream().filter(r -> r.type() == Type.BUG).count();
            long suggestions = list.stream().filter(r -> r.type() == Type.SUGGESTION).count();
            long feedback = list.stream().filter(r -> r.type() == Type.FEEDBACK).count();
            if (bugs + suggestions + feedback == 0) return;
            MutableComponent message = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Waiting for you: ").withStyle(ChatFormatting.YELLOW))
                .append(count(bugs, "bug report", ChatFormatting.RED, "viewreport"))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(count(suggestions, "suggestion", ChatFormatting.AQUA, "viewsuggest"))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(count(feedback, "feedback", ChatFormatting.LIGHT_PURPLE, "viewsuggest"))
                .append(Component.literal("  [View All]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
                    .withClickEvent(new ClickEvent.RunCommand("/s2 viewboth"))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open all reports, suggestions and feedback")))));
            say(message);
        }).exceptionally(e -> {
            say(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Couldn't check for bug reports: " + rootMessage(e)).withStyle(ChatFormatting.GRAY)));
            return null;
        });
    }

    private static Component count(long n, String word, ChatFormatting colour, String command) {
        String text = n + " " + word + (n == 1 || word.equals("feedback") ? "" : "s");
        return Component.literal(text).withStyle(s -> s.withColor(colour)
            .withClickEvent(new ClickEvent.RunCommand("/s2 " + command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to view"))));
    }

    static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
        });
    }
}
