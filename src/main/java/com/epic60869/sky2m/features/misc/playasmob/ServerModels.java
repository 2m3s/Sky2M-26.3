package com.epic60869.sky2m.features.misc.playasmob;

import com.epic60869.sky2m.features.sbc.SbcCosmetics;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The models Sky2M staff give out, downloaded from the Sky2M server once per version into config/sky2m/model-cache
 * ({@code <id>-v<version>.<ext>}) and checked against the SHA-256 the server sent. Models only come from the server:
 * the cosmetics table names them, so nobody can make another player's game load a file of their choosing.
 */
public final class ServerModels {
    private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("model-cache");
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final Set<String> EXTS = Set.of("glb", "vrm", "bbmodel", "gltf", "stl");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    /** Downloads that failed this session, by file name, with when, so they are tried again only after a while. */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    private ServerModels() {
    }

    /** The model's file once it is downloaded and checked, or null (starting the download the first time). */
    public static Path file(SbcCosmetics.ModelRef ref) {
        String ext = ref.ext().toLowerCase(Locale.ROOT);
        String id = ref.id().replaceAll("[^a-z0-9_]", "_");
        if (!EXTS.contains(ext) || id.isEmpty()) return null;
        String name = id + "-v" + ref.version() + "." + ext;
        Path file = CACHE.resolve(name);
        if (Files.isRegularFile(file)) return file;
        Long failedAt = FAILED.get(name);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 5 * 60_000) return null;
        if (!LOADING.add(name)) return null;
        Thread thread = new Thread(() -> download(ref, file, name), "Sky2M model download");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    private static void download(SbcCosmetics.ModelRef ref, Path file, String name) {
        try {
            String url = ref.url().startsWith("/") ? "https://shadowisabot.com" + ref.url() : ref.url();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Sky2M").GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() != 200 || body.length == 0 || body.length > MAX_BYTES) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ", " + body.length + " bytes");
            }
            if (!ref.hash().isBlank()) {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                if (!hash.equalsIgnoreCase(ref.hash())) throw new IllegalStateException("the download doesn't match its hash");
            }
            Files.createDirectories(CACHE);
            Path part = CACHE.resolve(name + ".part");
            Files.write(part, body);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            removeOlderVersions(name);
        } catch (Exception e) {
            FAILED.put(name, System.currentTimeMillis());
            CustomModels.LOGGER.warn("[Play as Mob] model {} download failed: {}", name, e.toString());
        } finally {
            LOADING.remove(name);
        }
    }

    /** Old versions of a model that was replaced on the website. */
    private static void removeOlderVersions(String keep) {
        String prefix = keep.substring(0, keep.lastIndexOf("-v") + 2);
        try (var files = Files.list(CACHE)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && !n.equals(keep);
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
