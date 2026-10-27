package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Chat > Side Chat: a second chat window for messages moved out of the main chat (Pristine procs, and the
 * {@link SpamFilters} set to Move to Side Chat). It's a HUD element, so it's moved and scaled in /s2 gui. Like chat, new
 * lines fade out after a while and everything shows again while chat is open; then the mouse wheel scrolls it, and
 * clicking or hovering a message works as in chat. The moved messages are still read by Sky2M's trackers, which see
 * every message before it reaches chat.
 */
public final class SideChat {
    public static final String HUD_ID = "side_chat";
    private static final int LINE_HEIGHT = 9;
    /** Messages kept for scrolling back. */
    private static final int HISTORY = 100;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final List<Component> PREVIEW = List.of(
        Component.literal("PRISTINE! ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
            .append(Component.literal("You found ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("❈ Flawed Amethyst Gemstone").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" x2!").withStyle(ChatFormatting.GRAY)),
        Component.literal("Side Chat").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(": moved messages show here").withStyle(ChatFormatting.GRAY)));

    private record Entry(Component message, long time) {}

    private record Line(FormattedCharSequence text, Entry entry) {}

    /** Newest first. */
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    /** The wrapped lines, newest first, rebuilt when a message arrives or the width/timestamps change. */
    private static List<Line> lines = List.of();
    private static boolean dirty = true;
    private static int builtWidth = -1;
    private static boolean builtTimestamps;
    /** Lines scrolled up from the bottom while chat is open. */
    private static int scroll;

    private SideChat() {}

    static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.chat == null ? null : c.chat.sideChat;
    }

    private static boolean enabled() {
        Config c = config();
        return c != null && c.enabled;
    }

    public static void init() {
        Sky2MHuds.setting(HUD_ID, SideChat::enabled);
        Sky2MHuds.setSettingsSearch(HUD_ID, "Side Chat");
        Sky2MHuds.registerCustom(HUD_ID, "Side Chat", SideChat::enabled, new Hud(), 4, 60);
    }

    /**
     * Puts a message in the Side Chat. Called on the render thread from the chat receive event.
     *
     * @return false when the Side Chat is off (the message should then stay in the main chat)
     */
    public static boolean add(Component message) {
        if (!enabled()) return false;
        ENTRIES.addFirst(new Entry(message, System.currentTimeMillis()));
        while (ENTRIES.size() > HISTORY) ENTRIES.removeLast();
        // Keep reading the same lines while scrolled up, like chat.
        if (scroll > 0) scroll += wrap(message, entryWidth()).size();
        dirty = true;
        return true;
    }

    public static void clear() {
        ENTRIES.clear();
        lines = List.of();
        scroll = 0;
        dirty = true;
    }

    /** From the chat screen mixin when chat closes: back to the newest messages. */
    public static void closed() {
        scroll = 0;
    }

    // ------------------------------------------------------------------------------------------------ layout

    private static int boxWidth() {
        Config c = config();
        return c == null ? 220 : Math.max(60, c.width);
    }

    private static int maxLines() {
        Config c = config();
        return c == null ? 8 : Math.max(1, c.maxLines);
    }

    private static int entryWidth() {
        return boxWidth() - 4;
    }

    private static boolean chatOpen() {
        return Minecraft.getInstance().gui.screen() instanceof ChatScreen;
    }

    private static List<FormattedCharSequence> wrap(Component message, int width) {
        Config c = config();
        Component shown = message;
        if (c != null && c.timestamps) {
            // Placeholder width only: the real time is added when the lines are built.
            shown = Component.literal("[00:00] ").append(message);
        }
        return Minecraft.getInstance().font.split(shown, Math.max(20, width));
    }

    private static List<Line> lines() {
        Config c = config();
        boolean timestamps = c != null && c.timestamps;
        int width = entryWidth();
        if (dirty || width != builtWidth || timestamps != builtTimestamps) {
            Font font = Minecraft.getInstance().font;
            List<Line> out = new ArrayList<>();
            for (Entry entry : ENTRIES) {
                Component shown = entry.message();
                if (timestamps) {
                    String time = LocalTime.ofInstant(Instant.ofEpochMilli(entry.time()), ZoneId.systemDefault()).format(TIME);
                    MutableComponent stamped = Component.literal("[" + time + "] ").withStyle(ChatFormatting.DARK_GRAY);
                    shown = stamped.append(shown);
                }
                List<FormattedCharSequence> split = font.split(shown, Math.max(20, width));
                // Newest first, so a message's last line comes first.
                for (int i = split.size() - 1; i >= 0; i--) out.add(new Line(split.get(i), entry));
            }
            lines = out;
            dirty = false;
            builtWidth = width;
            builtTimestamps = timestamps;
        }
        return lines;
    }

    private static int maxScroll() {
        return Math.max(0, lines().size() - maxLines());
    }

    /** 0..1: how visible a line is when chat is closed. */
    private static double opacity(Entry entry) {
        Config c = config();
        double fade = c == null ? 10 : c.fadeSeconds;
        if (fade <= 0) return 1;
        double age = (System.currentTimeMillis() - entry.time()) / 1000.0;
        double d = Math.clamp((1 - age / fade) * 10, 0, 1);
        return d * d;
    }

    // ------------------------------------------------------------------------------------------------ input

    /** The Side Chat's on-screen box and scale: {x, y, w, h} and scale, or null when it isn't shown. */
    private static float[] screenBox() {
        if (!enabled() || ENTRIES.isEmpty()) return null;
        Sky2MHuds.Placement p = Sky2MHuds.placement(HUD_ID);
        int w = Math.round(boxWidth() * p.scale), h = Math.round(height() * p.scale);
        return new float[]{Sky2MHuds.mapX(p.x, w), Sky2MHuds.mapY(p.y, h), w, h, p.scale};
    }

    private static int height() {
        return maxLines() * LINE_HEIGHT + 2;
    }

    /** From the chat screen mixin: true when the wheel scrolled the Side Chat (the mouse is over it). */
    public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        float[] box = screenBox();
        if (box == null || mouseX < box[0] || mouseX >= box[0] + box[2] || mouseY < box[1] || mouseY >= box[1] + box[3]) return false;
        int step = Minecraft.getInstance().hasShiftDown() ? 1 : 7;
        scroll = Math.clamp(scroll + (int) Math.signum(amount) * step, 0, maxScroll());
        return true;
    }

