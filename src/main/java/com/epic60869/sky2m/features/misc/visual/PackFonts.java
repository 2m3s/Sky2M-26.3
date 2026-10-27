package com.epic60869.sky2m.features.misc.visual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Misc > Fonts: a resource pack's bitmap font (a .zip in config/sky2m/fonts or in the game's resourcepacks folder) as a
 * font choice, without turning the pack on. A pack with its own font/default.json uses its bitmap providers; a pack
 * that only swaps the font textures (like most font overlays) is drawn with Minecraft's own bitmap layout and its
 * textures. Only the pack's textures are read; nothing is copied or shipped.
 */
public final class PackFonts {
    private static final String OWN_FONT = "assets/minecraft/font/default.json";
    private static final String VANILLA_FONT = "/assets/minecraft/font/include/default.json";

    private PackFonts() {}

    /** Font resource packs by file name: the fonts folder first, then the game's resourcepacks folder. */
    public static Map<String, Path> packs() {
        Map<String, Path> out = new LinkedHashMap<>();
        for (Path dir : List.of(CustomFonts.folder(), FabricLoader.getInstance().getGameDir().resolve("resourcepacks"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted()
                    .filter(PackFonts::hasFont)
                    .forEach(p -> out.putIfAbsent(p.getFileName().toString(), p));
            } catch (IOException ignored) {}
        }
        return out;
    }

    /** A pack with a font definition or a replaced default font texture. */
    private static boolean hasFont(Path zip) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            return zf.getEntry(OWN_FONT) != null || zf.getEntry("assets/minecraft/textures/font/ascii.png") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The pack's font, or null if it has none that loads. */
    public static GlyphProvider load(Path zip) {
        List<GlyphProvider> loaded = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ResourceManager resources = new ZipResources(zf);
            for (JsonElement provider : definitions(zf)) {
                if (!provider.isJsonObject()) continue;
                JsonObject def = provider.getAsJsonObject();
                if (!"bitmap".equals(def.has("type") ? def.get("type").getAsString() : "")) continue;
                Identifier file = Identifier.parse(def.get("file").getAsString());
                // Overlays only replace some textures: the others stay with Minecraft's fonts behind this one.
                if (zf.getEntry("assets/" + file.getNamespace() + "/textures/" + file.getPath()) == null) continue;
                BitmapProvider.Definition definition = BitmapProvider.Definition.CODEC.codec()
                    .parse(JsonOps.INSTANCE, def).getOrThrow();
                var loader = definition.unpack().left().orElse(null);
                if (loader != null) loaded.add(loader.load(resources));
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load the font in " + zip.getFileName() + ": " + e.getMessage());
            loaded.forEach(GlyphProvider::close);
            return null;
        }
        if (loaded.isEmpty()) {
            System.err.println("[Sky2M] " + zip.getFileName() + " has no bitmap font textures");
            return null;
        }
        return new Stack(List.copyOf(loaded));
    }

    /** The pack's own font providers, or Minecraft's when the pack only swaps textures. */
    private static List<JsonElement> definitions(ZipFile zf) throws IOException {
        ZipEntry own = zf.getEntry(OWN_FONT);
        try (InputStream in = own != null ? zf.getInputStream(own) : Minecraft.class.getResourceAsStream(VANILLA_FONT)) {
            if (in == null) throw new IOException("Minecraft's font definition is missing");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<JsonElement> out = new ArrayList<>();
            root.getAsJsonArray("providers").forEach(out::add);
            return out;
        }
    }

    /**
     * Several bitmap providers as one font: the first that has a character draws it. Spaces stay with Minecraft's
     * space provider: a bitmap's empty space cell would measure next to nothing wide.
     */
    private record Stack(List<GlyphProvider> providers) implements GlyphProvider {
        private static boolean isSpace(int cp) {
            return cp == ' ' || cp == 0xA0 || Character.isWhitespace(cp) || Character.isSpaceChar(cp);
        }

        @Override
        public UnbakedGlyph getGlyph(int cp) {
            if (isSpace(cp)) return null;
            for (GlyphProvider p : providers) {
                UnbakedGlyph glyph = p.getGlyph(cp);
                if (glyph != null) return glyph;
            }
            return null;
        }

        @Override
        public IntSet getSupportedGlyphs() {
            IntSet out = new IntOpenHashSet();
            providers.forEach(p -> out.addAll(p.getSupportedGlyphs()));
            out.removeIf(Stack::isSpace);
            return out;
        }

        @Override
        public void close() {
            providers.forEach(GlyphProvider::close);
        }
    }

    /** Serves the zip's files to Minecraft's bitmap font loader. */
    private record ZipResources(ZipFile zip) implements ResourceManager {
        @Override
        public Optional<Resource> getResource(Identifier id) {
            ZipEntry entry = zip.getEntry("assets/" + id.getNamespace() + "/" + id.getPath());
            return entry == null ? Optional.empty() : Optional.of(new Resource((PackResources) null, IoSupplier.create(zip, entry)));
        }

        @Override
        public Set<String> getNamespaces() {
            return Set.of(Identifier.DEFAULT_NAMESPACE);
        }

        @Override
        public List<Resource> getResourceStack(Identifier id) {
            return getResource(id).map(List::of).orElse(List.of());
        }

        @Override
        public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> filter) {
            return Map.of();
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String path, Predicate<Identifier> filter) {
            return Map.of();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
    }
}
