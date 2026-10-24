package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-game staff tools: /s2 stafflogin &lt;code&gt; (a one-time code from shadowisabot.com, Staff &gt; Mod login),
 * /s2 staff (mute, ban and unmute in Sky2M chat; admins and owners also switch features for everyone) and
 * /s2 stafflogout. The server checks the role on every action (staff-mod.js); this only shows what it allows.
 *
 * <p>A login gives a token the mod keeps in config/sky2m/staff.json (not in the settings, so settings codes and cloud
 * sync never carry it). It's sent again after each Sky2M login, so staff stay logged in for 30 days.
 */
public final class SbcStaff {
    private static final Gson GSON = new Gson();

    /** The role the server confirmed on this connection ("mod", "admin", "owner"), or "" when not staff. */
    private static String role = "";
    private static String username = "";
    private static boolean canEditFlags;
    private static List<JsonObject> mutes = List.of();
    private static JsonObject flags = new JsonObject();
    /** The last action's answer, shown at the bottom of /s2 staff. */
    private static String status = "";
    private static boolean statusOk = true;
    private static boolean resuming;
    /** Every flag the server knows (id, group, name, description), for the Features tab. */
    private static List<JsonObject> catalog = List.of();
    private static List<JsonObject> auditEntries = List.of();
    private static boolean auditMore;
    private static int auditTotal;
    private static List<String> auditKinds = List.of();
    private static JsonObject lookup;
    private static List<JsonObject> onlinePlayers = List.of();
    private static JsonObject counts = new JsonObject();
    /** /s2 staff audit [n]: how many entries to print in chat when the answer arrives (0 = none). */
    private static int chatAudit;

