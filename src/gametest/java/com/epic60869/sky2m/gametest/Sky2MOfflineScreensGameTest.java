package com.epic60869.sky2m.gametest;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MHudEditorScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Applies every Sky2M mixin (a Mixin audit loads each target class), then opens Sky2M's screens that work offline in a
 * test world and screenshots each one: /s2 gui with every HUD turned on, the Item List over the inventory, /s2 rng,
 * /s2 achievements, /s2 whatsnew and the other screens below. Fails if Sky2M logs an error or an exception while it
 * runs. Screenshots land in build/run/clientGameTest/screenshots as "offline-NN-...".
 */
public class Sky2MOfflineScreensGameTest implements FabricClientGameTest {
    /** /s2 sub-commands that open a screen (or print something) without Hypixel or the Sky2M server. */
    private static final List<String> COMMANDS = List.of("rng", "achievements", "whatsnew", "huds", "perf", "keybinds",
        "notes", "search", "find", "runs", "runsummary", "deathrecap", "replay", "chesthistory", "screenshots",
        "networth", "portfolio", "collections", "helditem", "custom", "keys", "itemnotify", "session", "waypoints",
        "storageoverview", "termism", "log", "setup", "trophyfish", "rngadvice", "health", "keyconflicts", "errors");

    private static final List<String> PROBLEMS = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ErrorWatch watch = new ErrorWatch();
        watch.attach();
        // Settings back to their defaults, so earlier runs (the other tests use Disable All) don't hide anything.
        ctx.runOnClient(mc -> {
            if (!Sky2MConfig.importJson(new com.google.gson.JsonObject())) throw new AssertionError("Couldn't reset the settings");
            Sky2MConfig.current().inventory.itemList.enabled = true;
            Sky2MConfig.current().inventory.itemList.outsideSkyblock = true;
            // Show the items without searching first (the default hides them until you search).
            Sky2MConfig.current().inventory.itemList.hideWhen = com.epic60869.sky2m.features.FeatureConfigs.ItemList.HideWhen.NEVER;
        });
        checkMigrationTargets();
        // Every mixin target class, so a target, injection point or shadow that 26.3 doesn't have shows up now.
        ctx.runOnClient(mc -> org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit());
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            ctx.waitTicks(60);
            shot(ctx, "offline-00-world");

            try {
                hudEditor(ctx);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            itemList(ctx);

            for (int i = 0; i < COMMANDS.size(); i++) {
                String sub = COMMANDS.get(i);
                ctx.runOnClient(mc -> mc.player.connection.sendCommand("s2 " + sub));
                ctx.waitTicks(30);
                String screen = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName());
                System.out.println("[Sky2M gametest] /s2 " + sub + " -> screen " + screen);
                if (screen.equals("none")) {
                    // Chat-only commands: show what they printed.
                    ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
                    ctx.waitTicks(3);
                }
                shot(ctx, String.format("offline-%02d-%s", i + 10, sub));
                // Escape first: the storage overview only lets itself be closed that way.
                ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
                ctx.waitTicks(2);
                ctx.setScreen(() -> null);
                ctx.waitTicks(5);
            }

