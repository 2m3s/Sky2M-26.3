package com.epic60869.sky2m;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Carries settings over from the mod's old names, once, before anything reads them: every config/skyballs... and
 * config/skyjew... file, and the config/skyballs and config/skyjew folders, are copied to their config/sky2m... names
 * (SkyBalls' first, as the newer), and keybinds saved under the old names in options.txt get the new names too. The old
 * files are left as a backup.
 */
public final class Sky2MMigration implements PreLaunchEntrypoint {
    private static final String NEW = "sky2m";
    /** Newest first: a file already carried over from a newer name isn't overwritten by an older one. */
    private static final List<String> OLD = List.of("skyballs", "skyjew");

    @Override
    public void onPreLaunch() {
        Path config = FabricLoader.getInstance().getConfigDir();
        for (String old : OLD) {
            try (Stream<Path> entries = Files.list(config)) {
                for (Path from : entries.toList()) {
                    String name = from.getFileName().toString();
                    if (!name.startsWith(old)) continue;
                    Path target = config.resolve(NEW + name.substring(old.length()));
                    if (Files.exists(target)) continue;
                    copy(from, target);
                }
            } catch (IOException e) {
                System.err.println("[Sky2M] Could not carry over old settings: " + e.getMessage());
            }
        }
        migrateKeybinds(FabricLoader.getInstance().getGameDir().resolve("options.txt"));
    }

    /** "key_key.skyballs.protect_item:key.keyboard.p" -> also "key_key.sky2m.protect_item:...", unless it's set already. */
    private static void migrateKeybinds(Path options) {
        try {
            if (!Files.exists(options)) return;
            List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);
            List<String> added = new java.util.ArrayList<>();
            for (String line : lines) {
                for (String old : OLD) {
                    String prefix = "key_key." + old + ".";
                    if (!line.startsWith(prefix)) continue;
                    String renamed = "key_key." + NEW + "." + line.substring(prefix.length());
                    String key = renamed.substring(0, renamed.indexOf(':') < 0 ? renamed.length() : renamed.indexOf(':') + 1);
                    boolean present = lines.stream().anyMatch(l -> l.startsWith(key)) || added.stream().anyMatch(l -> l.startsWith(key));
                    if (!present) added.add(renamed);
                }
            }
            if (added.isEmpty()) return;
            List<String> out = new java.util.ArrayList<>(lines);
            out.addAll(added);
            Files.write(options, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Sky2M] Could not carry over old keybinds: " + e.getMessage());
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        if (Files.isDirectory(from)) {
            try (Stream<Path> walk = Files.walk(from)) {
                for (Path source : walk.toList()) {
                    Path dest = to.resolve(from.relativize(source).toString());
                    if (Files.isDirectory(source)) Files.createDirectories(dest);
                    else Files.copy(source, dest);
                }
            }
        } else {
            Files.copy(from, to);
        }
    }
}
