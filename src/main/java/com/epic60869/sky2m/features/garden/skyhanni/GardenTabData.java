package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sim.util.TabText;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Garden's tab list widgets say, read twice a second (the patterns are SkyHanni's tab widget patterns,
 * https://github.com/hannibal002/SkyHanni, LGPL-2.1): Stats (Farming Fortune), Crop Milestones, Pests (bonus, alive,
 * bonus pest chance), Pest Traps, Jacob's Contest, Garden Level and the current plot's Spray. Each value is null
 * (or empty) while its widget isn't in your tab list.
 */
public final class GardenTabData {
    private static final Pattern UNIVERSAL_FF = Pattern.compile("^\\s*Farming Fortune:\\s*[^\\d\\s]?(?<v>[\\d,]+)");
    private static final Pattern CROP_FF = Pattern.compile("^\\s*(?<crop>Wheat|Carrot|Potato|Nether Wart|Pumpkin|Melon Slice|Cocoa Beans|Sugar Cane|Cactus|Mushroom|Sunflower|Moonflower|Wild Rose) Fortune:\\s*[^\\d\\s]?(?<v>[\\d,]+)");
    private static final Pattern MILESTONE = Pattern.compile("^\\s*(?<crop>[A-Za-z ]+?) (?<tier>\\d+):\\s*(?:(?<pct>[\\d.]+)%|(?<max>MAX))");
    private static final Pattern BONUS = Pattern.compile("^\\s*Bonus:\\s*(?:(?<inactive>INACTIVE)|\\+(?<ff>\\d+)\\S*\\s+(?<time>.*))");
    private static final Pattern ALIVE = Pattern.compile("^\\s*Alive:\\s*(?<v>\\d+)");
    private static final Pattern BONUS_PEST_CHANCE = Pattern.compile("^\\s*Bonus Pest Chance:\\s*[^\\d\\s]?(?<v>[\\d,.]+)");
    private static final Pattern TRAPS = Pattern.compile("^\\s*Pest Traps:\\s*(?<c>\\d+)/(?<m>\\d+)");
    private static final Pattern FULL_TRAPS = Pattern.compile("^\\s*Full Traps:\\s*(?<t>.*)$");
    private static final Pattern NO_BAIT = Pattern.compile("^\\s*No Bait:\\s*(?<t>.*)$");
    private static final Pattern JACOB = Pattern.compile("^\\s*Jacob's Contest:\\s*(?<t>.*)$");
    private static final Pattern JACOB_CROP = Pattern.compile("^\\s*[^\\w\\s]\\s*(?<crop>[A-Za-z ]+?)\\s*$");
    private static final Pattern GARDEN_LEVEL = Pattern.compile("^\\s*Garden Level:\\s*(?<l>\\S+)");
    private static final Pattern SPRAY = Pattern.compile("^\\s*Spray:\\s*(?<spray>[A-Za-z ]+?)\\s*(?:\\((?<time>[^)]*)\\))?\\s*$");
    private static final Pattern TIME_PART = Pattern.compile("(\\d+)\\s*([hms])");

    /** A crop's milestone row in the tab list: the tier it is on and the percent to the next one. */
    public record Milestone(int tier, double percent, boolean maxed, long seenAt) {}

    public record Bonus(boolean active, int fortune, long expiresAt) {}

    public record Traps(int placed, int max, List<Integer> full, List<Integer> noBait) {}

    public record Spray(String name, String time, long seenAt) {}

    private static volatile double universalFf = -1;
    private static volatile double cropFf = -1;
    private static volatile GardenCrop cropFfCrop;
    private static volatile Bonus bonus;
    private static volatile int pestsAlive = -1;
    private static volatile double bonusPestChance;
    private static volatile Traps traps;
    private static volatile List<String> jacobLines = List.of();
    private static volatile String jacobTimeText;
    private static volatile List<GardenCrop> jacobCrops = List.of();
    private static volatile String gardenLevelText;
    private static volatile Spray spray;
    private static final Map<GardenCrop, Milestone> MILESTONES = new EnumMap<>(GardenCrop.class);
    private static int ticks;

