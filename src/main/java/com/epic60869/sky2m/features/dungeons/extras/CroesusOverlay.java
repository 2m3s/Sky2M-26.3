package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.DungeonChestProfit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Croesus Chest Tracker (features/dungeon/CroesusChestTracker, https://github.com/hannibal002/SkyHanni,
 * LGPL-2.1), for dungeon runs:
 * <ul>
 *     <li>Every finished run is added to a list (newest first, at most 60 like Croesus, runs over 3 days old dropped);
 *     opening Croesus's menu reads each run's state off its page: unopened, opened, or a key used.</li>
 *     <li>"Chests: n/60" on screen, the unopened runs in Croesus (Dungeon Hub, dungeons, Forgotten Skull).</li>
 *     <li>The run colours (unopened dark purple, opened dark aqua), a green check on runs a Kismet Feather was used
 *     on and the Kismet Feather count on the Reroll Chest are drawn by {@link DungeonChestProfit}'s slot hooks.</li>
 *     <li>A warning when 55 or more runs wait in Croesus.</li>
 * </ul>
 */
public final class CroesusOverlay {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("sky2m");
    private static final Pattern CROESUS = Pattern.compile("^(?:\\((\\d+)/\\d+\\) )?Croesus$");
    private static final Pattern FLOOR = Pattern.compile("Floor (?<floor>[IVX]+)");
    private static final Pattern TEAM_SCORE = Pattern.compile("^\\s*Team Score: [\\d,]+ \\([A-DS+]+\\)");
    private static final int MAX_RUNS = 60, EMPTY_SLOT = 22, FRONT_ARROW_SLOT = 53;
    private static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;

    /** 0 unopened, 1 opened, 2 a key was used (nothing left to open). */
    private static final int UNOPENED = 0, OPENED = 1, KEY_USED = 2;

    private static final class Run {
        String floor;
        long time;
        int state;
        boolean kismet;
    }

    private static List<Run> runs;
    private static String loadedProfile;
    private static int ticks;

    private CroesusOverlay() {}

    private static DungeonExtras.Croesus config() {
        DungeonExtras.Config c = DungeonExtras.config();
        return c == null ? null : c.croesus;
    }

    /** SkyHanni Style is on: the colours DungeonChestProfit draws Croesus's runs in. */
    public static boolean skyHanniStyle() {
        DungeonExtras.Croesus c = config();
        return c != null && c.skyHanniStyle;
    }

    public static String unopenedColour() {
        return config().unopenedColour;
    }

    public static String openedColour() {
        return config().openedColour;
    }

