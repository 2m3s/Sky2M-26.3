package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.mixin.chat.Sky2MChatComponentAccessor;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Compact Bestiary Messages (features/chat/CompactBestiaryChatMessage.kt, LGPL-2.1:
 * https://github.com/hannibal002/SkyHanni): Hypixel's multi-line BESTIARY level-up block becomes one line,
 * "BESTIARY Zombie II➡III", with the whole block (rewards and all) in its hover and Hypixel's click kept. A bestiary
 * milestone gets its own "BESTIARY MILESTONE 4➡5" line.
 * <p>
 * The block is read from {@link Sky2MChat} (the chat packet, before any filter hides a line), so a border hidden by
 * the Spam Filters still ends the block; the lines are then hidden from the main chat in ALLOW_GAME.
 */
public final class CompactBestiary {
    private static final Pattern BORDER = Pattern.compile("^▬{20,}$");
    /** A milestone line ends in "4➡5" (SkyHanni: {@code ^.+(§8\d{1,3}➡§e\d{1,3})$}). */
    private static final Pattern MILESTONE = Pattern.compile("^.+?(\\d{1,3})➡(\\d{1,3})$");
    /** Hypixel's padding lines just before the title, already in chat (SkyHanni: sent under 500 ms ago). */
    private static final int RECENT_TICKS = 10;

    private static boolean inBestiary;
    private static final List<String> description = new ArrayList<>();
    private static boolean acceptMoreDescription = true;
    private static String command = "";
    private static int blockedLines;
    private static String milestoneMessage;

    /** The chat packet whose line ALLOW_GAME should hide (decided by the packet listener just before). */
    private static Component pendingHide;
    private static String pendingHideText;

    private CompactBestiary() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.chat != null && c.chat.compactBestiary;
    }

    public static void init() {
        Sky2MChat.onChat(message -> onMessage(message.component(), message.text()));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !shouldHide(message));
    }

    private static boolean shouldHide(Component message) {
        Component hide = pendingHide;
        if (hide == null) return false;
        boolean same = message == hide || Sky2MLocation.strip(message.getString()).equals(pendingHideText);
        pendingHide = null;
        pendingHideText = null;
        return same;
    }

    private static void hide(Component component, String text) {
        pendingHide = component;
        pendingHideText = text;
    }

    private static void onMessage(Component component, String text) {
        pendingHide = null;
        pendingHideText = null;
        if (!enabled() || !Compat.isOnSkyblock()) {
            reset();
            return;
        }
        String trimmed = text.trim();

        if (trimmed.equals("BESTIARY")) {
            hide(component, text);
            deleteRecentPadding();
            command = clickCommand(component);
            inBestiary = true;
            blockedLines = 0;
            description.clear();
            acceptMoreDescription = true;
            milestoneMessage = null;
            description.add(line(component));
            return;
        }
        if (!inBestiary) return;

        hide(component, text);
        blockedLines++;
        if (blockedLines > 15) {
            reset();
            return;
        }
        if (BORDER.matcher(trimmed).matches()) {
            inBestiary = false;
            sendMilestone();
            send();
            reset();
            return;
        }
        sendMilestone();
        // The family line ("Zombie 4➡5" with numbers on) is the title, never the milestone.
        Matcher m = description.size() > 1 ? MILESTONE.matcher(trimmed) : null;
        if (m != null && m.matches()) {
            acceptMoreDescription = false;
            milestoneMessage = "§8" + m.group(1) + "➡§e" + m.group(2);
        }
        if (acceptMoreDescription) description.add(line(component));
    }

    private static void reset() {
        inBestiary = false;
        description.clear();
        acceptMoreDescription = true;
        blockedLines = 0;
        command = "";
    }

    /** A block line as § text, without SkyHanni's removed white (§f) and the indent. */
    private static String line(Component component) {
        return SbcItems.legacy(component).replace("§r", "").replace("§f", "").trim();
    }

    private static void sendMilestone() {
        if (milestoneMessage == null) return;
        String milestone = milestoneMessage;
        milestoneMessage = null;
        addToChat(SbcItems.parseLegacy("§6§lBESTIARY MILESTONE §r" + milestone));
    }

    private static void send() {
        if (description.size() < 2) return;
        List<String> lines = new ArrayList<>(description);
        while (lines.size() > 2 && Sky2MLocation.strip(lines.get(lines.size() - 1)).isBlank()) lines.remove(lines.size() - 1);
        String title = lines.get(1);
        MutableComponent hover = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) hover.append("\n");
            hover.append(SbcItems.parseLegacy(lines.get(i)));
        }
        MutableComponent message = SbcItems.parseLegacy("§6§lBESTIARY §r" + title);
        String run = command;
        message.withStyle(s -> {
            s = s.withHoverEvent(new HoverEvent.ShowText(hover));
            return run.isEmpty() ? s : s.withClickEvent(new ClickEvent.RunCommand(run));
        });
        addToChat(message);
    }

    private static void addToChat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    /** Hypixel's click on the title line (it opens the Bestiary). */
    private static String clickCommand(Component component) {
        String[] found = {""};
        component.visit((style, part) -> {
            if (style.getClickEvent() instanceof ClickEvent.RunCommand(String run)) found[0] = run;
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found[0];
    }

    /** The empty line and border just before the title, already in chat. */
    private static void deleteRecentPadding() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud == null) return;
        Sky2MChatComponentAccessor chat = (Sky2MChatComponentAccessor) mc.gui.hud.getChat();
        List<GuiMessage> all = chat.sky2m$allMessages();
        int now = mc.gui.hud.getGuiTicks();
        int removed = 0;
        while (removed < 2 && !all.isEmpty()) {
            GuiMessage line = all.get(0);
            String text = Sky2MLocation.strip(line.content().getString()).trim();
            if (now - line.addedTime() > RECENT_TICKS || !(text.isEmpty() || BORDER.matcher(text).matches())) break;
            all.remove(0);
            removed++;
        }
        if (removed > 0) chat.sky2m$refreshTrimmedMessages();
    }
}
