package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Reforge helper and anvil combine helper. In the Reforge menu the item's current reforge is added to its tooltip, and
 * clicks on the apply button are blocked when the item has a reforge from the protected list (Shift goes through).
 * In the Anvil, the value of both items and of the result is shown above the window.
 */
final class ReforgeAnvil {
    private static final int ITEM_SLOT = 13;
    private static final int ANVIL_LEFT = 29;
    private static final int ANVIL_RIGHT = 33;
    private static final int ANVIL_RESULT = 13;

    private ReforgeAnvil() {}

    static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            String title = Economy.screenTitle(screen);
            if (title.startsWith("Reforge")) {
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !blocked(container));
            } else if (title.equals("Anvil")) {
                ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                    EconomyConfig c = Economy.config();
                    List<Slot> slots = container.getMenu().slots;
                    if (c == null || !c.anvilHelper || !Compat.isOnSkyblock() || slots.size() <= ANVIL_RIGHT) return;
                    double left = ValueBreakdown.total(slots.get(ANVIL_LEFT).getItem());
                    double right = ValueBreakdown.total(slots.get(ANVIL_RIGHT).getItem());
                    ItemStack resultStack = slots.get(ANVIL_RESULT).getItem();
                    double result = ValueBreakdown.total(resultStack);
                    if (left <= 0 && right <= 0) return;
                    var access = (Sky2MContainerScreenAccessor) container;
                    var font = Minecraft.getInstance().font;
                    Component line = Component.literal("Anvil: ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(ValueBreakdown.coins(left) + " + " + ValueBreakdown.coins(right)).withStyle(ChatFormatting.YELLOW));
                    if (result > 0) {
                        double diff = result - left - right;
                        line = line.copy().append(Component.literal(" -> " + ValueBreakdown.coins(result)).withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(String.format(Locale.ENGLISH, " (%+,.0f)", diff)).withStyle(diff >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
                    }
                    int x = access.sky2m$getLeftPos();
                    int y = access.sky2m$getTopPos() - 14;
                    g.fill(x - 2, y - 2, x + font.width(line) + 4, y + 11, 0xB0101216);
                    g.text(font, line, x + 1, y + 1, 0xFFFFFFFF, true);
                });
            }
        });
    }

    /** The reforge ("modifier") on an item, lower case, or "". */
    private static String reforge(ItemStack stack) {
        return Compat.customDataView(stack).getStringOr("modifier", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isProtected(String reforge) {
        EconomyConfig c = Economy.config();
        if (c == null || reforge.isEmpty()) return false;
        for (String name : c.protectedReforges.split(",")) {
            if (name.trim().toLowerCase(Locale.ROOT).equals(reforge)) return true;
        }
        return false;
    }

    /** Whether the click about to happen is on the reforge button while a protected reforge would be replaced. */
    private static boolean blocked(AbstractContainerScreen<?> container) {
        EconomyConfig c = Economy.config();
        if (c == null || !c.reforgeBlock || !Compat.isOnSkyblock() || Economy.shiftDown()) return false;
        Slot hovered = ((Sky2MContainerScreenAccessor) container).sky2m$getHoveredSlot();
        if (hovered == null || hovered.container instanceof net.minecraft.world.entity.player.Inventory || !hovered.hasItem()) return false;
        String name = ChatFormatting.stripFormatting(hovered.getItem().getHoverName().getString());
        if (name == null || !name.contains("Reforge Item")) return false;
        List<Slot> slots = container.getMenu().slots;
        if (slots.size() <= ITEM_SLOT || !isProtected(reforge(slots.get(ITEM_SLOT).getItem()))) return false;
        Economy.message(Component.literal("Blocked: this item has a protected reforge. Hold Shift to reforge anyway.").withStyle(ChatFormatting.RED));
        return true;
    }

    static void tooltip(ItemStack stack, List<Component> lines) {
        EconomyConfig c = Economy.config();
        if (c == null || !c.reforgeHelper) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container) || !Economy.screenTitle(screen).startsWith("Reforge")) return;
        List<Slot> slots = container.getMenu().slots;
        if (slots.size() <= ITEM_SLOT || slots.get(ITEM_SLOT).getItem() != stack) return;
        String reforge = reforge(stack);
        if (reforge.isEmpty()) return;
        lines.add(Component.literal("Current reforge: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(reforge).withStyle(isProtected(reforge) ? ChatFormatting.RED : ChatFormatting.GREEN)));
    }
}
