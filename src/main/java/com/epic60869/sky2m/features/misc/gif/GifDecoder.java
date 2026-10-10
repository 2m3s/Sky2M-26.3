package com.epic60869.sky2m.features.misc.gif;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decodes a GIF into whole frames. ImageIO's GIF reader gives each frame as the (often partial) patch it draws, at its
 * own offset, so the frames are put together here following each frame's disposal method.
 * <p>
 * Ported from Killer560's Mod (gifplayer/GifDecoder), MIT License, Copyright (c) 2026 Killer560.
 */
public final class GifDecoder {
    /** Frames bigger than this (in pixels) are refused, so a huge GIF can't eat all the memory. */
    private static final long MAX_PIXELS = 1024L * 1024L * 64L;

    public record Frame(int[] argb, int delayMs) {}

    public record Gif(int width, int height, List<Frame> frames) {}

    private GifDecoder() {}

    public static boolean isGif(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    public static Gif decode(Path file) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) throw new IOException("Couldn't open " + file.getFileName());
            ImageReader reader = ImageIO.getImageReadersBySuffix("gif").next();
            try {
                reader.setInput(stream, false);
                return decode(reader, file.getFileName().toString());
            } finally {
                reader.dispose();
            }
        }
    }

    private static Gif decode(ImageReader reader, String name) throws IOException {
        int width, height;
        try {
            IIOMetadataNode screen = child((IIOMetadataNode) reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"),
                "LogicalScreenDescriptor");
            width = Integer.parseInt(screen.getAttribute("logicalScreenWidth"));
            height = Integer.parseInt(screen.getAttribute("logicalScreenHeight"));
        } catch (Exception e) {
            width = reader.getWidth(0);
            height = reader.getHeight(0);
        }
        int count = reader.getNumImages(true);
        if ((long) width * height * count > MAX_PIXELS) throw new IOException(name + " is too big");

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        List<Frame> frames = new ArrayList<>();
        String disposal = "none";
        Rectangle previous = null;
        BufferedImage restore = null;
        try {
            for (int i = 0; i < count; i++) {
                // Undo the previous frame as it asked.
                if (previous != null) {
                    if (disposal.equals("restoreToBackgroundColor")) {
                        g.setComposite(AlphaComposite.Clear);
                        g.fillRect(previous.x, previous.y, previous.width, previous.height);
                    } else if (disposal.equals("restoreToPreviousState") && restore != null) {
                        g.setComposite(AlphaComposite.Src);
                        g.drawImage(restore, 0, 0, null);
                    }
                }
                BufferedImage patch = reader.read(i);
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode control = child(root, "GraphicControlExtension");
                IIOMetadataNode descriptor = child(root, "ImageDescriptor");
                String thisDisposal = control != null ? control.getAttribute("disposalMethod") : "none";
                int centis = control != null && !control.getAttribute("delayTime").isEmpty() ? Integer.parseInt(control.getAttribute("delayTime")) : 10;
                int x = descriptor != null ? Integer.parseInt(descriptor.getAttribute("imageLeftPosition")) : 0;
                int y = descriptor != null ? Integer.parseInt(descriptor.getAttribute("imageTopPosition")) : 0;
                if (thisDisposal.equals("restoreToPreviousState")) restore = copy(canvas);

                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(patch, x, y, null);
                // Browsers treat 0-10 ms as 100 ms, and GIFs are made for that.
                int delay = centis * 10 <= 10 ? 100 : centis * 10;
                frames.add(new Frame(canvas.getRGB(0, 0, width, height, null, 0, width), delay));
                disposal = thisDisposal;
                previous = new Rectangle(x, y, patch.getWidth(), patch.getHeight());
            }
        } finally {
            g.dispose();
        }
        if (frames.isEmpty()) throw new IOException(name + " has no frames");
        return new Gif(width, height, frames);
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        if (root == null) return null;
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        return null;
    }
}
