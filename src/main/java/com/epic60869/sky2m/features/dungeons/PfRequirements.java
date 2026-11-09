package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.tracker.DungeonProfitHour;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.SharedSuggestionProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Party Finder auto-kick requirements (/s2 autokick, also /s2 pfreq and the Requirements button in the Party Finder
 * settings): the Default ones, and for each of F1-F7 and M1-M7 whether it has its own. Each has the slowest S+, the
 * minimum Catacombs level, secrets and magical power (0 = don't check); the Default also has the floor whose S+ is
 * checked. Auto kick uses the floor you're queued for or playing when it has its own, else the Default. Saved in
 * config/sky2m/pf-requirements.json.
 */
public final class PfRequirements {
    public static final class Req {
        /** A floor's own requirements are used (always true for the Default). */
        public boolean enabled;
        public int maxPbSeconds;
        public int minCata;
        /** Before secrets were typed as a count: thousands. Moved into {@link #minSecrets} when read. */
        public int minSecretsK;
        public long minSecrets;
        public int minMagicalPower;
        /** The Default only: the floor whose S+ is checked ("M7"). */
        public String pbFloor;

        boolean checksAnything() {
            return maxPbSeconds > 0 || minCata > 0 || minSecrets > 0 || minMagicalPower > 0;
        }
    }

    /** The requirements for a party: which ones, and the floor whose S+ they check. */
    public record Choice(Req req, String floor, boolean own) {}

    public static final String DEFAULT = "Default";
    static final List<String> FLOORS = List.of("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Req> requirements;

    private PfRequirements() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                for (String name : new String[]{"autokick", "pfreq"}) {
                    dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal(name)
                        .executes(c -> Compat.queueOpenScreen(new PfRequirementsScreen(null, null)))
                        .then(ClientCommands.argument("floor", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(tabs(), b))
                            .executes(c -> Compat.queueOpenScreen(new PfRequirementsScreen(null,
                                tab(StringArgumentType.getString(c, "floor"))))))));
                }
            }
        });
    }

    /** The settings button: opens the screen, going back to the settings when it closes. */
    public static void openFromSettings() {
        Compat.queueOpenScreen(new PfRequirementsScreen(net.minecraft.client.Minecraft.getInstance().gui.screen(), null));
    }

    /** Default, then F1-F7 and M1-M7. */
    static List<String> tabs() {
        List<String> tabs = new ArrayList<>();
        tabs.add(DEFAULT);
        tabs.addAll(FLOORS);
        return tabs;
    }

    /** A typed tab name ("m7", "default"), or null when it's none of them. */
    static String tab(String text) {
        for (String t : tabs()) if (t.equalsIgnoreCase(text.trim())) return t;
        return null;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("pf-requirements.json");
    }

    /** The Default's and every floor's requirements. */
    static synchronized Map<String, Req> all() {
        if (requirements == null) {
            Map<String, Req> loaded = null;
            try {
                if (Files.exists(file())) loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Map<String, Req>>() {}.getType());
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read pf-requirements.json: " + e.getMessage());
            }
            Sky2MConfig c = Sky2MConfig.current();
            boolean migrate = migrate(loaded, c == null ? null : c.dungeons.partyFinderStats);
            requirements = load(loaded, c == null ? null : c.dungeons.partyFinderStats);
            if (migrate) save();
        }
        return requirements;
    }

    private static boolean migrate(Map<String, Req> loaded, FeatureConfigs.PartyFinderStats c) {
        return (loaded == null || !loaded.containsKey(DEFAULT)) && c != null;
    }

    /**
     * Fills in the Default and every floor. Before there was a Default, the requirements were sliders in the Party
     * Finder settings and the floors' own were only used with Per-Floor Requirements on: the sliders become the
     * Default, and with that setting off the floors' own are turned off (their numbers are kept).
     */
    public static Map<String, Req> load(Map<String, Req> loaded, FeatureConfigs.PartyFinderStats old) {
        Map<String, Req> all = new LinkedHashMap<>();
        Req def = loaded != null ? loaded.get(DEFAULT) : null;
        boolean fromSettings = def == null && old != null;
        if (def == null) {
            def = new Req();
            if (old != null) {
                def.maxPbSeconds = old.maxPbSeconds;
                def.minCata = old.minCata;
                def.minSecretsK = old.minSecretsK;
                def.minMagicalPower = old.minMagicalPower;
                def.pbFloor = (old.masterMode ? "M" : "F") + Math.clamp(old.floor, 1, 7);
            }
        }
        def.enabled = true;
        if (def.pbFloor == null || !FLOORS.contains(def.pbFloor)) def.pbFloor = "F7";
        all.put(DEFAULT, tidy(def));
        for (String floor : FLOORS) {
            Req req = loaded != null && loaded.get(floor) != null ? loaded.get(floor) : new Req();
            if (fromSettings && !old.perFloor) req.enabled = false;
            req.pbFloor = null;
            all.put(floor, tidy(req));
        }
        return all;
    }

    private static Req tidy(Req req) {
        if (req.minSecretsK > 0 && req.minSecrets <= 0) req.minSecrets = req.minSecretsK * 1000L;
        req.minSecretsK = 0;
        return req;
    }

    /** The requirements for a party going to {@code floor} ("M5", or null when not known). */
    static Choice forKick(String floor) {
        Req own = floor == null ? null : all().get(floor);
        if (own != null && own.enabled) return new Choice(own, floor, true);
        Req def = all().get(DEFAULT);
        return new Choice(def, def.pbFloor, false);
    }

    static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(all()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save pf-requirements.json: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ text

    /** "1:25", or "" for 0. */
    static String time(int seconds) {
        return seconds <= 0 ? "" : DungeonProfitHour.time(seconds);
    }

    /** A typed time ("1:25", "85", "1m25s") in whole seconds, 0 when empty, or -1 when it isn't a time. */
    public static int parseTime(String text) {
        if (text.isBlank()) return 0;
        double s = DungeonProfitHour.seconds(text);
        return s < 0 ? -1 : (int) Math.round(s);
    }

    /** A typed number ("40", "15k", "1,200", "1.5k"), 0 when empty, or -1 when it isn't one. */
    public static long parseNumber(String text) {
        String t = text.replace(",", "").replace("_", "").trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return 0;
        double mult = t.endsWith("k") ? 1_000 : t.endsWith("m") ? 1_000_000 : 1;
        if (mult != 1) t = t.substring(0, t.length() - 1);
        try {
            double v = Double.parseDouble(t) * mult;
            return v < 0 || Double.isNaN(v) ? -1 : Math.round(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "15k" for 15000, "15,500" for 15500, "" for 0. */
    public static String number(long v) {
        if (v <= 0) return "";
        if (v >= 1000 && v % 1000 == 0) return v / 1000 + "k";
        return String.format(Locale.US, "%,d", v);
    }

    /** What these requirements check, one line each ("S+ on F7 slower than 1:25"). */
    static List<String> describe(Req req, String floor) {
        List<String> lines = new ArrayList<>();
        if (req.maxPbSeconds > 0) lines.add("S+ on " + floor + " slower than " + time(req.maxPbSeconds) + " (or none)");
        if (req.minCata > 0) lines.add("Catacombs below " + req.minCata);
        if (req.minSecrets > 0) lines.add("Secrets below " + number(req.minSecrets));
        if (req.minMagicalPower > 0) lines.add("Magical Power below " + String.format(Locale.US, "%,d", req.minMagicalPower));
        return lines;
    }

    /** A short summary for the floors list ("1:25, Cata 40, 15k secrets, 1,200 MP"). */
    static String brief(Req req) {
        List<String> parts = new ArrayList<>();
        if (req.maxPbSeconds > 0) parts.add(time(req.maxPbSeconds));
        if (req.minCata > 0) parts.add("Cata " + req.minCata);
        if (req.minSecrets > 0) parts.add(number(req.minSecrets) + " secrets");
        if (req.minMagicalPower > 0) parts.add(String.format(Locale.US, "%,d", req.minMagicalPower) + " MP");
        return parts.isEmpty() ? "nothing checked" : String.join(", ", parts);
    }
}
