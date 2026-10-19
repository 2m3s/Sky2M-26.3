package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.hunting.IslandGraph;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which slayer the place you're standing in is for, ported from SkyHanni's SlayerApi.checkTypeForCurrentArea: the
 * area comes from SkyHanni's island graph (the nearest area node to you), so a slayer's tracker counts exactly where
 * SkyHanni's does. Before an island's graph has loaded, the scoreboard's location is used instead.
 * With SkyHanni's defaults: the Graveyard counts for Revenant, the Dragon's Nest doesn't count for Voidgloom, and the
 * rest of the End (no named area) does.
 */
public final class SlayerArea {
    /** The slayer name SkyHanni's drop lists use ("Revenant Horror"...), per island graph area. */
    private static final Map<String, String> AREAS = Map.ofEntries(
        Map.entry("Graveyard", "Revenant Horror"),
        Map.entry("Revenant Cave", "Revenant Horror"),
        Map.entry("Crypts", "Revenant Horror"),
        Map.entry("Spider Mound", "Tarantula Broodfather"),
        Map.entry("Arachne's Burrow", "Tarantula Broodfather"),
        Map.entry("Arachne's Sanctuary", "Tarantula Broodfather"),
        Map.entry("Burning Desert", "Tarantula Broodfather"),
        Map.entry("Ruins", "Sven Packmaster"),
        Map.entry("Howling Cave", "Sven Packmaster"),
        Map.entry("Soul Cave", "Sven Packmaster"),
        Map.entry("Spirit Cave", "Sven Packmaster"),
        Map.entry("Void Sepulture", "Voidgloom Seraph"),
        Map.entry("Zealot Bruiser Hideout", "Voidgloom Seraph"),
        Map.entry("Stronghold", "Inferno Demonlord"),
        Map.entry("The Wasteland", "Inferno Demonlord"),
        Map.entry("Smoldering Tomb", "Inferno Demonlord"),
        Map.entry("Stillgore Château", "Riftstalker Bloodfiend"),
        Map.entry("Oubliette", "Riftstalker Bloodfiend"));

    /** The islands slayers are done on -> SkyHanni's island graph for them. */
    private static final Map<String, String> GRAPHS = Map.of(
        "Hub", "HUB",
        "Spider's Den", "SPIDER_DEN",
        "The Park", "THE_PARK",
        "The End", "THE_END",
        "Crimson Isle", "CRIMSON_ISLE",
        "The Rift", "THE_RIFT");

    private static boolean loaded;

    private SlayerArea() {}

    /** Loads the slayer islands' graphs (bundled, else SkyHanni's live repo) once. */
    public static void init() {
        if (loaded) return;
        loaded = true;
        GRAPHS.values().forEach(IslandGraph::load);
    }

    /** SkyHanni's name for the area you're in: its island graph's, else the scoreboard's location. */
    static String area() {
        Minecraft mc = Minecraft.getInstance();
        String graphName = GRAPHS.get(Sky2MLocation.area());
        IslandGraph graph = graphName == null ? null : IslandGraph.get(graphName);
        if (graph != null && mc.player != null) return graph.areaAt(mc.player.position());
        return Sky2MLocation.location();
    }

    /** The area, and whether it came from the island graph or the scoreboard (the graph hasn't loaded), for the log. */
    public static String debugArea() {
        String graphName = GRAPHS.get(Sky2MLocation.area());
        boolean graph = graphName != null && IslandGraph.get(graphName) != null;
        return area() + (graph ? "" : " (scoreboard: " + (graphName == null ? "not a slayer island" : "map not loaded") + ")");
    }

    /** The slayer this area is for ("Revenant Horror"...), or null. */
    public static String slayerHere() {
        String area = area();
        String slayer = AREAS.get(area);
        if (slayer != null) return slayer;
        // SkyHanni's voidgloomInNoArea (on by default): anywhere in the End outside its named areas.
        if (Sky2MLocation.area().equals("The End") && IslandGraph.NO_AREA.equals(area)) return "Voidgloom Seraph";
        return null;
    }

    /**
     * The slayer whose quest you have ("Revenant Horror"..., SkyHanni's SlayerType names), or null: from the sidebar,
     * else its team lines (a scoreboard mod such as SkyHanni's custom scoreboard can take the sidebar objective away),
     * else the tab list (its Slayer widget), as SkyHanni falls back to. Letters only: Hypixel's padding symbols (some,
     * like ⚽, survive the scoreboard cleanup) can land inside the boss's name.
     */
    public static String questSlayer() {
        String quest = questSlayerIn(Sky2MLocation.scoreboard());
        if (quest == null) quest = questSlayerIn(Sky2MLocation.teamLines());
        if (quest == null) quest = questSlayerIn(tabLines());
        return quest;
    }

    /** Every tab list line (its entries aren't in display order, and no player name holds a slayer boss's name). */
    private static List<String> tabLines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return List.of();
        List<String> out = new ArrayList<>();
        for (net.minecraft.client.multiplayer.PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            String text = com.epic60869.sky2m.sim.util.TabText.plain(info);
            if (text != null && !text.isBlank()) out.add(text);
        }
        return out;
    }

    private static String questSlayerIn(List<String> lines) {
        for (String raw : lines) {
            String line = LETTERS_ONLY.matcher(raw).replaceAll("");
            if (line.contains("RevenantHorror") || line.contains("AtonedHorror")) return "Revenant Horror";
            if (line.contains("TarantulaBroodfather") || line.contains("ConjoinedBrood")) return "Tarantula Broodfather";
            if (line.contains("SvenPackmaster")) return "Sven Packmaster";
            if (line.contains("VoidgloomSeraph")) return "Voidgloom Seraph";
            if (line.contains("InfernoDemonlord")) return "Inferno Demonlord";
            if (line.contains("Bloodfiend")) return "Riftstalker Bloodfiend";
        }
        return null;
    }

    private static final Pattern LETTERS_ONLY = Pattern.compile("[^A-Za-z]");
}
