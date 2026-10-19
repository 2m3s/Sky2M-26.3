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
     * settings still use on 26.3: they store a mouse button as that number (0-9), or as -100 plus it in older configs.
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
        int glfw = glfwButton(sdlButton);
        return bind == glfw || bind == -100 + glfw;
    }

    /** A MoulConfig keybind setting's mouse button in SDL numbering, or -1 if it's a key. */
    public static int bindMouseButton(int bind) {
        int glfw = bind >= -100 && bind <= -91 ? bind + 100 : bind >= 0 && bind <= 9 ? bind : -1;
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
