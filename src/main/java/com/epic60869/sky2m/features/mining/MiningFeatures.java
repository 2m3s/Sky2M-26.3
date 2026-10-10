package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Crystal Hollows map, Mines of Divan tools alert and mineshaft timer. */
public final class MiningFeatures {
    private static final Set<String> DIVAN_TOOLS = Set.of("Scavenged Diamond Axe", "Scavenged Golden Hammer", "Scavenged Emerald Hammer", "Scavenged Lapis Sword");
    private static final Set<String> STRUCTURES = Set.of("Mines of Divan", "Lost Precursor City", "Jungle Temple", "Goblin Queen's Den", "Khazad-dûm", "Fairy Grotto", "Dragon's Lair", "Crystal Nucleus");
    // Cold pattern from SkyHanni's repo (MIT). Freezing happens at -100 cold.
    private static final Pattern COLD = Pattern.compile("Cold: (?<cold>-?\\d+)❄");

    // Crystal Hollows playable area in world coordinates.
    private static final double MIN = 202, MAX = 824;
    private static final int MAP_SIZE = 128;

    private static final Map<String, double[]> FOUND_STRUCTURES = new LinkedHashMap<>();
    private static final Deque<long[]> COLD_SAMPLES = new ArrayDeque<>();
    private static boolean divanAlerted;
    private static long mineshaftEntered;
    private static int cold;
    private static int ticks;

    private MiningFeatures() {}

    private static FeatureConfigs.MiningFeatures config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.features;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 == 0) tick(mc);
        });
        Sky2MLocation.onAreaChange(area -> {
            if (area.equals("Crystal Hollows")) {
                FOUND_STRUCTURES.clear(); // every visit is a new lobby
                divanAlerted = false;
            }
            if (area.equals("Mineshaft")) {
                mineshaftEntered = System.currentTimeMillis();
                COLD_SAMPLES.clear();
            } else {
                mineshaftEntered = 0;
            }
        });

        Sky2MHuds.registerCustom("ch_map", "Crystal Hollows Map",
            () -> config() != null && config().crystalHollowsMap,
            new CrystalHollowsMap(), 8, 480);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("mineshaft", () -> config() != null && config().mineshaftTimer);
        Sky2MHuds.register("mineshaft", "Mineshaft Timer",
            () -> config() != null && config().mineshaftTimer && mineshaftEntered > 0,
            MiningFeatures::mineshaftLines,
            List.of(kv("Mineshaft: ", "3:12"), kv("Cold: ", "-24❄"), kv("Freeze in: ", "~5:40")),
            150, 480);
    }

    private static Component kv(String key, String value) {
        return Component.literal(key).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;
        FeatureConfigs.MiningFeatures config = config();
        if (config == null) return;

        if (Sky2MLocation.inCrystalHollows()) {
            String location = Sky2MLocation.location();
            if (STRUCTURES.contains(location) && !FOUND_STRUCTURES.containsKey(location)) {
                FOUND_STRUCTURES.put(location, new double[]{mc.player.getX(), mc.player.getZ()});
            }
            if (config.divanToolsAlert && !divanAlerted && hasAllDivanTools(mc)) {
                divanAlerted = true;
                Sky2MAlerts.title(Component.literal("ALL 4 TOOLS!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.literal("Place them for the Jade Crystal").withStyle(ChatFormatting.WHITE));
            }
        }

        if (mineshaftEntered > 0) {
            String coldValue = null;
            for (String line : Sky2MLocation.scoreboard()) {
                Matcher m = COLD.matcher(line);
                if (m.find()) coldValue = m.group("cold");
            }
            if (coldValue != null) {
                cold = Integer.parseInt(coldValue);
                COLD_SAMPLES.addLast(new long[]{System.currentTimeMillis(), cold});
                while (COLD_SAMPLES.size() > 1 && System.currentTimeMillis() - COLD_SAMPLES.peekFirst()[0] > 60_000) COLD_SAMPLES.removeFirst();
            }
        }
    }

    private static boolean hasAllDivanTools(Minecraft mc) {
        int found = 0;
        for (String tool : DIVAN_TOOLS) {
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && com.epic60869.sky2m.custom.util.Compat.realName(stack).getString().contains(tool)) {
                    found++;
                    break;
                }
            }
        }
        return found == DIVAN_TOOLS.size();
    }

    private static List<Component> mineshaftLines() {
        long seconds = (System.currentTimeMillis() - mineshaftEntered) / 1000;
        String freeze = "-";
        if (COLD_SAMPLES.size() >= 2) {
            long[] first = COLD_SAMPLES.peekFirst();
            long[] last = COLD_SAMPLES.peekLast();
            double perSecond = (first[1] - last[1]) / Math.max(1.0, (last[0] - first[0]) / 1000.0); // cold goes negative
            if (perSecond > 0) {
                long left = (long) ((100 + cold) / perSecond);
                freeze = String.format(Locale.US, "~%d:%02d", left / 60, left % 60);
            }
        }
        return List.of(
            kv("Mineshaft: ", String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)),
            kv("Cold: ", cold + "❄"),
            kv("Freeze in: ", freeze));
    }

    /** Top-down Crystal Hollows map: the four zones, the nucleus, found structures and you. */
    private static final class CrystalHollowsMap implements Sky2MHuds.CustomHud {
        @Override
        public int width() { return MAP_SIZE; }

        @Override
        public int height() { return MAP_SIZE; }

        @Override
        public boolean visible() {
            return Sky2MLocation.inCrystalHollows();
        }

        @Override
        public void render(GuiGraphicsExtractor g, boolean preview) {
            int half = MAP_SIZE / 2;
            g.fill(0, 0, half, half, 0xC02E7D32);            // Jungle (north-west)
            g.fill(half, 0, MAP_SIZE, half, 0xC000897B);     // Mithril Deposits (north-east)
            g.fill(0, half, half, MAP_SIZE, 0xC0E65100);     // Goblin Holdout (south-west)
            g.fill(half, half, MAP_SIZE, MAP_SIZE, 0xC05E35B1); // Precursor Remnants (south-east)
            int nucleus = 12;
            g.fill(half - nucleus, half - nucleus, half + nucleus, half + nucleus, 0xC0FDD835);
            var font = Minecraft.getInstance().font;

            for (var entry : FOUND_STRUCTURES.entrySet()) {
                int x = toMap(entry.getValue()[0]);
                int z = toMap(entry.getValue()[1]);
                g.fill(x - 2, z - 2, x + 2, z + 2, 0xFFFFFFFF);
                g.pose().pushMatrix();
                g.pose().translate(x + 3f, z - 3f);
                g.pose().scale(0.5f, 0.5f);
                g.text(font, entry.getKey(), 0, 0, 0xFFFFFFFF, true);
                g.pose().popMatrix();
            }

            var player = Minecraft.getInstance().player;
            if (player != null && (!preview || Sky2MLocation.inCrystalHollows())) {
                int x = toMap(player.getX());
                int z = toMap(player.getZ());
                int colour = player.getY() < 64 ? 0xFFFF5555 : 0xFF55FFFF; // red when down in the Magma Fields
                g.fill(x - 2, z - 2, x + 2, z + 2, colour);
            }
            g.text(font, "Crystal Hollows", 2, 2, 0xFFFFFFFF, true);
        }

        private static int toMap(double coordinate) {
            double t = (coordinate - MIN) / (MAX - MIN);
            return (int) Math.round(Math.max(0, Math.min(1, t)) * MAP_SIZE);
        }
    }
}
