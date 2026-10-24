package com.epic60869.sky2m.features.garden.skyhanni;

/** Starts SkyHanni's farming features for the Garden (see {@link SkyHanniGardenConfig}). Called from GardenFeatures.init. */
public final class SkyHanniGarden {
    private SkyHanniGarden() {}

    public static void init() {
        GardenTabData.init();
        FarmingHud.init();
        JacobContest.init();
        FarmingFortuneHud.init();
        OptimalFarming.init();
        PlotBorders.init();
        PestExtras.init();
    }
}
