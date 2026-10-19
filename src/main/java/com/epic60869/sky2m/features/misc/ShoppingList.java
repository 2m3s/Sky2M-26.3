package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A shopping list of what you still want to get: /s2 shopping add &lt;item&gt; [amount], /s2 shopping lists everything
 * in chat with a got-one, reset and remove button on each line, /s2 shopping clear empties it. The list is kept in
 * {@code sky2m/shopping.json}; with Auto Pickups on, ItemPickupLog tells the list when something lands in your
 * inventory and its line's got counter goes up by itself, saying so in chat when an entry is done. The HUD shows what's
 * still missing — move it in /s2 gui.
 */
public final class ShoppingList {
    private static final String HUD_ID = "shopping_list";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** An amount at the end of the name: "enchanted hay bale 12", "emeralds ×24", with or without the x/×. */
    private static final Pattern TRAILING_AMOUNT = Pattern.compile("\\s+[x×]?([0-9,]+)\\s*$");

    /** One line of the list: what to get, how many (0 = just "get it"), and how many you already have. */
    public static final class Item {
        public String name = "";
        public long needed;
        public long got;
    }

    private static final List<Item> ITEMS = new ArrayList<>();
    private static Path file;
    private static List<Component> hudLines = List.of();
    private static int ticksSinceHud;

    private ShoppingList() {}