    /** The text style under the mouse (for clicks and hover text), or null. */
    public static Style styleAt(double mouseX, double mouseY) {
        float[] box = screenBox();
        if (box == null) return null;
        double localX = (mouseX - box[0]) / box[4], localY = (mouseY - box[1]) / box[4];
        if (localX < 2 || localX >= boxWidth() || localY < 0 || localY >= height()) return null;
        int row = (int) ((height() - 1 - localY) / LINE_HEIGHT);
        List<Line> all = lines();
        int index = scroll + row;
        if (row < 0 || row >= maxLines() || index >= all.size()) return null;
        return styleAtWidth(all.get(index).text(), localX - 2);
    }

    private static Style styleAtWidth(FormattedCharSequence text, double x) {
        Font font = Minecraft.getInstance().font;
        Style[] found = {null};
        float[] at = {0};
        text.accept((index, style, codepoint) -> {
            at[0] += font.getSplitter().stringWidth(FormattedCharSequence.codepoint(codepoint, style));
            if (at[0] > x) {
                found[0] = style;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /** From the chat screen mixin, after the screen is drawn: the hover text of the message part under the mouse. */
    public static void renderHover(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Style style = styleAt(mouseX, mouseY);
        if (style != null && style.getHoverEvent() instanceof HoverEvent.ShowText(Component value)) {
            g.setTooltipForNextFrame(Minecraft.getInstance().font, value, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------------------------------------ HUD

    private static final class Hud implements Sky2MHuds.CustomHud {
        @Override
        public int width() {
            return boxWidth();
        }

        @Override
        public int height() {
            return SideChat.height();
        }

        @Override
        public boolean visible() {
            if (ENTRIES.isEmpty()) return false;
            if (chatOpen()) return true;
            Entry newest = ENTRIES.peekFirst();
            return newest != null && opacity(newest) > 0.01;
        }

        @Override
        public void render(GuiGraphicsExtractor g, boolean preview) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            Config c = config();
            boolean background = c == null || c.background;
            int w = boxWidth(), h = SideChat.height(), max = maxLines();
            double textOpacity = mc.options.chatOpacity().get() * 0.9 + 0.1;
            double backOpacity = mc.options.textBackgroundOpacity().get();

            if (preview && ENTRIES.isEmpty()) {
                if (background) g.fill(0, 0, w, h, (int) (255 * backOpacity) << 24);
                List<FormattedCharSequence> sample = new ArrayList<>();
                for (Component line : PREVIEW) sample.addAll(font.split(line, w - 4));
                for (int i = 0; i < sample.size() && i < max; i++) {
                    g.text(font, sample.get(sample.size() - 1 - i), 2, h - (i + 1) * LINE_HEIGHT, 0xFFFFFFFF, true);
                }
                return;
            }

            boolean open = chatOpen() || preview;
            if (!open) scroll = 0;
            List<Line> all = lines();
            scroll = Math.clamp(scroll, 0, maxScroll());
            for (int row = 0; row < max; row++) {
                int index = scroll + row;
                if (index >= all.size()) break;
                Line line = all.get(index);
                double o = open ? 1 : opacity(line.entry());
                if (o <= 0.01) continue;
                int top = h - (row + 1) * LINE_HEIGHT - 1;
                if (background) {
                    int back = (int) (255 * backOpacity * o);
                    if (back > 0) g.fill(0, top, w, top + LINE_HEIGHT, back << 24);
                }
                int alpha = (int) (255 * textOpacity * o);
                if (alpha > 3) g.text(font, line.text(), 2, top + 1, (alpha << 24) | 0xFFFFFF, true);
            }
            // A scroll bar while scrolled, like chat.
            if (open && all.size() > max) {
                int barH = Math.max(4, h * max / all.size());
                int barY = (int) ((h - barH) * (1 - scroll / (double) Math.max(1, maxScroll())));
                g.fill(w - 2, barY, w, barY + barH, scroll > 0 ? 0xFFAAAAAA : 0x66AAAAAA);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ config

    public static final class Config {
        @Expose
        @ConfigOption(name = "Side Chat", desc = "Show the Side Chat: a second chat window (move and scale it in /s2 gui) for messages moved out of the main chat. When off, moved messages stay in the main chat.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Pristine Procs", desc = "\"PRISTINE! You found Flawed ... Gemstone xN!\": move them to the Side Chat, hide them or leave them in chat. The Pristine record and Gemstone tracker count them either way.")
        @ConfigEditorDropdown
        public SpamFilters.Mode pristine = SpamFilters.Mode.SIDE_CHAT;

        @Expose
        @ConfigOption(name = "Max Lines", desc = "How many lines the Side Chat shows (scroll with the mouse wheel over it while chat is open to see older ones).")
        @ConfigEditorSlider(minValue = 1, maxValue = 30, minStep = 1)
        public int maxLines = 8;

        @Expose
        @ConfigOption(name = "Width", desc = "How wide the Side Chat is; longer messages wrap.")
        @ConfigEditorSlider(minValue = 80, maxValue = 400, minStep = 5)
        public int width = 220;

        @Expose
        @ConfigOption(name = "Fade Time", desc = "Seconds a message stays before fading out while chat is closed (0: never fade). Everything shows while chat is open.")
        @ConfigEditorSlider(minValue = 0, maxValue = 60, minStep = 1)
        public int fadeSeconds = 10;

        @Expose
        @ConfigOption(name = "Background", desc = "Draw chat's dark background behind the lines (uses your Text Background Opacity setting).")
        @ConfigEditorBoolean
        public boolean background = true;

        @Expose
        @ConfigOption(name = "Show Timestamps", desc = "The time each message arrived, in grey, in front of it.")
        @ConfigEditorBoolean
        public boolean timestamps = false;
    }
}
