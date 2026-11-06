package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What your own Chest Spending History says about the reward chest in front of you.
 * <ul>
 * <li><b>Reroll Advisor</b>: on the Reroll Chest's tooltip, this chest's profit against your average for that floor and
 *     chest type less a Kismet Feather. The repo's drop tables are per floor boss and only cover the rare drops, with no
 *     per-chest odds and nothing for the common contents, so a real expected value can't be worked out: it's always
 *     labelled as your average, from the chests you chose to open.</li>
 * <li><b>Chest Luck</b>: under Dungeon Chest Value, this chest's worth against your average, like
 *     "+24% vs your M6 Bedrock average".</li>
 * </ul>
 */
public final class ChestAdvice {
    /** Fewer chests than this and an average says too little to compare a reroll with. */
    public static final int MIN_REROLL_CHESTS = 5;
    public static final int MIN_LUCK_CHESTS = 3;

    private ChestAdvice() {}

    /** Your chests: how many, their average profit before key and feather costs, and their average worth. */
    public record Average(int chests, double profit, double value) {}

    /** What a reroll looks like against this chest. {@code enough} is false when there's too little history or no feather price. */
    public record Advice(boolean enough, boolean reroll, double afterFeather) {}

    /**
     * Your average on a floor, for one chest type or all of them ({@code type} null). The profit is contents minus coins:
     * key and feather costs are left out, as this chest's profit doesn't have them either. Public for the game test.
     */
    public static Average average(List<ChestHistory.Chest> chests, String floor, @Nullable String type) {
        int count = 0;
        double profit = 0, value = 0;
        for (ChestHistory.Chest chest : chests) {
            if (!floor.equalsIgnoreCase(chest.floor) || (type != null && !type.equals(chest.type))) continue;
            count++;
            profit += chest.value - chest.coins;
            value += chest.value;
        }
        return count == 0 ? new Average(0, 0, 0) : new Average(count, profit / count, value / count);
    }

    /** Whether your average less the feather beats this chest's profit. Public for the game test. */
    public static Advice advise(double current, Average average, double featherPrice) {
        boolean enough = average.chests() >= MIN_REROLL_CHESTS && featherPrice > 0;
        double after = average.profit() - Math.max(0, featherPrice);
        return new Advice(enough, enough && after > current, after);
    }

    /**
     * "+24% vs your M6 Bedrock average", from that chest type on the floor, else every chest on the floor, or null with
     * too little history. Public for the game test.
     */
    public static @Nullable String luckText(List<ChestHistory.Chest> chests, String floor, String type, double value) {
        Average average = average(chests, floor, type);
        String label = floor + " " + type;
        if (average.chests() < MIN_LUCK_CHESTS) {
            average = average(chests, floor, null);
            label = floor;
        }
        if (average.chests() < MIN_LUCK_CHESTS || average.value() <= 0 || value <= 0) return null;
        long percent = Math.round((value / average.value() - 1) * 100);
        return (percent >= 0 ? "+" : "") + percent + "% vs your " + label + " average";
    }

    /** {@link #luckText} for the open chest: its menu's title and what its contents are worth. */
    static @Nullable String luck(String title, double value) {
        String type = ChestHistory.chestType(title);
        String floor = ChestHistory.currentFloor();
        return type == null || floor.equals("?") ? null : luckText(ChestHistory.chests(), floor, type, value);
    }

    private static FeatureConfigs.ChestProfit config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.chestProfit;
    }

    static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            FeatureConfigs.ChestProfit config = config();
            if (config == null || !config.rerollAdvisor || !DungeonChestProfit.isInLootArea()) return;
            if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
            if (!isRerollButton(stack, screen)) return;
            String type = ChestHistory.chestType(ChatFormatting.stripFormatting(screen.getTitle().getString()).trim());
            if (type != null) lines.addAll(rerollLines(DungeonChestProfit.rewardChestValue(screen), type));
        });
    }

    private static boolean isRerollButton(ItemStack stack, AbstractContainerScreen<?> screen) {
        if (!"Reroll Chest".equals(ChatFormatting.stripFormatting(stack.getHoverName().getString()))) return false;
        if (!DungeonChestProfit.lore(stack).contains("Click to reroll this chest!")) return false;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.getItem() == stack) return !(slot.container instanceof Inventory);
        }
        return false;
    }

    private static List<Component> rerollLines(double current, String type) {
        List<Component> out = new ArrayList<>();
        String floor = ChestHistory.currentFloor();
        String label = floor + " " + type;
        Average average = floor.equals("?") ? new Average(0, 0, 0) : average(ChestHistory.chests(), floor, type);
        out.add(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("Reroll Advisor").withStyle(ChatFormatting.AQUA)));
        out.add(row("This chest: ", signed(current), current >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        if (average.chests() < MIN_REROLL_CHESTS) {
            out.add(Component.literal("Not enough " + label + " chests saved yet (" + average.chests() + " of " + MIN_REROLL_CHESTS + ")").withStyle(ChatFormatting.GRAY));
            return out;
        }
        double feather = ChestHistory.price("KISMET_FEATHER");
        Advice advice = advise(current, average, feather);
        out.add(row("Your " + label + " average: ", signed(average.profit()) + " (" + average.chests() + " chests)", ChatFormatting.GOLD));
        if (!advice.enough()) {
            out.add(Component.literal("No Kismet Feather price yet").withStyle(ChatFormatting.GRAY));
            return out;
        }
        out.add(row("After a Kismet Feather: ", signed(advice.afterFeather()), ChatFormatting.GOLD));
        out.add(advice.reroll()
            ? Component.literal("A reroll beats this chest on your average").withStyle(ChatFormatting.GREEN)
            : Component.literal("This chest beats your average, keep it").withStyle(ChatFormatting.YELLOW));
        out.add(Component.literal("Your average of chests you opened, not real odds").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    private static Component row(String label, String value, ChatFormatting colour) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(colour));
    }

    private static String signed(double value) {
        return (value < 0 ? "-" : "+") + CombatFeatures.formatCoins(Math.abs(value));
    }
}
