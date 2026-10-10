package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.sbc.Flags;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.epic60869.sky2m.features.sbc.SbcConfig;
import com.epic60869.sky2m.features.sbc.SbcInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Upcoming SkyBlock events from the Sky2M server's calendar (refreshed every 10 minutes; times are real-world): a
 * HUD and /s2 calendar with countdowns, and reminders (popup + sound) a chosen number of minutes before the events
 * you turn on. Reminders are scheduled here, so they work between refreshes.
 */
public final class EventCalendar {
    public record Event(String id, String name, long start, long end, boolean active, List<String> crops) {}

    private static final String URL = "https://shadowisabot.com/mod-api/api/v1/calendar?hours=72";
    private static final long REFRESH_MS = 10 * 60_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static volatile List<Event> events = List.of();
    static volatile String skyblockDate = "";
    static volatile String mayor = "";
    static volatile String minister = "";
    static volatile long loadedAt;
    private static volatile boolean loading;
    private static final Set<String> REMINDED = new HashSet<>();
    private static int ticks;

    private EventCalendar() {}

    private static SbcConfig.EventCalendar config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? new SbcConfig.EventCalendar() : c.misc.eventCalendar;
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("calendar").executes(c -> {
                    if (!Flags.check("calendar")) return 1;
                    refresh();
                    return Compat.queueOpenScreen(new EventCalendarScreen());
                })));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 20 != 0 || mc.player == null || !Flags.isEnabled("calendar")) return;
            SbcConfig.EventCalendar c = config();
            if (!c.hud && !c.reminders && !(mc.gui.screen() instanceof EventCalendarScreen)) return;
            if (System.currentTimeMillis() - loadedAt > REFRESH_MS) refresh();
            if (c.reminders) remind(c);
        });
        Sky2MHuds.register("eventCalendar", "SkyBlock Events", () -> config().hud && Flags.isEnabled("calendar"), EventCalendar::hudLines,
            List.of(Component.literal("SkyBlock Events").withStyle(ChatFormatting.GOLD),
                Component.literal("Dark Auction: ").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal("12m 3s").withStyle(ChatFormatting.WHITE)),
                Component.literal("Jacob's Contest: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("NOW").withStyle(ChatFormatting.GREEN))), 8, 120);
    }

    /** Downloads the calendar (off the game thread). */
    public static void refresh() {
        if (loading) return;
        loading = true;
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Sky2M/" + SbcInfo.modVersion()).GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() != 200) return;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            List<Event> list = new ArrayList<>();
            for (JsonElement e : Sbc.arr(root, "events")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                List<String> crops = new ArrayList<>();
                for (JsonElement crop : Sbc.arr(o, "crops")) crops.add(crop.getAsString());
                list.add(new Event(Sbc.str(o, "id"), Sbc.str(o, "name"), Sbc.lng(o, "start", 0), Sbc.lng(o, "end", 0), Sbc.bool(o, "active"), crops));
            }
            list.sort((a, b) -> Long.compare(a.start(), b.start()));
            events = List.copyOf(list);
            JsonObject sb = Sbc.obj(root, "skyblock");
            skyblockDate = sb.size() == 0 ? "" : Sbc.str(sb, "monthName") + " " + Sbc.lng(sb, "day", 0) + ", Year " + Sbc.lng(sb, "year", 0);
            mayor = nameOf(root, "mayor");
            minister = nameOf(root, "minister");
            loadedAt = System.currentTimeMillis();
        }).whenComplete((v, e) -> {
            loading = false;
            // Failed: try again in a minute, not every second.
            if (e != null || loadedAt == 0) loadedAt = System.currentTimeMillis() - REFRESH_MS + 60_000L;
        });
    }

    private static String nameOf(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) return "";
        if (root.get(key).isJsonPrimitive()) return root.get(key).getAsString();
        return Sbc.str(root.getAsJsonObject(key), "name");
    }

    /** Whether reminders are on for this event, and how many minutes before it. */
    static int reminderMinutes(SbcConfig.EventCalendar c, Event e) {
        return switch (e.id()) {
            case "jacob" -> c.jacob && cropsWanted(c, e) ? c.jacobMinutes : -1;
            case "dark_auction" -> c.darkAuction ? c.darkAuctionMinutes : -1;
            case "fallen_star" -> c.fallenStar ? c.fallenStarMinutes : -1;
            case "spooky" -> c.spooky ? c.spookyMinutes : -1;
            case "new_year" -> c.newYear ? c.newYearMinutes : -1;
            case "jerry" -> c.jerry ? c.jerryMinutes : -1;
            case "zoo_summer", "zoo_winter" -> c.zoo ? c.zooMinutes : -1;
            case "hoppity" -> c.hoppity ? c.hoppityMinutes : -1;
            case "election_open", "election_close" -> c.elections ? c.electionsMinutes : -1;
            case "fishing_festival", "mythological_ritual" -> c.mayorEvents ? c.mayorEventsMinutes : -1;
            default -> -1;
        };
    }

    private static boolean cropsWanted(SbcConfig.EventCalendar c, Event e) {
        if (c.jacobCrops == null || c.jacobCrops.isBlank() || e.crops().isEmpty()) return true;
        for (String wanted : c.jacobCrops.split(",")) {
            for (String crop : e.crops()) if (crop.trim().equalsIgnoreCase(wanted.trim())) return true;
        }
        return false;
    }

    private static void remind(SbcConfig.EventCalendar c) {
        long now = System.currentTimeMillis();
        for (Event e : events) {
            int minutes = reminderMinutes(c, e);
            if (minutes < 0) continue;
            long at = e.start() - minutes * 60_000L;
            // Due now, and not something that already started long ago (e.g. just after joining).
            if (now < at || now > e.start() + 30_000L) continue;
            String key = e.id() + "@" + e.start();
            if (!REMINDED.add(key)) continue;
            String when = e.start() - now > 1000 ? "in " + Sbc.duration(e.start() - now) : "now";
            String crops = e.crops().isEmpty() ? "" : " (" + String.join(", ", e.crops()) + ")";
            Sbc.toast(Component.literal(e.name() + " " + when).withStyle(ChatFormatting.GOLD), Component.literal(crops.isEmpty() ? "SkyBlock event" : crops.trim()));
            if (c.sound) Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BELL.value(), 1.2f, 0.8f);
        }
        if (REMINDED.size() > 500) REMINDED.clear();
    }

    /** "12m 3s", or "NOW (ends in 5m)" for an event in progress. */
    static String countdown(Event e, long now) {
        if (e.active() || (now >= e.start() && now < e.end())) return "NOW" + (e.end() > now ? " (ends in " + Sbc.duration(e.end() - now) + ")" : "");
        return Sbc.duration(e.start() - now);
    }

    static int colour(String id) {
        return switch (id) {
            case "jacob" -> 0xFFFF55;
            case "dark_auction" -> 0xAA00AA;
            case "fallen_star" -> 0xFF55FF;
            case "spooky" -> 0xFFAA00;
            case "new_year" -> 0x55FFFF;
            case "jerry" -> 0xFFFFFF;
            case "zoo_summer", "zoo_winter" -> 0x55FF55;
            case "hoppity" -> 0xFF5555;
            case "election_open", "election_close" -> 0x5555FF;
            default -> 0xAAAAAA;
        };
    }

    private static List<Component> hudLines() {
        List<Event> list = events;
        if (list.isEmpty()) return List.of();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("SkyBlock Events").withStyle(ChatFormatting.GOLD));
        int count = 0;
        for (Event e : list) {
            if (e.end() > 0 ? e.end() < now : e.start() < now) continue;
            String crops = e.id().equals("jacob") && !e.crops().isEmpty() ? " " + shortCrops(e.crops()) : "";
            lines.add(Component.literal(e.name() + crops + ": ").withStyle(s -> s.withColor(colour(e.id())))
                .append(Component.literal(countdown(e, now)).withStyle(now >= e.start() ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
            if (++count >= config().hudCount) break;
        }
        return lines;
    }

    private static String shortCrops(List<String> crops) {
        List<String> out = new ArrayList<>();
        for (String c : crops) out.add(c.length() > 6 ? c.substring(0, 6) : c);
        return "(" + String.join("/", out).toLowerCase(Locale.ROOT) + ")";
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
