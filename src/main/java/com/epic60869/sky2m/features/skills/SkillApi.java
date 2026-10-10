package com.epic60869.sky2m.features.skills;

import com.epic60869.sky2m.Sky2MStorageSearch;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's SkillApi and SkillUtil (https://github.com/hannibal002/SkyHanni, LGPL-2.1): reads skill XP from the
 * action bar, the tab list, the Your Skills menu and XP chat messages (Jerry Box, gifts, Lily-splosion), and keeps
 * each skill's level, XP, overflow and custom goal per profile.
 */
public final class SkillApi {
    // REGEX-TEST: +1.1 Mining (48.39%)
    private static final Pattern SKILL_PERCENT = Pattern.compile("\\+(?<gained>[\\d.,]+) (?<skillName>.+) \\((?<progress>[\\d.,]+)%\\)");
    // REGEX-TEST: +6.3 Foraging (24/750)   +207.2 Hunting (5,183,244/0)
    private static final Pattern SKILL_MULTIPLIER = Pattern.compile("\\+(?<gained>[\\d.,]+) (?<skillName>.+) \\((?<current>[\\d.,]+)/(?<needed>[\\d,.]+[kmb]?)\\)");
    private static final Pattern JERRY_BOX = Pattern.compile(".*You claimed (?<gained>[\\d,]+) (?<skillName>\\w+) XP from the Jerry Box!");
    private static final Pattern GIFT = Pattern.compile("(?:COMMON|RARE|SWEET|SANTA(?: TIER)?|PARTY(?: TIER)?)! \\+(?<gained>[\\d,]+) (?<skillName>[\\w ]+) XP gift with .*!?");
    private static final Pattern LILY_SPLOSION_START = Pattern.compile("LIL[YI]-SPLOSION!");
    private static final Pattern LILY_SPLOSION_XP = Pattern.compile("\\+(?<gained>[\\d,]+) (?<skillName>\\w+) Experience");
    // " Farming 35: 12.4%"
    private static final Pattern SKILL_TAB = Pattern.compile(" (?<type>\\w+)(?: (?<level>\\d+))?: (?<progress>[0-9.]+)%");
    // " Farming 60: MAX"
    private static final Pattern MAX_SKILL_TAB = Pattern.compile(" (?<type>\\w+) (?<level>\\d+): MAX");
    // " Mining 14: 22,922/75k"
    private static final Pattern SKILL_TAB_NO_PERCENT = Pattern.compile(" (?<type>\\w+)(?: (?<level>\\d+))?: (?<current>[0-9,.]+)/(?<needed>[\\d,.]+[kMB]?+)");
    private static final Pattern SKILL_MAX_LEVEL_MENU = Pattern.compile("Max Skill level reached!");
    private static final String SKILL_MENU_NAME = "Your Skills";

    private static final long XP_NEEDED_FOR_60 = 111_672_425L;
    /** NEU repo's "leveling" constant: XP from each level to the next, levels 1 to 60. */
    static final int[] LEVEL_ARRAY = {50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000,
        30000, 50000, 75000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000,
        1300000, 1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000, 2500000,
        2600000, 2750000, 2900000, 3100000, 3400000, 3700000, 4000000, 4300000, 4600000, 4900000, 5200000, 5500000, 5800000,
        6100000, 6400000, 6700000, 7000000};

    private static final String GIFT_SOURCE = "chat-gift";
    private static final double SNOWMAN_MASK_BONUS = 0.10;
    private static final Map<String, Double> GIFT_TALISMAN_BONUSES = Map.of(
        "WHITE_GIFT_TALISMAN", 0.05, "GREEN_GIFT_TALISMAN", 0.10, "BLUE_GIFT_TALISMAN", 0.15,
        "PURPLE_GIFT_TALISMAN", 0.20, "GOLD_GIFT_TALISMAN", 0.25);

    public static final class SkillInfo {
        @Expose public int level = 0;
        @Expose public long totalXp = 0;
        @Expose public long currentXp = 0;
        @Expose public long currentXpMax = 0;
        @Expose public int overflowLevel = 0;
        @Expose public long overflowCurrentXp = 0;
        @Expose public long overflowTotalXp = 0;
        @Expose public long overflowCurrentXpMax = 0;
        @Expose public String lastGain = "";
        @Expose public int customGoalLevel = 0;

