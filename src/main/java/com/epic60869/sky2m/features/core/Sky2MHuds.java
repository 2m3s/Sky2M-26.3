package com.epic60869.sky2m.features.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
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
    public static final String LAYOUT_DEFAULT = "Default";
    public static final String LAYOUT_DUNGEONS = "Dungeons";
    public static final String LAYOUT_GARDEN = "Garden";
    public static final String LAYOUT_MINING = "Mining";
    /** Saved under this key in the file; PLACEMENTS is the default layout, as it always was. */
    private static final String LAYOUTS_KEY = "#layouts";
    /** HUD Layouts Per Area: layout -> HUD id -> placement, holding only what was moved in that layout. */
    private static final Map<String, Map<String, Placement>> LAYOUTS = new LinkedHashMap<>();
    private static String activeLayout = LAYOUT_DEFAULT;
    private static long layoutTick = -1;
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
        // New players start from Sky2M's default HUD layout; an existing file is never replaced.
        com.epic60869.sky2m.config.Sky2MConfig.copyBundledDefault(file, "sky2m-huds.json");
        load();
        // Drawn before the tab list, so holding Tab shows the list over the HUDs (like every Sky2M HUD layer).
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, ID, (graphics, delta) -> renderAll(graphics));
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
        return safe(element, setting != null ? setting : element.enabled());
    }

    public static List<Element> elements() {
        return new ArrayList<>(ELEMENTS.values());
    }

    /** A HUD that replaces others gets the first of their positions that was saved, the first time. */
    public static void inheritPlacement(String id, List<String> oldIds) {
        if (PLACEMENTS.containsKey(id)) return;
        for (String old : oldIds) {
            Placement from = PLACEMENTS.get(old);
            if (from == null) continue;
            Placement p = new Placement();
            p.x = from.x;
            p.y = from.y;
            p.scale = from.scale;
            p.background = from.background;
            PLACEMENTS.put(id, p);
            return;
        }
    }

    /**
     * The placement in use where you are: this area's layout when it has one for this HUD, else the default layout's.
     * Asking for it counts as the HUD being on screen ({@link #drawnRecently}): HUDs that draw themselves in a menu (the
     * Storage Value, Profit Tracker, Composter, Anita shop...) never go through {@link #renderAll}, and read this just
     * before drawing. Not while /s2 gui or the HUD overview is open, which read every HUD's.
     */
    public static Placement placement(String id) {
        noteDrawn(id);
        Placement base = PLACEMENTS.computeIfAbsent(id, k -> new Placement());
        String group = layoutGroup();
        return group.equals(LAYOUT_DEFAULT) ? base : resolveLayout(LAYOUTS.get(group), id, base);
    }

    /**
     * The placement to change: only {@link #placement} is read every frame, so an area's layout only ever holds what was
     * really moved there. The first change in an area copies the HUD's default placement, and leaves the default alone.
     */
    public static Placement editPlacement(String id) {
        String group = layoutGroup();
        if (group.equals(LAYOUT_DEFAULT)) return placement(id);
        Placement from = placement(id);
        return LAYOUTS.computeIfAbsent(group, k -> new LinkedHashMap<>()).computeIfAbsent(id, k -> copy(from));
    }

    private static Placement copy(Placement from) {
        Placement p = new Placement();
        p.x = from.x;
        p.y = from.y;
        p.scale = from.scale;
        p.background = from.background;
        return p;
    }

    /** A HUD's placement in a layout, or the default one when that layout has none for it (or there's no layout). Public for the game test. */
    public static <T> T resolveLayout(Map<String, T> layout, String id, T fallback) {
        T saved = layout == null ? null : layout.get(id);
        return saved != null ? saved : fallback;
    }

    /** Which layout an area uses. Public for the game test. */
    public static String layoutGroup(String area, boolean inDungeon) {
        if (inDungeon) return LAYOUT_DUNGEONS;
        return switch (area == null ? "" : area) {
            case "Garden" -> LAYOUT_GARDEN;
            case "Dwarven Mines", "Crystal Hollows", "Mineshaft" -> LAYOUT_MINING;
            default -> LAYOUT_DEFAULT;
        };
    }

    /** The layout in use now: always the default one while HUD Layouts Per Area is off. Worked out once per client tick. */
    public static String layoutGroup() {
        if (layoutTick != clientTicks) {
            layoutTick = clientTicks;
            com.epic60869.sky2m.config.Sky2MConfig config = com.epic60869.sky2m.config.Sky2MConfig.current();
            activeLayout = config != null && config.general.hudLayoutsPerArea
                ? layoutGroup(Sky2MLocation.area(), Sky2MLocation.inDungeon()) : LAYOUT_DEFAULT;
        }
        return activeLayout;
    }

    private static void renderAll(GuiGraphicsExtractor graphics) {
        if (com.epic60869.sky2m.features.misc.storage.StorageOverlay.hidesOtherHuds()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!Sky2MLocation.skyblockOnlyAllows()) return;
        // The held item editor has no background (your hand shows through it): HUDs would draw over its panel.
        if (com.epic60869.sky2m.features.helditem.HeldItemEditorScreen.editing()) return;
        // Positions saved before the reference size existed were placed on this screen size.
        if (refWidth <= 0 || refHeight <= 0) setReferenceToScreen();
        Sky2MPerf.frame();
        boolean stamp = !(mc.gui.screen() instanceof com.epic60869.sky2m.gui.Sky2MHudEditorScreen);
        long now = System.currentTimeMillis();
        if (stamp) lastLiveMs = now;
        for (Element element : ELEMENTS.values()) {
            if (!safe(element, element.enabled())) continue;
            long started = Sky2MPerf.start();
            if (element.custom() != null) {
                try {
                    if (customVisible(element)) {
                        if (stamp) LAST_DRAWN.put(element.id(), now);
                        renderCustom(graphics, element, false);
                    }
                } catch (Exception e) {
                    Sky2MErrors.caught(element.name(), e, "HUD " + element.id());
                }
                Sky2MPerf.end(Sky2MPerf.Kind.HUD, element.name(), started);
                continue;
            }
            List<Component> lines = tickLines(element);
            if (!lines.isEmpty()) {
                if (stamp) LAST_DRAWN.put(element.id(), now);
                Placement p = placement(element.id());
                render(graphics, lines, mapX(p.x, width(lines, p.scale)), mapY(p.y, height(lines, p.scale)), p.scale, p.background);
            }
            Sky2MPerf.end(Sky2MPerf.Kind.HUD, element.name(), started);
        }
    }

    /** When each HUD last actually drew, for /s2 gui to list only what is on screen (like SkyHanni's editor). */
    private static final Map<String, Long> LAST_DRAWN = new java.util.HashMap<>();
    private static long lastLiveMs;
    private static final long SEEN_GRACE_MS = 10_000L;

    /**
     * The moment the HUD layer last drew. Nothing draws while a screen hides it, so /s2 gui measures its grace window
     * from here rather than from the clock.
     */
    public static long lastLive() {
        return lastLiveMs == 0L ? System.currentTimeMillis() : lastLiveMs;
    }

    private static void noteDrawn(String id) {
        if (!ELEMENTS.containsKey(id)) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (screen instanceof com.epic60869.sky2m.gui.Sky2MHudEditorScreen || screen instanceof Sky2MHudOverviewScreen) return;
        LAST_DRAWN.put(id, System.currentTimeMillis());
    }

    /** Whether the HUD drew within 10 seconds of {@code asOfMs}. */
    public static boolean drawnRecently(String id, long asOfMs) {
        Long at = LAST_DRAWN.get(id);
        return at != null && asOfMs - at <= SEEN_GRACE_MS;
    }

    private static boolean safe(Element element, BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (Exception e) {
            Sky2MErrors.caught(element.name(), e);
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
            Sky2MErrors.caught(element.name(), e);
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

    /**
     * Called by /s2 gui once every position has been converted to the current screen. The saved area layouts share the one
     * reference size, so they (and, while an area's layout is being edited, the default one) keep their place here.
     */
    public static void setReferenceToScreen() {
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        if (refWidth > 0 && refHeight > 0 && (refWidth != screenW || refHeight != screenH)) {
            for (Map<String, Placement> layout : LAYOUTS.values()) rescale(layout, screenW, screenH);
            if (!layoutGroup().equals(LAYOUT_DEFAULT)) rescale(PLACEMENTS, screenW, screenH);
        }
        refWidth = screenW;
        refHeight = screenH;
        save();
    }

    private static void rescale(Map<String, Placement> layout, int screenW, int screenH) {
        for (Placement p : layout.values()) {
            p.x = Math.round(p.x * (float) screenW / refWidth);
            p.y = Math.round(p.y * (float) screenH / refHeight);
        }
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
        // Popped even if the HUD throws, so one broken HUD doesn't shift every HUD drawn after it.
        try {
            element.custom().render(graphics, preview);
        } finally {
            graphics.pose().popMatrix();
        }
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
        // HUD theme (colour-blind palettes, high contrast); the same lines when the theme is Default.
        List<Component> themed = Sky2MTheme.lines(lines);
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(scale, scale);
        if (background) graphics.fill(0, 0, w + PADDING * 2, h, Sky2MTheme.background(0x80000000));
        for (int i = 0; i < themed.size(); i++) {
            graphics.text(font, themed.get(i), PADDING, PADDING + i * LINE_HEIGHT, Sky2MTheme.argb(0xFFFFFFFF), true);
        }
        graphics.pose().popMatrix();
    }

    public static void toggleBackground(String id) {
        Placement p = editPlacement(id);
        p.background = !p.background;
    }

    public static void changeScale(String id, float delta) {
        Placement p = editPlacement(id);
        p.scale = Math.max(0.5f, Math.min(3f, Math.round((p.scale + delta) * 10f) / 10f));
    }

    /**
     * Puts a registered HUD back at its registered x/y and scale 1 (the background choice is kept), and saves. In an area's
     * layout it drops that layout's position instead, so the HUD goes back to following the default layout.
     */
    public static boolean resetPlacement(String id) {
        Element element = ELEMENTS.get(id);
        if (element == null) return false;
        resetOne(element);
        save();
        return true;
    }

    /** {@link #resetPlacement} for every registered HUD, saved once. */
    public static void resetAllPlacements() {
        for (Element element : ELEMENTS.values()) resetOne(element);
        save();
    }

    private static void resetOne(Element element) {
        Map<String, Placement> layout = LAYOUTS.get(layoutGroup());
        if (!layoutGroup().equals(LAYOUT_DEFAULT)) {
            if (layout != null) layout.remove(element.id());
            return;
        }
        Placement p = placement(element.id());
        p.x = element.defaultX();
        p.y = element.defaultY();
        p.scale = 1f;
    }

    /**
     * Whether the saved position puts part of the HUD past the screen edge on this window: it is still drawn, pulled
     * back to the edge, but not where it was placed.
     */
    public static boolean offScreen(Element element) {
        if (Minecraft.getInstance().getWindow() == null) return false;
        try {
            Placement p = placement(element.id());
            int w = editorWidth(element), h = editorHeight(element);
            int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int x = refWidth <= 0 ? p.x : Math.round((p.x + w / 2f) * screenW / refWidth - w / 2f);
            int y = refHeight <= 0 ? p.y : Math.round((p.y + h / 2f) * screenH / refHeight - h / 2f);
            return x < 0 || y < 0 || x + w > screenW || y + h > screenH;
        } catch (Exception e) {
            Sky2MErrors.caught(element.name(), e);
            return false;
        }
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
            if (root.has(LAYOUTS_KEY)) {
                for (var layout : root.getAsJsonObject(LAYOUTS_KEY).entrySet()) {
                    Map<String, Placement> saved = new LinkedHashMap<>();
                    for (var entry : layout.getValue().getAsJsonObject().entrySet()) {
                        saved.put(entry.getKey(), GSON.fromJson(entry.getValue(), Placement.class));
                    }
                    LAYOUTS.put(layout.getKey(), saved);
                }
            }
            // Backgrounds became opt-in; turn off the ones saved before that, once.
            if (!root.has(BACKGROUNDS_OFF)) {
                for (Placement p : PLACEMENTS.values()) p.background = false;
                com.epic60869.sky2m.config.Sky2MConfig config = com.epic60869.sky2m.config.Sky2MConfig.current();
                if (config != null) {
                    config.mining.commissions.background = false;
                    config.misc.rngHud.background = false;
                    com.epic60869.sky2m.config.Sky2MConfig.saveCurrent(config);
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
            if (!LAYOUTS.isEmpty()) root.add(LAYOUTS_KEY, GSON.toJsonTree(LAYOUTS));
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save HUD positions: " + e.getMessage());
        }
    }
}
