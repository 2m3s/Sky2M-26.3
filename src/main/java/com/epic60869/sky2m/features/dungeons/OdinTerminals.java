package com.epic60869.sky2m.features.dungeons;

import com.mojang.blaze3d.platform.InputConstants;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StainedGlassPaneBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floor 7 terminal solvers, ported from Odin's TerminalSolver and terminal handlers
 * (https://github.com/odtheking/Odin, BSD-3-Clause).
 * <ul>
 *     <li>Every terminal slot is covered and only the slots to click are drawn, in Odin's colours.</li>
 *     <li>Clicks are taken over: a click on a wrong slot is dropped (no misclicks), clicks in the first
 *     moments after opening are blocked (first click protection), and right slots are clicked with a
 *     middle click like Odin.</li>
 *     <li>Client prediction: the solution updates straight away, and falls back to the server's state
 *     if the click doesn't register.</li>
 * </ul>
 */
public final class OdinTerminals {
    enum Type {
        PANES(Pattern.compile("^Correct all the panes!$"), 45),
        RUBIX(Pattern.compile("^Change all to same color!$"), 45),
        NUMBERS(Pattern.compile("^Click in order!$"), 36),
        STARTS_WITH(Pattern.compile("^What starts with: '(\\w)'\\?$"), 45),
        SELECT(Pattern.compile("^Select all the ([\\w ]+) items!$"), 54),
        MELODY(Pattern.compile("^Click the button on time!$"), 54);

        final Pattern title;
        final int windowSize;

        Type(Pattern title, int windowSize) {
            this.title = title;
            this.windowSize = windowSize;
        }
    }

    private static final DyeColor[] RUBIX_ORDER = {DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED};
    private static final int RUBIX_LAST_PANE = 32;

    private static Handler current;

    private OdinTerminals() {}

    static FeatureConfigs.Terminals config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.f7.terminals;
    }

    public static boolean enabled() {
        FeatureConfigs.Terminals config = config();
        return config != null && config.enabled;
    }

    /** The NoammAddons look: its own centred panel instead of drawing over the menu. */
    static boolean noammStyle() {
        FeatureConfigs.Terminals config = config();
        return config != null && config.solverStyle == FeatureConfigs.TerminalStyle.NOAMM;
    }

    public static void init() {
        // NoammAddons style: its panel is drawn over the whole screen and takes the clicks.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            // Pick the terminal up as soon as its screen opens (also when Hypixel reopens it), not at the end of the
            // next client tick: until then the vanilla menu was drawn for a frame or two.
            if (current == null || current.menu != container.getMenu()) {
                current = null;
                if (enabled() && com.epic60869.sky2m.custom.util.Compat.isOnSkyblock()) open(container);
            }
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                if (active() && noammStyle() && current.menu == container.getMenu()) renderNoamm(g, mouseX, mouseY);
            });
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (!active() || !noammStyle() || current.menu != container.getMenu()) return true;
                clickNoamm(event.x(), event.y(), event.button());
                return false;
            });
        });
        ServerTickCallback.EVENT.register(() -> {
            if (current != null) current.ticksOpened++;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Screen screen = mc.gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                current = null;
                return;
            }
            if (current != null && current.menu != container.getMenu()) current = null;
            // The terminal titles only exist in F7/M7, so this doesn't wait on the dungeon being detected.
            if (current == null && enabled() && com.epic60869.sky2m.custom.util.Compat.isOnSkyblock()) open(container);
            if (current == null) return;
            // Client prediction fallback: a click the server never answered is forgotten after the timeout.
            FeatureConfigs.Terminals config = config();
            long timeout = config == null ? 600 : config.resolveTimeout;
            if (!current.clicked.isEmpty() && System.currentTimeMillis() - current.lastClick >= timeout) {
                current.clicked.clear();
                current.resolve(container.getMenu().slots, -1);
            }
        });
    }

    private static void open(AbstractContainerScreen<?> screen) {
        String title = Sky2MLocation.strip(screen.getTitle().getString()).trim();
        for (Type type : Type.values()) {
            Matcher m = type.title.matcher(title);
            if (!m.matches()) continue;
            Handler handler = switch (type) {
                case PANES -> new Panes();
                case RUBIX -> new Rubix();
                case NUMBERS -> new Numbers();
                case STARTS_WITH -> new StartsWith(m.group(1));
                case SELECT -> {
                    DyeColor color = selectColor(m.group(1));
                    yield color == null ? null : new Select(color);
                }
                case MELODY -> new Melody();
            };
            if (handler == null) return;
            handler.menu = screen.getMenu();
            current = handler;
            handler.resolve(screen.getMenu().slots, -1);
            return;
        }
    }

    private static DyeColor selectColor(String name) {
        String wanted = name.replace("SILVER", "LIGHT GRAY").replace('_', ' ');
        for (DyeColor color : DyeColor.values()) {
            if (color.getName().replace('_', ' ').equalsIgnoreCase(wanted) || color.name().replace('_', ' ').equalsIgnoreCase(wanted)) return color;
        }
        return null;
    }

    /** True while a terminal is open and solved by this class. */
    public static boolean active() {
        return current != null && enabled();
    }

    // ----- Hooks (ContainerSolverMenuMixin / ContainerSolverScreenMixin) -----

    /** A slot of the open menu changed. */
    public static void onSetItem(AbstractContainerMenu menu, int slot) {
        Handler handler = current;
        if (handler == null || menu != handler.menu) return;
        if (slot < 0 || slot >= handler.type().windowSize - 9) return;
        ItemStack stack = menu.getSlot(slot).getItem();
        if (stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK))) return;
        // The server has answered clicks up to this slot.
        for (int i = 0; i < handler.clicked.size(); i++) {
            if (handler.clicked.get(i)[0] == slot) {
                handler.clicked.subList(0, i + 1).clear();
                break;
            }
        }
        handler.resolve(menu.slots, slot);
    }

    /**
     * The whole menu arrived at once (the opening contents, or a resync after a rejected click): the server's state
     * replaces any clicks still waiting for an answer, and the terminal is solved again from every slot.
     */
    public static void onContents(AbstractContainerMenu menu) {
        Handler handler = current;
        if (handler == null || menu != handler.menu) return;
        handler.clicked.clear();
        handler.resolve(menu.slots, -1);
    }

    /** @return true to cancel the vanilla click. */
    public static boolean onSlotClicked(int slot, int button) {
        Handler handler = current;
        if (handler == null || !enabled()) return false;
        if (slot < 0 || slot >= handler.type().windowSize) return true;
        handler.click(slot, button);
        return true;
    }

    /** True when the solver replaces the whole terminal menu (always, except Melody with its solver off), so only the solver is drawn. */
    public static boolean hidesMenu(AbstractContainerScreen<?> screen) {
        Handler handler = current;
        FeatureConfigs.Terminals config = config();
        if (handler == null || config == null || screen.getMenu() != handler.menu) return false;
        return handler.type() != Type.MELODY || config.melodySolver;
    }

    /**
     * Draws the solver on its own in place of the menu (items, chest texture, inventory and tooltips are skipped).
     * The NoammAddons panel is drawn after the screen by {@link #renderNoamm}; the Odin one goes where the chest was,
     * so clicks still land on the right slots.
     */
    public static void renderOwn(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int left, int top) {
        Handler handler = current;
        if (handler == null || noammStyle()) return;
        var font = Minecraft.getInstance().font;
        graphics.pose().pushMatrix();
        graphics.pose().translate(left, top);
        String title = noammTitle(handler.type());
        graphics.text(font, title, 7 + 9 * 18 / 2 - font.width(title) / 2, 6, 0xFFFFFFFF, true);
        render(graphics, screen);
        graphics.pose().popMatrix();
    }

    /** Covers the terminal and draws the solution. The pose is already at the container's origin. */
    public static void render(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        Handler handler = current;
        FeatureConfigs.Terminals config = config();
        if (handler == null || config == null || screen.getMenu() != handler.menu) return;
        if (noammStyle()) return; // drawn by renderNoamm instead
        if (handler.type() == Type.MELODY && !config.melodySolver) return;
        var font = Minecraft.getInstance().font;
        int rows = handler.type().windowSize / 9;
        graphics.fill(7, 17, 7 + 9 * 18, 17 + rows * 18, colour(config.backgroundColor, 0x80262626));
        if (handler.type() == Type.MELODY) {
            // Like Odin's Melody GUI: every lane (columns 1-5) and button (column 7) in rows 1-3 gets a background cell,
            // so the board and buttons show even though the menu's own items are hidden. Melody has 3 rows since
            // Hypixel's 2026 dungeon update; row 4 holds the bottom of the purple column, drawn like the top (Odin).
            int background = colour(config.melodyBackgroundColor, 0xFF262626);
            int magenta = handler.solution.size() > 1 ? handler.solution.get(1) : -1;
            for (Slot slot : screen.getMenu().slots) {
                int row = slot.index / 9;
                int col = slot.index % 9;
                if (magenta >= 0 && slot.index == magenta + 36) {
                    graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour(config.melodyColumnColor, 0xFFAA00AA));
                    continue;
                }
                if (slot.index >= handler.type().windowSize || row < 1 || row > 3 || !(col >= 1 && col <= 5 || col == 7)) continue;
                graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, background);
            }
        }
        for (Slot slot : screen.getMenu().slots) {
            if (slot.index >= handler.type().windowSize) continue;
            if (!handler.solution.contains(slot.index)) continue;
            Render r = handler.render(slot.index);
            if (r == null) continue;
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, r.colour());
            if (r.text() != null) graphics.text(font, r.text(), slot.x + 8 - font.width(r.text()) / 2, slot.y + 4, 0xFFFFFFFF, true);
        }
    }

    // ----- NoammAddons style (layout, colours and click handling follow NoammAddons' TerminalSolver) -----

    private static final int NOAMM_SOLUTION = 0x8200FF00;
    /** Click in order: green to click now, then yellow, then red. */
    private static final int[] NOAMM_NUMBERS = {0x8200FF00, 0x82FFFF00, 0x82FF0000};
    private static final int NOAMM_RUBIX_PLUS = 0x820072FF;
    private static final int NOAMM_RUBIX_MINUS = 0x82CD0000;
    private static final int NOAMM_MELODY_COLUMN = 0x82FF00FF;
    private static final int NOAMM_MELODY_INDICATOR = 0x82FF7400;

    /** Panel scale in GUI units: NoammAddons draws at 3x real pixels, times its Scale setting. */
    private static float noammScale() {
        FeatureConfigs.Terminals config = config();
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return (float) (3.0 * (config == null ? 1f : config.noammScale) / Math.max(1.0, guiScale));
    }

    /**
     * The part of the chest NoammAddons draws for each terminal (its gridSize): only the playable cells, packed
     * together with no gaps, not the whole 9-wide chest. {col, row, width, height} of the chest area it covers.
     */
    private static int[] noammGrid(Type type) {
        return switch (type) {
            case PANES -> new int[]{2, 1, 5, 3};
            case RUBIX -> new int[]{3, 1, 3, 3};
            case NUMBERS -> new int[]{1, 1, 7, 2};
            case STARTS_WITH -> new int[]{1, 1, 7, 3};
            case SELECT -> new int[]{1, 1, 7, 4};
            // The five lanes and, right next to them, the button column (chest column 7).
            case MELODY -> new int[]{1, 0, 6, 5};
        };
    }

    /** Where a chest slot is in the NoammAddons grid ({x, y} in cells), or null if it isn't shown. */
    private static int[] noammCell(Type type, int slot) {
        int[] grid = noammGrid(type);
        int col = slot % 9;
        int row = slot / 9;
        if (type == Type.MELODY) {
            if (col == 7) col = 6;
            else if (col < 1 || col > 5) return null;
        }
        int x = col - grid[0];
        int y = row - grid[1];
        return x < 0 || y < 0 || x >= grid[2] || y >= grid[3] ? null : new int[]{x, y};
    }

    /** The chest slot under a cell of the NoammAddons grid. */
    private static int noammSlotAt(Type type, int x, int y) {
        int[] grid = noammGrid(type);
        int col = x + grid[0];
        if (type == Type.MELODY && x == 5) col = 7;
        return (y + grid[1]) * 9 + col;
    }

    private static final int NOAMM_CELL = 16;
    private static final int NOAMM_PADDING = 2;
    private static final int NOAMM_WRONG = 0x82FF0000;

    /** Top-left of the grid (in panel units): centred on the screen, like NoammAddons. */
    private static float[] noammOrigin(int guiWidth, int guiHeight, Type type, float scale) {
        int[] grid = noammGrid(type);
        return new float[]{guiWidth / scale / 2 - grid[2] * NOAMM_CELL / 2f, guiHeight / scale / 2 - grid[3] * NOAMM_CELL / 2f};
    }

    private static String noammTitle(Type type) {
        return switch (type) {
            case PANES -> "Panes";
            case RUBIX -> "Rubix";
            case NUMBERS -> "Numbers";
            case STARTS_WITH -> "Starts With";
            case SELECT -> "Colors";
            case MELODY -> "Melody";
        };
    }

    private static void renderNoamm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Handler handler = current;
        FeatureConfigs.Terminals config = config();
        if (handler == null || config == null) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        Type type = handler.type();
        g.nextStratum();

        float scale = noammScale();
        int[] grid = noammGrid(type);
        float[] origin = noammOrigin(g.guiWidth(), g.guiHeight(), type, scale);
        int width = grid[2] * NOAMM_CELL;
        int height = grid[3] * NOAMM_CELL;
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        g.pose().translate(origin[0], origin[1]);

        String title = noammTitle(type);
        g.pose().pushMatrix();
        g.pose().translate(width / 2f, -15 - NOAMM_PADDING);
        g.pose().scale(1.2f, 1.2f);
        g.text(font, title, -font.width(title) / 2, 0, 0xFFFFFFFF, true);
        g.pose().popMatrix();

        g.fill(-NOAMM_PADDING, -NOAMM_PADDING, width + NOAMM_PADDING, height + NOAMM_PADDING, 0x64000000);
        g.outline(-NOAMM_PADDING, -NOAMM_PADDING, width + NOAMM_PADDING * 2, height + NOAMM_PADDING * 2, 0xFFFFFFFF);

        if (type == Type.MELODY) {
            // The right lane: the whole column; the moving pane: its cell; the buttons: green on the one to press.
            int pane = handler.solution.isEmpty() ? -1 : handler.solution.getFirst();
            int magenta = handler.solution.size() > 1 ? handler.solution.get(1) : -1;
            int press = handler.solution.size() > 2 ? handler.solution.get(2) : -1;
            int[] column = magenta < 0 ? null : noammCell(type, magenta);
            if (column != null) noammSlot(g, config, column[0] * NOAMM_CELL, 0, NOAMM_CELL, height, NOAMM_MELODY_COLUMN);
            int[] cell = pane < 0 ? null : noammCell(type, pane);
            if (cell != null) noammSlot(g, config, cell[0] * NOAMM_CELL, cell[1] * NOAMM_CELL, NOAMM_CELL, NOAMM_CELL, NOAMM_MELODY_INDICATOR);
            for (int slot : MELODY_BUTTONS) {
                int[] button = noammCell(type, slot);
                if (button == null) continue;
                int colour = press < 0 ? 0x40FFFFFF : slot == press ? NOAMM_SOLUTION : NOAMM_WRONG;
                noammSlot(g, config, button[0] * NOAMM_CELL, button[1] * NOAMM_CELL, NOAMM_CELL, NOAMM_CELL, colour);
            }
        } else {
            List<Integer> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(handler.solution));
            for (int index = 0; index < distinct.size(); index++) {
                int slot = distinct.get(index);
                int[] cell = noammCell(type, slot);
                if (cell == null) continue;
                int x = cell[0] * NOAMM_CELL;
                int y = cell[1] * NOAMM_CELL;
                switch (type) {
                    case NUMBERS -> {
                        if (index > 2) continue;
                        // The Numbers 1-3 colours, at the NoammAddons transparency.
                        noammSlot(g, config, x, y, NOAMM_CELL, NOAMM_CELL, (handler.render(slot).colour() & 0x00FFFFFF) | (NOAMM_NUMBERS[index] & 0xFF000000));
                        if (config.noammShowNumbers) {
                            Render r = handler.render(slot);
                            if (r != null && r.text() != null) g.text(font, r.text(), x + 8 - font.width(r.text()) / 2, y + 4, 0xFFFFFFFF, true);
                        }
                    }
                    case RUBIX -> {
                        Render r = handler.render(slot);
                        if (r == null) continue;
                        boolean positive = !r.text().startsWith("-");
                        noammSlot(g, config, x, y, NOAMM_CELL, NOAMM_CELL, positive ? NOAMM_RUBIX_PLUS : NOAMM_RUBIX_MINUS);
                        g.text(font, r.text(), x + 8 - font.width(r.text()) / 2, y + 4, 0xFFFFFFFF, true);
                    }
                    default -> noammSlot(g, config, x, y, NOAMM_CELL, NOAMM_CELL, NOAMM_SOLUTION);
                }
            }
        }
        g.pose().popMatrix();
    }

    private static void noammSlot(GuiGraphicsExtractor g, FeatureConfigs.Terminals config, int x, int y, int w, int h, int colour) {
        switch (config.noammSlotStyle) {
            case RECT -> g.fill(x, y, x + w, y + h, colour);
            case BORDERED -> {
                g.fill(x, y, x + w, y + h, (colour & 0x00FFFFFF) | 0x28000000);
                g.outline(x, y, w, h, colour | 0xFF000000);
            }
            case BUTTON -> {
                int solid = colour | 0xFF000000;
                g.fill(x, y, x + w, y + h, darker(solid));
                g.fill(x, y, x + w - 1, y + h - 1, solid);
                g.fill(x + 1, y + 1, x + w - 1, y + h - 1, darker(solid));
            }
        }
    }

    private static int darker(int argb) {
        int r = (int) (((argb >> 16) & 0xFF) * 0.7);
        int gr = (int) (((argb >> 8) & 0xFF) * 0.7);
        int b = (int) ((argb & 0xFF) * 0.7);
        return (argb & 0xFF000000) | (r << 16) | (gr << 8) | b;
    }

    /** A click on the NoammAddons panel: works out the terminal slot and clicks it like NoammAddons would. */
    private static void clickNoamm(double mouseX, double mouseY, int mouseButton) {
        Handler handler = current;
        if (handler == null) return;
        Minecraft mc = Minecraft.getInstance();
        float scale = noammScale();
        Type type = handler.type();
        int[] grid = noammGrid(type);
        float[] origin = noammOrigin(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), type, scale);
        int x = (int) Math.floor((mouseX / scale - origin[0]) / NOAMM_CELL);
        int y = (int) Math.floor((mouseY / scale - origin[1]) / NOAMM_CELL);
        if (x < 0 || y < 0 || x >= grid[2] || y >= grid[3]) return;
        int slot = noammSlotAt(type, x, y);
        if (slot >= type.windowSize) return;
        int button = mouseButton == InputConstants.MOUSE_BUTTON_RIGHT ? 1 : 0;
        switch (handler.type()) {
            case NUMBERS -> {
                // Only the next number, like NoammAddons.
                if (handler.solution.isEmpty() || handler.solution.getFirst() != slot) return;
            }
            case RUBIX -> {
                // The right button for this slot, whichever one you pressed.
                if (!(handler instanceof Rubix rubix) || !handler.solution.contains(slot)) return;
                button = rubix.rightClick.contains(slot) ? 1 : 0;
            }
            case MELODY -> {
                if (!isMelodyButton(slot)) return;
            }
            default -> {
                if (!handler.solution.contains(slot)) return;
            }
        }
        handler.click(slot, button);
    }

    static int colour(String value, int fallback) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ----- Handlers -----

    record Render(int colour, String text) {}

    abstract static class Handler {
        AbstractContainerMenu menu;
        final List<int[]> clicked = new ArrayList<>();
        final List<Integer> solution = new ArrayList<>();
        final long opened = System.currentTimeMillis();
        long lastClick;
        int ticksOpened;

        abstract Type type();

        abstract List<Integer> solve(List<Slot> slots, int updated);

        abstract Render render(int slot);

        void resolve(List<Slot> allSlots, int updated) {
            int size = Math.min(type().windowSize - 9, allSlots.size());
            solution.clear();
            solution.addAll(solve(allSlots.subList(0, size), updated));
            FeatureConfigs.Terminals config = config();
            if (config == null || config.clickPrediction) for (int[] c : clicked) simulate(c[0], c[1]);
        }

        void simulate(int slot, int button) {
            solution.remove(Integer.valueOf(slot));
        }

        boolean canClick(int slot, int button) {
            return solution.contains(slot);
        }

        boolean protect() {
            FeatureConfigs.Terminals config = config();
            if (config == null) return false;
            if (System.currentTimeMillis() - opened < config.firstClickProt) return true;
            return config.lagProtection && ticksOpened < config.lagProtectionTicks;
        }

        void click(int slot, int button) {
            FeatureConfigs.Terminals config = config();
            boolean blockWrong = config == null || config.blockMisclicks;
            if (protect()) return;
            if (blockWrong && !canClick(slot, button)) return;
            if (canClick(slot, button)) F7Extras.onTerminalClick();
            int mouse = button == 1 && type() == Type.RUBIX ? 1 : 2;
            clicked.add(new int[]{slot, mouse});
            lastClick = System.currentTimeMillis();
            if (config == null || config.clickPrediction) simulate(slot, mouse);
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode == null || mc.player == null) return;
            mc.gameMode.handleContainerInput(menu.containerId, slot, mouse, mouse == 2 ? ContainerInput.CLONE : ContainerInput.PICKUP, mc.player);
        }
    }

    private static boolean is(ItemStack stack, Item item) {
        return stack.is(item);
    }

    static final class Panes extends Handler {
        Type type() { return Type.PANES; }

        List<Integer> solve(List<Slot> slots, int updated) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) if (is(slots.get(i).getItem(), Items.STAINED_GLASS_PANE.pick(DyeColor.RED))) out.add(i);
            return out;
        }

        Render render(int slot) {
            return new Render(colour(config().panesColor, 0xFF55FF55), null);
        }
    }

    static final class Numbers extends Handler {
        Type type() { return Type.NUMBERS; }

        List<Integer> solve(List<Slot> slots, int updated) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) if (is(slots.get(i).getItem(), Items.STAINED_GLASS_PANE.pick(DyeColor.RED))) out.add(i);
            out.sort((a, b) -> Integer.compare(slots.get(a).getItem().getCount(), slots.get(b).getItem().getCount()));
            return out;
        }

        @Override
        void simulate(int slot, int button) {
            if (!solution.isEmpty()) solution.removeFirst();
        }

        @Override
        boolean canClick(int slot, int button) {
            return !solution.isEmpty() && solution.getFirst() == slot;
        }

        Render render(int slot) {
            FeatureConfigs.Terminals c = config();
            int index = solution.indexOf(slot);
            int colour = switch (index) {
                // Green to click now, then yellow, then red; each moves up a colour as you click.
                case 0 -> colour(c.numbersNextColor, 0xFF55FF55);
                case 1 -> colour(c.numbersSecondColor, 0xFFFFFF55);
                case 2 -> colour(c.numbersThirdColor, 0xFFFF5555);
                default -> 0;
            };
            return new Render(colour, String.valueOf(Math.abs((solution.size() - 14) - index) + 1));
        }
    }

    static final class Rubix extends Handler {
        private DyeColor locked;
        private final Set<Integer> rightClick = new HashSet<>();

        Type type() { return Type.RUBIX; }

        private static DyeColor paneColour(ItemStack stack) {
            return stack.getItem() instanceof BlockItem block && block.getBlock() instanceof StainedGlassPaneBlock pane ? pane.getColor() : null;
        }

        private static int indexOf(DyeColor color) {
            for (int i = 0; i < RUBIX_ORDER.length; i++) if (RUBIX_ORDER[i] == color) return i;
            return -1;
        }

        private static int dist(int pane, int goal) {
            return pane > goal ? goal + RUBIX_ORDER.length - pane : goal - pane;
        }

        private Map<Integer, Integer> clicksFor(DyeColor goal, List<int[]> panes) {
            boolean leftOnly = config() != null && config().rubixLeftClicksOnly;
            int goalIndex = indexOf(goal);
            Map<Integer, Integer> out = new HashMap<>();
            for (int[] p : panes) {
                int forward = dist(p[1], goalIndex);
                int clicks = forward > 2 && !leftOnly ? forward - RUBIX_ORDER.length : forward;
                if (clicks != 0) out.put(p[0], clicks);
            }
            return out;
        }

        List<Integer> solve(List<Slot> slots, int updated) {
            List<int[]> panes = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                DyeColor color = paneColour(slots.get(i).getItem());
                if (color == null || color == DyeColor.BLACK) continue;
                int index = indexOf(color);
                if (index >= 0) panes.add(new int[]{i, index});
            }
            if ((updated == RUBIX_LAST_PANE || updated == -1) && locked == null && panes.size() >= 9) {
                int best = Integer.MAX_VALUE;
                for (DyeColor goal : RUBIX_ORDER) {
                    int total = clicksFor(goal, panes).values().stream().mapToInt(Math::abs).sum();
                    if (total < best) {
                        best = total;
                        locked = goal;
                    }
                }
            }
            rightClick.clear();
            List<Integer> out = new ArrayList<>();
            if (locked == null) return out;
            for (var entry : clicksFor(locked, panes).entrySet()) {
                if (entry.getValue() < 0) rightClick.add(entry.getKey());
                for (int i = 0; i < Math.abs(entry.getValue()); i++) out.add(entry.getKey());
            }
            return out;
        }

        @Override
        boolean canClick(int slot, int button) {
            return solution.contains(slot) && (button == 1) == rightClick.contains(slot);
        }

        Render render(int slot) {
            FeatureConfigs.Terminals c = config();
            int remaining = (int) solution.stream().filter(s -> s == slot).count();
            if (remaining == 0) return null;
            int clicks = rightClick.contains(slot) ? -remaining : remaining;
            int colour = switch (clicks) {
                case 1 -> colour(c.rubix1Color, 0xFF55FF55);
                case 2 -> colour(c.rubix2Color, 0xFF2A7F2A);
                case -1, 4 -> colour(c.rubixMinus1Color, 0xFFAA0000);
                default -> colour(c.rubixMinus2Color, 0xFF550000);
            };
            return new Render(colour, String.valueOf(clicks));
        }
    }

    static final class StartsWith extends Handler {
        private final String letter;
        private final Map<Integer, Boolean> clickedOverrides = new HashMap<>();

        StartsWith(String letter) {
            this.letter = letter.toLowerCase(Locale.ROOT);
        }

        Type type() { return Type.STARTS_WITH; }

        List<Integer> solve(List<Slot> slots, int updated) {
            clickedOverrides.computeIfPresent(updated, (k, v) -> true);
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) continue;
                String name = Sky2MLocation.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
                boolean glint = stack.hasFoil() && !stack.is(Items.GOLDEN_APPLE) && !stack.getPrototype().has(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
                if (name.startsWith(letter) && clickedOverrides.get(i) != Boolean.TRUE && !glint) out.add(i);
            }
            return out;
        }

        @Override
        void click(int slot, int button) {
            if (canClick(slot, button) && !clickedOverrides.containsKey(slot)) clickedOverrides.put(slot, false);
            super.click(slot, button);
        }

        Render render(int slot) {
            return new Render(colour(config().startsWithColor, 0xFF55FF55), null);
        }
    }

    static final class Select extends Handler {
        private final Set<String> prefixes;

        Select(DyeColor color) {
            prefixes = switch (color) {
                case BLACK -> Set.of("black", "ink");
                case BLUE -> Set.of("blue", "lapis");
                case BROWN -> Set.of("brown", "cocoa");
                case WHITE -> Set.of("white", "bone", "wool");
                case GREEN -> Set.of("green", "cactus");
                case RED -> Set.of("red", "rose");
                case YELLOW -> Set.of("yellow", "dandelion");
                case LIGHT_GRAY -> Set.of("silver", "light gray");
                default -> Set.of(color.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            };
        }

        Type type() { return Type.SELECT; }

        List<Integer> solve(List<Slot> slots, int updated) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty() || stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)) || stack.hasFoil()) continue;
                String name = Sky2MLocation.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix)) {
                        out.add(i);
                        break;
                    }
                }
            }
            return out;
        }

        Render render(int slot) {
            return new Render(colour(config().selectColor, 0xFF55FF55), null);
        }
    }

    /** Whether a terminal is open and being solved. */
    static boolean terminalOpen() {
        return current != null;
    }

    /** Melody's buttons (column 7 of rows 1-3): Melody has had 3 rows since Hypixel's 2026 dungeon update (Odin 0.3.6). */
    private static final int[] MELODY_BUTTONS = {16, 25, 34};

    private static boolean isMelodyButton(int slot) {
        for (int button : MELODY_BUTTONS) if (button == slot) return true;
        return false;
    }

    static final class Melody extends Handler {
        Type type() { return Type.MELODY; }

        List<Integer> solve(List<Slot> slots, int updated) {
            int magenta = -1;
            int greenPane = -1;
            int greenClay = -1;
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i).getItem();
                if (magenta < 0 && stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA))) magenta = i;
                if (stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.LIME))) greenPane = i;
                if (stack.is(Items.DYED_TERRACOTTA.pick(DyeColor.LIME))) greenClay = i;
            }
            List<Integer> out = new ArrayList<>();
            out.add(greenPane);
            out.add(magenta);
            if (greenPane >= 0 && magenta >= 0 && greenPane % 9 == magenta % 9) out.add(greenClay);
            return out;
        }

        @Override
        boolean canClick(int slot, int button) {
            return isMelodyButton(slot);
        }

        @Override
        void simulate(int slot, int button) {}

        Render render(int slot) {
            FeatureConfigs.Terminals c = config();
            int row = slot / 9;
            return new Render(row == 0 || row == 4 ? colour(c.melodyColumnColor, 0xFFAA00AA) : colour(c.melodyPointerColor, 0xFF55FF55), null);
        }
    }
}
