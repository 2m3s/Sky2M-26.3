package com.epic60869.sky2m.features.dungeons.party;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod Conflict Warnings, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod,
 * interop/ModConflictWarnings): once a session, a few seconds after joining a server, a chat line for each installed
 * mod that does the same job as a Sky2M feature you have on, since one will draw over or replace the other. It only
 * reads the other mods' config files, never writes them.
 */
public final class ModConflictWarnings {
    private static boolean checked;
    private static int countdown = -1;

    private ModConflictWarnings() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            if (!checked) countdown = 100;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (checked || countdown < 0 || --countdown > 0) return;
            countdown = -1;
            if (mc.player == null) return;
            checked = true;
            try {
                for (String warning : warnings()) {
                    Sky2MAlerts.chat(Component.literal(warning).withStyle(ChatFormatting.YELLOW));
                }
            } catch (RuntimeException ignored) {
                // A warning must never break joining.
            }
        });
    }

    private static List<String> warnings() {
        List<String> out = new ArrayList<>();
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || !c.dungeons.partyData.conflictWarnings) return out;
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded("skyblocker") && c.dungeons.f7.terminals.enabled && skyblockerTerminalSolver()) {
            out.add("Skyblocker's terminal solver is on too and draws over Sky2M's. Turn one of them off.");
        }
        if (loader.isModLoaded("customscoreboard") && c.gui.customScoreboard.enabled) {
            out.add("The Custom Scoreboard mod is installed and Sky2M's Custom Scoreboard is on: two sidebars will draw. Turn one of them off.");
        }
        if (loader.isModLoaded("skyblock-item-list") && c.inventory.itemList.enabled) {
            out.add("SkyBlock Item List is installed and Sky2M's Item List is on: both panels will show. Turn one of them off.");
        }
        if (loader.isModLoaded("skyhanni") && c.gui.customScoreboard.enabled && skyhanniScoreboard()) {
            out.add("SkyHanni's Custom Scoreboard is on and so is Sky2M's. Turn one of them off.");
        }
        return out;
    }

    private static boolean skyblockerTerminalSolver() {
        JsonObject terminals = child(child(read(FabricLoader.getInstance().getConfigDir().resolve("skyblocker.json")), "dungeons"), "terminals");
        if (terminals == null) return false;
        for (String key : new String[]{"solveColor", "solveSameColor", "solveOrder", "solveStartsWith"}) if (bool(terminals, key)) return true;
        return false;
    }

    private static boolean skyhanniScoreboard() {
        JsonObject root = read(FabricLoader.getInstance().getConfigDir().resolve("skyhanni").resolve("config.json"));
        JsonObject board = child(child(root, "gui"), "customScoreboard");
        return board != null && bool(board, "enabled");
    }

    private static JsonObject read(Path path) {
        try {
            if (!Files.exists(path)) return null;
            JsonElement e = JsonParser.parseString(Files.readString(path));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject child(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static boolean bool(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
