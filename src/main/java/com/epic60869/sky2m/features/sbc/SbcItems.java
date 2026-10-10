package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MGlobalChat;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Items as the mod server sends them ({@code {id, name, lore[], count, rarity, skullTexture?}} with § codes): turning
 * your item into one to share in Sky2M chat, and turning a received one back into an ItemStack, a tooltip or a
 * hoverable "[Item Name]" in chat.
 */
public final class SbcItems {
    private static final String[] RARITIES = {"VERY SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE",
        "UNCOMMON", "COMMON", "ULTIMATE", "ADMIN"};
    private static final int MAX_LORE = 40;
    private static final int MAX_LINE = 200;
    private static final int MAX_NAME = 100;

    /** Items shared in chat recently, so clicking one can show it. */
    private static final Map<Integer, JsonObject> SHARED = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, JsonObject> eldest) {
            return size() > 100;
        }
    };
    private static int nextShared = 1;
    /** Ids may hold ';' (pets "GOLDEN_DRAGON;4", books "ENCHANTMENT_ULTIMATE_WISE;5", runes). */
    private static final Pattern PUBLIC_ITEM = Pattern.compile("\\[\\[SBITEM\\|([A-Za-z0-9_:;.\\-]+)\\|(\\d{1,2})(?:\\|([A-Za-z0-9_-]+))?]]"
        + "|\\[([^\\[\\]()]{1,60})]\\(sb:([A-Za-z0-9_:;.\\-]+)(?:\\*(\\d{1,2}))?\\)");
    private static final Pattern ITEM_TOKEN = Pattern.compile("(?i)\\[item]");
    /** [inv] shares your whole inventory; [brag] still works the same way. */
    private static final Pattern INV_TOKEN = Pattern.compile("(?i)\\[(?:inv|brag)]");
    /** A shared inventory, or part of one: {@code [[SBINV|key|part/parts|data]]}. */
    private static final Pattern INV_MARKER = Pattern.compile("\\[\\[SBINV\\|([a-z0-9]{1,8})\\|(\\d{1,2})/(\\d{1,2})\\|([A-Za-z0-9_-]*)]]");
    /** Data per public chat message: Hypixel allows 256 characters, a "/msg SomeLongName " command included. */
    private static final int INV_PART_LENGTH = 170;
    private static final long FOLLOWUP_DELAY_MS = 350L;
    /** Chat commands whose text can hold [item] and [inv]; the ones that name a player first take one more word. */
    private static final Set<String> CHAT_COMMANDS = Set.of("pc", "pchat", "ac", "achat", "gc", "gchat", "oc", "ochat",
        "cc", "cchat", "shout", "r", "reply");
    private static final Set<String> PLAYER_CHAT_COMMANDS = Set.of("msg", "w", "tell", "whisper", "message", "t");

    private record Followup(String message, boolean sky2M, boolean command) {}

    /** Inventories shared in chat, as their parts arrive. */
    private static final Map<String, SharedInventory> INVENTORIES = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SharedInventory> eldest) {
            return size() > 50;
        }
    };

    private static final class SharedInventory {
        final String[] parts;
        String owner = "";

        SharedInventory(int count) {
            parts = new String[count];
        }

        boolean complete() {
            for (String part : parts) if (part == null) return false;
            return true;
        }
    }

    private static final Queue<Followup> FOLLOWUPS = new ArrayDeque<>();
    private static long nextFollowupAt;

    private SbcItems() {}

    public static void init() {
        ClientSendMessageEvents.MODIFY_CHAT.register(SbcItems::modifyOutgoingChat);
        ClientSendMessageEvents.MODIFY_COMMAND.register(SbcItems::modifyOutgoingCommand);
        ClientTickEvents.END_CLIENT_TICK.register(SbcItems::sendFollowup);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FOLLOWUPS.clear());
    }

    private static boolean sharingEnabled() {
        return Sbc.config().chat.itemSharing && Flags.isEnabled("chat.items");
    }

    private static String modifyOutgoingChat(String message) {
        if (!sharingEnabled()) return message;
        List<String> expanded = expandInventoryText(message, INV_PART_LENGTH);
        List<String> prepared = expanded.stream().map(SbcItems::replaceItemTokens).toList();
        enqueue(prepared.subList(1, prepared.size()), false, false);
        return prepared.getFirst();
    }

    /**
     * [item] and [inv] in party, guild, all, co-op and private messages sent as commands (/pc, /gc, /msg Name, ...),
     * not just in plain chat.
     */
    private static String modifyOutgoingCommand(String command) {
        if (!sharingEnabled()) return command;
        if (!command.toLowerCase(Locale.ROOT).contains("[item]") && !INV_TOKEN.matcher(command).find()) return command;
        String[] words = command.split(" ", 3);
        String name = words[0].toLowerCase(Locale.ROOT);
        int prefixWords = CHAT_COMMANDS.contains(name) ? 1 : PLAYER_CHAT_COMMANDS.contains(name) ? 2 : 0;
        if (prefixWords == 0 || words.length <= prefixWords) return command;
        String prefix = String.join(" ", java.util.Arrays.copyOf(words, prefixWords));
        String text = command.substring(prefix.length()).trim();
        List<String> prepared = expandInventoryText(text, INV_PART_LENGTH).stream().map(SbcItems::replaceItemTokens).toList();
        enqueue(prepared.subList(1, prepared.size()).stream().map(part -> prefix + " " + part).toList(), false, true);
        return prefix + " " + prepared.getFirst();
    }

    /** Expands [inv] for the Sky2M channel (one message holds it all); [item] remains for its richer attached-item packet. */
    public static List<String> expandBrag(String message) {
        return sharingEnabled() ? expandInventoryText(message, Integer.MAX_VALUE) : List.of(message);
    }

    public static void enqueueSky2MFollowups(List<String> messages) {
        enqueue(messages, true, false);
    }

    private static void enqueue(List<String> messages, boolean sky2M, boolean command) {
        for (String message : messages) FOLLOWUPS.add(new Followup(message, sky2M, command));
    }

    private static void sendFollowup(Minecraft mc) {
        if (FOLLOWUPS.isEmpty() || mc.player == null || System.currentTimeMillis() < nextFollowupAt) return;
        Followup followup = FOLLOWUPS.poll();
        if (followup == null) return;
        nextFollowupAt = System.currentTimeMillis() + FOLLOWUP_DELAY_MS;
        if (followup.sky2M()) {
            JsonObject packet = SbcChat.outgoing(followup.message());
            if (packet != null) Sky2MGlobalChat.sendPacket(packet);
        } else if (followup.command()) {
            mc.player.connection.sendCommand(followup.message());
        } else {
            mc.player.connection.sendChat(followup.message());
        }
    }

    private static String replaceItemTokens(String message) {
        ItemStack stack = heldOrHovered();
        String marker = publicMarker(stack, true);
        if (marker == null) return message;
        Matcher matcher = ITEM_TOKEN.matcher(message);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            out.append(message, cursor, matcher.start()).append(marker);
            cursor = matcher.end();
        }
        return cursor == 0 ? message : out.append(message, cursor, message.length()).toString();
    }

    /**
     * [inv]: your inventory, hotbar and armour as one compressed marker. Hypixel's chat can't hold it in one message,
     * so the rest goes in follow-up messages that Sky2M hides for everyone; it shows as one clickable [Inventory].
     */
    private static List<String> expandInventoryText(String message, int partLength) {
        Matcher token = INV_TOKEN.matcher(message);
        if (!token.find()) return List.of(message);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return List.of(message);
        String data = encodeInventory(mc.player.getInventory());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < data.length(); i += partLength) parts.add(data.substring(i, Math.min(data.length(), i + partLength)));
        if (parts.isEmpty()) parts.add("");
        String key = Long.toString(Math.floorMod(System.nanoTime() ^ mc.player.getUUID().getLeastSignificantBits(), 2_176_782_336L), 36);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) lines.add("[[SBINV|" + key + "|" + (i + 1) + "/" + parts.size() + "|" + parts.get(i) + "]]");
        String prefix = INV_TOKEN.matcher(message.substring(0, token.start())).replaceAll("").trim();
        String suffix = INV_TOKEN.matcher(message.substring(token.end())).replaceAll("").trim();
        lines.set(0, (prefix.isEmpty() ? "" : prefix + " ") + lines.getFirst() + (suffix.isEmpty() ? "" : " " + suffix));
        return lines;
    }

    /** Slots 0-35 then the armour (boots to helmet): "ID" or "ID*count", comma separated, deflated, base64. */
    private static String encodeInventory(net.minecraft.world.entity.player.Inventory inventory) {
        List<String> entries = new ArrayList<>();
        for (int slot = 0; slot < 40; slot++) {
            ItemStack stack = inventory.getItem(slot);
            entries.add(stack.isEmpty() ? "" : itemId(stack) + (stack.getCount() > 1 ? "*" + stack.getCount() : ""));
        }
        while (!entries.isEmpty() && entries.getLast().isEmpty()) entries.removeLast();
        byte[] raw = String.join(",", entries).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)) {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** The 40 slots of a shared inventory (empty strings for empty slots), or null if it can't be read. */
    private static List<String> decodeInventory(String data) {
        try (java.util.zip.Inflater inflater = new java.util.zip.Inflater(true)) {
            inflater.setInput(Base64.getUrlDecoder().decode(data));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!inflater.finished() && out.size() <= 16_384) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buffer, 0, n);
            }
            List<String> slots = new ArrayList<>(List.of(out.toString(java.nio.charset.StandardCharsets.UTF_8).split(",", -1)));
            while (slots.size() < 40) slots.add("");
            return slots.subList(0, 40);
        } catch (Exception e) {
            return null;
        }
    }

    private static String itemId(ItemStack stack) {
        String id = Compat.neuName(stack);
        return id.isBlank() ? stack.getItem().toString().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "") : id;
    }

    /** Remembers the inventory parts in a chat line; true if it holds only follow-up parts (hidden from chat). */
    public static boolean recordInventoryParts(Component message) {
        String text = message.getString();
        if (!text.contains("[[SBINV|")) return false;
        Matcher matcher = INV_MARKER.matcher(text);
        boolean any = false, onlyFollowups = true;
        while (matcher.find()) {
            any = true;
            int part = Integer.parseInt(matcher.group(2)), count = Integer.parseInt(matcher.group(3));
            if (count < 1 || part < 1 || part > count) continue;
            synchronized (INVENTORIES) {
                SharedInventory inventory = INVENTORIES.get(matcher.group(1));
                if (inventory == null || inventory.parts.length != count) {
                    inventory = new SharedInventory(count);
                    INVENTORIES.put(matcher.group(1), inventory);
                }
                inventory.parts[part - 1] = matcher.group(4);
                if (part == 1) inventory.owner = sender(text.substring(0, matcher.start()));
            }
            if (part == 1) onlyFollowups = false;
        }
        return any && onlyFollowups && sharingEnabled();
    }

    /** "Party > [MVP+] Name: " -> "Name". */
    private static String sender(String before) {
        int colon = before.lastIndexOf(':');
        if (colon < 0) return "";
        String[] words = before.substring(0, colon).trim().split(" ");
        return words.length == 0 ? "" : words[words.length - 1].replaceAll("[^A-Za-z0-9_]", "");
    }

    /** The clickable "[Inventory]" a shared inventory shows as. */
    private static MutableComponent inventoryComponent(String key) {
        String owner;
        synchronized (INVENTORIES) {
            SharedInventory inventory = INVENTORIES.get(key);
            owner = inventory == null ? "" : inventory.owner;
        }
        String label = owner.isEmpty() ? "[Inventory]" : "[" + owner + "'s Inventory]";
        return Component.literal(label).withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to see the inventory").withStyle(ChatFormatting.GRAY)))
            .withClickEvent(new ClickEvent.RunCommand("/s2 viewinv " + key)));
    }

    /** Opens a shared inventory: its 40 slots as items, or an error if it's incomplete or unknown. */
    static void viewInventory(String key) {
        SharedInventory inventory;
        synchronized (INVENTORIES) {
            inventory = INVENTORIES.get(key);
        }
        if (inventory == null) {
            Sbc.error("That inventory is too old to show.");
            return;
        }
        if (!inventory.complete()) {
            Sbc.error("That inventory hasn't fully arrived yet. Try again in a moment.");
            return;
        }
        List<String> slots = decodeInventory(String.join("", inventory.parts));
        if (slots == null) {
            Sbc.error("Couldn't read that inventory.");
            return;
        }
        List<JsonObject> items = new ArrayList<>();
        for (String slot : slots) {
            if (slot.isBlank()) {
                items.add(null);
                continue;
            }
            int star = slot.lastIndexOf('*');
            String id = star > 0 ? slot.substring(0, star) : slot;
            int count = 1;
            if (star > 0) {
                try {
                    count = Integer.parseInt(slot.substring(star + 1));
                } catch (NumberFormatException ignored) {}
            }
            items.add(publicItem(id, count, null));
        }
        Compat.queueOpenScreen(new SbcInventoryScreen(inventory.owner, items));
    }

    /**
     * "[Heroic Hyperion](sb:HYPERION)" ("(sb:ENCHANTED_DIAMOND*12)" for a stack): readable for players without
     * Sky2M, and a hoverable item for players with it.
     */
    private static String publicMarker(ItemStack stack, boolean includeName) {
        if (stack == null || stack.isEmpty()) return null;
        String id = Compat.neuName(stack);
        if (id.isBlank()) id = stack.getItem().toString().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        String name = includeName ? Compat.realName(stack).getString().replaceAll("[\\[\\]()]", "").trim() : "";
        if (name.isEmpty() || name.length() > 60) name = id.replace('_', ' ');
        int count = Math.clamp(stack.getCount(), 1, 99);
        return "[" + name + "](sb:" + id + (count > 1 ? "*" + count : "") + ")";
    }

    /** Replaces compact public-chat item references with the same clickable preview used by Sky2M chat. */
    public static Component decoratePublicItems(Component message) {
        if (!sharingEnabled()) return message;
        if (message.getString().contains("[[SBINV|")) {
            // A line with only follow-up parts is left as it is, for Sky2MChatHudMixin to see and hide.
            if (recordInventoryParts(message)) return message;
            message = replaceInventoryMarkers(message);
        }
        String plain = message.getString();
        if (!plain.contains("[[SBITEM|") && !plain.contains("](sb:")) return message;
        MutableComponent result = Component.empty();
        boolean[] replaced = {false};
        message.visit((style, value) -> {
            Matcher matcher = PUBLIC_ITEM.matcher(value);
            int cursor = 0;
            while (matcher.find()) {
                if (matcher.start() > cursor) result.append(Component.literal(value.substring(cursor, matcher.start())).withStyle(style));
                JsonObject item = matcher.group(4) != null
                    ? publicItem(matcher.group(5), matcher.group(6) == null ? 1 : Integer.parseInt(matcher.group(6)), null, matcher.group(4))
                    : publicItem(matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.group(3));
                result.append(chatComponent(item));
                cursor = matcher.end();
                replaced[0] = true;
            }
            if (cursor < value.length()) result.append(Component.literal(value.substring(cursor)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return replaced[0] ? result : message;
    }

    /** The first part of a shared inventory becomes a clickable [Inventory]; follow-up parts disappear. */
    private static Component replaceInventoryMarkers(Component message) {
        MutableComponent result = Component.empty();
        message.visit((style, value) -> {
            Matcher matcher = INV_MARKER.matcher(value);
            int cursor = 0;
            while (matcher.find()) {
                if (matcher.start() > cursor) result.append(Component.literal(value.substring(cursor, matcher.start())).withStyle(style));
                if (matcher.group(2).equals("1")) result.append(inventoryComponent(matcher.group(1)));
                cursor = matcher.end();
            }
            if (cursor < value.length()) result.append(Component.literal(value.substring(cursor)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    private static JsonObject publicItem(String id, int count, String encodedName) {
        return publicItem(id, count, encodedName, null);
    }

    /** {@code encodedName}: the old markers' base64 name; {@code plainName}: the new ones' readable name. */
    private static JsonObject publicItem(String id, int count, String encodedName, String plainName) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("count", Math.clamp(count, 1, 99));
        String name = plainName;
        if (encodedName != null) {
            try {
                name = new String(Base64.getUrlDecoder().decode(encodedName), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {}
        }
        ItemStack template = RepoItems.itemStack(id);
        // A plain name is shown in the item's rarity colour.
        if (name != null && !name.isBlank() && !name.contains("§") && RepoItems.tier(id) != null) {
            name = RepoItems.tierColour(RepoItems.tier(id)) + name;
        }
        if (name == null || name.isBlank()) {
            String repoName = RepoItems.displayName(id);
            if (repoName != null) name = ChatFormatting.stripFormatting(repoName);
            else if (!template.is(Items.BARRIER)) name = Compat.realName(template).getString();
            else name = id.replace('_', ' ');
        }
        item.addProperty("name", name);
        if (!template.isEmpty()) {
            JsonArray lore = new JsonArray();
            for (Component line : template.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) {
                if (lore.size() >= MAX_LORE) break;
                lore.add(limit(legacy(line), MAX_LINE));
            }
            if (!lore.isEmpty()) item.add("lore", lore);
        }
        return item;
    }

    // ------------------------------------------------------------------------------------------------ outgoing

    /** The item in the slot under the mouse in an open container, or the one in your hand; empty if neither. */
    public static ItemStack heldOrHovered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            Slot slot = ((Sky2MContainerScreenAccessor) screen).sky2m$getHoveredSlot();
            if (slot != null && slot.hasItem()) return slot.getItem();
        }
        return mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
    }

    /** The item as the server wants it, or null for an empty stack. */
    public static JsonObject toJson(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        JsonObject item = new JsonObject();
        String id = Compat.neuName(stack);
        item.addProperty("id", id.isEmpty() ? stack.getItem().toString().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "") : id);
        item.addProperty("name", limit(legacy(Compat.realName(stack)), MAX_NAME));
        JsonArray lore = new JsonArray();
        ItemLore itemLore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        for (Component line : itemLore.lines()) {
            if (lore.size() >= MAX_LORE) break;
            lore.add(limit(legacy(line), MAX_LINE));
        }
        item.add("lore", lore);
        item.addProperty("count", stack.getCount());
        String rarity = rarity(itemLore.lines());
        if (!rarity.isEmpty()) item.addProperty("rarity", rarity);
        String texture = Compat.getHeadTexture(stack);
        if (!texture.isEmpty() && texture.length() < 2000) item.addProperty("skullTexture", texture);
        return item;
    }

    /** "LEGENDARY" from the last lore line that names a rarity ("§6§lLEGENDARY SWORD"). */
    private static String rarity(List<Component> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            String text = ChatFormatting.stripFormatting(lore.get(i).getString());
            if (text == null) continue;
            text = text.trim().replaceFirst("^a ", "").toUpperCase(Locale.ROOT);
            for (String rarity : RARITIES) {
                if (text.startsWith(rarity)) return rarity.replace(' ', '_');
            }
        }
        return "";
    }

    private static String limit(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** A component as a string with § codes, like Hypixel item names and lore. */
    public static String legacy(Component component) {
        StringBuilder out = new StringBuilder();
        Style[] last = {null};
        component.visit((style, text) -> {
            if (text.isEmpty()) return Optional.empty();
            if (!style.equals(last[0])) {
                if (last[0] != null) out.append("§r");
                ChatFormatting colour = nearest(style.getColor());
                if (colour != null) out.append(colour);
                if (style.isObfuscated()) out.append("§k");
                if (style.isBold()) out.append("§l");
                if (style.isStrikethrough()) out.append("§m");
                if (style.isUnderlined()) out.append("§n");
                if (style.isItalic()) out.append("§o");
                last[0] = style;
            }
            out.append(text);
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static ChatFormatting nearest(TextColor colour) {
        if (colour == null) return null;
        int rgb = colour.getValue();
        ChatFormatting best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ChatFormatting f : ChatFormatting.values()) {
            TextColor legacy = TextColor.fromLegacyFormat(f);
            if (legacy == null) continue;
            int c = legacy.getValue();
            int dr = (c >> 16 & 255) - (rgb >> 16 & 255), dg = (c >> 8 & 255) - (rgb >> 8 & 255), db = (c & 255) - (rgb & 255);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                best = f;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ incoming

    /** A string with § codes as a styled component (so it works in tooltips and hovers). */
    public static MutableComponent parseLegacy(String text) {
        MutableComponent out = Component.empty();
        if (text == null || text.isEmpty()) return out;
        Style style = Style.EMPTY.withItalic(false);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                if (f != null) {
                    if (!run.isEmpty()) {
                        out.append(Component.literal(run.toString()).setStyle(style));
                        run.setLength(0);
                    }
                    style = f == ChatFormatting.RESET ? Style.EMPTY.withItalic(false)
                        : TextColor.fromLegacyFormat(f) != null ? Style.EMPTY.withItalic(false).withColor(f) : style.applyFormat(f);
                    i++;
                    continue;
                }
            }
            run.append(c);
        }
        if (!run.isEmpty()) out.append(Component.literal(run.toString()).setStyle(style));
        return out;
    }

    public static String name(JsonObject item) {
        String name = Sbc.str(item, "name");
        return name.isEmpty() ? Sbc.str(item, "id") : name;
    }

    /** The item's name and lore, as a tooltip. */
    public static List<Component> tooltip(JsonObject item) {
        List<Component> lines = new ArrayList<>();
        lines.add(parseLegacy(name(item)));
        for (JsonElement line : Sbc.arr(item, "lore")) lines.add(parseLegacy(line.isJsonPrimitive() ? line.getAsString() : ""));
        return lines;
    }

    /** An ItemStack that looks like the item (its SkyBlock icon when known), with its name and lore. */
    public static ItemStack stack(JsonObject item) {
        String id = Sbc.str(item, "id");
        String texture = Sbc.str(item, "skullTexture");
        ItemStack stack;
        if (!texture.isEmpty()) {
            stack = Compat.createSkull(texture);
        } else {
            ItemStack repo = id.isEmpty() ? ItemStack.EMPTY : RepoItems.itemStack(id);
            if (repo != null && !repo.isEmpty() && !repo.is(Items.BARRIER)) {
                stack = repo.copy();
            } else {
                String registryId = id.contains(":") ? id.toLowerCase(Locale.ROOT) : "minecraft:" + id.toLowerCase(Locale.ROOT);
                Identifier parsed = Identifier.tryParse(registryId);
                Item vanilla = parsed == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(parsed).orElse(Items.PAPER);
                stack = new ItemStack(vanilla);
            }
        }
        stack.setCount((int) Math.max(1, Math.min(99, Sbc.lng(item, "count", 1))));
        stack.set(DataComponents.CUSTOM_NAME, parseLegacy(name(item)));
        List<Component> lore = tooltip(item);
        lore.removeFirst();
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /** "[Item Name]" in the item's colour; hover shows the tooltip, click shows the item. */
    public static MutableComponent chatComponent(JsonObject item) {
        int key;
        synchronized (SHARED) {
            key = nextShared++;
            SHARED.put(key, item);
        }
        MutableComponent name = parseLegacy(name(item));
        TextColor colour = firstColour(name);
        long count = Sbc.lng(item, "count", 1);
        MutableComponent hover = Component.empty();
        List<Component> lines = tooltip(item);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) hover.append("\n");
            hover.append(lines.get(i));
        }
        String plain = ChatFormatting.stripFormatting(name.getString());
        return Component.literal("[" + (count > 1 ? count + "x " : "") + plain + "]").withStyle(Style.EMPTY
            .withColor(colour == null ? TextColor.fromLegacyFormat(ChatFormatting.AQUA) : colour)
            .withHoverEvent(new HoverEvent.ShowText(hover))
            .withClickEvent(new ClickEvent.RunCommand("/s2 viewitem " + key)));
    }

    private static TextColor firstColour(Component component) {
        TextColor[] found = {null};
        component.visit((style, text) -> {
            if (found[0] == null && !text.isBlank() && style.getColor() != null) found[0] = style.getColor();
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    static JsonObject shared(int key) {
        synchronized (SHARED) {
            return SHARED.get(key);
        }
    }
}
