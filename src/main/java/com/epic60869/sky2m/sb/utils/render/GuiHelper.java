// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils.render;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

public final class GuiHelper {
	private GuiHelper() {}

	public static void border(GuiGraphicsExtractor context, int x, int y, int width, int height, int color) {
		com.epic60869.sky2m.custom.util.GuiHelper.border(context, x, y, width, height, color);
	}

	/** Draws a player head, falling back to the default skin until the profile loads. */
	public static void playerHead(GuiGraphicsExtractor graphics, int x, int y, int size, UUID uuid) {
		Minecraft client = Minecraft.getInstance();
		PlayerSkin texture = client.playerSkinRenderCache().lookup(ResolvableProfile.createUnresolved(uuid))
				.getNow(java.util.Optional.empty())
				.map(info -> info.playerSkin())
				.orElseGet(() -> DefaultPlayerSkin.get(uuid));
		PlayerFaceExtractor.extractRenderState(graphics, texture, x, y, size);
	}

	/** Text with a dark outline (drawn as four offset copies). */
	public static void outlinedText(GuiGraphicsExtractor graphics, FormattedCharSequence text, int x, int y, int color, int outlineColor) {
		var font = Minecraft.getInstance().font;
		graphics.text(font, text, x - 1, y, outlineColor, false);
		graphics.text(font, text, x + 1, y, outlineColor, false);
		graphics.text(font, text, x, y - 1, outlineColor, false);
		graphics.text(font, text, x, y + 1, outlineColor, false);
		graphics.text(font, text, x, y, color, false);
	}

	/** Skyblocker blurs the map background; Sky2M draws a translucent fill instead. */
	public static void blurredRectangle(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius) {
		graphics.fill(x0, y0, x1, y1, 0x60000000);
	}
}
