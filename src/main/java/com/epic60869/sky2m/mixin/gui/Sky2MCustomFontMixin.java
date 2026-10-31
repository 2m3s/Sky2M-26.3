package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.features.misc.visual.CustomFonts;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/** Misc > Fonts: the chosen font in front of Minecraft's default font ({@link CustomFonts}). */
@Mixin(FontManager.class)
public abstract class Sky2MCustomFontMixin {
    @ModifyReturnValue(method = "resolveProviders", at = @At("RETURN"))
    private Map<Identifier, List<GlyphProvider.Conditional>> sky2m$customFont(Map<Identifier, List<GlyphProvider.Conditional>> fonts) {
        return CustomFonts.apply(fonts);
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void sky2m$afterApply(CallbackInfo ci) {
        CustomFonts.afterApply();
    }
}
