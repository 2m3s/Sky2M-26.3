package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player notes: /s2 note <player> <text> ("good M7 tank", "scammer"), shown in chat when that player joins your party
 * or your Party Finder group, or talks in chat (at most once every 10 minutes per player). /s2 note <player> shows
 * one, /s2 note remove <player> deletes it and /s2 notes lists them. Kept in config/sky2m/player-notes.json.
 */
public final class Sky2MPlayerNotes {
    public static final class Note {
        public String name;
        public String text;
        public long at;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SHOW_GAP_MS = 10 * 60_000L;
    private static final int MAX_TEXT = 150;
    /** "Party Finder > Name joined the dungeon group!", "[MVP+] Name joined the party." */
    private static final Pattern JOINED = Pattern.compile("^(?:Party Finder > )?(?:\\[[^\\]]+] ?)?(\\w{1,16}) joined the (?:dungeon group|party)");
    /** A chat message's sender: channel, level, emblem and rank before the name, a symbol after it, then ": ". */
    private static final Pattern SENDER = Pattern.compile(
        "^(?:(?:Party|Guild|Officer|Co-op) > |From |To )?(?:\\[\\d+] )?(?:[^\\w\\s\\[]{1,3} )?(?:\\[[A-Z+ ]+] )?(\\w{3,16})(?: [^\\s:]{1,3})?: ");

    private static Map<String, Note> notes;
    private static final Map<String, Long> SHOWN = new HashMap<>();

    private Sky2MPlayerNotes() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("notes").executes(c -> {
                        list();
                        return 1;
                    }))
                    .then(ClientCommands.literal("note")
                        .then(ClientCommands.literal("remove").then(ClientCommands.argument("player", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                            .executes(c -> {
                                remove(StringArgumentType.getString(c, "player"));
                                return 1;
                            })))
                        .then(ClientCommands.argument("player", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                            .executes(c -> {
                                show(StringArgumentType.getString(c, "player"), true);
                                return 1;
                            })
                            .then(ClientCommands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                                set(StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "text"));
                                return 1;
                            })))));
            }
        });
        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            Matcher joined = JOINED.matcher(text);
            if (joined.find()) {
                show(joined.group(1), false);
                return;
            }
            Matcher sender = SENDER.matcher(text);
            if (sender.find()) show(sender.group(1), false);
        });
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("player-notes.json");
    }

    private static synchronized Map<String, Note> notes() {
        if (notes == null) {
            notes = new LinkedHashMap<>();
            try {
                if (Files.exists(file())) {
                    Map<String, Note> loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Map<String, Note>>() {}.getType());
                    if (loaded != null) loaded.forEach((k, v) -> { if (v != null && v.text != null) notes.put(k.toLowerCase(Locale.ROOT), v); });
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read player-notes.json: " + e.getMessage());
            }
        }
        return notes;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(notes()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save player-notes.json: " + e.getMessage());
        }
    }

    private static synchronized List<String> names() {
        return notes().values().stream().map(n -> n.name).toList();
    }

    /** The note on {@code name}, or null. */
    public static synchronized String note(String name) {
        Note note = name == null ? null : notes().get(name.toLowerCase(Locale.ROOT));
        return note == null ? null : note.text;
    }

    /**
     * Saves {@code text} as the note on {@code name} unless they already have one, without a chat message (Party
     * Finder's Auto-Note Kicks never replaces a note you wrote). Returns whether it was saved.
     */
    public static synchronized boolean addIfAbsent(String name, String text) {
        if (name == null || !name.matches("\\w{1,16}") || notes().containsKey(name.toLowerCase(Locale.ROOT))) return false;
        Note note = new Note();
        note.name = name;
        note.text = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        note.at = System.currentTimeMillis();
        notes().put(name.toLowerCase(Locale.ROOT), note);
        save();
        return true;
    }

    private static synchronized void set(String name, String text) {
        if (!name.matches("\\w{1,16}")) {
            say(Component.literal("That isn't a Minecraft name.").withStyle(ChatFormatting.RED));
            return;
        }
        Note note = new Note();
        note.name = name;
        note.text = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        note.at = System.currentTimeMillis();
        notes().put(name.toLowerCase(Locale.ROOT), note);
        save();
        say(Component.literal("Saved your note on " + name + ". It shows when they join your party or talk in chat.").withStyle(ChatFormatting.GREEN));
    }

    private static synchronized void remove(String name) {
        boolean removed = notes().remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        say(Component.literal(removed ? "Removed your note on " + name + "." : "You have no note on " + name + ".").withStyle(ChatFormatting.GRAY));
    }

    private static void list() {
        Map<String, Note> all;
        synchronized (Sky2MPlayerNotes.class) {
            all = new LinkedHashMap<>(notes());
        }
        if (all.isEmpty()) {
            say(Component.literal("No notes yet: /s2 note <player> <text>").withStyle(ChatFormatting.GRAY));
            return;
        }
        say(Component.literal("Your notes (" + all.size() + "):").withStyle(ChatFormatting.GOLD));
        for (Note note : all.values()) say(line(note.name, note.text));
    }

    /** Shows the note on {@code name}: always when asked, otherwise at most once every 10 minutes. */
    private static void show(String name, boolean asked) {
        String text = note(name);
        if (text == null) {
            if (asked) say(Component.literal("You have no note on " + name + ". Add one: /s2 note " + name + " <text>").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (name.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) return;
        long now = System.currentTimeMillis();
        Long last = SHOWN.get(name.toLowerCase(Locale.ROOT));
        if (!asked && last != null && now - last < SHOW_GAP_MS) return;
        SHOWN.put(name.toLowerCase(Locale.ROOT), now);
        say(line(name, text));
    }

    private static MutableComponent line(String name, String text) {
        return Component.literal("Note on " + name + ": ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" [Edit]").withStyle(s -> s.withColor(ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent.SuggestCommand("/s2 note " + name + " " + text))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Change or remove (/s2 note remove " + name + ")")))));
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(text));
        });
    }
}
