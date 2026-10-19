package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
                    })));
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
            }
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
