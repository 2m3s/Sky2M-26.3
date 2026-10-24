package com.epic60869.sky2m.features.garden.helpers;

import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.sim.util.TabText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Farming weight display, from SkyHanni's FarmingWeightData and weight display (LGPL-2.1,
 * https://github.com/hannibal002/SkyHanni): your farming weight from Elite (api.elitebot.dev, the same copy of the
 * Hypixel API the collection tracker uses), the crop factors from Elite's /weights/all (SkyHanni's backup values if
 * that fails), the weight you gain per hour and the time to a goal. Elite only refreshes every couple of hours, so
 * between refreshes the weight grows by an estimate: crops broken times their base drops times your Farming Fortune,
 * divided by the crop's factor. Each time Elite sends newer data the estimate is dropped and Elite's weight is used.
 */
final class FarmingWeightHud {
    private static final String HUD = "garden_farming_weight";
    private static final String API = "https://api.elitebot.dev";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final long REFRESH_MS = 10 * 60_000L;
    private static final long ACTIVE_WINDOW_MS = 4_000L;
    private static final Pattern TAB_FORTUNE = Pattern.compile("Farming Fortune:\\s*\\S?\\s*([\\d,]+)");

    /** Elite crop name to {weight factor, base drops per break} (SkyHanni's backup factors and CropType.baseDrops). */
    private static final Map<String, double[]> CROPS = new ConcurrentHashMap<>();

    static {
        CROPS.put("Wheat", new double[]{100_000.0, 1});
        CROPS.put("Carrot", new double[]{300_000.0, 3});
        CROPS.put("Potato", new double[]{298_328.17, 3});
        CROPS.put("Sugar Cane", new double[]{198_885.45, 2});
        CROPS.put("Nether Wart", new double[]{248_606.81, 2.5});
        CROPS.put("Pumpkin", new double[]{99_236.12, 1});
        CROPS.put("Melon", new double[]{488_435.88, 5});
        CROPS.put("Mushroom", new double[]{90_944.27, 1});
        CROPS.put("Cocoa Beans", new double[]{276_733.75, 3});
        CROPS.put("Cactus", new double[]{178_730.65, 2});
        CROPS.put("Moonflower", new double[]{200_000.0, 2});
        CROPS.put("Sunflower", new double[]{200_000.0, 2});
        CROPS.put("Wild Rose", new double[]{200_000.0, 2});
    }

    private static volatile double eliteWeight = -1;
    private static volatile long eliteUpdated;
    private static volatile String profileId = "";
    private static volatile boolean loading;
    private static volatile boolean factorsLoaded;
    private static volatile long fetchedAt;
    private static volatile String status = "";

    private static double sinceUpdate;
    private static double sessionGain;
    private static long activeMs;
    private static long lastBreak;
    private static int tab = -1;
    private static int tabTick;

    private FarmingWeightHud() {}

