package com.epic60869.sky2m;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class Sky2MDiscordScreen extends Screen {
    private static Sky2MDiscordScreen active;

    private final Screen previousScreen;
    private EditBox messageBox;

    private int panelX, panelY, panelW, panelH;
    private String view = "home";
    private String selectedGuildName = "";
    private String selectedChannelId = "";
    private String selectedChannelName = "";
    private String selectedDmUserId = "";
    private String selectedDmName = "";

    private List<Entry> entries = new ArrayList<>();
    private List<MessageRow> messages = new ArrayList<>();
    private String error = "";
    private boolean loading;
    private boolean linked = false;
    private boolean linkKnown = false;
    /** The server turned the first request down (Discord isn't available there); its message is shown instead. */
    private boolean unavailable = false;

    private static final int BG = 0xFF1E1F22;
    private static final int SERVER_BAR = 0xFF111214;
    private static final int SIDEBAR = 0xFF2B2D31;
    private static final int HEADER = 0xFF313338;
    private static final int CHAT = 0xFF313338;
    private static final int HOVER = 0xFF3F4147;
    private static final int ACCENT = 0xFF5865F2;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFFB5BAC1;

    public Sky2MDiscordScreen(Screen previousScreen) {
        super(Component.literal("Discord"));
        this.previousScreen = previousScreen;
        active = this;
    }

    public static void handleResult(String requestId, boolean ok, JsonObject result, String detail) {
        Sky2MDiscordScreen screen = active;
        if (screen == null || screen.minecraft == null) return;
        screen.loading = false;
        if (!ok) {
            screen.error = detail == null || detail.isBlank() ? "Discord request failed." : detail.trim();
            // Nothing loaded yet: the whole screen depends on it, so say so there rather than "Checking..." forever.
            if (!screen.linkKnown) screen.unavailable = true;
            return;
        }
        screen.unavailable = false;
        screen.error = "";
        screen.applyResult(result);
    }

    @Override
    protected void init() {
        super.init();
        active = this;
        rebuildLayout();

        messageBox = new EditBox(font, panelX + 410, panelY + panelH - 52, panelW - 465, 32,
            Component.literal("Message"));
        messageBox.setMaxLength(1900);
        messageBox.setHint(Component.literal("Message"));
        addRenderableWidget(messageBox);

        requestHome();
    }

    private void rebuildLayout() {
        panelW = Math.min(1180, width - 20);
        panelH = Math.min(680, height - 20);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    private void requestHome() {
        view = "home";
        if (!Sky2MGlobalChat.isOnline()) {
            loading = false;
            unavailable = !linkKnown;
            error = "Sky2M chat isn't connected yet. Try again in a moment.";
            Sky2MGlobalChat.ensureConnected();
            return;
        }
        loading = true;
        Sky2MGlobalChat.requestDiscord("home", new JsonObject());
    }

    private void requestChannels(String guildId, String guildName) {
        selectedGuildName = guildName;
        selectedChannelId = "";
        selectedDmUserId = "";
        view = "channels";
        loading = true;
        JsonObject data = new JsonObject();
        data.addProperty("guildId", guildId);
        Sky2MGlobalChat.requestDiscord("channels", data);
    }

    private void requestMessages(String channelId, String channelName) {
        selectedChannelId = channelId;
        selectedChannelName = channelName;
        selectedDmUserId = "";
        view = "messages";
        loading = true;
        JsonObject data = new JsonObject();
        data.addProperty("channelId", channelId);
        Sky2MGlobalChat.requestDiscord("messages", data);
    }

    private void requestDm(String userId, String userName) {
        selectedDmUserId = userId;
        selectedDmName = userName;
        selectedChannelId = "";
        view = "dm";
        loading = true;
        JsonObject data = new JsonObject();
        data.addProperty("userId", userId);
        Sky2MGlobalChat.requestDiscord("openDm", data);
    }

    private void sendMessage() {
        String message = messageBox == null ? "" : messageBox.getValue().trim();
        if (message.isBlank()) return;

        JsonObject data = new JsonObject();
        data.addProperty("message", message);

        if ("dm".equals(view) && !selectedDmUserId.isBlank()) {
            data.addProperty("userId", selectedDmUserId);
            Sky2MGlobalChat.requestDiscord("sendDm", data);
        } else if ("messages".equals(view) && !selectedChannelId.isBlank()) {
            data.addProperty("channelId", selectedChannelId);
            Sky2MGlobalChat.requestDiscord("send", data);
        } else {
            return;
        }

        messageBox.setValue("");
    }

    private void applyResult(JsonObject result) {
        String action = result.has("action") ? result.get("action").getAsString() : "";

        if ("home".equals(action)) {
            if (result.has("linked")) {
                linked = result.get("linked").getAsBoolean();
                linkKnown = true;
            }

            entries = new ArrayList<>();
            if (result.has("guilds") && result.get("guilds").isJsonArray()) {
                JsonArray guilds = result.getAsJsonArray("guilds");
                for (int i = 0; i < guilds.size(); i++) {
                    JsonObject g = guilds.get(i).getAsJsonObject();
                    entries.add(new Entry("guild", g.get("id").getAsString(), g.get("name").getAsString()));
                }
            }
            if (result.has("dms") && result.get("dms").isJsonArray()) {
                JsonArray dms = result.getAsJsonArray("dms");
                for (int i = 0; i < dms.size(); i++) {
                    JsonObject d = dms.get(i).getAsJsonObject();
                    entries.add(new Entry("dm", d.get("id").getAsString(), d.get("name").getAsString()));
                }
            }
            messages = new ArrayList<>();
            return;
        }

        if ("channels".equals(action)) {
            entries = new ArrayList<>();
            if (result.has("guild")) {
                selectedGuildName = result.getAsJsonObject("guild").get("name").getAsString();
            }
            if (result.has("channels") && result.get("channels").isJsonArray()) {
                JsonArray channels = result.getAsJsonArray("channels");
                for (int i = 0; i < channels.size(); i++) {
                    JsonObject ch = channels.get(i).getAsJsonObject();
                    entries.add(new Entry("channel", ch.get("id").getAsString(), ch.get("name").getAsString()));
                }
            }
            return;
        }

        if ("messages".equals(action) || "openDm".equals(action)) {
            messages = new ArrayList<>();
            if (result.has("messages") && result.get("messages").isJsonArray()) {
                JsonArray rows = result.getAsJsonArray("messages");
                for (int i = 0; i < rows.size(); i++) {
                    JsonObject m = rows.get(i).getAsJsonObject();
                    messages.add(new MessageRow(
                        m.has("author") ? m.get("author").getAsString() : "Unknown",
                        m.has("content") ? m.get("content").getAsString() : "",
                        m.has("timestamp") ? m.get("timestamp").getAsString() : ""
                    ));
                }
            }
            if ("openDm".equals(action) && result.has("user")) {
                JsonObject u = result.getAsJsonObject("user");
                selectedDmUserId = u.get("id").getAsString();
                selectedDmName = u.get("name").getAsString();
            }
            return;
        }

        if ("send".equals(action) || "sendDm".equals(action)) {
            if (result.has("message")) {
                JsonObject m = result.getAsJsonObject("message");
                messages.add(new MessageRow(
                    m.has("author") ? m.get("author").getAsString() : "You",
                    m.has("content") ? m.get("content").getAsString() : "",
                    m.has("timestamp") ? m.get("timestamp").getAsString() : ""
                ));
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        rebuildLayout();
        graphics.fill(0, 0, width, height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG);

        int serverW = 72;
        int sidebarW = 255;
        int contentX = panelX + serverW + sidebarW;

        graphics.fill(panelX, panelY, panelX + serverW, panelY + panelH, SERVER_BAR);
        graphics.fill(panelX + serverW, panelY, panelX + serverW + sidebarW, panelY + panelH, SIDEBAR);
        graphics.fill(contentX, panelY, panelX + panelW, panelY + panelH, CHAT);
        graphics.fill(contentX, panelY, panelX + panelW, panelY + 48, HEADER);

        drawServerRail(graphics, mouseX, mouseY);
        drawSidebar(graphics, mouseX, mouseY);
        drawHeader(graphics);
        drawContent(graphics, mouseX, mouseY);

        if (messageBox != null) messageBox.visible = linkKnown && linked && !unavailable
            && (view.equals("messages") || view.equals("dm"));
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        if (loading) {
            graphics.text(font, Component.literal("Loading..."), contentX + 18, panelY + 58, MUTED, false);
        }
        // Errors go above the message box (wrapped), unless the content area already shows it.
        if (!error.isBlank() && !unavailable) {
            List<String> lines = wrap(error, Math.max(24, (panelW - 370) / 6));
            int y = panelY + panelH - 76 - (lines.size() - 1) * 11;
            for (String line : lines) {
                graphics.text(font, Component.literal(line), contentX + 18, y, 0xFFFF6B6B, false);
                y += 11;
            }
        }
    }

    private void drawServerRail(GuiGraphicsExtractor g, int mx, int my) {
        int x = panelX + 11;
        drawCircleLike(g, x + 25, panelY + 34, 25, ACCENT);
        g.text(font, Component.literal("D"), x + 19, panelY + 27, 0xFFFFFFFF, true);

        int y = panelY + 78;
        int shown = 0;
        for (Entry e : entries) {
            if (!"guild".equals(e.type) || shown >= 12) continue;
            int cy = y + shown * 52;
            boolean hover = mx >= x && mx < x + 50 && my >= cy - 2 && my < cy + 48;
            drawCircleLike(g, x + 25, cy + 23, 23, hover ? 0xFF5865F2 : 0xFF36393F);
            String letter = e.name.isBlank() ? "?" : e.name.substring(0, 1).toUpperCase();
            int tw = font.width(Component.literal(letter));
            g.text(font, Component.literal(letter), x + 25 - tw / 2, cy + 16, TEXT, true);
            shown++;
        }

        drawCircleLike(g, x + 25, panelY + panelH - 34, 23, 0xFF36393F);
        g.text(font, Component.literal("+"), x + 20, panelY + panelH - 41, 0xFF43B581, true);
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        int x = panelX + 72;
        g.text(font, Component.literal(selectedGuildName.isBlank() ? "Discord" : selectedGuildName),
            x + 16, panelY + 17, TEXT, true);
        g.fill(x, panelY + 47, x + 255, panelY + 48, 0xFF202225);

        if (view.equals("home")) {
            g.text(font, Component.literal("DIRECT MESSAGES"), x + 16, panelY + 67, MUTED, true);
            int y = panelY + 88;
            int i = 0;
            for (Entry e : entries) {
                if (!"dm".equals(e.type)) continue;
                drawSidebarEntry(g, e, x + 10, y + i * 34, 235, mx, my);
                i++;
            }
            if (i == 0 && linkKnown && !linked) {
                g.text(font, Component.literal("Connect Discord to continue."), x + 16, panelY + 94, MUTED, false);
            }
            return;
        }

        g.text(font, Component.literal("TEXT CHANNELS"), x + 16, panelY + 67, MUTED, true);
        int y = panelY + 88;
        int i = 0;
        for (Entry e : entries) {
            if (!"channel".equals(e.type)) continue;
            drawSidebarEntry(g, e, x + 10, y + i * 32, 235, mx, my);
            i++;
        }
    }

    private void drawSidebarEntry(GuiGraphicsExtractor g, Entry e, int x, int y, int w, int mx, int my) {
        boolean selected = e.id.equals(selectedChannelId) || e.id.equals(selectedDmUserId);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 28;
        if (selected || hover) g.fill(x, y, x + w, y + 28, selected ? 0xFF404249 : HOVER);
        String prefix = "channel".equals(e.type) ? "# " : "";
        g.text(font, Component.literal(prefix + e.name), x + 10, y + 8, selected ? TEXT : MUTED, false);
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        int x = panelX + 327;
        String title = view.equals("home") ? "Friends / DMs" :
            view.equals("channels") ? selectedGuildName :
            view.equals("dm") ? selectedDmName : "# " + selectedChannelName;
        g.text(font, Component.literal(title), x + 18, panelY + 17, TEXT, true);

        if (!view.equals("home")) {
            g.text(font, Component.literal("←"), x + 2, panelY + 16, MUTED, true);
        }

        drawSmallButton(g, panelX + panelW - 78, panelY + 10, 58, 28, "X");
    }

    private void drawContent(GuiGraphicsExtractor g, int mx, int my) {
        int x = panelX + 327;

        if (unavailable) {
            drawUnavailable(g, x);
            return;
        }
        if (!linkKnown || !linked) {
            drawLinkScreen(g, x, my);
            return;
        }

        if (view.equals("home")) {
            g.text(font, Component.literal("Welcome to Discord"), x + 24, panelY + 86, TEXT, true);
            g.text(font, Component.literal("Select a server or direct message from the left."),
                x + 24, panelY + 108, MUTED, false);
            return;
        }

        drawMessages(g, x);
    }

    private void drawLinkScreen(GuiGraphicsExtractor g, int x, int my) {
        int centerX = x + (panelW - 327) / 2;
        int y = panelY + 155;

        String title = linkKnown && !linked ? "Connect your Discord" : "Checking Discord connection...";
        int tw = font.width(Component.literal(title));
        g.text(font, Component.literal(title), centerX - tw / 2, y, TEXT, true);

        if (linkKnown && !linked) {
            String sub = "Link your account to use Discord inside Minecraft.";
            int sw = font.width(Component.literal(sub));
            g.text(font, Component.literal(sub), centerX - sw / 2, y + 25, MUTED, false);
            drawButton(g, centerX - 105, y + 60, 210, 36, "Link Discord", 0, 0);
            g.text(font, Component.literal("Your Discord password is entered only on Discord."),
                centerX - 155, y + 111, MUTED, false);
        }
    }

    /** The server can't do Discord right now: its reason, centred, and how to try again. */
    private void drawUnavailable(GuiGraphicsExtractor g, int x) {
        int centerX = x + (panelW - 327) / 2;
        int y = panelY + 155;
        String title = "Discord isn't available";
        g.text(font, Component.literal(title), centerX - font.width(title) / 2, y, TEXT, true);
        y += 25;
        for (String line : wrap(error.isBlank() ? "The Sky2M server turned the request down." : error, Math.max(24, (panelW - 400) / 6))) {
            g.text(font, Component.literal(line), centerX - font.width(line) / 2, y, MUTED, false);
            y += 12;
        }
        String retry = "Reopen this screen to try again.";
        g.text(font, Component.literal(retry), centerX - font.width(retry) / 2, y + 12, MUTED, false);
    }

    private void drawMessages(GuiGraphicsExtractor g, int x) {
        int y = panelY + 68;
        int bottom = panelY + panelH - 68;
        int lineY = bottom;

        for (int i = messages.size() - 1; i >= 0 && lineY > y; i--) {
            MessageRow row = messages.get(i);
            String author = row.author;
            g.text(font, Component.literal(author), x + 20, lineY - 15, 0xFFFFFFFF, true);

            List<String> wrapped = wrap(row.content, Math.max(24, (panelW - 370) / 7));
            for (int j = wrapped.size() - 1; j >= 0 && lineY > y; j--) {
                g.text(font, Component.literal(wrapped.get(j)), x + 20, lineY + j * 11,
                    TEXT, false);
            }
            lineY -= Math.max(28, wrapped.size() * 11 + 12);
        }

        if (messages.isEmpty() && !loading) {
            g.text(font, Component.literal("No messages yet."), x + 20, y + 20, MUTED, false);
        }

        g.fill(x + 15, panelY + panelH - 60, panelX + panelW - 15, panelY + panelH - 12, 0xFF383A40);
    }

    private List<String> wrap(String text, int chars) {
        List<String> result = new ArrayList<>();
        String remaining = text == null ? "" : text;
        while (remaining.length() > chars) {
            int cut = remaining.lastIndexOf(' ', chars);
            if (cut <= 0) cut = chars;
            result.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) result.add(remaining);
        return result;
    }

    private void drawButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF4752C4 : ACCENT);
        int tw = font.width(Component.literal(label));
        g.text(font, Component.literal(label), x + (w - tw) / 2, y + (h - 9) / 2, 0xFFFFFFFF, true);
    }

    private void drawSmallButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label) {
        g.fill(x, y, x + w, y + h, 0xFF404249);
        int tw = font.width(Component.literal(label));
        g.text(font, Component.literal(label), x + (w - tw) / 2, y + 9, MUTED, true);
    }

    private void drawCircleLike(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
        g.fill(cx - radius, cy - radius, cx + radius, cy + radius, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);

        int mx = (int) event.x();
        int my = (int) event.y();

        if (mx >= panelX + panelW - 78 && mx < panelX + panelW - 20
            && my >= panelY + 10 && my < panelY + 38) {
            minecraft.gui.setScreen(previousScreen);
            active = null;
            return true;
        }

        int contentX = panelX + 327;

        if (unavailable) return super.mouseClicked(event, doubleClick);
        if (!linkKnown || !linked) {
            int centerX = contentX + (panelW - 327) / 2;
            int y = panelY + 215;
            if (mx >= centerX - 105 && mx < centerX + 105 && my >= y && my < y + 36) {
                openDiscordLink();
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        if (!view.equals("home") && mx >= contentX && mx < contentX + 42
            && my >= panelY + 8 && my < panelY + 42) {
            requestHome();
            return true;
        }

        if (view.equals("home")) {
            int x = panelX + 72;
            int y = panelY + 88;
            int i = 0;
            for (Entry e : entries) {
                if (!"dm".equals(e.type)) continue;
                if (mx >= x + 10 && mx < x + 245 && my >= y + i * 34 && my < y + i * 34 + 28) {
                    requestDm(e.id, e.name);
                    return true;
                }
                i++;
            }

            int railX = panelX + 11;
            int railY = panelY + 78;
            int guildIndex = 0;
            for (Entry e : entries) {
                if (!"guild".equals(e.type)) continue;
                int cy = railY + guildIndex * 52;
                if (mx >= railX && mx < railX + 50 && my >= cy - 2 && my < cy + 48) {
                    requestChannels(e.id, e.name);
                    return true;
                }
                guildIndex++;
            }
        } else if (view.equals("channels")) {
            int x = panelX + 82;
            int y = panelY + 88;
            int i = 0;
            for (Entry e : entries) {
                if (!"channel".equals(e.type)) continue;
                if (mx >= x && mx < x + 235 && my >= y + i * 32 && my < y + i * 32 + 28) {
                    requestMessages(e.id, e.name);
                    return true;
                }
                i++;
            }
        }

        if ((view.equals("messages") || view.equals("dm"))
            && mx >= panelX + panelW - 86 && mx < panelX + panelW - 16
            && my >= panelY + panelH - 55 && my < panelY + panelH - 15) {
            sendMessage();
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    private void openDiscordLink() {
        try {
            String uuid = Minecraft.getInstance().getUser().getProfileId().toString();
            String url = "https://shadowisabot.com/mod-api/discord/link?minecraft=" + uuid;
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                error = "Open https://shadowisabot.com/mod-api/discord/link in your browser.";
            }
        } catch (Exception e) {
            error = "Could not open your browser.";
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private record Entry(String type, String id, String name) {}
    private record MessageRow(String author, String content, String timestamp) {}
}
