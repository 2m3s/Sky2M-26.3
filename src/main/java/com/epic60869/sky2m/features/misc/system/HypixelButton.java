package com.epic60869.sky2m.features.misc.system;

import com.epic60869.sky2m.config.Sky2MConfig;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Locale;

/**
 * Misc > Hypixel Button: a "Hypixel" button on the title screen that connects straight to play.hypixel.net. Next to
 * Multiplayer (which gets half as wide), placed like SkyOcean's Quick Join button. When a custom main menu mod hides
 * or replaces the title screen's buttons, it floats in the top right instead, drawn after (over) everything else and
 * taking its clicks first, so it shows over custom menus too. A small "S2" button beside it (always shown) opens the
 * Sky2M settings.
 */
public final class HypixelButton {
    private static final String ADDRESS = "play.hypixel.net";
    private static final int FLOAT_WIDTH = 80, FLOAT_HEIGHT = 20, FLOAT_MARGIN = 6, GAP = 4;

    private HypixelButton() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.hypixelButton;
    }

    /** The vanilla title screen, or a custom main menu's own screen (FancyMenu, Custom Main Menu, ...). */
    private static boolean isMainMenu(Screen screen) {
        if (screen instanceof TitleScreen) return true;
        String name = screen.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("titlescreen") || name.contains("mainmenu") || name.contains("main_menu");
    }

    private static void connect(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        ServerData data = new ServerData("Hypixel", ADDRESS, ServerData.Type.OTHER);
        // Not in the server list, so don't ask about the resource pack every time.
        data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
        ConnectScreen.startConnecting(screen, client, ServerAddress.parseString(ADDRESS), data, false, null);
    }

    /** Sky2M's settings (what /s2 opens), going back to the title screen when closed. Needs no world or player. */
    private static void openSettings(Screen screen) {
        Sky2MConfig.openGuiOver(screen);
    }

    /** The square "S2" settings button, sized like vanilla's icon buttons (language, accessibility). */
    private static Button settingsButton(Screen screen, int size) {
        return Button.builder(Component.literal("S2"), b -> openSettings(screen))
            .size(size, size)
            .tooltip(Tooltip.create(Component.literal("Sky2M settings")))
            .build();
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // The S2 button is always there; the Hypixel button only when turned on.
            if (Sky2MConfig.current() == null || !isMainMenu(screen)) return;
            Button multiplayer = null;
            for (AbstractWidget widget : Screens.getWidgets(screen)) {
                if (widget instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals("menu.multiplayer")) {
                    multiplayer = button;
                    break;
                }
            }
            if (multiplayer != null && multiplayer.visible) {
                besideMultiplayer(screen, multiplayer, enabled());
            } else {
                floating(screen, enabled());
            }
        });
    }

    /**
     * Multiplayer, Hypixel (when on) and S2 share Multiplayer's row, together exactly as wide as Multiplayer was, so
     * nothing reaches into the vanilla buttons beside or below it.
     */
    private static void besideMultiplayer(Screen screen, Button multiplayerButton, boolean withHypixel) {
        int height = multiplayerButton.getHeight();
        int rest = multiplayerButton.getWidth() - height - GAP;
        Button hypixel = null;
        if (withHypixel) {
            int half = (rest - GAP) / 2;
            multiplayerButton.setWidth(half);
            hypixel = Button.builder(Component.literal("Hypixel"), b -> connect(screen))
                .bounds(multiplayerButton.getX() + half + GAP, multiplayerButton.getY(), rest - half - GAP, height)
                .tooltip(Tooltip.create(Component.literal("Join " + ADDRESS + " (Sky2M)")))
                .build();
            Screens.getWidgets(screen).add(hypixel);
        } else {
            multiplayerButton.setWidth(rest);
        }
        Button settings = settingsButton(screen, height);
        Screens.getWidgets(screen).add(settings);
        Button hypixelButton = hypixel;
        boolean[] floated = {false};
        Runnable place = () -> {
            int x = multiplayerButton.getX() + multiplayerButton.getWidth() + GAP;
            if (hypixelButton != null) {
                hypixelButton.setX(x);
                hypixelButton.setY(multiplayerButton.getY());
                x += hypixelButton.getWidth() + GAP;
            }
            settings.setX(x);
            settings.setY(multiplayerButton.getY());
        };
        place.run();
        // The title screen can move its buttons (e.g. while it fades in), so keep these next to Multiplayer.
        ScreenEvents.afterTick(screen).register(s -> {
            place.run();
            // A custom menu mod can hide the buttons after the screen opens: float instead.
            if (!multiplayerButton.visible && !floated[0]) {
                floated[0] = true;
                if (hypixelButton != null) hypixelButton.visible = false;
                settings.visible = false;
                floating(screen, hypixelButton != null);
            }
        });
    }

    /** Top right: Join Hypixel (when on) with S2 to its left. */
    private static void floating(Screen screen, boolean withHypixel) {
        int inset = FLOAT_MARGIN;
        if (withHypixel) {
            Button hypixel = Button.builder(Component.literal("Join Hypixel"), b -> connect(screen))
                .size(FLOAT_WIDTH, FLOAT_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("Join " + ADDRESS + " (Sky2M)")))
                .build();
            floatButton(screen, hypixel, inset, HypixelButton::connect);
            inset += FLOAT_WIDTH + GAP;
        }
        floatButton(screen, settingsButton(screen, FLOAT_HEIGHT), inset, HypixelButton::openSettings);
    }

    /**
     * Not a widget of the screen: drawn last and clicked first, so a custom menu drawing over the screen can't hide it.
     * {@code rightInset} is the gap between its right edge and the screen's.
     */
    private static void floatButton(Screen screen, Button button, int rightInset, java.util.function.Consumer<Screen> press) {
        button.setPosition(screen.width - rightInset - button.getWidth(), FLOAT_MARGIN);
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
            button.setX(s.width - rightInset - button.getWidth());
            graphics.nextStratum();
            button.extractRenderState(graphics, mouseX, mouseY, delta);
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || !button.isMouseOver(event.x(), event.y())) return true;
            button.playDownSound(Minecraft.getInstance().getSoundManager());
            press.accept(s);
            return false;
        });
    }
}
