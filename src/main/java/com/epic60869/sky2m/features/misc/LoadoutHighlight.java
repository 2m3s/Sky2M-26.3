package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
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

import java.util.regex.Pattern;

/**
 * SkyHanni's loadout highlighting (https://github.com/hannibal002/SkyHanni, LGPL-2.1: LoadoutApi,
 * LoadoutSlotHighlight): the loadout you have equipped is highlighted in the Loadouts menu. Hypixel shows
 * "Left-click to equip!" on every loadout but the equipped one.
 */
public final class LoadoutHighlight {
    private static final Pattern INVENTORY = Pattern.compile("(?i)(?:\\(\\d+/\\d+\\)\\s*)?Loadouts");
    private static final Pattern LOCKED = Pattern.compile("(?i)Loadout \\d+ Locked");
    private static final int FIRST_ICON_SLOT = 14;
    private static final int ROWS = 4;
    private static final int SLOTS_PER_ROW = 3;

    private LoadoutHighlight() {}

    private static final Pattern EQUIPPED_CHAT = Pattern.compile("^You equipped (.+)!$");
    /** The loadout named in the last "You equipped X!" message: Hypixel no longer leaves "Left-click to equip!" off the equipped one. */
    private static volatile String equippedName = "";
    private static net.minecraft.client.gui.screens.Screen cacheScreen;
    private static long cacheAt;
    private static final java.util.Set<Integer> EQUIPPED_SLOTS = new java.util.HashSet<>();

    public static void init() {
        Sky2MChat.onChat(message -> {
            java.util.regex.Matcher m = EQUIPPED_CHAT.matcher(message.text().trim());
            if (m.matches()) equippedName = normalise(m.group(1));
        });
    }

