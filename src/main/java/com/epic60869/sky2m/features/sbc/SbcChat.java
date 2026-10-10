package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.mixin.Sky2MChatComponentAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sky2M chat (/s2c) beyond sending and showing text: message ids, replies ("↪ Name: text" above the message and a
 * ↩ button after it), mentions (highlight + ping), emoji reactions (shown after the message, updated in place when the
 * counts change), shared items, and the server's "your message was refused" / mute notices.
 */
public final class SbcChat {
    /** A reaction the server allows, and how it's drawn (Minecraft's font has no colour emoji). */
    public record Emoji(String emoji, String label, int colour, String name) {}

    public static final List<Emoji> EMOJIS = List.of(
        new Emoji("👍", "+1", 0x55FF55, "thumbs up"),
        new Emoji("👎", "-1", 0xFF5555, "thumbs down"),
        new Emoji("❤️", "❤", 0xFF5577, "heart"),
        new Emoji("😂", "XD", 0xFFFF55, "laughing"),
        new Emoji("😮", "wow", 0xFFAA00, "surprised"),
        new Emoji("😢", "sad", 0x55AAFF, "sad"),
        new Emoji("🔥", "fire", 0xFF8800, "fire"),
        new Emoji("🎉", "yay", 0xFF55FF, "party"),
        new Emoji("💀", "☠", 0xDDDDDD, "skull"),
        new Emoji("👀", "o_o", 0xAAAAFF, "eyes"),
        new Emoji("✅", "✔", 0x55FF55, "check"),
        new Emoji("❌", "✖", 0xFF5555, "cross"));

    public record Reaction(String emoji, int count, List<String> users) {}

    /** A received message: what replies and reactions need to know. */
    public static final class Stored {
        final long id;
        final String username;
        final String text;
        List<Reaction> reactions = List.of();

        Stored(long id, String username, String text) {
            this.id = id;
            this.username = username;
            this.text = text;
        }
    }

    private static final int KEEP = 200;
    private static final String REPLY_COMMAND = "/s2 reply ";
    private static final String REACT_COMMAND = "/s2 react ";

