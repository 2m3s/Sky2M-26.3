package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Frame;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Route;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Step;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Type;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Waypoint;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The route editor, /s2 route edit: change a room's route in game, on the same routes /s2 route start records. The way it
 * works is Odin's Dungeon Waypoints editor (https://github.com/odtheking/Odin,
 * features/impl/dungeon/dungeonwaypoints/DungeonWaypointEditor.kt, BSD-3-Clause), written for routes: right click a block to
 * add a waypoint or remove the one there, sneak right click to label it. On top of that a waypoint can be picked up and put
 * down somewhere else, and {@link RouteEditorScreen} lists the waypoints step by step.
 *
 * <p>While it's on, your right clicks on blocks in a recognised room stop reaching the server, so it turns itself off when you
 * change server or world. It only ever draws and saves: nothing moves, aims or clicks for you.
 */
public final class RouteEditor {
    /** Where a waypoint is in a route. */
    public record Located(int step, int index, Waypoint waypoint) {}

    private static final long CLICK_GAP_MS = 250;
    private static final double REACH = 32;

    private static boolean active;
    private static Type addType = Type.CUSTOM;
    private static Located held;
    private static Room heldRoom;
    private static long lastClick;

    private RouteEditor() {}

    static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            active = false;
            held = null;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("route")
                        .then(ClientCommands.literal("edit")
                            .executes(c -> toggle())
                            .then(ClientCommands.literal("menu").executes(c -> menu()))
                            .then(ClientCommands.literal("move").executes(c -> pickUpLookedAt()))
                            .then(ClientCommands.literal("remove").executes(c -> removeLookedAt()))
                            .then(ClientCommands.literal("label")
                                .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                    .executes(c -> labelLookedAt(StringArgumentType.getString(c, "text")))))
                            .then(ClientCommands.literal("type")
                                .then(ClientCommands.argument("type", StringArgumentType.word())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Type.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList(), b))
                                    .executes(c -> setType(StringArgumentType.getString(c, "type"))))))));
            }
        });
    }

    public static boolean active() {
        return active;
    }

    static Type addType() {
        return addType;
    }

    static void setAddType(Type type) {
        addType = type;
    }

    static String typeName(Type type) {
        String name = type.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ----- Changing a route. Public for the game test. -----

    /** The first waypoint at {@code pos} (in room terms), or null. */
    public static Located find(List<Step> route, BlockPos pos) {
        for (int s = 0; s < route.size(); s++) {
            List<Waypoint> waypoints = route.get(s).waypoints();
            for (int i = 0; i < waypoints.size(); i++) if (waypoints.get(i).pos().equals(pos)) return new Located(s, i, waypoints.get(i));
        }
        return null;
    }

    /**
     * Adds to the step, or to a new step right after it when this is a second secret, since a step is one secret. Returns
     * the step it went in.
     */
    public static int add(List<Step> route, int step, Waypoint waypoint) {
        if (route.isEmpty()) route.add(new Step());
        int at = Math.max(0, Math.min(step, route.size() - 1));
        if (waypoint.type().secret() && route.get(at).secret() != null) route.add(++at, new Step());
        route.get(at).waypoints().add(waypoint);
        return at;
    }

    public static void remove(List<Step> route, Located at) {
        route.get(at.step()).waypoints().remove(at.index());
    }

    /** Moves a waypoint, keeping its step, type and label. A pearl's line moves with it. */
    public static void move(List<Step> route, Located at, BlockPos to) {
        Waypoint w = route.get(at.step()).waypoints().get(at.index());
        DungeonRoutes.Aim aim = w.aim() == null ? null : new DungeonRoutes.Aim(w.aim().pitch(), w.aim().yaw(),
            w.aim().from().add(to.getX() - w.pos().getX(), to.getY() - w.pos().getY(), to.getZ() - w.pos().getZ()));
        route.get(at.step()).waypoints().set(at.index(), new Waypoint(to, w.type(), w.name(), aim));
    }

    /** Sets a waypoint's label; a blank one goes back to the type's own. */
    public static void label(List<Step> route, Located at, String text) {
        Waypoint w = route.get(at.step()).waypoints().get(at.index());
        route.get(at.step()).waypoints().set(at.index(), new Waypoint(w.pos(), w.type(), text == null || text.isBlank() ? null : text.trim(), w.aim()));
    }

    // ----- Your clicks -----

    /** A right click on a block. True when the editor used it, and the click shouldn't go on to the server. */
    static boolean onUseBlock(Player player, BlockPos pos, BlockState state) {
        if (!active) return false;
        Room room = DungeonRoutes.room();
        Frame frame = room == null ? null : DungeonRoutes.frameOf(room);
        if (frame == null) return false;
        // Holding the button repeats the click.
        long now = System.currentTimeMillis();
        if (now - lastClick < CLICK_GAP_MS) return true;
        lastClick = now;
        BlockPos local = frame.toRoom(pos);
        if (holding(room)) {
            List<Step> route = DungeonRoutes.editableSteps(room);
            move(route, held, local);
            held = null;
            DungeonRoutes.saveCustom();
            DungeonRoutes.say("Moved it here.", ChatFormatting.GREEN);
            return true;
        }
        Route showing = DungeonRoutes.showingRoute(room);
        Located there = showing == null ? null : find(showing.steps(), local);
        if (player.isShiftKeyDown()) {
            Type type = typeToAdd(state);
            String current = there == null ? "" : there.waypoint().name();
            Compat.queueOpenScreen(new RouteEditorScreen.Prompt("Waypoint label", current == null ? "" : current, null, text -> {
                List<Step> route = DungeonRoutes.editableSteps(room);
                if (there != null) label(route, there, text);
                else DungeonRoutes.setStepIndex(add(route, DungeonRoutes.stepIndex(), new Waypoint(local, type, text.isBlank() ? null : text.trim())));
                DungeonRoutes.saveCustom();
            }));
            return true;
        }
        List<Step> route = DungeonRoutes.editableSteps(room);
        if (there != null) {
            remove(route, there);
            DungeonRoutes.say("Removed the " + typeName(there.waypoint().type()) + " waypoint.", ChatFormatting.YELLOW);
        } else {
            Type type = typeToAdd(state);
            int step = add(route, DungeonRoutes.stepIndex(), new Waypoint(local, type, null));
            DungeonRoutes.setStepIndex(step);
            DungeonRoutes.say("Added a " + typeName(type) + " waypoint to step " + (step + 1) + ".", ChatFormatting.GREEN);
        }
        DungeonRoutes.saveCustom();
        return true;
    }

    /** Your chosen type; on Custom, a chest, head or lever is what it looks like. */
    private static Type typeToAdd(BlockState state) {
        if (addType != Type.CUSTOM) return addType;
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) return Type.CHEST;
        if (state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD)) return Type.ESSENCE;
        if (state.is(Blocks.LEVER)) return Type.LEVER;
        return Type.CUSTOM;
    }

    private static boolean holding(Room room) {
        if (held != null && heldRoom != room) held = null;
        return held != null;
    }

    static void pickUp(Room room, Located at) {
        held = at;
        heldRoom = room;
    }

    // ----- Commands -----

    private static int toggle() {
        active = !active;
        if (!active) {
            held = null;
            return DungeonRoutes.say("Route editor off.", ChatFormatting.YELLOW);
        }
        return DungeonRoutes.say("Route editor on: right click a block to add a waypoint or remove the one there, sneak right click to label it. Your right clicks on blocks don't reach the server until you turn it off with /s2 route edit. /s2 route edit menu lists the steps; /s2 route edit type <type> picks what you add.", ChatFormatting.GREEN);
    }

    private static int menu() {
        Room room = DungeonRoutes.room();
        if (room == null) return DungeonRoutes.say("Stand in a dungeon room Sky2M has recognised first.", ChatFormatting.RED);
        return Compat.queueOpenScreen(new RouteEditorScreen(room));
    }

    private static int setType(String name) {
        for (Type type : Type.values()) {
            if (type.name().equalsIgnoreCase(name)) {
                addType = type;
                return DungeonRoutes.say("You now add " + typeName(type) + " waypoints.", ChatFormatting.GREEN);
            }
        }
        return DungeonRoutes.say("No waypoint type called " + name + ".", ChatFormatting.RED);
    }

    /** The waypoint your crosshair is on, walls or not. Null when there isn't one. */
    private static Located lookedAt(Room room, Frame frame, List<Step> route) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(REACH));
        Located best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int s = 0; s < route.size(); s++) {
            List<Waypoint> waypoints = route.get(s).waypoints();
            for (int i = 0; i < waypoints.size(); i++) {
                var hit = new AABB(frame.toWorld(waypoints.get(i).pos())).clip(eye, end);
                if (hit.isEmpty() || hit.get().distanceToSqr(eye) >= bestDistance) continue;
                bestDistance = hit.get().distanceToSqr(eye);
                best = new Located(s, i, waypoints.get(i));
            }
        }
        return best;
    }

    private interface LookedAtAction {
        int run(Room room, Located at);
    }

    private static int withLookedAt(LookedAtAction action) {
        Room room = DungeonRoutes.room();
        Frame frame = room == null ? null : DungeonRoutes.frameOf(room);
        if (frame == null) return DungeonRoutes.say("Stand in a dungeon room Sky2M has recognised first.", ChatFormatting.RED);
        Route showing = DungeonRoutes.showingRoute(room);
        Located at = showing == null ? null : lookedAt(room, frame, showing.steps());
        if (at == null) return DungeonRoutes.say("Look at a waypoint of this room's route first.", ChatFormatting.RED);
        return action.run(room, at);
    }

    private static int pickUpLookedAt() {
        if (!active) return DungeonRoutes.say("Turn the route editor on first: /s2 route edit.", ChatFormatting.RED);
        return withLookedAt((room, at) -> {
            pickUp(room, at);
            return DungeonRoutes.say("Right click the block to move it to.", ChatFormatting.GREEN);
        });
    }

    private static int removeLookedAt() {
        return withLookedAt((room, at) -> {
            remove(DungeonRoutes.editableSteps(room), at);
            DungeonRoutes.saveCustom();
            return DungeonRoutes.say("Removed the " + typeName(at.waypoint().type()) + " waypoint.", ChatFormatting.YELLOW);
        });
    }

    private static int labelLookedAt(String text) {
        return withLookedAt((room, at) -> {
            label(DungeonRoutes.editableSteps(room), at, text);
            DungeonRoutes.saveCustom();
            return DungeonRoutes.say("Labelled it " + text + ".", ChatFormatting.GREEN);
        });
    }

    // ----- Drawing -----

    /** The step you're on as usual, the other steps' waypoints dim, and what a click would do on the block you're looking at. */
    static void render(PrimitiveCollector collector, Frame frame, List<Step> route, int stepIndex) {
        int current = Math.min(stepIndex, route.size() - 1);
        for (int s = 0; s < route.size(); s++) {
            if (s == current) continue;
            for (Waypoint w : route.get(s).waypoints()) {
                BlockPos pos = frame.toWorld(w.pos());
                collector.submitOutlinedBox(new AABB(pos), w.type().colour, 0.35f, 1.5f, true);
                collector.submitText(Component.literal("Step " + (s + 1)).withStyle(ChatFormatting.GRAY), Vec3.atCenterOf(pos), false);
            }
        }
        if (current >= 0) DungeonRoutes.renderStep(collector, frame, route.get(current), current == 0);
        HitResult hit = Minecraft.getInstance().hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = blockHit.getBlockPos();
        Located there = find(route, frame.toRoom(pos));
        String text = held != null ? "Put here" : there != null ? "Remove" : "Add " + typeName(addType);
        float[] colour = held != null ? held.waypoint().type().colour : there != null ? new float[]{1f, 0.33f, 0.33f} : addType.colour;
        collector.submitOutlinedBox(pos, colour, 2f, true);
        collector.submitText(Component.literal(text), Vec3.atCenterOf(pos).add(0, 0.8, 0), true);
    }

    /** A copy of a step's waypoints, for the screen to list. */
    static List<Waypoint> waypointsOf(List<Step> route, int step) {
        return step >= 0 && step < route.size() ? new ArrayList<>(route.get(step).waypoints()) : List.of();
    }
}
