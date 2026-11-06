package com.epic60869.sky2m.features.profileviewer;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /s2 weight [player]: Sky2M Mining and Farming Weight of a player's selected profile, hover a line for its breakdown. */
public final class Sky2MWeightCommand {
    private Sky2MWeightCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return literal("weight")
            .executes(c -> {
                var player = Minecraft.getInstance().player;
                return player == null ? 0 : show(player.getGameProfile().name());
            })
            .then(argument("player", StringArgumentType.word()).executes(c -> show(StringArgumentType.getString(c, "player"))));
    }

    private static int show(String name) {
        Sky2MAlerts.chat(Component.literal("Loading " + name + "'s weight...").withStyle(ChatFormatting.GRAY));
        ProfileApi.player(name).whenComplete((player, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null || player == null) {
                Sky2MAlerts.chat(Component.literal("Couldn't load " + name + ": " + ProfileApi.reason(error)).withStyle(ChatFormatting.RED));
                return;
            }
            ProfileApi.Profile profile = null;
            for (ProfileApi.Profile p : player.profiles()) if (profile == null || p.selected()) profile = p;
            if (profile == null || profile.member() == null) {
                Sky2MAlerts.chat(Component.literal(player.name() + " has no SkyBlock profile.").withStyle(ChatFormatting.RED));
                return;
            }
            Sky2MAlerts.chat(Component.literal(player.name() + " (" + profile.cuteName() + ")").withStyle(ChatFormatting.GOLD));
            Sky2MAlerts.chat(line("Mining Weight", Sky2MWeight.mining(profile.member())));
            Sky2MAlerts.chat(line("Farming Weight", Sky2MWeight.farming(profile.member())));
        }));
        return 1;
    }

    private static Component line(String label, Sky2MWeight.Result r) {
        MutableComponent hover = Component.literal("Sky2M " + label).withStyle(ChatFormatting.GOLD);
        for (Sky2MWeight.Part p : r.parts()) {
            hover.append(Component.literal("\n" + p.name() + ": " + String.format(Locale.US, "%,.1f", p.weight())
                + " (" + String.format(Locale.US, "%,.0f", p.amount()) + ")").withStyle(ChatFormatting.GRAY));
        }
        return Component.literal(" " + label + ": ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,.1f", r.total())).withStyle(ChatFormatting.YELLOW))
            .withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)));
    }
}
