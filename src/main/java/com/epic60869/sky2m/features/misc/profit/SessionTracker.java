package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /s2 session: one local-calendar-day record for every built-in profit preset, counted in the background even when
 * an individual profit tracker is disabled. Reopening the game does not start another section for the same day.
 */
public final class SessionTracker {
    /** Every built-in profit preset, including trackers whose HUD is disabled. */
    static final ProfitTracker.Preset[] ACTIVITIES = ProfitTracker.Preset.values();

    static final class Session {
        /** Epoch day this daily section belongs to (local timezone). */
        @Expose long day;
        /** First time Sky2M saw this day. */
        @Expose long started;
        /** When the session was last saved: about when the game closed, for a past session. */
        @Expose long ended;
        @Expose Map<String, ProfitTracker.Stats> activities = new LinkedHashMap<>();
        /** When each activity was first done this session. */
        @Expose Map<String, Long> firstActive = new LinkedHashMap<>();

        ProfitTracker.Stats get(ProfitTracker.Preset preset) {
            return activities.get(preset.name());
        }

        boolean has(ProfitTracker.Preset preset) {
            ProfitTracker.Stats s = get(preset);
            return s != null && (s.activeMs > 0 || !s.items.isEmpty() || s.coins != 0 || s.actions > 0);
        }

        boolean hasAny() {
            for (ProfitTracker.Preset preset : ACTIVITIES) if (has(preset)) return true;
            return false;
        }

        long firstActive(ProfitTracker.Preset preset) {
            return firstActive.getOrDefault(preset.name(), 0L);
        }

        /** When the first activity of the session was done, or the launch if none was. */
        long firstActiveAny() {
            long first = Long.MAX_VALUE;
            for (ProfitTracker.Preset preset : ACTIVITIES) if (has(preset) && firstActive(preset) > 0) first = Math.min(first, firstActive(preset));
            return first == Long.MAX_VALUE ? started : first;
        }

        long playtime() {
            long total = 0;
            for (ProfitTracker.Preset preset : ACTIVITIES) if (get(preset) != null) total += get(preset).activeMs;
            return total;
        }
    }

    static final class Saved {
        @Expose Session current;
        @Expose List<Session> history = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);

    private static final Map<ProfitTracker.Preset, Long> lastActive = new EnumMap<>(ProfitTracker.Preset.class);
    private static Path file;
    private static Saved saved = new Saved();
    private static Session current;
    /** Last game's session, to show in chat once you've joined. */
    private static Session pendingSummary;
    /** The recap taken when you last left a world, shown after you rejoin. */
    private static Component pendingRecap;
    private static boolean dirty;
    private static long lastTick;
    private static int ticks, playerTicks;

    private SessionTracker() {}

