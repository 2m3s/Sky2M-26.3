package com.epic60869.sky2m.features.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Registry for Sky2M's text HUD elements. Each element supplies its lines; this class draws
 * them with a fitted background and stores position and scale in {@code sky2m-huds.json}.
 * Every registered element appears in the /s2 gui editor.
 */
public final class Sky2MHuds {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Identifier ID = Identifier.fromNamespaceAndPath("sky2m", "feature_huds");
    public static final int PADDING = 3;
    public static final int LINE_HEIGHT = 10;

    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();
    /** Whether each HUD's feature is turned on, for HUDs whose {@code enabled} also depends on where you are. */
    private static final Map<String, BooleanSupplier> SETTINGS = new java.util.HashMap<>();
    private static final Map<String, Placement> PLACEMENTS = new LinkedHashMap<>();
    private static Path file;
    private static final String BACKGROUNDS_OFF = "#backgroundsOff";
    /**
     * The GUI-scaled screen size the positions were placed on. When the window or GUI scale changes, every HUD
     * keeps its place relative to the screen instead of piling up against an edge.
     */
    private static int refWidth;
    private static int refHeight;

    public static final class Placement {
        public int x;
        public int y;
        public float scale = 1f;
        /** Draw the dark background behind the HUD. Toggled by right-clicking it in /s2 gui. */
        public boolean background = false;
    }

    /** A HUD element that draws itself instead of drawing text lines. Sizes are unscaled. */
    public interface CustomHud {
        int width();
        int height();
        /** Whether there is anything to show outside the editor. */
        boolean visible();
        void render(GuiGraphicsExtractor graphics, boolean preview);
    }

    public record Element(String id, String name, BooleanSupplier enabled, Supplier<List<Component>> lines,
                          List<Component> preview, int defaultX, int defaultY, CustomHud custom) {}

