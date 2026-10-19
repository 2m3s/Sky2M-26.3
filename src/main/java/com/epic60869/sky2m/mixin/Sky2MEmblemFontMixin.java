package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.sbc.SbcEmblems;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Image emblems: the fonts {@code sky2m:emblem/<hash>} don't exist as resources; their one glyph is the downloaded
 * emblem picture ({@link SbcEmblems}). Every other font goes to vanilla untouched.
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
public abstract class Sky2MEmblemFontMixin {
    @Inject(method = "getGlyphSource", at = @At("HEAD"), cancellable = true)
    private void sky2m$emblemFont(FontDescription description, CallbackInfoReturnable<GlyphSource> cir) {
        if (!(description instanceof FontDescription.Resource resource)) return;
        GlyphSource source = SbcEmblems.source(resource.id());
        if (source != null) cir.setReturnValue(source);
    }
}
