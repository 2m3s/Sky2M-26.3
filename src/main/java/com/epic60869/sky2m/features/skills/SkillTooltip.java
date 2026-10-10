package com.epic60869.sky2m.features.skills;

import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.ListIterator;

/**
 * SkyHanni's SkillTooltip (https://github.com/hannibal002/SkyHanni, LGPL-2.1): overflow levels and custom goal
 * progress in the Your Skills menu's tooltips.
 */
final class SkillTooltip {
    private SkillTooltip() {}

    static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!Sky2MLocation.onSkyblock()) return;
            try {
                onToolTip(stack, lines);
            } catch (Exception ignored) {
            }
        });
    }

    private static void onToolTip(ItemStack stack, List<Component> toolTip) {
        FeatureConfigs.SkillProgress config = SkillProgress.config();
        if (config == null) return;
        var screen = Minecraft.getInstance().gui.screen();
        String inventoryName = screen == null ? "" : screen.getTitle().getString();
        if (!inventoryName.equals("Your Skills") || SkillApi.lore(stack).stream().noneMatch(l -> l.contains("Click to view!"))) return;

        FeatureConfigs.SkillOverflow overflowConfig = config.overflowConfig;
        FeatureConfigs.SkillCustomGoal customGoalConfig = config.customGoalConfig;
        String[] split = Sky2MLocation.strip(stack.getHoverName().getString()).split(" ");
        SkillType skill = SkillType.getByNameOrNull(split[0]);
        if (skill == null) return;
        boolean useRoman = SkillApi.isRoman(split[split.length - 1]);
        SkillApi.SkillInfo skillInfo = SkillApi.storage().get(skill);
        if (skillInfo == null) return;
        boolean showCustomGoal = skillInfo.customGoalLevel != 0 && customGoalConfig.enableInSkillMenuTooltip;
        boolean next = false;
        String newName = null;
        String bar = "                    ";
        ListIterator<Component> iterator = toolTip.listIterator();
        while (iterator.hasNext()) {
            String line = iterator.next().getString();
            if (line.contains("Max Skill level reached!") && overflowConfig.enableInSkillMenuTooltip) {
                double progress = (double) skillInfo.overflowCurrentXp / skillInfo.overflowCurrentXpMax * 100;
                String percent = "§e" + roundTo1(progress) + "%";
                int currentLevel = skillInfo.overflowLevel;
                String level = useRoman ? SkillApi.toRoman(currentLevel) : String.valueOf(currentLevel);
                String nextLevel = useRoman ? SkillApi.toRoman(currentLevel + 1) : String.valueOf(currentLevel + 1);
                iterator.set(Component.literal("§7Progress to Level " + nextLevel + ": " + percent));
                newName = "§a" + skill.displayName + " " + level;
                next = true;
                continue;
            }
            if (next && overflowConfig.enableInSkillMenuTooltip && line.contains(bar)) {
                double progress = (double) skillInfo.overflowCurrentXp / skillInfo.overflowCurrentXpMax;
                iterator.set(Component.literal(progressBar(progress) + " §e" + SkillApi.addSeparators(skillInfo.overflowCurrentXp)
                    + "§6/§e" + SkillApi.addSeparators(skillInfo.overflowCurrentXpMax)));
                iterator.add(Component.empty());
            }
            if ((line.contains(bar) || line.contains("/")) && showCustomGoal) {
                int targetLevel = skillInfo.customGoalLevel;
                long have = skillInfo.totalXp;
                long need = SkillApi.xpRequiredForLevel(targetLevel);
                double progress = (double) have / need;
                String nextLevel = useRoman ? SkillApi.toRoman(targetLevel) : String.valueOf(targetLevel);
                String percent = "§e" + roundTo1(progress * 100) + "%";
                iterator.add(Component.empty());
                iterator.add(Component.literal("§7Progress to Level " + nextLevel + ": " + percent));
                iterator.add(Component.literal(progressBar(progress) + " §e" + SkillApi.addSeparators(have) + "§6/§e" + SkillApi.addSeparators(need)));
                iterator.add(Component.empty());
            }
            if (next && overflowConfig.enableInSkillMenuTooltip && line.contains(bar)) {
                iterator.add(Component.literal("§b§lOVERFLOW XP:"));
                iterator.add(Component.literal("§7▸ " + SkillApi.addSeparators(skillInfo.overflowTotalXp)));
            }
        }
        if (newName != null && !toolTip.isEmpty()) toolTip.set(0, Component.literal(newName));
    }

    private static String roundTo1(double value) {
        return Double.toString(Math.round(value * 10) / 10.0);
    }

    /** SkyHanni's StringUtils.progressBar: a struck-through bar, green up to the progress and white after. */
    private static String progressBar(double percentage) {
        int steps = 24;
        StringBuilder builder = new StringBuilder("§5§o§2");
        boolean inMissingArea = false;
        for (int i = 0; i <= steps; i++) {
            double stepPercentage = (double) i / steps;
            if (stepPercentage >= percentage && !inMissingArea) {
                builder.append("§f");
                inMissingArea = true;
            }
            builder.append("§l§m ");
        }
        builder.append("§r");
        return builder.toString();
    }
}
