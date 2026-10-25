package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Misc > Fonts: the font all text is drawn in. Default is Minecraft's; Inter is built in (the bitmap Inter sized like
 * Caxton's default, {@code sky2m:inter}); anything else is a .ttf from config/sky2m/fonts. The chosen font goes in
 * front of {@code minecraft:default} when the fonts load ({@code Sky2MCustomFontMixin}), so a character it doesn't
 * have (Hypixel's icons, other scripts) still comes from Minecraft's fonts.
 */
public final class CustomFonts {
    public static final String DEFAULT = "";
    public static final String INTER = "Inter";

    private static final Identifier DEFAULT_FONT = Identifier.withDefaultNamespace("default");
    private static final Identifier INTER_FONT = Identifier.fromNamespaceAndPath("sky2m", "inter");

    /** The .ttf loaded for the font sets being built, and the one the font sets in use were built with. */
    private static volatile GlyphProvider loaded;
    private static GlyphProvider inUse;

    private CustomFonts() {}

    private static Sky2MConfig.Fonts config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.fonts;
    }

    /** config/sky2m/fonts, made if it isn't there. */
    public static Path folder() {
        Path folder = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("fonts");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            System.err.println("[Sky2M] Could not make the fonts folder: " + e.getMessage());
        }
        return folder;
    }

    /** The .ttf files in the fonts folder, by name. */
    public static List<String> fontFiles() {
        try (Stream<Path> files = Files.list(folder())) {
            return files.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".ttf"))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The chosen font: {@link #DEFAULT}, {@link #INTER} or a file name in the fonts folder. */
    public static String selected() {
        Sky2MConfig.Fonts c = config();
        return c == null || c.font == null ? DEFAULT : c.font;
    }

    public static void select(String font) {
        Sky2MConfig.Fonts c = config();
        if (c == null) return;
        c.font = font;
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        reload();
    }

    /** Loads the fonts again with the current choice (a resource reload, as changing a resource pack does). */
    public static void reload() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(mc::reloadResourcePacks);
    }

    public static void openFolder() {
        com.mojang.blaze3d.Blaze3D.openPath(folder());
    }

    public static void openPicker() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new PickerScreen(mc.gui.screen())));
    }

    /**
     * From the font manager, with every font's glyph providers just worked out: puts the chosen font in front of
     * {@code minecraft:default}. Runs off the main thread while the fonts load.
     */
    public static Map<Identifier, List<GlyphProvider.Conditional>> apply(Map<Identifier, List<GlyphProvider.Conditional>> fonts) {
        loaded = null;
        String choice = selected();
        if (choice.isEmpty() || !fonts.containsKey(DEFAULT_FONT)) return fonts;
        List<GlyphProvider.Conditional> front;
        if (choice.equals(INTER)) {
            front = fonts.get(INTER_FONT);
            if (front == null) return fonts;
        } else {
            GlyphProvider ttf = loadTtf(folder().resolve(choice));
            if (ttf == null) return fonts;
            loaded = ttf;
            front = List.of(new GlyphProvider.Conditional(ttf, FontOption.Filter.ALWAYS_PASS));
        }
        List<GlyphProvider.Conditional> merged = new ArrayList<>(front);
        merged.addAll(fonts.get(DEFAULT_FONT));
        Map<Identifier, List<GlyphProvider.Conditional>> out = new HashMap<>(fonts);
        out.put(DEFAULT_FONT, merged);
        return out;
    }

    /** The new font sets are in use: the .ttf the old ones were built with can be closed. */
    public static void afterApply() {
        if (inUse != null && inUse != loaded) inUse.close();
        inUse = loaded;
    }

    /** A .ttf as Minecraft's own "ttf" font provider loads one, with the Size, Sharpness and Height settings. */
    private static GlyphProvider loadTtf(Path file) {
        Sky2MConfig.Fonts c = config();
        float size = c == null ? 10f : c.size;
        float oversample = c == null ? 4f : c.sharpness;
        float shift = c == null ? 1f : c.height;
        ByteBuffer buffer = null;
        try (InputStream in = Files.newInputStream(file)) {
            buffer = TextureUtil.readResource(in);
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                FT_Face face;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer pointer = stack.mallocPointer(1);
                    FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), buffer, 0L, pointer), "Initializing font face");
                    face = FT_Face.create(pointer.get());
                }
                String format = FreeType.FT_Get_Font_Format(face);
                if (!"TrueType".equals(format)) {
                    FreeType.FT_Done_Face(face);
                    throw new IOException("it is " + format + ", not TrueType");
                }
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE), "Find unicode charmap");
                return new TrueTypeGlyphProvider(buffer, face, size, oversample, 0f, shift, "");
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load the font " + file.getFileName() + ": " + e.getMessage());
            if (buffer != null) MemoryUtil.memFree(buffer);
            return null;
        }
    }

    /** "Default", "Inter" and each .ttf in the fonts folder as a button; the chosen one is marked. */
    private static final class PickerScreen extends Screen {
        private final Screen parent;

        PickerScreen(Screen parent) {
            super(Component.literal("Font"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            clearWidgets();
            List<String[]> choices = new ArrayList<>();
            choices.add(new String[]{"Default (Minecraft)", DEFAULT});
            choices.add(new String[]{"Inter (built in)", INTER});
            for (String file : fontFiles()) choices.add(new String[]{file, file});
            int w = 220;
            int x = (width - w) / 2;
            int y = 44;
            for (String[] choice : choices) {
                String label = (choice[1].equals(selected()) ? "> " : "") + choice[0];
                addRenderableWidget(Button.builder(Component.literal(label), b -> {
                    select(choice[1]);
                    init();
                }).bounds(x, y, w, 20).build());
                y += 24;
                if (y > height - 60) break;
            }
            addRenderableWidget(Button.builder(Component.literal("Open Fonts Folder"), b -> openFolder())
                .bounds(x, height - 30, 106, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x + w - 106, height - 30, 106, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xFF090B10);
            g.text(font, "Font", (width - font.width("Font")) / 2, 16, 0xFFFFFFFF, true);
            String hint = "Put .ttf files in the fonts folder, then pick one here.";
            g.text(font, hint, (width - font.width(hint)) / 2, 28, 0xFF9AA4B2, false);
            super.extractRenderState(g, mouseX, mouseY, delta);
        }

        @Override
        public void onClose() {
            minecraft.gui.setScreen(parent);
        }
    }
}
