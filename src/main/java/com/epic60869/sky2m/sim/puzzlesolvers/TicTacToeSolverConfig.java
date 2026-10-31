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

/** Persisted Tic Tac Toe Solver settings - see {@link TicTacToeSolverFeature}. Ships disabled. */
public final class TicTacToeSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("sky2m-tictactoesolver.json");

    private static TicTacToeSolverConfig instance;

    private boolean enabled = false;
    private boolean showPrediction = false;
    /** Highlight style (killer560, 2026-09-21: "make an option again for fill or outline"). */
    private boolean fill = false;

    private TicTacToeSolverConfig() {
    }

    public static TicTacToeSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TicTacToeSolverConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            TicTacToeSolverConfig cfg = new TicTacToeSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showPrediction = ConfigJson.getBool(obj, "showPrediction", false);
            cfg.fill = ConfigJson.getBool(obj, "fill", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new TicTacToeSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showPrediction", showPrediction);
            obj.addProperty("fill", fill);
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

    public boolean isFill() {
        return fill;
    }

    public void setFill(boolean fill) {
        this.fill = fill;
    }

    public boolean isShowPrediction() {
        return showPrediction;
    }

    public void setShowPrediction(boolean showPrediction) {
        this.showPrediction = showPrediction;
    }
}
