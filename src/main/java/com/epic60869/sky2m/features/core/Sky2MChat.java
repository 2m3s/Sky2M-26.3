package com.epic60869.sky2m.features.core;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single entry point for chat and action bar messages, shared by all Sky2M features.
 * Messages are read from the system chat packet (see Sky2MSystemChatMixin) rather than Fabric's
 * receive events, so Sky2M still sees messages that another mod hides (e.g. [BOSS] dialogue).
 */
public final class Sky2MChat {
    /** A received message with its formatting-stripped text. */
    public record Message(Component component, String text) {}

    public interface Listener {
        void onMessage(Message message);
    }

    /** Raw listener for both chat and action bar messages. */
    public interface GameListener {
        void onMessage(Component message, boolean overlay);
    }

    private static final List<Listener> CHAT = new CopyOnWriteArrayList<>();
    private static final List<Listener> ACTION_BAR = new CopyOnWriteArrayList<>();
    private static final List<GameListener> GAME = new CopyOnWriteArrayList<>();

    private Sky2MChat() {}

    public static void init() {}

    public static void onChat(Listener listener) { CHAT.add(listener); }
    public static void onActionBar(Listener listener) { ACTION_BAR.add(listener); }
    public static void onGameMessage(GameListener listener) { GAME.add(listener); }

    /** Called on the render thread for every system chat packet, before other mods can cancel it. */
    public static void onPacket(Component component, boolean overlay) {
        for (GameListener listener : GAME) {
            try {
                listener.onMessage(component, overlay);
            } catch (Exception e) {
                com.epic60869.sky2m.features.sbc.SbcCrashReports.report(e, "chat listener");
            }
        }
        dispatch(overlay ? ACTION_BAR : CHAT, component);
    }

    private static void dispatch(List<Listener> listeners, Component component) {
        if (listeners.isEmpty()) return;
        Message message = new Message(component, Sky2MLocation.strip(component.getString()));
        for (Listener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                com.epic60869.sky2m.features.sbc.SbcCrashReports.report(e, "chat listener");
            }
        }
    }
}
