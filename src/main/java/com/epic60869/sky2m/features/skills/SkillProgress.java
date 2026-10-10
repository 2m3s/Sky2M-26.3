package com.epic60869.sky2m.features.skills;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SkyHanni's SkillProgress (https://github.com/hannibal002/SkyHanni, LGPL-2.1): the skill progress display and its
 * bar, the ETA display (XP/h, time to the next level or your goal, session time) and the all skills display, the
 * overflow level-up message and hiding the skill XP in the action bar. Hover and click the ETA and all skills
 * displays with chat or a menu open.
 */
public final class SkillProgress {
    private static final String DISPLAY_ID = "skill_progress";
    private static final String BAR_ID = "skill_progress_bar";
    private static final String ETA_ID = "skill_eta";
    private static final String ALL_ID = "skill_all";

    private static double skillExpPercentage = 0.0;
    private static List<Object> display = List.of();
    private static List<Line> allDisplay = List.of();
    private static List<Line> etaDisplay = List.of();
    private static long lastGainUpdate = 0;
    private static int maxWidth = 182;
    static List<String> hideInActionBar = List.of();

    /** A line of a display: legacy-formatted text, with hover tips and a click action (both optional). */
    private record Line(String text, List<String> tips, Runnable onClick) {
        Line(String text) {
            this(text, List.of(), null);
        }
    }

    /** A hover/click area drawn this frame, in screen coordinates. */
    private record Region(int x1, int y1, int x2, int y2, List<String> tips, Runnable onClick) {}

    /** Each display's hover/click areas from the last time it was drawn. */
    private static final Map<String, List<Region>> REGIONS = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<Region> regions() {
        List<Region> all = new ArrayList<>();
        for (List<Region> r : REGIONS.values()) all.addAll(r);
        return all;
    }

    private SkillProgress() {}

