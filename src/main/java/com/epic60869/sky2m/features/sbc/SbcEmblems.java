package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.PlainTextRenderable;
import net.minecraft.client.gui.font.SingleSpriteSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Image emblems (PNGs staff upload on shadowisabot.com) drawn as a text character, so they show everywhere a
 * player's symbols go: chat, the tab list (vanilla's and other mods'), name tags, Sky2M's screens.
 *
 * Each picture is its own font, {@code sky2m:emblem/<sha1 of url>}, with one 8x8 glyph (9 wide, like an item emoji).
 * {@code Sky2MEmblemFontMixin} hands Minecraft that glyph when it asks for the font; the glyph draws the downloaded
 * texture the same way vanilla draws player-head text sprites. Until the picture has downloaded the glyph is a blank
 * gap of the same width, so the line doesn't shift when it arrives.
 */
public final class SbcEmblems {
    private static final String PREFIX = "emblem/";
    /** Any character works (every one in the font is the picture); a private-use one keeps copied text clean. */
    private static final String CHAR = "";
    private static final GlyphInfo INFO = GlyphInfo.simple(9f);

    /** sha1(url) -> url, filled when a component for it is made. */
    private static final Map<String, String> URLS = new ConcurrentHashMap<>();
    private static final Map<String, Texture> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, GlyphSource> SOURCES = new ConcurrentHashMap<>();

    private record Texture(Identifier id, GlyphRenderTypes renderTypes) {}

    private SbcEmblems() {}

    /** The emblem at {@code url} as one character of text, keeping {@code style}'s hover and colour. */
    public static MutableComponent component(String url, Style style) {
        String hash = SbcCosmetics.hash(url);
        URLS.putIfAbsent(hash, url);
        // White so the picture keeps its own colours; no shadow (a dark copy of a picture looks smudged).
        return Component.literal(CHAR).withStyle(style.withColor(0xFFFFFF).withoutShadow()
            .withFont(new FontDescription.Resource(Compat.id(PREFIX + hash))));
    }

    /** The glyphs for one of our emblem fonts, or null for any other font (vanilla handles those). */
    public static GlyphSource source(Identifier font) {
        if (!"sky2m".equals(font.getNamespace()) || !font.getPath().startsWith(PREFIX)) return null;
        String hash = font.getPath().substring(PREFIX.length());
        return SOURCES.computeIfAbsent(hash, h -> new SingleSpriteSource(new Glyph(h)));
    }

    /** The texture, once downloaded; starts the download the first time. */
    private static Texture texture(String hash) {
        Texture texture = TEXTURES.get(hash);
        if (texture != null) return texture;
        String url = URLS.get(hash);
        if (url != null) SbcCosmetics.loadImage(url, "emblems", "Emblem", image -> {
            Identifier id = Compat.id("emblems/" + hash);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "sky2m emblem " + hash, image));
            TEXTURES.put(hash, new Texture(id, GlyphRenderTypes.createForColorTexture(id)));
        });
        return null;
    }

    private record Glyph(String hash) implements BakedGlyph {
        @Override
        public GlyphInfo info() {
            return INFO;
        }

        @Override
        public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
            Texture texture = texture(hash);
            return texture == null ? null : new Instance(texture, x, y, color, shadowColor, shadowOffset, style);
        }
    }

    /** One drawn emblem: the whole picture squeezed into the 8x8 box vanilla uses for text sprites. {@code style} keeps its hover text. */
    private record Instance(Texture texture, float x, float y, int color, int shadowColor, float shadowOffset, Style style) implements PlainTextRenderable {
        @Override
        public void renderSprite(Matrix4fc pose, VertexConsumer buffer, int light, float offsetX, float offsetY, float z, int color) {
            float x0 = offsetX + left();
            float x1 = offsetX + right();
            float y0 = offsetY + top();
            float y1 = offsetY + bottom();
            buffer.addVertex(pose, x0, y0, z).setUv(0, 0).setColor(color).setLight(light);
            buffer.addVertex(pose, x0, y1, z).setUv(0, 1).setColor(color).setLight(light);
            buffer.addVertex(pose, x1, y1, z).setUv(1, 1).setColor(color).setLight(light);
            buffer.addVertex(pose, x1, y0, z).setUv(1, 0).setColor(color).setLight(light);
        }

        @Override
        public RenderType renderType(Font.DisplayMode displayMode) {
            return texture.renderTypes().select(displayMode);
        }

        @Override
        public RenderPipeline guiPipeline() {
            return texture.renderTypes().guiPipeline();
        }

        @Override
        public GpuTextureView textureView() {
            return Minecraft.getInstance().getTextureManager().getTexture(texture.id()).getTextureView();
        }
    }
}
