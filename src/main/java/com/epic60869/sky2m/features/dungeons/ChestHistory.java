package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.price.ItemPriceResolver;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chest Spending History: every dungeon reward chest you open (the Wood to Bedrock chests at the end of a run, or the
 * same chests at Croesus) is saved to config/sky2m/chest-history[-profile].json with its floor, type, what it cost
 * (coins, a Dungeon Chest Key, and a Kismet Feather if you rerolled it first), the items inside with their worth (priced
 * like Dungeon Chest Value) and the profit. /s2 chesthistory opens ChestHistoryScreen; /s2 chesthistory chat prints
 * the totals overall, per floor and per chest type, and the best chests.
 * <p>
 * A chest counts as bought when its "Open Reward Chest" button is clicked and Hypixel doesn't refuse it in chat (not
 * enough coins, no key, already opened) within a moment; the contents are read from the chest menu at the click,
 * where Hypixel already lists them.
 */
public final class ChestHistory {
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("sky2m");
    private static final Gson GSON = new Gson();
    private static final int MAX_CHESTS = 2000;
    /** Ticks to wait for Hypixel to refuse a click before the chest counts as opened. */
    private static final int CONFIRM_TICKS = 30;
    /** A Kismet reroll is charged to the next chest of its type opened within this long. */
    private static final long KISMET_WINDOW_MS = 5 * 60_000;
    private static final Pattern CHEST = Pattern.compile("^(?<type>Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$");
    /** Croesus's menu for one run: "Catacombs - Floor VII" or "Master Catacombs - Floor VII". */
    private static final Pattern CROESUS_RUN = Pattern.compile("^(?<master>Master )?Catacombs - Floor (?<floor>[IV]+)$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ROOT);

    /** One opened chest. Costs and values are in coins. */
    public static final class Chest {
        public long time;
        public String floor;
        public String type;
        /** "Run" (end of a run) or "Croesus". */
        public String source;
        public double coins, keyCost, kismetCost;
        public boolean key, kismet;
        public double cost, value, profit;
        public List<Drop> items = new ArrayList<>();

        String date() {
            return DATE.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
        }
    }

    public record Drop(String id, String name, int count, double value) {}

    private static List<Chest> chests;
    private static String loadedProfile;
    /** The chest waiting for Hypixel not to refuse it, and how many ticks are left. */
    private static Chest pending;
    private static int pendingTicks;
    private static int pendingMenu = -1;
    /** The menu id of the last chest recorded, so a double click isn't counted twice. */
    private static int recordedMenu = -1;
    /** A Kismet reroll waiting to be confirmed, then charged to the next chest of its type. */
    private static String pendingKismetType;
    private static int pendingKismetTicks;
    private static String kismetType;
    private static long kismetAt;
    /** The floor of the last Croesus run menu opened ("F7", "M7"). */
    private static String croesusFloor = "";

    private ChestHistory() {}

    static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.dungeons.chestProfit.history;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            String title = ChatFormatting.stripFormatting(screen.getTitle().getString()).trim();
            Matcher run = CROESUS_RUN.matcher(title);
            if (run.matches()) {
                croesusFloor = (run.group("master") != null ? "M" : "F") + DungeonChestProfit.roman(run.group("floor"));
                return;
            }
            Matcher chest = CHEST.matcher(title);
            if (!chest.matches()) return;
            String type = chest.group("type");
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (enabled() && Compat.isOnSkyblock()) onClick(container, type);
                return true;
            });
        });
        Sky2MChat.onChat(message -> onChat(message.text()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (pending != null && --pendingTicks <= 0) {
                record(pending);
                recordedMenu = pendingMenu;
                pending = null;
            }
            if (pendingKismetType != null && --pendingKismetTicks <= 0) {
                kismetType = pendingKismetType;
                kismetAt = System.currentTimeMillis();
                pendingKismetType = null;
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("chesthistory")
                    .executes(c -> Compat.queueOpenScreen(new ChestHistoryScreen()))
                    .then(ClientCommands.literal("chat").executes(c -> {
                        summary();
                        return 1;
                    }))));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ capture

    private static void onClick(AbstractContainerScreen<?> screen, String type) {
        Slot clicked = ((AbstractContainerScreenAccessor) screen).getFocusedSlot();
        if (clicked == null || clicked.container instanceof Inventory) return;
        ItemStack button = clicked.getItem();
        if (button.isEmpty()) return;
        String name = ChatFormatting.stripFormatting(button.getHoverName().getString()).trim();
        List<String> lore = DungeonChestProfit.lore(button);
        int menu = screen.getMenu().containerId;
        if (name.contains("Reroll") && lore.stream().anyMatch(l -> l.contains("Kismet Feather"))) {
            pendingKismetType = type;
            pendingKismetTicks = CONFIRM_TICKS;
            return;
        }
        if (!name.equals("Open Reward Chest") || pending != null || recordedMenu == menu) return;

        Chest chest = new Chest();
        chest.time = System.currentTimeMillis();
        chest.type = type;
        String floor = Sky2MLocation.dungeonFloor();
        boolean inRun = Sky2MLocation.inDungeon() && floor != null && !floor.isEmpty();
        chest.floor = inRun ? floor : croesusFloor.isEmpty() ? "?" : croesusFloor;
        chest.source = inRun ? "Run" : "Croesus";
        // The button's lore: "Cost", then "2,000,000 Coins" (or "FREE") and maybe "Dungeon Chest Key".
        boolean cost = false;
        for (String line : lore) {
            String text = line.trim();
            if (text.equals("Cost")) {
                cost = true;
                continue;
            }
            if (!cost) continue;
            if (text.isEmpty()) break;
            if (text.endsWith(" Coins")) chest.coins += DungeonChestProfit.parseInt(text.replace(" Coins", "").replace(",", ""));
            else if (text.startsWith("Dungeon Chest Key")) {
                chest.key = true;
                chest.keyCost = keyPrice();
            }
        }
        if (kismetType != null && kismetType.equals(type) && chest.time - kismetAt < KISMET_WINDOW_MS) {
            chest.kismet = true;
            chest.kismetCost = price("KISMET_FEATHER");
            kismetType = null;
        }
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK))) continue;
            String itemName = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
            String id = DungeonChestProfit.lootId(stack, itemName);
            if (id.isEmpty()) continue;
            int count = DungeonChestProfit.lootQuantity(stack, itemName);
            double value = DungeonChestProfit.lootValue(id) * count;
            chest.items.add(new Drop(id, itemName, count, value));
            chest.value += value;
        }
        chest.cost = chest.coins + chest.keyCost + chest.kismetCost;
        chest.profit = chest.value - chest.cost;
        pending = chest;
        pendingTicks = CONFIRM_TICKS;
        pendingMenu = menu;
    }

    /** Hypixel refusing the purchase or the reroll (not enough coins, no key or feather, already opened). */
    private static void onChat(String raw) {
        if (pending == null && pendingKismetType == null) return;
        String text = raw.trim();
        if (text.contains(":")) return; // player chat
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("don't have enough") || lower.contains("do not have enough") || lower.contains("you need")
            || lower.contains("you cannot") || lower.contains("you can't") || lower.contains("already")) {
            pending = null;
            pendingKismetType = null;
        }
    }

    private static double keyPrice() {
        double price = Sky2MPriceTooltip.price("DUNGEON_CHEST_KEY", FeatureConfigs.ProfitPriceSource.SELL_ORDER);
        return price > 0 ? price : price("DUNGEON_CHEST_KEY");
    }

    private static double price(String id) {
        double price = ItemPriceResolver.value(id);
        return price > 0 ? price : 0;
    }

    private static void record(Chest chest) {
        List<Chest> all = chests();
        all.addFirst(chest);
        while (all.size() > MAX_CHESTS) all.removeLast();
        save();
    }

    // ------------------------------------------------------------------------------------------------ summary

    private static final class Totals {
        int opened;
        double spent, value, profit;

        void add(Chest chest) {
            opened++;
            spent += chest.cost;
            value += chest.value;
            profit += chest.profit;
        }
    }

    private static void summary() {
        List<Chest> all = chests();
        if (all.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No reward chests recorded yet.").withStyle(ChatFormatting.GRAY));
            return;
        }
        Totals total = new Totals();
        Map<String, Totals> floors = new LinkedHashMap<>();
        Map<String, Map<String, Totals>> floorTypes = new LinkedHashMap<>();
        Map<String, Totals> types = new LinkedHashMap<>();
        List<Chest> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing((Chest c) -> c.floor == null ? "?" : c.floor));
        for (Chest chest : sorted) {
            String floor = chest.floor == null ? "?" : chest.floor;
            total.add(chest);
            floors.computeIfAbsent(floor, f -> new Totals()).add(chest);
            floorTypes.computeIfAbsent(floor, f -> new LinkedHashMap<>()).computeIfAbsent(chest.type, t -> new Totals()).add(chest);
        }
        for (String type : List.of("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock")) {
            for (Chest chest : all) if (type.equals(chest.type)) types.computeIfAbsent(type, t -> new Totals()).add(chest);
        }

        MutableComponent out = Component.literal("Chest Spending History").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(" (" + (all.size() >= MAX_CHESTS ? "last " : "") + all.size() + " chests)").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n")).append(line("Total", total, null));
        out.append(Component.literal("\nPer floor").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" (hover for chest types)").withStyle(ChatFormatting.DARK_GRAY)));
        for (Map.Entry<String, Totals> e : floors.entrySet()) {
            MutableComponent hover = Component.literal(e.getKey() + " by chest").withStyle(ChatFormatting.GOLD);
            for (Map.Entry<String, Totals> t : floorTypes.get(e.getKey()).entrySet()) hover.append(Component.literal("\n")).append(line(t.getKey(), t.getValue(), null));
            out.append(Component.literal("\n")).append(line(e.getKey(), e.getValue(), hover));
        }
        out.append(Component.literal("\nPer chest").withStyle(ChatFormatting.YELLOW));
        for (Map.Entry<String, Totals> e : types.entrySet()) out.append(Component.literal("\n")).append(line(e.getKey(), e.getValue(), null));

        List<Chest> best = new ArrayList<>(all);
        best.sort(Comparator.comparingDouble((Chest c) -> c.profit).reversed());
        out.append(Component.literal("\nBest chests").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" (hover for contents)").withStyle(ChatFormatting.DARK_GRAY)));
        for (int i = 0; i < Math.min(5, best.size()); i++) {
            Chest chest = best.get(i);
            Drop top = chest.items.stream().max(Comparator.comparingDouble(Drop::value)).orElse(null);
            MutableComponent hover = Component.literal(chest.floor + " " + chest.type + " Chest (" + chest.source + ")").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n" + chest.date()).withStyle(ChatFormatting.DARK_GRAY));
            for (Drop drop : chest.items) {
                hover.append(Component.literal("\n" + drop.name() + (drop.count() > 1 && !drop.name().endsWith("x" + drop.count()) ? " x" + drop.count() : "")).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" " + CombatFeatures.formatCoins(drop.value())).withStyle(ChatFormatting.GOLD));
            }
            hover.append(Component.literal("\nCost: " + CombatFeatures.formatCoins(chest.cost)
                + (chest.key ? " (with key)" : "") + (chest.kismet ? " (rerolled)" : "")).withStyle(ChatFormatting.RED));
            MutableComponent row = Component.literal(" " + (i + 1) + ". ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(chest.floor + " " + chest.type).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(top == null ? "" : " " + top.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" " + signed(chest.profit)).withStyle(chest.profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            out.append(Component.literal("\n")).append(row.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover))));
        }
        Sky2MAlerts.chat(out);
    }

    /** " F7: 12 opened, spent 30M, value 45M, profit +15M (avg +1.25M)". */
    private static MutableComponent line(String label, Totals t, Component hover) {
        double average = t.opened == 0 ? 0 : t.profit / t.opened;
        MutableComponent line = Component.literal(" " + label + ": ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal(t.opened + " opened, spent ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(CombatFeatures.formatCoins(t.spent)).withStyle(ChatFormatting.RED))
            .append(Component.literal(", value ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(CombatFeatures.formatCoins(t.value)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(", profit ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(signed(t.profit)).withStyle(t.profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
            .append(Component.literal(" (avg " + signed(average) + ")").withStyle(ChatFormatting.DARK_GRAY));
        if (hover != null) line.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)));
        return line;
    }

    private static String signed(double value) {
        return (value < 0 ? "-" : "+") + CombatFeatures.formatCoins(Math.abs(value));
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static Path file(String profile) {
        return DIR.resolve(profile.isEmpty() ? "chest-history.json" : "chest-history-" + profile + ".json");
    }

    /** The current profile's chests, newest first (reloaded when the profile changes). */
    static synchronized List<Chest> chests() {
        String profile = Sky2MStorageSearch.currentProfile();
        if (profile == null) profile = "";
        if (chests == null || !profile.equals(loadedProfile)) {
            loadedProfile = profile;
            chests = null;
            Path file = file(profile);
            try {
                chests = Files.exists(file) ? GSON.fromJson(Files.readString(file), new TypeToken<List<Chest>>() {}.getType()) : null;
            } catch (Exception e) {
                System.err.println("[Sky2M] Chest History couldn't read its chests: " + e.getMessage());
            }
            if (chests == null) chests = new ArrayList<>();
        }
        return chests;
    }

    private static synchronized void save() {
        String json = GSON.toJson(chests());
        Path file = file(loadedProfile);
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, json);
            } catch (Exception e) {
                System.err.println("[Sky2M] Chest History couldn't save its chests: " + e.getMessage());
            }
        });
    }

    /** The config's Reset button: forgets the current profile's chests. */
    public static synchronized void reset() {
        chests().clear();
        save();
        Sky2MAlerts.chat(Component.literal("Chest spending history reset.").withStyle(ChatFormatting.YELLOW));
    }
}
