// Mixins supporting Sky2M's port of Skyblocker's dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.mixins.accessors;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.level.saveddata.maps.MapDecoration;

@Mixin(MapRenderer.class)
public interface MapRendererInvoker {
	@Invoker
	MapRenderState.MapDecorationRenderState invokeExtractDecorationRenderState(MapDecoration mapDecoration);
}
