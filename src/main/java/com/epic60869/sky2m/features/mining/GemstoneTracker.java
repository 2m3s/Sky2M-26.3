package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.price.ItemPriceResolver;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.slayer.SlayerBossProfit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mining > Gemstone Tracker: gemstones you mine in the Crystal Hollows, Dwarven Mines (Glacite Tunnels) and
 * Mineshafts, per gemstone type, as Flawed equivalents (1 Fine = 80 Flawed, 1 Rough = 1/80 Flawed) or raw counts by
 * tier, with their coin value, Flawed gemstones and coins per hour (the clock pauses when no gemstone has come in for a
 * while), pristine procs and the Mining Fiesta's Glossy Gemstones. Gemstones are counted from the "[Sacks]" messages'
 * hover (most go straight into your sacks) and from what turns up in your inventory with no menu open; gemstones taken
 * out of your sacks (/gfs or a negative "[Sacks]" change) aren't counted again when they reach your inventory. The
 * session resets when the game starts; the all-time totals are kept in {@code sky2m/gemstone-tracker.json}.
 * {@code /s2 resetgemstones [session]} resets them.
 */
public final class GemstoneTracker {
    public static final String HUD_ID = "gemstone_tracker";
    static final String GLOSSY_ID = "GLOSSY_GEMSTONE";
    private static final String GLOSSY_NAME = "Glossy Gemstone";

    /** Gemstone tiers and how many Flawed gemstones each is worth. */
    private enum Tier {
        ROUGH(1.0 / 80), FLAWED(1), FINE(80), FLAWLESS(6_400), PERFECT(512_000);

        final double flawed;
        final String label;

        Tier(double flawed) {
            this.flawed = flawed;
            this.label = name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    /** The gemstone types and their colour, as Hypixel colours them. */
    private static final Map<String, ChatFormatting> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("RUBY", ChatFormatting.RED);
        TYPES.put("AMBER", ChatFormatting.GOLD);
        TYPES.put("SAPPHIRE", ChatFormatting.AQUA);
        TYPES.put("JADE", ChatFormatting.GREEN);
        TYPES.put("AMETHYST", ChatFormatting.DARK_PURPLE);
        TYPES.put("TOPAZ", ChatFormatting.YELLOW);
        TYPES.put("JASPER", ChatFormatting.LIGHT_PURPLE);
        TYPES.put("OPAL", ChatFormatting.WHITE);
        TYPES.put("AQUAMARINE", ChatFormatting.DARK_AQUA);
        TYPES.put("CITRINE", ChatFormatting.DARK_RED);
        TYPES.put("ONYX", ChatFormatting.DARK_GRAY);
        TYPES.put("PERIDOT", ChatFormatting.DARK_GREEN);
    }

    /** "Flawed Ruby Gemstone" (a [Sacks] hover line's item name) or the item id "FLAWED_RUBY_GEM". */
    private static final Pattern GEM_NAME = Pattern.compile("^(Rough|Flawed|Fine|Flawless|Perfect) (\\w+) Gemstone$");
    private static final Pattern GEM_ID = Pattern.compile("^(ROUGH|FLAWED|FINE|FLAWLESS|PERFECT)_([A-Z]+)_GEM$");
    /** SkyHanni's mining.pristine pattern (same as {@link PristineRecord}). */
    private static final Pattern PRISTINE = Pattern.compile("^PRISTINE! You found . Flawed (?<gemstone>\\w+) Gemstone x(?<amount>\\d+)!$");
    /** "Moved 64 Flawed Ruby Gemstone from your Sacks to your inventory." (/gfs), as CollectionTracker reads it. */
    private static final Pattern FROM_SACKS = Pattern.compile("^Moved ([0-9,]+) (.+?) from your Sacks to your inventory\\.?$");

    /** The HUD shows this long after the last gemstone; the per-hour clock stops after IDLE_MS (sack messages come every ~30s). */
    private static final long SHOW_MS = 5 * 60_000L, IDLE_MS = 90_000L, CREDIT_MS = 60_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Counts for a session or all time. */
    private static final class Stats {
        /** Item id ("FLAWED_RUBY_GEM") -> amount. */
        Map<String, Long> gems = new LinkedHashMap<>();
        long glossy;
        long pristineProcs;
        long pristineGems;
        long activeMs;
    }

