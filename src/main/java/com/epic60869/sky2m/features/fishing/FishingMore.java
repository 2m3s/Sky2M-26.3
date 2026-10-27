package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.Command;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

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
 * Fishing tools ported from SkyHanni (hannibal002/SkyHanni, LGPL-2.1): Totem of Corruption overlay and range
 * (TotemOfCorruption), Sea Creature Tracker (SeaCreatureTracker), Golden Fish Timer (GoldenFishTimer), a health list of
 * nearby sea creatures, Odger waypoint and total trophy fish count (OdgerWaypoint, OdgerTotalCaught), Barn Fishing
 * Timer (BarnFishingTimer) and Shark Fish Counter (SharkFishCounter). Display only: nothing here acts for you.
 */
public final class FishingMore {
    // Totem of Corruption
    private record Totem(Vec3 pos, int seconds, String owner, long readAt) {}
    private static final Pattern TOTEM_TIME = Pattern.compile("Remaining: (?:(\\d+)m )?(\\d+)s");
    private static final Pattern TOTEM_OWNER = Pattern.compile("Owner: (.+)");
    private static List<Totem> totems = List.of();
    private static final Map<Long, Boolean> warnedTotems = new LinkedHashMap<>();

    // Sea creature tracker
    private static final Map<String, Integer> counts = new LinkedHashMap<>();
    private static long activeMs;
    private static long lastCatch;
    /** This game session's catches (SkyHanni's "This Session" display mode), not saved. */
    private static final Map<String, Integer> sessionCounts = new LinkedHashMap<>();
    private static long sessionActiveMs;
    /** The category shown ("All" or one of SkyHanni's groups), like SkyHanni's currentCategory: kept until restart. */
    private static final String ALL = "All";
    private static String category = ALL;
    /** The tracker drawn over a menu, where its Display Mode and Category lines can be clicked. */
    private static int[] modeLine, categoryLine;
    private static boolean loaded;
    private static long lastSave;

    // Golden fish
    private static long goldenLastSeen;
    private static long goldenThrow;
    private static long goldenBase;
    private static long goldenDespawn;
    private static int goldenInteractions;
    private static boolean goldenWarned;
    private static boolean wasInLava;

    // Health list
    private static List<Component> healthLines = List.of();

    // Odger
    private static final Vec3 ODGER = new Vec3(-373, 207, -808);
    private static boolean trophyInInventory;
    private static final Pattern TROPHY_COUNT = Pattern.compile("^(Bronze|Silver|Gold|Diamond) \\u2714 \\((\\d+)\\)");

    // Barn timer
    private static final List<Long> ownCatches = new ArrayList<>();
    private static final Map<Integer, Long> standSeen = new LinkedHashMap<>();
    private static int nearbyMobs;
    private static long barnWarned;
    private static long oldestSeen;

    // Sharks
    private static final String[] SHARKS = {"Nurse Shark", "Blue Shark", "Tiger Shark", "Great White Shark"};
    private static final String[] SHARK_COLOURS = {"§a", "§9", "§5", "§6"};
    private static final int[] sharkCounts = new int[SHARKS.length];

    private static int ticks;

