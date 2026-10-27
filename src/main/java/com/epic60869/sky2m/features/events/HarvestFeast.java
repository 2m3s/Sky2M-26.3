package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.garden.skyhanni.FarmingHud;
import com.epic60869.sky2m.features.garden.skyhanni.GardenCrop;
import com.epic60869.sky2m.tools.Sky2MSkyblockTime;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harvest Feast crop rotation, like SkyHanni's HarvestFeastManager (https://github.com/hannibal002/SkyHanni, LGPL-2.1):
 * a HUD with the crops in season during the Harvest Feast (or Grand Feast), how long until they rotate and which crops
 * come in season next. The rotation is fetched from EliteSkyBlock (api.eliteskyblock.com/harvest-feast/current, the
 * data SkyHanni users share) and read from Feast Chef Ted's All Crops menu when you open it.
 */
public final class HarvestFeast {
    private static final String HUD = "events_harvest_feast";
    private static final String API = "https://api.eliteskyblock.com/harvest-feast/current";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final long RETRY_MS = 5 * 60_000L;
    private static final Pattern FEAST_MENU = Pattern.compile("(?:Harvest|(?<grand>Grand)) Feast");
    private static final Pattern OUT_OF_SEASON = Pattern.compile("Out of season until the next Feast!");
    private static final Pattern WILL_BE_IN_SEASON = Pattern.compile("Will be in-season in (?<time>.+)!");
    private static final Pattern TIME_PART = Pattern.compile("(\\d+)\\s*([dhms])");

    /** One month's rotation: the crops in season now and when each other crop comes in season (0 = not this Feast). */
    private record Feast(int year, int month, boolean grand, List<GardenCrop> current, Map<GardenCrop, Long> next) {}

    private static volatile Feast feast;
    private static volatile boolean loading;
    private static volatile long fetchedAt;
    private static boolean grandMenu;
    private static int ticks;
    private static List<Component> lines = List.of();

    private HarvestFeast() {}

