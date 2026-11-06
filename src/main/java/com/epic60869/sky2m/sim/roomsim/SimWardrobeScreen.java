package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.compat.McCompat;
import com.epic60869.sky2m.sim.gui.SettingsButtonWidget;
import com.epic60869.sky2m.sim.gui.profit.ProfitPanels;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /simwardrobe} and {@code /simpets}: the Dungeon Sim's wardrobe, equipment and pets ({@link SimGear}).
 *
 * <p><b>Wardrobe</b>, laid out like Hypixel's page: nine columns, one per wardrobe slot, each the helmet down to the
 * boots and then the slot's equipment (necklace, cloak, belt, gloves). Click a slot's number (or press 1-9) to
 * equip it - armour into the armour slots, equipment on - and click the equipped one to take it off. Click any
 * piece to edit it below: the item (left/right click cycles), stars, reforge, Recombobulator, fragged, potato books
 * and gemstones; "Set" fills the slot's four armour pieces with a whole set.
 *
 * <p><b>Pets</b>: click a pet to put it out (it keeps the item it last held); the pet item and the class's Pet Rule
 * ({@link SimPetRules}: swap to a pet, or toggle between two) are set below, and "Get rod" gives the Pet Rules Rod.
 *
 * <p>Everything is per class - the class button switches - and a party guest's choices go to the host with him.
 */
public final class SimWardrobeScreen extends Screen {

    private static final int COLUMNS = SimGear.SLOTS;
    private static final int CELL = 18;
    private static final int PAD = 4;
    private static final int BAR = 30;
    private static final int ROWS = 8;

    private final Screen parent;
    private boolean pets;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int gridX;
    private int gridY;

    /** The piece being edited: slot 0-8, row 0-7 (0-3 armour, 4-7 equipment). */
    private int selSlot;
    private int selRow = 1;
    /** The set template the "Set" button would put in. */
    private int setIndex;

    private final List<ItemStack[]> icons = new ArrayList<>();

    public SimWardrobeScreen(Screen parent, boolean pets) {
        super(Component.literal(pets ? "Pets" : "Wardrobe"));
        this.parent = parent;
        this.pets = pets;
        int a = SimGear.activeSlot(SimStats.profile().selected());
        this.selSlot = Math.max(0, a);
    }

    private SimStats.DungeonClass cls() {
        return SimStats.profile().selected();
    }

