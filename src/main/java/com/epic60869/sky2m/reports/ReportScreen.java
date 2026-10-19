package com.epic60869.sky2m.reports;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * /s2 bugreport, /s2 suggest and /s2 feedback: a title, a description, and a submit button. The same window for all
 * three; only the heading and where it's filed change.
 */
public final class ReportScreen extends Screen {
    private final Reports.Type type;
    private EditBox title;
    private MultiLineEditBox description;
    private Button submit;
    private String status = "";
    private int statusColour = 0xFFAAAAAA;
    private boolean sending;
    private String keptTitle = "";
    private String keptDescription = "";
    /** Add the game info (versions, location, recent errors) under the description; on for bug reports. */
    private static boolean attachInfo = true;

    public ReportScreen(Reports.Type type) {
        super(Component.literal(type.label));
        this.type = type;
    }

    @Override
    protected void init() {
        if (title != null) keptTitle = title.getValue();
        if (description != null) keptDescription = description.getValue();
        int w = Math.min(420, width - 40);
        int x = (width - w) / 2;
        int y = 50;

        title = new EditBox(font, x, y + 12, w, 20, Component.literal("Title"));
        title.setMaxLength(Reports.TITLE_MAX);
        title.setHint(Component.literal(type == Reports.Type.BUG ? "What's broken?" : "In a few words").withStyle(ChatFormatting.DARK_GRAY));
        title.setValue(keptTitle);
        addRenderableWidget(title);
        setInitialFocus(title);

        int descTop = y + 50;
        int descH = Math.max(60, height - descTop - 60);
        description = MultiLineEditBox.builder()
            .setX(x).setY(descTop)
            .setPlaceholder(Component.literal(type == Reports.Type.BUG
                ? "What happened, what you expected, and how to make it happen again."
                : "Tell us more.").withStyle(ChatFormatting.DARK_GRAY))
            .setShowBackground(true)
            .build(font, w, descH, Component.literal("Description"));
        description.setCharacterLimit(4000);
        description.setValue(keptDescription);
        addRenderableWidget(description);

        submit = addRenderableWidget(Button.builder(Component.literal("Submit " + type.label), b -> send())
            .bounds(x + w - 150, descTop + descH + 10, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(x, descTop + descH + 10, 80, 20).build());
        if (type == Reports.Type.BUG) {
            addRenderableWidget(Button.builder(attachLabel(), b -> {
                attachInfo = !attachInfo;
                b.setMessage(attachLabel());
            }).bounds(x + 84, descTop + descH + 10, w - 84 - 154, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                    "Adds your Sky2M and Minecraft versions, where you are on SkyBlock and the latest warnings and errors "
                        + "from your game log (never chat) under the description, so the team can find the bug.")))
                .build());
        }
    }

    private static Component attachLabel() {
        return Component.literal("Attach game info: " + (attachInfo ? "ON" : "OFF"))
            .withStyle(attachInfo ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private void send() {
        String t = title.getValue().trim();
        String d = description.getValue().trim();
        if (t.isEmpty()) {
            status = "Give it a title first.";
            statusColour = 0xFFFF5555;
            return;
        }
        if (sending) return;
        sending = true;
        submit.active = false;
        status = "Sending...";
        statusColour = 0xFFAAAAAA;
        if (type == Reports.Type.BUG && attachInfo) d = d + ReportAttachment.build(Reports.DESCRIPTION_MAX - d.length());
        Reports.submit(type, t, d).thenAccept(result -> minecraft.execute(() -> {
            sending = false;
            if (result.ok()) {
                MutableComponent thanks = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("Thanks! Your " + type.label.toLowerCase() + " was sent. ").withStyle(ChatFormatting.GREEN));
                if (!result.threadId().isEmpty()) {
                    // A thread: the team can answer it, and the player gets a line in chat when they do.
                    thanks.append(Component.literal("You'll get a message here when someone replies. ").withStyle(ChatFormatting.GRAY))
                        .append(Reports.viewButton("/s2 reports", "View your reports"));
                }
                Reports.say(thanks);
                keptTitle = "";
                keptDescription = "";
                minecraft.gui.setScreen(null);
            } else {
                submit.active = true;
                status = result.message().isBlank() ? "Couldn't send it; try again in a moment." : result.message();
                statusColour = 0xFFFF5555;
            }
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int w = Math.min(420, width - 40);
        int x = (width - w) / 2;
        g.centeredText(font, Component.literal(type.label).withStyle(ChatFormatting.BOLD), width / 2, 20, 0xFFFFFFFF);
        g.text(font, Component.literal("Title:"), x, 50, 0xFFE0E0E0, false);
        g.text(font, Component.literal("Description:"), x, 88, 0xFFE0E0E0, false);
        if (!status.isEmpty()) g.centeredText(font, Component.literal(status), width / 2, height - 22, statusColour);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
