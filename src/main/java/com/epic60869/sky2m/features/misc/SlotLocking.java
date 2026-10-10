package com.epic60869.sky2m.features.misc;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Item protection and slot binding. Protected items cannot be dropped; binding turns a shift-click into the same swap
 * you'd do with a number key.
 * <ul>
 *   <li>Protect: run /s2 protect while holding an item. Protection follows the item as it moves between slots.</li>
 *   <li>Bind (Odin's Slot Binds): in your inventory, press the bind key (B) over a slot, then over another (one must be
 *   in the hotbar), or click it. Shift-clicking either then swaps them. Press B on a bound slot to remove the bind. A
 *   box and line show bound slots while you hover one, or always (Killer560's Mod's Slot Binds, MIT).</li>
 * </ul>
 */
public final class SlotLocking {
    private static Integer pendingBind;

    private SlotLocking() {}

    private static Sky2MConfig.SlotLocking config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.slotLocking;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                // The Protect Item key protects the hovered item.
                if (com.epic60869.sky2m.Sky2MKeyMappings.PROTECT_ITEM != null
                    && com.epic60869.sky2m.Sky2MKeyMappings.PROTECT_ITEM.matches(event)) {
                    Slot hovered = ((Sky2MContainerScreenAccessor) container).sky2m$getHoveredSlot();
                    if (hovered != null && hovered.hasItem()) {
                        toggleProtection(hovered.getItem());
                        return false;
                    }
                }
                return !keyPressed(container, event.key());
            });
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> render(container, g, mouseX, mouseY));
            ScreenEvents.remove(screen).register(s -> pendingBind = null);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("protect").executes(context -> toggleProtection())));
            }
        });
        // The Protect Item key in the world: the held item.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (com.epic60869.sky2m.Sky2MKeyMappings.PROTECT_ITEM != null
                && com.epic60869.sky2m.Sky2MKeyMappings.PROTECT_ITEM.consumeClick()) toggleProtection();
        });
    }

    // ------------------------------------------------------------ protection

    private static String protectionKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        String uuid = Compat.uuid(stack);
        if (!uuid.isBlank()) return "uuid:" + uuid;
        String id = Compat.neuName(stack);
        if (id.isBlank()) id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return "item:" + id;
    }

    public static boolean isProtected(ItemStack stack) {
        Sky2MConfig.SlotLocking c = config();
        String key = protectionKey(stack);
        return c != null && !key.isEmpty() && c.protectedItems.contains(key);
    }

    private static int toggleProtection() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        return toggleProtection(mc.player.getMainHandItem());
    }

    private static int toggleProtection(ItemStack stack) {
        String key = protectionKey(stack);
        if (key.isEmpty()) {
            say(Component.literal("Hold an item to protect or unprotect it.").withStyle(ChatFormatting.RED));
            return 0;
        }
        Sky2MConfig.SlotLocking c = config();
        if (c == null) return 0;
        String name = Compat.realName(stack).getString();
        if (c.protectedItems.remove(key)) {
            say(Component.literal("Removed drop protection from ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(name).withStyle(ChatFormatting.WHITE)));
        } else {
            c.protectedItems.add(key);
            say(Component.literal("★ Protected ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(name).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" from dropping.").withStyle(ChatFormatting.GREEN)));
        }
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        return 1;
    }

    /**
    * Called for every slot click in a menu (ContainerSolverScreenMixin); true cancels protected-item drops or handles
    * a shift-click on a bound slot.
     */
    public static boolean onSlotClicked(AbstractContainerScreen<?> screen, Slot slot, int button, ContainerInput input) {
        Sky2MConfig.SlotLocking c = config();
        if (c == null) return false;
        if (input == ContainerInput.THROW || slot == null && input == ContainerInput.PICKUP) {
            ItemStack dropped = slot == null ? screen.getMenu().getCarried() : slot.getItem();
            if (isProtected(dropped)) {
                say(Component.literal("★ ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("That item is protected. Use /s2 protect to unprotect it.").withStyle(ChatFormatting.RED)));
                return true;
            }
        }
        if (!c.enabled) return false;

        // A bind being made (the bind key was pressed over a slot): clicking the other slot finishes it, as in
        // Killer560's Mod's Slot Binds. Clicking anywhere else cancels it.
        if (pendingBind != null && screen instanceof InventoryScreen && input == ContainerInput.PICKUP) {
            int first = pendingBind;
            pendingBind = null;
            if (slot != null && slot.index >= 5 && slot.index < 45) finishBind(c, first, slot.index);
            return true;
        }

        // Slot binds: shift-click in your own inventory swaps with the bound hotbar slot.
        if (screen instanceof InventoryScreen && input == ContainerInput.QUICK_MOVE && slot != null) {
            int clicked = slot.index;
            Integer bound = boundTo(clicked);
            if (bound == null) return false;
            int from;
            int hotbar;
            if (clicked >= 36 && clicked <= 44) {
                from = bound;
                hotbar = clicked - 36;
            } else if (bound >= 36 && bound <= 44) {
                from = clicked;
                hotbar = bound - 36;
            } else {
                return false;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode == null || mc.player == null) return false;
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, from, hotbar, ContainerInput.SWAP, mc.player);
            return true;
        }
        return false;
    }

    /** Q in the world: nothing drops while the selected item is protected. */
    public static boolean blockDrop() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        boolean blocked = isProtected(mc.player.getMainHandItem());
        if (blocked) say(Component.literal("★ ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal("That item is protected. Use /s2 protect to unprotect it.").withStyle(ChatFormatting.RED)));
        return blocked;
    }

    private static Integer boundTo(int menuSlot) {
        Sky2MConfig.SlotLocking c = config();
        if (c == null) return null;
        Integer direct = c.binds.get(menuSlot);
        if (direct != null) return direct;
        for (var e : c.binds.entrySet()) if (e.getValue() == menuSlot) return e.getKey();
        return null;
    }

    private static boolean keyPressed(AbstractContainerScreen<?> screen, int key) {
        Sky2MConfig.SlotLocking c = config();
        if (c == null || !c.enabled || key == InputConstants.UNKNOWN.getValue()) return false;
        Slot hovered = ((Sky2MContainerScreenAccessor) screen).sky2m$getHoveredSlot();
        if (key == c.bindKey && screen instanceof InventoryScreen) {
            if (hovered == null || hovered.index < 5 || hovered.index >= 45) return false;
            int clicked = hovered.index;
            if (pendingBind == null) {
                Integer existing = c.binds.containsKey(clicked) ? Integer.valueOf(clicked) : null;
                if (existing == null) {
                    for (var e : c.binds.entrySet()) if (e.getValue() == clicked) existing = e.getKey();
                }
                if (existing != null) {
                    int to = c.binds.remove(existing);
                    Sky2MConfig.saveCurrent(Sky2MConfig.current());
                    say(Component.literal("Removed the bind between slots " + existing + " and " + to + ".").withStyle(ChatFormatting.YELLOW));
                    return true;
                }
                pendingBind = clicked;
                return true;
            }
            int first = pendingBind;
            pendingBind = null;
            finishBind(c, first, clicked);
            return true;
        }
        return false;
    }

    /** Binds two slots, one of them in the hotbar (the swap needs a hotbar slot). */
    private static void finishBind(Sky2MConfig.SlotLocking c, int first, int clicked) {
        if (first == clicked) {
            say(Component.literal("You can't bind a slot to itself.").withStyle(ChatFormatting.RED));
            return;
        }
        if ((first < 36 || first > 44) && (clicked < 36 || clicked > 44)) {
            say(Component.literal("One of the two slots must be in your hotbar.").withStyle(ChatFormatting.RED));
            return;
        }
        // A slot is only ever in one bind.
        c.binds.entrySet().removeIf(e -> e.getKey() == first || e.getValue() == first || e.getKey() == clicked || e.getValue() == clicked);
        c.binds.put(first, clicked);
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        say(Component.literal("Bound slot " + first + " to slot " + clicked + ". Shift-click either to swap them.").withStyle(ChatFormatting.GREEN));
    }

    // ---------------------------------------------------------------- drawing

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MConfig.SlotLocking c = config();
        if (c == null) return;
        Sky2MContainerScreenAccessor access = (Sky2MContainerScreenAccessor) screen;
        int left = access.sky2m$getLeftPos();
        int top = access.sky2m$getTopPos();

        if (!c.enabled || !(screen instanceof InventoryScreen)) return;
        // Every bind, all the time (Killer560's "always" mode): a box around both slots and a line between them.
        if (c.alwaysShowBinds) {
            for (var e : c.binds.entrySet()) {
                if (e.getKey() < 5 || e.getKey() >= 45 || e.getValue() < 5 || e.getValue() >= 45) continue;
                Slot a = screen.getMenu().getSlot(e.getKey()), b = screen.getMenu().getSlot(e.getValue());
                box(g, left + a.x, top + a.y, 0xFF55FF55);
                box(g, left + b.x, top + b.y, 0xFF55FF55);
                line(g, left + a.x + 8, top + a.y + 8, left + b.x + 8, top + b.y + 8, 0xFF55FF55);
            }
        }
        Slot hovered = access.sky2m$getHoveredSlot();
        Integer startIndex = pendingBind != null ? pendingBind : hovered == null ? null : Integer.valueOf(hovered.index);
        if (startIndex == null || startIndex < 5 || startIndex >= 45) return;
        Slot start = screen.getMenu().getSlot(startIndex);
        int sx = left + start.x + 8;
        int sy = top + start.y + 8;
        int ex;
        int ey;
        if (pendingBind != null) {
            ex = mouseX;
            ey = mouseY;
        } else {
            Integer bound = boundTo(startIndex);
            if (bound == null || (c.lineOnlyWithShift && !Minecraft.getInstance().hasShiftDown())) return;
            Slot end = screen.getMenu().getSlot(bound);
            ex = left + end.x + 8;
            ey = top + end.y + 8;
        }
        box(g, sx - 8, sy - 8, 0xFF55FF55);
        if (pendingBind == null) box(g, ex - 8, ey - 8, 0xFF55FF55);
        line(g, sx, sy, ex, ey, 0xFF55FF55);
    }

    /** A one-pixel box just around a slot whose top left is (x, y). */
    private static void box(GuiGraphicsExtractor g, int x, int y, int colour) {
        g.fill(x - 1, y - 1, x + 17, y, colour);
        g.fill(x - 1, y + 16, x + 17, y + 17, colour);
        g.fill(x - 1, y, x, y + 16, colour);
        g.fill(x + 16, y, x + 17, y + 16, colour);
    }

    /**
     * The star on a protected item, drawn with the slot (coordinates relative to the menu) so tooltips and the item on
     * your cursor go over it. Drawn at 3/4 size in the slot's top-right corner.
     */
    public static void renderSlot(GuiGraphicsExtractor g, Slot slot) {
        if (!isProtected(slot.getItem())) return;
        g.pose().pushMatrix();
        g.pose().translate(slot.x + 10.5f, slot.y - 0.5f);
        g.pose().scale(0.75f, 0.75f);
        g.text(Minecraft.getInstance().font, "★", 0, 0, 0xFFFFD54F, true);
        g.pose().popMatrix();
    }

    /** A straight line made of small squares. */
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int colour) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / Math.max(1, steps);
            int y = y1 + (y2 - y1) * i / Math.max(1, steps);
            g.fill(x, y, x + 1, y + 1, colour);
        }
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(
            Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(message));
    }
}
