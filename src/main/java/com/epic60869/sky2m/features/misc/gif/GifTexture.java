package com.epic60869.sky2m.features.misc.gif;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * A decoded GIF playing in one texture: moving to the next frame writes its pixels over the old ones and uploads them,
 * instead of making a texture per frame. Shared by the GIF Player and the DVD screensaver.
 */
public final class GifTexture {
    private static int nextId;

    private final GifDecoder.Gif gif;
    private final Identifier id;
    private final DynamicTexture texture;
    private int frame;
    private long frameStartedAt = System.currentTimeMillis();

    public GifTexture(String name, GifDecoder.Gif gif) {
        this.gif = gif;
        this.id = Identifier.fromNamespaceAndPath("sky2m", "gif/" + (nextId++));
        NativeImage image = new NativeImage(gif.width(), gif.height(), false);
        write(image, gif.frames().getFirst().argb());
        this.texture = new DynamicTexture(() -> "sky2m gif " + name, image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    public int width() {
        return gif.width();
    }

    public int height() {
        return gif.height();
    }

    /** Moves on to the frame that should be showing now; {@code speed} 2 plays twice as fast. */
    public void advance(float speed) {
        List<GifDecoder.Frame> frames = gif.frames();
        if (frames.size() <= 1) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        // At most one lap per call, so a long pause (a loading screen) doesn't spin through frames.
        for (int guard = 0; guard < frames.size(); guard++) {
            long delay = Math.max(10, Math.round(frames.get(frame).delayMs() / Math.max(0.05f, speed)));
            if (now - frameStartedAt < delay) break;
            frameStartedAt += delay;
            frame = (frame + 1) % frames.size();
            changed = true;
        }
        if (now - frameStartedAt > 1000) frameStartedAt = now;
        if (changed && texture.getPixels() != null) {
            write(texture.getPixels(), frames.get(frame).argb());
            texture.upload();
        }
    }

    /** Draws the current frame stretched to {@code w} x {@code h}. */
    public void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, w, h, gif.width(), gif.height(), gif.width(), gif.height());
    }

    public void close() {
        Minecraft.getInstance().getTextureManager().release(id);
    }

    private static void write(NativeImage image, int[] argb) {
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) image.setPixel(x, y, argb[row + x]);
        }
    }
}
