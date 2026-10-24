package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MUpdateChecker;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Messages from the Sky2M server itself: new versions (updateAvailable; required ones are shown on every join and
 * on the title screen), announcements (chat line, big title or popup, by their style) and the message of the day
 * (once per connection). Messages that arrive before you're in a world wait until you join one.
 */
public final class SbcServer {
    private record Update(String current, String version, String title, String notes, String url, boolean required, String message) {}

    private static Update update;
    private static boolean shownThisJoin;
    private static final List<Component> WAITING = new ArrayList<>();
    private static final String DEFAULT_URL = "https://github.com/2m3s/Sky2M-26.3/releases/latest";

    private SbcServer() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> shownThisJoin = false);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            if (!WAITING.isEmpty()) {
                List<Component> lines = new ArrayList<>(WAITING);
                WAITING.clear();
                lines.forEach(Sbc::say);
            }
            // A required update is shown every time you join a server.
            if (update != null && update.required() && !shownThisJoin && mc.player.tickCount > 60) {
                shownThisJoin = true;
                showUpdate(update);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof TitleScreen)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                Update u = update;
                if (u == null || !u.required()) return;
                var font = Minecraft.getInstance().font;
                String line = "Sky2M " + u.version() + " is a required update. Click here to download it.";
                int width = font.width(line);
                int x = (s.width - width) / 2;
                boolean hover = mouseX >= x - 4 && mouseX <= x + width + 4 && mouseY >= 2 && mouseY <= 16;
                graphics.fill(x - 4, 2, x + width + 4, 16, hover ? 0xE0801010 : 0xC0501010);
                graphics.text(font, line, x, 5, 0xFFFFD35A, true);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                Update u = update;
                if (u == null || !u.required() || event.y() > 16) return true;
                ConfirmLinkScreen.confirmLinkNow(s, java.net.URI.create(u.url()));
                return false;
            });
        });
    }

    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "updateAvailable" -> onUpdate(packet);
            case "announcement" -> onAnnouncement(packet);
            case "motd" -> {
                if (!Sbc.config().server.motd) return;
                String message = Sbc.str(packet, "message");
                if (message.isBlank()) return;
                later(Component.literal("Message of the day: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
            }
            default -> {}
        }
    }

    private static void onUpdate(JsonObject packet) {
        JsonObject latest = Sbc.obj(packet, "latest");
        String version = Sbc.str(latest, "version").replaceFirst("^[vV]", "");
        if (version.isEmpty()) return;
        String url = Sbc.str(latest, "downloadUrl");
        // This is the Minecraft 26.3 build (versions from 1.0); the server's versions are the 26.2 build's, so only a
        // release meant for 26.3 counts.
        if (!url.contains("26.3")) return;
        boolean required = Sbc.bool(packet, "required") || Sbc.bool(latest, "required");
        Update u = new Update(Sbc.str(packet, "current").isEmpty() ? SbcInfo.modVersion() : Sbc.str(packet, "current"), version,
            Sbc.str(latest, "title"), Sbc.str(latest, "notes"), url.startsWith("http") ? url : DEFAULT_URL, required, Sbc.str(packet, "message"));
        boolean seen = update != null && update.version().equals(version);
        update = u;
        Sky2MConfig c = Sky2MConfig.current();
        boolean wanted = c == null || c.general.updateNotifications;
        // The GitHub checker must not say the same thing again.
        Sky2MUpdateChecker.markAnnounced(version);
        if (required) {
            shownThisJoin = true;
            later(updateMessage(u));
        } else if (wanted && !seen) {
            later(updateMessage(u));
        }
    }

    private static void showUpdate(Update u) {
        Sbc.say(updateMessage(u));
    }

    private static Component updateMessage(Update u) {
        MutableComponent line = Component.literal(u.required() ? "Required Sky2M Update " : "New Sky2M Mod Version ")
            .withStyle(u.required() ? ChatFormatting.RED : ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
            .append(Component.literal(u.current()).withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(false)))
            .append(Component.literal(" --> ").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)))
            .append(Component.literal(u.version()).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(false)));
        try {
            line.append(Component.literal("  [Download]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(false).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(u.url())))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Download Sky2M " + u.version())))));
        } catch (Exception ignored) {}
        line.append(com.epic60869.sky2m.Sky2MUpdateChecker.githubLink());
        if (!u.notes().isBlank() || !u.title().isBlank()) {
            MutableComponent notes = Component.literal(u.title().isBlank() ? "Changelog" : u.title()).withStyle(ChatFormatting.GOLD);
            if (!u.notes().isBlank()) notes.append(Component.literal("\n" + u.notes()).withStyle(ChatFormatting.GRAY));
            line.append(Component.literal("  [Changelog]").withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(false)
                .withHoverEvent(new HoverEvent.ShowText(notes))));
        }
        if (!u.message().isBlank()) line.append(Component.literal("\n" + u.message()).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)));
        return line;
    }

    private static void onAnnouncement(JsonObject packet) {
        if (!Sbc.config().server.announcements) return;
        String title = Sbc.str(packet, "title");
        String message = Sbc.str(packet, "message");
        String by = Sbc.str(packet, "by");
        switch (Sbc.str(packet, "style")) {
            case "title" -> {
                Sky2MAlerts.title(Component.literal(title.isBlank() ? message : title).withStyle(ChatFormatting.GOLD),
                    title.isBlank() ? null : Component.literal(message).withStyle(ChatFormatting.WHITE));
                later(announcementLine(title, message, by));
            }
            case "toast" -> Sbc.toast(Component.literal(title.isBlank() ? "Sky2M" : title).withStyle(ChatFormatting.GOLD),
                Component.literal(message));
            default -> later(announcementLine(title, message, by));
        }
    }

    private static Component announcementLine(String title, String message, String by) {
        MutableComponent line = Component.literal("Announcement").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(": ").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)));
        if (!title.isBlank()) line.append(Component.literal(title + " ").withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true)));
        line.append(Component.literal(message).withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withBold(false)));
        if (!by.isBlank()) line.append(Component.literal(" - " + by).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withBold(false)));
        return line;
    }

    /** Shows the line now if you're in a world, otherwise when you join one. */
    private static void later(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) Sbc.say(line);
        else if (WAITING.size() < 10) WAITING.add(line);
    }
}
