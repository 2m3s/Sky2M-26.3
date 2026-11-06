/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.livemap;

import com.epic60869.sky2m.sim.util.ConfigJson;
import com.epic60869.sky2m.sim.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What is left of the Dungeon Sim's own map settings.
 *
 * <p>The sim used to carry its own HUD dungeon map, peek key and Interactive Map, with their own settings saved in
 * {@code sky2m-livemap.json}. The sim now shows the mod's main Dungeon Map (Dungeons &gt; Dungeon Map), so those are
 * gone. Only what the sim's map designer ({@code SimMapEditorScreen}) still draws with is kept: its room label style,
 * label size and shadow, and three room colours. They are read from an existing {@code sky2m-livemap.json} (any other
 * key in it is ignored, so an old file loads) and never written; a missing file gives the defaults.
 */
public final class LiveMapConfig {

    private static final Path CONFIG_PATH = ModPaths.config("sky2m-livemap.json");

    /** 0 Off, 1 Checkmarks, 2 Secrets, 3 Room Name, 4 Room Name + Secrets. */
    public static final String[] ROOM_LABEL_NAMES = {"Off", "Checkmarks", "Secrets", "Room Name", "Room Name + Secrets"};

    private static LiveMapConfig instance;

    private int roomLabels = 1;
    private int colorEntrance = 0xFF00FF00;
    private int colorBlood = 0xFFFF0000;
    private int colorWitherDoor = 0xFF101010;
    private float fontScale = 1f;
    private boolean textShadow = false;

    private LiveMapConfig() {
    }

    public static LiveMapConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static LiveMapConfig load() {
        LiveMapConfig cfg = new LiveMapConfig();
        if (!Files.exists(CONFIG_PATH)) {
            return cfg;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            int labels = ConfigJson.getInt(obj, "roomLabels", 1);
            cfg.roomLabels = labels < 0 || labels >= ROOM_LABEL_NAMES.length ? 1 : labels;
            cfg.colorEntrance = ConfigJson.getInt(obj, "colorEntrance", cfg.colorEntrance);
            cfg.colorBlood = ConfigJson.getInt(obj, "colorBlood", cfg.colorBlood);
            cfg.colorWitherDoor = ConfigJson.getInt(obj, "colorWitherDoor", cfg.colorWitherDoor);
            cfg.fontScale = Math.max(0.5f, Math.min(3f, ConfigJson.getFloat(obj, "fontScale", 1f)));
            cfg.textShadow = ConfigJson.getBool(obj, "textShadow", false);
        } catch (Exception e) {
            // A broken old file: the defaults.
        }
        return cfg;
    }

    /**
     * Whether the room scan must run for the Dungeon Map: always in the Dungeon Sim, and on Hypixel when Dungeons &gt;
     * Dungeon Map uses a style drawn from the scan (Stella, Devonian).
     */
    public boolean isEnabled() {
        return com.epic60869.sky2m.sim.roomsim.SimState.isActive()
                || com.epic60869.sky2m.features.dungeons.mapstyle.DungeonMapStyles.needsScan();
    }

    public int getRoomLabels() {
        return roomLabels;
    }

    public int getColorEntrance() {
        return colorEntrance;
    }

    public int getColorBlood() {
        return colorBlood;
    }

    public int getColorWitherDoor() {
        return colorWitherDoor;
    }

    public float getFontScale() {
        return fontScale;
    }

    public boolean isTextShadow() {
        return textShadow;
    }
}
