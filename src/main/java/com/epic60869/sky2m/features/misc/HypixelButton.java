package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
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
 * taking its clicks first, so it shows over custom menus too.
 */
public final class HypixelButton {
    private static final String ADDRESS = "play.hypixel.net";
    private static final int FLOAT_WIDTH = 80, FLOAT_HEIGHT = 20, FLOAT_MARGIN = 6;

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

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!enabled() || !isMainMenu(screen)) return;
            Button multiplayer = null;
            for (AbstractWidget widget : Screens.getWidgets(screen)) {
                if (widget instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals("menu.multiplayer")) {
                    multiplayer = button;
                    break;
                }
            }
            if (multiplayer != null && multiplayer.visible) {
                besideMultiplayer(screen, multiplayer);
            } else {
                floating(screen);
            }
        });
    }

    private static void besideMultiplayer(Screen screen, Button multiplayerButton) {
        int half = (multiplayerButton.getWidth() - 4) / 2;
        multiplayerButton.setWidth(half);
        Button hypixel = Button.builder(Component.literal("Hypixel"), b -> connect(screen))
            .bounds(multiplayerButton.getX() + half + 4, multiplayerButton.getY(), half, multiplayerButton.getHeight())
            .tooltip(Tooltip.create(Component.literal("Join " + ADDRESS + " (Sky2M)")))
            .build();
        Screens.getWidgets(screen).add(hypixel);
        boolean[] floated = {false};
        // The title screen can move its buttons (e.g. while it fades in), so keep this one next to Multiplayer.
        ScreenEvents.afterTick(screen).register(s -> {
            hypixel.setX(multiplayerButton.getX() + multiplayerButton.getWidth() + 4);
            hypixel.setY(multiplayerButton.getY());
            // A custom menu mod can hide the buttons after the screen opens: float instead.
            if (!multiplayerButton.visible && !floated[0]) {
                floated[0] = true;
                hypixel.visible = false;
                floating(screen);
            }
        });
    }

    /** Not a widget of the screen: drawn last and clicked first, so a custom menu drawing over the screen can't hide it. */
    private static void floating(Screen screen) {
        Button hypixel = Button.builder(Component.literal("Join Hypixel"), b -> connect(screen))
            .bounds(screen.width - FLOAT_WIDTH - FLOAT_MARGIN, FLOAT_MARGIN, FLOAT_WIDTH, FLOAT_HEIGHT)
            .tooltip(Tooltip.create(Component.literal("Join " + ADDRESS + " (Sky2M)")))
            .build();
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
            hypixel.setX(s.width - FLOAT_WIDTH - FLOAT_MARGIN);
            graphics.nextStratum();
            hypixel.extractRenderState(graphics, mouseX, mouseY, delta);
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (event.button() != 0 || !hypixel.isMouseOver(event.x(), event.y())) return true;
            hypixel.playDownSound(Minecraft.getInstance().getSoundManager());
            connect(s);
            return false;
        });
    }
}
