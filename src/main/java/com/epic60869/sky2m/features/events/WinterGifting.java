package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.price.ItemPriceResolver;
import com.epic60869.sky2m.tools.Sky2MSkyblockTime;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Winter gifting and Jerry's Workshop helpers: Unique Gifting Opportunities, the Gift Profit Tracker, Gift Clean Display,
 * the Frozen Treasure highlighter and the New Year Cake reminder. Ported from SkyHanni's UniqueGiftingOpportunitiesFeatures,
 * GiftProfitTracker, GiftCleanDisplay, FrozenTreasureHighlighter and NewYearCakeReminder
 * (https://github.com/hannibal002/SkyHanni, features/gifting/, features/misc/ and features/event/, LGPL-2.1).
 */
public final class WinterGifting {
    private static final String TRACKER_HUD = "events_winter_gift_tracker";
    private static final String TRACKER_KEY = "giftTracker";
    private static final String GIFTED_KEY = "giftedPlayers";
    private static final String CAKE_KEY = "cakeYear";
    private static final String GIFTED_MARK = "ꤥ";
    private static final long GIFTING_WINDOW_MS = 3 * 60 * 1000L;
    private static final long CAKE_REMINDER_MS = 30_000L;
    private static final int SCAN_INTERVAL_TICKS = 10;

    private static final String RARITY = "COMMON|RARE|SWEET|SANTA TIER|PARTY TIER";
    private static final Pattern REWARD = Pattern.compile("^(" + RARITY + ")! (.*)$");
    private static final Pattern COINS = Pattern.compile("^\\+([\\d,]+) Coins gift with .*");
    private static final Pattern SKILL_XP = Pattern.compile("^\\+([\\d,]+) ([\\w ]+) XP gift with .*");
    private static final Pattern ITEM = Pattern.compile("^(.+?) gift with .*");
    private static final Pattern NORTH_STARS = Pattern.compile("^EXTRA! \\+([\\d,]+) North Stars?");
    private static final Pattern GIFTED_CHAT = Pattern.compile("^\\+1 Unique Gift given! To (.+)!$");
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile("^(.+?) x([\\d,]+)$");
    private static final Pattern AMOUNT_PREFIX = Pattern.compile("^([\\d,]+)x (.+)$");
    private static final Pattern CAKE_SIDEBAR = Pattern.compile("New Year Event! .*");

    private static final Set<String> giftedPlayers = new HashSet<>();
    private static final Set<Integer> notGifted = new HashSet<>();
    private static List<BlockPos> treasures = List.of();
    private static boolean loaded;
    private static long lastGiftReward;
    private static long lastCakeReminder;
    private static int ticks;

    private WinterGifting() {}

    private static EventsConfig.Winter config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.winter;
    }

    static void init() {
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientTickEvents.END_CLIENT_TICK.register(WinterGifting::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            notGifted.clear();
            treasures = List.of();
        });
        EntityGlow.register(entity -> !notGifted.isEmpty() && notGifted.contains(entity.getId()) ? 0x55FF55 : -1);

        Sky2MWorldRender.register(collector -> {
            EventsConfig.Winter c = config();
            if (c == null || !c.frozenHighlighter || treasures.isEmpty()) return;
            float[] rgb = {0.33f, 1f, 1f};
            for (BlockPos pos : treasures) {
                collector.submitFilledBox(pos, rgb, 0.3f, true);
                collector.submitOutlinedBox(pos, rgb, 2f, true);
            }
        });

        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container
                && Sky2MLocation.strip(container.getTitle().getString()).trim().equals("Baker")) {
                markCakeClaimed();
            }
        });

        Sky2MHuds.register(TRACKER_HUD, "Gift Profit Tracker", () -> {
            EventsConfig.Winter c = config();
            if (c == null || !c.giftTracker || !Sky2MLocation.onSkyblock()) return false;
            return !c.giftTrackerOnlyGifting || holdingGift() || System.currentTimeMillis() - lastGiftReward < GIFTING_WINDOW_MS;
        }, WinterGifting::trackerLines, List.of(
            Component.literal("Gift Profit Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
            Component.literal("Total Rewards: 12").withStyle(ChatFormatting.YELLOW),
            Component.literal("Total Profit: 340k").withStyle(ChatFormatting.GOLD)), 10, 240);
        Sky2MHuds.setSettingsSearch(TRACKER_HUD, "Gift Profit Tracker");
        Sky2MHuds.setting(TRACKER_HUD, () -> config() != null && config().giftTracker);
    }

    private static boolean holdingGift() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        String name = mc.player.getMainHandItem().getHoverName().getString();
        return name.endsWith(" Gift") && (name.startsWith("White") || name.startsWith("Green") || name.startsWith("Red")
            || name.startsWith("Party"));
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        EventsConfig.Winter c = config();
        if (c == null) return;

        Matcher gifted = GIFTED_CHAT.matcher(text);
        if (c.giftingOpportunities && gifted.find()) addGifted(gifted.group(1).trim());

        if (text.equals("You claimed a New Year Cake!")) markCakeClaimed();

        if (!c.giftTracker) return;
        Matcher stars = NORTH_STARS.matcher(text);
        if (stars.find()) {
            lastGiftReward = System.currentTimeMillis();
            add("northStars", parse(stars.group(1)));
            return;
        }
        Matcher reward = REWARD.matcher(text);
        if (!reward.find()) return;
        lastGiftReward = System.currentTimeMillis();
        add("rarity_" + reward.group(1), 1);
        String rest = reward.group(2).trim();

        Matcher coins = COINS.matcher(rest);
        if (coins.find()) {
            add("coins", parse(coins.group(1)));
            return;
        }
        Matcher xp = SKILL_XP.matcher(rest);
        if (xp.find()) {
            add("xp_" + xp.group(2), parse(xp.group(1)));
            return;
        }
        Matcher item = ITEM.matcher(rest);
        if (item.find()) {
            String name = item.group(1).trim();
            long amount = 1;
            Matcher suffix = AMOUNT_SUFFIX.matcher(name);
            Matcher prefix = AMOUNT_PREFIX.matcher(name);
            if (suffix.find()) {
                name = suffix.group(1);
                amount = parse(suffix.group(2));
            } else if (prefix.find()) {
                amount = parse(prefix.group(1));
                name = prefix.group(2);
            }
            add("item_" + name, amount);
        }
    }

    private static long parse(String number) {
        try {
            return Long.parseLong(number.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void add(String key, long amount) {
        JsonObject data = EventsFeatures.object(TRACKER_KEY);
        data.addProperty(key, count(key) + amount);
        EventsFeatures.save();
    }

    private static long count(String key) {
        var e = EventsFeatures.object(TRACKER_KEY).get(key);
        try {
            return e == null ? 0 : e.getAsLong();
        } catch (Exception ex) {
            return 0;
        }
    }

    static void resetTracker() {
        EventsFeatures.remove(TRACKER_KEY);
        EventsFeatures.save();
        Minecraft.getInstance().execute(() -> EventsFeatures.say(Component.literal("Reset the Gift Profit Tracker.").withStyle(ChatFormatting.YELLOW)));
    }

    private static List<Component> trackerLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Gift Profit Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        JsonObject data = EventsFeatures.object(TRACKER_KEY);
        long rewards = 0;
        double profit = 0;
        long coins = count("coins");
        profit += coins;
        Map<String, Long> items = new TreeMap<>();
        for (var entry : data.entrySet()) {
            if (entry.getKey().startsWith("rarity_")) rewards += count(entry.getKey());
            else if (entry.getKey().startsWith("item_")) items.put(entry.getKey().substring(5), count(entry.getKey()));
        }
        for (Map.Entry<String, Long> e : items.entrySet()) {
            double value = ItemPriceResolver.valueByName(e.getKey());
            if (value > 0) profit += value * e.getValue();
            lines.add(Component.literal(EventsFeatures.grouped(e.getValue()) + "x " + e.getKey()
                + (value > 0 ? " (" + EventsFeatures.compact(value * e.getValue()) + ")" : "")).withStyle(ChatFormatting.AQUA));
        }
        if (coins > 0) lines.add(Component.literal(EventsFeatures.compact(coins) + " Gift Coins").withStyle(ChatFormatting.GOLD));
        long stars = count("northStars");
        if (stars > 0) lines.add(Component.literal(EventsFeatures.grouped(stars) + " North Stars").withStyle(ChatFormatting.LIGHT_PURPLE));
        for (var entry : data.entrySet()) {
            if (entry.getKey().startsWith("xp_")) {
                lines.add(Component.literal(EventsFeatures.compact(count(entry.getKey())) + " " + entry.getKey().substring(3) + " XP")
                    .withStyle(ChatFormatting.DARK_AQUA));
            }
        }
        lines.add(Component.literal("Total Rewards: " + EventsFeatures.grouped(rewards)).withStyle(ChatFormatting.YELLOW));
        lines.add(Component.literal("Total Profit: " + EventsFeatures.compact(profit)).withStyle(ChatFormatting.GOLD));
        return lines;
    }

    // ------------------------------------------------------------------------------------------------ gifted players

    private static void loadGifted() {
        if (loaded) return;
        loaded = true;
        for (var e : EventsFeatures.array(GIFTED_KEY)) giftedPlayers.add(e.getAsString());
    }

    private static void addGifted(String name) {
        loadGifted();
        if (giftedPlayers.add(name)) {
            JsonArray array = EventsFeatures.array(GIFTED_KEY);
            array.add(name);
            EventsFeatures.save();
        }
    }

    // ------------------------------------------------------------------------------------------------ cake

    private static long skyblockYear() {
        return (Instant.now().toEpochMilli() - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli()) / Sky2MSkyblockTime.YEAR_MILLIS + 1;
    }

    private static void markCakeClaimed() {
        EventsFeatures.put(CAKE_KEY, skyblockYear());
        EventsFeatures.save();
    }

    private static void cakeTick(Minecraft mc, EventsConfig.Winter c) {
        if (!c.newYearCakeReminder || mc.gui.screen() != null) return;
        if (EventsFeatures.number(CAKE_KEY, 0) == skyblockYear()) return;
        boolean cakeTime = false;
        for (String line : Sky2MLocation.scoreboard()) {
            if (CAKE_SIDEBAR.matcher(Sky2MLocation.strip(line).trim()).find()) {
                cakeTime = true;
                break;
            }
        }
        if (!cakeTime || System.currentTimeMillis() - lastCakeReminder < CAKE_REMINDER_MS) return;
        lastCakeReminder = System.currentTimeMillis();
        EventsFeatures.sayClickable("Reminding you to grab the free New Year Cake. Click here to open the baker menu!",
            "/openbaker", "Click to run /openbaker");
    }

    // ------------------------------------------------------------------------------------------------ scanning

    private static void tick(Minecraft mc) {
        EventsConfig.Winter c = config();
        if (c == null || mc.level == null || mc.player == null || !Sky2MLocation.onSkyblock()) {
            if (!notGifted.isEmpty() || !treasures.isEmpty()) {
                notGifted.clear();
                treasures = List.of();
            }
            return;
        }
        if (++ticks % SCAN_INTERVAL_TICKS != 0) return;
        if (ticks % (SCAN_INTERVAL_TICKS * 2) == 0) cakeTick(mc, c);

        AABB area = mc.player.getBoundingBox().inflate(c.frozenHighlighter ? 50 : 24);

        if (c.giftClean) {
            for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, area, ArmorStand::hasCustomName)) {
                String name = stand.getCustomName().getString();
                if ((name.startsWith("From:") || name.startsWith("To:")) && stand.isCustomNameVisible()) stand.setCustomNameVisible(false);
            }
        }

        notGifted.clear();
        if (c.giftingOpportunities && LocalDate.now().getMonth() == Month.DECEMBER && (!c.giftingOnlyHolding || holdingGift())) {
            loadGifted();
            for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, area, ArmorStand::hasCustomName)) {
                if (!stand.getCustomName().getString().equals(GIFTED_MARK)) continue;
                if (isRed(stand.getCustomName())) {
                    Player near = nearestPlayer(mc, stand);
                    if (near != null) addGifted(near.getName().getString());
                }
            }
            for (Player player : mc.level.players()) {
                if (player == mc.player || player.getUUID().version() != 4) continue;
                if (!giftedPlayers.contains(player.getName().getString())) notGifted.add(player.getId());
            }
        }

        if (c.frozenHighlighter && Sky2MLocation.areaIs("Jerry's Workshop") && Sky2MLocation.location().contains("Glacial Cave")) {
            List<BlockPos> found = new ArrayList<>();
            for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, area)) {
                int items = 0;
                for (EquipmentSlot slot : EquipmentSlot.values()) if (!stand.getItemBySlot(slot).isEmpty()) items++;
                if (items != 1) continue;
                ItemStack helmet = stand.getItemBySlot(EquipmentSlot.HEAD);
                if (helmet.isEmpty()) continue;
                if (helmet.is(net.minecraft.world.item.Items.PLAYER_HEAD) && helmet.getHoverName().getString().endsWith("Head")) continue;
                BlockPos pos = stand.blockPosition().above(2);
                BlockState state = mc.level.getBlockState(pos);
                if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) found.add(pos);
            }
            treasures = found;
        } else {
            treasures = List.of();
        }
    }

    private static Player nearestPlayer(Minecraft mc, ArmorStand stand) {
        Player single = null;
        for (Player p : mc.level.getEntitiesOfClass(Player.class, stand.getBoundingBox().inflate(2.0), e -> e.getUUID().version() == 4)) {
            if (single != null) return null;
            single = p;
        }
        return single;
    }

    private static boolean isRed(Component name) {
        var color = name.getStyle().getColor();
        if (color == null) {
            for (Component sibling : name.getSiblings()) {
                color = sibling.getStyle().getColor();
                if (color != null) break;
            }
        }
        return color != null && color.getValue() == net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.RED).getValue();
    }
}
