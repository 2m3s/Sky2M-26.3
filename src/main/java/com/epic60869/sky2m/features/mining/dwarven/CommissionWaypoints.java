package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.hunting.IslandGraph;
import com.epic60869.sky2m.hud.Sky2MCommissionHud;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Commission and Emissary Waypoints: in the Dwarven Mines, a beam on where each unfinished commission is done, and on
 * the nearest Emissary once one is DONE. The spots are SkyHanni's Dwarven Mines island graph's (its area, mob and
 * {@code mines_emissary} nodes), the nearest node of the right name to you; the commissions are the tab list's, as the
 * Commissions HUD reads them.
 */
public final class CommissionWaypoints {
    private static final String GRAPH = "DWARVEN_MINES";
    /** Commission name words -> the graph node name that marks where it's done. */
    private static final List<String[]> TARGETS = List.of(
        new String[]{"lava springs", "Lava Springs"},
        new String[]{"royal mines", "Royal Mines"},
        new String[]{"cliffside veins", "Cliffside Veins"},
        new String[]{"rampart's quarry", "Rampart's Quarry"},
        new String[]{"upper mines", "Upper Mines"},
        new String[]{"goblin", "Goblin Burrows"},
        new String[]{"glacite walker", "Glacite Walker"},
        new String[]{"treasure hoarder", "Treasure Hoarder"});
    private static boolean loaded;

    private CommissionWaypoints() {}

    private static Sky2MConfig.MiningCommissions config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.commissions;
    }

    /** Whether the commission list should be kept up to date for the waypoints (the HUD may be off). */
    public static boolean wantsCommissions() {
        Sky2MConfig.MiningCommissions c = config();
        return c != null && (c.waypoints || c.emissaryWaypoints) && Sky2MLocation.inDwarvenMines();
    }

    public static void init() {
        Sky2MWorldRender.register(collector -> {
            if (!wantsCommissions()) return;
            if (!loaded) {
                loaded = true;
                IslandGraph.load(GRAPH);
            }
            IslandGraph graph = IslandGraph.get(GRAPH);
            Minecraft mc = Minecraft.getInstance();
            if (graph == null || mc.player == null) return;
            Vec3 me = mc.player.position();
            Sky2MConfig.MiningCommissions c = config();
            boolean anyDone = false;
            for (Map.Entry<String, Boolean> commission : Sky2MCommissionHud.current().entrySet()) {
                if (commission.getValue()) {
                    anyDone = true;
                    continue;
                }
                if (!c.waypoints) continue;
                String target = targetOf(commission.getKey());
                Vec3 at = target == null ? null : nearestNamed(graph, target, null, me);
                if (at != null) mark(collector, at, commission.getKey(), 0x55FFFF, me);
            }
            if (anyDone && c.emissaryWaypoints) {
                Vec3 best = null;
                String name = null;
                for (int i : graph.withTag("mines_emissary")) {
                    if (best == null || graph.pos[i].distanceToSqr(me) < best.distanceToSqr(me)) {
                        best = graph.pos[i];
                        name = graph.names[i];
                    }
                }
                if (best != null) mark(collector, best, "Claim: " + name, 0x55FF55, me);
            }
        });
    }

    /** The graph node name for a commission ("Lava Springs Mithril" -> "Lava Springs"), or null for one done anywhere. */
    public static String targetOf(String commission) {
        String lower = commission.toLowerCase(Locale.ROOT);
        for (String[] t : TARGETS) if (lower.contains(t[0])) return t[1];
        return null;
    }

    private static Vec3 nearestNamed(IslandGraph graph, String name, String tag, Vec3 me) {
        Vec3 best = null;
        for (int i = 0; i < graph.pos.length; i++) {
            if (!name.equals(graph.names[i]) || tag != null && !graph.tags.get(i).contains(tag)) continue;
            if (best == null || graph.pos[i].distanceToSqr(me) < best.distanceToSqr(me)) best = graph.pos[i];
        }
        return best;
    }

    private static void mark(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector, Vec3 at, String label,
                             int colour, Vec3 me) {
        BlockPos pos = BlockPos.containing(at.x, at.y - 1, at.z);
        float[] rgb = {(colour >> 16 & 255) / 255f, (colour >> 8 & 255) / 255f, (colour & 255) / 255f};
        collector.submitFilledBoxWithBeaconBeam(pos, rgb, 0.5f, true);
        Vec3 text = Vec3.atCenterOf(pos).add(0, 1.5, 0);
        collector.submitText(Component.literal(label).withColor(colour), text, 2f, true);
        collector.submitText(Component.literal(Math.round(me.distanceTo(at)) + "m").withStyle(ChatFormatting.YELLOW),
            text.add(0, -0.6, 0), 1.5f, true);
    }
}
