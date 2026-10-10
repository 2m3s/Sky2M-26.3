package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.custom.util.Input;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Whether a MoulConfig keybind setting (a key, or a mouse button stored the MoulConfig way) is held. The Minecraft 26.2
 * and 26.3 versions of this class differ (GLFW and SDL); features use this so their own code is the same in both.
 */
public final class BindKeys {
    private BindKeys() {}

    /** Whether the bind is set at all. */
    public static boolean isSet(int bind) {
        return bind != InputConstants.UNKNOWN.getValue() && bind != 0;
    }

    public static boolean down(int bind) {
        if (!isSet(bind)) return false;
        int mouse = Input.bindMouseButton(bind);
        return mouse >= 0 ? Input.mouseDown(mouse) : Input.keyDown(bind);
    }
}
