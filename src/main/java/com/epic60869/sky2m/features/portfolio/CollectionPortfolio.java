package com.epic60869.sky2m.features.portfolio;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collection portfolio: like the portfolio's value graph, but for your collections. Every 30 minutes on SkyBlock your
 * collections are read from Elite (elitebot.dev, the same copy of the Hypixel API the collection tracker uses) and
 * saved to config/sky2m/portfolio/collections.json, per profile. /s2 collections then shows how much each one has
 * gone up over the last day, week, month or since tracking began, with a graph.
 *
 * Old snapshots are thinned out (one every 6 hours after a week, one a day after 90 days) so the file stays small.
 */
public final class CollectionPortfolio {
    private static final Gson GSON = new GsonBuilder().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final String API = "https://api.elitebot.dev";
    private static final long SNAPSHOT_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    /** The time periods the screen can show. */
    public enum Period {
        DAY("24h", CollectionPortfolio.DAY), WEEK("7d", 7 * CollectionPortfolio.DAY), MONTH("30d", 30 * CollectionPortfolio.DAY),
        ALL("All", Long.MAX_VALUE);

        public final String label;
        public final long millis;

        Period(String label, long millis) {
            this.label = label;
            this.millis = millis;
        }
    }

    /** Your collections at one moment. */
    public static final class Snapshot {
        public long time;
        public Map<String, Long> amounts = new HashMap<>();
    }

    /** One collection over a period: its total now, and how much it went up since {@code since}. */
    public record Change(String id, long total, long gained, long since, long until) {
        /** Gained per day, or 0 when the period is too short to say. */
        public double perDay() {
            long span = until - since;
            return span < HOUR ? 0 : gained * (double) DAY / span;
        }
    }

