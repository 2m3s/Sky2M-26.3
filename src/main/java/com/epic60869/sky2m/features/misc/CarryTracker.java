package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.slayer.SlayerFeatures;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Misc > Carry Tracker (/s2 carry): who you're carrying, how many are done and what they owe. A dungeon carry counts
 * when a run ends ("Team Score: ...") with that player in the lobby, and only on the carry's floor if its type is one
 * (f7, m5...). A slayer carry counts when a boss whose nametag says "Spawned by: Name" dies near you. Carries are kept
 * in {@code sky2m/carries.json}.
 */
public final class CarryTracker {
    private static final Pattern FLOOR = Pattern.compile("(?i)^(?:e|[fm][0-7])$");
    private static final Pattern PRICE = Pattern.compile("(?i)^([\\d.,]+)([kmb]?)$");
    private static final Gson GSON = new Gson();

    private static final class Carry {
        String player;
        String type = "";
        int total;
        int done;
        double price;
        double paid;
    }

    private static List<Carry> carries = new ArrayList<>();
    private static Path file;
    /** Slayer bosses spawned by a carried player, by entity id, and that player. */
    private static final Map<Integer, String> bosses = new HashMap<>();
    private static final Set<Integer> counted = new HashSet<>();
    private static boolean runCounted;
    private static int ticks;

    private CarryTracker() {}

