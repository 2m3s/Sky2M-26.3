package com.epic60869.sky2m.gametest;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MHudEditorScreen;
import com.epic60869.sky2m.Sky2MWhatsNewScreen;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks for the 1.0.3 changes, run inside {@link Sky2MOfflineScreensGameTest}'s world (so its error watch covers
 * them): a Discord bridge message through the real chat handler, /s2 mp being gone, the Inventory HUD in both styles,
 * the language file's text, /s2 whatsnew, the event calendar, /s2 keybinds with a hovered row, and importing empty
 * settings before opening /s2 gui. Screenshots are "final-NN-...".
 */
final class Sky2MFinalChecks {
    private Sky2MFinalChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext world) {
        discordMessage(ctx);
        mpRemoved(ctx);
        inventoryHud(ctx, world);
        language(ctx);
        screens(ctx);
        importEmptyThenGui(ctx);
    }

    /** A message from the Discord bridge, as the relay sends it, through Sky2MGlobalChat's own packet handler. */
    private static void discordMessage(ClientGameTestContext ctx) {
        String raw = "{\"type\":\"message\",\"id\":1,\"at\":" + System.currentTimeMillis() + ",\"source\":\"discord\","
            + "\"username\":\"[Discord] Bob\",\"minecraftUuid\":\"\",\"nickname\":\"[Discord] Bob\",\"nicknameEnabled\":false,"
            + "\"nicknameMode\":\"Plain\",\"nicknameHex\":\"\",\"nicknameFont\":\"Default\",\"message\":\"hello from discord\",\"mentions\":[]}";
        try {
            Class<?> listener = Class.forName("com.epic60869.sky2m.Sky2MGlobalChat$Listener");
            var constructor = listener.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            var handle = listener.getDeclaredMethod("handle", String.class);
            handle.setAccessible(true);
            // The socket thread calls it (the line reaches chat through Minecraft.execute); a thread of its own here,
            // since the gametest thread may not call Minecraft.getInstance().
            Throwable[] failed = new Throwable[1];
            Thread socket = new Thread(() -> {
                try {
                    handle.invoke(instance, raw);
                } catch (Throwable t) {
                    failed[0] = t;
                }
            }, "Sky2M gametest socket");
            socket.start();
            socket.join(10_000);
            if (failed[0] != null) throw new AssertionError("The message handler threw", failed[0]);
        } catch (ReflectiveOperationException | InterruptedException e) {
            throw new AssertionError("Couldn't call Sky2MGlobalChat's message handler", e);
        }
        ctx.waitTicks(5);
        GuiMessage found = ctx.computeOnClient(mc -> {
            for (GuiMessage m : allMessages()) if (m.content().getString().contains("hello from discord")) return m;
            return null;
        });
        if (found == null) {
            diagnose(ctx, raw);
            throw new AssertionError("The Discord message didn't reach chat");
        }
        String text = found.content().getString();
        System.out.println("[Sky2M gametest] discord line: \"" + text + "\"");
        String expected = "[S2M] [Discord] [Bob]: hello from discord";
        // The reply arrow (" ↩") is added after the text when replies are on.
        if (!text.equals(expected) && !text.equals(expected + " ↩")) {
            throw new AssertionError("Discord line is \"" + text + "\", expected \"" + expected + "\"");
        }
        TextColor[] bob = new TextColor[1];
        found.content().visit((style, part) -> {
            if (part.equals("Bob")) bob[0] = style.getColor();
            return Optional.empty();
        }, Style.EMPTY);
        System.out.println("[Sky2M gametest] discord sender colour: " + bob[0]);
        if (bob[0] == null || !bob[0].equals(TextColor.fromLegacyFormat(ChatFormatting.BLUE))) {
            throw new AssertionError("\"Bob\" isn't blue: " + bob[0]);
        }
        openChat(ctx);
        shot(ctx, "final-01-discord-chat");
        ctx.setScreen(() -> null);
        ctx.waitTicks(3);
    }

    /** Prints each step of the chat handler's path for this packet, to show where it stopped. */
    private static void diagnose(ClientGameTestContext ctx, String raw) {
        ctx.runOnClient(mc -> {
            JsonObject packet = JsonParser.parseString(raw).getAsJsonObject();
            List<String> recent = new ArrayList<>();
            for (GuiMessage m : allMessages()) if (recent.size() < 6) recent.add(m.content().getString());
            System.out.println("[Sky2M gametest] diag recent chat: " + recent);
            System.out.println("[Sky2M gametest] diag flag chat=" + com.epic60869.sky2m.features.sbc.Flags.isEnabled("chat")
                + " showSjChat=" + Sky2MConfig.current().chat.customChat.showSjChat
                + " muted=" + com.epic60869.sky2m.features.sbc.SbcRooms.isMuted("[Discord] Bob", ""));
            String[] steps = {"nickFilter", "nick", "emojis", "prefix", "decorate"};
            for (String step : steps) {
                try {
                    Object out = switch (step) {
                        case "nickFilter" -> com.epic60869.sky2m.Sky2MNickFilter.isBlocked("[Discord] Bob", null);
                        case "nick" -> com.epic60869.sky2m.Sky2MNick.displayName(null, "[Discord] Bob");
                        case "emojis" -> com.epic60869.sky2m.features.misc.ItemEmojis.replace(com.epic60869.sky2m.Sky2MNopoFeatures.replaceChatEmojis(net.minecraft.network.chat.Component.literal("hello from discord")));
                        case "prefix" -> com.epic60869.sky2m.features.sbc.SbcChat.prefix(1);
                        default -> com.epic60869.sky2m.features.sbc.SbcChat.decorate(packet, net.minecraft.network.chat.Component.literal("x"), "hello from discord");
                    };
                    System.out.println("[Sky2M gametest] diag " + step + " ok: " + out);
                } catch (Throwable t) {
                    System.out.println("[Sky2M gametest] diag " + step + " threw " + t);
                    t.printStackTrace(System.out);
                }
            }
        });
    }

    /** /s2 mp was removed: no such client command, and running it says so. */
    private static void mpRemoved(ClientGameTestContext ctx) {
        boolean registered = ctx.computeOnClient(mc -> {
            var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher();
            if (dispatcher == null) return false;
            for (String root : new String[]{"s2", "s2m"}) {
                var node = dispatcher.getRoot().getChild(root);
                if (node != null && node.getChild("mp") != null) return true;
            }
            return false;
        });
        if (registered) throw new AssertionError("/s2 mp is still registered");
        int before = ctx.computeOnClient(mc -> allMessages().size());
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("s2 mp"));
        ctx.waitTicks(10);
        List<String> added = ctx.computeOnClient(mc -> {
            List<GuiMessage> all = allMessages();
            List<String> lines = new ArrayList<>();
            // Newest first.
            for (int i = 0; i < Math.max(0, all.size() - before) && i < all.size(); i++) lines.add(all.get(i).content().getString());
            return lines;
        });
        System.out.println("[Sky2M gametest] /s2 mp printed: " + added);
        if (added.stream().noneMatch(l -> l.toLowerCase().contains("unknown") || l.toLowerCase().contains("incorrect"))) {
            throw new AssertionError("/s2 mp didn't fail as an unknown command: " + added);
        }
        openChat(ctx);
        shot(ctx, "final-02-mp-unknown");
        ctx.setScreen(() -> null);
        ctx.waitTicks(3);
    }

    /** The Inventory HUD with a few items, Dark then Vanilla. */
    private static void inventoryHud(ClientGameTestContext ctx, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().get(0);
            var inventory = player.getInventory();
            inventory.setItem(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 64));
            inventory.setItem(10, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 3));
            inventory.setItem(13, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
            inventory.setItem(20, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 32));
            inventory.setItem(35, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENDER_PEARL, 16));
        });
        ctx.waitTicks(5);
        int items = ctx.computeOnClient(mc -> {
            int n = 0;
            for (int i = 9; i < 36; i++) if (!mc.player.getInventory().getItem(i).isEmpty()) n++;
            return n;
        });
        System.out.println("[Sky2M gametest] items in the client's inventory rows: " + items);
        FeatureConfigs.InventoryHud.Style[] styles = {FeatureConfigs.InventoryHud.Style.DARK, FeatureConfigs.InventoryHud.Style.VANILLA};
        for (int i = 0; i < styles.length; i++) {
            FeatureConfigs.InventoryHud.Style style = styles[i];
            ctx.runOnClient(mc -> {
                var hud = Sky2MConfig.current().gui.inventoryHud;
                hud.enabled = true;
                hud.show = FeatureConfigs.InventoryHud.Show.ALWAYS;
                hud.style = style;
            });
            ctx.waitTicks(10);
            shot(ctx, "final-0" + (3 + i) + "-inventory-hud-" + style.name().toLowerCase());
        }
        ctx.runOnClient(mc -> Sky2MConfig.current().gui.inventoryHud.enabled = false);
    }

    /** No Sky2M translation shows "Â" (text that was read as Latin-1 somewhere). */
    private static void language(ClientGameTestContext ctx) {
        List<String> keys = new ArrayList<>();
        try (var in = Sky2MFinalChecks.class.getResourceAsStream("/assets/sky2m/lang/en_us.json")) {
            if (in == null) throw new AssertionError("en_us.json isn't on the classpath");
            JsonObject lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            keys.addAll(lang.keySet());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        List<String> bad = ctx.computeOnClient(mc -> {
            List<String> found = new ArrayList<>();
            for (String key : keys) {
                String value = net.minecraft.client.resources.language.I18n.get(key);
                if (value.contains("Â") || value.contains("â€")) found.add(key + " = " + value);
            }
            return found;
        });
        String sample = ctx.computeOnClient(mc -> net.minecraft.client.resources.language.I18n.get("skyblocker.dungeons.secrets.markSecretFound", 3));
        System.out.println("[Sky2M gametest] checked " + keys.size() + " translations; sample: " + sample);
        if (!sample.contains("Marked secret #3")) throw new AssertionError("Sky2M's translations aren't loaded: " + sample);
        if (!bad.isEmpty()) throw new AssertionError("Translations with broken characters:\n" + String.join("\n", bad));
    }

    /** /s2 whatsnew, the event calendar, and /s2 keybinds with a row hovered. */
    private static void screens(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("s2 whatsnew"));
        ctx.waitForScreen(Sky2MWhatsNewScreen.class);
        ctx.waitTicks(10);
        shot(ctx, "final-10-whatsnew");
        ctx.setScreen(() -> null);
        ctx.waitTicks(3);

        ctx.runOnClient(mc -> mc.player.connection.sendCommand("s2 calendar"));
        ctx.waitForScreen(com.epic60869.sky2m.features.misc.EventCalendarScreen.class);
        ctx.waitTicks(40);
        shot(ctx, "final-11-calendar");
        ctx.setScreen(() -> null);
        ctx.waitTicks(3);

        ctx.runOnClient(mc -> mc.player.connection.sendCommand("s2 keybinds"));
        ctx.waitForScreen(com.epic60869.sky2m.features.misc.KeybindSheetScreen.class);
        ctx.waitTicks(5);
        // The first row set in the settings (its tooltip has two lines: the path, then "Click to open it in /s2").
        int row = ctx.computeOnClient(mc -> {
            try {
                var screen = mc.gui.screen();
                var shown = screen.getClass().getDeclaredMethod("shown");
                shown.setAccessible(true);
                List<?> rows = (List<?>) shown.invoke(screen);
                for (int i = 0; i < rows.size(); i++) {
                    var where = rows.get(i).getClass().getDeclaredMethod("where");
                    where.setAccessible(true);
                    if ("Settings".equals(where.invoke(rows.get(i)))) return i;
                }
                return -1;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        if (row < 0) throw new AssertionError("/s2 keybinds lists no key set in the settings");
        // Rows start 16 below the headers, which are 60 below the top (10 + 50); 13 per row.
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(80 * scale, (10 + 50 + 16 + row * 13 + 3) * scale);
        ctx.waitTicks(5);
        shot(ctx, "final-12-keybinds-hover");
        ctx.setScreen(() -> null);
        ctx.waitTicks(3);
    }

    /** Importing "{}" (every setting back to default), then /s2 gui opened and closed. */
    private static void importEmptyThenGui(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (!Sky2MConfig.importJson(JsonParser.parseString("{}").getAsJsonObject())) throw new AssertionError("importJson({}) failed");
        });
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("s2 gui"));
        ctx.waitForScreen(Sky2MHudEditorScreen.class);
        ctx.waitTicks(10);
        shot(ctx, "final-20-gui-after-import");
        ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
        ctx.waitTicks(5);
        String screen = ctx.computeOnClient(mc -> String.valueOf(mc.gui.screen()));
        System.out.println("[Sky2M gametest] screen after closing /s2 gui: " + screen);
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
    }

    private static List<GuiMessage> allMessages() {
        return ((com.epic60869.sky2m.commandkeys.mixin.accessor.ChatComponentAccessor) Minecraft.getInstance().gui.hud.getChat())
            .commandkeys$getAllMessages();
    }

    private static void openChat(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
        ctx.waitTicks(3);
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        System.out.println("[Sky2M gametest] screenshot " + name + " -> " + ctx.takeScreenshot(name));
    }
}
