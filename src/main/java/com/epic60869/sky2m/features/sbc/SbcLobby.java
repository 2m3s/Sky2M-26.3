package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Lobby Finder: which Sky2M players are in your lobby (the same Hypixel server) and who else is on your island in
 * another lobby, from the Sky2M online list ({@link SbcSocial}). Only players who share their location with you
 * show. /s2 lobby lists them in chat, each name clickable to invite to your party; the Lobby Finder HUD keeps it on
 * screen. Handy for Diana and Inquisitor lootshare, or finding friends.
 */
public final class SbcLobby {
    private static final long REFRESH_MS = 30_000L;
    private static long refreshedAt;
    /** Set by /s2 lobby: show the list in chat once the fresh one arrives. */
    private static long showAfter;

    private SbcLobby() {}

    record Here(List<SbcSocial.Player> lobby, List<SbcSocial.Player> island, String server) {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("lobby").executes(c -> {
                    SbcSocial.requestWho(false);
                    showAfter = System.currentTimeMillis();
                    return 1;
                })));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(SbcLobby::tick);
        Sky2MHuds.register("sky2m_lobby", "Lobby Finder", () -> Sbc.config().social.lobbyHud, SbcLobby::hudLines,
            List.of(Component.literal("Sky2M in your lobby (2)").withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.literal(" 2m3s ").withStyle(ChatFormatting.WHITE).append(Component.literal("Hub").withStyle(ChatFormatting.GRAY)),
                Component.literal(" poke174 ").withStyle(ChatFormatting.WHITE).append(Component.literal("Hub").withStyle(ChatFormatting.GRAY)),
                Component.literal("+3 elsewhere on this island").withStyle(ChatFormatting.DARK_GRAY)), 4, 220);
    }

    private static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        // The HUD keeps the list fresh (at most every 30 seconds).
        if (Sbc.config().social.lobbyHud && mc.player != null && now - refreshedAt > REFRESH_MS) {
            refreshedAt = now;
            SbcSocial.requestWho(false);
        }
        if (showAfter > 0 && SbcSocial.onlineAt > showAfter) {
            showAfter = 0;
            say(mc);
        } else if (showAfter > 0 && now - showAfter > 8_000L) {
            showAfter = 0;
            Sbc.error(SbcNet.online() ? "The Sky2M server didn't send the player list; try again in a moment." : SbcNet.OFFLINE);
        }
    }

    /** Who's in your lobby and elsewhere on your island (not you). */
    static Here here() {
        String server = SbcSocial.server();
        String area = Sky2MLocation.area();
        String self = Minecraft.getInstance().getUser().getName();
        List<SbcSocial.Player> lobby = new ArrayList<>(), island = new ArrayList<>();
        for (SbcSocial.Player p : SbcSocial.online) {
            if (!p.online() || p.username().equalsIgnoreCase(self)) continue;
            if (!server.isEmpty() && server.equalsIgnoreCase(p.server())) lobby.add(p);
            else if (area != null && !area.isEmpty() && area.equalsIgnoreCase(p.area())) island.add(p);
        }
        return new Here(lobby, island, server);
    }

    private static List<Component> hudLines() {
        if (!Sky2MLocation.onSkyblock()) return List.of();
        Here here = here();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Sky2M in your lobby (" + here.lobby().size() + ")").withStyle(ChatFormatting.LIGHT_PURPLE));
        for (int i = 0; i < here.lobby().size() && i < 8; i++) {
            SbcSocial.Player p = here.lobby().get(i);
            lines.add(Component.literal(" " + p.username() + " ").withStyle(p.afk() ? ChatFormatting.GRAY : ChatFormatting.WHITE)
                .append(Component.literal(p.afk() ? "AFK" : p.area()).withStyle(ChatFormatting.GRAY)));
        }
        if (here.lobby().size() > 8) lines.add(Component.literal(" +" + (here.lobby().size() - 8) + " more").withStyle(ChatFormatting.GRAY));
        if (!here.island().isEmpty()) lines.add(Component.literal("+" + here.island().size() + " elsewhere on this island").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static void say(Minecraft mc) {
        if (mc.player == null) return;
        Here here = here();
        if (here.server().isEmpty()) {
            Sbc.say(Component.literal("Can't see which lobby you're in (no server on the sidebar).").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Sbc.say(Component.literal("Sky2M players in your lobby (" + here.server() + "): ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(here.lobby().isEmpty() ? Component.literal("nobody else").withStyle(ChatFormatting.GRAY) : names(here.lobby(), false)));
        if (!here.island().isEmpty()) {
            Sbc.say(Component.literal("On this island in other lobbies: ").withStyle(ChatFormatting.GRAY).append(names(here.island(), true)));
        }
        Sbc.say(Component.literal("Click a name to invite them to your party. Only players who share their location show.").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static MutableComponent names(List<SbcSocial.Player> players, boolean withServer) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < players.size(); i++) {
            SbcSocial.Player p = players.get(i);
            if (i > 0) out.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            String where = (p.area().isEmpty() ? "" : p.area()) + (withServer && !p.server().isEmpty() ? " · " + p.server() : "") + (p.afk() ? " · AFK" : "");
            out.append(Component.literal(p.username()).withStyle(style -> style.withColor(p.friend() ? ChatFormatting.GREEN : ChatFormatting.WHITE)
                .withClickEvent(new ClickEvent.SuggestCommand("/party invite " + p.username()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal((where.isEmpty() ? "" : where + "\n") + "Click to invite to your party")))));
        }
        return out;
    }
}
