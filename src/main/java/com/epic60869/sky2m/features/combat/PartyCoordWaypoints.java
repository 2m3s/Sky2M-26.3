package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diana > Party Coord Waypoints, like SkyHanni's Patcher coords waypoints: coordinates in party chat ("x: -30, y: 87,
 * z: 126", maybe with "| something" after) become a waypoint with the sender's name and distance, until you reach it
 * or for a minute. Rare mob shares are left to {@link DianaRareMobs}, which gives them their own waypoint.
 */
public final class PartyCoordWaypoints {
    private static final Pattern PARTY_LINE = Pattern.compile("^Party > (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>[A-Za-z0-9_]{1,16})[^:]*: (?<body>.+)$");
    private static final Pattern COORDS = Pattern.compile("x: ?(?<x>-?\\d+(?:\\.\\d+)?),? y: ?(?<y>-?\\d+(?:\\.\\d+)?),? z: ?(?<z>-?\\d+(?:\\.\\d+)?)(?: ?\\| ?(?<note>.+))?", Pattern.CASE_INSENSITIVE);
    private static final long LIFETIME_MS = 60_000L;
    private static final double REACHED = 5.0;
    private static final float[] COLOUR = {1f, 0.67f, 0f};

    private record Waypoint(String sender, String note, Vec3 pos, long expiresAt) {}

    private static final List<Waypoint> WAYPOINTS = new CopyOnWriteArrayList<>();

    private PartyCoordWaypoints() {}

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.mayors.diana.partyCoordWaypoints;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> WAYPOINTS.clear());
        Sky2MChat.onChat(message -> {
            if (!enabled()) return;
            Matcher party = PARTY_LINE.matcher(message.text().trim());
            if (!party.matches()) return;
            String sender = party.group("name");
            if (sender.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) return;
            String body = party.group("body").trim();
            if (DianaRareMobs.isRareMobShare(body)) return;
            Matcher coords = COORDS.matcher(body);
            if (!coords.find()) return;
            try {
                Vec3 pos = new Vec3(Double.parseDouble(coords.group("x")), Double.parseDouble(coords.group("y")), Double.parseDouble(coords.group("z")));
                String note = coords.group("note") == null ? "" : coords.group("note").trim();
                // One waypoint per player: a new one replaces their last.
                WAYPOINTS.removeIf(w -> w.sender().equalsIgnoreCase(sender));
                WAYPOINTS.add(new Waypoint(sender, note, pos, System.currentTimeMillis() + LIFETIME_MS));
            } catch (NumberFormatException ignored) {}
        });
        Sky2MWorldRender.register(collector -> {
            if (WAYPOINTS.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !enabled()) return;
            long now = System.currentTimeMillis();
            WAYPOINTS.removeIf(w -> now > w.expiresAt() || mc.player.position().distanceTo(w.pos()) < REACHED);
            Vec3 camera = mc.gameRenderer.mainCamera().position();
            for (Waypoint w : WAYPOINTS) {
                BlockPos block = BlockPos.containing(w.pos());
                collector.submitFilledBoxWithBeaconBeam(block, COLOUR, 0.5f, true);
                Vec3 label = Vec3.atBottomCenterOf(block).add(0, 1.8, 0);
                double distance = camera.distanceTo(label);
                float scale = (float) Math.max(1, distance / 7);
                collector.submitText(Component.literal(w.sender()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                    .append(Component.literal(w.note().isEmpty() ? "" : " | " + w.note()).withStyle(ChatFormatting.YELLOW)),
                    label.add(0, 0.3 * scale, 0), scale, true);
                collector.submitText(Component.literal(String.format(Locale.US, "%.0fm", mc.player.position().distanceTo(w.pos())))
                    .withStyle(ChatFormatting.GRAY), label, scale * 0.8f, true);
            }
        });
    }
}
