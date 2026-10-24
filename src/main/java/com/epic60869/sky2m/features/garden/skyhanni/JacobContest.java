package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Jacob's Contest features (https://github.com/hannibal002/SkyHanni, LGPL-2.1: FarmingContestApi,
 * JacobContestStatsSummary, JacobContestFFNeededDisplay, ZorroCapeContestProtection): a contest timer HUD, a
 * notification when a contest starts, the medal cut-offs with the Farming Fortune they take, the end of contest
 * stats and the Zorro's Cape protection for claiming rewards.
 *
 * The cut-offs are taught by opening the "Your Contests" menu (they are remembered in config/sky2m/jacob-contests.json).
 * A contest is running while the sidebar shows its "Jacob's Contest" line with the crop under it.
 */
public final class JacobContest {
    private static final Pattern YOUR_CONTESTS = Pattern.compile("(?:\\(\\d+/\\d+\\) )?Your Contests");
    private static final Pattern CONTEST_DATE = Pattern.compile("(?<month>(?:Early |Late )?(?:Spring|Summer|Autumn|Winter)) (?<day>\\d+)(?:st|nd|rd|th), Year (?<year>\\d+)");
    private static final Pattern CONTEST_CROP = Pattern.compile("^(?<crop>.+) Contest$");
    private static final Pattern BRACKET = Pattern.compile("(?<bracket>BRONZE|SILVER|GOLD|PLATINUM|DIAMOND) \\(Top \\d{1,2}%\\): (?<amount>[\\d,]+)");
    private static final Pattern SIDEBAR_CROP = Pattern.compile("^\\s*\\S?\\s*(?<crop>[A-Za-z ]+?)\\s+(?<time>(?:\\d+[hms])+)\\s*$");
    private static final String[] MONTHS = {"Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
        "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"};
    private static final String[] BRACKET_NAMES = {"DIAMOND", "PLATINUM", "GOLD", "SILVER", "BRONZE"};
    private static final String[] BRACKET_COLORS = {"§b§l", "§3§l", "§6§l", "§f§l", "§c§l"};

    /** One contest read from the Your Contests menu. */
    static final class ContestRecord {
        String crop;
        long sortKey;
        Map<String, Integer> brackets = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, ContestRecord> contests = new LinkedHashMap<>();
    private static Path file;
    private static boolean dirty;

    private static GardenCrop contestCrop;
    private static long contestStart;
    private static long contestEndMs;
    private static int blocksBroken;
    private static boolean inContestsMenu;
    private static String cloakId; // null = not read yet
    private static int ticks;
    private static List<Component> medalLines = List.of();
    private static List<Component> timerLines = List.of();
    private static String lastBonusWarningKey = "";
    private static boolean bonusWasActive;

    private JacobContest() {}

    private static SkyHanniGardenConfig.Jacob config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.skyhanniGarden.jacob;
    }

