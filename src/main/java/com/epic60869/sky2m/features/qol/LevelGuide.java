package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
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
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Misc > SkyBlock Level Guide: highlights the tasks you have not finished in the SkyBlock Guide menus. Ported from
 * SkyHanni's SkyblockGuideHighlightFeature (hannibal002/SkyHanni, LGPL-3.0): a menu is recognised by its title and an
 * item is highlighted when its name or lore matches that menu's "not done yet" line.
 */
public final class LevelGuide {
    private static final Pattern CROSS = Pattern.compile("^\\s?✖.*");
    private static final Pattern ONCE = Pattern.compile("^This task can only be completed once!");
    private static final Pattern DUNGEON_ONCE = Pattern.compile("^This task can only be completed once!|^You have not unlocked the content");
    private static final Pattern TOTAL_PROGRESS = Pattern.compile("^Total Progress: \\d{1,2}(?:\\.\\d)?%");
    private static final Pattern CATEGORY_PROGRESS = Pattern.compile("^Progress to Complete Category: \\d{1,2}(?:\\.\\d)?%");
    private static final Pattern MINION_MISSING = Pattern.compile("^\\s?✖.*|^You haven't crafted this minion\\.");
    private static final Pattern CONSUMABLE = Pattern.compile("^This task can be completed \\d+ times!");
    private static final Pattern COLLECTION = Pattern.compile("^Progress to .*|^Find this item to add it to your|^Kill this boss once to view collection|^(?:Boss )?Collections (?:Unlocked|Maxed Out): .*");

    private record Menu(Pattern title, Pattern condition, Predicate<Config> enabled) {}

    private static final Menu[] MENUS = {
        new Menu(Pattern.compile(".*Guide ➜.*"), CROSS, c -> c.missingTasks),
        new Menu(Pattern.compile("Miscellaneous ➜ Abiphone Contac.*"), ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Core ➜ Bank Upgrades"), ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Core ➜ Fast Travels Unlocked"), ONCE, c -> c.story),
        new Menu(Pattern.compile("Event ➜ Spooky Festival"), ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Miscellaneous ➜ The Dojo"), ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Event ➜ Jacob's Farming Contest"), ONCE, c -> c.story),
        new Menu(Pattern.compile("Slaying ➜ .*"), ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Story ➜ Complete Objectives"), ONCE, c -> c.story),
        new Menu(Pattern.compile("Mining ➜ Rock Milestones"), ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Fishing ➜ Dolphin Milestones"), ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Essence Shop ➜.*"), CROSS, c -> c.essence),
        new Menu(Pattern.compile("(?:\\(\\d/\\d\\) )?(?:Crafted Minions|\\w+ ➜ Minions)"), MINION_MISSING, c -> c.minions),
        new Menu(Pattern.compile("Miscellaneous ➜ Harp Songs"), CROSS, c -> c.story),
        new Menu(Pattern.compile("Miscellaneous ➜ Consumable Items"), CONSUMABLE, c -> c.consumables),
        new Menu(Pattern.compile("Complete Dungeons ➜.*"), DUNGEON_ONCE, c -> c.oneTime),
        new Menu(Pattern.compile("Dungeon ➜ Complete Dungeons"), CROSS, c -> c.oneTime),
        new Menu(Pattern.compile("Tasks ➜ .*"), TOTAL_PROGRESS, c -> c.menu),
        new Menu(Pattern.compile("Skill Related Tasks"), CATEGORY_PROGRESS, c -> c.menu),
        new Menu(Pattern.compile("\\w+ Collections|Collections"), COLLECTION, c -> c.collections),
    };

    /** Per-stack results, so the lore is not re-read for every slot every frame. */
    private static final Map<ItemStack, Boolean> CACHE = new WeakHashMap<>();
    private static Component lastTitle;
    private static Menu lastMenu;
    private static boolean lastMenuKnown;

    private LevelGuide() {}

    public static final class Config {
        @Expose @ConfigOption(name = "Missing Tasks", desc = "Highlight tasks you have not done in the SkyBlock Level Guide menus (the ones with a red cross).") @ConfigEditorBoolean public boolean missingTasks = false;
        @Expose @ConfigOption(name = "Menu Highlight", desc = "Highlight the top level of tasks that are not completed in the SkyBlock Guide's task menus.") @ConfigEditorBoolean public boolean menu = false;
        @Expose @ConfigOption(name = "One Time Completions", desc = "Highlight missing one-time tasks: Kuudra and Dungeon floors, Spooky Festival, bank upgrades, Rock and Dolphin milestones, slaying, the Dojo and Abiphone contacts.") @ConfigEditorBoolean public boolean oneTime = false;
        @Expose @ConfigOption(name = "Story Highlight", desc = "Highlight unfinished storylines, missing fast travel scrolls, harp songs and Jacob's Farming Contest crops.") @ConfigEditorBoolean public boolean story = false;
        @Expose @ConfigOption(name = "Minion Highlight", desc = "Highlight minions you have not crafted or maxed.") @ConfigEditorBoolean public boolean minions = false;
        @Expose @ConfigOption(name = "Consumable Highlight", desc = "Highlight consumables you have not fully used.") @ConfigEditorBoolean public boolean consumables = false;
        @Expose @ConfigOption(name = "Essence Shop Highlight", desc = "Highlight essence shop upgrades you are missing.") @ConfigEditorBoolean public boolean essence = false;
        @Expose @ConfigOption(name = "Collection Highlight", desc = "Highlight collections you have not finished.") @ConfigEditorBoolean public boolean collections = false;
    }

    private static Menu menuFor(net.minecraft.client.gui.screens.Screen screen, Config c) {
        Component title = screen.getTitle();
        if (title != lastTitle) {
            lastTitle = title;
            String name = Sky2MLocation.strip(title.getString()).trim();
            lastMenu = null;
            for (Menu menu : MENUS) {
                if (menu.title().matcher(name).matches()) {
                    lastMenu = menu;
                    break;
                }
            }
            lastMenuKnown = true;
            CACHE.clear();
        }
        return lastMenu != null && lastMenu.enabled().test(c) ? lastMenu : null;
    }

    /** Drawn behind the slot's item, from Sky2MLevelGuideMixin. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null) return;
        Config c = config.misc.levelGuide;
        if (!(c.missingTasks || c.menu || c.oneTime || c.story || c.minions || c.consumables || c.essence || c.collections)) return;
        if (!Compat.isOnSkyblock() || slot.container instanceof Inventory || slot.getContainerSlot() == 4) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return;
        Menu menu = menuFor(screen, c);
        if (menu == null) return;
        ItemStack stack = slot.getItem();
        if (stack == null || stack.isEmpty()) return;
        if (CACHE.computeIfAbsent(stack, s -> missing(s, menu))) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x70FF3030);
        }
    }

    private static boolean missing(ItemStack stack, Menu menu) {
        if (menu.condition().matcher(Sky2MLocation.strip(stack.getHoverName().getString()).trim()).find()) return true;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            if (menu.condition().matcher(Sky2MLocation.strip(line.getString())).find()) return true;
        }
        return false;
    }
}