    static void init() {
        Sky2MHuds.setting(HUD, () -> {
            HelperConfig c = HelperUtil.cfg();
            return c != null && c.weight.enabled;
        });
        Sky2MHuds.register(HUD, "Farming Weight",
            () -> {
                HelperConfig c = HelperUtil.cfg();
                return c != null && c.weight.enabled && Sky2MLocation.onSkyblock() && (!c.weight.gardenOnly || Sky2MLocation.inGarden());
            },
            FarmingWeightHud::lines,
            List.of(SbcItems.parseLegacy("§6Farming Weight: §e1,019.43"), SbcItems.parseLegacy("§7Gain: §e32.5/h"),
                SbcItems.parseLegacy("§7Goal 1,500: §e14h 50m")),
            5, 170);
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            HelperConfig c = HelperUtil.cfg();
            if (c == null || !c.weight.enabled || !Sky2MLocation.inGarden()) return;
            String crop = crop(state.getBlock(), level.getGameTime());
            if (crop == null) return;
            double[] info = CROPS.get(crop);
            if (info == null) return;
            double gain = info[1] * (1 + fortune(c) / 100.0) / info[0];
            sinceUpdate += gain;
            sessionGain += gain;
            lastBreak = System.currentTimeMillis();
        });
        ClientTickEvents.END_CLIENT_TICK.register(FarmingWeightHud::tick);
    }

    private static String crop(Block block, long dayTime) {
        if (block == Blocks.WHEAT) return "Wheat";
        if (block == Blocks.CARROTS) return "Carrot";
        if (block == Blocks.POTATOES) return "Potato";
        if (block == Blocks.NETHER_WART) return "Nether Wart";
        if (block == Blocks.PUMPKIN || block == Blocks.CARVED_PUMPKIN) return "Pumpkin";
        if (block == Blocks.MELON) return "Melon";
        if (block == Blocks.COCOA) return "Cocoa Beans";
        if (block == Blocks.SUGAR_CANE) return "Sugar Cane";
        if (block == Blocks.CACTUS) return "Cactus";
        if (block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM_BLOCK
            || block == Blocks.BROWN_MUSHROOM_BLOCK) return "Mushroom";
        if (block == Blocks.ROSE_BUSH) return "Wild Rose";
        if (block == Blocks.SUNFLOWER) return dayTime % 24_000L >= 12_000L ? "Moonflower" : "Sunflower";
        return null;
    }

    private static double fortune(HelperConfig c) {
        return tab >= 0 ? tab : c.weight.farmingFortune;
    }

    private static void tick(Minecraft mc) {
        HelperConfig c = HelperUtil.cfg();
        if (mc.player == null || c == null || !c.weight.enabled || !Sky2MLocation.onSkyblock()) return;
        long now = System.currentTimeMillis();
        if (now - lastBreak < ACTIVE_WINDOW_MS) activeMs += 50;
        if (++tabTick % 100 == 0 && mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String line = TabText.plain(info);
                if (line == null) continue;
                Matcher m = TAB_FORTUNE.matcher(line);
                if (m.find()) tab = (int) HelperUtil.parseAmount(m.group(1));
            }
        }
        if (Sky2MLocation.inGarden() && !loading && now - fetchedAt > (eliteWeight < 0 ? 30_000L : REFRESH_MS)) fetch();
    }

    // ------------------------------------------------------------------------------------------ Elite

    private static JsonObject get(String path) throws Exception {
        String version = FabricLoader.getInstance().getModContainer("sky2m")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Sky2M/" + version)
            .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static void fetch() {
        loading = true;
        fetchedAt = System.currentTimeMillis();
        String uuid = Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
        Thread thread = new Thread(() -> {
            try {
                if (!factorsLoaded) {
                    JsonObject crops = get("/weights/all").getAsJsonObject("crops");
                    for (Map.Entry<String, JsonElement> e : crops.entrySet()) {
                        double[] old = CROPS.get(e.getKey());
                        if (old != null && e.getValue().getAsDouble() > 0) CROPS.put(e.getKey(), new double[]{e.getValue().getAsDouble(), old[1]});
                    }
                    factorsLoaded = true;
                }
                JsonObject profile = get("/profile/" + uuid + "/selected");
                String pid = profile.get("profileId").getAsString();
                JsonObject weight = get("/weight/" + uuid + "/" + pid);
                double total = weight.get("totalWeight").getAsDouble();
                long updated = weight.has("lastUpdated") ? weight.get("lastUpdated").getAsLong() : 0;
                Minecraft.getInstance().execute(() -> {
                    // Newer data from Elite already counts what we estimated.
                    if (!pid.equals(profileId) || updated != eliteUpdated || eliteWeight < 0) sinceUpdate = 0;
                    profileId = pid;
                    eliteUpdated = updated;
                    eliteWeight = total;
                    status = "";
                });
            } catch (Exception e) {
                status = eliteWeight < 0 ? "Elite has no data for you yet" : "";
            } finally {
                loading = false;
            }
        }, "Sky2M-farming-weight");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------------------------------ HUD

    private static List<Component> lines() {
        HelperConfig c = HelperUtil.cfg();
        if (c == null) return List.of();
        List<Component> out = new ArrayList<>();
        if (eliteWeight < 0) {
            out.add(SbcItems.parseLegacy("§6Farming Weight: §7" + (status.isEmpty() ? "loading..." : status)));
            return out;
        }
        double weight = eliteWeight + sinceUpdate;
        out.add(SbcItems.parseLegacy("§6Farming Weight: §e" + String.format(Locale.US, "%,.2f", weight)));
        double hours = activeMs / 3_600_000.0;
        // Wait for a little farming before quoting a rate.
        double perHour = activeMs >= 30_000 ? sessionGain / hours : 0;
        if (c.weight.perHour) {
            out.add(SbcItems.parseLegacy("§7Gain: §e" + (perHour > 0 ? String.format(Locale.US, "%,.1f", perHour) + "/h" : "§7...")
                + (sessionGain > 0 ? " §7(+" + String.format(Locale.US, "%,.2f", sessionGain) + " this session)" : "")));
        }
        if (c.weight.goal > 0) {
            double missing = c.weight.goal - weight;
            String goal = String.format(Locale.US, "%,d", c.weight.goal);
            if (missing <= 0) out.add(SbcItems.parseLegacy("§7Goal " + goal + ": §aReached!"));
            else if (perHour > 0) out.add(SbcItems.parseLegacy("§7Goal " + goal + ": §e" + HelperUtil.duration(missing / perHour * 3600)));
            else out.add(SbcItems.parseLegacy("§7Goal " + goal + ": §7" + String.format(Locale.US, "%,.0f", missing) + " to go"));
        }
        return out;
    }
}