    private static EventsConfig.HarvestFeast config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.harvestFeast;
    }

    static void init() {
        Sky2MHuds.setting(HUD, () -> config() != null && config().display);
        Sky2MHuds.register(HUD, "Harvest Feast",
            () -> {
                EventsConfig.HarvestFeast c = config();
                return c != null && c.display && Sky2MLocation.onSkyblock() && (c.showOutsideGarden || Sky2MLocation.inGarden());
            },
            () -> lines,
            List.of(Component.literal("§6Harvest Feast"), Component.literal("§aIn-season §7(§b9h 50m§7)"),
                Component.literal(" §e● Carrot"), Component.literal(" §e● Cocoa Beans"), Component.literal(" §e● Sugar Cane"),
                Component.literal(" §e● Moonflower"), Component.literal("§7Next §7(§b9h 50m§7): §fMelon, Mushroom, Nether Wart, Sunflower")),
            5, 300);
        ClientTickEvents.END_CLIENT_TICK.register(HarvestFeast::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ContainerScreen container) || !Sky2MLocation.onSkyblock()) return;
            String title = Sky2MLocation.strip(screen.getTitle().getString()).trim();
            Matcher menu = FEAST_MENU.matcher(title);
            if (menu.matches()) {
                grandMenu = menu.group("grand") != null;
            } else if (title.equals("All Crops")) {
                int[] counter = {0};
                ScreenEvents.afterTick(screen).register(s -> {
                    if (++counter[0] % 10 == 2) readAllCrops(container);
                });
            }
        });
    }

    private static void tick(Minecraft mc) {
        EventsConfig.HarvestFeast c = config();
        if (c == null || !c.display || mc.player == null || !Sky2MLocation.onSkyblock()) return;
        if (++ticks % 20 != 0) return;
        long now = System.currentTimeMillis();
        if (c.fetchAutomatically && !isCurrent(feast) && !loading && now - fetchedAt > RETRY_MS) fetch();
        lines = build(now);
    }

    // ---------------------------------------------------------------------------------------------- HUD

    /** What the display shows now (empty when there is no rotation for this month). */
    public static List<Component> hudLines() {
        return build(System.currentTimeMillis());
    }

    private static List<Component> build(long now) {
        Feast f = feast;
        if (!isCurrent(f) || f.current.isEmpty()) return List.of();
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(f.grand ? "§6§lGrand Feast" : "§6Harvest Feast"));
        // The current crops leave when the next ones arrive; with nothing coming this Feast they stay to the month's end.
        TreeMap<Long, List<GardenCrop>> upcoming = new TreeMap<>();
        for (Map.Entry<GardenCrop, Long> e : f.next.entrySet()) {
            if (e.getValue() > now) upcoming.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        long rotation = upcoming.isEmpty() ? monthEnd(now) : upcoming.firstKey();
        out.add(Component.literal("§aIn-season §7(§b" + FarmingHud.duration((rotation - now) / 1000) + "§7)"));
        for (GardenCrop crop : f.current) out.add(Component.literal(" §e● " + crop.displayName));
        for (Map.Entry<Long, List<GardenCrop>> e : upcoming.entrySet()) {
            List<String> names = new ArrayList<>();
            for (GardenCrop crop : e.getValue()) names.add(crop.displayName);
            out.add(Component.literal("§7Next §7(§b" + FarmingHud.duration((e.getKey() - now) / 1000) + "§7): §f" + String.join(", ", names)));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- time

    /** {SkyBlock year, month 1-12} of a moment. */
    private static int[] date(long millis) {
        long elapsed = millis - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli();
        int year = (int) (elapsed / Sky2MSkyblockTime.YEAR_MILLIS) + 1;
        int month = (int) (elapsed % Sky2MSkyblockTime.YEAR_MILLIS / Sky2MSkyblockTime.MONTH_MILLIS) + 1;
        return new int[]{year, month};
    }

    private static long monthEnd(long now) {
        long elapsed = now - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli();
        return now + Sky2MSkyblockTime.MONTH_MILLIS - elapsed % Sky2MSkyblockTime.MONTH_MILLIS;
    }

    /** The data is for this SkyBlock month. */
    private static boolean isCurrent(Feast f) {
        if (f == null) return false;
        int[] now = date(System.currentTimeMillis());
        return f.year == now[0] && f.month == now[1];
    }

    // ---------------------------------------------------------------------------------------------- EliteSkyBlock

    private static void fetch() {
        loading = true;
        fetchedAt = System.currentTimeMillis();
        Thread thread = new Thread(() -> {
            try {
                String version = FabricLoader.getInstance().getModContainer("sky2m")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
                HttpRequest request = HttpRequest.newBuilder(URI.create(API))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Sky2M/" + version)
                    .GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                Feast fetched = parse(JsonParser.parseString(response.body()).getAsJsonObject());
                // A menu read this month is first-hand, so it isn't replaced by shared data.
                if (fetched != null && !isCurrent(feast)) feast = fetched;
            } catch (Exception e) {
                System.err.println("[Sky2M] Could not fetch the Harvest Feast rotation: " + e.getMessage());
            } finally {
                loading = false;
            }
        }, "Sky2M Harvest Feast");
        thread.setDaemon(true);
        thread.start();
    }

    private static Feast parse(JsonObject json) {
        if (!json.has("year") || !json.has("month") || !json.has("current")) return null;
        List<GardenCrop> current = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("current")) {
            GardenCrop crop = GardenCrop.byName(e.getAsString());
            if (crop != null) current.add(crop);
        }
        Map<GardenCrop, Long> next = new EnumMap<>(GardenCrop.class);
        if (json.has("next") && json.get("next").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("next").entrySet()) {
                GardenCrop crop = GardenCrop.byName(e.getKey());
                if (crop == null) continue;
                next.put(crop, e.getValue().isJsonNull() ? 0L : e.getValue().getAsLong() * 1000L);
            }
        }
        boolean grand = json.has("isGrandFeast") && json.get("isGrandFeast").getAsBoolean();
        return new Feast(json.get("year").getAsInt(), json.get("month").getAsInt(), grand, current, next);
    }

    // ---------------------------------------------------------------------------------------------- menu

    /**
     * Feast Chef Ted's All Crops menu: each crop says "Will be in-season in 9h 50m!", "Out of season until the next
     * Feast!", or (for the crops in season now) neither.
     */
    private static void readAllCrops(ContainerScreen container) {
        var slots = container.getMenu().slots;
        int size = Math.max(0, slots.size() - 36);
        List<GardenCrop> current = new ArrayList<>();
        Map<GardenCrop, Long> next = new EnumMap<>(GardenCrop.class);
        long now = System.currentTimeMillis();
        for (int i = 0; i < size; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            GardenCrop crop = GardenCrop.byName(Sky2MLocation.strip(stack.getHoverName().getString()));
            if (crop == null || next.containsKey(crop) || current.contains(crop)) continue;
            Long when = null;
            for (String line : lore(stack)) {
                if (OUT_OF_SEASON.matcher(line).find()) {
                    when = 0L;
                    break;
                }
                Matcher m = WILL_BE_IN_SEASON.matcher(line);
                if (m.find()) {
                    long seconds = seconds(m.group("time"));
                    if (seconds >= 0) when = now + seconds * 1000;
                    break;
                }
            }
            if (when == null) {
                current.add(crop);
                next.put(crop, 0L);
            } else {
                next.put(crop, when);
            }
        }
        if (current.isEmpty()) return;
        int[] date = date(now);
        feast = new Feast(date[0], date[1], grandMenu, current, next);
    }

    private static long seconds(String text) {
        Matcher m = TIME_PART.matcher(text);
        long total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> n * 86400;
                case "h" -> n * 3600;
                case "m" -> n * 60;
                default -> n;
            };
        }
        return any ? total : -1;
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Component c : lore.lines()) out.add(Sky2MLocation.strip(c.getString()).trim());
        return out;
    }
}
