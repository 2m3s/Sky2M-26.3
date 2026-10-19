package com.epic60869.sky2m.reports;

import com.epic60869.sky2m.Sky2MLogin;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.sbc.Flags;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.epic60869.sky2m.features.sbc.SbcCrashReports;
import com.epic60869.sky2m.features.sbc.SbcNet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bug reports, suggestions and feedback, as forum threads.
 *
 * Anyone can start one with /s2 bugreport, /s2 suggest or /s2 feedback (a window with a title and a description) and
 * read their own with /s2 reports: every report is a thread where the player and the Sky2M admins (the owner 2m3s, and
 * any staff the server makes admins) write back and forth. Admins see every thread, can mark it read, answered or
 * closed, and delete it. Someone online gets a line in chat when the other side answers; someone offline gets it when
 * they next log in.
 *
 * Threads go over the logged-in Sky2M WebSocket (the server knows who each connection is):
 * <pre>
 * mod -> server: reportsPending {}, reportsMine {}, reportsAll {filter: all|bug|suggestion|feedback,
 *                    status: any|open|read|answered|closed}, reportThread {id}, reportReply {id, text},
 *                reportSubmit {type, title, description, modVersion}, reportMarkRead {id} (admin),
 *                reportSetStatus {id, status} (admin), reportDelete {id} (admin)
 * server -> mod: reportsPending {admin, threads: [summary]}  (threads with something new for this player)
 *                reportsList {scope: mine|all, admin, threads: [summary]}
 *                reportThread {admin, thread: {...summary, messages: [{id, author {uuid, name, admin}, text, createdAt}]}}
 *                reportSubmitted {thread}
 *                reportUpdated {event: new|reply|status|deleted, thread: summary, by {uuid, name, admin}, message?}
 *                reportDeleted {id}
 *                reportError {code, message, request, id?}
 * summary = {id, type, title, status, createdAt, updatedAt, author {uuid, name}, messageCount,
 *            lastMessage {id, author, text, createdAt}, unreadForUser, unreadForAdmin, unread}
 * </pre>
 * The full spec is reports-forum-server-prompt.md in the repository root.
 *
 * Until the server knows these packets (it answers reportsPending with nothing or a plain error, or the
 * "reportThreads" flag is off), the old way still works: reports are sent to the site, and only the owner can read
 * and delete them there. Reading them proves who you are the way Minecraft servers do: the site hands out a random
 * server id, the mod "joins" it through Mojang's session server, and the site asks Mojang whether 2m3s joined it:
 * <pre>
 * POST   /mod-api/reports                 {type: bug|suggestion|feedback, title, description, uuid, username, modVersion}
 * GET    /mod-api/auth/challenge          -> {serverId}
 * POST   /mod-api/auth/verify             {username, serverId} -> {token}   (checks Mojang hasJoined, owner only)
 * GET    /mod-api/reports?type=bug|suggestion|all   (Authorization: Bearer token) -> {reports: [{id, type, title,
 *        description, username, uuid, createdAt}]}
 * DELETE /mod-api/reports/{id}            (Authorization: Bearer token)
 * </pre>
 */
public final class Reports {
    public static final UUID OWNER = UUID.fromString("9761ccfb-2ccb-45cf-b6b4-fac991b7a019"); // 2m3s
    static final String API = "https://shadowisabot.com/mod-api";
    static final String FLAG = "reportThreads";

    public static final int TITLE_MAX = 100;
    public static final int MESSAGE_MAX = 1000;
    /** The longest description the server takes. */
    public static final int DESCRIPTION_MAX = 4000;
    /** Shortest gap between two sends (new reports and replies); the server enforces it too. */
    static final long SEND_GAP_MS = 3_000L;

    public enum Type {
        BUG("bug", "Bug Report", "BUG", 0xFFFF5555),
        SUGGESTION("suggestion", "Suggestion", "IDEA", 0xFF55FFFF),
        FEEDBACK("feedback", "Feedback", "FEEDBACK", 0xFFFF55FF);

        final String id;
        public final String label;
        final String tag;
        final int colour;