    private static Stats session = new Stats();
    private static Stats total = new Stats();
    private static Path file;
    private static boolean dirty;
    private static long lastGain, lastTick;
    private static int ticks;
    private static Map<String, Integer> lastInventory;
    /** Gemstones just taken out of your sacks: id -> {amount, expires at}. */
    private static final Map<String, long[]> credits = new HashMap<>();
    private static List<Component> cachedLines = List.of();

    private GemstoneTracker() {}

    private static FeatureConfigs.GemstoneTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.gemstones;
    }

    private static boolean enabled() {
        FeatureConfigs.GemstoneTracker c = config();
        return c != null && c.enabled;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("gemstone-tracker.json");
        load();
        Sky2MChat.onChat(GemstoneTracker::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(GemstoneTracker::tick);
        Sky2MLocation.onAreaChange(area -> lastInventory = null);
        Sky2MHuds.register(HUD_ID, "Gemstone Tracker", () -> enabled() && Sky2MLocation.inMiningIsland(),
            () -> cachedLines, preview(), 10, 200);
        Sky2MHuds.setting(HUD_ID, GemstoneTracker::enabled);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("resetgemstones")
                    .executes(c -> {
                        reset(false);
                        return 1;
                    })
                    .then(ClientCommands.literal("session").executes(c -> {
                        reset(true);
                        return 1;
                    }))));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ counting

    private static void onChat(Sky2MChat.Message message) {
        if (!enabled() || !Sky2MLocation.inMiningIsland()) return;
        String text = message.text().trim();
        Matcher pristine = PRISTINE.matcher(text);
        if (pristine.matches()) {
            // The gemstones themselves arrive in the [Sacks] message (or your inventory): only count the proc here.
            long amount = Long.parseLong(pristine.group("amount"));
            for (Stats stats : List.of(session, total)) {
                stats.pristineProcs++;
                stats.pristineGems += amount;
            }
            activity();
            return;
        }
        Matcher moved = FROM_SACKS.matcher(text);
        if (moved.matches()) {
            String id = idOf(moved.group(2).trim());
            if (id != null) credit(id, Long.parseLong(moved.group(1).replace(",", "")));
            return;
        }
        if (!text.startsWith("[Sacks]")) return;
        for (Map.Entry<String, Long> e : SlayerBossProfit.sackGains(message.component()).entrySet()) {
            String id = idOf(e.getKey());
            if (id == null) continue;
            if (e.getValue() > 0) add(id, e.getValue());
            else if (e.getValue() < 0) credit(id, -e.getValue());
        }
    }

    /** The item id for a gemstone or Glossy Gemstone's name, else null. */
    private static String idOf(String name) {
        name = Sky2MLocation.strip(name).trim();
        if (name.equals(GLOSSY_NAME)) return GLOSSY_ID;
        Matcher m = GEM_NAME.matcher(name);
        if (!m.matches()) return null;
        String type = m.group(2).toUpperCase(Locale.ROOT);
        return TYPES.containsKey(type) ? m.group(1).toUpperCase(Locale.ROOT) + "_" + type + "_GEM" : null;
    }

    private static boolean tracked(String id) {
        if (id.equals(GLOSSY_ID)) return true;
        Matcher m = GEM_ID.matcher(id);
        return m.matches() && TYPES.containsKey(m.group(2));
    }

    private static void credit(String id, long amount) {
        long now = System.currentTimeMillis();
        long[] old = credits.get(id);
        long keep = old == null || old[1] < now ? 0 : old[0];
        // A /gfs can show up both as "Moved ..." and as a negative [Sacks] change: don't add them up.
        credits.put(id, new long[]{Math.max(keep, amount), now + CREDIT_MS});
    }

    private static void add(String id, long amount) {
        if (amount <= 0) return;
        for (Stats stats : List.of(session, total)) {
            if (id.equals(GLOSSY_ID)) stats.glossy += amount;
            else stats.gems.merge(id, amount, Long::sum);
        }
        activity();
    }

    private static void activity() {
        lastGain = System.currentTimeMillis();
        dirty = true;
    }