    private GardenTabData() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.getConnection() == null || !Sky2MLocation.inGarden()) return;
            if (++ticks % 10 == 0) scan();
        });
    }

    /** The Farming Fortune of the Stats widget plus the crop's, or -1 when it isn't there. */
    public static double trueFortune() {
        return universalFf < 0 ? -1 : universalFf + Math.max(0, cropFf);
    }

    public static double universalFortune() {
        return universalFf;
    }

    public static double cropFortune() {
        return cropFf;
    }

    public static GardenCrop cropFortuneCrop() {
        return cropFfCrop;
    }

    public static Bonus pestBonus() {
        return bonus;
    }

    public static int pestsAlive() {
        return pestsAlive;
    }

    public static double bonusPestChance() {
        return bonusPestChance;
    }

    public static Traps traps() {
        return traps;
    }

    public static List<String> jacobLines() {
        return jacobLines;
    }

    /** The Jacob's Contest header text after the colon ("19m left", ...), or null. */
    public static String jacobTime() {
        return jacobTimeText;
    }

    public static List<GardenCrop> jacobCrops() {
        return jacobCrops;
    }

    public static String gardenLevelText() {
        return gardenLevelText;
    }

    public static Spray spray() {
        return spray;
    }

    public static synchronized Milestone milestone(GardenCrop crop) {
        return MILESTONES.get(crop);
    }

    /** Seconds in "1h 5m 3s" / "19m" / "5m2s", or -1. */
    public static long seconds(String text) {
        if (text == null) return -1;
        Matcher m = TIME_PART.matcher(text);
        long total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "h" -> n * 3600;
                case "m" -> n * 60;
                default -> n;
            };
        }
        return any ? total : -1;
    }

    private static List<Integer> traps(String text) {
        TreeSet<Integer> set = new TreeSet<>();
        Matcher m = Pattern.compile("#(\\d+)").matcher(text);
        while (m.find()) set.add(Integer.parseInt(m.group(1)));
        return new ArrayList<>(set);
    }

    private static void scan() {
        List<PlayerInfo> players = Sky2MTabWidgetManager.players();
        if (players.isEmpty()) return;
        List<String> lines = new ArrayList<>(players.size());
        for (PlayerInfo info : players) {
            String line = TabText.plain(info);
            if (line != null && !line.isBlank()) lines.add(line);
        }
        long now = System.currentTimeMillis();
        double universal = -1;
        double crop = -1;
        GardenCrop cropOfFf = null;
        Bonus newBonus = null;
        int alive = -1;
        double chance = 0;
        int placed = -1;
        int max = 3;
        List<Integer> full = List.of();
        List<Integer> noBait = List.of();
        List<String> jacob = new ArrayList<>();
        String jacobTime = null;
        List<GardenCrop> jacobCropList = new ArrayList<>();
        boolean inJacob = false;
        String level = null;
        Spray newSpray = null;

        for (String line : lines) {
            Matcher m;
            if ((m = UNIVERSAL_FF.matcher(line)).find()) {
                universal = Double.parseDouble(m.group("v").replace(",", ""));
            } else if ((m = CROP_FF.matcher(line)).find()) {
                cropOfFf = GardenCrop.byName(m.group("crop"));
                crop = Double.parseDouble(m.group("v").replace(",", ""));
            } else if ((m = BONUS.matcher(line)).find()) {
                if (m.group("inactive") != null) {
                    newBonus = new Bonus(false, 0, 0);
                } else {
                    long secs = seconds(m.group("time"));
                    newBonus = new Bonus(true, Integer.parseInt(m.group("ff")), secs < 0 ? 0 : now + secs * 1000);
                }
            } else if ((m = ALIVE.matcher(line)).find()) {
                alive = Integer.parseInt(m.group("v"));
            } else if ((m = BONUS_PEST_CHANCE.matcher(line)).find()) {
                try {
                    chance = Double.parseDouble(m.group("v").replace(",", ""));
                } catch (NumberFormatException ignored) {
                }
            } else if ((m = TRAPS.matcher(line)).find()) {
                placed = Integer.parseInt(m.group("c"));
                max = Integer.parseInt(m.group("m"));
            } else if ((m = FULL_TRAPS.matcher(line)).find()) {
                full = traps(m.group("t"));
            } else if ((m = NO_BAIT.matcher(line)).find()) {
                noBait = traps(m.group("t"));
            } else if ((m = JACOB.matcher(line)).find()) {
                jacobTime = m.group("t").trim();
                jacob.add(line.trim());
                inJacob = true;
                continue;
            } else if ((m = GARDEN_LEVEL.matcher(line)).find()) {
                level = m.group("l");
            } else if ((m = SPRAY.matcher(line)).find()) {
                newSpray = new Spray(m.group("spray").trim(), m.group("time"), now);
            } else if ((m = MILESTONE.matcher(line)).find()) {
                GardenCrop milestoneCrop = GardenCrop.byName(m.group("crop"));
                if (milestoneCrop != null) {
                    boolean maxed = m.group("max") != null;
                    double pct = maxed ? 100 : Double.parseDouble(m.group("pct"));
                    synchronized (MILESTONES) {
                        MILESTONES.put(milestoneCrop, new Milestone(Integer.parseInt(m.group("tier")), pct, maxed, now));
                    }
                }
            }
            if (inJacob) {
                Matcher c = JACOB_CROP.matcher(line);
                GardenCrop jc = c.matches() ? GardenCrop.byName(c.group("crop")) : null;
                if (jc != null) {
                    jacob.add(line.trim());
                    jacobCropList.add(jc);
                } else {
                    inJacob = false;
                }
            }
        }
        universalFf = universal;
        cropFf = crop;
        cropFfCrop = cropOfFf;
        bonus = newBonus;
        pestsAlive = alive;
        bonusPestChance = chance;
        traps = placed < 0 ? null : new Traps(placed, max, full, noBait);
        jacobLines = jacob;
        jacobTimeText = jacobTime;
        jacobCrops = jacobCropList;
        gardenLevelText = level;
        spray = newSpray;
    }
}