    static FeatureConfigs.SessionTrackerSettings config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.profitTrackers.session;
    }

    static boolean on() {
        FeatureConfigs.SessionTrackerSettings c = config();
        return c != null && c.enabled;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("sessions.json");
        load();
        // Keep one record per day across game restarts; only a day rollover creates a new history entry.
        long today = java.time.LocalDate.now().toEpochDay();
        if (saved.current != null) {
            if (saved.current.day == 0) saved.current.day = java.time.Instant.ofEpochMilli(saved.current.started).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
            if (saved.current.day == today) {
                current = saved.current;
            } else {
                if (saved.current.hasAny()) {
                    saved.history.addFirst(saved.current);
                    pendingSummary = saved.current;
                }
                current = new Session();
                current.day = today;
                current.started = System.currentTimeMillis();
                saved.current = current;
            }
        } else {
            current = new Session();
            current.day = today;
            current.started = System.currentTimeMillis();
            saved.current = current;
        }
        // If an older version stored multiple sessions for the currently open day, fold them into today's record.
        if (current != null) {
            saved.history.removeIf(oldDay -> {
                if (oldDay.day != current.day) return false;
                merge(current, oldDay);
                return true;
            });
        }
        trim();
        save();
        ClientTickEvents.END_CLIENT_TICK.register(SessionTracker::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            save();
            SessionShare.upload(current, true);
        });
        SessionShare.init();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root).then(command()).then(recapCommand()));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onLeave());
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return ClientCommands.literal("session")
            .executes(ctx -> SessionTrackerScreen.open(-1))
            .then(ClientCommands.literal("last").executes(ctx -> SessionTrackerScreen.open(saved.history.isEmpty() ? -1 : 0)));
    }

    // ------------------------------------------------------------------------------------------------ recap

    /** /s2 recap: one chat summary across every tracked activity of today's session. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> recapCommand() {
        return ClientCommands.literal("recap").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (current == null || !current.hasAny()) {
                ctx.getSource().sendFeedback(Component.literal("[S2M] Nothing tracked yet this session.").withStyle(ChatFormatting.YELLOW));
                return 1;
            }
            ctx.getSource().sendFeedback(recap(current, "Session recap"));
            return 1;
        });
    }

    /** Playtime, total profit, coins, drops and actions across all activities, then each activity's line. */
    static Component recap(Session s, String title) {
        double coins = 0;
        long drops = 0, actions = 0;
        Map<String, Long> items = new LinkedHashMap<>();
        for (ProfitTracker.Preset preset : ACTIVITIES) {
            ProfitTracker.Stats st = s.get(preset);
            if (st == null) continue;
            coins += st.coins;
            actions += st.actions;
            for (Map.Entry<String, Long> e : st.items.entrySet()) {
                drops += e.getValue();
                items.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        long playtime = s.playtime();
        double profit = profit(s);
        MutableComponent text = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(title + " ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal("(" + duration(playtime) + " played): ").withStyle(ChatFormatting.GRAY))
            .append(signed(profit));
        if (playtime > 60_000) {
            text.append(Component.literal(" (" + ProfitTrackerHud.coins(profit * 3_600_000d / playtime) + "/h)").withStyle(ChatFormatting.GRAY));
        }
        text.append(Component.literal("\n  Coins earned: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(ProfitTrackerHud.coins(coins)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal("  Drops: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.valueOf(drops)).withStyle(ChatFormatting.WHITE));
        if (actions > 0) {
            text.append(Component.literal("  Actions: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(actions)).withStyle(ChatFormatting.WHITE));
        }
        List<Map.Entry<String, Long>> top = new ArrayList<>(items.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (!top.isEmpty()) {
            StringBuilder best = new StringBuilder();
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                if (i > 0) best.append(", ");
                best.append(top.get(i).getValue()).append("x ").append(ProfitTrackerHud.name(top.get(i).getKey()));
            }
            text.append(Component.literal("\n  Top drops: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(best.toString()).withStyle(ChatFormatting.WHITE));
        }
        for (ProfitTracker.Preset preset : ACTIVITIES) {
            if (!s.has(preset)) continue;
            text.append(Component.literal("\n  " + name(preset) + " ").withStyle(colour(preset)))
                .append(Component.literal(duration(s.get(preset).activeMs) + " · ").withStyle(ChatFormatting.GRAY))
                .append(signed(profit(preset, s.get(preset), null)));
        }
        HoverEvent hover = new HoverEvent.ShowText(Component.literal("Click to open /s2 session").withStyle(ChatFormatting.YELLOW));
        return text.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/s2 session")).withHoverEvent(hover));
    }

    /** Leaving the game or server: keep the recap and show it once you're back in a world. */
    private static void onLeave() {
        SessionShare.upload(current, true);
        FeatureConfigs.SessionTrackerSettings c = config();
        if (c == null || !c.recapOnLeave || current == null || !current.hasAny()) return;
        pendingRecap = recap(current, "Recap");
        playerTicks = 0;
    }

    private static void showRecap(Minecraft mc) {
        if (pendingRecap == null || playerTicks < 120) return;
        Component message = pendingRecap;
        pendingRecap = null;
        mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    // ------------------------------------------------------------------------------------------------ counting

    /** Collect every built-in profit preset while daily history is enabled, independent of HUD/feature toggles. */
    static boolean tracks(ProfitTracker.Preset preset) {
        return on() && preset != null;
    }

    /** Where {@link ProfitTracker} adds this preset's drops, coins and actions for the session; null if not tracked. */
    static ProfitTracker.Stats stats(ProfitTracker.Preset preset) {
        if (!tracks(preset) || current == null) return null;
        dirty = true;
        return current.activities.computeIfAbsent(preset.name(), k -> new ProfitTracker.Stats());
    }

    /** You did this activity just now: mined, farmed, dug a burrow, got a drop. */
    static void active(ProfitTracker.Preset preset) {
        if (!tracks(preset) || current == null) return;
        long now = System.currentTimeMillis();
        lastActive.put(preset, now);
        current.firstActive.putIfAbsent(preset.name(), now);
        current.activities.computeIfAbsent(preset.name(), k -> new ProfitTracker.Stats());
    }

    /** Whether this activity's playtime is going right now (done in the last few seconds). */
    static boolean running(ProfitTracker.Preset preset) {
        Long last = lastActive.get(preset);
        FeatureConfigs.SessionTrackerSettings c = config();
        long pause = (c == null ? 10 : Math.clamp(c.pauseSeconds, 5, 60)) * 1000L;
        return last != null && System.currentTimeMillis() - last <= pause;
    }

    private static void tick(Minecraft mc) {
        ticks++;
        long now = System.currentTimeMillis();
        long delta = lastTick == 0 ? 0 : Math.min(1_000L, now - lastTick);
        lastTick = now;
        if (mc.player == null) {
            playerTicks = 0;
            return;
        }
        playerTicks++;
        showSummary(mc);
        showRecap(mc);
        if (!on() || current == null) return;

        // One daily section, regardless of how often the game is launched.
        long today = java.time.LocalDate.now().toEpochDay();
        if (current.day != today) {
            if (current.hasAny()) {
                saved.history.addFirst(current);
                SessionShare.upload(current, false);
                // Playing through midnight: the finished day's breakdown, like on the first launch of a day.
                pendingSummary = current;
                playerTicks = 100;
            }
            current = new Session();
            current.day = today;
            current.started = now;
            saved.current = current;
            lastActive.clear();
            trim();
            dirty = true;
        }

        // Mining and farming are holding attack on a block; Diana is using or swinging the spade.
        ProfitTracker.Preset where = ProfitTracker.currentPreset();
        if (where != null && mc.gui.screen() == null) {
            boolean attack = mc.options.keyAttack.isDown(), use = mc.options.keyUse.isDown();
            boolean onBlock = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
            boolean spade = mc.player.getMainHandItem().getHoverName().getString().contains("Spade");
            if ((where == ProfitTracker.Preset.MINING || where == ProfitTracker.Preset.FARMING) && attack && onBlock) active(where);
            if (where == ProfitTracker.Preset.MYTHOLOGICAL_RITUAL && spade && (attack || use)) active(where);
        }
        for (ProfitTracker.Preset preset : ACTIVITIES) {
            if (!running(preset)) continue;
            stats(preset).activeMs += delta;
        }
        if (dirty && ticks % 600 == 0) save();
    }

    // ------------------------------------------------------------------------------------------------ value

    /** One activity's profit: drops at the chosen price, coins and Kernels, less costs. {@code unknown[0]} if a drop has no price. */
    static double profit(ProfitTracker.Preset preset, ProfitTracker.Stats s, boolean[] unknown) {
        if (s == null) return 0;
        FeatureConfigs.SessionTrackerSettings c = config();
        FeatureConfigs.ProfitPriceSource source = c == null || c.priceSource == null ? FeatureConfigs.ProfitPriceSource.INSTANT_SELL : c.priceSource;
        double total = s.coins - s.costs;
        for (Map.Entry<String, Long> e : s.items.entrySet()) {
            double unit = ProfitItemPrices.price(e.getKey(), source);
            if (unit > 0) total += unit * e.getValue();
            else if (unknown != null) unknown[0] = true;
        }
        if (preset == ProfitTracker.Preset.FARMING && s.kernels > 0) {
            total += ProfitItemPrices.price(ProfitTrackerHud.KERNEL_ITEM, source) * s.kernels / ProfitTrackerHud.KERNELS_PER_ITEM;
        }
        return total;
    }

    static double profit(Session session) {
        double total = 0;
        for (ProfitTracker.Preset preset : ACTIVITIES) total += profit(preset, session.get(preset), null);
        return total;
    }

    // ------------------------------------------------------------------------------------------------ chat breakdown

    private static void showSummary(Minecraft mc) {
        // A few seconds after joining, so it isn't lost among the server's join messages.
        if (pendingSummary == null || playerTicks < 100) return;
        Session s = pendingSummary;
        pendingSummary = null;
        FeatureConfigs.SessionTrackerSettings c = config();
        if (c == null || !c.summaryOnLaunch) return;
        HoverEvent hover = new HoverEvent.ShowText(Component.literal("Click to open it in /s2 session").withStyle(ChatFormatting.YELLOW));
        ClickEvent click = new ClickEvent.RunCommand("/s2 session last");
        MutableComponent text = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("Previous day ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(date(s.firstActiveAny()) + " " + time(s.firstActiveAny()) + ", " + duration(s.playtime()) + ": ").withStyle(ChatFormatting.GRAY))
            .append(signed(profit(s)));
        for (ProfitTracker.Preset preset : ACTIVITIES) {
            if (!s.has(preset)) continue;
            text.append(Component.literal("\n  " + name(preset) + " ").withStyle(colour(preset)))
                .append(Component.literal(time(s.firstActive(preset)) + " · " + duration(s.get(preset).activeMs) + " · ").withStyle(ChatFormatting.GRAY))
                .append(signed(profit(preset, s.get(preset), null)));
        }
        text.append(Component.literal("\n  [Click to view the breakdown]").withStyle(ChatFormatting.YELLOW));
        Component message = text.withStyle(style -> style.withClickEvent(click).withHoverEvent(hover));
        mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    private static Component signed(double value) {
        return Component.literal((value >= 0 ? "+" : "-") + ProfitTrackerHud.coins(Math.abs(value)))
            .withStyle(value >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    // ------------------------------------------------------------------------------------------------ formatting

    static String name(ProfitTracker.Preset preset) {
        return switch (preset) {
            case MYTHOLOGICAL_RITUAL -> "Diana";
            case MINING -> "Mining";
            case FARMING -> "Farming";
            default -> preset.displayName;
        };
    }

    static ChatFormatting colour(ProfitTracker.Preset preset) {
        return switch (preset) {
            case MYTHOLOGICAL_RITUAL -> ChatFormatting.GOLD;
            case MINING -> ChatFormatting.AQUA;
            default -> ChatFormatting.GREEN;
        };
    }

    static String date(long millis) {
        return millis <= 0 ? "-" : DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String time(long millis) {
        return millis <= 0 ? "-" : TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** "1h 02m 05s", "4m 10s", "12s". */
    static String duration(long ms) {
        long s = ms / 1000, h = s / 3600, m = s % 3600 / 60;
        s %= 60;
        if (h > 0) return String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format(Locale.ROOT, "%dm %02ds", m, s);
        return s + "s";
    }

    // ------------------------------------------------------------------------------------------------ sessions

    /** Saves soon: an item was edited or removed by hand. */
    static void markDirty() {
        dirty = true;
    }

    /** The session at {@code index}: -1 is this one, 0 the last game's, and so on. */
    static Session session(int index) {
        if (index < 0 || index >= saved.history.size()) return current;
        return saved.history.get(index);
    }

    /** Today's record followed by the newest past days, at most {@code count} in all (for Share Sessions to Website). */
    static List<Session> recentForShare(int count) {
        List<Session> out = new ArrayList<>();
        if (current != null) out.add(current);
        for (Session s : saved.history) {
            if (out.size() >= count) break;
            out.add(s);
        }
        return out;
    }

    static int pastSessions() {
        return saved.history.size();
    }

    static void delete(int index) {
        if (index < 0 || index >= saved.history.size()) return;
        saved.history.remove(index);
        save();
    }

    private static void trim() {
        FeatureConfigs.SessionTrackerSettings c = config();
        int keep = c == null ? 50 : Math.clamp(c.keep, 1, 100);
        while (saved.history.size() > keep) saved.history.removeLast();
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Saved loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (loaded == null) return;
            if (loaded.history == null) loaded.history = new ArrayList<>();
            loaded.history.removeIf(s -> s == null || s.activities == null);
            for (Session s : loaded.history) fix(s);
            // Migrate older versions' multiple game sessions into one record per local calendar day.
            Map<Long, Session> byDay = new LinkedHashMap<>();
            for (Session oldSession : loaded.history) {
                Session daySession = byDay.get(oldSession.day);
                if (daySession == null) {
                    byDay.put(oldSession.day, oldSession);
                } else {
                    merge(daySession, oldSession);
                }
            }
            loaded.history = new ArrayList<>(byDay.values());
            if (loaded.current != null) {
                if (loaded.current.activities == null) loaded.current = null;
                else {
                    fix(loaded.current);
                    if (loaded.current.day == 0 && loaded.current.started > 0) {
                        loaded.current.day = Instant.ofEpochMilli(loaded.current.started).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
                    }
                }
            }
            saved = loaded;
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the session tracker: " + e.getMessage());
        }
    }

    private static void fix(Session s) {
        if (s.day == 0 && s.started > 0) s.day = Instant.ofEpochMilli(s.started).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
        if (s.firstActive == null) s.firstActive = new LinkedHashMap<>();
        for (ProfitTracker.Stats stats : s.activities.values()) {
            if (stats.items == null) stats.items = new LinkedHashMap<>();
            if (stats.pestKills == null) stats.pestKills = new LinkedHashMap<>();
        }
        s.activities.values().removeIf(java.util.Objects::isNull);
        // Bait counted by older versions comes out of every day's totals.
        for (ProfitTracker.Stats stats : s.activities.values()) stats.items.keySet().removeIf(ProfitTracker::isBait);
    }

    private static void merge(Session into, Session from) {
        into.started = into.started <= 0 ? from.started : from.started <= 0 ? into.started : Math.min(into.started, from.started);
        into.ended = Math.max(into.ended, from.ended);
        if (into.firstActive == null) into.firstActive = new LinkedHashMap<>();
        if (from.firstActive != null) from.firstActive.forEach((key, value) -> into.firstActive.merge(key, value, Math::min));
        if (into.activities == null) into.activities = new LinkedHashMap<>();
        if (from.activities == null) return;
        from.activities.forEach((key, source) -> {
            if (source == null) return;
            ProfitTracker.Stats target = into.activities.computeIfAbsent(key, ignored -> new ProfitTracker.Stats());
            if (target.items == null) target.items = new LinkedHashMap<>();
            if (source.items != null) source.items.forEach((id, amount) -> target.items.merge(id, amount, Long::sum));
            if (target.pestKills == null) target.pestKills = new LinkedHashMap<>();
            if (source.pestKills != null) source.pestKills.forEach((name, amount) -> target.pestKills.merge(name, amount, Long::sum));
            target.coins += source.coins;
            target.kernels += source.kernels;
            target.actions += source.actions;
            target.activeMs += source.activeMs;
            target.costs += source.costs;
        });
    }

    private static void save() {
        dirty = false;
        if (file == null) return;
        try {
            if (current != null) current.ended = System.currentTimeMillis();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the session tracker: " + e.getMessage());
        }
    }
}