    private static void tick(Minecraft mc) {
        ticks++;
        long now = System.currentTimeMillis();
        long delta = lastTick == 0 ? 0 : Math.min(1_000L, now - lastTick);
        lastTick = now;
        if (dirty && ticks % 200 == 0) save();
        if (!enabled() || mc.player == null || !Sky2MLocation.inMiningIsland()) {
            lastInventory = null;
            if (ticks % 10 == 0) cachedLines = List.of();
            return;
        }
        if (lastGain > 0 && now - lastGain < IDLE_MS && mc.isWindowActive()) {
            session.activeMs += delta;
            total.activeMs += delta;
        }
        credits.values().removeIf(c -> c[1] < now);
        if (mc.gui.screen() != null) {
            // Menus move items around (storage, sacks, crafting): start again from what's there once it's closed.
            lastInventory = null;
        } else {
            Map<String, Integer> inventory = inventory(mc);
            if (lastInventory != null) {
                for (Map.Entry<String, Integer> e : inventory.entrySet()) {
                    long gained = e.getValue() - lastInventory.getOrDefault(e.getKey(), 0);
                    if (gained <= 0) continue;
                    long[] credit = credits.get(e.getKey());
                    if (credit != null) {
                        long used = Math.min(credit[0], gained);
                        credit[0] -= used;
                        gained -= used;
                        if (credit[0] <= 0) credits.remove(e.getKey());
                    }
                    add(e.getKey(), gained);
                }
            }
            lastInventory = inventory;
        }
        if (ticks % 10 == 0) cachedLines = lines(now);
    }

