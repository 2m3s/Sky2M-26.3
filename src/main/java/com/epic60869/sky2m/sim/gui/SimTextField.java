package com.epic60869.sky2m.sim.gui;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The Dungeon Sim's text field: vanilla's {@link EditBox} with its white frame redrawn in the sim's own greys (purple
 * while typing), so it matches the dark buttons beside it. Drawn over the vanilla frame's 1px edge, which the text never
 * reaches, so typing, scrolling and the cursor are vanilla's own.
 */
public class SimTextField extends EditBox {

    public SimTextField(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
        if (isBordered() && isVisible()) {
            graphics.outline(getX(), getY(), getWidth(), getHeight(),
                    isFocused() ? Sky2MGuiStyle.HEADING : isHovered() ? 0xFF6A6A7A : 0xFF3A3A46);
        }
    }
}
