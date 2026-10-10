package com.epic60869.sky2m;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact-chat implementation modelled after the behaviour of the Compacting
 * Fabric mod: a message is compacted according to how recently the same
 * message was last seen, rather than only when two copies are adjacent.
 *
 * The original implementation only compared allMessages[0] and [1], which
 * breaks as soon as another chat line is inserted between two repetitions.
 */
public final class Sky2MChatCompactor {
    private static final int WINDOW_TICKS = 100; // 5 seconds at Minecraft's 20 TPS
    private static final Pattern COUNT_SUFFIX =
        Pattern.compile("\\s*\\u00a7?7?\\s*\\(x(\\d+)\\)$");
    /** Hypixel's decorative lines (▬▬▬, ----, blank padding): never compacted, they belong to the message around them. */
    private static final Pattern SEPARATOR = Pattern.compile("^[\\s▬\\-=_~*⏤━─]*$");

    private static String lastKey;
    private static long lastAt;
    private static final java.util.Map<String, Component> BASE_MESSAGES = new java.util.HashMap<>();

    private Sky2MChatCompactor() {}

    public static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.chat != null && c.chat.compactChat;
    }

    /** Returns a stable comparison key with our own xN suffix removed. */
    public static String key(Component message) {
        if (message == null) return "";
        String text = message.getString();
        Matcher m = COUNT_SUFFIX.matcher(text);
        return m.find() ? text.substring(0, m.start()).stripTrailing() : text;
    }

    public static boolean same(Component a, Component b) {
        String aa = key(a);
        String bb = key(b);
        return !aa.isBlank() && aa.equals(bb);
    }

    /**
     * Records the newest message and returns whether it is inside the
     * "recently seen" window. Minecraft's GuiMessage.addedTime is a creation tick,
     */
    public static boolean recentlySeen(Component message) {
        if (!enabled() || message == null) return false;
        String k = key(message);
        if (k.isBlank()) return false;

        long now = System.currentTimeMillis();
        boolean recent = k.equals(lastKey) && now - lastAt <= WINDOW_TICKS;
        lastKey = k;
        lastAt = now;
        return recent;
    }

    /**
     * Compact the newest chat message against a matching copy that arrived
     * of the same message. This deliberately does not require adjacency.
     *
     * Returns true when an older copy was merged into the newest message.
     */
    public static boolean compact(java.util.List<GuiMessage> messages) {
        if (!enabled() || messages == null || messages.size() < 2) return false;

        GuiMessage newest = messages.get(0);
        String key = key(newest.content());
        if (key.isBlank() || SEPARATOR.matcher(key).matches()) return false;

        long now = newest.addedTime();
        for (int i = 1; i < messages.size(); i++) {
            GuiMessage old = messages.get(i);
            // Newest first: everything from here on is older than the window. Without this, every new line was
            // compared with the whole chat history (huge with More Chat History), which lagged on bursts of
            // messages such as the bestiary's.
            long age = Math.max(0L, now - old.addedTime());
            if (age > WINDOW_TICKS) break;
            if (!key.equals(key(old.content()))) continue;

            int oldCount = count(old.content());
            int newCount = oldCount + 1;
            Component base = BASE_MESSAGES.getOrDefault(key, stripCount(old.content()));
            BASE_MESSAGES.putIfAbsent(key, base.copy());

            // The count stacks onto the newest (bottom) line and the older copy is removed.
            Component compacted = withCount(base, newCount);
            messages.set(0, new GuiMessage(
                newest.addedTime(), compacted, newest.signature(), newest.source(), newest.tag()
            ));
            messages.remove(i);
            return true;
        }

        return false;
    }

    public static int record(Component message) {
        recentlySeen(message);
        return count(message);
    }

    public static Component withCount(Component message, int value) {
        if (message == null || value <= 1) return message;

        MutableComponent out = message.copy();
        out.append(Component.literal(" §7(x" + value + ")"));
        return out;
    }

    public static int count(Component message) {
        if (message == null) return 1;
        Matcher m = COUNT_SUFFIX.matcher(message.getString());
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static Component stripCount(Component message) {
        if (message == null) return Component.empty();
        String text = message.getString();
        Matcher m = COUNT_SUFFIX.matcher(text);
        if (!m.find()) return message;

        // This path is only used when recovering a pre-existing compacted
        // message. New messages are stored in BASE_MESSAGES so their styling
        // is retained for every subsequent count update.
        return Component.literal(text.substring(0, m.start()));
    }

    public static void clear() {
        lastKey = null;
        lastAt = 0L;
        BASE_MESSAGES.clear();
    }
}
