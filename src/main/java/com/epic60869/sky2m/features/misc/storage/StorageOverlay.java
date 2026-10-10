// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/StorageOverlay.kt and
// the storage commands in commands/rome.kt.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.function.IntFunction;

public final class StorageOverlay {
    private StorageOverlay() {}

    private static final Sky2MConfig.StorageOverlaySettings DEFAULTS = new Sky2MConfig.StorageOverlaySettings();

    /** Firmament's StorageOverlay.TConfig. */
    public static Sky2MConfig.StorageOverlaySettings config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.misc.storageOverlaySettings == null ? DEFAULTS : c.misc.storageOverlaySettings;
    }

    /** "Always Open Overlay": replace the storage menus without /s2 storage. */
    public static boolean alwaysReplace() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.misc.storageOverlaySettings.enabled;
    }

    public static int colour(String special) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(special).getEffectiveColourRGB();
        } catch (Exception e) {
            return 0xFFFFFF00;
        }
    }

    public static double adjustScrollSpeed(double amount) {
        return amount * config().scrollSpeed * (config().inverseScroll ? 1 : -1);
    }

    /** The storage overlay is showing: on its own, or drawn over a storage menu. */
    public static boolean isOverlayScreen(Screen screen) {
        return screen instanceof StorageOverlayScreen || screen instanceof StorageOverviewScreen
            || screen instanceof AbstractContainerScreen<?> acs && CustomGui.get(acs) instanceof StorageOverlayCustom;
    }

    public static StorageOverviewScreen lastStorageOverlay;
    public static boolean skipNextStorageOverlayBackflip;
    public static StorageBackingHandle currentHandler;
    private static int ticks;

    public static void init(Path configDir) {
        StorageData.init(configDir);
        StorageValueHud.init();
        // Firmament remembers a page each time its contents arrive; checking the open page every few ticks does the same.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 == 0) rememberContent(currentHandler);
            StorageData.tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> StorageData.save());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> StorageData.save());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("storage").executes(c -> {
                        Compat.queueOpenScreen(new StorageOverlayScreen());
                        sendCommand("storage");
                        return 1;
                    }))
                    .then(ClientCommands.literal("storageoverview").executes(c -> {
                        Compat.queueOpenScreen(new StorageOverviewScreen());
                        sendCommand("storage");
                        return 1;
                    }))
                    .then(ClientCommands.literal("storagename")
                        .then(storageNameBranch("enderchest", 9, StoragePageSlot::ofEnderChestPage))
                        .then(storageNameBranch("backpack", 18, StoragePageSlot::ofBackPackPage))));
            }
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> storageNameBranch(
        String type, int maxPage, IntFunction<StoragePageSlot> toSlot) {
        return ClientCommands.literal(type).then(ClientCommands.argument("page", IntegerArgumentType.integer(1, maxPage))
            .executes(c -> resetStorageName(toSlot.apply(IntegerArgumentType.getInteger(c, "page"))))
            .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                .executes(c -> setStorageName(toSlot.apply(IntegerArgumentType.getInteger(c, "page")), StringArgumentType.getString(c, "name")))));
    }

    private static int setStorageName(StoragePageSlot slot, String name) {
        StorageData.data().customNames.put(slot, name);
        StorageData.markDirty();
        return say("Renamed " + slot.defaultName() + " to \"" + name + "\".");
    }

    private static int resetStorageName(StoragePageSlot slot) {
        StorageData.data().customNames.remove(slot);
        StorageData.markDirty();
        return say("Reset the name of " + slot.defaultName() + ".");
    }

    private static int say(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(Component.literal(text).withStyle(ChatFormatting.YELLOW)));
        });
        return 1;
    }

    private static void sendCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(command);
    }

    /** A slot in a storage page was clicked (Sky2MCustomGuiContainerMixin). */
    public static void onSlotClick(Slot slot) {
        if (lastStorageOverlay != null && !(slot.container instanceof Inventory) && slot.getContainerSlot() < 9
            && slot.getItem().getItem() != net.minecraft.world.item.Items.STAINED_GLASS_PANE.black()) {
            skipNextStorageOverlayBackflip = true;
        }
    }

    /**
     * Firmament's ScreenChangeEvent handler (Sky2MScreenChangeMixin): attaches the overlay to storage menus, and
     * returns a screen to show instead of {@code newScreen}, or null to show {@code newScreen}.
     */
    public static Screen onScreenChange(Screen oldScreen, Screen newScreen) {
        if (oldScreen == null && newScreen == null) return null;
        StorageOverlayScreen storageOverlayScreen = oldScreen instanceof StorageOverlayScreen s ? s
            : oldScreen instanceof AbstractContainerScreen<?> acs && CustomGui.get(acs) instanceof StorageOverlayCustom custom ? custom.overview : null;
        StorageOverviewScreen storageOverviewScreen = oldScreen instanceof StorageOverviewScreen o ? o : null;
        ContainerScreen screen = newScreen instanceof ContainerScreen cs ? cs : null;
        rememberContent(currentHandler);
        StorageBackingHandle oldHandler = currentHandler;
        currentHandler = StorageBackingHandle.fromScreen(screen);
        if (storageOverviewScreen != null && oldHandler instanceof StorageBackingHandle.HasBackingScreen backing) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.connection.send(new ServerboundContainerClosePacket(backing.handler().containerId));
                if (mc.player.containerMenu == backing.handler()) mc.player.containerMenu = mc.player.inventoryMenu;
            }
        }
        if (storageOverviewScreen == null) storageOverviewScreen = lastStorageOverlay;
        // Hypixel closes the page before opening the next one: keep showing the overlay meanwhile.
        if (newScreen == null && storageOverlayScreen != null && !storageOverlayScreen.isExiting) {
            return storageOverlayScreen;
        }
        if (storageOverviewScreen != null && !storageOverviewScreen.isClosing
            && (currentHandler instanceof StorageBackingHandle.Overview || currentHandler == null)) {
            if (skipNextStorageOverlayBackflip) {
                skipNextStorageOverlayBackflip = false;
                return null;
            }
            lastStorageOverlay = null;
            return storageOverviewScreen;
        }
        if (screen == null) return null;
        if (storageOverlayScreen != null && storageOverlayScreen.isExiting) return null;
        if (currentHandler == null) return null;
        StorageOverlayScreen overview = storageOverlayScreen != null ? storageOverlayScreen
            : alwaysReplace() && Compat.isOnSkyblock() ? new StorageOverlayScreen() : null;
        if (overview == null) return null;
        CustomGui.set(screen, new StorageOverlayCustom(currentHandler, screen, overview));
        return null;
    }

    public static void rememberContent(StorageBackingHandle handler) {
        if (handler == null) return;
        SortedMap<StoragePageSlot, StorageData.StorageInventory> data = StorageData.data().storageInventories;
        if (handler instanceof StorageBackingHandle.Overview overview) rememberStorageOverview(overview, data);
        else if (handler instanceof StorageBackingHandle.Page page) rememberPage(page, data);
    }

    private static void rememberStorageOverview(StorageBackingHandle.Overview handler, SortedMap<StoragePageSlot, StorageData.StorageInventory> data) {
        List<ItemStack> items = handler.handler().getItems();
        boolean changed = false;
        for (int index = 0; index < items.size(); index++) {
            ItemStack stack = items.get(index);
            // Ignore unloaded item stacks
            if (stack.isEmpty()) continue;
            StoragePageSlot slot = StoragePageSlot.fromOverviewSlotIndex(index);
            if (slot == null) continue;
            boolean isEmpty = StorageOverviewScreen.EMPTY_STORAGE_SLOT_ITEMS.contains(stack.getItem());
            if (data.containsKey(slot)) {
                if (isEmpty) {
                    data.remove(slot);
                    changed = true;
                }
                continue;
            }
            if (!isEmpty) {
                data.put(slot, new StorageData.StorageInventory(slot, null));
                changed = true;
            }
        }
        if (changed) StorageData.markDirty();
    }

    private static void rememberPage(StorageBackingHandle.Page handler, SortedMap<StoragePageSlot, StorageData.StorageInventory> data) {
        List<ItemStack> all = handler.handler().getItems();
        int end = Math.min(all.size(), handler.handler().getRowCount() * 9);
        if (end <= 9) return;
        List<ItemStack> newStacks = new ArrayList<>(all.subList(9, end));
        StorageData.StorageInventory existing = data.get(handler.storagePageSlot());
        if (existing != null && existing.inventory != null && existing.inventory.sameAs(newStacks)) return;
        if (existing == null) {
            existing = new StorageData.StorageInventory(handler.storagePageSlot(), null);
            data.put(handler.storagePageSlot(), existing);
        }
        existing.inventory = new VirtualInventory(newStacks);
        StorageData.markDirty();
    }
}
