package com.epic60869.sky2m;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Discord leaderboards: the SBC chat server says which leaderboards are running and what they count; the mod watches
 * your chat for those things and reports them, and the server keeps the standings (shown in Discord and with
 * /s2 leaderboard).
 *
 * <p>/s2 leaderboard lists the running event boards and the Slayer PBs board; /s2 leaderboard &lt;name&gt; shows an
 * event board, and when no event board has that name, "slayer" and "slayer &lt;boss&gt;" show the slayer kill time
 * board ({@link com.epic60869.sky2m.features.slayer.SlayerLeaderboard}, which documents its slayerPb* packets).
 *
 * <pre>
 * server -> mod: leaderboardsActive {boards: [{id, name, endsAt, trackers: [
 *                    {kind: "drop", items: ["Summoning Eye", ...]},            RARE DROP! / PET DROP! ... messages
 *                    {kind: "chat", pattern: "^...$", amountGroup: "amount"}   any chat message (regex)
 *                ]}]}
 *                leaderboardStandings {boardId, name, endsAt, entries: [{username, amount}], you: {rank, amount}}
 *                leaderboardProgressResult {boardId, ok, total, rank, message}
 * mod -> server: leaderboardProgress {boardId, amount, detail, message}  (needs the casinoAuth login)
 *                leaderboardGet {boardId}
 * </pre>
 */
public final class Sky2MLeaderboards {
    private static final Pattern TRAILING_LEVEL = Pattern.compile("^(.+?)\\s+(\\d+)$");
    /** Hypixel's drop messages: "RARE DROP! Summoning Eye (+312% ✯ Magic Find)", "PET DROP! Ender Dragon". */
    private static final Pattern DROP = Pattern.compile(
        "^(?:(?:VERY |CRAZY |INSANE |PRAY TO RNGESUS )?RARE DROP!|PET DROP!|INSANE DROP!)\\s+\\(?(?<item>.+?)\\)?(?:\\s+x(?<amount>[\\d,]+))?(?:\\s+\\(\\+[\\d,.]+%? ?.*Magic Find\\))?\\s*$");
    private static final int MAX_PATTERN_LENGTH = 300;

    private record Tracker(String kind, List<String> items, Pattern pattern, String amountGroup) {}

    private record Board(String id, String name, long endsAt, List<Tracker> trackers) {}

    private static volatile List<Board> boards = List.of();
    /** Test boards made with /s2 lbdebug addlocal: counted in the mod only, never reported. */
    private static final List<Board> LOCAL_BOARDS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final String LOCAL_PREFIX = "local:";

    private Sky2MLeaderboards() {}

