package com.epic60869.sky2m.features.combat.extra;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combat > Ghost Counter, ported from SkyHanni's GhostTracker (https://github.com/hannibal002/SkyHanni, LGPL-2.1): in the
 * Mist of the Dwarven Mines, the Ghost kills (read from the Bestiary tab widget, as SkyHanni does), ghosts since a
 * Sorrow, the best kill combo, the average magic find, the drops and the coins of the bags, with the profit. Counts are
 * kept in {@code sky2m/ghosts.json}.
 */
public final class GhostTracker {
    /** SkyHanni-REPO's GhostDrops.json ghost_drops, by name. */
    private static final List<String> DROPS = List.of("Sorrow", "Plasma", "Volta", "Ghostly Boots");
    private static final Pattern DROP = Pattern.compile("^RARE DROP! (?<item>.+?) \\(\\+(?<mf>\\d+)%?[^)]*Magic Find\\)");
    private static final Pattern COMBO = Pattern.compile("Your Kill Combo has expired! You reached a (?<kill>[\\d,.]+) Kill Combo!");
    private static final Pattern BAG = Pattern.compile("The ghost's death materialized 1,000,000 coins from the mists!");
    private static final Pattern BESTIARY = Pattern.compile("\\s*Ghost (?<level>\\d+|[XVI]+): (?<kills>[\\d,.]+)/(?<next>[\\d,.]+)");
    private static final Pattern BESTIARY_MAX = Pattern.compile("\\s*Ghost (?<level>\\d+|[XVI]+): MAX");
    private static final long MAX_BESTIARY = 100_000, ACTIVE_MS = 90_000;

    private static final class Data {
        long kills, sinceSorrow, maxCombo, mfTotal, mfKills, bestiaryKills, coins, activeMs;
        Map<String, Long> drops = new LinkedHashMap<>();
    }

    private static Data data = new Data();
    private static Path file;
    private static long lastKillAt, lastTick, lastWarning;
    private static int ticks;
    private static boolean dirty, foundBestiary;

    private GhostTracker() {}

