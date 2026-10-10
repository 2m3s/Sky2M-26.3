package com.epic60869.sky2m;

import com.epic60869.sky2m.features.core.Sky2MChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows which chat channel you are typing in just above the chat box, like SkyHanni's
 * Current Chat Display (features/chat/CurrentChatDisplay.kt): All, Party, Guild, Officer,
 * Co-op or a private conversation, read from Hypixel's channel messages. Sky2M chat is shown
 * when you have switched to it with /chat sj.
 */
public final class Sky2MCurrentChat {
    // Patterns from SkyHanni's CurrentChatDisplay, on the unformatted message.
    private static final Pattern CHANGED = Pattern.compile("^You are now in the (?<chat>.+) channel$");
    private static final Pattern MOVED_TO_ALL = Pattern.compile(
        "^(?:You are not in a party and were moved to the ALL channel\\.|The conversation you were in expired and you have been moved back to the ALL channel\\.)$");
    private static final Pattern PRIVATE = Pattern.compile(
        "^Opened a chat conversation with (?:\\[.+?] )?(?<player>\\S+) for the next 5 minutes\\. Use /chat a to leave$");
    private static final long PRIVATE_DURATION_MS = 5 * 60 * 1000;

    private static String channel = "ALL";
    private static String privatePlayer;
    private static long privateEnds;
    private static Path file;

    private Sky2MCurrentChat() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("current-chat.txt");
        try {
            if (Files.exists(file)) channel = Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        if (channel.isEmpty() || channel.equals("PRIVATE")) channel = "ALL";

        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            Matcher m = CHANGED.matcher(text);
            if (m.matches()) {
                set(m.group("chat").toUpperCase(Locale.ROOT), null);
            } else if (MOVED_TO_ALL.matcher(text).matches()) {
                set("ALL", null);
            } else if ((m = PRIVATE.matcher(text)).matches()) {
                privateEnds = System.currentTimeMillis() + PRIVATE_DURATION_MS;
                set("PRIVATE", m.group("player"));
            }
        });

    }

    /**
     * Drawn at the start of the chat screen (Sky2MChatScreenMixin), in the gap between the chat history and the
     * input box, so command and emoji suggestions show over it instead of under it.
     */
    public static void render(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int screenHeight) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.chat.currentChatDisplay) return;
        graphics.text(Minecraft.getInstance().font, display(), 2, screenHeight - 26, 0xFFFFFFFF, true);
    }

    private static void set(String newChannel, String player) {
        channel = newChannel;
        privatePlayer = player;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, channel, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static Component display() {
        MutableComponent line = Component.literal("Chat: ").withStyle(ChatFormatting.GREEN);
        if (Sky2MGlobalChat.isInSky2MChannel()) {
            return line.append(Component.literal("Sky2M").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return switch (channel) {
            case "PARTY" -> line.append(Component.literal("Party").withStyle(ChatFormatting.BLUE));
            case "GUILD" -> line.append(Component.literal("Guild").withStyle(ChatFormatting.DARK_GREEN));
            case "OFFICER" -> line.append(Component.literal("Officer").withStyle(ChatFormatting.DARK_AQUA));
            case "SKYBLOCK CO-OP" -> line.append(Component.literal("Co-op").withStyle(ChatFormatting.AQUA));
            case "PRIVATE" -> {
                line.append(Component.literal((privatePlayer == null ? "Unknown" : privatePlayer) + " ").withStyle(ChatFormatting.GOLD));
                long left = privateEnds - System.currentTimeMillis();
                yield left <= 0
                    ? line.append(Component.literal("(EXPIRED)").withStyle(ChatFormatting.RED))
                    : line.append(Component.literal(String.format(Locale.US, "%d:%02d", left / 60000, left / 1000 % 60)).withStyle(ChatFormatting.AQUA));
            }
            default -> line.append(Component.literal("All").withStyle(ChatFormatting.YELLOW));
        };
    }
}
