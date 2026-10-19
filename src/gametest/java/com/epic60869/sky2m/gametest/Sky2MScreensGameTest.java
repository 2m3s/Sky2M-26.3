package com.epic60869.sky2m.gametest;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MHudEditorScreen;
import com.epic60869.sky2m.Sky2MWhatsNewScreen;
import com.epic60869.sky2m.features.dungeons.TermismScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Opens screens in a test world and screenshots each one: /s2 whatsnew's three tabs, /s2 termism with Solution on
 * (every terminal), and /s2 gui with the Storage Value box. Run with `gradle runClientGameTest`; screenshots land in
 * build/run/clientGameTest/screenshots.
 */
public class Sky2MScreensGameTest implements FabricClientGameTest {
    /** Rows of each Termism board, in menu order (Melody: 4 rows plus the two edges). */
    private static final int[] BOARD_ROWS = {5, 5, 4, 5, 6, 6};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Keybinds unset the 26.2 way (-1), as in settings carried over from SkyBalls/26.2: on 26.3 this crashed every
        // tick in a world (Hunting's fusion keybinds read the keyboard at index -1).
        ctx.runOnClient(mc -> {
            var h = Sky2MConfig.current().hunting;
            h.fusionKeybinds.repeatFusionKeybind = -1;
            h.fusionKeybinds.confirmFusionKeybind = -1;
            h.nextShulkerKeybind = -1;
            h.shardTracker.selectShardKeybind = -1;
        });
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            ctx.waitTicks(60);
            shot(ctx, "00-world");

