package com.epic60869.sky2m.features.misc;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.mixin.Sky2MClientTextTooltipAccessor;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.List;
import java.util.Objects;

/**
 * Scrollable tooltips, ported from Skysoft's Tooltip Scroll (LGPL-3.0): the mouse wheel (and optionally WASD /
 * Page Up / Page Down) pans the tooltip you're hovering, with smooth movement, so long tooltips can be read.
 * The pan resets when you hover something else. Sky2MTooltipMixin wraps the tooltip positioner with
 * {@link #decorate}; Sky2MTooltipScrollMixin hands mouse wheel input to {@link #onMouseScroll}.
 * When Skysoft itself is installed this stays off, so the two don't both move the tooltip.
 */
public final class ScrollableTooltips {
    private static final boolean SKYSOFT = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("skysoft");
    private static final long VISIBILITY_GRACE_NANOS = 250_000_000L;
    private static final int EDGE_GAP = 4;
    private static final int ANCHOR_TOLERANCE = 12;
    private static final double SETTLE_TOLERANCE = 0.05;

    private static PanSession session;
    private static boolean wasResetKeyDown;

    private ScrollableTooltips() {}

    private static Sky2MConfig.TooltipScroll config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.tooltipScroll;
    }

    private static boolean enabled(Sky2MConfig.TooltipScroll settings) {
        if (SKYSOFT || settings == null || !settings.enabled) return false;
        Screen screen = Minecraft.getInstance().gui.screen();
        return !(screen instanceof ChatScreen) || settings.enabledInChat;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> updateKeyboardPan());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> clear());
    }

    public static void clear() {
        session = null;
        wasResetKeyDown = false;
    }

    /** Wraps the vanilla positioner so the tooltip it places is moved by the current pan. */
    public static ClientTooltipPositioner decorate(List<ClientTooltipComponent> components, int anchorX, int anchorY, ClientTooltipPositioner original) {
        if (!enabled(config()) || components.isEmpty()) return original;
        int identity = identity(components);
        return (screenWidth, screenHeight, x, y, width, height) ->
            place(original, identity, anchorX, anchorY, screenWidth, screenHeight, x, y, width, height);
    }

    /**
     * Mouse wheel over a screen; {@code screenScroll} hands it to the screen. A tooltip bigger than the screen pans
     * before the screen scrolls. One that fits pans only when the screen didn't use the wheel, so lists and settings
     * still scroll while an inventory item's tooltip moves. Returns true if the scroll was used.
     */
    public static boolean onMouseScroll(double horizontal, double vertical, java.util.function.BooleanSupplier screenScroll) {
        Sky2MConfig.TooltipScroll settings = config();
        if (!enabled(settings) || !settings.enableScrollWheel || !hasVisibleSession()
            // The storage overlay scrolls with the wheel, and its item tooltips are often taller than the screen: the
            // wheel always scrolls the storage there (the tooltip can still be moved with the keys).
            || com.epic60869.sky2m.features.misc.storage.StorageOverlay.isOverlayScreen(session.screen)) {
            return screenScroll.getAsBoolean();
        }
        if (session.frame.overflows()) return pan(settings, horizontal, vertical) || screenScroll.getAsBoolean();
        return screenScroll.getAsBoolean() || pan(settings, horizontal, vertical);
    }

    private static boolean pan(Sky2MConfig.TooltipScroll settings, double horizontal, double vertical) {
        boolean sideways = horizontal != 0 || isHorizontalModifierDown(settings);
        double x = horizontal * settings.mouseScrollingSpeed;
        double y = 0;
        if (vertical != 0) {
            if (sideways) x += vertical * settings.mouseScrollingSpeed;
            else y = vertical * settings.mouseScrollingSpeed;
        }
        if (settings.invertHorizontal) x = -x;
        if (settings.invertVertical) y = -y;
        if (x == 0 && y == 0) return false;
        session.panBy(x, y);
        return true;
    }

    private static void updateKeyboardPan() {
        Sky2MConfig.TooltipScroll settings = config();
        if (!enabled(settings)) {
            if (session != null) clear();
            return;
        }
        if (!hasVisibleSession()) {
            wasResetKeyDown = false;
            if (settings.resetWhenNotHovered && session != null) session.center();
            return;
        }
        boolean resetDown = isKeyDown(settings.resetTooltipKey);
        if (resetDown && !wasResetKeyDown) session.center();
        wasResetKeyDown = resetDown;

        double speed = settings.keyboardScrollingSpeed;
        double x = 0;
        double y = 0;
        if (settings.enableWASD) {
            if (isKeyDown(InputConstants.KEY_A)) x -= speed;
            if (isKeyDown(InputConstants.KEY_D)) x += speed;
            if (isKeyDown(InputConstants.KEY_W)) y -= speed;
            if (isKeyDown(InputConstants.KEY_S)) y += speed;
        }
        boolean sideways = isHorizontalModifierDown(settings);
        if (isKeyDown(settings.moveUpKey)) {
            if (sideways) x -= speed;
            else y -= speed;
        }
        if (isKeyDown(settings.moveDownKey)) {
            if (sideways) x += speed;
            else y += speed;
        }
        if (x != 0 || y != 0) session.panBy(x, y);
    }

    private static Vector2ic place(ClientTooltipPositioner original, int identity, int anchorX, int anchorY,
                                   int viewportWidth, int viewportHeight, int x, int y, int width, int height) {
        Sky2MConfig.TooltipScroll settings = config();
        Vector2ic base = original.positionTooltip(viewportWidth, viewportHeight, x, y, width, height);
        if (settings == null) return base;
        Screen screen = Minecraft.getInstance().gui.screen();
        Frame frame = new Frame(base.x(), base.y(), width, height, viewportWidth, viewportHeight);
        long now = System.nanoTime();
        boolean expired = !hasVisibleSession(now);
        if (session == null || session.screen != screen) {
            session = new PanSession(screen, identity, anchorX, anchorY, frame, now, settings.startOnTop);
        } else {
            boolean changedTarget = session.isDifferentTarget(identity, anchorX, anchorY);
            session.observe(identity, anchorX, anchorY, frame, now);
            if (settings.resetWhenNotHovered && (expired || changedTarget)) {
                session.center();
                session.alignTallTooltipToTop(settings.startOnTop);
            }
        }
        session.advance(settings.scrollSmoothness / 100.0);
        return new Vector2i(base.x() + session.roundedX(), base.y() + session.roundedY());
    }

    private static boolean hasVisibleSession() {
        return hasVisibleSession(System.nanoTime());
    }

    private static boolean hasVisibleSession(long now) {
        return session != null && session.screen == Minecraft.getInstance().gui.screen()
            && now - session.lastObservedNanos <= VISIBILITY_GRACE_NANOS;
    }

    private static boolean isHorizontalModifierDown(Sky2MConfig.TooltipScroll settings) {
        return (settings.useLeftShift && isKeyDown(InputConstants.KEY_LSHIFT)) || isKeyDown(settings.horizontalMovementKey);
    }

    /** A MoulConfig keybind: a key, or a mouse button (see Input.bindMouseButton). */
    private static boolean isKeyDown(int key) {
        int mouse = com.epic60869.sky2m.custom.util.Input.bindMouseButton(key);
        if (mouse != -1) return com.epic60869.sky2m.custom.util.Input.mouseDown(mouse);
        return key > 9 && com.epic60869.sky2m.custom.util.Input.keyDown(key);
    }

    /** Identifies "the same tooltip": the hovered slot plus the first line's text. */
    private static int identity(List<ClientTooltipComponent> components) {
        Object slot = Minecraft.getInstance().gui.screen() instanceof Sky2MContainerScreenAccessor container ? container.sky2m$getHoveredSlot() : null;
        ClientTooltipComponent title = components.getFirst();
        int result = 31 * Objects.hashCode(slot) + title.getClass().hashCode();
        if (title instanceof Sky2MClientTextTooltipAccessor text) result = 31 * result + textIdentity(text.sky2m$getText());
        return result;
    }

    private static int textIdentity(FormattedCharSequence text) {
        int[] result = {1};
        text.accept((index, style, codePoint) -> {
            result[0] = 31 * result[0] + codePoint;
            return true;
        });
        return result[0];
    }

    /** Where vanilla put the tooltip, and how far it may be panned while keeping an edge on screen. */
    private record Frame(int x, int y, int width, int height, int viewportWidth, int viewportHeight) {
        double clampX(double value) {
            return clamp(value, EDGE_GAP - width - x, viewportWidth - EDGE_GAP - x);
        }

        double clampY(double value) {
            return clamp(value, EDGE_GAP - height - y, viewportHeight - EDGE_GAP - y);
        }

        boolean overflows() {
            return width + 2 * EDGE_GAP > viewportWidth || height + 2 * EDGE_GAP > viewportHeight;
        }

        private static double clamp(double value, int min, int max) {
            if (min > max) return 0;
            return Math.max(min, Math.min(value, max));
        }
    }

    /** The pan of the tooltip currently on screen; the displayed offset slides toward the target. */
    private static final class PanSession {
        final Screen screen;
        private int identity;
        private int anchorX;
        private int anchorY;
        private Frame frame;
        long lastObservedNanos;
        private double targetX;
        private double targetY;
        private double displayedX;
        private double displayedY;

        PanSession(Screen screen, int identity, int anchorX, int anchorY, Frame frame, long observedAt, boolean startOnTop) {
            this.screen = screen;
            this.identity = identity;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.frame = frame;
            this.lastObservedNanos = observedAt;
            clampMotion();
            alignTallTooltipToTop(startOnTop);
        }

        boolean isDifferentTarget(int nextIdentity, int nextAnchorX, int nextAnchorY) {
            return identity != nextIdentity || Math.abs(anchorX - nextAnchorX) > ANCHOR_TOLERANCE
                || Math.abs(anchorY - nextAnchorY) > ANCHOR_TOLERANCE;
        }

        void observe(int nextIdentity, int nextAnchorX, int nextAnchorY, Frame nextFrame, long observedAt) {
            identity = nextIdentity;
            anchorX = nextAnchorX;
            anchorY = nextAnchorY;
            frame = nextFrame;
            lastObservedNanos = observedAt;
            clampMotion();
        }

        void panBy(double x, double y) {
            targetX += x;
            targetY += y;
            clampMotion();
        }

        void center() {
            targetX = targetY = displayedX = displayedY = 0;
        }

        void alignTallTooltipToTop(boolean startOnTop) {
            if (!startOnTop || frame.height() <= frame.viewportHeight() - EDGE_GAP * 2 || frame.y() >= EDGE_GAP) return;
            targetY = EDGE_GAP - frame.y();
            displayedY = targetY;
            clampMotion();
        }

        void advance(double amount) {
            if (amount >= 1) {
                displayedX = targetX;
                displayedY = targetY;
                return;
            }
            displayedX = settle(displayedX + (targetX - displayedX) * amount, targetX);
            displayedY = settle(displayedY + (targetY - displayedY) * amount, targetY);
        }

        private void clampMotion() {
            targetX = frame.clampX(targetX);
            targetY = frame.clampY(targetY);
            displayedX = frame.clampX(displayedX);
            displayedY = frame.clampY(displayedY);
        }

        int roundedX() {
            return (int) Math.round(displayedX);
        }

        int roundedY() {
            return (int) Math.round(displayedY);
        }

        private static double settle(double value, double target) {
            return Math.abs(target - value) < SETTLE_TOLERANCE ? target : value;
        }
    }
}
