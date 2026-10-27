package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.gui.Sky2MHudEditorScreen;
import com.epic60869.sky2m.input.Sky2MKeyMappings;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Global search overlay: one search box over every setting, category, section, HUD and /s2 command.
 * Opened by /s2 find [text], /s2 search &lt;text&gt; or an (unbound by default) key. Click a result to open the
 * settings at it; click a setting's switch to turn it on or off right here.
 */
public final class GlobalSearchScreen extends Screen {
    private static final int ROW = 22;
    private static final int GREY = 0xFF7A8290;
    private static KeyMapping key;

    private final Screen parent;
    private List<GlobalSearchIndex.Entry> all;
    private List<GlobalSearchIndex.Entry> results = new ArrayList<>();
    private EditBox box;
    private String query;
    private int scroll;
    private int selected;

    public GlobalSearchScreen(Screen parent, String query) {
        super(Component.literal("Sky2M Search"));
        this.parent = parent;
        this.query = query == null ? "" : query;
        List<GlobalSearchIndex.Entry> index = GlobalSearchIndex.cachedOrBuild();
        this.all = index == null ? List.of() : index;
        this.waiting = index == null;
        refresh();
    }

    private boolean waiting;

    @Override
    public void tick() {
        super.tick();
        if (waiting) {
            List<GlobalSearchIndex.Entry> index = GlobalSearchIndex.cachedOrBuild();
            if (index != null) {
                waiting = false;
                all = index;
                int keepSelected = selected;
                refresh();
                selected = Math.min(keepSelected, Math.max(0, results.size() - 1));
            }
        }
    }

