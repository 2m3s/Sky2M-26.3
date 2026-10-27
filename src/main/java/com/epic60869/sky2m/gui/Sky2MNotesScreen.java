package com.epic60869.sky2m.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import com.epic60869.sky2m.social.Sky2MNotes;

/**
 * /s2 notes: a notepad built on Minecraft's multi-line text editor, so Enter splits lines,
 * Backspace joins them, arrow keys move between lines, and long lines wrap. Saves automatically.
 */
public final class Sky2MNotesScreen extends Screen {
    private static final int MAX_CHARACTERS = 100_000;
    private static final long AUTOSAVE_DELAY_MS = 1500;

    private final Path configDir;
    private MultiLineEditBox editor;
    private String text;
    private long lastEdit;
    private boolean dirty;

    public Sky2MNotesScreen(Path configDir) {
        super(Component.literal("Sky2M Notes"));
        this.configDir = configDir;
    }

    @Override
    protected void init() {
        if (text == null) {
            text = Sky2MNotes.load(configDir).replace("\r\n", "\n").replace('\r', '\n');
        } else if (editor != null) {
            text = editor.getValue(); // keep edits when the window is resized
        }

        int width = Math.min(720, this.width - 40);
        int left = (this.width - width) / 2;
        int top = 40;
        int height = this.height - top - 40;

        editor = MultiLineEditBox.builder()
            .setX(left)
            .setY(top)
            .setPlaceholder(Component.literal("Write your notes here...").withStyle(net.minecraft.ChatFormatting.GRAY))
            .setShowBackground(true)
            .build(font, width, height, Component.literal("Notes"));
        editor.setCharacterLimit(MAX_CHARACTERS);
        editor.setValue(text);
        editor.setValueListener(value -> {
            text = value;
            dirty = true;
            lastEdit = System.currentTimeMillis();
        });
        addRenderableWidget(editor);
        setInitialFocus(editor);

        addRenderableWidget(Button.builder(Component.literal("Save & Close"), b -> onClose())
            .bounds(left + width - 100, this.height - 30, 100, 20).build());
    }

    @Override
    public void tick() {
        // Save shortly after typing stops, so nothing is lost if the game closes.
        if (dirty && System.currentTimeMillis() - lastEdit > AUTOSAVE_DELAY_MS) save();
    }

    private void save() {
        dirty = false;
        Sky2MNotes.save(configDir, text == null ? "" : text);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == InputConstants.KEY_S) {
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        save();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int width = Math.min(720, this.width - 40);
        int left = (this.width - width) / 2;
        g.text(font, Component.literal("Sky2M Notes"), left, 24, 0xFFFFFFFF, true);
        g.text(font, Component.literal(dirty ? "Unsaved changes" : "Saved"), left + 90, 24, dirty ? 0xFFFFD83D : 0xFF55FF55, false);
        g.text(font, Component.literal("Ctrl+S to save, ESC to close"), left, this.height - 24, 0xFFAAAAAA, false);
    }
}
