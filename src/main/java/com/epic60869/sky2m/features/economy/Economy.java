package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.nio.file.Path;

/**
 * Misc > Economy: entry point and shared helpers. The features themselves are {@link ValueBreakdown},
 * {@link BazaarExtras}, {@link TradeValue}, {@link Bits} and {@link ReforgeAnvil}.
 */
public final class Economy {
    /** Opens the Value Breakdown window for the hovered item. */
    public static KeyMapping VALUE_BREAKDOWN;

    private Economy() {}

    public static void init(Path configDir) {
        VALUE_BREAKDOWN = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.sky2m.value_breakdown", InputConstants.Type.KEYBOARD, InputConstants.KEY_J,
            com.epic60869.sky2m.Sky2MKeyMappings.CATEGORY));

        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            EconomyConfig c = config();
            if (c == null || !Compat.isOnSkyblock()) return;
            ValueBreakdown.tooltip(stack, lines);
            BazaarExtras.tooltip(stack, lines);
            Bits.tooltip(stack, lines);
            ReforgeAnvil.tooltip(stack, lines);
        });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                EconomyConfig c = config();
                if (c == null || !c.breakdownScreen || VALUE_BREAKDOWN == null || !VALUE_BREAKDOWN.matches(event)) return true;
                Slot hovered = ((Sky2MContainerScreenAccessor) container).sky2m$getHoveredSlot();
                if (hovered == null || !hovered.hasItem() || !Compat.isOnSkyblock()) return true;
                client.gui.setScreen(new ValueBreakdownScreen(container, hovered.getItem()));
                return false;
            });
        });

        BazaarExtras.init(configDir);
        TradeValue.init();
        Bits.init();
        ReforgeAnvil.init();
    }

    static EconomyConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.economy;
    }

    static String screenTitle(net.minecraft.client.gui.screens.Screen screen) {
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return title == null ? "" : title.trim();
    }

    static boolean shiftDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }

    static void message(net.minecraft.network.chat.Component text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Compat.PREFIX.get().append(text));
    }
}
