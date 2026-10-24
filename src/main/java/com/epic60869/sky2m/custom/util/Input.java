package com.epic60869.sky2m.custom.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/**
 * Keyboard and mouse helpers for Minecraft 26.3, which uses SDL instead of GLFW: key codes are SDL's (use
 * {@link InputConstants}'s KEY_ constants), mouse buttons are numbered 1 left, 2 middle, 3 right, and there's no GLFW
 * to ask for key or button state or to move the cursor.
 */
public final class Input {
    private Input() {}

    /** Whether a mouse button (SDL numbering: {@link InputConstants#MOUSE_BUTTON_LEFT} and so on) is held. */
    public static boolean mouseDown(int button) {
        var mouse = Minecraft.getInstance().mouseHandler;
        if (button == InputConstants.MOUSE_BUTTON_LEFT) return mouse.isLeftPressed();
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) return mouse.isRightPressed();
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) return mouse.isMiddlePressed();
        return false;
    }

    /**
     * The mouse button in GLFW's numbering (0 left, 1 right, 2 middle, 3+ side buttons), which MoulConfig's keybind
     * settings use. On 26.3 a mouse bind is stored as -100 plus that number (Sky2MKeybindEditorMixin): stored as the
     * plain number 0-9 it was the same as SDL's keys (A-F are 4-9), so A-F binds read as mouse buttons.
     */
    public static int glfwButton(int sdlButton) {
        return switch (sdlButton) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> 2;
            default -> sdlButton - 1;
        };
    }

    /** Whether a MoulConfig keybind setting is this mouse button (SDL numbering). */
    public static boolean isMouseBind(int bind, int sdlButton) {
        return bindMouseButton(bind) == sdlButton;
    }

    /**
     * A MoulConfig keybind setting's mouse button in SDL numbering, or -1 if it's a key. Mouse binds are -100 plus the
     * GLFW button; 1-3 (right, middle, back) are older mouse binds no key uses. 0 is unset and 4-9 are the keys A-F.
     */
    public static int bindMouseButton(int bind) {
        int glfw = bind >= -100 && bind <= -91 ? bind + 100 : bind >= 1 && bind <= 3 ? bind : -1;
        return switch (glfw) {
            case -1 -> -1;
            case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
            case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
            case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
            default -> glfw + 1;
        };
    }

    /**
     * Whether a key (SDL key code) is held. An unset key (0, SDL's unknown, or -1, which is "unknown" in 26.2 and what
     * settings carried over from it hold) or a code past SDL's keyboard state is never held: InputConstants.isKeyDown
     * reads that state by index, and -1 crashed the game every tick (a Hunting fusion keybind left unset).
     */
    public static boolean keyDown(int key) {
        if (key <= 0 || key == InputConstants.UNKNOWN.getValue()) return false;
        java.nio.ByteBuffer state = org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardState();
        return state != null && key < state.limit() && InputConstants.isKeyDown(key);
    }

    /** A keybind setting's name: "NONE", "Button 4" for a mouse bind, else the key's name ("B", "Left Shift"...). */
    public static String bindName(int bind) {
        if (bind == 0 || bind == -1) return "NONE";
        int mouse = bindMouseButton(bind);
        return mouse >= 0 ? "Button " + (glfwButton(mouse) + 1) : keyName(bind);
    }

    /**
     * Moves mouse binds saved the old way (the plain button number) to -100 plus it, in every keybind setting under
     * {@code root}. Only 1-3 are moved: 4-9 are also the keys A-F, which is what someone saving them meant (the bug
     * made A-F read as mouse buttons). Safe to run on every load: no key uses 1-3. Returns how many it moved.
     */
    public static int migrateMouseBinds(Object root) {
        return migrateMouseBinds(root, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static int migrateMouseBinds(Object node, java.util.Set<Object> seen) {
        if (node == null || !seen.add(node)) return 0;
        int moved = 0;
        for (Class<?> type = node.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !field.isAnnotationPresent(com.google.gson.annotations.Expose.class)) continue;
                try {
                    field.setAccessible(true);
                    if (field.getType() == int.class && field.isAnnotationPresent(io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind.class)) {
                        int bind = field.getInt(node);
                        if (bind >= 1 && bind <= 3) {
                            field.setInt(node, -100 + bind);
                            moved++;
                        }
                    } else if (!field.getType().isPrimitive() && field.getType().getName().startsWith("com.epic60869")) {
                        moved += migrateMouseBinds(field.get(node), seen);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return moved;
    }

    /** The key's name as Minecraft shows it ("A", "Left Shift"...). */
    public static String keyName(int key) {
        return InputConstants.getKey(new KeyEvent(key, 0, 0)).getDisplayName().getString();
    }

    /** Moves the cursor to (x, y) in window coordinates. */
    public static void setCursor(double x, double y) {
        org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow(Minecraft.getInstance().getWindow().handle(), (float) x, (float) y);
    }

    public static double cursorX() {
        return Minecraft.getInstance().mouseHandler.xpos();
    }

    public static double cursorY() {
        return Minecraft.getInstance().mouseHandler.ypos();
    }
}
