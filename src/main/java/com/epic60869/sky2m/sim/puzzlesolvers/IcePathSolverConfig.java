/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.puzzlesolvers;

import com.epic60869.sky2m.sim.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.epic60869.sky2m.sim.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Ice Path (silverfish) Solver settings - see {@link IcePathSolverFeature}. Ships disabled. */
public final class IcePathSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("sky2m-icepathsolver.json");

    private static IcePathSolverConfig instance;

    private boolean enabled = false;
    private boolean showNextBox = true;

    private IcePathSolverConfig() {
    }

    public static IcePathSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new IcePathSolverConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            IcePathSolverConfig cfg = new IcePathSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showNextBox = ConfigJson.getBool(obj, "showNextBox", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new IcePathSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showNextBox", showNextBox);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return com.epic60869.sky2m.sim.roomsim.SimState.isActive(); // Sky2M: only inside the Dungeon Sim
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowNextBox() {
        return showNextBox;
    }

    public void setShowNextBox(boolean showNextBox) {
        this.showNextBox = showNextBox;
    }
}
