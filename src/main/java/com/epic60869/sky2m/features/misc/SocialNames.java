package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.features.core.Sky2MChat;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your friends' and guild members' names, for Command Tab Complete, read from Hypixel's chat like SkyHanni's FriendApi
 * and GuildApi: the "/f list" pages, "/g online" and "/g list", and friend and guild join or leave messages. Kept in
 * config/sky2m/social-names.json between sessions.
 */
public final class SocialNames {
    private static final String RANK = "(?:\\[[^\\]]+\\] )?";
    private static final Pattern FRIEND_LIST_LINE = Pattern.compile("^" + RANK + "(\\w{1,16}) (?:is|was) .+$");
    private static final Pattern FRIEND_ADDED = Pattern.compile("^You are now friends with " + RANK + "(\\w{1,16})$");
    private static final Pattern FRIEND_REMOVED = Pattern.compile("^You removed " + RANK + "(\\w{1,16}) from your friends list!$");
    private static final Pattern FRIEND_REMOVED_YOU = Pattern.compile("^" + RANK + "(\\w{1,16}) removed you from their friends list!$");
    private static final Pattern FRIEND_ACTIVITY = Pattern.compile("^Friend > " + RANK + "(\\w{1,16}) (?:joined|left)\\.$");
    private static final Pattern GUILD_JOINED = Pattern.compile("^" + RANK + "(\\w{1,16}) joined the guild!$");
    private static final Pattern GUILD_LEFT = Pattern.compile("^" + RANK + "(\\w{1,16}) (?:left the guild!|was kicked from the guild.*)$");
    private static final Pattern GUILD_NAME = Pattern.compile(RANK + "(\\w{1,16})");

    private static final Set<String> FRIENDS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> GUILD = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static Path file;
    private static boolean loaded;

    private SocialNames() {}

    public static void init() {
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
    }

    public static synchronized List<String> friends() {
        load();
        return new ArrayList<>(FRIENDS);
    }

    public static synchronized List<String> guild() {
        load();
        return new ArrayList<>(GUILD);
    }

    private static synchronized void onChat(String text) {
        // A "/f list" page arrives as one message, a line per friend: "Name is in SkyBlock - Garden".
        if (text.contains("Friends (Page")) {
            load();
            boolean added = false;
            for (String line : text.split("\\R")) {
                Matcher friend = FRIEND_LIST_LINE.matcher(line.trim());
                if (friend.matches()) added |= FRIENDS.add(friend.group(1));
            }
            if (added) save();
            return;
        }
        // Player chat ("Name: hi") never counts.
        if (text.contains(":")) return;
        load();
        boolean changed = false;
        Matcher m;
        if ((m = FRIEND_ADDED.matcher(text)).matches()) changed = FRIENDS.add(m.group(1));
        else if ((m = FRIEND_REMOVED.matcher(text)).matches() || (m = FRIEND_REMOVED_YOU.matcher(text)).matches()) changed = FRIENDS.remove(m.group(1));
        else if ((m = FRIEND_ACTIVITY.matcher(text)).matches()) changed = FRIENDS.add(m.group(1));
        else if ((m = GUILD_JOINED.matcher(text)).matches()) changed = GUILD.add(m.group(1));
        else if ((m = GUILD_LEFT.matcher(text)).matches()) changed = GUILD.remove(m.group(1));
        else if ((m = FRIEND_LIST_LINE.matcher(text)).matches()) changed = FRIENDS.add(m.group(1));
        else if (text.contains("●")) {
            // A "/g online" or "/g list" rank line: "[MVP+] Name ●  [VIP] Other ●".
            for (String part : text.split("●|\\R")) {
                Matcher name = GUILD_NAME.matcher(part.trim());
                if (name.matches()) changed |= GUILD.add(name.group(1));
            }
        }
        if (changed) save();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        file = Minecraft.getInstance().gameDirectory.toPath().resolve("config/sky2m/social-names.json");
        try {
            if (!Files.exists(file)) return;
            JsonObject json = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) return;
            if (json.has("friends")) json.getAsJsonArray("friends").forEach(e -> FRIENDS.add(e.getAsString()));
            if (json.has("guild")) json.getAsJsonArray("guild").forEach(e -> GUILD.add(e.getAsString()));
        } catch (Exception ignored) {
            // A broken file starts the lists over.
        }
    }

    private static void save() {
        try {
            JsonObject json = new JsonObject();
            json.add("friends", new Gson().toJsonTree(new ArrayList<>(FRIENDS)));
            json.add("guild", new Gson().toJsonTree(new ArrayList<>(GUILD)));
            Files.createDirectories(file.getParent());
            Files.writeString(file, new Gson().toJson(json), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Kept in memory; saved next time.
        }
    }
}
