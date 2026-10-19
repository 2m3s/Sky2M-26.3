package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Party Finder auto-kick requirements per floor (/s2 pfreq): for each of F1-F7 and M1-M7, whether it has its own, and
 * the slowest S+, the minimum Catacombs level, secrets (thousands) and magical power (0 = don't check). Saved in
 * config/sky2m/pf-requirements.json. Used when Party Finder Stats' Per-Floor Requirements is on.
 */
public final class PfRequirements {
    public static final class Req {
        public boolean enabled;
        public int maxPbSeconds;
        public int minCata;
        public int minSecretsK;
        public int minMagicalPower;
    }

    static final List<String> FLOORS = List.of("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Req> requirements;

    private PfRequirements() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("pfreq")
                    .executes(c -> Compat.queueOpenScreen(new PfRequirementsScreen()))));
            }
        });
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("pf-requirements.json");
    }

    /** Every floor's requirements (a row for each floor, off until set). */
    static synchronized Map<String, Req> all() {
        if (requirements == null) {
            Map<String, Req> loaded = null;
            try {
                if (Files.exists(file())) loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Map<String, Req>>() {}.getType());
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read pf-requirements.json: " + e.getMessage());
            }
            requirements = new LinkedHashMap<>();
            for (String floor : FLOORS) requirements.put(floor, loaded != null && loaded.get(floor) != null ? loaded.get(floor) : new Req());
        }
        return requirements;
    }

    /** The floor's own requirements ("M5"), or null when it has none turned on. */
    static Req forFloor(String floor) {
        Req req = all().get(floor);
        return req != null && req.enabled ? req : null;
    }

    static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(all()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save pf-requirements.json: " + e.getMessage());
        }
    }
}
