package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diana achievements, ported from SBO's AchievementManager and Achievement (https://github.com/SkyblockOverhaul/SBO,
 * Apache-2.0): the same achievements, rarities and unlock rules, read from Sky2M's Diana trackers. Achievements that
 * need SBO's own server (mob kill totals, the kills leaderboard, Enderman Slayer 9) and "Download SBO" are left out.
 *
 * <p>An achievement with a previous one unlocks that first. With Repeat Each Event on, the repeatable ones can be
 * earned again every Diana season, like SBO's debug option.
 */
public final class DianaAchievements {
    public enum Rarity {
        COMMON("Common", ChatFormatting.WHITE), UNCOMMON("Uncommon", ChatFormatting.GREEN), RARE("Rare", ChatFormatting.BLUE),
        EPIC("Epic", ChatFormatting.DARK_PURPLE), LEGENDARY("Legendary", ChatFormatting.GOLD), MYTHIC("Mythic", ChatFormatting.LIGHT_PURPLE),
        DIVINE("Divine", ChatFormatting.AQUA), CELESTIAL("Celestial", ChatFormatting.DARK_BLUE), IMPOSSIBLE("Impossible", ChatFormatting.DARK_RED);

        public final String label;
        public final ChatFormatting colour;

        Rarity(String label, ChatFormatting colour) {
            this.label = label;
            this.colour = colour;
        }

        boolean special() {
            return this == DIVINE || this == CELESTIAL || this == IMPOSSIBLE;
        }
    }

    public record Achievement(int id, String category, String name, String description, Rarity rarity, Integer previous,
                              boolean hidden, boolean repeatable) {}

    static final class Saved {
        /** Achievement id -> times unlocked. */
        @Expose Map<Integer, Integer> total = new LinkedHashMap<>();
        /** Achievements earned this season (Repeat Each Event). */
        @Expose Map<Integer, Boolean> currentEvent = new LinkedHashMap<>();
        @Expose long lastEventYear = -1;
    }

    private static final Map<Integer, Achievement> ALL = new LinkedHashMap<>();
    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
    private static final Pattern COA_MAGIC_FIND = Pattern.compile("\\+([0-9]*\\.?[0-9]+) Magic Find");
    private static final Pattern KILLS = Pattern.compile("^Kills: ([\\d,]+)");
    private static Path file;
    private static Saved saved = new Saved();
    private static final Deque<Integer> queue = new ArrayDeque<>();
    private static int queueCooldown;
    /** A menu that was just opened, checked once its items have arrived. */
    private static Object menuScreen;
    private static int menuTicks;
    private static int areaChangeTicks = -1;

    private DianaAchievements() {}

