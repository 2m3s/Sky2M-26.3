package com.epic60869.sky2m.features.misc.system;

import com.epic60869.sky2m.features.combat.CombatFeatures;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import com.epic60869.sky2m.features.misc.items.SackValueHistory;

/**
 * /s2 networth: your saved networth as a line graph, one profile at a time, in the same dark panel as Sky2M's other
 * screens. Hover the graph for a snapshot's date, value and change from the one before. /s2 sacks history draws your
 * sacks' daily value ({@link SackValueHistory}) in the same graph; a button switches between the two.
 */
public final class NetworthGraphScreen extends Screen {
    private static final int PANEL = 0xC0101216;
    private static final int PANEL_BORDER = 0xFF2C313A;
    private static final int GRID = 0x30FFFFFF;
    private static final int LINE = 0xFF55FF55;
    private static final int DOWN = 0xFFFF5555;
    private static final int LABEL = 0xFFAAAAAA;

    private final List<String> profiles = new ArrayList<>();
    private String profile;
    private Button profileButton;
    private int panelX, panelY, panelW, panelH;
    /** Sack value history instead of networth. */
    private final boolean sacks;

    public NetworthGraphScreen() {
        this(false);
    }

    public NetworthGraphScreen(boolean sacks) {
        super(Component.literal(sacks ? "Sack Value History" : "Networth History"));
        this.sacks = sacks;
    }

    private List<NetworthHistory.Snapshot> history() {
        return sacks ? SackValueHistory.history() : NetworthHistory.history();
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 32, 520);
        panelX = (width - panelW) / 2;
        panelY = 44;
        panelH = Math.max(100, height - panelY - 36);
        profiles.clear();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<NetworthHistory.Snapshot> all = history();
        for (int i = all.size() - 1; i >= 0; i--) seen.add(all.get(i).profile);
        profiles.addAll(seen);
        if (profile == null || !profiles.contains(profile)) profile = profiles.isEmpty() ? null : profiles.getFirst();

