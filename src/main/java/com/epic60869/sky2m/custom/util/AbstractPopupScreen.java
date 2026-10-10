// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom.util;


import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A more bare-bones version of Vanilla's Popup Screen. Meant to be extended.
 */
public class AbstractPopupScreen extends Screen {
	private static final Identifier BACKGROUND_TEXTURE = Identifier.withDefaultNamespace("popup/background");
	public final Screen backgroundScreen;

	protected AbstractPopupScreen(Component title, Screen backgroundScreen) {
		super(title);
		this.backgroundScreen = backgroundScreen;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.backgroundScreen);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.backgroundScreen.extractBackground(graphics, -1, -1, a);
		graphics.nextStratum();
		this.backgroundScreen.extractRenderState(graphics, -1, -1, a);
		graphics.nextStratum();
		this.extractTransparentBackground(graphics);
	}

	/**
	 * These are the inner positions and size of the popup, not outer
	 */
	public static void extractPopupBackground(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x - 18, y - 18, width + 36, height + 36);
	}

	@Override
	protected void init() {
		super.init();
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.backgroundScreen.resize(this.width, this.height);
	}

	@Override
	public void added() {
		super.added();
		this.backgroundScreen.clearFocus();
	}


}
