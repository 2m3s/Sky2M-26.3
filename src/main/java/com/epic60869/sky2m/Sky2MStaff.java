package com.epic60869.sky2m;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SJ chat rank prefixes. Ranks belong to Minecraft account UUIDs (sent by the mod from your logged-in session),
 * not names, so a nickname can never pick one up; {@link Sky2MNickFilter} also blocks nicknames that look like
 * a ranked account or a rank.
 *
 * Ranks are managed on shadowisabot.com and downloaded from {@value #RANKS_URL} every few minutes:
 * <pre>
 * {"ranks": [
 *   {"uuid": "9761ccfb-2ccb-45cf-b6b4-fac991b7a019", "name": "2m3s", "prefix": "OWNER", "color": "#FF5555", "bold": true},
 *   {"uuid": "...", "name": "Someone", "prefix": "", "color": ""}   // empty prefix removes a built-in rank
 * ]}
 * </pre>
 * The built-in ranks below apply until the site answers, and for accounts the site doesn't list.
 */
public final class Sky2MStaff {
    public static final String RANKS_URL = "https://shadowisabot.com/mod-api/ranks";
    private static final long REFRESH_MINUTES = 5;
    private static final int MAX_PREFIX_LENGTH = 16;

    /** A rank prefix: the text between the brackets, its colour and whether it's bold. */
    public record Rank(String label, int colour, boolean bold) {}

    private record Entry(Rank rank, String name) {}

    private static final Rank OWNER = new Rank("OWNER", 0xFF5555, true);
    private static final Rank TESTER = new Rank("TESTER", 0x5555FF, true);

    private static final Map<UUID, Entry> BUILT_IN = Map.of(
        UUID.fromString("9761ccfb-2ccb-45cf-b6b4-fac991b7a019"), new Entry(OWNER, "2m3s"),
        UUID.fromString("6ed85701-fe2e-4aa4-8428-5a3eb5ac16bc"), new Entry(TESTER, "svinkus"),
        UUID.fromString("458c87ae-6cbc-423d-9f4e-93263ae49220"), new Entry(TESTER, "nixjussid"));

    /** Ranks from the website; an entry with a null rank removes a built-in one. */
    private static volatile Map<UUID, Entry> remote = Map.of();
    private static ScheduledExecutorService scheduler;

    private Sky2MStaff() {}

    public static synchronized void init() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Sky2M rank sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(Sky2MStaff::refresh, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    /** Reloads the ranks right away (the relay sends "ranksUpdated" when they change on the website). */
    public static void refreshNow() {
        if (scheduler != null) scheduler.execute(Sky2MStaff::refresh);
    }

    private static void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(RANKS_URL))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Sky2M")
                .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return; // keep what we have
            JsonElement root = JsonParser.parseString(response.body());
            Iterable<JsonElement> list = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("ranks");
            Map<UUID, Entry> parsed = new HashMap<>();
            for (JsonElement element : list) {
                JsonObject o = element.getAsJsonObject();
                UUID uuid = parseUuid(str(o, "uuid"));
                if (uuid == null) continue;
                String label = cleanLabel(str(o, "prefix"));
                Rank rank = label.isEmpty() ? null : new Rank(label, parseColour(str(o, "color")), !o.has("bold") || o.get("bold").getAsBoolean());
                parsed.put(uuid, new Entry(rank, str(o, "name").toLowerCase(Locale.ROOT)));
            }
            remote = Map.copyOf(parsed);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load ranks from shadowisabot.com: " + e.getMessage());
        }
    }

    private static Map<UUID, Entry> all() {
        Map<UUID, Entry> merged = new HashMap<>(BUILT_IN);
        merged.putAll(remote);
        merged.values().removeIf(entry -> entry.rank() == null);
        return merged;
    }

    public static Rank rank(UUID uuid) {
        if (uuid == null) return null;
        Entry entry = remote.containsKey(uuid) ? remote.get(uuid) : BUILT_IN.get(uuid);
        return entry == null ? null : entry.rank();
    }

    /** "[OWNER] " in the rank's colour, or null for players without a rank. */
    public static MutableComponent prefix(UUID uuid) {
        Rank rank = rank(uuid);
        if (rank == null) return null;
        return Component.literal("[" + rank.label() + "] ").withStyle(Style.EMPTY.withColor(rank.colour()).withBold(rank.bold()));
    }

    /** Every rank in use (built-in and from the website). */
    public static java.util.Collection<Rank> allRanks() {
        java.util.List<Rank> out = new ArrayList<>();
        for (Entry entry : all().values()) if (!out.contains(entry.rank())) out.add(entry.rank());
        return out;
    }

    /** Ranked accounts' usernames (as the site or the built-in list names them) and their ranks. */
    public static Map<String, Rank> ranksByName() {
        Map<String, Rank> out = new HashMap<>();
        for (Entry entry : all().values()) {
            if (entry.name() != null && entry.name().matches("\\w{1,16}")) out.put(entry.name(), entry.rank());
        }
        return out;
    }

    /** Names of ranked accounts, which nobody else can use as a nickname. */
    static List<String> names(UUID except) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : all().entrySet()) {
            String name = e.getValue().name();
            if (!e.getKey().equals(except) && name != null && name.length() >= 3) names.add(name);
        }
        return names;
    }

    /** Rank words (e.g. "owner", "tester"), which can't appear in a nickname. */
    static List<String> roleWords(UUID except) {
        Rank own = rank(except);
        String ownWord = own == null ? "" : own.label().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        List<String> words = new ArrayList<>();
        for (String word : List.of("owner", "tester")) if (!word.equals(ownWord)) words.add(word);
        for (Entry entry : all().values()) {
            String word = entry.rank().label().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
            if (word.length() >= 3 && !words.contains(word) && !word.equals(ownWord)) words.add(word);
        }
        return words;
    }

    private static String cleanLabel(String prefix) {
        String label = prefix.replaceAll("§.", "").replaceAll("[\\[\\]]", "").replaceAll("[^\\p{L}\\p{N} +_.!-]", "").trim();
        return label.length() > MAX_PREFIX_LENGTH ? label.substring(0, MAX_PREFIX_LENGTH).trim() : label;
    }

    private static int parseColour(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return h.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(h, 16) : 0xFFAA00;
    }

    private static UUID parseUuid(String text) {
        String t = text.replace("-", "");
        if (!t.matches("[0-9a-fA-F]{32}")) return null;
        return UUID.fromString(t.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
