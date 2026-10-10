package com.epic60869.sky2m.mixin;

import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ModelManager.class)
public interface Sky2MModelManagerAccessor {
    @Accessor("bakedItemStackModels")
    Map<Identifier, ItemModel> sky2m$getBakedItemStackModels();
}
