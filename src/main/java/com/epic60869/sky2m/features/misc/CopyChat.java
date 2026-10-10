package com.epic60869.sky2m.features.misc;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MStaff;
import com.epic60869.sky2m.mixin.Sky2MChatComponentAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Copy Chat, from NoFrills' Chat Tweaks: with chat open, press the Copy Message Key (any key or mouse button, right
 * click by default) over a message to copy the whole message. A short preview of what was copied shows in chat. Sky2M rank prefixes like "[OWNER] " are left out of what's copied.
 */
public final class CopyChat {
    private CopyChat() {}

    private static Sky2MConfig.CopyChat config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.copyChat;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                Sky2MConfig.CopyChat c = config();
                if (c == null || !c.enabled || !isMouseBind(c.copyMessageKey, event.button())) return true;
                return !copy(event.x(), event.y());
            });
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                Sky2MConfig.CopyChat c = config();
                if (c == null || !c.enabled || com.epic60869.sky2m.custom.util.Input.bindMouseButton(c.copyMessageKey) != -1 || c.copyMessageKey == InputConstants.UNKNOWN.getValue() || event.key() != c.copyMessageKey) return true;
                Minecraft mc = Minecraft.getInstance();
                double x = mc.mouseHandler.getScaledXPos(mc.getWindow());
                double y = mc.mouseHandler.getScaledYPos(mc.getWindow());
                // Over a message: copy it and keep the key out of the chat box. Anywhere else the key types as normal.
                return !copy(x, y);
            });
        });
    }

    /** Copies the chat message under the mouse; false if there's none there. */
    private static boolean copy(double mouseX, double mouseY) {
        String text = hovered(mouseX, mouseY);
        if (text.isEmpty()) return false;
        text = stripRanks(text);
        Sky2MConfig.CopyChat c = config();
        if (c != null && c.trim) text = text.trim();
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.setClipboard(text);
        showPreview("Message copied", text);
        return true;
    }

    /**
     * Shows "[S2M] {what}: "text"" in chat, cut to the Copy Chat preview length. Also used by other features that
     * copy a message by themselves (Copy Rare Drops). Does nothing when the Copy Chat preview is off.
     */
    public static void showPreview(String what, String text) {
        Sky2MConfig.CopyChat c = config();
        if (c == null || !c.preview) return;
        Minecraft mc = Minecraft.getInstance();
        int length = c.previewLength;
        String shown = length > 0 && text.length() > length ? text.substring(0, length) + "..." : text;
        Component message = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(what + (length == 0 ? "." : ": ")).withStyle(ChatFormatting.GREEN));
        if (length != 0) {
            message = message.copy().append(Component.literal("\"").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(shown).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\"").withStyle(ChatFormatting.GREEN));
        }
        mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    /** Sky2M rank prefixes ("[OWNER] ") aren't part of what the player wrote. */
    private static String stripRanks(String text) {
        String out = text;
        for (Sky2MStaff.Rank rank : Sky2MStaff.allRanks()) out = out.replace("[" + rank.label() + "] ", "");
        return out;
    }

    /** The plain text of the chat message under the mouse, like NoFrills' getHoveredMsg. */
    /** A key bound to a mouse button: MoulConfig uses 0-9 for mouse buttons (older versions -100 + button). */
    private static boolean isMouseBind(int bind, int button) {
        return com.epic60869.sky2m.custom.util.Input.isMouseBind(bind, button);
    }

    private record DrawnLine(FormattedCharSequence content, float top, float bottom) {}

    private static final List<DrawnLine> DRAWN = new ArrayList<>();

    /** A new chat frame is being drawn (Sky2MChatLineMixin). */
    public static void beginFrame() {
        DRAWN.clear();
    }

    /** A chat line was drawn at this height on screen (Sky2MChatLineMixin). */
    public static void recordLine(FormattedCharSequence content, float top, float bottom) {
        DRAWN.add(new DrawnLine(content, Math.min(top, bottom), Math.max(top, bottom)));
    }

    /**
     * The plain text of the chat message under the mouse. Uses the lines exactly as vanilla drew them this frame, so
     * the right line is found whatever the chat scale, line spacing or position (the old maths could land a couple of
     * lines too high).
     */
    private static String hovered(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Sky2MChatComponentAccessor access = (Sky2MChatComponentAccessor) mc.gui.hud.getChat();
        List<GuiMessage.Line> all = access.sky2m$trimmedMessages();
        double chatScale = mc.options.chatScale().get();
        double maxX = ChatComponent.getWidth(mc.options.chatWidth().get()) * chatScale + 8;
        if (mouseX < 0 || mouseX > maxX) return "";
        DrawnLine found = null;
        float gap = DRAWN.size() > 1 ? Math.abs(DRAWN.get(0).top() - DRAWN.get(1).top()) : 9;
        for (DrawnLine line : DRAWN) {
            // A line owns its row: from its top down to where the next row starts.
            float top = line.top() - Math.max(0, gap - (line.bottom() - line.top())) / 2f;
            float bottom = top + Math.max(gap, line.bottom() - line.top());
            if (mouseY >= top && mouseY < bottom) {
                found = line;
                break;
            }
        }
        if (found == null) return "";
        int index = -1;
        for (int k = 0; k < all.size(); k++) {
            if (all.get(k).content() == found.content()) {
                index = k;
                break;
            }
        }
        if (index < 0) return plain(found.content());
        StringBuilder out = new StringBuilder();
        for (GuiMessage.Line line : fullMessage(all, index)) out.append(plain(line.content()));
        return out.toString();
    }

    private static List<GuiMessage.Line> fullMessage(List<GuiMessage.Line> visible, int index) {
        List<GuiMessage.Line> lines = new ArrayList<>();
        for (int i = index + 1; i < visible.size(); i++) {
            GuiMessage.Line line = visible.get(i);
            if (line.endOfEntry()) break;
            lines.addFirst(line);
        }
        for (int i = index; i >= 0; i--) {
            GuiMessage.Line line = visible.get(i);
            lines.add(line);
            if (line.endOfEntry()) break;
        }
        return lines;
    }

    private static String plain(FormattedCharSequence sequence) {
        StringBuilder out = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            out.appendCodePoint(codePoint);
            return true;
        });
        return out.toString();
    }
}