    private static FeatureConfigs.DianaAchievements config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mayors.diana.achievements;
    }

    private static boolean enabled() {
        FeatureConfigs.DianaAchievements c = config();
        return c != null && c.enabled;
    }

    private static boolean repeatEachEvent() {
        FeatureConfigs.DianaAchievements c = config();
        return c != null && c.repeatEachEvent;
    }

    public static Collection<Achievement> all() {
        return ALL.values();
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("diana-achievements.json");
        load();
        addAll();
        ClientTickEvents.END_CLIENT_TICK.register(DianaAchievements::tick);
        Sky2MLocation.onAreaChange(area -> areaChangeTicks = 40);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("achievements")
                    .executes(c -> {
                        open();
                        return 1;
                    })
                    .then(ClientCommands.literal("backtrack").executes(c -> {
                        Minecraft.getInstance().execute(DianaAchievements::backtrack);
                        return 1;
                    }))
                    .then(ClientCommands.literal("lock").then(ClientCommands.argument("confirm", StringArgumentType.word()).executes(c -> {
                        Minecraft.getInstance().execute(() -> lockAll(StringArgumentType.getString(c, "confirm")));
                        return 1;
                    })))));
            }
        });
    }

    public static void open() {
        Compat.queueOpenScreen(new DianaAchievementsScreen());
    }

    private static void lockAll(String confirm) {
        if (!confirm.equals("CONFIRM")) {
            Sky2MAlerts.chat(Component.literal("This resets all your Diana achievements. Type /s2 achievements lock CONFIRM to do it.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        saved = new Saved();
        save();
        Sky2MAlerts.chat(Component.literal("Diana achievements locked.").withStyle(ChatFormatting.YELLOW));
    }

    /** SBO's backtrack: checks every saved season (and your since counters) for achievements you've already earned. */
    private static void backtrack() {
        Sky2MAlerts.chat(Component.literal("Backtracking Diana achievements...").withStyle(ChatFormatting.YELLOW));
        for (DianaTracker.Data season : DianaTracker.allSeasons()) checkSeason(season, 0, 0, 0);
        checkSince();
    }

    // ------------------------------------------------------------------------------------------------ state

    private static void checkYearReset() {
        long year = DianaProfitTracker.electionYear();
        if (saved.lastEventYear != year) {
            saved.currentEvent.clear();
            saved.lastEventYear = year;
            save();
        }
    }

    public static boolean isUnlocked(Achievement a) {
        checkYearReset();
        if (a.repeatable() && repeatEachEvent()) return saved.currentEvent.getOrDefault(a.id(), false);
        return saved.total.getOrDefault(a.id(), 0) > 0;
    }

    public static boolean everUnlocked(Achievement a) {
        return saved.total.getOrDefault(a.id(), 0) > 0;
    }

    private static boolean canBeUnlocked(Achievement a) {
        return !isUnlocked(a);
    }

    public static void unlock(int id) {
        if (!enabled() || !Sky2MLocation.onSkyblock()) return;
        Achievement a = ALL.get(id);
        if (a != null && canBeUnlocked(a) && !queue.contains(id)) queue.add(id);
    }

    private static void tick(Minecraft mc) {
        if (queueCooldown > 0) queueCooldown--;
        // Popups wait for the Inquisitor Gamble so they don't give a Chimera away.
        while (queueCooldown == 0 && !queue.isEmpty() && !InquisitorGamble.holding()) {
            int id = queue.poll();
            Achievement a = ALL.get(id);
            if (a == null || !canBeUnlocked(a)) continue;
            if (a.previous() != null && ALL.containsKey(a.previous()) && !isUnlocked(ALL.get(a.previous()))) {
                // The previous one first, then this one.
                queue.addFirst(id);
                queue.addFirst(a.previous());
                continue;
            }
            doUnlock(a);
            queueCooldown = 20;
        }
        if (!enabled() || mc.player == null) return;
        checkMenus(mc);
        if (areaChangeTicks >= 0 && areaChangeTicks-- == 0) checkDaedalusAxe(mc);
    }

    private static void doUnlock(Achievement a) {
        checkYearReset();
        saved.total.merge(a.id(), 1, Integer::sum);
        if (a.repeatable() && repeatEachEvent()) saved.currentEvent.put(a.id(), true);
        save();

        Minecraft mc = Minecraft.getInstance();
        Component name = Component.literal(a.name()).withStyle(a.rarity().colour);
        Component title = a.rarity().special()
            ? Component.literal("✦ ").withStyle(a.rarity().colour).append(name).append(Component.literal(" ✦").withStyle(a.rarity().colour))
            : name;
        Component hover = Component.literal(a.hidden() ? "[Secret Achievement] " : "").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(a.description()).withStyle(ChatFormatting.GREEN))
            .append(Component.literal("\n" + a.rarity().label).withStyle(a.rarity().colour));
        mc.execute(() -> {
            mc.gui.hud.setTimes(0, 50, 20);
            mc.gui.hud.setTitle(title);
            mc.gui.hud.setSubtitle(Component.literal("Achievement Unlocked!").withStyle(ChatFormatting.GREEN));
        });
        Sky2MAlerts.chat(Component.literal("Achievement Unlocked ").withStyle(ChatFormatting.GREEN)
            .append(Component.literal(">> ").withStyle(ChatFormatting.GRAY))
            .append(title.copy().withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)))));
        Sky2MAlerts.play(a.rarity().special() ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP, 1.0f);
    }

    // ------------------------------------------------------------------------------------------------ checks

    /** After anything the tracker counts: SBO's trackAchievementsItem and trackSince for this season. */
    static void check() {
        if (!enabled()) return;
        DianaProfitTracker.Totals totals = DianaProfitTracker.totals(DianaProfitTracker.Period.SEASON);
        checkSeason(DianaTracker.season(), totals.activeMs(), totals.burrows(), totals.profit());
        checkSince();
        checkCrownOfAvarice();
    }

    private static void checkSeason(DianaTracker.Data d, long time, long burrows, double profit) {
        long chimera = d.drop(DianaTracker.Drop.CHIMERA) + d.dropLs(DianaTracker.Drop.CHIMERA);
        long chimeraLs = d.dropLs(DianaTracker.Drop.CHIMERA);
        long wool = d.drop(DianaTracker.Drop.SHIMMERING_WOOL) + d.dropLs(DianaTracker.Drop.SHIMMERING_WOOL);
        long food = d.drop(DianaTracker.Drop.BRAIN_FOOD) + d.dropLs(DianaTracker.Drop.BRAIN_FOOD);
        long cores = d.drop(DianaTracker.Drop.MANTI_CORE) + d.dropLs(DianaTracker.Drop.MANTI_CORE);
        long stingers = d.drop(DianaTracker.Drop.FATEFUL_STINGER) + d.dropLs(DianaTracker.Drop.FATEFUL_STINGER);
        long sticks = d.drop(DianaTracker.Drop.DAEDALUS_STICK);
        long relics = d.drop(DianaTracker.Drop.MINOS_RELIC);
        long dyes = d.drop(DianaTracker.Drop.MYTHOLOGICAL_DYE);
        long crowns = d.drop(DianaTracker.Drop.CROWN_OF_GREED);
        long braided = d.drop(DianaTracker.Drop.BRAIDED_GRIFFIN_FEATHER);
        long fish = d.drop(DianaTracker.Drop.MYTH_THE_FISH);

        if (List.of(chimera, wool, food, cores, sticks, relics, dyes, stingers, crowns, braided, fish).stream().allMatch(n -> n >= 1)) unlock(128);

        if (burrows >= 25000) unlock(22);
        else if (burrows >= 20000) unlock(21);
        else if (burrows >= 15000) unlock(20);
        else if (burrows >= 10000) unlock(19);
        else if (burrows >= 5000) unlock(18);

        long hour = 3_600_000L, day = 86_400_000L;
        if (time >= day * 3) unlock(27);
        else if (time >= day * 2) unlock(26);
        else if (time >= day) unlock(25);
        else if (time >= hour * 10) unlock(24);
        else if (time >= hour) unlock(23);

        if (relics >= 1) unlock(16);
        if (sticks >= 7) unlock(8);
        else if (sticks >= 1) unlock(14);

        if (chimera >= 128) unlock(127);
        else if (chimera >= 64) unlock(125);
        else if (chimera >= 32) unlock(11);
        else if (chimera >= 16) unlock(9);
        else if (chimera >= 1) unlock(12);

        if (chimeraLs >= 32) unlock(126);
        else if (chimeraLs >= 16) unlock(10);
        else if (chimeraLs >= 1) unlock(13);

        if (time >= 5 * hour) {
            long perHour = (long) (burrows / (time / (double) hour));
            if (perHour >= 600) unlock(72);
            else if (perHour >= 500) unlock(71);
            else if (perHour >= 450) unlock(70);
            else if (perHour >= 400) unlock(69);
            else if (perHour >= 350) unlock(68);
        }

        if (sticks >= 1 && chimera >= 2) unlock(73);
        if (sticks >= 1 && relics >= 2) unlock(74);
        if (sticks >= 1 && cores >= 2) unlock(78);

        if (wool >= 3) unlock(79);
        else if (wool >= 1) unlock(83);
        if (d.dropLs(DianaTracker.Drop.SHIMMERING_WOOL) >= 1) unlock(111);
        if (crowns >= 10) unlock(131);

        if (d.dropLs(DianaTracker.Drop.MANTI_CORE) >= 1) unlock(114);
        if (cores >= 1) unlock(113);
        if (cores >= 3) unlock(129);
        if (stingers >= 1) unlock(130);
        if (food >= 5) unlock(85);
        if (dyes >= 1) unlock(86);

        if (d.mob(DianaTracker.Mob.KING_MINOS) >= 1) unlock(116);
        if (d.mob(DianaTracker.Mob.SPHINX) >= 1) unlock(115);
        if (d.mob(DianaTracker.Mob.MANTICORE) >= 1) unlock(112);

        if (profit >= 1_000_000_000d) unlock(84);
    }

    private static void checkSince() {
        DianaTracker.Data all = DianaTracker.data(DianaProfitTracker.Period.ALL_TIME);
        DianaTracker.Streaks s = DianaTracker.streaks();
        long sinceInq = all.since.getOrDefault(DianaTracker.Mob.MINOS_INQUISITOR.name(), 0L);
        if (sinceInq >= 1000) unlock(33);
        else if (sinceInq >= 500) unlock(32);
        else if (sinceInq >= 250) unlock(31);

        long inqsSinceChim = s.since(DianaTracker.Drop.CHIMERA.name());
        if (inqsSinceChim >= 100) unlock(37);
        else if (inqsSinceChim >= 60) unlock(36);
        else if (inqsSinceChim >= 30) unlock(35);
        else if (inqsSinceChim >= 15) unlock(34);

        if (s.since(DianaTracker.Drop.DAEDALUS_STICK.name()) >= 200) unlock(29);
        long champsSinceRelic = s.since(DianaTracker.Drop.MINOS_RELIC.name());
        if (champsSinceRelic >= 3000) unlock(65);
        else if (champsSinceRelic >= 1000) unlock(30);
    }

    static void onMagicFind(int magicFind, boolean chimera) {
        if (magicFind <= 0) return;
        if (magicFind >= 600) unlock(42);
        else if (magicFind >= 500) unlock(41);
        else if (magicFind >= 400) unlock(40);
        else if (magicFind >= 300) unlock(39);
        if (chimera) {
            if (magicFind < 100) unlock(43);
            else if (magicFind < 200) unlock(44);
        }
    }

    /** A back-to-back mob or drop ({@code again}: the third or more in a row). */
    static void onBackToBack(String key, boolean again) {
        switch (key) {
            case "MINOS_INQUISITOR" -> unlock(again ? 7 : 6);
            case "KING_MINOS" -> unlock(again ? 117 : 87);
            case "MANTICORE" -> unlock(again ? 110 : 109);
            case "SPHINX" -> unlock(again ? 108 : 107);
            case "CHIMERA" -> {
                unlock(again ? 2 : 1);
                if (DianaTracker.streaks().b2b.contains("MINOS_INQUISITOR")) unlock(75);
            }
            case "CHIMERA_LS" -> unlock(again ? 67 : 66);
            case "DAEDALUS_STICK" -> unlock(again ? 4 : 3);
            case "MINOS_RELIC" -> unlock(5);
            case "SHIMMERING_WOOL" -> unlock(81);
            case "SHIMMERING_WOOL_LS" -> unlock(82);
            case "MANTI_CORE" -> unlock(94);
            case "MANTI_CORE_LS" -> unlock(95);
            case "BRAIN_FOOD" -> unlock(again ? 98 : 97);
            case "BRAIN_FOOD_LS" -> unlock(again ? 100 : 99);
            default -> {}
        }
    }

    /** SBO's trackCOA: wearing a Crown of Avarice, and how much Magic Find it's grown to. */
    private static void checkCrownOfAvarice() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack helmet = mc.player.getItemBySlot(EquipmentSlot.HEAD);
        if (!helmet.getHoverName().getString().contains("Crown of Avarice")) return;
        unlock(121);
        ItemLore lore = helmet.get(DataComponents.LORE);
        if (lore == null) return;
        for (Component line : lore.lines()) {
            Matcher m = COA_MAGIC_FIND.matcher(Sky2MLocation.strip(line.getString()));
            if (!m.find()) continue;
            double mf = Double.parseDouble(m.group(1));
            if (mf >= 25.0) unlock(124);
            else if (mf >= 22.5) unlock(123);
            else if (mf >= 20.0) unlock(122);
            return;
        }
    }

    /** SBO's Daedalus Axe checks, a moment after you change area: Chimera V, Looting V and Divine Gift III. */
    private static void checkDaedalusAxe(Minecraft mc) {
        if (!Sky2MLocation.onSkyblock()) return;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains("daedalus")) continue;
            CompoundTag enchants = Compat.getCustomData(stack).getCompoundOrEmpty("enchantments");
            if (enchants.isEmpty()) continue;
            boolean chim = enchants.getIntOr("ultimate_chimera", 0) == 5;
            boolean looting = enchants.getIntOr("looting", 0) == 5;
            boolean gift = enchants.getIntOr("divine_gift", 0) == 3;
            if (chim) unlock(52);
            if (looting) unlock(53);
            if (gift) unlock(54);
            if (chim && looting && gift) unlock(55);
            return;
        }
    }

    /** The Mythological Creatures bestiary and the Mythological Ritual (carnival perks), once their items are in. */
    private static void checkMenus(Minecraft mc) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            menuScreen = null;
            return;
        }
        if (screen != menuScreen) {
            menuScreen = screen;
            menuTicks = 0;
        }
        if (++menuTicks != 10) return;
        String title = screen.getTitle().getString();
        List<Slot> slots = screen.getMenu().slots;
        if (title.contains("Mythological Creatur") && slots.size() > 23) {
            // Slot, achievement, kills to max it.
            int[][] bestiary = {{10, 102, 3000}, {11, 50, 3000}, {12, 103, 3000}, {13, 106, 100}, {14, 105, 100}, {15, 47, 1000},
                {16, 48, 3000}, {19, 45, 500}, {20, 46, 1000}, {21, 49, 3000}, {22, 104, 500}, {23, 101, 3000}};
            boolean allMaxed = true;
            for (int[] b : bestiary) {
                if (kills(slots.get(b[0]).getItem()) >= b[2]) unlock(b[1]);
                else allMaxed = false;
            }
            if (allMaxed) unlock(51);
        } else if (title.contains("Mythological Ritual") && slots.size() > 15) {
            String[] perks = {"Storied Stinger V", "Deadly Greed V", "Diana's Favor III", "Elusive Hunter II"};
            int[] perkSlots = {11, 12, 14, 15};
            for (int i = 0; i < perks.length; i++) {
                ItemStack stack = slots.get(perkSlots[i]).getItem();
                if (!Sky2MLocation.strip(stack.getHoverName().getString()).contains(perks[i]) || !loreContains(stack, "UNLOCKED")) return;
            }
            unlock(120);
        }
    }

    private static int kills(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return 0;
        for (Component line : lore.lines()) {
            Matcher m = KILLS.matcher(Sky2MLocation.strip(line.getString()).trim());
            if (m.find()) return Integer.parseInt(m.group(1).replace(",", ""));
        }
        return 0;
    }

    private static boolean loreContains(ItemStack stack, String text) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) if (Sky2MLocation.strip(line.getString()).contains(text)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ the list

    private static void add(int id, String category, String name, String description, Rarity rarity) {
        add(id, category, name, description, rarity, null, false, true);
    }

    private static void add(int id, String category, String name, String description, Rarity rarity, Integer previous) {
        add(id, category, name, description, rarity, previous, false, true);
    }

    private static void add(int id, String category, String name, String description, Rarity rarity, Integer previous, boolean hidden, boolean repeatable) {
        if (ALL.containsKey(id)) throw new IllegalStateException("Duplicate Diana achievement id " + id);
        ALL.put(id, new Achievement(id, category, name, description, rarity, previous, hidden, repeatable));
    }

    /** SBO's achievements (ids kept, so they line up with SBO's). */
    private static void addAll() {
        if (!ALL.isEmpty()) return;
        String c = "Inquisitor";
        add(1, c, "Back-to-Back Chimera", "Get 2 Chimera in a row", Rarity.MYTHIC);
        add(2, c, "b2b2b Chimera", "Get 3 Chimera in a row", Rarity.DIVINE);
        add(66, c, "Back-to-Back LS Chimera", "Get 2 Lootshare Chimera in a row", Rarity.DIVINE);
        add(67, c, "b2b2b LS Chimera", "Get 3 Lootshare Chimera in a row", Rarity.IMPOSSIBLE, 66);
        add(6, c, "Inquisitor Double Trouble", "Get 2 Inquisitors in a row", Rarity.EPIC);
        add(7, c, "b2b2b Inquisitor", "Get 3 Inquisitors in a row", Rarity.DIVINE);
        add(75, c, "Back-to-Back Goat", "Get b2b chimera from b2b inquisitor", Rarity.IMPOSSIBLE, null, true, true);
        add(12, c, "First Chimera", "Get your first Chimera", Rarity.EPIC, null, false, false);
        add(9, c, "Chimera V", "Get 16 chimera in one event", Rarity.MYTHIC, 12);
        add(11, c, "Chimera VI", "Get 32 Chimera in one event", Rarity.DIVINE, 9);
        add(125, c, "Chimera VII", "Get 64 Chimera in one event", Rarity.CELESTIAL, 11);
        add(127, c, "Chimera VIII", "Get 128 Chimera in one event", Rarity.IMPOSSIBLE, 125);
        add(13, c, "First lootshare Chimera", "Lootshare your first Chimera", Rarity.LEGENDARY);
        add(10, c, "Tf?", "Get 16 lootshare Chimera in one event", Rarity.DIVINE, 13);
        add(126, c, "Wtf?", "Get 32 lootshare Chimera in one event", Rarity.CELESTIAL, 10);

        c = "Minotaur";
        add(14, c, "First Stick", "Get your first Stick", Rarity.UNCOMMON, null, false, false);
        add(8, c, "Can i make a ladder now?", "Get 7 Sticks in one event", Rarity.EPIC, 14);
        add(3, c, "Back-to-Back Stick", "Get 2 Sticks in a row", Rarity.DIVINE);
        add(15, c, "1/6250", "Lootshare a Stick (1/6250)", Rarity.IMPOSSIBLE, null, true, true);

        c = "Champion";
        add(16, c, "First Relic", "Get your first Relic", Rarity.EPIC, null, false, false);
        add(17, c, "1/25000", "Lootshare a Relic (1/25000)", Rarity.IMPOSSIBLE, null, true, true);
        add(5, c, "Back-to-Back Relic", "Get 2 Relics in a row", Rarity.IMPOSSIBLE);

        c = "Sphinx";
        add(115, c, "What do i click?", "Get your first Sphinx", Rarity.UNCOMMON, null, false, false);
        add(107, c, "Back-to-Back Sphinx", "Get 2 Sphinx in a row", Rarity.EPIC);
        add(108, c, "b2b2b Sphinx", "Get 3 Sphinx in a row", Rarity.MYTHIC, 107);
        add(97, c, "Back-to-Back Brain Food", "Get 2 Brain Food in a row", Rarity.LEGENDARY);
        add(98, c, "b2b2b Brain Food", "Get 3 Brain Food in a row", Rarity.IMPOSSIBLE);
        add(99, c, "b2b ls Brain Food", "Lootshare 2 Brain Food in a row", Rarity.MYTHIC);
        add(100, c, "b2b2b ls Brain Food", "Lootshare 3 Brain Food in a row", Rarity.CELESTIAL);
        add(85, c, "Might get some braincells back", "Get 5 brain food in one event", Rarity.LEGENDARY);

        c = "Manticore";
        add(112, c, "Here, Kitty Kitty… OH NO.", "Spawn your first Manticore", Rarity.EPIC, null, false, false);
        add(109, c, "b2b Manticore", "Spawn 2 Manticores in a row", Rarity.DIVINE);
        add(110, c, "b2b2b Manticore", "Spawn 3 Manticores in a row", Rarity.CELESTIAL);
        add(113, c, "First core", "Drop your first Manti-core", Rarity.MYTHIC);
        add(94, c, "Back-to-Back core", "Drop 2 Manti-cores in a row", Rarity.CELESTIAL);
        add(129, c, "Oh baby a triple!", "Get 3 manti-cores in 1 event", Rarity.CELESTIAL);
        add(114, c, "First Lootshare core", "Lootshare your first Manti-core", Rarity.LEGENDARY);
        add(95, c, "Back-to-Back Lootshare core", "Lootshare 2 Manti-cores in a row", Rarity.IMPOSSIBLE);
        add(130, c, "I can turn into a bee now", "Drop 1 Stinger", Rarity.EPIC);

        c = "King Minos";
        add(116, c, "Why do i hear boss music?", "Get your first King Minos", Rarity.LEGENDARY, null, false, false);
        add(87, c, "b2b King Minos", "Get 2 King Minos in a row", Rarity.CELESTIAL);
        add(117, c, "b2b2b King Minos", "Get 3 King Minos in a row", Rarity.IMPOSSIBLE);
        add(83, c, "First Wool", "Get your first Wool", Rarity.MYTHIC);
        add(81, c, "Back-to-Back Wool", "Get 2 Wools in a row", Rarity.IMPOSSIBLE);
        add(111, c, "First Lootshare Wool", "Lootshare your first Wool", Rarity.DIVINE);
        add(82, c, "Back-to-Back Lootshare Wool", "Lootshare 2 Wools in a row", Rarity.IMPOSSIBLE);
        add(131, c, "Very greedy I see", "Get 10 Crown of Greeds in 1 event", Rarity.LEGENDARY);

        c = "Burrows";
        add(18, c, "Where the grind begins", "Get 5k burrows in one event", Rarity.COMMON);
        add(19, c, "Touch some grass", "Get 10k burrows in one event", Rarity.UNCOMMON, 18);
        add(20, c, "Please go outside", "Get 15k burrows in one event", Rarity.EPIC, 19);
        add(21, c, "Digging your own grave", "Get 20k burrows in one event", Rarity.LEGENDARY, 20);
        add(22, c, "Are you mentally stable?", "Get 25k burrows in one event", Rarity.MYTHIC, 21);
        add(68, c, "Dedicated Digger", "Get 350 burrows/hour (5h playtime)", Rarity.UNCOMMON);
        add(69, c, "Burrow Enthusiast", "Get 400 burrows/hour (5h playtime)", Rarity.EPIC, 68);
        add(70, c, "Shovel Expert", "Get 450 burrows/hour (5h playtime)", Rarity.LEGENDARY, 69);
        add(71, c, "Burrow Maniac", "Get 500 burrows/hour (5h playtime)", Rarity.DIVINE, 70);
        add(72, c, "Nice macro!", "Get 600 burrows/hour (5h playtime)", Rarity.IMPOSSIBLE, 71, true, true);

        c = "Playtime";
        add(23, c, "So this is Diana?", "1 hour of playtime in one event", Rarity.COMMON);
        add(24, c, "Is this really fun?", "10 hours of playtime in one event", Rarity.UNCOMMON, 23);
        add(25, c, "No shower for me", "1 day of playtime in one event", Rarity.RARE, 24);
        add(26, c, "Are you okay?", "2 days of playtime in one event", Rarity.EPIC, 25);
        add(27, c, "Sleep is downtime!", "3 days of playtime in one event", Rarity.LEGENDARY, 26);

        c = "Dry Streaks";
        add(29, c, "lf Stick", "200 Minotaur since Stick", Rarity.COMMON);
        add(30, c, "lf Relic", "1000 Champions since Relic", Rarity.UNCOMMON);
        add(65, c, "Where is my Relic?", "3000 champions since Relic", Rarity.MYTHIC, 30);
        add(31, c, "lf Inquisitor", "250 mobs since Inquisitor", Rarity.COMMON);
        add(32, c, "You have legi Griffin right?", "500 mobs since Inquisitor", Rarity.RARE, 31);
        add(33, c, "Why do you still play?", "1000 mobs since Inquisitor", Rarity.LEGENDARY, 32);
        add(34, c, "lf Chimera", "15 Inquisitors since Chimera", Rarity.COMMON);
        add(35, c, "So where is my Chimera?", "30 inquisitors since Chimera", Rarity.EPIC, 34);
        add(36, c, "I am done", "60 Inquisitors since Chimera", Rarity.LEGENDARY, 35);
        add(37, c, "No more Diana", "100 Inquisitors since Chimera", Rarity.DIVINE, 36);

        c = "Magic Find";
        add(39, c, "Fortune seeker", "Get a Diana drop with 300 Magic Find", Rarity.UNCOMMON);
        add(40, c, "Blessed by fortune", "Get a Diana drop with 400 Magic Find", Rarity.EPIC, 39);
        add(41, c, "Greed knows no bounds", "Get a Diana drop with 500 Magic Find", Rarity.MYTHIC, 40);
        add(42, c, "The principle of luck", "Get a Diana drop with 600 Magic Find", Rarity.DIVINE, 41);
        add(44, c, "Magic Find is overrated", "Drop a Chimera, under 200 Magic Find", Rarity.EPIC);
        add(43, c, "I don't need Magic Find", "Drop a Chimera, under 100 Magic Find", Rarity.LEGENDARY, 44);

        c = "Bestiary";
        add(45, c, "Inquisitor Slayer", "Max the Inquisitor Bestiary", Rarity.EPIC, null, false, false);
        add(46, c, "Minotaur Slayer", "Max the Minotaur Bestiary", Rarity.LEGENDARY, null, false, false);
        add(47, c, "Champion Slayer", "Max the Champion Bestiary", Rarity.EPIC, null, false, false);
        add(48, c, "Hunter Slayer", "Max the Hunter Bestiary", Rarity.EPIC, null, false, false);
        add(49, c, "Lynx Slayer", "Max the Siamese Lynx Bestiary", Rarity.EPIC, null, false, false);
        add(50, c, "Gaia Slayer", "Max the Gaia Bestiary", Rarity.LEGENDARY, null, false, false);
        add(101, c, "Nymph Slayer", "Max the Nymph Bestiary", Rarity.EPIC, null, false, false);
        add(102, c, "Cretan Bull Slayer", "Max the Cretan Bull Bestiary", Rarity.EPIC, null, false, false);
        add(103, c, "Harpy Slayer", "Max the Harpy Bestiary", Rarity.EPIC, null, false, false);
        add(104, c, "Sphinx Slayer", "Max the Sphinx Bestiary", Rarity.LEGENDARY, null, false, false);
        add(105, c, "Manticore Slayer", "Max the Manticore Bestiary", Rarity.MYTHIC, null, false, false);
        add(106, c, "King Minos Slayer", "Max the King Minos Bestiary", Rarity.MYTHIC, null, false, false);
        add(51, c, "Time to get on the leaderboard", "Max all Diana Bestiaries", Rarity.MYTHIC, null, true, false);

        c = "Daedalus Axe";
        add(52, c, "Daedalus Mastery: Chimera V", "Chimera V on Daedalus Axe", Rarity.LEGENDARY, null, false, false);
        add(53, c, "Daedalus Mastery: Looting V", "Looting V on Daedalus Axe", Rarity.LEGENDARY, null, false, false);
        add(54, c, "Daedalus Mastery: Divine Gift III", "Divine Gift III on Daedalus Axe", Rarity.LEGENDARY, null, false, false);
        add(55, c, "Looking Clean", "Get max Divine Gift, Chimera, Looting", Rarity.MYTHIC, null, true, false);

        c = "Other";
        add(73, c, "Can I craft a Chimera sword now?", "Get 1 stick & 2 chimeras in 1 event", Rarity.EPIC);
        add(74, c, "Can I craft a Relic sword now?", "Get 1 stick & 2 relics in 1 event", Rarity.LEGENDARY);
        add(78, c, "Can I craft a Core sword now?", "Get 1 stick & 2 manti-cores in 1 event", Rarity.DIVINE);
        add(79, c, "Can I craft a Shimmering bed now?", "Get 3 shimmering wool in 1 event", Rarity.CELESTIAL, 83);
        add(86, c, "It could look better", "Get a Mythological Dye", Rarity.EPIC);
        add(84, c, "Those coins gotta be heavy?", "Make 1b profit in 1 event", Rarity.LEGENDARY);
        add(128, c, "Got 'Em All", "Get every diana drop in 1 event", Rarity.CELESTIAL);
        add(92, c, "Why am I not getting a wool???", "Hit a king with a shear", Rarity.UNCOMMON, null, true, false);
        add(93, c, "Why are you doing this?", "Hit a Manticore with 'core' in item name", Rarity.UNCOMMON, null, true, false);
        add(118, c, "No wool? Sell his soul to the devil!", "Get a King's soul", Rarity.EPIC, null, true, false);
        add(119, c, "Knowledge is Power", "Get Myth the Fish from answering Sphinx question correct", Rarity.MYTHIC, null, true, true);
        add(120, c, "Max Diana Carnival", "Get all diana carnival perks maxed out", Rarity.LEGENDARY, null, false, false);
        add(77, c, "From the ashes", "Drop a Phoenix pet from a Diana mob", Rarity.IMPOSSIBLE, null, true, true);
        add(121, c, "Capitalism on top!", "Get COA", Rarity.RARE, null, false, false);
        add(122, c, "Inflation speedrun any%", "Get a 10m coins COA", Rarity.EPIC, 121, false, false);
        add(123, c, "Already? Damn that was fast!", "Get a 100m coins COA", Rarity.LEGENDARY, 122, false, false);
        add(124, c, "All of that just for 2.5 mf...", "Get a 1b coins COA", Rarity.MYTHIC, 123, false, false);
    }

    // ------------------------------------------------------------------------------------------------ saving

    private static void load() {
        try {
            if (Files.exists(file)) {
                Saved loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
                if (loaded != null) {
                    if (loaded.total == null) loaded.total = new LinkedHashMap<>();
                    if (loaded.currentEvent == null) loaded.currentEvent = new LinkedHashMap<>();
                    saved = loaded;
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the Diana achievements: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the Diana achievements: " + e.getMessage());
        }
    }
}