    /** "uuid/profileId" -> snapshots, oldest first. */
    private static Map<String, List<Snapshot>> history = new HashMap<>();
    private static Path file;
    private static String profileKey = "";
    private static long lastFetch;
    private static long lastAttempt;
    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static volatile String status = "";
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    private CollectionPortfolio() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("portfolio").resolve("collections.json");
        load();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || !Sky2MLocation.onSkyblock() || !enabled()) return;
            long now = System.currentTimeMillis();
            if (now - lastFetch > SNAPSHOT_MS && now - lastAttempt > RETRY_MS) refresh();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("collections")
                    .executes(c -> {
                        refresh();
                        return Compat.queueOpenScreen(new CollectionPortfolioScreen());
                    })));
            }
        });
    }

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null || config.misc.collectionPortfolio;
    }

    // ----- Reading -----

    /** Snapshots of the profile you're on (or last were on), oldest first. */
    public static List<Snapshot> snapshots() {
        if (!profileKey.isEmpty()) return history.getOrDefault(profileKey, List.of());
        // Not fetched yet this session: the most recently updated profile of this account.
        String uuid = playerUuid();
        List<Snapshot> best = List.of();
        for (Map.Entry<String, List<Snapshot>> e : history.entrySet()) {
            if (!e.getKey().startsWith(uuid + "/") || e.getValue().isEmpty()) continue;
            if (best.isEmpty() || e.getValue().getLast().time > best.getLast().time) best = e.getValue();
        }
        return best;
    }

    /** How each collection changed over the period, biggest gain first. */
    public static List<Change> changes(Period period) {
        List<Snapshot> snaps = snapshots();
        if (snaps.isEmpty()) return List.of();
        Snapshot latest = snaps.getLast();
        Snapshot base = baseline(snaps, latest.time, period);
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : latest.amounts.entrySet()) {
            long before = base.amounts.getOrDefault(e.getKey(), 0L);
            out.add(new Change(e.getKey(), e.getValue(), Math.max(0, e.getValue() - before), base.time, latest.time));
        }
        out.sort((a, b) -> a.gained() != b.gained() ? Long.compare(b.gained(), a.gained()) : Long.compare(b.total(), a.total()));
        return out;
    }

    /** The last snapshot at or before the period's start, or the first one when tracking began later than that. */
    private static Snapshot baseline(List<Snapshot> snaps, long now, Period period) {
        if (period == Period.ALL) return snaps.getFirst();
        long start = now - period.millis;
        Snapshot base = snaps.getFirst();
        for (Snapshot s : snaps) {
            if (s.time > start) break;
            base = s;
        }
        return base;
    }

    /** One collection's total over the period, for the graph: {time, amount} pairs. */
    public static List<long[]> graph(String id, Period period) {
        List<Snapshot> snaps = snapshots();
        if (snaps.isEmpty()) return List.of();
        Snapshot base = baseline(snaps, snaps.getLast().time, period);
        List<long[]> points = new ArrayList<>();
        for (Snapshot s : snaps) {
            if (s.time < base.time) continue;
            Long amount = s.amounts.get(id);
            if (amount != null) points.add(new long[]{s.time, amount});
        }
        return points;
    }

    /** When tracking began for this profile, or 0. */
    public static long trackedSince() {
        List<Snapshot> snaps = snapshots();
        return snaps.isEmpty() ? 0 : snaps.getFirst().time;
    }

    public static long lastUpdate() {
        List<Snapshot> snaps = snapshots();
        return snaps.isEmpty() ? 0 : snaps.getLast().time;
    }

    public static String status() {
        return status;
    }

    public static String name(String id) {
        return switch (id) {
            case "MUSHROOM_COLLECTION" -> "Mushroom";
            case "GEMSTONE_COLLECTION" -> "Gemstone";
            default -> {
                String name = RepoItems.displayName(id.replace(':', '-'));
                String plain = name == null ? null : ChatFormatting.stripFormatting(name);
                if (plain != null && !plain.isBlank()) yield plain.trim();
                String words = id.replace(':', ' ').replace('_', ' ').toLowerCase(Locale.ROOT);
                StringBuilder out = new StringBuilder();
                for (String w : words.split(" ")) {
                    if (w.isEmpty()) continue;
                    if (!out.isEmpty()) out.append(' ');
                    out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                }
                yield out.toString();
            }
        };
    }

    public static ItemStack icon(String id) {
        ItemStack cached = ICONS.get(id);
        if (cached != null) return cached;
        String neuId = switch (id) {
            case "MUSHROOM_COLLECTION" -> "RED_MUSHROOM";
            case "GEMSTONE_COLLECTION" -> "ROUGH_RUBY_GEM";
            default -> id.replace(':', '-');
        };
        ItemStack stack = RepoItems.itemStack(neuId);
        if (RepoItems.itemsLoaded()) ICONS.put(id, stack);
        return stack;
    }

    // ----- Fetching -----

    /** Reads your collections from Elite now (at most once every 30 seconds). */
    public static void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 30_000L || !FETCHING.compareAndSet(false, true)) return;
        lastAttempt = now;
        status = "Updating...";
        String uuid = playerUuid();
        String version = FabricLoader.getInstance().getModContainer("sky2m")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/profile/" + uuid + "/selected"))
            .timeout(Duration.ofSeconds(15)).header("User-Agent", "Sky2M/" + version).GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
            String profileId = profile.has("profileId") ? profile.get("profileId").getAsString() : "";
            // When Hypixel's API last updated the profile (Elite gives seconds), so the gain is dated right.
            long updated = profile.has("lastUpdated") && profile.get("lastUpdated").isJsonPrimitive()
                ? profile.get("lastUpdated").getAsLong() * 1000L : 0L;
            Snapshot snap = new Snapshot();
            snap.time = updated > 0 ? Math.min(updated, System.currentTimeMillis()) : System.currentTimeMillis();
            if (profile.has("collections") && profile.get("collections").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : profile.getAsJsonObject("collections").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) snap.amounts.put(e.getKey(), e.getValue().getAsLong());
                }
            }
            Minecraft.getInstance().execute(() -> {
                FETCHING.set(false);
                if (profileId.isEmpty() || snap.amounts.isEmpty()) {
                    status = "Elite has no collections for you yet (your Collections API may be off).";
                    return;
                }
                profileKey = uuid + "/" + profileId;
                lastFetch = System.currentTimeMillis();
                add(profileKey, snap);
                status = "";
            });
        }).exceptionally(e -> {
            Minecraft.getInstance().execute(() -> {
                FETCHING.set(false);
                status = "Couldn't reach Elite. Trying again in a few minutes.";
            });
            return null;
        });
    }

    private static void add(String key, Snapshot snap) {
        List<Snapshot> list = history.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.isEmpty()) {
            Snapshot last = list.getLast();
            // Elite hasn't seen a new copy of your profile: nothing to add.
            if (snap.time <= last.time || snap.amounts.equals(last.amounts)) return;
        }
        list.add(snap);
        thin(list);
        save();
    }

    /** Keeps everything from the last week, one snapshot per 6 hours before that, and one a day after 90 days. */
    private static void thin(List<Snapshot> list) {
        long now = System.currentTimeMillis();
        List<Snapshot> kept = new ArrayList<>();
        long lastBucket = Long.MIN_VALUE;
        for (int i = 0; i < list.size(); i++) {
            Snapshot s = list.get(i);
            long age = now - s.time;
            boolean first = i == 0;
            if (first || age < 7 * DAY) {
                kept.add(s);
                lastBucket = Long.MIN_VALUE;
                continue;
            }
            long bucket = s.time / (age < 90 * DAY ? 6 * HOUR : DAY);
            if (bucket != lastBucket) kept.add(s);
            lastBucket = bucket;
        }
        if (kept.size() != list.size()) {
            list.clear();
            list.addAll(kept);
        }
    }

    // ----- Saving -----

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, List<Snapshot>> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, List<Snapshot>>>() {}.getType());
            if (loaded != null) {
                history = new HashMap<>();
                loaded.forEach((k, v) -> history.put(k, new ArrayList<>(v)));
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read collections.json: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(history), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save collections.json: " + e.getMessage());
        }
    }

    private static String playerUuid() {
        return Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
    }
}
