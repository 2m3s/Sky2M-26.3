package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.events.CarnivalFruitDigging.Fruit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Picks the best next dig in Fruit Digging. Ported from SkyHanni's FruitDiggingSolver and FruitDiggingBelief
 * (https://github.com/hannibal002/SkyHanni, features/event/carnival/, LGPL-2.1).
 *
 * <p>It reads the board (known fruit, cells still to dig, fruit a bomb destroyed and the shovel clues), deals the
 * fruit left in the bag onto the unknown cells many times, keeps the deals that fit every clue, and scores each
 * diggable cell by expected points, bomb and rum risk, multiplier set-up and how much it would reveal.
 */
final class FruitDiggingSolver {
    static final int SIZE = 7;

    record CellInput(Fruit content, boolean diggable, boolean ghost, Integer mines, Fruit treasure, Fruit anchor) {}

    record Recommendation(int row, int col, String shovel, double expectedPoints, double pBomb, double pRum, int samples) {}

    private static final int TARGET_SAMPLES = 500;
    private static final int MAX_ATTEMPTS = 20_000;
    private static final double RISK_WEIGHT = 1.0;
    private static final double DESTRUCTION_WEIGHT = 0.10;
    private static final double SETUP_WEIGHT = 0.5;
    private static final double INFO_WEIGHT = 15.0;

    private static final int[][] NEIGHBOURS = new int[SIZE * SIZE][];

    static {
        for (int id = 0; id < SIZE * SIZE; id++) {
            int row = id / SIZE, col = id % SIZE;
            List<Integer> list = new ArrayList<>();
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) continue;
                    int r = row + dr, c = col + dc;
                    if (r >= 0 && r < SIZE && c >= 0 && c < SIZE) list.add(r * SIZE + c);
                }
            }
            NEIGHBOURS[id] = list.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    private FruitDiggingSolver() {}

    static Recommendation recommend(CellInput[][] grid, int digsUsed, double nextMultiplier, boolean coconutProtection, Random random) {
        return new Advisor(grid, digsUsed, nextMultiplier, coconutProtection, random).recommend();
    }

    /** What a dowsing clue ranks a fruit as: Apple is the lowest, the rest by points. */
    static int dowsingRank(Fruit fruit) {
        return fruit == Fruit.APPLE ? 0 : fruit.points();
    }

    private static final class Advisor {
        final Fruit[] known = new Fruit[SIZE * SIZE];
        final boolean[] diggable = new boolean[SIZE * SIZE];
        final boolean[] ghost = new boolean[SIZE * SIZE];
        final List<Integer> candidates = new ArrayList<>();
        final List<Integer> open = new ArrayList<>();
        final Set<Integer> openOnBoard = new HashSet<>();
        final int digsUsed;
        final double nextMultiplier;
        final boolean coconutProtection;
        int applesCollected;
        int cherriesCollected;
        final Map<Integer, Map<Fruit, Double>> beliefs;
        final int samples;

        Advisor(CellInput[][] grid, int digsUsed, double nextMultiplier, boolean coconutProtection, Random random) {
            this.digsUsed = digsUsed;
            this.nextMultiplier = nextMultiplier;
            this.coconutProtection = coconutProtection;
            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    int id = row * SIZE + col;
                    CellInput cell = grid[row][col];
                    Fruit content = cell.content() == null || cell.content() == Fruit.UNKNOWN || cell.content() == Fruit.NO_FRUIT ? null : cell.content();
                    known[id] = content;
                    diggable[id] = cell.diggable();
                    ghost[id] = cell.ghost() && content == null;
                    if (cell.diggable()) {
                        candidates.add(id);
                        if (content == null) {
                            open.add(id);
                            openOnBoard.add(id);
                        }
                    } else if (content == null) {
                        open.add(id);
                    }
                    if (content != null && !cell.diggable()) {
                        if (content == Fruit.APPLE) applesCollected++;
                        if (content == Fruit.CHERRY) cherriesCollected++;
                    }
                }
            }
            Belief belief = new Belief(open, remainingBag(), clues(grid), random);
            beliefs = belief.estimate();
            samples = belief.samples;
        }

        List<Fruit> remainingBag() {
            Map<Fruit, Integer> counts = new EnumMap<>(Fruit.class);
            for (Fruit f : Fruit.values()) if (f.inBag()) counts.put(f, f.count());
            for (Fruit f : known) if (f != null) counts.merge(f, -1, Integer::sum);
            List<Fruit> bag = new ArrayList<>();
            counts.forEach((f, n) -> {
                for (int i = 0; i < n; i++) bag.add(f);
            });
            // A bag that doesn't fit the open cells (a missed reveal) would make every deal wrong: pad or trim it.
            while (bag.size() < open.size()) bag.add(Fruit.BOMB);
            while (bag.size() > open.size()) bag.remove(bag.size() - 1);
            return bag;
        }

        List<Clue> clues(CellInput[][] grid) {
            List<Clue> clues = new ArrayList<>();
            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    int id = row * SIZE + col;
                    CellInput cell = grid[row][col];
                    List<Integer> hidden = new ArrayList<>();
                    for (int nb : NEIGHBOURS[id]) if (openOnBoard.contains(nb)) hidden.add(nb);
                    if (cell.mines() != null) clues.add(new Clue(ClueType.BOMBS, hidden, cell.mines(), null, false));
                    if (cell.treasure() != null) clues.add(orderClue(id, hidden, cell.treasure(), false));
                    if (cell.anchor() != null) clues.add(orderClue(id, hidden, cell.anchor(), true));
                    if (ghost[id]) clues.add(new Clue(ClueType.MUST_FRUIT, List.of(id), 0, null, false));
                }
            }
            return clues;
        }

        Clue orderClue(int centre, List<Integer> hidden, Fruit named, boolean anchor) {
            if (named == Fruit.NO_FRUIT) return new Clue(ClueType.NO_FRUIT, hidden, 0, null, false);
            boolean satisfied = false;
            for (int nb : NEIGHBOURS[centre]) if (known[nb] == named && diggable[nb]) satisfied = true;
            return new Clue(anchor ? ClueType.FLOOR : ClueType.CEIL, hidden, dowsingRank(named), named, satisfied);
        }

        double pointValue(Fruit f) {
            return switch (f) {
                case APPLE -> 100.0 * (applesCollected + 1);
                case CHERRY -> 200.0 + 300.0 * (cherriesCollected + 1);
                case BOMB, RUM, UNKNOWN, NO_FRUIT -> 0.0;
                default -> f.points();
            };
        }

        Map<Fruit, Double> distribution(int cell) {
            return beliefs.getOrDefault(cell, Map.of());
        }

        double probability(int cell, Fruit f) {
            if (known[cell] != null) return known[cell] == f ? 1.0 : 0.0;
            return distribution(cell).getOrDefault(f, 0.0);
        }

        double expectedPoints(int cell) {
            double raw;
            if (known[cell] != null) raw = valueWithWatermelon(known[cell], cell);
            else {
                raw = 0;
                for (Map.Entry<Fruit, Double> e : distribution(cell).entrySet()) raw += e.getValue() * valueWithWatermelon(e.getKey(), cell);
            }
            return raw * nextMultiplier;
        }

        double valueWithWatermelon(Fruit f, int cell) {
            if (f != Fruit.WATERMELON) return pointValue(f);
            double nearby = averageNearbyFruitValue(cell);
            return pointValue(f) + 0.5 * (nearby > 0 ? nearby : averageRemainingFruitValue());
        }

        double averageNearbyFruitValue(int cell) {
            double value = 0, weight = 0;
            for (int nb : NEIGHBOURS[cell]) {
                if (!diggable[nb]) continue;
                if (known[nb] != null) {
                    if (known[nb].edible) {
                        value += pointValue(known[nb]);
                        weight += 1;
                    }
                } else {
                    for (Map.Entry<Fruit, Double> e : distribution(nb).entrySet()) {
                        if (e.getKey().edible) {
                            value += e.getValue() * pointValue(e.getKey());
                            weight += e.getValue();
                        }
                    }
                }
            }
            return weight > 0 ? value / weight : 0;
        }

        double averageRemainingFruitValue() {
            int count = 0;
            double value = 0;
            for (Fruit f : Fruit.values()) {
                if (!f.edible) continue;
                int n = f.count();
                for (Fruit k : known) if (k == f) n--;
                count += n;
                value += pointValue(f) * n;
            }
            return count > 0 ? value / count : 0;
        }

        double fruitValueAtRisk(int cell) {
            double total = 0;
            for (int nb : NEIGHBOURS[cell]) {
                if (!diggable[nb]) continue;
                if (known[nb] != null) {
                    if (known[nb].edible) total += pointValue(known[nb]);
                } else {
                    for (Map.Entry<Fruit, Double> e : distribution(nb).entrySet()) if (e.getKey().edible) total += e.getValue() * pointValue(e.getKey());
                }
            }
            return total;
        }

        double opportunityCost() {
            double best = 0;
            for (int c : candidates) {
                if (probability(c, Fruit.BOMB) < 0.05 && probability(c, Fruit.RUM) < 0.05) best = Math.max(best, expectedPoints(c));
            }
            return best > 0 ? best : averageRemainingFruitValue();
        }

        double score(int cell, double opportunity) {
            double bomb = probability(cell, Fruit.BOMB);
            double risk = (bomb + probability(cell, Fruit.RUM)) * opportunity * RISK_WEIGHT;
            if (!coconutProtection) risk += bomb * DESTRUCTION_WEIGHT * fruitValueAtRisk(cell);
            double setup = (probability(cell, Fruit.POMEGRANATE) - probability(cell, Fruit.DURIAN)) * SETUP_WEIGHT * opportunity;
            int hidden = 0;
            for (int nb : NEIGHBOURS[cell]) if (openOnBoard.contains(nb)) hidden++;
            return expectedPoints(cell) - risk + setup + hidden * INFO_WEIGHT;
        }

        String shovel(int cell) {
            double fruitNearby = 0;
            for (int nb : NEIGHBOURS[cell]) {
                if (!diggable[nb]) continue;
                if (known[nb] != null) {
                    if (known[nb].edible) fruitNearby += 1;
                } else {
                    for (Map.Entry<Fruit, Double> e : distribution(nb).entrySet()) if (e.getKey().edible) fruitNearby += e.getValue();
                }
            }
            return fruitNearby >= 0.25 ? "Anchor" : "Mines";
        }

        Recommendation recommend() {
            if (candidates.isEmpty()) return null;
            double opportunity = opportunityCost();
            int best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int c : candidates) {
                double s = score(c, opportunity);
                if (s > bestScore) {
                    bestScore = s;
                    best = c;
                }
            }
            // A Durian halved the next dig: spend it on the cheapest safe cell and keep the good ones for full value.
            if (nextMultiplier < 1.0 && CarnivalFruitDigging.maxDigs() - digsUsed > 1) {
                int absorber = -1;
                double lowest = Double.POSITIVE_INFINITY;
                for (int c : candidates) {
                    if (probability(c, Fruit.BOMB) >= 0.15 || probability(c, Fruit.RUM) >= 0.15) continue;
                    double e = expectedPoints(c);
                    if (e < lowest) {
                        lowest = e;
                        absorber = c;
                    }
                }
                if (absorber >= 0) best = absorber;
            }
            return new Recommendation(best / SIZE, best % SIZE, shovel(best), expectedPoints(best),
                probability(best, Fruit.BOMB), probability(best, Fruit.RUM), samples);
        }
    }

    private enum ClueType { BOMBS, FLOOR, CEIL, NO_FRUIT, MUST_FRUIT }

    private record Clue(ClueType type, List<Integer> cells, int value, Fruit named, boolean satisfiedByKnown) {}

    /** Deals the bag onto the open cells and keeps the deals that fit every clue. */
    private static final class Belief {
        final List<Integer> open;
        final List<Fruit> pool;
        final List<Clue> clues;
        final Random random;
        int samples;

        Belief(List<Integer> open, List<Fruit> pool, List<Clue> clues, Random random) {
            this.open = open;
            this.pool = pool;
            this.clues = clues;
            this.random = random;
        }

        Map<Integer, Map<Fruit, Double>> estimate() {
            if (open.isEmpty()) return Map.of();
            if (clues.isEmpty()) {
                samples = TARGET_SAMPLES;
                return baseRates();
            }
            Map<Integer, Integer> slotOf = new HashMap<>();
            for (int i = 0; i < open.size(); i++) slotOf.put(open.get(i), i);
            List<int[]> slots = new ArrayList<>();
            for (Clue clue : clues) slots.add(clue.cells().stream().filter(slotOf::containsKey).mapToInt(slotOf::get).toArray());

            Fruit[] deal = pool.toArray(new Fruit[0]);
            int[][] hits = new int[open.size()][Fruit.values().length];
            int accepted = 0;
            for (int attempt = 0; attempt < MAX_ATTEMPTS && accepted < TARGET_SAMPLES; attempt++) {
                for (int i = deal.length - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    Fruit t = deal[i];
                    deal[i] = deal[j];
                    deal[j] = t;
                }
                boolean ok = true;
                for (int c = 0; c < clues.size() && ok; c++) ok = fits(clues.get(c), slots.get(c), deal);
                if (!ok) continue;
                accepted++;
                for (int s = 0; s < deal.length; s++) hits[s][deal[s].ordinal()]++;
            }
            samples = accepted;
            if (accepted == 0) return baseRates();
            Map<Integer, Map<Fruit, Double>> result = new HashMap<>();
            for (int s = 0; s < open.size(); s++) {
                Map<Fruit, Double> dist = new EnumMap<>(Fruit.class);
                for (Fruit f : Fruit.values()) if (hits[s][f.ordinal()] > 0) dist.put(f, hits[s][f.ordinal()] / (double) accepted);
                result.put(open.get(s), dist);
            }
            return result;
        }

        Map<Integer, Map<Fruit, Double>> baseRates() {
            Map<Fruit, Double> dist = new EnumMap<>(Fruit.class);
            for (Fruit f : pool) dist.merge(f, 1.0 / pool.size(), Double::sum);
            Map<Integer, Map<Fruit, Double>> result = new HashMap<>();
            for (int cell : open) result.put(cell, dist);
            return result;
        }

        static boolean fits(Clue clue, int[] slots, Fruit[] deal) {
            switch (clue.type()) {
                case BOMBS -> {
                    int bombs = 0;
                    for (int s : slots) if (deal[s] == Fruit.BOMB) bombs++;
                    return bombs == clue.value();
                }
                case NO_FRUIT -> {
                    for (int s : slots) if (deal[s].edible) return false;
                    return true;
                }
                case FLOOR, CEIL -> {
                    boolean found = clue.satisfiedByKnown();
                    for (int s : slots) {
                        Fruit f = deal[s];
                        if (!f.edible) continue;
                        int rank = dowsingRank(f);
                        if (clue.type() == ClueType.FLOOR ? rank < clue.value() : rank > clue.value()) return false;
                        if (f == clue.named()) found = true;
                    }
                    return found;
                }
                case MUST_FRUIT -> {
                    return slots.length == 0 || deal[slots[0]].edible;
                }
            }
            return true;
        }
    }
}
