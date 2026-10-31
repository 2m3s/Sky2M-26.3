package com.epic60869.sky2m.features.sbc;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.mixin.chat.Sky2MChatComponentAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What SBC chat draws over the chat: the highlight behind messages that mention you, the reaction bar (↩ and the 12
 * emojis) next to the SBC message under the mouse while chat is open, and "Replying to ..." above the chat box.
 */
public final class SbcChatOverlay {
    private record DrawnLine(FormattedCharSequence content, float top, float bottom) {}

    private record Button(int x, int width, String emoji, String label, int colour, String tooltip) {}

    private static final List<DrawnLine> DRAWN = new ArrayList<>();
    private static final int BAR_HEIGHT = 12;

    /** Chat lines -> the message they belong to; rebuilt when the chat changes. */
    private static final Map<FormattedCharSequence, GuiMessage> PARENTS = new IdentityHashMap<>();
    private static Object parentsKey;
    private static int parentsSize = -1;

    /** The message the bar belongs to, and where the bar is. */
    private static long barId;
    private static int barX;
    private static int barY;
    private static final List<Button> BUTTONS = new ArrayList<>();

    private SbcChatOverlay() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            barId = 0;
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
                Button button = buttonAt(event.x(), event.y());
                if (button == null) return true;
                long id = barId;
                if (button.emoji() == null) SbcChat.startReply(id);
                else SbcChat.toggleReaction(id, button.emoji());
                return false;
            });
            // Escape with a reply pending cancels the reply first.
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (event.key() != InputConstants.KEY_ESCAPE || SbcChat.replying() == null) return true;
                SbcChat.cancelReply();
                return true;
            });
        });
    }

    /** A new frame of the open chat is being drawn. */
    public static void beginFrame() {
        DRAWN.clear();
    }

    /** A line of the open chat was drawn at this height on screen. */
    public static void recordLine(FormattedCharSequence content, float top, float bottom) {
        DRAWN.add(new DrawnLine(content, Math.min(top, bottom), Math.max(top, bottom)));
    }

    /** Before a chat line is drawn (open or closed chat): the mention highlight behind it. */
    public static void drawLineBackground(GuiGraphicsExtractor graphics, int textTop, float opacity, FormattedCharSequence content) {
        SbcConfig.Chat config = Sbc.config().chat;
        if (!config.mentionHighlight || !SbcChat.hasMentions()) return;
        GuiMessage parent = parent(content);
        if (parent == null || !SbcChat.isMention(SbcChat.idOf(parent))) return;
        Minecraft mc = Minecraft.getInstance();
        int width = ChatComponent.getWidth(mc.options.chatWidth().get());
        int colour = Sbc.colour(config.mentionColour, 0x5AFFAA00);
        int alpha = Math.round((colour >>> 24) * Math.max(0f, Math.min(1f, opacity)));
        graphics.fill(-2, textTop - 1, width + 4, textTop + 9, alpha << 24 | colour & 0xFFFFFF);
    }

    private static GuiMessage parent(FormattedCharSequence content) {
        Minecraft mc = Minecraft.getInstance();
        List<GuiMessage.Line> lines = ((Sky2MChatComponentAccessor) mc.gui.hud.getChat()).sky2m$trimmedMessages();
        Object key = lines.isEmpty() ? null : lines.getFirst();
        if (key != parentsKey || lines.size() != parentsSize) {
            PARENTS.clear();
            for (GuiMessage.Line line : lines) PARENTS.put(line.content(), line.parent());
            parentsKey = key;
            parentsSize = lines.size();
        }
        return PARENTS.get(content);
    }

    /** After the chat screen is drawn: the reaction bar and the reply notice. */
    public static void render(GuiGraphicsExtractor graphics, int screenHeight, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        SbcChat.Stored replying = SbcChat.replying();
        if (replying != null) {
            String text = replying.text.length() > 40 ? replying.text.substring(0, 40) + "..." : replying.text;
            graphics.text(font, Component.literal("↪ Replying to " + replying.username + ": ").withStyle(s -> s.withColor(0xAAAAAA))
                .append(Component.literal(text).withStyle(s -> s.withColor(0x777777).withItalic(true)))
                .append(Component.literal("  (Esc cancels)").withStyle(s -> s.withColor(0x555555))), 100, screenHeight - 26, 0xFFFFFFFF, true);
        }

        SbcConfig.Chat config = Sbc.config().chat;
        boolean reactions = config.reactions && Flags.isEnabled("chat.reactions");
        boolean replies = config.replies && Flags.isEnabled("chat.replies");
        if (!reactions && !replies) {
            barId = 0;
            return;
        }

        // Keep the bar while the mouse is on it, even though that's outside the chat.
        if (barId != 0 && buttonAt(mouseX, mouseY) == null && !overBar(mouseX, mouseY)) barId = 0;
        if (barId == 0) findHovered(mouseX, mouseY);
        if (barId == 0) return;

        BUTTONS.clear();
        int x = barX + 2;
        if (replies) {
            BUTTONS.add(new Button(x, font.width("↩") + 6, null, "↩", 0xFFFFFF, "Reply"));
            x += font.width("↩") + 7;
        }
        if (reactions) {
            for (SbcChat.Emoji emoji : SbcChat.EMOJIS) {
                int w = font.width(emoji.label()) + 6;
                BUTTONS.add(new Button(x, w, emoji.emoji(), emoji.label(), emoji.colour(), "React " + emoji.name()));
                x += w + 1;
            }
        }
        graphics.fill(barX, barY, x + 1, barY + BAR_HEIGHT, 0xE0101018);
        graphics.outline(barX, barY, x + 1 - barX, BAR_HEIGHT, 0xFF3B465B);
        for (Button b : BUTTONS) {
            boolean hover = mouseX >= b.x() && mouseX < b.x() + b.width() && mouseY >= barY && mouseY < barY + BAR_HEIGHT;
            if (hover) {
                graphics.fill(b.x(), barY + 1, b.x() + b.width(), barY + BAR_HEIGHT - 1, 0x60FFFFFF);
                graphics.setTooltipForNextFrame(font, Component.literal(b.tooltip()), mouseX, mouseY);
            }
            graphics.text(font, b.label(), b.x() + 3, barY + 2, 0xFF000000 | b.colour(), true);
        }
    }

    private static void findHovered(int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.options.chatScale().get();
        int chatRight = (int) Math.ceil(ChatComponent.getWidth(mc.options.chatWidth().get()) * scale) + 8;
        if (mouseX < 0 || mouseX > chatRight || DRAWN.isEmpty()) return;
        float gap = DRAWN.size() > 1 ? Math.abs(DRAWN.get(0).top() - DRAWN.get(1).top()) : 9;
        for (DrawnLine line : DRAWN) {
            float top = line.top() - Math.max(0, gap - (line.bottom() - line.top())) / 2f;
            float bottom = top + Math.max(gap, line.bottom() - line.top());
            if (mouseY < top || mouseY >= bottom) continue;
            long id = SbcChat.idOf(parent(line.content()));
            if (id <= 0) return;
            barId = id;
            barX = chatRight + 2;
            barY = Math.round(top) - 2;
            // Off the right of the screen: put it just above the line instead.
            Font font = mc.font;
            int width = 16;
            for (SbcChat.Emoji e : SbcChat.EMOJIS) width += font.width(e.label()) + 7;
            int screenWidth = mc.getWindow().getGuiScaledWidth();
            if (barX + width > screenWidth) {
                barX = Math.max(0, Math.min(mouseX - width / 2, screenWidth - width));
                barY = Math.round(top) - BAR_HEIGHT - 1;
            }
            return;
        }
    }

    private static boolean overBar(double mouseX, double mouseY) {
        if (BUTTONS.isEmpty()) return false;
        Button last = BUTTONS.getLast();
        // A little slack between the line and the bar so moving onto it doesn't lose it.
        return mouseX >= barX - 12 && mouseX <= last.x() + last.width() + 2 && mouseY >= barY - 3 && mouseY <= barY + BAR_HEIGHT + 3;
    }

    private static Button buttonAt(double mouseX, double mouseY) {
        if (barId == 0 || mouseY < barY || mouseY >= barY + BAR_HEIGHT) return null;
        for (Button b : BUTTONS) {
            if (mouseX >= b.x() && mouseX < b.x() + b.width()) return b;
        }
        return null;
    }
}
