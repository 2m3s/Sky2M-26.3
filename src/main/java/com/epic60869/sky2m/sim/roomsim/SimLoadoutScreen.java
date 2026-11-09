package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.compat.McCompat;
import com.epic60869.sky2m.sim.gui.SettingsButtonWidget;
import com.epic60869.sky2m.sim.gui.profit.ProfitPanels;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * {@code /simloadout}, and Class &amp; Loadouts on the sim menu: pick a class, open the wardrobe and pets, and save,
 * load, rename and delete loadout presets ({@link SimLoadout.Preset}) - the whole setup at once.
 */
public final class SimLoadoutScreen extends Screen {

    private static final int BAR = 30;
    private static final int PER_PAGE = 6;
    private static final int ROW = 22;

    private final Screen parent;
    private int page;
    /** The row whose Delete was clicked once and is waiting for the second click, or -1. */
    private int confirmDelete = -1;
    private EditBox nameBox;
    private String nameText = "";
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;

    public SimLoadoutScreen(Screen parent) {
        super(Component.literal("Class & Loadouts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, 480);
        panelH = Math.min(this.height - 20, BAR + 22 + 26 + 24 + 14 + PER_PAGE * ROW + 32);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int x = panelX + 10;
        int w = panelW - 20;

        // The five classes; the selected one is the primary button.
        SimStats.DungeonClass[] classes = SimStats.DungeonClass.values();
        int cw = (w - 4 * 4) / classes.length;
        SimStats.DungeonClass selected = SimStats.profile().selected();
        for (int i = 0; i < classes.length; i++) {
            SimStats.DungeonClass c = classes[i];
            var b = SettingsButtonWidget.builder(Component.literal(c.label + " " + SimStats.profile().classLevel(c)),
                    btn -> {
                        SimClassKit.select(this.minecraft, c);
                        rebuildWidgets();
                    }).bounds(x + i * (cw + 4), panelY + BAR + 4, cw, 20);
            if (c == selected) {
                b.primary();
            }
            addRenderableWidget(b.build());
        }

        int ty = panelY + BAR + 4 + 22 + 26;
        int tw = (w - 3 * 4) / 4;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Wardrobe"), b ->
                McCompat.setScreen(this.minecraft, new SimWardrobeScreen(this, false)))
                .bounds(x, ty, tw, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Pets"), b ->
                McCompat.setScreen(this.minecraft, new SimWardrobeScreen(this, true)))
                .bounds(x + tw + 4, ty, tw, 20).build());
        // Staff only, like /simstats: greyed out for everyone else.
        SettingsButtonWidget stats = SettingsButtonWidget.builder(SimStatsScreen.buttonLabel(), b ->
                SimStatsScreen.open(this.minecraft, this))
                .bounds(x + 2 * (tw + 4), ty, tw, 20).build();
        stats.active = SimCommandGate.isStaff();
        addRenderableWidget(stats);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Default presets"), b -> {
            SimLoadout.restoreDefaultPresets();
            rebuildWidgets();
        }).bounds(x + 3 * (tw + 4), ty, w - 3 * (tw + 4), 20).build());

        listY = ty + 24 + 14;
        List<SimLoadout.Preset> presets = SimLoadout.presets();
        int pages = Math.max(1, (presets.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int bw = 56;
        for (int r = 0; r < PER_PAGE; r++) {
            int idx = page * PER_PAGE + r;
            if (idx >= presets.size()) {
                break;
            }
            SimLoadout.Preset p = presets.get(idx);
            int ry = listY + r * ROW;
            int bx = panelX + panelW - 10 - 3 * bw - 8;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Load"), b -> {
                SimLoadout.loadPreset(this.minecraft, p.name());
                rebuildWidgets();
            }).primary().bounds(bx, ry, bw, 20).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Rename"), b -> {
                String to = nameBox == null ? "" : nameBox.getValue();
                if (to.isBlank()) {
                    b.setMessage(Component.literal("§eType below"));
                    return;
                }
                if (SimLoadout.renamePreset(p.name(), to)) {
                    nameText = "";
                    rebuildWidgets();
                } else {
                    b.setMessage(Component.literal("§cTaken"));
                }
            }).bounds(bx + bw + 4, ry, bw, 20).build());
            int row = idx;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal(confirmDelete == row
                    ? "§cSure?" : "Delete"), b -> {
                if (confirmDelete == row) {
                    SimLoadout.deletePreset(p.name());
                    confirmDelete = -1;
                } else {
                    confirmDelete = row;
                }
                rebuildWidgets();
            }).bounds(bx + 2 * (bw + 4), ry, bw, 20).build());
        }

        int by = panelY + panelH - 26;
        if (pages > 1) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("<"), b -> {
                page = (page + pages - 1) % pages;
                confirmDelete = -1;
                rebuildWidgets();
            }).bounds(x, by, 20, 20).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal(">"), b -> {
                page = (page + 1) % pages;
                confirmDelete = -1;
                rebuildWidgets();
            }).bounds(x + 24, by, 20, 20).build());
        }
        int left = x + (pages > 1 ? 48 : 0);
        int saveW = 110;
        int doneW = 50;
        nameBox = new com.epic60869.sky2m.sim.gui.SimTextField(this.font, left, by + 1, panelX + panelW - 10 - doneW - saveW - 8 - left, 18,
                Component.literal("Preset name"));
        nameBox.setHint(Component.literal("Preset name..."));
        nameBox.setMaxLength(48);
        nameBox.setValue(nameText);
        nameBox.setResponder(v -> nameText = v);
        addRenderableWidget(nameBox);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Save current as"), b -> {
            String name = nameText.isBlank() ? SimStats.profile().selected().label + " - "
                    + SimGear.armourFor(SimStats.profile().selected()).name() : nameText;
            if (SimLoadout.savePreset(this.minecraft, name)) {
                nameText = "";
                rebuildWidgets();
            }
        }).bounds(panelX + panelW - 10 - doneW - 4 - saveW, by, saveW, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelX + panelW - 10 - doneW, by, doneW, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ProfitPanels.frame(g, this.font, this.width, this.height, panelX, panelY, panelW, panelH, BAR,
                "Class & Loadouts");
        int w = panelW - 20;
        SimStats.DungeonClass c = SimStats.profile().selected();
        int[] cds = this.minecraft == null ? new int[]{-1, -1} : SimClassKit.cooldowns(this.minecraft);
        String ability = SimClassKit.abilityName(c) + cooldownText(cds[0]);
        String ult = SimClassKit.ultimateName(c) + cooldownText(cds[1]);
        int y = panelY + BAR + 4 + 22 + 2;
        g.text(this.font, this.font.plainSubstrByWidth("Orb right-click: " + ability + "   left-click / drop: " + ult
                + "   Gear: " + SimGear.armourFor(c).name() + " + " + SimGear.petFor(c).name(), w),
                panelX + 10, y, ProfitPanels.TEXT, false);
        g.text(this.font, this.font.plainSubstrByWidth(SimClassKit.passivesText(c), w), panelX + 10, y + 11,
                ProfitPanels.DIM, false);
        g.text(this.font, "PRESETS (class, armour, pet, hotbar and stats)", panelX + 10, listY - 11,
                ProfitPanels.ACCENT, false);
        List<SimLoadout.Preset> presets = SimLoadout.presets();
        int nameW = panelW - 20 - 3 * 56 - 8 - 6;
        if (presets.isEmpty()) {
            g.text(this.font, "No presets - save one below, or Default presets", panelX + 10, listY + 6,
                    ProfitPanels.DIM, false);
        }
        for (int r = 0; r < PER_PAGE; r++) {
            int idx = page * PER_PAGE + r;
            if (idx >= presets.size()) {
                break;
            }
            SimLoadout.Preset p = presets.get(idx);
            int ry = listY + r * ROW;
            g.fill(panelX + 8, ry - 1, panelX + panelW - 8, ry + 21, r % 2 == 0 ? ProfitPanels.INNER_BG : 0xFF15151C);
            g.text(this.font, this.font.plainSubstrByWidth(p.name(), nameW), panelX + 12, ry + 2,
                    ProfitPanels.TEXT, false);
            g.text(this.font, this.font.plainSubstrByWidth(p.dungeonClass().label + " "
                    + p.profile().classLevel(p.dungeonClass()) + " - " + SimGear.armour(p.armour()).name() + ", "
                    + SimGear.pet(p.pet()).name(), nameW), panelX + 12, ry + 12, ProfitPanels.DIM, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private static String cooldownText(int seconds) {
        return seconds > 0 ? " (" + seconds + "s)" : seconds == 0 ? " (ready)" : "";
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
