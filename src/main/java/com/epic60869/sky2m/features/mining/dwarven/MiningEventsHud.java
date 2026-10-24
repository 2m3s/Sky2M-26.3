package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.Sky2MBossOverlayAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mining events display and notifications, after SkyHanni's MiningEventTracker / MiningEventDisplay / MiningNotifications
 * (hannibal002/SkyHanni, LGPL-2.1). The HUD lists the events other servers report through the community Soopy API
 * (api.soopy.dev); Sky2M only reads from it and never sends your server or events. The events of the server you are on are
 * read from the boss bar and the "... STARTED!" chat message for the start alert.
 */
public final class MiningEventsHud {
    private static final String ID = "mining_events";
    private static final String URL = "https://api.soopy.dev/skyblock/chevents/get";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final Pattern BAR_PASSIVE = Pattern.compile("^PASSIVE EVENT (.+) RUNNING FOR (\\S+)$");
    private static final Pattern BAR_ACTIVE = Pattern.compile("^EVENT (.+) ACTIVE IN (.+) for (\\S+)$");
    private static final Pattern STARTED = Pattern.compile("^\\s*([A-Z0-9][A-Z0-9 ]+) STARTED!$");

    private static final Map<String, String> EVENT_NAMES = Map.of(
        "GONE_WITH_THE_WIND", "Gone with the Wind",
        "DOUBLE_POWDER", "2x Powder",
        "GOBLIN_RAID", "Goblin Raid",
        "BETTER_TOGETHER", "Better Together",
        "RAFFLE", "Raffle",
        "MITHRIL_GOURMAND", "Mithril Gourmand");
    private static final Map<String, String> ISLAND_NAMES = new LinkedHashMap<>();

    static {
        ISLAND_NAMES.put("DWARVEN_MINES", "Dwarven Mines");
        ISLAND_NAMES.put("CRYSTAL_HOLLOWS", "Crystal Hollows");
    }

    private record Running(String event, long endsAt, boolean isDouble) {}

    private static volatile Map<String, List<Running>> running = Map.of();
    /** Server clock minus local clock, from the API's own "curr_time". */
    private static volatile long clockOffset;
    private static volatile long nextFetch;
    private static volatile boolean fetching;
    private static volatile boolean apiError;
    private static int ticks;

    /** The event on this server from the boss bar, with the time it says is left. */
    private static String currentEvent;
    private static long currentEnds;
    private static long lastStartAlert;

    private MiningEventsHud() {}

    private static boolean miningIsland() {
        return Sky2MLocation.inDwarvenMines() || Sky2MLocation.inCrystalHollows();
    }

