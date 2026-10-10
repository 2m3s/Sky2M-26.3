package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import com.epic60869.sky2m.mixin.Sky2MScreenWidgetsInvoker;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.PatternSyntaxException;

/**
 * Misc > Inventory Buttons, ported from Skyblocker's Quick Navigation (https://github.com/SkyblockerMod/Skyblocker,
 * skyblock/quicknav/QuickNav.java, QuickNavButton.java, QuickNavConfirmationButton.java and the QuickNav mixins,
 * LGPL-3.0): up to 14 creative-inventory tabs, 7 above and 7 below every SkyBlock menu, that run a command. Tabs sit
 * behind the menu; the one for the menu you're in (and one you just clicked) is drawn in front, selected. Tabs set to
 * double click (warps) only run on a second click within a second.
 */
public final class InventoryButtons {
    private static final Map<Screen, List<QuickNavButton>> BUTTONS = new WeakHashMap<>();

    private InventoryButtons() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            BUTTONS.remove(screen);
            if (!(screen instanceof AbstractContainerScreen<?>) || client.player == null || client.player.isCreative()) return;
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null || !config.misc.inventoryButtons.enabled || !Sky2MLocation.onSkyblock()) return;
            List<QuickNavButton> buttons = create(config.misc.inventoryButtons, screen.getTitle().getString().trim());
            for (QuickNavButton button : buttons) ((Sky2MScreenWidgetsInvoker) screen).sky2m$addWidget(button);
            BUTTONS.put(screen, buttons);
        });
    }

    private static List<QuickNavButton> create(Sky2MConfig.InventoryButtonsSettings settings, String title) {
        Sky2MConfig.QuickNavButton[] all = {settings.button1, settings.button2, settings.button3, settings.button4,
            settings.button5, settings.button6, settings.button7, settings.button8, settings.button9, settings.button10,
            settings.button11, settings.button12, settings.button13, settings.button14};
        List<QuickNavButton> buttons = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            Sky2MConfig.QuickNavButton info = all[i];
            if (info == null || !info.render) continue;
            boolean selected = false;
            try {
                selected = info.uiTitle != null && title.matches(info.uiTitle);
            } catch (PatternSyntaxException e) {
                var player = Minecraft.getInstance().player;
                if (player != null) player.sendSystemMessage(Compat.PREFIX.get().append(Component.literal("Invalid regex in Inventory Button " + (i + 1) + "!").withStyle(ChatFormatting.RED)));
            }
            buttons.add(new QuickNavButton(i, selected, info.command, icon(info.icon), info.tooltip, info.doubleClick));
        }
        return buttons;
    }

    /** "minecraft:bone", "skull:<texture>", a SkyBlock id, each optionally "#<colour>" for dyed leather. */
    static ItemStack icon(String text) {
        if (text == null || text.isBlank()) return new ItemStack(Items.BARRIER);
        String spec = text.trim();
        Integer dye = null;
        int hash = spec.lastIndexOf('#');
        if (hash > 0 && !spec.startsWith("skull:")) {
            try {
                dye = Integer.parseInt(spec.substring(hash + 1));
            } catch (NumberFormatException ignored) {}
            spec = spec.substring(0, hash);
        }
        ItemStack stack;
        if (spec.startsWith("skull:")) {
            stack = Compat.createSkull(spec.substring("skull:".length()));
        } else if (spec.contains(":") || spec.equals(spec.toLowerCase())) {
            Identifier id = Identifier.tryParse(spec.contains(":") ? spec : "minecraft:" + spec);
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
            stack = new ItemStack(item == Items.AIR ? Items.BARRIER : item);
        } else {
            stack = RepoItems.itemStack(spec);
        }
        if (dye != null) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(dye));
        return stack;
    }

    /** Before the menu's background: the unselected tabs, so the menu covers their lower edge. */
    public static void renderUnselected(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        List<QuickNavButton> buttons = BUTTONS.get(screen);
        if (buttons == null || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c && com.epic60869.sky2m.features.dungeons.LeapMenu.isActive(c)) return;
        for (QuickNavButton button : buttons) {
            if (!button.toggled() || button.alpha < 255) {
                button.renderInFront = false;
                button.extractRenderState(graphics, mouseX, mouseY, delta);
            }
        }
    }

    /** After the menu's background: the selected tab, joined to the menu like the creative inventory's. */
    public static void renderSelected(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        List<QuickNavButton> buttons = BUTTONS.get(screen);
        if (buttons == null || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c && com.epic60869.sky2m.features.dungeons.LeapMenu.isActive(c)) return;
        for (QuickNavButton button : buttons) {
            if (button.toggled()) {
                button.renderInFront = true;
                button.extractRenderState(graphics, mouseX, mouseY, delta);
            }
        }
    }

    static final class QuickNavButton extends AbstractWidget {
        private static final long TOGGLE_DURATION = 1000;
        private static final long DOUBLE_CLICK_TIME = 1000;
        private static final Tooltip CONFIRM_TOOLTIP = Tooltip.create(Component.literal("Click again to confirm"));

        private final int index;
        private final boolean selected;
        private final String command;
        private final ItemStack icon;
        private final Tooltip tooltip;
        private final boolean doubleClick;
        private boolean temporaryToggled;
        private long toggleTime;
        private long lastClicked;
        private boolean showingConfirm;
        boolean renderInFront;
        int alpha = 255;

        QuickNavButton(int index, boolean selected, String command, ItemStack icon, String tooltip, boolean doubleClick) {
            super(0, 0, 26, 32, Component.empty());
            this.index = index;
            this.selected = selected;
            this.command = command;
            this.icon = icon;
            this.doubleClick = doubleClick;
            this.tooltip = tooltip == null || tooltip.isEmpty() ? null : Tooltip.create(Component.literal(tooltip));
            setTooltip(this.tooltip);
            setTooltipDelay(Duration.ofMillis(100));
        }

        boolean toggled() {
            return selected || temporaryToggled;
        }

        private boolean topTab() {
            return index < 7;
        }

        private void updateCoordinates() {
            if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
            Sky2MContainerScreenAccessor accessor = (Sky2MContainerScreenAccessor) screen;
            int x = accessor.sky2m$getLeftPos();
            int y = accessor.sky2m$getTopPos();
            int h = accessor.sky2m$getImageHeight();
            if (screen instanceof ContainerScreen) h--; // their height is one too many
            int w = accessor.sky2m$getImageWidth();
            setX(x + index % 7 * 25 + w / 2 - 176 / 2);
            setY(topTab() ? y - 28 : y + h - 4);
        }

        @Override
        public void onClick(MouseButtonEvent click, boolean doubled) {
            if (doubleClick && !isDoubleClick()) {
                lastClicked = System.currentTimeMillis();
                return;
            }
            if (temporaryToggled) return;
            temporaryToggled = true;
            toggleTime = System.currentTimeMillis();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (command == null || command.isBlank()) {
                mc.player.sendSystemMessage(Compat.PREFIX.get().append(Component.literal("Inventory Button " + (index + 1) + " has no command!").withStyle(ChatFormatting.RED)));
            } else if (command.trim().startsWith("/")) {
                mc.player.connection.sendCommand(command.trim().substring(1));
            } else {
                mc.player.connection.sendChat(command.trim());
            }
            alpha = 0;
        }

        private boolean isDoubleClick() {
            return System.currentTimeMillis() - lastClicked < DOUBLE_CLICK_TIME;
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            if (!doubleClick || isDoubleClick()) {
                super.playDownSound(soundManager);
                return;
            }
            soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, 1.0f));
        }

        /** Like the creative inventory's tabs, these aren't reached with Tab. */
        @Override
        public ComponentPath nextFocusPath(FocusNavigationEvent navigation) {
            return null;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            updateCoordinates();
            if (temporaryToggled && System.currentTimeMillis() - toggleTime >= TOGGLE_DURATION) temporaryToggled = false;
            if (alpha < 255) alpha = Math.min(alpha + 10, 255);
            if (doubleClick && !toggled() && isDoubleClick() != showingConfirm) {
                showingConfirm = !showingConfirm;
                setTooltip(showingConfirm ? CONFIRM_TOOLTIP : tooltip);
            }
            String state = renderInFront ? "selected" : "unselected";
            Identifier texture = Identifier.withDefaultNamespace("container/creative_inventory/tab_" + (topTab() ? "top" : "bottom") + "_" + state + "_" + (index % 7 + 1));
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, texture, getX(), getY(), width, height, renderInFront ? ARGB.color(alpha, -1) : -1);
            graphics.item(icon, getX() + 5, getY() + 8 + (topTab() ? 1 : -1));
            handleCursor(graphics);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }
}