    private static CombatExtraConfig.GhostCounter config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.ghostCounter;
    }

    private static boolean inArea() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.inDwarvenMines() && Sky2MLocation.location().contains("The Mist");
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("ghosts.json");
        data = CombatExtras.load(file, Data.class, new Data());
        if (data.drops == null) data.drops = new LinkedHashMap<>();
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientTickEvents.END_CLIENT_TICK.register(GhostTracker::tick);
        Sky2MHuds.register("ghost_counter", "Ghost Counter", () -> config() != null && config().enabled && inArea(), GhostTracker::lines,
            List.of(Component.literal("Ghost Profit Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal("Kills: ").withStyle(ChatFormatting.GRAY).append(Component.literal("7,813").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Ghosts Since Sorrow: ").withStyle(ChatFormatting.GRAY).append(Component.literal("71").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Max Kill Combo: ").withStyle(ChatFormatting.GRAY).append(Component.literal("681").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Average Magic Find: ").withStyle(ChatFormatting.GRAY).append(Component.literal("278.9").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Sorrow: ").withStyle(ChatFormatting.GOLD).append(Component.literal("2 (12.4M)").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Total profit: ").withStyle(ChatFormatting.GOLD).append(Component.literal("48.3M (6.1M/h)").withStyle(ChatFormatting.YELLOW))),
            8, 150);
        Sky2MHuds.setting("ghost_counter", () -> config() != null && config().enabled);
    }

    /** The Reset Counts button. */
    public static void reset() {
        data = new Data();
        save();
        Sky2MAlerts.chat(Component.literal("Ghost counter reset.").withStyle(ChatFormatting.YELLOW));
    }

    private static void save() {
        dirty = false;
        CombatExtras.save(file, data);
    }

    private static void onChat(String text) {
        CombatExtraConfig.GhostCounter c = config();
        if (c == null || !c.enabled || !inArea()) return;
        Matcher drop = DROP.matcher(text);
        if (drop.find()) {
            String item = drop.group("item").trim();
            if (!DROPS.contains(item)) return;
            data.drops.merge(item, 1L, Long::sum);
            data.mfTotal += Long.parseLong(drop.group("mf"));
            data.mfKills++;
            if (item.equals("Sorrow")) data.sinceSorrow = 0;
            dirty = true;
            return;
        }
        Matcher combo = COMBO.matcher(text);
        if (combo.find()) {
            long kills = (long) CombatExtras.parseNumber(combo.group("kill"));
            if (kills > data.maxCombo) data.maxCombo = kills;
            dirty = true;
            return;
        }
        if (BAG.matcher(text).find()) {
            data.coins += 1_000_000;
            data.drops.merge("Bag of Cash", 1L, Long::sum);
            dirty = true;
        }
    }

    private static void tick(Minecraft mc) {
        CombatExtraConfig.GhostCounter c = config();
        if (c == null || !c.enabled || mc.player == null || !inArea()) {
            lastTick = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (lastTick != 0 && now - lastKillAt < ACTIVE_MS) data.activeMs += Math.min(1_000L, now - lastTick);
        lastTick = now;
        if (++ticks % 20 == 0) {
            readBestiary(now);
            if (dirty) save();
        }
    }

    /** The Bestiary tab widget row "Ghost 21: 29,614/40,000": its kills going up are the ghosts you killed. */
    private static void readBestiary(long now) {
        foundBestiary = false;
        for (Component line : Sky2MTabWidgetManager.get("Bestiary").lines()) {
            String text = Sky2MLocation.strip(line.getString());
            if (BESTIARY_MAX.matcher(text).find()) {
                data.bestiaryKills = MAX_BESTIARY;
                foundBestiary = true;
                return;
            }
            Matcher m = BESTIARY.matcher(text);
            if (!m.find()) continue;
            foundBestiary = true;
            long kills = (long) CombatExtras.parseNumber(m.group("kills"));
            if (kills <= data.bestiaryKills) return;
            long difference = kills - data.bestiaryKills;
            data.bestiaryKills = kills;
            if (difference > 50) {
                dirty = true;
                return;
            }
            data.kills += difference;
            data.sinceSorrow += difference;
            lastKillAt = now;
            dirty = true;
            return;
        }
        if (!foundBestiary && now - lastWarning > 60_000L && now - lastKillAt > 0) {
            lastWarning = now;
            Sky2MAlerts.chat(Component.literal("Ghost Counter needs the Bestiary tab widget: enable it with /widget.").withStyle(ChatFormatting.RED));
        }
    }

    private static List<Component> lines() {
        CombatExtraConfig.GhostCounter c = config();
        List<Component> out = new ArrayList<>();
        if (c == null) return out;
        out.add(Component.literal("Ghost Profit Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        out.add(row("Kills: ", CombatExtras.commas(data.kills)));
        out.add(row("Ghosts Since Sorrow: ", CombatExtras.commas(data.sinceSorrow)));
        out.add(row("Max Kill Combo: ", CombatExtras.commas(data.maxCombo)));
        out.add(row("Average Magic Find: ", data.mfKills == 0 ? "0" : String.format(Locale.US, "%.1f", data.mfTotal / (double) data.mfKills)));
        out.add(row("Bestiary Kills: ", data.bestiaryKills >= MAX_BESTIARY ? "MAX" : CombatExtras.commas(data.bestiaryKills)));
        double profit = data.coins;
        for (Map.Entry<String, Long> e : data.drops.entrySet()) {
            double value = e.getKey().equals("Bag of Cash") ? 0 : ItemPriceResolver.valueByName(e.getKey()) * e.getValue();
            profit += value;
            if (c.showDrops) {
                out.add(Component.literal(e.getKey() + ": ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(e.getValue() + (value > 0 ? " (" + CombatExtras.shortNumber(value) + ")" : "")).withStyle(ChatFormatting.YELLOW)));
            }
        }
        if (data.coins > 0 && c.showDrops) {
            out.add(Component.literal("Dropped Coins: ").withStyle(ChatFormatting.GOLD).append(Component.literal(CombatExtras.shortNumber(data.coins)).withStyle(ChatFormatting.YELLOW)));
        }
        if (c.showProfit) {
            double hours = data.activeMs / 3_600_000.0;
            String perHour = hours > 0.01 ? " (" + CombatExtras.shortNumber(profit / hours) + "/h)" : "";
            out.add(row("Total profit: ", CombatExtras.shortNumber(profit) + perHour));
        }
        return out;
    }

    private static Component row(String label, String value) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.YELLOW));
    }
}