    private static String normalise(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : Sky2MLocation.strip(text).toCharArray()) if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        return out.toString();
    }

    /**
     * The loadout icon slots that are the equipped loadout, worked out a few times a second: the one named in the last
     * "You equipped X!" message; else the ones whose lore armor matches what you wear; else the ones without
     * "Left-click to equip!" (the old behaviour).
     */
    private static java.util.Set<Integer> equippedSlots(AbstractContainerScreen<?> screen) {
        long now = System.currentTimeMillis();
        if (screen == cacheScreen && now - cacheAt < 250) return EQUIPPED_SLOTS;
        cacheScreen = screen;
        cacheAt = now;
        EQUIPPED_SLOTS.clear();
        java.util.List<Slot> icons = new java.util.ArrayList<>();
        for (Slot s : screen.getMenu().slots) {
            if (s.container instanceof Inventory || !isIconSlot(s.index) || s.getItem().isEmpty()) continue;
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).getPath().contains("glass_pane")) continue;
            if (LOCKED.matcher(Sky2MLocation.strip(s.getItem().getHoverName().getString())).find()) continue;
            icons.add(s);
        }
        String named = equippedName;
        if (!named.isEmpty()) {
            for (Slot s : icons) if (normalise(s.getItem().getHoverName().getString()).equals(named)) EQUIPPED_SLOTS.add(s.index);
            if (!EQUIPPED_SLOTS.isEmpty()) return EQUIPPED_SLOTS;
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            String[] worn = {
                wornName(player, net.minecraft.world.entity.EquipmentSlot.HEAD), wornName(player, net.minecraft.world.entity.EquipmentSlot.CHEST),
                wornName(player, net.minecraft.world.entity.EquipmentSlot.LEGS), wornName(player, net.minecraft.world.entity.EquipmentSlot.FEET)};
            String[] keys = {"helmet:", "chestplate:", "leggings:", "boots:"};
            for (Slot s : icons) {
                ItemLore lore = s.getItem().get(DataComponents.LORE);
                if (lore == null) continue;
                String[] want = new String[4];
                for (Component line : lore.lines()) {
                    String text = Sky2MLocation.strip(line.getString()).trim();
                    String lower = text.toLowerCase(java.util.Locale.ROOT);
                    for (int i = 0; i < 4; i++) if (lower.startsWith(keys[i])) want[i] = normalise(text.substring(keys[i].length()));
                }
                boolean all = true;
                for (int i = 0; i < 4 && all; i++) {
                    String w = want[i] == null || want[i].equals("empty") || want[i].equals("none") ? "" : want[i];
                    all = w.equals(worn[i]);
                }
                if (all) EQUIPPED_SLOTS.add(s.index);
            }
            if (!EQUIPPED_SLOTS.isEmpty()) return EQUIPPED_SLOTS;
        }
        for (Slot s : icons) if (isCurrentSelectedLoadout(s.getItem())) EQUIPPED_SLOTS.add(s.index);
        return EQUIPPED_SLOTS;
    }

    private static String wornName(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        return stack.isEmpty() ? "" : normalise(stack.getHoverName().getString());
    }

    /** Drawn behind the slot's item, from Sky2MSlotBackgroundMixin. */
    private static net.minecraft.network.chat.Component lastTitle;
    private static boolean lastTitleMatches;
    private static net.minecraft.client.gui.screens.Screen lastScreen;

    /** Asked for every slot every frame: the title is only matched when it changes. */
    private static boolean inLoadoutMenu(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.network.chat.Component title = screen.getTitle();
        if (screen != lastScreen || title != lastTitle) {
            lastScreen = screen;
            lastTitle = title;
            lastTitleMatches = INVENTORY.matcher(Sky2MLocation.strip(title.getString())).find();
        }
        return lastTitleMatches;
    }

    private static net.minecraft.client.gui.screens.Screen reported;

    /**
     * When a menu with "loadout" in its title opens, says once in chat (and in the log) what the highlight saw: the
     * title, whether it matched, and for each loadout icon its item, name and the decision. This is what to send if the
     * highlight is wrong, since Hypixel's menu text can change.
     */
    private static void report(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == reported) return;
        String title = Sky2MLocation.strip(screen.getTitle().getString());
        if (!title.toLowerCase(java.util.Locale.ROOT).contains("loadout")) {
            reported = screen;
            return;
        }
        // The first frames after opening have empty slots: wait until the loadout icons have arrived.
        boolean filled = false;
        for (Slot s : ((AbstractContainerScreen<?>) screen).getMenu().slots) {
            if (!(s.container instanceof Inventory) && isIconSlot(s.index) && !s.getItem().isEmpty()) {
                filled = true;
                break;
            }
        }
        if (!filled) return;
        reported = screen;
        StringBuilder log = new StringBuilder("[Sky2M] Loadout highlight: title '" + title + "' matched=" + INVENTORY.matcher(title).find()
            + " onSkyblock=" + Compat.isOnSkyblock() + "\n");
        int equipped = 0;
        var menu = ((AbstractContainerScreen<?>) screen).getMenu();
        for (Slot s : menu.slots) {
            if (s.container instanceof Inventory || !isIconSlot(s.index)) continue;
            ItemStack stack = s.getItem();
            boolean decision = isCurrentSelectedLoadout(stack);
            if (decision) equipped++;
            ItemLore lore = stack.get(DataComponents.LORE);
            log.append("  slot ").append(s.index).append(' ')
                .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
                .append(" '").append(Sky2MLocation.strip(stack.getHoverName().getString())).append("' equipped=").append(decision)
                .append(" lore=").append(lore == null ? "none" : lore.lines().stream()
                    .map(l -> Sky2MLocation.strip(l.getString())).collect(java.util.stream.Collectors.joining(" | "))).append('\n');
        }
        if (com.epic60869.sky2m.Sky2MDebug.roomDebug()) for (String row : log.toString().split("\n")) System.out.println(row);
    }

    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.inventory.loadoutHighlight.enabled || !config.inventory.loadoutHighlight.currentlyEquipped) return;
        if (slot.container instanceof Inventory) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return;
        report(screen);
        if (!Compat.isOnSkyblock() || !isIconSlot(slot.index)) return;
        if (!inLoadoutMenu(screen)) return;
        if (!equippedSlots((AbstractContainerScreen<?>) screen).contains(slot.index)) return;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour(config.inventory.loadoutHighlight.equippedColor));
    }

    private static boolean isIconSlot(int index) {
        for (int row = 0; row < ROWS; row++) {
            int start = FIRST_ICON_SLOT + row * 9;
            if (index >= start && index < start + SLOTS_PER_ROW) return true;
        }
        return false;
    }

    // A loadout that doesn't change anything has no "Left-click to equip!" either, so it counts as equipped too.
    private static boolean isCurrentSelectedLoadout(ItemStack stack) {
        if (stack == null || stack.isEmpty() || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("glass_pane")) return false;
        if (LOCKED.matcher(Sky2MLocation.strip(stack.getHoverName().getString())).find()) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            String text = Sky2MLocation.strip(line.getString()).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("to equip") || text.contains("you must customize this loadout")) return false;
        }
        return !lore.lines().isEmpty();
    }

    private static int colour(String value) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return 0xAA55FF55;
        }
    }
}
