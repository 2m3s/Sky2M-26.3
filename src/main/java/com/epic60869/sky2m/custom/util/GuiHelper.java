package com.epic60869.sky2m.custom.util;

import org.joml.Matrix3x2f;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;

/**
 * The parts of Skyblocker's GuiHelper used by the customization screen. Ported from Skyblocker (LGPL-3.0).
 */
public class GuiHelper {

	public static void horizontalGradient(GuiGraphicsExtractor graphics, float startX, float startY, float endX, float endY, int colorStart, int colorEnd) {
		Compat.guiRenderState(graphics).addGuiElement(new GuiHorizontalGradientRenderState(RenderPipelines.GUI, TextureSetup.noTexture(), new Matrix3x2f(graphics.pose()), (int) startX, (int) startY, (int) endX, (int) endY, colorStart, colorEnd, Compat.scissor(graphics)));
	}

	public static void border(GuiGraphicsExtractor context, int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + 1, color);
		context.fill(x, y + height - 1, x + width, y + height, color);
		context.fill(x, y + 1, x + 1, y + height - 1, color);
		context.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
	}

	public static <S> void equipment(GuiGraphicsExtractor graphics, EquipmentLayerRenderer equipmentRenderer, EquipmentClientInfo.LayerType layerType, ResourceKey<EquipmentAsset> assetKey, Model<S> model, S state, ItemStack stack, int x1, int y1, int x2, int y2, float rotation, float scale, float offset) {
		GuiEquipmentRenderState<S> renderState = new GuiEquipmentRenderState<>(equipmentRenderer, layerType, assetKey, model, state, stack, x1, y1, x2, y2, rotation, scale, offset, Compat.scissor(graphics));

		Compat.guiRenderState(graphics).addPicturesInPictureState(renderState);
	}
}
