package com.epic60869.sky2m.features.mining.extras;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fossil Excavator solver, a port of SkyHanni's FossilSolver and FossilSolverDisplay (hannibal002/SkyHanni,
 * LGPL-2.1). The excavator is a 9x6 grid; one of eight fossil shapes (any rotation or flip it allows) hides under
 * the dirt. The solver counts, for every tile, in how many placements still consistent with the revealed tiles it
 * is part of the fossil, and highlights the tile in the most of them. The first chisels follow a fixed opening that
 * is best against all fossils. Display only: you still click the tile yourself.
 */
public final class FossilSolver {
    private static final Pattern CHARGES = Pattern.compile("Chisel Charges Remaining: (\\d+)");
    private static final Pattern PROGRESS = Pattern.compile("Fossil Excavation Progress: ([\\d.]+%)");

    private record Tile(int x, int y) {
        Tile(int slot) {
            this(slot % 9, slot / 9);
        }

        int slot() {
            return x + y * 9;
        }
    }

    private record Shape(List<Tile> tiles) {
        int width() {
            return tiles.stream().mapToInt(Tile::x).max().orElse(0) - tiles.stream().mapToInt(Tile::x).min().orElse(0);
        }

        int height() {
            return tiles.stream().mapToInt(Tile::y).max().orElse(0) - tiles.stream().mapToInt(Tile::y).min().orElse(0);
        }

        Shape moveTo(int x, int y) {
            return new Shape(tiles.stream().map(t -> new Tile(t.x + x, t.y + y)).toList());
        }

        Shape rotate(int degree) {
            int w = width(), h = height();
            return switch (degree) {
                case 90 -> new Shape(tiles.stream().map(t -> new Tile(t.y, w - t.x)).toList());
                case 180 -> new Shape(tiles.stream().map(t -> new Tile(w - t.x, h - t.y)).toList());
                case 270 -> new Shape(tiles.stream().map(t -> new Tile(h - t.y, t.x)).toList());
                default -> this;
            };
        }

        Shape flip() {
            int h = height();
            return new Shape(tiles.stream().map(t -> new Tile(t.x, h - t.y)).toList());
        }
    }

    private enum Mutation {
        ROTATE_0, ROTATE_90, ROTATE_180, ROTATE_270, FLIP_ROTATE_0, FLIP_ROTATE_90, FLIP_ROTATE_180, FLIP_ROTATE_270;

        static final List<Mutation> ONLY_ROTATION = List.of(ROTATE_0, ROTATE_90, ROTATE_180, ROTATE_270);

        Shape apply(Shape shape) {
            int degree = (ordinal() % 4) * 90;
            Shape rotated = shape.rotate(degree);
            return ordinal() >= 4 ? rotated.flip() : rotated;
        }
    }

