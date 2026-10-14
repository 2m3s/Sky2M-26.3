package com.epic60869.sky2m.features.hunting;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SkyHanni's Hunting Profit Tracker (https://github.com/hannibal002/SkyHanni, features/hunting/HuntingProfitTracker.kt,
 * LGPL-2.1): every shard you get while hunting (caught, captured, charmed, loot shared, floor drops), with its Bazaar
 * value, the mobs caught and shards collected, the total profit and the profit per hour, for this session or in total.
 * Shown while you hold a hunting tool (a lasso, fishing net, pocket black hole or hunting axe), for a few seconds after
 * a catch, or always.
 */
public final class HuntingProfitTracker {
    private static final String HUD_ID = "hunting_profit_tracker";
    /** SkyHanni-REPO's Items.json hunting_blackholes and hunting_axes; replaced by the live copy. */
    private static volatile Set<String> huntingTools = Set.of("SMALL_POCKET_BLACK_HOLE", "MEDIUM_POCKET_BLACK_HOLE",
        "VENATOR_GENESIS", "SILVA_DOMINUS", "CURSUS_FERAE", "APEX_PRAEDATOR", "NEX_TITANUM");
    private static final long SHOW_MS = 10_000L;
    private static final long ACTIVE_WINDOW_MS = 90_000L;

    static final class Data {
        /** Shard display name -> amount. */
        @Expose Map<String, Long> shards = new LinkedHashMap<>();
        @Expose long totalMobsCaught;
        @Expose long totalShardsGained;
        @Expose long activeMs;
    }

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private static Path file;
    private static Data total = new Data();
    private static Data session = new Data();
    private static long lastHunt;
    private static long lastToolHeld;
    private static long lastTick;
    private static boolean dirty;
    private static int ticks;
    private static List<Component> lines = List.of();

    private HuntingProfitTracker() {}

