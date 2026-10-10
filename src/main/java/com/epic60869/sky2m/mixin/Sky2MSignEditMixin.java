package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.SignCalculator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Sign Calculator ({@link SignCalculator}): the value above SkyBlock's number signs, and sending it. */
@Mixin(AbstractSignEditScreen.class)
public abstract class Sky2MSignEditMixin extends Screen {
    @Shadow @Final private String[] messages;

    protected Sky2MSignEditMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void sky2m$showValue(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!SignCalculator.enabled() || !SignCalculator.isInputSign(messages)) return;
        Component preview = SignCalculator.preview(messages[0]);
        if (preview != null) graphics.centeredText(font, preview, width / 2, 55, 0xFFFFFFFF);
    }

    @Inject(method = "onDone", at = @At("HEAD"))
    private void sky2m$sendValue(CallbackInfo ci) {
        if (!SignCalculator.enabled() || !SignCalculator.isInputSign(messages) || messages[0].isBlank()) return;
        messages[0] = SignCalculator.result(messages[0], messages[2].contains("price"));
    }
}
