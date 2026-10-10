package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.dungeons.CaseOpening;
import com.epic60869.sky2m.features.dungeons.OdinTerminals;
import com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menus that Sky2M draws over completely: the chest itself (items, texture, inventory, tooltips) isn't drawn.
 * Terminals > Hide Menu draws only the terminal solver; Case Opening hides the reward chest while the case spins.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MHideMenuMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (CaseOpening.hidesMenu(screen)) {
            ci.cancel();
            return;
        }
        if (!OdinTerminals.hidesMenu(screen)) return;
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        OdinTerminals.renderOwn(graphics, screen, accessor.getX(), accessor.getY());
        ci.cancel();
    }
}