            // /s2 whatsnew: the command opens it, then each tab.
            command(ctx, "s2 whatsnew");
            ctx.waitForScreen(Sky2MWhatsNewScreen.class);
            ctx.waitTicks(10);
            shot(ctx, "01-whatsnew-features");
            for (String tab : new String[]{"Changes", "Fixes"}) {
                clickButtonStartingWith(ctx, tab);
                ctx.waitTicks(5);
                shot(ctx, "0" + (tab.equals("Changes") ? 2 : 3) + "-whatsnew-" + tab.toLowerCase());
            }
            // Done closes it.
            clickButtonStartingWith(ctx, "Done");
            ctx.waitTicks(5);
            System.out.println("[Sky2M gametest] screen after clicking Done: " + ctx.computeOnClient(mc -> String.valueOf(mc.gui.screen())));
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);

            disableAllAndStorageValue(ctx);

            termism(ctx);

            try {
                profileViewerFishing(ctx);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** /s2disableall typed in chat, a click on its [Click to confirm], then the settings searched for "Storage Value". */
    private static void disableAllAndStorageValue(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
        ctx.waitTicks(3);
        ctx.getInput().typeChars("/s2disableall");
        ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
        ctx.waitTicks(5);
        shot(ctx, "30-disableall-asked");
        // The red [Click to confirm] at the end of the newest chat line.
        click(ctx, 240, 187);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
        ctx.waitTicks(3);
        shot(ctx, "31-disableall-confirmed");
        ctx.setScreen(() -> null);
        ctx.waitTicks(3);
        System.out.println("[Sky2M gametest] after disable all: whatsNewMessage=" + ctx.computeOnClient(mc -> Sky2MConfig.current().general.whatsNewMessage)
            + " valueBreakdown=" + ctx.computeOnClient(mc -> Sky2MConfig.current().misc.storageOverlaySettings.valueBreakdown));
        command(ctx, "s2");
        ctx.waitTicks(15);
        // The magnifier opens the search box.
        click(ctx, 376, 68);
        ctx.waitTicks(3);
        ctx.getInput().typeChars("Storage Value");
        ctx.waitTicks(10);
        shot(ctx, "32-settings-search");
        // The Misc category, where Storage Value Breakdown is.
        click(ctx, 99, 109);
        ctx.waitTicks(10);
        shot(ctx, "33-settings-misc");
        // Open the "Inventory & Storage" group.
        click(ctx, 280, 99);
        ctx.waitTicks(10);
        shot(ctx, "34-settings-inventory-storage");
        // Open "Storage Overlay" inside it.
        click(ctx, 290, 124);
        ctx.waitTicks(10);
        shot(ctx, "35-settings-storage-overlay");
        // The Storage Value Breakdown switch: off after Disable All, clicked on.
        click(ctx, 232, 168);
        ctx.waitTicks(5);
        shot(ctx, "36-storage-value-on");
        System.out.println("[Sky2M gametest] Storage Value Breakdown after clicking its switch: "
            + ctx.computeOnClient(mc -> Sky2MConfig.current().misc.storageOverlaySettings.valueBreakdown));
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
        // /s2 gui: with everything else off, the Storage Value box should be the one HUD listed.
        command(ctx, "s2 gui");
        ctx.waitForScreen(Sky2MHudEditorScreen.class);
        ctx.waitTicks(10);
        shot(ctx, "37-hud-editor-storage-value");
        // Drag it from its default place to the middle of the screen.
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int left = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
        // Grab it at its left edge, below the top row of filter buttons (they start 44 to the right).
        int[] at = ctx.computeOnClient(mc -> {
            var p = com.epic60869.sky2m.features.core.Sky2MHuds.placement("storage_value");
            return new int[]{p.x, p.y};
        });
        System.out.println("[Sky2M gametest] Storage Value position before dragging: " + at[0] + "," + at[1]);
        int grabX = at[0] + 20, grabY = at[1] + 30;
        ctx.getInput().setCursorPos(grabX * scale, grabY * scale);
        ctx.waitTicks(2);
        ctx.getInput().holdMouse(left);
        ctx.waitTicks(2);
        for (int step = 1; step <= 10; step++) {
            ctx.getInput().setCursorPos((grabX + step * 20) * scale, (grabY + step * 12) * scale);
            ctx.waitTick();
        }
        ctx.getInput().releaseMouse(left);
        ctx.waitTicks(3);
        shot(ctx, "38-hud-editor-storage-value-moved");
        System.out.println("[Sky2M gametest] Storage Value position after dragging: " + ctx.computeOnClient(mc -> {
            var p = com.epic60869.sky2m.features.core.Sky2MHuds.placement("storage_value");
            return p.x + "," + p.y;
        }));
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
    }

    /** /pv 2m3s, then the Fishing tab's three views. */
    private static void profileViewerFishing(ClientGameTestContext ctx) throws ReflectiveOperationException {
        ctx.setScreen(() -> new com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen("2m3s"));
        ctx.waitTicks(400);   // the profile is fetched in the background
        String[] views = {"overview", "gear", "bestiary"};
        for (int view = 0; view < views.length; view++) {
            int v = view;
            ctx.runOnClient(mc -> {
                var screen = mc.gui.screen();
                Class<?> c = screen.getClass();
                java.lang.reflect.Field page = c.getDeclaredField("page");
                page.setAccessible(true);
                Class<?> pageType = page.getType();
                for (Object constant : pageType.getEnumConstants()) if (((Enum<?>) constant).name().equals("FISHING")) page.set(screen, constant);
                java.lang.reflect.Field sub = c.getDeclaredField("subView");
                sub.setAccessible(true);
                sub.setInt(screen, v);
            });
            ctx.waitTicks(view == 2 ? 100 : 20);
            shot(ctx, "50-pv-fishing-" + views[view]);
        }
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
    }

    private static void termism(ClientGameTestContext ctx) {
        ctx.setScreen(TermismScreen::new);
        ctx.waitTicks(5);
        int width = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int height = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        int x = (width - 220) / 2;
        int y = height / 2 - 110;
        // Solution: Off -> On.
        click(ctx, x + 166, y + 168);
        ctx.waitTicks(3);
        shot(ctx, "10-termism-menu");
        String[] names = {"panes", "rubix", "numbers", "startswith", "select", "melody"};
        for (int i = 0; i < names.length; i++) {
            click(ctx, x + 110, y + 24 + i * 22 + 9);
            ctx.waitTicks(i == 5 ? 12 : 4);
            shot(ctx, "1" + (i + 1) + "-termism-" + names[i]);
            // Back to the menu with its Menu button (below the board, right of New and Random).
            int boardH = BOARD_ROWS[i] * 18 + 24;
            int bx = (width - 176) / 2;
            int by = (height - boardH) / 2 - 20 + boardH + 6;
            click(ctx, bx + 12 + 2 * 53 + 26, by + 38);
            ctx.waitTicks(3);
        }
        ctx.setScreen(() -> null);
        ctx.waitTicks(3);
    }

    /** Clicks the screen's button whose label starts with {@code prefix} with the mouse, at its centre. */
    private static void clickButtonStartingWith(ClientGameTestContext ctx, String prefix) {
        int[] centre = ctx.computeOnClient(mc -> {
            for (var child : mc.gui.screen().children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().startsWith(prefix)) {
                    return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
                }
            }
            return null;
        });
        if (centre == null) throw new AssertionError("No button starting with " + prefix);
        click(ctx, centre[0], centre[1]);
    }

    private static void command(ClientGameTestContext ctx, String command) {
        ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
    }

    /** Clicks at a GUI-scaled position. */
    private static void click(ClientGameTestContext ctx, double guiX, double guiY) {
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(guiX * scale, guiY * scale);
        ctx.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        System.out.println("[Sky2M gametest] screenshot " + name + " -> " + ctx.takeScreenshot(name));
    }
}
