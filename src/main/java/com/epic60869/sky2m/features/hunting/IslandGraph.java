package com.epic60869.sky2m.features.hunting;

import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * SkyHanni's island graphs (SkyHanni-REPO constants/island_graphs, MIT), read for the islands hunting needs: tagged
 * spots (Shulker and Hideyho locations), the sub-area you're in (SkyHanni's graph area: the area node nearest along the
 * graph from your closest node) and a route along the graph to a target, drawn as a line, like SkyHanni's IslandGraphs
 * pathfinding (LGPL-2.1). Only one route is shown at a time.
 */
public final class IslandGraph {
    public static final String NO_AREA = "no_area";

    public final Vec3[] pos;
    public final String[] names;
    public final List<List<String>> tags;
    /** Neighbour indexes and distances, per node. */
    final int[][] edges;
    final double[][] weights;
    /** The area each node belongs to (precomputed: the nearest area node along the graph). */
    private final String[] area;

    private static final Map<String, IslandGraph> GRAPHS = new HashMap<>();

    private IslandGraph(JsonObject json) {
        int n = json.size();
        Map<String, Integer> index = new HashMap<>();
        int i = 0;
        for (String key : json.keySet()) index.put(key, i++);
        pos = new Vec3[n];
        names = new String[n];
        tags = new ArrayList<>(n);
        edges = new int[n][];
        weights = new double[n][];
        i = 0;
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            JsonObject node = e.getValue().getAsJsonObject();
            String[] p = node.get("Position").getAsString().split(":");
            pos[i] = new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            names[i] = node.has("Name") ? node.get("Name").getAsString() : null;
            List<String> t = new ArrayList<>();
            if (node.has("Tags")) node.getAsJsonArray("Tags").forEach(tag -> t.add(tag.getAsString()));
            tags.add(t);
            JsonObject neighbours = node.has("Neighbours") ? node.getAsJsonObject("Neighbours") : new JsonObject();
            List<Integer> to = new ArrayList<>();
            List<Double> w = new ArrayList<>();
            for (Map.Entry<String, JsonElement> nb : neighbours.entrySet()) {
                Integer j = index.get(nb.getKey());
                if (j == null) continue;
                to.add(j);
                w.add(nb.getValue().getAsDouble());
            }
            edges[i] = to.stream().mapToInt(Integer::intValue).toArray();
            weights[i] = w.stream().mapToDouble(Double::doubleValue).toArray();
            i++;
        }
        area = computeAreas();
    }

    /** Multi-source Dijkstra from every area node: each node gets the name of its nearest area node. */
    private String[] computeAreas() {
        String[] result = new String[pos.length];
        double[] dist = new double[pos.length];
        Arrays.fill(dist, Double.MAX_VALUE);
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        for (int i = 0; i < pos.length; i++) {
            if (names[i] != null && (tags.get(i).contains("area") || tags.get(i).contains("small_area"))) {
                dist[i] = 0;
                result[i] = names[i];
                queue.add(new double[]{i, 0});
            }
        }
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[0];
            if (top[1] > dist[u]) continue;
            for (int k = 0; k < edges[u].length; k++) {
                int v = edges[u][k];
                double d = dist[u] + weights[u][k];
                if (d < dist[v]) {
                    dist[v] = d;
                    result[v] = result[u];
                    queue.add(new double[]{v, d});
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------ loading

    /** Loads an island's graph (bundled, then the live repo copy). */
    public static void load(String island) {
        SkyHanniRepo.load("island_graphs/" + island, json -> {
            IslandGraph graph = new IslandGraph(json);
            Minecraft.getInstance().execute(() -> GRAPHS.put(island, graph));
        });
    }

    public static IslandGraph get(String island) {
        return GRAPHS.get(island);
    }

    // ------------------------------------------------------------------------------------------------ queries

    public int nearest(Vec3 to) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < pos.length; i++) {
            double d = pos[i].distanceToSqr(to);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** The area you're in, or {@link #NO_AREA}. */
    public String areaAt(Vec3 at) {
        int node = nearest(at);
        String name = node < 0 ? null : area[node];
        return name == null ? NO_AREA : name;
    }

    public List<Integer> withTag(String tag) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < pos.length; i++) if (tags.get(i).contains(tag)) out.add(i);
        return out;
    }

    /** Graph distances from a node to every node, with the previous node on each shortest path. */
    private double[] dijkstra(int from, int[] previous) {
        double[] dist = new double[pos.length];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(previous, -1);
        dist[from] = 0;
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        queue.add(new double[]{from, 0});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[0];
            if (top[1] > dist[u]) continue;
            for (int k = 0; k < edges[u].length; k++) {
                int v = edges[u][k];
                double d = dist[u] + weights[u][k];
                if (d < dist[v]) {
                    dist[v] = d;
                    previous[v] = u;
                    queue.add(new double[]{v, d});
                }
            }
        }
        return dist;
    }

    public List<Vec3> path(int from, int to) {
        int[] previous = new int[pos.length];
        double[] dist = dijkstra(from, previous);
        List<Vec3> out = new ArrayList<>();
        if (dist[to] == Double.MAX_VALUE) {
            out.add(pos[to]);
            return out;
        }
        for (int at = to; at != -1; at = previous[at]) out.addFirst(pos[at]);
        return out;
    }

    /**
     * An order to visit the targets in, starting near {@code start}: nearest neighbour by graph distance, then 2-opt,
     * like SkyHanni's NavigationUtils.getRoute (a travelling salesman approximation).
     */
    public List<Integer> route(List<Integer> targets, int start) {
        int n = targets.size();
        if (n <= 1) return new ArrayList<>(targets);
        int[] previous = new int[pos.length];
        double[][] d = new double[n + 1][n + 1];
        List<Integer> all = new ArrayList<>(targets);
        all.add(start);
        for (int a = 0; a <= n; a++) {
            double[] dist = dijkstra(all.get(a), previous);
            for (int b = 0; b <= n; b++) d[a][b] = dist[all.get(b)] == Double.MAX_VALUE ? 1e9 : dist[all.get(b)];
        }
        List<Integer> order = new ArrayList<>();
        boolean[] used = new boolean[n];
        int at = n;
        for (int step = 0; step < n; step++) {
            int best = -1;
            for (int c = 0; c < n; c++) if (!used[c] && (best < 0 || d[at][c] < d[at][best])) best = c;
            used[best] = true;
            order.add(best);
            at = best;
        }
        boolean improved = true;
        for (int iteration = 0; improved && iteration < 300; iteration++) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    int a = i == 0 ? n : order.get(i - 1), b = order.get(i), c = order.get(j);
                    int e = j + 1 < n ? order.get(j + 1) : -1;
                    double before = d[a][b] + (e < 0 ? 0 : d[c][e]);
                    double after = d[a][c] + (e < 0 ? 0 : d[b][e]);
                    if (after + 1e-6 < before) {
                        java.util.Collections.reverse(order.subList(i, j + 1));
                        improved = true;
                    }
                }
            }
        }
        List<Integer> out = new ArrayList<>(n);
        for (int index : order) out.add(targets.get(index));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ navigation

    /** The route being shown: to {@link #goal}, recalculated as you move, with a label and colour. */
    private static IslandGraph navGraph;
    private static int navGoal = -1;
    private static String navLabel;
    private static int navColour;
    private static BooleanSupplier navCondition;
    private static Runnable navFound;
    private static List<Vec3> navPath = List.of();
    private static int navFrom = -1;
    private static int ticks;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(IslandGraph::tick);
        Sky2MWorldRender.register(collector -> {
            if (navGraph == null || navPath.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            float[] rgb = {(navColour >> 16 & 0xFF) / 255f, (navColour >> 8 & 0xFF) / 255f, (navColour & 0xFF) / 255f};
            Vec3[] points = new Vec3[navPath.size() + 1];
            points[0] = mc.player.position().add(0, 0.2, 0);
            for (int i = 0; i < navPath.size(); i++) points[i + 1] = navPath.get(i).add(0.5, 0.2, 0.5);
            collector.submitLinesFromPoints(points, rgb, 1f, 3f, true);
            Vec3 goal = navGraph.pos[navGoal];
            collector.submitFilledBox(new AABB(goal.x, goal.y, goal.z, goal.x + 1, goal.y + 1, goal.z + 1), rgb, 0.35f, true);
            collector.submitText(Component.literal(navLabel), goal.add(0.5, 1.6, 0.5), 1.5f, true);
        });
    }

    /** Shows a route to a node. {@code onFound} runs once you're within 3 blocks; the route stops if the condition fails. */
    public static void navigate(IslandGraph graph, int goal, String label, int colour, BooleanSupplier condition, Runnable onFound) {
        navGraph = graph;
        navGoal = goal;
        navLabel = label;
        navColour = colour;
        navCondition = condition;
        navFound = onFound;
        navFrom = -1;
        navPath = List.of();
        ticks = 0;
    }

    public static void stopNavigation() {
        navGraph = null;
        navPath = List.of();
    }

    public static boolean navigating() {
        return navGraph != null;
    }

    private static void tick(Minecraft mc) {
        if (navGraph == null) return;
        if (mc.player == null || mc.level == null) {
            stopNavigation();
            return;
        }
        Vec3 goal = navGraph.pos[navGoal];
        if (mc.player.position().distanceToSqr(goal.add(0.5, 0, 0.5)) < 9) {
            Runnable found = navFound;
            Sky2MAlerts.chat(Component.literal("Navigation reached ").withStyle(ChatFormatting.YELLOW).append(Component.literal(navLabel)).append(Component.literal("!").withStyle(ChatFormatting.YELLOW)));
            stopNavigation();
            if (found != null) found.run();
            return;
        }
        if (!navCondition.getAsBoolean()) {
            Sky2MAlerts.chat(Component.literal("Navigation stopped!").withStyle(ChatFormatting.YELLOW));
            stopNavigation();
            return;
        }
        if (ticks++ % 10 != 0) return;
        int from = navGraph.nearest(mc.player.position());
        if (from == navFrom) return;
        navFrom = from;
        navPath = navGraph.path(from, navGoal);
    }
}
