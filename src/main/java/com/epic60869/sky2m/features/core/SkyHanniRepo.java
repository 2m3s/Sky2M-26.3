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
 * Constants from SkyHanni's repo (https://github.com/hannibal002/SkyHanni-REPO), such as Garden.json (visitors) and
 * Enchants.json. A copy is bundled in the jar (assets/sky2m/skyhanni) so features work straight away and offline;
 * the live file is downloaded once per start and replaces it.
 */
public final class SkyHanniRepo {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SkyHanniRepo() {}

    /** Calls {@code consumer} with the bundled constant now, then with the downloaded one once it arrives. */
    public static void load(String name, Consumer<JsonObject> consumer) {
        JsonObject bundled = bundled(name);
        if (bundled != null) accept(name, bundled, consumer);
        RepoItems.runAsync(() -> {
            try {
                JsonObject live = JsonParser.parseString(RepoItems.skyHanniRepoFile("constants/" + name + ".json")).getAsJsonObject();
                accept(name, live, consumer);
            } catch (Exception e) {
                LOGGER.warn("[Sky2M] Couldn't download SkyHanni's {}.json, using the bundled copy: {}", name, e.toString());
            }
        });
    }

    private static void accept(String name, JsonObject json, Consumer<JsonObject> consumer) {
        try {
            consumer.accept(json);
        } catch (Exception e) {
            LOGGER.error("[Sky2M] Couldn't read SkyHanni's {}.json", name, e);
        }
    }

    private static JsonObject bundled(String name) {
        try (InputStream in = SkyHanniRepo.class.getResourceAsStream("/assets/sky2m/skyhanni/" + name + ".json")) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.error("[Sky2M] Couldn't read the bundled {}.json", name, e);
            return null;
        }
    }
}