    private static FeatureConfigs.CarryTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.carryTracker;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("carries.json");
        load();
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MLocation.onAreaChange(area -> {
            runCounted = false;
            bosses.clear();
            counted.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 == 0) scanBosses(mc);
        });
        Sky2MHuds.register("carry_tracker", "Carry Tracker", () -> config() != null && config().hud, CarryTracker::lines,
            List.of(Component.literal("Carries").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("Steve ").withStyle(ChatFormatting.YELLOW).append(Component.literal("3/5 F7").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" owes 12.00M").withStyle(ChatFormatting.RED))),
            10, 340);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("carry")
                    .executes(c -> run(CarryTracker::list))
                    .then(ClientCommands.literal("list").executes(c -> run(CarryTracker::list)))
                    .then(ClientCommands.literal("clear").executes(c -> run(CarryTracker::clear)))
                    .then(ClientCommands.literal("add").then(player()
                        .then(ClientCommands.argument("amount", IntegerArgumentType.integer(1, 10000))
                            .executes(c -> run(() -> add(StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "amount"), "")))
                            .then(ClientCommands.argument("type and price", StringArgumentType.greedyString())
                                .executes(c -> run(() -> add(StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "amount"),
                                    StringArgumentType.getString(c, "type and price"))))))))
                    .then(ClientCommands.literal("paid").then(player()
                        .then(ClientCommands.argument("coins", StringArgumentType.word())
                            .executes(c -> run(() -> paid(StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "coins")))))))
                    .then(ClientCommands.literal("count").then(player()
                        .executes(c -> run(() -> count(StringArgumentType.getString(c, "player"), 1)))
                        .then(ClientCommands.argument("n", IntegerArgumentType.integer(-100, 100))
                            .executes(c -> run(() -> count(StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "n")))))))
                    .then(ClientCommands.literal("remove").then(player()
                        .executes(c -> run(() -> remove(StringArgumentType.getString(c, "player"))))))));
            }
        });
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, String> player() {
        return ClientCommands.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (Carry carry : carries) builder.suggest(carry.player);
            return builder.buildFuture();
        });
    }

    private static int run(Runnable action) {
        Minecraft.getInstance().execute(action);
        return 1;
    }

    private static Carry find(String player) {
        for (Carry carry : carries) if (carry.player.equalsIgnoreCase(player)) return carry;
        return null;
    }

    // ------------------------------------------------------------------------------------------------ commands

    private static void add(String player, int amount, String rest) {
        Carry carry = find(player);
        if (carry == null) {
            carry = new Carry();
            carry.player = player;
            carries.add(carry);
        }
        carry.total += amount;
        for (String token : rest.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            Matcher m = PRICE.matcher(token);
            if (m.matches() && !FLOOR.matcher(token).matches()) carry.price = coins(m.group(1), m.group(2));
            else carry.type = token;
        }
        save();
        say(Component.literal("Carrying " + carry.player + ": " + carry.done + "/" + carry.total + label(carry)
            + (carry.price > 0 ? " at " + CombatFeatures.formatCoins(carry.price) + " each" : "") + ".").withStyle(ChatFormatting.GREEN));
    }

    private static void paid(String player, String amount) {
        Carry carry = find(player);
        Matcher m = PRICE.matcher(amount);
        if (carry == null || !m.matches()) {
            say(Component.literal(carry == null ? "You aren't carrying " + player + "." : "Give the coins like 10m, 500k or 2500000.").withStyle(ChatFormatting.RED));
            return;
        }
        carry.paid += coins(m.group(1), m.group(2));
        save();
        say(Component.literal(carry.player + " has paid " + CombatFeatures.formatCoins(carry.paid) + owesText(carry) + ".").withStyle(ChatFormatting.GREEN));
    }

    private static void count(String player, int n) {
        Carry carry = find(player);
        if (carry == null) {
            say(Component.literal("You aren't carrying " + player + ".").withStyle(ChatFormatting.RED));
            return;
        }
        progress(carry, n);
    }

    private static void remove(String player) {
        Carry carry = find(player);
        if (carry == null) {
            say(Component.literal("You aren't carrying " + player + ".").withStyle(ChatFormatting.RED));
            return;
        }
        carries.remove(carry);
        save();
        say(Component.literal("Removed " + carry.player + "'s carry" + owesText(carry) + ".").withStyle(ChatFormatting.YELLOW));
    }

    private static void clear() {
        carries.clear();
        save();
        say(Component.literal("Removed every carry.").withStyle(ChatFormatting.YELLOW));
    }

    private static void list() {
        if (carries.isEmpty()) {
            say(Component.literal("No carries. /s2 carry add <player> <amount> [type] [price each]").withStyle(ChatFormatting.GRAY));
            return;
        }
        say(Component.literal("Carries:").withStyle(ChatFormatting.GOLD));
        for (Carry carry : carries) say(line(carry));
    }

    // ------------------------------------------------------------------------------------------------ counting

    private static void progress(Carry carry, int n) {
        int before = carry.done;
        carry.done = Math.max(0, carry.done + n);
        save();
        if (n > 0) Sky2MAchievements.add(Sky2MAchievements.Stat.CARRIES, n);
        FeatureConfigs.CarryTracker c = config();
        if (c == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (n > 0 && c.partyProgress && mc.getConnection() != null) mc.getConnection().sendCommand("pc " + carry.player + ": " + Math.min(carry.done, carry.total) + "/" + carry.total + " carries done");
        if (before < carry.total && carry.done >= carry.total && c.doneMessage) {
            say(Component.literal(carry.player + "'s " + carry.total + label(carry) + " carries are done" + owesText(carry) + ".").withStyle(ChatFormatting.GOLD));
        } else if (n > 0) {
            say(Component.literal("Counted a carry for " + carry.player + ": " + carry.done + "/" + carry.total + ".").withStyle(ChatFormatting.GRAY));
        }
    }

    private static void onChat(String text) {
        FeatureConfigs.CarryTracker c = config();
        if (c == null || !c.countDungeons || runCounted || !Sky2MLocation.inDungeon() || !text.startsWith("Team Score:")) return;
        runCounted = true;
        String floor = Sky2MLocation.dungeonFloor();
        floor = floor.equals("E0") || floor.equals("F0") ? "E" : floor;
        Set<String> here = lobbyNames();
        for (Carry carry : new ArrayList<>(carries)) {
            if (carry.done >= carry.total || !here.contains(carry.player.toLowerCase(Locale.ROOT))) continue;
            boolean floorCarry = FLOOR.matcher(carry.type).matches();
            if (floorCarry && !carry.type.equalsIgnoreCase(floor)) continue;
            if (!floorCarry && !carry.type.isEmpty() && looksSlayer(carry.type)) continue;
            progress(carry, 1);
        }
    }

    private static boolean looksSlayer(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return t.contains("void") || t.contains("eman") || t.contains("blaze") || t.contains("inferno") || t.contains("rev") || t.contains("tara")
            || t.contains("sven") || t.contains("vamp") || t.contains("slayer") || t.startsWith("t") && t.length() <= 3;
    }

    private static Set<String> lobbyNames() {
        Set<String> out = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return out;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) out.add(info.getProfile().name().toLowerCase(Locale.ROOT));
        return out;
    }

    private static void scanBosses(Minecraft mc) {
        FeatureConfigs.CarryTracker c = config();
        if (c == null || !c.countSlayers || mc.level == null || mc.player == null || carries.isEmpty() || Sky2MLocation.inDungeon()) return;
        for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(32))) {
            String owner = SlayerFeatures.spawnedBy(e);
            if (owner == null) continue;
            Carry carry = find(owner);
            if (carry == null || carry.done >= carry.total) continue;
            LivingEntity boss = SlayerFeatures.bossUnder(e);
            if (boss != null && !counted.contains(boss.getId())) bosses.put(boss.getId(), carry.player);
        }
        // A boss whose health hit 0 counts even if its death event was missed.
        for (Map.Entry<Integer, String> entry : new ArrayList<>(bosses.entrySet())) {
            Entity boss = mc.level.getEntity(entry.getKey());
            if (boss instanceof LivingEntity living && living.isDeadOrDying()) onEntityDeath(living);
        }
    }

    /** From the entity event handler when a mob dies. */
    public static void onEntityDeath(Entity entity) {
        if (entity == null) return;
        String player = bosses.remove(entity.getId());
        if (player == null || !counted.add(entity.getId())) return;
        Carry carry = find(player);
        if (carry != null) progress(carry, 1);
    }

    // ------------------------------------------------------------------------------------------------ display

    private static String label(Carry carry) {
        return carry.type.isEmpty() ? "" : " " + carry.type.toUpperCase(Locale.ROOT);
    }

    private static double owes(Carry carry) {
        return Math.max(0, Math.min(carry.done, carry.total) * carry.price - carry.paid);
    }

    private static String owesText(Carry carry) {
        if (carry.price <= 0) return "";
        double owed = owes(carry);
        return owed > 0 ? ", owes " + CombatFeatures.formatCoins(owed) : ", nothing owed";
    }

    private static MutableComponent line(Carry carry) {
        MutableComponent line = Component.literal(carry.player + " ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(carry.done + "/" + carry.total + label(carry)).withStyle(carry.done >= carry.total ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        if (carry.price > 0) {
            double owed = owes(carry);
            line.append(Component.literal(owed > 0 ? " owes " + CombatFeatures.formatCoins(owed) : " paid").withStyle(owed > 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
        }
        return line;
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        if (carries.isEmpty()) return out;
        out.add(Component.literal("Carries").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Carry carry : carries) out.add(line(carry));
        return out;
    }

    private static double coins(String digits, String suffix) {
        try {
            double v = Double.parseDouble(digits.replace(",", ""));
            return switch (suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> v * 1_000;
                case "m" -> v * 1_000_000;
                case "b" -> v * 1_000_000_000;
                default -> v;
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void say(Component text) {
        Sky2MAlerts.chat(text);
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            List<Carry> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<ArrayList<Carry>>() {}.getType());
            if (read != null) {
                read.removeIf(c -> c.player == null);
                for (Carry c : read) if (c.type == null) c.type = "";
                carries = read;
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(carries), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
