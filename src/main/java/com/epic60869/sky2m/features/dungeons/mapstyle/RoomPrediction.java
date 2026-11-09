package com.epic60869.sky2m.features.dungeons.mapstyle;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Predict Unopened Rooms (Dungeons > Dungeon Map): a guess at what a grey room is, worked out only from what Hypixel's
 * map item shows. Puzzles, traps and minibosses are always one-tile dead ends, so a grey room that can't be any bigger
 * (every slot around it is on the map, or it is in the floor's right-hand 1x1 column) and has one door is probably one
 * of them. Types already opened on the map are then ruled out.
 *
 * <p>Takes a plain snapshot of map cells and no world data: no blocks, chunks or room database, so it reveals nothing
 * beyond what can be deduced from the map, and it remains a guess (a dead-end normal room reads the same).
 *
 * <p>The dead-end, 1x1 column and generation order rules (the miniboss is placed before the trap, both before puzzles,
 * so outside the column they take the first special rooms) come from the SpecialColumn class of dtMap
 * (https://github.com/ricedotwho/dtMap, BSD-3-Clause, Copyright (c) 2026 rice.who) and Odin's
 * (https://github.com/odtheking/Odin, BSD-3-Clause). Their other rules need the run's puzzle count from the tab list,
 * which is not on the map, so they are left out.
 */
public final class RoomPrediction {
    /** Cells across and down the map's room and door grid. Even x and z: a room slot. Otherwise: the gap between two. */
    public static final int GRID = 11;

    /** What the map item shows in one grid cell. */
    public enum Cell {
        /** Nothing on the map here yet. */
        NONE,
        /** A grey room with a question mark. */
        UNOPENED,
        /** An opened room that isn't a puzzle, trap or miniboss. */
        OPENED,
        PUZZLE, TRAP, MINIBOSS,
        /** Entrance or Blood: their colour is on the map from the start, there is nothing to guess. */
        FIXED,
        DOOR,
        /** The filled gap between two tiles of one bigger room. */
        CONNECTOR
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Keeps the grey room showing around a guess, so it never looks like a room that has been opened. */
    private static final int INSET = 2;
    private static final int TINT_ALPHA = 0x90000000;

    private RoomPrediction() {}

    /** The room column that only holds 1x1 rooms: the last one on F4-F6 and M4-M6, none on other floors (dtMap, Odin). */
    static int specialColumn(int floor) {
        return floor >= 4 && floor <= 6 ? 5 : -1;
    }

    /**
     * Guesses for the unopened rooms on the map.
     *
     * @param floor floor number (0 for the Entrance), negative when unknown: no guesses
     * @param cols  room slots across the floor
     * @param rows  room slots down the floor
     * @param cells {@link #GRID} x {@link #GRID} cells, indexed {@code z * GRID + x}
     */
    public static List<MapView.Guess> predict(int floor, int cols, int rows, Cell[] cells) {
        List<MapView.Guess> out = new ArrayList<>();
        if (floor < 0) return out;
        int column = specialColumn(floor);

        boolean openedMiniboss = false;
        boolean openedTrap = false;
        boolean trapOutsideColumn = false;
        int outsideColumn = 0; // special rooms outside the 1x1 column, opened or guessed
        List<int[]> candidates = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Cell cell = cells[index(col * 2, row * 2)];
                switch (cell) {
                    case MINIBOSS -> {
                        openedMiniboss = true;
                        if (col != column) outsideColumn++;
                    }
                    case TRAP -> {
                        openedTrap = true;
                        if (col != column) {
                            trapOutsideColumn = true;
                            outsideColumn++;
                        }
                    }
                    case PUZZLE -> {
                        if (col != column) outsideColumn++;
                    }
                    case UNOPENED -> {
                        if (!isOneTileDeadEnd(col, row, cols, rows, column, cells)) break;
                        candidates.add(new int[]{col, row});
                        if (col != column) outsideColumn++;
                    }
                    default -> {}
                }
            }
        }

        boolean trapPossible = floor >= 3 && !openedTrap;
        for (int[] slot : candidates) {
            boolean miniboss = !openedMiniboss;
            boolean trap = trapPossible;
            if (slot[0] == column) {
                // The miniboss goes first, then the trap: special rooms outside the column have used them up.
                if (outsideColumn >= 1) miniboss = false;
                if (outsideColumn >= 2 || trapOutsideColumn) trap = false;
            }
            List<MapView.RoomType> types = new ArrayList<>(3);
            if (miniboss) types.add(MapView.RoomType.MINIBOSS);
            if (trap) types.add(MapView.RoomType.TRAP);
            types.add(MapView.RoomType.PUZZLE); // a run can hold several, so opening one rules none out
            out.add(new MapView.Guess(slot[0], slot[1], List.copyOf(types)));
        }
        return out;
    }

    /** One door seen, and the room can't reach into another slot: all around it is off the floor, the 1x1 column or on the map. */
    private static boolean isOneTileDeadEnd(int col, int row, int cols, int rows, int column, Cell[] cells) {
        int doors = 0;
        for (int[] side : SIDES) {
            int nCol = col + side[0];
            int nRow = row + side[1];
            if (nCol < 0 || nRow < 0 || nCol >= cols || nRow >= rows) continue;
            Cell gap = cells[index(col * 2 + side[0], row * 2 + side[1])];
            if (gap == Cell.CONNECTOR) return false;
            if (gap == Cell.DOOR) doors++;
            if (col != column && nCol != column && cells[index(nCol * 2, nRow * 2)] == Cell.NONE) return false;
        }
        return doors == 1;
    }

    private static int index(int gx, int gz) {
        return gz * GRID + gx;
    }

    /**
     * Draws a guess on a room tile: one faint band per possible type, inset so the room's grey shows around it.
     *
     * @param color the map style's own colour for a room type
     */
    static void draw(GuiGraphicsExtractor g, MapView.Guess guess, int x, int y, int size, ToIntFunction<MapView.RoomType> color) {
        int inner = size - INSET * 2;
        if (inner <= 0 || guess.types().isEmpty()) return;
        int bands = guess.types().size();
        int top = y + INSET;
        int bandLeft = x + INSET;
        for (int i = 0; i < bands; i++) {
            int bandRight = i == bands - 1 ? x + INSET + inner : bandLeft + inner / bands;
            g.fill(bandLeft, top, bandRight, top + inner, TINT_ALPHA | (color.applyAsInt(guess.types().get(i)) & 0xFFFFFF));
            bandLeft = bandRight;
        }
    }
}
