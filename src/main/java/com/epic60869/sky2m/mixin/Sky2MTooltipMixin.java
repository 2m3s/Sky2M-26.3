package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.ScrollableTooltips;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Misc > Tooltip Scroll: wraps the tooltip positioner so the tooltip is moved by the scroll pan (Skysoft's approach). */
@Mixin(GuiGraphicsExtractor.class)
public abstract class Sky2MTooltipMixin {
    @WrapOperation(method = "tooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;positionTooltip(IIIIII)Lorg/joml/Vector2ic;"))
    private Vector2ic sky2m$scrollTooltip(ClientTooltipPositioner positioner, int screenWidth, int screenHeight, int x, int y, int width, int height, Operation<Vector2ic> original, @Local(argsOnly = true) List<ClientTooltipComponent> lines) {
        ClientTooltipPositioner scrolling = positioner;
        try {
            scrolling = ScrollableTooltips.decorate(lines, x, y, positioner);
        } catch (Throwable t) {
            System.err.println("[Sky2M] Tooltip scroll failed: " + t);
        }
        return original.call(scrolling, screenWidth, screenHeight, x, y, width, height);
    }
}