            Sky2MFinalChecks.run(ctx, world);
        }
        watch.detach();
        synchronized (PROBLEMS) {
            if (!PROBLEMS.isEmpty()) throw new AssertionError("Sky2M logged " + PROBLEMS.size() + " error(s):\n" + String.join("\n", PROBLEMS));
        }
    }

    /** Every new path in Sky2MConfig's settings-layout migration is a real setting (a renamed field loses the value). */
    private static void checkMigrationTargets() {
        try {
            Class<?> migration = Class.forName("com.epic60869.sky2m.Sky2MConfig$LayoutMigration");
            java.lang.reflect.Field movesField = migration.getDeclaredField("MOVES");
            movesField.setAccessible(true);
            for (String[] move : (String[][]) movesField.get(null)) {
                Class<?> type = Sky2MConfig.class;
                for (String part : move[1].split("\\.")) {
                    try {
                        type = type.getField(part).getType();
                    } catch (NoSuchFieldException e) {
                        throw new AssertionError("Settings migration moves " + move[0] + " to " + move[1] + ", which isn't a setting");
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** /s2 gui with every HUD's setting turned on and "Show: All", then each category. */
    @SuppressWarnings("unchecked")
    private static void hudEditor(ClientGameTestContext ctx) throws ReflectiveOperationException {
        Map<String, BooleanSupplier>[] saved = new Map[1];
        ctx.runOnClient(mc -> {
            var huds = com.epic60869.sky2m.features.core.Sky2MHuds.class;
            var settingsField = huds.getDeclaredField("SETTINGS");
            settingsField.setAccessible(true);
            Map<String, BooleanSupplier> settings = (Map<String, BooleanSupplier>) settingsField.get(null);
            saved[0] = new java.util.HashMap<>(settings);
            for (var element : com.epic60869.sky2m.features.core.Sky2MHuds.elements()) settings.put(element.id(), () -> true);
            System.out.println("[Sky2M gametest] HUDs registered: " + com.epic60869.sky2m.features.core.Sky2MHuds.elements().size());
        });
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("s2 gui"));
        ctx.waitForScreen(Sky2MHudEditorScreen.class);
        ctx.waitTicks(5);
        clickButtonStartingWith(ctx, "Show:");
        ctx.waitTicks(5);
        shot(ctx, "offline-01-hud-editor-all");
        String[] categories = {"Mining", "Dungeons", "Combat", "Pets", "Trackers", "Other"};
        for (int i = 0; i < categories.length; i++) {
            clickButtonStartingWith(ctx, categories[i]);
            ctx.waitTicks(5);
            shot(ctx, "offline-0" + (i + 2) + "-hud-editor-" + categories[i].toLowerCase());
        }
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            var settingsField = com.epic60869.sky2m.features.core.Sky2MHuds.class.getDeclaredField("SETTINGS");
            settingsField.setAccessible(true);
            Map<String, BooleanSupplier> settings = (Map<String, BooleanSupplier>) settingsField.get(null);
            settings.clear();
            settings.putAll(saved[0]);
        });
    }

    /** The Item List beside the inventory (its items come from the repo, so give it time to load). */
    private static void itemList(ClientGameTestContext ctx) {
        // The first run downloads the NEU repo (about 22 MB); later runs load it from disk.
        ctx.waitFor(mc -> {
            com.epic60869.sky2m.features.itemlist.ItemListData.ensureLoaded();
            return com.epic60869.sky2m.features.itemlist.ItemListData.loaded();
        }, 20 * 120);
        ctx.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        ctx.waitTicks(40);
        shot(ctx, "offline-08-item-list");
        // A bigger window, as most players have: the list has room beside the inventory.
        int[] size = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        ctx.getInput().resizeWindow(1920, 1057);
        ctx.waitTicks(40);
        shot(ctx, "offline-09-item-list-large");
        ctx.setScreen(() -> null);
        ctx.getInput().resizeWindow(size[0], size[1]);
        ctx.waitTicks(5);
    }

    private static void clickButtonStartingWith(ClientGameTestContext ctx, String prefix) {
        int[] centre = ctx.computeOnClient(mc -> {
            for (var child : mc.gui.screen().children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
                    && w.getMessage().getString().replace("• ", "").startsWith(prefix)) {
                    return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
                }
            }
            return null;
        });
        if (centre == null) throw new AssertionError("No button starting with " + prefix);
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(centre[0] * scale, centre[1] * scale);
        ctx.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        System.out.println("[Sky2M gametest] screenshot " + name + " -> " + ctx.takeScreenshot(name));
    }

    /** Collects ERROR log lines from Sky2M (or with an exception from Sky2M's code) and Mixin failures. */
    private static final class ErrorWatch extends AbstractAppender {
        ErrorWatch() {
            super("Sky2MGameTestErrors", null, null, true, Property.EMPTY_ARRAY);
        }

        void attach() {
            start();
            ((Logger) LogManager.getRootLogger()).addAppender(this);
        }

        void detach() {
            ((Logger) LogManager.getRootLogger()).removeAppender(this);
            stop();
        }

        @Override
        public void append(LogEvent event) {
            if (!event.getLevel().isMoreSpecificThan(Level.ERROR) && !event.getLevel().equals(Level.WARN)) return;
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            Throwable thrown = event.getThrown();
            StringBuilder trace = new StringBuilder();
            for (Throwable t = thrown; t != null; t = t.getCause()) {
                trace.append(t).append('\n');
                for (StackTraceElement e : t.getStackTrace()) trace.append(e).append('\n');
            }
            String all = event.getLoggerName() + " " + message + " " + trace;
            boolean ours = all.contains("sky2m") || all.contains("Sky2M") || all.contains("epic60869");
            boolean mixin = all.contains("Mixin") || all.contains("mixin") || all.contains("Injection");
            boolean error = event.getLevel().isMoreSpecificThan(Level.ERROR);
            // Warnings only count when they're a Mixin problem in a Sky2M mixin (e.g. an injector that matched nothing).
            if (ours && (error || mixin) && !(event.getLevel().equals(Level.WARN) && !mixin)) {
                String line = "[" + event.getLevel() + "] " + event.getLoggerName() + ": " + message
                    + (thrown == null ? "" : " (" + thrown + ")");
                System.out.println("[Sky2M gametest] problem: " + line);
                synchronized (PROBLEMS) {
                    PROBLEMS.add(line);
                }
            }
        }
    }
}
