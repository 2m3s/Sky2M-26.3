/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560; see THIRD_PARTY_NOTICES.md.
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

/** Persisted Weirdos Solver settings - see {@link WeirdosSolverFeature}'s class doc for the real
 *  Odin-ported dialogue-matching logic this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class WeirdosSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("sky2m-weirdossolver.json");

    private static WeirdosSolverConfig instance;

    private boolean enabled = false;
    private boolean showWrongChests = true;

    private WeirdosSolverConfig() {
    }

    public static WeirdosSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new WeirdosSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            WeirdosSolverConfig cfg = new WeirdosSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showWrongChests = ConfigJson.getBool(obj, "showWrongChests", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new WeirdosSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showWrongChests", showWrongChests);
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

    public boolean isShowWrongChests() {
        return showWrongChests;
    }

    public void setShowWrongChests(boolean showWrongChests) {
        this.showWrongChests = showWrongChests;
    }
}
