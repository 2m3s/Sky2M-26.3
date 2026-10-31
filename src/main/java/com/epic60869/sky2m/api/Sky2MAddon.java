package com.epic60869.sky2m.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * An add-on mod for Sky2M. Add-ons list a class implementing this under the {@code "sky2m-addon"} entrypoint in their
 * fabric.mod.json; Sky2M then shows them in /s2 > Add-ons with a button that opens {@link #openSettings(Screen)}.
 */
public interface Sky2MAddon {
    /** The name shown in /s2 > Add-ons. */
    String name();

    /** One line about what the add-on does. */
    default String description() {
        return "";
    }

    /** Opens the add-on's own features and settings over {@code parent} (return to it when closed). */
    void openSettings(Screen parent);
}