    public static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("jacob-contests.json");
        load();
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> save());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        FarmingHud.onBreak(crop -> {
            if (contestCrop != null && crop == contestCrop) blocksBroken++;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ContainerScreen container) || !Sky2MLocation.onSkyblock()) return;
            String title = screen.getTitle().getString();
            if (YOUR_CONTESTS.matcher(title).matches() && Sky2MLocation.inGarden()) {
                int[] counter = {0};
                ScreenEvents.afterTick(screen).register(s -> {
                    if (++counter[0] % 10 == 2) readContests(container);
                    inContestsMenu = true;
                });
                ScreenEvents.remove(screen).register(s -> inContestsMenu = false);
            } else if (title.equals("Stats & Equipment")) {
                int[] counter = {0};
                ScreenEvents.afterTick(screen).register(s -> {
                    if (++counter[0] % 10 == 2 && container.getMenu().slots.size() > 19) {
                        ItemStack cloak = container.getMenu().slots.get(19).getItem();
                        if (!cloak.isEmpty()) cloakId = Compat.neuName(cloak);
                    }
                });
            }
        });
        com.epic60869.sky2m.features.core.Sky2MChat.onChat(message -> {
            String text = message.text();
            if (text.startsWith("You equipped a ") && text.endsWith("!")) {
                if (text.contains("Zorro")) cloakId = "ZORROS_CAPE";
                else if (text.contains("Cloak") || text.contains("Cape")) cloakId = "OTHER";
            }
        });

        Sky2MHuds.setting("sh_jacob_timer", () -> config() != null && config().timer);
        Sky2MHuds.register("sh_jacob_timer", "Jacob's Contest Timer", () -> config() != null && config().timer && Sky2MLocation.inGarden(),
            () -> timerLines,
            List.of(Component.literal("§6Jacob's Contest: 12m left"), Component.literal("§e○ Wheat"), Component.literal("§e☘ Cactus")),
            8, 230);
        Sky2MHuds.setting("sh_jacob_medals", () -> config() != null && config().medals);
        Sky2MHuds.register("sh_jacob_medals", "Jacob's Medal Cut-offs", () -> config() != null && config().medals && Sky2MLocation.inGarden(),
            () -> medalLines,
            List.of(Component.literal("§6Wheat contest cut-offs"), Component.literal("§b§lDIAMOND§f: §6850,000 §7(FF 1,234)"),
                Component.literal("§6§lGOLD§f: §6600,000 §7(FF 800)")),
            8, 280);
    }

    // ---------------------------------------------------------------------------------------------- state

    private static void tick(Minecraft mc) {
        SkyHanniGardenConfig.Jacob c = config();
        if (c == null || mc.player == null || !Sky2MLocation.inGarden()) return;
        if (++ticks % 10 != 0) return;
        readSidebar(c);
        if (c.timer) timerLines = buildTimer();
        medalLines = c.medals ? buildMedals() : List.of();
        pestBonusWarning();
    }

    private static void readSidebar(SkyHanniGardenConfig.Jacob c) {
        GardenCrop crop = null;
        long endMs = 0;
        List<String> board = Sky2MLocation.scoreboard();
        for (int i = 0; i < board.size() - 1; i++) {
            if (!board.get(i).contains("Jacob's Contest")) continue;
            Matcher m = SIDEBAR_CROP.matcher(board.get(i + 1));
            if (m.matches()) {
                crop = GardenCrop.byName(m.group("crop"));
                long secs = GardenTabData.seconds(m.group("time"));
                if (secs >= 0) endMs = System.currentTimeMillis() + secs * 1000;
            }
            break;
        }
        long now = System.currentTimeMillis();
        if (crop != null && contestCrop == null) {
            contestCrop = crop;
            contestStart = now;
            blocksBroken = 0;
            if (c.notification) {
                Sky2MAlerts.title(Component.literal("§6Jacob's Contest started!"), Component.literal("§e" + crop.displayName));
                Sky2MAlerts.chat(Component.literal("§6Jacob's Contest started: §e" + crop.displayName + "§6!"));
            }
            if (c.summary) Sky2MAlerts.chat(Component.literal("§7Started tracking your Jacob's Contest blocks per second!"));
        } else if (crop == null && contestCrop != null) {
            if (c.summary && now - contestStart > 2 * 60_000L) summary(c);
            contestCrop = null;
        } else if (crop != null && crop != contestCrop) {
            if (c.summary) Sky2MAlerts.chat(Component.literal("§7You changed the crop during the contest, resetting the blocks per second calculation."));
            contestCrop = crop;
            contestStart = now;
            blocksBroken = 0;
        }
        contestEndMs = endMs;
    }

    private static void summary(SkyHanniGardenConfig.Jacob c) {
        if (c.hideZeroStats && blocksBroken == 0) return;
        long seconds = Math.max(1, (System.currentTimeMillis() - contestStart) / 1000);
        double bps = blocksBroken / (double) seconds;
        String color = bps > 19 ? "§c" : "§a";
        Sky2MAlerts.chat(Component.literal("§7Stats for " + contestCrop.displayName + " Contest:"));
        Sky2MAlerts.chat(Component.literal("§7Blocks Broken in total: §e" + FarmingHud.number(blocksBroken)));
        Sky2MAlerts.chat(Component.literal("§7Average Blocks Per Second: " + color + String.format(Locale.US, "%.2f", bps)));
        Sky2MAlerts.chat(Component.literal("§7Participated for §b" + FarmingHud.duration(seconds)));
    }

    // ---------------------------------------------------------------------------------------------- HUDs

    private static List<Component> buildTimer() {
        List<String> lines = GardenTabData.jacobLines();
        List<Component> out = new ArrayList<>();
        if (!lines.isEmpty()) {
            out.add(Component.literal("§6" + lines.get(0)));
            for (int i = 1; i < lines.size(); i++) out.add(Component.literal("§e" + lines.get(i)));
        } else if (contestCrop != null) {
            out.add(Component.literal("§6Jacob's Contest: §e" + contestCrop.displayName));
        }
        if (contestCrop != null && contestEndMs > 0) {
            out.add(Component.literal("§7Time left: §b" + FarmingHud.duration(Math.max(0, (contestEndMs - System.currentTimeMillis()) / 1000))));
        }
        return out;
    }

    /** The average cut-offs of the last 10 contests of {@code crop} that the Your Contests menu taught us. */
    private static Map<String, Integer> averages(GardenCrop crop, int[] count) {
        List<ContestRecord> list = new ArrayList<>();
        for (ContestRecord r : contests.values()) if (crop.displayName.equalsIgnoreCase(r.crop) || crop.name().equalsIgnoreCase(r.crop)) list.add(r);
        list.sort((a, b) -> Long.compare(b.sortKey, a.sortKey));
        if (list.size() > 10) list = list.subList(0, 10);
        count[0] = list.size();
        Map<String, Long> sum = new LinkedHashMap<>();
        Map<String, Integer> n = new LinkedHashMap<>();
        for (ContestRecord r : list) {
            for (Map.Entry<String, Integer> e : r.brackets.entrySet()) {
                sum.merge(e.getKey(), (long) e.getValue(), Long::sum);
                n.merge(e.getKey(), 1, Integer::sum);
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : sum.entrySet()) out.put(e.getKey(), (int) (e.getValue() / n.get(e.getKey())));
        return out;
    }

    private static List<Component> buildMedals() {
        if (contestCrop == null) return List.of();
        int[] count = {0};
        Map<String, Integer> avg = averages(contestCrop, count);
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("§6" + contestCrop.displayName + " contest cut-offs"));
        if (avg.isEmpty()) {
            out.add(Component.literal("§cOpen the Your Contests menu once to learn cut-offs"));
        } else {
            out.add(Component.literal("§7Average of your last §e" + count[0] + " §7contests"));
            double bps = FarmingHud.latestBps() >= 15 ? FarmingHud.latestBps() : 19.9;
            for (int i = 0; i < BRACKET_NAMES.length; i++) {
                Integer amount = avg.get(BRACKET_NAMES[i]);
                if (amount == null) continue;
                double ff = Math.max(0, amount * 100.0 / (1200.0 * bps * contestCrop.baseDrops) - 100.0);
                out.add(Component.literal(BRACKET_COLORS[i] + BRACKET_NAMES[i] + "§f: §6" + FarmingHud.number(amount) + " §7(FF " + FarmingHud.number(Math.ceil(ff)) + ")"));
            }
        }
        out.add(Component.literal("§7Blocks broken: §e" + FarmingHud.number(blocksBroken)));
        double ff = GardenTabData.trueFortune();
        if (ff >= 0) {
            double bps = FarmingHud.latestBps() >= 15 ? FarmingHud.latestBps() : 19.9;
            double predicted = (100.0 + ff) * bps * contestCrop.baseDrops * 20 * 60 / 100.0;
            out.add(Component.literal("§7Predicted crops (full contest): §6" + FarmingHud.number(predicted)));
        }
        return out;
    }

    private static void pestBonusWarning() {
        SkyHanniGardenConfig.Fortune f = Sky2MConfig.current() == null ? null : Sky2MConfig.current().farming.skyhanniGarden.fortune;
        if (f == null || !f.pestBonusWarning) return;
        GardenTabData.Bonus bonus = GardenTabData.pestBonus();
        if (bonus == null) return;
        if (bonus.active()) {
            bonusWasActive = true;
        } else if (bonusWasActive) {
            bonusWasActive = false;
            Sky2MAlerts.title(Component.literal("§cPest Fortune Buff Has Expired!"), Component.empty());
            Sky2MAlerts.chat(Component.literal("§cPest fortune buff has expired! §eVisit the barn or call Phillip."));
        }
    }

    // ---------------------------------------------------------------------------------------------- menu

    private static void readContests(ContainerScreen container) {
        var slots = container.getMenu().slots;
        int size = Math.max(0, slots.size() - 36);
        boolean changed = false;
        for (int i = 0; i < size; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            List<String> lore = lore(stack);
            if (lore.stream().noneMatch(l -> l.startsWith("Your score: "))) continue;
            String name = Sky2MLocation.strip(stack.getHoverName().getString()).trim();
            Matcher date = CONTEST_DATE.matcher(name);
            if (!date.matches() || contests.containsKey(name)) continue;
            ContestRecord r = new ContestRecord();
            for (String line : lore) {
                Matcher crop = CONTEST_CROP.matcher(line);
                if (r.crop == null && crop.matches() && GardenCrop.byName(crop.group("crop")) != null) {
                    r.crop = GardenCrop.byName(crop.group("crop")).displayName;
                }
                Matcher b = BRACKET.matcher(line);
                if (b.find()) r.brackets.put(b.group("bracket"), Integer.parseInt(b.group("amount").replace(",", "")));
            }
            if (r.crop == null || r.brackets.isEmpty()) continue;
            int month = 0;
            for (int m = 0; m < MONTHS.length; m++) if (MONTHS[m].equals(date.group("month"))) month = m;
            r.sortKey = Long.parseLong(date.group("year")) * 1000L + month * 40L + Integer.parseInt(date.group("day"));
            contests.put(name, r);
            changed = true;
        }
        if (changed) {
            dirty = true;
            save();
        }
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (Component c : lore.lines()) lines.add(Sky2MLocation.strip(c.getString()).trim());
        return lines;
    }

    // ---------------------------------------------------------------------------------------------- zorro

    /**
     * From the container click mixin: true stops the click. Claiming a contest (or Bulk Claim) in the Your Contests
     * menu without Zorro's Cape equipped.
     */
    public static boolean onSlotClicked(AbstractContainerScreen<?> screen, Slot slot) {
        SkyHanniGardenConfig.Jacob c = config();
        if (c == null || !c.zorroProtection || slot == null || !slot.hasItem() || !Sky2MLocation.inGarden()) return false;
        if (!YOUR_CONTESTS.matcher(screen.getTitle().getString()).matches()) return false;
        if ("ZORROS_CAPE".equals(cloakId)) return false;
        ItemStack stack = slot.getItem();
        List<String> lore = lore(stack);
        boolean claimable = !lore.isEmpty() && lore.get(lore.size() - 1).equals("Click to claim reward!");
        boolean bulk = Sky2MLocation.strip(stack.getHoverName().getString()).trim().equals("Bulk Claim");
        if (!claimable && !bulk) return false;
        Sky2MAlerts.title(Component.literal("§cNo Zorro's Cape equipped!"), Component.empty());
        Sky2MAlerts.chat(Component.literal("§cBlocked Jacob's Contest reward claim! §eEquip §6Zorro's Cape §ewhen claiming contest rewards"
            + (cloakId == null ? " (open your Stats & Equipment menu once so Sky2M knows your cloak)." : ".")));
        return true;
    }

    // ---------------------------------------------------------------------------------------------- storage

    private static void load() {
        try {
            if (Files.exists(file)) {
                Map<String, ContestRecord> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<LinkedHashMap<String, ContestRecord>>() {}.getType());
                if (loaded != null) contests = loaded;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load jacob-contests.json: " + e.getMessage());
        }
    }

    private static void save() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(contests), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save jacob-contests.json: " + e.getMessage());
        }
    }
}
