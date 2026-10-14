package com.epic60869.sky2m.features.profileviewer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads other players' SkyBlock profiles for the Profile Viewer and Party Finder Stats. Sky2M has no Hypixel API key,
 * so profiles come from a public proxy of Hypixel's /v2/skyblock/profiles (Odin's by default, set in Misc > Profile
 * Viewer), after Mojang turns the name into a UUID. Answers are kept for five minutes.
 */
public final class ProfileApi {
    public static final String DEFAULT_URL = "https://api.odtheking.com/hypixel/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final long CACHE_MS = 5 * 60 * 1000L;

    /** One SkyBlock profile of a player: {@code member} is their part of it, {@code profile} the shared part (bank). */
    public record Profile(String id, String cuteName, String gameMode, boolean selected, JsonObject profile, JsonObject member) {
        public String label() {
            String mode = switch (gameMode) {
                case "ironman" -> " ♲";
                case "island" -> " ☀";
                case "bingo" -> " Ⓑ";
                default -> "";
            };
            return cuteName + mode;
        }
    }

    /** A player and all their profiles, the selected one first. */
    public record Player(String name, UUID uuid, List<Profile> profiles) {}

    private record Cached(Player player, long at) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /** Where the last fresh lookup's profiles came from, for the networth history's breakdown. */
    public static volatile String lastSource = "";

    private ProfileApi() {}

