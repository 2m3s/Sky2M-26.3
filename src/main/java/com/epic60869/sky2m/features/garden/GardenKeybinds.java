package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.input.Sky2MMouseLock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Garden > Custom Keybinds: while a farming tool is held in the Garden with no menu open, Minecraft's attack, use and
 * movement keys follow the keys set here instead ({@code Sky2MGardenKeybindsMixin}). Keys left unset keep their own.
 */
public final class GardenKeybinds {
    /** Presses of each remapped key not yet taken by Minecraft's consumeClick(). */
    private static final Map<KeyMapping, Integer> clicks = new IdentityHashMap<>();
    private static final Map<KeyMapping, Boolean> wasDown = new IdentityHashMap<>();

    private GardenKeybinds() {}

    public static void init() {
        // Before Minecraft handles its keys this tick, count new presses of the custom keys as clicks.
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (!active()) {
                clicks.clear();
                wasDown.clear();
                return;
            }
            for (KeyMapping mapping : mappings(mc)) {
                int key = customKey(mapping);
                if (key == -1) continue;
                boolean down = isKeyDown(key);
                if (down && !wasDown.getOrDefault(mapping, false)) clicks.merge(mapping, 1, Integer::sum);
                wasDown.put(mapping, down);
            }
        });
    }

    private static FeatureConfigs.GardenKeybinds config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.garden.keybinds;
    }

    /** Custom keys are in use right now. */
    public static boolean active() {
        FeatureConfigs.GardenKeybinds c = config();
        if (c == null || !c.enabled) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null || !Sky2MLocation.inGarden()) return false;
        return Sky2MMouseLock.isFarmingTool(mc.player.getMainHandItem());
    }

    private static KeyMapping[] mappings(Minecraft mc) {
        var o = mc.options;
        return new KeyMapping[] {o.keyAttack, o.keyUse, o.keyUp, o.keyLeft, o.keyDown, o.keyRight, o.keyJump, o.keyShift};
    }

    /** The custom key for one of Minecraft's key mappings, or -1 when it has none. */
    public static int customKey(KeyMapping mapping) {
        FeatureConfigs.GardenKeybinds c = config();
        if (c == null) return -1;
        var o = Minecraft.getInstance().options;
        int key = mapping == o.keyAttack ? c.attack : mapping == o.keyUse ? c.use : mapping == o.keyUp ? c.forward
            : mapping == o.keyLeft ? c.left : mapping == o.keyDown ? c.back : mapping == o.keyRight ? c.right
            : mapping == o.keyJump ? c.jump : mapping == o.keyShift ? c.sneak : -1;
        return key == 0 ? -1 : key;
    }

    /** Whether the mapping is remapped right now (so Minecraft's own key state is replaced). */
    public static boolean overrides(KeyMapping mapping) {
        return customKey(mapping) != -1 && active();
    }

    public static boolean isDown(KeyMapping mapping) {
        return isKeyDown(customKey(mapping));
    }

    public static boolean consumeClick(KeyMapping mapping) {
        int left = clicks.getOrDefault(mapping, 0);
        if (left <= 0) return false;
        clicks.put(mapping, left - 1);
        return true;
    }

    /** A MoulConfig key code: keyboard keys as GLFW codes, mouse buttons as -100 + button. */
    static boolean isKeyDown(int key) {
        if (key == -1 || key == 0) return false;
        long window = Minecraft.getInstance().getWindow().handle();
        if (key < 0) {
            int button = key + 100;
            return button >= GLFW.GLFW_MOUSE_BUTTON_1 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST
                && GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
        }
        return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