        int y = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Save Now"), b -> {
            if (sacks) {
                SackValueHistory.saveNow();
                rebuildWidgets();
            } else {
                NetworthHistory.snapshot(true);
            }
        }).bounds(width / 2 - 207, y, 100, 20).build());
        profileButton = addRenderableWidget(Button.builder(profileLabel(), b -> {
            if (profiles.size() < 2) return;
            profile = profiles.get((profiles.indexOf(profile) + 1) % profiles.size());
            b.setMessage(profileLabel());
        }).bounds(width / 2 - 103, y, 100, 20).build());
        profileButton.active = profiles.size() > 1;
        addRenderableWidget(Button.builder(Component.literal(sacks ? "Networth" : "Sack Value"),
            b -> minecraft.gui.setScreen(new NetworthGraphScreen(!sacks))).bounds(width / 2 + 1, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 + 105, y, 100, 20).build());
    }

    private Component profileLabel() {
        if (profile == null) return Component.literal("No profile");
        // Sack values are kept under the profile id; a shortened one fits the button.
        return Component.literal(sacks && profile.length() > 12 ? "Profile " + profile.substring(0, 8) : profile);
    }

    private List<NetworthHistory.Snapshot> points() {
        List<NetworthHistory.Snapshot> out = new ArrayList<>();
        for (NetworthHistory.Snapshot s : history()) if (s.profile.equals(profile)) out.add(s);
        out.sort(Comparator.comparingLong(s -> s.time));
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.centeredText(font, getTitle().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), width / 2, 17, 0xFFFFFFFF);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
        g.outline(panelX, panelY, panelW, panelH, PANEL_BORDER);
        List<NetworthHistory.Snapshot> points = points();
        if (points.isEmpty()) {
            String empty = sacks ? "No values yet. Turn on Inventory > Sacks Value and open your sacks."
                : "No snapshots yet. Turn on Misc > Networth History, or press Save Now.";
            g.centeredText(font, Component.literal(empty).withStyle(ChatFormatting.GRAY),
                width / 2, panelY + panelH / 2 - 4, LABEL);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }
        NetworthHistory.Snapshot latest = points.getLast();
        g.centeredText(font, Component.literal(CombatFeatures.formatCoins(latest.total)).withStyle(ChatFormatting.GREEN)
            .append(change(points, 86_400_000L, "in a day")).append(change(points, 7 * 86_400_000L, "in a week")), width / 2, 29, 0xFFFFFFFF);

        int left = panelX + 52, right = panelX + panelW - 12, top = panelY + 12, bottom = panelY + panelH - 22;
        long minT = points.getFirst().time, maxT = latest.time;
        long minV = Long.MAX_VALUE, maxV = Long.MIN_VALUE;
        for (NetworthHistory.Snapshot s : points) {
            minV = Math.min(minV, s.total);
            maxV = Math.max(maxV, s.total);
        }
        long pad = Math.max(1, (maxV - minV) / 10);
        if (maxV == minV) pad = Math.max(1, maxV / 20);
        minV = Math.max(0, minV - pad);
        maxV += pad;

        for (int i = 0; i <= 4; i++) {
            int y = bottom - (bottom - top) * i / 4;
            g.fill(left, y, right, y + 1, GRID);
            String label = CombatFeatures.formatCoins(minV + (maxV - minV) * i / 4.0);
            g.text(font, label, left - 4 - font.width(label), y - 4, LABEL, false);
        }
        String pattern = maxT - minT < 2 * 86_400_000L ? "HH:mm" : "MMM d";
        SimpleDateFormat dates = new SimpleDateFormat(pattern, Locale.US);
        String first = dates.format(new Date(minT)), last = dates.format(new Date(maxT));
        g.text(font, first, left, bottom + 6, LABEL, false);
        if (maxT > minT) g.text(font, last, right - font.width(last), bottom + 6, LABEL, false);

        float[] xs = new float[points.size()], ys = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            NetworthHistory.Snapshot s = points.get(i);
            xs[i] = maxT == minT ? (left + right) / 2f : left + (right - left) * (float) ((s.time - minT) / (double) (maxT - minT));
            ys[i] = bottom - (bottom - top) * (float) ((s.total - minV) / (double) (maxV - minV));
        }
        for (int i = 1; i < points.size(); i++) line(g, xs[i - 1], ys[i - 1], xs[i], ys[i], points.get(i).total >= points.get(i - 1).total ? LINE : DOWN);
        int hovered = -1;
        if (mouseX >= left - 4 && mouseX <= right + 4 && mouseY >= top && mouseY <= bottom + 4) {
            float best = 8;
            for (int i = 0; i < xs.length; i++) {
                float d = Math.abs(xs[i] - mouseX);
                if (d < best) {
                    best = d;
                    hovered = i;
                }
            }
        }
        for (int i = 0; i < points.size(); i++) {
            int size = i == hovered ? 3 : 2;
            g.fill((int) xs[i] - size, (int) ys[i] - size, (int) xs[i] + size, (int) ys[i] + size, i == hovered ? 0xFFFFFFFF : LINE);
        }
        if (hovered >= 0) {
            g.fill((int) xs[hovered], top, (int) xs[hovered] + 1, bottom, 0x50FFFFFF);
            NetworthHistory.Snapshot s = points.get(hovered);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(new SimpleDateFormat("EEE MMM d, HH:mm", Locale.US).format(new Date(s.time))).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal(CombatFeatures.formatCoins(s.total)).withStyle(ChatFormatting.GOLD));
            if (hovered > 0) {
                long diff = s.total - points.get(hovered - 1).total;
                tip.add(Component.literal((diff >= 0 ? "+" : "-") + CombatFeatures.formatCoins(Math.abs(diff)) + " since the one before")
                    .withStyle(diff >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /** "  +12.3M in a day" against the newest snapshot at least {@code span} old. */
    private static Component change(List<NetworthHistory.Snapshot> points, long span, String label) {
        NetworthHistory.Snapshot latest = points.getLast(), base = null;
        for (NetworthHistory.Snapshot s : points) if (latest.time - s.time >= span) base = s;
        if (base == null) return Component.empty();
        long diff = latest.total - base.total;
        return Component.literal("  " + (diff >= 0 ? "+" : "-") + CombatFeatures.formatCoins(Math.abs(diff)) + " " + label)
            .withStyle(diff >= 0 ? ChatFormatting.DARK_GREEN : ChatFormatting.RED);
    }

    private static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int colour) {
        float dx = x2 - x1, dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.5f) return;
        g.pose().pushMatrix();
        g.pose().translate(x1, y1);
        g.pose().rotate((float) Math.atan2(dy, dx));
        g.fill(0, -1, (int) Math.ceil(length), 1, colour);
        g.pose().popMatrix();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