    public static void init() {
        Sky2MChat.onChat(message -> onChat(message.text()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 20 == 0) tick(mc);
        });
        Sky2MHuds.setting(ID, () -> DwarvenMining.events() != null && DwarvenMining.events().hud);
        Sky2MHuds.register(ID, "Mining Events", MiningEventsHud::visible, MiningEventsHud::lines,
            List.of(Component.literal("Dwarven Mines: ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("Goblin Raid (3:20)").withStyle(ChatFormatting.RED)),
                Component.literal("Crystal Hollows: ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("2x Powder (12:05)").withStyle(ChatFormatting.AQUA))),
            8, 300);
    }

    private static boolean visible() {
        DwarvenConfig.Events c = DwarvenMining.events();
        return c != null && c.hud && Sky2MLocation.onSkyblock() && (c.outsideMining || miningIsland());
    }

    private static void onChat(String text) {
        DwarvenConfig.Events c = DwarvenMining.events();
        if (c == null || !Sky2MLocation.onSkyblock()) return;
        boolean mining = miningIsland() || Sky2MLocation.inMineshaft();
        if (!mining) return;
        String trimmed = text.trim();
        if (c.goldenGoblin && trimmed.equals("A Golden Goblin has spawned!")) {
            Sky2MAlerts.title(Component.literal("Golden Goblin").withStyle(ChatFormatting.GOLD), null);
        } else if (c.diamondGoblin && trimmed.equals("A Diamond Goblin has spawned!")) {
            Sky2MAlerts.title(Component.literal("Diamond Goblin").withStyle(ChatFormatting.AQUA), null);
        } else if (c.scrap && trimmed.equals("EXCAVATOR! You found a Suspicious Scrap!")) {
            Sky2MAlerts.title(Component.literal("Suspicious Scrap").withStyle(ChatFormatting.BLUE), null);
        } else if (c.mineshaft && trimmed.equals("WOW! You found a Glacite Mineshaft portal!")) {
            Sky2MAlerts.title(Component.literal("Glacite Mineshaft").withStyle(ChatFormatting.AQUA), null);
        }
        if (c.startAlert) {
            Matcher m = STARTED.matcher(text);
            if (m.matches() && isEventName(m.group(1)) && System.currentTimeMillis() - lastStartAlert > 5000) {
                lastStartAlert = System.currentTimeMillis();
                Sky2MAlerts.title(Component.literal(title(m.group(1))).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                    Component.literal("Mining event started").withStyle(ChatFormatting.WHITE));
            }
        }
    }

    private static boolean isEventName(String name) {
        for (String display : EVENT_NAMES.values()) {
            if (display.equalsIgnoreCase(name) || name.equals("2X POWDER") || name.equals("RAFFLE")) return true;
        }
        return false;
    }

    private static String title(String upper) {
        for (String display : EVENT_NAMES.values()) if (display.equalsIgnoreCase(upper)) return display;
        return upper.equals("2X POWDER") ? "2x Powder" : upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
    }

    private static void tick(Minecraft mc) {
        DwarvenConfig.Events c = DwarvenMining.events();
        if (c == null || mc.player == null || !Sky2MLocation.onSkyblock()) return;
        if (miningIsland()) readBossBar(mc);
        else currentEvent = null;
        if (c.hud && (c.outsideMining || miningIsland()) && !fetching && System.currentTimeMillis() >= nextFetch) fetch();
    }

    private static void readBossBar(Minecraft mc) {
        String found = null;
        long ends = 0;
        for (LerpingBossEvent bar : ((Sky2MBossOverlayAccessor) mc.gui.hud.getBossOverlay()).sky2m$getEvents().values()) {
            String name = Sky2MLocation.strip(bar.getName().getString()).trim();
            Matcher passive = BAR_PASSIVE.matcher(name);
            Matcher active = BAR_ACTIVE.matcher(name);
            if (passive.matches()) {
                found = title(passive.group(1));
            } else if (active.matches()) {
                found = title(active.group(1));
                ends = parseTime(active.group(3));
            }
        }
        currentEvent = found;
        currentEnds = ends > 0 ? System.currentTimeMillis() + ends : 0;
    }

    /** "01:03" -> 63000 ms, "1:02:03" too; 0 when it isn't a time. */
    private static long parseTime(String text) {
        String[] parts = text.split(":");
        try {
            long seconds = 0;
            for (String p : parts) seconds = seconds * 60 + Long.parseLong(p);
            return seconds * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void fetch() {
        fetching = true;
        nextFetch = System.currentTimeMillis() + 60_000;
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Sky2M").GET().build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (response.statusCode() != 200 || !root.has("success") || !root.get("success").getAsBoolean()) throw new IllegalStateException("bad response");
                JsonObject data = root.getAsJsonObject("data");
                Map<String, List<Running>> out = new LinkedHashMap<>();
                JsonObject events = data.getAsJsonObject("running_events");
                for (String island : ISLAND_NAMES.keySet()) {
                    List<Running> list = new ArrayList<>();
                    JsonArray array = events.has(island) ? events.getAsJsonArray(island) : new JsonArray();
                    for (JsonElement e : array) {
                        JsonObject o = e.getAsJsonObject();
                        list.add(new Running(o.get("event").getAsString(), (long) o.get("ends_at").getAsDouble(),
                            o.has("is_double") && o.get("is_double").getAsBoolean()));
                    }
                    list.sort((a, b) -> Long.compare(a.endsAt(), b.endsAt()));
                    out.put(island, list);
                }
                clockOffset = (long) data.get("curr_time").getAsDouble() - System.currentTimeMillis();
                running = out;
                apiError = false;
                nextFetch = System.currentTimeMillis() + Math.max(60_000L, (long) data.get("update_in").getAsDouble());
            } catch (Exception e) {
                apiError = true;
                nextFetch = System.currentTimeMillis() + 5 * 60_000L;
            } finally {
                fetching = false;
            }
        });
    }

    private static List<Component> lines() {
        DwarvenConfig.Events c = DwarvenMining.events();
        List<Component> out = new ArrayList<>();
        if (c == null) return out;
        if (apiError && running.isEmpty()) {
            out.add(Component.literal("Mining Event API Error!").withStyle(ChatFormatting.RED));
            return out;
        }
        if (currentEvent != null && miningIsland()) {
            Component line = Component.literal("Here: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(currentEvent).withStyle(ChatFormatting.YELLOW));
            if (currentEnds > 0) line = line.copy().append(Component.literal(" (" + format(currentEnds - System.currentTimeMillis()) + ")").withStyle(ChatFormatting.GRAY));
            out.add(line);
        }
        long now = System.currentTimeMillis() + clockOffset;
        for (Map.Entry<String, String> island : ISLAND_NAMES.entrySet()) {
            boolean show = switch (c.islands) {
                case DWARVEN -> island.getKey().equals("DWARVEN_MINES");
                case CRYSTAL -> island.getKey().equals("CRYSTAL_HOLLOWS");
                case CURRENT -> island.getKey().equals("DWARVEN_MINES") ? Sky2MLocation.inDwarvenMines() : Sky2MLocation.inCrystalHollows();
                default -> true;
            };
            if (!show) continue;
            List<Running> events = running.getOrDefault(island.getKey(), List.of());
            Component line = Component.literal(island.getValue() + ": ").withStyle(ChatFormatting.GREEN);
            boolean any = false;
            for (Running r : events) {
                if (r.endsAt() <= now) continue;
                if (any) line = line.copy().append(Component.literal(" -> ").withStyle(ChatFormatting.DARK_GRAY));
                String name = EVENT_NAMES.getOrDefault(r.event(), r.event());
                line = line.copy().append(Component.literal(name + (r.isDouble() ? " x2" : "") + " (" + format(r.endsAt() - now) + ")").withStyle(ChatFormatting.AQUA));
                any = true;
            }
            if (!any) line = line.copy().append(Component.literal("???").withStyle(ChatFormatting.GRAY));
            out.add(line);
        }
        if (out.isEmpty() && fetching) out.add(Component.literal("Loading mining events...").withStyle(ChatFormatting.GRAY));
        return out;
    }

    private static String format(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