    static void init() {
        Sky2MChat.onChat(message -> {
            DungeonExtras.Croesus c = config();
            if (c == null || !Sky2MLocation.inDungeon() || !TEAM_SCORE.matcher(message.text()).find()) return;
            String floor = Sky2MLocation.dungeonFloor();
            if (floor.isEmpty() || floor.equals("E")) return;
            addRun(floor);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 != 0) return;
            DungeonExtras.Croesus c = config();
            if (c == null || !Compat.isOnSkyblock() || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
                DungeonChestProfit.setExtras(Map.of(), Set.of());
                return;
            }
            String title = ChatFormatting.stripFormatting(screen.getTitle().getString()).trim();
            Matcher croesus = CROESUS.matcher(title);
            if (croesus.matches()) {
                scanCroesus(screen, croesus.group(1) == null ? 0 : Integer.parseInt(croesus.group(1)) - 1, c);
            } else if (c.kismetCount && DungeonChestProfit.isChest(title)) {
                rerollCount(screen);
            } else {
                DungeonChestProfit.setExtras(Map.of(), Set.of());
            }
        });
        Sky2MHuds.register("croesus_chests", "Croesus Chests", () -> shouldShow(), CroesusOverlay::lines,
            List.of(Component.literal("Chests: ").withStyle(ChatFormatting.GRAY).append(Component.literal("23").withStyle(ChatFormatting.YELLOW)).append(Component.literal("/60").withStyle(ChatFormatting.GRAY))), 10, 210);
        Sky2MHuds.setting("croesus_chests", () -> config() != null && config().overlay);
    }

    // ------------------------------------------------------------------------------------------------ the list of runs

    private static Path file(String profile) {
        return DIR.resolve(profile.isEmpty() ? "croesus-runs.json" : "croesus-runs-" + profile + ".json");
    }

    private static synchronized List<Run> runs() {
        String profile = Sky2MStorageSearch.currentProfile();
        if (profile == null) profile = "";
        if (runs == null || !profile.equals(loadedProfile)) {
            loadedProfile = profile;
            runs = null;
            try {
                Path file = file(profile);
                if (Files.exists(file)) runs = GSON.fromJson(Files.readString(file), new TypeToken<List<Run>>() {}.getType());
            } catch (Exception e) {
                System.err.println("[Sky2M] Croesus Overlay couldn't read its runs: " + e.getMessage());
            }
            if (runs == null) runs = new ArrayList<>();
        }
        return runs;
    }

    private static synchronized void save() {
        String json = GSON.toJson(runs());
        Path file = file(loadedProfile);
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, json);
            } catch (Exception e) {
                System.err.println("[Sky2M] Croesus Overlay couldn't save its runs: " + e.getMessage());
            }
        });
    }

    private static synchronized void addRun(String floor) {
        List<Run> list = runs();
        Run run = new Run();
        run.floor = floor;
        run.time = System.currentTimeMillis();
        list.addFirst(run);
        while (list.size() > MAX_RUNS) list.removeLast();
        removeOld();
        save();
        DungeonExtras.Croesus c = config();
        if (c != null && c.limitWarning && lastActive() >= 55) {
            Sky2MAlerts.chat(Component.literal("You are close to the Croesus limit. Please open your chests!").withStyle(ChatFormatting.RED));
        }
    }

    private static void removeOld() {
        long now = System.currentTimeMillis();
        runs().removeIf(r -> r.time != 0 && now - r.time > MAX_AGE_MS);
    }

    private static int unopened() {
        int n = 0;
        for (Run r : runs()) if (r.state == UNOPENED) n++;
        return n;
    }

    /** SkyHanni's getLastActiveChest: the position of the last run still waiting, counted from 1. */
    private static int lastActive() {
        List<Run> list = runs();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).state == UNOPENED) return i + 1;
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ reading Croesus

    private static int runIndex(int slot, int page) {
        int index;
        if (slot >= 10 && slot <= 16) index = slot - 10;
        else if (slot >= 19 && slot <= 25) index = slot - 12;
        else if (slot >= 28 && slot <= 34) index = slot - 14;
        else if (slot >= 37 && slot <= 43) index = slot - 16;
        else return -1;
        return index + page * 28;
    }

    private static void scanCroesus(AbstractContainerScreen<?> screen, int page, DungeonExtras.Croesus config) {
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= FRONT_ARROW_SLOT) return;
        Map<Integer, String> noLabels = Map.of();
        Set<Integer> checks = new HashSet<>();
        boolean empty = ChatFormatting.stripFormatting(slots.get(EMPTY_SLOT).getItem().getHoverName().getString()).equals("No treasures!");
        if (empty) {
            synchronized (CroesusOverlay.class) {
                if (!runs().isEmpty()) {
                    runs().clear();
                    save();
                }
            }
            DungeonChestProfit.setExtras(noLabels, checks);
            return;
        }
        synchronized (CroesusOverlay.class) {
            List<Run> list = runs();
            int onPage = 0;
            boolean changed = false;
            for (Slot slot : slots) {
                if (slot.container instanceof Inventory) continue;
                int index = runIndex(slot.index, page);
                if (index < 0) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
                if (!name.endsWith("The Catacombs")) continue;
                onPage++;
                while (list.size() <= index) list.add(new Run());
                Run run = list.get(index);
                int state = state(stack);
                if (state >= 0 && run.state != state) {
                    run.state = state;
                    changed = true;
                }
                if (run.floor == null) {
                    run.floor = (name.startsWith("Master Mode") ? "M" : "F") + floorNumber(stack);
                    changed = true;
                }
                boolean kismet = DungeonChestProfit.kismetUsed(stack);
                if (kismet != run.kismet) {
                    run.kismet = kismet;
                    changed = true;
                }
                if (kismet && config.kismetCheck) checks.add(slot.index);
            }
            boolean lastPage = !slots.get(FRONT_ARROW_SLOT).getItem().is(Items.ARROW);
            if (lastPage) {
                int total = page * 28 + onPage;
                while (list.size() > total) {
                    list.removeLast();
                    changed = true;
                }
            }
            if (changed) save();
        }
        DungeonChestProfit.setExtras(noLabels, checks);
    }

    private static int state(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return -1;
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text.equals("No more chests to open!")) return KEY_USED;
            if (text.startsWith("Opened Chest:") || text.startsWith("Opened chest:")) return OPENED;
            if (text.equals("No chests opened yet!")) return UNOPENED;
        }
        return -1;
    }

    private static int floorNumber(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return 0;
        for (Component line : lore.lines()) {
            Matcher m = FLOOR.matcher(ChatFormatting.stripFormatting(line.getString()));
            if (m.find()) return DungeonChestProfit.roman(m.group("floor"));
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ Kismet count

    private static void rerollCount(AbstractContainerScreen<?> screen) {
        Map<Integer, String> counts = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ChatFormatting.stripFormatting(stack.getHoverName().getString()).equals("Reroll Chest")) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            boolean used = false;
            if (lore != null) {
                for (Component line : lore.lines()) if (ChatFormatting.stripFormatting(line.getString()).equals("You already rerolled a chest!")) used = true;
            }
            if (!used) counts.put(slot.index, "§a" + kismetFeathers(screen));
        }
        DungeonChestProfit.setExtras(counts, Set.of());
    }

    private static int kismetFeathers(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        int total = 0;
        if (mc.player == null) return 0;
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && "KISMET_FEATHER".equals(Compat.neuName(stack))) total += stack.getCount();
        }
        return total;
    }

    // ------------------------------------------------------------------------------------------------ the overlay

    private static boolean shouldShow() {
        DungeonExtras.Croesus c = config();
        if (c == null || !c.overlay || !Compat.isOnSkyblock()) return false;
        return Sky2MLocation.inDungeon() || Sky2MLocation.area().equals("Dungeon Hub") || Sky2MLocation.location().contains("Forgotten Skull");
    }

    private static List<Component> lines() {
        int n;
        synchronized (CroesusOverlay.class) {
            removeOld();
            n = unopened();
        }
        ChatFormatting colour = n >= 45 ? ChatFormatting.DARK_RED : n >= 30 ? ChatFormatting.RED : n >= 15 ? ChatFormatting.YELLOW : ChatFormatting.GOLD;
        return List.of(Component.literal("Chests: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(n)).withStyle(colour))
            .append(Component.literal("/" + MAX_RUNS).withStyle(ChatFormatting.GRAY)));
    }
}