        Type(String id, String label, String tag, int colour) {
            this.id = id;
            this.label = label;
            this.tag = tag;
            this.colour = colour;
        }

        static Type of(String id) {
            for (Type t : values()) if (t.id.equalsIgnoreCase(id)) return t;
            return FEEDBACK;
        }
    }

    public enum Status {
        OPEN("open", "Open", 0xFFFFAA00), READ("read", "Read", 0xFF55AAFF),
        ANSWERED("answered", "Answered", 0xFF55FF55), CLOSED("closed", "Closed", 0xFF888888);

        final String id;
        final String label;
        final int colour;

        Status(String id, String label, int colour) {
            this.id = id;
            this.label = label;
            this.colour = colour;
        }

        static Status of(String id) {
            for (Status s : values()) if (s.id.equalsIgnoreCase(id)) return s;
            return OPEN;
        }
    }

    public record Author(String uuid, String name, boolean admin) {
        boolean isSelf() {
            return Sbc.isSelf(uuid);
        }
    }

    public record Message(String id, Author author, String text, long createdAt) {}

    /** A thread; {@code messages} is empty in a summary (lists), filled when opened. */
    public record ReportThread(String id, Type type, String title, Status status, long createdAt, long updatedAt,
                               Author author, int messageCount, Message last, List<Message> messages,
                               boolean unreadForUser, boolean unreadForAdmin, boolean unread) {
        boolean mine() {
            return author.isSelf();
        }
    }

    /** An old-style report from the site (the owner's fallback view). */
    public record Report(String id, Type type, String title, String description, String username, long createdAt) {}

    /** Whether the connected server knows the report thread packets. */
    enum Support { UNKNOWN, YES, NO }

    /** What a submit came to: ok, the thread id when it's a thread, or why it failed. */
    record SubmitResult(boolean ok, String threadId, String message) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final long ANSWER_TIMEOUT_MS = 8_000L;
    private static final long SUBMIT_TIMEOUT_MS = 12_000L;
    private static final int PENDING_LINES = 3;

    // REST (fallback) state.
    private static String token;
    private static long tokenAt;
    private static boolean restAnnounced;

    // Thread state; only touched on the game thread.
    static volatile Support support = Support.UNKNOWN;
    /** When reportsPending was asked, while support is still unknown (0 = not waiting). */
    private static long supportAskedAt;
    /** Whether the server says this player is an admin. */
    static volatile boolean admin;
    static volatile List<ReportThread> threads = List.of();
    static volatile boolean threadsLoaded;
    /** The thread the thread screen shows (full, with messages), or null. */
    static volatile ReportThread current;
    /** Set when the open thread was deleted, so its screen goes back. */
    static volatile String deletedId = "";
    /** Bumped on every report packet, so open screens refresh. */
    static volatile int version;
    static String error = "";
    static long errorAt;

    /** When a report packet was last sent; a plain "error" soon after is the server answering it. */
    private static volatile long sentAt;
    private static JsonObject lastAuthed;
    private static boolean retried;
    private static long lastSendAt;

    private static CompletableFuture<SubmitResult> pendingSubmit;
    private static long pendingSubmitAt;
    private static Type pendingSubmitType;
    private static String pendingSubmitTitle;
    private static String pendingSubmitDescription;

    /** Thread id -> updatedAt already announced in chat, so one update is said once. */
    private static final Map<String, Long> NOTIFIED = new HashMap<>();
    /** Chat lines waiting for the player to be in a world. */
    private static final List<Component> QUEUED = new ArrayList<>();
    private static long joinedAt;
    private static boolean announcePendingNext;

    private Reports() {}

    public static boolean isOwner() {
        return OWNER.equals(Minecraft.getInstance().getUser().getProfileId());
    }