    private SbcStaff() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("stafflogin")
                        .executes(c -> {
                            Sbc.say(Component.literal("Get a code on shadowisabot.com (Staff > Mod login), then /s2 stafflogin <code>.").withStyle(ChatFormatting.YELLOW));
                            return 1;
                        })
                        .then(ClientCommands.argument("code", StringArgumentType.word()).executes(c -> {
                            login(StringArgumentType.getString(c, "code"));
                            return 1;
                        })))
                    .then(ClientCommands.literal("stafflogout").executes(c -> {
                        logout();
                        return 1;
                    }))
                    .then(ClientCommands.literal("staff").executes(c -> {
                        open();
                        return 1;
                    })
                        .then(ClientCommands.literal("audit")
                            .executes(c -> {
                                chatAudit(10);
                                return 1;
                            })
                            .then(ClientCommands.argument("n", IntegerArgumentType.integer(1, 50)).executes(c -> {
                                chatAudit(IntegerArgumentType.getInteger(c, "n"));
                                return 1;
                            })))));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ state

    public static boolean isStaff() {
        return !role.isEmpty();
    }

    public static String role() {
        return role;
    }

    public static String username() {
        return username;
    }

    public static boolean canEditFlags() {
        return canEditFlags;
    }

    public static List<JsonObject> mutes() {
        return mutes;
    }

    public static JsonObject flags() {
        return flags;
    }

    public static List<JsonObject> catalog() {
        return catalog;
    }

    public static List<JsonObject> auditEntries() {
        return auditEntries;
    }

    public static boolean auditMore() {
        return auditMore;
    }

    public static int auditTotal() {
        return auditTotal;
    }

    public static List<String> auditKinds() {
        return auditKinds;
    }

    /** The last player lookup answer, or null. */
    public static JsonObject lookup() {
        return lookup;
    }

    public static List<JsonObject> onlinePlayers() {
        return onlinePlayers;
    }

    public static JsonObject counts() {
        return counts;
    }

    public static String status() {
        return status;
    }

    public static boolean statusOk() {
        return statusOk;
    }

    /** Whether a saved login is waiting for the server's answer. */
    public static boolean resuming() {
        return resuming;
    }

    public static boolean hasSavedLogin() {
        return !savedToken().isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ actions

    private static void login(String code) {
        JsonObject packet = Sbc.packet("staffLogin");
        packet.addProperty("code", code.trim());
        SbcNet.sendAuthed(packet);
    }

    private static void logout() {
        String token = savedToken();
        deleteSavedLogin();
        boolean wasStaff = isStaff();
        role = "";
        JsonObject packet = Sbc.packet("staffLogout");
        if (!token.isEmpty()) packet.addProperty("token", token);
        if (!SbcNet.sendQuietly(packet)) {
            Sbc.say(Component.literal(wasStaff || !token.isEmpty() ? "Logged out of the staff tools." : "You weren't logged in as staff.").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void open() {
        if (!isStaff() && hasSavedLogin()) resume();
        if (isStaff()) refresh();
        Compat.queueOpenScreen(new SbcStaffScreen());
    }

    /** Asks for the mutes and the feature flags again (/s2 staff, and after each change). */
    static void refresh() {
        if (!isStaff()) return;
        SbcNet.sendQuietly(Sbc.packet("staffMutes"));
        SbcNet.sendQuietly(Sbc.packet("staffFlags"));
    }

    /** Asks for one page of the audit log (newest first). before = the id of the last entry already seen, 0 for the first page. */
    static void audit(String kind, String text, String staff, String target, long before, int limit) {
        JsonObject packet = Sbc.packet("staffAudit");
        if (kind != null && !kind.isBlank()) packet.addProperty("filter", kind);
        if (text != null && !text.isBlank()) packet.addProperty("q", text.trim());
        if (staff != null && !staff.isBlank()) packet.addProperty("by", staff.trim());
        if (target != null && !target.isBlank()) packet.addProperty("target", target.trim());
        if (before > 0) packet.addProperty("before", before);
        packet.addProperty("limit", limit);
        SbcNet.sendQuietly(packet);
    }

    /** /s2 staff audit [n]: prints the newest n audit entries in chat. */
    private static void chatAudit(int n) {
        if (!isStaff()) {
            Sbc.error("You aren't logged in as staff. Use /s2 stafflogin <code>.");
            return;
        }
        chatAudit = n;
        audit("", "", "", "", 0, n);
    }

    static void lookup(String name) {
        JsonObject packet = Sbc.packet("staffLookup");
        packet.addProperty("username", name.trim());
        SbcNet.sendQuietly(packet);
    }

    static void requestOnline() {
        SbcNet.sendQuietly(Sbc.packet("staffOnline"));
        SbcNet.sendQuietly(Sbc.packet("staffCounts"));
    }

    static void announce(String message, String style) {
        JsonObject packet = Sbc.packet("staffAnnounce");
        packet.addProperty("message", message.trim());
        packet.addProperty("style", style);
        SbcNet.send(packet);
    }

    private static void resume() {
        String token = savedToken();
        if (token.isEmpty()) return;
        resuming = true;
        JsonObject packet = Sbc.packet("staffResume");
        packet.addProperty("token", token);
        SbcNet.sendAuthed(packet);
    }

    static void mute(String player, int minutes, String reason, boolean ban) {
        JsonObject packet = Sbc.packet("staffMute");
        packet.addProperty("username", player.trim());
        packet.addProperty("minutes", Math.max(0, minutes));
        packet.addProperty("reason", reason.trim());
        packet.addProperty("ban", ban);
        SbcNet.send(packet);
    }

    static void unmute(String player) {
        JsonObject packet = Sbc.packet("staffUnmute");
        packet.addProperty("username", player.trim());
        SbcNet.send(packet);
    }

    static void setFlag(String name, boolean enabled, String message) {
        JsonObject packet = Sbc.packet("staffSetFlag");
        packet.addProperty("name", name);
        packet.addProperty("enabled", enabled);
        if (message != null && !message.isBlank()) packet.addProperty("message", message.trim());
        SbcNet.send(packet);
    }

    // ------------------------------------------------------------------------------------------------ connection

    /** After each Sky2M login: a saved staff login logs in again on the new connection. */
    public static void onLoggedIn() {
        if (!isStaff() && hasSavedLogin()) resume();
    }

    /** The connection closed: the staff login was only for it (the saved one comes back with the next login). */
    public static void onDisconnected() {
        role = "";
        resuming = false;
        lookup = null;
        auditEntries = List.of();
        onlinePlayers = List.of();
    }

    static boolean handles(String type) {
        return type.startsWith("staff");
    }

    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "staffLoginResult" -> {
                boolean resumed = Sbc.bool(packet, "resumed");
                resuming = false;
                if (Sbc.bool(packet, "ok")) {
                    role = Sbc.str(packet, "role");
                    username = Sbc.str(packet, "username");
                    String token = Sbc.str(packet, "token");
                    if (!token.isEmpty()) saveLogin(token, Sbc.lng(packet, "expiresAt", 0));
                    if (!resumed) Sbc.say(Component.literal(Sbc.str(packet, "message")).withStyle(ChatFormatting.GREEN));
                    refresh();
                } else {
                    role = "";
                    if (resumed) deleteSavedLogin();
                    Sbc.error(Sbc.str(packet, "message"));
                }
            }
            case "staffLoggedOut" -> {
                role = "";
                Sbc.say(Component.literal(Sbc.str(packet, "message")).withStyle(ChatFormatting.YELLOW));
            }
            case "staffMutes" -> {
                List<JsonObject> list = new ArrayList<>();
                for (JsonElement e : Sbc.arr(packet, "mutes")) if (e.isJsonObject()) list.add(e.getAsJsonObject());
                mutes = List.copyOf(list);
            }
            case "staffFlags" -> {
                flags = Sbc.obj(packet, "flags");
                canEditFlags = Sbc.bool(packet, "canEdit");
                catalog = objects(packet, "catalog");
            }
            case "staffAudit" -> {
                auditEntries = objects(packet, "entries");
                auditMore = Sbc.bool(packet, "more");
                auditTotal = (int) Sbc.lng(packet, "total", 0);
                List<String> kinds = new ArrayList<>();
                for (JsonElement e : Sbc.arr(packet, "kinds")) kinds.add(e.getAsString());
                auditKinds = List.copyOf(kinds);
                if (chatAudit > 0) {
                    chatAudit = 0;
                    printAudit(auditEntries);
                }
            }
            case "staffLookup" -> lookup = packet;
            case "staffOnline" -> onlinePlayers = objects(packet, "players");
            case "staffCounts" -> counts = Sbc.obj(packet, "counts");
            case "staffResult" -> {
                statusOk = Sbc.bool(packet, "ok");
                status = Sbc.str(packet, "message");
                if (!(Minecraft.getInstance().gui.screen() instanceof SbcStaffScreen)) {
                    Sbc.say(Component.literal(status).withStyle(statusOk ? ChatFormatting.GREEN : ChatFormatting.RED));
                }
            }
            case "staffError" -> {
                statusOk = false;
                status = Sbc.str(packet, "message");
                if ("notStaff".equals(Sbc.str(packet, "code"))) role = "";
                Sbc.error(status);
            }
            default -> { }
        }
    }

    private static List<JsonObject> objects(JsonObject packet, String key) {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement e : Sbc.arr(packet, key)) if (e.isJsonObject()) list.add(e.getAsJsonObject());
        return List.copyOf(list);
    }

    /** "10-23 14:05" in the player's time zone. */
    static String time(long millis) {
        if (millis <= 0) return "never";
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()));
    }

    private static void printAudit(List<JsonObject> entries) {
        net.minecraft.network.chat.MutableComponent out = Component.literal("Staff audit log (newest first)").withStyle(ChatFormatting.GOLD);
        if (entries.isEmpty()) out.append(Component.literal("\n  Nothing logged yet.").withStyle(ChatFormatting.GRAY));
        for (JsonObject e : entries) {
            String target = Sbc.str(e, "target");
            out.append(Component.literal("\n  " + time(Sbc.lng(e, "at", 0)) + " ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("[" + Sbc.str(e, "kind") + "] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(Sbc.str(e, "by") + (target.isEmpty() ? "" : " → " + target) + ": ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(Sbc.str(e, "summary")).withStyle(ChatFormatting.GRAY));
        }
        Sbc.say(out);
    }

    // ------------------------------------------------------------------------------------------------ saved login

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("staff.json");
    }

    private static String savedToken() {
        try {
            if (!Files.exists(file())) return "";
            JsonObject saved = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
            if (saved == null) return "";
            long expires = Sbc.lng(saved, "expiresAt", 0);
            if (expires > 0 && expires < System.currentTimeMillis()) return "";
            return Sbc.str(saved, "token");
        } catch (Exception e) {
            return "";
        }
    }

    private static void saveLogin(String token, long expiresAt) {
        try {
            JsonObject saved = new JsonObject();
            saved.addProperty("token", token);
            saved.addProperty("expiresAt", expiresAt);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the staff login: " + e.getMessage());
        }
    }

    private static void deleteSavedLogin() {
        try {
            Files.deleteIfExists(file());
        } catch (Exception ignored) {}
    }
}
