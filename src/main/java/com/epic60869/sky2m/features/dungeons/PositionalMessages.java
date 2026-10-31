package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Positional messages: send a party message when you reach a spot (within a radius, or inside a box), and
 * show the spots in the world. Follows Odin's PositionalMessages (https://github.com/odtheking/Odin, BSD-3-Clause).
 */
public final class PositionalMessages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A saved message: radius mode when x2 is null, box mode otherwise. Delay is in ticks. */
    public static final class PosMessage {
        public double x, y, z;
        public Double x2, y2, z2;
        public Double radius;
        public int delay;
        public String message;

        boolean inside(Vec3 pos) {
            if (x2 != null) return new AABB(x, y, z, x2, y2, z2).contains(pos);
            return radius != null && pos.distanceToSqr(x, y, z) <= radius * radius;
        }

        Vec3 centre() {
            return x2 == null ? new Vec3(x, y, z) : new Vec3((x + x2) / 2, (y + y2) / 2, (z + z2) / 2);
        }
    }

    private record Pending(String message, int ticksLeft) {}

    private static List<PosMessage> messages = new ArrayList<>();
    private static final Set<PosMessage> SENT = new HashSet<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static Path file;

    private PositionalMessages() {}

    private static FeatureConfigs.PositionalMessages config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.positionalMessages;
    }

    /**
     * The world in which chat showed we are on F7/M7 (the "entered ... Floor VII" line or a Wither Lord speaking), so the
     * spots still show when the sidebar can't be read. Only counts while you are still in that world.
     */
    private static java.lang.ref.WeakReference<Object> floor7World = new java.lang.ref.WeakReference<>(null);

    /** On F7 or M7: from the sidebar, the boss tracking, or this world's chat. */
    static boolean onFloor7() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level == floor7World.get()) return true;
        if (DungeonManager.getBoss().isFloor(7)) return true;
        return Sky2MLocation.inDungeon() && Sky2MLocation.dungeonFloor().endsWith("7");
    }

    private static void onChat(Sky2MChat.Message message) {
        String text = message.text();
        if (text.startsWith("[BOSS] Maxor:") || text.startsWith("[BOSS] Storm:") || text.startsWith("[BOSS] Goldor:")
            || text.startsWith("[BOSS] Necron:") || text.startsWith("[BOSS] Wither King:")
            || (text.contains("entered") && text.contains("The Catacombs, Floor VII"))) {
            floor7World = new java.lang.ref.WeakReference<>(Minecraft.getInstance().level);
        }
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m-posmsgs.json");
        load();
        ClientTickEvents.END_CLIENT_TICK.register(PositionalMessages::tick);
        Sky2MChat.onChat(PositionalMessages::onChat);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            floor7World = new java.lang.ref.WeakReference<>(null);
            SENT.clear();
            PENDING.clear();
        });
        Sky2MWorldRender.register(PositionalMessages::render);
        registerCommands();
    }

    private static boolean active(FeatureConfigs.PositionalMessages config) {
        return !config.onlyInBoss || (Sky2MLocation.inDungeon() && DungeonManager.isInBoss());
    }

    private static void tick(Minecraft mc) {
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.ticksLeft() > 0) continue;
            it.remove();
            if (mc.getConnection() != null) mc.getConnection().sendCommand("pc " + p.message());
        }
        PENDING.replaceAll(p -> new Pending(p.message(), p.ticksLeft() - 1));

        FeatureConfigs.PositionalMessages config = config();
        if (config == null || mc.player == null) return;
        if (!config.enabled || !active(config)) return;
        Vec3 pos = mc.player.position();
        for (PosMessage m : messages) {
            if (SENT.contains(m) || m.message == null || m.message.isBlank() || !m.inside(pos)) continue;
            SENT.add(m);
            PENDING.add(new Pending(m.message, Math.max(0, m.delay)));
        }
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.PositionalMessages config = config();
        if (config == null) return;
        if (!config.enabled || !config.showPositions || !active(config)) return;
        float[] colour = {0.3f, 0.8f, 1f};
        for (PosMessage m : messages) {
            if (m.x2 != null) {
                collector.submitOutlinedBox(new AABB(m.x, m.y, m.z, m.x2, m.y2, m.z2), colour, 2f, false);
            } else if (m.radius != null) {
                ring(collector, new Vec3(m.x, m.y, m.z), m.radius.floatValue(), config.ringHeight, 0xFF4DCCFF);
            }
            if (config.showMessage && m.message != null) {
                collector.submitText(Component.literal(m.message), m.centre().add(0, 1, 0), config.messageSize, true);
            }
        }
    }

    /** Odin's position ring: a short cylinder band (two circles joined by vertical lines) with a faint filled floor. */
    static void ring(PrimitiveCollector collector, Vec3 centre, float radius, float height, int argb) {
        float[] rgb = {((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f};
        collector.submitFilledCircle(centre.add(0, 0.01, 0), radius, 48, (argb & 0x00FFFFFF) | 0x40000000);
        collector.submitCylinder(centre, radius, height, 48, argb);
        int segments = 24;
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2 * i / segments;
            Vec3 bottom = centre.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            collector.submitLinesFromPoints(new Vec3[]{bottom, bottom.add(0, height, 0)}, rgb, 1f, 2f, false);
        }
    }

    // ----- Storage -----

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            List<PosMessage> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<PosMessage>>() {}.getType());
            if (loaded != null) messages = new ArrayList<>(loaded);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read positional messages: " + e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file, GSON.toJson(messages), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save positional messages: " + e);
        }
    }

    // ----- Commands -----

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("posmsg")
                    .executes(c -> help())
                    .then(ClientCommands.literal("add")
                        .then(ClientCommands.literal("here")
                            .then(ClientCommands.argument("radius", DoubleArgumentType.doubleArg(0.1, 50))
                                .then(ClientCommands.argument("delay", IntegerArgumentType.integer(0, 1200))
                                    .then(ClientCommands.argument("message", StringArgumentType.greedyString()).executes(PositionalMessages::addHere)))))
                        .then(ClientCommands.literal("at")
                            .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                                .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                                    .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                                        .then(ClientCommands.argument("radius", DoubleArgumentType.doubleArg(0.1, 50))
                                            .then(ClientCommands.argument("delay", IntegerArgumentType.integer(0, 1200))
                                                .then(ClientCommands.argument("message", StringArgumentType.greedyString()).executes(PositionalMessages::addAt))))))))
                        .then(ClientCommands.literal("in")
                            .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                                .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                                    .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                                        .then(ClientCommands.argument("x2", DoubleArgumentType.doubleArg())
                                            .then(ClientCommands.argument("y2", DoubleArgumentType.doubleArg())
                                                .then(ClientCommands.argument("z2", DoubleArgumentType.doubleArg())
                                                    .then(ClientCommands.argument("delay", IntegerArgumentType.integer(0, 1200))
                                                        .then(ClientCommands.argument("message", StringArgumentType.greedyString()).executes(PositionalMessages::addIn)))))))))))
                    .then(ClientCommands.literal("list").executes(c -> list()))
                    .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("number", IntegerArgumentType.integer(1)).executes(c -> remove(IntegerArgumentType.getInteger(c, "number")))))
                    .then(ClientCommands.literal("clear").executes(c -> {
                        messages.clear();
                        save();
                        return say("Removed all positional messages.", ChatFormatting.YELLOW);
                    }))));
            }
        });
    }

    private static int help() {
        say("/s2 posmsg add here <radius> <delay ticks> <message>", ChatFormatting.YELLOW);
        say("/s2 posmsg add at <x> <y> <z> <radius> <delay ticks> <message>", ChatFormatting.YELLOW);
        say("/s2 posmsg add in <x> <y> <z> <x2> <y2> <z2> <delay ticks> <message>", ChatFormatting.YELLOW);
        return say("/s2 posmsg list | remove <number> | clear", ChatFormatting.YELLOW);
    }

    private static int addHere(CommandContext<FabricClientCommandSource> c) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        PosMessage m = new PosMessage();
        m.x = Math.round(player.getX() * 10) / 10.0;
        m.y = Math.round(player.getY() * 10) / 10.0;
        m.z = Math.round(player.getZ() * 10) / 10.0;
        m.radius = DoubleArgumentType.getDouble(c, "radius");
        return add(m, c);
    }

    private static int addAt(CommandContext<FabricClientCommandSource> c) {
        PosMessage m = new PosMessage();
        m.x = DoubleArgumentType.getDouble(c, "x");
        m.y = DoubleArgumentType.getDouble(c, "y");
        m.z = DoubleArgumentType.getDouble(c, "z");
        m.radius = DoubleArgumentType.getDouble(c, "radius");
        return add(m, c);
    }

    private static int addIn(CommandContext<FabricClientCommandSource> c) {
        PosMessage m = new PosMessage();
        m.x = DoubleArgumentType.getDouble(c, "x");
        m.y = DoubleArgumentType.getDouble(c, "y");
        m.z = DoubleArgumentType.getDouble(c, "z");
        m.x2 = DoubleArgumentType.getDouble(c, "x2");
        m.y2 = DoubleArgumentType.getDouble(c, "y2");
        m.z2 = DoubleArgumentType.getDouble(c, "z2");
        return add(m, c);
    }

    private static int add(PosMessage m, CommandContext<FabricClientCommandSource> c) {
        m.delay = IntegerArgumentType.getInteger(c, "delay");
        m.message = StringArgumentType.getString(c, "message");
        messages.add(m);
        save();
        return say("Added positional message #" + messages.size() + ": " + m.message, ChatFormatting.GREEN);
    }

    private static int list() {
        if (messages.isEmpty()) return say("No positional messages. Add one with /s2 posmsg add here <radius> <delay> <message>.", ChatFormatting.YELLOW);
        for (int i = 0; i < messages.size(); i++) {
            PosMessage m = messages.get(i);
            String where = m.x2 != null
                ? String.format(Locale.US, "in %.1f %.1f %.1f to %.1f %.1f %.1f", m.x, m.y, m.z, m.x2, m.y2, m.z2)
                : String.format(Locale.US, "at %.1f %.1f %.1f (radius %.1f)", m.x, m.y, m.z, m.radius);
            say("#" + (i + 1) + " " + where + ", delay " + m.delay + ": " + m.message, ChatFormatting.GRAY);
        }
        return 1;
    }

    private static int remove(int number) {
        if (number > messages.size()) return say("There is no positional message #" + number + ".", ChatFormatting.RED);
        PosMessage removed = messages.remove(number - 1);
        SENT.remove(removed);
        save();
        return say("Removed positional message #" + number + ".", ChatFormatting.YELLOW);
    }

    private static int say(String text, ChatFormatting colour) {
        Sky2MAlerts.chat(Component.literal(text).withStyle(colour));
        return 1;
    }
}
