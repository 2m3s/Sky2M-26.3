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
        // -1 is "unset" in settings from 26.2 (its unknown key); 26.3's own unknown key is 0.
        return bind != InputConstants.UNKNOWN.getValue() && bind != 0 && bind != -1;
    }

    /**
     * The mouse button a bind stands for (as {@link Input} numbers buttons), or -1 for a keyboard key or no bind.
     * On 26.3 mouse buttons are stored as -100 + the button (see {@link Input#bindMouseButton}).
     */
    public static int mouseButton(int bind) {
        return isSet(bind) ? Input.bindMouseButton(bind) : -1;
    }

    public static boolean down(int bind) {
        if (!isSet(bind)) return false;
        int mouse = Input.bindMouseButton(bind);
        return mouse >= 0 ? Input.mouseDown(mouse) : Input.keyDown(bind);
    }
}
