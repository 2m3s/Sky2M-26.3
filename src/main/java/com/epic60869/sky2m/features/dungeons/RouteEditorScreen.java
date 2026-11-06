package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Route;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Step;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Type;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Waypoint;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * /s2 route edit menu: the room's route, step by step. Move through routes and steps, add and delete them, and per waypoint
 * nudge it, label it, pick it up to put down with a right click, or delete it. Packs are at the bottom. Works whether or not
 * the editor is on; {@link RouteEditor} is the part in the world.
 */
public final class RouteEditorScreen extends Screen {
    private static final int ROW = 20;
    private static final int NUDGE = 18;

    private final Room room;
    private int first;
    private EditBox packName;
    private String packText = "";

    public RouteEditorScreen(Room room) {
        super(Component.literal("Route Editor"));
        this.room = room;
    }

    private int panelWidth() { return Math.min(520, width - 16); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return 8; }
    private int rowsTop() { return top() + 72; }
    private int visibleRows() { return Math.max(2, (height - rowsTop() - 44) / ROW); }

    private List<Step> shown() {
        Route showing = DungeonRoutes.showingRoute(room);
        return showing == null ? List.of() : showing.steps();
    }

    private int step() {
        return Math.max(0, Math.min(DungeonRoutes.stepIndex(), shown().size() - 1));
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        if (packName != null) packText = packName.getValue();
        clearWidgets();
        int x = left() + 8, y = top() + 20;
        List<Route> routes = DungeonRoutes.routesFor(room.getName());
        List<Step> route = shown();

        // Routes
        button("<", x + 62, y, 18, () -> DungeonRoutes.switchRoute(room, -1)).active = routes.size() > 1;
        button(">", x + 82, y, 18, () -> DungeonRoutes.switchRoute(room, 1)).active = routes.size() > 1;
        button("New", x + 104, y, 36, () -> DungeonRoutes.newRoute(room));
        Route showing = DungeonRoutes.showingRoute(room);
        button("Delete", x + 144, y, 44, () -> DungeonRoutes.deleteShowing(room)).active = showing != null && showing.own();

        // Steps
        y += 22;
        button("<", x + 62, y, 18, () -> DungeonRoutes.setStepIndex(step() - 1)).active = step() > 0;
        button(">", x + 82, y, 18, () -> DungeonRoutes.setStepIndex(step() + 1)).active = step() < route.size() - 1;
        button("New", x + 104, y, 36, () -> {
            List<Step> steps = DungeonRoutes.editableSteps(room);
            int at = Math.min(step() + 1, steps.size());
            steps.add(at, new Step());
            DungeonRoutes.setStepIndex(at);
            DungeonRoutes.saveCustom();
        });
        button("Delete", x + 144, y, 44, () -> {
            List<Step> steps = DungeonRoutes.editableSteps(room);
            if (steps.size() <= 1) {
                steps.getFirst().waypoints().clear();
                steps.getFirst().line().clear();
            } else {
                steps.remove(Math.min(step(), steps.size() - 1));
            }
            DungeonRoutes.setStepIndex(Math.min(step(), steps.size() - 1));
            DungeonRoutes.saveCustom();
        }).active = !route.isEmpty();
        button("Clear Line", x + 192, y, 60, () -> {
            DungeonRoutes.editableSteps(room).get(step()).line().clear();
            DungeonRoutes.saveCustom();
        }).active = !route.isEmpty() && !route.get(step()).line().isEmpty();
        addRenderableWidget(Button.builder(Component.literal("Adds: " + RouteEditor.typeName(RouteEditor.addType())), b -> {
            Type[] types = Type.values();
            RouteEditor.setAddType(types[(RouteEditor.addType().ordinal() + 1) % types.length]);
            rebuild();
        }).bounds(left() + panelWidth() - 8 - 100, y, 100, 18).build());

        // Waypoints
        List<Waypoint> waypoints = RouteEditor.waypointsOf(route, step());
        first = Math.max(0, Math.min(first, waypoints.size() - visibleRows()));
        for (int i = first; i < waypoints.size() && i < first + visibleRows(); i++) rowButtons(i, rowsTop() + (i - first) * ROW);

        // Packs
        int by = height - 26;
        packName = new EditBox(font, x, by + 1, 110, 16, Component.literal("Pack name"));
        packName.setMaxLength(40);
        packName.setValue(packText);
        packName.setHint(Component.literal("Pack name").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(packName);
        button("Import Clipboard", x + 114, by, 96, () -> RoutePacks.importClipboard(packName.getValue()));
        button("Copy Room", x + 214, by, 62, RoutePacks::exportRoom);
        button("Copy All", x + 280, by, 54, RoutePacks::exportAll);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(left() + panelWidth() - 8 - 60, by, 60, 18).build());
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> {
            action.run();
            rebuild();
        }).bounds(x, y, w, 18).build());
    }

    /** Label, Move, Delete and the six nudges, from the right edge. */
    private void rowButtons(int index, int y) {
        int x = left() + panelWidth() - 8;
        x -= 18;
        button("X", x, y, 18, () -> {
            RouteEditor.remove(DungeonRoutes.editableSteps(room), located(index));
            DungeonRoutes.saveCustom();
        });
        x -= 38;
        addRenderableWidget(Button.builder(Component.literal("Move"), b -> {
            RouteEditor.pickUp(room, located(index));
            DungeonRoutes.say(RouteEditor.active() ? "Right click the block to move it to."
                : "Turn the route editor on with /s2 route edit, then right click the block to move it to.", ChatFormatting.GREEN);
            onClose();
        }).bounds(x, y, 36, 18).build());
        x -= 40;
        addRenderableWidget(Button.builder(Component.literal("Label"), b -> {
            String current = RouteEditor.waypointsOf(shown(), step()).get(index).name();
            minecraft.gui.setScreen(new Prompt("Waypoint label", current == null ? "" : current, this, text -> {
                RouteEditor.label(DungeonRoutes.editableSteps(room), located(index), text);
                DungeonRoutes.saveCustom();
            }));
        }).bounds(x, y, 38, 18).build());
        int[][] nudges = {{0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}};
        String[] names = {"Z+", "Z-", "Y+", "Y-", "X+", "X-"};
        for (int n = 0; n < nudges.length; n++) {
            x -= NUDGE;
            int[] by = nudges[n];
            button(names[n], x, y, NUDGE - 1, () -> {
                List<Step> steps = DungeonRoutes.editableSteps(room);
                RouteEditor.Located at = located(index);
                RouteEditor.move(steps, at, at.waypoint().pos().offset(by[0], by[1], by[2]));
                DungeonRoutes.saveCustom();
            });
        }
    }

    private RouteEditor.Located located(int index) {
        return new RouteEditor.Located(step(), index, RouteEditor.waypointsOf(shown(), step()).get(index));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int left = left(), w = panelWidth();
        g.fill(0, 0, width, height, 0xC007090D);
        g.fill(left, top(), left + w, height - 4, 0xF0181B21);
        g.fill(left, top(), left + w, top() + 2, 0xFFFF5555);
        List<Route> routes = DungeonRoutes.routesFor(room.getName());
        Route showing = DungeonRoutes.showingRoute(room);
        g.text(font, Component.literal("Route Editor: " + room.getName()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), left + 8, top() + 7, 0xFFFFFFFF, true);
        String source = showing == null ? "no route yet" : showing.own() ? "yours" : "bundled or a pack, changing it makes it yours";
        g.text(font, source, left + w - 8 - font.width(source), top() + 7, 0xFF7A8290, false);
        int routeNumber = showing == null ? 0 : routes.indexOf(showing) + 1;
        g.text(font, "Route " + routeNumber + "/" + routes.size(), left + 8, top() + 25, 0xFFB8C0CC, false);
        List<Step> route = shown();
        g.text(font, "Step " + (route.isEmpty() ? 0 : step() + 1) + "/" + route.size(), left + 8, top() + 47, 0xFFB8C0CC, false);
        List<Waypoint> waypoints = RouteEditor.waypointsOf(route, step());
        int textWidth = w - 16 - 18 - 38 - 40 - 6 * NUDGE - 6;
        if (waypoints.isEmpty()) g.text(font, "No waypoints in this step. /s2 route edit turns the editor on, then right click a block.", left + 8, rowsTop() + 5, 0xFF7A8290, false);
        for (int i = first; i < waypoints.size() && i < first + visibleRows(); i++) {
            Waypoint wp = waypoints.get(i);
            BlockPos p = wp.pos();
            String text = RouteEditor.typeName(wp.type()) + " " + p.getX() + " " + p.getY() + " " + p.getZ() + (wp.name() == null ? "" : " \"" + wp.name() + "\"");
            g.text(font, font.plainSubstrByWidth(text, textWidth), left + 8, rowsTop() + (i - first) * ROW + 5, 0xFFFFFFFF, false);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int rows = RouteEditor.waypointsOf(shown(), step()).size();
        int next = Math.max(0, Math.min(first - (int) Math.signum(scrollY), rows - visibleRows()));
        if (next != first) {
            first = next;
            rebuild();
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Asks for one line of text. */
    public static final class Prompt extends Screen {
        private final String initial;
        private final Screen parent;
        private final Consumer<String> done;
        private EditBox box;

        public Prompt(String title, String initial, Screen parent, Consumer<String> done) {
            super(Component.literal(title));
            this.initial = initial;
            this.parent = parent;
            this.done = done;
        }

        @Override
        protected void init() {
            box = new EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, title);
            box.setMaxLength(64);
            box.setValue(initial);
            addRenderableWidget(box);
            setInitialFocus(box);
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> finish()).bounds(width / 2 - 100, height / 2 + 16, 98, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 2, height / 2 + 16, 98, 20).build());
        }

        private void finish() {
            done.accept(box.getValue());
            onClose();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.key() == 257 || event.key() == 335) { // Enter
                finish();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xC007090D);
            g.text(font, title, width / 2 - font.width(title) / 2, height / 2 - 24, 0xFFFFFFFF, true);
            super.extractRenderState(g, mouseX, mouseY, delta);
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().gui.setScreen(parent);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