    public static CompletableFuture<Player> player(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MS) return CompletableFuture.completedFuture(cached.player());
        return playerFresh(name);
    }

    /** {@link #player}, always loaded now rather than from the last five minutes' lookups (the networth history). */
    public static CompletableFuture<Player> playerFresh(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        lastSource = "";
        return get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name).thenCompose(body -> {
            JsonObject mojang = JsonParser.parseString(body).getAsJsonObject();
            if (!mojang.has("id")) throw new IllegalStateException("no such player");
            String id = mojang.get("id").getAsString().replace("-", "");
            String realName = mojang.has("name") ? mojang.get("name").getAsString() : name;
            // Like SkyBlockPV's /pv: its backend's /profiles is up to date. Sky2M's proxy can be hours behind (it
            // had a few of someone's backpacks and ender chest pages, billions short), so it's only the fallback.
            return backendGet("/profiles/" + id, true)
                .thenApply(data -> data != null && data.has("profiles") ? parse(realName, id, data) : null)
                .exceptionally(error -> {
                    lastSource = "Sky2M's proxy (SkyBlockPV: " + reason(error) + ")";
                    return null;
                })
                .thenCompose(fresh -> {
                    if (fresh != null) {
                        lastSource = "SkyBlockPV";
                        return CompletableFuture.completedFuture(fresh);
                    }
                    if (!lastSource.startsWith("Sky2M's proxy")) lastSource = "Sky2M's proxy (SkyBlockPV had no profiles)";
                    return get(baseUrl() + "get/" + id).thenApply(data -> parse(realName, id, JsonParser.parseString(data).getAsJsonObject()));
                });
        }).whenComplete((player, error) -> {
            if (player != null) CACHE.put(key, new Cached(player, System.currentTimeMillis()));
        });
    }

    /** Why a lookup failed, in a few words. */
    public static String reason(Throwable error) {
        if (error == null) return "no SkyBlock profiles";
        Throwable t = error;
        while (t.getCause() != null && t != t.getCause()) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String baseUrl() {
        Sky2MConfig c = Sky2MConfig.current();
        String base = c == null || c.misc.profileViewer.apiUrl.isBlank() ? DEFAULT_URL : c.misc.profileViewer.apiUrl.trim();
        return base.endsWith("/") ? base : base + "/";
    }

    private static CompletableFuture<String> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "Sky2M").build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 404 || r.statusCode() == 204) throw new IllegalStateException("no such player");
            if (r.statusCode() == 429) throw new IllegalStateException("too many lookups, try again in a minute");
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
            return r.body();
        });
    }

    private static Player parse(String name, String uuid, JsonObject root) {
        List<Profile> profiles = new ArrayList<>();
        JsonArray list = root.has("profiles") && root.get("profiles").isJsonArray() ? root.getAsJsonArray("profiles") : new JsonArray();
        for (JsonElement e : list) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            JsonObject member = Json.obj(Json.obj(p, "members"), uuid);
            if (member == null) continue;
            // Profiles they left (or were removed from) carry a deletion notice.
            if (Json.obj(Json.obj(member, "profile"), "deletion_notice") != null) continue;
            Profile profile = new Profile(Json.str(p, "profile_id"), Json.str(p, "cute_name"), Json.str(p, "game_mode"),
                Json.bool(p, "selected"), p, member);
            if (profile.selected()) profiles.addFirst(profile);
            else profiles.add(profile);
        }
        if (profiles.isEmpty()) throw new IllegalStateException(name + " has no SkyBlock profiles");
        return new Player(name, toUuid(uuid), profiles);
    }

    private static UUID toUuid(String plain) {
        return UUID.fromString(plain.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    // ------------------------------------------------------------------------------------------------ museum, garden, rank

    /**
     * The museum, garden and player (rank) endpoints, which Sky2M's profile proxy doesn't serve. They come from the
     * SkyBlockPV backend (meowdding/skyblock-pv-backend, as Killer560's Mod and SkyBlockPV use it), which proxies Hypixel
     * without a key. It signs you in like a server does: Sky2M calls Mojang's {@code joinServer} with a random server id
     * and the backend asks Mojang whether you joined, so your access token only ever goes to Mojang.
     */
    public enum Aux {
        MUSEUM("/museum/", "members"), GARDEN("/garden/", "garden"), PLAYER("/player/", "player");

        final String path, field;

        Aux(String path, String field) {
            this.path = path;
            this.field = field;
        }
    }

    private static final String BACKEND = "https://skyblock-pv.thatgravyboat.tech";
    private static final Map<String, CompletableFuture<JsonObject>> AUX = new ConcurrentHashMap<>();
    private static volatile String backendToken;
    private static volatile long backendTokenAt;
    private static CompletableFuture<String> tokenInFlight;

    /** A museum or garden (by profile id) or a player (by UUID); null data means there's none. Cached for the session. */
    public static CompletableFuture<JsonObject> aux(Aux kind, String id) {
        String key = kind + ":" + id.replace("-", "");
        CompletableFuture<JsonObject> cached = AUX.get(key);
        if (cached != null && !(cached.isDone() && cached.isCompletedExceptionally())) return cached;
        CompletableFuture<JsonObject> future = backendGet(kind.path + id.replace("-", ""), true).thenApply(body -> {
            if (body == null) return null;
            JsonElement data = body.get(kind.field);
            return data != null && data.isJsonObject() ? data.getAsJsonObject() : null;
        });
        AUX.put(key, future);
        return future;
    }

    /** Forgets a failed museum, garden or player lookup so the next one tries again. */
    public static void forgetAux(Aux kind, String id) {
        AUX.remove(kind + ":" + id.replace("-", ""));
    }

    private static CompletableFuture<JsonObject> backendGet(String path, boolean retryAuth) {
        return backendToken().thenCompose(token -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BACKEND + path)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Sky2M").header("Authorization", token).header("X-Intent", "Sky2M profile viewer").GET().build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        }).thenCompose(r -> {
            if (r.statusCode() == 200) return CompletableFuture.completedFuture(JsonParser.parseString(r.body()).getAsJsonObject());
            if (r.statusCode() == 401 && retryAuth) {
                backendToken = null;
                return backendGet(path, false);
            }
            if (r.statusCode() == 404) return CompletableFuture.completedFuture(null);
            if (r.statusCode() == 429) return CompletableFuture.failedFuture(new IllegalStateException("too many lookups, try again in a minute"));
            return CompletableFuture.failedFuture(new IllegalStateException("HTTP " + r.statusCode()));
        });
    }

    /** Mojang's session handshake for a backend token, kept for 23 of its 24 hours. */
    private static synchronized CompletableFuture<String> backendToken() {
        String token = backendToken;
        if (token != null && System.currentTimeMillis() - backendTokenAt < 23L * 60 * 60 * 1000) return CompletableFuture.completedFuture(token);
        if (tokenInFlight != null && !tokenInFlight.isDone()) return tokenInFlight;
        tokenInFlight = CompletableFuture.supplyAsync(() -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.client.User user = mc.getUser();
            String serverId = UUID.randomUUID().toString().replace("-", "");
            try {
                mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
            } catch (Exception e) {
                // The backend decides: it may still hand out a guest token.
                System.err.println("[Sky2M] Profile Viewer: Mojang sign-in failed, asking the SkyBlockPV backend anyway");
            }
            return new String[]{user.getName(), serverId};
        }).thenCompose(pair -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BACKEND + "/authenticate")).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Sky2M").header("x-minecraft-username", pair[0]).header("x-minecraft-server", pair[1]).GET().build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        }).thenApply(r -> {
            String body = r.body() == null ? "" : r.body().trim();
            if (r.statusCode() != 200 || body.isEmpty()) throw new IllegalStateException("sign-in to the SkyBlockPV backend failed (HTTP " + r.statusCode() + ")");
            backendToken = body;
            backendTokenAt = System.currentTimeMillis();
            return body;
        });
        return tokenInFlight;
    }

    // ------------------------------------------------------------------------------------------------ level tables

    private static volatile JsonObject leveling, pets;
    private static volatile boolean loading;

    /** NEU's level tables (constants/leveling.json and pets.json), loaded once in the background. */
    public static void loadTables() {
        if (loading || leveling != null) return;
        loading = true;
        RepoItems.runAsync(() -> {
            try {
                leveling = JsonParser.parseString(RepoItems.neuRepoFile("constants/leveling.json")).getAsJsonObject();
                pets = JsonParser.parseString(RepoItems.neuRepoFile("constants/pets.json")).getAsJsonObject();
            } catch (Exception e) {
                System.err.println("[Sky2M] Profile Viewer couldn't load NEU's level tables: " + e.getMessage());
            } finally {
                loading = false;
            }
        });
    }

    static JsonObject leveling() {
        return leveling;
    }

    static JsonObject pets() {
        return pets;
    }
}
