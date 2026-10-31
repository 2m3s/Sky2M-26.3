package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.price.ItemPriceResolver;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's pest features (https://github.com/hannibal002/SkyHanni, LGPL-2.1: PestTrapApi, PestTrapFeatures,
 * PestProfitTracker, SprayDisplay): the pest traps from the Pest Traps / Full Traps / No Bait tab widgets with a HUD
 * and repeating warnings, the Pest Profit Tracker for what pests drop when you kill them, and the spray of the plot
 * you are in. The trap release hotkey is not ported: it clicks the menu for you.
 */
public final class PestExtras {
    // "You received 7x Enchanted Potato for killing a Locust!"
    private static final Pattern PEST_DEATH = Pattern.compile("^You received (?<amount>[\\d,]+)x (?<item>.+) for killing an? (?<pest>.+)!$");
    // "RARE DROP! Enchanted Potato x2 (+250 ...)"
    private static final Pattern RARE_DROP = Pattern.compile("(?:RARE|PET) DROP! (?<item>.+?)(?: x(?<amount>[\\d,]+))? \\((?:\\+[\\d.,]+|Cocoaleech)");

    private static final Map<String, Long> DROPS = new LinkedHashMap<>();
    private static int kills;
    private static long firstKillAt;
    private static long lastKillAt;

    private static long nextWarning;
    private static long gardenJoinedAt;
    private static boolean wasInGarden;
    private static int ticks;
    private static List<Component> trapLines = List.of();
    private static List<Component> profitLines = List.of();
    private static List<Component> sprayLines = List.of();

    private PestExtras() {}

