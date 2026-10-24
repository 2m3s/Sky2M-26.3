package com.epic60869.sky2m.gametest;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Input;
import com.epic60869.sky2m.features.core.BindKeys;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.notenoughupdates.moulconfig.platform.ModernKeybindHelper;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Minecraft 26.3's keybind numbers: SDL's keys A-F are 4-9, which MoulConfig's 26.3 build also used for mouse
 * buttons, so A-F binds read as mouse buttons. Checks binds are told apart (mouse binds are -100 + the button), that
 * old saved mouse binds are moved over while A-F binds are kept, the names settings show, and Slot Binding's B.
 */
public class Sky2MKeybindGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            // A-F are keys; mouse binds are -100 + the GLFW button; 1-3 are older mouse binds; 0 is unset.
            for (int key = InputConstants.KEY_A; key <= InputConstants.KEY_F; key++) {
                check(Input.bindMouseButton(key) == -1, "key " + key + " (A-F) read as a mouse button");
                check(BindKeys.mouseButton(key) == -1, "BindKeys.mouseButton(" + key + ") is a mouse button");
            }
            check(Input.bindMouseButton(-100) == InputConstants.MOUSE_BUTTON_LEFT, "-100 isn't the left button");
            check(Input.bindMouseButton(-99) == InputConstants.MOUSE_BUTTON_RIGHT, "-99 isn't the right button");
            check(Input.bindMouseButton(-98) == InputConstants.MOUSE_BUTTON_MIDDLE, "-98 isn't the middle button");
            check(Input.bindMouseButton(1) == InputConstants.MOUSE_BUTTON_RIGHT, "old 1 isn't the right button");
            check(Input.bindMouseButton(0) == -1 && !BindKeys.isSet(0), "0 isn't unset");
            check(Input.isMouseBind(-99, InputConstants.MOUSE_BUTTON_RIGHT), "isMouseBind(-99, right) is false");
            check(!Input.isMouseBind(InputConstants.KEY_B, 5), "B matched mouse button 5");

            // The names MoulConfig's keybind settings show.
            check("B".equals(ModernKeybindHelper.getKeyName(InputConstants.KEY_B).getText()), "B isn't named B: "
                + ModernKeybindHelper.getKeyName(InputConstants.KEY_B).getText());
            check("F".equals(ModernKeybindHelper.getKeyName(InputConstants.KEY_F).getText()), "F isn't named F");
            check("Button 2".equals(ModernKeybindHelper.getKeyName(-99).getText()), "-99 isn't named Button 2: "
                + ModernKeybindHelper.getKeyName(-99).getText());
            check("NONE".equals(ModernKeybindHelper.getKeyName(0).getText()), "0 isn't named NONE");

            // Old saved binds: a right-click bind (1) moves to -99; B (5) stays B.
            Sky2MConfig config = Sky2MConfig.current();
            int before = config.inventory.slotLocking.bindKey;
            config.inventory.slotLocking.bindKey = 1;
            Input.migrateMouseBinds(config);
            check(config.inventory.slotLocking.bindKey == -99, "an old right-click bind wasn't moved: " + config.inventory.slotLocking.bindKey);
            config.inventory.slotLocking.bindKey = InputConstants.KEY_B;
            Input.migrateMouseBinds(config);
            check(config.inventory.slotLocking.bindKey == InputConstants.KEY_B, "a B bind was changed: " + config.inventory.slotLocking.bindKey);
            Input.migrateMouseBinds(config);
            check(config.inventory.slotLocking.bindKey == InputConstants.KEY_B, "running the move twice changed B");
            config.inventory.slotLocking.bindKey = before;
            System.out.println("[Sky2M gametest] keybind numbers OK");
        });

        // Slot Binding's Bind Key in the settings: it should read "B".
        ctx.getInput().resizeWindow(1920, 1057);
        ctx.runOnClient(mc -> mc.options.guiScale().set(2));
        ctx.runOnClient(mc -> Sky2MConfig.openGui("Bind Key"));
        ctx.waitTicks(20);
        // Slot Binding is in Inventory: open it, then its two sections (Inventory & Storage, Item Protect & Slot Binding).
        ctx.runOnClient(mc -> {
            try {
                var field = Sky2MConfig.class.getDeclaredField("editor");
                field.setAccessible(true);
                var editor = (io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor<?>) field.get(null);
                editor.getAllCategories().entrySet().stream().filter(e -> e.getKey().endsWith(".inventory")).findFirst()
                    .ifPresent(e -> editor.setSelectedCategory(e.getValue()));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ctx.waitTicks(5);
        for (int y : new int[]{275, 425}) {
            ctx.getInput().setCursorPos(900, y);
            ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            ctx.waitTicks(5);
        }
        System.out.println("[Sky2M gametest] screenshot keybind-bind-key -> " + ctx.takeScreenshot("keybind-bind-key"));
        ctx.runOnClient(mc -> Sky2MConfig.openGui(""));
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
    }

    private static void check(boolean ok, String failure) {
        if (!ok) throw new AssertionError("[Sky2M keybinds] " + failure);
    }
}
