package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat mentions: highlights and plays a sound when your name or one of your keywords appears in other players' chat
 * messages (all, party, guild, co-op). Only what is shown changes; nothing is ever sent for you.
 */
public final class MentionAlerts {
    /** "Guild > [VIP] Name [GM]: text", "[123] [MVP+] Name: text", "Party > Name: text": the sender and the message. */
    private static final Pattern CHAT_LINE = Pattern.compile("^(?:(?:Party|Guild|Officer|Co-op) > )?(?:\\[\\d+] )?(?:\\S )?(?:\\[[A-Za-z+]+] )?(\\w{1,16})(?: \\[[^\\]]+])?(?: \\S)?: (.+)$");

    private static String cachedKeywords;
    private static List<Pattern> cachedPatterns = List.of();

    private MentionAlerts() {}

    public enum Sound {
        BELL("Bell"), PLING("Pling"), LEVEL_UP("Level Up"), TOAST("Toast");

        private final String label;

        Sound(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        SoundEvent event() {
            return switch (this) {
                case BELL -> SoundEvents.NOTE_BLOCK_BELL.value();
                case PLING -> SoundEvents.NOTE_BLOCK_PLING.value();
                case LEVEL_UP -> SoundEvents.PLAYER_LEVELUP;
                case TOAST -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
            };
        }
    }

    public enum Colour {
        YELLOW("Yellow", ChatFormatting.YELLOW), GOLD("Gold", ChatFormatting.GOLD), GREEN("Green", ChatFormatting.GREEN),
        AQUA("Aqua", ChatFormatting.AQUA), RED("Red", ChatFormatting.RED), PINK("Pink", ChatFormatting.LIGHT_PURPLE);

        private final String label;
        private final ChatFormatting format;

        Colour(String label, ChatFormatting format) {
            this.label = label;
            this.format = format;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Highlight your name and your keywords in other players' chat messages and play a sound. Your own messages are ignored.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Keywords", desc = "Extra words to react to, separated by commas (not case sensitive, whole words). Your Minecraft name is always included.")
        @ConfigEditorText
        public String keywords = "";

        @Expose
        @ConfigOption(name = "Highlight", desc = "Show the matched words in a colour and bold.")
        @ConfigEditorBoolean
        public boolean highlight = true;

        @Expose
        @ConfigOption(name = "Highlight Colour", desc = "The colour of the matched words.")
        @ConfigEditorDropdown
        public Colour colour = Colour.YELLOW;

        @Expose
        @ConfigOption(name = "Play Sound", desc = "Play a sound when a message matches.")
        @ConfigEditorBoolean
        public boolean sound = true;

        @Expose
        @ConfigOption(name = "Sound", desc = "Which sound to play.")
        @ConfigEditorDropdown
        public Sound soundChoice = Sound.BELL;

        @Expose
        @ConfigOption(name = "Volume", desc = "The sound's volume, in percent.")
        @ConfigEditorSlider(minValue = 10, maxValue = 100, minStep = 5)
        public int volume = 100;
    }

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.mentions;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            Config c = config();
            if (c == null) return;
            String text = message.text().trim();
            if (c.enabled && c.sound && !matches(c, text).isEmpty()) {
                Sky2MAlerts.play(c.soundChoice.event(), 1.0f, c.volume / 100f);
            }
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : highlight(message));
    }

    // ------------------------------------------------------------------------------------------ mentions

    /** The sender and message of a player chat line, or null. */
    private static String[] split(String text) {
        Matcher m = CHAT_LINE.matcher(text);
        return m.matches() ? new String[]{m.group(1), m.group(2)} : null;
    }

    private static String self() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "" : mc.player.getGameProfile().name();
    }

    private static List<Pattern> patterns(Config c) {
        String self = self();
        String key = c.keywords + "|" + self;
        if (key.equals(cachedKeywords)) return cachedPatterns;
        List<Pattern> out = new ArrayList<>();
        List<String> words = new ArrayList<>();
        if (!self.isEmpty()) words.add(self);
        if (c.keywords != null) for (String w : c.keywords.split(",")) if (!w.isBlank()) words.add(w.trim());
        for (String w : words) out.add(Pattern.compile("(?<![\\w])" + Pattern.quote(w) + "(?![\\w])", Pattern.CASE_INSENSITIVE));
        cachedKeywords = key;
        cachedPatterns = out;
        return out;
    }

    /** {start, end} pairs of the keyword matches in the message part of a player chat line (empty for other lines). */
    private static List<int[]> matches(Config c, String text) {
        List<int[]> out = new ArrayList<>();
        String[] parts = split(text);
        if (parts == null || parts[0].equalsIgnoreCase(self())) return out;
        int offset = text.length() - parts[1].length();
        for (Pattern p : patterns(c)) {
            Matcher m = p.matcher(parts[1]);
            while (m.find()) out.add(new int[]{offset + m.start(), offset + m.end()});
        }
        return out;
    }

    private static Component highlight(Component message) {
        Config c = config();
        if (c == null || !c.enabled || !c.highlight) return message;
        String text = TextEdit.plain(message);
        List<int[]> found = matches(c, text);
        if (found.isEmpty()) return message;
        List<Style> styles = new ArrayList<>();
        message.visit((style, s) -> {
            for (int i = 0; i < s.length(); i++) styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        if (styles.size() != text.length()) return message;
        boolean[] marked = new boolean[text.length()];
        for (int[] range : found) for (int i = range[0]; i < range[1] && i < marked.length; i++) marked[i] = true;
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style runStyle = null;
        for (int i = 0; i < text.length(); i++) {
            Style style = marked[i] ? styles.get(i).withColor(c.colour.format).withBold(true) : styles.get(i);
            if (runStyle != null && !style.equals(runStyle)) {
                out.append(Component.literal(run.toString()).withStyle(runStyle));
                run.setLength(0);
            }
            runStyle = style;
            run.append(text.charAt(i));
        }
        if (runStyle != null && !run.isEmpty()) out.append(Component.literal(run.toString()).withStyle(runStyle));
        return out;
    }
}