    /**
     * Admin rights: what the server says once it supports threads; until then, the owner and anyone the website's
     * Staff section makes a reports admin (see Sky2MStaff).
     */
    static boolean isAdmin() {
        if (support == Support.YES) return admin;
        return isOwner() || com.epic60869.sky2m.Sky2MStaff.isReportsAdmin(Minecraft.getInstance().getUser().getProfileId());
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("sky2m").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                var node = ClientCommands.literal(root)
                    .then(ClientCommands.literal("bugreport").executes(c -> open(Type.BUG)))
                    .then(ClientCommands.literal("suggest").executes(c -> open(Type.SUGGESTION)))
                    .then(ClientCommands.literal("feedback").executes(c -> open(Type.FEEDBACK)))
                    .then(ClientCommands.literal("reports")
                        .executes(c -> openForum(null))
                        .then(ClientCommands.literal("open").then(ClientCommands.argument("id", StringArgumentType.greedyString())
                            .executes(c -> openThread(StringArgumentType.getString(c, "id"))))));
                // The old owner commands: the admin view, filtered. Registered for everyone, since staff can be made
                // admins on the website at any time; non-admins just get their own reports.
                node = node
                    .then(ClientCommands.literal("viewboth").executes(c -> view(null)))
                    .then(ClientCommands.literal("viewreport").executes(c -> view(Type.BUG)))
                    .then(ClientCommands.literal("viewsuggest").executes(c -> view(Type.SUGGESTION)));
                dispatcher.register(node);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joinedAt = System.currentTimeMillis());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (Exception e) {
                SbcCrashReports.report(e, "reports tick");
            }
        });
    }

    private static int open(Type type) {
        return Compat.queueOpenScreen(new ReportScreen(type));
    }

    private static int openForum(Type filter) {
        return Compat.queueOpenScreen(new ReportsForumScreen(filter));
    }

    private static int openThread(String id) {
        return Compat.queueOpenScreen(new ReportThreadScreen(id.trim(), null));
    }

    /** /s2 viewboth | viewreport | viewsuggest: the admin view, or the old site view when threads aren't there yet. */
    private static int view(Type filter) {
        if (!isAdmin()) return openForum(null);
        // The old site view needs the owner's own login token.
        if ((support == Support.NO || !Flags.isEnabled(FLAG)) && isOwner()) {
            ReportsViewScreen.Filter old = filter == null ? ReportsViewScreen.Filter.ALL
                : filter == Type.BUG ? ReportsViewScreen.Filter.BUGS : ReportsViewScreen.Filter.SUGGESTIONS;
            return Compat.queueOpenScreen(new ReportsViewScreen(old));
        }
        return openForum(filter);
    }

    // ---------------------------------------------------------------- connection and support

    /** Whether the threads (replies, statuses, the forum screens) can be used right now. */
    static boolean threadsReady() {
        return support == Support.YES && SbcNet.online() && Flags.isEnabled(FLAG);
    }

    /** Called by Sky2MLogin on the game thread after each login: what's new for me, which also shows whether the server knows threads. */
    public static void onLoggedIn() {
        if (!Flags.isEnabled(FLAG)) {
            support = Support.NO;
            version++;
            return;
        }
        announcePendingNext = true;
        askSupport();
    }

    /** Asks reportsPending (if not already waiting); the answer, or its absence, settles {@link #support}. */
    static void askSupport() {
        if (supportAskedAt > 0 || !SbcNet.online()) return;
        supportAskedAt = System.currentTimeMillis();
        sentAt = supportAskedAt;
        SbcNet.sendAuthed(Sbc.packet("reportsPending"));
    }

    /** Called on the game thread when the connection closes: what we knew was only true for it. */
    public static void onDisconnected() {
        support = Support.UNKNOWN;
        supportAskedAt = 0;
        threadsLoaded = false;
        failSubmit("S2C offline: can't reach the Sky2M server right now.");
        version++;
    }

    /** Whether a plain server "error" packet is probably the answer to a report packet. */
    public static boolean awaitingReply() {
        return System.currentTimeMillis() - sentAt < 5_000L;
    }

    /**
     * A plain "error" packet the server sent in answer to a report packet. While support isn't known yet that means
     * an older server that doesn't know the packets: fall back to the site.
     */
    public static void plainError(String code, String message) {
        sentAt = 0;
        if ("notLoggedIn".equals(code)) {
            onError(code, message, "");
            return;
        }
        if (support != Support.YES) {
            unsupported();
            return;
        }
        onError(code, message, "");
    }

    private static void unsupported() {
        support = Support.NO;
        supportAskedAt = 0;
        if (pendingSubmit != null) {
            // Send it the old way instead, so nothing is lost.
            CompletableFuture<SubmitResult> future = pendingSubmit;
            pendingSubmit = null;
            submitRest(pendingSubmitType, pendingSubmitTitle, pendingSubmitDescription)
                .thenAccept(ok -> future.complete(new SubmitResult(ok, "", ok ? "" : "Couldn't send it; try again in a moment.")));
        }
        version++;
    }

    private static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (support == Support.UNKNOWN && supportAskedAt > 0) {
            if (!Sky2MLogin.loggedIn()) {
                // reportsPending waits for the login before it goes out: the clocks start once it has.
                supportAskedAt = now;
                sentAt = now;
            } else if (now - supportAskedAt > ANSWER_TIMEOUT_MS) {
                unsupported(); // no answer at all: the server drops packets it doesn't know
            }
        }
        if (pendingSubmit != null && now - pendingSubmitAt > SUBMIT_TIMEOUT_MS) {
            failSubmit("The Sky2M server didn't answer; try again in a moment.");
        }
        if (mc.player != null && joinedAt > 0 && now - joinedAt > 3_000L) {
            if (!QUEUED.isEmpty()) {
                for (Component line : QUEUED) mc.gui.hud.getChat().addClientSystemMessage(line);
                QUEUED.clear();
            }
            // The owner's old "waiting for you" line, when the server can't do threads (or can't be reached).
            if (!restAnnounced && isOwner() && now - joinedAt > 15_000L && support != Support.YES) {
                restAnnounced = true;
                announceRestPending();
            }
        }
    }

    // ---------------------------------------------------------------- sending (threads)

    /** Milliseconds until another reply or report may be sent (0 = now). */
    static long sendWait() {
        return Math.max(0, lastSendAt + SEND_GAP_MS - System.currentTimeMillis());
    }

    private static void authed(JsonObject packet) {
        sentAt = System.currentTimeMillis();
        lastAuthed = packet;
        retried = false;
        SbcNet.sendAuthed(packet);
    }

    static void requestList(Type filter, Status status) {
        if (!threadsReady()) return;
        if (admin) {
            JsonObject p = Sbc.packet("reportsAll");
            p.addProperty("filter", filter == null ? "all" : filter.id);
            p.addProperty("status", status == null ? "any" : status.id);
            authed(p);
        } else {
            authed(Sbc.packet("reportsMine"));
        }
    }

    static void requestThread(String id) {
        if (!threadsReady()) return;
        authed(withId("reportThread", id));
    }

    /** Sends a reply; false (with {@link #error} set) when it can't go yet. */
    static boolean reply(String id, String text) {
        String t = text.strip();
        if (t.isEmpty()) return false;
        if (t.length() > MESSAGE_MAX) {
            setError("Replies can be at most " + MESSAGE_MAX + " characters.");
            return false;
        }
        if (sendWait() > 0) {
            setError("Wait a moment before sending again.");
            return false;
        }
        if (!threadsReady()) {
            setError(SbcNet.OFFLINE);
            return false;
        }
        lastSendAt = System.currentTimeMillis();
        JsonObject p = withId("reportReply", id);
        p.addProperty("text", t);
        authed(p);
        return true;
    }

    static void markRead(String id) {
        if (threadsReady() && admin) authed(withId("reportMarkRead", id));
    }

    static void setStatus(String id, Status status) {
        if (!threadsReady() || !admin) return;
        JsonObject p = withId("reportSetStatus", id);
        p.addProperty("status", status.id);
        authed(p);
    }

    static void deleteThread(String id) {
        if (threadsReady() && admin) authed(withId("reportDelete", id));
    }

    private static JsonObject withId(String type, String id) {
        JsonObject p = Sbc.packet(type);
        p.addProperty("id", id);
        return p;
    }

    /**
     * Sends a new report: as a thread when the server knows them, otherwise to the site like before (also when the
     * server turns out not to know reportSubmit). Completes on the game thread or the HTTP thread.
     */
    static CompletableFuture<SubmitResult> submit(Type type, String title, String description) {
        String t = title.strip();
        String d = description.strip();
        if (t.length() > TITLE_MAX) t = t.substring(0, TITLE_MAX);
        long wait = sendWait();
        if (wait > 0) return CompletableFuture.completedFuture(new SubmitResult(false, "", "Wait a moment before sending again."));
        lastSendAt = System.currentTimeMillis();
        if (!threadsReady() || pendingSubmit != null) {
            return submitRest(type, t, d).thenApply(ok -> new SubmitResult(ok, "", ok ? "" : "Couldn't send it; try again in a moment."));
        }
        CompletableFuture<SubmitResult> future = new CompletableFuture<>();
        pendingSubmit = future;
        pendingSubmitAt = System.currentTimeMillis();
        pendingSubmitType = type;
        pendingSubmitTitle = t;
        pendingSubmitDescription = d;
        JsonObject p = Sbc.packet("reportSubmit");
        // "type" is the packet's own name; overwriting it with "bug"/"suggestion" made the server drop the report.
        p.addProperty("reportType", type.id);
        p.addProperty("title", t);
        p.addProperty("description", d);
        p.addProperty("modVersion", version());
        authed(p);
        return future;
    }

    private static void failSubmit(String message) {
        if (pendingSubmit == null) return;
        CompletableFuture<SubmitResult> future = pendingSubmit;
        pendingSubmit = null;
        future.complete(new SubmitResult(false, "", message));
    }

    // ---------------------------------------------------------------- receiving (threads)

    /** A report* packet from the server, on the game thread. */
    public static void handle(String type, JsonObject packet) {
        try {
            if (!"reportError".equals(type) || !"unknownType".equals(Sbc.str(packet, "code"))) {
                support = Support.YES;
                supportAskedAt = 0;
            }
            if (packet.has("admin")) admin = Sbc.bool(packet, "admin");
            switch (type) {
                case "reportsPending" -> {
                    List<ReportThread> pending = parseThreads(Sbc.arr(packet, "threads"));
                    restAnnounced = true; // the threads cover the owner's announcement now
                    if (announcePendingNext) {
                        announcePendingNext = false;
                        announcePending(pending);
                    }
                }
                case "reportsList" -> {
                    threads = sorted(parseThreads(Sbc.arr(packet, "threads")));
                    threadsLoaded = true;
                }
                case "reportThread" -> {
                    ReportThread thread = parseThread(Sbc.obj(packet, "thread"));
                    if (!thread.id().isEmpty()) {
                        current = thread;
                        replaceSummary(thread);
                        NOTIFIED.merge(thread.id(), thread.updatedAt(), Math::max);
                    }
                }
                case "reportSubmitted" -> {
                    ReportThread thread = parseThread(Sbc.obj(packet, "thread"));
                    NOTIFIED.put(thread.id(), thread.updatedAt());
                    replaceSummary(thread);
                    if (pendingSubmit != null) {
                        CompletableFuture<SubmitResult> future = pendingSubmit;
                        pendingSubmit = null;
                        future.complete(new SubmitResult(true, thread.id(), ""));
                    }
                }
                case "reportUpdated" -> onUpdated(packet);
                case "reportDeleted" -> removed(Sbc.str(packet, "id"));
                case "reportError" -> {
                    if ("unknownType".equals(Sbc.str(packet, "code")) && support != Support.YES) {
                        unsupported();
                    } else {
                        onError(Sbc.str(packet, "code"), Sbc.str(packet, "message"), Sbc.str(packet, "request"));
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            SbcCrashReports.report(e, "report packet " + type);
        }
        version++;
    }

    private static void onError(String code, String message, String request) {
        if ("notLoggedIn".equals(code)) {
            Sky2MLogin.forget();
            if (lastAuthed != null && !retried) {
                retried = true; // log in and try once more
                SbcNet.sendAuthed(lastAuthed);
                return;
            }
        }
        String text = message.isBlank() ? "The Sky2M server refused that" + (code.isBlank() ? "." : " (" + code + ").") : message;
        if (pendingSubmit != null && (request.isEmpty() || "reportSubmit".equals(request))) {
            failSubmit(text);
            return;
        }
        if ("notFound".equals(code) && current != null) deletedId = current.id();
        setError(text);
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof ReportsForumScreen) && !(mc.gui.screen() instanceof ReportThreadScreen)) {
            Sbc.error(text);
        }
    }

    static void setError(String message) {
        error = message;
        errorAt = System.currentTimeMillis();
        version++;
    }

    /** reportUpdated: a reply, a status change, a new report or a deletion by someone else. */
    private static void onUpdated(JsonObject packet) {
        String event = Sbc.str(packet, "event");
        ReportThread thread = parseThread(Sbc.obj(packet, "thread"));
        if (thread.id().isEmpty()) return;
        if ("deleted".equals(event)) {
            removed(thread.id());
            return;
        }
        replaceSummary(thread);
        JsonObject byJson = Sbc.obj(packet, "by");
        Author by = Sbc.hasObj(packet, "by") ? parseAuthor(byJson)
            : thread.last() != null ? thread.last().author() : thread.author();
        Minecraft mc = Minecraft.getInstance();
        boolean viewing = current != null && current.id().equals(thread.id()) && mc.gui.screen() instanceof ReportThreadScreen;
        if (viewing) {
            requestThread(thread.id()); // the open screen shows it; asking for it also marks it read
            NOTIFIED.merge(thread.id(), thread.updatedAt(), Math::max);
            return;
        }
        if (by.isSelf()) return;
        Long said = NOTIFIED.get(thread.id());
        if (said != null && said >= thread.updatedAt()) return;
        NOTIFIED.put(thread.id(), thread.updatedAt());
        Component line = switch (event) {
            case "new" -> line(by.name() + " sent a new " + thread.type().label + ": ", thread);
            case "status" -> thread.mine()
                ? line(by.name() + " marked your " + thread.type().label + " as " + thread.status().label + ": ", thread)
                : null; // other admins' status changes aren't worth a line
            default -> thread.mine()
                ? line(by.name() + " Has Responded To Your " + thread.type().label + ": ", thread)
                : line(by.name() + " replied to their " + thread.type().label + ": ", thread);
        };
        if (line != null) chat(line);
    }

    private static void removed(String id) {
        if (id.isEmpty()) return;
        threads = threads.stream().filter(t -> !t.id().equals(id)).toList();
        if (current != null && current.id().equals(id)) deletedId = id;
    }

    private static void replaceSummary(ReportThread thread) {
        List<ReportThread> list = new ArrayList<>(threads);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(thread.id())) {
                list.set(i, thread);
                found = true;
            }
        }
        if (!found) {
            // Only add it if this list would show it (an admin sees everything; a player only their own).
            if (!threadsLoaded || !(admin || thread.mine())) return;
            list.add(thread);
        }
        threads = sorted(list);
    }

    /** Unread first, then the most recently active. */
    private static List<ReportThread> sorted(List<ReportThread> list) {
        List<ReportThread> out = new ArrayList<>(list);
        out.sort(Comparator.comparing((ReportThread t) -> !t.unread()).thenComparing(Comparator.comparingLong(ReportThread::updatedAt).reversed()));
        return List.copyOf(out);
    }

    /** On login: one line per thread with something new, or a summary when there are many. */
    private static void announcePending(List<ReportThread> pending) {
        List<ReportThread> fresh = new ArrayList<>();
        for (ReportThread t : pending) {
            Long said = NOTIFIED.get(t.id());
            if (said != null && said >= t.updatedAt()) continue;
            NOTIFIED.put(t.id(), t.updatedAt());
            fresh.add(t);
        }
        if (fresh.isEmpty()) return;
        if (fresh.size() > PENDING_LINES) {
            long mine = fresh.stream().filter(ReportThread::mine).count();
            long others = fresh.size() - mine;
            StringBuilder text = new StringBuilder();
            if (mine > 0) text.append("The Sky2M team responded to ").append(mine).append(" of your reports");
            if (others > 0) {
                long fresh1 = fresh.stream().filter(t -> !t.mine() && t.messageCount() <= 1).count();
                if (!text.isEmpty()) text.append("; ");
                text.append(others).append(others == 1 ? " report needs you" : " reports need you")
                    .append(" (").append(fresh1).append(" new, ").append(others - fresh1).append(" with replies)");
            }
            chat(Component.literal(text + ". ").withStyle(ChatFormatting.YELLOW).append(viewButton("/s2 reports", "Open your reports")));
            return;
        }
        for (ReportThread t : fresh) {
            String who = t.last() != null ? t.last().author().name() : t.author().name();
            if (t.mine()) chat(line(who + " Has Responded To Your " + t.type().label + ": ", t));
            else if (t.messageCount() <= 1) chat(line(t.author().name() + " sent a new " + t.type().label + ": ", t));
            else chat(line(who + " replied to their " + t.type().label + ": ", t));
        }
    }

    /** "<lead><title> [View]", all of it clickable to open the thread. */
    private static Component line(String lead, ReportThread t) {
        String command = "/s2 reports open " + t.id();
        MutableComponent text = Component.literal(lead).withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(t.title()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" "))
            .append(viewButton(command, "Open this " + t.type().label.toLowerCase()));
        return text.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to open"))));
    }

    static Component viewButton(String command, String hover) {
        return Component.literal("[View]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** "[S2M] line" now if in a world, otherwise as soon as the player is. */
    static void chat(Component line) {
        Component full = Sbc.prefix().append(line);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null && System.currentTimeMillis() - joinedAt > 3_000L) mc.gui.hud.getChat().addClientSystemMessage(full);
            else QUEUED.add(full);
        });
    }

    // ---------------------------------------------------------------- parsing

    private static List<ReportThread> parseThreads(JsonArray array) {
        List<ReportThread> out = new ArrayList<>();
        for (JsonElement e : array) if (e.isJsonObject()) out.add(parseThread(e.getAsJsonObject()));
        return out;
    }

    static Author parseAuthor(JsonObject o) {
        return new Author(Sbc.str(o, "uuid"), Sbc.str(o, "name"), Sbc.bool(o, "admin"));
    }

    private static Message parseMessage(JsonObject o) {
        return new Message(Sbc.str(o, "id"), parseAuthor(Sbc.obj(o, "author")), Sbc.str(o, "text"), Sbc.lng(o, "createdAt", 0));
    }

    static ReportThread parseThread(JsonObject o) {
        List<Message> messages = new ArrayList<>();
        for (JsonElement e : Sbc.arr(o, "messages")) if (e.isJsonObject()) messages.add(parseMessage(e.getAsJsonObject()));
        Message last = Sbc.hasObj(o, "lastMessage") ? parseMessage(Sbc.obj(o, "lastMessage"))
            : messages.isEmpty() ? null : messages.get(messages.size() - 1);
        Author author = parseAuthor(Sbc.obj(o, "author"));
        boolean forUser = Sbc.bool(o, "unreadForUser");
        boolean forAdmin = Sbc.bool(o, "unreadForAdmin");
        boolean unread = o.has("unread") ? Sbc.bool(o, "unread") : author.isSelf() ? forUser : admin && forAdmin;
        long created = Sbc.lng(o, "createdAt", 0);
        return new ReportThread(Sbc.str(o, "id"), Type.of(Sbc.str(o, "type")), Sbc.str(o, "title"), Status.of(Sbc.str(o, "status")),
            created, Sbc.lng(o, "updatedAt", created), author, (int) Sbc.lng(o, "messageCount", messages.size()), last,
            List.copyOf(messages), forUser, forAdmin, unread);
    }

    // ---------------------------------------------------------------- the site (fallback)

    /** Sends a report to the site; the future says whether it accepted it. */
    static CompletableFuture<Boolean> submitRest(Type type, String title, String description) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject body = new JsonObject();
        body.addProperty("type", type.id);
        body.addProperty("title", title);
        body.addProperty("description", description);
        body.addProperty("uuid", mc.getUser().getProfileId().toString());
        body.addProperty("username", mc.getUser().getName());
        body.addProperty("modVersion", version());
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/reports"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Sky2M/" + version())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 300)
            .exceptionally(e -> false);
    }

    /** A token proving to the site that this is the owner, via Mojang's session server. */
    private static CompletableFuture<String> auth() {
        if (!isOwner()) return CompletableFuture.failedFuture(new IllegalStateException("not the owner"));
        if (token != null && System.currentTimeMillis() - tokenAt < 30 * 60_000L) return CompletableFuture.completedFuture(token);
        Minecraft mc = Minecraft.getInstance();
        return HTTP.sendAsync(get("/auth/challenge", null), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) throw new IllegalStateException("challenge HTTP " + r.statusCode());
            String serverId = JsonParser.parseString(r.body()).getAsJsonObject().get("serverId").getAsString();
            try {
                mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
            } catch (Exception e) {
                throw new IllegalStateException("Mojang session: " + e.getMessage());
            }
            JsonObject verify = new JsonObject();
            verify.addProperty("username", mc.getUser().getName());
            verify.addProperty("serverId", serverId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/auth/verify"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(verify.toString())).build();
            try {
                HttpResponse<String> v = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (v.statusCode() != 200) throw new IllegalStateException("verify HTTP " + v.statusCode());
                token = JsonParser.parseString(v.body()).getAsJsonObject().get("token").getAsString();
                tokenAt = System.currentTimeMillis();
                return token;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage());
            }
        });
    }

    private static HttpRequest get(String path, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Sky2M/" + version()).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return b.build();
    }

    /** Every report on the site (bugs, suggestions and feedback), newest first. */
    static CompletableFuture<List<Report>> fetchAll() {
        return auth().thenCompose(t -> HTTP.sendAsync(get("/reports?type=all", t), HttpResponse.BodyHandlers.ofString())).thenApply(r -> {
            if (r.statusCode() == 401 || r.statusCode() == 403) token = null;
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
            JsonElement root = JsonParser.parseString(r.body());
            JsonArray array = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("reports");
            List<Report> out = new ArrayList<>();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Report(str(o, "id"), Type.of(str(o, "type")), str(o, "title"), str(o, "description"),
                    str(o, "username"), o.has("createdAt") ? o.get("createdAt").getAsLong() : 0));
            }
            out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
            return out;
        });
    }

    /** Deletes a report on the site (the owner's fallback view). */
    static CompletableFuture<Boolean> delete(String id) {
        return auth().thenCompose(t -> HTTP.sendAsync(HttpRequest.newBuilder(URI.create(API + "/reports/" + id))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + t).DELETE().build(),
            HttpResponse.BodyHandlers.ofString())).thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 300);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** "You have 2 bug reports, 1 suggestion and 3 feedback waiting. [View]" (from the site). */
    private static void announceRestPending() {
        fetchAll().thenAccept(list -> {
            long bugs = list.stream().filter(r -> r.type() == Type.BUG).count();
            long suggestions = list.stream().filter(r -> r.type() == Type.SUGGESTION).count();
            long feedback = list.stream().filter(r -> r.type() == Type.FEEDBACK).count();
            if (bugs + suggestions + feedback == 0) return;
            MutableComponent message = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Waiting for you: ").withStyle(ChatFormatting.YELLOW))
                .append(count(bugs, "bug report", ChatFormatting.RED, "viewreport"))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(count(suggestions, "suggestion", ChatFormatting.AQUA, "viewsuggest"))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(count(feedback, "feedback", ChatFormatting.LIGHT_PURPLE, "viewsuggest"))
                .append(Component.literal("  [View All]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
                    .withClickEvent(new ClickEvent.RunCommand("/s2 viewboth"))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open all reports, suggestions and feedback")))));
            say(message);
        }).exceptionally(e -> {
            say(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Couldn't check for bug reports: " + rootMessage(e)).withStyle(ChatFormatting.GRAY)));
            return null;
        });
    }

    private static Component count(long n, String word, ChatFormatting colour, String command) {
        String text = n + " " + word + (n == 1 || word.equals("feedback") ? "" : "s");
        return Component.literal(text).withStyle(s -> s.withColor(colour)
            .withClickEvent(new ClickEvent.RunCommand("/s2 " + command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to view"))));
    }

    static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
        });
    }
}