    private static final Map<Long, Stored> MESSAGES = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Stored> eldest) {
            return size() > KEEP;
        }
    };
    /** Ids of messages that mention you. */
    private static final Set<Long> MENTIONED = new LinkedHashSet<>();
    /** Names seen in Sky2M chat recently, for @name completion. */
    private static final Set<String> RECENT_NAMES = new LinkedHashSet<>();
    /** Id found in each chat message (0 = not an SBC message), so it isn't searched for every frame. */
    private static final Map<GuiMessage, Long> IDS = new IdentityHashMap<>();

    private static long replyTo;
    private static ChatScreen replyScreen;

    private SbcChat() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("reply")
                        .then(ClientCommands.argument("id", LongArgumentType.longArg(1))
                            .executes(c -> SbcCommands.run(() -> startReply(LongArgumentType.getLong(c, "id"))))))
                    .then(ClientCommands.literal("react")
                        .then(ClientCommands.argument("id", LongArgumentType.longArg(1))
                            .then(ClientCommands.argument("emoji", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(EMOJIS.stream().map(Emoji::label).toList(), b))
                                .executes(c -> SbcCommands.run(() -> toggleReaction(LongArgumentType.getLong(c, "id"),
                                    StringArgumentType.getString(c, "emoji")))))))
                    .then(ClientCommands.literal("share")
                        .executes(c -> SbcCommands.run(() -> share("")))
                        .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                            .executes(c -> SbcCommands.run(() -> share(StringArgumentType.getString(c, "message"))))))
                    .then(ClientCommands.literal("viewitem")
                        .then(ClientCommands.argument("key", IntegerArgumentType.integer(1))
                            .executes(c -> SbcCommands.run(() -> viewItem(IntegerArgumentType.getInteger(c, "key"))))))
                    .then(ClientCommands.literal("viewinv")
                        .then(ClientCommands.argument("key", StringArgumentType.word())
                            .executes(c -> SbcCommands.run(() -> SbcItems.viewInventory(StringArgumentType.getString(c, "key")))))));
            }
        });
        // The reply ends when the chat opened for it closes (sent or not).
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen != replyScreen || replyScreen == null) return;
            ScreenEvents.remove(screen).register(s -> {
                if (s == replyScreen) {
                    replyScreen = null;
                    replyTo = 0;
                }
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ incoming

    /** The "[S2M]" at the start of a message; clicking it replies. It also marks the line with the message's id. */
    public static MutableComponent prefix(long id) {
        MutableComponent prefix = Component.literal("[S2M]").withStyle(ChatFormatting.LIGHT_PURPLE);
        if (id > 0) {
            prefix.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(REPLY_COMMAND + id))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to reply").withStyle(ChatFormatting.GRAY))));
        }
        return prefix;
    }

    /**
     * Adds what the plain line doesn't have: the reply it answers (a grey line above), the shared item, the ↩ button
     * and the reactions. Remembers the message and pings when it mentions you. Called on the socket thread.
     */
    public static Component decorate(JsonObject packet, MutableComponent line, String text) {
        SbcConfig.Chat config = Sbc.config().chat;
        long id = Sbc.lng(packet, "id", 0);
        String username = Sbc.str(packet, "username");

        if (Sbc.hasObj(packet, "item") && config.itemSharing && Flags.isEnabled("chat.items")) {
            if (!text.isBlank()) line.append(Component.literal(" "));
            line.append(SbcItems.chatComponent(packet.getAsJsonObject("item")));
        }
        if (id > 0 && config.replies && Flags.isEnabled("chat.replies")) {
            line.append(Component.literal(" ↩").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY)
                .withClickEvent(new ClickEvent.RunCommand(REPLY_COMMAND + id))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Reply to " + username)))));
        }

        boolean mentioned = false;
        for (JsonElement e : Sbc.arr(packet, "mentions")) {
            if (e.isJsonObject() && Sbc.isSelf(Sbc.str(e.getAsJsonObject(), "minecraftUuid"))) mentioned = true;
        }
        boolean fromSelf = Sbc.isSelf(Sbc.str(packet, "minecraftUuid"));

        synchronized (MESSAGES) {
            if (id > 0) MESSAGES.put(id, new Stored(id, username, text));
            if (mentioned && id > 0 && !fromSelf) {
                MENTIONED.add(id);
                while (MENTIONED.size() > KEEP) MENTIONED.remove(MENTIONED.iterator().next());
            }
            String plainName = username.replaceFirst("^\\[Discord] ", "");
            if (plainName.matches("\\w{1,16}")) {
                RECENT_NAMES.remove(plainName);
                RECENT_NAMES.add(plainName);
                while (RECENT_NAMES.size() > 50) RECENT_NAMES.remove(RECENT_NAMES.iterator().next());
            }
        }

        if (mentioned && !fromSelf && config.mentionSound) {
            float volume = config.mentionVolume;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null && volume > 0) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, volume, 1.4f);
            });
        }

        JsonObject reply = Sbc.obj(packet, "replyTo");
        if (reply.size() == 0 || !config.replies || !Flags.isEnabled("chat.replies")) return line;
        String replyName = Sbc.str(reply, "username");
        String replyText = Sbc.str(reply, "message");
        if (replyText.length() > 80) replyText = replyText.substring(0, 80) + "...";
        MutableComponent header = Component.literal("  ↪ " + replyName + ": ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(replyText).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        return Component.empty().append(header).append("\n").append(line);
    }

    /** The SBC message id of a chat message (0 if it isn't one). */
    public static long idOf(GuiMessage message) {
        if (message == null) return 0;
        synchronized (IDS) {
            Long cached = IDS.get(message);
            if (cached != null) return cached;
            if (IDS.size() > 2000) IDS.clear();
            long id = findCommandId(message.content(), REPLY_COMMAND);
            IDS.put(message, id);
            return id;
        }
    }

    private static long findCommandId(Component content, String command) {
        long[] found = {0};
        content.visit((style, text) -> {
            if (style.getClickEvent() instanceof ClickEvent.RunCommand(String run) && run.startsWith(command)) {
                try {
                    found[0] = Long.parseLong(run.substring(command.length()).trim().split(" ")[0]);
                    return Optional.of(Boolean.TRUE);
                } catch (NumberFormatException ignored) {}
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    public static boolean hasMentions() {
        synchronized (MESSAGES) {
            return !MENTIONED.isEmpty();
        }
    }

    public static boolean isMention(long id) {
        synchronized (MESSAGES) {
            return id > 0 && MENTIONED.contains(id);
        }
    }

    public static Stored stored(long id) {
        synchronized (MESSAGES) {
            return MESSAGES.get(id);
        }
    }

    /** Names for @completion: online Sky2M players first, then people seen in chat. */
    public static List<String> mentionNames() {
        Set<String> names = new LinkedHashSet<>(SbcSocial.onlineNames());
        synchronized (MESSAGES) {
            List<String> recent = new ArrayList<>(RECENT_NAMES);
            for (int i = recent.size() - 1; i >= 0; i--) names.add(recent.get(i));
        }
        names.remove(Minecraft.getInstance().getUser().getName());
        return new ArrayList<>(names);
    }

    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "reactions" -> onReactions(packet);
            case "reactionError" -> Sbc.error(orCode(packet, "Couldn't react"));
            case "chatBlocked" -> {
                long until = Sbc.lng(packet, "until", 0);
                String left = until > System.currentTimeMillis() ? " (" + Sbc.duration(until - System.currentTimeMillis()) + " left)" : "";
                Sbc.error(orCode(packet, "Your message wasn't sent") + left);
            }
            case "muted" -> {
                long until = Sbc.lng(packet, "until", 0);
                String message = Sbc.str(packet, "message");
                MutableComponent line = Component.literal(message.isBlank() ? "You were muted in Sky2M chat." : message).withStyle(ChatFormatting.RED);
                line.append(Component.literal(until <= 0 ? " (permanent)" : " (" + Sbc.duration(until - System.currentTimeMillis()) + " left)").withStyle(ChatFormatting.RED));
                String reason = Sbc.str(packet, "reason");
                if (!reason.isBlank()) line.append(Component.literal("\nReason: " + reason).withStyle(ChatFormatting.GRAY));
                String by = Sbc.str(packet, "by");
                if (!by.isBlank()) line.append(Component.literal(" (by " + by + ")").withStyle(ChatFormatting.DARK_GRAY));
                Sbc.say(line);
            }
            case "unmuted" -> Sbc.say(Component.literal(Sbc.str(packet, "message").isBlank() ? "You can talk in Sky2M chat again." : Sbc.str(packet, "message"))
                .withStyle(ChatFormatting.GREEN));
            default -> {}
        }
    }

    private static String orCode(JsonObject packet, String fallback) {
        String message = Sbc.str(packet, "message");
        return message.isBlank() ? fallback + " (" + Sbc.str(packet, "code") + ")." : message;
    }

    // ------------------------------------------------------------------------------------------------ replies

    public static void startReply(long id) {
        if (!Sbc.config().chat.replies || !Flags.check("chat.replies")) return;
        Stored message = stored(id);
        if (message == null) {
            Sbc.error("That message is too old to reply to.");
            return;
        }
        replyTo = id;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ChatScreen screen = new ChatScreen(com.epic60869.sky2m.Sky2MGlobalChat.isInSky2MChannel() ? "" : "/s2c ", false);
            replyScreen = screen;
            Compat.queueOpenScreen(screen);
        });
    }

    /** The message being replied to, or null. */
    public static Stored replying() {
        return replyTo == 0 ? null : stored(replyTo);
    }

    public static void cancelReply() {
        replyTo = 0;
    }

    // ------------------------------------------------------------------------------------------------ outgoing

    /**
     * The packet for a message typed in Sky2M chat, with the reply and item attached, or null if there's nothing
     * to send. "[item]" in the text attaches the item you're holding.
     */
    public static JsonObject outgoing(String text) {
        JsonObject packet = Sbc.packet("message");
        String message = text;
        if (message.toLowerCase(Locale.ROOT).contains("[item]") && Sbc.config().chat.itemSharing && Flags.isEnabled("chat.items")) {
            JsonObject item = SbcItems.toJson(SbcItems.heldOrHovered());
            if (item != null) {
                packet.add("item", item);
                message = message.replaceAll("(?i)\\s*\\[item]\\s*", " ").trim();
            }
        }
        if (message.isBlank() && !packet.has("item")) return null;
        if (message.length() > 2000) message = message.substring(0, 2000);
        packet.addProperty("message", message);
        if (replyTo > 0) {
            packet.addProperty("replyTo", replyTo);
            replyTo = 0;
        }
        return packet;
    }

    /** /s2 share and the Share Item key: sends the held (or hovered) item to Sky2M chat. */
    public static void share(String message) {
        if (!Sbc.config().chat.itemSharing || !Flags.check("chat.items")) return;
        JsonObject item = SbcItems.toJson(SbcItems.heldOrHovered());
        if (item == null) {
            Sbc.error("Hold (or hover) the item you want to share.");
            return;
        }
        JsonObject packet = Sbc.packet("message");
        packet.addProperty("message", message == null ? "" : message.trim());
        packet.add("item", item);
        com.epic60869.sky2m.Sky2MGlobalChat.sendPacket(packet);
    }

    private static void viewItem(int key) {
        JsonObject item = SbcItems.shared(key);
        if (item == null) {
            Sbc.error("That item is too old to show.");
            return;
        }
        Compat.queueOpenScreen(new SbcItemScreen(item));
    }

    // ------------------------------------------------------------------------------------------------ reactions

    public static Emoji emoji(String emojiOrLabel) {
        String wanted = emojiOrLabel.trim();
        for (Emoji e : EMOJIS) {
            if (e.emoji().equals(wanted) || e.label().equalsIgnoreCase(wanted) || e.name().equalsIgnoreCase(wanted)
                || e.emoji().replace("️", "").equals(wanted.replace("️", ""))) return e;
        }
        return null;
    }

    /** Adds your reaction, or takes it back if you already reacted with that emoji. */
    public static void toggleReaction(long id, String emojiOrLabel) {
        if (!Sbc.config().chat.reactions || !Flags.check("chat.reactions")) return;
        Emoji emoji = emoji(emojiOrLabel);
        if (emoji == null) {
            Sbc.error("You can react with: " + String.join(" ", EMOJIS.stream().map(Emoji::label).toList()));
            return;
        }
        boolean remove = false;
        Stored message = stored(id);
        if (message != null) {
            String me = Minecraft.getInstance().getUser().getName();
            for (Reaction r : message.reactions) {
                if (sameEmoji(r.emoji(), emoji.emoji()) && r.users().stream().anyMatch(u -> u.equalsIgnoreCase(me))) remove = true;
            }
        }
        JsonObject packet = Sbc.packet("react");
        packet.addProperty("messageId", id);
        packet.addProperty("emoji", emoji.emoji());
        packet.addProperty("remove", remove);
        SbcNet.sendAuthed(packet);
    }

    private static boolean sameEmoji(String a, String b) {
        return a.replace("️", "").equals(b.replace("️", ""));
    }

    private static void onReactions(JsonObject packet) {
        long id = Sbc.lng(packet, "messageId", 0);
        List<Reaction> reactions = new ArrayList<>();
        for (JsonElement e : Sbc.arr(packet, "reactions")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            List<String> users = new ArrayList<>();
            for (JsonElement u : Sbc.arr(o, "users")) if (u.isJsonPrimitive()) users.add(u.getAsString());
            int count = (int) Sbc.lng(o, "count", users.size());
            if (count > 0) reactions.add(new Reaction(Sbc.str(o, "emoji"), count, users));
        }
        Stored message = stored(id);
        if (message != null) message.reactions = reactions;
        if (Sbc.config().chat.reactions && Flags.isEnabled("chat.reactions")) updateLine(id, reactions);
    }

    /** " [+1 2] [❤ 1]": hover lists who reacted, click adds or takes back yours. */
    private static MutableComponent reactionSuffix(long id, List<Reaction> reactions) {
        MutableComponent out = Component.empty();
        for (Reaction r : reactions) {
            Emoji emoji = emoji(r.emoji());
            String label = emoji == null ? r.emoji() : emoji.label();
            int colour = emoji == null ? 0xAAAAAA : emoji.colour();
            MutableComponent hover = Component.literal((emoji == null ? r.emoji() : emoji.name()) + " (" + r.count() + ")").withStyle(ChatFormatting.YELLOW);
            for (String user : r.users()) hover.append(Component.literal("\n" + user).withStyle(ChatFormatting.GRAY));
            if (r.users().size() < r.count()) hover.append(Component.literal("\n+" + (r.count() - r.users().size()) + " more").withStyle(ChatFormatting.DARK_GRAY));
            hover.append(Component.literal("\nClick to add or remove yours").withStyle(ChatFormatting.DARK_GRAY));
            Style style = Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(REACT_COMMAND + id + " " + label))
                .withHoverEvent(new HoverEvent.ShowText(hover));
            out.append(Component.literal(" [").setStyle(style.withColor(ChatFormatting.DARK_GRAY)))
                .append(Component.literal(label).setStyle(style.withColor(colour)))
                .append(Component.literal(" " + r.count() + "]").setStyle(style.withColor(ChatFormatting.GRAY)));
        }
        return out;
    }

    /** Rewrites the message's line in chat with the new reactions. */
    private static void updateLine(long id, List<Reaction> reactions) {
        Minecraft mc = Minecraft.getInstance();
        Sky2MChatComponentAccessor chat = (Sky2MChatComponentAccessor) mc.gui.hud.getChat();
        List<GuiMessage> all = chat.sky2m$allMessages();
        boolean changed = false;
        for (int i = 0; i < all.size(); i++) {
            GuiMessage message = all.get(i);
            if (idOf(message) != id) continue;
            MutableComponent content = withoutReactions(message.content());
            content.append(reactionSuffix(id, reactions));
            GuiMessage updated = new GuiMessage(message.addedTime(), content, message.signature(), message.source(), message.tag());
            all.set(i, updated);
            synchronized (IDS) {
                IDS.put(updated, id);
            }
            changed = true;
        }
        if (changed) chat.sky2m$refreshTrimmedMessages();
    }

    private static MutableComponent withoutReactions(Component content) {
        MutableComponent out = Component.empty();
        content.visit((style, text) -> {
            if (text.isEmpty()) return Optional.empty();
            if (style.getClickEvent() instanceof ClickEvent.RunCommand(String run) && run.startsWith(REACT_COMMAND)) return Optional.empty();
            out.append(Component.literal(text).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }
}
