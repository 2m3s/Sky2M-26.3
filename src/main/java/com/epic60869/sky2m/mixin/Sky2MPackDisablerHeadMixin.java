package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.PackDisabler;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When Pack Disabler draws an item as a player head (its old look), the head renderer reads the skin from the stack's
 * profile, which an item from Hypixel's pack no longer has: this gives it the old skin. From Killer560's Mod (MIT).
 */
@Mixin(PlayerHeadSpecialRenderer.class)
public abstract class Sky2MPackDisablerHeadMixin {
    @ModifyVariable(method = "extractArgument(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;",
        at = @At("STORE"), ordinal = 0)
    private ResolvableProfile sky2m$packDisablerSkin(ResolvableProfile profile, ItemStack stack) {
        return PackDisabler.profile(stack, profile);
    }
}
