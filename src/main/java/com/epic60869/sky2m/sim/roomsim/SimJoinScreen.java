package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.compat.McCompat;
import com.epic60869.sky2m.sim.gui.SettingsButtonWidget;
import com.epic60869.sky2m.sim.gui.SimTextField;
import com.epic60869.sky2m.sim.gui.profit.ProfitPanels;
import com.epic60869.sky2m.sim.relay.SimRelay;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Join Sim Party on the title screen: the {@code /simparty join} a guest would otherwise need a world to type in. A
 * party code goes through the relay, an address (has a dot or a colon) is a direct LAN connection, as in
 * {@link SimParty}. Why a join failed is shown here, since the relay's chat message has no player to reach.
 */
public final class SimJoinScreen extends Screen {

    private static final int BAR = 30;

    private final Screen parent;
    private EditBox codeBox;
    private String codeText = "";
    private String status = "";
    private int statusColour = ProfitPanels.DIM;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public SimJoinScreen(Screen parent) {
        super(Component.literal("Join Sim Party"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, 320);
        panelH = BAR + 92;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int x = panelX + 10;
        int w = panelW - 20;

        codeBox = new SimTextField(this.font, x, panelY + BAR + 18, w, 18, Component.literal("Party code"));
        codeBox.setHint(Component.literal("Party code or LAN address..."));
        codeBox.setMaxLength(64);
        codeBox.setValue(codeText);
        codeBox.setResponder(v -> codeText = v);
        addRenderableWidget(codeBox);
        setInitialFocus(codeBox);

        int by = panelY + panelH - 26;
        int bw = (w - 4) / 2;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Join"), b -> join())
                .primary().bounds(x, by, bw, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(x + bw + 4, by, w - bw - 4, 20).build());
    }

    private void join() {
        String text = codeText.trim();
        // A pasted "/simparty join <code>" line works too.
        if (text.toLowerCase(java.util.Locale.ROOT).startsWith("/simparty join ")) {
            text = text.substring("/simparty join ".length()).trim();
        }
        if (text.indexOf('.') < 0 && text.indexOf(':') < 0) {
            if (!text.matches("[A-Za-z0-9-]{3,32}")) {
                show("Not a party code: " + text, 0xFFFF5555);
                return;
            }
            show("Joining party " + text + " through the relay...", ProfitPanels.DIM);
            SimRelay.join(text, port -> {
                ServerData data = new ServerData("Dungeon Sim party", "127.0.0.1:" + port, ServerData.Type.OTHER);
                ConnectScreen.startConnecting(this, this.minecraft, new ServerAddress("127.0.0.1", port), data,
                        false, null);
            }, reason -> this.minecraft.execute(() -> show("Couldn't join: " + reason, 0xFFFF5555)));
            return;
        }
        if (!ServerAddress.isValidAddress(text)) {
            show("Not an address: " + text, 0xFFFF5555);
            return;
        }
        ServerData data = new ServerData("Dungeon Sim party", text, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(text), data, false, null);
    }

    private void show(String line, int colour) {
        status = line;
        statusColour = colour;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ProfitPanels.frame(g, this.font, this.width, this.height, panelX, panelY, panelW, panelH, BAR,
                "Join Sim Party");
        int w = panelW - 20;
        g.text(this.font, this.font.plainSubstrByWidth("Paste the code from the host's /simparty open", w),
                panelX + 10, panelY + BAR + 5, ProfitPanels.TEXT, false);
        if (!status.isEmpty()) {
            g.text(this.font, this.font.plainSubstrByWidth(status, w), panelX + 10, panelY + BAR + 42,
                    statusColour, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }
}
