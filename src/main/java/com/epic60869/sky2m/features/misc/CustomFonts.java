package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
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
    public static final String DEFAULT = "Default";
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
        return c == null || c.font == null || c.font.isBlank() ? DEFAULT : c.font;
    }

    /** Settings: the Font option is a dropdown of {@link #choices()}, read again when the fonts folder changes. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface FontDropdown {}

    /** "Default", "Inter", then each .ttf in the fonts folder. */
    public static String[] choices() {
        List<String> out = new ArrayList<>(List.of(DEFAULT, INTER));
        out.addAll(fontFiles());
        return out.toArray(String[]::new);
    }

    private static String applied;
    private static List<String> listed = List.of();
    private static int ticks;

    /**
     * Picking a font in the dropdown only changes the setting: this loads it (a resource reload) once the choice
     * changed, and rebuilds the settings when .ttf files are added or removed so the dropdown lists them.
     */
    public static void init() {
        listed = fontFiles();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 20 != 0) return;
            String choice = selected();
            if (applied == null) applied = choice;
            else if (!choice.equals(applied)) {
                applied = choice;
                reload();
            }
            boolean settingsOpen = mc.gui.screen() != null && mc.gui.screen().getClass().getName().contains("moulconfig");
            if (ticks % 100 == 0 && !settingsOpen) {
                List<String> files = fontFiles();
                if (!files.equals(listed)) {
                    listed = files;
                    Sky2MConfig.rebuildEditors();
                }
            }
        });
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


    /**
     * From the font manager, with every font's glyph providers just worked out: puts the chosen font in front of
     * {@code minecraft:default}. Runs off the main thread while the fonts load.
     */
    public static Map<Identifier, List<GlyphProvider.Conditional>> apply(Map<Identifier, List<GlyphProvider.Conditional>> fonts) {
        loaded = null;
        String choice = selected();
        if (choice.equals(DEFAULT) || !fonts.containsKey(DEFAULT_FONT)) return fonts;
        List<GlyphProvider.Conditional> front;
        if (choice.equals(INTER)) {
            front = fonts.get(INTER_FONT);
            if (front == null) {
                System.out.println("[Sky2M] Font: Inter wasn't loaded (sky2m:inter), using Minecraft's font");
                return fonts;
            }
        } else {
            GlyphProvider ttf = loadTtf(folder().resolve(choice));
            if (ttf == null) return fonts;
            loaded = ttf;
            front = List.of(new GlyphProvider.Conditional(ttf, FontOption.Filter.ALWAYS_PASS));
        }
        // These lists run lowest priority first (the font set is built from the list reversed), so the chosen font
        // goes last to come before Minecraft's glyphs.
        List<GlyphProvider.Conditional> merged = new ArrayList<>(fonts.get(DEFAULT_FONT));
        merged.addAll(front);
        Map<Identifier, List<GlyphProvider.Conditional>> out = new HashMap<>(fonts);
        out.put(DEFAULT_FONT, merged);
        System.out.println("[Sky2M] Font: " + choice + " in front of minecraft:default (" + front.size() + " + " + fonts.get(DEFAULT_FONT).size() + " glyph providers)");
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

}
