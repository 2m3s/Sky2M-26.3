package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.mixin.gui.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Trade value overlay: in a trade window, the Estimated Item Value of what you offer (the left four columns) and
 * what you are offered (the right four columns), and the difference. Coins in the trade are not counted.
 */
final class TradeValue {
    private static final Pattern TRADE_TITLE = Pattern.compile("^You\\s{2,}\\S+");

    private TradeValue() {}

    static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            if (!TRADE_TITLE.matcher(Economy.screenTitle(screen)).find()) return;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                EconomyConfig c = Economy.config();
                if (c == null || !c.tradeValue || !Compat.isOnSkyblock()) return;
                double mine = 0, theirs = 0;
                List<Slot> slots = container.getMenu().slots;
                for (int i = 0; i < Math.min(slots.size(), 54); i++) {
                    int col = i % 9;
                    if (col == 4 || !slots.get(i).hasItem()) continue;
                    double value = ValueBreakdown.total(slots.get(i).getItem());
                    if (col < 4) mine += value;
                    else theirs += value;
                }
                var access = (Sky2MContainerScreenAccessor) container;
                int x = access.sky2m$getLeftPos();
                int y = access.sky2m$getTopPos() - 34;
                var font = Minecraft.getInstance().font;
                Component line1 = Component.literal("You offer: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(ValueBreakdown.coins(mine)).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("   They offer: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(ValueBreakdown.coins(theirs)).withStyle(ChatFormatting.YELLOW));
                double diff = theirs - mine;
                Component line2 = Component.literal("Difference: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.format(java.util.Locale.ENGLISH, "%+,.0f", diff)).withStyle(diff >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .append(Component.literal("  (items only, not coins)").withStyle(ChatFormatting.DARK_GRAY));
                g.fill(x - 2, y - 2, x + Math.max(font.width(line1), font.width(line2)) + 4, y + 22, 0xB0101216);
                g.text(font, line1, x + 1, y + 1, 0xFFFFFFFF, true);
                g.text(font, line2, x + 1, y + 12, 0xFFFFFFFF, true);
            });
        });
    }
}
