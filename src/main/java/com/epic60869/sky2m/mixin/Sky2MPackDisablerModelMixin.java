package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.PackDisabler;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Pack Disabler's hook: appendItemLayers starts by reading the stack's item model into its only Identifier local, and
 * swapping that value changes the model everywhere an item is drawn (slots, hand, dropped, item frames) without copying
 * the stack. From Killer560's Mod (MIT).
 */
@Mixin(ItemModelResolver.class)
public abstract class Sky2MPackDisablerModelMixin {
    @ModifyVariable(method = "appendItemLayers", at = @At("STORE"), ordinal = 0)
    private Identifier sky2m$packDisabler(Identifier model, ItemStackRenderState state, ItemStack stack,
                                             ItemDisplayContext context, Level level, ItemOwner owner, int seed) {
        return PackDisabler.model(stack, model);
    }
}