    private FishingMore() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.fishing == null ? null : c.fishing.more;
    }

    public static void init() {
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            totems = List.of();
            standSeen.clear();
            nearbyMobs = 0;
            goldenThrow = 0;
            goldenDespawn = 0;
            wasInLava = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FishingMore::tick);
        Sky2MChat.onChat(FishingMore::onChat);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
            dispatcher.register(ClientCommands.literal("s2fishingreset").executes(ctx -> {
                counts.clear();
                activeMs = 0;
                lastCatch = 0;
                sessionCounts.clear();
                sessionActiveMs = 0;
                save();
                Sky2MAlerts.chat(Component.literal("Reset the Sea Creature Tracker.").withStyle(ChatFormatting.GREEN));
                return Command.SINGLE_SUCCESS;
            })));
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> odgerTooltip(lines));
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> trackerInScreen(g));
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !trackerClick(event.x(), event.y(), event.button()));
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen).register(s -> modeLine = categoryLine = null);
        });

        Sky2MHuds.setting("fishing_totem", () -> config() != null && config().totemOverlay);
        Sky2MHuds.register("fishing_totem", "Totem of Corruption", () -> config() != null && config().totemOverlay && !totems.isEmpty(),
            FishingMore::totemLines,
            List.of(SbcItems.parseLegacy("§5§lTotem of Corruption"), SbcItems.parseLegacy("§7Remaining: §e2m 30s"), SbcItems.parseLegacy("§7Owner: §eSteve")),
            8, 200);
        Sky2MHuds.setting("fishing_sc_tracker", () -> config() != null && config().seaCreatureTracker);
        // Over a menu it's drawn by trackerInScreen instead, with clickable Display Mode and Category lines.
        Sky2MHuds.register("fishing_sc_tracker", "Sea Creature Tracker",
            () -> trackerShown() && !(Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>),
            () -> trackerLines(false),
            List.of(SbcItems.parseLegacy("§7Sea Creature Tracker:"), SbcItems.parseLegacy(" §7- §e120 §bSquid §755%"),
                SbcItems.parseLegacy(" §7- §e45 §c§lThunder §720%"), SbcItems.parseLegacy(" §7- §e165 §7Total Sea Creatures")),
            8, 230);
        Sky2MHuds.setting("fishing_golden_fish", () -> config() != null && config().goldenFishTimer);
        Sky2MHuds.register("fishing_golden_fish", "Golden Fish Timer",
            () -> config() != null && config().goldenFishTimer && goldenEnabled(),
            FishingMore::goldenLines,
            List.of(SbcItems.parseLegacy("§6§lGolden Fish Timer"), SbcItems.parseLegacy("§7Last Golden Fish: §b12m 3s"),
                SbcItems.parseLegacy("§7Can spawn in: §b4m 10s")),
            8, 290);
        Sky2MHuds.setting("fishing_sc_health", () -> config() != null && config().seaCreatureHealth);
        Sky2MHuds.register("fishing_sc_health", "Sea Creature Health",
            () -> config() != null && config().seaCreatureHealth && !healthLines.isEmpty(),
            () -> healthLines,
            List.of(SbcItems.parseLegacy("§c[Lv600] Thunder §a12M§f/§a35M§c❤"), SbcItems.parseLegacy("§c[Lv100] Lord Jawbus §a4.2M§f/§a20M§c❤")),
            8, 330);
        Sky2MHuds.setting("fishing_barn_timer", () -> config() != null && config().barnTimer);
        Sky2MHuds.register("fishing_barn_timer", "Barn Fishing Timer",
            () -> config() != null && config().barnTimer && barnActive() && nearbyMobs > 0,
            FishingMore::barnLines,
            List.of(SbcItems.parseLegacy("§a2m 12s §8(§a3§7/§a12 §bsea creatures§8)")),
            8, 360);
        Sky2MHuds.setting("fishing_sharks", () -> config() != null && config().sharkCounter);
        Sky2MHuds.register("fishing_sharks", "Shark Fish Counter",
            () -> config() != null && config().sharkCounter && sharkTotal() > 0 && holdingRod(),
            FishingMore::sharkLines,
            List.of(SbcItems.parseLegacy("§7Sharks caught: §e12 §7(§a5 §91 §52 §64§7)")),
            8, 390);

        Sky2MWorldRender.register(collector -> {
            Config c = config();
            if (c == null) return;
            if (c.totemRange && !totems.isEmpty()) {
                int colour = argb(c.totemColour, 0x80AA00FF);
                for (Totem t : totems) {
                    if (c.totemOwnOnly && !isOwn(t)) continue;
                    Vec3 centre = t.pos().add(0, 1, 0);
                    if (c.totemFilled) collector.submitSphere(centre, 16f, 24, 12, (colour & 0x00FFFFFF) | 0x33000000);
                    collector.submitOutlinedCircle(centre, 16f, 2f, 64, colour | 0xFF000000);
                }
            }
            if (c.odgerWaypoint && odgerActive()) {
                collector.submitFilledBoxWithBeaconBeam(BlockPos.containing(ODGER), new float[]{1f, 1f, 1f}, 0.5f, true);
                Minecraft mc = Minecraft.getInstance();
                double distance = mc.gameRenderer.mainCamera().position().distanceTo(ODGER);
                float scale = (float) Math.max(1, distance / 7);
                collector.submitText(Component.literal("Odger").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                    ODGER.add(0, 2.3 + 0.3 * scale, 0), scale, true);
            }
        });
    }

    private static boolean holdingRod() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.getMainHandItem().getItem() instanceof FishingRodItem;
    }

    private static boolean fishing() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.fishing != null;
    }

    /** A sea creature you caught (a double hook counts as two). Called by FishingFeatures. */
    static void onCatch(FishingData.SeaCreature creature, boolean doubleHook) {
        Config c = config();
        if (c == null) return;
        long now = System.currentTimeMillis();
        int amount = doubleHook && c.trackerCountDouble ? 2 : 1;
        if (c.seaCreatureTracker) {
            // Active time: the gap since the last catch counts unless it is a long break.
            if (lastCatch > 0) {
                long gap = Math.min(now - lastCatch, 5 * 60_000L);
                activeMs += gap;
                if (!sessionCounts.isEmpty()) sessionActiveMs += gap;
            }
            lastCatch = now;
            counts.merge(creature.name(), amount, Integer::sum);
            sessionCounts.merge(creature.name(), amount, Integer::sum);
            if (now - lastSave > 30_000) save();
        }
        ownCatches.add(now);
        for (int i = 0; i < SHARKS.length; i++) if (creature.name().equals(SHARKS[i])) sharkCounts[i] += doubleHook ? 2 : 1;
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(Sky2MChat.Message message) {
        Config c = config();
        if (c == null || !Sky2MLocation.onSkyblock()) return;
        String text = message.text();
        long now = System.currentTimeMillis();
        if (text.startsWith("You spot a Golden Fish surface from beneath the lava!")) {
            goldenLastSeen = now;
            goldenInteractions = 0;
            goldenDespawn = now + 60_000;
        } else if (text.startsWith("The Golden Fish escapes your hook but looks weakened.")) {
            goldenInteractions++;
            goldenDespawn = now + 60_000;
        } else if (text.startsWith("The Golden Fish is weak!")) {
            goldenDespawn = now + 60_000;
        } else if (text.startsWith("The Golden Fish swims back beneath the lava")
            || (text.startsWith("TROPHY FISH!") && text.contains("Golden Fish"))) {
            goldenBase = now;
            goldenDespawn = 0;
        } else if (text.contains("The festival has concluded!")) {
            java.util.Arrays.fill(sharkCounts, 0);
        }
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        Player player = mc.player;
        ClientLevel level = mc.level;
        Config c = config();
        if (c == null || player == null || level == null || !Compat.isOnSkyblock()) {
            if (!totems.isEmpty()) totems = List.of();
            if (!healthLines.isEmpty()) healthLines = List.of();
            return;
        }
        ticks++;
        // Golden fish: a cast into lava starts (and refreshes) the spawn timer.
        if (c.goldenFishTimer) tickGolden(player, c);
        if (ticks % 40 == 0 && c.totemOverlay | c.totemRange) tickTotems(player, level, c);
        else if (!(c.totemOverlay | c.totemRange) && !totems.isEmpty()) totems = List.of();
        if (ticks % 10 == 0) {
            if (c.seaCreatureHealth) tickHealth(player, level, c);
            else if (!healthLines.isEmpty()) healthLines = List.of();
        }
        if (ticks % 20 == 0) {
            if (c.barnTimer) tickBarn(player, level, c);
            if (c.odgerWaypoint) scanTrophies(player);
        }
    }

    // ------------------------------------------------------------------------------------------------ totem

    private static void tickTotems(Player player, ClientLevel level, Config c) {
        List<ArmorStand> stands = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ArmorStand stand && stand.hasCustomName() && stand.distanceToSqr(player) < 100 * 100) stands.add(stand);
        }
        List<Totem> found = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ArmorStand stand : stands) {
            if (!Sky2MLocation.strip(stand.getCustomName().getString()).equals("Totem of Corruption")) continue;
            int seconds = -1;
            String owner = null;
            for (ArmorStand other : stands) {
                if (other.distanceToSqr(stand) > 4) continue;
                String name = Sky2MLocation.strip(other.getCustomName().getString());
                Matcher m = TOTEM_TIME.matcher(name);
                if (m.matches()) seconds = (m.group(1) == null ? 0 : Integer.parseInt(m.group(1))) * 60 + Integer.parseInt(m.group(2));
                m = TOTEM_OWNER.matcher(name);
                if (m.matches()) owner = m.group(1).trim();
            }
            if (seconds < 0 || owner == null) continue;
            Totem totem = new Totem(stand.position(), seconds, owner, now);
            found.add(totem);
            if (c.totemWarnSeconds > 0 && seconds <= c.totemWarnSeconds && isOwn(totem)) {
                long key = Math.round(stand.getX()) * 31L + Math.round(stand.getZ()) + (long) Math.round(stand.getY()) * 977L;
                if (warnedTotems.putIfAbsent(key, true) == null) {
                    Sky2MAlerts.title(Component.literal("Totem of Corruption").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                        Component.literal("about to expire!").withStyle(ChatFormatting.YELLOW));
                    if (warnedTotems.size() > 50) warnedTotems.remove(warnedTotems.keySet().iterator().next());
                }
            }
        }
        totems = found;
    }

    private static boolean isOwn(Totem t) {
        return t.owner().equalsIgnoreCase(Minecraft.getInstance().getUser().getName());
    }

    private static List<Component> totemLines() {
        Config c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || mc.player == null || totems.isEmpty()) return List.of();
        Totem best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Totem t : totems) {
            if (c.totemOwnOnly && !isOwn(t)) continue;
            double d = t.pos().distanceTo(mc.player.position());
            if (d > c.totemDistance) continue;
            if (isOwn(t)) {
                best = t;
                break;
            }
            if (d < bestDistance) {
                bestDistance = d;
                best = t;
            }
        }
        if (best == null) return List.of();
        int remaining = Math.max(0, best.seconds() - (int) ((System.currentTimeMillis() - best.readAt()) / 1000));
        return List.of(SbcItems.parseLegacy("§5§lTotem of Corruption"),
            SbcItems.parseLegacy("§7Remaining: §e" + (remaining / 60) + "m " + (remaining % 60) + "s"),
            SbcItems.parseLegacy("§7Owner: §e" + best.owner()));
    }

    // ------------------------------------------------------------------------------------------------ tracker

    private static boolean trackerShown() {
        Config c = config();
        return c != null && c.seaCreatureTracker && !counts.isEmpty()
            && (holdingRod() || fishing() || System.currentTimeMillis() - lastCatch < 3 * 60_000L)
            && !inStorage(Minecraft.getInstance().gui.screen());
    }

    /** Hypixel's /storage menus (the overview, ender chest and backpack pages) and Sky2M's storage screens: no tracker there. */
    public static boolean inStorage(net.minecraft.client.gui.screens.Screen screen) {
        return screen != null && (com.epic60869.sky2m.features.misc.storage.StorageBackingHandle.fromScreen(screen) != null
            || screen instanceof com.epic60869.sky2m.features.misc.storage.StorageOverlayScreen
            || screen instanceof com.epic60869.sky2m.features.misc.storage.StorageOverviewScreen);
    }

    private static boolean session() {
        Config c = config();
        return c != null && c.trackerDisplay == TrackerDisplay.SESSION;
    }

    /** SkyHanni's getCurrentCategories: All, then each group you've caught something from, with how many kinds. */
    private static Map<String, Integer> categories(Map<String, Integer> shown) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put(ALL, shown.size());
        for (Map.Entry<String, java.util.Set<String>> e : FishingData.skyHanniVariants().entrySet()) {
            int kinds = 0;
            for (String name : e.getValue()) if (shown.containsKey(name)) kinds++;
            if (kinds > 0) out.put(e.getKey(), kinds);
        }
        return out;
    }

    /**
     * SkyHanni's Sea Creature Tracker: the shown display mode's catches in the current category. With a menu open
     * ({@code buttons}) its Category and Display Mode lines are added, which can be clicked (see trackerInScreen).
     */
    private static List<Component> trackerLines(boolean buttons) {
        List<Component> lines = new ArrayList<>();
        lines.add(SbcItems.parseLegacy("§7Sea Creature Tracker:"));
        Map<String, Integer> shown = session() ? sessionCounts : counts;
        Map<String, Integer> categories = categories(shown);
        if (!categories.containsKey(category)) category = ALL;
        if (buttons) lines.add(SbcItems.parseLegacy("§7Category: §e[" + category + " §7(" + categories.get(category) + ")§e]"));
        java.util.Set<String> inCategory = category.equals(ALL) ? null : FishingData.skyHanniVariants().get(category);
        Map<String, Integer> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : shown.entrySet()) if (inCategory == null || inCategory.contains(e.getKey())) filtered.put(e.getKey(), e.getValue());
        int total = 0;
        for (int v : filtered.values()) total += v;
        Config c = config();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(filtered.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : sorted) {
            String percent = c != null && c.trackerPercentage && total > 0
                ? String.format(Locale.US, " §7%.1f%%", e.getValue() * 100.0 / total) : "";
            // SkyHanni's colours: each sea creature in its group's chat colour, rare ones bold.
            lines.add(SbcItems.parseLegacy(" §7- §e" + String.format(Locale.US, "%,d", e.getValue()) + " "
                + FishingData.skyHanniDisplayName(e.getKey()) + "§r" + percent));
        }
        lines.add(SbcItems.parseLegacy(" §7- §e" + String.format(Locale.US, "%,d", total) + " §7Total Sea Creatures"));
        if (buttons) lines.add(SbcItems.parseLegacy("§7Display Mode: " + (session() ? "§7[Total] §a§l[This Session]" : "§a§l[Total] §7[This Session]")));
        return lines;
    }

    /** The tracker over a menu (inventory, chests), where SkyHanni shows its buttons, at its /s2 gui position. */
    private static void trackerInScreen(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        modeLine = categoryLine = null;
        if (!trackerShown()) return;
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines = trackerLines(true);
        int w = 0;
        for (Component line : lines) w = Math.max(w, mc.font.width(line));
        w += 2 * Sky2MHuds.PADDING;
        int h = lines.size() * Sky2MHuds.LINE_HEIGHT + 2 * Sky2MHuds.PADDING;
        Sky2MHuds.Placement p = Sky2MHuds.placement("fishing_sc_tracker");
        int x = Sky2MHuds.mapX(p.x, Math.round(w * p.scale)), y = Sky2MHuds.mapY(p.y, Math.round(h * p.scale));
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(p.scale, p.scale);
        if (p.background) g.fill(0, 0, w, h, 0x80000000);
        for (int i = 0; i < lines.size(); i++) g.text(mc.font, lines.get(i), Sky2MHuds.PADDING, Sky2MHuds.PADDING + i * Sky2MHuds.LINE_HEIGHT, 0xFFFFFFFF, true);
        g.pose().popMatrix();
        int lineH = Math.round(Sky2MHuds.LINE_HEIGHT * p.scale), top = y + Math.round(Sky2MHuds.PADDING * p.scale);
        // The Category line is the second, Display Mode the last.
        categoryLine = new int[]{x, top + lineH, Math.round(w * p.scale), lineH};
        modeLine = new int[]{x, top + (lines.size() - 1) * lineH, Math.round(w * p.scale), lineH};
    }

    /** Clicking Category (left: next, right: back) or Display Mode on the tracker over a menu. */
    private static boolean trackerClick(double mx, double my, int button) {
        Config c = config();
        if (c == null) return false;
        if (inside(categoryLine, mx, my)) {
            List<String> list = new ArrayList<>(categories(session() ? sessionCounts : counts).keySet());
            int i = Math.max(0, list.indexOf(category));
            category = list.get(Math.floorMod(i + (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1), list.size()));
            return true;
        }
        if (inside(modeLine, mx, my)) {
            c.trackerDisplay = session() ? TrackerDisplay.TOTAL : TrackerDisplay.SESSION;
            Sky2MConfig.saveCurrent(Sky2MConfig.current());
            return true;
        }
        return false;
    }

    private static boolean inside(int[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("fishing-tracker.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path file = file();
            if (!Files.exists(file)) return;
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            activeMs = json.has("activeMs") ? json.get("activeMs").getAsLong() : 0;
            if (json.has("counts")) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : json.getAsJsonObject("counts").entrySet()) counts.put(e.getKey(), e.getValue().getAsInt());
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the sea creature tracker: " + e.getMessage());
        }
    }

    private static void save() {
        lastSave = System.currentTimeMillis();
        try {
            JsonObject json = new JsonObject();
            json.addProperty("activeMs", activeMs);
            JsonObject map = new JsonObject();
            for (Map.Entry<String, Integer> e : counts.entrySet()) map.addProperty(e.getKey(), e.getValue());
            json.add("counts", map);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the sea creature tracker: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ golden fish

    private static boolean goldenEnabled() {
        return Sky2MLocation.areaIs("Crimson Isle") && (holdingRod() || fishing() || goldenDespawn > System.currentTimeMillis());
    }

    private static void tickGolden(Player player, Config c) {
        long now = System.currentTimeMillis();
        if (goldenThrow > 0 && now - goldenThrow > 3 * 60_000L) {
            goldenThrow = 0;
            goldenBase = 0;
            goldenWarned = false;
        }
        if (goldenDespawn > 0 && now > goldenDespawn) goldenDespawn = 0;
        if (!Sky2MLocation.areaIs("Crimson Isle")) return;
        var hook = player.fishing;
        boolean inLava = hook != null && hook.isInLava();
        if (inLava && !wasInLava) {
            goldenThrow = now;
            goldenWarned = false;
            if (goldenBase == 0) goldenBase = now;
        }
        wasInLava = inLava;
        if (c.goldenFishRodWarning && goldenThrow > 0 && !goldenWarned && now - goldenThrow >= 3 * 60_000L - c.goldenFishWarnSeconds * 1000L) {
            goldenWarned = true;
            Sky2MAlerts.title(Component.literal("Throw your rod!").withStyle(ChatFormatting.RED), null);
        }
    }

    private static List<Component> goldenLines() {
        Config c = config();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        lines.add(SbcItems.parseLegacy("§6§lGolden Fish Timer"));
        if (goldenDespawn > now) {
            lines.add(SbcItems.parseLegacy("§7Interactions: §b" + goldenInteractions + "/3" + (goldenInteractions >= 3 ? " §cPULL" : "")));
            lines.add(SbcItems.parseLegacy("§7Despawns in: §b" + dur(goldenDespawn - now)));
            return lines;
        }
        lines.add(SbcItems.parseLegacy("§7Last Golden Fish: " + (goldenLastSeen == 0 ? "§cNone this session" : "§b" + dur(now - goldenLastSeen))));
        if (goldenBase == 0) {
            lines.add(SbcItems.parseLegacy("§cCast your rod into lava to start!"));
            return lines;
        }
        long reduction = c == null ? 0 : (long) c.goldBaitLevel * 30_000L;
        long min = goldenBase + 8 * 60_000L - reduction;
        long max = goldenBase + 12 * 60_000L - reduction;
        if (now < min) lines.add(SbcItems.parseLegacy("§7Can spawn in: §b" + dur(min - now)));
        else {
            lines.add(SbcItems.parseLegacy("§7Can spawn since: §b" + dur(now - min)));
            lines.add(SbcItems.parseLegacy("§7Chance: §b" + Math.min(100, Math.round((now - min) * 100.0 / Math.max(1, max - min))) + "%"));
        }
        return lines;
    }

    // ------------------------------------------------------------------------------------------------ health list

    private static void tickHealth(Player player, ClientLevel level, Config c) {
        List<ArmorStand> found = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(40), Entity::hasCustomName)) {
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            if (!name.contains("❤") || !name.contains("/")) continue;
            if (seaCreatureIn(name) == null) continue;
            found.add(stand);
        }
        found.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
        List<Component> lines = new ArrayList<>();
        for (ArmorStand stand : found) {
            if (lines.size() >= c.healthLimit) break;
            lines.add(stand.getCustomName());
        }
        healthLines = lines;
    }

    private static FishingData.SeaCreature seaCreatureIn(String name) {
        for (FishingData.SeaCreature creature : FishingData.creatures()) {
            if (name.contains(creature.name())) return creature;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ barn timer

    private static boolean barnActive() {
        Config c = config();
        if (c == null) return false;
        if (c.barnAnywhere) return true;
        if (Sky2MLocation.areaIs("Crimson Isle") || Sky2MLocation.areaIs("Crystal Hollows") || Sky2MLocation.areaIs("Jerry's Workshop")) return true;
        Player player = Minecraft.getInstance().player;
        return Sky2MLocation.areaIs("Hub") && player != null && player.position().distanceTo(new Vec3(108, 89, -252)) < 50;
    }

    private static void tickBarn(Player player, ClientLevel level, Config c) {
        long now = System.currentTimeMillis();
        ownCatches.removeIf(t -> now - t > 360_000L);
        if (!barnActive()) {
            standSeen.clear();
            nearbyMobs = 0;
            return;
        }
        List<Integer> present = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(48), Entity::hasCustomName)) {
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            if (!name.contains("❤") || !name.contains("/") || seaCreatureIn(name) == null) continue;
            present.add(stand.getId());
            standSeen.putIfAbsent(stand.getId(), now);
        }
        standSeen.keySet().retainAll(present);
        nearbyMobs = present.size();
        oldestSeen = nearbyMobs == 0 ? 0 : standSeen.values().stream().min(Long::compare).orElse(0L);
        if (nearbyMobs == 0 || now - barnWarned < 5000) return;
        long age = now - oldestSeen;
        String reason = null;
        if (c.barnTimeAlert && age >= c.barnAlertSeconds * 1000L) reason = "Fishing Time Limit!";
        else if (c.barnWarnPersonal && Math.min(ownCatches.size(), nearbyMobs) >= 10) reason = "Reached Personal Cap!";
        else if (c.barnWarnGlobal && nearbyMobs >= 60) reason = "Reached Global Cap!";
        if (reason != null) {
            barnWarned = now;
            Sky2MAlerts.title(Component.literal(reason).withStyle(ChatFormatting.RED), null);
        }
    }

    private static List<Component> barnLines() {
        long age = oldestSeen == 0 ? 0 : System.currentTimeMillis() - oldestSeen;
        int own = Math.min(ownCatches.size(), nearbyMobs);
        Config c = config();
        String timeColour = c != null && c.barnTimeAlert && age >= c.barnAlertSeconds * 1000L ? "§c" : "§a";
        return List.of(SbcItems.parseLegacy(timeColour + dur(age) + " §8(" + (own >= 10 ? "§c" : "§a") + own + "§7/"
            + (nearbyMobs >= 60 ? "§c" : "§a") + nearbyMobs + " §bsea creatures§8)"));
    }

    // ------------------------------------------------------------------------------------------------ sharks

    private static int sharkTotal() {
        int total = 0;
        for (int v : sharkCounts) total += v;
        return total;
    }

    private static List<Component> sharkLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SHARKS.length; i++) sb.append(SHARK_COLOURS[i]).append(sharkCounts[i]).append(i < SHARKS.length - 1 ? " " : "");
        return List.of(SbcItems.parseLegacy("§7Sharks caught: §e" + sharkTotal() + " §7(" + sb + "§7)"));
    }

    // ------------------------------------------------------------------------------------------------ odger

    private static boolean odgerActive() {
        return Sky2MLocation.areaIs("Crimson Isle") && trophyInInventory && !holdingRod();
    }

    private static void scanTrophies(Player player) {
        boolean found = false;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && !found; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                if (Sky2MLocation.strip(line.getString()).endsWith("TROPHY FISH")) {
                    found = true;
                    break;
                }
            }
        }
        trophyInInventory = found;
    }

    /** In Odger's Trophy Fishing menu: the total you caught of a trophy fish, under its Bronze line. */
    private static void odgerTooltip(List<Component> lines) {
        Config c = config();
        if (c == null || !c.odgerTotal || !Sky2MLocation.onSkyblock()) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (screen == null || !Sky2MLocation.strip(screen.getTitle().getString()).equals("Trophy Fishing")) return;
        int total = 0;
        int bronzeLine = -1;
        int best = 0;
        boolean discovered = false;
        for (int i = 0; i < lines.size(); i++) {
            String text = Sky2MLocation.strip(lines.get(i).getString()).trim();
            if (text.equals("Discovered")) discovered = true;
            Matcher m = TROPHY_COUNT.matcher(text);
            if (m.find()) {
                total += Integer.parseInt(m.group(2));
                if (m.group(1).equals("Bronze")) bronzeLine = i;
            }
        }
        if (!discovered || bronzeLine < 0 || lines.stream().anyMatch(l -> l.getString().contains("Total:"))) return;
        lines.add(bronzeLine + 1, Component.empty());
        lines.add(bronzeLine + 2, Component.literal("Total: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d", total)).withStyle(ChatFormatting.GOLD)));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static int argb(String value, int fallback) {
        try {
            return ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String dur(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /** SkyHanni's tracker display modes. */
    public enum TrackerDisplay {
        TOTAL("Total"), SESSION("This Session");

        private final String label;

        TrackerDisplay(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Totem of Corruption Overlay", desc = "HUD with the time left and owner of the Totem of Corruption near you (SkyHanni). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean totemOverlay = false;

        @Expose
        @ConfigOption(name = "Totem Range", desc = "Draws the 16 block range of Totems of Corruption in the world.")
        @ConfigEditorBoolean
        public boolean totemRange = false;

        @Expose
        @ConfigOption(name = "Totem Range Filled", desc = "Also fills the range with a transparent sphere, not only the ring.")
        @ConfigEditorBoolean
        public boolean totemFilled = false;

        @Expose
        @ConfigOption(name = "Totem Colour", desc = "Colour of the Totem of Corruption range.")
        @ConfigEditorColour
        public String totemColour = "0:128:170:0:255";

        @Expose
        @ConfigOption(name = "Own Totem Only", desc = "Only show your own Totems of Corruption.")
        @ConfigEditorBoolean
        public boolean totemOwnOnly = false;

        @Expose
        @ConfigOption(name = "Totem Distance", desc = "Only show the overlay for Totems closer than this many blocks.")
        @ConfigEditorSlider(minValue = 5, maxValue = 100, minStep = 1)
        public float totemDistance = 30;

        @Expose
        @ConfigOption(name = "Warn When About to Expire", desc = "A title when your own Totem of Corruption has this many seconds left. 0 turns it off.")
        @ConfigEditorSlider(minValue = 0, maxValue = 120, minStep = 5)
        public float totemWarnSeconds = 0;

        @Expose
        @ConfigOption(name = "Sea Creature Tracker", desc = "HUD counting the sea creatures you catch, per type, with how many per hour (SkyHanni). Shown while you fish. Reset with /s2fishingreset. Needs Sea Creature Chat in SkyBlock settings.")
        @ConfigEditorBoolean
        public boolean seaCreatureTracker = true;

        @Expose
        @ConfigOption(name = "Tracker: Count Double Hook", desc = "Count a double hook catch as two sea creatures.")
        @ConfigEditorBoolean
        public boolean trackerCountDouble = true;

        @Expose
        @ConfigOption(name = "Tracker: Percentages", desc = "Show the share of each sea creature in the tracker.")
        @ConfigEditorBoolean
        public boolean trackerPercentage = true;

        @Expose
        @ConfigOption(name = "Tracker: Display Mode", desc = "Total: every sea creature since the last reset. This Session: only since you started the game. Also switched by clicking \"Display Mode\" on the tracker while your inventory or a menu is open, like SkyHanni.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
        public TrackerDisplay trackerDisplay = TrackerDisplay.TOTAL;

        @Expose
        @ConfigOption(name = "Golden Fish Timer", desc = "Crimson Isle: when the Golden Fish can spawn after you cast into lava, its interactions and despawn timer (SkyHanni). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean goldenFishTimer = false;

        @Expose
        @ConfigOption(name = "Gold Bait Shard Level", desc = "Your Goldfin attribute level: each level lowers the Golden Fish spawn time by 30 seconds.")
        @ConfigEditorSlider(minValue = 0, maxValue = 10, minStep = 1)
        public float goldBaitLevel = 0;

        @Expose
        @ConfigOption(name = "Golden Fish Rod Warning", desc = "A title when you have not cast your rod into lava for almost 3 minutes, which pauses the Golden Fish timer.")
        @ConfigEditorBoolean
        public boolean goldenFishRodWarning = false;

        @Expose
        @ConfigOption(name = "Rod Warning Seconds", desc = "How many seconds before the 3 minutes run out the rod warning shows.")
        @ConfigEditorSlider(minValue = 5, maxValue = 60, minStep = 5)
        public float goldenFishWarnSeconds = 20;

        @Expose
        @ConfigOption(name = "Sea Creature Health", desc = "HUD listing the health of the sea creatures near you, nearest first (SkyHanni). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean seaCreatureHealth = false;

        @Expose
        @ConfigOption(name = "Health List Limit", desc = "The most sea creatures to list.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        public float healthLimit = 5;

        @Expose
        @ConfigOption(name = "Barn Fishing Timer", desc = "Time and amount of your own/all sea creatures nearby while barn fishing: Crimson Isle, Crystal Hollows, Jerry's Workshop and the Hub barn (SkyHanni).")
        @ConfigEditorBoolean
        public boolean barnTimer = false;

        @Expose
        @ConfigOption(name = "Barn Timer Anywhere", desc = "Show the Barn Fishing Timer wherever you fish.")
        @ConfigEditorBoolean
        public boolean barnAnywhere = false;

        @Expose
        @ConfigOption(name = "Barn Time Alert", desc = "A title when the oldest sea creature reaches the alert time. Sea creatures despawn after 6 minutes.")
        @ConfigEditorBoolean
        public boolean barnTimeAlert = true;

        @Expose
        @ConfigOption(name = "Barn Alert Seconds", desc = "The age in seconds that triggers the time alert.")
        @ConfigEditorSlider(minValue = 240, maxValue = 360, minStep = 1)
        public float barnAlertSeconds = 300;

        @Expose
        @ConfigOption(name = "Warn Personal Cap", desc = "A title when you reach your personal sea creature cap (10).")
        @ConfigEditorBoolean
        public boolean barnWarnPersonal = true;

        @Expose
        @ConfigOption(name = "Warn Global Cap", desc = "A title when you reach the global sea creature cap (60, only counts mobs in range).")
        @ConfigEditorBoolean
        public boolean barnWarnGlobal = true;

        @Expose
        @ConfigOption(name = "Shark Fish Counter", desc = "HUD counting the sharks you catch (Nurse, Blue, Tiger, Great White), reset when the Fishing Festival ends (SkyHanni).")
        @ConfigEditorBoolean
        public boolean sharkCounter = false;

        @Expose
        @ConfigOption(name = "Odger Waypoint", desc = "Crimson Isle: a waypoint to Odger while you carry trophy fish and don't hold a fishing rod (SkyHanni).")
        @ConfigEditorBoolean
        public boolean odgerWaypoint = false;

        @Expose
        @ConfigOption(name = "Odger Trophy Total", desc = "In Odger's Trophy Fishing menu, adds the total number of each trophy fish you caught to its tooltip (SkyHanni).")
        @ConfigEditorBoolean
        public boolean odgerTotal = false;
    }
}
