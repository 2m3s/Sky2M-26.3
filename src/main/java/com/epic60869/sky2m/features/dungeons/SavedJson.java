package com.epic60869.sky2m.features.dungeons;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/** Reads and writes a small saved file the way {@link RoomBests} does, so a bad file is never overwritten. */
public final class SavedJson {
    private SavedJson() {}

    /** A missing file gives {@code empty}. One that can't be read is copied aside first, so the next save can't overwrite what's in it. */
    public static <T> T load(Path file, Function<String, T> parse, T empty) {
        try {
            if (Files.exists(file)) return parse.apply(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load " + file.getFileName() + ": " + e.getMessage());
            String name = file.getFileName().toString().replaceFirst("\\.json$", "");
            try {
                Files.copy(file, file.resolveSibling(name + "-unreadable-" + System.currentTimeMillis() + ".json"));
            } catch (Exception copyFailed) {
                System.err.println("[Sky2M] Could not copy " + file.getFileName() + " aside: " + copyFailed.getMessage());
            }
        }
        return empty;
    }

    public static void save(Path file, String json) {
        try {
            DungeonRoutes.writeSafely(file, json);
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
