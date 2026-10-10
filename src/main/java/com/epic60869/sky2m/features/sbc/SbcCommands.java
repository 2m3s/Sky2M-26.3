package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** /s2 help and the /s2 commands of the Sky2M Online features. */
public final class SbcCommands {
    private record Help(String usage, String description) {}

    private record Section(String title, List<Help> entries) {}

    private static final List<Section> HELP = List.of(
        new Section("General", List.of(
            new Help("/s2", "Open the Sky2M settings"),
            new Help("/s2 help", "This list"),
            new Help("/s2 gui", "Move and resize the HUDs"),
            new Help("/s2 toggle <setting>", "Turn a setting on or off"),
            new Help("/s2 disableall", "Turn every feature off"),
            new Help("/s2 notes", "Your notes"),
            new Help("/s2 search", "Search your storage (or press O)"),
            new Help("/s2 calc <sum>", "Calculator"),
            new Help("/s2 recipe <item>", "Show an item's recipe"),
            new Help("/s2 keys", "Command keys"),
            new Help("/s2 custom", "Item and armor customization"),
            new Help("/s2 discord", "The Sky2M Discord"),
            new Help("/s2 bugreport | suggest | feedback", "Send a report to the Sky2M team"))),
        new Section("Sky2M Chat", List.of(
            new Help("/s2c [message]", "Send to Sky2M chat (no message: switch to the Sky2M channel)"),
            new Help("/s2 chat leave", "Leave the Sky2M channel"),
            new Help("/s2 reply <id>", "Reply to a message (or click ↩ after it)"),
            new Help("/s2 share", "Share the item you're holding (or the Share Item key; [item] in a message)"),
            new Help("/s2 react <id> <emoji>", "React to a message (or hover it with chat open)"),
            new Help("/s2 ignore <player> | discord <name> | list", "Stop seeing someone's messages"),
            new Help("/s2 unignore <player> | discord <name>", "See their messages again"),
            new Help("/s2 nick", "Your nickname"))),
        new Section("Players", List.of(
            new Help("/s2 who", "Who's online with Sky2M"),
            new Help("/s2 friend add|remove|accept|deny <player>", "Manage Sky2M friends"),
            new Help("/s2 friend list", "Your friends (or /s2 friends)"),
            new Help("/s2 cosmetics", "Pick your badge and cape"))),
        new Section("Leaderboards", List.of(
            new Help("/s2 leaderboard", "Sky2M leaderboards"))),
        new Section("SkyBlock", List.of(
            new Help("/s2 calendar", "Upcoming SkyBlock events"),
            new Help("/s2 portfolio", "Your portfolio"),
            new Help("/s2 collections", "How much your collections went up"),
            new Help("/s2 trackcollection", "Track a collection"),
            new Help("/s2 itemnotify", "Item notifications"),
            new Help("/s2 helditem", "Held item editor"),
            new Help("/s2 waypoints", "Waypoints"))),
        new Section("Settings Sync", List.of(
            new Help("/s2 settings upload [slot]", "Save your settings to the Sky2M server"),
            new Help("/s2 settings download [slot]", "Load saved settings"),
            new Help("/s2 settings list", "Your saved slots"),
            new Help("/s2 settings delete <slot>", "Delete a slot"))));

    private SbcCommands() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) dispatcher.register(tree(root));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> tree(String root) {
        return ClientCommands.literal(root)
            .then(ClientCommands.literal("help").executes(c -> help()))
            .then(ClientCommands.literal("cosmetics").executes(c -> run(SbcCosmetics::openScreen)))
            .then(ClientCommands.literal("settings")
                .executes(c -> {
                    SbcSettingsSync.list();
                    return 1;
                })
                .then(ClientCommands.literal("upload")
                    .executes(c -> run(() -> SbcSettingsSync.upload(null)))
                    .then(ClientCommands.argument("slot", StringArgumentType.word())
                        .executes(c -> run(() -> SbcSettingsSync.upload(StringArgumentType.getString(c, "slot"))))))
                .then(ClientCommands.literal("download")
                    .executes(c -> run(() -> SbcSettingsSync.download(null)))
                    .then(ClientCommands.argument("slot", StringArgumentType.word())
                        .executes(c -> run(() -> SbcSettingsSync.download(StringArgumentType.getString(c, "slot"))))))
                .then(ClientCommands.literal("list").executes(c -> run(SbcSettingsSync::list)))
                .then(ClientCommands.literal("delete")
                    .then(ClientCommands.argument("slot", StringArgumentType.word())
                        .executes(c -> run(() -> SbcSettingsSync.delete(StringArgumentType.getString(c, "slot")))))));
    }

    /** Runs a command's action, reporting (instead of throwing) anything that goes wrong. */
    static int run(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            SbcCrashReports.report(e, "command");
            Sbc.error("Something went wrong: " + e.getMessage());
        }
        return 1;
    }

    private static int help() {
        MutableComponent out = Component.literal("Sky2M commands").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(" (click one to type it)").withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false)));
        for (Section section : HELP) {
            out.append(Component.literal("\n" + section.title()).withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false)));
            for (Help help : section.entries()) {
                String typed = help.usage().replaceFirst("\\s*[<\\[|].*$", "").trim() + " ";
                out.append(Component.literal("\n  " + help.usage()).withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(false)
                        .withClickEvent(new ClickEvent.SuggestCommand(typed))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(help.description())))))
                    .append(Component.literal(" - " + help.description()).withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false)));
            }
        }
        Sbc.say(out);
        return 1;
    }
}
