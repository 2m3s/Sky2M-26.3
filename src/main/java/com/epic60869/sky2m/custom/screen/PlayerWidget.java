// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom.screen;

import com.epic60869.sky2m.custom.util.Compat;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import com.epic60869.sky2m.mixin.Sky2MInventoryScreenInvoker;

public class PlayerWidget extends AbstractWidget {
	private static final Identifier INNER_SPACE_TEXTURE = Compat.id("menu_inner_space");
	private static final float FLIP_ROTATION = (float) Math.PI;
	private final AbstractClientPlayer player;

	private float xRotation = -10;
	private float yRotation = 225;

	public PlayerWidget(int x, int y, int width, int height, AbstractClientPlayer player) {
		super(x, y, width, height, Component.literal(""));
		this.player = player;
		this.player.yHeadRot = this.player.yHeadRotO = 0;
	}

	@Override
	protected void onDrag(MouseButtonEvent click, double offsetX, double offsetY) {
		super.onDrag(click, offsetX, offsetY);
		this.xRotation = Mth.clamp(this.xRotation - (float) offsetY * 2.5f, -50.0f, 50.0f);
		this.yRotation += (float) offsetX * 2.5f;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, INNER_SPACE_TEXTURE, getX(), getY(), getWidth(), getHeight());

		float size = 64f;
		Vector3f translation = new Vector3f(0, player.getBbHeight() / 2f + 0.0625f, 0);
		Quaternionf rotation = new Quaternionf().rotationXYZ(-xRotation * Mth.DEG_TO_RAD, -yRotation * Mth.DEG_TO_RAD, FLIP_ROTATION);

		EntityRenderState renderState = Sky2MInventoryScreenInvoker.invokeExtractRenderState(this.player);
		graphics.entity(renderState, size, translation, rotation, null, getX(), getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput builder) {}

	@Override
	public void playDownSound(SoundManager soundManager) {}
}
