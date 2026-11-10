package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.custom.RepoItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Data from Sky2M's own repo (https://github.com/2m3s/Sky2M-REPO), so Hypixel wording, patterns and values can be fixed
 * without a new jar. A copy is bundled in the jar (assets/sky2m/repo) for the first start and offline use; the live
 * file replaces it once downloaded (cached on disk and re-checked after 12 hours, see {@link RepoItems}).
 *
 * <p>Each file has a {@code formatVersion}. A file newer than this jar understands is ignored, so the repo can change
 * a file's layout for newer jars without breaking older ones.
 */
public final class Sky2MRepo {
    /** The highest file layout this jar reads. */
    public static final int FORMAT_VERSION = 1;
    private static final Logger LOGGER = LogUtils.getLogger();

    private Sky2MRepo() {}

    /** Calls {@code consumer} with the bundled constant now, then with the downloaded one once it arrives. */
    public static void load(String name, Consumer<JsonObject> consumer) {
        JsonObject bundled = bundled(name);
        if (bundled != null) accept(name, bundled, consumer, "bundled");
        RepoItems.runAsync(() -> {
            try {
                JsonObject live = JsonParser.parseString(RepoItems.sky2mRepoFile("constants/" + name + ".json")).getAsJsonObject();
                accept(name, live, consumer, "repo");
            } catch (Exception e) {
                LOGGER.warn("[Sky2M] Couldn't download Sky2M-REPO's {}.json, using the bundled copy: {}", name, e.toString());
            }
        });
    }

    private static void accept(String name, JsonObject json, Consumer<JsonObject> consumer, String source) {
        int format = json.has("formatVersion") ? json.get("formatVersion").getAsInt() : 1;
        if (format > FORMAT_VERSION) {
            LOGGER.info("[Sky2M] Skipping the {} {}.json: it is format {}, this version reads up to {}", source, name, format, FORMAT_VERSION);
            return;
        }
        try {
            consumer.accept(json);
        } catch (Exception e) {
            LOGGER.error("[Sky2M] Couldn't read the {} {}.json", source, name, e);
        }
    }

    /** The copy of a constant bundled in the jar, or null. */
    public static JsonObject bundled(String name) {
        try (InputStream in = Sky2MRepo.class.getResourceAsStream("/assets/sky2m/repo/constants/" + name + ".json")) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.error("[Sky2M] Couldn't read the bundled {}.json", name, e);
            return null;
        }
    }
}
