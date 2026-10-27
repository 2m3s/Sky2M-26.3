package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.epic60869.sky2m.features.misc.social.Sky2MAchievements;

/**
 * Chat > Chat Filters: hides lines from chat. Only the display is filtered: Sky2M's own features still read every
 * message (they listen to the chat packet first). Ads are only looked for in all-chat messages from players, never in
 * party, guild, co-op or private messages.
 */
public final class ChatFilters {
    private static final Pattern LOBBY_JOIN = Pattern.compile("^(?:>>> )?\\[[A-Z+]+] \\w{1,16} .*(?:joined|into) the lobby!(?: <<<)?$");
    private static final Pattern MYSTERY_BOX = Pattern.compile("^(?:\\[Mystery Box] |✦ )?\\w{1,16} found an? .*Mystery Box!?.*$|^\\[Mystery Box].*");
    /** "[123] [MVP+] Name: hello" or "Name: hello" with nothing in front saying it's party, guild or private chat. */
    private static final Pattern ALL_CHAT = Pattern.compile("^(?:\\[\\d+] )?(?:\\S )?(?:\\[[A-Z+]+] )?\\w{1,16}(?: \\S)?: .+");
    private static final List<String> PRIVATE_PREFIXES = List.of("Party > ", "Guild > ", "Officer > ", "Co-op > ", "From ", "To ", "[S2M]");
    private static final List<String> WATCHDOG = List.of("[WATCHDOG ANNOUNCEMENT]", "Watchdog has banned", "Staff have banned an additional",
        "Blacklisted modifications are a bannable offense!");
    private static final List<String> WARPING = List.of("Sending to server ", "Warping...", "Warping you to your SkyBlock island...", "Request join for ",
        "Sending you to ", "Evacuating to Hub...");
    private static final List<String> ABILITY = List.of("This ability is on cooldown for", "There are blocks in the way!",
        "You do not have enough mana to do this!", "This ability is currently on cooldown");

    private static String customSource;
    private static List<Object> custom = List.of();

    private ChatFilters() {}

    private static FeatureConfigs.ChatFilters config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.filters;
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !hidden(message));
    }

    private static boolean hidden(Component message) {
        FeatureConfigs.ChatFilters c = config();
        if (c == null) return false;
        String text = Sky2MLocation.strip(message.getString()).trim();
        if (text.isEmpty()) return false;
        boolean hide = (c.lobbyJoins && LOBBY_JOIN.matcher(text).matches())
            || (c.mysteryBoxes && MYSTERY_BOX.matcher(text).matches())
            || (c.watchdog && startsWithAny(text, WATCHDOG))
            || (c.warping && startsWithAny(text, WARPING))
            || (c.abilitySpam && startsWithAny(text, ABILITY))
            || (c.ads && isAd(text, c.adWords))
            || matchesCustom(text, c.custom);
        if (hide) Sky2MAchievements.add(Sky2MAchievements.Stat.HIDDEN_MESSAGES, 1);
        return hide;
    }

    private static boolean startsWithAny(String text, List<String> prefixes) {
        for (String p : prefixes) if (text.startsWith(p)) return true;
        return false;
    }

    private static boolean isAd(String text, String words) {
        if (startsWithAny(text, PRIVATE_PREFIXES) || !ALL_CHAT.matcher(text).matches()) return false;
        String body = text.substring(text.indexOf(": ") + 2).toLowerCase(Locale.ROOT);
        for (String word : words.split(",")) {
            String w = word.trim().toLowerCase(Locale.ROOT);
            if (!w.isEmpty() && body.contains(w)) return true;
        }
        return false;
    }

    private static boolean matchesCustom(String text, String source) {
        if (source == null || source.isBlank()) return false;
        if (!source.equals(customSource)) {
            customSource = source;
            List<Object> parsed = new ArrayList<>();
            for (String part : source.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
                    try {
                        parsed.add(Pattern.compile(p.substring(1, p.length() - 1), Pattern.CASE_INSENSITIVE));
                    } catch (PatternSyntaxException ignored) {}
                } else {
                    parsed.add(p.toLowerCase(Locale.ROOT));
                }
            }
            custom = parsed;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Object f : custom) {
            if (f instanceof Pattern p ? p.matcher(text).find() : lower.contains((String) f)) return true;
        }
        return false;
    }
}