        public SkillInfo() {}

        SkillInfo(int level, int overflowLevel) {
            this.level = level;
            this.overflowLevel = overflowLevel;
        }

        SkillInfo copy() {
            SkillInfo c = new SkillInfo();
            c.level = level; c.totalXp = totalXp; c.currentXp = currentXp; c.currentXpMax = currentXpMax;
            c.overflowLevel = overflowLevel; c.overflowCurrentXp = overflowCurrentXp; c.overflowTotalXp = overflowTotalXp;
            c.overflowCurrentXpMax = overflowCurrentXpMax; c.lastGain = lastGain; c.customGoalLevel = customGoalLevel;
            return c;
        }
    }

    public static final class SkillXPInfo {
        float lastTotalXP = 0f;
        final LinkedList<Float> xpGainQueue = new LinkedList<>();
        float xpGainHour = 0f;
        float xpGainLast = 0f;
        int timer = 3;
        boolean sessionTimerActive = false;
        boolean isActive = false;
        long lastUpdate = 0;
        long timeActive = 0L;
    }

    record SkillLevel(int level, long xpCurrent, long xpForNext, long overflowXP) {}

    private static final class ProfileSkills {
        /** Saved by skill name (SkillType's toString is its coloured display name). */
        @Expose Map<String, SkillInfo> skillData = new HashMap<>();
        @Expose double giftTalismanSkillXpBonus = 0.0;
        Map<SkillType, SkillInfo> skills;