    /** Gemstones and Glossy Gemstones in your inventory (not the armour or offhand slots). */
    private static Map<String, Integer> inventory(Minecraft mc) {
        Map<String, Integer> counts = new HashMap<>();
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = Sky2MPriceTooltip.marketId(stack);
            if (!id.isEmpty() && tracked(id)) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------------------------------------ HUD

    private record TypeTotal(String type, double flawed, double value, Map<Tier, Long> tiers) {}

    private static List<TypeTotal> byType(Stats stats) {
        Map<String, TypeTotal> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : stats.gems.entrySet()) {
            Matcher m = GEM_ID.matcher(e.getKey());
            if (!m.matches()) continue;
            Tier tier = Tier.valueOf(m.group(1));
            String type = m.group(2);
            TypeTotal old = out.get(type);
            Map<Tier, Long> tiers = old == null ? new java.util.EnumMap<>(Tier.class) : old.tiers();
            tiers.merge(tier, e.getValue(), Long::sum);
            double value = Math.max(0, ItemPriceResolver.value(e.getKey())) * e.getValue();
            out.put(type, new TypeTotal(type, (old == null ? 0 : old.flawed()) + tier.flawed * e.getValue(),
                (old == null ? 0 : old.value()) + value, tiers));
        }
        List<TypeTotal> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingDouble(TypeTotal::value).thenComparingDouble(TypeTotal::flawed).reversed());
        return list;
    }

    private static List<Component> lines(long now) {
        FeatureConfigs.GemstoneTracker c = config();
        if (c == null || lastGain == 0 || now - lastGain > SHOW_MS) return List.of();
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Gemstone Tracker").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        List<TypeTotal> types = byType(session);
        double flawed = 0, coins = 0;
        for (TypeTotal t : types) {
            flawed += t.flawed();
            coins += t.value();
            MutableComponent line = Component.literal(name(t.type()) + ": ").withStyle(TYPES.getOrDefault(t.type(), ChatFormatting.WHITE));
            if (c.flawedEquivalent) {
                line.append(Component.literal(amount(t.flawed()) + " Flawed").withStyle(ChatFormatting.WHITE));
            } else {
                boolean first = true;
                for (Tier tier : Tier.values()) {
                    Long n = t.tiers().get(tier);
                    if (n == null || n <= 0) continue;
                    if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                    line.append(Component.literal(String.format(Locale.US, "%,d %s", n, tier.label)).withStyle(ChatFormatting.WHITE));
                    first = false;
                }
            }
            if (t.value() > 0) line.append(Component.literal(" (" + CombatFeatures.formatCoins(t.value()) + ")").withStyle(ChatFormatting.GOLD));
            out.add(line);
        }
        if (types.isEmpty()) out.add(Component.literal("No gemstones yet").withStyle(ChatFormatting.GRAY));
        double glossyValue = Math.max(0, ItemPriceResolver.value(GLOSSY_ID)) * session.glossy;
        coins += glossyValue;
        if (c.showGlossy) {
            out.add(kv("Glossy Gemstones: ", String.format(Locale.US, "%,d", session.glossy), ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(glossyValue > 0 ? " (" + CombatFeatures.formatCoins(glossyValue) + ")" : "").withStyle(ChatFormatting.GOLD)));
        }
        out.add(kv("Total: ", amount(flawed) + " Flawed", ChatFormatting.WHITE)
            .append(Component.literal(" (" + CombatFeatures.formatCoins(coins) + ")").withStyle(ChatFormatting.GOLD)));
        double hours = session.activeMs / 3_600_000.0;
        if (hours > 0.001) {
            out.add(kv("Per hour: ", amount(flawed / hours) + " Flawed", ChatFormatting.WHITE)
                .append(Component.literal(" (" + CombatFeatures.formatCoins(coins / hours) + ")").withStyle(ChatFormatting.GOLD)));
        }
        if (c.showPristine && session.pristineProcs > 0) {
            out.add(kv("Pristine: ", String.format(Locale.US, "%,d procs (%,d Flawed)", session.pristineProcs, session.pristineGems), ChatFormatting.LIGHT_PURPLE));
        }
        boolean idle = now - lastGain >= IDLE_MS;
        out.add(kv("Uptime: ", duration(session.activeMs) + (idle ? " (paused)" : ""), idle ? ChatFormatting.RED : ChatFormatting.AQUA));
        if (c.showAllTime) {
            double allFlawed = 0, allCoins = Math.max(0, ItemPriceResolver.value(GLOSSY_ID)) * total.glossy;
            for (TypeTotal t : byType(total)) {
                allFlawed += t.flawed();
                allCoins += t.value();
            }
            out.add(kv("All time: ", amount(allFlawed) + " Flawed", ChatFormatting.WHITE)
                .append(Component.literal(" (" + CombatFeatures.formatCoins(allCoins) + ")").withStyle(ChatFormatting.GOLD)));
            if (c.showGlossy && total.glossy > 0) {
                out.add(kv("All-time Glossy: ", String.format(Locale.US, "%,d", total.glossy), ChatFormatting.LIGHT_PURPLE));
            }
        }
        return out;
    }

    private static List<Component> preview() {
        return List.of(
            Component.literal("Gemstone Tracker").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
            Component.literal("Jade: ").withStyle(ChatFormatting.GREEN).append(Component.literal("1,284 Flawed").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (4.1M)").withStyle(ChatFormatting.GOLD)),
            Component.literal("Amber: ").withStyle(ChatFormatting.GOLD).append(Component.literal("932.5 Flawed").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (2.8M)").withStyle(ChatFormatting.GOLD)),
            kv("Glossy Gemstones: ", "3", ChatFormatting.LIGHT_PURPLE).append(Component.literal(" (1.2M)").withStyle(ChatFormatting.GOLD)),
            kv("Total: ", "2,216.5 Flawed", ChatFormatting.WHITE).append(Component.literal(" (8.1M)").withStyle(ChatFormatting.GOLD)),
            kv("Per hour: ", "3,912 Flawed", ChatFormatting.WHITE).append(Component.literal(" (14.3M)").withStyle(ChatFormatting.GOLD)),
            kv("Pristine: ", "412 procs (1,104 Flawed)", ChatFormatting.LIGHT_PURPLE),
            kv("Uptime: ", "34m 2s", ChatFormatting.AQUA));
    }

    private static MutableComponent kv(String key, String value, ChatFormatting colour) {
        return Component.literal(key).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(colour));
    }

    private static String name(String type) {
        return type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String amount(double flawed) {
        return flawed >= 100 || flawed == Math.rint(flawed)
            ? String.format(Locale.US, "%,d", Math.round(flawed))
            : String.format(Locale.US, "%,.1f", flawed);
    }

    private static String duration(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600, minutes = seconds / 60 % 60;
        return hours > 0 ? String.format(Locale.US, "%dh %dm", hours, minutes) : String.format(Locale.US, "%dm %ds", minutes, seconds % 60);
    }

    // ------------------------------------------------------------------------------------------------ saving

    /** The Reset button and /s2 resetgemstones: the session only, or the session and the all-time totals. */
    public static void reset(boolean sessionOnly) {
        session = new Stats();
        if (!sessionOnly) total = new Stats();
        lastGain = 0;
        cachedLines = List.of();
        save();
        Sky2MAlerts.chat(Component.literal(sessionOnly ? "Gemstone Tracker session reset." : "Gemstone Tracker reset.").withStyle(ChatFormatting.YELLOW));
    }

    public static void resetAll() {
        reset(false);
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Stats read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stats.class);
            if (read != null) {
                if (read.gems == null) read.gems = new LinkedHashMap<>();
                total = read;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read the gemstone tracker: " + e);
        }
    }

    private static void save() {
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(total), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save the gemstone tracker: " + e);
        }
    }
}
