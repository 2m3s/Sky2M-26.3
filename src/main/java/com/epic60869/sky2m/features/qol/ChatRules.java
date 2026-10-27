package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Chat > Chat Rules & Player Format: a small chat rules engine (hide, replace and notify by regular expression, in the
 * spirit of SkyHanni's and NoFrills' chat tweaks) and SkyHanni's player chat format (leave out the SkyBlock level, rank
 * and emblem on other players' all-chat messages). Only what is shown changes; Sky2M's own features still read the
 * original message.
 */
public final class ChatRules {
    /** "[123] ✿ [MVP+] Name ✯: hello": level, a one-character prefix, rank, name, an emblem. Only all-chat lines match. */
    private static final Pattern PLAYER_LINE = Pattern.compile("^(\\[\\d+] )?(\\S )?(\\[[A-Z+]+] )?(\\w{1,16})( \\S)?: .+");

    private enum Action { HIDE, REPLACE, NOTIFY }

    private record Rule(Action action, Pattern regex, String text) {}

    private static String parsedSource;
    private static List<Rule> parsed = List.of();

    private ChatRules() {}

    public static final class Config {
        @Expose
        @ConfigOption(name = "Chat Rules", desc = "Run your own rules on incoming chat lines. Write the rules below.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Rules", desc = "Separate rules with ;; and write each as: hide /regex/ or replace /regex/ => new text or notify /regex/ => title text. A pattern without slashes is plain text (not case sensitive). Replacements can use $1 for a group. Example: hide /^You are sending commands too fast/ ;; replace /coins/ => gold ;; notify /Your \\w+ minion is full/ => Minion full!")
        @ConfigEditorText
        public String rules = "";

        @Expose
        @ConfigOption(name = "Player Format: Hide Level", desc = "Leave the SkyBlock level (\"[123] \") out of other players' all-chat messages, like SkyHanni's player chat format.")
        @ConfigEditorBoolean
        public boolean hideLevel = false;

        @Expose
        @ConfigOption(name = "Player Format: Hide Rank", desc = "Leave the rank (\"[MVP+] \") out of other players' all-chat messages.")
        @ConfigEditorBoolean
        public boolean hideRank = false;

        @Expose
        @ConfigOption(name = "Player Format: Hide Emblem", desc = "Leave the emblem after the name out of other players' all-chat messages.")
        @ConfigEditorBoolean
        public boolean hideEmblem = false;

        @Expose
        @ConfigOption(name = "Chat Peeking", desc = "Hold the Peek Chat key (Controls > Sky2M) to show the whole chat history as if it were open, without opening it, so you can read it while moving.")
        @ConfigEditorBoolean
        public boolean peeking = false;
    }

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.chatRules;
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !runRules(message));
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : modify(message));
    }

    /** Runs the notify rules; true if a hide rule matches. */
    private static boolean runRules(Component message) {
        Config c = config();
        if (c == null || !c.enabled || c.rules == null || c.rules.isBlank()) return false;
        String text = TextEdit.plain(message).trim();
        if (text.isEmpty()) return false;
        boolean hide = false;
        for (Rule rule : rules(c.rules)) {
            Matcher m = rule.regex().matcher(text);
            if (!m.find()) continue;
            switch (rule.action()) {
                case HIDE -> hide = true;
                case NOTIFY -> Sky2MAlerts.title(Component.literal(expand(m, rule.text())), null);
                default -> {}
            }
        }
        return hide;
    }

    private static Component modify(Component message) {
        Config c = config();
        if (c == null) return message;
        Component result = message;
        if (c.enabled && c.rules != null && !c.rules.isBlank()) {
            for (Rule rule : rules(c.rules)) {
                if (rule.action() != Action.REPLACE) continue;
                String text = TextEdit.plain(result);
                Matcher m = rule.regex().matcher(text);
                List<TextEdit.Edit> edits = new ArrayList<>();
                while (m.find()) {
                    if (m.end() == m.start()) continue;
                    edits.add(new TextEdit.Edit(m.start(), m.end(), expand(m, rule.text())));
                }
                result = TextEdit.apply(result, edits);
            }
        }
        if (c.hideLevel || c.hideRank || c.hideEmblem) result = playerFormat(result, c);
        return result;
    }

    private static Component playerFormat(Component message, Config c) {
        String text = TextEdit.plain(message);
        Matcher m = PLAYER_LINE.matcher(text);
        if (!m.matches()) return message;
        List<TextEdit.Edit> edits = new ArrayList<>();
        if (c.hideLevel && m.group(1) != null) edits.add(new TextEdit.Edit(m.start(1), m.end(1), ""));
        if (c.hideRank && m.group(3) != null) edits.add(new TextEdit.Edit(m.start(3), m.end(3), ""));
        if (c.hideEmblem && m.group(5) != null) edits.add(new TextEdit.Edit(m.start(5), m.end(5), ""));
        return TextEdit.apply(message, edits);
    }

    private static List<Rule> rules(String source) {
        if (source.equals(parsedSource)) return parsed;
        List<Rule> out = new ArrayList<>();
        for (String part : source.split(";;")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int space = p.indexOf(' ');
            if (space < 0) continue;
            Action action;
            try {
                action = Action.valueOf(p.substring(0, space).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String rest = p.substring(space + 1).trim();
            String pattern = rest;
            String argument = "";
            int arrow = rest.lastIndexOf("=>");
            if (arrow >= 0) {
                pattern = rest.substring(0, arrow).trim();
                argument = rest.substring(arrow + 2).trim();
            }
            if (pattern.isEmpty()) continue;
            try {
                Pattern regex = pattern.length() > 2 && pattern.startsWith("/") && pattern.endsWith("/")
                    ? Pattern.compile(pattern.substring(1, pattern.length() - 1), Pattern.CASE_INSENSITIVE)
                    : Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
                out.add(new Rule(action, regex, argument));
            } catch (PatternSyntaxException ignored) {
                // A rule with a broken pattern is skipped.
            }
        }
        parsedSource = source;
        parsed = out;
        return out;
    }

    /** {@code template} with $0 to $9 filled from the match. */
    private static String expand(Matcher m, String template) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char ch = template.charAt(i);
            if (ch == '$' && i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
                int group = template.charAt(i + 1) - '0';
                if (group <= m.groupCount() && m.group(group) != null) sb.append(m.group(group));
                i++;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
