package com.epic60869.sky2m;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Sky2MNick {
    private static final Map<String, Integer> COLORS = Map.ofEntries(
        Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA), Map.entry("dark_green", 0x00AA00),
        Map.entry("dark_aqua", 0x00AAAA), Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
        Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA), Map.entry("dark_gray", 0x555555),
        Map.entry("blue", 0x5555FF), Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF),
        Map.entry("red", 0xFF5555), Map.entry("light_purple", 0xFF55FF), Map.entry("yellow", 0xFFFF55),
        Map.entry("white", 0xFFFFFF),
        // Spellings the website or older servers may send.
        Map.entry("grey", 0xAAAAAA), Map.entry("dark_grey", 0x555555), Map.entry("purple", 0xAA00AA),
        Map.entry("pink", 0xFF55FF), Map.entry("magenta", 0xFF55FF), Map.entry("cyan", 0x55FFFF)
    );

    private static final Map<UUID, RemoteNick> REMOTE_NICKS = new ConcurrentHashMap<>();

    private Sky2MNick() {}

    public static void init(Sky2MConfig loadedConfig) {}

    public static Component tabDisplayName(Component original, UUID uuid, String actualName) {
        if (original == null || uuid == null || actualName == null || actualName.isBlank()) return original;
        // Asked for every entry every frame while the tab list shows; the entry's text object only changes when
        // Hypixel updates it.
        synchronized (TAB_CACHE) {
            Component cached = cache(TAB_CACHE).get(original);
            if (cached != null) return cached;
        }
        Component result = tabDisplayNameUncached(original, uuid, actualName);
        synchronized (TAB_CACHE) {
            cache(TAB_CACHE).put(original, result);
        }
        return result;
    }

    private static Component tabDisplayNameUncached(Component original, UUID uuid, String actualName) {

        Minecraft mc = Minecraft.getInstance();
        boolean local = uuid.equals(mc.getUser().getProfileId())
            || (mc.player != null && uuid.equals(mc.player.getUUID()))
            || actualName.equals(mc.getUser().getName());
        // Your own nickname comes from the server like everyone else's.
        RemoteNick remote = local ? localNick() : shownNick(uuid);

        if (remote == null) {
            // Hypixel tab entries are fake profiles, so match usernames in the text instead.
            // Only the name is replaced; the level, rank and colours around it are kept.
            if (local) return insertSymbols(original, uuid, actualName);
            Component result = original;
            // Only rebuild the entry for names that are actually in it.
            String plain = original.getString();
            RemoteNick self = localNick();
            if (self != null && plain.contains(mc.getUser().getName())) result = replaceExactName(result, mc.getUser().getName(), styled(self));
            for (RemoteNick r : REMOTE_NICKS.values()) {
                if (!r.shown() || r.name.isBlank() || r.username.isBlank() || isLocalUuid(r.uuid) || !plain.contains(r.username)) continue;
                result = replaceExactName(result, r.username, styled(r));
            }
            result = insertSymbols(result, uuid, actualName);
            return insertSymbols(result);
        }

        // If the name is not in the text, leave the entry alone rather than replacing its formatting.
        return replaceExactName(original, actualName, styled(remote));
    }

    /**
     * Name shown above a player's head. Replaces only the username inside the display name,
     * keeping any team prefix, rank and colours.
     */
    public static Component nameTag(Component original, UUID uuid, String actualName) {
        if (original == null || uuid == null || actualName == null) return original;
        RemoteNick remote = isLocalUuid(uuid) ? localNick() : shownNick(uuid);
        if (remote == null) return insertSymbols(original, uuid, actualName);
        return replaceExactName(original, actualName, styled(remote));
    }

    /**
     * Replaces your username (when your nick is on) and other Sky2M users' usernames in text shown in the world:
     * entity nametags, Hypixel's armor-stand name lines and text displays.
     */
    public static Component worldText(Component original) {
        if (original == null) return original;
        // Called for every named entity, twice a frame; Hypixel keeps the same name component until it changes.
        synchronized (WORLD_CACHE) {
            Component cached = cache(WORLD_CACHE).get(original);
            if (cached != null) return cached;
        }
        Component result = worldTextUncached(original);
        synchronized (WORLD_CACHE) {
            cache(WORLD_CACHE).put(original, result);
            // The result goes through here again (render state after getNameTag): it's already done.
            WORLD_CACHE.put(result, result);
        }
        return result;
    }

    /**
     * Results of the name replacements, by the identity of the text that went in. Emptied when any nickname,
     * username or cosmetic changes, and every few seconds so settings changes show and old names are let go.
     */
    private static final Map<Component, Component> WORLD_CACHE = new java.util.IdentityHashMap<>();
    private static final Map<Component, Component> TAB_CACHE = new java.util.IdentityHashMap<>();
    private static volatile int version;
    private static int worldVersion = -1;
    private static int tabVersion = -1;
    private static long worldClearedAt;
    private static long tabClearedAt;

    /** Something that changes how names look changed (a nickname, a username, cosmetics). */
    public static void invalidateCache() {
        version++;
    }

    private static Map<Component, Component> cache(Map<Component, Component> map) {
        long now = System.currentTimeMillis();
        boolean world = map == WORLD_CACHE;
        int built = world ? worldVersion : tabVersion;
        long cleared = world ? worldClearedAt : tabClearedAt;
        if (built != version || now - cleared > 3_000L || map.size() > 4096) {
            map.clear();
            if (world) {
                worldVersion = version;
                worldClearedAt = now;
            } else {
                tabVersion = version;
                tabClearedAt = now;
            }
        }
        return map;
    }

    private static Component worldTextUncached(Component original) {
        String plain = original.getString();
        Component result = original;
        Minecraft mc = Minecraft.getInstance();
        RemoteNick local = localNick();
        if (local != null && mc.player != null) {
            String self = mc.player.getGameProfile().name();
            if (plain.contains(self)) result = replaceExactName(result, self, styled(local));
        }
        for (RemoteNick remote : REMOTE_NICKS.values()) {
            if (!remote.shown() || remote.name.isBlank() || remote.username.isBlank() || isLocalUuid(remote.uuid)) continue;
            if (plain.contains(remote.username)) result = replaceExactName(result, remote.username, styled(remote));
        }
        return result;
    }

    /** Your own nickname, from the server (set on shadowisabot.com), or null without one. */
    private static RemoteNick localNick() {
        return shownNick(Minecraft.getInstance().getUser().getProfileId());
    }

    private static RemoteNick shownNick(UUID uuid) {
        RemoteNick nick = uuid == null ? null : REMOTE_NICKS.get(uuid);
        return nick != null && nick.shown() && !nick.name.isBlank() ? nick : null;
    }

    /** The nickname in its style, after the player's supporter symbol and badge. */
    private static Component styled(RemoteNick nick) {
        return com.epic60869.sky2m.features.sbc.SbcCosmetics.decorate(nick.uuid,
            styled(nick.name, nick.mode, nick.customHex, nick.gradientHex, nick.font));
    }

    /**
     * Applies the local nickname directly to the client-side TAB entry.
     * Hypixel can periodically replace PlayerInfo display names, so this is
     * re-applied from the client tick instead of relying only on a render mixin.
     */

    /**
     * Replaces the local player's name in normal Minecraft chat so your nickname
     * is not limited to Sky2M's separate global-chat channel.
     */
    /** Replaces synced nicknames for other Sky2M users in normal Hypixel chat. */
    public static Component replaceOtherNamesInChat(Component message) {
        if (message == null) return message;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return message;

        Component result = message;
        // Each replacement walks the whole message, so only try names that are in it at all (there can be hundreds
        // of known nicknames, and this runs for every chat line).
        String plain = message.getString().toLowerCase(java.util.Locale.ROOT);
        // Use the relay's UUID -> username mapping first. This is important for
        // Hypixel /msg and guild messages: those are server/game messages and
        // the target player may not be present in the local tab list.
        for (RemoteNick remote : REMOTE_NICKS.values()) {
            if (!remote.shown() || remote.name.isBlank() || remote.username.isBlank()) continue;
            if (isLocalUuid(remote.uuid)) continue;
            if (!plain.contains(remote.username.toLowerCase(java.util.Locale.ROOT))) continue;
            result = replaceExactName(result, remote.username, styled(remote));
        }
        result = insertSymbols(result);

        // Fill in missing usernames from the live tab list for older persisted
        // nickname records which were created before usernames were persisted.
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            if (info == null || info.getProfile() == null) continue;
            UUID uuid = info.getProfile().id();
            String actualName = info.getProfile().name();
            RemoteNick remote = REMOTE_NICKS.get(uuid);
            if (remote == null || !remote.shown() || remote.name.isBlank()
                || actualName == null || actualName.isBlank() || isLocalUuid(uuid)) continue;
            if (!remote.username.equals(actualName)) {
                REMOTE_NICKS.put(uuid, remote.withUsername(actualName));
            }
            if (!plain.contains(actualName.toLowerCase(java.util.Locale.ROOT))) continue;
            result = replaceExactName(result, actualName, styled(remote));
        }
        return result;
    }

    private static Component replaceExactName(Component message, String actualName, Component replacement) {
        if (message == null || actualName == null || actualName.isBlank()) return message;

        // Hypixel splits chat into many styled component leaves. Usernames can
        // cross a leaf boundary, so matching each leaf separately is unreliable.
        List<StyledRun> runs = new java.util.ArrayList<>();
        StringBuilder plain = new StringBuilder();
        message.visit((style, value) -> {
            if (value != null && !value.isEmpty()) {
                runs.add(new StyledRun(value, style));
                plain.append(value);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);

        String text = plain.toString();
        MutableComponent result = Component.empty();
        int cursor = 0;
        // Where to look for the next match; separate from cursor so a skipped match doesn't drop the text before it.
        int search = 0;
        boolean changed = false;

        while (search < text.length()) {
            int at = text.indexOf(actualName, search);
            if (at < 0) break;
            int end = at + actualName.length();
            boolean leftOk = at == 0 || !isNameChar(text.charAt(at - 1));
            boolean rightOk = end >= text.length() || !isNameChar(text.charAt(end));
            if (!leftOk || !rightOk) {
                search = at + 1;
                continue;
            }
            // Text that is already a nickname (it carries the "real name" hover) isn't replaced again, so a nick that
            // contains the username as a word doesn't grow each time the name goes through here.
            Style here = styleAt(runs, at);
            if (here != null && here.getHoverEvent() instanceof HoverEvent.ShowText(Component existing)
                && existing.getString().startsWith(REAL_NAME_PREFIX)) {
                search = at + 1;
                continue;
            }

            appendStyledRange(result, runs, cursor, at);
            result.append(withRealNameHover(replacement, actualName, styleAt(runs, at)));
            changed = true;
            cursor = end;
            search = end;
        }

        if (!changed) return message;
        appendStyledRange(result, runs, cursor, text.length());
        return result;
    }

    /**
     * Wraps a nickname so hovering it shows the player's real username. Any hover text
     * Hypixel already had on the name is kept below it, and click actions are preserved.
     */
    private static Component withRealNameHover(Component replacement, String actualName, Style original) {
        HoverEvent hover = original.getHoverEvent();
        // Names are re-replaced every tick (tab list) and a colour-only nick keeps the same
        // text, so the input may already carry our hover. Never wrap it again: nesting it
        // each tick grows the component without bound and overflows the stack.
        boolean alreadyTagged = hover instanceof HoverEvent.ShowText(Component existing)
            && existing.getString().startsWith(REAL_NAME_PREFIX);
        // A nick with the same text as the username has nothing to reveal.
        if (!alreadyTagged && !replacement.getString().equals(actualName)) {
            MutableComponent hoverText = Component.literal(REAL_NAME_PREFIX).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(actualName).withStyle(ChatFormatting.WHITE));
            if (hover instanceof HoverEvent.ShowText(Component existing)) {
                hoverText.append(Component.literal("\n")).append(existing);
            }
            hover = new HoverEvent.ShowText(hoverText);
        }
        // Keep the name's own look (Hypixel's rank colour, bold...) underneath: a nick with a colour of its own
        // overrides it, and one without ("Plain" with no custom colour) shows in the colour the name had.
        Style style = original.withHoverEvent(hover).withClickEvent(original.getClickEvent()).withInsertion(null);
        return Component.empty().setStyle(style).append(replacement.copy());
    }

    private static final String REAL_NAME_PREFIX = "Real name: ";

    private static Style styleAt(List<StyledRun> runs, int index) {
        int offset = 0;
        for (StyledRun run : runs) {
            if (index < offset + run.text.length()) return run.style;
            offset += run.text.length();
        }
        return Style.EMPTY;
    }

    private static void appendStyledRange(MutableComponent out, List<StyledRun> runs,
                                          int start, int end) {
        if (start >= end) return;
        int offset = 0;
        for (StyledRun run : runs) {
            int runStart = offset;
            int runEnd = offset + run.text.length();
            int from = Math.max(start, runStart);
            int to = Math.min(end, runEnd);
            if (from < to) {
                out.append(Component.literal(run.text.substring(from - runStart, to - runStart))
                    .setStyle(run.style));
            }
            offset = runEnd;
            if (offset >= end) break;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public static Component replaceOwnNameInChat(Component message) {
        RemoteNick local = localNick();
        if (message == null || local == null) return message;

        String actualName = Minecraft.getInstance().getUser().getName();
        if (actualName == null || actualName.isBlank()) return message;

        return replaceExactName(message, actualName, styled(local));
    }

    public static Component displayName(String actualName) {
        RemoteNick local = localNick();
        if (local == null || !actualName.equals(Minecraft.getInstance().getUser().getName())) {
            return Component.literal(actualName);
        }
        return styled(local);
    }

    public static Component displayName(UUID uuid, String actualName) {
        RemoteNick remote = shownNick(uuid);
        return remote != null ? styled(remote) : com.epic60869.sky2m.features.sbc.SbcCosmetics.decorate(uuid, displayName(actualName));
    }

    /** A player's nickname if they show one, or their name, with their symbols ({@code /s2 who}, friends list). */
    public static Component displayName(UUID uuid, String actualName, String nick, boolean enabled, String mode, String hex, String hex2, String font) {
        RemoteNick remote = shownNick(uuid);
        if (remote != null) return styled(remote);
        Component name = enabled && nick != null && !nick.isBlank() && !Sky2MNickFilter.isBlocked(nick, uuid)
            ? styled(nick, mode, hex, hex2, font) : Component.literal(actualName);
        return com.epic60869.sky2m.features.sbc.SbcCosmetics.decorate(uuid, name);
    }

    /** Usernames of every player the server told us about (nickname or not), for their badges in chat and tab. */
    private static final Map<UUID, String> USERNAMES = new ConcurrentHashMap<>();

    public static void rememberUsername(UUID uuid, String username) {
        String clean = cleanUsername(username);
        if (uuid != null && !clean.isBlank() && !clean.equals(USERNAMES.put(uuid, clean))) invalidateCache();
    }

    public static String username(UUID uuid) {
        RemoteNick nick = uuid == null ? null : REMOTE_NICKS.get(uuid);
        if (nick != null && !nick.username.isBlank()) return nick.username;
        return uuid == null ? "" : USERNAMES.getOrDefault(uuid, "");
    }

    /** Puts the player's supporter symbol and badge in front of their name, where their name appears as is. */
    private static Component insertSymbols(Component message, UUID uuid, String actualName) {
        if (message == null || uuid == null || actualName == null || actualName.isBlank()) return message;
        Component symbols = com.epic60869.sky2m.features.sbc.SbcCosmetics.symbols(uuid);
        return symbols == null ? message : insertBeforeName(message, actualName, symbols);
    }

    /** Symbols in front of every Sky2M player without a nickname whose name is in the text. */
    private static Component insertSymbols(Component message) {
        if (message == null) return message;
        String plain = message.getString();
        Component result = message;
        for (UUID uuid : com.epic60869.sky2m.features.sbc.SbcCosmetics.decoratedPlayers()) {
            if (shownNick(uuid) != null) continue;
            String name = username(uuid);
            if (name.isBlank() || !plain.contains(name)) continue;
            result = insertSymbols(result, uuid, name);
        }
        return result;
    }

    private static Component insertBeforeName(Component message, String actualName, Component prefix) {
        List<StyledRun> runs = new java.util.ArrayList<>();
        StringBuilder plain = new StringBuilder();
        message.visit((style, value) -> {
            if (value != null && !value.isEmpty()) {
                runs.add(new StyledRun(value, style));
                plain.append(value);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        String text = plain.toString();
        String prefixText = prefix.getString();
        MutableComponent result = Component.empty();
        int cursor = 0;
        int search = 0;
        boolean changed = false;
        while (search < text.length()) {
            int at = text.indexOf(actualName, search);
            if (at < 0) break;
            int end = at + actualName.length();
            boolean leftOk = at == 0 || !isNameChar(text.charAt(at - 1));
            boolean rightOk = end >= text.length() || !isNameChar(text.charAt(end));
            // Already has its symbols (the text went through here before).
            boolean done = at >= prefixText.length() && text.startsWith(prefixText, at - prefixText.length());
            if (!leftOk || !rightOk || done) {
                search = at + 1;
                continue;
            }
            appendStyledRange(result, runs, cursor, at);
            result.append(prefix.copy());
            changed = true;
            cursor = at;
            search = end;
        }
        if (!changed) return message;
        appendStyledRange(result, runs, cursor, text.length());
        return result;
    }

    public static void updateRemote(UUID uuid, boolean enabled, String name, String mode, String customHex) {
        updateRemote(uuid, "", enabled, name, mode, customHex, "Default");
    }

    public static void updateRemote(UUID uuid, String username, boolean enabled, String name, String mode, String customHex) {
        updateRemote(uuid, username, enabled, name, mode, customHex, null);
    }

    /** {@code font} null keeps the font already known for this player (message packets may not carry it). */
    public static void updateRemote(UUID uuid, String username, boolean enabled, String name, String mode, String customHex, String font) {
        updateRemote(uuid, username, enabled, name, mode, customHex, null, font);
    }

    /** {@code gradientHex} (the Gradient style's second colour) null keeps the one already known. */
    public static void updateRemote(UUID uuid, String username, boolean enabled, String name, String mode, String customHex, String gradientHex, String font) {
        if (uuid == null) return;
        rememberUsername(uuid, username);
        // Nicknames with blocked words are not shown; the player's real name is used instead.
        if (!enabled || name == null || name.isBlank() || Sky2MNickFilter.isBlocked(name, uuid)) {
            if (REMOTE_NICKS.remove(uuid) != null) invalidateCache();
            return;
        }
        String safeUsername = username == null ? "" : cleanUsername(username);
        RemoteNick previous = REMOTE_NICKS.get(uuid);
        if (safeUsername.isBlank() && previous != null) safeUsername = previous.username;
        String safeFont = font != null ? Sky2MNickFonts.parse(font).label : previous != null ? previous.font : "Default";
        String safeGradient = gradientHex != null ? cleanHex(gradientHex) : previous != null ? previous.gradientHex : "";
        // A null style or colour (a chat message that doesn't carry it) keeps the one nicknameUpdate gave.
        String safeMode = mode != null ? cleanMode(mode) : previous != null ? previous.mode : "Plain";
        String safeHex = customHex != null ? cleanHex(customHex) : previous != null ? previous.customHex : "";
        RemoteNick updated = new RemoteNick(
            uuid,
            safeUsername,
            clean(name),
            safeMode,
            safeHex,
            safeGradient,
            safeFont,
            true
        );
        REMOTE_NICKS.put(uuid, updated);
        // nicknameUpdate is re-sent for everyone every 30 s; only a real change empties the name caches.
        if (!updated.equals(previous)) invalidateCache();
    }

    public static void removeRemote(UUID uuid) {
        if (uuid != null && REMOTE_NICKS.remove(uuid) != null) invalidateCache();
    }

    public static void clearRemote() {
        REMOTE_NICKS.clear();
        invalidateCache();
    }

    public static Component styled(String text, String style, String customHex) {
        return styled(text, style, customHex, "Default");
    }

    public static Component styled(String text, String style, String customHex, String fontName) {
        return styled(text, style, customHex, null, fontName);
    }

    /**
     * Chroma letters carry this colour with their place in the name (0-255) in the blue channel; the text renderer
     * turns it into the moving rainbow (Sky2MChromaTextMixin).
     */
    public static final int CHROMA_MARK = 0x0B0B00;

    /** The Chroma colour right now for a letter at {@code offset} (0-1) along the name. */
    public static int chroma(float offset) {
        float phase = (System.currentTimeMillis() % 3000) / 3000f;
        return Color.HSBtoRGB(offset + phase, 0.95f, 1.0f) & 0xFFFFFF;
    }

    /**
     * The nickname in its colour, gradient, rainbow or chroma, and font. Gradient fades each letter from
     * {@code customHex} to {@code gradientHex}; Chroma is a rainbow that moves (see {@link #CHROMA_MARK}).
     */
    public static Component styled(String text, String style, String customHex, String gradientHex, String fontName) {
        String safeStyle = style == null ? "Plain" : style;
        Sky2MNickFonts.NickFont font = Sky2MNickFonts.parse(fontName);
        String shown = Sky2MNickFonts.letters(text, font);

        if ("Gradient".equalsIgnoreCase(safeStyle)) {
            String fromHex = cleanHex(customHex), toHex = cleanHex(gradientHex);
            int from = !fromHex.isEmpty() ? Integer.parseInt(fromHex.substring(1), 16) : 0xFFFFFF;
            int to = !toHex.isEmpty() ? Integer.parseInt(toHex.substring(1), 16) : from;
            MutableComponent out = Component.empty();
            int[] codePoints = shown.codePoints().toArray();
            int n = codePoints.length;
            for (int i = 0; i < n; i++) {
                float t = n == 1 ? 0f : (float) i / (n - 1);
                out.append(Component.literal(new String(Character.toChars(codePoints[i])))
                    .setStyle(Sky2MNickFonts.style(Style.EMPTY.withColor(mix(from, to, t)), font)));
            }
            return out;
        }

        if ("Chroma".equalsIgnoreCase(safeStyle)) {
            MutableComponent out = Component.empty();
            int[] codePoints = shown.codePoints().toArray();
            int n = Math.max(1, codePoints.length);
            for (int i = 0; i < codePoints.length; i++) {
                int offset = Math.min(255, Math.round((float) i / n * 256f));
                out.append(Component.literal(new String(Character.toChars(codePoints[i])))
                    .setStyle(Sky2MNickFonts.style(Style.EMPTY.withColor(CHROMA_MARK | offset), font)));
            }
            return out;
        }

        if ("Rainbow".equalsIgnoreCase(safeStyle)) {
            MutableComponent out = Component.empty();
            int[] codePoints = shown.codePoints().toArray();
            int n = Math.max(1, codePoints.length);
            for (int i = 0; i < codePoints.length; i++) {
                float hue = (float) i / n;
                int rgb = Color.HSBtoRGB(hue, 0.95f, 1.0f) & 0xFFFFFF;
                out.append(Component.literal(new String(Character.toChars(codePoints[i])))
                    .setStyle(Sky2MNickFonts.style(Style.EMPTY.withColor(rgb), font)));
            }
            return out;
        }

        String key = colourKey(safeStyle);
        Integer rgb = COLORS.get(key);

        // A custom colour is "Plain" + customHex; any other mode the mod doesn't know ("Custom", "Hex", ...) with a
        // customHex uses it too, rather than dropping the colour.
        String hex = cleanHex(customHex);
        if (!hex.isEmpty() && (rgb == null || "plain".equals(key))) {
            rgb = Integer.parseInt(hex.substring(1), 16);
        }

        Style base = rgb == null ? Style.EMPTY : Style.EMPTY.withColor(rgb);
        return Component.literal(shown).setStyle(Sky2MNickFonts.style(base, font));
    }

    /** Each channel of {@code from} moved {@code t} of the way to {@code to}, rounded. */
    private static int mix(int from, int to, float t) {
        int r = Math.round((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * t);
        int g = Math.round((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return r << 16 | g << 8 | b;
    }

    private static String clean(String value) {
        value = value.replace("\\r", "").replace("\\n", "").trim();
        int[] codePoints = value.codePoints().limit(32).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private static String cleanMode(String value) {
        if (value == null || value.isBlank()) return "Plain";
        // "Dark Blue", "dark_blue", "DARK-BLUE" and "DarkBlue" are all the same colour.
        String cleaned = value.replaceAll("[^A-Za-z _-]", "").trim();
        return cleaned.isBlank() ? "Plain" : cleaned.substring(0, Math.min(20, cleaned.length()));
    }

    /** "Dark Blue" / "dark_blue" / "DarkBlue" -> "dark_blue", the key in {@link #COLORS}. */
    private static String colourKey(String mode) {
        return mode.trim().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "_");
    }

    /** "#RRGGBB" (upper case) from "#rrggbb", "rrggbb" or "0xRRGGBB", or "" when it isn't a colour. */
    private static String cleanHex(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("0x") || v.startsWith("0X")) v = v.substring(2);
        if (v.startsWith("#")) v = v.substring(1);
        return v.matches("[0-9a-fA-F]{6}") ? "#" + v.toUpperCase(Locale.ROOT) : "";
    }

    private static boolean isLocalUuid(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        return uuid != null && (uuid.equals(mc.getUser().getProfileId())
            || (mc.player != null && uuid.equals(mc.player.getUUID())));
    }

    private static String cleanUsername(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "").substring(
            0, Math.min(16, value.replaceAll("[^A-Za-z0-9_]", "").length()));
    }

    private record StyledRun(String text, Style style) {}

    private record RemoteNick(UUID uuid, String username, String name, String mode, String customHex, String gradientHex, String font, boolean enabled) {
        private RemoteNick withUsername(String value) {
            return new RemoteNick(uuid, value, name, mode, customHex, gradientHex, font, enabled);
        }

        private boolean shown() {
            return enabled;
        }
    }
}
