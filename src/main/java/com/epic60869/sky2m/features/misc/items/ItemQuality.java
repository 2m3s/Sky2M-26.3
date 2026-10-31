package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Misc > Items & Tooltips > Item Quality, like SkyblockAddons' base stat boost display: dungeon drops carry their
 * quality ({@code baseStatBoostPercentage}, 0 to 50) and the floor they dropped on ({@code item_tier}) in their data.
 * Both can go on the slot (quality top left, floor top right) and in the tooltip.
 */
public final class ItemQuality {
    private ItemQuality() {}

    private static FeatureConfigs.ItemQuality config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.itemQuality;
    }

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            FeatureConfigs.ItemQuality c = config();
            if (c == null || !c.tooltip || lines.isEmpty()) return;
            int quality = quality(stack);
            if (quality < 0) return;
            int tier = tier(stack);
            lines.add(1, Component.literal("Item Quality: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(quality + "%").withStyle(colour(quality)))
                .append(Component.literal(tier >= 0 ? " (" + floor(tier) + ")" : "").withStyle(ChatFormatting.DARK_GRAY)));
        });
    }

    /** The item's quality, or -1 when it has none. */
    private static int quality(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        CompoundTag data = Compat.customDataView(stack);
        return data == null || !data.contains("baseStatBoostPercentage") ? -1 : data.getIntOr("baseStatBoostPercentage", -1);
    }

    private static int tier(ItemStack stack) {
        CompoundTag data = Compat.customDataView(stack);
        return data == null || !data.contains("item_tier") ? -1 : data.getIntOr("item_tier", -1);
    }

    private static String floor(int tier) {
        return tier == 0 ? "E" : "F" + tier;
    }

    private static ChatFormatting colour(int quality) {
        if (quality >= 50) return ChatFormatting.GOLD;
        if (quality >= 40) return ChatFormatting.GREEN;
        if (quality >= 25) return ChatFormatting.YELLOW;
        if (quality >= 10) return ChatFormatting.RED;
        return ChatFormatting.DARK_RED;
    }

    /** From the slot mixin, after the item is drawn. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        FeatureConfigs.ItemQuality c = config();
        if (c == null || (!c.slot && !c.floor) || !slot.hasItem()) return;
        ItemStack stack = slot.getItem();
        int quality = quality(stack);
        if (quality < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (c.slot && c.stackSize) {
            String text = String.valueOf(quality);
            graphics.text(mc.font, text, slot.x + 17 - mc.font.width(text), slot.y + 9,
                0xFF000000 | net.minecraft.network.chat.TextColor.fromLegacyFormat(colour(quality)).getValue(), true);
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(slot.x, slot.y);
        graphics.pose().scale(0.6f, 0.6f);
        if (c.slot && !c.stackSize) {
            ChatFormatting colour = colour(quality);
            graphics.text(mc.font, String.valueOf(quality), 0, 0, 0xFF000000 | net.minecraft.network.chat.TextColor.fromLegacyFormat(colour).getValue(), true);
        }
        int tier = tier(stack);
        if (c.floor && tier >= 0) {
            String text = floor(tier);
            graphics.text(mc.font, text, (int) (16 / 0.6f) - mc.font.width(text), 0, 0xFFAAAAAA, true);
        }
        graphics.pose().popMatrix();
    }
}
