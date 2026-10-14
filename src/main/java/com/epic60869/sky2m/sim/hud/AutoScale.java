package com.epic60869.sky2m.sim.hud;

/** No Auto Scale in Sky2M: the factor is always 1. */
final class AutoScale {

    private AutoScale() {
    }

    static float current() {
        return 1.0f;
    }
}
