package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crystal Nucleus run tracker, ported from SkyHanni's CrystalNucleusApi and CrystalNucleusTracker (hannibal002/SkyHanni,
 * LGPL-2.1): the "CRYSTAL NUCLEUS LOOT BUNDLE" chat message lists the loot until the next ▬ line, each loot line starting
 * with four spaces. Powder lines are ignored, like SkyHanni does. Counts are kept in {@code sky2m/nucleus.json}. Items
 * are tracked by their name as shown in chat and not valued.
 */
public final class NucleusTracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ID = "nucleus_tracker";
    private static final Pattern START = Pattern.compile("^\\s*CRYSTAL NUCLEUS LOOT BUNDLE.*$");
    private static final Pattern END = Pattern.compile("^▬{20,}$");
    /** "Flawed Ruby Gemstone x20", "Enchanted Book (Lapidary I)", "3x Jungle Key". */
    private static final Pattern LOOT = Pattern.compile("^(?:(\\d[\\d,]*)x )?(.+?)(?: x(\\d[\\d,]*))?$");
    private static final Pattern BAL = Pattern.compile("^(?:\\[[^]]*] )?(\\w{1,16}) has obtained \\[Lvl 1] Bal!$");

    private static final class Data {
        long runs;
        long bals;
        Map<String, Long> loot = new LinkedHashMap<>();
    }

    private static Data data = new Data();
    private static Path file;
    private static boolean inLoop;
    private static final Map<String, Long> current = new LinkedHashMap<>();
    /** Chat lines of the loot bundle that the chat compactor still has to hide, in order. */
    private static final Deque<String> hide = new ArrayDeque<>();

    private NucleusTracker() {}

    private static boolean tracking() {
        DwarvenConfig.Nucleus c = DwarvenMining.nucleus();
        return c != null && c.tracker;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("nucleus.json");
        load();
        Sky2MChat.onChat(message -> onChat(message.text()));
        Sky2MLocation.onAreaChange(area -> {
            inLoop = false;
            current.clear();
            hide.clear();
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !hidden(message));
        Sky2MHuds.setting(ID, NucleusTracker::tracking);
        Sky2MHuds.register(ID, "Crystal Nucleus Tracker", () -> tracking() && Sky2MLocation.inCrystalHollows(), NucleusTracker::lines,
            List.of(Component.literal("Crystal Nucleus Runs: ").withStyle(ChatFormatting.GRAY).append(Component.literal("12").withStyle(ChatFormatting.YELLOW)),
                Component.literal(" Flawed Ruby Gemstone: ").withStyle(ChatFormatting.GRAY).append(Component.literal("340").withStyle(ChatFormatting.WHITE))),
            8, 250);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("resetnucleus").executes(c -> {
                    data = new Data();
                    save();
                    Sky2MAlerts.chat(Component.literal("Crystal Nucleus tracker reset.").withStyle(ChatFormatting.YELLOW));
                    return 1;
                })));
            }
        });
    }

    private static boolean hidden(Component message) {
        if (hide.isEmpty()) return false;
        String text = Sky2MLocation.strip(message.getString()).trim();
        if (hide.peekFirst().equals(text)) {
            hide.removeFirst();
            return true;
        }
        return false;
    }

    private static void onChat(String raw) {
        DwarvenConfig.Nucleus c = DwarvenMining.nucleus();
        if (c == null || !(c.tracker || c.compactChat) || !Sky2MLocation.inCrystalHollows()) return;
        String text = raw.trim();
        Minecraft mc = Minecraft.getInstance();

        if (START.matcher(text).matches()) {
            inLoop = true;
            current.clear();
            if (c.compactChat) hide.addLast(text);
            return;
        }
        if (c.tracker && mc.player != null) {
            Matcher bal = BAL.matcher(text);
            if (bal.matches() && bal.group(1).equalsIgnoreCase(mc.player.getGameProfile().name())) {
                data.bals++;
                save();
            }
        }
        if (!inLoop) return;

        if (END.matcher(text).matches()) {
            inLoop = false;
            if (c.compactChat) hide.addLast(text);
            finish(c);
            return;
        }
        if (c.compactChat) hide.addLast(text);
        // Only the loot lines (four leading spaces); the header and "REWARDS" lines are skipped.
        if (!raw.startsWith("    ") || text.isEmpty() || text.contains("Powder") || text.contains("REWARDS")) return;
        Matcher m = LOOT.matcher(text);
        if (!m.matches()) return;
        String name = m.group(2).trim();
        String amount = m.group(1) != null ? m.group(1) : m.group(3);
        long count = 1;
        if (amount != null) {
            try {
                count = Long.parseLong(amount.replace(",", ""));
            } catch (NumberFormatException ignored) {
                // keep 1
            }
        }
        current.merge(name, count, Long::sum);
    }

    private static void finish(DwarvenConfig.Nucleus c) {
        if (c.tracker) {
            data.runs++;
            for (Map.Entry<String, Long> e : current.entrySet()) data.loot.merge(e.getKey(), e.getValue(), Long::sum);
            save();
        }
        if (c.compactChat) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Long> e : current.entrySet()) parts.add((e.getValue() > 1 ? e.getValue() + "x " : "") + e.getKey());
            Component summary = Component.literal("Crystal Nucleus loot: ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(parts.isEmpty() ? "nothing recognised" : String.join(", ", parts)).withStyle(ChatFormatting.WHITE));
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player == null) return;
                mc.gui.hud.getChat().addClientSystemMessage(summary);
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("▬".repeat(64)).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
            });
        }
        current.clear();
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Crystal Nucleus Runs: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d", data.runs)).withStyle(ChatFormatting.YELLOW)));
        if (data.bals > 0) {
            out.add(Component.literal(" Bal pets: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(data.bals)).withStyle(ChatFormatting.GOLD)));
        }
        data.loot.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10).forEach(e ->
            out.add(Component.literal(" " + e.getKey() + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format(Locale.US, "%,d", e.getValue())).withStyle(ChatFormatting.WHITE))));
        return out;
    }

    private static void load() {
        try {
            if (Files.exists(file)) {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) {
                    data = loaded;
                    if (data.loot == null) data.loot = new LinkedHashMap<>();
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read the Crystal Nucleus tracker: " + e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save the Crystal Nucleus tracker: " + e);
        }
    }
}