    private static FeatureConfigs.ShoppingList config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.shoppingList;
    }

    private static boolean hudEnabled() {
        FeatureConfigs.ShoppingList c = config();
        return c != null && c.hud && !ITEMS.isEmpty();
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("shopping.json");
        load();
        ItemPickupLog.addPickupListener(ShoppingList::onPickup);
        ClientTickEvents.END_CLIENT_TICK.register(ShoppingList::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("shopping")
                    .executes(c -> {
                        Minecraft.getInstance().execute(ShoppingList::list);
                        return 1;
                    })
                    .then(ClientCommands.literal("add").then(ClientCommands.argument("item", StringArgumentType.greedyString())
                        .executes(c -> {
                            String argument = StringArgumentType.getString(c, "item").trim();
                            Minecraft.getInstance().execute(() -> add(argument));
                            return 1;
                        })))
                    .then(ClientCommands.literal("got")
                        .then(ClientCommands.argument("index", IntegerArgumentType.integer(1, 200))
                            .executes(c -> {
                                int index = IntegerArgumentType.getInteger(c, "index");
                                Minecraft.getInstance().execute(() -> tickOff(index, 1));
                                return 1;
                            })
                            .then(ClientCommands.argument("amount", IntegerArgumentType.integer(1, 1_000_000_000))
                                .executes(c -> {
                                    int index = IntegerArgumentType.getInteger(c, "index");
                                    int amount = IntegerArgumentType.getInteger(c, "amount");
                                    Minecraft.getInstance().execute(() -> tickOff(index, amount));
                                    return 1;
                                }))))
                    .then(ClientCommands.literal("reset")
                        .then(ClientCommands.argument("index", IntegerArgumentType.integer(1, 200))
                            .executes(c -> {
                                int index = IntegerArgumentType.getInteger(c, "index");
                                Minecraft.getInstance().execute(() -> reset(index));
                                return 1;
                            })))
                    .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("index", IntegerArgumentType.integer(1, 200))
                            .executes(c -> {
                                int index = IntegerArgumentType.getInteger(c, "index");
                                Minecraft.getInstance().execute(() -> remove(index));
                                return 1;
                            })))
                    .then(ClientCommands.literal("clear").executes(c -> {
                        Minecraft.getInstance().execute(ShoppingList::clear);
                        return 1;
                    }))));
            }
        });
        Sky2MHuds.register(HUD_ID, "Shopping List", ShoppingList::hudEnabled,
            () -> hudLines,
            List.of(Component.literal("Shopping List").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal("Enchanted Hay Bale ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("8/12").withStyle(ChatFormatting.GOLD))),
            4, 220);
        Sky2MHuds.setSettingsSearch(HUD_ID, "Shopping List");
        Sky2MHuds.setting(HUD_ID, ShoppingList::hudEnabled);
    }

    // ------------------------------------------------------------------------------------------------ the list

    /** /s2 shopping add enchanted hay bale 12: keeps the name you wrote and, if there was one, the amount. */
    private static void add(String argument) {
        String text = argument.trim();
        long needed = 0;
        java.util.regex.Matcher amount = TRAILING_AMOUNT.matcher(text);
        if (amount.find()) {
            try {
                needed = Long.parseLong(amount.group(1).replace(",", ""));
                text = text.substring(0, amount.start()).trim();
            } catch (NumberFormatException ignored) {}
        }
        if (text.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("Give the item to add, like /s2 shopping add enchanted hay bale 12.").withStyle(ChatFormatting.RED));
            return;
        }
        for (Item item : ITEMS) {
            if (normalized(item.name).equals(normalized(text))) {
                item.needed = needed;
                Sky2MAlerts.chat(Component.literal("Updated the amount of ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(item.name).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" in your shopping list.").withStyle(ChatFormatting.YELLOW)));
                save();
                list();
                return;
            }
        }
        Item item = new Item();
        item.name = text;
        item.needed = needed;
        item.got = 0;
        ITEMS.add(item);
        save();
        list();
    }

    /** An item line in chat (buttons included when it's the chat list, not the HUD): 3. Emeralds 4/12 [✓] [✗] [×]. */
    private static Component item(int index, boolean hud) {
        Item item = ITEMS.get(index);
        boolean done = done(item);
        MutableComponent line = Component.literal((index + 1) + ". ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(item.name).withStyle(done ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        if (item.needed > 0) {
            line.append(Component.literal(" " + item.got + "/" + item.needed).withStyle(done ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        } else if (done) {
            line.append(Component.literal(" done!").withStyle(ChatFormatting.GREEN));
        }
        if (!hud) {
            line.append(button(" [✓]", "s2 shopping got " + (index + 1), "Adds one to the counter", ChatFormatting.GREEN)
                .append(button(" [✗]", "s2 shopping reset " + (index + 1), "Resets how many you already have", ChatFormatting.YELLOW))
                .append(button(" [×]", "s2 shopping remove " + (index + 1), "Removes this from the list", ChatFormatting.RED)));
        }
        return line;
    }

    private static MutableComponent button(String text, String command, String hover, ChatFormatting colour) {
        return Component.literal(text).withStyle(style -> style.withColor(colour).withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static boolean done(Item item) {
        return item.needed > 0 ? item.got >= item.needed : item.got > 0;
    }

    /** One of that entry landed in your inventory (or /s2 shopping got ran): add it to the counter, cap at the amount. */
    private static void tickOff(int index, long amount) {
        if (index < 1 || index > ITEMS.size()) return;
        Item item = ITEMS.get(index - 1);
        item.got = item.needed == 0 ? item.got + amount : Math.min(item.needed, item.got + amount);
        save();
        rebuildHud();
        if (done(item)) {
            Sky2MAlerts.chat(Component.literal("Shopping List: ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(item.name).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" done!").withStyle(ChatFormatting.GREEN)));
        }
    }

    private static void reset(int index) {
        if (index < 1 || index > ITEMS.size()) return;
        ITEMS.get(index - 1).got = 0;
        save();
        rebuildHud();
        list();
    }

    private static void remove(int index) {
        if (index < 1 || index > ITEMS.size()) return;
        Item removed = ITEMS.remove(index - 1);
        save();
        rebuildHud();
        Sky2MAlerts.chat(Component.literal("Removed ").withStyle(ChatFormatting.YELLOW).append(Component.literal(removed.name).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" from your shopping list.").withStyle(ChatFormatting.YELLOW)));
    }

    private static void clear() {
        ITEMS.clear();
        save();
        rebuildHud();
        Sky2MAlerts.chat(Component.literal("Your shopping list is empty.").withStyle(ChatFormatting.YELLOW));
    }

    /** When ItemPickupLog sees an item come in, match it to the list by name (your entry may be the end of the name). */
    private static void onPickup(String key, String priceId, String name, long delta) {
        FeatureConfigs.ShoppingList c = config();
        if (c == null || !c.autoPickups || key.equals("coins") || delta <= 0) return;
        String giving = normalized(name);
        if (giving.isEmpty()) return;
        for (int i = 0; i < ITEMS.size(); i++) {
            String wanted = normalized(ITEMS.get(i).name);
            if (wanted.equals(giving) || (giving.endsWith(wanted) && wanted.length() >= 6)) {
                int entry = i;
                Minecraft.getInstance().execute(() -> tickOff(entry + 1, delta));
                return;
            }
        }
    }

    /** Just letters and numbers, lower case, colour codes gone: how entries match item names fuzzy. */
    private static String normalized(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '§') i++; else if (Character.isLetterOrDigit(ch)) out.append(Character.toLowerCase(ch));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ HUD & chat

    private static void tick(Minecraft mc) {
        if (!hudEnabled() || ++ticksSinceHud % 10 != 0) return;
        rebuildHud();
    }

    private static void rebuildHud() {
        if (!hudEnabled()) {
            hudLines = List.of();
            return;
        }
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Shopping List").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        int pending = 0;
        for (Item item : ITEMS) {
            if (!done(item)) pending++;
        }
        out.add(Component.literal(pending + " left, " + (ITEMS.size() - pending) + " done").withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < ITEMS.size(); i++) {
            if (done(ITEMS.get(i))) continue;
            out.add(item(i, true));
        }
        if (pending == 0) for (int i = 0; i < ITEMS.size(); i++) out.add(item(i, true));
        hudLines = out;
    }

    /** The whole list in chat, with the buttons to tick items off and remove them. */
    private static void list() {
        if (ITEMS.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("Your shopping list is empty. Add something with ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("/s2 shopping add <item>").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.SuggestCommand("/s2 shopping add "))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Type what you want to buy"))))));
            return;
        }
        Sky2MAlerts.chat(Component.literal("Shopping List (" + ITEMS.size() + "):").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        for (int i = 0; i < ITEMS.size(); i++) Sky2MAlerts.chat(item(i, false));
        Sky2MAlerts.chat(Component.literal("[Empty the List]").withStyle(style -> style.withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent.RunCommand("/s2 shopping clear"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Removes every entry")))));
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static void load() {
        try {
            List<Item> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Item>>() {}.getType());
            if (read != null) ITEMS.addAll(read);
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(ITEMS), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