    /** Called from Sky2MKeyMappings.init(): keys must be registered before the game options load. */
    public static void registerKey() {
        key = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.global_search", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), Sky2MKeyMappings.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (key.consumeClick()) {
                if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new GlobalSearchScreen(null, ""));
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                // /s2 search keeps opening the storage search; /s2 search <text> and /s2 find [text] open this.
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("find")
                        .executes(c -> Compat.queueOpenScreen(new GlobalSearchScreen(null, "")))
                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                            .executes(c -> Compat.queueOpenScreen(new GlobalSearchScreen(null, StringArgumentType.getString(c, "text"))))))
                    .then(ClientCommands.literal("search")
                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                            .executes(c -> Compat.queueOpenScreen(new GlobalSearchScreen(null, StringArgumentType.getString(c, "text")))))));
            }
        });
    }

    private void refresh() {
        results = GlobalSearchIndex.search(all, query, 200);
        scroll = 0;
        selected = 0;
    }

    private int left() { return Math.max(10, width / 2 - 200); }
    private int panelW() { return Math.min(400, width - 20); }
    private int top() { return 10; }
    private int rowsTop() { return top() + 40; }
    private int rowsBottom() { return height - 24; }
    private int visibleRows() { return Math.max(1, (rowsBottom() - rowsTop()) / ROW); }
    private int switchX() { return left() + panelW() - 8 - 26; }

    @Override
    protected void init() {
        box = new EditBox(font, left() + 8, top() + 14, panelW() - 16, 18, Component.literal("Search"));
        box.setHint(Component.literal("Search settings, HUDs and commands").withStyle(ChatFormatting.DARK_GRAY));
        box.setMaxLength(100);
        box.setValue(query);
        box.setResponder(text -> {
            query = text;
            refresh();
        });
        addRenderableWidget(box);
        setInitialFocus(box);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xC0000000);
        g.fill(left(), top(), left() + panelW(), height - 8, 0xFF181B21);
        g.fill(left(), top(), left() + panelW(), top() + 2, 0xFF55FFFF);
        g.text(font, Component.literal("Search").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), left() + 8, top() + 4, 0xFFFFFFFF, true);

        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, results.size() - visible)));
        if (results.isEmpty()) {
            g.text(font, query.isBlank() ? "Type to search." : "Nothing matches \"" + query.trim() + "\".", left() + 8, rowsTop() + 4, GREY, false);
        }
        for (int i = scroll; i < results.size() && i < scroll + visible; i++) {
            GlobalSearchIndex.Entry e = results.get(i);
            int y = rowsTop() + (i - scroll) * ROW;
            boolean hover = mouseX >= left() + 4 && mouseX < left() + panelW() - 4 && mouseY >= y && mouseY < y + ROW - 1;
            if (hover || i == selected) g.fill(left() + 4, y, left() + panelW() - 4, y + ROW - 1, hover ? 0xFF2B303B : 0xFF222630);
            int textW = panelW() - 16 - 80;
            g.text(font, font.plainSubstrByWidth(label(e), textW), left() + 8, y + 2, 0xFFFFFFFF, false);
            String sub = e.path() == null || e.path().isEmpty() ? e.desc() : e.path() + (e.desc() == null || e.desc().isEmpty() ? "" : "  -  " + e.desc());
            g.text(font, font.plainSubstrByWidth(sub == null ? "" : sub, textW), left() + 8, y + 12, GREY, false);
            drawRight(g, e, y, mouseX, mouseY);
        }
        g.text(font, font.plainSubstrByWidth("Click a result for its settings. Click a switch to toggle it here. Up/Down + Enter also work.", panelW() - 16),
            left() + 8, height - 20, GREY, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private static String label(GlobalSearchIndex.Entry e) {
        return switch (e.kind()) {
            case CATEGORY -> e.name() + " (category)";
            case SECTION -> e.name() + " (section)";
            case HUD -> e.name() + " (HUD)";
            default -> e.name();
        };
    }

    private void drawRight(GuiGraphicsExtractor g, GlobalSearchIndex.Entry e, int y, int mouseX, int mouseY) {
        int right = left() + panelW() - 8;
        if (e.isToggle()) {
            boolean on = e.on();
            int x = switchX();
            g.fill(x, y + 4, x + 26, y + 16, on ? 0xFF2E8B3D : 0xFF4A4F5A);
            int knob = on ? x + 14 : x;
            g.fill(knob + 1, y + 5, knob + 11, y + 15, 0xFFFFFFFF);
        } else if (e.kind() == GlobalSearchIndex.Kind.HUD) {
            drawSmallButton(g, right - 44, y + 3, 44, "Move", mouseX, mouseY);
            boolean on = false;
            for (Sky2MHuds.Element h : Sky2MHuds.elements()) if (h.id().equals(e.hudId())) on = Sky2MHuds.turnedOn(h);
            String s = on ? "On" : "Off";
            g.text(font, s, right - 50 - font.width(s), y + 7, on ? 0xFF55FF55 : GREY, false);
        } else if (e.kind() == GlobalSearchIndex.Kind.BUTTON) {
            drawSmallButton(g, right - 44, y + 3, 44, "Run", mouseX, mouseY);
        } else if (e.kind() == GlobalSearchIndex.Kind.SETTING) {
            String v = font.plainSubstrByWidth(e.valueText(), 76);
            g.text(font, v, right - font.width(v), y + 7, 0xFFB8C0CC, false);
        }
    }

    private void drawSmallButton(GuiGraphicsExtractor g, int x, int y, int w, String label, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 16;
        g.fill(x, y, x + w, y + 16, hover ? 0xFF3A4150 : 0xFF262A33);
        g.centeredText(font, Component.literal(label), x + w / 2, y + 4, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || event.y() < rowsTop() || event.y() >= rowsBottom()) return false;
        int index = scroll + (int) ((event.y() - rowsTop()) / ROW);
        if (index < 0 || index >= results.size() || index >= scroll + visibleRows()) return false;
        GlobalSearchIndex.Entry e = results.get(index);
        selected = index;
        double x = event.x();
        int right = left() + panelW() - 8;
        if (e.isToggle() && x >= switchX() && x < switchX() + 26) {
            e.toggle();
            return true;
        }
        if (e.kind() == GlobalSearchIndex.Kind.HUD && x >= right - 44) {
            minecraft.gui.setScreen(new Sky2MHudEditorScreen(this));
            return true;
        }
        if (e.kind() == GlobalSearchIndex.Kind.BUTTON && x >= right - 44) {
            e.run();
            return true;
        }
        activate(e);
        return true;
    }

    private void activate(GlobalSearchIndex.Entry e) {
        if (e.kind() == GlobalSearchIndex.Kind.COMMAND) {
            Minecraft mc = minecraft;
            String command = "s2 " + e.search();
            mc.gui.setScreen(null);
            if (mc.getConnection() != null) mc.getConnection().sendCommand(command);
            return;
        }
        Sky2MConfig.openGui(e.search());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int code = event.key();
        if (code == InputConstants.KEY_DOWN) {
            selected = Math.min(results.size() - 1, selected + 1);
            keepSelectedVisible();
            return true;
        }
        if (code == InputConstants.KEY_UP) {
            selected = Math.max(0, selected - 1);
            keepSelectedVisible();
            return true;
        }
        if ((code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER) && selected >= 0 && selected < results.size()) {
            activate(results.get(selected));
            return true;
        }
        return super.keyPressed(event);
    }

    private void keepSelectedVisible() {
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + visibleRows()) scroll = selected - visibleRows() + 1;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
