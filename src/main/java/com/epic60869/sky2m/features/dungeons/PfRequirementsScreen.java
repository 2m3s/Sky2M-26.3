package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /s2 pfreq: Party Finder auto-kick requirements for each floor, as a table (F1-F7, M1-M7): a Use button and boxes for
 * the slowest S+ (seconds), minimum Catacombs, secrets (thousands) and magical power. 0 = don't check. Saved on close.
 */
public final class PfRequirementsScreen extends Screen {
    private static final int ROW = 20;
    private static final String[] HEADERS = {"Floor", "Use", "Slowest S+ (s)", "Min Cata", "Secrets (k)", "Min MP"};

    private int first;
    private final List<Runnable> savers = new ArrayList<>();

    public PfRequirementsScreen() {
        super(Component.literal("Party Finder Requirements"));
    }

    private int panelWidth() { return Math.min(460, width - 20); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return 10; }
    private int rowsTop() { return top() + 58; }
    private int visibleRows() { return Math.max(3, Math.min(PfRequirements.FLOORS.size(), (height - rowsTop() - 40) / ROW)); }

    private int[] columns() {
        int x = left() + 10, w = panelWidth() - 20;
        return new int[]{x, x + w * 12 / 100, x + w * 30 / 100, x + w * 52 / 100, x + w * 68 / 100, x + w * 84 / 100};
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        keep();
        clearWidgets();
        savers.clear();
        int[] c = columns();
        int boxW = Math.max(36, (panelWidth() - 20) * 14 / 100);
        first = Math.max(0, Math.min(first, PfRequirements.FLOORS.size() - visibleRows()));
        for (int i = first; i < PfRequirements.FLOORS.size() && i < first + visibleRows(); i++) {
            String floor = PfRequirements.FLOORS.get(i);
            PfRequirements.Req req = PfRequirements.all().get(floor);
            int y = rowsTop() + (i - first) * ROW;
            addRenderableWidget(Button.builder(useLabel(req.enabled), b -> {
                req.enabled = !req.enabled;
                b.setMessage(useLabel(req.enabled));
            }).bounds(c[1], y, 40, 18).build());
            EditBox pb = box(c[2], y, boxW, req.maxPbSeconds);
            EditBox cata = box(c[3], y, boxW, req.minCata);
            EditBox secrets = box(c[4], y, boxW, req.minSecretsK);
            EditBox mp = box(c[5], y, boxW, req.minMagicalPower);
            savers.add(() -> {
                req.maxPbSeconds = number(pb);
                req.minCata = number(cata);
                req.minSecretsK = number(secrets);
                req.minMagicalPower = number(mp);
            });
        }
        int by = rowsTop() + visibleRows() * ROW + 8;
        addRenderableWidget(Button.builder(Component.literal("Party Finder Settings"), b -> {
            keep();
            PfRequirements.save();
            Sky2MConfig.openGui("Per-Floor Requirements");
        }).bounds(left() + 10, by, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(left() + panelWidth() - 10 - 70, by, 70, 20).build());
    }

    private static Component useLabel(boolean on) {
        return Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private EditBox box(int x, int y, int w, int value) {
        EditBox box = new EditBox(font, x, y + 1, w, 16, Component.literal("Value"));
        box.setMaxLength(6);
        box.setValue(value > 0 ? Integer.toString(value) : "");
        box.setHint(Component.literal("0").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(box);
        return box;
    }

    private static int number(EditBox box) {
        try {
            return Math.max(0, Integer.parseInt(box.getValue().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Copies what's typed into the requirements. */
    private void keep() {
        for (Runnable saver : savers) saver.run();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int left = left(), w = panelWidth();
        int bottom = rowsTop() + visibleRows() * ROW + 36;
        g.fill(0, 0, width, height, 0xFF07090D);
        g.fill(left, top(), left + w, bottom, 0xFF181B21);
        g.fill(left, top(), left + w, top() + 2, 0xFFFF5555);
        g.text(font, Component.literal("Party Finder Requirements").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), left + 10, top() + 8, 0xFFFFFFFF, true);
        var config = Sky2MConfig.current();
        boolean on = config != null && config.dungeons.partyFinderStats.autoKick && config.dungeons.partyFinderStats.perFloor;
        g.text(font, on ? "Auto kick uses the row for the floor you're queued for or in; floors set OFF use the settings."
                : "Turn on Auto Kick and Per-Floor Requirements in the Party Finder settings to use these.",
            left + 10, top() + 22, on ? 0xFFB8C0CC : 0xFFFFAA00, false);
        int[] c = columns();
        for (int i = 0; i < HEADERS.length; i++) g.text(font, HEADERS[i], c[i], rowsTop() - 14, 0xFFFF7777, false);
        g.fill(left + 8, rowsTop() - 4, left + w - 8, rowsTop() - 3, 0xFF3A3F48);
        for (int i = first; i < PfRequirements.FLOORS.size() && i < first + visibleRows(); i++) {
            String floor = PfRequirements.FLOORS.get(i);
            int y = rowsTop() + (i - first) * ROW;
            g.text(font, Component.literal(floor).withStyle(floor.startsWith("M") ? ChatFormatting.RED : ChatFormatting.WHITE, ChatFormatting.BOLD),
                c[0], y + 5, 0xFFFFFFFF, false);
        }
        if (PfRequirements.FLOORS.size() > visibleRows()) {
            g.text(font, "Scroll for more floors", left + w - 10 - font.width("Scroll for more floors"), top() + 8, 0xFF7A8290, false);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int next = Math.max(0, Math.min(first - (int) Math.signum(scrollY), PfRequirements.FLOORS.size() - visibleRows()));
        if (next != first) {
            keep();
            first = next;
            rebuild();
        }
        return true;
    }

    @Override
    public void onClose() {
        keep();
        PfRequirements.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