        Map<SkillType, SkillInfo> skills() {
            if (skills == null) {
                skills = new EnumMap<>(SkillType.class);
                if (skillData != null) {
                    for (Map.Entry<String, SkillInfo> e : skillData.entrySet()) {
                        try {
                            if (e.getValue() != null) skills.put(SkillType.valueOf(e.getKey()), e.getValue());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
            return skills;
        }

        void prepareSave() {
            if (skills == null) return;
            skillData = new HashMap<>();
            for (Map.Entry<SkillType, SkillInfo> e : skills.entrySet()) skillData.put(e.getKey().name(), e.getValue());
        }
    }

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
    private static Map<String, ProfileSkills> profiles = new HashMap<>();
    private static Path file;
    private static boolean dirty;

    static final Map<SkillType, SkillXPInfo> skillXPInfoMap = new EnumMap<>(SkillType.class);
    static final Map<SkillType, SkillInfo> oldSkillInfoMap = new EnumMap<>(SkillType.class);
    static final Map<Integer, Integer> levelingMap = new HashMap<>();
    static final Map<Integer, Integer> exactLevelingMap = new HashMap<>();
    static SkillType activeSkill;
    static boolean showDisplay = false;
    static long lastUpdate = 0;

    private static List<String> lastTabLines = List.of();
    private static long lastLilySplosion = 0;
    private static String lastProfile;
    private static int ticks;

    static {
        for (int i = 0; i < LEVEL_ARRAY.length; i++) {
            levelingMap.put(i + 1, LEVEL_ARRAY[i]);
            exactLevelingMap.put(LEVEL_ARRAY[i], i + 1);
        }
    }

    private SkillApi() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("skills.json");
        load();
        Sky2MChat.onActionBar(message -> onActionBarUpdate(message.text()));
        Sky2MChat.onChat(message -> {
            if (Sky2MLocation.onSkyblock()) onChat(message.component().getString());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> save());
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ContainerScreen container)) return;
            String title = screen.getTitle().getString();
            if (title.equals(SKILL_MENU_NAME)) {
                ScreenEvents.afterTick(screen).register(s -> readSkillMenu(container));
            } else if (title.startsWith("Accessory Bag")) {
                ScreenEvents.afterTick(screen).register(s -> {
                    List<ItemStack> items = new ArrayList<>();
                    for (Slot slot : container.getMenu().slots) items.add(slot.getItem());
                    updateGiftTalismanBonus(items);
                });
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            List<String> roots = new ArrayList<>();
            // SkyHanni's own command, unless SkyHanni is installed too.
            if (!FabricLoader.getInstance().isModLoaded("skyhanni")) roots.add("shskills");
            roots.add("sbskills");
            for (String root : roots) {
                dispatcher.register(ClientCommands.literal(root)
                    .executes(ctx -> {
                        onCommand(new String[0]);
                        return 1;
                    })
                    .then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                        onCommand(StringArgumentType.getString(ctx, "args").trim().split("\\s+"));
                        return 1;
                    })));
            }
        });
    }

    // ---------------------------------------------------------------------------------------------- storage

    /** This profile's skills (SkyHanni's ProfileStorageData.profileSpecific.skills.skillData). */
    static Map<SkillType, SkillInfo> storage() {
        return profile().skills();
    }

    private static ProfileSkills profile() {
        String id = Sky2MStorageSearch.currentProfile();
        return profiles.computeIfAbsent(id == null || id.isEmpty() ? "unknown" : id, k -> new ProfileSkills());
    }

    static void markDirty() {
        dirty = true;
    }

    private static void load() {
        try {
            if (file != null && Files.exists(file)) {
                Map<String, ProfileSkills> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, ProfileSkills>>() {}.getType());
                if (loaded != null) {
                    profiles = new HashMap<>(loaded);
                    profiles.values().removeIf(java.util.Objects::isNull);
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load skills.json: " + e.getMessage());
        }
    }

    private static void save() {
        if (file == null || !dirty) return;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            for (ProfileSkills p : profiles.values()) p.prepareSave();
            Files.writeString(file, GSON.toJson(profiles), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save skills.json: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------- events

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;
        ticks++;
        if (ticks % 20 != 0) return;
        // SkyHanni's onProfileJoin.
        String profile = Sky2MStorageSearch.currentProfile();
        if (lastProfile != null && !lastProfile.equals(profile)) SkillProgress.onProfileJoin();
        lastProfile = profile;
        if (Sky2MLocation.onSkyblock()) {
            lastTabLines = tabLines();
            onSecondPassed();
            // ItemAddInInventoryEvent: a gift talisman picked up.
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) items.add(mc.player.getInventory().getItem(i));
            updateGiftTalismanBonus(items);
        }
        SkillProgress.onSecondPassed();
        if (ticks % 600 == 0) save();
    }

    private static List<String> tabLines() {
        List<String> lines = new ArrayList<>();
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = Compat.rawTabName(info);
            if (name != null) lines.add(Sky2MLocation.strip(name.getString()));
        }
        return lines;
    }

    private static void onSecondPassed() {
        SkillType active = activeSkill;
        if (active == null) return;
        SkillXPInfo info = skillXPInfoMap.get(active);
        if (info == null || !info.sessionTimerActive) return;
        int time = pauseTime(active, 0);
        if (System.currentTimeMillis() - info.lastUpdate > time * 1000L) info.sessionTimerActive = false;
        if (info.sessionTimerActive) info.timeActive++;
    }

    static int pauseTime(SkillType skill, int fallback) {
        if (SkillProgress.config() == null) return fallback;
        var eta = SkillProgress.config().skillETADisplayConfig;
        return switch (skill) {
            case FARMING -> eta.farmingPauseTime;
            case MINING -> eta.miningPauseTime;
            case COMBAT -> eta.combatPauseTime;
            case FORAGING -> eta.foragingPauseTime;
            case FISHING -> eta.fishingPauseTime;
            default -> fallback;
        };
    }

    private static void onActionBarUpdate(String actionBar) {
        if (SkillProgress.config() == null) return;
        for (String component : actionBar.split(" {2}")) {
            component = component.trim();
            if (component.isEmpty()) continue;
            Matcher matcher = SKILL_PERCENT.matcher(component);
            boolean percent = matcher.matches();
            if (!percent) {
                matcher = SKILL_MULTIPLIER.matcher(component);
                if (!matcher.matches()) continue;
            }
            SkillType skillType = SkillType.getByNameOrNull(matcher.group("skillName"));
            if (skillType == null) return;
            SkillInfo skillInfo = storage().get(skillType);
            if (skillInfo == null) skillInfo = new SkillInfo();
            SkillXPInfo skillXP = skillXPInfoMap.computeIfAbsent(skillType, k -> new SkillXPInfo());
            activeSkill = skillType;
            if (percent) handleSkillPatternPercent(matcher, skillType);
            else handleSkillPatternMultiplier(matcher, skillType, skillInfo);

            showDisplay = true;
            lastUpdate = System.currentTimeMillis();
            skillXP.lastUpdate = System.currentTimeMillis();
            skillXP.sessionTimerActive = true;
            SkillProgress.updateDisplay();
            SkillProgress.hideInActionBar = List.of(component);
            return;
        }
    }

    private static void onChat(String raw) {
        for (String line : Sky2MLocation.strip(raw).split("\n")) {
            String message = line.trim();
            if (LILY_SPLOSION_START.matcher(message).matches()) {
                lastLilySplosion = System.currentTimeMillis();
                continue;
            }
            Matcher m = JERRY_BOX.matcher(message);
            if (m.matches()) {
                postChatSkillXp(m.group("skillName"), formatLong(m.group("gained")), "chat-jerry-box");
                return;
            }
            m = GIFT.matcher(message);
            if (m.matches()) {
                postChatSkillXp(m.group("skillName"), formatLong(m.group("gained")), GIFT_SOURCE);
                return;
            }
            m = LILY_SPLOSION_XP.matcher(message);
            if (m.matches() && System.currentTimeMillis() - lastLilySplosion <= 5_000) {
                postChatSkillXp(m.group("skillName"), formatLong(m.group("gained")), "chat-lily-splosion");
            }
        }
    }

    private static void postChatSkillXp(String skillName, long gained, String source) {
        SkillType skillType = SkillType.getByNameOrNull(skillName);
        if (skillType == null) return;
        double effectiveGain = gained * giftXpMultiplier(source);
        addChatSkillXp(skillType, effectiveGain);

        SkillXPInfo skillXP = skillXPInfoMap.computeIfAbsent(skillType, k -> new SkillXPInfo());
        activeSkill = skillType;
        showDisplay = true;
        lastUpdate = System.currentTimeMillis();
        skillXP.lastUpdate = System.currentTimeMillis();
        skillXP.sessionTimerActive = true;
        SkillProgress.updateDisplay();
    }

    private static void addChatSkillXp(SkillType skillType, double gained) {
        SkillInfo skillInfo = storage().computeIfAbsent(skillType, k -> new SkillInfo());
        if (skillInfo.totalXp <= 0L) return;
        long roundedTotalXp = Math.round(skillInfo.totalXp + gained);
        SkillLevel skillLevel = calculateSkillLevel(roundedTotalXp, skillType.maxLevel);
        skillInfo.totalXp = roundedTotalXp;
        skillInfo.currentXp = skillLevel.xpCurrent();
        skillInfo.currentXpMax = skillLevel.xpForNext();
        skillInfo.level = Math.min(skillLevel.level(), skillType.maxLevel);
        skillInfo.overflowLevel = skillLevel.level();
        skillInfo.overflowCurrentXp = skillLevel.xpCurrent();
        skillInfo.overflowCurrentXpMax = skillLevel.xpForNext();
        skillInfo.overflowTotalXp = skillLevel.overflowXP();
        skillInfo.lastGain = addSeparators(gained);
        markDirty();
    }

    private static void updateGiftTalismanBonus(List<ItemStack> items) {
        double best = 0;
        for (ItemStack stack : items) {
            Double bonus = GIFT_TALISMAN_BONUSES.get(internalName(stack));
            if (bonus != null) best = Math.max(best, bonus);
        }
        // Item and accessory events can miss temporary inventory state, so never downgrade the best known bonus.
        ProfileSkills p = profile();
        if (best > p.giftTalismanSkillXpBonus) {
            p.giftTalismanSkillXpBonus = best;
            markDirty();
        }
    }

    private static double giftXpMultiplier(String source) {
        if (!GIFT_SOURCE.equals(source)) return 1.0;
        // Gift chat says the base XP. These bonuses are applied by Hypixel after that.
        return 1.0 + profile().giftTalismanSkillXpBonus + snowmanMaskBonus();
    }

    private static double snowmanMaskBonus() {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0.0;
        return "SNOWMAN_MASK".equals(internalName(player.getItemBySlot(EquipmentSlot.HEAD))) ? SNOWMAN_MASK_BONUS : 0.0;
    }

    private static String internalName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return Compat.getCustomData(stack).getStringOr("id", "");
    }

    // ---------------------------------------------------------------------------------------------- Your Skills menu

    private static void readSkillMenu(ContainerScreen container) {
        var menu = container.getMenu();
        int size = menu.getRowCount() * 9;
        for (int i = 0; i < size && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            List<String> lore = lore(stack);
            if (lore.stream().noneMatch(l -> l.contains("Click to view!") || l.contains("Not unlocked!"))) continue;
            String cleanName = Sky2MLocation.strip(stack.getHoverName().getString());
            String[] split = cleanName.split(" ");
            SkillType skill = SkillType.getByNameOrNull(split[0]);
            if (skill == null) continue;
            int skillLevel = split.length > 1 ? romanToDecimalIfNecessary(split[split.length - 1]) : 0;
            SkillInfo skillInfo = storage().computeIfAbsent(skill, k -> new SkillInfo());

            for (int index = 0; index < lore.size(); index++) {
                String cleanLine = lore.get(index);
                if (!cleanLine.startsWith("                    ")) continue;
                if (index == 0) continue;
                String previousLine = lore.get(index - 1);
                String progress = cleanLine.substring(cleanLine.lastIndexOf(' ') + 1);
                if (SKILL_MAX_LEVEL_MENU.matcher(previousLine).matches()) {
                    onUpdateMax(progress, skill, skillInfo, skillLevel);
                } else {
                    onUpdateNotMax(progress, skillLevel, skillInfo);
                }
                markDirty();
            }
        }
    }

    static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (Component c : lore.lines()) lines.add(Sky2MLocation.strip(c.getString()));
        return lines;
    }

    private static void onUpdateMax(String progress, SkillType skill, SkillInfo skillInfo, int skillLevel) {
        long totalXP = formatLong(progress);
        int cap = skill.maxLevel;
        long maxXP = xpRequiredForLevel(cap);
        long currentXP = totalXP - maxXP;
        SkillLevel overflow = calculateSkillLevel(totalXP, cap);
        skillInfo.overflowLevel = overflow.level();
        skillInfo.overflowCurrentXp = overflow.xpCurrent();
        skillInfo.overflowCurrentXpMax = overflow.xpForNext();
        skillInfo.overflowTotalXp = overflow.overflowXP();
        skillInfo.totalXp = totalXP;
        skillInfo.level = skillLevel;
        skillInfo.currentXp = currentXP;
        skillInfo.currentXpMax = 0L;
    }

    private static void onUpdateNotMax(String progress, int skillLevel, SkillInfo skillInfo) {
        String[] splitProgress = progress.split("/");
        long currentXP = formatLong(splitProgress[0]);
        long neededXP = formatLong(splitProgress[splitProgress.length - 1]);
        long levelXP = (long) calculateLevelXP(skillLevel - 1);
        skillInfo.currentXp = currentXP;
        skillInfo.level = skillLevel;
        skillInfo.currentXpMax = neededXP;
        skillInfo.totalXp = levelXP + currentXP;
        skillInfo.overflowCurrentXp = currentXP;
        skillInfo.overflowLevel = skillLevel;
        skillInfo.overflowCurrentXpMax = neededXP;
        skillInfo.overflowTotalXp = levelXP + currentXP;
    }

    // ---------------------------------------------------------------------------------------------- action bar parsing

    private static void handleSkillPatternPercent(Matcher matcher, SkillType skillType) {
        long current = 0L;
        long needed = 0L;
        boolean isPercentPatternFound = false;
        Integer tablistLevel = null;

        for (String line : lastTabLines) {
            Matcher m = SKILL_TAB.matcher(line);
            if (m.matches()) {
                if (m.group("type").equals(skillType.displayName)) {
                    tablistLevel = m.group("level") == null ? null : Integer.parseInt(m.group("level"));
                    isPercentPatternFound = true;
                    if (activeSkill == null || !m.group("type").toLowerCase(Locale.ROOT).equals(activeSkill.lowercaseName)) tablistLevel = null;
                    break;
                }
            }
            m = MAX_SKILL_TAB.matcher(line);
            if (m.matches()) {
                if (m.group("type").equals(skillType.displayName)) {
                    tablistLevel = Integer.parseInt(m.group("level"));
                    if (activeSkill == null || !m.group("type").toLowerCase(Locale.ROOT).equals(activeSkill.lowercaseName)) tablistLevel = null;
                    break;
                }
            }
            m = SKILL_TAB_NO_PERCENT.matcher(line);
            if (m.matches()) {
                if (m.group("type").equals(skillType.displayName)) {
                    tablistLevel = m.group("level") == null ? null : Integer.parseInt(m.group("level"));
                    current = formatLong(m.group("current"));
                    needed = formatLong(m.group("needed"));
                    isPercentPatternFound = false;
                    break;
                }
            }
        }

        double xpPercentage = formatDouble(matcher.group("progress"));
        SkillInfo existingLevel = storage().get(skillType);
        if (existingLevel == null) existingLevel = new SkillInfo();
        if (tablistLevel == null) return;
        int level = tablistLevel;
        if (isPercentPatternFound) {
            double levelXP = calculateLevelXP(level - 1);
            double nextLevelDiff = level >= 0 && level < LEVEL_ARRAY.length ? LEVEL_ARRAY[level] : 7_600_000.0;
            double nextLevelProgress = nextLevelDiff * xpPercentage / 100;
            double totalXP = levelXP + nextLevelProgress;
            updateSkillInfo(existingLevel, level, (long) nextLevelProgress, (long) nextLevelDiff, (long) totalXP, matcher.group("gained"));
        } else {
            long levelXP = (long) calculateLevelXP(level - 1) + current;
            updateSkillInfo(existingLevel, level, current, needed, levelXP, matcher.group("gained"));
        }
        storage().put(skillType, existingLevel);
        markDirty();
    }

    private static void updateSkillInfo(SkillInfo existingLevel, int level, long currentXP, long maxXP, long totalXP, String gained) {
        Integer cap = activeSkill == null ? null : activeSkill.maxLevel;
        long add = cap != null && level >= cap ? xpRequiredForLevel(cap) : 0;
        SkillLevel overflow = calculateSkillLevel(totalXP + add, cap == null ? 60 : cap);
        existingLevel.totalXp = totalXP;
        existingLevel.currentXp = currentXP;
        existingLevel.currentXpMax = maxXP;
        existingLevel.level = level;
        existingLevel.overflowTotalXp = overflow.overflowXP();
        existingLevel.overflowCurrentXp = overflow.xpCurrent();
        existingLevel.overflowCurrentXpMax = overflow.xpForNext();
        existingLevel.overflowLevel = overflow.level();
        existingLevel.lastGain = gained;
    }

    private static void handleSkillPatternMultiplier(Matcher matcher, SkillType skillType, SkillInfo skillInfo) {
        long currentXP = formatLong(matcher.group("current"));
        long maxXP = formatLong(matcher.group("needed"));
        // when at overflow, we don't need to subtract one level in the logic below
        int minus = maxXP == 0L ? 0 : 1;
        int level = getLevelExact(maxXP) - minus;

        long levelXP = (long) calculateLevelXP(level - 1) + currentXP;
        SkillLevel current = calculateSkillLevel(levelXP, skillType.maxLevel);

        if (skillInfo.overflowLevel > skillType.maxLevel && current.level() == skillInfo.overflowLevel + 1) {
            SkillProgress.onLevelUp(skillType, skillInfo.overflowLevel, current.level());
        }

        skillInfo.overflowCurrentXp = current.xpCurrent();
        skillInfo.overflowCurrentXpMax = current.xpForNext();
        skillInfo.overflowTotalXp = current.overflowXP();
        skillInfo.overflowLevel = current.level();
        skillInfo.currentXp = currentXP;
        skillInfo.currentXpMax = maxXP;
        skillInfo.totalXp = levelXP;
        skillInfo.level = level;
        skillInfo.lastGain = matcher.group("gained");
        storage().put(skillType, skillInfo);
        markDirty();
    }

    // ---------------------------------------------------------------------------------------------- SkillUtil

    static long xpRequiredForLevel(int desiredLevel) {
        long totalXP = 0L;
        if (desiredLevel <= 60) {
            for (int level = 1; level <= desiredLevel; level++) totalXP += levelingMap.getOrDefault(level, 0);
        } else {
            totalXP += XP_NEEDED_FOR_60;
            int level = 60;
            long xpForNext = 7_000_000L + 600_000L;
            long slope = 600_000L;
            while (level < desiredLevel) {
                totalXP += xpForNext;
                level++;
                xpForNext += slope;
                if (level % 10 == 0) slope *= 2;
            }
        }
        return totalXP;
    }

    static int getLevelExact(long neededXP) {
        return exactLevelingMap.getOrDefault((int) neededXP, activeSkill != null ? activeSkill.maxLevel : 60);
    }

    static double calculateLevelXP(int level) {
        double sum = 0;
        for (int i = 0; i < Math.min(level + 1, LEVEL_ARRAY.length); i++) sum += LEVEL_ARRAY[i];
        return sum;
    }

    /** The returned level is uncapped; only overflowXP is relative to the cap. */
    static SkillLevel calculateSkillLevel(long currentXP, int maxSkillCap) {
        long xpCurrent = currentXP;
        int level = 0;
        while (level < 60) {
            Integer xpForNextLevel = levelingMap.get(level + 1);
            if (xpForNextLevel == null) break;
            if (xpCurrent < xpForNextLevel) break;
            xpCurrent -= xpForNextLevel;
            level++;
        }
        long xpForNext = levelingMap.getOrDefault(level + 1, 0);
        if (level >= 60) {
            long slope = 600_000L;
            xpForNext = 7_000_000L + slope;
            while (xpCurrent >= xpForNext) {
                level++;
                xpCurrent -= xpForNext;
                xpForNext += slope;
                if (level % 10 == 0) slope *= 2;
            }
        }
        long xpForCap = xpRequiredForLevel(Math.min(maxSkillCap, 60));
        long overflowXP = Math.max(0L, currentXP - xpForCap);
        return new SkillLevel(level, xpCurrent, xpForNext, overflowXP);
    }

    // ---------------------------------------------------------------------------------------------- command

    private static void onCommand(String[] it) {
        if (it.length == 0 || (it.length == 1 && it[0].isEmpty())) {
            commandHelp();
            return;
        }
        String first = it[0].toLowerCase(Locale.ROOT);
        if (it.length == 1 && first.equals("goal")) {
            chat("§bSkill Custom Goal Level");
            boolean any = false;
            for (Map.Entry<SkillType, SkillInfo> e : storage().entrySet()) {
                if (e.getValue().customGoalLevel == 0) continue;
                any = true;
                chat("§e" + e.getKey().displayName + ": §b" + e.getValue().customGoalLevel);
            }
            if (!any) userError("You haven't set any custom goals yet!");
            return;
        }
        if (it.length == 2) {
            String second = it[1];
            switch (first) {
                case "levelwithxp" -> {
                    long xp;
                    try {
                        xp = formatLong(second);
                    } catch (Exception e) {
                        userError("Not a valid number: '" + second + "'");
                        return;
                    }
                    SkillLevel l = calculateSkillLevel(xp, 60);
                    chat("With §b" + addSeparators(xp) + " §eXP you would be level §b" + l.level()
                        + " §ewith progress (§b" + addSeparators(l.xpCurrent()) + "§e/§b" + addSeparators(l.xpForNext()) + "§e) XP");
                    return;
                }
                case "xpforlevel" -> {
                    int level;
                    try {
                        level = Integer.parseInt(second);
                    } catch (NumberFormatException e) {
                        userError("Not a valid number: '" + second + "'");
                        return;
                    }
                    chat("You need §b" + addSeparators(xpRequiredForLevel(level)) + " §eXP to reach level §b" + (double) level);
                    return;
                }
                case "goal" -> {
                    String rawSkill = it[1].toLowerCase(Locale.ROOT);
                    SkillType skillType = SkillType.getByNameOrNull(rawSkill);
                    if (skillType == null) {
                        userError("Unknown Skill type: " + rawSkill);
                        return;
                    }
                    SkillInfo skill = storage().get(skillType);
                    if (skill == null) return;
                    skill.customGoalLevel = 0;
                    markDirty();
                    chat("Custom goal level for §b" + skillType.displayName + " §ereset");
                    return;
                }
                default -> {
                }
            }
        }
        if (it.length == 3 && first.equals("goal")) {
            String rawSkill = it[1].toLowerCase(Locale.ROOT);
            SkillType skillType = SkillType.getByNameOrNull(rawSkill);
            if (skillType == null) {
                userError("Unknown Skill type: " + rawSkill);
                return;
            }
            String rawLevel = it[2];
            int targetLevel;
            try {
                targetLevel = Integer.parseInt(rawLevel);
            } catch (NumberFormatException e) {
                userError(rawLevel + " is not a valid number.");
                return;
            }
            SkillInfo skill = storage().get(skillType);
            if (skill == null) return;
            if (targetLevel <= skill.overflowLevel) {
                userError("Custom goal level (" + targetLevel + ") must be greater than your current level (" + skill.overflowLevel + ").");
                return;
            }
            skill.customGoalLevel = targetLevel;
            markDirty();
            chat("Custom goal level for §b" + skillType.displayName + " §eset to §b" + targetLevel);
            return;
        }
        commandHelp();
    }

    private static void commandHelp() {
        String root = FabricLoader.getInstance().isModLoaded("skyhanni") ? "/sbskills" : "/shskills";
        rawChat(String.join("\n",
            "§6" + root + " levelwithxp <xp> - §bGet a level with the given current XP.",
            "§6" + root + " xpforlevel <desiredLevel> - §bGet how much XP you need for a desired level.",
            "§6" + root + " goal - §bView your current goal",
            "§6" + root + " goal <skill> <level> - §bDefine your goal for <skill>",
            ""));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** A chat line with the Sky2M prefix, in SkyHanni's default yellow. */
    static void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal("§e" + message)));
        });
    }

    static void rawChat(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
        });
    }

    private static void userError(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal("§c" + message)));
        });
    }

    /** SkyHanni's String.formatLong(): "1,234", "50k", "3.1M", "2b". */
    static long formatLong(String text) {
        return Math.round(formatDouble(text));
    }

    static double formatDouble(String text) {
        String t = text.replace(",", "").trim().toLowerCase(Locale.ROOT);
        double scale = 1;
        if (t.endsWith("k")) scale = 1_000;
        else if (t.endsWith("m")) scale = 1_000_000;
        else if (t.endsWith("b")) scale = 1_000_000_000;
        if (scale > 1) t = t.substring(0, t.length() - 1);
        return Double.parseDouble(t) * scale;
    }

    static String addSeparators(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** SkyHanni's Number.addSeparators() for decimals: "1,234.5" (up to two decimals, no trailing zeros). */
    static String addSeparators(double value) {
        java.text.DecimalFormat format = new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }

    static int romanToDecimalIfNecessary(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        Map<Character, Integer> values = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100, 'D', 500, 'M', 1000);
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            Integer v = values.get(text.charAt(i));
            if (v == null) return 0;
            Integer next = i + 1 < text.length() ? values.get(text.charAt(i + 1)) : null;
            total += next != null && next > v ? -v : v;
        }
        return total;
    }

    static boolean isRoman(String text) {
        return !text.isEmpty() && text.matches("[IVXLCDM]+");
    }

    static String toRoman(int number) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (number >= values[i]) {
                number -= values[i];
                out.append(symbols[i]);
            }
        }
        return out.toString();
    }
}
