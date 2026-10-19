package com.epic60869.sky2m.features.sbc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, server-relayed name-tag lines for Sky2M users: each player's stats line ("NW 37.47B   Skill Avg 52.4") and
 * their standing on the leaderboard they picked ("Wheat 12,345,678 #4"). The server sends the two apart (stats,
 * boardText, rank) so they're drawn on two lines; an older server only sends the combined {@code line}, drawn as the
 * stats line.
 */
public final class SbcNameTags {
    private record Entry(String username, String stats, String boardText, int rank) {}
    private static final Map<UUID, Entry> LINES = new ConcurrentHashMap<>();
    /** Rank colours: gold, silver and bronze for the top 3, aqua for everyone else. */
    private static final int GOLD = 0xFFAA00, SILVER = 0xC0C0C0, BRONZE = 0xCD7F32, AQUA = 0x55FFFF;
    private static long lastSent;
    private static String lastLine = "";
    private static String lastBoard = "";
    private SbcNameTags() {}

    public static void onConnected() {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "nameTagGet");
        SbcNet.sendQuietly(packet);
        lastSent = 0;
    }

    /**
     * Publish at most every 30 seconds; the backend adds the standing on {@code board} ("collection|WHEAT",
     * "slayer|zombie", "pb|Revenant Horror V", or "" for none).
     */
    public static void publish(String line, String board) {
        if (!SbcNet.online()) return;
        long now = System.currentTimeMillis();
        String clean = ChatFormatting.stripFormatting(line == null ? "" : line);
        if (clean == null) clean = "";
        clean = clean.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 100) clean = clean.substring(0, 100);
        String key = board == null ? "" : board.trim();
        if (now - lastSent < 30_000L && clean.equals(lastLine) && key.equals(lastBoard)) return;
        lastSent = now; lastLine = clean; lastBoard = key;
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "nameTagPublish");
        packet.addProperty("line", clean);
        packet.addProperty("board", key);
        // Older servers only know collections, by id.
        if (key.startsWith("collection|")) packet.addProperty("collectionId", key.substring("collection|".length()));
        SbcNet.sendQuietly(packet);
    }

    public static void handle(String type, JsonObject packet) {
        if ("nameTagTable".equals(type)) {
            LINES.clear();
            JsonArray entries = Sbc.arr(packet, "entries");
            for (var element : entries) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                UUID uuid = Sbc.uuid(Sbc.str(item, "uuid"));
                if (uuid != null) LINES.put(uuid, entry(item));
            }
            return;
        }
        UUID uuid = Sbc.uuid(Sbc.str(packet, "uuid"));
        if (uuid == null) return;
        Entry entry = entry(packet);
        if (entry.stats().isBlank() && entry.boardText().isBlank()) LINES.remove(uuid);
        else LINES.put(uuid, entry);
    }

    private static Entry entry(JsonObject item) {
        // A server that sends "stats" sends the parts apart; an older one only the combined line.
        boolean split = item.has("stats");
        String stats = split ? Sbc.str(item, "stats") : Sbc.str(item, "line");
        String boardText = split ? Sbc.str(item, "boardText") : "";
        int rank = 0;
        try {
            if (item.has("rank") && item.get("rank").isJsonPrimitive()) rank = item.get("rank").getAsInt();
        } catch (NumberFormatException ignored) {}
        return new Entry(Sbc.str(item, "username"), stats, boardText, rank);
    }

    /** A player's shared stats line, or null. */
    public static Component line(UUID uuid) {
        Entry entry = LINES.get(uuid);
        return entry == null || entry.stats().isBlank() ? null : Component.literal(entry.stats());
    }

    /** A player's leaderboard line ("Wheat 12,345,678 #4"), coloured by rank, or null. */
    public static Component boardLine(UUID uuid) {
        Entry entry = LINES.get(uuid);
        return entry == null || entry.boardText().isBlank() ? null : boardLine(entry.boardText(), entry.rank());
    }

    /** "Wheat 12,345,678" in aqua, or gold / silver / bronze for #1-#3, with the rank at the end. */
    public static Component boardLine(String text, int rank) {
        int colour = rank == 1 ? GOLD : rank == 2 ? SILVER : rank == 3 ? BRONZE : AQUA;
        MutableComponent line = Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(colour)));
        if (rank > 0) line.append(Component.literal(" #" + rank).withStyle(style -> style.withColor(TextColor.fromRgb(colour)).withBold(rank <= 3)));
        return line;
    }
}
