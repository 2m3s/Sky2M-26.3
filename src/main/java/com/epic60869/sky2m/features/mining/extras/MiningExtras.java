package com.epic60869.sky2m.features.mining.extras;

import java.nio.file.Path;

/** Entry point of the Glacite Mineshaft helpers, powder tracker and fossil excavator helpers. */
public final class MiningExtras {
    private MiningExtras() {}

    public static void init(Path configDir) {
        GlaciteMineshaft.init();
        MineshaftStats.init();
        PowderTrackerHud.init();
        FossilSolver.init();
        ExcavatorProfit.init(configDir);
    }
}
