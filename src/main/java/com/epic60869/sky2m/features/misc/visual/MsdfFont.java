package com.epic60869.sky2m.features.misc.visual;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The built-in Inter drawn the way Caxton draws it: from a multi-channel signed distance field (MTSDF) atlas made with
 * msdf-atlas-gen, through a shader that rebuilds sharp letter edges at any size ({@code sky2m:core/msdf_text}), instead
 * of a small pre-drawn bitmap per letter. Glyphs are vanilla {@link BakedSheetGlyph}s pointing at the atlas, drawn with
 * Sky2M's MSDF text pipelines; layout, colour, shadow and bold stay Minecraft's.
 */
public final class MsdfFont {
    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("sky2m", "textures/font/inter_msdf.png");
    private static final String METRICS = "/assets/sky2m/msdf/inter.json";
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("sky2m", "core/msdf_text");
    private static final Identifier TEXT_SHADER = Identifier.withDefaultNamespace("core/text");
    /** Minecraft's baseline is this far below the top of a line of text. */
    private static final float BASELINE = 7f;

    private static RenderPipeline worldPipeline, seeThroughPipeline, polygonOffsetPipeline, guiPipeline;
    private static GlyphRenderTypes renderTypes;

    private MsdfFont() {}

    /** Registers the MSDF text pipelines at startup, so they're compiled with the others. */
    public static void init() {
        worldPipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("sky2m", "pipeline/msdf_text"))
            .withVertexShader(TEXT_SHADER).withFragmentShader(SHADER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build());
        seeThroughPipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.TEXT_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("sky2m", "pipeline/msdf_text_see_through"))
            .withVertexShader(TEXT_SHADER).withFragmentShader(SHADER)
            .withShaderDefine("IS_SEE_THROUGH").withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(Optional.empty()).build());
        polygonOffsetPipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("sky2m", "pipeline/msdf_text_polygon_offset"))
            .withVertexShader(TEXT_SHADER).withFragmentShader(SHADER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 1.0f, 10.0f)).build());
        guiPipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXT_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("sky2m", "pipeline/msdf_gui_text"))
            .withVertexShader(TEXT_SHADER).withFragmentShader(SHADER).build());
    }

    private static GlyphRenderTypes renderTypes() {
        if (renderTypes == null) {
            renderTypes = new GlyphRenderTypes(
                RenderType.create("sky2m_msdf_text", RenderSetup.builder(worldPipeline).withTexture("Sampler0", ATLAS).useLightmap().createRenderSetup()),
                RenderType.create("sky2m_msdf_text_see_through", RenderSetup.builder(seeThroughPipeline).withTexture("Sampler0", ATLAS).useLightmap().createRenderSetup()),
                RenderType.create("sky2m_msdf_text_polygon_offset", RenderSetup.builder(polygonOffsetPipeline).withTexture("Sampler0", ATLAS).useLightmap().createRenderSetup()),
                guiPipeline);
        }
        return renderTypes;
    }

    /**
     * Inter from the atlas at {@code size} GUI pixels per em, moved down by {@code shift} (Height - 1, so the default
     * Height of 1 sits on Minecraft's baseline). Null if the atlas metrics can't be read.
     */
    public static GlyphProvider inter(float size, float shift) {
        try (InputStream in = MsdfFont.class.getResourceAsStream(METRICS)) {
            if (in == null) throw new IllegalStateException(METRICS + " not found");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject atlas = root.getAsJsonObject("atlas");
            float width = atlas.get("width").getAsFloat();
            float height = atlas.get("height").getAsFloat();
            Int2ObjectOpenHashMap<UnbakedGlyph> glyphs = new Int2ObjectOpenHashMap<>();
            for (var element : root.getAsJsonArray("glyphs")) {
                JsonArray g = element.getAsJsonArray();
                float advance = g.get(1).getAsFloat() * size;
                GlyphInfo info = () -> advance;
                if (g.size() < 10) {
                    // A space: advance only.
                    glyphs.put(g.get(0).getAsInt(), new Glyph(info, 0, 0, 0, 0, 0, 0, 0, 0));
                    continue;
                }
                glyphs.put(g.get(0).getAsInt(), new Glyph(info,
                    g.get(6).getAsFloat() / width, g.get(8).getAsFloat() / width,
                    g.get(7).getAsFloat() / height, g.get(9).getAsFloat() / height,
                    g.get(2).getAsFloat() * size, g.get(4).getAsFloat() * size,
                    BASELINE + shift + g.get(3).getAsFloat() * size, BASELINE + shift + g.get(5).getAsFloat() * size));
            }
            return new Provider(glyphs);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load the Inter MSDF font: " + e.getMessage());
            return null;
        }
    }

    private record Provider(Int2ObjectOpenHashMap<UnbakedGlyph> glyphs) implements GlyphProvider {
        @Override
        public UnbakedGlyph getGlyph(int codepoint) {
            return glyphs.get(codepoint);
        }

        @Override
        public IntSet getSupportedGlyphs() {
            return glyphs.keySet();
        }
    }

    /** One letter: its place in the atlas (u, v) and on the line (left, right, top, bottom, in GUI pixels). */
    private record Glyph(GlyphInfo info, float u0, float u1, float v0, float v1, float left, float right, float top, float bottom)
        implements UnbakedGlyph {
        @Override
        public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
            // Baked on the render thread the first time the letter is drawn; the atlas texture is loaded then.
            var view = Minecraft.getInstance().getTextureManager().getTexture(ATLAS).getTextureView();
            return new BakedSheetGlyph(info, renderTypes(), view, u0, u1, v0, v1, left, right, top, bottom);
        }
    }
}
