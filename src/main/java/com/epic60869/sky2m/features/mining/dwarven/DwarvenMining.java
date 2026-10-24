package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.Sky2MConfig;

import java.nio.file.Path;

/** Entry point and config access of the Dwarven Mines solvers, Nucleus tracker, cold helpers, Flowstate, HOTX overlays and mining events. */
public final class DwarvenMining {
    private DwarvenMining() {}

    public static void init(Path configDir) {
        DwarvenSolvers.init();
        MetalDetectorSolver.init();
        NucleusTracker.init(configDir);
        GlaciteCold.init();
        PrecisionMining.init(configDir);
        HotxOverlay.init();
        MiningEventsHud.init();
    }

    static Sky2MConfig.Mining mining() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining;
    }

    static DwarvenConfig.Solvers solvers() {
        Sky2MConfig.Mining m = mining();
        return m == null ? null : m.dwarvenSolvers;
    }

    static DwarvenConfig.Nucleus nucleus() {
        Sky2MConfig.Mining m = mining();
        return m == null ? null : m.nucleus;
    }

    static DwarvenConfig.Glacite glacite() {
        Sky2MConfig.Mining m = mining();
        return m == null ? null : m.glaciteCold;
    }

    static DwarvenConfig.Precision precision() {
        Sky2MConfig.Mining m = mining();
        return m == null ? null : m.precision;
    }

    static DwarvenConfig.Hotx hotx() {
        Sky2MConfig.Mining m = mining();
        return m == null ? null : m.hotx;
    }

    static DwarvenConfig.Events events() {
        Sky2MConfig.Mining m = mining();
        return m == null ? null : m.miningEvents;
    }

    public static boolean redCarpets() {
        Sky2MConfig.Mining m = mining();
        return m != null && m.retexture != null && m.retexture.redCarpets;
    }
}
