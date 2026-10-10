package com.epic60869.sky2m.features.sbc;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** An item shared in Sky2M chat (click its [Item Name]): the item, big, with its full tooltip. */
public final class SbcItemScreen extends Screen {
    private final JsonObject item;
    private final List<Component> tooltip;
    /** The shared inventory it was opened from, to go back to; null from chat. */
    private final Screen parent;

    public SbcItemScreen(JsonObject item) {
        this(item, null);
    }

    public SbcItemScreen(JsonObject item, Screen parent) {
        super(Component.literal("Shared Item"));
        this.item = item;
        this.tooltip = SbcItems.tooltip(item);
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (parent != null) minecraft.gui.setScreen(parent);
        else super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        List<FormattedCharSequence> lines = new ArrayList<>();
        int width = 0;
        for (Component line : tooltip) {
            lines.add(line.getVisualOrderText());
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * 10;
        int left = (this.width - width - 60) / 2;
        int top = Math.max(10, (this.height - height) / 2);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(3f, 3f);
        // Its real icon, even off Hypixel (where most items would otherwise be paper).
        SbcItemIcons.draw(g, item, 0, 0);
        g.pose().popMatrix();
        g.fill(left + 56, top - 4, left + 64 + width, top + height + 2, 0xF0100010);
        g.outline(left + 56, top - 4, width + 8, height + 6, 0xFF5000A0);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), left + 60, top + i * 10, 0xFFFFFFFF, true);
        g.centeredText(font, "Press Esc to close", this.width / 2, this.height - 14, 0xFF777777);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
