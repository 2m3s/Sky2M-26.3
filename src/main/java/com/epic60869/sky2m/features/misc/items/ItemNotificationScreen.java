package com.epic60869.sky2m.features.misc.items;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.mixin.gui.Sky2MMultiLineEditBoxAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Item Notification list editor, like /s2 notes: one item per line. As you type, matching SkyBlock item names
 * are suggested on the right; Tab (or a click) fills in the highlighted one, and the up and down arrows pick another.
 * Saved when you close it.
 */
public final class ItemNotificationScreen extends Screen {
    private static final int MAX_SUGGESTIONS = 12;
    private static final int ROW = 11;

    private final Screen parent;
    private MultiLineEditBox editor;
    private String text;
    private List<String> suggestions = List.of();
    private String suggestedFor = null;
    private int selected;
    private int left, top, editorW, editorH, listX, listW;

    public ItemNotificationScreen(Screen parent) {
        super(Component.literal("Item Notification"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (text == null) {
            Sky2MConfig c = Sky2MConfig.current();
            String saved = c == null ? "" : c.inventory.itemNotification.items;
            text = String.join("\n", ItemNotification.split(saved));
        } else if (editor != null) {
            text = editor.getValue();
        }
        int total = Math.min(760, width - 40);
        left = (width - total) / 2;
        top = 40;
        listW = Math.min(240, total / 3);
        editorW = total - listW - 10;
        editorH = height - top - 44;
        listX = left + editorW + 10;

        editor = MultiLineEditBox.builder()
            .setX(left)
            .setY(top)
            .setPlaceholder(Component.literal("One item per line, e.g.\nEnchanted Diamond\nEnder Artifact").withStyle(ChatFormatting.GRAY))
            .setShowBackground(true)
            .build(font, editorW, editorH, Component.literal("Items"));
        editor.setCharacterLimit(20_000);
        editor.setValue(text);
        editor.setValueListener(value -> text = value);
        addRenderableWidget(editor);
        setInitialFocus(editor);

        addRenderableWidget(Button.builder(Component.literal("Save & Close"), b -> onClose())
            .bounds(left + total - 100, height - 30, 100, 20).build());
    }

    private MultilineTextField field() {
        return ((Sky2MMultiLineEditBoxAccessor) editor).sky2m$textField();
    }

    /** Start and end (exclusive) of the line the cursor is on, in the whole text. */
    private int[] currentLine() {
        String value = editor.getValue();
        int cursor = Math.min(field().cursor(), value.length());
        int start = value.lastIndexOf('\n', cursor - 1) + 1;
        int end = value.indexOf('\n', cursor);
        return new int[]{start, end < 0 ? value.length() : end};
    }

    private String currentWord() {
        int[] line = currentLine();
        return editor.getValue().substring(line[0], line[1]).trim();
    }

    private void updateSuggestions() {
        String typed = currentWord();
        if (typed.equals(suggestedFor)) return;
        suggestedFor = typed;
        selected = 0;
        if (typed.length() < 2) {
            suggestions = List.of();
            return;
        }
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String name : RepoItems.allNames()) {
            String plain = ChatFormatting.stripFormatting(name).trim();
            String p = plain.toLowerCase(Locale.ROOT);
            if (p.equals(lower)) continue;
            if (p.startsWith(lower)) starts.add(name);
            else if (p.contains(lower)) contains.add(name);
        }
        starts.sort((a, b) -> Integer.compare(ChatFormatting.stripFormatting(a).length(), ChatFormatting.stripFormatting(b).length()));
        contains.sort((a, b) -> Integer.compare(ChatFormatting.stripFormatting(a).length(), ChatFormatting.stripFormatting(b).length()));
        List<String> out = new ArrayList<>(starts);
        out.addAll(contains);
        suggestions = out.stream().distinct().limit(MAX_SUGGESTIONS).toList();
    }

    /** Replaces the current line with the chosen item name and moves to the end of it. */
    private void accept(String name) {
        String plain = ChatFormatting.stripFormatting(name).trim();
        int[] line = currentLine();
        String value = editor.getValue();
        String updated = value.substring(0, line[0]) + plain + value.substring(line[1]);
        editor.setValue(updated);
        text = updated;
        field().seekCursor(Whence.ABSOLUTE, line[0] + plain.length());
        suggestedFor = plain;
        suggestions = List.of();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!suggestions.isEmpty()) {
            if (event.key() == InputConstants.KEY_TAB) {
                accept(suggestions.get(selected));
                return true;
            }
            if (event.key() == InputConstants.KEY_DOWN && event.hasControlDown() || event.key() == InputConstants.KEY_DOWN && isAtSuggestions()) {
                selected = (selected + 1) % suggestions.size();
                return true;
            }
            if (event.key() == InputConstants.KEY_UP && (event.hasControlDown() || isAtSuggestions())) {
                selected = (selected + suggestions.size() - 1) % suggestions.size();
                return true;
            }
        }
        if (event.hasControlDown() && event.key() == InputConstants.KEY_S) {
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    /** The arrow keys pick suggestions while the cursor is at the end of the line (where typing happens). */
    private boolean isAtSuggestions() {
        return field().cursor() == currentLine()[1];
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!suggestions.isEmpty() && click.x() >= listX && click.x() <= listX + listW) {
            int index = (int) ((click.y() - (top + 16)) / ROW);
            if (index >= 0 && index < suggestions.size()) {
                accept(suggestions.get(index));
                setFocused(editor);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void save() {
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null) return;
        c.inventory.itemNotification.items = String.join("\n", ItemNotification.split(editor.getValue()));
        Sky2MConfig.saveCurrent(c);
    }

    @Override
    public void onClose() {
        save();
        minecraft.gui.setScreen(parent instanceof net.minecraft.client.gui.screens.ChatScreen ? null : parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        updateSuggestions();
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.text(font, Component.literal("Item Notification").withStyle(ChatFormatting.BOLD), left, 24, 0xFFFFFFFF, true);
        g.text(font, Component.literal("One item per line"), left + 110, 24, 0xFFAAAAAA, false);
        g.text(font, Component.literal("Tab: use suggestion  •  ↑↓: pick one  •  Ctrl+S: save  •  ESC: save and close"), left, height - 24, 0xFFAAAAAA, false);

        // Suggestions panel.
        g.fill(listX, top, listX + listW, top + editorH, 0xC0101216);
        g.outline(listX, top, listW, editorH, 0xFF2C313A);
        g.text(font, Component.literal("Suggestions").withStyle(ChatFormatting.GRAY), listX + 6, top + 4, 0xFFAAAAAA, false);
        if (!RepoItems.itemsLoaded()) {
            g.text(font, Component.literal("Loading items...").withStyle(ChatFormatting.DARK_GRAY), listX + 6, top + 18, 0xFFFFFFFF, false);
            return;
        }
        if (suggestions.isEmpty()) {
            String hint = currentWord().length() < 2 ? "Type an item name..." : "No matching items";
            g.text(font, Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY), listX + 6, top + 18, 0xFFFFFFFF, false);
            return;
        }
        int y = top + 16;
        for (int i = 0; i < suggestions.size(); i++) {
            boolean hovered = mouseX >= listX && mouseX <= listX + listW && mouseY >= y && mouseY < y + ROW;
            if (i == selected || hovered) g.fill(listX + 2, y - 1, listX + listW - 2, y + ROW - 1, i == selected ? 0x50FFFFFF : 0x28FFFFFF);
            String name = suggestions.get(i);
            String shown = font.plainSubstrByWidth(name, listW - 12);
            g.text(font, shown, listX + 6, y + 1, 0xFFFFFFFF, true);
            y += ROW;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
