package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lobby Compromised, ported from Skysoft's DianaLobbyCompromisedWatcher (https://github.com/Akinsoft/Skysoft,
 * LGPL-3.0): while you do Diana in the Hub, an alert when the lobby has at least the Stranger Limit of players who
 * aren't you or in your party (a title, and optionally "Lobby compromised!" in party chat). The count is the tab list's
 * "Players (N)" minus you and the party members in it; it has to stay that high for two seconds, and it alerts again
 * only when more strangers join. Skysoft reads the party from the Hypixel mod API; here it's read from party chat and
 * Hypixel's party messages.
 */
public final class DianaLobbyCompromised {
    private static final String MESSAGE = "Lobby compromised!";
    private static final Pattern PLAYER_COUNT = Pattern.compile("Players \\((\\d+)\\)");
    /** Each party member's whole-word pattern, compiled once instead of on every check. */
    private static final Map<String, Pattern> MEMBER_PATTERNS = new HashMap<>();
    private static final String RANK = "(?:\\[[^]]+] )?";
    private static final Pattern PARTY_CHAT = Pattern.compile("^Party > " + RANK + "(\\w{1,16})[^:]*: ");
    private static final Pattern JOINED = Pattern.compile("^" + RANK + "(\\w{1,16}) joined the party\\.$");
    private static final Pattern LEFT = Pattern.compile("^" + RANK + "(\\w{1,16}) (?:has left|has been removed from) the party\\.$");
    private static final Pattern JOINED_OTHER = Pattern.compile("^You have joined " + RANK + "(\\w{1,16})'s? party!$");
    private static final Pattern LIST_LINE = Pattern.compile("^Party (?:Leader|Moderators|Members): (.+)$");
    private static final long STABLE_MS = 2_000L;
    private static final long FRIENDLY_GRACE_MS = 15_000L;

    /** Party members' names, lowercase. */
    private static final Set<String> party = new HashSet<>();
    /** When each party member (or you) was last seen in the tab list. */
    private static final Map<String, Long> friendlySeen = new HashMap<>();

    private static boolean hasBaseline;
    private static boolean wasCompromised;
    private static int lastThreshold = -1;
    private static int acknowledged;
    private static long candidateSince = -1;
    private static long joinedAt;
    private static int ticks;
    private static int pingsLeft;
    private static long nextPingAt;

    private DianaLobbyCompromised() {}

    private static FeatureConfigs.DianaLobbyCompromised config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mayors.diana.lobbyCompromised;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DianaLobbyCompromised::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            reset();
            joinedAt = System.currentTimeMillis();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> party.clear());
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
    }

    private static void reset() {
        hasBaseline = false;
        wasCompromised = false;
        lastThreshold = -1;
        acknowledged = 0;
        candidateSince = -1;
        friendlySeen.clear();
    }

    private static void onChat(String text) {
        Matcher m;
        if ((m = PARTY_CHAT.matcher(text)).find()) party.add(m.group(1).toLowerCase(Locale.ROOT));
        else if ((m = JOINED.matcher(text)).matches()) party.add(m.group(1).toLowerCase(Locale.ROOT));
        else if ((m = LEFT.matcher(text)).matches()) party.remove(m.group(1).toLowerCase(Locale.ROOT));
        else if ((m = JOINED_OTHER.matcher(text)).matches()) {
            party.clear();
            party.add(m.group(1).toLowerCase(Locale.ROOT));
        } else if ((m = LIST_LINE.matcher(text)).matches()) {
            // "[MVP+] Name ● [VIP] Other ●"
            for (String part : m.group(1).split("●")) {
                String name = part.trim().replaceAll("^\\[[^]]+]\\s*", "").trim();
                if (name.matches("\\w{1,16}")) party.add(name.toLowerCase(Locale.ROOT));
            }
        } else if (text.equals("You left the party.") || text.startsWith("The party was disbanded")
            || text.startsWith("You have been kicked from the party") || text.startsWith("You are not currently in a party")) {
            party.clear();
        }
    }

    /** You're doing Diana: in the Hub with a spade in your hotbar. */
    private static boolean active(Minecraft mc) {
        if (!Sky2MLocation.onSkyblock() || !Sky2MLocation.areaIs("Hub") || mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString().contains("Spade")) return true;
        }
        return false;
    }

    private static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (pingsLeft > 0 && now >= nextPingAt && mc.player != null) {
            mc.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.9f, 1.15f);
            pingsLeft--;
            nextPingAt = now + 450;
        }
        if (++ticks % 10 != 0) return;
        FeatureConfigs.DianaLobbyCompromised config = config();
        if (config == null || !config.enabled || !active(mc)) {
            reset();
            return;
        }
        // Give the tab list a moment to fill in after joining.
        if (now - joinedAt < 2_000L) return;
        Integer strangers = strangerCount(mc, now);
        if (strangers == null) {
            reset();
            return;
        }
        int threshold = Math.max(2, Math.min(12, config.strangerLimit));
        if (!hasBaseline || lastThreshold != threshold) {
            // What's already there when you start doesn't alert.
            hasBaseline = true;
            wasCompromised = strangers >= threshold;
            lastThreshold = threshold;
            acknowledged = strangers;
            candidateSince = -1;
            return;
        }
        if (strangers < threshold) {
            wasCompromised = false;
            acknowledged = strangers;
            candidateSince = -1;
            return;
        }
        if (wasCompromised && strangers <= acknowledged) {
            acknowledged = strangers;
            candidateSince = -1;
            return;
        }
        if (candidateSince < 0) candidateSince = now;
        if (now - candidateSince < STABLE_MS) return;
        wasCompromised = true;
        acknowledged = strangers;
        candidateSince = -1;
        alert(mc, config);
    }

    /** "Players (N)" in the tab list, minus you and the party members seen in it in the last 15 seconds. */
    private static Integer strangerCount(Minecraft mc, long now) {
        Integer reported = null;
        StringBuilder names = new StringBuilder();
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component raw = Compat.rawTabName(info);
            if (raw == null) continue;
            String text = Sky2MLocation.strip(raw.getString());
            Matcher m = PLAYER_COUNT.matcher(text);
            if (reported == null && m.find()) reported = Integer.parseInt(m.group(1));
            names.append(' ').append(text.toLowerCase(Locale.ROOT)).append(' ');
        }
        if (reported == null) return null;
        String tab = names.toString();
        String self = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        friendlySeen.keySet().removeIf(name -> !name.equals(self) && !party.contains(name));
        if (reported > 0) friendlySeen.put(self, now);
        for (String member : party) {
            if (MEMBER_PATTERNS.computeIfAbsent(member, n -> Pattern.compile("\\b" + Pattern.quote(n) + "\\b")).matcher(tab).find()) friendlySeen.put(member, now);
        }
        long friendly = friendlySeen.values().stream().filter(at -> now - at <= FRIENDLY_GRACE_MS).count();
        return (int) Math.max(0, reported - Math.min(friendly, reported));
    }

    private static void alert(Minecraft mc, FeatureConfigs.DianaLobbyCompromised config) {
        if (config.titleAlert) {
            mc.gui.hud.setTimes(0, 100, 10);
            mc.gui.hud.setTitle(Component.literal(MESSAGE).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
            mc.gui.hud.setSubtitle(Component.empty());
            pingsLeft = 3;
            nextPingAt = 0;
        }
        if (config.chatAlert && !party.isEmpty() && mc.getConnection() != null) mc.getConnection().sendCommand("pc " + MESSAGE);
    }
}
