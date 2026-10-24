package com.epic60869.sky2m.features.dungeons.mapstyle;

/** Dungeons > Dungeon Map > Map Style: which mod's dungeon map look is drawn. Add a value here and register its renderer in {@link DungeonMapStyles}. */
public enum DungeonMapStyle {
    SKYBLOCKER("Skyblocker"), STELLA("Stella"), DEVONIAN("Devonian");

    private final String label;

    DungeonMapStyle(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
