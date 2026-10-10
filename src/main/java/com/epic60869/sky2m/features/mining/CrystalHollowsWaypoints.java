package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crystal Hollows waypoints, following Skyblocker's CrystalsLocationsManager (LGPL-3.0), without its waypoint
 * sharing server:
 * <ul>
 *     <li>Walking into Mines of Divan, Jungle Temple, Goblin Queen's Den, ... marks it at your position.</li>
 *     <li>NPC and crystal messages (e.g. "[NPC] King Yolkar:") move the waypoint to where you are.</li>
 *     <li>Coordinates in chat ("Divan 512 100 488") become waypoints; without a place name you get a menu to pick one.</li>
 *     <li>/s2 crystalwaypoints add|share|remove|clear.</li>
 * </ul>
 * Waypoints are per lobby and cleared when you leave.
 */
public final class CrystalHollowsWaypoints {
    private static final String SPACER = "                                ";

    enum Place {
        UNKNOWN("Unknown", 0xFFFFFF, null),
        JUNGLE_TEMPLE("Jungle Temple", 0xAA00AA, "[NPC] Kalhuiki Door Guardian:"),
        MINES_OF_DIVAN("Mines of Divan", 0x55FF55, SPACER + "Jade Crystal"),
        GOBLIN_QUEENS_DEN("Goblin Queen's Den", 0xFFAA00, SPACER + "Amber Crystal"),
        LOST_PRECURSOR_CITY("Lost Precursor City", 0x55FFFF, SPACER + "Sapphire Crystal"),
        KHAZAD_DUM("Khazad-dûm", 0xFFFF55, SPACER + "Topaz Crystal"),
        FAIRY_GROTTO("Fairy Grotto", 0xFF55FF, null),
        DRAGONS_LAIR("Dragon's Lair", 0xFFAA00, "[NPC] Golden Dragon:"),
        CORLEONE("Corleone", 0xFFFFFF, null),
        KING_YOLKAR("King Yolkar", 0xFF5555, "[NPC] King Yolkar:"),
        ODAWA("Odawa", 0xFF55FF, "[NPC] Odawa:"),
        KEY_GUARDIAN("Key Guardian", 0xAAAAAA, null),
        XALX("Xalx", 0x55FF55, "[NPC] Xalx:");

        final String label;
        final int colour;
        final String linkedMessage;

        Place(String label, int colour, String linkedMessage) {
            this.label = label;
            this.colour = colour;
            this.linkedMessage = linkedMessage;
        }

        static Place of(String name) {
            for (Place p : values()) if (p.label.equalsIgnoreCase(name.trim())) return p;
            return null;
        }
    }

    /** Skyblocker's coordinate pattern: three numbers with optional x/y/z and separators. */
    private static final Pattern COORDS = Pattern.compile("\\Dx?(\\d{3})(?=[, ]),? ?y?(\\d{2,3})(?=[, ]),? ?z?(\\d{3})\\D?(?!\\d)");
    private static final Map<Place, BlockPos> ACTIVE = new LinkedHashMap<>();
    private static final java.util.Set<Place> VERIFIED = java.util.EnumSet.noneOf(Place.class);
    private static int ticks;

    private CrystalHollowsWaypoints() {}

