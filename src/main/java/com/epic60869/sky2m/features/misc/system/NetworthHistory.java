package com.epic60869.sky2m.features.misc.system;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.profileviewer.Networth;
import com.epic60869.sky2m.features.profileviewer.ProfileApi;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import com.epic60869.sky2m.features.misc.social.Sky2MAchievements;

/**
 * Misc > Networth History: your networth saved over time ({@code sky2m/networth-history.json}) and drawn by
 * {@link NetworthGraphScreen} (/s2 networth). Each snapshot loads your selected profile like the profile viewer and
 * values it like SkyHelper (Sky2M's port of SkyHelper-Networth), museum included, from up-to-date profile data. Snapshots are taken on SkyBlock, at most every few hours.
 */
public final class NetworthHistory {
    private static final Gson GSON = new Gson();

    public static final class Snapshot {
        public long time;
        public long total;
        public String profile;
        /** Worked out like SkyHelper, museum included (snapshots from before that are dropped when loading). */
        boolean skyHelper = true;

        public Snapshot(long time, long total, String profile) {
            this.time = time;
            this.total = total;
            this.profile = profile;
        }
    }

    private static final List<Snapshot> history = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static Path file;
    private static long lastTry;
    private static int ticks;

    private NetworthHistory() {}

    private static FeatureConfigs.NetworthHistory config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.networthHistory;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("networth-history.json");
        load();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 1200 != 0) return;
            FeatureConfigs.NetworthHistory c = config();
            if (c == null || !c.enabled || !Sky2MLocation.onSkyblock()) return;
            long now = System.currentTimeMillis(), every = c.hours * 3_600_000L;
            long last = history.isEmpty() ? 0 : history.getLast().time;
            // Retry a failed snapshot after 15 minutes, not every minute.
            if (now - last >= every && now - lastTry >= 15 * 60_000L) snapshot(false);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("networth")
                    .executes(c -> {
                        open();
                        return 1;
                    })
                    .then(ClientCommands.literal("save").executes(c -> {
                        Minecraft.getInstance().execute(() -> snapshot(true));
                        return 1;
                    }))));
            }
        });
    }

    static List<Snapshot> history() {
        return history;
    }

    /** The Open Graph button and /s2 networth. */
    public static void open() {
        Compat.queueOpenScreen(new NetworthGraphScreen());
    }

    /** Saves your networth now (the Snapshot Now button and /s2 networth save tell you how it went). */
    public static void snapshot(boolean manual) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !BUSY.compareAndSet(false, true)) return;
        lastTry = System.currentTimeMillis();
        if (manual) Sky2MAlerts.chat(Component.literal("Working out your networth...").withStyle(ChatFormatting.GRAY));
        String name = mc.player.getGameProfile().name();
        // Like the profile viewer's networth: the museum too, so the history matches it. A snapshot without the museum
        // would be billions too low on big profiles, so none is saved until the museum loads.
        ProfileApi.playerFresh(name).thenCompose(player -> {
            ProfileApi.Profile profile = player.profiles().getFirst();
            ProfileApi.forgetAux(ProfileApi.Aux.MUSEUM, profile.id());
            return ProfileApi.aux(ProfileApi.Aux.MUSEUM, profile.id()).thenCompose(museumData -> {
                if (museumData == null) throw new IllegalStateException("couldn't load your museum");
                // SkyHelper's networth (Sky2M's port of SkyHelper-Networth), like the profile viewer and the bots.
                return Networth.skyHelper(profile.profile(), profile.member(), museumData, player.uuid());
            }).thenApply(result -> {
                lastResult = result;
                return new Snapshot(System.currentTimeMillis(), result.total(), profile.cuteName());
            });
        }).whenComplete((snap, error) -> mc.execute(() -> {
            BUSY.set(false);
            if (error != null || snap == null) {
                if (manual) Sky2MAlerts.chat(Component.literal("Couldn't work out your networth: " + ProfileApi.reason(error)).withStyle(ChatFormatting.RED));
                return;
            }
            history.add(snap);
            save();
            Sky2MAchievements.max(Sky2MAchievements.Stat.NETWORTH, snap.total);
            if (manual) {
                Sky2MAlerts.chat(Component.literal("Saved your networth: " + CombatFeatures.formatCoins(snap.total) + " (" + snap.profile + ").").withStyle(ChatFormatting.GREEN));
                printBreakdown();
            }
        }));
    }

    private static volatile Networth.Result lastResult;

    /** Each category of the last snapshot, biggest first, and where the profile came from. */
    private static void printBreakdown() {
        Networth.Result r = lastResult;
        if (r == null) return;
        List<Networth.Category> cats = new ArrayList<>(r.categories());
        cats.sort((a, b) -> Long.compare(b.total(), a.total()));
        net.minecraft.network.chat.MutableComponent line = Component.empty();
        boolean first = true;
        for (Networth.Category c : cats) {
            if (c.total() <= 0) continue;
            if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            first = false;
            line.append(Component.literal(c.name() + " ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(CombatFeatures.formatCoins(c.total())).withStyle(ChatFormatting.WHITE));
        }
        Sky2MAlerts.chat(line);
        Sky2MAlerts.chat(Component.literal("Profile data from " + (ProfileApi.lastSource.isEmpty() ? "the last lookup" : ProfileApi.lastSource) + ".").withStyle(ChatFormatting.DARK_GRAY));
    }

    static void delete(Snapshot snap) {
        history.remove(snap);
        save();
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            List<Snapshot> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<ArrayList<Snapshot>>() {}.getType());
            if (read != null) {
                read.removeIf(s -> s.profile == null || !s.skyHelper);
                history.addAll(read);
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new ArrayList<>(history)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
