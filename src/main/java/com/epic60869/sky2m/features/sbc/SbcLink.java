package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;

/**
 * /s2 link: asks the Sky2M server for a one-time code that links your Minecraft account to your browser on
 * shadowisabot.com/link, where you can make your shared session history public. /s2 link delete removes every stored
 * session; /s2 link unlink also removes the link. Uploading sessions is {@code SessionShare}.
 */
public final class SbcLink {
    private static final String FALLBACK_URL = "https://shadowisabot.com/link";
    private static boolean hintedNotLinked;
    private static boolean showStatus;

    private SbcLink() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("link")
                    .executes(c -> {
                        SbcNet.sendAuthed(Sbc.packet("linkCode"));
                        return 1;
                    })
                    .then(ClientCommands.literal("status").executes(c -> {
                        showStatus = true;
                        SbcNet.sendAuthed(Sbc.packet("linkStatus"));
                        return 1;
                    }))
                    .then(ClientCommands.literal("delete").executes(c -> {
                        SbcNet.sendAuthed(Sbc.packet("linkDelete"));
                        return 1;
                    }))
                    .then(ClientCommands.literal("unlink").executes(c -> {
                        JsonObject packet = Sbc.packet("linkDelete");
                        packet.addProperty("unlink", true);
                        SbcNet.sendAuthed(packet);
                        return 1;
                    }))));
            }
        });
    }

    /** Packets from the server: linkCodeResult, linkStatus, linkDeleteResult, trackerSessionsResult, linkError. */
    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "linkCodeResult" -> {
                if (!Sbc.bool(packet, "ok")) {
                    Sbc.error(Sbc.str(packet, "message").isEmpty() ? "Couldn't make a link code." : Sbc.str(packet, "message"));
                    return;
                }
                showCode(Sbc.str(packet, "code"), Sbc.str(packet, "url"));
            }
            case "linkStatus" -> {
                if (!showStatus) return;
                showStatus = false;
                boolean linked = Sbc.bool(packet, "linked");
                if (!linked) {
                    Sbc.say(Component.literal("Not linked. Run /s2 link to link your account.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                Sbc.say(Component.literal("Linked. " + Sbc.lng(packet, "count", 0) + " session(s) stored, "
                    + (Sbc.bool(packet, "public") ? "public" : "private") + ". Manage it at shadowisabot.com/link.").withStyle(ChatFormatting.GREEN));
            }
            case "linkDeleteResult" -> {
                long deleted = Sbc.lng(packet, "deleted", 0);
                Sbc.say(Component.literal("Deleted " + deleted + " stored session(s)" + (Sbc.bool(packet, "unlinked") ? " and unlinked your account." : "."))
                    .withStyle(ChatFormatting.GREEN));
            }
            case "trackerSessionsResult" -> {
                if (!Sbc.bool(packet, "ok") && "notLinked".equals(Sbc.str(packet, "code")) && !hintedNotLinked) {
                    hintedNotLinked = true;
                    Sbc.say(Component.literal("Session sharing is on, but your account isn't linked yet. Run /s2 link to link it.").withStyle(ChatFormatting.YELLOW));
                }
            }
            case "linkError" -> {
                // Background uploads can race the login; only report errors the player asked for.
                if (!"trackerSessions".equals(Sbc.str(packet, "request"))) Sbc.error(Sbc.str(packet, "message"));
            }
            default -> { }
        }
    }

    private static void showCode(String code, String url) {
        if (code.isEmpty()) return;
        String site = url.startsWith("https://") ? url : FALLBACK_URL;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.keyboardHandler.setClipboard(code));
        MutableComponent codeText = Component.literal(code).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(code))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy").withStyle(ChatFormatting.YELLOW))));
        MutableComponent link = Component.literal(site).withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
        try {
            URI uri = URI.create(site);
            link = link.withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(uri))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to open").withStyle(ChatFormatting.YELLOW))));
        } catch (IllegalArgumentException ignored) {
            // Shown as plain text.
        }
        Sbc.say(Component.literal("Link code: ").withStyle(ChatFormatting.GRAY).append(codeText)
            .append(Component.literal(" (copied; valid 10 minutes)").withStyle(ChatFormatting.GRAY)));
        Sbc.say(Component.literal("Paste it at ").withStyle(ChatFormatting.GRAY).append(link)
            .append(Component.literal(", then turn on Share Sessions to Website in the Session Tracker settings.").withStyle(ChatFormatting.GRAY)));
    }
}