    static FeatureConfigs.SkillProgress config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.skills;
    }

    public static void init() {
        SkillApi.init(Minecraft.getInstance().gameDirectory.toPath().resolve("config"));
        SkillTooltip.init();
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? onActionBar(message) : message);

        Sky2MHuds.setting(DISPLAY_ID, () -> config() != null && config().enabled);
        Sky2MHuds.registerCustom(DISPLAY_ID, "Skill Progress", () -> config() != null && config().enabled, new DisplayHud(), 250, 200);
        Sky2MHuds.setting(BAR_ID, () -> config() != null && config().enabled && config().skillProgressBarConfig.enabled);
        Sky2MHuds.registerCustom(BAR_ID, "Skill Progress Bar", () -> config() != null && config().enabled && config().skillProgressBarConfig.enabled, new BarHud(), 250, 218);
        Sky2MHuds.setting(ETA_ID, () -> config() != null && config().enabled && config().skillETADisplayConfig.enabled);
        Sky2MHuds.registerCustom(ETA_ID, "Skill ETA", () -> config() != null && config().enabled && config().skillETADisplayConfig.enabled, new LinesHud(ETA_ID, () -> etaDisplay, ETA_PREVIEW), 5, 155);
        Sky2MHuds.setting(ALL_ID, () -> config() != null && config().enabled && config().allSkillDisplayConfig.enabled);
        Sky2MHuds.registerCustom(ALL_ID, "All Skills Display", () -> config() != null && config().enabled && config().allSkillDisplayConfig.enabled, new LinesHud(ALL_ID, () -> allDisplay, ALL_PREVIEW), 5, 209);

        // Hover tips and clicks on the ETA and all skills displays, and the displays above menus.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> afterScreen(s, graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(event.x(), event.y()));
        });
    }

    private static final List<Line> ETA_PREVIEW = List.of(new Line("§6Skill: §aFarming §847➜§348"), new Line("§7In §b58m 12s "),
        new Line("§7XP/h: §e1,520,000 "), new Line("§7Session: §e1h 2m 3s "));
    private static final List<Line> ALL_PREVIEW = List.of(new Line("§aCombat 45 §7(§b1,234,567§6/§b2,600,000§7)"),
        new Line("§2Farming 60 §7(§b5,000,000§7)"));

    // ---------------------------------------------------------------------------------------------- events

    static void onProfileJoin() {
        display = List.of();
        allDisplay = List.of();
        etaDisplay = List.of();
        skillExpPercentage = 0.0;
    }

    static void onSecondPassed() {
        if (!isDisplayEnabled()) return;
        FeatureConfigs.SkillProgress c = config();
        if (System.currentTimeMillis() - SkillApi.lastUpdate > 3_000) SkillApi.showDisplay = c.alwaysShow;
        allDisplay = formatAllDisplay(drawAllDisplay());
        etaDisplay = drawETADisplay();
        // The display follows setting changes (SkyHanni redraws it when they're toggled).
        updateDisplay();
        update();
        updateSkillInfo();
    }

    static void onLevelUp(SkillType skillType, int oldLevel, int newLevel) {
        FeatureConfigs.SkillProgress c = config();
        if (c == null || !c.overflowConfig.enableInChat || !Sky2MLocation.onSkyblock()) return;
        String skillName = skillType.displayName;
        SkillApi.SkillInfo skill = SkillApi.storage().get(skillType);
        if (skill == null) return;
        boolean goalReached = newLevel == skill.customGoalLevel && c.customGoalConfig.enableInChat;

        List<String> rewards = new ArrayList<>();
        rewards.add("  §r§7§8+§b1 Flexing Point");
        if (newLevel % 5 == 0) rewards.add("  §r§7§8+§d50 SkyHanni User Luck");
        List<String> messages = List.of(
            "§3§l---------------------------------------------",
            "  §r§b§lSKILL LEVEL UP §3" + skillName + " §8" + oldLevel + "➜§3" + newLevel,
            goalReached ? String.join("\n", "", "  §r§d§lGOAL REACHED!", "") : "",
            "  §r§a§lREWARDS",
            String.join("\n", rewards),
            "§3§l---------------------------------------------");
        SkillApi.rawChat(String.join("\n", messages));
        if (goalReached) {
            SkillApi.chat("§lYou have reached your goal level of §b§l" + skill.customGoalLevel + " §e§lin the §b§l" + skillName + " §e§lskill!");
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> com.epic60869.sky2m.features.core.Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1f, 1f));
    }

    /** Hide In Action Bar: removes the skill XP part Hypixel shows, keeping the rest and its colours. */
    private static Component onActionBar(Component message) {
        FeatureConfigs.SkillProgress c = config();
        if (c == null || !c.hideInActionBar || !isDisplayEnabled()) return message;
        Component result = message;
        for (String line : hideInActionBar) result = removeText(result, line);
        return result;
    }

    /** The message without {@code text} (matched ignoring colour codes) and the spaces before it; other parts keep their colours. */
    public static Component removeText(Component message, String text) {
        List<String> values = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        message.visit((style, value) -> {
            if (!value.isEmpty()) {
                values.add(value);
                styles.add(style);
                plain.append(value);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        String all = plain.toString();
        // Hypixel can put legacy colour codes inside the text: match without them, mapped back to the raw text.
        StringBuilder stripped = new StringBuilder();
        List<Integer> rawIndex = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            if (all.charAt(i) == '§' && i + 1 < all.length()) {
                i++;
                continue;
            }
            stripped.append(all.charAt(i));
            rawIndex.add(i);
        }
        int at = stripped.indexOf(text);
        if (at < 0) return message;
        int start = rawIndex.get(at);
        int end = rawIndex.get(at + text.length() - 1) + 1;
        // Also the spaces (and colour codes) in front of it, like SkyHanni's "\s*" + text.
        while (start > 0) {
            if (Character.isWhitespace(all.charAt(start - 1))) start--;
            else if (start > 1 && all.charAt(start - 2) == '§') start -= 2;
            else break;
        }
        MutableComponent out = Component.empty();
        int offset = 0;
        for (int r = 0; r < values.size(); r++) {
            String value = values.get(r);
            int runStart = offset, runEnd = offset + value.length();
            StringBuilder kept = new StringBuilder();
            for (int i = runStart; i < runEnd; i++) if (i < start || i >= end) kept.append(all.charAt(i));
            if (!kept.isEmpty()) out.append(Component.literal(kept.toString()).setStyle(styles.get(r)));
            offset = runEnd;
        }
        return out.getString().isBlank() ? Component.empty() : out;
    }

    static void updateDisplay() {
        display = drawDisplay();
    }

    private static void update() {
        lastGainUpdate = System.currentTimeMillis();
        for (SkillApi.SkillXPInfo info : SkillApi.skillXPInfoMap.values()) info.xpGainLast = info.xpGainHour;
    }

    // ---------------------------------------------------------------------------------------------- displays

    private static List<Line> formatAllDisplay(Map<SkillType, Line> map) {
        List<Line> newList = new ArrayList<>();
        if (map.isEmpty()) return newList;
        for (SkillType skillType : config().allSkillDisplayConfig.skillEntryList) {
            Line line = map.get(skillType);
            if (line != null) newList.add(line);
        }
        return newList;
    }

    /** Progress towards the custom goal level, as cumulative XP out of the cumulative XP that level needs. */
    private static SkillApi.SkillLevel customGoalProgress(SkillApi.SkillInfo info) {
        return new SkillApi.SkillLevel(info.overflowLevel, info.totalXp, SkillApi.xpRequiredForLevel(info.customGoalLevel), info.totalXp);
    }

    private static Map<SkillType, Line> drawAllDisplay() {
        Map<SkillType, Line> map = new EnumMap<>(SkillType.class);
        FeatureConfigs.SkillProgress c = config();
        Map<SkillType, SkillApi.SkillInfo> skillMap = SkillApi.storage();
        List<SkillType> sorted = new ArrayList<>(List.of(SkillType.values()));
        sorted.sort(java.util.Comparator.comparing(t -> t.displayName.substring(0, 2)));
        for (SkillType skill : sorted) {
            SkillApi.SkillInfo skillInfo = skillMap.get(skill);
            if (skillInfo == null) skillInfo = new SkillApi.SkillInfo(-1, -1);
            boolean lockedLevels = skillInfo.overflowCurrentXp > skillInfo.overflowCurrentXpMax;
            boolean useCustomGoalLevel = skillInfo.customGoalLevel != 0 && skillInfo.customGoalLevel > skillInfo.overflowLevel
                && c.customGoalConfig.enableInAllDisplay;
            SkillApi.SkillLevel l;
            if (useCustomGoalLevel) l = customGoalProgress(skillInfo);
            else if (c.overflowConfig.enableInAllDisplay && !lockedLevels)
                l = new SkillApi.SkillLevel(skillInfo.overflowLevel, skillInfo.overflowCurrentXp, skillInfo.overflowCurrentXpMax, skillInfo.overflowTotalXp);
            else l = new SkillApi.SkillLevel(skillInfo.level, skillInfo.currentXp, skillInfo.currentXpMax, skillInfo.totalXp);

            if (l.level() == -1) {
                map.put(skill, new Line("§cOpen your skills menu!", List.of("§eClick here to execute §6/skills"), () -> sendCommand("skills")));
            } else {
                List<String> tips = List.of(
                    "§6Level: §b" + l.level(),
                    "§6Current XP: §b" + SkillApi.addSeparators(l.xpCurrent()),
                    "§6Needed XP: §b" + SkillApi.addSeparators(l.xpForNext()),
                    "§6Total XP: §b" + SkillApi.addSeparators(l.overflowXP()));
                String nameColor = skill == SkillApi.activeSkill ? "§2" : "§a";
                StringBuilder text = new StringBuilder();
                text.append(nameColor).append(skill.displayName).append(" ").append(l.level()).append(" ");
                text.append("§7(").append("§b").append(SkillApi.addSeparators(l.xpCurrent()));
                if (l.xpForNext() != 0L) text.append("§6/").append("§b").append(SkillApi.addSeparators(l.xpForNext()));
                text.append("§7)");
                map.put(skill, new Line(text.toString(), tips, null));
            }
        }
        return map;
    }

    private static List<Line> drawETADisplay() {
        List<Line> list = new ArrayList<>();
        FeatureConfigs.SkillProgress c = config();
        SkillType activeSkill = SkillApi.activeSkill;
        if (activeSkill == null) return list;
        SkillApi.SkillInfo skillInfo = SkillApi.storage().get(activeSkill);
        if (skillInfo == null) return list;
        SkillApi.SkillXPInfo xpInfo = SkillApi.skillXPInfoMap.get(activeSkill);
        if (xpInfo == null) return list;
        SkillApi.SkillInfo skillInfoLast = SkillApi.oldSkillInfoMap.get(activeSkill);
        if (skillInfoLast == null) return list;
        SkillApi.oldSkillInfoMap.put(activeSkill, skillInfo);
        int level = c.overflowConfig.enableInEtaDisplay || c.customGoalConfig.enableInETADisplay ? skillInfo.overflowLevel : skillInfo.level;

        boolean useCustomGoalLevel = skillInfo.customGoalLevel != 0 && skillInfo.customGoalLevel > skillInfo.overflowLevel
            && c.customGoalConfig.enableInETADisplay;
        int targetLevel = useCustomGoalLevel ? skillInfo.customGoalLevel : level + 1;
        if (targetLevel < level + 1 || targetLevel > 400) targetLevel = level + 1;

        long need = skillInfo.overflowCurrentXpMax;
        long have = skillInfo.overflowCurrentXp;
        long currentLevelNeededXP = SkillApi.xpRequiredForLevel(level) + have;
        long targetNeededXP = SkillApi.xpRequiredForLevel(targetLevel);
        long remaining = useCustomGoalLevel ? targetNeededXP - currentLevelNeededXP : need - have;

        if (!useCustomGoalLevel && have < need) {
            if (skillInfo.overflowCurrentXpMax == skillInfoLast.overflowCurrentXpMax) {
                remaining = (long) interpolate(remaining, need - have, lastGainUpdate);
            }
        }

        list.add(new Line("§6Skill: §a" + activeSkill.displayName + " §8" + level + "➜§3" + targetLevel));
        if (useCustomGoalLevel) list.add(new Line("§7Needed XP: §e" + SkillApi.addSeparators(remaining)));

        float xpInterp = xpInfo.xpGainHour;
        if (have > need) {
            list.add(new Line("§7In §cIncrease level cap!"));
        } else if (xpInfo.xpGainHour < 1000) {
            list.add(new Line("§7In §cN/A"));
        } else {
            long duration = remaining * 1000 * 60 * 60 / (long) xpInterp;
            list.add(new Line("§7In §b" + formatDuration(duration, TimeUnit.DAY) + " " + (xpInfo.isActive ? "" : "§c(PAUSED)")));
        }

        if (xpInfo.xpGainLast == xpInfo.xpGainHour && xpInfo.xpGainHour <= 0) {
            list.add(new Line("§7XP/h: §cN/A"));
        } else {
            xpInterp = interpolate(xpInfo.xpGainHour, xpInfo.xpGainLast, lastGainUpdate);
            list.add(new Line("§7XP/h: §e" + SkillApi.addSeparators((long) xpInterp) + " " + (xpInfo.isActive ? "" : "§c(PAUSED)")));
        }

        String session = formatDuration(xpInfo.timeActive * 1000L, TimeUnit.HOUR);
        list.add(new Line("§7Session: §e" + session + " " + (xpInfo.sessionTimerActive ? "" : "§c(PAUSED)"),
            List.of("§eClick to reset!"), () -> {
                xpInfo.sessionTimerActive = false;
                xpInfo.timeActive = 0L;
                SkillApi.chat("Timer for §b" + activeSkill.displayName + " §ehas been reset!");
                updateDisplay();
                update();
            }));
        return list;
    }

    private static List<Object> drawDisplay() {
        List<Object> list = new ArrayList<>();
        FeatureConfigs.SkillProgress c = config();
        SkillType activeSkill = SkillApi.activeSkill;
        if (c == null || activeSkill == null) return list;
        SkillApi.SkillInfo skill = SkillApi.storage().get(activeSkill);
        if (skill == null) return list;
        boolean useCustomGoalLevel = skill.customGoalLevel != 0 && skill.customGoalLevel > skill.overflowLevel;

        SkillApi.SkillLevel l;
        if (useCustomGoalLevel && c.customGoalConfig.enableInDisplay) l = customGoalProgress(skill);
        else if (c.overflowConfig.enableInDisplay)
            l = new SkillApi.SkillLevel(skill.overflowLevel, skill.overflowCurrentXp, skill.overflowCurrentXpMax, skill.overflowTotalXp);
        else l = new SkillApi.SkillLevel(skill.level, skill.currentXp, skill.currentXpMax, skill.totalXp);
        long currentXP = l.xpCurrent();
        long currentXPMax = l.xpForNext();

        if (c.showLevel) list.add("§9[§d" + l.level() + "§9] ");
        if (c.useIcon) list.add(activeSkill.item());

        StringBuilder text = new StringBuilder();
        text.append("§b+").append(skill.lastGain).append(" ");
        if (c.useSkillName) text.append(activeSkill.displayName).append(" ");

        long barCurrent, barMax;
        if (useCustomGoalLevel && c.customGoalConfig.enableInProgressBar) {
            barCurrent = currentXP;
            barMax = currentXPMax;
        } else if (c.overflowConfig.enableInProgressBar) {
            barCurrent = skill.overflowCurrentXp;
            barMax = skill.overflowCurrentXpMax;
        } else {
            barCurrent = skill.currentXp;
            barMax = skill.currentXpMax;
        }
        float barPercent = barMax == 0L ? 100F : 100F * barCurrent / barMax;
        skillExpPercentage = barPercent / 100.0;

        float percent = currentXPMax == 0L ? 100F : 100F * currentXP / currentXPMax;
        if (c.usePercentage) {
            text.append("§7(§6").append(roundTo(percent, 2)).append("%§7)");
        } else if (currentXPMax == 0L) {
            text.append("§7(§6").append(SkillApi.addSeparators(currentXP)).append("§7)");
        } else {
            text.append("§7(§6").append(SkillApi.addSeparators(currentXP)).append("§7/§6").append(SkillApi.addSeparators(currentXPMax)).append("§7)");
        }
        if (c.showActionLeft && percent != 100f) text.append(" - ").append(addActionsLeft(skill, currentXPMax, currentXP));
        list.add(text.toString());
        return list;
    }

    private static String addActionsLeft(SkillApi.SkillInfo skill, long currentXPMax, long currentXP) {
        if (!skill.lastGain.isEmpty()) {
            try {
                double gain = SkillApi.formatDouble(skill.lastGain);
                long actionLeft = (long) (Math.ceil(currentXPMax - (double) currentXP) / gain) + 1;
                String formatted = SkillApi.addSeparators(actionLeft);
                if (!formatted.contains("-")) return "§6" + formatted + " Left";
            } catch (Exception ignored) {
            }
        }
        return "§6∞ Left";
    }

    private static void updateSkillInfo() {
        SkillType activeSkill = SkillApi.activeSkill;
        if (activeSkill == null) return;
        SkillApi.SkillXPInfo xpInfo = SkillApi.skillXPInfoMap.computeIfAbsent(activeSkill, k -> new SkillApi.SkillXPInfo());
        SkillApi.SkillInfo skillInfo = SkillApi.storage().get(activeSkill);
        if (skillInfo == null) return;
        SkillApi.oldSkillInfoMap.put(activeSkill, skillInfo);
        long totalXP = skillInfo.currentXp;
        if (xpInfo.lastTotalXP > 0) {
            float delta = totalXP - xpInfo.lastTotalXP;
            if (delta > 0) {
                xpInfo.timer = SkillApi.pauseTime(activeSkill, 3);
                xpInfo.xpGainQueue.addFirst(delta);
                calculateXPHour(xpInfo);
            } else if (xpInfo.timer > 0) {
                xpInfo.timer--;
                xpInfo.xpGainQueue.addFirst(0f);
                calculateXPHour(xpInfo);
            } else {
                xpInfo.isActive = false;
            }
        }
        xpInfo.lastTotalXP = totalXP;
    }

    private static void calculateXPHour(SkillApi.SkillXPInfo xpInfo) {
        while (xpInfo.xpGainQueue.size() > 30) xpInfo.xpGainQueue.removeLast();
        float totalGain = 0;
        for (float f : xpInfo.xpGainQueue) totalGain += f;
        xpInfo.xpGainHour = totalGain * (60 * 60) / xpInfo.xpGainQueue.size();
        xpInfo.isActive = true;
    }

    private static boolean isDisplayEnabled() {
        FeatureConfigs.SkillProgress c = config();
        return c != null && c.enabled && Sky2MLocation.onSkyblock();
    }

    // ---------------------------------------------------------------------------------------------- rendering

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static boolean inMenu() {
        return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>;
    }

    /** The progress display: level, icon and XP in a row, aligned over the bar. */
    private static final class DisplayHud implements Sky2MHuds.CustomHud {
        private List<Object> parts(boolean preview) {
            if (preview && display.isEmpty()) return List.of("§9[§d47§9] ", SkillType.FARMING.item(), "§b+12.5 §7(§61,234,567§7/§62,600,000§7)");
            return display;
        }

        private int contentWidth(List<Object> parts) {
            int w = 0;
            for (Object p : parts) w += p instanceof ItemStack ? 16 : font().width(Component.literal((String) p));
            return w;
        }

        private int height(List<Object> parts) {
            for (Object p : parts) if (p instanceof ItemStack) return 16;
            return 9;
        }

        @Override
        public int width() {
            List<Object> parts = parts(true);
            FeatureConfigs.SkillProgress c = config();
            if (c == null || c.textAlignmentProperty == FeatureConfigs.SkillProgress.TextAlignment.NONE) return contentWidth(parts);
            return Math.max(maxWidth, contentWidth(parts));
        }

        @Override
        public int height() {
            return height(parts(true));
        }

        @Override
        public boolean visible() {
            return isDisplayEnabled() && !display.isEmpty() && SkillApi.showDisplay;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, boolean preview) {
            List<Object> parts = parts(preview);
            FeatureConfigs.SkillProgress c = config();
            int contentW = contentWidth(parts);
            int h = height(parts);
            int x = 0;
            if (c != null && c.textAlignmentProperty.alignment != null) {
                int box = Math.max(maxWidth, contentW);
                x = switch (c.textAlignmentProperty.alignment) {
                    case 0 -> (box - contentW) / 2;
                    case 1 -> box - contentW;
                    default -> 0;
                };
            }
            for (Object p : parts) {
                if (p instanceof ItemStack stack) {
                    graphics.item(stack, x, (h - 16) / 2);
                    x += 16;
                } else {
                    Component text = Component.literal((String) p);
                    graphics.text(font(), text, x, (h - 8) / 2, 0xFFFFFFFF, true);
                    x += font().width(text);
                }
            }
        }
    }

    /** The progress bar: plain (its colour or chroma) or textured like the XP bar. */
    private static final class BarHud implements Sky2MHuds.CustomHud {
        @Override
        public int width() {
            FeatureConfigs.SkillProgressBar bar = config().skillProgressBarConfig;
            return bar.useTexturedBar ? 182 : bar.regularBar.width;
        }

        @Override
        public int height() {
            FeatureConfigs.SkillProgressBar bar = config().skillProgressBarConfig;
            return bar.useTexturedBar ? 5 : bar.regularBar.height;
        }

        @Override
        public boolean visible() {
            return isDisplayEnabled() && !display.isEmpty() && SkillApi.showDisplay;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, boolean preview) {
            FeatureConfigs.SkillProgressBar bar = config().skillProgressBarConfig;
            double fraction = preview && display.isEmpty() ? 0.457 : Math.min(1.0, skillExpPercentage);
            int start = colour(bar.barStartColor);
            if (bar.useTexturedBar) {
                maxWidth = 182;
                int progress = (int) (Math.max(0, fraction) * 182);
                renderTextured(graphics, bar.texturedBar.usedTexture, progress, bar.useChroma);
            } else {
                maxWidth = bar.regularBar.width;
                int width = bar.regularBar.width;
                int height = bar.regularBar.height;
                int progress = (int) (1.0 + Math.max(0, fraction) * (width - 2.0));
                graphics.fill(0, 0, width, height, 0xFF43464B);
                int bg = bar.useChroma ? darker(0xFF404040, 0.2) : darker(start, 0.2);
                graphics.fill(1, 1, width - 1, height - 1, bg);
                if (bar.useChroma) {
                    for (int x = 1; x < progress; x++) graphics.fill(x, 1, x + 1, height - 1, chroma(x));
                } else {
                    graphics.fill(1, 1, progress, height - 1, start);
                }
            }
        }

        private void renderTextured(GuiGraphicsExtractor graphics, FeatureConfigs.SkillProgressBar.TexturedBar.UsedTexture texture,
                                    int progress, boolean useChroma) {
            if (texture == FeatureConfigs.SkillProgressBar.TexturedBar.UsedTexture.MATCH_PACK) {
                Identifier background = Identifier.withDefaultNamespace("hud/experience_bar_background");
                Identifier fill = Identifier.withDefaultNamespace("hud/experience_bar_progress");
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, background, 0, 0, 182, 5);
                if (useChroma) {
                    for (int x = 0; x < progress; x++) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, fill, 182, 5, x, 0, x, 0, 1, 5, chroma(x));
                } else if (progress > 0) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, fill, 182, 5, 0, 0, 0, 0, progress, 5);
                }
            } else {
                Identifier id = Identifier.parse(texture.path);
                graphics.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, 182, 5, 182, 5, 256, 256, -1);
                if (useChroma) {
                    for (int x = 0; x < progress; x++) graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, 0, x, 5f, 1, 5, 1, 5, 256, 256, chroma(x));
                } else if (progress > 0) {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 5f, progress, 5, progress, 5, 256, 256, -1);
                }
            }
        }
    }

    /** The ETA and all skills displays: lines with hover tips and clicks. */
    private static final class LinesHud implements Sky2MHuds.CustomHud {
        private final String id;
        private final java.util.function.Supplier<List<Line>> lines;
        private final List<Line> preview;

        LinesHud(String id, java.util.function.Supplier<List<Line>> lines, List<Line> preview) {
            this.id = id;
            this.lines = lines;
            this.preview = preview;
        }

        private List<Line> current(boolean preview) {
            List<Line> l = lines.get();
            return preview && l.isEmpty() ? this.preview : l;
        }

        @Override
        public int width() {
            int w = 0;
            for (Line line : current(true)) w = Math.max(w, font().width(Component.literal(line.text())));
            return Math.max(1, w);
        }

        @Override
        public int height() {
            return Math.max(1, current(true).size() * 10 - 1);
        }

        @Override
        public boolean visible() {
            // Above menus it's drawn by the menu screen itself (SkyHanni shows these over inventories).
            boolean shown = isDisplayEnabled() && !display.isEmpty() && !lines.get().isEmpty();
            if (!shown) REGIONS.remove(id);
            return shown && !inMenu();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, boolean preview) {
            List<Line> l = current(preview);
            Sky2MHuds.Placement p = Sky2MHuds.placement(id);
            int hudX = Sky2MHuds.mapX(p.x, Math.round(width() * p.scale));
            int hudY = Sky2MHuds.mapY(p.y, Math.round(height() * p.scale));
            List<Region> regions = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                Line line = l.get(i);
                Component text = Component.literal(line.text());
                graphics.text(font(), text, 0, i * 10, 0xFFFFFFFF, true);
                if (!preview && (!line.tips().isEmpty() || line.onClick() != null)) {
                    int w = font().width(text);
                    regions.add(new Region(hudX, Math.round(hudY + i * 10 * p.scale), Math.round(hudX + w * p.scale),
                        Math.round(hudY + (i * 10 + 9) * p.scale), line.tips(), line.onClick()));
                }
            }
            if (!preview) REGIONS.put(id, regions);
        }
    }

    private static void afterScreen(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (screen instanceof AbstractContainerScreen<?> && isDisplayEnabled() && !display.isEmpty()) {
            // SkyHanni draws the ETA display in chest menus and the all skills display above every menu.
            for (Sky2MHuds.Element element : Sky2MHuds.elements()) {
                boolean eta = element.id().equals(ETA_ID) && config().skillETADisplayConfig.enabled && !etaDisplay.isEmpty();
                boolean all = element.id().equals(ALL_ID) && config().allSkillDisplayConfig.enabled && !allDisplay.isEmpty();
                if (eta || all) Sky2MHuds.renderCustom(graphics, element, false);
            }
        }
        for (Region r : regions()) {
            if (mouseX >= r.x1() && mouseX < r.x2() && mouseY >= r.y1() && mouseY < r.y2() && !r.tips().isEmpty()) {
                List<Component> tips = new ArrayList<>();
                for (String tip : r.tips()) tips.add(Component.literal(tip));
                graphics.setTooltipForNextFrame(font(), tips, java.util.Optional.empty(), mouseX, mouseY);
                break;
            }
        }
    }

    private static boolean click(double mouseX, double mouseY) {
        for (Region r : regions()) {
            if (r.onClick() != null && mouseX >= r.x1() && mouseX < r.x2() && mouseY >= r.y1() && mouseY < r.y2()) {
                r.onClick().run();
                return true;
            }
        }
        return false;
    }

    private static void sendCommand(String command) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.sendCommand(command);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static int colour(String chroma) {
        try {
            return 0xFF000000 | com.epic60869.sky2m.custom.util.ChromaColours.parse(chroma).getEffectiveColourRGB();
        } catch (Exception e) {
            return 0xFFFF0000;
        }
    }

    private static int darker(int argb, double factor) {
        Color c = new Color(argb, true);
        int r = (int) Math.max(c.getRed() * (1 - factor), 0);
        int g = (int) Math.max(c.getGreen() * (1 - factor), 0);
        int b = (int) Math.max(c.getBlue() * (1 - factor), 0);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** SBA-like chroma: the hue moves along the bar over time. */
    private static int chroma(int x) {
        float time = (System.currentTimeMillis() % 4000L) / 4000f;
        float hue = (x / 182f) - time;
        return 0xFF000000 | (Color.HSBtoRGB(hue - (float) Math.floor(hue), 0.8f, 0.9f) & 0xFFFFFF);
    }

    /** SkyHanni's NumberUtil.interpolate. */
    private static float interpolate(float now, float last, long lastUpdate) {
        float interp = now;
        if (last >= 0 && last != now) {
            float factor = (System.currentTimeMillis() - lastUpdate) / 1000f;
            factor = Math.max(0f, Math.min(1f, factor));
            interp = last + (now - last) * factor;
        }
        return interp;
    }

    /** Kotlin's Float.roundTo(2).toString(): "45.7", "100.0". */
    private static String roundTo(float value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Float.toString((float) (Math.round(value * scale) / scale));
    }

    private enum TimeUnit {
        YEAR(31_536_000_000L, "y"), DAY(86_400_000L, "d"), HOUR(3_600_000L, "h"), MINUTE(60_000L, "m"), SECOND(1000L, "s");

        final long factor;
        final String shortName;

        TimeUnit(long factor, String shortName) {
            this.factor = factor;
            this.shortName = shortName;
        }
    }

    /** SkyHanni's Duration.format(biggestUnit). */
    private static String formatDuration(long durationMs, TimeUnit biggestUnit) {
        if (durationMs < 0) return "Soon";
        long millis = durationMs;
        Map<TimeUnit, Long> parts = new EnumMap<>(TimeUnit.class);
        for (TimeUnit unit : TimeUnit.values()) {
            if (unit.ordinal() >= biggestUnit.ordinal()) {
                parts.put(unit, millis / unit.factor);
                millis %= unit.factor;
            }
        }
        if (durationMs < 1000) return "0." + (millis / 100) + "s";
        StringBuilder result = new StringBuilder();
        for (Map.Entry<TimeUnit, Long> e : parts.entrySet()) {
            if (e.getValue() != 0) result.append(String.format(Locale.US, "%,d", e.getValue())).append(e.getKey().shortName).append(" ");
        }
        return result.toString().trim();
    }
}
