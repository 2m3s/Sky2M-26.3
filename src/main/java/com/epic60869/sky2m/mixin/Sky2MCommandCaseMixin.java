package com.epic60869.sky2m.mixin;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes client commands case-insensitive, so /SJ GUI works like /s2 gui. Each word that matches
 * a command literal ignoring case is replaced by the literal's real spelling; arguments such as
 * chat messages and nicknames are left alone. Runs before Fabric executes client commands
 * (lower priority applies first).
 */
@Mixin(value = ClientPacketListener.class, priority = 500)
public abstract class Sky2MCommandCaseMixin {
    @ModifyVariable(method = "sendCommand", at = @At("HEAD"), argsOnly = true)
    private String sky2m$normaliseCase(String command) {
        // Misc > Warp Shortcuts: /dhub -> /warp dhub, and the Garden's /home, /barn and /tp <plot>.
        return com.epic60869.sky2m.features.misc.WarpShortcuts.rewrite(sky2m$normalise(command));
    }

    @ModifyVariable(method = "sendUnattendedCommand", at = @At("HEAD"), argsOnly = true)
    private String sky2m$normaliseCaseUnattended(String command) {
        return sky2m$normalise(command);
    }

    private static String sky2m$normalise(String command) {
        try {
            var dispatcher = ClientCommands.getActiveDispatcher();
            if (dispatcher == null || command == null || command.isEmpty()) return command;
            String[] words = command.split(" ", -1);
            CommandNode<FabricClientCommandSource> node = dispatcher.getRoot();
            for (int i = 0; i < words.length; i++) {
                CommandNode<FabricClientCommandSource> match = null;
                for (CommandNode<FabricClientCommandSource> child : node.getChildren()) {
                    if (child instanceof LiteralCommandNode<FabricClientCommandSource> literal && literal.getLiteral().equalsIgnoreCase(words[i])) {
                        match = child;
                        break;
                    }
                }
                if (match == null) break;
                words[i] = ((LiteralCommandNode<FabricClientCommandSource>) match).getLiteral();
                node = match.getRedirect() != null ? match.getRedirect() : match;
            }
            return String.join(" ", words);
        } catch (Exception e) {
            return command;
        }
    }
}