    private static FeatureConfigs.MiningFeatures config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.features;
    }

    private static boolean enabled() {
        FeatureConfigs.MiningFeatures c = config();
        return c != null && c.crystalWaypoints && Sky2MLocation.inCrystalHollows();
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            ACTIVE.clear();
            VERIFIED.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 40 != 0 || !enabled() || mc.player == null) return;
            Place here = Place.of(Sky2MLocation.location());
            if (here != null && here != Place.UNKNOWN && !ACTIVE.containsKey(here)) add(here, mc.player.blockPosition());
        });
        Sky2MChat.onChat(message -> onChat(message.text()));
        Sky2MWorldRender.register(collector -> {
            if (!enabled()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            for (var entry : ACTIVE.entrySet()) {
                Place place = entry.getKey();
                BlockPos pos = entry.getValue();
                float[] rgb = {(place.colour >> 16 & 255) / 255f, (place.colour >> 8 & 255) / 255f, (place.colour & 255) / 255f};
                collector.submitFilledBoxWithBeaconBeam(pos, rgb, 0.5f, true);
                double distance = mc.player.position().distanceTo(Vec3.atCenterOf(pos));
                Vec3 label = Vec3.atCenterOf(pos).add(0, 1.5, 0);
                collector.submitText(Component.literal(place.label).withColor(place.colour), label, 2f, true);
                collector.submitText(Component.literal(Math.round(distance) + "m").withStyle(ChatFormatting.YELLOW), label.add(0, -0.6, 0), 1.5f, true);
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("crystalwaypoints")
                    .then(ClientCommands.literal("add")
                        .executes(c -> {
                            var p = Minecraft.getInstance().player;
                            if (p != null) Sky2MAlerts.chat(placeMenu(p.blockPosition()));
                            return 1;
                        })
                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                    .then(ClientCommands.argument("place", StringArgumentType.greedyString())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(labels(), b))
                                        .executes(c -> {
                                            Place place = Place.of(StringArgumentType.getString(c, "place"));
                                            if (place == null) return say("Unknown place.", ChatFormatting.RED);
                                            add(place, new BlockPos(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")));
                                            return say("Added " + place.label + ".", ChatFormatting.GREEN);
                                        }))))))
                    .then(ClientCommands.literal("share").then(ClientCommands.argument("place", StringArgumentType.greedyString())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(ACTIVE.keySet().stream().map(p -> p.label).toList(), b))
                        .executes(c -> {
                            Place place = Place.of(StringArgumentType.getString(c, "place"));
                            BlockPos pos = place == null ? null : ACTIVE.get(place);
                            if (pos == null) return say("No waypoint for that place.", ChatFormatting.RED);
                            var connection = Minecraft.getInstance().getConnection();
                            if (connection != null) connection.sendCommand("pc " + place.label + ": " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                            return 1;
                        })))
                    .then(ClientCommands.literal("remove").then(ClientCommands.argument("place", StringArgumentType.greedyString())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(ACTIVE.keySet().stream().map(p -> p.label).toList(), b))
                        .executes(c -> {
                            Place place = Place.of(StringArgumentType.getString(c, "place"));
                            if (place == null || ACTIVE.remove(place) == null) return say("No waypoint for that place.", ChatFormatting.RED);
                            VERIFIED.remove(place);
                            return say("Removed " + place.label + ".", ChatFormatting.GREEN);
                        })))
                    .then(ClientCommands.literal("clear").executes(c -> {
                        ACTIVE.clear();
                        VERIFIED.clear();
                        return say("Cleared Crystal Hollows waypoints.", ChatFormatting.GREEN);
                    }))));
            }
        });
    }

    private static java.util.List<String> labels() {
        return java.util.Arrays.stream(Place.values()).map(p -> p.label).toList();
    }

    private static void add(Place place, BlockPos pos) {
        // A known place close to an "Unknown" marker replaces it.
        BlockPos unknown = ACTIVE.get(Place.UNKNOWN);
        if (place != Place.UNKNOWN && unknown != null && unknown.distSqr(pos) < 50 * 50) ACTIVE.remove(Place.UNKNOWN);
        ACTIVE.put(place, pos.immutable());
    }

    private static boolean inHollows(BlockPos pos) {
        return pos.getX() >= 202 && pos.getX() <= 823 && pos.getZ() >= 202 && pos.getZ() <= 823 && pos.getY() >= 31 && pos.getY() <= 188;
    }

    private static void onChat(String text) {
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        // NPC/crystal messages mean you're standing at that place: move its waypoint here (once per lobby).
        if (mc.player != null) {
            for (Place place : Place.values()) {
                if (place.linkedMessage == null) continue;
                // Crystal lines are indented; compare them trimmed.
                boolean matches = place.linkedMessage.startsWith(SPACER)
                    ? text.trim().equals(place.linkedMessage.trim())
                    : text.startsWith(place.linkedMessage);
                if (matches && VERIFIED.add(place)) {
                    add(place, mc.player.blockPosition());
                }
            }
        }
        FeatureConfigs.MiningFeatures c = config();
        if (c == null || !c.crystalWaypointsFromChat || !text.contains(":") || text.startsWith("[S2M]")) return;
        String said = text.split(":", 2)[1];
        Matcher m = COORDS.matcher(said);
        if (!m.find()) return;
        BlockPos pos = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if (!inHollows(pos)) return;
        String lower = said.toLowerCase(Locale.ROOT);
        for (Place place : Place.values()) {
            if (place == Place.UNKNOWN) continue;
            for (String word : place.label.toLowerCase(Locale.ROOT).split(" ")) {
                if (word.length() > 2 && lower.contains(word)) {
                    if (!ACTIVE.containsKey(place)) add(place, pos);
                    return;
                }
            }
        }
        Sky2MAlerts.chat(placeMenu(pos));
    }

    /** "What's at x y z?" with a clickable button per place, like Skyblocker's location menu. */
    private static Component placeMenu(BlockPos pos) {
        String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        MutableComponent text = Component.literal("Crystal Hollows waypoint at " + coords + ": ").withStyle(ChatFormatting.AQUA);
        for (Place place : Place.values()) {
            String command = "/s2 crystalwaypoints add " + coords + " " + place.label;
            text.append(Component.literal("[" + place.label + "] ").withStyle(style -> style.withColor(place.colour)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Mark as " + place.label)))));
        }
        return text;
    }

    private static int say(String text, ChatFormatting colour) {
        Sky2MAlerts.chat(Component.literal(text).withStyle(colour));
        return 1;
    }
}
