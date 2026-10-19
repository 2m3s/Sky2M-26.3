package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Misc > Sacks Value > Value History: your sacks' total value, one point per day per profile, saved like Networth
 * History ({@code sky2m/sack-value-history.json}, the same snapshot list) and drawn by {@link NetworthGraphScreen}
 * (/s2 sacks history). The value comes from {@link SacksValue} whenever it is worked out, so it is only known after
 * the sacks have been opened once; the day's point is replaced by the latest value.
 */
public final class SackValueHistory {
    private static final Gson GSON = new Gson();
    /** Sacks Value works the value out every 30 seconds; the file is written at most this often. */
    private static final long SAVE_GAP_MS = 60_000L;

    private static final List<NetworthHistory.Snapshot> history = new CopyOnWriteArrayList<>();
    private static Path file;
    private static boolean dirty;
    private static long lastSave;
    /** The latest value seen this session, for Save Now: profile key and value. */
    private static String latestProfile;
    private static long latestTotal = -1;
    private static int ticks;

    private SackValueHistory() {}

    private static FeatureConfigs.SacksValue config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.sacksValue;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("sack-value-history.json");
        load();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 1200 == 0 && dirty) save();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("sacks")
                    .then(ClientCommands.literal("history").executes(c -> {
                        open();
                        return 1;
                    }))));
            }
        });
    }

    static List<NetworthHistory.Snapshot> history() {
        return history;
    }

    /** The Open Value Graph button and /s2 sacks history. */
    public static void open() {
        Compat.queueOpenScreen(new NetworthGraphScreen(true));
    }

    /** Sacks Value worked out {@code total} for {@code profile} (any thread): keeps it as today's point. */
    static void record(String profile, long total) {
        if (profile == null || total <= 0) return;
        Minecraft.getInstance().execute(() -> {
            latestProfile = profile;
            latestTotal = total;
            FeatureConfigs.SacksValue c = config();
            if (c == null || !c.history) return;
            put(profile, total, false);
        });
    }

    /** The graph's Save Now: writes the latest value now, or says why there is none. */
    static void saveNow() {
        if (latestProfile == null || latestTotal <= 0) {
            Sky2MAlerts.chat(Component.literal("Your sacks' value isn't known yet: turn on Misc > Sacks Value and open your sacks.").withStyle(ChatFormatting.RED));
            return;
        }
        put(latestProfile, latestTotal, true);
        Sky2MAlerts.chat(Component.literal("Saved your sacks' value: " + CombatFeatures.formatCoins(latestTotal) + ".").withStyle(ChatFormatting.GREEN));
    }

    private static void put(String profile, long total, boolean force) {
        long now = System.currentTimeMillis();
        LocalDate today = day(now);
        NetworthHistory.Snapshot todays = null;
        for (NetworthHistory.Snapshot s : history) if (profile.equals(s.profile) && day(s.time).equals(today)) todays = s;
        if (todays == null) {
            history.add(new NetworthHistory.Snapshot(now, total, profile));
        } else {
            if (todays.total == total && !force) return;
            todays.time = now;
            todays.total = total;
        }
        dirty = true;
        if (force || todays == null || now - lastSave >= SAVE_GAP_MS) save();
    }

    private static LocalDate day(long time) {
        return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            List<NetworthHistory.Snapshot> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<ArrayList<NetworthHistory.Snapshot>>() {}.getType());
            if (read != null) {
                read.removeIf(s -> s == null || s.profile == null);
                history.addAll(read);
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        dirty = false;
        lastSave = System.currentTimeMillis();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new ArrayList<>(history)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
