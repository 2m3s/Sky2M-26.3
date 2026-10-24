package com.epic60869.sky2m.features.garden.helpers;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Greenhouse solvers from SkyHanni (LGPL-2.1, https://github.com/hannibal002/SkyHanni): the DNA Analyzer solver
 * (DnaAnalyzerSolver) and the Phantomleaf solver (PhantomleafSolver). Both only show where to look or click; the
 * clicking is yours.
 */
public final class GreenhouseHelpers {
    private static final String DNA_HUD = "garden_dna_solver";
    private static final int ROWS = 4, UNREACHABLE = 1000;
    private static final int RED = 0, GREEN = 1, BLUE = 2, YELLOW = 3;
    private static final List<int[]> PERMUTATIONS = new ArrayList<>();

    private static volatile boolean allowEnds = true;
    private static List<int[]> swaps = List.of();
    private static int swapsNeeded = -1;
    private static boolean fakeInventory;
    private static String boardKey = "";

    // Phantomleaf
    private static final int HYPIXEL_VOLUME_SCALING = 30;
    private static final double MUTATION_Y = 74.0;
    private static final float[] GREEN_COLOUR = {0f, 1f, 0f}, YELLOW_COLOUR = {1f, 1f, 0f};
    private static boolean searching;
    private static Vec3 lastPos;
    private static final List<BlockPos> candidates = new ArrayList<>();

    static {
        permute(new int[]{0, 1, 2, 3}, 0);
    }

    private GreenhouseHelpers() {}

    public static void init() {
        SkyHanniRepo.load("Garden", json -> {
            if (json.has("dna_solver_allows_ends")) allowEnds = json.get("dna_solver_allows_ends").getAsBoolean();
        });
        Sky2MHuds.setting(DNA_HUD, () -> {
            HelperConfig c = HelperUtil.cfg();
            return c != null && c.greenhouse.dnaSolver;
        });
        Sky2MHuds.register(DNA_HUD, "DNA Analyzer Solver", () -> false,
            () -> swapsNeeded < 0 ? List.of() : List.of(SbcItems.parseLegacy("§eDNA Analyzer: §a" + swapsNeeded + " swaps needed")),
            List.of(SbcItems.parseLegacy("§eDNA Analyzer: §a12 swaps needed")), 5, 90);

        Sky2MChat.onChat(message -> {
            HelperConfig c = HelperUtil.cfg();
            if (c == null || !c.greenhouse.phantomleaf || !Sky2MLocation.inGarden()) return;
            String text = message.text();
            if (text.contains("Phantomleaf: Poof! Try and find me!")) searching = true;
            else if (text.contains("Phantomleaf: You found me!") || text.contains("Phantomleaf: That's not me! Better luck next time!")) reset();
        });
        Sky2MLocation.onAreaChange(area -> reset());
        Sky2MWorldRender.register(collector -> {
            HelperConfig c = HelperUtil.cfg();
            if (c == null || !c.greenhouse.phantomleaf || !searching || candidates.isEmpty()) return;
            float[] colour = candidates.size() > 1 ? YELLOW_COLOUR : GREEN_COLOUR;
            for (BlockPos pos : new ArrayList<>(candidates)) collector.submitFilledBoxWithBeaconBeam(pos, colour, 0.5f, true);
        });
    }

    // ------------------------------------------------------------------------------------------ DNA Analyzer

    static void updateMenu(AbstractContainerScreen<?> screen, String title) {
        HelperConfig c = HelperUtil.cfg();
        if (c == null || !c.greenhouse.dnaSolver || !title.endsWith(" DNA") || !Sky2MLocation.inGarden()) {
            swapsNeeded = -1;
            swaps = List.of();
            boardKey = "";
            fakeInventory = false;
            return;
        }
        List<Slot> slots = screen.getMenu().slots;
        int[][] board = new int[9][4];
        fakeInventory = false;
        StringBuilder key = new StringBuilder();
        for (int i = 9; i <= 44; i++) {
            if (i >= slots.size()) return;
            int colour = colourOf(slots.get(i).getItem());
            if (colour < 0) {
                fakeInventory = true;
                swapsNeeded = -1;
                swaps = List.of();
                return;
            }
            board[i % 9][i / 9 - 1] = colour;
            key.append(colour);
        }
        // Every colour must be in every column, else the menu hasn't fully loaded.
        for (int[] column : board) {
            boolean[] seen = new boolean[4];
            for (int v : column) seen[v] = true;
            for (boolean s : seen) if (!s) return;
        }
        if (key.toString().equals(boardKey)) return;
        boardKey = key.toString();
        Object[] solved = solve(board, allowEnds);
        swapsNeeded = (Integer) solved[0];
        @SuppressWarnings("unchecked") List<int[]> list = (List<int[]>) solved[1];
        swaps = list;
        if (swapsNeeded >= UNREACHABLE) swapsNeeded = -1;
    }

    static void drawMenu(AbstractContainerScreen<?> screen, String title, GuiGraphicsExtractor graphics) {
        HelperConfig c = HelperUtil.cfg();
        if (c == null || !c.greenhouse.dnaSolver || !title.endsWith(" DNA") || fakeInventory || swaps.isEmpty()) return;
        // SkyHanni highlights the last swap it found.
        int[] swap = swaps.get(swaps.size() - 1);
        List<Slot> slots = screen.getMenu().slots;
        for (int row : new int[]{swap[1], swap[2]}) {
            int index = swap[0] + (row + 1) * 9;
            if (index < slots.size()) {
                GardenHelpers.fillSlot(graphics, screen, slots.get(index), 0x8055FF55);
                GardenHelpers.outlineSlot(graphics, screen, slots.get(index), 0xFF55FF55);
            }
        }
        if (swapsNeeded >= 0) {
            List<Component> lines = List.of(SbcItems.parseLegacy("§eDNA Analyzer: §a" + swapsNeeded + " swaps needed"));
            int[] at = Sky2MHuds.screenPosition(DNA_HUD, lines);
            Sky2MHuds.Placement p = Sky2MHuds.placement(DNA_HUD);
            Sky2MHuds.render(graphics, lines, at[0], at[1], p.scale, p.background);
        }
    }

    private static int colourOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !HelperUtil.name(stack).startsWith("DNA")) return -1;
        for (Component part : stack.getHoverName().toFlatList(Style.EMPTY)) {
            if (part.getString().isEmpty()) continue;
            TextColor colour = part.getStyle().getColor();
            if (colour == null) return -1;
            return switch (colour.getValue()) {
                case 0xFF5555 -> RED;
                case 0xFFFF55 -> YELLOW;
                case 0x5555FF -> BLUE;
                case 0x55FF55 -> GREEN;
                default -> -1;
            };
        }
        return -1;
    }

    private static void permute(int[] a, int l) {
        if (l == ROWS) {
            PERMUTATIONS.add(a.clone());
            return;
        }
        for (int i = l; i < ROWS; i++) {
            int t = a[l]; a[l] = a[i]; a[i] = t;
            permute(a, l + 1);
            t = a[l]; a[l] = a[i]; a[i] = t;
        }
    }

    private static boolean canConnect(int[] a, int[] b) {
        for (int r = 0; r < ROWS; r++) {
            int v = a[r];
            if (b[r] == v) continue;
            if (r > 0 && b[r - 1] == v) continue;
            if (r < ROWS - 1 && b[r + 1] == v) continue;
            return false;
        }
        return true;
    }

    private static int[] apply(int[] column, int[] permutation) {
        int[] out = new int[ROWS];
        for (int i = 0; i < ROWS; i++) out[i] = column[permutation[i]];
        return out;
    }

    /** Fewest swaps within a column to turn {@code from} into {@code to}: {cost, then row pairs}. */
    private static Object[] columnSwaps(int[] from, int[] to) {
        int[] pos = new int[ROWS];
        for (int i = 0; i < ROWS; i++) {
            int at = 0;
            for (int j = 0; j < ROWS; j++) if (from[j] == to[i]) at = j;
            pos[at] = i;
        }
        boolean[] visited = new boolean[ROWS];
        List<int[]> out = new ArrayList<>();
        int cost = 0;
        for (int i = 0; i < ROWS; i++) {
            if (visited[i]) continue;
            int cur = i;
            List<Integer> cycle = new ArrayList<>();
            while (!visited[cur]) {
                visited[cur] = true;
                cycle.add(cur);
                cur = pos[cur];
            }
            if (cycle.size() > 1) {
                cost += cycle.size() - 1;
                for (int k = 1; k < cycle.size(); k++) out.add(new int[]{cycle.get(0), cycle.get(k)});
            }
        }
        return new Object[]{cost, out};
    }

    /** Port of SkyHanni's DnaBoard.solveBoard: {swaps needed, list of {column, rowA, rowB}}. */
    @SuppressWarnings("unchecked")
    private static Object[] solve(int[][] board, boolean allowEnds) {
        int first = allowEnds ? 0 : 1;
        int last = allowEnds ? board.length - 1 : board.length - 2;
        int count = last - first + 1;
        int p = PERMUTATIONS.size();
        int[][] dp = new int[count][p], parent = new int[count][p], cost = new int[count][p];
        List<int[]>[][] swapMap = new List[count][p];
        for (int i = 0; i < count; i++) {
            java.util.Arrays.fill(dp[i], UNREACHABLE);
            java.util.Arrays.fill(parent[i], -1);
            int[] column = board[first + i];
            for (int q = 0; q < p; q++) {
                Object[] r = columnSwaps(column, apply(column, PERMUTATIONS.get(q)));
                cost[i][q] = (Integer) r[0];
                swapMap[i][q] = (List<int[]>) r[1];
            }
        }
        for (int q = 0; q < p; q++) {
            int[] perm = apply(board[first], PERMUTATIONS.get(q));
            if (!allowEnds && !canConnect(board[0], perm)) continue;
            dp[0][q] = cost[0][q];
        }
        for (int i = 1; i < count; i++) {
            for (int q = 0; q < p; q++) {
                int[] cur = apply(board[first + i], PERMUTATIONS.get(q));
                for (int r = 0; r < p; r++) {
                    if (dp[i - 1][r] == UNREACHABLE) continue;
                    int[] prev = apply(board[first + i - 1], PERMUTATIONS.get(r));
                    if (!canConnect(prev, cur)) continue;
                    int total = dp[i - 1][r] + cost[i][q];
                    if (total < dp[i][q]) {
                        dp[i][q] = total;
                        parent[i][q] = r;
                    }
                }
            }
        }
        int best = UNREACHABLE, lastPerm = -1;
        for (int q = 0; q < p; q++) {
            int[] perm = apply(board[last], PERMUTATIONS.get(q));
            if (!allowEnds && !canConnect(perm, board[board.length - 1])) continue;
            if (dp[count - 1][q] < best) {
                best = dp[count - 1][q];
                lastPerm = q;
            }
        }
        if (lastPerm == -1) return new Object[]{UNREACHABLE, new ArrayList<int[]>()};
        List<int[]> result = new ArrayList<>();
        int i = count - 1, cur = lastPerm;
        while (i >= 0) {
            for (int[] s : swapMap[i][cur]) result.add(new int[]{first + i, s[0], s[1]});
            cur = parent[i][cur];
            i--;
        }
        return new Object[]{best, result};
    }

    // ------------------------------------------------------------------------------------------ Phantomleaf

    /**
     * Called for every sound. A note at pitch 0.61 to 0.62 has the volume 1 - distance / 30, with the distance from the
     * hiding spot, so standing still for two notes lets us find every block at that distance (SkyHanni's formula).
     */
    public static void onSound(String name, float volume, float pitch) {
        HelperConfig c = HelperUtil.cfg();
        if (c == null || !c.greenhouse.phantomleaf || !searching || !Sky2MLocation.inGarden()) return;
        if (pitch < 0.61f || pitch > 0.62f || !name.equals("block.note_block.basedrum")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 pos = mc.player.position();
        if (lastPos != null && lastPos.x == pos.x && lastPos.z == pos.z) {
            double radius = HYPIXEL_VOLUME_SCALING * (1.0 - volume);
            candidates.clear();
            double cx = Math.floor(pos.x) + 0.5, cz = Math.floor(pos.z) + 0.5;
            for (int dx = -HYPIXEL_VOLUME_SCALING - 1; dx <= HYPIXEL_VOLUME_SCALING + 1; dx++) {
                for (int dz = -HYPIXEL_VOLUME_SCALING - 1; dz <= HYPIXEL_VOLUME_SCALING + 1; dz++) {
                    double d = Math.hypot(cx + dx - pos.x, cz + dz - pos.z);
                    if (Math.abs(d - radius) < 0.001) {
                        candidates.add(new BlockPos((int) Math.floor(cx + dx), (int) MUTATION_Y, (int) Math.floor(cz + dz)));
                    }
                }
            }
            if (candidates.isEmpty()) Sky2MAlerts.chat(Component.literal("§ePhantomleaf: No solutions found. Try moving a little."));
            else if (candidates.size() > 1) Sky2MAlerts.chat(Component.literal("§ePhantomleaf: Multiple candidates found. Move a little to recalculate."));
        }
        lastPos = pos;
    }

    private static void reset() {
        searching = false;
        lastPos = null;
        candidates.clear();
    }
}