    private SimGear.ClassGear gear() {
        return SimGear.classGear(cls());
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        icons.clear();
        SimGear.ClassGear g = gear();
        if (pets) {
            for (SimGear.Pet p : SimGear.PETS) {
                icons.add(new ItemStack[]{SimGear.petIcon(new SimGear.PetBuild(p, g.itemFor(p.id())))});
            }
        } else {
            for (int s = 0; s < COLUMNS; s++) {
                SimGear.WardrobeSlot w = g.slots().get(s);
                ItemStack[] col = new ItemStack[ROWS];
                for (int r = 0; r < 4; r++) {
                    col[r] = SimGear.buildPiece(w.armour().get(r), r);
                    col[r + 4] = SimGear.equipmentIcon(w.equipment().get(r));
                }
                icons.add(col);
            }
        }
        int rows = pets ? 2 : ROWS;
        int gridW = COLUMNS * CELL + (COLUMNS - 1) * PAD;
        int gridH = rows * CELL + (rows - 1) * 2;
        panelW = Math.max(gridW + 24, 330);
        panelH = BAR + 26 + 12 + gridH + 10 + 2 * 22 + 26 + 28;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(0, (this.height - panelH) / 2);
        gridX = panelX + (panelW - gridW) / 2;
        gridY = panelY + BAR + 26 + 12;

        int x = panelX + 10;
        int y = panelY + BAR + 2;
        int w = (panelW - 20 - 8) / 3;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(pets ? "Wardrobe" : "§6Wardrobe"), b -> {
            pets = false;
            rebuildWidgets();
        }).bounds(x, y, w, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(pets ? "§6Pets" : "Pets"), b -> {
            pets = true;
            rebuildWidgets();
        }).bounds(x + w + 4, y, w, 20).build());
        SimStats.DungeonClass[] classes = SimStats.DungeonClass.values();
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Class: " + cls().label), b -> {
            SimClassKit.select(this.minecraft, classes[(cls().ordinal() + 1) % classes.length]);
            rebuildWidgets();
        }).secondaryPress(b -> {
            SimClassKit.select(this.minecraft, classes[(cls().ordinal() + classes.length - 1) % classes.length]);
            rebuildWidgets();
        }).bounds(x + 2 * (w + 4), y, panelW - 20 - 2 * (w + 4), 20).build());

        int ey = gridY + gridH + 10;
        if (pets) {
            petEditor(ey);
        } else {
            pieceEditor(ey);
        }
        int by = panelY + panelH - 26;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .primary().bounds(panelX + panelW - 80, by, 70, 20).build());
    }

    /** One editor button: left click {@code next}, right click {@code prev}. */
    private void button(int x, int y, int w, String label, Runnable next, Runnable prev) {
        var builder = SettingsButtonWidget.builder(Component.literal(label), b -> {
            next.run();
            rebuildWidgets();
        });
        if (prev != null) {
            builder = builder.secondaryPress(b -> {
                prev.run();
                rebuildWidgets();
            });
        }
        addRenderableWidget(builder.bounds(x, y, w, 20).build());
    }

    private SimItemBuild selected() {
        SimGear.WardrobeSlot w = gear().slots().get(selSlot);
        return selRow < 4 ? w.armour().get(selRow) : w.equipment().get(selRow - 4);
    }

    private void setSelected(SimItemBuild b) {
        boolean armour = selRow < 4;
        SimGear.setPiece(cls(), selSlot, armour, armour ? selRow : selRow - 4, b);
        if (armour && gear().active() == selSlot) {
            SimGear.equip(this.minecraft);
        }
    }

    private void pieceEditor(int y) {
        SimItemBuild b = selected();
        int x = panelX + 10;
        int full = panelW - 20;
        int w = (full - 4 * 4) / 5;
        boolean armour = selRow < 4;
        int piece = armour ? selRow : selRow - 4;
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(armour ? SimItemData.armourOptions(piece) : SimItemData.equipmentOptions(piece));
        int at = Math.max(0, options.indexOf(b.id()));
        // Row 1: item, stars, reforge, recombobulator, fragged
        button(x, y, w, b.isEmpty() ? "Item: none" : "Item", () -> setSelected(itemAt(options, at + 1, b)),
                () -> setSelected(itemAt(options, at - 1, b)));
        if (!b.isEmpty()) {
            boolean crimson = SimItemData.crimson(b.id());
            int max = 10;
            button(x + (w + 4), y, w, (crimson ? "Crimson ✪: " : "Stars: ") + b.totalStars(),
                    () -> setSelected(b.withStars((b.totalStars() + 1) % (max + 1))),
                    () -> setSelected(b.withStars((b.totalStars() + max) % (max + 1))));
            List<String> refs = SimItemData.reforgeOptions(b.id());
            int ri = Math.max(0, refs.indexOf(b.reforge()));
            String rn = SimItemData.reforgeName(b.reforge());
            button(x + 2 * (w + 4), y, w, rn == null ? "No reforge" : rn,
                    () -> setSelected(b.withReforge(refs.get((ri + 1) % refs.size()))),
                    () -> setSelected(b.withReforge(refs.get((ri + refs.size() - 1) % refs.size()))));
            button(x + 3 * (w + 4), y, w, "Recomb: " + (b.recombobulated() ? "§aON" : "§7off"),
                    () -> setSelected(b.withRecombobulator(!b.recombobulated())), null);
            if (SimItemData.hasFragged(b.id())) {
                button(x + 4 * (w + 4), y, w, "Fragged: " + (b.fragged() ? "§aON" : "§7off"),
                        () -> setSelected(b.withFragged(!b.fragged())), null);
            } else if (b.id().equals("SKELETON_MASTER_CHESTPLATE")) {
                button(x + 4 * (w + 4), y, w, "Floor " + (b.skeletonFloor() == 0 ? 10 : b.skeletonFloor()) + " "
                                + b.skeletonQuality() + "%",
                        () -> setSelected(b.withSkeletonRoll(b.skeletonFloor() == 0 ? 10 : b.skeletonFloor(),
                                (b.skeletonQuality() + 10) % 60)),
                        () -> setSelected(b.withSkeletonRoll(((b.skeletonFloor() == 0 ? 10 : b.skeletonFloor()) + 8)
                                % 10 + 1, b.skeletonQuality())));
            }
            // Row 2: books, gems, set, equip
            int y2 = y + 22;
            if (armour) {
                int books = b.hotPotato() + b.fuming();
                int next = books == 0 ? 10 : books == 10 ? 15 : 0;
                int prev = books == 0 ? 15 : books == 15 ? 10 : 0;
                button(x, y2, w, books == 15 ? "Fuming" : books == 10 ? "Hot Potato" : "No books",
                        () -> setSelected(b.withPotatoBooks(next)), () -> setSelected(b.withPotatoBooks(prev)));
            }
            List<String> slots = SimItemData.gemSlots(b.id());
            if (!slots.isEmpty()) {
                button(x + (w + 4), y2, w, gemLabel(b, slots), () -> setSelected(cycleGemQuality(b, slots)),
                        () -> setSelected(cycleGemType(b, slots)));
            }
        }
        int y2 = y + 22;
        SimGear.Armour set = SimGear.ARMOUR.get(Math.floorMod(setIndex, SimGear.ARMOUR.size()));
        button(x + 2 * (w + 4), y2, w * 2 + 4, "Set: " + set.name(), () -> {
            List<SimItemBuild> pieces = new ArrayList<>();
            for (SimItemBuild p : set.pieces()) {
                pieces.add(p.withStars(10));
            }
            SimGear.WardrobeSlot old = gear().slots().get(selSlot);
            SimGear.setWardrobeSlot(cls(), selSlot, pieces, old.equipment(), old.name());
            if (gear().active() == selSlot) {
                SimGear.equip(this.minecraft);
            }
        }, () -> setIndex++);
        boolean on = gear().active() == selSlot;
        button(x + 4 * (w + 4), y2, w, on ? "§aEquipped" : "Equip " + (selSlot + 1),
                () -> SimGear.equipSlot(this.minecraft, on ? -1 : selSlot), null);
    }

    private static SimItemBuild itemAt(List<String> options, int i, SimItemBuild old) {
        String id = options.get(Math.floorMod(i, options.size()));
        if (id.isEmpty()) {
            return SimItemBuild.EMPTY;
        }
        // A new item comes maxed (stars, Recombobulator, books, reforge, gems, enchantments); change any of it after.
        return SimItemData.fullBuild(id);
    }

    private static String gemLabel(SimItemBuild b, List<String> slots) {
        if (b.gems().isEmpty()) {
            return "Gems: none";
        }
        SimItemBuild.Gem g = b.gems().getFirst();
        return b.gems().size() + "/" + slots.size() + " " + cap(g.quality().name()) + " " + cap(g.type().name());
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Left click: none -> Flawless -> Perfect -> none, in every slot, of the best gem for the first slot. */
    private static SimItemBuild cycleGemQuality(SimItemBuild b, List<String> slots) {
        SimItemBuild.GemQuality q = b.gems().isEmpty() ? null : b.gems().getFirst().quality();
        SimItemBuild.GemType t = b.gems().isEmpty() ? defaultGem(slots.getFirst()) : b.gems().getFirst().type();
        SimItemBuild.GemQuality next = q == null ? SimItemBuild.GemQuality.FLAWLESS
                : q == SimItemBuild.GemQuality.FLAWLESS ? SimItemBuild.GemQuality.PERFECT : null;
        return next == null ? b.withGems(List.of()) : fill(b, slots, t, next);
    }

    /** Right click: the next gem type that fits. */
    private static SimItemBuild cycleGemType(SimItemBuild b, List<String> slots) {
        SimItemBuild.GemQuality q = b.gems().isEmpty() ? SimItemBuild.GemQuality.PERFECT
                : b.gems().getFirst().quality();
        SimItemBuild.GemType t = b.gems().isEmpty() ? defaultGem(slots.getFirst()) : b.gems().getFirst().type();
        SimItemBuild.GemType[] all = SimItemBuild.GemType.values();
        return fill(b, slots, all[(t.ordinal() + 1) % all.length], q);
    }

    /** Every slot with {@code type} where it fits, else the slot's own gem. */
    private static SimItemBuild fill(SimItemBuild b, List<String> slots, SimItemBuild.GemType type,
                                     SimItemBuild.GemQuality q) {
        List<SimItemBuild.Gem> gems = new ArrayList<>();
        for (String s : slots) {
            SimItemBuild.GemType t = SimItemData.fits(s, type) ? type : defaultGem(s);
            gems.add(new SimItemBuild.Gem(t, q));
        }
        return b.withGems(gems);
    }

    private static SimItemBuild.GemType defaultGem(String slot) {
        return switch (slot) {
            case "SAPPHIRE" -> SimItemBuild.GemType.SAPPHIRE;
            case "DEFENSIVE", "AMETHYST" -> SimItemBuild.GemType.AMETHYST;
            case "RUBY" -> SimItemBuild.GemType.RUBY;
            case "ONYX" -> SimItemBuild.GemType.ONYX;
            default -> SimItemBuild.GemType.JASPER;
        };
    }

    private void petEditor(int y) {
        SimGear.ClassGear g = gear();
        SimGear.PetBuild out = g.pet();
        int x = panelX + 10;
        int w = (panelW - 20 - 3 * 4) / 4;
        SimGear.PetItem[] items = SimGear.PetItem.values();
        button(x, y, w * 2 + 4, "Pet item: " + out.item().label, () -> SimGear.setPet(cls(),
                new SimGear.PetBuild(out.pet(), items[(out.item().ordinal() + 1) % items.length])),
                () -> SimGear.setPet(cls(), new SimGear.PetBuild(out.pet(),
                        items[(out.item().ordinal() + items.length - 1) % items.length])));
        SimPetRules.Rule rule = g.rule();
        SimPetRules.Mode[] modes = SimPetRules.Mode.values();
        button(x + 2 * (w + 4), y, w, "Rule: " + rule.mode().label, () -> SimGear.setPetRule(cls(),
                new SimPetRules.Rule(modes[(rule.mode().ordinal() + 1) % modes.length], rule.a(), rule.b())), null);
        button(x + 3 * (w + 4), y, w, "Get rod", () -> {
            if (!SimItems.give(this.minecraft, SimPetRules.ROD_ID)) {
                com.epic60869.sky2m.sim.util.ModChat.send("Sim", com.epic60869.sky2m.sim.util.ModChat.text(
                        "The Pet Rules Rod is given inside the sim."));
            }
        }, null);
        int y2 = y + 22;
        button(x, y2, w * 2 + 4, "A = " + (rule.a() == null ? "(pet out)" : rule.a().label()), () -> SimGear
                .setPetRule(cls(), new SimPetRules.Rule(rule.mode() == SimPetRules.Mode.OFF ? SimPetRules.Mode.SWAP
                        : rule.mode(), out, rule.b())), null);
        button(x + 2 * (w + 4), y2, w * 2 + 4, "B = " + (rule.b() == null ? "(pet out)" : rule.b().label()),
                () -> SimGear.setPetRule(cls(), new SimPetRules.Rule(SimPetRules.Mode.TOGGLE, rule.a(), out)), null);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        int[] cell = cellAt(event.x(), event.y());
        if (cell != null) {
            if (pets) {
                int i = cell[1] * COLUMNS + cell[0];
                if (i < SimGear.PETS.size()) {
                    SimGear.setPet(cls(), SimGear.PETS.get(i).id());
                    rebuildWidgets();
                }
            } else {
                selSlot = cell[0];
                selRow = cell[1];
                rebuildWidgets();
            }
            return true;
        }
        if (!pets) {
            int slot = headerAt(event.x(), event.y());
            if (slot >= 0) {
                SimGear.equipSlot(this.minecraft, gear().active() == slot ? -1 : slot);
                selSlot = slot;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int k = event.key();
        if (!pets && k >= com.mojang.blaze3d.platform.InputConstants.KEY_1
                && k <= com.mojang.blaze3d.platform.InputConstants.KEY_9) {
            int slot = k - com.mojang.blaze3d.platform.InputConstants.KEY_1;
            SimGear.equipSlot(this.minecraft, slot);
            selSlot = slot;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    /** {column, row} of the cell under a point, or null. */
    private int[] cellAt(double mx, double my) {
        int rows = pets ? 2 : ROWS;
        if (mx < gridX || my < gridY) {
            return null;
        }
        int col = (int) ((mx - gridX) / (CELL + PAD));
        int row = (int) ((my - gridY) / (CELL + 2));
        if ((mx - gridX) % (CELL + PAD) > CELL || (my - gridY) % (CELL + 2) > CELL || col >= COLUMNS
                || row >= rows) {
            return null;
        }
        return new int[]{col, row};
    }

    /** The wardrobe slot whose number is under a point, or -1. */
    private int headerAt(double mx, double my) {
        if (my < gridY - 11 || my > gridY - 1 || mx < gridX) {
            return -1;
        }
        int col = (int) ((mx - gridX) / (CELL + PAD));
        return (mx - gridX) % (CELL + PAD) <= CELL && col < COLUMNS ? col : -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ProfitPanels.frame(g, this.font, this.width, this.height, panelX, panelY, panelW, panelH, BAR,
                pets ? "Pets" : "Wardrobe");
        SimGear.ClassGear gear = gear();
        int[] hover = cellAt(mouseX, mouseY);
        ItemStack tip = null;
        if (pets) {
            for (int i = 0; i < SimGear.PETS.size(); i++) {
                int cx = gridX + (i % COLUMNS) * (CELL + PAD);
                int cy = gridY + (i / COLUMNS) * (CELL + 2);
                boolean out = SimGear.PETS.get(i).id().equals(gear.pet().id());
                boolean hov = hover != null && hover[1] * COLUMNS + hover[0] == i;
                g.fill(cx, cy, cx + CELL, cy + CELL, hov ? com.epic60869.sky2m.gui.Sky2MGuiStyle.ROW_HOVER : out ? 0xFF16301C : 0xFF141414);
                g.item(icons.get(i)[0], cx + 1, cy + 1);
                if (SimGear.PETS.get(i).classes().contains(cls())) {
                    g.text(this.font, "★", cx + CELL - 6, cy - 2, ProfitPanels.ACCENT, false);
                }
                if (out) {
                    g.outline(cx - 1, cy - 1, CELL + 2, CELL + 2, ProfitPanels.GOOD);
                }
                if (hov) {
                    tip = icons.get(i)[0];
                }
            }
        } else {
            for (int s = 0; s < COLUMNS; s++) {
                int cx = gridX + s * (CELL + PAD);
                boolean on = gear.active() == s;
                g.centeredText(this.font, (on ? "§a" : "§7") + (s + 1), cx + CELL / 2, gridY - 10, ProfitPanels.TEXT);
                for (int r = 0; r < ROWS; r++) {
                    int cy = gridY + r * (CELL + 2) + (r >= 4 ? 0 : 0);
                    boolean hov = hover != null && hover[0] == s && hover[1] == r;
                    boolean sel = s == selSlot && r == selRow;
                    g.fill(cx, cy, cx + CELL, cy + CELL, hov ? com.epic60869.sky2m.gui.Sky2MGuiStyle.ROW_HOVER : r >= 4 ? 0xFF10161E
                            : on ? 0xFF16301C : 0xFF141414);
                    ItemStack st = icons.get(s)[r];
                    if (!st.isEmpty()) {
                        g.item(st, cx + 1, cy + 1);
                        if (hov) {
                            tip = st;
                        }
                    }
                    if (sel) {
                        g.outline(cx - 1, cy - 1, CELL + 2, CELL + 2, ProfitPanels.ACCENT);
                    }
                }
                if (on) {
                    g.outline(cx - 2, gridY - 2, CELL + 4, ROWS * (CELL + 2) + 2, ProfitPanels.GOOD);
                }
            }
        }
        int rows = pets ? 2 : ROWS;
        int textY = gridY + rows * (CELL + 2) + 10 + 2 * 22 + 2;
        String line1;
        String line2;
        if (pets) {
            SimGear.PetBuild out = gear.pet();
            line1 = cls().label + ": [Lvl " + out.pet().level() + "] " + out.label() + " - "
                    + SimGear.summary(out.stats());
            line2 = "Pet Rule: " + gear.rule().describe() + "  (cast any fishing rod)";
        } else {
            SimGear.WardrobeSlot w = gear.slots().get(selSlot);
            String piece = selRow < 4 ? SimItemData.ARMOUR_SLOT_NAMES[selRow] : SimItemData.EQUIPMENT_SLOT_NAMES[selRow - 4];
            SimItemBuild b = selected();
            line1 = "Slot " + (selSlot + 1) + " (" + w.label() + ") " + piece + ": "
                    + (b.isEmpty() ? "empty" : b.displayName().replaceAll("§.", ""));
            SimGear.Armour set = SimGear.fullSet(w.armour());
            line2 = (set != null ? "Full Set Bonus: " + set.bonus() + ". " : "")
                    + "Click a number or press 1-9 to equip. Right-click buttons to go back.";
        }
        g.centeredText(this.font, this.font.plainSubstrByWidth(line1, panelW - 20), panelX + panelW / 2, textY,
                ProfitPanels.TEXT);
        g.centeredText(this.font, this.font.plainSubstrByWidth(line2, panelW - 20), panelX + panelW / 2, textY + 11,
                ProfitPanels.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (tip != null && !tip.is(Items.BARRIER)) {
            g.setComponentTooltipForNextFrame(this.font, Screen.getTooltipFromItem(this.minecraft, tip), mouseX,
                    mouseY);
        }
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
