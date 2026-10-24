package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.SignAutofill;
import com.epic60869.sky2m.features.misc.SignCalculator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Misc > Sign Calculator ({@link SignCalculator}): the value above SkyBlock's number signs, and sending it.
 * Misc > Sign Autofill ({@link SignAutofill}): the suggested bazaar/auction price or amount, filled in and selected.
 * Misc > Enter Confirms Signs: Enter sends the sign like Done instead of moving to the next line.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class Sky2MSignEditMixin extends Screen {
    @Shadow @Final private String[] messages;
    @Shadow private TextFieldHelper signField;

    @Shadow
    private void setMessage(String message) {}

    @Shadow
    private void onDone() {}

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

    // The menu that opened the sign is still the current screen while the sign screen is built.
    @Inject(method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;ZLnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
    private void sky2m$prepareAutofill(SignBlockEntity sign, net.minecraft.world.level.block.entity.SignTextSlot slot, boolean filtered, Component title, CallbackInfo ci) {
        SignAutofill.onSignCreated(messages);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void sky2m$autofill(CallbackInfo ci) {
        String suggestion = SignAutofill.takeSuggestion();
        if (suggestion == null || !messages[0].isBlank() || signField == null) return;
        setMessage(suggestion);
        signField.selectAll();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sky2m$enterConfirms(net.minecraft.client.input.KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        com.epic60869.sky2m.Sky2MConfig c = com.epic60869.sky2m.Sky2MConfig.current();
        if (c == null || !c.inventory.signEnterConfirms || !event.isConfirmation() || !com.epic60869.sky2m.custom.util.Compat.isOnSkyblock()) return;
        onDone();
        cir.setReturnValue(true);
    }

    // After sky2m$sendValue, so a typed sum is already worked out.
    @Inject(method = "onDone", at = @At("HEAD"))
    private void sky2m$rememberAmount(CallbackInfo ci) {
        SignAutofill.onSignDone(messages);
    }
}