    private static SkyHanniGardenConfig.PestExtras config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.skyhanniGarden.pests;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            SkyHanniGardenConfig.PestExtras c = config();
            if (c == null || !c.profitTracker || !Sky2MLocation.inGarden()) return;
            String text = message.text().trim();
            Matcher m = PEST_DEATH.matcher(text);
            if (m.matches()) {
                kills++;
                long now = System.currentTimeMillis();
                if (firstKillAt == 0 || now - lastKillAt > 10 * 60_000L) firstKillAt = now - 60_000L;
                lastKillAt = now;
                synchronized (DROPS) {
                    DROPS.merge(m.group("item").trim(), Long.parseLong(m.group("amount").replace(",", "")), Long::sum);
                }
                return;
            }
            m = RARE_DROP.matcher(text);
            if (m.find()) {
                String amount = m.group("amount");
                synchronized (DROPS) {
                    DROPS.merge(m.group("item").trim(), amount == null ? 1L : Long.parseLong(amount.replace(",", "")), Long::sum);
                }
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("pestprofit")
                    .then(ClientCommands.literal("reset").executes(ctx -> {
                        synchronized (DROPS) {
                            DROPS.clear();
                        }
                        kills = 0;
                        firstKillAt = 0;
                        Sky2MAlerts.chat(Component.literal("§aPest Profit Tracker reset."));
                        return 1;
                    }))));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));

        Sky2MHuds.setting("sh_pest_traps", () -> config() != null && config().trapHud);
        Sky2MHuds.register("sh_pest_traps", "Pest Traps", () -> config() != null && config().trapHud && Sky2MLocation.inGarden(),
            () -> trapLines,
            List.of(Component.literal("§6Pest Traps: §e2§7/§e3"), Component.literal("§cFull: §f#1"), Component.literal("§eNo Bait: §f#2")),
            8, 380);
        Sky2MHuds.setting("sh_pest_profit", () -> config() != null && config().profitTracker);
        Sky2MHuds.register("sh_pest_profit", "Pest Profit Tracker", () -> config() != null && config().profitTracker && Sky2MLocation.inGarden(),
            () -> profitLines,
            List.of(Component.literal("§6Pest Profit Tracker"), Component.literal("§7Pests killed: §e24"),
                Component.literal("§7 Enchanted Potato §8x120 §6(120k)"), Component.literal("§7Total profit: §6120k §7(§6480k/h§7)")),
            8, 410);
        Sky2MHuds.setting("sh_spray", () -> config() != null && config().sprayDisplay);
        Sky2MHuds.register("sh_spray", "Plot Spray", () -> config() != null && config().sprayDisplay && Sky2MLocation.inGarden(),
            () -> sprayLines, List.of(Component.literal("§eSprayed with §aCompost §7- §b12m")), 8, 440);
    }

    private static void tick(Minecraft mc) {
        SkyHanniGardenConfig.PestExtras c = config();
        boolean inGarden = mc.player != null && Sky2MLocation.inGarden();
        if (inGarden && !wasInGarden) {
            gardenJoinedAt = System.currentTimeMillis();
            nextWarning = gardenJoinedAt + 5_000L;
        }
        wasInGarden = inGarden;
        if (c == null || !inGarden) return;
        if (++ticks % 10 != 0) return;
        GardenTabData.Traps traps = GardenTabData.traps();
        if (c.trapHud) trapLines = buildTraps(traps);
        if (c.trapWarnings) warn(c, traps);
        if (c.profitTracker) profitLines = buildProfit();
        if (c.sprayDisplay) sprayLines = buildSpray(c);
    }

    private static List<Component> buildTraps(GardenTabData.Traps traps) {
        if (traps == null) return List.of();
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("§6Pest Traps: §e" + traps.placed() + "§7/§e" + traps.max()));
        if (!traps.full().isEmpty()) out.add(Component.literal("§cFull: §f" + format(traps.full())));
        if (!traps.noBait().isEmpty()) out.add(Component.literal("§eNo Bait: §f" + format(traps.noBait())));
        return out;
    }

    private static String format(List<Integer> traps) {
        StringBuilder sb = new StringBuilder();
        for (int t : traps) {
            if (sb.length() > 0) sb.append("§7, §f");
            sb.append("#").append(t);
        }
        return sb.toString();
    }

    private static void warn(SkyHanniGardenConfig.PestExtras c, GardenTabData.Traps traps) {
        long now = System.currentTimeMillis();
        if (traps == null || now < nextWarning) return;
        List<String> warnings = new ArrayList<>();
        if (c.warnFull && !traps.full().isEmpty()) {
            warnings.add("§cFull " + (traps.full().size() == 1 ? "Trap" : "Traps") + ": §f" + format(traps.full()));
        }
        if (c.warnNoBait && !traps.noBait().isEmpty()) {
            warnings.add("§cNo Bait " + (traps.noBait().size() == 1 ? "Trap" : "Traps") + ": §f" + format(traps.noBait()));
        }
        if (c.warnUnplaced && traps.placed() < traps.max()) {
            warnings.add("§aUnplaced Traps: §c" + traps.placed() + "§4/§c" + traps.max() + " §aPlaced");
        }
        if (warnings.isEmpty()) return;
        nextWarning = now + (long) Math.max(10, c.warningInterval) * 1000L;
        playSound(c.warningSound);
        if (c.warningDisplay != SkyHanniGardenConfig.PestExtras.Display.CHAT) {
            Sky2MAlerts.title(Component.literal(warnings.get(0)), Component.empty(), false);
        }
        if (c.warningDisplay != SkyHanniGardenConfig.PestExtras.Display.TITLE) {
            for (String w : warnings) Sky2MAlerts.chat(Component.literal(w));
        }
    }

    private static void playSound(String name) {
        Identifier id = Identifier.tryParse(name == null ? "" : name.trim());
        if (id == null || name.isBlank()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1f, 1f));
            } catch (Exception ignored) {
            }
        });
    }

    private static List<Component> buildProfit() {
        if (kills == 0) return List.of(Component.literal("§6Pest Profit Tracker"), Component.literal("§7Kill a pest to start"));
        List<Map.Entry<String, Long>> entries;
        synchronized (DROPS) {
            entries = new ArrayList<>(DROPS.entrySet());
        }
        record Line(String name, long amount, double value) {}
        List<Line> lines = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Long> e : entries) {
            double value = ItemPriceResolver.valueByName(e.getKey()) * e.getValue();
            total += value;
            lines.add(new Line(e.getKey(), e.getValue(), value));
        }
        lines.sort(Comparator.comparingDouble(Line::value).reversed());
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("§6Pest Profit Tracker"));
        out.add(Component.literal("§7Pests killed: §e" + kills));
        for (int i = 0; i < Math.min(8, lines.size()); i++) {
            Line l = lines.get(i);
            out.add(Component.literal("§7 " + l.name() + " §8x" + FarmingHud.number(l.amount()) + " §6(" + FarmingHud.coins(l.value()) + ")"));
        }
        double hours = Math.max(1.0 / 60, (Math.max(lastKillAt, System.currentTimeMillis() - 30_000L) - firstKillAt) / 3_600_000.0);
        out.add(Component.literal("§7Total profit: §6" + FarmingHud.coins(total) + " §7(§6" + FarmingHud.coins(total / hours) + "/h§7)"));
        return out;
    }

    private static List<Component> buildSpray(SkyHanniGardenConfig.PestExtras c) {
        GardenTabData.Spray spray = GardenTabData.spray();
        if (spray == null) return List.of();
        boolean none = spray.name().equalsIgnoreCase("None");
        if (none) return c.showNotSprayed ? List.of(Component.literal("§cNot sprayed!")) : List.of();
        String time = spray.time() == null || spray.time().isBlank() ? "" : " §7- §b" + spray.time().trim();
        return List.of(Component.literal("§eSprayed with §a" + spray.name() + time));
    }
}
