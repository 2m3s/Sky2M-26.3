package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.compat.McCompat;
import com.epic60869.sky2m.sim.gui.SettingsButtonWidget;
import com.epic60869.sky2m.sim.gui.profit.ProfitPanels;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /simstats}, and Player Stats on the sim menu: the stats profile {@link SimStats} keeps, as a form.
 *
 * <p>Three columns - the combat stats, the rest of the stats, and the dungeon levels - with a box per number, so a
 * value is typed rather than clicked up a hundred at a time. Under them, what the profile comes to on the current
 * floor (the dungeon bonus applied, see {@link SimStats#effective}) and what a few hits are worth with it, so a
 * change shows its effect before he walks back into a room. Every edit is saved as it is typed.
 *
 * <p>Speed is not a {@link SimStats} stat: its box edits {@code /speed}'s own setting ({@link SimSpeed}).
 */
public final class SimStatsScreen extends Screen {

    private static final int BAR = 30;
    private static final int BOX_W = 58;

    private final Screen parent;
    /** A speed typed in the Speed box, applied on close; -1 for none. */
    private int pendingSpeed = -1;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int rowH;

    /** Labels drawn beside the boxes: {x, y, text}. */
    private final List<Object[]> labels = new ArrayList<>();

    public SimStatsScreen(Screen parent) {
        super(Component.literal("Sim Stats"));
        this.parent = parent;
    }

    /**
     * Opens the screen over {@code parent} - staff only ({@link SimCommandGate}): anyone else is told so and plays
     * with the default stats. Client thread.
     */
    public static void open(net.minecraft.client.Minecraft client, Screen parent) {
        if (!SimCommandGate.isStaff()) {
            com.epic60869.sky2m.sim.util.ModChat.send("Sim",
                    com.epic60869.sky2m.sim.util.ModChat.bad(SimCommandGate.STAFF_ONLY));
            return;
        }
        McCompat.setScreen(client, new SimStatsScreen(parent));
    }

    /** The Player Stats button's label: says so when the screen is staff-only for this player. */
    static Component buttonLabel() {
        return Component.literal(SimCommandGate.isStaff() ? "Player Stats" : "Player Stats (Staff only)");
    }

    @Override
    protected void init() {
        labels.clear();
        panelW = Math.min(this.width - 20, 520);
        panelH = Math.min(this.height - 20, 270);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        // Eight rows in the dungeon column, above two preview lines and the button row.
        rowH = Math.max(16, Math.min(20, (panelH - BAR - 18 - 39 - 26) / 8));
        int colW = (panelW - 30) / 3;
        int top = panelY + BAR + 16;
        SimStats.Profile p = SimStats.profile();

        SimStats.Stat[] first = {SimStats.Stat.HEALTH, SimStats.Stat.DEFENSE, SimStats.Stat.STRENGTH,
                SimStats.Stat.CRIT_CHANCE, SimStats.Stat.CRIT_DAMAGE};
        SimStats.Stat[] second = {SimStats.Stat.INTELLIGENCE, SimStats.Stat.FEROCITY, SimStats.Stat.ABILITY_DAMAGE,
                SimStats.Stat.ATTACK_SPEED, SimStats.Stat.DAMAGE_BONUS};
        int x1 = panelX + 10;
        int x2 = x1 + colW + 5;
        int x3 = x2 + colW + 5;
        for (int i = 0; i < first.length; i++) {
            statRow(x1, top + i * rowH, colW, first[i], p);
        }
        for (int i = 0; i < second.length; i++) {
            statRow(x2, top + i * rowH, colW, second[i], p);
        }
        // Speed: /speed's setting, edited in place.
        // Applied when the screen closes, not per keystroke: typing 550 would otherwise set 5, 55 and 550.
        numberRow(x2, top + second.length * rowH, colW, "Speed ✦",
                String.valueOf(pendingSpeed > 0 ? pendingSpeed : SimSpeed.saved()), v -> {
                    int speed = (int) Math.round(v);
                    pendingSpeed = speed >= 1 && speed <= 10_000 ? speed : -1;
                });

        // Hypixel ghosts, or the old instant teleport home (SimDeath).
        addRenderableWidget(SettingsButtonWidget.builder(deathLabel(SimDeath.ghostMode()), b -> {
            boolean on = !SimDeath.ghostMode();
            SimDeath.setGhostMode(on);
            b.setMessage(deathLabel(on));
        }).bounds(x2, top + (second.length + 1) * rowH, colW, rowH - 2).build());

        numberRow(x3, top, colW, "Catacombs", String.valueOf(p.catacombs()),
                v -> SimStats.setCatacombs((int) Math.round(v)));
        SimStats.DungeonClass[] classes = SimStats.DungeonClass.values();
        for (int i = 0; i < classes.length; i++) {
            SimStats.DungeonClass c = classes[i];
            numberRow(x3, top + (i + 1) * rowH, colW, c.label, String.valueOf(p.classLevel(c)),
                    v -> SimStats.setClassLevel(c, (int) Math.round(v)));
        }
        int classY = top + (classes.length + 1) * rowH;
        // Through SimClassKit, so the class's armour goes on with it inside the sim.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Class: " + p.selected().label), b -> {
            SimStats.DungeonClass next = classes[(SimStats.profile().selected().ordinal() + 1) % classes.length];
            SimClassKit.select(this.minecraft, next);
            b.setMessage(Component.literal("Class: " + next.label));
        }).secondaryPress(b -> {
            SimStats.DungeonClass prev = classes[(SimStats.profile().selected().ordinal() + classes.length - 1)
                    % classes.length];
            SimClassKit.select(this.minecraft, prev);
            b.setMessage(Component.literal("Class: " + prev.label));
        }).bounds(x3, classY, colW, rowH - 2).build());
        addRenderableWidget(SettingsButtonWidget.builder(manaLabel(p.manaCosts()), b -> {
            boolean on = !SimStats.profile().manaCosts();
            SimStats.setManaCosts(on);
            b.setMessage(manaLabel(on));
        }).bounds(x3, classY + rowH, colW, rowH - 2).build());

        int by = panelY + panelH - 26;
        int bw = (panelW - 20 - 12) / 3;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Import levels from my profile"), b -> {
            b.active = false;
            SimStats.importFromProfile(this.minecraft, () -> {
                if (this.minecraft != null && McCompat.screen(this.minecraft) == this) {
                    rebuildWidgets();
                }
            });
        }).bounds(panelX + 10, by, bw, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset to defaults"), b -> {
            SimStats.resetToDefaults();
            rebuildWidgets();
        }).bounds(panelX + 10 + bw + 6, by, bw, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .primary().bounds(panelX + 10 + 2 * (bw + 6), by, panelW - 20 - 2 * (bw + 6), 20).build());
    }

    private static Component deathLabel(boolean ghosts) {
        return Component.literal("Deaths: " + (ghosts ? "§aGhost" : "§eTeleport"));
    }

    private static Component manaLabel(boolean on) {
        return Component.literal("Mana costs: " + (on ? "§aON" : "§cOFF"));
    }

    private void statRow(int x, int y, int w, SimStats.Stat stat, SimStats.Profile p) {
        numberRow(x, y, w, stat.label + " " + stat.symbol, plain(p.get(stat)), v -> SimStats.set(stat, v));
    }

    /** A label and a number box; {@code onValue} gets every value that parses. */
    private void numberRow(int x, int y, int w, String label, String value,
                           java.util.function.DoubleConsumer onValue) {
        labels.add(new Object[]{x, y + (rowH - 2 - 8) / 2, label});
        EditBox box = new com.epic60869.sky2m.sim.gui.SimTextField(this.font, x + w - BOX_W, y, BOX_W, rowH - 2, Component.literal(label));
        box.setMaxLength(9);
        box.setValue(value);
        box.setResponder(t -> {
            try {
                if (!t.isEmpty()) {
                    onValue.accept(Double.parseDouble(t.replace(",", "")));
                }
            } catch (NumberFormatException ignored) {
                // A half-typed number ("1.") waits for the next key.
            }
        });
        addRenderableWidget(box);
    }

    private static String plain(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ProfitPanels.frame(g, this.font, this.width, this.height, panelX, panelY, panelW, panelH, BAR,
                "Player Stats");
        int colW = (panelW - 30) / 3;
        int headY = panelY + BAR + 4;
        g.text(this.font, "BASE STATS (no gear or pet)", panelX + 10, headY, ProfitPanels.ACCENT, false);
        g.text(this.font, "DUNGEON", panelX + 10 + 2 * (colW + 5), headY, ProfitPanels.ACCENT, false);
        for (Object[] l : labels) {
            String text = this.font.plainSubstrByWidth((String) l[2], colW - BOX_W - 4);
            g.text(this.font, text, (int) l[0], (int) l[1], ProfitPanels.TEXT, false);
        }
        drawPreview(g, panelY + panelH - 26 - 48, panelW - 20);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** What the profile comes to on the current floor, and what it hits for. */
    /** "Necron's Leggings 10✪" without colours, for the gear line. */
    private static String shortName(SimItemBuild b) {
        SimItemData.Def d = SimItemData.def(b.dataId());
        String n = d == null ? b.id() : d.name();
        return n + (b.totalStars() > 0 ? " " + b.totalStars() + "✪" : "");
    }

    private void drawPreview(GuiGraphicsExtractor g, int y, int width) {
        String floor = SimState.floorLabel();
        if (floor == null || floor.isBlank()) {
            floor = "M7";
        }
        SimStats.Profile p = SimStats.profile();
        // The always-on talismans (SimAccessories), above the gear line.
        g.text(this.font, this.font.plainSubstrByWidth(SimAccessories.summary(floor), width), panelX + 10, y,
                ProfitPanels.DIM, false);
        y += 11;
        // What the wardrobe adds on top of the boxes above (SimGear), so base and gear read apart.
        SimStats.DungeonClass c = p.selected();
        // Per piece: the worn wardrobe slot's four armour builds and four equipment builds, and the pet's build.
        SimGear.ClassGear cg = SimGear.classGear(c);
        StringBuilder pieces = new StringBuilder();
        for (SimItemBuild b : cg.armour()) {
            pieces.append(pieces.length() == 0 ? "" : ", ").append(b.isEmpty() ? "-" : shortName(b));
        }
        int eq = 0;
        for (SimItemBuild b : cg.equipment()) {
            eq += b.isEmpty() ? 0 : 1;
        }
        String gear = String.format(Locale.US, "Slot %s [%s] +%d equipment, %s: %s",
                cg.active() < 0 ? "-" : String.valueOf(cg.active() + 1), pieces, eq, cg.pet().label(),
                SimGear.summary(SimGear.gearTotals(c, floor)));
        g.text(this.font, this.font.plainSubstrByWidth(gear, width), panelX + 10, y, ProfitPanels.GOOD, false);
        y += 11;
        SimStats.Effective bare = SimStats.effective(p, null, floor);
        String line1 = String.format(Locale.US, "Base + gear on %s (dungeon items x%.2f at 10 stars): %s❤ %s❈ %s❁ %s☠ %s✎ mana %s",
                floor, 1 + SimItemData.catacombsBonus(p.catacombs()) + 0.5 + (SimStats.masterMode(floor) ? 0.25 : 0)
                        + SimAccessories.MEDALLION, SimDamage.commas(bare.health()),
                SimDamage.commas(bare.defense()), SimDamage.commas(bare.strength()),
                SimDamage.commas(bare.critDamage()), SimDamage.commas(bare.intelligence()),
                SimDamage.commas(bare.mana()));
        SimStats.Effective term = SimStats.effective(p, SimTerminator.ITEM_ID, floor);
        SimStats.Effective hype = SimStats.effective(p, "HYPERION", floor);
        double arrow = SimDamage.normal(term, true);
        double mobHit = SimVitals.mobHit(floor);
        String line2 = String.format(Locale.US,
                "Terminator arrow %s (crit %s)   Wither Impact %s   %s mob hit %s -> %s",
                SimHealth.shortHealth(arrow), SimHealth.shortHealth(arrow * (1 + term.critDamage() / 100.0)),
                SimHealth.shortHealth(SimDamage.magic(hype, SimDamage.IMPLOSION_BASE, SimDamage.IMPLOSION_SCALING)),
                floor, SimDamage.commas(mobHit), SimDamage.commas(mobHit * SimVitals.throughDefense(bare.defense())));
        g.text(this.font, this.font.plainSubstrByWidth(line1, width), panelX + 10, y, ProfitPanels.TEXT, false);
        g.text(this.font, this.font.plainSubstrByWidth(line2, width), panelX + 10, y + 11, ProfitPanels.DIM, false);
    }

    @Override
    public void onClose() {
        if (pendingSpeed > 0 && pendingSpeed != SimSpeed.saved()) {
            if (SimState.canAct(this.minecraft)) {
                SimSpeed.set(this.minecraft, pendingSpeed);
            } else {
                // Outside the sim it is only remembered; the sim applies the saved speed when it opens.
                SimSpeed.remember(pendingSpeed);
            }
        }
        pendingSpeed = -1;
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
