package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.features.core.RepoPattern;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 rnglist: every RNG drop ({@link RngDropList}), crossed off by itself when you get one, or ticked by hand in
 * {@link RngListScreen}. Saved to config/sky2m/rng-list.json (moved from the Sky2M Personal add-on's rng-drops.json).
 * <p>
 * What counts as getting a drop:
 * <ul>
 *   <li>a server (not player) chat line that starts with a drop word: "RARE DROP!", "VERY RARE DROP!", "CRAZY RARE
 *   DROP!", "INSANE DROP!", "PRAY TO RNGESUS DROP!", "RNGESUS INCARNATE DROP!", "PET DROP!", "RARE CROP!", "PEST
 *   LOOT!", "... CATCH!", "RNG METER!" and so on, or "You dug out ..." (Diana);</li>
 *   <li>"RARE REWARD! You found a ... in your ... Chest!" (or with your own name);</li>
 *   <li>buying a dungeon or Kuudra reward chest ("Open Reward Chest") that holds the drop, if Hypixel doesn't refuse.</li>
 * </ul>
 */
public final class RngTracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** How a hand-ticked drop is marked. */
    static final String BY_HAND = "Ticked by hand";
    /** The save file; the game test points it somewhere else. */
    static Path file;

    /** A drop line: optional spaces, then Hypixel's capitals ending in "!" with a drop word in them. */
    private static final RepoPattern DROP_LINE = RepoPattern.of("tracker.RngTracker.DROP_LINE", "^\\s*(?<tag>[A-Z][A-Z ]*?(?:DROP|CROP|LOOT|CATCH|RNGESUS|RNG METER|REWARD)[A-Z ]*!+)\\s*(?<rest>.+)$");
    private static final RepoPattern DUG_OUT = RepoPattern.of("tracker.RngTracker.DUG_OUT", "^\\s*(?:Wow! )?You dug out (?:an? )?(?<rest>.+)$");
    private static final RepoPattern RARE_REWARD = RepoPattern.of("tracker.RngTracker.RARE_REWARD", "^\\s*RARE REWARD! (?<who>\\S+) found (?:an? )?(?<item>.+?) in (?:their|your) .+? Chest!?$");
    /**
     * A drop line about someone else's drop: "RNG DROP! [MVP+] Koehy just found a ..." (a party member's drop, shared by
     * Hypixel or another mod). The rank is optional; the name is a Minecraft name.
     */
    private static final RepoPattern FINDER = RepoPattern.of("tracker.RngTracker.FINDER", "^(?:\\[[^\\]]+]\\s*)?(?<who>[A-Za-z0-9_]{1,16}) (?:just |has )?(?:found|got|obtained|dropped|pulled|caught|dug out)\\b(?<rest>.*)$");
    private static final RepoPattern MAGIC_FIND = RepoPattern.of("tracker.RngTracker.MAGIC_FIND", "\\s*\\(\\+[^)]*\\)\\s*$");
    private static final RepoPattern QUANTITY = RepoPattern.of("tracker.RngTracker.QUANTITY", "^(?:x\\d+\\s+|\\d+x\\s+)|\\s+x\\d+$");
    private static final RepoPattern PET_LEVEL = RepoPattern.of("tracker.RngTracker.PET_LEVEL", "^\\[Lvl \\d+]\\s*");
    private static final RepoPattern BOOK = RepoPattern.of("tracker.RngTracker.BOOK", "^Enchanted Book \\((.+)\\)$");
    /** Reward chests: dungeon chests at the end of a run or at Croesus, and Kuudra's. */
    private static final RepoPattern CHEST_TITLE = RepoPattern.of("tracker.RngTracker.CHEST_TITLE", "^(?:Wood|Gold|Diamond|Emerald|Obsidian|Bedrock|Paid|Free)(?: Chest)?$");
    static final int CONFIRM_TICKS = 30;

    /** Save file contents. */
    private static final class Save {
        /** Item -> when and how it was first got. */
        Map<String, Got> got = new LinkedHashMap<>();
    }

    public record Got(long time, String how) {}

    /** Lower-case name -> the list's spelling. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    /** The list's names, longest first, for finding one inside a longer line. */
    private static final List<String> BY_LENGTH = new ArrayList<>();

    private static Map<String, Got> got;

    /** Items from a bought chest, waiting for Hypixel not to refuse it. */
    private static List<String> pending;
    private static int pendingTicks;
    private static int lastChestMenu = -1;
    private static boolean openScreen;

    private RngTracker() {}

    static boolean on() {
        var c = TrackLines.config();
        return c != null && c.rngList;
    }

    /** For Sky2M's game test: /s2 rnglist test's failures, empty when every drop message and chest counted right. */
    public static List<String> selfTestFailures() throws Exception {
        return RngSelfTest.failuresNow();
    }

    public static void init() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        file = TrackLines.movedFrom(dir.resolve("sky2m").resolve("rng-list.json"), dir.resolve("sky2m-personal").resolve("rng-drops.json"));
        loadNames();
        // GAME is server messages only: anything a player types arrives as CHAT, so it can't fake a drop.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && on()) onMessage(message.getString());
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            String title = strip(screen.getTitle().getString()).trim();
            if (!CHEST_TITLE.matcher(title).matches()) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (on()) onChestClick(container);
                return true;
            });
        });
        // /s2 rnglist opens the list (a tick later, after the chat screen closes); /s2 rnglist test checks it.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("rnglist").executes(ctx -> {
                    if (!on()) {
                        ctx.getSource().sendFeedback(Component.literal("§cTurn on RNG Drop List" + DungeonProfitHour.TURN_ON));
                        return 0;
                    }
                    openScreen = true;
                    return 1;
                }).then(ClientCommands.literal("test").executes(ctx -> {
                    RngSelfTest.chat(ctx.getSource());
                    return 1;
                }))));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!openScreen) return;
            openScreen = false;
            mc.gui.setScreen(new RngListScreen(null));
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    }

    static void loadNames() {
        if (!NAMES.isEmpty()) return;
        for (RngDropList.Category c : RngDropList.ALL) for (String item : c.items()) NAMES.putIfAbsent(item.toLowerCase(Locale.ROOT), item);
        BY_LENGTH.addAll(NAMES.values());
        BY_LENGTH.sort((a, b) -> b.length() - a.length());
    }

    // ------------------------------------------------------------------------------------------------ detection

    static void onMessage(String raw) {
        String text = strip(raw).trim();
        if (text.isEmpty()) return;
        String lower = text.toLowerCase(Locale.ROOT);

        // A refused chest purchase (no coins, no key, already opened).
        if (pending != null && !text.contains(":") && (lower.contains("don't have enough") || lower.contains("do not have enough")
            || lower.contains("you need") || lower.contains("you cannot") || lower.contains("you can't") || lower.contains("already"))) {
            pending = null;
        }

        Matcher reward = RARE_REWARD.matcher(text);
        if (reward.matches()) {
            String who = reward.group("who");
            if (who.equalsIgnoreCase("You") || who.equalsIgnoreCase(selfName.get())) {
                String item = find(reward.group("item"));
                if (item != null) mark(item, "RARE REWARD");
            }
            return;
        }
        Matcher drop = DROP_LINE.matcher(text);
        if (drop.matches()) {
            String rest = drop.group("rest");
            Matcher finder = FINDER.matcher(rest.trim());
            if (finder.matches()) {
                // Another player's drop is not yours; your own name, or "You" ("RARE DROP! You dug out a Minos Relic!",
                // "OUTSTANDING CATCH! You caught a Tiki Mask!"), still counts.
                String who = finder.group("who");
                if (!who.equalsIgnoreCase("You") && !who.equalsIgnoreCase(selfName.get())) return;
                rest = finder.group("rest");
            }
            String item = find(rest);
            if (item != null) mark(item, drop.group("tag").replaceAll("!+$", ""));
            return;
        }
        Matcher dug = DUG_OUT.matcher(text);
        if (dug.matches()) {
            String item = find(dug.group("rest"));
            if (item != null) mark(item, "Dug out");
        }
    }

    private static void onChestClick(AbstractContainerScreen<?> screen) {
        Slot clicked = ((AbstractContainerScreenAccessor) screen).getFocusedSlot();
        if (clicked == null || clicked.container instanceof Inventory) return;
        ItemStack button = clicked.getItem();
        if (button.isEmpty() || !strip(button.getHoverName().getString()).trim().equals("Open Reward Chest")) return;
        List<String[]> stacks = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String firstLore = "";
            for (String line : TrackLines.lore(stack)) {
                if (line.isBlank()) continue;
                firstLore = line.trim();
                break;
            }
            stacks.add(new String[]{strip(stack.getHoverName().getString()).trim(), firstLore});
        }
        chestBought(screen.getMenu().containerId, stacks);
    }

    /**
     * "Open Reward Chest" clicked in chest menu {@code menu} holding {@code stacks} (each its name and first lore line):
     * the listed drops in it wait {@link #CONFIRM_TICKS} for Hypixel to refuse, then cross off.
     */
    static void chestBought(int menu, List<String[]> stacks) {
        if (pending != null || menu == lastChestMenu) return;
        List<String> items = new ArrayList<>();
        for (String[] stack : stacks) {
            String item = find(stack[0]);
            // Books show as "Enchanted Book" with the enchantment on the first lore line.
            if (item == null && stack[0].equals("Enchanted Book") && !stack[1].isEmpty()) item = find(stack[1]);
            if (item != null && !items.contains(item)) items.add(item);
        }
        lastChestMenu = menu;
        if (items.isEmpty()) return;
        pending = items;
        pendingTicks = CONFIRM_TICKS;
    }

    static void tick() {
        if (pending != null && --pendingTicks <= 0) {
            for (String item : pending) mark(item, "Reward chest");
            pending = null;
        }
    }

    /** The list's name for the drop in this text, or null. Exact name first, then the longest name found as whole words. */
    static String find(String text) {
        String t = MAGIC_FIND.matcher(text.trim()).replaceAll("").trim();
        t = t.replaceAll("[!.]+$", "").trim();
        if (t.startsWith("(") && t.endsWith(")")) t = t.substring(1, t.length() - 1).trim();
        Matcher book = BOOK.matcher(t);
        if (book.matches()) t = book.group(1).trim();
        t = PET_LEVEL.matcher(t).replaceFirst("");
        t = QUANTITY.matcher(t).replaceAll("").trim();
        String exact = NAMES.get(t.toLowerCase(Locale.ROOT));
        if (exact != null) return exact;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : BY_LENGTH) {
            // Whole words, and not "Smite VI" inside "Smite VII".
            Pattern p = Pattern.compile("(?<![\\w'])" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "(?![\\w'])");
            if (p.matcher(lower).find()) return name;
        }
        return null;
    }

    /** Ticks or unticks a drop from the screen. */
    public static void toggle(String item) {
        Map<String, Got> all = got();
        if (all.remove(item) == null) all.put(item, new Got(System.currentTimeMillis(), BY_HAND));
        save();
    }

    static void mark(String item, String how) {
        Map<String, Got> all = got();
        if (all.containsKey(item)) return;
        all.put(item, new Got(System.currentTimeMillis(), how));
        save();
        notify.accept(item);
    }

    /** Says in chat that a drop was crossed off; the game test swaps it out. */
    static java.util.function.Consumer<String> notify = RngTracker::chatNotify;

    private static void chatNotify(String item) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        player.sendSystemMessage(Component.literal("[S2P] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("RNG crossed off: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(item).withStyle(ChatFormatting.GREEN, ChatFormatting.STRIKETHROUGH))
            .append(Component.literal(" (" + got().size() + "/" + NAMES.size() + ")").withStyle(ChatFormatting.DARK_GRAY)));
    }

    /** Your Minecraft name, for "RARE REWARD! <name> found ..."; the game test swaps it out. */
    static java.util.function.Supplier<String> selfName = () -> {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name();
    };

    private static String strip(String s) {
        return TrackLines.strip(s);
    }

    // ------------------------------------------------------------------------------------------------ data

    /** Read-only view for the screen. */
    public static Map<String, Got> obtained() {
        return java.util.Collections.unmodifiableMap(got());
    }

    /** For the game test: forget everything (and the loaded file). */
    static void resetForTest(Path testFile) {
        file = testFile;
        got = null;
        pending = null;
        lastChestMenu = -1;
        loadNames();
    }

    public static int total() {
        return NAMES.size();
    }

    private static Map<String, Got> got() {
        if (got != null) return got;
        got = new LinkedHashMap<>();
        try {
            if (file != null && Files.exists(file)) {
                Save save = GSON.fromJson(Files.readString(file), Save.class);
                if (save != null && save.got != null) got.putAll(save.got);
            }
        } catch (IOException | RuntimeException e) {
            TrackLines.LOG.warn("Couldn't read {}: {}", file, e.toString());
        }
        return got;
    }

    private static void save() {
        Save save = new Save();
        save.got = got();
        try {
            if (file == null) return;
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(save));
        } catch (IOException e) {
            TrackLines.LOG.warn("Couldn't save {}: {}", file, e.toString());
        }
    }
}
