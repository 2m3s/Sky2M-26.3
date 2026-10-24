package com.epic60869.sky2m.features.combat.kuudra;

import net.minecraft.core.BlockPos;

/** The supply crates of Kuudra's Hollow and where they are picked up and dropped off (Odin's Supply, BSD-3-Clause). */
enum KuudraSupply {
    TRIANGLE("Triangle", new BlockPos(-67, 77, -122), new BlockPos(-94, 78, -106)),
    X("X", new BlockPos(-142, 77, -151), new BlockPos(-106, 78, -112)),
    EQUALS("Equals", new BlockPos(-65, 76, -87), new BlockPos(-98, 78, -99)),
    SLASH("Slash", new BlockPos(-113, 77, -68), new BlockPos(-106, 78, -99)),
    SHOP("Shop", new BlockPos(-81, 76, -143), new BlockPos(-98, 78, -112)),
    XCANNON("xCannon", new BlockPos(-143, 76, -125), new BlockPos(-110, 78, -106)),
    SQUARE("Square", new BlockPos(-143, 76, -80), new BlockPos(0, 0, 0)),
    NONE("None", new BlockPos(0, 0, 0), new BlockPos(0, 0, 0));

    final String label;
    final BlockPos pickUp;
    final BlockPos dropOff;
    /** False once "SUPPLIES RECEIVED" shows over its drop-off. */
    boolean active = true;

    KuudraSupply(String label, BlockPos pickUp, BlockPos dropOff) {
        this.label = label;
        this.pickUp = pickUp;
        this.dropOff = dropOff;
    }

    /** From a party call such as "Triangle", "X", "xCannon". */
    static KuudraSupply byLabel(String text) {
        for (KuudraSupply s : values()) if (s.label.equalsIgnoreCase(text)) return s;
        return NONE;
    }

    boolean pickUpCloserThan(double x, double y, double z, double distance) {
        double dx = pickUp.getX() - x, dy = pickUp.getY() - y, dz = pickUp.getZ() - z;
        return dx * dx + dy * dy + dz * dz < distance * distance;
    }

    boolean pickUpCenterCloserThan(double x, double y, double z, double distance) {
        double dx = pickUp.getX() + 0.5 - x, dy = pickUp.getY() + 0.5 - y, dz = pickUp.getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz < distance * distance;
    }
}
