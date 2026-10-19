package com.epic60869.sky2m.reports;

import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.loader.api.FabricLoader;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Game info added under a bug report, so the team doesn't have to ask: the Sky2M and Minecraft versions, where you
 * are on SkyBlock, and the latest warnings and errors from logs/latest.log. Chat lines are never included.
 */
final class ReportAttachment {
    private static final int TAIL_BYTES = 512 * 1024;
    private static final int MAX_LINES = 25;
    private static final int MAX_LINE = 180;
    /** WARN/ERROR lines, exceptions and stack trace lines. */
    private static final Pattern PROBLEM = Pattern.compile("/(WARN|ERROR|FATAL)\\]|Exception|^\\s+at |Caused by:");
    private static final Pattern CHAT = Pattern.compile("\\[CHAT\\]|\\[System\\] \\[CHAT\\]");

    private ReportAttachment() {}

    /** The block to add under the description, at most {@code room} characters (empty if nothing fits). */
    static String build(int room) {
        if (room < 120) return "";
        StringBuilder out = new StringBuilder("\n\n--- Game info (added by Sky2M) ---\n");
        out.append("Sky2M ").append(version("sky2m")).append(" · Minecraft ").append(version("minecraft"))
            .append(" · Fabric Loader ").append(version("fabricloader")).append('\n');
        out.append("Location: ").append(location()).append('\n');
        out.append("Mods: ").append(FabricLoader.getInstance().getAllMods().size()).append(" loaded\n");
        List<String> problems = recentProblems();
        if (!problems.isEmpty()) {
            out.append("Recent warnings/errors:\n");
            // Newest last; drop the oldest until it fits.
            int start = 0;
            while (start < problems.size() && out.length() + joinedLength(problems, start) > room) start++;
            for (int i = start; i < problems.size(); i++) out.append(problems.get(i)).append('\n');
        }
        String text = out.toString();
        return text.length() > room ? text.substring(0, room) : text;
    }

    private static int joinedLength(List<String> lines, int from) {
        int n = 0;
        for (int i = from; i < lines.size(); i++) n += lines.get(i).length() + 1;
        return n;
    }

    private static String version(String mod) {
        return FabricLoader.getInstance().getModContainer(mod).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private static String location() {
        if (!Sky2MLocation.onSkyblock()) return "not on SkyBlock";
        String area = Sky2MLocation.area(), place = Sky2MLocation.location(), floor = Sky2MLocation.dungeonFloor();
        StringBuilder text = new StringBuilder(area == null || area.isBlank() ? "SkyBlock" : area);
        if (place != null && !place.isBlank() && !place.equalsIgnoreCase(area)) text.append(" / ").append(place);
        if (floor != null && !floor.isBlank()) text.append(" (").append(floor).append(')');
        return text.toString();
    }

    /** The last warnings and errors in logs/latest.log, oldest first, without chat. */
    private static List<String> recentProblems() {
        Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        List<String> out = new ArrayList<>();
        if (!Files.exists(log)) return out;
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long from = Math.max(0, length - TAIL_BYTES);
            byte[] bytes = new byte[(int) (length - from)];
            file.seek(from);
            file.readFully(bytes);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0 && out.size() < MAX_LINES; i--) {
                String line = lines[i];
                if (line.isBlank() || CHAT.matcher(line).find() || !PROBLEM.matcher(line).find()) continue;
                // No paths with the Windows user name in them.
                line = line.replaceAll("(?i)[A-Z]:\\\\Users\\\\[^\\\\]+", "C:\\\\Users\\\\<you>").replaceAll("/home/[^/]+", "/home/<you>");
                out.add(0, line.length() > MAX_LINE ? line.substring(0, MAX_LINE) + "..." : line);
            }
        } catch (Exception e) {
            out.add("(couldn't read latest.log: " + e.getClass().getSimpleName().toLowerCase(Locale.ROOT) + ")");
        }
        return out;
    }
}