    public static void init() {
        com.epic60869.sky2m.features.core.Sky2MChat.onChat(message -> onChat(message.text(), message.component()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : com.epic60869.sky2m.custom.util.Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("leaderboard")
                    // Bare /s2 leaderboard opens the GUI (queued one tick so the command handoff doesn't clash
                    // with the closing chat screen, like /s2 who). /s2 leaderboard <name> still answers in chat.
                    .executes(c -> com.epic60869.sky2m.custom.util.Compat.queueOpenScreen(new Sky2MLeaderboardScreen()))
                    .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(nameSuggestions(), b))
                        .executes(c -> standings(StringArgumentType.getString(c, "name"))))));
                // Testing tools, only for the owner account (like /s2 viewboth).
                if (com.epic60869.sky2m.reports.Reports.isOwner()) dispatcher.register(ClientCommands.literal(root).then(debugCommand()));
            }
        });
    }

    // ---------------------------------------------------------------- counting

    /** A message counted less than this long ago is Hypixel (or a relay) sending it again, not a second drop. */
    private static final long DUPLICATE_MS = 250L;
    private static final java.util.Map<String, Long> RECENT = new java.util.concurrent.ConcurrentHashMap<>();
    /** A level at the end of a name: "Chimera I", or "Chimera 1" as the drop line sometimes writes it. */
    private static final Pattern ROMAN_LEVEL = Pattern.compile("\\s+(?:[IVXLCDM]+|\\d+)$");
    /** "Enchanted Book (Chimera 1": the drop pattern leaves the closing bracket out of the item. */
    private static final Pattern NAMED_IN_BRACKETS = Pattern.compile("^(?<base>.+?)\\s+\\((?<inner>[^()]+)\\)?$");
    private static final Pattern ARABIC_LEVEL = Pattern.compile("^(?<name>.+?)\\s+(?<level>\\d{1,2})$");

    private static void onChat(String text, Component component) {
        process(text, component, true, true);
    }

    /**
     * Counts a chat line for every running board (and local test board). Returns what it matched, as
     * "board: +amount detail"; reports them to the server when {@code send} is true (local boards never are).
     * A line counts at most once per board, even when several of the board's trackers match it, and the same message
     * arriving twice within {@link #DUPLICATE_MS} counts once ({@code dedupe}; debug runs skip it).
     */
    private static List<String> process(String text, Component component, boolean send, boolean dedupe) {
        List<String> matched = new ArrayList<>();
        List<Board> running = new ArrayList<>(boards);
        running.addAll(LOCAL_BOARDS);
        if (running.isEmpty() || text == null || text.isBlank()) return matched;
        long now = System.currentTimeMillis();
        String line = text.trim();
        Matcher drop = DROP.matcher(line);
        String dropItem = drop.matches() ? drop.group("item").trim() : null;
        long dropAmount = dropItem != null && drop.group("amount") != null ? parse(drop.group("amount")) : 1;
        // Enchanted books say only "(Enchanted Book)": the enchantment ("Chimera I") is in the hover.
        List<String> hoverNames = dropItem != null && component != null ? hoverNames(component) : new ArrayList<>();
        // Newer lines name it in the text too: "RARE DROP! Enchanted Book (Chimera 1) (+304 ✯ Magic Find)".
        Matcher named = dropItem != null ? NAMED_IN_BRACKETS.matcher(dropItem) : null;
        if (named != null && named.matches()) {
            dropItem = named.group("base").trim();
            String inner = named.group("inner").trim();
            Matcher level = ARABIC_LEVEL.matcher(inner);
            // "Chimera I" first, as the hover says it, so the reported detail is the same however the line writes it.
            if (level.matches()) hoverNames.add(0, level.group("name") + " " + roman(Integer.parseInt(level.group("level"))));
            hoverNames.add(inner);
        }

        if (dedupe && (dropItem != null || hasChatTracker(running))) {
            String key = line + "|" + String.join("|", hoverNames);
            Long seen = RECENT.put(key, now);
            RECENT.values().removeIf(at -> now - at > 5_000L);
            if (seen != null && now - seen < DUPLICATE_MS) return matched;
        }

        for (Board board : running) {
            if (board.endsAt() > 0 && now > board.endsAt()) continue;
            long amount = 0;
            String detail = null;
            for (Tracker tracker : board.trackers()) {
                if ("drop".equals(tracker.kind()) && dropItem != null) {
                    String hit = matchDrop(tracker.items(), dropItem, hoverNames);
                    if (hit != null && (detail == null || !hit.equalsIgnoreCase(dropItem))) {
                        amount = dropAmount;
                        detail = hit; // a hover name ("Chimera I") beats the plain "Enchanted Book"
                    }
                } else if ("chat".equals(tracker.kind()) && tracker.pattern() != null && detail == null) {
                    Matcher m = tracker.pattern().matcher(line);
                    if (m.find()) {
                        amount = 1;
                        if (tracker.amountGroup() != null) {
                            try {
                                amount = parse(m.group(tracker.amountGroup()));
                            } catch (IllegalArgumentException ignored) {}
                        }
                        detail = m.group();
                    }
                }
            }
            if (detail == null) continue;
            matched.add(board.name() + ": +" + amount + " " + detail);
            if (send && !board.id().startsWith(LOCAL_PREFIX)) report(board, amount, detail, line);
        }
        return matched;
    }

    private static boolean hasChatTracker(List<Board> running) {
        for (Board board : running) {
            for (Tracker tracker : board.trackers()) if ("chat".equals(tracker.kind())) return true;
        }
        return false;
    }

    /**
     * What a drop tracker counts this drop as, or null: the drop item itself, a hover name ("Chimera I"), or a hover
     * name without its level (tracker "Chimera" matches "Chimera I" and "Chimera V"; "Chimera I" only level I).
     */
    private static String matchDrop(List<String> items, String dropItem, List<String> hoverNames) {
        // Hover names other than the drop's own name first, so a board counting "Enchanted Book" and "Chimera"
        // reports "Chimera I" rather than the book.
        for (String item : items) {
            for (String hover : hoverNames) {
                if (hover.equalsIgnoreCase(dropItem)) continue;
                if (item.equalsIgnoreCase(hover) || item.equalsIgnoreCase(ROMAN_LEVEL.matcher(hover).replaceFirst(""))) return hover;
            }
        }
        for (String item : items) {
            if (item.equalsIgnoreCase(dropItem)) return dropItem;
        }
        return null;
    }

    /**
     * The names in a message's hovers: for an item, its name, lore lines, stored enchantments and SkyBlock
     * enchantments (custom data "enchantments": {"ultimate_chimera": 1} becomes "Chimera I" and "Ultimate Chimera I");
     * for hover text, each line. Colour codes removed, blank lines skipped.
     */
    static List<String> hoverNames(Component component) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        collectHovers(component, names, 0);
        return new ArrayList<>(names);
    }

    private static void collectHovers(Component component, java.util.Set<String> names, int depth) {
        if (depth > 16) return;
        net.minecraft.network.chat.HoverEvent hover = component.getStyle().getHoverEvent();
        if (hover instanceof net.minecraft.network.chat.HoverEvent.ShowItem(net.minecraft.world.item.ItemStackTemplate template)) {
            try {
                itemNames(template.create(), names);
            } catch (Exception ignored) {}
        } else if (hover instanceof net.minecraft.network.chat.HoverEvent.ShowText(Component text)) {
            for (String line : text.getString().split("\n")) add(names, line);
        }
        for (Component sibling : component.getSiblings()) collectHovers(sibling, names, depth + 1);
    }

    private static void itemNames(net.minecraft.world.item.ItemStack stack, java.util.Set<String> names) {
        add(names, stack.getHoverName().getString());
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) for (Component line : lore.lines()) add(names, line.getString());
        var stored = stack.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
        if (stored != null) {
            for (var entry : stored.entrySet()) {
                add(names, net.minecraft.world.item.enchantment.Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
            }
        }
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().get("enchantments") instanceof net.minecraft.nbt.CompoundTag enchantments) {
            for (String key : enchantments.keySet()) {
                int level = enchantments.getIntOr(key, 0);
                String name = titleCase(key.replaceFirst("^ultimate_", ""));
                String level_ = level > 0 ? " " + roman(level) : "";
                add(names, name + level_);
                if (key.startsWith("ultimate_")) add(names, "Ultimate " + name + level_);
            }
        }
    }

    private static void add(java.util.Set<String> names, String text) {
        String clean = ChatFormatting.stripFormatting(text);
        if (clean == null) return;
        clean = clean.trim();
        if (!clean.isEmpty()) names.add(clean);
    }

    private static String titleCase(String key) {
        StringBuilder out = new StringBuilder();
        for (String word : key.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String roman(int number) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length && number > 0; i++) {
            while (number >= values[i]) {
                out.append(numerals[i]);
                number -= values[i];
            }
        }
        return out.toString();
    }

    /** One thing counted: sent once logged in (the server only trusts a checked Minecraft login). */
    /** Reports sent and not answered yet, oldest first: a refused one is resent once after logging in again. */
    private static final java.util.ArrayDeque<JsonObject> UNANSWERED = new java.util.ArrayDeque<>();

    private static void report(Board board, long amount, String detail, String message) {
        if (amount <= 0) return;
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "leaderboardProgress");
        packet.addProperty("boardId", board.id());
        packet.addProperty("amount", amount);
        packet.addProperty("detail", detail);
        packet.addProperty("message", message.length() > 256 ? message.substring(0, 256) : message);
        Minecraft.getInstance().execute(() -> Sky2MLogin.whenLoggedIn(() -> sendReport(packet)));
    }

    private static void sendReport(JsonObject packet) {
        if (!Sky2MGlobalChat.send(packet)) return;
        UNANSWERED.addLast(packet);
        while (UNANSWERED.size() > 50) UNANSWERED.removeFirst();
    }

    private static long parse(String number) {
        if (number == null) return 1;
        try {
            return Long.parseLong(number.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ---------------------------------------------------------------- screen support (from /s2 leaderboard)

    /** One running event board, as the {@link Sky2MLeaderboardScreen} shows it. */
    public record EventBoard(String id, String name, long endsAt) {}

    /** One permanent stat board: a collection ("collection") or a slayer XP board ("slayer").
     *  {@code category} is the server's grouping ("Farming".."Other", empty when the server didn't send one). */
    public record StatBoard(String type, String id, String name, String category) {}

    /** The last statLeaderboardList: every collection and slayer XP board, plus whether stats are refreshing. */
    public record StatListData(List<StatBoard> collections, List<StatBoard> slayers, boolean refreshing, long at) {}

    /** One row of a stat leaderboard. */
    public record StatEntry(int rank, String username, long value) {}

    /**
     * The last statLeaderboardResult. {@code key} is "boardType|id" of the request it answered, so a late answer for
     * a different request is not shown until the screen asks for that one again ("|" when it can't be matched).
     */
    public record StatResult(String type, String key, String name, List<StatEntry> entries, String error, long at) {}

    /** One row of an event standing. */
    public record EventEntry(String username, long amount) {}

    /** The last leaderboardStandings. */
    public record EventStandings(String boardName, List<EventEntry> entries, long youRank, long youAmount, long at) {}

    /** The GUI's callback, if one is open; every cached packet update calls it (already on the game thread). */
    private static volatile Runnable screenListener;
    private static volatile StatListData lastStatList;
    private static volatile StatResult lastStatResult;
    private static volatile EventStandings lastEventStandings;
    /** "boardType|id" of the standing the screen last asked for; the result keeps it, late answers for others don't. */
    private static volatile String pendingStatKey = "";
    /** When {@link #pendingStatKey} was asked for: the server's answer doesn't say which request it answers. */
    private static volatile long pendingStatAt;
    /** Every stat board answered this game, by "boardType|id", for the Rival HUD (the screen keeps its own). */
    private static final java.util.Map<String, StatResult> STAT_RESULTS = new java.util.concurrent.ConcurrentHashMap<>();
    /** One-shot quiet flags: while the screen is up, the one answer it asked for is not printed to chat as well. */
    private static volatile boolean quietList, quietStat, quietEvent;
    private static volatile String quietEventName = "";

    /** Registers (or, with null, unregisters) the open screen's refresh callback. */
    public static void setScreenListener(Runnable listener) {
        screenListener = listener;
        if (listener == null) clearQuiet();
    }

    private static void clearQuiet() {
        quietList = quietStat = quietEvent = false;
        quietEventName = "";
    }

    private static void refreshed() {
        Runnable listener = screenListener;
        if (listener != null) listener.run();
    }

    public static List<EventBoard> eventBoards() {
        return boards.stream().map(b -> new EventBoard(b.id(), b.name(), b.endsAt())).toList();
    }

    public static StatListData statList() { return lastStatList; }

    public static StatResult statResult() { return lastStatResult; }

    /** The last standings of one stat board ("collection|WHEAT") this game, or null. */
    public static StatResult cachedStat(String key) { return key == null ? null : STAT_RESULTS.get(key); }

    /**
     * Whether a stat board request is still waiting for its answer (up to 8 seconds). The answer only matches its
     * request by order, so the Rival HUD doesn't ask while another one is out.
     */
    public static boolean statBusy() {
        return !pendingStatKey.isEmpty() && System.currentTimeMillis() - pendingStatAt < 8_000L;
    }

    public static EventStandings eventStandings() { return lastEventStandings; }

    /** Asks for the whole stat list (every collection and slayer XP board name); false when the socket is down. */
    public static boolean requestStatList() {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "statLeaderboardGet");
        packet.addProperty("boardType", "list");
        if (!Sky2MGlobalChat.send(packet)) return false;
        quietList = true;
        return true;
    }

    /** Asks for one collection's or slayer XP board's standings; false when the socket is down. */
    public static boolean requestStat(String boardType, String key) {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "statLeaderboardGet");
        packet.addProperty("boardType", boardType);
        packet.addProperty("key", key);
        if (!Sky2MGlobalChat.send(packet)) return false;
        pendingStatKey = boardType + "|" + key;
        pendingStatAt = System.currentTimeMillis();
        quietStat = true;
        return true;
    }

    /** Asks for one event board's standings; false when the socket is down. */
    public static boolean requestEvent(String boardId, String boardName) {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "leaderboardGet");
        packet.addProperty("boardId", boardId);
        if (!Sky2MGlobalChat.send(packet)) return false;
        quietEventName = boardName == null ? "" : boardName.trim().toLowerCase(Locale.ROOT);
        quietEvent = true;
        return true;
    }

    /** Caches statLeaderboardList for the screen (always done, whatever chat does with it). */
    private static void cacheStatList(JsonObject packet) {
        List<StatBoard> collections = new ArrayList<>();
        if (packet.has("collections") && packet.get("collections").isJsonArray()) {
            for (JsonElement element : packet.getAsJsonArray("collections")) {
                if (!element.isJsonObject()) continue;
                JsonObject board = element.getAsJsonObject();
                collections.add(new StatBoard("collection", string(board, "id"), string(board, "name"), string(board, "category")));
            }
        }
        List<StatBoard> slayers = new ArrayList<>();
        if (packet.has("slayers") && packet.get("slayers").isJsonArray()) {
            for (JsonElement element : packet.getAsJsonArray("slayers")) {
                if (!element.isJsonObject()) continue;
                JsonObject board = element.getAsJsonObject();
                slayers.add(new StatBoard("slayer", string(board, "id"), string(board, "name"), string(board, "category")));
            }
        }
        boolean refreshing = packet.has("refreshing") && packet.get("refreshing").getAsBoolean();
        lastStatList = new StatListData(List.copyOf(collections), List.copyOf(slayers), refreshing, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- commands

    /** Tab completion: running event boards, then "slayer" and "slayer &lt;boss&gt;" for your and the known boss keys. */
    private static List<String> nameSuggestions() {
        List<String> out = new ArrayList<>(boards.stream().map(Board::name).toList());
        out.add("slayer");
        out.add("collection");
        out.add("collections");
        out.add("slayer xp");
        out.add("slayer xp zombie");
        out.add("slayer xp spider");
        out.add("slayer xp wolf");
        out.add("slayer xp enderman");
        out.add("slayer xp blaze");
        out.add("slayer xp vampire");
        for (String boss : com.epic60869.sky2m.features.slayer.SlayerLeaderboard.suggestions()) out.add("slayer " + boss);
        return out;
    }

    /**
     * /s2 leaderboard &lt;name&gt;: an event board with that name first (so a board called "slayer" or "Slayer Race"
     * still works), otherwise "slayer" / "slayer &lt;boss&gt;" for the slayer kill time board.
     */
    private static int standings(String name) {
        String input = name.trim();
        Board board = boards.stream().filter(b -> b.name().equalsIgnoreCase(input)).findFirst().orElse(null);
        if (board == null) {
            String lower = input.toLowerCase(Locale.ROOT);
            if (lower.equals("collection") || lower.equals("collections") || lower.equals("slayer xp")) {
                JsonObject packet = new JsonObject();
                packet.addProperty("type", "statLeaderboardGet");
                packet.addProperty("boardType", "list");
                Sky2MGlobalChat.send(packet);
                say(Component.literal("Loading collection and slayer stat leaderboards…").withStyle(ChatFormatting.GRAY));
                return 1;
            }
            if (lower.startsWith("collection ") || lower.startsWith("collections ")) {
                String key = input.substring(input.indexOf(" ") + 1).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_ ]", "").replaceAll("\\s+", "_");
                return requestStatBoard("collection", key);
            }
            if (lower.startsWith("slayer xp ")) {
                String key = input.substring("slayer xp ".length()).trim();
                return requestStatBoard("slayer", key);
            }
            if (lower.equals("slayer")) {
                com.epic60869.sky2m.features.slayer.SlayerLeaderboard.showSummary();
                return 1;
            }
            if (lower.startsWith("slayer ")) {
                com.epic60869.sky2m.features.slayer.SlayerLeaderboard.showBoss(input.substring("slayer ".length()));
                return 1;
            }
            say(Component.literal("No running leaderboard called \"" + name + "\". Try /s2 leaderboard slayer for slayer kill times.")
                .withStyle(ChatFormatting.RED));
            return 1;
        }
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "leaderboardGet");
        packet.addProperty("boardId", board.id());
        if (!Sky2MGlobalChat.send(packet)) {
            say(Component.literal("Connecting to S2C, try again in a moment.").withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    // ---------------------------------------------------------------- packets from the server

    /** leaderboardsActive, leaderboardStandings and leaderboardProgressResult (on the game thread). */
    public static void handle(String type, JsonObject packet) {
        switch (type) {
            case "leaderboardsActive" -> boards = parseBoards(packet);
            case "leaderboardStandings" -> showStandings(packet);
            case "statLeaderboardList" -> showStatList(packet);
            case "statLeaderboardResult" -> showStatStandings(packet);
            case "leaderboardProgressResult" -> {
                boolean ok = packet.has("ok") && packet.get("ok").getAsBoolean();
                String message = string(packet, "message");
                JsonObject answered = UNANSWERED.pollFirst();
                if (!ok && "notLoggedIn".equals(string(packet, "code"))) {
                    Sky2MLogin.forget();
                    // Log in again and resend it once, so the drop isn't lost.
                    if (answered != null && !answered.has("retried")) {
                        answered.addProperty("retried", true);
                        Sky2MLogin.whenLoggedIn(() -> sendReport(answered));
                        return;
                    }
                }
                if (!message.isEmpty()) say(Component.literal(message).withStyle(ok ? ChatFormatting.GRAY : ChatFormatting.RED));
            }
            default -> {}
        }
    }

    private static int requestStatBoard(String type, String key) {
        if (key.isBlank()) {
            say(Component.literal("Enter a collection ID or slayer name. Use /s2 leaderboard collection or /s2 leaderboard slayer xp to browse.")
                .withStyle(ChatFormatting.YELLOW));
            return 1;
        }
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "statLeaderboardGet");
        packet.addProperty("boardType", type);
        packet.addProperty("key", key);
        if (!Sky2MGlobalChat.send(packet)) {
            say(Component.literal("Connecting to the Sky2M server; try again in a moment.").withStyle(ChatFormatting.YELLOW));
        } else {
            // Keyed like the screen's requests, so rivals on this board hear when they pass you from a chat lookup too.
            pendingStatKey = type + "|" + key;
            pendingStatAt = System.currentTimeMillis();
        }
        return 1;
    }

    private static void showStatList(JsonObject packet) {
        cacheStatList(packet);
        refreshed();
        if (quietList) {
            // The /s2 leaderboard screen asked for this; it shows the lists instead of the chat summary.
            quietList = false;
            return;
        }
        say(Component.literal("Permanent Stat Leaderboards").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (packet.has("refreshing") && packet.get("refreshing").getAsBoolean()) {
            say(Component.literal("Hypixel profile stats are refreshing in the background; standings may take a moment to appear.")
                .withStyle(ChatFormatting.GRAY));
        }
        if (packet.has("collections") && packet.get("collections").isJsonArray()) {
            MutableComponent line = Component.literal("Collections: ").withStyle(ChatFormatting.GRAY);
            JsonArray entries = packet.getAsJsonArray("collections");
            int shown = 0;
            for (JsonElement element : entries) {
                if (shown >= 20) break;
                JsonObject board = element.getAsJsonObject();
                if (shown++ > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                String id = string(board, "id");
                String name = string(board, "name");
                line.append(Component.literal(name).withStyle(style -> style.withColor(ChatFormatting.GREEN)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/s2 leaderboard collection " + id))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Show " + name + " collection standings")))));
            }
            say(line);
            if (entries.size() > 20) say(Component.literal("Showing the first 20 collections; use the website for the full list.")
                .withStyle(ChatFormatting.GRAY));
        }
        if (packet.has("slayers") && packet.get("slayers").isJsonArray()) {
            MutableComponent line = Component.literal("Slayer XP: ").withStyle(ChatFormatting.GRAY);
            JsonArray entries = packet.getAsJsonArray("slayers");
            for (int i = 0; i < entries.size(); i++) {
                JsonObject board = entries.get(i).getAsJsonObject();
                if (i > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                String id = string(board, "id");
                String name = string(board, "name");
                line.append(Component.literal(name).withStyle(style -> style.withColor(ChatFormatting.RED)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/s2 leaderboard slayer xp " + id))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Show " + name + " XP standings")))));
            }
            say(line);
        }
        say(Component.literal("Website: shadowisabot.com/leaderboards").withStyle(style -> style.withColor(ChatFormatting.AQUA)
            .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create("https://shadowisabot.com/leaderboards")))
            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Open the full leaderboard browser")))));
    }

    private static void showStatStandings(JsonObject packet) {
        boolean ok = packet.has("ok") && packet.get("ok").getAsBoolean();
        List<StatEntry> entries = new ArrayList<>();
        if (ok && packet.has("entries") && packet.get("entries").isJsonArray()) {
            int index = 0;
            for (JsonElement element : packet.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                index++;
                int rank = entry.has("rank") ? entry.get("rank").getAsInt() : index;
                long value = entry.has("value") ? entry.get("value").getAsLong() : 0L;
                entries.add(new StatEntry(rank, string(entry, "username"), value));
            }
        }
        // key stays "|" when it can't be matched (the screen's requests and chat board lookups set it before sending).
        lastStatResult = new StatResult(string(packet, "boardType"), pendingStatKey, string(packet, "name"),
            List.copyOf(entries), ok ? "" : string(packet, "message"), System.currentTimeMillis());
        // The board this answers, from the answer itself when the server says (a second request can be out by then).
        String answered = !string(packet, "boardType").isEmpty() && !string(packet, "key").isEmpty()
            ? string(packet, "boardType") + "|" + string(packet, "key") : pendingStatKey;
        if (ok && !answered.isEmpty() && !answered.equals("|")) STAT_RESULTS.put(answered, lastStatResult);
        // Rivals: a board's key is "type|id", or the board's name when it can't be told.
        if (ok) Sky2MRivals.onStandings(answered.isEmpty() || answered.equals("|") ? string(packet, "name") : answered,
            string(packet, "name"), lastStatResult.entries());
        pendingStatKey = "";
        refreshed();
        if (quietStat) {
            // The /s2 leaderboard screen asked for this; it shows the standings instead of the chat list.
            quietStat = false;
            return;
        }
        if (!ok) {
            say(Component.literal(string(packet, "message").isBlank()
                ? "That leaderboard isn't ready yet. Try again in a moment." : string(packet, "message")).withStyle(ChatFormatting.YELLOW));
            return;
        }
        say(Component.literal(string(packet, "name")).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (!packet.has("entries") || !packet.get("entries").isJsonArray() || packet.getAsJsonArray("entries").isEmpty()) {
            say(Component.literal("No scores are available yet.").withStyle(ChatFormatting.GRAY));
            return;
        }
        JsonArray rows = packet.getAsJsonArray("entries");
        int limit = Math.min(10, rows.size());
        for (int i = 0; i < limit; i++) {
            JsonObject entry = rows.get(i).getAsJsonObject();
            int rank = entry.has("rank") ? entry.get("rank").getAsInt() : i + 1;
            String username = string(entry, "username");
            long value = entry.has("value") ? entry.get("value").getAsLong() : 0L;
            ChatFormatting colour = rank == 1 ? ChatFormatting.GOLD : rank == 2 ? ChatFormatting.GRAY : rank == 3 ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
            MutableComponent row = Component.literal("#" + rank + " ").withStyle(colour)
                .append(Component.literal(username).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" — " + String.format(Locale.ROOT, "%,d", value)
                    + ("slayer".equals(string(packet, "boardType")) ? " XP" : "")).withStyle(ChatFormatting.GREEN));
            say(row);
        }
        say(Component.literal("Full rankings: shadowisabot.com/leaderboards").withStyle(style -> style.withColor(ChatFormatting.AQUA)
            .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create("https://shadowisabot.com/leaderboards")))
            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Open the full leaderboard")))));
    }

    private static List<Board> parseBoards(JsonObject packet) {
        List<Board> out = new ArrayList<>();
        if (!packet.has("boards") || !packet.get("boards").isJsonArray()) return out;
        for (JsonElement e : packet.getAsJsonArray("boards")) {
            try {
                JsonObject o = e.getAsJsonObject();
                List<Tracker> trackers = new ArrayList<>();
                if (o.has("trackers")) {
                    for (JsonElement t : o.getAsJsonArray("trackers")) {
                        JsonObject tracker = t.getAsJsonObject();
                        String kind = string(tracker, "kind").toLowerCase(Locale.ROOT);
                        List<String> items = new ArrayList<>();
                        if (tracker.has("items") && tracker.get("items").isJsonArray()) {
                            for (JsonElement item : tracker.getAsJsonArray("items")) items.add(item.getAsString().trim());
                        }
                        Pattern pattern = null;
                        String source = string(tracker, "pattern");
                        if (!source.isEmpty() && source.length() <= MAX_PATTERN_LENGTH) {
                            try {
                                pattern = Pattern.compile(source);
                            } catch (PatternSyntaxException ignored) {}
                        }
                        String group = string(tracker, "amountGroup");
                        trackers.add(new Tracker(kind, items, pattern, group.isEmpty() ? null : group));
                    }
                }
                out.add(new Board(string(o, "id"), string(o, "name"), o.has("endsAt") ? o.get("endsAt").getAsLong() : 0, trackers));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void showStandings(JsonObject packet) {
        String name = string(packet, "name");
        List<EventEntry> list = new ArrayList<>();
        JsonArray entries = packet.has("entries") && packet.get("entries").isJsonArray() ? packet.getAsJsonArray("entries") : new JsonArray();
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).isJsonObject()) continue;
            JsonObject entry = entries.get(i).getAsJsonObject();
            list.add(new EventEntry(string(entry, "username"), entry.has("amount") ? entry.get("amount").getAsLong() : 0L));
        }
        long youRank = 0, youAmount = 0;
        if (packet.has("you") && packet.get("you").isJsonObject()) {
            JsonObject you = packet.getAsJsonObject("you");
            youRank = you.has("rank") ? you.get("rank").getAsLong() : 0;
            youAmount = you.has("amount") ? you.get("amount").getAsLong() : 0;
        }
        lastEventStandings = new EventStandings(name, List.copyOf(list), youRank, youAmount, System.currentTimeMillis());
        boolean quiet = quietEvent && quietEventName.equalsIgnoreCase(name.trim());
        if (quietEvent) {
            // Consumed once regardless of the name, so a different board's answer can't suppress a later chat view.
            quietEvent = false;
            quietEventName = "";
        }
        refreshed();
        if (quiet) {
            // The /s2 leaderboard screen asked for this board; it shows the standings instead of chat.
            return;
        }
        say(Component.literal(name + " leaderboard").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (entries.isEmpty()) {
            say(Component.literal("Nobody has anything counted yet.").withStyle(ChatFormatting.GRAY));
        }
        String self = Minecraft.getInstance().getUser().getName();
        for (int i = 0; i < Math.min(10, entries.size()); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            String user = string(entry, "username");
            ChatFormatting colour = i == 0 ? ChatFormatting.GOLD : i == 1 ? ChatFormatting.WHITE : i == 2 ? ChatFormatting.RED
                : user.equalsIgnoreCase(self) ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            say(Component.literal((i + 1) + ". " + user + " - " + String.format(Locale.ENGLISH, "%,d", entry.get("amount").getAsLong()))
                .withStyle(colour));
        }
        if (packet.has("you") && packet.get("you").isJsonObject()) {
            JsonObject you = packet.getAsJsonObject("you");
            long rank = you.has("rank") ? you.get("rank").getAsLong() : 0;
            if (rank > 10) {
                say(Component.literal("You: #" + rank + " - " + String.format(Locale.ENGLISH, "%,d", you.get("amount").getAsLong()))
                    .withStyle(ChatFormatting.GREEN));
            }
        }
    }

    // ---------------------------------------------------------------- /s2 lbdebug (owner only)

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> debugCommand() {
        return ClientCommands.literal("lbdebug")
            .executes(c -> debugHelp())
            .then(ClientCommands.literal("status").executes(c -> debugStatus()))
            .then(ClientCommands.literal("dry").then(ClientCommands.argument("line", StringArgumentType.greedyString())
                .executes(c -> debugLine(StringArgumentType.getString(c, "line"), false))))
            .then(ClientCommands.literal("send").then(ClientCommands.argument("line", StringArgumentType.greedyString())
                .executes(c -> debugLine(StringArgumentType.getString(c, "line"), true))))
            .then(ClientCommands.literal("book").then(ClientCommands.literal("dry")
                    .then(ClientCommands.argument("enchant", StringArgumentType.greedyString())
                        .executes(c -> debugBook(StringArgumentType.getString(c, "enchant"), false))))
                .then(ClientCommands.literal("send")
                    .then(ClientCommands.argument("enchant", StringArgumentType.greedyString())
                        .executes(c -> debugBook(StringArgumentType.getString(c, "enchant"), true)))))
            .then(ClientCommands.literal("addlocal").then(ClientCommands.argument("item", StringArgumentType.greedyString())
                .executes(c -> debugAddLocal(StringArgumentType.getString(c, "item")))))
            .then(ClientCommands.literal("clearlocal").executes(c -> {
                LOCAL_BOARDS.clear();
                say(Component.literal("Local test boards removed.").withStyle(ChatFormatting.GRAY));
                return 1;
            }));
    }

    private static int debugHelp() {
        for (String line : List.of(
            "/s2 lbdebug status - connection, login and the boards the server sent",
            "/s2 lbdebug dry <chat line> - what that line would count (nothing is sent)",
            "/s2 lbdebug send <chat line> - count it and report it to the server for real",
            "/s2 lbdebug book dry|send <enchant> [level] - a book drop like Hypixel's, the enchantment only in the hover (e.g. book dry Chimera 1)",
            "/s2 lbdebug addlocal <item> - a local drop board for testing matching (never reported)",
            "/s2 lbdebug clearlocal - remove the local test boards",
            "Example: /s2 lbdebug dry RARE DROP! Summoning Eye")) {
            say(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int debugStatus() {
        boolean connected = Sky2MGlobalChat.currentConnection() != null;
        String problem = Sky2MLogin.problem();
        say(Component.literal("Connected to S2C: " + connected + ", logged in: " + Sky2MLogin.loggedIn()
            + (problem.isEmpty() ? "" : " (last login error: " + problem + ")")).withStyle(ChatFormatting.YELLOW));
        List<Board> all = new ArrayList<>(boards);
        all.addAll(LOCAL_BOARDS);
        if (all.isEmpty()) {
            say(Component.literal("No boards: the server hasn't sent leaderboardsActive, or none are running.").withStyle(ChatFormatting.GRAY));
        }
        long now = System.currentTimeMillis();
        for (Board board : all) {
            String ends = board.endsAt() <= 0 ? "no end" : board.endsAt() < now ? "ENDED" : "ends in " + (board.endsAt() - now) / 60_000L + "m";
            say(Component.literal(board.name() + " [" + board.id() + "] " + ends).withStyle(ChatFormatting.GOLD));
            for (Tracker t : board.trackers()) {
                String what = "drop".equals(t.kind()) ? "items " + t.items()
                    : "pattern " + (t.pattern() == null ? "(invalid or missing)" : t.pattern().pattern())
                        + (t.amountGroup() == null ? "" : ", amount group " + t.amountGroup());
                say(Component.literal("  " + t.kind() + ": " + what).withStyle(ChatFormatting.GRAY));
            }
        }
        sayGuiCaches(now);
        return 1;
    }

    /** The /s2 leaderboard GUI's cached packets, for finding out what the server has actually sent us. */
    private static void sayGuiCaches(long now) {
        say(Component.literal("Leaderboard GUI caches:").withStyle(ChatFormatting.GOLD));
        StatListData list = statList();
        say(Component.literal("stat list: " + (list == null ? "none yet"
            : list.collections().size() + " collections, " + list.slayers().size() + " slayer boards"
                + (list.refreshing() ? ", refreshing" : "") + " (" + age(list.at(), now) + ")")).withStyle(ChatFormatting.GRAY));
        StatResult result = statResult();
        say(Component.literal("stat result: " + (result == null ? "none yet"
            : "key \"" + result.key() + "\", " + (result.error().isEmpty()
                ? result.entries().size() + " entries" : "error: " + result.error()) + " (" + age(result.at(), now) + ")")).withStyle(ChatFormatting.GRAY));
        EventStandings event = eventStandings();
        say(Component.literal("event standings: " + (event == null ? "none yet"
            : "\"" + event.boardName() + "\", " + event.entries().size() + " entries (" + age(event.at(), now) + ")")).withStyle(ChatFormatting.GRAY));
    }

    private static String age(long at, long now) {
        long seconds = Math.max(0, (now - at) / 1000L);
        return seconds < 60 ? seconds + "s ago" : seconds / 60 + "m ago";
    }

    private static int debugLine(String line, boolean send) {
        List<String> matched = process(line, null, send, false);
        if (matched.isEmpty()) {
            say(Component.literal("No board counts that line.").withStyle(ChatFormatting.RED));
        } else {
            for (String m : matched) say(Component.literal((send ? "Sent " : "Would count ") + m).withStyle(ChatFormatting.GREEN));
            if (send && !Sky2MLogin.loggedIn()) {
                say(Component.literal("Logging in first; the reports go out once that is done.").withStyle(ChatFormatting.GRAY));
            }
        }
        return 1;
    }

    /**
     * "RARE DROP! (Enchanted Book) (+123% Magic Find)" with the book in the hover, like Hypixel sends it: the name
     * "Enchanted Book", the lore line "Chimera I" and custom data {id: ENCHANTED_BOOK, enchantments: {ultimate_chimera: 1}}.
     */
    private static int debugBook(String input, boolean send) {
        String text = input.trim();
        int level = 1;
        Matcher m = TRAILING_LEVEL.matcher(text);
        if (m.matches()) {
            text = m.group(1).trim();
            level = Math.max(1, Integer.parseInt(m.group(2)));
        }
        String enchant = titleCase(text.replace(' ', '_'));
        String key = (enchant.equalsIgnoreCase("Chimera") || enchant.startsWith("Ultimate ") ? "ultimate_" : "")
            + text.toLowerCase(Locale.ROOT).replaceFirst("^ultimate ", "").replace(' ', '_');
        net.minecraft.world.item.ItemStack book = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK);
        book.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Enchanted Book"));
        book.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
            List.of(Component.literal(enchant + " " + roman(level)).withStyle(ChatFormatting.LIGHT_PURPLE))));
        net.minecraft.nbt.CompoundTag data = new net.minecraft.nbt.CompoundTag();
        data.putString("id", "ENCHANTED_BOOK");
        net.minecraft.nbt.CompoundTag enchantments = new net.minecraft.nbt.CompoundTag();
        enchantments.putInt(key, level);
        data.put("enchantments", enchantments);
        book.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        Component message = Component.literal("RARE DROP! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal("(Enchanted Book)").withStyle(style -> style.withColor(ChatFormatting.WHITE).withBold(false)
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowItem(net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(book)))))
            .append(Component.literal(" (+123% \u272f Magic Find)").withStyle(ChatFormatting.AQUA));
        say(Component.literal("Test drop: ").withStyle(ChatFormatting.GRAY).append(message));
        say(Component.literal("Hover names: " + hoverNames(message)).withStyle(ChatFormatting.DARK_GRAY));
        List<String> matched = process(ChatFormatting.stripFormatting(message.getString()), message, send, false);
        if (matched.isEmpty()) {
            say(Component.literal("No board counts that drop.").withStyle(ChatFormatting.RED));
        } else {
            for (String line : matched) say(Component.literal((send ? "Sent " : "Would count ") + line).withStyle(ChatFormatting.GREEN));
        }
        return 1;
    }

    private static int debugAddLocal(String item) {
        String name = "Local test " + (LOCAL_BOARDS.size() + 1);
        LOCAL_BOARDS.add(new Board(LOCAL_PREFIX + name, name, 0, List.of(new Tracker("drop", List.of(item.trim()), null, null))));
        say(Component.literal("Added " + name + ", counting drops of " + item.trim() + " (local only, never reported).")
            .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(text));
            }
        });
    }
}