    private Sky2MHuds() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m-huds.json");
        load();
        HudElementRegistry.addLast(ID, (graphics, delta) -> renderAll(graphics));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> clientTicks++);
    }

    /**
     * Registers a text HUD.
     *
     * @param lines   current lines; an empty list hides the element
     * @param preview sample lines shown in the /s2 gui editor when there is no live data
     */
    public static void register(String id, String name, BooleanSupplier enabled, Supplier<List<Component>> lines,
                                List<Component> preview, int defaultX, int defaultY) {
        ELEMENTS.put(id, new Element(id, name, enabled, lines, preview, defaultX, defaultY, null));
        defaultPlacement(id, defaultX, defaultY);
    }

    /** Registers a HUD element that draws itself. */
    public static void registerCustom(String id, String name, BooleanSupplier enabled, CustomHud hud, int defaultX, int defaultY) {
        ELEMENTS.put(id, new Element(id, name, enabled, List::of, List.of(), defaultX, defaultY, hud));
        defaultPlacement(id, defaultX, defaultY);
    }

    /** What to search the settings for, when a HUD's name isn't the name of its setting (right-click in /s2 gui). */
    private static final Map<String, String> SETTINGS_NAMES = new java.util.HashMap<>();

    public static void setSettingsSearch(String id, String search) {
        SETTINGS_NAMES.put(id, search);
    }

    /** The settings search for a HUD: what was registered, else its name ("GIF: cat.gif" finds the GIF Player). */
    public static String settingsSearch(String id, String name) {
        String search = SETTINGS_NAMES.get(id);
        if (search != null) return search;
        int colon = name.indexOf(':');
        if (id.startsWith("gif_")) return "GIF Player";
        return colon > 0 ? name.substring(0, colon).trim() : name;
    }

    /** Removes a HUD (its saved position is kept), for HUDs that come and go, like the GIF Player's GIFs. */
    public static void unregister(String id) {
        ELEMENTS.remove(id);
        SETTINGS.remove(id);
    }

    private static void defaultPlacement(String id, int defaultX, int defaultY) {
        PLACEMENTS.computeIfAbsent(id, k -> {
            Placement p = new Placement();
            p.x = defaultX;
            p.y = defaultY;
            return p;
        });
    }

    /**
     * Whether a HUD's feature is turned on in the settings: /s2 gui only lists those. {@code enabled} is often also
     * "in a dungeon" or "holding a rod", so HUDs like that register their setting on its own here.
     */
    public static void setting(String id, BooleanSupplier turnedOn) {
        SETTINGS.put(id, turnedOn);
    }

    /** The HUD's feature is turned on (its registered setting, else its {@code enabled}). */
    public static boolean turnedOn(Element element) {
        BooleanSupplier setting = SETTINGS.get(element.id());
        return safe(setting != null ? setting : element.enabled());
    }

    public static List<Element> elements() {
        return new ArrayList<>(ELEMENTS.values());
    }

    public static Placement placement(String id) {
        return PLACEMENTS.computeIfAbsent(id, k -> new Placement());
    }

    private static void renderAll(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Hidden while the Inquisitor Gamble plays, so a tracker doesn't show the Chimera first.
        if (com.epic60869.sky2m.features.combat.InquisitorGamble.holding()) return;
        if (!Sky2MLocation.skyblockOnlyAllows()) return;
        // Positions saved before the reference size existed were placed on this screen size.
        if (refWidth <= 0 || refHeight <= 0) setReferenceToScreen();
        for (Element element : ELEMENTS.values()) {
            if (!safe(element.enabled())) continue;
            if (element.custom() != null) {
                try {
                    if (customVisible(element)) renderCustom(graphics, element, false);
                } catch (Exception e) {
                    com.epic60869.sky2m.features.sbc.SbcCrashReports.report(e, "HUD " + element.id());
                }
                continue;
            }
            List<Component> lines = tickLines(element);
            if (lines.isEmpty()) continue;
            Placement p = placement(element.id());
            render(graphics, lines, mapX(p.x, width(lines, p.scale)), mapY(p.y, height(lines, p.scale)), p.scale, p.background);
        }
    }

    private static boolean safe(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The HUD's lines, built once per client tick rather than every frame: they only change as often as the game
     * state they show, and building them (text, formatting, lookups) every frame cost the most at high frame rates.
     */
    private static final Map<String, List<Component>> TICK_LINES = new java.util.HashMap<>();
    private static long linesTick = -1;
    private static long clientTicks;

    private static List<Component> tickLines(Element element) {
        if (linesTick != clientTicks) {
            TICK_LINES.clear();
            linesTick = clientTicks;
        }
        return TICK_LINES.computeIfAbsent(element.id(), id -> safeLines(element));
    }

    private static List<Component> safeLines(Element element) {
        try {
            List<Component> lines = element.lines().get();
            return lines == null ? List.of() : lines;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Where a text HUD with these lines is drawn on screen, after keeping it on screen: {x, y}. */
    public static int[] screenPosition(String id, List<Component> lines) {
        Placement p = placement(id);
        return new int[]{mapX(p.x, width(lines, p.scale)), mapY(p.y, height(lines, p.scale))};
    }

    /**
     * Screen x for a HUD saved at x with width w: its centre keeps the same fraction of the screen width as when it
     * was placed, so layouts survive GUI scale and window size changes. Always kept on screen.
     */
    public static int mapX(int x, int w) {
        // Before the window exists (mod init) there is nothing to map to.
        if (Minecraft.getInstance().getWindow() == null) return x;
        int screen = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        if (refWidth <= 0 || refWidth == screen) return clampX(x, w);
        return clampX(Math.round((x + w / 2f) * screen / refWidth - w / 2f), w);
    }

    public static int mapY(int y, int h) {
        if (Minecraft.getInstance().getWindow() == null) return y;
        int screen = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        if (refHeight <= 0 || refHeight == screen) return clampY(y, h);
        return clampY(Math.round((y + h / 2f) * screen / refHeight - h / 2f), h);
    }

    /** Called by /s2 gui once every position has been converted to the current screen. */
    public static void setReferenceToScreen() {
        refWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        refHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        save();
    }

    /** Keeps a HUD on screen even if its saved position is past the edge (e.g. after a window or GUI scale change). */
    private static int clampX(int x, int w) {
        return Math.max(0, Math.min(x, Minecraft.getInstance().getWindow().getGuiScaledWidth() - w));
    }

    private static int clampY(int y, int h) {
        return Math.max(0, Math.min(y, Minecraft.getInstance().getWindow().getGuiScaledHeight() - h));
    }

    /**
     * A custom HUD's visibility and size, worked out once per client tick: like text HUDs' lines, they only change as
     * often as the game state, and asking every frame (several times) was costly for HUDs that build their content.
     */
    private static final Map<String, Boolean> TICK_VISIBLE = new java.util.HashMap<>();
    private static final Map<String, int[]> TICK_SIZE = new java.util.HashMap<>();
    private static long customTick = -1;

    private static void refreshCustomCache() {
        if (customTick != clientTicks) {
            TICK_VISIBLE.clear();
            TICK_SIZE.clear();
            customTick = clientTicks;
        }
    }

    private static boolean customVisible(Element element) {
        refreshCustomCache();
        return TICK_VISIBLE.computeIfAbsent(element.id(), id -> element.custom().visible());
    }

    private static int[] customSize(Element element) {
        refreshCustomCache();
        return TICK_SIZE.computeIfAbsent(element.id(), id -> new int[]{element.custom().width(), element.custom().height()});
    }

    public static void renderCustom(GuiGraphicsExtractor graphics, Element element, boolean preview) {
        Placement p = placement(element.id());
        int[] size = customSize(element);
        int x = mapX(p.x, Math.round(size[0] * p.scale));
        int y = mapY(p.y, Math.round(size[1] * p.scale));
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(p.scale, p.scale);
        element.custom().render(graphics, preview);
        graphics.pose().popMatrix();
    }

    /** Scaled on-screen width of an element as shown in the editor. */
    public static int editorWidth(Element element) {
        float scale = placement(element.id()).scale;
        if (element.custom() != null) return Math.round(element.custom().width() * scale);
        return width(editorLines(element), scale);
    }

    /** Scaled on-screen height of an element as shown in the editor. */
    public static int editorHeight(Element element) {
        float scale = placement(element.id()).scale;
        if (element.custom() != null) return Math.round(element.custom().height() * scale);
        return height(editorLines(element), scale);
    }

    /** Lines to use for sizing and previewing in the editor. */
    public static List<Component> editorLines(Element element) {
        List<Component> live = safeLines(element);
        return live.isEmpty() ? element.preview() : live;
    }

    public static int width(List<Component> lines, float scale) {
        return Math.round((textWidth(lines) + PADDING * 2) * scale);
    }

    public static int height(List<Component> lines, float scale) {
        return Math.round((lines.size() * LINE_HEIGHT + PADDING * 2 - 2) * scale);
    }

    public static void render(GuiGraphicsExtractor graphics, List<Component> lines, int x, int y, float scale) {
        render(graphics, lines, x, y, scale, true);
    }

    /**
     * The widest line, by the list's identity, for the current tick. A HUD's lines are the same list all tick
     * (see tickLines), and they were measured three times a frame: twice to place the HUD, once to draw it.
     */
    private static final Map<List<Component>, Integer> TICK_WIDTH = new java.util.IdentityHashMap<>();
    private static long widthTick = -1;

    private static int textWidth(List<Component> lines) {
        if (widthTick != clientTicks) {
            TICK_WIDTH.clear();
            widthTick = clientTicks;
        }
        Integer cached = TICK_WIDTH.get(lines);
        if (cached != null) return cached;
        var font = Minecraft.getInstance().font;
        int w = 0;
        for (Component line : lines) w = Math.max(w, font.width(line));
        TICK_WIDTH.put(lines, w);
        return w;
    }

    public static void render(GuiGraphicsExtractor graphics, List<Component> lines, int x, int y, float scale, boolean background) {
        var font = Minecraft.getInstance().font;
        int w = textWidth(lines);
        int h = lines.size() * LINE_HEIGHT + PADDING * 2 - 2;
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(scale, scale);
        if (background) graphics.fill(0, 0, w + PADDING * 2, h, 0x80000000);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), PADDING, PADDING + i * LINE_HEIGHT, 0xFFFFFFFF, true);
        }
        graphics.pose().popMatrix();
    }

    public static void toggleBackground(String id) {
        Placement p = placement(id);
        p.background = !p.background;
    }

    public static void changeScale(String id, float delta) {
        Placement p = placement(id);
        p.scale = Math.max(0.5f, Math.min(3f, Math.round((p.scale + delta) * 10f) / 10f));
    }

    private static void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : root.entrySet()) {
                if (entry.getKey().equals("#refWidth")) refWidth = entry.getValue().getAsInt();
                if (entry.getKey().equals("#refHeight")) refHeight = entry.getValue().getAsInt();
                if (entry.getKey().startsWith("#")) continue;
                PLACEMENTS.put(entry.getKey(), GSON.fromJson(entry.getValue(), Placement.class));
            }
            // Backgrounds became opt-in; turn off the ones saved before that, once.
            if (!root.has(BACKGROUNDS_OFF)) {
                for (Placement p : PLACEMENTS.values()) p.background = false;
                com.epic60869.sky2m.Sky2MConfig config = com.epic60869.sky2m.Sky2MConfig.current();
                if (config != null) {
                    config.mining.commissions.background = false;
                    config.farming.rng.background = false;
                    com.epic60869.sky2m.Sky2MConfig.saveCurrent(config);
                }
                save();
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load HUD positions: " + e.getMessage());
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = GSON.toJsonTree(PLACEMENTS).getAsJsonObject();
            root.addProperty(BACKGROUNDS_OFF, true);
            if (refWidth > 0) root.addProperty("#refWidth", refWidth);
            if (refHeight > 0) root.addProperty("#refHeight", refHeight);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save HUD positions: " + e.getMessage());
        }
    }
}