    private static HuntingConfig.ProfitTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.hunting.huntingProfitTracker;
    }

    private static boolean shown() {
        HuntingConfig.ProfitTracker c = config();
        if (c == null || !c.enabled || !Sky2MLocation.onSkyblock()) return false;
        long now = System.currentTimeMillis();
        return c.alwaysShow || c.showWhenPickup && now - lastHunt < SHOW_MS || now - lastToolHeld < SHOW_MS;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("hunting-profit.json");
        try {
            if (Files.exists(file)) total = GSON.fromJson(Files.readString(file), Data.class);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read hunting-profit.json: " + e.getMessage());
        }
        if (total == null) total = new Data();
        SkyHanniRepo.load("Items", json -> {
            Set<String> tools = new HashSet<>();
            for (String key : List.of("hunting_blackholes", "hunting_axes")) {
                if (json.has(key)) json.getAsJsonArray(key).forEach(e -> tools.add(e.getAsString()));
            }
            if (!tools.isEmpty()) huntingTools = Set.copyOf(tools);
        });
        AttributeShards.onShard((shard, amount, source, gain) -> {
            if (!gain || amount <= 0) return;
            HuntingConfig.ProfitTracker c = config();
            if (c == null || !c.enabled) return;
            for (Data d : List.of(total, session)) {
                d.shards.merge(shard.displayName(), (long) amount, Long::sum);
                d.totalMobsCaught++;
                d.totalShardsGained += amount;
            }
            lastHunt = System.currentTimeMillis();
            dirty = true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(HuntingProfitTracker::tick);
        Sky2MHuds.register(HUD_ID, "Hunting Profit Tracker", HuntingProfitTracker::shown,
            () -> ScreenHud.inMenu() ? List.of() : lines,
            List.of(Component.literal("Hunting Profit Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal(" 14x ").withStyle(ChatFormatting.GRAY).append(Component.literal("Invisibug Shard").withStyle(ChatFormatting.BLUE))
                    .append(Component.literal(": 1.2M").withStyle(ChatFormatting.GOLD)),
                Component.literal("Mobs caught: ").withStyle(ChatFormatting.GRAY).append(Component.literal("14").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Total Profit: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("1.2M").withStyle(ChatFormatting.GOLD))),
            20, 20);
        Sky2MHuds.setSettingsSearch(HUD_ID, "Hunting Profit Tracker");
        Sky2MHuds.setting(HUD_ID, () -> config() != null && config().enabled);
        // Like SkyHanni, also shown in your own inventory.
        ScreenHud.register(HUD_ID, () -> shown() && Minecraft.getInstance().gui.screen()
            instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen ? lines : List.of(), null);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("resethuntingtracker").executes(c -> {
                    Minecraft.getInstance().execute(HuntingProfitTracker::reset);
                    return 1;
                })));
            }
        });
    }

    /** Resets what's shown: this session, or the total. */
    public static void reset() {
        HuntingConfig.ProfitTracker c = config();
        boolean sessionShown = c != null && c.perTrackerConfig.displayMode == HuntingConfig.TrackerSettings.DisplayMode.SESSION;
        if (sessionShown) session = new Data();
        else total = new Data();
        dirty = true;
        lines = List.of();
        Sky2MAlerts.chat(Component.literal("Hunting Profit Tracker (" + (sessionShown ? "session" : "total") + ") reset.").withStyle(ChatFormatting.YELLOW));
    }

    private static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        long delta = lastTick == 0 ? 0 : Math.min(1_000L, now - lastTick);
        lastTick = now;
        HuntingConfig.ProfitTracker c = config();
        if (c == null || !c.enabled || mc.player == null) return;
        if (isHuntingTool(mc.player.getMainHandItem())) lastToolHeld = now;
        if (now - lastHunt < ACTIVE_WINDOW_MS) {
            total.activeMs += delta;
            session.activeMs += delta;
        }
        if (++ticks % 10 == 0) lines = shown() ? build(c) : List.of();
        if (dirty && ticks % 200 == 0) save();
    }

    static boolean isHuntingTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (huntingTools.contains(Compat.neuName(stack))) return true;
        // SkyHanni's item categories: the rarity line ends with the item's category ("LEGENDARY LASSO").
        List<String> lore = AttributeShards.lore(stack);
        for (int i = lore.size() - 1; i >= 0 && i >= lore.size() - 3; i--) {
            String line = lore.get(i);
            if (line.endsWith(" LASSO") || line.endsWith(" FISHING NET")) return true;
        }
        return false;
    }

    private static List<Component> build(HuntingConfig.ProfitTracker c) {
        boolean sessionShown = c.perTrackerConfig.displayMode == HuntingConfig.TrackerSettings.DisplayMode.SESSION;
        Data data = sessionShown ? session : total;
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Hunting Profit Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
            .append(Component.literal(sessionShown ? " (Session)" : " (Total)").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false))));
        record Row(AttributeShards.Shard shard, long amount, double value) {}
        List<Row> rows = new ArrayList<>();
        double profit = 0;
        for (Map.Entry<String, Long> e : data.shards.entrySet()) {
            AttributeShards.Shard shard = AttributeShards.byName(e.getKey());
            double value = shard == null ? 0 : ItemPriceResolver.value(shard.bazaarName()) * e.getValue();
            profit += value;
            if (shard != null && value >= c.perTrackerConfig.hideCheapBelow) rows.add(new Row(shard, e.getValue(), value));
        }
        rows.sort((a, b) -> Double.compare(b.value(), a.value()));
        for (int i = 0; i < rows.size() && i < c.perTrackerConfig.maxLines; i++) {
            Row row = rows.get(i);
            out.add(Component.literal(String.format(Locale.US, " %,dx ", row.amount())).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(row.shard().rarityColour() + row.shard().displayName() + " Shard"))
                .append(Component.literal(": " + CombatFeatures.formatCoins(row.value())).withStyle(ChatFormatting.GOLD)));
        }
        if (rows.size() > c.perTrackerConfig.maxLines) {
            out.add(Component.literal(" " + (rows.size() - c.perTrackerConfig.maxLines) + " more...").withStyle(ChatFormatting.DARK_GRAY));
        }
        out.add(Component.literal("Mobs caught: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d", data.totalMobsCaught)).withStyle(ChatFormatting.YELLOW)));
        out.add(Component.literal("Shards collected: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d", data.totalShardsGained)).withStyle(ChatFormatting.YELLOW)));
        out.add(Component.literal("Total Profit: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(CombatFeatures.formatCoins(profit)).withStyle(ChatFormatting.GOLD)));
        if (data.totalMobsCaught > 0) {
            out.add(Component.literal("Profit per Shard: ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(CombatFeatures.formatCoins(profit / data.totalShardsGained)).withStyle(ChatFormatting.GOLD)));
        }
        if (data.activeMs >= 60_000L) {
            out.add(Component.literal("Profit per Hour: ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(CombatFeatures.formatCoins(profit / (data.activeMs / 3_600_000d))).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" (" + uptime(data.activeMs) + ")").withStyle(ChatFormatting.GRAY)));
        }
        return out;
    }

    private static String uptime(long ms) {
        long minutes = ms / 60_000L;
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
    }

    private static void save() {
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(total), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save hunting-profit.json: " + e.getMessage());
        }
    }
}
