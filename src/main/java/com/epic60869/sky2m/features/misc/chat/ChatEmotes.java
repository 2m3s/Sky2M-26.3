package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chat > Chat Emotes: Hypixel's MVP++ chat emotes for everyone. Typing o/, &lt;3, :shrug: and the rest in chat (or in
 * /pc, /gc, /msg and the other chat commands) sends the emote itself, so everyone sees it, not just Sky2M users.
 */
public final class ChatEmotes {
    /** Hypixel's list: the MVP++ emotes, then the ones gifting ranks unlocks. */
    public static final Map<String, String> EMOTES = new LinkedHashMap<>();

    static {
        EMOTES.put("<3", "❤");
        EMOTES.put(":star:", "✮");
        EMOTES.put(":yes:", "✔");
        EMOTES.put(":no:", "✖");
        EMOTES.put(":java:", "☕");
        EMOTES.put(":arrow:", "➜");
        EMOTES.put(":shrug:", "¯\\_(ツ)_/¯");
        EMOTES.put(":tableflip:", "(╯°□°）╯︵ ┻━┻");
        EMOTES.put("o/", "( ﾟ◡ﾟ)/");
        EMOTES.put(":123:", "123");
        EMOTES.put(":totem:", "☉_☉");
        EMOTES.put(":typing:", "✎...");
        EMOTES.put(":maths:", "√(π+x)=L");
        EMOTES.put(":snail:", "@'-'");
        EMOTES.put(":thinking:", "(0.o?)");
        EMOTES.put(":gimme:", "༼つ◕_◕༽つ");
        EMOTES.put(":wizard:", "('-')⊃━☆ﾟ.*･｡ﾟ");
        EMOTES.put(":pvp:", "⚔");
        EMOTES.put(":peace:", "✌");
        EMOTES.put(":oof:", "OOF");
        EMOTES.put(":puffer:", "<('O')>");
        EMOTES.put(":yey:", "ヽ (◕◡◕) ﾉ");
        EMOTES.put(":cat:", "= ^● ⋏ ●^ =");
        EMOTES.put(":dab:", "<o/");
        EMOTES.put(":dj:", "ヽ(⌐■_■)ノ♬");
        EMOTES.put(":snow:", "☃");
        EMOTES.put(":^_^:", "^_^");
        EMOTES.put(":h/:", "ヽ(^◇^*)/");
        EMOTES.put(":^-^:", "^-^");
        EMOTES.put(":sloth:", "(・⊝・)");
        EMOTES.put(":cute:", "(✿◠‿◠)");
        EMOTES.put(":dog:", "(ᵔᴥᵔ)");
    }

    /**
     * Every emote in one pass, so an emote's own text (":dab:" gives "&lt;o/") isn't replaced again. "o/" only counts
     * as its own word, so links and paths ("photo/", "go/") are left alone.
     */
    private static final Pattern TOKENS;

    static {
        StringBuilder alternatives = new StringBuilder();
        EMOTES.keySet().stream().sorted((a, b) -> b.length() - a.length()).forEach(key -> {
            if (!alternatives.isEmpty()) alternatives.append('|');
            alternatives.append(key.equals("o/") ? "(?<!\\S)o/(?!\\S)" : Pattern.quote(key));
        });
        TOKENS = Pattern.compile(alternatives.toString());
    }

    /** Chat commands whose whole text is the message, and the ones with a player name first. */
    private static final Set<String> CHAT_COMMANDS = Set.of("pc", "pchat", "ac", "achat", "gc", "gchat", "oc", "ochat",
        "cc", "cchat", "shout", "r", "reply");
    private static final Set<String> PLAYER_CHAT_COMMANDS = Set.of("msg", "w", "tell", "whisper", "message", "t");

    /** Minecraft's longest chat message: longer text isn't changed rather than being cut off or refused. */
    private static final int MAX_LENGTH = 256;

    private ChatEmotes() {}

    public static void init() {
        ClientSendMessageEvents.MODIFY_CHAT.register(message -> enabled() ? replaceFitting(message, 0) : message);
        ClientSendMessageEvents.MODIFY_COMMAND.register(command -> enabled() ? replaceCommand(command) : command);
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.chat.chatEmotes;
    }

    /** The text with every emote replaced. */
    public static String replace(String text) {
        if (text == null || text.isEmpty()) return text;
        return TOKENS.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(EMOTES.get(m.group().trim())));
    }

    /** {@link #replace}, unless that makes the message (with {@code extra} more characters before it) too long. */
    static String replaceFitting(String text, int extra) {
        String replaced = replace(text);
        return replaced.length() + extra > MAX_LENGTH ? text : replaced;
    }

    /** A chat command (without its slash) with emotes replaced in its message part; other commands are unchanged. */
    public static String replaceCommand(String command) {
        String[] words = command.split(" ", 3);
        String name = words[0].toLowerCase(Locale.ROOT);
        int prefixWords = CHAT_COMMANDS.contains(name) ? 1 : PLAYER_CHAT_COMMANDS.contains(name) ? 2 : 0;
        if (prefixWords == 0 || words.length <= prefixWords) return command;
        String prefix = String.join(" ", java.util.Arrays.copyOf(words, prefixWords)) + " ";
        String text = command.substring(prefix.length());
        return prefix + replaceFitting(text, prefix.length() + 1);
    }
}
