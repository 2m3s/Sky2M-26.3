package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights attribute shards you own enough of to level up (or unlock). In the Hunting Box each shard says both how
 * many it needs ("Syphon 3 shards to level up!") and how many you have ("Owned: 5 Shards"). The Attribute Menu only
 * says how many it needs, so it uses the amounts last seen in the Hunting Box, saved between sessions (SkyHanni's
 * AttributeShardsData reads the same lines). Shards are matched by item id, as the Hunting Box names the shard and the
 * Attribute Menu names its attribute; the names are only a fallback for items without one.
 */
public final class ShardLevelUpHighlight {
    private static final Pattern HUNTING_BOX = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Hunting Box");
    private static final Pattern ATTRIBUTE_MENU = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Attribute Menu");
    private static final Pattern SYPHON = Pattern.compile("Syphon (\\d+) shards? to (?:level up|unlock)!");
    private static final Pattern OWNED = Pattern.compile("Owned: ([\\d,]+) Shards?");
    /** The Hunting Box's lore line naming the attribute: "Veil (Combat)", "Yummy X (Foraging)". */
    private static final Pattern BOX_NAME = Pattern.compile("^(.+?)(?: [IVXL]+)? \\(\\w+\\)$");
    /** The Attribute Menu's item name: "Berry Eater IX", "Nature Elemental". */
    private static final Pattern MENU_NAME = Pattern.compile("^(.+?)(?: [IVXL]+)?$");
    private static final int COLOUR = 0x8055FF55;

    /** Shards owned in the Hunting Box, by item id and by attribute name. */
    private static final Map<String, Integer> OWNED_AMOUNTS = new ConcurrentHashMap<>();
    private static boolean loaded;
    private static boolean hinted;
    /** Whether each item can level up, so lore isn't read every frame. */
    private static final Map<ItemStack, Boolean> CACHE = new WeakHashMap<>();

    private ShardLevelUpHighlight() {}

    /** Drawn behind the slot's item, from Sky2MSlotBackgroundMixin. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.shardLevelUpHighlight || !slot.hasItem()) return;
        if (slot.container instanceof Inventory || !Compat.isOnSkyblock()) return;
        if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        String title = Sky2MLocation.strip(screen.getTitle().getString());
        boolean box = HUNTING_BOX.matcher(title).find();
        if (!box && !ATTRIBUTE_MENU.matcher(title).find()) return;
        ItemStack stack = slot.getItem();
        Boolean ready = CACHE.get(stack);
        if (ready == null) {
            ready = box ? readBoxShard(stack) : readMenuShard(stack);
            CACHE.put(stack, ready);
        }
        if (ready) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, COLOUR);
    }

    private static boolean readBoxShard(ItemStack stack) {
        String name = null;
        int needed = -1;
        int owned = -1;
        for (Component line : lore(stack)) {
            String text = Sky2MLocation.strip(line.getString()).trim();
            Matcher m;
            if (name == null && (m = BOX_NAME.matcher(text)).matches()) name = m.group(1);
            if ((m = SYPHON.matcher(text)).find()) needed = Integer.parseInt(m.group(1));
            if ((m = OWNED.matcher(text)).find()) owned = Integer.parseInt(m.group(1).replace(",", ""));
        }
        if (owned >= 0) {
            boolean changed = remember(Compat.neuName(stack), owned);
            if (name != null) changed |= remember(name, owned);
            if (changed) save();
        }
        return needed > 0 && owned >= needed;
    }

    private static boolean readMenuShard(ItemStack stack) {
        int needed = -1;
        for (Component line : lore(stack)) {
            Matcher m = SYPHON.matcher(Sky2MLocation.strip(line.getString()));
            if (m.find()) needed = Integer.parseInt(m.group(1));
        }
        if (needed <= 0) return false;
        load();
        if (OWNED_AMOUNTS.isEmpty() && !hinted) {
            // Otherwise nothing lights up and it looks broken: the Attribute Menu doesn't say how many you own.
            hinted = true;
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal(
                "Open your Hunting Box once so Shard Level Up Highlight knows how many shards you have.").withColor(0xFFFF55)));
        }
        String id = Compat.neuName(stack);
        Integer owned = id.isEmpty() ? null : OWNED_AMOUNTS.get(id);
        if (owned == null) {
            Matcher m = MENU_NAME.matcher(Sky2MLocation.strip(stack.getHoverName().getString()).trim());
            if (m.matches()) owned = OWNED_AMOUNTS.get(m.group(1));
        }
        return owned != null && owned >= needed;
    }

    private static boolean remember(String key, int owned) {
        if (key == null || key.isEmpty()) return false;
        load();
        Integer old = OWNED_AMOUNTS.put(key, owned);
        return old == null || old != owned;
    }

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("shard-amounts.json");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            java.nio.file.Path file = file();
            if (!java.nio.file.Files.exists(file)) return;
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            root.entrySet().forEach(e -> OWNED_AMOUNTS.putIfAbsent(e.getKey(), e.getValue().getAsInt()));
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read shard-amounts.json: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            java.nio.file.Path file = file();
            java.nio.file.Files.createDirectories(file.getParent());
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            new java.util.TreeMap<>(OWNED_AMOUNTS).forEach(root::addProperty);
            java.nio.file.Files.writeString(file, root.toString());
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save shard-amounts.json: " + e.getMessage());
        }
    }

    private static java.util.List<Component> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        return lore == null ? java.util.List.of() : lore.lines();
    }
}
