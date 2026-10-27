package com.epic60869.sky2m.features.social;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.misc.chat.PartyCommands;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Waypoints from coordinates in any chat (all, party, guild, officer, co-op and private), extending Diana's party-only
 * Party Coord Waypoints ({@link com.epic60869.sky2m.features.combat.PartyCoordWaypoints}; same "x: 1, y: 2, z: 3 |
 * note" format as SkyHanni and Patcher): a clickable [Waypoint] button on the message (or an automatic waypoint), plus
 * {@code /sendcoords [all|party|guild|officer] [note]} to share where you stand.
 */
public final class ChatWaypoints {
    private static final Pattern SENDER = Pattern.compile(
        "^(?:(?:Party|Guild|Officer|Co-op) > |From |To )?(?:\\[\\d+] )?(?:[^\\w\\s\\[] )?(?:\\[[A-Z+]+] )?(?<name>\\w{3,16})(?: \\[[^]]+])?(?: [^\\w\\s:])?: (?<body>.+)$");
    private static final Pattern LABELLED = Pattern.compile("x: ?(?<x>-?\\d+(?:\\.\\d+)?),? y: ?(?<y>-?\\d+(?:\\.\\d+)?),? z: ?(?<z>-?\\d+(?:\\.\\d+)?)(?: ?\\| ?(?<note>.+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETED = Pattern.compile("\\((?<x>-?\\d{1,6}), ?(?<y>-?\\d{1,4}), ?(?<z>-?\\d{1,6})\\)");
    private static final long LIFETIME_MS = 180_000L;
    private static final double REACHED = 5.0;
    private static final float[] COLOUR = {0.3f, 0.85f, 1f};
    private static final String BUTTON_TEXT = " [Waypoint]";

    private record Waypoint(String sender, String note, Vec3 pos, long expiresAt) {}

    private static final List<Waypoint> WAYPOINTS = new CopyOnWriteArrayList<>();

    private ChatWaypoints() {}

    private static SocialConfig config() {
        return SocialConfig.current();
    }

    /** The sender and coordinates in a chat line, or null. */
    private record Shared(String sender, double x, double y, double z, String note) {}

    private static Shared parse(String text) {
        if (text.startsWith("[NPC]")) return null;
        Matcher sender = SENDER.matcher(text.trim());
        if (!sender.matches()) return null;
        String body = sender.group("body");
        Matcher m = LABELLED.matcher(body);
        boolean labelled = m.find();
        if (!labelled) {
            m = BRACKETED.matcher(body);
            if (!m.find()) return null;
        }
        try {
            String note = labelled && m.group("note") != null ? m.group("note").trim() : "";
            return new Shared(sender.group("name"), Double.parseDouble(m.group("x")), Double.parseDouble(m.group("y")), Double.parseDouble(m.group("z")), note);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> WAYPOINTS.clear());
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : addButton(message));
        Sky2MChat.onChat(message -> {
            SocialConfig c = config();
            if (c == null || !c.chatWaypoints || !c.autoChatWaypoints) return;
            Shared shared = parse(message.text());
            if (shared == null || shared.sender().equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) return;
            // Party chat is Diana > Party Coord Waypoints' while that is on.
            var config = com.epic60869.sky2m.config.Sky2MConfig.current();
            if (message.text().startsWith("Party >") && config != null && config.events.diana.partyCoordWaypoints) return;
            add(shared.sender(), shared.note(), new Vec3(shared.x(), shared.y(), shared.z()));
        });
        Sky2MWorldRender.register(collector -> {
            if (WAYPOINTS.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            long now = System.currentTimeMillis();
            WAYPOINTS.removeIf(w -> now > w.expiresAt() || mc.player.position().distanceTo(w.pos()) < REACHED);
            Vec3 camera = mc.gameRenderer.mainCamera().position();
            for (Waypoint w : WAYPOINTS) {
                BlockPos block = BlockPos.containing(w.pos());
                collector.submitFilledBoxWithBeaconBeam(block, COLOUR, 0.5f, true);
                Vec3 label = Vec3.atBottomCenterOf(block).add(0, 1.8, 0);
                double distance = camera.distanceTo(label);
                float scale = (float) Math.max(1, distance / 7);
                collector.submitText(Component.literal(w.sender()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                    .append(Component.literal(w.note().isEmpty() ? "" : " | " + w.note()).withStyle(ChatFormatting.WHITE)),
                    label.add(0, 0.3 * scale, 0), scale, true);
                collector.submitText(Component.literal(String.format(Locale.US, "%.0fm", mc.player.position().distanceTo(w.pos())))
                    .withStyle(ChatFormatting.GRAY), label, scale * 0.8f, true);
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("chatwaypoint")
                    .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                        .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                            .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                                .then(ClientCommands.argument("sender", StringArgumentType.word())
                                    .executes(c -> {
                                        Vec3 pos = new Vec3(DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z"));
                                        String sender = StringArgumentType.getString(c, "sender");
                                        Minecraft.getInstance().execute(() -> add(sender, "", pos));
                                        return 1;
                                    })))))));
            }
            dispatcher.register(ClientCommands.literal("sendcoords")
                .executes(c -> sendCoords(""))
                .then(ClientCommands.argument("channel and note", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("party", "guild", "officer", "all"), builder))
                    .executes(c -> sendCoords(StringArgumentType.getString(c, "channel and note")))));
        });
    }

    private static void add(String sender, String note, Vec3 pos) {
        // One waypoint per player: a new one replaces their last.
        WAYPOINTS.removeIf(w -> w.sender().equalsIgnoreCase(sender));
        WAYPOINTS.add(new Waypoint(sender, note, pos, System.currentTimeMillis() + LIFETIME_MS));
    }

    /** The message with a clickable [Waypoint] on the end when it shares coordinates. */
    private static Component addButton(Component message) {
        SocialConfig c = config();
        if (c == null || !c.chatWaypoints) return message;
        String text = message.getString();
        if (text.contains(BUTTON_TEXT.trim()) || !text.contains(",") && !text.toLowerCase(Locale.ROOT).contains("x:")) return message;
        Shared shared = parse(com.epic60869.sky2m.features.core.Sky2MLocation.strip(text));
        if (shared == null || shared.sender().equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) return message;
        String where = String.format(Locale.US, "%.0f %.0f %.0f", shared.x(), shared.y(), shared.z());
        Style style = Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("/s2 chatwaypoint " + where + " " + shared.sender()))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Make a waypoint at " + where.replace(' ', ',') + " from " + shared.sender()).withStyle(ChatFormatting.YELLOW)));
        return message.copy().append(Component.literal(BUTTON_TEXT).withStyle(style));
    }

    private static int sendCoords(String args) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            String rest = args.trim();
            String channel = "party";
            String first = rest.split(" ", 2)[0].toLowerCase(Locale.ROOT);
            if (first.equals("party") || first.equals("guild") || first.equals("officer") || first.equals("all")) {
                channel = first;
                rest = rest.length() > first.length() ? rest.substring(first.length()).trim() : "";
            }
            BlockPos pos = mc.player.blockPosition();
            String message = "x: " + pos.getX() + ", y: " + pos.getY() + ", z: " + pos.getZ() + (rest.isEmpty() ? "" : " | " + rest);
            switch (channel) {
                case "party" -> PartyCommands.partyChat(message);
                case "guild" -> mc.player.connection.sendCommand("gc " + message);
                case "officer" -> mc.player.connection.sendCommand("oc " + message);
                default -> mc.player.connection.sendChat(message);
            }
        });
        return 1;
    }
}
