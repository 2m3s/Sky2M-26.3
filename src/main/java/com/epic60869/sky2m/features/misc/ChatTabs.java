package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MCurrentChat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chat > Chat Tabs, Search & Timestamps. The tabs and the search use the chat's own visible-message filter, so the
 * hidden messages are still there and come back when you pick All. While searching, the chat box is the search box:
 * what you type filters the chat and Enter doesn't send it. Timestamps are added in front of each message as it
 * arrives; the chat compactor and the tabs look past them.
 */
public final class ChatTabs {
    public enum Tab {
        ALL("All"), PARTY("Party"), GUILD("Guild"), PRIVATE("Private");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final Pattern TIMESTAMP = Pattern.compile("^\\[\\d{1,2}:\\d{2}(?::\\d{2})?(?: [AP]M)?] ");
    private static final int TAB_H = 11, GAP = 2;
    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter H24S = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter H12S = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US);

    private static Tab tab = Tab.ALL;
    private static boolean searching;
    private static String search = "";
    private static String savedDraft = "";

    private ChatTabs() {}

    private static FeatureConfigs.ChatTabs config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.chatTabs;
    }

    /** Removes a timestamp this class put in front of a message's text. */
    public static String stripTimestamp(String text) {
        return TIMESTAMP.matcher(text).replaceFirst("");
    }

    /** From the chat component mixin: the message with the time in front, when timestamps are on. */
    public static Component timestamp(Component message) {
        FeatureConfigs.ChatTabs c = config();
        if (c == null || !c.timestamps || message.getString().isBlank()) return message;
        DateTimeFormatter f = switch (c.timestampFormat) {
            case H24 -> H24;
            case H24_SECONDS -> H24S;
            case H12 -> H12;
            case H12_SECONDS -> H12S;
        };
        MutableComponent out = Component.literal("[" + LocalTime.now().format(f) + "] ").withStyle(ChatFormatting.DARK_GRAY);
        return out.append(message);
    }

    private static boolean matches(GuiMessage message) {
        String text = stripTimestamp(Sky2MLocation.strip(message.content().getString()));
        if (!search.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(search)) return false;
        return switch (tab) {
            case ALL -> true;
            case PARTY -> text.startsWith("Party > ") || text.startsWith("Party Finder > ") || text.contains(" the party")
                || text.startsWith("Party Members") || text.startsWith("Party Leader") || text.startsWith("Party Moderators");
            case GUILD -> text.startsWith("Guild > ") || text.startsWith("Officer > ");
            case PRIVATE -> text.startsWith("From ") || text.startsWith("To ");
        };
    }

    private static void apply() {
        Minecraft mc = Minecraft.getInstance();
        boolean filtering = tab != Tab.ALL || !search.isEmpty();
        mc.gui.hud.getChat().setVisibleMessageFilter(filtering ? ChatTabs::matches : m -> true);
    }

    private static int left() {
        return 2 + Sky2MCurrentChat.displayWidth();
    }

    private static int top(int screenHeight) {
        return screenHeight - 26;
    }

    /** From the chat screen mixin, after the screen is drawn. */
    public static void render(GuiGraphicsExtractor g, int screenHeight, EditBox input, int mouseX, int mouseY) {
        FeatureConfigs.ChatTabs c = config();
        if (c == null) return;
        if (searching) {
            String typed = input.getValue().trim().toLowerCase(Locale.ROOT);
            if (!typed.equals(search)) {
                search = typed;
                apply();
            }
        }
        if (!c.tabs && tab != Tab.ALL) {
            tab = Tab.ALL;
            apply();
        }
        if (!c.tabs && !c.search) return;
        Font font = Minecraft.getInstance().font;
        int x = left(), y = top(screenHeight);
        if (c.tabs) for (Tab t : Tab.values()) x = button(g, font, t.label, x, y, t == tab, mouseX, mouseY);
        if (c.search) button(g, font, searching ? "Searching" : "Search", x, y, searching, mouseX, mouseY);
    }

    private static int button(GuiGraphicsExtractor g, Font font, String label, int x, int y, boolean selected, int mouseX, int mouseY) {
        int w = font.width(label) + 8;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TAB_H;
        g.fill(x, y, x + w, y + TAB_H, selected ? 0xC0205070 : hover ? 0xA0303840 : 0x90101216);
        g.fill(x, y + TAB_H - 1, x + w, y + TAB_H, selected ? 0xFF33AAFF : 0xFF2C313A);
        g.text(font, label, x + 4, y + 2, selected ? 0xFFFFFFFF : 0xFFAAAAAA, false);
        return x + w + GAP;
    }

    /** From the chat screen mixin: true when a tab was clicked. */
    public static boolean mouseClicked(double mouseX, double mouseY, int screenHeight, EditBox input) {
        FeatureConfigs.ChatTabs c = config();
        if (c == null || (!c.tabs && !c.search)) return false;
        Font font = Minecraft.getInstance().font;
        int x = left(), y = top(screenHeight);
        if (mouseY < y || mouseY >= y + TAB_H) return false;
        if (c.tabs) for (Tab t : Tab.values()) {
            int w = font.width(t.label) + 8;
            if (mouseX >= x && mouseX < x + w) {
                tab = t;
                apply();
                return true;
            }
            x += w + GAP;
        }
        if (c.search) {
            int w = font.width(searching ? "Searching" : "Search") + 8;
            if (mouseX >= x && mouseX < x + w) {
                toggleSearch(input);
                return true;
            }
        }
        return false;
    }

    /** From the chat screen mixin: true when the key is used (Ctrl+F, or Enter while searching). */
    public static boolean keyPressed(int key, boolean control, EditBox input) {
        FeatureConfigs.ChatTabs c = config();
        if (c == null || !c.search) return false;
        if (control && key == com.mojang.blaze3d.platform.InputConstants.KEY_F) {
            toggleSearch(input);
            return true;
        }
        return searching && (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER);
    }

    private static void toggleSearch(EditBox input) {
        searching = !searching;
        if (searching) {
            savedDraft = input.getValue();
            input.setValue("");
            input.setHint(Component.literal("Search chat...").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            search = "";
            input.setValue(savedDraft);
            input.setHint(Component.empty());
            apply();
        }
    }

    /** From the chat screen mixin when chat closes: searching stops, the tab stays. */
    public static void closed() {
        if (!searching && search.isEmpty()) return;
        searching = false;
        search = "";
        apply();
    }
}