    private static Shape shape(int... xy) {
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) tiles.add(new Tile(xy[i], xy[i + 1]));
        return new Shape(tiles);
    }

    private enum Fossil {
        TUSK("Tusk", "12.5%", shape(0, 2, 0, 3, 0, 4, 1, 1, 2, 0, 3, 1, 3, 3, 4, 2), List.of(Mutation.values())),
        WEBBED("Webbed", "10%", shape(0, 2, 1, 1, 2, 0, 3, 0, 3, 1, 3, 2, 3, 3, 4, 0, 5, 1, 6, 2),
            List.of(Mutation.ROTATE_0, Mutation.FLIP_ROTATE_0)),
        CLUB("Club", "9.1%", shape(0, 2, 0, 3, 1, 2, 1, 3, 2, 1, 3, 0, 4, 0, 5, 0, 6, 0, 6, 2, 7, 1),
            List.of(Mutation.ROTATE_0, Mutation.ROTATE_180, Mutation.FLIP_ROTATE_0, Mutation.FLIP_ROTATE_180)),
        SPINE("Spine", "8.3%", shape(0, 2, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 3, 0, 3, 1, 3, 2, 4, 1, 4, 2, 5, 2), Mutation.ONLY_ROTATION),
        CLAW("Claw", "7.7%", shape(0, 3, 1, 2, 1, 4, 2, 1, 2, 3, 3, 1, 3, 2, 3, 4, 4, 0, 4, 1, 4, 2, 4, 3, 5, 1), List.of(Mutation.values())),
        FOOTPRINT("Footprint", "7.7%", shape(0, 2, 1, 1, 1, 2, 1, 3, 2, 1, 2, 2, 2, 3, 3, 0, 3, 2, 3, 4, 4, 0, 4, 2, 4, 4), Mutation.ONLY_ROTATION),
        HELIX("Helix", "7.1%", shape(0, 0, 0, 1, 0, 2, 0, 4, 1, 0, 1, 2, 1, 4, 2, 0, 2, 4, 3, 0, 3, 1, 3, 2, 3, 3, 3, 4), List.of(Mutation.values())),
        UGLY("Ugly", "6.2%", shape(0, 1, 1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 2, 3, 3, 0, 3, 1, 3, 2, 3, 3, 4, 0, 4, 1, 4, 2, 5, 1), Mutation.ONLY_ROTATION);

        final String label, firstPercentage;
        final Shape shape;
        final List<Mutation> mutations;

        Fossil(String label, String firstPercentage, Shape shape, List<Mutation> mutations) {
            this.label = label;
            this.firstPercentage = firstPercentage;
            this.shape = shape;
            this.mutations = mutations;
        }
    }

    private record Step(Tile tile, double chance, int remaining) {}

    /** Used while you have fewer than 18 chisel charges: solves most fossils in 16 to 18 clicks, not all. */
    private static final List<Step> RISKY = List.of(
        new Step(new Tile(4, 2), 0.515, 404), new Step(new Tile(5, 3), 0.393, 196), new Step(new Tile(3, 2), 0.513, 119),
        new Step(new Tile(7, 2), 0.345, 58), new Step(new Tile(1, 3), 0.342, 38), new Step(new Tile(3, 4), 0.6, 25),
        new Step(new Tile(5, 1), 0.8, 10), new Step(new Tile(4, 3), 1.0, 2));
    /** With 18 charges this opening solves every fossil in 18 clicks. */
    private static final List<Step> SAFE = List.of(
        new Step(new Tile(4, 2), 0.515, 404), new Step(new Tile(5, 4), 0.413, 196), new Step(new Tile(3, 3), 0.461, 115),
        new Step(new Tile(5, 2), 0.387, 62), new Step(new Tile(3, 1), 0.342, 38), new Step(new Tile(7, 3), 0.48, 25),
        new Step(new Tile(1, 2), 0.846, 13), new Step(new Tile(3, 4), 1.0, 2));

    private static int maxCharges, charges, remaining = -1, slotToClick = -1;
    private static String chance, percentage;
    private static boolean impossible, completed;
    private static Set<Fossil> possible = Set.of();
    private static List<String> lastNames = List.of();
    private static boolean inGrid;

    private FossilSolver() {}

    private static MiningExtrasConfig.FossilExcavator config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.fossilExcavator;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(FossilSolver::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            clear();
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> render(container, g));
        });
    }

    private static void clear() {
        maxCharges = 0;
        charges = 0;
        remaining = -1;
        slotToClick = -1;
        chance = null;
        percentage = null;
        impossible = false;
        completed = false;
        possible = Set.of();
        lastNames = List.of();
        inGrid = false;
    }

    private static boolean enabled() {
        MiningExtrasConfig.FossilExcavator c = config();
        return c != null && c.solver && Sky2MLocation.onSkyblock();
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || !enabled() || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
            || !Sky2MLocation.strip(screen.getTitle().getString()).trim().equals("Fossil Excavator")) {
            inGrid = false;
            return;
        }
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) if (!(slot.container instanceof Inventory) && slot.index < 54) slots.add(slot);
        List<String> names = new ArrayList<>();
        for (Slot slot : slots) names.add(Sky2MLocation.strip(slot.getItem().getHoverName().getString()));
        // The first menu (with the Start Excavator button) is not the grid.
        if (names.contains("Start Excavator")) {
            inGrid = false;
            return;
        }
        inGrid = true;
        if (names.equals(lastNames)) return;
        lastNames = names;

        Set<Integer> fossils = new HashSet<>(), dirt = new HashSet<>();
        boolean foundCharges = false;
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            String name = names.get(slot.index);
            boolean isDirt = name.equals("Dirt"), isFossil = name.equals("Fossil");
            if (isDirt) dirt.add(slot.index);
            else if (isFossil) fossils.add(slot.index);
            else continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                String text = Sky2MLocation.strip(line.getString());
                Matcher charge = CHARGES.matcher(text);
                if (!foundCharges && charge.find()) {
                    charges = Integer.parseInt(charge.group(1));
                    if (maxCharges == 0) maxCharges = charges;
                    foundCharges = true;
                }
                Matcher progress = PROGRESS.matcher(text);
                if (isFossil && percentage == null && progress.find()) percentage = progress.group(1);
            }
        }
        solve(fossils, dirt);
    }

    private static void solve(Set<Integer> fossilSlots, Set<Integer> dirtSlots) {
        slotToClick = -1;
        chance = null;
        impossible = false;
        completed = false;
        Set<Tile> invalid = new HashSet<>();
        for (int i = 0; i < 54; i++) if (!fossilSlots.contains(i) && !dirtSlots.contains(i)) invalid.add(new Tile(i));
        Set<Tile> found = new HashSet<>();
        for (int slot : fossilSlots) found.add(new Tile(slot));

        List<Step> sequence = maxCharges < 18 ? RISKY : SAFE;
        boolean allInSequence = true;
        for (Tile t : invalid) {
            boolean in = false;
            for (Step step : sequence) if (step.tile.equals(t)) in = true;
            allInSequence &= in;
        }
        if (found.isEmpty() && allInSequence) {
            int taken = invalid.size();
            if (taken >= sequence.size()) {
                impossible = true;
                return;
            }
            Step next = sequence.get(taken);
            next(next.tile, next.chance, next.remaining);
            return;
        }

        Map<Tile, Integer> counts = new HashMap<>();
        double total = 0;
        List<Fossil> candidates = percentage == null ? List.of(Fossil.values())
            : java.util.Arrays.stream(Fossil.values()).filter(f -> f.firstPercentage.equals(percentage)).toList();
        if (percentage != null) possible = EnumSet.copyOf(candidates.isEmpty() ? List.of(Fossil.TUSK) : candidates);
        for (int x = 0; x <= 8; x++) {
            for (int y = 0; y <= 5; y++) {
                for (Fossil fossil : candidates) {
                    for (Mutation mutation : fossil.mutations) {
                        Shape placed = mutation.apply(fossil.shape).moveTo(x, y);
                        if (!valid(placed, invalid, found)) continue;
                        total++;
                        for (Tile t : placed.tiles) counts.merge(t, 1, Integer::sum);
                    }
                }
            }
        }
        for (Tile t : found) counts.remove(t);
        Tile best = null;
        int bestCount = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        if (best == null) {
            if (fossilSlots.isEmpty()) impossible = true;
            else completed = true;
            return;
        }
        next(best, bestCount / total, (int) total);
    }

    private static boolean valid(Shape shape, Set<Tile> invalid, Set<Tile> found) {
        for (Tile t : shape.tiles) {
            if (invalid.contains(t) || t.x < 0 || t.y < 0 || t.x >= 9 || t.y >= 6) return false;
        }
        for (Tile t : found) if (!shape.tiles.contains(t)) return false;
        return true;
    }

    private static void next(Tile tile, double chance, int remaining) {
        slotToClick = tile.slot();
        FossilSolver.chance = String.format(Locale.US, "%.1f%%", chance * 100);
        FossilSolver.remaining = remaining;
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!enabled() || !inGrid) return;
        MiningExtrasConfig.FossilExcavator c = config();
        var accessor = (Sky2MContainerScreenAccessor) screen;
        int left = accessor.sky2m$getLeftPos(), top = accessor.sky2m$getTopPos();
        var font = Minecraft.getInstance().font;
        if (slotToClick >= 0) {
            for (Slot slot : screen.getMenu().slots) {
                if (slot.index != slotToClick || slot.container instanceof Inventory) continue;
                int x = left + slot.x, y = top + slot.y;
                g.fill(x, y, x + 16, y + 16, 0x9055FF55);
                if (c.showChance && chance != null) g.text(font, Component.literal(chance).withStyle(ChatFormatting.DARK_GREEN), x, y + 8, 0xFFFFFFFF, true);
            }
        }
        List<Component> lines = new ArrayList<>();
        if (impossible) lines.add(Component.literal("No possible fossils on board.").withStyle(ChatFormatting.RED));
        else if (completed) lines.add(Component.literal("Fossil found, get all the loot you can.").withStyle(ChatFormatting.GREEN));
        else lines.add(Component.literal("Possible fossils remaining: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(String.valueOf(Math.max(remaining, 0))).withStyle(ChatFormatting.GREEN)));
        lines.add(Component.literal("Charges remaining: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(String.valueOf(charges)).withStyle(ChatFormatting.GREEN)));
        if (!possible.isEmpty()) {
            lines.add(Component.literal("Possible fossil types:").withStyle(ChatFormatting.YELLOW));
            for (Fossil f : possible) lines.add(Component.literal("- " + f.label).withStyle(ChatFormatting.GRAY));
        }
        Sky2MHuds.render(g, lines, Math.max(4, left - Sky2MHuds.width(lines, 1f) - 6), top, 1f);
    }
}
